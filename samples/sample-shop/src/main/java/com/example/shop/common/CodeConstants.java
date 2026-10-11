package com.example.shop.common;

/**
 * 공통 상수 모음.
 *
 * 주의: 이 파일은 EUC-KR(MS949) 인코딩으로 저장되어 있다. (레거시 소스 흉내)
 */
public final class CodeConstants {

    /** REST API 경로 접두사. 컨트롤러의 @RequestMapping 에서 상수 연결로 쓴다. */
    public static final String API_PREFIX = "/api";

    public static final String YES = "Y";
    public static final String NO = "N";

    /** 주문 상태: 배송완료 */
    public static final String STATUS_DONE = "40";
    /** 주문 상태: 주문취소 */
    public static final String STATUS_CANCELED = "90";

    /** 시스템 사용자 ID */
    public static final String DEFAULT_USER = "SYSTEM";

    public static final String MSG_NOT_FOUND = "데이터가 존재하지 않습니다.";
    public static final String MSG_STOCK_SHORT = "재고가 부족합니다.";

    private CodeConstants() {
        // 인스턴스 생성 금지
    }

    /** 더 이상 바꿀 수 없는 상태(배송완료, 주문취소)인지 */
    public static boolean isClosed(String statusCode) {
        return STATUS_DONE.equals(statusCode) || STATUS_CANCELED.equals(statusCode);
    }
}
