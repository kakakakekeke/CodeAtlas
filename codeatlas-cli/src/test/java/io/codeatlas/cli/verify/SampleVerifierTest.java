package io.codeatlas.cli.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 두 샘플(sample-shop, sample-pg)이 같이 쓰는 대조 장치의 단위 테스트. 파이프라인은 돌리지 않고 손으로 만든 그래프를 넣는다. */
class SampleVerifierTest {

    private static final String UNRESOLVED_MD = """
            ## 1. 반드시 미해결이어야 하는 지점

            | # | 위치 | 내용 | 이유 | 있으면 안 되는 것 |
            |---|---|---|---|---|
            | U1 | `db/routines.sql:40-41` | `EXECUTE format(...)` | 매개변수 | 테이블 |

            ## 4. 일부러 넣은 미지원 구문

            | # | 위치 | 내용 | 있으면 안 되는 것 |
            |---|---|---|---|
            | N1 | `db/schema.sql:7` | `REFRESH MATERIALIZED VIEW mv;` | 테이블·구문 노드 |
            """;

    @TempDir
    Path dir;

    private Path project;
    private Path reportDir;
    private final GraphModel graph = new GraphModel();

    @BeforeEach
    void setUp() throws Exception {
        project = dir.resolve("mini");
        reportDir = dir.resolve("out");
        Path expected = Files.createDirectories(project.resolve("expected"));
        Files.writeString(expected.resolve(ExpectedSet.MUST_HAVE_NODES), """
                [{"id":"db:table:T","kind":"DB_TABLE"}]""", StandardCharsets.UTF_8);
        Files.writeString(expected.resolve(ExpectedSet.MUST_HAVE_EDGES), """
                [{"from":"db:view:V","to":"db:table:T","kind":"READS"}]""", StandardCharsets.UTF_8);
        Files.writeString(expected.resolve(ExpectedSet.MUST_NOT_EDGES), """
                [{"from":"*","to":"db:table:ONLY","kind":"*","note":"함정 1. ONLY"}]""", StandardCharsets.UTF_8);
        Files.writeString(expected.resolve("expected-unresolved.md"), UNRESOLVED_MD, StandardCharsets.UTF_8);

        graph.declare(Node.builder("db:table:T", NodeKind.DB_TABLE).build());
        graph.declare(Node.builder("db:view:V", NodeKind.DB_VIEW).build());
        graph.addEdge(Edge.of("db:view:V", "db:table:T", EdgeKind.READS));
    }

    private SampleVerifier.Settings settings(String mode) {
        return new SampleVerifier.Settings(project, reportDir, mode, 100, 100, 0, CheckOptions.defaults());
    }

    private static List<String> gateNames(SampleReport report) {
        return report.gates().stream().map(SampleReport.Gate::name).toList();
    }

    @Test
    @DisplayName("F-019-AC34 샘플 이름이 산출물 이름과 제목에 들어간다: <이름>-report.md, <이름>-report.json, <이름>-graph.json")
    void writesFilesNamedAfterTheSample() throws Exception {
        SampleReport report = SampleVerifier.of("sample-pg").evaluate(graph, List.of(), List.of("분석기 sql(30)"), settings("assert"));

        assertThat(reportDir.resolve("sample-pg-report.md")).isRegularFile();
        assertThat(reportDir.resolve("sample-pg-report.json")).isRegularFile();
        assertThat(reportDir.resolve("sample-pg-graph.json")).isRegularFile();
        assertThat(reportDir.resolve("sample-shop-report.md")).doesNotExist();
        assertThat(Files.readString(reportDir.resolve("sample-pg-report.md"))).startsWith("# sample-pg 검증 리포트").contains("분석기 sql(30)");
        assertThat(report.gates()).allMatch(SampleReport.Gate::passed);
    }

