package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 방언 기본값 정책(F-009-R24, R25. sql 모듈의 F-007-R23과 같은 순위다). 이 도구의 대상 방언은 Oracle·DB2다: 프로젝트 설정이
 * 대상 외 방언(전자정부 템플릿의 {@code Globals.DbType=mysql}/{@code hsql})을 가리켜도 대상 방언의 매퍼 파일이 있으면 그쪽을 고른다.
 */
class DialectPolicyTest {

    private static final String NS = "aDAO";
    private static final String VARIANTS_CODE = "DIALECT_VARIANT_MAPPERS";

    private static String variant(String table, String extra) {
        return Mb.mapper(NS, "  <select id=\"find\" resultType=\"map\">SELECT ID FROM " + table + " WHERE ID = #{id}</select>\n" + extra);
    }

    private static TestProject variants() {
        return TestProject.create()
                .file("mapper/A_SQL_cubrid.xml", variant("TB_CUB", ""))
                .file("mapper/A_SQL_mysql.xml", variant("TB_MY", "  <select id=\"onlyMy\">SELECT 1 FROM TB_ONLY_MY</select>\n"
                        + "  <select id=\"commentedInOracle\">SELECT 1 FROM TB_MY_C</select>\n"))
                .file("mapper/A_SQL_oracle.xml", variant("TB_ORA", "  <!-- 오라클에서는 쓰지 않음\n"
                        + "  <select id=\"commentedInOracle\" resultType=\"map\">\n    SELECT 1 FROM TB_ORA_C\n  </select>\n  -->\n"))
                .file("mapper/A_SQL_altibase.xml", variant("TB_ALT", ""));
    }

    private static List<Diagnostic> summaries(AnalysisContext ctx) {
        return Mb.diags(ctx, VARIANTS_CODE);
    }

