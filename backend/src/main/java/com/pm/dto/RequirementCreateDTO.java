package com.pm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建需求入参（POST /api/requirements）
 * 权限：该项目 PM 或该项目的 PLANNER 工种成员
 */
@Data
public class RequirementCreateDTO {

    @NotNull(message = "projectId不能为空")
    private Long projectId;

    @NotBlank(message = "需求名称不能为空")
    @Size(max = 100, message = "需求名称不能超过100字")
    private String title;

    /** 优先级：1高 2中 3低，可空默认2（中） */
    @Min(value = 1, message = "优先级只能为1/2/3")
    @Max(value = 3, message = "优先级只能为1/2/3")
    private Integer priority;

    /** 需求描述（富文本HTML，可空） */
    private String description;

    /** 策划，可空默认当前用户；提交时必须为项目成员（PM 或任一工种成员） */
    private Long plannerId;

    /** 参与人（userId + 工种），可空；每人必须是该项目对应工种成员 */
    @Valid
    private List<ParticipantDTO> participants;

    /** 附件，可空；先调 POST /api/files/upload 再回传，落 attachment 表（biz_type=REQUIREMENT） */
    @Valid
    private List<AttachmentDTO> attachments;
}
