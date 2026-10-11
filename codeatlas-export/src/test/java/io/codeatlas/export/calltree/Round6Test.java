package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.common.NodeLabels;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.report.SummaryReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** java·link, web·jsp 4차 수정이 그래프에 새로 남기는 것(MATCHES.module, resolvedFrom·resolvedBy, viaArgument, dynamicSegment, httpMethods, selfSubmit, dynamicDecorator)을 읽는 규칙. */
class Round6Test {

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

    /** 요청 → 엔드포인트 대응에 {@code module}을 적는다(링커가 그 요청이 속한 웹 모듈을 안 경우). */
    private static String request(GraphFixture f, String url, String file, int line, String page, String endpoint, String module) {
        String id = f.request("GET", url, file, line, page, null);
        Edge.Builder matches = Edge.builder(id, endpoint, EdgeKind.MATCHES);
        f.edge(module == null ? matches.build() : matches.attr("module", module).build());
        return id;
    }

    /** 두 웹 모듈이 같은 경로의 핸들러·뷰 컨트롤러를 가진 프로젝트. 화면은 src/main 꼴이 아닌 폴더(WebContent)에 있어 경로로는 모듈을 알 수 없다. */
    private static final class TwoModules {
        final GraphFixture f = GraphFixture.create();
        final String adminPage = f.jsp("admin-web/WebContent/list.jsp");
        final String frontPage = f.jsp("front-web/WebContent/list.jsp");
        final String admin = method(f, "admin-web", "com.m.admin.AdminController", "list");
        final String front = method(f, "front-web", "com.m.front.FrontController", "list");
        final String endpoint = f.endpoint("GET", "/user/list", admin);
        final String home = f.endpoint("GET", "/", null);
        final String adminHome = f.jsp("admin-web/WebContent/index.jsp");
        final String frontHome = f.jsp("front-web/WebContent/index.jsp");
        final String adminTable = f.table("T_ADMIN");
        final String frontTable = f.table("T_FRONT");

        TwoModules() {
            f.edge(endpoint, front, EdgeKind.ROUTES_TO);
            // 뷰 컨트롤러: 모듈마다 선언이 하나씩 있다(엣지의 위치가 그 선언)
            f.edge(Edge.builder(home, adminHome, EdgeKind.RENDERS).location(SourceLocation.of("admin-web/src/main/resources/mvc.xml", 12)).build());
            f.edge(Edge.builder(home, frontHome, EdgeKind.RENDERS).location(SourceLocation.of("front-web/src/main/resources/mvc.xml", 12)).build());
            String adminStatement = f.statement("com.m.AdminMapper", "select", "SELECT");
            String frontStatement = f.statement("com.m.FrontMapper", "select", "SELECT");
            f.reads(adminStatement, "T_ADMIN");
            f.reads(frontStatement, "T_FRONT");
            f.table("T_SHARED");
            String adminShared = f.statement("com.m.AdminMapper", "shared", "SELECT");
            String frontShared = f.statement("com.m.FrontMapper", "shared", "SELECT");
            f.reads(adminShared, "T_SHARED");
            f.reads(frontShared, "T_SHARED");
            f.edge(admin, adminShared, EdgeKind.EXECUTES);
            f.edge(front, frontShared, EdgeKind.EXECUTES);
            f.edge(admin, adminStatement, EdgeKind.EXECUTES);
            f.edge(front, frontStatement, EdgeKind.EXECUTES);
            request(f, "/user/list", "admin-web/WebContent/list.jsp", 7, adminPage, endpoint, "admin-web");
            request(f, "/", "admin-web/WebContent/list.jsp", 9, adminPage, home, "admin-web");
            request(f, "/user/list", "front-web/WebContent/list.jsp", 7, frontPage, endpoint, "front-web");
            request(f, "/", "front-web/WebContent/list.jsp", 9, frontPage, home, "front-web");
        }
    }

