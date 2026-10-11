package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 공개 프로젝트 검증에서 나온 요청↔엔드포인트 매칭 규칙(F-012-R15~R21). 그래프는 손으로 만든다. */
class HttpMatchRulesTest extends LinkTestBase {

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

    // ---- 프로퍼티 자리표시자 (R15) ----

    @Test
    @DisplayName("F-012-AC27 엔드포인트 경로의 ${키}를 프로젝트 properties 값으로 치환해 맞추고 resolvedPath를 남긴다")
    void placeholderResolvedFromProperties() {
        project.file("src/main/resources/app.properties", "adminPath=/a\nfrontPath=/f\nurlSuffix=.html\n");
        rescan();
        String form = endpoint(null, "/${adminPath}/sys/user/form");
        String index = endpoint(null, "/${frontPath}/index-{siteId}${urlSuffix}");
        String byDefault = endpoint("GET", "/${apiBase:/api}/ping");
        String r1 = request("GET", "/a/sys/user/form");
        String r2 = request("GET", "/f/index-1.html");
        String r3 = request("GET", "/api/ping");
        String r4 = request("GET", "/b/sys/user/form");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(form);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(g.node(form).orElseThrow().attrString("resolvedPath")).isEqualTo("/a/sys/user/form");
        assertThat(match(r2).to()).isEqualTo(index);
        assertThat(match(r2).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(g.node(index).orElseThrow().attrString("resolvedPath")).isEqualTo("/f/index-{siteId}.html");
        assertThat(match(r3).to()).isEqualTo(byDefault);
        assertThat(g.node(byDefault).orElseThrow().attrString("resolvedPath")).isEqualTo("/api/ping");
        assertThat(unresolved(r4)).as("치환된 값과 다른 고정 접두는 맞지 않는다").isTrue();
    }

    @Test
    @DisplayName("F-012-AC27 여러 파일에 값이 다른 키는 치환하지 않는다(resolvedPath 없음)")
    void placeholderWithConflictingValuesStaysUnresolved() {
        project.file("src/main/resources/dev.properties", "adminPath=/a\n");
        project.file("src/main/resources/prod.properties", "adminPath=/admin\n");
        rescan();
        String form = endpoint(null, "/${adminPath}/sys/user/form");
        String r1 = request("GET", "/a/sys/user/form");

        run(new HttpMatchLinker());

        assertThat(g.node(form).orElseThrow().attrString("resolvedPath")).isNull();
        assertThat(unresolved(r1)).isTrue();
    }

    @Test
    @DisplayName("F-012-AC28 값을 못 찾은 자리표시자 세그먼트는 와일드카드가 아니라 알 수 없는 고정 접두다: 요청의 {*}와만 INFERRED로 맞는다")
    void unknownPlaceholderIsNotWildcard() {
        String admin = endpoint(null, "/${adminPath}");
        endpoint(null, "/${frontPath}");
        String form = endpoint(null, "/${adminPath}/sys/user/form");
        String r1 = request("GET", "/index-{*}");
        String r2 = request("GET", "/x/sys/user/form");
        String r3 = request("GET", "/{*}/sys/user/form");
        String r4 = request("GET", "{*}/sys/user/form");
        String r5 = request("GET", "/oa/oaNotify/{*}");
        String r6 = request("GET", "{*}cordova_plugins.json");

        run(new HttpMatchLinker());

        assertThat(unresolved(r1)).as("/index-{*} 는 /${adminPath}·/${frontPath} 와 맞지 않는다").isTrue();
        assertThat(unresolved(r2)).as("리터럴 x 는 미지 접두와 맞지 않는다").isTrue();
        assertThat(match(r3).to()).isEqualTo(form);
        assertThat(inferred(match(r3))).isTrue();
        assertThat(match(r3).attrBoolean("dynamicSegment")).isTrue();
        assertThat(match(r4).to()).isEqualTo(form);
        assertThat(inferred(match(r4))).isTrue();
        assertThat(match(r4).attrString("strippedPrefix")).isEqualTo("{*}");
        assertThat(match(r4).attrString("strippedPlaceholder")).isEqualTo("${adminPath}");
        assertThat(unresolved(r5)).as("접두를 떼고 남은 {*} 하나로는 루트 핸들러에 붙지 않는다").isTrue();
        assertThat(unresolved(r6)).isTrue();
        assertThat(g.node(admin).orElseThrow().attrString("resolvedPath")).isNull();
    }

    @Test
    @DisplayName("F-012-AC29 요청 경로가 엔드포인트 경로에서 맨 앞 자리표시자 세그먼트를 뗀 나머지와 맞으면 INFERRED + strippedPlaceholder")
    void strippedPlaceholder() {
        project.file("src/main/resources/app.properties", "adminPath=/a\nfrontPath=/f\nurlSuffix=.html\n");
        rescan();
        String form = endpoint(null, "/${adminPath}/sys/user/form");
        String leave = endpoint(null, "/${adminPath}/oa/leave");
        String index = endpoint(null, "/${frontPath}/index-{siteId}${urlSuffix}");
        endpoint(null, "/${frontPath}");
        String unknown = endpoint(null, "/${otherPath}/cms/link/form");
        String exact = endpoint("GET", "/sys/dict/form");
        endpoint(null, "/${adminPath}/sys/dict/form");
        String r1 = request("GET", "/sys/user/form");
        String r2 = request("GET", "/oa/leave/");
        String r3 = request("GET", "/index-{*}");
        String r4 = request("GET", "/cms/link/form");
        String r5 = request("GET", "/sys/dict/form");
        String r6 = request("GET", "/");
        String r7 = request("GET", "{*}/sys/user/form");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(form);
        assertThat(inferred(match(r1))).isTrue();
        assertThat(match(r1).attrString("strippedPlaceholder")).isEqualTo("${adminPath}");
        assertThat(match(r2).to()).isEqualTo(leave);
        assertThat(inferred(match(r2))).isTrue();
        assertThat(match(r2).attrBoolean("trailingSlash")).isTrue();
        assertThat(match(r3).to()).isEqualTo(index);
        assertThat(inferred(match(r3))).isTrue();
        assertThat(match(r3).attrString("strippedPlaceholder")).isEqualTo("${frontPath}");
        assertThat(match(r4).to()).as("값을 못 찾은 자리표시자도 같은 규칙").isEqualTo(unknown);
        assertThat(match(r4).attrString("strippedPlaceholder")).isEqualTo("${otherPath}");
        assertThat(match(r5).to()).as("정확히 맞는 엔드포인트가 먼저다").isEqualTo(exact);
        assertThat(match(r5).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(unresolved(r6)).as("자리표시자뿐인 엔드포인트는 나머지가 없어 맞추지 않는다").isTrue();
        assertThat(match(r7).to()).isEqualTo(form);
        assertThat(match(r7).attrString("strippedPrefix")).isEqualTo("{*}");
        assertThat(match(r7).attrString("strippedPlaceholder")).isEqualTo("${adminPath}");
    }

    // ---- 리터럴과 변수가 섞인 세그먼트 (R16) ----

    @Test
    @DisplayName("F-012-AC30 리터럴과 변수가 섞인 세그먼트는 리터럴 부분이 같은 자리에 맞아야 한다")
    void mixedSegments() {
        String comment = endpoint("GET", "/f/comment-{theme}");
        String view = endpoint(null, "/f/view-{categoryId}-{contentId}.html");
        String list = endpoint(null, "/f/list-{categoryId}.html");
        endpoint(null, "/f/listc-{categoryId}-{customView}.html");
        String json = endpoint("GET", "/data/{name}.json");
        String r1 = request("GET", "/f/view-{*}-{*}");
        String r2 = request("GET", "/f/view-{*}-{*}.html");
        String r3 = request("GET", "/f/list-{*}");
        String r4 = request("GET", "/{*}/list-{*}");
        String r5 = request("GET", "/f/comment-basic");
        String r6 = request("GET", "/f/index-{*}");
        String r7 = request("GET", "/data/menu.json");
        String r8 = request("GET", "/data/menu.xml");

        run(new HttpMatchLinker());

        assertThat(targets(r1)).containsExactly(view);
        assertThat(inferred(match(r1))).as("요청의 {*}가 .html 을 덮어야 맞는다").isTrue();
        assertThat(match(r1).attrBoolean("dynamicSegment")).isTrue();
        assertThat(targets(r2)).containsExactly(view);
        assertThat(match(r2).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(targets(r3)).containsExactly(list);
        assertThat(targets(r4)).containsExactly(list);
        assertThat(inferred(match(r4))).isTrue();
        assertThat(match(r5).to()).isEqualTo(comment);
        assertThat(match(r5).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(unresolved(r6)).isTrue();
        assertThat(match(r7).to()).isEqualTo(json);
        assertThat(match(r7).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(unresolved(r8)).isTrue();
    }

    // ---- 요청 맨 앞의 {*} (R17) ----

    @Test
    @DisplayName("F-012-AC31 요청 맨 앞의 {*}는 0개 이상의 미지 접두다: 나머지 경로 전체가 엔드포인트 경로 전체와 맞을 때만 INFERRED")
    void leadingUnknownPrefix() {
        String getBoard = endpoint("GET", "/board");
        String postBoard = endpoint("POST", "/board");
        endpoint("DELETE", "/schedule/{schdulId}");
        String detail = endpoint("GET", "/board/{bbsId}/{nttId}");
        String r1 = request("GET", "{*}/board");
        String r2 = request("ANY", "{*}/board");
        String r3 = request("DELETE", "{*}/board");
        String r4 = request("GET", "{*}/board/{*}/{*}");
        String r5 = request("GET", "{*}/x/board");

        run(new HttpMatchLinker());

        assertThat(targets(r1)).containsExactly(getBoard);
        assertThat(inferred(match(r1))).isTrue();
        assertThat(match(r1).attrString("strippedPrefix")).isEqualTo("{*}");
        assertThat(targets(r2)).containsExactly(getBoard, postBoard);
        assertThat(out(r2, EdgeKind.MATCHES)).allMatch(e -> inferred(e) && e.attrBoolean("ambiguous"));
        assertThat(unresolved(r3)).as("{*}/board 는 DELETE /schedule/{schdulId} 가 아니다").isTrue();
        assertThat(targets(r4)).containsExactly(detail);
        assertThat(unresolved(r5)).isTrue();
    }

    // ---- 상대 링크 (R18) ----

    @Test
    @DisplayName("F-012-AC32 상대 링크의 접미 매칭은 엔드포인트의 고정 세그먼트와만 한다(경로 변수에는 맞추지 않는다)")
    void relativeSuffixOnlyOnFixedSegments() {
        endpoint("GET", "/news/article/{articleId}");
        endpoint("GET", "/topic/{topicId}");
        endpoint("GET", "/{page}");
        String detail = endpoint("GET", "/order/detail/{id}");
        String r1 = requestFrom("ui/news/index.html", "GET", "article/index.html");
        String r2 = requestFrom("ui/news/index.html", "GET", "../news/article/list");
        String r3 = requestFrom("ui/topic/list.html", "GET", "topic/index.html");
        String r4 = requestFrom("ui/a.jsp", "GET", "signin");
        String r5 = requestFrom("ui/a.jsp", "GET", "detail/{*}");
        String catalog = endpoint("GET", "/catalog");
        endpoint("GET", "/catalog/");
        String r6 = requestFrom("index.html", "GET", "catalog");
        endpoint("GET", "/a/test/test");
        String r7 = requestFrom("static/demo/index.html", "GET", "../test/");

        run(new HttpMatchLinker());

        assertThat(targets(r6)).as("끝 슬래시만 다른 같은 엔드포인트는 후보 하나다").containsExactly(catalog);
        assertThat(unresolved(r7)).as("디렉터리를 가리키는 상대 링크는 끝 슬래시 없는 엔드포인트의 접미로 잇지 않는다").isTrue();

        assertThat(out(r1, EdgeKind.MATCHES)).as("index.html 은 {articleId} 가 아니다(정적 자원이라 미해결도 남기지 않는다)").isEmpty();
        assertThat(unresolved(r2)).isTrue();
        assertThat(out(r3, EdgeKind.MATCHES)).isEmpty();
        assertThat(unresolved(r4)).as("한 조각 상대 링크는 /{page} 에 맞추지 않는다").isTrue();
        assertThat(match(r5).to()).isEqualTo(detail);
        assertThat(inferred(match(r5))).isTrue();
        assertThat(match(r5).attrBoolean("relative")).isTrue();
    }

    @Test
    @DisplayName("F-012-AC33 상대 링크는 그 페이지를 그리는(RENDERS) 핸들러의 엔드포인트 경로를 기준 디렉터리로 풀어 본다")
    void relativeResolvedAgainstRenderingEndpoint() {
        String page = jsp("src/main/webapp/WEB-INF/jsp/sso/index.jsp");
        String other = jsp("src/main/webapp/WEB-INF/jsp/etc/loose.jsp");
        String index = routedEndpoint("GET", "/sso/index", "src/main/java/com/a/SsoController.java", "com.a.SsoController", "index");
        String handler = g.outgoing(index, EdgeKind.ROUTES_TO).get(0).to();
        g.addEdge(Edge.of(handler, page, EdgeKind.RENDERS));
        String ssoSignin = endpoint("POST", "/sso/signin");
        endpoint("POST", "/member/signin");
        endpoint("GET", "/member/logout");
        String r1 = requestFrom("src/main/webapp/WEB-INF/jsp/sso/index.jsp", "POST", "signin");
        String r2 = requestFrom("src/main/webapp/WEB-INF/jsp/etc/loose.jsp", "POST", "signin");
        String r3 = requestFrom("src/main/webapp/WEB-INF/jsp/sso/index.jsp", "GET", "logout");
        String r4 = requestFrom("src/main/webapp/WEB-INF/jsp/sso/index.jsp", "POST", "../member/signin");
        String r5 = requestFrom("src/main/webapp/WEB-INF/jsp/sso/index.jsp", "GET", "member/logout");

        run(new HttpMatchLinker());

        assertThat(match(r5).to()).as("여러 조각 상대 경로가 엔드포인트 경로 전체와 같으면 맨 앞의 / 를 빠뜨린 것으로 본다")
                .isEqualTo("http:endpoint:GET /member/logout");
        assertThat(inferred(match(r5))).isTrue();
        assertThat(match(r5).attrs()).doesNotContainKey("relativeBase");

        assertThat(other).isNotNull();
        assertThat(targets(r1)).containsExactly(ssoSignin);
        assertThat(inferred(match(r1))).isTrue();
        assertThat(match(r1).attrBoolean("relative")).isTrue();
        assertThat(match(r1).attrString("relativeBase")).isEqualTo("/sso");
        assertThat(unresolved(r2)).as("기준을 모르고 후보가 둘이면 잇지 않는다").isTrue();
        assertThat(unresolved(r3)).as("기준 디렉터리에 없는 경로는 다른 디렉터리의 같은 이름으로 잇지 않는다").isTrue();
        assertThat(match(r4).to()).isEqualTo("http:endpoint:POST /member/signin");
        assertThat(match(r4).attrString("relativeBase")).isEqualTo("/sso");
    }

    @Test
    @DisplayName("F-012-AC33 뷰 연결 링커가 만든 RENDERS를 HTTP 연결 링커가 이어서 쓴다(링커 순서)")
    void relativeUsesRendersFromViewLinker() {
        g.declare(io.codeatlas.core.model.Node.builder(io.codeatlas.core.model.Ids.springBean("viewResolver"),
                        io.codeatlas.core.model.NodeKind.SPRING_BEAN)
                .attr("class", "org.springframework.web.servlet.view.InternalResourceViewResolver")
                .attr("property.prefix", "/WEB-INF/jsp/").attr("property.suffix", ".jsp").build());
        jsp("src/main/webapp/WEB-INF/jsp/sso/index.jsp");
        String index = routedEndpoint("GET", "/sso/index", "src/main/java/com/a/SsoController.java", "com.a.SsoController", "index");
        g.putAttrs(g.outgoing(index, EdgeKind.ROUTES_TO).get(0).to(), java.util.Map.of("viewNames", List.of("sso/index")));
        String ssoSignin = endpoint("POST", "/sso/signin");
        endpoint("POST", "/member/signin");
        String r1 = requestFrom("src/main/webapp/WEB-INF/jsp/sso/index.jsp", "POST", "signin");

        run(new HttpMatchLinker(), new ViewLinker());

        assertThat(targets(r1)).containsExactly(ssoSignin);
    }

    // ---- 끝 슬래시 (R19) ----

    @Test
    @DisplayName("F-012-AC34 끝 슬래시만 다른 요청과 엔드포인트는 맞는 것으로 보고 trailingSlash=true를 남긴다(신뢰도는 그대로)")
    void trailingSlash() {
        String orders = endpoint("GET", "/orders");
        String list = endpoint("GET", "/list/");
        String x = endpoint("GET", "/x");
        String xSlash = endpoint("GET", "/x/");
        String r1 = request("GET", "/orders/");
        String r2 = request("GET", "/list");
        String r3 = request("GET", "/orders");
        String r4 = request("GET", "/x/");
        String r5 = request("GET", "/x");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(orders);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r1).attrBoolean("trailingSlash")).isTrue();
        assertThat(match(r2).to()).isEqualTo(list);
        assertThat(match(r2).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r2).attrBoolean("trailingSlash")).isTrue();
        assertThat(match(r3).attrs()).doesNotContainKey("trailingSlash");
        assertThat(targets(r4)).as("끝 슬래시까지 같은 엔드포인트가 먼저다").containsExactly(xSlash);
        assertThat(match(r4).attrs()).doesNotContainKey("trailingSlash");
        assertThat(targets(r5)).containsExactly(x);
    }

    // ---- 루트 엔드포인트와 접두 제거 (R20) ----

    @Test
    @DisplayName("F-012-AC35 요청 / 는 루트 엔드포인트와 맞고, 접두 제거 추론은 고정 세그먼트가 하나 이상 일치할 때만 한다")
    void rootEndpointAndPrefixStripNeedsFixedSegment() {
        String root = endpoint("GET", "/");
        String page = endpoint("GET", "/{page}");
        String orders = endpoint("GET", "/orders");
        String r1 = request("GET", "/");
        String r2 = request("GET", "/shop/");
        String r3 = request("GET", "/shop/app/{*}");
        String r4 = request("GET", "/ctx/orders");
        String r5 = request("GET", "http://localhost:8080/");
        String r6 = request("GET", "/a/b/c");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(root);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(targets(r2)).as("/shop/ 은 /{page} 와 정확히 맞는다(접두를 떼어 루트로 가지 않는다)").containsExactly(page);
        assertThat(match(r2).attrs()).doesNotContainKey("strippedPrefix");
        assertThat(unresolved(r3)).as("접두를 떼고 남은 것이 {*}뿐이면 추론하지 않는다").isTrue();
        assertThat(match(r4).to()).isEqualTo(orders);
        assertThat(match(r4).attrString("strippedPrefix")).isEqualTo("/ctx");
        assertThat(match(r5).to()).isEqualTo(root);
        assertThat(inferred(match(r5))).isTrue();
        assertThat(unresolved(r6)).as("고정 조각이 맞지 않는 /{page} 로 접두 제거 추론하지 않는다").isTrue();
    }

    // ---- 멀티모듈 웹앱 경계 (R21) ----

    @Test
    @DisplayName("F-012-AC36 화면과 핸들러의 모듈 루트가 다르고 같은 모듈에 맞는 엔드포인트가 없으면 INFERRED + crossModule, 같은 모듈에 있으면 그것만")
    void moduleBoundary() {
        project.file("web-a/pom.xml", "<project/>");
        project.file("web-b/build.gradle", "plugins {}");
        project.file("web-c/pom.xml", "<project/>");
        rescan();
        String shared = routedEndpoint("GET", "/manage/index", "web-a/src/main/java/com/a/ManageController.java", "com.a.ManageController", "index");
        routedEndpoint("GET", "/manage/index", "web-b/src/main/java/com/b/ManageController.java", "com.b.ManageController", "index");
        String login = routedEndpoint("GET", "/sso/login", "web-b/src/main/java/com/b/SsoController.java", "com.b.SsoController", "login");
        String itemById = routedEndpoint("GET", "/item/{id}", "web-a/src/main/java/com/a/ItemController.java", "com.a.ItemController", "byId");
        routedEndpoint("GET", "/item/new", "web-b/src/main/java/com/b/ItemController.java", "com.b.ItemController", "fresh");
        String r1 = requestFrom("web-a/src/main/webapp/WEB-INF/jsp/index.jsp", "GET", "/manage/index");
        String r2 = requestFrom("web-a/src/main/webapp/WEB-INF/jsp/index.jsp", "GET", "/sso/login");
        String r3 = requestFrom("web-a/src/main/webapp/WEB-INF/jsp/index.jsp", "GET", "/item/new");
        String r4 = requestFrom("front/src/App.jsx", "GET", "/sso/login");
        String r5 = requestFrom("web-c/src/main/webapp/index.html", "GET", "/sso/login");
        String r6 = requestFrom("web-b/src/main/webapp/WEB-INF/jsp/login.jsp", "GET", "/sso/login");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(shared);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r1).attrs()).doesNotContainKey("crossModule");
        assertThat(match(r2).to()).isEqualTo(login);
        assertThat(inferred(match(r2))).isTrue();
        assertThat(match(r2).attrBoolean("crossModule")).isTrue();
        assertThat(targets(r3)).as("같은 모듈에 맞는 엔드포인트가 있으면 그것만").containsExactly(itemById);
        assertThat(match(r3).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r4).confidence()).as("빌드 파일이 없는 프런트 폴더: 판단하지 않는다").isEqualTo(Confidence.RESOLVED);
        // 2026-10-11 고쳐 씀(F-012-R36, AC53): 전에는 핸들러가 없는 모듈의 요청은 판단하지 않아 RESOLVED였다
        assertThat(inferred(match(r5))).as("핸들러가 없는 모듈이어도 대상 핸들러가 전부 다른 모듈이면 crossModule").isTrue();
        assertThat(match(r5).attrBoolean("crossModule")).isTrue();
        assertThat(match(r6).confidence()).isEqualTo(Confidence.RESOLVED);
    }

    // ---- 샘플 프로젝트와 같은 모양(기존 규칙 유지) ----

    @Test
    @DisplayName("F-012-AC12 F-012-AC14 샘플과 같은 모양: 컨텍스트 경로 접두, 같은 경로·다른 메서드, 쿼리스트링, axios baseURL")
    void sampleShapedGraph() {
        String list = routedEndpoint("GET", "/order/list.do", "src/main/java/com/shop/OrderController.java", "com.shop.OrderController", "list");
        String save = routedEndpoint("POST", "/order/save.do", "src/main/java/com/shop/OrderController.java", "com.shop.OrderController", "save");
        String apiGet = routedEndpoint("GET", "/api/orders/{id}", "src/main/java/com/shop/OrderApi.java", "com.shop.OrderApi", "get");
        String apiPut = routedEndpoint("PUT", "/api/orders/{id}", "src/main/java/com/shop/OrderApi.java", "com.shop.OrderApi", "update");
        String apiList = routedEndpoint("GET", "/api/orders", "src/main/java/com/shop/OrderApi.java", "com.shop.OrderApi", "list");
        String apiCreate = routedEndpoint("POST", "/api/orders", "src/main/java/com/shop/OrderApi.java", "com.shop.OrderApi", "create");
        String r1 = requestFrom("src/main/webapp/WEB-INF/views/order/list.jsp", "GET", "/order/list.do?page=1");
        String r2 = requestFrom("src/main/webapp/WEB-INF/views/order/form.jsp", "POST", "/order/save.do");
        String r3 = requestFrom("src/main/webapp/WEB-INF/views/order/form.jsp", "GET", "/shop/order/list.do");
        String r4 = requestFrom("src/main/webapp/static/js/order.js", "GET", "/api/orders/{*}");
        String r5 = requestFrom("src/main/webapp/static/js/order.js", "PUT", "/api/orders/{*}");
        String r6 = requestFrom("src/main/webapp/static/js/order.js", "POST", "/api/orders");
        String r7 = requestFrom("src/main/webapp/static/js/order.js", "GET", "{*}/api/orders?size=10");
        String r8 = requestFrom("src/main/webapp/static/js/order.js", "DELETE", "/api/orders/{*}");
        String r9 = requestFrom("src/main/webapp/static/js/order.js", "GET", "/order/save.do");

        run(new HttpMatchLinker());

        assertThat(match(r1).to()).isEqualTo(list);
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r2).to()).isEqualTo(save);
        assertThat(match(r2).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r3).to()).isEqualTo(list);
        assertThat(inferred(match(r3))).isTrue();
        assertThat(match(r3).attrString("strippedPrefix")).isEqualTo("/shop");
        assertThat(match(r4).to()).isEqualTo(apiGet);
        assertThat(match(r4).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r5).to()).isEqualTo(apiPut);
        assertThat(match(r5).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(r6).to()).isEqualTo(apiCreate);
        assertThat(match(r6).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(targets(r7)).containsExactly(apiList);
        assertThat(inferred(match(r7))).isTrue();
        assertThat(match(r7).attrString("strippedPrefix")).isEqualTo("{*}");
        assertThat(unresolved(r8)).as("DELETE 는 GET·PUT 엔드포인트와 맞지 않는다").isTrue();
        assertThat(unresolved(r9)).as("GET 요청은 POST 전용 엔드포인트에 붙지 않는다").isTrue();
    }

    @Test
    @DisplayName("F-012-AC53 컨트롤러가 없는 웹 모듈의 요청이 다른 모듈의 엔드포인트(GET /)와 맞으면 RESOLVED가 아니라 INFERRED + crossModule이다. 모듈을 모르는 파일은 판단하지 않는다")
    void requestFromModuleWithoutControllers() {
        project.file("web-ui/pom.xml", "<project/>");
        project.file("web-admin/pom.xml", "<project/>");
        rescan();
        String root = routedEndpoint("GET", "/", "web-admin/src/main/java/com/a/IndexController.java", "com.a.IndexController", "index");
        String fromUi = requestFrom("web-ui/src/main/webapp/index.html", "GET", "/");
        String fromAdmin = requestFrom("web-admin/src/main/webapp/WEB-INF/jsp/top.jsp", "GET", "/");
        String unknownModule = requestFrom("docs/site/index.html", "GET", "/");

        run(new HttpMatchLinker());

        assertThat(match(fromUi).to()).isEqualTo(root);
        assertThat(inferred(match(fromUi))).isTrue();
        assertThat(match(fromUi).attrBoolean("crossModule")).isTrue();
        assertThat(match(fromAdmin).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(match(fromAdmin).attrs()).doesNotContainKey("crossModule");
        assertThat(match(unknownModule).confidence()).isEqualTo(Confidence.RESOLVED);
    }

    // ------------------------------------------------------------------ web·jsp 3차 수정이 내는 요청(확인)

    private String requestWithApi(String file, String httpMethod, String url, String api) {
        String id = requestFrom(file, httpMethod, url);
        g.putAttrs(id, java.util.Map.of("api", api));
        return id;
    }

    @Test
    @DisplayName("F-012-AC44 clientRoute=true는 api 값(route, navigation)과 상관없이 attr만으로 판정해 맞추지도 미해결로 남기지도 않는다")
    void clientRouteIsJudgedByAttrOnly() {
        endpoint("GET", "/login");
        String route = requestWithApi("front/src/App.jsx", "GET", "/login", "route");
        String navigation = requestWithApi("front/src/App.jsx", "GET", "/login", "navigation");
        String missing = requestWithApi("front/src/App.jsx", "GET", "/no/such/page", "navigation");
        g.putAttrs(route, java.util.Map.of("clientRoute", true));
        g.putAttrs(navigation, java.util.Map.of("clientRoute", true));
        g.putAttrs(missing, java.util.Map.of("clientRoute", true));
        // clientRoute가 없는 navigation 요청(location.href = "/login")은 전처럼 맞춘다
        String plainNavigation = requestWithApi("front/src/App.jsx", "GET", "/login", "navigation");

        run(new HttpMatchLinker());

        assertThat(out(route, EdgeKind.MATCHES)).isEmpty();
        assertThat(out(navigation, EdgeKind.MATCHES)).isEmpty();
        assertThat(out(missing, EdgeKind.MATCHES)).isEmpty();
        assertThat(g.diagnostics()).isEmpty();
        assertThat(match(plainNavigation).confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    @DisplayName("F-012-AC29 api=tag 요청(JSP 커스텀 태그의 URL 속성: 태그 파일이 붙이는 접두를 모른다)도 엔드포인트 경로의 맨 앞 자리표시자를 뗀 나머지와 맞으면 INFERRED + strippedPlaceholder다")
    void tagAttributeRequestMatchesAfterLeadingPlaceholder() {
        String tree = endpoint("ANY", "/${adminPath}/sys/office/treeData");
        endpoint("ANY", "/${adminPath}/sys/user/list");
        String tag = requestWithApi("src/main/webapp/WEB-INF/views/modules/sys/userForm.jsp", "ANY", "/sys/office/treeData", "tag");
        String unknown = requestWithApi("src/main/webapp/WEB-INF/views/modules/sys/userForm.jsp", "ANY", "/sys/nothing/here", "tag");

        run(new HttpMatchLinker());

        assertThat(match(tag).to()).isEqualTo(tree);
        assertThat(inferred(match(tag))).isTrue();
        assertThat(match(tag).attrString("strippedPlaceholder")).isEqualTo("${adminPath}");
        assertThat(unresolved(unknown)).isTrue();
    }

    @Test
    @DisplayName("F-012-AC34 EL 삼항 후보로 온 끝이 /인 경로(/oa/oaNotify/)는 끝 슬래시 규칙으로 /oa/oaNotify에 맞는다(자리표시자를 뗀 경로에도)")
    void trailingSlashCandidateFromElTernary() {
        String plain = endpoint("ANY", "/oa/oaNotify");
        String self = endpoint("ANY", "/oa/oaNotify/self");
        String withPlaceholder = endpoint("ANY", "/${adminPath}/cms/article");
        String r1 = requestFrom("src/main/webapp/WEB-INF/views/modules/oa/oaNotifyList.jsp", "ANY", "/oa/oaNotify/");
        String r2 = requestFrom("src/main/webapp/WEB-INF/views/modules/oa/oaNotifyList.jsp", "ANY", "/oa/oaNotify/self");
        String r3 = requestFrom("src/main/webapp/WEB-INF/views/modules/cms/articleList.jsp", "ANY", "/cms/article/");

        run(new HttpMatchLinker());

        assertThat(targets(r1)).containsExactly(plain);
        assertThat(match(r1).attrBoolean("trailingSlash")).isTrue();
        assertThat(match(r1).confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(targets(r2)).containsExactly(self);
        assertThat(targets(r3)).containsExactly(withPlaceholder);
        assertThat(match(r3).attrBoolean("trailingSlash")).isTrue();
        assertThat(inferred(match(r3))).isTrue();
    }

    @Test
    @DisplayName("F-012-AC16 메서드를 모르는 요청(ANY 하나)이 메서드만 다른 여러 엔드포인트와 맞으면 전부 INFERRED + ambiguous다")
    void anyMethodRequestMatchesAllMethodVariants() {
        String get = endpoint("GET", "/board/list");
        String post = endpoint("POST", "/board/list");
        String onlyGet = endpoint("GET", "/board/view");
        String any = requestFrom("src/main/webapp/WEB-INF/jsp/board.jsp", "ANY", "/board/list");
        String single = requestFrom("src/main/webapp/WEB-INF/jsp/board.jsp", "ANY", "/board/view");

        run(new HttpMatchLinker());

        assertThat(targets(any)).containsExactlyInAnyOrder(get, post);
        assertThat(out(any, EdgeKind.MATCHES)).allMatch(e -> inferred(e) && e.attrBoolean("ambiguous"));
        assertThat(targets(single)).containsExactly(onlyGet);
        assertThat(match(single).attrs()).doesNotContainKey("ambiguous");
    }
}
