package com.fuzuyang.trading.api.controller;

import com.fuzuyang.trading.api.dto.HandleReceiptRequest;
import com.fuzuyang.trading.application.service.ReceiptApplicationService;
import com.fuzuyang.trading.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上游回执接口。
 *
 * <p>供外部上游回调；模拟上游消费者内部也调用同一应用服务。</p>
 */
@RestController
@RequestMapping("/api")
public class ReceiptController {

    private final ReceiptApplicationService receiptApplicationService;

    public ReceiptController(ReceiptApplicationService receiptApplicationService) {
        this.receiptApplicationService = receiptApplicationService;
    }

    @PostMapping("/receipts")
    public ApiResponse<Void> handle(@Valid @RequestBody HandleReceiptRequest request) {
        receiptApplicationService.handleReceipt(request, null);
        return ApiResponse.ok();
    }
}
