package com.fuzuyang.trading.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单号生成器单元测试。
 */
class OrderNoGeneratorTest {

    @Test
    void shouldGenerateDistinct20DigitOrderNos() {
        // 1000 个连续值在 mod 1000 下必然互不重复（跨毫秒则时间前缀不同），单线程下保证唯一
        int count = 1000;
        Set<String> orderNos = new HashSet<>();

        for (int i = 0; i < count; i++) {
            String orderNo = OrderNoGenerator.generate();
            assertThat(orderNo).hasSize(20).containsOnlyDigits();
            orderNos.add(orderNo);
        }

        assertThat(orderNos).hasSize(count);
    }
}
