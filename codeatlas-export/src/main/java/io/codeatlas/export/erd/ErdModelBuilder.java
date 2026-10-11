package io.codeatlas.export.erd;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.common.Texts;
import io.codeatlas.export.erd.ErdModel.Cardinality;
import io.codeatlas.export.erd.ErdModel.Column;
import io.codeatlas.export.erd.ErdModel.Entity;
import io.codeatlas.export.erd.ErdModel.EntityKind;
import io.codeatlas.export.erd.ErdModel.Relation;
import io.codeatlas.export.erd.ErdOptions.ColumnLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 그래프에서 {@link ErdModel}을 만든다. 그래프 계약의 DB_TABLE, DB_VIEW, DB_COLUMN, CONTAINS, REFERENCES와
 * 컬럼으로 가는 READS/WRITES만 읽는다. 속성이 빠져 있어도 예외를 던지지 않는다.
 */
public final class ErdModelBuilder {

    private final GraphModel graph;
    private final ErdOptions options;

    /** 후보 엔티티 노드. ID → 노드. */
    private final Map<String, Node> candidates = new LinkedHashMap<>();
    /** 정의는 없고(CONTAINS 없음) SQL에서 참조만 된 컬럼. 소유 엔티티 ID → 컬럼 노드. */
    private final Map<String, List<Node>> orphanColumns = new HashMap<>();
    /** 시작 테이블 이름 중 후보에서 찾지 못한 것(준 순서). */
    private final Set<String> unresolvedNames = new LinkedHashSet<>();
    /** unresolvedNames 가운데 뷰를 넣지 않아서 빠진 것. */
    private final Set<String> excludedViewNames = new LinkedHashSet<>();
    /** 후보에서 뺀 시스템 카탈로그({@code systemCatalog=true})의 이름(이름순). */
    private final Set<String> systemCatalogNames = new java.util.TreeSet<>();

    private ErdModelBuilder(GraphModel graph, ErdOptions options) {
        this.graph = graph;
        this.options = options;
    }

    public static ErdModel build(GraphModel graph, ErdOptions options) {
        return new ErdModelBuilder(graph, options == null ? ErdOptions.defaults() : options).build();
    }

    /** 가공 전 관계(엣지 하나). */
    private record RawRelation(Edge edge, String childId, String parentId, List<String> columns, List<String> refColumns,
            boolean inferred, int evidence, boolean directionKnown) {
    }

    private ErdModel build() {
        collectCandidates();
        List<RawRelation> raw = collectRelations();
        Set<String> scope = scope(raw);
        collectOrphanColumns(scope);

        // 1) 전체 컬럼으로 엔티티를 만든다(카디널리티 계산에 전체 컬럼이 필요하다).
        Map<String, List<Column>> fullColumns = new HashMap<>();
        for (String id : scope) {
            fullColumns.put(id, columnsOf(candidates.get(id), raw));
        }

        // 2) 범위 안의 관계.
        List<Relation> relations = new ArrayList<>();
        for (RawRelation r : raw) {
            if (!scope.contains(r.childId()) || !scope.contains(r.parentId())) {
                continue;
            }
            Cardinality[] sides = cardinality(r, fullColumns.get(r.childId()), uniqueKeys(candidates.get(r.childId())));
            relations.add(new Relation(candidates.get(r.childId()).name(), candidates.get(r.parentId()).name(), r.childId(),
                    r.parentId(), r.columns(), r.refColumns(), Texts.isBlank(r.edge().attrString("constraint")) ? null
                            : r.edge().attrString("constraint"),
                    r.inferred(), r.evidence(), r.directionKnown(), sides[0], sides[1],
                    r.directionKnown() ? null : ErdLabels.directionNote(r.edge()),
                    r.edge().attrBoolean("fromUpgrade"),
                    r.inferred() && r.directionKnown() && !Texts.isBlank(r.edge().attrString("directionFrom"))
                            ? r.edge().attrString("directionFrom").strip() : null));
        }
        relations.sort(Comparator.comparing(Relation::child).thenComparing(Relation::parent)
                .thenComparing(r -> String.join(",", r.columns())).thenComparing(r -> String.join(",", r.refColumns()))
                .thenComparing(r -> Texts.nullToEmpty(r.constraint())).thenComparing(Relation::inferred)
                .thenComparing(Relation::childId).thenComparing(Relation::parentId));

        // 3) 컬럼 표시 수준 적용.
        List<Entity> entities = new ArrayList<>();
        for (String id : scope) {
            Node node = candidates.get(id);
            List<Column> all = fullColumns.get(id);
            List<Column> shown = switch (options.columnLevel()) {
                case ALL -> all;
                case NONE -> List.of();
                case KEYS -> keyColumns(id, all, relations);
            };
            entities.add(new Entity(id, node.name(), node.kind() == NodeKind.DB_VIEW ? EntityKind.VIEW : EntityKind.TABLE,
                    node.declared(), node.attrString("schema"), node.attrString("comment"), shown, all.size()));
        }
        entities.sort(Comparator.comparing(Entity::name).thenComparing(Entity::id));
        return new ErdModel(entities, relations, List.copyOf(unresolvedNames), List.copyOf(excludedViewNames), List.copyOf(systemCatalogNames));
    }

