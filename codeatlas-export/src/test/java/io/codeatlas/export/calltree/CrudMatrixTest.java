package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.export.calltree.CrudMatrix.Layout;
import io.codeatlas.export.calltree.CrudMatrix.Row;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.ShopGraph;
import io.codeatlas.export.fixture.TriggerShop;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CrudMatrixTest {

    @Test
    @DisplayName("F-015-AC13 테이블 × 구문: READS는 R, op에 따라 C/U/D, MERGE는 CU")
    void byStatement() {
        ShopGraph shop = new ShopGraph();
        CrudMatrix m = CrudMatrix.of(shop.f.graph(), Subject.STATEMENT);

        assertThat(m.tables()).containsExactly("AUDIT_LOG", "CUSTOMER", "DAILY_SALES", "ORDERS", "ORDER_HIST", "STOCK", "TMP_UNDECLARED");
        assertThat(m.cell(shop.stmtSelect, "ORDERS")).isEqualTo("R");
        assertThat(m.cell(shop.stmtSelect, "CUSTOMER")).isEqualTo("R");
        assertThat(m.cell(shop.stmtInsert, "ORDERS")).isEqualTo("C");
        assertThat(m.cell(shop.stmtUpdate, "ORDERS")).isEqualTo("RU");
        assertThat(m.cell(shop.stmtMerge, "STOCK")).isEqualTo("CU");
        assertThat(m.cell(shop.stmtDeleteOld, "AUDIT_LOG")).isEqualTo("D");
        assertThat(m.cell(shop.stmtSelect, "STOCK")).isEmpty();
        assertThat(m.rows()).extracting(Row::id).contains(shop.procRead, shop.triggerStmt).isSorted();
    }

    @Test
    @DisplayName("F-015-AC13 op가 없는 WRITES는 statementType으로 정하고, 그래도 모르면 W")
    void writeWithoutOp() {
        GraphFixture f = GraphFixture.create();
        f.table("T");
        String del = f.statement("com.a.M", "del", "DELETE");
        String merge = f.statement("com.a.M", "merge", "MERGE");
        String other = f.statement("com.a.M", "other", "OTHER");
        f.edge(del, Ids.table("T"), EdgeKind.WRITES);
        f.edge(merge, Ids.table("T"), EdgeKind.WRITES);
        f.edge(other, Ids.table("T"), EdgeKind.WRITES);
        f.edge(Edge.builder(other, Ids.table("T"), EdgeKind.READS).build());

        CrudMatrix m = CrudMatrix.of(f.graph(), Subject.STATEMENT);

        assertThat(m.cell(del, "T")).isEqualTo("D");
        assertThat(m.cell(merge, "T")).isEqualTo("CU");
        assertThat(m.cell(other, "T")).isEqualTo("RW");
    }

    @Test
    @DisplayName("F-015-AC13 테이블 × 메서드(구문을 직접 실행하는 메서드), 테이블 × 루틴")
    void byMethodAndRoutine() {
        ShopGraph shop = new ShopGraph();
        GraphModel g = shop.f.graph();

        CrudMatrix methods = CrudMatrix.of(g, Subject.METHOD);
        assertThat(methods.rows()).extracting(Row::id).containsExactly(shop.taskletExecute, shop.auditInsert, shop.mapperInsert,
                shop.mapperMerge, shop.mapperSelect, shop.mapperUpdate);
        assertThat(methods.cell(shop.mapperUpdate, "ORDERS")).isEqualTo("RU");
        assertThat(methods.cell(shop.mapperSelect, "CUSTOMER")).isEqualTo("R");
        // 메서드가 실행하는 루틴 안의 구문도 넣는다(루틴을 거친 접근이라 소문자)
        assertThat(methods.cell(shop.taskletExecute, "ORDERS")).isEqualTo("r");
        assertThat(methods.cell(shop.taskletExecute, "DAILY_SALES")).isEqualTo("cu");
        assertThat(methods.rows().get(1).label()).isEqualTo("com.shop.common.AuditMapper#insertLog(String)");

        CrudMatrix routines = CrudMatrix.of(g, Subject.ROUTINE);
        assertThat(routines.rows()).extracting(Row::label).containsExactly("SP_DAILY_SUM", "TRG_ORDER");
        assertThat(routines.cell(shop.procedure, "ORDERS")).isEqualTo("R");
        assertThat(routines.cell(shop.procedure, "DAILY_SALES")).isEqualTo("CU");
        assertThat(routines.cell(shop.trigger, "ORDER_HIST")).isEqualTo("C");
    }

    @Test
    @DisplayName("F-015-AC13 테이블 × 엔드포인트: 호출 사슬(인터페이스 디스패치, 추론 호출, 트리거 포함)로 닿는 테이블, 깊이 상한")
    void byEndpoint() {
        ShopGraph shop = new ShopGraph();
        GraphModel g = shop.f.graph();

        CrudMatrix m = CrudMatrix.of(g, Subject.ENDPOINT);
        assertThat(m.rows()).extracting(Row::label).containsExactly("GET /orders/{id}", "POST /orders");
        assertThat(m.cell(shop.getOrder, "ORDERS")).isEqualTo("R");
        assertThat(m.cell(shop.getOrder, "CUSTOMER")).isEqualTo("R");
        // AuditHelper#log → AuditMapper#insertLog는 추론 호출이다: 그 길로만 닿는 칸에는 ?가 붙는다(F-015-AC63)
        assertThat(m.cell(shop.getOrder, "AUDIT_LOG")).isEqualTo("C?");
        assertThat(m.cell(shop.getOrder, "STOCK")).isEmpty();
        assertThat(m.cell(shop.postOrder, "ORDERS")).isEqualTo("C");
        assertThat(m.cell(shop.postOrder, "STOCK")).isEqualTo("CU");
        // 트리거 본문의 구문이 하는 접근은 루틴을 거친 것이라 소문자다
        assertThat(m.cell(shop.postOrder, "ORDER_HIST")).isEqualTo("c");
        assertThat(m.cell(shop.postOrder, "CUSTOMER")).isEmpty();

        // 엔드포인트 → 컨트롤러 → 서비스 → 구현 → 매퍼 → 구문은 5걸음이다
        CrudMatrix shallow = CrudMatrix.of(g, Subject.ENDPOINT, 4);
        assertThat(shallow.cell(shop.getOrder, "ORDERS")).isEmpty();
        assertThat(CrudMatrix.of(g, Subject.ENDPOINT, 5).cell(shop.getOrder, "ORDERS")).isEqualTo("R");
    }

    @Test
    @DisplayName("F-015-AC23 테이블 × 엔드포인트: 트리거가 쓰는 테이블은 그 트리거를 일으키는 구문을 거칠 때만 넣는다")
    void endpointMatrixRespectsTriggerEvent() {
        TriggerShop shop = new TriggerShop();
        GraphModel g = shop.f.graph();

        CrudMatrix m = CrudMatrix.of(g, Subject.ENDPOINT);

        // POST /order/save는 INSERT만 한다: AFTER UPDATE OF ORDER_STATUS 트리거의 TB_ORDER_HIST에는 닿지 않는다
        assertThat(m.cell(shop.saveEndpoint, "TB_ORDER")).isEqualTo("C");
        assertThat(m.cell(shop.saveEndpoint, "TB_ORDER_HIST")).isEmpty();
        assertThat(m.cell(shop.saveEndpoint, "TB_EVENT")).isEqualTo("c");
        assertThat(m.cell(shop.statusEndpoint, "TB_ORDER")).isEqualTo("U");
        assertThat(m.cell(shop.statusEndpoint, "TB_ORDER_HIST")).isEqualTo("c");
        assertThat(m.cell(shop.statusEndpoint, "TB_EVENT")).isEqualTo("c");
        assertThat(CallTrees.toText(CallTrees.build(g, shop.saveEndpoint, CallTreeOptions.callees()), false))
                .contains("[trigger] TRG_ORDER_NEW").doesNotContain("TRG_ORDER_STATUS_HIST");
    }

    @Test
    @DisplayName("F-015-AC26 R은 테이블 수준 READS가 있을 때만 붙는다 (조건절 컬럼의 READS만 있는 DELETE는 D, UPDATE는 U)")
    void readOnlyFromTableLevelEdge() {
        GraphFixture f = GraphFixture.create();
        f.table("T");
        f.column("T", "ID", "NUMBER", 1);
        f.column("T", "NAME", "VARCHAR2(10)", 2);
        f.table("S");
        String del = f.statement("com.r.M", "del", "DELETE");
        f.writes(del, "T", "DELETE").readsColumn(del, "T", "ID");
        String upd = f.statement("com.r.M", "upd", "UPDATE");
        f.writes(upd, "T", "UPDATE", "NAME").readsColumn(upd, "T", "ID");
        String updSub = f.statement("com.r.M", "updSub", "UPDATE");
        f.writes(updSub, "T", "UPDATE", "NAME").reads(updSub, "T", "ID").reads(updSub, "S");
        String sel = f.statement("com.r.M", "sel", "SELECT");
        f.reads(sel, "T", "ID", "NAME");
        f.type("com.r.M", "interface", "public");
        String delMethod = f.method("com.r.M", "del");
        f.edge(delMethod, del, EdgeKind.EXECUTES);

        CrudMatrix m = CrudMatrix.of(f.graph(), Subject.STATEMENT);

        assertThat(m.cell(del, "T")).isEqualTo("D");
        assertThat(m.cell(upd, "T")).isEqualTo("U");
        assertThat(m.cell(updSub, "T")).isEqualTo("RU");
        assertThat(m.cell(updSub, "S")).isEqualTo("R");
        assertThat(m.cell(sel, "T")).isEqualTo("R");
        assertThat(CrudMatrix.of(f.graph(), Subject.METHOD).cell(delMethod, "T")).isEqualTo("D");
    }

    @Test
    @DisplayName("F-015-AC27 테이블 × 루틴: 루틴이 부르는 루틴의 구문까지 넣고, 간접으로만 닿는 접근은 소문자로 적는다 (깊이 상한, 순환 방지)")
    void routineMatrixFollowsRoutineCalls() {
        GraphFixture f = GraphFixture.create();
        for (String table : new String[] {"TB_ORDER", "TB_DELIVERY", "TB_ORDER_HIST", "TB_CODE"}) {
            f.table(table);
        }
        String daily = f.routine("CLOSE_DAILY", "PROCEDURE");
        String order = f.routine("CLOSE_ORDER", "PROCEDURE");
        String hist = f.routine("WRITE_HIST", "PROCEDURE");
        String code = f.routine("FN_CODE", "FUNCTION");
        String dailySelect = f.routineStatement(daily, 90, "SELECT");
        f.reads(dailySelect, "TB_ORDER").reads(dailySelect, "TB_DELIVERY");
        String orderUpdate = f.routineStatement(order, 60, "UPDATE");
        f.writes(orderUpdate, "TB_ORDER", "UPDATE");
        f.writes(f.routineStatement(hist, 15, "INSERT"), "TB_ORDER_HIST", "INSERT");
        f.reads(f.routineStatement(code, 9, "SELECT"), "TB_CODE");
        f.edge(daily, order, EdgeKind.CALLS, Map.of("callType", "procedure"));
        f.edge(order, hist, EdgeKind.CALLS, Map.of("callType", "procedure"));
        // 구문 안에서 부른 함수(SQL_STATEMENT -CALLS→ DB_ROUTINE)도 한 걸음이다
        f.edge(orderUpdate, code, EdgeKind.CALLS);
        // 재귀와 상호 호출이 있어도 끝난다
        f.edge(order, daily, EdgeKind.CALLS, Map.of("callType", "procedure"));
        f.edge(hist, hist, EdgeKind.CALLS, Map.of("callType", "procedure"));
        GraphModel g = f.graph();

        CrudMatrix m = CrudMatrix.of(g, Subject.ROUTINE);

        // CLOSE_DAILY는 직접 읽기만 하지만 CLOSE_ORDER를 거쳐 TB_ORDER를 갱신한다(감사 지적의 재현)
        assertThat(m.cell(daily, "TB_ORDER")).isEqualTo("Ru");
        assertThat(m.cell(daily, "TB_DELIVERY")).isEqualTo("R");
        assertThat(m.cell(daily, "TB_ORDER_HIST")).isEqualTo("c");
        assertThat(m.cell(daily, "TB_CODE")).isEqualTo("r");
        assertThat(m.cell(order, "TB_ORDER")).isEqualTo("rU");
        assertThat(m.cell(order, "TB_DELIVERY")).isEqualTo("r");
        assertThat(m.cell(order, "TB_ORDER_HIST")).isEqualTo("c");
        assertThat(m.cell(hist, "TB_ORDER_HIST")).isEqualTo("C");
        assertThat(m.cell(hist, "TB_ORDER")).isEmpty();
        // CSV의 모양(머리줄, 열)은 그대로이고 칸의 글자만 소문자가 섞인다
        assertThat(m.toCsv(Layout.MATRIX, false)).startsWith("루틴,위치,TB_CODE,TB_DELIVERY,TB_ORDER,TB_ORDER_HIST\n")
                .contains("CLOSE_DAILY,db/close_daily.prc:1,r,R,Ru,c\n");

        // 깊이: 루틴에서 루틴으로 가는 걸음 수. 0이면 직접 가진 구문만이다
        CrudMatrix oneHop = CrudMatrix.of(g, Subject.ROUTINE, 1);
        assertThat(oneHop.cell(daily, "TB_ORDER")).isEqualTo("Ru");
        assertThat(oneHop.cell(daily, "TB_ORDER_HIST")).isEmpty();
        assertThat(oneHop.cell(daily, "TB_CODE")).isEmpty();
        CrudMatrix direct = CrudMatrix.of(g, Subject.ROUTINE, 0);
        assertThat(direct.cell(daily, "TB_ORDER")).isEqualTo("R");
        assertThat(direct.cell(order, "TB_ORDER")).isEqualTo("U");
    }

    @Test
    @DisplayName("F-015-AC31 대상 테이블을 알 수 없는 동적 구문은 빼지 않고 (동적) 열과 별도 목록으로 낸다")
    void dynamicStatementsAreVisible() {
        GraphFixture f = GraphFixture.create();
        f.table("TB_ORDER");
        f.type("com.d.M", "interface", "public");
        f.type("com.d.C", "class", "public");
        String backup = f.statement("com.d.M", "selectBackup", "SELECT");
        f.dynamicAccess(backup, EdgeKind.READS, null, "__DYNAMIC__");
        String mixed = f.statement("com.d.M", "mixed", "SELECT");
        f.reads(mixed, "TB_ORDER");
        f.dynamicAccess(mixed, EdgeKind.READS, null, "__DYNAMIC__");
        String procedure = f.routine("SP_ARCHIVE", "PROCEDURE");
        String archive = f.routineStatement(procedure, 8, "INSERT");
        f.dynamicAccess(archive, EdgeKind.WRITES, "INSERT", "INSERT INTO TB_ORDER_ARC_{*} SELECT * FROM TB_ORDER");
        String method = f.method("com.d.M", "selectBackup");
        f.edge(method, backup, EdgeKind.EXECUTES);
        String handler = f.method("com.d.C", "backup");
        f.calls(handler, method);
        String endpoint = f.endpoint("GET", "/backup", handler);
        GraphModel g = f.graph();

        CrudMatrix m = CrudMatrix.of(g, Subject.STATEMENT);

        assertThat(m.rows()).extracting(Row::id).containsExactly(mixed, backup, archive);
        assertThat(m.tables()).containsExactly("TB_ORDER");
        assertThat(m.hasDynamicColumn()).isTrue();
        assertThat(m.cell(backup, CrudMatrix.DYNAMIC_COLUMN)).isEqualTo("R");
        assertThat(m.cell(archive, CrudMatrix.DYNAMIC_COLUMN)).isEqualTo("C");
        assertThat(m.cell(mixed, "TB_ORDER")).isEqualTo("R");
        assertThat(m.cell(mixed, CrudMatrix.DYNAMIC_COLUMN)).isEqualTo("R");
        assertThat(m.dynamicStatements()).extracting(DynamicStatement::id).containsExactly(mixed, backup, archive);
        assertThat(m.dynamicStatements()).extracting(DynamicStatement::access).containsExactly("R", "R", "C");
        assertThat(m.dynamicStatements().get(2).location()).isEqualTo("db/sp_archive.prc:8");
        assertThat(m.dynamicStatements().get(2).text()).startsWith("INSERT INTO TB_ORDER_ARC_{*}");

        String[] csv = m.toCsv(Layout.MATRIX, false).split("\n");
        assertThat(csv[0]).isEqualTo("구문,위치,TB_ORDER,(동적)");
        assertThat(csv[1]).matches("com\\.d\\.M\\.mixed,src/main/resources/mapper/M\\.xml:\\d+,R,R");
        assertThat(csv[2]).matches("com\\.d\\.M\\.selectBackup,src/main/resources/mapper/M\\.xml:\\d+,,R");
        assertThat(csv[3]).isEqualTo("SP_ARCHIVE:8,db/sp_archive.prc:8,,C");
        assertThat(m.toCsv(Layout.LIST, false)).contains("\n(동적),com.d.M.selectBackup,R,").contains("\n(동적),SP_ARCHIVE:8,C,db/sp_archive.prc:8\n");
        assertThat(m.toCsv(Layout.MATRIX_BY_TABLE, false)).endsWith("\n(동적),R,R,C\n");

        String md = m.toMarkdown(Layout.MATRIX);
        assertThat(md).contains("| 구문 | TB_ORDER | (동적) |\n").contains("\n**대상 테이블을 정적으로 알 수 없는 구문 (3)**\n")
                .contains("| 구문 | CRUD | 위치 |\n|---|---|---|\n").contains("| `SP_ARCHIVE:8` | C | `db/sp_archive.prc:8` |\n")
                .containsPattern("\\| `com\\.d\\.M\\.selectBackup` \\| R \\| `src/main/resources/mapper/M\\.xml:\\d+` \\|");

        // 그 구문에 닿는 메서드, 엔드포인트, 루틴의 매트릭스에도 (동적) 열이 생긴다
        assertThat(CrudMatrix.of(g, Subject.METHOD).cell(method, CrudMatrix.DYNAMIC_COLUMN)).isEqualTo("R");
        assertThat(CrudMatrix.of(g, Subject.ENDPOINT).cell(endpoint, CrudMatrix.DYNAMIC_COLUMN)).isEqualTo("R");
        assertThat(CrudMatrix.of(g, Subject.ROUTINE).cell(procedure, CrudMatrix.DYNAMIC_COLUMN)).isEqualTo("C");
        assertThat(CrudMatrix.of(g, Subject.ROUTINE).dynamicStatements()).isEmpty();

        // 동적 구문이 없는 그래프의 출력은 그대로다
        CrudMatrix plain = CrudMatrix.of(new ShopGraph().f.graph(), Subject.STATEMENT);
        assertThat(plain.hasDynamicColumn()).isFalse();
        assertThat(plain.dynamicStatements()).isEmpty();
        assertThat(plain.toCsv(Layout.MATRIX, false)).doesNotContain("(동적)");
        assertThat(plain.toMarkdown(Layout.MATRIX)).doesNotContain("정적으로 알 수 없는");
    }

    @Test
    @DisplayName("F-015-AC32 프로시저를 호출하는 구문과 그것을 실행하는 메서드·엔드포인트에 루틴을 거친 테이블이 소문자로 들어간다")
    void callStatementsReachRoutineTables() {
        GraphFixture f = GraphFixture.create();
        for (String table : new String[] {"TB_ORDER", "TB_ORDER_HIST", "TB_MEMBER"}) {
            f.table(table);
        }
        f.attr(f.type("com.c.OrderMapper", "interface", "public"), "stereotype", "mapper");
        f.type("com.c.OrderService", "class", "public");
        String closeOrder = f.routine("CLOSE_ORDER", "PROCEDURE");
        String writeHist = f.routine("WRITE_HIST", "PROCEDURE");
        String closeSelect = f.routineStatement(closeOrder, 47, "SELECT");
        f.reads(closeSelect, "TB_ORDER");
        f.writes(f.routineStatement(closeOrder, 60, "UPDATE"), "TB_ORDER", "UPDATE");
        f.writes(f.routineStatement(writeHist, 15, "INSERT"), "TB_ORDER_HIST", "INSERT");
        f.edge(closeOrder, writeHist, EdgeKind.CALLS, Map.of("callType", "procedure"));
        // {call CLOSE_ORDER(...)}: 구문에는 테이블 엣지가 없고 루틴으로 가는 CALLS만 있다
        String call = f.statement("com.c.OrderMapper", "callCloseOrder", "CALL");
        f.edge(call, closeOrder, EdgeKind.CALLS);
        String callMethod = f.method("com.c.OrderMapper", "callCloseOrder", java.util.List.of(), "void");
        f.edge(callMethod, call, EdgeKind.EXECUTES);
        // 같은 테이블을 직접 읽기도 하는 메서드: 직접 R + 루틴 경유 u
        String select = f.statement("com.c.OrderMapper", "selectOrder", "SELECT");
        f.reads(select, "TB_ORDER").reads(select, "TB_MEMBER");
        String both = f.method("com.c.OrderService", "closeAndRead");
        f.edge(both, select, EdgeKind.EXECUTES);
        f.edge(both, call, EdgeKind.EXECUTES);
        String handler = f.method("com.c.OrderService", "close");
        f.calls(handler, callMethod);
        String endpoint = f.endpoint("POST", "/order/close", handler);
        GraphModel g = f.graph();

        // 감사 지적의 재현: 프로시저를 호출하는 구문을 실행하는 메서드의 행이 없었다
        CrudMatrix methods = CrudMatrix.of(g, Subject.METHOD);
        assertThat(methods.rows()).extracting(Row::id).containsExactly(callMethod, both);
        assertThat(methods.cell(callMethod, "TB_ORDER")).isEqualTo("ru");
        assertThat(methods.cell(callMethod, "TB_ORDER_HIST")).isEqualTo("c");
        assertThat(methods.cell(both, "TB_ORDER")).isEqualTo("Ru");
        assertThat(methods.cell(both, "TB_MEMBER")).isEqualTo("R");
        assertThat(methods.cell(both, "TB_ORDER_HIST")).isEqualTo("c");

        CrudMatrix statements = CrudMatrix.of(g, Subject.STATEMENT);
        assertThat(statements.cell(call, "TB_ORDER")).isEqualTo("ru");
        assertThat(statements.cell(call, "TB_ORDER_HIST")).isEqualTo("c");
        assertThat(statements.cell(select, "TB_ORDER")).isEqualTo("R");
        assertThat(statements.cell(closeSelect, "TB_ORDER")).isEqualTo("R");
        assertThat(statements.toCsv(Layout.MATRIX, false)).containsPattern("com\\.c\\.OrderMapper\\.callCloseOrder,[^,]+,,ru,c\n");

        CrudMatrix endpoints = CrudMatrix.of(g, Subject.ENDPOINT);
        assertThat(endpoints.cell(endpoint, "TB_ORDER")).isEqualTo("ru");
        assertThat(endpoints.cell(endpoint, "TB_ORDER_HIST")).isEqualTo("c");

        // 걸음 수 0이면 루틴 안으로 들어가지 않는다. 1이면 구문이 부른 루틴까지, 그 루틴이 부른 루틴은 2부터다
        assertThat(CrudMatrix.of(g, Subject.METHOD, 0).rows()).extracting(Row::id).containsExactly(both);
        assertThat(CrudMatrix.of(g, Subject.METHOD, 1).cell(callMethod, "TB_ORDER")).isEqualTo("ru");
        assertThat(CrudMatrix.of(g, Subject.METHOD, 1).cell(callMethod, "TB_ORDER_HIST")).isEmpty();
        assertThat(CrudMatrix.of(g, Subject.METHOD, 2).cell(callMethod, "TB_ORDER_HIST")).isEqualTo("c");
    }

    @Test
    @DisplayName("F-015-AC14 CSV(BOM 옵션, 이스케이프)와 Markdown 출력")
    void csvAndMarkdown() {
        GraphFixture f = GraphFixture.create();
        f.table("\"X,Y\"");
        f.table("PLAIN");
        String quoted = f.statement("com.a.M", "say\"hi\"", "SELECT");
        String plain = f.statement("com.a.M", "upd", "UPDATE");
        f.reads(quoted, "\"X,Y\"");
        f.writes(plain, "PLAIN", "UPDATE").reads(plain, "\"X,Y\"");
        CrudMatrix m = CrudMatrix.of(f.graph(), Subject.STATEMENT);

        String csv = m.toCsv(Layout.MATRIX, false);
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("구문,위치,PLAIN,\"X,Y\"");
        assertThat(lines[1]).matches("\"com\\.a\\.M\\.say\"\"hi\"\"\",src/main/resources/mapper/M\\.xml:\\d+,,R");
        assertThat(lines[2]).matches("com\\.a\\.M\\.upd,src/main/resources/mapper/M\\.xml:\\d+,U,R");
        assertThat(csv).endsWith("\n").doesNotContain("\r");
        assertThat(m.toCsv(Layout.MATRIX, true)).startsWith("﻿구문,위치,");

        assertThat(m.toCsv(Layout.LIST, false).split("\n"))
                .satisfies(l -> assertThat(l[0]).isEqualTo("테이블,구문,CRUD,위치"))
                .satisfies(l -> assertThat(l[1]).startsWith("PLAIN,com.a.M.upd,U,"))
                .satisfies(l -> assertThat(l[2]).startsWith("\"X,Y\",\"com.a.M.say\"\"hi\"\"\",R,"))
                .hasSize(4);
        assertThat(m.toCsv(Layout.MATRIX_BY_TABLE, false).split("\n")[0]).isEqualTo("테이블,\"com.a.M.say\"\"hi\"\"\",com.a.M.upd");
        assertThat(m.toCsv(Layout.MATRIX_BY_TABLE, false).split("\n")[2]).isEqualTo("\"X,Y\",R,R");

        String md = m.toMarkdown(Layout.MATRIX);
        assertThat(md).contains("| 구문 | PLAIN | X,Y |\n|---|---|---|\n");
        assertThat(md).contains("| `com.a.M.upd` | U | R |\n");
        assertThat(m.toMarkdown(Layout.LIST)).contains("| 테이블 | 구문 | CRUD | 위치 |\n");

        CrudMatrix endpoints = CrudMatrix.of(new ShopGraph().f.graph(), Subject.ENDPOINT);
        assertThat(endpoints.toCsv(Layout.MATRIX, false)).startsWith("엔드포인트,위치,AUDIT_LOG,CUSTOMER,ORDERS,ORDER_HIST,STOCK\n")
                .containsPattern("GET /orders/\\{id},src/main/java/com/shop/order/OrderController\\.java:\\d+,C\\?,R,R,,\n");
    }
}
