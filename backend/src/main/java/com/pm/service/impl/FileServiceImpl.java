package com.pm.service.impl;

import com.pm.common.BizException;
import com.pm.config.FileStorageProperties;
import com.pm.service.FileService;
import com.pm.vo.FileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 附件上传服务实现：扩展名白名单 + 大小限制校验，本地磁盘按 年/月 目录落盘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** 图片扩展名白名单（单张 ≤10MB） */
    private static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /** 文档扩展名白名单（单个 ≤50MB） */
    private static final Set<String> DOC_EXTS = Set.of("pdf", "doc", "docx", "xlsx", "zip");

    /** 视频扩展名白名单（单个 ≤50MB，浏览器原生 <video> 可播放） */
    private static final Set<String> VIDEO_EXTS = Set.of("mp4", "webm", "mov");

    /** 图片大小上限：10MB */
    private static final long IMAGE_MAX_SIZE = 10L * 1024 * 1024;

    /** 文档大小上限：50MB */
    private static final long DOC_MAX_SIZE = 50L * 1024 * 1024;

    /** 视频大小上限：50MB */
    private static final long VIDEO_MAX_SIZE = 50L * 1024 * 1024;

    /** 文件类别：图片 */
    private static final String TYPE_IMAGE = "IMAGE";

    /** 文件类别：文档 */
    private static final String TYPE_DOC = "DOC";

    /** 文件类别：视频 */
    private static final String TYPE_VIDEO = "VIDEO";

    private final FileStorageProperties properties;

    @Override
    public FileVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要上传的文件");
        }
        // 原始文件名：去除浏览器可能携带的路径部分，仅保留文件名
        String original = file.getOriginalFilename();
        String fileName = original == null ? "" : original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        // 提取扩展名（小写），按扩展名判断类别并校验大小
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
        String fileType;
        if (IMAGE_EXTS.contains(ext)) {
            fileType = TYPE_IMAGE;
            if (file.getSize() > IMAGE_MAX_SIZE) {
                throw new BizException(400, "图片大小不能超过10MB");
            }
        } else if (DOC_EXTS.contains(ext)) {
            fileType = TYPE_DOC;
            if (file.getSize() > DOC_MAX_SIZE) {
                throw new BizException(400, "文档大小不能超过50MB");
            }
        } else if (VIDEO_EXTS.contains(ext)) {
            fileType = TYPE_VIDEO;
            if (file.getSize() > VIDEO_MAX_SIZE) {
                throw new BizException(400, "视频大小不能超过50MB");
            }
        } else {
            throw new BizException(400, "不支持的文件类型：" + (ext.isEmpty() ? "缺少扩展名" : ext));
        }
        // 文件名列宽保护（attachment.file_name varchar(100)）
        if (fileName.length() > 100) {
            fileName = fileName.substring(0, 100);
        }

        // 存储路径：{upload-dir}/{yyyy}/{MM}/{uuid}.{ext}
        LocalDateTime now = LocalDateTime.now();
        String monthDir = String.format("%d/%02d", now.getYear(), now.getMonthValue());
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Path dir = Paths.get(properties.getUploadDir(), monthDir);
            Files.createDirectories(dir);
            // 转绝对路径，避免 multipart 临时目录作为相对路径基准
            Path target = dir.resolve(storedName).toAbsolutePath();
            file.transferTo(target);
        } catch (IOException e) {
            log.error("附件保存失败：{}", fileName, e);
            throw new BizException(500, "文件保存失败，请稍后重试");
        }

        FileVO vo = new FileVO();
        vo.setUrl("/upload/" + monthDir + "/" + storedName);
        vo.setFileName(fileName);
        vo.setFileType(fileType);
        vo.setFileSize(file.getSize());
        return vo;
    }
}
