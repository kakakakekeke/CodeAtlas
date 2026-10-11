package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MapperStructureTest {

    private static final String NS = "com.a.OrderMapper";

    @Test
    @DisplayName("F-009-AC1 매퍼, 구문, 조각 노드와 CONTAINS 엣지를 만든다")
    void mapperStatementFragmentNodes() {
        String xml = Mb.mapper(NS, """
                  <sql id="cols">ORDER_ID, MEMBER_ID</sql>
                  <select id="selectOne" resultType="map">
                    SELECT ORDER_ID FROM TB_ORDER
                  </select>
                """);
        try (TestProject project = TestProject.create().file("src/main/resources/mapper/OrderMapper.xml", xml)) {
            AnalysisContext ctx = Mb.run(project);

            Node mapper = ctx.graph().node(Ids.mybatisMapper(NS)).orElseThrow();
            assertThat(mapper.kind()).isEqualTo(NodeKind.MYBATIS_MAPPER);
            assertThat(mapper.attrString("namespace")).isEqualTo(NS);
            assertThat(mapper.name()).isEqualTo("OrderMapper");
            assertThat(mapper.declared()).isTrue();

            Node stmt = Mb.stmt(ctx, NS, "selectOne");
            assertThat(stmt.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
            assertThat(stmt.name()).isEqualTo("selectOne");
            assertThat(stmt.qualifiedName()).isEqualTo(NS + ".selectOne");
            int[] expected = Mb.lineCol(xml, "<select id=\"selectOne\"");
            assertThat(stmt.location().file()).isEqualTo("src/main/resources/mapper/OrderMapper.xml");
            assertThat(stmt.location().startLine()).isEqualTo(expected[0]);
            assertThat(stmt.location().startColumn()).isEqualTo(expected[1]);

            Node fragment = ctx.graph().node(Ids.mybatisFragment(NS, "cols")).orElseThrow();
            assertThat(fragment.kind()).isEqualTo(NodeKind.SQL_FRAGMENT);
            assertThat(fragment.attrString("sql")).isEqualTo("ORDER_ID, MEMBER_ID");

            assertThat(ctx.graph().edges(Ids.file("src/main/resources/mapper/OrderMapper.xml"), EdgeKind.CONTAINS, mapper.id()))
                    .hasSize(1);
            assertThat(ctx.graph().edges(mapper.id(), EdgeKind.CONTAINS, stmt.id())).hasSize(1);
            assertThat(ctx.graph().edges(mapper.id(), EdgeKind.CONTAINS, fragment.id())).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-009-AC2 구문 노드의 속성: statementType, sql, dynamic, 원문 속성")
    void statementAttrs() {
        String longList = "C" + "0123456789".repeat(300);
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="sel" parameterType="long" resultType="map" fetchSize="100" timeout="30">
                    SELECT O.ORDER_ID,
                           O.MEMBER_ID
                      FROM TB_ORDER O
                     WHERE O.ORDER_ID = #{orderId}
                  </select>
                  <insert id="ins" useGeneratedKeys="true" keyProperty="id">INSERT INTO TB_A (A) VALUES (#{a})</insert>
                  <update id="upd">UPDATE TB_A SET A = #{a}</update>
                  <delete id="del">DELETE FROM TB_A</delete>
                  <update id="mrg">
                    /* 재고 반영 */
                    MERGE INTO TB_A T USING DUAL ON (T.A = #{a}) WHEN MATCHED THEN UPDATE SET T.B = 1
                  </update>
                  <insert id="mrg2">merge into TB_A T USING TB_B S ON (T.A = S.A) WHEN NOT MATCHED THEN INSERT (A) VALUES (S.A)</insert>
                  <update id="notMerge">UPDATE MERGE_LOG SET A = 1</update>
                  <select id="big">SELECT %s FROM TB_BIG</select>
                """.formatted(longList));

        Node sel = Mb.stmt(ctx, NS, "sel");
        assertThat(sel.attrString("statementType")).isEqualTo("SELECT");
        assertThat(sel.attrString("namespace")).isEqualTo(NS);
        assertThat(sel.attrString("statementId")).isEqualTo("sel");
        assertThat(sel.attrString("tag")).isEqualTo("select");
        assertThat(sel.attrString("parameterType")).isEqualTo("long");
        assertThat(sel.attrString("resultType")).isEqualTo("map");
        assertThat(sel.attr("fetchSize")).isEqualTo(100);
        assertThat(sel.attr("timeout")).isEqualTo(30);
        assertThat(sel.attrString("sql")).isEqualTo("SELECT O.ORDER_ID, O.MEMBER_ID FROM TB_ORDER O WHERE O.ORDER_ID = ?");
        assertThat(sel.attr("dynamic")).isEqualTo(false);
        assertThat(sel.attr("variants")).isEqualTo(1);

        Node ins = Mb.stmt(ctx, NS, "ins");
        assertThat(ins.attrString("statementType")).isEqualTo("INSERT");
        assertThat(ins.attr("useGeneratedKeys")).isEqualTo(true);
        assertThat(ins.attrString("keyProperty")).isEqualTo("id");
        assertThat(Mb.stmt(ctx, NS, "upd").attrString("statementType")).isEqualTo("UPDATE");
        assertThat(Mb.stmt(ctx, NS, "mrg").attrString("statementType")).isEqualTo("MERGE");
        assertThat(Mb.stmt(ctx, NS, "mrg2").attrString("statementType")).isEqualTo("MERGE");
        assertThat(Mb.stmt(ctx, NS, "notMerge").attrString("statementType")).isEqualTo("UPDATE");
        assertThat(Mb.stmt(ctx, NS, "del").attrString("statementType")).isEqualTo("DELETE");

        assertThat(Mb.stmt(ctx, NS, "big").attrString("sql")).hasSize(2000);
        // 내장 소스에는 잘리지 않은 본문이 간다
        assertThat(Mb.text(ctx, NS, "big")).endsWith("FROM TB_BIG");
    }

    @Test
    @DisplayName("F-009-AC3 CALLABLE이거나 본문이 {call로 시작하면 statementType은 CALL")
    void callableStatements() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="callA" parameterType="map" statementType="CALLABLE">
                    {call PKG_ORDER.CLOSE_ORDER(
                        #{orderId,  mode=IN,  jdbcType=NUMERIC},
                        #{resultCd, mode=OUT, jdbcType=VARCHAR}
                    )}
                  </select>
                  <update id="callB">
                    { call SP_DAILY_SUM(#{a, mode=IN}, #{b, mode=OUT, jdbcType=VARCHAR}) }
                  </update>
                  <update id="callC">CALL SP_WRITE_LOG(#{jobNm}, #{msg})</update>
                  <select id="callD">{ #{ret, mode=OUT, jdbcType=NUMERIC} = call FN_X(#{a}) }</select>
                  <update id="notCall">UPDATE CALL_LOG SET A = 1</update>
                """);

        assertThat(Mb.stmt(ctx, NS, "callA").attrString("statementType")).isEqualTo("CALL");
        assertThat(Mb.stmt(ctx, NS, "callA").attrString("jdbcStatementType")).isEqualTo("CALLABLE");
        assertThat(Mb.stmt(ctx, NS, "callB").attrString("statementType")).isEqualTo("CALL");
        assertThat(Mb.stmt(ctx, NS, "callC").attrString("statementType")).isEqualTo("CALL");
        assertThat(Mb.stmt(ctx, NS, "callD").attrString("statementType")).isEqualTo("CALL");
        assertThat(Mb.stmt(ctx, NS, "notCall").attrString("statementType")).isEqualTo("UPDATE");
        assertThat(Mb.text(ctx, NS, "callA")).isEqualTo("{call PKG_ORDER.CLOSE_ORDER( ?, ? )}");
        assertThat(Mb.text(ctx, NS, "callB")).isEqualTo("{ call SP_DAILY_SUM( ?, ?) }");
        assertThat(Mb.stmt(ctx, NS, "callB").attrString("sql")).isEqualTo("{ call SP_DAILY_SUM(?, ?) }");
    }

    @Test
    @DisplayName("F-009-AC16 databaseId만 다른 같은 id의 구문은 노드 하나, 내장 소스는 dialect별")
    void databaseIdVariants() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="now" resultType="string" databaseId="oracle">SELECT SYSDATE FROM DUAL</select>
                  <select id="now" resultType="string" databaseId="db2">SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1</select>
                """);

        assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).hasSize(1);
        Node node = Mb.stmt(ctx, NS, "now");
        assertThat(node.attrList("databaseIds")).containsExactly("oracle", "db2");
        assertThat(node.attr("variants")).isEqualTo(2);
        List<EmbeddedSource> sources = Mb.sources(ctx, NS, "now");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).attrs()).containsEntry("dialect", "oracle").containsEntry("variant", "1");
        assertThat(sources.get(0).text()).contains("SYSDATE");
        assertThat(sources.get(1).attrs()).containsEntry("dialect", "db2").containsEntry("variant", "2");
        assertThat(sources.get(1).text()).contains("SYSIBM.SYSDUMMY1");
        assertThat(Mb.diags(ctx, "DUPLICATE_STATEMENT_ID")).isEmpty();
    }

    @Test
    @DisplayName("F-009-AC16 databaseId까지 같은 중복 정의는 전개본을 모두 등록하고 경고한다")
    void duplicateStatementId() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="dup">SELECT 1 FROM TB_A</select>
                  <select id="dup">SELECT 1 FROM TB_B</select>
                """);

        assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).hasSize(1);
        assertThat(Mb.texts(ctx, NS, "dup")).containsExactly("SELECT 1 FROM TB_A", "SELECT 1 FROM TB_B");
        assertThat(Mb.diags(ctx, "DUPLICATE_STATEMENT_ID")).hasSize(1);
    }

    @Test
    @DisplayName("F-009-AC23 sqlMap 파일은 INFO 진단 한 건, cache·parameterMap은 조용히 무시")
    void sqlMapAndIgnoredElements() {
        try (TestProject project = TestProject.create()
                .file("sqlmap/Old.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE sqlMap PUBLIC "-//ibatis.apache.org//DTD SQL Map 2.0//EN" "http://ibatis.apache.org/dtd/sql-map-2.dtd">
                        <sqlMap namespace="Old">
                          <select id="a" resultClass="hashmap">SELECT 1 FROM TB_OLD</select>
                          <select id="b" resultClass="hashmap">SELECT 2 FROM TB_OLD</select>
                        </sqlMap>
                        """)
                .file("mapper/New.xml", Mb.mapper(NS, """
                          <cache eviction="LRU"/>
                          <cache-ref namespace="com.a.Other"/>
                          <parameterMap id="pm" type="map"><parameter property="a"/></parameterMap>
                          <select id="a">SELECT 1 FROM TB_NEW</select>
                        """))) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.diags(ctx, DiagnosticCodes.UNSUPPORTED_SYNTAX)).singleElement().satisfies(d -> {
                assertThat(d.severity().name()).isEqualTo("INFO");
                assertThat(d.location().file()).isEqualTo("sqlmap/Old.xml");
            });
            assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).extracting(Node::id)
                    .containsExactly(Ids.mybatisStatement(NS, "a"));
            assertThat(ctx.graph().nodes(NodeKind.MYBATIS_MAPPER)).hasSize(1);
            assertThat(ctx.graph().diagnostics()).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-009-AC1 ServiceLoader로 MyBatisAnalyzer를 찾고 order는 100이다")
    void registeredAsService() {
        List<Analyzer> found = ServiceLoader.load(Analyzer.class).stream().map(ServiceLoader.Provider::get)
                .filter(a -> a instanceof MyBatisAnalyzer).toList();
        assertThat(found).hasSize(1);
        assertThat(found.get(0).order()).isEqualTo(Analyzer.ORDER_PRODUCER);
        assertThat(found.get(0).name()).isEqualTo("mybatis");
    }

    @Test
    @DisplayName("F-009-AC1 같은 id의 구문과 조각이 있어도 CONTAINS 엣지는 종류별로 하나씩이다")
    void containsEdgesAreNotDuplicated() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="x">A</sql>
                  <select id="x">SELECT <include refid="x"/> FROM T</select>
                """);
        List<Edge> contains = Mb.edges(ctx, Ids.mybatisMapper(NS), EdgeKind.CONTAINS);
        assertThat(contains).extracting(Edge::to)
                .containsExactlyInAnyOrder(Ids.mybatisFragment(NS, "x"), Ids.mybatisStatement(NS, "x"));
    }
}
