package com.example.shop.common;

/**
 * 주문 상태 (TB_CODE 의 GROUP_CD = 'ORDER_STATUS' 와 같은 값).
 */
public enum OrderStatus {

    ORDERED("10", "주문접수"),
    PAID("20", "결제완료"),
    SHIPPED("30", "배송중"),
    DONE("40", "배송완료"),
    CANCELED("90", "주문취소");

    private final String code;
    private final String label;

    OrderStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 코드 값으로 상수를 찾는다. 없으면 null. */
    public static OrderStatus fromCode(String code) {
        for (OrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
