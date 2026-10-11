package io.codeatlas.export.report;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.export.calltree.DynamicStatement;
import io.codeatlas.export.common.DataLoads;
import io.codeatlas.export.common.DbGraph;
import io.codeatlas.export.common.GraphContents;
import io.codeatlas.export.common.NodeLabels;
import io.codeatlas.export.common.UndeclaredColumns;
import io.codeatlas.export.common.Texts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 분석 요약 리포트(Markdown). 종류별 노드·엣지 수, 신뢰도별 엣지 수, 미해결 참조(분류별, 엣지 기준),
 * 진단 집계와 상위 항목, 그리고 점검 목록(DDL에 없는 테이블과 참조하는 구문 수, 참조되지 않는 테이블,
 * 데이터 적재 스크립트 요약(그런 구문이 있을 때만), 대상 테이블을 정적으로 알 수 없는 구문,
 * 대응 구문이 없는 매퍼 메서드, 호출하는 곳이 없는 매퍼 구문, Java 호출자가 없는 매퍼 구문)을 적는다.
 * 시각이나 절대 경로는 넣지 않는다(같은 입력에 같은 출력).
 *
 * <p>DB 객체(테이블·뷰), Java 타입, 엔드포인트, 배치 잡이 그래프에 하나도 없으면 머리에 한 줄씩 알린다.
 * 표준 출력기는 그 대상의 산출물({@code erd/}, {@code crud/}, {@code class-diagram/}, {@code calltree/})을 쓰지 않는다.
 * 표에서 위치를 모르는 칸은 {@code -}다.
 */
public final class SummaryReport {

    /** 진단 코드마다 보여 줄 상위 항목 수의 기본값. */
    public static final int DEFAULT_MAX_ITEMS = 20;

    /** 테이블·뷰가 하나도 없을 때 머리에 적는 줄. */
    public static final String NO_DB_OBJECTS = "DB 객체 없음(DDL이나 SQL이 분석되지 않음)";
    /** 소스에서 정의를 본 Java 타입이 하나도 없을 때. */
    public static final String NO_JAVA_TYPES = "Java 타입 없음(Java 소스가 분석되지 않음)";
    /** 엔드포인트가 하나도 없을 때. */
    public static final String NO_ENDPOINTS = "엔드포인트 없음";
    /** 배치 잡과 스텝이 하나도 없을 때. */
    public static final String NO_BATCH_JOBS = "배치 잡 없음";
    /** "DDL에 없는 테이블"에서 참조하는 구문이 하나뿐인 테이블의 비고. */
    public static final String SUSPICIOUS_TABLE = "의심(구문 파싱 복구에서 나왔을 수 있음)";
    /** DB가 제공하는 시스템 카탈로그(데이터 사전) 테이블의 비고. */
    public static final String SYSTEM_CATALOG = "시스템 카탈로그";
    /** 데이터 적재 구문만 참조하는 테이블의 비고. */
    public static final String DATA_LOAD_ONLY = "데이터 적재 스크립트만 참조";

    private SummaryReport() {
    }

    /** 위치 칸. 위치를 모르면 빈칸 대신 {@code -}를 적는다. */
    private static String locationCell(String location) {
        String code = Texts.mdCode(location);
        return code.isEmpty() ? "-" : code;
    }

    public static String render(GraphModel graph) {
        return render(graph, DEFAULT_MAX_ITEMS);
    }

    /**
     * @param maxItems 진단 코드마다 보여 줄 항목 수. 0 이하면 전부
     */
    public static String render(GraphModel graph, int maxItems) {
        return render(graph, maxItems, false);
    }

    /**
     * @param maxItems 진단 코드마다 보여 줄 항목 수. 0 이하면 전부
     * @param includeDataLoad true면 테이블 점검(DDL에 없는 테이블의 구문 수, 참조되지 않는 테이블)에 데이터 적재 구문
     *        ({@code attrs.dataLoad=true})도 센다. 기본은 뺀다. 어느 쪽이든 적재 구문이 있으면 "데이터 적재 스크립트" 절로 요약한다
     */
    public static String render(GraphModel graph, int maxItems, boolean includeDataLoad) {
        StringBuilder out = new StringBuilder("# CodeAtlas 분석 요약\n\n");
        if (graph.project() != null && !Texts.isBlank(graph.project().name())) {
            out.append("- 프로젝트: ").append(Texts.mdCode(graph.project().name())).append('\n');
        }
        List<Node> nodes = new ArrayList<>(graph.nodes());
        nodes.sort(Comparator.comparing(Node::id));
        // 엣지는 정렬하지 않는다(수백만 개일 수 있다). 순서가 드러나는 곳(미해결 참조 목록)에서만 그 부분을 정렬한다.
        Collection<Edge> edges = graph.edges();
        out.append("- 노드 ").append(nodes.size()).append("개, 엣지 ").append(edges.size()).append("개, 진단 ")
                .append(graph.diagnostics().size()).append("개\n");
        if (!GraphContents.hasDbObjects(graph)) {
            out.append("- ").append(NO_DB_OBJECTS).append('\n');
        }
        if (!GraphContents.hasJavaTypes(graph)) {
            out.append("- ").append(NO_JAVA_TYPES).append('\n');
        }
        if (!GraphContents.hasEndpoints(graph)) {
            out.append("- ").append(NO_ENDPOINTS).append('\n');
        }
        if (!GraphContents.hasBatchJobs(graph)) {
            out.append("- ").append(NO_BATCH_JOBS).append('\n');
        }
        long catalogs = nodes.stream().filter(n -> (n.kind() == NodeKind.DB_TABLE || n.kind() == NodeKind.DB_VIEW) && n.attrBoolean("systemCatalog")).count();
        if (catalogs > 0) {
            // DB가 제공하는 데이터 사전은 프로젝트 스키마가 아니다: ERD에 그리지 않고 테이블 명세에 이름만 남긴다.
            out.append("- 시스템 카탈로그 ").append(catalogs).append("개는 ERD에서 제외(이름은 `erd/tables.md`의 \"시스템 카탈로그\" 절)\n");
        }

        counts(out, nodes, edges);
        unresolved(out, graph, edges);
        requests(out, graph);
        diagnostics(out, graph, maxItems);
        tables(out, graph, nodes, edges, maxItems, includeDataLoad);
        mappers(out, graph, nodes);
        return out.toString();
    }

