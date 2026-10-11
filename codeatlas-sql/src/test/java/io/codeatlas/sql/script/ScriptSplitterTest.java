package io.codeatlas.sql.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScriptSplitterTest {

    private static List<ScriptUnit> split(String text) {
        return ScriptSplitter.split(text);
    }

    /** 단위마다 "종류@시작줄:첫 두 단어" */
    private static String summary(List<ScriptUnit> units) {
        return units.stream()
                .map(u -> u.kind().name().charAt(0) + "@" + u.startLine() + ":"
                        + u.tokens().stream().limit(2).map(t -> t.upper()).collect(Collectors.joining(" ")))
                .collect(Collectors.joining(" | "));
    }

    @Test
    @DisplayName("F-006-AC10 세미콜론으로 나누고 문자열·주석 안의 세미콜론은 무시한다")
    void semicolons() {
        List<ScriptUnit> units = split("SELECT 1 FROM a;\n-- c; c\nINSERT INTO b VALUES ('x;y'); /* ; */ UPDATE c SET d = 1\n;\n");
        assertThat(summary(units)).isEqualTo("S@1:SELECT 1 | S@3:INSERT INTO | S@3:UPDATE C");
        assertThat(units.get(0).text()).isEqualTo("SELECT 1 FROM a");
        assertThat(units.get(1).tokens().get(units.get(1).tokens().size() - 1).text()).isEqualTo(")");
        assertThat(units.get(2).endLine()).isEqualTo(3);
    }

    @Test
    @DisplayName("F-006-AC10 줄 단독 / 는 종결자이고 식 안의 / 는 아니다")
    void slash() {
        List<ScriptUnit> units = split("SELECT a / b FROM t\n/\nSELECT 2 FROM u\n/\n");
        assertThat(summary(units)).isEqualTo("S@1:SELECT A | S@3:SELECT 2");
    }

    @Test
    @DisplayName("F-006-AC10 프로시저는 내부 세미콜론에서 끊지 않고 한 단위로 잡는다 (/ 유무 모두)")
    void oracleProcedure() {
        String proc = "CREATE OR REPLACE PROCEDURE sp_a (p_id IN NUMBER) IS\n  v NUMBER;\nBEGIN\n  IF p_id > 0 THEN\n    UPDATE t SET x = 1 WHERE id = p_id;\n  END IF;\n"
                + "  FOR r IN (SELECT id FROM t) LOOP\n    v := CASE WHEN r.id > 1 THEN 1 ELSE 0 END;\n  END LOOP;\n  BEGIN\n    NULL;\n  EXCEPTION WHEN OTHERS THEN NULL;\n  END;\nEND sp_a;\n";
        List<ScriptUnit> withSlash = split(proc + "/\nSELECT 1 FROM dual;\n");
        assertThat(summary(withSlash)).isEqualTo("R@1:CREATE OR | S@16:SELECT 1");
        assertThat(withSlash.get(0).text()).endsWith("END sp_a;");
        List<ScriptUnit> withoutSlash = split(proc + "\nCREATE TABLE t2 (a NUMBER);\n");
        assertThat(summary(withoutSlash)).isEqualTo("R@1:CREATE OR | S@16:CREATE TABLE");
    }

    @Test
    @DisplayName("F-006-AC10 중첩 프로시저가 있는 프로시저, 선언부가 있는 트리거, 익명 블록")
    void nestedAndTrigger() {
        String sql = "CREATE PROCEDURE outer_p IS\n  PROCEDURE inner_p IS\n  BEGIN\n    NULL;\n  END inner_p;\nBEGIN\n  inner_p;\nEND;\n/\n"
                + "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW\nDECLARE\n  v NUMBER;\nBEGIN\n  SELECT 1 INTO v FROM dual;\nEND;\n/\n"
                + "DECLARE\n  x NUMBER;\nBEGIN\n  x := 1;\nEND;\n/\nBEGIN\n  pkg.run;\nEND;\n";
        List<ScriptUnit> units = split(sql);
        assertThat(summary(units)).isEqualTo("R@1:CREATE PROCEDURE | R@10:CREATE OR | B@17:DECLARE X | B@23:BEGIN PKG");
    }

    @Test
    @DisplayName("F-006-AC10 패키지 명세와 본문(초기화부 포함)은 각각 한 단위다")
    void packages() {
        String sql = "CREATE OR REPLACE PACKAGE pkg AS\n  TYPE t_rec IS RECORD (a NUMBER);\n  CURSOR c IS SELECT CASE WHEN a = 1 THEN 1 END FROM t;\n  PROCEDURE a(x NUMBER);\n  FUNCTION b RETURN NUMBER;\nEND pkg;\n/\n"
                + "CREATE OR REPLACE PACKAGE BODY pkg AS\n  g NUMBER;\n  PROCEDURE a(x NUMBER) IS\n    v NUMBER;\n    FUNCTION local_f RETURN NUMBER IS BEGIN RETURN 1; END;\n  BEGIN\n    v := local_f;\n  END a;\n"
                + "  FUNCTION b RETURN NUMBER IS\n  BEGIN\n    RETURN 1;\n  END b;\nBEGIN\n  g := 0;\nEND pkg;\n\nSELECT 1 FROM dual;";
        List<ScriptUnit> units = split(sql);
        assertThat(summary(units)).isEqualTo("R@1:CREATE OR | R@8:CREATE OR | S@24:SELECT 1");
    }

    @Test
    @DisplayName("F-006-AC10 DB2: --#SET TERMINATOR @ 지시문과 @ 종결자")
    void db2Terminator() {
        String sql = "--#SET TERMINATOR @\nCREATE OR REPLACE PROCEDURE sp_log (IN p VARCHAR(10))\nLANGUAGE SQL\nBEGIN ATOMIC\n  DECLARE v INT;\n  WHILE v < 3 DO\n    SET v = v + 1;\n  END WHILE;\n  INSERT INTO log (m) VALUES (p);\nEND@\n\n"
                + "CREATE TABLE t (a INT)@\nSELECT * FROM t@\n--#SET TERMINATOR ;\nSELECT 1 FROM sysibm.sysdummy1;\nSELECT * FROM emp@remote;";
        List<ScriptUnit> units = split(sql);
        assertThat(summary(units)).isEqualTo("R@2:CREATE OR | S@12:CREATE TABLE | S@13:SELECT * | S@15:SELECT 1 | S@16:SELECT *");
        assertThat(units.get(4).text()).isEqualTo("SELECT * FROM emp@remote");
    }

    @Test
    @DisplayName("F-006-AC10 DB2: 지시문이 없어도 줄 끝의 @ 는 종결자로 본다")
    void db2TerminatorHeuristic() {
        String sql = "CREATE PROCEDURE p ()\nLANGUAGE SQL\nBEGIN\n  DELETE FROM t;\nEND@\nCREATE FUNCTION f (a INT) RETURNS INT LANGUAGE SQL RETURN a + 1@\n";
        assertThat(summary(split(sql))).isEqualTo("R@1:CREATE PROCEDURE | R@6:CREATE FUNCTION");
    }

    @Test
    @DisplayName("F-006-AC10 본문 없는 루틴(외부 루틴, RETURN 식 함수, 호출 명세)은 세미콜론에서 끝난다")
    void routinesWithoutBody() {
        String sql = "CREATE FUNCTION f (a INT) RETURNS INT LANGUAGE SQL RETURN CASE WHEN a > 0 THEN 1 ELSE 0 END;\n"
                + "CREATE PROCEDURE ext (IN a INT) EXTERNAL NAME 'lib!fn' LANGUAGE C;\n"
                + "CREATE PROCEDURE j AS LANGUAGE JAVA NAME 'A.b()';\n"
                + "CREATE TRIGGER trg AFTER INSERT ON t REFERENCING NEW AS n FOR EACH ROW MODE DB2SQL INSERT INTO log VALUES (n.id);\n"
                + "SELECT 1 FROM t;";
        assertThat(summary(split(sql))).isEqualTo("R@1:CREATE FUNCTION | R@2:CREATE PROCEDURE | R@3:CREATE PROCEDURE | R@4:CREATE TRIGGER | S@5:SELECT 1");
    }

    @Test
    @DisplayName("F-006-AC10 빈 줄: 새 구문을 시작하는 키워드 앞에서만 끊는다")
    void blankLines() {
        assertThat(summary(split("SELECT * FROM a\n\nUPDATE b SET x = 1\n\nDELETE FROM c"))).isEqualTo("S@1:SELECT * | S@3:UPDATE B | S@5:DELETE FROM");
        assertThat(summary(split("INSERT INTO t (a)\n\nSELECT 1 FROM dual"))).isEqualTo("S@1:INSERT INTO");
        assertThat(summary(split("SELECT 1 FROM a\nUNION ALL\n\nSELECT 2 FROM b"))).isEqualTo("S@1:SELECT 1");
        assertThat(summary(split("SELECT 1 FROM a\n\nSELECT 2 FROM b"))).isEqualTo("S@1:SELECT 1 | S@3:SELECT 2");
        assertThat(summary(split("SELECT 1 FROM a\n\n WHERE x = 1\n\n   AND y = 2"))).isEqualTo("S@1:SELECT 1");
        assertThat(summary(split("SELECT x FROM a\n\n FOR UPDATE"))).isEqualTo("S@1:SELECT X");
        assertThat(summary(split("CREATE VIEW v AS\n\nSELECT 1 FROM a"))).isEqualTo("S@1:CREATE VIEW");
        assertThat(summary(split("SELECT B.ID, (SELECT COUNT(*\n FROM C\n WHERE B.U IN ('Y'\n\nUPDATE T\n SET N = '닫히지 않음\n WHERE ID = 1;")))
                .isEqualTo("S@1:SELECT B | S@5:UPDATE T");
    }

    @Test
    @DisplayName("F-006-AC10 SQL*Plus 줄 명령은 그 줄에서 끝나고 PROMPT/REM 줄은 버린다")
    void sqlplusCommands() {
        String sql = "SET DEFINE OFF\nPROMPT creating table 'x\nREM don't care\nWHENEVER SQLERROR EXIT\nCREATE TABLE t (a NUMBER);\n@other_script.sql\nCOMMIT\nEXIT\n";
        assertThat(summary(split(sql))).isEqualTo("S@1:SET DEFINE | S@4:WHENEVER SQLERROR | S@5:CREATE TABLE | S@6:@ OTHER_SCRIPT | S@7:COMMIT | S@8:EXIT");
    }

    @Test
    @DisplayName("F-006-AC10 내장 소스 모드: 빈 줄, /, 줄 명령 규칙을 쓰지 않는다")
    void embeddedMode() {
        List<ScriptUnit> units = ScriptSplitter.splitEmbedded("SELECT a\n\nFROM t\n\nWHERE x = 1;\n\nSELECT b\n/\n2 FROM u");
        assertThat(summary(units)).isEqualTo("S@1:SELECT A | S@7:SELECT B");
        assertThat(summary(ScriptSplitter.splitEmbedded("BEGIN pkg.proc(1); pkg.other(2); END;"))).isEqualTo("B@1:BEGIN PKG");
        assertThat(summary(ScriptSplitter.splitEmbedded("{call pkg.proc(?, ?)}"))).isEqualTo("S@1:{ CALL");
        assertThat(summary(ScriptSplitter.splitEmbedded("SET x = 1 WHERE y = 2"))).isEqualTo("S@1:SET X");
    }

    @Test
    @DisplayName("F-006-AC10 깨진 루틴 뒤의 줄 처음 CREATE 에서 다시 끊는다")
    void safetyNet() {
        String sql = "CREATE PROCEDURE p IS\nBEGIN\n  BEGIN\n    NULL;\nEND;\n\nCREATE TABLE t (a NUMBER);\n";
        assertThat(summary(split(sql))).isEqualTo("R@1:CREATE PROCEDURE | S@7:CREATE TABLE");
    }

    @Test
    @DisplayName("F-007-AC12 루틴 헤더: 종류, 이름, 매개변수, 트리거 대상, 패키지 멤버")
    void headers() {
        RoutineHeader p = RoutineHeader.parse(split("CREATE OR REPLACE PROCEDURE shop.sp_a (p_id IN NUMBER, p_nm OUT VARCHAR2) IS BEGIN NULL; END;").get(0).tokens());
        assertThat(p.kind()).isEqualTo(RoutineHeader.Kind.PROCEDURE);
        assertThat(p.name().name()).isEqualTo("SP_A");
        assertThat(p.name().qualifier()).isEqualTo("SHOP");
        assertThat(p.parameters()).containsExactly("P_ID IN NUMBER", "P_NM OUT VARCHAR2");

        RoutineHeader f = RoutineHeader.parse(split("CREATE FUNCTION fn_x RETURN NUMBER IS BEGIN RETURN 1; END;").get(0).tokens());
        assertThat(f.kind()).isEqualTo(RoutineHeader.Kind.FUNCTION);
        assertThat(f.parameters()).isEmpty();

        RoutineHeader t = RoutineHeader.parse(split("CREATE OR REPLACE TRIGGER trg_a AFTER UPDATE OF st ON shop.tb_order FOR EACH ROW BEGIN NULL; END;").get(0).tokens());
        assertThat(t.kind()).isEqualTo(RoutineHeader.Kind.TRIGGER);
        assertThat(t.triggerTable().name()).isEqualTo("TB_ORDER");
        assertThat(t.triggerEvent()).isEqualTo("AFTER UPDATE OF ST");

        RoutineHeader spec = RoutineHeader.parse(split("CREATE PACKAGE pkg AS PROCEDURE a(x NUMBER); FUNCTION b RETURN NUMBER; END pkg;").get(0).tokens());
        assertThat(spec.kind()).isEqualTo(RoutineHeader.Kind.PACKAGE);
        assertThat(spec.members()).extracting(RoutineHeader.Member::name).containsExactly("A", "B");
        assertThat(spec.members()).allMatch(m -> !m.hasBody());

        List<ScriptUnit> units = split("CREATE OR REPLACE PACKAGE BODY pkg AS\n PROCEDURE a(x NUMBER) IS\n  FUNCTION loc RETURN NUMBER IS BEGIN RETURN 1; END;\n BEGIN NULL; END a;\n FUNCTION b RETURN NUMBER IS BEGIN RETURN 1; END b;\n PROCEDURE fwd;\nBEGIN NULL; END pkg;");
        RoutineHeader body = RoutineHeader.parse(units.get(0).tokens());
        assertThat(body.kind()).isEqualTo(RoutineHeader.Kind.PACKAGE_BODY);
        assertThat(body.name().name()).isEqualTo("PKG");
        assertThat(body.members()).extracting(RoutineHeader.Member::name).containsExactly("A", "B", "FWD");
        assertThat(body.members()).extracting(RoutineHeader.Member::hasBody).containsExactly(true, true, false);
        RoutineHeader.Member a = body.members().get(0);
        assertThat(units.get(0).tokens().get(a.startToken()).upper()).isEqualTo("PROCEDURE");
        assertThat(units.get(0).tokens().get(a.endToken() - 1).text()).isEqualTo(";");
        assertThat(units.get(0).tokens().get(a.endToken() - 2).upper()).isEqualTo("A");
        assertThat(a.pos().line()).isEqualTo(2);

        RoutineHeader block = RoutineHeader.parse(split("BEGIN NULL; END;").get(0).tokens());
        assertThat(block.kind()).isEqualTo(RoutineHeader.Kind.ANONYMOUS_BLOCK);
    }

    @Test
    @DisplayName("F-006-AC22 종결자 없이 줄바꿈으로만 나눈 스크립트는 줄 처음의 구문 키워드에서 끊는다(줄을 바꿔 이어 쓴 SELECT는 끊지 않는다)")
    void lineSeparated() {
        String text = "DROP TABLE IF EXISTS A\nCREATE MEMORY TABLE A(ID INT,\n  NM VARCHAR(10))\nSET SCHEMA PUBLIC\n"
                + "INSERT INTO A VALUES(1,'x; y')\nINSERT INTO A\nSELECT ID, NM FROM B\nUPDATE A SET NM = 'z'\n WHERE ID = 1\n";
        assertThat(ScriptSplitter.shapeOf(text).lineSeparated()).isTrue();
        List<ScriptUnit> units = split(text);
        assertThat(summary(units)).isEqualTo("S@1:DROP TABLE | S@2:CREATE MEMORY | S@4:SET SCHEMA | S@5:INSERT INTO | S@6:INSERT INTO | S@8:UPDATE A");
        assertThat(units.get(1).endLine()).isEqualTo(3);
        assertThat(units.get(4).text()).isEqualTo("INSERT INTO A\nSELECT ID, NM FROM B");
        assertThat(units.get(5).endLine()).isEqualTo(9);
    }

    @Test
    @DisplayName("F-006-AC22 파일의 모양: 문자열·주석 밖의 종결자와 줄 시작 구문 키워드를 센다. 종결자가 있는 보통 스크립트는 줄에서 끊지 않는다")
    void shape() {
        ScriptSplitter.Shape normal = ScriptSplitter.shapeOf("CREATE TABLE A (ID INT);\nINSERT INTO A\n VALUES (1);\n-- INSERT x;\n/* CREATE ; */\nINSERT INTO A VALUES (';');\n");
        assertThat(normal.terminators()).isEqualTo(3);
        assertThat(normal.lineStartStatements()).isEqualTo(3);
        assertThat(normal.lineSeparated()).isFalse();
        assertThat(normal.hasContent()).isTrue();
        // 줄 단독 / 와 줄 끝 @ 도 종결자다
        assertThat(ScriptSplitter.shapeOf("CREATE PROCEDURE P IS BEGIN NULL; END;\n/\nCREATE TABLE T (A INT)\n/\n").terminators()).isEqualTo(4);
        assertThat(ScriptSplitter.shapeOf("CREATE TABLE T (A INT) @\nINSERT INTO T VALUES (1) @\n").terminators()).isEqualTo(2);
        // 종결자 지시문이 있으면 줄바꿈 구분으로 보지 않는다
        assertThat(ScriptSplitter.shapeOf("--#SET TERMINATOR !\nCREATE TABLE T (A INT)!\nINSERT INTO T VALUES (1)!\n").lineSeparated()).isFalse();
        ScriptSplitter.Shape drops = ScriptSplitter.shapeOf("DROP TABLE A\nDROP TABLE B\n# CREATE x\n");
        assertThat(drops.lineSeparated()).isTrue();
        assertThat(drops.hasContent()).isFalse();
        // 끝에 종결자가 하나뿐인 파일도 줄바꿈 구분으로 본다
        assertThat(summary(split("CREATE TABLE A (ID INT)\nCREATE TABLE B (ID INT)\nINSERT INTO A VALUES (1);\n")))
                .isEqualTo("S@1:CREATE TABLE | S@2:CREATE TABLE | S@3:INSERT INTO");
    }

    @Test
    @DisplayName("F-006-AC15 흘려 나누기: forEach는 split과 같은 단위를 순서대로 넘기고, 토큰 청취자는 주석을 포함한 모든 토큰을 한 번씩 받는다")
    void streaming() {
        StringBuilder sb = new StringBuilder("-- 머리 주석\nCREATE TABLE A (ID INT);\n");
        for (int i = 0; i < 3000; i++) {
            sb.append("INSERT INTO A (ID, NM) VALUES (").append(i).append(", 'n").append(i).append("'); -- ").append(i).append('\n');
        }
        sb.append("CREATE OR REPLACE PROCEDURE P IS\nBEGIN\n  NULL;\nEND;\n/\nSELECT 1 FROM DUAL\n");
        String text = sb.toString();
        List<ScriptUnit> expected = split(text);
        List<ScriptUnit> streamed = new java.util.ArrayList<>();
        List<io.codeatlas.sql.lexer.Token> heard = new java.util.ArrayList<>();
        ScriptSplitter.Shape shape = ScriptSplitter.forEach(text, streamed::add, heard::add);
        assertThat(expected).hasSize(3003);
        assertThat(streamed).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(streamed.get(i).text()).isEqualTo(expected.get(i).text());
            assertThat(streamed.get(i).startLine()).isEqualTo(expected.get(i).startLine());
            assertThat(streamed.get(i).kind()).isEqualTo(expected.get(i).kind());
        }
        List<io.codeatlas.sql.lexer.Token> all = io.codeatlas.sql.lexer.Lexer.tokenizeWithComments(text);
        assertThat(heard).hasSize(all.size());
        for (int i = 0; i < all.size(); i++) {
            assertThat(heard.get(i).offset()).isEqualTo(all.get(i).offset());
            assertThat(heard.get(i).type()).isEqualTo(all.get(i).type());
        }
        assertThat(shape.terminators()).isGreaterThan(3000);
    }

    @Test
    @DisplayName("F-006-AC15 단위의 본문만 다시 렉싱해도(tokenizeAt) 원문 기준 위치가 처음 나눴을 때의 토큰과 같다")
    void relexAtOriginalPosition() {
        String text = "/* c */ SELECT a,\n  'x\ny' AS s FROM t; UPDATE t\n   SET a = 1 -- c\n WHERE b = `q`;\n\n  INSERT INTO t VALUES (0x1F, :b);";
        for (ScriptUnit unit : split(text)) {
            List<io.codeatlas.sql.lexer.Token> again = io.codeatlas.sql.lexer.Lexer.tokenizeAt(unit.text(), unit.first().offset(),
                    unit.startLine(), unit.startColumn());
            assertThat(again.get(again.size() - 1).isEof()).isTrue();
            assertThat(again.subList(0, again.size() - 1)).hasSize(unit.tokens().size());
            for (int i = 0; i < unit.tokens().size(); i++) {
                io.codeatlas.sql.lexer.Token a = unit.tokens().get(i);
                io.codeatlas.sql.lexer.Token b = again.get(i);
                assertThat(b.text()).isEqualTo(a.text());
                assertThat(b.type()).isEqualTo(a.type());
                assertThat(new int[] {b.offset(), b.endOffset(), b.line(), b.column(), b.endLine(), b.endColumn()})
                        .as(a.toString()).containsExactly(a.offset(), a.endOffset(), a.line(), a.column(), a.endLine(), a.endColumn());
            }
        }
    }

    @Test
    @DisplayName("F-006-AC22 구문 하나짜리 파일(종결자 하나)에서 ALTER TABLE 다음 줄의 DROP·ALTER는 그 ALTER의 동작이다: 줄에서 끊지 않는다(Oracle·DB2)")
    void alterActionsOnNextLineAreNotSplit() {
        assertThat(summary(split("ALTER TABLE T\nDROP COLUMN X;\n"))).isEqualTo("S@1:ALTER TABLE");
        assertThat(split("ALTER TABLE T\nDROP COLUMN X;\n").get(0).text()).isEqualTo("ALTER TABLE T\nDROP COLUMN X");
        assertThat(summary(split("ALTER TABLE SHOP.T\nALTER COLUMN C SET DATA TYPE VARCHAR(20);\n"))).isEqualTo("S@1:ALTER TABLE");
        assertThat(summary(split("ALTER TABLE T\n  ADD (C1 NUMBER)\n  DROP (C2);\n"))).isEqualTo("S@1:ALTER TABLE");
        assertThat(summary(split("ALTER TABLE T ADD C1 NUMBER\nDROP CONSTRAINT FK_T\nDROP PRIMARY KEY\n"))).isEqualTo("S@1:ALTER TABLE");
        // 줄바꿈으로만 나눈 스크립트의 구문은 전처럼 줄에서 끊는다
        assertThat(summary(split("ALTER TABLE A ADD C1 INT\nDROP TABLE B\nALTER TABLE C ADD C2 INT\nALTER TABLE D ADD C3 INT\n")))
                .isEqualTo("S@1:ALTER TABLE | S@2:DROP TABLE | S@3:ALTER TABLE | S@4:ALTER TABLE");
    }
}
