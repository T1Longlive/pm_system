package com.pm.service;

import com.pm.vo.FileVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件上传服务：校验白名单与大小限制后落盘本地磁盘
 */
public interface FileService {

    /**
     * 上传附件（任何登录用户）：
     * 图片 png/jpg/jpeg/gif/webp ≤10MB；文档 pdf/doc/docx/xlsx/zip ≤50MB；
     * 存储路径 {pm.upload-dir}/{yyyy}/{MM}/{uuid}.{ext}，返回访问 url 与文件元信息
     */
    FileVO upload(MultipartFile file);
}
