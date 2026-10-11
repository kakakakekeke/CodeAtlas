package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.node;
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
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL DDL(F-019-R18~R22): pg_dump 평문의 {@code ALTER TABLE ONLY}, {@code CREATE TABLE}의 수식어와 타입, {@code LIKE}·{@code INHERITS}·
 * {@code PARTITION OF}, 인덱스 문법, 덤프의 부속 구문. 파일을 분석해 그래프에 남는 것까지 본다.
 */
class PostgresDdlTest {

    private static final String PG = "db/postgres/schema.sql";

    /** 경로와 내용의 쌍들을 분석한다. */
    private static GraphModel analyze(String... files) {
        try (TestProject project = TestProject.create()) {
            for (int i = 0; i + 1 < files.length; i += 2) {
                project.file(files[i], files[i + 1]);
            }
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    private static List<Diagnostic> problems(GraphModel g) {
        return g.diagnostics().stream()
                .filter(d -> d.code().equals(DiagnosticCodes.PARSE_FAILED) || d.code().equals(DiagnosticCodes.UNSUPPORTED_SYNTAX))
                .collect(Collectors.toList());
    }

    private static List<String> tables(GraphModel g) {
        return g.nodes(NodeKind.DB_TABLE).stream().map(Node::name).sorted().collect(Collectors.toList());
    }

    /** 테이블의 컬럼 이름(순번 순). */
    private static List<String> columns(GraphModel g, String table) {
        return g.outgoing("db:table:" + table, EdgeKind.CONTAINS).stream().map(e -> node(g, e.to()))
                .filter(n -> n.kind() == NodeKind.DB_COLUMN)
                .sorted(java.util.Comparator.comparingInt(n -> ((Number) n.attr("ordinal")).intValue()))
                .map(Node::name).collect(Collectors.toList());
    }

    private static Node column(GraphModel g, String table, String column) {
        return node(g, "db:column:" + table + "." + column);
    }

    // ------------------------------------------------------------------ F-019-AC17: ALTER TABLE [IF EXISTS] [ONLY]

    private static final String AC17 = """
            CREATE TABLE public.customers (id bigint NOT NULL);
            CREATE TABLE public.orders (id bigint NOT NULL, customer_id bigint);
            ALTER TABLE ONLY public.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
            ALTER TABLE ONLY public.orders ADD CONSTRAINT fk FOREIGN KEY (customer_id) REFERENCES public.customers(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;
            ALTER TABLE IF EXISTS ONLY public.orders ADD COLUMN memo text;
            """;

    @Test
    @DisplayName("F-019-AC17 ALTER TABLE [IF EXISTS] [ONLY] 이름: ONLY·IF EXISTS는 수식어다. 기본 키·외래 키·컬럼 추가가 그 이름의 테이블에 붙고 ONLY·IF·EXISTS라는 테이블이 없다")
    void alterTableOnly() {
        GraphModel g = analyze(PG, AC17);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("CUSTOMERS", "ORDERS");
        assertThat(node(g, "db:table:ORDERS").attrList("primaryKey")).containsExactly("ID");
        assertThat(column(g, "ORDERS", "ID").attrBoolean("primaryKey")).isTrue();
        Edge fk = edge(g, "db:table:ORDERS", EdgeKind.REFERENCES, "db:table:CUSTOMERS");
        assertThat(fk.attrString("source")).isEqualTo("DDL");
        assertThat(fk.attrList("columns")).containsExactly("CUSTOMER_ID");
        assertThat(fk.attrList("refColumns")).containsExactly("ID");
        assertThat(fk.attrString("onDelete")).isEqualTo("CASCADE");
        assertThat(fk.attrString("constraint")).isEqualTo("FK");
        assertThat(g.edges("db:table:CUSTOMERS", EdgeKind.REFERENCES, "db:table:ORDERS")).isEmpty();
        assertThat(columns(g, "ORDERS")).containsExactly("ID", "CUSTOMER_ID", "MEMO");
        assertThat(column(g, "ORDERS", "MEMO").attrString("dataType")).isEqualTo("TEXT");
    }

    @Test
    @DisplayName("F-019-AC17 내용 특징으로 PostgreSQL로 판정한 파일(경로에 방언 이름 없음)에서도, 경로가 oracle이라 기본 읽기 방식으로 읽는 파일에서도 ONLY라는 테이블이 없다")
    void alterTableOnlyInAnyReading() {
        for (String path : new String[] {"db/01_schema.sql", "db/oracle/01_schema.sql"}) {
            GraphModel g = analyze(path, AC17);
            assertThat(tables(g)).as(path).containsExactly("CUSTOMERS", "ORDERS");
            assertThat(node(g, "db:table:ORDERS").attrList("primaryKey")).as(path).containsExactly("ID");
            assertThat(g.edges("db:table:ORDERS", EdgeKind.REFERENCES, "db:table:CUSTOMERS")).as(path).hasSize(1);
        }
    }

    /** 따옴표로 감싼 소문자 이름으로 만든 테이블과, 따옴표 없이 그 테이블을 쓰는 함수. */
    private static final String QUOTED_LOWERCASE = """
            CREATE TABLE public."orders" ("id" bigint NOT NULL, "status" text, "user" text);
            ALTER TABLE ONLY public."orders" ADD CONSTRAINT orders_pkey PRIMARY KEY ("id");
            CREATE FUNCTION public.f(p bigint) RETURNS void LANGUAGE plpgsql AS $$
            BEGIN
              UPDATE orders SET status = 'X' WHERE id = p;
            END $$;
            """;

    @Test
    @DisplayName("F-019-AC17 PostgreSQL에서 따옴표로 감싼 소문자 이름은 따옴표 없는 이름과 같은 테이블이다: \"orders\"의 정의·기본 키와 orders에 대한 쓰기가 db:table:ORDERS 하나에 모인다")
    void quotedLowercaseNameIsTheUnquotedName() {
        GraphModel g = analyze(PG, QUOTED_LOWERCASE);
        assertThat(g.diagnostics()).isEmpty();
        assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::id).containsExactly("db:table:ORDERS");
        Node orders = node(g, "db:table:ORDERS");
        assertThat(orders.declared()).isTrue();
        assertThat(orders.attrList("primaryKey")).containsExactly("ID");
        assertThat(columns(g, "ORDERS")).containsExactly("ID", "STATUS", "USER");
        assertThat(g.nodes(NodeKind.DB_COLUMN)).extracting(Node::id)
                .containsExactlyInAnyOrder("db:column:ORDERS.ID", "db:column:ORDERS.STATUS", "db:column:ORDERS.USER");
        assertThat(g.nodes(NodeKind.DB_COLUMN)).allMatch(Node::declared);
        String update = "sql:stmt:F:5";
        assertThat(edge(g, update, EdgeKind.WRITES, "db:table:ORDERS").attrString("op")).isEqualTo("UPDATE");
        assertThat(GraphAssert.targets(g, update, EdgeKind.WRITES)).containsExactly("db:column:ORDERS.STATUS", "db:table:ORDERS");
        assertThat(GraphAssert.targets(g, update, EdgeKind.READS)).containsExactly("db:column:ORDERS.ID");
    }

    @Test
    @DisplayName("F-019-AC17 대문자가 든 따옴표 이름(\"OrderItems\", \"QTY\")은 PostgreSQL에서도 원문 그대로이고, 기본 읽기 방식(oracle 경로)의 따옴표 소문자 이름은 전처럼 원문 그대로다(F-019-R8)")
    void quotedNamesWithUppercaseAndOtherSyntaxesStay() {
        GraphModel g = analyze(PG, "CREATE TABLE \"OrderItems\" (\"orderId\" bigint, \"QTY\" integer, \"memo\" text);\n");
        assertThat(tables(g)).containsExactly("OrderItems");
        assertThat(columns(g, "OrderItems")).containsExactly("orderId", "QTY", "MEMO");

        GraphModel oracle = analyze("db/oracle/schema.sql", "CREATE TABLE \"orders\" (\"id\" NUMBER(10), NM VARCHAR2(10));\n");
        assertThat(tables(oracle)).containsExactly("orders");
        assertThat(columns(oracle, "orders")).containsExactly("id", "NM");
    }

    @Test
    @DisplayName("F-019-AC17 F-019-AC15 따옴표 소문자 이름이 닿는 다른 경로: 시퀀스 정의와 nextval('\"seq\"'), 인덱스·외래 키·COMMENT의 이름, 뷰의 쿼리")
    void quotedLowercaseNamesInOtherPlaces() {
        GraphModel g = analyze(PG, """
                CREATE SEQUENCE public."orders_id_seq";
                CREATE SEQUENCE public."Audit_Seq";
                CREATE TABLE "customers" ("id" bigint PRIMARY KEY, "email" text);
                CREATE TABLE orders (id bigint DEFAULT nextval('public."orders_id_seq"'::regclass), "customer_id" bigint REFERENCES "customers" ("id"), memo text);
                CREATE INDEX "idx_orders_customer" ON "orders" ("customer_id");
                COMMENT ON COLUMN "orders"."memo" IS '메모';
                CREATE VIEW "v_orders" AS SELECT o."id", c."email" FROM "orders" o JOIN "customers" c ON c."id" = o."customer_id";
                INSERT INTO "orders" ("id", "customer_id") VALUES (nextval('"orders_id_seq"'), 1);
                SELECT nextval('"Audit_Seq"');
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactlyInAnyOrder("CUSTOMERS", "ORDERS");
        assertThat(g.nodes(NodeKind.DB_VIEW)).extracting(Node::name).containsExactly("V_ORDERS");
        assertThat(g.nodes(NodeKind.DB_COLUMN)).allMatch(Node::declared);
        assertThat(g.nodes(NodeKind.DB_SEQUENCE)).extracting(Node::id).containsExactlyInAnyOrder("db:sequence:ORDERS_ID_SEQ", "db:sequence:Audit_Seq");
        assertThat(g.nodes(NodeKind.DB_SEQUENCE)).allMatch(Node::declared);
        assertThat(g.edges("db:table:ORDERS", EdgeKind.REFERENCES, "db:table:CUSTOMERS")).hasSize(1);
        assertThat(column(g, "ORDERS", "MEMO").attrString("comment")).isEqualTo("메모");
        assertThat(GraphAssert.targets(g, Ids.sqlStatement(PG, 8), EdgeKind.READS)).contains("db:sequence:ORDERS_ID_SEQ");
        assertThat(GraphAssert.targets(g, Ids.sqlStatement(PG, 9), EdgeKind.READS)).containsExactly("db:sequence:Audit_Seq");
        assertThat(GraphAssert.targets(g, Ids.sqlStatement(PG, 7), EdgeKind.READS))
                .contains("db:table:ORDERS", "db:table:CUSTOMERS", "db:column:ORDERS.CUSTOMER_ID", "db:column:CUSTOMERS.EMAIL");
    }

    @Test
    @DisplayName("F-019-AC17 ONLY·IF가 정말 테이블 이름이면(바로 뒤가 ADD 같은 동작 키워드) 전처럼 그 테이블에 적용한다(F-019-R8)")
    void tableNamedOnlyStaysATable() {
        GraphModel g = analyze("db/oracle/t.sql", "ALTER TABLE ONLY ADD (X NUMBER);\nALTER TABLE T2 ADD (Y NUMBER);\n");
        assertThat(columns(g, "ONLY")).containsExactly("X");
        assertThat(columns(g, "T2")).containsExactly("Y");
    }

    @Test
    @DisplayName("F-019-AC17 외래 키의 MATCH ..·ON UPDATE ..·NOT VALID·DEFERRABLE·INITIALLY ..는 받아 넘긴다: ON UPDATE의 동작을 onDelete로 읽지 않고, 뒤의 컬럼·제약이 깨지지 않는다")
    void foreignKeyOptions() {
        GraphModel g = analyze(PG, """
                CREATE TABLE customers (id bigint PRIMARY KEY);
                CREATE TABLE products (id integer PRIMARY KEY);
                CREATE TABLE order_items (
                    id bigint NOT NULL,
                    order_id bigint NOT NULL,
                    product_id integer REFERENCES products (id) MATCH SIMPLE ON UPDATE CASCADE ON DELETE RESTRICT NOT DEFERRABLE,
                    qty integer NOT NULL
                );
                CREATE TABLE orders (id bigint PRIMARY KEY, customer_id bigint, referrer_id bigint,
                    CONSTRAINT fk_ref FOREIGN KEY (referrer_id) REFERENCES customers (id) MATCH FULL ON DELETE SET NULL DEFERRABLE INITIALLY IMMEDIATE,
                    memo text);
                ALTER TABLE ONLY public.order_items
                    ADD CONSTRAINT order_items_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id) ON DELETE CASCADE NOT VALID;
                ALTER TABLE ONLY public.orders
                    ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customers(id) ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED;
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("CUSTOMERS", "ORDERS", "ORDER_ITEMS", "PRODUCTS");
        assertThat(columns(g, "ORDER_ITEMS")).containsExactly("ID", "ORDER_ID", "PRODUCT_ID", "QTY");
        assertThat(columns(g, "ORDERS")).containsExactly("ID", "CUSTOMER_ID", "REFERRER_ID", "MEMO");
        assertThat(edge(g, "db:table:ORDER_ITEMS", EdgeKind.REFERENCES, "db:table:PRODUCTS").attrString("onDelete")).isEqualTo("RESTRICT");
        assertThat(edge(g, "db:table:ORDER_ITEMS", EdgeKind.REFERENCES, "db:table:ORDERS").attrString("onDelete")).isEqualTo("CASCADE");
        List<Edge> toCustomers = g.edges("db:table:ORDERS", EdgeKind.REFERENCES, "db:table:CUSTOMERS");
        assertThat(toCustomers).hasSize(2);
        Edge byReferrer = toCustomers.stream().filter(e -> e.attrList("columns").contains("REFERRER_ID")).findFirst().orElseThrow();
        Edge byCustomer = toCustomers.stream().filter(e -> e.attrList("columns").contains("CUSTOMER_ID")).findFirst().orElseThrow();
        assertThat(byReferrer.attrString("onDelete")).isEqualTo("SET NULL");
        assertThat(byCustomer.attrs()).doesNotContainKey("onDelete"); // ON UPDATE CASCADE는 onDelete가 아니다
    }

    @Test
    @DisplayName("F-019-AC17 pg_dump의 ALTER TABLE 꼴: OWNER TO는 테이블을 만들지 않고(시퀀스·뷰의 소유자도 이 꼴로 나온다), ALTER COLUMN .. ADD GENERATED .. AS IDENTITY는 identity다. ADD COLUMN IF NOT EXISTS의 IF는 컬럼이 아니다")
    void pgDumpAlterTableForms() {
        GraphModel g = analyze(PG, """
                CREATE TABLE public.payments (id bigint NOT NULL, amount numeric(12,2));
                CREATE SEQUENCE public.payments_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
                ALTER TABLE public.payments OWNER TO shop;
                ALTER TABLE public.payments_id_seq OWNER TO shop;
                ALTER TABLE public.payments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
                    SEQUENCE NAME public.payments_id_seq2
                    START WITH 1
                    INCREMENT BY 1
                    NO MINVALUE
                    NO MAXVALUE
                    CACHE 1
                );
                ALTER TABLE ONLY public.payments ALTER COLUMN amount SET DEFAULT 0;
                ALTER TABLE ONLY public.payments ALTER COLUMN amount TYPE numeric(14,2);
                ALTER TABLE public.payments ADD COLUMN IF NOT EXISTS memo text;
                ALTER TABLE ONLY public.payments REPLICA IDENTITY FULL;
                ALTER TABLE public.payments ENABLE ROW LEVEL SECURITY;
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("PAYMENTS");
        assertThat(columns(g, "PAYMENTS")).containsExactly("ID", "AMOUNT", "MEMO");
        assertThat(column(g, "PAYMENTS", "ID").attrBoolean("identity")).isTrue();
        assertThat(column(g, "PAYMENTS", "AMOUNT").attrString("defaultValue")).isEqualTo("0");
        assertThat(column(g, "PAYMENTS", "AMOUNT").attrString("dataType")).isEqualTo("NUMERIC(14,2)");
        assertThat(g.nodes(NodeKind.DB_SEQUENCE)).extracting(Node::name).containsExactly("PAYMENTS_ID_SEQ");
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).isEmpty();
    }

    // ------------------------------------------------------------------ F-019-AC18: CREATE TABLE 수식어, serial, 타입 텍스트

    @Test
    @DisplayName("F-019-AC18 CREATE UNLOGGED TABLE IF NOT EXISTS, CREATE TEMP TABLE: 테이블 정의로 기록되고 serial 컬럼은 identity=true, 배열·여러 단어 타입은 타입 텍스트 그대로다")
    void createTableModifiersAndTypes() {
        GraphModel g = analyze(PG, """
                CREATE UNLOGGED TABLE IF NOT EXISTS session_cache (k text PRIMARY KEY, v bytea);
                CREATE TEMP TABLE tmp_x (id serial, tags text[], at timestamp with time zone DEFAULT now());
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("SESSION_CACHE", "TMP_X");
        Node cache = node(g, "db:table:SESSION_CACHE");
        assertThat(cache.declared()).isTrue();
        assertThat(cache.attrs()).doesNotContainKey("temporary"); // UNLOGGED는 임시 테이블이 아니다
        assertThat(cache.attrList("primaryKey")).containsExactly("K");
        assertThat(columns(g, "SESSION_CACHE")).containsExactly("K", "V");
        assertThat(column(g, "SESSION_CACHE", "V").attrString("dataType")).isEqualTo("BYTEA");
        Node tmp = node(g, "db:table:TMP_X");
        assertThat(tmp.declared()).isTrue();
        assertThat(tmp.attrBoolean("temporary")).isTrue();
        assertThat(columns(g, "TMP_X")).containsExactly("ID", "TAGS", "AT");
        assertThat(column(g, "TMP_X", "ID").attrBoolean("identity")).isTrue();
        assertThat(column(g, "TMP_X", "ID").attrString("dataType")).isEqualTo("SERIAL");
        assertThat(column(g, "TMP_X", "TAGS").attrString("dataType")).isEqualTo("TEXT[]");
        assertThat(column(g, "TMP_X", "TAGS").attrs()).doesNotContainKey("identity");
        assertThat(column(g, "TMP_X", "AT").attrString("dataType")).isEqualTo("TIMESTAMP WITH TIME ZONE");
        assertThat(column(g, "TMP_X", "AT").attrString("defaultValue")).isEqualTo("NOW()");
    }

    @Test
    @DisplayName("F-019-AC18 [GLOBAL|LOCAL] TEMP|TEMPORARY, serial 류 여섯 가지, 타입 뒤의 캐스트가 든 기본값: 타입 텍스트에 기본값의 캐스트가 섞이지 않는다")
    void serialFamilyAndTypeTexts() {
        GraphModel g = analyze(PG, """
                CREATE GLOBAL TEMPORARY TABLE g_tmp (a smallserial, b bigserial);
                CREATE LOCAL TEMP TABLE l_tmp (a serial2, b serial4, c serial8, d integer);
                CREATE TABLE public.types (
                    c1 character varying(120) NOT NULL,
                    c2 numeric(12,2)[] DEFAULT '{}'::numeric[],
                    c3 double precision,
                    c4 timestamp(3) without time zone,
                    c5 public.order_status DEFAULT 'NEW'::public.order_status NOT NULL,
                    c6 jsonb DEFAULT '{}'::jsonb NOT NULL,
                    c7 timestamp with time zone DEFAULT (now() + '00:30:00'::interval) NOT NULL,
                    c8 integer[][],
                    c9 bit varying(8),
                    c10 time with time zone,
                    c11 bigint NOT NULL GENERATED BY DEFAULT AS IDENTITY,
                    c12 text[3] NOT NULL,
                    c13 integer[] DEFAULT ARRAY[1, 2] NOT NULL,
                    CONSTRAINT types_c8_check CHECK ((c8 IS NOT NULL)),
                    UNIQUE NULLS NOT DISTINCT (c1, c3)
                );
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(node(g, "db:table:G_TMP").attrBoolean("temporary")).isTrue();
        assertThat(node(g, "db:table:L_TMP").attrBoolean("temporary")).isTrue();
        for (String c : new String[] {"G_TMP.A", "G_TMP.B", "L_TMP.A", "L_TMP.B", "L_TMP.C"}) {
            assertThat(node(g, "db:column:" + c).attrBoolean("identity")).as(c).isTrue();
        }
        assertThat(column(g, "L_TMP", "D").attrs()).doesNotContainKey("identity");
        assertThat(columns(g, "TYPES")).containsExactly("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11", "C12", "C13");
        assertThat(node(g, "db:table:TYPES").attrList("uniqueKeys")).containsExactly("C1,C3");
        assertThat(column(g, "TYPES", "C2").attrString("defaultValue")).isEqualTo("'{}'::NUMERIC[]");
        assertThat(column(g, "TYPES", "C5").attrString("defaultValue")).isEqualTo("'NEW'::PUBLIC.ORDER_STATUS");
        assertThat(column(g, "TYPES", "C7").attrString("defaultValue")).isEqualTo("(NOW() + '00:30:00'::INTERVAL)");
        assertThat(column(g, "TYPES", "C13").attrString("defaultValue")).isEqualTo("ARRAY[1,2]");
        assertThat(column(g, "TYPES", "C13").attr("nullable")).isEqualTo(false);
        assertThat(column(g, "TYPES", "C1").attrString("dataType")).isEqualTo("CHARACTER VARYING(120)");
        assertThat(column(g, "TYPES", "C2").attrString("dataType")).isEqualTo("NUMERIC(12,2)[]");
        assertThat(column(g, "TYPES", "C3").attrString("dataType")).isEqualTo("DOUBLE PRECISION");
        assertThat(column(g, "TYPES", "C4").attrString("dataType")).isEqualTo("TIMESTAMP(3) WITHOUT TIME ZONE");
        assertThat(column(g, "TYPES", "C5").attrString("dataType")).isEqualTo("PUBLIC.ORDER_STATUS");
        assertThat(column(g, "TYPES", "C5").attr("nullable")).isEqualTo(false);
        assertThat(column(g, "TYPES", "C6").attrString("dataType")).isEqualTo("JSONB");
        assertThat(column(g, "TYPES", "C6").attr("nullable")).isEqualTo(false);
        assertThat(column(g, "TYPES", "C7").attrString("dataType")).isEqualTo("TIMESTAMP WITH TIME ZONE");
        assertThat(column(g, "TYPES", "C7").attr("nullable")).isEqualTo(false);
        assertThat(column(g, "TYPES", "C8").attrString("dataType")).isEqualTo("INTEGER[][]");
        assertThat(column(g, "TYPES", "C9").attrString("dataType")).isEqualTo("BIT VARYING(8)");
        assertThat(column(g, "TYPES", "C10").attrString("dataType")).isEqualTo("TIME WITH TIME ZONE");
        assertThat(column(g, "TYPES", "C11").attrBoolean("identity")).isTrue();
        assertThat(column(g, "TYPES", "C12").attrString("dataType")).isEqualTo("TEXT[3]");
        assertThat(column(g, "TYPES", "C12").attr("nullable")).isEqualTo(false);
    }

    // ------------------------------------------------------------------ F-019-AC19: LIKE, INHERITS, PARTITION OF

    private static final String ORDERS = "CREATE TABLE orders (id bigint NOT NULL, amount numeric(12,2) DEFAULT 0);\n";

    private static final String DERIVED = """
            CREATE TABLE audit_log (LIKE public.orders INCLUDING ALL);
            CREATE TABLE vip_orders (grade int) INHERITS (orders);
            CREATE TABLE orders_2026 PARTITION OF orders FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
            CREATE TABLE ghost_copy (LIKE ghost);
            """;

    private static void assertDerived(GraphModel g, String when) {
        assertThat(problems(g)).as(when).isEmpty();
        assertThat(tables(g)).as(when).containsExactly("AUDIT_LOG", "GHOST_COPY", "ORDERS", "ORDERS_2026", "VIP_ORDERS");
        assertThat(columns(g, "AUDIT_LOG")).as(when).containsExactly("ID", "AMOUNT");
        assertThat(columns(g, "VIP_ORDERS")).as(when).containsExactly("ID", "AMOUNT", "GRADE");
        assertThat(columns(g, "ORDERS_2026")).as(when).containsExactly("ID", "AMOUNT");
        assertThat(node(g, "db:table:ORDERS_2026").attrString("partitionOf")).as(when).isEqualTo("ORDERS");
        assertThat(node(g, "db:table:ORDERS").attrs()).as(when).doesNotContainKey("partitionOf");
        assertThat(node(g, "db:table:VIP_ORDERS").attrs()).as(when).doesNotContainKey("partitionOf");
        assertThat(columns(g, "GHOST_COPY")).as(when).isEmpty();
        assertThat(node(g, "db:table:GHOST_COPY").declared()).as(when).isTrue();
        // 정의가 없는 원본은 노드도 관계도 만들지 않는다. 키워드가 테이블·컬럼이 되지 않는다
        assertThat(g.hasNode("db:table:GHOST")).as(when).isFalse();
        assertThat(g.hasNode("db:table:LIKE")).as(when).isFalse();
        assertThat(g.hasNode("db:table:INCLUDING")).as(when).isFalse();
        assertThat(g.edges().stream().filter(e -> e.kind() == EdgeKind.REFERENCES)).as(when).isEmpty();
        // 가져온 컬럼은 원본의 타입과 NOT NULL을 갖는다
        assertThat(column(g, "AUDIT_LOG", "ID").attrString("dataType")).as(when).isEqualTo("BIGINT");
        assertThat(column(g, "AUDIT_LOG", "ID").attr("nullable")).as(when).isEqualTo(false);
        assertThat(column(g, "VIP_ORDERS", "AMOUNT").attrString("dataType")).as(when).isEqualTo("NUMERIC(12,2)");
        assertThat(column(g, "ORDERS_2026", "AMOUNT").attrString("dataType")).as(when).isEqualTo("NUMERIC(12,2)");
        assertThat(column(g, "VIP_ORDERS", "GRADE").attrString("dataType")).as(when).isEqualTo("INT");
    }

    @Test
    @DisplayName("F-019-AC19 LIKE·INHERITS·PARTITION OF: 원본·부모의 컬럼이 자기 컬럼 앞에 들어가고 파티션에는 partitionOf가 붙는다. 정의가 없는 ghost를 LIKE한 테이블은 컬럼이 없다")
    void likeInheritsPartitionOf() {
        assertDerived(analyze(PG, ORDERS + DERIVED), "같은 파일, 원본이 앞");
    }

    @Test
    @DisplayName("F-019-AC19 원본·부모의 정의가 뒤에 있거나 다른 파일(경로상 뒤)에 있어도 같은 분석 안이면 컬럼을 넣는다")
    void sourceDefinedLater() {
        assertDerived(analyze(PG, DERIVED + ORDERS), "같은 파일, 원본이 뒤");
        assertDerived(analyze("db/postgres/01_derived.sql", DERIVED, "db/postgres/02_orders.sql", ORDERS), "원본이 뒤 파일");
    }

    @Test
    @DisplayName("F-019-AC19 LIKE는 놓인 자리에 원본의 컬럼을 넣고 INCLUDING DEFAULTS·INDEXES가 있을 때만 기본값·기본 키를 가져온다. 자기 컬럼의 제약과 PARTITION BY 절은 컬럼을 깨지 않는다")
    void likeOptionsAndPosition() {
        GraphModel g = analyze(PG, """
                CREATE TABLE public.orders (
                    id bigint NOT NULL PRIMARY KEY,
                    status text DEFAULT 'NEW' NOT NULL,
                    memo text
                );
                CREATE TABLE public.orders_archive (
                    LIKE public.orders INCLUDING DEFAULTS INCLUDING CONSTRAINTS,
                    archived_at timestamp with time zone DEFAULT now() NOT NULL
                );
                CREATE TABLE plain_copy (copied_at date, LIKE orders, note text);
                CREATE TABLE full_copy (LIKE orders INCLUDING ALL EXCLUDING DEFAULTS);
                CREATE TABLE public.order_events (
                    order_id bigint NOT NULL,
                    created_at timestamp with time zone DEFAULT now() NOT NULL
                )
                PARTITION BY RANGE (created_at);
                CREATE TABLE public.order_events_2026 PARTITION OF public.order_events
                    FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
                CREATE TABLE public.order_events_default PARTITION OF public.order_events (
                    CONSTRAINT order_id_positive CHECK (order_id > 0)
                ) DEFAULT;
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(columns(g, "ORDERS_ARCHIVE")).containsExactly("ID", "STATUS", "MEMO", "ARCHIVED_AT");
        assertThat(node(g, "db:table:ORDERS_ARCHIVE").attrs()).doesNotContainKey("primaryKey"); // INCLUDING INDEXES가 없다
        assertThat(column(g, "ORDERS_ARCHIVE", "ID").attr("primaryKey")).isEqualTo(false);
        assertThat(column(g, "ORDERS_ARCHIVE", "ID").attr("nullable")).isEqualTo(false);
        assertThat(column(g, "ORDERS_ARCHIVE", "STATUS").attrString("defaultValue")).isEqualTo("'NEW'");
        assertThat(column(g, "ORDERS_ARCHIVE", "ARCHIVED_AT").attr("ordinal")).isEqualTo(4);
        assertThat(columns(g, "PLAIN_COPY")).containsExactly("COPIED_AT", "ID", "STATUS", "MEMO", "NOTE");
        assertThat(column(g, "PLAIN_COPY", "STATUS").attrs()).doesNotContainKey("defaultValue");
        assertThat(column(g, "PLAIN_COPY", "STATUS").attr("nullable")).isEqualTo(false);
        assertThat(node(g, "db:table:FULL_COPY").attrList("primaryKey")).containsExactly("ID");
        assertThat(column(g, "FULL_COPY", "STATUS").attrs()).doesNotContainKey("defaultValue");
        assertThat(columns(g, "ORDER_EVENTS")).containsExactly("ORDER_ID", "CREATED_AT");
        assertThat(node(g, "db:table:ORDER_EVENTS").attrs()).doesNotContainKey("partitionOf");
        assertThat(columns(g, "ORDER_EVENTS_2026")).containsExactly("ORDER_ID", "CREATED_AT");
        assertThat(node(g, "db:table:ORDER_EVENTS_2026").attrString("partitionOf")).isEqualTo("ORDER_EVENTS");
        assertThat(columns(g, "ORDER_EVENTS_DEFAULT")).containsExactly("ORDER_ID", "CREATED_AT");
        assertThat(node(g, "db:table:ORDER_EVENTS_DEFAULT").attrString("partitionOf")).isEqualTo("ORDER_EVENTS");
        assertThat(g.hasNode("db:table:RANGE")).isFalse();
        assertThat(g.hasNode("db:column:ORDER_EVENTS_DEFAULT.CONSTRAINT")).isFalse();
        // 가져온 컬럼의 위치는 자기 정의 파일의 그 절이다
        assertThat(column(g, "ORDERS_ARCHIVE", "ID").location().file()).isEqualTo(PG);
        assertThat(column(g, "ORDERS_ARCHIVE", "ID").location().startLine()).isEqualTo(7);
    }

    @Test
    @DisplayName("F-019-AC19 pg_dump의 ALTER TABLE [ONLY] 부모 ATTACH PARTITION 자식 FOR VALUES ..: 자식에 partitionOf가 붙고 컬럼은 자식 자신의 정의 그대로다")
    void attachPartition() {
        GraphModel g = analyze(PG, """
                CREATE TABLE public.order_events (
                    order_id bigint NOT NULL,
                    created_at timestamp with time zone DEFAULT now() NOT NULL
                )
                PARTITION BY RANGE (created_at);
                CREATE TABLE public.order_events_2026 (
                    order_id bigint NOT NULL,
                    created_at timestamp with time zone DEFAULT now() NOT NULL,
                    note text
                );
                CREATE TABLE public.order_events_rest (order_id bigint NOT NULL, created_at timestamp with time zone);
                ALTER TABLE ONLY public.order_events ATTACH PARTITION public.order_events_2026
                    FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
                ALTER TABLE order_events ATTACH PARTITION order_events_rest DEFAULT;
                ALTER TABLE ONLY public.order_events ATTACH PARTITION public.ghost_part FOR VALUES IN ('x');
                ALTER TABLE ONLY public.order_events ADD CONSTRAINT order_events_pkey PRIMARY KEY (order_id, created_at);
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("ORDER_EVENTS", "ORDER_EVENTS_2026", "ORDER_EVENTS_REST");
        assertThat(node(g, "db:table:ORDER_EVENTS_2026").attrString("partitionOf")).isEqualTo("ORDER_EVENTS");
        assertThat(node(g, "db:table:ORDER_EVENTS_REST").attrString("partitionOf")).isEqualTo("ORDER_EVENTS");
        assertThat(node(g, "db:table:ORDER_EVENTS").attrs()).doesNotContainKey("partitionOf");
        // 컬럼은 자식 자신의 정의 그대로다(부모의 컬럼을 다시 넣지 않는다)
        assertThat(columns(g, "ORDER_EVENTS_2026")).containsExactly("ORDER_ID", "CREATED_AT", "NOTE");
        assertThat(columns(g, "ORDER_EVENTS_REST")).containsExactly("ORDER_ID", "CREATED_AT");
        assertThat(columns(g, "ORDER_EVENTS")).containsExactly("ORDER_ID", "CREATED_AT");
        // ATTACH 뒤의 구문은 그대로 적용된다. 정의가 없는 자식은 노드를 만들지 않는다
        assertThat(node(g, "db:table:ORDER_EVENTS").attrList("primaryKey")).containsExactly("ORDER_ID", "CREATED_AT");
        assertThat(g.hasNode("db:table:GHOST_PART")).isFalse();
        assertThat(g.hasNode("db:table:ATTACH")).isFalse();
        assertThat(g.hasNode("db:table:PARTITION")).isFalse();
        // 기본 읽기 방식에서는 전처럼 동작 없는 ALTER TABLE이다(DB2의 ATTACH PARTITION은 뜻이 다르다)
        GraphModel db2 = analyze("db/db2/ddl.sql", "CREATE TABLE T_P (ID INT);\nCREATE TABLE T_C (ID INT);\n"
                + "ALTER TABLE T_P ATTACH PARTITION P1 STARTING FROM (1) ENDING AT (10) FROM T_C;\n");
        assertThat(node(db2, "db:table:T_C").attrs()).doesNotContainKey("partitionOf");
        assertThat(db2.hasNode("db:table:P1")).isFalse();
    }

    @Test
    @DisplayName("F-019-AC19 INHERITS: 부모가 여럿이면 차례로 넣고, 자식이 다시 적은 같은 이름의 컬럼은 부모의 자리에 합친다. 손자도 조부모의 컬럼을 갖는다. 부모의 기본 키는 따라오지 않는다")
    void inheritsMergesAndChains() {
        GraphModel g = analyze(PG, """
                CREATE TABLE gold_customers (level int) INHERITS (vip_customers);
                CREATE TABLE vip_customers (
                    vip_since date DEFAULT CURRENT_DATE NOT NULL,
                    email character varying(200) NOT NULL
                )
                INHERITS (public.customers, auditable);
                CREATE TABLE public.customers (id bigint PRIMARY KEY, email character varying(120), name text NOT NULL);
                CREATE TABLE auditable (created_by text, name text);
                CREATE INDEX idx_vip_email ON vip_customers (email);
                COMMENT ON COLUMN vip_customers.name IS '이름';
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(columns(g, "VIP_CUSTOMERS")).containsExactly("ID", "EMAIL", "NAME", "CREATED_BY", "VIP_SINCE");
        assertThat(column(g, "VIP_CUSTOMERS", "EMAIL").attrString("dataType")).isEqualTo("CHARACTER VARYING(200)");
        assertThat(column(g, "VIP_CUSTOMERS", "EMAIL").attr("nullable")).isEqualTo(false);
        assertThat(column(g, "VIP_CUSTOMERS", "EMAIL").attrBoolean("indexed")).isTrue();
        assertThat(column(g, "VIP_CUSTOMERS", "NAME").attr("nullable")).isEqualTo(false);
        assertThat(column(g, "VIP_CUSTOMERS", "NAME").attrString("comment")).isEqualTo("이름");
        assertThat(column(g, "VIP_CUSTOMERS", "ID").attr("primaryKey")).isEqualTo(false);
        assertThat(node(g, "db:table:VIP_CUSTOMERS").attrs()).doesNotContainKey("primaryKey");
        assertThat(columns(g, "GOLD_CUSTOMERS")).containsExactly("ID", "EMAIL", "NAME", "CREATED_BY", "VIP_SINCE", "LEVEL");
        assertThat(g.hasNode("db:table:INHERITS")).isFalse();
    }

    @Test
    @DisplayName("F-019-AC19 부모 정의가 없는 INHERITS·PARTITION OF: 자기 컬럼만 남고 부모의 노드·관계를 만들지 않는다. partitionOf는 구문에 적힌 사실이라 남는다. 서로를 가리키는 LIKE에서도 끝난다")
    void missingParentAndCycle() {
        GraphModel g = analyze(PG, """
                CREATE TABLE child (grade int) INHERITS (nowhere);
                CREATE TABLE part_x PARTITION OF nowhere_p FOR VALUES IN ('x');
                CREATE TABLE a (x int, LIKE b);
                CREATE TABLE b (y int, LIKE a);
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("A", "B", "CHILD", "PART_X");
        assertThat(columns(g, "CHILD")).containsExactly("GRADE");
        assertThat(columns(g, "PART_X")).isEmpty();
        assertThat(node(g, "db:table:PART_X").attrString("partitionOf")).isEqualTo("NOWHERE_P");
        assertThat(columns(g, "A")).contains("X");
        assertThat(columns(g, "B")).contains("Y");
    }

    @Test
    @DisplayName("F-019-AC19 부모 정의가 없어 컬럼을 다 알지 못하는 테이블: 자기 컬럼에 없는 이름을 'DDL에 없는 컬럼'이라고 단정하지 않는다(부모에게서 왔을 수 있다). 부모 정의가 있으면 전처럼 알린다")
    void incompleteColumnsAreNotTrusted() {
        String rest = """
                CREATE TABLE other (id int);
                SELECT email, grade FROM child, other;
                """;
        GraphModel missing = analyze(PG, "CREATE TABLE child (grade int) INHERITS (nowhere);\n" + rest);
        assertThat(diagnostics(missing, SqlDiagnosticCodes.UNKNOWN_COLUMN)).isEmpty();
        assertThat(GraphAssert.targets(missing, Ids.sqlStatement(PG, 3), EdgeKind.READS))
                .contains("db:table:CHILD", "db:table:OTHER", "db:column:CHILD.GRADE").doesNotContain("db:column:OTHER.EMAIL");
        GraphModel known = analyze(PG, "CREATE TABLE child (grade int) INHERITS (parent);\n" + rest + "CREATE TABLE parent (name text);\n");
        assertThat(diagnostics(known, SqlDiagnosticCodes.UNKNOWN_COLUMN)).hasSize(1);
    }

    // ------------------------------------------------------------------ F-019-AC20: CREATE INDEX

    @Test
    @DisplayName("F-019-AC20 CREATE INDEX CONCURRENTLY IF NOT EXISTS .. USING gin (컬럼 연산자클래스), USING btree (.. DESC NULLS LAST) WHERE (..), 식 인덱스: 인덱스의 컬럼만 기록되고 LOWER·JSONB_PATH_OPS·GIN·BTREE라는 컬럼이 없다")
    void createIndexForms() {
        GraphModel g = analyze(PG, """
                CREATE TABLE public.customers (id bigint, email text, profile jsonb);
                CREATE TABLE public.orders (id bigint, customer_id bigint, ordered_at timestamp with time zone, status text);
                CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_c ON public.customers USING gin (profile jsonb_path_ops);
                CREATE UNIQUE INDEX idx_o ON public.orders USING btree (customer_id, ordered_at DESC NULLS LAST) WHERE (status <> 'SHIPPED');
                CREATE INDEX idx_l ON customers (lower(email));
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("CUSTOMERS", "ORDERS");
        assertThat(node(g, "db:table:CUSTOMERS").attrList("indexes")).containsExactly("IDX_C(PROFILE)", "IDX_L(LOWER(EMAIL))");
        assertThat(node(g, "db:table:ORDERS").attrList("indexes")).containsExactly("IDX_O UNIQUE(CUSTOMER_ID,ORDERED_AT DESC)");
        assertThat(columns(g, "CUSTOMERS")).containsExactly("ID", "EMAIL", "PROFILE");
        assertThat(columns(g, "ORDERS")).containsExactly("ID", "CUSTOMER_ID", "ORDERED_AT", "STATUS");
        assertThat(column(g, "CUSTOMERS", "PROFILE").attrBoolean("indexed")).isTrue();
        assertThat(column(g, "CUSTOMERS", "EMAIL").attrs()).doesNotContainKey("indexed"); // 식 항목은 컬럼으로 만들지 않는다
        assertThat(column(g, "ORDERS", "CUSTOMER_ID").attrBoolean("indexed")).isTrue();
        assertThat(column(g, "ORDERS", "ORDERED_AT").attrBoolean("indexed")).isTrue();
        // 부분 유일 인덱스는 테이블 전체의 유일 키가 아니다
        assertThat(node(g, "db:table:ORDERS").attrs()).doesNotContainKey("uniqueKeys");
        assertThat(column(g, "ORDERS", "STATUS").attrs()).doesNotContainKey("indexed");
    }

    @Test
    @DisplayName("F-019-AC20 인덱스 문법의 나머지: 이름 없는 인덱스, ON ONLY, 스키마를 붙인 연산자 클래스와 COLLATE, INCLUDE·WITH·TABLESPACE, 괄호로 싼 식, pg_dump의 식 인덱스 꼴")
    void createIndexRest() {
        GraphModel g = analyze(PG, """
                CREATE TABLE t (a text, b text, c integer, d text, e text);
                CREATE INDEX ON t (a);
                CREATE UNIQUE INDEX IF NOT EXISTS ON ONLY t USING btree (c);
                CREATE INDEX idx_ops ON ONLY public.t USING gin (a public.gin_trgm_ops, b COLLATE "C" text_pattern_ops DESC NULLS FIRST);
                CREATE UNIQUE INDEX idx_inc ON t USING btree (d) INCLUDE (e) WITH (fillfactor = 70) TABLESPACE fast;
                CREATE INDEX idx_expr ON t ((lower(a)), c);
                CREATE INDEX idx_dump ON public.t USING btree (lower((e)::text));
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("T");
        assertThat(columns(g, "T")).containsExactly("A", "B", "C", "D", "E");
        assertThat(node(g, "db:table:T").attrList("indexes")).containsExactly("(A)", "UNIQUE(C)", "IDX_OPS(A,B DESC)", "IDX_INC UNIQUE(D)",
                "IDX_EXPR((LOWER(A)),C)", "IDX_DUMP(LOWER((E)::TEXT))");
        assertThat(column(g, "T", "C").attrBoolean("unique")).isTrue();
        assertThat(column(g, "T", "D").attrBoolean("unique")).isTrue();
        assertThat(column(g, "T", "E").attrs()).doesNotContainKey("indexed"); // INCLUDE의 컬럼과 식 안의 컬럼은 인덱스 컬럼이 아니다
        assertThat(column(g, "T", "B").attrBoolean("indexed")).isTrue();
    }

    // ------------------------------------------------------------------ F-019-AC21: pg_dump 부속 구문

    /** spec 5절 "pg_dump 예시"와 F-019-R22의 목록. */
    private static final String HOUSEKEEPING = """
            --
            -- PostgreSQL database dump
            --

            SET statement_timeout = 0;
            SET client_encoding = 'UTF8';
            SELECT pg_catalog.set_config('search_path', '', false);
            SET default_tablespace = '';
            SET default_table_access_method = heap;
            CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
            COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';
            CREATE SCHEMA shop;
            ALTER SCHEMA shop OWNER TO app;
            COMMENT ON SCHEMA shop IS 'shop';
            CREATE TYPE public.order_status AS ENUM ('NEW', 'PAID', 'SHIPPED');
            CREATE DOMAIN public.email AS text CHECK (VALUE ~ '@');
            CREATE COLLATION public.ko (provider = icu, locale = 'ko-KR');
            ALTER TYPE public.order_status OWNER TO app;
            COMMENT ON TYPE public.order_status IS '주문 상태';
            ALTER FUNCTION public.close_order(bigint, text) OWNER TO app;
            COMMENT ON FUNCTION public.close_order(bigint, text) IS '주문 마감';
            ALTER TABLE public.orders_id_seq OWNER TO app;
            ALTER SEQUENCE public.orders_id_seq OWNER TO app;
            ALTER SEQUENCE public.orders_id_seq OWNED BY public.orders.id;
            SELECT pg_catalog.setval('public.orders_id_seq', 1000, false);
            GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly;
            GRANT SELECT,INSERT,UPDATE ON TABLE public.orders TO shop_app;
            GRANT ALL ON SEQUENCE public.orders_id_seq TO shop_app;
            REVOKE ALL ON SCHEMA public FROM PUBLIC;
            """;

    @Test
    @DisplayName("F-019-AC21 pg_dump 머리말과 부속 구문만 있는 파일: PARSE_FAILED·UNSUPPORTED_SYNTAX가 없고 테이블·구문 노드가 없다")
    void pgDumpHousekeepingIsSilent() {
        for (String path : new String[] {PG, "db/dump.sql"}) { // 경로의 방언 이름, 내용 특징
            GraphModel g = analyze(path, HOUSEKEEPING);
            assertThat(problems(g)).as(path).isEmpty();
            assertThat(g.diagnostics()).as(path).allMatch(d -> d.severity() == Severity.INFO);
            assertThat(g.nodes(NodeKind.DB_TABLE)).as(path).isEmpty();
            assertThat(g.nodes(NodeKind.DB_VIEW)).as(path).isEmpty();
            assertThat(g.nodes(NodeKind.SQL_STATEMENT)).as(path).isEmpty();
            assertThat(g.nodes(NodeKind.DB_SEQUENCE)).as(path).isEmpty();
            assertThat(g.nodes(NodeKind.DB_ROUTINE)).as(path).isEmpty();
        }
    }

    @Test
    @DisplayName("F-019-AC21 ALTER TABLE .. ALTER COLUMN .. SET DEFAULT nextval('…'::regclass)는 진단 없이 그 컬럼의 기본값 글자로 남고(Oracle DEFAULT seq.NEXTVAL과 같다) 구문 노드·REGCLASS 컬럼을 만들지 않는다")
    void setDefaultNextval() {
        GraphModel g = analyze(PG, """
                CREATE TABLE public.customers (id bigint NOT NULL, email text);
                CREATE SEQUENCE public.customers_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
                ALTER SEQUENCE public.customers_id_seq OWNED BY public.customers.id;
                ALTER TABLE ONLY public.customers ALTER COLUMN id SET DEFAULT nextval('public.customers_id_seq'::regclass);
                """);
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("CUSTOMERS");
        assertThat(columns(g, "CUSTOMERS")).containsExactly("ID", "EMAIL");
        assertThat(column(g, "CUSTOMERS", "ID").attrString("defaultValue")).isEqualTo("NEXTVAL('public.customers_id_seq'::REGCLASS)");
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).isEmpty();
        assertThat(g.nodes(NodeKind.DB_SEQUENCE)).extracting(Node::name).containsExactly("CUSTOMERS_ID_SEQ");
    }

    @Test
    @DisplayName("F-019-AC10 F-019-AC21 SELECT pg_catalog.setval(..)·set_config(..)는 덤프의 뒷정리다: 구문 노드·시퀀스 참조·진단이 없다. pg_catalog.을 붙이지 않은 스크립트의 SELECT setval(..)은 전처럼 구문이다")
    void catalogCallsAreHousekeeping() {
        String path = "db/postgres/data.sql";
        GraphModel g = analyze(path, "COPY public.customers (id, email) FROM stdin;\n"
                + "1\ta@x.com\n"
                + "\\.\n"
                + "\n"
                + "SELECT pg_catalog.setval('public.orders_id_seq', 10, true);\n"
                + "SELECT pg_catalog.set_config('search_path', '', false);\n"
                + "SELECT setval('public.items_id_seq', 5);\n"
                + "SELECT pg_catalog.setval('public.a_seq', 1, true), id FROM customers;\n");
        assertThat(problems(g)).isEmpty();
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).extracting(Node::id)
                .containsExactlyInAnyOrder(Ids.sqlStatement(path, 1), Ids.sqlStatement(path, 7), Ids.sqlStatement(path, 8));
        assertThat(g.hasNode("db:sequence:ORDERS_ID_SEQ")).isFalse();
        assertThat(g.hasNode("db:sequence:ITEMS_ID_SEQ")).isTrue();
        // 부속 구문이 끼어 있어도 COPY 묶음은 그대로다
        assertThat(node(g, Ids.sqlStatement(path, 1)).attr("rowCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("F-019-AC21 부속 구문 목록에 없는 PostgreSQL 구문은 조용히 버리지 않는다: REFRESH MATERIALIZED VIEW와 CREATE RULE은 UNSUPPORTED_SYNTAX로 남고 테이블·구문 노드를 만들지 않는다")
    void outOfListStatementsAreReported() {
        GraphModel g = analyze(PG, """
                CREATE TABLE public.orders (id bigint);
                REFRESH MATERIALIZED VIEW public.mv_daily_sales;
                CREATE RULE r AS ON INSERT TO public.orders DO NOTHING;
                """);
        List<Diagnostic> unsupported = diagnostics(g, DiagnosticCodes.UNSUPPORTED_SYNTAX);
        assertThat(unsupported).extracting(d -> d.location().startLine()).containsExactly(2, 3);
        assertThat(unsupported).allMatch(d -> d.severity() == Severity.INFO);
        assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isEmpty();
        assertThat(tables(g)).containsExactly("ORDERS");
        assertThat(g.nodes(NodeKind.DB_VIEW)).isEmpty();
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC21 기본 읽기 방식의 REFRESH TABLE(DB2)은 전처럼 조용히 넘긴다(F-019-R8)")
    void db2RefreshStaysSilent() {
        GraphModel g = analyze("db/db2/mqt.sql", "CREATE TABLE T (ID INT);\nREFRESH TABLE MQT_SALES;\n");
        assertThat(problems(g)).isEmpty();
        assertThat(tables(g)).containsExactly("T");
    }

    // ------------------------------------------------------------------ 퍼즈

    private static final String FUZZ_SEED = AC17 + DERIVED + ORDERS + """
            CREATE UNLOGGED TABLE IF NOT EXISTS session_cache (k text PRIMARY KEY, v bytea, tags text[] DEFAULT '{}'::text[]);
            CREATE TABLE a (x serial, LIKE b INCLUDING ALL EXCLUDING DEFAULTS, y int REFERENCES orders (id) MATCH FULL ON DELETE SET NULL DEFERRABLE);
            CREATE TABLE b (LIKE a, z int ARRAY[3]) INHERITS (a, public.orders) PARTITION BY RANGE (z);
            CREATE TABLE p2 PARTITION OF b (z WITH OPTIONS NOT NULL, CONSTRAINT c CHECK (z > 0)) FOR VALUES FROM (1) TO (2);
            ALTER TABLE ONLY public.orders ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME s START WITH 1);
            ALTER TABLE IF EXISTS public.orders ADD COLUMN IF NOT EXISTS memo2 text, ALTER COLUMN memo TYPE varchar(10) USING memo::varchar(10);
            ALTER TABLE public.orders OWNER TO shop;
            CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS idx_o ON ONLY public.orders USING btree (customer_id, lower((memo)::text) DESC NULLS LAST) INCLUDE (id) WHERE (id > 0);
            CREATE INDEX ON orders USING gin (memo public.gin_trgm_ops, id COLLATE "C" int8_ops(x = 1));
            SELECT pg_catalog.setval('public.orders_id_seq', 10, true);
            REFRESH MATERIALIZED VIEW public.mv;
            """;

    @Test
    @DisplayName("F-019-AC17 F-019-AC19 F-019-AC20 퍼즈: 망가뜨린 PostgreSQL DDL을 어느 읽기 방식으로 읽어도 분석기가 ERROR 진단 없이 끝난다")
    void brokenDdlNeverStopsTheAnalysis() {
        java.util.Random random = new java.util.Random(19L);
        String junk = "()',;.=*[]:$\"\n ";
        List<String> words = List.of(" LIKE ", " INHERITS (", " PARTITION OF ", " ONLY ", " IF NOT EXISTS ", " USING ", " INCLUDING ", " ON ", " WHERE ",
                " NULLS ", "[]", " ARRAY", " ADD ", " ALTER COLUMN ", " GENERATED ", " REFERENCES ", " MATCH ", " OWNER TO ", " CONCURRENTLY ");
        List<String> texts = new java.util.ArrayList<>();
        for (int i = 0; i <= FUZZ_SEED.length(); i += 9) {
            texts.add(FUZZ_SEED.substring(0, i));
            texts.add(FUZZ_SEED.substring(i));
        }
        texts.add(FUZZ_SEED.replace("(", ""));
        texts.add(FUZZ_SEED.replace(")", ""));
        texts.add(FUZZ_SEED.replace(";", ""));
        texts.add("CREATE TABLE t (" + "LIKE t, ".repeat(2000) + "x int) INHERITS (" + "t, ".repeat(2000) + "t);");
        texts.add("CREATE INDEX ON t (" + "(".repeat(3000) + "a" + ")".repeat(2999) + ");");
        for (int n = 0; n < 400; n++) {
            StringBuilder sb = new StringBuilder(FUZZ_SEED);
            int edits = 1 + random.nextInt(6);
            for (int e = 0; e < edits && sb.length() > 0; e++) {
                int at = random.nextInt(sb.length());
                switch (random.nextInt(4)) {
                    case 0 -> sb.deleteCharAt(at);
                    case 1 -> sb.insert(at, junk.charAt(random.nextInt(junk.length())));
                    case 2 -> sb.delete(at, Math.min(sb.length(), at + random.nextInt(40)));
                    default -> sb.insert(at, words.get(random.nextInt(words.size())));
                }
            }
            texts.add(sb.toString());
        }
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(120), () -> {
            for (String folder : new String[] {"postgres", "oracle", "mysql"}) {
                try (TestProject project = TestProject.create()) {
                    for (int i = 0; i < texts.size(); i++) {
                        project.file("db/" + folder + "/f" + i + ".sql", texts.get(i));
                    }
                    GraphModel g = project.run(new SqlAnalyzer()).graph();
                    assertThat(g.diagnostics()).as(folder).noneMatch(d -> d.severity() == Severity.ERROR);
                    assertThat(g.nodes(NodeKind.DB_TABLE)).as(folder).isNotEmpty();
                }
            }
        });
    }
}
