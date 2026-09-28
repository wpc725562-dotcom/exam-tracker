package com.wpc725562.examtracker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未登录时返回 401 —— **JSON 格式的 401**。
 *
 * <p>为什么必须自己实现：Spring Security 默认走的是重定向到登录页
 * （`/login`），对浏览器是对的，对一个纯 REST API 就是灾难 ——
 * 前端拿到的是 302 和一段 HTML，根本没法解析。
 *
 * <p>提示语优先用 {@link JwtAuthenticationFilter} 留下的具体原因
 * （「登录已过期」/「凭证无效」），没有就退回通用文案。
 */
@Slf4j
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        Object detail = request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR);
        String message = detail instanceof String s && !s.isBlank()
                ? s
                : ErrorCode.UNAUTHORIZED.defaultMessage();

        log.debug("未认证请求被拒绝 {} {} -> {}", request.getMethod(), request.getRequestURI(), message);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error(ErrorCode.UNAUTHORIZED, message));
    }
}
