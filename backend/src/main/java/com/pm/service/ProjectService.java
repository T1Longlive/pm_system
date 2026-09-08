package com.pm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.dto.MemberAddDTO;
import com.pm.dto.ProjectCreateDTO;
import com.pm.dto.ProjectUpdateDTO;
import com.pm.vo.ProjectDetailVO;
import com.pm.vo.ProjectListVO;

/**
 * 项目管理服务：项目查询 / 创建 / 编辑 / 成员工种管理
 */
public interface ProjectService {

    /**
     * 项目分页列表：管理员可见全部项目；普通用户仅可见我参与（project_member 有记录）或我创建的项目。
     * myRole：创建者返回 PM，兼任工种时为 PM + 工种，普通成员返回工种数组
     */
    Page<ProjectListVO> pageProjects(Long userId, String keyword, long page, long size);

    /**
     * 创建项目（仅管理员，由 Controller 层校验管理员身份）：项目缩写全局唯一，创建者自动担任项目经理
     */
    void createProject(Long adminUserId, ProjectCreateDTO dto);

    /**
     * 项目详情：仅项目创建者 / 项目成员 / 管理员可见；members 按用户分组聚合工种
     */
    ProjectDetailVO getProjectDetail(Long userId, Long projectId);

    /**
     * 编辑项目名称与描述（仅该项目 PM = 创建者）
     */
    void updateProject(Long userId, Long projectId, ProjectUpdateDTO dto);

    /**
     * 添加项目成员（仅 PM）：工种增量合并，该用户已有工种跳过，新工种插入 project_member
     */
    void addMember(Long userId, Long projectId, MemberAddDTO dto);

    /**
     * 移除项目成员（仅 PM）：craft 非空删除该用户该工种一行；为空删除该用户全部工种行
     */
    void removeMember(Long userId, Long projectId, Long targetUserId, String craft);
}
