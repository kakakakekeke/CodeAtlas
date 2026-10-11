package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Linker;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 연결(F-012-R7~R9, R15~R21). 화면의 HTTP_REQUEST를 서버의 HTTP_ENDPOINT와 MATCHES로 잇는다.
 *
 * <p>정확히 맞으면 RESOLVED, 접두 세그먼트·자리표시자 세그먼트를 떼거나 상대 URL로 맞췄으면 INFERRED(근거는 attrs),
 * 후보가 동률이면 전부 INFERRED + ambiguous, 맞는 것이 없으면 미해결 엣지(분류 {@value #CATEGORY})를 남긴다.
 * {@code external=true}인 요청과 {@code clientRoute=true}인 요청(클라이언트 라우팅)은 맞춰 보지 않고, 정적 자원은 맞는 것이 없어도 미해결로 남기지 않는다.
 *
 * <p>엔드포인트 경로의 프로퍼티 자리표시자를 프로젝트 properties 값으로 치환해 맞추고, 치환한 경로를 엔드포인트 노드의
 * {@code attrs.resolvedPath}에 남긴다. 상대 URL의 기준 디렉터리를 그 페이지의 RENDERS에서 얻으므로 뷰 연결(order 220) 뒤에 돈다.
 */
public final class HttpMatchLinker implements Linker {

    static final String CATEGORY = "http-endpoint";

    @Override
    public String name() {
        return "link-http-match";
    }

    @Override
    public int order() {
        return 225;
    }

    @Override
    public void link(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        EndpointMatcher matcher = new EndpointMatcher(ctx);
        matcher.recordResolvedPaths();
        for (Node request : graph.nodes(NodeKind.HTTP_REQUEST)) {
            if (request.attrBoolean("external")) {
                continue;   // 프로젝트 밖으로 나가는 요청은 맞추지 않는다
            }
            if (request.attrBoolean("clientRoute")) {
                // 클라이언트 라우팅(React Router의 navigate, <Link to> 등)은 서버로 가는 요청이 아니다: 맞추지도, 미해결로 남기지도 않는다(F-012-R29)
                continue;
            }
            if (request.attrBoolean("selfSubmit")) {
                selfSubmit(graph, matcher, request);
                continue;
            }
            // 매칭에는 정규화된 패턴(path)을 쓴다. 없으면 원문(url), 그것도 없으면 ID(http:request:<METHOD> <패턴>@<파일>:<줄>)
            String method = request.attrString("httpMethod");
            String url = request.attrString("path");
            if (url == null || url.isBlank()) {
                url = request.attrString("url");
            }
            if (method == null || url == null || url.isBlank()) {
                String rest = Ids.remainder(request.id());
                int space = rest.indexOf(' ');
                int at = rest.lastIndexOf('@');
                if (method == null) {
                    method = space > 0 ? rest.substring(0, space) : Ids.ANY_METHOD;
                }
                if (url == null || url.isBlank()) {
                    url = rest.substring(space + 1, at > space ? at : rest.length());
                }
            }
            String sourceFile = request.location() == null ? null : request.location().file();
            // 상대 URL의 기준: 요청이 든 페이지를 그리는 핸들러의 엔드포인트 경로
            List<String> bases = sourceFile == null || url.startsWith("/") ? List.of() : matcher.baseDirectoriesOfPage(Ids.jspPage(sourceFile));
            EndpointMatcher.Result result = matcher.match(new EndpointMatcher.Request(method, url, request.attrBoolean("relative"),
                    sourceFile, bases, request.attrList("httpMethods")));
            if (result.skipped()) {
                continue;
            }
            if (!result.matched()) {
                if (!EndpointMatcher.isStaticResource(result.path())) {
                    graph.addUnresolvedEdge(request.id(), EdgeKind.MATCHES, CATEGORY,
                            Ids.httpMethod(method) + " " + result.path(), request.location());
                }
                continue;
            }
            for (EndpointMatcher.Hit hit : result.hits()) {
                graph.addEdge(Edge.builder(request.id(), hit.endpoint().node().id(), EdgeKind.MATCHES)
                        .confidence(result.confidence()).location(request.location()).attrs(hit.attrs()).build());
            }
        }
    }

    /**
     * action 없는 폼(F-012-R40): 현재 URL로 제출하므로, 그 폼이 든 JSP를 그리는(RENDERS) 핸들러의 엔드포인트와 경로가 같고
     * 폼 메서드에 맞는 엔드포인트로 MATCHES(INFERRED, {@code selfSubmit=true})를 만든다. 같은 컨트롤러의
     * {@code @GetMapping("/x")} 화면 + {@code @PostMapping("/x")} 처리 관용구다. 그리는 핸들러가 여럿이면 각각 잇고,
     * 그리는 핸들러를 모르거나 맞는 엔드포인트가 없으면 미해결이다.
     */
    private static void selfSubmit(GraphModel graph, EndpointMatcher matcher, Node request) {
        String method = request.attrString("httpMethod");
        if (method == null) {
            method = Ids.ANY_METHOD;
        }
        String sourceFile = request.location() == null ? null : request.location().file();
        Set<String> pages = new LinkedHashSet<>();
        for (Edge requests : graph.incoming(request.id(), EdgeKind.REQUESTS)) {
            if (graph.node(requests.from()).filter(n -> n.kind() == NodeKind.JSP_PAGE).isPresent()) {
                pages.add(requests.from());
            }
        }
        if (pages.isEmpty() && sourceFile != null) {
            pages.add(Ids.jspPage(sourceFile));
        }
        // 그 페이지를 그리는 핸들러 메서드의 엔드포인트(view-controller면 엔드포인트가 직접 그린다)
        Set<String> rendering = new LinkedHashSet<>();
        for (String page : pages) {
            for (Edge renders : graph.incoming(page, EdgeKind.RENDERS)) {
                if (renders.confidence() == Confidence.UNRESOLVED) {
                    continue;
                }
                if (graph.node(renders.from()).filter(n -> n.kind() == NodeKind.HTTP_ENDPOINT).isPresent()) {
                    rendering.add(renders.from());
                }
                for (Edge route : graph.incoming(renders.from(), EdgeKind.ROUTES_TO)) {
                    rendering.add(route.from());
                }
            }
        }
        Map<String, Map<String, Object>> targets = new LinkedHashMap<>();
        for (String endpoint : rendering) {
            EndpointMatcher.Result result = matcher.matchSamePath(endpoint,
                    new EndpointMatcher.Request(method, "", false, sourceFile, List.of(), request.attrList("httpMethods")));
            for (EndpointMatcher.Hit hit : result.hits()) {
                targets.putIfAbsent(hit.endpoint().node().id(), hit.attrs());
            }
        }
        if (targets.isEmpty()) {
            graph.addUnresolvedEdge(request.id(), EdgeKind.MATCHES, CATEGORY, Ids.httpMethod(method) + " (selfSubmit)", request.location());
            return;
        }
        targets.forEach((endpoint, attrs) -> {
            Map<String, Object> own = new LinkedHashMap<>(attrs);
            // 같은 그리는 엔드포인트 안에서 동률이었다는 표시는 여기서 다시 센다
            own.remove("ambiguous");
            own.put("selfSubmit", true);
            if (targets.size() > 1) {
                own.put("ambiguous", true);
            }
            graph.addEdge(Edge.builder(request.id(), endpoint, EdgeKind.MATCHES).inferred().location(request.location()).attrs(own).build());
        });
    }
}
