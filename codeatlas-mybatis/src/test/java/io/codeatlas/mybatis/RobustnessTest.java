package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobustnessTest {

    @Test
    @DisplayName("F-009-AC21 깨진 XML은 PARSE_FAILED를 남기고 오류 전의 구문과 다른 파일은 살린다")
    void brokenXmlKeepsEarlierStatements() {
        String broken = Mb.mapper("broken.Mapper", """
                  <sql id="okFrag">A, B</sql>
                  <select id="ok" resultType="map">
                    SELECT <include refid="okFrag"/> FROM TB_OK
                  </select>
                  <select id="bad" resultType="map">
                    SELECT * FROM TB_BAD WHERE 1 = 1
                    <if test="id != null">
                      AND ID < #{id}
                  </select>
                  <update id="after">UPDATE TB_AFTER SET A = 1</update>
                """);
        try (TestProject project = TestProject.create()
                .file("mapper/a_broken.xml", broken)
                .file("mapper/b_good.xml", Mb.mapper("good.Mapper", "<select id=\"sel\">SELECT 1 FROM TB_GOOD</select>"))
                .file("mapper/c_empty.xml", Mb.HEADER + "<mapper namespace=\"unclosed.Mapper\">\n<select id=\"x\">SELECT 1")) {
            AnalysisContext ctx = Mb.run(project);

            List<Diagnostic> failed = Mb.diags(ctx, DiagnosticCodes.PARSE_FAILED);
            assertThat(failed).hasSize(2);
            Diagnostic first = failed.stream().filter(d -> d.location().file().equals("mapper/a_broken.xml")).findFirst().orElseThrow();
            assertThat(first.severity()).isEqualTo(Severity.ERROR);
            assertThat(first.location().startLine()).isEqualTo(Mb.lineCol(broken, "AND ID < #{id}")[0]);

            assertThat(ctx.graph().node(Ids.mybatisMapper("broken.Mapper"))).isPresent();
            assertThat(Mb.text(ctx, "broken.Mapper", "ok")).isEqualTo("SELECT A, B FROM TB_OK");
            assertThat(ctx.graph().node(Ids.mybatisStatement("broken.Mapper", "bad"))).isEmpty();
            assertThat(ctx.graph().node(Ids.mybatisStatement("broken.Mapper", "after"))).isEmpty();
            assertThat(Mb.text(ctx, "good.Mapper", "sel")).isEqualTo("SELECT 1 FROM TB_GOOD");
            assertThat(ctx.graph().node(Ids.mybatisStatement("unclosed.Mapper", "x"))).isEmpty();
            assertThat(ctx.embedded(EmbeddedSource.SQL)).noneMatch(s -> s.text().contains("TB_BAD"));
        }
    }

    @Test
    @DisplayName("F-009-AC21 namespace가 없는 매퍼는 PARSE_FAILED로 건너뛴다")
    void mapperWithoutNamespace() {
        try (TestProject project = TestProject.create()
                .file("mapper/NoNs.xml", "<mapper>\n<select id=\"a\">SELECT 1 FROM TB_A</select>\n</mapper>")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.diags(ctx, DiagnosticCodes.PARSE_FAILED)).hasSize(1);
            assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).isEmpty();
            assertThat(ctx.graph().nodes(NodeKind.MYBATIS_MAPPER)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-009-AC22 DTD를 받으러 가지 않고 외부 엔티티의 내용을 읽지 않는다")
    void noNetworkNoExternalEntities(@TempDir Path outside) throws Exception {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET_TABLE");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://127.0.0.1:1/dtd/mybatis-3-mapper.dtd" [
                  <!ENTITY secret SYSTEM "%s">
                  <!ENTITY tbl "TB_INTERNAL_ENTITY">
                ]>
                <mapper namespace="x.Y">
                  <select id="a">SELECT 1 FROM TB_SAFE WHERE A = '&secret;'</select>
                  <select id="b">SELECT 1 FROM &tbl; WHERE B = #{b}</select>
                </mapper>
                """.formatted(secret.toUri());
        try (TestProject project = TestProject.create().file("mapper/Xxe.xml", xml)) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.diags(ctx, DiagnosticCodes.PARSE_FAILED)).isEmpty();
            assertThat(Mb.text(ctx, "x.Y", "a")).startsWith("SELECT 1 FROM TB_SAFE WHERE A =");
            assertThat(ctx.embedded(EmbeddedSource.SQL)).noneMatch(s -> s.text().contains("TOP_SECRET_TABLE"));
            assertThat(ctx.graph().nodes()).noneMatch(n -> String.valueOf(n.attrs().get("sql")).contains("TOP_SECRET"));
            // 내부 엔티티는 풀어서 넣는다. 원문과 길이가 달라지므로 위치는 정확하지 않다고 표시한다
            EmbeddedSource b = Mb.variants(ctx, "x.Y", "b").get(0);
            assertThat(Mb.norm(b.text())).isEqualTo("SELECT 1 FROM TB_INTERNAL_ENTITY WHERE B = ?");
            assertThat(b.exactPositions()).isFalse();
        }
    }

    @Test
    @DisplayName("F-009-AC22 XML 선언의 encoding과 실제 인코딩이 달라도(MS949) 한글 본문을 읽는다")
    void declaredEncodingIsIgnored() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="kr.Mapper">
                  <!-- 한글 주석 -->
                  <select id="sel">SELECT 이름 FROM TB_회원 WHERE 구분 = '일반'</select>
                </mapper>
                """;
        try (TestProject project = TestProject.create().file("mapper/Kr.xml", xml, Charset.forName("MS949"))) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.text(ctx, "kr.Mapper", "sel")).isEqualTo("SELECT 이름 FROM TB_회원 WHERE 구분 = '일반'");
            assertThat(ctx.graph().diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-009-AC22 큰 매퍼 파일(약 2MB, 엔티티 수천 개, 깊은 중첩)도 파서 보안 한도에 걸리지 않는다")
    void largeMapper() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            body.append("<select id=\"s").append(i).append("\" resultType=\"map\">\n")
                    .append("  SELECT A, B, C FROM TB_BIG_").append(i).append(" WHERE A &lt; #{a} AND B &gt;= #{b} AND C &lt;&gt; 'x&amp;y'\n")
                    .append("  <if test=\"c != null\">AND C = #{c}</if>\n")
                    .append("  /* ").append("채움".repeat(150)).append(" */\n")
                    .append("</select>\n");
        }
        StringBuilder deep = new StringBuilder("<select id=\"deep\">SELECT 1 FROM TB_DEEP WHERE 1 = 1 ");
        for (int i = 0; i < 60; i++) {
            deep.append("<if test=\"a\">");
        }
        deep.append("AND DEEP_COL = 1");
        for (int i = 0; i < 60; i++) {
            deep.append("</if>");
        }
        deep.append("</select>");
        String xml = Mb.mapper("big.Mapper", body + deep.toString());
        assertThat(xml.length()).isGreaterThan(1_500_000);
        try (TestProject project = TestProject.create().file("mapper/Big.xml", xml)) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(ctx.graph().diagnostics()).isEmpty();
            assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).hasSize(4001);
            assertThat(Mb.text(ctx, "big.Mapper", "s3999"))
                    .startsWith("SELECT A, B, C FROM TB_BIG_3999 WHERE A < ? AND B >= ? AND C <> 'x&y' AND C = ?");
            assertThat(Mb.text(ctx, "big.Mapper", "deep")).isEqualTo("SELECT 1 FROM TB_DEEP WHERE 1 = 1 AND DEEP_COL = 1");
        }
    }

    @Test
    @DisplayName("F-009-AC21 알 수 없는 태그는 본문만 전개하고 INFO 진단을 남긴다")
    void unknownElement() {
        AnalysisContext ctx = Mb.analyze("u.Mapper", """
                  <select id="sel">SELECT 1 FROM TB_A <isNotNull property="a">WHERE A = #{a}</isNotNull></select>
                """);
        assertThat(Mb.text(ctx, "u.Mapper", "sel")).isEqualTo("SELECT 1 FROM TB_A WHERE A = ?");
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNSUPPORTED_SYNTAX)).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("isNotNull");
        });
        Node node = Mb.stmt(ctx, "u.Mapper", "sel");
        assertThat(node.attr("dynamic")).isEqualTo(true);
    }

    @Test
    @DisplayName("F-009-AC21 id가 없는 구문은 건너뛰고 진단을 남긴다")
    void statementWithoutId() {
        AnalysisContext ctx = Mb.analyze("u.Mapper", """
                  <select>SELECT 1 FROM TB_NOID</select>
                  <select id="ok">SELECT 1 FROM TB_A</select>
                """);
        assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).extracting(Node::id)
                .containsExactly(Ids.mybatisStatement("u.Mapper", "ok"));
        assertThat(Mb.diags(ctx, DiagnosticCodes.PARSE_FAILED)).hasSize(1);
    }

    @Test
    @DisplayName("F-009-AC36 iBatis 잔재 태그가 섞인 구문은 본문을 전개하고 구문마다 INFO 진단 1건만 남긴다")
    void ibatisLeftoverTags() {
        AnalysisContext ctx = Mb.analyze("u.Mapper", """
                  <select id="old">
                    SELECT A.ID FROM TB_OLD A
                    <dynamic prepend="WHERE">
                      <isNotEmpty property="nm">A.NM = #{nm}</isNotEmpty>
                      <isNull property="useYn">AND A.USE_YN = 'Y'</isNull>
                      <isEqual property="se" compareValue="G">AND A.SE = 'G'</isEqual>
                      <isEqual property="se" compareValue="E">AND A.SE = 'E'</isEqual>
                      <iterate property="ids" open="(" close=")" conjunction=",">#{ids[]}</iterate>
                    </dynamic>
                  </select>
                  <select id="old2">SELECT 1 FROM TB_OLD2 <isNotNull property="a">WHERE A = #{a}</isNotNull></select>
                  <select id="fine">SELECT 1 FROM TB_FINE</select>
                """);

        assertThat(Mb.text(ctx, "u.Mapper", "old")).isEqualTo(
                "SELECT A.ID FROM TB_OLD A A.NM = ? AND A.USE_YN = 'Y' AND A.SE = 'G' AND A.SE = 'E' ?");
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNSUPPORTED_SYNTAX)).hasSize(2).allSatisfy(
                d -> assertThat(d.severity()).isEqualTo(Severity.INFO));
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNSUPPORTED_SYNTAX).get(0).message())
                .contains("old").contains("dynamic").contains("isNotEmpty").contains("isNull").contains("isEqual").contains("iterate");
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNSUPPORTED_SYNTAX).get(0).location().startLine())
                .isEqualTo(Mb.stmt(ctx, "u.Mapper", "old").location().startLine());
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNSUPPORTED_SYNTAX).get(1).message()).contains("old2").contains("isNotNull");
    }
}
