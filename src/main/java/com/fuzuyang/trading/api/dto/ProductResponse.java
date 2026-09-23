package com.fuzuyang.trading.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 商品详情响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {

    private String productId;

    private String name;

    private BigDecimal price;
}
