package com.fuzuyang.trading;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * trading-order-service 启动类。
 *
 * <p>D1–D5：工程骨架、数据库脚本、Mapper/Entity、健康检查、下单主链路、幂等、库存。
 * Outbox 与 Kafka 投递属于 D6 及之后的任务。</p>
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.fuzuyang.trading.infrastructure.persistence.mapper")
public class TradingOrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingOrderServiceApplication.class, args);
    }
}
