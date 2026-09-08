package com.pm.controller;

import com.pm.common.Result;
import com.pm.service.FileService;
import com.pm.vo.FileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件上传接口（/api/files）
 * 任何登录用户可上传；白名单：图片 png/jpg/jpeg/gif/webp ≤10MB，文档 pdf/doc/docx/xlsx/zip ≤50MB，视频 mp4/webm/mov ≤50MB
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 上传附件：落盘 {pm.upload-dir}/{yyyy}/{MM}/{uuid}.{ext}，返回 url 供需求/Bug 提交时引用
     */
    @PostMapping("/upload")
    public Result<FileVO> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileService.upload(file));
    }
}
