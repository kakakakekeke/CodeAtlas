package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Linker;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 빈 참조 풀기(F-012-R12).
 *
 * <ul>
 * <li>대상이 SPRING_BEAN인 INJECTS·USES에, 그 빈을 정의한 타입으로 가는 같은 종류의 엣지를 더한다(기존 엣지는 남긴다, {@code attrs.resolvedFrom=<빈 ID>}).
 *     INJECTS의 출발점이 빈이어도 같은 방식으로 푼다({@code attrs.resolvedSource=<빈 ID>}).
 *     빈 이름은 전역이라 정의가 여럿일 수 있다: 참조하는 쪽과 같은 모듈의 정의를 우선하고 테스트 소스의 정의는 테스트 소스에서만 쓴다(F-012-R26).</li>
 * <li>이름에 프로퍼티 자리표시자가 든 빈 참조는 치환한 이름의 빈으로 먼저 잇는다(F-012-R23).</li>
 * <li>USES(스텝→타입, role=tasklet|reader|processor|writer)에, 그 타입의 역할 메서드({@code execute}, {@code read}, {@code process}, {@code write})로
 *     가는 USES(스텝→메서드)를 더한다. 콜트리가 잡에서 코드로 이어지게 하기 위해서다.</li>
 * </ul>
 */
public final class BeanReferenceLinker implements Linker {

    static final String RESOLVED_FROM = "resolvedFrom";
    /** INFO 진단: 이름에 프로퍼티 자리표시자가 든 빈 참조를 풀지 못했다(요약 1건). */
    static final String UNRESOLVED_BEAN_PLACEHOLDER = "UNRESOLVED_BEAN_PLACEHOLDER";
    private static final Map<String, String> ROLE_METHODS = Map.of(
            "tasklet", "execute", "reader", "read", "processor", "process", "writer", "write");

    @Override
    public String name() {
        return "link-bean-reference";
    }

    @Override
    public int order() {
        return 230;
    }

    @Override
    public void link(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        ModuleRoots modules = ModuleRoots.of(ctx.files());
        resolvePlaceholderBeanNames(ctx);
        for (EdgeKind kind : List.of(EdgeKind.INJECTS, EdgeKind.USES)) {
            for (Edge edge : graph.edges(kind)) {
                if (isBean(edge.to()) || (kind == EdgeKind.INJECTS && isBean(edge.from()))) {
                    resolveBeanEnds(graph, edge, modules);
                }
            }
        }
        for (Edge edge : graph.edges(EdgeKind.USES)) {
            String methodName = ROLE_METHODS.get(String.valueOf(edge.attrString("role")));
            if (methodName != null && graph.node(edge.to()).filter(n -> n.kind() == NodeKind.JAVA_TYPE).isPresent()) {
                linkRoleMethods(graph, edge, methodName);
            }
        }
    }

    private static boolean isBean(String id) {
        return Ids.kindOf(id).orElse(null) == NodeKind.SPRING_BEAN;
    }

    /**
     * 자리표시자가 든 빈 이름(F-012-R23). {@code <alias name="dataSource-${Globals.DbType}">}처럼 이름에 프로퍼티 자리표시자가 든 빈 참조는
     * 프로젝트 properties 값으로 치환한 이름의 빈이 정의돼 있으면 그 빈으로 가는 같은 종류의 엣지를 더한다({@code resolvedFrom=<원래 빈 ID>}).
     * 값을 못 찾았거나 치환한 이름의 빈이 없으면 아무 엣지도 더하지 않고 INFO 진단 한 건에 모아 적는다.
     */
    private static void resolvePlaceholderBeanNames(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        ProjectProperties properties = null;
        Map<String, Integer> unresolved = new java.util.TreeMap<>();
        for (EdgeKind kind : List.of(EdgeKind.INJECTS, EdgeKind.USES)) {
            for (Edge edge : graph.edges(kind)) {
                if (!isBean(edge.to()) || !ProjectProperties.hasPlaceholder(edge.to())) {
                    continue;
                }
                if (properties == null) {
                    properties = ProjectProperties.of(ctx);
                }
                String name = Ids.remainder(edge.to());
                ProjectProperties.Resolution resolution = properties.resolve(name, referrerFile(graph, edge));
                String beanId = resolution.complete() ? Ids.springBean(resolution.text()) : null;
                boolean defined = beanId != null && graph.node(beanId)
                        .filter(n -> n.kind() == NodeKind.SPRING_BEAN && n.attrBoolean("declared")).isPresent();
                if (!defined) {
                    unresolved.merge(name, 1, Integer::sum);
                    continue;
                }
                if (graph.edges(edge.from(), edge.kind(), beanId).stream().noneMatch(e -> java.util.Objects.equals(e.location(), edge.location()))) {
                    graph.addEdge(edge.withEndpoints(edge.from(), beanId).toBuilder().attr(RESOLVED_FROM, edge.to()).build());
                }
            }
        }
        if (!unresolved.isEmpty()) {
            StringBuilder names = new StringBuilder();
            unresolved.forEach((name, count) -> names.append(names.length() == 0 ? "" : ", ").append(name).append(" (").append(count).append("건)"));
            graph.report(Diagnostic.info(UNRESOLVED_BEAN_PLACEHOLDER,
                    "자리표시자가 든 빈 이름을 풀지 못해 그 참조를 타입으로 잇지 않음: " + names, null));
        }
    }

