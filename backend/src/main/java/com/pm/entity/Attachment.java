package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件，统一管理需求/Bug的附件，富文本内嵌图片同样走此表（对应表 attachment）
 */
@Data
@TableName("attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型：REQUIREMENT/BUG */
    private String bizType;

    /** 业务主键 */
    private Long bizId;

    /** 文件访问路径，如 /upload/2026/09/uuid.png */
    private String fileUrl;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型：IMAGE/DOC/VIDEO_LINK等 */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 上传人 */
    private Long uploadedBy;

    private LocalDateTime createdAt;
}
