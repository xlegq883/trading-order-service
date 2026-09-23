package com.fuzuyang.trading.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.trading.api.dto.ProductResponse;
import com.fuzuyang.trading.api.dto.UpdateProductRequest;
import com.fuzuyang.trading.application.port.ProductCache;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.infrastructure.persistence.entity.ProductDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品缓存治理单元测试：命中、回源写缓存（TTL 随机）、空值缓存防穿透、更新删缓存、Redis 降级。
 */
@ExtendWith(MockitoExtension.class)
class ProductApplicationServiceTest {

    private static final String KEY = "product:P1001";

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductCache productCache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProductApplicationService productApplicationService;

    @BeforeEach
    void setUp() {
        productApplicationService = new ProductApplicationService(productMapper, productCache, objectMapper);
        ReflectionTestUtils.setField(productApplicationService, "cacheTtl", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(productApplicationService, "cacheTtlJitter", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(productApplicationService, "nullCacheTtl", Duration.ofSeconds(30));
    }

    @Test
    void shouldReturnCachedProductOnHit() throws Exception {
        String json = objectMapper.writeValueAsString(new ProductResponse("P1001", "商品A", new BigDecimal("100.00")));
        when(productCache.get(KEY)).thenReturn(Optional.of(json));

        ProductResponse response = productApplicationService.getProduct("P1001");

        assertThat(response.getName()).isEqualTo("商品A");
        verify(productMapper, never()).selectByProductId(anyString());
    }

    @Test
    void shouldLoadFromDbAndCacheWithRandomTtlOnMiss() {
        when(productCache.get(KEY)).thenReturn(Optional.empty());
        when(productMapper.selectByProductId("P1001")).thenReturn(product("P1001", "商品A", "100.00"));

        ProductResponse response = productApplicationService.getProduct("P1001");

        assertThat(response.getProductId()).isEqualTo("P1001");
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(productCache).put(eq(KEY), anyString(), ttlCaptor.capture());
        // base 300s ± jitter 60s
        assertThat(ttlCaptor.getValue().toSeconds()).isBetween(240L, 360L);
    }

    @Test
    void shouldCacheNullMarkerWhenProductMissing() {
        when(productCache.get(KEY)).thenReturn(Optional.empty());
        when(productMapper.selectByProductId("P1001")).thenReturn(null);

        assertThatThrownBy(() -> productApplicationService.getProduct("P1001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品不存在");

        verify(productCache).put(KEY, "__NULL__", Duration.ofSeconds(30));
    }

    @Test
    void shouldThrowFromNullMarkerWithoutHittingDb() {
        when(productCache.get(KEY)).thenReturn(Optional.of("__NULL__"));

        assertThatThrownBy(() -> productApplicationService.getProduct("P1001"))
                .isInstanceOf(BusinessException.class);

        verify(productMapper, never()).selectByProductId(anyString());
    }

    @Test
    void shouldUpdateDbThenEvictCache() {
        when(productMapper.selectByProductId("P1001")).thenReturn(product("P1001", "旧名", "100.00"));

        productApplicationService.updateProduct("P1001", request("新名", "88.00"));

        verify(productMapper).update(any());
        verify(productCache).evict(KEY);
    }

    @Test
    void shouldThrowWhenUpdatingMissingProduct() {
        when(productMapper.selectByProductId("P1001")).thenReturn(null);

        assertThatThrownBy(() -> productApplicationService.updateProduct("P1001", request("新名", "88.00")))
                .isInstanceOf(BusinessException.class);

        verify(productMapper, never()).update(any());
        verify(productCache, never()).evict(anyString());
    }

    @Test
    void shouldFallbackToDbWhenRedisUnavailable() {
        when(productCache.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));
        when(productMapper.selectByProductId("P1001")).thenReturn(product("P1001", "商品A", "100.00"));

        ProductResponse response = productApplicationService.getProduct("P1001");

        assertThat(response.getProductId()).isEqualTo("P1001");
    }

    private ProductDO product(String productId, String name, String price) {
        ProductDO product = new ProductDO();
        product.setProductId(productId);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        return product;
    }

    private UpdateProductRequest request(String name, String price) {
        UpdateProductRequest request = new UpdateProductRequest();
        request.setName(name);
        request.setPrice(new BigDecimal(price));
        return request;
    }
}
