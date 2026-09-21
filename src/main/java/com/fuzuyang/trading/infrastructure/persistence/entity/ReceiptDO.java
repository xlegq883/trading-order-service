package com.fuzuyang.trading.infrastructure.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上游回执表 t_receipt 的持久化对象（DO）。
 */
@Data
public class ReceiptDO {

    /** 主键。 */
    private Long id;

    /** 订单号，唯一。 */
    private String orderNo;

    /** 上游流水号。 */
    private String upstreamNo;

    /** 上游处理结果。 */
    private Integer status;

    /** 原始回执（JSON 字符串）。 */
    private String payload;

    private LocalDateTime createdAt;
}
