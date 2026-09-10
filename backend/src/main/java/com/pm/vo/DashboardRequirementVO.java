package com.pm.vo;

import lombok.Data;

/**
 * 工作台"我参与的需求"列表项 VO（轻量，不含进度缩略图数据）
 */
@Data
public class DashboardRequirementVO {

    private Long id;

    /** 需求编号，如 REQ-ACE-0001 */
    private String reqNo;

    /** 需求标题 */
    private String title;

    private Long projectId;

    /** 所属项目名 */
    private String projectName;

    /** 派生状态：PENDING/DEV/TEST/ARCHIVING */
    private String status;

    /** 优先级 */
    private Integer priority;
}
