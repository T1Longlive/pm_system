package com.pm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 停用/启用人员入参（POST /api/users/{id}/status）
 */
@Data
public class UserStatusDTO {

    @NotNull(message = "status不能为空")
    @Min(value = 0, message = "status只能为0或1")
    @Max(value = 1, message = "status只能为0或1")
    private Integer status;
}
