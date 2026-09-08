package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目成员，按工种建行，一人可多工种=多行（对应表 project_member）
 */
@Data
@TableName("project_member")
public class ProjectMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long userId;

    /** 工种：PLANNER/FE/BE/OPS/QA/UI */
    private String craft;

    private LocalDateTime createdAt;
}
