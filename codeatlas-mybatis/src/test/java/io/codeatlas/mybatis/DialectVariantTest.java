package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 같은 namespace·구문 id가 DB 방언별 파일로 여러 벌 있는 프로젝트(F-009-R15, R16). */
class DialectVariantTest {

    private static final String NS = "stockDAO";
    private static final String VARIANTS_CODE = "DIALECT_VARIANT_MAPPERS";
    private static final String DUPLICATE_CODE = "DUPLICATE_STATEMENT_ID";

    /** 방언 파일 하나. 조각, resultMap, 구문 {@code find}가 방언마다 다른 테이블을 본다. */
    private static String variant(String table, String extraStatements) {
        return Mb.mapper(NS, """
                  <sql id="cols">%1$s_ID, %1$s_NM</sql>
                  <select id="find" resultType="map">
                    SELECT <include refid="cols"/> FROM %1$s WHERE %1$s_ID = #{id}
                  </select>
                  <select id="same" resultType="map">SELECT STOCK_ID FROM TB_STOCK</select>
                """.formatted(table) + extraStatements);
    }

    private static TestProject fourDialects() {
        return TestProject.create()
                .file("mapper/stock/Stock_SQL_altibase.xml", variant("TB_ALT", ""))
                .file("mapper/stock/Stock_SQL_mysql.xml", variant("TB_MY", """
                          <select id="onlyMy">SELECT 1 FROM TB_ONLY_MY</select>
                          <select id="myAndHsql">SELECT 1 FROM TB_MY_FIRST</select>
                        """))
                .file("mapper/stock/Stock_SQL_hsql.xml", variant("TB_HS", """
                          <select id="myAndHsql">SELECT 1 FROM TB_HS_SECOND</select>
                        """))
                .file("mapper/stock/Stock_SQL_oracle.xml", variant("TB_ORA", ""));
    }

    private static List<Diagnostic> summaries(AnalysisContext ctx) {
        return Mb.diags(ctx, VARIANTS_CODE);
    }

