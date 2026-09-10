package com.pm.service;

import com.pm.vo.ReportProjectOverviewVO;
import com.pm.vo.ReportRequirementStatusVO;
import com.pm.vo.ReportWorkloadVO;

import java.util.List;

/**
 * 统计报表服务（全员可见，只读汇总）
 */
public interface ReportsService {

    /**
     * 需求状态分布：全部项目 × 全部状态的需求个数（含终态）
     */
    List<ReportRequirementStatusVO> requirementStatus();

    /**
     * 人员负载：最近 months 个自然月（含当月）按 人员×月份 的排期工时合计（排除 VOID 行）
     */
    List<ReportWorkloadVO> workload(int months);

    /**
     * 项目概览：每项目的成员数 / 非终态需求数 / 未归档 bug 数
     */
    List<ReportProjectOverviewVO> projectOverview();
}