    private static void counts(StringBuilder out, List<Node> nodes, Collection<Edge> edges) {
        Map<NodeKind, int[]> byNodeKind = new EnumMap<>(NodeKind.class);
        for (Node n : nodes) {
            int[] c = byNodeKind.computeIfAbsent(n.kind(), k -> new int[2]);
            c[0]++;
            if (!n.declared()) {
                c[1]++;
            }
        }
        out.append("\n## 종류별 노드 수\n\n");
        if (byNodeKind.isEmpty()) {
            out.append("없음\n");
        } else {
            out.append("| 종류 | 개수 | 정의 없이 참조만 된 것 |\n|---|---|---|\n");
            byNodeKind.forEach((kind, c) -> out.append("| ").append(kind).append(" | ").append(c[0]).append(" | ").append(c[1])
                    .append(" |\n"));
        }

        Map<EdgeKind, int[]> byEdgeKind = new EnumMap<>(EdgeKind.class);
        int[] byConfidence = new int[Confidence.values().length];
        for (Edge e : edges) {
            byEdgeKind.computeIfAbsent(e.kind(), k -> new int[1 + Confidence.values().length])[0]++;
            byEdgeKind.get(e.kind())[1 + e.confidence().ordinal()]++;
            byConfidence[e.confidence().ordinal()]++;
        }
        out.append("\n## 종류별 엣지 수\n\n");
        if (byEdgeKind.isEmpty()) {
            out.append("없음\n");
        } else {
            out.append("| 종류 | 개수 | RESOLVED | INFERRED | UNRESOLVED |\n|---|---|---|---|---|\n");
            byEdgeKind.forEach((kind, c) -> out.append("| ").append(kind).append(" | ").append(c[0]).append(" | ")
                    .append(c[1 + Confidence.RESOLVED.ordinal()]).append(" | ").append(c[1 + Confidence.INFERRED.ordinal()])
                    .append(" | ").append(c[1 + Confidence.UNRESOLVED.ordinal()]).append(" |\n"));
        }
        out.append("\n## 신뢰도별 엣지 수\n\n| 신뢰도 | 개수 |\n|---|---|\n");
        for (Confidence c : Confidence.values()) {
            out.append("| ").append(c).append(" | ").append(byConfidence[c.ordinal()]).append(" |\n");
        }
    }

    /** 링커가 다른 방법으로 풀었다고 표시한 미해결 노드인지({@code resolvedBy}가 있다). 미해결 집계에서 뺀다. */
    public static boolean resolvedAnotherWay(GraphModel graph, String nodeId) {
        return graph.node(nodeId).map(n -> n.kind() == NodeKind.UNRESOLVED && n.attr("resolvedBy") != null).orElse(false);
    }

    /**
     * 미해결 참조를 엣지 기준으로 한 번씩 세고 분류별로 묶는다. 진단 UNRESOLVED_REFERENCE와 따로 세지 않는다.
     *
     * <p>위의 "신뢰도별 엣지 수"는 그래프 그대로이고 이 절은 풀린 것을 뺀 수다. 두 수가 다르면 까닭을 한 줄로 적는다: 같은 지점에서 다른 방법
     * (접두 일치)으로 풀린 참조(대상 미해결 노드의 {@code resolvedBy})를 뺐고, 클라이언트 라우트의 대응은 "화면 요청" 절에서 세며,
     * 신뢰도는 UNRESOLVED가 아니지만 대상이 미해결 노드인 엣지는 더한다.
     */
    private static void unresolved(StringBuilder out, GraphModel graph, Collection<Edge> edges) {
        List<Edge> found = new ArrayList<>();
        int unresolvedEdges = 0;
        int resolvedElsewhere = 0;
        int clientRoutes = 0;
        int extra = 0;
        Set<String> resolvedHow = new TreeSet<>();
        for (Edge e : edges) {
            boolean unresolvedConfidence = e.confidence() == Confidence.UNRESOLVED;
            if (unresolvedConfidence) {
                unresolvedEdges++;
            }
            if (unresolvedConfidence || graph.node(e.to()).map(n -> n.kind() == NodeKind.UNRESOLVED).orElse(true)) {
                // 클라이언트 라우트(화면 안의 이동)는 엔드포인트 대상이 아니다: 맞는 엔드포인트가 없어도 미해결로 세지 않는다("화면 요청" 절에서 센다).
                if (e.kind() == EdgeKind.MATCHES && NodeLabels.isClientRoute(graph.node(e.from()).orElse(null))) {
                    clientRoutes += unresolvedConfidence ? 1 : 0;
                    continue;
                }
                // 같은 지점에서 다른 방법(접두 일치 등)으로 풀린 참조(미해결 노드의 resolvedBy)는 미해결이 아니다.
                if (resolvedAnotherWay(graph, e.to())) {
                    resolvedElsewhere += unresolvedConfidence ? 1 : 0;
                    resolvedHow.add(String.valueOf(graph.node(e.to()).map(n -> n.attr("resolvedBy")).orElse(null)));
                    continue;
                }
                extra += unresolvedConfidence ? 0 : 1;
                found.add(e);
            }
        }
        found.sort(Comparator.comparing(Edge::from).thenComparing(Edge::to).thenComparing(e -> e.kind().name())
                .thenComparing(e -> e.location() == null ? "" : e.location().toString()));
        Map<String, List<Edge>> byCategory = new TreeMap<>();
        int total = found.size();
        for (Edge e : found) {
            byCategory.computeIfAbsent(category(e, graph.node(e.to()).orElse(null)), k -> new ArrayList<>()).add(e);
        }
        String difference = unresolvedDifference(unresolvedEdges, resolvedElsewhere, resolvedHow, clientRoutes, extra);
        out.append("\n## 미해결 참조 (").append(total).append(")\n\n");
        if (total == 0) {
            out.append("없음").append(difference.isEmpty() ? "" : "." + difference).append('\n');
            return;
        }
        out.append("해석하지 못한 참조다. 엣지 하나를 한 건으로 센다.").append(difference).append("\n\n| 분류 | 건수 |\n|---|---|\n");
        byCategory.forEach((category, list) -> out.append("| ").append(Texts.mdCell(category)).append(" | ").append(list.size())
                .append(" |\n"));
        for (Map.Entry<String, List<Edge>> entry : byCategory.entrySet()) {
            out.append("\n### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n\n");
            out.append("| 참조한 곳 | 엣지 | 대상 | 위치 |\n|---|---|---|---|\n");
            for (Edge e : entry.getValue()) {
                Node target = graph.node(e.to()).orElse(null);
                String text = e.to().startsWith("unresolved:") && target != null ? target.name() : e.to();
                // 맞는 엔드포인트를 찾지 못한 action 없는 폼: 원문 "POST (selfSubmit)" 대신 그 화면을 그리는 엔드포인트의 경로로 풀어 적는다.
                if (e.kind() == EdgeKind.MATCHES && NodeLabels.unresolvedSelfSubmitMethod(graph, e.to()) != null) {
                    text = NodeLabels.unresolvedSelfSubmitLabel(graph, e.to(), e.from());
                } else if (NodeLabels.unresolvedSelfSubmitMethod(graph, e.to()) != null) {
                    text = NodeLabels.label(graph, e.to());
                }
                // 원문에는 인자를 적지 않으므로(vo.set(…)) 엣지의 인자 수로 풀어 적는다.
                if (e.attr("argCount") instanceof Number argCount && text != null && text.endsWith("(…)")) {
                    text = text.substring(0, text.length() - 3) + "(인자 " + argCount.intValue() + "개)";
                }
                String location = e.location() != null ? NodeLabels.location(e.location()) : NodeLabels.location(graph, e.from());
                out.append("| ").append(Texts.mdCode(NodeLabels.tagged(graph, e.from()))).append(" | ").append(e.kind()).append(" | ")
                        .append(Texts.mdCode(text)).append(" | ").append(locationCell(location)).append(" |\n");
            }
        }
    }

