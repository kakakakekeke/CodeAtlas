--#SET TERMINATOR @
-- =====================================================================
-- 배치 공통 프로시저 (DB2 SQL PL). 문장 종결자는 @ 이다.
-- =====================================================================

-- 배치 로그 기록
CREATE OR REPLACE PROCEDURE SP_WRITE_BATCH_LOG (
    IN P_JOB_NM   VARCHAR(100),
    IN P_STEP_NM  VARCHAR(100),
    IN P_STATUS   CHAR(2),
    IN P_MSG      VARCHAR(1000)
)
LANGUAGE SQL
MODIFIES SQL DATA
BEGIN ATOMIC
    INSERT INTO BATCH.TB_BATCH_LOG (JOB_NM, STEP_NM, STATUS, MSG, REG_TS)
    VALUES (P_JOB_NM, P_STEP_NM, P_STATUS, P_MSG, CURRENT TIMESTAMP);
END@

-- 보관 기간이 지난 행 삭제.
-- 테이블 이름을 인자로 받아 동적 SQL 로 실행하므로 대상 테이블을 정적으로 알 수 없다.
CREATE OR REPLACE PROCEDURE SP_PURGE_TABLE (
    IN  P_TABLE_NM   VARCHAR(128),
    IN  P_KEEP_DAYS  INTEGER,
    OUT P_DELETED    INTEGER
)
LANGUAGE SQL
BEGIN
    DECLARE V_SQL  VARCHAR(1000);
    DECLARE V_CNT  INTEGER DEFAULT 0;
    DECLARE S1 STATEMENT;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        CALL SP_WRITE_BATCH_LOG('PURGE', P_TABLE_NM, 'ER', 'purge failed');
        SET P_DELETED = -1;
    END;

    SET V_SQL = 'DELETE FROM ' || P_TABLE_NM
             || ' WHERE REG_TS < CURRENT TIMESTAMP - ' || CHAR(P_KEEP_DAYS) || ' DAYS';

    PREPARE S1 FROM V_SQL;
    EXECUTE S1;

    GET DIAGNOSTICS V_CNT = ROW_COUNT;
    SET P_DELETED = V_CNT;

    CALL SP_WRITE_BATCH_LOG('PURGE', P_TABLE_NM, 'OK', 'deleted=' || CHAR(V_CNT));
END@
