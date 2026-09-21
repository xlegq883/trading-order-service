package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.api.dto.CreateOrderRequest;
import com.fuzuyang.trading.api.dto.CreateOrderResponse;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落单事务单元测试：验证 DB 扣减与状态流转 INIT → STOCK_LOCKED → CREATED。
 */
@ExtendWith(MockitoExtension.class)
class OrderCreationServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private StockMapper stockMapper;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private OrderCreationService orderCreationService;

    @Test
    void shouldInsertDeductAndTransitStatuses() {
        when(stockMapper.deduct("P1001", 2)).thenReturn(1);

        CreateOrderResponse response = orderCreationService.create(request(), "k1");

        ArgumentCaptor<OrderDO> captor = ArgumentCaptor.forClass(OrderDO.class);
        verify(orderMapper).insert(captor.capture());
        OrderDO inserted = captor.getValue();

        assertThat(inserted.getStatus()).isEqualTo(OrderStatus.INIT.getCode());
        assertThat(inserted.getIdempotentKey()).isEqualTo("k1");
        verify(stockMapper).deduct("P1001", 2);
        verify(orderMapper).updateStatus(inserted.getOrderNo(), OrderStatus.STOCK_LOCKED.getCode());
        verify(orderMapper).updateStatus(inserted.getOrderNo(), OrderStatus.CREATED.getCode());
        verify(outboxService).recordOrderCreated(inserted, OrderStatus.CREATED);
        assertThat(response.getStatus()).isEqualTo("CREATED");
    }

    @Test
    void shouldThrowWhenStockInsufficient() {
        when(stockMapper.deduct("P1001", 2)).thenReturn(0);

        assertThatThrownBy(() -> orderCreationService.create(request(), "k1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        verify(orderMapper).insert(any());
        verify(orderMapper, never()).updateStatus(anyString(), any());
        verify(outboxService, never()).recordOrderCreated(any(), any());
    }

    private CreateOrderRequest request() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId("U1");
        request.setProductId("P1001");
        request.setQuantity(2);
        return request;
    }
}
