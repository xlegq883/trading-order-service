package com.fuzuyang.trading.infrastructure.persistence.mapper;

import com.fuzuyang.trading.infrastructure.persistence.entity.ProductDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 商品表 t_product 的 Mapper。
 */
@Mapper
public interface ProductMapper {

    /** 按商品 ID 查询。 */
    ProductDO selectByProductId(@Param("productId") String productId);

    /** 更新商品名称与价格。 */
    int update(ProductDO product);
}
