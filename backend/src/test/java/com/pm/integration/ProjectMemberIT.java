package com.pm.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pm.common.BizException;
import com.pm.dto.MemberAddDTO;
import com.pm.entity.Project;
import com.pm.entity.ProjectMember;
import com.pm.entity.Requirement;
import com.pm.entity.RequirementParticipant;
import com.pm.entity.ScheduleMonth;
import com.pm.entity.ScheduleRow;
import com.pm.entity.User;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleMonthMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.ProjectService;
import com.pm.support.AbstractIntegrationTest;
import com.pm.support.Fixtures;
import com.pm.vo.ProjectDetailVO;
import com.pm.vo.ProjectListVO;
import com.pm.vo.ProjectMemberVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 项目成员写路径集成测试：增量添加、按工种移除、移除全部工种、
 * 以及成员移除向需求参与人 / 排期行的联动。
 */
@DisplayName("项目成员管理集成测试")
class ProjectMemberIT extends AbstractIntegrationTest {

    @Autowired
    private ProjectService projectService;
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

    private User admin;
    private User pm;
    private User pm2;
    private User member;
    private User outsider;
    private Project project;
    private Project project2;

    @BeforeEach
    void setUp() {
        cleanBusinessTables();
        admin = user("admin", "管理员", true);
        pm = user("pm", "项目经理", false);
        pm2 = user("pm2", "另一个PM", false);
        member = user("member", "成员", false);
        outsider = user("out", "无关用户", false);

        project = project("M1", pm.getId());
        project2 = project("M2", pm2.getId());
        projectMemberMapper.insert(Fixtures.member(project.getId(), member.getId(), "FE"));
    }

    @Test
    @DisplayName("添加成员为增量合并：已有工种跳过，仅新工种落库并留痕")
    void addMemberIsIncremental() {
        addCrafts(member.getId(), "FE", "QA");
        assertThat(craftsOf(member.getId())).containsExactlyInAnyOrder("FE", "QA");
        assertThat(countMembers(member.getId())).isEqualTo(2);
        assertThat(countAddMemberLogs()).isEqualTo(1);

        addCrafts(member.getId(), "QA", "UI");
        assertThat(craftsOf(member.getId())).containsExactlyInAnyOrder("FE", "QA", "UI");
        assertThat(countMembers(member.getId())).isEqualTo(3);
        assertThat(countAddMemberLogs()).isEqualTo(2);
    }

