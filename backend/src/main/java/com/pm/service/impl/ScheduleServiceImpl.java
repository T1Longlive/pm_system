package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pm.common.BizException;
import com.pm.common.CraftConstants;
import com.pm.common.NotifyTypes;
import com.pm.dto.ScheduleMonthCreateDTO;
import com.pm.dto.ScheduleRequirementDTO;
import com.pm.dto.ScheduleRowSaveDTO;
import com.pm.entity.BizOperationLog;
import com.pm.entity.CalendarDay;
import com.pm.entity.Project;
import com.pm.entity.Requirement;
import com.pm.entity.RequirementParticipant;
import com.pm.entity.ScheduleCell;
import com.pm.entity.ScheduleMonth;
import com.pm.entity.ScheduleRow;
import com.pm.entity.User;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleCellMapper;
import com.pm.mapper.ScheduleMonthMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.CalendarService;
import com.pm.service.NotificationService;
import com.pm.service.RequirementStageService;
import com.pm.service.ScheduleService;
import com.pm.vo.CalendarDayVO;
import com.pm.vo.CopyRowResultVO;
import com.pm.vo.RequirementGroupAggVO;
import com.pm.vo.ScheduleIncompleteVO;
import com.pm.vo.ScheduleMonthVO;
import com.pm.vo.ScheduleRowSaveResultVO;
import com.pm.vo.ScheduleViewVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排期管理服务实现
 * 核心规则：
 * 1. 排期行生成：PM 从需求池选 PENDING 需求，按需求参与人生成行（每行 工种×负责人 唯一），
 *    seq=当月该表已有需求数+1；同一需求同月只生成一次
 * 2. 保存校验：VOID 行拒绝编辑（非 VOID 行随时可编辑）；乐观锁（updatedAt 毫秒比对）；
 *    格子日期必须属于所在月且为可排班日（WORKDAY/ADJUSTED）；单格 0<v≤1 且为 0.1 的整数倍；
 *    同人同日跨需求合计≤1
 * 3. 跨月：plannedEffort 非空 且 当月格子合计<plannedEffort 且 当月剩余可填工作日数为 0 时，
 *    自动创建下月表并追加 is_overflow=1 的顺延行；PM 也可手动"添加到下月"（复制行，带原工期）
 * 4. 行状态：仅 TODO/VOID 落库；已排/排期中/未排期三态由前端按 工期 vs 已排合计 派生
 * 5. 需求状态派生化：status 由 stage 纯函数按「排期数据 + 日期」派生（RequirementStageService），
 *    本服务不直接写需求状态；全部写路径（加入/移除/保存行/复制下月）末尾统一调 syncStage 对账，
 *    纯时间流逝导致的变化（DEV→TEST→ARCHIVING 等）由定时任务全量对账兜底；
 *    ARCHIVING→ARCHIVED 由需求详情 PM 手动归档（人工落库决定，不参与派生）
 * 6. 排期不完整拦截：修改/删除导致排期组不再排满时状态自动派生回退（无需回退代码）；
 *    「未排满 + 开发工种已开始」的需求由前端排期界面退出拦截（GET /schedule/incomplete，
 *    仅返回当前用户为项目 PM 的可修复需求）强制完成排期后才允许离开
 */
