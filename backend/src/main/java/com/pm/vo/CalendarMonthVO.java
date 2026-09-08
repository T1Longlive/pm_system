package com.pm.vo;

import lombok.Data;

import java.util.List;

/**
 * 整月工作日历 VO（GET /api/calendar?year=&month=）
 */
@Data
public class CalendarMonthVO {

    /** 年份 */
    private Integer year;

    /** 月份 1-12 */
    private Integer month;

    /** 整月每天（1号~月末），按日期升序 */
    private List<CalendarDayVO> days;
}
