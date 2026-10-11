package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapperBindingLinkerTest extends LinkTestBase {

    private static final String NS = "com.a.OrderMapper";

    @Test
    @DisplayName("F-012-AC9 구문의 namespace와 같은 타입의 같은 이름 메서드 전부에 EXECUTES를 만든다")
    void bindsAllOverloads() {
        type(NS, "interface");
        String m1 = method(NS, "find", "long");
        String m2 = method(NS, "find", "java.lang.String");
        String other = method(NS, "save", "com.a.Order");
        String stmt = stmt(NS, "find");

        run(new MapperBindingLinker());

        for (String m : List.of(m1, m2)) {
            Edge e = only(m, EdgeKind.EXECUTES);
            assertThat(e.to()).isEqualTo(stmt);
            assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
        }
        assertThat(out(other, EdgeKind.EXECUTES)).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC9 애너테이션 SQL 구문도 같은 규칙으로 잇고, 이미 있는 엣지는 다시 만들지 않는다")
    void annotationStatementAndNoDuplicate() {
        type(NS, "interface");
        String m = method(NS, "count");
        String stmt = Ids.mybatisStatement(NS, "count");
        g.declare(Node.builder(stmt, NodeKind.SQL_STATEMENT).attr("namespace", NS).attr("statementId", "count")
                .attr("annotation", true).build());
        String m2 = method(NS, "exists");
        String stmt2 = stmt(NS, "exists");
        edge(m2, EdgeKind.EXECUTES, stmt2);   // java가 이미 만든 엣지

        run(new MapperBindingLinker());

        assertThat(only(m, EdgeKind.EXECUTES).to()).isEqualTo(stmt);
        assertThat(out(m2, EdgeKind.EXECUTES)).hasSize(1);
    }

    @Test
    @DisplayName("F-012-AC9 namespace·statementId 속성이 없으면 ID에서 얻고, 타입이 없는 namespace는 건너뛴다")
    void fallbackToIdAndMissingType() {
        type(NS, "interface");
        String m = method(NS, "find");
        String stmt = Ids.mybatisStatement(NS, "find");
        g.declare(Node.of(stmt, NodeKind.SQL_STATEMENT, "find"));
        stmt("orderDao", "selectList");   // DAO 방식: namespace가 타입이 아니다
        g.declare(Node.of("sql:stmt:db/a.sql:3", NodeKind.SQL_STATEMENT, "a"));

        run(new MapperBindingLinker());

        assertThat(only(m, EdgeKind.EXECUTES).to()).isEqualTo(stmt);
        assertThat(g.edges(EdgeKind.EXECUTES)).hasSize(1);
        assertThat(g.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC9 상위 인터페이스에서 물려받은 메서드는 INFERRED로 잇는다")
    void inheritedMethod() {
        type(NS, "interface");
        type("com.a.BaseMapper", "interface");
        g.addEdge(Edge.of(Ids.javaType(NS), Ids.javaType("com.a.BaseMapper"), EdgeKind.EXTENDS));
        String base = method("com.a.BaseMapper", "selectById", "long");
        String stmt = stmt(NS, "selectById");

        run(new MapperBindingLinker());

        Edge e = only(base, EdgeKind.EXECUTES);
        assertThat(e.to()).isEqualTo(stmt);
        assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(e.attrString("declaredIn")).isEqualTo(Ids.javaType("com.a.BaseMapper"));
    }

    @Test
    @DisplayName("F-012-AC10 매퍼 인터페이스의 추상 메서드에 구문이 없으면 WARN 진단을 남긴다")
    void mapperMethodWithoutStatement() {
        type(NS, "interface");
        g.declare(Node.of(Ids.mybatisMapper(NS), NodeKind.MYBATIS_MAPPER, "OrderMapper"));
        method(NS, "a");
        String b = method(NS, "b");
        String c = method(NS, "c");
        g.putAttrs(c, java.util.Map.of("modifiers", List.of("public", "default")));
        stmt(NS, "a");
        // stereotype=mapper 이고 매퍼 XML이 없는 인터페이스
        g.declare(Node.builder(Ids.javaType("com.a.MemberMapper"), NodeKind.JAVA_TYPE).qualifiedName("com.a.MemberMapper")
                .attr("typeKind", "interface").attr("stereotype", "mapper").build());
        method("com.a.MemberMapper", "x");
        // 매퍼가 아닌 인터페이스
        type("com.a.OrderService", "interface");
        method("com.a.OrderService", "y");

        run(new MapperBindingLinker());

        assertThat(diagnostics("MAPPER_METHOD_WITHOUT_STATEMENT"))
                .hasSize(2)
                .allMatch(d -> d.severity() == Severity.WARN)
                .anyMatch(d -> d.message().contains(NS + "#b") && d.location().equals(g.node(b).orElseThrow().location()))
                .anyMatch(d -> d.message().contains("com.a.MemberMapper#x"));
    }

    @Test
    @DisplayName("F-012-AC11 shortId EXECUTES는 statementId가 유일하게 맞는 구문으로 INFERRED 엣지를 추가한다")
    void shortIdUniqueMatch() {
        String caller = method("com.a.OrderDao", "one");
        String stmt = stmt("order", "selectOne");
        Edge original = g.addEdge(Edge.builder(caller, "mybatis:stmt:selectOne", EdgeKind.EXECUTES).location(loc())
                .attr("shortId", true).build());

        run(new MapperBindingLinker());

        List<Edge> edges = out(caller, EdgeKind.EXECUTES);
        assertThat(edges).hasSize(2).contains(original);
        Edge added = edges.stream().filter(e -> e.to().equals(stmt)).findFirst().orElseThrow();
        assertThat(added.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(added.attrBoolean("shortId")).isTrue();
        assertThat(added.attrString("resolvedFrom")).isEqualTo("mybatis:stmt:selectOne");
        assertThat(added.location()).isEqualTo(original.location());
    }

    @Test
    @DisplayName("F-012-AC11 같은 statementId의 구문이 둘이면 추가하지 않고, shortId가 없는 엣지는 건드리지 않는다")
    void shortIdAmbiguousOrNotShort() {
        String caller = method("com.a.OrderDao", "one");
        stmt("order", "selectOne");
        stmt("member", "selectOne");
        stmt("member", "selectAll");
        g.addEdge(Edge.builder(caller, "mybatis:stmt:selectOne", EdgeKind.EXECUTES).attr("shortId", true).build());
        g.addEdge(Edge.of(caller, "mybatis:stmt:typo.selectAll", EdgeKind.EXECUTES));

        run(new MapperBindingLinker());

        assertThat(out(caller, EdgeKind.EXECUTES)).extracting(Edge::to)
                .containsExactlyInAnyOrder("mybatis:stmt:selectOne", "mybatis:stmt:typo.selectAll");
    }

    @Test
    @DisplayName("F-012-AC40 물려받은 매퍼 메서드의 EXECUTES에는 namespace(구체 매퍼 타입)와 inherited=true가 있고 INFERRED다. 선언이 그 타입 자신에 있으면 RESOLVED다")
    void inheritedMethodCarriesNamespace() {
        type("com.a.CrudDao", "interface");
        type("com.a.UserDao", "interface");
        type("com.a.RoleDao", "interface");
        g.addEdge(Edge.of(Ids.javaType("com.a.UserDao"), Ids.javaType("com.a.CrudDao"), EdgeKind.EXTENDS));
        g.addEdge(Edge.of(Ids.javaType("com.a.RoleDao"), Ids.javaType("com.a.CrudDao"), EdgeKind.EXTENDS));
        String findList = method("com.a.CrudDao", "findList", "java.lang.Object");
        String ownFindList = method("com.a.RoleDao", "findList", "java.lang.Object");
        String userStmt = stmt("com.a.UserDao", "findList");
        String roleStmt = stmt("com.a.RoleDao", "findList");

        run(new MapperBindingLinker());

        Edge inherited = only(findList, EdgeKind.EXECUTES);
        assertThat(inherited.to()).isEqualTo(userStmt);
        assertThat(inherited.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(inherited.attrString("namespace")).isEqualTo("com.a.UserDao");
        assertThat(inherited.attrBoolean("inherited")).isTrue();
        assertThat(inherited.attrString("declaredIn")).isEqualTo(Ids.javaType("com.a.CrudDao"));
        Edge own = only(ownFindList, EdgeKind.EXECUTES);
        assertThat(own.to()).isEqualTo(roleStmt);
        assertThat(own.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(own.attrs()).doesNotContainKeys("inherited", "declaredIn");
    }

    @Test
    @DisplayName("F-012-AC52 statementPrefix가 있는 미해결 EXECUTES는 그 접두로 시작하는 구문마다 INFERRED EXECUTES(prefixMatch=true)를 더한다. 후보가 5개를 넘거나 없으면 더하지 않는다")
    void statementPrefixMatchesStatements() {
        String general = stmt("LoginUsr", "updateLoginIncorrectGNR");
        String enterprise = stmt("LoginUsr", "updateLoginIncorrectENT");
        stmt("LoginUsr", "selectLogin");
        stmt("Other", "updateLoginIncorrectGNR");
        for (int i = 0; i < 6; i++) {
            stmt("Many", "select" + i);
        }
        String dao = method("com.a.LoginDao", "incorrect");
        Edge unresolved = prefixed(dao, "LoginUsr.updateLoginIncorrect", "update");
        String many = method("com.a.LoginDao", "many");
        prefixed(many, "Many.select", "selectOne");
        String none = method("com.a.LoginDao", "none");
        prefixed(none, "Nothing.select", "selectOne");

        run(new MapperBindingLinker());

        List<Edge> added = g.outgoing(dao, EdgeKind.EXECUTES).stream().filter(e -> e.confidence() == Confidence.INFERRED).toList();
        assertThat(added).extracting(Edge::to).containsExactlyInAnyOrder(general, enterprise);
        assertThat(added).allMatch(e -> e.attrBoolean("prefixMatch") && "LoginUsr.updateLoginIncorrect".equals(e.attrString("statementPrefix"))
                && "update".equals(e.attrString("operation")) && e.location().equals(unresolved.location()));
        // 원래의 미해결 엣지는 남는다
        assertThat(g.outgoing(dao, EdgeKind.EXECUTES)).hasSize(3);
        assertThat(g.outgoing(many, EdgeKind.EXECUTES)).hasSize(1);
        assertThat(g.outgoing(none, EdgeKind.EXECUTES)).hasSize(1);
    }

    private String typedStmt(String namespace, String id, String statementType) {
        String stmt = stmt(namespace, id);
        g.putAttrs(stmt, java.util.Map.of("statementType", statementType));
        return stmt;
    }

    @Test
    @DisplayName("F-012-AC59 접두 구문 ID 연결은 호출의 연산 종류로 거른다: insert·update·delete 호출은 INSERT·UPDATE·DELETE·MERGE·CALL 구문으로만, select* 호출은 SELECT·CALL 구문으로만 잇고, 후보 수(1~5)는 거른 뒤에 센다")
    void statementPrefixFilteredByOperation() {
        String insertNotice = typedStmt("Board", "insertNotice", "INSERT");
        String insertFaq = typedStmt("Board", "insertFaq", "INSERT");
        String deleteNotice = typedStmt("Board", "deleteNotice", "DELETE");
        String call = typedStmt("Board", "callProc", "CALL");
        String selectNotice = typedStmt("Board", "selectNotice", "SELECT");
        String selectFaq = typedStmt("Board", "selectFaq", "SELECT");
        String untyped = stmt("Board", "legacy");
        String writer = method("com.a.BoardDao", "write");
        prefixed(writer, "Board.", "insert");
        String reader = method("com.a.BoardDao", "read");
        prefixed(reader, "Board.", "selectList");
        String other = method("com.a.BoardDao", "other");
        prefixed(other, "Board.", "flushStatements");

        run(new MapperBindingLinker());

        // 구문이 7개라 거르지 않으면 5개를 넘어 하나도 잇지 못한다. 종류를 모르는 구문(statementType 없음)은 거르지 않는다
        assertThat(inferredExecutes(writer)).extracting(Edge::to)
                .containsExactlyInAnyOrder(insertNotice, insertFaq, deleteNotice, call, untyped);
        assertThat(inferredExecutes(reader)).extracting(Edge::to).containsExactlyInAnyOrder(selectNotice, selectFaq, call, untyped);
        assertThat(MapperBindingLinker.operationFits("update", "MERGE")).isTrue();
        assertThat(MapperBindingLinker.operationFits("queryForList", "DELETE")).isFalse();
        // 종류를 모르는 호출은 거르지 않는다: 7개라 잇지 않는다
        assertThat(inferredExecutes(other)).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC60 접두로 풀린 미해결 EXECUTES는 소비자가 숨길 수 있다: 풀린 엣지에 resolvedFrom(미해결 노드 ID)과 같은 위치가 있고, 그 미해결 노드로 들어오는 엣지가 전부 풀렸으면 노드에 resolvedBy=prefixMatch를 남긴다")
    void resolvedPrefixIsMarked() {
        typedStmt("Login", "updateIncorrectGNR", "UPDATE");
        typedStmt("Login", "selectIncorrectGNR", "SELECT");
        String update = method("com.a.LoginDao", "update");
        Edge unresolvedUpdate = prefixed(update, "Login.updateIncorrect", "update");
        typedStmt("Mix", "updateOnly", "UPDATE");
        String shared = method("com.a.LoginDao", "shared");
        prefixed(shared, "Mix.", "update");
        String sharedUnmatched = method("com.a.LoginDao", "sharedUnmatched");
        prefixed(sharedUnmatched, "Mix.", "selectOne");
        for (int i = 0; i < 6; i++) {
            stmt("Many", "s" + i);
        }
        String many = method("com.a.LoginDao", "many");
        Edge unresolvedMany = prefixed(many, "Many.s", "selectOne");

        run(new MapperBindingLinker());

        Edge resolved = inferredExecutes(update).get(0);
        assertThat(resolved.attrString("resolvedFrom")).isEqualTo(unresolvedUpdate.to());
        assertThat(resolved.location()).isEqualTo(unresolvedUpdate.location());
        assertThat(g.node(unresolvedUpdate.to()).orElseThrow().attrString("resolvedBy")).isEqualTo("prefixMatch");
        // 같은 미해결 노드(Mix.{*})로 들어오는 엣지 가운데 풀리지 않은 것이 있으면 노드에는 표시하지 않는다(엣지 단위로는 resolvedFrom으로 판정한다)
        assertThat(inferredExecutes(shared)).hasSize(1);
        assertThat(inferredExecutes(sharedUnmatched)).isEmpty();
        assertThat(g.node(Ids.unresolved("mybatis-statement", "Mix.{*}")).orElseThrow().attrs()).doesNotContainKey("resolvedBy");
        assertThat(g.node(unresolvedMany.to()).orElseThrow().attrs()).doesNotContainKey("resolvedBy");
    }

    private List<Edge> inferredExecutes(String from) {
        return g.outgoing(from, EdgeKind.EXECUTES).stream().filter(e -> e.confidence() == Confidence.INFERRED).toList();
    }

    @Test
    @DisplayName("F-012-AC61 메서드 attrs.statementIdLiterals의 문자열 가운데 그 ID의 매퍼 구문이 실제로 있는 것만 EXECUTES(INFERRED, viaArgument=true)로 잇고 위치는 그 인자의 자리다. 없는 것은 잇지도 미해결로 남기지도 않는다")
    void statementIdPassedAsArgument() {
        String insert = typedStmt("ZipManageDAO", "insertExcelZip", "INSERT");
        String already = typedStmt("ZipManageDAO", "selectZip", "SELECT");
        String service = method("com.a.ZipService", "upload");
        g.putAttrs(service, java.util.Map.of(
                "statementIdLiterals", List.of("ZipManageDAO.insertExcelZip", "fail.common.msg", "ZipManageDAO.selectZip"),
                "statementIdLiteralLines", List.of("42:20", "43:9", "44:1")));
        // 이미 그 구문으로 가는 EXECUTES가 있으면(SqlSession 호출로 잡힌 것) 더하지 않는다
        g.addEdge(Edge.builder(service, already, EdgeKind.EXECUTES).location(loc()).build());

        run(new MapperBindingLinker());

        Edge edge = g.edges(service, EdgeKind.EXECUTES, insert).get(0);
        assertThat(edge.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(edge.attrBoolean("viaArgument")).isTrue();
        assertThat(edge.location().startLine()).isEqualTo(42);
        assertThat(edge.location().file()).isEqualTo(g.node(service).orElseThrow().location().file());
        assertThat(g.outgoing(service, EdgeKind.EXECUTES)).hasSize(2);
        assertThat(g.nodes(io.codeatlas.core.model.NodeKind.UNRESOLVED)).isEmpty();
    }

    private Edge prefixed(String from, String prefix, String operation) {
        String id = Ids.unresolved("mybatis-statement", prefix + "{*}");
        g.declare(io.codeatlas.core.model.Node.builder(id, io.codeatlas.core.model.NodeKind.UNRESOLVED).name(prefix + "{*}")
                .attr("category", "mybatis-statement").build());
        return g.addEdge(Edge.builder(from, id, EdgeKind.EXECUTES).unresolved().location(loc())
                .attr("statementPrefix", prefix).attr("operation", operation).build());
    }
}
