package com.pm.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pm.common.BizException;
import com.pm.dto.AttachmentDTO;
import com.pm.dto.BugCreateDTO;
import com.pm.dto.BugImageAddDTO;
import com.pm.dto.BugUpdateDTO;
import com.pm.entity.Attachment;
import com.pm.entity.Bug;
import com.pm.entity.Project;
import com.pm.entity.User;
import com.pm.mapper.AttachmentMapper;
import com.pm.mapper.BugMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.BugService;
import com.pm.support.AbstractIntegrationTest;
import com.pm.support.Fixtures;
import com.pm.vo.BugListVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Bug Excel 化编辑模型的集成测试。
 *
 * <p>重点验证「逐格部分更新」的哨兵语义与状态边界：
 * null=不修改、""=清空文本、0=清空数值/ID；归档只读；重开清空进度；
 * 删除连带清理截图；编辑留痕只记录实际变化的字段。
 *
 * <p>Bug 无乐观锁（最后写入胜出）是刻意设计，本测试不期待 409。
 */
@DisplayName("Bug 生命周期集成测试")
class BugLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private BugService bugService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private ProjectMemberMapper projectMemberMapper;
    @Autowired
    private BugMapper bugMapper;
    @Autowired
    private AttachmentMapper attachmentMapper;

    private User pm;
    private User fe;
    private User admin;
    private Project project;

    @BeforeEach
    void setUp() {
        cleanBusinessTables();
        pm = insertUser("pm", "项目经理", false);
        fe = insertUser("fe", "前端开发", false);
        admin = insertUser("admin", "系统管理员", true);

        project = Fixtures.project("BUG", pm.getId());
        projectMapper.insert(project);
        projectMemberMapper.insert(Fixtures.member(project.getId(), fe.getId(), "FE"));
    }

    // ==================== 创建 ====================

    @Test
    @DisplayName("创建 Bug：编号自动生成、测试人=当前用户、其余字段留空、archived=0")
    void createBugMinimalRow() {
        BugListVO vo = bugService.createBug(fe.getId(), createDTO());

        assertThat(vo.getBugNo()).matches("BUG-BUG-\\d{4}");
        assertThat(vo.getTesterId()).isEqualTo(fe.getId());
        assertThat(vo.getArchived()).isZero();
        assertThat(vo.getFeature()).isNull();
        assertThat(vo.getOwnerId()).isNull();
        assertThat(vo.getCreatedBy()).isEqualTo(fe.getId());
    }

    @Test
    @DisplayName("列表必须指定项目；archived 仅允许 0/1")
    void pageBugsRequiresProjectAndValidArchived() {
        assertBiz(400, () -> bugService.pageBugs(fe.getId(), null, 0, null, 1, 100));
        assertBiz(400, () -> bugService.pageBugs(fe.getId(), project.getId(), 2, null, 1, 100));
    }

    // ==================== 逐格更新与清空哨兵 ====================

    @Test
    @DisplayName("逐格更新：正常赋值；空串清空文本、0 清空数值/ID")
    void updateAndClearWithSentinels() {
        Long bugId = createBug().getId();

        BugUpdateDTO set = new BugUpdateDTO();
        set.setFeature("登录页");
        set.setDescription("验证码不刷新");
        set.setPriority(1);
        set.setOwnerId(fe.getId());
        set.setHandleStatus("DONE");
        set.setAcceptStatus("PASSED");
        bugService.updateBug(fe.getId(), bugId, set);

        Bug updated = bugMapper.selectById(bugId);
        assertThat(updated.getFeature()).isEqualTo("登录页");
        assertThat(updated.getPriority()).isEqualTo(1);
        assertThat(updated.getOwnerId()).isEqualTo(fe.getId());
        assertThat(updated.getHandleStatus()).isEqualTo("DONE");
        assertThat(updated.getAcceptStatus()).isEqualTo("PASSED");

        BugUpdateDTO clear = new BugUpdateDTO();
        clear.setFeature("");
        clear.setDescription("");
        clear.setPriority(0);
        clear.setOwnerId(0L);
        clear.setHandleStatus("");
        clear.setAcceptStatus("");
        bugService.updateBug(fe.getId(), bugId, clear);

        Bug cleared = bugMapper.selectById(bugId);
        assertThat(cleared.getFeature()).isNull();
        assertThat(cleared.getDescription()).isNull();
        assertThat(cleared.getPriority()).isNull();
        assertThat(cleared.getOwnerId()).isNull();
        assertThat(cleared.getHandleStatus()).isNull();
        assertThat(cleared.getAcceptStatus()).isNull();
    }

    @Test
    @DisplayName("提交相同值不写 UPDATE 日志（避免刷日志）")
    void unchangedUpdateDoesNotWriteLog() {
        Long bugId = createBug().getId();
        BugUpdateDTO dto = new BugUpdateDTO();
        dto.setFeature("登录页");

        bugService.updateBug(fe.getId(), bugId, dto);
        bugService.updateBug(fe.getId(), bugId, dto);

        Long updateLogs = countLogs(bugId, "UPDATE");
        assertThat(updateLogs).isEqualTo(1);
    }

    @Test
    @DisplayName("非法处理/验收进度 → 400")
    void invalidProgressRejected() {
        Long bugId = createBug().getId();

        BugUpdateDTO badHandle = new BugUpdateDTO();
        badHandle.setHandleStatus("UNKNOWN");
        assertBiz(400, () -> bugService.updateBug(fe.getId(), bugId, badHandle));

        BugUpdateDTO badAccept = new BugUpdateDTO();
        badAccept.setAcceptStatus("UNKNOWN");
        assertBiz(400, () -> bugService.updateBug(fe.getId(), bugId, badAccept));
    }

    // ==================== 归档 / 重开 ====================

    @Test
    @DisplayName("归档：成员 403；PM 可归档；重复归档 400；归档后编辑只读")
    void archiveAndReadOnly() {
        Long bugId = createBug().getId();

        assertBiz(403, () -> bugService.archiveBug(fe.getId(), bugId));
        assertThatCode(() -> bugService.archiveBug(pm.getId(), bugId)).doesNotThrowAnyException();
        assertThat(bugMapper.selectById(bugId).getArchived()).isEqualTo(1);

        assertBiz(400, () -> bugService.archiveBug(pm.getId(), bugId));

        BugUpdateDTO dto = new BugUpdateDTO();
        dto.setFeature("归档后修改");
        assertBiz(400, () -> bugService.updateBug(fe.getId(), bugId, dto));
        assertBiz(400, () -> bugService.addImages(fe.getId(), bugId, imageDTO()));
    }

    @Test
    @DisplayName("重新打开：回总表、清空归档三字段、清空处理与验收进度")
    void reopenClearsProgress() {
        Long bugId = createBug().getId();
        BugUpdateDTO progress = new BugUpdateDTO();
        progress.setHandleStatus("DONE");
        progress.setAcceptStatus("PASSED");
        bugService.updateBug(fe.getId(), bugId, progress);

        bugService.archiveBug(pm.getId(), bugId);
        assertThat(bugMapper.selectById(bugId).getArchivedAt()).isNotNull();

        bugService.reopenBug(pm.getId(), bugId);

        Bug reopened = bugMapper.selectById(bugId);
        assertThat(reopened.getArchived()).isZero();
        assertThat(reopened.getArchivedAt()).isNull();
        assertThat(reopened.getArchivedBy()).isNull();
        assertThat(reopened.getHandleStatus()).isNull();
        assertThat(reopened.getAcceptStatus()).isNull();
    }

    @Test
    @DisplayName("未归档 Bug 重新打开 → 400")
    void reopenUnarchivedRejected() {
        Long bugId = createBug().getId();
        assertBiz(400, () -> bugService.reopenBug(pm.getId(), bugId));
    }

    // ==================== 截图 ====================

    @Test
    @DisplayName("截图追加与删除：列表按 id 升序返回；非图片拒绝；删除校验归属")
    void imageAddAndDelete() {
        Long bugId = createBug().getId();

        List<Long> ids = bugService.addImages(fe.getId(), bugId, imageDTO());
        assertThat(ids).hasSize(2);

        BugListVO vo = bugService.pageBugs(fe.getId(), project.getId(), 0, null, 1, 100)
                .getRecords().get(0);
        assertThat(vo.getImages()).hasSize(2);
        assertThat(vo.getImages().get(0).getId()).isEqualTo(ids.get(0));

        bugService.deleteImage(fe.getId(), bugId, ids.get(0));
        assertThat(attachmentMapper.selectById(ids.get(0))).isNull();
        assertThat(attachmentMapper.selectById(ids.get(1))).isNotNull();

        // 删除不属于该 Bug 的附件 → 404
        assertBiz(404, () -> bugService.deleteImage(fe.getId(), bugId, 999999L));
    }

    @Test
    @DisplayName("追加非图片附件 → 400「仅支持上传图片」")
    void addNonImageRejected() {
        Long bugId = createBug().getId();
        BugImageAddDTO dto = new BugImageAddDTO();
        dto.setImages(List.of(attachment("/upload/a.pdf", "a.pdf", "DOC")));

        BizException ex = catchThrowableOfType(
                () -> bugService.addImages(fe.getId(), bugId, dto), BizException.class);
        assertThat(ex.getCode()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("仅支持上传图片");
    }

    @Test
    @DisplayName("删除 Bug：物理删除并连带清理截图附件")
    void deleteBugCascadesImages() {
        Long bugId = createBug().getId();
        bugService.addImages(fe.getId(), bugId, imageDTO());

        bugService.deleteBug(fe.getId(), bugId);

        assertThat(bugMapper.selectById(bugId)).isNull();
        assertThat(attachmentMapper.selectCount(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getBizType, "BUG")
                .eq(Attachment::getBizId, bugId))).isZero();
    }

    // ==================== 造数与断言 ====================

    private BugListVO createBug() {
        BugCreateDTO dto = createDTO();
        return bugService.createBug(fe.getId(), dto);
    }

    private BugCreateDTO createDTO() {
        BugCreateDTO dto = new BugCreateDTO();
        dto.setProjectId(project.getId());
        return dto;
    }

    private BugImageAddDTO imageDTO() {
        BugImageAddDTO dto = new BugImageAddDTO();
        dto.setImages(List.of(
                attachment("/upload/2026/11/a.png", "a.png", "IMAGE"),
                attachment("/upload/2026/11/b.png", "b.png", "IMAGE")));
        return dto;
    }

    private static AttachmentDTO attachment(String url, String name, String type) {
        AttachmentDTO dto = new AttachmentDTO();
        dto.setUrl(url);
        dto.setFileName(name);
        dto.setFileType(type);
        dto.setFileSize(1024L);
        return dto;
    }

    private User insertUser(String username, String realName, boolean isAdmin) {
        User user = Fixtures.user(username, realName, isAdmin);
        userMapper.insert(user);
        return user;
    }

    private Long countLogs(Long bugId, String action) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_operation_log WHERE biz_type='BUG' AND biz_id=? AND action=?",
                Long.class, bugId, action);
    }

    private static void assertBiz(int expectedCode, Runnable action) {
        BizException ex = catchThrowableOfType(action::run, BizException.class);
        assertThat(ex).as("应抛出 BizException(code=%s)", expectedCode).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expectedCode);
    }
}
