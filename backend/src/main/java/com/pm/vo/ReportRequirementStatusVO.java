package com.pm.vo;

import lombok.Data;

/**
 * 报表-需求状态分布 VO：项目 × 状态的需求个数（全部状态含终态）
 */
@Data
public class ReportRequirementStatusVO {

    private Long projectId;

    private String projectName;

    /** 需求状态：PENDING/DEV/TEST/ARCHIVING/ARCHIVED/CANCELLED */
    private String status;

    /** 需求个数 */
    private Long count;
}
