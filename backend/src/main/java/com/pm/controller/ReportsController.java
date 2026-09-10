package com.pm.controller;

import com.pm.common.Result;
import com.pm.service.ReportsService;
import com.pm.vo.ReportProjectOverviewVO;
import com.pm.vo.ReportRequirementStatusVO;
import com.pm.vo.ReportWorkloadVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计报表接口（/api/reports，任何登录用户，只读汇总）
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportsController {

    private final ReportsService reportsService;

    /**
     * 需求状态分布：全部项目 × 全部状态的需求个数
     */
    @GetMapping("/requirement-status")
    public Result<List<ReportRequirementStatusVO>> requirementStatus() {
        return Result.success(reportsService.requirementStatus());
    }

    /**
     * 人员负载：最近 months 个自然月（含当月）按 人员×月份 的排期工时合计；months 限 1~12，默认 6
     */
    @GetMapping("/workload")
    public Result<List<ReportWorkloadVO>> workload(@RequestParam(defaultValue = "6") int months) {
        if (months < 1 || months > 12) {
            months = 6;
        }
        return Result.success(reportsService.workload(months));
    }

    /**
     * 项目概览：每项目的成员数 / 非终态需求数 / 未归档 bug 数
     */
    @GetMapping("/project-overview")
    public Result<List<ReportProjectOverviewVO>> projectOverview() {
        return Result.success(reportsService.projectOverview());
    }
}
