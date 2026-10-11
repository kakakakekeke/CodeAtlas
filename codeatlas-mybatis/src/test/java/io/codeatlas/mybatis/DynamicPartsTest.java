package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Ids;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ${…}} 자리의 내부 표식({@code __DYNAMIC__})을 사용자에게 보이지 않게 하는 치환 목록(F-009-R21).
 * 공개 프로젝트에서 구문 노드의 {@code sql}과 sql 분석기의 진단·미해결 원문에 {@code __DYNAMIC__}이 그대로 보였다.
 */
class DynamicPartsTest {

    private static final String NS = "com.example.DynMapper";

    @Test
    @DisplayName("F-009-AC40 ${}를 치환한 내장 소스는 attrs.dynamicParts에 원문을 등장 순서대로 <길이>:<원문>으로 싣는다(위치를 보존한 본문, 전개본 모두)")
    void dynamicPartsOnEmbeddedSources() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="exact" resultType="map">
                    SELECT a.ID FROM TB_A a WHERE a.NM = #{nm} ${sqlMap.dsf} ORDER BY ${ orderBy }
                  </select>
                  <select id="expanded" resultType="map">
                    SELECT a.ID FROM ${schema}.TB_A a
                    <where>
                      <if test="nm != null">a.NM = #{nm}</if>
                      ${sqlMap.dsf}
                    </where>
                  </select>
                  <select id="twoLines" resultType="map">
                    <if test="x != null">SELECT 1 FROM DUAL WHERE 1 = ${
                        cond }</if>
                  </select>
                  <select id="plain" resultType="map">SELECT a.ID FROM TB_A a WHERE a.NM = #{nm}</select>
                """);
        EmbeddedSource exact = Mb.variants(ctx, NS, "exact").get(0);
        assertThat(exact.exactPositions()).isTrue();
        assertThat(Mb.norm(exact.text())).isEqualTo("SELECT a.ID FROM TB_A a WHERE a.NM = ? __DYNAMIC__ ORDER BY __DYNAMIC__");
        assertThat(exact.attrs()).containsEntry("dynamicParts", "13:${sqlMap.dsf}12:${ orderBy }");

        EmbeddedSource expanded = Mb.variants(ctx, NS, "expanded").get(0);
        assertThat(expanded.exactPositions()).isFalse();
        assertThat(Mb.norm(expanded.text())).isEqualTo("SELECT a.ID FROM __DYNAMIC__.TB_A a WHERE a.NM = ? __DYNAMIC__");
        assertThat(expanded.attrs()).containsEntry("dynamicParts", "9:${schema}13:${sqlMap.dsf}");

        // 원문에 줄바꿈이 있으면 줄바꿈을 낀 공백 묶음을 한 칸으로 줄인다(값에 줄바꿈이 없다)
        assertThat(Mb.variants(ctx, NS, "twoLines").get(0).attrs()).containsEntry("dynamicParts", "9:${ cond }");
        // ${}가 없으면 키가 없다
        assertThat(Mb.variants(ctx, NS, "plain").get(0).attrs()).doesNotContainKey("dynamicParts");
    }

    @Test
    @DisplayName("F-009-AC40 구문·조각 노드의 sql에는 __DYNAMIC__ 대신 ${…} 원문이 보인다")
    void sqlAttrShowsOriginal() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="tail">ORDER BY ${orderBy}</sql>
                  <update id="upd">UPDATE ${businessTable} SET PROC_INS_ID = #{procInsId} WHERE ID = #{id}</update>
                  <select id="sel" resultType="map">
                    SELECT * FROM TB_LOG_${ym} <include refid="tail"/>
                  </select>
                """);
        assertThat(Mb.stmt(ctx, NS, "upd").attrString("sql")).isEqualTo("UPDATE ${businessTable} SET PROC_INS_ID = ? WHERE ID = ?");
        assertThat(Mb.stmt(ctx, NS, "sel").attrString("sql")).isEqualTo("SELECT * FROM TB_LOG_${ym} ORDER BY ${orderBy}");
        assertThat(ctx.graph().node(Ids.mybatisFragment(NS, "tail")).orElseThrow().attrString("sql")).isEqualTo("ORDER BY ${orderBy}");
        assertThat(ctx.graph().nodes()).noneMatch(n -> String.valueOf(n.attrs().get("sql")).contains("__DYNAMIC__"));
        // 내장 소스 본문은 전과 같이 __DYNAMIC__ 식별자다(sql 분석기와의 약속)
        assertThat(Mb.text(ctx, NS, "sel")).isEqualTo("SELECT * FROM TB_LOG___DYNAMIC__ ORDER BY __DYNAMIC__");
        assertThat(Mb.variants(ctx, NS, "sel").get(0).attrs()).containsEntry("dynamicParts", "5:${ym}10:${orderBy}");
        assertThat(SqlParams.decodeParts("5:${ym}10:${orderBy}")).isEqualTo(List.of("${ym}", "${orderBy}"));
    }
}
