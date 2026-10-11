package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
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

/**
 * PostgreSQL 루틴(F-019-R24~R31): 루틴 헤더와 {@code language}, PL/pgSQL 본문의 구문·변수 스코프·호출·동적 SQL, 트리거, {@code DO} 블록,
 * {@code LANGUAGE sql} 본문. 파일을 분석해 그래프에 남는 것까지 본다.
 */
class PostgresRoutineTest {

    private static final String DDL_PATH = "db/postgres/01_schema.sql";
    private static final String PATH = "db/postgres/02_routines.sql";

    private static final String DDL = """
            CREATE TABLE public.customers (id bigint NOT NULL, email text, profile jsonb);
            CREATE TABLE public.orders (id bigint NOT NULL, customer_id bigint, status text, amount numeric(12,2), memo text, ordered_at date);
            CREATE TABLE public.order_items (id bigint NOT NULL, order_id bigint, qty integer, unit_price numeric(12,2));
            CREATE TABLE public.audit_log (id bigint, memo text, at timestamp with time zone);
            CREATE TABLE public.session_cache (k text NOT NULL, v bytea, expires_at timestamp with time zone);
            """;

    /** 스키마 파일과 루틴 파일을 함께 분석한다. */
    private static GraphModel analyze(String routines) {
        try (TestProject project = TestProject.create().file(DDL_PATH, DDL).file(PATH, routines)) {
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    private static List<Diagnostic> problems(GraphModel g) {
        return g.diagnostics().stream()
                .filter(d -> d.code().equals(DiagnosticCodes.PARSE_FAILED) || d.code().equals(DiagnosticCodes.UNSUPPORTED_SYNTAX))
                .collect(Collectors.toList());
    }

    /** needle이 처음 나오는 줄(1부터). */
    private static int line(String text, String needle) {
        return RoutineAnalyzerTest.line(text, needle);
    }

    private static String stmt(String routine, String text, String needle) {
        return Ids.routineStatement(routine, line(text, needle));
    }

    private static List<String> routineTargets(GraphModel g, String routineId, EdgeKind kind) {
        return RoutineAnalyzerTest.routineTargets(g, routineId, kind);
    }

    private static List<String> tables(GraphModel g) {
        return g.nodes(NodeKind.DB_TABLE).stream().map(Node::name).sorted().collect(Collectors.toList());
    }

    private static List<String> columns(GraphModel g) {
        return g.nodes(NodeKind.DB_COLUMN).stream().map(Node::id).sorted().collect(Collectors.toList());
    }

    /** DDL에 선언되지 않은 컬럼 노드(변수·매개변수가 컬럼으로 새면 여기에 나온다). */
    private static List<String> undeclaredColumns(GraphModel g) {
        return g.nodes(NodeKind.DB_COLUMN).stream().filter(n -> !n.declared()).map(Node::id).sorted().collect(Collectors.toList());
    }

    private static List<String> statementsOf(GraphModel g, String routineId) {
        return targets(g, routineId, EdgeKind.CONTAINS);
    }

    // ------------------------------------------------------------------ spec 5절 "루틴 예시"

    /** spec 5절 "루틴 예시"의 close_order 그대로. */
    private static final String CLOSE_ORDER = """
            CREATE OR REPLACE FUNCTION public.close_order(p_order_id bigint, p_memo text DEFAULT NULL)
            RETURNS integer
            LANGUAGE plpgsql
            AS $$
            <<main>>
            DECLARE
                v_cnt integer := 0;
                v_id orders.customer_id%TYPE;
                r record;
                c CURSOR FOR SELECT id FROM orders;
            BEGIN
                UPDATE orders SET status = 'SHIPPED', memo = coalesce(p_memo, memo) WHERE id = p_order_id;
                GET DIAGNOSTICS v_cnt = ROW_COUNT;
                FOR r IN SELECT order_id, qty FROM order_items WHERE order_id = p_order_id LOOP
                    INSERT INTO audit_log (id, memo) VALUES (r.order_id, 'x' || r.qty::text);
                    CONTINUE WHEN r.qty = 0;
                END LOOP;
                FOR i IN 1..3 LOOP NULL; END LOOP;
                WHILE v_cnt > 10 LOOP v_cnt := v_cnt - 1; EXIT WHEN v_cnt = 5; END LOOP;
                IF NOT FOUND THEN
                    RAISE NOTICE 'no items for %', p_order_id;
                ELSIF v_cnt > 1 THEN
                    RAISE EXCEPTION 'too many' USING ERRCODE = 'P0001';
                ELSE
                    v_cnt = 1;
                END IF;
                SELECT count(*) INTO STRICT v_cnt FROM orders WHERE customer_id = v_id;
                PERFORM public.touch_customer(p_order_id);
                EXECUTE format('DELETE FROM %I WHERE k = $1', 'session_cache') USING p_order_id::text;
                BEGIN
                    INSERT INTO session_cache (k) VALUES ('x');
                EXCEPTION WHEN unique_violation OR others THEN
                    NULL;
                END;
                RETURN v_cnt;
            EXCEPTION WHEN no_data_found THEN
                RETURN -1;
            END main;
            $$;
            """;

    private static final String TOUCH_CUSTOMER = """
            CREATE FUNCTION touch_customer(p_customer_id bigint) RETURNS void AS $body$
            BEGIN
                UPDATE customers SET profile = '{}'::jsonb WHERE id = p_customer_id;
            END;
            $body$ LANGUAGE plpgsql;
            """;

    // ------------------------------------------------------------------ F-019-AC22: 헤더와 language

    @Test
    @DisplayName("F-019-AC22 close_order의 루틴 노드: routineType·parameters(P_ORDER_ID BIGINT, P_MEMO TEXT DEFAULT NULL)·returnType·schema, language=PLPGSQL, dialect=POSTGRES")
    void headerOfCloseOrder() {
        String sql = CLOSE_ORDER + TOUCH_CUSTOMER;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        Node n = node(g, "db:routine:CLOSE_ORDER");
        assertThat(n.kind()).isEqualTo(NodeKind.DB_ROUTINE);
        assertThat(n.declared()).isTrue();
        assertThat(n.attrString("routineType")).isEqualTo("FUNCTION");
        assertThat(n.attrList("parameters")).containsExactly("P_ORDER_ID BIGINT", "P_MEMO TEXT DEFAULT NULL");
        assertThat(n.attrString("returnType")).isEqualTo("INTEGER");
        assertThat(n.attrString("schema")).isEqualTo("PUBLIC");
        assertThat(n.attrString("language")).isEqualTo("PLPGSQL");
        assertThat(n.attrString("dialect")).isEqualTo("POSTGRES");
        assertThat(n.attrList("typeRefs")).containsExactly("ORDERS.CUSTOMER_ID");
        // 같은 함수를 LANGUAGE가 본문 뒤에 오는 꼴로 써도 결과가 같다
        String languageLast = CLOSE_ORDER.replace("LANGUAGE plpgsql\nAS $$", "AS $$").replace("END main;\n$$;", "END main;\n$$ LANGUAGE plpgsql;");
        assertThat(languageLast).isNotEqualTo(CLOSE_ORDER).contains("$$ LANGUAGE plpgsql;");
        GraphModel after = analyze(languageLast + TOUCH_CUSTOMER);
        assertThat(problems(after)).isEmpty();
        Node m = node(after, "db:routine:CLOSE_ORDER");
        for (String key : List.of("routineType", "parameters", "returnType", "schema", "language", "dialect", "typeRefs", "statementCount", "dynamicSql")) {
            assertThat(m.attr(key)).as(key).isEqualTo(n.attr(key));
        }
        assertThat(((Number) m.attr("statementCount")).intValue()).isEqualTo(7);
        // LANGUAGE가 본문 뒤에 오는 다른 함수(touch_customer)
        Node t = node(g, "db:routine:TOUCH_CUSTOMER");
        assertThat(t.attrString("language")).isEqualTo("PLPGSQL");
        assertThat(t.attrString("returnType")).isEqualTo("VOID");
        assertThat(t.attrList("parameters")).containsExactly("P_CUSTOMER_ID BIGINT");
        assertThat(t.attrs()).doesNotContainKey("schema");
        assertThat(hasEdge(g, stmt("TOUCH_CUSTOMER", sql, "UPDATE customers"), EdgeKind.WRITES, "db:table:CUSTOMERS")).isTrue();
    }

    @Test
    @DisplayName("F-019-AC22 RETURNS TABLE(..)·SETOF·OUT·INOUT·VARIADIC·이름 없는 매개변수·여러 낱말 타입·= 기본값: parameters는 이름 [모드] 타입, 본문에서 OUT과 TABLE 컬럼은 변수다")
    void parameterForms() {
        String sql = """
                CREATE FUNCTION order_names(p_min bigint)
                    RETURNS TABLE (id bigint, name text)
                    LANGUAGE plpgsql STABLE
                    AS $$
                BEGIN
                    RETURN QUERY SELECT o.id, o.memo FROM orders o WHERE o.id > p_min;
                    name := 'x';
                    UPDATE audit_log SET memo = name WHERE audit_log.id = order_names.id;
                END;
                $$;
                CREATE FUNCTION stats(IN p_from date, OUT o_cnt integer, INOUT io_sum numeric(12,2) = 0, VARIADIC p_tags text[])
                    RETURNS record LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp COST 100 AS $$
                BEGIN
                    SELECT count(*), sum(amount) INTO o_cnt, io_sum FROM orders WHERE ordered_at >= p_from AND status = ANY(p_tags);
                END;
                $$;
                CREATE FUNCTION pending() RETURNS SETOF public.orders LANGUAGE plpgsql ROWS 10 PARALLEL SAFE AS $$
                BEGIN
                    RETURN QUERY SELECT * FROM orders WHERE status = 'NEW';
                END;
                $$;
                CREATE FUNCTION at_zone(timestamp with time zone, character varying(10), p_n double precision DEFAULT 1.5)
                    RETURNS timestamp with time zone LANGUAGE plpgsql IMMUTABLE STRICT AS $$
                BEGIN
                    RETURN $1;
                END;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        Node names = node(g, "db:routine:ORDER_NAMES");
        assertThat(names.attrList("parameters")).containsExactly("P_MIN BIGINT");
        assertThat(names.attrString("returnType")).isEqualTo("TABLE(ID BIGINT, NAME TEXT)");
        assertThat(names.attrString("language")).isEqualTo("PLPGSQL");
        assertThat(routineTargets(g, "db:routine:ORDER_NAMES", EdgeKind.READS)).contains("db:table:ORDERS", "db:column:ORDERS.ID", "db:column:ORDERS.MEMO");
        // RETURNS TABLE의 컬럼 name은 본문에서 변수다: AUDIT_LOG.NAME 컬럼이 생기지 않는다
        assertThat(routineTargets(g, "db:routine:ORDER_NAMES", EdgeKind.WRITES)).containsExactly("db:column:AUDIT_LOG.MEMO", "db:table:AUDIT_LOG");

        Node stats = node(g, "db:routine:STATS");
        assertThat(stats.attrList("parameters")).containsExactly("P_FROM IN DATE", "O_CNT OUT INTEGER", "IO_SUM INOUT NUMERIC(12,2) = 0", "P_TAGS VARIADIC TEXT[]");
        assertThat(stats.attrString("returnType")).isEqualTo("RECORD");
        assertThat(stats.attrString("language")).isEqualTo("PLPGSQL");
        assertThat(routineTargets(g, "db:routine:STATS", EdgeKind.READS))
                .containsExactly("db:column:ORDERS.AMOUNT", "db:column:ORDERS.ORDERED_AT", "db:column:ORDERS.STATUS", "db:table:ORDERS");

        assertThat(node(g, "db:routine:PENDING").attrString("returnType")).isEqualTo("SETOF PUBLIC.ORDERS");
        Node zone = node(g, "db:routine:AT_ZONE");
        assertThat(zone.attrList("parameters")).containsExactly("TIMESTAMP WITH TIME ZONE", "CHARACTER VARYING(10)", "P_N DOUBLE PRECISION DEFAULT 1.5");
        assertThat(zone.attrString("returnType")).isEqualTo("TIMESTAMP WITH TIME ZONE");
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
    }

    @Test
    @DisplayName("F-019-AC22 LANGUAGE c 등 그 밖의 언어: 루틴 노드만 있고 language가 없으며 진단이 없다. 본문 문자열은 분석하지 않는다")
    void otherLanguages() {
        String sql = """
                CREATE FUNCTION public.fast_hash(text) RETURNS integer
                    LANGUAGE c IMMUTABLE STRICT
                    AS '$libdir/shop_ext', 'fast_hash';
                CREATE FUNCTION py_sum(a integer, b integer) RETURNS integer AS $$
                    # DELETE FROM orders
                    return a + b
                $$ LANGUAGE plpython3u;
                CREATE FUNCTION lower_c(text) RETURNS text AS 'lower' LANGUAGE internal;
                """;
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).isEmpty();
        for (String name : List.of("FAST_HASH", "PY_SUM", "LOWER_C")) {
            Node n = node(g, "db:routine:" + name);
            assertThat(n.attrString("routineType")).as(name).isEqualTo("FUNCTION");
            assertThat(n.attrs()).as(name).doesNotContainKey("language");
            assertThat(n.attrs()).as(name).doesNotContainKey("statementCount");
            assertThat(statementsOf(g, n.id())).as(name).isEmpty();
        }
        assertThat(node(g, "db:routine:FAST_HASH").attrList("parameters")).containsExactly("TEXT");
        assertThat(node(g, "db:routine:FAST_HASH").attrString("returnType")).isEqualTo("INTEGER");
        assertThat(node(g, "db:routine:PY_SUM").attrList("parameters")).containsExactly("A INTEGER", "B INTEGER");
    }

    @Test
    @DisplayName("F-019-AC22 같은 이름의 오버로드는 루틴 노드 하나이고 본문들의 구문이 그 노드에 모인다")
    void overloads() {
        String sql = """
                CREATE FUNCTION purge(p_id bigint) RETURNS void LANGUAGE plpgsql AS $$
                BEGIN
                    DELETE FROM audit_log WHERE id = p_id;
                END;
                $$;
                CREATE FUNCTION purge(p_k text, p_force boolean) RETURNS void LANGUAGE plpgsql AS $$
                BEGIN
                    DELETE FROM session_cache WHERE k = p_k;
                END;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        assertThat(g.diagnostics()).isEmpty();
        assertThat(g.nodes(NodeKind.DB_ROUTINE)).extracting(Node::id).containsExactly("db:routine:PURGE");
        assertThat(((Number) node(g, "db:routine:PURGE").attr("statementCount")).intValue()).isEqualTo(2);
        assertThat(routineTargets(g, "db:routine:PURGE", EdgeKind.WRITES)).contains("db:table:AUDIT_LOG", "db:table:SESSION_CACHE");
        assertThat(undeclaredColumns(g)).isEmpty();
    }

    /** 루틴 노드에서 CONTAINS로 이어진 서로 다른 구문 노드의 수. */
    private static int containedStatements(GraphModel g, String routineId) {
        return (int) g.outgoing(routineId, EdgeKind.CONTAINS).stream().map(Edge::to).distinct()
                .filter(id -> node(g, id).kind() == NodeKind.SQL_STATEMENT).count();
    }

    private static int statementCount(GraphModel g, String routineId) {
        return ((Number) node(g, routineId).attr("statementCount")).intValue();
    }

    @Test
    @DisplayName("F-019-AC22 같은 함수를 다른 파일에서 다시 정의(CREATE OR REPLACE)해도 statementCount는 더해지지 않는다: 그 루틴에 실제로 속한 구문 노드의 수다")
    void redefinitionDoesNotInflateStatementCount() {
        try (TestProject project = TestProject.create()
                .file("db/postgres/V1__f.sql", """
                        CREATE TABLE t (id bigint, v text);
                        CREATE OR REPLACE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$
                        BEGIN
                          DELETE FROM t WHERE id = 1;
                        END $$;
                        """)
                .file("db/postgres/V2__f.sql", """
                        -- 같은 함수의 재정의
                        CREATE OR REPLACE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$
                        BEGIN
                          DELETE FROM t WHERE id = 2;
                        END $$;
                        """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(problems(g)).isEmpty();
            assertThat(g.nodes(NodeKind.DB_ROUTINE)).extracting(Node::id).containsExactly("db:routine:F");
            assertThat(statementsOf(g, "db:routine:F")).containsExactly("sql:stmt:F:4");
            assertThat(statementCount(g, "db:routine:F")).isEqualTo(1);
            assertThat(statementCount(g, "db:routine:F")).isEqualTo(containedStatements(g, "db:routine:F"));
            assertThat(routineTargets(g, "db:routine:F", EdgeKind.WRITES)).contains("db:table:T");
        }
    }

    @Test
    @DisplayName("F-019-AC22 재정의한 본문의 구문이 다른 줄이면 두 본문의 구문이 모두 그 루틴에 남고(어느 정의가 나중인지 추측하지 않는다) statementCount가 그 수와 같다. dynamicSql·typeRefs는 합친다")
    void redefinitionKeepsBothBodiesAndMergesAttrs() {
        try (TestProject project = TestProject.create()
                .file("db/postgres/V10__f.sql", """
                        CREATE TABLE t (id bigint, v text);
                        CREATE TABLE u (id bigint, v text);
                        CREATE OR REPLACE FUNCTION f(p_id bigint) RETURNS void LANGUAGE plpgsql AS $$
                        DECLARE
                          v_v t.v%TYPE;
                        BEGIN
                          DELETE FROM t WHERE id = p_id;
                          EXECUTE 'DELETE FROM u WHERE id = ' || p_id;
                        END $$;
                        """)
                .file("db/postgres/V2__f.sql", """
                        CREATE OR REPLACE FUNCTION f(p_id bigint) RETURNS void LANGUAGE plpgsql AS $$
                        DECLARE
                          v_row u%ROWTYPE;
                        BEGIN
                          UPDATE t SET v = 'x' WHERE id = p_id;
                          DELETE FROM u WHERE id = p_id;
                        END $$;
                        """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(problems(g)).isEmpty();
            // V10의 7·8줄과 V2의 5·6줄: 줄이 모두 달라 구문 노드가 넷이다
            assertThat(statementsOf(g, "db:routine:F")).containsExactlyInAnyOrder("sql:stmt:F:5", "sql:stmt:F:6", "sql:stmt:F:7", "sql:stmt:F:8");
            assertThat(statementCount(g, "db:routine:F")).isEqualTo(4);
            assertThat(statementCount(g, "db:routine:F")).isEqualTo(containedStatements(g, "db:routine:F"));
            Node f = node(g, "db:routine:F");
            assertThat(f.attr("dynamicSql")).isEqualTo(true);
            assertThat(f.attrList("typeRefs")).containsExactlyInAnyOrder("T.V", "U");
        }
    }

    @Test
    @DisplayName("F-019-AC22 F-019-AC24 오버로드든 재정의든 루틴의 statementCount는 그래프의 구문 노드 수와 같다(close_order, 한 파일의 오버로드, LANGUAGE sql 본문)")
    void statementCountMatchesGraph() {
        GraphModel g = analyze(CLOSE_ORDER + TOUCH_CUSTOMER + """
                CREATE FUNCTION purge(p_id bigint) RETURNS void LANGUAGE plpgsql AS $$
                BEGIN
                    DELETE FROM audit_log WHERE id = p_id;
                END;
                $$;
                CREATE FUNCTION purge(p_k text, p_force boolean) RETURNS void LANGUAGE plpgsql AS $$
                BEGIN
                    DELETE FROM session_cache WHERE k = p_k;
                    DELETE FROM audit_log WHERE memo = p_k;
                END;
                $$;
                CREATE FUNCTION cnt(bigint) RETURNS bigint LANGUAGE sql AS 'SELECT count(*) FROM orders WHERE customer_id = $1';
                """);
        assertThat(statementCount(g, "db:routine:PURGE")).isEqualTo(3);
        for (Node routine : g.nodes(NodeKind.DB_ROUTINE)) {
            if (routine.attr("statementCount") != null) {
                assertThat(statementCount(g, routine.id())).as(routine.id()).isEqualTo(containedStatements(g, routine.id()));
            }
        }
        assertThat(statementCount(g, "db:routine:CLOSE_ORDER")).isPositive();
        assertThat(statementCount(g, "db:routine:CNT")).isEqualTo(1);
    }

    @Test
    @DisplayName("F-019-AC22 F-019-AC17 따옴표로 감싼 소문자 루틴 이름·매개변수는 따옴표 없는 이름과 같다: 루틴 노드 하나, 매개변수가 컬럼이 되지 않고, 호출이 그 루틴에 붙는다")
    void quotedLowercaseRoutineNamesAndParameters() {
        String sql = """
                CREATE FUNCTION public."purge_one"("p_id" bigint) RETURNS void LANGUAGE plpgsql AS $$
                BEGIN
                    DELETE FROM "audit_log" WHERE "id" = p_id;
                    DELETE FROM session_cache WHERE k = "p_id"::text;
                END;
                $$;
                CREATE PROCEDURE purge_all() LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM "purge_one"(1);
                    PERFORM public.purge_one(2);
                END;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).isEmpty();
        assertThat(g.nodes(NodeKind.DB_ROUTINE)).extracting(Node::id).containsExactlyInAnyOrder("db:routine:PURGE_ONE", "db:routine:PURGE_ALL");
        assertThat(node(g, "db:routine:PURGE_ONE").attrList("parameters")).containsExactly("P_ID BIGINT");
        assertThat(routineTargets(g, "db:routine:PURGE_ONE", EdgeKind.WRITES)).contains("db:table:AUDIT_LOG", "db:table:SESSION_CACHE");
        assertThat(targets(g, "db:routine:PURGE_ALL", EdgeKind.CALLS)).containsExactly("db:routine:PURGE_ONE");
        assertThat(tables(g)).doesNotContain("audit_log");
        assertThat(undeclaredColumns(g)).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC26 F-019-AC17 동적 SQL의 format('%I', ..)·quote_ident가 따옴표로 감싼 소문자 이름(\"2fa_tokens\")도 따옴표 없는 정의와 같은 테이블이다. 대문자가 든 이름은 원문 그대로다")
    void quotedIdentifiersFromFormat() {
        String sql = """
                CREATE TABLE "2fa_tokens" (id bigint, k text);
                CREATE PROCEDURE dyn_quoted(p_k text) LANGUAGE plpgsql AS $$
                BEGIN
                    EXECUTE format('DELETE FROM %I WHERE k = $1', '2fa_tokens') USING p_k;
                    EXECUTE 'DELETE FROM ' || quote_ident('audit log') || ' WHERE id = 1';
                    EXECUTE format('DELETE FROM %I', 'AuditLog');
                END;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        assertThat(node(g, "db:table:2FA_TOKENS").declared()).isTrue();
        assertThat(routineTargets(g, "db:routine:DYN_QUOTED", EdgeKind.WRITES)).contains("db:table:2FA_TOKENS", "db:table:AUDIT LOG", "db:table:AuditLog");
        assertThat(tables(g)).doesNotContain("2fa_tokens", "audit log");
    }

    // ------------------------------------------------------------------ F-019-AC23: 본문 문장과 위치

    @Test
    @DisplayName("F-019-AC23 close_order 본문: PARSE_FAILED·UNSUPPORTED_SYNTAX가 없고 본문 구문의 위치가 원본 파일의 줄·열이다")
    void bodyPositionsAreFilePositions() {
        String file = "-- 주문 마감\n\n" + CLOSE_ORDER + TOUCH_CUSTOMER;
        GraphModel g = analyze(file);
        assertThat(problems(g)).isEmpty();
        String update = stmt("CLOSE_ORDER", file, "UPDATE orders SET status");
        assertThat(update).isEqualTo("sql:stmt:CLOSE_ORDER:14");
        Node n = node(g, update);
        assertThat(n.location().file()).isEqualTo(PATH);
        assertThat(n.location().startLine()).isEqualTo(14);
        assertThat(n.location().startColumn()).isEqualTo(5);
        assertThat(n.attrString("sql")).isEqualTo("UPDATE orders SET status = 'SHIPPED', memo = coalesce(p_memo, memo) WHERE id = p_order_id");
        assertThat(n.attrString("routine")).isEqualTo("db:routine:CLOSE_ORDER");
        assertThat(n.attrString("dialect")).isEqualTo("POSTGRES");
        // 엣지의 위치도 원본 파일 기준이다
        Edge write = edge(g, update, EdgeKind.WRITES, "db:table:ORDERS");
        assertThat(write.location().startLine()).isEqualTo(14);
        assertThat(write.location().startColumn()).isEqualTo(12);
        // 루틴 노드의 위치는 CREATE 줄이다
        assertThat(node(g, "db:routine:CLOSE_ORDER").location().startLine()).isEqualTo(3);
    }

    @Test
    @DisplayName("F-019-AC23 F-019-R25의 문장을 모두 담은 본문: PARSE_FAILED·UNSUPPORTED_SYNTAX 없이 분석되고, 제어 구조 안쪽·커서·RETURN QUERY의 쿼리가 모두 구문 노드가 된다")
    void everyStatementKind() {
        String sql = """
                CREATE FUNCTION every_kind(p_id bigint, p_ids bigint[]) RETURNS SETOF orders LANGUAGE plpgsql AS $$
                <<main>>
                DECLARE
                    v integer := 0;
                    v_text text;
                    arr integer[] := ARRAY[1, 2];
                    r record;
                    c CURSOR (p_min bigint) FOR SELECT id FROM orders WHERE id > p_min;
                    rc refcursor;
                BEGIN
                    v := 1;
                    v = v + 1;
                    arr[1] := 2;
                    IF v > 1 THEN
                        UPDATE orders SET memo = 'if' WHERE id = p_id;
                    ELSIF v = 0 THEN
                        NULL;
                    ELSE
                        DELETE FROM audit_log WHERE id = v;
                    END IF;
                    CASE v
                        WHEN 1, 2 THEN INSERT INTO audit_log (id, memo) VALUES (v, 'case');
                        ELSE NULL;
                    END CASE;
                    <<outer_loop>>
                    LOOP
                        v := v + 1;
                        EXIT outer_loop WHEN v > 10;
                        CONTINUE WHEN v = 5;
                    END LOOP outer_loop;
                    WHILE v < 20 LOOP
                        v := v + 1;
                    END LOOP;
                    FOR i IN REVERSE 10..1 BY 2 LOOP
                        UPDATE order_items SET qty = i WHERE id = v;
                    END LOOP;
                    FOR r IN SELECT c.id, c.email FROM customers c WHERE c.id = ANY(p_ids) LOOP
                        RAISE NOTICE 'customer % %', r.id, r.email;
                    END LOOP;
                    FOR r IN c(10) LOOP
                        NULL;
                    END LOOP;
                    FOREACH v IN ARRAY arr LOOP
                        DELETE FROM session_cache WHERE k = v::text;
                    END LOOP;
                    GET DIAGNOSTICS v = ROW_COUNT;
                    ASSERT v >= 0, 'row count';
                    OPEN c(1);
                    FETCH c INTO v;
                    MOVE NEXT FROM c;
                    CLOSE c;
                    OPEN rc FOR SELECT o.id FROM orders o WHERE o.customer_id = p_id;
                    FETCH NEXT FROM rc INTO r;
                    CLOSE rc;
                    OPEN rc FOR EXECUTE 'SELECT k FROM session_cache WHERE k = $1' USING v_text;
                    CLOSE rc;
                    EXECUTE 'SELECT max(id) FROM audit_log' INTO STRICT v;
                    SET LOCAL statement_timeout = 0;
                    BEGIN
                        UPDATE customers SET email = lower(email) WHERE id = p_id RETURNING email INTO v_text;
                    EXCEPTION
                        WHEN unique_violation OR check_violation THEN
                            RAISE WARNING 'skipped %: %', p_id, SQLERRM;
                        WHEN SQLSTATE '22012' THEN
                            RAISE;
                        WHEN others THEN
                            GET STACKED DIAGNOSTICS v_text = MESSAGE_TEXT;
                    END;
                    COMMIT;
                    RETURN QUERY
                        SELECT * FROM orders WHERE id = p_id;
                    RETURN QUERY EXECUTE 'SELECT * FROM orders WHERE amount > $1' USING v;
                    RETURN NEXT r;
                    RETURN;
                EXCEPTION WHEN no_data_found THEN
                    ROLLBACK;
                    RETURN;
                END main;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).isEmpty();
        String routine = "db:routine:EVERY_KIND";
        assertThat(node(g, routine).attrString("returnType")).isEqualTo("SETOF ORDERS");
        assertThat(statementsOf(g, routine)).containsExactlyInAnyOrder(
                stmt("EVERY_KIND", sql, "c CURSOR (p_min bigint) FOR SELECT"),
                stmt("EVERY_KIND", sql, "UPDATE orders SET memo = 'if'"),
                stmt("EVERY_KIND", sql, "DELETE FROM audit_log WHERE id = v"),
                stmt("EVERY_KIND", sql, "INSERT INTO audit_log (id, memo) VALUES (v, 'case')"),
                stmt("EVERY_KIND", sql, "UPDATE order_items SET qty = i"),
                stmt("EVERY_KIND", sql, "FOR r IN SELECT c.id, c.email"),
                stmt("EVERY_KIND", sql, "DELETE FROM session_cache WHERE k = v::text"),
                stmt("EVERY_KIND", sql, "OPEN rc FOR SELECT o.id"),
                stmt("EVERY_KIND", sql, "OPEN rc FOR EXECUTE"),
                stmt("EVERY_KIND", sql, "EXECUTE 'SELECT max(id) FROM audit_log'"),
                stmt("EVERY_KIND", sql, "UPDATE customers SET email = lower(email)"),
                stmt("EVERY_KIND", sql, "SELECT * FROM orders WHERE id = p_id"),
                stmt("EVERY_KIND", sql, "RETURN QUERY EXECUTE"));
        assertThat(routineTargets(g, routine, EdgeKind.WRITES)).containsExactly("db:column:AUDIT_LOG.ID", "db:column:AUDIT_LOG.MEMO",
                "db:column:CUSTOMERS.EMAIL", "db:column:ORDERS.MEMO", "db:column:ORDER_ITEMS.QTY", "db:table:AUDIT_LOG", "db:table:CUSTOMERS",
                "db:table:ORDERS", "db:table:ORDER_ITEMS", "db:table:SESSION_CACHE");
        assertThat(routineTargets(g, routine, EdgeKind.READS)).contains("db:table:AUDIT_LOG", "db:table:CUSTOMERS", "db:table:ORDERS",
                "db:table:SESSION_CACHE", "db:column:ORDERS.AMOUNT", "db:column:ORDERS.CUSTOMER_ID", "db:column:CUSTOMERS.EMAIL");
        assertThat(node(g, stmt("EVERY_KIND", sql, "OPEN rc FOR SELECT o.id")).attrString("cursor")).isEqualTo("RC");
        assertThat(node(g, stmt("EVERY_KIND", sql, "OPEN rc FOR EXECUTE")).attrString("dynamicKind")).isEqualTo("OPEN FOR");
        assertThat(node(g, routine).attr("dynamicSql")).isEqualTo(true);
        assertThat(targets(g, routine, EdgeKind.CALLS)).isEmpty();
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
    }

    // ------------------------------------------------------------------ F-019-AC24: 본문의 SQL 구문과 변수 스코프

    @Test
    @DisplayName("F-019-AC24 close_order 본문의 SQL 구문이 문장마다 구문 노드가 되고 읽기·쓰기가 맞다. 매개변수·변수·레코드·FOUND가 컬럼·테이블이 되지 않는다")
    void statementsAndScopeOfCloseOrder() {
        GraphModel g = analyze(CLOSE_ORDER + TOUCH_CUSTOMER);
        assertThat(problems(g)).isEmpty();
        String routine = "db:routine:CLOSE_ORDER";
        String cursor = stmt("CLOSE_ORDER", CLOSE_ORDER, "c CURSOR FOR SELECT id FROM orders");
        String update = stmt("CLOSE_ORDER", CLOSE_ORDER, "UPDATE orders SET status");
        String forQuery = stmt("CLOSE_ORDER", CLOSE_ORDER, "FOR r IN SELECT order_id");
        String insertAudit = stmt("CLOSE_ORDER", CLOSE_ORDER, "INSERT INTO audit_log");
        String selectInto = stmt("CLOSE_ORDER", CLOSE_ORDER, "SELECT count(*) INTO STRICT");
        String execute = stmt("CLOSE_ORDER", CLOSE_ORDER, "EXECUTE format(");
        String insertCache = stmt("CLOSE_ORDER", CLOSE_ORDER, "INSERT INTO session_cache");
        assertThat(statementsOf(g, routine)).containsExactlyInAnyOrder(cursor, update, forQuery, insertAudit, selectInto, execute, insertCache);

        assertThat(node(g, update).attrString("statementType")).isEqualTo("UPDATE");
        assertThat(targets(g, update, EdgeKind.WRITES)).containsExactly("db:column:ORDERS.MEMO", "db:column:ORDERS.STATUS", "db:table:ORDERS");
        assertThat(targets(g, update, EdgeKind.READS)).containsExactly("db:column:ORDERS.ID", "db:column:ORDERS.MEMO");
        assertThat(edge(g, update, EdgeKind.WRITES, "db:table:ORDERS").attrString("op")).isEqualTo("UPDATE");

        assertThat(node(g, forQuery).attrString("statementType")).isEqualTo("SELECT");
        assertThat(targets(g, forQuery, EdgeKind.READS))
                .containsExactly("db:column:ORDER_ITEMS.ORDER_ID", "db:column:ORDER_ITEMS.QTY", "db:table:ORDER_ITEMS");

        assertThat(node(g, insertAudit).attrString("statementType")).isEqualTo("INSERT");
        assertThat(targets(g, insertAudit, EdgeKind.WRITES)).containsExactly("db:column:AUDIT_LOG.ID", "db:column:AUDIT_LOG.MEMO", "db:table:AUDIT_LOG");
        assertThat(targets(g, insertAudit, EdgeKind.READS)).isEmpty();

        assertThat(node(g, selectInto).attrString("statementType")).isEqualTo("SELECT");
        assertThat(targets(g, selectInto, EdgeKind.READS)).containsExactly("db:column:ORDERS.CUSTOMER_ID", "db:table:ORDERS");

        assertThat(node(g, cursor).attrString("cursor")).isEqualTo("C");
        assertThat(targets(g, cursor, EdgeKind.READS)).containsExactly("db:column:ORDERS.ID", "db:table:ORDERS");
        assertThat(targets(g, insertCache, EdgeKind.WRITES)).containsExactly("db:column:SESSION_CACHE.K", "db:table:SESSION_CACHE");

        // 변수·매개변수·레코드·레이블·FOUND·타입 이름에서 나온 컬럼·테이블이 없다
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
        assertThat(((Number) node(g, routine).attr("statementCount")).intValue()).isEqualTo(7);
    }

    @Test
    @DisplayName("F-019-AC24 INTO가 FROM 뒤·끝에 오는 SELECT, RETURNING .. INTO, 레코드 필드, %ROWTYPE, alias FOR $1, 중첩 DECLARE: INTO 대상과 변수가 컬럼·테이블이 되지 않는다")
    void intoTargetsAndVariables() {
        String sql = """
                CREATE FUNCTION f_into(integer, p_key text) RETURNS integer LANGUAGE plpgsql AS $$
                DECLARE
                    a_id ALIAS FOR $1;
                    v_row orders%ROWTYPE;
                    v_new_id bigint;
                    v_memo text = 'm';
                    c_limit CONSTANT integer NOT NULL DEFAULT 10;
                BEGIN
                    SELECT * FROM orders WHERE id = a_id INTO v_row;
                    SELECT o.id, o.memo FROM orders o WHERE o.id = $1 LIMIT c_limit INTO STRICT v_new_id, v_memo;
                    INSERT INTO audit_log (id, memo) VALUES (v_row.id, v_memo) RETURNING id INTO v_new_id;
                    UPDATE orders SET memo = v_memo WHERE id = v_row.id RETURNING amount, status INTO STRICT v_row.amount, v_row.status;
                    DELETE FROM session_cache WHERE k = p_key RETURNING k INTO undeclared_target;
                    DECLARE
                        v_inner integer;
                    BEGIN
                        SELECT qty INTO v_inner FROM order_items WHERE order_id = v_row.id AND unit_price > v_inner;
                    END;
                    RETURN v_new_id;
                END;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
        String routine = "db:routine:F_INTO";
        assertThat(statementsOf(g, routine)).hasSize(6);
        assertThat(routineTargets(g, routine, EdgeKind.WRITES)).containsExactly("db:column:AUDIT_LOG.ID", "db:column:AUDIT_LOG.MEMO",
                "db:column:ORDERS.MEMO", "db:table:AUDIT_LOG", "db:table:ORDERS", "db:table:SESSION_CACHE");
        assertThat(targets(g, stmt("F_INTO", sql, "SELECT qty INTO v_inner"), EdgeKind.READS)).containsExactly("db:column:ORDER_ITEMS.ORDER_ID",
                "db:column:ORDER_ITEMS.QTY", "db:column:ORDER_ITEMS.UNIT_PRICE", "db:table:ORDER_ITEMS");
        assertThat(node(g, routine).attrList("typeRefs")).containsExactly("ORDERS");
        assertThat(node(g, routine).attrList("parameters")).containsExactly("INTEGER", "P_KEY TEXT");
    }

    @Test
    @DisplayName("F-019-AC24 본문의 CREATE TEMP TABLE .. AS 쿼리, INSERT .. ON CONFLICT, 대입식·조건식의 서브쿼리, SELECT INTO 대상이 앞에 오는 꼴, 키워드 이름의 변수도 구문과 변수로 읽는다")
    void otherBodyStatements() {
        String sql = """
                CREATE FUNCTION misc(p_id bigint) RETURNS integer LANGUAGE plpgsql AS $$
                DECLARE
                    values text[];
                    lock boolean := false;
                    v_count integer;
                BEGIN
                    CREATE TEMP TABLE tmp_orders ON COMMIT DROP AS SELECT o.id FROM orders o WHERE o.customer_id = p_id;
                    SELECT INTO v_count count(*) FROM order_items i WHERE i.order_id = p_id;
                    v_count := (SELECT count(*) FROM customers c WHERE c.id = p_id) + v_count;
                    IF EXISTS (SELECT 1 FROM audit_log WHERE id = p_id) AND NOT lock THEN
                        INSERT INTO session_cache (k, v) VALUES ('a', NULL) ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v;
                    END IF;
                    values := array_append(values, 'x');
                    lock = true;
                    DROP TABLE tmp_orders;
                    RETURN v_count;
                END;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).isEmpty();
        String routine = "db:routine:MISC";
        String ctas = stmt("MISC", sql, "CREATE TEMP TABLE");
        assertThat(targets(g, ctas, EdgeKind.READS)).containsExactly("db:column:ORDERS.CUSTOMER_ID", "db:column:ORDERS.ID", "db:table:ORDERS");
        assertThat(targets(g, ctas, EdgeKind.WRITES)).containsExactly("db:table:TMP_ORDERS");
        assertThat(node(g, "db:table:TMP_ORDERS").declared()).isFalse();
        assertThat(targets(g, stmt("MISC", sql, "SELECT INTO v_count"), EdgeKind.READS)).containsExactly("db:column:ORDER_ITEMS.ORDER_ID", "db:table:ORDER_ITEMS");
        assertThat(targets(g, stmt("MISC", sql, "v_count := (SELECT"), EdgeKind.READS)).containsExactly("db:column:CUSTOMERS.ID", "db:table:CUSTOMERS");
        assertThat(targets(g, stmt("MISC", sql, "IF EXISTS (SELECT"), EdgeKind.READS)).containsExactly("db:column:AUDIT_LOG.ID", "db:table:AUDIT_LOG");
        assertThat(targets(g, stmt("MISC", sql, "INSERT INTO session_cache"), EdgeKind.WRITES))
                .containsExactly("db:column:SESSION_CACHE.K", "db:column:SESSION_CACHE.V", "db:table:SESSION_CACHE");
        assertThat(statementsOf(g, routine)).hasSize(5);
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE", "TMP_ORDERS");
    }

    @Test
    @DisplayName("F-019-AC36 PL/pgSQL·LANGUAGE sql 본문의 COPY (쿼리) TO ..는 루틴에 속한 SELECT 구문이다: 쿼리의 읽기가 기록되고 매개변수는 컬럼이 아니다. 본문의 COPY 테이블 TO|FROM '경로'는 전처럼 조용히 넘긴다")
    void copyQueryToInBody() {
        String sql = """
                CREATE FUNCTION export_audit(p_customer bigint) RETURNS void LANGUAGE plpgsql AS $$
                BEGIN
                    COPY (SELECT a.id, a.memo FROM audit_log a JOIN orders o ON o.id = a.id WHERE o.customer_id = p_customer) TO '/tmp/audit.csv' WITH (FORMAT csv);
                    COPY session_cache TO '/tmp/cache.csv';
                    COPY orders (id, status) FROM '/tmp/orders.csv';
                END;
                $$;
                CREATE PROCEDURE export_cache() LANGUAGE sql AS $$
                    COPY (SELECT k FROM session_cache WHERE expires_at IS NULL) TO '/tmp/keys.csv';
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).isEmpty();
        String routine = "db:routine:EXPORT_AUDIT";
        String copy = stmt("EXPORT_AUDIT", sql, "COPY (SELECT a.id");
        assertThat(statementsOf(g, routine)).containsExactly(copy);
        assertThat(node(g, copy).attrString("statementType")).isEqualTo("SELECT");
        assertThat(targets(g, copy, EdgeKind.READS)).containsExactly("db:column:AUDIT_LOG.ID", "db:column:AUDIT_LOG.MEMO",
                "db:column:ORDERS.CUSTOMER_ID", "db:column:ORDERS.ID", "db:table:AUDIT_LOG", "db:table:ORDERS");
        assertThat(targets(g, copy, EdgeKind.WRITES)).isEmpty();
        assertThat(routineTargets(g, routine, EdgeKind.CALLS)).isEmpty();
        assertThat(node(g, routine).attr("statementCount")).isEqualTo(1);
        String sqlBody = stmt("EXPORT_CACHE", sql, "COPY (SELECT k");
        assertThat(statementsOf(g, "db:routine:EXPORT_CACHE")).containsExactly(sqlBody);
        assertThat(targets(g, sqlBody, EdgeKind.READS)).containsExactly("db:column:SESSION_CACHE.EXPIRES_AT", "db:column:SESSION_CACHE.K", "db:table:SESSION_CACHE");
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
    }

    @Test
    @DisplayName("F-019-AC22 F-019-AC24 작은따옴표 본문과 옛 표기 LANGUAGE 'plpgsql': 안의 ''를 풀어 읽고 구문의 줄은 원본 파일 기준이다. NEW.컬럼에 대입해도 컬럼이 생기지 않는다")
    void singleQuotedPlpgsqlBody() {
        String sql = """
                CREATE FUNCTION old_style(integer, text) RETURNS integer AS '
                DECLARE
                    a ALIAS FOR $1;
                    b ALIAS FOR $2;
                BEGIN
                    UPDATE audit_log SET memo = b WHERE id = a;
                    IF NOT FOUND THEN
                        INSERT INTO audit_log (id, memo) VALUES (a, ''it''''s'');
                    END IF;
                    RETURN 1;
                END;
                ' LANGUAGE 'plpgsql';
                CREATE FUNCTION set_updated() RETURNS trigger AS $$
                BEGIN
                    NEW.ordered_at := now();
                    NEW.memo = upper(NEW.memo);
                    IF TG_ARGV[0] = 'x' AND TG_TABLE_NAME = 'orders' THEN
                        DELETE FROM session_cache WHERE k = OLD.memo;
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql;
                """;
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).isEmpty();
        Node old = node(g, "db:routine:OLD_STYLE");
        assertThat(old.attrString("language")).isEqualTo("PLPGSQL");
        assertThat(old.attrList("parameters")).containsExactly("INTEGER", "TEXT");
        String update = stmt("OLD_STYLE", sql, "UPDATE audit_log SET memo = b");
        String insert = stmt("OLD_STYLE", sql, "INSERT INTO audit_log (id, memo) VALUES (a,");
        assertThat(statementsOf(g, old.id())).containsExactlyInAnyOrder(update, insert);
        assertThat(node(g, update).location().startLine()).isEqualTo(6);
        assertThat(node(g, update).location().startColumn()).isEqualTo(5);
        assertThat(node(g, insert).attrString("sql")).isEqualTo("INSERT INTO audit_log (id, memo) VALUES (a, 'it''s')");
        assertThat(targets(g, update, EdgeKind.WRITES)).containsExactly("db:column:AUDIT_LOG.MEMO", "db:table:AUDIT_LOG");
        assertThat(targets(g, update, EdgeKind.READS)).containsExactly("db:column:AUDIT_LOG.ID");
        String delete = stmt("SET_UPDATED", sql, "DELETE FROM session_cache WHERE k = OLD.memo");
        assertThat(statementsOf(g, "db:routine:SET_UPDATED")).containsExactly(delete);
        assertThat(targets(g, delete, EdgeKind.READS)).containsExactly("db:column:SESSION_CACHE.K");
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
    }

    @Test
    @DisplayName("F-019-AC15 PL/pgSQL 식의 nextval('시퀀스')·PERFORM setval('시퀀스', ..): 리터럴 인자면 시퀀스 참조로 남고(스키마·::regclass 제거) 변수 인자면 남지 않는다")
    void sequenceFunctionsInBody() {
        String sql = """
                CREATE SEQUENCE public.orders_id_seq;
                CREATE FUNCTION next_ids(p_name text) RETURNS bigint LANGUAGE plpgsql AS $$
                DECLARE
                    v_id bigint;
                BEGIN
                    v_id := nextval('public.orders_id_seq'::regclass);
                    PERFORM setval('orders_id_seq', v_id + 10, false);
                    v_id := nextval(p_name);
                    INSERT INTO orders (id) VALUES (nextval('orders_id_seq'));
                    RETURN v_id;
                END;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).isEmpty();
        String assign = stmt("NEXT_IDS", sql, "v_id := nextval('public.orders_id_seq'");
        String perform = stmt("NEXT_IDS", sql, "PERFORM setval(");
        String insert = stmt("NEXT_IDS", sql, "INSERT INTO orders (id)");
        assertThat(statementsOf(g, "db:routine:NEXT_IDS")).containsExactlyInAnyOrder(assign, perform, insert);
        for (String id : List.of(assign, perform, insert)) {
            assertThat(targets(g, id, EdgeKind.READS)).as(id).containsExactly("db:sequence:ORDERS_ID_SEQ");
            assertThat(edge(g, id, EdgeKind.READS, "db:sequence:ORDERS_ID_SEQ").attr("sequence")).as(id).isEqualTo(true);
        }
        assertThat(node(g, assign).attr("sequence")).isEqualTo(true);
        assertThat(node(g, assign).attrString("statementType")).isEqualTo("OTHER");
        assertThat(node(g, "db:sequence:ORDERS_ID_SEQ").declared()).isTrue();
        assertThat(g.nodes(NodeKind.DB_ROUTINE)).extracting(Node::id).containsExactly("db:routine:NEXT_IDS");
        assertThat(g.nodes(NodeKind.DB_SEQUENCE)).extracting(Node::id).containsExactly("db:sequence:ORDERS_ID_SEQ");
    }

    // ------------------------------------------------------------------ F-019-AC25: 호출

    @Test
    @DisplayName("F-019-AC25 PERFORM f(..), v := f(..), SQL 안의 f(..), CALL p(..)는 루틴 호출이고 내장 함수(pg_sleep, format, now, coalesce)는 아니다. 스키마는 뗀다")
    void calls() {
        String sql = """
                CREATE FUNCTION calc_total(p_id bigint) RETURNS numeric LANGUAGE sql AS $$ SELECT sum(amount) FROM orders WHERE id = p_id $$;
                CREATE PROCEDURE purge_old(p_days integer) LANGUAGE plpgsql AS $$ BEGIN DELETE FROM audit_log; END $$;
                CREATE FUNCTION caller(p_order_id bigint, p_id bigint) RETURNS void LANGUAGE plpgsql AS $$
                DECLARE
                    v numeric;
                    v_text text;
                BEGIN
                    PERFORM public.touch_customer(p_order_id);
                    v := calc_total(p_id);
                    SELECT calc_total(o.id) INTO v FROM orders o;
                    CALL purge_old(30);
                    CALL public.purge_old(60);
                    PERFORM pg_sleep(1);
                    PERFORM pg_catalog.set_config('search_path', '', true);
                    PERFORM pg_notify('ch', 'x'), nextval('orders_id_seq');
                    PERFORM setval('orders_id_seq', 10);
                    v_text := format('%s-%s', coalesce(v_text, 'a'), now()::text) || upper(trim(v_text)) || unknown_fn(1);
                    PERFORM 1 FROM orders o WHERE o.id = p_id AND o.amount > v;
                    PERFORM undefined_proc(p_id);
                    IF public.calc_total(p_id) > 0 AND length(v_text) > 0 THEN
                        NULL;
                    END IF;
                END;
                $$;
                """;
        GraphModel g = analyze(sql + TOUCH_CUSTOMER);
        assertThat(problems(g)).isEmpty();
        String caller = "db:routine:CALLER";
        // 루틴에서 나가는 호출: PERFORM f(..)·CALL은 호출문, 대입식·조건식의 f(..)는 식 안의 호출이다
        assertThat(targets(g, caller, EdgeKind.CALLS))
                .containsExactly("db:routine:CALC_TOTAL", "db:routine:PURGE_OLD", "db:routine:TOUCH_CUSTOMER", "db:routine:UNDEFINED_PROC");
        assertThat(edge(g, caller, EdgeKind.CALLS, "db:routine:TOUCH_CUSTOMER").attrString("callType")).isEqualTo("procedure");
        assertThat(edge(g, caller, EdgeKind.CALLS, "db:routine:TOUCH_CUSTOMER").location().startLine()).isEqualTo(line(sql, "PERFORM public.touch_customer"));
        assertThat(edge(g, caller, EdgeKind.CALLS, "db:routine:PURGE_OLD").attrString("callType")).isEqualTo("procedure");
        assertThat(g.edges(caller, EdgeKind.CALLS, "db:routine:PURGE_OLD")).hasSize(2);
        assertThat(g.edges(caller, EdgeKind.CALLS, "db:routine:CALC_TOTAL")).extracting(e -> e.attrString("callType")).containsOnly("function").hasSize(2);
        // 정의가 없는 PERFORM 대상은 호출문 꼴이라 참조(declared=false)로 남는다. 식 안의 미정의 함수(unknown_fn)는 남기지 않는다
        assertThat(node(g, "db:routine:UNDEFINED_PROC").declared()).isFalse();
        // SQL 구문 안의 호출은 그 구문에서 나간다
        String select = stmt("CALLER", sql, "SELECT calc_total(o.id) INTO v");
        assertThat(targets(g, select, EdgeKind.CALLS)).containsExactly("db:routine:CALC_TOTAL");
        // 쿼리인 PERFORM은 SELECT 구문이다
        String perform = stmt("CALLER", sql, "PERFORM 1 FROM orders");
        assertThat(node(g, perform).attrString("statementType")).isEqualTo("SELECT");
        assertThat(targets(g, perform, EdgeKind.READS)).containsExactly("db:column:ORDERS.AMOUNT", "db:column:ORDERS.ID", "db:table:ORDERS");
        // 내장 함수와 스키마 이름이 루틴이 되지 않는다
        assertThat(g.nodes(NodeKind.DB_ROUTINE)).extracting(Node::id).containsExactlyInAnyOrder("db:routine:CALC_TOTAL", "db:routine:PURGE_OLD",
                "db:routine:CALLER", "db:routine:TOUCH_CUSTOMER", "db:routine:UNDEFINED_PROC");
        assertThat(undeclaredColumns(g)).isEmpty();
    }

    // ------------------------------------------------------------------ F-019-AC26: 동적 SQL

    @Test
    @DisplayName("F-019-AC26 EXECUTE: 문자열 리터럴·연결·format의 %I·%s·%L 자리에 리터럴 인자가 오면 풀어서 분석하고, 풀 수 없으면 미해결 엣지와 DYNAMIC_SQL을 남긴다")
    void dynamicSql() {
        String sql = """
                CREATE PROCEDURE dyn(p_k text, p_table text) LANGUAGE plpgsql AS $$
                DECLARE
                    v_cnt integer;
                    v_sql text := 'UPDATE orders SET memo = ' || quote_literal('x') || ' WHERE id = 1';
                    r record;
                BEGIN
                    EXECUTE 'DELETE FROM session_cache WHERE k = $1' USING p_k;
                    EXECUTE format('DELETE FROM %I WHERE k = $1', 'session_cache')
                        USING p_k;
                    EXECUTE format('INSERT INTO %I.%I (id, memo) VALUES (%L, %s)', 'public', 'audit_log', '7', 'now()');
                    EXECUTE 'SELECT count(*) FROM ' || 'customers' INTO STRICT v_cnt;
                    EXECUTE v_sql;
                    EXECUTE format('DELETE FROM %I', p_table);
                    EXECUTE 'SELECT 1 FROM ' || quote_ident(p_table) || ' WHERE k = $1' INTO v_cnt USING p_k;
                    FOR r IN EXECUTE 'SELECT id FROM order_items WHERE qty > $1' USING v_cnt LOOP
                        NULL;
                    END LOOP;
                END;
                $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        String routine = "db:routine:DYN";
        assertThat(node(g, routine).attr("dynamicSql")).isEqualTo(true);

        String plain = stmt("DYN", sql, "EXECUTE 'DELETE FROM session_cache");
        assertThat(node(g, plain).attr("dynamic")).isEqualTo(true);
        assertThat(node(g, plain).attrString("dynamicKind")).isEqualTo("EXECUTE");
        assertThat(node(g, plain).attrString("sql")).isEqualTo("DELETE FROM session_cache WHERE k = $1");
        Edge delete = edge(g, plain, EdgeKind.WRITES, "db:table:SESSION_CACHE");
        assertThat(delete.attrString("op")).isEqualTo("DELETE");
        assertThat(delete.confidence()).isEqualTo(Confidence.RESOLVED);

        String formatted = stmt("DYN", sql, "EXECUTE format('DELETE FROM %I WHERE k");
        assertThat(node(g, formatted).attrString("sql")).isEqualTo("DELETE FROM session_cache WHERE k = $1");
        assertThat(edge(g, formatted, EdgeKind.WRITES, "db:table:SESSION_CACHE").attrString("op")).isEqualTo("DELETE");

        String insert = stmt("DYN", sql, "EXECUTE format('INSERT INTO %I.%I");
        assertThat(node(g, insert).attrString("sql")).isEqualTo("INSERT INTO public.audit_log (id, memo) VALUES ('7', now())");
        assertThat(targets(g, insert, EdgeKind.WRITES)).containsExactly("db:column:AUDIT_LOG.ID", "db:column:AUDIT_LOG.MEMO", "db:table:AUDIT_LOG");

        assertThat(targets(g, stmt("DYN", sql, "EXECUTE 'SELECT count(*) FROM '"), EdgeKind.READS)).containsExactly("db:table:CUSTOMERS");
        assertThat(targets(g, stmt("DYN", sql, "EXECUTE v_sql"), EdgeKind.WRITES)).containsExactly("db:column:ORDERS.MEMO", "db:table:ORDERS");
        assertThat(targets(g, stmt("DYN", sql, "FOR r IN EXECUTE"), EdgeKind.READS))
                .containsExactly("db:column:ORDER_ITEMS.ID", "db:column:ORDER_ITEMS.QTY", "db:table:ORDER_ITEMS");

        // 풀 수 없는 것: 기존 동적 SQL 미해결 기록과 같은 꼴
        String unresolved = stmt("DYN", sql, "EXECUTE format('DELETE FROM %I', p_table)");
        assertThat(node(g, unresolved).attr("dynamic")).isEqualTo(true);
        assertThat(node(g, unresolved).attrString("sql")).isEqualTo("DELETE FROM {*}");
        assertThat(targets(g, unresolved, EdgeKind.WRITES)).containsExactly("unresolved:sql-dynamic:" + unresolved);
        Edge u = edge(g, unresolved, EdgeKind.WRITES, "unresolved:sql-dynamic:" + unresolved);
        assertThat(u.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(u.attrString("op")).isEqualTo("DELETE");
        String partial = stmt("DYN", sql, "EXECUTE 'SELECT 1 FROM '");
        assertThat(node(g, partial).attrString("sql")).isEqualTo("SELECT 1 FROM {*} WHERE k = $1");
        assertThat(targets(g, partial, EdgeKind.READS)).containsExactly("unresolved:sql-dynamic:" + partial);
        assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).hasSize(2).allMatch(d -> d.severity() == Severity.WARN);
        assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).extracting(d -> d.location().startLine())
                .containsExactlyInAnyOrder(line(sql, "EXECUTE format('DELETE FROM %I', p_table)"), line(sql, "EXECUTE 'SELECT 1 FROM '"));
        // 서식의 조각·매개변수·INTO 대상이 테이블·컬럼이 되지 않는다
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
        assertThat(undeclaredColumns(g)).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC26 close_order의 EXECUTE format(.. %I .., 'session_cache'): SESSION_CACHE 쓰기(DELETE)가 RESOLVED로 남고 DYNAMIC_SQL 진단이 없다")
    void dynamicSqlOfCloseOrder() {
        GraphModel g = analyze(CLOSE_ORDER + TOUCH_CUSTOMER);
        String execute = stmt("CLOSE_ORDER", CLOSE_ORDER, "EXECUTE format(");
        assertThat(node(g, execute).attr("dynamic")).isEqualTo(true);
        assertThat(edge(g, execute, EdgeKind.WRITES, "db:table:SESSION_CACHE").attrString("op")).isEqualTo("DELETE");
        assertThat(edge(g, execute, EdgeKind.WRITES, "db:table:SESSION_CACHE").confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).isEmpty();
        assertThat(node(g, "db:routine:CLOSE_ORDER").attr("dynamicSql")).isEqualTo(true);
        assertThat(targets(g, "db:routine:CLOSE_ORDER", EdgeKind.CALLS)).containsExactly("db:routine:TOUCH_CUSTOMER");
    }

    // ------------------------------------------------------------------ F-019-AC27: 트리거

    private static final String TRIGGER_FUNCTION = """
            CREATE FUNCTION public.trg_orders_audit() RETURNS trigger
                LANGUAGE plpgsql
                AS $$
            BEGIN
                IF TG_OP = 'UPDATE' THEN
                    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
                        RETURN NEW;
                    END IF;
                END IF;
                INSERT INTO audit_log (id, memo) VALUES (NEW.id, TG_OP);
                UPDATE audit_log SET memo = TG_TABLE_NAME || to_jsonb(NEW)::text WHERE id = OLD.id AND at < NEW.ordered_at;
                RETURN NEW;
            END;
            $$;
            """;

    @Test
    @DisplayName("F-019-AC27 CREATE TRIGGER .. EXECUTE FUNCTION: 트리거 루틴이 테이블에 TRIGGERS_ON, 함수를 CALLS 한다. 함수 본문의 NEW·OLD·TG_*는 변수라 컬럼·테이블이 되지 않는다")
    void triggerAndTriggerFunction() {
        String sql = TRIGGER_FUNCTION
                + "CREATE TRIGGER orders_audit AFTER INSERT OR UPDATE ON public.orders FOR EACH ROW EXECUTE FUNCTION public.trg_orders_audit();\n";
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        Node trigger = node(g, "db:routine:ORDERS_AUDIT");
        assertThat(trigger.attrString("routineType")).isEqualTo("TRIGGER");
        assertThat(trigger.attrString("triggerEvent")).isEqualTo("AFTER INSERT OR UPDATE");
        assertThat(trigger.attrString("dialect")).isEqualTo("POSTGRES");
        assertThat(trigger.attrs()).doesNotContainKey("language");
        assertThat(targets(g, trigger.id(), EdgeKind.TRIGGERS_ON)).containsExactly("db:table:ORDERS");
        assertThat(targets(g, trigger.id(), EdgeKind.CALLS)).containsExactly("db:routine:TRG_ORDERS_AUDIT");
        assertThat(edge(g, trigger.id(), EdgeKind.CALLS, "db:routine:TRG_ORDERS_AUDIT").location().startLine()).isEqualTo(line(sql, "CREATE TRIGGER"));
        assertThat(statementsOf(g, trigger.id())).isEmpty();

        Node function = node(g, "db:routine:TRG_ORDERS_AUDIT");
        assertThat(function.attrString("routineType")).isEqualTo("FUNCTION");
        assertThat(function.attrString("language")).isEqualTo("PLPGSQL");
        assertThat(function.attrString("returnType")).isEqualTo("TRIGGER");
        String insert = stmt("TRG_ORDERS_AUDIT", sql, "INSERT INTO audit_log");
        assertThat(targets(g, insert, EdgeKind.WRITES)).containsExactly("db:column:AUDIT_LOG.ID", "db:column:AUDIT_LOG.MEMO", "db:table:AUDIT_LOG");
        assertThat(targets(g, insert, EdgeKind.READS)).isEmpty();
        String update = stmt("TRG_ORDERS_AUDIT", sql, "UPDATE audit_log");
        assertThat(targets(g, update, EdgeKind.READS)).containsExactly("db:column:AUDIT_LOG.AT", "db:column:AUDIT_LOG.ID");
        // NEW.status·OLD.status·NEW.ordered_at로 ORDERS의 컬럼 읽기를 만들지 않는다
        assertThat(routineTargets(g, function.id(), EdgeKind.READS)).noneMatch(t -> t.contains("ORDERS"));
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
        assertThat(undeclaredColumns(g)).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC27 CONSTRAINT TRIGGER, INSTEAD OF, UPDATE OF 컬럼, WHEN (조건), 옛 표기 EXECUTE PROCEDURE, 인자: 모두 트리거 루틴이고 사건 표기는 기존 꼴이다")
    void triggerForms() {
        String sql = TRIGGER_FUNCTION + """
                CREATE CONSTRAINT TRIGGER orders_check AFTER INSERT ON orders DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE PROCEDURE trg_orders_audit('a', 1);
                CREATE OR REPLACE TRIGGER orders_status BEFORE UPDATE OF status, memo OR DELETE ON public.orders
                    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status AND is_tracked(OLD.id)) EXECUTE FUNCTION trg_orders_audit();
                CREATE VIEW v_orders AS SELECT id, status FROM orders;
                CREATE TRIGGER v_orders_ins INSTEAD OF INSERT ON v_orders FOR EACH ROW EXECUTE FUNCTION public.trg_orders_audit();
                CREATE TRIGGER orders_stmt AFTER TRUNCATE ON orders REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION missing_fn();
                CREATE FUNCTION is_tracked(p_id bigint) RETURNS boolean LANGUAGE sql AS 'SELECT true';
                """;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        assertThat(node(g, "db:routine:ORDERS_CHECK").attrString("routineType")).isEqualTo("TRIGGER");
        assertThat(node(g, "db:routine:ORDERS_CHECK").attrString("triggerEvent")).isEqualTo("AFTER INSERT");
        assertThat(targets(g, "db:routine:ORDERS_CHECK", EdgeKind.TRIGGERS_ON)).containsExactly("db:table:ORDERS");
        assertThat(targets(g, "db:routine:ORDERS_CHECK", EdgeKind.CALLS)).containsExactly("db:routine:TRG_ORDERS_AUDIT");

        assertThat(node(g, "db:routine:ORDERS_STATUS").attrString("triggerEvent")).isEqualTo("BEFORE UPDATE OF STATUS,MEMO OR DELETE");
        assertThat(targets(g, "db:routine:ORDERS_STATUS", EdgeKind.TRIGGERS_ON)).containsExactly("db:table:ORDERS");
        // WHEN 조건의 함수 호출도 트리거에서 나간다. NEW·OLD는 변수다
        assertThat(targets(g, "db:routine:ORDERS_STATUS", EdgeKind.CALLS)).containsExactly("db:routine:IS_TRACKED", "db:routine:TRG_ORDERS_AUDIT");

        assertThat(node(g, "db:routine:V_ORDERS_INS").attrString("triggerEvent")).isEqualTo("INSTEAD OF INSERT");
        assertThat(targets(g, "db:routine:V_ORDERS_INS", EdgeKind.TRIGGERS_ON)).hasSize(1).allMatch(t -> t.endsWith(":V_ORDERS"));
        assertThat(targets(g, "db:routine:V_ORDERS_INS", EdgeKind.CALLS)).containsExactly("db:routine:TRG_ORDERS_AUDIT");

        // 정의가 없는 트리거 함수는 참조로 남는다(호출문 꼴)
        assertThat(node(g, "db:routine:ORDERS_STMT").attrString("triggerEvent")).isEqualTo("AFTER TRUNCATE");
        assertThat(targets(g, "db:routine:ORDERS_STMT", EdgeKind.CALLS)).containsExactly("db:routine:MISSING_FN");
        assertThat(node(g, "db:routine:MISSING_FN").declared()).isFalse();
        // 뷰에 건 트리거의 대상은 기존 규칙대로 테이블 참조로 남는다(뷰로 바꾸는 것은 링커의 정규화다). 그 밖의 테이블은 생기지 않는다
        assertThat(g.nodes(NodeKind.DB_TABLE).stream().filter(Node::declared).map(Node::name).sorted().collect(Collectors.toList()))
                .containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE", "V_ORDERS");
        assertThat(undeclaredColumns(g)).isEmpty();
    }

    // ------------------------------------------------------------------ F-019-AC28: DO

    @Test
    @DisplayName("F-019-AC28 DO $$ .. $$: 익명 블록의 구문 노드 하나에서 SESSION_CACHE 쓰기와 TOUCH_CUSTOMER 호출이 나가고 UNSUPPORTED_SYNTAX가 없다")
    void doBlock() {
        String sql = "SET search_path = public;\n\nDO $$ BEGIN DELETE FROM session_cache; PERFORM touch_customer(1); END $$;\n";
        GraphModel g = analyze(sql + TOUCH_CUSTOMER);
        assertThat(problems(g)).isEmpty();
        String block = Ids.sqlStatement(PATH, 3);
        Node n = node(g, block);
        assertThat(n.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
        assertThat(n.attrString("statementType")).isEqualTo("DELETE");
        assertThat(n.attrString("dialect")).isEqualTo("POSTGRES");
        assertThat(n.location().startLine()).isEqualTo(3);
        assertThat(hasEdge(g, Ids.file(PATH), EdgeKind.CONTAINS, block)).isTrue();
        assertThat(targets(g, block, EdgeKind.WRITES)).containsExactly("db:table:SESSION_CACHE");
        assertThat(edge(g, block, EdgeKind.WRITES, "db:table:SESSION_CACHE").attrString("op")).isEqualTo("DELETE");
        assertThat(edge(g, block, EdgeKind.WRITES, "db:table:SESSION_CACHE").location().startLine()).isEqualTo(3);
        assertThat(targets(g, block, EdgeKind.CALLS)).containsExactly("db:routine:TOUCH_CUSTOMER");
        // 블록 안의 구문은 따로 노드가 되지 않는다
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).extracting(Node::id)
                .containsExactlyInAnyOrder(block, stmt("TOUCH_CUSTOMER", sql + TOUCH_CUSTOMER, "UPDATE customers"));
    }

    @Test
    @DisplayName("F-019-AC28 DO LANGUAGE plpgsql $$..$$, DO $$..$$ LANGUAGE plpgsql, DECLARE가 있는 여러 줄 블록, 작은따옴표 본문도 익명 블록이다. 다른 언어의 DO는 분석하지 않고 진단도 없다")
    void doBlockForms() {
        String sql = """
                DO LANGUAGE plpgsql $do$
                DECLARE
                    v_n integer;
                BEGIN
                    SELECT count(*) INTO v_n FROM session_cache;
                    IF v_n > 0 THEN
                        DELETE FROM session_cache WHERE expires_at < now();
                    END IF;
                END
                $do$;
                DO $$ BEGIN UPDATE orders SET memo = 'x' WHERE id = 1; END $$ LANGUAGE plpgsql;
                DO 'BEGIN DELETE FROM audit_log WHERE memo = ''old''; END';
                DO LANGUAGE plpython3u $$ plpy.execute("DELETE FROM customers") $$;
                """;
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).isEmpty();
        String first = Ids.sqlStatement(PATH, 1);
        assertThat(targets(g, first, EdgeKind.READS)).containsExactly("db:column:SESSION_CACHE.EXPIRES_AT", "db:table:SESSION_CACHE");
        assertThat(targets(g, first, EdgeKind.WRITES)).containsExactly("db:table:SESSION_CACHE");
        assertThat(edge(g, first, EdgeKind.WRITES, "db:table:SESSION_CACHE").location().startLine()).isEqualTo(7);
        assertThat(targets(g, Ids.sqlStatement(PATH, 11), EdgeKind.WRITES)).containsExactly("db:column:ORDERS.MEMO", "db:table:ORDERS");
        assertThat(targets(g, Ids.sqlStatement(PATH, 12), EdgeKind.WRITES)).containsExactly("db:table:AUDIT_LOG");
        assertThat(g.hasNode(Ids.sqlStatement(PATH, 13))).isFalse();
        assertThat(g.incoming("db:table:CUSTOMERS").stream().filter(e -> e.kind() == EdgeKind.WRITES)).isEmpty();
        assertThat(undeclaredColumns(g)).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC28 매퍼(내장 소스)의 DO 블록도 주인 구문 노드 하나에 모인다")
    void doBlockInEmbeddedSource() {
        try (TestProject project = TestProject.create().file(DDL_PATH, DDL).file(PATH, TOUCH_CUSTOMER)) {
            AnalysisContext ctx = project.context();
            String owner = Ids.mybatisStatement("com.a.M", "cleanup");
            ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name("cleanup").location(SourceLocation.of("m.xml", 3)).build());
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner,
                    "DO $$ BEGIN DELETE FROM session_cache WHERE k = #{key}; PERFORM touch_customer(1); END $$", SourceLocation.of("m.xml", 3, 5, 3, 5), false,
                    Map.of("dialect", "postgresql")));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(problems(g)).isEmpty();
            assertThat(targets(g, owner, EdgeKind.WRITES)).containsExactly("db:table:SESSION_CACHE");
            assertThat(targets(g, owner, EdgeKind.READS)).containsExactly("db:column:SESSION_CACHE.K");
            assertThat(targets(g, owner, EdgeKind.CALLS)).containsExactly("db:routine:TOUCH_CUSTOMER");
        }
    }

