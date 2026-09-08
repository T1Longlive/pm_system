package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求参与人，排期行生成来源（对应表 requirement_participant）
 */
@Data
@TableName("requirement_participant")
public class RequirementParticipant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long requirementId;

    private Long userId;

    /** 工种：PLANNER/FE/BE/OPS/QA/UI */
    private String craft;

    private LocalDateTime createdAt;
}
