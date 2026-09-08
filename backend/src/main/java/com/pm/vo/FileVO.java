package com.pm.vo;

import lombok.Data;

/**
 * 上传文件返回（POST /api/files/upload）
 */
@Data
public class FileVO {

    /** 文件访问路径，如 /upload/2026/09/uuid.png */
    private String url;

    /** 原始文件名 */
    private String fileName;

    /** 文件类别：IMAGE图片 / DOC文档（按扩展名判断） */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;
}
