package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.common.CraftConstants;
import com.pm.dto.MemberAddDTO;
import com.pm.dto.ProjectCreateDTO;
import com.pm.dto.ProjectUpdateDTO;
import com.pm.entity.BizOperationLog;
import com.pm.entity.Project;
import com.pm.entity.ProjectMember;
import com.pm.entity.User;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.ProjectMapper;
import com.pm.mapper.ProjectMemberMapper;
import com.pm.mapper.UserMapper;
import com.pm.service.ProjectService;
import com.pm.service.RequirementService;
import com.pm.vo.ProjectDetailVO;
import com.pm.vo.ProjectListVO;
import com.pm.vo.ProjectMemberVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目管理服务实现
 */
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final BizOperationLogMapper operationLogMapper;
    private final RequirementService requirementService;

    @Override
    public Page<ProjectListVO> pageProjects(Long userId, String keyword, long page, long size) {
        boolean admin = isAdmin(userId);
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            // keyword 模糊匹配项目名称或缩写
            wrapper.and(w -> w.like(Project::getName, kw).or().like(Project::getCode, kw));
        }
        wrapper.orderByDesc(Project::getId);
        if (!admin) {
            // 普通用户：仅我参与的项目（project_member 有记录）或我创建的项目
            List<Long> joinedProjectIds = projectMemberMapper.selectList(
                            new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getUserId, userId))
                    .stream().map(ProjectMember::getProjectId).distinct().toList();
            wrapper.and(w -> {
                if (joinedProjectIds.isEmpty()) {
                    w.eq(Project::getCreatorId, userId);
                } else {
                    w.in(Project::getId, joinedProjectIds).or().eq(Project::getCreatorId, userId);
                }
            });
        }
        Page<Project> projectPage = projectMapper.selectPage(new Page<>(page, size), wrapper);
        Page<ProjectListVO> voPage =
                new Page<>(projectPage.getCurrent(), projectPage.getSize(), projectPage.getTotal());
        List<Project> projects = projectPage.getRecords();
        if (projects.isEmpty()) {
            voPage.setRecords(new ArrayList<>());
            return voPage;
        }
        List<Long> projectIds = projects.stream().map(Project::getId).toList();

        // 批量查询创建者姓名
        Set<Long> creatorIds = projects.stream().map(Project::getCreatorId).collect(Collectors.toSet());
        Map<Long, String> creatorNames = userMapper.selectBatchIds(creatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName));

        // 批量统计成员数（同一用户多工种只算一人）
        QueryWrapper<ProjectMember> countWrapper = new QueryWrapper<>();
        countWrapper.select("project_id AS projectId", "COUNT(DISTINCT user_id) AS memberCount")
                .in("project_id", projectIds)
                .groupBy("project_id");
        Map<Long, Integer> memberCounts = projectMemberMapper.selectMaps(countWrapper).stream()
                .collect(Collectors.toMap(
                        m -> ((Number) m.get("projectId")).longValue(),
                        m -> ((Number) m.get("memberCount")).intValue()));

        // 当前用户在各项目的工种，用于组装 myRole
        Map<Long, List<String>> myCraftsMap = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(ProjectMember::getUserId, userId)
                                .in(ProjectMember::getProjectId, projectIds))
                .stream().collect(Collectors.groupingBy(ProjectMember::getProjectId,
                        Collectors.mapping(ProjectMember::getCraft, Collectors.toList())));

        List<ProjectListVO> records = new ArrayList<>();
        for (Project project : projects) {
            ProjectListVO vo = new ProjectListVO();
            vo.setId(project.getId());
            vo.setCode(project.getCode());
            vo.setName(project.getName());
            vo.setDescription(project.getDescription());
            vo.setCreatorId(project.getCreatorId());
            vo.setCreatorName(creatorNames.get(project.getCreatorId()));
            vo.setMemberCount(memberCounts.getOrDefault(project.getId(), 0));
            vo.setMyRole(buildMyRole(project.getCreatorId(), userId, myCraftsMap.get(project.getId())));
            records.add(vo);
        }
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public void createProject(Long adminUserId, ProjectCreateDTO dto) {
        // 项目缩写全局唯一
        Long count = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getCode, dto.getCode()));
        if (count != null && count > 0) {
            throw new BizException(400, "项目缩写已存在");
        }
        Project project = new Project();
        project.setCode(dto.getCode());
        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        // 创建者自动担任项目经理（PM 不是工种，不写 project_member）
        project.setCreatorId(adminUserId);
        projectMapper.insert(project);
        // 留痕：创建项目
        writeLog(project, adminUserId, "CREATE", "创建项目：" + project.getName());
    }

    @Override
    public ProjectDetailVO getProjectDetail(Long userId, Long projectId) {
        Project project = requireProject(projectId);
        // 可见性：创建者 / 项目成员 / 管理员
        boolean creator = project.getCreatorId().equals(userId);
        if (!creator && !isAdmin(userId)) {
            Long memberCount = projectMemberMapper.selectCount(
                    new LambdaQueryWrapper<ProjectMember>()
                            .eq(ProjectMember::getProjectId, projectId)
                            .eq(ProjectMember::getUserId, userId));
            if (memberCount == null || memberCount == 0) {
                throw new BizException(403, "无权限");
            }
        }

        // 成员按用户分组聚合工种（LinkedHashMap 保持 userId 升序）
        List<ProjectMember> memberRows = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .orderByAsc(ProjectMember::getUserId)
                        .orderByAsc(ProjectMember::getId));
        Map<Long, List<String>> craftsByUser = new LinkedHashMap<>();
        for (ProjectMember row : memberRows) {
            craftsByUser.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(row.getCraft());
        }
        List<ProjectMemberVO> members = new ArrayList<>();
        if (!craftsByUser.isEmpty()) {
            Map<Long, User> userMap = userMapper.selectBatchIds(craftsByUser.keySet()).stream()
                    .collect(Collectors.toMap(User::getId, u -> u));
            for (Map.Entry<Long, List<String>> entry : craftsByUser.entrySet()) {
                User u = userMap.get(entry.getKey());
                ProjectMemberVO memberVO = new ProjectMemberVO();
                memberVO.setUserId(entry.getKey());
                memberVO.setRealName(u.getRealName());
                memberVO.setUsername(u.getUsername());
                memberVO.setCrafts(entry.getValue());
                members.add(memberVO);
            }
        }

        ProjectDetailVO vo = new ProjectDetailVO();
        vo.setId(project.getId());
        vo.setCode(project.getCode());
        vo.setName(project.getName());
        vo.setDescription(project.getDescription());
        vo.setCreatorId(project.getCreatorId());
        User creatorUser = userMapper.selectById(project.getCreatorId());
        vo.setCreatorName(creatorUser == null ? null : creatorUser.getRealName());
        vo.setCreatedAt(project.getCreatedAt());
        vo.setMyRole(buildMyRole(project.getCreatorId(), userId, craftsByUser.get(userId)));
        vo.setMembers(members);
        return vo;
    }

    @Override
    public void updateProject(Long userId, Long projectId, ProjectUpdateDTO dto) {
        Project project = requireProject(projectId);
        // 仅该项目 PM（创建者）可编辑
        checkPm(userId, project);
        Project update = new Project();
        update.setId(projectId);
        update.setName(dto.getName());
        update.setDescription(dto.getDescription());
        projectMapper.updateById(update);
        // 留痕：编辑项目
        writeLog(project, userId, "UPDATE", "编辑项目信息：" + project.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addMember(Long userId, Long projectId, MemberAddDTO dto) {
        Project project = requireProject(projectId);
        checkPm(userId, project);
        // 工种合法性校验
        for (String craft : dto.getCrafts()) {
            if (!CraftConstants.ALL_CRAFTS.contains(craft)) {
                throw new BizException(400, "非法工种：" + craft);
            }
        }
        // 成员用户必须存在
        User member = userMapper.selectById(dto.getUserId());
        if (member == null) {
            throw new BizException(404, "用户不存在");
        }
        // 增量合并：已有工种跳过，仅插入新工种（配合唯一键 uk_proj_user_craft 天然防重）
        Set<String> existingCrafts = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>()
                                .eq(ProjectMember::getProjectId, projectId)
                                .eq(ProjectMember::getUserId, dto.getUserId()))
                .stream().map(ProjectMember::getCraft).collect(Collectors.toSet());
        List<String> addedCrafts = new ArrayList<>();
        for (String craft : new LinkedHashSet<>(dto.getCrafts())) {
            if (existingCrafts.contains(craft)) {
                continue;
            }
            ProjectMember row = new ProjectMember();
            row.setProjectId(projectId);
            row.setUserId(dto.getUserId());
            row.setCraft(craft);
            projectMemberMapper.insert(row);
            addedCrafts.add(craft);
        }
        // 留痕：添加成员（仅实际新增工种时记录）
        if (!addedCrafts.isEmpty()) {
            String craftNames = addedCrafts.stream().map(CraftConstants.CRAFT_NAMES::get)
                    .collect(Collectors.joining("、"));
            writeLog(project, userId, "ADD_MEMBER",
                    "添加成员：" + member.getRealName() + "（" + craftNames + "）");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long userId, Long projectId, Long targetUserId, String craft) {
        Project project = requireProject(projectId);
        checkPm(userId, project);
        if (craft != null && !CraftConstants.ALL_CRAFTS.contains(craft)) {
            throw new BizException(400, "非法工种：" + craft);
        }
        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, targetUserId);
        if (craft == null) {
            // 不带 craft：删除该用户全部工种行
            int deleted = projectMemberMapper.delete(wrapper);
            if (deleted == 0) {
                throw new BizException(404, "该成员不存在");
            }
        } else {
            // 带 craft：仅删除该用户该工种一行
            int deleted = projectMemberMapper.delete(wrapper.eq(ProjectMember::getCraft, craft));
            if (deleted == 0) {
                throw new BizException(404, "该成员无此工种");
            }
        }
        // 需求联动（5.4.5）：被移除成员退出该项目全部非终态需求的参与人
        // （craft 为空=全部工种），排期行标 VOID、通知本人并对账需求状态；同一事务内失败即回滚
        requirementService.removeParticipantByProjectMember(projectId, targetUserId, craft, userId);
        // 留痕：移除成员（删除成功后记录；目标用户名查库回显）
        User removed = userMapper.selectById(targetUserId);
        String craftText = craft == null ? "全部工种" : CraftConstants.CRAFT_NAMES.getOrDefault(craft, craft);
        writeLog(project, userId, "REMOVE_MEMBER",
                "移除成员：" + (removed == null ? targetUserId : removed.getRealName()) + "（" + craftText + "）");
    }

    /**
     * 写操作日志（biz_type=PROJECT，round_no 恒 1；project_id 即当前项目）
     */
    private void writeLog(Project project, Long operatorId, String action, String remark) {
        BizOperationLog log = new BizOperationLog();
        log.setBizType("PROJECT");
        log.setBizId(project.getId());
        log.setProjectId(project.getId());
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setRoundNo(1);
        log.setRemark(remark);
        operationLogMapper.insert(log);
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
     * 校验操作者是该项目 PM（创建者），否则抛 403
     */
    private void checkPm(Long userId, Project project) {
        if (!project.getCreatorId().equals(userId)) {
            throw new BizException(403, "无权限");
        }
    }

    /**
     * 判断用户是否管理员
     */
    private boolean isAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && user.getIsAdmin() != null && user.getIsAdmin() == 1;
    }

    /**
     * 组装我的角色：创建者含 PM；兼任工种时为 PM + 工种；普通成员返回工种数组
     */
    private List<String> buildMyRole(Long creatorId, Long userId, List<String> crafts) {
        List<String> roles = new ArrayList<>();
        if (creatorId.equals(userId)) {
            roles.add("PM");
        }
        if (crafts != null) {
            crafts.stream().distinct().forEach(roles::add);
        }
        return roles;
    }
}
