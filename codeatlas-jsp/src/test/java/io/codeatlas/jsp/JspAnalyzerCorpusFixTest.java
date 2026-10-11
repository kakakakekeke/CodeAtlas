package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.TAGLIBS;
import static io.codeatlas.jsp.Fixtures.VIEWS;
import static io.codeatlas.jsp.Fixtures.WEB;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 공개 프로젝트에 돌려 찾은 문제의 재현 테스트: 벤더 폴더의 페이지, 폼 표, Thymeleaf. */
class JspAnalyzerCorpusFixTest {

    private static final String CK_DIALOG = """
            <html><body>
            <a href="/ck/help.html">help</a>
            <form action="/ck/upload" method="post"><input type="submit"></form>
            <script src="dialog.js"></script>
            <script>function ckInit() { location.href = '/ck/init'; }</script>
            <button onclick="ckInit()">x</button>
            </body></html>
            """;

    private static TestProject vendorProject() {
        return TestProject.create()
                .file(WEB + "/static/ckeditor/plugins/link/dialogs/link.html", CK_DIALOG)
                .file(WEB + "/static/ckeditor/_samples/index.html", CK_DIALOG)
                .file(WEB + "/static/bootstrap/2.3.1/docs/index.html", CK_DIALOG)
                .file(WEB + "/static/ckfinder/core/connector/java/connector.jsp", "<a href=\"/ckfinder/x.do\">x</a>\n")
                .file(WEB + "/js/lib/demo/demo.html", CK_DIALOG)
                .file(WEB + "/thirdparty/grid/demo.html", CK_DIALOG)
                .file(WEB + "/help/index.html", "<html><body><a href=\"/help/faq.do\">FAQ</a></body></html>\n")
                // 업무 도메인 이름으로 쓰인 vendor(거래처)·plugin·layer 폴더의 화면은 벤더가 아니다
                .file(VIEWS + "/vendor/list.jsp", TAGLIBS + "<a href=\"${ctx}/vendor/form.do\">거래처 등록</a>\n")
                .file(VIEWS + "/layer/popup.jsp", TAGLIBS + "<a href=\"${ctx}/layer/close.do\">닫기</a>\n")
                .file("src/main/resources/templates/plugin/list.html", "<a href=\"/plugin/list\">플러그인</a>\n")
                .file(VIEWS + "/order/list.jsp", TAGLIBS + "<a href=\"${ctx}/order/form.do\">등록</a>\n");
    }

