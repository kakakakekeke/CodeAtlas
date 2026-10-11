package io.codeatlas.cli;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 조회 명령 테스트용 그래프. 그래프 계약대로 노드와 엣지를 손으로 넣는다(분석기를 돌리지 않는다).
 *
 * <pre>
 * [GET /orders/{id}] ─ROUTES_TO→ OrderController#detail ─CALLS→ OrderService#find ─CALLS→ OrderMapper#selectOrder
 *   ─EXECUTES→ selectOrder(SELECT) ─READS→ ORDERS(STATUS), CUSTOMER
 * OrderService#create ─CALLS→ OrderMapper#insertOrder ─EXECUTES→ insertOrder(INSERT) ─WRITES→ ORDERS
 * AuditHelper#find (이름이 겹치는 메서드: 검색어 find의 후보가 둘이 된다)
 * ORDERS ─REFERENCES(DDL)→ CUSTOMER, ORDER_HIST ~REFERENCES(추론)→ ORDERS, 뷰 V_ORDERS
 * </pre>
 */
final class ShopGraphFixture {

    static final String CONTROLLER = "com.shop.order.OrderController";
    static final String SERVICE = "com.shop.order.OrderService";
    static final String MAPPER = "com.shop.order.OrderMapper";
    static final String AUDIT = "com.shop.common.AuditHelper";

    static final String ENDPOINT = Ids.httpEndpoint("GET", "/orders/{id}");
    static final String CONTROLLER_DETAIL = Ids.javaMethod(CONTROLLER, "detail", "java.lang.Long");
    static final String SERVICE_FIND = Ids.javaMethod(SERVICE, "find", "java.lang.Long");
    static final String SERVICE_CREATE = Ids.javaMethod(SERVICE, "create", "java.lang.String");
    static final String AUDIT_FIND = Ids.javaMethod(AUDIT, "find");
    static final String MAPPER_SELECT = Ids.javaMethod(MAPPER, "selectOrder", "java.lang.Long");
    static final String MAPPER_INSERT = Ids.javaMethod(MAPPER, "insertOrder", "java.lang.String");
    static final String STMT_SELECT = Ids.mybatisStatement(MAPPER, "selectOrder");
    static final String STMT_INSERT = Ids.mybatisStatement(MAPPER, "insertOrder");

    private final GraphModel graph;

    private ShopGraphFixture() {
        this(new GraphModel());
    }

    private ShopGraphFixture(GraphModel graph) {
        this.graph = graph;
    }

