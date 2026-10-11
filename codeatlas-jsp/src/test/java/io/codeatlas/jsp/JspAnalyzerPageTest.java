package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.VIEWS;
import static io.codeatlas.jsp.Fixtures.WEB;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.ProjectFiles;
import io.codeatlas.core.spi.SourceFile;
import io.codeatlas.core.testing.TestProject;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JspAnalyzerPageTest {

    @Test
    @DisplayName("F-011-AC3 파일마다 JSP_PAGE 노드, CONTAINS, pageType, taglibs, scriptletCount")
    void pageNodes() {
        String list = VIEWS + "/order/list.jsp";
        String footer = VIEWS + "/common/footer.jspf";
        String tag = WEB + "/WEB-INF/tags/layout.tag";
        try (TestProject project = TestProject.create()
                .file(list, """
                        <%@ page language="java" contentType="text/html; charset=UTF-8" %>
                        <%@ page import="java.util.Date" %>
                        <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                        <%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
                        <%! private static final int PAGE_SIZE = 10; %>
                        <%
                            String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
                        %>
                        <html><body>
                        <h2>주문 목록 <small>(<%= today %> 기준)</small></h2>
                        <a href="<%=request.getContextPath()%>/order/form.do">등록</a>
                        </body></html>
                        """)
                .file(footer, "<div id=\"footer\">&copy; 2026</div>\n")
                .file(tag, """
                        <%@ tag pageEncoding="UTF-8" %>
                        <%@ attribute name="title" required="true" %>
                        <html><body><h1>${title}</h1><jsp:doBody/></body></html>
                        """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());
            GraphModel graph = ctx.graph();

            Node page = graph.node(Ids.jspPage(list)).orElseThrow();
            assertThat(page.kind()).isEqualTo(NodeKind.JSP_PAGE);
            assertThat(page.declared()).isTrue();
            assertThat(page.name()).isEqualTo("list.jsp");
            assertThat(page.qualifiedName()).isEqualTo(list);
            assertThat(page.location().file()).isEqualTo(list);
            assertThat(page.attrString("pageType")).isEqualTo("jsp");
            assertThat(page.attrList("taglibs")).containsExactly(
                    "c=http://java.sun.com/jsp/jstl/core", "fmt=http://java.sun.com/jsp/jstl/fmt");
            assertThat(page.attrInt("scriptletCount", -1)).as("선언 1 + 스크립틀릿 1 + 식 2").isEqualTo(4);
            assertThat(graph.edges(Ids.file(list), EdgeKind.CONTAINS, page.id())).hasSize(1);

            assertThat(graph.node(Ids.jspPage(footer)).orElseThrow().attrString("pageType")).isEqualTo("jspf");
            assertThat(graph.node(Ids.jspPage(footer)).orElseThrow().attrInt("scriptletCount", -1)).isZero();
            assertThat(graph.node(Ids.jspPage(tag)).orElseThrow().attrString("pageType")).isEqualTo("tag");
            assertThat(graph.edges(Ids.file(tag), EdgeKind.CONTAINS, Ids.jspPage(tag))).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-011-AC13 순수 HTML: pageType=html, 요청과 포함을 뽑고 스크립트는 치환 없이 넘긴다")
    void htmlFile() {
        String html = "src/main/resources/static/index.html";
        String script = "\n  const load = (id) => fetch(`/api/orders/${id}`).then(r => r.json());\n";
        try (TestProject project = TestProject.create()
                .file(html, "<!DOCTYPE html>\n<html><head>\n"
                        + "<link rel=\"stylesheet\" href=\"/css/app.css\">\n"
                        + "<script src=\"js/app.js\"></script>\n"
                        + "</head><body>\n"
                        + "<form action=\"/login\" method=\"post\"><input name=\"id\"></form>\n"
                        + "<a href=\"/order/list\">주문</a> <p>${notEl}</p>\n"
                        + "<script>" + script + "</script>\n"
                        + "</body></html>\n")
                .file("src/main/resources/static/js/app.js", "function app() {}\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            Node page = ctx.graph().node(Ids.jspPage(html)).orElseThrow();
            assertThat(page.attrString("pageType")).isEqualTo("html");
            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("POST /login", "GET /order/list");
            assertThat(Fixtures.include(ctx, html, Ids.jsModule("src/main/resources/static/js/app.js")).confidence().name())
                    .isEqualTo("RESOLVED");

            List<EmbeddedSource> embedded = ctx.embedded(EmbeddedSource.JS);
            assertThat(embedded).hasSize(1);
            assertThat(embedded.get(0).text()).isEqualTo(script);
            assertThat(embedded.get(0).ownerNodeId()).isEqualTo(Ids.jsModule(html));
        }
    }

    @Test
    @DisplayName("F-011-AC14 읽지 못한 파일은 PARSE_FAILED 진단을 남기고 나머지는 계속 분석한다")
    void fileFailureIsIsolated() throws Exception {
        try (TestProject project = TestProject.create().file(VIEWS + "/ok.jsp", "<a href=\"/ok.do\">ok</a>\n")) {
            AnalysisContext scanned = project.context();
            List<SourceFile> files = new ArrayList<>(scanned.files().all());
            files.add(new SourceFile(project.root().resolve("missing/ghost.jsp"), VIEWS + "/ghost.jsp", FileType.JSP));
            AnalysisContext ctx = AnalysisContext.of(project.root(), ProjectFiles.of(files), scanned.graph(),
                    AnalysisOptions.defaults());

            new JspAnalyzer().analyze(ctx);

            assertThat(ctx.graph().diagnostics())
                    .anyMatch(d -> d.code().equals(DiagnosticCodes.PARSE_FAILED) && d.location() != null
                            && d.location().file().equals(VIEWS + "/ghost.jsp"));
            assertThat(ctx.graph().hasNode(Ids.jspPage(VIEWS + "/ok.jsp"))).isTrue();
            assertThat(Fixtures.requestKeys(ctx)).containsExactly("GET /ok.do");
        }
    }

    @Test
    @DisplayName("F-011 ServiceLoader로 찾을 수 있고 order는 생산자 표준값 100이다")
    void registeredAsService() {
        List<Analyzer> analyzers = new ArrayList<>();
        ServiceLoader.load(Analyzer.class).forEach(analyzers::add);

        assertThat(analyzers).anyMatch(a -> a instanceof JspAnalyzer);
        assertThat(new JspAnalyzer().order()).isEqualTo(Analyzer.ORDER_PRODUCER);
        assertThat(new JspAnalyzer().name()).isEqualTo("jsp");
    }
}
