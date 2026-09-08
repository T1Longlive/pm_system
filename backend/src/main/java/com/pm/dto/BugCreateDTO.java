package com.pm.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建 Bug 入参（POST /api/bugs）——Excel 化最小行创建
 * 任何登录用户可创建；编号后端自动生成（BUG-{项目缩写}-{4位流水}），
 * 测试人员=当前用户，其余字段留空由前端单元格编辑填充
 */
@Data
public class BugCreateDTO {

    @NotNull(message = "projectId不能为空")
    private Long projectId;
}
