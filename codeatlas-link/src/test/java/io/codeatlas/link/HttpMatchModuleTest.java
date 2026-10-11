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

/**
 * 3차 재검증 후속: 모듈 경계에서 자기 모듈의 선언을 먼저 보는 규칙(F-012-R37, R38), 메서드 후보 집합(F-012-R39),
 * action 없는 폼(F-012-R40). 그래프는 손으로 만든다.
 */
class HttpMatchModuleTest extends LinkTestBase {

    private Edge match(String request) {
        return only(request, EdgeKind.MATCHES);
    }

    private boolean unresolved(String request) {
        List<Edge> edges = out(request, EdgeKind.MATCHES);
        return edges.size() == 1 && edges.get(0).confidence() == Confidence.UNRESOLVED
                && edges.get(0).to().startsWith("unresolved:http-endpoint:");
    }

    private List<String> targets(String request) {
        return out(request, EdgeKind.MATCHES).stream().map(Edge::to).sorted().toList();
    }

    /** 여러 모듈의 view-controller가 모인 {@code GET /} 엔드포인트. 핸들러 메서드는 cms-web에만 있다. */
    private String sharedRoot(String... declaringModules) {
        String root = routedEndpoint("GET", "/", "cms-web/src/main/java/com/a/IndexController.java", "com.a.IndexController", "index");
        List<String> declarations = new java.util.ArrayList<>();
        for (String module : declaringModules) {
            declarations.add(module + "/src/main/resources/springMVC-servlet.xml:12:2 /index.jsp");
        }
        g.putAttrs(root, Map.of("viewController", true, "viewNames", List.of("/index.jsp"), "viewDeclarations", declarations));
        return root;
    }

    // ---- M5: 자기 모듈의 선언 (R37) ----

