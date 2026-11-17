package com.pm.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录拦截器的边界：缺 token、坏 token、合法 token 的 request 属性注入。
 * 使用 MockHttpServletRequest/Response + 真实 JwtUtil，不启动 Spring。
 */
class JwtInterceptorTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private JwtUtil jwtUtil;
    private JwtInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 60);
        interceptor = new JwtInterceptor(jwtUtil, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("缺少 Authorization → 拒绝，HTTP 200 + 业务 401")
    void missingTokenRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(request, response, new Object());

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"code\":401", "未登录或缺少 token");
    }

    @Test
    @DisplayName("格式错误或解析失败的 token → 拒绝，业务 401")
    void malformedTokenRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(request, response, new Object());

        assertThat(passed).isFalse();
        assertThat(response.getContentAsString()).contains("\"code\":401", "token 无效或已过期");
    }

    @Test
    @DisplayName("合法 token → 放行并写入 userId / username")
    void validTokenPassesAndInjectsUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateToken(42L, "alice"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(request, response, new Object());

        assertThat(passed).isTrue();
        assertThat(request.getAttribute("userId")).isEqualTo(42L);
        assertThat(request.getAttribute("username")).isEqualTo("alice");
    }

    @Test
    @DisplayName("subject 不是数字（异常 token）→ 拒绝，不抛给上层")
    void invalidSubjectRejected() throws Exception {
        // 用另一个密钥签发，触发签名校验失败
        JwtUtil other = new JwtUtil("ffffffffffffffffffffffffffffffff", 60);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + other.generateToken(1L, "bob"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getContentAsString()).contains("\"code\":401");
    }
}
