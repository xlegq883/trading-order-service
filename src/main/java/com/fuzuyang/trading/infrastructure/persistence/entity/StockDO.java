package com.fuzuyang.trading.infrastructure.persistence.entity;

import lombok.Data;

/**
 * 库存表 t_stock 的持久化对象（DO）。
 */
@Data
public class StockDO {

    /** 商品 ID，主键。 */
    private String productId;

    /** 总库存。 */
    private Integer total;

    /** 可用库存。 */
    private Integer available;

    /** 乐观锁版本号。 */
    private Integer version;
}
