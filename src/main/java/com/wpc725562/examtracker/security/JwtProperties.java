package com.wpc725562.examtracker.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 相关配置，绑定 {@code app.jwt.*}。
 *
 * <p>用 record + 构造器绑定（Spring Boot 3 对 record 原生支持）：
 * 字段一旦注入就不可变，不存在「某个 Service 顺手把过期时间改小」这种事。
 *
 * @param secret        签名密钥。HS256 要求**至少 32 字节**，构造 {@link JwtService} 时会校验。
 * @param expireMinutes token 有效期（分钟）
 * @param issuer        签发方标识，写进 {@code iss} 声明，便于多服务共用密钥时区分来源
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long expireMinutes, String issuer) {
}
