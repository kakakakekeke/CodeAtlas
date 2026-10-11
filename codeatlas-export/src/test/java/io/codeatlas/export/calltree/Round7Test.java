package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.common.NodeLabels;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.report.SummaryReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 4차 재검증 후속(5차 수정): 호출자 방향의 모듈 표식, selfSubmit 표기, 엔드포인트의 forward·redirect 호출자, 미해결 집계의 각주, 화면 요청 통계, 데이터 적재 표기. */
class Round7Test {

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

    /** 요청을 만들고 matches(출발은 자리 표시)의 대상·신뢰도·attrs대로 MATCHES를 잇는다. */
    private static String request(GraphFixture f, String url, String file, int line, String page, Edge.Builder matches) {
        String id = f.request("GET", url, file, line, page, null);
        Edge template = matches.build();
        Edge.Builder b = Edge.builder(id, template.to(), EdgeKind.MATCHES);
        template.attrs().forEach(b::attr);
        f.edge(template.confidence() == Confidence.INFERRED ? b.inferred().build() : b.build());
        return id;
    }

    /** 두 웹 모듈(admin-web, front-web)이 같은 경로의 핸들러를 가진 프로젝트. 화면은 WebContent 아래라 경로로는 모듈을 알 수 없다. */
    private static final class TwoModules {
        final GraphFixture f = GraphFixture.create();
        final String adminPage = f.jsp("admin-web/WebContent/list.jsp");
        final String frontPage = f.jsp("front-web/WebContent/list.jsp");
        final String admin = method(f, "admin-web", "com.m.admin.AdminController", "list");
        final String front = method(f, "front-web", "com.m.front.FrontController", "list");
        final String endpoint = f.endpoint("GET", "/user/list", admin);

        TwoModules() {
            f.edge(endpoint, front, EdgeKind.ROUTES_TO);
            request(f, "/user/list", "admin-web/WebContent/list.jsp", 7, adminPage, Edge.builder("x", endpoint, EdgeKind.MATCHES).attr("module", "admin-web"));
            request(f, "/user/list", "front-web/WebContent/list.jsp", 7, frontPage, Edge.builder("x", endpoint, EdgeKind.MATCHES).attr("module", "front-web"));
        }
    }

    @Test
    @DisplayName("F-015-AC81 위 방향: 선언이 여러 모듈에 있는 엔드포인트에서 시작하면 [request] 줄에 그 요청의 MATCHES.module을 (모듈 …)로 붙인다. module이 없는 요청과 선언이 한 모듈뿐인 엔드포인트에는 붙이지 않는다")
    void callersTagRequestModules() {
        TwoModules m = new TwoModules();
        String other = m.f.jsp("etc/WebContent/a.jsp");
        request(m.f, "/user/list", "etc/WebContent/a.jsp", 3, other, Edge.builder("x", m.endpoint, EdgeKind.MATCHES));

        assertThat(callers(m.f.graph(), m.endpoint)).isEqualTo("""
                [endpoint] GET /user/list
                ├─ [request] GET /user/list (모듈 admin-web)
                │  └─ [jsp] admin-web/WebContent/list.jsp
                ├─ [request] GET /user/list
                │  └─ [jsp] etc/WebContent/a.jsp
                └─ [request] GET /user/list (모듈 front-web)
                   └─ [jsp] front-web/WebContent/list.jsp
                """);
        assertThat(callers(m.f.reversedCopy(), m.endpoint)).isEqualTo(callers(m.f.graph(), m.endpoint));
        // 핸들러에서 올라온 엔드포인트는 그 모듈의 요청만 남으므로 표식이 없다(전과 같다)
        assertThat(callers(m.f.graph(), m.admin)).isEqualTo("""
                AdminController#list()
                └─ [endpoint] GET /user/list
                   ├─ [request] GET /user/list
                   │  └─ [jsp] admin-web/WebContent/list.jsp
                   └─ [request] GET /user/list
                      └─ [jsp] etc/WebContent/a.jsp
                """);

        // 선언이 한 모듈뿐인 엔드포인트: module이 적혀 있어도 붙이지 않는다
        GraphFixture single = GraphFixture.create();
        String page = single.jsp("web/WebContent/a.jsp");
        String endpoint = single.endpoint("GET", "/a", method(single, "web", "com.m.AController", "a"));
        request(single, "/a", "web/WebContent/a.jsp", 3, page, Edge.builder("x", endpoint, EdgeKind.MATCHES).attr("module", "web"));
        assertThat(callers(single.graph(), endpoint)).isEqualTo("""
                [endpoint] GET /a
                └─ [request] GET /a
                   └─ [jsp] web/WebContent/a.jsp
                """);
    }

