package io.codeatlas.web;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** 테스트에서 그래프를 읽는 도우미. */
final class G {

    private final GraphModel graph;

    G(AnalysisContext ctx) {
        this.graph = ctx.graph();
    }

    GraphModel graph() {
        return graph;
    }

    static String fn(String path, String name) {
        return "js:function:" + path + "#" + name;
    }

    static String mod(String path) {
        return "js:module:" + path;
    }

    /**
     * jsp 분석기 대신 "이 페이지가 이 스크립트들을 싣는다"는 INCLUDES 엣지를 만드는 가짜 생산자(order 100).
     * 모듈 문법이 없는 JS 파일끼리의 전역 호출은 두 파일을 함께 싣는 페이지가 있을 때만 잇기 때문에(F-010-R18) 필요하다.
     */
    static Analyzer page(String jsp, String... scripts) {
        return new Analyzer() {
            @Override
            public String name() {
                return "fake-jsp";
            }

            @Override
            public void analyze(AnalysisContext ctx) {
                String pageId = "jsp:page:" + jsp;
                ctx.graph().declare(Node.of(pageId, NodeKind.JSP_PAGE, jsp));
                for (String script : scripts) {
                    ctx.graph().addEdge(Edge.of(pageId, mod(script), EdgeKind.INCLUDES));
                }
            }
        };
    }

    Node node(String id) {
        return graph.node(id).orElseThrow(() -> new AssertionError("노드가 없다: " + id + "\n있는 노드: " + ids(null)));
    }

    /** 그 종류의 노드 ID 전부(종류가 null이면 전체). */
    Set<String> ids(NodeKind kind) {
        return (kind == null ? graph.nodes() : graph.nodes(kind)).stream().map(Node::id).collect(Collectors.toCollection(TreeSet::new));
    }

    /** 모듈 path의 함수 이름 경로 전부. */
    Set<String> functions(String path) {
        String prefix = "js:function:" + path + "#";
        return graph.nodes(NodeKind.JS_FUNCTION).stream().map(Node::id).filter(id -> id.startsWith(prefix))
                .map(id -> id.substring(prefix.length())).collect(Collectors.toCollection(TreeSet::new));
    }

    List<Edge> edges(String from, EdgeKind kind) {
        return graph.outgoing(from, kind);
    }

    /** from에서 그 종류로 나가는 엣지의 대상 ID 집합. */
    Set<String> targets(String from, EdgeKind kind) {
        return graph.outgoing(from, kind).stream().map(Edge::to).collect(Collectors.toCollection(TreeSet::new));
    }

    Edge edge(String from, EdgeKind kind, String to) {
        List<Edge> found = graph.edges(from, kind, to);
        if (found.isEmpty()) {
            throw new AssertionError("엣지가 없다: " + from + " -" + kind + "-> " + to + "\n있는 대상: " + targets(from, kind));
        }
        return found.get(0);
    }

    boolean hasEdge(String from, EdgeKind kind, String to) {
        return !graph.edges(from, kind, to).isEmpty();
    }

    /** "METHOD path" 형태로 본 요청 노드 전부. */
    Set<String> requests() {
        return graph.nodes(NodeKind.HTTP_REQUEST).stream().map(n -> n.attrString("httpMethod") + " " + n.attrString("path"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** from이 REQUESTS로 가리키는 요청을 "METHOD path" 형태로. */
    Set<String> requestsFrom(String from) {
        return graph.outgoing(from, EdgeKind.REQUESTS).stream().map(e -> graph.node(e.to()).orElseThrow())
                .filter(n -> n.kind() == NodeKind.HTTP_REQUEST)
                .map(n -> n.attrString("httpMethod") + " " + n.attrString("path")).collect(Collectors.toCollection(TreeSet::new));
    }

    Node request(String method, String path) {
        return graph.nodes(NodeKind.HTTP_REQUEST).stream()
                .filter(n -> method.equals(n.attrString("httpMethod")) && path.equals(n.attrString("path"))).findFirst()
                .orElseThrow(() -> new AssertionError("요청 노드가 없다: " + method + " " + path + "\n있는 요청: " + requests()));
    }

    List<Diagnostic> diagnostics(String code) {
        return graph.diagnostics().stream().filter(d -> d.code().equals(code)).toList();
    }
}
