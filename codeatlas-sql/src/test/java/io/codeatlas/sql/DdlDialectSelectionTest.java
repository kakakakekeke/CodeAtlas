package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 방언별 DDL 여러 벌 가운데 한 방언을 프로젝트 전체로 일관되게 고른다(F-007-R18, F-007-R19).
 * 공개 프로젝트(jeesite)에서 Activiti 테이블은 DB2 파일({@code activiti.db2.create.*.sql}), 업무 테이블은 Oracle 파일의 정의가 쓰였고,
 * {@code --dialect db2}인데 DB2 파일이 없으면 안내 없이 경로순 첫 파일(altibase)이 쓰였으며, {@code mybatis.dialectVariant=mysql}이면
 * 매퍼만 MySQL이고 DDL은 Oracle이었다.
 */
class DdlDialectSelectionTest {

    /** jeesite의 배치: Activiti 테이블은 방언 6벌 가운데 일부, 업무 테이블은 Oracle·MySQL 파일. */
    private static TestProject jeesiteLike() {
        return TestProject.create()
                .file("db/act/activiti.db2.create.engine.sql", "CREATE TABLE ACT_X (ID_ VARCHAR(64) NOT NULL, DB2_COL INTEGER, PRIMARY KEY (ID_));\n")
                .file("db/act/activiti.mysql55.create.engine.sql", "CREATE TABLE ACT_X (ID_ varchar(64), MYSQL_COL integer, primary key (ID_));\n")
                .file("db/act/activiti.oracle.create.engine.sql", "CREATE TABLE ACT_X (ID_ NVARCHAR2(64) NOT NULL, ORA_COL INTEGER, PRIMARY KEY (ID_));\n")
                .file("db/sys/jeesite_mysql.sql", "CREATE TABLE SYS_USER (ID varchar(64) NOT NULL, MYSQL_COL int);\n")
                .file("db/sys/jeesite_oracle.sql", "CREATE TABLE SYS_USER (ID VARCHAR2(64) NOT NULL, ORA_COL NUMBER);\n");
    }

    private static List<Diagnostic> summary(GraphModel g) {
        return diagnostics(g, SqlDiagnosticCodes.DIALECT_VARIANT_DDL);
    }

