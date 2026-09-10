package com.pm.vo;

import lombok.Data;

/**
 * 工种排期日期区间（批量版，需求列表进度缩略图用）：
 * 多个需求的全部非 VOID 行按（需求, 工种）聚合 min/max 格子日（跨全部月份）
 */
@Data
public class CraftScheduleBatchVO {

    /** 需求 ID */
    private Long requirementId;

    /** 工种 */
    private String craft;

    /** 最早格子日（yyyy-MM-dd） */
    private String startDate;

    /** 最晚格子日（yyyy-MM-dd） */
    private String endDate;
}