    @Test
    @DisplayName("F-019-AC35 깨진 파일 목록을 준 샘플(sample-shop)의 기준은 전과 같다: 파이프라인, broken/ 파일, must-not, 엣지, 노드")
    void brokenFileGateIsKeptForSamplesThatDeclareBrokenFiles() throws Exception {
        graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "깨짐", SourceLocation.of("broken/A.java", 3)));

        SampleReport report = SampleVerifier.of("sample-shop").brokenFiles(List.of("broken/A.java", "broken/b.sql"))
                .evaluate(graph, List.of(), List.of(), settings("report"));

        assertThat(gateNames(report)).containsExactly("파이프라인이 예외 없이 끝남", "broken/ 파일 2개가 각각 진단으로 남음", "must-not 위반 ≤ 0",
                "must-have 엣지 통과율 ≥ 100.0% (certainty=low 제외)", "must-have 노드 통과율 ≥ 100.0% (certainty=low 제외)");
        assertThat(report.gates().get(1)).satisfies(g -> {
            assertThat(g.passed()).isFalse();
            assertThat(g.detail()).isEqualTo("진단 없음: broken/b.sql");
        });
        assertThat(Files.readString(reportDir.resolve("sample-shop-report.md"))).contains("| `broken/A.java` | 있음 |").contains("| `broken/b.sql` | **없음** |");
        assertThat(reportDir.resolve("sample-shop-graph.json")).isRegularFile();
    }

    @Test
    @DisplayName("깨진 파일 목록을 주지 않은 샘플에는 broken/ 기준이 없다")
    void noBrokenFileGateWithoutBrokenFiles() throws Exception {
        SampleReport report = SampleVerifier.of("sample-pg").evaluate(graph, List.of(), List.of(), settings("report"));

        assertThat(gateNames(report)).containsExactly("파이프라인이 예외 없이 끝남", "must-not 위반 ≤ 0",
                "must-have 엣지 통과율 ≥ 100.0% (certainty=low 제외)", "must-have 노드 통과율 ≥ 100.0% (certainty=low 제외)");
    }

    @Test
    @DisplayName("F-019-AC34 진단 기준: PARSE_FAILED·UNSUPPORTED_SYNTAX가 expected-unresolved.md의 N 지점에만 있으면 통과한다(없어도 통과)")
    void parseFailuresAtListedPointsPass() throws Exception {
        SampleVerifier verifier = SampleVerifier.of("sample-pg").parseFailuresOnlyAt("N");

        SampleReport silent = verifier.evaluate(graph, List.of(), List.of(), settings("assert"));
        assertThat(silent.gates()).filteredOn(g -> g.name().contains("일부러 넣은 미지원 구문")).singleElement().satisfies(g -> {
            assertThat(g.name()).isEqualTo("PARSE_FAILED·UNSUPPORTED_SYNTAX가 일부러 넣은 미지원 구문(N1 db/schema.sql:7)에만 있음");
            assertThat(g.passed()).isTrue();
        });

        graph.report(Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "REFRESH", SourceLocation.of("db/schema.sql", 7)));
        SampleReport reported = verifier.evaluate(graph, List.of(), List.of(), settings("assert"));
        assertThat(reported.gates()).allMatch(SampleReport.Gate::passed);
        assertThat(Files.readString(reportDir.resolve("sample-pg-report.md"))).contains("| N1 | `db/schema.sql:7` | UNSUPPORTED_SYNTAX REFRESH |");
    }

    @Test
    @DisplayName("F-019-AC34 진단 기준: 목록 밖의 PARSE_FAILED·UNSUPPORTED_SYNTAX가 있으면 실패하고 그 목록을 보여 준다. report 모드에서는 리포트에 적기만 한다")
    void parseFailuresOutsideListedPointsFail() throws Exception {
        graph.report(Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "REFRESH", SourceLocation.of("db/schema.sql", 7)));
        for (int line = 100; line < 112; line++) {
            graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "읽지 못한 문장 " + line, SourceLocation.of("db/schema.sql", line)));
        }
        // U 지점은 허용 목록이 아니다
        graph.report(Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "EXECUTE", SourceLocation.of("db/routines.sql", 40)));
        SampleVerifier verifier = SampleVerifier.of("sample-pg").parseFailuresOnlyAt("N");

        SampleReport report = verifier.evaluate(graph, List.of(), List.of(), settings("report"));
        assertThat(report.gates()).filteredOn(g -> !g.passed()).singleElement().satisfies(g -> {
            assertThat(g.name()).contains("일부러 넣은 미지원 구문");
            assertThat(g.detail()).startsWith("목록 밖 13건: ").contains("PARSE_FAILED db/schema.sql:100").contains("외 ");
        });
        String md = Files.readString(reportDir.resolve("sample-pg-report.md"));
        assertThat(md).contains("#### 목록 밖의 PARSE_FAILED·UNSUPPORTED_SYNTAX (13)").contains("| UNSUPPORTED_SYNTAX | `db/routines.sql:40` | EXECUTE |");

        // 단언 모드의 실패 메시지에는 줄이지 않은 목록이 다 나온다
        assertThatThrownBy(() -> verifier.evaluate(graph, List.of(), List.of(), settings("assert")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("sample-pg 임계값 미달")
                .hasMessageContaining("sample-pg-report.md")
                .hasMessageContaining("PARSE_FAILED db/schema.sql:111 읽지 못한 문장 111")
                .hasMessageContaining("UNSUPPORTED_SYNTAX db/routines.sql:40 EXECUTE");
        assertThat(reportDir.resolve("sample-pg-report.md")).isRegularFile();
    }

    @Test
    @DisplayName("단언 모드: 통과율·함정·파이프라인 기준에 못 미치면 실패하고, report 모드는 같은 판정을 리포트에만 적는다")
    void assertModeFailsOnThresholdsAndReportModeDoesNot() throws Exception {
        GraphModel bad = new GraphModel();
        bad.declare(Node.builder("db:view:V", NodeKind.DB_VIEW).build());
        bad.declare(Node.builder("db:table:ONLY", NodeKind.DB_TABLE).build());
        bad.addEdge(Edge.of("db:view:V", "db:table:ONLY", EdgeKind.READS));
        SampleVerifier verifier = SampleVerifier.of("sample-pg");

        SampleReport report = verifier.evaluate(bad, List.of("파이프라인 예외: java.lang.IllegalStateException: boom"), List.of(), settings("report"));
        assertThat(report.gates()).noneMatch(SampleReport.Gate::passed);
        assertThat(report.gates().get(0).detail()).contains("boom");

        assertThatThrownBy(() -> verifier.evaluate(bad, List.of(), List.of(), settings("assert")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must-not 위반 ≤ 0 — 위반 1 / 함정 1")
                .hasMessageContaining("must-have 엣지 통과율")
                .hasMessageContaining("must-have 노드 통과율");
    }

    @Test
    @DisplayName("F-019-AC34 정답 JSON으로 적지 못한 기대는 따로 주는 확인으로 기준에 더해진다")
    void extraChecksBecomeGates() throws Exception {
        graph.declare(Node.builder("db:routine:FAST_HASH", NodeKind.fromId("db:routine:FAST_HASH").orElseThrow()).attr("language", "C").build());
        SampleVerifier verifier = SampleVerifier.of("sample-pg")
                .check(ExtraCheck.nodeWithoutAttr("db:routine:FAST_HASH", "language"))
                .check(ExtraCheck.nodeWithoutAttr("db:table:T", "language"));

        SampleReport report = verifier.evaluate(graph, List.of(), List.of(), settings("report"));

        assertThat(gateNames(report)).endsWith("`db:routine:FAST_HASH`에 `language` 속성이 없음", "`db:table:T`에 `language` 속성이 없음");
        assertThat(report.gates().get(report.gates().size() - 2).passed()).isFalse();
        assertThat(report.gates().get(report.gates().size() - 1).passed()).isTrue();
        assertThatThrownBy(() -> verifier.evaluate(graph, List.of(), List.of(), settings("assert")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("`db:routine:FAST_HASH`에 `language` 속성이 없음 — language=C");
    }

    @Test
    @DisplayName("F-019-AC34 속성이 없어야 하는 노드: 노드가 없으면 실패, 속성이 있으면 실패, 노드만 있고 속성이 없으면 통과")
    void nodeWithoutAttr() {
        ExtraCheck check = ExtraCheck.nodeWithoutAttr("db:routine:FAST_HASH", "language");
        NodeKind routine = NodeKind.fromId("db:routine:FAST_HASH").orElseThrow();

        GraphModel g = new GraphModel();
        assertThat(check.test().apply(g)).satisfies(o -> {
            assertThat(o.passed()).isFalse();
            assertThat(o.detail()).isEqualTo("노드가 없다");
        });

        g.declare(Node.builder("db:routine:FAST_HASH", routine).attr("routineType", "FUNCTION").build());
        assertThat(check.test().apply(g).passed()).isTrue();

        g.putAttrs("db:routine:FAST_HASH", Map.of("language", "PLPGSQL"));
        assertThat(check.test().apply(g)).satisfies(o -> {
            assertThat(o.passed()).isFalse();
            assertThat(o.detail()).isEqualTo("language=PLPGSQL");
        });
    }

    @Test
    @DisplayName("F-019-AC34 있어야 하는 미해결 엣지: from·to·종류가 같고 신뢰도가 UNRESOLVED이며 적힌 attrs가 맞아야 통과한다")
    void unresolvedEdge() {
        String stmt = "sql:stmt:ARCHIVE_ORDERS:173";
        String target = "unresolved:sql-dynamic:" + stmt;
        ExtraCheck check = ExtraCheck.unresolvedEdge(stmt, EdgeKind.WRITES, target, Map.of("op", "INSERT"));
        assertThat(check.name()).isEqualTo("미해결 엣지 `sql:stmt:ARCHIVE_ORDERS:173` → `unresolved:sql-dynamic:sql:stmt:ARCHIVE_ORDERS:173` WRITES {op=INSERT}가 있음");

        GraphModel none = new GraphModel();
        assertThat(check.test().apply(none)).satisfies(o -> {
            assertThat(o.passed()).isFalse();
            assertThat(o.detail()).startsWith("엣지가 없다");
        });

        // 같은 끝점이지만 RESOLVED인 엣지는 미해결 엣지가 아니다
        GraphModel resolved = new GraphModel();
        resolved.addEdge(Edge.builder(stmt, target, EdgeKind.WRITES).attr("op", "INSERT").build());
        assertThat(check.test().apply(resolved)).satisfies(o -> {
            assertThat(o.passed()).isFalse();
            assertThat(o.detail()).contains("신뢰도 RESOLVED");
        });

        GraphModel wrongOp = new GraphModel();
        wrongOp.addEdge(Edge.builder(stmt, target, EdgeKind.WRITES).unresolved().attr("op", "DELETE").build());
        assertThat(check.test().apply(wrongOp)).satisfies(o -> {
            assertThat(o.passed()).isFalse();
            assertThat(o.detail()).contains("op: 기대 INSERT, 실제 DELETE");
        });

        // 줄이 다른 구문에서 나간 미해결 엣지는 통과가 아니고, 실패 내용에 그 엣지를 보여 준다
        GraphModel otherLine = new GraphModel();
        otherLine.addEdge(Edge.builder("sql:stmt:ARCHIVE_ORDERS:174", "unresolved:sql-dynamic:sql:stmt:ARCHIVE_ORDERS:174", EdgeKind.WRITES).unresolved()
                .attr("op", "INSERT").build());
        assertThat(check.test().apply(otherLine)).satisfies(o -> {
            assertThat(o.passed()).isFalse();
            assertThat(o.detail()).contains("unresolved:sql-dynamic:sql:stmt:ARCHIVE_ORDERS:174");
        });

        GraphModel ok = new GraphModel();
        ok.addEdge(Edge.builder(stmt, target, EdgeKind.WRITES).unresolved().attr("op", "INSERT").attr("text", "INSERT INTO {*}").build());
        assertThat(check.test().apply(ok).passed()).isTrue();
    }
}
