package io.codeatlas.cli.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 정답 대조기 단위 테스트. 손으로 만든 작은 그래프와 작은 정답 JSON으로 선택 필드와 와일드카드를 하나씩 확인한다. */
class ExpectedCheckerTest {

    private final GraphModel graph = new GraphModel();

    private void node(String id) {
        graph.declare(Node.builder(id, NodeKind.fromId(id).orElseThrow()).build());
    }

    private void nodes(String... ids) {
        for (String id : ids) {
            node(id);
        }
    }

    private Edge.Builder edge(String from, EdgeKind kind, String to) {
        return Edge.builder(from, to, kind);
    }

    private void add(Edge.Builder builder) {
        graph.addEdge(builder.build());
    }

    private EdgeResult checkEdge(String json) {
        return checkEdge(json, CheckOptions.defaults());
    }

    private EdgeResult checkEdge(String json, CheckOptions options) {
        VerificationResult result = new ExpectedChecker(graph, options).check(ExpectedSet.parse("[" + json + "]", "[]", "[]"));
        assertThat(result.edges()).hasSize(1);
        return result.edges().get(0);
    }

    private NodeResult checkNode(String json) {
        VerificationResult result = new ExpectedChecker(graph, CheckOptions.defaults()).check(ExpectedSet.parse("[]", "[" + json + "]", "[]"));
        assertThat(result.nodes()).hasSize(1);
        return result.nodes().get(0);
    }

    private List<Violation> violations(String json) {
        return new ExpectedChecker(graph, CheckOptions.defaults()).check(ExpectedSet.parse("[]", "[]", "[" + json + "]")).violations();
    }

    // ------------------------------------------------------------------ must-have 엣지: 일반 항목

