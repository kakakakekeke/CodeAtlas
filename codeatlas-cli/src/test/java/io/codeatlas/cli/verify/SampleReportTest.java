package io.codeatlas.cli.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 리포트 생성과 리포트용 보조 판정(깨진 파일의 진단, expected-unresolved.md 지점)의 단위 테스트. */
class SampleReportTest {

    private static final String UNRESOLVED_MD = """
            # 미해결로 나와야 하는 지점

            ## 1. 반드시 미해결이어야 하는 지점

            | # | 위치 | 내용 | 이유 | 있으면 안 되는 것 |
            |---|---|---|---|---|
            | U1 | `src/a/OrderDao.java:110` | `sqlSession.selectList(queryId, param)` | 구문 ID 가 메서드 인자다 | EXECUTES |
            | U2 | `src/a/OrderServiceImpl.java:169-170` | `"orderDao.select" + type` | 실행 시점 값 | EXECUTES |

            ## 2. 동적이지만 대상은 정해지는 지점

            | # | 위치 | 내용 | 기대 |
            |---|---|---|---|
            | D3 | 매퍼 XML 전반 | `<if>` | 미해결이 아니다 |
            | D4 | `src/web/detail.jsp:61`, `order/form.jsp:55`, React 의 템플릿 리터럴 URL | URL | `{*}` |
            """;

    private final GraphModel graph = new GraphModel();
    private VerificationResult result;

