package com.fuzuyang.trading.perf;

import com.fuzuyang.trading.application.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发本地集成测试（真实 MySQL 3307 + Redis 6379）。
 *
 * <p>默认被 surefire 的 *IT 规则排除；显式运行：
 * {@code mvn -Dfile.encoding=UTF-8 -Dtest=OrderConcurrencyLocalIT test}</p>
 *
 * <p><b>注意</b>：会读写本地测试库的 t_order / t_outbox / t_receipt / t_stock，
 * 只清理本测试商品 IT_P1 的数据，不影响其它商品。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.outbox.relay-enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
@ActiveProfiles("local")
@Tag("local-it")
class OrderConcurrencyLocalIT {

    private static final String PRODUCT = "IT_P1";
    private static final int STOCK = 100;
    private static final int THREADS = 200;
    private static final Pattern ORDER_NO = Pattern.compile("\"orderNo\"\\s*:\\s*\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockService stockService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void resetFixture() {
        jdbc.update("DELETE FROM t_outbox WHERE aggregate_id IN (SELECT order_no FROM t_order WHERE product_id = ?)", PRODUCT);
        jdbc.update("DELETE FROM t_receipt WHERE order_no IN (SELECT order_no FROM t_order WHERE product_id = ?)", PRODUCT);
        jdbc.update("DELETE FROM t_order WHERE product_id = ?", PRODUCT);
        jdbc.update("INSERT INTO t_stock(product_id, total, available, version) VALUES(?, ?, ?, 0) "
                + "ON DUPLICATE KEY UPDATE total = VALUES(total), available = VALUES(available), version = 0",
                PRODUCT, STOCK, STOCK);
        // 重新预热 Redis 库存键（key 不存在时应用也会自动从 DB 初始化）
        stockService.warmUp();
    }

    @Test
    @DisplayName("200 并发不同幂等键 -> 不超卖：成功单数 == 库存")
    void shouldNotOversell() throws Exception {
        List<String> keys = IntStream.range(0, THREADS)
                .mapToObj(i -> "IT-A-" + UUID.randomUUID())
                .collect(Collectors.toList());

        Result result = fire(keys, 1);

        long ok = result.responses.stream().filter(r -> r.http == 200).count();
        long insufficient = result.responses.stream().filter(r -> r.http == 422).count();

        Integer available = jdbc.queryForObject("SELECT available FROM t_stock WHERE product_id = ?", Integer.class, PRODUCT);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM t_order WHERE product_id = ?", Integer.class, PRODUCT);
        Integer outbox = jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox", Integer.class);

        System.out.printf("[并发-不同键] 成功=%d 库存不足=%d 其它=%d 订单行=%d 剩余库存=%d outbox=%d 耗时=%dms QPS≈%.0f P99=%dms%n",
                ok, insufficient, THREADS - ok - insufficient, orders, available, outbox,
                result.elapsedMs, result.qps(), result.p99Ms());

        assertThat(ok).isEqualTo(STOCK);
        assertThat(available).isZero();
        assertThat(orders).isEqualTo(STOCK);
        assertThat(result.responses.stream().filter(r -> r.http != 200 && r.http != 422).count()).isZero();
    }

    @Test
    @DisplayName("200 并发同一幂等键 -> 只落 1 单")
    void shouldCreateOnlyOneOrderForSameKey() throws Exception {
        String key = "IT-B-" + UUID.randomUUID();
        List<String> keys = IntStream.range(0, THREADS).mapToObj(i -> key).collect(Collectors.toList());

        Result result = fire(keys, 1);

        List<String> orderNos = result.responses.stream()
                .filter(r -> r.http == 200 && r.orderNo != null)
                .map(r -> r.orderNo)
                .distinct()
                .collect(Collectors.toList());
        long conflict = result.responses.stream().filter(r -> r.http == 409).count();

        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM t_order WHERE product_id = ?", Integer.class, PRODUCT);
        Integer available = jdbc.queryForObject("SELECT available FROM t_stock WHERE product_id = ?", Integer.class, PRODUCT);

        System.out.printf("[并发-同键] 200=%d 409=%d 不同单号数=%d 订单行=%d 剩余库存=%d 耗时=%dms QPS≈%.0f P99=%dms%n",
                THREADS - conflict, conflict, orderNos.size(), orders, available,
                result.elapsedMs, result.qps(), result.p99Ms());

        assertThat(orders).isEqualTo(1);
        assertThat(orderNos).hasSize(1);
        assertThat(available).isEqualTo(STOCK - 1);
    }

    private Result fire(List<String> keys, int quantity) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(keys.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < keys.size(); i++) {
                final int idx = i;
                final String key = keys.get(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return call(key, "U-IT-" + idx, quantity);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            long begin = System.nanoTime();
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            long elapsedMs = Math.max(1, (System.nanoTime() - begin) / 1_000_000);
            return new Result(responses, elapsedMs);
        } finally {
            pool.shutdownNow();
        }
    }

    private Response call(String idempotencyKey, String userId, int quantity) throws Exception {
        String body = String.format("{\"userId\":\"%s\",\"productId\":\"%s\",\"quantity\":%d}", userId, PRODUCT, quantity);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("x-idempotency-key", idempotencyKey)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        // 200 并发首连时 JDK HttpClient 连接池存在瞬时竞态（ClosedChannelException），
        // 下单接口幂等，故对传输层 IOException 做有限重试，避免偶发网络错误污染正确性断言。
        IOException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            long begin = System.nanoTime();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long costMs = (System.nanoTime() - begin) / 1_000_000;
                Matcher matcher = ORDER_NO.matcher(response.body());
                String orderNo = matcher.find() ? matcher.group(1) : null;
                return new Response(response.statusCode(), orderNo, costMs);
            } catch (IOException ex) {
                lastError = ex;
                Thread.sleep(50L * attempt);
            }
        }
        throw lastError;
    }

    private record Response(int http, String orderNo, long costMs) {
    }

    private record Result(List<Response> responses, long elapsedMs) {

        double qps() {
            return responses.size() * 1000.0 / elapsedMs;
        }

        long p99Ms() {
            List<Long> costs = responses.stream().map(Response::costMs).sorted().collect(Collectors.toList());
            int index = Math.min(costs.size() - 1, (int) Math.ceil(costs.size() * 0.99) - 1);
            return costs.isEmpty() ? 0 : costs.get(Math.max(0, index));
        }
    }
}
