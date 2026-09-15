package com.pm.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtUtil} 的纯单元测试。
 *
 * <p>⚠️ 不启动 Spring 上下文：{@code JwtUtil} 的构造器只是普通参数
 * （Spring 用 {@code @Value} 注入，但测试里直接 new 就行），
 * 所以这里完全不需要容器，也不需要 {@code @SpringBootTest}。
 *
 * <p>重点是**安全性质**而不只是"能生成能解析"：
 * 签名被篡改、密钥不对、已过期 —— 这三种都必须被拒绝。
 * 这几条是"能编译但逻辑写错"最危险的区域：一旦校验被绕过，整个鉴权就形同虚设。
 */
class JwtUtilTest {

    /** 32 字符 = 32 字节，满足 HS256 的密钥长度下限 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("生成后能解析回来：subject 是用户ID，自定义声明 username 保留")
    void roundTrip() {
        JwtUtil jwt = new JwtUtil(SECRET, 60);

        String token = jwt.generateToken(42L, "alice");
        Claims claims = jwt.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username")).isEqualTo("alice");
    }

    @Test
    @DisplayName("有效期与配置的分钟数一致（60 分钟）")
    void expirationMatchesConfiguredMinutes() {
        JwtUtil jwt = new JwtUtil(SECRET, 60);

        Claims claims = jwt.parseToken(jwt.generateToken(1L, "bob"));

        long deltaMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(deltaMs).isEqualTo(60L * 60 * 1000);
    }

    @Test
    @DisplayName("已过期的 token 必须被拒绝（抛 ExpiredJwtException）")
    void expiredTokenRejected() {
        // 有效期 -1 分钟 ⇒ 签发即已过期
        JwtUtil jwt = new JwtUtil(SECRET, -1);

        String token = jwt.generateToken(1L, "bob");

        assertThatThrownBy(() -> jwt.parseToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("用别的密钥签发的 token 必须被拒绝（防伪造）")
    void tokenFromAnotherSecretRejected() {
        JwtUtil signer = new JwtUtil(SECRET, 60);
        JwtUtil verifier = new JwtUtil("ffffffffffffffffffffffffffffffff", 60);

        String token = signer.generateToken(1L, "bob");

        assertThatThrownBy(() -> verifier.parseToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("被改动过的 token 必须被拒绝（防篡改）")
    void tamperedTokenRejected() {
        JwtUtil jwt = new JwtUtil(SECRET, 60);
        String token = jwt.generateToken(1L, "bob");

        // 改掉签名段的第一个字符，签名随即失配
        int lastDot = token.lastIndexOf('.');
        char original = token.charAt(lastDot + 1);
        char flipped = (original == 'A') ? 'B' : 'A';
        String tampered = token.substring(0, lastDot + 1) + flipped + token.substring(lastDot + 2);

        assertThatThrownBy(() -> jwt.parseToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("密钥短于 32 字节时构造即失败（HS256 下限，防止弱密钥混进配置）")
    void weakSecretRejected() {
        assertThatThrownBy(() -> new JwtUtil("too-short", 60))
                .isInstanceOf(WeakKeyException.class);
    }
}
