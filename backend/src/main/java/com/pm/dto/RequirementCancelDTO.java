package com.pm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取消需求入参（POST /api/requirements/{id}/cancel），仅项目 PM 可操作
 */
@Data
public class RequirementCancelDTO {

    @NotBlank(message = "取消原因不能为空")
    @Size(max = 500, message = "取消原因不能超过500字")
    private String reason;
}
