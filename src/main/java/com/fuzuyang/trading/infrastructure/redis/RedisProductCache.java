package com.fuzuyang.trading.infrastructure.redis;

import com.fuzuyang.trading.application.port.ProductCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 基于 Redis 的商品缓存实现。
 *
 * <p>连接失败抛 {@code DataAccessException}，由应用层决定降级（回源 DB）。</p>
 */
@Repository
public class RedisProductCache implements ProductCache {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisProductCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
    }

    @Override
    public void evict(String key) {
        stringRedisTemplate.delete(key);
    }
}
