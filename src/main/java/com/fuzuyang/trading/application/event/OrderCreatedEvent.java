package com.fuzuyang.trading.application.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单创建事件，作为 Outbox 消息体投递到 Kafka。
 */
public record OrderCreatedEvent(
        String orderNo,
        String userId,
        String productId,
        Integer quantity,
        BigDecimal amount,
        Integer status,
        LocalDateTime occurredAt) {
}
