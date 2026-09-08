package com.pm.vo;

import lombok.Data;

/**
 * 附件项（需求详情 attachments 元素）
 */
@Data
public class AttachmentVO {

    private Long id;

    /** 文件访问路径，如 /upload/2026/09/uuid.png */
    private String url;

    /** 原始文件名 */
    private String fileName;

    /** 文件类别：IMAGE图片 / DOC文档 */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;
}
