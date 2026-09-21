package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.application.port.StockCache;
import com.fuzuyang.trading.application.port.StockCache.DeductResult;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.infrastructure.persistence.entity.StockDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存预扣编排单元测试：成功 / 不足 / 未预热重试 / Redis 降级 / 预热。
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private StockCache stockCache;

    @Mock
    private StockMapper stockMapper;

    @InjectMocks
    private StockService stockService;

    @Test
    void shouldReserveSuccessfully() {
        when(stockCache.tryDeduct("P1", 2)).thenReturn(DeductResult.SUCCESS);

        assertThatCode(() -> stockService.reserve("P1", 2)).doesNotThrowAnyException();

        verify(stockCache).tryDeduct("P1", 2);
    }

    @Test
    void shouldThrowWhenInsufficient() {
        when(stockCache.tryDeduct("P1", 2)).thenReturn(DeductResult.INSUFFICIENT);

        assertThatThrownBy(() -> stockService.reserve("P1", 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");
    }

    @Test
    void shouldWarmUpOnDemandThenRetryWhenNotInitialized() {
        when(stockCache.tryDeduct("P1", 2))
                .thenReturn(DeductResult.NOT_INITIALIZED)
                .thenReturn(DeductResult.SUCCESS);
        when(stockMapper.selectByProductId("P1")).thenReturn(stock("P1", 10));

        stockService.reserve("P1", 2);

        verify(stockCache).init("P1", 10);
        verify(stockCache, times(2)).tryDeduct("P1", 2);
    }

    @Test
    void shouldDegradeWhenRedisUnavailable() {
        when(stockCache.tryDeduct("P1", 2)).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> stockService.reserve("P1", 2)).doesNotThrowAnyException();
    }

    @Test
    void shouldReleaseStock() {
        stockService.release("P1", 2);

        verify(stockCache).restore("P1", 2);
    }

    @Test
    void shouldWarmUpAllStocks() {
        when(stockMapper.selectAll()).thenReturn(List.of(stock("P1", 10), stock("P2", 20)));

        stockService.warmUp();

        verify(stockCache).init("P1", 10);
        verify(stockCache).init("P2", 20);
    }

    @Test
    void shouldNotFailWarmUpWhenRedisUnavailable() {
        when(stockMapper.selectAll()).thenReturn(List.of(stock("P1", 10)));
        doThrow(new RedisConnectionFailureException("down")).when(stockCache).init("P1", 10);

        assertThatCode(() -> stockService.warmUp()).doesNotThrowAnyException();
    }

    private StockDO stock(String productId, int available) {
        StockDO stock = new StockDO();
        stock.setProductId(productId);
        stock.setTotal(available);
        stock.setAvailable(available);
        stock.setVersion(0);
        return stock;
    }
}
