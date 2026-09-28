package com.wpc725562.examtracker.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 {@code Authorization: Bearer <token>} 里取出身份，放进 {@code SecurityContext}。
 *
 * <p>继承 {@code OncePerRequestFilter}：一个请求只跑一次。用普通 {@code Filter} 的话，
 * 在 forward / include 的场景下会被重复执行 —— 对认证来说就是白验几次签名。
 *
 * <p><b>关键设计：token 无效时不在这里抛异常。</b>
 * 本过滤器对所有请求都生效（包括公开接口），如果这里直接拒绝，
 * 那么「带着一个过期 token 去访问登录接口」也会被拦掉 —— 那是错的，
 * 用户恰恰需要访问登录接口来换新 token。
 * 所以这里只负责「能验出来就设置身份」，验不出来就**什么都不做**，
 * 由后面的授权环节根据接口是否需要登录来决定放不放行。
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性名：把失败原因暂存给 {@link RestAuthenticationEntryPoint} 用，好给出更准确的提示。 */
    static final String ATTR_AUTH_ERROR = "exam-tracker.authError";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();

        try {
            Claims claims = jwtService.parse(token);
            UserPrincipal principal = new UserPrincipal(
                    jwtService.extractUserId(claims),
                    jwtService.extractUsername(claims));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException ex) {
            // 过期是最常见的一种，单独给一句人话，前端可以直接据此跳登录页
            log.debug("token 已过期: {}", ex.getMessage());
            request.setAttribute(ATTR_AUTH_ERROR, "登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("token 无效: {}", ex.getMessage());
            request.setAttribute(ATTR_AUTH_ERROR, "登录凭证无效，请重新登录");
        }

        filterChain.doFilter(request, response);
    }
}
