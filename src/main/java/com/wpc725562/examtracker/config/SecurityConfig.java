package com.wpc725562.examtracker.config;

import com.wpc725562.examtracker.security.CorsProperties;
import com.wpc725562.examtracker.security.JwtAuthenticationFilter;
import com.wpc725562.examtracker.security.RestAccessDeniedHandler;
import com.wpc725562.examtracker.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 安全配置。
 *
 * <p><b>路径匹配要注意 context-path。</b>本项目 {@code server.servlet.context-path=/api}，
 * 但 Spring Security 的 {@code requestMatchers} 匹配的是**去掉 context-path 之后**的路径。
 * 所以这里写 {@code "/auth/login"}，而不是 {@code "/api/auth/login"} ——
 * 后者永远匹配不上，表现为「登录接口也要求带 token」，很容易查半天。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 无需登录即可访问的路径。
     *
     * <p>只放开**注册和登录**两个业务接口，以及文档、健康检查。
     * 其余一律 {@code authenticated()} —— 默认拒绝比默认放行安全，
     * 新加接口时忘了配权限的后果是「访问不了」，而不是「谁都能访问」。
     */
    private static final String[] PUBLIC_PATHS = {
            "/auth/register",
            "/auth/login",
            // 存活探针
            "/health",
            // 接口文档
            "/doc.html",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/webjars/**",
            // 健康检查（Docker / 网关探活）
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            // 错误转发页，不放开的话任何 404 都会被转成 401，掩盖真实问题
            "/error"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource) throws Exception {

        http
                // 纯 token 认证、不使用 Cookie 会话，所以 CSRF 攻击面不存在。
                // 注意：一旦将来改成用 Cookie 传 token，这一行必须去掉。
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                // 不创建、不使用 HttpSession —— 每个请求都靠 JWT 自带身份
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 认证/授权失败统一返回 JSON，而不是 Spring Security 默认的 HTML 登录页
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .authorizeHttpRequests(auth -> auth
                        // 预检请求不带 Authorization 头，必须放行，否则跨域直接失败
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())

                // 把 JWT 过滤器插在用户名密码过滤器之前 —— 它负责把 token 变成 SecurityContext 里的身份
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器。
     *
     * <p>BCrypt 而不是 MD5/SHA：它自带随机盐（同一个密码每次哈希结果都不同，
     * 彩虹表失效）并且是**故意慢**的（可调 cost，默认 10 轮），
     * 让离线暴力破解的代价高到不划算。MD5 一秒能算几十亿次。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration config = new CorsConfiguration();

        // 允许的来源来自配置，不用 "*"。带 credentials 时通配符会被浏览器拒绝，
        // 而且它等于允许任意网站用用户的浏览器调这个接口。
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 暴露 Authorization 头，方便前端（或调试工具）读取
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        // 预检结果缓存 1 小时，减少 OPTIONS 请求
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
