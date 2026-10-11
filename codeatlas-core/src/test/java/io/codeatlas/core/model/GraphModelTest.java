package io.codeatlas.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphModelTest {

    private static final SourceLocation LOC = SourceLocation.of("ddl/schema.sql", 3, 1, 9, 2);

    @Test
    @DisplayName("F-001-AC6 reference 뒤 declare는 한 노드로 병합되고 declared, 위치, 속성 규칙을 따른다")
    void referenceThenDeclareMerges() {
        GraphModel graph = new GraphModel();
        Node ref = graph.reference("db:table:ORDERS", NodeKind.DB_TABLE, "ORDERS");
        assertThat(ref.declared()).isFalse();
        assertThat(ref.attrs()).containsEntry("declared", false);
        graph.putAttrs("db:table:ORDERS", Map.of("schema", "OLD", "seenBy", "sql"));

        Node declared = graph.declare(Node.builder("db:table:ORDERS", NodeKind.DB_TABLE)
                .name("ORDERS").location(LOC).attr("schema", "HR").attr("comment", "주문").build());

        assertThat(graph.nodes()).hasSize(1);
        assertThat(declared.declared()).isTrue();
        assertThat(declared.location()).isEqualTo(LOC);
        assertThat(declared.attrs())
                .containsEntry("schema", "HR")
                .containsEntry("seenBy", "sql")
                .containsEntry("comment", "주문")
                .containsEntry("declared", true);

        Node again = graph.reference("db:table:ORDERS", NodeKind.DB_TABLE, "ORDERS");
        assertThat(again).isEqualTo(declared);
        assertThat(graph.node("db:table:ORDERS")).contains(declared);
    }

    @Test
    @DisplayName("F-001-AC6 declare를 두 번 하면 새 attrs가 덮어쓰고 첫 정의 위치는 유지된다")
    void declareTwiceMergesAttrs() {
        GraphModel graph = new GraphModel();
        graph.declare(Node.builder("java:type:com.a.B", NodeKind.JAVA_TYPE).name("B")
                .location(SourceLocation.of("B.java", 1)).attr("typeKind", "class").build());
        Node merged = graph.declare(Node.builder("java:type:com.a.B", NodeKind.JAVA_TYPE).name("B")
                .attr("stereotype", "service").attr("typeKind", "interface").build());

        assertThat(merged.attrs()).containsEntry("typeKind", "interface").containsEntry("stereotype", "service");
        assertThat(merged.location()).isEqualTo(SourceLocation.of("B.java", 1));
        assertThat(graph.nodes()).hasSize(1);
    }

    @Test
    @DisplayName("F-001-AC50 reference로 새로 만든 노드의 qualifiedName은 ID에서 접두사를 뗀 나머지이고 name은 준 값 그대로다")
    void referenceDerivesQualifiedNameFromId() {
        GraphModel graph = new GraphModel();
        // {ID, 종류, 호출자가 준 name}. 기대하는 qualifiedName은 ID의 나머지다.
        Object[][] cases = {
                {"java:type:jakarta.annotation.Resource", NodeKind.JAVA_TYPE, "Resource"},
                {"java:type:com.a.Outer$Inner", NodeKind.JAVA_TYPE, "Inner"},
                {"java:method:javax.servlet.http.HttpServletRequest#getContextPath(?)", NodeKind.JAVA_METHOD, "getContextPath"},
                {"java:field:com.a.B#count", NodeKind.JAVA_FIELD, "count"},
                {"db:table:TB_HOLIDAY", NodeKind.DB_TABLE, "TB_HOLIDAY"},
                {"db:view:V_ORDERS", NodeKind.DB_VIEW, "V_ORDERS"},
                {"db:routine:PKG_ORDER.CLOSE_ORDER", NodeKind.DB_ROUTINE, "CLOSE_ORDER"},
                {"db:sequence:SEQ_ORDER", NodeKind.DB_SEQUENCE, "SEQ_ORDER"},
                {"db:column:TB_BROKEN.BROKEN_ID", NodeKind.DB_COLUMN, "BROKEN_ID"},
                {"mybatis:stmt:com.a.OrderMapper.selectOne", NodeKind.SQL_STATEMENT, "selectOne"},
                {"spring:bean:orderService", NodeKind.SPRING_BEAN, "orderService"},
                {"js:module:static/js/app.js", NodeKind.JS_MODULE, "app.js"},
                {"jsp:page:WEB-INF/views/order/list.jsp", NodeKind.JSP_PAGE, "list.jsp"},
                {"http:endpoint:GET /orders/{id}", NodeKind.HTTP_ENDPOINT, "GET /orders/{id}"},
                {"batch:step:?/loadStep", NodeKind.BATCH_STEP, "loadStep"},
                {"file:src/a:b.txt", NodeKind.FILE, "a:b.txt"},
        };
        for (Object[] c : cases) {
            String id = (String) c[0];
            Node created = graph.reference(id, (NodeKind) c[1], (String) c[2]);
            assertThat(created.qualifiedName()).as(id).isEqualTo(Ids.remainder(id));
            assertThat(created.name()).as(id).isEqualTo(c[2]);
            assertThat(created.declared()).as(id).isFalse();
            assertThat(graph.node(id)).as(id).contains(created);
        }
        assertThat(graph.node("java:type:jakarta.annotation.Resource").orElseThrow().qualifiedName())
                .isEqualTo("jakarta.annotation.Resource");
        assertThat(graph.node("java:method:javax.servlet.http.HttpServletRequest#getContextPath(?)").orElseThrow().qualifiedName())
                .isEqualTo("javax.servlet.http.HttpServletRequest#getContextPath(?)");
        assertThat(graph.node("db:column:TB_BROKEN.BROKEN_ID").orElseThrow().qualifiedName()).isEqualTo("TB_BROKEN.BROKEN_ID");
        assertThat(graph.node("file:src/a:b.txt").orElseThrow().qualifiedName()).isEqualTo("src/a:b.txt");

        // 계약에 없는 접두사와 콜론이 없는 ID도 나머지 전체를 쓴다. name을 주지 않으면 name도 나머지다.
        assertThat(graph.reference("custom:thing:x.y", NodeKind.UNRESOLVED, "y").qualifiedName()).isEqualTo("x.y");
        assertThat(graph.reference("weird", NodeKind.UNRESOLVED, "w").qualifiedName()).isEqualTo("weird");
        Node unnamed = graph.reference("db:column:T.C", NodeKind.DB_COLUMN, null);
        assertThat(unnamed.name()).isEqualTo("T.C");
        assertThat(unnamed.qualifiedName()).isEqualTo("T.C");
    }

    @Test
    @DisplayName("F-001-AC50 이미 있는 노드를 reference하면 qualifiedName을 건드리지 않는다")
    void referenceKeepsExistingQualifiedName() {
        GraphModel graph = new GraphModel();
        Node declared = graph.declare(Node.builder("db:table:ORDERS", NodeKind.DB_TABLE).name("ORDERS").qualifiedName("HR.ORDERS").build());
        Node shortNamed = graph.declare(Node.of("java:type:com.a.B", NodeKind.JAVA_TYPE, "B"));
        Node first = graph.reference("java:method:com.x.Ext#run(?)", NodeKind.JAVA_METHOD, "run");

        assertThat(graph.reference("db:table:ORDERS", NodeKind.DB_TABLE, "ORDERS")).isSameAs(declared);
        assertThat(graph.reference("java:type:com.a.B", NodeKind.JAVA_TYPE, "B")).isSameAs(shortNamed);
        assertThat(graph.reference("java:method:com.x.Ext#run(?)", NodeKind.JAVA_METHOD, "other")).isSameAs(first);

        assertThat(graph.node("db:table:ORDERS").orElseThrow().qualifiedName()).isEqualTo("HR.ORDERS");
        assertThat(graph.node("java:type:com.a.B").orElseThrow().qualifiedName()).isEqualTo("B");
        assertThat(first.name()).isEqualTo("run");
        assertThat(first.qualifiedName()).isEqualTo("com.x.Ext#run(?)");
    }

    @Test
    @DisplayName("F-001-AC50 reference 뒤에 declare가 오면 declare의 name과 qualifiedName이 이긴다")
    void declareAfterReferenceWinsQualifiedName() {
        GraphModel graph = new GraphModel();
        graph.reference("java:method:com.a.B#save(java.lang.String)", NodeKind.JAVA_METHOD, "save");
        graph.reference("java:type:com.a.B", NodeKind.JAVA_TYPE, "B");

        Node method = graph.declare(Node.builder("java:method:com.a.B#save(java.lang.String)", NodeKind.JAVA_METHOD)
                .name("save").qualifiedName("com.a.B#save(String)").build());
        // qualifiedName을 주지 않은 declare는 지금처럼 name과 같은 값이 된다
        Node type = graph.declare(Node.of("java:type:com.a.B", NodeKind.JAVA_TYPE, "B"));

        assertThat(method.qualifiedName()).isEqualTo("com.a.B#save(String)");
        assertThat(method.declared()).isTrue();
        assertThat(type.qualifiedName()).isEqualTo("B");
        assertThat(graph.nodes()).hasSize(2);
    }

    @Test
    @DisplayName("F-001-AC50 마무리 단계가 만드는 UNRESOLVED 노드의 name과 qualifiedName은 그대로다(name은 나머지, qualifiedName은 ID)")
    void finalizationNodesKeepTheirNames() {
        GraphModel graph = new GraphModel();
        graph.declare(Node.of("java:method:com.a.A#a()", NodeKind.JAVA_METHOD, "a"));
        graph.addEdge(Edge.of("java:method:com.a.A#a()", "java:method:com.a.B#x()", EdgeKind.CALLS));

        graph.resolveDanglingEdges();

        Node unresolved = graph.node("java:method:com.a.B#x()").orElseThrow();
        assertThat(unresolved.kind()).isEqualTo(NodeKind.UNRESOLVED);
        assertThat(unresolved.name()).isEqualTo("com.a.B#x()");
        assertThat(unresolved.qualifiedName()).isEqualTo("java:method:com.a.B#x()");
    }

    @Test
    @DisplayName("F-001-AC7 (from,to,kind,location)이 같은 엣지는 한 번만 저장한다")
    void duplicateEdgesAreStoredOnce() {
        GraphModel graph = new GraphModel();
        SourceLocation l1 = SourceLocation.of("A.java", 10);
        SourceLocation l2 = SourceLocation.of("A.java", 20);
        Edge first = graph.addEdge(Edge.builder("java:method:A#a()", "java:method:B#b()", EdgeKind.CALLS).location(l1).build());
        Edge dup = graph.addEdge(Edge.builder("java:method:A#a()", "java:method:B#b()", EdgeKind.CALLS).location(l1)
                .attr("callType", "virtual").build());
        graph.addEdge(Edge.builder("java:method:A#a()", "java:method:B#b()", EdgeKind.CALLS).location(l2).build());

        assertThat(dup).isSameAs(first);
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.outgoing("java:method:A#a()")).hasSize(2);
        assertThat(graph.incoming("java:method:B#b()")).hasSize(2);
        assertThat(graph.edges(EdgeKind.CALLS)).hasSize(2);
        assertThat(graph.edges(EdgeKind.READS)).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC13 매달린 엣지는 UNRESOLVED 노드를 만들고 신뢰도가 UNRESOLVED로 바뀐다")
    void danglingEdgesBecomeUnresolved() {
        GraphModel graph = new GraphModel();
        graph.declare(Node.of("java:method:com.a.A#a()", NodeKind.JAVA_METHOD, "a"));
        graph.declare(Node.of("java:method:com.a.A#ok()", NodeKind.JAVA_METHOD, "ok"));
        graph.addEdge(Edge.of("java:method:com.a.A#a()", "java:method:com.a.B#x()", EdgeKind.CALLS));
        graph.addEdge(Edge.of("java:method:com.a.A#a()", "java:method:com.a.A#ok()", EdgeKind.CALLS));
        graph.addEdge(Edge.of("db:table:GHOST", "java:method:com.a.A#ok()", EdgeKind.JOINS));

        int created = graph.resolveDanglingEdges();

        assertThat(created).isEqualTo(2);
        Node unresolved = graph.node("java:method:com.a.B#x()").orElseThrow();
        assertThat(unresolved.kind()).isEqualTo(NodeKind.UNRESOLVED);
        assertThat(unresolved.attrs()).containsEntry("expectedPrefix", "java:method").containsEntry("declared", false);
        assertThat(graph.node("db:table:GHOST").orElseThrow().attrs()).containsEntry("expectedPrefix", "db:table");

        assertThat(graph.edges("java:method:com.a.A#a()", EdgeKind.CALLS, "java:method:com.a.B#x()"))
                .singleElement().extracting(Edge::confidence).isEqualTo(Confidence.UNRESOLVED);
        assertThat(graph.edges("db:table:GHOST", EdgeKind.JOINS, "java:method:com.a.A#ok()"))
                .singleElement().extracting(Edge::confidence).isEqualTo(Confidence.UNRESOLVED);
        assertThat(graph.edges("java:method:com.a.A#a()", EdgeKind.CALLS, "java:method:com.a.A#ok()"))
                .singleElement().extracting(Edge::confidence).isEqualTo(Confidence.RESOLVED);
        assertThat(graph.diagnostics()).filteredOn(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(2);
        assertThat(graph.resolveDanglingEdges()).isZero();
    }

    @Test
    @DisplayName("addUnresolvedEdge는 unresolved 노드, UNRESOLVED 엣지, 진단을 함께 남긴다")
    void addUnresolvedEdge() {
        GraphModel graph = new GraphModel();
        SourceLocation loc = SourceLocation.of("A.java", 7);
        Edge edge = graph.addUnresolvedEdge("java:method:A#a()", EdgeKind.CALLS, "java-call", "foo.bar()", loc);

        assertThat(edge.to()).isEqualTo("unresolved:java-call:foo.bar()");
        assertThat(edge.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(edge.location()).isEqualTo(loc);
        Node node = graph.node(edge.to()).orElseThrow();
        assertThat(node.kind()).isEqualTo(NodeKind.UNRESOLVED);
        assertThat(node.name()).isEqualTo("foo.bar()");
        assertThat(graph.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Severity.WARN);
            assertThat(d.code()).isEqualTo("UNRESOLVED_REFERENCE");
            assertThat(d.location()).isEqualTo(loc);
        });
    }

    @Test
    @DisplayName("종류별 조회와 이웃 탐색")
    void queries() {
        GraphModel graph = new GraphModel();
        graph.declare(Node.of("java:type:A", NodeKind.JAVA_TYPE, "A"));
        graph.declare(Node.of("java:method:A#a()", NodeKind.JAVA_METHOD, "a"));
        graph.declare(Node.of("java:method:A#b()", NodeKind.JAVA_METHOD, "b"));
        graph.declare(Node.of("java:method:A#c()", NodeKind.JAVA_METHOD, "c"));
        graph.addEdge(Edge.of("java:type:A", "java:method:A#a()", EdgeKind.CONTAINS));
        graph.addEdge(Edge.of("java:type:A", "java:method:A#b()", EdgeKind.CONTAINS));
        graph.addEdge(Edge.of("java:method:A#a()", "java:method:A#b()", EdgeKind.CALLS));
        graph.addEdge(Edge.of("java:method:A#b()", "java:method:A#c()", EdgeKind.CALLS));
        graph.addEdge(Edge.of("java:method:A#c()", "java:method:A#a()", EdgeKind.CALLS));

        assertThat(graph.nodes(NodeKind.JAVA_METHOD)).extracting(Node::name).containsExactly("a", "b", "c");
        assertThat(graph.outgoing("java:type:A", EdgeKind.CALLS)).isEmpty();
        assertThat(graph.successors("java:type:A", EdgeKind.CONTAINS)).extracting(Node::name).containsExactly("a", "b");
        assertThat(graph.predecessors("java:method:A#b()")).extracting(Node::id)
                .containsExactlyInAnyOrder("java:type:A", "java:method:A#a()");
        assertThat(graph.predecessors("java:method:A#b()", EdgeKind.CALLS)).extracting(Node::name).containsExactly("a");
        assertThat(graph.outgoing("nope")).isEmpty();

        assertThat(GraphTraversal.reachable(graph, "java:method:A#a()", GraphTraversal.Direction.OUT, 10, EdgeKind.CALLS))
                .containsExactly("java:method:A#b()", "java:method:A#c()");
        assertThat(GraphTraversal.reachable(graph, "java:method:A#a()", GraphTraversal.Direction.OUT, 1, EdgeKind.CALLS))
                .containsExactly("java:method:A#b()");
        assertThat(GraphTraversal.reachable(graph, "java:method:A#a()", GraphTraversal.Direction.IN, 10, EdgeKind.CALLS))
                .containsExactly("java:method:A#c()", "java:method:A#b()");
    }

    @Test
    @DisplayName("F-001-AC52 종류로 거른 incoming/outgoing은 넣은 순서를 지키고(여러 종류를 섞어도, replaceNode 뒤에도) 진입 차수가 큰 노드에서도 결과 수만큼만 일한다")
    void kindFilteredLookupsKeepOrderAndScale() {
        GraphModel graph = new GraphModel();
        graph.declare(Node.of("db:table:T", NodeKind.DB_TABLE, "T"));
        graph.declare(Node.of("db:column:T.C", NodeKind.DB_COLUMN, "C"));
        graph.addEdge(Edge.of("sql:stmt:a.sql:1", "db:column:T.C", EdgeKind.WRITES));
        graph.addEdge(Edge.of("db:table:T", "db:column:T.C", EdgeKind.CONTAINS));
        graph.addEdge(Edge.of("sql:stmt:a.sql:2", "db:column:T.C", EdgeKind.READS));
        graph.addEdge(Edge.of("sql:stmt:a.sql:3", "db:column:T.C", EdgeKind.WRITES));
        graph.addEdge(Edge.of("sql:stmt:a.sql:1", "db:table:T", EdgeKind.WRITES));
        graph.addEdge(Edge.of("sql:stmt:a.sql:1", "db:table:T", EdgeKind.READS));

        assertThat(graph.incoming("db:column:T.C")).extracting(Edge::from)
                .containsExactly("sql:stmt:a.sql:1", "db:table:T", "sql:stmt:a.sql:2", "sql:stmt:a.sql:3");
        assertThat(graph.incoming("db:column:T.C", EdgeKind.CONTAINS)).extracting(Edge::from).containsExactly("db:table:T");
        // 여러 종류를 주면 종류별로 묶이지 않고 넣은 순서 그대로다. 같은 종류를 두 번 줘도 한 번만 나온다.
        assertThat(graph.incoming("db:column:T.C", EdgeKind.WRITES, EdgeKind.READS, EdgeKind.WRITES)).extracting(Edge::from)
                .containsExactly("sql:stmt:a.sql:1", "sql:stmt:a.sql:2", "sql:stmt:a.sql:3");
        assertThat(graph.incoming("db:column:T.C", new EdgeKind[0])).hasSize(4);
        assertThat(graph.outgoing("sql:stmt:a.sql:1", EdgeKind.READS, EdgeKind.WRITES)).extracting(e -> e.kind() + " " + e.to())
                .containsExactly("WRITES db:column:T.C", "WRITES db:table:T", "READS db:table:T");
        assertThat(graph.outgoing("sql:stmt:a.sql:1", EdgeKind.CALLS)).isEmpty();
        assertThat(graph.edges("sql:stmt:a.sql:1", EdgeKind.WRITES, "db:table:T")).hasSize(1);
        assertThatThrownBy(() -> graph.incoming("db:column:T.C", EdgeKind.READS).add(Edge.of("a", "b", EdgeKind.READS)))
                .isInstanceOf(UnsupportedOperationException.class);

        // 마무리 단계가 신뢰도를 바꾼 엣지도 조회에 그대로 보인다(매달린 구문 노드).
        graph.resolveDanglingEdges();
        assertThat(graph.incoming("db:column:T.C", EdgeKind.READS)).singleElement().extracting(Edge::confidence)
                .isEqualTo(Confidence.UNRESOLVED);

        // replaceNode로 옮긴 엣지는 지금처럼 뒤에 붙는다.
        graph.declare(Node.of("db:view:T", NodeKind.DB_VIEW, "T"));
        graph.replaceNode("db:table:T", "db:view:T");
        assertThat(graph.incoming("db:column:T.C", EdgeKind.CONTAINS)).extracting(Edge::from).containsExactly("db:view:T");
        assertThat(graph.incoming("db:column:T.C")).extracting(Edge::from)
                .containsExactly("sql:stmt:a.sql:1", "sql:stmt:a.sql:2", "sql:stmt:a.sql:3", "db:view:T");
        assertThat(graph.outgoing("db:table:T")).isEmpty();
        assertThat(graph.incoming("db:view:T", EdgeKind.WRITES, EdgeKind.READS)).extracting(Edge::kind)
                .containsExactly(EdgeKind.WRITES, EdgeKind.READS);

        // 들어오는 엣지가 20만 개인 컬럼: 종류로 거른 조회를 엣지 수만큼 불러도 전체를 매번 복사하지 않는다.
        GraphModel big = new GraphModel();
        big.addEdge(Edge.of("db:table:BIG", "db:column:BIG.C", EdgeKind.CONTAINS));
        int writers = 200_000;
        for (int i = 0; i < writers; i++) {
            big.addEdge(Edge.of("sql:stmt:data.sql:" + i, "db:column:BIG.C", EdgeKind.WRITES));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            int found = 0;
            for (int i = 0; i < writers; i++) {
                found += big.incoming("db:column:BIG.C", EdgeKind.CONTAINS).size();
            }
            assertThat(found).isEqualTo(writers);
        });
        assertThat(big.incoming("db:column:BIG.C", EdgeKind.WRITES)).hasSize(writers);
        assertThat(big.incoming("db:column:BIG.C")).hasSize(writers + 1);
    }

    @Test
    @DisplayName("F-001-AC53 마무리 단계가 만든 UNRESOLVED 노드에 expectedPrefix에서 유도한 category가 붙는다")
    void danglingNodesGetCategory() {
        GraphModel graph = new GraphModel();
        graph.declare(Node.of("java:method:com.a.A#a()", NodeKind.JAVA_METHOD, "a"));
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("mybatis:stmt:ns.missing", "mybatis-statement");
        expected.put("java:method:com.a.B#x()", "java-call");
        expected.put("java:type:com.a.Gone", "java-type");
        expected.put("db:routine:PROC_GONE", "db-routine");
        expected.put("spring:bean:goneBean", "spring-bean-ref");
        expected.put("js:module:static/gone.js", "js-module");
        expected.put("js:function:static/a.js#gone", "js-call");
        expected.put("jsp:page:WEB-INF/gone.jsp", "jsp-include");
        expected.put("http:endpoint:GET /gone", "http-endpoint");
        expected.put("mybatis:fragment:ns.cols", "mybatis-fragment");
        expected.put("batch:step:job/gone", "batch-step");
        expected.put("db:table:GHOST", "db-table");
        expected.put("sql:stmt:a.sql:9", "sql-stmt");
        expected.put("whatever", "whatever");
        expected.keySet().forEach(id -> graph.addEdge(Edge.of("java:method:com.a.A#a()", id, EdgeKind.CALLS)));

        graph.resolveDanglingEdges();

        expected.forEach((id, category) -> {
            Node node = graph.node(id).orElseThrow();
            assertThat(node.kind()).as(id).isEqualTo(NodeKind.UNRESOLVED);
            assertThat(node.attrs()).as(id).containsEntry("category", category).containsEntry("expectedPrefix", Ids.prefixOf(id));
            assertThat(Ids.danglingCategory(Ids.prefixOf(id))).as(id).isEqualTo(category);
        });
        // 분석기가 직접 남긴 미해결 노드의 category는 건드리지 않는다.
        graph.addUnresolvedEdge("java:method:com.a.A#a()", EdgeKind.CALLS, "java-call", "x.y(…)", null);
        assertThat(graph.node(Ids.unresolved("java-call", "x.y(…)")).orElseThrow().attrs())
                .containsEntry("category", "java-call").doesNotContainKey("expectedPrefix");
        assertThat(Ids.danglingCategory(null)).isNull();
    }

    @Test
    @DisplayName("putAttrs는 있는 노드의 속성만 덧붙인다")
    void putAttrs() {
        GraphModel graph = new GraphModel();
        graph.declare(Node.of("mybatis:stmt:ns.id", NodeKind.SQL_STATEMENT, "id"));
        assertThat(graph.putAttrs("mybatis:stmt:ns.id", Map.of("statementType", "SELECT")))
                .get().satisfies(n -> assertThat(n.attrs()).containsEntry("statementType", "SELECT").containsEntry("declared", true));
        assertThat(graph.putAttrs("mybatis:stmt:none", Map.of("a", "b"))).isEmpty();
        assertThat(graph.node("mybatis:stmt:none")).isEmpty();
    }

    @Test
    @DisplayName("여러 스레드에서 동시에 써도 노드와 엣지가 빠지지 않는다")
    void threadSafe() throws Exception {
        GraphModel graph = new GraphModel();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            int thread = t;
            pool.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    graph.declare(Node.of("java:type:T" + thread + "_" + i, NodeKind.JAVA_TYPE, "T"));
                    graph.reference("java:type:Shared" + i, NodeKind.JAVA_TYPE, "Shared");
                    graph.addEdge(Edge.of("java:type:T" + thread + "_" + i, "java:type:Shared" + i, EdgeKind.DEPENDS_ON));
                    graph.addEdge(Edge.of("java:type:Shared" + i, "java:type:Shared0", EdgeKind.DEPENDS_ON));
                    graph.report(Diagnostic.info("X", "m", null));
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(graph.nodes()).hasSize(8 * 500 + 500);
        assertThat(graph.edges()).hasSize(8 * 500 + 500);
        assertThat(graph.diagnostics()).hasSize(4000);
    }

    @Test
    @DisplayName("노드와 엣지는 불변이고 attrs 값 타입을 검사한다")
    void immutableValues() {
        Node node = Node.builder("db:column:T.C", NodeKind.DB_COLUMN).name("C")
                .attr("ordinal", 3L).attr("nullable", true).attr("tags", List.of("a", "b")).attr("skipped", null).build();
        assertThat(node.attrs()).containsEntry("ordinal", 3).containsEntry("nullable", true).doesNotContainKey("skipped");
        assertThat(node.attrInt("ordinal", 0)).isEqualTo(3);
        assertThat(node.attrBoolean("nullable")).isTrue();
        assertThat(node.attrList("tags")).containsExactly("a", "b");
        assertThat(node.attrString("none")).isNull();
        assertThat(node.qualifiedName()).isEqualTo("C");
        assertThatThrownBy(() -> node.attrs().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Node.builder("x:y", NodeKind.FILE).attr("bad", new Object()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Edge.builder("a", "b", EdgeKind.CALLS).attr("bad", List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);

        Edge edge = Edge.of("a", "b", EdgeKind.CALLS);
        assertThat(edge.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(edge.withConfidence(Confidence.INFERRED).confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(edge.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(node.withAttr("dataType", "NUMBER").attrs()).containsEntry("dataType", "NUMBER");
        assertThat(node.attrs()).doesNotContainKey("dataType");
    }
}
