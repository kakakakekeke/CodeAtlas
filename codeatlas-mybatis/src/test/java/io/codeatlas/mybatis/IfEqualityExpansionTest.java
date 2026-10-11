package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 같은 식을 서로 다른 리터럴과 비교하는 {@code <if>}를 값마다 전개본으로 나눈다(F-009-R14). */
class IfEqualityExpansionTest {

    private static final String NS = "com.a.IfMapper";

    @Test
    @DisplayName("F-009-AC27 같은 변수를 다른 리터럴과 비교하는 인접한 if는 choose처럼 분기별 전개본이 된다")
    void adjacentIfsWithCompleteStatements() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="sign" resultType="map">
                    <!-- 일반 -->
                    <if test="userSe == &quot;GNR&quot;">
                      SELECT G_ID AS ID FROM TB_GNR WHERE G_ID = #{id}
                    </if>
                    <!-- 기업 -->
                    <if test="userSe == &quot;ENT&quot;">
                      SELECT E_ID AS ID FROM TB_ENT WHERE E_ID = #{id}
                    </if>
                    <if test="userSe == &quot;USR&quot;">
                      SELECT U.U_ID AS ID FROM TB_USR U LEFT OUTER JOIN TB_GRP G ON U.GRP_ID = G.GRP_ID WHERE U.U_ID = #{id}
                    </if>
                  </select>
                  <select id="paged" resultType="map">
                    SELECT * FROM ( SELECT ROWNUM RN, TB.* FROM (
                      SELECT A.ID FROM TB_A A
                    <if test="pageUnit == 5">
                      ) TB ) WHERE RN BETWEEN #{first} + 1 AND #{first} + 5
                    </if>
                    <if test="pageUnit == 10">
                      ) TB ) WHERE RN BETWEEN #{first} + 1 AND #{first} + 10
                    </if>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "sign")).containsExactly(
                "SELECT G_ID AS ID FROM TB_GNR WHERE G_ID = ?",
                "SELECT E_ID AS ID FROM TB_ENT WHERE E_ID = ?",
                "SELECT U.U_ID AS ID FROM TB_USR U LEFT OUTER JOIN TB_GRP G ON U.GRP_ID = G.GRP_ID WHERE U.U_ID = ?");
        assertThat(Mb.stmt(ctx, NS, "sign").attr("variants")).isEqualTo(3);
        assertThat(Mb.stmt(ctx, NS, "sign").attr("dynamic")).isEqualTo(true);
        // 대표 SQL은 첫 값의 분기다
        assertThat(Mb.stmt(ctx, NS, "sign").attrString("sql")).isEqualTo("SELECT G_ID AS ID FROM TB_GNR WHERE G_ID = ?");

        assertThat(Mb.texts(ctx, NS, "paged")).containsExactly(
                "SELECT * FROM ( SELECT ROWNUM RN, TB.* FROM ( SELECT A.ID FROM TB_A A ) TB ) WHERE RN BETWEEN ? + 1 AND ? + 5",
                "SELECT * FROM ( SELECT ROWNUM RN, TB.* FROM ( SELECT A.ID FROM TB_A A ) TB ) WHERE RN BETWEEN ? + 1 AND ? + 10");
        assertThat(ctx.graph().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-009-AC28 문장 여러 곳에 흩어진 같은 변수의 등치 if는 같은 값끼리 한 전개본에 들어간다")
    void sameValueGoesToSameVariant() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="nameLike">
                    AND A.NAME LIKE
                    <if test="dbName == 'oracle'">'%'||#{name}||'%'</if>
                    <if test="dbName == 'mysql'">concat('%',#{name},'%')</if>
                  </sql>
                  <select id="find" resultType="map">
                    SELECT A.ID FROM TB_A A WHERE A.DEL_YN = 'N'
                    <include refid="nameLike"/>
                    <if test="title != null and title != ''">
                      AND A.TITLE LIKE <if test="dbName == 'oracle'">'%'||#{title}||'%'</if><if test="dbName == 'mysql'">concat('%',#{title},'%')</if>
                    </if>
                  </select>
                  <select id="stats" resultType="map">
                    SELECT
                    <if test="kind == 'D'">S.DAY_CD AS CD</if>
                    <if test="kind == 'M'">S.MONTH_CD AS CD</if>
                    , COUNT(*) AS CNT FROM TB_STAT S
                    <where>
                      <if test="from != null">AND S.DT >= #{from}</if>
                      <if test="kind == 'D'">AND S.DAY_YN = 'Y'</if>
                      <if test="kind == 'M'">AND S.MONTH_YN = 'Y'</if>
                    </where>
                    GROUP BY <if test="kind == 'M'">S.MONTH_CD</if><if test="kind == 'D'">S.DAY_CD</if>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "find")).containsExactly(
                "SELECT A.ID FROM TB_A A WHERE A.DEL_YN = 'N' AND A.NAME LIKE '%'||?||'%' AND A.TITLE LIKE '%'||?||'%'",
                "SELECT A.ID FROM TB_A A WHERE A.DEL_YN = 'N' AND A.NAME LIKE concat('%',?,'%') AND A.TITLE LIKE concat('%',?,'%')");
        assertThat(Mb.texts(ctx, NS, "stats")).containsExactly(
                "SELECT S.DAY_CD AS CD , COUNT(*) AS CNT FROM TB_STAT S WHERE S.DT >= ? AND S.DAY_YN = 'Y' GROUP BY S.DAY_CD",
                "SELECT S.MONTH_CD AS CD , COUNT(*) AS CNT FROM TB_STAT S WHERE S.DT >= ? AND S.MONTH_YN = 'Y' GROUP BY S.MONTH_CD");
    }

    @Test
    @DisplayName("F-009-AC29 등치 조건의 여러 표기(eq, equals, 따옴표, 숫자, 괄호, 덧붙은 null 검사)를 읽고 그 밖의 if는 전부 넣는다")
    void equalityForms() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="eq">SELECT 1 FROM TB_A WHERE 1 = 1
                    <if test="a eq 1">AND A1 = 1</if>
                    <if test=" a  eq  2 ">AND A2 = 2</if>
                  </select>
                  <select id="equals">SELECT 1 FROM TB_B WHERE 1 = 1
                    <if test='b.equals("X")'>AND BX = 1</if>
                    <if test='"Y".equals(b)'>AND BY = 1</if>
                  </select>
                  <select id="nullGuard">SELECT 1 FROM TB_C WHERE 1 = 1
                    <if test="c != null and c == 'P'">AND CP = 1</if>
                    <if test="c != null and (c == 'Q')">AND CQ = 1</if>
                    <if test="(c != null) &amp;&amp; c != '' &amp;&amp; c == 'R'.toString()">AND CR = 1</if>
                  </select>
                  <select id="path">SELECT 1 FROM TB_D WHERE 1 = 1
                    <if test="cond.type == 10">AND D10 = 1</if>
                    <if test="5 == cond.type">AND D5 = 1</if>
                  </select>
                  <select id="anyOf">SELECT 1 FROM TB_E WHERE 1 = 1
                    <if test="e == 'Y' or e == 'N'">AND E_SET = 1</if>
                    <if test="e == 'Y'">AND E_Y = 1</if>
                    <if test="e == 'N'">AND E_N = 1</if>
                    <if test="e == 'Z'">AND E_Z = 1</if>
                  </select>
                  <insert id="batch">INSERT INTO TB_G (ID)
                    <if test="list.get(0).dbName == 'oracle'">SELECT #{id} FROM DUAL</if>
                    <if test="list.get(0).dbName == 'mssql' or list.get(0).dbName == 'mysql'">VALUES (#{id})</if>
                  </insert>
                  <select id="ignoreCase">SELECT 1 FROM TB_H WHERE 1 = 1
                    <if test="tbl.equalsIgnoreCase('a')">AND HA = 1</if>
                    <if test="tbl.equalsIgnoreCase('b')">AND HB = 1</if>
                  </select>
                  <select id="notLiteral">SELECT 1 FROM TB_F WHERE 1 = 1
                    <if test="f != null">AND F1 = #{f}</if>
                    <if test="f == null">AND F2 IS NULL</if>
                    <if test="f == g">AND F3 = 1</if>
                    <if test="n > 3">AND F4 = 1</if>
                    <if test="f != 'A'">AND F5 = 1</if>
                    <if test="f == 'A' or g != null">AND F6 = 1</if>
                    <if test="p == 1">AND P = 1</if>
                    <if test="q == 1">AND Q = 1</if>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "eq")).containsExactly(
                "SELECT 1 FROM TB_A WHERE 1 = 1 AND A1 = 1", "SELECT 1 FROM TB_A WHERE 1 = 1 AND A2 = 2");
        assertThat(Mb.texts(ctx, NS, "equals")).containsExactly(
                "SELECT 1 FROM TB_B WHERE 1 = 1 AND BX = 1", "SELECT 1 FROM TB_B WHERE 1 = 1 AND BY = 1");
        assertThat(Mb.texts(ctx, NS, "nullGuard")).containsExactly(
                "SELECT 1 FROM TB_C WHERE 1 = 1 AND CP = 1", "SELECT 1 FROM TB_C WHERE 1 = 1 AND CQ = 1",
                "SELECT 1 FROM TB_C WHERE 1 = 1 AND CR = 1");
        assertThat(Mb.texts(ctx, NS, "path")).containsExactly(
                "SELECT 1 FROM TB_D WHERE 1 = 1 AND D10 = 1", "SELECT 1 FROM TB_D WHERE 1 = 1 AND D5 = 1");
        assertThat(Mb.texts(ctx, NS, "anyOf")).containsExactly(
                "SELECT 1 FROM TB_E WHERE 1 = 1 AND E_SET = 1 AND E_Y = 1",
                "SELECT 1 FROM TB_E WHERE 1 = 1 AND E_SET = 1 AND E_N = 1",
                "SELECT 1 FROM TB_E WHERE 1 = 1 AND E_Z = 1");
        // 메서드 호출이 낀 경로(list.get(0).dbName)와 equalsIgnoreCase. 본문이 같아지는 값(mssql, mysql)은 전개본 하나다
        assertThat(Mb.texts(ctx, NS, "batch")).containsExactly(
                "INSERT INTO TB_G (ID) SELECT ? FROM DUAL", "INSERT INTO TB_G (ID) VALUES (?)");
        assertThat(Mb.texts(ctx, NS, "ignoreCase")).containsExactly(
                "SELECT 1 FROM TB_H WHERE 1 = 1 AND HA = 1", "SELECT 1 FROM TB_H WHERE 1 = 1 AND HB = 1");
        // 리터럴 등치가 아닌 조건, 값이 하나뿐인 등치, 서로 다른 변수의 등치는 지금처럼 한 전개본에 전부 들어간다
        assertThat(Mb.text(ctx, NS, "notLiteral")).isEqualTo("SELECT 1 FROM TB_F WHERE 1 = 1"
                + " AND F1 = ? AND F2 IS NULL AND F3 = 1 AND F4 = 1 AND F5 = 1 AND F6 = 1 AND P = 1 AND Q = 1");
    }

    @Test
    @DisplayName("F-009-AC30 값이 상한보다 많으면 전개본은 16개이고 WARN 진단을 남긴다. choose와 섞여도 한 번에 하나만 바꾼다")
    void capAndMixWithChoose() {
        String ifs = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "<if test=\"col == " + i + "\">AND C" + i + " = #{v}</if>")
                .collect(Collectors.joining("\n"));
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="many">SELECT 1 FROM TB_MANY WHERE 1 = 1
                """ + ifs + """
                  </select>
                  <select id="mixed">SELECT 1 FROM TB_MIX WHERE 1 = 1
                    <if test="k == 'A'">AND KA = 1</if>
                    <if test="k == 'B'">AND KB = 1</if>
                    ORDER BY
                    <choose>
                      <when test="s == 'x'">X</when>
                      <otherwise>Y</otherwise>
                    </choose>
                  </select>
                """);

        List<String> many = Mb.texts(ctx, NS, "many");
        assertThat(many).hasSize(16);
        assertThat(many.get(0)).isEqualTo("SELECT 1 FROM TB_MANY WHERE 1 = 1 AND C1 = ?");
        assertThat(many.get(15)).isEqualTo("SELECT 1 FROM TB_MANY WHERE 1 = 1 AND C16 = ?");
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNSUPPORTED_SYNTAX)).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Severity.WARN);
            assertThat(d.message()).contains("many").contains("16");
        });

        assertThat(Mb.texts(ctx, NS, "mixed")).containsExactly(
                "SELECT 1 FROM TB_MIX WHERE 1 = 1 AND KA = 1 ORDER BY X",
                "SELECT 1 FROM TB_MIX WHERE 1 = 1 AND KB = 1 ORDER BY X",
                "SELECT 1 FROM TB_MIX WHERE 1 = 1 AND KA = 1 ORDER BY Y");
    }
}
