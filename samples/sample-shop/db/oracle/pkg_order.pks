CREATE OR REPLACE PACKAGE PKG_ORDER AS
/******************************************************************************
 * 주문 마감/취소 처리 패키지 (명세)
 *   - CLOSE_ORDER    : 주문 1건 마감
 *   - CLOSE_DAILY    : 기준일 주문 일괄 마감 (배치에서 호출)
 *   - CANCEL_ORDER   : 주문 취소 (재고 복원, 결제 취소)
 *   - ARCHIVE_ORDERS : 마감 주문 보관 테이블 이관 (동적 SQL)
 *   - GET_ORDER_AMT  : 주문 금액 계산
 ******************************************************************************/

    C_STATUS_DONE    CONSTANT VARCHAR2(2) := '40';
    C_STATUS_CANCEL  CONSTANT VARCHAR2(2) := '90';

    -- "TABLE OF" 는 컬렉션 타입이다. 테이블이 아니다.
    TYPE T_NUM_TAB IS TABLE OF NUMBER INDEX BY PLS_INTEGER;

    PROCEDURE CLOSE_ORDER (
        P_ORDER_ID   IN  NUMBER,
        P_USER_ID    IN  VARCHAR2,
        P_RESULT_CD  OUT VARCHAR2
    );

    PROCEDURE CLOSE_DAILY (
        P_BASE_DT     IN  VARCHAR2,
        P_CLOSED_CNT  OUT NUMBER
    );

    PROCEDURE CANCEL_ORDER (
        P_ORDER_ID  IN NUMBER,
        P_USER_ID   IN VARCHAR2
    );

    PROCEDURE ARCHIVE_ORDERS (P_BASE_DT IN VARCHAR2);

    FUNCTION GET_ORDER_AMT (P_ORDER_ID IN NUMBER) RETURN NUMBER;

END PKG_ORDER;
/
