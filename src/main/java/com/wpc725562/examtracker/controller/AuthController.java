package com.wpc725562.examtracker.controller;

import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.dto.AuthDtos;
import com.wpc725562.examtracker.security.UserPrincipal;
import com.wpc725562.examtracker.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册 / 登录 / 个人资料。
 *
 * <p>注意这里的路径是 {@code /auth/...} 而**不是** {@code /api/auth/...} ——
 * {@code /api} 是 {@code server.servlet.context-path}，会自动加上。
 * 在 Controller 里再写一遍就变成 {@code /api/api/auth}。
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "01. 认证", description = "注册、登录、当前用户信息")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements   // 覆盖全局的「需要认证」，这个接口是公开的
    @Operation(summary = "注册", description = "用户名全局唯一；密码用 BCrypt 加盐存储。注册成功后可直接用同一套凭据登录。")
    public ApiResponse<AuthDtos.UserInfo> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "登录",
            description = "成功返回 JWT。之后所有请求都要带 `Authorization: Bearer <token>`。"
                    + "用户名不存在与密码错误返回**完全相同**的提示，避免账号被枚举。")
    public ApiResponse<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "当前登录用户信息", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AuthDtos.UserInfo> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(authService.getProfile(principal.getUserId()));
    }

    /**
     * 修改个人资料。
     *
     * <p>用 {@code PATCH} 但语义是**全量替换**：请求里出现的字段就写进去，
     * 没出现的字段会被置空。
     *
     * <p>之所以不做「只更新非空字段」：JSON 无法区分「字段没传」和「字段传了 null」，
     * 硬做只能靠 {@code JsonNullable} 之类的包装，把接口复杂度抬高一档，
     * 而收益只是省一次提交。全量替换的规则一句话能说清，前端也不会猜错。
     */
    @PatchMapping("/me")
    @Operation(summary = "修改个人资料（全量替换）", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AuthDtos.UserInfo> updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                        @Valid @RequestBody AuthDtos.UpdateProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(principal.getUserId(), request));
    }
}
