package com.pm.vo;

import lombok.Data;

/**
 * 报表-项目概览 VO：每项目的成员数 / 非终态需求数 / 未归档 bug 数
 */
@Data
public class ReportProjectOverviewVO {

    private Long projectId;

    private String projectName;

    /** 项目成员数（按成员去重，一人多工种算一人） */
    private Long memberCount;

    /** 非终态需求数（未归档未取消） */
    private Long requirementCount;

    /** 未归档 bug 数 */
    private Long openBugCount;
}
