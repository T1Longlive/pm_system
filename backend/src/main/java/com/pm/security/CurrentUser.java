package com.pm.security;

import com.pm.common.BizException;
import com.pm.entity.User;
import com.pm.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 当前用户 helper：读取 JwtInterceptor 写入的请求属性，
 * 并提供基于数据库（user.is_admin）的管理员权限校验，供 Controller 调用
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    /** 静态持有 UserMapper 供静态方法查询（Spring 容器启动时通过 @PostConstruct 初始化） */
    private static UserMapper userMapper;

    private final UserMapper mapper;

    /**
     * 容器启动后把注入的 mapper 赋给静态字段
     */
    @PostConstruct
    public void init() {
        CurrentUser.userMapper = this.mapper;
    }

    /**
     * 获取当前登录用户 ID（JWT 拦截器校验通过后写入的请求属性）
     */
    public static Long getUserId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    /**
     * 校验当前登录用户为管理员：根据 userId 查 user.is_admin，
     * 非管理员抛 BizException(403,"无权限")，校验通过返回 userId
     */
    public static Long getAdminUserId(HttpServletRequest request) {
        Long userId = getUserId(request);
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || user.getIsAdmin() == null || user.getIsAdmin() != 1) {
            throw new BizException(403, "无权限");
        }
        return userId;
    }
}
