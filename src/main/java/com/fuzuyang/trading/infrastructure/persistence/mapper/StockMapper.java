package com.fuzuyang.trading.infrastructure.persistence.mapper;

import com.fuzuyang.trading.infrastructure.persistence.entity.StockDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 库存表 t_stock 的基础 Mapper。
 *
 * <p>扣减采用条件原子更新（行锁 + available &gt;= quantity）防超卖，
 * Redis Lua 预扣是其上的并发闸门。</p>
 */
@Mapper
public interface StockMapper {

    /** 按商品 ID 查询库存。 */
    StockDO selectByProductId(@Param("productId") String productId);

    /** 查询全部库存，用于启动预热。 */
    List<StockDO> selectAll();

    /**
     * 条件原子扣减：仅当可用量充足时成功，version 自增。
     *
     * @return 影响行数，0 表示库存不足
     */
    int deduct(@Param("productId") String productId, @Param("quantity") Integer quantity);

    /** 回补库存（超时未支付等场景）。 */
    int restore(@Param("productId") String productId, @Param("quantity") Integer quantity);
}
