package com.pm.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 添加项目成员入参（POST /api/projects/{id}/members）
 */
@Data
public class MemberAddDTO {

    @NotNull(message = "userId不能为空")
    private Long userId;

    /** 工种代码集合（PLANNER/FE/BE/OPS/QA/UI），具体合法值由 Service 层校验 */
    @NotEmpty(message = "工种不能为空")
    private List<String> crafts;
}
