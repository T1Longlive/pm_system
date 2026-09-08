package com.pm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建月度排期表入参（POST /api/schedule/months，PM 或管理员）
 */
@Data
public class ScheduleMonthCreateDTO {

    /** 年份 */
    @NotNull(message = "year不能为空")
    @Min(value = 2000, message = "年份不合法")
    @Max(value = 2100, message = "年份不合法")
    private Integer year;

    /** 月份 1-12 */
    @NotNull(message = "month不能为空")
    @Min(value = 1, message = "月份只能为1-12")
    @Max(value = 12, message = "月份只能为1-12")
    private Integer month;
}
