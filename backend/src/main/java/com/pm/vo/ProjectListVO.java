package com.pm.vo;

import lombok.Data;

import java.util.List;

/**
 * 项目列表项（GET /api/projects 记录行）
 */
@Data
public class ProjectListVO {

    private Long id;

    /** 项目缩写（编号前缀） */
    private String code;

    private String name;

    private String description;

    private Long creatorId;

    private String creatorName;

    /** 成员数（按用户去重，一人多工种只算一人） */
    private Integer memberCount;

    /** 我的角色：创建者返回 PM，兼任工种时为 PM + 工种，普通成员返回工种数组 */
    private List<String> myRole;
}
