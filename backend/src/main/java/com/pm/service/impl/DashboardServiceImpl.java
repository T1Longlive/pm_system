package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pm.entity.Bug;
import com.pm.entity.Project;
import com.pm.entity.Requirement;
import com.pm.entity.RequirementParticipant;
import com.pm.mapper.BugMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleCellMapper;
import com.pm.service.DashboardService;
import com.pm.service.NotificationService;
import com.pm.vo.DashboardRequirementVO;
import com.pm.vo.DashboardSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工作台服务实现（个人视角只读汇总，不做任何写操作）
 * 口径：
 * - 未读通知：notification.receiver=我 且 is_read=0（复用铃铛角标口径）
 * - 我参与的需求：requirement_participant 有我的行 且 需求非终态（未归档未取消）
 * - 本月工时：schedule_cell 按排期行 user=我 聚合（排除 VOID 行），自然月口径
 * - 待处理 bug：我是负责人、未归档、处理状态非 DONE/SKIP（含未处理与挂起）
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final String REQ_ARCHIVED = "ARCHIVED";
    private static final String REQ_CANCELLED = "CANCELLED";
    private static final String BUG_HANDLE_DONE = "DONE";
    private static final String BUG_HANDLE_SKIP = "SKIP";

    private final RequirementMapper requirementMapper;
    private final RequirementParticipantMapper participantMapper;
    private final ProjectMapper projectMapper;
    private final BugMapper bugMapper;
    private final ScheduleCellMapper scheduleCellMapper;
    private final NotificationService notificationService;

    @Override
    public DashboardSummaryVO summary(Long userId) {
        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setUnreadNotifications(notificationService.unreadCount(userId));
        vo.setMyRequirementCount(countMyRequirements(userId));
        LocalDate today = LocalDate.now();
        vo.setMonthEffort(scheduleCellMapper.sumUserMonthEffort(userId, today.getYear(), today.getMonthValue()));
        vo.setOpenBugCount(bugMapper.selectCount(new LambdaQueryWrapper<Bug>()
                .eq(Bug::getOwnerId, userId)
                .eq(Bug::getArchived, 0)
                .and(w -> w.isNull(Bug::getHandleStatus)
                        .or().notIn(Bug::getHandleStatus, List.of(BUG_HANDLE_DONE, BUG_HANDLE_SKIP)))));
        return vo;
    }

    @Override
    public List<DashboardRequirementVO> myRequirements(Long userId) {
        // 我参与的（任意工种）非终态需求，更新时间倒序取 20 条
        List<Long> reqIds = participantMapper.selectList(new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getUserId, userId))
                .stream().map(RequirementParticipant::getRequirementId).distinct().toList();
        if (reqIds.isEmpty()) {
            return List.of();
        }
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .in(Requirement::getId, reqIds)
                .notIn(Requirement::getStatus, List.of(REQ_ARCHIVED, REQ_CANCELLED))
                .orderByDesc(Requirement::getUpdatedAt)
                .last("LIMIT 20"));
        if (reqs.isEmpty()) {
            return List.of();
        }
        // 批量补项目名
        Map<Long, Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .in(Project::getId, reqs.stream().map(Requirement::getProjectId).distinct().toList()))
                .stream().collect(Collectors.toMap(Project::getId, Function.identity()));
        return reqs.stream().map(req -> {
            DashboardRequirementVO item = new DashboardRequirementVO();
            item.setId(req.getId());
            item.setReqNo(req.getReqNo());
            item.setTitle(req.getTitle());
            item.setProjectId(req.getProjectId());
            item.setProjectName(projects.containsKey(req.getProjectId())
                    ? projects.get(req.getProjectId()).getName() : "");
            item.setStatus(req.getStatus());
            item.setPriority(req.getPriority());
            return item;
        }).toList();
    }

    /**
     * 我参与的非终态需求总数（汇总卡片用；无参与记录直接 0，避免空 IN 查询）
     */
    private Long countMyRequirements(Long userId) {
        List<Long> reqIds = participantMapper.selectList(new LambdaQueryWrapper<RequirementParticipant>()
                        .eq(RequirementParticipant::getUserId, userId))
                .stream().map(RequirementParticipant::getRequirementId).distinct().toList();
        if (reqIds.isEmpty()) {
            return 0L;
        }
        return requirementMapper.selectCount(new LambdaQueryWrapper<Requirement>()
                .in(Requirement::getId, reqIds)
                .notIn(Requirement::getStatus, List.of(REQ_ARCHIVED, REQ_CANCELLED)));
    }
}
