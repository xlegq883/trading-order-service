package com.fuzuyang.trading.common.exception;

import com.fuzuyang.trading.common.ErrorCode;

/**
 * 业务异常：携带业务错误码，由 {@link GlobalExceptionHandler} 统一转换为 HTTP 响应。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }
}
