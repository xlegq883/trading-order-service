package com.fuzuyang.trading.infrastructure.persistence.mapper;

import com.fuzuyang.trading.infrastructure.persistence.entity.StockDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 库存表 t_stock 的基础 Mapper。
 *
 * <p>扣减采用乐观锁（version）兜底，D5 会在其上加 Redis Lua 预扣。</p>
 */
@Mapper
public interface StockMapper {

    /** 按商品 ID 查询库存。 */
    StockDO selectByProductId(@Param("productId") String productId);

    /**
     * 乐观锁扣减库存：仅当版本号匹配且可用量充足时成功。
     *
     * @return 影响行数，0 表示扣减失败（版本冲突或库存不足）
     */
    int deductWithVersion(@Param("productId") String productId,
                          @Param("quantity") Integer quantity,
                          @Param("version") Integer version);

    /** 回补库存（超时未支付等场景）。 */
    int restore(@Param("productId") String productId, @Param("quantity") Integer quantity);
}
