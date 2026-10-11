package com.example.shop.product.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.example.shop.product.vo.CategoryVO;
import com.example.shop.product.vo.ProductVO;

/**
 * 상품/재고 매퍼. SQL 은 mapper/product/ProductMapper.xml 에 있다.
 */
@Mapper
public interface ProductMapper {

    List<CategoryVO> selectCategoryTree();

    List<ProductVO> selectProductList(Map<String, Object> param);

    ProductVO selectProduct(Long productId);

    int mergeStock(@Param("productId") Long productId, @Param("qty") int qty);

    int decreaseStock(@Param("productId") Long productId, @Param("qty") int qty);

    List<ProductVO> selectLowStockProducts(int limit);

    List<Map<String, Object>> selectBestSellers();

    int insertProduct(ProductVO product);
}
