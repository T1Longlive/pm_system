package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 排期格子：行×日（对应表 schedule_cell）
 * 空格子不落库，只存已填格子
 */
@Data
@TableName("schedule_cell")
public class ScheduleCell {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rowId;

    /** 日期 */
    private LocalDate day;

    /** 当天工作量 0<x<=1，步进0.1 */
    private BigDecimal value;
}
