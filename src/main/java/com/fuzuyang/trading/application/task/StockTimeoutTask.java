package com.fuzuyang.trading.application.task;

import com.fuzuyang.trading.application.service.StockService;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
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
 * 超时库存回补任务。
 *
 * <p>扫描长时间停留在 {@link OrderStatus#STOCK_LOCKED} 的订单（如流程中断），
 * 回补 DB 与 Redis 库存并置为 {@link OrderStatus#FAILED}。</p>
 */
@Component
public class StockTimeoutTask {

    private static final Logger log = LoggerFactory.getLogger(StockTimeoutTask.class);

    private final OrderMapper orderMapper;
    private final StockMapper stockMapper;
    private final StockService stockService;

    @Value("${app.stock.lock-timeout:PT15M}")
    private Duration lockTimeout;

    @Value("${app.stock.timeout-scan-limit:100}")
    private int scanLimit;

    public StockTimeoutTask(OrderMapper orderMapper, StockMapper stockMapper, StockService stockService) {
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
        this.stockService = stockService;
    }

    /**
     * 首次执行延迟一个扫描周期，确保启动预热（ApplicationRunner）先完成，
     * 避免预热读到的旧值覆盖并发回补写入的 Redis 库存。
     */
    @Scheduled(fixedDelayString = "${app.stock.timeout-scan-interval-ms:60000}",
            initialDelayString = "${app.stock.timeout-scan-interval-ms:60000}")
    public void restoreTimedOutOrders() {
        LocalDateTime before = LocalDateTime.now().minus(lockTimeout);
        List<OrderDO> orders = orderMapper.selectTimeoutLocked(
                OrderStatus.STOCK_LOCKED.getCode(), before, scanLimit);
        if (orders.isEmpty()) {
            return;
        }
        for (OrderDO order : orders) {
            stockMapper.restore(order.getProductId(), order.getQuantity());
            stockService.release(order.getProductId(), order.getQuantity());
            orderMapper.updateStatus(order.getOrderNo(), OrderStatus.FAILED.getCode());
            log.warn("超时回补库存：orderNo={}, productId={}, quantity={}",
                    order.getOrderNo(), order.getProductId(), order.getQuantity());
        }
    }
}
