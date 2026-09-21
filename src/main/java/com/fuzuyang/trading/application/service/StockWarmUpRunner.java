package com.fuzuyang.trading.application.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动后预热库存到 Redis。
 */
@Component
public class StockWarmUpRunner implements ApplicationRunner {

    private final StockService stockService;

    public StockWarmUpRunner(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public void run(ApplicationArguments args) {
        stockService.warmUp();
    }
}
