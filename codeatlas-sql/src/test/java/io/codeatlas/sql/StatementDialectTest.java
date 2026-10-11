package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.node;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQL 스크립트 구문의 {@code dialect} 속성(F-006-R28). 공개 프로젝트에서 MySQL 덤프의 구문이 {@code DB2}({@code BIGINT}·{@code SMALLINT} 때문),
 * mysql·altibase·hsql 데이터 스크립트의 구문이 {@code ORACLE}({@code SYSDATE} 때문)로 적혔다. 판정하지 못하면 키를 넣지 않는다.
 */
class StatementDialectTest {

    private static final String MYSQL_DUMP = """
            CREATE TABLE cms_doc (doc_id bigint(20) NOT NULL, kind smallint, orders bigint);
            UPDATE cms_doc SET kind = CAST(orders AS SMALLINT) WHERE doc_id = 1;
            CREATE VIEW v_doc AS SELECT doc_id, CAST(kind AS BIGINT) AS kind FROM cms_doc;
            """;

    private static String rows(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("INSERT INTO T_CODE (CD, REG_DT) VALUES ('").append(i).append("', SYSDATE);\n");
        }
        return sb.toString();
    }

    private static GraphModel analyze(String path, String sql, AnalysisOptions options) {
        try (TestProject project = TestProject.create().file(path, sql)) {
            if (options != null) {
                project.options(options);
            }
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    @Test
    @DisplayName("F-006-AC28 대상 외 방언의 스크립트(경로의 방언 이름, MySQL 특징)의 구문에는 dialect를 넣지 않는다. BIGINT·SMALLINT만으로 DB2라고 하지 않는다")
    void foreignScriptsHaveNoDialect() {
        for (String path : new String[] {"db/mysql/dump.sql", "db/dump_mariadb.sql", "db/hsql/dump.sql", "db/dump.sql"}) {
            GraphModel g = analyze(path, MYSQL_DUMP, null);
            assertThat(g.nodes(NodeKind.SQL_STATEMENT)).as(path).hasSize(2);
            assertThat(g.nodes(NodeKind.SQL_STATEMENT)).as(path).allMatch(n -> n.attr("dialect") == null);
        }
        // 방언 이름이 없는 파일: 백틱·ENGINE= 같은 MySQL 특징이 있으면 넣지 않는다
        GraphModel dump = analyze("db/dump.sql", "CREATE TABLE `t` (`id` bigint NOT NULL) ENGINE=InnoDB;\nUPDATE `t` SET `id` = NVL(`id`, 0);\n", null);
        assertThat(node(dump, "sql:stmt:db/dump.sql:2").attr("dialect")).isNull();
        // 데이터 적재 묶음도 같다: altibase·mysql 데이터 스크립트의 SYSDATE로 ORACLE이라고 하지 않는다
        for (String path : new String[] {"script/dml/altibase/data.sql", "script/dml/data_mysql.sql", "script/dml/tibero/data.sql"}) {
            GraphModel g = analyze(path, rows(12), null);
            assertThat(node(g, "sql:stmt:" + path + ":1").attr("dataLoad")).as(path).isEqualTo(true);
            assertThat(node(g, "sql:stmt:" + path + ":1").attr("dialect")).as(path).isNull();
        }
    }

    @Test
    @DisplayName("F-006-AC28 F-006-AC37 dialect는 경로의 Oracle·DB2 이름, 구문 특징 순으로만 넣는다. --dialect 옵션은 표기로 찍지 않는다")
    void dialectFromOptionPathFeature() {
        // 옵션은 구문의 dialect로 찍지 않는다(F-006-R37. 전에는 옵션이 먼저였다): mysql 경로의 구문은 --dialect db2여도 dialect가 없다
        GraphModel byOption = analyze("db/mysql/dump.sql", MYSQL_DUMP, AnalysisOptions.builder().dialect(Dialect.DB2).build());
        assertThat(byOption.nodes(NodeKind.SQL_STATEMENT)).isNotEmpty().allMatch(n -> n.attr("dialect") == null);
        // 경로의 방언 이름(폴더, 파일 이름의 토큰)
        assertThat(node(analyze("db/data_oracle.sql", rows(12), null), "sql:stmt:db/data_oracle.sql:1").attrString("dialect")).isEqualTo("ORACLE");
        assertThat(node(analyze("db/db2/data.sql", rows(12), null), "sql:stmt:db/db2/data.sql:1").attrString("dialect")).isEqualTo("DB2");
        // 방언 이름이 없으면 구문 특징
        assertThat(node(analyze("db/data.sql", rows(12), null), "sql:stmt:db/data.sql:1").attrString("dialect")).isEqualTo("ORACLE");
        GraphModel db2 = analyze("db/q.sql", "UPDATE T SET A = 1 WHERE B = CURRENT TIMESTAMP;\n", null);
        assertThat(node(db2, "sql:stmt:db/q.sql:1").attrString("dialect")).isEqualTo("DB2");
        // 특징이 없으면 넣지 않는다
        GraphModel none = analyze("db/q.sql", "UPDATE T SET A = CAST(B AS BIGINT) WHERE C = 1;\n", null);
        assertThat(node(none, "sql:stmt:db/q.sql:1").attr("dialect")).isNull();
    }

    private static io.codeatlas.core.spi.Dialect detect(String sql) {
        return io.codeatlas.sql.analysis.DialectDetector.fromTokens(io.codeatlas.sql.lexer.Lexer.tokenize(sql));
    }

    @Test
    @DisplayName("F-006-AC42 MySQL 표기(SYSDATE()처럼 괄호가 붙은 꼴, 따옴표 없는 INTERVAL n MONTH, ADDDATE)가 있는 구문은 SYSDATE가 있어도 ORACLE로 추측하지 않는다. Oracle·DB2 구문의 판정은 그대로다")
    void mysqlIdiomsAreNotGuessedAsOracle() {
        // 재현(S5/repro/my2 M.mysqlInterval): dialect=ORACLE이 붙었다
        assertThat(detect("SELECT LOG_ID FROM TB_LOG WHERE OCCRRNC_DE < ADDDATE(SYSDATE(), INTERVAL -6 MONTH)")).isNull();
        assertThat(detect("SELECT LOG_ID FROM TB_LOG WHERE OCCRRNC_DE < SYSDATE()")).isNull();
        assertThat(detect("SELECT NVL(A, 0) FROM TB_LOG WHERE OCCRRNC_DE < ADDDATE(REG_DT, 7)")).isNull();
        assertThat(detect("DELETE FROM TB_LOG WHERE OCCRRNC_DE < SYSDATE - INTERVAL 6 MONTH")).isNull();
        assertThat(detect("DELETE FROM TB_LOG WHERE OCCRRNC_DE < SYSDATE + INTERVAL -1 DAY")).isNull();
        // Oracle·DB2의 같은 자리 표기는 전과 같다
        assertThat(detect("DELETE FROM TB_LOG WHERE OCCRRNC_DE < SYSDATE - INTERVAL '6' MONTH")).isEqualTo(io.codeatlas.core.spi.Dialect.ORACLE);
        assertThat(detect("SELECT NVL(A, SYSDATE) FROM TB_LOG WHERE B IN (SYSDATE) AND C = SYSDATE(+)")).isEqualTo(io.codeatlas.core.spi.Dialect.ORACLE);
        assertThat(detect("SELECT LOG_ID FROM TB_LOG WHERE OCCRRNC_DE < ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -6)")).isEqualTo(io.codeatlas.core.spi.Dialect.ORACLE);
        assertThat(detect("SELECT LOG_ID FROM TB_LOG WHERE OCCRRNC_DE < CURRENT DATE - 6 MONTHS WITH UR")).isEqualTo(io.codeatlas.core.spi.Dialect.DB2);
        // INTERVAL이 컬럼 이름인 Oracle 구문(뒤에 단위가 오지 않는다)
        assertThat(detect("SELECT NVL(INTERVAL, 0) - 1 FROM TB_JOB WHERE INTERVAL - 1 > 0 AND REG_DT < SYSDATE")).isEqualTo(io.codeatlas.core.spi.Dialect.ORACLE);

        // 구문 노드의 dialect 표기까지: 방언 이름이 없는 파일의 구문마다 판정한다
        GraphModel g = analyze("db/q.sql", """
                SELECT LOG_ID FROM TB_LOG WHERE OCCRRNC_DE < ADDDATE(SYSDATE(), INTERVAL -6 MONTH);
                SELECT LOG_ID FROM TB_LOG WHERE OCCRRNC_DE < CURRENT DATE - 6 MONTHS WITH UR;
                """, null);
        assertThat(node(g, "sql:stmt:db/q.sql:1").attr("dialect")).isNull();
    }
}
