package com.example.shop.product.vo;

import com.example.shop.common.BaseVO;

/**
 * 상품 VO (TB_PRODUCT, 재고 수량 포함).
 */
public class ProductVO extends BaseVO {

    private static final long serialVersionUID = 1L;

    private Long productId;
    private Integer categoryId;
    private String categoryNm;
    /** 카테고리 (단일 연관) */
    private CategoryVO category;
    private String productNm;
    private long price;
    private String statusCd;
    private int stockQty;
    private int safeQty;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryNm() {
        return categoryNm;
    }

    public void setCategoryNm(String categoryNm) {
        this.categoryNm = categoryNm;
    }

    public CategoryVO getCategory() {
        return category;
    }

    public void setCategory(CategoryVO category) {
        this.category = category;
    }

    public String getProductNm() {
        return productNm;
    }

    public void setProductNm(String productNm) {
        this.productNm = productNm;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public String getStatusCd() {
        return statusCd;
    }

    public void setStatusCd(String statusCd) {
        this.statusCd = statusCd;
    }

    public int getStockQty() {
        return stockQty;
    }

    public void setStockQty(int stockQty) {
        this.stockQty = stockQty;
    }

    public int getSafeQty() {
        return safeQty;
    }

    public void setSafeQty(int safeQty) {
        this.safeQty = safeQty;
    }
}
