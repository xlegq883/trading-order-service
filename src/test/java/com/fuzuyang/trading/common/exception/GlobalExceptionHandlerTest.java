package com.fuzuyang.trading.common.exception;

import com.fuzuyang.trading.common.ApiResponse;
import com.fuzuyang.trading.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器单元测试：业务异常 HTTP 码映射、不可读请求体、兜底异常。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldMapBusinessExceptionToHttpStatus() {
        ResponseEntity<ApiResponse<Void>> notFound =
                handler.handleBusiness(new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody()).isNotNull();
        assertThat(notFound.getBody().getCode()).isEqualTo(404);

        ResponseEntity<ApiResponse<Void>> insufficient =
                handler.handleBusiness(new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "库存不足"));
        assertThat(insufficient.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(insufficient.getBody()).isNotNull();
        assertThat(insufficient.getBody().getCode()).isEqualTo(422);
    }

    @Test
    void shouldReturn400WhenBodyNotReadable() {
        ApiResponse<Void> response = handler.handleNotReadable(new HttpMessageNotReadableException("bad json"));

        assertThat(response.getCode()).isEqualTo(400);
    }

    @Test
    void shouldReturn500OnUnexpectedException() {
        ApiResponse<Void> response = handler.handleException(new RuntimeException("boom"));

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).isEqualTo(ErrorCode.SERVER_ERROR.getMessage());
    }
}
