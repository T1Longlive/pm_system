package com.pm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑项目入参（PUT /api/projects/{id}）
 */
@Data
public class ProjectUpdateDTO {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 50, message = "项目名称不能超过50字")
    private String name;

    @Size(max = 500, message = "项目描述不能超过500字")
    private String description;
}
