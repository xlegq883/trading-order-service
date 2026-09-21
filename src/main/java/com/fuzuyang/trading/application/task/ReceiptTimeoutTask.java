package com.fuzuyang.trading.application.task;

import com.fuzuyang.trading.application.service.StockService;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReconcileDiffDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReceiptMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReconcileDiffMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时无回执任务：长时间处于 CREATED 且没有回执的订单 → 置 FAILED、回补库存、记录差异。
 */
@Component
public class ReceiptTimeoutTask {

    private static final Logger log = LoggerFactory.getLogger(ReceiptTimeoutTask.class);

    private final OrderMapper orderMapper;
    private final ReceiptMapper receiptMapper;
    private final StockMapper stockMapper;
    private final StockService stockService;
    private final ReconcileDiffMapper reconcileDiffMapper;

    @Value("${app.receipt.timeout:PT15M}")
    private Duration timeout;

    @Value("${app.receipt.scan-limit:100}")
    private int scanLimit;

    public ReceiptTimeoutTask(OrderMapper orderMapper,
                              ReceiptMapper receiptMapper,
                              StockMapper stockMapper,
                              StockService stockService,
                              ReconcileDiffMapper reconcileDiffMapper) {
        this.orderMapper = orderMapper;
        this.receiptMapper = receiptMapper;
        this.stockMapper = stockMapper;
        this.stockService = stockService;
        this.reconcileDiffMapper = reconcileDiffMapper;
    }

    @Scheduled(fixedDelayString = "${app.receipt.timeout-interval-ms:60000}",
            initialDelayString = "${app.receipt.timeout-interval-ms:60000}")
    public void failOrdersWithoutReceipt() {
        LocalDateTime before = LocalDateTime.now().minus(timeout);
        List<OrderDO> orders = orderMapper.selectTimeoutLocked(
                OrderStatus.CREATED.getCode(), before, scanLimit);
        for (OrderDO order : orders) {
            if (receiptMapper.selectByOrderNo(order.getOrderNo()) != null) {
                continue;
            }
            stockMapper.restore(order.getProductId(), order.getQuantity());
            stockService.release(order.getProductId(), order.getQuantity());
            orderMapper.updateStatus(order.getOrderNo(), OrderStatus.FAILED.getCode());
            recordDiff(order.getOrderNo(), "RECEIPT_TIMEOUT", "超时无回执，已回补库存并置 FAILED");
            log.warn("超时无回执：orderNo={}, productId={}, quantity={}",
                    order.getOrderNo(), order.getProductId(), order.getQuantity());
        }
    }

    private void recordDiff(String orderNo, String diffType, String detail) {
        ReconcileDiffDO diff = new ReconcileDiffDO();
        diff.setOrderNo(orderNo);
        diff.setDiffType(diffType);
        diff.setDetail(detail);
        diff.setCreatedAt(LocalDateTime.now());
        reconcileDiffMapper.insert(diff);
    }
}
