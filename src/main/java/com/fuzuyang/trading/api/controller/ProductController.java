package com.fuzuyang.trading.api.controller;

import com.fuzuyang.trading.api.dto.ProductResponse;
import com.fuzuyang.trading.api.dto.UpdateProductRequest;
import com.fuzuyang.trading.application.service.ProductApplicationService;
import com.fuzuyang.trading.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口（缓存治理演示）。
 */
@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductApplicationService productApplicationService;

    public ProductController(ProductApplicationService productApplicationService) {
        this.productApplicationService = productApplicationService;
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<ProductResponse> get(@PathVariable String productId) {
        return ApiResponse.ok(productApplicationService.getProduct(productId));
    }

    @PutMapping("/products/{productId}")
    public ApiResponse<Void> update(@PathVariable String productId,
                                    @Valid @RequestBody UpdateProductRequest request) {
        productApplicationService.updateProduct(productId, request);
        return ApiResponse.ok();
    }
}
