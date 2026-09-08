package com.pm.vo;

import lombok.Data;

import java.util.List;

/**
 * 项目成员项（项目详情 members 元素），按用户聚合工种
 */
@Data
public class ProjectMemberVO {

    private Long userId;

    private String realName;

    private String username;

    /** 该成员在本项目兼任的工种代码集合 */
    private List<String> crafts;
}