    @Test
    @DisplayName("F-015-AC75 MATCHES.module이 있으면 엔드포인트 아래로는 그 모듈의 핸들러(ROUTES_TO)와 그 모듈 선언에서 나온 화면(RENDERS, 엣지 위치)만 따라간다")
    void matchesModuleSelectsHandlersAndViews() {
        TwoModules m = new TwoModules();

        assertThat(callees(m.f.graph(), m.adminPage)).isEqualTo("""
                [jsp] admin-web/WebContent/list.jsp
                ├─ [request] GET /user/list
                │  └─ [endpoint] GET /user/list
                │     └─ AdminController#list()
                │        ├─ [sql] AdminMapper.select <SELECT>
                │        │  └─ [table] T_ADMIN (R)
                │        └─ [sql] AdminMapper.shared <SELECT>
                │           └─ [table] T_SHARED (R)
                └─ [request] GET /
                   └─ [endpoint] GET /
                      └─ [jsp] admin-web/WebContent/index.jsp
                """);
        assertThat(callees(m.f.reversedCopy(), m.adminPage)).isEqualTo(callees(m.f.graph(), m.adminPage));
    }

    @Test
    @DisplayName("F-015-AC75 module이 없는 MATCHES는 종전대로(핸들러 전부, 줄마다 모듈 표시)이고, module에 맞는 핸들러가 없으면 아무것도 따라가지 않는다")
    void matchesWithoutModuleFollowEveryHandler() {
        TwoModules m = new TwoModules();
        String other = m.f.jsp("etc/WebContent/a.jsp");
        request(m.f, "/user/list", "etc/WebContent/a.jsp", 3, other, m.endpoint, null);
        request(m.f, "/user/list", "etc/WebContent/a.jsp", 5, other, m.endpoint, "batch");

        assertThat(callees(m.f.graph(), other)).isEqualTo("""
                [jsp] etc/WebContent/a.jsp
                ├─ [request] GET /user/list
                │  └─ [endpoint] GET /user/list
                │     ├─ AdminController#list() (모듈 admin-web)
                │     │  ├─ [sql] AdminMapper.select <SELECT>
                │     │  │  └─ [table] T_ADMIN (R)
                │     │  └─ [sql] AdminMapper.shared <SELECT>
                │     │     └─ [table] T_SHARED (R)
                │     └─ FrontController#list() (모듈 front-web)
                │        ├─ [sql] FrontMapper.select <SELECT>
                │        │  └─ [table] T_FRONT (R)
                │        └─ [sql] FrontMapper.shared <SELECT>
                │           └─ [table] T_SHARED (R)
                └─ [request] GET /user/list
                   └─ [endpoint] GET /user/list
                """);
    }

    @Test
    @DisplayName("F-015-AC75 위 방향도 같다: 핸들러(뷰 컨트롤러의 화면)에서 올라간 엔드포인트의 요청은 MATCHES.module이 그 핸들러(그 선언)의 모듈인 것만이다. 영향도도 같은 화면만 낸다")
    void callersAndImpactFollowMatchesModule() {
        TwoModules m = new TwoModules();

        assertThat(callers(m.f.graph(), m.admin)).isEqualTo("""
                AdminController#list()
                └─ [endpoint] GET /user/list
                   └─ [request] GET /user/list
                      └─ [jsp] admin-web/WebContent/list.jsp
                """);
        assertThat(callers(m.f.graph(), m.frontHome)).isEqualTo("""
                [jsp] front-web/WebContent/index.jsp
                └─ [endpoint] GET /
                   └─ [request] GET /
                      └─ [jsp] front-web/WebContent/list.jsp
                """);

        ImpactReport impact = ImpactAnalysis.analyze(m.f.graph(), "T_FRONT");
        assertThat(impact.screens()).extracting(Item::label)
                .contains("front-web/WebContent/list.jsp").doesNotContain("admin-web/WebContent/list.jsp");
        // 두 핸들러가 모두 닿는 테이블: 엔드포인트를 핸들러마다 따로 올라가 두 모듈의 화면을 모두 낸다
        ImpactReport shared = ImpactAnalysis.analyze(m.f.graph(), "T_SHARED");
        assertThat(shared.screens()).extracting(Item::label)
                .contains("front-web/WebContent/list.jsp", "admin-web/WebContent/list.jsp");
    }

