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
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.parser.SqlParser;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 조건·절 사이에 홀로 있는 {@code ${…}} 조각(F-006-R25)과 내부 표식 {@code __DYNAMIC__}의 표시(F-006-R26).
 * 공개 프로젝트(jeesite)의 데이터 권한 필터 {@code … WHERE a.NM = #{nm} ${sqlMap.dsf} ORDER BY …}에서 PARSE_FAILED가 났고,
 * 진단 메시지와 미해결 원문에 {@code __DYNAMIC__}이 그대로 보였다.
 */
class LoneDynamicTest {

    private static final String MAPPER = "mapper/A.xml";

    private static String embed(AnalysisContext ctx, String id, boolean exact, Map<String, String> attrs, String sql) {
        String owner = Ids.mybatisStatement("aDAO", id);
        ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(id).location(SourceLocation.of(MAPPER, 4)).build());
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, sql, SourceLocation.of(MAPPER, 4, 5, 4, 5), exact, attrs));
        return owner;
    }

    private static AnalysisContext context(TestProject project) {
        return project.file(MAPPER, "<mapper namespace=\"aDAO\"/>")
                .file("db/ddl.sql", "CREATE TABLE TB_A (ID VARCHAR2(10), NM VARCHAR2(20), DEL CHAR(1), X NUMBER, Y NUMBER);\n"
                        + "CREATE TABLE TB_B (ID VARCHAR2(10), A_ID VARCHAR2(10));\n")
                .context();
    }

    @ParameterizedTest
    @DisplayName("F-006-AC25 조건·절 사이에 홀로 있는 __DYNAMIC__은 불투명한 조각으로 건너뛴다: 문제가 없다")
    @ValueSource(strings = {
            "SELECT a.ID FROM TB_A a WHERE a.NM = ? __DYNAMIC__ ORDER BY a.ID",
            "SELECT a.ID FROM TB_A a WHERE a.NM = ? __DYNAMIC__",
            "SELECT a.ID FROM TB_A a WHERE a.NM = ? __DYNAMIC__ AND a.Y = 2 __DYNAMIC__ GROUP BY a.ID __DYNAMIC__ ORDER BY a.ID",
            "SELECT a.ID FROM TB_A a WHERE a.NM = ? __DYNAMIC__ __DYNAMIC__ OR a.Y = 2",
            "SELECT a.ID FROM TB_A a __DYNAMIC__ WHERE a.X = 1",
            "SELECT a.ID FROM TB_A a LEFT JOIN TB_B b ON b.A_ID = a.ID __DYNAMIC__ WHERE 1 = 1",
            "SELECT * FROM (SELECT a.ID FROM TB_A a WHERE a.DEL = '0' __DYNAMIC__) x",
            "SELECT * FROM (SELECT a.ID FROM TB_A a __DYNAMIC__) x WHERE x.ID = ?",
            "SELECT a.ID FROM TB_A a WHERE a.ID IN (SELECT b.A_ID FROM TB_B b WHERE b.ID = ? __DYNAMIC__) __DYNAMIC__",
            "SELECT a.ID FROM TB_A a WHERE (a.X = 1 __DYNAMIC__) AND a.Y = 2",
            "SELECT CASE WHEN a.X = 1 __DYNAMIC__ THEN 1 ELSE 2 END FROM TB_A a",
            "UPDATE TB_A SET X = 1 __DYNAMIC__ WHERE ID = ?",
            "UPDATE TB_A SET X = 1, __DYNAMIC__ WHERE ID = ?",
            "UPDATE TB_A SET __DYNAMIC__ WHERE ID = ?",
            "UPDATE TB_A SET __DYNAMIC__, X = 1 WHERE ID = ? __DYNAMIC__",
            "DELETE FROM TB_A WHERE ID = ? __DYNAMIC__",
            "INSERT INTO TB_A (ID, NM) VALUES (?, ? __DYNAMIC__)",
            "INSERT INTO TB_A (ID, NM __DYNAMIC__) VALUES (?, ? __DYNAMIC__)",
            "SELECT a.ID FROM TB_A a WHERE a.NM = ? ${sqlMap.dsf} ORDER BY a.ID"})
    void loneDynamicIsSkipped(String sql) {
        ParseResult result = SqlParser.parse(sql);
        assertThat(result.problems()).as(sql).isEmpty();
        assertThat(result.salvagedReads()).as(sql).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC25 홀로 있는 조각을 건너뛰어도 테이블·컬럼·조인은 그대로이고 구문은 dynamic=true다. PARSE_FAILED가 없다")
    void factsAreKept() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String list = embed(ctx, "findList", false, Map.of(),
                    "SELECT a.ID, a.NM FROM TB_A a LEFT JOIN TB_B b ON b.A_ID = a.ID __DYNAMIC__ WHERE a.NM = ? __DYNAMIC__ ORDER BY a.ID");
            String update = embed(ctx, "update", false, Map.of(), "UPDATE TB_A SET X = 1, __DYNAMIC__ WHERE ID = ? __DYNAMIC__");
            // 테이블 자리, 선택 목록, 정렬 항목 자리의 동적 식별자는 전과 같다
            String table = embed(ctx, "table", false, Map.of(), "SELECT __DYNAMIC__ FROM __DYNAMIC__ T WHERE T.ID = ? ORDER BY __DYNAMIC__");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isEmpty();
            assertThat(targets(g, list, EdgeKind.READS)).containsExactly("db:column:TB_A.ID", "db:column:TB_A.NM", "db:column:TB_B.A_ID",
                    "db:table:TB_A", "db:table:TB_B");
            assertThat(hasEdge(g, "db:table:TB_B", EdgeKind.JOINS, "db:table:TB_A")).isTrue();
            assertThat(node(g, list).attr("dynamic")).isEqualTo(true);
            assertThat(g.outgoing(list)).noneMatch(e -> e.to().startsWith("unresolved:"));
            assertThat(targets(g, update, EdgeKind.WRITES)).containsExactly("db:column:TB_A.X", "db:table:TB_A");
            assertThat(node(g, update).attr("dynamic")).isEqualTo(true);
            assertThat(hasEdge(g, table, EdgeKind.READS, Ids.unresolved("sql-dynamic", table))).isTrue();
        }
    }

    @Test
    @DisplayName("F-006-AC26 치환 목록(dynamicParts)이 있으면 미해결 원문과 진단 메시지에 ${…} 원문이 보이고, 없으면 ${…}로 보인다. __DYNAMIC__은 어디에도 남지 않는다")
    void internalMarkerIsNotShown() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String upd = embed(ctx, "upd", false, Map.of("dynamicParts", "16:${businessTable}"), "UPDATE __DYNAMIC__ SET PROC_INS_ID = ? WHERE ID = ?");
            String part = embed(ctx, "part", false, Map.of("dynamicParts", "5:${ym}9:${schema}10:${orderBy}"),
                    "SELECT T.ID FROM TB_LOG___DYNAMIC__ T, __DYNAMIC__.TB_B B WHERE B.ID = T.ID ORDER BY __DYNAMIC__");
            String fixed = embed(ctx, "fixed", false, Map.of("dynamicParts", "7:${type}"),
                    "SELECT A.ID FROM TB_A A, TB_B B WHERE B.A_ID = A.ID AND A.NM = '__DYNAMIC__'");
            // 구문이 통째로 치환이라 종류를 모르는 경우: 메시지의 키워드 자리에 표식이 나오던 곳
            String broken = embed(ctx, "broken", false, Map.of("dynamicParts", "6:${sql}"), "__DYNAMIC__ TB_A SET X = 1");
            // 치환 목록이 없는 내장 소스(다른 생산자), 건수가 맞지 않는 목록
            String none = embed(ctx, "none", false, Map.of(), "DELETE FROM TB_LOG___DYNAMIC__ WHERE ID = ?");
            String mismatch = embed(ctx, "mismatch", false, Map.of("dynamicParts", "4:${a}4:${b}"), "SELECT * FROM __DYNAMIC__ WHERE ID = ?");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            Edge e = edge(g, upd, EdgeKind.WRITES, Ids.unresolved("sql-dynamic", upd));
            assertThat(e.attrString("text")).isEqualTo("${businessTable}");
            assertThat(node(g, Ids.unresolved("sql-dynamic", upd)).name()).isEqualTo("${businessTable}");
            assertThat(diagnostics(g, DiagnosticCodes.DYNAMIC_SQL)).extracting(Diagnostic::message)
                    .anyMatch(m -> m.contains("${businessTable}") && m.contains(upd));
            assertThat(edge(g, part, EdgeKind.READS, Ids.unresolved("sql-dynamic", part)).attrString("text")).isEqualTo("TB_LOG_${ym}");
            assertThat(hasEdge(g, part, EdgeKind.READS, "db:table:TB_B")).isTrue();
            assertThat(edge(g, "db:table:TB_B", EdgeKind.JOINS, "db:table:TB_A").attrList("rightFixedValues")).containsExactly("NM=${type}");
            assertThat(diagnostics(g, DiagnosticCodes.UNSUPPORTED_SYNTAX)).extracting(Diagnostic::message)
                    .anyMatch(m -> m.contains("지원하지 않는 구문: ${…}") && m.contains(broken));
            assertThat(edge(g, none, EdgeKind.WRITES, Ids.unresolved("sql-dynamic", none)).attrString("text")).isEqualTo("TB_LOG_${…}");
            assertThat(edge(g, mismatch, EdgeKind.READS, Ids.unresolved("sql-dynamic", mismatch)).attrString("text")).isEqualTo("${…}");

            assertThat(g.diagnostics()).extracting(Diagnostic::message).noneMatch(m -> m.contains("__DYNAMIC__"));
            assertThat(g.nodes()).noneMatch(n -> n.name().contains("__DYNAMIC__") || String.valueOf(n.attrs()).contains("__DYNAMIC__"));
            assertThat(g.edges()).noneMatch(x -> String.valueOf(x.attrs()).contains("__DYNAMIC__"));
            assertThat(fixed).isNotNull();
        }
    }

    @Test
    @DisplayName("F-006-AC26 위치를 보존한 내장 소스는 치환 목록으로 원문을 되돌려도 엣지의 위치가 같다(mybatis가 채운 공백까지 바꾼다)")
    void exactPositionsAreKept() {
        // 원문: SELECT A.ID, A.NM FROM ${schemaName}.TB_A A WHERE A.X = 1 ${sqlMap.dsf} AND A.Y = 2
        String padded = "SELECT A.ID, A.NM FROM   __DYNAMIC__.TB_A A WHERE A.X = 1 __DYNAMIC__   AND A.Y = 2";
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String with = embed(ctx, "with", true, Map.of("dynamicParts", "13:${schemaName}13:${sqlMap.dsf}"), padded);
            String without = embed(ctx, "without", true, Map.of(), padded);
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isEmpty();
            for (String column : new String[] {"db:column:TB_A.ID", "db:column:TB_A.NM", "db:column:TB_A.X", "db:column:TB_A.Y"}) {
                assertThat(edge(g, with, EdgeKind.READS, column).location()).as(column).isEqualTo(edge(g, without, EdgeKind.READS, column).location());
            }
            assertThat(edge(g, with, EdgeKind.READS, "db:column:TB_A.Y").location().startColumn()).isEqualTo(5 + padded.indexOf("A.Y"));
            // 스키마 자리가 동적인 테이블 이름은 원문(${schemaName})이 시작하는 열부터다: 채운 공백 두 칸이 원문의 일부로 돌아왔다
            assertThat(edge(g, with, EdgeKind.READS, "db:table:TB_A").location().startColumn()).isEqualTo(5 + padded.indexOf("  __DYNAMIC__.TB_A"));
            assertThat(edge(g, with, EdgeKind.READS, "db:table:TB_A").location().endColumn())
                    .isEqualTo(edge(g, without, EdgeKind.READS, "db:table:TB_A").location().endColumn());
        }
    }
}
