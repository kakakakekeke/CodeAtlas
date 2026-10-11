package io.codeatlas.cli.verify;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.cli.RecordingAnalyzer;
import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.pipeline.Pipeline;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.Linker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * sample-shop 통합 검증. Pipeline API로 {@code samples/sample-shop}을 분석하고 {@code expected/}의 정답과 대조해
 * {@code target/sample-shop-report.md}, {@code target/sample-shop-report.json}, {@code target/sample-shop-graph.json}을 쓴 뒤
 * 임계값으로 단언한다. failsafe(verify 단계)에서만 돈다.
 *
 * <pre>
 * mvn -q -pl codeatlas-cli verify                              # 임계값으로 단언 (기본)
 * mvn -q -pl codeatlas-cli verify -Dsample.verify=report       # 단언 없이 리포트만 쓰고 통과 (진행 상황 측정)
 * </pre>
 *
 * 시스템 프로퍼티 (괄호 안은 기본값)
 * <ul>
 *   <li>{@code sample.verify} (assert): {@code assert} 또는 {@code report}</li>
 *   <li>{@code sample.verify.minEdgePassRate} (100): must-have 엣지 통과율 하한(%). certainty=low 제외</li>
 *   <li>{@code sample.verify.minNodePassRate} (100): must-have 노드 통과율 하한(%). certainty=low 제외</li>
 *   <li>{@code sample.verify.maxViolations} (0): 허용하는 함정 위반 수. certainty=low 제외</li>
 *   <li>{@code sample.verify.edgeAttrs} (strict): {@code strict}면 엣지의 attrs 불일치를 실패로, {@code lenient}면 통과로 본다</li>
 *   <li>{@code sample.project} (../samples/sample-shop), {@code sample.report.dir} (target)</li>
 * </ul>
 */
class SampleShopIT {

    /** 파일 단위 실패로 기록돼야 하는 깨진 입력(samples/sample-shop/README.md 3절). */
    static final List<String> BROKEN_FILES = List.of("broken/BrokenService.java", "broken/broken_query.sql", "broken/BrokenMapper.xml");

    static final String GRAPH_FILE = "sample-shop-graph.json";

    private static double percentProperty(String name, double defaultValue) {
        return Double.parseDouble(System.getProperty(name, String.valueOf(defaultValue)));
    }

