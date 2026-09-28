package com.wpc725562.examtracker.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 的签发与校验。
 *
 * <p><b>为什么用 JWT 而不是服务端 Session：</b>这个 API 会被前端（浏览器）、
 * 手机、以及将来的定时任务同时调用，服务端不想维护会话状态，
 * 也不想让每个请求都去查一次 Redis。JWT 把身份信息放在 token 里自带过来，
 * 服务端只验签名。
 *
 * <p><b>代价要说清楚：</b>JWT 签发后**无法单独撤销**，只能等它过期。
 * 所以这里把有效期设得比较短（默认 12 小时），并且**不往 token 里放任何敏感信息**
 * （只放 userId 和 username）—— token 是 Base64 编码，不是加密，任何人都能解开看。
 */
@Service
public class JwtService {

    /** HS256 的密钥长度下限（字节）。低于这个值 jjwt 会直接抛异常，这里提前给出可读的错误。 */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String CLAIM_USERNAME = "username";

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;

        byte[] keyBytes = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < MIN_SECRET_BYTES) {
            // 在**启动时**失败，而不是等到第一个用户登录才失败。
            // 配置错了就应该起不来，这样问题在部署那一刻就暴露。
            throw new IllegalStateException(
                    "app.jwt.secret 太短：需要至少 " + MIN_SECRET_BYTES + " 字节，当前 "
                            + keyBytes.length + " 字节。可用 `openssl rand -base64 48` 生成。");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** 签发 token。subject 放 userId，username 放自定义声明。 */
    public String generateToken(Long userId, String username) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.expireMinutes() * 60);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 校验并解析 token。
     *
     * <p>签名不对、格式不对、已过期 —— jjwt 都会抛 {@link JwtException} 的子类。
     * 这里不吞掉它，交给调用方决定怎么处理（本项目里统一转成 401）。
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String extractUsername(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    /** token 有效期（秒），登录响应里返回给前端，让它提前刷新而不是等 401。 */
    public long getExpiresInSeconds() {
        return properties.expireMinutes() * 60;
    }
}
