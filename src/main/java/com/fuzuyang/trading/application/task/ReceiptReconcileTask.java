package com.fuzuyang.trading.application.task;

import com.fuzuyang.trading.application.service.ReceiptReconcileService;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回执对账任务：扫描 REPORTED 订单，与回执比对后推进 CONFIRMED / FAILED。
 */
@Component
public class ReceiptReconcileTask {

    private final OrderMapper orderMapper;
    private final ReceiptReconcileService receiptReconcileService;

    @Value("${app.receipt.scan-limit:100}")
    private int scanLimit;

    public ReceiptReconcileTask(OrderMapper orderMapper, ReceiptReconcileService receiptReconcileService) {
        this.orderMapper = orderMapper;
        this.receiptReconcileService = receiptReconcileService;
    }

    @Scheduled(fixedDelayString = "${app.receipt.reconcile-interval-ms:10000}")
    public void reconcileReportedOrders() {
        List<OrderDO> orders = orderMapper.selectByStatus(OrderStatus.REPORTED.getCode(), scanLimit);
        for (OrderDO order : orders) {
            receiptReconcileService.reconcile(order.getOrderNo());
        }
    }
}
