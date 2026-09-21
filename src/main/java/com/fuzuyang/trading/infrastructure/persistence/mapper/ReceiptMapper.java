package com.fuzuyang.trading.infrastructure.persistence.mapper;

import com.fuzuyang.trading.infrastructure.persistence.entity.ReceiptDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 上游回执表 t_receipt 的基础 Mapper。
 */
@Mapper
public interface ReceiptMapper {

    /** 插入回执。 */
    int insert(ReceiptDO receipt);

    /** 按订单号查询回执。 */
    ReceiptDO selectByOrderNo(@Param("orderNo") String orderNo);
}
