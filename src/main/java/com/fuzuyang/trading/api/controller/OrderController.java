package com.fuzuyang.trading.api.controller;

import com.fuzuyang.trading.api.dto.CreateOrderRequest;
import com.fuzuyang.trading.api.dto.CreateOrderResponse;
import com.fuzuyang.trading.application.service.OrderApplicationService;
import com.fuzuyang.trading.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口。
 *
 * <p>只负责协议适配与参数校验，业务编排交给应用层。</p>
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    /**
     * 创建订单（幂等）。
     *
     * <p>幂等键通过请求头 {@code x-idempotency-key} 传入；缺失时由全局异常处理器返回 400。</p>
     */
    @PostMapping("/orders")
    public ApiResponse<CreateOrderResponse> create(
            @RequestHeader("x-idempotency-key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderApplicationService.createOrder(request, idempotencyKey));
    }
}
