package com.pm.vo;

import lombok.Data;

/**
 * 月度排期表 VO（月表列表/创建/排期视图的 month 字段）
 */
@Data
public class ScheduleMonthVO {

    /** 月表 ID */
    private Long id;

    /** 年份 */
    private Integer year;

    /** 月份 1-12 */
    private Integer month;
}
