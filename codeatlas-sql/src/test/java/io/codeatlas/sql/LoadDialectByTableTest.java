package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.node;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 방언별 스크립트가 여러 벌인 프로젝트에서 데이터 적재 묶음을 고르는 단위는 파일이 아니라 테이블이다(F-007-R29).
 * 4차 수정(F-007-R27)이 "고르지 않은 방언의 파일"의 묶음을 통째로 버려서, 고른 방언(oracle)의 파일에 적재가 없는 테이블의 초기 데이터
 * (jeesite의 {@code db/jeesite_mysql.sql}, simple-backend의 {@code db/shtdb.sql}, 공통컴포넌트의 HSQL 테스트 픽스처)가 사라졌다.
 */
class LoadDialectByTableTest {

    private static final String ORACLE_DDL = "CREATE TABLE T_A\n(\n\tID  VARCHAR2(10)  NOT NULL ,\n\tNM  VARCHAR2(60)  NULL ,\nCONSTRAINT  T_A_PK PRIMARY KEY (ID)\n);\n";
    private static final String MYSQL_DDL = "CREATE TABLE T_A\n(\n\tID  VARCHAR(10)  NOT NULL ,\n\tNM  VARCHAR(60)  NULL ,\n\tPRIMARY KEY (ID)\n) ENGINE=InnoDB;\n";

