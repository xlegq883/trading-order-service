package com.fuzuyang.trading.infrastructure.persistence.mapper;

import com.fuzuyang.trading.domain.enums.OutboxStatus;
import com.fuzuyang.trading.infrastructure.persistence.entity.OutboxDO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox Mapper 异常/边界路径集成测试（H2）。
 *
 * <p>覆盖扫描过滤（status + next_retry_at）、markSent、reschedule、markFailed 与终态不再重试。</p>
 */
@SpringBootTest
@Transactional
class OutboxMapperTest {

    @Autowired
    private OutboxMapper outboxMapper;

    @Test
    void shouldReturnOnlyDuePendingMessages() {
        insert("due", OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        insert("future", OutboxStatus.PENDING, LocalDateTime.now().plusMinutes(10));
        insert("sent", OutboxStatus.SENT, LocalDateTime.now().minusMinutes(1));
        insert("failed", OutboxStatus.FAILED, LocalDateTime.now().minusMinutes(1));

        List<OutboxDO> pending = outboxMapper.selectPending(100);

        assertThat(pending).extracting(OutboxDO::getAggregateId).containsExactly("due");
    }

    @Test
    void shouldMarkSent() {
        Long id = insert("o-sent", OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(1));

        outboxMapper.markSent(id);

        OutboxDO saved = outboxMapper.selectByAggregateId("o-sent").get(0);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.SENT.getCode());
        assertThat(outboxMapper.selectPending(100)).extracting(OutboxDO::getAggregateId)
                .doesNotContain("o-sent");
    }

    @Test
    void shouldRescheduleKeepingPending() {
        Long id = insert("o-retry", OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        LocalDateTime nextRetryAt = LocalDateTime.now().plusMinutes(5);

        outboxMapper.reschedule(id, 3, nextRetryAt);

        OutboxDO saved = outboxMapper.selectByAggregateId("o-retry").get(0);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING.getCode());
        assertThat(saved.getRetryCount()).isEqualTo(3);
        assertThat(saved.getNextRetryAt()).isAfter(LocalDateTime.now().plusMinutes(4));
    }

    @Test
    void shouldMarkFailedAndStopRetrying() {
        Long id = insert("o-failed", OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(1));

        outboxMapper.markFailed(id, 5, LocalDateTime.now());

        OutboxDO saved = outboxMapper.selectByAggregateId("o-failed").get(0);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.FAILED.getCode());
        assertThat(saved.getRetryCount()).isEqualTo(5);
        assertThat(outboxMapper.selectPending(100)).extracting(OutboxDO::getAggregateId)
                .doesNotContain("o-failed");
    }

    private Long insert(String aggregateId, OutboxStatus status, LocalDateTime nextRetryAt) {
        LocalDateTime now = LocalDateTime.now();
        OutboxDO outbox = new OutboxDO();
        outbox.setAggregateType("ORDER");
        outbox.setAggregateId(aggregateId);
        outbox.setPayload("{}");
        outbox.setStatus(status.getCode());
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(nextRetryAt);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
        return outbox.getId();
    }
}
