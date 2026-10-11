package com.example.shop.product.service;

import static com.example.shop.common.CodeConstants.MSG_NOT_FOUND;
import static com.example.shop.common.CodeConstants.MSG_STOCK_SHORT;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.shop.common.BizException;
import com.example.shop.product.mapper.ProductMapper;
import com.example.shop.product.vo.CategoryVO;
import com.example.shop.product.vo.ProductVO;

/**
 * 상품/재고 서비스. 인터페이스 없이 클래스 하나로 되어 있다. (빈 이름은 기본값 productService)
 */
@Service
public class ProductService {

    @Autowired
    private ProductMapper productMapper;

    public List<ProductVO> getProducts(Map<String, Object> param) {
        return productMapper.selectProductList(param);
    }

    public ProductVO getProduct(Long productId) {
        ProductVO product = productMapper.selectProduct(productId);
        if (product == null) {
            throw new BizException(MSG_NOT_FOUND);
        }
        return product;
    }

    /** 평면 목록을 트리로 조립한다. */
    public List<CategoryVO> getCategoryTree() {
        List<CategoryVO> flat = productMapper.selectCategoryTree();
        Map<Integer, CategoryVO> byId = new LinkedHashMap<>();
        for (CategoryVO category : flat) {
            byId.put(category.getCategoryId(), category);
        }

        List<CategoryVO> roots = new ArrayList<>();
        for (CategoryVO category : flat) {
            CategoryVO parent = byId.get(category.getParentId());
            if (parent == null) {
                roots.add(category);
            } else {
                category.setParent(parent);
                parent.getChildren().add(category);
            }
        }

        // 메서드 참조 (this::)
        roots.forEach(this::sortChildren);
        return roots;
    }

    /** 재귀 호출 */
    private void sortChildren(CategoryVO node) {
        node.getChildren().sort(Comparator.comparing(CategoryVO::getCategoryNm));
        for (CategoryVO child : node.getChildren()) {
            sortChildren(child);
        }
    }

    public void decreaseStock(Long productId, int qty) {
        int updated = productMapper.decreaseStock(productId, qty);
        if (updated == 0) {
            throw new BizException("E200", MSG_STOCK_SHORT);
        }
    }

    public void increaseStock(Long productId, int qty) {
        productMapper.mergeStock(productId, qty);
    }

    public List<ProductVO> getLowStockProducts(int limit) {
        return productMapper.selectLowStockProducts(limit);
    }

    public List<Map<String, Object>> getBestSellers() {
        return productMapper.selectBestSellers();
    }

    public ProductVO register(ProductVO product) {
        productMapper.insertProduct(product);
        increaseStock(product.getProductId(), 0);
        return product;
    }
}
