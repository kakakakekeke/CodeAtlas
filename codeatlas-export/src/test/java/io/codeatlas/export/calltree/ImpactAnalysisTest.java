package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.ShopGraph;
import io.codeatlas.export.fixture.TriggerShop;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImpactAnalysisTest {

    @Test
    @DisplayName("F-015-AC11 테이블 영향도: 구문 → 실행 주체 → 호출 사슬 → 엔드포인트 → 화면 → 배치를 계층별로 모은다")
    void tableImpact() {
        ShopGraph shop = new ShopGraph();
        // list.jsp를 그리는 컨트롤러 메서드와 그 엔드포인트(테이블과는 무관하다)
        String listPage = shop.f.method(ShopGraph.CONTROLLER, "listPage");
        shop.f.edge(listPage, shop.listJsp, EdgeKind.RENDERS);
        String listEndpoint = shop.f.endpoint("GET", "/orders/list", listPage);
        GraphModel g = shop.f.graph();

        ImpactReport report = ImpactAnalysis.analyze(g, Ids.table("ORDERS"));

        assertThat(report.targetId()).isEqualTo(Ids.table("ORDERS"));
        assertThat(report.statements()).extracting(Item::id)
                .containsExactly(shop.stmtInsert, shop.stmtSelect, shop.stmtUpdate, shop.procRead);
        assertThat(report.statements()).extracting(Item::access).containsExactly("C", "R", "RU", "R");
        assertThat(report.tableLevelStatements()).isEmpty();
        assertThat(report.executors()).extracting(Item::id)
                .containsExactly(shop.procedure, shop.mapperInsert, shop.mapperSelect, shop.mapperUpdate);
        assertThat(report.callers()).extracting(Item::id).contains(shop.implCreate, shop.implFind, shop.legacyFind,
                shop.controllerCreate, shop.controllerDetail, shop.taskletExecute, Ids.javaType(ShopGraph.TASKLET));
        assertThat(report.endpoints()).extracting(Item::id).containsExactly(shop.getOrder, shop.postOrder);
        assertThat(report.screens()).extracting(Item::id).contains(shop.listJsp, shop.inlineModule, shop.loadOrder, shop.fetchOrder,
                shop.orderDetailComponent);
        assertThat(report.screens()).extracting(Item::kind).contains(NodeKind.HTTP_REQUEST);
        assertThat(report.batch()).extracting(Item::id).containsExactly(shop.job, shop.sumStep);
        // 뷰를 그리는 컨트롤러 쪽으로는 화면에서 더 거슬러 가지 않는다
        assertThat(report.all()).extracting(Item::id).doesNotContain(listPage, listEndpoint);

        Item post = report.endpoints().get(1);
        assertThat(post.distance()).isEqualTo(4);
        assertThat(post.path()).containsExactly(shop.postOrder, shop.controllerCreate, shop.implCreate, shop.mapperInsert, shop.stmtInsert);
        assertThat(post.tableLevelOnly()).isFalse();
    }

    @Test
    @DisplayName("F-015-AC11 영향도 리포트를 Markdown 표와 JSON으로 낸다")
    void rendersReport() {
        ShopGraph shop = new ShopGraph();
        ImpactReport report = ImpactAnalysis.analyze(shop.f.graph(), Ids.table("ORDERS"));

        String md = report.toMarkdown();
        assertThat(md).startsWith("# 영향도: ORDERS\n");
        assertThat(md).contains("- 대상: `db:table:ORDERS` (테이블)");
        assertThat(md).contains("## 구문 (4)").contains("## 실행하는 메서드·루틴·배치 스텝 (4)").contains("## 호출 사슬")
                .contains("## 엔드포인트 (2)").contains("## 화면").contains("## 배치 (2)");
        assertThat(md).containsPattern("\\| `OrderMapper\\.updateStatus <UPDATE>` \\| RU \\| `src/main/resources/mapper/OrderMapper\\.xml:\\d+` \\|");
        assertThat(md).contains("| `POST /orders` | endpoint |");
        assertThat(md).contains("`[endpoint] POST /orders` → `OrderController#create(OrderDto)` → `OrderServiceImpl#create(OrderDto)` → "
                + "`OrderMapper#insertOrder(OrderDto)` → `[sql] OrderMapper.insertOrder <INSERT>`");

        String json = report.toJson();
        assertThat(json).contains("\"target\": {").contains("\"id\": \"db:table:ORDERS\"").contains("\"statements\": [")
                .contains("\"access\": \"RU\"").contains("\"endpoints\": [").contains("\"screens\": [").contains("\"batch\": [")
                .contains("\"tableLevelOnly\": false");
    }

    @Test
    @DisplayName("F-015-AC11 트리거가 쓰는 테이블의 영향도는 트리거가 걸린 테이블을 쓰는 구문으로 이어진다")
    void triggerChain() {
        ShopGraph shop = new ShopGraph();

        ImpactReport report = ImpactAnalysis.analyze(shop.f.graph(), Ids.table("ORDER_HIST"));

        assertThat(report.statements()).extracting(Item::id).containsExactly(shop.triggerStmt);
        assertThat(report.executors()).extracting(Item::id).containsExactly(shop.trigger);
        assertThat(report.callers()).extracting(Item::id).contains(Ids.table("ORDERS"), shop.stmtInsert, shop.stmtUpdate)
                .doesNotContain(shop.stmtSelect);
        assertThat(report.endpoints()).extracting(Item::id).containsExactly(shop.postOrder);
    }

    @Test
    @DisplayName("F-015-AC23 트리거가 쓰는 테이블의 영향도는 그 트리거를 일으키는 구문으로만 이어진다 (한 테이블에 트리거가 여럿이면 트리거마다 따로)")
    void triggerChainRespectsEvent() {
        TriggerShop shop = new TriggerShop();
        GraphModel g = shop.f.graph();

        // TB_ORDER_HIST는 AFTER UPDATE OF ORDER_STATUS 트리거만 쓴다: INSERT만 하는 POST /order/save는 영향 범위가 아니다
        ImpactReport hist = ImpactAnalysis.analyze(g, "TB_ORDER_HIST");
        assertThat(hist.executors()).extracting(Item::id).containsExactly(shop.statusTrigger);
        assertThat(hist.callers()).extracting(Item::id).contains(Ids.table("TB_ORDER"), shop.updateOrderStatus).doesNotContain(shop.insertOrder);
        assertThat(hist.endpoints()).extracting(Item::id).containsExactly(shop.statusEndpoint);

        // TB_EVENT는 두 트리거가 모두 쓴다: 두 엔드포인트가 각자의 트리거를 거쳐 닿는다
        ImpactReport event = ImpactAnalysis.analyze(g, "TB_EVENT");
        assertThat(event.executors()).extracting(Item::id).containsExactly(shop.newTrigger, shop.statusTrigger);
        assertThat(event.endpoints()).extracting(Item::id).containsExactly(shop.saveEndpoint, shop.statusEndpoint);
        Item save = event.endpoints().get(0);
        assertThat(save.path()).contains(shop.insertOrder, Ids.table("TB_ORDER"), shop.newTrigger).doesNotContain(shop.statusTrigger);
        Item status = event.endpoints().get(1);
        assertThat(status.path()).contains(shop.updateOrderStatus, Ids.table("TB_ORDER"), shop.statusTrigger).doesNotContain(shop.newTrigger);
        assertThat(event.all()).extracting(Item::id).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("F-015-AC26 테이블 영향도의 R은 테이블 수준 READS가 있을 때만, 컬럼 영향도는 컬럼 엣지 그대로")
    void tableImpactIgnoresColumnReads() {
        GraphFixture f = GraphFixture.create();
        f.table("T");
        f.column("T", "ID", "NUMBER", 1);
        f.column("T", "NAME", "VARCHAR2(10)", 2);
        String del = f.statement("com.r.M", "del", "DELETE");
        f.writes(del, "T", "DELETE").readsColumn(del, "T", "ID");
        String upd = f.statement("com.r.M", "upd", "UPDATE");
        f.writes(upd, "T", "UPDATE", "NAME").readsColumn(upd, "T", "ID");

        ImpactReport table = ImpactAnalysis.analyze(f.graph(), "T");
        assertThat(table.statements()).extracting(Item::id).containsExactly(del, upd);
        assertThat(table.statements()).extracting(Item::access).containsExactly("D", "U");

        // 컬럼에서 시작하면 그 컬럼의 엣지를 쓴다: 조건절에서 읽는 ID는 R, 갱신하는 NAME은 U
        assertThat(ImpactAnalysis.analyze(f.graph(), "T.ID").statements()).extracting(Item::access).containsExactly("R", "R");
        ImpactReport name = ImpactAnalysis.analyze(f.graph(), "T.NAME");
        assertThat(name.statements()).extracting(Item::id).containsExactly(upd);
        assertThat(name.statements().get(0).access()).isEqualTo("U");
    }

    @Test
    @DisplayName("F-015-AC31 영향도 리포트는 대상 테이블을 정적으로 알 수 없는 동적 구문을 별도 절로 알린다")
    void reportsDynamicStatements() {
        GraphFixture f = GraphFixture.create();
        f.table("TB_ORDER");
        f.column("TB_ORDER", "ORDER_ID", "NUMBER", 1);
        String select = f.statement("com.d.M", "selectOrder", "SELECT");
        f.reads(select, "TB_ORDER", "ORDER_ID");
        String backup = f.statement("com.d.M", "selectBackup", "SELECT");
        f.dynamicAccess(backup, EdgeKind.READS, null, "__DYNAMIC__");
        String purge = f.routineStatement(f.routine("SP_PURGE", "PROCEDURE"), 42, "DELETE");
        f.dynamicAccess(purge, EdgeKind.WRITES, "DELETE", "DELETE FROM {*} WHERE REG_TS < ?");

        ImpactReport report = ImpactAnalysis.analyze(f.graph(), "TB_ORDER");

        // 동적 구문은 이 테이블의 구문으로 세지 않는다(추측하지 않는다). 다만 빠뜨리지 않고 따로 적는다
        assertThat(report.statements()).extracting(Item::id).containsExactly(select);
        assertThat(report.dynamicStatements()).extracting(DynamicStatement::id).containsExactly(backup, purge);
        assertThat(report.dynamicStatements()).extracting(DynamicStatement::access).containsExactly("R", "D");
        String md = report.toMarkdown();
        assertThat(md).contains("- 대상 테이블을 정적으로 알 수 없는 구문 2개(아래 별도 절)")
                .contains("\n## 대상 테이블을 정적으로 알 수 없는 구문 (2)\n")
                .contains("| `SP_PURGE:42 <DELETE>` | D | `db/sp_purge.prc:42` |\n")
                .containsPattern("\\| `M\\.selectBackup <SELECT>` \\| R \\| `src/main/resources/mapper/M\\.xml:\\d+` \\|");
        assertThat(report.toJson()).contains("\"dynamicStatements\": [").contains("\"id\": \"sql:stmt:SP_PURGE:42\"")
                .contains("\"text\": \"DELETE FROM {*} WHERE REG_TS < ?\"");
        // 컬럼 대상에도 같다
        assertThat(ImpactAnalysis.analyze(f.graph(), "TB_ORDER.ORDER_ID").dynamicStatements()).hasSize(2);

        // 동적 구문이 없으면 절이 없고, 대상을 찾지 못한 리포트에도 없다
        ImpactReport plain = ImpactAnalysis.analyze(new ShopGraph().f.graph(), "ORDERS");
        assertThat(plain.dynamicStatements()).isEmpty();
        assertThat(plain.toMarkdown()).doesNotContain("정적으로 알 수 없는");
        assertThat(plain.toJson()).contains("\"dynamicStatements\": []");
        assertThat(ImpactAnalysis.analyze(f.graph(), "NOPE").dynamicStatements()).isEmpty();
    }

    @Test
    @DisplayName("F-015-AC12 컬럼 영향도: 컬럼 엣지가 있는 구문만 확정, 컬럼 미해석 구문은 테이블 수준에서만 확인됨으로 따로")
    void columnImpact() {
        GraphFixture f = GraphFixture.create();
        f.table("T");
        f.column("T", "X", "NUMBER", 1);
        f.column("T", "Y", "NUMBER", 2);
        f.type("com.a.M", "interface", "public");
        String a = f.statement("com.a.M", "a", "UPDATE");
        String b = f.statement("com.a.M", "b", "SELECT");
        String c = f.statement("com.a.M", "c", "SELECT");
        f.writes(a, "T", "UPDATE", "X");
        f.reads(b, "T", "Y");
        f.reads(c, "T");
        String ma = f.method("com.a.M", "a");
        String mb = f.method("com.a.M", "b");
        String mc = f.method("com.a.M", "c");
        f.edge(ma, a, EdgeKind.EXECUTES);
        f.edge(mb, b, EdgeKind.EXECUTES);
        f.edge(mc, c, EdgeKind.EXECUTES);
        f.type("com.a.S", "class", "public");
        String both = f.method("com.a.S", "both");
        String onlyC = f.method("com.a.S", "onlyC");
        f.calls(both, ma);
        f.calls(both, mc);
        f.calls(onlyC, mc);

        ImpactReport report = ImpactAnalysis.analyze(f.graph(), Ids.column("T", "X"));

        assertThat(report.statements()).extracting(Item::id).containsExactly(a);
        assertThat(report.statements().get(0).access()).isEqualTo("U");
        assertThat(report.tableLevelStatements()).extracting(Item::id).containsExactly(c);
        assertThat(report.tableLevelStatements().get(0).tableLevelOnly()).isTrue();
        assertThat(report.executors()).extracting(Item::id).containsExactly(ma, mc);
        assertThat(report.executors()).extracting(Item::tableLevelOnly).containsExactly(false, true);
        assertThat(report.callers()).extracting(Item::id).containsExactly(both, onlyC);
        assertThat(report.callers()).extracting(Item::tableLevelOnly).containsExactly(false, true);
        assertThat(report.all()).extracting(Item::id).doesNotContain(b, mb);

        String md = report.toMarkdown();
        assertThat(md).startsWith("# 영향도: T.X\n");
        assertThat(md).contains("- 대상: `db:column:T.X` (컬럼, 테이블 `T`)");
        assertThat(md).contains("### 테이블 수준에서만 확인됨 (1)");
        assertThat(md).contains("| `M#c()` | method |").contains("테이블 수준");

        // 이름으로도 찾는다
        assertThat(ImpactAnalysis.analyze(f.graph(), "T.X").targetId()).isEqualTo(Ids.column("T", "X"));
        assertThat(ImpactAnalysis.analyze(f.graph(), "t").statements()).extracting(Item::id).containsExactly(a, b, c);
        // 없는 대상은 빈 리포트
        assertThat(ImpactAnalysis.analyze(f.graph(), "NOPE").all()).isEmpty();
        assertThat(ImpactAnalysis.analyze(f.graph(), "NOPE").toMarkdown()).contains("찾지 못했다");
    }

    @Test
    @DisplayName("F-015-AC21 대상을 찾지 못하면 이름이 비슷한 테이블·뷰·컬럼 후보를 리포트에 담는다")
    void suggestsSimilarNames() {
        GraphFixture f = GraphFixture.create();
        f.table("TB_ORDER");
        f.column("TB_ORDER", "ORDER_STATUS", "VARCHAR2(10)", 1);
        f.table("TB_ORDER_ITEM");
        f.view("V_ORDER_SUMMARY");
        f.type("com.a.OrderService", "class", "public");
        String method = f.method("com.a.OrderService", "order");
        GraphModel g = f.graph();

        ImpactReport report = ImpactAnalysis.analyze(g, "order");

        assertThat(report.found()).isFalse();
        assertThat(report.all()).isEmpty();
        // NodeFinder와 같은 규칙으로 찾되 테이블·뷰·컬럼만이다(메서드 order()는 후보가 아니다)
        assertThat(report.suggestions()).extracting(ImpactReport.Suggestion::id).containsExactly(
                "db:column:TB_ORDER.ORDER_STATUS", "db:table:TB_ORDER", "db:table:TB_ORDER_ITEM", "db:view:V_ORDER_SUMMARY");
        assertThat(report.suggestions().get(1).kind()).isEqualTo(NodeKind.DB_TABLE);
        assertThat(report.suggestions().get(1).label()).isEqualTo("TB_ORDER");
        assertThat(report.suggestions().get(1).location()).startsWith("db/tables.sql:");
        assertThat(report.toMarkdown()).contains("찾지 못했다").contains("비슷한 이름 (4)")
                .contains("- `TB_ORDER` (테이블, `db:table:TB_ORDER`)").contains("- `TB_ORDER.ORDER_STATUS` (컬럼, `db:column:TB_ORDER.ORDER_STATUS`)")
                .contains("- `V_ORDER_SUMMARY` (뷰, `db:view:V_ORDER_SUMMARY`)");
        assertThat(report.toJson()).contains("\"found\": false").contains("\"suggestions\": [").contains("\"id\": \"db:table:TB_ORDER_ITEM\"");

        // 비슷한 이름이 없으면 비어 있다. DB 노드가 아닌 노드 ID를 줘도 후보는 DB 노드뿐이다
        assertThat(ImpactAnalysis.analyze(g, "ZZZ").suggestions()).isEmpty();
        assertThat(ImpactAnalysis.analyze(g, "ZZZ").toMarkdown()).doesNotContain("비슷한 이름");
        assertThat(ImpactAnalysis.analyze(g, method).found()).isFalse();
        assertThat(ImpactAnalysis.analyze(g, method).suggestions()).isEmpty();
        assertThat(ImpactAnalysis.analyze(new GraphModel(), "ORDERS").suggestions()).isEmpty();
        // 찾은 대상의 리포트에는 후보가 없다
        assertThat(ImpactAnalysis.analyze(g, "tb_order").found()).isTrue();
        assertThat(ImpactAnalysis.analyze(g, "tb_order").suggestions()).isEmpty();
        assertThat(ImpactAnalysis.analyze(g, "tb_order").toJson()).contains("\"suggestions\": []");
    }
}
