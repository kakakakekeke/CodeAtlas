package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.spi.AnalysisContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 조건이 같은 {@code <when>}은 한 구문 안에서 함께 움직인다(F-009-R23). */
class ChooseCorrelationTest {

    private static final String NS = "com.a.ChooseMapper";

    @Test
    @DisplayName("F-009-AC42 등치 꼴 when이 같은 두 choose는 같은 값의 분기끼리만 짝을 이룬다(엇갈린 조합이 없다)")
    void equalityWhensMoveTogether() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="rss" resultType="map">
                    SELECT
                    <choose>
                      <when test="t.equalsIgnoreCase('tb_log')">LOG_ID, MSG</when>
                      <when test="t.equalsIgnoreCase('tb_zip')">ZIP, SN</when>
                      <otherwise>*</otherwise>
                    </choose>
                    FROM
                    <choose>
                      <when test="t.equalsIgnoreCase('tb_log')">tb_log</when>
                      <when test="t.equalsIgnoreCase('tb_zip')">tb_zip</when>
                      <otherwise>tb_other</otherwise>
                    </choose>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "rss")).containsExactly(
                "SELECT LOG_ID, MSG FROM tb_log",
                "SELECT ZIP, SN FROM tb_zip",
                "SELECT * FROM tb_other");
        assertThat(Mb.stmt(ctx, NS, "rss").attr("variants")).isEqualTo(3);
        assertThat(Mb.stmt(ctx, NS, "rss").attrString("sql")).isEqualTo("SELECT LOG_ID, MSG FROM tb_log");
        assertThat(ctx.graph().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-009-AC42 include한 조각 안의 choose도 구문의 choose와 같은 값으로 움직인다")
    void fragmentChooseMovesWithStatementChoose() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <sql id="cols">
                    <choose>
                      <when test="t == 'log'">LOG_ID, MSG</when>
                      <when test="t == 'zip'">ZIP, SN</when>
                      <otherwise>*</otherwise>
                    </choose>
                  </sql>
                  <select id="viaInclude" resultType="map">
                    SELECT <include refid="cols"/> FROM
                    <choose>
                      <when test="t == 'log'">TB_LOG</when>
                      <when test="t == 'zip'">TB_ZIP</when>
                      <otherwise>TB_OTHER</otherwise>
                    </choose>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "viaInclude")).containsExactly(
                "SELECT LOG_ID, MSG FROM TB_LOG",
                "SELECT ZIP, SN FROM TB_ZIP",
                "SELECT * FROM TB_OTHER");
    }

    @Test
    @DisplayName("F-009-AC43 등치 꼴이 아닌 test도 문자열이 같으면(공백 정규화) 함께 참·거짓이 되고, 같은 test의 if도 따른다")
    void sameTestStringMovesTogether() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="search" resultType="map">
                    SELECT
                    <choose>
                      <when test="searchCnd != null and searchCnd != ''">A.TITLE</when>
                      <otherwise>A.ID</otherwise>
                    </choose>
                    FROM TB_A A
                    <choose>
                      <when test=" searchCnd  !=  null and searchCnd != '' ">JOIN TB_IDX I ON I.ID = A.ID</when>
                      <otherwise>JOIN TB_ALL L ON L.ID = A.ID</otherwise>
                    </choose>
                    WHERE 1 = 1
                    <if test="searchCnd != null and searchCnd != ''">AND I.WORD = #{searchWrd}</if>
                    <if test="useAt != null">AND A.USE_AT = #{useAt}</if>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "search")).containsExactly(
                "SELECT A.TITLE FROM TB_A A JOIN TB_IDX I ON I.ID = A.ID WHERE 1 = 1 AND I.WORD = ? AND A.USE_AT = ?",
                "SELECT A.ID FROM TB_A A JOIN TB_ALL L ON L.ID = A.ID WHERE 1 = 1 AND A.USE_AT = ?");
    }

    @Test
    @DisplayName("F-009-AC43 test가 겹치지 않는 choose는 서로 독립이다: 1번은 각 choose의 첫 분기, 나머지는 하나만 바꾼 것")
    void unrelatedChoosesStayIndependent() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="unrelated" resultType="map">
                    SELECT * FROM TB_O O WHERE 1 = 1
                    <choose>
                      <when test="a != null">AND O.A = #{a}</when>
                      <when test="b != null">AND O.B = #{b}</when>
                      <otherwise>AND O.C = 1</otherwise>
                    </choose>
                    <if test="d != null">AND O.D = #{d}</if>
                    ORDER BY
                    <choose>
                      <when test="s == 'dt'">O.DT</when>
                      <when test="s == 'amt'">O.AMT</when>
                    </choose>
                  </select>
                  <select id="mixedExprs" resultType="map">
                    SELECT 1 FROM
                    <choose>
                      <when test="t == 'A'">TB_TA</when>
                      <when test="u == 'X'">TB_UX</when>
                      <when test="t == 'B'">TB_TB</when>
                      <otherwise>TB_O</otherwise>
                    </choose>
                  </select>
                """);

        // choose 하나의 전개본 번호는 분기 순서를 따른다(when이 보는 식이 섞여 있어도)
        assertThat(Mb.texts(ctx, NS, "mixedExprs")).containsExactly(
                "SELECT 1 FROM TB_TA", "SELECT 1 FROM TB_UX", "SELECT 1 FROM TB_TB", "SELECT 1 FROM TB_O");
        assertThat(Mb.texts(ctx, NS, "unrelated")).containsExactly(
                "SELECT * FROM TB_O O WHERE 1 = 1 AND O.A = ? AND O.D = ? ORDER BY O.DT",
                "SELECT * FROM TB_O O WHERE 1 = 1 AND O.B = ? AND O.D = ? ORDER BY O.DT",
                "SELECT * FROM TB_O O WHERE 1 = 1 AND O.C = 1 AND O.D = ? ORDER BY O.DT",
                "SELECT * FROM TB_O O WHERE 1 = 1 AND O.A = ? AND O.D = ? ORDER BY O.AMT");
    }

    @Test
    @DisplayName("F-009-AC44 when의 식이 등치 if의 식과 같으면 if와 when이 같은 값으로 움직이고, 어느 값도 아니면 otherwise다")
    void whenFollowsIfEqualityValue() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="byKind" resultType="map">
                    SELECT S.ID
                    <if test="kind == 'D'">, S.DAY_CD</if>
                    <if test="kind == 'M'">, S.MONTH_CD</if>
                    FROM
                    <choose>
                      <when test="kind == 'M'">TB_MONTH S</when>
                      <when test="kind == 'D'">TB_DAY S</when>
                      <otherwise>TB_ETC S</otherwise>
                    </choose>
                  </select>
                """);

        // 1번은 식에서 처음 나온 값(D)이다. choose의 첫 when(M)이 아니라 값이 맞는 when을 고른다
        assertThat(Mb.texts(ctx, NS, "byKind")).containsExactly(
                "SELECT S.ID , S.DAY_CD FROM TB_DAY S",
                "SELECT S.ID , S.MONTH_CD FROM TB_MONTH S",
                "SELECT S.ID FROM TB_ETC S");
    }

    @Test
    @DisplayName("F-009-AC44 when 집합이 일부만 겹치면 한쪽에만 있는 값에서 다른 choose는 otherwise(없으면 본문 없음)다")
    void partiallyOverlappingWhens() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="partial" resultType="map">
                    SELECT
                    <choose>
                      <when test="t == 'a'">A_COL</when>
                      <when test="t == 'b'">B_COL</when>
                      <otherwise>*</otherwise>
                    </choose>
                    FROM
                    <choose>
                      <when test="t == 'a'">TB_A</when>
                      <when test="t == 'c'">TB_C</when>
                      <otherwise>TB_ETC</otherwise>
                    </choose>
                  </select>
                  <select id="noOtherwise" resultType="map">
                    SELECT * FROM TB_A A
                    <choose>
                      <when test="t == 'a'">JOIN TB_J J ON J.ID = A.ID</when>
                    </choose>
                    WHERE
                    <choose>
                      <when test="t == 'a'">J.X = 1</when>
                      <otherwise>A.X = 1</otherwise>
                    </choose>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "partial")).containsExactly(
                "SELECT A_COL FROM TB_A",
                "SELECT B_COL FROM TB_ETC",
                "SELECT * FROM TB_ETC",
                "SELECT * FROM TB_C");
        assertThat(Mb.texts(ctx, NS, "noOtherwise")).containsExactly(
                "SELECT * FROM TB_A A JOIN TB_J J ON J.ID = A.ID WHERE J.X = 1",
                "SELECT * FROM TB_A A WHERE A.X = 1");
    }

    @Test
    @DisplayName("F-009-AC44 앞의 when에 가려 고를 수 없는 분기(같은 test가 두 번)도 전개본에서 빠지지 않는다")
    void shadowedBranchIsKept() {
        AnalysisContext ctx = Mb.analyze(NS, """
                  <select id="shadowed" resultType="map">
                    SELECT 1 FROM
                    <choose>
                      <when test="x != null">TB_X1</when>
                      <when test="x != null">TB_X2</when>
                      <otherwise>TB_O</otherwise>
                    </choose>
                  </select>
                  <select id="shadowedEq" resultType="map">
                    SELECT 1 FROM
                    <choose>
                      <when test="x != null and t == 'a'">TB_E1</when>
                      <when test="y != null and t == 'a'">TB_E2</when>
                    </choose>
                  </select>
                """);

        assertThat(Mb.texts(ctx, NS, "shadowed"))
                .containsExactly("SELECT 1 FROM TB_X1", "SELECT 1 FROM TB_X2", "SELECT 1 FROM TB_O");
        assertThat(Mb.texts(ctx, NS, "shadowedEq")).containsExactly("SELECT 1 FROM TB_E1", "SELECT 1 FROM TB_E2");
    }
}
