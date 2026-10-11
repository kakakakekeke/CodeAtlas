package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.VIEWS;
import static io.codeatlas.jsp.Fixtures.WEB;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
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

/** 3차 수정(재검증 표본에서 나온 것): 스크립트 경로의 접미사 일치, 커스텀 태그의 URL 속성, EL 삼항, 가짜 요청 경로, 벤더 경로. */
class JspAnalyzerCorpusRound3Test {

    private static Confidence requestConfidence(AnalysisContext ctx, String page, Node request) {
        return ctx.graph().edges(Ids.jspPage(page), EdgeKind.REQUESTS, request.id()).get(0).confidence();
    }

    @Test
    @DisplayName("F-011-AC25 <script src>를 웹 루트에서 못 찾으면 파일 이름을 포함한 경로 접미사가 가장 길게(두 조각 이상) 맞는 프로젝트 JS 파일이 하나뿐일 때 INFERRED로 잇고, 없는 압축본(x.min.js)은 x.js로 잇는다(minOf)")
    void scriptByLongestSuffix() {
        String page = "admin/src/main/webapp/WEB-INF/jsp/index.jsp";
        try (TestProject project = TestProject.create()
                .file(page, """
                        <html><head>
                        <script src="${basePath}/resources/ui-admin/js/common.js"></script>
                        <script src="${basePath}/resources/ui-admin/js/dup.js"></script>
                        <script src="${basePath}/res/solo.js"></script>
                        <script src="${basePath}/resources/ui-admin/plugins/tool/tool.js"></script>
                        <script src="${basePath}/resources/ui-admin/js/app.min.js"></script>
                        <script src="${basePath}/resources/ui-admin/js/gone.min.js"></script>
                        </head></html>
                        """)
                .file("ui-admin/src/js/common.js", "function common() {}\n")
                .file("other/src/util/common.js", "function other() {}\n")
                .file("a/js/dup.js", "function a() {}\n").file("b/js/dup.js", "function b() {}\n")
                .file("m/src/solo.js", "function solo() {}\n")
                .file("ui-admin/src/plugins/tool/tool.js", "function tool() {}\n")
                .file("ui-admin/src/js/app.js", "function app() {}\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            Edge common = Fixtures.include(ctx, page, Ids.jsModule("ui-admin/src/js/common.js"));
            assertThat(common.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(common.attrString("via")).isEqualTo("script");
            assertThat(common.attr("minOf")).isNull();
            Edge app = Fixtures.include(ctx, page, Ids.jsModule("ui-admin/src/js/app.js"));
            assertThat(app.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(app.attrString("minOf")).isEqualTo("/resources/ui-admin/js/app.min.js");
            // 가장 길게 맞는 후보가 둘이거나, 파일 이름 한 조각만 맞거나, 벤더 폴더의 파일이면 잇지 않는다
            assertThat(Fixtures.includes(ctx, page)).extracting(Edge::to).containsExactlyInAnyOrder(
                    Ids.jsModule("ui-admin/src/js/common.js"), Ids.jsModule("ui-admin/src/js/app.js"));
            assertThat(ctx.graph().node(Ids.jspPage(page)).orElseThrow().attrs().get("externalScripts")).isEqualTo(List.of(
                    "${basePath}/resources/ui-admin/js/dup.js", "${basePath}/res/solo.js",
                    "${basePath}/resources/ui-admin/plugins/tool/tool.js", "${basePath}/resources/ui-admin/js/gone.min.js"));
        }
    }

    @Test
    @DisplayName("F-011-AC26 접두사 있는 태그의 url·href·action·src·dataUrl 속성이 서버 경로면 api=tag인 요청(ANY)과 INFERRED REQUESTS를 만든다")
    void customTagUrlAttributes() {
        String page = VIEWS + "/modules/sys/userForm.jsp";
        try (TestProject project = TestProject.create().file(page, """
                <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                <%@ taglib prefix="sys" tagdir="/WEB-INF/tags/sys" %>
                <c:set var="ctx" value="${pageContext.request.contextPath}"/>
                <sys:treeselect id="office" name="office.id" url="/sys/office/treeData?type=2" title="부서" cssClass="required"/>
                <my:link href="${ctx}/sys/user/list">목록</my:link>
                <my:grid dataUrl="${ctx}/sys/user/data/${user.id}" />
                <sys:ckfinder input="files" uploadPath="/cms/article" selectMultiple="true"/>
                <my:img src="/static/images/a.png"/>
                <my:x url="relative/path"/>
                <my:x url="${dynamicUrl}"/>
                <my:x url="http://example.com/api"/>
                <c:import url="/common/menu.do"/>
                <c:url var="saveUrl" value="/sys/user/save"/>
                """)
                .file(WEB + "/WEB-INF/tags/sys/treeselect.tag", "<%@ tag pageEncoding=\"UTF-8\" %>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("ANY /sys/office/treeData", "ANY /sys/user/list",
                    "ANY /sys/user/data/{*}", "GET /common/menu.do");
            Node tree = Fixtures.request(ctx, "ANY /sys/office/treeData");
            assertThat(tree.attrString("api")).isEqualTo("tag");
            assertThat(tree.attrString("query")).isEqualTo("type=2");
            assertThat(tree.attrString("url")).isEqualTo("/sys/office/treeData?type=2");
            assertThat(tree.location().startLine()).isEqualTo(4);
            assertThat(requestConfidence(ctx, page, tree)).isEqualTo(Confidence.INFERRED);
            assertThat(Fixtures.request(ctx, "ANY /sys/user/data/{*}").attrString("api")).isEqualTo("tag");
            // 종전 규칙이 다루는 태그(c:import)는 그대로다
            assertThat(Fixtures.request(ctx, "GET /common/menu.do").attrString("api")).isEqualTo("include");
            // 동적이기만 한 값은 미해결로도 남기지 않는다(그 속성이 URL인지 모른다)
            assertThat(ctx.graph().outgoing(Ids.jspPage(page), EdgeKind.REQUESTS)).hasSize(4);
            // 태그 파일 사용의 INCLUDES는 종전대로
            assertThat(Fixtures.includes(ctx, page)).extracting(Edge::to)
                    .contains(Ids.jspPage(WEB + "/WEB-INF/tags/sys/treeselect.tag"));
        }
    }

    @Test
    @DisplayName("F-011-AC27 URL 안의 EL이 cond ? '리터럴A' : '리터럴B' 꼴이면 {*} 대신 후보 URL 둘로 요청을 만든다(INFERRED)")
    void elTernaryCandidates() {
        String page = VIEWS + "/modules/oa/oaNotifyList.jsp";
        try (TestProject project = TestProject.create().file(page, """
                <%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
                <c:set var="ctx" value="${pageContext.request.contextPath}"/>
                <a href="${ctx}/oa/oaNotify/${oaNotify.self?'self':''}">목록</a>
                <form:form id="searchForm" modelAttribute="oaNotify" action="${ctx}/oa/oaNotify/${oaNotify.self ? 'self' : 'all'}/search" method="post">
                </form:form>
                <a href="${ctx}/oa/view/${empty id ? other : 'x'}">변수가 섞인 삼항</a>
                """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("GET /oa/oaNotify/self", "GET /oa/oaNotify/",
                    "POST /oa/oaNotify/self/search", "POST /oa/oaNotify/all/search", "GET /oa/view/{*}");
            Node self = Fixtures.request(ctx, "GET /oa/oaNotify/self");
            assertThat(self.attrString("url")).isEqualTo("${ctx}/oa/oaNotify/${oaNotify.self?'self':''}");
            assertThat(self.attr("dynamic")).isEqualTo(false);
            assertThat(requestConfidence(ctx, page, self)).isEqualTo(Confidence.INFERRED);
            assertThat(requestConfidence(ctx, page, Fixtures.request(ctx, "GET /oa/oaNotify/"))).isEqualTo(Confidence.INFERRED);
            assertThat(requestConfidence(ctx, page, Fixtures.request(ctx, "GET /oa/view/{*}"))).isEqualTo(Confidence.RESOLVED);
        }
    }

    @Test
    @DisplayName("F-011-AC28 컨텍스트 경로 식 '/' 뒤의 '/x'는 슬래시를 하나로 합치고(외부 URL이 아니다), 컨텍스트 경로 뒤가 통째로 동적이면 쿼리 조각을 경로로 삼지 않고 미해결로 둔다")
    void contextSlashAndQueryOnly() {
        String page = VIEWS + "/sns/twitter.jsp";
        try (TestProject project = TestProject.create().file(page, """
                <c:set var="ctx" value="${pageContext.request.contextPath}"/>
                <a href="<c:url value='/'/>/twitter/login.do">로그인</a>
                <a href="${ctx}//twitter/me.do">나</a>
                <a href="//cdn.example.com/lib/x">외부</a>
                <a href="${ctx}${url}&extId=${extId}">동적</a>
                <a href="${ctx}/x/view.do&id=${id}">물음표 없는 쿼리</a>
                """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("GET /twitter/login.do", "GET /twitter/me.do",
                    "GET //cdn.example.com/lib/x", "GET /x/view.do");
            assertThat(Fixtures.request(ctx, "GET /twitter/login.do").attr("external")).isNull();
            assertThat(Fixtures.request(ctx, "GET //cdn.example.com/lib/x").attr("external")).isEqualTo(true);
            assertThat(Fixtures.request(ctx, "GET /x/view.do").attrString("query")).isEqualTo("id={*}");
            assertThat(ctx.graph().outgoing(Ids.jspPage(page), EdgeKind.REQUESTS)).extracting(Edge::to)
                    .contains("unresolved:http-url:${ctx}${url}&extId=${extId}");
        }
    }

    @Test
    @DisplayName("F-011-AC29 벤더 경로 보강: plugins/<이름>/ 아래의 페이지는 정적 자원 폴더 밖이어도 건너뛰고, WEB-INF·templates 안쪽과 plugins 바로 아래의 페이지는 분석한다")
    void pluginFoldersAreVendor() {
        try (TestProject project = TestProject.create()
                .file("admin/src/plugins/fancyplayer/demo/index.html", "<html><a href=\"/demo/upload\">x</a></html>\n")
                .file("admin/src/plugins/readme.html", "<html><a href=\"/own/readme\">x</a></html>\n")
                .file(VIEWS + "/plugins/board/list.jsp", "<a href=\"/plugins/board/view.do\">x</a>\n")
                .file("src/main/resources/templates/plugins/cms/index.html", "<html><a href=\"/cms/index\">x</a></html>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("GET /own/readme", "GET /plugins/board/view.do",
                    "GET /cms/index");
            assertThat(ctx.graph().node(Ids.jspPage("admin/src/plugins/fancyplayer/demo/index.html")).orElseThrow()
                    .attrString("skipped")).isEqualTo("vendor");
            assertThat(ctx.graph().nodes(NodeKind.JSP_PAGE)).hasSize(4);
        }
    }
}
