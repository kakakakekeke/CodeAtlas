package io.codeatlas.sql.routine;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.sql.analysis.FactsWriter;
import io.codeatlas.sql.analysis.SqlIds;
import io.codeatlas.sql.ast.Pos;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 루틴(또는 패키지 초기화부)이 주인인 기록: 구문마다 {@code SQL_STATEMENT} 노드와 {@code 주인 -CONTAINS→ 구문}을 만들고,
 * 호출은 {@code 주인 -CALLS→ ROUTINE} 엣지로 쓴다.
 */
final class RoutineSink implements BodyWalker.Sink {

    private final BodyWalker.Env env;
    private final String ownerId;
    private final String ownerRef;
    private final String shortName;
    private final String displayName;
    private final Set<String> statementIds = new LinkedHashSet<>();

    /**
     * @param ownerId 주인 노드 ID({@code db:routine:..} 또는 {@code db:package:..})
     * @param shortName 주인의 짧은 이름(구문 노드의 name에 쓴다)
     * @param displayName 주인의 전체 이름({@code PKG.NAME})
     */
    RoutineSink(BodyWalker.Env env, String ownerId, String shortName, String displayName) {
        this.env = env;
        this.ownerId = ownerId;
        this.ownerRef = ownerId.startsWith("db:routine:") ? ownerId : Ids.remainder(ownerId);
        this.shortName = shortName;
        this.displayName = displayName;
    }

    /** 지금까지 만든 구문 노드 ID. */
    Set<String> statementIds() {
        return statementIds;
    }

    @Override
    public void statement(Pos pos, String text, Map<String, Object> attrs, Consumer<FactsWriter> fill) {
        int line = pos.line();
        String id = Ids.routineStatement(ownerRef, line);
        Node.Builder node = Node.builder(id, NodeKind.SQL_STATEMENT).name(shortName + ":" + line).qualifiedName(displayName + ":" + line)
                .location(env.locator().locate(pos)).attr("sql", text).attr("routine", ownerId);
        attrs.forEach(node::attr);
        env.graph().declare(node.build());
        env.graph().addEdge(Edge.of(ownerId, id, EdgeKind.CONTAINS));
        FactsWriter writer = new FactsWriter(env.graph(), env.catalog(), id);
        fill.accept(writer);
        writer.finish(env.dialect());
        statementIds.add(id);
    }

    @Override
    public void call(String packageName, String name, Pos pos, boolean function) {
        String target = SqlIds.routine(packageName, name);
        env.graph().reference(target, NodeKind.DB_ROUTINE, name);
        env.graph().addEdge(Edge.builder(ownerId, target, EdgeKind.CALLS).location(env.locator().locate(pos))
                .attr("callType", function ? "function" : "procedure").build());
    }
}
