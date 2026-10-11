package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.parser.RoutineLanguage;
import io.codeatlas.sql.parser.RoutineParser;
import io.codeatlas.sql.parser.SqlParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-008 퍼즈성 테스트: 임의로 자르고 망가뜨린 루틴 본문에서 본문 파서와 분석기가 예외·무한 루프 없이 끝나는지 본다. */
class RoutineFuzzTest {

    private static final String PLSQL = """
            CREATE OR REPLACE PACKAGE BODY PKG_F AS
              G_CNT NUMBER := 0;
              CURSOR G_CUR IS SELECT ID FROM T_G;
              PROCEDURE P_PRIVATE (P_MSG IN VARCHAR2) IS
                PRAGMA AUTONOMOUS_TRANSACTION;
              BEGIN
                INSERT INTO T_LOG (MSG) VALUES (P_MSG);
                COMMIT;
              END P_PRIVATE;
              FUNCTION F_AMT (P_ID NUMBER) RETURN NUMBER IS
                V NUMBER;
              BEGIN
                SELECT SUM(AMT) INTO V FROM T_ITEM WHERE ORDER_ID = P_ID;
                RETURN NVL(V, 0);
              EXCEPTION WHEN NO_DATA_FOUND THEN RETURN 0;
              END F_AMT;
              PROCEDURE P_MAIN (P_DT IN VARCHAR2, P_RC OUT SYS_REFCURSOR) IS
                CURSOR C1 (P_MIN NUMBER) IS SELECT ID, AMT FROM T_PAY WHERE AMT > P_MIN;
                TYPE T_IDS IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
                V_IDS T_IDS;
                V_SQL VARCHAR2(200) := 'DELETE FROM T_DYN WHERE DT = ''' || 'x' || '''';
                R1 C1%ROWTYPE;
                PROCEDURE LOCAL_P (K NUMBER) IS BEGIN UPDATE T_LOCAL SET X = K; P_PRIVATE('l'); END;
              BEGIN
                <<L0>>
                FOR R IN (SELECT O.ID FROM T_ORD O, T_DLV D WHERE O.ID = D.ORD_ID AND O.DT = P_DT) LOOP
                  IF F_AMT(R.ID) > 0 THEN LOCAL_P(R.ID); ELSIF R.ID IS NULL THEN CONTINUE; ELSE EXIT L0 WHEN G_CNT > 3; END IF;
                  CASE WHEN R.ID > 10 THEN P_PRIVATE('big'); ELSE NULL; END CASE;
                END LOOP;
                OPEN C1(10);
                LOOP FETCH C1 INTO R1; EXIT WHEN C1%NOTFOUND; DELETE FROM T_TMP WHERE ID = R1.ID; END LOOP;
                CLOSE C1;
                SELECT ID BULK COLLECT INTO V_IDS FROM T_TARGET WHERE DT = P_DT;
                FORALL I IN 1 .. V_IDS.COUNT UPDATE T_STOCK SET QTY = QTY - 1 WHERE ID = V_IDS(I);
                BEGIN
                  MERGE INTO T_SUM T USING (SELECT DT, COUNT(*) C FROM T_ORD GROUP BY DT) S ON (T.DT = S.DT)
                  WHEN MATCHED THEN UPDATE SET T.C = S.C WHEN NOT MATCHED THEN INSERT (DT, C) VALUES (S.DT, S.C);
                EXCEPTION WHEN DUP_VAL_ON_INDEX THEN GOTO L0; WHEN OTHERS THEN RAISE;
                END;
                EXECUTE IMMEDIATE V_SQL;
                EXECUTE IMMEDIATE 'UPDATE ' || P_DT || ' SET X = 1' USING G_CNT;
                OPEN P_RC FOR SELECT ID FROM T_OUT WHERE G_CNT > (SELECT MAX(N) FROM T_LIMIT);
                SP_OTHER(P_DT); PKG_X.RUN(1, P_NAME => F_AMT(2));
                DBMS_OUTPUT.PUT_LINE('done');
              END P_MAIN;
            BEGIN
              G_CNT := 1;
            END PKG_F;
            /
            CREATE OR REPLACE TRIGGER TRG_F BEFORE INSERT OR UPDATE ON T_ORD FOR EACH ROW WHEN (NEW.ID > 0)
            DECLARE V NUMBER;
            BEGIN
              SELECT COUNT(*) INTO V FROM T_STOCK S WHERE S.ID = :NEW.PRODUCT_ID;
              IF V = 0 THEN RAISE_APPLICATION_ERROR(-20001, 'x'); END IF;
              :NEW.UPD_DT := SYSDATE;
            END;
            /
            DECLARE X NUMBER; BEGIN FOR R IN (SELECT ID FROM T_A) LOOP PKG_F.P_MAIN('a', :rc); END LOOP; END;
            /
            """;

