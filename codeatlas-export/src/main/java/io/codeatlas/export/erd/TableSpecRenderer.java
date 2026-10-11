package io.codeatlas.export.erd;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.common.DataLoads;
import io.codeatlas.export.common.DbGraph;
import io.codeatlas.export.common.NodeLabels;
import io.codeatlas.export.common.UndeclaredColumns;
import io.codeatlas.export.common.Texts;
import io.codeatlas.export.erd.ErdModel.Column;
import io.codeatlas.export.erd.ErdModel.Entity;
import io.codeatlas.export.erd.ErdModel.Relation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테이블 명세(Markdown). 테이블·뷰마다 컬럼 표, PK, 관계(나가는 것 →, 들어오는 것 ←), 참조하는 구문 수를 적는다.
 * 테이블은 이름순이고, 시작 테이블을 줬으면({@link ErdOptions#tables()}) 그것들이 준 순서대로 맨 앞에 온다.
 * 데이터 적재 구문(스크립트의 리터럴 INSERT 묶음)은 구문 수에 넣지 않고 따로 적는다.
 * 시스템 카탈로그({@code systemCatalog=true}: DB가 제공하는 데이터 사전)는 명세를 쓰지 않고 전체 명세의 끝에 이름만 모은다.
 *
 * <pre>{@code
 * String all = TableSpecRenderer.render(graph);                                                   // 전체 테이블과 뷰
 * String some = TableSpecRenderer.render(graph, ErdOptions.builder().tables("ORDERS").neighborDepth(1).build());
 * }</pre>
 */
public final class TableSpecRenderer {

    /** 시스템 카탈로그({@code systemCatalog=true})의 이름을 따로 모은 절의 제목. */
    public static final String SYSTEM_CATALOG_SECTION = "시스템 카탈로그";

    private TableSpecRenderer() {
    }

    /** 전체 테이블과 뷰의 명세. */
    public static String render(GraphModel graph) {
        return render(graph, ErdOptions.builder().includeViews(true).build());
    }

    /**
     * 옵션의 범위에 드는 테이블의 명세. 범위(시작 테이블과 이웃 깊이, 이름 패턴, 뷰 포함)와 관계 옵션(추론 관계 포함, 최소 근거 수)은
     * ERD와 같은 규칙으로 적용한다. 컬럼 표시 수준과 주석 표시는 쓰지 않는다(컬럼 표는 항상 전부다).
     * 범위를 좁히면 범위 밖 테이블과의 관계는 적지 않는다.
     *
     * @param options null이면 {@link ErdOptions#defaults()}(전체 테이블, 뷰 제외)
     */
    public static String render(GraphModel graph, ErdOptions options) {
        return render(graph, options, false);
    }

    /**
     * @param includeDataLoad true면 "참조하는 구문"에 데이터 적재 구문({@code attrs.dataLoad=true})도 센다.
     *        기본(false)은 빼고, 적재되는 테이블에는 {@code - 데이터 적재: 구문 n개, m행} 줄을 따로 적는다
     */
    public static String render(GraphModel graph, ErdOptions options, boolean includeDataLoad) {
        ErdOptions given = options == null ? ErdOptions.defaults() : options;
        ErdOptions scope = ErdOptions.builder().tables(given.tables()).neighborDepth(given.neighborDepth()).patterns(given.patterns())
                .includeViews(given.includeViews()).includeInferred(given.includeInferred()).minEvidence(given.minEvidence()).build();
        ErdModel model = Erd.model(graph, scope);
        Map<String, Set<String>> readers = new HashMap<>();
        Map<String, Set<String>> writers = new HashMap<>();
        Set<String> dataLoads = includeDataLoad ? Set.of() : DataLoads.ids(graph);
        collectAccess(graph, EdgeKind.READS, readers, dataLoads);
        collectAccess(graph, EdgeKind.WRITES, writers, dataLoads);
        Map<String, DataLoads.Load> loads = dataLoads.isEmpty() ? Map.of() : DataLoads.summarize(graph).byTable();

        Map<String, List<UndeclaredColumns.Column>> undeclaredColumns = UndeclaredColumns.find(graph);
        long undeclared = model.entities().stream().filter(e -> !e.declared()).count();
        long views = model.entities().stream().filter(ErdLabels::isView).count();
        StringBuilder out = new StringBuilder("# 테이블 명세\n\n");
        if (!scope.wholeSchema()) {
            List<String> parts = new ArrayList<>();
            if (!scope.tables().isEmpty()) {
                parts.add("테이블 " + Texts.oneLine(String.join(", ", scope.tables())) + ", 이웃 깊이 " + scope.neighborDepth());
            }
            if (!scope.patterns().isEmpty()) {
                parts.add("이름 패턴 " + Texts.oneLine(String.join(", ", scope.patterns())));
            }
            out.append("- 범위: ").append(String.join(" / ", parts)).append(". 범위 밖 테이블과의 관계는 적지 않는다\n");
        }
        out.append("- 테이블 ").append(model.entities().size() - views).append("개 (DDL에 없는 테이블 ").append(undeclared)
                .append("개), 뷰 ").append(views).append("개\n");
        out.append("- 관계 ").append(model.relations().size()).append("개 (추론 ")
                .append(model.relations().stream().filter(Relation::inferred).count()).append("개)\n");
        // 시스템 카탈로그는 프로젝트 스키마가 아니다: 전체 명세에서만 끝에 이름을 따로 모은다.
        List<String> catalogs = scope.wholeSchema() ? model.systemCatalogNames() : List.of();
        if (!catalogs.isEmpty()) {
            out.append("- 시스템 카탈로그 ").append(catalogs.size()).append("개는 명세와 ERD에서 뺐다(아래 \"").append(SYSTEM_CATALOG_SECTION)
                    .append("\" 절에 이름만 적는다)\n");
        }

        for (Entity e : startTablesFirst(graph, model, scope.tables())) {
            out.append("\n## ").append(e.name());
            if (!e.declared()) {
                out.append(" (").append(ErdLabels.UNDECLARED).append(')');
            } else if (ErdLabels.isView(e)) {
                out.append(" (VIEW)");
            }
            out.append("\n\n");
            if (!Texts.isBlank(e.comment())) {
                out.append(Texts.oneLine(e.comment())).append("\n\n");
            }
            Node node = graph.node(e.id()).orElse(null);
            if (!Texts.isBlank(e.schema())) {
                out.append("- 스키마: ").append(e.schema()).append('\n');
            }
            if (node != null && node.location() != null) {
                out.append("- 정의: ").append(Texts.mdCode(NodeLabels.location(node.location()))).append('\n');
            }
            Set<String> r = readers.getOrDefault(e.id(), Set.of());
            Set<String> w = writers.getOrDefault(e.id(), Set.of());
            Set<String> all = new HashSet<>(r);
            all.addAll(w);
            out.append("- 참조하는 구문: ").append(all.size()).append("개 (읽기 ").append(r.size()).append(", 쓰기 ").append(w.size())
                    .append(")\n");
            DataLoads.Load load = loads.get(e.id());
            if (load != null) {
                out.append("- 데이터 적재: 구문 ").append(load.statements()).append("개, ").append(load.rowsText("행")).append('\n');
            }
            List<String> pk = e.columns().stream().filter(Column::primaryKey).map(Column::name).toList();
            if (!pk.isEmpty()) {
                out.append("- PK: ").append(String.join(", ", pk)).append('\n');
            }

            if (!e.columns().isEmpty()) {
                out.append('\n');
                out.append(e.declared() ? "" : "SQL에서 참조된 컬럼만 적는다.\n\n");
                out.append("| # | 컬럼 | 타입 | NULL | 키 | 기본값 | 설명 |\n|---|---|---|---|---|---|---|\n");
                int index = 0;
                for (Column c : e.columns()) {
                    index++;
                    List<String> keys = new ArrayList<>();
                    if (c.primaryKey()) {
                        keys.add("PK");
                    }
                    if (c.foreignKey()) {
                        keys.add("FK");
                    }
                    if (c.unique() && !c.primaryKey()) {
                        keys.add("UK");
                    }
                    String nullable = c.notNull() ? "N" : Boolean.TRUE.equals(c.nullable()) ? "Y" : "";
                    out.append("| ").append(c.ordinal() > 0 ? c.ordinal() : index).append(" | ").append(Texts.mdCell(c.name()))
                            .append(" | ").append(Texts.mdCell(c.dataType())).append(" | ").append(nullable).append(" | ")
                            .append(String.join(", ", keys)).append(" | ").append(Texts.mdCell(c.defaultValue())).append(" | ")
                            .append(Texts.mdCell(c.comment())).append(" |\n");
                }
            }

            List<UndeclaredColumns.Column> ghosts = undeclaredColumns.getOrDefault(e.id(), List.of());
            if (!ghosts.isEmpty()) {
                out.append("\nDDL에 없는데 SQL이 참조하는 컬럼:\n");
                for (UndeclaredColumns.Column ghost : ghosts) {
                    out.append("- ").append(Texts.mdCode(ghost.name())).append(" — 구문 ").append(ghost.statements().size()).append("개");
                    List<String> names = ghost.statements().stream().limit(5)
                            .map(s -> Texts.mdCode(NodeLabels.statementName(graph.node(s).orElse(null), s))).toList();
                    out.append(names.isEmpty() ? "" : ": " + String.join(", ", names)).append('\n');
                }
            }

            List<String> lines = new ArrayList<>();
            for (Relation rel : model.relations()) {
                if (rel.childId().equals(e.id())) {
                    lines.add("- → " + rel.parent() + relationDetail(rel));
                }
            }
            for (Relation rel : model.relations()) {
                if (rel.parentId().equals(e.id())) {
                    lines.add("- ← " + rel.child() + relationDetail(rel));
                }
            }
            if (!lines.isEmpty()) {
                out.append("\n관계\n\n").append(String.join("\n", lines)).append('\n');
            }
        }
        if (!catalogs.isEmpty()) {
            out.append("\n## ").append(SYSTEM_CATALOG_SECTION).append(" (").append(catalogs.size()).append(")\n\n");
            out.append("DB가 제공하는 데이터 사전이다. 프로젝트 스키마가 아니라서 이름만 적는다.\n\n");
            catalogs.forEach(name -> out.append("- ").append(Texts.mdCode(name)).append('\n'));
        }
        return out.toString();
    }

    /**
     * 명세에 적을 순서: 시작 테이블({@link ErdOptions#tables()})을 준 순서대로 앞에, 나머지(이웃, 이름 패턴에 맞은 테이블)를 이름순으로 뒤에 둔다.
     * 시작 테이블이 없으면(범위가 전체이거나 패턴뿐이면) 모델의 순서(이름순) 그대로다.
     */
    private static List<Entity> startTablesFirst(GraphModel graph, ErdModel model, List<String> tables) {
        if (tables.isEmpty()) {
            return model.entities();
        }
        Map<String, Node> candidates = new LinkedHashMap<>();
        Map<String, Entity> byId = new HashMap<>();
        for (Entity e : model.entities()) {
            graph.node(e.id()).ifPresent(node -> candidates.put(e.id(), node));
            byId.put(e.id(), e);
        }
        Set<String> first = new LinkedHashSet<>();
        for (String name : tables) {
            first.addAll(ErdModelBuilder.resolve(name, candidates));
        }
        List<Entity> ordered = new ArrayList<>();
        first.forEach(id -> ordered.add(byId.get(id)));
        for (Entity e : model.entities()) {
            if (!first.contains(e.id())) {
                ordered.add(e);
            }
        }
        return ordered;
    }

    private static String relationDetail(Relation rel) {
        StringBuilder s = new StringBuilder();
        if (!rel.columns().isEmpty() || !rel.refColumns().isEmpty()) {
            s.append(" (").append(String.join(", ", rel.columns())).append(" → ").append(String.join(", ", rel.refColumns()))
                    .append(')');
        }
        if (rel.constraint() != null) {
            s.append(" `").append(rel.constraint().replace('`', '\'')).append('`');
        }
        if (rel.inferred()) {
            s.append(" (").append(ErdLabels.inferredNote(rel)).append(')');
        } else if (rel.fromUpgrade()) {
            // upgrade·patch 스크립트의 ALTER TABLE에서 온 외래 키: 현재 스키마에 반영됐는지는 스크립트를 봐야 안다.
            s.append(" (").append(ErdLabels.FROM_UPGRADE).append(')');
        }
        return s.toString();
    }

    /** 테이블 ID → 그 테이블(또는 그 컬럼)에 엣지가 있는 구문 ID. excluded의 구문은 세지 않는다. */
    private static void collectAccess(GraphModel graph, EdgeKind kind, Map<String, Set<String>> target, Set<String> excluded) {
        for (Edge e : graph.edges(kind)) {
            if (!excluded.contains(e.from()) && graph.node(e.from()).map(n -> n.kind() == NodeKind.SQL_STATEMENT).orElse(false)) {
                DbGraph.accessedTable(graph, e).ifPresent(t -> target.computeIfAbsent(t, k -> new HashSet<>()).add(e.from()));
            }
        }
    }
}
