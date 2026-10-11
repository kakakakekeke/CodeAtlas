package io.codeatlas.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PostgreSQL이 대상 방언이 된 뒤의 cli: {@code --dialect postgres}, 사용법·오류 문구, 방언 힌트(F-019-R1, R34). */
class PostgresDialectCliTest {

    @TempDir
    Path project;
    @TempDir
    Path work;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String... args) {
        outBytes.reset();
        errBytes.reset();
        return Main.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8), new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void fakeProject() throws IOException {
        Path file = project.resolve("db/query.sql");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "select * from orders;\n", StandardCharsets.UTF_8);
        RecordingAnalyzer.reset();
    }

    @AfterEach
    void resetHooks() {
        RecordingAnalyzer.reset();
    }

    @Test
    @DisplayName("F-019-AC1 --dialect postgres, PostgreSQL, pgsql은 Dialect.POSTGRES이고 graph.json의 project.options에도 그렇게 저장된다")
    void dialectPostgresAndAliases() throws IOException {
        int i = 0;
        for (String value : List.of("postgres", "PostgreSQL", "pgsql", "POSTGRESQL")) {
            Path outDir = work.resolve("o" + i++);
            assertThat(run("analyze", project.toString(), "--out", outDir.toString(), "--no-export", "--quiet", "--dialect", value))
                    .as("--dialect %s: %s", value, err()).isZero();
            assertThat(RecordingAnalyzer.lastOptions.dialect()).as("--dialect %s", value).isEqualTo(Dialect.POSTGRES);
            assertThat(GraphJson.read(outDir.resolve("graph.json")).project().options().dialect()).isEqualTo(Dialect.POSTGRES);
        }
        // --dialect=값 꼴도 같다
        assertThat(run("analyze", project.toString(), "--out", work.resolve("eq").toString(), "--no-export", "--quiet", "--dialect=postgres"))
                .as(err()).isZero();
        assertThat(RecordingAnalyzer.lastOptions.dialect()).isEqualTo(Dialect.POSTGRES);
        assertThat(Dialect.parse("postgres")).isEqualTo(Dialect.POSTGRES);
    }

    @Test
    @DisplayName("F-019-AC1 F-019-AC32 --dialect mysql, --dialect foo는 사용법 오류(2)이고 오류 문구에 oracle, db2, postgres, auto와 "
            + "그 밖의 방언용 --opt mybatis.dialectVariant가 있다")
    void unknownDialectListsPostgres() {
        for (String value : List.of("mysql", "foo")) {
            assertThat(run("analyze", project.toString(), "--out", work.resolve("x").toString(), "--dialect", value)).isEqualTo(2);
            assertThat(err()).contains("알 수 없는 방언: " + value).contains("oracle, db2, postgres, auto")
                    .contains("--opt mybatis.dialectVariant=<방언>");
            assertThat(out()).isEmpty();
        }
        assertThat(work.resolve("x/graph.json")).doesNotExist();
    }

    @Test
    @DisplayName("F-019-AC32 analyze 사용법과 도움말에 postgres가 있다: --dialect oracle|db2|postgres|auto, 대상 방언(oracle, db2, postgres)과 "
            + "기본 순서 Oracle → DB2 → PostgreSQL → 그 계열")
    void usageAndHelpMentionPostgres() {
        assertThat(run("--help")).isZero();
        assertThat(out()).contains("[--dialect oracle|db2|postgres|auto]").doesNotContain("--dialect oracle|db2|auto");

        assertThat(run("help", "analyze")).isZero();
        assertThat(out()).contains("[--dialect oracle|db2|postgres|auto]")
                .contains("SQL 방언. oracle, db2, postgres(별칭 postgresql, pgsql), auto(기본값)")
                .contains("대상 방언(oracle, db2, postgres)").contains("Oracle → DB2 → PostgreSQL →")
                .contains("--opt mybatis.dialectVariant=<방언>");

        // 사용법 오류로 나오는 사용법도 같다
        assertThat(run("analyze")).isEqualTo(2);
        assertThat(err()).contains("[--dialect oracle|db2|postgres|auto]");
    }

    @Test
    @DisplayName("F-019-AC32 계약 문서 4.5의 사용법과 README가 cli의 사용법과 같은 --dialect 값을 적는다")
    void contractAndReadmeAgreeWithSynopsis() throws IOException {
        String values = "--dialect oracle|db2|postgres|auto";
        assertThat(new AnalyzeCommand().synopsis()).contains("[" + values + "]");

        String design = Files.readString(Path.of("..", "docs", "specs", "F-001-analysis-pipeline", "design.md"), StandardCharsets.UTF_8);
        String section = design.substring(design.indexOf("### 4.5 "), design.indexOf("### 4.6 "));
        assertThat(section).as("계약 4.5의 사용법").contains("[" + values + "]").doesNotContain("--dialect oracle|db2|auto");
        assertThat(section).as("계약 4.5의 힌트 표").contains("`--dialect <oracle\\|db2\\|postgres>`").doesNotContain("`--dialect <oracle\\|db2>`");

        String readme = Files.readString(Path.of("..", "README.md"), StandardCharsets.UTF_8);
        assertThat(readme).as("README").contains("`" + values + "`").doesNotContain("--dialect oracle|db2|auto")
                .doesNotContain("--dialect oracle|db2`").doesNotContain("`--dialect`는 oracle·db2만").doesNotContain("oracle·db2·auto만");
    }

    private static GraphModel graph() {
        GraphModel graph = new GraphModel();
        graph.project(new ProjectInfo("p", "/work/p", Instant.parse("2026-10-11T00:00:00Z"), AnalysisOptions.defaults()));
        return graph;
    }

    private static void mapperStatement(GraphModel graph, String id, String file, String dialectVariant) {
        graph.declare(Node.builder(Ids.mybatisStatement("aDAO", id), NodeKind.SQL_STATEMENT).name(id)
                .location(SourceLocation.of(file, 4)).attr("statementType", "SELECT").attr("dialectVariant", dialectVariant).build());
    }

    private static void table(GraphModel graph, String name, String file) {
        graph.declare(Node.builder(Ids.table(name), NodeKind.DB_TABLE).name(name).location(SourceLocation.of(file, 1)).build());
    }

    @Test
    @DisplayName("F-019-AC32 DIALECT_VARIANT_DDL·DIALECT_NOT_FOUND 힌트, 매퍼·DDL 불일치 경고, UNKNOWN_COLUMN 힌트의 옵션 안내가 "
            + "--dialect <oracle|db2|postgres>다")
    void hintsOfferPostgres() {
        GraphModel graph = graph();
        graph.report(Diagnostic.info("DIALECT_VARIANT_DDL",
                "방언별 DDL 2벌 감지(oracle, postgres): oracle 파일을 사용(테이블 4개), 다른 방언에서 보충 0개, 제외한 파일 1개.", null));
        graph.report(Diagnostic.warn("DIALECT_NOT_FOUND",
                "요청한 방언 db2(`--dialect`)의 DDL·데이터 스크립트 파일이 없음: 감지한 방언(oracle, postgres) 가운데 oracle 파일을 사용", null));
        graph.report(Diagnostic.info("UNKNOWN_COLUMN", "DDL에 없는 컬럼이라 소속 테이블을 정하지 못함: C", SourceLocation.of("m.xml", 1)));
        mapperStatement(graph, "findList", "src/main/resources/mapper/A_SQL_postgres.xml", "postgres");
        table(graph, "TB_A", "db/oracle/ddl_oracle.sql");

        List<String> hints = Hints.of(graph);

        assertThat(hints).hasSize(4).allSatisfy(hint -> assertThat(hint).doesNotContain("\n").doesNotContain("<oracle|db2>"));
        assertThat(hints.get(0)).startsWith("주의: ").contains("요청한 방언 db2")
                .contains("감지한 방언으로 분석하려면 --dialect <oracle|db2|postgres>, 그 밖의 방언은 --opt mybatis.dialectVariant=<방언>");
        assertThat(hints.get(1)).contains("방언별 DDL")
                .contains("다른 방언의 DDL을 쓰려면 --dialect <oracle|db2|postgres>, 그 밖의 방언은 --opt mybatis.dialectVariant=<방언>");
        assertThat(hints.get(2)).startsWith("경고").contains("매퍼는 postgres").contains("DDL은 oracle")
                .contains("대상 방언이면 --dialect <oracle|db2|postgres>도 된다");
        assertThat(hints.get(3)).contains("UNKNOWN_COLUMN 1건").contains("--dialect <oracle|db2|postgres>");
    }

    @Test
    @DisplayName("F-019-AC32 힌트의 방언 순위는 F-019-R4와 같다: 같은 수로 가장 많은 방언이 여럿이면 oracle, db2, postgres, tibero, altibase 순으로 "
            + "앞선 것을 적는다. 경로의 postgresql·pgsql 토큰은 postgres다")
    void hintRankingPutsPostgresBeforeOracleFamily() {
        // 매퍼: tibero와 postgres가 한 구문씩(같은 수). DDL은 mysql뿐이다 → 어긋났다고 알릴 때 앞선 방언(postgres)을 적는다
        GraphModel graph = graph();
        mapperStatement(graph, "a", "mapper/A_SQL_tibero.xml", "tibero");
        mapperStatement(graph, "b", "mapper/A_SQL_postgres.xml", "postgresql");
        table(graph, "TB_A", "db/mysql/a.sql");
        assertThat(Hints.of(graph)).singleElement().asString().startsWith("경고").contains("매퍼는 postgres").contains("DDL은 mysql");

        // DDL 쪽도 같다: altibase와 pgsql이 한 테이블씩이면 postgres가 앞이고, db2가 끼면 db2가 그보다 앞이다
        GraphModel ddl = graph();
        mapperStatement(ddl, "a", "mapper/A_SQL_mysql.xml", "mysql");
        table(ddl, "TB_A", "db/altibase/a.sql");
        table(ddl, "TB_B", "db/pgsql/b.sql");
        assertThat(Hints.of(ddl)).singleElement().asString().contains("매퍼는 mysql").contains("DDL은 postgres");
        table(ddl, "TB_C", "db/c.db2.sql");
        assertThat(Hints.of(ddl)).singleElement().asString().contains("DDL은 db2");

        assertThat(Hints.dialectOfPath("db/postgres/schema.sql")).isEqualTo("postgres");
        assertThat(Hints.dialectOfPath("db/schema_pgsql.sql")).isEqualTo("postgres");
        assertThat(Hints.dialectOfPath("mapper/Order.postgresql.xml")).isEqualTo("postgres");
    }
}
