package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pm.common.NotifyTypes;
import com.pm.entity.BizOperationLog;
import com.pm.entity.Requirement;
import com.pm.entity.RequirementParticipant;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.service.NotificationService;
import com.pm.service.RequirementStageService;
import com.pm.vo.RequirementGroupAggVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 需求阶段派生服务实现
 * 核心规则（stage 纯函数，唯一 status 写入口，归档/取消除外）：
 * 1. 人工终态：CANCELLED/ARCHIVED 直接返回，永不派生改写
 * 2. 待排期 PENDING：无非 VOID 排期行，或任一排期组未排满（无工期/工期≤0/组内跨月格子合计<工期）
 * 3. 开发中 DEV：排满且开发窗口未结束——存在非 QA 行，且每个非 QA 行都有格子，
 *    且最大非 QA 格子日 ≥ 今天（口径同原 selectDevReqIdsAllPassed：空行阻塞推进）
 * 4. 测试中 TEST：开发窗口已结束但测试窗口未结束——存在行（含 QA），且每行都有格子，
 *    且最大格子日 ≥ 今天（口径同原 selectTestReqIdsAllPassed）
 * 5. 待归档 ARCHIVING：全部行（含 QA）的格子日都已是过去
 * 对账收口：写路径调 syncStage 实时对账，定时任务调 syncAllStages 兜底纯时间流逝；
 * 状态变更通知全部参与人（去重），用户触发时写 STAGE_SYNC 操作日志，定时触发不写（无操作人）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementStageServiceImpl implements RequirementStageService {

    private static final String REQ_PENDING = "PENDING";
    private static final String REQ_DEV = "DEV";
    private static final String REQ_TEST = "TEST";
    private static final String REQ_ARCHIVING = "ARCHIVING";
    private static final String REQ_ARCHIVED = "ARCHIVED";
    private static final String REQ_CANCELLED = "CANCELLED";
    private static final String CRAFT_QA = "QA";
    private static final String BIZ_TYPE = "REQUIREMENT";

    private final RequirementMapper requirementMapper;
    private final ScheduleRowMapper scheduleRowMapper;
    private final RequirementParticipantMapper participantMapper;
    private final BizOperationLogMapper operationLogMapper;
    private final NotificationService notificationService;

    @Override
    public String deriveStage(Requirement req, List<RequirementGroupAggVO> groups, LocalDate today) {
        // 人工终态：归档/取消是仅有的两个人工落库决定，直接沿用不派生
        if (REQ_CANCELLED.equals(req.getStatus())) {
            return REQ_CANCELLED;
        }
        if (REQ_ARCHIVED.equals(req.getStatus())) {
            return REQ_ARCHIVED;
        }
        // 无非 VOID 排期行 → 待排期
        if (groups.isEmpty()) {
            return REQ_PENDING;
        }
        // 任一排期组未排满（组无工期/工期≤0/跨月格子合计<工期）→ 待排期（排期节点黄）
        boolean anyNotFull = groups.stream().anyMatch(g -> g.getPlannedEffort() == null
                || g.getPlannedEffort().signum() <= 0
                || g.getTotal().compareTo(g.getPlannedEffort()) < 0);
        if (anyNotFull) {
            return REQ_PENDING;
        }
        // 开发窗口判定（存在非 QA 行时）：每个非 QA 行都有格子 且 最大非 QA 格子日 < 今天，否则开发中
        List<RequirementGroupAggVO> devGroups = groups.stream()
                .filter(g -> !CRAFT_QA.equals(g.getCraft())).toList();
        if (!devGroups.isEmpty() && !allRowsPassed(devGroups, today)) {
            return REQ_DEV;
        }
        // 测试窗口判定（含 QA 行）：每行都有格子 且 最大格子日 < 今天，否则测试中
        return allRowsPassed(groups, today) ? REQ_ARCHIVING : REQ_TEST;
    }

    @Override
    public boolean syncStage(Long requirementId, Long operatorId) {
        Requirement req = requirementMapper.selectById(requirementId);
        if (req == null) {
            return false;
        }
        // 终态不参与对账（人工决定不可被派生改写）
        if (REQ_ARCHIVED.equals(req.getStatus()) || REQ_CANCELLED.equals(req.getStatus())) {
            return false;
        }
        String after = deriveStage(req, loadGroups(List.of(requirementId)), LocalDate.now());
        return applyIfChanged(req, after, operatorId);
    }

    @Override
    public int syncAllStages() {
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .notIn(Requirement::getStatus, List.of(REQ_ARCHIVED, REQ_CANCELLED))
                .orderByAsc(Requirement::getId));
        if (reqs.isEmpty()) {
            return 0;
        }
        // 一次聚合查询带回全部需求的排期组数据，避免 N+1
        Map<Long, List<RequirementGroupAggVO>> groupsByReq = loadGroups(
                        reqs.stream().map(Requirement::getId).toList()).stream()
                .collect(Collectors.groupingBy(RequirementGroupAggVO::getRequirementId));
        LocalDate today = LocalDate.now();
        int changed = 0;
        for (Requirement req : reqs) {
            String after = deriveStage(req, groupsByReq.getOrDefault(req.getId(), List.of()), today);
            if (applyIfChanged(req, after, null)) {
                changed++;
            }
        }
        return changed;
    }

    // ==================== 私有方法 ====================

    /**
     * 窗口判定：全部组内每行都有格子（rowsWithCells = rowCount，空行阻塞推进）且组内最大格子日 < 今天
     */
    private boolean allRowsPassed(List<RequirementGroupAggVO> groups, LocalDate today) {
        return groups.stream().allMatch(g -> g.getRowsWithCells() >= g.getRowCount()
                && g.getMaxDay() != null && g.getMaxDay().isBefore(today));
    }

    /**
     * 批量聚合查询该批需求的全部排期组（跨全部月份非 VOID 行）
     */
    private List<RequirementGroupAggVO> loadGroups(List<Long> reqIds) {
        return scheduleRowMapper.selectGroupAggBatch(reqIds);
    }

    /**
     * 派生值与库中不一致时条件更新（eq 旧状态防并发覆盖，如期间被取消/归档/他人已变更）；
     * 更新成功则通知参与人，用户触发（operatorId 非空）时写 STAGE_SYNC 日志
     */
    private boolean applyIfChanged(Requirement req, String after, Long operatorId) {
        if (after.equals(req.getStatus())) {
            return false;
        }
        int updated = requirementMapper.update(null, new LambdaUpdateWrapper<Requirement>()
                .eq(Requirement::getId, req.getId())
                .eq(Requirement::getStatus, req.getStatus())
                .set(Requirement::getStatus, after));
        if (updated <= 0) {
            return false;
        }
        if (operatorId != null) {
            writeLog(req, operatorId, req.getStatus(), after);
        }
        notifyParticipants(req, after);
        return true;
    }

    /**
     * 状态变更通知全部参与人（按用户去重），文案按目标阶段区分（回退与推进共用同一套描述）
     */
    private void notifyParticipants(Requirement req, String target) {
        String content = switch (target) {
            case REQ_PENDING -> "需求「" + req.getTitle() + "」排期未完成，已回退为待排期，请重新完成排期";
            case REQ_DEV -> "需求「" + req.getTitle() + "」全部排期组已完成排期，进入开发中";
            case REQ_TEST -> "需求「" + req.getTitle() + "」全部开发任务排期已结束，进入测试中";
            case REQ_ARCHIVING -> "需求「" + req.getTitle() + "」全部排期已结束，进入待归档";
            default -> "需求「" + req.getTitle() + "」状态变更为「" + target + "」";
        };
        participantMapper.selectList(new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getRequirementId, req.getId()))
                .stream().map(RequirementParticipant::getUserId).distinct()
                .forEach(receiverId -> notificationService.send(receiverId, NotifyTypes.REQ_STATUS_CHANGED,
                        "需求状态变更", content, BIZ_TYPE, req.getId()));
    }

    /**
     * 对账留痕（仅用户触发时调用；operator_id 非空约束，定时对账不写日志）
     */
    private void writeLog(Requirement req, Long operatorId, String from, String to) {
        BizOperationLog logRow = new BizOperationLog();
        logRow.setBizType(BIZ_TYPE);
        logRow.setBizId(req.getId());
        logRow.setProjectId(req.getProjectId());
        logRow.setOperatorId(operatorId);
        logRow.setAction("STAGE_SYNC");
        logRow.setRoundNo(1);
        logRow.setRemark("状态对账：" + from + " → " + to + "（按排期数据与日期派生）：" + req.getTitle());
        operationLogMapper.insert(logRow);
    }
}