    @Test
    @DisplayName("F-009-AC31 방언별 파일이 여러 벌이면 하나(자동이면 Oracle)만 골라 쓰고 요약 INFO 1건만 남긴다")
    void picksOneDialectAutomatically() {
        try (TestProject project = fourDialects()) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(ctx.graph().nodes(NodeKind.MYBATIS_MAPPER)).hasSize(1);
            assertThat(ctx.graph().nodes(NodeKind.SQL_FRAGMENT)).hasSize(1);
            assertThat(Mb.text(ctx, NS, "find")).isEqualTo("SELECT TB_ORA_ID, TB_ORA_NM FROM TB_ORA WHERE TB_ORA_ID = ?");
            Node find = Mb.stmt(ctx, NS, "find");
            assertThat(find.attrString("dialectVariant")).isEqualTo("oracle");
            assertThat(find.attr("variants")).isEqualTo(1);
            assertThat(find.location().file()).isEqualTo("mapper/stock/Stock_SQL_oracle.xml");
            assertThat(Mb.variants(ctx, NS, "find").get(0).attrs()).containsEntry("dialect", "oracle");
            // 조각도 고른 방언 파일의 것이다
            Node cols = ctx.graph().node(Ids.mybatisFragment(NS, "cols")).orElseThrow();
            assertThat(cols.location().file()).isEqualTo("mapper/stock/Stock_SQL_oracle.xml");
            assertThat(cols.attrString("sql")).isEqualTo("TB_ORA_ID, TB_ORA_NM");
            assertThat(cols.attrString("dialectVariant")).isEqualTo("oracle");

            Node mapper = ctx.graph().node(Ids.mybatisMapper(NS)).orElseThrow();
            assertThat(mapper.attrString("dialectVariant")).isEqualTo("oracle");
            assertThat(mapper.location().file()).isEqualTo("mapper/stock/Stock_SQL_oracle.xml");
            // 고르지 않은 파일 가운데 구문을 하나도 내지 않은 파일(altibase)은 매퍼를 CONTAINS하지 않는다.
            // oracle에 없는 구문을 보충한 파일은 남는다: onlyMy는 mysql, myAndHsql은 그 밖의 방언의 순서(사전순, F-009-R26)가 앞인 hsql
            assertThat(ctx.graph().incoming(Ids.mybatisMapper(NS), EdgeKind.CONTAINS)).extracting(Edge::from)
                    .containsExactlyInAnyOrder(Ids.file("mapper/stock/Stock_SQL_oracle.xml"),
                            Ids.file("mapper/stock/Stock_SQL_mysql.xml"), Ids.file("mapper/stock/Stock_SQL_hsql.xml"));

            assertThat(Mb.diags(ctx, DUPLICATE_CODE)).isEmpty();
            assertThat(summaries(ctx)).singleElement().satisfies(d -> {
                assertThat(d.severity()).isEqualTo(Severity.INFO);
                assertThat(d.message()).contains("4벌").contains("oracle").contains("mybatis.dialectVariant");
            });
            assertThat(ctx.graph().diagnostics()).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-009-AC31 고른 방언 파일에 없는 구문은 다음 순위 방언 파일에서 가져온다")
    void fallsBackPerStatement() {
        try (TestProject project = fourDialects()) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.text(ctx, NS, "onlyMy")).isEqualTo("SELECT 1 FROM TB_ONLY_MY");
            assertThat(Mb.stmt(ctx, NS, "onlyMy").attrString("dialectVariant")).isEqualTo("mysql");
            // mysql과 hsql에만 있으면 그 밖의 방언의 순서(이름의 사전순, F-009-R26)가 앞인 hsql이다(전에는 방언 표의 순서로 mysql이었다)
            assertThat(Mb.text(ctx, NS, "myAndHsql")).isEqualTo("SELECT 1 FROM TB_HS_SECOND");
            assertThat(Mb.stmt(ctx, NS, "myAndHsql").attrString("dialectVariant")).isEqualTo("hsql");
            assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).hasSize(4);
        }
    }

    @Test
    @DisplayName("F-009-AC32 --dialect가 있으면 그 방언 파일을, mybatis.dialectVariant가 있으면 그것을 먼저 고른다. 폴더 이름의 방언도 읽는다")
    void dialectOptionAndForcedVariant() {
        try (TestProject project = fourDialects()
                .file("mapper/db2/Stock.xml", variant("TB_DB2", ""))
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).build())) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.text(ctx, NS, "find")).isEqualTo("SELECT TB_DB2_ID, TB_DB2_NM FROM TB_DB2 WHERE TB_DB2_ID = ?");
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("db2");
            assertThat(summaries(ctx)).singleElement().satisfies(d -> assertThat(d.message()).contains("5벌").contains("db2"));
        }
        try (TestProject project = fourDialects()
                .options(AnalysisOptions.builder().dialect(Dialect.ORACLE).extra("mybatis.dialectVariant", "MySQL").build())) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.text(ctx, NS, "find")).isEqualTo("SELECT TB_MY_ID, TB_MY_NM FROM TB_MY WHERE TB_MY_ID = ?");
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("mysql");
            assertThat(ctx.graph().node(Ids.mybatisMapper(NS)).orElseThrow().attrString("dialectVariant")).isEqualTo("mysql");
            assertThat(Mb.diags(ctx, DiagnosticCodes.INVALID_OPTION)).isEmpty();
        }
        try (TestProject project = fourDialects()
                .options(AnalysisOptions.builder().extra("mybatis.dialectVariant", "nosuchdb").build())) {
            AnalysisContext ctx = Mb.run(project);
            // 모르는 값은 경고하고 자동 순서로 고른다
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
            assertThat(Mb.diags(ctx, DiagnosticCodes.INVALID_OPTION)).singleElement()
                    .satisfies(d -> assertThat(d.severity()).isEqualTo(Severity.WARN));
        }
    }

    @Test
    @DisplayName("F-009-AC32 mybatis.dialectVariant=all이면 모든 방언 파일의 정의를 한 노드에 합치고 구문마다 경고하지 않는다")
    void allKeepsEveryVariant() {
        try (TestProject project = fourDialects()
                .options(AnalysisOptions.builder().extra("mybatis.dialectVariant", "all").build())) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.texts(ctx, NS, "find")).containsExactly(
                    "SELECT TB_ALT_ID, TB_ALT_NM FROM TB_ALT WHERE TB_ALT_ID = ?",
                    "SELECT TB_HS_ID, TB_HS_NM FROM TB_HS WHERE TB_HS_ID = ?",
                    "SELECT TB_MY_ID, TB_MY_NM FROM TB_MY WHERE TB_MY_ID = ?",
                    "SELECT TB_ORA_ID, TB_ORA_NM FROM TB_ORA WHERE TB_ORA_ID = ?");
            assertThat(Mb.variants(ctx, NS, "find")).extracting(s -> s.attrs().get("dialect"))
                    .containsExactly("altibase", "hsql", "mysql", "oracle");
            assertThat(Mb.stmt(ctx, NS, "find").attr("dialectVariant")).isNull();
            assertThat(Mb.diags(ctx, DUPLICATE_CODE)).isEmpty();
            assertThat(summaries(ctx)).singleElement().satisfies(d -> assertThat(d.message()).contains("all"));
        }
    }

    @Test
    @DisplayName("F-009-AC33 방언을 읽을 수 없는 두 파일에 같은 구문이 있으면 지금처럼 합치고 DUPLICATE_STATEMENT_ID를 남긴다")
    void unrelatedDuplicateStillWarns() {
        try (TestProject project = TestProject.create()
                .file("mapper/a/Stock.xml", Mb.mapper(NS, "<select id=\"dup\">SELECT 1 FROM TB_A</select>"))
                .file("mapper/b/Stock.xml", Mb.mapper(NS, "<select id=\"dup\">SELECT 1 FROM TB_B</select>"))) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.texts(ctx, NS, "dup")).containsExactly("SELECT 1 FROM TB_A", "SELECT 1 FROM TB_B");
            assertThat(Mb.stmt(ctx, NS, "dup").attr("dialectVariant")).isNull();
            assertThat(Mb.diags(ctx, DUPLICATE_CODE)).singleElement()
                    .satisfies(d -> assertThat(d.severity()).isEqualTo(Severity.WARN));
            assertThat(summaries(ctx)).isEmpty();
        }
        // 방언 토큰이 같은 두 파일(복사본)도 방언 변형이 아니라 중복이다
        try (TestProject project = TestProject.create()
                .file("mapper/a/Stock_SQL_oracle.xml", Mb.mapper(NS, "<select id=\"dup\">SELECT 1 FROM TB_A</select>"))
                .file("mapper/b/Stock_SQL_oracle.xml", Mb.mapper(NS, "<select id=\"dup\">SELECT 1 FROM TB_B</select>"))) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.texts(ctx, NS, "dup")).hasSize(2);
            assertThat(Mb.diags(ctx, DUPLICATE_CODE)).hasSize(1);
            assertThat(summaries(ctx)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-009-AC34 본문이 같은 전개본은 정의가 여러 벌이어도 한 번만 등록하고 variants도 한 번만 센다")
    void sameBodyCountsOnce() {
        try (TestProject project = fourDialects()
                .options(AnalysisOptions.builder().extra("mybatis.dialectVariant", "all").build())) {
            AnalysisContext ctx = Mb.run(project);

            List<EmbeddedSource> same = Mb.variants(ctx, NS, "same");
            assertThat(same).hasSize(1);
            assertThat(same.get(0).attrs()).containsEntry("variant", "1");
            assertThat(same.get(0).exactPositions()).isTrue();
            assertThat(Mb.stmt(ctx, NS, "same").attr("variants")).isEqualTo(1);
        }
        AnalysisContext ctx = Mb.analyze(NS, """
                  <insert id="twice">
                    <selectKey keyProperty="id" resultType="long" order="BEFORE">SELECT SEQ_A.NEXTVAL FROM DUAL</selectKey>
                    INSERT INTO TB_A (ID) VALUES (#{id})
                  </insert>
                  <insert id="twice">
                    <selectKey keyProperty="id" resultType="long" order="BEFORE">SELECT SEQ_A.NEXTVAL FROM DUAL</selectKey>
                    INSERT INTO TB_A (ID) VALUES (#{id})
                  </insert>
                """);
        assertThat(Mb.sources(ctx, NS, "twice")).hasSize(2);
        assertThat(Mb.stmt(ctx, NS, "twice").attr("variants")).isEqualTo(1);
        // 두 번 정의됐다는 사실은 그대로 알린다
        assertThat(Mb.diags(ctx, DUPLICATE_CODE)).hasSize(1);
    }

    @Test
    @DisplayName("F-009-AC39 요청한 방언(mybatis.dialectVariant, --dialect)의 매퍼 파일이 없으면 WARN DIALECT_NOT_FOUND에 무엇으로 대체했는지 적는다")
    void requestedDialectNotFound() {
        // --dialect db2: db2 매퍼가 없다. 전에는 조용히 Oracle을 썼다
        try (TestProject project = fourDialects().options(AnalysisOptions.builder().dialect(Dialect.DB2).build())) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
            List<Diagnostic> warn = Mb.diags(ctx, "DIALECT_NOT_FOUND");
            assertThat(warn).hasSize(1);
            assertThat(warn.get(0).severity()).isEqualTo(Severity.WARN);
            assertThat(warn.get(0).location()).isNull();
            assertThat(warn.get(0).message()).contains("db2(`--dialect`)").contains("매퍼 파일이 없음").contains("oracle, altibase, hsql, mysql")
                    .contains("oracle 파일을 사용");
        }
        // 옵션으로 준 방언도 없고 --dialect의 방언도 없다: 둘 다 적는다. 옵션이 먼저다
        try (TestProject project = fourDialects()
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).extra("mybatis.dialectVariant", "cubrid").build())) {
            AnalysisContext ctx = Mb.run(project);
            List<Diagnostic> warn = Mb.diags(ctx, "DIALECT_NOT_FOUND");
            assertThat(warn).hasSize(1);
            assertThat(warn.get(0).message()).contains("cubrid(`mybatis.dialectVariant`), db2(`--dialect`)");
        }
        // 요청한 방언의 파일이 있으면, 그리고 all이면 경고하지 않는다
        try (TestProject project = fourDialects()
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).extra("mybatis.dialectVariant", "mysql").build())) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("mysql");
            assertThat(Mb.diags(ctx, "DIALECT_NOT_FOUND")).isEmpty();
        }
        try (TestProject project = fourDialects()
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).extra("mybatis.dialectVariant", "all").build())) {
            assertThat(Mb.diags(Mb.run(project), "DIALECT_NOT_FOUND")).isEmpty();
        }
        // 방언별 매퍼가 여러 벌이 아니면 경고하지 않는다
        try (TestProject project = TestProject.create().file("mapper/Stock_SQL_oracle.xml", variant("TB_ORA", ""))
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).build())) {
            assertThat(Mb.run(project).graph().diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-009-AC41 F-009-AC45 프로젝트 설정(Globals.DbType 등)에서 읽은 방언: 대상 외 방언이면 대상 방언(oracle) 매퍼가 있는 한 그쪽을 고르고 INFO로 알린다")
    void projectSettingDialect() {
        // 설정은 hsql이지만 대상 방언(oracle) 파일이 있다(F-009-R24. 전에는 설정대로 hsql을 골랐다)
        try (TestProject project = fourDialects()
                .file("src/main/resources/egovframework/egovProps/globals.properties", "#Globals.DbType = oracle\nGlobals.DbType = hsql\n")
                .file("src/test/resources/globals.properties", "Globals.DbType = altibase\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.text(ctx, NS, "find")).isEqualTo("SELECT TB_ORA_ID, TB_ORA_NM FROM TB_ORA WHERE TB_ORA_ID = ?");
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
            // oracle 파일에 없는 구문은 다음 순위에서 가져온다: 설정의 방언(hsql)이 그 밖의 방언(mysql)보다 먼저다
            assertThat(Mb.stmt(ctx, NS, "onlyMy").attrString("dialectVariant")).isEqualTo("mysql");
            assertThat(Mb.stmt(ctx, NS, "myAndHsql").attrString("dialectVariant")).isEqualTo("hsql");
            Diagnostic d = summaries(ctx).get(0);
            assertThat(summaries(ctx)).hasSize(1);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("oracle 파일을 사용")
                    .contains("프로젝트 설정(src/main/resources/egovframework/egovProps/globals.properties:Globals.DbType=hsql)과 다르게 대상 방언 oracle 파일을 사용")
                    .contains("설정대로 분석하려면 `--opt mybatis.dialectVariant=hsql`");
            assertThat(Mb.diags(ctx, "DIALECT_NOT_FOUND")).isEmpty();
        }
        // 명시한 옵션(--dialect, mybatis.dialectVariant)이 설정보다 먼저다
        try (TestProject project = fourDialects().file("src/main/resources/globals.properties", "Globals.DbType = hsql\n")
                .options(AnalysisOptions.builder().dialect(Dialect.ORACLE).build())) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
            assertThat(summaries(ctx).get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(summaries(ctx).get(0).message()).doesNotContain("프로젝트 설정");
        }
        // 설정이 Oracle 계열(altibase)이어도 Oracle 파일이 있으면 Oracle이 먼저다(계열 안에서만 설정이 먼저다). INFO다
        try (TestProject project = fourDialects().file("src/main/resources/application.yml", "spring:\n  datasource:\n    url: jdbc:Altibase://db:20300/mydb\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
            Diagnostic d = summaries(ctx).get(0);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("프로젝트 설정(src/main/resources/application.yml:spring.datasource.url=jdbc:Altibase://db:20300/mydb)과 다르게 대상 방언 oracle 파일을 사용")
                    .doesNotContain("부정확");
        }
        // 설정 파일마다 값이 다르면 쓰지 않는다
        try (TestProject project = fourDialects().file("a/globals.properties", "Globals.DbType = hsql\n").file("b/globals.properties", "Globals.DbType = mysql\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
            assertThat(summaries(ctx).get(0).message()).contains("프로젝트 설정이 엇갈려 쓰지 않음(a/globals.properties=hsql, b/globals.properties=mysql)");
        }
    }
}
