package io.codeatlas.export.common;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import java.util.Optional;

/** DB 노드에 대한 그래프 조회 보조 함수. */
public final class DbGraph {

    private DbGraph() {
    }

    public static boolean isTableLike(Node node) {
        return node != null && (node.kind() == NodeKind.DB_TABLE || node.kind() == NodeKind.DB_VIEW);
    }

    /**
     * 컬럼 ID가 속한 테이블(또는 뷰)의 ID. 컬럼 ID({@code db:column:<테이블>.<컬럼>})의 테이블 이름이 그래프에 있으면 그것이고
     * (계약상 컬럼 ID가 소속을 말한다. READS/WRITES 엣지마다 불리므로 엣지를 훑지 않는다), ID로 찾지 못할 때만
     * 그 컬럼으로 들어오는 CONTAINS 엣지를 본다. 찾지 못하면 비어 있다.
     */
    public static Optional<String> tableOfColumn(GraphModel graph, String columnId) {
        if (Ids.kindOf(columnId).orElse(null) == NodeKind.DB_COLUMN) {
            String rest = Ids.remainder(columnId);
            for (int dot = rest.lastIndexOf('.'); dot > 0; dot = rest.lastIndexOf('.', dot - 1)) {
                String owner = rest.substring(0, dot);
                if (graph.hasNode("db:table:" + owner)) {
                    return Optional.of("db:table:" + owner);
                }
                if (graph.hasNode("db:view:" + owner)) {
                    return Optional.of("db:view:" + owner);
                }
            }
        }
        for (Edge e : graph.incoming(columnId, EdgeKind.CONTAINS)) {
            if (isTableLike(graph.node(e.from()).orElse(null))) {
                return Optional.of(e.from());
            }
        }
        return Optional.empty();
    }

    /**
     * READS/WRITES 엣지의 대상을 테이블로 접는다. 대상이 테이블·뷰면 그대로, 컬럼이면 소속 테이블, 그 밖이면 비어 있다.
     */
    public static Optional<String> accessedTable(GraphModel graph, Edge edge) {
        Node target = graph.node(edge.to()).orElse(null);
        if (isTableLike(target)) {
            return Optional.of(edge.to());
        }
        if ((target != null && target.kind() == NodeKind.DB_COLUMN) || (target == null && edge.to().startsWith("db:column:"))) {
            return tableOfColumn(graph, edge.to());
        }
        return Optional.empty();
    }
}
