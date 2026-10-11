package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ViewLinkerTest extends LinkTestBase {

    private static final String CTRL = "com.a.web.OrderController";

    private String handler(String name, String... viewNames) {
        String id = method(CTRL, name);
        g.putAttrs(id, Map.of("viewNames", List.of(viewNames)));
        return id;
    }

    private void resolverBean(String prefix, String suffix) {
        g.declare(Node.builder(Ids.springBean("viewResolver"), NodeKind.SPRING_BEAN)
                .attr("class", "org.springframework.web.servlet.view.InternalResourceViewResolver")
                .attr("property.prefix", prefix).attr("property.suffix", suffix).build());
    }

    @Test
    @DisplayName("F-012-AC18 뷰 리졸버 빈의 prefix·suffix로 풀어 RESOLVED RENDERS를 만든다")
    void resolverBean() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String page = jsp("src/main/webapp/WEB-INF/views/order/list.jsp");
        jsp("src/main/webapp/WEB-INF/views/member/list.jsp");
        String m = handler("list", "order/list");

        run(new ViewLinker());

        Edge e = only(m, EdgeKind.RENDERS);
        assertThat(e.to()).isEqualTo(page);
        assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(e.attrString("viewName")).isEqualTo("order/list");
        assertThat(e.location()).isEqualTo(g.node(m).orElseThrow().location());
    }

    @Test
    @DisplayName("F-012-AC18 뷰 이름이 여럿이면 각각 잇고, /로 시작하는 이름도 푼다")
    void multipleViewNames() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String a = jsp("WebContent/WEB-INF/views/order/list.jsp");
        String b = jsp("WebContent/WEB-INF/views/order/empty.jsp");
        String m = handler("list", "/order/list", "order/empty");

        run(new ViewLinker());

        assertThat(out(m, EdgeKind.RENDERS)).extracting(Edge::to).containsExactlyInAnyOrder(a, b);
        assertThat(out(m, EdgeKind.RENDERS)).allMatch(e -> e.confidence() == Confidence.RESOLVED);
    }

    @Test
    @DisplayName("F-012-AC18 application.properties의 spring.mvc.view.prefix/suffix로 푼다")
    void propertiesResolver() {
        project.file("src/main/resources/application.properties",
                "server.port=8080\nspring.mvc.view.prefix=/WEB-INF/jsp/\nspring.mvc.view.suffix = .jsp\n");
        rescan();
        String page = jsp("src/main/webapp/WEB-INF/jsp/main.jsp");
        String m = handler("main", "main");

        run(new ViewLinker());

        assertThat(only(m, EdgeKind.RENDERS).to()).isEqualTo(page);
        assertThat(only(m, EdgeKind.RENDERS).confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    @DisplayName("F-012-AC18 application.yml의 spring.mvc.view.prefix/suffix로 푼다")
    void yamlResolver() {
        project.file("src/main/resources/application.yml", """
                server:
                  port: 8080
                spring:
                  datasource:
                    url: jdbc:x   # 주석
                  mvc:
                    view:
                      prefix: "/WEB-INF/jsp/"
                      suffix: .jsp
                """);
        rescan();
        String page = jsp("src/main/webapp/WEB-INF/jsp/main.jsp");
        String m = handler("main", "main");

        run(new ViewLinker());

        assertThat(only(m, EdgeKind.RENDERS).to()).isEqualTo(page);
        assertThat(only(m, EdgeKind.RENDERS).confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    @DisplayName("F-012-AC18 같은 경로의 JSP가 여러 웹 루트에 있으면 핸들러와 가까운 것을, 못 고르면 전부 INFERRED + ambiguous")
    void multipleWebRoots() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String near = jsp("admin/src/main/webapp/WEB-INF/views/x.jsp");
        jsp("front/src/main/webapp/WEB-INF/views/x.jsp");
        String m1 = Ids.javaMethod("com.b.AdminController", "x");
        g.declare(Node.builder(m1, NodeKind.JAVA_METHOD).name("x")
                .location(io.codeatlas.core.model.SourceLocation.of("admin/src/main/java/com/b/AdminController.java", 5))
                .attr("viewNames", List.of("x")).build());
        String m2 = handler("x", "x");   // src/main/java/... : 어느 모듈과도 가깝지 않다

        run(new ViewLinker());

        assertThat(only(m1, EdgeKind.RENDERS).to()).isEqualTo(near);
        assertThat(only(m1, EdgeKind.RENDERS).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(out(m2, EdgeKind.RENDERS)).hasSize(2).allMatch(e -> inferred(e) && e.attrBoolean("ambiguous"));
    }

    @Test
    @DisplayName("F-012-AC19 리졸버 정보가 없으면 /<이름>.jsp로 끝나는 JSP가 하나일 때만 INFERRED")
    void noResolverInfo() {
        String page = jsp("src/main/webapp/WEB-INF/views/order/list.jsp");
        jsp("src/main/webapp/a/dup.jsp");
        jsp("src/main/webapp/b/dup.jsp");
        jsp("src/main/webapp/WEB-INF/views/reorder/list.jsp");
        String m = handler("list", "order/list", "dup", "missing");

        run(new ViewLinker());

        List<Edge> edges = out(m, EdgeKind.RENDERS);
        assertThat(edges).hasSize(3);
        Edge ok = edges.stream().filter(e -> e.to().equals(page)).findFirst().orElseThrow();
        assertThat(ok.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(ok.attrString("viewName")).isEqualTo("order/list");
        assertThat(edges).filteredOn(e -> e.confidence() == Confidence.UNRESOLVED).extracting(Edge::to)
                .containsExactlyInAnyOrder("unresolved:view:dup", "unresolved:view:missing");
        assertThat(diagnostics("UNRESOLVED_REFERENCE")).hasSize(2);
    }

    @Test
    @DisplayName("F-012-AC19 리졸버가 있는데 그 경로의 JSP가 없으면 미해결이다")
    void resolverButNoPage() {
        resolverBean("/WEB-INF/views/", ".jsp");
        jsp("src/main/webapp/other/order/list.jsp");
        String m = handler("list", "order/list");

        run(new ViewLinker());

        Edge e = only(m, EdgeKind.RENDERS);
        assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(e.to()).isEqualTo("unresolved:view:order/list");
        assertThat(g.node(e.to()).orElseThrow().attrString("category")).isEqualTo("view");
    }

    @Test
    @DisplayName("F-012-AC19 동적 뷰 이름({*})은 비슷한 JSP가 있어도 잇지 않고 미해결로 남긴다")
    void dynamicViewName() {
        resolverBean("/WEB-INF/views/", ".jsp");
        jsp("src/main/webapp/WEB-INF/views/order/popup_a.jsp");
        String m = handler("popup", "order/popup_{*}");

        run(new ViewLinker());

        Edge e = only(m, EdgeKind.RENDERS);
        assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(e.to()).isEqualTo("unresolved:view:order/popup_{*}");
    }

    @Test
    @DisplayName("F-012-AC20 redirect:·forward:는 맞는 엔드포인트의 핸들러로 INFERRED CALLS를 만든다")
    void redirectAndForward() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String list = method(CTRL, "list");
        String detail = method(CTRL, "detail", "long");
        g.addEdge(Edge.of(endpoint("GET", "/order/list"), list, EdgeKind.ROUTES_TO));
        g.addEdge(Edge.of(endpoint("POST", "/order/list"), method(CTRL, "search"), EdgeKind.ROUTES_TO));
        g.addEdge(Edge.of(endpoint(null, "/order/{id}"), detail, EdgeKind.ROUTES_TO));
        String save = handler("save", "redirect:/order/list?page=1");
        String view = handler("view", "forward:/order/{*}");

        run(new ViewLinker());

        Edge redirect = only(save, EdgeKind.CALLS);
        assertThat(redirect.to()).isEqualTo(list);
        assertThat(redirect.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(redirect.attrString("callType")).isEqualTo("redirect");
        assertThat(redirect.attrString("path")).isEqualTo("/order/list");
        Edge forward = only(view, EdgeKind.CALLS);
        assertThat(forward.to()).isEqualTo(detail);
        assertThat(forward.attrString("callType")).isEqualTo("forward");
        assertThat(g.edges(EdgeKind.RENDERS)).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC20 맞는 엔드포인트가 없는 redirect는 미해결 CALLS, 외부 URL은 아무것도 만들지 않는다")
    void redirectWithoutEndpoint() {
        endpoint("GET", "/order/list");   // 핸들러(ROUTES_TO) 없음
        String a = handler("a", "redirect:/nowhere");
        String b = handler("b", "redirect:https://pay.example.com/start");
        String c = handler("c", "forward:/WEB-INF/views/err.jsp");
        String page = jsp("src/main/webapp/WEB-INF/views/err.jsp");

        run(new ViewLinker());

        Edge e = only(a, EdgeKind.CALLS);
        assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(e.to()).isEqualTo("unresolved:http-endpoint:GET /nowhere");
        assertThat(g.outgoing(b)).isEmpty();
        assertThat(only(c, EdgeKind.RENDERS).to()).isEqualTo(page);
        assertThat(out(c, EdgeKind.CALLS)).isEmpty();
    }

    // ------------------------------------------------------------------ AC25: 서블릿 API의 redirect·forward (메서드 attrs)

    private String servletMethod(String name, List<String> redirects, List<String> forwards) {
        String id = method("com.a.web.LegacyServlet", name);
        if (!redirects.isEmpty()) {
            g.putAttrs(id, Map.of("redirects", redirects));
        }
        if (!forwards.isEmpty()) {
            g.putAttrs(id, Map.of("forwards", forwards));
        }
        return id;
    }

    @Test
    @DisplayName("F-012-AC25 메서드 attrs.redirects·forwards의 경로와 맞는 엔드포인트의 핸들러로 INFERRED CALLS(callType, path)를 만든다")
    void servletRedirectAndForward() {
        String list = method(CTRL, "list");
        String detail = method(CTRL, "detail", "long");
        g.addEdge(Edge.of(endpoint("GET", "/order/list"), list, EdgeKind.ROUTES_TO));
        g.addEdge(Edge.of(endpoint("POST", "/order/list"), method(CTRL, "search"), EdgeKind.ROUTES_TO));
        g.addEdge(Edge.of(endpoint(null, "/order/{id}"), detail, EdgeKind.ROUTES_TO));
        String save = servletMethod("doPost", List.of("/order/list?saved=1"), List.of());
        String view = servletMethod("doGet", List.of(), List.of("/order/{*}"));

        run(new ViewLinker());

        Edge redirect = only(save, EdgeKind.CALLS);
        assertThat(redirect.to()).isEqualTo(list);
        assertThat(redirect.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(redirect.attrString("callType")).isEqualTo("redirect");
        assertThat(redirect.attrString("path")).isEqualTo("/order/list");
        assertThat(redirect.attrs()).doesNotContainKey("viewName");
        assertThat(redirect.location()).isEqualTo(g.node(save).orElseThrow().location());
        Edge forward = only(view, EdgeKind.CALLS);
        assertThat(forward.to()).isEqualTo(detail);
        assertThat(forward.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(forward.attrString("callType")).isEqualTo("forward");
        assertThat(forward.attrString("path")).isEqualTo("/order/{*}");
        assertThat(g.edges(EdgeKind.RENDERS)).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC25 forward 대상이 JSP 경로면 RENDERS(메서드→JSP), 맞는 엔드포인트가 없으면 미해결 CALLS(http-endpoint), 외부 URL은 만들지 않는다")
    void servletForwardToJspAndUnresolved() {
        String page = jsp("src/main/webapp/WEB-INF/views/order/result.jsp");
        endpoint("GET", "/order/list");   // 핸들러(ROUTES_TO) 없음
        String toJsp = servletMethod("show", List.of(), List.of("/WEB-INF/views/order/result.jsp"));
        String nowhere = servletMethod("lost", List.of("/nowhere.do"), List.of("/missing/{*}"));
        String outside = servletMethod("pay", List.of("https://pay.example.com/start"), List.of());
        String noJsp = servletMethod("broken", List.of(), List.of("/WEB-INF/views/none.jsp"));

        run(new ViewLinker());

        Edge renders = only(toJsp, EdgeKind.RENDERS);
        assertThat(renders.to()).isEqualTo(page);
        assertThat(renders.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(renders.attrString("viewName")).isEqualTo("/WEB-INF/views/order/result.jsp");
        assertThat(out(toJsp, EdgeKind.CALLS)).isEmpty();

        assertThat(out(nowhere, EdgeKind.CALLS)).hasSize(2).allMatch(e -> e.confidence() == Confidence.UNRESOLVED);
        assertThat(out(nowhere, EdgeKind.CALLS)).extracting(Edge::to)
                .containsExactlyInAnyOrder("unresolved:http-endpoint:GET /nowhere.do", "unresolved:http-endpoint:ANY /missing/{*}");
        assertThat(g.node("unresolved:http-endpoint:GET /nowhere.do").orElseThrow().attrString("category")).isEqualTo("http-endpoint");
        assertThat(g.outgoing(outside)).isEmpty();
        // 없는 JSP로의 forward는 forward: 뷰 이름과 같은 방식(분류 view)으로 남는다
        assertThat(only(noJsp, EdgeKind.RENDERS).to()).isEqualTo("unresolved:view:/WEB-INF/views/none.jsp");
    }

    @Test
    @DisplayName("F-012-AC25 viewNames가 없고 redirects만 있는 메서드도 처리하고, 같은 메서드의 redirect: 뷰 이름과 겹쳐도 엣지는 하나다")
    void servletRedirectTogetherWithViewNames() {
        String list = method(CTRL, "list");
        g.addEdge(Edge.of(endpoint("GET", "/order/list"), list, EdgeKind.ROUTES_TO));
        String both = handler("save", "redirect:/order/list");
        g.putAttrs(both, Map.of("redirects", List.of("/order/list")));

        run(new ViewLinker());

        assertThat(only(both, EdgeKind.CALLS).to()).isEqualTo(list);
        assertThat(only(both, EdgeKind.CALLS).attrString("viewName")).isEqualTo("redirect:/order/list");
    }

    @Test
    @DisplayName("F-012-AC37 뷰 리졸버 빈의 prefix·suffix가 ${키}이면 프로젝트 properties 값으로 치환해 푼다")
    void resolverBeanWithPlaceholders() {
        project.file("src/main/resources/jeesite.properties", "web.view.prefix=/WEB-INF/views/\nweb.view.suffix=.jsp\n");
        rescan();
        resolverBean("${web.view.prefix}", "${web.view.suffix}");
        String page = jsp("src/main/webapp/WEB-INF/views/modules/sys/userList.jsp");
        jsp("src/main/webapp/WEB-INF/old/modules/sys/userList.jsp");
        String m = handler("list", "modules/sys/userList");

        run(new ViewLinker());

        Edge e = only(m, EdgeKind.RENDERS);
        assertThat(e.to()).isEqualTo(page);
        assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(e.attrString("resolverPrefix")).isEqualTo("/WEB-INF/views/");
        assertThat(e.attrString("resolverSuffix")).isEqualTo(".jsp");
    }

    @Test
    @DisplayName("F-012-AC37 값을 못 찾은 자리표시자 리졸버는 쓰지 않는다: 리졸버 정보가 없을 때처럼 유일한 JSP로만 INFERRED")
    void resolverBeanWithUnknownPlaceholder() {
        resolverBean("${web.view.prefix}", ".jsp");
        String page = jsp("src/main/webapp/WEB-INF/views/a/list.jsp");
        jsp("src/main/webapp/x/dup.jsp");
        jsp("src/main/webapp/y/dup.jsp");
        String m = handler("list", "a/list", "dup");

        run(new ViewLinker());

        List<Edge> edges = out(m, EdgeKind.RENDERS);
        assertThat(edges).filteredOn(e -> e.to().equals(page)).hasSize(1).allMatch(LinkTestBase::inferred);
        assertThat(edges).filteredOn(e -> e.confidence() == Confidence.UNRESOLVED).extracting(Edge::to)
                .containsExactly("unresolved:view:dup");
    }

    @Test
    @DisplayName("F-012-AC36 redirect 대상 엔드포인트의 핸들러가 여러 모듈에 있으면 같은 모듈의 핸들러로만 잇고, 다른 모듈뿐이면 crossModule=true")
    void redirectRespectsModuleBoundary() {
        project.file("web-a/pom.xml", "<project/>");
        project.file("web-b/pom.xml", "<project/>");
        rescan();
        routedEndpoint("GET", "/manage/index", "web-a/src/main/java/com/a/ManageController.java", "com.a.ManageController", "index");
        routedEndpoint("GET", "/manage/index", "web-b/src/main/java/com/b/ManageController.java", "com.b.ManageController", "index");
        routedEndpoint("GET", "/sso/login", "web-b/src/main/java/com/b/SsoController.java", "com.b.SsoController", "login");
        String save = methodAt("web-a/src/main/java/com/a/ManageController.java", "com.a.ManageController", "save");
        g.putAttrs(save, Map.of("viewNames", List.of("redirect:/manage/index", "redirect:/sso/login")));

        run(new ViewLinker());

        List<Edge> calls = out(save, EdgeKind.CALLS);
        assertThat(calls).extracting(Edge::to).containsExactlyInAnyOrder(
                Ids.javaMethod("com.a.ManageController", "index"), Ids.javaMethod("com.b.SsoController", "login"));
        Edge cross = calls.stream().filter(e -> e.to().contains("SsoController")).findFirst().orElseThrow();
        assertThat(cross.attrBoolean("crossModule")).isTrue();
        Edge same = calls.stream().filter(e -> e.to().contains("com.a.")).findFirst().orElseThrow();
        assertThat(same.attrs()).doesNotContainKey("crossModule");
    }

    @Test
    @DisplayName("F-012-AC33 상대 redirect 대상은 그 핸들러의 엔드포인트 경로를 기준 디렉터리로 푼다")
    void relativeRedirectUsesHandlerEndpointDirectory() {
        String saveEndpoint = routedEndpoint("POST", "/order/save", "src/main/java/com/a/OrderController.java", "com.a.OrderController", "save");
        String save = g.outgoing(saveEndpoint, EdgeKind.ROUTES_TO).get(0).to();
        routedEndpoint("GET", "/order/list", "src/main/java/com/a/OrderController.java", "com.a.OrderController", "list");
        routedEndpoint("GET", "/member/list", "src/main/java/com/a/MemberController.java", "com.a.MemberController", "list");
        g.putAttrs(save, Map.of("viewNames", List.of("redirect:list")));

        run(new ViewLinker());

        Edge call = only(save, EdgeKind.CALLS);
        assertThat(call.to()).isEqualTo(Ids.javaMethod("com.a.OrderController", "list"));
        assertThat(call.attrString("relativeBase")).isEqualTo("/order");
    }

    // ------------------------------------------------------------------ AC43: 엣지 위치는 호출 지점 (viewNameLines·redirectLines·forwardLines)

    private static SourceLocation at(String methodId, GraphModelView view, int line, int column) {
        return SourceLocation.of(view.fileOf(methodId), line, column, line, column);
    }

    /** 테스트에서 메서드 노드의 파일을 얻는 얇은 도우미. */
    private interface GraphModelView {
        String fileOf(String methodId);
    }

    private GraphModelView view() {
        return id -> g.node(id).orElseThrow().location().file();
    }

    @Test
    @DisplayName("F-012-AC43 viewNameLines가 있으면 RENDERS·redirect·forward CALLS·미해결 엣지의 위치가 메서드 선언이 아니라 그 줄:열이다")
    void edgeLocationFromViewNameLines() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String page = jsp("src/main/webapp/WEB-INF/views/order/list.jsp");
        String errorPage = jsp("src/main/webapp/WEB-INF/views/error.jsp");
        String list = method(CTRL, "list");
        g.addEdge(Edge.of(endpoint("GET", "/order/list"), list, EdgeKind.ROUTES_TO));
        String detail = method(CTRL, "detail", "long");
        g.addEdge(Edge.of(endpoint(null, "/order/{id}"), detail, EdgeKind.ROUTES_TO));
        String m = handler("save", "order/list", "redirect:/order/list", "forward:/order/7", "forward:/WEB-INF/views/error.jsp", "missing/view");
        g.putAttrs(m, Map.of("viewNameLines", List.of("160:9", "148:13", "151:13", "153:13", "157")));

        run(new ViewLinker());

        assertThat(g.edges(m, EdgeKind.RENDERS, page).get(0).location()).isEqualTo(at(m, view(), 160, 9));
        assertThat(g.edges(m, EdgeKind.CALLS, list).get(0).location()).isEqualTo(at(m, view(), 148, 13));
        assertThat(g.edges(m, EdgeKind.CALLS, detail).get(0).location()).isEqualTo(at(m, view(), 151, 13));
        assertThat(g.edges(m, EdgeKind.RENDERS, errorPage).get(0).location()).isEqualTo(at(m, view(), 153, 13));
        // 열이 없으면 줄만, 미해결 엣지와 그 진단도 같은 위치다
        Edge missing = g.edges(m, EdgeKind.RENDERS, "unresolved:view:missing/view").get(0);
        assertThat(missing.location()).isEqualTo(SourceLocation.of(view().fileOf(m), 157));
        assertThat(diagnostics("UNRESOLVED_REFERENCE")).extracting(d -> d.location()).containsExactly(missing.location());
    }

    @Test
    @DisplayName("F-012-AC43 redirectLines·forwardLines가 있으면 서블릿 API redirect·forward의 CALLS·RENDERS 위치가 그 호출 지점이다")
    void edgeLocationFromRedirectAndForwardLines() {
        String page = jsp("src/main/webapp/WEB-INF/views/order/result.jsp");
        String list = method(CTRL, "list");
        g.addEdge(Edge.of(endpoint("GET", "/order/list"), list, EdgeKind.ROUTES_TO));
        String detail = method(CTRL, "detail", "long");
        g.addEdge(Edge.of(endpoint(null, "/order/{id}"), detail, EdgeKind.ROUTES_TO));
        String m = servletMethod("doPost", List.of("/order/list", "/nowhere.do"), List.of("/order/{*}", "/WEB-INF/views/order/result.jsp"));
        g.putAttrs(m, Map.of("redirectLines", List.of("31:13", "40:9"), "forwardLines", List.of("35:13", "37:13")));

        run(new ViewLinker());

        assertThat(g.edges(m, EdgeKind.CALLS, list).get(0).location()).isEqualTo(at(m, view(), 31, 13));
        assertThat(g.edges(m, EdgeKind.CALLS, "unresolved:http-endpoint:GET /nowhere.do").get(0).location()).isEqualTo(at(m, view(), 40, 9));
        assertThat(g.edges(m, EdgeKind.CALLS, detail).get(0).location()).isEqualTo(at(m, view(), 35, 13));
        assertThat(only(m, EdgeKind.RENDERS).to()).isEqualTo(page);
        assertThat(only(m, EdgeKind.RENDERS).location()).isEqualTo(at(m, view(), 37, 13));
    }

    @Test
    @DisplayName("F-012-AC43 병렬 목록이 없거나(옛 그래프) 길이가 다르거나 값을 읽을 수 없으면 전처럼 메서드 위치다")
    void edgeLocationFallsBackToMethod() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String a = jsp("src/main/webapp/WEB-INF/views/a.jsp");
        String b = jsp("src/main/webapp/WEB-INF/views/b.jsp");
        String old = handler("old", "a");
        String shorter = handler("shorter", "a", "b");
        g.putAttrs(shorter, Map.of("viewNameLines", List.of("12:5")));
        String broken = handler("broken", "a", "b");
        g.putAttrs(broken, Map.of("viewNameLines", List.of("x:y", "22:5")));

        run(new ViewLinker());

        assertThat(only(old, EdgeKind.RENDERS).location()).isEqualTo(g.node(old).orElseThrow().location());
        assertThat(out(shorter, EdgeKind.RENDERS)).allMatch(e -> e.location().equals(g.node(shorter).orElseThrow().location()));
        assertThat(g.edges(broken, EdgeKind.RENDERS, a).get(0).location()).isEqualTo(g.node(broken).orElseThrow().location());
        assertThat(g.edges(broken, EdgeKind.RENDERS, b).get(0).location()).isEqualTo(at(broken, view(), 22, 5));
    }

    // ------------------------------------------------------------------ AC45, AC46: Tiles 정의

    private void tilesFile(String path, String... definitions) {
        tilesFile(path, List.of(definitions), List.of());
    }

    private void tilesFile(String path, List<String> definitions, List<String> attributes) {
        Node.Builder b = Node.builder(Ids.file(path), NodeKind.FILE).name(path.substring(path.lastIndexOf('/') + 1)).qualifiedName(path)
                .location(SourceLocation.ofFile(path)).attr("fileType", "XML").attr("tilesDefinitions", definitions);
        if (!attributes.isEmpty()) {
            b.attr("tilesAttributes", attributes);
        }
        g.declare(b.build());
    }

    @Test
    @DisplayName("F-012-AC45 뷰 이름이 Tiles definition 이름과 같으면 그 template JSP로 RESOLVED RENDERS(via=tiles, definition)를 만들고, 리졸버 규칙보다 먼저다")
    void tilesDefinitionByName() {
        resolverBean("/WEB-INF/views/", ".jsp");
        tilesFile("src/main/webapp/WEB-INF/tiles/tiles.xml",
                "order.list=/WEB-INF/layouts/main.jsp", "member.list=/WEB-INF/layouts/popup.jsp");
        String layout = jsp("src/main/webapp/WEB-INF/layouts/main.jsp");
        jsp("src/main/webapp/WEB-INF/layouts/popup.jsp");
        jsp("src/main/webapp/WEB-INF/views/order.list.jsp");   // 리졸버 규칙으로도 맞는 JSP: Tiles가 먼저다
        String plain = jsp("src/main/webapp/WEB-INF/views/order/detail.jsp");
        String m = handler("list", "order.list", "order/detail");
        g.putAttrs(m, Map.of("viewNameLines", List.of("20:9", "24:9")));

        run(new ViewLinker());

        Edge tiles = only(m, EdgeKind.RENDERS, layout);
        assertThat(tiles.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(tiles.attrString("via")).isEqualTo("tiles");
        assertThat(tiles.attrString("definition")).isEqualTo("order.list");
        assertThat(tiles.attrString("viewName")).isEqualTo("order.list");
        assertThat(tiles.attrs()).doesNotContainKeys("resolverPrefix", "resolverSuffix");
        assertThat(tiles.location()).isEqualTo(at(m, view(), 20, 9));
        // Tiles 정의에 없는 뷰 이름은 기존 리졸버 규칙대로다
        Edge byResolver = only(m, EdgeKind.RENDERS, plain);
        assertThat(byResolver.attrs()).doesNotContainKey("via");
        assertThat(byResolver.attrString("resolverPrefix")).isEqualTo("/WEB-INF/views/");
        assertThat(out(m, EdgeKind.RENDERS)).hasSize(2);
    }

    private Edge only(String from, EdgeKind kind, String to) {
        List<Edge> edges = g.edges(from, kind, to);
        assertThat(edges).as(from + " -" + kind + "-> " + to).hasSize(1);
        return edges.get(0);
    }

    @Test
    @DisplayName("F-012-AC46 와일드카드 definition은 *를 세그먼트 하나로 맞추고 {n}을 치환한다. 정확한 이름이 와일드카드보다 먼저고, template JSP가 없으면 미해결이다")
    void tilesWildcardDefinition() {
        tilesFile("src/main/webapp/WEB-INF/tiles.xml",
                "*/*=/WEB-INF/layouts/{1}Layout.jsp", "admin/home=/WEB-INF/layouts/home.jsp", "*.popup=/WEB-INF/layouts/popup.jsp",
                "nolayout/*=/WEB-INF/layouts/none.jsp");
        String adminLayout = jsp("src/main/webapp/WEB-INF/layouts/adminLayout.jsp");
        String home = jsp("src/main/webapp/WEB-INF/layouts/home.jsp");
        String popup = jsp("src/main/webapp/WEB-INF/layouts/popup.jsp");
        String m = handler("pages", "admin/users", "admin/home", "zip.popup", "admin/users/detail", "nolayout/x", "admin/{*}");

        run(new ViewLinker());

        Edge wildcard = only(m, EdgeKind.RENDERS, adminLayout);
        assertThat(wildcard.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(wildcard.attrString("via")).isEqualTo("tiles");
        assertThat(wildcard.attrString("definition")).isEqualTo("*/*");
        assertThat(wildcard.attrString("viewName")).isEqualTo("admin/users");
        assertThat(only(m, EdgeKind.RENDERS, home).attrString("definition")).isEqualTo("admin/home");
        assertThat(only(m, EdgeKind.RENDERS, popup).attrString("definition")).isEqualTo("*.popup");
        // *는 /를 넘지 않는다: admin/users/detail은 */*에 맞지 않고, 리졸버 정보도 없어 미해결이다
        // template JSP가 그래프에 없는 definition, 동적 뷰 이름({*})도 미해결이다
        assertThat(out(m, EdgeKind.RENDERS)).filteredOn(e -> e.confidence() == Confidence.UNRESOLVED).extracting(Edge::to)
                .containsExactlyInAnyOrder("unresolved:view:admin/users/detail", "unresolved:view:nolayout/x", "unresolved:view:admin/{*}");
        assertThat(out(m, EdgeKind.RENDERS)).hasSize(6);
    }

    @Test
    @DisplayName("F-012-AC45 tilesDefinitions가 없는 그래프에서는 기존 동작 그대로다(같은 이름의 JSP를 리졸버로 잇는다)")
    void withoutTilesDefinitions() {
        resolverBean("/WEB-INF/views/", ".jsp");
        g.declare(Node.builder(Ids.file("src/main/webapp/WEB-INF/tiles.xml"), NodeKind.FILE).name("tiles.xml").attr("fileType", "XML").build());
        String page = jsp("src/main/webapp/WEB-INF/views/order.list.jsp");
        String m = handler("list", "order.list");

        run(new ViewLinker());

        Edge e = only(m, EdgeKind.RENDERS);
        assertThat(e.to()).isEqualTo(page);
        assertThat(e.attrs()).doesNotContainKey("via");
    }

    @Test
    @DisplayName("F-012-AC47 tilesAttributes의 JSP(본문 등)로도 RENDERS(via=tiles, definition, tilesAttribute=true)를 만든다. {n}을 치환하고, 없는 JSP는 미해결 없이 건너뛴다")
    void tilesAttributePages() {
        tilesFile("src/main/webapp/WEB-INF/tiles.xml",
                List.of("*/*=/WEB-INF/layouts/default.jsp", "main=/WEB-INF/layouts/default.jsp", "empty="),
                List.of("*/*=/WEB-INF/views/{1}/{2}.jsp", "*/*=/WEB-INF/layouts/header.jsp", "*/*=/WEB-INF/layouts/missing-footer.jsp",
                        "main=/WEB-INF/views/main.jsp", "other=/WEB-INF/views/other.jsp"));
        String layout = jsp("src/main/webapp/WEB-INF/layouts/default.jsp");
        String header = jsp("src/main/webapp/WEB-INF/layouts/header.jsp");
        String body = jsp("src/main/webapp/WEB-INF/views/order/list.jsp");
        jsp("src/main/webapp/WEB-INF/views/order/detail.jsp");
        jsp("src/main/webapp/WEB-INF/views/other.jsp");
        String mainBody = jsp("src/main/webapp/WEB-INF/views/main.jsp");
        String m = handler("list", "order/list");
        g.putAttrs(m, Map.of("viewNameLines", List.of("30:9")));
        String main = handler("main", "main");
        String empty = handler("empty", "empty");

        run(new ViewLinker());

        assertThat(out(m, EdgeKind.RENDERS)).extracting(Edge::to).containsExactlyInAnyOrder(layout, header, body);
        assertThat(out(m, EdgeKind.RENDERS)).allMatch(e -> e.confidence() == Confidence.RESOLVED && "tiles".equals(e.attrString("via"))
                && "*/*".equals(e.attrString("definition")) && "order/list".equals(e.attrString("viewName"))
                && e.location().equals(at(m, view(), 30, 9)));
        assertThat(only(m, EdgeKind.RENDERS, layout).attrs()).doesNotContainKey("tilesAttribute");
        assertThat(only(m, EdgeKind.RENDERS, body).attrBoolean("tilesAttribute")).isTrue();
        assertThat(only(m, EdgeKind.RENDERS, header).attrBoolean("tilesAttribute")).isTrue();
        assertThat(out(main, EdgeKind.RENDERS)).extracting(Edge::to).containsExactlyInAnyOrder(layout, mainBody);
        // template이 없는(빈 값) 정의는 건너뛴다: 리졸버 정보도 없으므로 뷰 미해결이다
        assertThat(only(empty, EdgeKind.RENDERS).to()).isEqualTo("unresolved:view:empty");
        // 없는 조각 JSP(missing-footer)는 미해결로 남기지 않는다
        assertThat(diagnostics("UNRESOLVED_REFERENCE")).hasSize(1);
    }

    @Test
    @DisplayName("F-012-AC46 definition 이름의 **는 /를 포함해 0개 이상의 글자에 맞고 {n}은 n번째 와일드카드다")
    void tilesDoubleStarWildcard() {
        tilesFile("src/main/webapp/WEB-INF/tiles.xml", List.of("admin/**=/WEB-INF/layouts/admin.jsp", "*.do=/WEB-INF/layouts/{1}.jsp"),
                List.of("admin/**=/WEB-INF/views/admin/{1}.jsp"));
        String layout = jsp("src/main/webapp/WEB-INF/layouts/admin.jsp");
        String deep = jsp("src/main/webapp/WEB-INF/views/admin/user/role/list.jsp");
        String m = handler("deep", "admin/user/role/list");

        run(new ViewLinker());

        assertThat(out(m, EdgeKind.RENDERS)).extracting(Edge::to).containsExactlyInAnyOrder(layout, deep);
        assertThat(out(m, EdgeKind.RENDERS)).allMatch(e -> "admin/**".equals(e.attrString("definition")));
    }

    // ------------------------------------------------------------------ AC48~AC51: 후보 뷰 이름, 자리표시자 redirect, 엔드포인트의 뷰, 컨텍스트 루트 기준 forward

    @Test
    @DisplayName("F-012-AC48 메서드 attrs.viewNameCandidates에 든 뷰 이름(지역 변수 값의 후보)의 RENDERS는 JSP를 찾아도 INFERRED + candidate=true다")
    void candidateViewNamesAreInferred() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String form = jsp("src/main/webapp/WEB-INF/views/modules/oa/leaveForm.jsp");
        String audit = jsp("src/main/webapp/WEB-INF/views/modules/oa/leaveAudit.jsp");
        String list = jsp("src/main/webapp/WEB-INF/views/modules/oa/leaveList.jsp");
        String m = handler("form", "modules/oa/leaveForm", "modules/oa/leaveAudit", "modules/oa/leaveList");
        g.putAttrs(m, Map.of("viewNameCandidates", List.of("modules/oa/leaveForm", "modules/oa/leaveAudit")));

        run(new ViewLinker());

        assertThat(only(m, EdgeKind.RENDERS, form).confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(only(m, EdgeKind.RENDERS, form).attrBoolean("candidate")).isTrue();
        assertThat(only(m, EdgeKind.RENDERS, audit).confidence()).isEqualTo(Confidence.INFERRED);
        Edge plain = only(m, EdgeKind.RENDERS, list);
        assertThat(plain.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(plain.attrs()).doesNotContainKey("candidate");
    }

    @Test
    @DisplayName("F-012-AC49 redirect 대상의 프로퍼티 자리표시자(redirect:${adminPath}/login)는 프로젝트 properties 값으로 치환해 맞추고, 값을 못 찾으면 {*}로 읽는다")
    void redirectTargetWithPropertyPlaceholder() {
        project.file("src/main/resources/jeesite.properties", "adminPath=/a\n");
        rescan();
        String login = method(CTRL, "login");
        g.addEdge(Edge.of(endpoint("GET", "/a/login"), login, EdgeKind.ROUTES_TO));
        String index = method(CTRL, "index");
        g.addEdge(Edge.of(endpoint("GET", "/a"), index, EdgeKind.ROUTES_TO));
        String a = handler("a", "redirect:${adminPath}/login?x=1");
        String b = handler("b", "redirect:${adminPath}");
        String c = servletMethod("c", List.of("${adminPath}/login"), List.of());
        String plainLogin = method(CTRL, "plainLogin");
        g.addEdge(Edge.of(endpoint("GET", "/login"), plainLogin, EdgeKind.ROUTES_TO));
        String d = handler("d", "redirect:${unknownPath}/login");

        run(new ViewLinker());

        Edge fromA = only(a, EdgeKind.CALLS);
        assertThat(fromA.to()).isEqualTo(login);
        assertThat(fromA.attrString("path")).isEqualTo("/a/login");
        assertThat(fromA.attrString("viewName")).isEqualTo("redirect:${adminPath}/login?x=1");
        assertThat(only(b, EdgeKind.CALLS).to()).isEqualTo(index);
        assertThat(only(c, EdgeKind.CALLS).to()).isEqualTo(login);
        // 값을 모르는 자리표시자는 알 수 없는 조각({*})이다: 맨 앞이면 알 수 없는 접두로 보고 나머지 경로 전체가 맞는 엔드포인트로 추론한다
        Edge fromD = only(d, EdgeKind.CALLS);
        assertThat(fromD.to()).isEqualTo(plainLogin);
        assertThat(inferred(fromD)).isTrue();
        assertThat(fromD.attrString("strippedPrefix")).isEqualTo("{*}");
    }

    @Test
    @DisplayName("F-012-AC50 viewNames가 있는 HTTP_ENDPOINT(mvc:view-controller)에서도 RENDERS를 만든다: .jsp로 끝나는 경로는 컨텍스트 루트 기준 파일, redirect:는 기존 규칙")
    void viewControllerEndpoint() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String index = jsp("src/main/webapp/index.jsp");
        jsp("src/main/webapp/WEB-INF/views/index.jsp");
        String about = jsp("src/main/webapp/WEB-INF/views/about.jsp");
        String list = method(CTRL, "list");
        g.addEdge(Edge.of(endpoint("GET", "/order/list"), list, EdgeKind.ROUTES_TO));
        String root = viewController("/", "/index.jsp");
        String aboutEndpoint = viewController("/about", "about");
        String moved = viewController("/old", "redirect:/order/list");

        run(new ViewLinker());

        Edge toIndex = only(root, EdgeKind.RENDERS);
        assertThat(toIndex.to()).isEqualTo(index);
        assertThat(toIndex.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(toIndex.attrString("viewName")).isEqualTo("/index.jsp");
        assertThat(toIndex.location()).isEqualTo(g.node(root).orElseThrow().location());
        assertThat(only(aboutEndpoint, EdgeKind.RENDERS).to()).isEqualTo(about);
        Edge redirect = only(moved, EdgeKind.CALLS);
        assertThat(redirect.to()).isEqualTo(list);
        assertThat(redirect.attrString("callType")).isEqualTo("redirect");
        assertThat(inferred(redirect)).isTrue();
    }

    @Test
    @DisplayName("F-012-AC50 여러 모듈이 같은 경로의 view-controller를 선언하면(viewDeclarations) 선언마다 그 설정 파일과 가까운 JSP로 RENDERS를 만들고 위치는 그 선언이다. 뷰 이름은 리졸버를 먼저 거친다")
    void viewControllerDeclarationsPerModule() {
        resolverBean("/WEB-INF/jsp", "");
        String a = jsp("web-a/src/main/webapp/WEB-INF/jsp/index.jsp");
        String b = jsp("web-b/src/main/webapp/WEB-INF/jsp/index.jsp");
        String root = Ids.httpEndpoint("GET", "/");
        g.declare(Node.builder(root, NodeKind.HTTP_ENDPOINT).name("GET /")
                .location(SourceLocation.of("web-a/src/main/java/com/a/IndexController.java", 20))
                .attr("httpMethod", "GET").attr("path", "/").attr("viewController", true).attr("viewNames", List.of("/index.jsp"))
                .attr("viewDeclarations", List.of("web-a/src/main/resources/springMVC-servlet.xml:12:2 /index.jsp",
                        "web-b/src/main/resources/springMVC-servlet.xml:11:5 /index.jsp")).build());

        run(new ViewLinker());

        Edge toA = only(root, EdgeKind.RENDERS, a);
        assertThat(toA.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(toA.location()).isEqualTo(SourceLocation.of("web-a/src/main/resources/springMVC-servlet.xml", 12, 2, 12, 2));
        assertThat(toA.attrString("resolverPrefix")).isEqualTo("/WEB-INF/jsp");
        Edge toB = only(root, EdgeKind.RENDERS, b);
        assertThat(toB.location()).isEqualTo(SourceLocation.of("web-b/src/main/resources/springMVC-servlet.xml", 11, 5, 11, 5));
        assertThat(out(root, EdgeKind.RENDERS)).hasSize(2);
    }

    @Test
    @DisplayName("F-012-AC50 view-controller 선언의 모듈에 그 JSP가 없으면 다른 모듈의 같은 경로 JSP로 잇지 않고 뷰 미해결로 남긴다")
    void viewControllerDeclarationDoesNotCrossModules() {
        project.file("web-a/pom.xml", "<project/>");
        project.file("web-b/pom.xml", "<project/>");
        project.file("web-c/pom.xml", "<project/>");
        rescan();
        resolverBean("/WEB-INF/jsp", "");
        String a = jsp("web-a/src/main/webapp/WEB-INF/jsp/index.jsp");
        jsp("web-b/src/main/webapp/WEB-INF/jsp/index.jsp");
        String root = Ids.httpEndpoint("GET", "/");
        g.declare(Node.builder(root, NodeKind.HTTP_ENDPOINT).name("GET /")
                .attr("httpMethod", "GET").attr("path", "/").attr("viewController", true).attr("viewNames", List.of("/index.jsp"))
                .attr("viewDeclarations", List.of("web-a/src/main/resources/springMVC-servlet.xml:12:2 /index.jsp",
                        "web-c/src/main/resources/springMVC-servlet.xml:12:2 /index.jsp")).build());

        run(new ViewLinker());

        assertThat(out(root, EdgeKind.RENDERS)).extracting(Edge::to).containsExactlyInAnyOrder(a, "unresolved:view:/index.jsp");
        Edge unresolved = only(root, EdgeKind.RENDERS, "unresolved:view:/index.jsp");
        assertThat(unresolved.location().file()).isEqualTo("web-c/src/main/resources/springMVC-servlet.xml");
    }

    @Test
    @DisplayName("F-012-AC62 동적 조각이 디렉터리 하나인 뷰 이름(themes/{*}/x)은 그 자리에 들어갈 수 있는 실제 디렉터리의 JSP가 1~5개면 각각 RENDERS(INFERRED, dynamicSegment=true, 둘 이상이면 candidate=true)다")
    void dynamicDirectoryCandidates() {
        resolverBean("/WEB-INF/views/", ".jsp");
        String basic = jsp("src/main/webapp/WEB-INF/views/modules/cms/front/themes/basic/frontIndex.jsp");
        String weixin = jsp("src/main/webapp/WEB-INF/views/modules/cms/front/themes/weixin/frontIndex.jsp");
        String onlyBasic = jsp("src/main/webapp/WEB-INF/views/modules/cms/front/themes/basic/frontMap.jsp");
        // 디렉터리 하나가 아니라 둘을 건너뛴 곳, 다른 이름의 파일은 후보가 아니다
        jsp("src/main/webapp/WEB-INF/views/modules/cms/front/themes/basic/sub/frontIndex.jsp");
        jsp("src/main/webapp/WEB-INF/views/modules/cms/front/themes/basic/frontIndexOld.jsp");
        String m = handler("front", "modules/cms/front/themes/{*}/frontIndex", "modules/cms/front/themes/{*}/frontMap");

        run(new ViewLinker());

        List<Edge> index = out(m, EdgeKind.RENDERS).stream().filter(e -> e.attrString("viewName").endsWith("frontIndex")).toList();
        assertThat(index).extracting(Edge::to).containsExactlyInAnyOrder(basic, weixin);
        assertThat(index).allMatch(e -> inferred(e) && e.attrBoolean("candidate") && e.attrBoolean("dynamicSegment")
                && "/WEB-INF/views/".equals(e.attrString("resolverPrefix")));
        Edge map = only(m, EdgeKind.RENDERS, onlyBasic);
        assertThat(inferred(map)).isTrue();
        assertThat(map.attrBoolean("dynamicSegment")).isTrue();
        assertThat(map.attrs()).doesNotContainKey("candidate");
        assertThat(g.nodes(NodeKind.UNRESOLVED)).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC62 동적 조각이 디렉터리 하나가 아니거나(마지막 조각, 조각의 일부, 둘 이상) 후보가 없거나 5개를 넘으면 전처럼 뷰 미해결이다. 파일 경로 꼴의 이름(/{*}/qa/index.html)은 리졸버로 못 찾으면 경로 접미사로 찾는다(suffixMatch)")
    void dynamicDirectoryLimits() {
        resolverBean("/WEB-INF/views/", ".jsp");
        for (String theme : List.of("a", "b", "c", "d", "e", "f")) {
            jsp("src/main/webapp/WEB-INF/views/skin/" + theme + "/many.jsp");
        }
        jsp("src/main/webapp/WEB-INF/views/themes/basic/frontIndex.jsp");
        jsp("src/main/webapp/WEB-INF/views/themes/basic/x/frontIndex.jsp");
        String html = jsp("web-ui/cms-web/qa/index.html");
        String m = handler("limits", "skin/{*}/many", "themes/{*}", "themes/basic/front{*}", "themes/{*}/{*}", "nothing/{*}/here",
                "/{*}/qa/index.html");

        run(new ViewLinker());

        assertThat(out(m, EdgeKind.RENDERS).stream().filter(e -> e.confidence() == Confidence.UNRESOLVED)).extracting(Edge::to)
                .containsExactlyInAnyOrder("unresolved:view:skin/{*}/many", "unresolved:view:themes/{*}", "unresolved:view:themes/basic/front{*}",
                        "unresolved:view:themes/{*}/{*}", "unresolved:view:nothing/{*}/here");
        Edge bySuffix = only(m, EdgeKind.RENDERS, html);
        assertThat(inferred(bySuffix)).isTrue();
        assertThat(bySuffix.attrBoolean("suffixMatch")).isTrue();
        assertThat(bySuffix.attrBoolean("dynamicSegment")).isTrue();
    }

    // ---- 동적 디렉터리 뷰의 후보 좁히기 (R47) ----

    private String handlerAt(String file, String type, String name, String... viewNames) {
        String id = methodAt(file, type, name);
        g.putAttrs(id, Map.of("viewNames", List.of(viewNames)));
        return id;
    }

    @Test
    @DisplayName("F-012-AC65 동적 디렉터리 자리가 프로퍼티 자리표시자(/${app.name}/login.html)이고 컨트롤러 모듈의 프로퍼티 값이 하나로 모이면 그 디렉터리의 후보만 잇는다(INFERRED, dynamicSegment, narrowedBy=property, candidate 없음). 모듈 디렉터리 이름보다 먼저다")
    void dynamicDirectoryNarrowedByProperty() {
        project.file("pom.xml", "<project><packaging>pom</packaging></project>");
        project.file("ucenter/pom.xml", "<project><packaging>war</packaging></project>");
        // Maven 필터링 자리(app.name=${app.name})는 값이 아니다. 프로파일 파일의 값이 하나로 모인다
        project.file("ucenter/src/main/resources/config.properties", "app.name=${app.name}\n");
        project.file("ucenter/src/main/resources/profiles/dev.properties", "app.name=member-web\n");
        project.file("ucenter/src/main/resources/profiles/prod.properties", "app.name=member-web\n");
        project.file("other/pom.xml", "<project><packaging>war</packaging></project>");
        project.file("other/src/main/resources/profiles/dev.properties", "app.name=other-web\n");
        rescan();
        String right = jsp("ui/member-web/login.html");
        jsp("admin/src/login.html");
        jsp("ui/ucenter/login.html");
        jsp("ui/other-web/login.html");
        String m = handlerAt("ucenter/src/main/java/com/u/SignController.java", "com.u.SignController", "signin", "/${app.name}/login.html");

        run(new ViewLinker());

        Edge e = only(m, EdgeKind.RENDERS);
        assertThat(e.to()).isEqualTo(right);
        assertThat(inferred(e)).isTrue();
        assertThat(e.attrBoolean("dynamicSegment")).isTrue();
        assertThat(e.attrString("narrowedBy")).isEqualTo("property");
        assertThat(e.attrBoolean("suffixMatch")).isTrue();
        assertThat(e.attrs()).doesNotContainKey("candidate");
        assertThat(e.attrString("viewName")).isEqualTo("/${app.name}/login.html");
        assertThat(g.nodes(NodeKind.UNRESOLVED)).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC65 프로퍼티 값이 가리키는 디렉터리에 그 뷰가 없으면 다른 디렉터리의 후보로 잇지 않고 뷰 미해결이다. 값을 모르는 자리표시자는 {*}처럼 다룬다(모듈 이름, 그다음 후보 전부)")
    void dynamicDirectoryPropertyWithoutPageOrValue() {
        project.file("pom.xml", "<project><packaging>pom</packaging></project>");
        project.file("ucenter/pom.xml", "<project><packaging>war</packaging></project>");
        project.file("ucenter/src/main/resources/config.properties", "app.name=member-web\nmode=dev\n");
        project.file("ucenter/src/main/resources/other.properties", "mode=prod\n");
        rescan();
        jsp("admin/src/login.html");
        jsp("ui/elsewhere/login.html");
        String adminReg = jsp("admin/src/reg.html");
        String uiReg = jsp("ui/elsewhere/reg.html");
        String byModule = jsp("ui/ucenter/password.html");
        jsp("admin/src/password.html");
        String m = handlerAt("ucenter/src/main/java/com/u/SignController.java", "com.u.SignController", "signin",
                "/${app.name}/login.html", "/${nope}/reg.html", "/${mode}/password.html");

        run(new ViewLinker());

        assertThat(g.edges(m, EdgeKind.RENDERS, "unresolved:view:/${app.name}/login.html")).hasSize(1);
        List<Edge> reg = out(m, EdgeKind.RENDERS).stream().filter(e -> "/${nope}/reg.html".equals(e.attrString("viewName"))).toList();
        assertThat(reg).extracting(Edge::to).containsExactlyInAnyOrder(adminReg, uiReg);
        assertThat(reg).allMatch(e -> inferred(e) && e.attrBoolean("candidate") && e.attrBoolean("dynamicSegment") && !e.attrs().containsKey("narrowedBy"));
        Edge password = only(m, EdgeKind.RENDERS, byModule);
        assertThat(password.attrString("narrowedBy")).as("값이 갈리는 프로퍼티는 모른다: 모듈 디렉터리 이름으로 좁힌다").isEqualTo("moduleName");
        assertThat(out(m, EdgeKind.RENDERS)).hasSize(4);
    }

    @Test
    @DisplayName("F-012-AC65 프로퍼티를 모르면 동적 자리의 디렉터리 이름이 컨트롤러 모듈의 디렉터리 이름과 같은 후보만 남긴다(narrowedBy=moduleName). 후보가 5개를 넘어도 그렇게 하나로 정해지면 잇는다")
    void dynamicDirectoryNarrowedByModuleName() {
        project.file("pom.xml", "<project><packaging>pom</packaging></project>");
        project.file("ucenter/ucenter-web/pom.xml", "<project><packaging>war</packaging></project>");
        rescan();
        String login = jsp("ui/ucenter-web/login.html");
        jsp("admin/src/login.html");
        String index = jsp("ui/ucenter-web/index.html");
        for (String dir : List.of("a", "b", "c", "d", "e", "f")) {
            jsp("ui/" + dir + "/index.html");
        }
        String m = handlerAt("ucenter/ucenter-web/src/main/java/com/u/SignController.java", "com.u.SignController", "signin",
                "/{*}/login.html", "/{*}/index.html");

        run(new ViewLinker());

        Edge e = only(m, EdgeKind.RENDERS, login);
        assertThat(inferred(e)).isTrue();
        assertThat(e.attrBoolean("dynamicSegment")).isTrue();
        assertThat(e.attrString("narrowedBy")).isEqualTo("moduleName");
        assertThat(e.attrs()).doesNotContainKey("candidate");
        Edge many = only(m, EdgeKind.RENDERS, index);
        assertThat(many.attrString("narrowedBy")).isEqualTo("moduleName");
        assertThat(out(m, EdgeKind.RENDERS)).hasSize(2);
        assertThat(g.nodes(NodeKind.UNRESOLVED)).isEmpty();
    }

    @Test
    @DisplayName("F-012-AC65 프로퍼티도 모듈 이름도 근거가 못 되면 전처럼 후보 1~5개를 candidate로 남기고(narrowedBy 없음), 5개를 넘으면 미해결이다")
    void dynamicDirectoryWithoutEvidenceKeepsCandidates() {
        project.file("pom.xml", "<project><packaging>pom</packaging></project>");
        project.file("site/pom.xml", "<project><packaging>war</packaging></project>");
        rescan();
        String basic = jsp("site/src/main/webapp/WEB-INF/views/themes/basic/frontIndex.jsp");
        String weixin = jsp("site/src/main/webapp/WEB-INF/views/themes/weixin/frontIndex.jsp");
        for (String dir : List.of("a", "b", "c", "d", "e", "f")) {
            jsp("ui/" + dir + "/index.html");
        }
        resolverBean("/WEB-INF/views/", ".jsp");
        String m = handlerAt("site/src/main/java/com/s/FrontController.java", "com.s.FrontController", "index",
                "themes/{*}/frontIndex", "/{*}/index.html");

        run(new ViewLinker());

        List<Edge> themes = out(m, EdgeKind.RENDERS).stream().filter(e -> e.confidence() != Confidence.UNRESOLVED).toList();
        assertThat(themes).extracting(Edge::to).containsExactlyInAnyOrder(basic, weixin);
        assertThat(themes).allMatch(e -> e.attrBoolean("candidate") && e.attrBoolean("dynamicSegment") && !e.attrs().containsKey("narrowedBy"));
        assertThat(g.edges(m, EdgeKind.RENDERS, "unresolved:view:/{*}/index.html")).hasSize(1);
    }

    private String viewController(String path, String viewName) {
        String id = Ids.httpEndpoint("GET", path);
        g.declare(Node.builder(id, NodeKind.HTTP_ENDPOINT).name(path).location(SourceLocation.of("src/main/webapp/WEB-INF/spring-mvc.xml", 12))
                .attr("httpMethod", "GET").attr("path", path).attr("viewController", true).attr("viewNames", List.of(viewName)).build());
        return id;
    }

    @Test
    @DisplayName("F-012-AC51 메서드 attrs.forwards의 JSP 경로는 컨텍스트 루트 기준이다: 웹 루트 바로 아래의 파일이면 RESOLVED, 접미사로만 맞는 JSP가 하나면 INFERRED + suffixMatch, 여럿이면 잇지 않는다")
    void servletForwardPathIsContextRootRelative() {
        resolverBean("/WEB-INF/jsp/", ".jsp");
        String atRoot = jsp("src/main/webapp/cmm/error/root.jsp");
        jsp("src/main/webapp/WEB-INF/jsp/cmm/error/root.jsp");
        String onlyUnderWebInf = jsp("src/main/webapp/WEB-INF/jsp/cmm/error/hidden.jsp");
        jsp("src/main/webapp/WEB-INF/jsp/a/dup.jsp");
        jsp("src/main/webapp/WEB-INF/old/a/dup.jsp");
        String exact = servletMethod("exact", List.of(), List.of("/cmm/error/root.jsp"));
        String suffix = servletMethod("suffix", List.of(), List.of("/cmm/error/hidden.jsp"));
        String several = servletMethod("several", List.of(), List.of("/a/dup.jsp"));
        // forward: 뷰 이름 접두는 기존 규칙 그대로다(접미사로 맞는 JSP로 RESOLVED)
        String viaViewName = handler("viaViewName", "forward:/cmm/error/hidden.jsp");

        run(new ViewLinker());

        Edge resolved = only(exact, EdgeKind.RENDERS);
        assertThat(resolved.to()).isEqualTo(atRoot);
        assertThat(resolved.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(resolved.attrs()).doesNotContainKeys("suffixMatch", "resolverPrefix");
        Edge bySuffix = only(suffix, EdgeKind.RENDERS);
        assertThat(bySuffix.to()).isEqualTo(onlyUnderWebInf);
        assertThat(bySuffix.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(bySuffix.attrBoolean("suffixMatch")).isTrue();
        assertThat(only(several, EdgeKind.RENDERS).to()).isEqualTo("unresolved:view:/a/dup.jsp");
        assertThat(only(viaViewName, EdgeKind.RENDERS).confidence()).isEqualTo(Confidence.RESOLVED);
    }
}
