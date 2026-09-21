package com.fuzuyang.trading.common;

import lombok.Data;

/**
 * 统一响应结构：{"code":0,"message":"ok","data":{...}}。
 *
 * @param <T> 业务数据类型
 */
@Data
public class ApiResponse<T> {

    /** 业务码，0 表示成功。 */
    private int code;

    /** 提示信息。 */
    private String message;

    /** 业务数据。 */
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
