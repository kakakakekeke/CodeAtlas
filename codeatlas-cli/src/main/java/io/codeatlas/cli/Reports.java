package io.codeatlas.cli;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.pipeline.PipelineResult;
import io.codeatlas.core.spi.FileType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** analyze가 내는 요약(표준 출력)과 diagnostics.md 본문. */
final class Reports {

    private Reports() {
    }

    /**
     * 파일 종류별 개수, 노드·엣지 종류별 개수, 신뢰도별 엣지 수, 진단 수, 그리고 해당하는 것이 있을 때만 힌트({@link Hints}).
     * 노드·엣지의 종류별 수와 신뢰도별 수는 그래프 그대로 센다(종류별 합이 총수와 같다). 같은 지점에서 다른 방법으로 풀린 참조
     * ({@code resolvedBy}가 있는 {@code UNRESOLVED} 노드)와 그리로 가는 미해결 엣지는 그 수에 들어 있고, 몇 건이 그런지를 신뢰도 표 아래에
     * 한 줄로 알린다({@link UnresolvedRefs}. {@code report.md}의 "미해결 참조"와 힌트는 그것을 빼고 센다. F-001-R58).
     */
    static String summary(PipelineResult result, Path diagnosticsFile) {
        return summary(result.graph(), result.context().projectRoot().toString(), result.graphFile(), diagnosticsFile,
                result.files().size(), result.files().countsByType());
    }

    /**
     * 저장된 그래프의 요약. 파일 수는 FILE 노드의 {@code fileType}으로 센다(FILE 노드를 만들지 않는 OTHER, JAR는 빠진다).
     *
     * @param diagnosticsFile 진단 리포트 경로. 없으면 null
     */
    static String summary(GraphModel graph, Path graphFile, Path diagnosticsFile) {
        Map<FileType, Integer> fileCounts = new EnumMap<>(FileType.class);
        int files = 0;
        for (Node node : graph.nodes(NodeKind.FILE)) {
            files++;
            String type = node.attrString("fileType");
            try {
                fileCounts.merge(type == null ? FileType.OTHER : FileType.valueOf(type), 1, Integer::sum);
            } catch (IllegalArgumentException e) {
                fileCounts.merge(FileType.OTHER, 1, Integer::sum);
            }
        }
        ProjectInfo project = graph.project();
        return summary(graph, project == null ? "" : project.root(), graphFile, diagnosticsFile, files, fileCounts);
    }

    private static String summary(GraphModel graph, String projectRoot, Path graphFile, Path diagnosticsFile, int fileTotal, Map<FileType, Integer> fileCounts) {
        StringBuilder sb = new StringBuilder();
        ProjectInfo project = graph.project();
        sb.append("CodeAtlas 분석 결과: ").append(project == null ? "" : project.name()).append('\n');
        sb.append("  프로젝트: ").append(projectRoot).append('\n');
        sb.append("  그래프:   ").append(graphFile).append('\n');
        if (diagnosticsFile != null) {
            sb.append("  진단:     ").append(diagnosticsFile).append('\n');
        }

        section(sb, "파일", fileTotal, fileCounts);

        Map<NodeKind, Integer> nodeCounts = new EnumMap<>(NodeKind.class);
        int resolvedNodes = 0;
        List<String> resolvedBy = new ArrayList<>();
        for (Node node : graph.nodes()) {
            nodeCounts.merge(node.kind(), 1, Integer::sum);
            if (UnresolvedRefs.resolvedElsewhere(node)) {
                resolvedNodes++;
                resolvedBy.add(UnresolvedRefs.resolvedBy(node));
            }
        }
        section(sb, "노드", graph.nodeCount(), nodeCounts);

        Map<EdgeKind, Integer> edgeCounts = new EnumMap<>(EdgeKind.class);
        Map<Confidence, Integer> confidenceCounts = zeros(Confidence.class);
        int resolvedEdges = 0;
        for (Edge edge : graph.edges()) {
            edgeCounts.merge(edge.kind(), 1, Integer::sum);
            confidenceCounts.merge(edge.confidence(), 1, Integer::sum);
            if (UnresolvedRefs.resolvedElsewhere(graph, edge)) {
                resolvedEdges++;
            }
        }
        section(sb, "엣지", graph.edgeCount(), edgeCounts);
        sb.append("\n신뢰도별 엣지\n");
        rows(sb, confidenceCounts);
        if (resolvedNodes > 0 || resolvedEdges > 0) {
            // 위의 수는 그래프 그대로다. 풀린 참조를 빼고 세는 곳(report.md의 "미해결 참조", 힌트)과 수가 다른 까닭을 알린다.
            sb.append("  (UNRESOLVED 가운데 참조 ").append(resolvedNodes).append("개와 그리로 가는 엣지 ").append(resolvedEdges)
                    .append("건은 같은 지점에서 ").append(UnresolvedRefs.how(resolvedBy)).append(" 풀렸다: resolvedBy. ")
                    .append("report.md의 \"미해결 참조\"와 힌트는 이를 빼고 센다)\n");
        }

        Map<Severity, Integer> severityCounts = zeros(Severity.class);
        List<Diagnostic> diagnostics = graph.diagnostics();
        for (Diagnostic diagnostic : diagnostics) {
            severityCounts.merge(diagnostic.severity(), 1, Integer::sum);
        }
        section(sb, "진단", diagnostics.size(), severityCounts);

        List<String> hints = Hints.of(graph);
        if (!hints.isEmpty()) {
            sb.append("\n힌트\n");
            hints.forEach(hint -> sb.append("  - ").append(hint).append('\n'));
        }
        return sb.toString();
    }

