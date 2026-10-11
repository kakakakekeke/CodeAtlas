package io.codeatlas.link;

import io.codeatlas.core.model.Diagnostic;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 매퍼 바인딩(F-012-R4~R6). 매퍼 구문과 같은 namespace의 인터페이스 메서드를 EXECUTES로 잇는다.
 *
 * <ul>
 * <li>구문(namespace, statementId)마다 qualifiedName이 namespace인 타입의 name이 statementId인 메서드 전부 → 구문 (RESOLVED).</li>
 * <li>그 타입이 메서드를 상속만 했으면(선언은 상위 인터페이스에) 상위 메서드 → 구문 (INFERRED, {@code namespace}, {@code inherited=true}, {@code declaredIn}).</li>
 * <li>매퍼 인터페이스의 추상 메서드에 구문이 없으면 WARN {@value #MAPPER_METHOD_WITHOUT_STATEMENT}.</li>
 * <li>{@code attrs.shortId=true}인 EXECUTES는 statementId가 유일하게 맞는 구문으로 INFERRED 엣지를 더한다.</li>
 * </ul>
 */
public final class MapperBindingLinker implements Linker {

    static final String MAPPER_METHOD_WITHOUT_STATEMENT = "MAPPER_METHOD_WITHOUT_STATEMENT";
    /** 물려받은 메서드로 이은 EXECUTES의 attrs: 구문의 namespace(구체 매퍼 타입의 바이너리 이름). */
    static final String NAMESPACE = "namespace";
    /** 물려받은 메서드로 이은 EXECUTES의 attrs: 메서드 선언이 namespace 타입이 아니라 상위 인터페이스에 있다. */
    static final String INHERITED = "inherited";
    private static final String STATEMENT_PREFIX = "mybatis:stmt:";

    @Override
    public String name() {
        return "link-mapper-binding";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void link(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        Map<String, Node> typesByName = new HashMap<>();
        for (Node type : graph.nodes(NodeKind.JAVA_TYPE)) {
            typesByName.putIfAbsent(type.qualifiedName(), type);
            typesByName.putIfAbsent(Ids.remainder(type.id()), type);
        }
        // namespace → 그 namespace의 statementId들
        Map<String, Set<String>> statementIds = new HashMap<>();
        // statementId → 구문 노드 ID들
        Map<String, List<String>> byStatementId = new HashMap<>();

        for (Node statement : graph.nodes(NodeKind.SQL_STATEMENT)) {
            if (!statement.id().startsWith(STATEMENT_PREFIX)) {
                continue;
            }
            String namespace = namespaceOf(statement);
            String statementId = statementIdOf(statement);
            if (statementId == null) {
                continue;
            }
            byStatementId.computeIfAbsent(statementId, k -> new ArrayList<>()).add(statement.id());
            if (namespace == null) {
                continue;
            }
            statementIds.computeIfAbsent(namespace, k -> new HashSet<>()).add(statementId);
            Node type = typesByName.get(namespace);
            if (type != null) {
                bind(graph, type, statementId, statement);
            }
        }
        reportMethodsWithoutStatement(graph, statementIds);
        resolveShortIds(graph, byStatementId);
        resolvePrefixes(graph);
        resolveArgumentLiterals(graph);
    }

    /** 접두로 찾은 구문이 이 수를 넘으면 잇지 않는다(접두가 너무 짧아 추측이 된다). */
    private static final int MAX_PREFIX_CANDIDATES = 5;

    /**
     * 구문 ID가 "리터럴 접두 + 동적"인 호출(F-012-R31): java 분석기가 남긴 미해결 EXECUTES의 {@code attrs.statementPrefix}로
     * 시작하는 구문마다 INFERRED EXECUTES({@code prefixMatch=true})를 더한다. 호출의 연산 종류({@code attrs.operation})와
     * 구문의 {@code statementType}이 맞는 구문만 후보다(F-012-R41). 원래의 미해결 엣지는 그래프에 남지만(엣지를 지우는 API가 없다)
     * 풀린 엣지의 {@code resolvedFrom}이 그 미해결 노드를 가리키고 위치가 같으므로 소비자가 숨길 수 있고,
     * 그 미해결 노드로 들어오는 엣지가 전부 풀렸으면 노드에 {@code resolvedBy=prefixMatch}를 남긴다(F-012-R42).
     */
    private static void resolvePrefixes(GraphModel graph) {
        List<Edge> prefixed = graph.edges(EdgeKind.EXECUTES).stream()
                .filter(e -> e.attrString("statementPrefix") != null && !e.attrBoolean("prefixMatch")).toList();
        if (prefixed.isEmpty()) {
            return;
        }
        List<Node> statements = graph.nodes(NodeKind.SQL_STATEMENT).stream()
                .filter(n -> n.id().startsWith(STATEMENT_PREFIX)).sorted(java.util.Comparator.comparing(Node::id)).toList();
        // 미해결 노드 ID → 들어오는 접두 엣지가 전부 풀렸는가
        Map<String, Boolean> allResolved = new HashMap<>();
        for (Edge edge : prefixed) {
            String prefix = STATEMENT_PREFIX + edge.attrString("statementPrefix");
            String operation = edge.attrString("operation");
            List<String> candidates = statements.stream().filter(n -> n.id().startsWith(prefix))
                    .filter(n -> operationFits(operation, n.attrString("statementType"))).map(Node::id).toList();
            boolean resolved = !candidates.isEmpty() && candidates.size() <= MAX_PREFIX_CANDIDATES;
            allResolved.merge(edge.to(), resolved, Boolean::logicalAnd);
            if (!resolved) {
                continue;
            }
            for (String statement : candidates) {
                Edge.Builder b = Edge.builder(edge.from(), statement, EdgeKind.EXECUTES).inferred().location(edge.location())
                        .attr("prefixMatch", true).attr("statementPrefix", edge.attrString("statementPrefix")).attr("resolvedFrom", edge.to());
                if (operation != null) {
                    b.attr("operation", operation);
                }
                graph.addEdge(b.build());
            }
        }
        allResolved.forEach((unresolved, resolved) -> {
            if (resolved && graph.incoming(unresolved).stream().allMatch(e -> e.attrString("statementPrefix") != null)) {
                graph.putAttrs(unresolved, Map.of("resolvedBy", "prefixMatch"));
            }
        });
    }

    /**
     * 호출의 연산({@code SqlSession}의 메서드 이름)이 그 종류의 구문을 실행할 수 있는가. 읽는 호출(select*, queryFor*, list*)은
     * {@code SELECT}·{@code CALL} 구문만, 쓰는 호출(insert, update, delete)은 {@code INSERT}·{@code UPDATE}·{@code DELETE}·{@code MERGE}·{@code CALL}
     * 구문만 실행한다(MyBatis는 insert·update·delete를 서로 바꿔 불러도 동작하므로 쓰는 것끼리는 가리지 않는다).
     * 연산이나 구문의 종류를 모르면 거르지 않는다.
     */
    static boolean operationFits(String operation, String statementType) {
        if (operation == null || statementType == null) {
            return true;
        }
        String op = operation.toLowerCase(java.util.Locale.ROOT);
        String type = statementType.toUpperCase(java.util.Locale.ROOT);
        boolean read = op.startsWith("select") || op.startsWith("query") || op.startsWith("list");
        boolean write = op.startsWith("insert") || op.startsWith("update") || op.startsWith("delete");
        if (read) {
            return type.equals("SELECT") || type.equals("CALL");
        }
        if (write) {
            return Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "CALL").contains(type);
        }
        return true;
    }

    /**
     * 구문 ID 문자열을 라이브러리 메서드에 넘긴 호출(F-012-R43): java 분석기가 메서드 attrs {@code statementIdLiterals}에 남긴
     * "구문 ID처럼 보이는 문자열 상수 인자" 가운데 그 ID의 매퍼 구문이 실제로 있는 것만 INFERRED EXECUTES({@code viaArgument=true})로 잇는다.
     * 위치는 병렬 목록 {@code statementIdLiteralLines}({@code 줄:열})의 자리다. 구문이 없는 문자열은 구문 ID가 아니었던 것이라 아무것도 남기지 않는다.
     */
    private static void resolveArgumentLiterals(GraphModel graph) {
        for (Node method : graph.nodes(NodeKind.JAVA_METHOD)) {
            List<String> literals = method.attrList("statementIdLiterals");
            if (literals.isEmpty()) {
                continue;
            }
            List<String> lines = method.attrList("statementIdLiteralLines");
            for (int i = 0; i < literals.size(); i++) {
                String literal = literals.get(i);
                if (literal == null || literal.isBlank()) {
                    continue;
                }
                String statement = STATEMENT_PREFIX + literal.trim();
                if (graph.node(statement).filter(n -> n.kind() == NodeKind.SQL_STATEMENT && n.declared()).isEmpty()
                        || !graph.edges(method.id(), EdgeKind.EXECUTES, statement).isEmpty()) {
                    continue;
                }
                graph.addEdge(Edge.builder(method.id(), statement, EdgeKind.EXECUTES).inferred()
                        .location(lines.size() == literals.size() ? ViewLinker.callSite(method.location(), lines.get(i)) : method.location())
                        .attr("viaArgument", true).build());
            }
        }
    }

    private static void bind(GraphModel graph, Node type, String statementId, Node statement) {
        List<Node> own = methodsNamed(graph, type.id(), statementId);
        for (Node method : own) {
            if (graph.edges(method.id(), EdgeKind.EXECUTES, statement.id()).isEmpty()) {
                graph.addEdge(Edge.builder(method.id(), statement.id(), EdgeKind.EXECUTES).location(method.location()).build());
            }
        }
        if (!own.isEmpty()) {
            return;
        }
        // 상위 인터페이스에서 물려받은 메서드: 하위 매퍼의 구문을 실행한다고 추론한다.
        // 상위 메서드 노드 하나에서 모든 하위 매퍼의 같은 id 구문으로 엣지가 퍼지므로(CrudDao<T>#findList → 매퍼마다 하나),
        // 소비자가 호출 엣지의 receiverType과 맞춰 한 구문만 따라갈 수 있게 구체 매퍼 타입(namespace)과 inherited를 남긴다
        for (String superId : GraphTraversal.reachable(graph, type.id(), GraphTraversal.Direction.OUT, 20,
                EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS)) {
            for (Node method : methodsNamed(graph, superId, statementId)) {
                if (graph.edges(method.id(), EdgeKind.EXECUTES, statement.id()).isEmpty()) {
                    graph.addEdge(Edge.builder(method.id(), statement.id(), EdgeKind.EXECUTES).inferred()
                            .location(method.location()).attr("declaredIn", superId)
                            .attr(NAMESPACE, Ids.remainder(type.id())).attr(INHERITED, true).build());
                }
            }
        }
    }

    private static List<Node> methodsNamed(GraphModel graph, String typeId, String name) {
        return graph.successors(typeId, EdgeKind.CONTAINS).stream()
                .filter(n -> n.kind() == NodeKind.JAVA_METHOD && n.name().equals(name)).toList();
    }

    private static void reportMethodsWithoutStatement(GraphModel graph, Map<String, Set<String>> statementIds) {
        Set<String> mapperNamespaces = new HashSet<>();
        for (Node mapper : graph.nodes(NodeKind.MYBATIS_MAPPER)) {
            String namespace = mapper.attrString("namespace");
            mapperNamespaces.add(namespace != null ? namespace : Ids.remainder(mapper.id()));
        }
        for (Node type : graph.nodes(NodeKind.JAVA_TYPE)) {
            boolean mapper = "mapper".equals(type.attrString("stereotype")) || mapperNamespaces.contains(type.qualifiedName());
            String typeKind = type.attrString("typeKind");
            if (!mapper || (typeKind != null && !typeKind.equals("interface"))) {
                continue;
            }
            Set<String> ids = statementIds.getOrDefault(type.qualifiedName(), Set.of());
            for (Node method : graph.successors(type.id(), EdgeKind.CONTAINS)) {
                if (method.kind() != NodeKind.JAVA_METHOD || !isAbstract(method) || ids.contains(method.name())) {
                    continue;
                }
                graph.report(Diagnostic.warn(MAPPER_METHOD_WITHOUT_STATEMENT,
                        "매퍼 메서드에 대응하는 SQL 구문이 없다: " + type.qualifiedName() + "#" + method.name(), method.location()));
            }
        }
    }

    private static boolean isAbstract(Node method) {
        if (method.attrBoolean("constructor")) {
            return false;
        }
        List<String> modifiers = method.attrList("modifiers");
        return !modifiers.contains("default") && !modifiers.contains("static") && !modifiers.contains("private");
    }

    private static void resolveShortIds(GraphModel graph, Map<String, List<String>> byStatementId) {
        for (Edge edge : graph.edges(EdgeKind.EXECUTES)) {
            if (!edge.attrBoolean("shortId") || graph.hasNode(edge.to()) || !edge.to().startsWith(STATEMENT_PREFIX)) {
                continue;
            }
            String shortId = edge.attrString("statementId");
            if (shortId == null) {
                String rest = edge.to().substring(STATEMENT_PREFIX.length());
                shortId = rest.substring(rest.lastIndexOf('.') + 1);
            }
            List<String> candidates = byStatementId.getOrDefault(shortId, List.of());
            if (candidates.size() != 1) {
                continue;
            }
            graph.addEdge(edge.withEndpoints(edge.from(), candidates.get(0)).toBuilder().inferred()
                    .attr("resolvedFrom", edge.to()).build());
        }
    }

    private static String namespaceOf(Node statement) {
        String namespace = statement.attrString("namespace");
        if (namespace != null && !namespace.isBlank()) {
            return namespace;
        }
        String rest = statement.id().substring(STATEMENT_PREFIX.length());
        int dot = rest.lastIndexOf('.');
        return dot > 0 ? rest.substring(0, dot) : null;
    }

    private static String statementIdOf(Node statement) {
        String statementId = statement.attrString("statementId");
        if (statementId != null && !statementId.isBlank()) {
            return statementId;
        }
        String rest = statement.id().substring(STATEMENT_PREFIX.length());
        String id = rest.substring(rest.lastIndexOf('.') + 1);
        return id.isEmpty() ? null : id;
    }
}
