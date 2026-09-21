package com.fuzuyang.trading.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单号生成器。
 *
 * <p>格式：yyyyMMddHHmmss + 6 位随机数（共 20 位，≤ 32）。随机后缀只能降低碰撞概率，
 * 唯一性最终由 {@code t_order.uk_order_no} 唯一索引兜底；如需严格趋势递增可替换为 Snowflake。</p>
 */
public final class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int RANDOM_BOUND = 1_000_000;

    private OrderNoGenerator() {
    }

    public static String generate() {
        String prefix = LocalDateTime.now().format(FORMATTER);
        String suffix = String.format("%06d", ThreadLocalRandom.current().nextInt(RANDOM_BOUND));
        return prefix + suffix;
    }
}
