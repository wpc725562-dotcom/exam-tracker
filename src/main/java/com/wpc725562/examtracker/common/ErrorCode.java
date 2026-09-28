package com.wpc725562.examtracker.common;

/**
 * 业务错误码。
 *
 * <p>HTTP 状态码表达的是「这次请求在传输层怎么样了」，业务错误码表达的是
 * 「业务规则为什么没通过」—— 两者不是一回事。比如「用户名已存在」既是
 * HTTP 409，也需要一个稳定的业务码给前端做分支判断。
 *
 * <p>编码规则：{@code 0} 成功；{@code 4xxxx} 客户端问题；{@code 5xxxx} 服务端问题。
 * 前三位跟 HTTP 状态码对齐，便于人肉对照。
 */
public enum ErrorCode {

    /** 成功 */
    OK(0, "成功"),

    /** 请求参数不合法（字段缺失、格式错误、越界） */
    INVALID_PARAM(40000, "请求参数不合法"),

    /** 未登录 / token 无效或过期 */
    UNAUTHORIZED(40100, "未登录或登录已过期"),

    /** 已登录但没有权限操作该资源 */
    FORBIDDEN(40300, "没有权限执行该操作"),

    /** 资源不存在 */
    NOT_FOUND(40400, "资源不存在"),

    /** 与现有数据冲突（用户名重复、科目重名等） */
    CONFLICT(40900, "资源冲突"),

    /**
     * 依赖的外部服务暂时不可用（AI 模型超时 / 配额耗尽 / 网络不通）。
     *
     * <p>和 {@link #INTERNAL} 分开是有意义的：500 意味着「我们自己的代码出问题了」，
     * 503 意味着「我们没问题，是对面暂时不行，稍后重试可能就好了」。
     * 混在一起会让告警和排查方向都失准。
     */
    UNAVAILABLE(50300, "依赖的服务暂时不可用"),

    /** 服务端未预期的问题 */
    INTERNAL(50000, "服务器内部错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * 对应的 HTTP 状态码。取业务码的前三位 —— 这也是把编码规则定成
     * 「前三位对齐 HTTP」的原因：这里不需要再维护一张映射表。
     */
    public int httpStatus() {
        if (this == OK) {
            return 200;
        }
        return code / 100;
    }
}