@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    /** 排期行状态：待开始 */
    private static final String ROW_TODO = "TODO";
    /** 排期行状态：作废（参与人移除） */
    private static final String ROW_VOID = "VOID";

    /** 需求终态：已归档（不可再排期，状态由需求模块人工落库） */
    private static final String REQ_ARCHIVED = "ARCHIVED";
    /** 需求终态：已取消（不可再排期，状态由需求模块人工落库） */
    private static final String REQ_CANCELLED = "CANCELLED";

    /** 日历类型：工作日（可排班） */
    private static final String TYPE_WORKDAY = "WORKDAY";
    /** 日历类型：调休上班（可排班） */
    private static final String TYPE_ADJUSTED = "ADJUSTED";

    /** 测试工种（需求状态 DEV→TEST 推进判定时排除：QA 行可与开发并行） */
    private static final String CRAFT_QA = CraftConstants.QA;

    /** 计划工期上限（对应 schedule_row.planned_effort DECIMAL(5,2)） */
    private static final BigDecimal EFFORT_MAX = new BigDecimal("999.99");
    /** 单格 0.1 步进校验的浮点容忍度（v×10 后与整数比对，等价于原值容忍 1e-9） */
    private static final BigDecimal STEP_TOLERANCE = new BigDecimal("0.00000001");

    /** 留痕/通知使用的业务类型 */
    private static final String BIZ_TYPE = "REQUIREMENT";

    private final ScheduleMonthMapper scheduleMonthMapper;
    private final ScheduleRowMapper scheduleRowMapper;
    private final ScheduleCellMapper scheduleCellMapper;
    private final RequirementMapper requirementMapper;
    private final RequirementParticipantMapper participantMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final BizOperationLogMapper operationLogMapper;
    private final NotificationService notificationService;
    private final CalendarService calendarService;
    private final RequirementStageService stageSyncService;

    // ==================== 月度排期表 ====================

    @Override
    public List<ScheduleMonthVO> listMonths() {
        return scheduleMonthMapper.selectList(new LambdaQueryWrapper<ScheduleMonth>()
                        .orderByAsc(ScheduleMonth::getYear)
                        .orderByAsc(ScheduleMonth::getMonth))
                .stream().map(this::toMonthVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScheduleMonthVO createMonth(Long userId, ScheduleMonthCreateDTO dto) {
        checkMonthCreatePermission(userId);
        Long count = scheduleMonthMapper.selectCount(new LambdaQueryWrapper<ScheduleMonth>()
                .eq(ScheduleMonth::getYear, dto.getYear())
                .eq(ScheduleMonth::getMonth, dto.getMonth()));
        if (count != null && count > 0) {
            throw new BizException(400, "该月排期表已存在");
        }
        ScheduleMonth month = new ScheduleMonth();
        month.setYear(dto.getYear());
        month.setMonth(dto.getMonth());
        month.setCreatedBy(userId);
        scheduleMonthMapper.insert(month);
        return toMonthVO(month);
    }

    @Override
    public ScheduleViewVO getMonthView(Long monthId) {
        ScheduleMonth month = requireMonth(monthId);
        ScheduleViewVO view = new ScheduleViewVO();
        view.setMonth(toMonthVO(month));
        // 整月日历（前端据此灰显周末/节假日列）
        view.setDays(calendarService.getMonth(month.getYear(), month.getMonth()).getDays());

        // 当月全部排期行（按 工种+id 升序；VOID 行也返回，前端灰显）
        List<ScheduleRow> rows = scheduleRowMapper.selectList(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getMonthId, monthId)
                .orderByAsc(ScheduleRow::getCraft)
                .orderByAsc(ScheduleRow::getId));
        if (rows.isEmpty()) {
            view.setGroups(List.of());
            return view;
        }

        // 批量查需求/项目/用户（需求级合并展示列全部 JOIN 派生，不冗余存储）
        List<Long> rowIds = rows.stream().map(ScheduleRow::getId).toList();
        Map<Long, Requirement> reqMap = requirementMapper.selectBatchIds(
                        rows.stream().map(ScheduleRow::getRequirementId).distinct().toList())
                .stream().collect(Collectors.toMap(Requirement::getId, r -> r));
        Map<Long, Project> projectMap = projectMapper.selectBatchIds(
                        reqMap.values().stream().map(Requirement::getProjectId).distinct().toList())
                .stream().collect(Collectors.toMap(Project::getId, p -> p));
        Set<Long> userIds = new LinkedHashSet<>();
        reqMap.values().forEach(r -> userIds.add(r.getPlannerId()));
        rows.forEach(r -> userIds.add(r.getUserId()));
        Map<Long, String> nameMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));

        // 批量查格子，按行分组
        Map<Long, List<ScheduleCell>> cellsByRow = scheduleCellMapper.selectList(
                        new LambdaQueryWrapper<ScheduleCell>().in(ScheduleCell::getRowId, rowIds))
                .stream().collect(Collectors.groupingBy(ScheduleCell::getRowId));

        // 跨月已排合计：查这些需求全部月份的非 VOID 行及其格子，按（需求,工种,负责人）分组求和
        List<Long> reqIds = rows.stream().map(ScheduleRow::getRequirementId).distinct().toList();
        List<ScheduleRow> allMonthRows = scheduleRowMapper.selectList(new LambdaQueryWrapper<ScheduleRow>()
                .in(ScheduleRow::getRequirementId, reqIds)
                .ne(ScheduleRow::getStatus, ROW_VOID));
        Map<Long, String> groupKeyByRowId = new HashMap<>();
        for (ScheduleRow r : allMonthRows) {
            groupKeyByRowId.put(r.getId(), groupKey(r.getRequirementId(), r.getCraft(), r.getUserId()));
        }
        Set<Long> allRowIds = allMonthRows.stream().map(ScheduleRow::getId).collect(Collectors.toSet());
        Map<String, BigDecimal> groupSum = new HashMap<>();
        if (!allRowIds.isEmpty()) {
            for (ScheduleCell cell : scheduleCellMapper.selectList(new LambdaQueryWrapper<ScheduleCell>()
                    .in(ScheduleCell::getRowId, allRowIds))) {
                String key = groupKeyByRowId.get(cell.getRowId());
                if (key != null) {
                    groupSum.merge(key, cell.getValue(), BigDecimal::add);
                }
            }
        }

        // 按需求分组（保持行的 工种+id 顺序）
        Map<Long, List<ScheduleRow>> rowsByReq = rows.stream()
                .collect(Collectors.groupingBy(ScheduleRow::getRequirementId, LinkedHashMap::new, Collectors.toList()));
        List<ScheduleViewVO.GroupVO> groups = new ArrayList<>();
        for (Map.Entry<Long, List<ScheduleRow>> entry : rowsByReq.entrySet()) {
            Requirement req = reqMap.get(entry.getKey());
            if (req == null) {
                continue;
            }
            Project project = projectMap.get(req.getProjectId());
            ScheduleViewVO.GroupVO group = new ScheduleViewVO.GroupVO();
            group.setSeq(entry.getValue().get(0).getSeq());
            group.setRequirementId(req.getId());
            group.setReqNo(req.getReqNo());
            group.setTitle(req.getTitle());
            group.setProjectId(req.getProjectId());
            group.setProjectName(project == null ? null : project.getName());
            group.setPlannerName(nameMap.get(req.getPlannerId()));
            group.setPriority(req.getPriority());
            group.setReqStatus(req.getStatus());
            group.setRows(entry.getValue().stream()
                    .map(row -> toRowVO(row, cellsByRow.getOrDefault(row.getId(), List.of()), nameMap,
                            groupSum.getOrDefault(groupKey(row.getRequirementId(), row.getCraft(), row.getUserId()),
                                    BigDecimal.ZERO)))
                    .toList());
            groups.add(group);
        }
        // groups 按 seq（当月表内展示序号）升序
        groups.sort((a, b) -> {
            int cmp = Integer.compare(a.getSeq(), b.getSeq());
            return cmp != 0 ? cmp : Long.compare(a.getRequirementId(), b.getRequirementId());
        });
        view.setGroups(groups);
        return view;
    }

    // ==================== 排期行生成 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer addRequirement(Long userId, Long monthId, ScheduleRequirementDTO dto) {
        requireMonth(monthId);
        Requirement req = requireRequirement(dto.getRequirementId());
        Project project = requireProject(req.getProjectId());
        // 权限：仅该需求项目的 PM（项目创建者）
        if (!project.getCreatorId().equals(userId)) {
            throw new BizException(403, "无权限");
        }
        // 未归档且未取消的需求均可排期（需求池：需求一直保留，直到归档/取消）
        if (REQ_ARCHIVED.equals(req.getStatus()) || REQ_CANCELLED.equals(req.getStatus())) {
            throw new BizException(400, "已归档或已取消的需求不可排期");
        }
        // 同一需求同月只生成一次
        Long count = scheduleRowMapper.selectCount(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getMonthId, monthId)
                .eq(ScheduleRow::getRequirementId, req.getId()));
        if (count != null && count > 0) {
            throw new BizException(400, "该需求已在当月排期表中");
        }
        // 按需求参与人生成行（requirement_participant 已保证 工种×负责人 唯一）
        List<RequirementParticipant> participants = participantMapper.selectList(
                new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getRequirementId, req.getId())
                        .orderByAsc(RequirementParticipant::getId));
        if (participants.isEmpty()) {
            return 0;
        }
        // seq = 当月该表已有需求数 + 1
        int seq = scheduleRowMapper.countRequirementsInMonth(monthId).intValue() + 1;
        // 工期跨月共用：继承该需求在其他月份已设置的工期（同组取任一非空值；无历史则为空=待排期）
        // 已排统计无需复制——查询时按组跨全部月份动态求和，自动带上其他月份的格子
        Map<String, BigDecimal> effortByGroup = new HashMap<>();
        for (ScheduleRow r : scheduleRowMapper.selectList(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getRequirementId, req.getId())
                .ne(ScheduleRow::getStatus, ROW_VOID)
                .isNotNull(ScheduleRow::getPlannedEffort))) {
            effortByGroup.putIfAbsent(r.getCraft() + ":" + r.getUserId(), r.getPlannedEffort());
        }
        for (RequirementParticipant participant : participants) {
            ScheduleRow row = new ScheduleRow();
            row.setMonthId(monthId);
            row.setRequirementId(req.getId());
            row.setCraft(participant.getCraft());
            row.setUserId(participant.getUserId());
            row.setSeq(seq);
            row.setPlannedEffort(effortByGroup.get(participant.getCraft() + ":" + participant.getUserId()));
            row.setStatus(ROW_TODO);
            row.setIsOverflow(0);
            scheduleRowMapper.insert(row);
        }
        // 需求状态对账（状态派生化）：排期行集合变化后按排期数据派生状态，统一收口
        stageSyncService.syncStage(req.getId(), userId);
        return participants.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer removeRequirement(Long userId, Long monthId, Long requirementId) {
        ScheduleMonth month = requireMonth(monthId);
        Requirement req = requireRequirement(requirementId);
        Project project = requireProject(req.getProjectId());
        // 权限：仅该需求项目的 PM（与加入排期一致）
        if (!project.getCreatorId().equals(userId)) {
            throw new BizException(403, "无权限");
        }
        // 该需求在该月的全部排期行
        List<ScheduleRow> monthRows = scheduleRowMapper.selectList(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getMonthId, monthId)
                .eq(ScheduleRow::getRequirementId, requirementId));
        if (monthRows.isEmpty()) {
            throw new BizException(404, "该需求在此月排期表中不存在");
        }
        // 连带删除这些行的格子
        List<Long> rowIds = monthRows.stream().map(ScheduleRow::getId).toList();
        scheduleCellMapper.delete(new LambdaQueryWrapper<ScheduleCell>().in(ScheduleCell::getRowId, rowIds));
        // 删除当月行（其他月份的行不受影响）
        scheduleRowMapper.delete(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getMonthId, monthId)
                .eq(ScheduleRow::getRequirementId, requirementId));
        // 需求状态对账（状态派生化）：删除后按剩余排期数据派生——全部月份无行回 PENDING；
        // 部分删除导致组不再排满同样自动回退（回退后由前端退出拦截强制完成排期）
        stageSyncService.syncStage(requirementId, userId);
        // 留痕 + 通知参与人
        writeLog(requirementId, userId, "SCHEDULE_REMOVE",
                "从" + month.getYear() + "年" + month.getMonth() + "月排期表移除：" + req.getTitle()
                        + "（" + monthRows.size() + " 行）");
        notifyAllParticipants(req, "排期移除",
                "需求「" + req.getTitle() + "」已被项目经理从" + month.getYear() + "年" + month.getMonth()
                        + "月排期表移除");
        return monthRows.size();
    }

    // ==================== 排期矩阵填写 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScheduleRowSaveResultVO saveRow(Long userId, Long rowId, ScheduleRowSaveDTO dto) {
        ScheduleRow row = requireRow(rowId);
        ScheduleMonth month = requireMonth(row.getMonthId());
        Requirement req = requireRequirement(row.getRequirementId());
        Project project = requireProject(req.getProjectId());
        // 权限：仅项目 PM（排期时间由 PM 统一填写）
        if (!project.getCreatorId().equals(userId)) {
            throw new BizException(403, "无权限");
        }
        // 校验0：终态需求的排期行不可编辑（已归档历史只读；已取消需求排期同样冻结）
        if (REQ_ARCHIVED.equals(req.getStatus())) {
            throw new BizException(400, "该需求已归档，排期不可修改");
        }
        if (REQ_CANCELLED.equals(req.getStatus())) {
            throw new BizException(400, "该需求已取消，排期不可修改");
        }
        // 校验1：仅 VOID 拒绝编辑（非 VOID 行随时可编辑工期与格子）
        if (ROW_VOID.equals(row.getStatus())) {
            throw new BizException(400, "该排期行已作废，不可编辑");
        }
        // 校验2：乐观锁（提交 updatedAt 毫秒与库中不一致则 409）
        Long dbVersion = toMillis(row.getUpdatedAt());
        if (dto.getUpdatedAt() == null || !dto.getUpdatedAt().equals(dbVersion)) {
            throw new BizException(409, "数据已被他人修改，请刷新后重试");
        }
        // 校验3：计划工期（可空；>0、一位小数即 0.1 步进、不超过上限——与格子 0.1 步进口径一致，避免两位小数永远排不满）
        checkPlannedEffort(dto.getPlannedEffort());
        // 校验4：格子日期归属月 + 可排班日 + 数值范围与 0.1 步进
        Map<LocalDate, CalendarDay> monthDays = calendarService.getMonthDays(month.getYear(), month.getMonth());
        Map<LocalDate, BigDecimal> newCells = checkAndNormalizeCells(dto.getCells(), month, monthDays);
        // 校验5：组内跨月已排合计（其他月份 + 本月新格子）不得超过计划工期（超过则拒绝，提示先修改工期）
        if (dto.getPlannedEffort() != null) {
            BigDecimal otherMonthsSum = scheduleRowMapper.selectGroupCellsSum(
                    row.getRequirementId(), row.getCraft(), row.getUserId(), rowId);
            BigDecimal sum = otherMonthsSum.add(newCells.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            if (sum.compareTo(dto.getPlannedEffort()) > 0) {
                throw new BizException(400, "已排工作量" + sum.stripTrailingZeros().toPlainString()
                        + "超过计划工期" + dto.getPlannedEffort().stripTrailingZeros().toPlainString()
                        + "，请先修改计划工期");
            }
        }
        // 校验6：同人同日合计≤1（跨所有需求所有行，含其他月份同日）
        checkUserDayTotal(row.getUserId(), newCells, rowId);

        // 变更检测：格子与库中不一致才发 SCHEDULE_CHANGED 通知
        Map<LocalDate, BigDecimal> oldCells = loadRowCells(rowId);
        boolean cellsChanged = !cellsEquals(oldCells, newCells);

        // 全量替换该行格子（空 map=清空全部格子）
        scheduleCellMapper.delete(new LambdaQueryWrapper<ScheduleCell>().eq(ScheduleCell::getRowId, rowId));
        for (Map.Entry<LocalDate, BigDecimal> entry : newCells.entrySet()) {
            ScheduleCell cell = new ScheduleCell();
            cell.setRowId(rowId);
            cell.setDay(entry.getKey());
            cell.setValue(entry.getValue());
            scheduleCellMapper.insert(cell);
        }

        // 更新行：plannedEffort 全量替换（null=清空）；显式推进 updated_at 作为乐观锁版本（保证严格大于旧值）
        LocalDateTime newVersion = nextVersionTime(row.getUpdatedAt());
        scheduleRowMapper.update(null, new LambdaUpdateWrapper<ScheduleRow>()
                .eq(ScheduleRow::getId, rowId)
                .set(ScheduleRow::getPlannedEffort, dto.getPlannedEffort())
                .set(ScheduleRow::getUpdatedAt, newVersion));
        // 工期跨月共用：同（需求,工种,负责人）其他月份的行同步工期（仅更新工期，不动其乐观锁版本）
        scheduleRowMapper.update(null, new LambdaUpdateWrapper<ScheduleRow>()
                .eq(ScheduleRow::getRequirementId, row.getRequirementId())
                .eq(ScheduleRow::getCraft, row.getCraft())
                .eq(ScheduleRow::getUserId, row.getUserId())
                .ne(ScheduleRow::getId, rowId)
                .set(ScheduleRow::getPlannedEffort, dto.getPlannedEffort()));

        // 跨月自动顺延检查（当月排不下剩余工期时自动建下月表并追加顺延行）
        ScheduleRowSaveResultVO.OverflowVO overflow =
                checkAndCreateOverflow(row, month, req, dto.getPlannedEffort(), newCells, monthDays, userId);

        // 需求状态对账（状态派生化）：格子/工期/顺延行写完后按「排期数据+日期」派生状态，
        // 排满则自动推进（PENDING→DEV）、不再排满则自动回退，统一收口无需链式推进/回退代码
        stageSyncService.syncStage(req.getId(), userId);

        // 通知该行负责人：排期被修改（仅格子实际变化时）
        if (cellsChanged) {
            notificationService.send(row.getUserId(), NotifyTypes.SCHEDULE_CHANGED, "排期已修改",
                    "需求「" + req.getTitle() + "」您的排期已被项目经理修改（"
                            + month.getYear() + "年" + month.getMonth() + "月·" + craftName(row.getCraft()) + "）",
                    BIZ_TYPE, req.getId());
        }

        ScheduleRowSaveResultVO result = new ScheduleRowSaveResultVO();
        result.setUpdatedAt(toMillis(newVersion));
        result.setOverflow(overflow);
        return result;
    }

    // ==================== 复制到下月与状态推进 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CopyRowResultVO copyRequirementToNextMonth(Long userId, Long rowId) {
        ScheduleRow row = requireRow(rowId);
        ScheduleMonth month = requireMonth(row.getMonthId());
        Requirement req = requireRequirement(row.getRequirementId());
        Project project = requireProject(req.getProjectId());
        // 权限：仅项目 PM（与排期编辑一致）
        if (!project.getCreatorId().equals(userId)) {
            throw new BizException(403, "无权限");
        }
        // 终态校验：已归档/已取消的需求不可添加到下月
        if (REQ_ARCHIVED.equals(req.getStatus()) || REQ_CANCELLED.equals(req.getStatus())) {
            throw new BizException(400, "已归档或已取消的需求不可添加到下月");
        }
        // 该需求全部排期组（craft × user，跨全部月份去重，取各组最新一行带出工期）
        List<ScheduleRow> allRows = scheduleRowMapper.selectList(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getRequirementId, req.getId())
                .ne(ScheduleRow::getStatus, ROW_VOID)
                .orderByDesc(ScheduleRow::getId));
        Map<String, ScheduleRow> groups = new LinkedHashMap<>();
        for (ScheduleRow r : allRows) {
            groups.putIfAbsent(r.getCraft() + ":" + r.getUserId(), r);
        }
        if (groups.isEmpty()) {
            throw new BizException(400, "该需求没有可添加的排期行");
        }
        // 下月表不存在则自动创建（跨年自动进位）
        ScheduleMonth nextMonth = resolveOrCreateNextMonth(month, userId);
        // 整个需求逐组复制到下月：幂等（下月已有同组行跳过），格子空白、工期带原值（跨月共用）
        int seq = resolveNextMonthSeq(nextMonth, req.getId());
        int inserted = 0;
        for (ScheduleRow source : groups.values()) {
            Long existCount = scheduleRowMapper.selectCount(new LambdaQueryWrapper<ScheduleRow>()
                    .eq(ScheduleRow::getMonthId, nextMonth.getId())
                    .eq(ScheduleRow::getRequirementId, req.getId())
                    .eq(ScheduleRow::getCraft, source.getCraft())
                    .eq(ScheduleRow::getUserId, source.getUserId()));
            if (existCount != null && existCount > 0) {
                continue;
            }
            ScheduleRow copy = new ScheduleRow();
            copy.setMonthId(nextMonth.getId());
            copy.setRequirementId(req.getId());
            copy.setCraft(source.getCraft());
            copy.setUserId(source.getUserId());
            copy.setSeq(seq);
            copy.setPlannedEffort(source.getPlannedEffort());
            copy.setStatus(ROW_TODO);
            copy.setIsOverflow(0);
            try {
                scheduleRowMapper.insert(copy);
                inserted++;
            } catch (DuplicateKeyException e) {
                // uk_month_req_craft_user 并发兜底：已存在同键行视为未新插入
            }
        }
        // 操作留痕
        if (inserted > 0) {
            writeLog(req.getId(), userId, "SCHEDULE_COPY",
                    "整个需求添加到" + nextMonth.getYear() + "年" + nextMonth.getMonth() + "月："
                            + req.getTitle() + "（" + inserted + " 行，格子空白、工期跨月共用）");
        }
        // 需求状态对账（状态派生化）：写路径统一收口（复制行不影响跨月组统计，通常无变化）
        stageSyncService.syncStage(req.getId(), userId);
        return toCopyResult(nextMonth.getId(), inserted > 0, inserted);
    }

    @Override
    public List<ScheduleIncompleteVO> listIncompleteRequirements(Long userId) {
        // 拦截口径与进度图节点判定一致（不依赖需求状态字段，防 PENDING 漏网）：
        // 非终态（未归档未取消）且 排期组未排满 且 存在已开始的开发工种（FE/BE/OPS/UI 有格子日≤今天，
        // 即开发并行节点黄/绿）——此类需求不可回退（开发已开始），须强制完成排期后才能离开排期界面。
        // 仅返回当前用户为项目 PM（项目创建者）的需求：普通成员无权修复，不被 PM 未完成的排期困住
        List<Long> projectIds = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .eq(Project::getCreatorId, userId)).stream().map(Project::getId).toList();
        if (projectIds.isEmpty()) {
            return List.of();
        }
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .in(Requirement::getProjectId, projectIds)
                .notIn(Requirement::getStatus, List.of(REQ_ARCHIVED, REQ_CANCELLED))
                .orderByAsc(Requirement::getId));
        if (reqs.isEmpty()) {
            return List.of();
        }
        List<Long> reqIds = reqs.stream().map(Requirement::getId).toList();
        // 批量聚合判定排满（任一组无工期/工期≤0/跨月合计<工期 → 未排满），避免逐需求 N+1
        Set<Long> notFullReqIds = scheduleRowMapper.selectGroupAggBatch(reqIds).stream()
                .filter(g -> g.getPlannedEffort() == null || g.getPlannedEffort().signum() <= 0
                        || g.getTotal().compareTo(g.getPlannedEffort()) < 0)
                .map(RequirementGroupAggVO::getRequirementId)
                .collect(Collectors.toSet());
        if (notFullReqIds.isEmpty()) {
            return List.of();
        }
        // 批量查询开发已开工的需求 id 集合（开发未开始可派生回退，不拦截）
        List<Long> candidateIds = reqIds.stream().filter(notFullReqIds::contains).toList();
        Set<Long> startedDevReqIds = new HashSet<>(scheduleRowMapper.selectStartedDevReqIds(candidateIds, LocalDate.now()));
        List<ScheduleIncompleteVO> result = new ArrayList<>();
        for (Requirement req : reqs) {
            if (!notFullReqIds.contains(req.getId()) || !startedDevReqIds.contains(req.getId())) {
                continue;
            }
            ScheduleIncompleteVO vo = new ScheduleIncompleteVO();
            vo.setRequirementId(req.getId());
            vo.setReqNo(req.getReqNo());
            vo.setTitle(req.getTitle());
            result.add(vo);
        }
        return result;
    }

    // ==================== 私有校验 ====================

    /**
     * 创建月表权限：管理员 或 任意项目的项目经理（查 project 表 creator_id=当前用户，即项目创建者）
     */
    private void checkMonthCreatePermission(Long userId) {
        User user = userMapper.selectById(userId);
        if (user != null && user.getIsAdmin() != null && user.getIsAdmin() == 1) {
            return;
        }
        Long createdProjects = projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                .eq(Project::getCreatorId, userId));
        if (createdProjects == null || createdProjects == 0) {
            throw new BizException(403, "无权限");
        }
    }

    /**
     * 计划工期校验：可空；>0、0.1 步进（一位小数，与格子步进口径一致，避免两位小数永远排不满）、
     * 不超过 DECIMAL(5,2) 上限 999.99
     */
    private void checkPlannedEffort(BigDecimal plannedEffort) {
        if (plannedEffort == null) {
            return;
        }
        if (plannedEffort.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "计划工期必须大于0");
        }
        if (plannedEffort.scale() > 1) {
            throw new BizException(400, "计划工期必须为0.1的整数倍");
        }
        if (plannedEffort.compareTo(EFFORT_MAX) > 0) {
            throw new BizException(400, "计划工期不能超过999.99");
        }
    }

    /**
     * 格子校验与规范化：
     * 1. 日期可解析（2026-09-04 格式）且属于该行所在月
     * 2. 日期为可排班日（WORKDAY/ADJUSTED，其余 400「X日为非工作日」）
     * 3. 单格值 0<v≤1 且为 0.1 的整数倍（BigDecimal 判断，浮点误差容忍 1e-9），规范化为一位小数落库
     */
    private Map<LocalDate, BigDecimal> checkAndNormalizeCells(Map<String, BigDecimal> cells, ScheduleMonth month,
                                                              Map<LocalDate, CalendarDay> monthDays) {
        Map<LocalDate, BigDecimal> result = new LinkedHashMap<>();
        if (cells == null || cells.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, BigDecimal> entry : cells.entrySet()) {
            LocalDate day;
            try {
                day = LocalDate.parse(entry.getKey());
            } catch (DateTimeParseException e) {
                throw new BizException(400, "日期格式错误：" + entry.getKey() + "（应为2026-09-04）");
            }
            if (day.getYear() != month.getYear() || day.getMonthValue() != month.getMonth()) {
                throw new BizException(400, entry.getKey() + "不属于该行所在月份" + month.getYear() + "-" + month.getMonth());
            }
            String type = monthDays.get(day).getType();
            if (!TYPE_WORKDAY.equals(type) && !TYPE_ADJUSTED.equals(type)) {
                throw new BizException(400, entry.getKey() + "为非工作日");
            }
            BigDecimal value = entry.getValue();
            if (value == null) {
                throw new BizException(400, entry.getKey() + "工作量不能为空");
            }
            if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
                throw new BizException(400, entry.getKey() + "工作量必须大于0且不超过1");
            }
            // 0.1 步进：v×10 与最近整数的差 ≤ 1e-8（等价于原值容忍 1e-9）
            BigDecimal x10 = value.multiply(BigDecimal.TEN);
            BigDecimal rounded = x10.setScale(0, RoundingMode.HALF_UP);
            if (x10.subtract(rounded).abs().compareTo(STEP_TOLERANCE) > 0) {
                throw new BizException(400, entry.getKey() + "工作量必须为0.1的整数倍");
            }
            // 规范化：按步进值落库，避免 0.30000000000000004 之类的浮点尾数
            result.put(day, rounded.divide(BigDecimal.TEN, 1, RoundingMode.HALF_UP));
        }
        return result;
    }

    /**
     * 同人同日合计≤1 校验：该负责人在该日期、除本行外全部非 VOID 行（含其他需求、其他月份同日的行，
     * day 为绝对日期天然跨月对齐）的格子合计，加上本行新值 ≤1，否则 400「用户X在X日工作量已超过1」
     */
    private void checkUserDayTotal(Long rowUserId, Map<LocalDate, BigDecimal> newCells, Long rowId) {
        for (Map.Entry<LocalDate, BigDecimal> entry : newCells.entrySet()) {
            BigDecimal other = scheduleRowMapper.selectUserDayTotal(rowUserId, entry.getKey(), rowId);
            BigDecimal total = entry.getValue().add(other == null ? BigDecimal.ZERO : other);
            if (total.compareTo(BigDecimal.ONE) > 0) {
                throw new BizException(400, "用户" + userName(rowUserId) + "在" + entry.getKey() + "工作量已超过1");
            }
        }
    }

    /**
     * 跨月自动顺延检查（保存事务内）：
     * 条件（同时满足）：plannedEffort 非空 且 组内跨月已排合计（其他月份 + 本月新格子）< plannedEffort
     * 且 该行当月剩余可填工作日数为 0（当月可排班日中未被本行填值的格子数=0，即当月已排不下剩余工期）。
     * 动作：下月排期表不存在则自动创建（created_by=当前操作人）；插入顺延行（同需求/工种/负责人，
     * is_overflow=1，planned_effort=原工期（工期跨月共用），格子空白）；通知该行负责人 SCHEDULE_OVERFLOW。
     * 幂等：下月已存在同（需求,工种,负责人）行则不重复插入。
     */
    private ScheduleRowSaveResultVO.OverflowVO checkAndCreateOverflow(ScheduleRow row, ScheduleMonth month, Requirement req,
                                                                      BigDecimal plannedEffort, Map<LocalDate, BigDecimal> newCells,
                                                                      Map<LocalDate, CalendarDay> monthDays, Long operatorId) {
        if (plannedEffort == null) {
            return null;
        }
        // 组内跨月已排合计 = 其他月份已排 + 本月新格子
        BigDecimal otherMonthsSum = scheduleRowMapper.selectGroupCellsSum(
                row.getRequirementId(), row.getCraft(), row.getUserId(), row.getId());
        BigDecimal groupTotal = otherMonthsSum.add(newCells.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        if (groupTotal.compareTo(plannedEffort) >= 0) {
            // 跨月已排合计不小于计划工期，无需顺延
            return null;
        }
        // 当月剩余可填工作日数（可排班日 - 本行已填格子）> 0 说明当月还排得下
        long unfilled = monthDays.values().stream()
                .filter(d -> TYPE_WORKDAY.equals(d.getType()) || TYPE_ADJUSTED.equals(d.getType()))
                .map(CalendarDay::getDay)
                .filter(day -> !newCells.containsKey(day))
                .count();
        if (unfilled > 0) {
            return null;
        }
        // 剩余工期 = 计划工期 - 组内跨月已排合计
        BigDecimal carriedEffort = plannedEffort.subtract(groupTotal).setScale(2, RoundingMode.HALF_UP);
        // 下月（跨年自动进位），下月排期表不存在则自动创建
        YearMonth next = YearMonth.of(month.getYear(), month.getMonth()).plusMonths(1);
        ScheduleMonth nextMonth = resolveOrCreateNextMonth(month, operatorId);
        // 幂等：下月已存在同（需求,工种,负责人）行则不重复插入
        Long existCount = scheduleRowMapper.selectCount(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getMonthId, nextMonth.getId())
                .eq(ScheduleRow::getRequirementId, req.getId())
                .eq(ScheduleRow::getCraft, row.getCraft())
                .eq(ScheduleRow::getUserId, row.getUserId()));
        if (existCount != null && existCount > 0) {
            return null;
        }
        // 顺延行展示序号：该需求在下月已有行则沿用其 seq，否则 = 下月已有需求数+1
        ScheduleRow sameReqRow = scheduleRowMapper.selectOne(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getMonthId, nextMonth.getId())
                .eq(ScheduleRow::getRequirementId, req.getId())
                .last("LIMIT 1"));
        int seq = sameReqRow != null ? sameReqRow.getSeq()
                : scheduleRowMapper.countRequirementsInMonth(nextMonth.getId()).intValue() + 1;
        ScheduleRow overflowRow = new ScheduleRow();
        overflowRow.setMonthId(nextMonth.getId());
        overflowRow.setRequirementId(req.getId());
        overflowRow.setCraft(row.getCraft());
        overflowRow.setUserId(row.getUserId());
        overflowRow.setSeq(seq);
        // 工期跨月共用：顺延行带原工期（非剩余），排满判定按组内跨月格子之和
        overflowRow.setPlannedEffort(plannedEffort);
        overflowRow.setStatus(ROW_TODO);
        overflowRow.setIsOverflow(1);
        scheduleRowMapper.insert(overflowRow);

        // 通知该行负责人（内容含需求名、剩余工期、顺延到的月份）
        notificationService.send(row.getUserId(), NotifyTypes.SCHEDULE_OVERFLOW, "排期跨月顺延",
                "需求「" + req.getTitle() + "」" + month.getMonth() + "月排期已满，剩余工期" + carriedEffort
                        + "已自动顺延至" + next.getYear() + "年" + next.getMonthValue() + "月",
                BIZ_TYPE, req.getId());

        ScheduleRowSaveResultVO.OverflowVO overflow = new ScheduleRowSaveResultVO.OverflowVO();
        overflow.setYear(next.getYear());
        overflow.setMonth(next.getMonthValue());
        overflow.setCarriedEffort(carriedEffort);
        return overflow;
    }

    /**
     * 取下月排期表，不存在则自动创建（created_by=操作人，跨年自动进位）
     */
    private ScheduleMonth resolveOrCreateNextMonth(ScheduleMonth month, Long operatorId) {
        YearMonth next = YearMonth.of(month.getYear(), month.getMonth()).plusMonths(1);
        ScheduleMonth nextMonth = scheduleMonthMapper.selectOne(new LambdaQueryWrapper<ScheduleMonth>()
                .eq(ScheduleMonth::getYear, next.getYear())
                .eq(ScheduleMonth::getMonth, next.getMonthValue()));
        if (nextMonth == null) {
            nextMonth = new ScheduleMonth();
            nextMonth.setYear(next.getYear());
            nextMonth.setMonth(next.getMonthValue());
            nextMonth.setCreatedBy(operatorId);
            scheduleMonthMapper.insert(nextMonth);
        }
        return nextMonth;
    }

    /**
     * 需求在下月行的展示序号：该需求在下月已有行则沿用其 seq，否则 = 下月已有需求数+1
     */
    private int resolveNextMonthSeq(ScheduleMonth nextMonth, Long requirementId) {
        ScheduleRow sameReqRow = scheduleRowMapper.selectOne(new LambdaQueryWrapper<ScheduleRow>()
                .eq(ScheduleRow::getMonthId, nextMonth.getId())
                .eq(ScheduleRow::getRequirementId, requirementId)
                .last("LIMIT 1"));
        return sameReqRow != null ? sameReqRow.getSeq()
                : scheduleRowMapper.countRequirementsInMonth(nextMonth.getId()).intValue() + 1;
    }

    /**
     * 复制行结果 VO 构造
     */
    private CopyRowResultVO toCopyResult(Long monthId, boolean duplicated, int insertedCount) {
        CopyRowResultVO vo = new CopyRowResultVO();
        vo.setMonthId(monthId);
        vo.setDuplicated(duplicated);
        vo.setInsertedCount(insertedCount);
        return vo;
    }

    /**
     * 通知需求全部参与人（按用户去重）
     */
    private void notifyAllParticipants(Requirement req, String title, String content) {
        List<RequirementParticipant> participants = participantMapper.selectList(
                new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getRequirementId, req.getId()));
        participants.stream().map(RequirementParticipant::getUserId).distinct().forEach(receiverId ->
                notificationService.send(receiverId, NotifyTypes.REQ_STATUS_CHANGED, title, content,
                        BIZ_TYPE, req.getId()));
    }

    // ==================== 私有工具方法 ====================

    /**
     * 查询该行当前全部格子（day -> value）
     */
    private Map<LocalDate, BigDecimal> loadRowCells(Long rowId) {
        return scheduleCellMapper.selectList(new LambdaQueryWrapper<ScheduleCell>()
                        .eq(ScheduleCell::getRowId, rowId))
                .stream().collect(Collectors.toMap(ScheduleCell::getDay, ScheduleCell::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 格子集合比对（用 compareTo 比较，忽略 BigDecimal 精度差异如 1.0 与 1.00）
     */
    private boolean cellsEquals(Map<LocalDate, BigDecimal> a, Map<LocalDate, BigDecimal> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<LocalDate, BigDecimal> entry : a.entrySet()) {
            BigDecimal other = b.get(entry.getKey());
            if (other == null || entry.getValue().compareTo(other) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按 id 查询排期行，不存在抛 404
     */
    private ScheduleRow requireRow(Long rowId) {
        ScheduleRow row = scheduleRowMapper.selectById(rowId);
        if (row == null) {
            throw new BizException(404, "排期行不存在");
        }
        return row;
    }

    /**
     * 按 id 查询月表，不存在抛 404
     */
    private ScheduleMonth requireMonth(Long monthId) {
        ScheduleMonth month = scheduleMonthMapper.selectById(monthId);
        if (month == null) {
            throw new BizException(404, "排期表不存在");
        }
        return month;
    }

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
     * 写操作日志（biz_type=REQUIREMENT，需求恒 round_no=1）
     */
    private void writeLog(Long requirementId, Long operatorId, String action, String remark) {
        BizOperationLog log = new BizOperationLog();
        log.setBizType(BIZ_TYPE);
        log.setBizId(requirementId);
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setRoundNo(1);
        log.setRemark(remark);
        operationLogMapper.insert(log);
    }

    /**
     * 查询用户姓名，用户不存在时回退为 userId 字符串
     */
    private String userName(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? String.valueOf(userId) : user.getRealName();
    }

    /**
     * 工种代码 -> 中文名（通知内容用）
     */
    private String craftName(String craft) {
        return CraftConstants.CRAFT_NAMES.getOrDefault(craft, craft);
    }

    /**
     * 月表实体 -> VO
     */
    private ScheduleMonthVO toMonthVO(ScheduleMonth month) {
        ScheduleMonthVO vo = new ScheduleMonthVO();
        vo.setId(month.getId());
        vo.setYear(month.getYear());
        vo.setMonth(month.getMonth());
        return vo;
    }

    /**
     * 排期行实体 -> 视图 VO（scheduledSum = 组内跨全部月份已排合计，前端"已排"列与状态派生用）
     */
    private ScheduleViewVO.RowVO toRowVO(ScheduleRow row, List<ScheduleCell> cells, Map<Long, String> nameMap,
                                         BigDecimal scheduledSum) {
        ScheduleViewVO.RowVO vo = new ScheduleViewVO.RowVO();
        vo.setRowId(row.getId());
        vo.setCraft(row.getCraft());
        vo.setUserId(row.getUserId());
        vo.setRealName(nameMap.get(row.getUserId()));
        vo.setStatus(row.getStatus());
        vo.setIsOverflow(row.getIsOverflow());
        vo.setPlannedEffort(row.getPlannedEffort());
        vo.setScheduledSum(scheduledSum);
        vo.setUpdatedAt(toMillis(row.getUpdatedAt()));
        Map<String, BigDecimal> cellMap = new LinkedHashMap<>();
        cells.forEach(cell -> cellMap.put(cell.getDay().toString(), cell.getValue()));
        vo.setCells(cellMap);
        return vo;
    }

    /**
     * 排期组键：同需求×工种×负责人（跨全部月份共用工期与已排统计）
     */
    private String groupKey(Long requirementId, String craft, Long userId) {
        return requirementId + ":" + craft + ":" + userId;
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
