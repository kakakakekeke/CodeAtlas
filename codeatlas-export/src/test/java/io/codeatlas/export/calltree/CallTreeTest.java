package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.CallTreeNode.Marker;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.MermaidSyntax;
import io.codeatlas.export.fixture.ShopGraph;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallTreeTest {

    private static String callees(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callees()), false);
    }

    private static String callers(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callers()), false);
    }

    @Test
    @DisplayName("F-015-AC1 F-015-AC41 엔드포인트에서 테이블까지 callees 트리가 한 줄기로 이어진다 (컬럼 엣지는 테이블로 접는다, 호출은 호출 지점 순)")
    void calleesFromEndpoint() {
        ShopGraph shop = new ShopGraph();

        String text = callees(shop.f.graph(), shop.getOrder);

        assertThat(text).isEqualTo("""
                [endpoint] GET /orders/{id}
                └─ OrderController#detail(Long)
                   ├─ OrderService#find(Long)
                   │  ├─ [impl] LegacyOrderService#find(Long)
                   │  │  └─ OrderMapper#selectOrder(Long)
                   │  │     └─ [sql] OrderMapper.selectOrder <SELECT>
                   │  │        ├─ [table] CUSTOMER (R)
                   │  │        └─ [table] ORDERS (R)
                   │  └─ [impl] OrderServiceImpl#find(Long)
                   │     ├─ OrderMapper#selectOrder(Long) [see above]
                   │     └─ AuditHelper#log(String)
                   │        └─ ~ AuditMapper#insertLog(String)
                   │           └─ [sql] AuditMapper.insertLog <INSERT>
                   │              └─ [table] AUDIT_LOG (C)
                   └─ [jsp] src/main/webapp/WEB-INF/views/order/detail.jsp
                """);
    }

    @Test
    @DisplayName("F-015-AC41 callees의 자식은 이름순이 아니라 호출 지점 위치순(줄, 같은 줄이면 열)이고, 위치 없는 호출은 뒤에 이름순, 구현 전개·테이블 줄은 그 뒤에 ID 순이다")
    void childrenInCallSiteOrder() {
        GraphFixture f = GraphFixture.create();
        f.type("com.o.Api", "interface", "public");
        f.type("com.o.Impl", "class", "public");
        String run = f.method("com.o.Api", "run", List.of(), "void", "public", "abstract");
        String impl = f.method("com.o.Impl", "run");
        f.edge(impl, run, EdgeKind.OVERRIDES);
        String file = "src/main/java/com/o/Impl.java";
        for (String name : List.of("alpha", "beta", "gamma", "delta", "noSiteB", "noSiteA")) {
            f.method("com.o.Helper", name);
        }
        // 소스의 순서: gamma(10:5) → alpha(10:30, 같은 줄) → beta(12) → delta(20), gamma는 30번째 줄에서 또 부른다
        call(f, impl, "delta", SourceLocation.of(file, 20, 9, 20, 14));
        call(f, impl, "alpha", SourceLocation.of(file, 10, 30, 10, 35));
        call(f, impl, "gamma", SourceLocation.of(file, 30, 9, 30, 14));
        call(f, impl, "beta", SourceLocation.of(file, 12, 9, 12, 13));
        call(f, impl, "gamma", SourceLocation.of(file, 10, 5, 10, 10));
        call(f, impl, "noSiteB", null);
        call(f, impl, "noSiteA", null);
        String statement = f.statement("com.o.Mapper", "select", "SELECT");
        f.reads(statement, "ZED").reads(statement, "ABLE");
        f.edge(Edge.builder(impl, statement, EdgeKind.EXECUTES).location(SourceLocation.of(file, 15, 9, 15, 20)).build());
        GraphModel g = f.graph();

        String expected = """
                Api#run()
                └─ [impl] Impl#run()
                   ├─ Helper#gamma()
                   ├─ Helper#alpha()
                   ├─ Helper#beta()
                   ├─ [sql] Mapper.select <SELECT>
                   │  ├─ [table] ABLE (R)
                   │  └─ [table] ZED (R)
                   ├─ Helper#delta()
                   ├─ Helper#noSiteA()
                   └─ Helper#noSiteB()
                """;
        assertThat(callees(g, run)).isEqualTo(expected);
        assertThat(callees(f.reversedCopy(), run)).isEqualTo(expected);
        // callers는 호출자마다 파일이 달라 위치순이 뜻이 없다: 노드 ID 순 그대로다
        call(f, f.method("com.o.Zeta", "go"), "gamma", SourceLocation.of("src/main/java/com/o/Zeta.java", 1));
        call(f, f.method("com.o.Alpha", "go"), "gamma", SourceLocation.of("src/main/java/com/o/Alpha.java", 99));
        assertThat(callers(f.graph(), Ids.javaMethod("com.o.Helper", "gamma"))).isEqualTo("""
                Helper#gamma()
                ├─ Alpha#go()
                ├─ Impl#run()
                └─ Zeta#go()
                """);
    }

    private static void call(GraphFixture f, String from, String helperMethod, SourceLocation at) {
        f.edge(Edge.builder(from, Ids.javaMethod("com.o.Helper", helperMethod), EdgeKind.CALLS).location(at).attr("callType", "virtual").build());
    }

    @Test
    @DisplayName("F-015-AC1 JSP·배치 잡에서 시작하는 callees: 화면 → 요청 → 엔드포인트, 잡 → 스텝 → 타입 → 메서드 → 루틴 (NEXT는 따라가지 않는다)")
    void calleesFromJspAndJob() {
        ShopGraph shop = new ShopGraph();

        String jsp = CallTrees.toText(CallTrees.build(shop.f.graph(), shop.listJsp, CallTreeOptions.builder().maxDepth(4).build()), false);
        assertThat(jsp).startsWith("""
                [jsp] src/main/webapp/WEB-INF/views/order/list.jsp
                ├─ [request] GET /orders/{*}
                │  └─ [endpoint] GET /orders/{id}
                │     └─ OrderController#detail(Long)
                """);
        assertThat(jsp).contains("""
                └─ [module] src/main/webapp/WEB-INF/views/order/list.jsp
                   └─ [js] list.jsp#loadOrder
                      └─ [request] POST /orders
                         └─ [endpoint] POST /orders [depth limit]
                """);

        // 스텝은 이름순이 아니라 NEXT 순서다(sumStep → cleanStep). NEXT 자체는 자식으로 따라가지 않는다.
        assertThat(callees(shop.f.graph(), shop.job)).isEqualTo("""
                [job] dailyJob
                ├─ [step] sumStep
                │  └─ [type] SumTasklet (tasklet)
                │     └─ SumTasklet#execute()
                │        └─ [procedure] SP_DAILY_SUM
                │           ├─ [sql] SP_DAILY_SUM:10 <SELECT>
                │           │  └─ [table] ORDERS (R)
                │           └─ [sql] SP_DAILY_SUM:20 <MERGE>
                │              └─ [table] DAILY_SALES (CU)
                └─ [step] cleanStep
                   └─ [sql] CleanMapper.deleteOld <DELETE>
                      └─ [table] AUDIT_LOG (D)
                """);
    }

    @Test
    @DisplayName("F-015-AC2 테이블에서 callers 트리가 구문 → 메서드 → 엔드포인트 → 요청 → 화면으로 거슬러 올라간다")
    void callersFromTable() {
        ShopGraph shop = new ShopGraph();

        String text = callers(shop.f.graph(), Ids.table("ORDERS"));

        assertThat(text).startsWith("""
                [table] ORDERS
                ├─ [sql] OrderMapper.insertOrder <INSERT> (C)
                │  └─ OrderMapper#insertOrder(OrderDto)
                │     └─ OrderServiceImpl#create(OrderDto)
                │        └─ [via interface] OrderController#create(OrderDto)
                │           └─ [endpoint] POST /orders
                │              └─ [request] POST /orders
                │                 └─ [js] list.jsp#loadOrder
                │                    └─ [module] src/main/webapp/WEB-INF/views/order/list.jsp
                │                       └─ [jsp] src/main/webapp/WEB-INF/views/order/list.jsp
                ├─ [sql] OrderMapper.selectOrder <SELECT> (R)
                """);
        assertThat(text).contains("├─ [sql] OrderMapper.updateStatus <UPDATE> (RU)\n│  └─ OrderMapper#updateStatus(Long, String)\n");
        assertThat(text).contains("""
                └─ [sql] SP_DAILY_SUM:10 <SELECT> (R)
                   └─ [procedure] SP_DAILY_SUM
                      └─ SumTasklet#execute()
                         └─ [type] SumTasklet
                            └─ [step] sumStep (tasklet)
                               └─ [job] dailyJob
                """);
        // 화면 쪽: JS 함수의 호출자와 모듈까지
        assertThat(text).contains("[js] orderApi.js#fetchOrder").contains("[js] OrderDetail.jsx#OrderDetail");
        // 트리거는 테이블의 호출자가 아니다
        assertThat(text).doesNotContain("TRG_ORDER");
    }

    /** Client#run → Svc#go(인터페이스) ⇐ ImplA#go, ImplB#go. */
    private static GraphFixture dispatch() {
        GraphFixture f = GraphFixture.create();
        f.type("com.a.Client", "class", "public");
        f.type("com.a.Svc", "interface", "public");
        f.type("com.a.ImplA", "class", "public");
        f.type("com.a.ImplB", "class", "public");
        f.type("com.a.Dao", "class", "public");
        String run = f.method("com.a.Client", "run");
        String go = f.method("com.a.Svc", "go", List.of(), "void", "public", "abstract");
        String goA = f.method("com.a.ImplA", "go");
        String goB = f.method("com.a.ImplB", "go");
        f.calls(run, go);
        f.edge(goA, go, EdgeKind.OVERRIDES);
        f.edge(goB, go, EdgeKind.OVERRIDES);
        f.calls(goA, f.method("com.a.Dao", "a"));
        f.calls(goB, f.method("com.a.Dao", "b"));
        return f;
    }

    private static final String RUN = Ids.javaMethod("com.a.Client", "run");

    @Test
    @DisplayName("F-015-AC3 인터페이스 메서드 아래에 구현 메서드를 [impl]로 펼친다")
    void expandsImplementations() {
        GraphModel g = dispatch().graph();

        assertThat(callees(g, RUN)).isEqualTo("""
                Client#run()
                └─ Svc#go()
                   ├─ [impl] ImplA#go()
                   │  └─ Dao#a()
                   └─ [impl] ImplB#go()
                      └─ Dao#b()
                """);
        CallTree tree = CallTrees.build(g, RUN, CallTreeOptions.callees());
        assertThat(tree.root().children().get(0).children()).allMatch(n -> n.markers().contains(Marker.IMPL));
        assertThat(CallTrees.toText(CallTrees.build(g, RUN, CallTreeOptions.builder().expandImplementations(false).build()), false))
                .isEqualTo("Client#run()\n└─ Svc#go()\n");
    }

    @Test
    @DisplayName("F-015-AC4 INJECTS의 candidates 또는 resolvedFrom이 가리키는 구현만 펼치고, 옵션으로 끌 수 있다")
    void narrowsByInjection() {
        GraphFixture byCandidates = dispatch();
        byCandidates.edge(Ids.javaType("com.a.Client"), Ids.javaType("com.a.Svc"), EdgeKind.INJECTS,
                Map.of("via", "field", "candidates", List.of("com.a.ImplB")));
        assertThat(callees(byCandidates.graph(), RUN)).isEqualTo("""
                Client#run()
                └─ Svc#go()
                   └─ [impl] ImplB#go()
                      └─ Dao#b()
                """);
        assertThat(CallTrees.toText(CallTrees.build(byCandidates.graph(), RUN,
                CallTreeOptions.builder().narrowByInjection(false).build()), false)).contains("[impl] ImplA#go()", "[impl] ImplB#go()");

        GraphFixture byInferred = dispatch();
        byInferred.edge(Edge.builder(Ids.javaType("com.a.Client"), Ids.javaType("com.a.ImplA"), EdgeKind.INJECTS).inferred()
                .attr("resolvedFrom", Ids.javaType("com.a.Svc")).build());
        assertThat(callees(byInferred.graph(), RUN)).contains("[impl] ImplA#go()").doesNotContain("ImplB");

        // 후보가 구현과 하나도 맞지 않으면 좁히지 않는다
        GraphFixture mismatch = dispatch();
        mismatch.edge(Ids.javaType("com.a.Client"), Ids.javaType("com.a.Svc"), EdgeKind.INJECTS, Map.of("candidates", List.of("com.a.Nothing")));
        assertThat(callees(mismatch.graph(), RUN)).contains("[impl] ImplA#go()", "[impl] ImplB#go()");
    }

    @Test
    @DisplayName("F-015-AC5 구현 메서드의 callers에 상위 메서드의 호출자가 [via interface]로 나온다")
    void callersViaInterface() {
        GraphFixture f = dispatch();
        String goB = Ids.javaMethod("com.a.ImplB", "go");
        String direct = f.method("com.a.Dao", "direct");
        f.calls(direct, goB);

        assertThat(callers(f.graph(), goB)).isEqualTo("""
                ImplB#go()
                ├─ [via interface] Client#run()
                └─ Dao#direct()
                """);

        // 주입 정보가 다른 구현을 가리키면 그 호출자는 뺀다
        f.edge(Ids.javaType("com.a.Client"), Ids.javaType("com.a.Svc"), EdgeKind.INJECTS, Map.of("candidates", List.of("com.a.ImplA")));
        assertThat(callers(f.graph(), goB)).isEqualTo("ImplB#go()\n└─ Dao#direct()\n");
        assertThat(callers(f.graph(), Ids.javaMethod("com.a.ImplA", "go"))).contains("[via interface] Client#run()");
        assertThat(CallTrees.toText(CallTrees.build(f.graph(), goB,
                CallTreeOptions.builder().direction(CallTreeOptions.Direction.CALLERS).narrowByInjection(false).build()), false))
                .contains("[via interface] Client#run()");
    }

    /** a → b → a, a → c → d, b → c. */
    private static GraphFixture cyclic() {
        GraphFixture f = GraphFixture.create();
        f.type("com.a.X", "class", "public");
        String a = f.method("com.a.X", "a");
        String b = f.method("com.a.X", "b");
        String c = f.method("com.a.X", "c");
        String d = f.method("com.a.X", "d");
        f.calls(a, b);
        f.calls(b, a);
        f.calls(a, c);
        f.calls(b, c);
        f.calls(c, d);
        return f;
    }

    private static final String A = Ids.javaMethod("com.a.X", "a");

    @Test
    @DisplayName("F-015-AC6 경로상 재등장은 [cycle], 이미 펼친 노드는 [see above] (옵션으로 끌 수 있다)")
    void cyclesAndRepeats() {
        GraphModel g = cyclic().graph();

        assertThat(callees(g, A)).isEqualTo("""
                X#a()
                ├─ X#b()
                │  ├─ X#a() [cycle]
                │  └─ X#c()
                │     └─ X#d()
                └─ X#c() [see above]
                """);
        assertThat(CallTrees.toText(CallTrees.build(g, A, CallTreeOptions.builder().collapseRepeated(false).build()), false)).isEqualTo("""
                X#a()
                ├─ X#b()
                │  ├─ X#a() [cycle]
                │  └─ X#c()
                │     └─ X#d()
                └─ X#c()
                   └─ X#d()
                """);
    }

    @Test
    @DisplayName("F-015-AC7 깊이 제한은 [depth limit], 노드 수 상한에 닿으면 잘림 표시")
    void limits() {
        GraphModel g = cyclic().graph();

        CallTree shallow = CallTrees.build(g, A, CallTreeOptions.builder().maxDepth(1).build());
        assertThat(CallTrees.toText(shallow, false)).isEqualTo("""
                X#a()
                ├─ X#b() [depth limit]
                └─ X#c() [depth limit]
                """);
        assertThat(shallow.truncated()).isFalse();

        CallTree capped = CallTrees.build(g, A, CallTreeOptions.builder().maxNodes(3).build());
        assertThat(capped.nodeCount()).isEqualTo(3);
        assertThat(capped.truncated()).isTrue();
        assertThat(CallTrees.toText(capped, false)).isEqualTo("""
                X#a()
                └─ X#b()
                   └─ X#a() [cycle]
                ... 노드 수 상한 3개에 닿아 나머지를 생략했다
                """);
        assertThat(CallTrees.toJson(capped)).contains("\"truncated\": true");
    }

    @Test
    @DisplayName("F-015-AC8 신뢰도 표시(~, ?), 소스 위치, external 메서드 숨김")
    void confidenceLocationExternal() {
        ShopGraph shop = new ShopGraph();
        GraphModel g = shop.f.graph();

        String text = CallTrees.toText(CallTrees.build(g, shop.implCreate, CallTreeOptions.callees()));
        assertThat(text.lines().findFirst().orElseThrow())
                .matches("OrderServiceImpl#create\\(OrderDto\\)  src/main/java/com/shop/order/OrderServiceImpl\\.java:\\d+");
        assertThat(text).containsPattern("~ AuditMapper#insertLog\\(String\\)  src/main/java/com/shop/common/AuditMapper\\.java:\\d+");
        // 정의 위치가 없는 external 메서드는 호출 지점을 적는다
        assertThat(text).containsPattern("├─ List#add\\(Object\\)  src/main/java/com/shop/order/OrderServiceImpl\\.java:\\d+\n");
        assertThat(text).contains("? [unresolved] helper.unknown()  src/main/java/com/shop/order/OrderServiceImpl.java:77\n");
        // 여러 곳에서 참조한 미해결 노드는 줄마다 그 호출 지점을 적는다(노드의 위치는 처음 등록한 곳 하나뿐이다)
        g.addUnresolvedEdge(shop.legacyFind, EdgeKind.CALLS, "method", "helper.unknown()",
                SourceLocation.of("src/main/java/com/shop/order/LegacyOrderService.java", 9));
        assertThat(CallTrees.toText(CallTrees.build(g, shop.legacyFind, CallTreeOptions.callees())))
                .contains("? [unresolved] helper.unknown()  src/main/java/com/shop/order/LegacyOrderService.java:9\n");
        assertThat(text).containsPattern("\\[sql\\] OrderMapper\\.mergeStock <MERGE>  src/main/resources/mapper/OrderMapper\\.xml:\\d+");

        String hidden = CallTrees.toText(CallTrees.build(g, shop.implCreate, CallTreeOptions.builder().hideExternal(true).build()));
        assertThat(hidden).doesNotContain("List#add").contains("helper.unknown()");
    }

    @Test
    @DisplayName("F-015-AC9 JSON 트리와 Mermaid flowchart 출력")
    void jsonAndMermaid() {
        ShopGraph shop = new ShopGraph();
        CallTree tree = CallTrees.build(shop.f.graph(), shop.getOrder, CallTreeOptions.callees());

        String json = CallTrees.toJson(tree);
        assertThat(json).startsWith("{\n  \"start\": \"http:endpoint:GET /orders/{id}\",\n  \"direction\": \"CALLEES\",");
        assertThat(json).contains("\"kind\": \"HTTP_ENDPOINT\"").contains("\"children\": [").contains("\"markers\": [\"impl\"]")
                .contains("\"confidence\": \"INFERRED\"").contains("\"via\": \"ROUTES_TO\"")
                .contains("\"callSite\": \"src/main/java/com/shop/order/OrderController.java:31\"")
                .contains("\"detail\": \"R\"").contains("\"nodeCount\": " + tree.nodeCount());

        String mermaid = CallTrees.toMermaid(tree);
        assertThat(mermaid).startsWith("flowchart TD\n");
        assertThat(MermaidSyntax.checkFlowchart(mermaid)).isEmpty();
        assertThat(mermaid).contains("([\"GET /orders/{id}\"])").contains("[(\"ORDERS\")]").contains("-.->|\"impl\"|").contains("-.->|\"~\"|")
                .contains("[\"[sql] OrderMapper.selectOrder #lt;SELECT#gt;\"]");
        // 같은 노드는 한 번만 정의한다
        assertThat(mermaid.split("OrderMapper#35;selectOrder\\(Long\\)", -1)).hasSize(2);

        // callers 트리는 화살표를 실제 호출 방향(호출자 → 대상)으로 그린다
        String up = CallTrees.toMermaid(CallTrees.build(shop.f.graph(), shop.mapperInsert, CallTreeOptions.callers()));
        assertThat(MermaidSyntax.checkFlowchart(up)).isEmpty();
        assertThat(up).contains("n1 --> n0");
    }

    @Test
    @DisplayName("F-015-AC10 트리거: callees는 쓰는 테이블 → 트리거 → 트리거 안 구문, callers는 트리거 → 테이블 → 쓰는 구문")
    void triggers() {
        ShopGraph shop = new ShopGraph();
        GraphModel g = shop.f.graph();

        assertThat(callees(g, shop.stmtInsert)).isEqualTo("""
                [sql] OrderMapper.insertOrder <INSERT>
                └─ [table] ORDERS (C)
                   └─ [trigger] TRG_ORDER
                      └─ [sql] TRG_ORDER:5 <INSERT>
                         └─ [table] ORDER_HIST (C)
                """);
        // 읽기만 하는 구문 아래에서는 트리거로 가지 않는다
        assertThat(callees(g, shop.stmtSelect)).doesNotContain("TRG_ORDER");

        String up = CallTrees.toText(CallTrees.build(g, shop.trigger, CallTreeOptions.builder()
                .direction(CallTreeOptions.Direction.CALLERS).maxDepth(2).build()), false);
        assertThat(up).isEqualTo("""
                [trigger] TRG_ORDER
                └─ [table] ORDERS
                   ├─ [sql] OrderMapper.insertOrder <INSERT> (C) [depth limit]
                   └─ [sql] OrderMapper.updateStatus <UPDATE> (U) [depth limit]
                """);
    }

    @Test
    @DisplayName("F-015-AC1 스텝이 메서드를 직접 USES하면 타입을 거치지 않는다 (타입만 가리킬 때만 타입 → public 메서드로 펼친다)")
    void stepUsesMethodDirectly() {
        GraphFixture f = GraphFixture.create();
        String job = f.job("j");
        String step = f.step("j", "s");
        f.type("com.b.T", "class", "public");
        String execute = f.method("com.b.T", "execute");
        String rerun = f.method("com.b.T", "rerun");
        f.edge(step, Ids.javaType("com.b.T"), EdgeKind.USES, Map.of("role", "tasklet"));
        f.edge(step, execute, EdgeKind.USES, Map.of("role", "tasklet"));
        String stmt = f.statement("com.b.M", "sel", "SELECT");
        f.edge(stmt, "mybatis:fragment:com.b.M.cols", EdgeKind.INCLUDES);
        f.graph().declare(Node.of("mybatis:fragment:com.b.M.cols", NodeKind.SQL_FRAGMENT, "cols"));
        f.edge(execute, stmt, EdgeKind.EXECUTES);

        assertThat(callees(f.graph(), job)).isEqualTo("""
                [job] j
                └─ [step] s
                   └─ T#execute() (tasklet)
                      └─ [sql] M.sel <SELECT>
                         └─ [fragment] M.cols
                """);
        assertThat(callers(f.graph(), stmt)).isEqualTo("""
                [sql] M.sel <SELECT>
                └─ T#execute()
                   └─ [step] s (tasklet)
                      └─ [job] j
                """);
        assertThat(callers(f.graph(), rerun)).isEqualTo("T#rerun()\n");
    }

    @Test
    @DisplayName("F-015-AC1 F-015-AC43 RENDERS로 닿은 JSP 아래(그 화면의 요청)는 기본으로 펼치지 않고, expandRenderedViews(true)면 펼친다")
    void renderedViewsAsLeaves() {
        ShopGraph shop = new ShopGraph();
        shop.f.request("POST", "/orders", ShopGraph.DETAIL_JSP, 7, shop.detailJsp, shop.postOrder);
        GraphModel g = shop.f.graph();

        assertThat(CallTreeOptions.callees().expandRenderedViews()).isFalse();
        assertThat(CallTreeOptions.callers().expandRenderedViews()).isFalse();
        String followed = CallTrees.toText(CallTrees.build(g, shop.getOrder, CallTreeOptions.builder().expandRenderedViews(true).build()));
        assertThat(followed).contains("   └─ [jsp] src/main/webapp/WEB-INF/views/order/detail.jsp\n"
                + "      └─ [request] POST /orders  src/main/webapp/WEB-INF/views/order/detail.jsp:7\n"
                + "         └─ [endpoint] POST /orders  src/main/java/com/shop/order/OrderController.java:");

        // 조회 API의 기본값: 화면 → 요청 → 다음 엔드포인트로 내려가지 않는다(표준 산출물과 같다)
        String leaf = CallTrees.toText(CallTrees.build(g, shop.getOrder, CallTreeOptions.callees()));
        assertThat(leaf).isEqualTo(CallTrees.toText(CallTrees.build(g, shop.getOrder, CallTreeOptions.builder().expandRenderedViews(false).build())));
        assertThat(leaf).endsWith("   └─ [jsp] src/main/webapp/WEB-INF/views/order/detail.jsp\n").doesNotContain("POST /orders");
        // JSP에서 직접 시작하면 옵션과 무관하게 펼친다
        assertThat(CallTrees.toText(CallTrees.build(g, shop.detailJsp, CallTreeOptions.builder().expandRenderedViews(false).build())))
                .contains("[request] POST /orders");
    }

    @Test
    @DisplayName("F-015-AC18 이름 일부로 시작 노드를 찾는다 (정확한 ID·qualifiedName 우선, ID 순)")
    void findsNodes() {
        ShopGraph shop = new ShopGraph();
        GraphModel g = shop.f.graph();

        assertThat(NodeFinder.find(g, shop.getOrder)).extracting(Node::id).containsExactly(shop.getOrder);
        assertThat(NodeFinder.find(g, "OrderService#find")).extracting(Node::id).containsExactly(shop.serviceFind);
        assertThat(NodeFinder.find(g, "com.shop.order.OrderServiceImpl#create(com.shop.order.OrderDto)")).extracting(Node::id)
                .containsExactly(shop.implCreate);
        assertThat(NodeFinder.find(g, "GET /orders/{id}")).extracting(Node::id).containsExactly(shop.getOrder);
        assertThat(NodeFinder.find(g, "orders", NodeKind.DB_TABLE)).extracting(Node::id).containsExactly(Ids.table("ORDERS"));
        assertThat(NodeFinder.find(g, "OrderMapper.selectOrder")).extracting(Node::id).containsExactly(shop.stmtSelect);
        assertThat(NodeFinder.find(g, "find", NodeKind.JAVA_METHOD)).extracting(Node::id)
                .containsExactly(shop.legacyFind, shop.serviceFind, shop.implFind);
        assertThat(NodeFinder.find(g, "dailyJob")).extracting(Node::id).containsExactly(shop.job);
        assertThat(NodeFinder.find(g, "ORDERS.STATUS")).extracting(Node::id).containsExactly(Ids.column("ORDERS", "STATUS"));
        assertThat(NodeFinder.find(g, "no-such-thing")).isEmpty();
        assertThat(NodeFinder.find(g, " ")).isEmpty();
    }

    /**
     * TB_ORDER에 트리거 셋: TRG_STATUS_HIST(AFTER UPDATE OF ORDER_STATUS), TRG_AUDIT(BEFORE INSERT OR DELETE), TRG_UNKNOWN(사건 정보 없음).
     * 구문: ins(INSERT), updStatus(UPDATE ORDER_STATUS), updAmt(UPDATE TOTAL_AMT), updNoCols(UPDATE, 컬럼 엣지 없음), del(DELETE),
     * merge(MERGE ORDER_STATUS), trunc(TRUNCATE), sel(SELECT).
     */
    private static GraphFixture triggerEvents() {
        GraphFixture f = GraphFixture.create();
        f.table("TB_ORDER");
        f.column("TB_ORDER", "ORDER_STATUS", "VARCHAR2(10)", 1);
        f.column("TB_ORDER", "TOTAL_AMT", "NUMBER", 2);
        f.table("TB_ORDER_HIST");
        String hist = f.trigger("TRG_STATUS_HIST", "TB_ORDER", "AFTER UPDATE OF ORDER_STATUS");
        f.writes(f.routineStatement(hist, 7, "INSERT"), "TB_ORDER_HIST", "INSERT");
        f.trigger("TRG_AUDIT", "TB_ORDER", "BEFORE INSERT OR DELETE");
        f.trigger("TRG_UNKNOWN", "TB_ORDER", null);
        f.writes(f.statement("com.t.M", "ins", "INSERT"), "TB_ORDER", "INSERT", "ORDER_STATUS", "TOTAL_AMT");
        f.writes(f.statement("com.t.M", "updStatus", "UPDATE"), "TB_ORDER", "UPDATE", "ORDER_STATUS");
        f.writes(f.statement("com.t.M", "updAmt", "UPDATE"), "TB_ORDER", "UPDATE", "TOTAL_AMT");
        f.writes(f.statement("com.t.M", "updNoCols", "UPDATE"), "TB_ORDER", "UPDATE");
        f.writes(f.statement("com.t.M", "del", "DELETE"), "TB_ORDER", "DELETE");
        f.writes(f.statement("com.t.M", "merge", "MERGE"), "TB_ORDER", "MERGE", "ORDER_STATUS");
        f.edge(f.statement("com.t.M", "trunc", "DELETE"), Ids.table("TB_ORDER"), EdgeKind.WRITES, Map.of("op", "DELETE", "truncate", true));
        f.reads(f.statement("com.t.M", "sel", "SELECT"), "TB_ORDER", "ORDER_STATUS");
        return f;
    }

    private static String stmt(String id) {
        return Ids.mybatisStatement("com.t.M", id);
    }

    @Test
    @DisplayName("F-015-AC23 callees: 쓰는 구문의 op가 트리거의 triggerEvent와 맞을 때만 트리거로 내려간다 (MERGE는 INSERT·UPDATE, 사건을 모르면 [trigger?])")
    void triggersFireOnlyOnMatchingEvent() {
        GraphModel g = triggerEvents().graph();

        // INSERT는 AFTER UPDATE OF ORDER_STATUS 트리거를 일으키지 않는다(감사 지적 E3의 재현)
        assertThat(callees(g, stmt("ins"))).isEqualTo("""
                [sql] M.ins <INSERT>
                └─ [table] TB_ORDER (C)
                   ├─ [trigger] TRG_AUDIT
                   └─ [trigger?] [trigger] TRG_UNKNOWN
                """);
        // 컬럼 지정 트리거는 그 컬럼을 쓰는 UPDATE에만
        assertThat(callees(g, stmt("updStatus"))).isEqualTo("""
                [sql] M.updStatus <UPDATE>
                └─ [table] TB_ORDER (U)
                   ├─ [trigger] TRG_STATUS_HIST
                   │  └─ [sql] TRG_STATUS_HIST:7 <INSERT>
                   │     └─ [table] TB_ORDER_HIST (C)
                   └─ [trigger?] [trigger] TRG_UNKNOWN
                """);
        assertThat(callees(g, stmt("updAmt"))).doesNotContain("TRG_STATUS_HIST").doesNotContain("TRG_AUDIT")
                .contains("[trigger?] [trigger] TRG_UNKNOWN");
        // 컬럼 엣지가 하나도 없는 UPDATE는 그 컬럼을 쓰는지 알 수 없다: 내려가되 불확실 표시
        assertThat(callees(g, stmt("updNoCols"))).contains("├─ [trigger?] [trigger] TRG_STATUS_HIST\n").doesNotContain("TRG_AUDIT");
        assertThat(callees(g, stmt("del"))).contains("├─ [trigger] TRG_AUDIT\n").doesNotContain("TRG_STATUS_HIST");
        // MERGE는 INSERT와 UPDATE 둘 다에 해당한다
        assertThat(callees(g, stmt("merge"))).contains("├─ [trigger] TRG_AUDIT\n").contains("├─ [trigger] TRG_STATUS_HIST\n");
        // TRUNCATE는 DML 트리거를 일으키지 않는다(사건을 모르는 트리거도). 읽기만 하는 구문도 마찬가지다
        assertThat(callees(g, stmt("trunc"))).isEqualTo("[sql] M.trunc <DELETE>\n└─ [table] TB_ORDER (D)\n");
        assertThat(callees(g, stmt("sel"))).isEqualTo("[sql] M.sel <SELECT>\n└─ [table] TB_ORDER (R)\n");
        // 테이블에서 시작하면(쓰는 구문을 모른다) 걸린 트리거를 모두 보인다
        assertThat(callees(g, Ids.table("TB_ORDER"))).contains("[trigger] TRG_AUDIT", "[trigger] TRG_STATUS_HIST", "[trigger] TRG_UNKNOWN")
                .doesNotContain("[trigger?]");

        CallTreeNode unknown = CallTrees.build(g, stmt("ins"), CallTreeOptions.callees()).root().children().get(0).children().get(1);
        assertThat(unknown.markers()).containsExactly(Marker.TRIGGER_UNCERTAIN);
        assertThat(CallTrees.toJson(CallTrees.build(g, stmt("ins"), CallTreeOptions.callees()))).contains("\"markers\": [\"trigger?\"]");
        String mermaid = CallTrees.toMermaid(CallTrees.build(g, stmt("ins"), CallTreeOptions.callees()));
        assertThat(MermaidSyntax.checkFlowchart(mermaid)).isEmpty();
        assertThat(mermaid).contains("-.->|\"trigger?\"|");
    }

    @Test
    @DisplayName("F-015-AC23 callers: 트리거에서 위로 갈 때도 그 트리거를 일으키는 구문만 나온다")
    void triggerCallersMatchEvent() {
        GraphModel g = triggerEvents().graph();
        CallTreeOptions up = CallTreeOptions.builder().direction(CallTreeOptions.Direction.CALLERS).build();

        assertThat(CallTrees.toText(CallTrees.build(g, Ids.routine("TRG_STATUS_HIST"), up), false)).isEqualTo("""
                [trigger] TRG_STATUS_HIST
                └─ [table] TB_ORDER
                   ├─ [sql] M.merge <MERGE> (CU)
                   ├─ [trigger?] [sql] M.updNoCols <UPDATE> (U)
                   └─ [sql] M.updStatus <UPDATE> (U)
                """);
        assertThat(CallTrees.toText(CallTrees.build(g, Ids.routine("TRG_AUDIT"), up), false)).isEqualTo("""
                [trigger] TRG_AUDIT
                └─ [table] TB_ORDER
                   ├─ [sql] M.del <DELETE> (D)
                   ├─ [sql] M.ins <INSERT> (C)
                   └─ [sql] M.merge <MERGE> (CU)
                """);
        // 트리거 안 구문이 쓰는 테이블에서 시작해도 같다
        String fromHist = CallTrees.toText(CallTrees.build(g, Ids.table("TB_ORDER_HIST"), up), false);
        assertThat(fromHist).contains("[sql] M.updStatus <UPDATE> (U)").contains("[trigger?] [sql] M.updNoCols <UPDATE> (U)")
                .doesNotContain("M.ins").doesNotContain("M.updAmt").doesNotContain("M.del").doesNotContain("M.trunc");
    }

    @Test
    @DisplayName("F-015-AC23 triggerEvent 표기: 시점(BEFORE/AFTER/INSTEAD OF), OR로 이은 사건, UPDATE OF 컬럼 목록")
    void parsesTriggerEvents() {
        assertThat(TriggerEvent.parse("AFTER UPDATE OF ORDER_STATUS").clauses())
                .containsExactly(new TriggerEvent.Clause("UPDATE", java.util.Set.of("ORDER_STATUS")));
        assertThat(TriggerEvent.parse("before insert or update of a, \"b\" or delete").clauses()).containsExactly(
                new TriggerEvent.Clause("INSERT", java.util.Set.of()), new TriggerEvent.Clause("UPDATE", java.util.Set.of("A", "b")),
                new TriggerEvent.Clause("DELETE", java.util.Set.of()));
        assertThat(TriggerEvent.parse("INSTEAD OF INSERT").clauses()).containsExactly(new TriggerEvent.Clause("INSERT", java.util.Set.of()));
        assertThat(TriggerEvent.parse("UPDATE").clauses()).containsExactly(new TriggerEvent.Clause("UPDATE", java.util.Set.of()));
        assertThat(TriggerEvent.parse("NO CASCADE BEFORE UPDATE OF ST,AMT").clauses())
                .containsExactly(new TriggerEvent.Clause("UPDATE", java.util.Set.of("ST", "AMT")));
        assertThat(TriggerEvent.parse(null).known()).isFalse();
        assertThat(TriggerEvent.parse("AFTER LOGON").known()).isFalse();
    }

    /**
     * DiscountPolicy#discount(인터페이스) ⇐ DefaultDiscountPolicy#discount ⇐ VipDiscountPolicy#discount(super.discount 호출).
     * GoldDiscountPolicy extends VipDiscountPolicy(재정의 없음). OVERRIDES는 가장 가까운 상위 하나로만 간다.
     * 주입: OrderServiceImpl→[Vip], MemberServiceImpl→[Default], GoldClient→[Gold], Other는 정보 없음.
     */
    private static GraphFixture discount() {
        GraphFixture f = GraphFixture.create();
        for (String type : List.of("OrderServiceImpl", "MemberServiceImpl", "GoldClient", "Other", "DefaultDiscountPolicy",
                "VipDiscountPolicy", "GoldDiscountPolicy", "RateDao")) {
            f.type("com.p." + type, "class", "public");
        }
        f.type("com.p.DiscountPolicy", "interface", "public");
        f.typeEdge("com.p.DefaultDiscountPolicy", "com.p.DiscountPolicy", EdgeKind.IMPLEMENTS);
        f.typeEdge("com.p.VipDiscountPolicy", "com.p.DefaultDiscountPolicy", EdgeKind.EXTENDS);
        f.typeEdge("com.p.GoldDiscountPolicy", "com.p.VipDiscountPolicy", EdgeKind.EXTENDS);
        String policy = f.method("com.p.DiscountPolicy", "discount", List.of(), "long");
        String byDefault = f.method("com.p.DefaultDiscountPolicy", "discount");
        String byVip = f.method("com.p.VipDiscountPolicy", "discount");
        f.edge(byDefault, policy, EdgeKind.OVERRIDES);
        f.edge(byVip, byDefault, EdgeKind.OVERRIDES);
        f.edge(byVip, byDefault, EdgeKind.CALLS, Map.of("callType", "super"));
        f.calls(byDefault, f.method("com.p.RateDao", "rate"));
        for (String caller : List.of("OrderServiceImpl#placeOrder", "MemberServiceImpl#join", "GoldClient#run", "Other#run")) {
            String type = "com.p." + caller.substring(0, caller.indexOf('#'));
            f.edge(f.method(type, caller.substring(caller.indexOf('#') + 1)), policy, EdgeKind.CALLS, Map.of("callType", "interface"));
        }
        inject(f, "OrderServiceImpl", "VipDiscountPolicy");
        inject(f, "MemberServiceImpl", "DefaultDiscountPolicy");
        inject(f, "GoldClient", "GoldDiscountPolicy");
        return f;
    }

    private static void inject(GraphFixture f, String client, String candidate) {
        f.edge(Ids.javaType("com.p." + client), Ids.javaType("com.p.DiscountPolicy"), EdgeKind.INJECTS,
                Map.of("via", "constructor", "candidates", List.of(Ids.javaType("com.p." + candidate))));
    }

    @Test
    @DisplayName("F-015-AC24 구현 펼치기는 OVERRIDES를 전이적으로 따르고, 주입 좁히기는 후보 타입에서 가장 가까운 구현(자신 또는 상위 클래스)을 고른다")
    void transitiveImplementationsAndNearestOverride() {
        GraphModel g = discount().graph();

        // 주입 정보가 없으면 간접 구현(Vip)까지 펼친다
        assertThat(callees(g, Ids.javaMethod("com.p.Other", "run"))).isEqualTo("""
                Other#run()
                └─ DiscountPolicy#discount()
                   ├─ [impl] DefaultDiscountPolicy#discount()
                   │  └─ RateDao#rate()
                   └─ [impl] VipDiscountPolicy#discount()
                      └─ DefaultDiscountPolicy#discount() (super) [see above]
                """);
        // @Qualifier("vipDiscountPolicy"): Vip의 재정의로 가고 거기서 super로 Default에 닿는다(감사 지적 E4의 재현)
        assertThat(callees(g, Ids.javaMethod("com.p.OrderServiceImpl", "placeOrder"))).isEqualTo("""
                OrderServiceImpl#placeOrder()
                └─ DiscountPolicy#discount()
                   └─ [impl] VipDiscountPolicy#discount()
                      └─ DefaultDiscountPolicy#discount() (super)
                         └─ RateDao#rate()
                """);
        // 후보가 Default면 하위 클래스(Vip)의 재정의는 고르지 않는다
        assertThat(callees(g, Ids.javaMethod("com.p.MemberServiceImpl", "join"))).isEqualTo("""
                MemberServiceImpl#join()
                └─ DiscountPolicy#discount()
                   └─ [impl] DefaultDiscountPolicy#discount()
                      └─ RateDao#rate()
                """);
        // 후보 타입에 재정의가 없으면 EXTENDS를 따라 가장 가까운 상위 클래스의 구현을 고른다
        assertThat(callees(g, Ids.javaMethod("com.p.GoldClient", "run"))).startsWith("""
                GoldClient#run()
                └─ DiscountPolicy#discount()
                   └─ [impl] VipDiscountPolicy#discount()
                """).doesNotContain("[impl] DefaultDiscountPolicy");
    }

    @Test
    @DisplayName("F-015-AC25 callers: 전이적으로 재정의한 모든 상위 메서드의 호출자를 [via interface]로 넣되, 주입이 다른 구현을 가리키는 호출자와 super 호출은 뺀다")
    void callersThroughTransitiveOverrides() {
        GraphModel g = discount().graph();
        CallTreeOptions oneUp = CallTreeOptions.builder().direction(CallTreeOptions.Direction.CALLERS).maxDepth(1).build();

        // Vip#discount → Default#discount → DiscountPolicy#discount: 인터페이스 메서드의 호출자까지 올라간다.
        // Default를 주입받는 MemberServiceImpl은 빠지고, 자기 자신의 super 호출은 호출자가 아니다.
        assertThat(CallTrees.toText(CallTrees.build(g, Ids.javaMethod("com.p.VipDiscountPolicy", "discount"), oneUp), false)).isEqualTo("""
                VipDiscountPolicy#discount()
                ├─ [via interface] GoldClient#run()
                ├─ [via interface] OrderServiceImpl#placeOrder()
                └─ [via interface] Other#run()
                """);
        // Default#discount: Vip을 주입받는 호출자는 Vip#discount를 거쳐서(super) 닿는다
        assertThat(CallTrees.toText(CallTrees.build(g, Ids.javaMethod("com.p.DefaultDiscountPolicy", "discount"), oneUp), false)).isEqualTo("""
                DefaultDiscountPolicy#discount()
                ├─ [via interface] MemberServiceImpl#join()
                ├─ [via interface] Other#run()
                └─ VipDiscountPolicy#discount() (super) [depth limit]
                """);
        assertThat(callers(g, Ids.javaMethod("com.p.RateDao", "rate"))).contains("[via interface] OrderServiceImpl#placeOrder()")
                .contains("[via interface] MemberServiceImpl#join()");
    }

    @Test
    @DisplayName("F-015-AC26 콜트리의 테이블 줄: 컬럼 수준 READS는 R로 세지 않는다 (DELETE … WHERE는 D, 단순 UPDATE는 U)")
    void columnReadsAreNotTableReads() {
        GraphFixture f = GraphFixture.create();
        f.table("T");
        f.column("T", "ID", "NUMBER", 1);
        f.column("T", "NAME", "VARCHAR2(10)", 2);
        String del = f.statement("com.r.M", "del", "DELETE");
        f.writes(del, "T", "DELETE").readsColumn(del, "T", "ID");
        String upd = f.statement("com.r.M", "upd", "UPDATE");
        f.writes(upd, "T", "UPDATE", "NAME").readsColumn(upd, "T", "ID");
        String updSub = f.statement("com.r.M", "updSub", "UPDATE");
        f.writes(updSub, "T", "UPDATE", "NAME").reads(updSub, "T", "ID");

        assertThat(callees(f.graph(), del)).isEqualTo("[sql] M.del <DELETE>\n└─ [table] T (D)\n");
        assertThat(callees(f.graph(), upd)).isEqualTo("[sql] M.upd <UPDATE>\n└─ [table] T (U)\n");
        // 서브쿼리에서 같은 테이블을 읽으면(테이블 수준 READS가 있으면) R이 붙는다
        assertThat(callees(f.graph(), updSub)).isEqualTo("[sql] M.updSub <UPDATE>\n└─ [table] T (RU)\n");
        assertThat(callers(f.graph(), Ids.table("T"))).isEqualTo("""
                [table] T
                ├─ [sql] M.del <DELETE> (D)
                ├─ [sql] M.upd <UPDATE> (U)
                └─ [sql] M.updSub <UPDATE> (RU)
                """);
    }

    @Test
    @DisplayName("F-015-AC29 잡의 스텝은 NEXT 흐름 순서로 나온다 (시작 스텝부터, 분기는 ID 순, 순환은 한 번만, 연결 안 된 스텝은 뒤에 이름순)")
    void stepsInFlowOrder() {
        GraphFixture f = GraphFixture.create();
        String job = f.job("flow");
        for (String name : List.of("aSecond", "bFail", "bLone", "cAlone", "mThird", "zFirst")) {
            f.step("flow", name);
        }
        f.edge(Ids.batchStep("flow", "zFirst"), Ids.batchStep("flow", "aSecond"), EdgeKind.NEXT);
        f.edge(Ids.batchStep("flow", "aSecond"), Ids.batchStep("flow", "mThird"), EdgeKind.NEXT);
        f.edge(Ids.batchStep("flow", "aSecond"), Ids.batchStep("flow", "bFail"), EdgeKind.NEXT, Map.of("on", "FAILED"));
        // 재시도 순환
        f.edge(Ids.batchStep("flow", "mThird"), Ids.batchStep("flow", "aSecond"), EdgeKind.NEXT, Map.of("on", "RETRY"));

        assertThat(callees(f.graph(), job)).isEqualTo("""
                [job] flow
                ├─ [step] zFirst
                ├─ [step] aSecond
                ├─ [step] bFail
                ├─ [step] mThird
                ├─ [step] bLone
                └─ [step] cAlone
                """);
        assertThat(callees(f.reversedCopy(), job)).isEqualTo(callees(f.graph(), job));

        // 들어오는 NEXT가 없는 스텝이 하나도 없는 순환뿐이면 ID가 앞선 스텝부터 흐름을 따른다
        GraphFixture loop = GraphFixture.create();
        loop.job("loop");
        String x = loop.step("loop", "x");
        String y = loop.step("loop", "y");
        String w = loop.step("loop", "w");
        loop.edge(y, x, EdgeKind.NEXT);
        loop.edge(x, w, EdgeKind.NEXT);
        loop.edge(w, y, EdgeKind.NEXT);
        assertThat(BatchFlow.order(loop.graph(), List.of(x, y, w))).containsExactly(w, y, x);
    }

    @Test
    @DisplayName("F-015-AC1 없는 시작 노드와 미해결 노드에서도 예외 없이 한 줄짜리 트리가 나온다")
    void missingStart() {
        GraphModel g = new ShopGraph().f.graph();

        CallTree tree = CallTrees.build(g, "java:method:com.none.Missing#x()", CallTreeOptions.callees());
        assertThat(tree.nodeCount()).isEqualTo(1);
        assertThat(CallTrees.toText(tree, false)).isEqualTo("? Missing#x()\n");
        assertThat(CallTrees.toMermaid(tree)).startsWith("flowchart TD\n");
    }
}