    @Test
    @DisplayName("按工种移除：只删该工种成员行；需求参与人联动删除，排期行标 VOID")
    void removeMemberByCraftKeepsOtherCrafts() {
        projectMemberMapper.insert(Fixtures.member(project.getId(), member.getId(), "QA"));
        Requirement requirement = requirementWithRows();

        projectService.removeMember(pm.getId(), project.getId(), member.getId(), "FE");

        assertThat(craftsOf(member.getId())).containsExactly("QA");
        assertThat(participantCrafts()).containsExactly("QA");
        assertThat(rowStatus("FE")).isEqualTo("VOID");
        assertThat(rowStatus("QA")).isEqualTo("TODO");
        assertThat(requirementMapper.selectById(requirement.getId()).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("不带工种移除：删除该用户全部工种行，全部参与人移除、排期行全部 VOID")
    void removeMemberAllCrafts() {
        projectMemberMapper.insert(Fixtures.member(project.getId(), member.getId(), "QA"));
        requirementWithRows();

        projectService.removeMember(pm.getId(), project.getId(), member.getId(), null);

        assertThat(countMembers(member.getId())).isZero();
        assertThat(participantCrafts()).isEmpty();
        assertThat(rowStatus("FE")).isEqualTo("VOID");
        assertThat(rowStatus("QA")).isEqualTo("VOID");
    }

    @Test
    @DisplayName("非 PM 移除成员 → 403；移除不存在的工种 → 404")
    void removeMemberPermissionAndMissingCraft() {
        assertBiz(403, () -> projectService.removeMember(member.getId(), project.getId(), member.getId(), "FE"));
        assertBiz(403, () -> projectService.removeMember(outsider.getId(), project.getId(), member.getId(), "FE"));
        assertBiz(404, () -> projectService.removeMember(pm.getId(), project.getId(), member.getId(), "QA"));
    }

    @Test
    @DisplayName("项目详情：成员按用户聚合工种，创建者 myRole 含 PM")
    void detailGroupsCraftsByUser() {
        projectMemberMapper.insert(Fixtures.member(project.getId(), member.getId(), "QA"));

        ProjectDetailVO detail = projectService.getProjectDetail(pm.getId(), project.getId());

        List<ProjectMemberVO> members = detail.getMembers();
        assertThat(members).hasSize(1);
        assertThat(members.get(0).getUserId()).isEqualTo(member.getId());
        assertThat(members.get(0).getCrafts()).containsExactlyInAnyOrder("FE", "QA");
        assertThat(detail.getMyRole()).contains("PM");
    }

    @Test
    @DisplayName("项目列表可见性：管理员全量、成员仅参与项目、无关用户空、创建者可见自己的项目")
    void pageProjectsVisibility() {
        assertThat(pageProjects(admin.getId()).getTotal()).isEqualTo(2);
        assertThat(pageProjects(member.getId()).getTotal()).isEqualTo(1);
        assertThat(pageProjects(pm.getId()).getTotal()).isEqualTo(1);
        assertThat(pageProjects(outsider.getId()).getTotal()).isZero();

        ProjectListVO p1 = pageProjects(member.getId()).getRecords().get(0);
        assertThat(p1.getId()).isEqualTo(project.getId());
        // 同一用户多工种也只算一个成员
        assertThat(p1.getMemberCount()).isEqualTo(1);
        assertThat(p1.getMyRole()).containsExactly("FE");
    }

    // ==================== 造数与查询 ====================

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

    private void addCrafts(Long userId, String... crafts) {
        MemberAddDTO dto = new MemberAddDTO();
        dto.setUserId(userId);
        dto.setCrafts(List.of(crafts));
        projectService.addMember(pm.getId(), project.getId(), dto);
    }

    /**
     * 建需求 + FE/QA 参与人 + 当月（未来）排期行，返回需求
     */
    private Requirement requirementWithRows() {
        Requirement requirement = Fixtures.requirement(project.getId(), "REQ-M1-0001", "成员联动需求",
                "PENDING", pm.getId(), pm.getId());
        requirementMapper.insert(requirement);
        participantMapper.insert(Fixtures.participant(requirement.getId(), member.getId(), "FE"));
        participantMapper.insert(Fixtures.participant(requirement.getId(), member.getId(), "QA"));

        YearMonth next = YearMonth.now().plusMonths(1);
        ScheduleMonth month = Fixtures.month(next.getYear(), next.getMonthValue(), pm.getId());
        monthMapper.insert(month);
        rowMapper.insert(Fixtures.row(month.getId(), requirement.getId(), "FE", member.getId(), 1, "1"));
        rowMapper.insert(Fixtures.row(month.getId(), requirement.getId(), "QA", member.getId(), 1, "1"));
        return requirement;
    }

    private List<String> craftsOf(Long userId) {
        return projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, project.getId())
                        .eq(ProjectMember::getUserId, userId)
                        .orderByAsc(ProjectMember::getCraft))
                .stream().map(ProjectMember::getCraft).toList();
    }

    private Long countMembers(Long userId) {
        return projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, project.getId())
                .eq(ProjectMember::getUserId, userId));
    }

    private List<String> participantCrafts() {
        return participantMapper.selectList(new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getUserId, member.getId()))
                .stream().map(RequirementParticipant::getCraft).toList();
    }

    private String rowStatus(String craft) {
        ScheduleRow row = rowMapper.selectOne(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getCraft, craft)
                .eq(ScheduleRow::getUserId, member.getId())
                .last("LIMIT 1"));
        return row == null ? null : row.getStatus();
    }

    private Long countAddMemberLogs() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_operation_log "
                + "WHERE biz_type='PROJECT' AND project_id=? AND action='ADD_MEMBER'",
                Long.class, project.getId());
    }

    private com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProjectListVO> pageProjects(Long userId) {
        return projectService.pageProjects(userId, null, 1, 20);
    }

    private static void assertBiz(int expectedCode, Runnable action) {
        BizException ex = catchThrowableOfType(action::run, BizException.class);
        assertThat(ex).as("应抛出 BizException(code=%s)", expectedCode).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expectedCode);
    }
}
