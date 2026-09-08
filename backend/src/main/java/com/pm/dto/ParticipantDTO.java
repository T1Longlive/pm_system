package com.pm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 需求参与人入参项（创建/编辑需求的 participants 数组元素，同一人兼多工种需分别提交多行）
 */
@Data
public class ParticipantDTO {

    @NotNull(message = "参与人userId不能为空")
    private Long userId;

    /** 工种代码：PLANNER/FE/BE/OPS/QA/UI（合法性由 Service 层校验） */
    @NotBlank(message = "参与人工种不能为空")
    private String craft;
}
