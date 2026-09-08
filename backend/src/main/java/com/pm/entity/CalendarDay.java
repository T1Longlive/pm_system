package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工作日历（对应表 calendar_day）
 * 主键为日期 day；初始化生成未来3年，周末自动标记，节假日/调休由管理员维护
 */
@Data
@TableName("calendar_day")
public class CalendarDay {

    /** 主键：日期（非自增，插入时由应用赋值） */
    @TableId(value = "day", type = IdType.INPUT)
    private LocalDate day;

    /** 类型：WORKDAY工作日/WEEKEND周末/HOLIDAY法定节假日/ADJUSTED调休上班(周末标记为工作日) */
    private String type;

    /** 节假日名称 */
    private String name;

    private LocalDateTime updatedAt;
}
