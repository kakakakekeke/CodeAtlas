package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.node;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.analysis.DialectDetector;
import io.codeatlas.sql.analysis.DialectSelection;
import io.codeatlas.sql.lexer.Lexer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PostgreSQL 판정(F-019-R2, F-019-R3): 경로의 방언 이름, 확장자, 내용 특징, 구문 특징(토큰 점수). */
class PostgresDialectDetectionTest {

    private static GraphModel analyze(String path, String sql) {
        try (TestProject project = TestProject.create().file(path, sql)) {
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    private static Dialect detect(String sql) {
        return DialectDetector.fromTokens(Lexer.tokenize(sql));
    }

    // ------------------------------------------------------------------ F-019-AC2: 파일의 방언

    @Test
    @DisplayName("F-019-AC2 경로의 방언 이름(postgres, postgresql, pgsql)과 확장자 .pgsql로 PostgreSQL 파일을 알아본다")
    void postgresByPath() {
        for (String path : new String[] {"db/postgres/schema.sql", "db/schema_pgsql.sql", "db/fn.pgsql", "db/postgresql/a.sql", "db/pgsql/a.sql",
                "sql/V1__init.postgresql.sql"}) {
            assertThat(DialectSelection.dialectOfFile(path)).as(path).isEqualTo("postgres");
        }
        assertThat(DialectDetector.fromPath("db/postgres/schema.sql")).isEqualTo(Dialect.POSTGRES);
        assertThat(DialectDetector.fromPath("db/PostgreSQL/schema.sql")).isEqualTo(Dialect.POSTGRES);
        assertThat(DialectDetector.fromPath("db\\pgsql\\schema.sql")).isEqualTo(Dialect.POSTGRES);
        assertThat(DialectDetector.fromPath("db/fn.PGSQL")).isEqualTo(Dialect.POSTGRES);
        // Oracle·DB2 경로 힌트는 그대로다
        assertThat(DialectDetector.fromPath("db/oracle/a.sql")).isEqualTo(Dialect.ORACLE);
        assertThat(DialectDetector.fromPath("db/pkg.pkb")).isEqualTo(Dialect.ORACLE);
        assertThat(DialectDetector.fromPath("db/db2/a.sql")).isEqualTo(Dialect.DB2);
        assertThat(DialectDetector.fromPath("db/a.sql")).isNull();
    }

    @Test
    @DisplayName("F-019-AC2 방언 이름 postgres·postgresql·pgsql(대소문자 무시)은 POSTGRES다. oracle·db2는 그대로고 대상 외 방언은 null이다")
    void postgresByName() {
        for (String name : new String[] {"postgres", "postgresql", "pgsql", "PostgreSQL", " PGSQL "}) {
            assertThat(DialectDetector.fromName(name)).as(name).isEqualTo(Dialect.POSTGRES);
        }
        assertThat(DialectDetector.fromName("oracle")).isEqualTo(Dialect.ORACLE);
        assertThat(DialectDetector.fromName("db2")).isEqualTo(Dialect.DB2);
        assertThat(DialectDetector.fromName("mysql")).isNull();
        assertThat(DialectDetector.fromName(null)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("F-019-AC2 경로에 방언 이름이 없는 파일은 PostgreSQL 내용 특징 하나로 postgres다")
    @ValueSource(strings = {
        "CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END; $$ LANGUAGE x;",
        "CREATE FUNCTION f() RETURNS int AS\n$body$ BEGIN RETURN 1; END; $body$;",
        "DO $$ BEGIN NULL; END $$;",
        "CREATE FUNCTION f() RETURNS int LANGUAGE plpgsql AS 'BEGIN RETURN 1; END';",
        "ALTER TABLE ONLY public.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);",
        "ALTER TABLE IF EXISTS ONLY orders ADD COLUMN memo text;",
        "COPY public.customers (id, email) FROM stdin;\n1\ta@b.c\n\\.\n",
        "COPY customers FROM STDIN;",
        "CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;",
        "ALTER TABLE orders ALTER COLUMN id SET DEFAULT nextval('orders_id_seq'::regclass);",
        "SET search_path = public, pg_temp;",
        "SELECT pg_catalog.set_config('search_path', '', false);",
        "CREATE TABLE t (id serial PRIMARY KEY, v text);",
        "CREATE TABLE t (\n    id serial,\n    v text\n);",
        "CREATE TABLE t (id bigserial NOT NULL, v text);",
        "CREATE TABLE t (id smallserial, v text);",
        "CREATE TABLE t (id int, profile jsonb);",
        "CREATE TABLE t (id int, v bytea);",
        "CREATE TABLE t (id int, at timestamptz);",
        "ALTER TABLE public.orders OWNER TO app;",
        "SELECT c.profile #>> '{a,b}' FROM customers c WHERE c.id = #{id}"})
    void postgresByContent(String text) {
        assertThat(DialectDetector.contentDialect(text)).isEqualTo("postgres");
        assertThat(DialectDetector.postgresFeatures(text)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("F-019-AC2 Oracle·DB2·MySQL 본문에 있을 수 있는 표기는 PostgreSQL 내용 특징이 아니다: 이름 안의 $·#, 조건부 컴파일의 $$, SERIAL#, SERIAL이라는 컬럼")
    @ValueSource(strings = {
        "SELECT A$$B, C$$ FROM T#1 WHERE X$ = 1",
        "BEGIN DBMS_OUTPUT.PUT_LINE($$PLSQL_UNIT || ':' || $$PLSQL_LINE); END;",
        "CREATE PROCEDURE P AS BEGIN $IF $$DEBUG $THEN NULL; $END NULL; END;",
        "SELECT SID, SERIAL# FROM V$SESSION",
        "SELECT SERIAL, NAME FROM T_DEVICE ORDER BY SERIAL",
        "INSERT INTO T_DEVICE (ID, SERIAL) VALUES (1, 'A')",
        "SELECT A FROM T WHERE SERIAL#>0 AND COL#>> 1",
        "SELECT A FROM T WHERE B = #{b} AND C = ${c}",
        "COPY THE ROWS FROM STDIN_TABLE",
        "SELECT LANGUAGE, OWNER FROM T_CFG",
        "SELECT a::text FROM t"})
    void notPostgresContent(String text) {
        assertThat(DialectDetector.postgresFeatures(text)).isFalse();
        assertThat(DialectDetector.contentDialect(text)).isNull();
    }

    @Test
    @DisplayName("F-019-AC2 내용 특징의 순서: HSQL·MySQL 특징이 먼저고, PostgreSQL의 강한 특징이 Oracle·DB2 특징보다 먼저다. 기존 판정은 그대로다")
    void contentFeatureOrder() {
        assertThat(DialectDetector.contentDialect("CREATE MEMORY TABLE T (ID INT, V BYTEA)")).isEqualTo("hsql");
        assertThat(DialectDetector.contentDialect("CREATE TABLE `t` (`id` bigint, v jsonb) ENGINE=InnoDB")).isEqualTo("mysql");
        assertThat(DialectDetector.contentDialect("ALTER TABLE ONLY t ADD COLUMN v NUMBER(10)")).isEqualTo("postgres");
        assertThat(DialectDetector.contentDialect("CREATE TABLE T (ID NUMBER(10), NM VARCHAR2(20))")).isEqualTo("oracle");
        assertThat(DialectDetector.contentDialect("CREATE TABLE T (ID INT GENERATED ALWAYS AS IDENTITY) IN TS1")).isEqualTo("db2");
        assertThat(DialectDetector.contentDialect("CREATE TABLE T (ID INT)")).isNull();
        assertThat(DialectDetector.contentDialect("")).isNull();
        assertThat(DialectDetector.postgresFeatures(null)).isFalse();
    }

    // ------------------------------------------------------------------ F-019-AC2: 주석·문자열 속 표기, 강한 특징과 약한 특징

    @ParameterizedTest(name = "{0}")
    @DisplayName("F-019-AC2 주석(--, /* */)과 작은따옴표 문자열 안의 PostgreSQL 표기는 내용 특징이 아니다")
    @ValueSource(strings = {
        "-- 2020-01-01 change owner to APP_USER after deploy\nCREATE TABLE T (ID INT);",
        "CREATE TABLE T (ID INT); -- ALTER TABLE ONLY t 로 옮길 것",
        "/* ALTER TABLE ONLY t ADD COLUMN x int;\n   CREATE EXTENSION pgcrypto; */\nSELECT A FROM T",
        "/*\n * LANGUAGE plpgsql 로 다시 쓸 예정\n * AS $$ .. $$\n */\nSELECT A FROM T",
        "INSERT INTO T_LOG (MSG) VALUES ('SET search_path = public')",
        "SELECT 'x'||'::regclass' FROM T",
        "COMMENT ON COLUMN T.A IS 'jsonb 값. it''s bytea, timestamptz 아님'",
        "SELECT A FROM T WHERE MEMO = 'pg_catalog.pg_class' -- COPY t FROM stdin",
        "INSERT INTO T_HELP (TXT) VALUES ('CREATE TABLE t (id serial, v text)')",
        "SELECT /* a #>> b */ A FROM T WHERE B = 'x #> y'",
        "SELECT q'[it's owner to x]' FROM DUAL"})
    void featuresInCommentsAndStringsDoNotCount(String text) {
        assertThat(DialectDetector.postgresFeatures(text)).isFalse();
        assertThat(DialectDetector.contentDialect(text)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("F-019-AC2 주석·문자열 뒤의 코드에 있는 PostgreSQL 표기는 그대로 내용 특징이다. 옛 표기 LANGUAGE 'plpgsql'은 따옴표째로 특징이다")
    @ValueSource(strings = {
        "-- 주문 테이블의 기본 키\nALTER TABLE ONLY public.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);",
        "/* 확장 */ CREATE EXTENSION IF NOT EXISTS pgcrypto;",
        "SELECT 'it''s' FROM t;\nSET search_path = public;",
        "CREATE FUNCTION f() RETURNS int AS 'BEGIN RETURN 1; END' LANGUAGE 'plpgsql';",
        "CREATE FUNCTION f() RETURNS int AS '\n-- don''t\nBEGIN RETURN 1; END\n' LANGUAGE plpgsql;",
        "COMMENT ON TABLE t IS 'it''s';\nCREATE TABLE t2 (id int, v jsonb);",
        "SELECT E'it\\'s', c.profile #>> '{a,b}' FROM customers c",
        "SELECT 'a' /* 'b */ , 'c' FROM t; ALTER TABLE t OWNER TO app;",
        "-- it's\nALTER TABLE t OWNER TO app;",
        "SELECT nextval('orders_id_seq'::regclass)"})
    void featuresInCodeStillCount(String text) {
        assertThat(DialectDetector.postgresFeatures(text)).isTrue();
        assertThat(DialectDetector.contentDialect(text)).isEqualTo("postgres");
    }

    @Test
    @DisplayName("F-019-AC2 약한 특징(OWNER TO, 타입 이름, serial 컬럼, #>)은 Oracle·DB2 특징이 없을 때만 postgres다. 강한 특징은 Oracle·DB2 특징보다 먼저다")
    void weakFeaturesYieldToOracleAndDb2() {
        String oracle = "CREATE TABLE T (ID NUMBER(10), NM VARCHAR2(20));\n";
        String db2 = "CREATE TABLE T (ID INT GENERATED ALWAYS AS IDENTITY) IN TS1;\n";
        for (String weak : new String[] {"ALTER TABLE T OWNER TO APP;", "CREATE TABLE T2 (ID bigserial);", "CREATE TABLE T2 (ID smallserial);",
            "CREATE TABLE T2 (V jsonb);", "CREATE TABLE T2 (V bytea);", "CREATE TABLE T2 (AT timestamptz);", "CREATE TABLE T2 (ID serial, V INT);",
            "SELECT A FROM T WHERE P #>> '{a}' = 'x';", "SELECT A FROM T WHERE P #> '{a}' IS NULL;"}) {
            assertThat(DialectDetector.contentDialect(weak)).as(weak).isEqualTo("postgres");
            assertThat(DialectDetector.contentDialect(oracle + weak)).as("oracle + " + weak).isEqualTo("oracle");
            assertThat(DialectDetector.contentDialect(weak + "\n" + oracle)).as(weak + " + oracle").isEqualTo("oracle");
            assertThat(DialectDetector.contentDialect(db2 + weak)).as("db2 + " + weak).isEqualTo("db2");
        }
        for (String strong : new String[] {"CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE x;", "DO $do$ BEGIN NULL; END $do$;",
            "CREATE FUNCTION f() RETURNS int LANGUAGE plpgsql AS 'BEGIN RETURN 1; END';", "ALTER TABLE ONLY t ADD COLUMN x int;",
            "ALTER TABLE IF EXISTS ONLY t ADD COLUMN x int;", "COPY t (a, b) FROM stdin;", "CREATE EXTENSION pgcrypto;",
            "SELECT 'orders_id_seq'::regclass;", "SET search_path = public;", "SELECT pg_catalog.set_config('search_path', '', false);"}) {
            assertThat(DialectDetector.contentDialect(strong)).as(strong).isEqualTo("postgres");
            assertThat(DialectDetector.contentDialect(oracle + strong)).as("oracle + " + strong).isEqualTo("postgres");
            assertThat(DialectDetector.contentDialect(db2 + strong)).as("db2 + " + strong).isEqualTo("postgres");
        }
        // 주석 속 강한 특징과 코드의 약한 특징: 약한 특징만 센다
        assertThat(DialectDetector.contentDialect("-- ALTER TABLE ONLY 로 바꿀 것\n" + oracle + "ALTER TABLE T OWNER TO APP;")).isEqualTo("oracle");
    }

    @Test
    @DisplayName("F-019-AC2 내용 특징 판정은 망가진 본문(닫히지 않은 주석·문자열·q 인용)에도 예외 없이 끝나고, 주석 속 후보가 아주 많은 큰 본문도 한 번 훑는 시간에 끝난다")
    void contentDetectionIsRobustAndLinear() {
        for (String broken : new String[] {"'", "''", "'''", "E'", "E'\\", "q'", "q'[", "nq'[x", "/*", "/* ALTER TABLE ONLY", "-", "--", "-- owner to",
            "'owner to", "x'", "q'[a]' q'[b ALTER TABLE ONLY t", "ALTER TABLE ONLY", "#>", "'#>>"}) {
            DialectDetector.contentDialect(broken);
            DialectDetector.postgresFeatures(broken);
        }
        // 닫히지 않은 문자열은 렉서처럼 그 줄 끝에서 끝난다: 다음 줄의 특징은 코드에 있다
        assertThat(DialectDetector.contentDialect("SELECT 'a\nALTER TABLE ONLY t ADD x int;")).isEqualTo("postgres");
        assertThat(DialectDetector.contentDialect("/* 닫히지 않은 주석\nALTER TABLE ONLY t ADD x int;")).isNull();

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            big.append("-- change owner to APP_USER; ALTER TABLE ONLY t; q'[ it's\n");
        }
        big.append("/*\n");
        for (int i = 0; i < 20_000; i++) {
            big.append(" * CREATE EXTENSION x; v jsonb; SELECT q'[ 'a' #>> 'b'\n");
        }
        big.append(" */\nINSERT INTO T_LOG (MSG) VALUES ('");
        for (int i = 0; i < 10_000; i++) {
            big.append("SET search_path = public; owner to x; ");
        }
        big.append("');\n");
        String text = big.toString();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
            assertThat(DialectDetector.postgresFeatures(text)).isFalse();
            assertThat(DialectDetector.contentDialect(text)).isNull();
            assertThat(DialectDetector.contentDialect(text + "CREATE TABLE T (ID NUMBER(10));\n")).isEqualTo("oracle");
            assertThat(DialectDetector.contentDialect(text + "ALTER TABLE ONLY t ADD x int;\n")).isEqualTo("postgres");
        });
    }

    /** F-019 이전에 Oracle로 판정되던 스크립트: 주석에 "owner to"가 있고 컬럼 이름이 {@code SERIAL#}이다. */
    private static final String ORACLE_SCRIPT_COMMENT = "-- 2020-01-01 change owner to APP_USER after deploy\n";
    private static final String ORACLE_SCRIPT_BODY = """
            CREATE TABLE TB_ORD (ORD_ID NUMBER(10) NOT NULL, ORD_NM VARCHAR2(100), SERIAL# NUMBER(5));
            CREATE OR REPLACE PROCEDURE SP_ORD(P_ID IN NUMBER) IS
              V_CNT NUMBER;
            BEGIN
              SELECT COUNT(*) INTO V_CNT FROM TB_ORD WHERE ORD_ID = P_ID;
              UPDATE TB_ORD SET ORD_NM = 'X' WHERE ORD_ID = P_ID AND SERIAL# > 0;
            END;
            /
            """;

    /** 그래프의 노드(ID와 attrs), 엣지, 진단을 정렬한 목록. 두 분석 결과가 같은지 볼 때 쓴다. */
    private static java.util.List<String> signature(GraphModel g) {
        java.util.List<String> out = new java.util.ArrayList<>();
        g.nodes().forEach(n -> out.add("N " + n.id() + " " + n.declared() + " " + new java.util.TreeMap<>(n.attrs())));
        g.edges().forEach(e -> out.add("E " + e.from() + " -" + e.kind() + "-> " + e.to() + " " + new java.util.TreeMap<>(e.attrs())));
        g.diagnostics().forEach(d -> out.add("D " + d.code() + " " + d.message()));
        java.util.Collections.sort(out);
        return out;
    }

    @Test
    @DisplayName("F-019-AC2 주석에 'owner to'가 있는 Oracle 스크립트는 F-019 이전처럼 oracle이다: 프로시저 본문 구문 2개, 컬럼 SERIAL#, dialect=ORACLE, PARSE_FAILED 없음")
    void oracleScriptWithPostgresWordsInCommentStaysOracle() {
        String text = ORACLE_SCRIPT_COMMENT + ORACLE_SCRIPT_BODY;
        assertThat(DialectDetector.contentDialect(text)).isEqualTo("oracle");
        GraphModel g = analyze("db/ddl.sql", text);
        assertThat(g.diagnostics()).extracting(d -> d.code() + " " + d.message()).isEmpty();
        Node routine = node(g, "db:routine:SP_ORD");
        assertThat(routine.attrString("dialect")).isEqualTo("ORACLE");
        assertThat(routine.attrString("language")).isEqualTo("PLSQL");
        assertThat(((Number) routine.attr("statementCount")).intValue()).isEqualTo(2);
        assertThat(node(g, "db:column:TB_ORD.SERIAL#").declared()).isTrue();
        assertThat(g.hasNode("db:column:TB_ORD.SERIAL")).isFalse();
        for (String id : new String[] {"sql:stmt:SP_ORD:6", "sql:stmt:SP_ORD:7"}) {
            assertThat(node(g, id).attrString("dialect")).as(id).isEqualTo("ORACLE");
        }
        // 주석의 낱말만 다른 같은 스크립트(F-019 이전에도 지금도 Oracle)와 결과가 같다
        GraphModel plain = analyze("db/ddl.sql", "-- 2020-01-01 change holder of APP_USER after deploy\n" + ORACLE_SCRIPT_BODY);
        assertThat(signature(g)).isEqualTo(signature(plain));
    }

    @Test
    @DisplayName("F-019-AC2 F-019-AC7 그 스크립트뿐인 프로젝트의 매퍼 SQL은 기본 읽기 방식이다: SERIAL#이 이름이고 구문의 dialect가 POSTGRES가 아니다")
    void oracleScriptDoesNotTurnMapperSqlIntoPostgres() {
        try (TestProject project = TestProject.create().file("db/ddl.sql", ORACLE_SCRIPT_COMMENT + ORACLE_SCRIPT_BODY)
                .file("mapper/M.xml", "<mapper namespace=\"m\"/>")) {
            AnalysisContext ctx = project.context();
            String owner = owner(ctx, "find");
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, "SELECT T.ORD_NM FROM TB_ORD T WHERE T.SERIAL# > #{n} AND ROWNUM = 1",
                    SourceLocation.of("mapper/M.xml", 1, 1, 1, 1), false, Map.of()));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(g.diagnostics()).extracting(d -> d.code() + " " + d.message()).isEmpty();
            assertThat(GraphAssert.targets(g, owner, io.codeatlas.core.model.EdgeKind.READS))
                    .contains("db:table:TB_ORD", "db:column:TB_ORD.ORD_NM", "db:column:TB_ORD.SERIAL#");
            assertThat(node(g, owner).attrString("dialect")).isEqualTo("ORACLE");
        }
    }

    @Test
    @DisplayName("F-019-AC2 F-019-AC7 방언 표시가 없는 매퍼 구문: 주석·문자열 속 PostgreSQL 표기로는 PostgreSQL 읽기 방식이 되지 않는다(SERIAL#이 이름으로 남는다)")
    void embeddedSourceIgnoresFeaturesInCommentsAndStrings() {
        try (TestProject project = TestProject.create().file("mapper/M.xml", "<mapper namespace=\"m\"/>")) {
            AnalysisContext ctx = project.context();
            String owner = owner(ctx, "find");
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner,
                    "SELECT /* jsonb 아님. owner to 확인 */ T.SERIAL# FROM TB_DEV T WHERE T.MEMO = 'a #>> b' -- ALTER TABLE ONLY",
                    SourceLocation.of("mapper/M.xml", 1, 1, 1, 1), false, Map.of()));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(g.diagnostics()).extracting(d -> d.code() + " " + d.message()).isEmpty();
            assertThat(GraphAssert.targets(g, owner, io.codeatlas.core.model.EdgeKind.READS)).contains("db:column:TB_DEV.SERIAL#");
            assertThat(node(g, owner).attr("dialect")).isNull();
        }
    }

    @Test
    @DisplayName("F-019-AC2 PostgreSQL로 판정한 파일(경로, 확장자, 내용 특징)의 구문 노드 dialect는 POSTGRES다")
    void statementsOfPostgresFilesAreStamped() {
        String dml = "UPDATE orders SET status = 'SHIPPED' WHERE id = 1;\n";
        for (String path : new String[] {"db/postgres/q.sql", "db/q_pgsql.sql", "db/q.pgsql"}) {
            GraphModel g = analyze(path, dml);
            assertThat(node(g, Ids.sqlStatement(path, 1)).attrString("dialect")).as(path).isEqualTo("POSTGRES");
        }
        // 경로에 방언 이름이 없으면 내용 특징: 파일 어디에든 특징이 하나 있으면 그 파일의 구문 모두가 POSTGRES다
        GraphModel byContent = analyze("db/q.sql", "CREATE TABLE orders (id bigserial NOT NULL, status text);\n" + dml);
        assertThat(node(byContent, "sql:stmt:db/q.sql:2").attrString("dialect")).isEqualTo("POSTGRES");
        // 특징이 없는 파일은 전과 같다(키 없음)
        assertThat(node(analyze("db/q.sql", dml), "sql:stmt:db/q.sql:1").attr("dialect")).isNull();
    }

    @Test
    @DisplayName("F-019-AC2 경로의 방언 이름이 내용 특징보다 먼저다: oracle/ 아래 파일은 본문에 ::나 bigserial이 있어도 ORACLE이다")
    void pathNameWinsOverContent() {
        String path = "db/oracle/q.sql";
        assertThat(DialectSelection.dialectOfFile(path)).isEqualTo("oracle");
        GraphModel g = analyze(path, "UPDATE orders SET memo = id::text WHERE id = 1;\nCREATE TABLE t2 (id bigserial);\n");
        assertThat(node(g, Ids.sqlStatement(path, 1)).attrString("dialect")).isEqualTo("ORACLE");
    }

    @Test
    @DisplayName("F-019-AC2 PostgreSQL로 판정한 파일의 루틴 노드 dialect는 POSTGRES다")
    void routinesOfPostgresFilesAreStamped() {
        String routine = "CREATE OR REPLACE PROCEDURE p_touch(p_id integer)\nLANGUAGE plpgsql\nAS $$\nBEGIN\n"
                + "  UPDATE t SET v = 'x' WHERE id = p_id;\nEND;\n$$;\n";
        for (String path : new String[] {"db/postgres/proc.sql", "db/proc.sql"}) { // 경로의 방언 이름, 내용 특징(LANGUAGE plpgsql, AS $$)
            GraphModel g = analyze(path, routine);
            Node node = g.nodes(NodeKind.DB_ROUTINE).stream().filter(n -> n.name().equals("P_TOUCH")).findFirst().orElseThrow();
            assertThat(node.attrString("dialect")).as(path).isEqualTo("POSTGRES");
        }
    }

    // ------------------------------------------------------------------ F-019-AC3: 구문 특징(토큰 점수)

    @Test
    @DisplayName("F-019-AC3 구문 특징 판정: nextval('seq')와 ::·ILIKE는 POSTGRES, NVL은 ORACLE, FETCH FIRST .. WITH UR은 DB2, 특징이 없으면 null이다")
    void tokenScores() {
        assertThat(detect("SELECT nextval('orders_id_seq')")).isEqualTo(Dialect.POSTGRES);
        assertThat(detect("SELECT a::text FROM t WHERE b ILIKE #{q}")).isEqualTo(Dialect.POSTGRES);
        assertThat(detect("SELECT NVL(a, 0) FROM t")).isEqualTo(Dialect.ORACLE);
        assertThat(detect("SELECT a FROM t FETCH FIRST 1 ROWS ONLY WITH UR")).isEqualTo(Dialect.DB2);
        assertThat(detect("SELECT a FROM t")).isNull();
    }

    @Test
    @DisplayName("F-019-AC3 그 밖의 PostgreSQL 구문 특징도 점수가 된다: currval·setval, 달러 인용, ON CONFLICT, #>>·@>, 타입, PLPGSQL, PG_CATALOG, PERFORM, RAISE NOTICE, DISTINCT ON")
    void otherPostgresTokenFeatures() {
        for (String sql : new String[] {
            "SELECT currval('s')", "SELECT setval('s', 1)",
            "DO $$ BEGIN NULL; END $$", "CREATE FUNCTION f() RETURNS int AS $body$ SELECT 1 $body$",
            "INSERT INTO t (a) VALUES (1) ON CONFLICT (a) DO NOTHING",
            "SELECT c.profile #>> '{a,b}' FROM customers c", "SELECT a FROM t WHERE tags @> ARRAY['x']",
            "CREATE TABLE t (id bigserial, v jsonb)", "CREATE TABLE t (id serial PRIMARY KEY, raw bytea, at timestamptz)",
            "CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql", "SELECT relname FROM pg_catalog.pg_class",
            "BEGIN PERFORM touch(1); END", "BEGIN RAISE NOTICE 'x'; END", "BEGIN RAISE EXCEPTION 'x'; END",
            "SELECT DISTINCT ON (c.id) c.id FROM customers c"}) {
            assertThat(detect(sql)).as(sql).isEqualTo(Dialect.POSTGRES);
        }
    }

    @Test
    @DisplayName("F-019-AC3 PostgreSQL에만 있는 표기(달러 인용, ::, ILIKE, nextval('..'))가 있으면 :=·NEXTVAL·LANGUAGE SQL·FETCH FIRST가 있어도 Oracle·DB2로 판정하지 않는다")
    void postgresOnlyNotationVetoesOracleAndDb2() {
        // PL/pgSQL 본문: := 는 PL/SQL에도 있지만 ::가 있으면 Oracle 점수로 세지 않는다
        assertThat(detect("BEGIN v_cnt := v_cnt + 1; v_txt := v_cnt::text; END")).isEqualTo(Dialect.POSTGRES);
        assertThat(detect("SELECT nextval('s'), currval('s') FROM t")).isEqualTo(Dialect.POSTGRES);
        // LANGUAGE SQL과 FETCH FIRST는 PostgreSQL에도 있다: PostgreSQL에만 있는 표기가 있으면 DB2 점수로 세지 않는다
        assertThat(detect("CREATE FUNCTION f() RETURNS int LANGUAGE SQL AS $$ SELECT 1 $$")).isEqualTo(Dialect.POSTGRES);
        assertThat(detect("SELECT a::int FROM t FETCH FIRST 1 ROWS ONLY")).isEqualTo(Dialect.POSTGRES);
        // Oracle 낱말이 더 많아도 Oracle이라고 하지 않는다(판정하지 않는다)
        assertThat(detect("SELECT NVL(a, 0), DECODE(b, 1, 2), SYSDATE, ROWNUM FROM DUAL WHERE c ILIKE 'x'")).isNull();
    }

    @Test
    @DisplayName("F-019-AC3 Oracle·DB2·MySQL 구문의 판정은 그대로다: seq.NEXTVAL·:=는 ORACLE, NEXT VALUE FOR는 DB2, MySQL과 겹치는 NOW()·LIMIT·->>·RETURNING은 점수가 없다")
    void existingJudgementsAreKept() {
        assertThat(detect("SELECT SEQ_ORDER.NEXTVAL FROM DUAL")).isEqualTo(Dialect.ORACLE);
        assertThat(detect("INSERT INTO T (ID) VALUES (SEQ_ORDER.NEXTVAL)")).isEqualTo(Dialect.ORACLE);
        assertThat(detect("BEGIN V := 1; END")).isEqualTo(Dialect.ORACLE);
        assertThat(detect("SELECT NEXT VALUE FOR SEQ_A FROM SYSIBM.SYSDUMMY1")).isEqualTo(Dialect.DB2);
        assertThat(detect("CREATE FUNCTION F() RETURNS INT LANGUAGE SQL RETURN 1")).isEqualTo(Dialect.DB2);
        assertThat(detect("SELECT SID, SERIAL# FROM V$SESSION WHERE ROWNUM = 1")).isEqualTo(Dialect.ORACLE);
        assertThat(detect("SELECT SERIAL, NVL(NM, '-') FROM T_DEVICE")).isEqualTo(Dialect.ORACLE);
        assertThat(detect("BEGIN DBMS_OUTPUT.PUT_LINE($$PLSQL_UNIT); END")).isEqualTo(Dialect.ORACLE);
        assertThat(detect("SELECT A$$B FROM T WHERE ROWNUM = 1")).isEqualTo(Dialect.ORACLE);
        // MySQL에도 있는 표기로는 PostgreSQL이라고 추측하지 않는다
        assertThat(detect("SELECT NOW() FROM T")).isNull();
        assertThat(detect("SELECT A FROM T LIMIT 10 OFFSET 5")).isNull();
        assertThat(detect("SELECT doc->>'$.name' FROM T")).isNull();
        assertThat(detect("DELETE FROM T WHERE ID = 1 RETURNING ID")).isNull();
        // MySQL 표기가 있으면 전처럼 판정하지 않는다
        assertThat(detect("SELECT a::text FROM T WHERE D < SYSDATE()")).isNull();
    }

    @Test
    @DisplayName("F-019-AC3 내장 소스의 구문 노드 dialect: 차례로 POSTGRES, POSTGRES, ORACLE, DB2, 키 없음. 생산자가 넘긴 attrs.dialect=postgresql도 POSTGRES다")
    void embeddedStatementsAreStamped() {
        try (TestProject project = TestProject.create().file("mapper/M.xml", "<mapper namespace=\"m\"/>")) {
            AnalysisContext ctx = project.context();
            String[] sqls = {"SELECT nextval('orders_id_seq')", "SELECT a::text FROM t WHERE b ILIKE #{q}", "SELECT NVL(a, 0) FROM t",
                "SELECT a FROM t FETCH FIRST 1 ROWS ONLY WITH UR", "SELECT a FROM t"};
            for (int i = 0; i < sqls.length; i++) {
                ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner(ctx, "s" + i), sqls[i], SourceLocation.of("mapper/M.xml", 1, 1, 1, 1), false,
                        Map.of()));
            }
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner(ctx, "byAttr"), "SELECT a FROM t", SourceLocation.of("mapper/M.xml", 1, 1, 1, 1),
                    false, Map.of("dialect", "postgresql")));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(node(g, Ids.mybatisStatement("m", "s0")).attrString("dialect")).isEqualTo("POSTGRES");
            assertThat(node(g, Ids.mybatisStatement("m", "s1")).attrString("dialect")).isEqualTo("POSTGRES");
            assertThat(node(g, Ids.mybatisStatement("m", "s2")).attrString("dialect")).isEqualTo("ORACLE");
            assertThat(node(g, Ids.mybatisStatement("m", "s3")).attrString("dialect")).isEqualTo("DB2");
            assertThat(node(g, Ids.mybatisStatement("m", "s4")).attr("dialect")).isNull();
            assertThat(node(g, Ids.mybatisStatement("m", "byAttr")).attrString("dialect")).isEqualTo("POSTGRES");
        }
    }

    private static String owner(AnalysisContext ctx, String id) {
        String owner = Ids.mybatisStatement("m", id);
        ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(id).location(SourceLocation.of("mapper/M.xml", 1)).build());
        return owner;
    }
}
