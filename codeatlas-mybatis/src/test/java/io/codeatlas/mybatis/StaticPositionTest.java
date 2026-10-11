package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Ids;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StaticPositionTest {

    private static final String NS = "com.a.PosMapper";
    private static final String FILE = "mapper/PosMapper.xml";

    @Test
    @DisplayName("F-009-AC4 정적 구문은 exactPositions=true이고 토큰 위치가 원본 파일과 같다")
    void staticStatementKeepsPositions() {
        String xml = Mb.mapper(NS, """
                  <select id="sel" parameterType="long" resultType="map">
                    SELECT O.ORDER_ID, O.한글컬럼
                      FROM TB_ORDER O
                     WHERE O.ORDER_ID = #{id, jdbcType=NUMERIC} AND O.CLOSE_YN = 'N'
                       AND O.MEMBER_ID = #{memberId}
                  </select>
                """);
        try (TestProject project = TestProject.create().file(FILE, xml)) {
            AnalysisContext ctx = Mb.run(project);

            List<EmbeddedSource> sources = Mb.sources(ctx, NS, "sel");
            assertThat(sources).hasSize(1);
            EmbeddedSource src = sources.get(0);
            assertThat(src.exactPositions()).isTrue();
            assertThat(src.language()).isEqualTo(EmbeddedSource.SQL);
            assertThat(src.ownerNodeId()).isEqualTo(Ids.mybatisStatement(NS, "sel"));
            assertThat(src.attrs()).containsEntry("variant", "1");

            // origin은 본문의 첫 글자(SELECT의 S)
            int[] select = Mb.lineCol(xml, "SELECT O.ORDER_ID");
            assertThat(src.origin().file()).isEqualTo(FILE);
            assertThat(src.origin().startLine()).isEqualTo(select[0]);
            assertThat(src.origin().startColumn()).isEqualTo(select[1]);
            assertThat(src.text()).startsWith("SELECT O.ORDER_ID").endsWith("?");

            assertThat(Mb.hostLineCol(src, "TB_ORDER")).containsExactly(Mb.lineCol(xml, "TB_ORDER"));
            assertThat(Mb.hostLineCol(src, "한글컬럼")).containsExactly(Mb.lineCol(xml, "한글컬럼"));
            // #{...} 치환 뒤의 토큰도 열이 밀리지 않는다
            assertThat(Mb.hostLineCol(src, "O.CLOSE_YN")).containsExactly(Mb.lineCol(xml, "O.CLOSE_YN"));
            assertThat(Mb.hostLineCol(src, "O.MEMBER_ID")).containsExactly(Mb.lineCol(xml, "O.MEMBER_ID"));
            assertThat(Mb.norm(src.text())).isEqualTo(
                    "SELECT O.ORDER_ID, O.한글컬럼 FROM TB_ORDER O WHERE O.ORDER_ID = ? AND O.CLOSE_YN = 'N' AND O.MEMBER_ID = ?");
            assertThat(src.text()).doesNotContain("#{");
        }
    }

    @Test
    @DisplayName("F-009-AC4 CRLF 줄바꿈과 탭이 있어도 위치가 맞다")
    void crlfAndTabs() {
        String xml = Mb.mapper(NS, "\t<select id=\"sel\">\r\n\t\tSELECT A\r\n\t\t  FROM TB_CRLF\r\n\t\t WHERE B = #{b}\r\n\t</select>")
                .replace("?>\n", "?>\r\n");
        try (TestProject project = TestProject.create().file(FILE, xml)) {
            AnalysisContext ctx = Mb.run(project);
            EmbeddedSource src = Mb.sources(ctx, NS, "sel").get(0);
            assertThat(src.exactPositions()).isTrue();
            // 줄 번호는 \r\n을 한 줄로 센다
            String lfView = xml.replace("\r\n", "\n");
            int[] expected = Mb.lineCol(lfView, "TB_CRLF");
            String textLf = src.text().replace("\r\n", "\n");
            int[] inText = Mb.lineCol(textLf, "TB_CRLF");
            var loc = src.locationOf(inText[0], inText[1], inText[0], inText[1] + 7);
            assertThat(new int[] {loc.startLine(), loc.startColumn()}).containsExactly(expected);
        }
    }

    @Test
    @DisplayName("F-009-AC5 CDATA와 엔티티는 풀고 XML 주석 안의 SQL은 버린다. 위치는 유지된다")
    void cdataEntitiesAndComments() {
        String xml = Mb.mapper(NS, """
                  <select id="sel" resultType="map">
                    SELECT P.PRODUCT_ID
                      FROM TB_PRODUCT P, TB_STOCK S
                     WHERE P.PRODUCT_ID = S.PRODUCT_ID
                    <!-- 예전 조건: AND P.ID IN (SELECT ID FROM TB_OLD_PRODUCT) -->
                       AND S.STOCK_QTY &lt; S.SAFE_QTY AND S.UPD_DT &gt;= #{fromDt}
                       AND P.PRICE &lt;&gt; 0 AND P.NM = 'A&amp;B'
                    <![CDATA[
                       AND S.STOCK_QTY <= #{limit} AND ROWNUM < 10
                    ]]>
                     ORDER BY S.STOCK_QTY
                  </select>
                """);
        try (TestProject project = TestProject.create().file(FILE, xml)) {
            AnalysisContext ctx = Mb.run(project);

            EmbeddedSource src = Mb.sources(ctx, NS, "sel").get(0);
            assertThat(src.exactPositions()).isTrue();
            String text = Mb.norm(src.text());
            assertThat(text).isEqualTo("SELECT P.PRODUCT_ID FROM TB_PRODUCT P, TB_STOCK S WHERE P.PRODUCT_ID = S.PRODUCT_ID"
                    + " AND S.STOCK_QTY < S.SAFE_QTY AND S.UPD_DT >= ? AND P.PRICE <> 0 AND P.NM = 'A &B'"
                    + " AND S.STOCK_QTY <= ? AND ROWNUM < 10 ORDER BY S.STOCK_QTY");
            // 길이를 보존하느라 엔티티 자리를 공백으로 채우므로 문자열 리터럴 안의 공백은 원문과 다를 수 있다('A&amp;B' → 'A    &B')
            assertThat(text).doesNotContain("TB_OLD_PRODUCT").doesNotContain("CDATA");

            assertThat(Mb.hostLineCol(src, "ROWNUM")).containsExactly(Mb.lineCol(xml, "ROWNUM"));
            assertThat(Mb.hostLineCol(src, "S.SAFE_QTY")).containsExactly(Mb.lineCol(xml, "S.SAFE_QTY"));
            assertThat(Mb.hostLineCol(src, "ORDER BY")).containsExactly(Mb.lineCol(xml, "ORDER BY"));
            assertThat(Mb.stmt(ctx, NS, "sel").attrString("sql")).doesNotContain("TB_OLD_PRODUCT").contains("S.STOCK_QTY <= ?");
        }
    }

    @Test
    @DisplayName("F-009-AC5 본문 전체가 CDATA인 구문도 위치가 맞다")
    void wholeBodyInCdata() {
        String xml = Mb.mapper(NS, """
                  <select id="sel"><![CDATA[
                    SELECT * FROM TB_A WHERE A < #{a}
                  ]]></select>
                """);
        try (TestProject project = TestProject.create().file(FILE, xml)) {
            AnalysisContext ctx = Mb.run(project);
            EmbeddedSource src = Mb.sources(ctx, NS, "sel").get(0);
            assertThat(src.exactPositions()).isTrue();
            assertThat(Mb.norm(src.text())).isEqualTo("SELECT * FROM TB_A WHERE A < ?");
            assertThat(Mb.hostLineCol(src, "TB_A")).containsExactly(Mb.lineCol(xml, "TB_A"));
            int[] select = Mb.lineCol(xml, "SELECT *");
            assertThat(new int[] {src.origin().startLine(), src.origin().startColumn()}).containsExactly(select);
        }
    }

    @Test
    @DisplayName("F-009-AC15 selectKey는 같은 주인의 별도 내장 소스이고 둘 다 위치가 정확하다")
    void selectKeyIsSeparateSource() {
        String xml = Mb.mapper(NS, """
                  <insert id="ins" parameterType="map">
                    <selectKey keyProperty="id" resultType="long" order="BEFORE">
                      SELECT SEQ_ORDER.NEXTVAL FROM DUAL
                    </selectKey>
                    INSERT INTO TB_ORDER (ORDER_ID, MEMBER_ID)
                    VALUES (#{id}, #{memberId})
                  </insert>
                """);
        try (TestProject project = TestProject.create().file(FILE, xml)) {
            AnalysisContext ctx = Mb.run(project);

            List<EmbeddedSource> all = Mb.sources(ctx, NS, "ins");
            assertThat(all).hasSize(2);
            EmbeddedSource key = all.stream().filter(s -> "true".equals(s.attrs().get("selectKey"))).findFirst().orElseThrow();
            EmbeddedSource main = Mb.variants(ctx, NS, "ins").get(0);

            assertThat(Mb.norm(key.text())).isEqualTo("SELECT SEQ_ORDER.NEXTVAL FROM DUAL");
            assertThat(key.exactPositions()).isTrue();
            assertThat(Mb.hostLineCol(key, "SEQ_ORDER")).containsExactly(Mb.lineCol(xml, "SEQ_ORDER"));

            assertThat(Mb.norm(main.text())).isEqualTo("INSERT INTO TB_ORDER (ORDER_ID, MEMBER_ID) VALUES ( ?, ?)");
            assertThat(main.text()).doesNotContain("SEQ_ORDER").doesNotContain("selectKey");
            assertThat(main.exactPositions()).isTrue();
            assertThat(Mb.hostLineCol(main, "TB_ORDER")).containsExactly(Mb.lineCol(xml, "TB_ORDER"));
            assertThat(Mb.hostLineCol(main, "VALUES")).containsExactly(Mb.lineCol(xml, "VALUES"));

            assertThat(Mb.stmt(ctx, NS, "ins").attr("selectKey")).isEqualTo(true);
            assertThat(Mb.stmt(ctx, NS, "ins").attrString("sql")).isEqualTo("INSERT INTO TB_ORDER (ORDER_ID, MEMBER_ID) VALUES (?, ?)");
            assertThat(Mb.stmt(ctx, NS, "ins").attr("variants")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("F-009-AC15 동적 구문 안의 selectKey도 별도 내장 소스로 나간다")
    void selectKeyInDynamicStatement() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <insert id="ins">
                    <selectKey keyProperty="id" resultType="long" order="BEFORE">VALUES NEXT VALUE FOR SEQ_SETTLE_ID</selectKey>
                    INSERT INTO TB_SETTLE (ID <if test="a != null">, A</if>) VALUES (#{id} <if test="a != null">, #{a}</if>)
                  </insert>
                """);
        List<EmbeddedSource> all = Mb.sources(ctx, NS, "ins");
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(s -> "true".equals(s.attrs().get("selectKey"))).singleElement()
                .satisfies(s -> assertThat(Mb.norm(s.text())).isEqualTo("VALUES NEXT VALUE FOR SEQ_SETTLE_ID"));
        assertThat(Mb.text(ctx, NS, "ins")).isEqualTo("INSERT INTO TB_SETTLE (ID , A) VALUES (? , ?)");
    }

    @Test
    @DisplayName("F-009-AC35 bind만 있고 다른 동적 태그가 없는 구문은 bind를 공백으로 지워 위치를 유지한다")
    void bindOnlyKeepsExactPositions() {
        String xml = Mb.mapper(NS, """
                  <select id="byName" resultType="map">
                    <bind name="pattern" value="'%' + name + '%'"/>
                    SELECT ORDER_ID
                      FROM TB_ORDER <bind name="unused" value="1 > 0"/>
                     WHERE ORDER_NM LIKE #{pattern}
                  </select>
                """);
        try (TestProject project = TestProject.create().file(FILE, xml)) {
            AnalysisContext ctx = Mb.run(project);

            EmbeddedSource src = Mb.variants(ctx, NS, "byName").get(0);
            assertThat(Mb.variants(ctx, NS, "byName")).hasSize(1);
            assertThat(src.exactPositions()).isTrue();
            assertThat(Mb.norm(src.text())).isEqualTo("SELECT ORDER_ID FROM TB_ORDER WHERE ORDER_NM LIKE ?");
            assertThat(src.text()).doesNotContain("bind").doesNotContain("pattern");
            for (String token : new String[] {"SELECT ORDER_ID", "TB_ORDER", "ORDER_NM"}) {
                assertThat(Mb.hostLineCol(src, token)).as(token).containsExactly(Mb.lineCol(xml, token));
            }
            // bind가 있다는 사실은 그대로 dynamic=true로 남긴다
            assertThat(Mb.stmt(ctx, NS, "byName").attr("dynamic")).isEqualTo(true);
        }
    }
}