    @Test
    @DisplayName("F-015-AC76 같은 메서드·같은 위치에서 다른 방법으로 풀린 구문(resolvedFrom이 그 미해결 노드)이 있으면 미해결 EXECUTES 줄을 내지 않는다")
    void unresolvedExecutesResolvedAnotherWayIsHidden() {
        GraphFixture f = GraphFixture.create();
        String dao = f.method("com.n.LoginDAO", "updateLoginIncorrect");
        String ent = f.statement("LoginUsr", "updateLoginIncorrectENT", "UPDATE");
        String unresolved = "unresolved:mybatis-statement:LoginUsr.updateLoginIncorrect{*}";
        String other = "unresolved:mybatis-statement:LoginUsr.unknown";
        SourceLocation site = SourceLocation.of("src/main/java/com/n/LoginDAO.java", 106, 6, 106, 12);
        for (String id : List.of(unresolved, other)) {
            f.graph().declare(Node.builder(id, NodeKind.UNRESOLVED).name(id.substring(id.lastIndexOf(':') + 1)).location(site)
                    .attr("category", "mybatis-statement").build());
        }
        f.attr(unresolved, "resolvedBy", "prefixMatch");
        f.edge(Edge.builder(dao, unresolved, EdgeKind.EXECUTES).unresolved().location(site).build());
        f.edge(Edge.builder(dao, ent, EdgeKind.EXECUTES).inferred().attr("prefixMatch", true).attr("statementPrefix", "LoginUsr.updateLoginIncorrect")
                .attr("resolvedFrom", unresolved).location(site).build());
        // 풀리지 않은 다른 참조는 그대로 보인다
        f.edge(Edge.builder(dao, other, EdgeKind.EXECUTES).unresolved()
                .location(SourceLocation.of("src/main/java/com/n/LoginDAO.java", 110, 6, 110, 12)).build());

        assertThat(callees(f.graph(), dao)).isEqualTo("""
                LoginDAO#updateLoginIncorrect()
                ├─ ~ [sql] LoginUsr.updateLoginIncorrectENT <UPDATE> (접두 일치)
                └─ ? [unresolved] LoginUsr.unknown
                """);

        // 리포트의 미해결 참조: resolvedBy가 있는 미해결 노드로 가는 참조는 세지 않는다
        String md = SummaryReport.render(f.graph());
        assertThat(md).contains("## 미해결 참조 (1)").contains("LoginUsr.unknown").doesNotContain("updateLoginIncorrect{*}");
    }

    @Test
    @DisplayName("F-015-AC77 인자로 넘긴 구문 ID로 이은 실행(EXECUTES의 viaArgument=true)은 추론 줄에 (인자 구문)을 붙이고, 그 경로로만 닿은 CRUD 칸은 ?다")
    void viaArgumentExecutes() {
        GraphFixture f = GraphFixture.create();
        String reader = f.method("com.n.JobConfig", "closedOrderReader");
        String statement = f.statement("com.n.BatchMapper", "selectClosedOrders", "SELECT");
        f.reads(statement, "ORDERS");
        f.table("ORDERS");
        f.edge(Edge.builder(reader, statement, EdgeKind.EXECUTES).inferred().attr("viaArgument", true)
                .location(SourceLocation.of("src/main/java/com/n/JobConfig.java", 84, 27, 84, 27)).build());

        assertThat(callees(f.graph(), reader)).isEqualTo("""
                JobConfig#closedOrderReader()
                └─ ~ [sql] BatchMapper.selectClosedOrders <SELECT> (인자 구문)
                   └─ [table] ORDERS (R)
                """);
        assertThat(callers(f.graph(), statement)).isEqualTo("""
                [sql] BatchMapper.selectClosedOrders <SELECT>
                └─ ~ JobConfig#closedOrderReader() (인자 구문)
                """);
        assertThat(CrudMatrix.of(f.graph(), Subject.METHOD).cell(reader, "ORDERS")).isEqualTo("R?");
        ImpactReport impact = ImpactAnalysis.analyze(f.graph(), "ORDERS");
        assertThat(impact.executors()).extracting(Item::inference).containsExactly("JobConfig#closedOrderReader() → [sql] BatchMapper.selectClosedOrders <SELECT>: 인자로 넘긴 구문 ID(viaArgument)");
    }

