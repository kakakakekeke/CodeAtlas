package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.node;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.analysis.DialectSelection;
import io.codeatlas.sql.analysis.ProjectDialect;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL을 대상 방언으로 둔 방언 순위와 선택(F-019-R4, F-019-R5). 순위: 명시값 → 프로젝트 설정이 가리키는 대상 방언 → Oracle → DB2 →
 * PostgreSQL → Oracle 계열(Tibero, Altibase) → 설정이 가리키는 대상 외 방언 → 그 밖의 방언.
 */
class PostgresDialectSelectionTest {

    /** 같은 테이블 TB_A를 정의한 방언별 DDL. 방언마다 컬럼 하나가 다르다. */
    private static TestProject variants(String... dialects) {
        TestProject project = TestProject.create();
        for (String dialect : dialects) {
            project.file("db/" + dialect + "/ddl_" + dialect + ".sql",
                    "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, " + dialect.toUpperCase() + "_COL INT);\n");
        }
        return project;
    }

    private static String file(String dialect) {
        return "db/" + dialect + "/ddl_" + dialect + ".sql";
    }

    private static Diagnostic summary(GraphModel g) {
        List<Diagnostic> found = diagnostics(g, SqlDiagnosticCodes.DIALECT_VARIANT_DDL);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    @DisplayName("F-019-AC4 (가)(나) Oracle·DB2 파일이 있으면 PostgreSQL보다 먼저다: oracle·postgres는 oracle, db2·postgres는 db2")
    void oracleAndDb2ComeBeforePostgres() {
        try (TestProject project = variants("postgres", "oracle")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo(file("oracle"));
            assertThat(summary(g).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(g).message()).contains("방언별 DDL 2벌 감지(oracle, postgres): oracle 파일을 사용");
        }
        try (TestProject project = variants("postgres", "db2")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo(file("db2"));
            assertThat(summary(g).message()).contains("방언별 DDL 2벌 감지(db2, postgres): db2 파일을 사용");
        }
    }

    @Test
    @DisplayName("F-019-AC4 (다)(라) PostgreSQL은 Oracle 계열과 그 밖의 방언보다 먼저다: postgres·tibero, postgres·mysql 모두 postgres이고 요약은 WARN이 아니다")
    void postgresComesBeforeOracleFamilyAndOthers() {
        for (String other : new String[] {"tibero", "altibase", "mysql", "cubrid"}) {
            try (TestProject project = variants(other, "postgres")) {
                GraphModel g = project.run(new SqlAnalyzer()).graph();
                assertThat(node(g, "db:table:TB_A").location().file()).as(other).isEqualTo(file("postgres"));
                Diagnostic d = summary(g);
                assertThat(d.severity()).as(other).isEqualTo(Severity.INFO);
                assertThat(d.message()).as(other).contains("postgres 파일을 사용(테이블 1개)").contains("`--dialect <oracle|db2|postgres>`")
                        .doesNotContain("부정확");
                assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_TARGET)).as(other).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("F-019-AC4 (마) 프로젝트 설정이 대상 외 방언(mysql)이어도 대상 방언 postgres 파일이 있으면 postgres를 고르고, 설정과 다르게 골랐다고 알린다")
    void postgresBeatsForeignSetting() {
        try (TestProject project = variants("mysql", "postgres").file("src/main/resources/globals.properties", "Globals.DbType=mysql\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo(file("postgres"));
            Diagnostic d = summary(g);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("Globals.DbType=mysql)과 다르게 대상 방언 postgres 파일을 사용")
                    .contains("설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`");
        }
    }

    @Test
    @DisplayName("F-019-AC4 (바) 프로젝트 설정이 postgresql이면 Oracle 파일이 있어도 postgres를 고른다(설정이 가리키는 대상 방언이 먼저다)")
    void postgresBySetting() {
        try (TestProject project = variants("oracle", "postgres")
                .file("src/main/resources/application.properties", "spring.datasource.url=jdbc:postgresql://localhost/shop\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo(file("postgres"));
            Diagnostic d = summary(g);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("방언별 DDL 2벌 감지(postgres, oracle)").contains("에 따라 postgres 선택");
        }
    }

    @Test
    @DisplayName("F-019-AC4 (사) --dialect postgres는 요청이다: Oracle 파일이 있어도 postgres를 고르고, postgres 파일이 없으면 DIALECT_NOT_FOUND로 알린다")
    void postgresByOption() {
        AnalysisOptions options = AnalysisOptions.builder().dialect(Dialect.POSTGRES).build();
        try (TestProject project = variants("oracle", "postgres").options(options)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo(file("postgres"));
            assertThat(summary(g).severity()).isEqualTo(Severity.INFO);
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND)).isEmpty();
        }
        try (TestProject project = variants("oracle", "mysql").options(options)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo(file("oracle"));
            List<Diagnostic> notFound = diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND);
            assertThat(notFound).hasSize(1);
            assertThat(notFound.get(0).message()).contains("요청한 방언 postgres(`--dialect`)");
        }
        // mybatis.dialectVariant가 --dialect 다음이다(sql 쪽 순서)
        try (TestProject project = variants("oracle", "postgres", "mysql")
                .options(AnalysisOptions.builder().dialect(Dialect.POSTGRES).extra("mybatis.dialectVariant", "mysql").build())) {
            assertThat(node(project.run(new SqlAnalyzer()).graph(), "db:table:TB_A").location().file()).isEqualTo(file("postgres"));
        }
    }

    @Test
    @DisplayName("F-019-AC4 방언 순위: 명시값 → 설정이 가리키는 대상 방언 → oracle → db2 → postgres → Oracle 계열 → 설정이 가리키는 대상 외 방언 → 그 밖의 방언")
    void rankOrder() {
        DialectSelection defaults = DialectSelection.defaults();
        assertThat(List.of("oracle", "db2", "postgres", "tibero", "altibase", "mysql")).isSortedAccordingTo(
                (a, b) -> Integer.compare(defaults.rank(a), defaults.rank(b)));

        DialectSelection altibaseSetting = new DialectSelection(null, null, new ProjectDialect.Setting("altibase", "a.properties", "Globals.DbType", "altibase"));
        assertThat(List.of("oracle", "db2", "postgres", "altibase", "tibero", "mysql")).isSortedAccordingTo(
                (a, b) -> Integer.compare(altibaseSetting.rank(a), altibaseSetting.rank(b)));

        DialectSelection mysqlSetting = new DialectSelection(null, null, new ProjectDialect.Setting("mysql", "a.properties", "Globals.DbType", "mysql"));
        assertThat(List.of("oracle", "db2", "postgres", "tibero", "altibase", "mysql", "cubrid")).isSortedAccordingTo(
                (a, b) -> Integer.compare(mysqlSetting.rank(a), mysqlSetting.rank(b)));

        DialectSelection postgresSetting = new DialectSelection(null, null,
                new ProjectDialect.Setting("postgres", "application.properties", "spring.datasource.url", "jdbc:postgresql://h/db"));
        assertThat(List.of("postgres", "oracle", "db2", "tibero", "altibase", "mysql")).isSortedAccordingTo(
                (a, b) -> Integer.compare(postgresSetting.rank(a), postgresSetting.rank(b)));

        DialectSelection option = new DialectSelection(Dialect.POSTGRES, "mysql", new ProjectDialect.Setting("db2", "a.properties", "jdbc.type", "db2"));
        assertThat(List.of("postgres", "mysql", "db2", "oracle", "tibero", "altibase", "cubrid")).isSortedAccordingTo(
                (a, b) -> Integer.compare(option.rank(a), option.rank(b)));
    }

    @Test
    @DisplayName("F-019-AC5 PostgreSQL 스크립트뿐인 프로젝트에는 DIALECT_NOT_TARGET이 없다(경로의 방언 이름, 내용 특징 모두). MySQL 덤프뿐이면 전처럼 있다")
    void postgresOnlyProjectIsTarget() {
        try (TestProject project = TestProject.create().file("db/postgres/schema.sql", "CREATE TABLE orders (id bigint NOT NULL, status text);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ORDERS").declared()).isTrue();
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_TARGET)).isEmpty();
        }
        try (TestProject project = TestProject.create().file("db/schema.sql", "CREATE TABLE orders (id bigserial NOT NULL, status text);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ORDERS").declared()).isTrue();
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_TARGET)).isEmpty();
        }
        try (TestProject project = TestProject.create()
                .file("db/dump.sql", "CREATE TABLE `orders` (`id` bigint NOT NULL AUTO_INCREMENT, `status` varchar(10)) ENGINE=InnoDB;\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            List<Diagnostic> found = diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_TARGET);
            assertThat(found).hasSize(1);
            assertThat(found.get(0).message()).contains("mysql로 보임").contains("이 도구의 대상 방언은 Oracle, DB2, PostgreSQL");
        }
    }
}
