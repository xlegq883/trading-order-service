package com.fuzuyang.trading.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.trading.application.event.OrderCreatedEvent;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.domain.enums.OutboxStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.OutboxDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OutboxMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Outbox 写入。
 *
 * <p>本类方法不带 {@code @Transactional}，由调用方（落单事务）决定事务边界，
 * 从而保证 {@code t_order} 与 {@code t_outbox} 在同一本地事务内写入。</p>
 */
@Service
public class OutboxService {

    private static final String AGGREGATE_TYPE_ORDER = "ORDER";

    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    /** 记录订单创建事件为待投递消息。 */
    public void recordOrderCreated(OrderDO order, OrderStatus status) {
        LocalDateTime now = LocalDateTime.now();
        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getOrderNo(),
                order.getUserId(),
                order.getProductId(),
                order.getQuantity(),
                order.getAmount(),
                status.getCode(),
                now);

        OutboxDO outbox = new OutboxDO();
        outbox.setAggregateType(AGGREGATE_TYPE_ORDER);
        outbox.setAggregateId(order.getOrderNo());
        outbox.setPayload(toJson(event));
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
    }

    private String toJson(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化订单事件失败: orderNo=" + event.orderNo(), ex);
        }
    }
}
