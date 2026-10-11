package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F-006-R29: 등치 조건의 한쪽(또는 양쪽)이 컬럼 하나만 감싼 형 변환·정리 함수면 그 컬럼으로 {@code JOINS}를 만들고
 * 엣지 attrs에 {@code wrapped=true}와 감싸인 쪽({@code leftWrapped}/{@code rightWrapped})을 남긴다.
 * 값을 바꾸는 함수와 연산식, 컬럼이 둘 이상 든 함수로는 만들지 않는다.
 */
class WrappedJoinTest {

    private static final String MAPPER = "src/main/resources/mapper/LogMapper.xml";

    private static String embed(AnalysisContext ctx, String statementId, int line, String sql) {
        String owner = Ids.mybatisStatement("com.a.LogMapper", statementId);
        if (!ctx.graph().hasNode(owner)) {
            ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(statementId)
                    .location(SourceLocation.of(MAPPER, line)).build());
        }
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, sql, SourceLocation.of(MAPPER, line, 5, line, 5), false, Map.of()));
        return owner;
    }

    private static AnalysisContext context(TestProject project) {
        return project.file(MAPPER, "<mapper namespace=\"com.a.LogMapper\"/>").context();
    }

    private static GraphModel analyze(String... sqls) {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            for (String sql : sqls) {
                embed(ctx, "stmt", 10, sql);
            }
            return TestProject.run(ctx, new SqlAnalyzer()).graph();
        }
    }

    private static List<Edge> joins(GraphModel g) {
        return g.edges().stream().filter(e -> e.kind() == EdgeKind.JOINS).toList();
    }

    private static Edge onlyJoin(GraphModel g, String from, String to) {
        List<Edge> edges = g.edges("db:table:" + from, EdgeKind.JOINS, "db:table:" + to);
        assertThat(edges).as(from + " JOINS " + to + " (전체: " + joins(g) + ")").hasSize(1);
        return edges.get(0);
    }

    @Test
    @DisplayName("F-006-AC29 TRIM으로 감싼 조인 키: 그 컬럼으로 JOINS를 만들고 wrapped·leftWrapped를 남긴다(고정 컬럼도 그대로)")
    void trimOnOneSide() {
        GraphModel g = analyze("SELECT a.LOG_ID, c.CODE_NM FROM TB_LOG a LEFT OUTER JOIN TB_CODE c ON TRIM(a.SE_CODE) = c.CODE "
                + "WHERE c.CODE_ID = 'COM033'");

        Edge join = onlyJoin(g, "TB_LOG", "TB_CODE");
        assertThat(join.attrList("leftColumns")).containsExactly("SE_CODE");
        assertThat(join.attrList("rightColumns")).containsExactly("CODE");
        assertThat(join.attrBoolean("wrapped")).isTrue();
        assertThat(join.attrBoolean("leftWrapped")).isTrue();
        assertThat(join.attrs()).doesNotContainKey("rightWrapped");
        assertThat(join.attrList("rightFixedValues")).containsExactly("CODE_ID=COM033");
    }

    @Test
    @DisplayName("F-006-AC29 감싸이지 않은 보통 조인에는 wrapped·leftWrapped·rightWrapped 키가 없다")
    void plainJoinHasNoKeys() {
        GraphModel g = analyze("SELECT 1 FROM TB_LOG a JOIN TB_CODE c ON a.SE_CODE = c.CODE");

        assertThat(onlyJoin(g, "TB_LOG", "TB_CODE").attrs()).doesNotContainKeys("wrapped", "leftWrapped", "rightWrapped");
    }

    static Stream<Arguments> wrappers() {
        return Stream.of(
                // 조건, 감싸인 쪽(L, R, LR)
                Arguments.of("LTRIM(a.X) = c.CODE", "L"),
                Arguments.of("RTRIM(a.X) = c.CODE", "L"),
                Arguments.of("UPPER(a.X) = c.CODE", "L"),
                Arguments.of("LOWER(a.X) = c.CODE", "L"),
                Arguments.of("TO_CHAR(a.X) = c.CODE", "L"),
                Arguments.of("TO_CHAR(a.X, 'YYYYMMDD') = c.CODE", "L"),
                Arguments.of("TO_NUMBER(a.X) = c.CODE", "L"),
                Arguments.of("CAST(a.X AS VARCHAR(10)) = c.CODE", "L"),
                Arguments.of("a.X::varchar = c.CODE", "L"),
                Arguments.of("NVL(a.X, 'N') = c.CODE", "L"),
                Arguments.of("COALESCE(a.X, 0) = c.CODE", "L"),
                Arguments.of("CHAR(a.X) = c.CODE", "L"),
                Arguments.of("VARCHAR(a.X) = c.CODE", "L"),
                Arguments.of("INTEGER(a.X) = c.CODE", "L"),
                Arguments.of("INT(a.X) = c.CODE", "L"),
                Arguments.of("DECIMAL(a.X, 10, 2) = c.CODE", "L"),
                Arguments.of("BIGINT(a.X) = c.CODE", "L"),
                Arguments.of("TRIM(BOTH ' ' FROM a.X) = c.CODE", "L"),
                Arguments.of("TRIM(UPPER(a.X)) = c.CODE", "L"),
                Arguments.of("a.X = TRIM(c.CODE)", "R"),
                Arguments.of("c.CODE = TRIM(a.X)", "L"),
                Arguments.of("UPPER(a.X) = UPPER(c.CODE)", "LR"),
                Arguments.of("TRIM(a.X) = c.CODE(+)", "L"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrappers")
    @DisplayName("F-006-AC29 형 변환·정리 함수가 컬럼 하나만 감쌌으면(중첩, 리터럴 인자 포함) 조인이고 감싸인 쪽이 남는다")
    void conversionFunctions(String condition, String sides) {
        GraphModel g = analyze("SELECT 1 FROM TB_LOG a, TB_CODE c WHERE " + condition);

        List<Edge> all = joins(g);
        assertThat(all).as(condition).hasSize(1);
        Edge join = all.get(0);
        boolean logIsFrom = join.from().equals("db:table:TB_LOG");
        assertThat(join.attrList(logIsFrom ? "leftColumns" : "rightColumns")).containsExactly("X");
        assertThat(join.attrList(logIsFrom ? "rightColumns" : "leftColumns")).containsExactly("CODE");
        assertThat(join.attrBoolean("wrapped")).isTrue();
        // 표의 L은 TB_LOG 쪽, R은 TB_CODE 쪽이다
        String logKey = logIsFrom ? "leftWrapped" : "rightWrapped";
        String codeKey = logIsFrom ? "rightWrapped" : "leftWrapped";
        if (sides.contains("L")) {
            assertThat(join.attrBoolean(logKey)).as(logKey).isTrue();
        } else {
            assertThat(join.attrs()).doesNotContainKey(logKey);
        }
        if (sides.contains("R")) {
            assertThat(join.attrBoolean(codeKey)).as(codeKey).isTrue();
        } else {
            assertThat(join.attrs()).doesNotContainKey(codeKey);
        }
    }

    static Stream<String> notJoins() {
        return Stream.of(
                "a.X || a.Y = c.CODE",
                "a.X + 1 = c.CODE",
                "SUBSTR(a.X, 1, 3) = c.CODE",
                "REPLACE(a.X, '-', '') = c.CODE",
                "DECODE(a.X, 'A', 'B') = c.CODE",
                "LPAD(a.X, 3, '0') = c.CODE",
                "CONCAT(a.X, a.Y) = c.CODE",
                "NVL(a.X, a.Y) = c.CODE",
                "COALESCE(a.X, c.Z) = c.CODE",
                "TRIM(a.X || 'A') = c.CODE",
                "TRIM(SUBSTR(a.X, 1, 2)) = c.CODE",
                "NVL(a.X, #{dflt}) = c.CODE",
                "MY_FUNC(a.X) = c.CODE",
                "PKG.TRIM(a.X) = c.CODE",
                "TRIM(a.X) = 'A'",
                "TRIM(a.X) <> c.CODE",
                "CASE WHEN a.X = 'A' THEN a.Y END = c.CODE");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notJoins")
    @DisplayName("F-006-AC29 연산식, 값을 바꾸는 함수, 컬럼이 둘 이상 든 함수, 사용자 함수로는 JOINS를 만들지 않는다")
    void valueChangingExpressionsAreNotJoins(String condition) {
        GraphModel g = analyze("SELECT 1 FROM TB_LOG a, TB_CODE c WHERE " + condition);

        assertThat(joins(g)).as(condition).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC29 같은 조인이 감싼 형태와 안 감싼 형태로 둘 다 나오면 한 엣지로 합치고 wrapped를 남기지 않는다(한 구문 안, 전개본 사이)")
    void wrappedOnlyWhenEveryEvidenceIsWrapped() {
        // 한 구문 안: 같은 인스턴스 쌍, 다른 인스턴스 쌍(서브쿼리)
        GraphModel same = analyze("SELECT 1 FROM TB_LOG a JOIN TB_CODE c ON TRIM(a.SE_CODE) = c.CODE WHERE a.SE_CODE = c.CODE");
        assertThat(onlyJoin(same, "TB_LOG", "TB_CODE").attrs()).doesNotContainKeys("wrapped", "leftWrapped", "rightWrapped");

        GraphModel other = analyze("SELECT 1 FROM TB_LOG a JOIN TB_CODE c ON TRIM(a.SE_CODE) = c.CODE "
                + "WHERE EXISTS (SELECT 1 FROM TB_LOG b, TB_CODE d WHERE b.SE_CODE = d.CODE)");
        assertThat(onlyJoin(other, "TB_LOG", "TB_CODE").attrs()).doesNotContainKeys("wrapped", "leftWrapped", "rightWrapped");

        // 전개본 사이: 한쪽은 감싸고 한쪽은 안 감쌌다
        GraphModel variants = analyze("SELECT 1 FROM TB_LOG a JOIN TB_CODE c ON TRIM(a.SE_CODE) = c.CODE",
                "SELECT 1 FROM TB_LOG a JOIN TB_CODE c ON a.SE_CODE = c.CODE");
        assertThat(onlyJoin(variants, "TB_LOG", "TB_CODE").attrs()).doesNotContainKeys("wrapped", "leftWrapped", "rightWrapped");

        // 전개본이 모두 감쌌으면 남는다(좌우가 뒤집혀 있어도 같은 쪽으로 모인다)
        GraphModel all = analyze("SELECT 1 FROM TB_LOG a JOIN TB_CODE c ON TRIM(a.SE_CODE) = c.CODE",
                "SELECT 1 FROM TB_CODE c, TB_LOG a WHERE c.CODE = RTRIM(a.SE_CODE) AND a.LOG_ID = ?");
        Edge join = onlyJoin(all, "TB_LOG", "TB_CODE");
        assertThat(join.attrBoolean("wrapped")).isTrue();
        assertThat(join.attrBoolean("leftWrapped")).isTrue();
        assertThat(join.attrs()).doesNotContainKey("rightWrapped");
    }

    @Test
    @DisplayName("F-006-AC29 여러 컬럼 조인에서 한 쌍만 감싸였어도 wrapped이고, 전개본의 더 넓은 조인과 합쳐도 그 쌍의 표시는 남는다")
    void compositeJoin() {
        GraphModel g = analyze("SELECT 1 FROM TB_LOG a JOIN TB_CODE c ON a.GRP = c.CODE_ID AND c.CODE = TRIM(a.SE_CODE)",
                "SELECT 1 FROM TB_LOG a JOIN TB_CODE c ON a.GRP = c.CODE_ID");

        Edge join = onlyJoin(g, "TB_LOG", "TB_CODE");
        assertThat(join.attrList("leftColumns")).containsExactly("GRP", "SE_CODE");
        assertThat(join.attrList("rightColumns")).containsExactly("CODE_ID", "CODE");
        assertThat(join.attrBoolean("wrapped")).isTrue();
        assertThat(join.attrBoolean("leftWrapped")).isTrue();
        assertThat(join.attrs()).doesNotContainKey("rightWrapped");
    }

    @Test
    @DisplayName("F-006-AC29 IN (서브쿼리)와 스칼라 서브쿼리 등치의 왼쪽이 감싸인 컬럼이어도 조인이다")
    void wrappedLeftOfSubquery() {
        GraphModel in = analyze("SELECT 1 FROM TB_LOG a WHERE TRIM(a.SE_CODE) IN (SELECT c.CODE FROM TB_CODE c)");
        Edge inJoin = onlyJoin(in, "TB_LOG", "TB_CODE");
        assertThat(inJoin.attrBoolean("wrapped")).isTrue();
        assertThat(inJoin.attrBoolean("leftWrapped")).isTrue();

        GraphModel scalar = analyze("SELECT 1 FROM TB_LOG a WHERE UPPER(a.SE_CODE) = (SELECT c.CODE FROM TB_CODE c WHERE c.CODE_ID = 'X')");
        Edge scalarJoin = onlyJoin(scalar, "TB_LOG", "TB_CODE");
        assertThat(scalarJoin.attrBoolean("wrapped")).isTrue();
        assertThat(scalarJoin.attrBoolean("leftWrapped")).isTrue();
    }
}
