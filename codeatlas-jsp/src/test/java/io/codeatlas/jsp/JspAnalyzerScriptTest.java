package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.TAGLIBS;
import static io.codeatlas.jsp.Fixtures.VIEWS;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JspAnalyzerScriptTest {

    private static final String LIST = VIEWS + "/order/list.jsp";

    private static List<EmbeddedSource> scripts(AnalysisContext ctx) {
        return ctx.embedded(EmbeddedSource.JS).stream().filter(e -> !e.attrs().containsKey("inlineHandler")).toList();
    }

    private static List<EmbeddedSource> handlers(AnalysisContext ctx) {
        return ctx.embedded(EmbeddedSource.JS).stream().filter(e -> "true".equals(e.attrs().get("inlineHandler"))).toList();
    }

    @Test
    @DisplayName("F-011-AC10 인라인 스크립트를 js 내장 소스로 넘기고 주인 JS_MODULE을 만든다")
    void inlineScripts() {
        String page = "<html><head>\n"
                + "<script src=\"/resources/js/common.js\"></script>\n"
                + "<script type=\"text/javascript\">\n"
                + "  function fn_search(pageNo) {\n"
                + "    document.searchForm.pageNo.value = pageNo;\n"
                + "    document.searchForm.submit();\n"
                + "  }\n"
                + "</script>\n"
                + "<script type=\"text/template\" id=\"rowTemplate\"><tr><td>{{name}}</td></tr></script>\n"
                + "<script type=\"application/json\">{\"a\": 1}</script>\n"
                + "</head><body>\n"
                + "<p>본문</p><script>$(function () { fn_search(1); });</script>\n"
                + "<script>   </script>\n"
                + "</body></html>\n";
        try (TestProject project = TestProject.create().file(LIST, page)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<EmbeddedSource> embedded = ctx.embedded(EmbeddedSource.JS);
            assertThat(embedded).hasSize(2);
            EmbeddedSource first = embedded.get(0);
            assertThat(first.language()).isEqualTo("js");
            assertThat(first.ownerNodeId()).isEqualTo(Ids.jsModule(LIST));
            assertThat(first.exactPositions()).isTrue();
            assertThat(first.text()).isEqualTo("\n  function fn_search(pageNo) {\n"
                    + "    document.searchForm.pageNo.value = pageNo;\n"
                    + "    document.searchForm.submit();\n"
                    + "  }\n");
            assertThat(first.origin().file()).isEqualTo(LIST);
            assertThat(first.origin().startLine()).isEqualTo(3);
            assertThat(first.origin().startColumn()).isEqualTo(32);
            // 본문 2번째 줄의 function 키워드: 호스트 파일 4번째 줄 3열
            assertThat(first.locationOf(2, 3, 2, 11)).isEqualTo(SourceLocation.of(LIST, 4, 3, 4, 11));

            EmbeddedSource second = embedded.get(1);
            assertThat(second.text()).isEqualTo("$(function () { fn_search(1); });");
            assertThat(second.origin().startLine()).isEqualTo(12);
            assertThat(second.origin().startColumn()).isEqualTo(18);

            Node module = ctx.graph().node(Ids.jsModule(LIST)).orElseThrow();
            assertThat(module.kind()).isEqualTo(NodeKind.JS_MODULE);
            assertThat(module.declared()).isTrue();
            assertThat(module.attrBoolean("inline")).isTrue();
            assertThat(ctx.graph().edges(Ids.jspPage(LIST), EdgeKind.CONTAINS, Ids.jsModule(LIST))).hasSize(1);
            assertThat(ctx.graph().nodes(NodeKind.JS_FUNCTION)).as("JS는 파싱하지 않는다").isEmpty();
        }
    }

    @Test
    @DisplayName("F-011-AC11 스크립트 안의 JSP 요소를 치환하고 줄 수를 보존한다")
    void jspElementsInScript() {
        String script = "\n"
                + "  var CTX = '${pageContext.request.contextPath}';\n"
                + "  var totalCount = <%= totalCount %>;\n"
                + "  <c:if test=\"${not empty message}\">\n"
                + "  alert('<c:out value=\"${message}\"/>');\n"
                + "  </c:if>\n"
                + "  function fn_search(pageNo) {\n"
                + "    $.ajax({\n"
                + "      url: '<c:url value=\"/order/listAjax.do\"/>',\n"
                + "      type: 'POST',\n"
                + "      data: { pageNo: pageNo, memberNm: '${cond.memberNm}' },\n"
                + "      success: function (res) { fn_render(res); }\n"
                + "    });\n"
                + "  }\n"
                + "  function fn_detail(orderId) {\n"
                + "    location.href = '${ctx}/order/detail.do?orderId=' + orderId;\n"
                + "  }\n"
                + "  function fn_excel() { location.href = '${excelUrl}'; }\n"
                + "  <% if (isAdmin) { %>\n"
                + "  function fn_delete(id) { $.post('${ctx}/order/delete.do', { orderId: id }); }\n"
                + "  <% } %>\n";
        String page = TAGLIBS + "<c:url value=\"/order/excel.do\" var=\"excelUrl\"/>\n<script>" + script + "</script>\n";
        try (TestProject project = TestProject.create().file(LIST, page)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<EmbeddedSource> embedded = scripts(ctx);
            assertThat(embedded).hasSize(1);
            String text = embedded.get(0).text();
            String[] lines = text.split("\n", -1);
            String[] original = script.split("\n", -1);

            assertThat(lines).hasSameSizeAs(original);
            assertThat(text).doesNotContain("${").doesNotContain("<%").doesNotContain("<c:");
            assertThat(lines[1]).startsWith("  var CTX = '__JSP_EXPR__'").endsWith(";").hasSameSizeAs(original[1]);
            assertThat(lines[2]).startsWith("  var totalCount = __JSP_EXPR__").endsWith(";").hasSameSizeAs(original[2]);
            assertThat(lines[3]).isBlank().hasSameSizeAs(original[3]);
            assertThat(lines[4]).startsWith("  alert('__JSP_EXPR__'").endsWith(");").hasSameSizeAs(original[4]);
            assertThat(lines[5]).isBlank();
            assertThat(lines[6]).isEqualTo(original[6]);
            assertThat(lines[8]).startsWith("      url: '/order/listAjax.do'").endsWith(",").hasSameSizeAs(original[8]);
            assertThat(lines[10]).isEqualTo("      data: { pageNo: pageNo, memberNm: '__JSP_EXPR__'     },");
            assertThat(lines[15]).isEqualTo("    location.href = '__JSP_EXPR__/order/detail.do?orderId=' + orderId;");
            assertThat(lines[17]).startsWith("  function fn_excel() { location.href = '/order/excel.do'; }");
            assertThat(lines[18]).isBlank();
            assertThat(lines[19]).isEqualTo("  function fn_delete(id) { $.post('__JSP_EXPR__/order/delete.do', { orderId: id }); }");
            assertThat(lines[20]).isBlank();
            assertThat(embedded.get(0).exactPositions()).isTrue();
            assertThat(embedded.get(0).origin().startLine()).isEqualTo(8);
            assertThat(ctx.graph().node(Ids.jspPage(LIST)).orElseThrow().attrInt("scriptletCount", 0)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("F-011-AC11 스크립트 안의 include도 INCLUDES로 잡는다")
    void includeInsideScript() {
        try (TestProject project = TestProject.create()
                .file(LIST, "<script>\n<%@ include file=\"/WEB-INF/views/common/script.jspf\" %>\nvar a = 1;\n</script>\n")
                .file(VIEWS + "/common/script.jspf", "function fn_common() {}\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.include(ctx, LIST, Ids.jspPage(VIEWS + "/common/script.jspf")).location().startLine())
                    .isEqualTo(2);
            assertThat(scripts(ctx).get(0).text()).contains("var a = 1;").doesNotContain("include");
        }
    }

    @Test
    @DisplayName("F-011-AC12 이벤트 핸들러와 javascript: URL을 내장 소스로 넘기고 같은 값은 한 번만 넘긴다")
    void inlineHandlers() {
        String page = "<table>\n"
                + "<tr><td><a href=\"#\" onclick=\"fn_search(1)\">1</a></td></tr>\n"
                + "<tr><td><a href=\"#\" onclick=\"fn_search(1)\">처음</a></td></tr>\n"
                + "<tr><td><select name=\"status\" onChange='fn_change(this)'></select></td></tr>\n"
                + "<tr><td><a href=\"javascript:fn_detail('${o.orderId}')\">상세</a></td></tr>\n"
                + "<tr><td><a href=\"javascript:void(0)\" onclick=\"return false;\">없음</a></td></tr>\n"
                + "<tr><td><button onclick=\"javascript:location.href='<c:url value=\"/order/form.do\"/>'\">등록</button></td></tr>\n"
                + "<tr><td><input onkeydown=\"if (event.keyCode == 13 &amp;&amp; ok) fn_search(1);\"></td></tr>\n"
                + "</table>\n";
        try (TestProject project = TestProject.create().file(LIST, page)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<EmbeddedSource> handlers = handlers(ctx);
            assertThat(handlers).extracting(EmbeddedSource::text).extracting(String::trim).containsExactly(
                    "fn_search(1)",
                    "fn_change(this)",
                    "fn_detail('__JSP_EXPR__')",
                    "location.href='/order/form.do'",
                    "if (event.keyCode == 13 && ok) fn_search(1);");
            assertThat(handlers).allMatch(h -> h.ownerNodeId().equals(Ids.jsModule(LIST)));
            assertThat(scripts(ctx)).isEmpty();

            EmbeddedSource search = handlers.get(0);
            assertThat(search.origin()).isEqualTo(SourceLocation.of(LIST, 2, 30, 2, 42));
            assertThat(search.exactPositions()).isTrue();
            assertThat(search.attrs()).containsEntry("attribute", "onclick");
            assertThat(handlers.get(1).attrs()).containsEntry("attribute", "onchange");
            EmbeddedSource detail = handlers.get(2);
            assertThat(detail.attrs()).containsEntry("attribute", "href");
            assertThat(detail.origin().startLine()).isEqualTo(5);
            assertThat(detail.origin().startColumn()).as("javascript: 다음부터").isEqualTo(29);
            assertThat(handlers.get(4).exactPositions()).as("엔티티를 푼 값은 위치가 정확하지 않다").isFalse();

            assertThat(ctx.graph().hasNode(Ids.jsModule(LIST))).isTrue();
            assertThat(ctx.graph().edges(Ids.jspPage(LIST), EdgeKind.CONTAINS, Ids.jsModule(LIST))).hasSize(1);
            assertThat(ctx.graph().nodes(NodeKind.HTTP_REQUEST)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-011-AC16 치환한 스크립트와 핸들러는 치환 목록을 attrs.jspSubstitutions로 넘기고(오프셋은 넘긴 본문 기준), 치환이 없으면 넘기지 않는다")
    void substitutionAttr() {
        String page = TAGLIBS + "<script>\n"
                + "  $.getJSON('${ctx}/order/history.do', function (rows) {});\n"
                + "</script>\n"
                + "<script>var plain = 1;</script>\n"
                + "<a href=\"javascript:fn_detail('${o.orderId}')\">상세</a>\n"
                + "<a href=\"#\" onclick=\"fn_search(1)\">1</a>\n"
                + "<input onkeydown=\"if (a &amp;&amp; ${ok}) fn_go('${ctx}/x.do');\">\n";
        try (TestProject project = TestProject.create().file(LIST, page)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<EmbeddedSource> scripts = scripts(ctx);
            assertThat(scripts).hasSize(2);
            // 본문은 "\n  $.getJSON('" 다음(오프셋 14)에 ${ctx}가 있다
            assertThat(scripts.get(0).text()).startsWith("\n  $.getJSON('__JSP_EXPR__/order/history.do'");
            assertThat(scripts.get(0).attrs()).containsEntry("jspSubstitutions", "14,12,6:${ctx}");
            assertThat(scripts.get(1).attrs()).doesNotContainKey("jspSubstitutions");

            List<EmbeddedSource> handlers = handlers(ctx);
            assertThat(handlers).hasSize(3);
            assertThat(handlers.get(0).text()).isEqualTo("fn_detail('__JSP_EXPR__')");
            assertThat(handlers.get(0).attrs()).containsEntry("jspSubstitutions", "11,12,12:${o.orderId}");
            assertThat(handlers.get(1).attrs()).doesNotContainKey("jspSubstitutions");
            // 엔티티를 푼 핸들러: 오프셋은 엔티티를 푼 뒤의 본문 기준이다
            assertThat(handlers.get(2).exactPositions()).isFalse();
            assertThat(handlers.get(2).text()).isEqualTo("if (a && __JSP_EXPR__) fn_go('__JSP_EXPR__/x.do');");
            assertThat(handlers.get(2).attrs()).containsEntry("jspSubstitutions", "9,12,5:${ok}30,12,6:${ctx}");
        }
    }

    @Test
    @DisplayName("F-011-AC16 순수 HTML 파일의 스크립트에는 치환 목록이 없다")
    void noSubstitutionsInHtml() {
        try (TestProject project = TestProject.create()
                .file("src/main/webapp/index.html", "<script>var u = `/api/${id}`;</script>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(scripts(ctx)).hasSize(1);
            assertThat(scripts(ctx).get(0).attrs()).doesNotContainKey("jspSubstitutions");
        }
    }
}