    @Test
    @DisplayName("F-011-AC17 벤더 폴더의 HTML·JSP는 페이지 노드(skipped=vendor)만 만들고 링크·요청·포함·내장 소스를 만들지 않으며 폴더마다 FILE_SKIPPED(INFO) 1건으로 알린다")
    void vendorPagesAreSkipped() {
        try (TestProject project = vendorProject()) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            for (String path : List.of(WEB + "/static/ckeditor/plugins/link/dialogs/link.html", WEB + "/static/ckeditor/_samples/index.html",
                    WEB + "/static/bootstrap/2.3.1/docs/index.html", WEB + "/static/ckfinder/core/connector/java/connector.jsp",
                    WEB + "/js/lib/demo/demo.html")) {
                Node page = ctx.graph().node(Ids.jspPage(path)).orElseThrow();
                assertThat(page.attrString("skipped")).as(path).isEqualTo("vendor");
                assertThat(page.attrString("pageType")).isEqualTo(path.endsWith(".jsp") ? "jsp" : "html");
                assertThat(ctx.graph().edges(Ids.file(path), EdgeKind.CONTAINS, Ids.jspPage(path))).hasSize(1);
                assertThat(ctx.graph().outgoing(Ids.jspPage(path))).as(path).isEmpty();
            }
            // 벤더가 아닌 페이지는 종전대로다
            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("GET /help/faq.do", "GET /order/form.do",
                    "GET /ck/help.html", "POST /ck/upload", "GET /vendor/form.do", "GET /layer/close.do", "GET /plugin/list");
            assertThat(ctx.graph().node(Ids.jspPage(WEB + "/help/index.html")).orElseThrow().attr("skipped")).isNull();
            assertThat(ctx.embedded(EmbeddedSource.JS)).allMatch(e -> e.ownerNodeId().equals(Ids.jsModule(WEB + "/thirdparty/grid/demo.html")));

            // 개정(4차): 요약 진단에 판정 근거(경로·옵션)가 붙는다(web의 F-010-R40과 같은 꼴)
            List<Diagnostic> skipped = ctx.graph().diagnostics().stream().filter(d -> d.code().equals("FILE_SKIPPED")).toList();
            assertThat(skipped).allMatch(d -> d.severity() == Severity.INFO);
            assertThat(skipped.stream().map(Diagnostic::message).toList()).containsExactlyInAnyOrder(
                    "벤더 페이지 2개 건너뜀(경로): " + WEB + "/static/ckeditor",
                    "벤더 페이지 1개 건너뜀(경로): " + WEB + "/static/bootstrap",
                    "벤더 페이지 1개 건너뜀(경로): " + WEB + "/static/ckfinder",
                    "벤더 페이지 1개 건너뜀(경로): " + WEB + "/js/lib");
        }
    }

    @Test
    @DisplayName("F-011-AC17 web.skipVendor=false면 벤더 폴더의 페이지도 분석하고, web.vendorPatterns의 glob에 맞는 페이지는 건너뛴다")
    void vendorOptions() {
        try (TestProject project = vendorProject()
                .options(AnalysisOptions.builder().extra("web.skipVendor", "false").build())) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).contains("GET /ckfinder/x.do");
            assertThat(ctx.graph().node(Ids.jspPage(WEB + "/static/ckeditor/_samples/index.html")).orElseThrow().attr("skipped")).isNull();
            assertThat(ctx.graph().diagnostics().stream().filter(d -> d.code().equals("FILE_SKIPPED"))).isEmpty();
        }
        try (TestProject project = vendorProject()
                .options(AnalysisOptions.builder().extra("web.vendorPatterns", "**/thirdparty/**,help,!**/ckeditor/_samples/**").build())) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(ctx.graph().node(Ids.jspPage(WEB + "/thirdparty/grid/demo.html")).orElseThrow().attrString("skipped"))
                    .isEqualTo("vendor");
            assertThat(ctx.graph().node(Ids.jspPage(WEB + "/help/index.html")).orElseThrow().attrString("skipped")).isEqualTo("vendor");
            // 개정(4차): 요약 진단에 판정 근거가 붙는다. 옵션 glob으로 건너뛴 것은 "옵션"이다
            assertThat(ctx.graph().diagnostics().stream().filter(d -> d.code().equals("FILE_SKIPPED")).map(Diagnostic::message))
                    .contains("벤더 페이지 1개 건너뜀(옵션): " + WEB + "/help", "벤더 페이지 1개 건너뜀(옵션): " + WEB + "/thirdparty/grid");
            // !로 시작하는 glob은 벤더가 아니라고 못 박는다: ckeditor/_samples의 페이지는 분석한다
            assertThat(ctx.graph().node(Ids.jspPage(WEB + "/static/ckeditor/_samples/index.html")).orElseThrow().attr("skipped")).isNull();
            assertThat(ctx.graph().node(Ids.jspPage(WEB + "/static/ckeditor/plugins/link/dialogs/link.html")).orElseThrow()
                    .attrString("skipped")).isEqualTo("vendor");
            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("GET /order/form.do", "GET /vendor/form.do",
                    "GET /layer/close.do", "GET /plugin/list", "GET /ck/help.html", "POST /ck/upload");
        }
    }

    @Test
    @DisplayName("F-011-AC18 페이지의 폼 표(attrs.formMethods: 이름·#id → 메서드)와 action 없는 POST 폼의 수(selfPostForms)를 페이지 노드에 싣는다")
    void formTable() {
        String list = VIEWS + "/sample/list.jsp";
        String plain = VIEWS + "/sample/plain.jsp";
        try (TestProject project = TestProject.create()
                .file(list, TAGLIBS + """
                        <form:form modelAttribute="sampleVO" id="listForm" name="listForm" method="post">
                          <input type="text" name="searchKeyword"/>
                        </form:form>
                        <form:form modelAttribute="detailVO">
                          <form:input path="id"/>
                        </form:form>
                        <form name="searchForm" action="${ctx}/search.do" method="get"></form>
                        <form id="plain" action="${ctx}/plain.do"></form>
                        <form name="dyn" method="${m}" action="${ctx}/dyn.do"></form>
                        <FORM NAME="upper" METHOD="POST"></FORM>
                        <form name="twice" method="post" action="${ctx}/a.do"></form>
                        <form name="twice" method="get" action="${ctx}/b.do"></form>
                        """)
                .file(plain, TAGLIBS + "<p>폼 없음</p>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            Node page = ctx.graph().node(Ids.jspPage(list)).orElseThrow();
            assertThat(page.attrs().get("formMethods")).isEqualTo(List.of(
                    "listForm=POST", "#listForm=POST", "#detailVO=POST", "searchForm=GET", "#plain=GET", "dyn=ANY", "upper=POST",
                    "twice=ANY"));
            // action이 없어 현재 URL로 가는 POST 폼: listForm, detailVO, upper
            assertThat(page.attrs().get("selfPostForms")).isEqualTo(3);
            // 폼 요소의 요청은 종전대로다(action 없는 폼은 요청을 만들지 않는다)
            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("GET /search.do", "GET /plain.do", "ANY /dyn.do",
                    "POST /a.do", "GET /b.do");
            Node none = ctx.graph().node(Ids.jspPage(plain)).orElseThrow();
            assertThat(none.attrs()).doesNotContainKeys("formMethods", "selfPostForms");
        }
    }

    private static final String TEMPLATES = "src/main/resources/templates";

    @Test
    @DisplayName("F-011-AC19 Thymeleaf의 th:href·th:action·th:src(@{…} 링크 식)를 읽어 요청을 만들고 같은 요소의 원형용 정적 href·action은 무시한다")
    void thymeleafLinks() {
        String page = TEMPLATES + "/news/index.html";
        try (TestProject project = TestProject.create()
                .file(page, """
                        <!DOCTYPE html>
                        <html xmlns:th="http://www.thymeleaf.org">
                        <head>
                          <link rel="stylesheet" href="../css/main.css" th:href="@{/css/main.css}"/>
                          <script src="../js/main.js" th:src="@{${uiPath} + ${appName} + '/js/main.js'}"></script>
                          <script src="../js/absent.js" th:src="@{/js/app.js}"></script>
                        </head>
                        <body>
                        <a class="brand" href="../index.html" th:href="@{/}">홈</a>
                        <a href="article/index.html" th:href="@{'/news/article/' + ${article.articleId}}" th:text="${article.title}">제목</a>
                        <a href="list.html" th:href="@{/news/list(page=${page.number},size=10)}">목록</a>
                        <a href="view.html" th:href="@{/news/{id}/view(id=${a.id},tab='c')}">보기</a>
                        <a href="x.html" th:href="@{|/news/tag/${tag.alias}|}">태그</a>
                        <a href="menu.html" th:href="@{${menu.url}}" th:target="${menu.target}">메뉴</a>
                        <a href="/static/only.do">정적 링크</a>
                        <a th:href="@{http://example.com/ext}">외부</a>
                        <a th:href="${plain.url}">전부 동적</a>
                        <form id="commentForm" action="#" th:action="@{'/news/comment/' + ${article.articleId}}" method="post"></form>
                        <form id="upload" th:action="${policy.action}" method="post"></form>
                        <iframe src="proto.html" th:src="@{/news/frame}"></iframe>
                        <a data-th-href="@{/news/data}">data 접두</a>
                        </body></html>
                        """)
                .file("src/main/resources/static/js/main.js", "function main() {}\n")
                .file("src/main/resources/static/js/app.js", "function app() {}\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder(
                    "GET /", "GET /news/article/{*}", "GET /news/list", "GET /news/{*}/view", "GET /news/tag/{*}", "GET /static/only.do",
                    "GET http://example.com/ext", "POST /news/comment/{*}", "GET /news/frame", "GET /news/data");
            Node article = Fixtures.request(ctx, "GET /news/article/{*}");
            assertThat(article.attrString("url")).isEqualTo("@{'/news/article/' + ${article.articleId}}");
            assertThat(article.attrBoolean("dynamic")).isTrue();
            assertThat(article.attrString("api")).isEqualTo("link");
            assertThat(Fixtures.request(ctx, "GET /news/list").attrString("query")).isEqualTo("page={*}&size=10");
            assertThat(Fixtures.request(ctx, "GET /news/{*}/view").attrString("query")).isEqualTo("tab=c");
            assertThat(Fixtures.request(ctx, "GET http://example.com/ext").attrBoolean("external")).isTrue();
            assertThat(Fixtures.request(ctx, "POST /news/comment/{*}").attrString("api")).isEqualTo("form");
            // 전부 동적인 링크 식은 미해결로 남는다(원문은 th: 속성 값)
            assertThat(ctx.graph().outgoing(Ids.jspPage(page), EdgeKind.REQUESTS).stream().map(Edge::to)
                    .filter(to -> to.startsWith("unresolved:")).toList()).containsExactlyInAnyOrder(
                            "unresolved:http-url:@{${menu.url}}", "unresolved:http-url:${plain.url}", "unresolved:http-url:${policy.action}");
            // th:src: 정적이면 그 경로로, 접두가 동적이면 원형 src가 가리키는 파일이 같은 꼬리일 때만 추론으로 잇는다
            assertThat(Fixtures.include(ctx, page, Ids.jsModule("src/main/resources/static/js/app.js")).confidence())
                    .isEqualTo(Confidence.INFERRED);
            assertThat(Fixtures.includes(ctx, page).stream().map(Edge::to).toList())
                    .containsExactly(Ids.jsModule("src/main/resources/static/js/app.js"));
            assertThat(ctx.graph().node(Ids.jspPage(page)).orElseThrow().attrs().get("externalScripts"))
                    .isEqualTo(List.of("@{${uiPath} + ${appName} + '/js/main.js'}"));
        }
    }

    @Test
    @DisplayName("F-011-AC19 th:src의 접두가 동적이어도 원형 src가 가리키는 파일의 경로가 같은 꼬리로 끝나면 INCLUDES(INFERRED)로 잇는다")
    void thymeleafScriptPrototype() {
        String page = "ui/cms-web/blog/index.html";
        try (TestProject project = TestProject.create()
                .file(page, """
                        <html><head>
                        <script src="../js/main.js" th:src="@{${uiPath} + ${appName} + '/js/main.js'}"></script>
                        <script src="../js/other.js" th:src="@{${uiPath} + '/js/renamed.js'}"></script>
                        </head></html>
                        """)
                .file("ui/cms-web/js/main.js", "function main() {}\n")
                .file("ui/cms-web/js/other.js", "function other() {}\n")
                .file("ui/admin-web/js/main.js", "function main() {}\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            Edge edge = Fixtures.include(ctx, page, Ids.jsModule("ui/cms-web/js/main.js"));
            assertThat(edge.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(edge.attrString("via")).isEqualTo("script");
            assertThat(Fixtures.includes(ctx, page)).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-011-AC20 th:replace·th:insert·th:include의 조각 식(템플릿 :: 조각)을 templates 폴더 기준 .html 파일로 풀어 INCLUDES로 잇는다")
    void thymeleafFragments() {
        String page = TEMPLATES + "/order/list.html";
        try (TestProject project = TestProject.create()
                .file(page, """
                        <html xmlns:th="http://www.thymeleaf.org">
                        <head th:replace="fragments/head :: common('주문')"></head>
                        <body>
                        <div th:insert="~{fragments/nav :: menu}"></div>
                        <div th:include="fragments/footer"></div>
                        <th:block th:replace="~{/layout/side.html :: side}"/>
                        <div th:replace=":: local"></div>
                        <div th:insert="this :: local"></div>
                        <div th:replace="${dynamicView} :: body"></div>
                        <div th:replace="fragments/missing :: x"></div>
                        <div th:replace="fragments/nav :: menu"></div>
                        <div th:fragment="local">x</div>
                        </body></html>
                        """)
                .file(TEMPLATES + "/fragments/head.html", "<head th:fragment=\"common(title)\"></head>\n")
                .file(TEMPLATES + "/fragments/nav.html", "<nav th:fragment=\"menu\"><a th:href=\"@{/order/list}\">주문</a></nav>\n")
                .file(TEMPLATES + "/fragments/footer.html", "<footer>f</footer>\n")
                .file(TEMPLATES + "/layout/side.html", "<aside th:fragment=\"side\"></aside>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<Edge> includes = Fixtures.includes(ctx, page);
            assertThat(includes.stream().filter(e -> e.confidence() == Confidence.RESOLVED).map(Edge::to).toList())
                    .containsExactlyInAnyOrder(
                            Ids.jspPage(TEMPLATES + "/fragments/head.html"), Ids.jspPage(TEMPLATES + "/fragments/nav.html"),
                            Ids.jspPage(TEMPLATES + "/fragments/footer.html"), Ids.jspPage(TEMPLATES + "/layout/side.html"));
            Edge head = Fixtures.include(ctx, page, Ids.jspPage(TEMPLATES + "/fragments/head.html"));
            assertThat(head.attrString("via")).isEqualTo("thymeleaf");
            assertThat(head.attrString("path")).isEqualTo("fragments/head :: common('주문')");
            assertThat(head.location().startLine()).isEqualTo(2);
            assertThat(includes.stream().filter(e -> e.confidence() == Confidence.UNRESOLVED).map(Edge::to).toList())
                    .containsExactlyInAnyOrder("unresolved:jsp-include:${dynamicView} :: body",
                            "unresolved:jsp-include:" + TEMPLATES + "/fragments/missing.html");
            // 조각 파일 안의 링크도 그 파일의 요청으로 잡힌다
            assertThat(Fixtures.requestKeys(ctx)).containsExactly("GET /order/list");
        }
    }
}
