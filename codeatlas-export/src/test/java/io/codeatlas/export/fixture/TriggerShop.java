package io.codeatlas.export.fixture;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import java.util.List;

/**
 * 한 테이블에 사건이 다른 트리거가 둘 걸린 그래프(샘플 프로젝트의 TRG_ORDER_STATUS_HIST를 본뜬 것).
 *
 * <pre>
 * [POST /order/save]   → OrderController#save   → OrderMapper#insertOrder       → insertOrder(INSERT TB_ORDER)
 * [POST /order/status] → OrderController#status → OrderMapper#updateOrderStatus → updateOrderStatus(UPDATE TB_ORDER.ORDER_STATUS)
 * TRG_ORDER_STATUS_HIST(AFTER UPDATE OF ORDER_STATUS ON TB_ORDER) ∋ 구문(INSERT TB_ORDER_HIST), 구문(INSERT TB_EVENT)
 * TRG_ORDER_NEW(AFTER INSERT ON TB_ORDER) ∋ 구문(INSERT TB_EVENT)
 * </pre>
 */
public final class TriggerShop {

    public static final String CONTROLLER = "com.example.shop.order.web.OrderController";
    public static final String MAPPER = "com.example.shop.order.mapper.OrderMapper";

    public final GraphFixture f = GraphFixture.create();
    public final String saveEndpoint;
    public final String statusEndpoint;
    public final String insertOrder;
    public final String updateOrderStatus;
    public final String statusTrigger;
    public final String newTrigger;

    public TriggerShop() {
        f.table("TB_ORDER");
        f.column("TB_ORDER", "ORDER_ID", "NUMBER", 1, "pk");
        f.column("TB_ORDER", "ORDER_STATUS", "VARCHAR2(10)", 2);
        f.table("TB_ORDER_HIST");
        f.table("TB_EVENT");
        f.type(CONTROLLER, "class", "public");
        f.attr(f.type(MAPPER, "interface", "public"), "stereotype", "mapper");
        String save = f.method(CONTROLLER, "save");
        String status = f.method(CONTROLLER, "status");
        String mapperInsert = f.method(MAPPER, "insertOrder", List.of(), "int");
        String mapperUpdate = f.method(MAPPER, "updateOrderStatus", List.of(), "int");
        f.calls(save, mapperInsert);
        f.calls(status, mapperUpdate);
        saveEndpoint = f.endpoint("POST", "/order/save", save);
        statusEndpoint = f.endpoint("POST", "/order/status", status);
        insertOrder = f.statement(MAPPER, "insertOrder", "INSERT");
        updateOrderStatus = f.statement(MAPPER, "updateOrderStatus", "UPDATE");
        f.writes(insertOrder, "TB_ORDER", "INSERT", "ORDER_ID", "ORDER_STATUS");
        f.writes(updateOrderStatus, "TB_ORDER", "UPDATE", "ORDER_STATUS").readsColumn(updateOrderStatus, "TB_ORDER", "ORDER_ID");
        f.edge(mapperInsert, insertOrder, EdgeKind.EXECUTES);
        f.edge(mapperUpdate, updateOrderStatus, EdgeKind.EXECUTES);

        statusTrigger = f.trigger("TRG_ORDER_STATUS_HIST", "TB_ORDER", "AFTER UPDATE OF ORDER_STATUS");
        f.writes(f.routineStatement(statusTrigger, 7, "INSERT"), "TB_ORDER_HIST", "INSERT");
        f.writes(f.routineStatement(statusTrigger, 9, "INSERT"), "TB_EVENT", "INSERT");
        newTrigger = f.trigger("TRG_ORDER_NEW", "TB_ORDER", "AFTER INSERT");
        f.writes(f.routineStatement(newTrigger, 4, "INSERT"), "TB_EVENT", "INSERT");
    }

    public static String table(String name) {
        return Ids.table(name);
    }
}