    /** 빈을 참조한 파일: 엣지의 위치, 없으면 출발 노드의 위치. */
    private static String referrerFile(GraphModel graph, Edge edge) {
        if (edge.location() != null && edge.location().file() != null) {
            return edge.location().file();
        }
        return graph.node(edge.from()).map(Node::location).map(l -> l.file()).orElse(null);
    }

    /** 엣지의 끝이 빈이면 그 빈을 정의한 타입으로 바꾼 엣지를 더한다. 출발점이 빈인 경우(XML에서 주입받는 빈의 클래스를 몰랐던 경우)도 푼다. */
    private static void resolveBeanEnds(GraphModel graph, Edge edge, ModuleRoots modules) {
        String referrer = referrerFile(graph, edge);
        List<String> sources = isBean(edge.from()) ? BeanSupport.definingTypes(graph, edge.from(), referrer, modules) : List.of();
        List<String> targets = isBean(edge.to()) ? BeanSupport.definingTypes(graph, edge.to(), referrer, modules) : List.of();
        if (sources.isEmpty() && targets.isEmpty()) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>(edge.attrs());
        if (!targets.isEmpty()) {
            attrs.remove("unresolvedBeanRef");
            attrs.put(RESOLVED_FROM, edge.to());
        }
        if (!sources.isEmpty()) {
            attrs.remove("unresolvedBeanSource");
            attrs.put("resolvedSource", edge.from());
        }
        // 빈 이름으로 정확히 찾은 것이므로, 원래 엣지가 미해결이었어도 새 엣지는 RESOLVED다
        Confidence confidence = edge.confidence() == Confidence.UNRESOLVED ? Confidence.RESOLVED : edge.confidence();
        for (String from : sources.isEmpty() ? List.of(edge.from()) : sources) {
            for (String to : targets.isEmpty() ? List.of(edge.to()) : targets) {
                if (from.equals(to) || !graph.edges(from, edge.kind(), to).isEmpty()) {
                    continue;
                }
                graph.addEdge(Edge.builder(from, to, edge.kind()).confidence(confidence).location(edge.location()).attrs(attrs).build());
            }
        }
    }

    /** 타입에 선언된 역할 메서드, 없으면 가장 가까운 상위 클래스의 것으로 잇는다. */
    private static void linkRoleMethods(GraphModel graph, Edge uses, String methodName) {
        Set<String> visited = new HashSet<>();
        String typeId = uses.to();
        while (typeId != null && visited.add(typeId)) {
            List<Node> methods = graph.successors(typeId, EdgeKind.CONTAINS).stream()
                    .filter(n -> n.kind() == NodeKind.JAVA_METHOD && n.name().equals(methodName)).toList();
            if (!methods.isEmpty()) {
                for (Node method : methods) {
                    if (graph.edges(uses.from(), EdgeKind.USES, method.id()).isEmpty()) {
                        graph.addEdge(uses.withEndpoints(uses.from(), method.id()));
                    }
                }
                return;
            }
            typeId = graph.successors(typeId, EdgeKind.EXTENDS).stream()
                    .filter(n -> n.kind() == NodeKind.JAVA_TYPE).map(Node::id).findFirst().orElse(null);
        }
    }
}
