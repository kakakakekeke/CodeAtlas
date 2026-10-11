package com.example.shop.batch.vo;

/**
 * 일 매출 집계 항목 (TB_DAILY_SALES 에 MERGE 할 입력).
 */
public class DailySalesVO {

    /** 매출일자 YYYYMMDD */
    private String salesDt;
    private Long orderId;
    private long salesAmt;

    public String getSalesDt() {
        return salesDt;
    }

    public void setSalesDt(String salesDt) {
        this.salesDt = salesDt;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public long getSalesAmt() {
        return salesAmt;
    }

    public void setSalesAmt(long salesAmt) {
        this.salesAmt = salesAmt;
    }
}
