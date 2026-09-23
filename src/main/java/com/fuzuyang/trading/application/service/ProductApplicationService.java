package com.fuzuyang.trading.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.trading.api.dto.ProductResponse;
import com.fuzuyang.trading.api.dto.UpdateProductRequest;
import com.fuzuyang.trading.application.port.ProductCache;
import com.fuzuyang.trading.common.ErrorCode;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.infrastructure.persistence.entity.ProductDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品详情查询与更新（缓存治理）。
 *
 * <ul>
 *   <li><b>cache-aside</b>：先查缓存，miss 回源 DB 并写缓存。</li>
 *   <li><b>防穿透</b>：DB 不存在时写空值标记（短 TTL）。</li>
 *   <li><b>防雪崩</b>：写缓存 TTL 随机化（base ± jitter）。</li>
 *   <li><b>一致性</b>：更新先更库、再删缓存；Redis 不可用时降级直读 DB。</li>
 * </ul>
 */
@Service
public class ProductApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ProductApplicationService.class);

    private static final String KEY_PREFIX = "product:";
    private static final String NULL_MARKER = "__NULL__";

    private final ProductMapper productMapper;
    private final ProductCache productCache;
    private final ObjectMapper objectMapper;

    @Value("${app.product.cache-ttl:PT5M}")
    private Duration cacheTtl;

    @Value("${app.product.cache-ttl-jitter:PT1M}")
    private Duration cacheTtlJitter;

    @Value("${app.product.null-cache-ttl:PT30S}")
    private Duration nullCacheTtl;

    public ProductApplicationService(ProductMapper productMapper,
                                     ProductCache productCache,
                                     ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.productCache = productCache;
        this.objectMapper = objectMapper;
    }

    /** 查询商品详情（cache-aside）。 */
    public ProductResponse getProduct(String productId) {
        String key = KEY_PREFIX + productId;

        Optional<String> cached = safeGet(key);
        if (cached.isPresent()) {
            String value = cached.get();
            if (NULL_MARKER.equals(value)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在: " + productId);
            }
            return deserialize(value);
        }

        ProductDO product = productMapper.selectByProductId(productId);
        if (product == null) {
            safePut(key, NULL_MARKER, nullCacheTtl);
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在: " + productId);
        }

        ProductResponse response = new ProductResponse(product.getProductId(), product.getName(), product.getPrice());
        safePut(key, serialize(response), randomTtl());
        return response;
    }

    /** 更新商品：先更库，再删缓存。 */
    @Transactional
    public void updateProduct(String productId, UpdateProductRequest request) {
        ProductDO existing = productMapper.selectByProductId(productId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在: " + productId);
        }
        ProductDO product = new ProductDO();
        product.setProductId(productId);
        product.setName(request.getName());
        product.setPrice(request.getPrice());
        productMapper.update(product);

        safeEvict(KEY_PREFIX + productId);
        log.info("商品已更新并删除缓存：productId={}", productId);
    }

    /** TTL 随机化：base ± random(0..jitter)，防止同一时刻批量失效（雪崩）。 */
    private Duration randomTtl() {
        long base = cacheTtl.toSeconds();
        long jitter = cacheTtlJitter.toSeconds();
        long delta = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        return Duration.ofSeconds(Math.max(1, base + delta));
    }

    private Optional<String> safeGet(String key) {
        try {
            return productCache.get(key);
        } catch (DataAccessException ex) {
            log.warn("读取商品缓存失败，降级直读 DB：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    private void safePut(String key, String value, Duration ttl) {
        try {
            productCache.put(key, value, ttl);
        } catch (DataAccessException ex) {
            log.warn("写入商品缓存失败：{}", ex.getMessage());
        }
    }

    private void safeEvict(String key) {
        try {
            productCache.evict(key);
        } catch (DataAccessException ex) {
            log.warn("删除商品缓存失败：{}", ex.getMessage());
        }
    }

    private String serialize(ProductResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化商品失败: productId=" + response.getProductId(), ex);
        }
    }

    private ProductResponse deserialize(String value) {
        try {
            return objectMapper.readValue(value, ProductResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("反序列化商品缓存失败: " + value, ex);
        }
    }
}
