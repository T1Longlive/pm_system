package com.pm.controller;

import com.pm.common.GlobalExceptionHandler;
import com.pm.entity.User;
import com.pm.mapper.UserMapper;
import com.pm.security.CurrentUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.lang.reflect.Field;

import static org.mockito.Mockito.when;

/**
 * Controller 切片测试的公共装配：Controller + 全局异常处理 + Bean Validation。
 * 不启动 Spring 上下文，避免 WebConfig / JwtInterceptor / 数据源进入测试。
 */
final class ControllerTestSupport {

    private ControllerTestSupport() {
    }

    static MockMvc build(Object... controllers) throws Exception {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    /**
     * 让 CurrentUser.getAdminUserId 判定为管理员。
     * CurrentUser 依赖静态 UserMapper（@PostConstruct 注入），切片测试无 Spring 容器，
     * 这里直接构造实例并调用 init() 完成静态赋值。
     */
    static void installAdmin(long adminUserId, UserMapper mapper) {
        User user = new User();
        user.setId(adminUserId);
        user.setIsAdmin(1);
        when(mapper.selectById(adminUserId)).thenReturn(user);
        new CurrentUser(mapper).init();
    }

    /**
     * 让 CurrentUser.getAdminUserId 判定为非管理员，用于 403 断言
     */
    static void installNonAdmin(long userId, UserMapper mapper) {
        User user = new User();
        user.setId(userId);
        user.setIsAdmin(0);
        when(mapper.selectById(userId)).thenReturn(user);
        new CurrentUser(mapper).init();
    }

    /**
     * 清空 CurrentUser 的静态 Mapper，避免 mock 状态跨测试类残留
     */
    static void clearCurrentUser() {
        try {
            Field field = CurrentUser.class.getDeclaredField("userMapper");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("重置 CurrentUser.userMapper 失败", e);
        }
    }
}
