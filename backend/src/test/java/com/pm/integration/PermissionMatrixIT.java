package com.pm.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.dto.MemberAddDTO;
import com.pm.dto.ProjectUpdateDTO;
import com.pm.dto.RequirementCancelDTO;
import com.pm.dto.ScheduleRowSaveDTO;
import com.pm.entity.BizOperationLog;
import com.pm.entity.Bug;
import com.pm.entity.Project;
import com.pm.entity.Requirement;
import com.pm.entity.ScheduleMonth;
import com.pm.entity.ScheduleRow;
import com.pm.entity.User;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.BugMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleMonthMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.BugService;
import com.pm.service.OperationLogService;
import com.pm.service.ProjectService;
import com.pm.service.RequirementService;
import com.pm.service.ScheduleService;
import com.pm.support.AbstractIntegrationTest;
import com.pm.support.Fixtures;
import com.pm.support.TestDates;
import com.pm.vo.OperationLogItemVO;
import com.pm.vo.UserOptionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 权限矩阵集成测试：同一资源用不同身份访问，断言放行 / 403 / 状态 400。
 *
 * <p>覆盖三类身份边界：
 * <ul>
 *   <li>系统管理员（user.is_admin=1）；</li>
 *   <li>项目 PM（project.creator_id）；</li>
 *   <li>项目工种成员 / 无关用户。</li>
 * </ul>
 *
 * <p>这些规则散落在各 Service 内部，是越权风险最集中的区域，
 * 用真实数据库 + 真实服务调用比逐条 mock 更可靠。
 */
