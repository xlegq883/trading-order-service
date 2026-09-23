package com.fuzuyang.trading.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品更新请求。
 */
@Data
public class UpdateProductRequest {

    @NotBlank(message = "name 不能为空")
    @Size(max = 64, message = "name 长度不能超过 64")
    private String name;

    @NotNull(message = "price 不能为空")
    @PositiveOrZero(message = "price 不能为负")
    private BigDecimal price;
}