    private static <E extends Enum<E>> Map<E, Integer> zeros(Class<E> type) {
        Map<E, Integer> counts = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            counts.put(value, 0);
        }
        return counts;
    }

    private static void section(StringBuilder sb, String title, int total, Map<? extends Enum<?>, Integer> counts) {
        sb.append('\n').append(title).append(" (총 ").append(total).append(")\n");
        rows(sb, counts);
    }

    private static void rows(StringBuilder sb, Map<? extends Enum<?>, Integer> counts) {
        counts.forEach((key, count) -> sb.append(String.format("  %-16s %6d%n", key.name(), count)));
    }

    private static final String UNRESOLVED_MESSAGE_PREFIX = "해석하지 못한 참조(";

    /**
     * 같은 지점에서 다른 방법으로 풀린 참조의 {@code UNRESOLVED_REFERENCE} 진단 메시지 → 풀린 방법({@code resolvedBy}).
     * 진단과 미해결 노드를 잇는 구조화된 값이 없어 메시지로 맞춘다: core가 미해결 노드와 함께 남기는 진단의 메시지는
     * {@code 해석하지 못한 참조(<분류>): <원문>}이고 노드는 {@code category}와 이름(원문)을 갖는다(설계 3.6).
     * {@code resolvedBy}는 그 노드로 들어오는 참조가 전부 풀렸을 때만 붙으므로 같은 메시지의 진단은 모두 풀린 것이다.
     */
    private static Map<String, String> resolvedReferenceMessages(GraphModel graph) {
        Map<String, String> messages = new java.util.HashMap<>();
        for (Node node : graph.nodes(NodeKind.UNRESOLVED)) {
            String by = UnresolvedRefs.resolvedBy(node);
            String category = node.attrString("category");
            if (by != null && category != null && node.name() != null) {
                messages.put(UNRESOLVED_MESSAGE_PREFIX + category + "): " + node.name(), by);
            }
        }
        return messages;
    }

    /**
     * diagnostics.md 본문. 심각도가 높은 것부터, 같은 심각도 안에서는 위치순으로 쓴다.
     * 진단은 분석기가 남긴 기록이라 건수를 바꾸지 않는다: 링커가 같은 지점에서 다른 방법으로 푼 참조의 {@code UNRESOLVED_REFERENCE}도
     * 그대로 싣고 메시지 끝에 {@code (접두 일치로 풀림)}을 덧붙이며, 머리에 {@code report.md}와 수가 다른 까닭을 한 줄 적는다(F-001-R58).
     */
    static String diagnosticsMarkdown(GraphModel graph) {
        List<Diagnostic> diagnostics = new ArrayList<>(graph.diagnostics());
        diagnostics.sort(Comparator.comparing(Diagnostic::severity).reversed()
                .thenComparing(d -> d.location() == null ? "" : d.location().file())
                .thenComparingInt(d -> d.location() == null ? 0 : d.location().startLine())
                .thenComparing(Diagnostic::code)
                .thenComparing(Diagnostic::message));

        Map<Severity, Integer> bySeverity = zeros(Severity.class);
        Map<String, Integer> byCode = new TreeMap<>();
        for (Diagnostic diagnostic : diagnostics) {
            bySeverity.merge(diagnostic.severity(), 1, Integer::sum);
            byCode.merge(diagnostic.severity().name() + " | " + diagnostic.code(), 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 진단 리포트\n\n");
        ProjectInfo project = graph.project();
        if (project != null) {
            sb.append("- 프로젝트: ").append(project.name()).append(" (`").append(project.root()).append("`)\n");
            sb.append("- 분석 시각: ").append(project.analyzedAt()).append('\n');
        }
        sb.append("- 진단 수: ").append(diagnostics.size())
                .append(" (ERROR ").append(bySeverity.get(Severity.ERROR))
                .append(", WARN ").append(bySeverity.get(Severity.WARN))
                .append(", INFO ").append(bySeverity.get(Severity.INFO)).append(")\n");
        Map<String, String> resolved = resolvedReferenceMessages(graph);
        int unresolvedReferences = 0;
        List<String> resolvedBy = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (DiagnosticCodes.UNRESOLVED_REFERENCE.equals(diagnostic.code())) {
                unresolvedReferences++;
                if (resolved.containsKey(diagnostic.message())) {
                    resolvedBy.add(resolved.get(diagnostic.message()));
                }
            }
        }
        if (!resolvedBy.isEmpty()) {
            String how = UnresolvedRefs.how(resolvedBy);
            sb.append("- `").append(DiagnosticCodes.UNRESOLVED_REFERENCE).append("` ").append(unresolvedReferences).append("건 가운데 ")
                    .append(resolvedBy.size()).append("건은 링커가 같은 지점에서 ").append(how).append(" 풀었다(목록의 메시지 끝에 `(")
                    .append(how).append(" 풀림)`). `report.md`의 \"미해결 참조\"는 엣지를 세고 풀린 것을 빼므로 이 수와 다르다.\n");
        }
        sb.append('\n');
        if (diagnostics.isEmpty()) {
            sb.append("진단이 없다.\n");
            return sb.toString();
        }

        sb.append("## 코드별 개수\n\n| 심각도 | 코드 | 개수 |\n|---|---|---|\n");
        byCode.forEach((key, count) -> sb.append("| ").append(key).append(" | ").append(count).append(" |\n"));

        sb.append("\n## 목록\n\n| 심각도 | 코드 | 위치 | 메시지 |\n|---|---|---|---|\n");
        for (Diagnostic diagnostic : diagnostics) {
            sb.append("| ").append(diagnostic.severity())
                    .append(" | ").append(cell(diagnostic.code()))
                    .append(" | ").append(location(diagnostic.location()))
                    .append(" | ").append(cell(diagnostic.message()));
            if (DiagnosticCodes.UNRESOLVED_REFERENCE.equals(diagnostic.code()) && resolved.containsKey(diagnostic.message())) {
                sb.append(" (").append(UnresolvedRefs.how(List.of(resolved.get(diagnostic.message())))).append(" 풀림)");
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private static String location(SourceLocation location) {
        return location == null ? "-" : cell(location.toString());
    }

    /** 표 칸에 넣을 수 있게 줄바꿈을 공백으로 바꾸고 '|'를 이스케이프한다. */
    private static String cell(String text) {
        return text.replaceAll("\\s*\\R\\s*", " ").replace("|", "\\|");
    }
}
