package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.calltree.CallTreeNode.Marker;
import io.codeatlas.export.calltree.CallTreeOptions.Direction;
import io.codeatlas.export.calltree.FlowNavigator.Step;
import io.codeatlas.export.common.DataLoads;
import io.codeatlas.export.common.NodeLabels;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 콜트리 조회 API의 입구.
 *
 * <pre>{@code
 * CallTree tree = CallTrees.build(graph, Ids.httpEndpoint("GET", "/orders/{id}"), CallTreeOptions.callees());
 * System.out.print(CallTrees.toText(tree));
 * }</pre>
 */
public final class CallTrees {

    private CallTrees() {
    }

    /** startId에서 옵션의 방향으로 트리를 만든다. 그래프에 없는 ID를 줘도 한 줄짜리 트리가 나온다. */
    public static CallTree build(GraphModel graph, String startId, CallTreeOptions options) {
        return new Builder(graph, options == null ? CallTreeOptions.callees() : options).build(startId);
    }

    /** 들여쓴 텍스트 트리(소스 위치 포함). */
    public static String toText(CallTree tree) {
        return CallTreeRenderer.text(tree, true);
    }

    /** 들여쓴 텍스트 트리. locations가 false면 소스 위치를 뺀다. */
    public static String toText(CallTree tree, boolean locations) {
        return CallTreeRenderer.text(tree, locations);
    }

    /** JSON 트리. */
    public static String toJson(CallTree tree) {
        return CallTreeRenderer.json(tree);
    }

    /** Mermaid flowchart. 작은 트리에 쓴다. 같은 노드는 한 번만 그린다. */
    public static String toMermaid(CallTree tree) {
        return CallTreeRenderer.mermaid(tree);
    }

    /** 걸음이 오버로드 후보 묶음에 드는 것이면 그 호출 지점의 키({@code 줄:열}), 아니면 null. */
    private static String ambiguityKey(Step step) {
        if (!step.ambiguous() || step.impl()) {
            return null;
        }
        // 요청 하나가 후보 엔드포인트 여럿과 맞은 것(MATCHES의 ambiguous=true): 그 요청의 후보 전부가 한 묶음이다.
        if (step.via() == EdgeKind.MATCHES) {
            return "matches";
        }
        // 동적 데코레이터 이름을 가리지 못해 후보 레이아웃 여럿으로 이은 것(INCLUDES의 ambiguous=true): 같은 선언 지점의 후보가 한 묶음이다.
        if (step.via() == EdgeKind.INCLUDES) {
            return "includes" + (step.site() == null ? "" : ":" + step.site().startLine() + ":" + step.site().startColumn());
        }
        if (step.via() != EdgeKind.CALLS || step.site() == null || step.site().startLine() <= 0) {
            return null;
        }
        return step.site().startLine() + ":" + step.site().startColumn() + (step.detail() == null ? "" : ":" + step.detail());
    }

    /** index부터 같은 호출 지점의 후보가 몇 개 이어지는지. */
    private static int groupSize(List<Step> steps, int index, String key) {
        int size = 0;
        while (index + size < steps.size() && key.equals(ambiguityKey(steps.get(index + size)))) {
            size++;
        }
        return size;
    }

    /** 후보들의 공통 이름: 타입이 모두 같으면 {@code Type#name}, 아니면 이름만. */
    private static String commonName(List<Step> candidates) {
        String common = null;
        String name = null;
        for (Step step : candidates) {
            String label = NodeLabels.methodLabel(io.codeatlas.core.model.Ids.remainder(step.nodeId()), false);
            int paren = label.indexOf('(');
            String withoutParams = paren < 0 ? label : label.substring(0, paren);
            name = withoutParams.substring(withoutParams.indexOf('#') + 1);
            common = common == null || common.equals(withoutParams) ? withoutParams : "";
        }
        return common == null || common.isEmpty() ? String.valueOf(name) : common;
    }

