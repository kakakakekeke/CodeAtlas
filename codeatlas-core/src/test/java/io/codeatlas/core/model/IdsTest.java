package io.codeatlas.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    @DisplayName("F-001-AC8 설계 3.3의 예시대로 Java, HTTP, 배치, MyBatis ID를 만든다")
    void designExamples() {
        assertThat(Ids.javaMethod("com.a.B", "save", "java.lang.String", "int[]"))
                .isEqualTo("java:method:com.a.B#save(java.lang.String,int[])");
        assertThat(Ids.javaMethod("com.a.B", "save", List.of("java.util.Map", " long ")))
                .isEqualTo("java:method:com.a.B#save(java.util.Map,long)");
        assertThat(Ids.javaConstructor("com.a.B")).isEqualTo("java:method:com.a.B#<init>()");
        assertThat(Ids.httpEndpoint("get", "/orders/{id}")).isEqualTo("http:endpoint:GET /orders/{id}");
        assertThat(Ids.httpEndpoint(null, "orders")).isEqualTo("http:endpoint:ANY /orders");
        assertThat(Ids.httpEndpoint("", "/orders//{id}")).isEqualTo("http:endpoint:ANY /orders/{id}");
        assertThat(Ids.batchStep(null, "load")).isEqualTo("batch:step:?/load");
        assertThat(Ids.batchStep("importJob", "load")).isEqualTo("batch:step:importJob/load");
        assertThat(Ids.mybatisStatement("com.a.OrderMapper", "selectOne")).isEqualTo("mybatis:stmt:com.a.OrderMapper.selectOne");
    }

    @Test
    @DisplayName("F-001-AC9 DB 이름은 스키마를 빼고 대문자로, 따옴표 식별자는 원문 그대로")
    void dbNames() {
        assertThat(Ids.table("hr.orders")).isEqualTo("db:table:ORDERS");
        assertThat(Ids.table("\"Orders\"")).isEqualTo("db:table:Orders");
        assertThat(Ids.table("hr.\"My.Table\"")).isEqualTo("db:table:My.Table");
        assertThat(Ids.column("orders", "order_id")).isEqualTo("db:column:ORDERS.ORDER_ID");
        assertThat(Ids.column("hr.orders", "\"Memo\"")).isEqualTo("db:column:ORDERS.Memo");
        assertThat(Ids.view("v_orders")).isEqualTo("db:view:V_ORDERS");
        assertThat(Ids.routine("proc_close")).isEqualTo("db:routine:PROC_CLOSE");
        assertThat(Ids.routine("pkg_order", "close_order")).isEqualTo("db:routine:PKG_ORDER.CLOSE_ORDER");
        assertThat(Ids.routine(null, "close_order")).isEqualTo("db:routine:CLOSE_ORDER");
        assertThat(Ids.dbPackage("pkg_order")).isEqualTo("db:package:PKG_ORDER");
        assertThat(Ids.sequence("seq_order")).isEqualTo("db:sequence:SEQ_ORDER");
        assertThat(Ids.synonym("syn")).isEqualTo("db:synonym:SYN");

        assertThat(DbName.parse(" hr . orders ")).isEqualTo(new DbName("HR", "ORDERS"));
        assertThat(DbName.parse("orders")).isEqualTo(new DbName(null, "ORDERS"));
        assertThat(DbName.parse("db.hr.\"o\"\"x\"")).isEqualTo(new DbName("HR", "o\"x"));
        assertThat(Ids.dbName("İd")).isEqualTo("İD");
    }

    @Test
    @DisplayName("나머지 ID 형식")
    void otherIds() {
        assertThat(Ids.file("src\\main\\A.java")).isEqualTo("file:src/main/A.java");
        assertThat(Ids.javaPackage("com.a")).isEqualTo("java:package:com.a");
        assertThat(Ids.javaType("com.a.Outer$Inner")).isEqualTo("java:type:com.a.Outer$Inner");
        assertThat(Ids.javaField("com.a.B", "name")).isEqualTo("java:field:com.a.B#name");
        assertThat(Ids.springBean("orderService")).isEqualTo("spring:bean:orderService");
        assertThat(Ids.httpRequest("post", "/api/orders/{*}", "web/app.js", 12))
                .isEqualTo("http:request:POST /api/orders/{*}@web/app.js:12");
        assertThat(Ids.batchJob("importJob")).isEqualTo("batch:job:importJob");
        assertThat(Ids.mybatisMapper("com.a.OrderMapper")).isEqualTo("mybatis:mapper:com.a.OrderMapper");
        assertThat(Ids.mybatisFragment("com.a.OrderMapper", "cols")).isEqualTo("mybatis:fragment:com.a.OrderMapper.cols");
        assertThat(Ids.sqlStatement("db/query.sql", 14)).isEqualTo("sql:stmt:db/query.sql:14");
        assertThat(Ids.routineStatement("db:routine:PKG.P", 30)).isEqualTo("sql:stmt:PKG.P:30");
        assertThat(Ids.routineStatement("PKG.P", 30)).isEqualTo("sql:stmt:PKG.P:30");
        assertThat(Ids.jsModule("web/app.js")).isEqualTo("js:module:web/app.js");
        assertThat(Ids.jsFunction("web/app.js", "Cart.add")).isEqualTo("js:function:web/app.js#Cart.add");
        assertThat(Ids.jsFunction("web/app.js", Ids.jsAnonymous(3, 9))).isEqualTo("js:function:web/app.js#<anonymous>@3:9");
        assertThat(Ids.jspPage("WEB-INF/views/a.jsp")).isEqualTo("jsp:page:WEB-INF/views/a.jsp");
        assertThat(Ids.unresolved("java-call", "foo()")).isEqualTo("unresolved:java-call:foo()");
    }

    @Test
    @DisplayName("ID에서 접두사와 종류를 알아낸다")
    void prefixes() {
        assertThat(Ids.prefixOf("java:method:com.a.B#x()")).isEqualTo("java:method");
        assertThat(Ids.prefixOf("file:a/b:c.txt")).isEqualTo("file");
        assertThat(Ids.prefixOf("custom:thing:x")).isEqualTo("custom:thing");
        assertThat(Ids.prefixOf("weird")).isEqualTo("weird");
        assertThat(Ids.remainder("db:table:ORDERS")).isEqualTo("ORDERS");
        assertThat(Ids.remainder("http:endpoint:GET /a:b")).isEqualTo("GET /a:b");
        assertThat(Ids.kindOf("mybatis:stmt:ns.id")).isEqualTo(Optional.of(NodeKind.SQL_STATEMENT));
        assertThat(Ids.kindOf("sql:stmt:a.sql:1")).isEqualTo(Optional.of(NodeKind.SQL_STATEMENT));
        assertThat(Ids.kindOf("zzz:1")).isEmpty();
    }
}
