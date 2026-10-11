package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.VIEWS;
import static io.codeatlas.jsp.Fixtures.WEB;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JspAnalyzerIncludeTest {

    private static final String LIST = VIEWS + "/order/list.jsp";

    @Test
    @DisplayName("F-011-AC4 지시자 include, jsp:include, c:import, 태그 파일을 INCLUDES로 잇는다")
    void includes() {
        try (TestProject project = TestProject.create()
                .file(LIST, """
                        <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                        <%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
                        <t:layout title="주문 목록">
                        <jsp:include page="../common/header.jsp" flush="true">
                            <jsp:param name="menu" value="order"/>
                        </jsp:include>
                        <c:import url="/WEB-INF/views/common/menu.jsp?active=order"/>
                        <c:import url="https://partner.example.com/banner.html"/>
                        <t:layout title="두 번째 사용"></t:layout>
                        <%@ include file="/WEB-INF/views/common/footer.jspf" %>
                        <%@include file="paging.jsp"%>
                        </t:layout>
                        """)
                .file(VIEWS + "/common/header.jsp", "<div id=\"header\"></div>\n")
                .file(VIEWS + "/common/menu.jsp", "<ul></ul>\n")
                .file(VIEWS + "/common/footer.jspf", "<div id=\"footer\"></div>\n")
                .file(VIEWS + "/order/paging.jsp", "<div class=\"paging\"></div>\n")
                .file(WEB + "/WEB-INF/tags/layout.tag", "<%@ tag pageEncoding=\"UTF-8\" %><jsp:doBody/>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<Edge> edges = Fixtures.includes(ctx, LIST);
            assertThat(edges).extracting(Edge::to).containsExactlyInAnyOrder(
                    Ids.jspPage(WEB + "/WEB-INF/tags/layout.tag"),
                    Ids.jspPage(VIEWS + "/common/header.jsp"),
                    Ids.jspPage(VIEWS + "/common/menu.jsp"),
                    Ids.jspPage(VIEWS + "/common/footer.jspf"),
                    Ids.jspPage(VIEWS + "/order/paging.jsp"));
            assertThat(edges).allMatch(e -> e.confidence() == Confidence.RESOLVED);

            Edge header = Fixtures.include(ctx, LIST, Ids.jspPage(VIEWS + "/common/header.jsp"));
            assertThat(header.attrString("via")).isEqualTo("action");
            assertThat(header.attrString("path")).isEqualTo("../common/header.jsp");
            assertThat(header.location().startLine()).isEqualTo(4);
            assertThat(header.location().startColumn()).isEqualTo(1);
            assertThat(Fixtures.include(ctx, LIST, Ids.jspPage(VIEWS + "/common/footer.jspf")).attrString("via"))
                    .isEqualTo("directive");
            assertThat(Fixtures.include(ctx, LIST, Ids.jspPage(VIEWS + "/common/menu.jsp")).attrString("via"))
                    .isEqualTo("import");
            Edge tag = Fixtures.include(ctx, LIST, Ids.jspPage(WEB + "/WEB-INF/tags/layout.tag"));
            assertThat(tag.attrString("via")).isEqualTo("tag");
            assertThat(tag.location().startLine()).as("첫 사용 위치에 한 번만").isEqualTo(3);
        }
    }

    @Test
    @DisplayName("F-011-AC4 웹 루트 판정: WebContent, WEB-INF 없는 webapp 하위, 여러 모듈")
    void webRoots() {
        try (TestProject project = TestProject.create()
                .file("WebContent/jsp/board/list.jsp", "<jsp:include page=\"/jsp/common/top.jsp\"/>\n")
                .file("WebContent/jsp/common/top.jsp", "<div></div>\n")
                .file("admin/src/main/webapp/WEB-INF/jsp/main.jsp", "<%@ include file=\"/WEB-INF/jsp/inc/head.jsp\" %>\n")
                .file("admin/src/main/webapp/WEB-INF/jsp/inc/head.jsp", "<div></div>\n")
                .file("front/src/main/webapp/WEB-INF/jsp/inc/head.jsp", "<div></div>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.includes(ctx, "WebContent/jsp/board/list.jsp")).extracting(Edge::to)
                    .containsExactly(Ids.jspPage("WebContent/jsp/common/top.jsp"));
            assertThat(Fixtures.includes(ctx, "admin/src/main/webapp/WEB-INF/jsp/main.jsp")).extracting(Edge::to)
                    .containsExactly(Ids.jspPage("admin/src/main/webapp/WEB-INF/jsp/inc/head.jsp"));
        }
    }

    @Test
    @DisplayName("F-011-AC5 없는 파일과 동적 경로의 include는 미해결 엣지(jsp-include)로 남긴다")
    void unresolvedIncludes() {
        try (TestProject project = TestProject.create()
                .file(LIST, """
                        <%@ include file="/WEB-INF/views/common/missing.jspf" %>
                        <jsp:include page="${viewName}.jsp"/>
                        <jsp:include page='<%= "/WEB-INF/views/" + skin + "/top.jsp" %>'/>
                        """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<Edge> edges = Fixtures.includes(ctx, LIST);
            assertThat(edges).hasSize(3).allMatch(e -> e.confidence() == Confidence.UNRESOLVED);
            assertThat(edges).extracting(Edge::to).contains(
                    Ids.unresolved("jsp-include", VIEWS + "/common/missing.jspf"),
                    Ids.unresolved("jsp-include", "${viewName}.jsp"));
            Node unresolved = ctx.graph().node(Ids.unresolved("jsp-include", "${viewName}.jsp")).orElseThrow();
            assertThat(unresolved.kind()).isEqualTo(NodeKind.UNRESOLVED);
            assertThat(unresolved.attrString("category")).isEqualTo("jsp-include");
            assertThat(ctx.graph().diagnostics())
                    .filteredOn(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(3);
            assertThat(edges.get(1).location().startLine()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("F-011-AC5 페이지가 아닌 대상(.do)의 jsp:include는 서버 요청으로 기록한다")
    void includeOfControllerUrl() {
        try (TestProject project = TestProject.create()
                .file(LIST, "<jsp:include page=\"/common/menu.do\"/>\n<jsp:forward page=\"/login.do\"/>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.includes(ctx, LIST)).isEmpty();
            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("GET /common/menu.do", "GET /login.do");
            assertThat(Fixtures.request(ctx, "GET /common/menu.do").attrString("api")).isEqualTo("include");
            assertThat(Fixtures.request(ctx, "GET /login.do").attrString("api")).isEqualTo("forward");
        }
    }

    @Test
    @DisplayName("F-011-AC6 script src: 컨텍스트 경로를 떼고 JS 파일을 찾는다. 끝 일치는 INFERRED, 못 찾으면 externalScripts")
    void scriptSources() {
        try (TestProject project = TestProject.create()
                .file(LIST, """
                        <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                        <c:set var="ctx" value="${pageContext.request.contextPath}"/>
                        <script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
                        <script src="//cdn.example.com/lib.js"></script>
                        <script src="${ctx}/resources/js/order.js"></script>
                        <script src="<c:url value='/resources/js/common.js'/>"></script>
                        <script type="text/javascript" src="<%=request.getContextPath()%>/js/legacy.js?v=20240101"></script>
                        <script src="/static/js/util.js"></script>
                        <script src="${pageContext.request.contextPath}/resources/js/missing.js"></script>
                        <script src="/js/dup.js"></script>
                        <script src="${cdnHost}/js/dynamic-${version}.js"></script>
                        """)
                .file(WEB + "/resources/js/order.js", "function fn_order() {}\n")
                .file(WEB + "/resources/js/common.js", "function fn_common() {}\n")
                .file(WEB + "/js/legacy.js", "function fn_legacy() {}\n")
                .file("src/main/resources/static/js/util.js", "function fn_util() {}\n")
                .file("module-a/web/js/dup.js", "var a;\n")
                .file("module-b/web/js/dup.js", "var b;\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<Edge> edges = Fixtures.includes(ctx, LIST);
            assertThat(edges).extracting(Edge::to).containsExactlyInAnyOrder(
                    Ids.jsModule(WEB + "/resources/js/order.js"),
                    Ids.jsModule(WEB + "/resources/js/common.js"),
                    Ids.jsModule(WEB + "/js/legacy.js"),
                    Ids.jsModule("src/main/resources/static/js/util.js"));
            assertThat(Fixtures.include(ctx, LIST, Ids.jsModule(WEB + "/resources/js/order.js")).confidence())
                    .isEqualTo(Confidence.RESOLVED);
            assertThat(Fixtures.include(ctx, LIST, Ids.jsModule(WEB + "/resources/js/common.js")).confidence())
                    .isEqualTo(Confidence.RESOLVED);
            assertThat(Fixtures.include(ctx, LIST, Ids.jsModule(WEB + "/js/legacy.js")).confidence())
                    .isEqualTo(Confidence.RESOLVED);
            Edge inferred = Fixtures.include(ctx, LIST, Ids.jsModule("src/main/resources/static/js/util.js"));
            assertThat(inferred.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(inferred.attrString("path")).isEqualTo("/static/js/util.js");
            assertThat(inferred.location().startLine()).isEqualTo(8);

            Node page = ctx.graph().node(Ids.jspPage(LIST)).orElseThrow();
            assertThat(page.attrList("externalScripts")).containsExactly(
                    "https://code.jquery.com/jquery-3.7.1.min.js",
                    "//cdn.example.com/lib.js",
                    "${pageContext.request.contextPath}/resources/js/missing.js",
                    "/js/dup.js",
                    "${cdnHost}/js/dynamic-${version}.js");
            // src만 있는 스크립트는 내장 소스도 주인 모듈도 만들지 않는다
            assertThat(ctx.embedded("js")).isEmpty();
            assertThat(ctx.graph().hasNode(Ids.jsModule(LIST))).isFalse();
            assertThat(ctx.graph().edges(EdgeKind.REQUESTS)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-011-AC15 정적 include한 조각의 taglib 선언을 따라가 태그 파일 사용을 잡는다")
    void taglibsFromStaticInclude() {
        try (TestProject project = TestProject.create()
                .file(VIEWS + "/common/taglibs.jspf", """
                        <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                        <%@ taglib prefix="ui" tagdir="/WEB-INF/tags/ui" %>
                        <c:set var="root" value="${pageContext.request.contextPath}"/>
                        """)
                .file(LIST, """
                        <%@ include file="/WEB-INF/views/common/taglibs.jspf" %>
                        <ui:paging total="${total}"/>
                        <ui:unknown/>
                        <a href="${root}">홈</a>
                        """)
                .file(WEB + "/WEB-INF/tags/ui/paging.tag", "<div class=\"paging\"></div>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.include(ctx, LIST, Ids.jspPage(WEB + "/WEB-INF/tags/ui/paging.tag")).confidence())
                    .isEqualTo(Confidence.RESOLVED);
            assertThat(Fixtures.include(ctx, LIST, Ids.unresolved("jsp-include", WEB + "/WEB-INF/tags/ui/unknown.tag"))
                    .confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(Fixtures.requestKeys(ctx)).as("include한 조각의 컨텍스트 경로 변수").containsExactly("GET /");
            assertThat(ctx.graph().node(Ids.jspPage(LIST)).orElseThrow().attrList("taglibs"))
                    .as("taglibs 속성은 이 파일에서 선언한 것만").isEmpty();
        }
    }
}