    @Test
    @DisplayName("F-015-AC82 crossModule과 module이 함께 있는 MATCHES: 추론(~)으로 표시하되 module의 핸들러만 따라가고(아래), 그 모듈의 핸들러에서 올라왔을 때만 그 요청을 낸다(위)")
    void crossModuleMatchesWithModuleFollowThatModule() {
        TwoModules m = new TwoModules();
        // 정적 자원 모듈(ui)의 화면: 그 화면을 그리는 핸들러의 모듈이 front-web이라 링커가 crossModule 대응에 module을 적었다
        String ui = m.f.jsp("ui/pages/list.html");
        request(m.f, "/user/list", "ui/pages/list.html", 12, ui,
                Edge.builder("x", m.endpoint, EdgeKind.MATCHES).inferred().attr("crossModule", true).attr("module", "front-web"));

        assertThat(callees(m.f.graph(), ui)).isEqualTo("""
                [jsp] ui/pages/list.html
                └─ [request] GET /user/list
                   └─ ~ [endpoint] GET /user/list
                      └─ FrontController#list()
                """);
        assertThat(callers(m.f.graph(), m.front)).isEqualTo("""
                FrontController#list()
                └─ [endpoint] GET /user/list
                   ├─ [request] GET /user/list
                   │  └─ [jsp] front-web/WebContent/list.jsp
                   └─ ~ [request] GET /user/list
                      └─ [jsp] ui/pages/list.html
                """);
        assertThat(callers(m.f.graph(), m.admin)).doesNotContain("ui/pages/list.html");
        // 엔드포인트에서 시작하면 crossModule 요청에도 모듈을 밝힌다
        assertThat(callers(m.f.graph(), m.endpoint)).contains("~ [request] GET /user/list (모듈 front-web)\n   └─ [jsp] ui/pages/list.html");
    }

    /** action 없는 폼의 요청({@code selfSubmit=true}). */
    private static String selfSubmit(GraphFixture f, String page, String file, int line) {
        String id = "http:request:POST {self}@" + file + ":" + line;
        f.graph().declare(Node.builder(id, NodeKind.HTTP_REQUEST).name("POST {self}").location(SourceLocation.of(file, line))
                .attr("httpMethod", "POST").attr("path", "{self}").attr("url", "").attr("selfSubmit", true).attr("api", "form").build());
        f.edge(Edge.builder(page, id, EdgeKind.REQUESTS).inferred().location(SourceLocation.of(file, line)).build());
        return id;
    }

    private static String unresolvedSelfSubmit(GraphFixture f) {
        String id = "unresolved:http-endpoint:POST (selfSubmit)";
        f.graph().declare(Node.builder(id, NodeKind.UNRESOLVED).name("POST (selfSubmit)").attr("category", "http-endpoint").build());
        return id;
    }

