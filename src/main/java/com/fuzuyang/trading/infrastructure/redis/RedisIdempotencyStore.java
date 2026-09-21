package com.fuzuyang.trading.infrastructure.redis;

import com.fuzuyang.trading.application.port.IdempotencyStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 基于 Redis 的幂等存储实现。
 *
 * <p>连接失败时直接抛出 {@code DataAccessException}，由应用层决定是否降级，
 * 本类不做吞异常处理。</p>
 */
@Repository
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdempotencyStore(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(String key, String value, Duration ttl) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(acquired);
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
    public void remove(String key) {
        stringRedisTemplate.delete(key);
    }
}
