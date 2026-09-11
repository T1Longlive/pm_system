package com.pm.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志列表项（统一日志查询用；bizNo/bizName/projectName 由后端批量回填）
 */
@Data
public class OperationLogItemVO {

    private Long id;

    /** 业务类型：REQUIREMENT/BUG/PROJECT */
    private String bizType;

    private Long bizId;

    /** 业务编号（需求 REQ-xxx / Bug BUG-xxx / 项目缩写） */
    private String bizNo;

    /** 业务名称（需求标题 / Bug功能模块 / 项目名） */
    private String bizName;

    private Long projectId;

    private String projectName;

    /** 动作码（前端映射中文名与颜色） */
    private String action;

    private Long operatorId;

    private String operatorName;

    private String remark;

    private LocalDateTime createdAt;
}