@DisplayName("权限矩阵集成测试")
class PermissionMatrixIT extends AbstractIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private RequirementService requirementService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private BugService bugService;
    @Autowired
    private OperationLogService operationLogService;

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
    private BugMapper bugMapper;
    @Autowired
    private BizOperationLogMapper operationLogMapper;

    private User admin;
    private User pm;
    private User pm2;
    private User fe;
    private User outsider;

    private Project project;
    private Project project2;
    private Requirement requirement;
    private Requirement requirement2;
    private ScheduleMonth month;
    private ScheduleRow row;
    private Bug bug;

    @BeforeEach
    void setUp() {
        cleanBusinessTables();

        admin = user("admin", "系统管理员", true);
        pm = user("pm", "项目经理", false);
        pm2 = user("pm2", "另一个项目经理", false);
        fe = user("fe", "前端开发", false);
        outsider = user("out", "无关用户", false);

        project = project("PM1", pm.getId());
        project2 = project("PM2", pm2.getId());
        projectMemberMapper.insert(Fixtures.member(project.getId(), fe.getId(), "FE"));

        requirement = requirement(project.getId(), "REQ-PM1-0001", "需求一", pm.getId());
        requirement2 = requirement(project2.getId(), "REQ-PM2-0001", "需求二", pm2.getId());
        participantMapper.insert(Fixtures.participant(requirement.getId(), fe.getId(), "FE"));

        YearMonth scheduleMonth = YearMonth.now().plusMonths(2);
        month = Fixtures.month(scheduleMonth.getYear(), scheduleMonth.getMonthValue(), pm.getId());
        monthMapper.insert(month);
        row = Fixtures.row(month.getId(), requirement.getId(), "FE", fe.getId(), 1, "1");
        rowMapper.insert(row);
        row = rowMapper.selectById(row.getId());

        bug = new Bug();
        bug.setBugNo("BUG-PM1-0001");
        bug.setProjectId(project.getId());
        bug.setFeature("登录页");
        bug.setPriority(2);
        bug.setTesterId(fe.getId());
        bug.setHandleStatus("DONE");
        bug.setAcceptStatus("PASSED");
        bug.setArchived(0);
        bug.setCreatedBy(fe.getId());
        bugMapper.insert(bug);
        bug = bugMapper.selectById(bug.getId());
    }

    // ==================== 项目 ====================

    @Test
    @DisplayName("项目详情：PM / 成员 / 管理员可见，无关用户 403")
    void projectDetailVisibility() {
        assertThatCode(() -> projectService.getProjectDetail(pm.getId(), project.getId()))
                .doesNotThrowAnyException();
        assertThatCode(() -> projectService.getProjectDetail(fe.getId(), project.getId()))
                .doesNotThrowAnyException();
        assertThatCode(() -> projectService.getProjectDetail(admin.getId(), project.getId()))
                .doesNotThrowAnyException();

        assertBiz(403, () -> projectService.getProjectDetail(outsider.getId(), project.getId()));
    }

    @Test
    @DisplayName("编辑项目 / 添加成员：仅项目 PM，成员与无关用户均 403")
    void projectWriteOnlyPm() {
        ProjectUpdateDTO update = new ProjectUpdateDTO();
        update.setName("新项目名");
        assertBiz(403, () -> projectService.updateProject(fe.getId(), project.getId(), update));
        assertBiz(403, () -> projectService.updateProject(outsider.getId(), project.getId(), update));

        MemberAddDTO member = new MemberAddDTO();
        member.setUserId(outsider.getId());
        member.setCrafts(List.of("QA"));
        assertBiz(403, () -> projectService.addMember(fe.getId(), project.getId(), member));

        // PM 放行
        assertThatCode(() -> projectService.updateProject(pm.getId(), project.getId(), update))
                .doesNotThrowAnyException();
        assertThatCode(() -> projectService.addMember(pm.getId(), project.getId(), member))
                .doesNotThrowAnyException();
        assertThat(projectMapper.selectById(project.getId()).getName()).isEqualTo("新项目名");
        assertThat(projectMemberMapper.selectCount(new LambdaQueryWrapper<com.pm.entity.ProjectMember>()
                .eq(com.pm.entity.ProjectMember::getProjectId, project.getId())
                .eq(com.pm.entity.ProjectMember::getUserId, outsider.getId())
                .eq(com.pm.entity.ProjectMember::getCraft, "QA"))).isEqualTo(1L);
    }

    // ==================== 需求 ====================

    @Test
    @DisplayName("取消需求：仅项目 PM，工种成员 403；PM 取消后进入终态")
    void cancelRequirementOnlyPm() {
        RequirementCancelDTO cancel = new RequirementCancelDTO();
        cancel.setReason("需求作废");

        assertBiz(403, () -> requirementService.cancelRequirement(fe.getId(), requirement.getId(), cancel));

        assertThatCode(() -> requirementService.cancelRequirement(pm.getId(), requirement.getId(), cancel))
                .doesNotThrowAnyException();
        assertThat(requirementMapper.selectById(requirement.getId()).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("归档需求：成员 403；PM 也仅允许 ARCHIVING 状态（否则 400）")
    void archiveRequirementPermissionAndState() {
        assertBiz(403, () -> requirementService.archiveRequirement(fe.getId(), requirement.getId()));
        assertBiz(400, () -> requirementService.archiveRequirement(pm.getId(), requirement.getId()));

        Requirement update = new Requirement();
        update.setId(requirement.getId());
        update.setStatus("ARCHIVING");
        requirementMapper.updateById(update);

        assertThatCode(() -> requirementService.archiveRequirement(pm.getId(), requirement.getId()))
                .doesNotThrowAnyException();
        assertThat(requirementMapper.selectById(requirement.getId()).getStatus()).isEqualTo("ARCHIVED");
    }

    // ==================== 排期 ====================

    @Test
    @DisplayName("保存排期：仅项目 PM，工种成员 403；PM 保存成功")
    void saveScheduleRowOnlyPm() {
        ScheduleRowSaveDTO dto = new ScheduleRowSaveDTO();
        dto.setPlannedEffort(new BigDecimal("1"));
        dto.setCells(Map.of(TestDates.firstWorkday(YearMonth.now().plusMonths(2)).toString(),
                new BigDecimal("0.1")));
        dto.setUpdatedAt(row.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        assertBiz(403, () -> scheduleService.saveRow(fe.getId(), row.getId(), dto));
        assertThatCode(() -> scheduleService.saveRow(pm.getId(), row.getId(), dto))
                .doesNotThrowAnyException();
    }

    // ==================== Bug ====================

    @Test
    @DisplayName("Bug 归档/重开：成员与无关用户 403；PM 归档、管理员重开")
    void bugArchiveAndReopenPermission() {
        assertBiz(403, () -> bugService.archiveBug(fe.getId(), bug.getId()));
        assertBiz(403, () -> bugService.archiveBug(outsider.getId(), bug.getId()));

        assertThatCode(() -> bugService.archiveBug(pm.getId(), bug.getId())).doesNotThrowAnyException();
        assertThat(bugMapper.selectById(bug.getId()).getArchived()).isEqualTo(1);

        assertThatCode(() -> bugService.reopenBug(admin.getId(), bug.getId())).doesNotThrowAnyException();
        Bug reopened = bugMapper.selectById(bug.getId());
        assertThat(reopened.getArchived()).isEqualTo(0);
        assertThat(reopened.getArchivedAt()).isNull();
        assertThat(reopened.getArchivedBy()).isNull();
        assertThat(reopened.getHandleStatus()).isNull();
        assertThat(reopened.getAcceptStatus()).isNull();
    }

    @Test
    @DisplayName("Bug 编辑：成员可编辑未归档行；归档后只读（400）")
    void bugUpdateMemberAndArchivedReadOnly() {
        com.pm.dto.BugUpdateDTO update = new com.pm.dto.BugUpdateDTO();
        update.setFeature("登录页-验证码");
        assertThatCode(() -> bugService.updateBug(fe.getId(), bug.getId(), update))
                .doesNotThrowAnyException();
        assertThat(bugMapper.selectById(bug.getId()).getFeature()).isEqualTo("登录页-验证码");

        bugService.archiveBug(pm.getId(), bug.getId());
        assertBiz(400, () -> bugService.updateBug(fe.getId(), bug.getId(), update));
    }

    // ==================== 操作日志 ====================

    @Test
    @DisplayName("操作日志可见范围：管理员全量、PM 仅自己项目、无关用户空页、越权指定项目 403")
    void operationLogVisibility() {
        insertLog(project.getId(), pm.getId(), "项目一日志");
        insertLog(project2.getId(), pm2.getId(), "项目二日志");

        assertThat(pageLogs(admin.getId(), null).getTotal()).isEqualTo(2);
        assertThat(pageLogs(pm.getId(), null).getTotal()).isEqualTo(1);
        assertThat(pageLogs(pm2.getId(), null).getTotal()).isEqualTo(1);
        assertThat(pageLogs(outsider.getId(), null).getTotal()).isZero();

        assertBiz(403, () -> operationLogService.pageLogs(pm.getId(), null, null, null,
                project2.getId(), null, null, null, 1, 50));

        assertThat(pageLogs(admin.getId(), project.getId()).getTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("操作人下拉与日志同口径：PM 只看到自己项目的操作人")
    void operationLogOperatorsVisibility() {
        insertLog(project.getId(), pm.getId(), "项目一日志");
        insertLog(project2.getId(), pm2.getId(), "项目二日志");

        List<UserOptionVO> pmOperators = operationLogService.listOperators(pm.getId());
        assertThat(pmOperators).hasSize(1);
        assertThat(pmOperators.get(0).getId()).isEqualTo(pm.getId());

        assertThat(operationLogService.listOperators(admin.getId())).hasSize(2);
        assertThat(operationLogService.listOperators(outsider.getId())).isEmpty();
    }

    // ==================== 造数与断言 ====================

    private User user(String username, String realName, boolean isAdmin) {
        User user = Fixtures.user(username, realName, isAdmin);
        userMapper.insert(user);
        return user;
    }

    private Project project(String code, Long creatorId) {
        Project project = Fixtures.project(code, creatorId);
        projectMapper.insert(project);
        return project;
    }

    private Requirement requirement(Long projectId, String reqNo, String title, Long creatorId) {
        Requirement requirement = Fixtures.requirement(projectId, reqNo, title,
                "PENDING", creatorId, creatorId);
        requirementMapper.insert(requirement);
        return requirement;
    }

    private void insertLog(Long projectId, Long operatorId, String remark) {
        BizOperationLog log = new BizOperationLog();
        log.setBizType("REQUIREMENT");
        log.setBizId(projectId.equals(project.getId()) ? requirement.getId() : requirement2.getId());
        log.setProjectId(projectId);
        log.setOperatorId(operatorId);
        log.setAction("CREATE");
        log.setRoundNo(1);
        log.setRemark(remark);
        operationLogMapper.insert(log);
    }

    private Page<OperationLogItemVO> pageLogs(Long userId, Long projectId) {
        return operationLogService.pageLogs(userId, null, null, null, projectId,
                null, null, null, 1, 50);
    }

    private static void assertBiz(int expectedCode, Runnable action) {
        BizException ex = catchThrowableOfType(action::run, BizException.class);
        assertThat(ex).as("应抛出 BizException(code=%s)", expectedCode).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expectedCode);
    }
}
