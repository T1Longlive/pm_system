package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.service.RequirementService;
import com.pm.vo.RequirementDetailVO;
import com.pm.vo.RequirementListVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller 切片测试（standalone MockMvc）：
 * 参数绑定、Bean Validation、统一 Result 包装、全局异常处理与当前用户透传。
 *
 * <p>不启动 Spring 上下文：只组装 Controller + ControllerAdvice + Validator，
 * 避免 WebConfig/JwtInterceptor/数据源等无关依赖进入测试。
 */
@ExtendWith(MockitoExtension.class)
class RequirementControllerTest {

    @Mock
    private RequirementService requirementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new RequirementController(requirementService));
    }

    @Test
    @DisplayName("列表：查询参数原样绑定，当前用户从 request 属性透传")
    void listBindsQueryAndUser() throws Exception {
        when(requirementService.pageRequirements(9L, 1L, "DEV", 1, 2L, "kw", true, 2, 20))
                .thenReturn(new Page<>(2, 20));

        mockMvc.perform(get("/api/requirements")
                        .param("projectId", "1")
                        .param("status", "DEV")
                        .param("priority", "1")
                        .param("plannerId", "2")
                        .param("keyword", "kw")
                        .param("excludeTerminal", "true")
                        .param("page", "2")
                        .param("size", "20")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.current").value(2));
    }

    @Test
    @DisplayName("创建：@Valid 校验失败 → HTTP 200 + code 400「参数错误」")
    void createValidationFailure() throws Exception {
        mockMvc.perform(post("/api/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith("参数错误：")));
    }

    @Test
    @DisplayName("创建成功：返回新需求 id，userId 透传")
    void createSuccess() throws Exception {
        when(requirementService.createRequirement(eq(9L), any())).thenReturn(5L);

        mockMvc.perform(post("/api/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":1,\"title\":\"需求\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(5));

        verify(requirementService).createRequirement(eq(9L), any());
    }

    @Test
    @DisplayName("取消：原因为空 → 400")
    void cancelRejectsBlankReason() throws Exception {
        mockMvc.perform(post("/api/requirements/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith("参数错误：")));
    }

    @Test
    @DisplayName("服务层 BizException → HTTP 200 + 业务码（前端按 code 处理）")
    void bizExceptionMapsToBusinessCode() throws Exception {
        when(requirementService.pageRequirements(any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyLong(), anyLong()))
                .thenThrow(new BizException(403, "无权限"));

        mockMvc.perform(get("/api/requirements").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权限"));
    }

    @Test
    @DisplayName("详情：路径变量与当前用户正确透传")
    void detailBindsPathAndUser() throws Exception {
        when(requirementService.getRequirementDetail(9L, 7L)).thenReturn(new RequirementDetailVO());

        mockMvc.perform(get("/api/requirements/7").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