    // ------------------------------------------------------------------ F-019-AC29: LANGUAGE sql

    @Test
    @DisplayName("F-019-AC29 LANGUAGE sql: 달러 인용·작은따옴표 본문의 구문과 RETURN 식이 루틴의 구문 노드가 되고 language=SQL이다. $1과 매개변수 이름은 컬럼이 아니다")
    void sqlLanguageBodies() {
        String sql = """
                CREATE PROCEDURE purge_old(p_days int) LANGUAGE sql AS $$ DELETE FROM audit_log WHERE at < now() - (p_days || ' days')::interval; $$;
                CREATE FUNCTION cnt(bigint) RETURNS bigint LANGUAGE sql AS 'SELECT count(*) FROM orders WHERE customer_id = $1';
                CREATE FUNCTION one() RETURNS int LANGUAGE sql RETURN 1;
                CREATE FUNCTION latest(p_customer bigint) RETURNS bigint LANGUAGE sql STABLE
                    RETURN (SELECT max(o.id) FROM orders o WHERE o.customer_id = p_customer);
                CREATE FUNCTION move_all(p_note text) RETURNS void
                    AS $fn$
                    INSERT INTO audit_log (id, memo)
                    SELECT o.id, p_note FROM orders o WHERE o.status = 'OLD';
                    DELETE FROM orders WHERE status = 'OLD' AND memo = p_note
                $fn$ LANGUAGE sql;
                """;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        Node purge = node(g, "db:routine:PURGE_OLD");
        assertThat(purge.attrString("routineType")).isEqualTo("PROCEDURE");
        assertThat(purge.attrString("language")).isEqualTo("SQL");
        assertThat(purge.attrList("parameters")).containsExactly("P_DAYS INT");
        String delete = Ids.routineStatement("PURGE_OLD", 1);
        assertThat(statementsOf(g, purge.id())).containsExactly(delete);
        assertThat(targets(g, delete, EdgeKind.WRITES)).containsExactly("db:table:AUDIT_LOG");
        assertThat(targets(g, delete, EdgeKind.READS)).containsExactly("db:column:AUDIT_LOG.AT");
        assertThat(node(g, delete).attrString("sql")).isEqualTo("DELETE FROM audit_log WHERE at < now() - (p_days || ' days')::interval");

        Node cnt = node(g, "db:routine:CNT");
        assertThat(cnt.attrString("language")).isEqualTo("SQL");
        assertThat(cnt.attrString("returnType")).isEqualTo("BIGINT");
        String select = Ids.routineStatement("CNT", 2);
        assertThat(statementsOf(g, cnt.id())).containsExactly(select);
        assertThat(targets(g, select, EdgeKind.READS)).containsExactly("db:column:ORDERS.CUSTOMER_ID", "db:table:ORDERS");
        assertThat(node(g, select).attrString("sql")).isEqualTo("SELECT count(*) FROM orders WHERE customer_id = $1");

        Node one = node(g, "db:routine:ONE");
        assertThat(one.attrString("language")).isEqualTo("SQL");
        assertThat(statementsOf(g, one.id())).isEmpty();
        assertThat(((Number) one.attr("statementCount")).intValue()).isZero();

        Node latest = node(g, "db:routine:LATEST");
        assertThat(latest.attrString("language")).isEqualTo("SQL");
        assertThat(routineTargets(g, latest.id(), EdgeKind.READS)).containsExactly("db:column:ORDERS.CUSTOMER_ID", "db:column:ORDERS.ID", "db:table:ORDERS");

        Node move = node(g, "db:routine:MOVE_ALL");
        assertThat(move.attrString("language")).isEqualTo("SQL");
        assertThat(statementsOf(g, move.id())).containsExactlyInAnyOrder(stmt("MOVE_ALL", sql, "INSERT INTO audit_log (id, memo)"),
                stmt("MOVE_ALL", sql, "DELETE FROM orders WHERE status = 'OLD'"));
        assertThat(routineTargets(g, move.id(), EdgeKind.WRITES))
                .containsExactly("db:column:AUDIT_LOG.ID", "db:column:AUDIT_LOG.MEMO", "db:table:AUDIT_LOG", "db:table:ORDERS");
        // P_DAYS·P_NOTE·P_CUSTOMER가 컬럼이 되지 않는다
        assertThat(undeclaredColumns(g)).isEmpty();
        assertThat(columns(g)).noneMatch(c -> c.contains(".P_"));
        assertThat(tables(g)).containsExactly("AUDIT_LOG", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SESSION_CACHE");
    }

    @Test
    @DisplayName("F-019-AC29 SQL 표준 본문 BEGIN ATOMIC .. END: 안의 구문마다 루틴의 구문 노드가 되고 뒤따르는 구문을 삼키지 않는다")
    void beginAtomicBody() {
        String sql = """
                CREATE PROCEDURE settle(p_id bigint) LANGUAGE sql
                BEGIN ATOMIC
                    UPDATE orders SET status = CASE WHEN amount > 0 THEN 'PAID' ELSE 'FREE' END WHERE id = p_id;
                    INSERT INTO audit_log (id, memo) VALUES (p_id, 'settled');
                END;
                SELECT id FROM customers;
                """;
        GraphModel g = analyze(sql);
        assertThat(problems(g)).isEmpty();
        Node settle = node(g, "db:routine:SETTLE");
        assertThat(settle.attrString("language")).isEqualTo("SQL");
        assertThat(statementsOf(g, settle.id())).containsExactlyInAnyOrder(Ids.routineStatement("SETTLE", 3), Ids.routineStatement("SETTLE", 4));
        assertThat(routineTargets(g, settle.id(), EdgeKind.WRITES)).containsExactly("db:column:AUDIT_LOG.ID", "db:column:AUDIT_LOG.MEMO",
                "db:column:ORDERS.STATUS", "db:table:AUDIT_LOG", "db:table:ORDERS");
        assertThat(hasEdge(g, Ids.sqlStatement(PATH, 6), EdgeKind.READS, "db:table:CUSTOMERS")).isTrue();
        assertThat(undeclaredColumns(g)).isEmpty();
    }

    // ------------------------------------------------------------------ 기존 결과(F-019-R8)와 실패 격리

    @Test
    @DisplayName("F-019-AC23 본문 하나가 깨져도 PARSE_FAILED로 남고 같은 파일의 다른 루틴은 그대로 분석된다. 닫히지 않은 DO 본문은 진단 1건만 남긴다")
    void brokenBodyDoesNotStopTheFile() {
        String sql = """
                CREATE FUNCTION broken() RETURNS void LANGUAGE plpgsql AS $$
                BEGIN
                    DELETE FROM audit_log;
                    IF true THEN
                        UPDATE orders SET memo = 'x';
                $$;
                """ + TOUCH_CUSTOMER + "DO $$ BEGIN DELETE FROM customers;\n";
        GraphModel g = analyze(sql);
        assertThat(g.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
        assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).hasSize(2);
        assertThat(routineTargets(g, "db:routine:BROKEN", EdgeKind.WRITES)).contains("db:table:AUDIT_LOG", "db:table:ORDERS");
        assertThat(routineTargets(g, "db:routine:TOUCH_CUSTOMER", EdgeKind.WRITES)).contains("db:table:CUSTOMERS");
        assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).anyMatch(d -> d.message().contains("닫히지 않은 달러 인용"));
        assertThat(g.incoming("db:table:CUSTOMERS").stream().filter(e -> e.kind() == EdgeKind.WRITES).map(Edge::from))
                .containsExactly(stmt("TOUCH_CUSTOMER", sql, "UPDATE customers"));
    }
}
