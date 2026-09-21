package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.api.dto.HandleReceiptRequest;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReceiptDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReconcileDiffDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReceiptMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReconcileDiffMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回执处理单元测试：首次落库、幂等跳过、订单不存在、迟到回执。
 */
@ExtendWith(MockitoExtension.class)
class ReceiptApplicationServiceTest {

    @Mock
    private ReceiptMapper receiptMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ReconcileDiffMapper reconcileDiffMapper;

    @InjectMocks
    private ReceiptApplicationService receiptApplicationService;

    @Test
    void shouldInsertReceiptAndMarkReported() {
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(null);
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(order("ON1", OrderStatus.CREATED));

        receiptApplicationService.handleReceipt(request("ON1", "UP1"), "{}");

        ArgumentCaptor<ReceiptDO> captor = ArgumentCaptor.forClass(ReceiptDO.class);
        verify(receiptMapper).insert(captor.capture());
        ReceiptDO receipt = captor.getValue();
        assertThat(receipt.getOrderNo()).isEqualTo("ON1");
        assertThat(receipt.getUpstreamNo()).isEqualTo("UP1");
        assertThat(receipt.getStatus()).isEqualTo(1);
        assertThat(receipt.getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(receipt.getPayload()).isEqualTo("{}");
        verify(orderMapper).updateStatus("ON1", OrderStatus.REPORTED.getCode());
        verify(reconcileDiffMapper, never()).insert(any());
    }

    @Test
    void shouldSkipWhenReceiptExists() {
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(new ReceiptDO());

        receiptApplicationService.handleReceipt(request("ON1", "UP1"), null);

        verify(receiptMapper, never()).insert(any());
        verify(orderMapper, never()).updateStatus(anyString(), anyInt());
    }

    @Test
    void shouldThrowWhenOrderNotFound() {
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(null);
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(null);

        assertThatThrownBy(() -> receiptApplicationService.handleReceipt(request("ON1", "UP1"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单不存在");
    }

    @Test
    void shouldRecordLateReceiptWithoutRevertingStatus() {
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(null);
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(order("ON1", OrderStatus.FAILED));

        receiptApplicationService.handleReceipt(request("ON1", "UP1"), null);

        verify(receiptMapper).insert(any());
        verify(orderMapper, never()).updateStatus(anyString(), anyInt());

        ArgumentCaptor<ReconcileDiffDO> captor = ArgumentCaptor.forClass(ReconcileDiffDO.class);
        verify(reconcileDiffMapper).insert(captor.capture());
        assertThat(captor.getValue().getDiffType()).isEqualTo("LATE_RECEIPT");
    }

    private HandleReceiptRequest request(String orderNo, String upstreamNo) {
        HandleReceiptRequest request = new HandleReceiptRequest();
        request.setOrderNo(orderNo);
        request.setUpstreamNo(upstreamNo);
        request.setStatus(1);
        request.setAmount(new BigDecimal("10.00"));
        return request;
    }

    private OrderDO order(String orderNo, OrderStatus status) {
        OrderDO order = new OrderDO();
        order.setOrderNo(orderNo);
        order.setStatus(status.getCode());
        return order;
    }
}
