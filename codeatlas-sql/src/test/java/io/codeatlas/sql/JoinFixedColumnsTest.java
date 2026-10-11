package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.edge;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-006-R13: JOINS 엣지의 고정 컬럼 attrs. 조인한 테이블 인스턴스의 컬럼 가운데 같은 구문에서 리터럴·바인드와 {@code =}로 묶인 것을
 * {@code leftFixedColumns}/{@code rightFixedColumns}(+ {@code leftFixedValues}/{@code rightFixedValues})로 남긴다.
 * 공통코드 테이블처럼 복합 PK의 일부를 상수로 고정한 조인을 링커(F-018-R9)가 N:1로 확정하는 근거다.
 */
class JoinFixedColumnsTest {

    private static final String MAPPER = "src/main/resources/mapper/CodeMapper.xml";

    private static String embed(AnalysisContext ctx, String statementId, int line, String sql) {
        String owner = Ids.mybatisStatement("com.a.CodeMapper", statementId);
        if (!ctx.graph().hasNode(owner)) {
            ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(statementId)
                    .location(SourceLocation.of(MAPPER, line)).build());
        }
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, sql, SourceLocation.of(MAPPER, line, 5, line, 5), false, Map.of()));
        return owner;
    }

    private static AnalysisContext context(TestProject project) {
        return project.file(MAPPER, "<mapper namespace=\"com.a.CodeMapper\"/>").context();
    }

    private static Edge onlyJoin(GraphModel g, String from, String to) {
        List<Edge> edges = g.edges("db:table:" + from, EdgeKind.JOINS, "db:table:" + to);
        assertThat(edges).as(from + " JOINS " + to).hasSize(1);
        return edges.get(0);
    }

    @Test
    @DisplayName("F-006-AC13 WHERE 절의 (+) 조인과 상수 조건: 조인 상대 인스턴스의 고정 컬럼과 값이 JOINS attrs에 남는다")
    void oracleOuterJoinWithLiteral() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            String owner = embed(ctx, "selectOrderList", 10,
                    "SELECT O.ORDER_ID, C.CODE_NM FROM TB_ORDER O, TB_CODE C "
                            + "WHERE O.ORDER_STATUS = C.CODE(+) AND C.GROUP_CD(+) = 'ORDER_STATUS'");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            Edge join = onlyJoin(g, "TB_ORDER", "TB_CODE");
            assertThat(join.attrString("statement")).isEqualTo(owner);
            assertThat(join.attrList("leftColumns")).containsExactly("ORDER_STATUS");
            assertThat(join.attrList("rightColumns")).containsExactly("CODE");
            assertThat(join.attrList("rightFixedColumns")).containsExactly("GROUP_CD");
            assertThat(join.attrList("rightFixedValues")).containsExactly("GROUP_CD=ORDER_STATUS");
            assertThat(join.attrs()).doesNotContainKeys("leftFixedColumns", "leftFixedValues");
        }
    }

    @Test
    @DisplayName("F-006-AC13 ON 절의 상수 조건, 상관 서브쿼리의 상수 조건, 바인드(COL=?)도 고정 컬럼이다")
    void ansiOnAndCorrelatedSubquery() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            embed(ctx, "selectOrderHistory", 10,
                    "SELECT H.HIST_ID, C.CODE_NM FROM TB_ORDER_HIST H LEFT JOIN TB_CODE C "
                            + "ON C.GROUP_CD = 'ORDER_STATUS' AND C.CODE = H.ORDER_STATUS WHERE H.ORDER_ID = #{orderId}");
            embed(ctx, "selectPayments", 20,
                    "SELECT P.PAYMENT_ID, (SELECT C.CODE_NM FROM TB_CODE C WHERE C.GROUP_CD = 'PAY_METHOD' AND C.CODE = P.PAY_METHOD_CD) AS NM "
                            + "FROM TB_PAYMENT P WHERE P.ORDER_ID = ?");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            Edge hist = onlyJoin(g, "TB_CODE", "TB_ORDER_HIST");
            assertThat(hist.attrList("leftFixedColumns")).containsExactly("GROUP_CD");
            assertThat(hist.attrList("leftFixedValues")).containsExactly("GROUP_CD=ORDER_STATUS");
            assertThat(hist.attrList("rightFixedColumns")).containsExactly("ORDER_ID");
            assertThat(hist.attrList("rightFixedValues")).containsExactly("ORDER_ID=?");

            Edge pay = onlyJoin(g, "TB_CODE", "TB_PAYMENT");
            assertThat(pay.attrList("leftFixedColumns")).containsExactly("GROUP_CD");
            assertThat(pay.attrList("leftFixedValues")).containsExactly("GROUP_CD=PAY_METHOD");
            assertThat(pay.attrList("rightFixedColumns")).containsExactly("ORDER_ID");
            assertThat(pay.attrList("rightFixedValues")).containsExactly("ORDER_ID=?");
        }
    }

    @Test
    @DisplayName("F-006-AC13 IN (리터럴 하나)와 숫자 리터럴은 고정이고, <>·범위·OR·NOT·IN (여럿)·IN (바인드)·NULL 비교·컬럼끼리의 등치는 고정이 아니다")
    void onlyConjunctiveEqualityWithConstant() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            embed(ctx, "mixed", 10,
                    "SELECT 1 FROM TB_A A JOIN TB_B B ON A.ID = B.A_ID "
                            + "WHERE B.K1 <> 'X' AND B.K2 > 1 AND (B.K3 = 'Y' OR B.K4 = 'Z') AND B.K5 IN ('P') AND B.K6 IN ('P', 'Q') "
                            + "AND B.K7 BETWEEN 1 AND 2 AND NOT (B.K8 = 'N') AND B.K9 = 7 AND B.K10 = A.X AND B.K11 IS NULL AND B.K12 = NULL "
                            + "AND 'it''s' = B.K13 AND B.K14 IN (#{status}) AND B.K15 IN (?)");
            embed(ctx, "plain", 20, "SELECT 1 FROM TB_C C JOIN TB_D D ON C.ID = D.C_ID");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            Edge mixed = onlyJoin(g, "TB_A", "TB_B");
            assertThat(mixed.attrList("leftColumns")).containsExactly("ID", "X");
            assertThat(mixed.attrList("rightColumns")).containsExactly("A_ID", "K10");
            assertThat(mixed.attrList("rightFixedColumns")).containsExactly("K5", "K9", "K13");
            assertThat(mixed.attrList("rightFixedValues")).containsExactly("K5=P", "K9=7", "K13=it's");
            assertThat(mixed.attrs()).doesNotContainKeys("leftFixedColumns", "leftFixedValues");

            Edge plain = onlyJoin(g, "TB_C", "TB_D");
            assertThat(plain.attrs()).doesNotContainKeys("leftFixedColumns", "leftFixedValues", "rightFixedColumns", "rightFixedValues");
        }
    }

    @Test
    @DisplayName("F-006-AC13 고정 컬럼은 테이블 인스턴스 단위다: 자기 조인의 양쪽, 인라인 뷰 안의 조건, OR 아래 서브쿼리 안의 조건")
    void perInstance() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            embed(ctx, "self", 10,
                    "SELECT 1 FROM TB_CODE A, TB_CODE B WHERE A.CODE = B.UP_CODE AND A.GROUP_CD = 'X' AND B.GROUP_CD = 'Y'");
            embed(ctx, "inline", 20,
                    "SELECT 1 FROM TB_ORDER O JOIN (SELECT CODE, CODE_NM FROM TB_CODE WHERE GROUP_CD = 'S') V ON V.CODE = O.ORDER_STATUS");
            embed(ctx, "underOr", 30,
                    "SELECT 1 FROM TB_PAY P WHERE P.AMT > 0 OR EXISTS (SELECT 1 FROM TB_KIND K WHERE K.GRP = 'G' AND K.CD = P.KIND_CD)");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            Edge self = onlyJoin(g, "TB_CODE", "TB_CODE");
            assertThat(self.attrList("leftColumns")).containsExactly("CODE");
            assertThat(self.attrList("rightColumns")).containsExactly("UP_CODE");
            assertThat(self.attrList("leftFixedValues")).containsExactly("GROUP_CD=X");
            assertThat(self.attrList("rightFixedValues")).containsExactly("GROUP_CD=Y");

            Edge inline = edge(g, "db:table:TB_CODE", EdgeKind.JOINS, "db:table:TB_ORDER");
            assertThat(inline.attrList("leftFixedColumns")).containsExactly("GROUP_CD");
            assertThat(inline.attrList("leftFixedValues")).containsExactly("GROUP_CD=S");

            Edge underOr = onlyJoin(g, "TB_KIND", "TB_PAY");
            assertThat(underOr.attrList("leftFixedColumns")).containsExactly("GRP");
            assertThat(underOr.attrs()).doesNotContainKeys("rightFixedColumns");
        }
    }

    @Test
    @DisplayName("F-006-AC13 같은 주인의 전개본을 합칠 때 고정 컬럼은 모든 전개본에 있는 것만 남기고 값은 모은다")
    void variantsIntersect() {
        try (TestProject project = TestProject.create()) {
            AnalysisContext ctx = context(project);
            embed(ctx, "sometimes", 10, "SELECT 1 FROM TB_ORDER O, TB_CODE C WHERE O.ST = C.CODE AND C.GROUP_CD = 'A' AND C.USE_YN = 'Y'");
            embed(ctx, "sometimes", 10, "SELECT 1 FROM TB_ORDER O, TB_CODE C WHERE O.ST = C.CODE AND C.USE_YN = 'Y'");
            embed(ctx, "always", 20, "SELECT 1 FROM TB_PAY P, TB_CODE C WHERE P.CD = C.CODE AND C.GROUP_CD = 'A'");
            embed(ctx, "always", 20, "SELECT 1 FROM TB_PAY P, TB_CODE C WHERE P.CD = C.CODE AND C.GROUP_CD = 'B' AND P.ID = ?");
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();

            Edge sometimes = onlyJoin(g, "TB_ORDER", "TB_CODE");
            assertThat(sometimes.attrList("rightFixedColumns")).containsExactly("USE_YN");
            assertThat(sometimes.attrList("rightFixedValues")).containsExactly("USE_YN=Y");

            Edge always = onlyJoin(g, "TB_PAY", "TB_CODE");
            assertThat(always.attrList("rightFixedColumns")).containsExactly("GROUP_CD");
            assertThat(always.attrList("rightFixedValues")).containsExactly("GROUP_CD=A", "GROUP_CD=B");
            assertThat(always.attrs()).doesNotContainKeys("leftFixedColumns");
        }
    }

    @Test
    @DisplayName("F-006-AC13 루틴 본문의 구문에서는 변수·매개변수와의 등치도 바인드(COL=?)로 본다")
    void routineVariablesAreBinds() {
        String source = """
                CREATE OR REPLACE PROCEDURE SP_CODE (P_GRP IN VARCHAR2) IS
                    V_NM VARCHAR2(100);
                BEGIN
                    SELECT C.CODE_NM
                      INTO V_NM
                      FROM TB_ORD O, TB_CODE C
                     WHERE O.ST = C.CODE
                       AND C.GROUP_CD = P_GRP
                       AND O.ORD_ID = 1;
                END;
                /
                """;
        try (TestProject project = TestProject.create().file("db/oracle/sp_code.prc", source)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            Edge join = onlyJoin(g, "TB_ORD", "TB_CODE");
            assertThat(join.attrList("rightFixedColumns")).containsExactly("GROUP_CD");
            assertThat(join.attrList("rightFixedValues")).containsExactly("GROUP_CD=?");
            assertThat(join.attrList("leftFixedValues")).containsExactly("ORD_ID=1");
            assertThat(g.node("db:column:TB_CODE.P_GRP")).isEmpty();
        }
    }
}
