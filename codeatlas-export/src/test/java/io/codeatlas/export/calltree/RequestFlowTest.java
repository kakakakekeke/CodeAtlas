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
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.report.SummaryReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 요청의 경계에 걸친 규칙: 요청으로 진입한 핸들러에만 붙는 {@code [before]}, 다른 요청으로 넘어가는 redirect·forward,
 * 핸들러 없이 화면을 그리는 엔드포인트(뷰 컨트롤러), 엔드포인트 대상이 아닌 클라이언트 라우트.
 */
class RequestFlowTest {

    private static final String CONTROLLER = "com.j.RoleController";
    private static final String FILE = "src/main/java/com/j/RoleController.java";

    @TempDir
    Path dir;

    private static String callees(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callees()), false);
    }

    private static String callers(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callers()), false);
    }

    private static Edge call(GraphFixture f, String from, String to, int line, String callType) {
        Edge.Builder b = Edge.builder(from, to, EdgeKind.CALLS).attr("callType", callType).location(SourceLocation.of(FILE, line));
        if (List.of("modelAttribute", "redirect", "forward").contains(callType)) {
            b.inferred();
        }
        return f.edge(b.build());
    }

    private static List<String> cells(CrudMatrix matrix, String endpointId) {
        for (Row row : matrix.rows()) {
            if (row.id().equals(endpointId)) {
                return row.cells().entrySet().stream().map(e -> Ids.remainder(e.getKey()) + "=" + e.getValue()).sorted().toList();
            }
        }
        return List.of();
    }

    @Test
    @DisplayName("F-015-AC52 [before](callType=modelAttribute)는 요청으로 진입한 핸들러(엔드포인트에서 ROUTES_TO로 도착했거나 트리의 시작 노드) 아래에만 붙는다. 다른 핸들러가 Java 호출로 부른 핸들러 아래에는 없다")
    void beforeOnlyUnderRequestEnteredHandler() {
        GraphFixture f = GraphFixture.create();
        f.table("SYS_ROLE");
        String save = f.method(CONTROLLER, "save");
        String form = f.method(CONTROLLER, "form");
        String get = f.method(CONTROLLER, "get");
        String find = f.statement("com.j.RoleDao", "get", "SELECT");
        f.reads(find, "SYS_ROLE");
        f.edge(get, find, EdgeKind.EXECUTES);
        String saveEndpoint = f.endpoint("POST", "/role/save", save);
        String formEndpoint = f.endpoint("GET", "/role/form", form);
        // 검증 실패 때 save가 form(role, model)을 평범한 Java 호출로 부른다
        call(f, save, form, 30, "virtual");
        call(f, save, get, 90, "modelAttribute");
        call(f, form, get, 90, "modelAttribute");
        GraphModel g = f.graph();

        assertThat(callees(g, saveEndpoint)).isEqualTo("""
                [endpoint] POST /role/save
                └─ RoleController#save()
                   ├─ ~ [before] RoleController#get()
                   │  └─ [sql] RoleDao.get <SELECT>
                   │     └─ [table] SYS_ROLE (R)
                   └─ RoleController#form()
                """);
        // 엔드포인트로 도착한 form과 시작 노드인 form에는 붙는다
        assertThat(callees(g, formEndpoint)).contains("└─ RoleController#form()\n   └─ ~ [before] RoleController#get()");
        assertThat(callees(g, form)).startsWith("RoleController#form()\n└─ ~ [before] RoleController#get()\n");
        // 반복 억제: 요청으로 진입한 form과 Java 호출로 닿은 form은 아래가 달라 따로 펼친다
        f.edge(Edge.builder(saveEndpoint, form, EdgeKind.ROUTES_TO).build());
        assertThat(callees(f.graph(), saveEndpoint)).isEqualTo("""
                [endpoint] POST /role/save
                ├─ RoleController#form()
                │  └─ ~ [before] RoleController#get()
                │     └─ [sql] RoleDao.get <SELECT>
                │        └─ [table] SYS_ROLE (R)
                └─ RoleController#save()
                   ├─ ~ [before] RoleController#get() [see above]
                   └─ RoleController#form()
                """);
    }

    @Test
    @DisplayName("F-015-AC52 callers: @ModelAttribute 메서드의 호출자로 올린 핸들러에서 위로는 엔드포인트로만 올라간다(그 핸들러를 Java 호출로 부른 쪽으로는 가지 않는다)")
    void beforeCallersClimbOnlyToEndpoints() {
        GraphFixture f = GraphFixture.create();
        f.table("SYS_ROLE");
        String save = f.method(CONTROLLER, "save");
        String form = f.method(CONTROLLER, "form");
        String get = f.method(CONTROLLER, "get");
        String helper = f.method("com.j.RoleHelper", "load");
        String find = f.statement("com.j.RoleDao", "get", "SELECT");
        f.reads(find, "SYS_ROLE");
        f.edge(get, find, EdgeKind.EXECUTES);
        String saveEndpoint = f.endpoint("POST", "/role/save", save);
        f.endpoint("GET", "/role/form", form);
        call(f, save, form, 30, "virtual");
        call(f, save, get, 90, "modelAttribute");
        call(f, form, get, 90, "modelAttribute");
        // @ModelAttribute 메서드를 평범하게 부르는 곳은 그대로 호출자다
        f.calls(helper, get);
        GraphModel g = f.graph();

        assertThat(callers(g, get)).isEqualTo("""
                RoleController#get()
                ├─ ~ [before] RoleController#form()
                │  └─ [endpoint] GET /role/form
                ├─ ~ [before] RoleController#save()
                │  └─ [endpoint] POST /role/save
                └─ RoleHelper#load()
                """);
        // 핸들러에서 바로 시작하면 Java 호출자도 나온다
        assertThat(callers(g, form)).contains("RoleController#save()").contains("[endpoint] GET /role/form");
        // 테이블 × 엔드포인트와 영향도도 같다: save는 자기 [before]로만 SYS_ROLE에 닿는다
        CrudMatrix matrix = CrudMatrix.of(g, Subject.ENDPOINT);
        assertThat(cells(matrix, saveEndpoint)).containsExactly("SYS_ROLE=R");
        ImpactReport impact = ImpactAnalysis.analyze(g, "SYS_ROLE");
        assertThat(impact.endpoints()).extracting(Item::label).containsExactly("GET /role/form", "POST /role/save");
        Item saveItem = impact.endpoints().stream().filter(i -> i.label().equals("POST /role/save")).findFirst().orElseThrow();
        assertThat(saveItem.pathLabels()).doesNotContain("RoleController#form()");
    }

    /** save —redirect→ list(TB_A를 읽는다), view —forward→ detail(TB_B를 읽는다). save 자신은 TB_C에 쓴다. */
    private static final class Moves {
        final GraphFixture f = GraphFixture.create();
        final String save = f.method(CONTROLLER, "save");
        final String list = f.method(CONTROLLER, "list");
        final String view = f.method(CONTROLLER, "view");
        final String detail = f.method(CONTROLLER, "detail");
        final String saveEndpoint;
        final String listEndpoint;
        final String viewEndpoint;
        final String detailEndpoint;

        Moves() {
            f.table("TB_A");
            f.table("TB_B");
            f.table("TB_C");
            String selectA = f.statement("com.j.RoleDao", "findList", "SELECT");
            f.reads(selectA, "TB_A");
            String selectB = f.statement("com.j.RoleDao", "get", "SELECT");
            f.reads(selectB, "TB_B");
            String insertC = f.statement("com.j.RoleDao", "insert", "INSERT");
            f.writes(insertC, "TB_C", "INSERT");
            f.edge(Edge.builder(list, selectA, EdgeKind.EXECUTES).location(SourceLocation.of(FILE, 50)).build());
            f.edge(Edge.builder(detail, selectB, EdgeKind.EXECUTES).location(SourceLocation.of(FILE, 70)).build());
            f.edge(Edge.builder(save, insertC, EdgeKind.EXECUTES).location(SourceLocation.of(FILE, 20)).build());
            call(f, save, list, 25, "redirect");
            call(f, view, detail, 60, "forward");
            saveEndpoint = f.endpoint("POST", "/role/save", save);
            listEndpoint = f.endpoint("GET", "/role/list", list);
            viewEndpoint = f.endpoint("GET", "/role/view", view);
            detailEndpoint = f.endpoint("GET", "/role/detail", detail);
        }
    }

    @Test
    @DisplayName("F-015-AC53 redirect·forward로 넘어가는 핸들러는 [redirect]·[forward] 한 줄만 보이고 그 아래는 펼치지 않는다(expandRenderedViews를 켜면 펼친다)")
    void redirectAndForwardTargetsAreLeaves() {
        Moves m = new Moves();
        GraphModel g = m.f.graph();

        assertThat(callees(g, m.saveEndpoint)).isEqualTo("""
                [endpoint] POST /role/save
                └─ RoleController#save()
                   ├─ [sql] RoleDao.insert <INSERT>
                   │  └─ [table] TB_C (C)
                   └─ ~ [redirect] RoleController#list()
                """);
        assertThat(callees(g, m.viewEndpoint)).isEqualTo("""
                [endpoint] GET /role/view
                └─ RoleController#view()
                   └─ ~ [forward] RoleController#detail()
                """);
        CallTree tree = CallTrees.build(g, m.save, CallTreeOptions.callees());
        CallTreeNode redirect = tree.root().children().get(1);
        assertThat(redirect.markers()).containsExactly(Marker.REDIRECT);
        assertThat(redirect.detail()).isNull();
        assertThat(CallTrees.toJson(tree)).contains("\"markers\": [\"redirect\"]");
        assertThat(CallTrees.toMermaid(tree)).contains("-.->|\"redirect\"|");

        CallTreeOptions expanded = CallTreeOptions.builder().expandRenderedViews(true).build();
        assertThat(CallTrees.toText(CallTrees.build(g, m.saveEndpoint, expanded), false)).isEqualTo("""
                [endpoint] POST /role/save
                └─ RoleController#save()
                   ├─ [sql] RoleDao.insert <INSERT>
                   │  └─ [table] TB_C (C)
                   └─ ~ [redirect] RoleController#list()
                      └─ [sql] RoleDao.findList <SELECT>
                         └─ [table] TB_A (R)
                """);
        // 넘어간 쪽에서 시작하면 그대로 펼친다
        assertThat(callees(g, m.list)).contains("[table] TB_A (R)");

        // callers: 넘어오는 쪽 핸들러도 한 줄만(그 위의 엔드포인트는 다른 요청이다). 옵션을 켜면 올라간다
        assertThat(callers(g, m.list)).isEqualTo("""
                RoleController#list()
                ├─ [endpoint] GET /role/list
                └─ ~ [redirect] RoleController#save()
                """);
        assertThat(CallTrees.toText(CallTrees.build(g, m.list,
                CallTreeOptions.builder().direction(CallTreeOptions.Direction.CALLERS).expandRenderedViews(true).build()), false))
                .contains("~ [redirect] RoleController#save()\n   └─ [endpoint] POST /role/save");
    }

    @Test
    @DisplayName("F-015-AC53 테이블 × 엔드포인트와 영향도: redirect 대상의 테이블은 원래 엔드포인트에 넣지 않고(별개의 요청), forward 대상의 테이블은 넣는다(같은 요청)")
    void redirectIsAnotherRequestForwardIsNot() {
        Moves m = new Moves();
        GraphModel g = m.f.graph();

        CrudMatrix matrix = CrudMatrix.of(g, Subject.ENDPOINT);
        assertThat(cells(matrix, m.saveEndpoint)).containsExactly("TB_C=C");
        assertThat(cells(matrix, m.listEndpoint)).containsExactly("TB_A=R");
        // forward는 같은 요청 안의 서버 내부 이동이다: 엣지가 추론이어도 그 길로 닿은 칸에 ?를 붙이지 않는다(F-015-AC63)
        assertThat(cells(matrix, m.viewEndpoint)).containsExactly("TB_B=R");
        assertThat(cells(matrix, m.detailEndpoint)).containsExactly("TB_B=R");

        assertThat(ImpactAnalysis.analyze(g, "TB_A").endpoints()).extracting(Item::label).containsExactly("GET /role/list");
        assertThat(ImpactAnalysis.analyze(g, "TB_A").callers()).extracting(Item::label).doesNotContain("RoleController#save()");
        assertThat(ImpactAnalysis.analyze(g, "TB_B").endpoints()).extracting(Item::label)
                .containsExactly("GET /role/detail", "GET /role/view");
    }

    @Test
    @DisplayName("F-015-AC57 뷰 컨트롤러 엔드포인트(viewController=true: 핸들러 없이 엔드포인트에서 바로 RENDERS)는 콜트리와 엔드포인트 목록에서 [jsp] 줄로 이어진다")
    void viewControllerEndpointRendersPage() throws Exception {
        GraphFixture f = GraphFixture.create();
        String handler = f.method("com.v.HomeController", "home");
        f.endpoint("GET", "/home", handler);
        String login = Ids.httpEndpoint("GET", "/login");
        f.graph().declare(Node.builder(login, NodeKind.HTTP_ENDPOINT).name("GET /login").attr("httpMethod", "GET").attr("path", "/login")
                .attr("viewController", true).location(SourceLocation.of("src/main/webapp/WEB-INF/spring-mvc.xml", 12)).build());
        String page = f.jsp("src/main/webapp/WEB-INF/views/login.jsp");
        f.edge(Edge.builder(login, page, EdgeKind.RENDERS).location(SourceLocation.of("src/main/webapp/WEB-INF/spring-mvc.xml", 12)).build());
        // 그 화면에서 나가는 요청은 기본으로 펼치지 않는다
        f.request("POST", "/home", "src/main/webapp/WEB-INF/views/login.jsp", 8, page, Ids.httpEndpoint("GET", "/home"));
        GraphModel g = f.graph();

        assertThat(callees(g, login)).isEqualTo("""
                [endpoint] GET /login
                └─ [jsp] src/main/webapp/WEB-INF/views/login.jsp
                """);
        assertThat(callers(g, page)).contains("[endpoint] GET /login");

        Path out = dir.resolve("views");
        new StandardExporter().export(g, out, Map.of());
        String md = Files.readString(out.resolve("calltree/endpoints.md"), StandardCharsets.UTF_8);
        assertThat(md).contains("## GET /login\n\n```text\n[endpoint] GET /login  src/main/webapp/WEB-INF/spring-mvc.xml:12\n"
                + "└─ [jsp] src/main/webapp/WEB-INF/views/login.jsp\n```")
                .contains("## GET /home\n");

        // 엔드포인트가 많아 컨트롤러별 파일로 나뉠 때: 핸들러가 없으므로 URL의 첫 조각으로 묶는다
        Path split = dir.resolve("views-split");
        new StandardExporter().export(g, split, Map.of("export.calltree.splitOver", "1"));
        assertThat(Files.readString(split.resolve("calltree/endpoints.md"), StandardCharsets.UTF_8))
                .contains("| `GET /login` | [login](endpoints/login.md) |");
        assertThat(Files.readString(split.resolve("calltree/endpoints/login.md"), StandardCharsets.UTF_8))
                .contains("└─ [jsp] src/main/webapp/WEB-INF/views/login.jsp");
    }

    @Test
    @DisplayName("F-015-AC58 클라이언트 라우트(HTTP_REQUEST의 clientRoute=true)는 [route] <경로>로 나오고, 엔드포인트로 이어지지 않아도 미해결로 표시하거나 세지 않는다. report.md의 화면 요청 통계에서 따로 센다")
    void clientRoutesAreNotUnresolvedRequests() {
        GraphFixture f = GraphFixture.create();
        String handler = f.method("com.v.AuthController", "me");
        String endpoint = f.endpoint("GET", "/api/auth/me", handler);
        f.jsModule("src/pages/Login.jsx");
        String component = f.jsFunction("src/pages/Login.jsx", "Login", 5);
        String matched = f.request("GET", "/api/auth/me", "src/pages/Login.jsx", 9, component, endpoint);
        String missing = f.request("POST", "/api/auth/logout", "src/pages/Login.jsx", 14, component, null);
        f.graph().addUnresolvedEdge(missing, EdgeKind.MATCHES, "http-endpoint", "POST /api/auth/logout", SourceLocation.of("src/pages/Login.jsx", 14));
        // navigate('/login'): 링커가 미해결 MATCHES를 남겼더라도 미해결 요청이 아니다
        String route = f.request("GET", "/login", "src/pages/Login.jsx", 20, component, null);
        f.attr(route, "clientRoute", true).attr(route, "api", "route");
        f.graph().addUnresolvedEdge(route, EdgeKind.MATCHES, "http-endpoint", "GET /login", SourceLocation.of("src/pages/Login.jsx", 20));
        String bare = f.request("GET", "/mypage", "src/pages/Login.jsx", 24, component, null);
        f.attr(bare, "clientRoute", true).attr(bare, "api", "route");
        GraphModel g = f.graph();

        assertThat(callees(g, component)).isEqualTo("""
                [js] Login.jsx#Login
                ├─ [request] GET /api/auth/me
                │  └─ [endpoint] GET /api/auth/me
                │     └─ AuthController#me()
                ├─ [request] POST /api/auth/logout
                │  └─ ? [unresolved] POST /api/auth/logout
                ├─ [route] /login
                └─ [route] /mypage
                """);
        assertThat(matched).isNotNull();

        String md = SummaryReport.render(g);
        assertThat(md).contains("## 미해결 참조 (1)").contains("| http-endpoint | 1 |").doesNotContain("`GET /login`");
        assertThat(md).contains("""
                ## 화면 요청 (4)

                서버 요청 2건 중 연결 1(확정 1, 추론 0), 미연결 1. 엔드포인트 연결률 50%.

                | 구분 | 건수 |
                |---|---|
                | 서버 요청 | 2 |
                | 연결됨(확정) | 1 |
                | 연결됨(추론) | 0 |
                | 미연결 | 1 |
                | 외부 URL | 0 |
                | 정적 페이지 링크 | 0 |
                | 화면 내 이동(클라이언트 라우트) | 2 |

                클라이언트 라우트는 서버 요청이 아니라서 분모에서 뺐다. 미연결 요청은 아래 [미연결 요청](#미연결-요청)에 있다.

                ### 미연결 요청

                | 위치 | 요청 |
                |---|---|
                | `src/pages/Login.jsx:14` | `POST /api/auth/logout` |
                """);
        // 요청이 없는 그래프에는 절이 없다
        assertThat(SummaryReport.render(GraphFixture.create().graph())).doesNotContain("## 화면 요청");
    }

    @Test
    @DisplayName("F-015-AC67 report.md의 화면 요청 통계: 외부 URL·정적 페이지 링크·클라이언트 라우트를 분모에서 빼고, 연결을 확정·추론으로 나눠 적고, 미연결 요청을 위치와 함께 30개까지 싣는다")
    void requestStatsSeparateExternalAndInferred() {
        GraphFixture f = GraphFixture.create();
        String handler = f.method("com.v.AuthController", "me");
        String endpoint = f.endpoint("GET", "/api/auth/me", handler);
        String page = f.jsp("src/main/webapp/login.jsp");
        f.request("GET", "/api/auth/me", "src/main/webapp/login.jsp", 9, page, endpoint);
        // 컨텍스트 경로를 떼고 맞춘 요청(추론), 다른 모듈의 핸들러와 맞춘 요청(crossModule: 추론으로 본다)
        String stripped = f.request("GET", "/ctx/api/auth/me", "src/main/webapp/login.jsp", 12, page, null);
        f.edge(Edge.builder(stripped, endpoint, EdgeKind.MATCHES).inferred().attr("strippedPrefix", "/ctx").build());
        String cross = f.request("GET", "/api/auth/me", "src/main/webapp/login.jsp", 15, page, null);
        f.edge(Edge.builder(cross, endpoint, EdgeKind.MATCHES).attr("crossModule", true).build());
        String missing = f.request("POST", "/api/auth/logout", "src/main/webapp/login.jsp", 18, page, null);
        f.graph().addUnresolvedEdge(missing, EdgeKind.MATCHES, "http-endpoint", "POST /api/auth/logout", SourceLocation.of("src/main/webapp/login.jsp", 18));
        // 프로젝트 밖으로 가는 요청: MATCHES가 없다
        for (int i = 0; i < 2; i++) {
            String external = f.request("GET", "https://kauth.example.com/oauth/authorize", "src/main/webapp/login.jsp", 30 + i, page, null);
            f.attr(external, "external", true);
        }

        // 정적 시안 링크: 프로젝트에 있는 정적 페이지 파일을 가리키는 링크, 정적 페이지에서 나가는 .html 링크(대상이 없어도)
        f.jsp("src/main/webapp/static/docs/index.html");
        f.request("GET", "/docs/index.html", "src/main/webapp/login.jsp", 40, page, null);
        String mockup = f.jsp("ui/mockup/index.html");
        String dead = f.request("GET", "home/answer.html", "ui/mockup/index.html", 7, mockup, null);
        f.graph().addUnresolvedEdge(dead, EdgeKind.MATCHES, "http-endpoint", "GET home/answer.html", SourceLocation.of("ui/mockup/index.html", 7));
        // 서버 페이지에서 나가는, 프로젝트에 없는 .html 경로는 서버 요청으로 남는다(*.html을 서블릿에 매핑한 프로젝트가 있다)
        f.request("GET", "/board/list.html", "src/main/webapp/login.jsp", 44, page, null);

        String md = SummaryReport.render(f.graph());
        assertThat(md).contains("""
                ## 화면 요청 (9)

                서버 요청 5건 중 연결 3(확정 1, 추론 2), 미연결 2. 엔드포인트 연결률 60%.

                | 구분 | 건수 |
                |---|---|
                | 서버 요청 | 5 |
                | 연결됨(확정) | 1 |
                | 연결됨(추론) | 2 |
                | 미연결 | 2 |
                | 외부 URL | 2 |
                | 정적 페이지 링크 | 2 |
                | 화면 내 이동(클라이언트 라우트) | 0 |

                외부 URL, 정적 페이지 링크(프로젝트의 정적 `.html` 파일을 가리키거나 정적 페이지에서 나가는 `.html` 링크)는 서버 요청이 아니라서 분모에서 뺐다. 미연결 요청은 아래 [미연결 요청](#미연결-요청)에 있다.

                ### 미연결 요청

                | 위치 | 요청 |
                |---|---|
                | `src/main/webapp/login.jsp:18` | `POST /api/auth/logout` |
                | `src/main/webapp/login.jsp:44` | `GET /board/list.html` |
                """);
        // 미연결 요청은 30개까지 적고 나머지는 수만 적는다
        for (int i = 0; i < 31; i++) {
            f.request("GET", "/missing/" + i, "src/main/webapp/many.jsp", 100 + i, page, null);
        }
        String many = SummaryReport.render(f.graph());
        assertThat(many).contains("| `src/main/webapp/many.jsp:127` | `GET /missing/27` |\n\n외 3건.").doesNotContain("`GET /missing/28`");
    }

    @Test
    @DisplayName("F-015-AC57 뷰 컨트롤러가 여러 모듈에 선언된 엔드포인트: 요청에서 오면 요청을 낸 화면과 같은 모듈의 화면만, 엔드포인트에서 시작하면 전부 모듈 표시와 함께 보인다")
    void viewControllerPagesPerModule() {
        GraphFixture f = GraphFixture.create();
        String root = Ids.httpEndpoint("GET", "/");
        f.graph().declare(Node.builder(root, NodeKind.HTTP_ENDPOINT).name("GET /").attr("httpMethod", "GET").attr("path", "/")
                .attr("viewController", true).build());
        String adminIndex = f.jsp("web-admin/src/main/webapp/WEB-INF/jsp/index.jsp");
        String frontIndex = f.jsp("web-front/src/main/webapp/WEB-INF/jsp/index.jsp");
        f.edge(root, adminIndex, EdgeKind.RENDERS);
        f.edge(root, frontIndex, EdgeKind.RENDERS);
        String menu = f.jsp("web-admin/src/main/webapp/WEB-INF/jsp/menu.jsp");
        f.request("GET", "/", "web-admin/src/main/webapp/WEB-INF/jsp/menu.jsp", 4, menu, root);
        GraphModel g = f.graph();

        assertThat(callees(g, root)).isEqualTo("""
                [endpoint] GET /
                ├─ [jsp] web-admin/src/main/webapp/WEB-INF/jsp/index.jsp (모듈 web-admin)
                └─ [jsp] web-front/src/main/webapp/WEB-INF/jsp/index.jsp (모듈 web-front)
                """);
        assertThat(callees(g, menu)).isEqualTo("""
                [jsp] web-admin/src/main/webapp/WEB-INF/jsp/menu.jsp
                └─ [request] GET /
                   └─ [endpoint] GET /
                      └─ [jsp] web-admin/src/main/webapp/WEB-INF/jsp/index.jsp
                """);
    }

    @Test
    @DisplayName("F-015-AC59 레이아웃 INCLUDES(via=sitemesh·tiles)와 tiles template으로 가는 RENDERS는 [layout] 한 줄만 보이고 따라가지 않는다(expandLayouts로만 펼친다). callers에서는 '쓰는 화면 n개' 한 줄로 접는다")
    void layoutsAreNotFollowed() {
        GraphFixture f = GraphFixture.create();
        String handler = f.method("com.l.BoardController", "list");
        String endpoint = f.endpoint("GET", "/board/list", handler);
        String logout = f.endpoint("GET", "/logout", f.method("com.l.LoginController", "logout"));
        String decorator = f.jsp("src/main/webapp/WEB-INF/decorators/default.jsp");
        String template = f.jsp("src/main/webapp/WEB-INF/tiles/layout.jsp");
        String header = f.jsp("src/main/webapp/WEB-INF/tiles/header.jsp");
        f.request("GET", "/logout", "src/main/webapp/WEB-INF/decorators/default.jsp", 7, decorator, logout);
        List<String> pages = new java.util.ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            String page = f.jsp("src/main/webapp/WEB-INF/views/" + name + ".jsp");
            pages.add(page);
            f.edge(Edge.builder(page, decorator, EdgeKind.INCLUDES).inferred().attr("via", "sitemesh")
                    .location(SourceLocation.of("src/main/webapp/WEB-INF/decorators.xml", 5)).build());
        }
        String fragment = f.jsp("src/main/webapp/WEB-INF/views/fragment.jsp");
        f.edge(Edge.builder(pages.get(0), fragment, EdgeKind.INCLUDES).location(SourceLocation.of("src/main/webapp/WEB-INF/views/a.jsp", 3)).build());
        f.edge(Edge.builder(template, header, EdgeKind.INCLUDES).inferred().attr("via", "tiles")
                .location(SourceLocation.of("src/main/webapp/WEB-INF/tiles.xml", 9)).build());
        // 핸들러 → tiles 정의: template과 본문으로 RENDERS가 하나씩 나간다
        f.edge(Edge.builder(handler, template, EdgeKind.RENDERS).attr("via", "tiles").attr("definition", "board.list")
                .location(SourceLocation.of("src/main/java/com/l/BoardController.java", 30)).build());
        f.edge(Edge.builder(handler, pages.get(0), EdgeKind.RENDERS).attr("via", "tiles").attr("definition", "board.list")
                .attr("tilesAttribute", true).location(SourceLocation.of("src/main/java/com/l/BoardController.java", 30)).build());
        GraphModel g = f.graph();

        // 화면에서 시작: 데코레이터는 [layout] 한 줄, 보통의 include는 그대로 따라간다
        assertThat(callees(g, pages.get(0))).isEqualTo("""
                [jsp] src/main/webapp/WEB-INF/views/a.jsp
                ├─ [jsp] src/main/webapp/WEB-INF/views/fragment.jsp
                └─ ~ [layout] [jsp] src/main/webapp/WEB-INF/decorators/default.jsp
                """);
        CallTreeOptions layouts = CallTreeOptions.builder().expandLayouts(true).build();
        assertThat(CallTrees.toText(CallTrees.build(g, pages.get(0), layouts), false)).contains("""
                └─ ~ [layout] [jsp] src/main/webapp/WEB-INF/decorators/default.jsp
                   └─ [request] GET /logout
                """);
        // 핸들러: template은 [layout], 본문은 [jsp]. 화면 이동을 펼쳐도(expandRenderedViews) 레이아웃은 펼치지 않는다
        CallTreeOptions views = CallTreeOptions.builder().expandRenderedViews(true).build();
        assertThat(CallTrees.toText(CallTrees.build(g, endpoint, views), false)).isEqualTo("""
                [endpoint] GET /board/list
                └─ BoardController#list()
                   ├─ [layout] [jsp] src/main/webapp/WEB-INF/tiles/layout.jsp
                   └─ [jsp] src/main/webapp/WEB-INF/views/a.jsp
                      ├─ [jsp] src/main/webapp/WEB-INF/views/fragment.jsp
                      └─ ~ [layout] [jsp] src/main/webapp/WEB-INF/decorators/default.jsp
                """);
        CallTree tree = CallTrees.build(g, handler, CallTreeOptions.callees());
        assertThat(tree.root().children().get(0).markers()).containsExactly(Marker.LAYOUT);
        // callers: 데코레이터를 쓰는 화면을 줄줄이 내지 않는다. 위로 올라가다 만난 데코레이터는 [shared] 한 줄이고(F-015-AC71),
        // 데코레이터에서 시작하면 "쓰는 화면 n개" 한 줄이다. 옵션을 켜면 화면마다 나온다
        assertThat(callers(g, logout)).contains("""
                └─ [request] GET /logout
                   └─ [shared] [jsp] src/main/webapp/WEB-INF/decorators/default.jsp (포함하는 화면 3개)
                """);
        assertThat(callers(g, decorator)).isEqualTo("""
                [jsp] src/main/webapp/WEB-INF/decorators/default.jsp
                └─ [layout] src/main/webapp/WEB-INF/decorators/default.jsp를 쓰는 화면 3개
                """);
        assertThat(CallTrees.toText(CallTrees.build(g, logout,
                CallTreeOptions.builder().direction(CallTreeOptions.Direction.CALLERS).expandLayouts(true).build()), false))
                .contains("~ [layout] [jsp] src/main/webapp/WEB-INF/views/b.jsp").doesNotContain("쓰는 화면");
        // 영향도와 엔드포인트 기준 CRUD는 레이아웃을 따라가지 않는다
        f.table("TB_USER");
        String statement = f.statement("com.l.LoginDao", "logout", "UPDATE");
        f.writes(statement, "TB_USER", "UPDATE");
        f.edge(Ids.javaMethod("com.l.LoginController", "logout"), statement, EdgeKind.EXECUTES);
        ImpactReport impact = ImpactAnalysis.analyze(f.graph(), "TB_USER");
        assertThat(impact.screens()).extracting(Item::label).contains("src/main/webapp/WEB-INF/decorators/default.jsp")
                .doesNotContain("src/main/webapp/WEB-INF/views/b.jsp");
    }

    @Test
    @DisplayName("F-015-AC60 한 호출 지점에서 오버로드를 가리지 못해 후보 전부로 이어진 호출(ambiguous=true)은 [ambiguous n] 한 줄로 묶고 그 아래에 후보를 펼친다")
    void ambiguousCallsAreGrouped() {
        GraphFixture f = GraphFixture.create();
        String caller = f.method("com.m.LoginService", "login");
        String one = f.method("com.m.FileScrty", "encrypt", List.of("java.lang.String"), "java.lang.String", "public", "static");
        String two = f.method("com.m.FileScrty", "encrypt", List.of("java.lang.String", "byte[]"), "java.lang.String", "public", "static");
        String other = f.method("com.m.FileScrty", "decode", List.of("java.lang.String"), "java.lang.String", "public", "static");
        String digest = f.method("com.m.Digests", "sha", List.of(), "void", "public");
        f.calls(two, digest);
        SourceLocation site = SourceLocation.of("src/main/java/com/m/LoginService.java", 90, 20, 90, 40);
        for (String target : List.of(one, two)) {
            f.edge(Edge.builder(caller, target, EdgeKind.CALLS).inferred().attr("callType", "static").attr("ambiguous", true).location(site).build());
        }
        // 후보가 하나뿐인(다른 호출 지점의) ambiguous 엣지와 보통의 호출은 그대로다
        f.edge(Edge.builder(caller, other, EdgeKind.CALLS).inferred().attr("callType", "static").attr("ambiguous", true)
                .location(SourceLocation.of("src/main/java/com/m/LoginService.java", 95)).build());
        f.edge(Edge.builder(caller, digest, EdgeKind.CALLS).attr("callType", "virtual")
                .location(SourceLocation.of("src/main/java/com/m/LoginService.java", 80)).build());
        GraphModel g = f.graph();

        assertThat(callees(g, caller)).isEqualTo("""
                LoginService#login()
                ├─ Digests#sha()
                ├─ ~ [ambiguous 2] FileScrty#encrypt
                │  ├─ ~ FileScrty#encrypt(String)
                │  └─ ~ FileScrty#encrypt(String, byte[])
                │     └─ Digests#sha()
                └─ ~ FileScrty#decode(String)
                """);
        CallTree tree = CallTrees.build(g, caller, CallTreeOptions.callees());
        CallTreeNode group = tree.root().children().get(1);
        assertThat(group.kindTag()).isEqualTo("ambiguous 2");
        assertThat(group.children()).extracting(CallTreeNode::id).containsExactly(one, two);
        assertThat(tree.nodeCount()).isEqualTo(7);
        // 테이블 × 엔드포인트와 영향도는 후보 전부를 따른다
        assertThat(callers(g, digest)).contains("FileScrty#encrypt(String, byte[])").contains("LoginService#login()");
    }

    @Test
    @DisplayName("F-015-AC61 그 스크립트를 함께 싣는지 확인하지 못한 JS 호출(loadUnverified=true)은 추론(~)으로, 세부 표시 (로드 미확인)과 함께 나온다")
    void unverifiedScriptLoadIsMarked() {
        GraphFixture f = GraphFixture.create();
        f.jsModule("js/page.js");
        f.jsModule("js/common.js");
        String caller = f.jsFunction("js/page.js", "init", 3);
        String helper = f.jsFunction("js/common.js", "fnSearch", 10);
        f.edge(Edge.builder(caller, helper, EdgeKind.CALLS).attr("callType", "call").attr("loadUnverified", true)
                .location(SourceLocation.of("js/page.js", 5)).build());
        GraphModel g = f.graph();

        assertThat(callees(g, caller)).isEqualTo("""
                [js] page.js#init
                └─ ~ [js] common.js#fnSearch (로드 미확인)
                """);
        assertThat(callers(g, helper)).contains("~ [js] page.js#init (로드 미확인)");
    }
}
