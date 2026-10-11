package io.codeatlas.cli.verify;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.GraphTraversal;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 정답 대조기. 그래프와 정답 목록({@link ExpectedSet})을 받아 항목별 판정을 낸다.
 * 비교 규칙은 samples/sample-shop/README.md 5절을 따른다.
 *
 * <ul>
 *   <li>엣지의 신뢰도: UNRESOLVED 엣지는 있는 것으로 치지 않는다. INFERRED는 통과로 치되 {@link Verdict#INFERRED_PASS}로 구분한다.</li>
 *   <li>{@code altTo}/{@code altFrom}: 어느 쪽이든 맞으면 통과.</li>
 *   <li>{@code undirected}: 방향을 무시한다. 뒤집힌 엣지는 leftColumns와 rightColumns를 바꿔 비교한다.</li>
 *   <li>{@code fromRoutine}: 줄 번호가 달라도 그 루틴이 CONTAINS 하는 구문 중 하나가 같은 종류의 엣지를 가지면 통과(느슨한 통과).</li>
 *   <li>{@code fromFile}+{@code line}(SQL 파일 구문): 그 파일의 구문 중 줄이 가까운 것(위치 범위에 들거나 시작 줄이 3줄 이내)이면 통과(느슨한 통과).</li>
 *   <li>{@code statement}: JOINS의 근거 구문이 같은 엣지를 먼저 고르고, 구문만 다르면 느슨한 통과.</li>
 *   <li>{@code fromFile/httpMethod/requestUrl → to}: 그 파일에서 나온 HTTP_REQUEST 중 메서드와 URL이 맞는 것이 to로 MATCHES를 가지면 통과.</li>
 *   <li>함정: {@code *}, 접두사{@code *} 와일드카드. 신뢰도와 상관없이 걸리면 위반이다.</li>
 * </ul>
 */
public final class ExpectedChecker {

    /** SQL 파일 구문의 줄 번호 허용 오차. */
    static final int FILE_STATEMENT_LINE_TOLERANCE = 3;

    private static final String ROUTINE_PREFIX = "db:routine:";
    private static final String CONTEXT_PATH_PLACEHOLDER = "{*}";

    private final GraphModel graph;
    private final CheckOptions options;
    private final Map<String, Set<String>> routineStatements = new HashMap<>();

    public ExpectedChecker(GraphModel graph, CheckOptions options) {
        this.graph = graph;
        this.options = options;
    }

    public VerificationResult check(ExpectedSet expected) {
        List<EdgeResult> edges = expected.mustHaveEdges().stream().map(this::checkEdge).toList();
        List<NodeResult> nodes = expected.mustHaveNodes().stream().map(this::checkNode).toList();
        List<Violation> violations = new ArrayList<>();
        for (ExpectedEdge rule : expected.mustNotEdges()) {
            List<Edge> found = forbiddenEdges(rule);
            if (!found.isEmpty()) {
                violations.add(new Violation(rule, found));
            }
        }
        return new VerificationResult(edges, nodes, violations, expected.mustNotEdges().size());
    }

    // ------------------------------------------------------------------ must-have 노드

    NodeResult checkNode(ExpectedNode expected) {
        Optional<Node> found = graph.node(expected.id());
        if (found.isEmpty()) {
            return new NodeResult(expected, Verdict.FAIL, FailReason.NODE_MISSING, "");
        }
        Node node = found.get();
        if (node.kind() == NodeKind.UNRESOLVED) {
            return new NodeResult(expected, Verdict.FAIL, FailReason.NODE_MISSING, "매달린 참조로만 있다(UNRESOLVED 노드)");
        }
        if (node.kind() != expected.kind()) {
            return new NodeResult(expected, Verdict.FAIL, FailReason.KIND_MISMATCH, "실제 " + node.kind());
        }
        Map<String, String> diff = AttrMatcher.differences(expected.attrs(), node.attrs());
        if (!diff.isEmpty()) {
            return new NodeResult(expected, Verdict.FAIL, FailReason.ATTR_MISMATCH, describe(diff));
        }
        return new NodeResult(expected, Verdict.PASS, null, node.declared() ? "" : "declared=false");
    }

    // ------------------------------------------------------------------ must-have 엣지

    /** 정답과 맞춰 볼 그래프의 엣지 후보. */
    private record Candidate(Edge edge, boolean reversed, String looseBy, boolean attrsOk, boolean statementOk, Map<String, String> attrDiff) {

        boolean usable() {
            return edge.confidence() != Confidence.UNRESOLVED;
        }
    }

    EdgeResult checkEdge(ExpectedEdge expected) {
        if (expected.isRequestMatch()) {
            return checkRequestMatch(expected);
        }
        EdgeKind kind = expected.edgeKind();
        List<String> froms = concat(expected.from(), expected.altFrom());
        List<String> tos = concat(expected.to(), expected.altTo());

        List<Candidate> candidates = new ArrayList<>();
        for (String from : froms) {
            for (String to : tos) {
                graph.edges(from, kind, to).forEach(e -> candidates.add(candidate(expected, e, false, null)));
                if (expected.undirected() && !from.equals(to)) {
                    graph.edges(to, kind, from).forEach(e -> candidates.add(candidate(expected, e, true, null)));
                }
            }
        }
        Set<String> looseFroms = new LinkedHashSet<>();
        if (expected.fromRoutine() != null) {
            for (String statement : statementsOf(expected.fromRoutine())) {
                if (!froms.contains(statement) && looseFroms.add(statement)) {
                    for (String to : tos) {
                        graph.edges(statement, kind, to).forEach(e -> candidates.add(candidate(expected, e, false, "fromRoutine")));
                    }
                }
            }
        }
        if (expected.fromFile() != null) {
            for (String statement : fileStatementsNear(expected.fromFile(), expected.line())) {
                if (!froms.contains(statement) && looseFroms.add(statement)) {
                    for (String to : tos) {
                        graph.edges(statement, kind, to).forEach(e -> candidates.add(candidate(expected, e, false, "fromFile")));
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            boolean fromPresent = froms.stream().anyMatch(this::present) || !looseFroms.isEmpty();
            boolean toPresent = tos.stream().anyMatch(this::present);
            FailReason reason = fromPresent && toPresent ? FailReason.EDGE_MISSING
                    : fromPresent ? FailReason.TO_NODE_MISSING
                    : toPresent ? FailReason.FROM_NODE_MISSING : FailReason.BOTH_NODES_MISSING;
            return new EdgeResult(expected, Verdict.FAIL, reason, "", null, false, false);
        }
        return judge(expected, candidates);
    }

    private Candidate candidate(ExpectedEdge expected, Edge edge, boolean reversed, String looseBy) {
        Map<String, String> diff = AttrMatcher.differences(expected.attrs(), edge.attrs(), reversed);
        return new Candidate(edge, reversed, looseBy, diff.isEmpty(), statementMatches(expected, edge), diff);
    }

    private boolean statementMatches(ExpectedEdge expected, Edge edge) {
        if (expected.statement() == null) {
            return true;
        }
        String actual = edge.attrString("statement");
        if (expected.statement().equals(actual)) {
            return true;
        }
        return actual != null && expected.statementRoutine() != null && statementsOf(expected.statementRoutine()).contains(actual);
    }

    /** 후보 가운데 가장 좋은 것으로 판정한다: 쓸 수 있는(UNRESOLVED가 아닌) 것, 속성이 맞는 것, RESOLVED, 정확한 것, 근거 구문이 맞는 것 순. */
    private EdgeResult judge(ExpectedEdge expected, List<Candidate> candidates) {
        Comparator<Candidate> best = Comparator.comparing((Candidate c) -> !c.usable())
                .thenComparing(c -> !c.attrsOk())
                .thenComparing(c -> c.edge().confidence() != Confidence.RESOLVED)
                .thenComparing(c -> c.looseBy() != null)
                .thenComparing(c -> !c.statementOk());
        Candidate chosen = candidates.stream().min(best).orElseThrow();
        Edge edge = chosen.edge();
        if (!chosen.usable()) {
            return new EdgeResult(expected, Verdict.FAIL, FailReason.EDGE_UNRESOLVED, "", edge, false, false);
        }
        Verdict passed = edge.confidence() == Confidence.INFERRED ? Verdict.INFERRED_PASS : Verdict.PASS;
        List<String> notes = new ArrayList<>();
        boolean loose = false;
        if (chosen.looseBy() != null) {
            loose = true;
            notes.add("느슨한 통과(" + chosen.looseBy() + "): 실제 from " + edge.from());
        }
        if (!chosen.statementOk()) {
            loose = true;
            notes.add("근거 구문(statement) 다름: 실제 " + edge.attrString("statement"));
        }
        if (!chosen.attrsOk()) {
            notes.add(0, describe(chosen.attrDiff()));
            if (options.strictEdgeAttrs()) {
                return new EdgeResult(expected, Verdict.FAIL, FailReason.ATTR_MISMATCH, String.join("; ", notes), edge, loose, true);
            }
            return new EdgeResult(expected, passed, null, "attrs 불일치(무시): " + String.join("; ", notes), edge, loose, true);
        }
        return new EdgeResult(expected, passed, null, String.join("; ", notes), edge, loose, false);
    }

    private static String describe(Map<String, String> diff) {
        return diff.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining(", "));
    }

    private static List<String> concat(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        for (String id : rest) {
            if (!all.contains(id)) {
                all.add(id);
            }
        }
        return all;
    }

    /** 그 ID의 노드가 있는가. 매달린 엣지 처리로 생긴 UNRESOLVED 종류 노드는 없는 것으로 본다. */
    private boolean present(String id) {
        return graph.node(id).filter(n -> n.kind() != NodeKind.UNRESOLVED).isPresent();
    }

    /** 루틴이 CONTAINS 하는 구문 ID. ID 규칙({@code sql:stmt:<루틴 이름>:<줄>})으로 그 루틴의 것임이 드러나는 구문도 넣는다. */
    Set<String> statementsOf(String routineId) {
        return routineStatements.computeIfAbsent(routineId, id -> {
            Set<String> result = new LinkedHashSet<>();
            for (String reached : GraphTraversal.reachable(graph, id, GraphTraversal.Direction.OUT, 3, EdgeKind.CONTAINS)) {
                if (graph.node(reached).filter(n -> n.kind() == NodeKind.SQL_STATEMENT).isPresent()) {
                    result.add(reached);
                }
            }
            if (id.startsWith(ROUTINE_PREFIX)) {
                String prefix = "sql:stmt:" + id.substring(ROUTINE_PREFIX.length()) + ":";
                for (Node node : graph.nodes(NodeKind.SQL_STATEMENT)) {
                    if (node.id().startsWith(prefix) && node.id().substring(prefix.length()).matches("\\d+")) {
                        result.add(node.id());
                    }
                }
            }
            return result;
        });
    }

    /** 그 SQL 파일의 구문 중 위치 범위가 line을 품거나 시작 줄이 허용 오차 안인 것. */
    private List<String> fileStatementsNear(String file, int line) {
        List<String> result = new ArrayList<>();
        String prefix = "sql:stmt:" + file + ":";
        for (Node node : graph.nodes(NodeKind.SQL_STATEMENT)) {
            SourceLocation location = node.location();
            boolean inFile = node.id().startsWith(prefix) || (location != null && location.file().equals(file) && node.id().startsWith("sql:stmt:"));
            if (!inFile) {
                continue;
            }
            int start = location != null && location.startLine() > 0 ? location.startLine() : lineOfId(node.id(), prefix);
            int end = location != null ? Math.max(location.endLine(), start) : start;
            if (start > 0 && ((start <= line && line <= end) || Math.abs(start - line) <= FILE_STATEMENT_LINE_TOLERANCE)) {
                result.add(node.id());
            }
        }
        return result;
    }

    private static int lineOfId(String id, String prefix) {
        if (id.startsWith(prefix)) {
            try {
                return Integer.parseInt(id.substring(prefix.length()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ MATCHES (fromFile / httpMethod / requestUrl)

    private EdgeResult checkRequestMatch(ExpectedEdge expected) {
        List<Node> inFile = requestsOf(expected.fromFile());
        List<Node> requests = inFile.stream().filter(r -> methodMatches(expected, r) && urlMatches(expected, r)).toList();
        List<String> tos = concat(expected.to(), expected.altTo());
        boolean toPresent = tos.stream().anyMatch(this::present);
        if (requests.isEmpty()) {
            String detail = inFile.isEmpty() ? "그 파일에서 나온 HTTP_REQUEST가 없다"
                    : "메서드와 URL이 맞는 요청이 없다. 그 파일의 요청: " + inFile.stream().map(ExpectedChecker::requestLabel).distinct().limit(12).collect(Collectors.joining(", "));
            return new EdgeResult(expected, Verdict.FAIL, toPresent ? FailReason.FROM_NODE_MISSING : FailReason.BOTH_NODES_MISSING, detail, null, false, false);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Node request : requests) {
            for (String to : tos) {
                graph.edges(request.id(), expected.edgeKind(), to).forEach(e -> candidates.add(candidate(expected, e, false, null)));
            }
        }
        if (candidates.isEmpty()) {
            String detail = "요청 " + requests.stream().map(Node::id).collect(Collectors.joining(", ")) + " 의 MATCHES: "
                    + requests.stream().flatMap(r -> graph.outgoing(r.id(), EdgeKind.MATCHES).stream())
                            .map(e -> e.to() + "(" + e.confidence() + ")").collect(Collectors.joining(", ", "[", "]"));
            return new EdgeResult(expected, Verdict.FAIL, toPresent ? FailReason.EDGE_MISSING : FailReason.TO_NODE_MISSING, detail, null, false, false);
        }
        return judge(expected, candidates);
    }

    /** 그 파일(과 그 파일을 주인으로 하는 인라인 모듈)에서 나온 HTTP_REQUEST 노드. */
    List<Node> requestsOf(String file) {
        List<Node> result = new ArrayList<>();
        for (Node request : graph.nodes(NodeKind.HTTP_REQUEST)) {
            if (requestBelongsTo(request, file)) {
                result.add(request);
            }
        }
        return result;
    }

    private boolean requestBelongsTo(Node request, String file) {
        if (file.equals(fileOfRequestId(request.id())) || (request.location() != null && file.equals(request.location().file()))) {
            return true;
        }
        for (Edge edge : graph.incoming(request.id(), EdgeKind.REQUESTS)) {
            if (edge.location() != null && file.equals(edge.location().file())) {
                return true;
            }
            if (file.equals(fileOfOwner(edge.from()))) {
                return true;
            }
        }
        return false;
    }

    /** REQUESTS 출발 노드(JS_FUNCTION, JS_MODULE, JSP_PAGE)가 속한 파일. 위치가 있으면 위치, 없으면 ID에서 읽는다. */
    private String fileOfOwner(String ownerId) {
        Optional<Node> owner = graph.node(ownerId);
        if (owner.isPresent() && owner.get().location() != null) {
            return owner.get().location().file();
        }
        for (String prefix : List.of("js:function:", "js:module:", "jsp:page:", "file:")) {
            if (ownerId.startsWith(prefix)) {
                String rest = ownerId.substring(prefix.length());
                int hash = prefix.equals("js:function:") ? rest.indexOf('#') : -1;
                return hash >= 0 ? rest.substring(0, hash) : rest;
            }
        }
        return null;
    }

    /** {@code http:request:<METHOD> <URL>@<파일>:<줄>}의 파일. 형식이 다르면 null. */
    private static String fileOfRequestId(String id) {
        int at = id.lastIndexOf('@');
        int colon = id.lastIndexOf(':');
        return at >= 0 && colon > at ? id.substring(at + 1, colon) : null;
    }

    private static int lineOfRequest(Node request) {
        if (request.location() != null && request.location().startLine() > 0) {
            return request.location().startLine();
        }
        String id = request.id();
        int at = id.lastIndexOf('@');
        int colon = id.lastIndexOf(':');
        if (at >= 0 && colon > at) {
            try {
                return Integer.parseInt(id.substring(colon + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String methodOf(Node request) {
        String method = request.attrString("httpMethod");
        if (method == null) {
            String rest = request.id().substring(request.id().indexOf("http:request:") + "http:request:".length());
            int space = rest.indexOf(' ');
            method = space > 0 ? rest.substring(0, space) : "";
        }
        return method;
    }

    /** 요청의 URL 패턴 후보: attrs.path, ID 안의 URL. */
    private static List<String> urlsOf(Node request) {
        List<String> urls = new ArrayList<>();
        if (request.attrString("path") != null) {
            urls.add(request.attrString("path"));
        }
        String id = request.id();
        int space = id.indexOf(' ');
        int at = id.lastIndexOf('@');
        if (space > 0 && at > space) {
            urls.add(id.substring(space + 1, at));
        }
        return urls;
    }

    private static String requestLabel(Node request) {
        List<String> urls = urlsOf(request);
        return methodOf(request) + " " + (urls.isEmpty() ? "?" : urls.get(0));
    }

    private static boolean methodMatches(ExpectedEdge expected, Node request) {
        return expected.httpMethod().equalsIgnoreCase(methodOf(request));
    }

    private static boolean urlMatches(ExpectedEdge expected, Node request) {
        String wanted = normalizeUrl(expected.requestUrl());
        return urlsOf(request).stream().anyMatch(url -> normalizeUrl(url).equals(wanted));
    }

    /** 쿼리스트링과 fragment를 떼고, 맨 앞의 {@code {*}/}(알 수 없는 컨텍스트 경로 접두사)에서 {@code {*}}를 뗀다. */
    static String normalizeUrl(String url) {
        String result = url;
        int cut = indexOfAny(result, '?', '#');
        if (cut >= 0) {
            result = result.substring(0, cut);
        }
        if (result.startsWith(CONTEXT_PATH_PLACEHOLDER + "/")) {
            result = result.substring(CONTEXT_PATH_PLACEHOLDER.length());
        }
        return result;
    }

    private static int indexOfAny(String text, char a, char b) {
        int ia = text.indexOf(a);
        int ib = text.indexOf(b);
        return ia < 0 ? ib : ib < 0 ? ia : Math.min(ia, ib);
    }

    // ------------------------------------------------------------------ 함정 (must-not)

    /** 함정 항목에 걸리는 그래프의 엣지. 신뢰도는 가리지 않는다. */
    List<Edge> forbiddenEdges(ExpectedEdge rule) {
        EdgeKind kind = rule.edgeKind();
        List<Edge> found = new ArrayList<>();
        if (rule.isRequestMatch()) {
            for (Node request : requestsOf(rule.fromFile())) {
                boolean same = methodMatches(rule, request)
                        && (urlMatches(rule, request) || (rule.line() > 0 && rule.line() == lineOfRequest(request)));
                if (same) {
                    for (Edge edge : graph.outgoing(request.id())) {
                        if ((kind == null || edge.kind() == kind) && idMatches(rule.to(), edge.to()) && attrsMatch(rule, edge)) {
                            found.add(edge);
                        }
                    }
                }
            }
            return found;
        }
        Set<String> routineSources = null;
        if (rule.fromRoutine() != null) {
            routineSources = new LinkedHashSet<>(statementsOf(rule.fromRoutine()));
            routineSources.add(rule.fromRoutine());
        }
        for (Edge edge : kind == null ? graph.edges() : graph.edges(kind)) {
            if (!idMatches(rule.to(), edge.to()) || !idMatches(rule.from(), edge.from())) {
                continue;
            }
            if (routineSources != null && !routineSources.contains(edge.from())) {
                continue;
            }
            if (attrsMatch(rule, edge)) {
                found.add(edge);
            }
        }
        return found;
    }

    private static boolean attrsMatch(ExpectedEdge rule, Edge edge) {
        return rule.attrs().isEmpty() || AttrMatcher.differences(rule.attrs(), edge.attrs()).isEmpty();
    }

    /** 와일드카드 매칭: {@code *}는 아무 ID, 끝이 {@code *}면 그 접두사로 시작하는 ID, 그 밖에는 같은 ID. */
    static boolean idMatches(String pattern, String id) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return id.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(id);
    }
}
