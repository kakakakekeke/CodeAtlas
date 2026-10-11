package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 요소 id에 namespace가 이미 붙어 있는 매퍼(F-009-R13). 전자정부 템플릿의 {@code <select id="loginDAO.actionLogin">} 꼴. */
class NamespaceIdTest {

    private static final String NS = "signDAO";

    @Test
    @DisplayName("F-009-AC25 id가 '<namespace>.'로 시작하는 구문은 namespace를 다시 붙이지 않는다")
    void statementIdAlreadyQualified() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="signDAO.actionSign" resultType="map">SELECT ID FROM TB_SIGN WHERE ID = #{id}</select>
                  <select id="plain" resultType="map">SELECT 1 FROM TB_PLAIN</select>
                """);

        assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).extracting(Node::id).containsExactlyInAnyOrder(
                "mybatis:stmt:signDAO.actionSign", "mybatis:stmt:signDAO.plain");
        Node node = ctx.graph().node("mybatis:stmt:signDAO.actionSign").orElseThrow();
        assertThat(node.attrString("namespace")).isEqualTo(NS);
        assertThat(node.attrString("statementId")).isEqualTo("actionSign");
        assertThat(node.name()).isEqualTo("actionSign");
        assertThat(node.qualifiedName()).isEqualTo("signDAO.actionSign");
        assertThat(Mb.edges(ctx, Ids.mybatisMapper(NS), EdgeKind.CONTAINS)).extracting(Edge::to)
                .containsExactlyInAnyOrder("mybatis:stmt:signDAO.actionSign", "mybatis:stmt:signDAO.plain");
        // 내장 소스의 주인도 같은 노드다
        assertThat(Mb.text(ctx, NS, "actionSign")).isEqualTo("SELECT ID FROM TB_SIGN WHERE ID = ?");
        assertThat(ctx.graph().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-009-AC26 sql·resultMap의 id와 include·resultMap 참조에도 같은 규칙을 쓴다. namespace로 시작하지 않는 점 있는 id는 원문 그대로 쓰고 WARN")
    void fragmentResultMapAndForeignDottedId() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <resultMap id="signDAO.signMap" type="com.a.vo.SignVO">
                    <result property="id" column="SIGN_ID"/>
                  </resultMap>
                  <sql id="signDAO.cols">SIGN_ID, SIGN_NM</sql>
                  <select id="signDAO.byShortRef" resultMap="signMap">
                    SELECT <include refid="cols"/> FROM TB_SIGN
                  </select>
                  <select id="byFullRef" resultMap="signDAO.signMap">
                    SELECT <include refid="signDAO.cols"/> FROM TB_SIGN
                  </select>
                  <select id="elsewhere.thing">SELECT 1 FROM TB_ELSE</select>
                """);

        assertThat(ctx.graph().nodes(NodeKind.SQL_FRAGMENT)).extracting(Node::id)
                .containsExactly("mybatis:fragment:signDAO.cols");
        Node fragment = ctx.graph().node("mybatis:fragment:signDAO.cols").orElseThrow();
        assertThat(fragment.attrString("fragmentId")).isEqualTo("cols");
        assertThat(fragment.name()).isEqualTo("cols");

        for (String id : new String[] {"byShortRef", "byFullRef"}) {
            String stmt = Ids.mybatisStatement(NS, id);
            assertThat(Mb.text(ctx, NS, id)).isEqualTo("SELECT SIGN_ID, SIGN_NM FROM TB_SIGN");
            assertThat(Mb.edges(ctx, stmt, EdgeKind.INCLUDES)).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo("mybatis:fragment:signDAO.cols");
                assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
            });
            assertThat(Mb.edges(ctx, stmt, EdgeKind.USES_TYPE)).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Ids.javaType("com.a.vo.SignVO"));
                assertThat(e.attrString("role")).isEqualTo("resultMap");
            });
            assertThat(ctx.graph().node(stmt).orElseThrow().attrList("resultColumns")).containsExactly("SIGN_ID=id");
        }

        // namespace로 시작하지 않는 점 있는 id: MyBatis는 오류로 본다. 원문을 그대로 ID로 쓰고 경고한다.
        Node foreign = ctx.graph().node("mybatis:stmt:elsewhere.thing").orElseThrow();
        assertThat(foreign.attrString("namespace")).isEqualTo(NS);
        assertThat(foreign.attrString("statementId")).isEqualTo("elsewhere.thing");
        assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).hasSize(3);
        assertThat(ctx.graph().diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Severity.WARN);
            assertThat(d.code()).isEqualTo(DiagnosticCodes.PARSE_FAILED);
            assertThat(d.message()).contains("elsewhere.thing");
        });
        assertThat(ctx.graph().nodes(NodeKind.UNRESOLVED)).isEmpty();
    }
}
