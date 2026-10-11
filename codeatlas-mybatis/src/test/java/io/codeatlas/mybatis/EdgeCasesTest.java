package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 요구사항의 경계에 있는 입력들. */
class EdgeCasesTest {

    private static final String NS = "com.a.EdgeMapper";

    @Test
    @DisplayName("F-009-AC13 같은 조각을 다른 property로 두 번 include하면 각각 치환된다")
    void sameFragmentTwice() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="cols">${t}.ID AS ${t}_ID</sql>
                  <select id="sel">
                    SELECT <include refid="cols"><property name="t" value="A"/></include>,
                           <include refid="cols"><property name="t" value="B"/></include>
                      FROM TB_A A JOIN TB_B B ON B.ID = A.ID
                  </select>
                """);
        assertThat(Mb.text(ctx, NS, "sel")).isEqualTo("SELECT A.ID AS A_ID, B.ID AS B_ID FROM TB_A A JOIN TB_B B ON B.ID = A.ID");
        // 위치가 다른 include 둘이므로 엣지도 둘이다
        assertThat(Mb.edges(ctx, Ids.mybatisStatement(NS, "sel"), EdgeKind.INCLUDES)).hasSize(2)
                .extracting(Edge::to).containsOnly(Ids.mybatisFragment(NS, "cols"));
    }

    @Test
    @DisplayName("F-009-AC16 조각도 databaseId가 맞는 정의를 골라 전개한다")
    void fragmentByDatabaseId() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="now" databaseId="oracle">SYSDATE</sql>
                  <sql id="now" databaseId="db2">CURRENT TIMESTAMP</sql>
                  <select id="sel" databaseId="oracle">SELECT <include refid="now"/> FROM DUAL</select>
                  <select id="sel" databaseId="db2">SELECT <include refid="now"/> FROM SYSIBM.SYSDUMMY1</select>
                """);
        assertThat(Mb.texts(ctx, NS, "sel")).containsExactly("SELECT SYSDATE FROM DUAL", "SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1");
        assertThat(ctx.graph().nodes(NodeKind.SQL_FRAGMENT)).hasSize(1);
        assertThat(ctx.graph().node(Ids.mybatisFragment(NS, "now")).orElseThrow().attrList("databaseIds"))
                .containsExactly("oracle", "db2");
    }

    @Test
    @DisplayName("F-009-AC6 where 안에 include만 있어도 앞의 AND를 떼고, 중첩된 trim도 안쪽부터 적용한다")
    void whereWithIncludeAndNestedTrim() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="cond">
                    <if test="a != null">AND A = #{a}</if>
                    <if test="b != null">AND B = #{b}</if>
                  </sql>
                  <select id="sel">
                    SELECT * FROM TB_A
                    <where><include refid="cond"/></where>
                  </select>
                  <select id="nested">
                    SELECT * FROM TB_A
                    <where>
                      <trim prefix="(" suffix=")" prefixOverrides="OR">
                        <if test="x">OR X = 1</if>
                        <if test="y">OR Y = 2</if>
                      </trim>
                      <if test="z">AND Z = 3</if>
                    </where>
                  </select>
                """);
        assertThat(Mb.text(ctx, NS, "sel")).isEqualTo("SELECT * FROM TB_A WHERE A = ? AND B = ?");
        assertThat(Mb.text(ctx, NS, "nested")).isEqualTo("SELECT * FROM TB_A WHERE ( X = 1 OR Y = 2 ) AND Z = 3");
    }

    @Test
    @DisplayName("F-009-AC8 중첩 foreach와 open·close가 없는 foreach")
    void nestedForeach() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <insert id="ins">
                    INSERT INTO TB_A (A, B) VALUES
                    <foreach collection="rows" item="row" separator=",">
                      <foreach collection="row" item="v" open="(" close=")" separator=",">#{v}</foreach>
                    </foreach>
                  </insert>
                """);
        assertThat(Mb.text(ctx, NS, "ins")).isEqualTo("INSERT INTO TB_A (A, B) VALUES (?)");
    }

    @Test
    @DisplayName("F-009-AC4 BMP 밖 문자와 여러 줄 #{}가 있어도 위치가 맞다")
    void supplementaryCharactersAndMultilineParam() {
        String xml = Mb.mapper(NS, """
                  <select id="sel">
                    SELECT '😀' AS ICON, A FROM TB_EMOJI WHERE A = #{a,
                         jdbcType=VARCHAR} AND B_COL = 1
                  </select>
                """);
        try (TestProject project = TestProject.create().file("mapper/Edge.xml", xml)) {
            AnalysisContext ctx = Mb.run(project);
            EmbeddedSource src = Mb.variants(ctx, NS, "sel").get(0);
            assertThat(src.exactPositions()).isTrue();
            assertThat(Mb.norm(src.text())).isEqualTo("SELECT '😀' AS ICON, A FROM TB_EMOJI WHERE A = ? AND B_COL = 1");
            assertThat(Mb.hostLineCol(src, "TB_EMOJI")).containsExactly(Mb.lineCol(xml, "TB_EMOJI"));
            assertThat(Mb.hostLineCol(src, "B_COL")).containsExactly(Mb.lineCol(xml, "B_COL"));
        }
    }

    @Test
    @DisplayName("F-009-AC1 같은 namespace의 매퍼 파일이 둘이면 매퍼 노드는 하나이고 두 파일이 모두 CONTAINS로 잇는다")
    void sameNamespaceInTwoFiles() {
        try (TestProject project = TestProject.create()
                .file("mapper/a.xml", Mb.mapper(NS, "<select id=\"a\">SELECT 1 FROM TB_A</select>"))
                .file("mapper/b.xml", Mb.mapper(NS, "<select id=\"b\">SELECT 1 FROM TB_B</select>"))) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(ctx.graph().nodes(NodeKind.MYBATIS_MAPPER)).hasSize(1);
            assertThat(ctx.graph().incoming(Ids.mybatisMapper(NS), EdgeKind.CONTAINS)).extracting(Edge::from)
                    .containsExactlyInAnyOrder(Ids.file("mapper/a.xml"), Ids.file("mapper/b.xml"));
            assertThat(Mb.edges(ctx, Ids.mybatisMapper(NS), EdgeKind.CONTAINS)).hasSize(2);
        }
    }

    @Test
    @DisplayName("F-009-AC18 Spring XML의 p 네임스페이스 표기와 value 요소도 읽는다")
    void springPNamespace() {
        try (TestProject project = TestProject.create()
                .file("spring/a.xml", """
                        <beans xmlns="http://www.springframework.org/schema/beans" xmlns:p="http://www.springframework.org/schema/p">
                          <bean id="sf" class="org.mybatis.spring.SqlSessionFactoryBean" p:typeAliasesPackage="com.p.vo"/>
                          <bean id="sf2" class="org.mybatis.spring.SqlSessionFactoryBean">
                            <property name="typeAliasesPackage"><value>com.q.vo</value></property>
                          </bean>
                        </beans>
                        """)
                .file("src/com/p/vo/PVo.java", "package com.p.vo;\nclass PVo {}\n")
                .file("src/com/q/vo/QVo.java", "/* 주석 */\npackage com.q.vo;\nclass QVo {}\n")
                .file("mapper/Edge.xml", Mb.mapper(NS, """
                          <select id="p" resultType="pVo">SELECT 1 FROM TB_A</select>
                          <select id="q" resultType="qvo">SELECT 1 FROM TB_A</select>
                        """))) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.edges(ctx, Ids.mybatisStatement(NS, "p"), EdgeKind.USES_TYPE)).extracting(Edge::to)
                    .containsExactly(Ids.javaType("com.p.vo.PVo"));
            assertThat(Mb.edges(ctx, Ids.mybatisStatement(NS, "q"), EdgeKind.USES_TYPE)).extracting(Edge::to)
                    .containsExactly(Ids.javaType("com.q.vo.QVo"));
        }
    }

    @Test
    @DisplayName("F-009-AC2 본문이 빈 구문은 노드만 만들고 내장 소스는 등록하지 않는다")
    void emptyBody() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="empty"/>
                  <select id="blank">   </select>
                  <select id="commentOnly"><!-- SELECT 1 FROM TB_GONE --></select>
                """);
        assertThat(ctx.embedded(EmbeddedSource.SQL)).isEmpty();
        assertThat(Mb.stmt(ctx, NS, "empty").attrString("sql")).isEmpty();
        assertThat(Mb.stmt(ctx, NS, "commentOnly").attr("variants")).isEqualTo(0);
        assertThat(ctx.graph().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-009-AC11 닫히지 않은 #{ 와 SQL의 일반 중괄호는 그대로 둔다")
    void unbalancedBraces() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="a">SELECT '#{' AS X FROM TB_A</select>
                  <select id="b">SELECT '{}' AS Y, '$' AS Z, '#' AS W FROM TB_A WHERE A = #{a}</select>
                """);
        assertThat(Mb.text(ctx, NS, "a")).isEqualTo("SELECT '#{' AS X FROM TB_A");
        assertThat(Mb.text(ctx, NS, "b")).isEqualTo("SELECT '{}' AS Y, '$' AS Z, '#' AS W FROM TB_A WHERE A = ?");
    }
}