    /**
     * "미해결 참조"의 건수가 신뢰도 UNRESOLVED 엣지 수와 다른 까닭(앞에 공백 하나를 둔 문장들). 같으면 빈 문자열.
     *
     * @param resolvedHow 풀린 참조의 {@code resolvedBy} 값들. {@code prefixMatch}뿐이면 "접두 일치"로 적는다
     */
    private static String unresolvedDifference(int unresolvedEdges, int resolvedElsewhere, Set<String> resolvedHow, int clientRoutes, int extra) {
        StringBuilder s = new StringBuilder();
        if (resolvedElsewhere > 0 || clientRoutes > 0) {
            s.append(" 신뢰도가 `UNRESOLVED`인 엣지 ").append(unresolvedEdges).append("건 가운데 ");
            List<String> parts = new ArrayList<>();
            if (resolvedElsewhere > 0) {
                String how = resolvedHow.equals(Set.of("prefixMatch")) ? "접두 일치로" : "다른 방법으로";
                parts.add(resolvedElsewhere + "건은 같은 지점에서 " + how + " 풀려(대상 노드의 `resolvedBy`) 여기서 뺐다");
            }
            if (clientRoutes > 0) {
                parts.add(clientRoutes + "건은 클라이언트 라우트의 대응이라 \"화면 요청\" 절에서 센다");
            }
            s.append(String.join(", ", parts)).append('.');
        }
        if (extra > 0) {
            s.append(" 신뢰도는 `UNRESOLVED`가 아니지만 대상이 미해결 노드인 엣지 ").append(extra).append("건을 더했다.");
        }
        return s.toString();
    }

    /** "미연결 요청" 목록에 싣는 최대 개수. */
    static final int MAX_UNMATCHED_REQUESTS = 30;

    /**
     * 화면 요청({@code HTTP_REQUEST})의 통계. {@code report.md}의 "화면 요청" 절과 cli의 힌트가 같은 수를 쓴다.
     *
     * @param total 요청 노드 전부
     * @param sure 엔드포인트에 확정으로 연결된 서버 요청
     * @param inferred 추론으로만 연결된 서버 요청({@code crossModule} 포함)
     * @param unmatchedRequests 엔드포인트에 연결되지 않은 서버 요청(위치순)
     * @param external 외부 URL({@code external=true})
     * @param staticLinks 정적 페이지 링크
     * @param clientRoutes 클라이언트 라우트({@code clientRoute=true})
     */
    public record RequestStats(int total, int sure, int inferred, List<Node> unmatchedRequests, int external, int staticLinks,
            int clientRoutes) {

        public RequestStats {
            unmatchedRequests = List.copyOf(unmatchedRequests);
        }

        /** 엔드포인트에 연결된 서버 요청(확정 + 추론). */
        public int matched() {
            return sure + inferred;
        }

        /** 미연결 서버 요청 수. */
        public int unmatched() {
            return unmatchedRequests.size();
        }

        /** 서버 요청: 연결된 것과 미연결. 외부 URL, 정적 페이지 링크, 클라이언트 라우트는 들지 않는다. */
        public int server() {
            return matched() + unmatched();
        }
    }

