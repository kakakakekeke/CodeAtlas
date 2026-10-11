package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DbObjectNormalizerTest extends LinkTestBase {

    private static final String STMT = "mybatis:stmt:com.a.M.find";

    private String stmtNode() {
        return stmt("com.a.M", "find");
    }

    @Test
    @DisplayName("F-012-AC1 테이블 스텁은 같은 이름의 뷰로 합친다")
    void stubMergesIntoView() {
        stmtNode();
        view("VW_ORDER");
        stubTable("VW_ORDER");
        edge(STMT, EdgeKind.READS, Ids.table("VW_ORDER"));

        run(new DbObjectNormalizer());

        assertThat(only(STMT, EdgeKind.READS).to()).isEqualTo(Ids.view("VW_ORDER"));
        assertThat(only(STMT, EdgeKind.READS).attrs()).doesNotContainKey("viaSynonym");
        assertThat(g.hasNode(Ids.table("VW_ORDER"))).isFalse();
    }

    @Test
    @DisplayName("F-012-AC1 정의된 테이블은 건드리지 않는다")
    void declaredTableUntouched() {
        stmtNode();
        table("TB_ORDER");
        view("TB_ORDER");
        edge(STMT, EdgeKind.READS, Ids.table("TB_ORDER"));

        run(new DbObjectNormalizer());

        assertThat(only(STMT, EdgeKind.READS).to()).isEqualTo(Ids.table("TB_ORDER"));
    }

    @Test
    @DisplayName("F-012-AC2 연쇄 시노님은 최종 대상으로 옮기고 viaSynonym을 남긴다")
    void synonymChain() {
        stmtNode();
        table("TB_ORDER");
        stubTable("S1");
        stubTable("S2");
        String s1 = synonym("S1", Ids.table("S2"));
        synonym("S2", Ids.table("TB_ORDER"));
        Edge writes = g.addEdge(Edge.builder(STMT, Ids.table("S1"), EdgeKind.WRITES).location(loc()).attr("op", "UPDATE").build());

        run(new DbObjectNormalizer());

        Edge moved = only(STMT, EdgeKind.WRITES);
        assertThat(moved.to()).isEqualTo(Ids.table("TB_ORDER"));
        assertThat(moved.attrString("viaSynonym")).isEqualTo("S1");
        assertThat(moved.attrString("op")).isEqualTo("UPDATE");
        assertThat(moved.location()).isEqualTo(writes.location());
        assertThat(g.hasNode(Ids.table("S1"))).isFalse();
        assertThat(g.hasNode(Ids.table("S2"))).isFalse();
        assertThat(only(s1, EdgeKind.ALIAS_OF).to()).isEqualTo(Ids.table("TB_ORDER"));
        assertThat(g.hasNode(s1)).isTrue();
    }

    @Test
    @DisplayName("F-012-AC2 시노님의 대상이 뷰면 뷰로 옮긴다")
    void synonymToView() {
        stmtNode();
        view("VW_STOCK");
        String syn = synonym("SYN_STOCK", Ids.table("VW_STOCK"));
        g.addEdge(Edge.of(STMT, Ids.table("SYN_STOCK"), EdgeKind.READS));   // 스텁 노드 없이 엣지만

        run(new DbObjectNormalizer());

        assertThat(only(STMT, EdgeKind.READS).to()).isEqualTo(Ids.view("VW_STOCK"));
        assertThat(only(STMT, EdgeKind.READS).attrString("viaSynonym")).isEqualTo("SYN_STOCK");
        assertThat(only(syn, EdgeKind.ALIAS_OF).to()).isEqualTo(Ids.view("VW_STOCK"));
    }

    @Test
    @DisplayName("F-012-AC2 시노님의 대상이 정의돼 있지 않아도 대상 스텁으로 옮긴다")
    void synonymToUndeclaredTarget() {
        stmtNode();
        stubTable("REMOTE_T");
        stubTable("SYN_R");
        synonym("SYN_R", Ids.table("REMOTE_T"));
        edge(STMT, EdgeKind.READS, Ids.table("SYN_R"));

        run(new DbObjectNormalizer());

        assertThat(only(STMT, EdgeKind.READS).to()).isEqualTo(Ids.table("REMOTE_T"));
        assertThat(g.node(Ids.table("REMOTE_T")).orElseThrow().declared()).isFalse();
        assertThat(g.hasNode(Ids.table("SYN_R"))).isFalse();
    }

    @Test
    @DisplayName("F-012-AC2 테이블과 이름이 같은 시노님은 순환으로 보지 않는다")
    void sameNameSynonym() {
        stmtNode();
        stubTable("TB_CODE");
        synonym("TB_CODE", Ids.table("TB_CODE"));
        edge(STMT, EdgeKind.READS, Ids.table("TB_CODE"));

        run(new DbObjectNormalizer());

        assertThat(only(STMT, EdgeKind.READS).to()).isEqualTo(Ids.table("TB_CODE"));
        assertThat(only(STMT, EdgeKind.READS).attrs()).doesNotContainKey("viaSynonym");
        assertThat(diagnostics("SYNONYM_CYCLE")).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC3 순환 시노님은 스텁을 그대로 두고 진단을 남긴다")
    void synonymCycle() {
        stmtNode();
        stubTable("A");
        stubTable("B");
        synonym("A", Ids.table("B"));
        synonym("B", Ids.table("A"));
        edge(STMT, EdgeKind.READS, Ids.table("A"));

        run(new DbObjectNormalizer());

        assertThat(only(STMT, EdgeKind.READS).to()).isEqualTo(Ids.table("A"));
        assertThat(g.hasNode(Ids.table("A"))).isTrue();
        assertThat(diagnostics("SYNONYM_CYCLE")).isNotEmpty().allMatch(d -> d.severity() == Severity.WARN);
    }

    @Test
    @DisplayName("F-012-AC4 ALIAS_OF의 대상을 정의된 패키지·시퀀스·루틴으로 맞춘다")
    void aliasTargetNormalized() {
        g.declare(Node.of(Ids.dbPackage("PKG_ORDER"), NodeKind.DB_PACKAGE, "PKG_ORDER"));
        g.declare(Node.of(Ids.sequence("SEQ_ORDER"), NodeKind.DB_SEQUENCE, "SEQ_ORDER"));
        routine("FN_CODE");
        stubTable("PKG_ORDER");
        stubTable("SEQ_ORDER");
        String s1 = synonym("SYN_PKG", Ids.table("PKG_ORDER"));
        String s2 = synonym("SYN_SEQ", Ids.table("SEQ_ORDER"));
        String s3 = synonym("SYN_FN", Ids.table("FN_CODE"));   // 대상 노드 없음

        run(new DbObjectNormalizer());

        assertThat(only(s1, EdgeKind.ALIAS_OF).to()).isEqualTo(Ids.dbPackage("PKG_ORDER"));
        assertThat(only(s2, EdgeKind.ALIAS_OF).to()).isEqualTo(Ids.sequence("SEQ_ORDER"));
        assertThat(only(s3, EdgeKind.ALIAS_OF).to()).isEqualTo(Ids.routine("FN_CODE"));
        assertThat(g.hasNode(Ids.table("PKG_ORDER"))).isFalse();
        assertThat(g.hasNode(Ids.table("FN_CODE"))).isFalse();
    }

    @Test
    @DisplayName("F-012-AC4 구문이 읽는 테이블 스텁은 이름이 같은 시퀀스로 합치지 않는다")
    void readStubNotMergedIntoSequence() {
        stmtNode();
        g.declare(Node.of(Ids.sequence("X"), NodeKind.DB_SEQUENCE, "X"));
        stubTable("X");
        edge(STMT, EdgeKind.READS, Ids.table("X"));

        run(new DbObjectNormalizer());

        assertThat(only(STMT, EdgeKind.READS).to()).isEqualTo(Ids.table("X"));
    }

    @Test
    @DisplayName("F-012-AC5 시노님 경유 컬럼 참조와 조인도 대상 테이블로 옮긴다")
    void synonymColumnsAndJoins() {
        stmtNode();
        table("TB_ORDER");
        column("TB_ORDER", "ORDER_ID", "NUMBER");
        stubTable("SYN_ORDER");
        synonym("SYN_ORDER", Ids.table("TB_ORDER"));
        g.reference(Ids.column("SYN_ORDER", "ORDER_ID"), NodeKind.DB_COLUMN, "ORDER_ID");
        g.reference(Ids.column("SYN_ORDER", "ETC"), NodeKind.DB_COLUMN, "ETC");
        g.addEdge(Edge.of(Ids.table("SYN_ORDER"), Ids.column("SYN_ORDER", "ORDER_ID"), EdgeKind.CONTAINS));
        edge(STMT, EdgeKind.READS, Ids.table("SYN_ORDER"));
        edge(STMT, EdgeKind.READS, Ids.column("SYN_ORDER", "ORDER_ID"));
        edge(STMT, EdgeKind.READS, Ids.column("SYN_ORDER", "ETC"));
        joins(Ids.table("SYN_ORDER"), Ids.table("TB_ORDER"), List.of("PARENT_ID"), List.of("ORDER_ID"), STMT);

        run(new DbObjectNormalizer());

        List<String> targets = out(STMT, EdgeKind.READS).stream().map(Edge::to).toList();
        assertThat(targets).containsExactlyInAnyOrder(
                Ids.table("TB_ORDER"), Ids.column("TB_ORDER", "ORDER_ID"), Ids.column("TB_ORDER", "ETC"));
        assertThat(out(STMT, EdgeKind.READS)).allMatch(e -> "SYN_ORDER".equals(e.attrString("viaSynonym")));
        assertThat(g.node(Ids.column("TB_ORDER", "ETC")).orElseThrow().kind()).isEqualTo(NodeKind.DB_COLUMN);
        assertThat(g.hasNode(Ids.column("SYN_ORDER", "ORDER_ID"))).isFalse();
        Edge join = only(Ids.table("TB_ORDER"), EdgeKind.JOINS);
        assertThat(join.to()).isEqualTo(Ids.table("TB_ORDER"));
        assertThat(join.attrString("viaSynonym")).isEqualTo("SYN_ORDER");
        assertThat(join.attrList("leftColumns")).containsExactly("PARENT_ID");
    }

    @Test
    @DisplayName("F-012-AC6 스키마·대소문자·패키지 시노님만 다른 루틴 참조를 정의된 루틴으로 합친다")
    void routineReferencesMerged() {
        String defined = routine("PKG_ORDER.CANCEL");
        g.declare(Node.of(Ids.dbPackage("PKG_ORDER"), NodeKind.DB_PACKAGE, "PKG_ORDER"));
        synonym("SYN_PKG", Ids.table("PKG_ORDER"));
        stubRoutine("SHOP.PKG_ORDER.CANCEL");
        stubRoutine("pkg_order.cancel");
        stubRoutine("SYN_PKG.CANCEL");
        String m = "java:method:com.a.Dao#a()";
        g.declare(Node.of(m, NodeKind.JAVA_METHOD, "a"));
        edge("mybatis:stmt:x.a", EdgeKind.CALLS, "db:routine:SHOP.PKG_ORDER.CANCEL");
        edge(m, EdgeKind.EXECUTES, "db:routine:pkg_order.cancel");
        edge("mybatis:stmt:x.c", EdgeKind.CALLS, "db:routine:SYN_PKG.CANCEL");
        g.addEdge(Edge.of("mybatis:stmt:x.d", "db:routine:SHOP.SYN_PKG.CANCEL", EdgeKind.CALLS));   // 노드 없는 대상

        run(new DbObjectNormalizer());

        assertThat(only("mybatis:stmt:x.a", EdgeKind.CALLS).to()).isEqualTo(defined);
        assertThat(only("mybatis:stmt:x.a", EdgeKind.CALLS).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(only(m, EdgeKind.EXECUTES).to()).isEqualTo(defined);
        assertThat(only("mybatis:stmt:x.c", EdgeKind.CALLS).to()).isEqualTo(defined);
        assertThat(only("mybatis:stmt:x.c", EdgeKind.CALLS).attrString("viaSynonym")).isEqualTo("SYN_PKG");
        assertThat(only("mybatis:stmt:x.d", EdgeKind.CALLS).to()).isEqualTo(defined);
        assertThat(g.nodes(NodeKind.DB_ROUTINE)).extracting(Node::id).containsExactly(defined);
        assertThat(diagnostics("UNDEFINED_ROUTINE")).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC6 루틴 시노님(단일 이름)도 정의된 루틴으로 합친다")
    void routineSynonym() {
        String defined = routine("SP_DAILY");
        synonym("SYN_SP", Ids.table("SP_DAILY"));
        stubRoutine("SYN_SP");
        edge("mybatis:stmt:x.a", EdgeKind.CALLS, "db:routine:SYN_SP");

        run(new DbObjectNormalizer());

        assertThat(only("mybatis:stmt:x.a", EdgeKind.CALLS).to()).isEqualTo(defined);
        assertThat(only("mybatis:stmt:x.a", EdgeKind.CALLS).attrString("viaSynonym")).isEqualTo("SYN_SP");
    }

    @Test
    @DisplayName("F-012-AC7 알려진 스키마가 붙은 독립 루틴 참조는 신뢰도를 유지하고 합친다")
    void knownSchemaQualifiedRoutine() {
        String defined = routine("SP_X");
        g.declare(Node.builder(Ids.table("T"), NodeKind.DB_TABLE).attr("schema", "SHOP").build());
        stubRoutine("SHOP.SP_X");
        edge("mybatis:stmt:x.a", EdgeKind.CALLS, "db:routine:SHOP.SP_X");

        run(new DbObjectNormalizer());

        Edge e = only("mybatis:stmt:x.a", EdgeKind.CALLS);
        assertThat(e.to()).isEqualTo(defined);
        assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(e.attrs()).doesNotContainKey("strippedQualifier");
    }

    @Test
    @DisplayName("F-012-AC7 알 수 없는 한정자가 붙은 독립 루틴 참조는 INFERRED로 합친다")
    void unknownQualifierRoutine() {
        String defined = routine("SP_X");
        stubRoutine("WHO.SP_X");
        edge("mybatis:stmt:x.a", EdgeKind.CALLS, "db:routine:WHO.SP_X");

        run(new DbObjectNormalizer());

        Edge e = only("mybatis:stmt:x.a", EdgeKind.CALLS);
        assertThat(e.to()).isEqualTo(defined);
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(e.attrString("strippedQualifier")).isEqualTo("WHO");
    }

    @Test
    @DisplayName("F-012-AC7 한정자가 정의된 패키지면 독립 루틴으로 합치지 않는다")
    void packageQualifierNotStripped() {
        routine("SAVE");
        routine("PKG_A.LOAD");
        stubRoutine("PKG_A.SAVE");
        edge("mybatis:stmt:x.a", EdgeKind.CALLS, "db:routine:PKG_A.SAVE");

        run(new DbObjectNormalizer());

        assertThat(only("mybatis:stmt:x.a", EdgeKind.CALLS).to()).isEqualTo("db:routine:PKG_A.SAVE");
        assertThat(diagnostics("UNDEFINED_ROUTINE")).hasSize(1);
    }

    @Test
    @DisplayName("F-012-AC8 정의를 못 찾은 테이블·루틴 스텁은 declared=false로 남고 루틴은 INFO 진단 1건")
    void undefinedStubsStay() {
        stmtNode();
        stubTable("GHOST");
        edge(STMT, EdgeKind.READS, Ids.table("GHOST"));
        g.addEdge(Edge.of(STMT, Ids.table("GHOST2"), EdgeKind.READS));            // 노드 없는 대상
        g.addEdge(Edge.of(STMT, "db:routine:NOPE.RUN", EdgeKind.CALLS, loc()));   // 노드 없는 대상
        g.addEdge(Edge.of("mybatis:stmt:com.a.M.other", "db:routine:NOPE.RUN", EdgeKind.CALLS, loc()));
        stmt("com.a.M", "other");

        run(new DbObjectNormalizer());
        g.resolveDanglingEdges();

        for (String id : List.of(Ids.table("GHOST"), Ids.table("GHOST2"))) {
            Node n = g.node(id).orElseThrow();
            assertThat(n.kind()).isEqualTo(NodeKind.DB_TABLE);
            assertThat(n.declared()).isFalse();
        }
        Node r = g.node("db:routine:NOPE.RUN").orElseThrow();
        assertThat(r.kind()).isEqualTo(NodeKind.DB_ROUTINE);
        assertThat(r.declared()).isFalse();
        assertThat(g.edges(EdgeKind.CALLS)).allMatch(e -> e.confidence() == Confidence.RESOLVED);
        assertThat(diagnostics("UNDEFINED_ROUTINE")).hasSize(1)
                .allMatch(d -> d.severity() == Severity.INFO && d.message().contains("NOPE.RUN"));
    }
}
