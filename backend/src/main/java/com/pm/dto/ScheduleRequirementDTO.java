package com.pm.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 把需求加入月度排期表入参（POST /api/schedule/months/{monthId}/requirements，仅该需求项目的 PM）
 * 按需求参与人（requirement_participant）自动生成排期行
 */
@Data
public class ScheduleRequirementDTO {

    /** 需求 ID（必须为 PENDING 待排期状态） */
    @NotNull(message = "requirementId不能为空")
    private Long requirementId;
}
