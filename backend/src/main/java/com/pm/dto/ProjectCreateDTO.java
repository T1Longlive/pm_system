package com.pm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建项目入参（POST /api/projects）
 */
@Data
public class ProjectCreateDTO {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 50, message = "项目名称不能超过50字")
    private String name;

    @NotBlank(message = "项目缩写不能为空")
    @Pattern(regexp = "^[A-Z]{2,6}$", message = "项目缩写需为2-6位大写字母")
    private String code;

    @Size(max = 500, message = "项目描述不能超过500字")
    private String description;
}
