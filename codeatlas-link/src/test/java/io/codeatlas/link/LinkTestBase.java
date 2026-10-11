package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

/** 링커 테스트 공통: 계약대로 노드·엣지를 손으로 넣는 도우미. */
abstract class LinkTestBase {

    protected TestProject project;
    protected AnalysisContext ctx;
    protected GraphModel g;
    private int line;

    @BeforeEach
    void setUpProject() {
        project = TestProject.create();
        rescan();
    }

    @AfterEach
    void closeProject() {
        project.close();
    }

    /** 파일이나 옵션을 바꾼 뒤 컨텍스트를 새로 만든다. */
    protected void rescan() {
        ctx = project.context();
        g = ctx.graph();
    }

    protected void run(Object... linkers) {
        TestProject.run(ctx, linkers);
    }

    /** 부를 때마다 다른 위치. 같은 (from, to, kind)의 엣지를 여러 개 넣을 때 쓴다. */
    protected SourceLocation loc() {
        return SourceLocation.of("src/x.xml", ++line);
    }

    // ---- DB ----

    protected String table(String name, String... pk) {
        Node.Builder b = Node.builder(Ids.table(name), NodeKind.DB_TABLE).name(name);
        if (pk.length > 0) {
            b.attr("primaryKey", List.of(pk));
        }
        return g.declare(b.build()).id();
    }

    protected String stubTable(String name) {
        return g.reference(Ids.table(name), NodeKind.DB_TABLE, name).id();
    }

    protected String view(String name) {
        return g.declare(Node.of(Ids.view(name), NodeKind.DB_VIEW, name)).id();
    }

    protected String column(String table, String name, String dataType) {
        String id = Ids.column(table, name);
        g.declare(Node.builder(id, NodeKind.DB_COLUMN).name(name).attr("dataType", dataType).build());
        String owner = g.hasNode(Ids.view(table)) ? Ids.view(table) : Ids.table(table);
        g.addEdge(Edge.of(owner, id, EdgeKind.CONTAINS));
        return id;
    }

    /** 시노님과 ALIAS_OF. sql 분석기는 대상을 항상 db:table:로 참조한다. */
    protected String synonym(String name, String targetId) {
        String id = g.declare(Node.of(Ids.synonym(name), NodeKind.DB_SYNONYM, name)).id();
        g.addEdge(Edge.of(id, targetId, EdgeKind.ALIAS_OF));
        return id;
    }

    protected String routine(String fullName) {
        String id = "db:routine:" + fullName;
        return g.declare(Node.of(id, NodeKind.DB_ROUTINE, fullName)).id();
    }

    protected String stubRoutine(String fullName) {
        String id = "db:routine:" + fullName;
        return g.reference(id, NodeKind.DB_ROUTINE, fullName).id();
    }

    protected String stmt(String namespace, String statementId) {
        String id = Ids.mybatisStatement(namespace, statementId);
        return g.declare(Node.builder(id, NodeKind.SQL_STATEMENT).name(statementId)
                .attr("namespace", namespace).attr("statementId", statementId).build()).id();
    }

    protected Edge edge(String from, EdgeKind kind, String to) {
        return g.addEdge(Edge.of(from, to, kind, loc()));
    }

    protected Edge joins(String left, String right, List<String> leftColumns, List<String> rightColumns, String statement) {
        return g.addEdge(Edge.builder(left, right, EdgeKind.JOINS).location(loc())
                .attr("leftColumns", leftColumns).attr("rightColumns", rightColumns).attr("statement", statement).build());
    }

    // ---- Java ----

    protected String type(String binaryName, String typeKind) {
        String simple = binaryName.substring(binaryName.lastIndexOf('.') + 1);
        return g.declare(Node.builder(Ids.javaType(binaryName), NodeKind.JAVA_TYPE).name(simple).qualifiedName(binaryName)
                .location(SourceLocation.of("src/main/java/" + binaryName.replace('.', '/') + ".java", 1))
                .attr("typeKind", typeKind).build()).id();
    }