    private void collectCandidates() {
        List<Node> nodes = new ArrayList<>(graph.nodes(NodeKind.DB_TABLE));
        if (options.includeViews()) {
            nodes.addAll(graph.nodes(NodeKind.DB_VIEW));
        }
        nodes.sort(Comparator.comparing(Node::id));
        for (Node node : nodes) {
            // DB가 제공하는 시스템 카탈로그(데이터 사전)는 프로젝트 스키마가 아니다: 엔티티로도 관계의 끝으로도 넣지 않고 이름만 모은다.
            if (node.attrBoolean("systemCatalog")) {
                systemCatalogNames.add(node.name());
                continue;
            }
            candidates.put(node.id(), node);
        }
    }

    private List<RawRelation> collectRelations() {
        List<Edge> edges = new ArrayList<>(graph.edges(EdgeKind.REFERENCES));
        edges.sort(Comparator.comparing(Edge::from).thenComparing(Edge::to)
                .thenComparing(e -> String.join(",", e.attrList("columns")))
                .thenComparing(e -> String.join(",", e.attrList("refColumns")))
                .thenComparing(e -> Texts.nullToEmpty(e.attrString("constraint")))
                .thenComparing(e -> e.location() == null ? "" : e.location().toString()));
        List<RawRelation> ddl = new ArrayList<>();
        List<RawRelation> inferred = new ArrayList<>();
        for (Edge e : edges) {
            if (!candidates.containsKey(e.from()) || !candidates.containsKey(e.to())) {
                continue;
            }
            boolean isInferred = isInferred(e);
            int evidence = e.attrInt("evidenceCount", 0);
            boolean directionKnown = !(e.attr("directionKnown") instanceof Boolean b) || b;
            RawRelation r = new RawRelation(e, e.from(), e.to(), e.attrList("columns"), e.attrList("refColumns"), isInferred,
                    evidence, !isInferred || directionKnown);
            if (!isInferred) {
                ddl.add(r);
            } else if (options.includeInferred() && (e.attrBoolean("fromUpgrade") || Math.max(1, evidence) >= options.minEvidence())) {
                // 현재 정의에 없는 upgrade 외래 키는 근거 수로 거르지 않는다(추론한 것이 아니라 스크립트에 적힌 것이다).
                inferred.add(r);
            }
        }
        // 같은 테이블 쌍·같은 컬럼의 관계는 한 번만. DDL이 추론보다 먼저다.
        List<RawRelation> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RawRelation r : ddl) {
            String key = key(r.childId(), r.parentId(), r.columns(), r.refColumns()) + "|" + r.edge().attrString("constraint");
            if (seen.add(key)) {
                result.add(r);
            }
        }
        Set<String> pairs = new HashSet<>();
        for (RawRelation r : result) {
            pairs.add(key(r.childId(), r.parentId(), r.columns(), r.refColumns()));
            pairs.add(key(r.parentId(), r.childId(), r.refColumns(), r.columns()));
        }
        for (RawRelation r : inferred) {
            String key = key(r.childId(), r.parentId(), r.columns(), r.refColumns());
            if (pairs.contains(key)) {
                continue;
            }
            pairs.add(key);
            pairs.add(key(r.parentId(), r.childId(), r.refColumns(), r.columns()));
            result.add(r);
        }
        return result;
    }

    /**
     * 추론 관계(점선)인가: {@code source=INFERRED}이거나, {@code source}가 없고 엣지 신뢰도가 INFERRED이거나, upgrade 스크립트에서 온
     * 외래 키({@code fromUpgrade=true})인데 엣지 신뢰도가 INFERRED인 것(현재 정의에 없는 외래 키).
     */
    static boolean isInferred(Edge e) {
        String source = e.attrString("source");
        return "INFERRED".equalsIgnoreCase(source)
                || (e.confidence() == Confidence.INFERRED && (source == null || e.attrBoolean("fromUpgrade")));
    }

    private static String key(String a, String b, List<String> columnsA, List<String> columnsB) {
        return a + "|" + b + "|" + String.join(",", columnsA) + "|" + String.join(",", columnsB);
    }

    /** 범위에 드는 엔티티 ID(ID 순). */
    private Set<String> scope(List<RawRelation> relations) {
        if (options.wholeSchema()) {
            return new LinkedHashSet<>(candidates.keySet());
        }
        Set<String> seeds = new LinkedHashSet<>();
        Map<String, Node> views = null;
        for (String name : options.tables()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            List<String> resolved = resolve(name, candidates);
            seeds.addAll(resolved);
            if (resolved.isEmpty()) {
                unresolvedNames.add(name.strip());
                if (!options.includeViews()) {
                    if (views == null) {
                        views = new LinkedHashMap<>();
                        for (Node view : graph.nodes(NodeKind.DB_VIEW)) {
                            views.put(view.id(), view);
                        }
                    }
                    if (!resolve(name, views).isEmpty()) {
                        excludedViewNames.add(name.strip());
                    }
                }
            }
        }
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (RawRelation r : relations) {
            neighbors.computeIfAbsent(r.childId(), k -> new HashSet<>()).add(r.parentId());
            neighbors.computeIfAbsent(r.parentId(), k -> new HashSet<>()).add(r.childId());
        }
        Set<String> reached = new HashSet<>(seeds);
        Set<String> frontier = seeds;
        for (int depth = 0; depth < options.neighborDepth() && !frontier.isEmpty(); depth++) {
            Set<String> next = new HashSet<>();
            for (String id : frontier) {
                for (String other : neighbors.getOrDefault(id, Set.of())) {
                    if (reached.add(other)) {
                        next.add(other);
                    }
                }
            }
            frontier = next;
        }
        // 이름 패턴에 맞는 테이블은 이웃으로 넓히지 않고 그대로 더한다.
        List<Pattern> patterns = options.patterns().stream().map(Texts::glob).toList();
        for (Node node : candidates.values()) {
            if (patterns.stream().anyMatch(p -> p.matcher(node.name()).matches())) {
                reached.add(node.id());
            }
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String id : candidates.keySet()) {
            if (reached.contains(id)) {
                ordered.add(id);
            }
        }
        return ordered;
    }

    /** 사용자가 준 테이블 이름 또는 ID에 맞는 후보 ID. */
    static List<String> resolve(String nameOrId, Map<String, Node> candidates) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return List.of();
        }
        String text = nameOrId.strip();
        if (candidates.containsKey(text)) {
            return List.of(text);
        }
        List<String> found = new ArrayList<>();
        String normalized;
        try {
            normalized = Ids.remainder(Ids.table(text));
        } catch (RuntimeException e) {
            normalized = text;
        }
        for (Node node : candidates.values()) {
            if (node.name().equals(normalized) || node.name().equals(text)) {
                found.add(node.id());
            }
        }
        if (found.isEmpty()) {
            for (Node node : candidates.values()) {
                if (node.name().equalsIgnoreCase(text)) {
                    found.add(node.id());
                }
            }
        }
        return found;
    }

    /** CONTAINS가 없는 컬럼 노드를 ID로 소유 엔티티에 붙인다. READS/WRITES로 참조된 것만. */
    private void collectOrphanColumns(Set<String> scope) {
        Map<String, String> idByName = new HashMap<>();
        for (String id : scope) {
            Node node = candidates.get(id);
            if (!node.declared()) {
                idByName.putIfAbsent(Ids.remainder(id), id);
            }
        }
        if (idByName.isEmpty()) {
            return;
        }
        for (Node column : graph.nodes(NodeKind.DB_COLUMN)) {
            String rest = Ids.remainder(column.id());
            for (int dot = rest.indexOf('.'); dot > 0; dot = rest.indexOf('.', dot + 1)) {
                String owner = idByName.get(rest.substring(0, dot));
                if (owner != null) {
                    if (graph.incoming(column.id(), EdgeKind.CONTAINS).isEmpty()
                            && !graph.incoming(column.id(), EdgeKind.READS, EdgeKind.WRITES).isEmpty()) {
                        orphanColumns.computeIfAbsent(owner, k -> new ArrayList<>()).add(column);
                    }
                    break;
                }
            }
        }
    }

    private List<Column> columnsOf(Node entity, List<RawRelation> relations) {
        Set<String> fkColumns = new HashSet<>();
        for (Edge e : graph.outgoing(entity.id(), EdgeKind.REFERENCES)) {
            if (!isInferred(e)) {
                fkColumns.addAll(e.attrList("columns"));
            }
        }
        String prefix = Ids.remainder(entity.id()) + ".";
        Map<String, Column> byName = new TreeMap<>();
        for (Edge e : graph.outgoing(entity.id(), EdgeKind.CONTAINS)) {
            Node c = graph.node(e.to()).orElse(null);
            if (c == null || c.kind() != NodeKind.DB_COLUMN) {
                continue;
            }
            // 정의가 있는 테이블의 declared=false 컬럼은 SQL만 아는 컬럼이다(DDL에 없다): ERD에 넣지 않는다(테이블 명세와 리포트에서 따로 알린다).
            if (entity.declared() && !c.declared()) {
                continue;
            }
            String name = columnName(c, prefix);
            byName.putIfAbsent(name, new Column(name, blankToNull(c.attrString("dataType")),
                    c.attr("nullable") instanceof Boolean b ? b : null, c.attrBoolean("primaryKey"), fkColumns.contains(name),
                    c.attrBoolean("unique"), c.attrInt("ordinal", 0), c.attrString("defaultValue"), c.attrString("comment"),
                    !entity.declared()));
        }
        if (!entity.declared()) {
            for (Node c : orphanColumns.getOrDefault(entity.id(), List.of())) {
                String name = columnName(c, prefix);
                byName.putIfAbsent(name, referenced(name, c.attrString("dataType")));
            }
            // 관계에 쓰인 컬럼도 참조된 컬럼이다.
            for (RawRelation r : relations) {
                if (r.childId().equals(entity.id())) {
                    r.columns().forEach(n -> byName.putIfAbsent(n, referenced(n, null)));
                }
                if (r.parentId().equals(entity.id())) {
                    r.refColumns().forEach(n -> byName.putIfAbsent(n, referenced(n, null)));
                }
            }
        }
        // 테이블 속성의 primaryKey(List), uniqueKeys(List, 원소는 "COL1,COL2")도 컬럼 표시에 반영한다.
        Set<String> tablePk = new HashSet<>(entity.attrList("primaryKey"));
        Set<String> singleUnique = new HashSet<>();
        for (List<String> key : uniqueKeys(entity)) {
            if (key.size() == 1) {
                singleUnique.add(key.get(0));
            }
        }
        byName.replaceAll((name, c) -> (tablePk.contains(name) && !c.primaryKey()) || (singleUnique.contains(name) && !c.unique())
                ? new Column(c.name(), c.dataType(), c.nullable(), c.primaryKey() || tablePk.contains(name), c.foreignKey(),
                        c.unique() || singleUnique.contains(name), c.ordinal(), c.defaultValue(), c.comment(), c.referencedOnly())
                : c);
        List<Column> columns = new ArrayList<>(byName.values());
        columns.sort(Comparator.comparingInt((Column c) -> c.ordinal() <= 0 ? Integer.MAX_VALUE : c.ordinal())
                .thenComparing(Column::name));
        return columns;
    }

    /** 테이블 속성 uniqueKeys를 컬럼 이름 목록들로 나눈다. */
    private static List<List<String>> uniqueKeys(Node entity) {
        List<List<String>> keys = new ArrayList<>();
        for (String key : entity.attrList("uniqueKeys")) {
            List<String> columns = new ArrayList<>();
            for (String part : key.split(",")) {
                if (!part.isBlank()) {
                    columns.add(part.strip());
                }
            }
            if (!columns.isEmpty()) {
                keys.add(columns);
            }
        }
        return keys;
    }

    private static Column referenced(String name, String dataType) {
        return new Column(name, blankToNull(dataType), null, false, false, false, 0, null, null, true);
    }

    private static String columnName(Node column, String ownerPrefix) {
        String rest = Ids.remainder(column.id());
        if (rest.startsWith(ownerPrefix) && rest.length() > ownerPrefix.length()) {
            return rest.substring(ownerPrefix.length());
        }
        return column.name();
    }

    private static String blankToNull(String s) {
        return Texts.isBlank(s) ? null : s.strip();
    }

    /** PK, FK, 그리고 그려지는 관계의 양 끝 컬럼. */
    private static List<Column> keyColumns(String entityId, List<Column> all, List<Relation> relations) {
        Set<String> used = new HashSet<>();
        for (Relation r : relations) {
            if (r.childId().equals(entityId)) {
                used.addAll(r.columns());
            }
            if (r.parentId().equals(entityId)) {
                used.addAll(r.refColumns());
            }
        }
        return all.stream().filter(c -> c.primaryKey() || c.foreignKey() || used.contains(c.name())).toList();
    }

    /**
     * [자식 쪽, 부모 쪽]. 엣지의 {@code cardinality} 속성({@code N:1}, {@code 1:1}, {@code 0..1:1} 등)이 있으면 자식 쪽은 그것을 따르고,
     * 없거나 읽지 못하면 컬럼 정보로 계산한다. 부모 쪽은 속성이 0..1을 말하거나 FK 컬럼이 nullable이면 0..1이다.
     * 추론 관계의 부모 쪽은 항상 0..1이다(제약이 없어 "반드시 하나"를 알 수 없다. 속성이 {@code N:1}이어도 같다. F-013-R28).
     * 자식 쪽의 "1"은 부모마다 자식이 많아야 하나라는 뜻이라 0..1로 둔다.
     *
     * <p>추론 관계의 {@code cardinality=unknown}은 링커가 정하지 못했다는 뜻이라 계산하지 않고 양쪽 다 모름으로 둔다(F-013-R27):
     * 부모 쪽 조인 컬럼이 유일한지 모르는데 자식 컬럼만 보고 N:1로 그리면 모르는 것을 확정처럼 그리게 된다. 방향은 그대로 안다
     * ({@code directionFrom=DDL}: 같은 테이블 쌍의 DDL 외래 키 방향을 따른 것). DDL 관계의 {@code unknown}은 전처럼 계산한다
     * (외래 키는 부모 쪽이 유일하다).
     */
    private static Cardinality[] cardinality(RawRelation r, List<Column> childColumns, List<List<String>> childUniqueKeys) {
        if (!r.directionKnown()) {
            return new Cardinality[] {Cardinality.UNKNOWN, Cardinality.UNKNOWN};
        }
        if (r.inferred() && "unknown".equalsIgnoreCase(Texts.nullToEmpty(r.edge().attrString("cardinality")).strip())) {
            return new Cardinality[] {Cardinality.UNKNOWN, Cardinality.UNKNOWN};
        }
        Map<String, Column> byName = new HashMap<>();
        childColumns.forEach(c -> byName.put(c.name(), c));
        Set<String> fk = new HashSet<>(r.columns());
        Set<String> pk = new HashSet<>();
        childColumns.stream().filter(Column::primaryKey).forEach(c -> pk.add(c.name()));
        boolean unique = !fk.isEmpty() && ((!pk.isEmpty() && fk.containsAll(pk))
                || fk.stream().anyMatch(n -> byName.containsKey(n) && byName.get(n).unique())
                || childUniqueKeys.stream().anyMatch(fk::containsAll));
        boolean optional = fk.stream().anyMatch(n -> byName.containsKey(n) && !byName.get(n).primaryKey()
                && Boolean.TRUE.equals(byName.get(n).nullable()));
        Cardinality child = unique ? Cardinality.ZERO_OR_ONE : Cardinality.MANY;
        // 추론 관계에는 제약이 없다: 자식 컬럼이 NOT NULL이어도 그 값의 부모 행이 반드시 있다고 할 수 없으므로 부모 쪽은 0..1이다(F-013-R28).
        Cardinality parent = optional || r.inferred() ? Cardinality.ZERO_OR_ONE : Cardinality.ONE;
        Cardinality[] explicit = parseCardinality(r.edge().attrString("cardinality"));
        if (explicit != null) {
            child = explicit[0] == Cardinality.MANY ? Cardinality.MANY : Cardinality.ZERO_OR_ONE;
            if (explicit[1] == Cardinality.ZERO_OR_ONE) {
                parent = Cardinality.ZERO_OR_ONE;
            }
        }
        return new Cardinality[] {child, parent};
    }

    /** {@code <자식>:<부모>} 형식. 해석하지 못하면({@code unknown} 포함) null. */
    private static Cardinality[] parseCardinality(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.strip().split(":");
        if (parts.length != 2) {
            return null;
        }
        Cardinality child = parseSide(parts[0]);
        Cardinality parent = parseSide(parts[1]);
        return child == null || parent == null ? null : new Cardinality[] {child, parent};
    }

    private static Cardinality parseSide(String token) {
        return switch (token.strip().toUpperCase(Locale.ROOT)) {
            case "1", "1..1" -> Cardinality.ONE;
            case "0..1" -> Cardinality.ZERO_OR_ONE;
            case "N", "M", "*", "0..*", "1..*", "0..N", "1..N" -> Cardinality.MANY;
            default -> null;
        };
    }
}
