package com.wpc725562.examtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 认证相关的 DTO。
 *
 * <p>用 record 而不是 class + getter/setter：请求对象是**一次性的不可变数据**，
 * 从 JSON 反序列化出来之后就不该再被修改。record 天然满足这一点，
 * 而且省掉了 Lombok 和几十行样板。
 *
 * <p>校验注解写在 record 的组件上，Spring Boot 3 会自动把它应用到对应的字段。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(description = "注册请求")
    public record RegisterRequest(

            @Schema(description = "用户名，3-50 位字母/数字/下划线", example = "darling")
            @NotBlank(message = "用户名不能为空")
            @Pattern(regexp = "^[A-Za-z0-9_]{3,50}$",
                    message = "用户名只能是 3-50 位的字母、数字或下划线")
            String username,

            @Schema(description = "密码，6-64 位", example = "study2026")
            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
            String password,

            @Schema(description = "昵称，不填则默认与用户名相同", example = "备考中的我")
            @Size(max = 50, message = "昵称最长 50 个字符")
            String nickname,

            @Schema(description = "考试日期，用于计算倒计时", example = "2027-03-14")
            LocalDate examDate
    ) {
    }

    @Schema(description = "登录请求")
    public record LoginRequest(

            @Schema(description = "用户名", example = "darling")
            @NotBlank(message = "用户名不能为空")
            String username,

            @Schema(description = "密码", example = "study2026")
            @NotBlank(message = "密码不能为空")
            String password
    ) {
    }

    @Schema(description = "登录成功返回的凭证")
    public record TokenResponse(

            @Schema(description = "JWT，后续请求放在 Authorization: Bearer <token>") String token,

            @Schema(description = "固定为 Bearer", example = "Bearer") String tokenType,

            @Schema(description = "有效期（秒）", example = "43200") long expiresIn,

            @Schema(description = "当前登录用户信息") UserInfo user
    ) {
    }

    @Schema(description = "当前用户信息")
    public record UserInfo(

            @Schema(description = "用户 id", example = "1") Long id,

            @Schema(description = "用户名", example = "darling") String username,

            @Schema(description = "昵称", example = "备考中的我") String nickname,

            @Schema(description = "考试日期", example = "2027-03-14") LocalDate examDate,

            /**
             * 距考试还有多少天。null 表示用户没填考试日期。
             * 负数表示考试已经过去了 —— 保留负数而不是归零，前端才能区分
             * 「还有 0 天（就是今天）」和「已经考完了」。
             */
            @Schema(description = "距考试天数，未设置考试日期时为 null", example = "167") Long daysUntilExam
    ) {
    }

    @Schema(description = "修改个人资料请求")
    public record UpdateProfileRequest(

            @Schema(description = "昵称", example = "备考中的我")
            @Size(max = 50, message = "昵称最长 50 个字符")
            String nickname,

            @Schema(description = "考试日期", example = "2027-03-14")
            LocalDate examDate
    ) {
    }
}
