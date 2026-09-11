package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.common.CraftConstants;
import com.pm.common.NotifyTypes;
import com.pm.dto.AttachmentDTO;
import com.pm.dto.ParticipantDTO;
import com.pm.dto.RequirementCancelDTO;
import com.pm.dto.RequirementCreateDTO;
import com.pm.dto.RequirementUpdateDTO;
import com.pm.entity.Attachment;
import com.pm.entity.BizOperationLog;
import com.pm.entity.Project;
import com.pm.entity.ProjectMember;
import com.pm.entity.Requirement;
import com.pm.entity.RequirementParticipant;
import com.pm.entity.ScheduleRow;
import com.pm.entity.User;
import com.pm.mapper.AttachmentMapper;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.NotificationService;
import com.pm.service.RequirementService;
import com.pm.service.RequirementStageService;
import com.pm.vo.AttachmentVO;
import com.pm.vo.CraftScheduleBatchVO;
import com.pm.vo.CraftScheduleVO;
import com.pm.vo.OperationLogVO;
import com.pm.vo.ParticipantVO;
import com.pm.vo.RequirementDetailVO;
import com.pm.vo.RequirementListVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 需求管理服务实现
 * 需求状态为派生值（排期数据+日期 → stage 纯函数），本服务仅直写两个人工决定：取消/归档（终态不参与派生）；
 * 其余状态流转由排期写路径与定时任务经 RequirementStageService.syncStage 统一对账；
 * 参与人变更联动排期行见 applyParticipantDiff：新增 → 在已有排期的月份补生成排期行；移除 → 排期行标 VOID
 */
@Service
@RequiredArgsConstructor
public class RequirementServiceImpl implements RequirementService {

    /** 需求流水号 Redis 键前缀：seq:req:{projectId} */
    private static final String SEQ_KEY_PREFIX = "seq:req:";

    /** 状态：待排期 */
    private static final String STATUS_PENDING = "PENDING";
    /** 状态：待开始 */
    private static final String STATUS_READY = "READY";
    /** 状态：开发中 */
    private static final String STATUS_DEV = "DEV";
    /** 状态：测试中 */
    private static final String STATUS_TEST = "TEST";
    /** 状态：待归档 */
    private static final String STATUS_ARCHIVING = "ARCHIVING";
    /** 状态：已归档（终态） */
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    /** 状态：已取消（终态） */
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** 全部合法状态（列表筛选参数校验用；READY 为派生化前的历史值，仅保留兼容校验） */
    private static final Set<String> ALL_STATUSES = Set.of(STATUS_PENDING, STATUS_READY, STATUS_DEV,
            STATUS_TEST, STATUS_ARCHIVING, STATUS_ARCHIVED, STATUS_CANCELLED);

    /** 排期行状态：待开始 */
    private static final String ROW_STATUS_TODO = "TODO";
    /** 排期行状态：作废（参与人移除） */
    private static final String ROW_STATUS_VOID = "VOID";

    /** 留痕/附件/通知使用的业务类型 */
    private static final String BIZ_TYPE = "REQUIREMENT";

    /** 优先级代码 -> 中文名（变更摘要用） */
    private static final Map<Integer, String> PRIORITY_NAMES = Map.of(1, "高", 2, "中", 3, "低");

    /** 工种代码 -> 摘要用短名（如 前端开发 -> 前端），与操作留痕摘要口径一致 */
    private static final Map<String, String> CRAFT_SHORT_NAMES = Map.of(
            CraftConstants.PLANNER, "策划", CraftConstants.FE, "前端", CraftConstants.BE, "后端",
            CraftConstants.OPS, "运维", CraftConstants.QA, "测试", CraftConstants.UI, "UI");

