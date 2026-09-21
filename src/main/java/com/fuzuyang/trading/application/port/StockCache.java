package com.fuzuyang.trading.application.port;

/**
 * 库存缓存端口。
 *
 * <p>Redis 是并发闸门（Lua 原子扣减），DB 条件更新是持久真相；应用层只依赖该接口。</p>
 */
public interface StockCache {

    /** 扣减结果。 */
    enum DeductResult {
        /** 扣减成功。 */
        SUCCESS,
        /** 库存不足。 */
        INSUFFICIENT,
        /** 缓存未预热（key 不存在）。 */
        NOT_INITIALIZED
    }

    /** 原子检查并扣减库存。 */
    DeductResult tryDeduct(String productId, int quantity);

    /** 回补库存。 */
    void restore(String productId, int quantity);

    /** 预热：写入商品可用库存。 */
    void init(String productId, int available);
}