    @Test
    @DisplayName("F-015-AC83 미해결 selfSubmit: 대상을 'POST (selfSubmit)'로 적지 않고 풀어 쓴다. 그 화면을 그리는 엔드포인트의 경로를 알면(RENDERS 역방향) 찾던 URL로 적는다")
    void unresolvedSelfSubmitIsSpelledOut() {
        GraphFixture f = GraphFixture.create();
        String known = f.jsp("src/main/webapp/WEB-INF/jsp/place.jsp");
        String orphan = f.jsp("src/main/webapp/WEB-INF/jsp/fragment.jsp");
        String handler = f.method("com.n.PlaceController", "view");
        f.endpoint("GET", "/place/view.do", handler);
        f.edge(handler, known, EdgeKind.RENDERS);
        String unresolved = unresolvedSelfSubmit(f);
        String fromKnown = selfSubmit(f, known, "src/main/webapp/WEB-INF/jsp/place.jsp", 59);
        String fromOrphan = selfSubmit(f, orphan, "src/main/webapp/WEB-INF/jsp/fragment.jsp", 12);
        for (String request : List.of(fromKnown, fromOrphan)) {
            f.edge(Edge.builder(request, unresolved, EdgeKind.MATCHES).unresolved().build());
        }

        assertThat(callees(f.graph(), known)).isEqualTo("""
                [jsp] src/main/webapp/WEB-INF/jsp/place.jsp
                └─ ~ [request] POST (자기 페이지로 제출)
                   └─ ? [unresolved] POST /place/view.do (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 POST 매핑 없음)
                """);
        assertThat(callees(f.graph(), orphan)).isEqualTo("""
                [jsp] src/main/webapp/WEB-INF/jsp/fragment.jsp
                └─ ~ [request] POST (자기 페이지로 제출)
                   └─ ? [unresolved] POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 찾지 못함)
                """);
        // 요청 문맥이 없는 곳(시작 노드): 두 까닭을 함께 적는다
        assertThat(NodeLabels.label(f.graph(), unresolved))
                .isEqualTo("POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 모르거나 그 경로에 POST 매핑 없음)");
        assertThat(callers(f.graph(), unresolved)).doesNotContain("(selfSubmit)");
        // 리포트의 미해결 참조도 같은 이름이다
        String md = SummaryReport.render(f.graph());
        assertThat(md).doesNotContain("(selfSubmit)")
                .contains("| `[request] POST (자기 페이지로 제출)` | MATCHES | `POST /place/view.do (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 POST 매핑 없음)` |")
                .contains("| `[request] POST (자기 페이지로 제출)` | MATCHES | `POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 찾지 못함)` |");
    }

    @Test
    @DisplayName("F-015-AC84 후보가 여럿인 selfSubmit: 묶음 줄이 요청 줄의 문구를 되풀이하지 않고 '후보 엔드포인트'다. 보통 요청의 묶음 줄은 전처럼 요청 이름이다")
    void ambiguousSelfSubmitGroupIsNotARepeat() {
        GraphFixture f = GraphFixture.create();
        String page = f.jsp("src/main/webapp/confirm.jsp");
        String view = f.endpoint("ANY", "/cns/confirmView.do", f.method("com.n.CnsController", "confirmView"));
        String detail = f.endpoint("POST", "/cns/detail.do", f.method("com.n.CnsController", "detail"));
        String self = selfSubmit(f, page, "src/main/webapp/confirm.jsp", 90);
        String plain = f.request("GET", "/cns/{*}", "src/main/webapp/confirm.jsp", 95, page, null);
        for (String endpoint : List.of(view, detail)) {
            f.edge(Edge.builder(self, endpoint, EdgeKind.MATCHES).inferred().attr("selfSubmit", true).attr("ambiguous", true).build());
            f.edge(Edge.builder(plain, endpoint, EdgeKind.MATCHES).inferred().attr("ambiguous", true).build());
        }

        assertThat(callees(f.graph(), page)).isEqualTo("""
                [jsp] src/main/webapp/confirm.jsp
                ├─ ~ [request] POST (자기 페이지로 제출)
                │  └─ ~ [ambiguous 2] 후보 엔드포인트
                │     ├─ ~ [endpoint] ANY /cns/confirmView.do
                │     │  └─ CnsController#confirmView()
                │     └─ ~ [endpoint] POST /cns/detail.do
                │        └─ CnsController#detail()
                └─ [request] GET /cns/{*}
                   └─ ~ [ambiguous 2] GET /cns/{*}
                      ├─ ~ [endpoint] ANY /cns/confirmView.do [see above]
                      └─ ~ [endpoint] POST /cns/detail.do [see above]
                """);
    }

