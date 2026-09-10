package com.pm.controller;

import com.pm.common.Result;
import com.pm.service.DashboardService;
import com.pm.vo.DashboardRequirementVO;
import com.pm.vo.DashboardSummaryVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作台接口（/api/dashboard，任何登录用户）
 * 个人视角汇总：统计卡片 + 我参与的需求（通知复用 /api/notifications 既有接口）
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 汇总卡片：未读通知数 / 我参与的非终态需求数 / 本月我的排期工时 / 我的待处理 bug 数
     */
    @GetMapping("/summary")
    public Result<DashboardSummaryVO> summary(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(dashboardService.summary(userId));
    }

    /**
     * 我参与的需求（非终态，更新时间倒序，上限 20 条）
     */
    @GetMapping("/my-requirements")
    public Result<List<DashboardRequirementVO>> myRequirements(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(dashboardService.myRequirements(userId));
    }
}
