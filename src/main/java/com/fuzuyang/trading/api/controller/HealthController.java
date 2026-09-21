package com.fuzuyang.trading.api.controller;

import com.fuzuyang.trading.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口。
 *
 * <p>该接口不依赖 MySQL / Redis / Kafka，任何外部依赖不可用时都应返回 200，
 * 保证工程在依赖未启动的情况下也能启动并完成演示。</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final Environment environment;

    @Value("${spring.application.name:trading-order-service}")
    private String applicationName;

    public HealthController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", applicationName);
        data.put("status", "UP");
        data.put("profiles", Arrays.asList(environment.getActiveProfiles()));
        data.put("timestamp", OffsetDateTime.now().toString());
        return ApiResponse.ok(data);
    }
}