    @Test
    @DisplayName("일반 엣지: 있으면 PASS, 없으면 사유를 노드 존재 여부로 나눈다")
    void plainEdge() {
        nodes("java:type:a.A", "java:type:a.B", "java:type:a.C");
        add(edge("java:type:a.A", EdgeKind.EXTENDS, "java:type:a.B"));

        assertThat(checkEdge("{\"from\":\"java:type:a.A\",\"to\":\"java:type:a.B\",\"kind\":\"EXTENDS\",\"note\":\"n\"}").verdict())
                .isEqualTo(Verdict.PASS);

        EdgeResult missing = checkEdge("{\"from\":\"java:type:a.A\",\"to\":\"java:type:a.C\",\"kind\":\"EXTENDS\"}");
        assertThat(missing.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(missing.reason()).isEqualTo(FailReason.EDGE_MISSING);

        // 종류가 다른 엣지는 치지 않는다
        assertThat(checkEdge("{\"from\":\"java:type:a.A\",\"to\":\"java:type:a.B\",\"kind\":\"IMPLEMENTS\"}").reason())
                .isEqualTo(FailReason.EDGE_MISSING);
        // 방향이 반대인 엣지는 치지 않는다
        assertThat(checkEdge("{\"from\":\"java:type:a.B\",\"to\":\"java:type:a.A\",\"kind\":\"EXTENDS\"}").reason())
                .isEqualTo(FailReason.EDGE_MISSING);

        assertThat(checkEdge("{\"from\":\"java:type:a.A\",\"to\":\"java:type:a.X\",\"kind\":\"EXTENDS\"}").reason())
                .isEqualTo(FailReason.TO_NODE_MISSING);
        assertThat(checkEdge("{\"from\":\"java:type:a.X\",\"to\":\"java:type:a.B\",\"kind\":\"EXTENDS\"}").reason())
                .isEqualTo(FailReason.FROM_NODE_MISSING);
        assertThat(checkEdge("{\"from\":\"java:type:a.X\",\"to\":\"java:type:a.Y\",\"kind\":\"EXTENDS\"}").reason())
                .isEqualTo(FailReason.BOTH_NODES_MISSING);
    }

    @Test
    @DisplayName("신뢰도: UNRESOLVED 엣지는 있는 것으로 치지 않고, INFERRED는 통과로 치되 따로 구분한다")
    void confidence() {
        nodes("java:method:a.A#x()", "java:method:a.B#y()", "java:method:a.B#z()");
        add(edge("java:method:a.A#x()", EdgeKind.CALLS, "java:method:a.B#y()").unresolved());
        add(edge("java:method:a.A#x()", EdgeKind.CALLS, "java:method:a.B#z()").inferred());

        EdgeResult unresolved = checkEdge("{\"from\":\"java:method:a.A#x()\",\"to\":\"java:method:a.B#y()\",\"kind\":\"CALLS\"}");
        assertThat(unresolved.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(unresolved.reason()).isEqualTo(FailReason.EDGE_UNRESOLVED);

        EdgeResult inferred = checkEdge("{\"from\":\"java:method:a.A#x()\",\"to\":\"java:method:a.B#z()\",\"kind\":\"CALLS\"}");
        assertThat(inferred.verdict()).isEqualTo(Verdict.INFERRED_PASS);
        assertThat(inferred.reason()).isNull();
    }

    @Test
    @DisplayName("신뢰도: 같은 자리에 RESOLVED와 INFERRED가 함께 있으면 RESOLVED로 판정한다")
    void resolvedWinsOverInferred() {
        nodes("java:method:a.A#x()", "java:method:a.B#y()");
        add(edge("java:method:a.A#x()", EdgeKind.CALLS, "java:method:a.B#y()").inferred().location(SourceLocation.of("A.java", 3)));
        add(edge("java:method:a.A#x()", EdgeKind.CALLS, "java:method:a.B#y()").location(SourceLocation.of("A.java", 9)));

        assertThat(checkEdge("{\"from\":\"java:method:a.A#x()\",\"to\":\"java:method:a.B#y()\",\"kind\":\"CALLS\"}").verdict())
                .isEqualTo(Verdict.PASS);
    }

    @Test
    @DisplayName("매달린 엣지 처리로 생긴 UNRESOLVED 종류 노드는 없는 노드로 본다")
    void danglingTargetCountsAsMissing() {
        node("java:type:a.A");
        add(edge("java:type:a.A", EdgeKind.EXTENDS, "java:type:a.Gone"));
        graph.resolveDanglingEdges();

        EdgeResult result = checkEdge("{\"from\":\"java:type:a.A\",\"to\":\"java:type:a.Gone\",\"kind\":\"EXTENDS\"}");
        assertThat(result.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(result.reason()).isEqualTo(FailReason.EDGE_UNRESOLVED);
        assertThat(checkEdge("{\"from\":\"java:type:a.A\",\"to\":\"java:type:a.Gone\",\"kind\":\"IMPLEMENTS\"}").reason())
                .isEqualTo(FailReason.TO_NODE_MISSING);
    }

    // ------------------------------------------------------------------ altTo / altFrom

    @Test
    @DisplayName("altTo: to나 altTo 중 어느 쪽으로든 엣지가 있으면 통과")
    void altTo() {
        nodes("java:type:a.Svc", "java:type:a.Policy", "java:type:a.VipPolicy", "java:type:a.Other");
        add(edge("java:type:a.Svc", EdgeKind.INJECTS, "java:type:a.VipPolicy"));
        String item = "{\"from\":\"java:type:a.Svc\",\"to\":\"java:type:a.Policy\",\"kind\":\"INJECTS\",\"altTo\":[\"java:type:a.Nope\",\"java:type:a.VipPolicy\"]}";

        EdgeResult result = checkEdge(item);
        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.matched().to()).isEqualTo("java:type:a.VipPolicy");

        // 변이: altTo에 없는 대상으로만 엣지가 있으면 실패
        GraphModel other = new GraphModel();
        other.declare(Node.of("java:type:a.Svc", NodeKind.JAVA_TYPE, "Svc"));
        other.declare(Node.of("java:type:a.Policy", NodeKind.JAVA_TYPE, "Policy"));
        other.declare(Node.of("java:type:a.Other", NodeKind.JAVA_TYPE, "Other"));
        other.addEdge(Edge.of("java:type:a.Svc", "java:type:a.Other", EdgeKind.INJECTS));
        EdgeResult failed = new ExpectedChecker(other, CheckOptions.defaults()).check(ExpectedSet.parse("[" + item + "]", "[]", "[]")).edges().get(0);
        assertThat(failed.reason()).isEqualTo(FailReason.EDGE_MISSING);
    }

    @Test
    @DisplayName("altFrom: from이나 altFrom 중 어느 쪽에서든 엣지가 나가면 통과")
    void altFrom() {
        nodes("batch:step:job/step", "java:method:a.Cfg#reader()", "mybatis:stmt:ns.select");
        add(edge("java:method:a.Cfg#reader()", EdgeKind.EXECUTES, "mybatis:stmt:ns.select"));

        EdgeResult result = checkEdge("{\"from\":\"batch:step:job/step\",\"to\":\"mybatis:stmt:ns.select\",\"kind\":\"EXECUTES\","
                + "\"altFrom\":[\"java:method:a.Cfg#reader()\"]}");
        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.matched().from()).isEqualTo("java:method:a.Cfg#reader()");

        assertThat(checkEdge("{\"from\":\"batch:step:job/step\",\"to\":\"mybatis:stmt:ns.select\",\"kind\":\"EXECUTES\"}").reason())
                .isEqualTo(FailReason.EDGE_MISSING);
    }

    // ------------------------------------------------------------------ attrs

    @Test
    @DisplayName("attrs: 지정한 키의 값이 같아야 통과하고, 다르면 ATTR_MISMATCH. 지정하지 않은 키는 보지 않는다")
    void edgeAttrs() {
        nodes("mybatis:stmt:ns.upd", "db:table:T1");
        add(edge("mybatis:stmt:ns.upd", EdgeKind.WRITES, "db:table:T1").attr("op", "UPDATE").attr("extra", "x"));

        assertThat(checkEdge("{\"from\":\"mybatis:stmt:ns.upd\",\"to\":\"db:table:T1\",\"kind\":\"WRITES\",\"attrs\":{\"op\":\"UPDATE\"}}").verdict())
                .isEqualTo(Verdict.PASS);

        EdgeResult mismatch = checkEdge("{\"from\":\"mybatis:stmt:ns.upd\",\"to\":\"db:table:T1\",\"kind\":\"WRITES\",\"attrs\":{\"op\":\"DELETE\"}}");
        assertThat(mismatch.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(mismatch.reason()).isEqualTo(FailReason.ATTR_MISMATCH);
        assertThat(mismatch.detail()).contains("op").contains("DELETE").contains("UPDATE");

        // 속성이 아예 없는 것도 불일치
        assertThat(checkEdge("{\"from\":\"mybatis:stmt:ns.upd\",\"to\":\"db:table:T1\",\"kind\":\"WRITES\",\"attrs\":{\"via\":\"x\"}}").reason())
                .isEqualTo(FailReason.ATTR_MISMATCH);
    }

    @Test
    @DisplayName("attrs: 느슨한 모드(README '속성 비교는 선택')에서는 속성이 달라도 통과시키고 표시만 남긴다")
    void edgeAttrsLenient() {
        nodes("mybatis:stmt:ns.upd", "db:table:T1");
        add(edge("mybatis:stmt:ns.upd", EdgeKind.WRITES, "db:table:T1").attr("op", "UPDATE"));

        EdgeResult result = checkEdge("{\"from\":\"mybatis:stmt:ns.upd\",\"to\":\"db:table:T1\",\"kind\":\"WRITES\",\"attrs\":{\"op\":\"DELETE\"}}",
                CheckOptions.defaults().withStrictEdgeAttrs(false));
        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.attrsMismatched()).isTrue();
    }

    @Test
    @DisplayName("attrs: 같은 자리에 엣지가 여럿이면 속성이 맞는 것을 고른다")
    void edgeAttrsPicksMatchingEdge() {
        nodes("java:type:a.C", "java:type:a.C2");
        add(edge("java:type:a.C", EdgeKind.ASSOCIATES, "java:type:a.C2").attr("field", "parent").attr("multiplicity", "1").location(SourceLocation.of("C.java", 3)));
        add(edge("java:type:a.C", EdgeKind.ASSOCIATES, "java:type:a.C2").attr("field", "children").attr("multiplicity", "*").location(SourceLocation.of("C.java", 4)));

        EdgeResult result = checkEdge("{\"from\":\"java:type:a.C\",\"to\":\"java:type:a.C2\",\"kind\":\"ASSOCIATES\",\"attrs\":{\"field\":\"children\",\"multiplicity\":\"*\"}}");
        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.matched().attrString("field")).isEqualTo("children");
    }

