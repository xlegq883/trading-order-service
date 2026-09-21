package com.fuzuyang.trading.infrastructure.persistence.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单表 t_order 的持久化对象（DO）。
 */
@Data
public class OrderDO {

    /** 主键。 */
    private Long id;

    /** 业务单号，唯一。 */
    private String orderNo;

    /** 用户 ID。 */
    private String userId;

    /** 商品 ID。 */
    private String productId;

    /** 购买数量。 */
    private Integer quantity;

    /** 金额，统一使用 BigDecimal，禁止 float。 */
    private BigDecimal amount;

    /** 状态，见 {@link com.fuzuyang.trading.domain.enums.OrderStatus}。 */
    private Integer status;

    /** 幂等键，唯一。 */
    private String idempotentKey;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