    @Test
    @DisplayName("F-015-AC78 동적 조각이 든 뷰 이름으로 찾은 화면(RENDERS의 dynamicSegment=true)은 추론 줄에 (동적 뷰 이름 후보)를 붙인다")
    void dynamicSegmentViews() {
        GraphFixture f = GraphFixture.create();
        String handler = f.method("com.n.BlogController", "index");
        String basic = f.jsp("src/main/webapp/basic/blog/index.jsp");
        String mobile = f.jsp("src/main/webapp/mobile/blog/index.jsp");
        for (String page : List.of(basic, mobile)) {
            f.edge(Edge.builder(handler, page, EdgeKind.RENDERS).inferred().attr("dynamicSegment", true).attr("suffixMatch", true)
                    .attr("viewName", "/{*}/blog/index.jsp").location(SourceLocation.of("src/main/java/com/n/BlogController.java", 30, 16, 30, 16)).build());
        }

        assertThat(callees(f.graph(), handler)).isEqualTo("""
                BlogController#index()
                ├─ ~ [jsp] src/main/webapp/basic/blog/index.jsp (동적 뷰 이름 후보)
                └─ ~ [jsp] src/main/webapp/mobile/blog/index.jsp (동적 뷰 이름 후보)
                """);
        assertThat(callers(f.graph(), basic)).isEqualTo("""
                [jsp] src/main/webapp/basic/blog/index.jsp
                └─ ~ BlogController#index() (동적 뷰 이름 후보)
                """);
    }

    @Test
    @DisplayName("F-015-AC79 메서드가 여럿인 요청(httpMethod=ANY, httpMethods=[POST, PUT])은 POST|PUT으로, 자기 페이지로 내는 폼(selfSubmit=true)은 {self} 대신 말로 적는다")
    void requestLabels() {
        GraphFixture f = GraphFixture.create();
        String page = f.jsp("src/main/webapp/edit.jsp");
        String save = f.endpoint("POST", "/edit", f.method("com.n.EditController", "save"));
        String multi = "http:request:ANY /api/schedule@src/main/webapp/edit.jsp:12";
        f.graph().declare(Node.builder(multi, NodeKind.HTTP_REQUEST).name("ANY /api/schedule").location(SourceLocation.of("src/main/webapp/edit.jsp", 12))
                .attr("httpMethod", "ANY").attr("httpMethods", List.of("POST", "PUT")).attr("path", "/api/schedule").attr("url", "editUrl").build());
        String self = "http:request:POST {self}@src/main/webapp/edit.jsp:20";
        f.graph().declare(Node.builder(self, NodeKind.HTTP_REQUEST).name("POST {self}").location(SourceLocation.of("src/main/webapp/edit.jsp", 20))
                .attr("httpMethod", "POST").attr("path", "{self}").attr("url", "").attr("selfSubmit", true).attr("api", "form").build());
        String any = f.request("ANY", "/plain", "src/main/webapp/edit.jsp", 30, page, null);
        f.edge(Edge.of(page, multi, EdgeKind.REQUESTS, SourceLocation.of("src/main/webapp/edit.jsp", 12)));
        f.edge(Edge.of(page, self, EdgeKind.REQUESTS, SourceLocation.of("src/main/webapp/edit.jsp", 20)));
        f.edge(Edge.builder(self, save, EdgeKind.MATCHES).inferred().attr("selfSubmit", true).build());

        assertThat(NodeLabels.label(f.graph(), multi)).isEqualTo("POST|PUT /api/schedule");
        assertThat(NodeLabels.label(f.graph(), self)).isEqualTo("POST (자기 페이지로 제출)");
        assertThat(NodeLabels.label(f.graph(), any)).isEqualTo("ANY /plain");
        assertThat(callees(f.graph(), page)).isEqualTo("""
                [jsp] src/main/webapp/edit.jsp
                ├─ [request] POST|PUT /api/schedule
                ├─ [request] POST (자기 페이지로 제출)
                │  └─ ~ [endpoint] POST /edit
                │     └─ EditController#save()
                └─ [request] ANY /plain
                """);
        // 리포트의 화면 요청: 자기 페이지로 내는 폼도 서버 요청이고, 미연결 목록에 {self}를 URL처럼 적지 않는다
        String md = SummaryReport.render(f.graph());
        assertThat(md).contains("`POST\\|PUT /api/schedule`").doesNotContain("{self}");
    }

