package com.pm.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：生成 / 解析 token
 * 密钥与过期时间从配置读取（application.yml: jwt.secret / jwt.expire）
 */
@Component
public class JwtUtil {

    private final SecretKey secretKey;

    /** token 有效期（分钟） */
    private final long expireMinutes;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire}") long expireMinutes) {
        // HS256 要求密钥长度不低于 32 字节（配置中已约束至少 32 字符）
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    /**
     * 生成 token：subject 存用户ID，附带 username 声明
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expireMinutes * 60 * 1000))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析 token：签名或有效期校验失败会抛出 JwtException / IllegalArgumentException
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
