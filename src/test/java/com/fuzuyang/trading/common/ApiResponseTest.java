package com.fuzuyang.trading.common;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应结构单元测试。
 */
class ApiResponseTest {

    @Test
    void okShouldWrapDataWithCodeZero() {
        ApiResponse<Map<String, Object>> response = ApiResponse.ok(Map.of("status", "UP"));

        assertThat(response.getCode()).isZero();
        assertThat(response.getMessage()).isEqualTo("ok");
        assertThat(response.getData()).containsEntry("status", "UP");
    }

    @Test
    void errorShouldCarryCodeAndMessage() {
        ApiResponse<Void> response = ApiResponse.error(400, "bad request");

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).isEqualTo("bad request");
        assertThat(response.getData()).isNull();
    }
}
