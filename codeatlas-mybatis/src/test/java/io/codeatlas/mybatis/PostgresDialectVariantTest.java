package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL이 대상 방언이 된 뒤의 방언별 매퍼 선택(F-019-R4, R32. sql 모듈의 DDL 선택과 같은 순위다): ① 명시값 ② 프로젝트 설정이
 * 가리키는 대상 방언(oracle, db2, postgres) ③ Oracle ④ DB2 ⑤ PostgreSQL ⑥ Oracle 계열 ⑦ 설정이 가리키는 대상 외 방언 ⑧ 그 밖.
 */
class PostgresDialectVariantTest {

    private static final String NS = "aDAO";
    private static final String VARIANTS_CODE = "DIALECT_VARIANT_MAPPERS";

    private static String variant(String table) {
        return Mb.mapper(NS, "  <select id=\"find\" resultType=\"map\">SELECT ID FROM " + table + " WHERE ID = #{id}</select>\n");
    }

    /** 방언별 매퍼 파일({@code mapper/A_SQL_<방언>.xml})을 한 벌씩 둔 프로젝트. 구문의 테이블은 {@code TB_<방언>}이다. */
    private static TestProject project(String... dialects) {
        TestProject project = TestProject.create();
        for (String dialect : dialects) {
            project.file("mapper/A_SQL_" + dialect + ".xml", variant("TB_" + dialect.toUpperCase(java.util.Locale.ROOT)));
        }
        return project;
    }

    private static String selected(AnalysisContext ctx) {
        return Mb.stmt(ctx, NS, "find").attrString("dialectVariant");
    }

    private static Diagnostic summary(AnalysisContext ctx) {
        List<Diagnostic> summaries = Mb.diags(ctx, VARIANTS_CODE);
        assertThat(summaries).hasSize(1);
        return summaries.get(0);
    }

    @Test
    @DisplayName("F-019-AC4 (가) oracle·postgres면 oracle, (나) db2·postgres면 db2: Oracle·DB2 파일이 있는 프로젝트의 선택은 달라지지 않는다")
    void oracleAndDb2StayAheadOfPostgres() {
        try (TestProject project = project("postgres", "oracle")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("oracle");
            assertThat(Mb.text(ctx, NS, "find")).isEqualTo("SELECT ID FROM TB_ORACLE WHERE ID = ?");
            assertThat(summary(ctx).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(ctx).message()).contains("방언별 매퍼 2벌 감지(oracle, postgres): oracle 파일을 사용");
        }
        try (TestProject project = project("postgres", "db2")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("db2");
            assertThat(summary(ctx).severity()).isEqualTo(Severity.INFO);
        }
    }

    @Test
    @DisplayName("F-019-AC4 (다) postgres·tibero면 postgres, (라) postgres·mysql이면 postgres: 대상 방언을 골랐으므로 요약은 WARN이 아니다")
    void postgresAheadOfOracleFamilyAndOthers() {
        try (TestProject project = project("tibero", "postgres")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("postgres");
            assertThat(summary(ctx).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(ctx).message()).contains("방언별 매퍼 2벌 감지(postgres, tibero): postgres 파일을 사용").doesNotContain("부정확");
        }
        try (TestProject project = project("mysql", "postgres")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("postgres");
            assertThat(Mb.text(ctx, NS, "find")).isEqualTo("SELECT ID FROM TB_POSTGRES WHERE ID = ?");
            assertThat(summary(ctx).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(ctx).message()).contains("방언별 매퍼 2벌 감지(postgres, mysql): postgres 파일을 사용").doesNotContain("부정확");
        }
        // 파일 이름의 별칭(postgresql, pgsql)도 같은 방언이다
        try (TestProject project = TestProject.create()
                .file("mapper/A_SQL_mysql.xml", variant("TB_MYSQL"))
                .file("mapper/pgsql/A.xml", variant("TB_POSTGRES"))) {
            assertThat(selected(Mb.run(project))).isEqualTo("postgres");
        }
    }

