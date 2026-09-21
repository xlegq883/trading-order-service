package com.fuzuyang.trading.application.task;

import com.fuzuyang.trading.application.service.StockService;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 超时库存回补任务单元测试。
 */
@ExtendWith(MockitoExtension.class)
class StockTimeoutTaskTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private StockMapper stockMapper;

    @Mock
    private StockService stockService;

    @InjectMocks
    private StockTimeoutTask stockTimeoutTask;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(stockTimeoutTask, "lockTimeout", Duration.ofMinutes(15));
        ReflectionTestUtils.setField(stockTimeoutTask, "scanLimit", 100);
    }

    @Test
    void shouldRestoreStockAndMarkFailed() {
        OrderDO order = new OrderDO();
        order.setOrderNo("ON1");
        order.setProductId("P1");
        order.setQuantity(2);
        order.setStatus(OrderStatus.STOCK_LOCKED.getCode());
        when(orderMapper.selectTimeoutLocked(eq(OrderStatus.STOCK_LOCKED.getCode()),
                any(LocalDateTime.class), eq(100))).thenReturn(List.of(order));

        stockTimeoutTask.restoreTimedOutOrders();

        verify(stockMapper).restore("P1", 2);
        verify(stockService).release("P1", 2);
        verify(orderMapper).updateStatus("ON1", OrderStatus.FAILED.getCode());
    }

    @Test
    void shouldDoNothingWhenNoTimeoutOrders() {
        when(orderMapper.selectTimeoutLocked(anyInt(), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of());

        stockTimeoutTask.restoreTimedOutOrders();

        verifyNoInteractions(stockMapper, stockService);
    }
}
