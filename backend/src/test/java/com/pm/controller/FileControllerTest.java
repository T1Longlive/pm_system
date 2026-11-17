package com.pm.controller;

import com.pm.common.BizException;
import com.pm.service.FileService;
import com.pm.vo.FileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件上传接口切片测试：multipart 参数绑定与服务层业务错误透传。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock
    private FileService fileService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new FileController(fileService));
    }

    @Test
    @DisplayName("上传成功：返回 url 与文件元信息")
    void uploadSuccess() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2});
        FileVO vo = new FileVO();
        vo.setUrl("/upload/2026/11/x.png");
        vo.setFileName("a.png");
        vo.setFileType("IMAGE");
        vo.setFileSize(2L);
        when(fileService.upload(any())).thenReturn(vo);

        mockMvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").value("/upload/2026/11/x.png"))
                .andExpect(jsonPath("$.data.fileType").value("IMAGE"));

        verify(fileService).upload(any());
    }

    @Test
    @DisplayName("上传失败：白名单校验错误按业务码 400 返回")
    void uploadRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.exe", "application/octet-stream", new byte[]{1});
        when(fileService.upload(any())).thenThrow(new BizException(400, "不支持的文件类型"));

        mockMvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不支持的文件类型"));
    }
}
