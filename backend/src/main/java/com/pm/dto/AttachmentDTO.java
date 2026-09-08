package com.pm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 附件入参项（创建/编辑需求的 attachments 数组元素，先调 POST /api/files/upload 拿到 url 再回传）
 */
@Data
public class AttachmentDTO {

    /** 文件访问路径，如 /upload/2026/09/uuid.png */
    @NotBlank(message = "附件url不能为空")
    private String url;

    /** 原始文件名 */
    @NotBlank(message = "附件文件名不能为空")
    private String fileName;

    /** 文件类别：IMAGE图片 / DOC文档 */
    @NotBlank(message = "附件类型不能为空")
    private String fileType;

    /** 文件大小（字节） */
    @NotNull(message = "附件大小不能为空")
    private Long fileSize;
}
