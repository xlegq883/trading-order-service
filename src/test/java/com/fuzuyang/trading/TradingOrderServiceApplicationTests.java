package com.fuzuyang.trading;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载测试：验证 Spring 容器、MyBatis Mapper 与配置可正常装配。
 * 使用 H2 内存库（见 src/test/resources/application.yml），不依赖外部中间件。
 */
@SpringBootTest
class TradingOrderServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
