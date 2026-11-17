package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.service.BugService;
import com.pm.vo.BugListVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug 接口切片测试：默认分页参数、创建校验、逐格更新的清空哨兵、截图与生命周期端点。
 */
@ExtendWith(MockitoExtension.class)
class BugControllerTest {

    @Mock
    private BugService bugService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new BugController(bugService));
    }

    @Test
    @DisplayName("列表：archived/page/size 默认值正确绑定")
    void pageDefaults() throws Exception {
        when(bugService.pageBugs(9L, null, 0, null, 1, 1000)).thenReturn(new Page<>(1, 1000));

        mockMvc.perform(get("/api/bugs").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("创建：projectId 缺失 → 400")
    void createValidation() throws Exception {
        mockMvc.perform(post("/api/bugs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("逐格更新：空串与 0 作为清空哨兵可正常提交")
    void updateWithClearSentinels() throws Exception {
        mockMvc.perform(put("/api/bugs/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feature\":\"\",\"priority\":0,\"handleStatus\":\"\",\"ownerId\":0}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(bugService).updateBug(eq(9L), eq(1L), any());
    }

    @Test
    @DisplayName("截图：空列表 → 400（@NotEmpty）")
    void addImagesValidation() throws Exception {
        mockMvc.perform(post("/api/bugs/1/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[]}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("截图：成功追加返回新附件 id 列表")
    void addImagesSuccess() throws Exception {
        when(bugService.addImages(eq(9L), eq(1L), any())).thenReturn(List.of(11L, 12L));

        mockMvc.perform(post("/api/bugs/1/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[{\"url\":\"/upload/a.png\",\"fileName\":\"a.png\","
                                + "\"fileType\":\"IMAGE\",\"fileSize\":10}]}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(11))
                .andExpect(jsonPath("$.data[1]").value(12));

        verify(bugService).addImages(eq(9L), eq(1L), any());
    }

    @Test
    @DisplayName("删除单张截图：路径变量与当前用户透传")
    void deleteImage() throws Exception {
        mockMvc.perform(delete("/api/bugs/1/images/5")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(bugService).deleteImage(9L, 1L, 5L);
    }

    @Test
    @DisplayName("归档 / 重开 / 删除：路径变量与当前用户透传")
    void lifecycleEndpoints() throws Exception {
        mockMvc.perform(post("/api/bugs/1/archive").requestAttr("userId", 9L))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/bugs/1/reopen").requestAttr("userId", 9L))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/bugs/1").requestAttr("userId", 9L))
                .andExpect(status().isOk());

        verify(bugService).archiveBug(9L, 1L);
        verify(bugService).reopenBug(9L, 1L);
        verify(bugService).deleteBug(9L, 1L);
    }

    @Test
    @DisplayName("创建成功：返回新行完整 VO")
    void createSuccess() throws Exception {
        BugListVO vo = new BugListVO();
        vo.setId(5L);
        vo.setBugNo("BUG-T-0001");
        when(bugService.createBug(eq(9L), any())).thenReturn(vo);

        mockMvc.perform(post("/api/bugs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":1}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.bugNo").value("BUG-T-0001"));
    }
}
