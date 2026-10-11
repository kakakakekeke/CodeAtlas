CREATE OR REPLACE PACKAGE BODY PKG_ORDER AS
/******************************************************************************
 * 주문 마감/취소 처리 패키지 (본문)
 ******************************************************************************/

    ---------------------------------------------------------------------------
    -- 주문 이력 기록 (패키지 내부 전용, 명세에는 없다)
    ---------------------------------------------------------------------------
    PROCEDURE WRITE_HIST (
        P_ORDER_ID  IN NUMBER,
        P_STATUS    IN VARCHAR2,
        P_USER_ID   IN VARCHAR2
    ) IS
    BEGIN
        INSERT INTO TB_ORDER_HIST (HIST_ID, ORDER_ID, ORDER_STATUS, CHG_DT, CHG_ID)
        VALUES (SEQ_ORDER_HIST_ID.NEXTVAL, P_ORDER_ID, P_STATUS, SYSDATE, P_USER_ID);
    END WRITE_HIST;

    ---------------------------------------------------------------------------
    -- 주문 금액 계산
    ---------------------------------------------------------------------------
    FUNCTION GET_ORDER_AMT (P_ORDER_ID IN NUMBER) RETURN NUMBER IS
        V_AMT  NUMBER := 0;
    BEGIN
        SELECT NVL(SUM(I.QTY * I.UNIT_PRICE), 0)
          INTO V_AMT
          FROM TB_ORDER_ITEM I
         WHERE I.ORDER_ID = P_ORDER_ID;

        RETURN V_AMT;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RETURN 0;
    END GET_ORDER_AMT;

    ---------------------------------------------------------------------------
    -- 주문 1건 마감
    ---------------------------------------------------------------------------
    PROCEDURE CLOSE_ORDER (
        P_ORDER_ID   IN  NUMBER,
        P_USER_ID    IN  VARCHAR2,
        P_RESULT_CD  OUT VARCHAR2
    ) IS
        V_STATUS  TB_ORDER.ORDER_STATUS%TYPE;
        V_AMT     NUMBER;
    BEGIN
        SELECT O.ORDER_STATUS
          INTO V_STATUS
          FROM TB_ORDER O
         WHERE O.ORDER_ID = P_ORDER_ID
           FOR UPDATE NOWAIT;

        IF V_STATUS = C_STATUS_CANCEL THEN
            P_RESULT_CD := 'E02';
            RETURN;
        END IF;

        V_AMT := GET_ORDER_AMT(P_ORDER_ID);

        UPDATE TB_ORDER
           SET CLOSE_YN     = 'Y',
               TOTAL_AMT    = V_AMT,
               ORDER_STATUS = C_STATUS_DONE,
               UPD_ID       = P_USER_ID,
               UPD_DT       = SYSDATE
         WHERE ORDER_ID = P_ORDER_ID;

        WRITE_HIST(P_ORDER_ID, C_STATUS_DONE, P_USER_ID);

        P_RESULT_CD := '00';
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            P_RESULT_CD := 'E01';
        WHEN OTHERS THEN
            ROLLBACK;
            P_RESULT_CD := 'E99';
            DBMS_OUTPUT.PUT_LINE('CLOSE_ORDER 실패: ' || SQLERRM);
    END CLOSE_ORDER;

    ---------------------------------------------------------------------------
    -- 기준일 주문 일괄 마감 (커서 FOR 루프)
    ---------------------------------------------------------------------------
    PROCEDURE CLOSE_DAILY (
        P_BASE_DT     IN  VARCHAR2,
        P_CLOSED_CNT  OUT NUMBER
    ) IS
        V_RESULT  VARCHAR2(3);
        V_CNT     PLS_INTEGER := 0;
    BEGIN
        FOR R IN (SELECT O.ORDER_ID
                    FROM TB_ORDER O,
                         TB_DELIVERY D
                   WHERE O.ORDER_ID = D.ORDER_ID
                     AND D.DELIVERY_STATUS = 'DONE'
                     AND O.ORDER_DT = P_BASE_DT
                     AND O.CLOSE_YN = 'N')
        LOOP
            CLOSE_ORDER(R.ORDER_ID, 'BATCH', V_RESULT);
            IF V_RESULT = '00' THEN
                V_CNT := V_CNT + 1;
            END IF;
        END LOOP;

        -- 독립 프로시저 호출
        SP_DAILY_SALES_SUM(P_BASE_DT);

        P_CLOSED_CNT := V_CNT;
        COMMIT;
    END CLOSE_DAILY;

    ---------------------------------------------------------------------------
    -- 주문 취소 (BULK COLLECT, FORALL, 명시적 커서)
    ---------------------------------------------------------------------------
    PROCEDURE CANCEL_ORDER (
        P_ORDER_ID  IN NUMBER,
        P_USER_ID   IN VARCHAR2
    ) IS
        CURSOR C_PAY IS
            SELECT P.PAYMENT_ID, P.PAY_AMT
              FROM TB_PAYMENT P
             WHERE P.ORDER_ID = P_ORDER_ID
               AND P.PAY_STATUS = 'DONE';

        V_PRODUCT_IDS  T_NUM_TAB;
        V_QTYS         T_NUM_TAB;
    BEGIN
        SELECT I.PRODUCT_ID, I.QTY
          BULK COLLECT INTO V_PRODUCT_IDS, V_QTYS
          FROM TB_ORDER_ITEM I
         WHERE I.ORDER_ID = P_ORDER_ID;

        FORALL IDX IN 1 .. V_PRODUCT_IDS.COUNT
            UPDATE TB_STOCK S
               SET S.STOCK_QTY = S.STOCK_QTY + V_QTYS(IDX),
                   S.UPD_DT    = SYSDATE
             WHERE S.PRODUCT_ID = V_PRODUCT_IDS(IDX);

        FOR R_PAY IN C_PAY LOOP
            UPDATE TB_PAYMENT
               SET PAY_STATUS = 'CANCEL'
             WHERE PAYMENT_ID = R_PAY.PAYMENT_ID;
        END LOOP;

        UPDATE TB_ORDER
           SET ORDER_STATUS = C_STATUS_CANCEL,
               UPD_ID       = P_USER_ID,
               UPD_DT       = SYSDATE
         WHERE ORDER_ID = P_ORDER_ID;

        WRITE_HIST(P_ORDER_ID, C_STATUS_CANCEL, P_USER_ID);
    EXCEPTION
        WHEN OTHERS THEN
            RAISE_APPLICATION_ERROR(-20001, '주문 취소 실패: ' || SQLERRM);
    END CANCEL_ORDER;

    ---------------------------------------------------------------------------
    -- 마감 주문 보관 테이블 이관
    --   보관 테이블 이름이 실행 시점에 정해지므로 정적 분석으로 풀 수 없다.
    ---------------------------------------------------------------------------
    PROCEDURE ARCHIVE_ORDERS (P_BASE_DT IN VARCHAR2) IS
        V_TABLE  VARCHAR2(30);
        V_SQL    VARCHAR2(1000);
    BEGIN
        V_TABLE := 'TB_ORDER_ARC_' || SUBSTR(P_BASE_DT, 1, 4);
        V_SQL   := 'INSERT INTO ' || V_TABLE
                || ' SELECT * FROM TB_ORDER WHERE ORDER_DT < :1 AND CLOSE_YN = ''Y''';

        EXECUTE IMMEDIATE V_SQL USING P_BASE_DT;

        -- 여기부터는 정적 SQL
        DELETE FROM TB_ORDER_ITEM
         WHERE ORDER_ID IN (SELECT ORDER_ID
                              FROM TB_ORDER
                             WHERE ORDER_DT < P_BASE_DT
                               AND CLOSE_YN = 'Y');
    END ARCHIVE_ORDERS;

END PKG_ORDER;
/
