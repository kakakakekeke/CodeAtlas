package io.codeatlas.export.fixture;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import java.util.List;
import java.util.Map;

/**
 * 화면부터 테이블까지 이어진 샘플 그래프. 콜트리, 영향도, CRUD 매트릭스, 표준 산출물 테스트가 함께 쓴다.
 *
 * <pre>
 * list.jsp ─REQUESTS→ GET /orders/{*} ─MATCHES→ [GET /orders/{id}] ─ROUTES_TO→ OrderController#detail
 * list.jsp ─INCLUDES→ (인라인 모듈) ∋ loadOrder ─REQUESTS→ POST /orders ─MATCHES→ [POST /orders] → OrderController#create
 * OrderDetail(jsx) ─CALLS→ fetchOrder(js) ─REQUESTS→ GET /orders/{*} ─MATCHES→ [GET /orders/{id}]
 * OrderController#detail ─CALLS→ OrderService#find(인터페이스) ⇐OVERRIDES─ OrderServiceImpl#find, LegacyOrderService#find
 * OrderServiceImpl#find ─CALLS→ OrderMapper#selectOrder ─EXECUTES→ selectOrder(SELECT) ─READS→ ORDERS, CUSTOMER
 * OrderServiceImpl#create ─CALLS→ OrderMapper#insertOrder → insertOrder(INSERT ORDERS), OrderMapper#mergeStock → mergeStock(MERGE STOCK)
 * AuditHelper#log ~CALLS(INFERRED)→ AuditMapper#insertLog → insertLog(INSERT AUDIT_LOG)
 * TRG_ORDER(AFTER INSERT OR UPDATE) ─TRIGGERS_ON→ ORDERS, TRG_ORDER ∋ 구문(INSERT ORDER_HIST)
 * dailyJob ∋ sumStep ─USES→ SumTasklet ∋ execute ─EXECUTES→ SP_DAILY_SUM ∋ 구문(READS ORDERS, MERGE DAILY_SALES)
 * sumStep ─NEXT→ cleanStep ─EXECUTES→ deleteOld(DELETE AUDIT_LOG)
 * </pre>
 */
public final class ShopGraph {

    public static final String LIST_JSP = "src/main/webapp/WEB-INF/views/order/list.jsp";
    public static final String DETAIL_JSP = "src/main/webapp/WEB-INF/views/order/detail.jsp";
    public static final String ORDER_API = "frontend/src/api/orderApi.js";
    public static final String ORDER_DETAIL = "frontend/src/pages/OrderDetail.jsx";

    public static final String CONTROLLER = "com.shop.order.OrderController";
    public static final String SERVICE = "com.shop.order.OrderService";
    public static final String SERVICE_IMPL = "com.shop.order.OrderServiceImpl";
    public static final String LEGACY = "com.shop.order.LegacyOrderService";
    public static final String MAPPER = "com.shop.order.OrderMapper";
    public static final String AUDIT = "com.shop.common.AuditHelper";
    public static final String AUDIT_MAPPER = "com.shop.common.AuditMapper";
    public static final String TASKLET = "com.shop.batch.SumTasklet";

    public final GraphFixture f = GraphFixture.create();

    public final String getOrder;
    public final String postOrder;
    public final String controllerDetail;
    public final String controllerCreate;
    public final String serviceFind;
    public final String serviceCreate;
    public final String implFind;
    public final String implCreate;
    public final String legacyFind;
    public final String mapperSelect;
    public final String mapperInsert;
    public final String mapperUpdate;
    public final String mapperMerge;
    public final String mapperOrphan;
    public final String auditLog;
    public final String auditInsert;
    public final String stmtSelect;
    public final String stmtInsert;
    public final String stmtUpdate;
    public final String stmtMerge;
    public final String stmtAudit;
    public final String stmtDeleteOld;
    public final String stmtUnused;
    public final String trigger;
    public final String triggerStmt;
    public final String procedure;
    public final String procRead;
    public final String procMerge;
    public final String job;
    public final String sumStep;
    public final String cleanStep;
    public final String taskletExecute;
    public final String listJsp;
    public final String detailJsp;
    public final String inlineModule;
    public final String loadOrder;
    public final String fetchOrder;
    public final String orderDetailComponent;
    public final String externalAdd;

