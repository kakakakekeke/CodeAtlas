package io.codeatlas.sql.analysis;

import static io.codeatlas.sql.analysis.FactsTestSupport.joins;
import static io.codeatlas.sql.analysis.FactsTestSupport.readColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.reads;
import static io.codeatlas.sql.analysis.FactsTestSupport.sequences;
import static io.codeatlas.sql.analysis.FactsTestSupport.writeColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.writes;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.parser.SqlParser;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 대상 방언(Oracle, DB2 LUW)의 구문 보강 점검(F-006-R34). 구문마다 문제(problem) 없이 파싱되고 테이블·컬럼·조인이 맞게 나오는지 고정한다.
 * 카탈로그 없이 본다: 컬럼은 별칭으로 한정했거나 스코프에 테이블이 하나뿐일 때만 나온다.
 */
class OracleDb2SyntaxTest {

    /** 기대 컬럼 목록을 정렬해 쉼표로 잇는다(표를 읽기 쉽게 순서 없이 적는다). */
    private static String cols(String... columns) {
        return Arrays.stream(columns).sorted().collect(Collectors.joining(","));
    }

    private static Arguments row(String label, String sql, String reads, String writes, String joins, String readColumns, String writeColumns) {
        return Arguments.of(label, sql, reads, writes, joins, readColumns, writeColumns);
    }

