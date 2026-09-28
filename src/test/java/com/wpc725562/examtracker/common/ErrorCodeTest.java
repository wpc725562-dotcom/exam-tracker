package com.wpc725562.examtracker.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务错误码与 HTTP 状态码的对应关系。
 *
 * <p>编码规则是「前三位对齐 HTTP」，{@link ErrorCode#httpStatus()} 直接靠
 * {@code code / 100} 推导 —— 好处是不用维护第二张映射表，代价是
 * **一旦有人把编码写错，就会静默地返回错误的 HTTP 状态**。
 * 所以这里逐个钉死。
 */
@DisplayName("ErrorCode —— 业务码 / HTTP 状态码")
class ErrorCodeTest {

    @Test
    @DisplayName("成功码是 0，对应 HTTP 200")
    void ok() {
        assertThat(ErrorCode.OK.code()).isZero();
        assertThat(ErrorCode.OK.httpStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("各业务码对应的 HTTP 状态码")
    void httpStatusMapping() {
        assertThat(ErrorCode.INVALID_PARAM.httpStatus()).isEqualTo(400);
        assertThat(ErrorCode.UNAUTHORIZED.httpStatus()).isEqualTo(401);
        assertThat(ErrorCode.FORBIDDEN.httpStatus()).isEqualTo(403);
        assertThat(ErrorCode.NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCode.CONFLICT.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.INTERNAL.httpStatus()).isEqualTo(500);
    }

    @Test
    @DisplayName("★ 编码不变式：非 0 的业务码，前三位必须等于它的 HTTP 状态码")
    void codePrefixAlwaysMatchesHttpStatus() {
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec == ErrorCode.OK) {
                continue;
            }
            assertThat(ec.httpStatus())
                    .as("错误码 %s（%d）的前三位应与 HTTP 状态码一致", ec.name(), ec.code())
                    .isEqualTo(ec.code() / 100);
            assertThat(ec.httpStatus())
                    .as("%s 应该落在 4xx 或 5xx 区间", ec.name())
                    .isBetween(400, 599);
        }
    }

    @Test
    @DisplayName("每个错误码都有非空的中文默认说明")
    void everyCodeHasDefaultMessage() {
        for (ErrorCode ec : ErrorCode.values()) {
            assertThat(ec.defaultMessage()).as("%s 的默认说明", ec.name()).isNotBlank();
        }
    }
}
