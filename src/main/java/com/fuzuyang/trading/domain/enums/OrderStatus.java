package com.fuzuyang.trading.domain.enums;

/**
 * 订单状态机：INIT → STOCK_LOCKED → CREATED → REPORTED → CONFIRMED / FAILED。
 *
 * <p>D1–D2 仅定义状态，不实现流转逻辑。</p>
 */
public enum OrderStatus {

    /** 初始状态。 */
    INIT(0),

    /** 库存已锁定。 */
    STOCK_LOCKED(1),

    /** 订单已创建。 */
    CREATED(2),

    /** 已上报上游。 */
    REPORTED(3),

    /** 上游已确认。 */
    CONFIRMED(4),

    /** 失败（含超时回补）。 */
    FAILED(9);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按状态码反查枚举。
     *
     * @throws IllegalArgumentException 状态码未知
     */
    public static OrderStatus of(int code) {
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知订单状态码: " + code);
    }
}
