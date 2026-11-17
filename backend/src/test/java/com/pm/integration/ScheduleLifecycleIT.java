package com.pm.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pm.common.BizException;
import com.pm.dto.ScheduleRequirementDTO;
import com.pm.entity.Project;
import com.pm.entity.Requirement;
import com.pm.entity.ScheduleMonth;
import com.pm.entity.ScheduleRow;
import com.pm.entity.User;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleCellMapper;
import com.pm.mapper.ScheduleMonthMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.RequirementService;
import com.pm.service.ScheduleService;
import com.pm.support.AbstractIntegrationTest;
import com.pm.support.Fixtures;
import com.pm.support.TestDates;
import com.pm.vo.CopyRowResultVO;
import com.pm.vo.ScheduleIncompleteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 排期生命周期集成测试：添加到下月、移除需求、参与人移除联动、退出拦截、加入月表。
 *
 * <p>这些是 ScheduleServiceImpl 中排名期保存之后最复杂的写路径，
 * 涉及跨月行复制、VOID 标记、状态回退，必须用真实 SQL 验证。
 */
@DisplayName("排期生命周期集成测试")
class ScheduleLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private RequirementService requirementService;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private ProjectMemberMapper projectMemberMapper;
    @Autowired
    private RequirementMapper requirementMapper;
    @Autowired
    private RequirementParticipantMapper participantMapper;
    @Autowired
    private ScheduleMonthMapper monthMapper;
    @Autowired
    private ScheduleRowMapper rowMapper;
    @Autowired
    private ScheduleCellMapper cellMapper;

    private User pm;
    private User fe;
    private User qa;
    private Project project;

    @BeforeEach
    void setUp() {
        cleanBusinessTables();
        pm = insertUser("pm", "项目经理");
        fe = insertUser("fe", "前端开发");
        qa = insertUser("qa", "测试");
        project = Fixtures.project("LIF", pm.getId());
        projectMapper.insert(project);
        projectMemberMapper.insert(Fixtures.member(project.getId(), fe.getId(), "FE"));
        projectMemberMapper.insert(Fixtures.member(project.getId(), qa.getId(), "QA"));
    }

    // ==================== 添加到下月 ====================

    @Test
    @DisplayName("整个需求添加到下月：逐组复制、工期沿用、格子空白；重复调用幂等")
    void copyRequirementToNextMonth() {
        Requirement requirement = requirement("REQ-LIF-0001", "PENDING");
        participant(requirement, fe, "FE");
        participant(requirement, qa, "QA");

        YearMonth current = YearMonth.now().plusMonths(1);
        ScheduleMonth month = month(current);
        ScheduleRow feRow = row(month, requirement, "FE", fe, 1, "2");
        row(month, requirement, "QA", qa, 1, "1");

        CopyRowResultVO result = scheduleService.copyRequirementToNextMonth(pm.getId(), feRow.getId());

        assertThat(result.isDuplicated()).isTrue();
        assertThat(result.getInsertedCount()).isEqualTo(2);

        ScheduleMonth nextMonth = monthMapper.selectOne(new LambdaQueryWrapper<ScheduleMonth>()
                .eq(ScheduleMonth::getYear, current.plusMonths(1).getYear())
                .eq(ScheduleMonth::getMonth, current.plusMonths(1).getMonthValue()));
        assertThat(nextMonth).isNotNull();
        assertThat(result.getMonthId()).isEqualTo(nextMonth.getId());

        List<ScheduleRow> copied = rowsOf(nextMonth);
        assertThat(copied).hasSize(2);
        assertThat(copied).extracting(ScheduleRow::getCraft).containsExactlyInAnyOrder("FE", "QA");
        ScheduleRow copiedFe = copied.stream()
                .filter(r -> "FE".equals(r.getCraft()))
                .findFirst()
                .orElseThrow();
        assertThat(copiedFe.getPlannedEffort()).isEqualByComparingTo("2");
        assertThat(cellMapper.selectCount(new LambdaQueryWrapper<com.pm.entity.ScheduleCell>()
                .in(com.pm.entity.ScheduleCell::getRowId,
                        copied.stream().map(ScheduleRow::getId).toList()))).isZero();

        // 幂等：再次复制不新增
        CopyRowResultVO again = scheduleService.copyRequirementToNextMonth(pm.getId(), feRow.getId());
        assertThat(again.isDuplicated()).isFalse();
        assertThat(again.getInsertedCount()).isZero();
        assertThat(rowsOf(nextMonth)).hasSize(2);
    }

    @Test
    @DisplayName("已归档需求不可添加到下月 → 400")
    void copyArchivedRejected() {
        Requirement requirement = requirement("REQ-LIF-0002", "ARCHIVED");
        participant(requirement, fe, "FE");
        ScheduleMonth month = month(YearMonth.now().plusMonths(1));
        ScheduleRow row = row(month, requirement, "FE", fe, 1, "1");

        assertBiz(400, () -> scheduleService.copyRequirementToNextMonth(pm.getId(), row.getId()));
    }

    // ==================== 移除需求 ====================

    @Test
    @DisplayName("移除某月需求：只删当月行与格子，其他月份保留；组不再排满 → 状态回退 PENDING")
    void removeRequirementOnlyAffectsThatMonthAndRollsBack() {
        Requirement requirement = requirement("REQ-LIF-0003", "DEV");
        participant(requirement, fe, "FE");

        YearMonth monthA = YearMonth.now().minusMonths(1);
        YearMonth monthB = YearMonth.now().minusMonths(2);
        ScheduleMonth a = month(monthA);
        ScheduleMonth b = month(monthB);
        ScheduleRow rowA = row(a, requirement, "FE", fe, 1, "2");
        ScheduleRow rowB = row(b, requirement, "FE", fe, 1, "2");
        cellMapper.insert(Fixtures.cell(rowA.getId(), TestDates.firstWorkday(monthA), "1"));
        cellMapper.insert(Fixtures.cell(rowB.getId(), TestDates.firstWorkday(monthB), "1"));

        assertBiz(403, () -> scheduleService.removeRequirement(fe.getId(), a.getId(), requirement.getId()));

        Integer removed = scheduleService.removeRequirement(pm.getId(), a.getId(), requirement.getId());

        assertThat(removed).isEqualTo(1);
        assertThat(rowsOf(a)).isEmpty();
        assertThat(rowsOf(b)).hasSize(1);
        assertThat(requirementMapper.selectById(requirement.getId()).getStatus()).isEqualTo("PENDING");
    }

    // ==================== 参与人移除联动 ====================

    @Test
    @DisplayName("从项目移除工种成员：需求参与人删除、排期行标 VOID（不物理删除）")
    void removeProjectMemberVoidsScheduleRows() {
        Requirement requirement = requirement("REQ-LIF-0004", "PENDING");
        participant(requirement, fe, "FE");
        ScheduleMonth month = month(YearMonth.now().plusMonths(1));
        ScheduleRow row = row(month, requirement, "FE", fe, 1, "1");

        int affected = requirementService.removeParticipantByProjectMember(
                project.getId(), fe.getId(), "FE", pm.getId());

        assertThat(affected).isEqualTo(1);
        assertThat(participantMapper.selectCount(new LambdaQueryWrapper<com.pm.entity.RequirementParticipant>()
                .eq(com.pm.entity.RequirementParticipant::getRequirementId, requirement.getId())
                .eq(com.pm.entity.RequirementParticipant::getUserId, fe.getId()))).isZero();
        ScheduleRow after = rowMapper.selectById(row.getId());
        assertThat(after.getStatus()).isEqualTo("VOID");
        assertThat(requirementMapper.selectById(requirement.getId()).getStatus()).isEqualTo("PENDING");
    }

    // ==================== 退出拦截 ====================

    @Test
    @DisplayName("退出拦截：仅 PM 可见「未排满且开发已开工」的需求；排满后不再拦截")
    void incompleteOnlyForPmAndOnlyWhenNotFull() {
        Requirement requirement = requirement("REQ-LIF-0005", "PENDING");
        participant(requirement, fe, "FE");
        YearMonth past = YearMonth.now().minusMonths(1);
        ScheduleMonth month = month(past);
        ScheduleRow row = row(month, requirement, "FE", fe, 1, "2");
        List<java.time.LocalDate> workdays = TestDates.workdays(past);
        cellMapper.insert(Fixtures.cell(row.getId(), workdays.get(0), "1"));

        List<ScheduleIncompleteVO> pmList = scheduleService.listIncompleteRequirements(pm.getId());
        assertThat(pmList).extracting(ScheduleIncompleteVO::getRequirementId)
                .containsExactly(requirement.getId());
        assertThat(scheduleService.listIncompleteRequirements(fe.getId())).isEmpty();

        // 补齐工期后不再拦截
        cellMapper.insert(Fixtures.cell(row.getId(), workdays.get(1), "1"));
        assertThat(scheduleService.listIncompleteRequirements(pm.getId())).isEmpty();
    }

    // ==================== 加入月表 ====================

    @Test
    @DisplayName("从需求池加入月表：仅 PM 可操作；按参与人生成行；同需求同月不可重复")
    void addRequirementGeneratesRowsAndRequiresPm() {
        Requirement requirement = requirement("REQ-LIF-0006", "PENDING");
        participant(requirement, fe, "FE");
        participant(requirement, qa, "QA");
        ScheduleMonth month = month(YearMonth.now().plusMonths(1));
        ScheduleRequirementDTO dto = new ScheduleRequirementDTO();
        dto.setRequirementId(requirement.getId());

        assertBiz(403, () -> scheduleService.addRequirement(fe.getId(), month.getId(), dto));

        assertThatCode(() -> scheduleService.addRequirement(pm.getId(), month.getId(), dto))
                .doesNotThrowAnyException();
        assertThat(rowsOf(month)).hasSize(2);

        assertBiz(400, () -> scheduleService.addRequirement(pm.getId(), month.getId(), dto));
    }

    // ==================== 造数与断言 ====================

    private User insertUser(String username, String realName) {
        User user = Fixtures.user(username, realName, false);
        userMapper.insert(user);
        return user;
    }

    private Requirement requirement(String reqNo, String status) {
        Requirement requirement = Fixtures.requirement(project.getId(), reqNo, "需求-" + reqNo,
                status, pm.getId(), pm.getId());
        requirementMapper.insert(requirement);
        return requirement;
    }

    private void participant(Requirement requirement, User user, String craft) {
        participantMapper.insert(Fixtures.participant(requirement.getId(), user.getId(), craft));
    }

    private ScheduleMonth month(YearMonth yearMonth) {
        ScheduleMonth month = Fixtures.month(yearMonth.getYear(), yearMonth.getMonthValue(), pm.getId());
        monthMapper.insert(month);
        return month;
    }

    private ScheduleRow row(ScheduleMonth month, Requirement requirement, String craft,
                            User user, int seq, String effort) {
        ScheduleRow row = Fixtures.row(month.getId(), requirement.getId(), craft, user.getId(), seq, effort);
        rowMapper.insert(row);
        return row;
    }

    private List<ScheduleRow> rowsOf(ScheduleMonth month) {
        return rowMapper.selectList(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getMonthId, month.getId()));
    }

    private static void assertBiz(int expectedCode, Runnable action) {
        BizException ex = catchThrowableOfType(action::run, BizException.class);
        assertThat(ex).as("应抛出 BizException(code=%s)", expectedCode).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expectedCode);
    }
}
