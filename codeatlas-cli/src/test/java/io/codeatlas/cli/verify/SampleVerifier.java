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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * 검증용 샘플 하나의 정답 대조. Pipeline API로 샘플을 옵션 없이 분석하고 {@code expected/}의 정답과 대조해
 * {@code <샘플 이름>-report.md}, {@code <샘플 이름>-report.json}, {@code <샘플 이름>-graph.json}을 쓴 뒤 임계값으로 단언한다.
 * {@code SampleShopIT}와 {@code SamplePgIT}가 같이 쓴다. 샘플마다 다른 것(이름, 깨진 파일 목록, 진단 허용 목록, 따로 보는 기대)만 받는다.
 *
 * <pre>{@code
 * SampleVerifier.of("sample-pg").parseFailuresOnlyAt("N").check(ExtraCheck.nodeWithoutAttr(id, "language"))
 *         .verify(SampleVerifier.Settings.fromSystemProperties("sample.pg.project", "../samples/sample-pg"));
 * }</pre>
 */
final class SampleVerifier {

    /** 임계값 표의 칸에 싣는 목록 밖 진단의 수. 전체는 리포트의 진단 절과 단언 메시지에 있다. */
    private static final int GATE_DETAIL_LIMIT = 5;

    /**
     * 실행 설정. 두 샘플이 같은 시스템 프로퍼티를 쓰고 프로젝트 경로의 프로퍼티만 다르다.
     *
     * @param mode {@code assert}(임계값으로 단언) 또는 {@code report}(단언 없이 리포트만)
     * @param minEdgePassRate must-have 엣지 통과율 하한(%). certainty=low 제외
     * @param minNodePassRate must-have 노드 통과율 하한(%). certainty=low 제외
     * @param maxViolations 허용하는 함정 위반 수. certainty=low 제외
     */
    record Settings(Path project, Path reportDir, String mode, double minEdgePassRate, double minNodePassRate, int maxViolations, CheckOptions options) {

        /**
         * 시스템 프로퍼티 (괄호 안은 기본값)
         * <ul>
         *   <li>{@code sample.verify} (assert): {@code assert} 또는 {@code report}</li>
         *   <li>{@code sample.verify.minEdgePassRate} (100), {@code sample.verify.minNodePassRate} (100), {@code sample.verify.maxViolations} (0)</li>
         *   <li>{@code sample.verify.edgeAttrs} (strict): {@code strict}면 엣지의 attrs 불일치를 실패로, {@code lenient}면 통과로 본다</li>
         *   <li>{@code projectProperty} ({@code defaultProject}): 샘플 프로젝트의 경로</li>
         *   <li>{@code sample.report.dir} (target)</li>
         * </ul>
         */
        static Settings fromSystemProperties(String projectProperty, String defaultProject) {
            return new Settings(
                    Path.of(System.getProperty(projectProperty, defaultProject)).toAbsolutePath().normalize(),
                    Path.of(System.getProperty("sample.report.dir", "target")).toAbsolutePath().normalize(),
                    System.getProperty("sample.verify", "assert").toLowerCase(Locale.ROOT),
                    percentProperty("sample.verify.minEdgePassRate", 100),
                    percentProperty("sample.verify.minNodePassRate", 100),
                    Integer.getInteger("sample.verify.maxViolations", 0),
                    new CheckOptions(!"lenient".equalsIgnoreCase(System.getProperty("sample.verify.edgeAttrs", "strict"))));
        }

        private static double percentProperty(String name, double defaultValue) {
            return Double.parseDouble(System.getProperty(name, String.valueOf(defaultValue)));
        }
    }

    private final String name;
    private List<String> brokenFiles = List.of();
    private String allowedParseFailurePrefix;
    private final List<ExtraCheck> checks = new ArrayList<>();

    private SampleVerifier(String name) {
        this.name = name;
    }

    /** @param name 샘플 이름. 산출물 파일 이름, 리포트 제목, 출력의 머리에 들어간다 */
    static SampleVerifier of(String name) {
        return new SampleVerifier(name);
    }

    /** 파일 단위 실패로 기록돼야 하는 깨진 입력. 주면 "파일마다 진단이 남음"이 기준에 들어간다. */
    SampleVerifier brokenFiles(List<String> files) {
        this.brokenFiles = List.copyOf(files);
        return this;
    }

