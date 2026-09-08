package com.pm.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志项（GET /api/requirements/{id}/logs 记录行，id 倒序）
 */
@Data
public class OperationLogVO {

    private Long id;

    private Long operatorId;

    private String operatorName;

    /** 动作：CREATE/UPDATE/CANCEL/ARCHIVE 等，时间线直接展示变更摘要 remark */
    private String action;

    /** 变更摘要，如「优先级：中 → 高」「参与人 +张三(FE)」 */
    private String remark;

    /** Bug 验收轮次（需求恒 1） */
    private Integer roundNo;

    private LocalDateTime createdAt;
}
