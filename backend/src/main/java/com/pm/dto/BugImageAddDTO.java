package com.pm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Bug 追加图片入参（POST /api/bugs/{id}/images）
 * 权限：创建者或项目 PM；图片先调 POST /api/files/upload 再回传
 */
@Data
public class BugImageAddDTO {

    /** 图片列表，每项 fileType 必须为 IMAGE（Service 层校验） */
    @NotEmpty(message = "图片列表不能为空")
    @Valid
    private List<AttachmentDTO> images;
}
