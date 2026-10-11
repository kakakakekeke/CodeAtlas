CREATE OR REPLACE PROCEDURE SP_DAILY_SALES_SUM (
    P_BASE_DT  IN VARCHAR2
)
/******************************************************************************
 * 일 매출 집계 (독립 프로시저)
 *   기준일 주문을 상품별로 집계해서 TB_DAILY_SALES 에 다시 적재한다.
 *   예전에는 TB_SALES_TMP 를 거쳤지만 지금은 쓰지 않는다. (주석 속 테이블 이름)
 ******************************************************************************/
IS
    V_JOB_NM  VARCHAR2(100);
    V_CNT     NUMBER := 0;
BEGIN
    V_JOB_NM := FN_GET_CODE_NM('BATCH_JOB', 'DAILY_SALES');

    DELETE FROM TB_DAILY_SALES
     WHERE SALES_DT = P_BASE_DT;

    INSERT INTO TB_DAILY_SALES (SALES_DT, PRODUCT_ID, SALES_QTY, SALES_AMT, REG_DT)
    SELECT O.ORDER_DT,
           I.PRODUCT_ID,
           SUM(I.QTY),
           SUM(I.AMT),
           SYSDATE
      FROM TB_ORDER O
      JOIN TB_ORDER_ITEM I ON I.ORDER_ID = O.ORDER_ID
     WHERE O.ORDER_DT = P_BASE_DT
       AND O.ORDER_STATUS = '40'
     GROUP BY O.ORDER_DT, I.PRODUCT_ID;

    V_CNT := SQL%ROWCOUNT;
    DBMS_OUTPUT.PUT_LINE(V_JOB_NM || ' 집계 건수: ' || V_CNT);

    COMMIT;
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END SP_DAILY_SALES_SUM;
/
