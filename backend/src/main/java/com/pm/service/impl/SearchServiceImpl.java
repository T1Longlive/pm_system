package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pm.entity.Bug;
import com.pm.entity.Project;
import com.pm.entity.ProjectMember;
import com.pm.entity.Requirement;
import com.pm.entity.User;
import com.pm.mapper.BugMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.SearchService;
import com.pm.vo.SearchResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 全局搜索实现：一次 keyword 聚合四类结果，每类 LIMIT 5。
 * 可见性口径：需求全员可见；项目/Bug 与项目列表页同口径（管理员全量，普通用户仅参与或创建的项目）；
 * 人员仅返回姓名/用户名。
 */
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    /** 每类结果条数上限 */
    private static final int LIMIT = 5;

    private final RequirementMapper requirementMapper;
    private final BugMapper bugMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;

    @Override
    public SearchResultVO search(Long userId, String keyword) {
        SearchResultVO vo = new SearchResultVO();
        if (!StringUtils.hasText(keyword)) {
            return vo;
        }
        String kw = keyword.trim();

        boolean admin = isAdmin(userId);

        // ===== 项目：名称/缩写模糊匹配；普通用户仅参与或创建的项目 =====
        LambdaQueryWrapper<Project> projectWrapper = new LambdaQueryWrapper<Project>()
                .and(w -> w.like(Project::getName, kw).or().like(Project::getCode, kw))
                .orderByDesc(Project::getId)
                .last("LIMIT " + LIMIT);
        if (!admin) {
            List<Long> joinedIds = joinedProjectIds(userId);
            if (joinedIds.isEmpty()) {
                projectWrapper.eq(Project::getCreatorId, userId);
            } else {
                projectWrapper.and(w -> w.in(Project::getId, joinedIds).or().eq(Project::getCreatorId, userId));
            }
        }
        List<Project> projects = projectMapper.selectList(projectWrapper);
        vo.setProjects(projects.stream().map(p -> {
            SearchResultVO.ProjectItem item = new SearchResultVO.ProjectItem();
            item.setId(p.getId());
            item.setName(p.getName());
            item.setCode(p.getCode());
            return item;
        }).toList());

        // ===== Bug：功能模块/编号模糊匹配；限定用户可见项目 =====
        if (admin) {
            fillBugs(vo, new LambdaQueryWrapper<Bug>()
                    .and(w -> w.like(Bug::getFeature, kw).or().like(Bug::getBugNo, kw))
                    .orderByDesc(Bug::getId)
                    .last("LIMIT " + LIMIT));
        } else {
            Set<Long> visibleIds = visibleProjectIds(userId);
            if (!visibleIds.isEmpty()) {
                fillBugs(vo, new LambdaQueryWrapper<Bug>()
                        .in(Bug::getProjectId, visibleIds)
                        .and(w -> w.like(Bug::getFeature, kw).or().like(Bug::getBugNo, kw))
                        .orderByDesc(Bug::getId)
                        .last("LIMIT " + LIMIT));
            }
        }

        // ===== 需求：标题/编号模糊匹配（全员可见，含终态——搜索可定位归档/取消需求） =====
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .and(w -> w.like(Requirement::getTitle, kw).or().like(Requirement::getReqNo, kw))
                .orderByDesc(Requirement::getId)
                .last("LIMIT " + LIMIT));
        Map<Long, String> projectNames = projectNamesOf(reqs.stream()
                .map(Requirement::getProjectId).collect(Collectors.toSet()));
        vo.setRequirements(reqs.stream().map(r -> {
            SearchResultVO.ReqItem item = new SearchResultVO.ReqItem();
            item.setId(r.getId());
            item.setReqNo(r.getReqNo());
            item.setTitle(r.getTitle());
            item.setProjectName(projectNames.getOrDefault(r.getProjectId(), ""));
            item.setStatus(r.getStatus());
            return item;
        }).toList());

        // ===== 人员：姓名/用户名模糊匹配（仅公开信息） =====
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .and(w -> w.like(User::getRealName, kw).or().like(User::getUsername, kw))
                .orderByAsc(User::getId)
                .last("LIMIT " + LIMIT));
        vo.setUsers(users.stream().map(u -> {
            SearchResultVO.UserItem item = new SearchResultVO.UserItem();
            item.setId(u.getId());
            item.setRealName(u.getRealName());
            item.setUsername(u.getUsername());
            return item;
        }).toList());

        return vo;
    }

    /**
     * Bug 命中项组装：批量补项目名
     */
    private void fillBugs(SearchResultVO vo, LambdaQueryWrapper<Bug> wrapper) {
        List<Bug> bugs = bugMapper.selectList(wrapper);
        Map<Long, String> projectNames = projectNamesOf(bugs.stream()
                .map(Bug::getProjectId).collect(Collectors.toSet()));
        vo.setBugs(bugs.stream().map(b -> {
            SearchResultVO.BugItem item = new SearchResultVO.BugItem();
            item.setId(b.getId());
            item.setBugNo(b.getBugNo());
            item.setProjectId(b.getProjectId());
            item.setFeature(b.getFeature());
            item.setProjectName(projectNames.getOrDefault(b.getProjectId(), ""));
            item.setHandleStatus(b.getHandleStatus());
            item.setArchived(b.getArchived());
            return item;
        }).toList());
    }

    /**
     * 批量取项目名（空集合直接返回空 Map，避免 selectBatchIds 全表）
     */
    private Map<Long, String> projectNamesOf(Set<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
    }

    /**
     * 普通用户参与的项目 id 集合（project_member 有记录）
     */
    private List<Long> joinedProjectIds(Long userId) {
        return projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getUserId, userId))
                .stream().map(ProjectMember::getProjectId).distinct().toList();
    }

    /**
     * 用户可见项目 id 集合 = 参与的项目 ∪ 创建的项目（Bug 搜索过滤用，含创建项避免漏）
     */
    private Set<Long> visibleProjectIds(Long userId) {
        Set<Long> ids = new java.util.HashSet<>(joinedProjectIds(userId));
        projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .select(Project::getId)
                        .eq(Project::getCreatorId, userId))
                .forEach(p -> ids.add(p.getId()));
        return ids;
    }

    /**
     * 是否全局管理员
     */
    private boolean isAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && user.getIsAdmin() != null && user.getIsAdmin() == 1;
    }
}
