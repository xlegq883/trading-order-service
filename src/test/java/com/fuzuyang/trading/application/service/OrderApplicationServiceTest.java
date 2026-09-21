package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.api.dto.CreateOrderRequest;
import com.fuzuyang.trading.api.dto.CreateOrderResponse;
import com.fuzuyang.trading.application.port.IdempotencyStore;
import com.fuzuyang.trading.common.ErrorCode;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等 + 库存编排单元测试。
 */
@ExtendWith(MockitoExtension.class)
class OrderApplicationServiceTest {

    private static final String REDIS_KEY = "order:idem:k1";
    private static final String PRODUCT_ID = "P1001";

    @Mock
    private IdempotencyStore idempotencyStore;

    @Mock
    private OrderCreationService orderCreationService;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private StockService stockService;

    @InjectMocks
    private OrderApplicationService orderApplicationService;

    @Test
    void shouldReserveStockAndCreateWhenLockAcquired() {
        when(idempotencyStore.tryLock(anyString(), anyString(), any())).thenReturn(true);
        when(orderMapper.selectByIdempotentKey("k1")).thenReturn(null);
        when(orderCreationService.create(any(), eq("k1")))
                .thenReturn(new CreateOrderResponse("ON1", "CREATED"));

        CreateOrderResponse response = orderApplicationService.createOrder(request(), "k1");

        assertThat(response.getOrderNo()).isEqualTo("ON1");
        verify(stockService).reserve(PRODUCT_ID, 2);
        verify(idempotencyStore).put(eq(REDIS_KEY), eq("ON1"), eq(Duration.ofHours(24)));
        verify(stockService, never()).release(anyString(), anyInt());
    }

    @Test
    void shouldReturnCachedOrderWhenLockNotAcquired() {
        when(idempotencyStore.tryLock(anyString(), anyString(), any())).thenReturn(false);
        when(idempotencyStore.get(REDIS_KEY)).thenReturn(Optional.of("ON1"));
        when(orderMapper.selectByOrderNo("ON1")).thenReturn(order("ON1", OrderStatus.CREATED));

        CreateOrderResponse response = orderApplicationService.createOrder(request(), "k1");

        assertThat(response.getOrderNo()).isEqualTo("ON1");
        verify(stockService, never()).reserve(anyString(), anyInt());
        verify(orderCreationService, never()).create(any(), any());
    }

    @Test
    void shouldReturnExistingFromPrecheckWithoutReservingStock() {
        when(idempotencyStore.tryLock(anyString(), anyString(), any())).thenReturn(true);
        when(orderMapper.selectByIdempotentKey("k1")).thenReturn(order("ON5", OrderStatus.CREATED));

        CreateOrderResponse response = orderApplicationService.createOrder(request(), "k1");

        assertThat(response.getOrderNo()).isEqualTo("ON5");
        verify(stockService, never()).reserve(anyString(), anyInt());
        verify(orderCreationService, never()).create(any(), any());
        verify(idempotencyStore).put(eq(REDIS_KEY), eq("ON5"), eq(Duration.ofHours(24)));
    }

    @Test
    void shouldReleaseIdempotencyKeyWhenStockInsufficient() {
        when(idempotencyStore.tryLock(anyString(), anyString(), any())).thenReturn(true);
        when(orderMapper.selectByIdempotentKey("k1")).thenReturn(null);
        doThrow(new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "库存不足"))
                .when(stockService).reserve(PRODUCT_ID, 2);

        assertThatThrownBy(() -> orderApplicationService.createOrder(request(), "k1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        verify(orderCreationService, never()).create(any(), any());
        verify(stockService, never()).release(anyString(), anyInt());
        verify(idempotencyStore).remove(REDIS_KEY);
    }

    @Test
    void shouldFallbackToDbWhenRedisUnavailable() {
        when(idempotencyStore.tryLock(anyString(), anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(orderMapper.selectByIdempotentKey("k1")).thenReturn(null);
        when(orderCreationService.create(any(), eq("k1")))
                .thenReturn(new CreateOrderResponse("ON2", "CREATED"));

        CreateOrderResponse response = orderApplicationService.createOrder(request(), "k1");

        assertThat(response.getOrderNo()).isEqualTo("ON2");
        verify(stockService).reserve(PRODUCT_ID, 2);
    }

    @Test
    void shouldReleaseStockAndReturnExistingOnDuplicateKey() {
        when(idempotencyStore.tryLock(anyString(), anyString(), any())).thenReturn(true);
        when(orderMapper.selectByIdempotentKey("k1"))
                .thenReturn(null)
                .thenReturn(order("ON9", OrderStatus.CREATED));
        when(orderCreationService.create(any(), eq("k1"))).thenThrow(new DuplicateKeyException("dup"));
        when(idempotencyStore.get(REDIS_KEY)).thenReturn(Optional.empty());

        CreateOrderResponse response = orderApplicationService.createOrder(request(), "k1");

        assertThat(response.getOrderNo()).isEqualTo("ON9");
        verify(stockService).release(PRODUCT_ID, 2);
    }

    @Test
    void shouldThrowConflictWhenLockHitButOrderNotYetPersisted() {
        when(idempotencyStore.tryLock(anyString(), anyString(), any())).thenReturn(false);
        when(idempotencyStore.get(REDIS_KEY)).thenReturn(Optional.empty());
        when(orderMapper.selectByIdempotentKey("k1")).thenReturn(null);

        assertThatThrownBy(() -> orderApplicationService.createOrder(request(), "k1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("处理中");
    }

    private CreateOrderRequest request() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId("U1");
        request.setProductId(PRODUCT_ID);
        request.setQuantity(2);
        return request;
    }

    private OrderDO order(String orderNo, OrderStatus status) {
        OrderDO order = new OrderDO();
        order.setOrderNo(orderNo);
        order.setStatus(status.getCode());
        return order;
    }
}
