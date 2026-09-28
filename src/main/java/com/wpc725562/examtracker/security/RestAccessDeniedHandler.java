package com.wpc725562.examtracker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 已登录但权限不够时返回 403（同样是 JSON，而不是 Spring Security 默认的 HTML 错误页）。
 *
 * <p>和 401 的区别要分清：**401 是「我不知道你是谁」，403 是「我知道你是谁，但你不能做这个」。**
 * 前端对两者的处理完全不同 —— 401 要跳登录页，403 应该只提示「没有权限」。
 */
@Slf4j
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        log.warn("拒绝访问 {} {}: {}", request.getMethod(), request.getRequestURI(),
                accessDeniedException.getMessage());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage()));
    }
}
