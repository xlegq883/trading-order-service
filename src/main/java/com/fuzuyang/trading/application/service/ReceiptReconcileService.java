package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReceiptDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReconcileDiffDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReceiptMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReconcileDiffMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 回执对账：把本地订单与上游回执按 {@code orderNo} 比对，推进最终状态。
 *
 * <p>本地 DB 为唯一事实来源；比对 status 与 amount，一致置 CONFIRMED，不一致置 FAILED 并记录差异。</p>
 */
@Service
public class ReceiptReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptReconcileService.class);

    private static final int UPSTREAM_SUCCESS = 1;

    private final OrderMapper orderMapper;
    private final ReceiptMapper receiptMapper;
    private final ReconcileDiffMapper reconcileDiffMapper;
    private final StockMapper stockMapper;
    private final StockService stockService;

    public ReceiptReconcileService(OrderMapper orderMapper,
                                   ReceiptMapper receiptMapper,
                                   ReconcileDiffMapper reconcileDiffMapper,
                                   StockMapper stockMapper,
                                   StockService stockService) {
        this.orderMapper = orderMapper;
        this.receiptMapper = receiptMapper;
        this.reconcileDiffMapper = reconcileDiffMapper;
        this.stockMapper = stockMapper;
        this.stockService = stockService;
    }

    /**
     * 对账单笔订单；仅处理处于 REPORTED 且已有回执的订单。
     */
    @Transactional
    public void reconcile(String orderNo) {
        OrderDO order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || order.getStatus() == null
                || order.getStatus() != OrderStatus.REPORTED.getCode()) {
            return;
        }
        ReceiptDO receipt = receiptMapper.selectByOrderNo(orderNo);
        if (receipt == null) {
            return;
        }

        if (receipt.getStatus() == null || receipt.getStatus() != UPSTREAM_SUCCESS) {
            recordDiff(orderNo, "UPSTREAM_FAILED", "上游处理失败: receiptStatus=" + receipt.getStatus());
            orderMapper.updateStatus(orderNo, OrderStatus.FAILED.getCode());
            restoreStock(order);
            return;
        }

        if (receipt.getAmount() != null && order.getAmount() != null
                && receipt.getAmount().compareTo(order.getAmount()) != 0) {
            recordDiff(orderNo, "AMOUNT_MISMATCH",
                    "金额不一致: local=" + order.getAmount() + ", upstream=" + receipt.getAmount());
            orderMapper.updateStatus(orderNo, OrderStatus.FAILED.getCode());
            restoreStock(order);
            return;
        }

        orderMapper.updateStatus(orderNo, OrderStatus.CONFIRMED.getCode());
        log.info("对账一致，订单确认：orderNo={}", orderNo);
    }

    /**
     * 对账失败时回补预扣库存（DB + Redis），与超时回补路径保持一致。
     */
    private void restoreStock(OrderDO order) {
        stockMapper.restore(order.getProductId(), order.getQuantity());
        stockService.release(order.getProductId(), order.getQuantity());
        log.warn("对账失败回补库存：orderNo={}, productId={}, quantity={}",
                order.getOrderNo(), order.getProductId(), order.getQuantity());
    }

    private void recordDiff(String orderNo, String diffType, String detail) {
        ReconcileDiffDO diff = new ReconcileDiffDO();
        diff.setOrderNo(orderNo);
        diff.setDiffType(diffType);
        diff.setDetail(detail);
        diff.setCreatedAt(LocalDateTime.now());
        reconcileDiffMapper.insert(diff);
        log.warn("对账差异：orderNo={}, type={}, detail={}", orderNo, diffType, detail);
    }
}
