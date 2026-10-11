package io.codeatlas.cli.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.model.SourceLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 대조 결과 리포트. 사람이 읽는 Markdown({@value #MARKDOWN_FILE})과 기계가 읽는 JSON({@value #JSON_FILE})을 만든다.
 *
 * <pre>{@code
 * SampleReport.builder(graph, result).mode("report").gate("must-not 위반 0", true, "").build().write(targetDir);
 * }</pre>
 */
public final class SampleReport {

    public static final String MARKDOWN_FILE = "sample-shop-report.md";
    public static final String JSON_FILE = "sample-shop-report.json";

    /** Markdown에서 목록 하나에 싣는 최대 줄 수. 넘는 것은 JSON에만 있다. */
    public static final int DEFAULT_MAX_LIST = 300;

    /** 임계값 하나의 판정. */
    public record Gate(String name, boolean passed, String detail) {
    }

    private final GraphModel graph;
    private final VerificationResult result;
    private final String mode;
    private final List<String> components;
    private final List<Gate> gates;
    private final Map<String, List<Diagnostic>> brokenFiles;
    private final List<UnresolvedPoints.Point> unresolvedPoints;
    private final int maxList;

    private SampleReport(Builder builder) {
        this.graph = builder.graph;
        this.result = builder.result;
        this.mode = builder.mode;
        this.components = List.copyOf(builder.components);
        this.gates = List.copyOf(builder.gates);
        this.brokenFiles = new LinkedHashMap<>(builder.brokenFiles);
        this.unresolvedPoints = List.copyOf(builder.unresolvedPoints);
        this.maxList = builder.maxList;
    }

    public static Builder builder(GraphModel graph, VerificationResult result) {
        return new Builder(graph, result);
    }

    public static final class Builder {
        private final GraphModel graph;
        private final VerificationResult result;
        private String mode = "assert";
        private List<String> components = List.of();
        private final List<Gate> gates = new ArrayList<>();
        private Map<String, List<Diagnostic>> brokenFiles = Map.of();
        private List<UnresolvedPoints.Point> unresolvedPoints = List.of();
        private int maxList = DEFAULT_MAX_LIST;

        private Builder(GraphModel graph, VerificationResult result) {
            this.graph = graph;
            this.result = result;
        }

        /** 실행 모드 표시({@code assert} 또는 {@code report}). */
        public Builder mode(String mode) {
            this.mode = mode;
            return this;
        }

        /** 실행한 분석기·링커 이름. 어떤 모듈이 아직 비어 있는지 리포트에서 보이게 한다. */
        public Builder components(List<String> components) {
            this.components = components;
            return this;
        }

        public Builder gate(String name, boolean passed, String detail) {
            gates.add(new Gate(name, passed, detail == null ? "" : detail));
            return this;
        }

        /** {@link DiagnosticChecks#parseFailures}의 결과. */
        public Builder brokenFiles(Map<String, List<Diagnostic>> brokenFiles) {
            this.brokenFiles = brokenFiles;
            return this;
        }

        public Builder unresolvedPoints(List<UnresolvedPoints.Point> points) {
            this.unresolvedPoints = points;
            return this;
        }

        public Builder maxList(int maxList) {
            this.maxList = maxList;
            return this;
        }

        public SampleReport build() {
            return new SampleReport(this);
        }
    }

    public List<Gate> gates() {
        return gates;
    }

    /** 두 파일을 쓴다. 디렉터리가 없으면 만든다. */
    public void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MARKDOWN_FILE), markdown(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(JSON_FILE), json(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ Markdown

    public String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# sample-shop 검증 리포트\n\n");
        header(sb);
        gatesSection(sb);
        summarySection(sb);
        failureSection(sb);
        passNotesSection(sb);
        violationSection(sb);
        diagnosticSection(sb);
        unresolvedSection(sb);
        return sb.toString();
    }

    private void header(StringBuilder sb) {
        ProjectInfo project = graph.project();
        if (project != null) {
            sb.append("- 프로젝트: ").append(project.name()).append(" (`").append(project.root()).append("`)\n");
            sb.append("- 분석 시각: ").append(project.analyzedAt()).append('\n');
        }
        sb.append("- 모드: ").append(mode).append('\n');
        sb.append("- 실행한 분석기·링커: ").append(components.isEmpty() ? "(없음)" : String.join(", ", components)).append('\n');
        Map<Confidence, Integer> byConfidence = confidenceCounts();
        sb.append("- 그래프: 노드 ").append(graph.nodeCount()).append(", 엣지 ").append(graph.edgeCount())
                .append(" (RESOLVED ").append(byConfidence.get(Confidence.RESOLVED))
                .append(", INFERRED ").append(byConfidence.get(Confidence.INFERRED))
                .append(", UNRESOLVED ").append(byConfidence.get(Confidence.UNRESOLVED))
                .append("), 진단 ").append(graph.diagnostics().size()).append("\n\n");
    }

    private Map<Confidence, Integer> confidenceCounts() {
        Map<Confidence, Integer> counts = new EnumMap<>(Confidence.class);
        for (Confidence c : Confidence.values()) {
            counts.put(c, 0);
        }
        for (Edge edge : graph.edges()) {
            counts.merge(edge.confidence(), 1, Integer::sum);
        }
        return counts;
    }

    private void gatesSection(StringBuilder sb) {
        if (gates.isEmpty()) {
            return;
        }
        sb.append("## 임계값 판정\n\n| 기준 | 판정 | 내용 |\n|---|---|---|\n");
        for (Gate gate : gates) {
            sb.append("| ").append(cell(gate.name())).append(" | ").append(gate.passed() ? "통과" : "실패").append(" | ").append(cell(gate.detail())).append(" |\n");
        }
        sb.append('\n');
    }

    private void summarySection(StringBuilder sb) {
        sb.append("## 요약\n\n");
        sb.append("`certainty=low` 항목은 아래 표에 넣지 않고 따로 센다. INFERRED는 신뢰도가 INFERRED인 엣지로 통과한 수다(통과율에 넣는다).\n\n");
        sb.append("### must-have 엣지 (종류별)\n\n");
        tallyTable(sb, "종류", result.edgesByKind(), result.edgeTally());
        Tally existence = result.edgeExistenceTally();
        sb.append("\n- 엣지 존재만 본 통과율(attrs 불일치를 통과로 셈): ").append(existence.passed()).append('/').append(existence.total())
                .append(" (").append(percent(existence)).append(")\n");
        sb.append("- 느슨한 규칙(fromRoutine, fromFile의 줄, statement 불일치)으로 통과한 항목: ").append(result.looseEdgePasses()).append("\n\n");
        sb.append("### must-have 노드 (종류별)\n\n");
        tallyTable(sb, "종류", result.nodesByKind(), result.nodeTally());
        sb.append("\n### certainty=low 항목 (리포트만, 임계값에 넣지 않는다)\n\n| 대상 | 통과 | INFERRED | 실패 | 전체 | 통과율 |\n|---|---|---|---|---|---|\n");
        tallyRow(sb, "엣지", result.lowEdgeTally());
        tallyRow(sb, "노드", result.lowNodeTally());
        sb.append("\n### 함정 (must-not)\n\n");
        int low = result.violations().size() - result.hardViolations().size();
        sb.append("- 함정 항목 ").append(result.mustNotTotal()).append("개 중 위반 ").append(result.hardViolations().size())
                .append("개").append(low > 0 ? " (그 밖에 certainty=low 위반 " + low + "개)" : "").append("\n\n");
        sb.append("### 실패 사유별\n\n| 대상 | 사유 | 개수 |\n|---|---|---|\n");
        result.edgeFailuresByReason().forEach((reason, count) -> sb.append("| 엣지 | ").append(reason.label()).append(" | ").append(count).append(" |\n"));
        result.nodeFailuresByReason().forEach((reason, count) -> sb.append("| 노드 | ").append(reason.label()).append(" | ").append(count).append(" |\n"));
        sb.append('\n');
    }

    private static void tallyTable(StringBuilder sb, String keyTitle, Map<String, Tally> byKind, Tally total) {
        sb.append("| ").append(keyTitle).append(" | 통과 | INFERRED | 실패 | 전체 | 통과율 |\n|---|---|---|---|---|---|\n");
        byKind.forEach((kind, tally) -> tallyRow(sb, kind, tally));
        tallyRow(sb, "**합계**", total);
    }

    private static void tallyRow(StringBuilder sb, String key, Tally tally) {
        sb.append("| ").append(key).append(" | ").append(tally.pass()).append(" | ").append(tally.inferredPass()).append(" | ").append(tally.fail())
                .append(" | ").append(tally.total()).append(" | ").append(percent(tally)).append(" |\n");
    }

    private static String percent(Tally tally) {
        return String.format(Locale.ROOT, "%.1f%%", tally.passRate());
    }

    private void failureSection(StringBuilder sb) {
        sb.append("## 실패한 must-have 엣지\n\n");
        List<EdgeResult> failedEdges = result.edges().stream().filter(r -> r.verdict() == Verdict.FAIL && !r.expected().lowCertainty()).toList();
        if (failedEdges.isEmpty()) {
            sb.append("없다.\n\n");
        }
        grouped(failedEdges, r -> r.expected().kind() + " — " + r.reason().label()).forEach((title, items) -> {
            sb.append("### ").append(title).append(" (").append(items.size()).append(")\n\n| from | to | note | 비고 |\n|---|---|---|---|\n");
            items.forEach(r -> edgeRow(sb, r));
            sb.append('\n');
        });

        sb.append("## 실패한 must-have 노드\n\n");
        List<NodeResult> failedNodes = result.nodes().stream().filter(r -> r.verdict() == Verdict.FAIL && !r.expected().lowCertainty()).toList();
        if (failedNodes.isEmpty()) {
            sb.append("없다.\n\n");
        }
        grouped(failedNodes, r -> r.expected().kind() + " — " + r.reason().label()).forEach((title, items) -> {
            sb.append("### ").append(title).append(" (").append(items.size()).append(")\n\n| id | note | 비고 |\n|---|---|---|\n");
            items.forEach(r -> nodeRow(sb, r));
            sb.append('\n');
        });

        sb.append("## 실패한 certainty=low 항목 (참고)\n\n");
        List<EdgeResult> lowEdges = result.edges().stream().filter(r -> r.verdict() == Verdict.FAIL && r.expected().lowCertainty()).toList();
        List<NodeResult> lowNodes = result.nodes().stream().filter(r -> r.verdict() == Verdict.FAIL && r.expected().lowCertainty()).toList();
        if (lowEdges.isEmpty() && lowNodes.isEmpty()) {
            sb.append("없다.\n\n");
            return;
        }
        if (!lowEdges.isEmpty()) {
            sb.append("| 종류 | 사유 | from | to | note | 비고 |\n|---|---|---|---|---|---|\n");
            for (EdgeResult r : lowEdges) {
                sb.append("| ").append(r.expected().kind()).append(" | ").append(r.reason().label()).append(' ');
                edgeRow(sb, r);
            }
            sb.append('\n');
        }
        if (!lowNodes.isEmpty()) {
            sb.append("| 종류 | 사유 | id | note | 비고 |\n|---|---|---|---|---|\n");
            for (NodeResult r : lowNodes) {
                sb.append("| ").append(r.expected().kind()).append(" | ").append(r.reason().label()).append(' ');
                nodeRow(sb, r);
            }
            sb.append('\n');
        }
    }

    private static <T> Map<String, List<T>> grouped(List<T> items, Function<T, String> key) {
        Map<String, List<T>> groups = new TreeMap<>();
        for (T item : items) {
            groups.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(item);
        }
        return groups;
    }

    private static void edgeRow(StringBuilder sb, EdgeResult r) {
        ExpectedEdge e = r.expected();
        String to = e.altTo().isEmpty() ? code(e.to()) : code(e.to()) + " (또는 " + String.join(", ", e.altTo().stream().map(SampleReport::code).toList()) + ")";
        String from = e.altFrom().isEmpty() ? code(e.fromLabel())
                : code(e.fromLabel()) + " (또는 " + String.join(", ", e.altFrom().stream().map(SampleReport::code).toList()) + ")";
        sb.append("| ").append(from).append(" | ").append(to).append(" | ").append(cell(e.note())).append(" | ").append(cell(r.detail())).append(" |\n");
    }

    private static void nodeRow(StringBuilder sb, NodeResult r) {
        sb.append("| ").append(code(r.expected().id())).append(" | ").append(cell(r.expected().note())).append(" | ").append(cell(r.detail())).append(" |\n");
    }

    private void passNotesSection(StringBuilder sb) {
        List<EdgeResult> inferred = result.edges().stream().filter(r -> r.verdict() == Verdict.INFERRED_PASS).toList();
        List<EdgeResult> loose = result.edges().stream().filter(r -> r.verdict().passed() && (r.loose() || r.attrsMismatched())).toList();
        sb.append("## 조건부로 통과한 엣지\n\n");
        sb.append("### INFERRED 엣지로 통과 (").append(inferred.size()).append(")\n\n");
        if (!inferred.isEmpty()) {
            sb.append("| 종류 | from | to | 실제 엣지 |\n|---|---|---|---|\n");
            for (EdgeResult r : inferred) {
                sb.append("| ").append(r.expected().kind()).append(" | ").append(code(r.expected().fromLabel())).append(" | ").append(code(r.expected().to()))
                        .append(" | ").append(cell(edgeLabel(r.matched()))).append(" |\n");
            }
            sb.append('\n');
        }
        sb.append("### 느슨한 규칙이나 attrs 무시로 통과 (").append(loose.size()).append(")\n\n");
        if (!loose.isEmpty()) {
            sb.append("| 종류 | from | to | 비고 |\n|---|---|---|---|\n");
            for (EdgeResult r : loose) {
                sb.append("| ").append(r.expected().kind()).append(" | ").append(code(r.expected().fromLabel())).append(" | ").append(code(r.expected().to()))
                        .append(" | ").append(cell(r.detail())).append(" |\n");
            }
            sb.append('\n');
        }
    }

    private void violationSection(StringBuilder sb) {
        sb.append("## 위반된 함정 (must-not)\n\n");
        if (result.violations().isEmpty()) {
            sb.append("없다.\n\n");
            return;
        }
        for (Violation violation : result.violations()) {
            ExpectedEdge rule = violation.rule();
            sb.append("- ").append(rule.lowCertainty() ? "(certainty=low) " : "")
                    .append(code(rule.fromLabel())).append(" → ").append(code(rule.to())).append(' ').append(rule.kind());
            if (rule.fromRoutine() != null) {
                sb.append(" (fromRoutine ").append(code(rule.fromRoutine())).append(')');
            }
            if (!rule.attrs().isEmpty()) {
                sb.append(" attrs ").append(rule.attrs());
            }
            sb.append(" — ").append(rule.note() == null ? "" : rule.note()).append('\n');
            List<Edge> edges = violation.edges();
            for (Edge edge : edges.subList(0, Math.min(edges.size(), 20))) {
                sb.append("  - ").append(edgeLabel(edge)).append('\n');
            }
            if (edges.size() > 20) {
                sb.append("  - … 외 ").append(edges.size() - 20).append("개 (전체는 JSON)\n");
            }
        }
        sb.append('\n');
    }

    private static String edgeLabel(Edge edge) {
        if (edge == null) {
            return "";
        }
        return code(edge.from()) + " → " + code(edge.to()) + " " + edge.kind() + " " + edge.confidence()
                + (edge.location() == null ? "" : " @ " + edge.location());
    }

    private void diagnosticSection(StringBuilder sb) {
        sb.append("## 진단\n\n### 코드별 개수\n\n| 심각도 | 코드 | 개수 |\n|---|---|---|\n");
        diagnosticCounts().forEach((key, count) -> sb.append("| ").append(key.get(0)).append(" | ").append(key.get(1)).append(" | ").append(count).append(" |\n"));
        if (graph.diagnostics().isEmpty()) {
            sb.append("| - | (진단 없음) | 0 |\n");
        }
        sb.append("\n### 깨진 입력 파일 (파일마다 PARSE_FAILED 또는 UNSUPPORTED_SYNTAX가 남아야 한다)\n\n| 파일 | 진단 | 내용 |\n|---|---|---|\n");
        brokenFiles.forEach((file, diagnostics) -> sb.append("| ").append(code(file)).append(" | ").append(diagnostics.isEmpty() ? "**없음**" : "있음").append(" | ")
                .append(cell(String.join("; ", diagnostics.stream().map(d -> d.code() + " " + d.location() + " " + d.message()).limit(3).toList())))
                .append(" |\n"));
        sb.append('\n');
    }

    /** (심각도, 코드) → 개수. 심각도가 높은 것부터. */
    private Map<List<String>, Integer> diagnosticCounts() {
        Map<List<String>, Integer> counts = new TreeMap<>(Comparator
                .comparing((List<String> key) -> Severity.valueOf(key.get(0))).reversed()
                .thenComparing(key -> key.get(1)));
        for (Diagnostic diagnostic : graph.diagnostics()) {
            counts.merge(List.of(diagnostic.severity().name(), diagnostic.code()), 1, Integer::sum);
        }
        return counts;
    }

    private void unresolvedSection(StringBuilder sb) {
        sb.append("## 미해결 대조 (expected-unresolved.md)\n\n");
        sb.append("자동으로 판정하지 않는다. 지점마다 같은 파일의 ±").append(UnresolvedPoints.NEARBY_LINES)
                .append("줄 안에 근거 위치가 있는 UNRESOLVED 엣지와 진단을 실었다. 사람이 대조한다.\n\n");
        if (!unresolvedPoints.isEmpty()) {
            sb.append("| # | 위치 | 근처의 UNRESOLVED 엣지 | 근처의 진단 |\n|---|---|---|---|\n");
            for (UnresolvedPoints.Point point : unresolvedPoints) {
                UnresolvedPoints.Evidence evidence = UnresolvedPoints.evidence(graph, point);
                sb.append("| ").append(point.id()).append(" | ").append(code(point.label())).append(" | ")
                        .append(cell(joinLimited(evidence.unresolvedEdges().stream().filter(e -> !resolvedElsewhere(e.to()))
                                .map(e -> e.kind() + " → " + e.to() + " @" + lineOf(e.location())).toList(), 5)))
                        .append(" | ")
                        .append(cell(joinLimited(evidence.diagnostics().stream().map(d -> d.code() + " @" + lineOf(d.location())).toList(), 5)))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        List<Node> unresolvedNodes = unresolvedNodes();
        sb.append("### 미해결 노드 (").append(unresolvedNodes.size()).append(")\n\n");
        int resolvedNodes = resolvedElsewhereNodes();
        if (resolvedNodes > 0) {
            sb.append("같은 지점에서 다른 방법으로 풀린 것(`resolvedBy`) ").append(resolvedNodes).append("개는 뺐다.\n\n");
        }
        Map<String, Integer> byCategory = new TreeMap<>();
        for (Node node : unresolvedNodes) {
            byCategory.merge(categoryOf(node), 1, Integer::sum);
        }
        if (!byCategory.isEmpty()) {
            sb.append("| 분류 | 개수 |\n|---|---|\n");
            byCategory.forEach((category, count) -> sb.append("| ").append(cell(category)).append(" | ").append(count).append(" |\n"));
            sb.append('\n');
        }
        limited(sb, unresolvedNodes, node -> "- " + code(node.id()) + (node.location() == null ? "" : " @ " + node.location()));

        List<Edge> unresolvedEdges = unresolvedEdges();
        sb.append("\n### UNRESOLVED 엣지 (").append(unresolvedEdges.size()).append(")\n\n");
        int resolvedEdges = resolvedElsewhereEdges();
        if (resolvedEdges > 0) {
            sb.append("같은 지점에서 다른 방법으로 풀린 참조(`resolvedBy`)로 가는 ").append(resolvedEdges).append("건은 뺐다.\n\n");
        }
        limited(sb, unresolvedEdges, edge -> "- " + edgeLabel(edge));

        List<Diagnostic> diagnostics = graph.diagnostics().stream().filter(d -> d.severity() != Severity.INFO).sorted(DIAGNOSTIC_ORDER).toList();
        sb.append("\n### 진단 목록 (WARN 이상, ").append(diagnostics.size()).append(")\n\n");
        if (!diagnostics.isEmpty()) {
            sb.append("| 심각도 | 코드 | 위치 | 메시지 |\n|---|---|---|---|\n");
            limited(sb, diagnostics, d -> "| " + d.severity() + " | " + cell(d.code()) + " | " + (d.location() == null ? "-" : cell(d.location().toString()))
                    + " | " + cell(d.message()) + " |");
        }
    }

    private static final Comparator<Edge> EDGE_ORDER = Comparator.comparing(Edge::from).thenComparing(Edge::to).thenComparing(e -> e.kind().name());
    private static final Comparator<Diagnostic> DIAGNOSTIC_ORDER = Comparator.comparing(Diagnostic::severity).reversed()
            .thenComparing(d -> d.location() == null ? "" : d.location().file())
            .thenComparingInt(d -> d.location() == null ? 0 : d.location().startLine())
            .thenComparing(Diagnostic::code)
            .thenComparing(Diagnostic::message);

    /**
     * 미해결 노드. 같은 지점에서 다른 방법으로 풀린 것({@code attrs.resolvedBy}: 링커가 접두 일치로 전부 푼 참조. F-012-R42)은 뺀다
     * (report.md의 "미해결 참조"와 같은 규칙. 머리의 그래프 수는 그대로 둔다. F-001-R58).
     */
    private List<Node> unresolvedNodes() {
        return graph.nodes(NodeKind.UNRESOLVED).stream().filter(n -> !resolvedElsewhere(n.id())).sorted(Comparator.comparing(Node::id)).toList();
    }

    /** UNRESOLVED 엣지. 풀린 참조({@link #resolvedElsewhere})로 가는 것은 뺀다. */
    private List<Edge> unresolvedEdges() {
        return graph.edges().stream().filter(e -> e.confidence() == Confidence.UNRESOLVED && !resolvedElsewhere(e.to())).sorted(EDGE_ORDER).toList();
    }

    private boolean resolvedElsewhere(String nodeId) {
        return graph.node(nodeId).map(n -> n.kind() == NodeKind.UNRESOLVED && n.attrString("resolvedBy") != null
                && !n.attrString("resolvedBy").isBlank()).orElse(false);
    }

    private int resolvedElsewhereNodes() {
        return (int) graph.nodes(NodeKind.UNRESOLVED).stream().filter(n -> resolvedElsewhere(n.id())).count();
    }

    private int resolvedElsewhereEdges() {
        return (int) graph.edges().stream().filter(e -> e.confidence() == Confidence.UNRESOLVED && resolvedElsewhere(e.to())).count();
    }

    /** 미해결 분류(설계 3.6). 마무리 단계가 만든 노드에도 core가 {@code expectedPrefix}에서 유도한 분류를 채운다(F-001-R38). */
    private static String categoryOf(Node node) {
        String category = node.attrString("category");
        return category == null ? "(분류 없음)" : category;
    }

    private <T> void limited(StringBuilder sb, List<T> items, Function<T, String> line) {
        for (T item : items.subList(0, Math.min(items.size(), maxList))) {
            sb.append(line.apply(item)).append('\n');
        }
        if (items.size() > maxList) {
            sb.append("\n… 외 ").append(items.size() - maxList).append("개 (전체는 ").append(JSON_FILE).append(")\n");
        }
    }

    private static String joinLimited(List<String> items, int limit) {
        if (items.isEmpty()) {
            return "-";
        }
        String joined = String.join("; ", items.subList(0, Math.min(items.size(), limit)));
        return items.size() > limit ? joined + " … 외 " + (items.size() - limit) + "개" : joined;
    }

    private static String lineOf(SourceLocation location) {
        return location == null ? "?" : String.valueOf(location.startLine());
    }

    private static String code(String text) {
        return "`" + cell(text) + "`";
    }

    /** 표 칸에 넣을 수 있게 줄바꿈을 공백으로 바꾸고 '|'를 이스케이프한다. */
    private static String cell(String text) {
        return text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").replace("|", "\\|");
    }

    // ------------------------------------------------------------------ JSON

    public String json() {
        Map<String, Object> root = new LinkedHashMap<>();
        ProjectInfo project = graph.project();
        if (project != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", project.name());
            p.put("root", project.root());
            p.put("analyzedAt", String.valueOf(project.analyzedAt()));
            root.put("project", p);
        }
        root.put("mode", mode);
        root.put("components", components);
        Map<String, Object> graphInfo = new LinkedHashMap<>();
        graphInfo.put("nodes", graph.nodeCount());
        graphInfo.put("edges", graph.edgeCount());
        Map<String, Object> confidence = new LinkedHashMap<>();
        confidenceCounts().forEach((c, n) -> confidence.put(c.name(), n));
        graphInfo.put("edgesByConfidence", confidence);
        graphInfo.put("diagnostics", graph.diagnostics().size());
        root.put("graph", graphInfo);
        root.put("gates", gates.stream().map(g -> map("name", g.name(), "passed", g.passed(), "detail", g.detail())).toList());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("edges", tally(result.edgeTally()));
        summary.put("edgesExistenceOnly", tally(result.edgeExistenceTally()));
        summary.put("nodes", tally(result.nodeTally()));
        summary.put("lowEdges", tally(result.lowEdgeTally()));
        summary.put("lowNodes", tally(result.lowNodeTally()));
        summary.put("looseEdgePasses", result.looseEdgePasses());
        summary.put("mustNotTotal", result.mustNotTotal());
        summary.put("mustNotViolations", result.hardViolations().size());
        summary.put("mustNotViolationsLow", result.violations().size() - result.hardViolations().size());
        root.put("summary", summary);

        root.put("edgesByKind", tallies(result.edgesByKind()));
        root.put("nodesByKind", tallies(result.nodesByKind()));
        root.put("edgeFailuresByReason", names(result.edgeFailuresByReason()));
        root.put("nodeFailuresByReason", names(result.nodeFailuresByReason()));

        root.put("edgeResults", result.edges().stream().map(SampleReport::edgeResultJson).toList());
        root.put("nodeResults", result.nodes().stream().map(SampleReport::nodeResultJson).toList());
        root.put("violations", result.violations().stream().map(v -> {
            Map<String, Object> item = expectedEdgeJson(v.rule());
            item.put("edges", v.edges().stream().map(SampleReport::edgeJson).toList());
            return item;
        }).toList());

        List<Map<String, Object>> byCode = new ArrayList<>();
        diagnosticCounts().forEach((key, count) -> byCode.add(map("severity", key.get(0), "code", key.get(1), "count", count)));
        root.put("diagnosticsByCode", byCode);
        List<Map<String, Object>> broken = new ArrayList<>();
        brokenFiles.forEach((file, diagnostics) -> broken.add(map("file", file, "reported", !diagnostics.isEmpty(),
                "diagnostics", diagnostics.stream().map(SampleReport::diagnosticJson).toList())));
        root.put("brokenFiles", broken);
        root.put("unresolvedPoints", unresolvedPoints.stream().map(point -> {
            UnresolvedPoints.Evidence evidence = UnresolvedPoints.evidence(graph, point);
            return map("id", point.id(), "file", point.file(), "startLine", point.startLine(), "endLine", point.endLine(), "text", point.text(),
                    "unresolvedEdges", evidence.unresolvedEdges().stream().filter(e -> !resolvedElsewhere(e.to())).map(SampleReport::edgeJson).toList(),
                    "diagnostics", evidence.diagnostics().stream().map(SampleReport::diagnosticJson).toList());
        }).toList());
        root.put("unresolvedNodes", unresolvedNodes().stream().map(Node::id).toList());
        root.put("unresolvedEdges", unresolvedEdges().stream().map(SampleReport::edgeJson).toList());
        root.put("resolvedElsewhereNodes", resolvedElsewhereNodes());
        root.put("resolvedElsewhereEdges", resolvedElsewhereEdges());
        root.put("diagnostics", graph.diagnostics().stream().sorted(DIAGNOSTIC_ORDER).map(SampleReport::diagnosticJson).toList());
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(root) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }

    private static Map<String, Object> tally(Tally tally) {
        return map("total", tally.total(), "pass", tally.pass(), "inferredPass", tally.inferredPass(), "fail", tally.fail(),
                "passRate", Math.round(tally.passRate() * 100.0) / 100.0);
    }

    private static Map<String, Object> tallies(Map<String, Tally> byKind) {
        Map<String, Object> result = new LinkedHashMap<>();
        byKind.forEach((kind, tally) -> result.put(kind, tally(tally)));
        return result;
    }

    private static Map<String, Object> names(Map<FailReason, Integer> counts) {
        Map<String, Object> result = new LinkedHashMap<>();
        counts.forEach((reason, count) -> result.put(reason.name(), count));
        return result;
    }

    private static Map<String, Object> expectedEdgeJson(ExpectedEdge e) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", e.source());
        item.put("index", e.index());
        item.put("kind", e.kind());
        item.put("from", e.from());
        if (e.isRequestMatch()) {
            item.put("fromFile", e.fromFile());
            item.put("line", e.line());
            item.put("httpMethod", e.httpMethod());
            item.put("requestUrl", e.requestUrl());
        }
        item.put("to", e.to());
        item.put("certainty", e.lowCertainty() ? "low" : null);
        item.put("note", e.note());
        return item;
    }

    private static Map<String, Object> edgeResultJson(EdgeResult r) {
        Map<String, Object> item = expectedEdgeJson(r.expected());
        item.put("verdict", r.verdict().name());
        item.put("reason", r.reason() == null ? null : r.reason().name());
        item.put("detail", r.detail());
        item.put("loose", r.loose());
        item.put("attrsMismatched", r.attrsMismatched());
        item.put("matched", r.matched() == null ? null : edgeJson(r.matched()));
        return item;
    }

    private static Map<String, Object> nodeResultJson(NodeResult r) {
        ExpectedNode n = r.expected();
        return map("source", n.source(), "index", n.index(), "kind", n.kind().name(), "id", n.id(), "certainty", n.lowCertainty() ? "low" : null,
                "note", n.note(), "verdict", r.verdict().name(), "reason", r.reason() == null ? null : r.reason().name(), "detail", r.detail());
    }

    private static Map<String, Object> edgeJson(Edge edge) {
        return map("from", edge.from(), "to", edge.to(), "kind", edge.kind().name(), "confidence", edge.confidence().name(),
                "location", edge.location() == null ? null : edge.location().toString(), "attrs", edge.attrs());
    }

    private static Map<String, Object> diagnosticJson(Diagnostic d) {
        return map("severity", d.severity().name(), "code", d.code(), "message", d.message(), "location", d.location() == null ? null : d.location().toString());
    }
}
