package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.api.dto.CreateOrderRequest;
import com.fuzuyang.trading.api.dto.CreateOrderResponse;
import com.fuzuyang.trading.common.util.OrderNoGenerator;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 下单用例编排。
 *
 * <p>事务边界放在应用层：一次下单包含「插入订单 + 状态流转」多步写操作，必须同一事务；
 * domain 保持不依赖 Spring，mapper 只表达单条 SQL，都无法承载业务事务。</p>
 *
 * <p>D3 范围：参数校验后落单，状态 INIT → CREATED。幂等、库存、Outbox 属于后续任务。</p>
 */
@Service
public class OrderApplicationService {

    private final OrderMapper orderMapper;

    public OrderApplicationService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 创建订单：落库状态 INIT，随后流转到 CREATED。
     *
     * @param request 已通过校验的下单请求
     * @return 订单号与最终状态
     */
    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        LocalDateTime now = LocalDateTime.now();

        OrderDO order = new OrderDO();
        order.setOrderNo(OrderNoGenerator.generate());
        order.setUserId(request.getUserId());
        order.setProductId(request.getProductId());
        order.setQuantity(request.getQuantity());
        // TODO D10：接入商品价格后按 quantity * unitPrice 计算；D3 无价格模型，先占位 0.00。
        order.setAmount(BigDecimal.ZERO);
        order.setStatus(OrderStatus.INIT.getCode());
        order.setIdempotentKey(request.getIdempotentKey());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        orderMapper.insert(order);
        orderMapper.updateStatus(order.getOrderNo(), OrderStatus.CREATED.getCode());

        return new CreateOrderResponse(order.getOrderNo(), OrderStatus.CREATED.name());
    }
}
