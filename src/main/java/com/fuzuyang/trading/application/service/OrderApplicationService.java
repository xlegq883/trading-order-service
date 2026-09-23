package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.api.dto.CreateOrderRequest;
import com.fuzuyang.trading.api.dto.CreateOrderResponse;
import com.fuzuyang.trading.application.port.IdempotencyStore;
import com.fuzuyang.trading.common.ErrorCode;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.common.util.OrderNoGenerator;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 下单编排：幂等 + 库存预扣 + 事务落单 + 失败补偿。
 *
 * <p>设计取舍：Redis SETNX/ Lua 只是<strong>性能与并发闸门</strong>，
 * {@code t_order.uk_idempotent_key} 唯一索引与 {@code t_stock.available >= quantity}
 * 条件更新才是<strong>正确性保证</strong>；Redis 不可用时自动降级。</p>
 *
 * <p>本类不做事务；落单事务在 {@link OrderCreationService}。这样在捕获
 * {@link DuplicateKeyException}（事务已回滚）后，仍能安全查询已存在订单并回补 Redis 库存。</p>
 */
@Service
public class OrderApplicationService {

    private static final Logger log = LoggerFactory.getLogger(OrderApplicationService.class);

    private static final String KEY_PREFIX = "order:idem:";
    private static final String PENDING = "PENDING";
    private static final Duration TTL = Duration.ofHours(24);

    /** 命中幂等键后，等待首单落库的有界轮询次数与间隔（合计约 1s）。 */
    private static final int RESOLVE_MAX_ATTEMPTS = 20;
    private static final long RESOLVE_INTERVAL_MILLIS = 50;

    /** 订单号碰撞时的最大换号重试次数。 */
    private static final int MAX_ORDER_NO_RETRIES = 3;

    private final OrderCreationService orderCreationService;
    private final OrderMapper orderMapper;
    private final IdempotencyStore idempotencyStore;
    private final StockService stockService;

    public OrderApplicationService(OrderCreationService orderCreationService,
                                   OrderMapper orderMapper,
                                   IdempotencyStore idempotencyStore,
                                   StockService stockService) {
        this.orderCreationService = orderCreationService;
        this.orderMapper = orderMapper;
        this.idempotencyStore = idempotencyStore;
        this.stockService = stockService;
    }

    /**
     * 创建订单（幂等 + 库存）。
     *
     * @param request        已通过校验的下单请求
     * @param idempotencyKey 请求头 x-idempotency-key
     */
    public CreateOrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;

        if (!tryAcquire(redisKey)) {
            // 已存在同 key 请求：返回首单结果
            return resolveExisting(idempotencyKey, redisKey);
        }

        String productId = request.getProductId();
        int quantity = request.getQuantity();
        boolean reserved = false;
        try {
            // 幂等预检：命中则直接返回，避免重复扣减库存
            OrderDO existing = orderMapper.selectByIdempotentKey(idempotencyKey);
            if (existing != null) {
                safePut(redisKey, existing.getOrderNo());
                return toResponse(existing);
            }

            stockService.reserve(productId, quantity);
            reserved = true;

            for (int attempt = 0; attempt < MAX_ORDER_NO_RETRIES; attempt++) {
                String orderNo = OrderNoGenerator.generate();
                try {
                    CreateOrderResponse response = orderCreationService.create(request, idempotencyKey, orderNo);
                    safePut(redisKey, response.getOrderNo());
                    return response;
                } catch (DuplicateKeyException ex) {
                    if (orderMapper.selectByIdempotentKey(idempotencyKey) != null) {
                        // 幂等冲突（唯一索引冲突实为 uk_idempotent_key）：本次为多余预扣，回补后返回首单
                        stockService.release(productId, quantity);
                        reserved = false;
                        log.warn("命中 uk_idempotent_key 唯一索引，返回已存在订单：idempotencyKey={}", idempotencyKey);
                        return resolveExisting(idempotencyKey, redisKey);
                    }
                    // 唯一索引冲突实为 uk_order_no（订单号碰撞）：换号重试
                    log.warn("订单号碰撞，换号重试：orderNo={}, attempt={}", orderNo, attempt + 1);
                }
            }
            throw new BusinessException(ErrorCode.SERVER_ERROR, "生成订单号失败，请重试");
        } catch (RuntimeException ex) {
            // 落单失败（含库存不足、换号重试耗尽）：回补已预扣库存并释放幂等键，允许重试
            if (reserved) {
                stockService.release(productId, quantity);
            }
            safeRemove(redisKey);
            throw ex;
        }
    }

    /**
     * 抢占幂等键；Redis 不可用时降级为「视为抢占成功」，交由 DB 唯一索引兜底。
     */
    private boolean tryAcquire(String redisKey) {
        try {
            return idempotencyStore.tryLock(redisKey, PENDING, TTL);
        } catch (DataAccessException ex) {
            log.warn("Redis 不可用，降级为 DB 唯一索引兜底：{}", ex.getMessage());
            return true;
        }
    }

    /**
     * 返回已存在订单：先读 Redis 缓存（best-effort），再以 DB 为唯一事实来源。
     *
     * <p>并发场景下首单可能尚未提交，这里做有界轮询等待其落库，避免直接给调用方 409；
     * 轮询超时仍未出现则说明首单可能已失败，返回 409 让客户端重试。</p>
     */
    private CreateOrderResponse resolveExisting(String idempotencyKey, String redisKey) {
        String cachedOrderNo = safeGet(redisKey);
        if (cachedOrderNo != null && !PENDING.equals(cachedOrderNo)) {
            OrderDO cached = orderMapper.selectByOrderNo(cachedOrderNo);
            if (cached != null) {
                return toResponse(cached);
            }
        }

        for (int attempt = 0; attempt < RESOLVE_MAX_ATTEMPTS; attempt++) {
            OrderDO existing = orderMapper.selectByIdempotentKey(idempotencyKey);
            if (existing != null) {
                return toResponse(existing);
            }
            sleepQuietly();
        }

        // 同 key 的首个请求仍未落库（可能已失败或仍在处理）
        throw new BusinessException(ErrorCode.CONFLICT, "重复请求正在处理中，请稍后重试");
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(RESOLVE_INTERVAL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVER_ERROR, "等待幂等结果被中断");
        }
    }

    private String safeGet(String key) {
        try {
            return idempotencyStore.get(key).orElse(null);
        } catch (DataAccessException ex) {
            log.warn("读取幂等缓存失败，回退 DB：{}", ex.getMessage());
            return null;
        }
    }

    private void safePut(String key, String value) {
        try {
            idempotencyStore.put(key, value, TTL);
        } catch (DataAccessException ex) {
            log.warn("回写幂等缓存失败：{}", ex.getMessage());
        }
    }

    private void safeRemove(String key) {
        try {
            idempotencyStore.remove(key);
        } catch (DataAccessException ex) {
            log.warn("释放幂等缓存失败：{}", ex.getMessage());
        }
    }

    private CreateOrderResponse toResponse(OrderDO order) {
        return new CreateOrderResponse(order.getOrderNo(), OrderStatus.of(order.getStatus()).name());
    }
}
