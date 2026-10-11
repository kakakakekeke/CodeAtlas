package io.codeatlas.cli.verify;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 정답 JSON의 형식으로 적지 못해 통합 테스트가 따로 보는 기대 하나("이 속성이 없다", "이 미해결 엣지가 있다").
 * {@link SampleVerifier#check}로 주면 리포트의 임계값 판정에 한 줄로 들어가고, 단언 모드에서는 통과하지 못하면 실패한다.
 *
 * @param name 기준의 이름(리포트의 "기준" 칸)
 * @param test 그래프를 보고 내리는 판정
 */
public record ExtraCheck(String name, Function<GraphModel, Outcome> test) {

    /** @param detail 리포트의 "내용" 칸. 실패했을 때는 무엇이 달랐는지 적는다 */
    public record Outcome(boolean passed, String detail) {

        public static Outcome pass(String detail) {
            return new Outcome(true, detail);
        }

        public static Outcome fail(String detail) {
            return new Outcome(false, detail);
        }
    }

    /** 노드는 있고 그 속성은 없어야 한다. 노드가 없으면 실패다(없는 노드에 속성이 없는 것은 기대한 모습이 아니다). */
    public static ExtraCheck nodeWithoutAttr(String nodeId, String attr) {
        return new ExtraCheck("`" + nodeId + "`에 `" + attr + "` 속성이 없음", graph -> {
            Optional<Node> node = graph.node(nodeId);
            if (node.isEmpty()) {
                return Outcome.fail("노드가 없다");
            }
            Object value = node.get().attrs().get(attr);
            return value == null ? Outcome.pass("") : Outcome.fail(attr + "=" + value);
        });
    }

    /**
     * {@code from}에서 {@code to}로 가는 그 종류의 엣지가 {@code UNRESOLVED} 신뢰도로 있어야 한다. {@code attrs}에 적은 키는 값도 맞아야 한다
     * (비교 규칙은 정답 JSON의 attrs와 같다).
     */
    public static ExtraCheck unresolvedEdge(String from, EdgeKind kind, String to, Map<String, Object> attrs) {
        String name = "미해결 엣지 `" + from + "` → `" + to + "` " + kind + (attrs.isEmpty() ? "" : " " + attrs) + "가 있음";
        return new ExtraCheck(name, graph -> {
            List<Edge> edges = graph.edges(from, kind, to);
            List<String> problems = new ArrayList<>();
            for (Edge edge : edges) {
                List<String> differences = new ArrayList<>();
                if (edge.confidence() != Confidence.UNRESOLVED) {
                    differences.add("신뢰도 " + edge.confidence());
                }
                AttrMatcher.differences(attrs, edge.attrs()).forEach((key, difference) -> differences.add(key + ": " + difference));
                if (differences.isEmpty()) {
                    return Outcome.pass(edge.location() == null ? "" : "@ " + edge.location());
                }
                problems.add(String.join(", ", differences));
            }
            if (!edges.isEmpty()) {
                return Outcome.fail("엣지는 있는데 다르다: " + String.join("; ", problems));
            }
            return Outcome.fail("엣지가 없다. " + sameCategoryEdges(graph, to));
        });
    }

    /** 같은 미해결 분류({@code unresolved:<분류>:})로 가는 UNRESOLVED 엣지. 줄이나 표기만 다른 엣지를 찾아볼 수 있게 실패 내용에 싣는다. */
    private static String sameCategoryEdges(GraphModel graph, String to) {
        int categoryEnd = to.indexOf(':', to.indexOf(':') + 1);
        if (categoryEnd < 0) {
            return "";
        }
        String prefix = to.substring(0, categoryEnd + 1);
        List<String> found = graph.edges().stream()
                .filter(e -> e.confidence() == Confidence.UNRESOLVED && e.to().startsWith(prefix))
                .map(e -> e.from() + " → " + e.to() + " " + e.kind())
                .sorted()
                .toList();
        if (found.isEmpty()) {
            return "`" + prefix + "`로 가는 미해결 엣지가 하나도 없다";
        }
        int limit = 5;
        return "`" + prefix + "`로 가는 미해결 엣지 " + found.size() + "건: " + String.join("; ", found.subList(0, Math.min(found.size(), limit)))
                + (found.size() > limit ? " … 외 " + (found.size() - limit) + "건" : "");
    }
}
