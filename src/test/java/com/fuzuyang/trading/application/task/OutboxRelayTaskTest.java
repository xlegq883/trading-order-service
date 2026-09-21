package com.fuzuyang.trading.application.task;

import com.fuzuyang.trading.infrastructure.persistence.entity.OutboxDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Outbox 投递任务单元测试：成功 / 退避重试 / 达上限。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTaskTest {

    @Mock
    private OutboxMapper outboxMapper;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxRelayTask outboxRelayTask;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outboxRelayTask, "topic", "trading.order.events");
        ReflectionTestUtils.setField(outboxRelayTask, "batchSize", 100);
        ReflectionTestUtils.setField(outboxRelayTask, "sendTimeoutMs", 1000L);
        ReflectionTestUtils.setField(outboxRelayTask, "baseBackoff", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(outboxRelayTask, "maxBackoff", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(outboxRelayTask, "maxRetries", 5);
    }

    @Test
    void shouldMarkSentOnSuccess() {
        when(outboxMapper.selectPending(100)).thenReturn(List.of(message(1L, 0)));
        when(kafkaTemplate.send(eq("trading.order.events"), eq("ON1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        outboxRelayTask.relay();

        verify(outboxMapper).markSent(1L);
        verify(outboxMapper, never()).reschedule(any(), anyInt(), any());
        verify(outboxMapper, never()).markFailed(any(), anyInt(), any());
    }

    @Test
    void shouldRescheduleWithBackoffOnFailure() {
        when(outboxMapper.selectPending(100)).thenReturn(List.of(message(2L, 0)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

        outboxRelayTask.relay();

        ArgumentCaptor<LocalDateTime> nextRetryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxMapper).reschedule(eq(2L), eq(1), nextRetryCaptor.capture());
        // 第 1 次重试退避 5s
        assertThat(nextRetryCaptor.getValue()).isAfter(LocalDateTime.now().plusSeconds(3));
        verify(outboxMapper, never()).markSent(any());
        verify(outboxMapper, never()).markFailed(any(), anyInt(), any());
    }

    @Test
    void shouldMarkFailedWhenReachMaxRetries() {
        when(outboxMapper.selectPending(100)).thenReturn(List.of(message(3L, 4)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

        outboxRelayTask.relay();

        verify(outboxMapper).markFailed(eq(3L), eq(5), any());
        verify(outboxMapper, never()).reschedule(any(), anyInt(), any());
        verify(outboxMapper, never()).markSent(any());
    }

    @Test
    void shouldDoNothingWhenNoPending() {
        when(outboxMapper.selectPending(100)).thenReturn(List.of());

        outboxRelayTask.relay();

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void shouldContinueDeliveringAfterOneFailure() {
        OutboxDO ok = message(1L, 0);
        ok.setAggregateId("OK");
        OutboxDO fail = message(2L, 0);
        fail.setAggregateId("FAIL");
        when(outboxMapper.selectPending(100)).thenReturn(List.of(ok, fail));
        when(kafkaTemplate.send(eq("trading.order.events"), eq("OK"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(kafkaTemplate.send(eq("trading.order.events"), eq("FAIL"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        outboxRelayTask.relay();

        verify(outboxMapper).markSent(1L);
        verify(outboxMapper).reschedule(eq(2L), eq(1), any());
    }

    private OutboxDO message(Long id, int retryCount) {
        OutboxDO message = new OutboxDO();
        message.setId(id);
        message.setAggregateId("ON1");
        message.setPayload("{}");
        message.setStatus(0);
        message.setRetryCount(retryCount);
        message.setNextRetryAt(LocalDateTime.now());
        return message;
    }
}