    /**
     * {@code PARSE_FAILED}·{@code UNSUPPORTED_SYNTAX}가 샘플의 "일부러 넣은 미지원 구문" 목록에 있는 위치에만 있어야 한다(F-019-AC34).
     * 목록은 {@code expected/expected-unresolved.md}의 표에서 번호가 이 접두사로 시작하는 행이다(sample-pg는 {@code N}).
     */
    SampleVerifier parseFailuresOnlyAt(String pointIdPrefix) {
        this.allowedParseFailurePrefix = pointIdPrefix;
        return this;
    }

    /** 정답 JSON으로 적지 못한 기대를 기준에 더한다. */
    SampleVerifier check(ExtraCheck check) {
        checks.add(check);
        return this;
    }

    /** 샘플을 옵션 없이 분석하고 대조한다. 단언 모드에서 기준에 못 미치면 {@link AssertionError}다. */
    SampleReport verify(Settings settings) throws IOException {
        assertThat(settings.mode()).as("sample.verify").isIn("assert", "report");
        assertThat(settings.project().resolve("expected")).as("샘플 프로젝트의 정답 폴더").isDirectory();

        // 분석: 실행 jar가 아니라 Pipeline API로. 테스트용 분석기는 빼고 출력기는 돌리지 않는다.
        Pipeline discovered = Pipeline.discover();
        List<Analyzer> analyzers = discovered.analyzers().stream().filter(a -> !(a instanceof RecordingAnalyzer)).toList();
        Pipeline pipeline = new Pipeline(analyzers, discovered.linkers(), List.of());
        List<String> components = new ArrayList<>();
        pipeline.analyzers().forEach(a -> components.add("분석기 " + a.name() + "(" + a.order() + ")"));
        pipeline.linkers().forEach(l -> components.add("링커 " + l.name() + "(" + l.order() + ")"));
        List<String> pipelineProblems = new ArrayList<>();
        List<String> loadFailures = serviceLoadFailures();

        GraphModel graph;
        try {
            graph = pipeline.analyze(settings.project(), AnalysisOptions.defaults()).graph();
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            pipelineProblems.add("파이프라인 예외: " + e);
            graph = new GraphModel();
        }
        loadFailures.forEach(f -> pipelineProblems.add("구현체 탐색 실패: " + f));
        return evaluate(graph, pipelineProblems, components, settings);
    }

