package com.example.shop.order.vo;

import java.io.Serializable;

import com.example.shop.product.vo.ProductVO;

/**
 * 주문 상세 VO (TB_ORDER_ITEM).
 */
public class OrderItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;
    private Integer itemSeq;
    private Long productId;
    private String productNm;
    /** 상품 (단일 연관) */
    private ProductVO product;
    private int qty;
    private long unitPrice;
    private long amt;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getItemSeq() {
        return itemSeq;
    }

    public void setItemSeq(Integer itemSeq) {
        this.itemSeq = itemSeq;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductNm() {
        return productNm;
    }

    public void setProductNm(String productNm) {
        this.productNm = productNm;
    }

    public ProductVO getProduct() {
        return product;
    }

    public void setProduct(ProductVO product) {
        this.product = product;
    }

    public int getQty() {
        return qty;
    }

    public void setQty(int qty) {
        this.qty = qty;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(long unitPrice) {
        this.unitPrice = unitPrice;
    }

    /** 금액. 따로 넣지 않았으면 수량 × 단가 */
    public long getAmt() {
        return amt > 0 ? amt : qty * unitPrice;
    }

    public void setAmt(long amt) {
        this.amt = amt;
    }
}
