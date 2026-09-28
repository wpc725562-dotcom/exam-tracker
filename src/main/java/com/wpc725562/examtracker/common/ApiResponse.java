package com.wpc725562.examtracker.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一响应体。
 *
 * <p>全站所有接口都返回这个结构，包括错误。好处是前端只需要写一处解包逻辑；
 * 坏处是 HTTP 状态码在业务失败时不再是 200 —— 这一点是刻意的：
 * **HTTP 状态码也要正确**，否则监控、网关重试策略、浏览器缓存全都会失准。
 * 常见的「永远返回 200 靠 code 区分」的做法会掩盖真实故障。
 *
 * @param code    业务码，0 表示成功
 * @param message 给**人**看的说明，可以直接展示
 * @param data    业务数据，失败时为 null
 */
@Schema(description = "统一响应体")
public record ApiResponse<T>(
        @Schema(description = "业务码，0 = 成功", example = "0") int code,
        @Schema(description = "说明信息", example = "成功") String message,
        @Schema(description = "业务数据，失败时为 null") T data
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(),
                message == null ? errorCode.defaultMessage() : message, null);
    }
}
