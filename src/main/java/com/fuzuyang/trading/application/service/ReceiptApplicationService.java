package com.fuzuyang.trading.application.service;

import com.fuzuyang.trading.api.dto.HandleReceiptRequest;
import com.fuzuyang.trading.common.ErrorCode;
import com.fuzuyang.trading.common.exception.BusinessException;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReceiptDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReconcileDiffDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReceiptMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReconcileDiffMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 上游回执处理。
 *
 * <p>消费幂等：以业务唯一键 {@code orderNo} 为准，先查 {@code t_receipt}，
 * 命中直接返回；{@code uk_receipt_order_no} 唯一索引兜底并发/重复。</p>
 *
 * <p>状态：正常推进到 REPORTED；若订单已被超时任务置 FAILED，则只记迟到差异，不回退状态。</p>
 */
@Service
public class ReceiptApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptApplicationService.class);

    private final ReceiptMapper receiptMapper;
    private final OrderMapper orderMapper;
    private final ReconcileDiffMapper reconcileDiffMapper;

    public ReceiptApplicationService(ReceiptMapper receiptMapper,
                                     OrderMapper orderMapper,
                                     ReconcileDiffMapper reconcileDiffMapper) {
        this.receiptMapper = receiptMapper;
        this.orderMapper = orderMapper;
        this.reconcileDiffMapper = reconcileDiffMapper;
    }

    /**
     * 处理回执：落 {@code t_receipt} 并把订单状态推进到 REPORTED。
     *
     * @param request 回执内容
     * @param payload 原始消息体（可为空）
     */
    @Transactional
    public void handleReceipt(HandleReceiptRequest request, String payload) {
        ReceiptDO existing = receiptMapper.selectByOrderNo(request.getOrderNo());
        if (existing != null) {
            log.info("回执已存在，幂等跳过：orderNo={}", request.getOrderNo());
            return;
        }

        OrderDO order = orderMapper.selectByOrderNo(request.getOrderNo());
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在: orderNo=" + request.getOrderNo());
        }

        ReceiptDO receipt = new ReceiptDO();
        receipt.setOrderNo(request.getOrderNo());
        receipt.setUpstreamNo(request.getUpstreamNo());
        receipt.setStatus(request.getStatus());
        receipt.setAmount(request.getAmount());
        receipt.setPayload(payload);
        receipt.setCreatedAt(LocalDateTime.now());
        receiptMapper.insert(receipt);

        if (order.getStatus() != null && order.getStatus() == OrderStatus.FAILED.getCode()) {
            recordDiff(request.getOrderNo(), "LATE_RECEIPT", "订单已 FAILED，回执迟到，未回退状态");
            log.warn("迟到回执：orderNo={}, upstreamNo={}", request.getOrderNo(), request.getUpstreamNo());
            return;
        }

        orderMapper.updateStatus(request.getOrderNo(), OrderStatus.REPORTED.getCode());
        log.info("回执处理完成：orderNo={}, upstreamNo={}", request.getOrderNo(), request.getUpstreamNo());
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