    private static final String SQLPL = """
            --#SET TERMINATOR @
            CREATE OR REPLACE PROCEDURE SP_F (IN P_YM CHAR(6), OUT P_CNT INTEGER)
            LANGUAGE SQL
            P1: BEGIN
              DECLARE V_ID BIGINT; DECLARE V_AMT DECIMAL(15,2) DEFAULT 0; DECLARE V_EOF SMALLINT DEFAULT 0;
              DECLARE V_SQL VARCHAR(500); DECLARE S1 STATEMENT;
              DECLARE C1 CURSOR WITH HOLD FOR SELECT S.ID, S.AMT FROM T_SETTLE S WHERE S.YM = P_YM WITH UR;
              DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_EOF = 1;
              DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; CALL SP_LOG('err'); SET P_CNT = -1; END;
              SET V_AMT = (SELECT SUM(D.AMT) FROM T_DTL D WHERE D.YM = P_YM);
              VALUES NEXT VALUE FOR SEQ_F INTO V_ID;
              IF EXISTS (SELECT 1 FROM T_LOCK WHERE YM = P_YM) THEN SIGNAL SQLSTATE '75001' SET MESSAGE_TEXT = 'locked'; END IF;
              OPEN C1;
              L1:LOOP
                FETCH C1 INTO V_ID, V_AMT;
                IF V_EOF = 1 THEN LEAVE L1; END IF;
                INSERT INTO T_OUT (ID, AMT) VALUES (V_ID, V_AMT);
              END LOOP L1;
              CLOSE C1;
              FOR V AS C2 CURSOR FOR SELECT M.ID AS MID FROM T_MEMBER M DO INSERT INTO T_NOTICE (MID) VALUES (V.MID); END FOR;
              WHILE V_AMT > 0 DO SET V_AMT = V_AMT - 1; END WHILE;
              REPEAT SET V_ID = V_ID - 1; UNTIL V_ID < 0 END REPEAT;
              SET V_SQL = 'DELETE FROM ' || P_YM || ' WHERE X = 1';
              PREPARE S1 FROM V_SQL; EXECUTE S1;
              GET DIAGNOSTICS P_CNT = ROW_COUNT;
              CALL SP_LOG('ok=' || CHAR(P_CNT));
            END P1@
            CREATE TRIGGER TRG_F2 AFTER UPDATE OF ST ON T_ORD REFERENCING NEW AS N OLD AS O FOR EACH ROW MODE DB2SQL
            WHEN (N.ST <> O.ST)
            BEGIN ATOMIC
              INSERT INTO T_HIST (ID, ST) VALUES (N.ID, N.ST);
            END@
            CREATE FUNCTION FN_F (P CHAR(2)) RETURNS DECIMAL(5,2) LANGUAGE SQL RETURN (SELECT R.RATE FROM T_RATE R WHERE R.CD = P)@
            """;

    private static final String JUNK = "()',;.=*@{}:?\"-/\n \t%&|<>!#$[]\\";
    private static final List<String> WORDS = List.of(" BEGIN ", " END ", " END;", " END IF;", " END LOOP;", " IS ", " AS ", " ( ", " ) ",
            " SELECT ", " FROM ", " INTO ", "\n/\n", " PROCEDURE ", " FUNCTION ", " DECLARE ", " CASE ", " WHEN ", " THEN ", " ELSE ", " LOOP ",
            " IF ", " FOR ", " IN ", " CURSOR ", " EXCEPTION ", " EXECUTE IMMEDIATE ", " := ", " || ", " SET ", " CALL ", " DO ", " HANDLER ",
            " UNTIL ", " <<L>> ", " L: ", "/*", "*/", "--", " q'[", "$IF ", " $THEN ", " $END ", "''", " RETURN ", " OPEN ", " FETCH ");

