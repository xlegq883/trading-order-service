package com.fuzuyang.trading.infrastructure.persistence.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品表 t_product 的持久化对象（DO）。
 */
@Data
public class ProductDO {

    /** 商品 ID，主键。 */
    private String productId;

    /** 商品名称。 */
    private String name;

    /** 商品价格。 */
    private BigDecimal price;
}