    public ShopGraph() {
        // ---- DB ----
        f.table("ORDERS", "주문");
        f.column("ORDERS", "ORDER_ID", "NUMBER(10)", 1, "pk", "notnull", "comment=주문 ID");
        f.column("ORDERS", "CUSTOMER_ID", "NUMBER(10)", 2, "notnull");
        f.column("ORDERS", "STATUS", "VARCHAR2(20)", 3, "nullable", "comment=주문 상태");
        f.table("CUSTOMER", "고객");
        f.column("CUSTOMER", "CUSTOMER_ID", "NUMBER(10)", 1, "pk", "notnull");
        f.column("CUSTOMER", "NAME", "VARCHAR2(100)", 2, "notnull");
        f.table("STOCK");
        f.column("STOCK", "PRODUCT_ID", "NUMBER(10)", 1, "pk", "notnull");
        f.table("AUDIT_LOG");
        f.table("ORDER_HIST");
        f.table("NEVER_USED");
        f.fk("ORDERS", "CUSTOMER", "FK_ORDERS_CUSTOMER", List.of("CUSTOMER_ID"), List.of("CUSTOMER_ID"));
        f.inferredFk("ORDER_HIST", "ORDERS", List.of("ORDER_ID"), List.of("ORDER_ID"), 2, true);

        // ---- Java ----
        f.attr(f.type(CONTROLLER, "class", "public"), "stereotype", "controller");
        f.type(SERVICE, "interface", "public");
        f.attr(f.type(SERVICE_IMPL, "class", "public"), "stereotype", "service");
        f.type(LEGACY, "class", "public");
        f.attr(f.type(MAPPER, "interface", "public"), "stereotype", "mapper");
        f.type(AUDIT, "class", "public");
        f.attr(f.type(AUDIT_MAPPER, "interface", "public"), "stereotype", "mapper");
        f.type(TASKLET, "class", "public");
        f.typeEdge(SERVICE_IMPL, SERVICE, EdgeKind.IMPLEMENTS);
        f.typeEdge(LEGACY, SERVICE, EdgeKind.IMPLEMENTS);
        f.edge(Ids.javaType(SERVICE_IMPL), Ids.javaType(MAPPER), EdgeKind.ASSOCIATES, Map.of("field", "orderMapper", "multiplicity", "1"));
        f.typeEdge(CONTROLLER, SERVICE, EdgeKind.DEPENDS_ON);

        controllerDetail = f.method(CONTROLLER, "detail", List.of("java.lang.Long"), "java.lang.String", "public");
        controllerCreate = f.method(CONTROLLER, "create", List.of("com.shop.order.OrderDto"), "java.lang.String", "public");
        serviceFind = f.method(SERVICE, "find", List.of("java.lang.Long"), "com.shop.order.OrderDto", "public", "abstract");
        serviceCreate = f.method(SERVICE, "create", List.of("com.shop.order.OrderDto"), "void", "public", "abstract");
        implFind = f.method(SERVICE_IMPL, "find", List.of("java.lang.Long"), "com.shop.order.OrderDto", "public");
        implCreate = f.method(SERVICE_IMPL, "create", List.of("com.shop.order.OrderDto"), "void", "public");
        legacyFind = f.method(LEGACY, "find", List.of("java.lang.Long"), "com.shop.order.OrderDto", "public");
        mapperSelect = f.method(MAPPER, "selectOrder", List.of("java.lang.Long"), "com.shop.order.OrderDto", "public", "abstract");
        mapperInsert = f.method(MAPPER, "insertOrder", List.of("com.shop.order.OrderDto"), "int", "public", "abstract");
        mapperUpdate = f.method(MAPPER, "updateStatus", List.of("java.lang.Long", "java.lang.String"), "int", "public", "abstract");
        mapperMerge = f.method(MAPPER, "mergeStock", List.of("java.lang.Long"), "int", "public", "abstract");
        mapperOrphan = f.method(MAPPER, "noStatement", List.of(), "int", "public", "abstract");
        auditLog = f.method(AUDIT, "log", List.of("java.lang.String"), "void", "public");
        auditInsert = f.method(AUDIT_MAPPER, "insertLog", List.of("java.lang.String"), "int", "public", "abstract");
        taskletExecute = f.method(TASKLET, "execute", List.of(), "void", "public");
        f.method(TASKLET, "<init>", List.of(), null, "public");
        externalAdd = f.externalMethod("java.util.List", "add", "java.lang.Object");
        f.externalType("java.util.List", "interface");

        f.edge(implFind, serviceFind, EdgeKind.OVERRIDES);
        f.edge(implCreate, serviceCreate, EdgeKind.OVERRIDES);
        f.edge(legacyFind, serviceFind, EdgeKind.OVERRIDES);
        f.edge(Edge.builder(controllerDetail, serviceFind, EdgeKind.CALLS).attr("callType", "interface")
                .location(SourceLocation.of("src/main/java/com/shop/order/OrderController.java", 31)).build());
        f.calls(controllerCreate, serviceCreate);
        f.calls(implFind, mapperSelect);
        f.calls(implFind, auditLog);
        f.calls(implCreate, mapperInsert);
        f.calls(implCreate, mapperMerge);
        f.calls(implCreate, auditLog);
        f.calls(implCreate, externalAdd);
        f.graph().addUnresolvedEdge(implCreate, EdgeKind.CALLS, "method", "helper.unknown()",
                SourceLocation.of("src/main/java/com/shop/order/OrderServiceImpl.java", 77));
        f.calls(legacyFind, mapperSelect);
        f.edge(Edge.builder(auditLog, auditInsert, EdgeKind.CALLS).inferred()
                .location(SourceLocation.of("src/main/java/com/shop/common/AuditHelper.java", 15)).build());

        // ---- 엔드포인트와 화면 ----
        getOrder = f.endpoint("GET", "/orders/{id}", controllerDetail);
        postOrder = f.endpoint("POST", "/orders", controllerCreate);
        listJsp = f.jsp(LIST_JSP);
        detailJsp = f.jsp(DETAIL_JSP);
        f.edge(controllerDetail, detailJsp, EdgeKind.RENDERS);
        f.request("GET", "/orders/{*}", LIST_JSP, 20, listJsp, getOrder);
        inlineModule = f.jsModule(LIST_JSP);
        f.edge(listJsp, inlineModule, EdgeKind.INCLUDES);
        loadOrder = f.jsFunction(LIST_JSP, "loadOrder", 30);
        f.request("POST", "/orders", LIST_JSP, 32, loadOrder, postOrder);
        f.jsModule(ORDER_API);
        fetchOrder = f.jsFunction(ORDER_API, "fetchOrder", 4);
        f.request("GET", "/orders/{*}", ORDER_API, 5, fetchOrder, getOrder);
        f.jsModule(ORDER_DETAIL);
        orderDetailComponent = f.jsFunction(ORDER_DETAIL, "OrderDetail", 8);
        f.edge(Edge.of(orderDetailComponent, fetchOrder, EdgeKind.CALLS, SourceLocation.of(ORDER_DETAIL, 12)));
        f.request("GET", "/unknown/api", ORDER_API, 40, fetchOrder, null);

        // ---- 매퍼 구문 ----
        stmtSelect = f.statement(MAPPER, "selectOrder", "SELECT");
        stmtInsert = f.statement(MAPPER, "insertOrder", "INSERT");
        stmtUpdate = f.statement(MAPPER, "updateStatus", "UPDATE");
        stmtMerge = f.statement(MAPPER, "mergeStock", "MERGE");
        stmtAudit = f.statement(AUDIT_MAPPER, "insertLog", "INSERT");
        stmtDeleteOld = f.statement("com.shop.batch.CleanMapper", "deleteOld", "DELETE");
        stmtUnused = f.statement(MAPPER, "unusedStatement", "SELECT");
        f.reads(stmtSelect, "ORDERS", "ORDER_ID", "STATUS").reads(stmtSelect, "CUSTOMER", "NAME");
        f.writes(stmtInsert, "ORDERS", "INSERT", "ORDER_ID", "STATUS");
        f.writes(stmtUpdate, "ORDERS", "UPDATE", "STATUS").reads(stmtUpdate, "ORDERS", "ORDER_ID");
        f.writes(stmtMerge, "STOCK", "MERGE");
        f.writes(stmtAudit, "AUDIT_LOG", "INSERT");
        f.writes(stmtDeleteOld, "AUDIT_LOG", "DELETE");
        f.reads(stmtUnused, "TMP_UNDECLARED", "X");
        f.edge(mapperSelect, stmtSelect, EdgeKind.EXECUTES);
        f.edge(mapperInsert, stmtInsert, EdgeKind.EXECUTES);
        f.edge(mapperUpdate, stmtUpdate, EdgeKind.EXECUTES);
        f.edge(mapperMerge, stmtMerge, EdgeKind.EXECUTES);
        f.edge(auditInsert, stmtAudit, EdgeKind.EXECUTES);

        // ---- 루틴과 트리거 ----
        trigger = f.trigger("TRG_ORDER", "ORDERS", "AFTER INSERT OR UPDATE");
        triggerStmt = f.routineStatement(trigger, 5, "INSERT");
        f.writes(triggerStmt, "ORDER_HIST", "INSERT");
        procedure = f.routine("SP_DAILY_SUM", "PROCEDURE");
        procRead = f.routineStatement(procedure, 10, "SELECT");
        procMerge = f.routineStatement(procedure, 20, "MERGE");
        f.reads(procRead, "ORDERS", "STATUS");
        f.writes(procMerge, "DAILY_SALES", "MERGE");

        // ---- 배치 ----
        job = f.job("dailyJob");
        sumStep = f.step("dailyJob", "sumStep");
        cleanStep = f.step("dailyJob", "cleanStep");
        f.edge(sumStep, Ids.javaType(TASKLET), EdgeKind.USES, Map.of("role", "tasklet"));
        f.edge(sumStep, cleanStep, EdgeKind.NEXT);
        f.edge(taskletExecute, procedure, EdgeKind.EXECUTES);
        f.edge(cleanStep, stmtDeleteOld, EdgeKind.EXECUTES);

        // ---- 진단 ----
        f.graph().report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "구문을 건너뜀", SourceLocation.of("broken/BrokenMapper.xml", 3)));
        f.graph().report(Diagnostic.info(DiagnosticCodes.DYNAMIC_SQL, "동적 테이블 이름", SourceLocation.of("src/main/resources/mapper/OrderMapper.xml", 40)));
        f.graph().report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "파일을 읽지 못함", null));
    }
}
