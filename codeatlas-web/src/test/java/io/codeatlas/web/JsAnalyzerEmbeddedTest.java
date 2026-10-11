package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsAnalyzerEmbeddedTest {

    /** jsp 모듈 대신 내장 소스와 INCLUDES 엣지를 만드는 가짜 생산자(order 100). */
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

    private static String page(AnalysisContext ctx, String jsp) {
        String pageId = Ids.jspPage(jsp);
        ctx.graph().declare(Node.of(pageId, NodeKind.JSP_PAGE, jsp));
        ctx.graph().declare(Node.builder(Ids.jsModule(jsp), NodeKind.JS_MODULE).attr("inline", true).build());
        ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule(jsp), EdgeKind.CONTAINS));
        return pageId;
    }

    private static void script(AnalysisContext ctx, String jsp, int line, int column, String text) {
        ctx.addEmbedded(EmbeddedSource.js(Ids.jsModule(jsp), text, SourceLocation.of(jsp, line, column, line, column), true));
    }

    @Test
    @DisplayName("F-010-AC13 내장 소스를 주인 모듈 아래에 분석하고 위치를 호스트 파일 기준으로 바꾸며 조각을 하나의 전역 스코프로 본다")
    void embeddedSources() {
        String jsp = "web/list.jsp";
        Analyzer fake = producer(ctx -> {
            page(ctx, jsp);
            script(ctx, jsp, 10, 9, "\nfunction fnSearch(page) {\n  $.post('__JSP_EXPR__/order/close.do'      , {});\n}\n");
            script(ctx, jsp, 20, 9, "\n$(function () { fnSearch(1); });\n"
                    + "function view(id) { location.href = '/order/' + __JSP_EXPR__ + '/view?id=' + id; }\n");
            // 이벤트 핸들러: 위치를 계산할 수 없는 조각
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.JS, Ids.jsModule(jsp), "view(3)",
                    SourceLocation.of(jsp, 30, 20, 30, 27), false, java.util.Map.of("inlineHandler", "true")));
        });
        try (TestProject project = TestProject.create().file(jsp, "<html></html>")) {
            G g = new G(project.run(fake, new JsAnalyzer()));
            String module = mod(jsp);

            assertThat(g.functions(jsp)).containsExactlyInAnyOrder("fnSearch", "view");
            Node search = g.node(fn(jsp, "fnSearch"));
            assertThat(search.location().file()).isEqualTo(jsp);
            assertThat(search.location().startLine()).isEqualTo(11);
            assertThat(search.location().startColumn()).isEqualTo(1);
            assertThat(g.hasEdge(module, EdgeKind.CONTAINS, fn(jsp, "fnSearch"))).isTrue();
            // 주인 모듈은 생산자가 만든 그대로다(속성 유지, FILE→MODULE 엣지를 더하지 않는다)
            assertThat(g.node(module).attrBoolean("inline")).isTrue();
            assertThat(g.graph().incoming(module, EdgeKind.CONTAINS)).hasSize(1);

            Node close = g.request("POST", "/order/close.do");
            assertThat(close.id()).isEqualTo("http:request:POST /order/close.do@web/list.jsp:12");
            assertThat(close.attrBoolean("dynamic")).isFalse();
            assertThat(g.hasEdge(fn(jsp, "fnSearch"), EdgeKind.REQUESTS, close.id())).isTrue();

            Node view = g.request("GET", "/order/{*}/view");
            assertThat(view.id()).isEqualTo("http:request:GET /order/{*}/view@web/list.jsp:22");
            assertThat(view.attrString("query")).isEqualTo("id={*}");
            assertThat(g.hasEdge(fn(jsp, "view"), EdgeKind.REQUESTS, view.id())).isTrue();

            // 둘째 조각의 모듈 수준 콜백이 첫째 조각의 전역 함수를 부른다
            Edge call = g.edge(module, EdgeKind.CALLS, fn(jsp, "fnSearch"));
            assertThat(call.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(call.location().startLine()).isEqualTo(21);
            // 이벤트 핸들러 조각의 호출 위치는 origin 그대로
            Edge handler = g.edge(module, EdgeKind.CALLS, fn(jsp, "view"));
            assertThat(handler.location().startLine()).isEqualTo(30);
            assertThat(handler.location().startColumn()).isEqualTo(20);
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC13 한 조각의 구문 오류나 중복 선언이 다른 조각의 분석을 막지 않는다")
    void brokenBlockIsIsolated() {
        String jsp = "web/a.jsp";
        Analyzer fake = producer(ctx -> {
            page(ctx, jsp);
            script(ctx, jsp, 5, 1, "function a() { b(); }\nvar x = = 1;\n");
            script(ctx, jsp, 9, 1, "function b() {}\nvar mode = 'A';\nvar mode = 'B';\nfunction b() {}\n");
        });
        try (TestProject project = TestProject.create().file(jsp, "<html></html>")) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.functions(jsp)).containsExactlyInAnyOrder("a", "b");
            assertThat(g.hasEdge(fn(jsp, "a"), EdgeKind.CALLS, fn(jsp, "b"))).isTrue();
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).hasSize(1);
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED).get(0).location().file()).isEqualTo(jsp);
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED).get(0).location().startLine()).isEqualTo(6);
        }
    }

    @Test
    @DisplayName("F-010-AC14 JSP가 INCLUDES로 포함한 모듈의 전역을 인라인 스크립트에서 해석하고, 이름이 겹치면 INFERRED + ambiguous로, 함께 싣는 근거가 없는 유일한 정의는 loadUnverified로 남긴다")
    void includedGlobals() {
        String jsp = "web/list.jsp";
        Analyzer fake = producer(ctx -> {
            String pageId = page(ctx, jsp);
            String header = page(ctx, "web/header.jsp");
            // 이 페이지를 include하면서 other.js도 싣는 바깥 페이지
            String main = Ids.jspPage("web/main.jsp");
            ctx.graph().declare(Node.of(main, NodeKind.JSP_PAGE, "web/main.jsp"));
            ctx.graph().addEdge(Edge.of(main, pageId, EdgeKind.INCLUDES));
            ctx.graph().addEdge(Edge.of(main, Ids.jsModule("js/other.js"), EdgeKind.INCLUDES));
            ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule("js/common.js"), EdgeKind.INCLUDES));
            ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule("js/order.js"), EdgeKind.INCLUDES));
            ctx.graph().addEdge(Edge.of(pageId, header, EdgeKind.INCLUDES));
            ctx.graph().addEdge(Edge.of(header, Ids.jsModule("js/hdr.js"), EdgeKind.INCLUDES));
            script(ctx, "web/header.jsp", 3, 1, "var CTX = '__JSP_EXPR__';\nfunction headerFn() {}\n");
            script(ctx, jsp, 10, 1, """
                    $(function () {
                      formatAmt(1);
                      OrderModule.cancel(2);
                      dup();
                      notIncluded();
                      nowhere();
                      browserThing();
                      menu();
                      headerFn();
                      $.get(CTX + API.LIST);
                      $('#b').on('click', OrderModule.cancel);
                    });
                    """);
        });
        try (TestProject project = TestProject.create()
                .file(jsp, "<html></html>")
                .file("web/header.jsp", "<html></html>")
                .file("js/common.js", "function formatAmt(v) {}\nfunction dup() {}\nvar API = { LIST: '/order/list.do' };\n")
                .file("js/order.js", "var OrderModule = (function () { function cancel(id) {} return { cancel: cancel }; })();\nfunction dup() {}\n")
                .file("js/hdr.js", "function menu() {}\n")
                .file("web/main.jsp", "<html></html>")
                .file("js/other.js", "function notIncluded() {}\n")
                .file("js/none.js", "function nowhere() {}\n")) {
            G g = new G(project.run(fake, new JsAnalyzer()));
            String module = mod(jsp);

            assertThat(g.targets(module, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn("js/common.js", "formatAmt"), fn("js/order.js", "OrderModule.cancel"),
                    fn("js/common.js", "dup"), fn("js/order.js", "dup"), fn("js/other.js", "notIncluded"),
                    fn("js/hdr.js", "menu"), fn("web/header.jsp", "headerFn"), fn("js/none.js", "nowhere"));

            Edge unique = g.edge(module, EdgeKind.CALLS, fn("js/common.js", "formatAmt"));
            assertThat(unique.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(unique.attr("ambiguous")).isNull();
            assertThat(unique.location().startLine()).isEqualTo(11);
            assertThat(g.edge(module, EdgeKind.CALLS, fn("js/order.js", "OrderModule.cancel")).confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(g.edge(module, EdgeKind.CALLS, fn("js/hdr.js", "menu")).confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(g.edge(module, EdgeKind.CALLS, fn("web/header.jsp", "headerFn")).confidence()).isEqualTo(Confidence.RESOLVED);

            for (String target : List.of(fn("js/common.js", "dup"), fn("js/order.js", "dup"))) {
                Edge e = g.edge(module, EdgeKind.CALLS, target);
                assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
                assertThat(e.attrBoolean("ambiguous")).isTrue();
            }
            // 이 페이지가 포함하지 않은 스크립트의 전역은, 두 스크립트를 함께 싣는 페이지가 있으면 그것을 근거로 잇는다(F-010-R18)
            Edge global = g.edge(module, EdgeKind.CALLS, fn("js/other.js", "notIncluded"));
            assertThat(global.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(global.attrString("resolution")).isEqualTo("global");
            assertThat(global.attrString("viaPage")).isEqualTo("jsp:page:web/main.jsp");
            assertThat(global.attr("loadUnverified")).isNull();
            assertThat(unique.attr("viaPage")).isNull();
            // 어느 페이지도 함께 싣지 않는 none.js의 nowhere(): 프로젝트에 정의가 하나뿐이라 잇되 근거가 약함을 표시한다(F-010-R34)
            Edge unverified = g.edge(module, EdgeKind.CALLS, fn("js/none.js", "nowhere"));
            assertThat(unverified.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(unverified.attr("loadUnverified")).isEqualTo(true);
            assertThat(unverified.attr("resolution")).isNull();
            assertThat(unverified.attr("viaPage")).isNull();
            // 포함한 JSP의 전역 상수(컨텍스트 경로)와 포함한 스크립트의 객체 상수로 URL을 푼다
            assertThat(g.requestsFrom(module)).containsExactly("GET /order/list.do");
            // 함수 참조(ref)
            assertThat(g.graph().edges(module, EdgeKind.CALLS, fn("js/order.js", "OrderModule.cancel")).stream()
                    .map(e -> e.attrString("callType")).toList()).containsExactlyInAnyOrder("call", "ref");
        }
    }

    @Test
    @DisplayName("F-010-AC15 모듈 문법이 없는 JS 파일끼리의 전역 호출은 두 파일을 함께 싣는 페이지가 있으면 INFERRED(resolution=global, viaPage)로, 없으면 정의가 하나뿐일 때만 loadUnverified로 잇고, ES 모듈의 전역 호출은 잇지 않는다")
    void classicScriptGlobals() {
        Analyzer fake = producer(ctx -> {
            // order.jsp가 common.js와 order.js를 함께 싣는다. orphan.js는 어느 페이지도 싣지 않는다
            String pageId = Ids.jspPage("web/order.jsp");
            ctx.graph().declare(Node.of(pageId, NodeKind.JSP_PAGE, "web/order.jsp"));
            ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule("js/common.js"), EdgeKind.INCLUDES));
            ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule("js/order.js"), EdgeKind.INCLUDES));
        });
        try (TestProject project = TestProject.create()
                .file("web/order.jsp", "<html></html>")
                .file("js/common.js", "function ajaxPost(url) {}\nvar showError = function (xhr) {};\nvar App = App || {};\nApp.msg = { alert: function () {} };\n")
                .file("js/order.js", """
                        var App = App || {};
                        function close(id) {
                          ajaxPost('/x');
                          $.ajax({ url: '/order/close.do', error: showError });
                          App.msg.alert('done');
                        }
                        """)
                .file("js/orphan.js", "function lonely() { ajaxPost('/z'); App.msg.alert('x'); }\n")
                .file("src/esm.js", "export function f() { ajaxPost('/y'); }\n")) {
            G g = new G(project.run(fake, new JsAnalyzer()));
            String close = fn("js/order.js", "close");

            assertThat(g.targets(close, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn("js/common.js", "ajaxPost"), fn("js/common.js", "showError"), fn("js/common.js", "App.msg.alert"));
            Edge call = g.edge(close, EdgeKind.CALLS, fn("js/common.js", "ajaxPost"));
            assertThat(call.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(call.attrString("resolution")).isEqualTo("global");
            assertThat(call.attrString("viaPage")).isEqualTo("jsp:page:web/order.jsp");
            assertThat(call.attrString("callType")).isEqualTo("call");
            assertThat(g.edge(close, EdgeKind.CALLS, fn("js/common.js", "showError")).attrString("callType")).isEqualTo("ref");
            // 함께 싣는 페이지를 모르는 스크립트: 정의가 프로젝트에 하나뿐인 이름만 잇고 근거가 약함을 표시한다(F-010-R34)
            assertThat(g.targets(fn("js/orphan.js", "lonely"), EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn("js/common.js", "ajaxPost"), fn("js/common.js", "App.msg.alert"));
            for (Edge e : g.edges(fn("js/orphan.js", "lonely"), EdgeKind.CALLS)) {
                assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
                assertThat(e.attr("loadUnverified")).isEqualTo(true);
                assertThat(e.attr("resolution")).isNull();
                assertThat(e.attr("viaPage")).isNull();
            }
            assertThat(g.edges(fn("src/esm.js", "f"), EdgeKind.CALLS)).isEmpty();
            assertThat(g.node(mod("js/order.js")).attrString("moduleKind")).isEqualTo("script");
            assertThat(g.node(mod("src/esm.js")).attrString("moduleKind")).isEqualTo("esm");
        }
    }

    @Test
    @DisplayName("F-010-AC26 include된 JSP의 인라인 스크립트와 JS 파일은, 그 JSP를 include하는 페이지가 함께 싣는 스크립트의 전역을 INFERRED + viaPage로 잇는다")
    void coLoadedByOuterPage() {
        Analyzer fake = producer(ctx -> {
            String layout = Ids.jspPage("web/layout.jsp");
            ctx.graph().declare(Node.of(layout, NodeKind.JSP_PAGE, "web/layout.jsp"));
            String menu = page(ctx, "web/menu.jsp");
            ctx.graph().addEdge(Edge.of(layout, menu, EdgeKind.INCLUDES));
            ctx.graph().addEdge(Edge.of(layout, Ids.jsModule("js/common.js"), EdgeKind.INCLUDES));
            ctx.graph().addEdge(Edge.of(layout, Ids.jsModule("js/menu.js"), EdgeKind.INCLUDES));
            // 같은 이름의 전역을 가진 다른 페이지 전용 스크립트: layout.jsp와 함께 실리지 않는다
            String admin = Ids.jspPage("web/admin.jsp");
            ctx.graph().declare(Node.of(admin, NodeKind.JSP_PAGE, "web/admin.jsp"));
            ctx.graph().addEdge(Edge.of(admin, Ids.jsModule("js/admin.js"), EdgeKind.INCLUDES));
            script(ctx, "web/menu.jsp", 5, 1, "function openMenu(id) { ajaxPost(CTX + '/menu/open.do'); toggle(id); }\n");
        });
        try (TestProject project = TestProject.create()
                .file("web/layout.jsp", "<html></html>").file("web/menu.jsp", "<html></html>").file("web/admin.jsp", "<html></html>")
                .file("js/common.js", "var CTX = $('#ctx').val();\nfunction ajaxPost(url) { $.post(url); }\n")
                .file("js/menu.js", "function toggle(id) { openMenu(id); }\n")
                .file("js/admin.js", "function toggle(id) {}\nfunction ajaxPost(url) {}\n")) {
            G g = new G(project.run(fake, new JsAnalyzer()));
            String open = fn("web/menu.jsp", "openMenu");

            assertThat(g.targets(open, EdgeKind.CALLS)).containsExactlyInAnyOrder(fn("js/common.js", "ajaxPost"), fn("js/menu.js", "toggle"));
            for (Edge e : g.edges(open, EdgeKind.CALLS)) {
                assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
                assertThat(e.attrString("resolution")).isEqualTo("global");
                assertThat(e.attrString("viaPage")).isEqualTo("jsp:page:web/layout.jsp");
                assertThat(e.attr("ambiguous")).isNull();
            }
            // JS 파일 → include된 JSP의 인라인 함수도 같은 페이지가 함께 싣는다
            Edge back = g.edge(fn("js/menu.js", "toggle"), EdgeKind.CALLS, open);
            assertThat(back.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(back.attrString("viaPage")).isEqualTo("jsp:page:web/layout.jsp");
            // 함께 실리는 스크립트의 상수로 URL을 풀고(추론), 요청 래퍼도 전파한다
            assertThat(g.requestsFrom(open)).containsExactly("POST /menu/open.do");
            assertThat(g.edge(open, EdgeKind.REQUESTS, g.request("POST", "/menu/open.do").id()).confidence()).isEqualTo(Confidence.INFERRED);
        }
    }

    @Test
    @DisplayName("F-010-AC27 표준 내장·DOM·잘 알려진 라이브러리 전역 이름($, jQuery, _, Date, JSON, document, console, setTimeout 등)과 그 멤버 호출은 같은 페이지가 싣는 스크립트에 같은 이름이 있어도 잇지 않는다")
    void builtinGlobalsAreNotLinked() {
        Analyzer fake = producer(ctx -> {
            String pageId = page(ctx, "web/list.jsp");
            for (String js : List.of("js/proto.js", "js/shim.js", "js/util.js", "js/app.js")) {
                ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule(js), EdgeKind.INCLUDES));
            }
            script(ctx, "web/list.jsp", 3, 1, "function init() { $('#a'); new Date(); JSON.stringify({}); console.log(1); helper(); }\n");
        });
        try (TestProject project = TestProject.create()
                .file("web/list.jsp", "<html></html>")
                .file("js/proto.js", "function $(id) { return document.getElementById(id); }\nfunction _(x) {}\nfunction jQuery(x) {}\n")
                .file("js/shim.js", """
                        function Date() {}
                        Date.now = function () {};
                        var JSON = { stringify: function () {}, parse: function () {} };
                        var console = { log: function () {} };
                        document.getElementById = function (id) {};
                        window.setTimeout = function (f) {};
                        function Promise() {}
                        var Math = { max: function () {} };
                        """)
                .file("js/util.js", "function helper() {}\n")
                .file("js/app.js", """
                        function run() {
                          $('#a'); jQuery('#b'); _(1);
                          var d = new Date(); Date.now();
                          JSON.stringify({}); JSON.parse('1');
                          document.getElementById('a'); window.document.getElementById('b');
                          console.log(1); setTimeout(tick, 1); window.setTimeout(tick);
                          new Promise(tick); Math.max(1, 2);
                          helper();
                        }
                        function tick() {}
                        """)) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.targets(fn("js/app.js", "run"), EdgeKind.CALLS))
                    .containsExactlyInAnyOrder(fn("js/util.js", "helper"), fn("js/app.js", "tick"));
            assertThat(g.targets(fn("web/list.jsp", "init"), EdgeKind.CALLS)).containsExactly(fn("js/util.js", "helper"));
            // 같은 파일에서 선언한 이름은 그 파일 안에서는 그대로 해석한다
            assertThat(g.functions("js/proto.js")).contains("$");
        }
    }

    private static final String EXPR = "__JSP_EXPR__";

    /** jsp가 싣는 치환 목록의 한 건: {@code <offset>,<length>,<원문 길이>:<원문>} (F-011 spec "치환 목록"). */
    private static String sub(int offset, int length, String original) {
        return offset + "," + length + "," + original.length() + ":" + original;
    }

    private static void scriptWithSubstitutions(AnalysisContext ctx, String jsp, int line, String text, String substitutions) {
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.JS, Ids.jsModule(jsp), text, SourceLocation.of(jsp, line, 9, line, 9), true,
                java.util.Map.of("jspSubstitutions", substitutions)));
    }

    @Test
    @DisplayName("F-010-AC21 인라인 스크립트 요청의 url은 치환 목록으로 되살린 JSP 원문이고 내부 자리표시자를 담지 않는다(path는 그대로)")
    void urlIsRestoredFromSubstitutions() {
        String jsp = "web/detail.jsp";
        // 원문: $.getJSON('${ctx}/order/history.do', cb);
        String history = "$.getJSON('" + EXPR + "/order/history.do', cb);\n";
        // 원문: $.post('<c:url value="/order/close.do"/>', {});  → 닫는 따옴표 뒤에 채움 공백 17칸
        String curl = "<c:url value=\"/order/close.do\"/>";
        String close = "$.post('/order/close.do'" + " ".repeat(17) + ", {});\n";
        // 원문: fetch('${pageContext.request.contextPath}/api/orders/' + id + '/items?p=${page}');
        String longEl = "${pageContext.request.contextPath}";
        String items = "fetch('" + EXPR + "/api/orders/'" + " ".repeat(22) + " + id + '/items?p=" + EXPR + "');\n";
        // 원문: $.get(${detailUrl});  → 전부 동적이라 미해결
        String dynamic = "$.get(" + EXPR + ");\n";
        // 원문: $.get('/order/list.do<c:if test="${not empty q}">?q=${q}</c:if>');  → 문자열 안에서 지운 제어 태그는 길이 0인 구간이다
        String cif = "<c:if test=\"${not empty q}\">";
        String conditional = "$.get('/order/list.do?q=" + EXPR + "'" + " ".repeat(cif.length() + 7) + ");\n";
        Analyzer fake = producer(ctx -> {
            page(ctx, jsp);
            scriptWithSubstitutions(ctx, jsp, 10, history, sub(history.indexOf(EXPR), 12, "${ctx}"));
            scriptWithSubstitutions(ctx, jsp, 20, close, sub(8, 15, curl) + sub(24, 17, ""));
            scriptWithSubstitutions(ctx, jsp, 30, items, sub(items.indexOf(EXPR), 12, longEl) + sub(items.indexOf("'  ") + 1, 22, "")
                    + sub(items.lastIndexOf(EXPR), 12, "${page}"));
            scriptWithSubstitutions(ctx, jsp, 40, dynamic, sub(6, 12, "${detailUrl}"));
            int q = conditional.indexOf(EXPR);
            scriptWithSubstitutions(ctx, jsp, 50, conditional, sub(conditional.indexOf("?q="), 0, cif) + sub(q, 12, "${q}")
                    + sub(q + 12, 0, "</c:if>") + sub(q + 13, cif.length() + 7, ""));
        });
        try (TestProject project = TestProject.create().file(jsp, "<html></html>")) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            Node historyRequest = g.request("GET", "/order/history.do");
            assertThat(historyRequest.attrString("url")).isEqualTo("'${ctx}/order/history.do'");
            assertThat(historyRequest.id()).isEqualTo("http:request:GET /order/history.do@web/detail.jsp:10");
            assertThat(g.request("POST", "/order/close.do").attrString("url")).isEqualTo("'" + curl + "'");
            Node itemsRequest = g.request("GET", "/api/orders/{*}/items");
            assertThat(itemsRequest.attrString("url")).isEqualTo("'" + longEl + "/api/orders/' + id + '/items?p=${page}'");
            assertThat(itemsRequest.attrString("query")).isEqualTo("p={*}");
            Node listRequest = g.request("GET", "/order/list.do");
            assertThat(listRequest.attrString("url")).isEqualTo("'/order/list.do" + cif + "?q=${q}</c:if>'");
            assertThat(listRequest.attrString("query")).isEqualTo("q={*}");
            // 미해결 원문에도 자리표시자가 아니라 JSP 원문을 쓴다
            assertThat(g.targets(mod(jsp), EdgeKind.REQUESTS)).contains("unresolved:http-url:${detailUrl}");
            for (Node n : g.graph().nodes()) {
                assertThat(n.id()).doesNotContain(EXPR);
                assertThat(String.valueOf(n.attrs().get("url"))).doesNotContain(EXPR);
            }
        }
    }

    @Test
    @DisplayName("F-010-AC21 치환 목록이 없거나 읽을 수 없으면 url의 자리표시자를 ${…}로 바꿔 내부 토큰이 보이지 않게 한다")
    void urlFallbackWithoutSubstitutions() {
        String jsp = "web/list.jsp";
        Analyzer fake = producer(ctx -> {
            page(ctx, jsp);
            script(ctx, jsp, 10, 9, "$.post('" + EXPR + "/order/close.do', {});\n$.get(" + EXPR + ");\n");
            scriptWithSubstitutions(ctx, jsp, 20, "$.get('" + EXPR + "/order/list.do');\n", "7,12,999:${ctx}");
            scriptWithSubstitutions(ctx, jsp, 30, "$.get('" + EXPR + "/order/view.do');\n", "깨진 값");
        });
        try (TestProject project = TestProject.create()
                .file(jsp, "<html></html>")
                .file("js/plain.js", "function f() { $.get('/plain/' + " + EXPR + "); }\n")) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.request("POST", "/order/close.do").attrString("url")).isEqualTo("'${…}/order/close.do'");
            assertThat(g.request("GET", "/order/list.do").attrString("url")).isEqualTo("'${…}/order/list.do'");
            assertThat(g.request("GET", "/order/view.do").attrString("url")).isEqualTo("'${…}/order/view.do'");
            assertThat(g.targets(mod(jsp), EdgeKind.REQUESTS)).contains("unresolved:http-url:${…}");
            // JS 파일의 원문은 손대지 않는다(그 이름의 변수를 실제로 쓴 것이다)
            assertThat(g.request("GET", "/plain/{*}").attrString("url")).isEqualTo("'/plain/' + " + EXPR);
        }
    }

    @Test
    @DisplayName("F-010-AC21 요청 래퍼 호출 지점의 url과 다른 조각에 있는 옵션 객체의 url도 그 식이 놓인 조각의 원문으로 되살린다")
    void urlRestoredAcrossFragments() {
        String jsp = "web/form.jsp";
        String opts = "var OPTS = { url: '" + EXPR + "/member/search.do', type: 'POST' };\n";
        String calls = "function ajaxGet(url) { $.ajax({ url: url }); }\nfunction go() { ajaxGet('" + EXPR + "/a/b.do'); $.ajax(OPTS); }\n";
        Analyzer fake = producer(ctx -> {
            page(ctx, jsp);
            scriptWithSubstitutions(ctx, jsp, 5, opts, sub(opts.indexOf(EXPR), 12, "${ctx}"));
            scriptWithSubstitutions(ctx, jsp, 9, calls, sub(calls.indexOf(EXPR), 12, "<%=ctx%>"));
        });
        try (TestProject project = TestProject.create().file(jsp, "<html></html>")) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.request("GET", "/a/b.do").attrString("url")).isEqualTo("'<%=ctx%>/a/b.do'");
            assertThat(g.request("POST", "/member/search.do").attrString("url")).isEqualTo("'${ctx}/member/search.do'");
        }
    }
}
