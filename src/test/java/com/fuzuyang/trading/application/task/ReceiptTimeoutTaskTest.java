package com.fuzuyang.trading.application.task;

import com.fuzuyang.trading.application.service.StockService;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReceiptDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReconcileDiffDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReceiptMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReconcileDiffMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 超时无回执任务单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ReceiptTimeoutTaskTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ReceiptMapper receiptMapper;

    @Mock
    private StockMapper stockMapper;

    @Mock
    private StockService stockService;

    @Mock
    private ReconcileDiffMapper reconcileDiffMapper;

    @InjectMocks
    private ReceiptTimeoutTask receiptTimeoutTask;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(receiptTimeoutTask, "timeout", Duration.ofMinutes(15));
        ReflectionTestUtils.setField(receiptTimeoutTask, "scanLimit", 100);
    }

    @Test
    void shouldFailRestoreStockAndRecordDiffWhenNoReceipt() {
        OrderDO order = new OrderDO();
        order.setOrderNo("ON1");
        order.setProductId("P1");
        order.setQuantity(2);
        order.setStatus(OrderStatus.CREATED.getCode());
        when(orderMapper.selectTimeoutLocked(eq(OrderStatus.CREATED.getCode()),
                any(LocalDateTime.class), eq(100))).thenReturn(List.of(order));
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(null);

        receiptTimeoutTask.failOrdersWithoutReceipt();

        verify(stockMapper).restore("P1", 2);
        verify(stockService).release("P1", 2);
        verify(orderMapper).updateStatus("ON1", OrderStatus.FAILED.getCode());

        ArgumentCaptor<ReconcileDiffDO> captor = ArgumentCaptor.forClass(ReconcileDiffDO.class);
        verify(reconcileDiffMapper).insert(captor.capture());
        assertThat(captor.getValue().getDiffType()).isEqualTo("RECEIPT_TIMEOUT");
    }

    @Test
    void shouldSkipWhenReceiptExists() {
        OrderDO order = new OrderDO();
        order.setOrderNo("ON1");
        order.setProductId("P1");
        order.setQuantity(2);
        order.setStatus(OrderStatus.CREATED.getCode());
        when(orderMapper.selectTimeoutLocked(anyInt(), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(order));
        when(receiptMapper.selectByOrderNo("ON1")).thenReturn(new ReceiptDO());

        receiptTimeoutTask.failOrdersWithoutReceipt();

        verifyNoInteractions(stockMapper, stockService, reconcileDiffMapper);
        verify(orderMapper, never()).updateStatus(anyString(), anyInt());
    }
}
