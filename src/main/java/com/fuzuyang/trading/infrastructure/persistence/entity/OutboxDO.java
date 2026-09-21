package com.fuzuyang.trading.infrastructure.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Outbox 消息表 t_outbox 的持久化对象（DO）。
 */
@Data
public class OutboxDO {

    /** 主键。 */
    private Long id;

    /** 聚合类型，如 ORDER。 */
    private String aggregateType;

    /** 聚合根 ID。 */
    private String aggregateId;

    /** 消息体（JSON 字符串）。 */
    private String payload;

    /** 状态，见 {@link com.fuzuyang.trading.domain.enums.OutboxStatus}。 */
    private Integer status;

    /** 已重试次数。 */
    private Integer retryCount;

    /** 下次重试时间。 */
    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