    @Test
    @DisplayName("F-012-AC54 요청 모듈에 그 엔드포인트의 view-controller 선언이 있으면 다른 모듈의 핸들러로 crossModule 연결하지 않고 같은 모듈 매칭이다(RESOLVED, attrs.module)")
    void ownViewControllerDeclarationIsSameModule() {
        for (String module : List.of("cms-web", "pay-web", "upms-server")) {
            project.file(module + "/pom.xml", "<project><packaging>war</packaging></project>");
        }
        rescan();
        String root = sharedRoot("cms-web", "pay-web", "upms-server");
        String fromPay = requestFrom("pay-web/src/main/webapp/WEB-INF/jsp/500.jsp", "GET", "/");
        String fromCms = requestFrom("cms-web/src/main/webapp/WEB-INF/jsp/500.jsp", "GET", "/");

        run(new HttpMatchLinker());

        Edge pay = match(fromPay);
        assertThat(pay.to()).isEqualTo(root);
        assertThat(pay.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(pay.attrs()).doesNotContainKey("crossModule");
        assertThat(pay.attrString("module")).isEqualTo("pay-web");
        Edge cms = match(fromCms);
        assertThat(cms.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(cms.attrString("module")).isEqualTo("cms-web");
    }

    @Test
    @DisplayName("F-012-AC54 선언이 한 모듈에만 있는 엔드포인트의 같은 모듈 매칭에는 attrs.module을 남기지 않는다(따라갈 핸들러·화면이 하나뿐이다)")
    void moduleAttrOnlyForSharedEndpoints() {
        project.file("web-a/pom.xml", "<project/>");
        rescan();
        routedEndpoint("GET", "/list", "web-a/src/main/java/com/a/ListController.java", "com.a.ListController", "list");
        String request = requestFrom("web-a/src/main/webapp/WEB-INF/jsp/index.jsp", "GET", "/list");

        run(new HttpMatchLinker());

        assertThat(match(request).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(request).attrs()).doesNotContainKey("module");
    }

    // ---- M5: 다른 웹앱으로는 잇지 않는다 (R38) ----

    @Test
    @DisplayName("F-012-AC55 요청 모듈이 독립 배포 단위(web.xml, war 패키징, Spring Boot 메인)이고 그 모듈에 선언이 없으면 다른 모듈의 같은 경로로 잇지 않고 미해결이다. 웹앱이 아닌 모듈은 전처럼 INFERRED + crossModule")
    void anotherWebAppIsNotLinked() {
        project.file("cms-admin/pom.xml", "<project><packaging>war</packaging></project>");
        project.file("api-server/pom.xml", "<project><packaging>war</packaging></project>");
        project.file("oss-admin/pom.xml", "<project/>");
        project.file("oss-admin/src/main/webapp/WEB-INF/web.xml", "<web-app/>");
        project.file("boot-app/pom.xml", "<project/>");
        project.file("static-ui/pom.xml", "<project><packaging>jar</packaging></project>");
        rescan();
        String manage = routedEndpoint("GET", "/manage/index", "cms-admin/src/main/java/com/a/ManageController.java", "com.a.ManageController", "index");
        String main = typeAt("boot-app/src/main/java/com/b/App.java", "com.b.App");
        g.addEdge(Edge.of(main, g.reference(Ids.javaType("org.springframework.boot.autoconfigure.SpringBootApplication"),
                NodeKind.JAVA_TYPE, "SpringBootApplication").id(), EdgeKind.ANNOTATED_BY));
        String fromWar = requestFrom("api-server/src/main/webapp/WEB-INF/jsp/index.jsp", "GET", "/manage/index");
        String fromWebXml = requestFrom("oss-admin/src/main/webapp/index.jsp", "GET", "/manage/index");
        String fromBoot = requestFrom("boot-app/src/main/resources/templates/index.html", "GET", "/manage/index");
        String fromStatic = requestFrom("static-ui/src/main/resources/index.html", "GET", "/manage/index");
        String own = requestFrom("cms-admin/src/main/webapp/WEB-INF/jsp/index.jsp", "GET", "/manage/index");

        run(new HttpMatchLinker());

        assertThat(unresolved(fromWar)).as("war 패키징 모듈").isTrue();
        assertThat(unresolved(fromWebXml)).as("web.xml이 있는 모듈").isTrue();
        assertThat(unresolved(fromBoot)).as("Spring Boot 메인 클래스가 있는 모듈").isTrue();
        assertThat(match(fromStatic).to()).isEqualTo(manage);
        assertThat(inferred(match(fromStatic))).isTrue();
        assertThat(match(fromStatic).attrBoolean("crossModule")).isTrue();
        assertThat(match(own).confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    @DisplayName("F-012-AC56 redirect·forward도 같은 규칙이다: 자기 모듈에 view-controller 선언만 있는 경로로의 redirect는 다른 모듈의 핸들러로 잇지 않고, 선언이 없는 웹앱 모듈의 redirect는 미해결이다")
    void dispatchFollowsSameModuleRule() {
        for (String module : List.of("cms-web", "pay-web", "api-server")) {
            project.file(module + "/pom.xml", "<project><packaging>war</packaging></project>");
        }
        rescan();
        sharedRoot("cms-web", "pay-web");
        String cmsIndex = Ids.javaMethod("com.a.IndexController", "index");
        String payHandler = dispatching("pay-web/src/main/java/com/p/PayController.java", "com.p.PayController", "done", "redirect:/");
        String cmsHandler = dispatching("cms-web/src/main/java/com/a/OtherController.java", "com.a.OtherController", "done", "redirect:/");
        String apiHandler = dispatching("api-server/src/main/java/com/x/ApiController.java", "com.x.ApiController", "done", "redirect:/");

        run(new ViewLinker());

        assertThat(out(payHandler, EdgeKind.CALLS)).as("자기 모듈의 view-controller로 가는 redirect: 다른 모듈의 핸들러로 잇지 않는다").isEmpty();
        assertThat(only(cmsHandler, EdgeKind.CALLS).to()).isEqualTo(cmsIndex);
        Edge api = only(apiHandler, EdgeKind.CALLS);
        assertThat(api.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(api.to()).isEqualTo("unresolved:http-endpoint:GET /");
    }

    // ---- crossModule 요청의 대상 모듈 (R48) ----

    /** 주어진 파일의 핸들러가 페이지를 그린다(RENDERS). */
    private void renders(String handlerFile, String type, String name, String page) {
        g.addEdge(Edge.builder(methodAt(handlerFile, type, name), page, EdgeKind.RENDERS).inferred()
                .location(SourceLocation.of(handlerFile, 20)).build());
    }

    private void staticUiProject() {
        project.file("pom.xml", "<project><packaging>pom</packaging></project>");
        for (String module : List.of("cms-web", "pay-web", "upms-server")) {
            project.file(module + "/pom.xml", "<project><packaging>war</packaging></project>");
        }
        rescan();
    }

    @Test
    @DisplayName("F-012-AC66 웹앱이 아닌 모듈의 페이지에서 나간 crossModule 요청은, 그 페이지를 그리는 핸들러가 모두 한 모듈에 있고 그 모듈에 엔드포인트의 선언이 있으면 MATCHES에 module=<그 모듈>을 남긴다(crossModule 유지, INFERRED)")
    void crossModuleRequestGetsRenderingModule() {
        staticUiProject();
        String root = sharedRoot("cms-web", "pay-web", "upms-server");
        String page = jsp("ui/cms-web/qa/index.html");
        renders("cms-web/src/main/java/com/a/QaController.java", "com.a.QaController", "index", page);
        String request = requestFrom("ui/cms-web/qa/index.html", "GET", "/");
        // view-controller가 그리는 페이지: 선언한 설정 파일의 모듈이다
        String payPage = jsp("ui/pay-web/index.html");
        g.addEdge(Edge.builder(root, payPage, EdgeKind.RENDERS).location(SourceLocation.of("pay-web/src/main/resources/springMVC-servlet.xml", 12)).build());
        String fromPay = requestFrom("ui/pay-web/index.html", "GET", "/");

        run(new HttpMatchLinker());

        Edge e = match(request);
        assertThat(e.to()).isEqualTo(root);
        assertThat(inferred(e)).isTrue();
        assertThat(e.attrBoolean("crossModule")).isTrue();
        assertThat(e.attrString("module")).isEqualTo("cms-web");
        assertThat(match(fromPay).attrString("module")).isEqualTo("pay-web");
        assertThat(match(fromPay).attrBoolean("crossModule")).isTrue();
    }

    @Test
    @DisplayName("F-012-AC66 그리는 핸들러가 없거나, 여러 모듈에 있거나, 그 모듈에 엔드포인트의 선언이 없거나, 동적 디렉터리 뷰의 여러 후보 가운데 하나로만 그려지면 전처럼 crossModule만 남긴다. 선언이 한 모듈뿐인 엔드포인트에도 module을 남기지 않는다")
    void crossModuleRequestWithoutSingleRenderingModule() {
        staticUiProject();
        String root = sharedRoot("cms-web", "pay-web");
        jsp("ui/orphan.html");
        String orphan = requestFrom("ui/orphan.html", "GET", "/");
        String shared = jsp("ui/shared.html");
        renders("cms-web/src/main/java/com/a/QaController.java", "com.a.QaController", "index", shared);
        renders("pay-web/src/main/java/com/p/PayController.java", "com.p.PayController", "index", shared);
        String fromShared = requestFrom("ui/shared.html", "GET", "/");
        String upmsPage = jsp("ui/upms/index.html");
        renders("upms-server/src/main/java/com/u/ManageController.java", "com.u.ManageController", "index", upmsPage);
        String fromUpms = requestFrom("ui/upms/index.html", "GET", "/");
        String single = routedEndpoint("GET", "/qa/list", "cms-web/src/main/java/com/a/QaController.java", "com.a.QaController", "list");
        String qaPage = jsp("ui/cms-web/qa/index.html");
        renders("cms-web/src/main/java/com/a/QaController.java", "com.a.QaController", "index", qaPage);
        String toSingle = requestFrom("ui/cms-web/qa/index.html", "GET", "/qa/list");
        // 동적 디렉터리 뷰의 여러 후보 가운데 하나로만 그려지는 페이지: 정말 그 핸들러가 그리는지 모르므로 근거로 쓰지 않는다
        String guessed = jsp("admin/src/login.html");
        g.addEdge(Edge.builder(methodAt("cms-web/src/main/java/com/a/SignController.java", "com.a.SignController", "signin"), guessed, EdgeKind.RENDERS)
                .inferred().location(SourceLocation.of("cms-web/src/main/java/com/a/SignController.java", 20))
                .attr("dynamicSegment", true).attr("candidate", true).build());
        String fromGuessed = requestFrom("admin/src/login.html", "GET", "/");

        run(new HttpMatchLinker());

        for (String request : List.of(orphan, fromShared, fromUpms, fromGuessed)) {
            Edge e = match(request);
            assertThat(e.to()).isEqualTo(root);
            assertThat(e.attrBoolean("crossModule")).isTrue();
            assertThat(e.attrs()).as(request).doesNotContainKey("module");
        }
        assertThat(match(toSingle).to()).isEqualTo(single);
        assertThat(match(toSingle).attrBoolean("crossModule")).isTrue();
        assertThat(match(toSingle).attrs()).doesNotContainKey("module");
    }

    @Test
    @DisplayName("F-012-AC66 다른 페이지에 포함되는 조각(INCLUDES)과 페이지가 싣는 스크립트의 요청은 포함하는 페이지를 그리는 핸들러의 모듈로 판단한다. 그 모듈의 선언이 있는 후보만 남긴다")
    void crossModuleRequestFromIncludedFragment() {
        staticUiProject();
        String root = sharedRoot("cms-web", "pay-web");
        String page = jsp("ui/cms-web/qa/index.html");
        String header = jsp("ui/cms-web/common/header.html");
        String script = g.declare(Node.of(Ids.jsModule("ui/cms-web/js/qa.js"), NodeKind.JS_MODULE, "qa.js")).id();
        g.addEdge(Edge.of(page, header, EdgeKind.INCLUDES, loc()));
        g.addEdge(Edge.of(page, script, EdgeKind.INCLUDES, loc()));
        renders("cms-web/src/main/java/com/a/QaController.java", "com.a.QaController", "index", page);
        String fromHeader = requestFrom("ui/cms-web/common/header.html", "GET", "/");
        String fromScript = requestFrom("ui/cms-web/js/qa.js", "GET", "/");
        // 같은 경로를 두 모듈이 따로 선언했다(ANY와 GET은 다른 노드): 그리는 모듈의 것만 남는다
        String cmsList = routedEndpoint("GET", "/list/{id}", "cms-web/src/main/java/com/a/QaController.java", "com.a.QaController", "list");
        routedEndpoint("GET", "/list/{no}", "pay-web/src/main/java/com/p/PayController.java", "com.p.PayController", "list");
        String toList = requestFrom("ui/cms-web/common/header.html", "GET", "/list/{*}");

        run(new HttpMatchLinker());

        assertThat(match(fromHeader).to()).isEqualTo(root);
        assertThat(match(fromHeader).attrString("module")).isEqualTo("cms-web");
        assertThat(match(fromHeader).attrBoolean("crossModule")).isTrue();
        assertThat(match(fromScript).attrString("module")).isEqualTo("cms-web");
        assertThat(targets(toList)).containsExactly(cmsList);
        assertThat(match(toList).attrs()).doesNotContainKeys("ambiguous", "module");
        assertThat(match(toList).attrBoolean("crossModule")).isTrue();
    }

    private String dispatching(String file, String type, String name, String viewName) {
        String id = methodAt(file, type, name);
        g.putAttrs(id, Map.of("viewNames", List.of(viewName)));
        return id;
    }

    // ---- 후보가 하나뿐인 ambiguous (R45) ----

    @Test
    @DisplayName("F-012-AC63 링커가 만든 엣지 가운데 ambiguous=true인데 같은 (출발, 종류, 위치)에서 나가는 후보가 하나뿐인 것은 없다: 동률인 엔드포인트들이 같은 핸들러로 가면 forward CALLS는 하나이고 ambiguous가 없다")
    void noAmbiguousEdgeWithSingleCandidate() {
        // 메서드만 다른 두 엔드포인트가 같은 핸들러로 간다: forward(ANY)는 둘 다와 동률이지만 부르는 메서드는 하나다
        routedEndpoint("GET", "/order/edit", "src/main/java/com/a/OrderController.java", "com.a.OrderController", "edit");
        routedEndpoint("POST", "/order/edit", "src/main/java/com/a/OrderController.java", "com.a.OrderController", "edit");
        // 메서드만 다른 두 엔드포인트가 다른 핸들러로 간다: 진짜 후보 둘
        routedEndpoint("GET", "/order/item", "src/main/java/com/a/OrderController.java", "com.a.OrderController", "item");
        routedEndpoint("POST", "/order/item", "src/main/java/com/a/OrderController.java", "com.a.OrderController", "saveItem");
        String same = dispatching("src/main/java/com/a/FlowController.java", "com.a.FlowController", "toEdit", "forward:/order/edit");
        String two = dispatching("src/main/java/com/a/FlowController.java", "com.a.FlowController", "toItem", "forward:/order/item");
        String request = g.declare(requestBuilder("ANY", "/order/item").build()).id();
        String page = jsp("src/main/webapp/WEB-INF/jsp/order/edit.jsp");
        g.addEdge(Edge.of(Ids.javaMethod("com.a.OrderController", "edit"), page, EdgeKind.RENDERS, loc()));
        String form = selfSubmit("src/main/webapp/WEB-INF/jsp/order/edit.jsp", "ANY");

        run(new ViewLinker(), new HttpMatchLinker());

        Edge single = only(same, EdgeKind.CALLS);
        assertThat(single.to()).isEqualTo(Ids.javaMethod("com.a.OrderController", "edit"));
        assertThat(single.attrs()).doesNotContainKey("ambiguous");
        assertThat(out(two, EdgeKind.CALLS)).hasSize(2).allMatch(e -> e.attrBoolean("ambiguous"));
        assertThat(out(request, EdgeKind.MATCHES)).hasSize(2).allMatch(e -> e.attrBoolean("ambiguous"));
        assertThat(out(form, EdgeKind.MATCHES)).hasSize(2).allMatch(e -> e.attrBoolean("ambiguous"));
        Map<String, Integer> perSite = new java.util.HashMap<>();
        for (Edge e : g.edges()) {
            if (e.attrBoolean("ambiguous")) {
                perSite.merge(e.kind() + " " + e.from() + " @" + e.location(), 1, Integer::sum);
            }
        }
        assertThat(perSite).isNotEmpty().allSatisfy((site, count) -> assertThat(count).as(site).isGreaterThan(1));
    }

    // ---- 정적 파일 꼴의 상대 링크 (R46) ----

    @Test
    @DisplayName("F-012-AC64 정적 파일 꼴의 상대 링크(login.html)는 기준 디렉터리로 풀어도 경로 변수 세그먼트(/topic/{topicId})에 맞추지 않는다. 고정 세그먼트로 맞는 엔드포인트와 정적 파일 꼴이 아닌 상대 링크는 전과 같다")
    void staticLookingRelativeLinkDoesNotMatchPathVariable() {
        String page = jsp("web-ui/topic/index.html");
        String byId = routedEndpoint("GET", "/topic/{topicId}", "src/main/java/com/a/TopicController.java", "com.a.TopicController", "topic");
        String literal = routedEndpoint("GET", "/topic/list.html", "src/main/java/com/a/TopicController.java", "com.a.TopicController", "list");
        g.addEdge(Edge.of(Ids.javaMethod("com.a.TopicController", "topic"), page, EdgeKind.RENDERS, loc()));
        String login = requestFrom("web-ui/topic/index.html", "GET", "login.html");
        String list = requestFrom("web-ui/topic/index.html", "GET", "list.html");
        String next = requestFrom("web-ui/topic/index.html", "GET", "42");

        run(new HttpMatchLinker());

        assertThat(out(login, EdgeKind.MATCHES)).as("기준 디렉터리의 다른 파일을 가리키는 링크다: 경로 변수의 값이 아니다").isEmpty();
        assertThat(match(list).to()).isEqualTo(literal);
        assertThat(match(next).to()).isEqualTo(byId);
    }

    // ---- 실제 매핑의 첫 세그먼트는 접두로 떼지 않는다 (R49) ----

    @Test
    @DisplayName("F-012-AC67 떼려는 접두의 첫 세그먼트가 선언된 엔드포인트 경로의 첫 고정 세그먼트면(/manage/…) 컨텍스트 경로로 보고 떼지 않는다: /manage/coder/index는 GET /index로 잇지 않고 미해결이다. redirect도 같다")
    void mappedFirstSegmentIsNotStrippedAsPrefix() {
        String index = endpoint("GET", "/index");
        endpoint("GET", "/manage/index");
        endpoint("GET", "/manage/user/list");
        String orders = endpoint("GET", "/orders");
        String dead = request("GET", "/manage/coder/index");
        String deadOne = request("GET", "/manage/orders");
        String deadWithHost = request("GET", "http://localhost:8080/manage/coder/index");
        String deadUnknownPrefix = request("GET", "{*}/manage/coder/index");
        String live = request("GET", "/manage/index");
        String context = request("GET", "/shop/orders");
        String contextTwo = request("GET", "/shop/app/index");
        String handler = dispatching("src/main/java/com/a/CoderController.java", "com.a.CoderController", "done", "redirect:/manage/coder/index");

        run(new ViewLinker(), new HttpMatchLinker());

        for (String request : List.of(dead, deadOne, deadWithHost, deadUnknownPrefix)) {
            assertThat(unresolved(request)).as(request).isTrue();
        }
        assertThat(match(live).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(context).to()).isEqualTo(orders);
        assertThat(match(context).attrString("strippedPrefix")).isEqualTo("/shop");
        assertThat(match(contextTwo).to()).isEqualTo(index);
        assertThat(match(contextTwo).attrString("strippedPrefix")).isEqualTo("/shop/app");
        Edge redirect = only(handler, EdgeKind.CALLS);
        assertThat(redirect.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(redirect.to()).isEqualTo("unresolved:http-endpoint:GET /manage/coder/index");
    }

    @Test
    @DisplayName("F-012-AC67 근거가 있는 접두(web.xml의 서블릿 매핑)는 그 첫 세그먼트로 시작하는 엔드포인트가 있어도 뗀다. 경로 변수·값을 모르는 자리표시자로 시작하는 엔드포인트는 '실제 매핑'의 근거가 아니다")
    void evidencedPrefixAndVariableFirstSegment() {
        project.file("src/main/webapp/WEB-INF/web.xml", "<web-app/>");
        rescan();
        g.putAttrs(Ids.file("src/main/webapp/WEB-INF/web.xml"), Map.of("servletMappings", List.of("/api/*")));
        String orders = endpoint("GET", "/orders");
        endpoint("GET", "/api/ping");
        endpoint("GET", "/{tenant}/home");
        endpoint("GET", "/${adminPath}/menu");
        String viaServlet = request("GET", "/api/orders");
        String tenant = request("GET", "/t1/orders");
        String placeholder = request("GET", "/admin/orders");

        run(new HttpMatchLinker());

        assertThat(match(viaServlet).to()).isEqualTo(orders);
        assertThat(match(viaServlet).attrString("strippedPrefix")).isEqualTo("/api");
        assertThat(match(tenant).to()).isEqualTo(orders);
        assertThat(match(tenant).attrString("strippedPrefix")).isEqualTo("/t1");
        assertThat(match(placeholder).to()).isEqualTo(orders);
        assertThat(match(placeholder).attrString("strippedPrefix")).isEqualTo("/admin");
    }

    // ---- M1: 메서드 후보 집합 (R39) ----

    @Test
    @DisplayName("F-012-AC57 attrs.httpMethods가 있는 요청은 그 메서드들의 엔드포인트와 ANY 엔드포인트만 후보다: ANY /api/schedule/{*} + [POST,PUT]은 PUT /schedule/{id}로만 가고 DELETE·GET은 뺀다")
    void methodCandidateSet() {
        project.file("src/main/webapp/WEB-INF/web.xml", "<web-app/>");
        rescan();
        g.putAttrs(Ids.file("src/main/webapp/WEB-INF/web.xml"), Map.of("servletMappings", List.of("/api/*")));
        endpoint("DELETE", "/schedule/{id}");
        endpoint("GET", "/schedule/{id}");
        String put = endpoint("PUT", "/schedule/{id}");
        endpoint("POST", "/schedule");
        String request = g.declare(requestBuilder("ANY", "/api/schedule/{*}").attr("path", "/api/schedule/{*}")
                .attr("httpMethods", List.of("POST", "PUT")).build()).id();
        String noSet = g.declare(requestBuilder("ANY", "/api/schedule/{*}").attr("path", "/api/schedule/{*}").build()).id();

        run(new HttpMatchLinker());

        assertThat(targets(request)).containsExactly(put);
        assertThat(match(request).attrs()).doesNotContainKey("ambiguous");
        assertThat(targets(noSet)).as("메서드 후보가 없는 ANY 요청은 전처럼 메서드만 다른 엔드포인트 전부").hasSize(3);
    }

    @Test
    @DisplayName("F-012-AC57 메서드 후보에 맞는 엔드포인트가 여럿이면 전부 INFERRED + ambiguous이고, ANY 엔드포인트도 후보이며, 맞는 것이 없으면 미해결이다")
    void methodCandidateSetAmbiguousAndAny() {
        String post = endpoint("POST", "/item");
        String put = endpoint("PUT", "/item");
        endpoint("DELETE", "/item");
        String any = endpoint(null, "/legacy.do");
        endpoint("GET", "/only-get");
        String both = g.declare(requestBuilder("ANY", "/item").attr("httpMethods", List.of("POST", "PUT")).build()).id();
        String toAny = g.declare(requestBuilder("ANY", "/legacy.do").attr("httpMethods", List.of("POST", "PUT")).build()).id();
        String none = g.declare(requestBuilder("ANY", "/only-get").attr("httpMethods", List.of("POST", "PUT")).build()).id();

        run(new HttpMatchLinker());

        assertThat(targets(both)).containsExactly(post, put);
        assertThat(out(both, EdgeKind.MATCHES)).allMatch(e -> inferred(e) && e.attrBoolean("ambiguous"));
        assertThat(match(toAny).to()).isEqualTo(any);
        assertThat(unresolved(none)).isTrue();
    }

    // ---- selfSubmit 폼 (R40) ----

    private String selfSubmit(String file, String httpMethod) {
        String id = "http:request:" + Ids.httpMethod(httpMethod) + " @" + file + ":7";
        g.declare(Node.builder(id, NodeKind.HTTP_REQUEST).location(SourceLocation.of(file, 7))
                .attr("httpMethod", Ids.httpMethod(httpMethod)).attr("selfSubmit", true).build());
        g.addEdge(Edge.of(Ids.jspPage(file), id, EdgeKind.REQUESTS, SourceLocation.of(file, 7)));
        return id;
    }

    @Test
    @DisplayName("F-012-AC58 action 없는 폼(selfSubmit=true)은 그 JSP를 그리는 핸들러의 엔드포인트와 경로가 같고 메서드가 맞는 엔드포인트로 MATCHES(INFERRED, selfSubmit=true)다: GET /x 화면 + POST /x 처리")
    void selfSubmitFormGoesToSamePath() {
        String page = jsp("src/main/webapp/WEB-INF/jsp/user/form.jsp");
        String get = routedEndpoint("GET", "/user/form", "src/main/java/com/a/UserController.java", "com.a.UserController", "form");
        String post = routedEndpoint("POST", "/user/form", "src/main/java/com/a/UserController.java", "com.a.UserController", "save");
        routedEndpoint("POST", "/user/other", "src/main/java/com/a/UserController.java", "com.a.UserController", "other");
        g.addEdge(Edge.of(Ids.javaMethod("com.a.UserController", "form"), page, EdgeKind.RENDERS, loc()));
        String form = selfSubmit("src/main/webapp/WEB-INF/jsp/user/form.jsp", "POST");
        String search = selfSubmit("src/main/webapp/WEB-INF/jsp/user/form.jsp", "GET");

        run(new HttpMatchLinker());

        Edge edge = match(form);
        assertThat(edge.to()).isEqualTo(post);
        assertThat(edge.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(edge.attrBoolean("selfSubmit")).isTrue();
        assertThat(match(search).to()).as("GET 폼은 화면을 그리는 그 엔드포인트로 돌아간다").isEqualTo(get);
    }

    @Test
    @DisplayName("F-012-AC58 그리는 핸들러가 여럿이면 엔드포인트마다 잇고, 메서드를 가리지 않는 엔드포인트(ANY)도 받는다. 그리는 핸들러를 모르거나 맞는 엔드포인트가 없으면 미해결이다")
    void selfSubmitSeveralRenderersAndUnresolved() {
        String page = jsp("src/main/webapp/WEB-INF/jsp/board/edit.jsp");
        routedEndpoint("GET", "/board/new", "src/main/java/com/a/BoardController.java", "com.a.BoardController", "fresh");
        String createPost = routedEndpoint("POST", "/board/new", "src/main/java/com/a/BoardController.java", "com.a.BoardController", "create");
        String editAny = routedEndpoint(null, "/board/edit.do", "src/main/java/com/a/BoardController.java", "com.a.BoardController", "edit");
        g.addEdge(Edge.of(Ids.javaMethod("com.a.BoardController", "fresh"), page, EdgeKind.RENDERS, loc()));
        g.addEdge(Edge.of(Ids.javaMethod("com.a.BoardController", "edit"), page, EdgeKind.RENDERS, loc()));
        String form = selfSubmit("src/main/webapp/WEB-INF/jsp/board/edit.jsp", "POST");

        jsp("src/main/webapp/WEB-INF/jsp/orphan.jsp");
        String orphan = selfSubmit("src/main/webapp/WEB-INF/jsp/orphan.jsp", "POST");
        String getOnlyPage = jsp("src/main/webapp/WEB-INF/jsp/view.jsp");
        routedEndpoint("GET", "/view", "src/main/java/com/a/ViewController.java", "com.a.ViewController", "view");
        g.addEdge(Edge.of(Ids.javaMethod("com.a.ViewController", "view"), getOnlyPage, EdgeKind.RENDERS, loc()));
        String noPost = selfSubmit("src/main/webapp/WEB-INF/jsp/view.jsp", "POST");

        run(new HttpMatchLinker());

        assertThat(targets(form)).containsExactlyInAnyOrder(createPost, editAny);
        assertThat(out(form, EdgeKind.MATCHES)).allMatch(e -> inferred(e) && e.attrBoolean("selfSubmit"));
        assertThat(unresolved(orphan)).isTrue();
        assertThat(unresolved(noPost)).isTrue();
    }
}
