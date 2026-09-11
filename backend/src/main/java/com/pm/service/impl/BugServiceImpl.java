package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.dto.AttachmentDTO;
import com.pm.dto.BugCreateDTO;
import com.pm.dto.BugImageAddDTO;
import com.pm.dto.BugUpdateDTO;
import com.pm.entity.Attachment;
import com.pm.entity.BizOperationLog;
import com.pm.entity.Bug;
import com.pm.entity.Project;
import com.pm.entity.ProjectMember;
import com.pm.entity.User;
import com.pm.mapper.AttachmentMapper;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.BugMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.BugService;
import com.pm.vo.AttachmentVO;
import com.pm.vo.BugListVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bug 管理服务实现（Excel 化编辑模型）
 * 所有登录用户可编辑未归档行（字段单元格内直接编辑，最后写入胜出，无乐观锁）；
 * 归档/重新打开限项目经理或管理员；删除所有人可用；
 * 归档表行只读（updateBug/addImages/deleteImage 校验 archived=0），但可删除
 */
@Service
@RequiredArgsConstructor
public class BugServiceImpl implements BugService {

    /** Bug 流水号 Redis 键前缀：seq:bug:{projectId} */
    private static final String SEQ_KEY_PREFIX = "seq:bug:";

    /** 处理进度：已处理 */
    private static final String HANDLE_DONE = "DONE";
    /** 处理进度：暂不处理 */
    private static final String HANDLE_HOLD = "HOLD";
    /** 处理进度：不处理 */
    private static final String HANDLE_SKIP = "SKIP";

    /** 验收进度：验收通过 */
    private static final String ACCEPT_PASSED = "PASSED";
    /** 验收进度：验收不通过 */
    private static final String ACCEPT_REJECTED = "REJECTED";

    /** 全部合法处理进度（"" = 清空/未处理） */
    private static final Set<String> ALL_HANDLE_STATUSES = Set.of("", HANDLE_DONE, HANDLE_HOLD, HANDLE_SKIP);

    /** 全部合法验收进度（"" = 清空/未验收） */
    private static final Set<String> ALL_ACCEPT_STATUSES = Set.of("", ACCEPT_PASSED, ACCEPT_REJECTED);

    /** 留痕/附件使用的业务类型 */
    private static final String BIZ_TYPE = "BUG";

    /** 一页全量上限（Excel 模式） */
    private static final long MAX_PAGE_SIZE = 1000;

