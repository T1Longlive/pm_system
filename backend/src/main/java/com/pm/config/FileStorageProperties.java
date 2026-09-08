package com.pm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置：读取 application.yml 的 pm.upload-dir（附件存本地磁盘，按 年/月 子目录组织）
 */
@Data
@Component
@ConfigurationProperties(prefix = "pm")
public class FileStorageProperties {

    /** 附件上传根目录，默认 D:/pm-upload，经 WebConfig 静态映射 /upload/** 对外提供访问 */
    private String uploadDir = "D:/pm-upload";
}
