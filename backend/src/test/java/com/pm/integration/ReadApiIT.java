package com.pm.integration;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.dto.ChangePasswordDTO;
import com.pm.dto.LoginDTO;
import com.pm.dto.LoginVO;
import com.pm.dto.ResetPasswordDTO;
import com.pm.dto.UserCreateDTO;
import com.pm.dto.UserStatusDTO;
import com.pm.dto.UserUpdateDTO;
import com.pm.dto.CalendarUpdateDTO;
import com.pm.entity.BizOperationLog;
import com.pm.entity.Bug;
import com.pm.entity.Project;
import com.pm.entity.Requirement;
import com.pm.entity.ScheduleCell;
import com.pm.entity.ScheduleMonth;
import com.pm.entity.ScheduleRow;
import com.pm.entity.User;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.BugMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleCellMapper;
import com.pm.mapper.ScheduleMonthMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.AuthService;
import com.pm.service.CalendarService;
import com.pm.service.DashboardService;
import com.pm.service.NotificationService;
import com.pm.service.OperationLogService;
import com.pm.service.ReportsService;
import com.pm.service.SearchService;
import com.pm.service.UserService;
import com.pm.support.AbstractIntegrationTest;
import com.pm.support.Fixtures;
import com.pm.vo.CalendarDayVO;
import com.pm.vo.CalendarMonthVO;
import com.pm.vo.DashboardRequirementVO;
import com.pm.vo.DashboardSummaryVO;
import com.pm.vo.NotificationVO;
import com.pm.vo.OperationLogItemVO;
import com.pm.vo.ReportProjectOverviewVO;
import com.pm.vo.ReportRequirementStatusVO;
import com.pm.vo.ReportWorkloadVO;
import com.pm.vo.SearchResultVO;
import com.pm.vo.UserOptionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 读侧服务集成测试：认证、人员、通知、工作台、报表、全局搜索、工作日历、操作日志筛选。
 *
 * <p>这些接口此前只有 Controller 切片（Service 全 mock），真实 SQL / BCrypt / Redis 路径零覆盖。
 * 本类统一走真实 MySQL + Redis，用最小造数验证「口径」而不重复切片层的参数校验。
 */
