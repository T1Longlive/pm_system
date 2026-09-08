package com.pm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑需求入参（PUT /api/requirements/{id}）
 * 字段均为可空：null 表示不修改；participants/attachments 非 null 时分别按 diff / 全量替换处理。
 * 权限：项目 PM 或需求创建人；终态（已归档/已取消）不可编辑
 */
@Data
public class RequirementUpdateDTO {

    @Size(max = 100, message = "需求名称不能超过100字")
    private String title;

    /** 优先级：1高 2中 3低，null 表示不修改 */
    @Min(value = 1, message = "优先级只能为1/2/3")
    @Max(value = 3, message = "优先级只能为1/2/3")
    private Integer priority;

    /** 需求描述（富文本HTML），null 表示不修改 */
    private String description;

    /** 策划，null 表示不修改；提交时必须为项目成员 */
    private Long plannerId;

    /** 参与人全量提交：null 不修改，非 null 与库中记录做增删 diff */
    @Valid
    private List<ParticipantDTO> participants;

    /** 附件全量提交：null 不修改，非 null 全量替换（简单可靠） */
    @Valid
    private List<AttachmentDTO> attachments;

    /** 乐观锁版本：页面加载时的 updated_at（毫秒时间戳），必填；与库中不一致返回 409 */
    @NotNull(message = "updatedAt不能为空")
    private Long updatedAt;
}