    @Test
    @DisplayName("F-019-AC4 (마) postgres·mysql에 프로젝트 설정이 mysql이어도 postgres를 고르고, INFO 요약에 설정과 다르게 골랐다는 것과 설정대로 돌리는 방법을 적는다")
    void postgresBeatsForeignSetting() {
        try (TestProject project = project("mysql", "postgres")
                .file("src/main/resources/globals.properties", "Globals.DbType=mysql\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("postgres");
            assertThat(summary(ctx).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(ctx).message())
                    .contains("프로젝트 설정(src/main/resources/globals.properties:Globals.DbType=mysql)과 다르게 대상 방언 postgres 파일을 사용")
                    .contains("설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`").doesNotContain("부정확");
        }
    }

    @Test
    @DisplayName("F-019-AC4 (바) oracle·postgres에 프로젝트 설정이 postgresql이면 postgres: 설정이 가리키는 대상 방언이 Oracle보다 먼저다")
    void configuredPostgresBeatsOracle() {
        try (TestProject project = project("oracle", "postgres")
                .file("src/main/resources/application.properties", "spring.datasource.url=jdbc:postgresql://localhost/shop\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("postgres");
            assertThat(summary(ctx).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(ctx).message()).contains("방언별 매퍼 2벌 감지(postgres, oracle): postgres 파일을 사용")
                    .contains("프로젝트 설정(src/main/resources/application.properties:spring.datasource.url=jdbc:postgresql://localhost/shop)에 따라"
                            + " postgres 선택");
        }
        // 설정이 Oracle 계열(tibero)이면 계열 안에서만 먼저다: PostgreSQL이 그보다 앞이다
        try (TestProject project = project("tibero", "altibase", "postgres")
                .file("src/main/resources/globals.properties", "Globals.DbType=altibase\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("postgres");
            assertThat(summary(ctx).message()).contains("방언별 매퍼 3벌 감지(postgres, altibase, tibero)");
        }
    }

    @Test
    @DisplayName("F-019-AC4 (사) F-019-AC30 oracle·postgres에 --dialect postgres면 postgres 파일의 구문을 쓰고, 옵션이 없으면 oracle이다. "
            + "방언별 파일에서 온 구문의 내장 소스 attrs.dialect는 그 파일의 방언(postgres)이다")
    void dialectOptionSelectsPostgres() {
        try (TestProject project = TestProject.create()
                .file("mapper/Order_SQL_oracle.xml", variant("TB_ORACLE"))
                .file("mapper/Order_SQL_postgres.xml", variant("TB_POSTGRES"))) {
            // (가) 옵션 없이: oracle
            AnalysisContext plain = Mb.run(project);
            assertThat(selected(plain)).isEqualTo("oracle");
            assertThat(Mb.variants(plain, NS, "find")).extracting(EmbeddedSource::text).extracting(Mb::norm)
                    .containsExactly("SELECT ID FROM TB_ORACLE WHERE ID = ?");
            assertThat(Mb.variants(plain, NS, "find").get(0).attrs()).containsEntry("dialect", "oracle");

            // (나) --dialect postgres: postgres
            project.options(AnalysisOptions.builder().dialect(Dialect.POSTGRES).build());
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("postgres");
            List<EmbeddedSource> sources = Mb.variants(ctx, NS, "find");
            assertThat(sources).extracting(EmbeddedSource::text).extracting(Mb::norm)
                    .containsExactly("SELECT ID FROM TB_POSTGRES WHERE ID = ?");
            assertThat(sources.get(0).attrs()).containsEntry("dialect", "postgres").doesNotContainKey("databaseId");
            assertThat(summary(ctx).severity()).isEqualTo(Severity.INFO);
            assertThat(Mb.diags(ctx, "DIALECT_NOT_FOUND")).isEmpty();
        }
        // 요청한 postgres의 매퍼 파일이 없으면 전처럼 무엇으로 대체했는지 알린다(--dialect에서 온 요청이라고 적는다)
        try (TestProject project = project("oracle", "mysql").options(AnalysisOptions.builder().dialect(Dialect.POSTGRES).build())) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("oracle");
            assertThat(Mb.diags(ctx, "DIALECT_NOT_FOUND")).hasSize(1);
            assertThat(Mb.diags(ctx, "DIALECT_NOT_FOUND").get(0).message()).contains("postgres(`--dialect`)").contains("oracle 파일을 사용");
        }
        // mybatis.dialectVariant가 --dialect보다 먼저다(전과 같다)
        try (TestProject project = project("oracle", "postgres")
                .options(AnalysisOptions.builder().dialect(Dialect.POSTGRES).extra("mybatis.dialectVariant", "oracle").build())) {
            assertThat(selected(Mb.run(project))).isEqualTo("oracle");
        }
    }

    @Test
    @DisplayName("F-019-AC30 databaseId=\"postgresql\" 구문의 내장 소스는 attrs.dialect=postgresql(소문자), attrs.databaseId=원문이다. "
            + "pgsql·PostgreSQL도 쓴 그대로 소문자로 넘긴다")
    void databaseIdPostgresPassedAsDialectHint() {
        AnalysisContext ctx = Mb.analyze("orderDAO", """
                  <select id="page" resultType="map" databaseId="postgresql">SELECT ID FROM ORDERS LIMIT #{size} OFFSET #{offset}</select>
                  <select id="page" resultType="map" databaseId="oracle">SELECT ID FROM ORDERS WHERE ROWNUM &lt;= #{size}</select>
                  <select id="one" resultType="map" databaseId="PostgreSQL">SELECT ID FROM ORDERS WHERE ID = #{id}</select>
                  <select id="two" resultType="map" databaseId="pgsql">SELECT ID FROM ORDERS WHERE ID = #{id}</select>
                  <select id="plain" resultType="map">SELECT ID FROM ORDERS</select>
                """);

        List<EmbeddedSource> page = Mb.variants(ctx, "orderDAO", "page");
        assertThat(page).hasSize(2);
        assertThat(page.get(0).attrs()).containsEntry("dialect", "postgresql").containsEntry("databaseId", "postgresql");
        assertThat(page.get(1).attrs()).containsEntry("dialect", "oracle").containsEntry("databaseId", "oracle");
        assertThat(Mb.variants(ctx, "orderDAO", "one").get(0).attrs())
                .containsEntry("dialect", "postgresql").containsEntry("databaseId", "PostgreSQL");
        assertThat(Mb.variants(ctx, "orderDAO", "two").get(0).attrs()).containsEntry("dialect", "pgsql").containsEntry("databaseId", "pgsql");
        assertThat(Mb.variants(ctx, "orderDAO", "plain").get(0).attrs()).doesNotContainKey("dialect").doesNotContainKey("databaseId");
    }

    @Test
    @DisplayName("F-019-AC4 방언별 매퍼가 대상 외 방언뿐이면 전처럼 WARN이고, 문구의 대상 방언에 PostgreSQL이 있다")
    void foreignOnlyStillWarns() {
        try (TestProject project = project("mysql", "cubrid")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(selected(ctx)).isEqualTo("cubrid");
            assertThat(summary(ctx).severity()).isEqualTo(Severity.WARN);
            assertThat(summary(ctx).message()).contains("이 도구의 대상 방언(Oracle, DB2, PostgreSQL)의 파일이 없어 cubrid 파일을 씀")
                    .contains("부정확할 수 있음");
        }
    }

    @Test
    @DisplayName("F-019-AC4 mybatis.dialectVariant에 모르는 이름을 주면 INVALID_OPTION의 예시에 postgres가 있다")
    void invalidOptionMessageListsPostgres() {
        try (TestProject project = project("oracle", "postgres")
                .options(AnalysisOptions.builder().extra("mybatis.dialectVariant", "nosuch").build())) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.diags(ctx, "INVALID_OPTION")).hasSize(1);
            assertThat(Mb.diags(ctx, "INVALID_OPTION").get(0).message()).contains("auto, all, oracle, db2, postgres, mysql 등");
            assertThat(selected(ctx)).isEqualTo("oracle");
        }
    }
}
