package io.codeatlas.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-008 본문 파서: 구문 트리의 모양과 오류 복구. */
class RoutineParserTest {

    private static RoutineParser parser(String text, boolean sqlPl) {
        List<Token> tokens = Lexer.tokenize(text);
        return new RoutineParser(tokens.subList(0, tokens.size() - 1), sqlPl);
    }

    private static Routine.Block plsql(String text) {
        RoutineParser p = parser(text, false);
        Routine.Block b = p.parseBody(false);
        assertThat(p.problems()).as("problems of: " + text).isEmpty();
        return b;
    }

    private static Routine.Block sqlPl(String text) {
        RoutineParser p = parser(text, true);
        Routine.Block b = p.parseBody(false);
        assertThat(p.problems()).as("problems of: " + text).isEmpty();
        return b;
    }

    /** 트리 안의 모든 SQL 구문의 종류와 시작 줄: {@code Select@3,Update@7}. */
    private static String sqls(Routine.Stmt root) {
        List<String> out = new ArrayList<>();
        Routine.visit(root, new Routine.Visitor() {
            @Override
            public void sql(Routine.Sql q) {
                assertThat(q.problems()).as("sql problems").isEmpty();
                out.add(q.statement().getClass().getSimpleName() + "@" + q.pos().line());
            }
        });
        return String.join(",", out);
    }

    /** 트리 안의 모든 호출문 이름. */
    private static String calls(Routine.Stmt root) {
        List<String> out = new ArrayList<>();
        Routine.visit(root, new Routine.Visitor() {
            @Override
            public void statement(Routine.Stmt s) {
                if (s instanceof Routine.Call c) {
                    out.add(c.name().display() + (c.callKeyword() ? "[CALL]" : ""));
                }
            }
        });
        return String.join(",", out);
    }

