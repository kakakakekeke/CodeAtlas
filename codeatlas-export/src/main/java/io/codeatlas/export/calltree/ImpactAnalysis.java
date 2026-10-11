package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.calltree.FlowNavigator.Step;
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.common.DbGraph;
import io.codeatlas.export.common.NodeLabels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 영향도(역추적). 테이블이나 컬럼에서 시작해 그것을 읽고 쓰는 구문, 실행하는 메서드·루틴·배치 스텝,
 * 호출 사슬, 엔드포인트, 그 엔드포인트를 요청하는 화면까지 거슬러 올라가 계층별로 모은다.
 *
 * <p>테이블이 대상이면 구문의 접근 종류에서 R은 테이블 수준 READS가 있을 때만 붙는다(조건절 컬럼의 READS만 있는 DELETE는 D).
 * 컬럼이 대상이면 그 컬럼으로 가는 엣지를 그대로 쓴다. 트리거를 거슬러 갈 때는 그 트리거를 일으키는 구문만 따라간다.
 * 대상 테이블을 정적으로 알 수 없는 동적 구문은 대상에 넣지 않고 {@link ImpactReport#dynamicStatements()}로 따로 알린다.
 *
 * <p>추론한 관계를 거쳐서만 닿은 항목에는 근거가 붙는다({@link Item#inference()}: 리포트의 {@code ~}). 확정 경로가 하나라도 있으면
 * 붙지 않고 그 경로를 적는다. 무엇이 추론 걸음인지는 {@link FlowNavigator}의 설명을 본다.
 *
 * <p>forward로 넘어간 다른 핸들러를 거쳐서만 닿은 항목에는 그 forward가 붙는다({@link Item#forward()}: 리포트의 {@code [forward]}).
 * 같은 확실함의 경로 가운데 forward를 거치지 않는 것이 하나라도 있으면 붙지 않고 그 경로를 적는다. redirect는 따라가지 않는다.
 *
 * <pre>{@code
 * ImpactReport report = ImpactAnalysis.analyze(graph, "ORDERS.STATUS");
 * Files.writeString(out, report.toMarkdown());
 * }</pre>
 */
public final class ImpactAnalysis {

    /** 구문에서 거슬러 올라갈 기본 최대 걸음 수. */
    public static final int DEFAULT_MAX_DEPTH = 30;

    private ImpactAnalysis() {
    }

    /**
     * @param target 테이블·뷰·컬럼의 노드 ID 또는 이름({@code ORDERS}, {@code ORDERS.STATUS}). 이름은 대소문자를 가리지 않는다
     */
    public static ImpactReport analyze(GraphModel graph, String target) {
        return analyze(graph, target, DEFAULT_MAX_DEPTH);
    }

    public static ImpactReport analyze(GraphModel graph, String target, int maxDepth) {
        return analyze(graph, target, maxDepth, false);
    }

    /**
     * @param includeDataLoad true면 데이터 적재 구문({@code attrs.dataLoad=true}: 스크립트의 리터럴 INSERT를 테이블별로 묶은 것)도 구문 목록에 넣는다.
     *        기본은 빼고 뺀 수를 {@link ImpactReport#dataLoadExcluded()}로 알린다
     */
    public static ImpactReport analyze(GraphModel graph, String target, int maxDepth, boolean includeDataLoad) {
        String targetId = resolve(graph, target);
        if (targetId == null) {
            return new ImpactReport(String.valueOf(target), null, String.valueOf(target), null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), suggestions(graph, target));
        }
        FlowNavigator navigator = new FlowNavigator(graph, true, true);
        NodeKind kind = navigator.kindOf(targetId);
        boolean column = kind == NodeKind.DB_COLUMN;
        String tableId = column ? DbGraph.tableOfColumn(graph, targetId).orElse(null) : targetId;

        // 구문 → 접근 글자
        Map<String, Set<Character>> confirmed = new TreeMap<>();
        Map<String, Set<Character>> tableOnly = new TreeMap<>();
        if (column) {
            collect(graph, targetId, confirmed, true);
            if (tableId != null) {
                Set<String> withColumns = new HashSet<>();
                for (String c : navigator.columnsOf(tableId)) {
                    for (Edge e : graph.incoming(c, EdgeKind.READS, EdgeKind.WRITES)) {
                        withColumns.add(e.from());
                    }
                }
                Map<String, Set<Character>> onTable = new TreeMap<>();
                collect(graph, tableId, onTable, false);
                onTable.forEach((statement, letters) -> {
                    if (!withColumns.contains(statement)) {
                        tableOnly.put(statement, letters);
                    }
                });
            }
        } else {
            collect(graph, targetId, confirmed, false);
            for (String c : navigator.columnsOf(targetId)) {
                collect(graph, c, confirmed, false);
            }
        }
        // 데이터 적재 구문(스크립트의 리터럴 INSERT 묶음)은 애플리케이션의 구문이 아니다: 기본으로 빼고 뺀 수만 알린다.
        int dataLoadExcluded = 0;
        if (!includeDataLoad) {
            Set<String> loads = io.codeatlas.export.common.DataLoads.ids(graph);
            for (Map<String, Set<Character>> statements : List.of(confirmed, tableOnly)) {
                int before = statements.size();
                statements.keySet().removeIf(loads::contains);
                dataLoadExcluded += before - statements.size();
            }
        }

        Climb fromConfirmed = climb(navigator, confirmed.keySet(), maxDepth);
        Climb fromTableOnly = climb(navigator, tableOnly.keySet(), maxDepth);

        List<Item> statements = new ArrayList<>();
        confirmed.forEach((id, letters) -> statements.add(item(graph, id, Access.format(letters), false, fromConfirmed)));
        List<Item> tableLevel = new ArrayList<>();
        tableOnly.forEach((id, letters) -> tableLevel.add(item(graph, id, Access.format(letters), true, fromTableOnly)));

        List<Item> executors = new ArrayList<>();
        List<Item> callers = new ArrayList<>();
        List<Item> endpoints = new ArrayList<>();
        List<Item> screens = new ArrayList<>();
        List<Item> batch = new ArrayList<>();
        Set<String> ids = new TreeSet<>(fromConfirmed.nodes());
        ids.addAll(fromTableOnly.nodes());
        for (String id : ids) {
            if (confirmed.containsKey(id) || tableOnly.containsKey(id)) {
                continue;
            }
            boolean onlyTableLevel = !fromConfirmed.reached(id);
            Climb source = onlyTableLevel ? fromTableOnly : fromConfirmed;
            Item item = item(graph, id, null, onlyTableLevel, source);
            NodeKind k = item.kind();
            // 계층은 가장 짧은 경로로 정한다(적는 경로는 확정 경로가 있으면 그것이라 더 길 수 있다).
            if (source.shortest().get(id) == 1 && (k == NodeKind.JAVA_METHOD || k == NodeKind.DB_ROUTINE || k == NodeKind.BATCH_STEP)) {
                executors.add(item);
            } else if (k == NodeKind.HTTP_ENDPOINT) {
                endpoints.add(item);
            } else if (k == NodeKind.HTTP_REQUEST || k == NodeKind.JS_FUNCTION || k == NodeKind.JS_MODULE || k == NodeKind.JSP_PAGE) {
                screens.add(item);
            } else if (k == NodeKind.BATCH_STEP || k == NodeKind.BATCH_JOB) {
                batch.add(item);
            } else {
                callers.add(item);
            }
        }
        String label = column && tableId != null
                ? Ids.remainder(tableId) + "." + columnName(targetId, tableId) : Ids.remainder(targetId);
        return new ImpactReport(targetId, kind, label, column ? tableId : null, statements, tableLevel, executors, callers,
                endpoints, screens, batch, List.of(), DynamicStatement.find(graph)).withDataLoadExcluded(dataLoadExcluded);
    }

    /** 찾지 못한 대상과 이름이 비슷한 테이블·뷰·컬럼. 다른 종류의 노드는 영향도의 대상이 아니므로 넣지 않는다. */
    private static List<ImpactReport.Suggestion> suggestions(GraphModel graph, String target) {
        List<ImpactReport.Suggestion> suggestions = new ArrayList<>();
        for (Node node : NodeFinder.find(graph, target, NodeKind.DB_TABLE, NodeKind.DB_VIEW, NodeKind.DB_COLUMN)) {
            suggestions.add(new ImpactReport.Suggestion(node.id(), node.kind(), NodeLabels.label(graph, node.id()),
                    NodeLabels.location(graph, node.id())));
        }
        return suggestions;
    }

    private static String columnName(String columnId, String tableId) {
        String rest = Ids.remainder(columnId);
        String prefix = Ids.remainder(tableId) + ".";
        return rest.startsWith(prefix) ? rest.substring(prefix.length()) : rest;
    }

    /**
     * nodeId(테이블 또는 컬럼)로 들어오는 READS/WRITES의 출발 노드와 접근 글자를 모은다.
     *
     * @param columnLevel true면 컬럼 엣지의 READS도 R로 센다(컬럼이 대상일 때). false면 테이블 단위 규칙
     *        ({@link Access#tableLetters}: 컬럼으로 가는 READS는 구문만 넣고 R로 세지 않는다)을 쓴다
     */
    private static void collect(GraphModel graph, String nodeId, Map<String, Set<Character>> target, boolean columnLevel) {
        for (Edge e : graph.incoming(nodeId, EdgeKind.READS, EdgeKind.WRITES)) {
            target.computeIfAbsent(e.from(), k -> new HashSet<>())
                    .addAll(columnLevel ? Access.letters(graph, e, e.from()) : Access.tableLetters(graph, e, e.from()));
        }
    }

    /** 방문의 확실함: 확정 경로로 닿음. */
    private static final int CERTAIN = 0;
    /** 방문의 확실함: 상속 매퍼 메서드(리플렉션 팬아웃)로 올라와, 수신자가 맞는 호출자의 확인을 기다린다. */
    private static final int PENDING = 1;
    /** 방문의 확실함: 추론 걸음을 거쳤다. */
    private static final int INFERRED = 2;

    /**
     * 거슬러 올라가며 닿은 방문 하나. 방문은 보통 노드 하나지만, 트리거에서 올라온 테이블은 트리거마다 따로다
     * (그 트리거를 일으키는 구문만 이어지므로 같은 테이블이라도 위쪽이 다르다). 같은 자리라도 더 확실한 경로로 다시 닿으면 방문이 하나 더 생긴다.
     *
     * @param nodeId 닿은 노드
     * @param distance 구문에서의 걸음 수
     * @param next 직전 방문의 키(구문 쪽). 구문이면 null
     * @param rank {@link #CERTAIN}, {@link #PENDING}, {@link #INFERRED}
     * @param inference 확정이 아니면 그 근거: 이 방문에서 구문 쪽으로 가는 경로에서 처음 만나는 추론 엣지와 까닭
     * @param place 방문의 자리(노드와 도착 문맥). 같은 자리의 더 확실한 방문이 생기면 이 방문은 더 올라가지 않는다
     * @param forwarded 이 방문에서 구문 쪽으로 가는 경로가 forward를 거치는지
     * @param forwards 직전 방문(구문 쪽)으로 가는 걸음이 forward인지(이 노드가 넘기는 핸들러다)
     */
    private record Reached(String nodeId, int distance, String next, int rank, String inference, String place, boolean forwarded,
            boolean forwards) {
    }

    /**
     * 자리마다 닿은 방문의 확실함. forward를 거치지 않은 방문과 거친 방문을 따로 적는다: 같은 자리에 같거나 더 확실하게 닿았더라도
     * forward를 거쳐서 닿은 것이면 forward를 거치지 않는 방문이 위를 다시 올라가야 한다("forward로만 닿은" 항목을 가리려면 그렇지 않은
     * 경로가 하나라도 있는지 알아야 한다).
     */
    private static final class Ranks {
        private final Map<String, Integer> direct = new java.util.HashMap<>();
        private final Map<String, Integer> forwarded = new java.util.HashMap<>();

        void put(String place, int rank, boolean viaForward) {
            (viaForward ? forwarded : direct).put(place, rank);
        }

        /** 그 자리에 같거나 더 나은 방문(더 확실하거나 같고, forward를 더 거치지 않는)이 이미 있는지. */
        boolean covered(String place, int rank, boolean viaForward) {
            return atMost(direct.get(place), rank) || (viaForward && atMost(forwarded.get(place), rank));
        }

        /** 그 자리에 이 방문보다 나은 방문이 생겼는지(이 방문 자신은 빼고 본다). */
        boolean surpassed(String place, int rank, boolean viaForward) {
            return viaForward ? atMost(direct.get(place), rank) || atMost(forwarded.get(place), rank - 1)
                    : atMost(direct.get(place), rank - 1);
        }

        private static boolean atMost(Integer seen, int rank) {
            return seen != null && seen <= rank;
        }
    }

    /**
     * 거슬러 올라간 결과.
     *
     * @param visits 방문 키 → 방문
     * @param best 노드 → 가장 확실한(같으면 forward를 거치지 않은, 그것도 같으면 가장 먼저 곧 가장 짧게 닿은) 방문의 키
     * @param shortest 노드 → 구문에서의 가장 짧은 걸음 수(확실함과 무관)
     */
    private record Climb(Map<String, Reached> visits, Map<String, String> best, Map<String, Integer> shortest) {

        Set<String> nodes() {
            return best.keySet();
        }

        boolean reached(String nodeId) {
            return best.containsKey(nodeId);
        }

        Reached of(String nodeId) {
            String key = best.get(nodeId);
            return key == null ? null : visits.get(key);
        }
    }

    /**
     * 여러 구문에서 동시에 시작하는 너비 우선 탐색. 화면에서 그 화면을 그리는 컨트롤러 쪽(RENDERS)과, 핸들러에서 그 핸들러로
     * redirect하는 핸들러 쪽으로는 가지 않는다(redirect는 별개의 요청이다. forward는 같은 요청이라 올라간다).
     *
     * <p>방문마다 확실함을 싣는다: 추론 걸음({@link Step#inference()})을 거치면 추론, 상속 매퍼 메서드로 올라가면 확인 대기이고
     * 수신자가 맞는 호출자({@code CALLS})를 만나면 확정으로 돌아온다. 추론으로 먼저 닿은 자리에 확정 경로로 다시 닿으면 그 위를 다시 올라간다
     * ("추론 경로로만 닿은" 항목을 가리려면 확정 경로가 하나라도 있는지 알아야 한다).
     *
     * <p>방문마다 forward를 거쳤는지도 싣는다: forward({@link Step#forward()})로 올라간 넘기는 핸들러와 그 위는 forward 경유다.
     * forward를 거쳐 먼저 닿은 자리에 그렇지 않은 경로로 다시 닿으면 그 위를 다시 올라간다.
     */
    private static Climb climb(FlowNavigator navigator, Set<String> seeds, int maxDepth) {
        GraphModel graph = navigator.graph();
        Map<String, Reached> visits = new LinkedHashMap<>();
        Map<String, String> best = new LinkedHashMap<>();
        Map<String, Integer> shortest = new LinkedHashMap<>();
        Ranks ranks = new Ranks();
        Map<String, Step> arrivals = new LinkedHashMap<>();
        List<String> frontier = new ArrayList<>(seeds);
        frontier.sort(Comparator.naturalOrder());
        for (String seed : frontier) {
            visits.put(seed, new Reached(seed, 0, null, CERTAIN, null, seed, false, false));
            best.put(seed, seed);
            shortest.put(seed, 0);
            ranks.put(seed, CERTAIN, false);
        }
        for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String key : frontier) {
                Reached current = visits.get(key);
                // 같은 자리에 더 나은 방문(더 확실하거나, forward를 거치지 않는)이 생겼으면 그쪽이 위를 맡는다.
                if (ranks.surpassed(current.place(), current.rank(), current.forwarded())) {
                    continue;
                }
                Reached below = current.next() == null ? null : visits.get(current.next());
                for (Step step : navigator.callers(current.nodeId(), below == null ? null : below.nodeId(), arrivals.get(key))) {
                    if (step.via() == EdgeKind.RENDERS || step.redirect()) {
                        continue;
                    }
                    // 팬아웃 엣지로 올라간 걸음(문맥에 조건이 실린다)은 수신자가 맞는 호출자가 확인할 때까지 확정이 아니다.
                    boolean awaits = step.context() != null && step.context().origin() != null;
                    int rank;
                    String inference;
                    if (step.inferred()) {
                        rank = INFERRED;
                        inference = edgeText(graph, step.nodeId(), current.nodeId()) + step.inference();
                    } else if (current.rank() == INFERRED) {
                        rank = INFERRED;
                        inference = current.inference();
                    } else if (awaits) {
                        rank = PENDING;
                        inference = edgeText(graph, step.nodeId(), current.nodeId()) + FlowNavigator.INFERENCE_RECEIVER_UNKNOWN;
                    } else if (current.rank() == PENDING && step.via() != EdgeKind.CALLS) {
                        // 호출이 아닌 관계(스텝의 구성 요소 등)로는 수신자를 확인할 수 없다.
                        rank = INFERRED;
                        inference = current.inference();
                    } else {
                        rank = CERTAIN;
                        inference = null;
                    }
                    // 트리거에서 올라온 테이블은 트리거마다 따로 본다(위로 이어지는 구문이 트리거의 사건에 따라 다르다).
                    // 필요한 수신자 타입이 붙은 메서드도 그 타입마다 따로 본다(남는 호출자가 다르다).
                    // 핸들러가 여럿인 엔드포인트도 핸들러마다 따로 본다(그 핸들러의 모듈로 오는 요청만 위로 이어진다).
                    boolean perOrigin = step.via() == EdgeKind.TRIGGERS_ON
                            || (step.via() == EdgeKind.ROUTES_TO && navigator.severalHandlers(step.nodeId()));
                    String place = (perOrigin ? step.nodeId() + "|" + current.nodeId() : step.nodeId())
                            + navigator.arrivalKey(step.nodeId(), step);
                    boolean forwarded = current.forwarded() || step.forward();
                    if (ranks.covered(place, rank, forwarded)) {
                        continue;
                    }
                    // 다른 트리거를 거쳐 이미 닿은 구문은 다시 올라가지 않는다(그 위는 같다).
                    // 조건 없이 이미 닿은 메서드도 다시 올라가지 않는다(조건 없는 쪽의 호출자가 전부를 포함한다).
                    if (!perOrigin && ranks.covered(step.nodeId(), rank, forwarded)) {
                        continue;
                    }
                    String visitKey = place + "\u0001" + rank + (forwarded ? "\u0001forward" : "");
                    visits.put(visitKey, new Reached(step.nodeId(), depth, key, rank, inference, place, forwarded, step.forward()));
                    ranks.put(place, rank, forwarded);
                    shortest.putIfAbsent(step.nodeId(), depth);
                    Reached known = visits.get(best.get(step.nodeId()));
                    if (known == null || rank < known.rank() || (rank == known.rank() && known.forwarded() && !forwarded)) {
                        best.put(step.nodeId(), visitKey);
                    }
                    arrivals.put(visitKey, step);
                    next.add(visitKey);
                }
            }
            frontier = next;
        }
        return new Climb(visits, best, shortest);
    }

    /** {@code 호출하는 쪽 → 호출되는 쪽: } (추론 엣지의 양 끝). */
    private static String edgeText(GraphModel graph, String upperId, String lowerId) {
        return NodeLabels.tagged(graph, upperId) + " → " + NodeLabels.tagged(graph, lowerId) + ": ";
    }

    private static Item item(GraphModel graph, String id, String access, boolean tableLevelOnly, Climb climb) {
        List<String> path = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Reached r = climb.of(id);
        int distance = r == null ? 0 : r.distance();
        Reached current = r;
        if (current == null) {
            path.add(id);
            labels.add(NodeLabels.tagged(graph, id));
        }
        ImpactReport.Forward forward = null;
        boolean arrivedByForward = false;
        for (int guard = 0; current != null && guard <= distance; guard++) {
            path.add(current.nodeId());
            // forward로 넘어간 대상 핸들러에는 콜트리의 줄처럼 표식을 붙인다.
            labels.add((arrivedByForward ? FORWARD_MARK + " " : "") + NodeLabels.tagged(graph, current.nodeId()));
            Reached below = current.next() == null ? null : climb.visits().get(current.next());
            arrivedByForward = current.forwards() && below != null;
            if (arrivedByForward && forward == null) {
                forward = new ImpactReport.Forward(current.nodeId(), below.nodeId(), forwardTarget(graph, current.nodeId(), below.nodeId()));
            }
            current = below;
        }
        String tag = NodeLabels.kindTag(graph, id);
        // 다른 방언 파일에서 남긴 데이터 적재 묶음은 이름 뒤에 밝힌다(--include-data-load로 넣었을 때만 구문 목록에 든다).
        String borrowed = io.codeatlas.export.common.DataLoads.borrowedNote(graph.node(id).orElse(null));
        return new Item(id, NodeLabels.kindOf(graph, id), NodeLabels.label(graph, id) + (borrowed == null ? "" : " (" + borrowed + ")"),
                tag.isEmpty() ? "method" : tag,
                NodeLabels.location(graph, id), access, tableLevelOnly, distance, path, labels,
                r == null || r.rank() == CERTAIN ? null : r.inference(), forward);
    }

    /** 경로에서 forward로 넘어간 대상 핸들러의 이름 앞에 붙는 표식(콜트리의 {@code [forward]}와 같다). */
    static final String FORWARD_MARK = "[forward]";

    /** 넘기는 핸들러에서 넘어간 핸들러로 가는 forward 엣지의 경로({@code path}). 엣지가 여럿이면 사전순으로 앞선 것, 모르면 null. */
    private static String forwardTarget(GraphModel graph, String fromId, String toId) {
        String target = null;
        for (Edge e : graph.outgoing(fromId, EdgeKind.CALLS)) {
            String path = e.attrString("path");
            if (e.to().equals(toId) && FlowNavigator.CALL_FORWARD.equals(e.attrString("callType")) && path != null && !path.isBlank()
                    && (target == null || path.compareTo(target) < 0)) {
                target = path;
            }
        }
        return target;
    }

    /** 노드 ID, {@code 테이블.컬럼}, 테이블·뷰 이름 순으로 맞춰 본다. 찾지 못하면 null. */
    static String resolve(GraphModel graph, String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String text = target.strip();
        Node byId = graph.node(text).orElse(null);
        if (byId != null) {
            return DbGraph.isTableLike(byId) || byId.kind() == NodeKind.DB_COLUMN ? text : null;
        }
        List<String> candidates = new ArrayList<>();
        try {
            candidates.add(Ids.table(text));
            candidates.add(Ids.view(text));
            int dot = text.lastIndexOf('.');
            if (dot > 0 && dot < text.length() - 1) {
                candidates.add(Ids.column(text.substring(0, dot), text.substring(dot + 1)));
            }
        } catch (RuntimeException e) {
            // 이름으로 해석할 수 없는 문자열
        }
        // 컬럼 표기(테이블.컬럼)가 스키마.테이블 표기보다 먼저다.
        if (candidates.size() == 3 && graph.hasNode(candidates.get(2))) {
            return candidates.get(2);
        }
        for (String candidate : candidates) {
            if (graph.hasNode(candidate)) {
                return candidate;
            }
        }
        List<Node> nodes = new ArrayList<>();
        nodes.addAll(graph.nodes(NodeKind.DB_TABLE));
        nodes.addAll(graph.nodes(NodeKind.DB_VIEW));
        nodes.addAll(graph.nodes(NodeKind.DB_COLUMN));
        nodes.sort(Comparator.comparing(Node::id));
        for (Node node : nodes) {
            if (Ids.remainder(node.id()).equalsIgnoreCase(text)) {
                return node.id();
            }
        }
        return null;
    }
}
