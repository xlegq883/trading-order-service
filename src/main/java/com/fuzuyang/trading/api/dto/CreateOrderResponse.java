package com.fuzuyang.trading.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 下单响应。
 */
@Data
@AllArgsConstructor
public class CreateOrderResponse {

    /** 业务单号。 */
    private String orderNo;

    /** 订单状态名，如 CREATED。 */
    private String status;
}
