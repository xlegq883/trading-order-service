package com.fuzuyang.trading.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单号生成器。
 *
 * <p>格式：{@code yyyyMMddHHmmssSSS}（17 位毫秒）+ {@link AtomicLong} 3 位循环序列（共 20 位）。
 * 单机每毫秒最多 1000 个不重复订单号，且跨毫秒天然唯一；若需多实例/超高性能可替换为 Snowflake。</p>
 */
public final class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long SEQUENCE_MOD = 1000L;

    private OrderNoGenerator() {
    }

    public static String generate() {
        // 同一毫秒内超过 1000 个请求时序列会回绕；本地压测量级(<1000/ms)下安全
        long seq = SEQUENCE.getAndIncrement() % SEQUENCE_MOD;
        return LocalDateTime.now().format(FORMATTER) + String.format("%03d", seq);
    }
}