    /** 화면 없는 작은 주문 시스템 그래프. */
    static GraphModel shop() {
        ShopGraphFixture f = new ShopGraphFixture();
        f.graph.project(new ProjectInfo("shop", "/work/shop", Instant.parse("2026-10-11T00:00:00Z")));

        f.table("ORDERS", "주문");
        f.column("ORDERS", "ORDER_ID", "NUMBER(10)", 1, true);
        f.column("ORDERS", "CUSTOMER_ID", "NUMBER(10)", 2, false);
        f.column("ORDERS", "STATUS", "VARCHAR2(20)", 3, false);
        f.table("CUSTOMER", "고객");
        f.column("CUSTOMER", "CUSTOMER_ID", "NUMBER(10)", 1, true);
        f.column("CUSTOMER", "NAME", "VARCHAR2(100)", 2, false);
        f.table("ORDER_HIST", null);
        f.column("ORDER_HIST", "ORDER_ID", "NUMBER(10)", 1, false);
        f.graph.declare(Node.builder(Ids.view("V_ORDERS"), NodeKind.DB_VIEW).name("V_ORDERS")
                .location(SourceLocation.of("db/views.sql", 1)).build());
        f.graph.addEdge(Edge.builder(Ids.table("ORDERS"), Ids.table("CUSTOMER"), EdgeKind.REFERENCES)
                .attr("columns", List.of("CUSTOMER_ID")).attr("refColumns", List.of("CUSTOMER_ID"))
                .attr("constraint", "FK_ORDERS_CUSTOMER").attr("source", "DDL")
                .location(SourceLocation.of("db/tables.sql", 20)).build());
        f.graph.addEdge(Edge.builder(Ids.table("ORDER_HIST"), Ids.table("ORDERS"), EdgeKind.REFERENCES).inferred()
                .attr("columns", List.of("ORDER_ID")).attr("refColumns", List.of("ORDER_ID"))
                .attr("source", "INFERRED").attr("evidenceCount", 1).attr("directionKnown", true).build());

        f.type(CONTROLLER, "class");
        f.type(SERVICE, "class");
        f.type(MAPPER, "interface");
        f.type(AUDIT, "class");
        f.graph.addEdge(Edge.of(Ids.javaType(CONTROLLER), Ids.javaType(SERVICE), EdgeKind.DEPENDS_ON));
        f.graph.addEdge(Edge.builder(Ids.javaType(SERVICE), Ids.javaType(MAPPER), EdgeKind.ASSOCIATES)
                .attr("field", "orderMapper").attr("multiplicity", "1").build());
        f.method(CONTROLLER, "detail", List.of("java.lang.Long"), "java.lang.String", 12);
        f.method(SERVICE, "find", List.of("java.lang.Long"), "java.lang.String", 21);
        f.method(SERVICE, "create", List.of("java.lang.String"), "void", 30);
        f.method(MAPPER, "selectOrder", List.of("java.lang.Long"), "java.lang.String", 7);
        f.method(MAPPER, "insertOrder", List.of("java.lang.String"), "int", 9);
        f.method(AUDIT, "find", List.of(), "void", 5);
        f.calls(CONTROLLER_DETAIL, SERVICE_FIND, 14);
        f.calls(SERVICE_FIND, MAPPER_SELECT, 23);
        f.calls(SERVICE_CREATE, MAPPER_INSERT, 32);

        f.graph.declare(Node.builder(ENDPOINT, NodeKind.HTTP_ENDPOINT).name("GET /orders/{id}")
                .attr("httpMethod", "GET").attr("path", "/orders/{id}")
                .location(SourceLocation.of(sourceFile(CONTROLLER), 12)).build());
        f.graph.addEdge(Edge.of(ENDPOINT, CONTROLLER_DETAIL, EdgeKind.ROUTES_TO));

        f.statement("selectOrder", "SELECT", 5);
        f.statement("insertOrder", "INSERT", 12);
        f.graph.addEdge(Edge.of(MAPPER_SELECT, STMT_SELECT, EdgeKind.EXECUTES));
        f.graph.addEdge(Edge.of(MAPPER_INSERT, STMT_INSERT, EdgeKind.EXECUTES));
        f.access(STMT_SELECT, EdgeKind.READS, null, Ids.table("ORDERS"));
        f.access(STMT_SELECT, EdgeKind.READS, null, Ids.column("ORDERS", "STATUS"));
        f.access(STMT_SELECT, EdgeKind.READS, null, Ids.table("CUSTOMER"));
        f.access(STMT_INSERT, EdgeKind.WRITES, "INSERT", Ids.table("ORDERS"));

        f.graph.report(Diagnostic.warn("PARSE_FAILED", "구문을 건너뜀", SourceLocation.of("broken/BrokenMapper.xml", 3)));
        return f.graph;
    }

    static final String VO = "com.shop.order.OrderVO";
    static final String VO_GET_NAME = Ids.javaMethod(VO, "getName");
    static final String CONTROLLER_SAVE = Ids.javaMethod(CONTROLLER, "save", "java.lang.String");
    static final String ENDPOINT_SAVE = Ids.httpEndpoint("POST", "/orders");
    static final String DETAIL_PAGE = Ids.jspPage("src/main/webapp/WEB-INF/views/order/detail.jsp");
    static final String DATA_LOAD = Ids.sqlStatement("db/data.sql", 1);
    static final String EXTERNAL_TRIM = Ids.javaMethod("org.lib.Strings", "trim", "java.lang.String");
    static final String REQUEST_SAVE = Ids.httpRequest("POST", "/orders", "src/main/webapp/WEB-INF/views/order/detail.jsp", 10);

