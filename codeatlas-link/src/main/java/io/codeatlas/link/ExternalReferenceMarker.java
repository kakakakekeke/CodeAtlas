package io.codeatlas.link;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Linker;

import java.util.List;
import java.util.Map;

/**
 * 외부 참조 표시(F-012-R14). 어느 분석기도 선언하지 않은 Java 타입·메서드·필드 참조({@code declared=false})에 {@code external=true}를 붙인다.
 *
 * <p>springxml·mybatis는 XML에 적힌 클래스 이름을 {@code java:type:} 참조로만 만든다. 그 타입을 java 분석기가 나중에 선언할지는
 * 만드는 시점에 알 수 없으므로, 모든 분석기가 끝난 뒤 링커가 "끝내 선언되지 않은" 것을 외부 타입으로 표시한다
 * (java가 직접 참조한 외부 타입에는 java가 이미 붙여 둔다). 다른 링커가 이 표시를 볼 수 있게 가장 먼저 돈다.
 */
public final class ExternalReferenceMarker implements Linker {

    private static final List<NodeKind> JAVA_KINDS = List.of(NodeKind.JAVA_TYPE, NodeKind.JAVA_METHOD, NodeKind.JAVA_FIELD);

    @Override
    public String name() {
        return "link-external-reference";
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public void link(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        for (NodeKind kind : JAVA_KINDS) {
            for (Node node : graph.nodes(kind)) {
                // 선언된 노드와, 분석기가 external을 이미 정한 노드(값이 false여도)는 건드리지 않는다
                if (!node.declared() && !node.attrs().containsKey("external")) {
                    graph.putAttrs(node.id(), Map.of("external", true));
                }
            }
        }
    }
}
