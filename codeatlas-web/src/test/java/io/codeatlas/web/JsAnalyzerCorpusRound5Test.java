package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 4차 수정본을 공개 프로젝트에 다시 돌려 찾은 것(5차): JS 문자열로 만들어 DOM에 넣는 태그의 URL이 요청으로 잡히지 않음,
 * 인라인 스크립트의 쿼리 문자열 안 EL 삼항이 경로가 같은 후보 둘로 갈림.
 */
class JsAnalyzerCorpusRound5Test {

    private static final String WEB = "src/main/webapp";

    private static Analyzer producer(Consumer<AnalysisContext> body) {
        return new Analyzer() {
            @Override
            public String name() {
                return "fake-jsp";
            }

            @Override
            public void analyze(AnalysisContext ctx) {
                body.accept(ctx);
            }
        };
    }

    private static final Pattern EL = Pattern.compile("\\$\\{[^}]*\\}");

    /** JSP에 쓴 스크립트를 jsp 분석기가 넘기는 꼴로 등록한다: EL은 {@code __JSP_EXPR__}로 바꾸고 치환 목록을 싣는다. */
    private static void inlineJsp(AnalysisContext ctx, String jsp, int line, String source) {
        String pageId = Ids.jspPage(jsp);
        if (!ctx.graph().hasNode(pageId)) {
            ctx.graph().declare(Node.of(pageId, NodeKind.JSP_PAGE, jsp));
        }
        if (!ctx.graph().hasNode(Ids.jsModule(jsp))) {
            ctx.graph().declare(Node.builder(Ids.jsModule(jsp), NodeKind.JS_MODULE).attr("inline", true).build());
            ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule(jsp), EdgeKind.CONTAINS));
        }
        StringBuilder text = new StringBuilder();
        StringBuilder substitutions = new StringBuilder();
        Matcher m = EL.matcher(source);
        int position = 0;
        while (m.find()) {
            text.append(source, position, m.start());
            substitutions.append(text.length()).append(',').append("__JSP_EXPR__".length()).append(',')
                    .append(m.group().length()).append(':').append(m.group());
            text.append("__JSP_EXPR__");
            position = m.end();
        }
        text.append(source.substring(position));
        Map<String, String> attrs = substitutions.length() > 0 ? Map.of("jspSubstitutions", substitutions.toString()) : Map.of();
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.JS, Ids.jsModule(jsp), text.toString(),
                SourceLocation.of(jsp, line, 1, line, 1), true, attrs));
    }

    private static Confidence confidence(G g, String from, String method, String path) {
        return g.edge(from, EdgeKind.REQUESTS, g.request(method, path).id()).confidence();
    }

    private static List<String> unresolvedRequests(G g, String from) {
        return g.edges(from, EdgeKind.REQUESTS).stream().map(Edge::to).filter(to -> to.startsWith("unresolved:")).toList();
    }

    @Test
    @DisplayName("F-010-AC59 DOM에 넣는 HTML 문자열(.html()·.append()·innerHTML·document.write·$()·insertAdjacentHTML) 안의 <iframe src>·<a href>·<form action>·<img src>를 요청(api=frame|link|form|img)으로 기록하고, URL을 풀지 못하면 미해결로 남긴다")
    void markupStringRequests() {
        String file = WEB + "/js/pop.js";
        String jsp = WEB + "/WEB-INF/jsp/cmm/uss/umt/EgovUserInsert.jsp";
        try (TestProject project = TestProject.create()
                .file(file, """
                        function fn_zip() {
                          var $dialog = $('<div id="modalPan"></div>')
                            .html('<iframe style="border: 0px; " src="' + "<c:url value='/sym/cmm/EgovCcmZipSearchList.do'/>" +'" width="100%" height="100%"></iframe>')
                            .dialog({ autoOpen: false, modal: true });
                        }
                        function fn_log(logId) {
                          $('#pan').html('<iframe src="' + "<c:url value='/sym/log/InqireLoginLog.do?logId='/>" + logId + '"></iframe>');
                        }
                        function fn_page() {
                          var page = "/sym/prm/EgovProgramListSearchNew.do";
                          $('<div></div>').html('<iframe src="' + page + '" width="100%"></iframe>');
                        }
                        function fn_links(id, url) {
                          document.getElementById('list').innerHTML = "<a href='/board/view.do?id=" + id + "'>보기</a> <a href='#'>x</a> <a href='javascript:fn_zip()'>y</a>";
                          $('#list').append(`<a class="b" href="/board/${id}/edit">수정</a><img src="/images/btn.gif"><img src='/file/download.do?id=${id}'>`);
                          document.write('<form name="f" method="post" action="/board/save.do"><input type="hidden"></form>');
                          var $f = $('<form action="/board/excel.do"></form>');
                          el.insertAdjacentHTML('beforeend', '<a href=/board/more.do>more</a>');
                          $('#list').append('<a href="' + url + '">동적</a>');
                          $('#list').append('<img src="' + url + '">');
                          $('#list').append('<a href="http://example.com/x">ext</a><a href="sub/rel.do">rel</a><a href="">empty</a>');
                        }
                        function notInserted(id) {
                          var s = '<a href="/board/ghost.do?id=' + id + '">x</a>';
                          return s;
                        }
                        function caller() { fn_links(1, '/board/byWrapper.do'); }
                        function viaVariable(id) {
                          var html = '<ul>';
                          html += '<li><a href="/board/item.do?id=' + id + '">항목</a></li>';
                          html += '</ul>';
                          $('#menu').html(html);
                        }
                        function plain() {
                          $('#msg').html('<b>저장했습니다</b> /board/notUrl.do');
                          $('#msg').text('<a href="/board/text.do">x</a>');
                          var imgpath = "/images/menu/";
                          document.write("<img id='icon" + 1 + "' src='" + imgpath + "menu_folder");
                        }
                        function caller2() { fn_links(2, 'home'); }
                        function more(el) {
                          el.innerHTML += '<a href="/board/plus.do">더</a>';
                        }
                        """)) {
            G g = new G(project.run(producer(ctx -> inlineJsp(ctx, jsp, 20, """
                    function fn_id() {
                      var $dialog = $('<div id="modalPan"></div>')
                        .html('<iframe style="border: 0px; " src="' + "${pageContext.request.contextPath}/uss/umt/cmm/EgovIdDplctCnfirmView.do?" + '" width="100%" height="100%"></iframe>')
                        .dialog({ autoOpen: false });
                    }
                    """)), new JsAnalyzer()));

            assertThat(g.requestsFrom(fn(file, "fn_zip"))).containsExactly("GET /sym/cmm/EgovCcmZipSearchList.do");
            Node zip = g.request("GET", "/sym/cmm/EgovCcmZipSearchList.do");
            // 위치는 호출 식의 첫 줄(2행)이 아니라 HTML 문자열 식(3행)이다
            assertThat(zip.id()).isEqualTo("http:request:GET /sym/cmm/EgovCcmZipSearchList.do@" + file + ":3");
            assertThat(zip.attrString("api")).isEqualTo("frame");
            assertThat(zip.attr("dynamic")).isEqualTo(false);
            assertThat(zip.attrString("url")).contains("<c:url value='/sym/cmm/EgovCcmZipSearchList.do'/>");
            assertThat(confidence(g, fn(file, "fn_zip"), "GET", "/sym/cmm/EgovCcmZipSearchList.do")).isEqualTo(Confidence.RESOLVED);
            assertThat(g.requestsFrom(fn(file, "fn_log"))).containsExactly("GET /sym/log/InqireLoginLog.do");
            assertThat(g.request("GET", "/sym/log/InqireLoginLog.do").attrString("query")).isEqualTo("logId={*}");
            // 상수 전파
            assertThat(g.requestsFrom(fn(file, "fn_page"))).containsExactly("GET /sym/prm/EgovProgramListSearchNew.do");
            assertThat(g.request("GET", "/sym/prm/EgovProgramListSearchNew.do").attrString("api")).isEqualTo("frame");

            // #·javascript:·빈 값·정적 자원·외부 URL·상대 경로는 요청이 아니다. <img>의 동적 src는 미해결도 아니다
            assertThat(g.requestsFrom(fn(file, "fn_links"))).containsExactlyInAnyOrder("GET /board/view.do", "GET /board/{*}/edit",
                    "GET /file/download.do", "POST /board/save.do", "GET /board/excel.do", "GET /board/more.do");
            assertThat(g.request("GET", "/board/view.do").attrString("api")).isEqualTo("link");
            assertThat(g.request("GET", "/board/view.do").attrString("query")).isEqualTo("id={*}");
            assertThat(g.request("GET", "/board/{*}/edit").attrString("api")).isEqualTo("link");
            assertThat(g.request("GET", "/file/download.do").attrString("api")).isEqualTo("img");
            assertThat(g.request("POST", "/board/save.do").attrString("api")).isEqualTo("form");
            assertThat(g.request("GET", "/board/excel.do").attrString("api")).isEqualTo("form");
            assertThat(g.request("GET", "/board/more.do").attrString("api")).isEqualTo("link");
            // URL 자리가 매개변수: 그 자리는 미해결이고 호출 지점에 요청이 생긴다(요청 래퍼)
            assertThat(unresolvedRequests(g, fn(file, "fn_links"))).containsExactly("unresolved:http-url:'<a href=\"' + url + '\">동적</a>'");
            assertThat(g.requestsFrom(fn(file, "caller"))).containsExactly("GET /board/byWrapper.do");
            Node wrapped = g.request("GET", "/board/byWrapper.do");
            assertThat(wrapped.attrString("api")).isEqualTo("link");
            assertThat(wrapped.attrString("via")).isEqualTo(fn(file, "fn_links"));
            // DOM에 넣지 않는 문자열, 태그가 없는 문자열, .text()는 읽지 않는다
            assertThat(g.edges(fn(file, "notInserted"), EdgeKind.REQUESTS)).isEmpty();
            assertThat(g.edges(fn(file, "plain"), EdgeKind.REQUESTS)).isEmpty();
            // 변수에 이어 붙여 만든 HTML, innerHTML +=
            assertThat(g.requestsFrom(fn(file, "viaVariable"))).containsExactly("GET /board/item.do");
            assertThat(g.requestsFrom(fn(file, "more"))).containsExactly("GET /board/plus.do");
            // 래퍼의 호출 지점도 서버 경로 꼴일 때만이다(상대 경로 'home'). 끝나지 않은 <img src>는 확장자를 몰라 만들지 않는다
            assertThat(g.edges(fn(file, "caller2"), EdgeKind.REQUESTS)).isEmpty();

            // 인라인 스크립트: 컨텍스트 경로 식을 떼고, url은 JSP에 쓴 원문이다
            assertThat(g.requestsFrom(fn(jsp, "fn_id"))).containsExactly("GET /uss/umt/cmm/EgovIdDplctCnfirmView.do");
            Node id = g.request("GET", "/uss/umt/cmm/EgovIdDplctCnfirmView.do");
            assertThat(id.attrString("api")).isEqualTo("frame");
            assertThat(id.attrString("url")).contains("${pageContext.request.contextPath}/uss/umt/cmm/EgovIdDplctCnfirmView.do?");
            assertThat(id.location().file()).isEqualTo(jsp);
        }
    }

    @Test
    @DisplayName("F-010-AC60 인라인 스크립트 문자열에서 쿼리 값 자리의 EL 삼항은 후보로 펼치지 않는다: 요청 하나(쿼리는 {*})이고 그 때문에 추론이 되지 않는다")
    void elTernaryInQueryIsNotExpanded() {
        String jsp = WEB + "/WEB-INF/tags/sys/ckfinder.tag";
        try (TestProject project = TestProject.create()) {
            G g = new G(project.run(producer(ctx -> inlineJsp(ctx, jsp, 10, """
                    function finderOpen() {
                      var url = "${ctxStatic}/ckfinder/open.do?type=${ctype}&start=" + year +
                        "/&action=js&dts=${type eq 'thumb'?'1':'0'}&sm=${selectMultiple?1:0}";
                      window.open(url);
                    }
                    function byPath() {
                      location.href = "${ctx}/oa/notify/${self ? 'self' : 'all'}/list?own=${self ? '1' : '0'}";
                    }
                    """)), new JsAnalyzer()));

            assertThat(g.requestsFrom(fn(jsp, "finderOpen"))).containsExactly("GET /ckfinder/open.do");
            assertThat(g.request("GET", "/ckfinder/open.do").attrString("query"))
                    .isEqualTo("type={*}&start={*}/&action=js&dts={*}&sm={*}");
            assertThat(confidence(g, fn(jsp, "finderOpen"), "GET", "/ckfinder/open.do")).isEqualTo(Confidence.RESOLVED);
            // 경로의 삼항은 종전대로 후보 둘(추론)이고 쿼리의 삼항은 {*}다
            assertThat(g.requestsFrom(fn(jsp, "byPath"))).containsExactlyInAnyOrder("GET /oa/notify/self/list", "GET /oa/notify/all/list");
            assertThat(g.request("GET", "/oa/notify/self/list").attrString("query")).isEqualTo("own={*}");
            assertThat(confidence(g, fn(jsp, "byPath"), "GET", "/oa/notify/self/list")).isEqualTo(Confidence.INFERRED);
        }
    }
}
