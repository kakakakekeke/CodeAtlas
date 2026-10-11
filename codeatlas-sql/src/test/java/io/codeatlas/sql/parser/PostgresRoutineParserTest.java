package io.codeatlas.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PL/pgSQL 본문 파서(F-019-R25): 구문 트리의 모양. PL/SQL·SQL PL로 읽을 때의 결과는 달라지지 않는다(F-019-R8). */
class PostgresRoutineParserTest {

    private static RoutineParser parser(String text, RoutineLanguage language) {
        List<Token> tokens = Lexer.tokenize(text, language.syntax());
        return new RoutineParser(tokens.subList(0, tokens.size() - 1), language);
    }

    private static Routine.Block plpgsql(String text) {
        RoutineParser p = parser(text, RoutineLanguage.PLPGSQL);
        Routine.Block b = p.parseBody(false);
        assertThat(p.problems()).as("problems of: " + text).isEmpty();
        return b;
    }

    /** 트리 안의 모든 문장 종류(전위 순서). 해석하지 못한 문장은 {@code Unknown}으로 드러난다. */
    private static String allKinds(Routine.Stmt root) {
        List<String> out = new ArrayList<>();
        Routine.visit(root, new Routine.Visitor() {
            @Override
            public void statement(Routine.Stmt s) {
                if (s != root) {
                    out.add(s instanceof Routine.Simple x ? x.keyword() : s instanceof Routine.Jump j ? j.kind()
                            : s instanceof Routine.Loop l ? "Loop:" + l.kind() : s.getClass().getSimpleName());
                }
            }
        });
        return String.join(",", out);
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

    private static String kinds(List<Routine.Stmt> statements) {
        return statements.stream().map(s -> s.getClass().getSimpleName()).collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("F-019-AC23 PL/pgSQL 선언부: 변수(:=, =, DEFAULT, CONSTANT, NOT NULL), %TYPE·%ROWTYPE, record, ALIAS FOR, 커서(인자·SCROLL), 여분의 DECLARE, type이라는 변수")
    void declarations() {
        Routine.Block b = plpgsql("""
                <<main>>
                DECLARE
                    v_cnt integer := 0;
                    v_name text = 'x';
                    c_max CONSTANT numeric(12,2) NOT NULL DEFAULT 10.5;
                    v_id orders.customer_id%TYPE;
                    v_row public.orders%ROWTYPE;
                    r record;
                    a ALIAS FOR $1;
                    type text;
                    c1 CURSOR FOR SELECT id FROM orders;
                    c2 NO SCROLL CURSOR (p_key integer, p_st text) IS SELECT id FROM order_items WHERE order_id = p_key;
                    c3 refcursor;
                DECLARE
                    v_more timestamp with time zone;
                BEGIN
                    NULL;
                END main;
                """);
        assertThat(b.label()).isEqualTo("MAIN");
        List<String> variables = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        for (Routine.Decl d : b.declarations()) {
            if (d instanceof Routine.Variable v) {
                variables.add(v.names().get(0) + ":" + v.kind());
            } else if (d instanceof Routine.Cursor c) {
                cursors.add(c.name() + "(" + c.params().stream().map(Routine.Param::name).collect(Collectors.joining(",")) + ")"
                        + (c.query() == null ? "" : ":" + c.query().statement().getClass().getSimpleName() + "@" + c.query().pos().line()));
            } else {
                variables.add(d.getClass().getSimpleName());
            }
        }
        assertThat(variables).containsExactly("V_CNT:VARIABLE", "V_NAME:VARIABLE", "C_MAX:CONSTANT", "V_ID:VARIABLE", "V_ROW:VARIABLE", "R:VARIABLE",
                "A:VARIABLE", "TYPE:VARIABLE", "C3:VARIABLE", "V_MORE:VARIABLE");
        assertThat(cursors).containsExactly("C1():Select@11", "C2(P_KEY,P_ST):Select@12");
        Routine.Variable name = (Routine.Variable) b.declarations().get(1);
        assertThat(name.type()).isEqualTo("TEXT");
        assertThat(name.init().tokens()).extracting(Token::text).containsExactly("'x'");
        assertThat(kinds(b.statements())).isEqualTo("Simple");
    }

    @Test
    @DisplayName("F-019-AC23 PL/pgSQL 문장 전부: 대입(:=, =), IF/ELSIF/ELSE, CASE, LOOP·WHILE·FOR 범위·FOR 쿼리·FOR EXECUTE·FOREACH, EXIT·CONTINUE, RETURN·RETURN NEXT·RETURN QUERY, "
            + "PERFORM, RAISE, GET DIAGNOSTICS, ASSERT, NULL, 커서, CALL, COMMIT·ROLLBACK, 중첩 블록과 예외 처리부")
    void allStatements() {
        String body = """
                DECLARE
                    v integer := 0;
                    arr integer[];
                    r record;
                    c CURSOR FOR SELECT id FROM orders;
                    rc refcursor;
                BEGIN
                    v := 1;
                    v = v + 1;
                    arr[1] := 2;
                    r.qty = 3;
                    IF v > 1 THEN
                        v := 2;
                    ELSIF v = 0 THEN
                        v := 3;
                    ELSEIF v < 0 THEN
                        NULL;
                    ELSE
                        v := 4;
                    END IF;
                    CASE v
                        WHEN 1, 2 THEN v := 5;
                        ELSE v := 6;
                    END CASE;
                    CASE WHEN v > 5 THEN NULL; END CASE;
                    <<outer_loop>>
                    LOOP
                        EXIT outer_loop WHEN v > 10;
                        CONTINUE;
                    END LOOP outer_loop;
                    WHILE v < 20 LOOP
                        v := v + 1;
                    END LOOP;
                    FOR i IN REVERSE 10..1 BY 2 LOOP
                        NULL;
                    END LOOP;
                    FOR r IN SELECT id, qty FROM order_items WHERE qty > v LOOP
                        CONTINUE WHEN r.qty = 0;
                    END LOOP;
                    FOR r IN
                        WITH x AS (SELECT id FROM orders)
                        SELECT id FROM x
                    LOOP
                        NULL;
                    END LOOP;
                    FOR r IN EXECUTE 'SELECT id FROM orders WHERE id > $1' USING v LOOP
                        NULL;
                    END LOOP;
                    FOR r IN c LOOP
                        NULL;
                    END LOOP;
                    FOREACH v SLICE 1 IN ARRAY arr LOOP
                        NULL;
                    END LOOP;
                    PERFORM touch_customer(v);
                    PERFORM 1 FROM orders WHERE id = v;
                    RAISE NOTICE 'value % and %', v, r.qty;
                    RAISE EXCEPTION 'bad' USING ERRCODE = 'P0001', HINT = 'h';
                    RAISE;
                    GET DIAGNOSTICS v = ROW_COUNT;
                    GET STACKED DIAGNOSTICS v_text = MESSAGE_TEXT;
                    ASSERT v > 0, 'must be positive';
                    NULL;
                    OPEN c;
                    OPEN rc FOR SELECT id FROM customers;
                    OPEN rc NO SCROLL FOR EXECUTE 'SELECT id FROM audit_log' USING v;
                    FETCH c INTO v;
                    FETCH NEXT FROM c INTO r;
                    FETCH RELATIVE -2 IN c INTO r;
                    MOVE FORWARD 2 FROM c;
                    CLOSE c;
                    CALL purge_old(30);
                    EXECUTE 'DELETE FROM session_cache' ;
                    EXECUTE 'SELECT count(*) FROM orders' INTO STRICT v USING v;
                    SET LOCAL search_path = public;
                    COMMIT;
                    ROLLBACK;
                    BEGIN
                        INSERT INTO audit_log (id) VALUES (v) ON CONFLICT DO NOTHING;
                    EXCEPTION
                        WHEN unique_violation OR check_violation THEN
                            RAISE WARNING 'skipped %', SQLERRM;
                        WHEN SQLSTATE '22012' THEN
                            NULL;
                        WHEN others THEN
                            RETURN NEXT r;
                    END;
                    RETURN QUERY SELECT id FROM orders;
                    RETURN QUERY EXECUTE 'SELECT id FROM orders WHERE id = $1' USING v;
                    RETURN NEXT v;
                    RETURN v + 1;
                    RETURN;
                END;
                """;
        Routine.Block b = plpgsql(body);
        assertThat(allKinds(b)).doesNotContain("Unknown");
        assertThat(kinds(b.statements())).isEqualTo("Assign,Assign,Assign,Assign,If,Case,Case,Loop,Loop,Loop,Loop,Loop,Block,Loop,Loop,Call,Sql,Raise,Raise,Raise,"
                + "Simple,Simple,Simple,Simple,Open,Open,Open,Fetch,Fetch,Fetch,Simple,Close,Call,ExecuteImmediate,ExecuteImmediate,Simple,Simple,Simple,Block,"
                + "Return,ExecuteImmediate,Return,Return,Return");
        assertThat(sqls(b)).isEqualTo("Select@5,Select@37,Select@41,Select@56,Select@65,Insert@79,Select@88");
        // 대입: =, 첨자, 필드
        Routine.Assign plain = (Routine.Assign) b.statements().get(1);
        assertThat(plain.targets().get(0).display()).isEqualTo("V");
        assertThat(plain.value().tokens()).extracting(Token::text).containsExactly("v", "+", "1");
        assertThat(((Routine.Assign) b.statements().get(2)).targets().get(0).display()).isEqualTo("ARR");
        assertThat(((Routine.Assign) b.statements().get(3)).targets().get(0).display()).isEqualTo("R.QTY");
        // IF의 가지 셋과 ELSE
        Routine.If branch = (Routine.If) b.statements().get(4);
        assertThat(branch.branches()).hasSize(3);
        assertThat(branch.otherwise()).hasSize(1);
        // 루프의 종류와 변수
        List<Routine.Loop> loops = new ArrayList<>();
        Routine.visit(b, new Routine.Visitor() {
            @Override
            public void statement(Routine.Stmt s) {
                if (s instanceof Routine.Loop l) {
                    loops.add(l);
                }
            }
        });
        assertThat(loops).extracting(l -> l.kind() + ":" + l.variable()).containsExactly("BASIC:null", "WHILE:null", "FOR_RANGE:I", "FOR_QUERY:R", "FOR_QUERY:R",
                "FOR_CURSOR:R", "FOR_CURSOR:R", "FOR_RANGE:V");
        assertThat(loops.get(0).label()).isEqualTo("OUTER_LOOP");
        assertThat(loops.get(6).cursor()).isEqualTo("C");
        // FOR r IN EXECUTE: 동적 SQL 지점과 루프
        Routine.Block forExecute = (Routine.Block) b.statements().get(12);
        assertThat(kinds(forExecute.statements())).isEqualTo("ExecuteImmediate,Loop");
        assertThat(((Routine.ExecuteImmediate) forExecute.statements().get(0)).sql().tokens()).hasSize(1);
        // PERFORM f(..)는 호출문, 쿼리인 PERFORM은 SELECT 구문이다
        assertThat(((Routine.Call) b.statements().get(15)).name().display()).isEqualTo("TOUCH_CUSTOMER");
        assertThat(((Routine.Sql) b.statements().get(16)).statement()).isInstanceOf(Statement.Select.class);
        // EXECUTE: 식과 꼬리
        Routine.ExecuteImmediate exec = (Routine.ExecuteImmediate) b.statements().get(34);
        assertThat(exec.sql().tokens()).extracting(Token::text).containsExactly("'SELECT count(*) FROM orders'");
        assertThat(exec.rest().tokens()).extracting(Token::upper).containsExactly("INTO", "STRICT", "V", "USING", "V");
        // 커서
        Routine.Open openDynamic = (Routine.Open) b.statements().get(26);
        assertThat(openDynamic.cursor()).isEqualTo("RC");
        assertThat(openDynamic.dynamic().tokens()).extracting(Token::text).containsExactly("'SELECT id FROM audit_log'");
        assertThat(((Routine.Fetch) b.statements().get(29)).cursor()).isEqualTo("C");
        assertThat(((Routine.Fetch) b.statements().get(29)).into().refs().get(0).name().display()).isEqualTo("R");
        // 중첩 블록의 예외 처리부
        Routine.Block nested = (Routine.Block) b.statements().get(38);
        assertThat(nested.handlers()).hasSize(3);
        assertThat(nested.handlers().get(0).exceptions()).containsExactly("UNIQUE_VIOLATION", "CHECK_VIOLATION");
        // RETURN QUERY의 쿼리는 SELECT 줄에서 시작한다
        Routine.Return query = (Routine.Return) b.statements().get(39);
        assertThat(query.query().statement()).isInstanceOf(Statement.Select.class);
    }

    @Test
    @DisplayName("F-019-AC23 본문 안 구문의 위치는 넘겨받은 토큰의 줄·열(원본 파일 기준) 그대로다: 달러 인용 본문을 Lexer.tokenizeBody로 만든 토큰")
    void positionsFollowBodyTokens() {
        String file = "CREATE FUNCTION f() RETURNS int\nLANGUAGE plpgsql\nAS $fn$\nBEGIN\n    DELETE FROM t WHERE a = 1;\n    RETURN QUERY\n      SELECT 1;\nEND;\n$fn$;\n";
        Token body = Lexer.tokenize(file, Syntax.POSTGRES).stream().filter(Token::dollarQuoted).findFirst().orElseThrow();
        List<Token> tokens = Lexer.tokenizeBody(body, Syntax.POSTGRES);
        RoutineParser p = new RoutineParser(tokens.subList(0, tokens.size() - 1), RoutineLanguage.PLPGSQL);
        Routine.Block b = p.parseBody(false);
        assertThat(p.problems()).isEmpty();
        Routine.Sql delete = (Routine.Sql) b.statements().get(0);
        assertThat(delete.pos().line()).isEqualTo(5);
        assertThat(delete.pos().column()).isEqualTo(5);
        assertThat(((Routine.Return) b.statements().get(1)).query().pos().line()).isEqualTo(7);
        assertThat(((Routine.Return) b.statements().get(1)).query().pos().column()).isEqualTo(7);
    }

    @Test
    @DisplayName("F-019-AC24 PL/pgSQL 본문 SQL의 INTO [STRICT] 대상은 구문에서 떼어 대상 이름으로 남긴다: 선택 목록 뒤, FROM 뒤, 끝, RETURNING 뒤")
    void intoTargets() {
        Routine.Block b = plpgsql("""
                BEGIN
                    SELECT a, b INTO STRICT v_a, r.b FROM t WHERE c = 1;
                    SELECT a FROM t INTO v_c WHERE c = 2;
                    SELECT a FROM t WHERE c = 3 FOR UPDATE INTO v_d;
                    INSERT INTO t (a) VALUES (1) RETURNING a INTO v_e;
                    UPDATE t SET a = 2 RETURNING a, b INTO STRICT v_f, v_g;
                    WITH x AS (SELECT a FROM t) INSERT INTO u SELECT a FROM x;
                END;
                """);
        List<String> targets = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        Routine.visit(b, new Routine.Visitor() {
            @Override
            public void sql(Routine.Sql q) {
                assertThat(q.problems()).as("sql problems").isEmpty();
                targets.add(String.join("+", q.intoTargets()));
                kinds.add(q.statement().getClass().getSimpleName());
            }
        });
        assertThat(targets).containsExactly("V_A+R", "V_C", "V_D", "V_E", "V_F+V_G", "");
        assertThat(kinds).containsExactly("Select", "Select", "Select", "Insert", "Update", "WithDml");
        // 구문의 토큰(원문)은 INTO를 그대로 갖는다
        assertThat(((Routine.Sql) b.statements().get(0)).tokens()).extracting(Token::upper).contains("INTO", "STRICT");
    }

    @Test
    @DisplayName("F-019-AC23 본문 처음의 컴파일러 지시문(#variable_conflict use_column, #option dump)은 건너뛴다")
    void compilerDirectives() {
        Routine.Block b = plpgsql("#variable_conflict use_column\n#option dump\nDECLARE\n    v int;\nBEGIN\n    DELETE FROM t WHERE a = v;\nEND;");
        assertThat(b.declarations()).hasSize(1);
        assertThat(sqls(b)).isEqualTo("Delete@6");
    }

    @Test
    @DisplayName("F-019-AC29 LANGUAGE sql 본문: ;로 나뉜 구문들이 차례로 SQL 구문이 된다(끝의 ; 없이 끝나도 된다)")
    void sqlBodyStatements() {
        RoutineParser p = parser("SELECT 1;\nINSERT INTO t (a) VALUES ($1);\nDELETE FROM u WHERE a = p_a", RoutineLanguage.PLPGSQL);
        Routine.Block b = p.parseStatementList();
        assertThat(p.problems()).isEmpty();
        assertThat(sqls(b)).isEqualTo("Select@1,Insert@2,Delete@3");
        assertThat(b.declarations()).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC6 PL/SQL·SQL PL로 읽을 때는 달라지지 않는다: PERFORM·= 대입·FOR .. IN SELECT는 PL/pgSQL에서만 그렇게 읽는다")
    void otherLanguagesAreUnchanged() {
        String body = "BEGIN PERFORM f(1); v = 1; END;";
        RoutineParser plsql = parser(body, RoutineLanguage.PLSQL);
        Routine.Block b = plsql.parseBody(false);
        assertThat(kinds(b.statements())).isEqualTo("Unknown,Unknown");
        // SET v = 1은 SQL PL에서 대입이고 PL/pgSQL에서는 세션 설정이다
        RoutineParser sqlPl = parser("BEGIN SET v = 1; END", RoutineLanguage.SQLPL);
        assertThat(kinds(sqlPl.parseBody(false).statements())).isEqualTo("Assign");
        assertThat(kinds(plpgsql("BEGIN SET v = 1; END").statements())).isEqualTo("Simple");
        // PL/SQL의 TYPE 선언은 그대로 타입 선언이다
        RoutineParser typed = parser("DECLARE TYPE t_ids IS TABLE OF NUMBER; BEGIN NULL; END;", RoutineLanguage.PLSQL);
        assertThat(typed.parseBody(false).declarations().get(0)).isInstanceOf(Routine.TypeDef.class);
    }
}
