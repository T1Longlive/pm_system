package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.mapper.UserMapper;
import com.pm.service.UserService;
import com.pm.vo.UserOptionVO;
import com.pm.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 人员管理接口切片测试：管理员校验、分页默认值、入参校验与各管理端点透传。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserMapper userMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new UserController(userService));
    }

    @AfterEach
    void tearDown() {
        ControllerTestSupport.clearCurrentUser();
    }

    @Test
    @DisplayName("列表：管理员校验通过，keyword/status 缺省为空、分页默认 1/10")
    void pageDefaults() throws Exception {
        ControllerTestSupport.installAdmin(9L, userMapper);
        Page<UserVO> page = new Page<>(1, 10);
        UserVO vo = new UserVO();
        vo.setId(2L);
        vo.setUsername("u2");
        vo.setStatus(1);
        page.setRecords(List.of(vo));
        when(userService.pageUsers(null, null, 1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/users").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].username").value("u2"));

        verify(userService).pageUsers(null, null, 1, 10);
    }

    @Test
    @DisplayName("列表：非管理员 → 403")
    void pageForbidden() throws Exception {
        ControllerTestSupport.installNonAdmin(9L, userMapper);

        mockMvc.perform(get("/api/users").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("新增：用户名为空/过短 → 400")
    void createValidation() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"realName\":\"张三\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("新增：管理员通过后创建，密码可缺省")
    void createSuccess() throws Exception {
        ControllerTestSupport.installAdmin(9L, userMapper);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zhangsan\",\"realName\":\"张三\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(userService).createUser(any());
    }

    @Test
    @DisplayName("编辑：姓名为空 → 400")
    void updateValidation() throws Exception {
        mockMvc.perform(put("/api/users/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("重置密码：密码可空，路径 id 透传")
    void resetPassword() throws Exception {
        ControllerTestSupport.installAdmin(9L, userMapper);

        mockMvc.perform(post("/api/users/2/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(userService).resetPassword(eq(2L), any());
    }

    @Test
    @DisplayName("状态：status 越界 → 400")
    void statusValidation() throws Exception {
        mockMvc.perform(post("/api/users/2/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":2}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("停用：操作人（当前用户）与目标用户分离透传")
    void updateStatusPassesOperator() throws Exception {
        ControllerTestSupport.installAdmin(9L, userMapper);

        mockMvc.perform(post("/api/users/2/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(userService).updateStatus(eq(9L), eq(2L), any());
    }

    @Test
    @DisplayName("下拉选项：任何登录用户可调，无需管理员")
    void options() throws Exception {
        UserOptionVO option = new UserOptionVO();
        option.setId(3L);
        option.setRealName("李四");
        option.setUsername("lisi");
        when(userService.listOptions()).thenReturn(List.of(option));

        mockMvc.perform(get("/api/users/options").requestAttr("userId", 3L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].username").value("lisi"));
    }
}
