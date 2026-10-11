package com.example.shop.order.vo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.shop.common.BaseVO;
import com.example.shop.common.OrderStatus;
import com.example.shop.member.vo.MemberVO;

/**
 * 주문 VO (TB_ORDER).
 * 필드 연관: 회원(단일), 주문상세(List), 상태(enum), 부가정보(Map).
 */
public class OrderVO extends BaseVO {

    private static final long serialVersionUID = 1L;

    private Long orderId;
    private Long memberId;
    /** 주문자 (resultMap 의 association) */
    private MemberVO member;
    /** 주문 상세 (resultMap 의 collection) */
    private List<OrderItemVO> items = new ArrayList<>();
    /** 주문일자 YYYYMMDD */
    private String orderDt;
    /** 주문 상태 코드 (TB_ORDER.ORDER_STATUS) */
    private String statusCode;
    private OrderStatus status;
    private Long totalAmt;
    private String closeYn;
    /** 화면 부가 정보 */
    private Map<String, Object> extra = new HashMap<>();

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public MemberVO getMember() {
        return member;
    }

    public void setMember(MemberVO member) {
        this.member = member;
    }

    public List<OrderItemVO> getItems() {
        return items;
    }

    public void setItems(List<OrderItemVO> items) {
        this.items = items;
    }

    public String getOrderDt() {
        return orderDt;
    }

    public void setOrderDt(String orderDt) {
        this.orderDt = orderDt;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Long getTotalAmt() {
        return totalAmt;
    }

    public void setTotalAmt(Long totalAmt) {
        this.totalAmt = totalAmt;
    }

    public String getCloseYn() {
        return closeYn;
    }

    public void setCloseYn(String closeYn) {
        this.closeYn = closeYn;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }
}
