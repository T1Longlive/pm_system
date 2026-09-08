package com.pm.vo;

import lombok.Data;

/**
 * 工种排期日期区间（需求详情进度条按工种配色用）：
 * 该需求全部非 VOID 行按 craft 聚合 min/max 格子日（跨全部月份）；无格子的工种无记录
 */
@Data
public class CraftScheduleVO {

    /** 工种 */
    private String craft;

    /** 最早格子日（yyyy-MM-dd） */
    private String startDate;

    /** 最晚格子日（yyyy-MM-dd） */
    private String endDate;
}
