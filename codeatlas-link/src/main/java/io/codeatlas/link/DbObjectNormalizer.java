package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Linker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DB 객체 정규화(F-012-R1~R3). 다른 링커보다 먼저 돈다.
 *
 * <p>SQL 분석은 테이블인지 뷰·시노님인지 모르고 항상 {@code db:table:X}로 참조한다. 이 링커는 정의가 없는 테이블 참조를
 * 같은 이름의 뷰나 시노님의 최종 대상으로 합치고, 스키마·패키지 시노님·대소문자만 다른 루틴 참조를 정의된 루틴으로 합친다.
 * 끝까지 정의를 찾지 못한 참조는 {@code declared=false}인 채로 둔다.
 */
public final class DbObjectNormalizer implements Linker {

    /** 시노님을 거쳐 옮긴 엣지에 남기는 속성. 값은 거친 시노님 이름. */
    static final String VIA_SYNONYM = "viaSynonym";
    /** 스키마인지 확실하지 않은 한정자를 떼고 합친 루틴 엣지에 남기는 속성. */
    static final String STRIPPED_QUALIFIER = "strippedQualifier";
    static final String SYNONYM_CYCLE = "SYNONYM_CYCLE";
    static final String UNDEFINED_ROUTINE = "UNDEFINED_ROUTINE";

