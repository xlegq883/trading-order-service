package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.api.dto.CreateOrderRequest;
import com.fuzuyang.trading.api.dto.CreateOrderResponse;
import com.fuzuyang.trading.common.ErrorCode;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单落单事务单元。
 *
 * <p>只负责「插入订单 + DB 扣减库存 + 状态流转」这一本地事务；
 * 订单号由编排层生成并传入（便于订单号碰撞时换号重试），幂等预检与 Redis 预扣由
 * {@link OrderApplicationService} 编排，避免 Redis 操作进入事务。</p>
 *
 * <p>状态流转：INIT → STOCK_LOCKED → CREATED。</p>
 */
@Service
public class OrderCreationService {

    private final OrderMapper orderMapper;
    private final StockMapper stockMapper;
    private final OutboxService outboxService;

    public OrderCreationService(OrderMapper orderMapper, StockMapper stockMapper, OutboxService outboxService) {
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
        this.outboxService = outboxService;
    }

    /**
     * 落单：插入 INIT，DB 条件扣减库存，再流转到 STOCK_LOCKED → CREATED。
     *
     * @param orderNo 由编排层生成并传入的订单号（订单号碰撞时由编排层换号重试）
     * @throws BusinessException 库存不足（{@link ErrorCode#STOCK_INSUFFICIENT}），事务回滚
     */
    @Transactional
    public CreateOrderResponse create(CreateOrderRequest request, String idempotencyKey, String orderNo) {
        LocalDateTime now = LocalDateTime.now();
        OrderDO order = new OrderDO();
        order.setOrderNo(orderNo);
        order.setUserId(request.getUserId());
        order.setProductId(request.getProductId());
        order.setQuantity(request.getQuantity());
        // TODO D10：接入商品价格后按 quantity * unitPrice 计算；当前无价格模型，先占位 0.00。
        order.setAmount(BigDecimal.ZERO);
        order.setStatus(OrderStatus.INIT.getCode());
        order.setIdempotentKey(idempotencyKey);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);

        int deducted = stockMapper.deduct(order.getProductId(), order.getQuantity());
        if (deducted == 0) {
            throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT,
                    "库存不足: productId=" + order.getProductId());
        }
        orderMapper.updateStatus(order.getOrderNo(), OrderStatus.STOCK_LOCKED.getCode());
        orderMapper.updateStatus(order.getOrderNo(), OrderStatus.CREATED.getCode());

        // 与订单、扣库存在同一本地事务内写 Outbox，保证「订单落库 ⇔ 消息待投递」
        outboxService.recordOrderCreated(order, OrderStatus.CREATED);

        return new CreateOrderResponse(order.getOrderNo(), OrderStatus.CREATED.name());
    }
}
