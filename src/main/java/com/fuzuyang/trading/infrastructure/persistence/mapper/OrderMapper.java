package com.fuzuyang.trading.infrastructure.persistence.mapper;

import com.fuzuyang.trading.infrastructure.persistence.entity.OrderDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单表 t_order 的基础 Mapper（D1–D2 仅提供基础方法）。
 */
@Mapper
public interface OrderMapper {

    /** 插入订单。 */
    int insert(OrderDO order);

    /** 按业务单号查询。 */
    OrderDO selectByOrderNo(@Param("orderNo") String orderNo);

    /** 按幂等键查询，用于幂等判重。 */
    OrderDO selectByIdempotentKey(@Param("idempotentKey") String idempotentKey);

    /** 按业务单号更新状态。 */
    int updateStatus(@Param("orderNo") String orderNo, @Param("status") Integer status);

    /** 查询指定状态且创建时间早于阈值的订单，用于超时回补。 */
    List<OrderDO> selectTimeoutLocked(@Param("status") Integer status,
                                      @Param("beforeTime") LocalDateTime beforeTime,
                                      @Param("limit") int limit);

    /** 按状态查询订单，用于对账扫描。 */
    List<OrderDO> selectByStatus(@Param("status") Integer status, @Param("limit") int limit);
}