    @Test
    @DisplayName("F-015-AC80 동적 데코레이터(INCLUDES의 dynamicDecorator=true)는 추론 레이아웃 줄이고, 가리지 못해 후보 여럿(ambiguous=true)이면 [ambiguous n] 아래에 묶는다. 후보가 하나면 묶지 않는다")
    void dynamicDecorators() {
        GraphFixture f = GraphFixture.create();
        String page = f.jsp("src/main/webapp/front/index.jsp");
        String single = f.jsp("src/main/webapp/front/list.jsp");
        String basic = f.jsp("src/main/webapp/themes/basic/layouts/default.jsp");
        String weixin = f.jsp("src/main/webapp/themes/weixin/layouts/default.jsp");
        for (String decorator : List.of(basic, weixin)) {
            f.edge(Edge.builder(page, decorator, EdgeKind.INCLUDES).inferred().attr("via", "sitemesh").attr("dynamicDecorator", true).attr("ambiguous", true)
                    .attr("path", "cms_default_${site.theme}").location(SourceLocation.of("src/main/webapp/front/index.jsp", 7, 2, 7, 62)).build());
        }
        f.edge(Edge.builder(single, basic, EdgeKind.INCLUDES).inferred().attr("via", "sitemesh").attr("dynamicDecorator", true)
                .attr("path", "cms_default_${site.theme}").location(SourceLocation.of("src/main/webapp/front/list.jsp", 7, 2, 7, 62)).build());

        assertThat(callees(f.graph(), page)).isEqualTo("""
                [jsp] src/main/webapp/front/index.jsp
                └─ ~ [ambiguous 2] src/main/webapp/front/index.jsp
                   ├─ ~ [layout] [jsp] src/main/webapp/themes/basic/layouts/default.jsp
                   └─ ~ [layout] [jsp] src/main/webapp/themes/weixin/layouts/default.jsp
                """);
        assertThat(callees(f.graph(), single)).isEqualTo("""
                [jsp] src/main/webapp/front/list.jsp
                └─ ~ [layout] [jsp] src/main/webapp/themes/basic/layouts/default.jsp
                """);
    }

    @Test
    @DisplayName("F-015-AC80 후보 묶음([ambiguous n])은 아래 방향에서만 만든다: 위 방향에서는 엔드포인트로 오는 요청 여럿이 저마다 후보 대응(ambiguous)이어도 한 묶음이 아니다")
    void ambiguousGroupsOnlyDownward() {
        GraphFixture f = GraphFixture.create();
        String page = f.jsp("src/main/webapp/list.jsp");
        String a = f.endpoint("GET", "/a/list", f.method("com.n.AController", "list"));
        String b = f.endpoint("GET", "/b/list", f.method("com.n.BController", "list"));
        for (int line : List.of(7, 9)) {
            String request = f.request("GET", "/list" + line, "src/main/webapp/list.jsp", line, page, null);
            for (String endpoint : List.of(a, b)) {
                f.edge(Edge.builder(request, endpoint, EdgeKind.MATCHES).inferred().attr("ambiguous", true).build());
            }
        }

        assertThat(callers(f.graph(), a)).isEqualTo("""
                [endpoint] GET /a/list
                ├─ ~ [request] GET /list7
                │  └─ [jsp] src/main/webapp/list.jsp
                └─ ~ [request] GET /list9
                   └─ [jsp] src/main/webapp/list.jsp
                """);
    }
}
