package com.fuzuyang.trading;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * trading-order-service 启动类。
 *
 * <p>D1–D2 阶段仅包含工程骨架、数据库脚本、Mapper/Entity 与健康检查。
 * 下单主链路、幂等、库存、Outbox、Kafka 投递属于 D3 及之后的任务。</p>
 */
@SpringBootApplication
@MapperScan("com.fuzuyang.trading.infrastructure.persistence.mapper")
public class TradingOrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingOrderServiceApplication.class, args);
    }
}
