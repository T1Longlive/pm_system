package com.pm.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 需求排期组聚合视图（需求状态派生用）：
 * 一条记录 = 一个排期组（同需求 × 工种 × 负责人，跨全部月份的非 VOID 行聚合）
 */
@Data
public class RequirementGroupAggVO {

    private Long requirementId;

    /** 工种（组键之一） */
    private String craft;

    /** 负责人（组键之一） */
    private Long userId;

    /** 组内共用工期（跨月行取 MAX；全组无工期时为 null） */
    private BigDecimal plannedEffort;

    /** 组内非 VOID 行数（跨全部月份） */
    private Long rowCount;

    /** 组内有格子的行数（< rowCount 表示存在空行，阻塞日期驱动的窗口判定） */
    private Long rowsWithCells;

    /** 组内全部月份格子工作量合计 */
    private BigDecimal total;

    /** 组内最大格子日（组内无任何格子时为 null） */
    private LocalDate maxDay;
}
