package com.fuzuyang.trading.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.trading.api.dto.HandleReceiptRequest;
import com.fuzuyang.trading.application.event.OrderCreatedEvent;
import com.fuzuyang.trading.application.service.ReceiptApplicationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单事件消费者（模拟上游）。
 *
 * <p>消费 {@code trading.order.events}，模拟上游处理后回执。
 * 消费幂等由 {@link ReceiptApplicationService} 以 {@code orderNo} 保证；
 * 处理失败抛异常，交给 Spring Kafka 默认错误处理器重试（配合幂等最终一致）。</p>
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private static final DateTimeFormatter UPSTREAM_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ObjectMapper objectMapper;
    private final ReceiptApplicationService receiptApplicationService;

    public OrderEventConsumer(ObjectMapper objectMapper, ReceiptApplicationService receiptApplicationService) {
        this.objectMapper = objectMapper;
        this.receiptApplicationService = receiptApplicationService;
    }

    @KafkaListener(
            topics = "${app.outbox.topic:trading.order.events}",
            groupId = "${spring.kafka.consumer.group-id:trading-order-service}")
    public void onMessage(ConsumerRecord<String, String> record) {
        OrderCreatedEvent event = parse(record.value());
        String upstreamNo = generateUpstreamNo();
        log.info("模拟上游消费订单事件：orderNo={}, upstreamNo={}", event.orderNo(), upstreamNo);

        HandleReceiptRequest request = new HandleReceiptRequest();
        request.setOrderNo(event.orderNo());
        request.setUpstreamNo(upstreamNo);
        request.setStatus(1);
        request.setAmount(event.amount());
        receiptApplicationService.handleReceipt(request, record.value());
    }

    private OrderCreatedEvent parse(String value) {
        try {
            return objectMapper.readValue(value, OrderCreatedEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("解析订单事件失败: " + value, ex);
        }
    }

    private String generateUpstreamNo() {
        return "UP" + LocalDateTime.now().format(UPSTREAM_NO_FORMATTER)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