    @Test
    @DisplayName("attrs: 짝을 이루는 목록(columns/refColumns)은 짝을 유지한 채 순서만 무시한다")
    void pairedListAttrs() {
        nodes("db:table:CHILD", "db:table:PARENT");
        add(edge("db:table:CHILD", EdgeKind.REFERENCES, "db:table:PARENT")
                .attr("columns", List.of("B_ID", "A_ID")).attr("refColumns", List.of("B", "A")).attr("source", "DDL"));

        assertThat(checkEdge("{\"from\":\"db:table:CHILD\",\"to\":\"db:table:PARENT\",\"kind\":\"REFERENCES\","
                + "\"attrs\":{\"columns\":[\"A_ID\",\"B_ID\"],\"refColumns\":[\"A\",\"B\"],\"source\":\"DDL\"}}").verdict()).isEqualTo(Verdict.PASS);
        // 변이: 원소는 같지만 짝이 어긋난다
        assertThat(checkEdge("{\"from\":\"db:table:CHILD\",\"to\":\"db:table:PARENT\",\"kind\":\"REFERENCES\","
                + "\"attrs\":{\"columns\":[\"A_ID\",\"B_ID\"],\"refColumns\":[\"B\",\"A\"]}}").reason()).isEqualTo(FailReason.ATTR_MISMATCH);
        // 변이: 컬럼이 하나 모자란다
        assertThat(checkEdge("{\"from\":\"db:table:CHILD\",\"to\":\"db:table:PARENT\",\"kind\":\"REFERENCES\","
                + "\"attrs\":{\"columns\":[\"A_ID\"],\"refColumns\":[\"A\"]}}").reason()).isEqualTo(FailReason.ATTR_MISMATCH);
    }

    // ------------------------------------------------------------------ undirected / statement

    @Test
    @DisplayName("undirected: 방향을 무시하고 테이블 쌍으로 비교하며, 뒤집힌 엣지는 leftColumns와 rightColumns를 바꿔 비교한다")
    void undirectedJoin() {
        nodes("db:table:TB_ORDER", "db:table:TB_CODE");
        add(edge("db:table:TB_CODE", EdgeKind.JOINS, "db:table:TB_ORDER")
                .attr("leftColumns", List.of("CODE")).attr("rightColumns", List.of("ORDER_STATUS")).attr("statement", "mybatis:stmt:ns.list"));

        String expected = "{\"from\":\"db:table:TB_ORDER\",\"to\":\"db:table:TB_CODE\",\"kind\":\"JOINS\",%s"
                + "\"attrs\":{\"leftColumns\":[\"ORDER_STATUS\"],\"rightColumns\":[\"CODE\"]}}";
        EdgeResult result = checkEdge(expected.formatted("\"undirected\":true,"));
        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.matched().from()).isEqualTo("db:table:TB_CODE");

