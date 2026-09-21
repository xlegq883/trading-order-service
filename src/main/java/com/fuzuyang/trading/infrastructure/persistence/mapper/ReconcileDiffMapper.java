package com.fuzuyang.trading.infrastructure.persistence.mapper;

import com.fuzuyang.trading.infrastructure.persistence.entity.ReconcileDiffDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对账差异表 t_reconcile_diff 的 Mapper。
 */
@Mapper
public interface ReconcileDiffMapper {

    /** 记录差异。 */
    int insert(ReconcileDiffDO diff);

    /** 按订单号查询差异。 */
    List<ReconcileDiffDO> selectByOrderNo(@Param("orderNo") String orderNo);
}
