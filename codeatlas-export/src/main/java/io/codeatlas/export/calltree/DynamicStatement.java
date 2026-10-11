package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.common.NodeLabels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 대상 테이블을 정적으로 알 수 없는 구문: {@code unresolved:sql-dynamic:}으로 가는 READS/WRITES가 있는 구문이다
 * (MyBatis의 {@code ${}} 테이블 이름, 루틴의 풀리지 않은 {@code EXECUTE IMMEDIATE} 등).
 * 어느 테이블에도 넣을 수 없어서 CRUD 매트릭스, 영향도, 요약 리포트가 따로 보여 준다.
 *
 * @param id 구문 노드 ID
 * @param label 짧은 표시 이름. 예: {@code OrderMapper.selectBackup <SELECT>}
 * @param access 접근 종류({@code R}, {@code C}, {@code CU} 등. READS는 R, WRITES는 op에 따라)
 * @param location 구문의 {@code file:line}. 구문 노드에 위치가 없으면 엣지의 위치, 그것도 없으면 null
 * @param text 엣지의 {@code attrs.text}(알아낸 원문 조각). 여럿이면 사전순으로 앞선 것, 없으면 null
 */
public record DynamicStatement(String id, String label, String access, String location, String text) {

    /** 미해결 분류 이름(F-001 design 3.6). */
    public static final String CATEGORY = "sql-dynamic";

    private static final String PREFIX = "unresolved:" + CATEGORY + ":";

    /** 엣지가 대상 테이블을 알 수 없는 동적 접근인지. */
    public static boolean isDynamicAccess(GraphModel graph, Edge edge) {
        if (edge.kind() != EdgeKind.READS && edge.kind() != EdgeKind.WRITES) {
            return false;
        }
        if (edge.to().startsWith(PREFIX)) {
            return true;
        }
        Node target = graph.node(edge.to()).orElse(null);
        return target != null && target.kind() == NodeKind.UNRESOLVED && CATEGORY.equals(target.attrString("category"));
    }

    /** 그래프의 동적 구문 전부(구문 ID 순). */
    public static List<DynamicStatement> find(GraphModel graph) {
        Map<String, Set<Character>> letters = new TreeMap<>();
        Map<String, String> texts = new TreeMap<>();
        Map<String, String> sites = new TreeMap<>();
        for (EdgeKind kind : List.of(EdgeKind.READS, EdgeKind.WRITES)) {
            for (Edge e : graph.edges(kind)) {
                if (!isDynamicAccess(graph, e)) {
                    continue;
                }
                letters.computeIfAbsent(e.from(), k -> new TreeSet<>()).addAll(Access.letters(graph, e, e.from()));
                String text = e.attrString("text");
                if (text != null && !text.isBlank()) {
                    texts.merge(e.from(), text, (a, b) -> a.compareTo(b) <= 0 ? a : b);
                }
                String site = NodeLabels.location(e.location());
                if (site != null) {
                    sites.merge(e.from(), site, (a, b) -> a.compareTo(b) <= 0 ? a : b);
                }
            }
        }
        List<DynamicStatement> result = new ArrayList<>();
        letters.forEach((id, set) -> {
            String location = NodeLabels.location(graph, id);
            result.add(new DynamicStatement(id, NodeLabels.label(graph, id), Access.format(set), location != null ? location : sites.get(id),
                    texts.get(id)));
        });
        return result;
    }
}
