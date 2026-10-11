package io.codeatlas.sql;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import java.util.List;
import java.util.stream.Collectors;

/** 그래프 검증 보조. */
public final class GraphAssert {

    private GraphAssert() {
    }

    public static Node node(GraphModel g, String id) {
        return g.node(id).orElseThrow(() -> new AssertionError("노드 없음: " + id + "\n있는 노드: "
                + g.nodes().stream().map(Node::id).filter(i -> !i.startsWith("file:")).sorted().collect(Collectors.joining("\n  "))));
    }

    public static Edge edge(GraphModel g, String from, EdgeKind kind, String to) {
        List<Edge> edges = g.edges(from, kind, to);
        if (edges.isEmpty()) {
            throw new AssertionError("엣지 없음: " + from + " -" + kind + "-> " + to + "\n" + from + " 에서 나가는 엣지: "
                    + g.outgoing(from).stream().map(e -> e.kind() + " -> " + e.to()).sorted().collect(Collectors.joining("\n  ")));
        }
        return edges.get(0);
    }

    public static boolean hasEdge(GraphModel g, String from, EdgeKind kind, String to) {
        return !g.edges(from, kind, to).isEmpty();
    }

    /** from에서 kind로 나가는 엣지의 to 목록(정렬). */
    public static List<String> targets(GraphModel g, String from, EdgeKind kind) {
        return g.outgoing(from, kind).stream().map(Edge::to).distinct().sorted().collect(Collectors.toList());
    }

    public static List<String> codes(GraphModel g) {
        return g.diagnostics().stream().map(Diagnostic::code).collect(Collectors.toList());
    }

    public static List<Diagnostic> diagnostics(GraphModel g, String code) {
        return g.diagnostics().stream().filter(d -> d.code().equals(code)).collect(Collectors.toList());
    }
}
