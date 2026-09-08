package com.pm.config;

import com.pm.security.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：CORS 跨域 + JWT 拦截器注册 + 附件静态资源映射
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    private final FileStorageProperties fileStorageProperties;

    /**
     * 跨域配置：允许前端本地开发环境跨域访问
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 拦截器注册：拦截全部 /api/** 请求，
     * 白名单放行登录接口（登录前无法携带 token）
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }

    /**
     * 附件静态资源映射：/upload/** -> 本地磁盘 {pm.upload-dir}/ 下同名文件，
     * 富文本内嵌图片与附件下载直接走此 URL（不经过 JWT 拦截器）
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 统一转为正斜杠并确保以 / 结尾，兼容 Windows 路径写法
        String dir = fileStorageProperties.getUploadDir().replace("\\", "/");
        if (!dir.endsWith("/")) {
            dir = dir + "/";
        }
        registry.addResourceHandler("/upload/**")
                .addResourceLocations("file:" + dir);
    }
}

