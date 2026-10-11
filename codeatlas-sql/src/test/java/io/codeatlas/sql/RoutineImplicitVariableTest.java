package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.RoutineAnalyzerTest.stmt;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-008-R16·R17: 루틴 본문에서 선언 없이 생기는 이름(DB2 {@code FOR} 문의 루프 변수와 선택 목록의 컬럼 이름, 레코드의 필드, INTO 대상,
 * 특수 변수·의사 식별자)을 변수로 알아보고, 그 위에서 "선언된 테이블의 DDL에 없는 컬럼"을 매퍼 구문과 같은 규칙으로 참조한다.
 *
 * <p>DDL이 있는 테이블(TB_A, TB_C, TB_LOG)과 없는 테이블(TB_X)을 함께 쓴다. DDL이 없는 테이블 하나만 있는 구문에서는 변수를 놓치면
 * 그 이름이 컬럼이 되고, DDL이 있는 테이블에서는 {@code UNKNOWN_COLUMN} 진단이나 {@code declared=false} 컬럼이 잘못 생긴다.
 */
class RoutineImplicitVariableTest {

    private static final String DDL = """
            CREATE TABLE TB_A (ID NUMBER PRIMARY KEY, NM VARCHAR2(10), ST VARCHAR2(2));
            CREATE TABLE TB_C (C_ID NUMBER PRIMARY KEY, A_ID NUMBER, NM VARCHAR2(10));
            CREATE TABLE TB_LOG (LOG_ID NUMBER, MSG VARCHAR2(100), AMT NUMBER);
            """;

    private static final String DB2_DDL = """
            CREATE TABLE TB_A (ID INTEGER NOT NULL PRIMARY KEY, NM VARCHAR(10), ST VARCHAR(2));
            CREATE TABLE TB_C (C_ID INTEGER NOT NULL PRIMARY KEY, A_ID INTEGER, NM VARCHAR(10));
            CREATE TABLE TB_LOG (LOG_ID INTEGER, MSG VARCHAR(100), AMT DECIMAL(10,2));
            """;

    private static List<Diagnostic> unknown(GraphModel g) {
        return GraphAssert.diagnostics(g, SqlDiagnosticCodes.UNKNOWN_COLUMN);
    }

    /** DDL에 없는데 참조로 생긴 컬럼 노드의 ID(정렬). */
    private static List<String> undeclaredColumns(GraphModel g) {
        return g.nodes().stream().filter(n -> n.kind() == NodeKind.DB_COLUMN && !n.declared()).map(Node::id).sorted().toList();
    }

    private static List<String> tables(GraphModel g) {
        return g.nodes(NodeKind.DB_TABLE).stream().map(Node::id).sorted().collect(Collectors.toList());
    }

