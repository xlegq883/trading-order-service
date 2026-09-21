package com.fuzuyang.trading.infrastructure.redis;

import com.fuzuyang.trading.application.port.StockCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.Collections;

/**
 * 基于 Redis Lua 的库存缓存实现。
 *
 * <p>脚本保证「检查 + 扣减」原子执行，避免 check-then-act 竞态；
 * 连接失败时抛出 {@code DataAccessException}，由应用层决定降级。</p>
 */
@Repository
public class RedisStockCache implements StockCache {

    private static final String KEY_PREFIX = "stock:";
    private static final long SUCCESS = 1L;
    private static final long INSUFFICIENT = 0L;
    private static final long NOT_INITIALIZED = -1L;

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> deductScript;

    public RedisStockCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.deductScript = new DefaultRedisScript<>();
        this.deductScript.setLocation(new ClassPathResource("lua/stock_deduct.lua"));
        this.deductScript.setResultType(Long.class);
    }

    @Override
    public DeductResult tryDeduct(String productId, int quantity) {
        Long result = stringRedisTemplate.execute(
                deductScript,
                Collections.singletonList(key(productId)),
                String.valueOf(quantity));
        if (result == null || result == NOT_INITIALIZED) {
            return DeductResult.NOT_INITIALIZED;
        }
        return result == SUCCESS ? DeductResult.SUCCESS : DeductResult.INSUFFICIENT;
    }

    @Override
    public void restore(String productId, int quantity) {
        stringRedisTemplate.opsForValue().increment(key(productId), quantity);
    }

    @Override
    public void init(String productId, int available) {
        stringRedisTemplate.opsForValue().set(key(productId), String.valueOf(available));
    }

    private String key(String productId) {
        return KEY_PREFIX + productId;
    }
}
