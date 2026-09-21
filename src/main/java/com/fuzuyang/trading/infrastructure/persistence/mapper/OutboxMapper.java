package com.fuzuyang.trading.infrastructure.persistence.mapper;

import com.fuzuyang.trading.infrastructure.persistence.entity.OutboxDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 消息表 t_outbox 的基础 Mapper。
 *
 * <p>投递与指数退避重试逻辑属于 D6。</p>
 */
@Mapper
public interface OutboxMapper {

    /** 插入待投递消息。 */
    int insert(OutboxDO outbox);

    /**
     * 扫描待投递消息：status=0 且 next_retry_at <= now()。
     *
     * <p>当前为单实例扫描。多实例部署下同一行可能被重复投递（至少一次语义），
     * 生产可改用 {@code SELECT ... FOR UPDATE SKIP LOCKED} 或按 id 分片。</p>
     */
    List<OutboxDO> selectPending(@Param("limit") int limit);

    /** 按聚合根 ID 查询消息。 */
    List<OutboxDO> selectByAggregateId(@Param("aggregateId") String aggregateId);

    /** 标记为已发送。 */
    int markSent(@Param("id") Long id);

    /** 重试调度：保持 status=0，更新重试次数与下次重试时间。 */
    int reschedule(@Param("id") Long id,
                   @Param("retryCount") Integer retryCount,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 标记为失败（达到重试上限）。 */
    int markFailed(@Param("id") Long id,
                   @Param("retryCount") Integer retryCount,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt);
}