    /**
     * {@link #shop()}에 화면, 접근자 호출, 데이터 적재 구문을 더한 그래프(콜트리·CRUD 옵션의 기본값 확인용).
     *
     * <pre>
     * OrderController#detail ─CALLS→ OrderVO#getName (접근자: 필드 name), ─CALLS→ org.lib.Strings#trim (소스 없는 메서드),
     *   ─RENDERS→ detail.jsp ─REQUESTS→ [POST /orders 요청]
     *   ─MATCHES→ [POST /orders] ─ROUTES_TO→ OrderController#save ─CALLS→ OrderService#create
     * db/data.sql:1 (dataLoad=true, rowCount=120) ─WRITES→ ORDER_HIST
     * </pre>
     */
    static GraphModel screens() {
        GraphModel graph = shop();
        ShopGraphFixture f = new ShopGraphFixture(graph);
        f.type(VO, "class");
        f.method(VO, "getName", List.of(), "java.lang.String", 8);
        graph.declare(Node.builder(Ids.javaField(VO, "name"), NodeKind.JAVA_FIELD).name("name").qualifiedName(VO + "#name")
                .location(SourceLocation.of(sourceFile(VO), 5)).attr("type", "java.lang.String").build());
        graph.addEdge(Edge.of(Ids.javaType(VO), Ids.javaField(VO, "name"), EdgeKind.CONTAINS));
        // 소스 없는 라이브러리 메서드(참조만 된 노드)
        graph.reference(EXTERNAL_TRIM, NodeKind.JAVA_METHOD, "trim");
        graph.addEdge(Edge.builder(CONTROLLER_DETAIL, EXTERNAL_TRIM, EdgeKind.CALLS)
                .location(SourceLocation.of(sourceFile(CONTROLLER), 13)).build());
        f.method(CONTROLLER, "save", List.of("java.lang.String"), "java.lang.String", 20);
        f.calls(CONTROLLER_DETAIL, VO_GET_NAME, 15);
        f.calls(CONTROLLER_SAVE, SERVICE_CREATE, 22);

        String jsp = "src/main/webapp/WEB-INF/views/order/detail.jsp";
        graph.declare(Node.builder(DETAIL_PAGE, NodeKind.JSP_PAGE).name("detail.jsp").qualifiedName(jsp)
                .location(SourceLocation.ofFile(jsp)).attr("pageType", "jsp").build());
        graph.addEdge(Edge.builder(CONTROLLER_DETAIL, DETAIL_PAGE, EdgeKind.RENDERS).attr("viewName", "order/detail")
                .location(SourceLocation.of(sourceFile(CONTROLLER), 16)).build());
        String request = REQUEST_SAVE;
        graph.declare(Node.builder(request, NodeKind.HTTP_REQUEST).name("POST /orders").location(SourceLocation.of(jsp, 10))
                .attr("httpMethod", "POST").attr("path", "/orders").attr("url", "${ctx}/orders").attr("api", "form").build());
        graph.addEdge(Edge.builder(DETAIL_PAGE, request, EdgeKind.REQUESTS).location(SourceLocation.of(jsp, 10)).build());
        graph.declare(Node.builder(ENDPOINT_SAVE, NodeKind.HTTP_ENDPOINT).name("POST /orders")
                .attr("httpMethod", "POST").attr("path", "/orders")
                .location(SourceLocation.of(sourceFile(CONTROLLER), 20)).build());
        graph.addEdge(Edge.builder(request, ENDPOINT_SAVE, EdgeKind.MATCHES).location(SourceLocation.of(jsp, 10)).build());
        graph.addEdge(Edge.of(ENDPOINT_SAVE, CONTROLLER_SAVE, EdgeKind.ROUTES_TO));

        graph.declare(Node.builder(DATA_LOAD, NodeKind.SQL_STATEMENT).name("data.sql:1").qualifiedName("db/data.sql:1")
                .location(SourceLocation.of("db/data.sql", 1))
                .attr("statementType", "INSERT").attr("dataLoad", true).attr("rowCount", 120).build());
        f.access(DATA_LOAD, EdgeKind.WRITES, "INSERT", Ids.table("ORDER_HIST"));
        return graph;
    }

