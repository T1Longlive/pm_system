package com.pm.vo;

import lombok.Data;

/**
 * 工作日历单日 VO（日历接口与排期视图共用）
 */
@Data
public class CalendarDayVO {

    /** 日期，格式 2026-09-01 */
    private String day;

    /** 类型：WORKDAY工作日/WEEKEND周末/HOLIDAY法定节假日/ADJUSTED调休上班 */
    private String type;

    /** 节假日名称（非节假日为 null） */
    private String name;
}