    protected String method(String typeBinaryName, String name, String... params) {
        String id = Ids.javaMethod(typeBinaryName, name, params);
        g.declare(Node.builder(id, NodeKind.JAVA_METHOD).name(name)
                .location(SourceLocation.of("src/main/java/" + typeBinaryName.replace('.', '/') + ".java", 10))
                .attr("parameterTypes", List.of(params)).build());
        g.addEdge(Edge.of(Ids.javaType(typeBinaryName), id, EdgeKind.CONTAINS));
        return id;
    }

    protected String bean(String name, String className) {
        return g.declare(Node.builder(Ids.springBean(name), NodeKind.SPRING_BEAN).name(name).attr("class", className).build()).id();
    }

    // ---- HTTP, 화면 ----

    protected String endpoint(String httpMethod, String path) {
        String id = Ids.httpEndpoint(httpMethod, path);
        return g.declare(Node.builder(id, NodeKind.HTTP_ENDPOINT).name(path)
                .attr("httpMethod", Ids.httpMethod(httpMethod)).attr("path", path).build()).id();
    }

    protected Node.Builder requestBuilder(String httpMethod, String url) {
        int at = ++line;
        return Node.builder(Ids.httpRequest(httpMethod, url, "web/a.js", at), NodeKind.HTTP_REQUEST)
                .location(SourceLocation.of("web/a.js", at))
                .attr("httpMethod", Ids.httpMethod(httpMethod)).attr("url", url);
    }

    protected String request(String httpMethod, String url) {
        return g.declare(requestBuilder(httpMethod, url).build()).id();
    }

    /** 주어진 파일에서 나가는 요청. 모듈 경계·상대 링크 기준을 볼 때 쓴다. */
    protected String requestFrom(String file, String httpMethod, String url) {
        int at = ++line;
        Node.Builder b = Node.builder(Ids.httpRequest(httpMethod, url, file, at), NodeKind.HTTP_REQUEST)
                .location(SourceLocation.of(file, at))
                .attr("httpMethod", Ids.httpMethod(httpMethod)).attr("url", url).attr("path", url);
        if (!url.startsWith("/") && !url.startsWith("{*}") && !url.contains("://")) {
            b.attr("relative", true);
        }
        return g.declare(b.build()).id();
    }

    /** 주어진 소스 파일에 선언된 타입. */
    protected String typeAt(String file, String binaryName) {
        String simple = binaryName.substring(binaryName.lastIndexOf('.') + 1);
        return g.declare(Node.builder(Ids.javaType(binaryName), NodeKind.JAVA_TYPE).name(simple).qualifiedName(binaryName)
                .location(SourceLocation.of(file, 1)).attr("typeKind", "class").build()).id();
    }

    /** 주어진 소스 파일에 선언된 메서드. */
    protected String methodAt(String file, String typeBinaryName, String name) {
        String id = Ids.javaMethod(typeBinaryName, name);
        g.declare(Node.builder(id, NodeKind.JAVA_METHOD).name(name).location(SourceLocation.of(file, 10)).build());
        return id;
    }

    /** 엔드포인트와 그 핸들러(ROUTES_TO). 핸들러는 주어진 소스 파일에 있다. 같은 엔드포인트에 여러 번 부르면 핸들러가 늘어난다. */
    protected String routedEndpoint(String httpMethod, String path, String handlerFile, String typeBinaryName, String methodName) {
        String ep = endpoint(httpMethod, path);
        g.addEdge(Edge.of(ep, methodAt(handlerFile, typeBinaryName, methodName), EdgeKind.ROUTES_TO));
        return ep;
    }

    protected String jsp(String relativePath) {
        return g.declare(Node.of(Ids.jspPage(relativePath), NodeKind.JSP_PAGE, relativePath)).id();
    }

    // ---- 조회 ----

    protected List<Edge> out(String from, EdgeKind kind) {
        return g.outgoing(from, kind);
    }

    protected Edge only(String from, EdgeKind kind) {
        List<Edge> edges = out(from, kind);
        if (edges.size() != 1) {
            throw new AssertionError(from + " 의 " + kind + " 엣지가 하나가 아니다: " + edges);
        }
        return edges.get(0);
    }

    protected List<Diagnostic> diagnostics(String code) {
        return g.diagnostics().stream().filter(d -> d.code().equals(code)).toList();
    }

    protected static boolean inferred(Edge e) {
        return e.confidence() == Confidence.INFERRED;
    }
}
