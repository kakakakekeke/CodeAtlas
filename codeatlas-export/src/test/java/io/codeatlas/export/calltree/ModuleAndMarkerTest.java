package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.StandardExporter;
import io.codeatlas.export.calltree.CallTreeNode.Marker;
import io.codeatlas.export.calltree.CrudMatrix.Row;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import io.codeatlas.export.common.NodeLabels;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.ShopGraph;
import io.codeatlas.export.report.SummaryReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** link·java 모듈이 새로 남기는 속성(crossModule, resolvedPath, reflection·modelAttribute 호출, 필터·인터셉터)을 읽는 규칙. */
class ModuleAndMarkerTest {

    @TempDir
    Path dir;

    private static String callees(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callees()), false);
    }

    private static String callers(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callers()), false);
    }

    private static String method(GraphFixture f, String module, String type, String name) {
        String id = Ids.javaMethod(type, name);
        String file = (module.isEmpty() ? "" : module + "/") + "src/main/java/" + type.replace('.', '/') + ".java";
        f.graph().declare(Node.builder(Ids.javaType(type), NodeKind.JAVA_TYPE).name(type.substring(type.lastIndexOf('.') + 1))
                .qualifiedName(type).location(SourceLocation.of(file, 5)).attr("typeKind", "class").build());
        f.graph().declare(Node.builder(id, NodeKind.JAVA_METHOD).name(name).qualifiedName(type + "#" + name + "()")
                .location(SourceLocation.of(file, 20)).attr("modifiers", List.of("public")).build());
        f.edge(Ids.javaType(type), id, EdgeKind.CONTAINS);
        return id;
    }

    @Test
    @DisplayName("F-015-AC47 핸들러가 여러 모듈에 있는 엔드포인트: 요청을 낸 화면과 같은 모듈의 핸들러가 있으면 그것만 따라가고, 엔드포인트에서 시작하면 모듈별로 묶어 전부 보인다")
    void followsHandlerOfTheRequestingModule() {
        GraphFixture f = GraphFixture.create();
        String admin = method(f, "web-admin", "com.m.admin.UserController", "list");
        String front = method(f, "web-front", "com.m.front.UserController", "list");
        String endpoint = f.endpoint("GET", "/user/list", front);
        f.edge(endpoint, admin, EdgeKind.ROUTES_TO);
        String adminJsp = f.jsp("web-admin/src/main/webapp/WEB-INF/views/user.jsp");
        String frontJsp = f.jsp("web-front/src/main/webapp/WEB-INF/views/user.jsp");
        String otherJsp = f.jsp("web-other/src/main/webapp/index.jsp");
        f.request("GET", "/user/list", "web-admin/src/main/webapp/WEB-INF/views/user.jsp", 3, adminJsp, endpoint);
        f.request("GET", "/user/list", "web-front/src/main/webapp/WEB-INF/views/user.jsp", 4, frontJsp, endpoint);
        // 같은 모듈에 핸들러가 없는 화면: 링커가 MATCHES에 crossModule=true를 남긴다
        String otherRequest = f.request("GET", "/user/list", "web-other/src/main/webapp/index.jsp", 9, otherJsp, null);
        f.edge(otherRequest, endpoint, EdgeKind.MATCHES, Map.of("crossModule", true));
        GraphModel g = f.graph();

        assertThat(callees(g, adminJsp)).isEqualTo("""
                [jsp] web-admin/src/main/webapp/WEB-INF/views/user.jsp
                └─ [request] GET /user/list
                   └─ [endpoint] GET /user/list
                      └─ UserController#list()
                """);
        assertThat(CallTrees.toText(CallTrees.build(g, adminJsp, CallTreeOptions.callees()))).contains("UserController#list()  web-admin/src/main/java/com/m/admin/UserController.java:20")
                .doesNotContain("UserController#list()  web-front/src/main/java");
        // 다른 모듈의 화면: 어느 핸들러인지 모르므로 전부, 추론(~)으로 표시한다
        assertThat(callees(g, otherJsp)).isEqualTo("""
                [jsp] web-other/src/main/webapp/index.jsp
                └─ [request] GET /user/list
                   └─ ~ [endpoint] GET /user/list
                      ├─ UserController#list() (모듈 web-admin)
                      └─ UserController#list() (모듈 web-front)
                """);
        assertThat(callees(g, endpoint)).isEqualTo("""
                [endpoint] GET /user/list
                ├─ UserController#list() (모듈 web-admin)
                └─ UserController#list() (모듈 web-front)
                """);
        // 위 방향도 같다: admin 핸들러의 호출자에 front 화면의 요청은 없다
        String up = callers(g, admin);
        assertThat(up).contains("web-admin/src/main/webapp/WEB-INF/views/user.jsp").contains("web-other/src/main/webapp/index.jsp")
                .doesNotContain("web-front/");
        // 핸들러가 한 모듈에만 있으면 모듈 표시가 없다
        assertThat(callees(new ShopGraph().f.graph(), new ShopGraph().getOrder)).doesNotContain("(모듈");
    }

    @Test
    @DisplayName("F-015-AC48 엔드포인트의 표시 이름은 resolvedPath가 있으면 그것을 쓰고 원문을 괄호로 덧붙이며, 검색도 resolvedPath로 된다")
    void endpointLabelUsesResolvedPath() {
        GraphFixture f = GraphFixture.create();
        f.table("SYS_USER");
        String statement = f.statement("com.r.UserDao", "findList", "SELECT");
        f.reads(statement, "SYS_USER");
        String handler = f.method("com.r.UserController", "list");
        f.edge(handler, statement, EdgeKind.EXECUTES);
        String endpoint = f.endpoint("ANY", "/${adminPath}/sys/user/list", handler);
        f.attr(endpoint, "resolvedPath", "/a/sys/user/list");
        String plain = f.endpoint("GET", "/plain", handler);
        GraphModel g = f.graph();

        assertThat(NodeLabels.label(g, endpoint)).isEqualTo("ANY /a/sys/user/list (/${adminPath}/sys/user/list)");
        assertThat(NodeLabels.label(g, plain)).isEqualTo("GET /plain");
        assertThat(callees(g, endpoint)).startsWith("[endpoint] ANY /a/sys/user/list (/${adminPath}/sys/user/list)\n");
        assertThat(CrudMatrix.of(g, Subject.ENDPOINT).rows()).extracting(Row::label)
                .contains("ANY /a/sys/user/list (/${adminPath}/sys/user/list)", "GET /plain");
        for (String query : List.of("/a/sys/user/list", "ANY /a/sys/user/list", "a/sys/user/list", "/${adminPath}/sys/user/list")) {
            assertThat(NodeFinder.find(g, query, NodeKind.HTTP_ENDPOINT)).as(query).extracting(Node::id).containsExactly(endpoint);
        }
    }

    @Test
    @DisplayName("F-015-AC49 @ModelAttribute 메서드로 가는 추론 호출(callType=modelAttribute)은 핸들러의 자식 맨 앞에 [before]로 나온다")
    void modelAttributeCallsComeFirst() {
        GraphFixture f = GraphFixture.create();
        String handler = f.method("com.b.ItemController", "list");
        String populate = f.method("com.b.ItemController", "populate");
        String zeta = f.method("com.b.ItemController", "another");
        String service = f.method("com.b.ItemService", "find");
        String file = "src/main/java/com/b/ItemController.java";
        f.edge(Edge.builder(handler, service, EdgeKind.CALLS).attr("callType", "virtual").location(SourceLocation.of(file, 10)).build());
        // 엣지의 위치는 호출 지점이 아니라 대상 메서드의 선언이다(핸들러보다 아래에 있어도 먼저 실행된다)
        f.edge(Edge.builder(handler, populate, EdgeKind.CALLS).inferred().attr("callType", "modelAttribute")
                .location(SourceLocation.of(file, 90)).build());
        f.edge(Edge.builder(handler, zeta, EdgeKind.CALLS).inferred().attr("callType", "modelAttribute")
                .location(SourceLocation.of(file, 70)).build());
        GraphModel g = f.graph();

        assertThat(callees(g, handler)).isEqualTo("""
                ItemController#list()
                ├─ ~ [before] ItemController#another()
                ├─ ~ [before] ItemController#populate()
                └─ ItemService#find()
                """);
        CallTree tree = CallTrees.build(g, handler, CallTreeOptions.callees());
        assertThat(tree.root().children().get(0).markers()).containsExactly(Marker.BEFORE);
        assertThat(tree.root().children().get(0).detail()).isNull();
        assertThat(CallTrees.toJson(tree)).contains("\"markers\": [\"before\"]");
        assertThat(CallTrees.toMermaid(tree)).contains("-.->|\"before\"|");
        assertThat(callers(g, populate)).isEqualTo("""
                ItemController#populate()
                └─ ~ [before] ItemController#list()
                """);
    }

    @Test
    @DisplayName("F-015-AC50 필터·인터셉터(stereotype=filter, interceptor)가 있으면 그 진입 메서드에서 시작하는 콜트리를 calltree/filters.md로 낸다")
    void writesFilterCallTrees() throws Exception {
        ShopGraph shop = new ShopGraph();
        GraphFixture f = shop.f;
        f.attr(f.type("com.shop.web.LoginFilter", "class", "public"), "stereotype", "filter");
        String doFilter = f.method("com.shop.web.LoginFilter", "doFilter", List.of("ServletRequest", "ServletResponse", "FilterChain"), "void", "public");
        f.attr(doFilter, "redirects", List.of("/login"));
        f.method("com.shop.web.LoginFilter", "init");
        f.calls(doFilter, shop.auditLog);
        f.edge(Edge.builder(doFilter, shop.controllerDetail, EdgeKind.CALLS).inferred().attr("callType", "redirect").build());
        f.attr(f.type("com.shop.web.AuthInterceptor", "class", "public"), "stereotype", "interceptor");
        f.method("com.shop.web.AuthInterceptor", "preHandle");
        f.method("com.shop.web.AuthInterceptor", "afterCompletion");
        f.attr(f.type("com.shop.web.EmptyFilter", "class", "public"), "stereotype", "filter");

        Path out = dir.resolve("filters");
        new StandardExporter().export(f.graph(), out, Map.of());

        String md = Files.readString(out.resolve("calltree/filters.md"), StandardCharsets.UTF_8);
        assertThat(md).startsWith("# 필터·인터셉터 콜트리\n").contains("필터·인터셉터 2개").contains("표기: ");
        assertThat(md).contains("## AuthInterceptor#afterCompletion()\n").contains("## AuthInterceptor#preHandle()\n")
                .contains("## LoginFilter#doFilter(ServletRequest, ServletResponse, FilterChain)\n\n```text\nLoginFilter#doFilter(")
                .contains("AuditHelper#log(String)").contains("~ [redirect] OrderController#detail(Long)")
                .doesNotContain("## LoginFilter#init()").doesNotContain("EmptyFilter");
        assertThat(md.indexOf("## AuthInterceptor#afterCompletion()")).isLessThan(md.indexOf("## LoginFilter#doFilter"));

        // 필터·인터셉터가 없으면 파일을 만들지 않는다
        Path none = dir.resolve("no-filters");
        new StandardExporter().export(new ShopGraph().f.graph(), none, Map.of());
        assertThat(none.resolve("calltree/filters.md")).doesNotExist();
        assertThat(none.resolve("calltree/endpoints.md")).exists();
    }

    @Test
    @DisplayName("F-015-AC51 report.md의 미해결 java-call·java-override는 엣지의 argCount가 있으면 원문의 (…)를 (인자 N개)로 풀어 적는다")
    void unresolvedCallsShowArgumentCount() {
        GraphFixture f = GraphFixture.create();
        String caller = f.method("com.a.A", "run");
        SourceLocation at = SourceLocation.of("src/main/java/com/a/A.java", 30);
        f.graph().addUnresolvedEdge(caller, EdgeKind.CALLS, "java-call", "vo.set(…)", at);
        f.graph().addEdge(Edge.builder(caller, Ids.unresolved("java-call", "vo.set(…)"), EdgeKind.CALLS).unresolved()
                .location(SourceLocation.of("src/main/java/com/a/A.java", 31)).attr("argCount", 2).build());
        f.graph().addEdge(Edge.builder(caller, Ids.unresolved("java-call", "vo.set(…)"), EdgeKind.CALLS).unresolved()
                .location(SourceLocation.of("src/main/java/com/a/A.java", 32)).attr("argCount", 0).build());

        String md = SummaryReport.render(f.graph());

        assertThat(md).contains("| CALLS | `vo.set(…)` | `src/main/java/com/a/A.java:30` |")
                .contains("| CALLS | `vo.set(인자 2개)` | `src/main/java/com/a/A.java:31` |")
                .contains("| CALLS | `vo.set(인자 0개)` | `src/main/java/com/a/A.java:32` |");
    }
}
