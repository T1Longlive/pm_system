package com.pm.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.dto.AttachmentDTO;
import com.pm.dto.ParticipantDTO;
import com.pm.dto.RequirementCancelDTO;
import com.pm.dto.RequirementCreateDTO;
import com.pm.dto.RequirementUpdateDTO;
import com.pm.entity.Attachment;
import com.pm.entity.Project;
import com.pm.entity.Requirement;
import com.pm.entity.RequirementParticipant;
import com.pm.entity.User;
import com.pm.mapper.AttachmentMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.NotificationService;
import com.pm.service.RequirementService;
import com.pm.support.AbstractIntegrationTest;
import com.pm.support.Fixtures;
import com.pm.vo.NotificationVO;
import com.pm.vo.OperationLogVO;
import com.pm.vo.RequirementDetailVO;
import com.pm.vo.RequirementListVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 需求主流程集成测试：创建（编号/参与人/附件/通知/留痕）、列表筛选、详情、编辑（乐观锁）、
 * 取消、归档与操作日志权限。
 *
 * <p>此前 RequirementServiceImpl 仅约 18% 行覆盖，需求 CRUD 是后端最大的覆盖洼地。
 * 本类全部走真实 MySQL + Redis（编号流水），断言到库表与通知副作用。
 */
@DisplayName("需求主流程集成测试")
class RequirementCrudIT extends AbstractIntegrationTest {

    @Autowired
    private RequirementService requirementService;
    @Autowired
    private NotificationService notificationService;

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
    private AttachmentMapper attachmentMapper;

    private User admin;
    private User pm;
    private User planner;
    private User fe;
    private User outsider;
    private Project project;

    @BeforeEach
    void setUp() {
        cleanBusinessTables();

        admin = user("admin", "系统管理员", true);
        pm = user("pm", "项目经理", false);
        planner = user("planner", "需求策划", false);
        fe = user("fe", "前端开发", false);
        outsider = user("outsider", "无关用户", false);

        project = project("ACE", pm.getId());
        member(project.getId(), planner.getId(), "PLANNER");
        member(project.getId(), fe.getId(), "FE");
    }

    // ==================== 创建 ====================

    @Test
    @DisplayName("创建需求：编号生成、参与人/附件落库、参与人通知、CREATE 留痕")
    void createWithParticipantsAttachmentsAndLog() {
        RequirementCreateDTO dto = createDto(project.getId(), "需求一");
        dto.setDescription("<p>需求描述</p>");
        dto.setAttachments(List.of(attachment("/upload/2026/11/a.png", "a.png")));

        Long id = requirementService.createRequirement(pm.getId(), dto);

        Requirement saved = requirementMapper.selectById(id);
        assertThat(saved).isNotNull();
        assertThat(saved.getReqNo()).isEqualTo("REQ-ACE-0001");
        assertThat(saved.getProjectId()).isEqualTo(project.getId());
        assertThat(saved.getTitle()).isEqualTo("需求一");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getPriority()).isEqualTo(2);
        assertThat(saved.getPlannerId()).isEqualTo(pm.getId());
        assertThat(saved.getCreatedBy()).isEqualTo(pm.getId());

