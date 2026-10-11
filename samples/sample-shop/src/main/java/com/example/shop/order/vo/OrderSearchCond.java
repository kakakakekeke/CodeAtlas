package com.example.shop.order.vo;

import java.util.List;

/**
 * 주문 검색 조건 DTO. 매퍼 XML 에서 dateRange.from 처럼 중첩 프로퍼티로 읽는다.
 */
public class OrderSearchCond {

    private String memberNm;
    private Long memberId;
    private List<String> statusList;
    private DateRange dateRange;
    /** ORDER BY 절에 ${sortColumn} 으로 그대로 들어간다 */
    private String sortColumn = "O.ORDER_ID DESC";
    private int startRow;
    private int endRow;

    /** 기간 조건 (정적 중첩 클래스) */
    public static class DateRange {

        private String from;
        private String to;

        public DateRange() {
        }

        public DateRange(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }
    }

    public String getMemberNm() {
        return memberNm;
    }

    public void setMemberNm(String memberNm) {
        this.memberNm = memberNm;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public List<String> getStatusList() {
        return statusList;
    }

    public void setStatusList(List<String> statusList) {
        this.statusList = statusList;
    }

    public DateRange getDateRange() {
        return dateRange;
    }

    public void setDateRange(DateRange dateRange) {
        this.dateRange = dateRange;
    }

    public String getSortColumn() {
        return sortColumn;
    }

    public void setSortColumn(String sortColumn) {
        this.sortColumn = sortColumn;
    }

    public int getStartRow() {
        return startRow;
    }

    public void setStartRow(int startRow) {
        this.startRow = startRow;
    }

    public int getEndRow() {
        return endRow;
    }

    public void setEndRow(int endRow) {
        this.endRow = endRow;
    }
}
