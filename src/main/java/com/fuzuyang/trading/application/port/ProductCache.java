package com.fuzuyang.trading.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 商品缓存端口（cache-aside）。
 */
public interface ProductCache {

    /** 读取缓存，不存在返回 {@link Optional#empty()}。 */
    Optional<String> get(String key);

    /** 写入缓存并设置过期时间。 */
    void put(String key, String value, Duration ttl);

    /** 删除缓存。 */
    void evict(String key);
}
