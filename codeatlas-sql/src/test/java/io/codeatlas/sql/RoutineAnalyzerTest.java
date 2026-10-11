package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-008 프로시저 분석: 루틴 본문의 SQL 구문, 호출, 변수 스코프, 동적 SQL을 그래프로 확인한다. */
class RoutineAnalyzerTest {

    /** needle이 처음 나오는 줄(1부터). */
    static int line(String text, String needle) {
        int i = text.indexOf(needle);
        if (i < 0) {
            throw new AssertionError("픽스처에 없음: " + needle);
        }
        return (int) text.substring(0, i).chars().filter(c -> c == '\n').count() + 1;
    }

    /** 루틴 안 구문의 ID: needle이 있는 줄. */
    static String stmt(String routine, String text, String needle) {
        return Ids.routineStatement(routine, line(text, needle));
    }

    /** 루틴이 CONTAINS 하는 구문들에서 kind로 나가는 엣지의 to 목록(정렬, 중복 제거). */
    static List<String> routineTargets(GraphModel g, String routineId, EdgeKind kind) {
        return g.outgoing(routineId, EdgeKind.CONTAINS).stream()
                .flatMap(c -> g.outgoing(c.to(), kind).stream()).map(Edge::to).distinct().sorted().collect(Collectors.toList());
    }

    static List<String> tableNodes(GraphModel g) {
        return g.nodes(NodeKind.DB_TABLE).stream().map(Node::id).sorted().collect(Collectors.toList());
    }

    static List<String> columnNodes(GraphModel g) {
        return g.nodes(NodeKind.DB_COLUMN).stream().map(Node::id).sorted().collect(Collectors.toList());
    }

    private static final String DDL = """
            CREATE TABLE TB_A (ID NUMBER PRIMARY KEY, NM VARCHAR2(10), ST VARCHAR2(2));
            CREATE TABLE TB_C (C_ID NUMBER PRIMARY KEY, A_ID NUMBER, NM VARCHAR2(10));
            """;

    // ------------------------------------------------------------------ AC2

    private static final String SP_BASIC = """
            CREATE OR REPLACE PROCEDURE SP_BASIC (P_ID IN NUMBER, P_NM IN VARCHAR2) IS
                V_CNT NUMBER;
            BEGIN
                SELECT COUNT(*)
                  INTO V_CNT
                  FROM TB_A A
                 WHERE A.ID = P_ID;

                UPDATE TB_A
                   SET NM = P_NM
                 WHERE ID = P_ID;

                INSERT INTO TB_B (ID, NM)
                SELECT A.ID, C.NM
                  FROM TB_A A
                  JOIN TB_C C ON C.A_ID = A.ID
                 WHERE A.ST = '1';

                DELETE FROM TB_D WHERE ID = P_ID;

                MERGE INTO TB_E E USING TB_A A ON (E.ID = A.ID)
                 WHEN MATCHED THEN UPDATE SET E.NM = A.NM;
            END SP_BASIC;
            /
            """;