    /**
     * 화면 요청의 통계를 낸다. 서버 요청이 아닌 것 셋을 분모에서 뺀다: 외부 URL({@code external=true}),
     * 정적 페이지 링크(엔드포인트에 연결되지 않았고 경로가 {@code .html}·{@code .htm}으로 끝나며, 그 파일이 프로젝트에 있거나 요청을 낸 파일이
     * 정적 페이지인 것: 화면 시안의 링크), 클라이언트 라우트({@code clientRoute=true}). 서버 요청은 엔드포인트에 연결된 것(확정 대응이 있는 것과
     * 추론으로만 이어진 것)과 미연결로 나눈다.
     */
    public static RequestStats requestStats(GraphModel graph) {
        Collection<Node> requests = graph.nodes(NodeKind.HTTP_REQUEST);
        Set<String> pages = new java.util.HashSet<>();
        for (NodeKind kind : List.of(NodeKind.JSP_PAGE, NodeKind.FILE)) {
            for (Node page : graph.nodes(kind)) {
                String path = Ids.remainder(page.id());
                if (isStaticPage(path)) {
                    // 경로의 모든 뒷부분(/a/b.html, /b.html): 컨텍스트 루트를 몰라도 절대 경로 링크와 맞춰 볼 수 있게
                    for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                        pages.add(path.substring(slash));
                    }
                    pages.add("/" + path);
                }
            }
        }
        int sure = 0;
        int inferred = 0;
        int external = 0;
        int statics = 0;
        int routes = 0;
        List<Node> unmatched = new ArrayList<>();
        for (Node request : requests) {
            if (NodeLabels.isClientRoute(request)) {
                routes++;
                continue;
            }
            boolean linked = false;
            boolean certain = false;
            for (Edge e : graph.outgoing(request.id(), EdgeKind.MATCHES)) {
                if (e.confidence() != Confidence.UNRESOLVED && graph.node(e.to()).map(n -> n.kind() == NodeKind.HTTP_ENDPOINT).orElse(false)) {
                    linked = true;
                    // 다른 모듈의 핸들러와 맞춘 대응(crossModule)은 콜트리와 같이 추론으로 본다.
                    certain |= e.confidence() == Confidence.RESOLVED && !e.attrBoolean("crossModule");
                }
            }
            if (linked) {
                if (certain) {
                    sure++;
                } else {
                    inferred++;
                }
            } else if (request.attrBoolean("external")) {
                external++;
            } else if (staticPageLink(request, pages)) {
                statics++;
            } else {
                unmatched.add(request);
            }
        }
        unmatched.sort(Comparator.comparing((Node n) -> n.location() == null ? "" : String.valueOf(n.location().file()))
                .thenComparingInt(n -> n.location() == null ? 0 : n.location().startLine()).thenComparing(Node::id));
        return new RequestStats(requests.size(), sure, inferred, unmatched, external, statics, routes);
    }

    /**
     * "화면 요청" 절. 요청이 있을 때만 쓴다. 통계({@link #requestStats})와 미연결 요청을 위치순으로 {@value #MAX_UNMATCHED_REQUESTS}개까지 싣는다.
     */
    private static void requests(StringBuilder out, GraphModel graph) {
        RequestStats stats = requestStats(graph);
        if (stats.total() == 0) {
            return;
        }
        int sure = stats.sure();
        int inferred = stats.inferred();
        int external = stats.external();
        int statics = stats.staticLinks();
        int routes = stats.clientRoutes();
        List<Node> unmatched = stats.unmatchedRequests();
        int matched = sure + inferred;
        int server = matched + unmatched.size();
        out.append("\n## 화면 요청 (").append(stats.total()).append(")\n\n");
        out.append("서버 요청 ").append(server).append("건 중 연결 ").append(matched).append("(확정 ").append(sure).append(", 추론 ").append(inferred)
                .append("), 미연결 ").append(unmatched.size()).append('.');
        if (server > 0) {
            out.append(" 엔드포인트 연결률 ").append(Math.round(matched * 100.0 / server)).append("%.");
        }
        out.append("\n\n| 구분 | 건수 |\n|---|---|\n");
        out.append("| 서버 요청 | ").append(server).append(" |\n");
        out.append("| 연결됨(확정) | ").append(sure).append(" |\n");
        out.append("| 연결됨(추론) | ").append(inferred).append(" |\n");
        out.append("| 미연결 | ").append(unmatched.size()).append(" |\n");
        out.append("| 외부 URL | ").append(external).append(" |\n");
        out.append("| 정적 페이지 링크 | ").append(statics).append(" |\n");
        out.append("| 화면 내 이동(클라이언트 라우트) | ").append(routes).append(" |\n");
        List<String> excluded = new ArrayList<>();
        if (external > 0) {
            excluded.add("외부 URL");
        }
        if (statics > 0) {
            excluded.add("정적 페이지 링크(프로젝트의 정적 `.html` 파일을 가리키거나 정적 페이지에서 나가는 `.html` 링크)");
        }
        if (routes > 0) {
            excluded.add("클라이언트 라우트");
        }
        List<String> notes = new ArrayList<>();
        if (!excluded.isEmpty()) {
            notes.add(String.join(", ", excluded) + "는 서버 요청이 아니라서 분모에서 뺐다.");
        }
        if (!unmatched.isEmpty()) {
            notes.add("미연결 요청은 아래 [미연결 요청](#미연결-요청)에 있다.");
        }
        if (!notes.isEmpty()) {
            out.append('\n').append(String.join(" ", notes)).append('\n');
        }
        if (unmatched.isEmpty()) {
            return;
        }
        out.append("\n### 미연결 요청\n\n| 위치 | 요청 |\n|---|---|\n");
        for (Node request : unmatched.subList(0, Math.min(MAX_UNMATCHED_REQUESTS, unmatched.size()))) {
            out.append("| ").append(locationCell(NodeLabels.location(request.location()))).append(" | ")
                    .append(Texts.mdCode(NodeLabels.label(graph, request.id()))).append(" |\n");
        }
        if (unmatched.size() > MAX_UNMATCHED_REQUESTS) {
            out.append("\n외 ").append(unmatched.size() - MAX_UNMATCHED_REQUESTS).append("건.\n");
        }
    }

    private static boolean isStaticPage(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    /**
     * 요청이 정적 페이지를 가리키는 링크인지: 경로(쿼리·fragment 제외)가 {@code .html}·{@code .htm}으로 끝나고, 프로젝트에 그 파일이 있거나
     * (요청을 낸 파일의 폴더 기준 상대 경로, 또는 어느 폴더든 그 경로로 끝나는 파일) 요청을 낸 파일 자신이 정적 페이지다(시안의 죽은 링크).
     * 서버 페이지(JSP 등)에서 나가는, 프로젝트에 없는 {@code .html} 경로는 서버 요청으로 둔다({@code *.html}을 서블릿에 매핑한 프로젝트가 있다).
     */
    private static boolean staticPageLink(Node request, Set<String> pages) {
        String path = request.attrString("path");
        if (Texts.isBlank(path)) {
            return false;
        }
        int cut = path.indexOf('?');
        int hash = path.indexOf('#');
        if (hash >= 0 && (cut < 0 || hash < cut)) {
            cut = hash;
        }
        String target = cut < 0 ? path : path.substring(0, cut);
        if (!isStaticPage(target)) {
            return false;
        }
        String file = request.location() == null ? null : request.location().file();
        if (file != null && isStaticPage(file)) {
            return true;
        }
        List<String> segments = new ArrayList<>();
        if (!target.startsWith("/") && file != null && file.contains("/")) {
            segments.addAll(List.of(file.substring(0, file.lastIndexOf('/')).split("/")));
        }
        boolean relative = !segments.isEmpty();
        for (String segment : target.split("/")) {
            if (segment.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
            } else if (!segment.isEmpty() && !segment.equals(".")) {
                segments.add(segment);
            }
        }
        String resolved = "/" + String.join("/", segments);
        if (pages.contains(resolved)) {
            return true;
        }
        // 상대 경로를 요청 파일 기준으로 풀어 못 찾았으면 경로 자체로 한 번 더 본다
        if (relative) {
            List<String> own = new ArrayList<>();
            for (String segment : target.split("/")) {
                if (!segment.isEmpty() && !segment.equals(".") && !segment.equals("..")) {
                    own.add(segment);
                }
            }
            return pages.contains("/" + String.join("/", own));
        }
        return false;
    }

    /**
     * 대상 노드의 category, 없으면 expectedPrefix에서 유도한 분류({@link Ids#danglingCategory}: 전에 만든 그래프의 마무리 단계 노드에는
     * category가 없다), 없으면 ID에서 읽는다.
     */
    private static String category(Edge e, Node target) {
        if (target != null) {
            if (!Texts.isBlank(target.attrString("category"))) {
                return Texts.oneLine(target.attrString("category"));
            }
            if (!Texts.isBlank(target.attrString("expectedPrefix"))) {
                return Texts.oneLine(Ids.danglingCategory(target.attrString("expectedPrefix")));
            }
        }
        if (e.to().startsWith("unresolved:")) {
            String rest = e.to().substring("unresolved:".length());
            int colon = rest.indexOf(':');
            return colon > 0 ? rest.substring(0, colon) : "기타";
        }
        if (target == null || target.kind() == NodeKind.UNRESOLVED) {
            return Ids.danglingCategory(Ids.prefixOf(e.to()));
        }
        // 대상은 해석됐지만 엣지의 신뢰도가 UNRESOLVED인 경우
        return e.kind().name();
    }

    private static void diagnostics(StringBuilder out, GraphModel graph, int maxItems) {
        List<Diagnostic> all = new ArrayList<>(graph.diagnostics());
        all.sort(Comparator.comparing(Diagnostic::code).thenComparing(Diagnostic::severity, Comparator.reverseOrder())
                .thenComparing(d -> d.location() == null ? "" : d.location().file())
                .thenComparingInt(d -> d.location() == null ? 0 : d.location().startLine())
                .thenComparingInt(d -> d.location() == null ? 0 : d.location().startColumn())
                .thenComparing(Diagnostic::message));
        out.append("\n## 진단 (").append(all.size()).append(")\n\n");
        if (all.isEmpty()) {
            out.append("없음\n");
            return;
        }
        Map<String, List<Diagnostic>> byCode = new TreeMap<>();
        all.forEach(d -> byCode.computeIfAbsent(d.code(), k -> new ArrayList<>()).add(d));
        out.append("| 코드 | ERROR | WARN | INFO | 합계 |\n|---|---|---|---|---|\n");
        byCode.forEach((code, list) -> out.append("| ").append(Texts.mdCell(code)).append(" | ").append(count(list, Severity.ERROR))
                .append(" | ").append(count(list, Severity.WARN)).append(" | ").append(count(list, Severity.INFO)).append(" | ")
                .append(list.size()).append(" |\n"));
        for (Map.Entry<String, List<Diagnostic>> entry : byCode.entrySet()) {
            List<Diagnostic> list = entry.getValue();
            out.append("\n### ").append(entry.getKey()).append(" (").append(list.size()).append(")\n\n");
            if (DiagnosticCodes.UNRESOLVED_REFERENCE.equals(entry.getKey())) {
                out.append("항목은 위의 \"미해결 참조\" 절에 있다.\n");
                continue;
            }
            int shown = maxItems <= 0 ? list.size() : Math.min(maxItems, list.size());
            out.append("| 심각도 | 위치 | 메시지 |\n|---|---|---|\n");
            for (Diagnostic d : list.subList(0, shown)) {
                out.append("| ").append(d.severity()).append(" | ").append(locationCell(NodeLabels.location(d.location()))).append(" | ")
                        .append(Texts.mdCell(d.message())).append(" |\n");
            }
            if (shown < list.size()) {
                out.append("\n외 ").append(list.size() - shown).append("건은 `diagnostics.md`와 `graph.json`에 있다.\n");
            }
        }
    }

    private static long count(List<Diagnostic> list, Severity severity) {
        return list.stream().filter(d -> d.severity() == severity).count();
    }

    private static void tables(StringBuilder out, GraphModel graph, List<Node> nodes, Collection<Edge> edges, int maxItems,
            boolean includeDataLoad) {
        DataLoads.Summary loads = DataLoads.summarize(graph);
        Set<String> excluded = includeDataLoad || loads.isEmpty() ? Set.of() : DataLoads.ids(graph);
        Map<String, Set<String>> statementsByTable = new TreeMap<>();
        for (Edge e : edges) {
            if ((e.kind() == EdgeKind.READS || e.kind() == EdgeKind.WRITES) && !excluded.contains(e.from())) {
                DbGraph.accessedTable(graph, e).ifPresent(t -> statementsByTable.computeIfAbsent(t, k -> new TreeSet<>()).add(e.from()));
            }
        }
        // 적재 구문을 뺐을 때, 스크립트가 적재하는 테이블
        Set<String> loadedOnly = excluded.isEmpty() ? Set.of() : loads.byTable().keySet();
        // DB가 제공하는 시스템 카탈로그(Oracle 데이터 사전, DB2 카탈로그, PostgreSQL pg_catalog·information_schema: attrs.systemCatalog=true)는 뒤에 따로 묶고 의심으로 치지 않는다.
        List<Node> undeclared = new ArrayList<>(nodes.stream().filter(n -> n.kind() == NodeKind.DB_TABLE && !n.declared()).toList());
        undeclared.sort(Comparator.comparing((Node n) -> n.attrBoolean("systemCatalog")).thenComparing(Node::id));
        out.append("\n## DDL에 없는 테이블 (").append(undeclared.size()).append(")\n\n");
        if (undeclared.isEmpty()) {
            out.append("없음\n");
        } else {
            long suspicious = undeclared.stream().filter(t -> !t.attrBoolean("systemCatalog")
                    && statementsByTable.getOrDefault(t.id(), Set.of()).size() == 1).count();
            long catalogs = undeclared.stream().filter(t -> t.attrBoolean("systemCatalog")).count();
            out.append("SQL에서 참조했지만 DDL에서 정의를 찾지 못한 테이블이다.");
            if (suspicious > 0) {
                out.append(" 참조하는 구문이 하나뿐인 테이블 ").append(suspicious)
                        .append("개는 의심으로 표시했다(오타이거나, 구문을 끝까지 파싱하지 못해 복구하면서 나온 이름일 수 있다).");
            }
            if (catalogs > 0) {
                out.append(" DB가 제공하는 시스템 카탈로그(데이터 사전) ").append(catalogs).append("개는 뒤에 따로 묶었다.");
            }
            out.append("\n\n| 테이블 | 참조하는 구문 수 | 구문 (최대 5개) | 비고 |\n|---|---|---|---|\n");
            for (Node t : undeclared) {
                Set<String> statements = statementsByTable.getOrDefault(t.id(), Set.of());
                List<String> names = statements.stream().limit(5).map(s -> Texts.mdCode(NodeLabels.statementName(graph.node(s).orElse(null), s)))
                        .toList();
                String note = t.attrBoolean("systemCatalog") ? SYSTEM_CATALOG : statements.size() == 1 ? SUSPICIOUS_TABLE
                        : statements.isEmpty() && loadedOnly.contains(t.id()) ? DATA_LOAD_ONLY : "";
                out.append("| ").append(Texts.mdCode(Ids.remainder(t.id()))).append(" | ").append(statements.size()).append(" | ")
                        .append(String.join(", ", names)).append(" | ").append(note).append(" |\n");
            }
        }
        List<Node> unused = nodes.stream()
                .filter(n -> n.kind() == NodeKind.DB_TABLE && n.declared() && !statementsByTable.containsKey(n.id())).toList();
        out.append("\n## 어떤 구문에서도 참조되지 않는 테이블 (").append(unused.size()).append(")\n\n");
        if (unused.isEmpty()) {
            out.append("없음\n");
        } else {
            unused.forEach(t -> out.append("- ").append(Texts.mdCode(Ids.remainder(t.id()))).append(located(t))
                    .append(loadedOnly.contains(t.id()) ? " — " + DATA_LOAD_ONLY : "").append('\n'));
        }
        undeclaredColumns(out, graph);
        borrowedStatements(out, nodes);
        dataLoads(out, loads, maxItems, includeDataLoad);

        // 동적 SQL: 어느 테이블에도 넣을 수 없는 구문을 조용히 빼지 않는다(CRUD 매트릭스의 (동적) 열과 같은 구문이다).
        List<DynamicStatement> dynamic = DynamicStatement.find(graph);
        out.append("\n## 대상 테이블을 정적으로 알 수 없는 구문 (").append(dynamic.size()).append(")\n\n");
        if (dynamic.isEmpty()) {
            out.append("없음\n");
        } else {
            out.append("동적 SQL이라 읽고 쓰는 테이블을 알 수 없다. 테이블별 집계(CRUD 매트릭스, 영향도)에는 테이블 없이 따로 나온다.\n\n");
            out.append("| 구문 | CRUD | 위치 |\n|---|---|---|\n");
            for (DynamicStatement d : dynamic) {
                out.append("| ").append(Texts.mdCode(d.id())).append(" | ").append(d.access()).append(" | ")
                        .append(locationCell(d.location())).append(" |\n");
            }
        }
    }

    /** DDL에 없는데 SQL이 참조하는 컬럼(정의가 있는 테이블의 {@code declared=false} 컬럼)이 있을 때만 쓰는 절. */
    private static void undeclaredColumns(StringBuilder out, GraphModel graph) {
        Map<String, List<UndeclaredColumns.Column>> byTable = UndeclaredColumns.find(graph);
        if (byTable.isEmpty()) {
            return;
        }
        int total = byTable.values().stream().mapToInt(List::size).sum();
        out.append("\n## SQL과 DDL의 컬럼 불일치 (").append(total).append(")\n\n");
        out.append("SQL이 참조하지만 그 테이블의 DDL에 없는 컬럼이다. DDL이 오래됐거나 다른 방언·버전의 DDL이 선택됐을 수 있다.\n\n");
        out.append("| 테이블 | 컬럼 | 참조하는 구문 수 | 구문 (최대 5개) |\n|---|---|---|---|\n");
        byTable.forEach((tableId, columns) -> {
            for (UndeclaredColumns.Column c : columns) {
                List<String> names = c.statements().stream().limit(5)
                        .map(s -> Texts.mdCode(NodeLabels.statementName(graph.node(s).orElse(null), s))).toList();
                out.append("| ").append(Texts.mdCode(Ids.remainder(tableId))).append(" | ").append(Texts.mdCode(c.name())).append(" | ")
                        .append(c.statements().size()).append(" | ").append(String.join(", ", names)).append(" |\n");
            }
        });
    }

    /**
     * 다른 방언 파일에서 보충한 구문({@code SQL_STATEMENT}의 {@code attrs.borrowedFrom=<방언>}: 선택한 방언의 매퍼 파일에 없어 다른 방언 파일의
     * 정의를 쓴 것)이 있을 때만 쓰는 절. 그 구문의 SQL은 선택한 방언의 것이 아닐 수 있다.
     */
    private static void borrowedStatements(StringBuilder out, List<Node> nodes) {
        // 데이터 적재 묶음의 borrowedFrom(다른 방언 파일의 묶음)은 "데이터 적재 스크립트" 절에서 밝힌다: 매퍼 구문이 아니다.
        List<Node> borrowed = nodes.stream().filter(n -> n.kind() == NodeKind.SQL_STATEMENT && !DataLoads.isDataLoad(n)
                && !Texts.isBlank(n.attrString("borrowedFrom"))).toList();
        if (borrowed.isEmpty()) {
            return;
        }
        out.append("\n## 다른 방언 파일에서 보충한 구문 (").append(borrowed.size()).append(")\n\n");
        out.append("선택한 방언의 매퍼 파일에 없어 다른 방언 파일의 정의를 쓴 구문이다. SQL이 선택한 방언의 것이 아닐 수 있다.\n\n");
        out.append("| 구문 | 보충한 방언 | 위치 |\n|---|---|---|\n");
        for (Node n : borrowed) {
            out.append("| ").append(Texts.mdCode(Ids.remainder(n.id()))).append(" | ").append(Texts.mdCell(n.attrString("borrowedFrom"))).append(" | ")
                    .append(locationCell(NodeLabels.location(n.location()))).append(" |\n");
        }
    }

    /**
     * 데이터 적재 구문이 있을 때만 쓰는 절: 파일·테이블·행 수 요약과 파일별 표. 고른 방언에 그 테이블의 적재가 없어 다른 방언 파일의 것을 쓴
     * 묶음({@code borrowedFrom})은 그 수를 한 줄로 적고 파일 이름 뒤에 방언을 밝힌다.
     */
    private static void dataLoads(StringBuilder out, DataLoads.Summary loads, int maxItems, boolean included) {
        if (loads.isEmpty()) {
            return;
        }
        DataLoads.Load total = loads.total();
        out.append("\n## 데이터 적재 스크립트\n\n");
        out.append("파일 ").append(loads.byFile().size()).append("개, 테이블 ").append(loads.byTable().size()).append("개, 행 ")
                .append(total.rows()).append("건");
        if (total.unknownRows() > 0) {
            out.append(" 이상(행 수를 모르는 구문 ").append(total.unknownRows()).append("개)");
        }
        out.append(". 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 구문 ").append(total.statements()).append("개다. ");
        out.append(included ? "`export.includeDataLoad=true`라서 CRUD 매트릭스, 테이블 명세의 구문 수, 콜트리, 위의 테이블 점검에 넣었다.\n"
                : "CRUD 매트릭스, 테이블 명세의 구문 수, 콜트리, 위의 테이블 점검에서는 뺐다(`export.includeDataLoad=true`로 넣는다).\n");
        if (loads.borrowedStatements() > 0) {
            List<String> byDialect = new ArrayList<>();
            loads.borrowed().forEach((dialect, count) -> byDialect.add(Texts.mdCell(dialect) + " " + count + "개"));
            out.append("이 가운데 묶음 ").append(loads.borrowedStatements()).append("개는 고른 방언에 그 테이블의 적재가 없어 다른 방언 파일의 것이다(")
                    .append(String.join(", ", byDialect)).append(". 파일 이름 뒤에 `(다른 방언 파일: <방언>)`).\n");
        }
        out.append("\n| 파일 | 테이블 수 | 행 수 |\n|---|---|---|\n");
        int shown = maxItems <= 0 ? loads.byFile().size() : Math.min(maxItems, loads.byFile().size());
        int n = 0;
        for (Map.Entry<String, DataLoads.FileLoad> e : loads.byFile().entrySet()) {
            if (n++ >= shown) {
                break;
            }
            String borrowed = e.getValue().borrowedNote();
            out.append("| ").append(Texts.mdCode(e.getKey())).append(borrowed == null ? "" : " (" + Texts.mdCell(borrowed) + ")").append(" | ")
                    .append(e.getValue().tables().size()).append(" | ").append(e.getValue().load().rowsText("")).append(" |\n");
        }
        if (shown < loads.byFile().size()) {
            out.append("\n외 ").append(loads.byFile().size() - shown).append("개 파일.");
            // 표에서 잘린 파일 가운데 다른 방언 파일은 이름을 남긴다(어느 파일의 적재가 다른 방언의 것인지 알 수 있게).
            List<String> hidden = new ArrayList<>();
            int index = 0;
            for (Map.Entry<String, DataLoads.FileLoad> e : loads.byFile().entrySet()) {
                if (index++ >= shown && e.getValue().borrowedNote() != null) {
                    hidden.add(Texts.mdCode(e.getKey()) + " (" + Texts.mdCell(String.join(", ", e.getValue().borrowedFrom())) + ")");
                }
            }
            if (!hidden.isEmpty()) {
                out.append(" 표에 없는 다른 방언 파일: ").append(String.join(", ", hidden)).append('.');
            }
            out.append('\n');
        }
    }

    private static void mappers(StringBuilder out, GraphModel graph, List<Node> nodes) {
        List<Node> orphanMethods = new ArrayList<>();
        Set<String> mapperTypes = new HashSet<>();
        for (Node n : nodes) {
            if (n.kind() == NodeKind.JAVA_TYPE
                    && ("mapper".equals(n.attrString("stereotype")) || graph.hasNode(Ids.mybatisMapper(Ids.remainder(n.id()))))) {
                mapperTypes.add(n.id());
            }
        }
        for (Node n : nodes) {
            if (n.kind() != NodeKind.JAVA_METHOD || n.attrBoolean("constructor") || !n.declared()) {
                continue;
            }
            List<String> modifiers = n.attrList("modifiers");
            if (modifiers.contains("static") || modifiers.contains("default") || modifiers.contains("private")) {
                continue;
            }
            boolean inMapper = graph.incoming(n.id(), EdgeKind.CONTAINS).stream().anyMatch(e -> mapperTypes.contains(e.from()));
            if (inMapper && graph.outgoing(n.id(), EdgeKind.EXECUTES).isEmpty()) {
                orphanMethods.add(n);
            }
        }
        out.append("\n## 대응 구문이 없는 매퍼 메서드 (").append(orphanMethods.size()).append(")\n\n");
        if (orphanMethods.isEmpty()) {
            out.append("없음\n");
        } else {
            orphanMethods.forEach(m -> out.append("- ").append(Texts.mdCode(NodeLabels.methodLabel(Ids.remainder(m.id()), true)))
                    .append(located(m)).append('\n'));
        }

        List<Node> orphanStatements = nodes.stream().filter(n -> n.kind() == NodeKind.SQL_STATEMENT
                && n.id().startsWith("mybatis:stmt:") && graph.incoming(n.id(), EdgeKind.EXECUTES).isEmpty()).toList();
        out.append("\n## 호출하는 곳이 없는 매퍼 구문 (").append(orphanStatements.size()).append(")\n\n");
        if (orphanStatements.isEmpty()) {
            out.append("없음\n");
        } else {
            orphanStatements.forEach(s -> out.append("- ").append(Texts.mdCode(Ids.remainder(s.id()))).append(located(s)).append('\n'));
        }

        // 실행하는 메서드는 있지만 그 메서드를 부르는 곳이 없는 구문
        Map<Node, List<String>> uncalled = new LinkedHashMap<>();
        for (Node n : nodes) {
            if (n.kind() != NodeKind.SQL_STATEMENT || !n.id().startsWith("mybatis:stmt:")) {
                continue;
            }
            List<String> executors = new ArrayList<>();
            boolean used = false;
            for (Edge e : graph.incoming(n.id(), EdgeKind.EXECUTES)) {
                boolean method = graph.node(e.from()).map(from -> from.kind() == NodeKind.JAVA_METHOD).orElse(false);
                if (!method || hasCaller(graph, e.from())) {
                    // 배치 스텝이 직접 실행하거나, 실행하는 메서드를 부르는 곳이 있다
                    used = true;
                    break;
                }
                if (!executors.contains(e.from())) {
                    executors.add(e.from());
                }
            }
            if (!used && !executors.isEmpty()) {
                executors.sort(Comparator.naturalOrder());
                uncalled.put(n, executors);
            }
        }
        out.append("\n## Java 호출자가 없는 매퍼 구문 (").append(uncalled.size()).append(")\n\n");
        if (uncalled.isEmpty()) {
            out.append("없음\n");
        } else {
            out.append("구문을 실행하는 메서드(← 뒤)는 있지만 그 메서드를(재정의·구현 관계인 메서드 포함) 부르는 Java 코드, 엔드포인트, 배치 스텝이 없다.\n\n");
            uncalled.forEach((statement, executors) -> {
                List<String> names = executors.stream().map(m -> Texts.mdCode(NodeLabels.methodLabel(Ids.remainder(m), true))).toList();
                out.append("- ").append(Texts.mdCode(Ids.remainder(statement.id()))).append(located(statement)).append(" ← ")
                        .append(String.join(", ", names)).append('\n');
            });
        }
    }

    /**
     * 메서드를 부르는 곳이 있는지: 그 메서드, 그것이 (전이적으로) 재정의·구현한 상위 메서드, 그것을 재정의·구현한 메서드 가운데
     * 하나라도 들어오는 CALLS(같은 묶음 안의 호출 제외), USES(배치 스텝의 구성 요소), ROUTES_TO(엔드포인트의 핸들러)가 있으면 true.
     * 인터페이스로 부르고 구현이 실행하는 DAO, 하위 매퍼 인터페이스로 부르는 매퍼 메서드를 호출된 것으로 보기 위해서다.
     */
    private static boolean hasCaller(GraphModel graph, String methodId) {
        Set<String> family = new TreeSet<>();
        family.add(methodId);
        related(graph, methodId, true, family);
        related(graph, methodId, false, family);
        for (String member : family) {
            for (Edge e : graph.incoming(member, EdgeKind.CALLS, EdgeKind.USES, EdgeKind.ROUTES_TO)) {
                if (!family.contains(e.from())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** OVERRIDES를 한 방향으로 끝까지 따라가 닿는 메서드를 모은다. up이면 재정의한 상위 쪽, 아니면 재정의·구현한 하위 쪽. */
    private static void related(GraphModel graph, String methodId, boolean up, Set<String> into) {
        List<String> frontier = List.of(methodId);
        Set<String> seen = new HashSet<>(frontier);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (Edge e : up ? graph.outgoing(id, EdgeKind.OVERRIDES) : graph.incoming(id, EdgeKind.OVERRIDES)) {
                    String other = up ? e.to() : e.from();
                    if (seen.add(other)) {
                        into.add(other);
                        next.add(other);
                    }
                }
            }
            frontier = next;
        }
    }

    private static String located(Node node) {
        String location = NodeLabels.location(node.location());
        return location == null ? "" : " (" + Texts.mdCode(location) + ")";
    }
}
