package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.node;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 방언 기본값 정책(F-007-R23~R25). 이 도구의 대상 방언은 Oracle·DB2다: 프로젝트 설정이 대상 외 방언(전자정부 템플릿의
 * {@code Globals.DbType=mysql}/{@code hsql})을 가리켜도 대상 방언의 파일이 있으면 그쪽을 고르고, 그 사실을 요약 진단으로 알린다.
 */
class DialectPolicyTest {

    private static TestProject variants() {
        return TestProject.create()
                .file("db/oracle/ddl_oracle.sql", "CREATE TABLE TB_A (ID VARCHAR2(10) NOT NULL, ORA_COL NUMBER);\n")
                .file("db/altibase/ddl_altibase.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, ALT_COL INTEGER);\n")
                .file("db/mysql/ddl_mysql.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, MY_COL INT);\n");
    }

    private static List<Diagnostic> summary(GraphModel g) {
        return diagnostics(g, SqlDiagnosticCodes.DIALECT_VARIANT_DDL);
    }

    @Test
    @DisplayName("F-007-AC24 프로젝트 설정이 대상 외 방언(mysql)이어도 대상 방언(oracle) 파일이 있으면 oracle을 고르고, INFO 요약에 설정과 다르게 골랐다는 것과 설정대로 돌리는 방법을 적는다")
    void targetDialectBeatsForeignSetting() {
        try (TestProject project = variants().file("src/main/resources/globals.properties", "Globals.DbType = mysql\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/oracle/ddl_oracle.sql");
            assertThat(summary(g)).hasSize(1);
            Diagnostic d = summary(g).get(0);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("oracle 파일을 사용(테이블 1개)")
                    .contains("프로젝트 설정(src/main/resources/globals.properties:Globals.DbType=mysql)과 다르게 대상 방언 oracle 파일을 사용")
                    .contains("설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`").doesNotContain("부정확");
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_FOUND)).isEmpty();
        }
        // 설정이 대상 방언(db2)이면 Oracle보다 먼저다: db2 파일이 없으면 다음 순위(oracle)
        try (TestProject project = variants().file("db/db2/ddl_db2.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, DB2_COL INTEGER);\n")
                .file("src/main/resources/app.properties", "jdbc.type=db2\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/db2/ddl_db2.sql");
            assertThat(summary(g).get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(summary(g).get(0).message()).contains("프로젝트 설정(src/main/resources/app.properties:jdbc.type=db2)에 따라 db2 선택");
        }
        // 설정이 Oracle 계열(altibase)이면 그 계열 안에서 먼저지만 Oracle·DB2보다는 뒤다
        try (TestProject project = variants().file("db/tibero/ddl_tibero.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, TB_COL INTEGER);\n")
                .file("src/main/resources/app.properties", "Globals.DbType=altibase\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/oracle/ddl_oracle.sql");
        }
        // 명시한 요청은 그대로 가장 먼저다
        try (TestProject project = variants().file("src/main/resources/globals.properties", "Globals.DbType=mysql\n")
                .options(AnalysisOptions.builder().extra("mybatis.dialectVariant", "mysql").build())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/mysql/ddl_mysql.sql");
            assertThat(summary(g).get(0).severity()).isEqualTo(Severity.INFO);
        }
    }

    @Test
    @DisplayName("F-007-AC24 대상 방언 파일이 하나도 없을 때만 WARN이다: 설정이 가리키는 대상 외 방언이 그 밖의 방언보다 먼저고, 설정이 없으면 그 밖의 방언의 순서(이름의 사전순, F-007-R28)로 첫 방언")
    void foreignDialectOnlyWhenNoTargetFiles() {
        TestProject base = TestProject.create()
                .file("db/cubrid/ddl_cubrid.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, CUB_COL INT);\n")
                .file("db/mysql/ddl_mysql.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, MY_COL INT);\n");
        try (TestProject project = base.file("src/main/resources/globals.properties", "Globals.DbType=mysql\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/mysql/ddl_mysql.sql");
            Diagnostic d = summary(g).get(0);
            assertThat(d.severity()).isEqualTo(Severity.WARN);
            assertThat(d.message()).contains("프로젝트 설정(src/main/resources/globals.properties:Globals.DbType=mysql)에 따라 mysql 선택")
                    .contains("이 도구의 대상 방언(Oracle, DB2)의 파일이 없어").contains("부정확할 수 있음");
        }
        try (TestProject project = TestProject.create()
                .file("db/cubrid/ddl_cubrid.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, CUB_COL INT);\n")
                .file("db/mysql/ddl_mysql.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, MY_COL INT);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/cubrid/ddl_cubrid.sql");
            assertThat(summary(g).get(0).severity()).isEqualTo(Severity.WARN);
            assertThat(summary(g).get(0).message()).contains("cubrid 파일을 사용").contains("대상 방언(Oracle, DB2)의 파일이 없어");
        }
    }

    @Test
    @DisplayName("F-007-AC24 프로젝트 설정이 파일마다 엇갈리면 쓰지 않고, 요약 진단에 엇갈린 설정을 적는다")
    void conflictingSettingsAreReported() {
        try (TestProject project = variants().file("src/main/resources/a.properties", "Globals.DbType=mysql\n")
                .file("src/main/resources/b.properties", "Globals.DbType=altibase\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/oracle/ddl_oracle.sql");
            assertThat(summary(g).get(0).message())
                    .contains("프로젝트 설정이 엇갈려 쓰지 않음(src/main/resources/a.properties=mysql, src/main/resources/b.properties=altibase)");
        }
    }

    @Test
    @DisplayName("F-007-AC25 경로에 방언 이름이 없는 파일은 내용 특징으로 방언을 읽는다: CREATE MEMORY TABLE·SET DATABASE(HSQL), 백틱·ENGINE=(MySQL), VARCHAR2·NUMBER((Oracle), IDENTITY+IN 테이블스페이스(DB2)")
    void dialectFromContentFeatures() {
        // simple-backend의 배치: HSQL 스크립트(이름에 방언이 없다)와 Oracle DDL
        try (TestProject project = TestProject.create()
                .file("src/main/resources/db/shtdb.sql", "SET DATABASE UNIQUE NAME HSQLDB1\nCREATE MEMORY TABLE TB_A(ID VARCHAR(10) NOT NULL, HSQL_COL INTEGER)\n"
                        + "CREATE MEMORY TABLE TB_ONLY_HSQL(ID VARCHAR(10))\n")
                .file("script/ddl/all_ddl.sql", "CREATE TABLE TB_A (ID VARCHAR2(10) NOT NULL, ORA_COL NUMBER(3));\n")
                .file("src/main/resources/application.properties", "Globals.DbType=hsql\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("script/ddl/all_ddl.sql");
            Diagnostic d = summary(g).get(0);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("방언별 DDL 2벌 감지(oracle, hsql)").contains("다른 방언에서 보충 1개(hsql 1개)")
                    .contains("Globals.DbType=hsql)과 다르게 대상 방언 oracle 파일을 사용").doesNotContain("파일은 없어");
        }
        // MySQL 덤프와 DB2 스크립트
        try (TestProject project = TestProject.create()
                .file("db/a_dump.sql", "CREATE TABLE `TB_A` (`ID` int(11) NOT NULL AUTO_INCREMENT, `MY_COL` int) ENGINE=InnoDB;\n")
                .file("db/b_schema.sql", "CREATE TABLE TB_A (ID INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY, DB2_COL INTEGER) IN TS_DATA;\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/b_schema.sql");
            assertThat(summary(g).get(0).message()).contains("방언별 DDL 2벌 감지(db2, mysql)");
        }
    }

    @Test
    @DisplayName("F-007-AC26 방언별 여러 벌이 아닌 단일 방언 프로젝트라도 그 방언이 대상 외(MySQL 덤프뿐)면 INFO 1건으로 알린다. 대상 방언이면 없다")
    void singleForeignDialectProject() {
        try (TestProject project = TestProject.create()
                .file("db/schema.sql", "CREATE TABLE `tb_a` (`id` int(11) NOT NULL AUTO_INCREMENT, PRIMARY KEY (`id`)) ENGINE=InnoDB;\n")
                .file("db/data.sql", "INSERT INTO `tb_a` (`id`) VALUES (1);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            List<Diagnostic> found = diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_TARGET);
            assertThat(found).hasSize(1);
            assertThat(found.get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(found.get(0).message()).contains("이 프로젝트의 SQL은 mysql로 보임").contains("대상 방언이 아니어서 일부 구문을 해석하지 못할 수 있음");
            assertThat(summary(g)).isEmpty();
        }
        try (TestProject project = TestProject.create()
                .file("db/schema.sql", "CREATE TABLE TB_A (ID VARCHAR2(10) NOT NULL, N NUMBER(3));\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(diagnostics(g, SqlDiagnosticCodes.DIALECT_NOT_TARGET)).isEmpty();
            assertThat(g.diagnostics()).isEmpty();
        }
    }

    private static String inserts(String table, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("INSERT INTO ").append(table).append(" (ID, NM) VALUES ('").append(i).append("', 'n');\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("F-007-AC28 고르지 않은 방언의 파일에서는 적재 묶음과 DML 구문을 만들지 않는다: 그 방언 파일에만 있는 테이블(comment 스크립트의 유령 테이블)도, DDL 파일에 섞인 INSERT도")
    void unselectedDialectFilesProduceNoStatements() {
        try (TestProject project = variants()
                // 선택되지 않은 방언(cubrid)의 comment 스크립트: 그 방언에만 있는 테이블에 넣는 적재 묶음
                .file("script/comment/cubrid/comment_cubrid.sql", inserts("_CUB_SCHEMA_COMMENTS", 12))
                .file("script/comment/oracle/comment_oracle.sql", "COMMENT ON TABLE TB_A IS 'a';\n")
                // 방언별 생성 스크립트에 섞인 INSERT(Activiti의 ACT_GE_PROPERTY): 고른 방언 파일의 것만 남는다
                .file("db/act.oracle.create.sql", "CREATE TABLE ACT_GE_PROPERTY (NAME_ NVARCHAR2(64), VALUE_ NVARCHAR2(300));\n"
                        + "INSERT INTO ACT_GE_PROPERTY VALUES ('schema.version', '5.21');\n")
                .file("db/act.mysql.create.sql", "CREATE TABLE ACT_GE_PROPERTY (NAME_ varchar(64), VALUE_ varchar(300));\n"
                        + "INSERT INTO ACT_GE_PROPERTY VALUES ('schema.version', '5.21');\n")
                .file("db/act.db2.create.sql", "CREATE TABLE ACT_GE_PROPERTY (NAME_ VARCHAR(64), VALUE_ VARCHAR(300));\n"
                        + "INSERT INTO ACT_GE_PROPERTY VALUES ('schema.version', '5.21');\n")
                // 방언 이름이 없는 파일과 고른 방언의 파일은 그대로다
                .file("data/data_oracle.sql", inserts("TB_A", 12))
                .file("data/common.sql", inserts("TB_COMMON", 12))) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(g.hasNode("db:table:_CUB_SCHEMA_COMMENTS")).as("유령 테이블").isFalse();
            List<String> statements = g.nodes(io.codeatlas.core.model.NodeKind.SQL_STATEMENT).stream().map(n -> n.id()).sorted().toList();
            assertThat(statements).containsExactly("sql:stmt:data/common.sql:1", "sql:stmt:data/data_oracle.sql:1", "sql:stmt:db/act.oracle.create.sql:2");
            assertThat(summary(g).get(0).message()).contains("oracle 파일을 사용");
        }
    }

    @Test
    @DisplayName("F-007-AC28 방언별로 한 벌을 고른 중복 정의에는 DUPLICATE_OBJECT_NAME(INFO)의 '한 벌만 지정 권장'을 내지 않는다. 방언 이름이 없는 진짜 중복에만 남는다")
    void noDuplicateHintForDialectVariants() {
        try (TestProject project = variants()
                .file("backup/ddl_a.sql", "CREATE TABLE TB_DUP (ID VARCHAR(10));\n")
                .file("backup/ddl_b.sql", "CREATE TABLE TB_DUP (ID VARCHAR(10), X INT);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            List<Diagnostic> duplicates = diagnostics(g, io.codeatlas.core.model.DiagnosticCodes.DUPLICATE_OBJECT_NAME);
            assertThat(duplicates).hasSize(1);
            assertThat(duplicates.get(0).message()).contains("backup/ddl_a.sql").contains("backup/ddl_b.sql").contains("한 벌만 지정 권장");
            assertThat(summary(g)).hasSize(1);
        }
    }

    private static String mapper(String table) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n<mapper namespace=\"aDAO\">\n"
                + "  <select id=\"find\" resultType=\"map\">SELECT ID FROM " + table + "</select>\n</mapper>\n";
    }

    @Test
    @DisplayName("F-007-AC29 대상 방언 파일도 프로젝트 설정도 없으면 그 밖의 방언끼리는 경로순이 아니라 이름의 사전순이다: mysql·postgres 두 벌이면 postgres 파일이 경로상 먼저여도 mysql(mybatis 모듈 F-009-AC47과 같은 방언). 적재 스크립트도 같다")
    void otherDialectsByName() {
        try (TestProject project = TestProject.create()
                .file("db/a_postgres.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, PG_COL INT);\n")
                .file("db/b_mysql.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, MY_COL INT);\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/b_mysql.sql");
            Diagnostic d = summary(g).get(0);
            assertThat(d.severity()).isEqualTo(Severity.WARN);
            assertThat(d.message()).contains("방언별 DDL 2벌 감지(mysql, postgres): mysql 파일을 사용(테이블 1개)").contains("대상 방언(Oracle, DB2)의 파일이 없어");
        }
        // 정의 없이 데이터 적재 스크립트만 방언별 두 벌일 때도 같은 순서다
        try (TestProject project = TestProject.create()
                .file("data/a_postgres.sql", inserts("TB_A", 12))
                .file("data/b_mysql.sql", inserts("TB_A", 12))) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(summary(g).get(0).message()).contains("방언별 데이터 적재 스크립트 2벌 감지(mysql, postgres): mysql 파일을 사용");
            assertThat(g.nodes(io.codeatlas.core.model.NodeKind.SQL_STATEMENT).stream().map(n -> n.id()).toList())
                    .containsExactly("sql:stmt:data/b_mysql.sql:1");
        }
    }

    @Test
    @DisplayName("F-007-AC29 매퍼와 SQL 스크립트 양쪽의 경로에 다 있는 그 밖의 방언이 한쪽에만 있는 방언보다 먼저다: 스크립트 cubrid·mysql·postgres에 매퍼 mysql·postgres면 mysql, 매퍼가 없으면 cubrid. 앞 단계(프로젝트 설정의 대상 외 방언)는 그대로 먼저다")
    void otherDialectsOnBothSidesFirst() {
        try (TestProject project = TestProject.create()
                .file("db/cubrid/ddl_cubrid.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, CUB_COL INT);\n")
                .file("db/mysql/ddl_mysql.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, MY_COL INT);\n")
                .file("db/postgres/ddl_postgres.sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, PG_COL INT);\n")) {
            // 스크립트뿐이면 이름의 사전순 첫 방언
            assertThat(node(project.run(new SqlAnalyzer()).graph(), "db:table:TB_A").location().file()).isEqualTo("db/cubrid/ddl_cubrid.sql");

            // 매퍼 쪽에도 있는 방언(mysql, postgres)이 먼저고 그 안에서 사전순
            project.file("mapper/A_SQL_postgres.xml", mapper("TB_A")).file("mapper/A_SQL_mysql.xml", mapper("TB_A"));
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "db:table:TB_A").location().file()).isEqualTo("db/mysql/ddl_mysql.sql");
            assertThat(summary(g).get(0).message()).contains("방언별 DDL 3벌 감지(mysql, postgres, cubrid): mysql 파일을 사용(테이블 1개)");

            // 프로젝트 설정이 가리키는 대상 외 방언은 전처럼 그보다 먼저다
            project.file("src/main/resources/globals.properties", "Globals.DbType=postgres\n");
            assertThat(node(project.run(new SqlAnalyzer()).graph(), "db:table:TB_A").location().file()).isEqualTo("db/postgres/ddl_postgres.sql");
        }
    }

    @Test
    @DisplayName("F-007-AC29 방언을 읽지 못한 파일은 전처럼 처음 물은 순서로 선다: 그 밖의 방언 파일보다 경로상 먼저면 그 앞, 뒤면 그 뒤")
    void unknownDialectFileKeepsPathOrder() {
        String plain = "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, PLAIN_COL INT);\n";
        String mysql = "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, MY_COL INT);\n";
        String postgres = "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, PG_COL INT);\n";
        try (TestProject project = TestProject.create().file("db/a_common.sql", plain)
                .file("db/b_postgres.sql", postgres).file("db/c_mysql.sql", mysql)) {
            assertThat(node(project.run(new SqlAnalyzer()).graph(), "db:table:TB_A").location().file()).isEqualTo("db/a_common.sql");
        }
        try (TestProject project = TestProject.create().file("db/z_common.sql", plain)
                .file("db/b_postgres.sql", postgres).file("db/c_mysql.sql", mysql)) {
            assertThat(node(project.run(new SqlAnalyzer()).graph(), "db:table:TB_A").location().file()).isEqualTo("db/c_mysql.sql");
        }
    }
}
