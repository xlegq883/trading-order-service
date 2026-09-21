package com.fuzuyang.trading.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuzuyang.trading.api.dto.HandleReceiptRequest;
import com.fuzuyang.trading.application.event.OrderCreatedEvent;
import com.fuzuyang.trading.application.service.ReceiptApplicationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 订单事件消费者单元测试：解析事件并触发回执。
 */
@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private ReceiptApplicationService receiptApplicationService;

    private ObjectMapper objectMapper;
    private OrderEventConsumer orderEventConsumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        orderEventConsumer = new OrderEventConsumer(objectMapper, receiptApplicationService);
    }

    @Test
    void shouldConsumeEventAndHandleReceipt() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "ON1", "U1", "P1001", 1, new BigDecimal("10.00"), 2, LocalDateTime.now());
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("trading.order.events", 0, 0L, "ON1", json);

        orderEventConsumer.onMessage(record);

        ArgumentCaptor<HandleReceiptRequest> captor = ArgumentCaptor.forClass(HandleReceiptRequest.class);
        verify(receiptApplicationService).handleReceipt(captor.capture(), eq(json));
        HandleReceiptRequest request = captor.getValue();
        assertThat(request.getOrderNo()).isEqualTo("ON1");
        assertThat(request.getUpstreamNo()).startsWith("UP");
        assertThat(request.getStatus()).isEqualTo(1);
        assertThat(request.getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
    }
}