    @Test
    @DisplayName("F-008-AC2 독립 프로시저: 구문 노드(ID=SQL 키워드 줄, attrs, 위치), CONTAINS, READS/WRITES 테이블·컬럼, JOINS")
    void statementsOfProcedure() {
        try (TestProject project = TestProject.create().file("db/oracle/01_ddl.sql", DDL).file("db/oracle/sp_basic.prc", SP_BASIC)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String routine = "db:routine:SP_BASIC";
            String select = stmt("SP_BASIC", SP_BASIC, "SELECT COUNT(*)");
            String update = stmt("SP_BASIC", SP_BASIC, "UPDATE TB_A");
            String insert = stmt("SP_BASIC", SP_BASIC, "INSERT INTO TB_B");
            String delete = stmt("SP_BASIC", SP_BASIC, "DELETE FROM TB_D");
            String merge = stmt("SP_BASIC", SP_BASIC, "MERGE INTO TB_E");
            assertThat(select).isEqualTo("sql:stmt:SP_BASIC:4");
            assertThat(targets(g, routine, EdgeKind.CONTAINS)).containsExactlyInAnyOrder(select, update, insert, delete, merge);

            Node s = node(g, select);
            assertThat(s.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
            assertThat(s.declared()).isTrue();
            assertThat(s.name()).isEqualTo("SP_BASIC:4");
            assertThat(s.attrString("statementType")).isEqualTo("SELECT");
            assertThat(s.attrString("dialect")).isEqualTo("ORACLE");
            assertThat(s.attrString("sql")).isEqualTo("SELECT COUNT(*) INTO V_CNT FROM TB_A A WHERE A.ID = P_ID");
            assertThat(s.attrString("routine")).isEqualTo(routine);
            assertThat(s.location()).isEqualTo(new SourceLocation("db/oracle/sp_basic.prc", 4, 5, 7, 23));
            assertThat(targets(g, select, EdgeKind.READS)).containsExactly("db:column:TB_A.ID", "db:table:TB_A");

            assertThat(node(g, update).attrString("statementType")).isEqualTo("UPDATE");
            assertThat(edge(g, update, EdgeKind.WRITES, "db:table:TB_A").attrString("op")).isEqualTo("UPDATE");
            assertThat(edge(g, update, EdgeKind.WRITES, "db:column:TB_A.NM").attrString("op")).isEqualTo("UPDATE");
            assertThat(targets(g, update, EdgeKind.READS)).containsExactly("db:column:TB_A.ID");

            assertThat(node(g, insert).attrString("statementType")).isEqualTo("INSERT");
            assertThat(edge(g, insert, EdgeKind.WRITES, "db:table:TB_B").attrString("op")).isEqualTo("INSERT");
            assertThat(targets(g, insert, EdgeKind.READS)).contains("db:table:TB_A", "db:table:TB_C", "db:column:TB_C.NM", "db:column:TB_A.ST");
            Edge join = edge(g, "db:table:TB_C", EdgeKind.JOINS, "db:table:TB_A");
            assertThat(join.attrString("statement")).isEqualTo(insert);
            assertThat(join.attrList("leftColumns")).containsExactly("A_ID");
            assertThat(join.attrList("rightColumns")).containsExactly("ID");

            assertThat(edge(g, delete, EdgeKind.WRITES, "db:table:TB_D").attrString("op")).isEqualTo("DELETE");
            assertThat(node(g, merge).attrString("statementType")).isEqualTo("MERGE");
            assertThat(edge(g, merge, EdgeKind.WRITES, "db:table:TB_E").attrString("op")).isEqualTo("MERGE");
            assertThat(hasEdge(g, merge, EdgeKind.READS, "db:table:TB_A")).isTrue();

            // 변수는 컬럼이 아니다
            assertThat(columnNodes(g)).noneMatch(id -> id.endsWith(".P_ID") || id.endsWith(".P_NM") || id.endsWith(".V_CNT"));
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC3

    private static final String SP_PLACES = """
            CREATE OR REPLACE PROCEDURE SP_PLACES (P_DT IN VARCHAR2, P_RC OUT SYS_REFCURSOR) IS
                CURSOR C_PAY (P_MIN NUMBER) IS
                    SELECT P.PAY_ID, P.AMT
                      FROM TB_PAY P
                     WHERE P.AMT > P_MIN;
                TYPE T_IDS IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
                V_IDS  T_IDS;
                R_PAY  C_PAY%ROWTYPE;
                V_NEW  NUMBER;
            BEGIN
                FOR R IN (SELECT O.ORDER_ID
                            FROM TB_ORD O, TB_DELIVERY D
                           WHERE O.ORDER_ID = D.ORDER_ID
                             AND O.ORDER_DT = P_DT)
                LOOP
                    NULL;
                END LOOP;

                OPEN C_PAY(100);
                LOOP
                    FETCH C_PAY INTO R_PAY;
                    EXIT WHEN C_PAY%NOTFOUND;
                    DELETE FROM TB_PAY_TMP WHERE PAY_ID = R_PAY.PAY_ID;
                END LOOP;
                CLOSE C_PAY;

                FOR R2 IN C_PAY(5) LOOP
                    NULL;
                END LOOP;

                SELECT T.ID BULK COLLECT INTO V_IDS FROM TB_TARGET T WHERE T.DT = P_DT;

                FORALL IDX IN 1 .. V_IDS.COUNT
                    UPDATE TB_STOCK S SET S.QTY = S.QTY - 1 WHERE S.ID = V_IDS(IDX);

                INSERT INTO TB_SEQ_LOG (ID, DT) VALUES (SEQ_LOG.NEXTVAL, P_DT)
                RETURNING ID INTO V_NEW;

                IF EXISTS_CHECK = 1 OR V_NEW > (SELECT MAX(X.LIMIT_NO) FROM TB_LIMIT X) THEN
                    NULL;
                END IF;

                OPEN P_RC FOR
                    SELECT M.MEMBER_ID FROM TB_MEMBER M WHERE M.JOIN_DT = P_DT;
            END;
            /
            """;

    private static final String DB2_PLACES = """
            --#SET TERMINATOR @
            CREATE OR REPLACE PROCEDURE SP_DB2_PLACES (IN P_YM CHAR(6), OUT P_CNT INTEGER)
            LANGUAGE SQL
            BEGIN
                DECLARE V_ID   BIGINT;
                DECLARE V_AMT  DECIMAL(15,2) DEFAULT 0;
                DECLARE V_EOF  SMALLINT DEFAULT 0;
                DECLARE C1 CURSOR WITH HOLD FOR
                    SELECT S.SETTLE_ID, S.AMT
                      FROM TB_SETTLE S
                     WHERE S.SETTLE_YM = P_YM;
                DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_EOF = 1;

                SET V_AMT = (SELECT SUM(D.AMT) FROM TB_SETTLE_DTL D WHERE D.YM = P_YM);
                SET (V_ID, V_AMT) = (SELECT MAX(H.ID), MAX(H.AMT) FROM TB_HIST H);
                VALUES NEXT VALUE FOR SEQ_SETTLE INTO V_ID;

                IF EXISTS (SELECT 1 FROM TB_LOCK L WHERE L.YM = P_YM) THEN
                    SET P_CNT = -1;
                END IF;

                OPEN C1;
                FETCH_LOOP: LOOP
                    FETCH C1 INTO V_ID, V_AMT;
                    IF V_EOF = 1 THEN LEAVE FETCH_LOOP; END IF;
                    UPDATE TB_SETTLE_SUM U SET U.AMT = U.AMT + V_AMT WHERE U.YM = P_YM;
                END LOOP FETCH_LOOP;
                CLOSE C1;

                FOR V AS C2 CURSOR FOR
                    SELECT M.MEMBER_ID AS MID FROM TB_MEMBER M WHERE M.STATUS_CD = 'A'
                DO
                    INSERT INTO TB_NOTICE (MEMBER_ID) VALUES (V.MID);
                END FOR;

                WHILE V_AMT > 0 DO
                    DELETE FROM TB_WORK W WHERE W.AMT = V_AMT;
                    SET V_AMT = V_AMT - 1;
                END WHILE;

                REPEAT
                    SET V_ID = V_ID - 1;
                    SELECT COUNT(*) INTO P_CNT FROM TB_REPEAT R WHERE R.ID = V_ID;
                UNTIL V_ID < 0
                END REPEAT;
            END@
            """;

    @Test
    @DisplayName("F-008-AC3 PL/SQL: 커서 선언, 커서 FOR 인라인 쿼리, OPEN FOR, BULK COLLECT, FORALL, RETURNING, 조건식의 서브쿼리가 구문 노드가 된다")
    void sqlPlacesPlsql() {
        try (TestProject project = TestProject.create().file("db/oracle/sp_places.prc", SP_PLACES)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String cursor = stmt("SP_PLACES", SP_PLACES, "SELECT P.PAY_ID");
            assertThat(targets(g, cursor, EdgeKind.READS)).contains("db:table:TB_PAY", "db:column:TB_PAY.AMT", "db:column:TB_PAY.PAY_ID");
            assertThat(node(g, cursor).attrString("cursor")).isEqualTo("C_PAY");
            assertThat(node(g, cursor).location().startLine()).isEqualTo(line(SP_PLACES, "SELECT P.PAY_ID"));

            String loop = stmt("SP_PLACES", SP_PLACES, "FOR R IN (SELECT");
            assertThat(targets(g, loop, EdgeKind.READS)).contains("db:table:TB_ORD", "db:table:TB_DELIVERY");
            assertThat(edge(g, "db:table:TB_ORD", EdgeKind.JOINS, "db:table:TB_DELIVERY").attrString("statement")).isEqualTo(loop);

            assertThat(edge(g, stmt("SP_PLACES", SP_PLACES, "DELETE FROM TB_PAY_TMP"), EdgeKind.WRITES, "db:table:TB_PAY_TMP").attrString("op"))
                    .isEqualTo("DELETE");
            assertThat(hasEdge(g, stmt("SP_PLACES", SP_PLACES, "SELECT T.ID BULK"), EdgeKind.READS, "db:table:TB_TARGET")).isTrue();
            String forall = stmt("SP_PLACES", SP_PLACES, "UPDATE TB_STOCK");
            assertThat(edge(g, forall, EdgeKind.WRITES, "db:table:TB_STOCK").attrString("op")).isEqualTo("UPDATE");
            String insert = stmt("SP_PLACES", SP_PLACES, "INSERT INTO TB_SEQ_LOG");
            assertThat(hasEdge(g, insert, EdgeKind.WRITES, "db:table:TB_SEQ_LOG")).isTrue();
            assertThat(edge(g, insert, EdgeKind.READS, "db:sequence:SEQ_LOG").attrBoolean("sequence")).isTrue();
            String cond = stmt("SP_PLACES", SP_PLACES, "(SELECT MAX(X.LIMIT_NO)");
            assertThat(hasEdge(g, cond, EdgeKind.READS, "db:table:TB_LIMIT")).isTrue();
            assertThat(node(g, cond).attrString("sql")).isEqualTo("SELECT MAX(X.LIMIT_NO) FROM TB_LIMIT X");
            String open = stmt("SP_PLACES", SP_PLACES, "SELECT M.MEMBER_ID");
            assertThat(hasEdge(g, open, EdgeKind.READS, "db:table:TB_MEMBER")).isTrue();

            assertThat(targets(g, "db:routine:SP_PLACES", EdgeKind.CONTAINS)).hasSize(8);
            assertThat(tableNodes(g)).containsExactly("db:table:TB_DELIVERY", "db:table:TB_LIMIT", "db:table:TB_MEMBER", "db:table:TB_ORD",
                    "db:table:TB_PAY", "db:table:TB_PAY_TMP", "db:table:TB_SEQ_LOG", "db:table:TB_STOCK", "db:table:TB_TARGET");
            assertThat(columnNodes(g)).noneMatch(id -> id.endsWith(".P_DT") || id.endsWith(".P_MIN") || id.endsWith(".V_NEW")
                    || id.endsWith(".IDX") || id.endsWith(".V_IDS") || id.contains("R_PAY"));
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-008-AC3 SQL PL: DECLARE CURSOR FOR, SET = (SELECT), VALUES INTO, IF EXISTS, FOR AS CURSOR DO, WHILE/REPEAT 안의 구문")
    void sqlPlacesDb2() {
        try (TestProject project = TestProject.create().file("db/db2/sp_places.db2", DB2_PLACES)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String r = "SP_DB2_PLACES";
            String cursor = stmt(r, DB2_PLACES, "SELECT S.SETTLE_ID");
            assertThat(hasEdge(g, cursor, EdgeKind.READS, "db:table:TB_SETTLE")).isTrue();
            assertThat(node(g, cursor).attrString("cursor")).isEqualTo("C1");
            assertThat(node(g, cursor).attrString("dialect")).isEqualTo("DB2");
            assertThat(hasEdge(g, stmt(r, DB2_PLACES, "SET V_AMT = (SELECT"), EdgeKind.READS, "db:table:TB_SETTLE_DTL")).isTrue();
            assertThat(hasEdge(g, stmt(r, DB2_PLACES, "SET (V_ID, V_AMT)"), EdgeKind.READS, "db:table:TB_HIST")).isTrue();
            assertThat(edge(g, stmt(r, DB2_PLACES, "VALUES NEXT VALUE"), EdgeKind.READS, "db:sequence:SEQ_SETTLE").attrBoolean("sequence")).isTrue();
            assertThat(hasEdge(g, stmt(r, DB2_PLACES, "IF EXISTS (SELECT 1"), EdgeKind.READS, "db:table:TB_LOCK")).isTrue();
            assertThat(edge(g, stmt(r, DB2_PLACES, "UPDATE TB_SETTLE_SUM"), EdgeKind.WRITES, "db:table:TB_SETTLE_SUM").attrString("op")).isEqualTo("UPDATE");
            assertThat(hasEdge(g, stmt(r, DB2_PLACES, "SELECT M.MEMBER_ID AS MID"), EdgeKind.READS, "db:table:TB_MEMBER")).isTrue();
            assertThat(hasEdge(g, stmt(r, DB2_PLACES, "INSERT INTO TB_NOTICE"), EdgeKind.WRITES, "db:table:TB_NOTICE")).isTrue();
            assertThat(hasEdge(g, stmt(r, DB2_PLACES, "DELETE FROM TB_WORK"), EdgeKind.WRITES, "db:table:TB_WORK")).isTrue();
            assertThat(hasEdge(g, stmt(r, DB2_PLACES, "SELECT COUNT(*) INTO P_CNT"), EdgeKind.READS, "db:table:TB_REPEAT")).isTrue();
            assertThat(targets(g, "db:routine:" + r, EdgeKind.CONTAINS)).hasSize(10);
            assertThat(tableNodes(g)).containsExactly("db:table:TB_HIST", "db:table:TB_LOCK", "db:table:TB_MEMBER", "db:table:TB_NOTICE",
                    "db:table:TB_REPEAT", "db:table:TB_SETTLE", "db:table:TB_SETTLE_DTL", "db:table:TB_SETTLE_SUM", "db:table:TB_WORK");
            assertThat(columnNodes(g)).noneMatch(id -> id.endsWith(".P_YM") || id.endsWith(".V_AMT") || id.endsWith(".V_ID") || id.endsWith(".MID"));
            assertThat(node(g, "db:routine:" + r).attrString("language")).isEqualTo("SQLPL");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC4

    private static final String PKG_VAR_SPEC = """
            CREATE OR REPLACE PACKAGE PKG_VAR AS
                C_DONE   CONSTANT VARCHAR2(2) := '40';
                G_LIMIT  NUMBER := 100;
                PROCEDURE RUN (P_ID IN NUMBER);
            END PKG_VAR;
            /
            """;

    private static final String PKG_VAR_BODY = """
            CREATE OR REPLACE PACKAGE BODY PKG_VAR AS
                G_PRIVATE  VARCHAR2(10) := 'x';

                PROCEDURE RUN (P_ID IN NUMBER) IS
                    V_ST    TB_TYPE_ONLY.STATUS%TYPE;
                    V_ROW   TB_ROW_ONLY%ROWTYPE;
                    V_NM    VARCHAR2(10);
                BEGIN
                    SELECT X.NM INTO V_NM FROM TB_X X WHERE X.ID = P_ID;
                    UPDATE TB_Y
                       SET STATUS = C_DONE, LIMIT_NO = G_LIMIT, MEMO = G_PRIVATE, NM = V_NM
                     WHERE Y_ID = P_ID AND ST = V_ST;
                    DECLARE
                        V_INNER NUMBER := 1;
                    BEGIN
                        FOR REC IN (SELECT Z.ID, Z.QTY FROM TB_Z Z WHERE Z.QTY > V_INNER) LOOP
                            DELETE FROM TB_W WHERE W_ID = REC.ID AND QTY = V_INNER AND OWNER_ID = P_ID;
                        END LOOP;
                    END;
                    INSERT INTO TB_V (ID, NM) VALUES (V_ROW.ID, V_ROW.NM);
                END RUN;
            END PKG_VAR;
            /
            """;

    @Test
    @DisplayName("F-008-AC4 변수 스코프: 매개변수·지역·중첩 블록·패키지 변수는 컬럼이 아니고, 레코드와 %TYPE/%ROWTYPE 대상은 테이블이 아니다")
    void variableScopes() {
        // 본문 파일이 명세 파일보다 먼저 분석돼도 명세의 상수를 안다
        try (TestProject project = TestProject.create().file("db/oracle/a_pkg_var.pkb", PKG_VAR_BODY).file("db/oracle/b_pkg_var.pks", PKG_VAR_SPEC)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(tableNodes(g)).containsExactly("db:table:TB_V", "db:table:TB_W", "db:table:TB_X", "db:table:TB_Y", "db:table:TB_Z");
            assertThat(columnNodes(g)).containsExactly(
                    "db:column:TB_V.ID", "db:column:TB_V.NM",
                    "db:column:TB_W.OWNER_ID", "db:column:TB_W.QTY", "db:column:TB_W.W_ID",
                    "db:column:TB_X.ID", "db:column:TB_X.NM",
                    "db:column:TB_Y.LIMIT_NO", "db:column:TB_Y.MEMO", "db:column:TB_Y.NM", "db:column:TB_Y.ST", "db:column:TB_Y.STATUS", "db:column:TB_Y.Y_ID",
                    "db:column:TB_Z.ID", "db:column:TB_Z.QTY");
            assertThat(routineTargets(g, "db:routine:PKG_VAR.RUN", EdgeKind.WRITES)).contains("db:table:TB_Y", "db:table:TB_W", "db:table:TB_V");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC5

    private static final String CALLS = """
            CREATE OR REPLACE FUNCTION FN_X (P NUMBER) RETURN NUMBER IS
            BEGIN
                RETURN P;
            END;
            /
            CREATE OR REPLACE PROCEDURE SP_A (P NUMBER) IS
            BEGIN
                NULL;
            END;
            /
            CREATE OR REPLACE PACKAGE PKG AS
                PROCEDURE B;
                FUNCTION F (P NUMBER) RETURN NUMBER;
            END;
            /
            CREATE OR REPLACE PROCEDURE SP_CALLER (P_ID NUMBER) IS
                TYPE T_TAB IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
                V      NUMBER;
                V_TAB  T_TAB;
                FN_SHADOW NUMBER;
            BEGIN
                SP_A(1);
                PKG.B;
                SHOP.SP_A(2);
                V := FN_X(1) + PKG.F(2) + NVL(V, 0) + TO_NUMBER('1');
                IF FN_X(V) > 0 AND UNKNOWN_FN(V) = 1 THEN
                    SP_UNKNOWN(V);
                    OTHER_PKG.DO_IT(V, P_NAME => FN_X(3));
                END IF;
                V := V_TAB(1) + V_TAB.COUNT + FN_SHADOW;
                V_TAB.DELETE;
                V_TAB(2) := UNKNOWN_FN2(V);
                DBMS_OUTPUT.PUT_LINE('x' || FN_X(4));
                RAISE_APPLICATION_ERROR(-20001, 'x');
                UTL_FILE.FCLOSE_ALL;
                SP_CALLER(P_ID - 1);
            END;
            /
            """;

    private static final String DB2_CALLS = """
            --#SET TERMINATOR @
            CREATE PROCEDURE SP_LOG (IN P_MSG VARCHAR(100))
            LANGUAGE SQL
            BEGIN
                INSERT INTO TB_LOG (MSG) VALUES (P_MSG);
            END@
            CREATE FUNCTION FN_RATE (P_CD CHAR(2)) RETURNS DECIMAL(5,2)
            LANGUAGE SQL
            RETURN (SELECT R.RATE FROM TB_RATE R WHERE R.CD = P_CD)@
            CREATE PROCEDURE SP_DB2_CALLER (IN P_CD CHAR(2))
            LANGUAGE SQL
            BEGIN
                DECLARE V_RATE DECIMAL(5,2);
                DECLARE EXIT HANDLER FOR SQLEXCEPTION
                BEGIN
                    CALL SP_LOG('error');
                    RESIGNAL;
                END;
                SET V_RATE = FN_RATE(P_CD) * COALESCE(V_RATE, 1);
                CALL SP_LOG('rate=' || CHAR(V_RATE));
                CALL SP_ELSEWHERE(P_CD);
                CALL SYSPROC.ADMIN_CMD('RUNSTATS ON TABLE TB_LOG');
                IF V_RATE < 0 THEN
                    SIGNAL SQLSTATE '75001' SET MESSAGE_TEXT = 'negative';
                END IF;
            END@
            """;

    @Test
    @DisplayName("F-008-AC5 PL/SQL 호출: 호출문·식 안의 정의된 함수는 CALLS, 내장·컬렉션·식 안의 미정의 함수는 엣지 없음")
    void callsPlsql() {
        try (TestProject project = TestProject.create()
                .file("db/oracle/00.sql", "CREATE TABLE SHOP.TB_S (ID NUMBER);\n").file("db/oracle/calls.sql", CALLS)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String caller = "db:routine:SP_CALLER";
            assertThat(targets(g, caller, EdgeKind.CALLS)).containsExactly(
                    "db:routine:FN_X", "db:routine:OTHER_PKG.DO_IT", "db:routine:PKG.B", "db:routine:PKG.F",
                    "db:routine:SP_A", "db:routine:SP_CALLER", "db:routine:SP_UNKNOWN");
            assertThat(node(g, "db:routine:SP_UNKNOWN").declared()).isFalse();
            assertThat(node(g, "db:routine:OTHER_PKG.DO_IT").declared()).isFalse();
            assertThat(node(g, "db:routine:SP_A").declared()).isTrue();
            // 호출 지점마다 엣지 하나, 위치는 호출 이름
            List<Edge> toFn = g.edges(caller, EdgeKind.CALLS, "db:routine:FN_X");
            assertThat(toFn).hasSize(4);
            assertThat(toFn).allMatch(e -> "function".equals(e.attrString("callType")));
            assertThat(toFn.get(0).location().startLine()).isEqualTo(line(CALLS, "V := FN_X(1)"));
            Edge toA = g.edges(caller, EdgeKind.CALLS, "db:routine:SP_A").get(0);
            assertThat(toA.attrString("callType")).isEqualTo("procedure");
            assertThat(toA.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(g.edges(caller, EdgeKind.CALLS, "db:routine:SP_A")).hasSize(2);
            assertThat(g.nodes(NodeKind.DB_ROUTINE)).noneMatch(n -> n.id().contains("DBMS_") || n.id().contains("UTL_") || n.id().contains("NVL")
                    || n.id().contains("UNKNOWN_FN") || n.id().contains("V_TAB") || n.id().contains("RAISE_") || n.id().contains("FN_SHADOW"));
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-008-AC5 SQL PL 호출: CALL 문(핸들러 안 포함), SET 식의 함수 호출, SYSPROC 무시, RETURN (SELECT) 함수 본문")
    void callsDb2() {
        try (TestProject project = TestProject.create().file("db/db2/calls.db2", DB2_CALLS)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String caller = "db:routine:SP_DB2_CALLER";
            assertThat(targets(g, caller, EdgeKind.CALLS)).containsExactly("db:routine:FN_RATE", "db:routine:SP_ELSEWHERE", "db:routine:SP_LOG");
            assertThat(g.edges(caller, EdgeKind.CALLS, "db:routine:SP_LOG")).hasSize(2);
            assertThat(node(g, "db:routine:SP_ELSEWHERE").declared()).isFalse();
            assertThat(g.nodes(NodeKind.DB_ROUTINE)).noneMatch(n -> n.id().contains("ADMIN_CMD") || n.id().contains("COALESCE") || n.id().contains("CHAR"));
            assertThat(routineTargets(g, "db:routine:FN_RATE", EdgeKind.READS)).contains("db:table:TB_RATE");
            assertThat(routineTargets(g, "db:routine:SP_LOG", EdgeKind.WRITES)).contains("db:table:TB_LOG");
            assertThat(node(g, "db:routine:FN_RATE").attrString("returnType")).isEqualTo("DECIMAL(5,2)");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC6, AC7

    private static final String PKG_SPEC = """
            CREATE OR REPLACE PACKAGE PKG_SALES AS
                PROCEDURE CLOSE_ALL (P_DT IN VARCHAR2);
                FUNCTION GET_AMT (P_ID IN NUMBER) RETURN NUMBER;
                PROCEDURE NOT_IMPLEMENTED (P_X NUMBER);
            END PKG_SALES;
            /
            """;

    private static final String PKG_BODY = """
            CREATE OR REPLACE PACKAGE BODY PKG_SALES AS
                G_COUNT NUMBER := 0;

                PROCEDURE LOG_IT (P_MSG IN VARCHAR2) IS
                    PRAGMA AUTONOMOUS_TRANSACTION;
                BEGIN
                    INSERT INTO TB_LOG (MSG, REG_DT) VALUES (P_MSG, SYSDATE);
                    COMMIT;
                END LOG_IT;

                FUNCTION GET_AMT (P_ID IN NUMBER) RETURN NUMBER IS
                    V_AMT NUMBER;
                BEGIN
                    SELECT SUM(I.AMT) INTO V_AMT FROM TB_ITEM I WHERE I.ORDER_ID = P_ID;
                    RETURN NVL(V_AMT, 0);
                EXCEPTION
                    WHEN NO_DATA_FOUND THEN
                        RETURN 0;
                END GET_AMT;

                PROCEDURE CLOSE_ONE (P_ID IN NUMBER) IS
                    V_AMT  NUMBER;
                    PROCEDURE TOUCH (P_KEY NUMBER) IS
                    BEGIN
                        UPDATE TB_TOUCH SET TOUCH_DT = SYSDATE WHERE ID = P_KEY;
                        LOG_IT('touch');
                    END TOUCH;
                BEGIN
                    V_AMT := GET_AMT(P_ID);
                    SELECT FN_TAX(O.AMT), GET_AMT(O.ORDER_ID), NVL(O.AMT, 0)
                      INTO V_AMT, V_AMT, V_AMT
                      FROM TB_ORD O
                     WHERE O.ORDER_ID = P_ID;
                    TOUCH(P_ID);
                    PKG_SALES.LOG_IT('closed');
                EXCEPTION
                    WHEN OTHERS THEN
                        ROLLBACK;
                        LOG_IT(SQLERRM);
                        RAISE;
                END CLOSE_ONE;

                PROCEDURE CLOSE_ALL (P_DT IN VARCHAR2) IS
                BEGIN
                    FOR R IN (SELECT O.ORDER_ID FROM TB_ORD O WHERE O.ORDER_DT = P_DT) LOOP
                        CLOSE_ONE(R.ORDER_ID);
                    END LOOP;
                    SP_DAILY_SUM(P_DT);
                END CLOSE_ALL;
            BEGIN
                G_COUNT := FN_TAX(0);
                DELETE FROM TB_PKG_INIT;
            END PKG_SALES;
            /
            CREATE OR REPLACE FUNCTION FN_TAX (P NUMBER) RETURN NUMBER IS
            BEGIN
                RETURN P * 0.1;
            END FN_TAX;
            /
            CREATE OR REPLACE PROCEDURE SP_DAILY_SUM (P_DT VARCHAR2) IS
            BEGIN
                NULL;
            END;
            /
            """;

    @Test
    @DisplayName("F-008-AC6 패키지 내부 호출은 db:routine:PKG.NAME, 독립 프로시저는 db:routine:NAME, 지역 서브프로그램은 바깥 루틴에 귀속")
    void packageInternalCalls() {
        try (TestProject project = TestProject.create().file("db/oracle/pkg_sales.pks", PKG_SPEC).file("db/oracle/pkg_sales.pkb", PKG_BODY)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String one = "db:routine:PKG_SALES.CLOSE_ONE";
            assertThat(targets(g, one, EdgeKind.CALLS)).containsExactly("db:routine:PKG_SALES.GET_AMT", "db:routine:PKG_SALES.LOG_IT");
            assertThat(g.edges(one, EdgeKind.CALLS, "db:routine:PKG_SALES.LOG_IT")).hasSize(3);
            assertThat(targets(g, "db:routine:PKG_SALES.CLOSE_ALL", EdgeKind.CALLS)).containsExactly("db:routine:PKG_SALES.CLOSE_ONE", "db:routine:SP_DAILY_SUM");
            // 지역 서브프로그램: 노드 없음, 구문은 바깥 루틴 소속
            assertThat(g.nodes(NodeKind.DB_ROUTINE)).noneMatch(n -> n.id().endsWith("TOUCH") || n.id().equals("db:routine:LOG_IT")
                    || n.id().equals("db:routine:GET_AMT") || n.id().equals("db:routine:CLOSE_ONE") || n.id().equals("db:routine:PKG_SALES.SP_DAILY_SUM"));
            String touch = stmt("PKG_SALES.CLOSE_ONE", PKG_BODY, "UPDATE TB_TOUCH");
            assertThat(hasEdge(g, one, EdgeKind.CONTAINS, touch)).isTrue();
            assertThat(hasEdge(g, touch, EdgeKind.WRITES, "db:table:TB_TOUCH")).isTrue();
            assertThat(columnNodes(g)).doesNotContain("db:column:TB_TOUCH.P_KEY");
            assertThat(routineTargets(g, "db:routine:PKG_SALES.LOG_IT", EdgeKind.WRITES)).contains("db:table:TB_LOG");
            // 패키지 초기화부는 패키지 노드가 주인
            String init = stmt("PKG_SALES", PKG_BODY, "DELETE FROM TB_PKG_INIT");
            assertThat(hasEdge(g, "db:package:PKG_SALES", EdgeKind.CONTAINS, init)).isTrue();
            assertThat(hasEdge(g, init, EdgeKind.WRITES, "db:table:TB_PKG_INIT")).isTrue();
            assertThat(targets(g, "db:package:PKG_SALES", EdgeKind.CALLS)).containsExactly("db:routine:FN_TAX");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-008-AC7 본문의 SQL 구문 안 사용자 함수 호출: 구문→ROUTINE CALLS (같은 패키지 함수는 PKG.NAME)")
    void functionCallsInsideSql() {
        try (TestProject project = TestProject.create().file("db/oracle/pkg_sales.pks", PKG_SPEC).file("db/oracle/pkg_sales.pkb", PKG_BODY)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String select = stmt("PKG_SALES.CLOSE_ONE", PKG_BODY, "SELECT FN_TAX(O.AMT)");
            assertThat(targets(g, select, EdgeKind.CALLS)).containsExactly("db:routine:FN_TAX", "db:routine:PKG_SALES.GET_AMT");
            assertThat(hasEdge(g, select, EdgeKind.READS, "db:table:TB_ORD")).isTrue();
        }
    }

    // ------------------------------------------------------------------ AC8, AC9

    private static final String DYN_OK = """
            CREATE OR REPLACE PROCEDURE SP_DYN_OK (P_ID NUMBER, P_RC OUT SYS_REFCURSOR) IS
                V_SQL   VARCHAR2(1000);
                V_STMT  VARCHAR2(1000) := 'INSERT INTO T5 (ID) VALUES (:1)';
                V_CUR   INTEGER;
            BEGIN
                EXECUTE IMMEDIATE 'DELETE FROM T1 WHERE ID = :1' USING P_ID;
                V_SQL := 'UPDATE T2 '
                      || 'SET X = ''a'' WHERE ID = 1';
                EXECUTE IMMEDIATE V_SQL;
                OPEN P_RC FOR 'SELECT * FROM T3 WHERE ID = :1' USING P_ID;
                EXECUTE IMMEDIATE V_STMT USING P_ID;
                V_CUR := DBMS_SQL.OPEN_CURSOR;
                DBMS_SQL.PARSE(V_CUR, 'TRUNCATE TABLE T6', DBMS_SQL.NATIVE);
                EXECUTE IMMEDIATE 'BEGIN SP_IN_DYN(1); UPDATE T7 SET X = 1; END;';
            END;
            /
            """;

    private static final String DYN_OK_DB2 = """
            --#SET TERMINATOR @
            CREATE PROCEDURE SP_DYN_DB2 ()
            LANGUAGE SQL
            BEGIN
                DECLARE V_SQL VARCHAR(200);
                DECLARE S1 STATEMENT;
                PREPARE S1 FROM 'INSERT INTO T4 VALUES (1)';
                EXECUTE S1;
                SET V_SQL = 'DELETE FROM T8 WHERE ID = ' CONCAT '1';
                EXECUTE IMMEDIATE V_SQL;
            END@
            """;

    @Test
    @DisplayName("F-008-AC8 동적 SQL이 리터럴로 풀리면 파싱해 일반 구문처럼 기록하고 dynamic=true")
    void dynamicResolved() {
        try (TestProject project = TestProject.create().file("db/oracle/dyn.prc", DYN_OK).file("db/db2/dyn.db2", DYN_OK_DB2)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String d1 = stmt("SP_DYN_OK", DYN_OK, "EXECUTE IMMEDIATE 'DELETE");
            Edge w1 = edge(g, d1, EdgeKind.WRITES, "db:table:T1");
            assertThat(w1.attrString("op")).isEqualTo("DELETE");
            assertThat(w1.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(w1.location().startLine()).isEqualTo(line(DYN_OK, "EXECUTE IMMEDIATE 'DELETE"));
            assertThat(node(g, d1).attrBoolean("dynamic")).isTrue();
            assertThat(node(g, d1).attrString("statementType")).isEqualTo("DELETE");
            assertThat(node(g, d1).attrString("sql")).isEqualTo("DELETE FROM T1 WHERE ID = :1");
            assertThat(node(g, d1).attrString("dynamicKind")).isEqualTo("EXECUTE IMMEDIATE");

            String d2 = stmt("SP_DYN_OK", DYN_OK, "EXECUTE IMMEDIATE V_SQL");
            assertThat(edge(g, d2, EdgeKind.WRITES, "db:table:T2").attrString("op")).isEqualTo("UPDATE");
            assertThat(node(g, d2).attrString("sql")).isEqualTo("UPDATE T2 SET X = 'a' WHERE ID = 1");
            String d3 = stmt("SP_DYN_OK", DYN_OK, "OPEN P_RC FOR");
            assertThat(edge(g, d3, EdgeKind.READS, "db:table:T3").attrBoolean("star")).isTrue();
            assertThat(node(g, d3).attrString("dynamicKind")).isEqualTo("OPEN FOR");
            String d5 = stmt("SP_DYN_OK", DYN_OK, "EXECUTE IMMEDIATE V_STMT");
            assertThat(hasEdge(g, d5, EdgeKind.WRITES, "db:table:T5")).isTrue();
            String d6 = stmt("SP_DYN_OK", DYN_OK, "DBMS_SQL.PARSE");
            assertThat(edge(g, d6, EdgeKind.WRITES, "db:table:T6").attrBoolean("truncate")).isTrue();
            String d7 = stmt("SP_DYN_OK", DYN_OK, "EXECUTE IMMEDIATE 'BEGIN");
            assertThat(hasEdge(g, d7, EdgeKind.WRITES, "db:table:T7")).isTrue();
            assertThat(hasEdge(g, d7, EdgeKind.CALLS, "db:routine:SP_IN_DYN")).isTrue();

            String d4 = stmt("SP_DYN_DB2", DYN_OK_DB2, "PREPARE S1 FROM");
            assertThat(edge(g, d4, EdgeKind.WRITES, "db:table:T4").attrString("op")).isEqualTo("INSERT");
            assertThat(node(g, d4).attrString("dynamicKind")).isEqualTo("PREPARE");
            assertThat(node(g, d4).attrString("dialect")).isEqualTo("DB2");
            String d8 = stmt("SP_DYN_DB2", DYN_OK_DB2, "EXECUTE IMMEDIATE V_SQL");
            assertThat(edge(g, d8, EdgeKind.WRITES, "db:table:T8").attrString("op")).isEqualTo("DELETE");
            assertThat(targets(g, "db:routine:SP_DYN_DB2", EdgeKind.CONTAINS)).containsExactlyInAnyOrder(d4, d8);

            assertThat(node(g, "db:routine:SP_DYN_OK").attrBoolean("dynamicSql")).isTrue();
            assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).isEmpty();
            assertThat(g.nodes(NodeKind.UNRESOLVED)).isEmpty();
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    private static final String DYN_BAD = """
            CREATE OR REPLACE PROCEDURE SP_DYN_BAD (P_DT VARCHAR2, P_TABLE VARCHAR2) IS
                V_TABLE  VARCHAR2(30);
                V_SQL    VARCHAR2(1000);
                V_TWICE  VARCHAR2(100);
            BEGIN
                V_TABLE := 'TB_ARC_' || SUBSTR(P_DT, 1, 4);
                V_SQL   := 'INSERT INTO ' || V_TABLE
                        || ' SELECT * FROM TB_ORDER WHERE ORDER_DT < :1';
                EXECUTE IMMEDIATE V_SQL USING P_DT;

                V_TWICE := 'DELETE FROM TB_ONE';
                V_TWICE := 'DELETE FROM TB_TWO';
                EXECUTE IMMEDIATE V_TWICE;

                EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM ' || P_TABLE INTO V_TWICE;
                EXECUTE IMMEDIATE P_TABLE;
            END;
            /
            """;

    private static final String DYN_BAD_DB2 = """
            --#SET TERMINATOR @
            CREATE PROCEDURE SP_PURGE (IN P_TABLE_NM VARCHAR(128), IN P_DAYS INTEGER)
            LANGUAGE SQL
            BEGIN
                DECLARE V_SQL VARCHAR(1000);
                DECLARE S1 STATEMENT;
                SET V_SQL = 'DELETE FROM ' || P_TABLE_NM
                         || ' WHERE REG_TS < CURRENT TIMESTAMP - ' || CHAR(P_DAYS) || ' DAYS';
                PREPARE S1 FROM V_SQL;
                EXECUTE S1;
            END@
            """;

    @Test
    @DisplayName("F-008-AC9 동적 SQL이 풀리지 않으면 구문 노드(dynamic, sql에 {*}) + unresolved:sql-dynamic UNRESOLVED 엣지 + DYNAMIC_SQL, 테이블 엣지는 없다")
    void dynamicUnresolved() {
        try (TestProject project = TestProject.create().file("db/oracle/dyn_bad.prc", DYN_BAD).file("db/db2/purge.db2", DYN_BAD_DB2)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String d1 = stmt("SP_DYN_BAD", DYN_BAD, "EXECUTE IMMEDIATE V_SQL");
            Node n1 = node(g, d1);
            assertThat(n1.attrBoolean("dynamic")).isTrue();
            assertThat(n1.attrString("sql")).isEqualTo("INSERT INTO TB_ARC_{*} SELECT * FROM TB_ORDER WHERE ORDER_DT < :1");
            assertThat(n1.attrString("statementType")).isEqualTo("INSERT");
            String u1 = Ids.unresolved("sql-dynamic", d1);
            Edge e1 = edge(g, d1, EdgeKind.WRITES, u1);
            assertThat(e1.confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(e1.attrString("op")).isEqualTo("INSERT");
            assertThat(node(g, u1).kind()).isEqualTo(NodeKind.UNRESOLVED);
            assertThat(g.outgoing(d1)).hasSize(1);

            String d2 = stmt("SP_DYN_BAD", DYN_BAD, "EXECUTE IMMEDIATE V_TWICE");
            assertThat(node(g, d2).attrString("sql")).isEqualTo("{*}");
            assertThat(edge(g, d2, EdgeKind.READS, Ids.unresolved("sql-dynamic", d2)).confidence()).isEqualTo(Confidence.UNRESOLVED);
            String d3 = stmt("SP_DYN_BAD", DYN_BAD, "EXECUTE IMMEDIATE 'SELECT COUNT");
            assertThat(node(g, d3).attrString("sql")).isEqualTo("SELECT COUNT(*) FROM {*}");
            assertThat(node(g, d3).attrString("statementType")).isEqualTo("SELECT");
            assertThat(hasEdge(g, d3, EdgeKind.READS, Ids.unresolved("sql-dynamic", d3))).isTrue();
            String d4 = stmt("SP_DYN_BAD", DYN_BAD, "EXECUTE IMMEDIATE P_TABLE");
            assertThat(hasEdge(g, d4, EdgeKind.READS, Ids.unresolved("sql-dynamic", d4))).isTrue();

            String p = stmt("SP_PURGE", DYN_BAD_DB2, "PREPARE S1 FROM V_SQL");
            Edge pe = edge(g, p, EdgeKind.WRITES, Ids.unresolved("sql-dynamic", p));
            assertThat(pe.attrString("op")).isEqualTo("DELETE");
            assertThat(pe.confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(node(g, p).attrString("sql")).isEqualTo("DELETE FROM {*} WHERE REG_TS < CURRENT TIMESTAMP - {*} DAYS");
            assertThat(targets(g, "db:routine:SP_PURGE", EdgeKind.CONTAINS)).containsExactly(p);

            // 추측으로 만든 테이블은 없다
            assertThat(tableNodes(g)).isEmpty();
            assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).hasSize(5);
            assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).allMatch(d -> d.severity() == Severity.WARN && d.location() != null);
            assertThat(node(g, "db:routine:SP_DYN_BAD").attrBoolean("dynamicSql")).isTrue();
            assertThat(node(g, "db:routine:SP_PURGE").attrBoolean("dynamicSql")).isTrue();
        }
    }

    // ------------------------------------------------------------------ AC10

    private static final String TRIGGERS = """
            CREATE OR REPLACE TRIGGER TRG_HIST
                AFTER UPDATE OF ST ON TB_ORD
                FOR EACH ROW
                WHEN (OLD.ST <> NEW.ST)
            DECLARE
                V_QTY NUMBER;
            BEGIN
                SELECT NVL(SUM(S.QTY), 0) INTO V_QTY FROM TB_STOCK S WHERE S.PRODUCT_ID = :NEW.PRODUCT_ID;
                IF V_QTY < :NEW.QTY THEN
                    RAISE_APPLICATION_ERROR(-20010, 'no stock ' || :NEW.PRODUCT_ID);
                END IF;
                INSERT INTO TB_ORD_HIST (ORDER_ID, ST, OLD_ST) VALUES (:NEW.ORDER_ID, :NEW.ST, :OLD.ST);
                :NEW.UPD_DT := SYSDATE;
            END;
            /
            """;

    private static final String DB2_TRIGGERS = """
            --#SET TERMINATOR @
            CREATE TRIGGER TRG_DB2_HIST
                AFTER UPDATE OF ST ON TB_ORD
                REFERENCING NEW AS N OLD AS O
                FOR EACH ROW MODE DB2SQL
                WHEN (N.ST <> O.ST)
            BEGIN ATOMIC
                DECLARE V_CNT INTEGER;
                SET V_CNT = (SELECT COUNT(*) FROM TB_ORD_ITEM I WHERE I.ORDER_ID = N.ORDER_ID);
                INSERT INTO TB_ORD_HIST (ORDER_ID, ST, OLD_ST, CNT) VALUES (N.ORDER_ID, N.ST, O.ST, V_CNT);
                UPDATE TB_ORD_SUM X SET X.CNT = X.CNT + 1 WHERE X.ORDER_ID = N.ORDER_ID;
            END@
            CREATE TRIGGER TRG_DB2_ONE
                AFTER INSERT ON TB_ORD
                REFERENCING NEW AS NN
                FOR EACH ROW MODE DB2SQL
                INSERT INTO TB_AUDIT (ORDER_ID) VALUES (NN.ORDER_ID)@
            """;

    @Test
    @DisplayName("F-008-AC10 트리거 본문: :NEW/:OLD, REFERENCING 상관 이름은 테이블·컬럼이 아니고 본문 구문은 일반 루틴처럼 기록된다")
    void triggers() {
        try (TestProject project = TestProject.create().file("db/oracle/trg.trg", TRIGGERS).file("db/db2/trg.db2", DB2_TRIGGERS)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(routineTargets(g, "db:routine:TRG_HIST", EdgeKind.READS)).contains("db:table:TB_STOCK", "db:column:TB_STOCK.PRODUCT_ID");
            assertThat(edge(g, stmt("TRG_HIST", TRIGGERS, "INSERT INTO TB_ORD_HIST"), EdgeKind.WRITES, "db:table:TB_ORD_HIST").attrString("op")).isEqualTo("INSERT");
            assertThat(hasEdge(g, "db:routine:TRG_HIST", EdgeKind.TRIGGERS_ON, "db:table:TB_ORD")).isTrue();
            assertThat(node(g, "db:routine:TRG_HIST").attrString("language")).isEqualTo("PLSQL");

            String r = "db:routine:TRG_DB2_HIST";
            assertThat(targets(g, r, EdgeKind.CONTAINS)).hasSize(3);
            assertThat(routineTargets(g, r, EdgeKind.READS)).contains("db:table:TB_ORD_ITEM");
            assertThat(routineTargets(g, r, EdgeKind.WRITES)).contains("db:table:TB_ORD_HIST", "db:table:TB_ORD_SUM");
            assertThat(node(g, r).attrString("language")).isEqualTo("SQLPL");
            assertThat(routineTargets(g, "db:routine:TRG_DB2_ONE", EdgeKind.WRITES)).contains("db:table:TB_AUDIT");

            assertThat(tableNodes(g)).containsExactly("db:table:TB_AUDIT", "db:table:TB_ORD", "db:table:TB_ORD_HIST", "db:table:TB_ORD_ITEM",
                    "db:table:TB_ORD_SUM", "db:table:TB_STOCK");
            assertThat(columnNodes(g)).noneMatch(id -> id.startsWith("db:column:N.") || id.startsWith("db:column:O.") || id.startsWith("db:column:NN.")
                    || id.startsWith("db:column:NEW.") || id.startsWith("db:column:OLD.") || id.endsWith(".V_CNT") || id.endsWith(".V_QTY"));
            // TB_ORD는 트리거 대상일 뿐 본문에서 읽고 쓰지 않는다
            assertThat(g.incoming("db:table:TB_ORD")).allMatch(e -> e.kind() == EdgeKind.TRIGGERS_ON);
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC11

    private static final String BLOCK = "DECLARE\n  V NUMBER := 0;\nBEGIN\n  FOR R IN (SELECT ID FROM T1 WHERE X > V) LOOP\n"
            + "    UPDATE T2 SET X = V WHERE ID = R.ID;\n    SP_A(R.ID);\n    V := FN_DEFINED(V) + FN_UNDEFINED(V);\n  END LOOP;\n"
            + "  DBMS_OUTPUT.PUT_LINE('x');\nEND;";

    @Test
    @DisplayName("F-008-AC11 익명 블록(SQL 파일, 내장 소스): 구문 노드 하나에 안쪽 구문의 READS/WRITES/CALLS가 모인다")
    void anonymousBlocks() {
        String file = "CREATE OR REPLACE FUNCTION FN_DEFINED (P NUMBER) RETURN NUMBER IS BEGIN RETURN P; END;\n/\n" + BLOCK + "\n/\n";
        try (TestProject project = TestProject.create().file("db/oracle/run.sql", file)) {
            AnalysisContext ctx = project.context();
            String owner = Ids.mybatisStatement("ns", "runBlock");
            ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name("runBlock").location(SourceLocation.of("m.xml", 10)).build());
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, BLOCK, SourceLocation.of("m.xml", 10, 5, 10, 5), true, Map.of()));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            String inFile = "sql:stmt:db/oracle/run.sql:3";
            for (String id : List.of(inFile, owner)) {
                assertThat(targets(g, id, EdgeKind.READS)).containsExactly("db:column:T1.ID", "db:column:T1.X", "db:column:T2.ID", "db:table:T1");
                assertThat(targets(g, id, EdgeKind.WRITES)).containsExactly("db:column:T2.X", "db:table:T2");
                assertThat(targets(g, id, EdgeKind.CALLS)).containsExactly("db:routine:FN_DEFINED", "db:routine:SP_A");
                assertThat(node(g, id).attrString("statementType")).isEqualTo("UPDATE");
            }
            assertThat(g.nodes(NodeKind.SQL_STATEMENT)).hasSize(2);
            assertThat(hasEdge(g, "file:db/oracle/run.sql", EdgeKind.CONTAINS, inFile)).isTrue();
            // 내장 소스의 위치는 호스트 파일 기준
            assertThat(edge(g, owner, EdgeKind.READS, "db:table:T1").location()).isEqualTo(new SourceLocation("m.xml", 13, 28, 13, 30));
            assertThat(tableNodes(g)).containsExactly("db:table:T1", "db:table:T2");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC12

    @Test
    @DisplayName("F-008-AC12 깨진 본문: END 누락·괄호 불균형·모르는 구문에서도 앞뒤 SQL을 건지고 진단을 남기며 다른 루틴은 정상 분석된다")
    void brokenBodies() {
        String sql = """
                CREATE OR REPLACE PROCEDURE SP_NO_END (P NUMBER) IS
                BEGIN
                    UPDATE T_BEFORE SET X = 1 WHERE ID = P;
                    IF P > 0 THEN
                        DELETE FROM T_IN_IF WHERE ID = P;
                    INSERT INTO T_AFTER (ID) VALUES (P);
                /
                CREATE OR REPLACE PROCEDURE SP_PAREN (P NUMBER) IS
                BEGIN
                    UPDATE T_P1 SET X = (1 + 2 WHERE ID = P;
                    DELETE FROM T_P2 WHERE ID = P;
                    FROBNICATE THE WIDGET WITH (SELECT 1 FROM T_SALVAGED);
                    INSERT INTO T_P3 (ID) VALUES (P);
                END;
                /
                CREATE OR REPLACE PROCEDURE SP_GOOD (P NUMBER) IS
                BEGIN
                    DELETE FROM T_GOOD WHERE ID = P;
                END;
                /
                """;
        try (TestProject project = TestProject.create().file("db/oracle/broken.sql", sql)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(g.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
            assertThat(routineTargets(g, "db:routine:SP_NO_END", EdgeKind.WRITES)).contains("db:table:T_BEFORE", "db:table:T_IN_IF", "db:table:T_AFTER");
            assertThat(routineTargets(g, "db:routine:SP_PAREN", EdgeKind.WRITES)).contains("db:table:T_P1", "db:table:T_P2", "db:table:T_P3");
            assertThat(routineTargets(g, "db:routine:SP_PAREN", EdgeKind.READS)).contains("db:table:T_SALVAGED");
            assertThat(routineTargets(g, "db:routine:SP_GOOD", EdgeKind.WRITES)).containsExactly("db:table:T_GOOD");
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isNotEmpty();
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).allMatch(d -> d.severity() == Severity.WARN && d.location() != null);
            assertThat(diagnostics(g, DiagnosticCodes.UNSUPPORTED_SYNTAX)).anyMatch(d -> d.message().contains("FROBNICATE")
                    && d.location().startLine() == line(sql, "FROBNICATE"));
        }
    }

    // ------------------------------------------------------------------ AC13

    @Test
    @DisplayName("F-008-AC13 루틴 노드 attrs: language, statementCount, dynamicSql, returnType, bodyMissing")
    void routineAttrs() {
        try (TestProject project = TestProject.create()
                .file("db/oracle/pkg_sales.pks", PKG_SPEC).file("db/oracle/pkg_sales.pkb", PKG_BODY)
                .file("db/oracle/dyn_bad.prc", DYN_BAD).file("db/db2/purge.db2", DYN_BAD_DB2).file("db/oracle/sp_basic.prc", SP_BASIC)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            Node fn = node(g, "db:routine:FN_TAX");
            assertThat(fn.attrString("language")).isEqualTo("PLSQL");
            assertThat(fn.attrString("returnType")).isEqualTo("NUMBER");
            assertThat(fn.attrInt("statementCount", -1)).isZero();
            assertThat(fn.attrBoolean("dynamicSql")).isFalse();
            assertThat(fn.attrBoolean("bodyMissing")).isFalse();
            Node basic = node(g, "db:routine:SP_BASIC");
            assertThat(basic.attrInt("statementCount", -1)).isEqualTo(5);
            assertThat(basic.attrList("parameters")).containsExactly("P_ID IN NUMBER", "P_NM IN VARCHAR2");
            Node purge = node(g, "db:routine:SP_PURGE");
            assertThat(purge.attrList("parameters")).containsExactly("P_TABLE_NM IN VARCHAR(128)", "P_DAYS IN INTEGER");
            assertThat(purge.attrString("language")).isEqualTo("SQLPL");
            assertThat(purge.attrBoolean("dynamicSql")).isTrue();
            assertThat(purge.attrInt("statementCount", -1)).isEqualTo(1);
            Node getAmt = node(g, "db:routine:PKG_SALES.GET_AMT");
            assertThat(getAmt.attrString("returnType")).isEqualTo("NUMBER");
            assertThat(getAmt.attrString("language")).isEqualTo("PLSQL");
            assertThat(getAmt.attrInt("statementCount", -1)).isEqualTo(1);
            assertThat(getAmt.attrBoolean("bodyMissing")).isFalse();
            assertThat(node(g, "db:routine:PKG_SALES.NOT_IMPLEMENTED").attrBoolean("bodyMissing")).isTrue();
            assertThat(node(g, "db:routine:PKG_SALES.CLOSE_ONE").attrInt("statementCount", -1)).isEqualTo(2);
            assertThat(node(g, "db:routine:PKG_SALES.LOG_IT").attrBoolean("bodyMissing")).isFalse();
        }
    }

    // ------------------------------------------------------------------ 그 밖의 경우

    @Test
    @DisplayName("F-008-AC10 DB2 문장 트리거의 전이 테이블(NEW TABLE AS nt)은 테이블이 아니고, 본문이 CALL 한 문장인 트리거도 분석된다")
    void transitionTablesAndCallTrigger() {
        String db2 = """
                --#SET TERMINATOR @
                CREATE TRIGGER TRG_STMT
                    AFTER INSERT ON TB_ORD
                    REFERENCING NEW TABLE AS NT OLD_TABLE AS OT
                    FOR EACH STATEMENT MODE DB2SQL
                BEGIN ATOMIC
                    INSERT INTO TB_AUDIT (ORDER_ID) SELECT N.ORDER_ID FROM NT N JOIN TB_MEMBER M ON M.MEMBER_ID = N.MEMBER_ID;
                    DELETE FROM TB_WORK W WHERE W.ID IN (SELECT ID FROM OT);
                END@
                """;
        String oracle = "CREATE OR REPLACE TRIGGER TRG_CALL AFTER INSERT ON TB_ORD FOR EACH ROW\nCALL SP_ON_INSERT(:NEW.ORDER_ID)\n/\n";
        try (TestProject project = TestProject.create().file("db/db2/trg.db2", db2).file("db/oracle/trg_call.trg", oracle)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(tableNodes(g)).containsExactly("db:table:TB_AUDIT", "db:table:TB_MEMBER", "db:table:TB_ORD", "db:table:TB_WORK");
            assertThat(routineTargets(g, "db:routine:TRG_STMT", EdgeKind.READS)).contains("db:table:TB_MEMBER", "db:column:TB_MEMBER.MEMBER_ID");
            assertThat(routineTargets(g, "db:routine:TRG_STMT", EdgeKind.WRITES)).contains("db:table:TB_AUDIT", "db:table:TB_WORK");
            assertThat(g.edges(EdgeKind.JOINS)).isEmpty();
            assertThat(targets(g, "db:routine:TRG_CALL", EdgeKind.CALLS)).containsExactly("db:routine:SP_ON_INSERT");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-008-AC12 패키지 멤버 하나가 깨져도 다른 멤버는 분석되고, 오버로드는 한 노드에 모인다")
    void packageMemberIsolation() {
        String body = """
                CREATE OR REPLACE PACKAGE BODY PKG_ISO AS
                    PROCEDURE GOOD_BEFORE IS
                    BEGIN
                        DELETE FROM T_BEFORE;
                    END GOOD_BEFORE;

                    PROCEDURE BROKEN (P NUMBER) IS
                    BEGIN
                        IF P > 0 THEN
                            UPDATE T_BROKEN SET X = (1 + ;
                        END IF
                        THIS IS NOT PLSQL AT ALL (((;
                    END BROKEN;

                    PROCEDURE OVER (P NUMBER) IS
                    BEGIN
                        INSERT INTO T_OVER1 (A) VALUES (P);
                    END OVER;

                    PROCEDURE OVER (P VARCHAR2, Q NUMBER) IS
                    BEGIN
                        INSERT INTO T_OVER2 (A) VALUES (P);
                        GOOD_BEFORE;
                    END OVER;
                END PKG_ISO;
                /
                """;
        try (TestProject project = TestProject.create().file("db/oracle/pkg_iso.pkb", body)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(g.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
            assertThat(routineTargets(g, "db:routine:PKG_ISO.GOOD_BEFORE", EdgeKind.WRITES)).containsExactly("db:table:T_BEFORE");
            assertThat(routineTargets(g, "db:routine:PKG_ISO.BROKEN", EdgeKind.WRITES)).contains("db:table:T_BROKEN");
            assertThat(routineTargets(g, "db:routine:PKG_ISO.OVER", EdgeKind.WRITES))
                    .contains("db:table:T_OVER1", "db:table:T_OVER2");
            assertThat(node(g, "db:routine:PKG_ISO.OVER").attrInt("statementCount", -1)).isEqualTo(2);
            assertThat(targets(g, "db:routine:PKG_ISO.OVER", EdgeKind.CALLS)).containsExactly("db:routine:PKG_ISO.GOOD_BEFORE");
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isNotEmpty();
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).allMatch(d -> d.message().contains("PKG_ISO.BROKEN"));
        }
    }

    @Test
    @DisplayName("F-008-AC5 키워드와 같은 이름의 사용자 프로시저 호출, 파이프라인 함수, SQL 안의 CASE·세미콜론 문자열")
    void keywordNamedCallsAndTrickySql() {
        String sql = """
                CREATE OR REPLACE FUNCTION FN_PIPE (P_DT VARCHAR2) RETURN T_TAB PIPELINED IS
                    V_ST VARCHAR2(10);
                BEGIN
                    FOR R IN (SELECT ID, CASE WHEN ST = 'A' THEN 1 ELSE 0 END AS FLAG FROM T_PIPE WHERE DT = P_DT) LOOP
                        PIPE ROW (R);
                    END LOOP;
                    UPDATE T_CASE SET NM = CASE WHEN ID = 1 THEN 'a;b' ELSE 'END' END WHERE DT = P_DT;
                    SELECT CASE WHEN COUNT(*) > 0 THEN 'Y' ELSE 'N' END INTO V_ST FROM T_CNT;
                    UPDATE T_ROW SET ROW = V_REC WHERE ID = 1;
                    INSERT INTO T_ROW VALUES V_REC;
                    PREPARE(1);
                    SIGNAL(2);
                    GET(3);
                    LOCK(4);
                    RETURN;
                END;
                /
                """;
        try (TestProject project = TestProject.create().file("db/oracle/fn_pipe.fnc", sql)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(routineTargets(g, "db:routine:FN_PIPE", EdgeKind.READS)).contains("db:table:T_PIPE", "db:table:T_CNT");
            assertThat(routineTargets(g, "db:routine:FN_PIPE", EdgeKind.WRITES)).contains("db:table:T_CASE");
            assertThat(targets(g, "db:routine:FN_PIPE", EdgeKind.CALLS))
                    .containsExactly("db:routine:GET", "db:routine:LOCK", "db:routine:PREPARE", "db:routine:SIGNAL");
            assertThat(node(g, "db:routine:FN_PIPE").attrInt("statementCount", -1)).isEqualTo(5);
            // SET ROW = 레코드: ROW는 컬럼이 아니다
            assertThat(columnNodes(g)).containsExactly("db:column:T_CASE.DT", "db:column:T_CASE.ID", "db:column:T_CASE.NM",
                    "db:column:T_PIPE.DT", "db:column:T_PIPE.ID", "db:column:T_PIPE.ST", "db:column:T_ROW.ID");
            assertThat(node(g, "db:routine:FN_PIPE").attrBoolean("dynamicSql")).isFalse();
            assertThat(node(g, "db:routine:FN_PIPE").attrString("returnType")).isEqualTo("T_TAB");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-008-AC11 DB2 익명 복합문(BEGIN ATOMIC, DECLARE, SET = (SELECT), CALL)과 MyBatis 바인드가 든 내장 블록")
    void db2AndEmbeddedBlocks() {
        String db2 = "--#SET TERMINATOR @\nBEGIN ATOMIC\n  DECLARE V INT;\n  SET V = (SELECT COUNT(*) FROM T_CNT);\n"
                + "  IF V > 0 THEN CALL SP_DB2(V); END IF;\n  DELETE FROM T_DEL WHERE N = V;\nEND@\n";
        try (TestProject project = TestProject.create().file("db/db2/run.db2", db2)) {
            AnalysisContext ctx = project.context();
            String owner = Ids.mybatisStatement("ns", "block");
            ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name("block").location(SourceLocation.of("m.xml", 5)).build());
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner,
                    "BEGIN UPDATE T_EMB SET A = #{a} WHERE ID = #{id}; PKG.P(#{id}, #{out, mode=OUT}); END;",
                    SourceLocation.of("m.xml", 5), false, Map.of()));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            String block = "sql:stmt:db/db2/run.db2:2";
            assertThat(targets(g, block, EdgeKind.READS)).contains("db:table:T_CNT", "db:column:T_DEL.N");
            assertThat(targets(g, block, EdgeKind.WRITES)).containsExactly("db:table:T_DEL");
            assertThat(targets(g, block, EdgeKind.CALLS)).containsExactly("db:routine:SP_DB2");
            assertThat(node(g, block).attrString("statementType")).isEqualTo("DELETE");
            assertThat(node(g, block).attrString("dialect")).isEqualTo("DB2");
            assertThat(columnNodes(g)).doesNotContain("db:column:T_DEL.V");
            assertThat(targets(g, owner, EdgeKind.WRITES)).containsExactly("db:column:T_EMB.A", "db:table:T_EMB");
            assertThat(targets(g, owner, EdgeKind.CALLS)).containsExactly("db:routine:PKG.P");
            assertThat(node(g, owner).attrString("statementType")).isEqualTo("UPDATE");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC15: %TYPE / %ROWTYPE 참조

    private static final String TYPE_REFS = """
            CREATE TABLE TB_ORDER (ORDER_ID NUMBER PRIMARY KEY, ORDER_STATUS VARCHAR2(2));
            CREATE OR REPLACE PACKAGE PKG_T AS
                G_LIMIT TB_CFG.LIMIT_NO%TYPE;
                CURSOR C_PKG IS SELECT ORDER_ID FROM TB_ORDER;
                PROCEDURE RUN (P_ID IN TB_ORDER.ORDER_ID%TYPE, P_ST SHOP.TB_ORDER.ORDER_STATUS%TYPE);
                FUNCTION SPEC_ONLY (P_CD SHOP.TB_CODE.CODE%TYPE) RETURN TB_CODE.CODE_NM%TYPE;
                PROCEDURE PLAIN (P_ID NUMBER);
            END PKG_T;
            /
            CREATE OR REPLACE PACKAGE BODY PKG_T AS
                PROCEDURE RUN (P_ID IN TB_ORDER.ORDER_ID%TYPE, P_ST SHOP.TB_ORDER.ORDER_STATUS%TYPE) IS
                    CURSOR C IS SELECT ORDER_ID FROM TB_ORDER;
                    R_C    C%ROWTYPE;
                    R_PKG  C_PKG%ROWTYPE;
                    R_ORD  tb_order%ROWTYPE;
                    R_ITEM SHOP.TB_ORDER_ITEM%ROWTYPE;
                    V_ST   R_ORD.ORDER_STATUS%TYPE;
                    V_ID   V_ST%TYPE;
                    V_G    PKG_T.G_LIMIT%TYPE;
                    TYPE T_REC IS RECORD (NM TB_MEMBER.MEMBER_NM%TYPE);
                BEGIN
                    NULL;
                END RUN;
                PROCEDURE PLAIN (P_ID NUMBER) IS
                BEGIN
                    NULL;
                END PLAIN;
            END PKG_T;
            /
            CREATE OR REPLACE FUNCTION FN_T (P TB_A.ID%TYPE) RETURN TB_A.NM%TYPE IS
                V SHOP.TB_CODE.CODE_NM%TYPE;
                W TB_A.ID%TYPE;
            BEGIN
                RETURN NULL;
            END;
            /
            CREATE OR REPLACE TRIGGER TRG_T BEFORE INSERT ON TB_ORDER FOR EACH ROW
            DECLARE
                V TB_B.X%TYPE;
            BEGIN
                NULL;
            END;
            /
            """;

    @Test
    @DisplayName("F-008-AC15 %TYPE·%ROWTYPE이 가리키는 테이블·컬럼을 루틴 노드 attrs.typeRefs에 남긴다(스키마 제거·대문자, 커서·변수·패키지 변수 기준은 제외, 엣지는 없음)")
    void typeRefs() {
        try (TestProject project = TestProject.create().file("db/oracle/type_refs.sql", TYPE_REFS)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:routine:PKG_T.RUN").attrList("typeRefs")).containsExactly(
                    "TB_ORDER.ORDER_ID", "TB_ORDER.ORDER_STATUS", "TB_ORDER", "TB_ORDER_ITEM", "TB_MEMBER.MEMBER_NM");
            assertThat(node(g, "db:routine:PKG_T.SPEC_ONLY").attrList("typeRefs")).containsExactly("TB_CODE.CODE", "TB_CODE.CODE_NM");
            assertThat(node(g, "db:routine:PKG_T.PLAIN").attrs()).doesNotContainKey("typeRefs");
            assertThat(node(g, "db:package:PKG_T").attrList("typeRefs")).containsExactly("TB_CFG.LIMIT_NO");
            assertThat(node(g, "db:routine:FN_T").attrList("typeRefs")).containsExactly("TB_A.ID", "TB_A.NM", "TB_CODE.CODE_NM");
            assertThat(node(g, "db:routine:TRG_T").attrList("typeRefs")).containsExactly("TB_B.X");
            // 계약(3.4): %TYPE·%ROWTYPE으로는 엣지도 노드도 만들지 않는다
            assertThat(tableNodes(g)).containsExactly("db:table:TB_ORDER");
            assertThat(columnNodes(g)).containsExactly("db:column:TB_ORDER.ORDER_ID", "db:column:TB_ORDER.ORDER_STATUS");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ AC16: 식 안의 시퀀스 참조

    private static final String SEQ_PLSQL = """
            CREATE SEQUENCE SEQ_KNOWN;
            CREATE OR REPLACE PROCEDURE SP_SEQ (P_ID NUMBER) IS
                TYPE T_REC IS RECORD (NEXTVAL NUMBER, CURRVAL NUMBER);
                R      T_REC;
                V_ID   NUMBER;
                V_INIT NUMBER := SEQ_INIT.NEXTVAL;
            BEGIN
                V_ID := SEQ_A.NEXTVAL;
                V_ID := SHOP.SEQ_B.CURRVAL + 1;
                V_ID := R.NEXTVAL + R.CURRVAL;
                IF SEQ_C.NEXTVAL > 10 THEN
                    SP_LOG(SEQ_KNOWN.NEXTVAL);
                END IF;
                INSERT INTO TB_LOG (ID) VALUES (SEQ_SQL.NEXTVAL);
            END;
            /
            """;

    private static final String SEQ_DB2 = """
            --#SET TERMINATOR @
            CREATE PROCEDURE SP_SEQ_DB2 (IN P_YM CHAR(6))
            LANGUAGE SQL
            BEGIN
                DECLARE V_ID BIGINT;
                DECLARE V_PREV BIGINT;
                SET V_ID = NEXT VALUE FOR SEQ_SETTLE_ID;
                SET V_PREV = PREVIOUS VALUE FOR BATCH.SEQ_SETTLE_ID;
                VALUES NEXT VALUE FOR SEQ_LOG INTO V_ID;
                SET V_ID = NEXTVAL FOR SEQ_OLD;
            END@
            """;

    @Test
    @DisplayName("F-008-AC16 PL/SQL 식 안의 시퀀스(x.NEXTVAL, x.CURRVAL): 대입문·조건식·인자·선언 초기값이 구문 노드(sequence=true)가 되고 시퀀스로 READS가 나간다")
    void sequencesInPlsqlExpressions() {
        try (TestProject project = TestProject.create().file("db/oracle/sp_seq.sql", SEQ_PLSQL)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String routine = "db:routine:SP_SEQ";
            String assign = stmt("SP_SEQ", SEQ_PLSQL, "V_ID := SEQ_A.NEXTVAL");
            Node node = node(g, assign);
            assertThat(node.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
            assertThat(node.attrString("statementType")).isEqualTo("OTHER");
            assertThat(node.attrBoolean("sequence")).isTrue();
            assertThat(node.attrString("sql")).isEqualTo("V_ID := SEQ_A.NEXTVAL");
            assertThat(node.attrString("routine")).isEqualTo(routine);
            assertThat(node.location().startLine()).isEqualTo(line(SEQ_PLSQL, "V_ID := SEQ_A.NEXTVAL"));
            assertThat(hasEdge(g, routine, EdgeKind.CONTAINS, assign)).isTrue();
            Edge read = edge(g, assign, EdgeKind.READS, "db:sequence:SEQ_A");
            assertThat(read.attrBoolean("sequence")).isTrue();
            assertThat(read.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(node(g, "db:sequence:SEQ_A").kind()).isEqualTo(NodeKind.DB_SEQUENCE);

            // 스키마는 뗀다. 조건식·호출 인자·선언 초기값에서도 같다
            assertThat(targets(g, stmt("SP_SEQ", SEQ_PLSQL, "V_ID := SHOP.SEQ_B"), EdgeKind.READS)).containsExactly("db:sequence:SEQ_B");
            assertThat(targets(g, stmt("SP_SEQ", SEQ_PLSQL, "IF SEQ_C.NEXTVAL"), EdgeKind.READS)).containsExactly("db:sequence:SEQ_C");
            assertThat(targets(g, stmt("SP_SEQ", SEQ_PLSQL, "SP_LOG(SEQ_KNOWN"), EdgeKind.READS)).containsExactly("db:sequence:SEQ_KNOWN");
            assertThat(targets(g, stmt("SP_SEQ", SEQ_PLSQL, "V_INIT NUMBER"), EdgeKind.READS)).containsExactly("db:sequence:SEQ_INIT");
            // 레코드 필드 R.NEXTVAL은 시퀀스가 아니다. SQL 구문 안의 시퀀스는 전과 같이 그 구문에서 나간다
            assertThat(g.node("db:sequence:R")).isEmpty();
            assertThat(g.node(stmt("SP_SEQ", SEQ_PLSQL, "V_ID := R.NEXTVAL"))).isEmpty();
            assertThat(targets(g, stmt("SP_SEQ", SEQ_PLSQL, "INSERT INTO TB_LOG"), EdgeKind.READS)).containsExactly("db:sequence:SEQ_SQL");
            assertThat(routineTargets(g, routine, EdgeKind.READS)).containsExactly("db:sequence:SEQ_A", "db:sequence:SEQ_B",
                    "db:sequence:SEQ_C", "db:sequence:SEQ_INIT", "db:sequence:SEQ_KNOWN", "db:sequence:SEQ_SQL");
            assertThat(node(g, routine).attrInt("statementCount", -1)).isEqualTo(6);
            assertThat(targets(g, routine, EdgeKind.CALLS)).containsExactly("db:routine:SP_LOG");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-008-AC16 SQL PL: SET v = NEXT VALUE FOR seq, PREVIOUS VALUE FOR, NEXTVAL FOR, VALUES NEXT VALUE FOR seq INTO v")
    void sequencesInDb2Expressions() {
        try (TestProject project = TestProject.create().file("db/db2/sp_seq.db2", SEQ_DB2)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String routine = "db:routine:SP_SEQ_DB2";
            String set = stmt("SP_SEQ_DB2", SEQ_DB2, "SET V_ID = NEXT VALUE FOR SEQ_SETTLE_ID");
            Node node = node(g, set);
            assertThat(node.attrString("statementType")).isEqualTo("OTHER");
            assertThat(node.attrBoolean("sequence")).isTrue();
            assertThat(node.attrString("sql")).isEqualTo("SET V_ID = NEXT VALUE FOR SEQ_SETTLE_ID");
            assertThat(node.attrString("dialect")).isEqualTo("DB2");
            assertThat(edge(g, set, EdgeKind.READS, "db:sequence:SEQ_SETTLE_ID").attrBoolean("sequence")).isTrue();
            assertThat(targets(g, stmt("SP_SEQ_DB2", SEQ_DB2, "SET V_PREV"), EdgeKind.READS)).containsExactly("db:sequence:SEQ_SETTLE_ID");
            assertThat(targets(g, stmt("SP_SEQ_DB2", SEQ_DB2, "VALUES NEXT VALUE"), EdgeKind.READS)).containsExactly("db:sequence:SEQ_LOG");
            assertThat(targets(g, stmt("SP_SEQ_DB2", SEQ_DB2, "SET V_ID = NEXTVAL FOR"), EdgeKind.READS)).containsExactly("db:sequence:SEQ_OLD");
            assertThat(node(g, routine).attrInt("statementCount", -1)).isEqualTo(4);
            assertThat(g.outgoing(routine, EdgeKind.CALLS)).isEmpty();
            assertThat(tableNodes(g)).isEmpty();
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-008-AC16 익명 블록의 식 안 시퀀스는 블록의 구문 노드에서 READS로 나간다")
    void sequenceInAnonymousBlock() {
        String file = "DECLARE\n  V NUMBER;\nBEGIN\n  V := SEQ_BLOCK.NEXTVAL;\n  UPDATE T_BLK SET X = V;\nEND;\n/\n";
        try (TestProject project = TestProject.create().file("db/oracle/blk.sql", file)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            String block = "sql:stmt:db/oracle/blk.sql:1";
            assertThat(targets(g, block, EdgeKind.READS)).containsExactly("db:sequence:SEQ_BLOCK");
            assertThat(edge(g, block, EdgeKind.READS, "db:sequence:SEQ_BLOCK").attrBoolean("sequence")).isTrue();
            assertThat(node(g, block).attrString("statementType")).isEqualTo("UPDATE");
            assertThat(g.nodes(NodeKind.SQL_STATEMENT)).hasSize(1);
        }
    }
}
