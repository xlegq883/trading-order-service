package com.fuzuyang.trading.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 上游回执请求。
 */
@Data
public class HandleReceiptRequest {

    @NotBlank(message = "orderNo 不能为空")
    @Size(max = 32, message = "orderNo 长度不能超过 32")
    private String orderNo;

    @NotBlank(message = "upstreamNo 不能为空")
    @Size(max = 64, message = "upstreamNo 长度不能超过 64")
    private String upstreamNo;

    /** 上游处理结果：1 成功 / 其它失败。 */
    @NotNull(message = "status 不能为空")
    private Integer status;

    /** 上游金额（用于对账比对，可为空）。 */
    @PositiveOrZero(message = "amount 不能为负")
    private BigDecimal amount;
}
