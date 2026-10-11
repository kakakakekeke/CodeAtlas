package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlAnalyzerTest {

    private static final String MAPPER = "src/main/resources/mapper/OrderMapper.xml";

    /** 매퍼 구문 노드를 만들고 내장 소스를 등록한다(생산자 흉내). */
    private static String embed(AnalysisContext ctx, String statementId, int line, boolean exact, Map<String, String> attrs, String sql) {
        String owner = Ids.mybatisStatement("com.a.OrderMapper", statementId);
        ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(statementId)
                .location(SourceLocation.of(MAPPER, line)).build());
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, sql, SourceLocation.of(MAPPER, line, 5, line, 5), exact, attrs));
        return owner;
    }

    private static AnalysisContext context(TestProject project) {
        return project.file(MAPPER, "<mapper namespace=\"com.a.OrderMapper\"/>").context();
    }

    @Test
    @DisplayName("F-006-AC9 서비스 로더 등록과 실행 순서(소비자 200)")
    void registered() {
        List<Analyzer> analyzers = ServiceLoader.load(Analyzer.class).stream().map(ServiceLoader.Provider::get).collect(Collectors.toList());
        assertThat(analyzers).anyMatch(a -> a instanceof SqlAnalyzer);
        assertThat(new SqlAnalyzer().order()).isEqualTo(Analyzer.ORDER_CONSUMER);
        assertThat(new SqlAnalyzer().name()).isEqualTo("sql");
    }

    @Test
    @DisplayName("F-006-AC3 F-006-AC4 내장 소스: READS/WRITES 테이블·컬럼 엣지, attrs.op, star, 위치")
    void embeddedReadsWrites() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String select = embed(ctx, "selectOrders", 10, true, Map.of(),
                    "SELECT o.ORDER_ID, m.MEMBER_NM\n  FROM TB_ORDER o JOIN TB_MEMBER m ON o.MEMBER_ID = m.MEMBER_ID\n WHERE o.ORDER_DT >= ?");
            String star = embed(ctx, "selectAll", 20, true, Map.of(), "SELECT * FROM shop.TB_ORDER WHERE ORDER_ID = ?");
            String insert = embed(ctx, "insertOrder", 30, false, Map.of(),
                    "INSERT INTO TB_ORDER (ORDER_ID, MEMBER_ID) VALUES (SEQ_ORDER.NEXTVAL, ?)");
            String update = embed(ctx, "updateOrder", 40, false, Map.of(), "UPDATE TB_ORDER SET ORDER_STATUS = ? WHERE ORDER_ID = ?");
            String delete = embed(ctx, "deleteOrder", 50, false, Map.of(), "DELETE FROM TB_ORDER WHERE ORDER_ID = ?");
            String merge = embed(ctx, "mergeStock", 60, false, Map.of(),
                    "MERGE INTO TB_STOCK s USING TB_ORDER_ITEM i ON (s.PRODUCT_ID = i.PRODUCT_ID) WHEN MATCHED THEN UPDATE SET s.QTY = s.QTY - i.QTY");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            assertThat(targets(g, select, EdgeKind.READS)).containsExactly(
                    "db:column:TB_MEMBER.MEMBER_ID", "db:column:TB_MEMBER.MEMBER_NM", "db:column:TB_ORDER.MEMBER_ID",
                    "db:column:TB_ORDER.ORDER_DT", "db:column:TB_ORDER.ORDER_ID", "db:table:TB_MEMBER", "db:table:TB_ORDER");
            Edge read = edge(g, select, EdgeKind.READS, "db:table:TB_ORDER");
            assertThat(read.confidence()).isEqualTo(Confidence.RESOLVED);
            // 위치 보존 본문: 조각의 2번째 줄 → 호스트 11번째 줄
            assertThat(read.location()).isEqualTo(new SourceLocation(MAPPER, 11, 8, 11, 16));
            assertThat(node(g, "db:table:TB_ORDER").declared()).isFalse();
            assertThat(node(g, "db:table:TB_ORDER").kind()).isEqualTo(NodeKind.DB_TABLE);
            assertThat(node(g, "db:column:TB_ORDER.ORDER_ID").kind()).isEqualTo(NodeKind.DB_COLUMN);
            assertThat(node(g, select).attrString("statementType")).isEqualTo("SELECT");

            assertThat(edge(g, star, EdgeKind.READS, "db:table:TB_ORDER").attrBoolean("star")).isTrue();
            assertThat(edge(g, select, EdgeKind.READS, "db:table:TB_ORDER").attr("star")).isNull();

            Edge w = edge(g, insert, EdgeKind.WRITES, "db:table:TB_ORDER");
            assertThat(w.attrString("op")).isEqualTo("INSERT");
            assertThat(w.location()).isEqualTo(SourceLocation.of(MAPPER, 30, 5, 30, 5));
            assertThat(targets(g, insert, EdgeKind.WRITES)).containsExactly("db:column:TB_ORDER.MEMBER_ID", "db:column:TB_ORDER.ORDER_ID", "db:table:TB_ORDER");
            assertThat(edge(g, insert, EdgeKind.WRITES, "db:column:TB_ORDER.ORDER_ID").attrString("op")).isEqualTo("INSERT");
            Edge seq = edge(g, insert, EdgeKind.READS, "db:sequence:SEQ_ORDER");
            assertThat(seq.attrBoolean("sequence")).isTrue();
            assertThat(node(g, "db:sequence:SEQ_ORDER").kind()).isEqualTo(NodeKind.DB_SEQUENCE);
            assertThat(node(g, insert).attrString("statementType")).isEqualTo("INSERT");

            assertThat(edge(g, update, EdgeKind.WRITES, "db:table:TB_ORDER").attrString("op")).isEqualTo("UPDATE");
            assertThat(hasEdge(g, update, EdgeKind.READS, "db:table:TB_ORDER")).isFalse();
            assertThat(hasEdge(g, update, EdgeKind.READS, "db:column:TB_ORDER.ORDER_ID")).isTrue();
            assertThat(edge(g, delete, EdgeKind.WRITES, "db:table:TB_ORDER").attrString("op")).isEqualTo("DELETE");
            assertThat(edge(g, merge, EdgeKind.WRITES, "db:table:TB_STOCK").attrString("op")).isEqualTo("MERGE");
            assertThat(hasEdge(g, merge, EdgeKind.READS, "db:table:TB_ORDER_ITEM")).isTrue();
            assertThat(node(g, merge).attrString("statementType")).isEqualTo("MERGE");
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-006-AC5 JOINS 엣지: 컬럼 대응, statement, 구문마다 다른 위치, 인스턴스 쌍마다 분리")
    void joinsEdges() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String a = embed(ctx, "a", 10, false, Map.of(),
                    "SELECT 1 FROM TB_ORDER o, TB_ORDER_ITEM i WHERE o.ORDER_ID = i.ORDER_ID AND i.ITEM_SEQ = 1");
            String b = embed(ctx, "b", 20, false, Map.of(),
                    "SELECT 1 FROM TB_ORDER_ITEM i JOIN TB_ORDER o ON i.ORDER_ID = o.ORDER_ID");
            String c = embed(ctx, "c", 30, false, Map.of(),
                    "SELECT 1 FROM TB_ORDER o JOIN TB_CODE c1 ON o.ST = c1.CD JOIN TB_CODE c2 ON o.TP = c2.CD");
            String self = embed(ctx, "self", 40, true, Map.of(), "SELECT 1 FROM TB_CAT c, TB_CAT p WHERE c.PARENT_ID = p.CAT_ID");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            List<Edge> ab = g.edges("db:table:TB_ORDER", EdgeKind.JOINS, "db:table:TB_ORDER_ITEM");
            assertThat(ab).hasSize(1);
            assertThat(ab.get(0).attrList("leftColumns")).containsExactly("ORDER_ID");
            assertThat(ab.get(0).attrList("rightColumns")).containsExactly("ORDER_ID");
            assertThat(ab.get(0).attrString("statement")).isEqualTo(a);
            assertThat(ab.get(0).location().startLine()).isEqualTo(10);
            List<Edge> ba = g.edges("db:table:TB_ORDER_ITEM", EdgeKind.JOINS, "db:table:TB_ORDER");
            assertThat(ba).hasSize(1);
            assertThat(ba.get(0).attrString("statement")).isEqualTo(b);

            // 같은 구문, 같은 테이블 쌍, 다른 인스턴스 쌍: 엣지 둘(위치가 서로 달라야 그래프에 둘 다 남는다)
            List<Edge> code = g.edges("db:table:TB_ORDER", EdgeKind.JOINS, "db:table:TB_CODE");
            assertThat(code).hasSize(2);
            assertThat(code).extracting(e -> e.attrList("leftColumns").get(0)).containsExactlyInAnyOrder("ST", "TP");
            assertThat(code).allMatch(e -> c.equals(e.attrString("statement")) && e.location().startLine() == 30);
            assertThat(code.get(0).location()).isNotEqualTo(code.get(1).location());

            Edge s = edge(g, "db:table:TB_CAT", EdgeKind.JOINS, "db:table:TB_CAT");
            assertThat(s.attrList("leftColumns")).containsExactly("PARENT_ID");
            assertThat(s.attrList("rightColumns")).containsExactly("CAT_ID");
            assertThat(s.attrString("statement")).isEqualTo(self);
            assertThat(s.location().startLine()).isEqualTo(40);
            assertThat(g.outgoing(a, EdgeKind.JOINS)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-006-AC6 테이블이 아닌 것: CTE, 별칭, DUAL, SYSDUMMY1, 테이블 함수, 주석·문자열")
    void notTables() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String s = embed(ctx, "s", 10, false, Map.of(),
                    "WITH recent AS (SELECT /* FROM TB_COMMENTED */ o.ID FROM TB_ORDER o WHERE o.NM <> 'FROM TB_STRING')\n"
                            + "SELECT r.ID, v.X, SYSDATE FROM recent r, (SELECT 1 X FROM DUAL) v, TABLE(fn_split(?)) t -- FROM TB_LINE");
            String d = embed(ctx, "d", 20, false, Map.of(), "SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(targets(g, s, EdgeKind.READS)).containsExactly("db:column:TB_ORDER.ID", "db:column:TB_ORDER.NM", "db:table:TB_ORDER");
            assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::id).containsExactly("db:table:TB_ORDER");
            assertThat(node(g, s).attrString("dummyTable")).isEqualTo("DUAL");
            assertThat(node(g, d).attrString("dummyTable")).isEqualTo("SYSIBM.SYSDUMMY1");
            assertThat(node(g, d).attrString("statementType")).isEqualTo("SELECT");
            assertThat(g.outgoing(d, EdgeKind.READS)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-006-AC7 호출: CALLS 엣지와 statementType=CALL, 두 부분 이름의 패키지/스키마 판정")
    void calls() {
        try (TestProject project = TestProject.create()
                .file("db/pkg.pks", "CREATE OR REPLACE PACKAGE PKG_ORDER AS\n  PROCEDURE CLOSE_ORDER(P NUMBER);\n  FUNCTION GET_AMT(P NUMBER) RETURN NUMBER;\nEND;\n/\n")
                .file("db/fn.fnc", "CREATE OR REPLACE FUNCTION FN_CODE_NM (P VARCHAR2) RETURN VARCHAR2 IS BEGIN RETURN P; END;\n/\n")
                .file("db/t.sql", "CREATE TABLE BATCH.TB_LOG (ID NUMBER);\n")) {
            AnalysisContext ctx = context(project);
            String c1 = embed(ctx, "c1", 10, false, Map.of(), "{call PKG_ORDER.CLOSE_ORDER(?, ?)}");
            String c2 = embed(ctx, "c2", 20, false, Map.of(), "{ ? = call FN_CODE_NM(?) }");
            String c3 = embed(ctx, "c3", 30, false, Map.of(), "CALL BATCH.SP_WRITE_LOG(?, ?)");
            String c4 = embed(ctx, "c4", 40, false, Map.of(), "CALL UNKNOWN_PKG.RUN(?)");
            String c5 = embed(ctx, "c5", 50, false, Map.of(), "CALL SHOP.PKG_ORDER.CLOSE_ORDER(?)");
            String c6 = embed(ctx, "c6", 60, false, Map.of(), "BEGIN PKG_ORDER.CLOSE_ORDER(#{id}); SP_PLAIN(1); END;");
            String c7 = embed(ctx, "c7", 70, false, Map.of(),
                    "SELECT FN_CODE_NM(o.ST), PKG_ORDER.GET_AMT(o.ID), NVL(o.X, 0), TO_CHAR(o.DT) FROM TB_ORDER o");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(targets(g, c1, EdgeKind.CALLS)).containsExactly("db:routine:PKG_ORDER.CLOSE_ORDER");
            assertThat(node(g, c1).attrString("statementType")).isEqualTo("CALL");
            assertThat(targets(g, c2, EdgeKind.CALLS)).containsExactly("db:routine:FN_CODE_NM");
            // BATCH는 DDL에서 스키마로 쓰인 이름
            assertThat(targets(g, c3, EdgeKind.CALLS)).containsExactly("db:routine:SP_WRITE_LOG");
            assertThat(node(g, "db:routine:SP_WRITE_LOG").declared()).isFalse();
            assertThat(node(g, "db:routine:SP_WRITE_LOG").kind()).isEqualTo(NodeKind.DB_ROUTINE);
            // 모르는 한정자는 패키지로 본다
            assertThat(targets(g, c4, EdgeKind.CALLS)).containsExactly("db:routine:UNKNOWN_PKG.RUN");
            assertThat(targets(g, c5, EdgeKind.CALLS)).containsExactly("db:routine:PKG_ORDER.CLOSE_ORDER");
            assertThat(targets(g, c6, EdgeKind.CALLS)).containsExactly("db:routine:PKG_ORDER.CLOSE_ORDER", "db:routine:SP_PLAIN");
            assertThat(node(g, c6).attrString("statementType")).isEqualTo("CALL");
            assertThat(targets(g, c7, EdgeKind.CALLS)).containsExactly("db:routine:FN_CODE_NM", "db:routine:PKG_ORDER.GET_AMT");
            assertThat(node(g, c7).attrString("statementType")).isEqualTo("SELECT");
            assertThat(g.outgoing(c1, EdgeKind.READS)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-006-AC8 __DYNAMIC__: 테이블 자리는 미해결 엣지와 DYNAMIC_SQL 진단, 그 밖의 자리는 attrs.dynamic")
    void dynamicSql() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String t = embed(ctx, "t", 10, false, Map.of(), "SELECT * FROM __DYNAMIC__ WHERE id = ? ORDER BY __DYNAMIC__");
            String part = embed(ctx, "part", 20, false, Map.of(), "INSERT INTO TB_LOG___DYNAMIC__ (ID, MSG) SELECT ID, MSG FROM TB_SRC WHERE __DYNAMIC__ = 1");
            String order = embed(ctx, "order", 30, false, Map.of(), "SELECT a.ID FROM TB_A a WHERE a.X = ? ORDER BY ${col} ${dir}");
            String schema = embed(ctx, "schema", 40, false, Map.of(), "SELECT ID FROM __DYNAMIC__.TB_B");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            String target = "unresolved:sql-dynamic:" + t;
            Edge e = edge(g, t, EdgeKind.READS, target);
            assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
            // 치환 목록(dynamicParts)이 없는 내장 소스: 내부 표식 대신 ${…}로 적는다(F-006-R26)
            assertThat(e.attrString("text")).isEqualTo("${…}");
            assertThat(g.outgoing(t, EdgeKind.READS)).hasSize(1);
            assertThat(node(g, target).kind()).isEqualTo(NodeKind.UNRESOLVED);
            assertThat(node(g, target).attrString("category")).isEqualTo("sql-dynamic");
            assertThat(node(g, t).attrBoolean("dynamic")).isTrue();

            Edge w = edge(g, part, EdgeKind.WRITES, "unresolved:sql-dynamic:" + part);
            assertThat(w.confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(w.attrString("op")).isEqualTo("INSERT");
            assertThat(w.attrString("text")).isEqualTo("TB_LOG_${…}");
            assertThat(targets(g, part, EdgeKind.READS)).contains("db:table:TB_SRC");
            assertThat(g.nodes(NodeKind.DB_TABLE)).extracting(Node::id).doesNotContain("db:table:TB_LOG___DYNAMIC__", "db:table:__DYNAMIC__");

            assertThat(node(g, order).attrBoolean("dynamic")).isTrue();
            assertThat(targets(g, order, EdgeKind.READS)).containsExactly("db:column:TB_A.ID", "db:column:TB_A.X", "db:table:TB_A");
            assertThat(node(g, schema).attrBoolean("dynamic")).isTrue();
            assertThat(targets(g, schema, EdgeKind.READS)).contains("db:table:TB_B");

            assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).hasSize(2);
            assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).allMatch(d -> d.severity() == Severity.WARN && d.location() != null);
            assertThat(g.diagnostics()).hasSize(2);
        }
    }

    @Test
    @DisplayName("F-006-AC9 같은 주인의 전개본·selectKey 결과를 합치고 MyBatis 잔재는 조용히 복구한다")
    void variantsMerged() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String owner = embed(ctx, "search", 10, false, Map.of("variant", "1"), "SELECT a.ID FROM TB_A a WHERE AND a.X = ?");
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner,
                    "SELECT a.ID\n FROM TB_A a, TB_B b\n WHERE a.ID = b.A_ID\n AND AND b.Y = ?,", SourceLocation.of(MAPPER, 10, 5, 10, 5), false, Map.of("variant", "2")));
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner,
                    "SELECT a.ID FROM TB_A a, TB_B b WHERE a.ID = b.A_ID AND a.K = b.K", SourceLocation.of(MAPPER, 10, 5, 10, 5), false, Map.of("variant", "3")));
            // 서로 배타적인 <if> 본문이 한 전개본에 같이 들어온 꼴
            String both = embed(ctx, "both", 20, false, Map.of("variant", "1"),
                    "SELECT x.ID FROM TB_X x FROM TB_Y y WHERE x.ID = ? WHERE y.ID = ? AND AND x.A = y.A ORDER BY 1");
            String ins = embed(ctx, "ins", 30, false, Map.of(), "INSERT INTO TB_A (ID, NM) VALUES (#{id}, #{nm})");
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, ins, "SELECT SEQ_A.NEXTVAL FROM DUAL",
                    SourceLocation.of(MAPPER, 31, 9, 31, 9), false, Map.of("selectKey", "true")));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            assertThat(targets(g, owner, EdgeKind.READS)).containsExactly(
                    "db:column:TB_A.ID", "db:column:TB_A.K", "db:column:TB_A.X", "db:column:TB_B.A_ID", "db:column:TB_B.K", "db:column:TB_B.Y",
                    "db:table:TB_A", "db:table:TB_B");
            // 전개본마다 조건이 더 붙은 같은 조인은 큰 쪽 하나만 남긴다
            List<Edge> joins = g.edges("db:table:TB_A", EdgeKind.JOINS, "db:table:TB_B");
            assertThat(joins).hasSize(1);
            assertThat(joins.get(0).attrList("leftColumns")).containsExactly("ID", "K");
            assertThat(joins.get(0).attrList("rightColumns")).containsExactly("A_ID", "K");
            assertThat(node(g, owner).attrString("statementType")).isEqualTo("SELECT");

            assertThat(targets(g, both, EdgeKind.READS)).contains("db:table:TB_X", "db:table:TB_Y");
            assertThat(hasEdge(g, "db:table:TB_X", EdgeKind.JOINS, "db:table:TB_Y")).isTrue();

            assertThat(node(g, ins).attrString("statementType")).isEqualTo("INSERT");
            assertThat(hasEdge(g, ins, EdgeKind.READS, "db:sequence:SEQ_A")).isTrue();
            assertThat(edge(g, ins, EdgeKind.READS, "db:sequence:SEQ_A").location().startLine()).isEqualTo(31);
            assertThat(hasEdge(g, ins, EdgeKind.WRITES, "db:table:TB_A")).isTrue();
            // 가벼운 잔재(빠진 피연산자, 남은 쉼표)는 진단을 남기지 않는다
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-006-AC9 파싱하지 못한 내장 소스는 기존 statementType을 지우지 않는다")
    void keepsExistingStatementType() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String owner = embed(ctx, "weird", 10, false, Map.of(), "FROBNICATE THE widgets");
            ctx.graph().putAttrs(owner, Map.of("statementType", "UPDATE"));
            String fresh = embed(ctx, "fresh", 20, false, Map.of(), "FROBNICATE AGAIN");
            String fixed = embed(ctx, "fixed", 30, false, Map.of(), "SELECT 1 FROM TB_A");
            ctx.graph().putAttrs(fixed, Map.of("statementType", "UPDATE"));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(node(g, owner).attrString("statementType")).isEqualTo("UPDATE");
            assertThat(node(g, fresh).attrString("statementType")).isEqualTo("OTHER");
            assertThat(node(g, fixed).attrString("statementType")).isEqualTo("SELECT");
            assertThat(diagnostics(g, DiagnosticCodes.UNSUPPORTED_SYNTAX)).hasSize(2);
        }
    }

    @Test
    @DisplayName("F-006-AC10 SQL 파일의 DML 구문: SQL_STATEMENT 노드, FILE→CONTAINS, attrs.sql, 루틴 단위 확장 지점")
    void sqlFileStatements() {
        String sql = "-- 초기 데이터\nINSERT INTO TB_CODE (CD, NM)\nVALUES ('A', 'x;y');\n\nUPDATE TB_CODE\n   SET NM = 'z'\n WHERE CD = 'A'\n/\n"
                + "CREATE OR REPLACE PROCEDURE SP_X IS\nBEGIN\n  DELETE FROM TB_IN_BODY;\nEND;\n/\nSELECT COUNT(*) FROM TB_CODE c WHERE c.CD = 'A';\nBEGIN SP_X; END;\n/\n";
        try (TestProject project = TestProject.create().file("db/data.sql", sql)) {
            GraphModel g = project.run(new SqlAnalyzer(new io.codeatlas.sql.script.HeaderOnlyRoutineHandler())).graph();
            Node ins = node(g, "sql:stmt:db/data.sql:2");
            assertThat(ins.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
            assertThat(ins.declared()).isTrue();
            assertThat(ins.attrString("statementType")).isEqualTo("INSERT");
            assertThat(ins.attrString("sql")).isEqualTo("INSERT INTO TB_CODE (CD, NM) VALUES ('A', 'x;y')");
            assertThat(ins.location()).isEqualTo(new SourceLocation("db/data.sql", 2, 1, 3, 20));
            assertThat(targets(g, "file:db/data.sql", EdgeKind.CONTAINS)).containsExactly(
                    "db:routine:SP_X", "sql:stmt:db/data.sql:14", "sql:stmt:db/data.sql:15", "sql:stmt:db/data.sql:2", "sql:stmt:db/data.sql:5");
            assertThat(edge(g, "sql:stmt:db/data.sql:5", EdgeKind.WRITES, "db:table:TB_CODE").attrString("op")).isEqualTo("UPDATE");
            assertThat(edge(g, "sql:stmt:db/data.sql:14", EdgeKind.READS, "db:table:TB_CODE").location())
                    .isEqualTo(new SourceLocation("db/data.sql", 14, 22, 14, 29));
            assertThat(node(g, "sql:stmt:db/data.sql:15").attrString("statementType")).isEqualTo("CALL");
            assertThat(hasEdge(g, "sql:stmt:db/data.sql:15", EdgeKind.CALLS, "db:routine:SP_X")).isTrue();
            assertThat(node(g, "db:routine:SP_X").declared()).isTrue();
            assertThat(g.hasNode("db:table:TB_IN_BODY")).isFalse();
            assertThat(g.diagnostics()).hasSize(1);
            assertThat(g.diagnostics().get(0).severity()).isEqualTo(Severity.INFO);
        }
    }

    @Test
    @DisplayName("F-006-AC10 루틴 단위 확장 지점: 끼운 핸들러가 단위 텍스트, 헤더, 방언, 그래프·카탈로그 접근을 받는다")
    void routineHandlerHook() {
        StringBuilder seen = new StringBuilder();
        SqlAnalyzer analyzer = new SqlAnalyzer((unit, context) -> {
            seen.append(unit.header().kind()).append(' ').append(unit.header().name().name()).append(' ')
                    .append(unit.nodeId()).append(' ').append(context.dialect()).append(' ')
                    .append(context.catalog().table("TB_T") != null).append(' ')
                    .append(context.location(unit.unit().pos()).startLine()).append(' ')
                    .append(unit.unit().text().length() > 10).append('|');
            context.graph().addEdge(Edge.of(unit.nodeId(), "db:table:TB_T", EdgeKind.TRIGGERS_ON));
        });
        try (TestProject project = TestProject.create()
                .file("db/oracle/a.sql", "CREATE TABLE TB_T (ID NUMBER);\nCREATE OR REPLACE PROCEDURE SP_Y IS\nBEGIN\n  NULL;\nEND;\n/\n")) {
            GraphModel g = project.run(analyzer).graph();
            assertThat(seen.toString()).isEqualTo("PROCEDURE SP_Y db:routine:SP_Y ORACLE true 2 true|");
            assertThat(hasEdge(g, "db:routine:SP_Y", EdgeKind.TRIGGERS_ON, "db:table:TB_T")).isTrue();
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-006-AC11 깨진 SQL 파일: 예외 없이 테이블을 건지고 PARSE_FAILED를 남긴다")
    void brokenFile() {
        String sql = "SELECT B.BROKEN_ID,\n       (SELECT COUNT(*\n          FROM TB_BROKEN_CHILD C\n         WHERE C.BROKEN_ID = B.BROKEN_ID AS CHILD_CNT\n  FROM TB_BROKEN B\n WHERE B.USE_YN IN ('Y', 'N'\n\n"
                + "UPDATE TB_BROKEN\n   SET BROKEN_NM = '닫히지 않은 문자열\n WHERE BROKEN_ID = 1;\n";
        try (TestProject project = TestProject.create().file("broken/broken_query.sql", sql)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(targets(g, "sql:stmt:broken/broken_query.sql:1", EdgeKind.READS)).contains("db:table:TB_BROKEN", "db:table:TB_BROKEN_CHILD");
            assertThat(hasEdge(g, "sql:stmt:broken/broken_query.sql:8", EdgeKind.WRITES, "db:table:TB_BROKEN")).isTrue();
            List<io.codeatlas.core.model.Diagnostic> failed = diagnostics(g, DiagnosticCodes.PARSE_FAILED);
            assertThat(failed).hasSize(2);
            assertThat(failed).allMatch(d -> d.severity() == Severity.WARN && d.location().file().equals("broken/broken_query.sql"));
            assertThat(failed).extracting(d -> d.location().startLine()).containsExactlyInAnyOrder(3, 9);
        }
    }

    @Test
    @DisplayName("F-006-AC11 내장 소스의 큰 문제는 PARSE_FAILED(WARN) 한 번, 임의의 쓰레기 입력에도 예외가 없다")
    void brokenEmbedded() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String owner = embed(ctx, "broken", 10, false, Map.of("variant", "1"), "SELECT a.ID FROM TB_A a WHERE a.X IN (SELECT b.X FROM TB_B b");
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, "SELECT a.ID FROM TB_A a WHERE a.X IN (SELECT b.X FROM TB_B b",
                    SourceLocation.of(MAPPER, 10, 5, 10, 5), false, Map.of("variant", "2")));
            embed(ctx, "junk1", 20, false, Map.of(), ")))) ((( ''' \"\" ;;; @@ {{ }} ::: ... ,,, SELECT FROM WHERE AND OR");
            embed(ctx, "junk2", 30, true, Map.of(), "");
            embed(ctx, "junk3", 40, true, Map.of(), null);
            embed(ctx, "junk4", 50, false, Map.of(), "SELECT " + "(".repeat(3000));
            assertThatCode(() -> TestProject.run(ctx, new SqlAnalyzer())).doesNotThrowAnyException();
            GraphModel g = ctx.graph();
            assertThat(targets(g, owner, EdgeKind.READS)).contains("db:table:TB_A", "db:table:TB_B");
            assertThat(hasEdge(g, "db:table:TB_A", EdgeKind.JOINS, "db:table:TB_B")).isTrue();
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED).stream().filter(d -> d.location().startLine() == 10)).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-006-AC12 F-006-AC37 방언 판정: 내장 소스 attrs·파일 경로, 구문 특징 순(--dialect 옵션은 표기로 찍지 않는다)")
    void dialects() {
        try (TestProject project = TestProject.create()
                .file("db/db2/x.sql", "SELECT ID FROM TB_A;\n")
                .file("db/oracle/y.sql", "SELECT ID FROM TB_A;\n")
                .file("db/plain/feat_db2.sql", "SELECT ID FROM TB_A FETCH FIRST 1 ROWS ONLY WITH UR;\n")
                .file("db/plain/none.sql", "SELECT ID FROM TB_A;\n")) {
            AnalysisContext ctx = context(project);
            String ora = embed(ctx, "ora", 10, false, Map.of(), "SELECT NVL(a.X, 0) FROM TB_A a, TB_B b WHERE a.ID = b.ID(+)");
            String db2 = embed(ctx, "db2", 20, false, Map.of("dialect", "db2", "databaseId", "db2"), "SELECT NVL(X, 0) FROM TB_A");
            String none = embed(ctx, "none", 30, false, Map.of(), "SELECT X FROM TB_A");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(node(g, "sql:stmt:db/db2/x.sql:1").attrString("dialect")).isEqualTo("DB2");
            assertThat(node(g, "sql:stmt:db/oracle/y.sql:1").attrString("dialect")).isEqualTo("ORACLE");
            assertThat(node(g, "sql:stmt:db/plain/feat_db2.sql:1").attrString("dialect")).isEqualTo("DB2");
            assertThat(node(g, "sql:stmt:db/plain/none.sql:1").attr("dialect")).isNull();
            assertThat(node(g, ora).attrString("dialect")).isEqualTo("ORACLE");
            assertThat(node(g, db2).attrString("dialect")).isEqualTo("DB2");
            assertThat(node(g, none).attr("dialect")).isNull();
        }
        try (TestProject project = TestProject.create().file("db/db2/x.sql", "SELECT ID FROM TB_A;\n")
                .options(AnalysisOptions.builder().dialect(Dialect.ORACLE).build())) {
            AnalysisContext ctx = context(project);
            String db2 = embed(ctx, "db2", 20, false, Map.of("dialect", "db2"), "SELECT X FROM TB_A");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            // --dialect는 구문의 dialect 표기로 찍지 않는다(F-006-R37. 전에는 옵션이 먼저였다): 경로와 내장 소스 attrs의 방언이 남는다
            assertThat(node(g, "sql:stmt:db/db2/x.sql:1").attrString("dialect")).isEqualTo("DB2");
            assertThat(node(g, db2).attrString("dialect")).isEqualTo("DB2");
        }
    }
}
