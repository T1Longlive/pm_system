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
     * 允许跨域的来源，逗号分隔；由环境变量 {@code CORS_ALLOWED_ORIGINS} 注入。
     * <p>
     * 默认只放行本地开发端口（Vite 5173 / 本地前端 8081）。
     * <p>
     * ⚠️ 注意：<b>「经 nginx 同源反向代理就无需配 CORS」这个想法是错的。</b>
     * 浏览器对非 GET/HEAD 请求（包括同源 POST）也会带 {@code Origin} 头，
     * 而 Spring 判断「是否跨域」是拿 {@code Origin} 与
     * <b>由 Host 头反推出的本机 origin</b> 做比对。
     * 若 nginx 用 {@code proxy_set_header Host $host}（会剥掉端口）透传，
     * 后端算出的端口（8080）与浏览器 Origin 的端口（8081）不一致，
     * 同源请求会被误判为跨域，并因白名单不含该来源而返回
     * {@code 403 Invalid CORS request}。
     * <p>
     * 正确做法有两层，本仓库两者都做了：
     * <ol>
     *   <li>nginx 用 {@code $http_host} 原样透传（含端口）→ 后端判定为同源，CORS 不参与；</li>
     *   <li>如仍有独立域名的跨域调用，再把该来源显式加进 {@code CORS_ALLOWED_ORIGINS}。</li>
     * </ol>
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

