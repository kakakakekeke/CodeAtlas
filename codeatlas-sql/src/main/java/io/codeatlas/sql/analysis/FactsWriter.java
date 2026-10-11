package io.codeatlas.sql.analysis;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.SqlDiagnosticCodes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 구문 노드 하나(주인)에 대한 사실을 그래프에 쓴다. 같은 주인의 사실을 {@link #add}로 여러 번 받아 합친 뒤
 * {@link #finish}에서 한 번에 쓴다: READS/WRITES(테이블·컬럼·시퀀스), CALLS, JOINS, 동적 테이블의 미해결 엣지와 진단,
 * 주인 노드의 attrs(statementType, dialect, dynamic, dummyTable).
 *
 * <p>사용: {@code new FactsWriter(graph, catalog, ownerId).add(facts, locator).finish(dialect)}.
 */
public final class FactsWriter {

    /** 동적 테이블 미해결 노드의 분류. */
    public static final String DYNAMIC_CATEGORY = "sql-dynamic";

    private final GraphModel graph;
    private final Catalog catalog;
    private final String ownerId;
    private final StatementFacts merged = new StatementFacts();
    private final Map<String, SourceLocation> locations = new HashMap<>();
    private final Map<String, Boolean> exactness = new HashMap<>();
    private SourceLocation ownerLocation;
    private boolean any;

    public FactsWriter(GraphModel graph, Catalog catalog, String ownerId) {
        this.graph = graph;
        this.catalog = catalog == null ? new Catalog() : catalog;
        this.ownerId = ownerId;
    }

    /** 지금까지 합친 사실. */
    public StatementFacts merged() {
        return merged;
    }

    /** 사실을 더한다. 위치는 이 시점의 locator로 바꿔 기억한다(먼저 들어온 위치가 남는다). */
    public FactsWriter add(StatementFacts facts, Locator locator) {
        any = true;
        if (ownerLocation == null) {
            ownerLocation = locator.locate(null);
        }
        facts.tableReads().forEach(r -> remember("R:" + r.table(), locator, r.pos()));
        facts.tableWrites().forEach(w -> remember("W:" + w.table() + "|" + w.op(), locator, w.pos()));
        facts.columnReads().forEach(c -> remember("RC:" + c.table() + "." + c.column(), locator, c.pos()));
        facts.columnWrites().forEach(c -> remember("WC:" + c.table() + "." + c.column() + "|" + c.op(), locator, c.pos()));
        facts.sequences().forEach(s -> remember("S:" + s.sequence(), locator, s.pos()));
        facts.calls().forEach(c -> remember("C:" + String.join(".", c.name()) + "|" + c.explicit(), locator, c.pos()));
        facts.joins().forEach(j -> remember(joinKey(j), locator, j.pos()));
        facts.dynamicTables().forEach(d -> remember("D:" + d.write() + "|" + d.op(), locator, d.pos()));
        facts.unknownColumns().forEach(u -> remember("U:" + u.column(), locator, u.pos()));
        merged.merge(facts);
        return this;
    }

    private void remember(String key, Locator locator, io.codeatlas.sql.ast.Pos pos) {
        if (!locations.containsKey(key)) {
            locations.put(key, locator.locate(pos));
            exactness.put(key, locator.exact() && pos != null && pos.known());
        }
    }

    private static String joinKey(StatementFacts.Join j) {
        return "J:" + (j.leftTable().compareTo(j.rightTable()) <= 0 ? j.leftTable() + "|" + j.rightTable() : j.rightTable() + "|" + j.leftTable())
                + "|" + new java.util.TreeSet<>(j.pairSet());
    }

    /**
     * 합친 사실을 그래프에 쓰고 주인 노드의 attrs를 채운다.
     *
     * @param dialect 판정한 방언. 모르면 null(attrs에 넣지 않는다)
     */
    public StatementFacts finish(Dialect dialect) {
        // DDL에 없는 컬럼의 추측 가운데 다른 전개본이 읽는 테이블로 설명되는 것은 버린다(F-006-R31)
        merged.dropGuessesExplainedBy((table, column) -> {
            Catalog.TableDef def = catalog.table(catalog.resolveSynonym(table));
            return def != null && def.hasColumn(column);
        });
        for (StatementFacts.TableRead r : merged.tableReads()) {
            catalog.noteTableUse(r.table(), merged.systemCatalogs().contains(r.table()));
            String id = SqlIds.table(r.table());
            graph.reference(id, NodeKind.DB_TABLE, r.table());
            Edge.Builder e = Edge.builder(ownerId, id, EdgeKind.READS).location(locations.get("R:" + r.table()));
            if (r.star()) {
                e.attr("star", true);
            }
            if (r.lock()) {
                e.attr("lock", true);
            }
            graph.addEdge(e.build());
        }
        for (StatementFacts.TableWrite w : merged.tableWrites()) {
            String id = SqlIds.table(w.table());
            graph.reference(id, NodeKind.DB_TABLE, w.table());
            Edge.Builder e = Edge.builder(ownerId, id, EdgeKind.WRITES).location(locations.get("W:" + w.table() + "|" + w.op())).attr("op", w.op());
            if (w.truncate()) {
                e.attr("truncate", true);
            }
            graph.addEdge(e.build());
        }
        for (StatementFacts.ColumnUse c : merged.columnReads()) {
            String id = SqlIds.column(c.table(), c.column());
            graph.reference(id, NodeKind.DB_COLUMN, c.column());
            graph.addEdge(Edge.builder(ownerId, id, EdgeKind.READS).location(locations.get("RC:" + c.table() + "." + c.column())).build());
        }
        for (StatementFacts.ColumnUse c : merged.columnWrites()) {
            String id = SqlIds.column(c.table(), c.column());
            graph.reference(id, NodeKind.DB_COLUMN, c.column());
            graph.addEdge(Edge.builder(ownerId, id, EdgeKind.WRITES)
                    .location(locations.get("WC:" + c.table() + "." + c.column() + "|" + c.op())).attr("op", c.op()).build());
        }
        for (StatementFacts.SequenceUse s : merged.sequences()) {
            String id = SqlIds.sequence(s.sequence());
            graph.reference(id, NodeKind.DB_SEQUENCE, s.sequence());
            graph.addEdge(Edge.builder(ownerId, id, EdgeKind.READS).location(locations.get("S:" + s.sequence())).attr("sequence", true).build());
        }
        for (StatementFacts.Call c : merged.calls()) {
            String id = c.explicit() ? SqlIds.routineOfCall(catalog, c.name())
                    : SqlIds.routine(c.name().size() > 1 ? c.name().get(0) : null, c.name().get(c.name().size() - 1));
            graph.reference(id, NodeKind.DB_ROUTINE, c.name().get(c.name().size() - 1));
            graph.addEdge(Edge.builder(ownerId, id, EdgeKind.CALLS)
                    .location(locations.get("C:" + String.join(".", c.name()) + "|" + c.explicit())).build());
        }
        writeJoins();
        writeDynamic();
        reportUnknownColumns();
        Map<String, Object> attrs = new LinkedHashMap<>();
        String type = merged.statementType();
        Node owner = graph.node(ownerId).orElse(null);
        if (!type.equals("OTHER") || owner == null || owner.attrString("statementType") == null) {
            attrs.put("statementType", type);
        }
        if (dialect != null && dialect != Dialect.AUTO) {
            attrs.put("dialect", dialect.name());
        }
        if (merged.dynamic()) {
            attrs.put("dynamic", true);
        }
        if (merged.dummyTable() != null) {
            attrs.put("dummyTable", merged.dummyTable());
        }
        if (any || owner == null || owner.attrString("statementType") == null) {
            graph.putAttrs(ownerId, attrs);
        }
        return merged;
    }

    private void writeJoins() {
        Set<String> used = new HashSet<>();
        Map<String, Integer> shift = new HashMap<>();
        for (StatementFacts.Join j : merged.joins()) {
            String from = SqlIds.table(j.leftTable());
            String to = SqlIds.table(j.rightTable());
            graph.reference(from, NodeKind.DB_TABLE, j.leftTable());
            graph.reference(to, NodeKind.DB_TABLE, j.rightTable());
            SourceLocation location = locations.get(joinKey(j));
            if (location == null) {
                location = ownerLocation;
            }
            // (from, to, kind, location)이 같으면 그래프가 하나로 합치므로, 같은 구문 안의 다른 조인은 위치를 구분한다
            String pair = from + ">" + to;
            while (location != null && !used.add(pair + "@" + location)) {
                int k = shift.merge(pair, 1, Integer::sum);
                SourceLocation base = locations.getOrDefault(joinKey(j), ownerLocation);
                location = new SourceLocation(base.file(), base.startLine(), base.startColumn() + k, base.endLine(), base.endColumn() + k);
            }
            Edge.Builder edge = Edge.builder(from, to, EdgeKind.JOINS).location(location)
                    .attr("leftColumns", j.leftColumns()).attr("rightColumns", j.rightColumns()).attr("statement", ownerId);
            fixedAttrs(edge, "left", j.leftFixed());
            fixedAttrs(edge, "right", j.rightFixed());
            if (j.wrapped()) {
                // 형 변환·정리 함수로 감싼 컬럼끼리의 등치(F-006-R29). 보통 조인에는 키를 넣지 않는다
                edge.attr("wrapped", true);
                if (j.leftWrapped()) {
                    edge.attr("leftWrapped", true);
                }
                if (j.rightWrapped()) {
                    edge.attr("rightWrapped", true);
                }
            }
            graph.addEdge(edge.build());
        }
    }

    /**
     * 소속 테이블을 정하지 못한 비한정 컬럼을 주인 구문마다 진단 하나로 알린다(F-006-R31). 전개본 여러 벌이 같은 컬럼을 말해도 한 번이다.
     * 위치는 처음 나온 컬럼의 자리다.
     */
    private void reportUnknownColumns() {
        if (merged.unknownColumns().isEmpty()) {
            return;
        }
        Set<String> tables = new java.util.LinkedHashSet<>();
        List<String> columns = new java.util.ArrayList<>();
        for (StatementFacts.UnknownColumn u : merged.unknownColumns()) {
            columns.add(u.column());
            tables.addAll(u.tables());
        }
        SourceLocation location = locations.getOrDefault("U:" + columns.get(0), ownerLocation);
        graph.report(Diagnostic.info(SqlDiagnosticCodes.UNKNOWN_COLUMN, "DDL에 없는 컬럼이라 소속 테이블을 정하지 못함: " + String.join(", ", columns)
                + " (후보 테이블: " + String.join(", ", tables) + ") (" + ownerId + ")", location));
    }

    /** 고정 컬럼 attrs({@code <side>FixedColumns}, {@code <side>FixedValues}). 고정 컬럼이 없으면 넣지 않는다(F-006-R13). */
    private static void fixedAttrs(Edge.Builder edge, String side, Map<String, Set<String>> fixed) {
        if (fixed.isEmpty()) {
            return;
        }
        List<String> values = new java.util.ArrayList<>();
        fixed.forEach((column, constants) -> constants.forEach(constant -> values.add(column + "=" + DynamicText.display(constant))));
        edge.attr(side + "FixedColumns", List.copyOf(fixed.keySet()));
        edge.attr(side + "FixedValues", values);
    }

    private void writeDynamic() {
        List<StatementFacts.DynamicTable> dynamics = merged.dynamicTables();
        if (dynamics.isEmpty()) {
            return;
        }
        String targetId = Ids.unresolved(DYNAMIC_CATEGORY, ownerId);
        Set<String> done = new HashSet<>();
        for (StatementFacts.DynamicTable d : dynamics) {
            String key = d.write() + "|" + d.op();
            if (!done.add(key)) {
                continue;
            }
            SourceLocation location = locations.getOrDefault("D:" + key, ownerLocation);
            // 원문 조각: 내부 표식(__DYNAMIC__)이 남아 있으면 ${…}로 적는다(F-006-R26)
            String text = DynamicText.display(d.text());
            graph.declare(Node.builder(targetId, NodeKind.UNRESOLVED).name(text).qualifiedName(ownerId)
                    .location(location).attr("category", DYNAMIC_CATEGORY).build());
            Edge.Builder e = Edge.builder(ownerId, targetId, d.write() ? EdgeKind.WRITES : EdgeKind.READS).unresolved()
                    .location(location).attr("text", text);
            if (d.write()) {
                e.attr("op", d.op());
            }
            graph.addEdge(e.build());
            graph.report(Diagnostic.warn(DiagnosticCodes.DYNAMIC_SQL,
                    (d.statement() ? "동적 SQL을 정적으로 풀 수 없어 대상을 알 수 없음: " : "테이블 이름이 동적(${})이라 대상을 알 수 없음: ")
                            + text + " (" + ownerId + ")", location));
        }
    }
}