    private static List<String> mutations(String script, Random random, int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i <= script.length(); i += 11) {
            list.add(script.substring(0, i));
            list.add(script.substring(i));
        }
        for (String remove : List.of("(", ")", ";", "'", "END", "BEGIN", "THEN", "LOOP", "IS", "DECLARE", "/", "@", "IF", "INTO", "FROM")) {
            list.add(script.replace(remove, ""));
        }
        list.add(script.replace("BEGIN", "BEGIN BEGIN"));
        list.add(script.replace("END", "END END"));
        list.add(script.replace(";", ";;"));
        list.add(script.replace("\n", " "));
        for (int n = 0; n < count; n++) {
            StringBuilder sb = new StringBuilder(script);
            int edits = 1 + random.nextInt(6);
            for (int e = 0; e < edits && sb.length() > 0; e++) {
                int at = random.nextInt(sb.length());
                switch (random.nextInt(4)) {
                    case 0 -> sb.deleteCharAt(at);
                    case 1 -> sb.insert(at, JUNK.charAt(random.nextInt(JUNK.length())));
                    case 2 -> sb.delete(at, Math.min(sb.length(), at + random.nextInt(60)));
                    default -> sb.insert(at, WORDS.get(random.nextInt(WORDS.size())));
                }
            }
            list.add(sb.toString());
        }
        return list;
    }

    @Test
    @DisplayName("F-008-AC12 퍼즈: 본문 파서가 임의로 망가뜨린 입력에 예외·무한 루프 없이 끝난다(본문, 서브프로그램, 패키지 구간, 익명 블록)")
    void parser() {
        Random random = new Random(8L);
        List<String> texts = new ArrayList<>();
        texts.addAll(mutations(PLSQL, random, 1200));
        texts.addAll(mutations(SQLPL, random, 1200));
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (String text : texts) {
                try {
                    List<Token> all = Lexer.tokenize(text);
                    List<Token> tokens = all.subList(0, all.size() - 1);
                    for (RoutineLanguage sqlPl : new RoutineLanguage[] {RoutineLanguage.PLSQL, RoutineLanguage.SQLPL}) {
                        Routine.Block a = new RoutineParser(tokens, sqlPl).parseBody(false);
                        Routine.Block b = new RoutineParser(tokens, sqlPl).parseBody(true);
                        assertThat(a).isNotNull();
                        assertThat(b).isNotNull();
                        assertThat(new RoutineParser(tokens, sqlPl).parsePackageSection()).isNotNull();
                        assertThat(new RoutineParser(tokens, sqlPl).parseSubprogram()).isNotNull();
                        assertThat(new RoutineParser(tokens, sqlPl).parseExpression()).isNotNull();
                    }
                    // 익명 블록으로도(구문 파서를 거쳐)
                    assertThat(SqlParser.parse("BEGIN " + text).statement()).isNotNull();
                } catch (RuntimeException | StackOverflowError e) {
                    throw new AssertionError("예외 발생: [" + text + "]", e);
                }
            }
        });
    }

    @Test
    @DisplayName("F-008-AC12 퍼즈: 분석기 전체가 망가진 루틴 파일과 내장 블록에 대해 ERROR 진단 없이 끝난다")
    void analyzer() {
        Random random = new Random(21L);
        List<String> texts = new ArrayList<>();
        texts.addAll(mutations(PLSQL, random, 250));
        texts.addAll(mutations(SQLPL, random, 250));
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
            try (TestProject project = TestProject.create()) {
                for (int i = 0; i < texts.size(); i++) {
                    project.file((i % 2 == 0 ? "db/oracle/f" : "db/db2/f") + i + ".sql", texts.get(i));
                }
                AnalysisContext ctx = project.context();
                for (int i = 0; i < texts.size(); i += 3) {
                    String owner = Ids.mybatisStatement("ns", "s" + i);
                    ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).build());
                    ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, "BEGIN " + texts.get(i),
                            SourceLocation.of("m.xml", i + 1), i % 2 == 0, Map.of()));
                }
                GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
                assertThat(g.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
                assertThat(g.nodes(NodeKind.SQL_STATEMENT).size()).isGreaterThan(texts.size());
            }
        });
    }

    @Test
    @DisplayName("F-008-AC12 원본 픽스처는 진단 없이(동적 SQL 제외) 분석된다")
    void pristineFixtures() {
        try (TestProject project = TestProject.create().file("db/oracle/f.sql", PLSQL).file("db/db2/f.db2", SQLPL)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(g.diagnostics()).allMatch(d -> d.code().equals("DYNAMIC_SQL"));
            assertThat(g.diagnostics()).hasSize(2);
            assertThat(GraphAssert.targets(g, "db:routine:PKG_F.P_MAIN", io.codeatlas.core.model.EdgeKind.CALLS)).containsExactly(
                    "db:routine:PKG_F.F_AMT", "db:routine:PKG_F.P_PRIVATE", "db:routine:PKG_X.RUN", "db:routine:SP_OTHER");
            assertThat(GraphAssert.targets(g, "db:routine:SP_F", io.codeatlas.core.model.EdgeKind.CALLS)).containsExactly("db:routine:SP_LOG");
            assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::id).contains("db:table:T_DYN", "db:table:T_LOCAL", "db:table:T_LIMIT",
                    "db:table:T_HIST", "db:table:T_RATE", "db:table:T_NOTICE", "db:table:T_G").doesNotContain("db:table:R", "db:table:N", "db:table:V");
        }
    }
}