        List<RequirementParticipant> participants = participantMapper.selectList(
                new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getRequirementId, id));
        assertThat(participants).hasSize(1);
        assertThat(participants.get(0).getUserId()).isEqualTo(fe.getId());
        assertThat(participants.get(0).getCraft()).isEqualTo("FE");

        List<Attachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getBizType, "REQUIREMENT")
                        .eq(Attachment::getBizId, id));
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).getFileUrl()).isEqualTo("/upload/2026/11/a.png");

        assertThat(notificationService.unreadCount(fe.getId())).isEqualTo(1);

        RequirementDetailVO detail = requirementService.getRequirementDetail(pm.getId(), id);
        assertThat(detail.getParticipants()).hasSize(1);
        assertThat(detail.getParticipants().get(0).getRealName()).isEqualTo("前端开发");
        assertThat(detail.getAttachments()).hasSize(1);
        assertThat(detail.getMyRole()).contains("PM");
        assertThat(detail.getPlannerName()).isEqualTo("项目经理");

        List<OperationLogVO> logs = requirementService.listLogs(id, pm.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo("CREATE");
        assertThat(logs.get(0).getRemark()).isEqualTo("创建需求");
        assertThat(logs.get(0).getOperatorName()).isEqualTo("项目经理");
    }

    @Test
    @DisplayName("创建权限：PM 与 PLANNER 成员可建，普通工种成员/无关用户 403")
    void createPermission() {
        Long byPm = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "PM 创建"));
        assertThat(requirementMapper.selectById(byPm).getCreatedBy()).isEqualTo(pm.getId());

        Long byPlanner = requirementService.createRequirement(planner.getId(),
                createDto(project.getId(), "策划创建"));
        assertThat(requirementMapper.selectById(byPlanner).getCreatedBy()).isEqualTo(planner.getId());

        assertBiz(403, () -> requirementService.createRequirement(fe.getId(),
                createDto(project.getId(), "前端创建")));
        assertBiz(403, () -> requirementService.createRequirement(outsider.getId(),
                createDto(project.getId(), "无关用户创建")));
    }

    @Test
    @DisplayName("创建校验：参与人工种不属于成员、非法工种、策划非项目成员、项目不存在")
    void createValidation() {
        RequirementCreateDTO wrongCraft = createDto(project.getId(), "错误工种");
        wrongCraft.setParticipants(List.of(participant(fe.getId(), "QA")));
        BizException notMember = catchThrowableOfType(
                () -> requirementService.createRequirement(pm.getId(), wrongCraft), BizException.class);
        assertThat(notMember).isNotNull();
        assertThat(notMember.getCode()).isEqualTo(400);
        assertThat(notMember.getMessage()).contains("不是本项目");

        RequirementCreateDTO illegalCraft = createDto(project.getId(), "非法工种");
        illegalCraft.setParticipants(List.of(participant(fe.getId(), "XX")));
        BizException illegal = catchThrowableOfType(
                () -> requirementService.createRequirement(pm.getId(), illegalCraft), BizException.class);
        assertThat(illegal).isNotNull();
        assertThat(illegal.getCode()).isEqualTo(400);
        assertThat(illegal.getMessage()).contains("非法工种");

        RequirementCreateDTO badPlanner = createDto(project.getId(), "策划非成员");
        badPlanner.setPlannerId(outsider.getId());
        BizException plannerError = catchThrowableOfType(
                () -> requirementService.createRequirement(pm.getId(), badPlanner), BizException.class);
        assertThat(plannerError).isNotNull();
        assertThat(plannerError.getCode()).isEqualTo(400);
        assertThat(plannerError.getMessage()).isEqualTo("策划必须是本项目成员");

        RequirementCreateDTO missingProject = createDto(999_999L, "项目不存在");
        assertBiz(404, () -> requirementService.createRequirement(pm.getId(), missingProject));
    }

    @Test
    @DisplayName("需求编号：按项目独立自增（REQ-缩写-4位流水）")
    void reqNoSequencePerProject() {
        Long first = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "需求一"));
        Long second = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "需求二"));

        assertThat(requirementMapper.selectById(first).getReqNo()).isEqualTo("REQ-ACE-0001");
        assertThat(requirementMapper.selectById(second).getReqNo()).isEqualTo("REQ-ACE-0002");

        Project other = project("XYZ", pm.getId());
        RequirementCreateDTO otherDto = new RequirementCreateDTO();
        otherDto.setProjectId(other.getId());
        otherDto.setTitle("另一个项目");
        Long otherFirst = requirementService.createRequirement(pm.getId(), otherDto);
        assertThat(requirementMapper.selectById(otherFirst).getReqNo()).isEqualTo("REQ-XYZ-0001");
    }

    // ==================== 列表 / 详情 ====================

    @Test
    @DisplayName("列表筛选：项目/关键词/优先级/策划/状态/排除终态与分页")
    void pageFilters() {
        Long high = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "登录改版", 1));
        requirementService.createRequirement(pm.getId(), createDto(project.getId(), "支付改造", 3));

        Page<RequirementListVO> all = requirementService.pageRequirements(pm.getId(), project.getId(),
                null, null, null, null, false, 1, 50);
        assertThat(all.getTotal()).isEqualTo(2);

        Page<RequirementListVO> keyword = requirementService.pageRequirements(pm.getId(), project.getId(),
                null, null, null, "登录", false, 1, 50);
        assertThat(keyword.getTotal()).isEqualTo(1);
        assertThat(keyword.getRecords().get(0).getTitle()).isEqualTo("登录改版");

        Page<RequirementListVO> byPriority = requirementService.pageRequirements(pm.getId(), project.getId(),
                null, 1, null, null, false, 1, 50);
        assertThat(byPriority.getTotal()).isEqualTo(1);

        Page<RequirementListVO> byPlanner = requirementService.pageRequirements(pm.getId(), project.getId(),
                "PENDING", null, pm.getId(), null, false, 1, 50);
        assertThat(byPlanner.getTotal()).isEqualTo(2);

        Page<RequirementListVO> firstPage = requirementService.pageRequirements(pm.getId(), project.getId(),
                null, null, null, null, false, 1, 1);
        assertThat(firstPage.getRecords()).hasSize(1);
        assertThat(firstPage.getTotal()).isEqualTo(2);

        // 取消后：excludeTerminal 排除，status 精确命中
        cancel(high, "不再需要");
        assertThat(requirementService.pageRequirements(pm.getId(), project.getId(),
                null, null, null, null, true, 1, 50).getTotal()).isEqualTo(1);
        assertThat(requirementService.pageRequirements(pm.getId(), project.getId(),
                "CANCELLED", null, null, null, false, 1, 50).getTotal()).isEqualTo(1);

        assertBiz(400, () -> requirementService.pageRequirements(pm.getId(), null,
                "FOO", null, null, null, false, 1, 50));
        assertBiz(400, () -> requirementService.pageRequirements(pm.getId(), null,
                null, 9, null, null, false, 1, 50));
    }

    @Test
    @DisplayName("详情：字段、参与人、附件与当前用户 myRole（PM / 工种成员 / 无关用户）")
    void detailMyRole() {
        RequirementCreateDTO dto = createDto(project.getId(), "需求一");
        dto.setDescription("<p>集成测试需求</p>");
        Long id = requirementService.createRequirement(pm.getId(), dto);

        RequirementDetailVO asPm = requirementService.getRequirementDetail(pm.getId(), id);
        assertThat(asPm.getTitle()).isEqualTo("需求一");
        assertThat(asPm.getDescription()).isEqualTo("<p>集成测试需求</p>");
        assertThat(asPm.getMyRole()).containsExactly("PM");

        RequirementDetailVO asFe = requirementService.getRequirementDetail(fe.getId(), id);
        assertThat(asFe.getMyRole()).contains("FE");

        RequirementDetailVO asOutsider = requirementService.getRequirementDetail(outsider.getId(), id);
        assertThat(asOutsider.getMyRole()).isEmpty();

        assertBiz(404, () -> requirementService.getRequirementDetail(pm.getId(), 999_999L));
    }

    // ==================== 编辑 ====================

    @Test
    @DisplayName("编辑：基础字段变更、版本号前进、UPDATE 摘要留痕")
    void updateFieldsAdvancesVersionAndLogs() {
        Long id = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "需求一"));
        long oldUpdatedAt = millis(requirementMapper.selectById(id));

        RequirementUpdateDTO dto = new RequirementUpdateDTO();
        dto.setTitle("需求一-改");
        dto.setPriority(1);
        dto.setDescription("<p>新描述</p>");
        dto.setUpdatedAt(oldUpdatedAt);
        requirementService.updateRequirement(pm.getId(), id, dto);

        Requirement updated = requirementMapper.selectById(id);
        assertThat(updated.getTitle()).isEqualTo("需求一-改");
        assertThat(updated.getPriority()).isEqualTo(1);
        assertThat(updated.getDescription()).isEqualTo("<p>新描述</p>");
        assertThat(millis(updated)).isGreaterThan(oldUpdatedAt);

        List<OperationLogVO> logs = requirementService.listLogs(id, pm.getId());
        assertThat(logs).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("UPDATE");
            assertThat(log.getRemark()).contains("标题已修改").contains("优先级：中→高")
                    .contains("描述已修改");
        });
    }

    @Test
    @DisplayName("编辑拦截：过期版本 409、非 PM/创建人 403、终态 400")
    void updateRejectsStalePermissionAndTerminal() {
        Long id = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "需求一"));
        long updatedAt = millis(requirementMapper.selectById(id));

        RequirementUpdateDTO stale = new RequirementUpdateDTO();
        stale.setTitle("过期编辑");
        stale.setUpdatedAt(updatedAt - 5_000);
        assertBiz(409, () -> requirementService.updateRequirement(pm.getId(), id, stale));

        RequirementUpdateDTO byFe = new RequirementUpdateDTO();
        byFe.setTitle("前端编辑");
        byFe.setUpdatedAt(updatedAt);
        assertBiz(403, () -> requirementService.updateRequirement(fe.getId(), id, byFe));

        cancel(id, "需求作废");
        long afterCancel = millis(requirementMapper.selectById(id));
        RequirementUpdateDTO terminal = new RequirementUpdateDTO();
        terminal.setTitle("终态编辑");
        terminal.setUpdatedAt(afterCancel);
        BizException ex = catchThrowableOfType(
                () -> requirementService.updateRequirement(pm.getId(), id, terminal), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("不可编辑");
    }

    // ==================== 取消 / 归档 ====================

    @Test
    @DisplayName("取消：仅 PM、落取消信息、通知参与人、不可重复取消")
    void cancelRequirement() {
        Long id = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "需求一"));
        RequirementCancelDTO dto = new RequirementCancelDTO();
        dto.setReason("不再需要");

        assertBiz(403, () -> requirementService.cancelRequirement(fe.getId(), id, dto));

        requirementService.cancelRequirement(pm.getId(), id, dto);
        Requirement cancelled = requirementMapper.selectById(id);
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getCancelledBy()).isEqualTo(pm.getId());
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancelReason()).isEqualTo("不再需要");

        List<NotificationVO> notifications = notificationService
                .pageMyNotifications(fe.getId(), 1, 20).getRecords();
        assertThat(notifications).extracting(NotificationVO::getTitle).contains("需求已取消");

        BizException repeat = catchThrowableOfType(
                () -> requirementService.cancelRequirement(pm.getId(), id, dto), BizException.class);
        assertThat(repeat).isNotNull();
        assertThat(repeat.getCode()).isEqualTo(400);
        assertThat(repeat.getMessage()).contains("已取消");
    }

    @Test
    @DisplayName("归档：仅 ARCHIVING 可归档、PM 权限、归档时间与通知、ARCHIVE 留痕")
    void archiveRequirement() {
        Long id = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "需求一"));

        assertBiz(403, () -> requirementService.archiveRequirement(fe.getId(), id));
        BizException notArchiving = catchThrowableOfType(
                () -> requirementService.archiveRequirement(pm.getId(), id), BizException.class);
        assertThat(notArchiving).isNotNull();
        assertThat(notArchiving.getCode()).isEqualTo(400);
        assertThat(notArchiving.getMessage()).isEqualTo("当前状态不可归档");

        Requirement toArchiving = new Requirement();
        toArchiving.setId(id);
        toArchiving.setStatus("ARCHIVING");
        requirementMapper.updateById(toArchiving);

        requirementService.archiveRequirement(pm.getId(), id);
        Requirement archived = requirementMapper.selectById(id);
        assertThat(archived.getStatus()).isEqualTo("ARCHIVED");
        assertThat(archived.getArchivedAt()).isNotNull();

        List<OperationLogVO> logs = requirementService.listLogs(id, admin.getId());
        assertThat(logs.get(0).getAction()).isEqualTo("ARCHIVE");
        assertThat(notificationService.pageMyNotifications(fe.getId(), 1, 20).getRecords())
                .extracting(NotificationVO::getTitle).contains("需求已归档");
    }

    // ==================== 操作日志 ====================

    @Test
    @DisplayName("需求日志：PM 与管理员可见、id 倒序、成员 403、需求不存在 404")
    void listLogsPermissionAndOrder() {
        Long id = requirementService.createRequirement(pm.getId(), createDto(project.getId(), "需求一"));
        long updatedAt = millis(requirementMapper.selectById(id));
        RequirementUpdateDTO dto = new RequirementUpdateDTO();
        dto.setTitle("需求一-改");
        dto.setUpdatedAt(updatedAt);
        requirementService.updateRequirement(pm.getId(), id, dto);

        List<OperationLogVO> asPm = requirementService.listLogs(id, pm.getId());
        assertThat(asPm).extracting(OperationLogVO::getAction).containsExactly("UPDATE", "CREATE");

        assertThat(requirementService.listLogs(id, admin.getId())).hasSize(2);
        assertBiz(403, () -> requirementService.listLogs(id, fe.getId()));
        assertBiz(404, () -> requirementService.listLogs(999_999L, pm.getId()));
    }

    // ==================== 造数与断言 ====================

    private RequirementCreateDTO createDto(Long projectId, String title) {
        return createDto(projectId, title, null);
    }

    private RequirementCreateDTO createDto(Long projectId, String title, Integer priority) {
        RequirementCreateDTO dto = new RequirementCreateDTO();
        dto.setProjectId(projectId);
        dto.setTitle(title);
        dto.setPriority(priority);
        dto.setParticipants(List.of(participant(fe.getId(), "FE")));
        return dto;
    }

    private ParticipantDTO participant(Long userId, String craft) {
        ParticipantDTO dto = new ParticipantDTO();
        dto.setUserId(userId);
        dto.setCraft(craft);
        return dto;
    }

    private AttachmentDTO attachment(String url, String fileName) {
        AttachmentDTO dto = new AttachmentDTO();
        dto.setUrl(url);
        dto.setFileName(fileName);
        dto.setFileType("IMAGE");
        dto.setFileSize(123L);
        return dto;
    }

    private void cancel(Long requirementId, String reason) {
        RequirementCancelDTO dto = new RequirementCancelDTO();
        dto.setReason(reason);
        requirementService.cancelRequirement(pm.getId(), requirementId, dto);
    }

    private long millis(Requirement requirement) {
        return requirement.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

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

    private static void assertBiz(int expectedCode, Runnable action) {
        BizException ex = catchThrowableOfType(action::run, BizException.class);
        assertThat(ex).as("应抛出 BizException(code=%s)", expectedCode).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expectedCode);
    }
}
