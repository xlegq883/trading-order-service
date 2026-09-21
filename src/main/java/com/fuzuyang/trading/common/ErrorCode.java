package com.fuzuyang.trading.common;

/**
 * 统一错误码。
 *
 * <p>0 表示成功；其余为业务错误码，与 HTTP 状态码语义保持一致。</p>
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAM_INVALID(400, "参数校验失败"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    SERVER_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