    private static List<String> messages(GraphModel g) {
        return g.diagnostics().stream().map(d -> d.severity() + " " + d.code() + " " + d.message()).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ DB2 FOR 문

    private static final String DB2_FOR = """
            --#SET TERMINATOR @
            CREATE PROCEDURE SP_FOR_IMPLICIT (IN P_ST VARCHAR(2))
            LANGUAGE SQL
            BEGIN
                DECLARE V_CNT INTEGER DEFAULT 0;
                FOR R AS
                    SELECT A.ID AS SRC_ID, A.NM, A.ST AS STATUS_CD, UPPER(A.NM) AS UP_NM
                      FROM TB_A A
                     WHERE A.ST = P_ST
                DO
                    UPDATE TB_C SET NM = R.UP_NM WHERE A_ID = R.SRC_ID;
                    INSERT INTO TB_LOG (LOG_ID, MSG) VALUES (SRC_ID, STATUS_CD || UP_NM);
                    UPDATE TB_C SET NM = UP_NM WHERE A_ID = SRC_ID AND C_ID > V_CNT;
                    UPDATE TB_X SET VAL = STATUS_CD WHERE X_ID = SRC_ID AND X_NM = NM;
                    DELETE FROM TB_LOG WHERE LOG_ID = SRC_ID AND MSG = R.STATUS_CD;
                    SET V_CNT = V_CNT + 1;
                END FOR;
                FOR ROW2 AS C2 CURSOR WITH HOLD FOR
                    SELECT C.C_ID, C.A_ID AS PARENT_ID FROM TB_C C
                DO
                    UPDATE TB_X SET VAL = PARENT_ID WHERE X_ID = C_ID;
                    UPDATE TB_LOG SET AMT = PARENT_ID WHERE LOG_ID = ROW2.C_ID;
                END FOR;
                UPDATE TB_X SET VAL = 0 WHERE STATUS_CD = P_ST;
            END@
            """;

    @Test
    @DisplayName("F-008-AC17 DB2 FOR 문: 루프 변수(r.col)와 선택 목록의 컬럼 이름(별칭·단순 컬럼)이 루프 본문의 SQL에서 변수다 — 컬럼도 UNKNOWN_COLUMN도 만들지 않는다")
    void db2ForLoopColumnsAreVariables() {
        try (TestProject project = TestProject.create().file("db/db2/01_ddl.sql", DB2_DDL).file("db/db2/sp_for.db2", DB2_FOR)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String r = "SP_FOR_IMPLICIT";

            assertThat(messages(g)).isEmpty();
            assertThat(tables(g)).containsExactly("db:table:TB_A", "db:table:TB_C", "db:table:TB_LOG", "db:table:TB_X");
            // TB_X(DDL 없음)의 컬럼은 진짜 컬럼뿐이다. 루프가 끝난 뒤의 STATUS_CD는 변수가 아니라 TB_X의 컬럼이다
            assertThat(undeclaredColumns(g)).containsExactly(
                    "db:column:TB_X.STATUS_CD", "db:column:TB_X.VAL", "db:column:TB_X.X_ID", "db:column:TB_X.X_NM");
            assertThat(hasEdge(g, stmt(r, DB2_FOR, "UPDATE TB_X SET VAL = 0"), EdgeKind.READS, "db:column:TB_X.STATUS_CD")).isTrue();
            // 루프 본문의 구문은 테이블과 진짜 컬럼만 읽고 쓴다
            String first = stmt(r, DB2_FOR, "UPDATE TB_C SET NM = R.UP_NM");
            assertThat(GraphAssert.targets(g, first, EdgeKind.READS)).containsExactly("db:column:TB_C.A_ID");
            assertThat(GraphAssert.targets(g, first, EdgeKind.WRITES)).containsExactly("db:column:TB_C.NM", "db:table:TB_C");
            String third = stmt(r, DB2_FOR, "UPDATE TB_C SET NM = UP_NM");
            assertThat(GraphAssert.targets(g, third, EdgeKind.READS)).containsExactly("db:column:TB_C.A_ID", "db:column:TB_C.C_ID");
            String onX = stmt(r, DB2_FOR, "UPDATE TB_X SET VAL = STATUS_CD");
            assertThat(GraphAssert.targets(g, onX, EdgeKind.READS)).containsExactly("db:column:TB_X.X_ID", "db:column:TB_X.X_NM");
            // 커서 이름을 준 FOR: 커서 이름·루프 변수는 테이블이 아니고 선택 목록의 컬럼 이름(C_ID, PARENT_ID)은 변수다
            String second = stmt(r, DB2_FOR, "UPDATE TB_X SET VAL = PARENT_ID");
            assertThat(GraphAssert.targets(g, second, EdgeKind.READS)).containsExactly("db:column:TB_X.X_ID");
            assertThat(GraphAssert.targets(g, stmt(r, DB2_FOR, "UPDATE TB_LOG SET AMT"), EdgeKind.READS)).containsExactly("db:column:TB_LOG.LOG_ID");
            assertThat(GraphAssert.node(g, stmt(r, DB2_FOR, "SELECT C.C_ID")).attrString("cursor")).isEqualTo("C2");
        }
    }

    private static final String DB2_FOR_STAR = """
            --#SET TERMINATOR @
            CREATE PROCEDURE SP_FOR_STAR ()
            LANGUAGE SQL
            BEGIN
                FOR R AS SELECT * FROM TB_A WHERE ST = '1' DO
                    UPDATE TB_C SET NM = NM WHERE A_ID = ID AND C_ID = ST;
                    UPDATE TB_X SET VAL = ST WHERE X_ID = ID;
                END FOR;
                FOR Q AS SELECT * FROM TB_NO_DDL DO
                    UPDATE TB_C SET NM = SOME_NM WHERE A_ID = SOME_ID;
                    UPDATE TB_A SET NM = 'x' WHERE ID = Q.SOME_ID AND GHOST = 1;
                END FOR;
            END@
            """;

    @Test
    @DisplayName("F-008-AC17 DB2 FOR 문의 SELECT *: DDL을 알면 그 테이블의 컬럼이 모두 변수이고, 모르면 그 루프 안에서는 DDL에 없는 이름을 추측하지도 알리지도 않는다")
    void db2ForLoopStar() {
        try (TestProject project = TestProject.create().file("db/db2/01_ddl.sql", DB2_DDL).file("db/db2/sp_star.db2", DB2_FOR_STAR)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String r = "SP_FOR_STAR";

            assertThat(messages(g)).isEmpty();
            // 대상 테이블에 같은 이름의 컬럼이 있으면 컬럼이 이긴다(NM은 TB_C의 컬럼), 없으면 루프의 컬럼 변수다(ID, ST)
            String onC = stmt(r, DB2_FOR_STAR, "UPDATE TB_C SET NM = NM");
            assertThat(GraphAssert.targets(g, onC, EdgeKind.READS)).containsExactly("db:column:TB_C.A_ID", "db:column:TB_C.C_ID", "db:column:TB_C.NM");
            String onX = stmt(r, DB2_FOR_STAR, "UPDATE TB_X SET VAL = ST");
            assertThat(GraphAssert.targets(g, onX, EdgeKind.READS)).containsExactly("db:column:TB_X.X_ID");
            // 컬럼을 모르는 테이블의 *: 무엇이 변수인지 알 수 없다. 틀린 컬럼도 진단도 만들지 않는다
            assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_X.VAL", "db:column:TB_X.X_ID");
            assertThat(unknown(g)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ PL/SQL 레코드, INTO 대상, 특수 이름

    private static final String PLSQL = """
            CREATE OR REPLACE PROCEDURE SP_RECORDS (P_ID IN NUMBER) IS
                CURSOR C_A (P_ST VARCHAR2) IS SELECT A.ID, A.NM, A.ST FROM TB_A A WHERE A.ST = P_ST;
                TYPE T_REC IS RECORD (KEY_ID NUMBER, LABEL VARCHAR2(10));
                TYPE T_IDS IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
                V_ROW    TB_A%ROWTYPE;
                V_CUR    C_A%ROWTYPE;
                V_REC    T_REC;
                V_IDS    T_IDS;
                V_NM     TB_A.NM%TYPE;
                V_ERR    VARCHAR2(200);
            BEGIN
                FOR R IN C_A('1') LOOP
                    UPDATE TB_X SET VAL = R.NM WHERE X_ID = R.ID AND NM = V_NM;
                    UPDATE TB_C SET NM = R.NM WHERE A_ID = R.ID;
                END LOOP;
                FOR Q IN (SELECT C.C_ID AS CID, C.NM FROM TB_C C) LOOP
                    DELETE FROM TB_X WHERE X_ID = Q.CID AND CID = 1;
                END LOOP;
                SELECT * INTO V_ROW FROM TB_A WHERE ID = P_ID;
                UPDATE TB_X SET VAL = V_ROW.NM, X_NM = V_REC.LABEL WHERE X_ID = V_ROW.ID AND K = V_REC.KEY_ID AND C = V_CUR.ST;
                UPDATE TB_A SET ST = 'X' WHERE ID = P_ID RETURNING NM INTO V_NM;
                DELETE FROM TB_C WHERE A_ID = P_ID RETURNING C_ID BULK COLLECT INTO V_IDS;
                SELECT ID BULK COLLECT INTO V_IDS FROM TB_A WHERE ST = V_ROW.ST;
                INSERT INTO TB_LOG (LOG_ID, MSG) VALUES (V_IDS(1), V_NM) RETURNING LOG_ID INTO V_REC.KEY_ID;
                FORALL I IN 1 .. V_IDS.COUNT
                    UPDATE TB_X SET VAL = 1 WHERE X_ID = V_IDS(I);
                IF SQL%ROWCOUNT = 0 OR SQL%NOTFOUND THEN
                    INSERT INTO TB_LOG (LOG_ID, MSG, AMT) VALUES (0, 'none', SQL%ROWCOUNT);
                END IF;
            EXCEPTION
                WHEN OTHERS THEN
                    V_ERR := SQLERRM;
                    INSERT INTO TB_LOG (LOG_ID, MSG) VALUES (SQLCODE, SUBSTR(SQLERRM, 1, 100));
                    UPDATE TB_X SET VAL = SQLCODE WHERE X_NM = SQLERRM;
            END;
            /
            """;

    @Test
    @DisplayName("F-008-AC17 PL/SQL: 커서 FOR 루프 레코드(r.col), %ROWTYPE·RECORD 필드, RETURNING/BULK COLLECT INTO 대상, SQL%ROWCOUNT·SQLERRM·SQLCODE는 컬럼도 테이블도 아니다")
    void plsqlRecordsAndSpecialNames() {
        try (TestProject project = TestProject.create().file("db/oracle/01_ddl.sql", DDL).file("db/oracle/sp_records.prc", PLSQL)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String r = "SP_RECORDS";

            assertThat(messages(g)).isEmpty();
            assertThat(tables(g)).containsExactly("db:table:TB_A", "db:table:TB_C", "db:table:TB_LOG", "db:table:TB_X");
            // PL/SQL의 커서 FOR 루프에서는 선택 목록의 이름이 변수가 아니다: TB_X.NM, TB_X.CID는 TB_X의 컬럼이다
            assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_X.C", "db:column:TB_X.CID", "db:column:TB_X.K", "db:column:TB_X.NM",
                    "db:column:TB_X.VAL", "db:column:TB_X.X_ID", "db:column:TB_X.X_NM");
            assertThat(GraphAssert.targets(g, stmt(r, PLSQL, "UPDATE TB_A SET ST = 'X'"), EdgeKind.READS))
                    .containsExactly("db:column:TB_A.ID", "db:column:TB_A.NM");
            assertThat(GraphAssert.targets(g, stmt(r, PLSQL, "DELETE FROM TB_C WHERE A_ID"), EdgeKind.READS))
                    .containsExactly("db:column:TB_C.A_ID", "db:column:TB_C.C_ID");
            assertThat(GraphAssert.targets(g, stmt(r, PLSQL, "SELECT ID BULK COLLECT"), EdgeKind.READS))
                    .containsExactly("db:column:TB_A.ID", "db:column:TB_A.ST", "db:table:TB_A");
        }
    }

    // ------------------------------------------------------------------ DB2 특수 변수·조건, 트리거

    private static final String DB2_SPECIAL = """
            --#SET TERMINATOR @
            CREATE PROCEDURE SP_SPECIAL (IN P_ID INTEGER, OUT P_CNT INTEGER)
            LANGUAGE SQL
            BEGIN
                DECLARE SQLCODE INTEGER DEFAULT 0;
                DECLARE SQLSTATE CHAR(5) DEFAULT '00000';
                DECLARE V_DONE SMALLINT DEFAULT 0;
                DECLARE V_MSG VARCHAR(200);
                DECLARE C_NOT_FOUND CONDITION FOR SQLSTATE '02000';
                DECLARE C_DUP CONDITION FOR SQLSTATE VALUE '23505';
                DECLARE CONTINUE HANDLER FOR C_NOT_FOUND SET V_DONE = 1;
                DECLARE EXIT HANDLER FOR C_DUP, SQLEXCEPTION
                BEGIN
                    GET DIAGNOSTICS EXCEPTION 1 V_MSG = MESSAGE_TEXT;
                    INSERT INTO TB_LOG (LOG_ID, MSG) VALUES (SQLCODE, SQLSTATE || V_MSG);
                END;
                UPDATE TB_A SET ST = '9' WHERE ID = P_ID;
                GET DIAGNOSTICS P_CNT = ROW_COUNT;
                IF SQLCODE <> 0 OR SQLSTATE = '02000' THEN
                    UPDATE TB_X SET VAL = SQLCODE, CNT = P_CNT WHERE X_ST = SQLSTATE AND X_DONE = V_DONE;
                    SIGNAL C_NOT_FOUND SET MESSAGE_TEXT = 'none';
                END IF;
                UPDATE TB_LOG SET AMT = P_CNT WHERE LOG_ID = P_ID AND MSG = CURRENT USER AND AMT < DAYS(CURRENT DATE);
            END@
            CREATE TRIGGER TRG_C_AUDIT
                AFTER UPDATE OF NM ON TB_C
                REFERENCING NEW AS N OLD AS O
                FOR EACH ROW MODE DB2SQL
            BEGIN ATOMIC
                INSERT INTO TB_LOG (LOG_ID, MSG) VALUES (N.C_ID, O.NM || N.NM);
                UPDATE TB_X SET VAL = N.NM WHERE X_ID = O.C_ID;
            END@
            """;

    private static final String ORA_TRIGGER = """
            CREATE OR REPLACE TRIGGER TRG_A_AUDIT
                BEFORE INSERT OR UPDATE OR DELETE ON TB_A
                FOR EACH ROW
            DECLARE
                V_OP VARCHAR2(1);
            BEGIN
                IF INSERTING THEN
                    V_OP := 'I';
                ELSIF UPDATING('ST') THEN
                    V_OP := 'U';
                END IF;
                INSERT INTO TB_LOG (LOG_ID, MSG)
                VALUES (NVL(:NEW.ID, :OLD.ID), CASE WHEN INSERTING THEN 'I' WHEN UPDATING THEN 'U' WHEN DELETING THEN 'D' END);
                UPDATE TB_X SET VAL = :NEW.NM, OP = V_OP WHERE X_ID = :OLD.ID AND (INSERTING OR DELETING);
            END;
            /
            """;

    @Test
    @DisplayName("F-008-AC17 DB2의 SQLCODE·SQLSTATE 변수, DECLARE … CONDITION, GET DIAGNOSTICS 대상, 트리거 상관 이름과 Oracle 트리거의 :NEW/:OLD·INSERTING/UPDATING/DELETING은 컬럼이 아니다")
    void specialVariablesAndTriggers() {
        try (TestProject project = TestProject.create().file("db/db2/01_ddl.sql", DB2_DDL).file("db/db2/sp_special.db2", DB2_SPECIAL)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(messages(g)).isEmpty();
            assertThat(tables(g)).containsExactly("db:table:TB_A", "db:table:TB_C", "db:table:TB_LOG", "db:table:TB_X");
            assertThat(undeclaredColumns(g)).containsExactly(
                    "db:column:TB_X.CNT", "db:column:TB_X.VAL", "db:column:TB_X.X_DONE", "db:column:TB_X.X_ID", "db:column:TB_X.X_ST");
            assertThat(g.nodes(NodeKind.DB_ROUTINE).stream().map(Node::id)).containsExactlyInAnyOrder("db:routine:SP_SPECIAL", "db:routine:TRG_C_AUDIT");
        }
        try (TestProject project = TestProject.create().file("db/oracle/01_ddl.sql", DDL).file("db/oracle/trg_a.trg", ORA_TRIGGER)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(messages(g)).isEmpty();
            assertThat(tables(g)).containsExactly("db:table:TB_A", "db:table:TB_C", "db:table:TB_LOG", "db:table:TB_X");
            assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_X.OP", "db:column:TB_X.VAL", "db:column:TB_X.X_ID");
        }
    }

    // ------------------------------------------------------------------ 고정 컬럼: 레코드 필드도 변수다

    private static final String FIXED = """
            CREATE OR REPLACE PROCEDURE SP_FIXED (P_ST IN VARCHAR2) IS
            BEGIN
                FOR R IN (SELECT ID, ST FROM TB_A) LOOP
                    INSERT INTO TB_LOG (LOG_ID, MSG)
                    SELECT C.C_ID, A.NM
                      FROM TB_C C, TB_A A
                     WHERE C.A_ID = A.ID
                       AND A.ST = R.ST
                       AND C.NM = P_ST;
                END LOOP;
            END;
            /
            """;

    @Test
    @DisplayName("F-008-AC17 레코드 필드와 비교한 컬럼도 변수와 비교한 것이다: JOINS의 고정 컬럼에 COL=?로 남는다(F-006-R13)")
    void recordFieldIsAConstantForFixedColumns() {
        try (TestProject project = TestProject.create().file("db/oracle/01_ddl.sql", DDL).file("db/oracle/sp_fixed.prc", FIXED)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            Edge join = edge(g, "db:table:TB_C", EdgeKind.JOINS, "db:table:TB_A");
            assertThat(join.attrList("leftFixedValues")).containsExactly("NM=?");
            assertThat(join.attrList("rightFixedValues")).containsExactly("ST=?");
            assertThat(messages(g)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ DDL에 없는 컬럼 참조(루틴 본문)

    private static final String UNDECLARED = """
            CREATE OR REPLACE PROCEDURE SP_UNDECLARED (P_ID IN NUMBER) IS
                V_NM  VARCHAR2(10);
                V_CNT NUMBER;
            BEGIN
                SELECT NM
                  INTO V_NM
                  FROM TB_A
                 WHERE ID = P_ID
                   AND DEL_YN = 'N';
                UPDATE TB_C SET NM = V_NM, UPD_ID = P_ID WHERE A_ID = P_ID AND USE_AT = 'Y';
                SELECT COUNT(*)
                  INTO V_CNT
                  FROM TB_A A, TB_C C
                 WHERE C.A_ID = A.ID
                   AND GHOST_COL = P_ID;
                FOR R IN (SELECT ID, SORT_NO FROM TB_A ORDER BY SORT_NO) LOOP
                    DELETE FROM TB_LOG WHERE LOG_ID = R.ID AND SEQ_NO = R.SORT_NO;
                END LOOP;
            END;
            /
            """;

    @Test
    @DisplayName("F-008-AC18 루틴 본문에서도 DDL에 없는 비한정 컬럼은 스코프의 테이블이 하나면 declared=false 컬럼 참조가 되고, 여럿이면 UNKNOWN_COLUMN(INFO)이다(매퍼 구문과 같은 규칙)")
    void undeclaredColumnsInRoutineBody() {
        try (TestProject project = TestProject.create().file("db/oracle/01_ddl.sql", DDL).file("db/oracle/sp_undeclared.prc", UNDECLARED)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String r = "SP_UNDECLARED";

            assertThat(undeclaredColumns(g)).containsExactly("db:column:TB_A.DEL_YN", "db:column:TB_A.SORT_NO", "db:column:TB_C.UPD_ID",
                    "db:column:TB_C.USE_AT", "db:column:TB_LOG.SEQ_NO");
            assertThat(hasEdge(g, stmt(r, UNDECLARED, "SELECT NM"), EdgeKind.READS, "db:column:TB_A.DEL_YN")).isTrue();
            assertThat(hasEdge(g, stmt(r, UNDECLARED, "UPDATE TB_C SET NM"), EdgeKind.READS, "db:column:TB_C.USE_AT")).isTrue();
            assertThat(hasEdge(g, stmt(r, UNDECLARED, "UPDATE TB_C SET NM"), EdgeKind.WRITES, "db:column:TB_C.UPD_ID")).isTrue();
            assertThat(hasEdge(g, stmt(r, UNDECLARED, "SELECT ID, SORT_NO"), EdgeKind.READS, "db:column:TB_A.SORT_NO")).isTrue();
            assertThat(hasEdge(g, stmt(r, UNDECLARED, "DELETE FROM TB_LOG"), EdgeKind.READS, "db:column:TB_LOG.SEQ_NO")).isTrue();
            assertThat(GraphAssert.node(g, "db:column:TB_A.DEL_YN").declared()).isFalse();
            // 테이블이 여럿인 구문만 진단으로 남는다. 변수·매개변수는 컬럼도 진단도 아니다
            List<Diagnostic> diagnostics = unknown(g);
            assertThat(diagnostics).hasSize(1);
            assertThat(diagnostics.get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(diagnostics.get(0).message()).contains("GHOST_COL").contains("TB_A, TB_C").doesNotContain("P_ID").doesNotContain("V_");
            assertThat(g.diagnostics()).hasSize(1);
            assertThat(g.nodes(NodeKind.DB_COLUMN).stream().map(Node::id)).noneMatch(id -> id.endsWith(".P_ID") || id.endsWith(".V_NM") || id.endsWith(".V_CNT"));
        }
    }

    private static final String ASSIGNED_ONLY = """
            CREATE OR REPLACE PACKAGE BODY PKG_ELSEWHERE AS
                PROCEDURE RUN (P_ID IN NUMBER) IS
                BEGIN
                    G_LAST_ID := P_ID;
                    SELECT NM INTO G_LAST_NM FROM TB_A WHERE ID = G_LAST_ID;
                    UPDATE TB_C SET NM = G_LAST_NM WHERE A_ID = G_LAST_ID;
                END RUN;
            END PKG_ELSEWHERE;
            /
            """;

    @Test
    @DisplayName("F-008-AC18 선언을 보지 못했어도 본문에서 대입(:=, SET, INTO)된 이름은 변수다: DDL에 없는 컬럼으로 만들지 않는다")
    void assignedNamesAreVariables() {
        // 패키지 명세(다른 파일, 분석 대상 밖)에 선언된 패키지 변수 G_LAST_ID, G_LAST_NM
        try (TestProject project = TestProject.create().file("db/oracle/01_ddl.sql", DDL).file("db/oracle/pkg_elsewhere.pkb", ASSIGNED_ONLY)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(undeclaredColumns(g)).isEmpty();
            assertThat(unknown(g)).isEmpty();
        }
    }

    private static final String BROKEN_DECLARATIONS = """
            CREATE OR REPLACE PROCEDURE SP_BROKEN_DECL (P_ID IN NUMBER) IS
                V_OK NUMBER;
                ( V_LOST NUMBER;
            BEGIN
                UPDATE TB_A SET NM = 'x' WHERE ID = P_ID AND ST = V_LOST;
                UPDATE TB_C SET NM = 'y' WHERE A_ID = V_LOST;
            END;
            /
            """;

    @Test
    @DisplayName("F-008-AC18 본문 구조를 다 읽지 못한 루틴(PARSE_FAILED)에서는 DDL에 없는 이름을 추측하지 않는다: 놓친 선언의 변수일 수 있다")
    void noGuessWhenBodyHasParseProblems() {
        try (TestProject project = TestProject.create().file("db/oracle/01_ddl.sql", DDL).file("db/oracle/sp_broken.prc", BROKEN_DECLARATIONS)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(GraphAssert.codes(g)).contains("PARSE_FAILED");
            assertThat(undeclaredColumns(g)).isEmpty();
            assertThat(unknown(g)).isEmpty();
            assertThat(hasEdge(g, stmt("SP_BROKEN_DECL", BROKEN_DECLARATIONS, "UPDATE TB_A"), EdgeKind.WRITES, "db:table:TB_A")).isTrue();
        }
    }
}