    static final String ENDPOINT_FILE_DOWN = Ids.httpEndpoint("ANY", "/cmm/fms/FileDown.do");
    static final String ENDPOINT_USER_LIST = Ids.httpEndpoint("ANY", "/${adminPath}/sys/user/list");
    static final String ENDPOINT_DELETE = Ids.httpEndpoint("DELETE", "/orders/{id}");
    static final String ENDPOINT_NEW = Ids.httpEndpoint("GET", "/orders/new");
    static final String ENDPOINT_ITEM = Ids.httpEndpoint("GET", "/orders/{id}/items/{itemId:[0-9]+}");
    static final String ENDPOINT_HEALTH_GET = Ids.httpEndpoint("GET", "/health");
    static final String FILE_LIST_PAGE = "src/main/webapp/WEB-INF/jsp/cmm/fms/EgovFileList.jsp";
    static final String REQUEST_FILE_DOWN = Ids.httpRequest("GET", "/cmm/fms/FileDown.do", FILE_LIST_PAGE, 33);

    /**
     * {@link #screens()}에 URL로 찾을 엔드포인트를 더한 그래프(F-001-R49).
     *
     * <pre>
     * [ANY /cmm/fms/FileDown.do] ─ROUTES_TO→ AuditHelper#find, 그 엔드포인트로 가는 화면 요청 둘(GET /cmm/fms/FileDown.do)
     * [ANY /${adminPath}/sys/user/list] (resolvedPath=/a/sys/user/list)
     * [DELETE /orders/{id}], [GET /orders/new], [GET /orders/{id}/items/{itemId:[0-9]+}]  (shop의 [GET /orders/{id}]와 함께)
     * [GET /health], [ANY /health]  (같은 경로에 메서드가 정해진 엔드포인트와 ANY 엔드포인트)
     * </pre>
     */
    static GraphModel urls() {
        GraphModel graph = screens();
        ShopGraphFixture f = new ShopGraphFixture(graph);
        f.endpoint(ENDPOINT_FILE_DOWN, "ANY", "/cmm/fms/FileDown.do", null);
        graph.addEdge(Edge.of(ENDPOINT_FILE_DOWN, AUDIT_FIND, EdgeKind.ROUTES_TO));
        for (int line : List.of(33, 57)) {
            String request = Ids.httpRequest("GET", "/cmm/fms/FileDown.do", FILE_LIST_PAGE, line);
            graph.declare(Node.builder(request, NodeKind.HTTP_REQUEST).name("GET /cmm/fms/FileDown.do")
                    .location(SourceLocation.of(FILE_LIST_PAGE, line))
                    .attr("httpMethod", "GET").attr("path", "/cmm/fms/FileDown.do").attr("api", "link").build());
            graph.addEdge(Edge.builder(request, ENDPOINT_FILE_DOWN, EdgeKind.MATCHES).build());
        }
        f.endpoint(ENDPOINT_USER_LIST, "ANY", "/${adminPath}/sys/user/list", "/a/sys/user/list");
        f.endpoint(ENDPOINT_DELETE, "DELETE", "/orders/{id}", null);
        f.endpoint(ENDPOINT_NEW, "GET", "/orders/new", null);
        f.endpoint(ENDPOINT_ITEM, "GET", "/orders/{id}/items/{itemId:[0-9]+}", null);
        f.endpoint(ENDPOINT_HEALTH_GET, "GET", "/health", null);
        f.endpoint(Ids.httpEndpoint("ANY", "/health"), "ANY", "/health", null);
        return graph;
    }

    private void endpoint(String id, String method, String path, String resolvedPath) {
        graph.declare(Node.builder(id, NodeKind.HTTP_ENDPOINT).name(method + " " + path)
                .attr("httpMethod", method).attr("path", path).attr("resolvedPath", resolvedPath)
                .location(SourceLocation.of(sourceFile(CONTROLLER), 40)).build());
    }

    /** DB 노드가 하나도 없는 그래프(sql 분석기가 없을 때의 모양). Java 타입만 있다. */
    static GraphModel javaOnly() {
        ShopGraphFixture f = new ShopGraphFixture();
        f.graph.project(new ProjectInfo("plain", "/work/plain", Instant.parse("2026-10-11T00:00:00Z")));
        f.type("com.plain.Tool", "class");
        f.method("com.plain.Tool", "run", List.of(), "void", 3);
        return f.graph;
    }

