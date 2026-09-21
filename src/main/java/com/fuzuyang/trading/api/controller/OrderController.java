package com.fuzuyang.trading.api.controller;

import com.fuzuyang.trading.api.dto.CreateOrderRequest;
import com.fuzuyang.trading.api.dto.CreateOrderResponse;
import com.fuzuyang.trading.application.service.OrderApplicationService;
import com.fuzuyang.trading.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
     * 创建订单。
     *
     * <p>D3 尚未实现幂等：相同参数但不同 idempotentKey 的重复调用会产生多单。</p>
     */
    @PostMapping("/orders")
    public ApiResponse<CreateOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderApplicationService.createOrder(request));
    }
}