@DisplayName("读侧服务集成测试")
class ReadApiIT extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private ReportsService reportsService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private CalendarService calendarService;
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
    private ScheduleCellMapper cellMapper;
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
    private Requirement reqPending;
    private Requirement reqTerminal;

    @BeforeEach
    void setUp() {
        cleanBusinessTables();

        admin = user("admin", "系统管理员", true);
        pm = user("pm", "项目经理", false);
        pm2 = user("pm2", "另一个项目经理", false);
        fe = user("fe", "前端开发", false);
        outsider = user("outsider", "无关用户", false);

        project = project("ACE", pm.getId());
        project2 = project("XYZ", pm2.getId());
        member(project.getId(), fe.getId(), "FE");

        reqPending = requirement(project.getId(), "REQ-ACE-0001", "登录需求", "PENDING", pm.getId());
        reqTerminal = requirement(project2.getId(), "REQ-XYZ-0001", "已归档需求", "ARCHIVED", pm2.getId());
    }

    // ==================== 认证 ====================

    @Test
    @DisplayName("登录成功：BCrypt 校验通过、签发 token、isPm 按项目创建者判定")
    void loginSuccessAndMe() {
        LoginVO vo = authService.login(login("pm", "Pm@123456"));

        assertThat(vo.getToken()).isNotBlank();
        assertThat(vo.getUserId()).isEqualTo(pm.getId());
        assertThat(vo.getUsername()).isEqualTo("pm");
        assertThat(vo.getIsAdmin()).isZero();
        assertThat(vo.getIsPm()).isEqualTo(1);
        assertThat(vo.getForceChangePwd()).isZero();

        com.pm.dto.UserVO me = authService.me(pm.getId());
        assertThat(me.getUserId()).isEqualTo(pm.getId());
        assertThat(me.getRealName()).isEqualTo("项目经理");
        assertThat(me.getIsPm()).isEqualTo(1);
    }

    @Test
    @DisplayName("登录失败：密码错误与账号停用都拒绝，且不签发 token")
    void loginRejectsBadCredentials() {
        BizException wrongPassword = catchThrowableOfType(
                () -> authService.login(login("pm", "wrong-password")), BizException.class);
        assertThat(wrongPassword).isNotNull();
        assertThat(wrongPassword.getMessage()).isEqualTo("用户名或密码错误");

        User disabled = user("off", "停用用户", false);
        User update = new User();
        update.setId(disabled.getId());
        update.setStatus(0);
        userMapper.updateById(update);

        BizException disabledError = catchThrowableOfType(
                () -> authService.login(login("off", "Pm@123456")), BizException.class);
        assertThat(disabledError).isNotNull();
        assertThat(disabledError.getMessage()).contains("账号已停用");
    }

    @Test
    @DisplayName("修改密码：校验原密码、落新 BCrypt 哈希并清除强制改密标记")
    void changePasswordFlow() {
        User force = new User();
        force.setId(pm.getId());
        force.setForceChangePwd(1);
        userMapper.updateById(force);

        ChangePasswordDTO wrong = new ChangePasswordDTO();
        wrong.setOldPassword("bad");
        wrong.setNewPassword("New@123456");
        BizException ex = catchThrowableOfType(
                () -> authService.changePassword(pm.getId(), wrong), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).isEqualTo("原密码错误");

        ChangePasswordDTO dto = new ChangePasswordDTO();
        dto.setOldPassword("Pm@123456");
        dto.setNewPassword("New@123456");
        authService.changePassword(pm.getId(), dto);

        assertThat(userMapper.selectById(pm.getId()).getForceChangePwd()).isZero();
        assertThatCode(() -> authService.login(login("pm", "New@123456"))).doesNotThrowAnyException();
        BizException oldPassword = catchThrowableOfType(
                () -> authService.login(login("pm", "Pm@123456")), BizException.class);
        assertThat(oldPassword).isNotNull();
    }

    // ==================== 人员管理 ====================

    @Test
    @DisplayName("新增人员：空密码使用默认密码，首次登录强制改密，用户名重复 400")
    void createUserWithDefaultPassword() {
        UserCreateDTO dto = new UserCreateDTO();
        dto.setUsername("newuser");
        dto.setRealName("新人");
        userService.createUser(dto);

        User created = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .eq(User::getUsername, "newuser"));
        assertThat(created).isNotNull();
        assertThat(created.getPassword()).startsWith("$2");
        assertThat(created.getIsAdmin()).isZero();
        assertThat(created.getStatus()).isEqualTo(1);
        assertThat(created.getForceChangePwd()).isEqualTo(1);

        LoginVO login = authService.login(login("newuser", "Pm@123456"));
        assertThat(login.getForceChangePwd()).isEqualTo(1);
        assertThat(login.getIsPm()).isZero();

        BizException duplicate = catchThrowableOfType(
                () -> userService.createUser(dto), BizException.class);
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.getCode()).isEqualTo(400);
        assertThat(duplicate.getMessage()).isEqualTo("用户名已存在");
    }

    @Test
    @DisplayName("人员分页：keyword 匹配姓名/用户名，status 过滤，出参不含密码")
    void pageUsersFilters() {
        Page<com.pm.vo.UserVO> byKeyword = userService.pageUsers("前端", null, 1, 10);
        assertThat(byKeyword.getTotal()).isEqualTo(1);
        assertThat(byKeyword.getRecords().get(0).getId()).isEqualTo(fe.getId());
        assertThat(byKeyword.getRecords().get(0).getUsername()).isEqualTo("fe");

        User disabled = user("off2", "停用用户二", false);
        User update = new User();
        update.setId(disabled.getId());
        update.setStatus(0);
        userMapper.updateById(update);

        Page<com.pm.vo.UserVO> byStatus = userService.pageUsers(null, 0, 1, 10);
        assertThat(byStatus.getTotal()).isEqualTo(1);
        assertThat(byStatus.getRecords().get(0).getId()).isEqualTo(disabled.getId());
    }

    @Test
    @DisplayName("编辑/重置密码/启停：重置后默认密码可登录，禁止停用自己，下拉过滤停用用户")
    void updateResetPasswordAndStatus() {
        UserUpdateDTO update = new UserUpdateDTO();
        update.setRealName("前端开发-改");
        userService.updateUser(fe.getId(), update);
        assertThat(userMapper.selectById(fe.getId()).getRealName()).isEqualTo("前端开发-改");

        ResetPasswordDTO reset = new ResetPasswordDTO();
        reset.setPassword(null);
        userService.resetPassword(fe.getId(), reset);
        LoginVO login = authService.login(login("fe", "Pm@123456"));
        assertThat(login.getForceChangePwd()).isEqualTo(1);

        UserStatusDTO stopSelf = new UserStatusDTO();
        stopSelf.setStatus(0);
        BizException self = catchThrowableOfType(
                () -> userService.updateStatus(pm.getId(), pm.getId(), stopSelf), BizException.class);
        assertThat(self).isNotNull();
        assertThat(self.getCode()).isEqualTo(400);
        assertThat(self.getMessage()).isEqualTo("不允许停用自己");

        userService.updateStatus(pm.getId(), fe.getId(), stopSelf);
        assertThat(userMapper.selectById(fe.getId()).getStatus()).isZero();
        assertThat(userService.listOptions()).extracting(UserOptionVO::getId).doesNotContain(fe.getId());
    }

    // ==================== 通知 ====================

    @Test
    @DisplayName("通知：未读数、id 倒序分页、全部已读")
    void notificationReadSide() {
        notificationService.send(fe.getId(), "REQ_PARTICIPANT_ADDED", "标题一", "内容一",
                "REQUIREMENT", reqPending.getId());
        notificationService.send(fe.getId(), "REQ_PARTICIPANT_ADDED", "标题二", "内容二",
                "REQUIREMENT", reqPending.getId());

        assertThat(notificationService.unreadCount(fe.getId())).isEqualTo(2);

        Page<NotificationVO> page = notificationService.pageMyNotifications(fe.getId(), 1, 10);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords()).extracting(NotificationVO::getTitle)
                .containsExactly("标题二", "标题一");

        notificationService.markAllRead(fe.getId());
        assertThat(notificationService.unreadCount(fe.getId())).isZero();
    }

    // ==================== 工作台 ====================

    @Test
    @DisplayName("工作台汇总：未读数、非终态参与需求、本月工时、待处理 Bug 四项口径")
    void dashboardSummary() {
        participantMapper.insert(Fixtures.participant(reqPending.getId(), fe.getId(), "FE"));
        Requirement cancelled = requirement(project.getId(), "REQ-ACE-0002", "已取消需求",
                "CANCELLED", pm.getId());
        participantMapper.insert(Fixtures.participant(cancelled.getId(), fe.getId(), "QA"));

        notificationService.send(fe.getId(), "REQ_PARTICIPANT_ADDED", "新通知", "内容",
                "REQUIREMENT", reqPending.getId());

        YearMonth now = YearMonth.now();
        ScheduleMonth month = Fixtures.month(now.getYear(), now.getMonthValue(), pm.getId());
        monthMapper.insert(month);
        ScheduleRow row = Fixtures.row(month.getId(), reqPending.getId(), "FE", fe.getId(), 1, "1");
        rowMapper.insert(row);
        ScheduleCell cell = Fixtures.cell(row.getId(), LocalDate.now(), "2.5");
        cellMapper.insert(cell);

        insertBug("BUG-ACE-0001", project.getId(), "登录失败", fe.getId(), null, 0);
        insertBug("BUG-ACE-0002", project.getId(), "已处理", fe.getId(), "DONE", 0);
        insertBug("BUG-ACE-0003", project.getId(), "归档 Bug", fe.getId(), null, 1);

        DashboardSummaryVO summary = dashboardService.summary(fe.getId());
        assertThat(summary.getUnreadNotifications()).isEqualTo(1);
        assertThat(summary.getMyRequirementCount()).isEqualTo(1);
        assertThat(summary.getMonthEffort()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(summary.getOpenBugCount()).isEqualTo(1);

        List<DashboardRequirementVO> mine = dashboardService.myRequirements(fe.getId());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getReqNo()).isEqualTo("REQ-ACE-0001");
        assertThat(mine.get(0).getProjectName()).isEqualTo("测试项目-ACE");
        assertThat(mine.get(0).getStatus()).isEqualTo("PENDING");
    }

    // ==================== 报表 ====================

    @Test
    @DisplayName("报表：需求状态分布与项目概览（成员去重、非终态需求、未归档 Bug）")
    void reportsStatusAndOverview() {
        // setUp 已把 fe 加为 FE 成员，这里再加工种 QA，验证「一人多工种只算一人」
        member(project.getId(), fe.getId(), "QA");
        insertBug("BUG-ACE-0001", project.getId(), "登录失败", fe.getId(), null, 0);
        insertBug("BUG-XYZ-0001", project2.getId(), "归档 Bug", pm2.getId(), null, 1);

        List<ReportRequirementStatusVO> status = reportsService.requirementStatus();
        assertThat(status).anySatisfy(vo -> {
            assertThat(vo.getProjectId()).isEqualTo(project.getId());
            assertThat(vo.getStatus()).isEqualTo("PENDING");
            assertThat(vo.getCount()).isEqualTo(1L);
        });
        assertThat(status).anySatisfy(vo -> {
            assertThat(vo.getProjectId()).isEqualTo(project2.getId());
            assertThat(vo.getStatus()).isEqualTo("ARCHIVED");
            assertThat(vo.getCount()).isEqualTo(1L);
        });

        List<ReportProjectOverviewVO> overview = reportsService.projectOverview();
        ReportProjectOverviewVO p1 = overview.stream()
                .filter(vo -> vo.getProjectId().equals(project.getId())).findFirst().orElseThrow();
        assertThat(p1.getMemberCount()).isEqualTo(1L);
        assertThat(p1.getRequirementCount()).isEqualTo(1L);
        assertThat(p1.getOpenBugCount()).isEqualTo(1L);

        ReportProjectOverviewVO p2 = overview.stream()
                .filter(vo -> vo.getProjectId().equals(project2.getId())).findFirst().orElseThrow();
        assertThat(p2.getMemberCount()).isZero();
        assertThat(p2.getRequirementCount()).isZero();
        assertThat(p2.getOpenBugCount()).isZero();
    }

    @Test
    @DisplayName("报表人员负载：按人员×月份聚合，排除 VOID 排期行")
    void reportsWorkloadExcludesVoidRows() {
        YearMonth now = YearMonth.now();
        ScheduleMonth month = Fixtures.month(now.getYear(), now.getMonthValue(), pm.getId());
        monthMapper.insert(month);

        ScheduleRow todo = Fixtures.row(month.getId(), reqPending.getId(), "FE", fe.getId(), 1, "1");
        rowMapper.insert(todo);
        cellMapper.insert(Fixtures.cell(todo.getId(), LocalDate.now(), "2.5"));

        ScheduleRow voidRow = Fixtures.row(month.getId(), reqTerminal.getId(), "QA", fe.getId(), 2, "1");
        voidRow.setStatus("VOID");
        rowMapper.insert(voidRow);
        cellMapper.insert(Fixtures.cell(voidRow.getId(), LocalDate.now(), "3.0"));

        List<ReportWorkloadVO> workload = reportsService.workload(6);
        assertThat(workload).hasSize(1);
        ReportWorkloadVO vo = workload.get(0);
        assertThat(vo.getUserId()).isEqualTo(fe.getId());
        assertThat(vo.getRealName()).isEqualTo("前端开发");
        assertThat(vo.getYear()).isEqualTo(now.getYear());
        assertThat(vo.getMonth()).isEqualTo(now.getMonthValue());
        assertThat(vo.getEffort()).isEqualByComparingTo(new BigDecimal("2.5"));
    }

    // ==================== 全局搜索 ====================

    @Test
    @DisplayName("搜索：空白关键词返回全空；项目/Bug 按可见项目过滤，需求全员可见")
    void searchVisibility() {
        insertBug("BUG-ACE-0001", project.getId(), "登录失败", fe.getId(), null, 0);
        insertBug("BUG-XYZ-0001", project2.getId(), "登录超时", pm2.getId(), null, 0);

        SearchResultVO blank = searchService.search(fe.getId(), "   ");
        assertThat(blank.getRequirements()).isEmpty();
        assertThat(blank.getBugs()).isEmpty();
        assertThat(blank.getProjects()).isEmpty();
        assertThat(blank.getUsers()).isEmpty();

        SearchResultVO normal = searchService.search(fe.getId(), "登录");
        assertThat(normal.getRequirements()).hasSize(1);
        assertThat(normal.getRequirements().get(0).getReqNo()).isEqualTo("REQ-ACE-0001");
        assertThat(normal.getBugs()).hasSize(1);
        assertThat(normal.getBugs().get(0).getBugNo()).isEqualTo("BUG-ACE-0001");

        SearchResultVO adminResult = searchService.search(admin.getId(), "登录");
        assertThat(adminResult.getBugs()).hasSize(2);

        SearchResultVO users = searchService.search(admin.getId(), "前端");
        assertThat(users.getUsers()).hasSize(1);
        assertThat(users.getUsers().get(0).getUsername()).isEqualTo("fe");
    }

    // ==================== 工作日历 ====================

    @Test
    @DisplayName("日历：整月每天返回、周末按规则推导、维护后立即可见、参数越界 400")
    void calendarMonthAndMaintenance() {
        YearMonth now = YearMonth.now();
        CalendarMonthVO month = calendarService.getMonth(now.getYear(), now.getMonthValue());
        assertThat(month.getYear()).isEqualTo(now.getYear());
        assertThat(month.getMonth()).isEqualTo(now.getMonthValue());
        assertThat(month.getDays()).hasSize(now.lengthOfMonth());
        assertThat(month.getDays().get(0).getDay()).isEqualTo(now.atDay(1).toString());

        CalendarDayVO saturday = month.getDays().stream()
                .filter(day -> LocalDate.parse(day.getDay()).getDayOfWeek() == DayOfWeek.SATURDAY)
                .findFirst().orElseThrow();
        assertThat(saturday.getType()).isEqualTo("WEEKEND");

        // 取当月第一个工作日维护：避免改到周六而影响上面的周末推导断言（重复执行也稳定）
        LocalDate cursor = now.atDay(1);
        while (cursor.getDayOfWeek() == DayOfWeek.SATURDAY
                || cursor.getDayOfWeek() == DayOfWeek.SUNDAY) {
            cursor = cursor.plusDays(1);
        }
        LocalDate target = cursor;
        CalendarUpdateDTO holiday = new CalendarUpdateDTO();
        holiday.setType("HOLIDAY");
        holiday.setName("测试节");
        calendarService.updateDay(target, holiday);

        CalendarMonthVO after = calendarService.getMonth(now.getYear(), now.getMonthValue());
        CalendarDayVO updated = after.getDays().stream()
                .filter(day -> day.getDay().equals(target.toString()))
                .findFirst().orElseThrow();
        assertThat(updated.getType()).isEqualTo("HOLIDAY");
        assertThat(updated.getName()).isEqualTo("测试节");

        CalendarUpdateDTO badType = new CalendarUpdateDTO();
        badType.setType("FOO");
        assertBiz(400, () -> calendarService.updateDay(target, badType));
        assertBiz(400, () -> calendarService.getMonth(null, 1));
        assertBiz(400, () -> calendarService.getMonth(2026, 13));
        assertBiz(400, () -> calendarService.getMonth(1999, 1));
    }

    // ==================== 操作日志筛选 ====================

    @Test
    @DisplayName("操作日志：业务类型/动作/操作人/项目/关键词/日期区间筛选")
    void operationLogFilters() {
        insertBug("BUG-ACE-0001", project.getId(), "登录失败", fe.getId(), null, 0);
        insertLog("REQUIREMENT", reqPending.getId(), project.getId(), pm.getId(), "CREATE", "创建需求");
        insertLog("BUG", bugMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Bug>()
                        .eq(Bug::getBugNo, "BUG-ACE-0001")).getId(),
                project.getId(), fe.getId(), "UPDATE", "登录模块调整");

        assertThat(pageLogs(admin.getId(), null, null, null, null, null).getTotal()).isEqualTo(2);
        assertThat(pageLogs(admin.getId(), "BUG", null, null, null, null).getTotal()).isEqualTo(1);
        assertThat(pageLogs(admin.getId(), null, "CREATE", null, null, null).getTotal()).isEqualTo(1);
        assertThat(pageLogs(admin.getId(), null, null, fe.getId(), null, null).getTotal()).isEqualTo(1);
        assertThat(pageLogs(admin.getId(), null, null, null, project.getId(), null).getTotal()).isEqualTo(2);
        assertThat(pageLogs(admin.getId(), null, null, null, null, "登录").getTotal()).isEqualTo(1);
        assertThat(operationLogService.pageLogs(admin.getId(), null, null, null, null, null,
                LocalDate.now(), LocalDate.now(), 1, 50).getTotal()).isEqualTo(2);

        Page<OperationLogItemVO> page = pageLogs(admin.getId(), "REQUIREMENT", null, null, null, null);
        assertThat(page.getRecords().get(0).getBizNo()).isEqualTo("REQ-ACE-0001");
        assertThat(page.getRecords().get(0).getOperatorName()).isEqualTo("项目经理");
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

    private void member(Long projectId, Long userId, String craft) {
        projectMemberMapper.insert(Fixtures.member(projectId, userId, craft));
    }

    private Requirement requirement(Long projectId, String reqNo, String title, String status, Long creatorId) {
        Requirement requirement = Fixtures.requirement(projectId, reqNo, title, status, creatorId, creatorId);
        requirementMapper.insert(requirement);
        return requirement;
    }

    private void insertBug(String bugNo, Long projectId, String feature, Long ownerId,
                           String handleStatus, int archived) {
        Bug bug = new Bug();
        bug.setBugNo(bugNo);
        bug.setProjectId(projectId);
        bug.setFeature(feature);
        bug.setPriority(2);
        bug.setOwnerId(ownerId);
        bug.setTesterId(ownerId);
        bug.setHandleStatus(handleStatus);
        bug.setArchived(archived);
        bug.setCreatedBy(ownerId);
        bugMapper.insert(bug);
    }

    private void insertLog(String bizType, Long bizId, Long projectId, Long operatorId,
                           String action, String remark) {
        BizOperationLog log = new BizOperationLog();
        log.setBizType(bizType);
        log.setBizId(bizId);
        log.setProjectId(projectId);
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setRoundNo(1);
        log.setRemark(remark);
        operationLogMapper.insert(log);
    }

    private Page<OperationLogItemVO> pageLogs(Long userId, String bizType, String action,
                                              Long operatorId, Long projectId, String keyword) {
        return operationLogService.pageLogs(userId, bizType, action, operatorId, projectId,
                keyword, null, null, 1, 50);
    }

    private LoginDTO login(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    private static void assertBiz(int expectedCode, Runnable action) {
        BizException ex = catchThrowableOfType(action::run, BizException.class);
        assertThat(ex).as("应抛出 BizException(code=%s)", expectedCode).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expectedCode);
    }
}