    /** 위 방향에서 이 수 이상의 페이지가 include하는 JSP는 공통 화면으로 보고 그 위를 펼치지 않는다({@code [shared]}). */
    public static final int SHARED_PAGE_THRESHOLD = 5;

    private static final class Builder {
        private final GraphModel graph;
        private final CallTreeOptions options;
        private final FlowNavigator navigator;
        /** 끝까지 펼친 노드(문맥 포함 키). */
        private final Set<String> complete = new HashSet<>();
        /** 깊이 제한 등으로 일부만 펼친 노드 → 그때 남아 있던 깊이. */
        private final Map<String, Integer> partial = new HashMap<>();
        private int count;
        private boolean truncated;

        Builder(GraphModel graph, CallTreeOptions options) {
            this.graph = graph;
            this.options = options;
            this.navigator = new FlowNavigator(graph, options.expandImplementations(), options.narrowByInjection(),
                    options.includeDataLoad(), options.expandLayouts());
        }

        CallTree build(String startId) {
            CallTreeNode root = new CallTreeNode(startId, navigator.kindOf(startId), NodeLabels.label(graph, startId),
                    NodeLabels.kindTag(graph, startId), NodeLabels.location(graph, startId), null, null, null,
                    graph.hasNode(startId) ? Confidence.RESOLVED : Confidence.UNRESOLVED);
            count = 1;
            Set<String> path = new HashSet<>();
            path.add(startId);
            expand(root, 0, path, null, null);
            return new CallTree(root, options, count, truncated);
        }

        /**
         * 줄에 적을 위치. 소스에 정의가 있는 노드는 정의 위치, 정의가 없는 노드(미해결, 참조만 된 것)는 호출 지점이다.
         * 미해결 노드는 여러 곳에서 함께 쓰여 노드의 위치가 처음 등록한 곳 하나뿐이기 때문이다.
         */
        private String definedAt(String nodeId, String callSite) {
            Node node = graph.node(nodeId).orElse(null);
            String own = node == null ? null : NodeLabels.location(node.location());
            boolean defined = node != null && node.declared() && node.kind() != NodeKind.UNRESOLVED;
            if (defined && own != null) {
                return own;
            }
            return callSite != null ? callSite : own;
        }

        /**
         * 후보 엔드포인트 묶음 줄의 이름: 그 요청의 이름. 자기 페이지로 내는 폼({@code selfSubmit})은 요청 줄과 같은 문구가 되풀이되므로
         * {@value NodeLabels#SELF_SUBMIT_CANDIDATES}로 적는다.
         */
        private String matchGroupLabel(CallTreeNode request) {
            boolean selfSubmit = graph.node(request.id()).map(n -> n.kind() == NodeKind.HTTP_REQUEST && n.attrBoolean("selfSubmit")).orElse(false);
            return selfSubmit ? NodeLabels.SELF_SUBMIT_CANDIDATES : request.label();
        }

        /** 이 JSP가 공통 화면이면(레이아웃이거나 {@value #SHARED_PAGE_THRESHOLD}개 이상의 페이지가 포함한다) 그것을 포함하는 화면 수, 아니면 0. */
        private int sharedPages(String jspId) {
            int including = navigator.includingPages(jspId);
            return including >= SHARED_PAGE_THRESHOLD || navigator.layoutUsers(jspId) > 0 ? including : 0;
        }

