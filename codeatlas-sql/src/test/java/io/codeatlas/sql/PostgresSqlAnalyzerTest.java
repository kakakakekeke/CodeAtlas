package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

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
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL 읽기 방식으로 분석한 결과(F-019-R7, R9~R17): 읽기 방식이 정해진 뒤 렉서·분할기·파서를 거쳐 그래프에 남는 것까지 본다.
 * mybatis 모듈에 기대지 않고 생산자처럼 내장 소스를 직접 등록한다.
 */
class PostgresSqlAnalyzerTest {

    private static final String MAPPER = "src/main/resources/mapper/CustomerMapper.xml";

    /** F-019-AC7의 매퍼 구문. */
    private static final String JSON_PATH = "SELECT c.profile #>> '{a,b}' FROM customers c WHERE c.id = #{id}";

    private static String embed(AnalysisContext ctx, String statementId, Map<String, String> attrs, String sql) {
        String owner = Ids.mybatisStatement("com.a.CustomerMapper", statementId);
        ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(statementId).location(SourceLocation.of(MAPPER, 3)).build());
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, sql, SourceLocation.of(MAPPER, 3, 5, 3, 5), false, attrs));
        return owner;
    }

    /** 매퍼 구문 하나를 주어진 조건(옵션, 생산자가 넘긴 attrs, 프로젝트 파일)으로 분석한다. */
    private static GraphModel analyzeMapper(AnalysisOptions options, Map<String, String> attrs, String sql, String... files) {
        try (TestProject project = TestProject.create().file(MAPPER, "<mapper namespace=\"com.a.CustomerMapper\"/>")) {
            for (int i = 0; i + 1 < files.length; i += 2) {
                project.file(files[i], files[i + 1]);
            }
            if (options != null) {
                project.options(options);
            }
            AnalysisContext ctx = project.context();
            embed(ctx, "find", attrs, sql);
            return TestProject.run(ctx, new SqlAnalyzer()).graph();
        }
    }

    private static final String OWNER = Ids.mybatisStatement("com.a.CustomerMapper", "find");

    private static GraphModel analyzeFile(String path, String sql) {
        try (TestProject project = TestProject.create().file(path, sql)) {
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    private static List<String> statementIds(GraphModel g) {
        return g.nodes(NodeKind.SQL_STATEMENT).stream().map(Node::id).sorted().collect(Collectors.toList());
    }

    private static List<Diagnostic> problems(GraphModel g) {
        return g.diagnostics().stream()
                .filter(d -> d.code().equals(DiagnosticCodes.PARSE_FAILED) || d.code().equals(DiagnosticCodes.UNSUPPORTED_SYNTAX))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ F-019-AC7: 읽기 방식에서 그래프까지

    private static void assertJsonPathRead(GraphModel g, String when) {
        assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).as(when).isEmpty();
        assertThat(targets(g, OWNER, EdgeKind.READS)).as(when)
                .containsExactly("db:column:CUSTOMERS.ID", "db:column:CUSTOMERS.PROFILE", "db:table:CUSTOMERS");
        assertThat(node(g, OWNER).attrString("dialect")).as(when).isEqualTo("POSTGRES");
    }

    @Test
    @DisplayName("F-019-AC7 (가) 프로젝트 설정이 jdbc:postgresql이면 매퍼 구문의 #>>를 PARSE_FAILED 없이 읽어 CUSTOMERS.PROFILE·CUSTOMERS.ID 읽기를 기록한다")
    void jsonPathByProjectSetting() {
        assertJsonPathRead(analyzeMapper(null, Map.of(), JSON_PATH,
                "src/main/resources/application.properties", "spring.datasource.url=jdbc:postgresql://localhost/shop\n"), "프로젝트 설정");
    }

    @Test
    @DisplayName("F-019-AC7 (나) --dialect postgres면 같은 읽기가 기록된다")
    void jsonPathByOption() {
        assertJsonPathRead(analyzeMapper(AnalysisOptions.builder().dialect(Dialect.POSTGRES).build(), Map.of(), JSON_PATH), "--dialect postgres");
    }

    @Test
    @DisplayName("F-019-AC7 (다) 매퍼의 databaseId=postgresql(생산자가 넘긴 attrs.dialect)이면 같은 읽기가 기록된다")
    void jsonPathByProducerDialect() {
        assertJsonPathRead(analyzeMapper(null, Map.of("dialect", "postgresql"), JSON_PATH), "attrs.dialect");
    }

    @Test
    @DisplayName("F-019-AC7 (라) 아무 표시가 없어도 그 구문의 #>>가 PostgreSQL 내용 특징이라 PostgreSQL로 읽는다: 같은 읽기가 기록된다")
    void jsonPathByContent() {
        assertJsonPathRead(analyzeMapper(null, Map.of(), JSON_PATH), "표시 없음");
    }

    @Test
    @DisplayName("F-019-AC7 (마) --dialect oracle이면 기본 읽기 방식이다: #은 연산자가 아니라서 그 식을 읽지 못한다(전과 같다). 테이블 읽기는 남는다")
    void jsonPathWithOracleOption() {
        GraphModel g = analyzeMapper(AnalysisOptions.builder().dialect(Dialect.ORACLE).build(), Map.of(), JSON_PATH);
        assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).hasSize(1);
        assertThat(hasEdge(g, OWNER, EdgeKind.READS, "db:table:CUSTOMERS")).isTrue();
        // 기본 읽기 방식에서 #이 이름의 글자인 구문은 그대로 읽는다(F-019-R8)
        GraphModel named = analyzeMapper(AnalysisOptions.builder().dialect(Dialect.ORACLE).build(), Map.of(), "SELECT t.SERIAL#, t.A$B FROM T#1 t WHERE t.ID = #{id}");
        assertThat(named.diagnostics()).isEmpty();
        assertThat(targets(named, OWNER, EdgeKind.READS)).containsExactly("db:column:T#1.A$B", "db:column:T#1.ID", "db:column:T#1.SERIAL#", "db:table:T#1");
    }

    @Test
    @DisplayName("F-019-AC7 PostgreSQL 읽기 방식의 매퍼 구문: #>> 뒤에 #{..}가 와도, $1·E'..'·달러 인용 문자열이 있어도 PARSE_FAILED 없이 읽는다")
    void postgresNotationInMapper() {
        GraphModel g = analyzeMapper(null, Map.of("dialect", "postgres"),
                "SELECT c.profile #>> #{path}::text[] AS v, $$it's$$ AS memo FROM customers c WHERE c.email = E'a\\'b' AND c.id = $1 AND c.name = #{name}");
        assertThat(g.diagnostics()).isEmpty();
        assertThat(targets(g, OWNER, EdgeKind.READS)).containsExactly("db:column:CUSTOMERS.EMAIL", "db:column:CUSTOMERS.ID",
                "db:column:CUSTOMERS.NAME", "db:column:CUSTOMERS.PROFILE", "db:table:CUSTOMERS");
    }

    // ------------------------------------------------------------------ F-019-AC8: 달러 인용

    @Test
    @DisplayName("F-019-AC8 닫히지 않은 달러 인용: 진단 1건과 함께 분석이 끝나고 앞의 구문은 그대로 기록된다")
    void unterminatedDollarQuote() {
        GraphModel g = analyzeFile("db/postgres/fn.sql", "CREATE TABLE t (id int);\nDELETE FROM t WHERE id = 1;\n"
                + "CREATE FUNCTION f() RETURNS int AS $$ BEGIN DELETE FROM t;\nRETURN 1; END;\nSELECT id FROM ghost;\n");
        assertThat(node(g, "db:table:T").declared()).isTrue();
        assertThat(hasEdge(g, Ids.sqlStatement("db/postgres/fn.sql", 2), EdgeKind.WRITES, "db:table:T")).isTrue();
        assertThat(g.diagnostics()).hasSize(1);
        Diagnostic d = g.diagnostics().get(0);
        assertThat(d.code()).isEqualTo(DiagnosticCodes.PARSE_FAILED);
        assertThat(d.message()).contains("닫히지 않은 달러 인용");
        assertThat(d.location().file()).isEqualTo("db/postgres/fn.sql");
        assertThat(d.location().startLine()).isEqualTo(3);
        // 닫히지 않은 본문에 든 글자는 구문으로 읽지 않는다
        assertThat(g.hasNode("db:table:GHOST")).isFalse();
    }

    @Test
    @DisplayName("F-019-AC8 달러 인용 본문이 든 함수 뒤의 구문은 따로 분석된다: 본문의 ;에서 끊긴 조각이 구문 노드가 되지 않는다")
    void statementsAfterDollarQuotedFunction() {
        GraphModel g = analyzeFile("db/postgres/fn.sql",
                "CREATE FUNCTION f() RETURNS int AS $body$ BEGIN DELETE FROM t; RETURN 1; END; $body$ LANGUAGE plpgsql;\nSELECT a FROM u;\n");
        assertThat(g.nodes(NodeKind.DB_ROUTINE)).extracting(Node::name).containsExactly("F");
        assertThat(statementIds(g)).contains(Ids.sqlStatement("db/postgres/fn.sql", 2));
        assertThat(hasEdge(g, Ids.sqlStatement("db/postgres/fn.sql", 2), EdgeKind.READS, "db:table:U")).isTrue();
        // 본문의 조각(RETURN 1, END, $body$ LANGUAGE plpgsql)이 파일의 구문으로 읽히면 "지원하지 않는 구문"이 나온다
        assertThat(diagnostics(g, DiagnosticCodes.UNSUPPORTED_SYNTAX)).isEmpty();
    }

    // ------------------------------------------------------------------ F-019-AC10: COPY … FROM stdin

    private static final String DUMP = "COPY public.customers (id, email) FROM stdin;\n"
            + "1\ta@x.com\n"
            + "2\tSELECT x FROM ghost; 'unclosed\n"
            + "\\.\n"
            + "\n"
            + "SELECT pg_catalog.setval('public.orders_id_seq', 10, true);\n"
            + "\\connect shop\n"
            + "COPY t FROM '/tmp/a.csv';\n"
            + "COPY (SELECT id FROM customers) TO stdout;\n"
            + "COPY customers TO '/tmp/out.csv';\n";

    @Test
    @DisplayName("F-019-AC10 COPY … FROM stdin과 데이터 줄은 그 테이블의 데이터 적재 묶음(rowCount=데이터 줄 수)이다: 데이터 줄과 \\connect에서 나온 진단·구문 노드가 없고, 파일에서 읽는 COPY와 COPY … TO는 적재가 아니다")
    void copyFromStdinIsDataLoad() {
        for (String path : new String[] {"db/postgres/data.sql", "db/dump.sql"}) { // 경로의 방언 이름, 내용 특징(COPY … FROM stdin)
            GraphModel g = analyzeFile(path, DUMP);
            assertThat(problems(g)).as(path).isEmpty();
            assertThat(g.diagnostics()).as(path).allMatch(d -> d.severity() == Severity.INFO);
            Node load = node(g, Ids.sqlStatement(path, 1));
            assertThat(load.attr("dataLoad")).as(path).isEqualTo(true);
            assertThat(load.attr("rowCount")).as(path).isEqualTo(2);
            assertThat(load.attrString("statementType")).isEqualTo("INSERT");
            assertThat(load.attrString("sql")).isEqualTo("COPY public.customers (id, email) FROM stdin");
            assertThat(load.attrString("dialect")).as(path).isEqualTo("POSTGRES");
            assertThat(targets(g, load.id(), EdgeKind.WRITES)).as(path)
                    .containsExactly("db:column:CUSTOMERS.EMAIL", "db:column:CUSTOMERS.ID", "db:table:CUSTOMERS");
            assertThat(edge(g, load.id(), EdgeKind.WRITES, "db:table:CUSTOMERS").attrString("op")).isEqualTo("INSERT");
            assertThat(hasEdge(g, Ids.file(path), EdgeKind.CONTAINS, load.id())).isTrue();
            // 데이터 줄, \connect, 파일에서 읽는 COPY, COPY 테이블 TO는 노드를 만들지 않는다. COPY 뒤의 구문은 데이터에 먹히지 않고 따로 나뉜다.
            // COPY (쿼리) TO는 적재가 아니라 그 쿼리를 읽는 SELECT 구문이다(F-019-AC36)
            assertThat(g.hasNode("db:table:GHOST")).as(path).isFalse();
            assertThat(g.hasNode("db:table:T")).as(path).isFalse();
            assertThat(g.hasNode("db:table:SHOP")).as(path).isFalse();
            assertThat(statementIds(g)).as(path).containsExactly(Ids.sqlStatement(path, 1), Ids.sqlStatement(path, 9));
            assertThat(node(g, Ids.sqlStatement(path, 9)).attrs()).as(path).doesNotContainKey("dataLoad");
            assertThat(targets(g, Ids.sqlStatement(path, 9), EdgeKind.WRITES)).as(path).isEmpty();
            // pg_catalog.을 붙여 쓴 setval은 덤프의 뒷정리다: 시퀀스 참조를 만들지 않는다
            assertThat(g.hasNode("db:sequence:ORDERS_ID_SEQ")).as(path).isFalse();
            List<Diagnostic> grouped = diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED);
            assertThat(grouped).as(path).hasSize(1);
            assertThat(grouped.get(0).message()).contains("COPY 1건");
        }
    }

    @Test
    @DisplayName("F-019-AC10 같은 테이블의 COPY가 여럿이면 한 묶음에 줄 수를 더하고, 테이블마다 묶음이 하나다. 컬럼 목록이 없는 COPY도 묶음이다")
    void copyGroupsPerTable() {
        String path = "db/postgres/data.sql";
        GraphModel g = analyzeFile(path, "COPY orders (id, status) FROM stdin;\n1\tNEW\n\\.\nCOPY customers FROM stdin;\n1\ta\n2\tb\n3\tc\n\\.\n"
                + "COPY orders (id, amount) FROM stdin;\n2\t10\n3\t20\n\\.\n");
        assertThat(problems(g)).isEmpty();
        assertThat(statementIds(g)).containsExactly(Ids.sqlStatement(path, 1), Ids.sqlStatement(path, 4));
        assertThat(node(g, Ids.sqlStatement(path, 1)).attr("rowCount")).isEqualTo(3);
        assertThat(targets(g, Ids.sqlStatement(path, 1), EdgeKind.WRITES))
                .containsExactly("db:column:ORDERS.AMOUNT", "db:column:ORDERS.ID", "db:column:ORDERS.STATUS", "db:table:ORDERS");
        assertThat(node(g, Ids.sqlStatement(path, 4)).attr("rowCount")).isEqualTo(3);
        assertThat(targets(g, Ids.sqlStatement(path, 4), EdgeKind.WRITES)).containsExactly("db:table:CUSTOMERS");
    }

    // ------------------------------------------------------------------ F-019-AC36: COPY (쿼리) TO

    @Test
    @DisplayName("F-019-AC36 스크립트의 COPY (쿼리) TO ..는 그 줄의 SELECT 구문 노드다: 쿼리의 테이블·컬럼 읽기와 조인이 기록되고 진단이 없다. COPY 테이블 TO ..는 전처럼 노드가 없다")
    void copyQueryToIsSelectStatement() {
        String sql = "SET search_path = public;\n"
                + "COPY (SELECT o.id FROM orders o JOIN customers c ON c.id = o.customer_id) TO STDOUT;\n"
                + "COPY customers TO '/tmp/out.csv';\n"
                + "COPY (SELECT id, email FROM customers WHERE email IS NOT NULL) TO '/tmp/c.csv' WITH (FORMAT csv, HEADER);\n";
        for (String path : new String[] {"db/postgres/export.sql", "db/export.sql"}) { // 경로의 방언 이름, 내용 특징(SET search_path)
            GraphModel g = analyzeFile(path, sql);
            assertThat(g.diagnostics()).as(path).isEmpty();
            String copy = Ids.sqlStatement(path, 2);
            assertThat(statementIds(g)).as(path).containsExactly(copy, Ids.sqlStatement(path, 4));
            assertThat(node(g, copy).attrString("statementType")).as(path).isEqualTo("SELECT");
            assertThat(node(g, copy).attrString("dialect")).as(path).isEqualTo("POSTGRES");
            assertThat(node(g, copy).attrs()).as(path).doesNotContainKey("dataLoad");
            assertThat(targets(g, copy, EdgeKind.READS)).as(path).containsExactly("db:column:CUSTOMERS.ID", "db:column:ORDERS.CUSTOMER_ID",
                    "db:column:ORDERS.ID", "db:table:CUSTOMERS", "db:table:ORDERS");
            assertThat(targets(g, copy, EdgeKind.WRITES)).as(path).isEmpty();
            assertThat(g.edges("db:table:CUSTOMERS", EdgeKind.JOINS, "db:table:ORDERS").size() + g.edges("db:table:ORDERS", EdgeKind.JOINS, "db:table:CUSTOMERS").size())
                    .as(path).isEqualTo(1);
            assertThat(targets(g, Ids.sqlStatement(path, 4), EdgeKind.READS)).as(path)
                    .containsExactly("db:column:CUSTOMERS.EMAIL", "db:column:CUSTOMERS.ID", "db:table:CUSTOMERS");
        }
    }

    @Test
    @DisplayName("F-019-AC36 스크립트의 COPY (쿼리) TO에서 쿼리를 읽지 못하면 그 줄에 PARSE_FAILED가 남고 읽은 테이블은 기록된다(조용히 넘기지 않는다)")
    void copyQueryToWithUnreadableQueryIsReported() {
        String path = "db/postgres/export.sql";
        GraphModel g = analyzeFile(path, "SELECT 1;\nCOPY (SELECT o.id FROM orders o WHERE o.id = = 1 }) TO STDOUT;\n");
        List<Diagnostic> failed = diagnostics(g, DiagnosticCodes.PARSE_FAILED);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).location().file()).isEqualTo(path);
        assertThat(failed.get(0).location().startLine()).isEqualTo(2);
        assertThat(hasEdge(g, Ids.sqlStatement(path, 2), EdgeKind.READS, "db:table:ORDERS")).isTrue();
    }

    @Test
    @DisplayName("F-019-AC36 PostgreSQL 읽기 방식의 매퍼 구문 COPY (쿼리) TO ..도 그 쿼리의 읽기를 기록한다")
    void copyQueryToInMapper() {
        GraphModel g = analyzeMapper(null, Map.of("dialect", "postgres"), "COPY (SELECT c.id, c.email FROM customers c WHERE c.id = #{id}) TO STDOUT");
        assertThat(g.diagnostics()).isEmpty();
        assertThat(node(g, OWNER).attrString("statementType")).isEqualTo("SELECT");
        assertThat(targets(g, OWNER, EdgeKind.READS)).containsExactly("db:column:CUSTOMERS.EMAIL", "db:column:CUSTOMERS.ID", "db:table:CUSTOMERS");
    }

    // ------------------------------------------------------------------ F-019-AC15: 시퀀스 함수

    @Test
    @DisplayName("F-019-AC15 매퍼 구문의 nextval('seq')·setval('seq', ..)는 Oracle seq.NEXTVAL과 같은 엣지(READS, sequence=true)로 기록되고, 인자가 리터럴이 아니면 참조가 없다")
    void sequenceFunctionsMakeSameEdgeAsOracle() {
        try (TestProject project = TestProject.create().file(MAPPER, "<mapper namespace=\"com.a.CustomerMapper\"/>")) {
            AnalysisContext ctx = project.context();
            String select = embed(ctx, "nextId", Map.of(), "SELECT nextval('orders_id_seq')");
            String insert = embed(ctx, "insert", Map.of(), "INSERT INTO orders (id) VALUES (nextval('public.orders_id_seq'::regclass))");
            String setval = embed(ctx, "reset", Map.of(), "SELECT setval('s', 1)");
            String variable = embed(ctx, "byName", Map.of(), "SELECT nextval(v_name)");
            String oracle = embed(ctx, "oracle", Map.of(), "SELECT SEQ_A.NEXTVAL FROM DUAL");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            Edge expected = edge(g, oracle, EdgeKind.READS, "db:sequence:SEQ_A");
            for (String[] pair : new String[][] {{select, "ORDERS_ID_SEQ"}, {insert, "ORDERS_ID_SEQ"}, {setval, "S"}}) {
                Edge actual = edge(g, pair[0], EdgeKind.READS, "db:sequence:" + pair[1]);
                assertThat(actual.attrs().keySet()).as(pair[0]).isEqualTo(expected.attrs().keySet());
                assertThat(actual.attrs().get("sequence")).as(pair[0]).isEqualTo(true);
                assertThat(node(g, "db:sequence:" + pair[1]).kind()).isEqualTo(NodeKind.DB_SEQUENCE);
            }
            assertThat(targets(g, insert, EdgeKind.WRITES)).containsExactly("db:column:ORDERS.ID", "db:table:ORDERS");
            assertThat(g.outgoing(variable)).isEmpty();
            assertThat(g.nodes(NodeKind.DB_SEQUENCE)).extracting(Node::name).containsExactlyInAnyOrder("ORDERS_ID_SEQ", "S", "SEQ_A");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-019-AC15 컬럼 기본값의 nextval('…'::regclass)은 Oracle DEFAULT seq.NEXTVAL과 같은 꼴이다: 기본값 글자로만 남고 시퀀스 엣지를 만들지 않는다")
    void columnDefaultSequenceIsLikeOracle() {
        GraphModel oracle = analyzeFile("db/oracle/t.sql", "CREATE TABLE ORDERS (ID NUMBER DEFAULT SEQ_ORD.NEXTVAL NOT NULL, MEMO VARCHAR2(10));\n");
        GraphModel postgres = analyzeFile("db/postgres/t.sql",
                "CREATE TABLE orders (id bigint DEFAULT nextval('public.orders_id_seq'::regclass) NOT NULL, memo text);\n");
        assertThat(problems(postgres)).isEmpty();
        Node oracleColumn = node(oracle, "db:column:ORDERS.ID");
        Node postgresColumn = node(postgres, "db:column:ORDERS.ID");
        assertThat(postgresColumn.attrs().keySet()).isEqualTo(oracleColumn.attrs().keySet());
        assertThat(postgres.nodes(NodeKind.DB_SEQUENCE)).hasSameSizeAs(oracle.nodes(NodeKind.DB_SEQUENCE));
        assertThat(postgres.nodes(NodeKind.SQL_STATEMENT)).isEmpty();
        assertThat(node(postgres, "db:column:ORDERS.MEMO").declared()).isTrue();
    }

    // ------------------------------------------------------------------ F-019-AC17: 따옴표로 감싼 소문자 이름(매퍼 구문)

    @Test
    @DisplayName("F-019-AC17 PostgreSQL 읽기 방식의 매퍼 구문에서 \"orders\".\"status\"는 ORDERS.STATUS다. 기본 읽기 방식(--dialect oracle)에서는 전처럼 원문 그대로다")
    void quotedLowercaseNamesInMapperSql() {
        String ddl = "CREATE TABLE orders (id bigint NOT NULL, status text, \"user\" text);\n";
        String sql = "SELECT o.\"id\", o.\"status\" FROM \"orders\" o WHERE o.\"user\" = #{u}";
        GraphModel g = analyzeMapper(null, Map.of(), sql, "db/postgres/schema.sql", ddl);
        assertThat(g.diagnostics()).isEmpty();
        assertThat(targets(g, OWNER, EdgeKind.READS))
                .containsExactly("db:column:ORDERS.ID", "db:column:ORDERS.STATUS", "db:column:ORDERS.USER", "db:table:ORDERS");
        assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::id).containsExactly("db:table:ORDERS");

        GraphModel oracle = analyzeMapper(AnalysisOptions.builder().dialect(Dialect.ORACLE).build(), Map.of(), sql);
        assertThat(targets(oracle, OWNER, EdgeKind.READS)).contains("db:table:orders", "db:column:orders.id");
    }

    // ------------------------------------------------------------------ F-019-AC16: 시스템 카탈로그, FROM 없는 SELECT

    @Test
    @DisplayName("F-019-AC16 pg_class, pg_catalog.pg_tables, information_schema.columns의 테이블 노드에 systemCatalog=true가 붙고, SELECT now()는 테이블 참조도 진단도 없다")
    void systemCatalogsAndSelectWithoutFrom() {
        String path = "db/postgres/q.sql";
        GraphModel g = analyzeFile(path, """
                SELECT relname FROM pg_class;
                SELECT * FROM pg_catalog.pg_tables;
                SELECT * FROM information_schema.columns;
                SELECT now();
                SELECT p.id FROM pg_payment p;
                """);
        assertThat(g.diagnostics()).isEmpty();
        for (String name : new String[] {"PG_CLASS", "PG_TABLES", "COLUMNS"}) {
            assertThat(node(g, "db:table:" + name).attr("systemCatalog")).as(name).isEqualTo(true);
            assertThat(node(g, "db:table:" + name).declared()).as(name).isFalse();
        }
        assertThat(node(g, "db:table:PG_PAYMENT").attr("systemCatalog")).as("접두사만 같은 프로젝트의 테이블").isNull();
        Node now = node(g, Ids.sqlStatement(path, 4));
        assertThat(now.attrString("statementType")).isEqualTo("SELECT");
        assertThat(g.outgoing(now.id())).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC16 Oracle 매퍼 구문의 PG_STAT_DAILY·PG_SETTINGS(결제 PG사 테이블)는 F-019 이전처럼 시스템 카탈로그가 아니다: 스키마 없는 PG_ 이름 규칙은 PostgreSQL 읽기 방식에서만 쓴다")
    void pgPrefixedBusinessTablesInOracleMapperAreNotCatalogs() {
        try (TestProject project = TestProject.create().file(MAPPER, "<mapper namespace=\"com.a.CustomerMapper\"/>")) {
            AnalysisContext ctx = project.context();
            String daily = embed(ctx, "sumDaily", Map.of(), "SELECT NVL(SUM(AMT),0) FROM PG_STAT_DAILY WHERE STAT_DT = #{dt}");
            String settings = embed(ctx, "findPg", Map.of(), "SELECT PG_CD, MID FROM PG_SETTINGS WHERE USE_YN = 'Y' AND ROWNUM = 1");
            String byAttr = embed(ctx, "byAttr", Map.of("dialect", "oracle"), "SELECT T.TYPNAME FROM PG_TYPE T, PG_STATIO_LOG L WHERE L.ID = T.ID");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(g.diagnostics()).isEmpty();
            assertThat(targets(g, daily, EdgeKind.READS)).contains("db:table:PG_STAT_DAILY");
            assertThat(targets(g, settings, EdgeKind.READS)).contains("db:table:PG_SETTINGS", "db:column:PG_SETTINGS.PG_CD");
            assertThat(targets(g, byAttr, EdgeKind.READS)).contains("db:table:PG_TYPE", "db:table:PG_STATIO_LOG");
            for (String name : new String[] {"PG_STAT_DAILY", "PG_SETTINGS", "PG_TYPE", "PG_STATIO_LOG"}) {
                assertThat(node(g, "db:table:" + name).attr("systemCatalog")).as(name).isNull();
                assertThat(node(g, "db:table:" + name).attrs()).as(name).doesNotContainKey("systemCatalog");
            }
            assertThat(node(g, daily).attrString("dialect")).isEqualTo("ORACLE");
        }
    }

    @Test
    @DisplayName("F-019-AC16 기본 읽기 방식의 SQL 파일·PL/SQL 본문·익명 블록·동적 SQL·뷰 쿼리에서도 PG_ 이름은 업무 테이블이다. pg_catalog.·information_schema.로 한정한 참조는 읽기 방식과 무관하게 시스템 카탈로그다")
    void pgPrefixedNamesInDefaultSyntaxFiles() {
        String path = "db/oracle/pg.sql";
        GraphModel g = analyzeFile(path, """
                SELECT PG_CD FROM PG_SETTINGS WHERE USE_YN = 'Y';
                CREATE OR REPLACE VIEW V_PG AS SELECT C.RELNAME FROM PG_CLASS C;
                CREATE OR REPLACE PROCEDURE SP_PG IS
                  V_CNT NUMBER;
                BEGIN
                  SELECT COUNT(*) INTO V_CNT FROM PG_STAT_DAILY;
                  EXECUTE IMMEDIATE 'DELETE FROM PG_STAT_HIST WHERE DT < SYSDATE - 30';
                END;
                /
                BEGIN
                  DELETE FROM PG_TABLES WHERE ID = 1;
                  UPDATE PG_ROLES SET NM = 'X' WHERE ID = (SELECT MAX(ID) FROM PG_USER_MAP);
                END;
                /
                SELECT * FROM pg_catalog.pg_namespace;
                SELECT * FROM information_schema.schemata;
                """);
        assertThat(problems(g)).isEmpty();
        for (String name : new String[] {"PG_SETTINGS", "PG_CLASS", "PG_STAT_DAILY", "PG_STAT_HIST", "PG_TABLES", "PG_ROLES", "PG_USER_MAP"}) {
            assertThat(node(g, "db:table:" + name).attr("systemCatalog")).as(name).isNull();
        }
        for (String name : new String[] {"PG_NAMESPACE", "SCHEMATA"}) {
            assertThat(node(g, "db:table:" + name).attr("systemCatalog")).as(name).isEqualTo(true);
        }
    }

    @Test
    @DisplayName("F-019-AC16 PostgreSQL 읽기 방식의 모든 경로에서 스키마 없는 pg_ 카탈로그 이름은 시스템 카탈로그다: 매퍼 구문(attrs.dialect), 뷰 쿼리, PL/pgSQL 본문, EXECUTE 동적 SQL, LANGUAGE sql 본문, DO 블록")
    void catalogNamesInEveryPostgresPath() {
        try (TestProject project = TestProject.create().file(MAPPER, "<mapper namespace=\"com.a.CustomerMapper\"/>")
                .file("db/postgres/pg.sql", """
                        CREATE VIEW v_rel AS SELECT c.relname FROM pg_class c;
                        CREATE FUNCTION f_stat() RETURNS void LANGUAGE plpgsql AS $$
                        DECLARE
                            v_cnt bigint;
                        BEGIN
                            SELECT count(*) INTO v_cnt FROM pg_stat_activity;
                            EXECUTE 'SELECT count(*) FROM pg_locks' INTO v_cnt;
                            DELETE FROM pg_payment WHERE id = 1;
                        END $$;
                        CREATE FUNCTION f_sql() RETURNS bigint LANGUAGE sql AS $$ SELECT count(*) FROM pg_indexes $$;
                        DO $$ BEGIN PERFORM 1 FROM pg_roles; END $$;
                        """)) {
            AnalysisContext ctx = project.context();
            embed(ctx, "sizes", Map.of("dialect", "postgresql"), "SELECT s.relname FROM pg_statio_user_tables s JOIN pg_namespace n ON n.oid = s.relid");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(problems(g)).isEmpty();
            for (String name : new String[] {"PG_CLASS", "PG_STAT_ACTIVITY", "PG_LOCKS", "PG_INDEXES", "PG_ROLES", "PG_STATIO_USER_TABLES",
                "PG_NAMESPACE"}) {
                assertThat(node(g, "db:table:" + name).attr("systemCatalog")).as(name).isEqualTo(true);
            }
            assertThat(node(g, "db:table:PG_PAYMENT").attr("systemCatalog")).isNull();
        }
    }

    // ------------------------------------------------------------------ F-019-AC11~AC13: 그래프까지

    @Test
    @DisplayName("F-019-AC11 F-019-AC12 F-019-AC13 매퍼 구문의 ON CONFLICT, DELETE … USING, 데이터 변경 CTE가 그래프에 남는다: EXCLUDED·T·DEL 테이블 노드가 없다")
    void conflictUsingAndCteInGraph() {
        try (TestProject project = TestProject.create().file(MAPPER, "<mapper namespace=\"com.a.CustomerMapper\"/>")) {
            AnalysisContext ctx = project.context();
            String upsert = embed(ctx, "upsert", Map.of(),
                    "INSERT INTO customers (email, name) VALUES (#{e}, #{n}) ON CONFLICT (email) DO UPDATE SET name = EXCLUDED.name RETURNING id");
            String delete = embed(ctx, "deleteItems", Map.of(),
                    "DELETE FROM order_items oi USING orders o WHERE o.id = oi.order_id AND o.status = 'NEW'");
            String cte = embed(ctx, "tree", Map.of(),
                    "WITH RECURSIVE t AS (SELECT id FROM orders WHERE id = #{id} UNION ALL SELECT o.id FROM orders o JOIN t ON o.customer_id = t.id), "
                            + "del AS (DELETE FROM session_cache WHERE k = #{k} RETURNING k) SELECT t.id, (SELECT count(*) FROM del) FROM t");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            assertThat(problems(g)).isEmpty();
            assertThat(targets(g, upsert, EdgeKind.WRITES)).containsExactly("db:column:CUSTOMERS.EMAIL", "db:column:CUSTOMERS.NAME", "db:table:CUSTOMERS");
            assertThat(targets(g, delete, EdgeKind.WRITES)).containsExactly("db:table:ORDER_ITEMS");
            assertThat(targets(g, delete, EdgeKind.READS)).contains("db:table:ORDERS", "db:column:ORDERS.ID", "db:column:ORDERS.STATUS");
            assertThat(hasEdge(g, cte, EdgeKind.READS, "db:table:ORDERS")).isTrue();
            assertThat(targets(g, cte, EdgeKind.WRITES)).containsExactly("db:table:SESSION_CACHE");
            assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::name)
                    .containsExactlyInAnyOrder("CUSTOMERS", "ORDER_ITEMS", "ORDERS", "SESSION_CACHE");
            assertThat(g.nodes().stream().map(Node::id).filter(id -> id.contains("EXCLUDED"))).isEmpty();
            assertThat(node(g, upsert).attrString("dialect")).isEqualTo("POSTGRES");
        }
    }
}
