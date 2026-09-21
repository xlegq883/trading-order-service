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

    /** 扫描待投递消息：status=0 且 next_retry_at <= now()。 */
    List<OutboxDO> selectPending(@Param("limit") int limit);

    /** 标记为已发送。 */
    int markSent(@Param("id") Long id);

    /** 标记为失败并更新重试次数与下次重试时间。 */
    int markFailed(@Param("id") Long id,
                   @Param("retryCount") Integer retryCount,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt);
}
