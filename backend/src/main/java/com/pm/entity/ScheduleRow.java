package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 排期行：需求×工种×负责人×月（对应表 schedule_row）
 * 已排工作量 = SUM(schedule_cell.value) 实时计算，不落库；
 * 计划工期 planned_effort 由 PM 填写，两者之差是跨月顺延的判定依据
 */
@Data
@TableName("schedule_row")
public class ScheduleRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long monthId;

    private Long requirementId;

    /** 工种：PLANNER/FE/BE/OPS/QA/UI */
    private String craft;

    /** 负责人 */
    private Long userId;

    /** 当月表内需求展示序号（编号列） */
    private Integer seq;

    /** 计划工期（PM 填写，0.1 步进如 5 或 1.3）；跨月顺延判定=已排格子之和 < 计划工期 且 当月无剩余可填工作日 */
    private BigDecimal plannedEffort;

    /** 状态：TODO待开始/VOID作废（参与人移除）；已排/排期中/未排期三态由前端按工期与已排合计派生 */
    private String status;

    /** 1=跨月自动顺延生成的行 */
    private Integer isOverflow;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
