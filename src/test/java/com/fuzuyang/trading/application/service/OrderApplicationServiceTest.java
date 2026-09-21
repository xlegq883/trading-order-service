package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.api.dto.CreateOrderRequest;
import com.fuzuyang.trading.api.dto.CreateOrderResponse;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 下单用例单元测试：用 Mockito 验证「先插入 INIT，再流转到 CREATED」的编排逻辑。
 */
@ExtendWith(MockitoExtension.class)
class OrderApplicationServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderApplicationService orderApplicationService;

    @Test
    void shouldInsertAsInitThenUpdateToCreated() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId("U1");
        request.setProductId("P1001");
        request.setQuantity(2);
        request.setIdempotentKey("idem-001");

        CreateOrderResponse response = orderApplicationService.createOrder(request);

        ArgumentCaptor<OrderDO> captor = ArgumentCaptor.forClass(OrderDO.class);
        verify(orderMapper).insert(captor.capture());
        OrderDO inserted = captor.getValue();

        assertThat(inserted.getOrderNo()).isNotBlank();
        assertThat(inserted.getUserId()).isEqualTo("U1");
        assertThat(inserted.getProductId()).isEqualTo("P1001");
        assertThat(inserted.getQuantity()).isEqualTo(2);
        assertThat(inserted.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(inserted.getStatus()).isEqualTo(OrderStatus.INIT.getCode());
        assertThat(inserted.getIdempotentKey()).isEqualTo("idem-001");

        verify(orderMapper).updateStatus(inserted.getOrderNo(), OrderStatus.CREATED.getCode());

        assertThat(response.getOrderNo()).isEqualTo(inserted.getOrderNo());
        assertThat(response.getStatus()).isEqualTo(OrderStatus.CREATED.name());
    }
}