    @Test
    @DisplayName("F-015-AC85 엔드포인트에서 시작한 위 방향 트리에 그 URL로 넘어오는 forward·redirect 핸들러가 화면 요청과 함께 한 줄씩 나온다(보통의 Java 호출은 아니다)")
    void endpointCallersIncludeForwardingHandlers() {
        GraphFixture f = GraphFixture.create();
        String list = f.method("com.n.BbsController", "list");
        String insert = f.method("com.n.BbsController", "insert");
        String update = f.method("com.n.BbsController", "update");
        String helper = f.method("com.n.BbsController", "helper");
        String endpoint = f.endpoint("ANY", "/bbs/list.do", list);
        f.endpoint("POST", "/bbs/insert.do", insert);
        String page = f.jsp("src/main/webapp/bbs.jsp");
        f.request("GET", "/bbs/list.do", "src/main/webapp/bbs.jsp", 4, page, endpoint);
        f.edge(Edge.builder(insert, list, EdgeKind.CALLS).inferred().attr("callType", "forward").attr("path", "/bbs/list.do")
                .location(SourceLocation.of("src/main/java/com/n/BbsController.java", 40, 9, 40, 30)).build());
        f.edge(Edge.builder(update, list, EdgeKind.CALLS).inferred().attr("callType", "redirect").attr("path", "/bbs/list.do")
                .location(SourceLocation.of("src/main/java/com/n/BbsController.java", 60, 9, 60, 30)).build());
        f.edge(Edge.builder(helper, list, EdgeKind.CALLS).attr("callType", "virtual")
                .location(SourceLocation.of("src/main/java/com/n/BbsController.java", 80, 9, 80, 30)).build());

        assertThat(callers(f.graph(), endpoint)).isEqualTo("""
                [endpoint] ANY /bbs/list.do
                ├─ [request] GET /bbs/list.do
                │  └─ [jsp] src/main/webapp/bbs.jsp
                ├─ ~ [forward] BbsController#insert()
                └─ ~ [redirect] BbsController#update()
                """);
        assertThat(callers(f.reversedCopy(), endpoint)).isEqualTo(callers(f.graph(), endpoint));
        // 핸들러에서 올라온 엔드포인트에는 다시 적지 않는다(핸들러의 호출자에 이미 있다)
        assertThat(callers(f.graph(), list)).isEqualTo("""
                BbsController#list()
                ├─ [endpoint] ANY /bbs/list.do
                │  └─ [request] GET /bbs/list.do
                │     └─ [jsp] src/main/webapp/bbs.jsp
                ├─ BbsController#helper()
                ├─ ~ [forward] BbsController#insert()
                └─ ~ [redirect] BbsController#update()
                """);
    }

    @Test
    @DisplayName("F-015-AC85 핸들러 하나가 여러 엔드포인트를 받으면 forward·redirect는 그 path와 경로가 같은 엔드포인트에만 적고, 어느 것과도 같지 않으면 전부에 적는다")
    void forwardsGoToTheEndpointOfTheirPath() {
        GraphFixture f = GraphFixture.create();
        String list = f.method("com.n.BbsController", "list");
        String a = f.endpoint("ANY", "/bbs/a.do", list);
        String b = f.endpoint("ANY", "/bbs/b.do", list);
        String toA = f.method("com.n.BbsController", "toA");
        String unknown = f.method("com.n.BbsController", "unknown");
        f.edge(Edge.builder(toA, list, EdgeKind.CALLS).inferred().attr("callType", "forward").attr("path", "/bbs/a.do?mode=1").build());
        f.edge(Edge.builder(unknown, list, EdgeKind.CALLS).inferred().attr("callType", "forward").attr("path", "/ctx/bbs/x.do").build());

        assertThat(callers(f.graph(), a)).isEqualTo("""
                [endpoint] ANY /bbs/a.do
                ├─ ~ [forward] BbsController#toA()
                └─ ~ [forward] BbsController#unknown()
                """);
        assertThat(callers(f.graph(), b)).isEqualTo("""
                [endpoint] ANY /bbs/b.do
                └─ ~ [forward] BbsController#unknown()
                """);
    }

