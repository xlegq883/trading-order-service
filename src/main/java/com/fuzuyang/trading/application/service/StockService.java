package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.application.port.StockCache;
import com.fuzuyang.trading.application.port.StockCache.DeductResult;
import com.fuzuyang.trading.common.ErrorCode;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.infrastructure.persistence.entity.StockDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 库存预扣编排。
 *
 * <p>Redis 为并发闸门，DB 为持久真相。Redis 未预热时按需从 DB 加载；
 * Redis 不可用时降级，交由 {@link com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper#deduct}
 * 的条件更新兜底。</p>
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockCache stockCache;
    private final StockMapper stockMapper;

    public StockService(StockCache stockCache, StockMapper stockMapper) {
        this.stockCache = stockCache;
        this.stockMapper = stockMapper;
    }

    /** 启动预热：把 DB 可用库存写入 Redis（Redis 不可用不影响启动）。 */
    public void warmUp() {
        try {
            for (StockDO stock : stockMapper.selectAll()) {
                stockCache.init(stock.getProductId(), stock.getAvailable());
            }
            log.info("库存预热完成");
        } catch (DataAccessException ex) {
            log.warn("库存预热失败（Redis 不可用？），将降级为 DB 条件扣减：{}", ex.getMessage());
        }
    }

    /**
     * 预扣库存。
     *
     * @throws BusinessException 库存不足（{@link ErrorCode#STOCK_INSUFFICIENT}）
     */
    public void reserve(String productId, int quantity) {
        try {
            DeductResult result = stockCache.tryDeduct(productId, quantity);
            if (result == DeductResult.NOT_INITIALIZED) {
                initializeFromDb(productId);
                result = stockCache.tryDeduct(productId, quantity);
            }
            if (result == DeductResult.INSUFFICIENT) {
                throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "库存不足: productId=" + productId);
            }
        } catch (DataAccessException ex) {
            log.warn("Redis 不可用，降级为 DB 条件扣减兜底：{}", ex.getMessage());
        }
    }

    /** 回补库存（best-effort）。 */
    public void release(String productId, int quantity) {
        try {
            stockCache.restore(productId, quantity);
        } catch (DataAccessException ex) {
            log.warn("回补 Redis 库存失败：productId={}, quantity={}, error={}",
                    productId, quantity, ex.getMessage());
        }
    }

    private void initializeFromDb(String productId) {
        StockDO stock = stockMapper.selectByProductId(productId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品库存不存在: productId=" + productId);
        }
        stockCache.init(productId, stock.getAvailable());
    }
}
