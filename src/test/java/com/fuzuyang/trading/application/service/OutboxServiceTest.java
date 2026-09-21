package com.fuzuyang.trading.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.domain.enums.OutboxStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.OutboxDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Outbox 写入单元测试。
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxMapper outboxMapper;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(outboxMapper, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void shouldRecordOrderCreatedEvent() {
        OrderDO order = new OrderDO();
        order.setOrderNo("ON1");
        order.setUserId("U1");
        order.setProductId("P1001");
        order.setQuantity(2);
        order.setAmount(new BigDecimal("10.00"));
        order.setStatus(OrderStatus.CREATED.getCode());

        outboxService.recordOrderCreated(order, OrderStatus.CREATED);

        ArgumentCaptor<OutboxDO> captor = ArgumentCaptor.forClass(OutboxDO.class);
        verify(outboxMapper).insert(captor.capture());
        OutboxDO outbox = captor.getValue();

        assertThat(outbox.getAggregateType()).isEqualTo("ORDER");
        assertThat(outbox.getAggregateId()).isEqualTo("ON1");
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING.getCode());
        assertThat(outbox.getRetryCount()).isZero();
        assertThat(outbox.getNextRetryAt()).isNotNull();
        assertThat(outbox.getPayload()).contains("ON1").contains("P1001");
    }
}