    private final BugMapper bugMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final AttachmentMapper attachmentMapper;
    private final BizOperationLogMapper operationLogMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Page<BugListVO> pageBugs(Long userId, Long projectId, Integer archived, String keyword, long page, long size) {
        // 项目必选：进入页面先选项目再加载列表
        if (projectId == null) {
            throw new BizException(400, "请先选择项目");
        }
        if (archived == null) {
            archived = 0;
        }
        if (archived != 0 && archived != 1) {
            throw new BizException(400, "归档视图参数只能为0/1");
        }
        // 一页全量模式：size 上限保护
        long safePage = page <= 0 ? 1 : page;
        long safeSize = size <= 0 || size > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : size;

        LambdaQueryWrapper<Bug> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Bug::getProjectId, projectId);
        // 总表/归档表同表查询，按 archived 区分
        wrapper.eq(Bug::getArchived, archived);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            // keyword 模糊匹配功能模块或编号
            wrapper.and(w -> w.like(Bug::getFeature, kw).or().like(Bug::getBugNo, kw));
        }
        wrapper.orderByDesc(Bug::getId);
        Page<Bug> bugPage = bugMapper.selectPage(new Page<>(safePage, safeSize), wrapper);

        Page<BugListVO> voPage = new Page<>(bugPage.getCurrent(), bugPage.getSize(), bugPage.getTotal());
        List<Bug> bugs = bugPage.getRecords();
        if (bugs.isEmpty()) {
            voPage.setRecords(new ArrayList<>());
            return voPage;
        }
        voPage.setRecords(assembleVOs(userId, bugs));
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BugListVO createBug(Long userId, BugCreateDTO dto) {
        Project project = requireProject(dto.getProjectId());
        // 最小行：编号自动生成、测试人员=当前用户、其余字段留空由前端单元格编辑填充
        Bug bug = new Bug();
        bug.setBugNo(generateBugNo(project));
        bug.setProjectId(project.getId());
        bug.setTesterId(userId);
        bug.setArchived(0);
        bug.setCreatedBy(userId);
        bugMapper.insert(bug);
        // 留痕：创建 Bug
        writeLog(bug, userId, "CREATE", "创建 Bug（测试人：本人）");
        return assembleVOs(userId, List.of(bug)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateBug(Long userId, Long bugId, BugUpdateDTO dto) {
        Bug bug = requireBug(bugId);
        // 归档表只读
        checkNotArchived(bug);
        // 进度值域校验（"" = 清空）
        if (dto.getHandleStatus() != null && !ALL_HANDLE_STATUSES.contains(dto.getHandleStatus())) {
            throw new BizException(400, "非法处理进度：" + dto.getHandleStatus());
        }
        if (dto.getAcceptStatus() != null && !ALL_ACCEPT_STATUSES.contains(dto.getAcceptStatus())) {
            throw new BizException(400, "非法验收进度：" + dto.getAcceptStatus());
        }

        // 逐格部分更新：清空哨兵 null=不修改、字符串 ""=清空、数值/ID 0=清空；
        // 统一 UpdateWrapper 显式 set（updateById 会忽略 null，无法表达清空）；
        // 提交值归一化后与库中旧值比较，仅真正变化的字段才 set（避免重复提交相同值刷 UPDATE 日志）
        LambdaUpdateWrapper<Bug> wrapper = new LambdaUpdateWrapper<Bug>().eq(Bug::getId, bugId);
        boolean changed = false;
        List<String> changedFields = new ArrayList<>();
        if (dto.getVersion() != null && putIfChanged(wrapper, Bug::getVersion,
                blankToNull(dto.getVersion()), bug.getVersion(), changedFields, "版本")) changed = true;
        if (dto.getFeature() != null && putIfChanged(wrapper, Bug::getFeature,
                blankToNull(dto.getFeature()), bug.getFeature(), changedFields, "功能模块")) changed = true;
        if (dto.getDescription() != null && putIfChanged(wrapper, Bug::getDescription,
                blankToNull(dto.getDescription()), bug.getDescription(), changedFields, "问题描述")) changed = true;
        if (dto.getPriority() != null && putIfChanged(wrapper, Bug::getPriority,
                zeroToNull(dto.getPriority()), bug.getPriority(), changedFields, "优先级")) changed = true;
        if (dto.getOwnerId() != null && putIfChanged(wrapper, Bug::getOwnerId,
                zeroToNull(dto.getOwnerId()), bug.getOwnerId(), changedFields, "负责人")) changed = true;
        if (dto.getTesterId() != null && putIfChanged(wrapper, Bug::getTesterId,
                zeroToNull(dto.getTesterId()), bug.getTesterId(), changedFields, "测试人")) changed = true;
        if (dto.getHandleStatus() != null && putIfChanged(wrapper, Bug::getHandleStatus,
                emptyToNull(dto.getHandleStatus()), bug.getHandleStatus(), changedFields, "处理进度")) changed = true;
        if (dto.getAcceptStatus() != null && putIfChanged(wrapper, Bug::getAcceptStatus,
                emptyToNull(dto.getAcceptStatus()), bug.getAcceptStatus(), changedFields, "验收进度")) changed = true;
        if (dto.getSolution() != null && putIfChanged(wrapper, Bug::getSolution,
                blankToNull(dto.getSolution()), bug.getSolution(), changedFields, "解决方案")) changed = true;
        if (dto.getDevRemark() != null && putIfChanged(wrapper, Bug::getDevRemark,
                blankToNull(dto.getDevRemark()), bug.getDevRemark(), changedFields, "开发备注")) changed = true;
        if (dto.getTestRemark() != null && putIfChanged(wrapper, Bug::getTestRemark,
                blankToNull(dto.getTestRemark()), bug.getTestRemark(), changedFields, "测试备注")) changed = true;
        boolean videoChanged = false;
        if (dto.getVideoUrl() != null) {
            videoChanged |= putIfChanged(wrapper, Bug::getVideoUrl,
                    blankToNull(dto.getVideoUrl()), bug.getVideoUrl(), changedFields, "视频");
        }
        if (dto.getVideoName() != null) {
            videoChanged |= putIfChanged(wrapper, Bug::getVideoName,
                    blankToNull(dto.getVideoName()), bug.getVideoName(), changedFields, null);
        }
        if (videoChanged) changed = true;

        if (changed) {
            bugMapper.update(null, wrapper);
            // 留痕：编辑 Bug（仅记录真正变化的字段，逐格编辑通常只含一两个）
            writeLog(bug, userId, "UPDATE", "编辑 Bug：" + String.join("、", changedFields));
        }
    }

    /** 字符串归一化：空白（含空串）→ null（清空哨兵） */
    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** 空串归一化："" → null（进度枚举的清空哨兵，与 blankToNull 区分语义） */
    private String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    /** 数值归一化：0 → null（清空哨兵） */
    private Integer zeroToNull(Integer v) {
        return v == null || v == 0 ? null : v;
    }

    /** 数值归一化：0 → null（清空哨兵），Long 重载 */
    private Long zeroToNull(Long v) {
        return v == null || v == 0 ? null : v;
    }

    /**
     * 归一化后的新值与旧值不同才写入 set 并记录字段名；
     * name 传 null 表示仅 set 不记录（用于视频名称随视频地址合并为一个字段名）。
     *
     * @return 是否发生了变化
     */
    private <T> boolean putIfChanged(LambdaUpdateWrapper<Bug> wrapper,
                                     SFunction<Bug, ?> column, T newVal, T oldVal,
                                     List<String> changedFields, String name) {
        if (java.util.Objects.equals(newVal, oldVal)) {
            return false;
        }
        wrapper.set(column, newVal);
        if (name != null) {
            changedFields.add(name);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveBug(Long userId, Long bugId) {
        Bug bug = requireBug(bugId);
        Project project = requireProject(bug.getProjectId());
        // 仅项目经理或管理员可归档（无状态限制）
        checkArchivePermission(userId, project);
        if (bug.getArchived() != null && bug.getArchived() == 1) {
            throw new BizException(400, "请勿重复归档");
        }

        Bug update = new Bug();
        update.setId(bugId);
        update.setArchived(1);
        update.setArchivedAt(LocalDateTime.now());
        update.setArchivedBy(userId);
        bugMapper.updateById(update);
        // 留痕：归档 Bug
        writeLog(bug, userId, "ARCHIVE", "归档 Bug：" + bug.getBugNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reopenBug(Long userId, Long bugId) {
        Bug bug = requireBug(bugId);
        Project project = requireProject(bug.getProjectId());
        // 仅项目经理或管理员可重新打开
        checkArchivePermission(userId, project);
        if (bug.getArchived() == null || bug.getArchived() != 1) {
            throw new BizException(400, "Bug未归档，无需重新打开");
        }

        // updateById 忽略 null 字段，置空必须用 UpdateWrapper 显式 set
        bugMapper.update(null, new LambdaUpdateWrapper<Bug>()
                .eq(Bug::getId, bugId)
                .set(Bug::getArchived, 0)
                .set(Bug::getArchivedAt, null)
                .set(Bug::getArchivedBy, null)
                // 重新打开置空处理进度与验收进度
                .set(Bug::getHandleStatus, null)
                .set(Bug::getAcceptStatus, null));
        // 留痕：重新打开 Bug
        writeLog(bug, userId, "RESTORE", "重新打开 Bug：" + bug.getBugNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBug(Long userId, Long bugId) {
        Bug bug = requireBug(bugId);
        // 留痕：删除 Bug（删除前记录，删除后业务行不存在）
        writeLog(bug, userId, "DELETE", "删除 Bug：" + bug.getBugNo());
        // 所有人可删（归档行也可删）：连带删除截图附件记录
        attachmentMapper.delete(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getBizType, BIZ_TYPE)
                .eq(Attachment::getBizId, bugId)
                .eq(Attachment::getFileType, "IMAGE"));
        bugMapper.deleteById(bugId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> addImages(Long userId, Long bugId, BugImageAddDTO dto) {
        Bug bug = requireBug(bugId);
        // 归档表只读
        checkNotArchived(bug);
        // 每项必须是图片
        for (AttachmentDTO a : dto.getImages()) {
            if (!"IMAGE".equals(a.getFileType())) {
                throw new BizException(400, "仅支持上传图片");
            }
        }
        // 逐张落库并收集新附件 id（与入参顺序一致）
        List<Long> ids = new ArrayList<>();
        for (AttachmentDTO a : dto.getImages()) {
            Attachment row = new Attachment();
            row.setBizType(BIZ_TYPE);
            row.setBizId(bugId);
            row.setFileUrl(a.getUrl());
            row.setFileName(a.getFileName());
            row.setFileType(a.getFileType());
            row.setFileSize(a.getFileSize());
            row.setUploadedBy(userId);
            attachmentMapper.insert(row);
            ids.add(row.getId());
        }
        // 留痕：上传截图
        writeLog(bug, userId, "ADD_IMAGE", "上传截图 " + ids.size() + " 张：" + bug.getBugNo());
        return ids;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteImage(Long userId, Long bugId, Long attachmentId) {
        Bug bug = requireBug(bugId);
        // 归档表只读
        checkNotArchived(bug);
        // 校验附件归属（biz_type=BUG + biz_id + fileType=IMAGE）
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !BIZ_TYPE.equals(attachment.getBizType())
                || !bugId.equals(attachment.getBizId()) || !"IMAGE".equals(attachment.getFileType())) {
            throw new BizException(404, "图片不存在");
        }
        attachmentMapper.deleteById(attachmentId);
        // 留痕：删除截图
        writeLog(bug, userId, "DELETE_IMAGE",
                "删除截图（" + (attachment.getFileName() == null ? attachment.getId() : attachment.getFileName()) + "）：" + bug.getBugNo());
    }

    /**
     * 写操作日志（biz_type=BUG，round_no 恒 1；project_id 回填供日志按项目过滤权限）
     */
    private void writeLog(Bug bug, Long operatorId, String action, String remark) {
        BizOperationLog log = new BizOperationLog();
        log.setBizType(BIZ_TYPE);
        log.setBizId(bug.getId());
        log.setProjectId(bug.getProjectId());
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setRoundNo(1);
        log.setRemark(remark);
        operationLogMapper.insert(log);
    }

    // ==================== 列表批量装配 ====================

    /**
     * 批量装配列表 VO（全 in 批查 + 分组，避免 N+1）：截图/项目名/用户名/myRole
     */
    private List<BugListVO> assembleVOs(Long userId, List<Bug> bugs) {
        List<Long> bugIds = bugs.stream().map(Bug::getId).toList();
        List<Long> projectIds = bugs.stream().map(Bug::getProjectId).distinct().toList();

        // 批量查问题截图，按 bug 分组（fileType=IMAGE，id 升序，第一张即缩略图）
        Map<Long, List<Attachment>> imagesByBug = attachmentMapper.selectList(
                        new LambdaQueryWrapper<Attachment>()
                                .eq(Attachment::getBizType, BIZ_TYPE)
                                .in(Attachment::getBizId, bugIds)
                                .eq(Attachment::getFileType, "IMAGE")
                                .orderByAsc(Attachment::getId))
                .stream().collect(Collectors.groupingBy(Attachment::getBizId));

        // 批量查项目（projectName + 创建者，用于组装 myRole）
        Map<Long, Project> projectMap = projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, p -> p));

        // 批量查用户姓名（负责人 + 测试人 + 创建人 + 归档人）
        Set<Long> userIds = new LinkedHashSet<>();
        bugs.forEach(b -> {
            if (b.getOwnerId() != null) {
                userIds.add(b.getOwnerId());
            }
            if (b.getTesterId() != null) {
                userIds.add(b.getTesterId());
            }
            userIds.add(b.getCreatedBy());
            if (b.getArchivedBy() != null) {
                userIds.add(b.getArchivedBy());
            }
        });
        Map<Long, String> nameMap = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getRealName));

        // 当前用户在这些项目的工种（myRole 组装用）
        Map<Long, List<String>> myCraftsMap = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(ProjectMember::getUserId, userId)
                                .in(ProjectMember::getProjectId, projectIds))
                .stream().collect(Collectors.groupingBy(ProjectMember::getProjectId,
                        Collectors.mapping(ProjectMember::getCraft, Collectors.toList())));

        List<BugListVO> records = new ArrayList<>();
        for (Bug bug : bugs) {
            BugListVO vo = new BugListVO();
            vo.setId(bug.getId());
            vo.setBugNo(bug.getBugNo());
            vo.setProjectId(bug.getProjectId());
            Project project = projectMap.get(bug.getProjectId());
            vo.setProjectName(project == null ? null : project.getName());
            vo.setVersion(bug.getVersion());
            vo.setFeature(bug.getFeature());
            vo.setDescription(bug.getDescription());
            vo.setPriority(bug.getPriority());
            vo.setOwnerId(bug.getOwnerId());
            vo.setOwnerName(bug.getOwnerId() == null ? null : nameMap.get(bug.getOwnerId()));
            vo.setHandleStatus(bug.getHandleStatus());
            vo.setTesterId(bug.getTesterId());
            vo.setTesterName(bug.getTesterId() == null ? null : nameMap.get(bug.getTesterId()));
            vo.setAcceptStatus(bug.getAcceptStatus());
            vo.setSolution(bug.getSolution());
            vo.setDevRemark(bug.getDevRemark());
            vo.setTestRemark(bug.getTestRemark());
            vo.setVideoUrl(bug.getVideoUrl());
            vo.setVideoName(bug.getVideoName());
            vo.setCreatedBy(bug.getCreatedBy());
            vo.setCreatorName(nameMap.get(bug.getCreatedBy()));
            vo.setCreatedAt(bug.getCreatedAt());
            vo.setUpdatedAt(toMillis(bug.getUpdatedAt()));
            vo.setArchived(bug.getArchived());
            vo.setArchivedAt(bug.getArchivedAt());
            vo.setArchivedByName(bug.getArchivedBy() == null ? null : nameMap.get(bug.getArchivedBy()));
            vo.setImages(imagesByBug.getOrDefault(bug.getId(), List.of()).stream()
                    .map(this::toAttachmentVO).toList());
            vo.setMyRole(buildMyRole(project, userId, myCraftsMap.get(bug.getProjectId())));
            records.add(vo);
        }
        return records;
    }

    // ==================== 编号生成 ====================

    /**
     * 生成 Bug 编号 BUG-{项目缩写}-{4位流水}：
     * Redis INCR seq:bug:{projectId}；key 不存在时先从 DB 最大流水回填（SETNX 防并发覆盖）
     */
    private String generateBugNo(Project project) {
        String key = SEQ_KEY_PREFIX + project.getId();
        Boolean exists = stringRedisTemplate.hasKey(key);
        if (exists == null || !exists) {
            Long maxSeq = bugMapper.selectMaxSeq(project.getId());
            stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(maxSeq == null ? 0 : maxSeq));
        }
        Long seq = stringRedisTemplate.opsForValue().increment(key);
        return "BUG-" + project.getCode() + "-" + String.format("%04d", seq);
    }

    // ==================== 私有校验 ====================

    /**
     * 归档/重新打开权限：项目经理（项目创建者）或全局管理员
     */
    private void checkArchivePermission(Long userId, Project project) {
        if (project.getCreatorId().equals(userId)) {
            return;
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getIsAdmin() == null || user.getIsAdmin() != 1) {
            throw new BizException(403, "无权限");
        }
    }

    /**
     * 已归档的 Bug 只读，不可编辑/不可改截图
     */
    private void checkNotArchived(Bug bug) {
        if (bug.getArchived() != null && bug.getArchived() == 1) {
            throw new BizException(400, "Bug已归档，不可操作");
        }
    }

    // ==================== 通用私有方法 ====================

    /**
     * 按 id 查询 Bug，不存在抛 404
     */
    private Bug requireBug(Long bugId) {
        Bug bug = bugMapper.selectById(bugId);
        if (bug == null) {
            throw new BizException(404, "Bug不存在");
        }
        return bug;
    }

    /**
     * 按 id 查询项目，不存在抛 404
     */
    private Project requireProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(404, "项目不存在");
        }
        return project;
    }

    /**
     * 组装我的角色：项目创建者含 PM；兼任工种时为 PM + 工种；普通成员返回工种数组；无角色返回空数组
     */
    private List<String> buildMyRole(Project project, Long userId, List<String> crafts) {
        List<String> roles = new ArrayList<>();
        if (project != null && project.getCreatorId().equals(userId)) {
            roles.add("PM");
        }
        if (crafts != null) {
            crafts.stream().distinct().forEach(roles::add);
        }
        return roles;
    }

    /**
     * 附件实体 -> VO
     */
    private AttachmentVO toAttachmentVO(Attachment attachment) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(attachment.getId());
        vo.setUrl(attachment.getFileUrl());
        vo.setFileName(attachment.getFileName());
        vo.setFileType(attachment.getFileType());
        vo.setFileSize(attachment.getFileSize());
        return vo;
    }

    /**
     * LocalDateTime -> 毫秒时间戳，null 安全
     */
    private Long toMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