    private final RequirementMapper requirementMapper;
    private final RequirementParticipantMapper participantMapper;
    private final ScheduleRowMapper scheduleRowMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final AttachmentMapper attachmentMapper;
    private final BizOperationLogMapper operationLogMapper;
    private final NotificationService notificationService;
    private final RequirementStageService stageSyncService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Page<RequirementListVO> pageRequirements(Long userId, Long projectId, String status, Integer priority,
                                                    Long plannerId, String keyword, boolean excludeTerminal,
                                                    long page, long size) {
        // 筛选参数合法性校验
        if (StringUtils.hasText(status) && !ALL_STATUSES.contains(status)) {
            throw new BizException(400, "非法需求状态：" + status);
        }
        if (priority != null && (priority < 1 || priority > 3)) {
            throw new BizException(400, "优先级只能为1/2/3");
        }
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<>();
        // projectId 空 = 全部项目（全局汇总视图，任何登录用户可见，不按成员身份过滤）
        wrapper.eq(projectId != null, Requirement::getProjectId, projectId);
        wrapper.eq(StringUtils.hasText(status), Requirement::getStatus, status);
        // 排除终态（需求池：未归档未取消的需求一直可选）
        if (excludeTerminal) {
            wrapper.notIn(Requirement::getStatus, "ARCHIVED", "CANCELLED");
        }
        wrapper.eq(priority != null, Requirement::getPriority, priority);
        wrapper.eq(plannerId != null, Requirement::getPlannerId, plannerId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            // keyword 模糊匹配需求名称或编号
            wrapper.and(w -> w.like(Requirement::getTitle, kw).or().like(Requirement::getReqNo, kw));
        }
        wrapper.orderByDesc(Requirement::getId);
        Page<Requirement> reqPage = requirementMapper.selectPage(new Page<>(page, size), wrapper);

        Page<RequirementListVO> voPage = new Page<>(reqPage.getCurrent(), reqPage.getSize(), reqPage.getTotal());
        List<Requirement> requirements = reqPage.getRecords();
        if (requirements.isEmpty()) {
            voPage.setRecords(new ArrayList<>());
            return voPage;
        }

        List<Long> reqIds = requirements.stream().map(Requirement::getId).toList();
        List<Long> projectIds = requirements.stream().map(Requirement::getProjectId).distinct().toList();

        // 批量查项目（projectName + 创建者，用于组装 myRole）
        Map<Long, Project> projectMap = projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, p -> p));

        // 批量查参与人，按需求分组
        Map<Long, List<RequirementParticipant>> participantsByReq = participantMapper.selectList(
                        new LambdaQueryWrapper<RequirementParticipant>()
                                .in(RequirementParticipant::getRequirementId, reqIds)
                                .orderByAsc(RequirementParticipant::getId))
                .stream().collect(Collectors.groupingBy(RequirementParticipant::getRequirementId));

        // 批量查用户姓名（策划 + 创建人 + 参与人）
        Set<Long> userIds = new LinkedHashSet<>();
        requirements.forEach(r -> {
            userIds.add(r.getPlannerId());
            userIds.add(r.getCreatedBy());
        });
        participantsByReq.values().forEach(rows -> rows.forEach(p -> userIds.add(p.getUserId())));
        Map<Long, String> nameMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));

        // 当前用户在这些项目的工种（myRole 组装用）
        Map<Long, List<String>> myCraftsMap = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(ProjectMember::getUserId, userId)
                                .in(ProjectMember::getProjectId, projectIds))
                .stream().collect(Collectors.groupingBy(ProjectMember::getProjectId,
                        Collectors.mapping(ProjectMember::getCraft, Collectors.toList())));

        // 批量查各工种排期日期区间（进度缩略图的开发/测试节点按日期点亮用），按需求分组
        Map<Long, List<CraftScheduleVO>> schedulesByReq = new HashMap<>();
        for (CraftScheduleBatchVO b : scheduleRowMapper.selectCraftDateRangeBatch(reqIds)) {
            CraftScheduleVO vo = new CraftScheduleVO();
            vo.setCraft(b.getCraft());
            vo.setStartDate(b.getStartDate());
            vo.setEndDate(b.getEndDate());
            schedulesByReq.computeIfAbsent(b.getRequirementId(), k -> new ArrayList<>()).add(vo);
        }

        List<RequirementListVO> records = new ArrayList<>();
        for (Requirement req : requirements) {
            RequirementListVO vo = new RequirementListVO();
            vo.setId(req.getId());
            vo.setReqNo(req.getReqNo());
            vo.setProjectId(req.getProjectId());
            Project project = projectMap.get(req.getProjectId());
            vo.setProjectName(project == null ? null : project.getName());
            vo.setTitle(req.getTitle());
            vo.setPriority(req.getPriority());
            vo.setStatus(req.getStatus());
            vo.setPlannerId(req.getPlannerId());
            vo.setPlannerName(nameMap.get(req.getPlannerId()));
            vo.setCreatedBy(req.getCreatedBy());
            vo.setCreatorName(nameMap.get(req.getCreatedBy()));
            vo.setCreatedAt(req.getCreatedAt());
            vo.setUpdatedAt(toMillis(req.getUpdatedAt()));
            vo.setParticipants(toParticipantVOs(participantsByReq.getOrDefault(req.getId(), List.of()), nameMap));
            vo.setCraftSchedules(schedulesByReq.getOrDefault(req.getId(), List.of()));
            vo.setMyRole(buildMyRole(project, userId, myCraftsMap.get(req.getProjectId())));
            records.add(vo);
        }
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public RequirementDetailVO getRequirementDetail(Long userId, Long requirementId) {
        Requirement req = requireRequirement(requirementId);
        Project project = projectMapper.selectById(req.getProjectId());
        // 参与人（平铺返回，前端按工种分组）
        List<RequirementParticipant> participants = participantMapper.selectList(
                new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getRequirementId, requirementId)
                        .orderByAsc(RequirementParticipant::getId));
        // 附件
        List<Attachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getBizType, BIZ_TYPE)
                        .eq(Attachment::getBizId, requirementId)
                        .orderByAsc(Attachment::getId));
        // 用户姓名（策划 + 参与人 + 取消人）
        Set<Long> userIds = new LinkedHashSet<>();
        userIds.add(req.getPlannerId());
        userIds.add(req.getCreatedBy());
        participants.forEach(p -> userIds.add(p.getUserId()));
        if (req.getCancelledBy() != null) {
            userIds.add(req.getCancelledBy());
        }
        Map<Long, String> nameMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));

        RequirementDetailVO vo = new RequirementDetailVO();
        vo.setId(req.getId());
        vo.setReqNo(req.getReqNo());
        vo.setProjectId(req.getProjectId());
        vo.setProjectName(project == null ? null : project.getName());
        vo.setTitle(req.getTitle());
        vo.setDescription(req.getDescription());
        vo.setPriority(req.getPriority());
        vo.setStatus(req.getStatus());
        vo.setPlannerId(req.getPlannerId());
        vo.setPlannerName(nameMap.get(req.getPlannerId()));
        vo.setCreatedBy(req.getCreatedBy());
        vo.setCreatorName(nameMap.get(req.getCreatedBy()));
        vo.setCreatedAt(req.getCreatedAt());
        vo.setUpdatedAt(toMillis(req.getUpdatedAt()));
        vo.setCancelledBy(req.getCancelledBy());
        vo.setCancelledByName(req.getCancelledBy() == null ? null : nameMap.get(req.getCancelledBy()));
        vo.setCancelledAt(req.getCancelledAt());
        vo.setCancelReason(req.getCancelReason());
        vo.setArchivedAt(req.getArchivedAt());
        vo.setParticipants(toParticipantVOs(participants, nameMap));
        // 工种排期区间（全部非 VOID 行跨全部月份按 craft 聚合 min/max 格子日；进度条分支卡日期配色）
        vo.setCraftSchedules(scheduleRowMapper.selectCraftDateRange(requirementId));
        vo.setAttachments(attachments.stream().map(this::toAttachmentVO).toList());
        // 当前用户工种（myRole 组装用）
        List<String> myCrafts = project == null ? List.of()
                : projectMapper.selectCrafts(req.getProjectId(), userId);
        vo.setMyRole(buildMyRole(project, userId, myCrafts));
        return vo;
    }

    @Override
    public List<OperationLogVO> listLogs(Long requirementId, Long userId) {
        Requirement req = requireRequirement(requirementId);
        // 权限：仅管理员或该项目 PM（创建者）可查看操作日志
        boolean admin = isAdmin(userId);
        if (!admin) {
            Project project = requireProject(req.getProjectId());
            if (!project.getCreatorId().equals(userId)) {
                throw new BizException(403, "仅项目经理或管理员可查看操作日志");
            }
        }
        List<BizOperationLog> logs = operationLogMapper.selectList(
                new LambdaQueryWrapper<BizOperationLog>()
                        .eq(BizOperationLog::getBizType, BIZ_TYPE)
                        .eq(BizOperationLog::getBizId, requirementId)
                        .orderByDesc(BizOperationLog::getId));
        if (logs.isEmpty()) {
            return List.of();
        }
        // 批量查操作人姓名
        Set<Long> operatorIds = logs.stream().map(BizOperationLog::getOperatorId).collect(Collectors.toSet());
        Map<Long, String> nameMap = userMapper.selectBatchIds(operatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));
        return logs.stream().map(log -> {
            OperationLogVO vo = new OperationLogVO();
            vo.setId(log.getId());
            vo.setOperatorId(log.getOperatorId());
            vo.setOperatorName(nameMap.get(log.getOperatorId()));
            vo.setAction(log.getAction());
            vo.setRemark(log.getRemark());
            vo.setRoundNo(log.getRoundNo());
            vo.setCreatedAt(log.getCreatedAt());
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRequirement(Long userId, RequirementCreateDTO dto) {
        Project project = requireProject(dto.getProjectId());
        // 权限：该项目 PM（创建者）或 PLANNER 工种成员
        checkCreatePermission(userId, project);
        // 参与人整理 + 合法性校验（每人必须是该项目对应工种成员）
        List<ParticipantDTO> participants = normalizeParticipants(dto.getParticipants());
        validateParticipants(project.getId(), participants);
        // 策划：默认当前用户，必须为项目成员（PM 或任一工种成员）
        Long plannerId = dto.getPlannerId() == null ? userId : dto.getPlannerId();
        if (!isProjectMember(plannerId, project)) {
            throw new BizException(400, "策划必须是本项目成员");
        }

        Requirement req = new Requirement();
        req.setReqNo(generateReqNo(project));
        req.setProjectId(project.getId());
        req.setTitle(dto.getTitle());
        req.setDescription(dto.getDescription());
        req.setPriority(dto.getPriority() == null ? 2 : dto.getPriority());
        // 创建后进入项目需求池
        req.setStatus(STATUS_PENDING);
        req.setPlannerId(plannerId);
        req.setCreatedBy(userId);
        requirementMapper.insert(req);

        // 参与人落库 + 通知被添加人
        insertParticipants(req.getId(), participants);
        for (ParticipantDTO p : participants) {
            notificationService.send(p.getUserId(), NotifyTypes.REQ_PARTICIPANT_ADDED,
                    "添加为需求参与人",
                    "您被添加为需求「" + req.getTitle() + "」的参与人（" + craftName(p.getCraft()) + "）",
                    BIZ_TYPE, req.getId());
        }

        // 附件落库（前端先调 /api/files/upload 再回传）
        insertAttachments(req.getId(), userId, dto.getAttachments());

        // 操作留痕
        writeLog(req, userId, "CREATE", "创建需求");
        return req.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRequirement(Long userId, Long requirementId, RequirementUpdateDTO dto) {
        Requirement req = requireRequirement(requirementId);
        Project project = requireProject(req.getProjectId());
        // 权限：项目 PM 或需求创建人
        if (!project.getCreatorId().equals(userId) && !req.getCreatedBy().equals(userId)) {
            throw new BizException(403, "无权限");
        }
        // 终态校验
        checkNotTerminal(req);
        // 乐观锁校验
        checkOptimisticLock(req.getUpdatedAt(), dto.getUpdatedAt());

        // ---------- 1. 基础字段变更检测（null = 不修改） ----------
        Requirement update = new Requirement();
        update.setId(requirementId);
        boolean rowChanged = false;
        boolean titleChanged = false;
        boolean priorityChanged = false;
        boolean plannerChanged = false;
        boolean descriptionChanged = false;

        if (dto.getTitle() != null) {
            if (dto.getTitle().isBlank()) {
                throw new BizException(400, "需求名称不能为空");
            }
            if (!dto.getTitle().equals(req.getTitle())) {
                update.setTitle(dto.getTitle());
                titleChanged = true;
                rowChanged = true;
            }
        }
        if (dto.getPriority() != null && !dto.getPriority().equals(req.getPriority())) {
            update.setPriority(dto.getPriority());
            priorityChanged = true;
            rowChanged = true;
        }
        if (dto.getPlannerId() != null && !dto.getPlannerId().equals(req.getPlannerId())) {
            if (!isProjectMember(dto.getPlannerId(), project)) {
                throw new BizException(400, "策划必须是本项目成员");
            }
            update.setPlannerId(dto.getPlannerId());
            plannerChanged = true;
            rowChanged = true;
        }
        if (dto.getDescription() != null && !Objects.equals(dto.getDescription(), req.getDescription())) {
            update.setDescription(dto.getDescription());
            descriptionChanged = true;
            rowChanged = true;
        }

        // ---------- 2. 参与人 diff（null = 不修改，非 null 与库中全量比对增删） ----------
        List<ParticipantDTO> addedParticipants = List.of();
        List<RequirementParticipant> removedParticipants = List.of();
        boolean participantChanged = false;
        if (dto.getParticipants() != null) {
            List<ParticipantDTO> incoming = normalizeParticipants(dto.getParticipants());
            List<RequirementParticipant> current = participantMapper.selectList(
                    new LambdaQueryWrapper<RequirementParticipant>()
                            .eq(RequirementParticipant::getRequirementId, requirementId)
                            .orderByAsc(RequirementParticipant::getId));
            Set<String> currentKeys = current.stream().map(this::participantKey).collect(Collectors.toSet());
            Set<String> incomingKeys = incoming.stream().map(this::participantKey).collect(Collectors.toSet());
            addedParticipants = incoming.stream().filter(p -> !currentKeys.contains(participantKey(p))).toList();
            removedParticipants = current.stream().filter(p -> !incomingKeys.contains(participantKey(p))).toList();
            // 仅校验新增参与人（存量成员被移出项目不阻断本次编辑）
            validateParticipants(project.getId(), addedParticipants);
            participantChanged = !addedParticipants.isEmpty() || !removedParticipants.isEmpty();
        }

        // ---------- 3. 附件 diff（null = 不修改，非 null 全量替换） ----------
        boolean attachmentChanged = false;
        int attachmentAdded = 0;
        int attachmentRemoved = 0;
        if (dto.getAttachments() != null) {
            List<Attachment> currentAttachments = attachmentMapper.selectList(
                    new LambdaQueryWrapper<Attachment>()
                            .eq(Attachment::getBizType, BIZ_TYPE)
                            .eq(Attachment::getBizId, requirementId)
                            .orderByAsc(Attachment::getId));
            Set<String> currentUrls = currentAttachments.stream().map(Attachment::getFileUrl).collect(Collectors.toSet());
            Set<String> incomingUrls = dto.getAttachments().stream().map(AttachmentDTO::getUrl).collect(Collectors.toSet());
            attachmentAdded = (int) dto.getAttachments().stream()
                    .filter(a -> !currentUrls.contains(a.getUrl())).count();
            attachmentRemoved = (int) currentAttachments.stream()
                    .filter(a -> !incomingUrls.contains(a.getFileUrl())).count();
            attachmentChanged = attachmentAdded > 0 || attachmentRemoved > 0;
        }

        // ---------- 4. 落库 + 留痕（无任何实际变更则不写日志不动版本） ----------
        if (rowChanged || participantChanged || attachmentChanged) {
            // 显式推进 updated_at（保证严格大于旧值），作为乐观锁版本号
            update.setUpdatedAt(nextVersionTime(req.getUpdatedAt()));
            requirementMapper.updateById(update);

            if (participantChanged) {
                applyParticipantDiff(requirementId, userId, req.getTitle(), addedParticipants, removedParticipants);
            }
            if (attachmentChanged) {
                // 附件全量替换（简单可靠）：先删后插
                attachmentMapper.delete(new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getBizType, BIZ_TYPE)
                        .eq(Attachment::getBizId, requirementId));
                insertAttachments(requirementId, userId, dto.getAttachments());
            }
            writeLog(req, userId, "UPDATE", buildUpdateSummary(req, dto, titleChanged,
                    priorityChanged, plannerChanged, descriptionChanged,
                    addedParticipants, removedParticipants, attachmentChanged, attachmentAdded, attachmentRemoved));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelRequirement(Long userId, Long requirementId, RequirementCancelDTO dto) {
        Requirement req = requireRequirement(requirementId);
        Project project = requireProject(req.getProjectId());
        // 仅项目 PM 可取消
        if (!project.getCreatorId().equals(userId)) {
            throw new BizException(403, "无权限");
        }
        // 终态不可取消
        if (STATUS_ARCHIVED.equals(req.getStatus())) {
            throw new BizException(400, "需求已归档，不可取消");
        }
        if (STATUS_CANCELLED.equals(req.getStatus())) {
            throw new BizException(400, "需求已取消，请勿重复操作");
        }

        Requirement update = new Requirement();
        update.setId(requirementId);
        update.setStatus(STATUS_CANCELLED);
        update.setCancelledBy(userId);
        update.setCancelledAt(LocalDateTime.now());
        update.setCancelReason(dto.getReason());
        requirementMapper.updateById(update);

        // 操作留痕
        writeLog(req, userId, "CANCEL", "取消原因：" + dto.getReason());

        // 通知全部参与人 + 策划（按用户去重）
        Set<Long> receiverIds = new LinkedHashSet<>();
        participantMapper.selectList(new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getRequirementId, requirementId))
                .forEach(p -> receiverIds.add(p.getUserId()));
        receiverIds.add(req.getPlannerId());
        for (Long receiverId : receiverIds) {
            notificationService.send(receiverId, NotifyTypes.REQ_CANCELLED, "需求已取消",
                    "需求「" + req.getTitle() + "」已被取消，取消原因：" + dto.getReason(),
                    BIZ_TYPE, requirementId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveRequirement(Long userId, Long requirementId) {
        Requirement req = requireRequirement(requirementId);
        Project project = requireProject(req.getProjectId());
        // 仅项目 PM 可归档
        if (!project.getCreatorId().equals(userId)) {
            throw new BizException(403, "无权限");
        }
        // 仅「待归档」状态可归档
        if (!STATUS_ARCHIVING.equals(req.getStatus())) {
            throw new BizException(400, "当前状态不可归档");
        }

        Requirement update = new Requirement();
        update.setId(requirementId);
        update.setStatus(STATUS_ARCHIVED);
        update.setArchivedAt(LocalDateTime.now());
        requirementMapper.updateById(update);

        // 操作留痕
        writeLog(req, userId, "ARCHIVE", "需求归档");

        // 通知全部参与人
        List<RequirementParticipant> participants = participantMapper.selectList(
                new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getRequirementId, requirementId));
        for (RequirementParticipant p : participants) {
            notificationService.send(p.getUserId(), NotifyTypes.REQ_ARCHIVED, "需求已归档",
                    "您参与的需求「" + req.getTitle() + "」已归档",
                    BIZ_TYPE, requirementId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeParticipantByProjectMember(Long projectId, Long targetUserId, String craft, Long operatorId) {
        // 该项目下全部非终态需求（终态历史冻结：已归档/已取消不联动）
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getProjectId, projectId)
                .notIn(Requirement::getStatus, List.of(STATUS_ARCHIVED, STATUS_CANCELLED)));
        int affected = 0;
        for (Requirement req : reqs) {
            LambdaQueryWrapper<RequirementParticipant> qw = new LambdaQueryWrapper<RequirementParticipant>()
                    .eq(RequirementParticipant::getRequirementId, req.getId())
                    .eq(RequirementParticipant::getUserId, targetUserId);
            if (craft != null) {
                // 按工种移除：仅联动该工种的参与身份（用户可能以其他工种仍是项目成员）
                qw.eq(RequirementParticipant::getCraft, craft);
            }
            List<RequirementParticipant> removed = participantMapper.selectList(qw);
            if (removed.isEmpty()) {
                continue;
            }
            // 复用参与人 diff 落库：删参与行 + 排期行标 VOID（跨全部月份）+ 通知本人 + syncStage 对账
            applyParticipantDiff(req.getId(), operatorId, req.getTitle(), List.of(), removed);
            affected++;
        }
        return affected;
    }

    // ==================== 编号生成 ====================

    /**
     * 生成需求编号 REQ-{项目缩写}-{4位流水}：
     * Redis INCR seq:req:{projectId}；key 不存在时先从 DB 最大流水回填（SETNX 防并发覆盖）
     */
    private String generateReqNo(Project project) {
        String key = SEQ_KEY_PREFIX + project.getId();
        Boolean exists = stringRedisTemplate.hasKey(key);
        if (exists == null || !exists) {
            Long maxSeq = requirementMapper.selectMaxSeq(project.getId());
            stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(maxSeq == null ? 0 : maxSeq));
        }
        Long seq = stringRedisTemplate.opsForValue().increment(key);
        return "REQ-" + project.getCode() + "-" + String.format("%04d", seq);
    }

    // ==================== 私有校验 ====================

    /**
     * 创建需求权限：该项目 PM（创建者）或 PLANNER 工种成员
     */
    private void checkCreatePermission(Long userId, Project project) {
        if (project.getCreatorId().equals(userId)) {
            return;
        }
        List<String> crafts = projectMapper.selectCrafts(project.getId(), userId);
        if (!crafts.contains(CraftConstants.PLANNER)) {
            throw new BizException(403, "无权限");
        }
    }

    /**
     * 终态校验：已归档/已取消的需求不可编辑
     */
    private void checkNotTerminal(Requirement req) {
        if (STATUS_ARCHIVED.equals(req.getStatus()) || STATUS_CANCELLED.equals(req.getStatus())) {
            throw new BizException(400, "需求已归档/已取消，不可编辑");
        }
    }

    /**
     * 乐观锁校验：提交的 updatedAt（毫秒时间戳）必须与库中 requirement.updated_at 一致
     */
    private void checkOptimisticLock(LocalDateTime dbTime, Long submittedMillis) {
        long dbMillis = dbTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (submittedMillis == null || submittedMillis != dbMillis) {
            throw new BizException(409, "数据已被他人修改，请刷新后重试");
        }
    }

    /**
     * 校验参与人列表：工种合法 + 每人必须是该项目对应工种成员（错误信息指明具体用户与工种）
     */
    private void validateParticipants(Long projectId, List<ParticipantDTO> participants) {
        if (participants.isEmpty()) {
            return;
        }
        for (ParticipantDTO p : participants) {
            if (!CraftConstants.ALL_CRAFTS.contains(p.getCraft())) {
                throw new BizException(400, "非法工种：" + p.getCraft());
            }
        }
        // 项目全部成员的工种映射（userId -> 工种集合）
        Map<Long, Set<String>> memberCrafts = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(ProjectMember::getProjectId, projectId))
                .stream().collect(Collectors.groupingBy(ProjectMember::getUserId,
                        Collectors.mapping(ProjectMember::getCraft, Collectors.toSet())));
        // 参与人姓名（错误信息展示用）
        Set<Long> userIds = participants.stream().map(ParticipantDTO::getUserId).collect(Collectors.toSet());
        Map<Long, String> nameMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));
        for (ParticipantDTO p : participants) {
            Set<String> crafts = memberCrafts.get(p.getUserId());
            if (crafts == null || !crafts.contains(p.getCraft())) {
                throw new BizException(400, "用户" + nameMap.getOrDefault(p.getUserId(), String.valueOf(p.getUserId()))
                        + "不是本项目" + craftName(p.getCraft()) + "工种成员");
            }
        }
    }

    /**
     * 判断用户是否项目成员：项目 PM（创建者）或 project_member 任一工种
     */
    private boolean isProjectMember(Long userId, Project project) {
        if (project.getCreatorId().equals(userId)) {
            return true;
        }
        Long count = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, project.getId())
                .eq(ProjectMember::getUserId, userId));
        return count != null && count > 0;
    }

    // ==================== 私有落库 ====================

    /**
     * 参与人入参整理：null 转空列表，按 userId+craft 去重（保持提交顺序）
     */
    private List<ParticipantDTO> normalizeParticipants(List<ParticipantDTO> participants) {
        if (participants == null) {
            return List.of();
        }
        Map<String, ParticipantDTO> distinct = new LinkedHashMap<>();
        for (ParticipantDTO p : participants) {
            distinct.putIfAbsent(participantKey(p), p);
        }
        return new ArrayList<>(distinct.values());
    }

    /**
     * 参与人变更落库 + 排期行联动（状态派生化）：
     * 新增参与人 → 在该需求已有排期行的所有月份补生成排期行（新组工期为空，自然未排满）；
     * 移除参与人 → 其全部排期行（跨全部月份）标 VOID（统计 SQL 均排除 VOID，自动从排满/已排/日期判定剔除）；
     * 联动完成后按排期数据对账需求状态（新增组未排满 → 自动回退待排期）
     */
    private void applyParticipantDiff(Long requirementId, Long operatorId, String title,
                                      List<ParticipantDTO> added, List<RequirementParticipant> removed) {
        for (ParticipantDTO p : added) {
            RequirementParticipant row = new RequirementParticipant();
            row.setRequirementId(requirementId);
            row.setUserId(p.getUserId());
            row.setCraft(p.getCraft());
            participantMapper.insert(row);
            // 排期行联动：在该需求已有排期行的所有月份补生成该参与人的排期行
            syncParticipantScheduleRows(requirementId, p);
            notificationService.send(p.getUserId(), NotifyTypes.REQ_PARTICIPANT_ADDED,
                    "添加为需求参与人",
                    "您被添加为需求「" + title + "」的参与人（" + craftName(p.getCraft()) + "）",
                    BIZ_TYPE, requirementId);
        }
        for (RequirementParticipant row : removed) {
            participantMapper.deleteById(row.getId());
            // 排期行联动：该参与人的全部排期行（跨全部月份）标 VOID（保留行与格子作历史，不参与统计）
            scheduleRowMapper.update(null, new LambdaUpdateWrapper<ScheduleRow>()
                    .eq(ScheduleRow::getRequirementId, requirementId)
                    .eq(ScheduleRow::getCraft, row.getCraft())
                    .eq(ScheduleRow::getUserId, row.getUserId())
                    .set(ScheduleRow::getStatus, ROW_STATUS_VOID));
            notificationService.send(row.getUserId(), NotifyTypes.REQ_PARTICIPANT_REMOVED,
                    "移出需求参与人",
                    "您被移出需求「" + title + "」的参与人（" + craftName(row.getCraft()) + "）",
                    BIZ_TYPE, requirementId);
        }
        // 排期行集合已变化：需求状态按「排期数据+日期」派生对账，统一收口
        stageSyncService.syncStage(requirementId, operatorId);
    }

    /**
     * 参与人新增的排期行联动：在该需求已有排期行的所有月份补生成排期行（工期为空=待排期）。
     * 处理三种情况：该月已有同键 TODO 行 → 跳过；已有 VOID 行（曾移除又加回）→ 复活为 TODO；
     * 无行 → 新插入（seq 沿用该需求在该月的展示序号，同需求同月共享）
     */
    private void syncParticipantScheduleRows(Long requirementId, ParticipantDTO p) {
        // 该需求出现的全部月份及各月展示序号
        Map<Long, Integer> monthSeq = new LinkedHashMap<>();
        for (ScheduleRow r : scheduleRowMapper.selectList(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getRequirementId, requirementId)
                .orderByAsc(ScheduleRow::getMonthId))) {
            monthSeq.putIfAbsent(r.getMonthId(), r.getSeq());
        }
        if (monthSeq.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, Integer> entry : monthSeq.entrySet()) {
            ScheduleRow existing = scheduleRowMapper.selectOne(new LambdaQueryWrapper<ScheduleRow>()
                    .eq(ScheduleRow::getMonthId, entry.getKey())
                    .eq(ScheduleRow::getRequirementId, requirementId)
                    .eq(ScheduleRow::getCraft, p.getCraft())
                    .eq(ScheduleRow::getUserId, p.getUserId())
                    .last("LIMIT 1"));
            if (existing != null) {
                // 曾被移除（VOID）又重新加回：复活为待开始；仍在排期中则跳过
                if (ROW_STATUS_VOID.equals(existing.getStatus())) {
                    scheduleRowMapper.update(null, new LambdaUpdateWrapper<ScheduleRow>()
                            .eq(ScheduleRow::getId, existing.getId())
                            .set(ScheduleRow::getStatus, ROW_STATUS_TODO));
                }
                continue;
            }
            ScheduleRow row = new ScheduleRow();
            row.setMonthId(entry.getKey());
            row.setRequirementId(requirementId);
            row.setCraft(p.getCraft());
            row.setUserId(p.getUserId());
            row.setSeq(entry.getValue() != null ? entry.getValue() : 1);
            row.setStatus(ROW_STATUS_TODO);
            row.setIsOverflow(0);
            try {
                scheduleRowMapper.insert(row);
            } catch (DuplicateKeyException e) {
                // uk_month_req_craft_user 并发兜底：已存在同键行视为已生成
            }
        }
    }

    /**
     * 批量插入参与人（已通过去重与校验）
     */
    private void insertParticipants(Long requirementId, List<ParticipantDTO> participants) {
        for (ParticipantDTO p : participants) {
            RequirementParticipant row = new RequirementParticipant();
            row.setRequirementId(requirementId);
            row.setUserId(p.getUserId());
            row.setCraft(p.getCraft());
            participantMapper.insert(row);
        }
    }

    /**
     * 批量插入附件（biz_type=REQUIREMENT，uploaded_by=当前操作人）
     */
    private void insertAttachments(Long requirementId, Long uploadedBy, List<AttachmentDTO> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        for (AttachmentDTO a : attachments) {
            Attachment row = new Attachment();
            row.setBizType(BIZ_TYPE);
            row.setBizId(requirementId);
            row.setFileUrl(a.getUrl());
            row.setFileName(a.getFileName());
            row.setFileType(a.getFileType());
            row.setFileSize(a.getFileSize());
            row.setUploadedBy(uploadedBy);
            attachmentMapper.insert(row);
        }
    }

    /**
     * 写操作日志（需求恒 round_no=1；projectId 取自传入需求实体，避免每次回查）
     */
    private void writeLog(Requirement req, Long operatorId, String action, String remark) {
        BizOperationLog log = new BizOperationLog();
        log.setBizType(BIZ_TYPE);
        log.setBizId(req.getId());
        log.setProjectId(req.getProjectId());
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setRoundNo(1);
        log.setRemark(remark);
        operationLogMapper.insert(log);
    }

    /**
     * 当前用户是否全局管理员（user.is_admin=1）
     */
    private boolean isAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && user.getIsAdmin() != null && user.getIsAdmin() == 1;
    }

    // ==================== 变更摘要 ====================

    /**
     * 生成编辑变更摘要（只记录实际变化的项，多段以「；」连接），
     * 形如：标题已修改；优先级：中→高；参与人 +张三(前端)、-李四(测试)；描述已修改；附件 +2
     */
    private String buildUpdateSummary(Requirement old, RequirementUpdateDTO dto, boolean titleChanged,
                                      boolean priorityChanged, boolean plannerChanged, boolean descriptionChanged,
                                      List<ParticipantDTO> addedParticipants,
                                      List<RequirementParticipant> removedParticipants,
                                      boolean attachmentChanged, int attachmentAdded, int attachmentRemoved) {
        List<String> parts = new ArrayList<>();
        if (titleChanged) {
            parts.add("标题已修改");
        }
        if (priorityChanged) {
            parts.add("优先级：" + PRIORITY_NAMES.getOrDefault(old.getPriority(), "?")
                    + "→" + PRIORITY_NAMES.getOrDefault(dto.getPriority(), "?"));
        }
        if (plannerChanged) {
            parts.add("策划：" + userName(old.getPlannerId()) + "→" + userName(dto.getPlannerId()));
        }
        if (!addedParticipants.isEmpty() || !removedParticipants.isEmpty()) {
            List<String> items = new ArrayList<>();
            if (!addedParticipants.isEmpty()) {
                items.add("+" + addedParticipants.stream()
                        .map(p -> userName(p.getUserId()) + "(" + craftShortName(p.getCraft()) + ")")
                        .collect(Collectors.joining("、")));
            }
            if (!removedParticipants.isEmpty()) {
                items.add("-" + removedParticipants.stream()
                        .map(p -> userName(p.getUserId()) + "(" + craftShortName(p.getCraft()) + ")")
                        .collect(Collectors.joining("、")));
            }
            parts.add("参与人 " + String.join("、", items));
        }
        if (descriptionChanged) {
            parts.add("描述已修改");
        }
        if (attachmentChanged) {
            List<String> items = new ArrayList<>();
            if (attachmentAdded > 0) {
                items.add("+" + attachmentAdded);
            }
            if (attachmentRemoved > 0) {
                items.add("-" + attachmentRemoved);
            }
            parts.add("附件 " + String.join("、", items));
        }
        return String.join("；", parts);
    }

    // ==================== 通用私有方法 ====================

    /**
     * 按 id 查询需求，不存在抛 404
     */
    private Requirement requireRequirement(Long requirementId) {
        Requirement req = requirementMapper.selectById(requirementId);
        if (req == null) {
            throw new BizException(404, "需求不存在");
        }
        return req;
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
     * 参与人唯一键：userId + craft
     */
    private String participantKey(ParticipantDTO p) {
        return p.getUserId() + ":" + p.getCraft();
    }

    private String participantKey(RequirementParticipant p) {
        return p.getUserId() + ":" + p.getCraft();
    }

    /**
     * 查询用户姓名，用户不存在时回退为 userId 字符串
     */
    private String userName(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? String.valueOf(userId) : user.getRealName();
    }

    /**
     * 工种代码 -> 中文名（通知内容用全名，如「前端开发」）
     */
    private String craftName(String craft) {
        return CraftConstants.CRAFT_NAMES.getOrDefault(craft, craft);
    }

    /**
     * 工种代码 -> 摘要短名（如 FE -> 前端）
     */
    private String craftShortName(String craft) {
        return CRAFT_SHORT_NAMES.getOrDefault(craft, craft);
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
     * 参与人实体列表 -> 平铺 VO 列表
     */
    private List<ParticipantVO> toParticipantVOs(List<RequirementParticipant> participants, Map<Long, String> nameMap) {
        return participants.stream().map(p -> {
            ParticipantVO vo = new ParticipantVO();
            vo.setUserId(p.getUserId());
            vo.setRealName(nameMap.get(p.getUserId()));
            vo.setCraft(p.getCraft());
            return vo;
        }).toList();
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
     * LocalDateTime -> 毫秒时间戳（前端编辑时原样回传做乐观锁校验），null 安全
     */
    private Long toMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 计算下一次 updated_at：截断到秒并保证严格大于旧值，
     * 避免数据库秒级精度下同秒内连续编辑导致乐观锁版本不前进
     */
    private LocalDateTime nextVersionTime(LocalDateTime oldTime) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (oldTime != null && !now.isAfter(oldTime)) {
            return oldTime.plusSeconds(1);
        }
        return now;
    }
}
