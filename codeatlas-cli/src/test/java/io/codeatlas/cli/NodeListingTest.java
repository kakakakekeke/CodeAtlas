package io.codeatlas.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NodeListingTest {

    @Test
    @DisplayName("F-001-AC40 후보 목록은 번호·종류·ID·위치 열을 맞추되, 아주 긴 ID 하나가 다른 줄의 위치를 80자 넘게 밀지 않는다")
    void alignsColumnsWithCappedIdWidth() {
        GraphModel graph = new GraphModel();
        String shortId = Ids.table("ORDERS");
        String longId = Ids.javaMethod("com.example.shop.order.service.OrderServiceImpl", "findOrders",
                "com.example.shop.order.vo.OrderSearchCond", "com.example.shop.common.PageRequest", "java.lang.String");
        Node table = graph.declare(Node.builder(shortId, NodeKind.DB_TABLE).name("ORDERS").location(SourceLocation.of("db/tables.sql", 3)).build());
        Node method = graph.declare(Node.builder(longId, NodeKind.JAVA_METHOD).name("findOrders").location(SourceLocation.of("src/A.java", 73)).build());
        Node noLocation = graph.reference(Ids.table("TMP"), NodeKind.DB_TABLE, "TMP");
        assertThat(longId.length()).isGreaterThan(80);

        List<String> lines = NodeListing.table(graph, List.of(table, method, noLocation), 10).lines().toList();

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("1  DB_TABLE     " + shortId + " ").endsWith("  db/tables.sql:3");
        assertThat(lines.get(0).indexOf("db/tables.sql")).isEqualTo("1  DB_TABLE     ".length() + 80 + 2);
        assertThat(lines.get(1)).isEqualTo("2  JAVA_METHOD  " + longId + "  src/A.java:73");
        assertThat(lines.get(2)).isEqualTo("3  DB_TABLE     " + Ids.table("TMP"));

        assertThat(NodeListing.table(graph, List.of(table, method, noLocation), 2).lines()).hasSize(2);
    }

    @Test
    @DisplayName("F-001-AC75 후보 목록은 메서드 후보가 있는 화면 요청(httpMethods)의 줄 끝에 [POST|PUT]을 적는다. ID와 다른 줄은 그대로다")
    void showsRequestMethodCandidates() {
        GraphModel graph = new GraphModel();
        String candidates = Ids.httpRequest("ANY", "/api/schedule", "src/Edit.jsx", 146);
        String any = Ids.httpRequest("ANY", "/api/schedule", "src/legacy.js", 10);
        Node withCandidates = graph.declare(Node.builder(candidates, NodeKind.HTTP_REQUEST).name("ANY /api/schedule")
                .location(SourceLocation.of("src/Edit.jsx", 146))
                .attr("httpMethod", "ANY").attr("httpMethods", List.of("POST", "PUT")).attr("path", "/api/schedule").build());
        Node plain = graph.declare(Node.builder(any, NodeKind.HTTP_REQUEST).name("ANY /api/schedule")
                .location(SourceLocation.of("src/legacy.js", 10)).attr("httpMethod", "ANY").attr("path", "/api/schedule").build());
        // 위치를 모르는 요청(참조만 된 노드에 속성을 얹은 것)에도 메서드 후보는 적는다
        Node noLocation = graph.declare(Node.builder(Ids.httpRequest("ANY", "/api/x", "src/x.js", 1), NodeKind.HTTP_REQUEST).name("ANY /api/x")
                .attr("httpMethods", List.of("DELETE")).build());

        List<String> lines = NodeListing.table(graph, List.of(withCandidates, plain, noLocation), 10).lines().toList();

        assertThat(lines.get(0)).isEqualTo("1  HTTP_REQUEST  " + candidates + "  src/Edit.jsx:146  [POST|PUT]");
        assertThat(lines.get(1)).isEqualTo("2  HTTP_REQUEST  " + any + "  src/legacy.js:10");
        assertThat(lines.get(2)).isEqualTo("3  HTTP_REQUEST  " + noLocation.id() + "  [DELETE]");
    }

    @Test
    @DisplayName("F-001-AC40 --kind 값은 대소문자와 하이픈을 가리지 않고 쉼표로 여러 개를 받으며, 모르는 종류는 사용법 오류다")
    void parsesKinds() {
        assertThat(NodeListing.kinds(List.of("java_method, DB-TABLE", "Sql_Statement", "java_method")))
                .containsExactly(NodeKind.JAVA_METHOD, NodeKind.DB_TABLE, NodeKind.SQL_STATEMENT);
        assertThat(NodeListing.kinds(List.of())).isEmpty();
        assertThatThrownBy(() -> NodeListing.kinds(List.of("table"))).isInstanceOf(UsageException.class)
                .hasMessageContaining("table").hasMessageContaining("DB_TABLE");
    }
}
