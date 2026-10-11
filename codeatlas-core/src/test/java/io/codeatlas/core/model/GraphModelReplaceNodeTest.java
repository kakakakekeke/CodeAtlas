package io.codeatlas.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 링커(F-012)가 참조 스텁을 뷰·시노님 대상 노드로 합칠 때 쓰는 replaceNode. */
class GraphModelReplaceNodeTest {

    private static final String STUB = "db:table:V_ORDERS";
    private static final String VIEW = "db:view:V_ORDERS";
    private static final String STMT = "mybatis:stmt:ns.a";
    private static final String OTHER = "db:table:CUSTOMER";
    private static final SourceLocation LOC = SourceLocation.of("m.xml", 3);

    private GraphModel graph;

    @BeforeEach
    void setUp() {
        graph = new GraphModel();
        graph.reference(STUB, NodeKind.DB_TABLE, "V_ORDERS");
        graph.declare(Node.builder(VIEW, NodeKind.DB_VIEW).name("V_ORDERS").attr("schema", "HR").build());
        graph.declare(Node.of(STMT, NodeKind.SQL_STATEMENT, "a"));
        graph.reference(OTHER, NodeKind.DB_TABLE, "CUSTOMER");
    }

    @Test
    @DisplayName("F-001-AC20 replaceNode는 들어오고 나가는 엣지를 새 ID로 옮기고 옛 노드를 지운다")
    void movesEdges() {
        graph.addEdge(Edge.builder(STMT, STUB, EdgeKind.READS).location(LOC).inferred().attr("via", "alias").build());
        graph.addEdge(Edge.builder(STUB, OTHER, EdgeKind.JOINS).attr("statement", STMT).build());

        assertThat(graph.replaceNode(STUB, VIEW)).isTrue();

        assertThat(graph.node(STUB)).isEmpty();
        assertThat(graph.node(VIEW).orElseThrow().attrs()).containsEntry("schema", "HR").containsEntry("declared", true);
        assertThat(graph.outgoing(STUB)).isEmpty();
        assertThat(graph.incoming(STUB)).isEmpty();
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.incoming(VIEW)).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo(STMT);
            assertThat(e.kind()).isEqualTo(EdgeKind.READS);
            assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(e.location()).isEqualTo(LOC);
            assertThat(e.attrs()).containsEntry("via", "alias");
        });
        assertThat(graph.outgoing(STMT)).extracting(Edge::to).containsExactly(VIEW);
        assertThat(graph.outgoing(VIEW)).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(OTHER);
            assertThat(e.attrs()).containsEntry("statement", STMT);
        });
        assertThat(graph.incoming(OTHER)).extracting(Edge::from).containsExactly(VIEW);
        assertThat(graph.edges(STMT, EdgeKind.READS, VIEW)).hasSize(1);
        assertThat(graph.resolveDanglingEdges()).isZero();
    }

    @Test
    @DisplayName("F-001-AC20 replaceNode로 옮긴 엣지가 이미 있는 엣지와 겹치면 기존 것 하나만 남는다")
    void deduplicates() {
        Edge existing = graph.addEdge(Edge.builder(STMT, VIEW, EdgeKind.READS).location(LOC).attr("keep", true).build());
        graph.addEdge(Edge.builder(STMT, STUB, EdgeKind.READS).location(LOC).attr("keep", false).build());
        graph.addEdge(Edge.builder(STMT, STUB, EdgeKind.READS).location(SourceLocation.of("m.xml", 9)).build());
        graph.addEdge(Edge.of(STMT, STUB, EdgeKind.WRITES));

        graph.replaceNode(STUB, VIEW);

        assertThat(graph.edges()).hasSize(3);
        assertThat(graph.incoming(VIEW)).hasSize(3).contains(existing);
        assertThat(graph.edges(STMT, EdgeKind.READS, VIEW)).hasSize(2);
        assertThat(graph.edges(STMT, EdgeKind.WRITES, VIEW)).hasSize(1);
    }

    @Test
    @DisplayName("F-001-AC20 replaceNode로 자기 자신을 가리키게 된 엣지는 원래 self-edge였던 것만 남긴다")
    void selfLoops() {
        graph.addEdge(Edge.of(STUB, VIEW, EdgeKind.ALIAS_OF));
        graph.addEdge(Edge.of(VIEW, STUB, EdgeKind.JOINS));
        graph.addEdge(Edge.of(STUB, STUB, EdgeKind.REFERENCES));

        graph.replaceNode(STUB, VIEW);

        assertThat(graph.edges()).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo(VIEW);
            assertThat(e.to()).isEqualTo(VIEW);
            assertThat(e.kind()).isEqualTo(EdgeKind.REFERENCES);
        });
        assertThat(graph.outgoing(VIEW)).hasSize(1);
        assertThat(graph.incoming(VIEW)).hasSize(1);
    }

    @Test
    @DisplayName("F-001-AC20 replaceNode는 옛 노드가 없거나 두 ID가 같으면 아무 일도 하지 않는다")
    void noOp() {
        graph.addEdge(Edge.of(STMT, "db:table:GHOST", EdgeKind.READS));
        graph.addEdge(Edge.of(STMT, STUB, EdgeKind.READS));

        assertThat(graph.replaceNode("db:table:GHOST", VIEW)).isFalse();
        assertThat(graph.replaceNode(STUB, STUB)).isFalse();

        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.edges()).extracting(Edge::to).containsExactly("db:table:GHOST", STUB);
    }

    @Test
    @DisplayName("F-001-AC20 replaceNode의 새 ID 노드가 아직 없어도 엣지는 옮긴다")
    void targetMayBeMissing() {
        graph.addEdge(Edge.of(STMT, STUB, EdgeKind.READS));

        assertThat(graph.replaceNode(STUB, "db:synonym:LATER")).isTrue();

        assertThat(graph.node("db:synonym:LATER")).isEmpty();
        assertThat(graph.outgoing(STMT)).extracting(Edge::to).containsExactly("db:synonym:LATER");
    }
}
