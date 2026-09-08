package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 月度排期表，对应排期样板一个sheet（对应表 schedule_month）
 */
@Data
@TableName("schedule_month")
public class ScheduleMonth {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 年份 */
    private Integer year;

    /** 月份：1-12 */
    private Integer month;

    /** 创建人（PM点击+ 或系统自动） */
    private Long createdBy;

    private LocalDateTime createdAt;
}
