package com.fuzuyang.trading.domain.enums;

/**
 * Outbox 消息状态：0 待发送 / 1 已发送 / 2 失败。
 *
 * <p>D1–D2 仅定义状态，投递逻辑属于 D6。</p>
 */
public enum OutboxStatus {

    /** 待发送。 */
    PENDING(0),

    /** 已发送。 */
    SENT(1),

    /** 发送失败，待重试。 */
    FAILED(2);

    private final int code;

    OutboxStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
