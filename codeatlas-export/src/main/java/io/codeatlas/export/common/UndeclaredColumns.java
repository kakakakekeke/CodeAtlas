package io.codeatlas.export.common;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * DDL에 없는데 SQL이 참조하는 컬럼: 정의가 있는 테이블·뷰의 {@code declared=false} 컬럼 노드(sql 분석기가 SQL에서만 본 컬럼).
 * DDL이 오래됐거나 다른 방언·버전의 DDL이 선택됐을 때 나온다. ERD 본체에는 넣지 않고 테이블 명세와 리포트에서 따로 알린다.
 * 정의가 없는 테이블의 컬럼은 대상이 아니다(그 테이블은 "DDL에 없는 테이블"로 나온다).
 */
public final class UndeclaredColumns {

    /**
     * @param tableId 소속 테이블(뷰)의 노드 ID
     * @param name 컬럼 이름
     * @param statements 그 컬럼을 읽거나 쓰는 구문의 노드 ID(ID 순)
     */
    public record Column(String tableId, String name, List<String> statements) {
    }

    private UndeclaredColumns() {
    }

    /** 테이블 ID → 그 테이블의 DDL에 없는 컬럼(이름순). 없으면 빈 맵. 테이블은 ID 순이다. */
    public static Map<String, List<Column>> find(GraphModel graph) {
        Map<String, List<Column>> byTable = new TreeMap<>();
        for (Node column : graph.nodes(NodeKind.DB_COLUMN)) {
            if (column.declared()) {
                continue;
            }
            String tableId = DbGraph.tableOfColumn(graph, column.id()).orElse(null);
            Node table = tableId == null ? null : graph.node(tableId).orElse(null);
            if (table == null || !table.declared() || !DbGraph.isTableLike(table)) {
                continue;
            }
            Set<String> statements = new TreeSet<>();
            for (Edge e : graph.incoming(column.id(), EdgeKind.READS, EdgeKind.WRITES)) {
                statements.add(e.from());
            }
            String rest = Ids.remainder(column.id());
            String prefix = Ids.remainder(tableId) + ".";
            String name = rest.startsWith(prefix) ? rest.substring(prefix.length()) : rest.substring(rest.lastIndexOf('.') + 1);
            byTable.computeIfAbsent(tableId, k -> new ArrayList<>()).add(new Column(tableId, name, List.copyOf(statements)));
        }
        byTable.values().forEach(list -> list.sort(Comparator.comparing(Column::name)));
        return byTable;
    }
}