    /** 노드가 하나도 없는 그래프. */
    static GraphModel empty() {
        GraphModel graph = new GraphModel();
        graph.project(new ProjectInfo("empty", "/work/empty", Instant.parse("2026-10-11T00:00:00Z")));
        return graph;
    }

    /** 그래프를 {@code dir/graph.json}에 저장하고 그 파일 경로를 돌려준다. */
    static Path save(GraphModel graph, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("graph.json");
        GraphJson.write(graph, file);
        return file;
    }

    private void table(String name, String comment) {
        graph.declare(Node.builder(Ids.table(name), NodeKind.DB_TABLE).name(name)
                .location(SourceLocation.of("db/tables.sql", 1)).attr("comment", comment).build());
    }

    private void column(String table, String name, String dataType, int ordinal, boolean primaryKey) {
        String id = Ids.column(table, name);
        graph.declare(Node.builder(id, NodeKind.DB_COLUMN).name(name).attr("dataType", dataType).attr("ordinal", ordinal)
                .attr("primaryKey", primaryKey ? Boolean.TRUE : null).attr("nullable", !primaryKey).build());
        graph.addEdge(Edge.of(Ids.table(table), id, EdgeKind.CONTAINS));
    }

    private void type(String binaryName, String typeKind) {
        String id = Ids.javaType(binaryName);
        String pkg = binaryName.substring(0, binaryName.lastIndexOf('.'));
        graph.declare(Node.builder(id, NodeKind.JAVA_TYPE).name(binaryName.substring(pkg.length() + 1)).qualifiedName(binaryName)
                .location(SourceLocation.of(sourceFile(binaryName), 3))
                .attr("typeKind", typeKind).attr("modifiers", List.of("public")).build());
        graph.declare(Node.of(Ids.javaPackage(pkg), NodeKind.JAVA_PACKAGE, pkg));
        graph.addEdge(Edge.of(Ids.javaPackage(pkg), id, EdgeKind.CONTAINS));
    }

    private void method(String type, String name, List<String> parameterTypes, String returnType, int line) {
        String id = Ids.javaMethod(type, name, parameterTypes);
        graph.declare(Node.builder(id, NodeKind.JAVA_METHOD).name(name)
                .qualifiedName(type + "#" + name + "(" + String.join(",", parameterTypes) + ")")
                .location(SourceLocation.of(sourceFile(type), line))
                .attr("modifiers", List.of("public")).attr("returnType", returnType).attr("parameterTypes", parameterTypes).build());
        graph.addEdge(Edge.of(Ids.javaType(type), id, EdgeKind.CONTAINS));
    }

    private void calls(String from, String to, int line) {
        graph.addEdge(Edge.builder(from, to, EdgeKind.CALLS)
                .location(SourceLocation.of(graph.node(from).orElseThrow().location().file(), line)).build());
    }

    private void statement(String statementId, String statementType, int line) {
        String id = Ids.mybatisStatement(MAPPER, statementId);
        graph.declare(Node.builder(id, NodeKind.SQL_STATEMENT).name(statementId).qualifiedName(MAPPER + "." + statementId)
                .location(SourceLocation.of("src/main/resources/mapper/OrderMapper.xml", line))
                .attr("statementType", statementType).attr("namespace", MAPPER).attr("statementId", statementId).build());
        graph.declare(Node.of(Ids.mybatisMapper(MAPPER), NodeKind.MYBATIS_MAPPER, MAPPER));
        graph.addEdge(Edge.of(Ids.mybatisMapper(MAPPER), id, EdgeKind.CONTAINS));
    }

    private void access(String statementId, EdgeKind kind, String op, String targetId) {
        graph.addEdge(Edge.builder(statementId, targetId, kind).attr("op", op)
                .location(graph.node(statementId).orElseThrow().location()).build());
    }

    private static String sourceFile(String binaryName) {
        return "src/main/java/" + binaryName.replace('.', '/') + ".java";
    }
}
