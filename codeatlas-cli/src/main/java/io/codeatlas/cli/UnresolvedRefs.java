package io.codeatlas.cli;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;

/**
 * 미해결을 셀 때의 공통 판정(F-001-R56, R58). 같은 지점에서 다른 방법으로 풀린 참조는 "미해결 참조"로 세지 않는다:
 * 링커는 그런 {@code UNRESOLVED} 노드에 {@code attrs.resolvedBy}(예: {@code prefixMatch})를 남기고 미해결 엣지와 노드는 그대로 둔다(F-012-R42).
 * 그래프의 수(종류별 노드·엣지, 신뢰도별 엣지)는 그래프 그대로 세고, 미해결 참조의 목록·건수·힌트에서만 뺀다.
 */
final class UnresolvedRefs {

    private static final String RESOLVED_BY = "resolvedBy";

    private UnresolvedRefs() {
    }

    /** {@code resolvedBy}가 있는 {@code UNRESOLVED} 노드인가. */
    static boolean resolvedElsewhere(Node node) {
        if (node == null || node.kind() != NodeKind.UNRESOLVED) {
            return false;
        }
        String by = node.attrString(RESOLVED_BY);
        return by != null && !by.isBlank();
    }

    /** 풀린 방법을 읽는 말로: {@code resolvedBy}가 전부 {@code prefixMatch}면 {@code 접두 일치로}, 아니면 {@code 다른 방법으로}. */
    static String how(java.util.Collection<String> resolvedBy) {
        return !resolvedBy.isEmpty() && resolvedBy.stream().allMatch("prefixMatch"::equals) ? "접두 일치로" : "다른 방법으로";
    }

    /** 노드의 {@code resolvedBy} 값. 없으면 null. */
    static String resolvedBy(Node node) {
        return resolvedElsewhere(node) ? node.attrString(RESOLVED_BY) : null;
    }

    /** 다른 방법으로 풀린 참조({@link #resolvedElsewhere(Node)})로 가는 미해결 엣지인가. */
    static boolean resolvedElsewhere(GraphModel graph, Edge edge) {
        return edge.confidence() == Confidence.UNRESOLVED && resolvedElsewhere(graph.node(edge.to()).orElse(null));
    }
}
