package com.pm.service;

import com.pm.vo.DashboardRequirementVO;
import com.pm.vo.DashboardSummaryVO;

import java.util.List;

/**
 * 工作台服务（当前用户个人视角的汇总数据，全员可见）
 */
public interface DashboardService {

    /**
     * 工作台汇总卡片：未读通知数 / 我参与的非终态需求数 / 本自然月我的排期工时 / 我的待处理 bug 数
     */
    DashboardSummaryVO summary(Long userId);

    /**
     * 我参与的需求（非终态，按更新时间倒序，上限 20 条，轻量字段）
     */
    List<DashboardRequirementVO> myRequirements(Long userId);
}
