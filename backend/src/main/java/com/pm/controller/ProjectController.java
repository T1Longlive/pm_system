package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.Result;
import com.pm.dto.MemberAddDTO;
import com.pm.dto.ProjectCreateDTO;
import com.pm.dto.ProjectUpdateDTO;
import com.pm.security.CurrentUser;
import com.pm.service.ProjectService;
import com.pm.vo.ProjectDetailVO;
import com.pm.vo.ProjectListVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目管理接口（/api/projects）
 * 创建项目仅管理员可调；列表/详情按参与关系控制可见范围；编辑与成员管理仅该项目 PM
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 项目分页列表：管理员全部项目；普通用户仅我参与的或我创建的项目
     */
    @GetMapping
    public Result<Page<ProjectListVO>> page(@RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(projectService.pageProjects(userId, keyword, page, size));
    }

    /**
     * 创建项目（仅管理员）：创建者自动担任项目经理，code 全局唯一
     */
    @PostMapping
    public Result<Void> create(@Valid @RequestBody ProjectCreateDTO dto,
                               HttpServletRequest request) {
        Long adminUserId = CurrentUser.getAdminUserId(request);
        projectService.createProject(adminUserId, dto);
        return Result.success();
    }

    /**
     * 项目详情：创建者/项目成员/管理员可见，members 按用户聚合工种
     */
    @GetMapping("/{id}")
    public Result<ProjectDetailVO> detail(@PathVariable Long id,
                                          HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(projectService.getProjectDetail(userId, id));
    }

    /**
     * 编辑项目信息（仅该项目 PM）
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ProjectUpdateDTO dto,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        projectService.updateProject(userId, id, dto);
        return Result.success();
    }

    /**
     * 添加项目成员（仅 PM）：工种增量合并，已有工种自动跳过
     */
    @PostMapping("/{id}/members")
    public Result<Void> addMember(@PathVariable Long id,
                                  @Valid @RequestBody MemberAddDTO dto,
                                  HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        projectService.addMember(userId, id, dto);
        return Result.success();
    }

    /**
     * 移除项目成员（仅 PM）：带 craft 参数删该工种一行，不带删该用户全部工种行
     */
    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@PathVariable Long id,
                                     @PathVariable Long userId,
                                     @RequestParam(required = false) String craft,
                                     HttpServletRequest request) {
        Long operatorId = (Long) request.getAttribute("userId");
        projectService.removeMember(operatorId, id, userId, craft);
        return Result.success();
    }
}
