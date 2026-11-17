package com.pm.controller;

import com.pm.common.BizException;
import com.pm.dto.LoginVO;
import com.pm.dto.UserVO;
import com.pm.service.AuthService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口切片测试：登录参数校验、业务错误码、改密与当前用户透传。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new AuthController(authService));
    }

    @Test
    @DisplayName("登录成功：返回 token 与用户信息")
    void loginSuccess() throws Exception {
        LoginVO vo = new LoginVO();
        vo.setToken("tk");
        vo.setUserId(7L);
        vo.setUsername("admin");
        vo.setRealName("管理员");
        vo.setIsAdmin(1);
        vo.setIsPm(0);
        vo.setForceChangePwd(0);
        when(authService.login(any())).thenReturn(vo);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"pwd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("tk"))
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.isAdmin").value(1));
    }

    @Test
    @DisplayName("登录参数为空 → 400「参数错误」")
    void loginValidation() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith("参数错误：")));
    }

    @Test
    @DisplayName("凭证错误：服务层 401 业务码原样返回（HTTP 200）")
    void loginBusinessError() throws Exception {
        when(authService.login(any())).thenThrow(new BizException(401, "用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("改密：userId 透传，成功返回 200")
    void changePasswordPassesUser() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"old\",\"newPassword\":\"new123\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(authService).changePassword(eq(9L), any());
    }

    @Test
    @DisplayName("改密：原密码/新密码为空 → 400")
    void changePasswordValidation() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("当前用户：userId 透传并返回用户信息")
    void mePassesUser() throws Exception {
        UserVO vo = new UserVO();
        vo.setUserId(9L);
        vo.setUsername("u");
        vo.setRealName("用户");
        vo.setIsAdmin(0);
        vo.setIsPm(1);
        vo.setForceChangePwd(0);
        when(authService.me(9L)).thenReturn(vo);

        mockMvc.perform(get("/api/auth/me").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(9))
                .andExpect(jsonPath("$.data.isPm").value(1));
    }
}
