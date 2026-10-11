package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.VIEWS;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.nio.charset.Charset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 구형 SI 페이지에서 흔한 모양: 대문자 태그, 따옴표 없는 속성, MS949, CRLF. */
class JspAnalyzerLegacyTest {

    private static final String PAGE = "WebContent/board/list.jsp";

    @Test
    @DisplayName("F-011-AC1 대문자 태그와 따옴표 없는 속성도 읽는다")
    void upperCaseTags() {
        try (TestProject project = TestProject.create().file(PAGE, """
                <%@ page contentType="text/html; charset=euc-kr" %>
                <HTML><BODY onLoad="init()">
                <FORM NAME=frm METHOD=POST ACTION=<%=request.getContextPath()%>/board/search.do>
                <INPUT TYPE=text NAME=keyword onKeyDown="if(event.keyCode==13) goSearch()">
                </FORM>
                <A HREF='<%=request.getContextPath()%>/board/write.do'>글쓰기</A>
                <SCRIPT LANGUAGE="JavaScript">
                function goSearch() { document.frm.submit(); }
                </SCRIPT>
                </BODY></HTML>
                """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("POST /board/search.do", "GET /board/write.do");
            List<EmbeddedSource> embedded = ctx.embedded("js");
            assertThat(embedded).extracting(e -> e.text().trim()).containsExactly(
                    "init()", "if(event.keyCode==13) goSearch()", "function goSearch() { document.frm.submit(); }");
            assertThat(embedded.get(2).origin().startLine()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("F-011-AC12 커스텀 태그는 알려진 이벤트 속성만 핸들러로 본다")
    void handlersOnCustomTags() {
        try (TestProject project = TestProject.create().file(PAGE, """
                <%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
                <%@ taglib prefix="t" tagdir="/WEB-INF/tags" %>
                <form:select path="status" onchange="fn_status(this.value)"/>
                <t:grid only="summary" once="true" online="x"/>
                """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(ctx.embedded("js")).extracting(EmbeddedSource::text).containsExactly("fn_status(this.value)");
        }
    }

    @Test
    @DisplayName("F-011-AC1 MS949 파일과 CRLF 줄바꿈에서도 줄 번호가 맞는다")
    void ms949AndCrlf() {
        String page = "<%@ page contentType=\"text/html; charset=euc-kr\" %>\r\n"
                + "<%-- 게시판 목록 --%>\r\n"
                + "<a href=\"/board/list.do\">목록으로</a>\r\n"
                + "<script>\r\n"
                + "  var title = '<%= title %>';\r\n"
                + "  alert('한글');\r\n"
                + "</script>\r\n";
        try (TestProject project = TestProject.create().file(PAGE, page, Charset.forName("MS949"))) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(ctx.graph().diagnostics()).noneMatch(d -> d.code().equals(DiagnosticCodes.PARSE_FAILED));
            assertThat(ctx.graph().hasNode(Ids.httpRequest("GET", "/board/list.do", PAGE, 3))).isTrue();
            EmbeddedSource script = ctx.embedded("js").get(0);
            assertThat(script.origin().startLine()).isEqualTo(4);
            assertThat(script.text()).isEqualTo("\r\n  var title = '__JSP_EXPR__';\r\n  alert('한글');\r\n");
        }
    }

    @Test
    @DisplayName("F-011-AC8 c:url var 본문의 c:param은 쿼리로 붙어 스크립트에서도 경로가 드러난다")
    void urlVariableWithParamsInScript() {
        try (TestProject project = TestProject.create().file(VIEWS + "/order/detail.jsp", """
                <c:url var="detailUrl" value="/order/detail.do">
                    <c:param name="orderId" value="${order.orderId}"/>
                </c:url>
                <a href="${detailUrl}">상세</a>
                <script>
                function fn_reload() { location.href = '${detailUrl}'; }
                </script>
                """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).containsExactly("GET /order/detail.do");
            assertThat(Fixtures.request(ctx, "GET /order/detail.do").attrString("query")).isEqualTo("orderId={*}");
            assertThat(ctx.embedded("js").get(0).text())
                    .contains("location.href = '/order/detail.do?orderId=__JSP_EXPR__';");
        }
    }
}
