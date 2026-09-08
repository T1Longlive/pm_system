package com.pm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 维护工作日历入参（PUT /api/calendar/{day}，仅管理员）
 * day 为路径参数（格式 2026-09-01）
 */
@Data
public class CalendarUpdateDTO {

    /** 类型：WORKDAY工作日/WEEKEND周末/HOLIDAY法定节假日/ADJUSTED调休上班（周末标记为工作日） */
    @NotBlank(message = "日历类型不能为空")
    private String type;

    /** 节假日名称（可空，如「国庆节」） */
    @Size(max = 30, message = "节假日名称不能超过30字")
    private String name;
}