    /** 문장 종류를 한 줄로: {@code If,Loop,Sql}. */
    private static String kinds(List<Routine.Stmt> statements) {
        return statements.stream().map(s -> s.getClass().getSimpleName()).collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("F-008-AC1 PL/SQL: 선언부, 중첩 블록, IF/ELSIF/ELSE, CASE 문, LOOP/WHILE/FOR, 레이블, GOTO, 예외 처리부, PRAGMA, PIPE ROW")
    void plsqlStructure() {
        String text = """
                DECLARE
                    C_MAX   CONSTANT NUMBER := 10;
                    V_CNT   PLS_INTEGER := 0;
                    V_ROW   TB_A%ROWTYPE;
                    V_ST    TB_A.ST%TYPE DEFAULT 'N';
                    E_STOP  EXCEPTION;
                    PRAGMA EXCEPTION_INIT(E_STOP, -20001);
                    TYPE T_REC IS RECORD (ID NUMBER, NM VARCHAR2(10));
                    SUBTYPE T_ID IS NUMBER(10);
                    CURSOR C1 (P_MIN NUMBER DEFAULT 0) IS
                        SELECT ID FROM TB_A WHERE ID > P_MIN;
                    FUNCTION LOCAL_F (P_X IN NUMBER, P_Y IN OUT NOCOPY VARCHAR2) RETURN NUMBER IS
                    BEGIN
                        RETURN P_X + 1;
                    END LOCAL_F;
                    PROCEDURE FORWARD_P (P_Z NUMBER);
                BEGIN
                    <<OUTER_LOOP>>
                    FOR I IN REVERSE 1 .. C_MAX LOOP
                        IF I = 1 THEN
                            UPDATE TB_A SET ST = 'A' WHERE ID = I;
                        ELSIF I = 2 THEN
                            DELETE FROM TB_B WHERE ID = I;
                        ELSE
                            CASE
                                WHEN I > 5 THEN
                                    INSERT INTO TB_C (ID) VALUES (I);
                                WHEN I > 3 THEN NULL;
                                ELSE
                                    SP_ELSE(I);
                            END CASE;
                        END IF;
                        CASE V_ST
                            WHEN 'N' THEN PKG.DO_N(V_CNT);
                            ELSE NULL;
                        END CASE;
                        CONTINUE WHEN I = 7;
                        EXIT OUTER_LOOP WHEN I = 9;
                    END LOOP OUTER_LOOP;
                    WHILE V_CNT < 3 LOOP
                        V_CNT := V_CNT + CASE WHEN V_CNT = 0 THEN 1 ELSE 2 END;
                    END LOOP;
                    LOOP
                        EXIT WHEN V_CNT > 0;
                    END LOOP;
                    BEGIN
                        SELECT ST INTO V_ST FROM TB_A WHERE ID = 1;
                    EXCEPTION
                        WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
                            GOTO DONE;
                        WHEN OTHERS THEN
                            RAISE;
                    END;
                    <<DONE>>
                    NULL;
                    PIPE ROW (V_ROW);
                    COMMIT;
                    SAVEPOINT SP1;
                    ROLLBACK TO SAVEPOINT SP1;
                    RETURN;
                EXCEPTION
                    WHEN E_STOP THEN
                        RAISE_APPLICATION_ERROR(-20001, 'stop');
                END;
                """;
        Routine.Block b = plsql(text);
        assertThat(b.declarations()).extracting(d -> d.getClass().getSimpleName())
                .containsExactly("Variable", "Variable", "Variable", "Variable", "Variable", "OtherDecl", "TypeDef", "TypeDef", "Cursor",
                        "Subprogram", "Subprogram");
        Routine.Variable st = (Routine.Variable) b.declarations().get(3);
        assertThat(st.names()).containsExactly("V_ST");
        assertThat(st.type()).isEqualTo("TB_A.ST%TYPE");
        assertThat(((Routine.Variable) b.declarations().get(0)).kind()).isEqualTo("CONSTANT");
        assertThat(((Routine.Variable) b.declarations().get(4)).kind()).isEqualTo("EXCEPTION");
        Routine.Cursor c1 = (Routine.Cursor) b.declarations().get(8);
        assertThat(c1.name()).isEqualTo("C1");
        assertThat(c1.params()).extracting(Routine.Param::name).containsExactly("P_MIN");
        assertThat(c1.query().pos().line()).isEqualTo(11);
        Routine.Subprogram f = (Routine.Subprogram) b.declarations().get(9);
        assertThat(f.kind()).isEqualTo("FUNCTION");
        assertThat(f.name()).isEqualTo("LOCAL_F");
        assertThat(f.returnType()).isEqualTo("NUMBER");
        assertThat(f.params()).extracting(p -> p.name() + ":" + p.mode() + ":" + p.type())
                .containsExactly("P_X:IN:NUMBER", "P_Y:IN OUT:VARCHAR2");
        assertThat(kinds(f.body().statements())).isEqualTo("Return");
        assertThat(((Routine.Subprogram) b.declarations().get(10)).body()).isNull();

        assertThat(kinds(b.statements())).isEqualTo("Loop,Loop,Loop,Block,Simple,Simple,Simple,Simple,Simple,Return");
        Routine.Loop forLoop = (Routine.Loop) b.statements().get(0);
        assertThat(forLoop.kind()).isEqualTo(Routine.LoopKind.FOR_RANGE);
        assertThat(forLoop.label()).isEqualTo("OUTER_LOOP");
        assertThat(forLoop.variable()).isEqualTo("I");
        assertThat(kinds(forLoop.statements())).isEqualTo("If,Case,Jump,Jump");
        Routine.If ifs = (Routine.If) forLoop.statements().get(0);
        assertThat(ifs.branches()).hasSize(2);
        assertThat(kinds(ifs.otherwise())).isEqualTo("Case");
        Routine.Case searched = (Routine.Case) ifs.otherwise().get(0);
        assertThat(searched.selector()).isNull();
        assertThat(searched.branches()).hasSize(2);
        assertThat(kinds(searched.otherwise())).isEqualTo("Call");
        assertThat(((Routine.Case) forLoop.statements().get(1)).selector()).isNotNull();
        Routine.Jump exit = (Routine.Jump) forLoop.statements().get(3);
        assertThat(exit.kind()).isEqualTo("EXIT");
        assertThat(exit.label()).isEqualTo("OUTER_LOOP");
        assertThat(exit.when()).isNotNull();
        assertThat(((Routine.Loop) b.statements().get(1)).kind()).isEqualTo(Routine.LoopKind.WHILE);
        assertThat(((Routine.Loop) b.statements().get(2)).kind()).isEqualTo(Routine.LoopKind.BASIC);
        Routine.Block inner = (Routine.Block) b.statements().get(3);
        assertThat(inner.handlers()).hasSize(2);
        assertThat(inner.handlers().get(0).exceptions()).containsExactly("NO_DATA_FOUND", "TOO_MANY_ROWS");
        assertThat(kinds(inner.handlers().get(0).statements())).isEqualTo("Jump");
        assertThat(b.handlers()).hasSize(1);

        assertThat(sqls(b)).isEqualTo("Select@11,Update@21,Delete@23,Insert@27,Select@47");
        assertThat(calls(b)).isEqualTo("SP_ELSE,PKG.DO_N,RAISE_APPLICATION_ERROR");
    }

    @Test
    @DisplayName("F-008-AC1 PL/SQL: 커서 FOR, OPEN/FETCH/CLOSE, FORALL, EXECUTE IMMEDIATE, 대입 대상의 첨자·필드, 조건부 컴파일($IF) 건너뛰기")
    void plsqlCursorsAndDynamic() {
        String text = """
                BEGIN
                    FOR R IN (SELECT A FROM T1) LOOP
                        V_TAB(R.A).NM := 'x';
                        :NEW.COL := R.A;
                    END LOOP;
                    FOR R2 IN C1(10) LOOP NULL; END LOOP;
                    OPEN C1(5);
                    FETCH C1 BULK COLLECT INTO V_A, V_B LIMIT 100;
                    CLOSE C1;
                    OPEN P_RC FOR SELECT B FROM T2;
                    OPEN P_RC2 FOR V_SQL USING P_ID;
                    FORALL I IN INDICES OF V_A SAVE EXCEPTIONS
                        INSERT INTO T3 VALUES V_A(I);
                    EXECUTE IMMEDIATE 'DELETE FROM T4 WHERE ID = :1' USING P_ID RETURNING ID INTO V_ID;
                    $IF DBMS_DB_VERSION.VER_LE_11 $THEN
                        SP_OLD(1);
                    $ELSE
                        SP_NEW(1);
                    $END
                    V_X := FN_A(1) + (SELECT COUNT(*) FROM T5);
                END;
                """;
        Routine.Block b = plsql(text);
        assertThat(kinds(b.statements())).isEqualTo("Loop,Loop,Open,Fetch,Close,Open,Open,ForAll,ExecuteImmediate,Call,Call,Assign");
        Routine.Loop q = (Routine.Loop) b.statements().get(0);
        assertThat(q.kind()).isEqualTo(Routine.LoopKind.FOR_QUERY);
        assertThat(q.query().statement()).isInstanceOf(Statement.Select.class);
        assertThat(kinds(q.statements())).isEqualTo("Assign,Assign");
        Routine.Assign a1 = (Routine.Assign) q.statements().get(0);
        assertThat(a1.targets().get(0).display()).isEqualTo("V_TAB");
        assertThat(a1.index()).isNotNull();
        assertThat(((Routine.Assign) q.statements().get(1)).targets().get(0).display()).isEqualTo("NEW.COL");
        Routine.Loop c = (Routine.Loop) b.statements().get(1);
        assertThat(c.kind()).isEqualTo(Routine.LoopKind.FOR_CURSOR);
        assertThat(c.cursor()).isEqualTo("C1");
        Routine.Fetch fetch = (Routine.Fetch) b.statements().get(3);
        assertThat(fetch.cursor()).isEqualTo("C1");
        assertThat(fetch.into().refs()).extracting(r -> r.name().display()).containsExactly("V_A", "V_B");
        Routine.Open staticOpen = (Routine.Open) b.statements().get(5);
        assertThat(staticOpen.query()).isNotNull();
        assertThat(staticOpen.dynamic()).isNull();
        Routine.Open dynamicOpen = (Routine.Open) b.statements().get(6);
        assertThat(dynamicOpen.query()).isNull();
        assertThat(dynamicOpen.dynamic().refs()).extracting(r -> r.name().display()).containsExactly("V_SQL");
        assertThat(dynamicOpen.using()).isNotNull();
        Routine.ForAll forAll = (Routine.ForAll) b.statements().get(7);
        assertThat(forAll.index()).isEqualTo("I");
        assertThat(forAll.statement()).isInstanceOf(Routine.Sql.class);
        Routine.ExecuteImmediate exec = (Routine.ExecuteImmediate) b.statements().get(8);
        assertThat(exec.sql().tokens()).hasSize(1);
        assertThat(exec.rest()).isNotNull();
        Routine.Assign last = (Routine.Assign) b.statements().get(11);
        assertThat(last.value().subqueries()).hasSize(1);
        assertThat(last.value().refs()).extracting(r -> r.name().display() + ":" + r.call()).containsExactly("FN_A:true");
        assertThat(calls(b)).isEqualTo("SP_OLD,SP_NEW");
        assertThat(sqls(b)).isEqualTo("Select@2,Select@10,Insert@13,Select@20");
    }

    @Test
    @DisplayName("F-008-AC1 SQL PL: BEGIN ATOMIC, DECLARE(변수·커서·핸들러·CONDITION·STATEMENT), 레이블, WHILE/REPEAT/FOR .. DO, SET, CALL, SIGNAL, GET DIAGNOSTICS, PREPARE/EXECUTE")
    void sqlPlStructure() {
        String text = """
                P1: BEGIN ATOMIC
                    DECLARE V_A, V_B INTEGER DEFAULT 0;
                    DECLARE V_MSG VARCHAR(100);
                    DECLARE NOT_OK CONDITION FOR SQLSTATE '75001';
                    DECLARE S1 STATEMENT;
                    DECLARE C1 CURSOR WITH RETURN TO CALLER FOR
                        SELECT ID FROM T1;
                    DECLARE C2 CURSOR FOR S1;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_A = 1, V_B = 2;
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION, SQLWARNING, NOT_OK, SQLSTATE VALUE '23505'
                    BEGIN
                        GET DIAGNOSTICS EXCEPTION 1 V_MSG = MESSAGE_TEXT;
                        CALL SP_LOG(V_MSG);
                        RESIGNAL;
                    END;
                    SET V_A = (SELECT COUNT(*) FROM T2), V_B = FN_B(V_A);
                    SET CURRENT SCHEMA = 'APP';
                    L1:WHILE V_A < 3 DO
                        SET V_A = V_A + 1;
                        IF V_A = 2 THEN ITERATE L1; ELSEIF V_A = 9 THEN LEAVE L1; END IF;
                    END WHILE L1;
                    REPEAT
                        UPDATE T3 SET X = V_A;
                    UNTIL V_A > 5
                    END REPEAT;
                    FOR V AS C3 CURSOR FOR SELECT ID FROM T4 DO
                        DELETE FROM T5 WHERE ID = V.ID;
                    END FOR;
                    FOR W AS SELECT ID FROM T6 DO
                        SET V_B = W.ID;
                    END FOR;
                    PREPARE S1 FROM V_MSG;
                    EXECUTE S1 USING V_A;
                    OPEN C2;
                    VALUES (1, 2) INTO V_A, V_B;
                    IF V_A < 0 THEN
                        SIGNAL SQLSTATE '75001' SET MESSAGE_TEXT = 'bad';
                    END IF;
                    DECLARE GLOBAL TEMPORARY TABLE SESSION.TMP AS (SELECT ID FROM T7) DEFINITION ONLY;
                    RETURN 0;
                END P1
                """;
        Routine.Block b = sqlPl(text);
        assertThat(b.label()).isEqualTo("P1");
        assertThat(b.atomic()).isTrue();
        assertThat(b.declarations()).extracting(d -> d.getClass().getSimpleName())
                .containsExactly("Variable", "Variable", "Variable", "Variable", "Cursor", "Cursor", "Handler", "Handler");
        assertThat(((Routine.Variable) b.declarations().get(0)).names()).containsExactly("V_A", "V_B");
        assertThat(((Routine.Variable) b.declarations().get(2)).kind()).isEqualTo("CONDITION");
        assertThat(((Routine.Variable) b.declarations().get(3)).kind()).isEqualTo("STATEMENT");
        assertThat(((Routine.Cursor) b.declarations().get(4)).query().pos().line()).isEqualTo(7);
        assertThat(((Routine.Cursor) b.declarations().get(5)).statementName()).isEqualTo("S1");
        Routine.Handler h1 = (Routine.Handler) b.declarations().get(6);
        assertThat(h1.action()).isEqualTo("CONTINUE");
        assertThat(h1.conditions()).isEqualTo("NOT FOUND");
        assertThat(h1.body()).isInstanceOf(Routine.Block.class); // 대입 둘이 블록으로 묶인다
        Routine.Handler h2 = (Routine.Handler) b.declarations().get(7);
        assertThat(h2.action()).isEqualTo("EXIT");
        assertThat(kinds(((Routine.Block) h2.body()).statements())).isEqualTo("Simple,Call,Raise");

        assertThat(kinds(b.statements()))
                .isEqualTo("Assign,Assign,Simple,Loop,Loop,Loop,Loop,Prepare,Execute,Open,Sql,If,Simple,Return");
        Routine.Loop w = (Routine.Loop) b.statements().get(3);
        assertThat(w.kind()).isEqualTo(Routine.LoopKind.WHILE);
        assertThat(w.label()).isEqualTo("L1");
        assertThat(kinds(w.statements())).isEqualTo("Assign,If");
        assertThat(((Routine.Loop) b.statements().get(4)).kind()).isEqualTo(Routine.LoopKind.REPEAT);
        Routine.Loop f = (Routine.Loop) b.statements().get(5);
        assertThat(f.kind()).isEqualTo(Routine.LoopKind.FOR_QUERY);
        assertThat(f.variable()).isEqualTo("V");
        assertThat(f.cursor()).isEqualTo("C3");
        assertThat(((Routine.Loop) b.statements().get(6)).cursor()).isNull();
        assertThat(((Routine.Prepare) b.statements().get(7)).name()).isEqualTo("S1");
        assertThat(sqls(b)).isEqualTo("Select@7,Select@16,Update@23,Select@26,Delete@27,Select@29,Select@35,Select@39");
        assertThat(calls(b)).isEqualTo("SP_LOG[CALL]");
    }

    @Test
    @DisplayName("F-008-AC1 DECLARE의 두 뜻: PL/SQL 중첩 블록의 선언부와 DB2 선언문을 뒤따르는 토큰으로 가른다")
    void declareDisambiguation() {
        Routine.Block nested = plsql("BEGIN DECLARE X NUMBER; Y NUMBER; BEGIN X := 1; END; UPDATE T SET A = 1; END;");
        assertThat(kinds(nested.statements())).isEqualTo("Block,Sql");
        assertThat(((Routine.Block) nested.statements().get(0)).declarations()).hasSize(2);
        assertThat(nested.declarations()).isEmpty();
        // 문법 플래그가 PL/SQL이어도 DECLARE가 이어지면 DB2 선언문이다
        Routine.Block db2 = plsql("BEGIN DECLARE X INT; DECLARE Y INT; SET X = 1; END");
        assertThat(db2.declarations()).hasSize(2);
        assertThat(kinds(db2.statements())).isEqualTo("Assign");
        // DECLARE x; BEGIN 은 문법 플래그를 따른다
        Routine.Block flagged = sqlPl("BEGIN DECLARE X INT; BEGIN SET X = 1; END; END");
        assertThat(flagged.declarations()).hasSize(1);
        assertThat(kinds(flagged.statements())).isEqualTo("Block");
    }

    @Test
    @DisplayName("F-008-AC1 서브프로그램과 패키지 구간: 매개변수, 반환 타입, 선언부, 패키지 초기화부")
    void subprogramAndPackageSection() {
        RoutineParser p = parser("""
                FUNCTION GET_AMT (P_ID IN NUMBER, P_DFLT NUMBER := 0) RETURN TB_A.AMT%TYPE DETERMINISTIC IS
                    V NUMBER;
                BEGIN
                    SELECT AMT INTO V FROM TB_A WHERE ID = P_ID;
                    RETURN NVL(V, P_DFLT);
                END GET_AMT;
                """, false);
        Routine.Subprogram sp = p.parseSubprogram();
        assertThat(p.problems()).isEmpty();
        assertThat(sp.name()).isEqualTo("GET_AMT");
        assertThat(sp.returnType()).isEqualTo("TB_A.AMT%TYPE");
        assertThat(sp.params()).extracting(Routine.Param::name).containsExactly("P_ID", "P_DFLT");
        assertThat(sp.params().get(1).defaultValue()).isNotNull();
        assertThat(sp.body().declarations()).hasSize(1);
        assertThat(kinds(sp.body().statements())).isEqualTo("Sql,Return");
        assertThat(((Routine.Sql) sp.body().statements().get(0)).intoTargets()).containsExactly("V");

        RoutineParser section = parser("G_A NUMBER := 1; CURSOR C IS SELECT 1 FROM DUAL; BEGIN G_A := 2; DELETE FROM T; END PKG;", false);
        Routine.Block init = section.parsePackageSection();
        assertThat(section.problems()).isEmpty();
        assertThat(init.declarations()).hasSize(2);
        assertThat(kinds(init.statements())).isEqualTo("Assign,Sql");
        RoutineParser spec = parser("C_X CONSTANT NUMBER := 1; TYPE T IS TABLE OF NUMBER; END PKG;", false);
        Routine.Block decls = spec.parsePackageSection();
        assertThat(decls.declarations()).hasSize(2);
        assertThat(decls.statements()).isEmpty();
    }

    @Test
    @DisplayName("F-008-AC12 깨진 본문: END 누락, 괄호 불균형, 종결자 누락에서도 예외 없이 끝나고 SQL 구문을 건진다")
    void brokenBodies() {
        // END IF와 END가 없다
        RoutineParser p1 = parser("BEGIN\n UPDATE T1 SET A = 1;\n IF X THEN\n  DELETE FROM T2;\n INSERT INTO T3 VALUES (1);", false);
        Routine.Block b1 = p1.parseBody(false);
        assertThat(p1.problems()).isNotEmpty();
        assertThat(sqlKinds(b1)).isEqualTo("Update,Delete,Insert");
        // 괄호가 닫히지 않아도 ; 에서 문장이 끝난다
        RoutineParser p2 = parser("BEGIN\n V := FN(1, (2;\n UPDATE T1 SET A = (1 + 2 WHERE ID = 3;\n DELETE FROM T2;\nEND;", false);
        Routine.Block b2 = p2.parseBody(false);
        assertThat(sqlKinds(b2)).isEqualTo("Update,Delete");
        // 종결자가 빠져도 다음 줄의 문장 키워드에서 끊는다
        RoutineParser p3 = parser("BEGIN\n V := 1\n UPDATE T1 SET A = 1\n DELETE FROM T2;\nEND;", false);
        Routine.Block b3 = p3.parseBody(false);
        assertThat(p3.problems()).isNotEmpty();
        // UPDATE와 DELETE 사이에 종결자가 없다: 한 구간으로 넘어와도 구문 경계에서 잘라 둘 다 잡는다(F-006-R16)
        assertThat(sqlKinds(b3)).isEqualTo("Sequence");
        List<String> inner = new ArrayList<>();
        Routine.visit(b3, new Routine.Visitor() {
            @Override
            public void sql(Routine.Sql q) {
                ((io.codeatlas.sql.ast.Statement.Sequence) q.statement()).statements().forEach(st -> inner.add(st.getClass().getSimpleName()));
            }
        });
        assertThat(inner).containsExactly("Update", "Delete");
        // END가 남아도(짝이 어긋나도) 뒤의 문장을 읽는다
        RoutineParser p4 = parser("BEGIN\n NULL;\nEND;\nEND IF;\n UPDATE T9 SET A = 1;\nEND;", false);
        Routine.Block b4 = p4.parseBody(false);
        assertThat(p4.problems()).isNotEmpty();
        assertThat(sqlKinds(b4)).isEqualTo("Update");
        // 모르는 문장은 Unknown으로 건너뛰고 안의 서브쿼리는 건진다
        RoutineParser p5 = parser("BEGIN\n FROB THE (SELECT 1 FROM T1) NOW;\n DELETE FROM T2;\nEND;", false);
        Routine.Block b5 = p5.parseBody(false);
        assertThat(kinds(b5.statements())).isEqualTo("Unknown,Sql");
        assertThat(sqlKinds(b5)).isEqualTo("Select,Delete");
        // 빈 입력, 토큰 하나
        assertThat(parser("", false).parseBody(false).statements()).isEmpty();
        assertThat(parser("BEGIN", false).parseBody(false).statements()).isEmpty();
        assertThat(parser(")", true).parseBody(false)).isNotNull();
        assertThat(parser("", false).parseSubprogram()).isNotNull();
        assertThat(parser("END", false).parsePackageSection().declarations()).isEmpty();
    }

    private static String sqlKinds(Routine.Stmt root) {
        List<String> out = new ArrayList<>();
        Routine.visit(root, new Routine.Visitor() {
            @Override
            public void sql(Routine.Sql q) {
                out.add(q.statement().getClass().getSimpleName());
            }
        });
        return String.join(",", out);
    }
}
