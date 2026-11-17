package com.pm.service.impl;

import com.pm.common.BizException;
import com.pm.config.FileStorageProperties;
import com.pm.vo.FileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 附件上传的边界测试：白名单、大小、文件名清洗、落盘路径。
 * 纯单元测试，使用临时目录，不启动 Spring、不连数据库。
 */
class FileServiceImplTest {

    private static final long MB = 1024L * 1024L;

    @TempDir
    Path tempDir;

    private FileServiceImpl service;

    @BeforeEach
    void setUp() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setUploadDir(tempDir.toString());
        service = new FileServiceImpl(properties);
    }

    @Test
    @DisplayName("图片上传：类型 IMAGE、URL 按年/月/uuid 组织、文件真实落盘")
    void uploadImage() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "x".getBytes());

        FileVO vo = service.upload(file);

        assertThat(vo.getFileType()).isEqualTo("IMAGE");
        assertThat(vo.getFileName()).isEqualTo("avatar.png");
        assertThat(vo.getUrl()).matches("/upload/\\d{4}/\\d{2}/[0-9a-f]{32}\\.png");
        assertThat(regularFiles(tempDir)).hasSize(1);
    }

    @Test
    @DisplayName("文档与视频按扩展名归类")
    void uploadDocAndVideo() {
        assertThat(service.upload(new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes()))
                .getFileType()).isEqualTo("DOC");
        assertThat(service.upload(new MockMultipartFile("file", "a.mp4", "video/mp4", "x".getBytes()))
                .getFileType()).isEqualTo("VIDEO");
    }

    @Test
    @DisplayName("扩展名大小写不敏感，落盘统一小写")
    void extensionIsCaseInsensitive() {
        FileVO vo = service.upload(new MockMultipartFile("file", "A.PNG", "image/png", "x".getBytes()));

        assertThat(vo.getFileType()).isEqualTo("IMAGE");
        assertThat(vo.getUrl()).endsWith(".png");
    }

    @Test
    @DisplayName("文件名中的路径部分被剥离，无法穿越上传目录")
    void stripsPathFromFileName() {
        FileVO vo = service.upload(new MockMultipartFile("file",
                "..\\..\\evil.png", "image/png", "x".getBytes()));

        assertThat(vo.getFileName()).isEqualTo("evil.png");
        assertThat(regularFiles(tempDir)).hasSize(1);
        assertThat(regularFiles(tempDir).get(0).startsWith(tempDir)).isTrue();
    }

    @Test
    @DisplayName("超长文件名截断到 100 字符（列宽保护）")
    void truncatesLongFileName() {
        String longName = "a".repeat(120) + ".png";

        FileVO vo = service.upload(new MockMultipartFile("file", longName, "image/png", "x".getBytes()));

        assertThat(vo.getFileName()).hasSize(100);
    }

    @Test
    @DisplayName("空文件 / 不支持类型 / 缺少扩展名 → 400")
    void rejectsInvalidFiles() {
        assertBiz(400, "请选择要上传的文件",
                new MockMultipartFile("file", "a.png", "image/png", new byte[0]));
        assertBiz(400, "不支持的文件类型：exe",
                new MockMultipartFile("file", "a.exe", "application/octet-stream", "x".getBytes()));
        assertBiz(400, "缺少扩展名",
                new MockMultipartFile("file", "README", "text/plain", "x".getBytes()));
    }

    @Test
    @DisplayName("超过分类大小上限 → 400（不落盘）")
    void rejectsOversizedFiles() {
        assertBiz(400, "图片大小不能超过10MB", mockFile("big.png", 10 * MB + 1));
        assertBiz(400, "文档大小不能超过50MB", mockFile("big.pdf", 50 * MB + 1));
        assertBiz(400, "视频大小不能超过50MB", mockFile("big.mp4", 50 * MB + 1));
    }

    @Test
    @DisplayName("磁盘写入失败 → 500「文件保存失败」")
    void reportsIoFailure() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("a.png");
        when(file.getSize()).thenReturn(10L);
        doThrow(new IOException("disk full")).when(file).transferTo(any(Path.class));

        BizException ex = catchThrowableOfType(() -> service.upload(file), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(500);
        assertThat(ex.getMessage()).contains("文件保存失败");
    }

    // ==================== 辅助 ====================

    private static MultipartFile mockFile(String name, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getSize()).thenReturn(size);
        return file;
    }

    private void assertBiz(int code, String messagePart, MultipartFile file) {
        BizException ex = catchThrowableOfType(() -> service.upload(file), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(code);
        assertThat(ex.getMessage()).contains(messagePart);
    }

    private static List<Path> regularFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
