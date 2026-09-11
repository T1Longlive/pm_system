package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.entity.BizOperationLog;
import com.pm.entity.Bug;
import com.pm.entity.Project;
import com.pm.entity.Requirement;
import com.pm.entity.User;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.BugMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.OperationLogService;
import com.pm.vo.OperationLogItemVO;
import com.pm.vo.UserOptionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一操作日志查询实现
 * 可见范围：管理员=全部项目；普通用户=仅自己创建（creator_id=自己）的项目（即项目经理视角）
 * 业务编号/名称回填：按 bizType 分组批量查 requirement/bug/project，避免 N+1
 */
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final BizOperationLogMapper operationLogMapper;
    private final ProjectMapper projectMapper;
    private final RequirementMapper requirementMapper;
    private final BugMapper bugMapper;
    private final UserMapper userMapper;

    @Override
    public Page<OperationLogItemVO> pageLogs(Long userId, String bizType, String action, Long operatorId,
                                             Long projectId, String keyword, LocalDate startDate, LocalDate endDate,
                                             long page, long size) {
        // 可见项目范围：管理员全量；项目经理=自己创建的项目
        List<Long> visibleProjectIds = visibleProjectIds(userId);
        // 非管理员且未创建任何项目：无可见范围，直接返回空页（避免 IN () 语法错误）
        if (visibleProjectIds.isEmpty()) {
            Page<OperationLogItemVO> emptyPage = new Page<>(Math.max(page, 1), size <= 0 || size > 200 ? 50 : size, 0);
            emptyPage.setRecords(List.of());
            return emptyPage;
        }

        // 指定项目筛选：越权（非自己可见项目）直接拒绝
        if (projectId != null && !visibleProjectIds.contains(projectId)) {
            throw new BizException(403, "无权限查看该项目日志");
        }

        LambdaQueryWrapper<BizOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BizOperationLog::getProjectId, visibleProjectIds);
        if (StringUtils.hasText(bizType)) {
            wrapper.eq(BizOperationLog::getBizType, bizType);
        }
        if (StringUtils.hasText(action)) {
            wrapper.eq(BizOperationLog::getAction, action);
        }
        if (operatorId != null) {
            wrapper.eq(BizOperationLog::getOperatorId, operatorId);
        }
        if (projectId != null) {
            wrapper.eq(BizOperationLog::getProjectId, projectId);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(BizOperationLog::getRemark, keyword.trim());
        }
        if (startDate != null) {
            wrapper.ge(BizOperationLog::getCreatedAt, startDate.atStartOfDay());
        }
        if (endDate != null) {
            wrapper.lt(BizOperationLog::getCreatedAt, endDate.plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(BizOperationLog::getId);

        long safePage = page <= 0 ? 1 : page;
        long safeSize = size <= 0 || size > 200 ? 50 : size;
        Page<BizOperationLog> logPage = operationLogMapper.selectPage(new Page<>(safePage, safeSize), wrapper);

        Page<OperationLogItemVO> voPage = new Page<>(logPage.getCurrent(), logPage.getSize(), logPage.getTotal());
        voPage.setRecords(assembleVOs(logPage.getRecords()));
        return voPage;
    }

    @Override
    public List<UserOptionVO> listOperators(Long userId) {
        List<Long> visibleProjectIds = visibleProjectIds(userId);
        if (visibleProjectIds.isEmpty()) {
            return List.of();
        }
        // SQL 层 DISTINCT 去重（可见项目范围内日志出现过的操作人），避免把日志行全量拉到内存
        List<Long> operatorIds = operationLogMapper.selectDistinctOperatorIds(visibleProjectIds);
        if (operatorIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(operatorIds).stream()
                .map(u -> {
                    UserOptionVO vo = new UserOptionVO();
                    vo.setId(u.getId());
                    vo.setRealName(u.getRealName());
                    vo.setUsername(u.getUsername());
                    return vo;
                })
                .sorted(java.util.Comparator.comparing(UserOptionVO::getId))
                .toList();
    }

    /**
     * 当前用户可见的项目 id 集合：管理员=全部项目；否则=自己创建的项目（项目经理视角）
     */
    private List<Long> visibleProjectIds(Long userId) {
        User user = userMapper.selectById(userId);
        if (user != null && user.getIsAdmin() != null && user.getIsAdmin() == 1) {
            return projectMapper.selectList(new LambdaQueryWrapper<Project>().select(Project::getId))
                    .stream().map(Project::getId).toList();
        }
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .select(Project::getId)
                        .eq(Project::getCreatorId, userId))
                .stream().map(Project::getId).toList();
    }

    /**
     * 批量装配 VO：操作人姓名 + 业务编号/名称 + 项目名（分组批查，避免 N+1）
     */
    private List<OperationLogItemVO> assembleVOs(List<BizOperationLog> logs) {
        if (logs.isEmpty()) {
            return List.of();
        }
        // 操作人
        Set<Long> operatorIds = logs.stream().map(BizOperationLog::getOperatorId).collect(Collectors.toSet());
        Map<Long, String> operatorNames = userMapper.selectBatchIds(operatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));
        // 项目
        Set<Long> projectIds = logs.stream().map(BizOperationLog::getProjectId).collect(Collectors.toSet());
        Map<Long, String> projectNames = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                        .collect(Collectors.toMap(Project::getId, Project::getName));
        // 需求编号/标题
        List<Long> reqIds = logs.stream().filter(l -> "REQUIREMENT".equals(l.getBizType()))
                .map(BizOperationLog::getBizId).distinct().toList();
        Map<Long, Requirement> reqMap = reqIds.isEmpty() ? Map.of()
                : requirementMapper.selectBatchIds(reqIds).stream()
                        .collect(Collectors.toMap(Requirement::getId, r -> r));
        // Bug 编号/功能模块
        List<Long> bugIds = logs.stream().filter(l -> "BUG".equals(l.getBizType()))
                .map(BizOperationLog::getBizId).distinct().toList();
        Map<Long, Bug> bugMap = bugIds.isEmpty() ? Map.of()
                : bugMapper.selectBatchIds(bugIds).stream()
                        .collect(Collectors.toMap(Bug::getId, b -> b));
        // 项目业务本身（bizType=PROJECT 时 bizId 即项目 id）
        List<Long> projBizIds = logs.stream().filter(l -> "PROJECT".equals(l.getBizType()))
                .map(BizOperationLog::getBizId).distinct().toList();
        Map<Long, Project> projBizMap = projBizIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projBizIds).stream()
                        .collect(Collectors.toMap(Project::getId, p -> p));

        List<OperationLogItemVO> vos = new ArrayList<>(logs.size());
        for (BizOperationLog log : logs) {
            OperationLogItemVO vo = new OperationLogItemVO();
            vo.setId(log.getId());
            vo.setBizType(log.getBizType());
            vo.setBizId(log.getBizId());
            vo.setProjectId(log.getProjectId());
            vo.setProjectName(projectNames.get(log.getProjectId()));
            vo.setAction(log.getAction());
            vo.setOperatorId(log.getOperatorId());
            vo.setOperatorName(operatorNames.get(log.getOperatorId()));
            vo.setRemark(log.getRemark());
            vo.setCreatedAt(log.getCreatedAt());
            switch (log.getBizType()) {
                case "REQUIREMENT" -> {
                    Requirement req = reqMap.get(log.getBizId());
                    vo.setBizNo(req == null ? null : req.getReqNo());
                    vo.setBizName(req == null ? null : req.getTitle());
                }
                case "BUG" -> {
                    Bug bug = bugMap.get(log.getBizId());
                    vo.setBizNo(bug == null ? null : bug.getBugNo());
                    vo.setBizName(bug == null ? null : bug.getFeature());
                }
                case "PROJECT" -> {
                    Project p = projBizMap.get(log.getBizId());
                    vo.setBizNo(p == null ? null : p.getCode());
                    vo.setBizName(p == null ? null : p.getName());
                }
                default -> {
                }
            }
            vos.add(vo);
        }
        return vos;
    }
}
