package com.pm.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目详情（GET /api/projects/{id}）
 */
@Data
public class ProjectDetailVO {

    private Long id;

    /** 项目缩写（编号前缀） */
    private String code;

    private String name;

    private String description;

    private Long creatorId;

    private String creatorName;

    private LocalDateTime createdAt;

    /** 我的角色：创建者返回 PM，兼任工种时为 PM + 工种，普通成员返回工种数组 */
    private List<String> myRole;

    /** 成员列表：按用户分组聚合工种 */
    private List<ProjectMemberVO> members;
}