    @Override
    public String name() {
        return "link-db-normalize";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void link(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        materializeDanglingReferences(graph);
        Catalog catalog = new Catalog(graph);
        normalizeTableStubs(graph, catalog);
        normalizeRoutineStubs(graph, catalog);
    }

    /**
     * 노드가 없는 {@code db:table:}, {@code db:routine:} 엣지 끝점을 참조 노드(declared=false)로 만든다.
     * 그래야 합칠 수 있고, 정의를 못 찾았을 때도 마무리 단계가 UNRESOLVED로 바꾸지 않는다.
     */
    private static void materializeDanglingReferences(GraphModel graph) {
        for (Edge edge : graph.edges()) {
            for (String id : List.of(edge.from(), edge.to())) {
                if (graph.hasNode(id)) {
                    continue;
                }
                NodeKind kind = Ids.kindOf(id).orElse(null);
                if (kind == NodeKind.DB_TABLE || kind == NodeKind.DB_ROUTINE) {
                    graph.reference(id, kind, Ids.remainder(id));
                }
            }
        }
    }

    // ---- 테이블 참조 ----

    private void normalizeTableStubs(GraphModel graph, Catalog catalog) {
        Set<String> cycleReported = new HashSet<>();
        for (Node stub : graph.nodes(NodeKind.DB_TABLE)) {
            if (stub.declared()) {
                continue;
            }
            String name = Ids.remainder(stub.id());
            Resolution target = catalog.resolve(name);
            if (target.cycle()) {
                if (cycleReported.add(name)) {
                    graph.report(Diagnostic.warn(SYNONYM_CYCLE, "시노님이 서로를 가리켜 대상을 정할 수 없다: " + name, stub.location()));
                }
                continue;
            }
            if (target.id() == null || target.id().equals(stub.id())) {
                continue;
            }
            NodeKind targetKind = Ids.kindOf(target.id()).orElse(null);
            boolean tableLike = targetKind == NodeKind.DB_TABLE || targetKind == NodeKind.DB_VIEW;
            if (!tableLike && !onlyAliasedTo(graph, stub.id())) {
                // 구문이 테이블로 읽고 쓰는 이름을 시퀀스·루틴·패키지로 합치지 않는다
                continue;
            }
            Map<String, Object> extra = target.viaSynonym() == null ? Map.of() : Map.of(VIA_SYNONYM, target.viaSynonym());
            if (target.viaSynonym() != null && tableLike) {
                moveColumns(graph, catalog, name, Ids.remainder(target.id()), extra);
            }
            merge(graph, stub.id(), target.id(), extra, null);
        }
    }

    private static boolean onlyAliasedTo(GraphModel graph, String stubId) {
        List<Edge> incoming = graph.incoming(stubId);
        return !incoming.isEmpty() && incoming.stream().allMatch(e -> e.kind() == EdgeKind.ALIAS_OF);
    }

    /** 시노님 이름으로 참조된 컬럼({@code db:column:SYN.COL})을 대상 테이블의 컬럼으로 옮긴다. */
    private static void moveColumns(GraphModel graph, Catalog catalog, String synonymName, String targetName, Map<String, Object> extra) {
        for (Node column : catalog.columnsOf(synonymName)) {
            if (column.declared()) {
                continue;
            }
            String targetColumn = Ids.column(quote(targetName), quote(column.name()));
            graph.reference(targetColumn, NodeKind.DB_COLUMN, column.name());
            merge(graph, column.id(), targetColumn, extra, null);
        }
    }

    /** 이미 정규화된 이름을 Ids에 다시 넘길 때 대소문자와 점이 바뀌지 않게 감싼다. */
    static String quote(String normalizedName) {
        return "\"" + normalizedName.replace("\"", "\"\"") + "\"";
    }

    // ---- 루틴 참조 ----

    private void normalizeRoutineStubs(GraphModel graph, Catalog catalog) {
        for (Node stub : graph.nodes(NodeKind.DB_ROUTINE)) {
            if (stub.declared()) {
                continue;
            }
            RoutineTarget target = catalog.resolveRoutine(Ids.remainder(stub.id()));
            if (target == null || target.id().equals(stub.id())) {
                SourceLocation where = graph.incoming(stub.id()).stream().map(Edge::location)
                        .filter(l -> l != null).findFirst().orElse(stub.location());
                graph.report(Diagnostic.info(UNDEFINED_ROUTINE,
                        "정의를 찾지 못한 루틴 참조: " + Ids.remainder(stub.id()) + " (DDL·프로시저 소스에 없음)", where));
                continue;
            }
            if (!graph.hasNode(target.id())) {
                graph.reference(target.id(), NodeKind.DB_ROUTINE, Ids.remainder(target.id()));
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            if (target.viaSynonym() != null) {
                extra.put(VIA_SYNONYM, target.viaSynonym());
            }
            if (target.strippedQualifier() != null) {
                extra.put(STRIPPED_QUALIFIER, target.strippedQualifier());
            }
            merge(graph, stub.id(), target.id(), extra, target.strippedQualifier() != null ? Confidence.INFERRED : null);
        }
    }

    // ---- 합치기 ----

    /**
     * oldId를 newId로 합친다. 엣지에 속성을 덧붙이거나 신뢰도를 낮춰야 하면, 바꾼 엣지를 먼저 넣은 뒤 replaceNode를 부른다
     * (replaceNode는 같은 (from, to, kind, location)의 엣지가 이미 있으면 기존 것을 남긴다).
     * 합쳐서 생긴 자기 참조는 버리되, JOINS는 실제 자기 조인이므로 남긴다.
     */
    static void merge(GraphModel graph, String oldId, String newId, Map<String, Object> extra, Confidence downgrade) {
        if (oldId.equals(newId)) {
            return;
        }
        boolean rewrite = !extra.isEmpty() || downgrade != null;
        List<Edge> touching = new ArrayList<>(graph.outgoing(oldId));
        for (Edge edge : graph.incoming(oldId)) {
            if (!edge.from().equals(oldId)) {
                touching.add(edge);
            }
        }
        for (Edge edge : touching) {
            String from = edge.from().equals(oldId) ? newId : edge.from();
            String to = edge.to().equals(oldId) ? newId : edge.to();
            boolean selfLoopByMerge = from.equals(to) && !edge.from().equals(edge.to());
            if (selfLoopByMerge ? edge.kind() != EdgeKind.JOINS : !rewrite) {
                continue;
            }
            Edge.Builder moved = edge.withEndpoints(from, to).toBuilder();
            extra.forEach((key, value) -> {
                Object existing = edge.attr(key);
                moved.attr(key, existing instanceof String s && !s.equals(value) ? s + "," + value : value);
            });
            if (downgrade != null && edge.confidence() == Confidence.RESOLVED) {
                moved.confidence(downgrade);
            }
            graph.addEdge(moved.build());
        }
        graph.replaceNode(oldId, newId);
    }

    // ---- 이름 해석 ----

    /** @param id 최종 대상 노드 ID, 못 찾으면 null. @param viaSynonym 시노님을 거쳤으면 처음 거친 시노님 이름 */
    record Resolution(String id, String viaSynonym, boolean cycle) {
        static final Resolution NONE = new Resolution(null, null, false);
    }

    record RoutineTarget(String id, String viaSynonym, String strippedQualifier) {
    }

    /** 링커 시작 시점의 정의된 DB 객체 목록. 이름은 ID에서 접두사를 뺀 정규화된 이름이다. */
    private static final class Catalog {
        private final GraphModel graph;
        private final Set<String> tables = new HashSet<>();
        private final Set<String> views = new HashSet<>();
        private final Set<String> sequences = new HashSet<>();
        private final Set<String> packages = new HashSet<>();
        private final Set<String> routines = new HashSet<>();
        /** 대문자로 바꾼 루틴 전체 이름 → 정의된 루틴 이름들. */
        private final Map<String, List<String>> routinesIgnoreCase = new HashMap<>();
        /** 패키지처럼 쓰인 한정자(정의된 패키지 이름과 "PKG.NAME" 루틴의 PKG), 대문자. */
        private final Set<String> packageQualifiers = new HashSet<>();
        private final Set<String> schemas = new HashSet<>();
        /** 시노님 이름 → ALIAS_OF 대상 ID. */
        private final Map<String, List<String>> synonyms = new HashMap<>();
        private final Map<String, List<Node>> columnsByOwner = new HashMap<>();

        Catalog(GraphModel graph) {
            this.graph = graph;
            for (Node node : graph.nodes()) {
                String schema = node.attrString("schema");
                if (schema != null && !schema.isBlank()) {
                    schemas.add(schema.toUpperCase(Locale.ROOT));
                }
                String name = Ids.remainder(node.id());
                switch (node.kind()) {
                    case DB_TABLE -> addIfDeclared(tables, node, name);
                    case DB_VIEW -> addIfDeclared(views, node, name);
                    case DB_SEQUENCE -> addIfDeclared(sequences, node, name);
                    case DB_PACKAGE -> {
                        if (node.declared()) {
                            packages.add(name);
                            packageQualifiers.add(name.toUpperCase(Locale.ROOT));
                        }
                    }
                    case DB_ROUTINE -> {
                        if (node.declared()) {
                            routines.add(name);
                            routinesIgnoreCase.computeIfAbsent(name.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(name);
                            int dot = name.lastIndexOf('.');
                            if (dot > 0) {
                                packageQualifiers.add(name.substring(0, dot).toUpperCase(Locale.ROOT));
                            }
                        }
                    }
                    case DB_SYNONYM -> {
                        List<String> targets = graph.outgoing(node.id(), EdgeKind.ALIAS_OF).stream().map(Edge::to).distinct().toList();
                        if (!targets.isEmpty()) {
                            synonyms.put(name, targets);
                        }
                    }
                    case DB_COLUMN -> {
                        String suffix = "." + node.name();
                        if (name.endsWith(suffix) && name.length() > suffix.length()) {
                            String owner = name.substring(0, name.length() - suffix.length());
                            columnsByOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(node);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        private static void addIfDeclared(Set<String> names, Node node, String name) {
            if (node.declared()) {
                names.add(name);
            }
        }

        List<Node> columnsOf(String owner) {
            return columnsByOwner.getOrDefault(owner, List.of());
        }

        /** 테이블 자리에 쓰인 이름이 실제로 가리키는 객체. 테이블 → 뷰 → 시노님 → 시퀀스 → 루틴 → 패키지 순으로 찾는다. */
        Resolution resolve(String name) {
            return resolve(name, new LinkedHashSet<>());
        }

        private Resolution resolve(String name, Set<String> visiting) {
            if (tables.contains(name)) {
                return new Resolution(Ids.table(quote(name)), null, false);
            }
            if (views.contains(name)) {
                return new Resolution(Ids.view(quote(name)), null, false);
            }
            List<String> aliasTargets = synonyms.get(name);
            if (aliasTargets != null && aliasTargets.size() == 1) {
                String targetId = aliasTargets.get(0);
                String targetName = Ids.remainder(targetId);
                Node targetNode = graph.node(targetId).orElse(null);
                if (targetNode != null && targetNode.declared() && targetNode.kind() != NodeKind.DB_SYNONYM) {
                    return new Resolution(targetId, name, false);
                }
                if (targetName.equals(name)) {
                    // 대상과 이름이 같은 시노님(PUBLIC SYNONYM T FOR OWNER.T): 참조는 이미 그 이름을 가리킨다
                    return new Resolution(targetId, null, false);
                }
                if (!visiting.add(name) || visiting.contains(targetName)) {
                    return new Resolution(null, null, true);
                }
                Resolution next = resolve(targetName, visiting);
                visiting.remove(name);
                if (next.cycle()) {
                    return next;
                }
                // 최종 대상의 정의가 없어도 시노님이 가리킨 이름으로 옮긴다
                return new Resolution(next.id() != null ? next.id() : targetId, name, false);
            }
            if (sequences.contains(name)) {
                return new Resolution(Ids.sequence(quote(name)), null, false);
            }
            if (routines.contains(name)) {
                return new Resolution("db:routine:" + name, null, false);
            }
            if (packages.contains(name)) {
                return new Resolution(Ids.dbPackage(quote(name)), null, false);
            }
            return Resolution.NONE;
        }

        /** 루틴 참조 이름(예: SHOP.PKG.PROC, SYN.PROC, pkg.proc)이 가리키는 루틴. 못 찾으면 null. */
        RoutineTarget resolveRoutine(String reference) {
            String[] parts = reference.split("\\.");
            if (parts.length == 0 || parts.length > 3) {
                return null;
            }
            // 1) 이름 그대로(대소문자만 다름)
            String same = definedRoutine(reference);
            if (same != null) {
                return new RoutineTarget(same, null, null);
            }
            // 2) 시노님: 루틴 시노님 또는 패키지 시노님
            RoutineTarget viaSynonym = parts.length == 1 ? routineSynonym(parts[0])
                    : parts.length == 2 ? packageSynonym(parts[0], parts[1]) : null;
            if (viaSynonym != null) {
                return viaSynonym;
            }
            // 3) 앞의 스키마를 뗀다
            if (parts.length == 3) {
                String rest = parts[1] + "." + parts[2];
                String defined = definedRoutine(rest);
                if (defined != null) {
                    return new RoutineTarget(defined, null, null);
                }
                return packageSynonym(parts[1], parts[2]);
            }
            if (parts.length == 2) {
                String qualifier = parts[0].toUpperCase(Locale.ROOT);
                if (packageQualifiers.contains(qualifier)) {
                    return null;   // 정의된 패키지에 그 루틴이 없다
                }
                String defined = definedRoutine(parts[1]);
                RoutineTarget target = defined != null ? new RoutineTarget(defined, null, null) : routineSynonym(parts[1]);
                if (target == null) {
                    return null;
                }
                boolean knownSchema = schemas.contains(qualifier);
                return knownSchema ? target : new RoutineTarget(target.id(), target.viaSynonym(), parts[0]);
            }
            return null;
        }

        private String definedRoutine(String fullName) {
            if (routines.contains(fullName)) {
                return "db:routine:" + fullName;
            }
            List<String> candidates = routinesIgnoreCase.getOrDefault(fullName.toUpperCase(Locale.ROOT), List.of());
            return candidates.size() == 1 ? "db:routine:" + candidates.get(0) : null;
        }

        private Resolution synonymTarget(String name) {
            String key = synonyms.containsKey(name) ? name : name.toUpperCase(Locale.ROOT);
            if (!synonyms.containsKey(key)) {
                return Resolution.NONE;
            }
            Resolution resolution = resolve(key);
            return resolution.viaSynonym() == null ? Resolution.NONE : resolution;
        }

        private RoutineTarget routineSynonym(String name) {
            Resolution r = synonymTarget(name);
            if (r.id() == null) {
                return null;
            }
            Node target = graph.node(r.id()).orElse(null);
            NodeKind kind = Ids.kindOf(r.id()).orElse(null);
            if (kind == NodeKind.DB_ROUTINE) {
                return new RoutineTarget(r.id(), r.viaSynonym(), null);
            }
            if (kind == NodeKind.DB_TABLE && (target == null || !target.declared())) {
                // 정의가 없는 대상: 시노님이 가리킨 이름의 루틴으로 본다
                return new RoutineTarget("db:routine:" + Ids.remainder(r.id()), r.viaSynonym(), null);
            }
            return null;
        }

        private RoutineTarget packageSynonym(String qualifier, String routineName) {
            Resolution r = synonymTarget(qualifier);
            if (r.id() == null) {
                return null;
            }
            Node target = graph.node(r.id()).orElse(null);
            NodeKind kind = Ids.kindOf(r.id()).orElse(null);
            boolean undeclared = kind == NodeKind.DB_TABLE && (target == null || !target.declared());
            if (kind != NodeKind.DB_PACKAGE && !undeclared) {
                return null;
            }
            String fullName = Ids.remainder(r.id()) + "." + routineName;
            String defined = definedRoutine(fullName);
            String id = defined != null ? defined : "db:routine:" + Ids.remainder(r.id()) + "." + routineName.toUpperCase(Locale.ROOT);
            return new RoutineTarget(id, r.viaSynonym(), null);
        }
    }
}