    @Test
    @DisplayName("F-015-AC86 report.md: 신뢰도별 엣지 수는 그래프 그대로 세고, '미해결 참조'는 접두 일치로 풀린 것(resolvedBy)을 뺀 수이며 두 수가 다른 까닭을 한 줄로 적는다")
    void reportExplainsResolvedReferences() {
        GraphFixture f = GraphFixture.create();
        String dao = f.method("com.n.LoginDAO", "update");
        String ent = f.statement("LoginUsr", "updateENT", "UPDATE");
        String resolved = "unresolved:mybatis-statement:LoginUsr.update{*}";
        String open = "unresolved:mybatis-statement:LoginUsr.unknown";
        SourceLocation site = SourceLocation.of("src/main/java/com/n/LoginDAO.java", 106, 6, 106, 12);
        for (String id : List.of(resolved, open)) {
            f.graph().declare(Node.builder(id, NodeKind.UNRESOLVED).name(id.substring(id.lastIndexOf(':') + 1)).location(site)
                    .attr("category", "mybatis-statement").build());
        }
        f.edge(Edge.builder(dao, resolved, EdgeKind.EXECUTES).unresolved().location(site).build());
        f.edge(Edge.builder(dao, open, EdgeKind.EXECUTES).unresolved().location(SourceLocation.of("src/main/java/com/n/LoginDAO.java", 110, 6, 110, 12)).build());

        String before = SummaryReport.render(f.graph());
        assertThat(before).contains("| UNRESOLVED | 2 |").contains("## 미해결 참조 (2)").doesNotContain("resolvedBy");

        f.attr(resolved, "resolvedBy", "prefixMatch");
        f.edge(Edge.builder(dao, ent, EdgeKind.EXECUTES).inferred().attr("prefixMatch", true).attr("resolvedFrom", resolved).location(site).build());
        String md = SummaryReport.render(f.graph());
        // 그래프의 수는 그대로다: 종류별 노드, 종류별 엣지, 신뢰도별 엣지
        assertThat(md).contains("| UNRESOLVED | 2 | 0 |").contains("| EXECUTES | 3 | 0 | 1 | 2 |").contains("| UNRESOLVED | 2 |");
        assertThat(md).contains("## 미해결 참조 (1)\n\n해석하지 못한 참조다. 엣지 하나를 한 건으로 센다. "
                + "신뢰도가 `UNRESOLVED`인 엣지 2건 가운데 1건은 같은 지점에서 접두 일치로 풀려(대상 노드의 `resolvedBy`) 여기서 뺐다.\n");
        assertThat(md).contains("| mybatis-statement | 1 |").doesNotContain("LoginUsr.update{*}");

        // 전부 풀렸어도 까닭은 남는다
        f.attr(open, "resolvedBy", "prefixMatch");
        assertThat(SummaryReport.render(f.graph())).contains("## 미해결 참조 (0)\n\n없음. "
                + "신뢰도가 `UNRESOLVED`인 엣지 2건 가운데 2건은 같은 지점에서 접두 일치로 풀려(대상 노드의 `resolvedBy`) 여기서 뺐다.\n");
    }