    @BeforeEach
    void setUp() {
        graph.project(new ProjectInfo("mini-shop", "/tmp/mini-shop", Instant.parse("2026-10-11T00:00:00Z")));
        for (String id : List.of("java:type:a.A", "java:type:a.B", "java:type:a.C", "java:method:a.OrderDao#selectByQueryId()", "db:table:T")) {
            graph.declare(Node.builder(id, NodeKind.fromId(id).orElseThrow()).build());
        }
        graph.addEdge(Edge.of("java:type:a.A", "java:type:a.B", EdgeKind.EXTENDS));
        graph.addEdge(Edge.builder("java:type:a.A", "java:type:a.C", EdgeKind.INJECTS).inferred().attr("via", "field").build());
        graph.addUnresolvedEdge("java:method:a.OrderDao#selectByQueryId()", EdgeKind.EXECUTES, "mybatis-statement", "queryId",
                SourceLocation.of("src/a/OrderDao.java", 110));
        graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "세미콜론 | 없음", SourceLocation.of("broken/Bad.java", 14)));
        graph.report(Diagnostic.info("EXCLUDED_DIRECTORY", "건너뜀", null));

        ExpectedSet expected = ExpectedSet.parse("""
                [{"from":"java:type:a.A","to":"java:type:a.B","kind":"EXTENDS","note":"A extends B"},
                 {"from":"java:type:a.B","to":"java:type:a.C","kind":"EXTENDS","note":"B는 C를 | 상속"},
                 {"from":"java:type:a.A","to":"java:type:a.C","kind":"INJECTS","attrs":{"via":"constructor"},"note":"생성자 주입"},
                 {"from":"java:type:a.A","to":"java:type:a.Z","kind":"IMPLEMENTS","certainty":"low","note":"라이브러리 타입"}]""",
                """
                [{"id":"db:table:T","kind":"DB_TABLE"},
                 {"id":"db:table:U","kind":"DB_TABLE","note":"없는 테이블"}]""",
                """
                [{"from":"java:type:a.A","to":"java:type:*","kind":"EXTENDS","note":"상속하면 안 된다"},
                 {"from":"*","to":"db:table:DUAL","kind":"*","note":"DUAL"}]""");
        result = new ExpectedChecker(graph, CheckOptions.defaults()).check(expected);
    }

    private SampleReport report() {
        return SampleReport.builder(graph, result)
                .mode("report")
                .components(List.of("analyzer java", "linker cross"))
                .gate("must-not 위반 0", false, "위반 1건")
                .gate("파이프라인 완료", true, "")
                .brokenFiles(DiagnosticChecks.parseFailures(graph, List.of("broken/Bad.java", "broken/bad.sql")))
                .unresolvedPoints(UnresolvedPoints.parse(UNRESOLVED_MD))
                .build();
    }

    @Test
    @DisplayName("깨진 파일 판정: 파일마다 PARSE_FAILED 또는 UNSUPPORTED_SYNTAX 진단이 있는지 본다")
    void parseFailures() {
        Map<String, List<Diagnostic>> found = DiagnosticChecks.parseFailures(graph, List.of("broken/Bad.java", "broken/bad.sql"));

        assertThat(found.keySet()).containsExactly("broken/Bad.java", "broken/bad.sql");
        assertThat(found.get("broken/Bad.java")).hasSize(1);
        assertThat(found.get("broken/bad.sql")).isEmpty();

        graph.report(Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "x", SourceLocation.of("broken/bad.sql", 6)));
        graph.report(Diagnostic.warn(DiagnosticCodes.UNRESOLVED_REFERENCE, "다른 코드는 치지 않는다", SourceLocation.of("broken/bad.sql", 7)));
        assertThat(DiagnosticChecks.parseFailures(graph, List.of("broken/bad.sql")).get("broken/bad.sql")).hasSize(1);
    }

    @Test
    @DisplayName("F-019-AC34 허용한 지점 밖의 PARSE_FAILED·UNSUPPORTED_SYNTAX: 같은 파일의 그 줄에서 시작한 진단만 허용하고, 다른 줄·다른 파일·위치 없는 진단은 밖으로 친다")
    void parseFailuresOutsideAllowedPoints() {
        List<UnresolvedPoints.Point> allowed = List.of(new UnresolvedPoints.Point("N1", "db/schema.sql", 7, 7, "REFRESH"),
                new UnresolvedPoints.Point("N2", "db/schema.sql", 20, 22, "여러 줄"));
        Diagnostic atPoint = Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "REFRESH", SourceLocation.of("db/schema.sql", 7));
        Diagnostic inRange = Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "범위 안", SourceLocation.of("db/schema.sql", 21));
        Diagnostic otherLine = Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "다른 줄", SourceLocation.of("db/schema.sql", 8));
        Diagnostic otherFile = Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "다른 파일", SourceLocation.of("other/db/schema.sql", 7));
        Diagnostic fileOnly = Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "줄 없음", SourceLocation.ofFile("db/schema.sql"));
        Diagnostic noLocation = Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "위치 없음", null);
        Diagnostic otherCode = Diagnostic.warn(DiagnosticCodes.UNRESOLVED_REFERENCE, "다른 코드는 보지 않는다", SourceLocation.of("db/schema.sql", 8));
        GraphModel g = new GraphModel();
        List.of(atPoint, inRange, otherLine, otherFile, fileOnly, noLocation, otherCode).forEach(g::report);

        assertThat(DiagnosticChecks.parseFailuresAt(g, allowed.get(0))).containsExactly(atPoint);
        assertThat(DiagnosticChecks.parseFailuresAt(g, allowed.get(1))).containsExactly(inRange);
        assertThat(DiagnosticChecks.parseFailuresOutside(g, allowed)).containsExactly(otherLine, otherFile, fileOnly, noLocation);
        // 허용 목록이 비면 전부 밖이다. 기존 그래프(broken/Bad.java의 PARSE_FAILED)로도 본다
        assertThat(DiagnosticChecks.parseFailuresOutside(g, List.of())).hasSize(6);
        assertThat(DiagnosticChecks.parseFailuresOutside(graph, allowed)).singleElement()
                .satisfies(d -> assertThat(d.location().file()).isEqualTo("broken/Bad.java"));
    }

    @Test
    @DisplayName("expected-unresolved.md의 표에서 지점(번호, 파일, 줄 범위)을 읽고, 근처의 미해결 엣지와 진단을 찾는다")
    void unresolvedPoints() {
        List<UnresolvedPoints.Point> points = UnresolvedPoints.parse(UNRESOLVED_MD);

        assertThat(points).extracting(UnresolvedPoints.Point::id).containsExactly("U1", "U2", "D4", "D4");
        assertThat(points.get(1)).satisfies(p -> {
            assertThat(p.file()).isEqualTo("src/a/OrderServiceImpl.java");
            assertThat(p.startLine()).isEqualTo(169);
            assertThat(p.endLine()).isEqualTo(170);
        });
        assertThat(points.get(3).file()).isEqualTo("order/form.jsp");

        UnresolvedPoints.Evidence evidence = UnresolvedPoints.evidence(graph, points.get(0));
        assertThat(evidence.unresolvedEdges()).singleElement().satisfies(e -> assertThat(e.kind()).isEqualTo(EdgeKind.EXECUTES));
        assertThat(evidence.diagnostics()).singleElement().satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE));
        assertThat(UnresolvedPoints.evidence(graph, points.get(1)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Markdown 리포트: 요약 표, 사유별 실패 목록, 함정 위반, 진단 집계, 미해결 목록이 들어간다")
    void markdown() {
        String md = report().markdown();

        assertThat(md).startsWith("# sample-shop 검증 리포트");
        assertThat(md).contains("mini-shop").contains("analyzer java");
        // 임계값 판정
        assertThat(md).contains("| must-not 위반 0 | 실패 | 위반 1건 |").contains("| 파이프라인 완료 | 통과 |");
        // 종류별 요약: 종류 | 통과 | INFERRED | 실패 | 전체 | 통과율
        assertThat(md).contains("| EXTENDS | 1 | 0 | 1 | 2 | 50.0% |");
        assertThat(md).contains("| INJECTS | 0 | 0 | 1 | 1 | 0.0% |");
        assertThat(md).contains("| **합계** | 1 | 0 | 2 | 3 | 33.3% |");
        assertThat(md).contains("| DB_TABLE | 1 | 0 | 1 | 2 | 50.0% |");
        assertThat(md).contains("엣지 존재만 본 통과율").contains("66.7%");
        // 실패 목록은 종류·사유별로 묶는다
        assertThat(md).contains("### EXTENDS — 양쪽 노드는 있는데 엣지 없음 (1)");
        assertThat(md).contains("| `java:type:a.B` | `java:type:a.C` | B는 C를 \\| 상속 |");
        assertThat(md).contains("### INJECTS — attrs 불일치 (1)").contains("via: 기대 constructor, 실제 field");
        assertThat(md).contains("### DB_TABLE — 노드 없음 (1)").contains("`db:table:U`");
        // low는 따로
        assertThat(md).contains("certainty=low").contains("라이브러리 타입");
        // 함정 위반
        assertThat(md).contains("상속하면 안 된다").contains("`java:type:a.A` → `java:type:a.B` EXTENDS RESOLVED");
        assertThat(md).doesNotContain("db:table:DUAL");
        // 진단
        assertThat(md).contains("| ERROR | PARSE_FAILED | 1 |").contains("| INFO | EXCLUDED_DIRECTORY | 1 |");
        assertThat(md).contains("| `broken/Bad.java` | 있음 |").contains("| `broken/bad.sql` | **없음** |");
        // 미해결 대조 보조
        assertThat(md).contains("| U1 | `src/a/OrderDao.java:110` |").contains("unresolved:mybatis-statement:queryId");
    }

    @Test
    @DisplayName("미해결 노드 분류: 마무리 단계가 만든 노드도 접두사가 아니라 분류(category)로 묶는다 (F-001-R38)")
    void danglingNodesAreGroupedByCategory() {
        graph.addEdge(Edge.of("java:method:a.OrderDao#selectByQueryId()", "mybatis:stmt:a.OrderMapper.missing", EdgeKind.EXECUTES));
        graph.addEdge(Edge.of("java:type:a.A", "java:type:a.Missing", EdgeKind.IMPLEMENTS));
        assertThat(graph.resolveDanglingEdges()).isEqualTo(2);

        String md = report().markdown();

        // 분석기가 남긴 unresolved:mybatis-statement:queryId와 마무리 단계가 만든 mybatis:stmt:… 노드가 한 분류로 모인다
        assertThat(md).contains("### 미해결 노드 (3)").contains("| mybatis-statement | 2 |").contains("| java-type | 1 |");
        assertThat(md).doesNotContain("매달린 엣지").doesNotContain("| mybatis:stmt |");
    }

    @Test
    @DisplayName("F-001-AC85 검증 리포트의 미해결 목록은 접두 일치로 풀린 참조(resolvedBy가 있는 UNRESOLVED 노드와 그리로 가는 엣지)를 빼고 뺀 수를 적는다. 머리의 그래프 수는 그대로다")
    void unresolvedListsExcludeReferencesResolvedElsewhere() throws Exception {
        graph.declare(Node.builder("mybatis:stmt:a.OrderMapper.selectA", NodeKind.SQL_STATEMENT).build());
        SourceLocation site = SourceLocation.of("src/a/OrderDao.java", 108);
        graph.addUnresolvedEdge("java:method:a.OrderDao#selectByQueryId()", EdgeKind.EXECUTES, "mybatis-statement", "a.OrderMapper.select{*}", site);
        String resolved = "unresolved:mybatis-statement:a.OrderMapper.select{*}";

        String before = report().markdown();
        assertThat(before).contains("UNRESOLVED 2)").contains("### 미해결 노드 (2)").contains("### UNRESOLVED 엣지 (2)").doesNotContain("resolvedBy");

        graph.addEdge(Edge.builder("java:method:a.OrderDao#selectByQueryId()", "mybatis:stmt:a.OrderMapper.selectA", EdgeKind.EXECUTES).inferred()
                .location(site).attr("prefixMatch", true).attr("resolvedFrom", resolved).build());
        graph.putAttrs(resolved, Map.of("resolvedBy", "prefixMatch"));

        String md = report().markdown();
        // 머리의 수는 그래프 그대로다
        assertThat(md).contains("UNRESOLVED 2)");
        assertThat(md).contains("### 미해결 노드 (1)\n\n같은 지점에서 다른 방법으로 풀린 것(`resolvedBy`) 1개는 뺐다.\n")
                .contains("### UNRESOLVED 엣지 (1)\n\n같은 지점에서 다른 방법으로 풀린 참조(`resolvedBy`)로 가는 1건은 뺐다.\n");
        assertThat(md).contains("- `unresolved:mybatis-statement:queryId`").doesNotContain("- `" + resolved + "`");
        // 지점 근처의 미해결 엣지에도 풀린 것은 싣지 않는다
        assertThat(md).contains("| U1 | `src/a/OrderDao.java:110` | EXECUTES → unresolved:mybatis-statement:queryId @110 |");

        JsonNode json = new ObjectMapper().readTree(report().json());
        assertThat(json.get("unresolvedNodes")).hasSize(1);
        assertThat(json.get("unresolvedNodes").get(0).asText()).isEqualTo("unresolved:mybatis-statement:queryId");
        assertThat(json.get("unresolvedEdges")).hasSize(1);
        assertThat(json.get("resolvedElsewhereNodes").asInt()).isEqualTo(1);
        assertThat(json.get("resolvedElsewhereEdges").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("JSON 리포트: 집계, 항목별 판정, 위반, 진단이 기계가 읽을 수 있는 형태로 들어간다")
    void json() throws Exception {
        JsonNode root = new ObjectMapper().readTree(report().json());

        assertThat(root.path("project").path("name").asText()).isEqualTo("mini-shop");
        assertThat(root.path("mode").asText()).isEqualTo("report");
        assertThat(root.path("gates").get(0).path("passed").asBoolean()).isFalse();
        JsonNode summary = root.path("summary");
        assertThat(summary.path("edges").path("total").asInt()).isEqualTo(3);
        assertThat(summary.path("edges").path("pass").asInt()).isEqualTo(1);
        assertThat(summary.path("edges").path("passRate").asDouble()).isCloseTo(33.33, org.assertj.core.data.Offset.offset(0.01));
        assertThat(summary.path("edgesExistenceOnly").path("inferredPass").asInt()).isEqualTo(1);
        assertThat(summary.path("nodes").path("fail").asInt()).isEqualTo(1);
        assertThat(summary.path("lowEdges").path("total").asInt()).isEqualTo(1);
        assertThat(summary.path("mustNotTotal").asInt()).isEqualTo(2);
        assertThat(summary.path("mustNotViolations").asInt()).isEqualTo(1);
        assertThat(root.path("edgesByKind").path("EXTENDS").path("total").asInt()).isEqualTo(2);
        assertThat(root.path("edgeFailuresByReason").path("ATTR_MISMATCH").asInt()).isEqualTo(1);
        assertThat(root.path("edgeResults")).hasSize(4);
        assertThat(root.path("edgeResults").get(2).path("reason").asText()).isEqualTo("ATTR_MISMATCH");
        assertThat(root.path("edgeResults").get(2).path("matched").path("confidence").asText()).isEqualTo("INFERRED");
        assertThat(root.path("nodeResults").get(1).path("verdict").asText()).isEqualTo("FAIL");
        assertThat(root.path("violations").get(0).path("edges").get(0).path("to").asText()).isEqualTo("java:type:a.B");
        assertThat(root.path("diagnosticsByCode")).hasSize(3);
        assertThat(root.path("brokenFiles").get(1).path("reported").asBoolean()).isFalse();
        assertThat(root.path("unresolvedNodes").get(0).asText()).isEqualTo("unresolved:mybatis-statement:queryId");
    }

    @Test
    @DisplayName("리포트 파일 쓰기: sample-shop-report.md와 sample-shop-report.json을 만든다")
    void writesFiles(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("target");

        report().write(target);

        assertThat(Files.readString(target.resolve("sample-shop-report.md"))).contains("# sample-shop 검증 리포트");
        assertThat(new ObjectMapper().readTree(target.resolve("sample-shop-report.json").toFile()).path("summary").isObject()).isTrue();
    }

    @Test
    @DisplayName("F-019-AC34 샘플 이름을 주면 제목과 파일 이름이 그 이름을 따른다. 주지 않으면 sample-shop이다")
    void sampleNameGoesIntoTitleAndFileNames(@TempDir Path dir) throws Exception {
        SampleReport pg = SampleReport.builder(graph, result).sample("sample-pg").mode("report").build();

        assertThat(pg.markdown()).startsWith("# sample-pg 검증 리포트\n");
        assertThat(pg.markdownFile()).isEqualTo("sample-pg-report.md").isEqualTo(SampleReport.markdownFile("sample-pg"));
        assertThat(pg.jsonFile()).isEqualTo("sample-pg-report.json").isEqualTo(SampleReport.jsonFile("sample-pg"));
        pg.write(dir);
        report().write(dir);
        assertThat(Files.readString(dir.resolve("sample-pg-report.md"))).startsWith("# sample-pg 검증 리포트");
        assertThat(dir.resolve("sample-pg-report.json")).isRegularFile();
        // 이름을 주지 않은 리포트는 전과 같은 이름으로 따로 남는다
        assertThat(report().markdownFile()).isEqualTo("sample-shop-report.md");
        assertThat(Files.readString(dir.resolve("sample-shop-report.md"))).startsWith("# sample-shop 검증 리포트");
        assertThat(dir.resolve("sample-shop-report.json")).isRegularFile();
    }

    @Test
    @DisplayName("F-019-AC34 일부러 넣은 미지원 구문 목록을 주면 지점마다 남은 진단과 목록 밖의 PARSE_FAILED·UNSUPPORTED_SYNTAX를 싣는다. 주지 않으면 그 절이 없다")
    void allowedParseFailuresSection() throws Exception {
        graph.report(Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "REFRESH는 읽지 않는다", SourceLocation.of("db/schema.sql", 7)));
        List<UnresolvedPoints.Point> allowed = List.of(new UnresolvedPoints.Point("N1", "db/schema.sql", 7, 7, "REFRESH"),
                new UnresolvedPoints.Point("N2", "db/schema.sql", 30, 30, "조용히 넘긴 것"));

        SampleReport with = SampleReport.builder(graph, result).sample("sample-pg").allowedParseFailures(allowed).build();
        String md = with.markdown();

        assertThat(md).contains("### 일부러 넣은 미지원 구문");
        assertThat(md).contains("| N1 | `db/schema.sql:7` | UNSUPPORTED_SYNTAX REFRESH는 읽지 않는다 |");
        assertThat(md).contains("| N2 | `db/schema.sql:30` | 없음 |");
        assertThat(md).contains("#### 목록 밖의 PARSE_FAILED·UNSUPPORTED_SYNTAX (1)");
        assertThat(md).contains("| PARSE_FAILED | `broken/Bad.java:14` | 세미콜론 \\| 없음 |");
        // 깨진 입력 파일을 주지 않았으면 그 표는 없다
        assertThat(md).doesNotContain("### 깨진 입력 파일");
        JsonNode json = new ObjectMapper().readTree(with.json());
        assertThat(json.path("allowedParseFailures")).hasSize(2);
        assertThat(json.path("allowedParseFailures").get(0).path("id").asText()).isEqualTo("N1");
        assertThat(json.path("allowedParseFailures").get(0).path("diagnostics")).hasSize(1);
        assertThat(json.path("allowedParseFailures").get(1).path("diagnostics")).isEmpty();
        assertThat(json.path("unexpectedParseFailures")).hasSize(1);
        assertThat(json.path("unexpectedParseFailures").get(0).path("location").asText()).isEqualTo("broken/Bad.java:14");

        // 목록을 주지 않은 리포트(sample-shop)는 전과 같다
        assertThat(report().markdown()).doesNotContain("일부러 넣은 미지원 구문").contains("### 깨진 입력 파일");
        JsonNode plain = new ObjectMapper().readTree(report().json());
        assertThat(plain.has("allowedParseFailures")).isFalse();
        assertThat(plain.has("unexpectedParseFailures")).isFalse();
    }
}
