package com.pm.config;

import com.pm.security.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
     * 允许跨域的来源，逗号分隔。
     * <p>
     * 默认只放行本地开发端口（Vite 5173 / 本地前端 8081）。
     * 生产环境经 nginx 同源反向代理访问（浏览器眼里前后端同源），
     * 因此无需放行任何外部来源；如确需跨域，用环境变量
     * {@code CORS_ALLOWED_ORIGINS} 显式列出白名单。
     */
    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:8081}")
    private String corsAllowedOrigins;

    /**
     * 跨域配置：默认仅放行本地开发来源，避免任意站点带凭证跨域访问
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = corsAllowedOrigins.split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }
        registry.addMapping("/**")
                .allowedOrigins(origins)
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