    @Test
    @DisplayName("F-015-AC87 화면 요청 통계(RequestStats)는 report.md의 '화면 요청' 절과 같은 수다: 서버 요청은 외부 URL·정적 페이지 링크·클라이언트 라우트를 뺀다")
    void requestStatsMatchTheReport() {
        GraphFixture f = GraphFixture.create();
        String page = f.jsp("src/pages/App.jsx");
        String endpoint = f.endpoint("GET", "/api/a", f.method("com.n.AController", "a"));
        f.request("GET", "/api/a", "src/pages/App.jsx", 3, page, endpoint);
        f.request("GET", "/api/b", "src/pages/App.jsx", 5, page, null);
        String external = f.request("GET", "https://example.org/x", "src/pages/App.jsx", 7, page, null);
        f.attr(external, "external", true);
        String route = f.request("GET", "/login", "src/pages/App.jsx", 9, page, null);
        f.attr(route, "clientRoute", true);

        SummaryReport.RequestStats stats = SummaryReport.requestStats(f.graph());
        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.server()).isEqualTo(2);
        assertThat(stats.matched()).isEqualTo(1);
        assertThat(stats.unmatched()).isEqualTo(1);
        assertThat(stats.external()).isEqualTo(1);
        assertThat(stats.clientRoutes()).isEqualTo(1);
        assertThat(SummaryReport.render(f.graph())).contains("## 화면 요청 (4)").contains("서버 요청 2건 중 연결 1(확정 1, 추론 0), 미연결 1.");
    }

    private static String dataLoad(GraphFixture f, String file, int line, String table, int rows, String borrowedFrom) {
        String id = Ids.sqlStatement(file, line);
        Node.Builder b = Node.builder(id, NodeKind.SQL_STATEMENT).name(Ids.remainder(id)).location(SourceLocation.of(file, line))
                .attr("statementType", "INSERT").attr("dataLoad", true).attr("rowCount", rows);
        f.graph().declare(borrowedFrom == null ? b.build() : b.attr("borrowedFrom", borrowedFrom).build());
        f.writes(id, table, "INSERT");
        return id;
    }

    @Test
    @DisplayName("F-015-AC88 다른 방언 파일에서 남긴 데이터 적재 묶음(borrowedFrom)은 report.md의 '데이터 적재 스크립트', 영향도(--include-data-load), 콜트리에 '(다른 방언 파일: mysql)'로 드러난다")
    void borrowedDataLoadsAreMarked() {
        GraphFixture f = GraphFixture.create();
        f.table("T_CODE");
        f.table("T_ONLY_MYSQL");
        dataLoad(f, "db/oracle/data.sql", 1, "T_CODE", 10, null);
        String borrowed = dataLoad(f, "db/mysql/data.sql", 1, "T_ONLY_MYSQL", 3, "mysql");

        String md = SummaryReport.render(f.graph());
        assertThat(md).contains("## 데이터 적재 스크립트").contains("| `db/oracle/data.sql` | 1 | 10 |")
                .contains("| `db/mysql/data.sql` (다른 방언 파일: mysql) | 1 | 3 |")
                .contains("이 가운데 묶음 1개는 고른 방언에 그 테이블의 적재가 없어 다른 방언 파일의 것이다(mysql 1개. 파일 이름 뒤에 `(다른 방언 파일: <방언>)`).\n");
        // 보충한 매퍼 구문의 절에는 넣지 않는다(적재 묶음은 매퍼 구문이 아니다)
        assertThat(md).doesNotContain("## 다른 방언 파일에서 보충한 구문");

        ImpactReport impact = ImpactAnalysis.analyze(f.graph(), "T_ONLY_MYSQL", ImpactAnalysis.DEFAULT_MAX_DEPTH, true);
        assertThat(impact.statements()).extracting(Item::label).containsExactly("db/mysql/data.sql:1 <INSERT> (다른 방언 파일: mysql)");
        ImpactReport plain = ImpactAnalysis.analyze(f.graph(), "T_CODE", ImpactAnalysis.DEFAULT_MAX_DEPTH, true);
        assertThat(plain.statements()).extracting(Item::label).containsExactly("db/oracle/data.sql:1 <INSERT>");

        CallTreeOptions withLoads = CallTreeOptions.builder().direction(CallTreeOptions.Direction.CALLERS).includeDataLoad(true).build();
        assertThat(CallTrees.toText(CallTrees.build(f.graph(), Ids.table("T_ONLY_MYSQL"), withLoads), false)).isEqualTo("""
                [table] T_ONLY_MYSQL
                └─ [sql] db/mysql/data.sql:1 <INSERT> (C, 다른 방언 파일: mysql)
                """);
        assertThat(borrowed).isNotNull();
    }

    @Test
    @DisplayName("F-015-AC89 좁혀진 동적 뷰(RENDERS의 dynamicSegment=true + narrowedBy)는 '후보'라 부르지 않고 좁힌 근거를 적는다(추론 표식 ~는 그대로). narrowedBy가 없는 것만 (동적 뷰 이름 후보)다")
    void narrowedDynamicViewsNameTheirEvidence() {
        GraphFixture f = GraphFixture.create();
        String handler = f.method("com.n.BlogController", "index");
        String byProperty = f.jsp("src/main/webapp/cms/blog/index.jsp");
        String byModule = f.jsp("src/main/webapp/upms/blog/index.jsp");
        String candidate = f.jsp("src/main/webapp/mobile/blog/index.jsp");
        SourceLocation site = SourceLocation.of("src/main/java/com/n/BlogController.java", 30, 16, 30, 16);
        // 동적 자리는 {*}가 아니라 프로퍼티 자리표시자일 수 있다: 글자가 아니라 dynamicSegment로 판단한다
        f.edge(Edge.builder(handler, byProperty, EdgeKind.RENDERS).inferred().attr("dynamicSegment", true).attr("narrowedBy", "property")
                .attr("viewName", "/${app.name}/blog/index.jsp").location(site).build());
        f.edge(Edge.builder(handler, byModule, EdgeKind.RENDERS).inferred().attr("dynamicSegment", true).attr("narrowedBy", "moduleName")
                .attr("viewName", "/${app.name}/blog/index.jsp").location(site).build());
        f.edge(Edge.builder(handler, candidate, EdgeKind.RENDERS).inferred().attr("dynamicSegment", true).attr("candidate", true)
                .attr("viewName", "/${app.name}/blog/index.jsp").location(site).build());

        assertThat(callees(f.graph(), handler)).isEqualTo("""
                BlogController#index()
                ├─ ~ [jsp] src/main/webapp/cms/blog/index.jsp (동적 뷰 이름: 프로퍼티 값으로 정함)
                ├─ ~ [jsp] src/main/webapp/mobile/blog/index.jsp (동적 뷰 이름 후보)
                └─ ~ [jsp] src/main/webapp/upms/blog/index.jsp (동적 뷰 이름: 모듈 이름으로 정함)
                """);
        assertThat(callers(f.graph(), byProperty)).isEqualTo("""
                [jsp] src/main/webapp/cms/blog/index.jsp
                └─ ~ BlogController#index() (동적 뷰 이름: 프로퍼티 값으로 정함)
                """);
        assertThat(callers(f.graph(), byModule)).contains("~ BlogController#index() (동적 뷰 이름: 모듈 이름으로 정함)");
        // 표준 산출물 crud/README.md의 추론 까닭 목록에도 있다
        assertThat(CrudReadme.render(List.of(CrudMatrix.Subject.ENDPOINT), false, 20)).contains("`narrowedBy`").contains("동적 뷰 이름의 후보 `dynamicSegment`");
    }

    @Test
    @DisplayName("F-015-AC88 report.md의 '다른 방언 파일에서 보충한 구문' 절은 매퍼 구문만 싣는다: borrowedFrom이 있는 데이터 적재 묶음은 그 절에 섞지 않고 '데이터 적재 스크립트' 절에 묶음 수와 방언을 적는다")
    void borrowedDataLoadsStayOutOfBorrowedMapperStatements() {
        GraphFixture f = GraphFixture.create();
        f.table("T_CODE");
        f.table("T_A");
        f.table("T_B");
        dataLoad(f, "db/oracle/data.sql", 1, "T_CODE", 10, null);
        dataLoad(f, "db/mysql/data.sql", 1, "T_A", 3, "mysql");
        dataLoad(f, "db/mysql/data.sql", 9, "T_B", 4, "mysql");
        dataLoad(f, "db/hsql/data.sql", 1, "T_B", 1, "hsql");
        String mapped = f.statement("com.n.CodeMapper", "selectPage", "SELECT");
        f.attr(mapped, "borrowedFrom", "mysql");

        String md = SummaryReport.render(f.graph());
        assertThat(md).contains("## 다른 방언 파일에서 보충한 구문 (1)").contains("| `com.n.CodeMapper.selectPage` | mysql |");
        int from = md.indexOf("## 다른 방언 파일에서 보충한 구문");
        assertThat(md.substring(from, md.indexOf("\n## ", from + 1))).doesNotContain("data.sql");
        assertThat(md).contains("이 가운데 묶음 3개는 고른 방언에 그 테이블의 적재가 없어 다른 방언 파일의 것이다(hsql 1개, mysql 2개. 파일 이름 뒤에 `(다른 방언 파일: <방언>)`).\n")
                .contains("| `db/hsql/data.sql` (다른 방언 파일: hsql) | 1 | 1 |")
                .contains("| `db/mysql/data.sql` (다른 방언 파일: mysql) | 2 | 7 |")
                .contains("| `db/oracle/data.sql` | 1 | 10 |");
        // 표가 파일 수 상한(export.report.maxItems)에 잘려도 다른 방언 파일은 이름이 남는다
        String cut = SummaryReport.render(f.graph(), 1);
        assertThat(cut).contains("| `db/hsql/data.sql` (다른 방언 파일: hsql) | 1 | 1 |").doesNotContain("| `db/mysql/data.sql`")
                .contains("외 2개 파일. 표에 없는 다른 방언 파일: `db/mysql/data.sql` (mysql).\n");
    }
}
