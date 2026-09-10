package com.pm.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 报表-人员负载 VO：某人某自然月的排期工时合计（排除 VOID 排期行；仅在有排期的月份有记录）
 */
@Data
public class ReportWorkloadVO {

    private Long userId;

    private String realName;

    private Integer year;

    private Integer month;

    /** 该月工时合计（人日） */
    private BigDecimal effort;
}