        /**
         * node의 자식을 만든다.
         *
         * @param arrival node에 온 걸음(수신자 문맥 포함). 시작 노드면 null
         * @return 이 서브트리가 깊이 제한이나 노드 수 상한에 잘리지 않고 끝까지 펼쳐졌으면 true
         */
        private boolean expand(CallTreeNode node, int depth, Set<String> path, String parentId, Step arrival) {
            // 화면 이동은 기본으로 그 너머를 펼치지 않는다: 컨트롤러가 그리는 JSP(아래 방향), redirect·forward로 넘어가는 핸들러(양방향).
            if (!options.expandRenderedViews() && ((options.direction() == Direction.CALLEES && node.via() == EdgeKind.RENDERS)
                    || (arrival != null && (arrival.redirect() || arrival.forward())))) {
                return true;
            }
            // 레이아웃(데코레이터, tiles template과 조각)은 따로 켜야 펼친다: 모든 화면이 공통 헤더·메뉴의 요청을 끌고 온다.
            if (!options.expandLayouts() && options.direction() == Direction.CALLEES && arrival != null && arrival.layout()) {
                return true;
            }
            List<Step> steps = options.direction() == Direction.CALLEES
                    ? navigator.callees(node.id(), parentId, arrival)
                    : navigator.callers(node.id(), parentId, arrival);
            if (options.hideExternal()) {
                steps = steps.stream().filter(s -> !navigator.isExternal(s.nodeId())).toList();
            }
            if (options.hideAccessors() && options.direction() == Direction.CALLEES) {
                steps = steps.stream().filter(s -> s.via() != EdgeKind.CALLS || !navigator.isAccessor(s.nodeId())).toList();
            }
            // callers: 이 JSP를 레이아웃으로 쓰는 화면은 줄줄이 내지 않고 한 줄로 접는다.
            int layoutUsers = options.direction() == Direction.CALLERS && !options.expandLayouts() && node.kind() == NodeKind.JSP_PAGE
                    ? navigator.layoutUsers(node.id()) : 0;
            if (steps.isEmpty() && layoutUsers == 0) {
                return true;
            }
            int remaining = options.maxDepth() - depth;
            if (remaining <= 0) {
                node.mark(Marker.DEPTH_LIMIT);
                return false;
            }
            String key = node.id();
            if (navigator.contextSensitive(node.id())) {
                key += "|" + parentId + "|" + node.via();
            }
            // 수신자 문맥이 다르면 같은 메서드라도 아래가 다르다.
            key += navigator.arrivalKey(node.id(), arrival);
            if (options.collapseRepeated()) {
                if (complete.contains(key)) {
                    node.mark(Marker.SEE_ABOVE);
                    return true;
                }
                // 앞에서 깊이 제한에 잘린 채 펼친 노드는 지금 더 깊이 볼 수 있을 때만 다시 펼친다.
                Integer before = partial.get(key);
                if (before != null && before >= remaining) {
                    node.mark(Marker.SEE_ABOVE);
                    return false;
                }
            }
            boolean whole = true;
            CallTreeNode owner = node;
            String groupKey = null;
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (count >= options.maxNodes()) {
                    truncated = true;
                    whole = false;
                    break;
                }
                // 같은 호출 지점에서 오버로드 후보 여럿으로 간 호출은 [ambiguous n] 한 줄 아래에 묶는다.
                // 위 방향에는 묶음이 없다: 호출자·요청마다 지점이 달라 "한 지점의 후보들"이 아니다.
                String ambiguity = options.direction() == Direction.CALLEES ? ambiguityKey(step) : null;
                if (ambiguity == null || !ambiguity.equals(groupKey)) {
                    owner = node;
                    groupKey = null;
                    int size = ambiguity == null ? 0 : groupSize(steps, index, ambiguity);
                    if (size > 1) {
                        boolean matches = step.via() == EdgeKind.MATCHES;
                        boolean includes = step.via() == EdgeKind.INCLUDES;
                        owner = new CallTreeNode("ambiguous:" + node.id() + "@" + ambiguity,
                                matches ? NodeKind.HTTP_ENDPOINT : includes ? NodeKind.JSP_PAGE : NodeKind.JAVA_METHOD,
                                matches ? matchGroupLabel(node) : includes ? node.label() : commonName(steps.subList(index, index + size)),
                                "ambiguous " + size, null,
                                step.via(), NodeLabels.location(step.site()), null, Confidence.INFERRED);
                        node.add(owner);
                        count++;
                        groupKey = ambiguity;
                    }
                }
                String callSite = NodeLabels.location(step.site());
                // 리플렉션 호출, 요청 전 처리, redirect·forward는 괄호 표시가 아니라 표식으로 보인다.
                Marker callMarker = step.via() != EdgeKind.CALLS ? null
                        : FlowNavigator.CALL_REFLECTION.equals(step.detail()) ? Marker.REFLECT
                        : step.beforeCall() ? Marker.BEFORE : step.redirect() ? Marker.REDIRECT : step.forward() ? Marker.FORWARD : null;
                if (step.layout()) {
                    callMarker = Marker.LAYOUT;
                }
                String detail = callMarker == null ? step.detail() : null;
                NodeKind childKind = navigator.kindOf(step.nodeId());
                // 다른 방언 파일에서 보충한 구문(선택한 방언의 매퍼 파일에 없어 다른 방언 파일의 정의를 쓴 것)
                // 데이터 적재 묶음이면 선택한 방언에 그 테이블의 적재가 없어 남긴 다른 방언 파일의 것이다.
                Node childNode = childKind == NodeKind.SQL_STATEMENT ? graph.node(step.nodeId()).orElse(null) : null;
                String borrowed = childNode == null ? null : childNode.attrString("borrowedFrom");
                if (borrowed != null && !borrowed.isBlank()) {
                    String note = DataLoads.isDataLoad(childNode) ? DataLoads.borrowedNote(childNode) : "다른 방언 " + borrowed + "에서 보충";
                    detail = (detail == null || detail.isEmpty() ? "" : detail + ", ") + note;
                }
                // callers: 여러 화면에 들어가는 공통 JSP(헤더·메뉴·레이아웃)는 그 위(포함하는 화면 전부)를 펼치지 않는다.
                int sharedBy = options.direction() == Direction.CALLERS && !options.expandLayouts() && childKind == NodeKind.JSP_PAGE
                        ? sharedPages(step.nodeId()) : 0;
                if (sharedBy > 0) {
                    detail = (detail == null || detail.isEmpty() ? "" : detail + ", ") + "포함하는 화면 " + sharedBy + "개";
                }
                // 맞는 엔드포인트를 찾지 못한 action 없는 폼: 그 요청의 화면을 그리는 엔드포인트의 경로로 풀어 적는다.
                String childLabel = options.direction() == Direction.CALLEES && step.via() == EdgeKind.MATCHES
                        ? NodeLabels.unresolvedSelfSubmitLabel(graph, step.nodeId(), node.id()) : NodeLabels.label(graph, step.nodeId());
                CallTreeNode child = new CallTreeNode(step.nodeId(), childKind,
                        childLabel, NodeLabels.kindTag(graph, step.nodeId()),
                        definedAt(step.nodeId(), callSite), step.via(), callSite, detail,
                        step.confidence());
                if (callMarker != null) {
                    child.mark(callMarker);
                }
                if (sharedBy > 0) {
                    child.mark(Marker.SHARED);
                    owner.add(child);
                    count++;
                    continue;
                }
                if (step.impl()) {
                    child.mark(Marker.IMPL);
                }
                if (step.viaInterface()) {
                    child.mark(Marker.VIA_INTERFACE);
                }
                if (step.triggerUncertain()) {
                    child.mark(Marker.TRIGGER_UNCERTAIN);
                }
                if (step.receiverUncertain()) {
                    child.mark(Marker.RECEIVER_UNCERTAIN);
                }
                owner.add(child);
                count++;
                if (path.contains(step.nodeId())) {
                    child.mark(Marker.CYCLE);
                    continue;
                }
                path.add(step.nodeId());
                whole &= expand(child, depth + 1, path, node.id(), step);
                path.remove(step.nodeId());
            }
            if (layoutUsers > 0 && count < options.maxNodes()) {
                node.add(new CallTreeNode("layout-users:" + node.id(), NodeKind.JSP_PAGE, node.label() + "를 쓰는 화면 " + layoutUsers + "개",
                        "layout", null, EdgeKind.INCLUDES, null, null, Confidence.RESOLVED));
                count++;
            }
            if (whole) {
                complete.add(key);
            } else {
                partial.merge(key, remaining, Math::max);
            }
            return whole;
        }
    }
}
