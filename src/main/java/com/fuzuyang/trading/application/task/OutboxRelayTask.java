package com.fuzuyang.trading.application.task;

import com.fuzuyang.trading.infrastructure.persistence.entity.OutboxDO;
import com.fuzuyang.trading.infrastructure.persistence.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 投递任务：扫描待发送消息投递 Kafka，失败按指数退避重试。
 *
 * <p>投递语义为「至少一次」：发送成功但更新状态前崩溃会重复投递，
 * 由消费端按业务唯一 id 幂等来达到「恰好一次」效果。</p>
 *
 * <p>可通过 {@code app.outbox.relay-enabled=false} 关闭（测试环境）。</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayTask {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayTask.class);

    private final OutboxMapper outboxMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.outbox.topic:trading.order.events}")
    private String topic;

    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    @Value("${app.outbox.send-timeout-ms:3000}")
    private long sendTimeoutMs;

    @Value("${app.outbox.base-backoff:PT5S}")
    private Duration baseBackoff;

    @Value("${app.outbox.max-backoff:PT5M}")
    private Duration maxBackoff;

    @Value("${app.outbox.max-retries:5}")
    private int maxRetries;

    public OutboxRelayTask(OutboxMapper outboxMapper, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxMapper = outboxMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.scan-interval-ms:5000}")
    public void relay() {
        List<OutboxDO> pending = outboxMapper.selectPending(batchSize);
        if (pending.isEmpty()) {
            return;
        }
        // 逐条投递，单条失败不影响同批其他消息
        for (OutboxDO message : pending) {
            deliver(message);
        }
    }

    private void deliver(OutboxDO message) {
        try {
            kafkaTemplate.send(topic, message.getAggregateId(), message.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            outboxMapper.markSent(message.getId());
            log.info("Outbox 投递成功：id={}, aggregateId={}", message.getId(), message.getAggregateId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Outbox 投递被中断：id={}", message.getId());
        } catch (Exception ex) {
            handleFailure(message, ex);
        }
    }

    private void handleFailure(OutboxDO message, Exception ex) {
        int nextRetry = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
        if (nextRetry >= maxRetries) {
            outboxMapper.markFailed(message.getId(), nextRetry, LocalDateTime.now());
            log.error("Outbox 投递达到重试上限，标记 FAILED：id={}, aggregateId={}, retry={}, error={}",
                    message.getId(), message.getAggregateId(), nextRetry, ex.getMessage());
        } else {
            Duration delay = backoff(nextRetry);
            outboxMapper.reschedule(message.getId(), nextRetry, LocalDateTime.now().plus(delay));
            log.warn("Outbox 投递失败，{} 后重试：id={}, retry={}, error={}",
                    delay, message.getId(), nextRetry, ex.getMessage());
        }
    }

    /** 指数退避：min(base × 2^(retryCount-1), maxBackoff)。 */
    private Duration backoff(int retryCount) {
        long millis = baseBackoff.toMillis() * (1L << (retryCount - 1));
        return Duration.ofMillis(Math.min(millis, maxBackoff.toMillis()));
    }
}
