package com.pm.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 工作台汇总统计 VO（当前用户个人视角）
 */
@Data
public class DashboardSummaryVO {

    /** 我的未读通知数 */
    private Long unreadNotifications;

    /** 我参与的非终态需求数 */
    private Long myRequirementCount;

    /** 本自然月我的排期工时合计 */
    private BigDecimal monthEffort;

    /** 我的待处理 bug 数（我是负责人、未归档、处理状态非 DONE/SKIP） */
    private Long openBugCount;
}
