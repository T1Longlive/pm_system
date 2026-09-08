package com.pm.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 保存排期行入参（PUT /api/schedule/rows/{rowId}，仅项目 PM）
 * 全量替换该行格子与计划工期：cells 空 map = 清空全部格子，plannedEffort 为 null = 清空计划工期
 */
@Data
public class ScheduleRowSaveDTO {

    /** 计划工期（可空；>0 且最多两位小数），跨月顺延判定依据 */
    private BigDecimal plannedEffort;

    /** 逐日工作量：key=日期（2026-09-04），value=当天工作量（0<v≤1，步进0.1）；空 map=清空全部格子 */
    @NotNull(message = "cells不能为空")
    private Map<String, BigDecimal> cells;

    /** 乐观锁版本：前端编辑时原样回传的行 updated_at（毫秒时间戳） */
    @NotNull(message = "updatedAt不能为空")
    private Long updatedAt;
}
