package com.fuzuyang.trading.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单接口全链路集成测试（H2 真落库）。
 *
 * <p>使用 MockMvc + @Transactional：请求与断言同线程，测试结束自动回滚，不污染数据库。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Test
    void shouldCreateOrderAndPersistAsCreated() throws Exception {
        String body = objectMapper.writeValueAsString(payload("U1", "P1001", 2, "it-ok-001"));

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn();

        String orderNo = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("orderNo").asText();

        OrderDO saved = orderMapper.selectByOrderNo(orderNo);
        assertThat(saved).isNotNull();
        assertThat(saved.getUserId()).isEqualTo("U1");
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CREATED.getCode());
    }

    @Test
    void shouldReturnUnifiedErrorWhenParamInvalid() throws Exception {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("productId", "P1001");
        invalid.put("quantity", 2);
        invalid.put("idempotentKey", "it-bad-001");

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("userId")));
    }

    @Test
    void duplicateCallWithDifferentKeysShouldCreateTwoOrders() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("U2", "P1002", 1, "it-dup-1"))))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("U2", "P1002", 1, "it-dup-2"))))
                .andExpect(status().isOk())
                .andReturn();

        String firstNo = extractOrderNo(first);
        String secondNo = extractOrderNo(second);

        assertThat(firstNo).isNotEqualTo(secondNo);
        assertThat(orderMapper.selectByOrderNo(firstNo)).isNotNull();
        assertThat(orderMapper.selectByOrderNo(secondNo)).isNotNull();
    }

    private Map<String, Object> payload(String userId, String productId, int quantity, String idempotentKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("productId", productId);
        body.put("quantity", quantity);
        body.put("idempotentKey", idempotentKey);
        return body;
    }

    private String extractOrderNo(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("orderNo").asText();
    }
}
