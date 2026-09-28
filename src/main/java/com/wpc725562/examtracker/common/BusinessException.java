package com.wpc725562.examtracker.common;

/**
 * 业务异常。
 *
 * <p>抛出它的地方只关心「哪条业务规则没通过」，不关心 HTTP 状态码、
 * 也不关心返回体长什么样 —— 那些由 {@link GlobalExceptionHandler} 统一决定。
 * 这样 Service 层不会掺进任何 Web 概念，也才好写单元测试。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    // ---- 常用快捷构造，让调用处读起来像句子 ----

    public static BusinessException notFound(String what) {
        return new BusinessException(ErrorCode.NOT_FOUND, what + "不存在");
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    public static BusinessException invalidParam(String message) {
        return new BusinessException(ErrorCode.INVALID_PARAM, message);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(ErrorCode.UNAUTHORIZED, message);
    }
}
