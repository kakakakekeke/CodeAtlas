package io.codeatlas.link;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.GraphTraversal;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Linker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 주입 후보(F-012-R12, R27). 인터페이스 타입으로 주입받는 INJECTS에, 빈으로 정의된 구현 타입이 하나로 정해지면
 * INJECTS(주입받는 타입→구현 타입, INFERRED, {@code attrs.resolvedFrom=<인터페이스 ID>})를 더한다.
 *
 * <p>후보를 정하는 길은 둘이고 결과 표현은 같다(구현 타입으로 가는 INFERRED 엣지).
 * <ul>
 * <li>java 분석기가 이미 후보를 적어 둔 주입({@code attrs.candidates}): 후보가 정확히 하나면 그 타입으로 잇는다. 여럿이면 잇지 않는다
 * (후보 판단은 java 한 곳에서 하고 링커가 다시 고르지 않는다).</li>
 * <li>후보가 없는 주입(XML 주입 등): 구현이 여럿이면 qualifier나 필드명이 빈 이름과 맞는 하나만 고르고, 못 고르면 아무것도 더하지 않는다.</li>
 * </ul>
 */
public final class InjectionCandidateLinker implements Linker {

    private static final List<String> NAME_KEYS = List.of("field", "fieldName", "name", "parameter", "parameterName", "property");
    /** 후보 엣지로 옮기는 원래 엣지의 attrs. */
    private static final List<String> COPIED_KEYS = List.of("via", "field", "qualifier");

    @Override
    public String name() {
        return "link-injection-candidate";
    }

    @Override
    public int order() {
        return 240;
    }

    @Override
    public void link(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        Map<String, Set<String>> beanNames = beanNamesByType(graph);
        Map<String, List<Node>> candidatesByInterface = new HashMap<>();
        for (Edge edge : graph.edges(EdgeKind.INJECTS)) {
            Node target = graph.node(edge.to()).orElse(null);
            if (target == null || target.kind() != NodeKind.JAVA_TYPE) {
                continue;
            }
            if (edge.attrs().containsKey("candidates")) {
                linkAnalyzerCandidate(graph, edge, target);
                continue;
            }
            if (!"interface".equals(target.attrString("typeKind"))) {
                continue;
            }
            List<Node> candidates = candidatesByInterface.computeIfAbsent(target.id(), id -> implementations(graph, id, beanNames));
            Map<String, Object> basis = new LinkedHashMap<>();
            Node chosen = choose(edge, candidates, beanNames, basis);
            if (chosen == null || chosen.id().equals(edge.from()) || !graph.edges(edge.from(), EdgeKind.INJECTS, chosen.id()).isEmpty()) {
                continue;
            }
            graph.addEdge(Edge.builder(edge.from(), chosen.id(), EdgeKind.INJECTS).inferred().location(edge.location())
                    .attrs(edge.attrs()).attr(BeanReferenceLinker.RESOLVED_FROM, target.id()).attrs(basis).build());
        }
    }

    /**
     * java가 좁혀 둔 후보({@code attrs.candidates}, JAVA_TYPE 노드 ID 목록)가 정확히 하나면 그 타입으로 INFERRED INJECTS를 더한다.
     * 원래 엣지의 via·field·qualifier를 옮기고 {@code resolvedFrom}에 선언된 대상 타입을 남긴다.
     */
    private static void linkAnalyzerCandidate(GraphModel graph, Edge edge, Node target) {
        List<String> candidates = edge.attrList("candidates");
        if (candidates.size() != 1) {
            return;
        }
        Node chosen = graph.node(candidates.get(0)).orElse(null);
        if (chosen == null || chosen.kind() != NodeKind.JAVA_TYPE || chosen.id().equals(target.id()) || chosen.id().equals(edge.from())
                || !graph.edges(edge.from(), EdgeKind.INJECTS, chosen.id()).isEmpty()) {
            return;
        }
        Edge.Builder added = Edge.builder(edge.from(), chosen.id(), EdgeKind.INJECTS).inferred().location(edge.location())
                .attr(BeanReferenceLinker.RESOLVED_FROM, target.id());
        for (String key : COPIED_KEYS) {
            Object value = edge.attr(key);
            if (value != null) {
                added.attr(key, value);
            }
        }
        graph.addEdge(added.build());
    }

    /**
     * 빈으로 정의된 타입 → 그 빈 이름들(F-012-R27). DEFINES_BEAN·빈의 class로 이어진 타입과, java가 빈 이름({@code attrs.beanName})을 적어 둔 타입이다.
     * {@code attrs.stereotype}은 보지 않는다: 값이 타입의 역할({@code filter}, {@code interceptor})일 수 있어 빈인지 알려 주지 않는다.
     */
    private static Map<String, Set<String>> beanNamesByType(GraphModel graph) {
        Map<String, Set<String>> names = new HashMap<>();
        for (Node bean : graph.nodes(NodeKind.SPRING_BEAN)) {
            for (String typeId : BeanSupport.definingTypes(graph, bean.id())) {
                names.computeIfAbsent(typeId, k -> new LinkedHashSet<>()).add(Ids.remainder(bean.id()));
            }
        }
        for (Node type : graph.nodes(NodeKind.JAVA_TYPE)) {
            String beanName = type.attrString("beanName");
            if (beanName != null && !beanName.isBlank()) {
                names.computeIfAbsent(type.id(), k -> new LinkedHashSet<>()).add(beanName);
            }
        }
        return names;
    }

    /** 인터페이스를 직접·간접으로 구현하고 빈으로 정의된 구체 타입. */
    private static List<Node> implementations(GraphModel graph, String interfaceId, Map<String, Set<String>> beanNames) {
        List<Node> result = new ArrayList<>();
        for (String id : GraphTraversal.reachable(graph, interfaceId, GraphTraversal.Direction.IN, 50,
                EdgeKind.IMPLEMENTS, EdgeKind.EXTENDS)) {
            Node type = graph.node(id).orElse(null);
            if (type == null || type.kind() != NodeKind.JAVA_TYPE || !beanNames.containsKey(id)) {
                continue;
            }
            String typeKind = type.attrString("typeKind");
            if ("interface".equals(typeKind) || "annotation".equals(typeKind) || type.attrList("modifiers").contains("abstract")) {
                continue;
            }
            result.add(type);
        }
        return result;
    }

    private static Node choose(Edge edge, List<Node> candidates, Map<String, Set<String>> beanNames, Map<String, Object> basis) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        String qualifier = edge.attrString("qualifier");
        if (qualifier != null && !qualifier.isBlank()) {
            basis.put("matchedBy", "qualifier");
            return named(candidates, beanNames, qualifier);   // qualifier가 있으면 이름으로 넘어가지 않는다
        }
        for (String key : NAME_KEYS) {
            String name = edge.attrString(key);
            if (name != null && !name.isBlank()) {
                basis.put("matchedBy", "name");
                return named(candidates, beanNames, name);
            }
        }
        return null;
    }

    private static Node named(List<Node> candidates, Map<String, Set<String>> beanNames, String beanName) {
        List<Node> matched = candidates.stream().filter(c -> beanNames.getOrDefault(c.id(), Set.of()).contains(beanName)).toList();
        return matched.size() == 1 ? matched.get(0) : null;
    }
}
