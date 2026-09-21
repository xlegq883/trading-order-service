package com.fuzuyang.trading.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 下单请求。
 *
 * <p>D3 只做参数校验与落单，幂等键由客户端提供但暂不做幂等处理（D4 再接入 Redis SETNX）。</p>
 */
@Data
public class CreateOrderRequest {

    @NotBlank(message = "userId 不能为空")
    @Size(max = 32, message = "userId 长度不能超过 32")
    private String userId;

    @NotBlank(message = "productId 不能为空")
    @Size(max = 32, message = "productId 长度不能超过 32")
    private String productId;

    @NotNull(message = "quantity 不能为空")
    @Positive(message = "quantity 必须大于 0")
    private Integer quantity;

    @NotBlank(message = "idempotentKey 不能为空")
    @Size(max = 64, message = "idempotentKey 长度不能超过 64")
    private String idempotentKey;
}
