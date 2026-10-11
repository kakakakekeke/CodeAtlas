package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IncludeTest {

    private static final String NS = "com.a.OrderMapper";

    @Test
    @DisplayName("F-009-AC12 다른 파일·다른 namespace의 조각과 조각 체인을 전개하고 INCLUDES 엣지를 만든다")
    void crossFileIncludeChain() {
        String orderXml = Mb.mapper(NS, """
                  <sql id="baseCols">O.ORDER_ID, O.MEMBER_ID</sql>
                  <sql id="cols"><include refid="baseCols"/>, O.ORDER_DT</sql>
                  <select id="selectPage" resultType="map">
                    <include refid="common.pagingHeader"/>
                    SELECT <include refid="cols"/>
                      FROM TB_ORDER O
                     WHERE O.CLOSE_YN = 'N'
                    <include refid="common.pagingFooter"/>
                  </select>
                """);
        String commonXml = Mb.mapper("common", """
                  <sql id="pagingHeader">
                    SELECT * FROM (SELECT ROWNUM AS RNUM, PAGE_T.* FROM (
                  </sql>
                  <sql id="pagingFooter">
                    <![CDATA[
                    ) PAGE_T WHERE ROWNUM <= #{endRow}) WHERE RNUM > #{startRow}
                    ]]>
                  </sql>
                """);
        // 파일 이름 순서상 include하는 쪽(a_order)이 조각을 정의한 쪽(z_common)보다 먼저 읽힌다
        try (TestProject project = TestProject.create()
                .file("mapper/a_order.xml", orderXml)
                .file("mapper/z_common.xml", commonXml)) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.text(ctx, NS, "selectPage")).isEqualTo(
                    "SELECT * FROM (SELECT ROWNUM AS RNUM, PAGE_T.* FROM ("
                            + " SELECT O.ORDER_ID, O.MEMBER_ID, O.ORDER_DT FROM TB_ORDER O WHERE O.CLOSE_YN = 'N'"
                            + " ) PAGE_T WHERE ROWNUM <= ?) WHERE RNUM > ?");
            assertThat(Mb.variants(ctx, NS, "selectPage").get(0).exactPositions()).isFalse();
            // include만 있고 동적 태그와 ${}가 없으면 dynamic은 false
            assertThat(Mb.stmt(ctx, NS, "selectPage").attr("dynamic")).isEqualTo(false);

            String stmtId = Ids.mybatisStatement(NS, "selectPage");
            List<Edge> fromStmt = Mb.edges(ctx, stmtId, EdgeKind.INCLUDES);
            assertThat(fromStmt).extracting(Edge::to).containsExactlyInAnyOrder(
                    Ids.mybatisFragment("common", "pagingHeader"),
                    Ids.mybatisFragment("common", "pagingFooter"),
                    Ids.mybatisFragment(NS, "cols"));
            assertThat(fromStmt).allSatisfy(e -> assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED));
            int[] includeAt = Mb.lineCol(orderXml, "<include refid=\"common.pagingHeader\"");
            Edge header = fromStmt.stream().filter(e -> e.to().endsWith("pagingHeader")).findFirst().orElseThrow();
            assertThat(header.location().file()).isEqualTo("mapper/a_order.xml");
            assertThat(header.location().startLine()).isEqualTo(includeAt[0]);
            assertThat(header.location().startColumn()).isEqualTo(includeAt[1]);

            assertThat(Mb.edges(ctx, Ids.mybatisFragment(NS, "cols"), EdgeKind.INCLUDES)).extracting(Edge::to)
                    .containsExactly(Ids.mybatisFragment(NS, "baseCols"));
            assertThat(ctx.graph().node(Ids.mybatisFragment(NS, "cols")).orElseThrow().attrString("sql"))
                    .isEqualTo("O.ORDER_ID, O.MEMBER_ID, O.ORDER_DT");
            assertThat(ctx.graph().diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-009-AC12 조각 안의 동적 태그와 choose 분기도 구문의 전개본에 반영된다")
    void dynamicFragment() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="cond">
                    <if test="memberId != null">AND O.MEMBER_ID = #{memberId}</if>
                    <choose>
                      <when test="from != null">AND O.ORDER_DT >= #{from}</when>
                      <otherwise>AND O.ORDER_DT >= (SELECT MIN(BASE_DT) FROM TB_BASE_DT)</otherwise>
                    </choose>
                  </sql>
                  <select id="cnt" resultType="int">
                    SELECT COUNT(*) FROM TB_ORDER O WHERE 1 = 1
                    <include refid="cond"/>
                  </select>
                """);
        assertThat(Mb.texts(ctx, NS, "cnt")).containsExactly(
                "SELECT COUNT(*) FROM TB_ORDER O WHERE 1 = 1 AND O.MEMBER_ID = ? AND O.ORDER_DT >= ?",
                "SELECT COUNT(*) FROM TB_ORDER O WHERE 1 = 1 AND O.MEMBER_ID = ? AND O.ORDER_DT >= (SELECT MIN(BASE_DT) FROM TB_BASE_DT)");
        assertThat(Mb.stmt(ctx, NS, "cnt").attr("dynamic")).isEqualTo(true);
        Node fragment = ctx.graph().node(Ids.mybatisFragment(NS, "cond")).orElseThrow();
        assertThat(fragment.attr("dynamic")).isEqualTo(true);
    }

    @Test
    @DisplayName("F-009-AC13 include의 property로 정해진 ${name}은 값으로 치환한다")
    void includeProperties() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="cols">${alias}.ORDER_ID, ${alias}.MEMBER_ID</sql>
                  <sql id="tbl">TB_${suffix} ${alias}</sql>
                  <sql id="outer"><include refid="tbl"><property name="suffix" value="${name}_HIST"/></include></sql>
                  <select id="sel" resultType="map">
                    SELECT <include refid="cols"><property name="alias" value="O"/></include>
                      FROM <include refid="outer">
                             <property name="name" value="ORDER"/>
                             <property name="alias" value="O"/>
                           </include>
                  </select>
                  <select id="unbound">SELECT <include refid="cols"/> FROM TB_ORDER</select>
                """);

        assertThat(Mb.text(ctx, NS, "sel")).isEqualTo("SELECT O.ORDER_ID, O.MEMBER_ID FROM TB_ORDER_HIST O");
        assertThat(Mb.stmt(ctx, NS, "sel").attr("dynamic")).isEqualTo(false);
        assertThat(Mb.text(ctx, NS, "unbound")).isEqualTo("SELECT __DYNAMIC__.ORDER_ID, __DYNAMIC__.MEMBER_ID FROM TB_ORDER");
        assertThat(Mb.stmt(ctx, NS, "unbound").attr("dynamic")).isEqualTo(true);
        assertThat(Mb.diags(ctx, DiagnosticCodes.DYNAMIC_SQL)).singleElement()
                .satisfies(d -> assertThat(d.message()).contains(NS + ".unbound"));
    }

    @Test
    @DisplayName("F-009-AC13 refid의 ${}도 property로 풀어 조각을 찾는다")
    void refidWithProperty() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="tblA">TB_A</sql>
                  <sql id="pick"><include refid="${target}"/></sql>
                  <select id="byRefid">
                    SELECT 1 FROM <include refid="pick"><property name="target" value="tblA"/></include>
                  </select>
                """);
        assertThat(Mb.text(ctx, NS, "byRefid")).isEqualTo("SELECT 1 FROM TB_A");
    }

    @Test
    @DisplayName("F-009-AC14 없는 조각은 미해결 엣지와 진단으로 남기고 나머지는 전개한다")
    void unresolvedInclude() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="sel" resultType="map">
                    SELECT <include refid="nowhere.frag"/> A
                      FROM TB_ORDER
                    <include refid="localMissing"/>
                  </select>
                """);

        assertThat(Mb.text(ctx, NS, "sel")).isEqualTo("SELECT A FROM TB_ORDER");
        List<Edge> edges = Mb.edges(ctx, Ids.mybatisStatement(NS, "sel"), EdgeKind.INCLUDES);
        assertThat(edges).extracting(Edge::to).containsExactlyInAnyOrder(
                Ids.unresolved("mybatis-fragment", "nowhere.frag"),
                Ids.unresolved("mybatis-fragment", NS + ".localMissing"));
        assertThat(edges).allSatisfy(e -> assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED));
        assertThat(ctx.graph().node(Ids.unresolved("mybatis-fragment", "nowhere.frag")).orElseThrow().kind())
                .isEqualTo(NodeKind.UNRESOLVED);
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(2);
    }

    @Test
    @DisplayName("F-009-AC14 순환 include는 끊고 진단을 남긴다")
    void cyclicInclude() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="a">A_COL, <include refid="b"/></sql>
                  <sql id="b">B_COL, <include refid="a"/></sql>
                  <sql id="self">S_COL <include refid="self"/></sql>
                  <select id="sel">SELECT <include refid="a"/> X FROM TB_A</select>
                """);

        assertThat(Mb.text(ctx, NS, "sel")).isEqualTo("SELECT A_COL, B_COL, X FROM TB_A");
        assertThat(Mb.edges(ctx, Ids.mybatisFragment(NS, "a"), EdgeKind.INCLUDES)).extracting(Edge::to)
                .containsExactly(Ids.mybatisFragment(NS, "b"));
        assertThat(Mb.edges(ctx, Ids.mybatisFragment(NS, "b"), EdgeKind.INCLUDES)).extracting(Edge::to)
                .containsExactly(Ids.mybatisFragment(NS, "a"));
        assertThat(ctx.graph().diagnostics()).isNotEmpty().allSatisfy(d -> {
            assertThat(d.code()).isEqualTo(DiagnosticCodes.UNSUPPORTED_SYNTAX);
            assertThat(d.message()).contains("순환");
        });
        // 같은 include 지점의 순환은 한 번만 보고한다: a→b→a, b→a→b, self→self
        assertThat(ctx.graph().diagnostics()).hasSize(3);
    }

    @Test
    @DisplayName("F-009-AC12 조각 안의 한정하지 않은 refid는 구문의 namespace에서 먼저 찾고, 없으면 조각의 namespace에서 찾아 INFERRED로 둔다")
    void nestedUnqualifiedRefid() {
        try (TestProject project = TestProject.create()
                .file("mapper/common.xml", Mb.mapper("common", """
                          <sql id="useYn">AND USE_YN = 'Y'</sql>
                          <sql id="cond">AND DEL_YN = 'N' <include refid="useYn"/></sql>
                        """))
                .file("mapper/order.xml", Mb.mapper(NS, """
                          <select id="sel">SELECT 1 FROM TB_A WHERE 1 = 1 <include refid="common.cond"/></select>
                        """))) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.text(ctx, NS, "sel")).isEqualTo("SELECT 1 FROM TB_A WHERE 1 = 1 AND DEL_YN = 'N' AND USE_YN = 'Y'");
            List<Edge> nested = Mb.edges(ctx, Ids.mybatisFragment("common", "cond"), EdgeKind.INCLUDES);
            assertThat(nested).extracting(Edge::to).containsExactly(Ids.mybatisFragment("common", "useYn"));
            assertThat(Mb.diags(ctx, DiagnosticCodes.UNRESOLVED_REFERENCE)).isEmpty();
        }
    }
}
