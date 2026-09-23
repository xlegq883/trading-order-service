package com.fuzuyang.trading.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.trading.application.port.ProductCache;
import com.fuzuyang.trading.infrastructure.persistence.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品接口集成测试（H2 + 模拟缓存）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductMapper productMapper;

    @MockBean
    private ProductCache productCache;

    @BeforeEach
    void stubCacheMiss() {
        lenient().when(productCache.get(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void shouldReturnProductDetailFromDbOnCacheMiss() throws Exception {
        mockMvc.perform(get("/api/products/P1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.productId").value("P1001"))
                .andExpect(jsonPath("$.data.name").value("商品A"))
                .andExpect(jsonPath("$.data.price").value(100.00));
    }

    @Test
    void shouldReturn404WhenProductMissing() throws Exception {
        mockMvc.perform(get("/api/products/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void shouldUpdateProductAndEvictCache() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "新商品A");
        body.put("price", 88.00);

        mockMvc.perform(put("/api/products/P1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(productMapper.selectByProductId("P1001").getName()).isEqualTo("新商品A");
        verify(productCache).evict("product:P1001");
    }
}
