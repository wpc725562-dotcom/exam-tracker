package com.wpc725562.examtracker.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CORS 配置，绑定 {@code app.cors.*}。
 *
 * <p>Spring Boot 会把 {@code app.cors.allowed-origins} 这个逗号分隔的字符串
 * 自动拆成 {@code List<String>}，不需要自己写解析。
 *
 * @param allowedOrigins 允许的前端来源。**不要用 {@code "*"}** ——
 *                       一旦将来要带 Cookie，通配符会被浏览器直接拒绝，而且它等于
 *                       允许任何网站拿用户的浏览器去调你的接口。
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
