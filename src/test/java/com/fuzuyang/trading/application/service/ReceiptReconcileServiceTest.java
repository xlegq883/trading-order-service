package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReceiptDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReconcileDiffDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReceiptMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReconcileDiffMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 回执对账单元测试：一致确认、金额不符、上游失败、非 REPORTED/无回执跳过。
 */
@ExtendWith(MockitoExtension.class)
class ReceiptReconcileServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ReceiptMapper receiptMapper;

    @Mock
    private ReconcileDiffMapper reconcileDiffMapper;

    @Mock
    private StockMapper stockMapper;

    @Mock
    private StockService stockService;

    @InjectMocks
    private ReceiptReconcileService receiptReconcileService;

    @Test
    void shouldConfirmWhenMatched() {
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(order("ON1", OrderStatus.REPORTED, "10.00"));
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(receipt("ON1", 1, "10.00"));

        receiptReconcileService.reconcile("ON1");

        verify(orderMapper).updateStatus("ON1", OrderStatus.CONFIRMED.getCode());
        verify(reconcileDiffMapper, never()).insert(any());
        verify(stockMapper, never()).restore(anyString(), anyInt());
        verifyNoInteractions(stockService);
    }

    @Test
    void shouldFailAndRecordDiffOnAmountMismatch() {
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(order("ON1", OrderStatus.REPORTED, "10.00"));
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(receipt("ON1", 1, "99.00"));

        receiptReconcileService.reconcile("ON1");

        verify(orderMapper).updateStatus("ON1", OrderStatus.FAILED.getCode());
        verify(stockMapper).restore("P1", 2);
        verify(stockService).release("P1", 2);
        assertThat(capturedDiffType()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void shouldFailAndRecordDiffOnUpstreamFailure() {
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(order("ON1", OrderStatus.REPORTED, "10.00"));
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(receipt("ON1", 0, "10.00"));

        receiptReconcileService.reconcile("ON1");

        verify(orderMapper).updateStatus("ON1", OrderStatus.FAILED.getCode());
        verify(stockMapper).restore("P1", 2);
        verify(stockService).release("P1", 2);
        assertThat(capturedDiffType()).isEqualTo("UPSTREAM_FAILED");
    }

    @Test
    void shouldSkipWhenNotReported() {
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(order("ON1", OrderStatus.CREATED, "10.00"));

        receiptReconcileService.reconcile("ON1");

        verifyNoInteractions(receiptMapper, reconcileDiffMapper);
        verify(orderMapper, never()).updateStatus(anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenNoReceipt() {
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(order("ON1", OrderStatus.REPORTED, "10.00"));
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(null);

        receiptReconcileService.reconcile("ON1");

        verify(orderMapper, never()).updateStatus(anyString(), anyInt());
        verifyNoInteractions(reconcileDiffMapper);
    }

    private String capturedDiffType() {
        ArgumentCaptor<ReconcileDiffDO> captor = ArgumentCaptor.forClass(ReconcileDiffDO.class);
        verify(reconcileDiffMapper).insert(captor.capture());
        return captor.getValue().getDiffType();
    }

    private OrderDO order(String orderNo, OrderStatus status, String amount) {
        OrderDO order = new OrderDO();
        order.setOrderNo(orderNo);
        order.setStatus(status.getCode());
        order.setAmount(new BigDecimal(amount));
        order.setProductId("P1");
        order.setQuantity(2);
        return order;
    }

    private ReceiptDO receipt(String orderNo, int status, String amount) {
        ReceiptDO receipt = new ReceiptDO();
        receipt.setOrderNo(orderNo);
        receipt.setStatus(status);
        receipt.setAmount(new BigDecimal(amount));
        return receipt;
    }
}
