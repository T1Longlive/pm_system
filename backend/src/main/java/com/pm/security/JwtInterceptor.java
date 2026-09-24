package com.pm.security;

// Jackson 3（Spring Boot 4 基线）：包名从 com.fasterxml.jackson 换成 tools.jackson。
// 这里只用到 writeValue()，API 本身没变，改 import 即可。
import tools.jackson.databind.ObjectMapper;
import com.pm.common.Result;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 登录态拦截器：校验请求头 Authorization: Bearer <token>
 * 白名单（/api/auth/login）已在 WebConfig 注册时排除，本拦截器只负责校验
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeError(response, 401, "未登录或缺少 token");
            return false;
        }
        try {
            Claims claims = jwtUtil.parseToken(authorization.substring("Bearer ".length()));
            // 校验通过，将用户信息放入 request 属性，供后续 Controller 使用
            request.setAttribute("userId", Long.valueOf(claims.getSubject()));
            request.setAttribute("username", claims.get("username", String.class));
            return true;
        } catch (Exception e) {
            writeError(response, 401, "token 无效或已过期");
            return false;
        }
    }

    /**
     * 以统一 Result 结构输出 401 业务错误（HTTP 200，code=401，前端按 code 跳转登录页）
     */
    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.error(code, message));
    }
}
