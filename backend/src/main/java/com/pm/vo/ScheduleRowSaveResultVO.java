package com.pm.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 保存排期行结果 VO（PUT /api/schedule/rows/{rowId}）
 */
@Data
public class ScheduleRowSaveResultVO {

    /** 保存后的行版本（毫秒时间戳，前端下次编辑回传做乐观锁校验） */
    private Long updatedAt;

    /** 跨月顺延信息（未发生顺延为 null，发生时前端提示负责人） */
    private OverflowVO overflow;

    /**
     * 跨月顺延信息：当月排不下剩余工期时，系统自动在下月表追加了顺延行
     */
    @Data
    public static class OverflowVO {

        /** 顺延到的年份 */
        private Integer year;

        /** 顺延到的月份 */
        private Integer month;

        /** 顺延携带的剩余工期（=计划工期-当月已排合计） */
        private BigDecimal carriedEffort;
    }
}