    @Test
    @DisplayName("sample-shop: 파이프라인이 끝까지 돌고, 깨진 파일은 진단으로 남고, 함정 위반이 없고, must-have가 전부 있다")
    void sampleShopMeetsExpectations() throws Exception {
        Path project = Path.of(System.getProperty("sample.project", "../samples/sample-shop")).toAbsolutePath().normalize();
        Path reportDir = Path.of(System.getProperty("sample.report.dir", "target")).toAbsolutePath().normalize();
        String mode = System.getProperty("sample.verify", "assert").toLowerCase(Locale.ROOT);
        double minEdgePassRate = percentProperty("sample.verify.minEdgePassRate", 100);
        double minNodePassRate = percentProperty("sample.verify.minNodePassRate", 100);
        int maxViolations = Integer.getInteger("sample.verify.maxViolations", 0);
        CheckOptions options = new CheckOptions(!"lenient".equalsIgnoreCase(System.getProperty("sample.verify.edgeAttrs", "strict")));
        assertThat(mode).as("sample.verify").isIn("assert", "report");
        assertThat(project.resolve("expected")).as("샘플 프로젝트의 정답 폴더").isDirectory();

        // 1. 분석: 실행 jar가 아니라 Pipeline API로. 테스트용 분석기는 빼고 출력기는 돌리지 않는다.
        Pipeline discovered = Pipeline.discover();
        List<Analyzer> analyzers = discovered.analyzers().stream().filter(a -> !(a instanceof RecordingAnalyzer)).toList();
        Pipeline pipeline = new Pipeline(analyzers, discovered.linkers(), List.of());
        List<String> components = new ArrayList<>();
        pipeline.analyzers().forEach(a -> components.add("분석기 " + a.name() + "(" + a.order() + ")"));
        pipeline.linkers().forEach(l -> components.add("링커 " + l.name() + "(" + l.order() + ")"));
        List<String> loadFailures = serviceLoadFailures();

        GraphModel graph;
        Throwable crash = null;
        try {
            graph = pipeline.analyze(project, AnalysisOptions.defaults()).graph();
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            crash = e;
            graph = new GraphModel();
        }
        Files.createDirectories(reportDir);
        GraphJson.write(graph, reportDir.resolve(GRAPH_FILE));

        // 2. 대조
        ExpectedSet expected = ExpectedSet.load(project.resolve("expected"));
        VerificationResult result = new ExpectedChecker(graph, options).check(expected);
        Map<String, List<Diagnostic>> broken = DiagnosticChecks.parseFailures(graph, BROKEN_FILES);
        List<Diagnostic> componentFailures = DiagnosticChecks.componentFailures(graph);
        List<String> silentlySkipped = broken.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();

        // 3. 리포트
        List<String> pipelineProblems = new ArrayList<>();
        if (crash != null) {
            pipelineProblems.add("파이프라인 예외: " + crash);
        }
        loadFailures.forEach(f -> pipelineProblems.add("구현체 탐색 실패: " + f));
        componentFailures.forEach(d -> pipelineProblems.add(d.code() + ": " + d.message()));
        Tally edges = result.edgeTally();
        Tally nodes = result.nodeTally();
        int violations = result.hardViolations().size();
        SampleReport report = SampleReport.builder(graph, result)
                .mode(mode + (options.strictEdgeAttrs() ? "" : ", edgeAttrs=lenient"))
                .components(components)
                .gate("파이프라인이 예외 없이 끝남", pipelineProblems.isEmpty(), String.join("; ", pipelineProblems))
                .gate("broken/ 파일 " + BROKEN_FILES.size() + "개가 각각 진단으로 남음", silentlySkipped.isEmpty(),
                        silentlySkipped.isEmpty() ? "" : "진단 없음: " + String.join(", ", silentlySkipped))
                .gate("must-not 위반 ≤ " + maxViolations, violations <= maxViolations, "위반 " + violations + " / 함정 " + result.mustNotTotal())
                .gate(String.format(Locale.ROOT, "must-have 엣지 통과율 ≥ %.1f%% (certainty=low 제외)", minEdgePassRate), edges.passRate() >= minEdgePassRate,
                        String.format(Locale.ROOT, "%d/%d (%.1f%%)", edges.passed(), edges.total(), edges.passRate()))
                .gate(String.format(Locale.ROOT, "must-have 노드 통과율 ≥ %.1f%% (certainty=low 제외)", minNodePassRate), nodes.passRate() >= minNodePassRate,
                        String.format(Locale.ROOT, "%d/%d (%.1f%%)", nodes.passed(), nodes.total(), nodes.passRate()))
                .brokenFiles(broken)
                .unresolvedPoints(UnresolvedPoints.parse(Files.readString(project.resolve("expected/expected-unresolved.md"), StandardCharsets.UTF_8)))
                .build();
        report.write(reportDir);
        Path reportFile = reportDir.resolve(SampleReport.MARKDOWN_FILE);
        System.out.printf(Locale.ROOT, "[sample-shop] 엣지 %d/%d (%.1f%%), 노드 %d/%d (%.1f%%), 함정 위반 %d, low 엣지 %d/%d, low 노드 %d/%d → %s%n",
                edges.passed(), edges.total(), edges.passRate(), nodes.passed(), nodes.total(), nodes.passRate(), violations,
                result.lowEdgeTally().passed(), result.lowEdgeTally().total(), result.lowNodeTally().passed(), result.lowNodeTally().total(), reportFile);

        // 4. 단언 (report 모드에서는 리포트만 쓰고 통과)
        if (mode.equals("report")) {
            return;
        }
        List<String> failed = report.gates().stream().filter(g -> !g.passed()).map(g -> g.name() + " — " + g.detail()).toList();
        assertThat(failed).as("sample-shop 임계값 미달. 리포트: %s", reportFile).isEmpty();
    }

    /** Pipeline이 구현체를 만들지 못한 것은 직접 만든 파이프라인에는 진단으로 남지 않으므로 여기서 따로 본다. */
    private static List<String> serviceLoadFailures() {
        List<String> failures = new ArrayList<>();
        for (Class<?> type : List.of(Analyzer.class, Linker.class)) {
            Iterator<?> iterator = ServiceLoader.load(type).iterator();
            for (int errors = 0; errors < 100; ) {
                try {
                    if (!iterator.hasNext()) {
                        break;
                    }
                    iterator.next();
                } catch (ServiceConfigurationError e) {
                    errors++;
                    failures.add(type.getSimpleName() + ": " + e.getMessage());
                }
            }
        }
        return failures;
    }
}