    @Test
    @DisplayName("F-007-AC18 Oracle과 DB2 파일이 같이 있으면 경로순이 아니라 Oracle이 먼저다: 모든 테이블이 한 방언(oracle)의 정의를 쓴다")
    void oracleBeforeDb2() {
        try (TestProject project = jeesiteLike()) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.oracle.create.engine.sql");
            assertThat(targets(g, "db:table:ACT_X", EdgeKind.CONTAINS)).containsExactly("db:column:ACT_X.ID_", "db:column:ACT_X.ORA_COL");
            assertThat(node(g, "db:table:SYS_USER").location().file()).isEqualTo("db/sys/jeesite_oracle.sql");
            List<Diagnostic> info = summary(g);
            assertThat(info).hasSize(1);
            assertThat(info.get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(info.get(0).location()).isNull();
            assertThat(info.get(0).message()).contains("방언별 DDL 3벌 감지").contains("oracle, db2, mysql").contains("oracle 파일을 사용(테이블 2개)")
                    .contains("다른 방언에서 보충 0개").contains("제외한 파일 3개").contains("--dialect");
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-007-AC18 --dialect db2면 DB2 파일의 정의를 먼저 쓰고, DB2 파일에 정의가 없는 테이블만 다음 순위(oracle)에서 가져온다")
    void requestedDialectThenNextRank() {
        try (TestProject project = jeesiteLike().options(AnalysisOptions.builder().dialect(Dialect.DB2).build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.db2.create.engine.sql");
            assertThat(node(g, "db:table:SYS_USER").location().file()).isEqualTo("db/sys/jeesite_oracle.sql");
            assertThat(summary(g)).hasSize(1);
            assertThat(summary(g).get(0).message()).contains("db2 파일을 사용(테이블 1개)").contains("다른 방언에서 보충 1개(oracle 1개)").contains("제외한 파일 3개");
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-007-AC18 mybatis.dialectVariant=<방언>이면 DDL도 그 방언을 쓴다. --dialect가 같이 있으면 DDL은 --dialect가 먼저다. auto·all은 건너뛴다")
    void mapperVariantOptionAppliesToDdl() {
        try (TestProject project = jeesiteLike().options(AnalysisOptions.builder().extra("mybatis.dialectVariant", "MySQL").build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.mysql55.create.engine.sql");
            assertThat(node(g, "db:table:SYS_USER").location().file()).isEqualTo("db/sys/jeesite_mysql.sql");
            assertThat(summary(g).get(0).message()).contains("mysql 파일을 사용(테이블 2개)");
        }
        try (TestProject project = jeesiteLike()
                .options(AnalysisOptions.builder().dialect(Dialect.ORACLE).extra("mybatis.dialectVariant", "mysql").build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.oracle.create.engine.sql");
            assertThat(node(g, "db:table:SYS_USER").location().file()).isEqualTo("db/sys/jeesite_oracle.sql");
        }
        for (String value : List.of("auto", "all", "nosuchdb")) {
            try (TestProject project = jeesiteLike().options(AnalysisOptions.builder().extra("mybatis.dialectVariant", value).build())) {
                GraphModel g = project.run(new SqlAnalyzer()).graph();
                assertThat(node(g, "db:table:ACT_X").location().file()).as(value).isEqualTo("db/act/activiti.oracle.create.engine.sql");
                assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND)).as(value).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("F-007-AC19 요청한 방언(--dialect, mybatis.dialectVariant)의 DDL 파일이 하나도 없으면 WARN DIALECT_NOT_FOUND에 무엇으로 대체했는지 적는다")
    void requestedDialectNotFound() {
        TestProject base = TestProject.create()
                .file("DATABASE/altibase/all_ddl_altibase.sql", "CREATE TABLE T_USR (ID VARCHAR(20), ALTI_COL INTEGER);\n")
                .file("DATABASE/mysql/all_ddl_mysql.sql", "CREATE TABLE T_USR (ID varchar(20), MYSQL_COL int);\n")
                .file("DATABASE/oracle/all_ddl_oracle.sql", "CREATE TABLE T_USR (ID VARCHAR2(20), ORA_COL NUMBER);\n");
        try (TestProject project = base.options(AnalysisOptions.builder().dialect(Dialect.DB2).build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            // 전에는 안내 없이 경로순 첫 파일(altibase)이었다
            assertThat(node(g, "db:table:T_USR").location().file()).isEqualTo("DATABASE/oracle/all_ddl_oracle.sql");
            List<Diagnostic> warn = diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND);
            assertThat(warn).hasSize(1);
            assertThat(warn.get(0).severity()).isEqualTo(Severity.WARN);
            assertThat(warn.get(0).message()).contains("db2").contains("--dialect").contains("DDL").contains("oracle 파일을 사용")
                    .contains("oracle, altibase, mysql");
            assertThat(summary(g).get(0).message()).contains("oracle 파일을 사용(테이블 1개)");
        }
        try (TestProject project = TestProject.create()
                .file("DATABASE/altibase/all_ddl_altibase.sql", "CREATE TABLE T_USR (ID VARCHAR(20), ALTI_COL INTEGER);\n")
                .file("DATABASE/mysql/all_ddl_mysql.sql", "CREATE TABLE T_USR (ID varchar(20), MYSQL_COL int);\n")
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).extra("mybatis.dialectVariant", "cubrid").build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            // 요청한 두 방언이 다 없다: Oracle 계열(altibase)로 대체한다
            assertThat(node(g, "db:table:T_USR").location().file()).isEqualTo("DATABASE/altibase/all_ddl_altibase.sql");
            List<Diagnostic> warn = diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND);
            assertThat(warn).hasSize(1);
            assertThat(warn.get(0).message()).contains("db2(`--dialect`)").contains("cubrid(`mybatis.dialectVariant`)").contains("altibase 파일을 사용");
        }
        // 방언별 DDL이 여러 벌이 아니면(고를 것이 없으면) 요청한 방언의 파일이 없어도 경고하지 않는다
        try (TestProject project = TestProject.create().file("db/schema.sql", "CREATE TABLE T_USR (ID VARCHAR2(20));\n")
                .file("db/mysql/extra.sql", "CREATE TABLE T_ETC (ID int);\n")
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-007-AC18 F-019-AC4 Oracle·DB2가 없으면 PostgreSQL, 그다음 Oracle 계열(Tibero, Altibase)이 먼저고, 그것도 없으면 그 밖의 방언의 순서(이름의 사전순, F-007-R28)로 첫 방언을 프로젝트 전체에 먼저 쓴다")
    void oracleFamilyThenPathOrder() {
        // PostgreSQL은 대상 방언이라 Oracle 계열보다 먼저다(F-019-R4. 전에는 tibero를 골랐다)
        try (TestProject project = TestProject.create()
                .file("ddl/mysql/a.sql", "CREATE TABLE T_A (ID int, MYSQL_COL int);\n")
                .file("ddl/postgres/a.sql", "CREATE TABLE T_A (ID int, PG_COL int);\n")
                .file("ddl/tibero/a.sql", "CREATE TABLE T_A (ID NUMBER, TIBERO_COL NUMBER);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:T_A").location().file()).isEqualTo("ddl/postgres/a.sql");
            assertThat(summary(g).get(0).message()).contains("postgres 파일을 사용(테이블 1개)");
        }
        // 대상 방언이 없으면 Oracle 계열이 그 밖의 방언보다 먼저다
        try (TestProject project = TestProject.create()
                .file("ddl/mysql/a.sql", "CREATE TABLE T_A (ID int, MYSQL_COL int);\n")
                .file("ddl/tibero/a.sql", "CREATE TABLE T_A (ID NUMBER, TIBERO_COL NUMBER);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:T_A").location().file()).isEqualTo("ddl/tibero/a.sql");
            assertThat(summary(g).get(0).message()).contains("tibero 파일을 사용(테이블 1개)");
        }
        // 대상 방언 postgres가 프로젝트의 방언이다(F-019-R4. 전에는 그 밖의 방언의 사전순으로 mysql이었다).
        // T_A는 mysql 파일이 경로상 먼저지만(a_mysql.sql) postgres 것을 쓰고, T_B·T_C도 postgres 파일에 정의가 있으므로 다른 방언에서 가져오지 않는다
        try (TestProject project = TestProject.create()
                .file("ddl/a_mysql.sql", "CREATE TABLE T_A (ID int, MYSQL_COL int);\n")
                .file("ddl/a_postgres.sql", "CREATE TABLE T_A (ID int, PG_COL int);\n")
                .file("ddl/b/aa_postgres.sql", "CREATE TABLE T_B (ID int, PG_COL int);\nCREATE TABLE T_C (ID int, PG_COL int);\n")
                .file("ddl/b/zz_mysql.sql", "CREATE TABLE T_B (ID int, MYSQL_COL int);\n")
                .file("ddl/b/zz_sqlite.sql", "CREATE TABLE T_C (ID int, SQLITE_COL int);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:T_A").location().file()).isEqualTo("ddl/a_postgres.sql");
            assertThat(node(g, "db:table:T_B").location().file()).isEqualTo("ddl/b/aa_postgres.sql");
            assertThat(node(g, "db:table:T_C").location().file()).isEqualTo("ddl/b/aa_postgres.sql");
            assertThat(summary(g).get(0).message()).contains("방언별 DDL 3벌 감지").contains("postgres 파일을 사용(테이블 3개)")
                    .contains("다른 방언에서 보충 0개").contains("제외한 파일 3개");
        }
        // 선호 방언이 하나도 없다: 그 밖의 방언의 순서(이름의 사전순, F-007-R28)가 가장 앞인 mysql이 프로젝트의 방언이다.
        // T_B는 sybase 파일이 경로상 먼저지만(aa_sybase.sql) mysql 파일에 정의가 있으므로 mysql 것을 쓰고, mysql에 없는 T_C만 다음 순위(sqlite)에서 가져온다
        try (TestProject project = TestProject.create()
                .file("ddl/a_mysql.sql", "CREATE TABLE T_A (ID int, MYSQL_COL int);\n")
                .file("ddl/a_sybase.sql", "CREATE TABLE T_A (ID int, SYB_COL int);\n")
                .file("ddl/b/aa_sybase.sql", "CREATE TABLE T_B (ID int, SYB_COL int);\nCREATE TABLE T_C (ID int, SYB_COL int);\n")
                .file("ddl/b/zz_mysql.sql", "CREATE TABLE T_B (ID int, MYSQL_COL int);\n")
                .file("ddl/b/zz_sqlite.sql", "CREATE TABLE T_C (ID int, SQLITE_COL int);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:T_A").location().file()).isEqualTo("ddl/a_mysql.sql");
            assertThat(node(g, "db:table:T_B").location().file()).isEqualTo("ddl/b/zz_mysql.sql");
            assertThat(node(g, "db:table:T_C").location().file()).isEqualTo("ddl/b/zz_sqlite.sql");
            assertThat(summary(g).get(0).message()).contains("방언별 DDL 3벌 감지").contains("mysql 파일을 사용(테이블 2개)")
                    .contains("다른 방언에서 보충 1개(sqlite 1개)").contains("제외한 파일 2개");
        }
    }

    @Test
    @DisplayName("F-007-AC19 방언이 다른 중복 정의가 없으면(같은 방언·방언 없는 파일끼리의 중복) DIALECT_VARIANT_DDL을 남기지 않는다")
    void noSummaryWithoutDialectVariants() {
        try (TestProject project = TestProject.create()
                .file("db/install/schema.sql", "CREATE TABLE T_A (ID NUMBER);\n")
                .file("db/dump/full.sql", "CREATE TABLE T_A (ID NUMBER, X NUMBER);\n")
                .file("db/oracle/only.sql", "CREATE TABLE T_B (ID NUMBER);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(summary(g)).isEmpty();
            assertThat(diagnostics(g, DiagnosticCodes.DUPLICATE_OBJECT_NAME)).hasSize(1);
            assertThat(g.diagnostics()).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-007-AC21 F-007-AC24 프로젝트 설정(jdbc.type 등)에서 읽은 방언: 대상 방언(oracle, db2)이면 Oracle보다 먼저고, 대상 외 방언이면 대상 방언 파일이 있는 한 그쪽을 고르고 INFO로 알린다")
    void projectSettingDialect() {
        // 설정은 mysql이지만 대상 방언(oracle) 파일이 있다(F-007-R23. 전에는 설정대로 mysql을 골랐다)
        try (TestProject project = jeesiteLike().file("src/main/resources/jeesite.properties", "#jdbc.type=oracle\njdbc.type=mysql\n#jdbc.type=mssql\n")
                // 테스트 소스의 설정은 보지 않는다
                .file("src/test/resources/test.properties", "jdbc.type=db2\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.oracle.create.engine.sql");
            assertThat(node(g, "db:table:SYS_USER").location().file()).isEqualTo("db/sys/jeesite_oracle.sql");
            Diagnostic d = summary(g).get(0);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("oracle 파일을 사용(테이블 2개)")
                    .contains("프로젝트 설정(src/main/resources/jeesite.properties:jdbc.type=mysql)과 다르게 대상 방언 oracle 파일을 사용")
                    .contains("설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`");
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND)).isEmpty();
        }
        // 명시한 옵션이 설정보다 먼저다
        try (TestProject project = jeesiteLike().file("src/main/resources/jeesite.properties", "jdbc.type=mysql\n")
                .options(AnalysisOptions.builder().dialect(Dialect.ORACLE).build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.oracle.create.engine.sql");
            assertThat(summary(g).get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(g).get(0).message()).doesNotContain("프로젝트 설정");
        }
        // 대상 방언(DB2)을 설정에서 읽었으면 INFO다. YAML의 드라이버 클래스에서도 읽는다
        try (TestProject project = jeesiteLike().file("src/main/resources/application.yml",
                "spring:\n  datasource:\n    driver-class-name: com.ibm.db2.jcc.DB2Driver # 운영\n    url: ${DB_URL}\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.db2.create.engine.sql");
            assertThat(node(g, "db:table:SYS_USER").location().file()).isEqualTo("db/sys/jeesite_oracle.sql");
            Diagnostic d = summary(g).get(0);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("프로젝트 설정(src/main/resources/application.yml:spring.datasource.driver-class-name=com.ibm.db2.jcc.DB2Driver)에 따라 db2 선택")
                    .doesNotContain("대상 방언이 아니어서");
        }
        // 설정의 방언(hsql) 파일이 없으면 다음 순위(Oracle)로 가고, 경고 없이 요약에 그 사실만 적는다
        try (TestProject project = jeesiteLike().file("src/main/resources/application.properties", "Globals.DbType=hsql\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.oracle.create.engine.sql");
            assertThat(summary(g).get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(g).get(0).message())
                    .contains("프로젝트 설정(src/main/resources/application.properties:Globals.DbType=hsql)과 다르게 대상 방언 oracle 파일을 사용(hsql 스크립트 파일은 없음)");
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND)).isEmpty();
        }
        // 설정 파일마다 값이 다르면 쓰지 않는다(기본 순위: Oracle)
        try (TestProject project = jeesiteLike().file("a/src/main/resources/globals.properties", "Globals.DbType = mysql\n")
                .file("b/src/main/resources/application.properties", "spring.datasource.url=jdbc:log4jdbc:db2://host:50000/SHOP\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:ACT_X").location().file()).isEqualTo("db/act/activiti.oracle.create.engine.sql");
            assertThat(summary(g).get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(g).get(0).message()).contains("프로젝트 설정이 엇갈려 쓰지 않음(a/src/main/resources/globals.properties=mysql, "
                    + "b/src/main/resources/application.properties=db2)");
        }
    }

    @Test
    @DisplayName("F-007-AC23 같은 테이블의 데이터 적재 묶음이 서로 다른 방언의 파일에 있으면 DDL과 같은 순위로 한 벌만 남긴다. 묶음을 모두 버린 파일은 제외한 파일이다")
    void loadScriptsOfOneDialect() {
        String rows = rows("T_CODE", 12) + rows("T_MENU", 3);
        try (TestProject project = TestProject.create()
                .file("script/dml/altibase/data_altibase.sql", "DELETE FROM T_CODE;\n" + rows)
                .file("script/dml/mysql/data_mysql.sql", "DELETE FROM T_CODE;\n" + rows + rows("T_MYSQL_ONLY", 2))
                .file("script/dml/oracle/data_oracle.sql", "DELETE FROM T_CODE;\n" + rows)
                .file("script/dml/common_data.sql", rows("T_CODE", 10))) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            // oracle 파일의 묶음 둘과 초기화 구문, 방언 이름이 없는 파일의 묶음. 고르지 않은 방언(mysql)의 파일은 그 파일에만 있는
            // 테이블(T_MYSQL_ONLY)의 묶음과 초기화 구문도 만들지 않는다(F-007-R27. 전에는 남겼다)
            assertThat(g.nodes(io.codeatlas.core.model.NodeKind.SQL_STATEMENT)).extracting(io.codeatlas.core.model.Node::id).containsExactlyInAnyOrder(
                    "sql:stmt:script/dml/oracle/data_oracle.sql:1", "sql:stmt:script/dml/oracle/data_oracle.sql:2",
                    "sql:stmt:script/dml/oracle/data_oracle.sql:14", "sql:stmt:script/dml/common_data.sql:1");
            assertThat(g.hasNode("db:table:T_MYSQL_ONLY")).isFalse();
            assertThat(g.incoming("db:table:T_MENU", EdgeKind.WRITES)).extracting(e -> e.from())
                    .containsExactly("sql:stmt:script/dml/oracle/data_oracle.sql:14");
            Diagnostic d = summary(g).get(0);
            assertThat(summary(g)).hasSize(1);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("방언별 데이터 적재 스크립트 3벌 감지(oracle, altibase, mysql)").contains("oracle 파일을 사용")
                    .contains("제외한 파일 2개").contains("묶음 5개를 만들지 않음");
            assertThat(diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED)).extracting(x -> x.location().file()).containsExactlyInAnyOrder(
                    "script/dml/oracle/data_oracle.sql", "script/dml/common_data.sql");
            // 묶음을 모두 버린 파일에 "읽은 것이 없음" 경고를 내지 않는다
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).isEmpty();
            assertThat(g.diagnostics()).hasSize(3);
        }
    }

    private static String rows(String table, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("INSERT INTO ").append(table).append(" (CD, NM) VALUES ('").append(i).append("', 'x');\n");
        }
        return sb.toString();
    }
}