    static Stream<Arguments> oracle() {
        return Stream.of(
                row("MERGE USING (SELECT .. FROM DUAL) + UPDATE .. WHERE .. DELETE WHERE + INSERT",
                        "MERGE INTO TB_STOCK S USING (SELECT ? AS PRODUCT_ID, ? AS QTY FROM DUAL) D ON (S.PRODUCT_ID = D.PRODUCT_ID) "
                                + "WHEN MATCHED THEN UPDATE SET S.QTY = S.QTY + D.QTY, S.UPD_DT = SYSDATE WHERE S.USE_YN = 'Y' DELETE WHERE S.QTY <= 0 "
                                + "WHEN NOT MATCHED THEN INSERT (PRODUCT_ID, QTY, REG_DT) VALUES (D.PRODUCT_ID, D.QTY, SYSDATE) WHERE D.QTY > 0",
                        "", "TB_STOCK[MERGE]", "",
                        cols("TB_STOCK.PRODUCT_ID", "TB_STOCK.QTY", "TB_STOCK.USE_YN"),
                        cols("TB_STOCK.QTY", "TB_STOCK.UPD_DT", "TB_STOCK.PRODUCT_ID", "TB_STOCK.REG_DT")),
                row("MERGE USING 테이블 + 조인 키",
                        "MERGE INTO TB_STOCK S USING TB_ORDER_ITEM I ON (S.PRODUCT_ID = I.PRODUCT_ID AND S.WH_CD = I.WH_CD) "
                                + "WHEN MATCHED THEN UPDATE SET S.QTY = S.QTY - I.QTY",
                        "TB_ORDER_ITEM", "TB_STOCK[MERGE]", "TB_STOCK(PRODUCT_ID,WH_CD)=TB_ORDER_ITEM(PRODUCT_ID,WH_CD)",
                        cols("TB_STOCK.PRODUCT_ID", "TB_STOCK.WH_CD", "TB_STOCK.QTY", "TB_ORDER_ITEM.PRODUCT_ID", "TB_ORDER_ITEM.WH_CD", "TB_ORDER_ITEM.QTY"),
                        cols("TB_STOCK.QTY")),
                row("INSERT ALL .. SELECT",
                        "INSERT ALL INTO TB_A (ID, NM) VALUES (SRC_ID, SRC_NM) INTO TB_B (ID, CD) VALUES (SRC_ID, SRC_CD) "
                                + "SELECT S.ID AS SRC_ID, S.NM AS SRC_NM, S.CD AS SRC_CD FROM TB_SRC S WHERE S.ST = '1'",
                        "TB_SRC", "TB_A[INSERT],TB_B[INSERT]", "",
                        cols("TB_SRC.ID", "TB_SRC.NM", "TB_SRC.CD", "TB_SRC.ST"), cols("TB_A.ID", "TB_A.NM", "TB_B.ID", "TB_B.CD")),
                row("INSERT FIRST WHEN .. THEN INTO .. ELSE INTO .. SELECT",
                        "INSERT FIRST WHEN AMT >= 1000 THEN INTO TB_BIG (ID, AMT) VALUES (ID, AMT) ELSE INTO TB_SMALL (ID, AMT) VALUES (ID, AMT) "
                                + "SELECT O.ID, O.AMT FROM TB_ORD O",
                        "TB_ORD", "TB_BIG[INSERT],TB_SMALL[INSERT]", "",
                        cols("TB_ORD.ID", "TB_ORD.AMT"), cols("TB_BIG.ID", "TB_BIG.AMT", "TB_SMALL.ID", "TB_SMALL.AMT")),
                row("FOR UPDATE OF t.col NOWAIT",
                        "SELECT O.ORDER_ID FROM TB_ORDER O, TB_MEMBER M WHERE O.MEMBER_ID = M.MEMBER_ID FOR UPDATE OF O.ORDER_STATUS NOWAIT",
                        "TB_MEMBER,TB_ORDER", "", "TB_ORDER(MEMBER_ID)=TB_MEMBER(MEMBER_ID)",
                        cols("TB_ORDER.ORDER_ID", "TB_ORDER.MEMBER_ID", "TB_ORDER.ORDER_STATUS", "TB_MEMBER.MEMBER_ID"), ""),
                row("FOR UPDATE OF t.col, t.col WAIT n",
                        "SELECT O.ORDER_ID FROM TB_ORDER O JOIN TB_MEMBER M ON M.MEMBER_ID = O.MEMBER_ID WHERE O.ORDER_ID = :id "
                                + "FOR UPDATE OF O.ORDER_STATUS, M.GRADE_CD WAIT 5",
                        "TB_MEMBER,TB_ORDER", "", "TB_MEMBER(MEMBER_ID)=TB_ORDER(MEMBER_ID)",
                        cols("TB_ORDER.ORDER_ID", "TB_ORDER.MEMBER_ID", "TB_ORDER.ORDER_STATUS", "TB_MEMBER.MEMBER_ID", "TB_MEMBER.GRADE_CD"), ""),
                row("FOR UPDATE SKIP LOCKED",
                        "SELECT Q.JOB_ID FROM TB_QUEUE Q WHERE Q.ST = 'READY' AND ROWNUM <= 10 FOR UPDATE SKIP LOCKED",
                        "TB_QUEUE", "", "", cols("TB_QUEUE.JOB_ID", "TB_QUEUE.ST"), ""),
                row("재귀 WITH + SEARCH/CYCLE",
                        "WITH T (ID, PID, LV) AS (SELECT R.ID, R.PID, 1 FROM TB_TREE R WHERE R.PID IS NULL "
                                + "UNION ALL SELECT C.ID, C.PID, T.LV + 1 FROM TB_TREE C JOIN T ON C.PID = T.ID) "
                                + "SEARCH DEPTH FIRST BY ID SET ORD CYCLE ID SET IS_CYCLE TO 'Y' DEFAULT 'N' "
                                + "SELECT T.ID, T.LV FROM T WHERE T.IS_CYCLE = 'N' ORDER BY ORD",
                        "TB_TREE", "", "", cols("TB_TREE.ID", "TB_TREE.PID"), ""),
                row("LISTAGG(..) WITHIN GROUP (ORDER BY ..)",
                        "SELECT D.DEPT_ID, LISTAGG(E.EMP_NM, ',') WITHIN GROUP (ORDER BY E.HIRE_DT DESC) AS NAMES "
                                + "FROM TB_DEPT D JOIN TB_EMP E ON E.DEPT_ID = D.DEPT_ID GROUP BY D.DEPT_ID",
                        "TB_DEPT,TB_EMP", "", "TB_EMP(DEPT_ID)=TB_DEPT(DEPT_ID)",
                        cols("TB_DEPT.DEPT_ID", "TB_EMP.EMP_NM", "TB_EMP.HIRE_DT", "TB_EMP.DEPT_ID"), ""),
                row("LISTAGG(DISTINCT .. ON OVERFLOW TRUNCATE) + OVER (PARTITION BY ..)",
                        "SELECT LISTAGG(DISTINCT E.JOB_CD, ';' ON OVERFLOW TRUNCATE '...' WITH COUNT) WITHIN GROUP (ORDER BY E.JOB_CD) "
                                + "OVER (PARTITION BY E.DEPT_ID) FROM TB_EMP E",
                        "TB_EMP", "", "", cols("TB_EMP.JOB_CD", "TB_EMP.DEPT_ID"), ""),
                row("KEEP (DENSE_RANK FIRST|LAST ORDER BY ..)",
                        "SELECT DEPT_ID, MAX(SAL) KEEP (DENSE_RANK FIRST ORDER BY HIRE_DT) AS FIRST_SAL, "
                                + "MIN(EMP_NM) KEEP (DENSE_RANK LAST ORDER BY BONUS DESC NULLS LAST, EMP_ID) FROM TB_EMP GROUP BY DEPT_ID",
                        "TB_EMP", "", "", cols("TB_EMP.DEPT_ID", "TB_EMP.SAL", "TB_EMP.HIRE_DT", "TB_EMP.EMP_NM", "TB_EMP.BONUS", "TB_EMP.EMP_ID"), ""),
                row("XMLAGG(XMLELEMENT(이름, ..) ORDER BY ..).EXTRACT(..).GETSTRINGVAL()",
                        "SELECT D.DEPT_ID, RTRIM(XMLAGG(XMLELEMENT(X, E.EMP_NM, ',') ORDER BY E.EMP_NM).EXTRACT('//text()').GETSTRINGVAL(), ',') "
                                + "FROM TB_DEPT D, TB_EMP E WHERE E.DEPT_ID = D.DEPT_ID GROUP BY D.DEPT_ID",
                        "TB_DEPT,TB_EMP", "", "TB_EMP(DEPT_ID)=TB_DEPT(DEPT_ID)",
                        cols("TB_DEPT.DEPT_ID", "TB_EMP.EMP_NM", "TB_EMP.DEPT_ID"), ""),
                row("XMLELEMENT(\"이름\", XMLATTRIBUTES(.. AS ..), ..), XMLFOREST",
                        "SELECT XMLELEMENT(\"emp\", XMLATTRIBUTES(EMP_ID AS \"id\"), XMLFOREST(EMP_NM AS \"name\", SAL)) FROM TB_EMP",
                        "TB_EMP", "", "", cols("TB_EMP.EMP_ID", "TB_EMP.EMP_NM", "TB_EMP.SAL"), ""),
                row("REGEXP_LIKE, REGEXP_SUBSTR",
                        "SELECT ID, REGEXP_SUBSTR(TEL, '[0-9]+', 1, 2) FROM TB_USER WHERE REGEXP_LIKE(EMAIL, '^[a-z]+@', 'i') AND NOT REGEXP_LIKE(TEL, '[^0-9-]')",
                        "TB_USER", "", "", cols("TB_USER.ID", "TB_USER.TEL", "TB_USER.EMAIL"), ""),
                row("CONNECT BY NOCYCLE PRIOR, SYS_CONNECT_BY_PATH, CONNECT_BY_ROOT, CONNECT_BY_ISLEAF, LEVEL, ORDER SIBLINGS BY",
                        "SELECT M.MENU_ID, SYS_CONNECT_BY_PATH(M.MENU_NM, '/') AS PATH, CONNECT_BY_ROOT M.MENU_ID AS ROOT_ID, CONNECT_BY_ISLEAF, LEVEL "
                                + "FROM TB_MENU M START WITH M.UP_MENU_ID IS NULL CONNECT BY NOCYCLE PRIOR M.MENU_ID = M.UP_MENU_ID AND M.USE_YN = 'Y' "
                                + "ORDER SIBLINGS BY M.SORT_NO",
                        "TB_MENU", "", "TB_MENU(MENU_ID)=TB_MENU(UP_MENU_ID)",
                        cols("TB_MENU.MENU_ID", "TB_MENU.MENU_NM", "TB_MENU.UP_MENU_ID", "TB_MENU.USE_YN", "TB_MENU.SORT_NO"), ""),
                row("계층 쿼리와 조인: CONNECT BY PRIOR 오른쪽, 비한정 컬럼",
                        "SELECT LPAD(' ', 2 * (LEVEL - 1)) || DEPT_NM FROM TB_DEPT START WITH UP_DEPT_ID = '0' CONNECT BY UP_DEPT_ID = PRIOR DEPT_ID",
                        "TB_DEPT", "", "TB_DEPT(UP_DEPT_ID)=TB_DEPT(DEPT_ID)", cols("TB_DEPT.DEPT_NM", "TB_DEPT.UP_DEPT_ID", "TB_DEPT.DEPT_ID"), ""),
                row("(+) 다중 조건: 여러 컬럼 조인과 상수 조건의 (+)",
                        "SELECT A.ID FROM TB_A A, TB_B B, TB_C C WHERE A.ID = B.A_ID(+) AND A.TYPE_CD = B.TYPE_CD (+) AND B.USE_YN(+) = 'Y' "
                                + "AND C.B_ID(+) = B.ID AND C.SEQ(+) = 1 AND B.DT(+) BETWEEN A.FROM_DT AND A.TO_DT",
                        "TB_A,TB_B,TB_C", "", "TB_A(ID,TYPE_CD)=TB_B(A_ID,TYPE_CD);TB_C(B_ID)=TB_B(ID)",
                        cols("TB_A.ID", "TB_A.TYPE_CD", "TB_A.FROM_DT", "TB_A.TO_DT", "TB_B.A_ID", "TB_B.TYPE_CD", "TB_B.USE_YN", "TB_B.ID", "TB_B.DT",
                                "TB_C.B_ID", "TB_C.SEQ"), ""),
                row("PARTITION (p) / SUBPARTITION (sp) / PARTITION FOR (..)",
                        "SELECT S.ID, T.AMT, U.AMT FROM TB_SALES PARTITION (P202401) S, TB_SALES SUBPARTITION (SP_01) T, "
                                + "TB_SALES PARTITION FOR (TO_DATE('2024', 'YYYY')) U WHERE S.ID = T.ID AND T.ID = U.ID",
                        // 같은 테이블의 같은 컬럼끼리의 등치는 관계 정보가 없어 조인으로 남기지 않는다
                        "TB_SALES", "", "", cols("TB_SALES.ID", "TB_SALES.AMT"), ""),
                row("DML의 PARTITION (p)",
                        "UPDATE TB_SALES PARTITION (P202401) S SET S.AMT = 0 WHERE S.ID = ?",
                        "", "TB_SALES[UPDATE]", "", cols("TB_SALES.ID"), cols("TB_SALES.AMT")),
                row("INSERT/DELETE의 PARTITION (p)",
                        "DELETE FROM TB_SALES PARTITION (P_OLD) WHERE SALE_DT < ADD_MONTHS(SYSDATE, -60)",
                        "", "TB_SALES[DELETE]", "", cols("TB_SALES.SALE_DT"), ""),
                row("AS OF TIMESTAMP (..) / AS OF SCN",
                        "SELECT O.ORDER_ID, P.AMT FROM TB_ORDER AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '10' MINUTE) O, TB_PAY AS OF SCN 12345 P "
                                + "WHERE P.ORDER_ID = O.ORDER_ID",
                        "TB_ORDER,TB_PAY", "", "TB_PAY(ORDER_ID)=TB_ORDER(ORDER_ID)",
                        cols("TB_ORDER.ORDER_ID", "TB_PAY.AMT", "TB_PAY.ORDER_ID"), ""),
                row("PIVOT (집계 AS 별칭 FOR 컬럼 IN (값 AS 별칭)) 인라인 뷰",
                        "SELECT * FROM (SELECT E.DEPT_ID, E.JOB_CD, E.SAL FROM TB_EMP E) PIVOT (SUM(SAL) AS S, COUNT(*) AS C FOR JOB_CD IN ('A' AS A, 'B' AS B)) "
                                + "ORDER BY DEPT_ID",
                        "TB_EMP", "", "", cols("TB_EMP.DEPT_ID", "TB_EMP.JOB_CD", "TB_EMP.SAL"), ""),
                row("UNPIVOT INCLUDE NULLS (값 FOR 이름 IN (컬럼 AS '값'))",
                        "SELECT U.ID, U.QTR, U.AMT FROM TB_SALES UNPIVOT INCLUDE NULLS (AMT FOR QTR IN (Q1 AS 'Q1', Q2 AS 'Q2')) U WHERE U.AMT > 0",
                        "TB_SALES", "", "", "", ""),
                row("OFFSET n ROWS FETCH NEXT n ROWS ONLY(12c)",
                        "SELECT A.ID, A.NM FROM TB_A A WHERE A.ST = ? ORDER BY A.ID OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.NM", "TB_A.ST"), ""),
                row("FETCH FIRST n PERCENT ROWS WITH TIES, OFFSET :n ROWS",
                        "SELECT A.ID FROM TB_A A ORDER BY A.AMT DESC OFFSET :skip ROWS FETCH FIRST 10 PERCENT ROWS WITH TIES",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.AMT"), ""),
                row("서브쿼리 안의 FETCH FIRST",
                        "SELECT M.ID, (SELECT L.MSG FROM TB_LOG L WHERE L.M_ID = M.ID ORDER BY L.TS DESC FETCH FIRST 1 ROW ONLY) FROM TB_M M",
                        "TB_LOG,TB_M", "", "TB_LOG(M_ID)=TB_M(ID)", cols("TB_M.ID", "TB_LOG.MSG", "TB_LOG.M_ID", "TB_LOG.TS"), ""),
                row("JSON_VALUE(.. RETURNING .. ON ERROR), JSON_EXISTS, JSON_QUERY",
                        "SELECT JSON_VALUE(T.DOC, '$.name') AS NM, JSON_VALUE(T.DOC, '$.age' RETURNING NUMBER DEFAULT 0 ON ERROR), "
                                + "JSON_QUERY(T.DOC, '$.tags' WITH WRAPPER) FROM TB_DOC T WHERE JSON_EXISTS(T.DOC, '$.name') AND T.DOC IS JSON",
                        "TB_DOC", "", "", cols("TB_DOC.DOC"), ""),
                row("JSON_TABLE(.. COLUMNS (..)): 컬럼 절은 건너뛴다",
                        "SELECT T.ID, J.NM, J.QTY FROM TB_DOC T, JSON_TABLE(T.DOC, '$.items[*]' COLUMNS (NM VARCHAR2(10) PATH '$.nm', "
                                + "QTY NUMBER PATH '$.qty', NESTED PATH '$.sub[*]' COLUMNS (SUB_ID NUMBER PATH '$.id'), ROW_NO FOR ORDINALITY)) J WHERE J.QTY > 0",
                        "TB_DOC", "", "", cols("TB_DOC.ID", "TB_DOC.DOC"), ""),
                row("XMLTABLE(.. PASSING .. COLUMNS ..): 컬럼 절은 건너뛴다",
                        "SELECT T.ID, X.ITEM_NM FROM TB_XML T, XMLTABLE('/order/item' PASSING T.DOC COLUMNS ITEM_NM VARCHAR2(50) PATH 'name', "
                                + "QTY NUMBER PATH '@qty') X WHERE X.QTY > 0",
                        "TB_XML", "", "", cols("TB_XML.ID", "TB_XML.DOC"), ""),
                row("DB 링크 t@link, 스키마.t@link.domain",
                        "SELECT A.ID, B.NM, C.CD FROM TB_A A, TB_REMOTE@DBLINK_ERP B, ERP.TB_CODE@ERP.WORLD C WHERE A.ID = B.ID AND B.CD = C.CD",
                        "TB_A,TB_CODE,TB_REMOTE", "", "TB_A(ID)=TB_REMOTE(ID);TB_REMOTE(CD)=TB_CODE(CD)",
                        cols("TB_A.ID", "TB_REMOTE.NM", "TB_REMOTE.ID", "TB_REMOTE.CD", "TB_CODE.CD"), ""),
                row("DB 링크 너머의 DML",
                        "INSERT INTO TB_LOG@LINK_BK (ID, MSG) SELECT L.ID, L.MSG FROM TB_LOG L WHERE L.TS < SYSDATE - 30",
                        "TB_LOG", "TB_LOG[INSERT]", "", cols("TB_LOG.ID", "TB_LOG.MSG", "TB_LOG.TS"), cols("TB_LOG.ID", "TB_LOG.MSG")),
                row("힌트 /*+ INDEX(a idx) */ 와 --+ 힌트",
                        "SELECT /*+ INDEX(A IDX_A_01) LEADING(A B) USE_NL(B) */ A.ID, B.NM FROM TB_A A, TB_B B WHERE A.ID = B.A_ID",
                        "TB_A,TB_B", "", "TB_A(ID)=TB_B(A_ID)", cols("TB_A.ID", "TB_B.NM", "TB_B.A_ID"), ""),
                row("DML의 힌트",
                        "UPDATE /*+ PARALLEL(T 4) */ TB_A T SET T.ST = '9' WHERE T.ID IN (SELECT /*+ UNNEST */ B.A_ID FROM TB_B B)",
                        "TB_B", "TB_A[UPDATE]", "TB_A(ID)=TB_B(A_ID)", cols("TB_A.ID", "TB_B.A_ID"), cols("TB_A.ST")),
                row("INSERT /*+ APPEND */ .. SELECT, 시퀀스",
                        "INSERT /*+ APPEND */ INTO TB_HIST (HIST_ID, ORDER_ID) SELECT SEQ_HIST.NEXTVAL, O.ORDER_ID FROM TB_ORDER O",
                        "TB_ORDER", "TB_HIST[INSERT]", "", cols("TB_ORDER.ORDER_ID"), cols("TB_HIST.HIST_ID", "TB_HIST.ORDER_ID")),
                row("분석 함수: 윈도 절, IGNORE NULLS, RATIO_TO_REPORT() OVER ()",
                        "SELECT E.EMP_ID, SUM(E.SAL) OVER (PARTITION BY E.DEPT_ID ORDER BY E.HIRE_DT ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), "
                                + "LAG(E.SAL, 1, 0) OVER (ORDER BY E.HIRE_DT), FIRST_VALUE(E.NM IGNORE NULLS) OVER (PARTITION BY E.JOB_CD ORDER BY E.SAL DESC), "
                                + "LAST_VALUE(E.NM) IGNORE NULLS OVER (ORDER BY E.SAL RANGE BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING), "
                                + "RATIO_TO_REPORT(E.SAL) OVER () FROM TB_EMP E",
                        "TB_EMP", "", "", cols("TB_EMP.EMP_ID", "TB_EMP.SAL", "TB_EMP.DEPT_ID", "TB_EMP.HIRE_DT", "TB_EMP.NM", "TB_EMP.JOB_CD"), ""),
                row("ROWNUM 페이징 + ROW_NUMBER() OVER",
                        "SELECT * FROM (SELECT ROW_NUMBER() OVER (ORDER BY A.REG_DT DESC) AS RN, A.ID, A.NM FROM TB_A A WHERE A.ST = ?) WHERE RN BETWEEN ? AND ?",
                        "TB_A", "", "", cols("TB_A.REG_DT", "TB_A.ID", "TB_A.NM", "TB_A.ST"), ""),
                row("TABLE(패키지 함수), TABLE(CAST(바인드 AS 타입)), THE",
                        "SELECT O.ID FROM TB_ORD O, TABLE(PKG_UTIL.SPLIT(O.TAGS, ',')) T, TABLE(CAST(:ids AS T_NUM_TAB)) I WHERE I.COLUMN_VALUE = O.ID",
                        "TB_ORD", "", "", cols("TB_ORD.ID", "TB_ORD.TAGS"), ""),
                row("CROSS APPLY / OUTER APPLY / LATERAL(12c)",
                        "SELECT D.DEPT_ID, X.CNT FROM TB_DEPT D CROSS APPLY (SELECT COUNT(*) AS CNT FROM TB_EMP E WHERE E.DEPT_ID = D.DEPT_ID) X",
                        "TB_DEPT,TB_EMP", "", "TB_EMP(DEPT_ID)=TB_DEPT(DEPT_ID)", cols("TB_DEPT.DEPT_ID", "TB_EMP.DEPT_ID"), ""),
                row("GROUP BY ROLLUP/CUBE/GROUPING SETS, GROUPING()",
                        "SELECT E.DEPT_ID, E.JOB_CD, GROUPING(E.DEPT_ID), SUM(E.SAL) FROM TB_EMP E GROUP BY ROLLUP(E.DEPT_ID, E.JOB_CD), CUBE(E.LOC), "
                                + "GROUPING SETS ((E.DEPT_ID), (E.JOB_CD), ())",
                        "TB_EMP", "", "", cols("TB_EMP.DEPT_ID", "TB_EMP.JOB_CD", "TB_EMP.SAL", "TB_EMP.LOC"), ""),
                row("날짜 리터럴·INTERVAL·AT TIME ZONE",
                        "SELECT A.ID FROM TB_A A WHERE A.REG_DT >= DATE '2024-01-01' AND A.UPD_TS < TIMESTAMP '2024-12-31 23:59:59' "
                                + "AND A.TS > SYSTIMESTAMP - INTERVAL '1' DAY AND A.END_DT < SYSDATE + INTERVAL '2-3' YEAR TO MONTH AND A.TZ = A.TS AT TIME ZONE 'UTC'",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.REG_DT", "TB_A.UPD_TS", "TB_A.TS", "TB_A.END_DT", "TB_A.TZ"), ""),
                row("파티션 외부 조인 PARTITION BY (..) RIGHT OUTER JOIN",
                        "SELECT S.PROD_ID, T.DT, S.AMT FROM TB_SALES S PARTITION BY (S.PROD_ID) RIGHT OUTER JOIN TB_CAL T ON S.DT = T.DT",
                        "TB_CAL,TB_SALES", "", "TB_SALES(DT)=TB_CAL(DT)", cols("TB_SALES.PROD_ID", "TB_SALES.AMT", "TB_SALES.DT", "TB_CAL.DT"), ""),
                row("NTH_VALUE(..) FROM FIRST IGNORE NULLS OVER (..): FROM FIRST는 테이블이 아니다",
                        "SELECT E.EMP_ID, NTH_VALUE(E.SAL, 2) FROM FIRST IGNORE NULLS OVER (PARTITION BY E.DEPT_ID ORDER BY E.SAL DESC) FROM TB_EMP E",
                        "TB_EMP", "", "", cols("TB_EMP.EMP_ID", "TB_EMP.SAL", "TB_EMP.DEPT_ID"), ""),
                row("컬렉션 조건 MEMBER OF, IS OF (타입), IS A SET",
                        "SELECT A.ID FROM TB_A A WHERE A.CD MEMBER OF :codes AND A.ST NOT MEMBER OF :excluded AND A.OBJ IS OF (T_SUB) AND A.TAGS IS NOT A SET",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.CD", "TB_A.ST", "TB_A.OBJ", "TB_A.TAGS"), ""),
                row("JSON_OBJECT(KEY k VALUE v .. ON NULL), JSON_ARRAYAGG(.. ORDER BY .. RETURNING ..)",
                        "SELECT JSON_OBJECT(KEY 'id' VALUE T.ID, 'nm' VALUE T.NM ABSENT ON NULL RETURNING VARCHAR2(4000)), "
                                + "JSON_ARRAYAGG(T.CD ORDER BY T.SORT_NO RETURNING CLOB) FROM TB_T T",
                        "TB_T", "", "", cols("TB_T.ID", "TB_T.NM", "TB_T.CD", "TB_T.SORT_NO"), ""),
                row("JSON 점 표기·객체 속성: 별칭.컬럼.속성",
                        "SELECT T.DOC.name, T.DOC.addr.city, T.ADDR.ZIP FROM TB_DOC T WHERE T.DOC.type = 'A'",
                        "TB_DOC", "", "", cols("TB_DOC.DOC", "TB_DOC.ADDR"), ""),
                row("RETURNING .. INTO 바인드",
                        "UPDATE TB_A SET ST = '9', UPD_DT = SYSDATE WHERE ID = :id RETURNING NM, ST INTO :nm, :st",
                        "", "TB_A[UPDATE]", "", cols("TB_A.ID", "TB_A.NM", "TB_A.ST"), cols("TB_A.ST", "TB_A.UPD_DT")),
                row("다중 컬럼 SET (a, b) = (서브쿼리)",
                        "UPDATE TB_A A SET (A.NM, A.ST) = (SELECT B.NM, B.ST FROM TB_B B WHERE B.A_ID = A.ID) WHERE EXISTS (SELECT 1 FROM TB_B B WHERE B.A_ID = A.ID)",
                        "TB_B", "TB_A[UPDATE]", "TB_B(A_ID)=TB_A(ID)", cols("TB_B.NM", "TB_B.ST", "TB_B.A_ID", "TB_A.ID"), cols("TB_A.NM", "TB_A.ST")));
    }

    static Stream<Arguments> db2() {
        return Stream.of(
                row("FETCH FIRST n ROWS ONLY",
                        "SELECT A.ID, A.NM FROM TB_A A WHERE A.ST = ? ORDER BY A.ID FETCH FIRST 10 ROWS ONLY",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.NM", "TB_A.ST"), ""),
                row("FETCH FIRST ROW ONLY, FETCH FIRST ? ROWS ONLY",
                        "SELECT A.ID FROM TB_A A WHERE A.ID > (SELECT B.ID FROM TB_B B ORDER BY B.TS DESC FETCH FIRST ROW ONLY) FETCH FIRST ? ROWS ONLY",
                        "TB_A,TB_B", "", "", cols("TB_A.ID", "TB_B.ID", "TB_B.TS"), ""),
                row("WITH UR",
                        "SELECT A.ID FROM TB_A A JOIN TB_B B ON B.A_ID = A.ID WHERE A.ST = ? WITH UR",
                        "TB_A,TB_B", "", "TB_B(A_ID)=TB_A(ID)", cols("TB_A.ID", "TB_B.A_ID", "TB_A.ST"), ""),
                row("WITH RS USE AND KEEP UPDATE LOCKS",
                        "SELECT A.ID FROM TB_A A WHERE A.ID = ? FOR UPDATE WITH RS USE AND KEEP UPDATE LOCKS",
                        "TB_A", "", "", cols("TB_A.ID"), ""),
                row("FOR READ ONLY / OPTIMIZE FOR n ROWS / FETCH FIRST / WITH UR 조합",
                        "SELECT A.ID FROM TB_A A ORDER BY A.ID FETCH FIRST 20 ROWS ONLY FOR READ ONLY OPTIMIZE FOR 20 ROWS WITH UR",
                        "TB_A", "", "", cols("TB_A.ID"), ""),
                row("FOR FETCH ONLY WITH CS",
                        "SELECT A.ID FROM TB_A A WHERE A.NM LIKE ? FOR FETCH ONLY WITH CS",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.NM"), ""),
                row("OPTIMIZE FOR 1 ROW, QUERYNO",
                        "SELECT A.ID FROM TB_A A WHERE A.CD = ? OPTIMIZE FOR 1 ROW QUERYNO 100",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.CD"), ""),
                row("WITH CS SKIP LOCKED DATA, WITH UR WAIT FOR OUTCOME, FOR READ ONLY USE AND KEEP UPDATE LOCKS",
                        "SELECT A.ID FROM TB_A A WHERE A.ST = (SELECT B.ST FROM TB_B B WHERE B.ID = ? WITH UR WAIT FOR OUTCOME) "
                                + "FOR READ ONLY USE AND KEEP UPDATE LOCKS WITH CS SKIP LOCKED DATA",
                        "TB_A,TB_B", "", "TB_A(ST)=TB_B(ST)", cols("TB_A.ID", "TB_A.ST", "TB_B.ST", "TB_B.ID"), ""),
                row("FOR UPDATE OF col, col",
                        "SELECT ID, ST FROM TB_A WHERE ST = '1' FOR UPDATE OF ST, UPD_TS",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.ST", "TB_A.UPD_TS"), ""),
                row("SELECT .. FROM FINAL TABLE (INSERT ..)",
                        "SELECT T.ID, T.REG_TS FROM FINAL TABLE (INSERT INTO TB_A (NM, ST) VALUES (?, 'N')) AS T",
                        "", "TB_A[INSERT]", "", "", cols("TB_A.NM", "TB_A.ST")),
                row("SELECT .. FROM OLD TABLE (UPDATE ..)",
                        "SELECT O.ID, O.ST FROM OLD TABLE (UPDATE TB_A A SET A.ST = 'X' WHERE A.ID = ?) AS O",
                        "", "TB_A[UPDATE]", "", cols("TB_A.ID"), cols("TB_A.ST")),
                row("SELECT .. FROM OLD TABLE (DELETE ..), NEW TABLE",
                        "SELECT COUNT(*) FROM OLD TABLE (DELETE FROM TB_A WHERE ST = 'D' AND UPD_TS < CURRENT TIMESTAMP - 30 DAYS)",
                        "", "TB_A[DELETE]", "", cols("TB_A.ST", "TB_A.UPD_TS"), ""),
                row("데이터 변경 테이블 참조의 INCLUDE (컬럼 타입)",
                        "SELECT T.ID, T.ROW_NO FROM FINAL TABLE (INSERT INTO TB_A (NM, ST) INCLUDE (ROW_NO INTEGER) VALUES (?, 'N', 1)) AS T ORDER BY T.ROW_NO",
                        "", "TB_A[INSERT]", "", "", cols("TB_A.NM", "TB_A.ST")),
                row("UPDATE .. INCLUDE (..) SET",
                        "SELECT O.ID FROM OLD TABLE (UPDATE TB_A INCLUDE (OLD_ST CHAR(1)) SET OLD_ST = ST, ST = 'X' WHERE ID = ?) O",
                        // OLD_ST는 INCLUDE로 결과에만 붙인 컬럼이다: 대상 테이블의 컬럼으로 쓰지 않는다
                        "", "TB_A[UPDATE]", "", cols("TB_A.ST", "TB_A.ID"), cols("TB_A.ST")),
                row("VALUES (..) 단독",
                        "VALUES (1, 'A'), (2, 'B')", "", "", "", "", ""),
                row("VALUES 식 단독: 특수 레지스터, 함수",
                        "VALUES (CURRENT DATE, CURRENT TIMESTAMP, CURRENT SCHEMA, USER, CURRENT_DATE)", "", "", "", "", ""),
                row("VALUES .. INTO",
                        "VALUES (CURRENT DATE - 1 MONTH, UPPER(P_NM)) INTO V_DT, V_NM", "", "", "", "", ""),
                row("VALUES (서브쿼리) INTO",
                        "VALUES ((SELECT MAX(A.ID) FROM TB_A A WHERE A.ST = '1')) INTO :maxId", "TB_A", "", "", cols("TB_A.ID", "TB_A.ST"), ""),
                row("MERGE .. USING (VALUES (?, ?)) AS s(a, b)",
                        "MERGE INTO TB_STOCK AS T USING (VALUES (?, ?)) AS S (PRODUCT_ID, QTY) ON T.PRODUCT_ID = S.PRODUCT_ID "
                                + "WHEN MATCHED AND S.QTY = 0 THEN DELETE WHEN MATCHED THEN UPDATE SET T.QTY = T.QTY + S.QTY, T.UPD_TS = CURRENT TIMESTAMP "
                                + "WHEN NOT MATCHED THEN INSERT (PRODUCT_ID, QTY) VALUES (S.PRODUCT_ID, S.QTY) ELSE IGNORE",
                        "", "TB_STOCK[MERGE]", "", cols("TB_STOCK.PRODUCT_ID", "TB_STOCK.QTY"),
                        cols("TB_STOCK.QTY", "TB_STOCK.UPD_TS", "TB_STOCK.PRODUCT_ID")),
                row("MERGE .. USING (VALUES (CAST(? AS ..), ..)) s(a, b), UPDATE SET (a, b) = (..)",
                        "MERGE INTO SHOP.TB_CODE C USING (VALUES (CAST(? AS VARCHAR(10)), CAST(? AS VARCHAR(100)))) S (CD, NM) ON (C.CD = S.CD) "
                                + "WHEN MATCHED THEN UPDATE SET (C.NM, C.UPD_ID) = (S.NM, USER) WHEN NOT MATCHED THEN INSERT (CD, NM) VALUES (S.CD, S.NM)",
                        "", "TB_CODE[MERGE]", "", cols("TB_CODE.CD"), cols("TB_CODE.NM", "TB_CODE.UPD_ID", "TB_CODE.CD")),
                row("MERGE .. USING (SELECT .. FROM SYSIBM.SYSDUMMY1)",
                        "MERGE INTO TB_CNT T USING (SELECT CAST(? AS INTEGER) AS ID FROM SYSIBM.SYSDUMMY1) S ON T.ID = S.ID "
                                + "WHEN MATCHED THEN UPDATE SET CNT = CNT + 1 WHEN NOT MATCHED THEN INSERT (ID, CNT) VALUES (S.ID, 1)",
                        "", "TB_CNT[MERGE]", "", cols("TB_CNT.ID", "TB_CNT.CNT"), cols("TB_CNT.CNT", "TB_CNT.ID")),
                row("CURRENT DATE/TIMESTAMP/TIME, CURRENT_DATE, 특수 레지스터",
                        "SELECT A.ID, CURRENT DATE, CURRENT TIMESTAMP, CURRENT TIME, CURRENT_DATE, CURRENT_TIMESTAMP, CURRENT SCHEMA, CURRENT USER, USER, "
                                + "CURRENT SERVER, SESSION_USER FROM TB_A A WHERE A.REG_ID = USER AND A.OWNER = CURRENT SCHEMA AND A.DT <= CURRENT_DATE",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.REG_ID", "TB_A.OWNER", "TB_A.DT"), ""),
                row("col CONCAT col",
                        "SELECT A.FIRST_NM CONCAT ' ' CONCAT A.LAST_NM AS FULL_NM FROM TB_A A WHERE A.CD CONCAT A.SUB_CD = ? AND A.NM LIKE '%' CONCAT ? CONCAT '%'",
                        "TB_A", "", "", cols("TB_A.FIRST_NM", "TB_A.LAST_NM", "TB_A.CD", "TB_A.SUB_CD", "TB_A.NM"), ""),
                row("DAYS(x), 레이블 기간 1 DAY / 3 MONTHS / n YEARS, 바인드 기간",
                        "SELECT DAYS(A.END_DT) - DAYS(A.START_DT) AS D, A.START_DT + 1 DAY, A.START_DT + 3 MONTHS - 1 DAY, DATE(A.TS) + 2 YEARS, "
                                + "A.TS + 30 SECONDS - 5 MINUTES, A.DT + ? DAYS, YEAR(A.DT), MONTH(A.DT), DAY(A.DT) FROM TB_A A "
                                + "WHERE A.DT BETWEEN CURRENT DATE - 7 DAYS AND CURRENT DATE + 1 MONTH AND A.TS > CURRENT TIMESTAMP - 2 HOURS",
                        "TB_A", "", "", cols("TB_A.END_DT", "TB_A.START_DT", "TB_A.TS", "TB_A.DT"), ""),
                row("괄호 식 뒤의 기간 단위",
                        "SELECT A.ID FROM TB_A A WHERE A.DT >= CURRENT DATE - (DAY(CURRENT DATE) - 1) DAYS AND A.DT < A.BASE_DT + (A.TERM) MONTHS",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.DT", "TB_A.BASE_DT", "TB_A.TERM"), ""),
                row("ROW_NUMBER() OVER(), ROWNUMBER() OVER (ORDER BY ..)",
                        "SELECT ROW_NUMBER() OVER() AS RN, ROWNUMBER() OVER (ORDER BY A.AMT DESC) AS RK, A.ID FROM TB_A A",
                        "TB_A", "", "", cols("TB_A.AMT", "TB_A.ID"), ""),
                row("ROW_NUMBER() OVER 페이징 인라인 뷰",
                        "SELECT T.ID, T.NM FROM (SELECT ROW_NUMBER() OVER (ORDER BY A.ID) AS RN, A.ID, A.NM FROM TB_A A) AS T WHERE T.RN BETWEEN ? AND ?",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.NM"), ""),
                row("LATERAL (상관 서브쿼리) AS t",
                        "SELECT A.ID, T.CNT FROM TB_A A, LATERAL (SELECT COUNT(*) AS CNT FROM TB_B B WHERE B.A_ID = A.ID) AS T",
                        "TB_A,TB_B", "", "TB_B(A_ID)=TB_A(ID)", cols("TB_A.ID", "TB_B.A_ID"), ""),
                row("TABLE (상관 서브쿼리) AS t",
                        "SELECT A.ID, T.MAX_TS FROM TB_A A, TABLE (SELECT MAX(B.TS) AS MAX_TS FROM TB_B B WHERE B.A_ID = A.ID) AS T",
                        "TB_A,TB_B", "", "TB_B(A_ID)=TB_A(ID)", cols("TB_A.ID", "TB_B.TS", "TB_B.A_ID"), ""),
                row("TABLE(함수(..)) AS t(c1, c2), TABLE(VALUES ..) AS t(n)",
                        "SELECT A.ID, T.TOKEN, N.N FROM TB_A A, TABLE(FN_SPLIT(A.CSV, ',')) AS T (SEQ, TOKEN), TABLE(VALUES (1), (2)) AS N (N)",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.CSV"), ""),
                row("TABLE(함수(..)) AS t(c1 타입, ..), XMLTABLE(.. PASSING x AS \"d\" COLUMNS ..)",
                        "SELECT A.ID, T.C1, X.NM FROM TB_A A, TABLE(SHOP.FN_ROWS(A.ID)) AS T (C1 INTEGER, C2 VARCHAR(10)), "
                                + "XMLTABLE('$d/items/item' PASSING A.DOC AS \"d\" COLUMNS NM VARCHAR(20) PATH 'name', QTY INTEGER PATH 'qty') AS X",
                        "TB_A", "", "", cols("TB_A.ID", "TB_A.DOC"), ""),
                row("상관 이름 뒤 컬럼 목록 AS t(c1, c2): 인라인 뷰",
                        "SELECT T.C1, T.C2 FROM (SELECT A.ID, A.NM FROM TB_A A WHERE A.ST = '1') AS T (C1, C2) JOIN TB_B B ON B.A_ID = T.C1 WHERE T.C2 LIKE ?",
                        "TB_A,TB_B", "", "TB_B(A_ID)=TB_A(ID)", cols("TB_A.ID", "TB_A.NM", "TB_A.ST", "TB_B.A_ID"), ""),
                row("상관 이름 뒤 컬럼 목록 AS t(c1, c2): 테이블(컬럼을 모르면 바꿔 부른 이름을 그 테이블의 컬럼으로 만들지 않는다)",
                        "SELECT X.C1, B.NM FROM TB_A AS X (C1, C2) JOIN TB_B B ON B.A_ID = X.C1 WHERE X.C2 = ?",
                        "TB_A,TB_B", "", "", cols("TB_B.NM", "TB_B.A_ID"), ""),
                row("테이블 지정자 인자: RID(t), RID_BIT(t)",
                        "SELECT RID(A), RID_BIT(A), A.ID FROM TB_A A WHERE RID(A) = ?",
                        "TB_A", "", "", cols("TB_A.ID"), ""),
                row("WITH t (c1, c2) AS (..) + 재귀",
                        "WITH T (ID, PID, LV) AS (SELECT R.ID, R.PID, 1 FROM TB_TREE R WHERE R.PID IS NULL UNION ALL "
                                + "SELECT C.ID, C.PID, T.LV + 1 FROM TB_TREE C, T WHERE C.PID = T.ID) SELECT ID, LV FROM T",
                        "TB_TREE", "", "", cols("TB_TREE.ID", "TB_TREE.PID"), ""),
                row("EXCEPT / INTERSECT / EXCEPT ALL",
                        "SELECT A.ID FROM TB_A A EXCEPT SELECT B.ID FROM TB_B B INTERSECT SELECT C.ID FROM TB_C C EXCEPT ALL SELECT D.ID FROM TB_D D ORDER BY 1",
                        "TB_A,TB_B,TB_C,TB_D", "", "", cols("TB_A.ID", "TB_B.ID", "TB_C.ID", "TB_D.ID"), ""),
                row("IN (… EXCEPT …)",
                        "SELECT A.ID FROM TB_A A WHERE A.ID IN (SELECT B.A_ID FROM TB_B B EXCEPT SELECT C.A_ID FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "", "TB_A(ID)=TB_B(A_ID);TB_A(ID)=TB_C(A_ID)", cols("TB_A.ID", "TB_B.A_ID", "TB_C.A_ID"), ""),
                row("스키마 한정 SCHEMA.TABLE",
                        "SELECT O.ORDER_ID, S.AMT FROM SHOP.TB_ORDER O JOIN BATCH.TB_SETTLE S ON S.ORDER_ID = O.ORDER_ID WHERE SHOP.TB_ORDER.ST IS NULL",
                        "TB_ORDER,TB_SETTLE", "", "TB_SETTLE(ORDER_ID)=TB_ORDER(ORDER_ID)",
                        cols("TB_ORDER.ORDER_ID", "TB_SETTLE.AMT", "TB_SETTLE.ORDER_ID"), ""),
                row("SYSIBM.SYSDUMMY1",
                        "SELECT CURRENT TIMESTAMP, NEXT VALUE FOR SHOP.SEQ_ORDER, PREVIOUS VALUE FOR SEQ_ORDER FROM SYSIBM.SYSDUMMY1", "", "", "", "", ""),
                row("UPDATE SET (a, b) = (?, ?)",
                        "UPDATE TB_A SET (NM, ST, UPD_TS) = (?, ?, CURRENT TIMESTAMP) WHERE ID = ?",
                        "", "TB_A[UPDATE]", "", cols("TB_A.ID"), cols("TB_A.NM", "TB_A.ST", "TB_A.UPD_TS")),
                row("다중 행 INSERT VALUES",
                        "INSERT INTO SHOP.TB_A (ID, NM) VALUES (?, ?), (?, ?), (NEXT VALUE FOR SEQ_A, 'x')",
                        "", "TB_A[INSERT]", "", "", cols("TB_A.ID", "TB_A.NM")),
                row("형 변환 함수와 CAST, DECIMAL(x, p, s), VARCHAR_FORMAT",
                        "SELECT INTEGER(A.CNT), DECIMAL(A.AMT, 15, 2), CHAR(A.DT, ISO), VARCHAR_FORMAT(A.TS, 'YYYYMMDD'), CAST(? AS VARCHAR(10)), "
                                + "CAST(NULL AS INTEGER), COALESCE(A.NM, ''), VALUE(A.CD, 'X'), STRIP(A.NM, LEADING, '0'), POSSTR(A.NM, 'x') FROM TB_A A",
                        "TB_A", "", "", cols("TB_A.CNT", "TB_A.AMT", "TB_A.DT", "TB_A.TS", "TB_A.NM", "TB_A.CD"), ""),
                row("LEFT(..)/RIGHT(..)/INSERT(..) 함수",
                        "SELECT LEFT(A.NM, 3), RIGHT(A.CD, 2), INSERT(A.NM, 1, 2, 'x') FROM TB_A A LEFT JOIN TB_B B ON B.A_ID = A.ID",
                        "TB_A,TB_B", "", "TB_B(A_ID)=TB_A(ID)", cols("TB_A.NM", "TB_A.CD", "TB_B.A_ID", "TB_A.ID"), ""),
                row("FOR SYSTEM_TIME AS OF, ONLY (t)",
                        "SELECT A.ID FROM TB_A FOR SYSTEM_TIME AS OF ? A WHERE A.ID IN (SELECT B.ID FROM ONLY (TB_B) B)",
                        "TB_A,TB_B", "", "TB_A(ID)=TB_B(ID)", cols("TB_A.ID", "TB_B.ID"), ""),
                row("DELETE .. WHERE EXISTS + WITH UR 서브쿼리",
                        "DELETE FROM TB_A A WHERE EXISTS (SELECT 1 FROM TB_B B WHERE B.A_ID = A.ID AND B.ST = 'D')",
                        "TB_B", "TB_A[DELETE]", "TB_B(A_ID)=TB_A(ID)", cols("TB_B.A_ID", "TB_A.ID", "TB_B.ST"), ""));
    }

    private static void check(String sql, String reads, String writes, String joins, String readColumns, String writeColumns) {
        ParseResult result = SqlParser.parse(sql);
        assertThat(result.problems()).as("problems of [%s]", sql).isEmpty();
        StatementFacts f = new FactExtractor(new Catalog()).extract(result);
        assertThat(reads(f).replace("*", "")).as("읽은 테이블 [%s]", sql).isEqualTo(reads);
        assertThat(writes(f)).as("쓴 테이블 [%s]", sql).isEqualTo(writes);
        assertThat(joins(f)).as("조인 [%s]", sql).isEqualTo(joins);
        assertThat(distinct(readColumns(f))).as("읽은 컬럼 [%s]", sql).isEqualTo(readColumns);
        assertThat(distinct(writeColumns(f))).as("쓴 컬럼 [%s]", sql).isEqualTo(writeColumns);
        assertThat(f.unknownColumns()).as("미확인 컬럼 [%s]", sql).isEmpty();
    }

    private static String distinct(String joined) {
        return Arrays.stream(joined.split(",")).filter(s -> !s.isEmpty()).distinct().sorted().collect(Collectors.joining(","));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oracle")
    @DisplayName("F-006-AC34 Oracle 구문: 문제 없이 파싱되고 테이블·조인·컬럼이 맞다")
    void oracleSyntax(String label, String sql, String reads, String writes, String joins, String readColumns, String writeColumns) {
        check(sql, reads, writes, joins, readColumns, writeColumns);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("db2")
    @DisplayName("F-006-AC34 DB2 구문: 문제 없이 파싱되고 테이블·조인·컬럼이 맞다")
    void db2Syntax(String label, String sql, String reads, String writes, String joins, String readColumns, String writeColumns) {
        check(sql, reads, writes, joins, readColumns, writeColumns);
    }

    @Test
    @DisplayName("F-006-AC34 테이블 뒤의 컬럼 목록 AS t(c1, c2): DDL을 알면 위치로 원래 컬럼에 대응시킨다")
    void columnListAfterTableCorrelationName() {
        Catalog catalog = new Catalog();
        catalog.defineTable("TB_A", "ID", "NM", "ST");
        catalog.defineTable("TB_B", "B_ID", "A_ID");
        StatementFacts f = FactsTestSupport.analyze("SELECT X.C2 FROM TB_A AS X (C1, C2, C3) JOIN TB_B B ON B.A_ID = X.C1 WHERE X.C3 = ?", catalog);
        assertThat(readColumns(f)).isEqualTo("TB_A.ID,TB_A.NM,TB_A.ST,TB_B.A_ID");
        assertThat(joins(f)).isEqualTo("TB_B(A_ID)=TB_A(ID)");
        assertThat(reads(f)).isEqualTo("TB_A,TB_B");
    }

    @Test
    @DisplayName("F-006-AC34 시퀀스와 더미 테이블: DB 링크가 붙은 시퀀스, NEXT VALUE FOR, SYSIBM.SYSDUMMY1·DUAL은 테이블이 아니다")
    void sequencesAndDummyTables() {
        StatementFacts f = FactsTestSupport.analyze("SELECT CURRENT TIMESTAMP, NEXT VALUE FOR SHOP.SEQ_ORDER, PREVIOUS VALUE FOR SEQ_PAY FROM SYSIBM.SYSDUMMY1");
        assertThat(sequences(f)).isEqualTo("SEQ_ORDER,SEQ_PAY");
        assertThat(reads(f)).isEmpty();
        assertThat(f.dummyTable()).isEqualTo("SYSIBM.SYSDUMMY1");
        f = FactsTestSupport.analyze("INSERT INTO TB_A (ID) VALUES (SEQ_A.NEXTVAL@LINK_BK)");
        assertThat(sequences(f)).isEqualTo("SEQ_A");
        f = FactsTestSupport.analyze("SELECT SEQ_A.CURRVAL, USER, SYSDATE FROM DUAL");
        assertThat(sequences(f)).isEqualTo("SEQ_A");
        assertThat(f.dummyTable()).isEqualTo("DUAL");
    }
}