        // 변이: undirected가 없으면 뒤집힌 엣지는 치지 않는다
        assertThat(checkEdge(expected.formatted("")).reason()).isEqualTo(FailReason.EDGE_MISSING);
        // 변이: 뒤집어 보면 컬럼이 반대로 적혀 있다
        assertThat(checkEdge("{\"from\":\"db:table:TB_ORDER\",\"to\":\"db:table:TB_CODE\",\"kind\":\"JOINS\",\"undirected\":true,"
                + "\"attrs\":{\"leftColumns\":[\"CODE\"],\"rightColumns\":[\"ORDER_STATUS\"]}}").reason()).isEqualTo(FailReason.ATTR_MISMATCH);
    }

    @Test
    @DisplayName("statement: 근거 구문이 같은 엣지를 먼저 고르고, 구문만 다르면 느슨한 통과로 표시한다")
    void joinStatement() {
        nodes("db:table:A", "db:table:B", "db:routine:P", "sql:stmt:P:12");
        add(edge("db:routine:P", EdgeKind.CONTAINS, "sql:stmt:P:12"));
        add(edge("db:table:A", EdgeKind.JOINS, "db:table:B").attr("leftColumns", List.of("ID")).attr("rightColumns", List.of("ID"))
                .attr("statement", "mybatis:stmt:ns.one").location(SourceLocation.of("m.xml", 1)));
        add(edge("db:table:A", EdgeKind.JOINS, "db:table:B").attr("leftColumns", List.of("ID")).attr("rightColumns", List.of("ID"))
                .attr("statement", "mybatis:stmt:ns.two").location(SourceLocation.of("m.xml", 9)));
        add(edge("db:table:A", EdgeKind.JOINS, "db:table:B").attr("leftColumns", List.of("ID")).attr("rightColumns", List.of("ID"))
                .attr("statement", "sql:stmt:P:12").location(SourceLocation.of("p.sql", 12)));
        String base = "{\"from\":\"db:table:A\",\"to\":\"db:table:B\",\"kind\":\"JOINS\",\"undirected\":true,"
                + "\"attrs\":{\"leftColumns\":[\"ID\"],\"rightColumns\":[\"ID\"]},%s}";

        EdgeResult exact = checkEdge(base.formatted("\"statement\":\"mybatis:stmt:ns.two\""));
        assertThat(exact.verdict()).isEqualTo(Verdict.PASS);
        assertThat(exact.matched().attrString("statement")).isEqualTo("mybatis:stmt:ns.two");
        assertThat(exact.loose()).isFalse();

        // 줄 번호가 다른 루틴 구문: statementRoutine이 CONTAINS 하는 구문이면 같은 것으로 본다
        EdgeResult routine = checkEdge(base.formatted("\"statement\":\"sql:stmt:P:10\",\"statementRoutine\":\"db:routine:P\""));
        assertThat(routine.verdict()).isEqualTo(Verdict.PASS);
        assertThat(routine.matched().attrString("statement")).isEqualTo("sql:stmt:P:12");
        assertThat(routine.loose()).isFalse();

        EdgeResult other = checkEdge(base.formatted("\"statement\":\"mybatis:stmt:ns.three\""));
        assertThat(other.verdict()).isEqualTo(Verdict.PASS);
        assertThat(other.loose()).isTrue();
        assertThat(other.detail()).contains("statement");
    }

    // ------------------------------------------------------------------ fromRoutine / fromFile

    @Test
    @DisplayName("fromRoutine: 줄 번호가 달라도 그 루틴이 CONTAINS 하는 구문이 같은 종류의 엣지를 가지면 느슨한 통과")
    void fromRoutine() {
        nodes("db:routine:PKG.CLOSE", "db:routine:PKG.OTHER", "sql:stmt:PKG.CLOSE:46", "sql:stmt:PKG.OTHER:80", "db:table:TB_ORDER", "db:table:TB_HIST");
        add(edge("db:routine:PKG.CLOSE", EdgeKind.CONTAINS, "sql:stmt:PKG.CLOSE:46"));
        add(edge("db:routine:PKG.OTHER", EdgeKind.CONTAINS, "sql:stmt:PKG.OTHER:80"));
        add(edge("sql:stmt:PKG.CLOSE:46", EdgeKind.READS, "db:table:TB_ORDER"));
        add(edge("sql:stmt:PKG.OTHER:80", EdgeKind.READS, "db:table:TB_HIST"));

        EdgeResult result = checkEdge("{\"from\":\"sql:stmt:PKG.CLOSE:47\",\"to\":\"db:table:TB_ORDER\",\"kind\":\"READS\",\"fromRoutine\":\"db:routine:PKG.CLOSE\"}");
        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.loose()).isTrue();
        assertThat(result.matched().from()).isEqualTo("sql:stmt:PKG.CLOSE:46");

        // 변이: fromRoutine이 없으면 줄 번호까지 같아야 한다
        assertThat(checkEdge("{\"from\":\"sql:stmt:PKG.CLOSE:47\",\"to\":\"db:table:TB_ORDER\",\"kind\":\"READS\"}").reason())
                .isEqualTo(FailReason.FROM_NODE_MISSING);
        // 변이: 다른 루틴의 구문이 가진 엣지는 치지 않는다
        assertThat(checkEdge("{\"from\":\"sql:stmt:PKG.CLOSE:47\",\"to\":\"db:table:TB_HIST\",\"kind\":\"READS\",\"fromRoutine\":\"db:routine:PKG.CLOSE\"}").reason())
                .isEqualTo(FailReason.EDGE_MISSING);
        // 변이: 종류가 다르면 치지 않는다
        assertThat(checkEdge("{\"from\":\"sql:stmt:PKG.CLOSE:47\",\"to\":\"db:table:TB_ORDER\",\"kind\":\"WRITES\",\"fromRoutine\":\"db:routine:PKG.CLOSE\"}").reason())
                .isEqualTo(FailReason.EDGE_MISSING);
    }

    @Test
    @DisplayName("fromRoutine: 줄 번호까지 같으면 느슨한 통과가 아니다")
    void fromRoutineExact() {
        nodes("db:routine:P", "sql:stmt:P:15", "db:table:T");
        add(edge("db:routine:P", EdgeKind.CONTAINS, "sql:stmt:P:15"));
        add(edge("sql:stmt:P:15", EdgeKind.WRITES, "db:table:T").attr("op", "INSERT"));

        EdgeResult result = checkEdge("{\"from\":\"sql:stmt:P:15\",\"to\":\"db:table:T\",\"kind\":\"WRITES\",\"fromRoutine\":\"db:routine:P\",\"attrs\":{\"op\":\"INSERT\"}}");
        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.loose()).isFalse();
    }

    @Test
    @DisplayName("fromFile+line: SQL 파일 구문은 그 파일에서 줄이 가까운(범위에 들거나 3줄 이내) 구문이면 느슨한 통과")
    void fromFileStatement() {
        nodes("db:table:TB_CODE");
        graph.declare(Node.builder("sql:stmt:db/codes.sql:40", NodeKind.SQL_STATEMENT).location(SourceLocation.of("db/codes.sql", 40, 1, 42, 30)).build());
        graph.declare(Node.builder("sql:stmt:db/codes.sql:10", NodeKind.SQL_STATEMENT).location(SourceLocation.of("db/codes.sql", 10, 1, 11, 30)).build());
        graph.declare(Node.builder("sql:stmt:db/other.sql:41", NodeKind.SQL_STATEMENT).location(SourceLocation.of("db/other.sql", 41)).build());
        add(edge("sql:stmt:db/codes.sql:40", EdgeKind.WRITES, "db:table:TB_CODE").attr("op", "INSERT"));
        add(edge("sql:stmt:db/other.sql:41", EdgeKind.WRITES, "db:table:TB_CODE").attr("op", "INSERT"));
        String item = "{\"from\":\"sql:stmt:db/codes.sql:%d\",\"to\":\"db:table:TB_CODE\",\"kind\":\"WRITES\",\"fromFile\":\"db/codes.sql\",\"line\":%d,\"attrs\":{\"op\":\"INSERT\"}}";

        EdgeResult near = checkEdge(item.formatted(41, 41));
        assertThat(near.verdict()).isEqualTo(Verdict.PASS);
        assertThat(near.loose()).isTrue();
        assertThat(near.matched().from()).isEqualTo("sql:stmt:db/codes.sql:40");

        // 변이: 같은 파일이지만 줄이 먼 구문만 있으면 실패
        assertThat(checkEdge(item.formatted(70, 70)).verdict()).isEqualTo(Verdict.FAIL);
    }

    // ------------------------------------------------------------------ MATCHES (fromFile / requestUrl / httpMethod)

    private void request(String id, String owner, String method, String path) {
        graph.declare(Node.builder(id, NodeKind.HTTP_REQUEST).attr("httpMethod", method).attr("path", path).build());
        add(edge(owner, EdgeKind.REQUESTS, id));
    }

    private static String matchItem(String file, String method, String url, String to) {
        return "{\"kind\":\"MATCHES\",\"fromFile\":\"" + file + "\",\"line\":13,\"httpMethod\":\"" + method + "\",\"requestUrl\":\"" + url
                + "\",\"to\":\"" + to + "\",\"note\":\"n\"}";
    }

    @Test
    @DisplayName("MATCHES 항목: 그 파일의 요청 중 메서드와 URL이 맞는 것이 to 엔드포인트로 MATCHES를 가지면 통과")
    void requestMatch() {
        nodes("js:function:web/api.js#fetchOrder", "http:endpoint:GET /api/orders/{id}", "http:endpoint:DELETE /api/orders/{id}");
        request("http:request:GET /api/orders/{*}@web/api.js:13", "js:function:web/api.js#fetchOrder", "GET", "/api/orders/{*}");
        request("http:request:DELETE /api/orders/{*}@web/api.js:26", "js:function:web/api.js#fetchOrder", "DELETE", "/api/orders/{*}");
        add(edge("http:request:GET /api/orders/{*}@web/api.js:13", EdgeKind.MATCHES, "http:endpoint:GET /api/orders/{id}"));

        EdgeResult ok = checkEdge(matchItem("web/api.js", "GET", "/api/orders/{*}", "http:endpoint:GET /api/orders/{id}"));
        assertThat(ok.verdict()).isEqualTo(Verdict.PASS);
        assertThat(ok.matched().from()).isEqualTo("http:request:GET /api/orders/{*}@web/api.js:13");

        // 변이: 메서드가 다른 요청은 엣지가 없다
        EdgeResult noEdge = checkEdge(matchItem("web/api.js", "DELETE", "/api/orders/{*}", "http:endpoint:DELETE /api/orders/{id}"));
        assertThat(noEdge.reason()).isEqualTo(FailReason.EDGE_MISSING);
        // 변이: 그 파일에 그런 요청이 없다
        EdgeResult noRequest = checkEdge(matchItem("web/api.js", "POST", "/api/orders", "http:endpoint:GET /api/orders/{id}"));
        assertThat(noRequest.reason()).isEqualTo(FailReason.FROM_NODE_MISSING);
        assertThat(noRequest.detail()).contains("GET /api/orders/{*}");
        // 변이: 다른 파일
        assertThat(checkEdge(matchItem("web/other.js", "GET", "/api/orders/{*}", "http:endpoint:GET /api/orders/{id}")).reason())
                .isEqualTo(FailReason.FROM_NODE_MISSING);
        // 변이: 엔드포인트 노드가 없다
        assertThat(checkEdge(matchItem("web/api.js", "GET", "/api/orders/{*}", "http:endpoint:GET /api/nope")).reason())
                .isEqualTo(FailReason.TO_NODE_MISSING);
    }

    @Test
    @DisplayName("MATCHES 항목: 파일은 REQUESTS 출발 노드의 위치나 ID로 판정한다 (JSP 페이지, 그 JSP를 주인으로 하는 인라인 모듈 포함)")
    void requestMatchFileOwnership() {
        String jsp = "src/main/webapp/WEB-INF/views/order/list.jsp";
        nodes("jsp:page:" + jsp, "js:module:" + jsp, "http:endpoint:GET /order/list", "http:endpoint:POST /order/close.do", "http:endpoint:ANY /order/history.do");
        // ID에 파일이 들어 있지 않은(다른 표기의) 요청 노드도 출발 노드로 파일을 안다
        request("http:request:GET /order/list@form-1", "jsp:page:" + jsp, "GET", "/order/list");
        request("http:request:POST /order/close.do@inline-2", "js:module:" + jsp, "POST", "/order/close.do");
        graph.declare(Node.builder("js:function:x#load", NodeKind.JS_FUNCTION).location(SourceLocation.of(jsp, 54)).build());
        request("http:request:GET /order/history.do@inline-3", "js:function:x#load", "GET", "/order/history.do");
        add(edge("http:request:GET /order/list@form-1", EdgeKind.MATCHES, "http:endpoint:GET /order/list"));
        add(edge("http:request:POST /order/close.do@inline-2", EdgeKind.MATCHES, "http:endpoint:POST /order/close.do").inferred());
        add(edge("http:request:GET /order/history.do@inline-3", EdgeKind.MATCHES, "http:endpoint:ANY /order/history.do"));

        assertThat(checkEdge(matchItem(jsp, "GET", "/order/list", "http:endpoint:GET /order/list")).verdict()).isEqualTo(Verdict.PASS);
        assertThat(checkEdge(matchItem(jsp, "POST", "/order/close.do", "http:endpoint:POST /order/close.do")).verdict()).isEqualTo(Verdict.INFERRED_PASS);
        assertThat(checkEdge(matchItem(jsp, "GET", "/order/history.do", "http:endpoint:ANY /order/history.do")).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    @DisplayName("MATCHES 항목: attrs.path가 없으면 ID의 URL로 비교하고, 쿼리스트링과 맨 앞의 {*}/ 접두사는 떼고 본다. UNRESOLVED 엣지는 치지 않는다")
    void requestMatchUrlNormalization() {
        nodes("jsp:page:a.jsp", "http:endpoint:GET /order/detail", "http:endpoint:GET /main.do");
        graph.declare(Node.builder("http:request:GET {*}/order/detail?orderId={*}@a.jsp:43", NodeKind.HTTP_REQUEST).build());
        add(edge("jsp:page:a.jsp", EdgeKind.REQUESTS, "http:request:GET {*}/order/detail?orderId={*}@a.jsp:43"));
        add(edge("http:request:GET {*}/order/detail?orderId={*}@a.jsp:43", EdgeKind.MATCHES, "http:endpoint:GET /order/detail"));
        graph.declare(Node.builder("http:request:GET /main.do@a.jsp:6", NodeKind.HTTP_REQUEST).build());
        add(edge("jsp:page:a.jsp", EdgeKind.REQUESTS, "http:request:GET /main.do@a.jsp:6"));
        add(edge("http:request:GET /main.do@a.jsp:6", EdgeKind.MATCHES, "http:endpoint:GET /main.do").unresolved());

        assertThat(checkEdge(matchItem("a.jsp", "GET", "/order/detail", "http:endpoint:GET /order/detail")).verdict()).isEqualTo(Verdict.PASS);
        assertThat(checkEdge(matchItem("a.jsp", "GET", "/main.do", "http:endpoint:GET /main.do")).reason()).isEqualTo(FailReason.EDGE_UNRESOLVED);
    }

    // ------------------------------------------------------------------ must-have 노드

    @Test
    @DisplayName("노드: ID가 있고 종류가 같으면 통과. 없으면 NODE_MISSING, 종류가 다르면 KIND_MISMATCH")
    void nodeExistence() {
        nodes("db:table:TB_ORDER", "java:type:a.A");
        add(edge("java:type:a.A", EdgeKind.EXTENDS, "java:type:a.Gone"));
        graph.resolveDanglingEdges();

        assertThat(checkNode("{\"id\":\"db:table:TB_ORDER\",\"kind\":\"DB_TABLE\"}").verdict()).isEqualTo(Verdict.PASS);
        assertThat(checkNode("{\"id\":\"db:table:TB_NONE\",\"kind\":\"DB_TABLE\"}").reason()).isEqualTo(FailReason.NODE_MISSING);
        assertThat(checkNode("{\"id\":\"db:table:TB_ORDER\",\"kind\":\"DB_VIEW\"}").reason()).isEqualTo(FailReason.KIND_MISMATCH);
        // 매달린 참조로만 있는 노드는 없는 것이다
        assertThat(checkNode("{\"id\":\"java:type:a.Gone\",\"kind\":\"JAVA_TYPE\"}").reason()).isEqualTo(FailReason.NODE_MISSING);
    }

    @Test
    @DisplayName("노드 attrs: 문자열, 숫자, 불리언을 비교한다. dataType은 공백과 대소문자를 정규화하고 그래도 다르면 불일치")
    void nodeAttrs() {
        graph.declare(Node.builder("db:column:TB_ORDER.TOTAL_AMT", NodeKind.DB_COLUMN)
                .attr("dataType", "number(15, 2)").attr("nullable", false).attr("primaryKey", false).attr("ordinal", 5).attr("comment", "주문총액").build());
        String item = "{\"id\":\"db:column:TB_ORDER.TOTAL_AMT\",\"kind\":\"DB_COLUMN\",\"attrs\":%s}";

        assertThat(checkNode(item.formatted("{\"dataType\":\"NUMBER(15,2)\",\"nullable\":false,\"primaryKey\":false,\"ordinal\":5,\"comment\":\"주문총액\"}")).verdict())
                .isEqualTo(Verdict.PASS);

        NodeResult type = checkNode(item.formatted("{\"dataType\":\"NUMBER(12)\"}"));
        assertThat(type.reason()).isEqualTo(FailReason.ATTR_MISMATCH);
        assertThat(type.detail()).contains("dataType").contains("NUMBER(12)").contains("number(15, 2)");

        assertThat(checkNode(item.formatted("{\"nullable\":true}")).reason()).isEqualTo(FailReason.ATTR_MISMATCH);
        assertThat(checkNode(item.formatted("{\"ordinal\":6}")).reason()).isEqualTo(FailReason.ATTR_MISMATCH);
        assertThat(checkNode(item.formatted("{\"comment\":\"주문 총액\"}")).reason()).isEqualTo(FailReason.ATTR_MISMATCH);
        assertThat(checkNode(item.formatted("{\"schema\":\"SHOP\"}")).reason()).isEqualTo(FailReason.ATTR_MISMATCH);
    }

    @Test
    @DisplayName("노드 attrs: declared=false인 참조 노드, 없는 불리언 속성은 false로 본다")
    void nodeBooleanAttrs() {
        graph.reference("db:table:TB_HOLIDAY", NodeKind.DB_TABLE, "TB_HOLIDAY");
        graph.declare(Node.builder("db:column:T.C", NodeKind.DB_COLUMN).attr("dataType", "DATE").build());

        assertThat(checkNode("{\"id\":\"db:table:TB_HOLIDAY\",\"kind\":\"DB_TABLE\",\"attrs\":{\"declared\":false}}").verdict()).isEqualTo(Verdict.PASS);
        assertThat(checkNode("{\"id\":\"db:table:TB_HOLIDAY\",\"kind\":\"DB_TABLE\",\"attrs\":{\"declared\":true}}").reason()).isEqualTo(FailReason.ATTR_MISMATCH);
        assertThat(checkNode("{\"id\":\"db:column:T.C\",\"kind\":\"DB_COLUMN\",\"attrs\":{\"primaryKey\":false}}").verdict()).isEqualTo(Verdict.PASS);
        assertThat(checkNode("{\"id\":\"db:column:T.C\",\"kind\":\"DB_COLUMN\",\"attrs\":{\"primaryKey\":true}}").reason()).isEqualTo(FailReason.ATTR_MISMATCH);
    }

    // ------------------------------------------------------------------ must-not (와일드카드)

    @Test
    @DisplayName("함정: 정확한 from/to/kind가 있으면 위반, 종류나 방향이 다르면 위반이 아니다")
    void mustNotExact() {
        nodes("db:table:TB_ORDER", "db:table:TB_PAYMENT");
        add(edge("db:table:TB_ORDER", EdgeKind.REFERENCES, "db:table:TB_PAYMENT"));

        List<Violation> found = violations("{\"from\":\"db:table:TB_ORDER\",\"to\":\"db:table:TB_PAYMENT\",\"kind\":\"REFERENCES\",\"note\":\"방향이 반대다\"}");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).edges()).hasSize(1);
        assertThat(found.get(0).rule().note()).isEqualTo("방향이 반대다");

        assertThat(violations("{\"from\":\"db:table:TB_PAYMENT\",\"to\":\"db:table:TB_ORDER\",\"kind\":\"REFERENCES\"}")).isEmpty();
        assertThat(violations("{\"from\":\"db:table:TB_ORDER\",\"to\":\"db:table:TB_PAYMENT\",\"kind\":\"JOINS\"}")).isEmpty();
    }

    @Test
    @DisplayName("함정 와일드카드: from '*', kind '*'는 아무 노드·아무 종류")
    void mustNotAnyFromAnyKind() {
        nodes("mybatis:stmt:ns.a", "file:db/t.sql", "db:table:DUAL", "db:table:DUAL2");
        add(edge("mybatis:stmt:ns.a", EdgeKind.READS, "db:table:DUAL"));
        add(edge("file:db/t.sql", EdgeKind.CONTAINS, "db:table:DUAL"));
        add(edge("mybatis:stmt:ns.a", EdgeKind.READS, "db:table:DUAL2"));

        List<Violation> found = violations("{\"from\":\"*\",\"to\":\"db:table:DUAL\",\"kind\":\"*\"}");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).edges()).extracting(Edge::kind).containsExactlyInAnyOrder(EdgeKind.READS, EdgeKind.CONTAINS);

        assertThat(violations("{\"from\":\"*\",\"to\":\"db:table:DUAL\",\"kind\":\"WRITES\"}")).isEmpty();
        assertThat(violations("{\"from\":\"*\",\"to\":\"db:table:DUA\",\"kind\":\"*\"}")).isEmpty();
    }

    @Test
    @DisplayName("함정 와일드카드: 끝이 '*'인 ID는 접두사, to '*'는 아무 노드. 미해결 노드로 가는 엣지는 접두사에 걸리지 않는다")
    void mustNotPrefix() {
        nodes("mybatis:stmt:orderDao.backup", "db:table:TB_ORDER_ARC_2024", "db:table:TB_ORDER", "http:endpoint:GET /products/{id}",
                "http:endpoint:GET /api/products", "java:method:a.C#get()");
        add(edge("mybatis:stmt:orderDao.backup", EdgeKind.READS, "db:table:TB_ORDER_ARC_2024"));
        graph.addUnresolvedEdge("mybatis:stmt:orderDao.backup", EdgeKind.READS, "sql-dynamic", "mybatis:stmt:orderDao.backup", null);
        add(edge("http:endpoint:GET /products/{id}", EdgeKind.ROUTES_TO, "java:method:a.C#get()"));
        add(edge("http:endpoint:GET /api/products", EdgeKind.ROUTES_TO, "java:method:a.C#get()"));

        List<Violation> tables = violations("{\"from\":\"mybatis:stmt:orderDao.backup\",\"to\":\"db:table:*\",\"kind\":\"READS\"}");
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).edges()).extracting(Edge::to).containsExactly("db:table:TB_ORDER_ARC_2024");

        assertThat(violations("{\"from\":\"*\",\"to\":\"db:table:TB_ORDER_ARC_*\",\"kind\":\"*\"}")).hasSize(1);
        assertThat(violations("{\"from\":\"*\",\"to\":\"db:table:TB_ORDER_BAK*\",\"kind\":\"*\"}")).isEmpty();

        List<Violation> routes = violations("{\"from\":\"http:endpoint:GET /products*\",\"to\":\"*\",\"kind\":\"ROUTES_TO\"}");
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).edges()).extracting(Edge::from).containsExactly("http:endpoint:GET /products/{id}");
    }

    @Test
    @DisplayName("함정 fromRoutine: 그 루틴이 CONTAINS 하는 구문에서 나가는 엣지만 금지한다")
    void mustNotFromRoutine() {
        nodes("db:routine:SP_PURGE", "db:routine:SP_OTHER", "sql:stmt:SP_PURGE:42", "sql:stmt:SP_OTHER:10", "db:table:TB_LOG");
        add(edge("db:routine:SP_PURGE", EdgeKind.CONTAINS, "sql:stmt:SP_PURGE:42"));
        add(edge("db:routine:SP_OTHER", EdgeKind.CONTAINS, "sql:stmt:SP_OTHER:10"));
        add(edge("sql:stmt:SP_OTHER:10", EdgeKind.WRITES, "db:table:TB_LOG"));
        String rule = "{\"from\":\"*\",\"to\":\"db:table:*\",\"kind\":\"WRITES\",\"fromRoutine\":\"db:routine:SP_PURGE\"}";

        assertThat(violations(rule)).isEmpty();

        add(edge("sql:stmt:SP_PURGE:42", EdgeKind.READS, "db:table:TB_LOG"));
        assertThat(violations(rule)).isEmpty();

        add(edge("sql:stmt:SP_PURGE:42", EdgeKind.WRITES, "db:table:TB_LOG"));
        List<Violation> found = violations(rule);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).edges()).extracting(Edge::from).containsExactly("sql:stmt:SP_PURGE:42");
    }

    @Test
    @DisplayName("함정 attrs: 그 속성까지 같은 엣지만 금지한다")
    void mustNotAttrs() {
        nodes("db:table:TB_PAYMENT", "db:table:TB_ORDER");
        add(edge("db:table:TB_PAYMENT", EdgeKind.REFERENCES, "db:table:TB_ORDER").inferred().attr("source", "INFERRED"));
        String rule = "{\"from\":\"db:table:TB_PAYMENT\",\"to\":\"db:table:TB_ORDER\",\"kind\":\"REFERENCES\",\"attrs\":{\"source\":\"DDL\"}}";

        assertThat(violations(rule)).isEmpty();

        add(edge("db:table:TB_PAYMENT", EdgeKind.REFERENCES, "db:table:TB_ORDER").attr("source", "DDL").location(SourceLocation.of("c.sql", 25)));
        assertThat(violations(rule)).hasSize(1);
    }

    @Test
    @DisplayName("함정 MATCHES 항목: 그 파일의 요청(메서드와 URL 또는 줄이 맞는 것)이 to 패턴의 엔드포인트와 맞으면 위반")
    void mustNotRequestMatch() {
        nodes("js:module:web/api.js", "http:endpoint:GET /api/orders/{id}", "http:endpoint:DELETE /api/orders/{id}", "http:endpoint:GET /api/orders/export");
        request("http:request:DELETE /api/orders/{*}@web/api.js:26", "js:module:web/api.js", "DELETE", "/api/orders/{*}");
        add(edge("http:request:DELETE /api/orders/{*}@web/api.js:26", EdgeKind.MATCHES, "http:endpoint:DELETE /api/orders/{id}"));
        String rule = "{\"kind\":\"MATCHES\",\"fromFile\":\"web/api.js\",\"line\":26,\"httpMethod\":\"DELETE\",\"requestUrl\":\"/api/orders/{*}\","
                + "\"to\":\"http:endpoint:GET /api/orders/{id}\"}";

        assertThat(violations(rule)).isEmpty();

        add(edge("http:request:DELETE /api/orders/{*}@web/api.js:26", EdgeKind.MATCHES, "http:endpoint:GET /api/orders/{id}").inferred());
        assertThat(violations(rule)).hasSize(1);

        // URL이 전부 동적인 요청: 줄로 찾고, to는 접두사 와일드카드
        request("http:request:GET {*}@web/api.js:46", "js:module:web/api.js", "GET", "{*}");
        String dynamic = "{\"kind\":\"MATCHES\",\"fromFile\":\"web/api.js\",\"line\":46,\"httpMethod\":\"GET\",\"requestUrl\":\"{*}\",\"to\":\"http:endpoint:*\"}";
        assertThat(violations(dynamic)).isEmpty();
        graph.addUnresolvedEdge("http:request:GET {*}@web/api.js:46", EdgeKind.MATCHES, "http-endpoint", "GET {*}", null);
        assertThat(violations(dynamic)).isEmpty();
        add(edge("http:request:GET {*}@web/api.js:46", EdgeKind.MATCHES, "http:endpoint:GET /api/orders/export"));
        assertThat(violations(dynamic)).hasSize(1);
    }

    // ------------------------------------------------------------------ 집계와 읽기

    @Test
    @DisplayName("집계: 종류별 통과/전체, INFERRED 통과, certainty=low 항목은 따로 센다")
    void tallies() {
        nodes("java:type:a.A", "java:type:a.B", "java:type:a.C", "db:table:T");
        add(edge("java:type:a.A", EdgeKind.EXTENDS, "java:type:a.B"));
        add(edge("java:type:a.A", EdgeKind.INJECTS, "java:type:a.C").inferred());
        ExpectedSet expected = ExpectedSet.parse("""
                [{"from":"java:type:a.A","to":"java:type:a.B","kind":"EXTENDS"},
                 {"from":"java:type:a.B","to":"java:type:a.C","kind":"EXTENDS"},
                 {"from":"java:type:a.A","to":"java:type:a.C","kind":"INJECTS"},
                 {"from":"java:type:a.A","to":"java:type:java.lang.Object","kind":"EXTENDS","certainty":"low"},
                 {"from":"java:type:a.A","to":"java:type:a.B","kind":"EXTENDS","certainty":"low"}]""",
                """
                [{"id":"db:table:T","kind":"DB_TABLE"},
                 {"id":"db:table:U","kind":"DB_TABLE"},
                 {"id":"db:view:V","kind":"DB_VIEW","certainty":"low"}]""",
                """
                [{"from":"java:type:a.A","to":"java:type:a.B","kind":"EXTENDS"},
                 {"from":"*","to":"db:table:T","kind":"*"},
                 {"from":"java:type:a.A","to":"java:type:a.C","kind":"INJECTS","certainty":"low"}]""");

        VerificationResult result = new ExpectedChecker(graph, CheckOptions.defaults()).check(expected);

        assertThat(result.edgeTally()).isEqualTo(new Tally(3, 1, 1, 1));
        assertThat(result.edgeTally().passRate()).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.edgesByKind()).containsEntry("EXTENDS", new Tally(2, 1, 0, 1)).containsEntry("INJECTS", new Tally(1, 0, 1, 0));
        assertThat(result.lowEdgeTally()).isEqualTo(new Tally(2, 1, 0, 1));
        assertThat(result.nodeTally()).isEqualTo(new Tally(2, 1, 0, 1));
        assertThat(result.nodesByKind()).containsEntry("DB_TABLE", new Tally(2, 1, 0, 1));
        assertThat(result.lowNodeTally()).isEqualTo(new Tally(1, 0, 0, 1));
        assertThat(result.edgeFailuresByReason()).containsEntry(FailReason.EDGE_MISSING, 1);
        assertThat(result.mustNotTotal()).isEqualTo(3);
        assertThat(result.violations()).hasSize(2);
        assertThat(result.hardViolations()).hasSize(1);
        assertThat(new Tally(0, 0, 0, 0).passRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("정답 디렉터리 읽기: 핵심 파일과 -extra 파일을 합치고, extra가 없어도 읽는다")
    void loadDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("must-have-edges.json"), "[{\"from\":\"a:b:1\",\"to\":\"a:b:2\",\"kind\":\"CALLS\",\"note\":\"x\"}]");
        Files.writeString(dir.resolve("must-have-edges-extra.json"), "[{\"from\":\"a:b:3\",\"to\":\"a:b:4\",\"kind\":\"CALLS\",\"altTo\":[\"a:b:5\"]}]");
        Files.writeString(dir.resolve("must-have-nodes.json"), "[{\"id\":\"db:table:T\",\"kind\":\"DB_TABLE\",\"attrs\":{\"declared\":true,\"ordinal\":1}}]");
        Files.writeString(dir.resolve("must-not-have-edges.json"), "[{\"from\":\"*\",\"to\":\"db:table:DUAL\",\"kind\":\"*\"}]");

        ExpectedSet expected = ExpectedSet.load(dir);

        assertThat(expected.mustHaveEdges()).hasSize(2);
        assertThat(expected.mustHaveEdges().get(1).source()).isEqualTo("must-have-edges-extra.json");
        assertThat(expected.mustHaveEdges().get(1).altTo()).containsExactly("a:b:5");
        assertThat(expected.mustHaveNodes()).singleElement().satisfies(n -> assertThat(n.attrs()).containsEntry("declared", true).containsEntry("ordinal", 1));
        assertThat(expected.mustNotEdges()).hasSize(1);
    }

    @Test
    @DisplayName("정답 읽기: 모르는 엣지·노드 종류나 from도 fromFile도 없는 항목은 읽을 때 거부한다")
    void rejectsMalformedExpectations() {
        assertThatThrownBy(() -> ExpectedSet.parse("[{\"from\":\"a\",\"to\":\"b\",\"kind\":\"CALLZ\"}]", "[]", "[]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CALLZ");
        assertThatThrownBy(() -> ExpectedSet.parse("[{\"from\":\"a\",\"to\":\"b\",\"kind\":\"*\"}]", "[]", "[]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpectedSet.parse("[{\"to\":\"b\",\"kind\":\"CALLS\"}]", "[]", "[]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpectedSet.parse("[]", "[{\"id\":\"x\",\"kind\":\"TABLE\"}]", "[]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TABLE");
        assertThat(ExpectedSet.parse("[]", "[]", "[{\"from\":\"a\",\"to\":\"b\",\"kind\":\"*\"}]").mustNotEdges()).hasSize(1);
    }
}