    private static String inserts(String table, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("INSERT INTO ").append(table).append(" (ID, NM) VALUES ('").append(i).append("', 'n');\n");
        }
        return sb.toString();
    }

    /** 공통컴포넌트의 HSQL 테스트 픽스처 꼴: 종결자 없는 CREATE MEMORY TABLE과 INSERT. */
    private static String hsqlFixture() {
        StringBuilder sb = new StringBuilder("-- Base Tables\nCREATE MEMORY TABLE SAMPLE(ID VARCHAR(16) NOT NULL PRIMARY KEY,NAME VARCHAR(50))\n"
                + "CREATE MEMORY TABLE USERS(USER_ID VARCHAR(30) NOT NULL, USER_PASSWORD VARCHAR(100) NOT NULL)\n\nSET SCHEMA PUBLIC\n");
        for (int i = 1; i <= 12; i++) {
            sb.append("INSERT INTO SAMPLE VALUES('S-").append(i).append("','n").append(i).append("')\n");
        }
        return sb.append("INSERT INTO USERS VALUES('admin', '1')\nINSERT INTO USERS VALUES('user', '1')\n").toString();
    }

    private static List<String> statements(GraphModel g) {
        return g.nodes(NodeKind.SQL_STATEMENT).stream().map(Node::id).sorted().toList();
    }

    private static Diagnostic summary(GraphModel g) {
        List<Diagnostic> found = diagnostics(g, SqlDiagnosticCodes.DIALECT_VARIANT_DDL);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    @DisplayName("F-007-AC30 같은 방언(hsql)의 테스트 픽스처 둘이 같은 테이블을 정의·적재해도 두 파일의 묶음이 다 남는다: 정의가 쓰이지 않은 쪽 파일의 묶음도 버리지 않고 borrowedFrom=hsql로 표시한다")
    void sameDialectFixturesBothKept() {
        try (TestProject project = TestProject.create()
                .file("script/ddl/oracle/a_DDL_oracle.sql", ORACLE_DDL)
                .file("script/ddl/mysql/a_DDL_mysql.sql", MYSQL_DDL)
                .file("src/test/resources/sec/testdb.sql", hsqlFixture())
                .file("src/test/resources/sess/testdb.sql", hsqlFixture())) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(statements(g)).containsExactly(
                    "sql:stmt:src/test/resources/sec/testdb.sql:18", "sql:stmt:src/test/resources/sec/testdb.sql:6",
                    "sql:stmt:src/test/resources/sess/testdb.sql:18", "sql:stmt:src/test/resources/sess/testdb.sql:6");
            for (String id : statements(g)) {
                assertThat(node(g, id).attr("dataLoad")).as(id).isEqualTo(true);
                assertThat(node(g, id).attrString("borrowedFrom")).as(id).isEqualTo("hsql");
            }
            assertThat(node(g, "sql:stmt:src/test/resources/sess/testdb.sql:6").attr("rowCount")).isEqualTo(12);
            assertThat(diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED)).extracting(d -> d.location().file())
                    .containsExactlyInAnyOrder("src/test/resources/sec/testdb.sql", "src/test/resources/sess/testdb.sql");
            assertThat(summary(g).message()).doesNotContain("만들지 않음")
                    .contains("oracle 파일에 적재가 없는 테이블의 묶음 4개는 다른 방언 파일의 것을 씀(hsql 4개)");
        }
    }

    @Test
    @DisplayName("F-007-AC30 고른 방언(oracle)의 파일에 적재가 없는 테이블은 다른 방언 파일(이름에 방언이 없는 HSQL 초기화 스크립트)의 묶음이 남고 borrowedFrom이 붙는다")
    void borrowedWhenSelectedDialectHasNoLoad() {
        // simple-backend의 배치: 앱이 실행하는 HSQL 초기화 스크립트(shtdb.sql)와 Oracle DDL. Oracle용 데이터 스크립트는 없다
        try (TestProject project = TestProject.create()
                .file("src/main/resources/db/shtdb.sql", "SET DATABASE UNIQUE NAME HSQLDB1\nCREATE MEMORY TABLE T_A(ID VARCHAR(10) NOT NULL, NM VARCHAR(60))\n"
                        + inserts("T_A", 12).replace(";", ""))
                .file("script/ddl/all_ddl.sql", ORACLE_DDL)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(node(g, "db:table:T_A").location().file()).isEqualTo("script/ddl/all_ddl.sql");
            assertThat(statements(g)).containsExactly("sql:stmt:src/main/resources/db/shtdb.sql:3");
            Node load = node(g, "sql:stmt:src/main/resources/db/shtdb.sql:3");
            assertThat(load.attr("dataLoad")).isEqualTo(true);
            assertThat(load.attr("rowCount")).isEqualTo(12);
            assertThat(load.attrString("borrowedFrom")).isEqualTo("hsql");
            assertThat(g.incoming("db:table:T_A", EdgeKind.WRITES)).extracting(e -> e.from()).containsExactly("sql:stmt:src/main/resources/db/shtdb.sql:3");
            assertThat(summary(g).message()).contains("방언별 DDL 2벌 감지(oracle, hsql)").doesNotContain("만들지 않음")
                    .contains("oracle 파일에 적재가 없는 테이블의 묶음 1개는 다른 방언 파일의 것을 씀(hsql 1개)");
        }
    }

    @Test
    @DisplayName("F-007-AC30 테이블마다 고른다: 고른 방언의 파일에 적재가 있는 테이블은 그 묶음만, 없는 테이블은 순위가 가장 앞선 다른 방언의 묶음만 남고, 초기화 구문도 남은 묶음의 것만 노드가 된다")
    void perTableSelection() {
        // jeesite의 배치: 업무 테이블의 초기 데이터는 mysql 파일에만, 일부 테이블은 oracle 파일에도 있다
        String ddl = "CREATE TABLE SYS_A (ID VARCHAR(10), NM VARCHAR(60));\nCREATE TABLE CMS_A (ID VARCHAR(10), NM VARCHAR(60));\n"
                + "CREATE TABLE OA_A (ID VARCHAR(10), NM VARCHAR(60));\n";
        try (TestProject project = TestProject.create()
                .file("db/sys/jeesite_oracle.sql", ddl)
                .file("db/sys/jeesite_mysql.sql", ddl)
                .file("db/sys/jeesite_postgres.sql", ddl)
                .file("db/cms/data_oracle.sql", "DELETE FROM CMS_A;\n" + inserts("CMS_A", 12))
                .file("db/jeesite_mysql.sql", "DELETE FROM CMS_A;\nDELETE FROM SYS_A;\n" + inserts("CMS_A", 12) + inserts("SYS_A", 11) + inserts("OA_A", 3))
                .file("db/jeesite_postgres.sql", "DELETE FROM SYS_A;\n" + inserts("SYS_A", 11) + inserts("OA_A", 3))
                .file("db/common_data.sql", inserts("CMS_A", 10))) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(statements(g)).containsExactly(
                    "sql:stmt:db/cms/data_oracle.sql:1", "sql:stmt:db/cms/data_oracle.sql:2", // oracle: 초기화 구문과 CMS_A
                    "sql:stmt:db/common_data.sql:1", // 방언을 읽지 못한 파일: 고르는 대상이 아니다
                    "sql:stmt:db/jeesite_mysql.sql:15", // SYS_A 묶음
                    "sql:stmt:db/jeesite_mysql.sql:2", // SYS_A 초기화 구문(CMS_A의 초기화 구문 1행은 묶음과 함께 빠진다)
                    "sql:stmt:db/jeesite_mysql.sql:26"); // OA_A 묶음
            assertThat(node(g, "sql:stmt:db/cms/data_oracle.sql:2").attr("borrowedFrom")).isNull();
            assertThat(node(g, "sql:stmt:db/common_data.sql:1").attr("borrowedFrom")).isNull();
            for (String id : List.of("sql:stmt:db/jeesite_mysql.sql:15", "sql:stmt:db/jeesite_mysql.sql:2", "sql:stmt:db/jeesite_mysql.sql:26")) {
                assertThat(node(g, id).attr("dataLoad")).as(id).isEqualTo(true);
                assertThat(node(g, id).attrString("borrowedFrom")).as(id).isEqualTo("mysql");
            }
            assertThat(node(g, "sql:stmt:db/jeesite_mysql.sql:2").attr("rowCount")).isEqualTo(0);
            assertThat(g.incoming("db:table:CMS_A", EdgeKind.WRITES)).extracting(e -> e.from())
                    .containsExactlyInAnyOrder("sql:stmt:db/cms/data_oracle.sql:1", "sql:stmt:db/cms/data_oracle.sql:2", "sql:stmt:db/common_data.sql:1");
            // 만들지 않은 묶음: mysql의 CMS_A, postgres의 SYS_A·OA_A. 묶음을 모두 버린 파일은 postgres 하나
            assertThat(summary(g).message()).contains("다른 방언 파일의 묶음 3개를 만들지 않음")
                    .contains("oracle 파일에 적재가 없는 테이블의 묶음 2개는 다른 방언 파일의 것을 씀(mysql 2개)");
            List<Diagnostic> grouped = diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED);
            assertThat(grouped).extracting(d -> d.location().file())
                    .containsExactlyInAnyOrder("db/cms/data_oracle.sql", "db/jeesite_mysql.sql", "db/common_data.sql");
            Diagnostic mysql = grouped.stream().filter(d -> d.location().file().equals("db/jeesite_mysql.sql")).findFirst().orElseThrow();
            assertThat(mysql.message()).contains("리터럴 INSERT 14건을 테이블별 구문 2개로 묶음").contains("초기화 구문(조건 없는 DELETE·TRUNCATE) 1건도 dataLoad로 표시");
            assertThat(diagnostics(g, io.codeatlas.core.model.DiagnosticCodes.PARSE_FAILED)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-007-AC30 정의가 없는 테이블(다른 방언 스크립트에만 나오는 유령 테이블)의 묶음은 다른 방언 파일에서 가져오지 않는다. 다른 방언 DDL에서 보충한 테이블의 묶음은 가져온다")
    void noGhostTablesFromOtherDialects() {
        try (TestProject project = TestProject.create()
                .file("script/ddl/oracle/a_DDL_oracle.sql", ORACLE_DDL)
                .file("script/ddl/mysql/a_DDL_mysql.sql", MYSQL_DDL + "CREATE TABLE T_MY_ONLY (ID VARCHAR(10), NM VARCHAR(60)) ENGINE=InnoDB;\n")
                .file("script/dml/mysql/data_mysql.sql", inserts("T_GHOST", 12) + inserts("T_MY_ONLY", 3))) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(g.hasNode("db:table:T_GHOST")).as("유령 테이블").isFalse();
            assertThat(statements(g)).containsExactly("sql:stmt:script/dml/mysql/data_mysql.sql:13");
            assertThat(node(g, "sql:stmt:script/dml/mysql/data_mysql.sql:13").attrString("borrowedFrom")).isEqualTo("mysql");
            assertThat(summary(g).message()).contains("다른 방언 파일의 묶음 1개를 만들지 않음")
                    .contains("oracle 파일에 적재가 없는 테이블의 묶음 1개는 다른 방언 파일의 것을 씀(mysql 1개)");
        }
    }
}
