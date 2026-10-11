package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.analysis.DialectDetector;
import io.codeatlas.sql.analysis.DialectSelection;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.script.RoutineUnitHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 읽기 방식을 정하는 한곳(F-019-R7, ADR-0012): {@link DialectDetector#syntaxOfFile}, {@link DialectDetector#syntaxOfEmbedded}와
 * 거기에 넘기는 프로젝트 방언({@link DialectSelection#projectSyntax}).
 *
 * <p>여기서는 "어느 읽기 방식이 선택되는가"까지만 본다. PostgreSQL 읽기 방식으로 읽은 결과({@code PARSE_FAILED} 없이
 * {@code CUSTOMERS.PROFILE} 읽기가 기록되는 것, F-019-AC7의 뒤쪽)는 {@link PostgresSqlAnalyzerTest}가 확인한다.
 */
class SyntaxDecisionTest {

    /** F-019-AC7의 매퍼 구문. */
    private static final String JSON_PATH = "SELECT c.profile #>> '{a,b}' FROM customers c WHERE c.id = #{id}";
    private static final String PLAIN = "SELECT c.id FROM customers c WHERE c.id = #{id}";
    private static final String BACKTICK = "SELECT `id` FROM `customers`";

    private static Syntax projectSyntax(AnalysisOptions options, String... files) {
        try (TestProject project = TestProject.create()) {
            for (int i = 0; i + 1 < files.length; i += 2) {
                project.file(files[i], files[i + 1]);
            }
            if (options != null) {
                project.options(options);
            }
            return DialectSelection.of(project.context()).projectSyntax();
        }
    }

    // ------------------------------------------------------------------ 내장 소스

    @Test
    @DisplayName("F-019-AC7 (가) 프로젝트 설정이 jdbc:postgresql이면 내장 소스를 PostgreSQL 읽기 방식으로 읽는다")
    void embeddedByProjectSetting() {
        Syntax project = projectSyntax(null, "src/main/resources/application.properties", "spring.datasource.url=jdbc:postgresql://localhost/shop\n");
        assertThat(project).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfEmbedded(null, project, JSON_PATH)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfEmbedded(null, project, PLAIN)).as("특징이 없는 구문도 프로젝트 방언을 따른다").isEqualTo(Syntax.POSTGRES);
    }

    @Test
    @DisplayName("F-019-AC7 (나) --dialect postgres면 PostgreSQL 읽기 방식이고, mybatis.dialectVariant=postgres도 같다")
    void embeddedByOption() {
        Syntax byDialect = projectSyntax(AnalysisOptions.builder().dialect(Dialect.POSTGRES).build());
        assertThat(byDialect).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfEmbedded(null, byDialect, JSON_PATH)).isEqualTo(Syntax.POSTGRES);
        Syntax byVariant = projectSyntax(AnalysisOptions.builder().extra("mybatis.dialectVariant", "postgresql").build());
        assertThat(byVariant).isEqualTo(Syntax.POSTGRES);
        // --dialect가 프로젝트 설정보다 먼저다
        assertThat(projectSyntax(AnalysisOptions.builder().dialect(Dialect.POSTGRES).build(),
                "src/main/resources/globals.properties", "Globals.DbType=mysql\n")).isEqualTo(Syntax.POSTGRES);
    }

    @Test
    @DisplayName("F-019-AC7 (다) 생산자가 넘긴 attrs.dialect(databaseId=postgresql)가 가장 먼저다: 프로젝트 방언이 달라도 PostgreSQL 읽기 방식이다")
    void embeddedByProducerDialect() {
        for (String attr : new String[] {"postgresql", "postgres", "pgsql", "PostgreSQL"}) {
            assertThat(DialectDetector.syntaxOfEmbedded(attr, null, JSON_PATH)).as(attr).isEqualTo(Syntax.POSTGRES);
            assertThat(DialectDetector.syntaxOfEmbedded(attr, Syntax.DEFAULT, PLAIN)).as(attr).isEqualTo(Syntax.POSTGRES);
            assertThat(DialectDetector.syntaxOfEmbedded(attr, Syntax.MYSQL, PLAIN)).as(attr).isEqualTo(Syntax.POSTGRES);
        }
        // 생산자가 다른 방언이라고 알렸으면 본문에 PostgreSQL 특징이 있어도 그 방언의 읽기 방식이다
        assertThat(DialectDetector.syntaxOfEmbedded("oracle", Syntax.POSTGRES, JSON_PATH)).isEqualTo(Syntax.DEFAULT);
        assertThat(DialectDetector.syntaxOfEmbedded("mysql", Syntax.POSTGRES, JSON_PATH)).isEqualTo(Syntax.MYSQL);
    }

    @Test
    @DisplayName("F-019-AC7 (라) 아무 표시가 없으면 그 구문의 PostgreSQL 내용 특징(#>>)으로 PostgreSQL 읽기 방식이다. 특징이 없으면 기본이다")
    void embeddedByContent() {
        Syntax project = projectSyntax(null);
        assertThat(project).isNull();
        assertThat(DialectDetector.syntaxOfEmbedded(null, project, JSON_PATH)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfEmbedded(null, project, PLAIN)).isEqualTo(Syntax.DEFAULT);
        assertThat(DialectDetector.syntaxOfEmbedded(null, project, "SELECT A#B, C FROM T#1 WHERE SERIAL#>0")).isEqualTo(Syntax.DEFAULT);
        assertThat(DialectDetector.syntaxOfEmbedded(null, project, null)).isEqualTo(Syntax.DEFAULT);
    }

    @Test
    @DisplayName("F-019-AC7 (마) --dialect oracle이면 본문에 #>>가 있어도 기본 읽기 방식이다(--dialect db2도 같다)")
    void embeddedWithOracleOption() {
        for (Dialect dialect : new Dialect[] {Dialect.ORACLE, Dialect.DB2}) {
            Syntax project = projectSyntax(AnalysisOptions.builder().dialect(dialect).build());
            assertThat(project).as(dialect.name()).isEqualTo(Syntax.DEFAULT);
            assertThat(DialectDetector.syntaxOfEmbedded(null, project, JSON_PATH)).as(dialect.name()).isEqualTo(Syntax.DEFAULT);
        }
    }

    @Test
    @DisplayName("F-019-AC7 스크립트에서 고른 방언이 PostgreSQL이면(여러 벌에서 고른 것, 또는 방언을 읽은 스크립트가 모두 PostgreSQL) 내장 소스의 프로젝트 방언이다")
    void embeddedByScriptDialect() {
        DialectSelection onlyPostgres = DialectSelection.defaults();
        onlyPostgres.registerFile("db/schema.sql", "postgres");
        onlyPostgres.registerFile("db/etc.sql", null);
        assertThat(onlyPostgres.declaredSyntax()).as("옵션·설정에는 표시가 없다").isNull();
        assertThat(onlyPostgres.projectSyntax()).isEqualTo(Syntax.POSTGRES);

        DialectSelection mixed = DialectSelection.defaults();
        mixed.registerFile("db/a.sql", "postgres");
        mixed.registerFile("db/b.sql", "oracle");
        assertThat(mixed.projectSyntax()).as("방언이 섞였고 여러 벌로 감지되지도 않았다").isNull();

        DialectSelection chosen = DialectSelection.defaults();
        chosen.detectedLoad("postgres");
        chosen.detectedLoad("mysql");
        assertThat(chosen.projectSyntax()).as("postgres·mysql 두 벌에서는 postgres를 고른다").isEqualTo(Syntax.POSTGRES);

        // Oracle·MySQL 스크립트로는 읽기 방식을 바꾸지 않는다(지금까지와 같이 본문 특징으로 정한다, F-019-R8)
        DialectSelection mysqlScripts = DialectSelection.defaults();
        mysqlScripts.registerFile("db/mysql/schema.sql", "mysql");
        assertThat(mysqlScripts.projectSyntax()).isNull();
        DialectSelection oracleChosen = DialectSelection.defaults();
        oracleChosen.detectedLoad("oracle");
        oracleChosen.detectedLoad("postgres");
        assertThat(oracleChosen.projectSyntax()).isNull();
    }

    @Test
    @DisplayName("F-019-AC7 MySQL 읽기 방식의 판정은 그대로다: attrs.dialect → --dialect(있으면 MySQL이 아니다) → mybatis.dialectVariant·프로젝트 설정 → 본문의 MySQL 특징")
    void embeddedMySqlDecisionIsKept() {
        assertThat(DialectDetector.syntaxOfEmbedded("mysql", null, PLAIN)).isEqualTo(Syntax.MYSQL);
        assertThat(DialectDetector.syntaxOfEmbedded("mariadb", Syntax.DEFAULT, PLAIN)).isEqualTo(Syntax.MYSQL);
        assertThat(DialectDetector.syntaxOfEmbedded("db2", Syntax.MYSQL, BACKTICK)).isEqualTo(Syntax.DEFAULT);
        assertThat(DialectDetector.syntaxOfEmbedded(null, null, BACKTICK)).isEqualTo(Syntax.MYSQL);
        assertThat(DialectDetector.syntaxOfEmbedded(null, Syntax.DEFAULT, BACKTICK)).as("--dialect oracle|db2").isEqualTo(Syntax.DEFAULT);
        assertThat(DialectDetector.syntaxOfEmbedded(null, Syntax.MYSQL, PLAIN)).isEqualTo(Syntax.MYSQL);
        // MySQL 특징이 PostgreSQL 특징보다 먼저다(내용 특징의 순서, F-019-R2)
        assertThat(DialectDetector.syntaxOfEmbedded(null, null, "SELECT `a` #>> '{x}' FROM `t`")).isEqualTo(Syntax.MYSQL);

        assertThat(projectSyntax(AnalysisOptions.builder().extra("mybatis.dialectVariant", "mysql").build())).isEqualTo(Syntax.MYSQL);
        assertThat(projectSyntax(null, "src/main/resources/globals.properties", "Globals.DbType=mariadb\n")).isEqualTo(Syntax.MYSQL);
        assertThat(projectSyntax(AnalysisOptions.builder().dialect(Dialect.ORACLE).extra("mybatis.dialectVariant", "mysql").build()))
                .as("--dialect가 있으면 MySQL이 아니다").isEqualTo(Syntax.DEFAULT);
        // Oracle·DB2를 가리키는 설정과 variant는 본문 특징을 막지 않는다(전과 같다): --dialect만 막는다
        assertThat(projectSyntax(null, "src/main/resources/globals.properties", "Globals.DbType=oracle\n")).isNull();
        assertThat(projectSyntax(AnalysisOptions.builder().extra("mybatis.dialectVariant", "oracle").build(),
                "src/main/resources/globals.properties", "Globals.DbType=mysql\n")).as("variant가 설정보다 먼저다").isNull();
    }

    // ------------------------------------------------------------------ SQL 파일

    @Test
    @DisplayName("F-019-AC7 SQL 파일의 읽기 방식: 경로의 방언 이름 → --dialect → 프로젝트 방언 → 본문 특징(MySQL, PostgreSQL) 순이다")
    void fileSyntax() {
        String pgDump = "ALTER TABLE ONLY public.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);\n";
        String plain = "CREATE TABLE T (ID INT);\n";
        String mysql = "CREATE TABLE `t` (`id` int) ENGINE=InnoDB;\n";
        // 경로의 방언 이름이 먼저다
        assertThat(DialectDetector.syntaxOfFile("postgres", null, plain, null)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfFile("postgres", Dialect.ORACLE, mysql, Syntax.MYSQL)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfFile("oracle", Dialect.POSTGRES, pgDump, Syntax.POSTGRES)).isEqualTo(Syntax.DEFAULT);
        assertThat(DialectDetector.syntaxOfFile("mysql", Dialect.POSTGRES, pgDump, Syntax.POSTGRES)).isEqualTo(Syntax.MYSQL);
        assertThat(DialectDetector.syntaxOfFile("mariadb", null, plain, null)).isEqualTo(Syntax.MYSQL);
        assertThat(DialectDetector.syntaxOfFile("hsql", null, mysql, Syntax.MYSQL)).isEqualTo(Syntax.DEFAULT);
        // 경로에 이름이 없으면 --dialect
        assertThat(DialectDetector.syntaxOfFile(null, Dialect.POSTGRES, plain, null)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfFile(null, Dialect.ORACLE, pgDump, Syntax.POSTGRES)).isEqualTo(Syntax.DEFAULT);
        assertThat(DialectDetector.syntaxOfFile(null, Dialect.DB2, mysql, Syntax.MYSQL)).isEqualTo(Syntax.DEFAULT);
        // 그다음 프로젝트 방언
        assertThat(DialectDetector.syntaxOfFile(null, null, plain, Syntax.POSTGRES)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfFile(null, null, plain, Syntax.MYSQL)).isEqualTo(Syntax.MYSQL);
        // 그다음 본문 특징
        assertThat(DialectDetector.syntaxOfFile(null, null, pgDump, null)).isEqualTo(Syntax.POSTGRES);
        assertThat(DialectDetector.syntaxOfFile(null, null, mysql, null)).isEqualTo(Syntax.MYSQL);
        assertThat(DialectDetector.syntaxOfFile(null, null, plain, null)).isEqualTo(Syntax.DEFAULT);
        assertThat(DialectDetector.syntaxOfFile(null, null, "CREATE TABLE T (ID NUMBER(10), NM VARCHAR2(20));", null)).isEqualTo(Syntax.DEFAULT);
        // HSQL로 판정한 본문은 PostgreSQL 특징(bytea)이 있어도 PostgreSQL로 읽지 않는다(내용 특징의 순서)
        assertThat(DialectDetector.syntaxOfFile(null, null, "CREATE MEMORY TABLE T (ID INT, V BYTEA);", null)).isEqualTo(Syntax.DEFAULT);
    }

    @Test
    @DisplayName("F-019-AC7 분석기는 파일마다 정한 읽기 방식을 루틴 핸들러까지 넘긴다: postgres 경로·내용 특징은 POSTGRES, mysql 경로는 MYSQL, 그 밖에는 기본이다")
    void analyzerPassesFileSyntaxToRoutineHandler() {
        String db2Routine = "CREATE PROCEDURE P_TOUCH (IN P_ID INTEGER)\nBEGIN\n  UPDATE T SET V = 'x' WHERE ID = P_ID;\nEND;\n";
        String pgRoutine = "CREATE OR REPLACE PROCEDURE p_touch(p_id integer)\nLANGUAGE plpgsql\nAS $$\nBEGIN\n"
                + "  UPDATE t SET v = 'x' WHERE id = p_id;\nEND;\n$$;\n";
        Map<String, Syntax> seen = new LinkedHashMap<>();
        RoutineUnitHandler capture = (unit, context) -> seen.put(context.file(), context.syntax());
        try (TestProject project = TestProject.create().file("db/postgres/proc.sql", db2Routine).file("db/pg_by_content.sql", pgRoutine)
                .file("db/mysql/proc.sql", db2Routine).file("db/plain/proc.sql", db2Routine).file("db/oracle/proc.sql", db2Routine)) {
            project.run(new SqlAnalyzer(capture));
        }
        assertThat(seen).containsEntry("db/postgres/proc.sql", Syntax.POSTGRES).containsEntry("db/pg_by_content.sql", Syntax.POSTGRES)
                .containsEntry("db/mysql/proc.sql", Syntax.MYSQL).containsEntry("db/plain/proc.sql", Syntax.DEFAULT)
                .containsEntry("db/oracle/proc.sql", Syntax.DEFAULT);

        // --dialect postgres면 방언 이름이 없는 파일도 PostgreSQL 읽기 방식이다. 경로에 다른 방언 이름이 있으면 그쪽이 먼저다
        seen.clear();
        try (TestProject project = TestProject.create().file("db/plain/proc.sql", db2Routine).file("db/oracle/proc.sql", db2Routine)
                .options(AnalysisOptions.builder().dialect(Dialect.POSTGRES).build())) {
            project.run(new SqlAnalyzer(capture));
        }
        assertThat(seen).containsEntry("db/plain/proc.sql", Syntax.POSTGRES).containsEntry("db/oracle/proc.sql", Syntax.DEFAULT);
    }
}
