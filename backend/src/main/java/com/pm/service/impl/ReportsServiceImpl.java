package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pm.entity.Bug;
import com.pm.entity.Project;
import com.pm.entity.ProjectMember;
import com.pm.entity.Requirement;
import com.pm.mapper.BugMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.ScheduleCellMapper;
import com.pm.service.ReportsService;
import com.pm.vo.ReportProjectOverviewVO;
import com.pm.vo.ReportRequirementStatusVO;
import com.pm.vo.ReportWorkloadVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统计报表服务实现（只读聚合，全员可见）
 * 口径：
 * - 状态分布：全部需求（含 ARCHIVED/CANCELLED），项目×状态一次聚合
 * - 人员负载：排期格子按 人员×自然月 求和（排除 VOID 行），月份区间由 months 参数决定
 * - 项目概览：成员数按人去重（一人多工种算一人）、需求只计非终态、bug 只计未归档
 */
@Service
@RequiredArgsConstructor
public class ReportsServiceImpl implements ReportsService {

    private static final String REQ_ARCHIVED = "ARCHIVED";
    private static final String REQ_CANCELLED = "CANCELLED";

    private final RequirementMapper requirementMapper;
    private final ScheduleCellMapper scheduleCellMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final BugMapper bugMapper;

    @Override
    public List<ReportRequirementStatusVO> requirementStatus() {
        return requirementMapper.countGroupByProjectStatus();
    }

    @Override
    public List<ReportWorkloadVO> workload(int months) {
        // 起始月 = 今天往前推 months-1 个月（含当月共 months 个自然月）
        LocalDate start = LocalDate.now().minusMonths(months - 1L);
        return scheduleCellMapper.sumEffortGroupByUserMonth(start.getYear(), start.getMonthValue());
    }

    @Override
    public List<ReportProjectOverviewVO> projectOverview() {
        List<Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .orderByAsc(Project::getId));
        if (projects.isEmpty()) {
            return List.of();
        }
        // 成员数按人去重（一人多工种算一人）
        Map<Long, Long> memberCounts = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>())
                .stream()
                .collect(Collectors.groupingBy(ProjectMember::getProjectId,
                        Collectors.mapping(ProjectMember::getUserId, Collectors.toSet())))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (long) e.getValue().size()));
        // 非终态需求数
        Map<Long, Long> reqCounts = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                        .select(Requirement::getId, Requirement::getProjectId)
                        .notIn(Requirement::getStatus, List.of(REQ_ARCHIVED, REQ_CANCELLED))).stream()
                .collect(Collectors.groupingBy(Requirement::getProjectId, Collectors.counting()));
        // 未归档 bug 数
        Map<Long, Long> bugCounts = bugMapper.selectList(new LambdaQueryWrapper<Bug>()
                        .select(Bug::getId, Bug::getProjectId)
                        .eq(Bug::getArchived, 0)).stream()
                .collect(Collectors.groupingBy(Bug::getProjectId, Collectors.counting()));
        return projects.stream().map(p -> {
            ReportProjectOverviewVO vo = new ReportProjectOverviewVO();
            vo.setProjectId(p.getId());
            vo.setProjectName(p.getName());
            vo.setMemberCount(memberCounts.getOrDefault(p.getId(), 0L));
            vo.setRequirementCount(reqCounts.getOrDefault(p.getId(), 0L));
            vo.setOpenBugCount(bugCounts.getOrDefault(p.getId(), 0L));
            return vo;
        }).toList();
    }
}
