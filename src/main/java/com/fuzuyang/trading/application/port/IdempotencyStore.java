package com.fuzuyang.trading.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等存储端口。
 *
 * <p>抽象出「抢占 / 读取 / 写入 / 释放」四个动作，应用层只依赖该接口，
 * 具体实现（Redis）放在 infrastructure，便于测试替换与后续演进。</p>
 */
public interface IdempotencyStore {

    /**
     * 原子抢占：等价于 Redis {@code SET key value NX EX ttl}。
     *
     * @return true 表示抢占成功（此前不存在），false 表示 key 已存在
     */
    boolean tryLock(String key, String value, Duration ttl);

    /** 读取值，不存在返回 {@link Optional#empty()}。 */
    Optional<String> get(String key);

    /** 覆盖写入值并设置过期时间。 */
    void put(String key, String value, Duration ttl);

    /** 删除 key。 */
    void remove(String key);
}
