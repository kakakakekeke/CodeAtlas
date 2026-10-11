package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DynamicSqlExpansionTest {

    private static final String NS = "com.a.DynMapper";

    @Test
    @DisplayName("F-009-AC6 where: 모든 if 본문을 넣고 맨 앞 AND/OR를 뗀다. 빈 where는 생략한다")
    void whereWithIfs() {
        String xml = Mb.mapper(NS, """
                  <select id="search" resultType="map">
                    SELECT * FROM TB_ORDER O
                    <where>
                      <if test="a != null">AND O.A = #{a}</if>
                      <if test="b != null and b != ''">
                        AND O.B = #{b}
                      </if>
                      <if test="c != null">OR O.C = #{c}</if>
                    </where>
                    ORDER BY O.A
                  </select>
                  <select id="orFirst">
                    SELECT * FROM TB_A
                    <where>
                      or A = 1
                      <if test="b">and ORDER_NO = 2</if>
                    </where>
                  </select>
                  <select id="empty">
                    SELECT * FROM TB_ORDER O
                    <where>
                    </where>
                  </select>
                  <select id="keepsAndroid">
                    SELECT * FROM TB_A <where>ANDROID_ID = 1</where>
                  </select>
                """);
        try (TestProject project = TestProject.create().file("mapper/Dyn.xml", xml)) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.text(ctx, NS, "search"))
                    .isEqualTo("SELECT * FROM TB_ORDER O WHERE O.A = ? AND O.B = ? OR O.C = ? ORDER BY O.A");
            assertThat(Mb.text(ctx, NS, "orFirst")).isEqualTo("SELECT * FROM TB_A WHERE A = 1 and ORDER_NO = 2");
            assertThat(Mb.text(ctx, NS, "empty")).isEqualTo("SELECT * FROM TB_ORDER O");
            assertThat(Mb.text(ctx, NS, "keepsAndroid")).isEqualTo("SELECT * FROM TB_A WHERE ANDROID_ID = 1");

            assertThat(Mb.stmt(ctx, NS, "search").attr("dynamic")).isEqualTo(true);
            assertThat(Mb.stmt(ctx, NS, "empty").attr("dynamic")).isEqualTo(true);
            assertThat(Mb.stmt(ctx, NS, "search").attrString("sql"))
                    .isEqualTo("SELECT * FROM TB_ORDER O WHERE O.A = ? AND O.B = ? OR O.C = ? ORDER BY O.A");

            EmbeddedSource src = Mb.variants(ctx, NS, "search").get(0);
            assertThat(src.exactPositions()).isFalse();
            int[] start = Mb.lineCol(xml, "<select id=\"search\"");
            assertThat(src.origin().file()).isEqualTo("mapper/Dyn.xml");
            assertThat(src.origin().startLine()).isEqualTo(start[0]);
            assertThat(src.origin().startColumn()).isEqualTo(start[1]);
            // if만 있고 ${}는 없으므로 DYNAMIC_SQL 진단은 없다
            assertThat(Mb.diags(ctx, DiagnosticCodes.DYNAMIC_SQL)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-009-AC7 set은 끝 쉼표를 떼고, trim은 prefix·suffix·overrides를 적용한다")
    void setAndTrim() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <update id="upd">
                    UPDATE TB_ORDER
                    <set>
                      <if test="a != null">A = #{a},</if>
                      <if test="b != null">B = #{b},</if>
                    </set>
                    WHERE ID = #{id}
                  </update>
                  <insert id="ins">
                    INSERT INTO TB_ORDER
                    <trim prefix="(" suffix=")" suffixOverrides=",">
                      ORDER_ID,
                      <if test="memberId != null">MEMBER_ID,</if>
                      <if test="amt != null">AMT,</if>
                    </trim>
                    <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
                      #{orderId},
                      <if test="memberId != null">#{memberId},</if>
                      <if test="amt != null">#{amt, jdbcType=NUMERIC},</if>
                    </trim>
                  </insert>
                  <select id="sel">
                    SELECT * FROM TB_PRODUCT P
                    <trim prefix="WHERE" prefixOverrides="AND |OR ">
                      <if test="x != null">
                        AND P.X = #{x}
                      </if>
                      <if test="y != null">
                        OR P.Y = #{y}
                      </if>
                    </trim>
                  </select>
                  <update id="trimSet">
                    UPDATE TB_ORDER
                    <trim prefix="SET" suffixOverrides=",">
                      ORDER_STATUS = #{status},
                      <if test="userId != null">UPD_ID = #{userId},</if>
                      UPD_DT = SYSDATE,
                    </trim>
                    WHERE ORDER_ID = #{orderId}
                  </update>
                  <select id="emptyTrim">
                    SELECT 1 FROM TB_A <trim prefix="WHERE" prefixOverrides="AND"> </trim>
                  </select>
                  <select id="suffixAnd">
                    SELECT 1 FROM TB_A WHERE
                    <trim suffixOverrides="AND|OR">
                      <if test="a">A = 1 AND</if>
                      <if test="b">B = 2 and</if>
                    </trim>
                  </select>
                """);

        assertThat(Mb.text(ctx, NS, "upd")).isEqualTo("UPDATE TB_ORDER SET A = ?, B = ? WHERE ID = ?");
        assertThat(Mb.text(ctx, NS, "ins"))
                .isEqualTo("INSERT INTO TB_ORDER ( ORDER_ID, MEMBER_ID, AMT ) VALUES ( ?, ?, ? )");
        assertThat(Mb.text(ctx, NS, "sel")).isEqualTo("SELECT * FROM TB_PRODUCT P WHERE P.X = ? OR P.Y = ?");
        assertThat(Mb.text(ctx, NS, "trimSet"))
                .isEqualTo("UPDATE TB_ORDER SET ORDER_STATUS = ?, UPD_ID = ?, UPD_DT = SYSDATE WHERE ORDER_ID = ?");
        assertThat(Mb.text(ctx, NS, "emptyTrim")).isEqualTo("SELECT 1 FROM TB_A");
        assertThat(Mb.text(ctx, NS, "suffixAnd")).isEqualTo("SELECT 1 FROM TB_A WHERE A = 1 AND B = 2");
    }

    @Test
    @DisplayName("F-009-AC8 foreach는 open + 본문 한 번 + close, bind는 무시한다")
    void foreachAndBind() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="inList">
                    <bind name="pattern" value="'%' + SECRET_BIND_TABLE + '%'"/>
                    SELECT * FROM TB_ORDER
                     WHERE NM LIKE #{pattern}
                       AND ORDER_STATUS IN
                    <foreach collection="statusList" item="status" open="(" separator="," close=")">
                      #{status}
                    </foreach>
                  </select>
                  <select id="compact">
                    SELECT * FROM TB_ORDER WHERE ID IN<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </select>
                  <insert id="insertAll">
                    INSERT ALL
                    <foreach collection="items" item="item" index="idx">
                      INTO TB_ORDER_ITEM (ORDER_ID, ITEM_SEQ, QTY)
                      VALUES (#{orderId}, #{idx} + 1, #{item.qty})
                    </foreach>
                    SELECT 1 FROM DUAL
                  </insert>
                  <insert id="unionAll">
                    INSERT INTO TB_LOG (A, B)
                    <foreach collection="list" item="it" separator="UNION ALL">
                      SELECT #{it.a}, #{it.b} FROM DUAL
                    </foreach>
                  </insert>
                """);

        assertThat(Mb.text(ctx, NS, "inList"))
                .isEqualTo("SELECT * FROM TB_ORDER WHERE NM LIKE ? AND ORDER_STATUS IN ( ? )");
        assertThat(Mb.text(ctx, NS, "compact")).isEqualTo("SELECT * FROM TB_ORDER WHERE ID IN(?)");
        assertThat(Mb.text(ctx, NS, "insertAll")).isEqualTo(
                "INSERT ALL INTO TB_ORDER_ITEM (ORDER_ID, ITEM_SEQ, QTY) VALUES (?, ? + 1, ?) SELECT 1 FROM DUAL");
        assertThat(Mb.text(ctx, NS, "unionAll")).isEqualTo("INSERT INTO TB_LOG (A, B) SELECT ?, ? FROM DUAL");
        assertThat(Mb.stmt(ctx, NS, "inList").attr("dynamic")).isEqualTo(true);
        assertThat(ctx.graph().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-009-AC9 choose는 첫 when이 대표이고 나머지 분기마다 전개본이 하나씩 는다")
    void chooseBranchesBecomeVariants() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="list" resultType="map">
                    SELECT * FROM TB_ORDER O
                     WHERE 1 = 1
                    <choose>
                      <when test="t == 'A'">AND O.A = #{a}</when>
                      <when test="t == 'B'">AND O.B IN (SELECT B FROM TB_B)</when>
                      <otherwise>AND O.C = 1</otherwise>
                    </choose>
                     ORDER BY
                    <choose>
                      <when test="s == 'dt'">O.ORDER_DT DESC</when>
                      <when test="s == 'amt'">O.TOTAL_AMT DESC</when>
                    </choose>
                  </select>
                """);

        List<EmbeddedSource> variants = Mb.variants(ctx, NS, "list");
        assertThat(variants).extracting(s -> s.attrs().get("variant")).containsExactly("1", "2", "3", "4");
        assertThat(variants).allSatisfy(s -> assertThat(s.exactPositions()).isFalse());
        assertThat(Mb.texts(ctx, NS, "list")).containsExactly(
                "SELECT * FROM TB_ORDER O WHERE 1 = 1 AND O.A = ? ORDER BY O.ORDER_DT DESC",
                "SELECT * FROM TB_ORDER O WHERE 1 = 1 AND O.B IN (SELECT B FROM TB_B) ORDER BY O.ORDER_DT DESC",
                "SELECT * FROM TB_ORDER O WHERE 1 = 1 AND O.C = 1 ORDER BY O.ORDER_DT DESC",
                "SELECT * FROM TB_ORDER O WHERE 1 = 1 AND O.A = ? ORDER BY O.TOTAL_AMT DESC");
        assertThat(Mb.stmt(ctx, NS, "list").attr("variants")).isEqualTo(4);
        assertThat(Mb.stmt(ctx, NS, "list").attrString("sql"))
                .isEqualTo("SELECT * FROM TB_ORDER O WHERE 1 = 1 AND O.A = ? ORDER BY O.ORDER_DT DESC");
    }

    @Test
    @DisplayName("F-009-AC9 다른 분기 안에 중첩된 choose의 분기도 전개본으로 나온다")
    void nestedChoose() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="nested">
                    SELECT 1 FROM
                    <choose>
                      <when test="a">TB_A</when>
                      <otherwise>
                        <choose>
                          <when test="b">TB_B</when>
                          <when test="c">TB_C</when>
                        </choose>
                      </otherwise>
                    </choose>
                  </select>
                  <select id="onlyOtherwise">
                    SELECT 1 FROM <choose><otherwise>TB_X</otherwise></choose>
                  </select>
                  <select id="same">
                    SELECT 1 FROM <choose><when test="a">TB_S</when><otherwise>TB_S</otherwise></choose>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "nested"))
                .containsExactly("SELECT 1 FROM TB_A", "SELECT 1 FROM TB_B", "SELECT 1 FROM TB_C");
        assertThat(Mb.texts(ctx, NS, "onlyOtherwise")).containsExactly("SELECT 1 FROM TB_X");
        // 본문이 같은 전개본은 한 번만 등록한다
        assertThat(Mb.texts(ctx, NS, "same")).containsExactly("SELECT 1 FROM TB_S");
    }

    @Test
    @DisplayName("F-009-AC10 전개본은 16개까지만 등록하고 WARN 진단을 남긴다")
    void variantLimit() {
        String whens = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "<when test=\"k == " + i + "\">TB_" + i + "</when>")
                .collect(Collectors.joining("\n"));
        String xml = Mb.mapper(NS, "<select id=\"many\">\nSELECT 1 FROM\n<choose>\n" + whens + "\n</choose>\n</select>");
        try (TestProject project = TestProject.create().file("mapper/Many.xml", xml)) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.texts(ctx, NS, "many")).hasSize(16).startsWith("SELECT 1 FROM TB_1", "SELECT 1 FROM TB_2");
            assertThat(Mb.stmt(ctx, NS, "many").attr("variants")).isEqualTo(16);
            List<Diagnostic> warns = ctx.graph().diagnostics().stream().filter(d -> d.severity() == Severity.WARN).toList();
            assertThat(warns).singleElement().satisfies(d -> {
                assertThat(d.code()).isEqualTo(DiagnosticCodes.UNSUPPORTED_SYNTAX);
                assertThat(d.message()).contains("16");
                assertThat(d.location().startLine()).isEqualTo(Mb.lineCol(xml, "<select id=\"many\"")[0]);
            });
        }
    }

    @Test
    @DisplayName("F-009-AC11 #{}는 ?로, ${}는 __DYNAMIC__으로 바꾸고 DYNAMIC_SQL 진단을 남긴다")
    void parameterSubstitution() {
        String xml = Mb.mapper(NS, """
                  <select id="dyn" resultType="map">
                    SELECT * FROM ${tableName} WHERE ID = #{id, jdbcType=NUMERIC} ORDER BY ${sortColumn}
                  </select>
                  <select id="plain">SELECT * FROM TB_A WHERE A = #{a} AND B = #{b.c,javaType=int}</select>
                  <select id="partial">SELECT * FROM TB_LOG_${yyyymm} WHERE MSG = '${literal}'</select>
                """);
        try (TestProject project = TestProject.create().file("mapper/Dyn.xml", xml)) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.text(ctx, NS, "dyn")).isEqualTo("SELECT * FROM __DYNAMIC__ WHERE ID = ? ORDER BY __DYNAMIC__");
            assertThat(Mb.stmt(ctx, NS, "dyn").attr("dynamic")).isEqualTo(true);
            // 노드의 sql은 사람이 보는 글이라 내부 표식 대신 ${…} 원문을 적는다(F-009-R21)
            assertThat(Mb.stmt(ctx, NS, "dyn").attrString("sql"))
                    .isEqualTo("SELECT * FROM ${tableName} WHERE ID = ? ORDER BY ${sortColumn}");
            assertThat(Mb.text(ctx, NS, "plain")).isEqualTo("SELECT * FROM TB_A WHERE A = ? AND B = ?");
            assertThat(Mb.stmt(ctx, NS, "plain").attr("dynamic")).isEqualTo(false);
            assertThat(Mb.text(ctx, NS, "partial")).isEqualTo("SELECT * FROM TB_LOG___DYNAMIC__ WHERE MSG = '__DYNAMIC__'");

            List<Diagnostic> dynamic = Mb.diags(ctx, DiagnosticCodes.DYNAMIC_SQL);
            assertThat(dynamic).hasSize(2).allSatisfy(d -> assertThat(d.severity()).isEqualTo(Severity.INFO));
            Diagnostic first = dynamic.stream().filter(d -> d.message().contains("tableName")).findFirst().orElseThrow();
            assertThat(first.message()).contains("sortColumn").contains(NS + ".dyn");
            assertThat(first.location().file()).isEqualTo("mapper/Dyn.xml");
            assertThat(first.location().startLine()).isEqualTo(Mb.lineCol(xml, "<select id=\"dyn\"")[0]);
        }
    }

    @Test
    @DisplayName("F-009-AC11 길이를 보존할 수 있는 ${}만 있는 정적 구문은 위치를 유지한다")
    void longDollarKeepsPositions() {
        String xml = Mb.mapper(NS, """
                  <select id="longName">
                    SELECT * FROM ${dynamicTableName} T
                     WHERE T.COL_A = 1
                  </select>
                  <select id="shortName">
                    SELECT * FROM ${t} T
                     WHERE T.COL_A = 1
                  </select>
                """);
        try (TestProject project = TestProject.create().file("mapper/Dyn.xml", xml)) {
            AnalysisContext ctx = Mb.run(project);

            EmbeddedSource longName = Mb.variants(ctx, NS, "longName").get(0);
            assertThat(longName.exactPositions()).isTrue();
            assertThat(Mb.norm(longName.text())).isEqualTo("SELECT * FROM __DYNAMIC__ T WHERE T.COL_A = 1");
            assertThat(Mb.hostLineCol(longName, "T.COL_A")).containsExactly(Mb.lineCol(xml, "T.COL_A"));

            EmbeddedSource shortName = Mb.variants(ctx, NS, "shortName").get(0);
            assertThat(shortName.exactPositions()).isFalse();
            assertThat(Mb.norm(shortName.text())).isEqualTo("SELECT * FROM __DYNAMIC__ T WHERE T.COL_A = 1");
        }
    }

    @Test
    @DisplayName("F-009-AC6 내장 소스 본문은 줄바꿈을 유지해 SQL 줄 주석이 뒤 내용을 삼키지 않는다")
    void keepsLineBreaks() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="c">
                    SELECT A -- 주석
                      FROM TB_A
                    <if test="x">WHERE X = 1</if>
                  </select>
                """);
        String text = Mb.variants(ctx, NS, "c").get(0).text();
        assertThat(text.lines().map(String::strip).toList())
                .containsExactly("SELECT A -- 주석", "FROM TB_A", "WHERE X = 1");
    }
}
