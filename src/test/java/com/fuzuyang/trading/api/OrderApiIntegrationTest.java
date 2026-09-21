package com.fuzuyang.trading.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.trading.api.dto.HandleReceiptRequest;
import com.fuzuyang.trading.application.port.IdempotencyStore;
import com.fuzuyang.trading.application.port.StockCache;
import com.fuzuyang.trading.application.service.ReceiptApplicationService;
import com.fuzuyang.trading.application.service.ReceiptReconcileService;
import com.fuzuyang.trading.application.task.ReceiptTimeoutTask;
import com.fuzuyang.trading.domain.enums.OrderStatus;
import com.fuzuyang.trading.domain.enums.OutboxStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.OutboxDO;
import com.fuzuyang.trading.infrastructure.persistence.entity.ReconcileDiffDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OrderMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OutboxMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReceiptMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ReconcileDiffMapper;
import com.fuzuyang.trading.infrastructure.persistence.mapper.StockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单接口全链路集成测试（H2 真落库 + 模拟幂等/库存缓存）。
 *
 * <p>使用 MockMvc + @Transactional：请求与断言同线程，测试结束自动回滚。
 * {@link IdempotencyStore} / {@link StockCache} 用 {@code @MockBean} 替换，
 * 让请求走真实的 DB 落单与扣减路径。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderApiIntegrationTest {

    private static final String HEADER = "x-idempotency-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private ReceiptMapper receiptMapper;

    @Autowired
    private ReceiptApplicationService receiptApplicationService;

    @Autowired
    private ReceiptReconcileService receiptReconcileService;

    @Autowired
    private ReceiptTimeoutTask receiptTimeoutTask;

    @Autowired
    private ReconcileDiffMapper reconcileDiffMapper;

    @MockBean
    private IdempotencyStore idempotencyStore;

    @MockBean
    private StockCache stockCache;

    @BeforeEach
    void stubPorts() {
        lenient().when(idempotencyStore.tryLock(anyString(), anyString(), any())).thenReturn(true);
        lenient().when(stockCache.tryDeduct(anyString(), anyInt()))
                .thenReturn(StockCache.DeductResult.SUCCESS);
    }

    @Test
    void shouldCreateOrderAndPersistAsCreated() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HEADER, "it-ok-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("U1", "P1001", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn();

        String orderNo = extractOrderNo(result);
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

        mockMvc.perform(post("/api/orders")
                        .header(HEADER, "it-bad-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("userId")));
    }

    @Test
    void shouldReturnBadRequestWhenIdempotencyKeyHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("U1", "P1001", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(HEADER)));
    }

    @Test
    void sameKeyShouldCreateOnlyOneOrder() throws Exception {
        String key = "it-same-key";

        String firstNo = extractOrderNo(postOrder(key, body("U3", "P1001", 1)));
        String secondNo = extractOrderNo(postOrder(key, body("U3", "P1001", 1)));

        assertThat(secondNo).isEqualTo(firstNo);
        assertThat(orderMapper.selectByIdempotentKey(key)).isNotNull();
    }

    @Test
    void differentKeysShouldCreateTwoOrders() throws Exception {
        String firstNo = extractOrderNo(postOrder("it-dup-1", body("U2", "P1002", 1)));
        String secondNo = extractOrderNo(postOrder("it-dup-2", body("U2", "P1002", 1)));

        assertThat(firstNo).isNotEqualTo(secondNo);
        assertThat(orderMapper.selectByOrderNo(firstNo)).isNotNull();
        assertThat(orderMapper.selectByOrderNo(secondNo)).isNotNull();
    }

    @Test
    void shouldDeductStockWhenOrderCreated() throws Exception {
        int before = stockMapper.selectByProductId("P1001").getAvailable();

        postOrder("it-stock-1", body("U5", "P1001", 2));

        int after = stockMapper.selectByProductId("P1001").getAvailable();
        assertThat(after).isEqualTo(before - 2);
    }

    @Test
    void shouldReturn422WhenStockInsufficient() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header(HEADER, "it-nostock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("U5", "P1001", 9999)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void shouldWriteOutboxWhenOrderCreated() throws Exception {
        String orderNo = extractOrderNo(postOrder("it-outbox-1", body("U7", "P1001", 1)));

        List<OutboxDO> messages = outboxMapper.selectByAggregateId(orderNo);
        assertThat(messages).hasSize(1);

        OutboxDO outbox = messages.get(0);
        assertThat(outbox.getAggregateType()).isEqualTo("ORDER");
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING.getCode());
        assertThat(outbox.getRetryCount()).isZero();
        assertThat(outbox.getPayload()).contains(orderNo);
    }

    @Test
    void shouldHandleReceiptIdempotently() throws Exception {
        String orderNo = extractOrderNo(postOrder("it-receipt-1", body("U10", "P1001", 1)));

        HandleReceiptRequest request = new HandleReceiptRequest();
        request.setOrderNo(orderNo);
        request.setUpstreamNo("UP-1");
        request.setStatus(1);

        receiptApplicationService.handleReceipt(request, "{\"first\":true}");

        assertThat(receiptMapper.selectByOrderNo(orderNo)).isNotNull();
        assertThat(orderMapper.selectByOrderNo(orderNo).getStatus())
                .isEqualTo(OrderStatus.REPORTED.getCode());

        // 重复回执：应幂等跳过（selectByOrderNo 若出现多行会抛异常，即证明唯一）
        receiptApplicationService.handleReceipt(request, "{\"duplicate\":true}");

        assertThat(receiptMapper.selectByOrderNo(orderNo).getUpstreamNo()).isEqualTo("UP-1");
        assertThat(orderMapper.selectByOrderNo(orderNo).getStatus())
                .isEqualTo(OrderStatus.REPORTED.getCode());
    }

    @Test
    void shouldReconcileToConfirmed() throws Exception {
        String orderNo = extractOrderNo(postOrder("it-rec-ok", body("U12", "P1001", 1)));
        handleReceipt(orderNo, "UP-OK", new BigDecimal("0.00"));

        receiptReconcileService.reconcile(orderNo);

        assertThat(orderMapper.selectByOrderNo(orderNo).getStatus())
                .isEqualTo(OrderStatus.CONFIRMED.getCode());
        assertThat(reconcileDiffMapper.selectByOrderNo(orderNo)).isEmpty();
    }

    @Test
    void shouldFailReconcileOnAmountMismatch() throws Exception {
        String orderNo = extractOrderNo(postOrder("it-rec-bad", body("U12", "P1001", 1)));
        handleReceipt(orderNo, "UP-BAD", new BigDecimal("99.99"));

        receiptReconcileService.reconcile(orderNo);

        assertThat(orderMapper.selectByOrderNo(orderNo).getStatus())
                .isEqualTo(OrderStatus.FAILED.getCode());
        assertThat(reconcileDiffMapper.selectByOrderNo(orderNo))
                .extracting(ReconcileDiffDO::getDiffType).containsExactly("AMOUNT_MISMATCH");
    }

    @Test
    void shouldTimeoutFailAndRestoreStock() {
        int before = stockMapper.selectByProductId("P1002").getAvailable();
        OrderDO order = new OrderDO();
        order.setOrderNo("ON-TIMEOUT-1");
        order.setUserId("U13");
        order.setProductId("P1002");
        order.setQuantity(3);
        order.setAmount(BigDecimal.ZERO);
        order.setStatus(OrderStatus.CREATED.getCode());
        order.setIdempotentKey("it-timeout-1");
        order.setCreatedAt(LocalDateTime.now().minusMinutes(20));
        order.setUpdatedAt(LocalDateTime.now().minusMinutes(20));
        orderMapper.insert(order);

        receiptTimeoutTask.failOrdersWithoutReceipt();

        assertThat(orderMapper.selectByOrderNo("ON-TIMEOUT-1").getStatus())
                .isEqualTo(OrderStatus.FAILED.getCode());
        assertThat(stockMapper.selectByProductId("P1002").getAvailable()).isEqualTo(before + 3);
        assertThat(reconcileDiffMapper.selectByOrderNo("ON-TIMEOUT-1"))
                .extracting(ReconcileDiffDO::getDiffType).containsExactly("RECEIPT_TIMEOUT");
    }

    private void handleReceipt(String orderNo, String upstreamNo, BigDecimal amount) {
        HandleReceiptRequest request = new HandleReceiptRequest();
        request.setOrderNo(orderNo);
        request.setUpstreamNo(upstreamNo);
        request.setStatus(1);
        request.setAmount(amount);
        receiptApplicationService.handleReceipt(request, null);
    }

    private MvcResult postOrder(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/orders")
                        .header(HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String body(String userId, String productId, int quantity) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("productId", productId);
        payload.put("quantity", quantity);
        return objectMapper.writeValueAsString(payload);
    }

    private String extractOrderNo(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("orderNo").asText();
    }
}
