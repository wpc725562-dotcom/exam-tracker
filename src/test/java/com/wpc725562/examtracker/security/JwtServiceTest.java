package com.wpc725562.examtracker.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发与校验。
 *
 * <p>三件事必须钉死：
 * <ol>
 *   <li>密钥太短要在**构造时**就失败，而不是等第一个用户登录才失败；</li>
 *   <li>签名被改、密钥不对、签发方不对、已过期 —— 都要抛异常，不能被放行；</li>
 *   <li>★ token 里**不能有敏感字段**。JWT 是 Base64 编码不是加密，任何人都能解开看。</li>
 * </ol>
 */
@DisplayName("JwtService —— 签发与校验")
class JwtServiceTest {

    /** 正好 32 字节，是 HS256 的下限。 */
    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "exam-tracker";

    private static JwtService service(String secret, long expireMinutes, String issuer) {
        return new JwtService(new JwtProperties(secret, expireMinutes, issuer));
    }

    private static JwtService defaultService() {
        return service(SECRET_32, 720, ISSUER);
    }

    @Test
    @DisplayName("★ 密钥短于 32 字节 -> 构造时就抛 IllegalStateException（启动即失败）")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> service("too-short", 720, ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("密钥为 null -> 同样在构造时失败（不等到签发）")
    void nullSecretFailsFast() {
        assertThatThrownBy(() -> service(null, 720, ISSUER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("签发 → 解析：userId / username / issuer 都能原样取回")
    void roundTrip() {
        JwtService jwt = defaultService();

        String token = jwt.generateToken(42L, "darling");
        Claims claims = jwt.parse(token);

        assertThat(jwt.extractUserId(claims)).isEqualTo(42L);
        assertThat(jwt.extractUsername(claims)).isEqualTo("darling");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("★ token 里只放 sub / username / iss / iat / exp —— 不放任何敏感信息")
    void tokenCarriesNoSensitiveClaims() {
        Claims claims = defaultService().parse(defaultService().generateToken(7L, "darling"));

        // 这条断言的价值：以后有人想「顺手把邮箱/密码哈希塞进 token 省一次查询」时，
        // 测试会立刻变红，而不是等到 token 被解开才发现。
        assertThat(claims).containsOnlyKeys("sub", "username", "iss", "iat", "exp");
    }

    @Test
    @DisplayName("有效期换算成秒：720 分钟 -> 43200 秒")
    void expiresInSeconds() {
        assertThat(service(SECRET_32, 720, ISSUER).getExpiresInSeconds()).isEqualTo(43_200L);
    }

    @Test
    @DisplayName("★ 换个密钥签的 token -> 校验失败（SignatureException）")
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtService attacker = service("ffffffffffffffffffffffffffffffff", 720, ISSUER);
        String forged = attacker.generateToken(1L, "admin");

        assertThatThrownBy(() -> defaultService().parse(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("★ 签发方不匹配 -> 校验失败（requireIssuer 生效）")
    void wrongIssuerIsRejected() {
        String token = service(SECRET_32, 720, "some-other-app").generateToken(1L, "darling");

        assertThatThrownBy(() -> defaultService().parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("★ 已过期的 token -> 校验失败（ExpiredJwtException）")
    void expiredTokenIsRejected() {
        // 用负数有效期签一个「出生就过期」的 token
        String expired = service(SECRET_32, -1, ISSUER).generateToken(1L, "darling");

        assertThatThrownBy(() -> defaultService().parse(expired))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("★ 篡改 payload -> 校验失败（签名对不上）")
    void tamperedTokenIsRejected() {
        JwtService jwt = defaultService();
        String token = jwt.generateToken(1L, "darling");

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        // 把 payload 换成一个「userId=999」的自己人 token 的 payload，签名保持不变
        String victimPayload = token.split("\\.")[1];
        String attackerPayload = jwt.generateToken(999L, "attacker").split("\\.")[1];
        String tampered = parts[0] + "." + attackerPayload + "." + parts[2];

        assertThat(attackerPayload).isNotEqualTo(victimPayload);
        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("★ 畸形字符串 -> 抛 JwtException；空串 / null -> IllegalArgumentException")
    void malformedTokenIsRejected() {
        JwtService jwt = defaultService();

        assertThatThrownBy(() -> jwt.parse("not-a-jwt"))
                .isInstanceOf(JwtException.class);

        // 空串和 null 走的是 jjwt 自己的参数校验分支，抛的是 IllegalArgumentException
        // 而不是 JwtException。**断言具体的类型是有意的**：写测试时如果只写
        // 「会抛异常」，就区分不出「被拒绝了」和「代码里有别的 bug」。
        // 关键是无论哪种，都不会被当成合法 token 放行。
        assertThatThrownBy(() -> jwt.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