    /**
     * 분석한 그래프를 정답과 대조해 그래프와 리포트를 쓰고, 단언 모드면 기준으로 단언한다.
     *
     * @param pipelineProblems 분석이 예외로 끝났거나 구현체를 만들지 못한 내용. 분석기·링커의 실패 진단은 여기서 그래프를 보고 더한다
     */
    SampleReport evaluate(GraphModel graph, List<String> pipelineProblems, List<String> components, Settings settings) throws IOException {
        Path project = settings.project();
        Path reportDir = settings.reportDir();
        Files.createDirectories(reportDir);
        GraphJson.write(graph, reportDir.resolve(name + "-graph.json"));

        // 대조
        ExpectedSet expected = ExpectedSet.load(project.resolve("expected"));
        CheckOptions options = settings.options();
        VerificationResult result = new ExpectedChecker(graph, options).check(expected);
        List<UnresolvedPoints.Point> points =
                UnresolvedPoints.parse(Files.readString(project.resolve("expected/expected-unresolved.md"), StandardCharsets.UTF_8));
        List<String> problems = new ArrayList<>(pipelineProblems);
        DiagnosticChecks.componentFailures(graph).forEach(d -> problems.add(d.code() + ": " + d.message()));
        Tally edges = result.edgeTally();
        Tally nodes = result.nodeTally();
        int violations = result.hardViolations().size();
        /* 기준의 이름 → 단언 메시지에 실을 내용. 표의 칸에는 줄여서 싣는 것만 여기에 다 적는다. */
        Map<String, String> fullDetails = new LinkedHashMap<>();

        // 리포트
        SampleReport.Builder builder = SampleReport.builder(graph, result)
                .sample(name)
                .mode(settings.mode() + (options.strictEdgeAttrs() ? "" : ", edgeAttrs=lenient"))
                .components(components)
                .gate("파이프라인이 예외 없이 끝남", problems.isEmpty(), String.join("; ", problems));
        if (!brokenFiles.isEmpty()) {
            Map<String, List<Diagnostic>> broken = DiagnosticChecks.parseFailures(graph, brokenFiles);
            List<String> silentlySkipped = broken.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList();
            builder.gate("broken/ 파일 " + brokenFiles.size() + "개가 각각 진단으로 남음", silentlySkipped.isEmpty(),
                    silentlySkipped.isEmpty() ? "" : "진단 없음: " + String.join(", ", silentlySkipped));
            builder.brokenFiles(broken);
        }
        if (allowedParseFailurePrefix != null) {
            List<UnresolvedPoints.Point> allowed = points.stream().filter(p -> p.id().startsWith(allowedParseFailurePrefix)).toList();
            List<String> outside = DiagnosticChecks.parseFailuresOutside(graph, allowed).stream()
                    .map(d -> d.code() + " " + (d.location() == null ? "(위치 없음)" : d.location()) + " " + d.message()).toList();
            String gate = "PARSE_FAILED·UNSUPPORTED_SYNTAX가 일부러 넣은 미지원 구문("
                    + String.join(", ", allowed.stream().map(p -> p.id() + " " + p.label()).toList()) + ")에만 있음";
            builder.gate(gate, outside.isEmpty(), outside.isEmpty() ? "" : "목록 밖 " + outside.size() + "건: " + joinLimited(outside));
            builder.allowedParseFailures(allowed);
            if (!outside.isEmpty()) {
                fullDetails.put(gate, "목록 밖 " + outside.size() + "건:\n    " + String.join("\n    ", outside));
            }
        }
        builder.gate("must-not 위반 ≤ " + settings.maxViolations(), violations <= settings.maxViolations(), "위반 " + violations + " / 함정 " + result.mustNotTotal())
                .gate(String.format(Locale.ROOT, "must-have 엣지 통과율 ≥ %.1f%% (certainty=low 제외)", settings.minEdgePassRate()),
                        edges.passRate() >= settings.minEdgePassRate(),
                        String.format(Locale.ROOT, "%d/%d (%.1f%%)", edges.passed(), edges.total(), edges.passRate()))
                .gate(String.format(Locale.ROOT, "must-have 노드 통과율 ≥ %.1f%% (certainty=low 제외)", settings.minNodePassRate()),
                        nodes.passRate() >= settings.minNodePassRate(),
                        String.format(Locale.ROOT, "%d/%d (%.1f%%)", nodes.passed(), nodes.total(), nodes.passRate()));
        for (ExtraCheck check : checks) {
            ExtraCheck.Outcome outcome = check.test().apply(graph);
            builder.gate(check.name(), outcome.passed(), outcome.detail());
        }
        SampleReport report = builder.unresolvedPoints(points).build();
        report.write(reportDir);
        Path reportFile = reportDir.resolve(report.markdownFile());
        System.out.printf(Locale.ROOT, "[%s] 엣지 %d/%d (%.1f%%), 노드 %d/%d (%.1f%%), 함정 위반 %d, low 엣지 %d/%d, low 노드 %d/%d → %s%n",
                name, edges.passed(), edges.total(), edges.passRate(), nodes.passed(), nodes.total(), nodes.passRate(), violations,
                result.lowEdgeTally().passed(), result.lowEdgeTally().total(), result.lowNodeTally().passed(), result.lowNodeTally().total(), reportFile);

        // 단언 (report 모드에서는 리포트만 쓰고 통과)
        if (settings.mode().equals("report")) {
            return report;
        }
        List<String> failed = report.gates().stream().filter(g -> !g.passed())
                .map(g -> g.name() + " — " + fullDetails.getOrDefault(g.name(), g.detail())).toList();
        assertThat(failed).as("%s 임계값 미달. 리포트: %s", name, reportFile).isEmpty();
        return report;
    }

    private static String joinLimited(List<String> items) {
        String joined = String.join("; ", items.subList(0, Math.min(items.size(), GATE_DETAIL_LIMIT)));
        return items.size() > GATE_DETAIL_LIMIT ? joined + " … 외 " + (items.size() - GATE_DETAIL_LIMIT) + "건" : joined;
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