    @Test
    @DisplayName("F-009-AC45 프로젝트 설정이 대상 외 방언(mysql)이어도 대상 방언(oracle) 매퍼가 있으면 oracle을 고르고, INFO 요약에 설정과 다르게 골랐다는 것과 설정대로 돌리는 방법을 적는다")
    void targetDialectBeatsForeignSetting() {
        try (TestProject project = variants().file("src/main/resources/globals.properties", "Globals.DbType = mysql\n")) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.text(ctx, NS, "find")).isEqualTo("SELECT ID FROM TB_ORA WHERE ID = ?");
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
            assertThat(summaries(ctx)).hasSize(1);
            Diagnostic d = summaries(ctx).get(0);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("oracle 파일을 사용")
                    .contains("프로젝트 설정(src/main/resources/globals.properties:Globals.DbType=mysql)과 다르게 대상 방언 oracle 파일을 사용")
                    .contains("설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`").doesNotContain("부정확");
        }
        // 설정이 Oracle 계열이면 그 계열 안에서 먼저지만 Oracle보다는 뒤다
        try (TestProject project = variants().file("src/main/resources/globals.properties", "Globals.DbType=altibase\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
        }
        // 명시한 요청은 그대로 가장 먼저다
        try (TestProject project = variants().file("src/main/resources/globals.properties", "Globals.DbType=mysql\n")
                .options(AnalysisOptions.builder().extra("mybatis.dialectVariant", "mysql").build())) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("mysql");
            assertThat(summaries(ctx).get(0).severity()).isEqualTo(Severity.INFO);
        }
    }

    @Test
    @DisplayName("F-009-AC45 대상 방언 매퍼가 하나도 없을 때만 WARN이다: 설정이 가리키는 대상 외 방언이 그 밖의 방언보다 먼저다. 설정이 엇갈리면 쓰지 않고 요약에 적는다")
    void foreignDialectOnlyWhenNoTargetFiles() {
        try (TestProject project = TestProject.create()
                .file("mapper/A_SQL_cubrid.xml", variant("TB_CUB", ""))
                .file("mapper/A_SQL_mysql.xml", variant("TB_MY", ""))
                .file("mapper/A_SQL_postgres.xml", variant("TB_PG", ""))
                .file("src/main/resources/globals.properties", "Globals.DbType=cubrid\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("cubrid");
            Diagnostic d = summaries(ctx).get(0);
            assertThat(d.severity()).isEqualTo(Severity.WARN);
            assertThat(d.message()).contains("프로젝트 설정(src/main/resources/globals.properties:Globals.DbType=cubrid)에 따라 cubrid 선택")
                    .contains("이 도구의 대상 방언(Oracle, DB2)의 파일이 없어").contains("부정확할 수 있음");
        }
        try (TestProject project = variants().file("src/main/resources/a.properties", "Globals.DbType=mysql\n")
                .file("src/main/resources/b.properties", "Globals.DbType=altibase\n")) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("oracle");
            assertThat(summaries(ctx).get(0).message())
                    .contains("프로젝트 설정이 엇갈려 쓰지 않음(src/main/resources/a.properties=mysql, src/main/resources/b.properties=altibase)");
        }
    }

    @Test
    @DisplayName("F-009-AC46 다른 방언 파일에서 보충한 구문은 borrowedFrom=<방언>이 붙고 요약에 구문 ID가 나온다. 선택한 방언 파일에서 XML 주석으로 뺀 id는 보충하지 않는다")
    void borrowedStatements() {
        try (TestProject project = variants()) {
            AnalysisContext ctx = Mb.run(project);

            // 고른 방언(oracle) 파일의 구문에는 borrowedFrom이 없다
            assertThat(Mb.stmt(ctx, NS, "find").attr("borrowedFrom")).isNull();
            // oracle 파일에 없는 구문: 다음 순위 방언(altibase·mysql 가운데 정의가 있는 mysql)에서 가져오고 표시한다
            assertThat(Mb.text(ctx, NS, "onlyMy")).isEqualTo("SELECT 1 FROM TB_ONLY_MY");
            assertThat(Mb.stmt(ctx, NS, "onlyMy").attrString("dialectVariant")).isEqualTo("mysql");
            assertThat(Mb.stmt(ctx, NS, "onlyMy").attrString("borrowedFrom")).isEqualTo("mysql");
            // oracle 파일에서 주석으로 뺀 구문: 일부러 뺀 것이므로 다른 방언에서 보충하지 않는다
            assertThat(ctx.graph().hasNode(Ids.mybatisStatement(NS, "commentedInOracle"))).isFalse();
            assertThat(ctx.graph().nodes(NodeKind.SQL_STATEMENT)).hasSize(2);
            Diagnostic d = summaries(ctx).get(0);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
            assertThat(d.message()).contains("oracle 파일에 없는 구문은 다른 방언 파일에서 가져왔다(mysql 1개: " + NS + ".onlyMy)")
                    .contains("oracle 파일에서 XML 주석으로 뺀 구문 1개는 보충하지 않음(" + NS + ".commentedInOracle)");
        }
    }

    private static final String PG_DDL = "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, PG_COL INT);\n";
    private static final String MY_DDL = "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, MY_COL INT);\n";

    @Test
    @DisplayName("F-009-AC47 대상 방언 매퍼도 프로젝트 설정도 없으면 그 밖의 방언끼리는 방언 표의 순서가 아니라 이름의 사전순이다: mysql·postgres 두 벌이면 mysql(sql 모듈 F-007-AC29와 같은 방언)")
    void otherDialectsByName() {
        try (TestProject project = TestProject.create()
                .file("mapper/A_SQL_mysql.xml", variant("TB_MY", ""))
                .file("mapper/A_SQL_postgres.xml", variant("TB_PG", ""))) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(Mb.text(ctx, NS, "find")).isEqualTo("SELECT ID FROM TB_MY WHERE ID = ?");
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("mysql");
            Diagnostic d = summaries(ctx).get(0);
            assertThat(d.severity()).isEqualTo(Severity.WARN);
            assertThat(d.message()).contains("방언별 매퍼 2벌 감지(mysql, postgres): mysql 파일을 사용").contains("대상 방언(Oracle, DB2)의 파일이 없어");
        }
    }

    @Test
    @DisplayName("F-009-AC47 매퍼와 SQL 스크립트 양쪽의 경로에 다 있는 그 밖의 방언이 한쪽에만 있는 방언보다 먼저다: 매퍼 cubrid·mysql·postgres에 스크립트 mysql·postgres면 mysql, 스크립트가 없으면 cubrid. 앞 단계(프로젝트 설정의 대상 외 방언)는 그대로 먼저다")
    void otherDialectsOnBothSidesFirst() {
        try (TestProject project = TestProject.create()
                .file("mapper/A_SQL_cubrid.xml", variant("TB_CUB", ""))
                .file("mapper/A_SQL_mysql.xml", variant("TB_MY", ""))
                .file("mapper/A_SQL_postgres.xml", variant("TB_PG", ""))) {
            // 매퍼뿐이면 이름의 사전순 첫 방언
            assertThat(Mb.stmt(Mb.run(project), NS, "find").attrString("dialectVariant")).isEqualTo("cubrid");

            // 스크립트 쪽에도 있는 방언(mysql, postgres)이 먼저고 그 안에서 사전순
            project.file("db/postgres/ddl_postgres.sql", PG_DDL).file("db/mysql/ddl_mysql.sql", MY_DDL);
            AnalysisContext ctx = Mb.run(project);
            assertThat(Mb.stmt(ctx, NS, "find").attrString("dialectVariant")).isEqualTo("mysql");
            assertThat(summaries(ctx).get(0).message()).contains("방언별 매퍼 3벌 감지(mysql, postgres, cubrid): mysql 파일을 사용");

            // 프로젝트 설정이 가리키는 대상 외 방언은 전처럼 그보다 먼저다
            project.file("src/main/resources/globals.properties", "Globals.DbType=postgres\n");
            assertThat(Mb.stmt(Mb.run(project), NS, "find").attrString("dialectVariant")).isEqualTo("postgres");
        }
    }
}
