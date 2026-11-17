package com.pm.integration;

import com.pm.common.BizException;
import com.pm.dto.ScheduleRowSaveDTO;
import com.pm.entity.Project;
import com.pm.entity.Requirement;
import com.pm.entity.ScheduleCell;
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
import com.pm.service.ScheduleService;
import com.pm.support.AbstractIntegrationTest;
import com.pm.support.Fixtures;
import com.pm.support.TestDates;
import com.pm.vo.ScheduleRowSaveResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 排期保存（{@link ScheduleService#saveRow}）第一批集成测试。
 *
 * <p>为什么是集成测试而不是单测：saveRow 的校验链依赖真实 SQL（同人同日合计、
 * 组内跨月合计）与事务行为，mock Mapper 只能测到分支外壳，测不出 SQL 口径。
 *
 * <p>数据策略：每个用例清空业务表后按最小场景造数；统一使用「下个月」的日期，
 * 保证最大格子日 ≥ 今天 → 派生结果稳定为 DEV，不受运行当天日期影响。
 */
@DisplayName("排期保存 saveRow 集成测试")
class ScheduleSaveIT extends AbstractIntegrationTest {

    @Autowired
    private ScheduleService scheduleService;
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
    private Project project;

    @BeforeEach
    void cleanUp() {
        cleanBusinessTables();
        pm = Fixtures.user("pm", "项目经理", false);
        fe = Fixtures.user("fe", "前端开发", false);
        userMapper.insert(pm);
        userMapper.insert(fe);

        project = Fixtures.project("TST", pm.getId());
        projectMapper.insert(project);
        projectMemberMapper.insert(Fixtures.member(project.getId(), fe.getId(), "FE"));
    }

    // ==================== 正常保存 ====================

    @Test
    @DisplayName("保存格子与工期成功：格子落库、返回新版本、开发窗口未结束 → 派生 DEV")
    void saveSuccessPersistsCellsAndDerivesDev() {
        YearMonth month = YearMonth.now().plusMonths(1);
        LocalDate day = firstWorkday(month);
        ScheduleRow row = seedRow(month, "0.5", "PENDING");

        ScheduleRowSaveResultVO result = scheduleService.saveRow(pm.getId(), row.getId(),
                dto("0.5", Map.of(day.toString(), new BigDecimal("0.5")), toMillis(row.getUpdatedAt())));

        assertThat(result.getUpdatedAt()).isNotNull();
        assertThat(result.getOverflow()).isNull();

        List<ScheduleCell> cells = cellMapper.selectList(null);
        assertThat(cells).hasSize(1);
        assertThat(cells.get(0).getDay()).isEqualTo(day);
        assertThat(cells.get(0).getValue()).isEqualByComparingTo("0.5");

        assertThat(requirementMapper.selectById(requirementId).getStatus()).isEqualTo("DEV");
    }

    // ==================== 乐观锁 ====================

    @Test
    @DisplayName("updatedAt 与库中不一致 → 409，提示刷新重试")
    void rejectsStaleVersion() {
        YearMonth month = YearMonth.now().plusMonths(1);
        ScheduleRow row = seedRow(month, "1", "PENDING");

        BizException ex = catchThrowableOfType(() -> scheduleService.saveRow(pm.getId(), row.getId(),
                        dto("1", Map.of(firstWorkday(month).toString(), new BigDecimal("0.5")),
                                toMillis(row.getUpdatedAt()) + 1000)),
                BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("已被他人修改");
    }

    // ==================== 格子校验 ====================

    @Test
    @DisplayName("非工作日（周六）填格子 → 400「为非工作日」")
    void rejectsNonWorkday() {
        YearMonth month = YearMonth.now().plusMonths(1);
        LocalDate saturday = firstDayOfWeek(month, DayOfWeek.SATURDAY);
        ScheduleRow row = seedRow(month, "1", "PENDING");

        BizException ex = catchThrowableOfType(() -> scheduleService.saveRow(pm.getId(), row.getId(),
                        dto("1", Map.of(saturday.toString(), new BigDecimal("0.5")),
                                toMillis(row.getUpdatedAt()))),
                BizException.class);

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("为非工作日");
        assertThat(cellMapper.selectList(null)).isEmpty();
    }

    @Test
    @DisplayName("工作量不是 0.1 的整数倍（0.15）→ 400")
    void rejectsInvalidStep() {
        YearMonth month = YearMonth.now().plusMonths(1);
        ScheduleRow row = seedRow(month, "1", "PENDING");

        BizException ex = catchThrowableOfType(() -> scheduleService.saveRow(pm.getId(), row.getId(),
                        dto("1", Map.of(firstWorkday(month).toString(), new BigDecimal("0.15")),
                                toMillis(row.getUpdatedAt()))),
                BizException.class);

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("0.1的整数倍");
    }

    @Test
    @DisplayName("单格工作量超过 1 → 400")
    void rejectsCellValueOverOne() {
        YearMonth month = YearMonth.now().plusMonths(1);
        ScheduleRow row = seedRow(month, "2", "PENDING");

        BizException ex = catchThrowableOfType(() -> scheduleService.saveRow(pm.getId(), row.getId(),
                        dto("2", Map.of(firstWorkday(month).toString(), new BigDecimal("1.5")),
                                toMillis(row.getUpdatedAt()))),
                BizException.class);

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("大于0且不超过1");
    }

    // ==================== 跨行/跨月合计 ====================

    @Test
    @DisplayName("组内跨月已排合计超过计划工期 → 400，提示先改工期")
    void rejectsGroupTotalOverPlannedEffort() {
        YearMonth month = YearMonth.now().plusMonths(1);
        List<LocalDate> workdays = workdays(month);
        ScheduleRow row = seedRow(month, "0.5", "PENDING");

        Map<String, BigDecimal> cells = new LinkedHashMap<>();
        cells.put(workdays.get(0).toString(), new BigDecimal("0.5"));
        cells.put(workdays.get(1).toString(), new BigDecimal("0.5"));

        BizException ex = catchThrowableOfType(() -> scheduleService.saveRow(pm.getId(), row.getId(),
                        dto("0.5", cells, toMillis(row.getUpdatedAt()))),
                BizException.class);

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("超过计划工期");
    }

    @Test
    @DisplayName("同人同日跨需求合计超过 1 → 400，提示该日工作量已超过 1")
    void rejectsUserDayTotalOverOne() {
        YearMonth month = YearMonth.now().plusMonths(1);
        LocalDate day = firstWorkday(month);
        ScheduleRow row = seedRow(month, "1", "PENDING");

        // 另一个需求：同一负责人同一天已排 0.6
        ScheduleMonth sameMonth = monthMapper.selectById(row.getMonthId());
        Requirement otherReq = Fixtures.requirement(project.getId(), "REQ-TST-0002", "另一个需求",
                "PENDING", pm.getId(), pm.getId());
        requirementMapper.insert(otherReq);
        ScheduleRow otherRow = Fixtures.row(sameMonth.getId(), otherReq.getId(), "FE", fe.getId(), 2, "1");
        rowMapper.insert(otherRow);
        cellMapper.insert(Fixtures.cell(otherRow.getId(), day, "0.6"));

        BizException ex = catchThrowableOfType(() -> scheduleService.saveRow(pm.getId(), row.getId(),
                        dto("1", Map.of(day.toString(), new BigDecimal("0.5")),
                                toMillis(row.getUpdatedAt()))),
                BizException.class);

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("工作量已超过1");
    }

    // ==================== 状态与顺延 ====================

    @Test
    @DisplayName("已归档需求的排期行不可保存 → 400「该需求已归档」")
    void rejectsArchivedRequirement() {
        YearMonth month = YearMonth.now().plusMonths(1);
        ScheduleRow row = seedRow(month, "1", "ARCHIVED");

        BizException ex = catchThrowableOfType(() -> scheduleService.saveRow(pm.getId(), row.getId(),
                        dto("1", Map.of(firstWorkday(month).toString(), new BigDecimal("0.5")),
                                toMillis(row.getUpdatedAt()))),
                BizException.class);

        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("该需求已归档");
    }

    @Test
    @DisplayName("当月工作日全部填满但仍未达工期 → 自动创建下月表并追加 is_overflow 行")
    void createsOverflowRowInNextMonth() {
        YearMonth month = YearMonth.now().plusMonths(1);
        List<LocalDate> workdays = workdays(month);
        // 工期 = 当月可填天数 + 1，保证填满当月仍差 1
        String effort = String.valueOf(workdays.size() + 1);
        ScheduleRow row = seedRow(month, effort, "PENDING");

        Map<String, BigDecimal> cells = new LinkedHashMap<>();
        for (LocalDate workday : workdays) {
            cells.put(workday.toString(), BigDecimal.ONE);
        }

        ScheduleRowSaveResultVO result = scheduleService.saveRow(pm.getId(), row.getId(),
                dto(effort, cells, toMillis(row.getUpdatedAt())));

        assertThat(result.getOverflow()).isNotNull();
        assertThat(result.getOverflow().getCarriedEffort()).isEqualByComparingTo("1");

        YearMonth next = month.plusMonths(1);
        ScheduleMonth nextMonth = monthMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScheduleMonth>()
                        .eq(ScheduleMonth::getYear, next.getYear())
                        .eq(ScheduleMonth::getMonth, next.getMonthValue()));
        assertThat(nextMonth).isNotNull();

        ScheduleRow overflowRow = rowMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScheduleRow>()
                        .eq(ScheduleRow::getMonthId, nextMonth.getId())
                        .eq(ScheduleRow::getRequirementId, requirementId)
                        .eq(ScheduleRow::getCraft, "FE")
                        .eq(ScheduleRow::getUserId, fe.getId()));
        assertThat(overflowRow).isNotNull();
        assertThat(overflowRow.getIsOverflow()).isEqualTo(1);
        assertThat(overflowRow.getPlannedEffort()).isEqualByComparingTo(effort);
        assertThat(overflowRow.getStatus()).isEqualTo("TODO");
    }

    @Test
    @DisplayName("12 月排满仍差工期 → 顺延到下一年 1 月（跨年进位）")
    void overflowCrossesYearBoundary() {
        YearMonth december = YearMonth.of(2030, 12);
        List<LocalDate> workdays = workdays(december);
        String effort = String.valueOf(workdays.size() + 1);
        ScheduleRow row = seedRow(december, effort, "PENDING");

        Map<String, BigDecimal> cells = new LinkedHashMap<>();
        for (LocalDate workday : workdays) {
            cells.put(workday.toString(), BigDecimal.ONE);
        }

        ScheduleRowSaveResultVO result = scheduleService.saveRow(pm.getId(), row.getId(),
                dto(effort, cells, toMillis(row.getUpdatedAt())));

        assertThat(result.getOverflow()).isNotNull();
        assertThat(result.getOverflow().getYear()).isEqualTo(2031);
        assertThat(result.getOverflow().getMonth()).isEqualTo(1);
        assertThat(result.getOverflow().getCarriedEffort()).isEqualByComparingTo("1");

        ScheduleRow overflowRow = rowMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScheduleRow>()
                        .eq(ScheduleRow::getRequirementId, requirementId)
                        .eq(ScheduleRow::getIsOverflow, 1));
        assertThat(overflowRow).isNotNull();
        assertThat(overflowRow.getPlannedEffort()).isEqualByComparingTo(effort);
    }

    // ==================== 造数与工具 ====================

    private Long requirementId;

    /**
     * 建「项目 + 需求 + 参与人 + 月表 + 排期行」最小场景，返回带数据库时间戳的排期行
     */
    private ScheduleRow seedRow(YearMonth month, String plannedEffort, String reqStatus) {
        Requirement requirement = Fixtures.requirement(project.getId(), "REQ-TST-0001", "测试需求",
                reqStatus, pm.getId(), pm.getId());
        requirementMapper.insert(requirement);
        requirementId = requirement.getId();
        participantMapper.insert(Fixtures.participant(requirementId, fe.getId(), "FE"));

        ScheduleMonth scheduleMonth = Fixtures.month(month.getYear(), month.getMonthValue(), pm.getId());
        monthMapper.insert(scheduleMonth);

        ScheduleRow row = Fixtures.row(scheduleMonth.getId(), requirementId, "FE", fe.getId(), 1, plannedEffort);
        rowMapper.insert(row);
        // 重新查询拿到 DB 默认生成的 created_at/updated_at（乐观锁版本）
        return rowMapper.selectById(row.getId());
    }

    private static ScheduleRowSaveDTO dto(String plannedEffort, Map<String, BigDecimal> cells, Long updatedAt) {
        ScheduleRowSaveDTO dto = new ScheduleRowSaveDTO();
        dto.setPlannedEffort(new BigDecimal(plannedEffort));
        dto.setCells(cells);
        dto.setUpdatedAt(updatedAt);
        return dto;
    }

    private static Long toMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static List<LocalDate> workdays(YearMonth month) {
        return TestDates.workdays(month);
    }

    private static LocalDate firstWorkday(YearMonth month) {
        return TestDates.firstWorkday(month);
    }

    private static LocalDate firstDayOfWeek(YearMonth month, DayOfWeek dayOfWeek) {
        return TestDates.firstDayOfWeek(month, dayOfWeek);
    }
}
