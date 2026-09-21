package com.fuzuyang.trading.infrastructure.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账差异表 t_reconcile_diff 的持久化对象（DO）。
 */
@Data
public class ReconcileDiffDO {

    /** 主键。 */
    private Long id;

    /** 订单号。 */
    private String orderNo;

    /** 差异类型：AMOUNT_MISMATCH / UPSTREAM_FAILED / RECEIPT_TIMEOUT / LATE_RECEIPT。 */
    private String diffType;

    /** 差异详情。 */
    private String detail;

    private LocalDateTime createdAt;
}
