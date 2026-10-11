package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.VIEWS;
import static io.codeatlas.jsp.Fixtures.WEB;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 3차 수정: CR뿐인 줄 끝, 레이아웃 프레임워크(SiteMesh 데코레이터, Tiles 정의)가 잇는 페이지 관계. */
class JspAnalyzerLayoutTest {

    private static List<String> sitemeshTargets(AnalysisContext ctx, String page) {
        return Fixtures.includes(ctx, page).stream().filter(e -> "sitemesh".equals(e.attrString("via"))).map(Edge::to).toList();
    }

    private static List<String> tilesTargets(AnalysisContext ctx, String page) {
        return Fixtures.includes(ctx, page).stream().filter(e -> "tiles".equals(e.attrString("via"))).map(Edge::to).sorted()
                .toList();
    }

    @Test
    @DisplayName("F-011-AC22 줄 끝이 CR뿐인(또는 CR·LF·CRLF가 섞인) JSP도 줄 단위로 위치를 센다: 요청·포함·내장 소스의 줄 번호가 CR마다 는다")
    void carriageReturnOnlyLineEnds() {
        String page = VIEWS + "/legacy/cr.jsp";
        try (TestProject project = TestProject.create()
                .file(page, "<html>\r<body>\r<a href=\"/cr/a.do\">a</a>\r<script>\rfunction f() {\r  location.href = '${ctx}/cr/b.do';\r}\r"
                        + "</script>\r<jsp:include page=\"inc.jsp\"/>\r</body>\r</html>\r")
                .file(VIEWS + "/legacy/inc.jsp", "<div>\r\n<a href=\"/cr/c.do\">c</a>\r<a href=\"/cr/d.do\">d</a>\n</div>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.request(ctx, "GET /cr/a.do").id()).endsWith("cr.jsp:3");
            assertThat(Fixtures.request(ctx, "GET /cr/a.do").location().startColumn()).isEqualTo(1);
            Edge include = Fixtures.include(ctx, page, Ids.jspPage(VIEWS + "/legacy/inc.jsp"));
            assertThat(include.location().startLine()).isEqualTo(9);
            List<EmbeddedSource> scripts = ctx.embedded(EmbeddedSource.JS);
            assertThat(scripts).hasSize(1);
            assertThat(scripts.get(0).origin().startLine()).isEqualTo(4);
            assertThat(scripts.get(0).origin().endLine()).isEqualTo(8);
            // 넘기는 본문의 줄 끝은 LF로 맞춘다(길이는 그대로): 소비자(web)가 줄을 같은 방식으로 센다
            assertThat(scripts.get(0).text()).isEqualTo("\nfunction f() {\n  location.href = '__JSP_EXPR__/cr/b.do';\n}\n");
            // CRLF는 줄 하나다
            assertThat(Fixtures.request(ctx, "GET /cr/c.do").id()).endsWith("inc.jsp:2");
            assertThat(Fixtures.request(ctx, "GET /cr/d.do").id()).endsWith("inc.jsp:3");

            LineMap lines = new LineMap("a\rb\r\nc\nd");
            assertThat(lines.line(0)).isEqualTo(1);
            assertThat(lines.line(2)).isEqualTo(2);
            assertThat(lines.line(3)).as("CRLF의 CR은 그 줄에 속한다").isEqualTo(2);
            assertThat(lines.line(5)).isEqualTo(3);
            assertThat(lines.column(5)).isEqualTo(1);
            assertThat(lines.line(7)).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("F-011-AC23 SiteMesh 2: <meta name=\"decorator\">와 decorators.xml로 페이지 → 데코레이터 JSP INCLUDES(INFERRED, via=sitemesh)를 만든다. 이름이 동적이면 맞는 데코레이터마다, 없는 이름은 미해결이다")
    void sitemeshDecoratorByMeta() {
        String list = VIEWS + "/modules/sys/userList.jsp";
        String form = VIEWS + "/modules/sys/userForm.jsp";
        String front = VIEWS + "/modules/cms/front/index.jsp";
        String none = VIEWS + "/modules/sys/sysLogin.jsp";
        String missing = VIEWS + "/modules/sys/missing.jsp";
        String path = VIEWS + "/modules/sys/byPath.jsp";
        try (TestProject project = TestProject.create()
                .file(WEB + "/WEB-INF/decorators.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <decorators defaultdir="/WEB-INF/views">
                        	<!-- <decorator name="commented" page="layouts/none.jsp" /> -->
                        	<decorator name="blank" page="layouts/blank.jsp" />
                        	<decorator name="default" page="layouts/default.jsp" />
                        	<decorator name="cms_default_basic" page="modules/cms/front/themes/basic/layouts/default.jsp" />
                        	<decorator name="cms_default_weixin" page="modules/cms/front/themes/weixin/layouts/default.jsp" />
                        	<decorator name="absolute" page="/WEB-INF/abs/main.jsp" />
                        </decorators>
                        """)
                .file(VIEWS + "/layouts/default.jsp", """
                        <html><head><title><sitemesh:title/></title>
                        <script src="${ctxStatic}/common/jeesite.js" type="text/javascript"></script>
                        <sitemesh:head/></head><body><sitemesh:body/></body></html>
                        """)
                .file(VIEWS + "/layouts/blank.jsp", "<html><head><sitemesh:head/></head><body><sitemesh:body/></body></html>\n")
                .file(VIEWS + "/modules/cms/front/themes/basic/layouts/default.jsp", "<html><sitemesh:body/></html>\n")
                .file(VIEWS + "/modules/cms/front/themes/weixin/layouts/default.jsp", "<html><sitemesh:body/></html>\n")
                .file(WEB + "/WEB-INF/abs/main.jsp", "<html><sitemesh:body/></html>\n")
                .file(WEB + "/static/common/jeesite.js", "function loading(m) {}\n")
                .file(list, """
                        <%@ page contentType="text/html;charset=UTF-8" %>
                        <html>
                        <head>
                        	<title>사용자</title>
                        	<meta name="decorator" content="default"/>
                        </head>
                        <body></body>
                        </html>
                        """)
                .file(form, "<html><head><META NAME=\"decorator\" CONTENT=\"blank\"></head><body></body></html>\n")
                .file(front, "<html><head>\n<meta name=\"decorator\" content=\"cms_default_${site.theme}\"/>\n</head></html>\n")
                .file(none, "<html><head><meta name=\"keywords\" content=\"x\"/></head><body></body></html>\n")
                .file(missing, "<html><head><meta name=\"decorator\" content=\"nope\"/></head></html>\n")
                .file(path, "<html><head><meta name=\"decorator\" content=\"absolute\"/></head></html>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            Edge edge = Fixtures.include(ctx, list, Ids.jspPage(VIEWS + "/layouts/default.jsp"));
            assertThat(edge.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(edge.attrString("via")).isEqualTo("sitemesh");
            assertThat(edge.attrString("path")).isEqualTo("default");
            assertThat(edge.location().file()).isEqualTo(list);
            assertThat(edge.location().startLine()).isEqualTo(5);
            assertThat(sitemeshTargets(ctx, form)).containsExactly(Ids.jspPage(VIEWS + "/layouts/blank.jsp"));
            assertThat(sitemeshTargets(ctx, path)).containsExactly(Ids.jspPage(WEB + "/WEB-INF/abs/main.jsp"));
            // 이름에 EL이 섞였으면 정적인 부분이 맞는 데코레이터마다(후보)
            assertThat(sitemeshTargets(ctx, front)).containsExactlyInAnyOrder(
                    Ids.jspPage(VIEWS + "/modules/cms/front/themes/basic/layouts/default.jsp"),
                    Ids.jspPage(VIEWS + "/modules/cms/front/themes/weixin/layouts/default.jsp"));
            assertThat(Fixtures.includes(ctx, front)).allMatch(e -> e.confidence() == Confidence.INFERRED);
            // meta가 없으면(패턴도 없다) 잇지 않고, 없는 이름은 미해결로 남긴다
            assertThat(sitemeshTargets(ctx, none)).isEmpty();
            assertThat(Fixtures.includes(ctx, missing)).extracting(Edge::to).containsExactly("unresolved:jsp-include:decorator:nope");
            // 데코레이터 자신에게는 엣지가 없다. 데코레이터의 <script src>는 종전대로 그 JSP의 INCLUDES다
            assertThat(sitemeshTargets(ctx, VIEWS + "/layouts/default.jsp")).isEmpty();
            assertThat(Fixtures.includes(ctx, VIEWS + "/layouts/default.jsp")).extracting(Edge::to)
                    .containsExactly(Ids.jsModule(WEB + "/static/common/jeesite.js"));
        }
    }

    @Test
    @DisplayName("F-011-AC23 SiteMesh: meta 없이 <pattern>만 있으면 패턴이 /* 하나뿐일 때만 모든 페이지에 적용하고(INFERRED), SiteMesh 3(sitemesh3.xml의 mapping)도 같다")
    void sitemeshDecoratorByPattern() {
        // SiteMesh 2, 패턴 하나(/*)
        try (TestProject project = TestProject.create()
                .file(WEB + "/WEB-INF/decorators.xml", """
                        <decorators defaultdir="/decorators">
                          <excludes><pattern>/static/*</pattern></excludes>
                          <decorator name="main" page="main.jsp">
                            <pattern>/*</pattern>
                          </decorator>
                          <decorator name="popup" page="popup.jsp"/>
                        </decorators>
                        """)
                .file(WEB + "/decorators/main.jsp", "<html><body><jsp:include page=\"/common/top.jsp\"/><decorator:body/></body></html>\n")
                .file(WEB + "/decorators/popup.jsp", "<html><body><decorator:body/></body></html>\n")
                .file(WEB + "/common/top.jsp", "<div></div>\n")
                .file(WEB + "/order/list.jsp", "<html><body></body></html>\n")
                .file(WEB + "/order/pop.jsp", "<html><head><meta name=\"decorator\" content=\"popup\"></head></html>\n")
                .file(WEB + "/order/part.jspf", "<div></div>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());
            String main = Ids.jspPage(WEB + "/decorators/main.jsp");

            assertThat(sitemeshTargets(ctx, WEB + "/order/list.jsp")).containsExactly(main);
            Edge edge = Fixtures.include(ctx, WEB + "/order/list.jsp", main);
            assertThat(edge.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(edge.attrString("path")).isEqualTo("/*");
            // meta가 있으면 그것이 먼저다
            assertThat(sitemeshTargets(ctx, WEB + "/order/pop.jsp")).containsExactly(Ids.jspPage(WEB + "/decorators/popup.jsp"));
            // 데코레이터 자신, 다른 페이지가 포함하는 조각, .jspf에는 적용하지 않는다
            assertThat(sitemeshTargets(ctx, WEB + "/decorators/main.jsp")).isEmpty();
            assertThat(sitemeshTargets(ctx, WEB + "/decorators/popup.jsp")).isEmpty();
            assertThat(sitemeshTargets(ctx, WEB + "/common/top.jsp")).isEmpty();
            assertThat(sitemeshTargets(ctx, WEB + "/order/part.jspf")).isEmpty();
        }
        // 패턴이 여럿이면 URL과 JSP의 대응을 모르므로 meta가 없는 페이지는 잇지 않는다
        try (TestProject project = TestProject.create()
                .file(WEB + "/WEB-INF/decorators.xml", """
                        <decorators defaultdir="/decorators">
                          <decorator name="admin" page="admin.jsp"><pattern>/admin/*</pattern></decorator>
                          <decorator name="main" page="main.jsp"><pattern>/*</pattern></decorator>
                        </decorators>
                        """)
                .file(WEB + "/decorators/main.jsp", "<html></html>\n").file(WEB + "/decorators/admin.jsp", "<html></html>\n")
                .file(WEB + "/order/list.jsp", "<html><body></body></html>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());
            assertThat(sitemeshTargets(ctx, WEB + "/order/list.jsp")).isEmpty();
        }
        // SiteMesh 3
        try (TestProject project = TestProject.create()
                .file(WEB + "/WEB-INF/sitemesh3.xml", """
                        <sitemesh>
                          <mapping path="/*" decorator="/WEB-INF/decorators/main.jsp"/>
                          <mapping path="/static/*" exclue="true"/>
                        </sitemesh>
                        """)
                .file(WEB + "/WEB-INF/decorators/main.jsp", "<html><body><sitemesh:write property=\"body\"/></body></html>\n")
                .file(WEB + "/WEB-INF/decorators/pop.jsp", "<html><body><sitemesh:write property=\"body\"/></body></html>\n")
                .file(WEB + "/WEB-INF/views/a.jsp", "<html><body></body></html>\n")
                .file(WEB + "/WEB-INF/views/b.jsp",
                        "<html><head><meta name=\"decorator\" content=\"/WEB-INF/decorators/pop.jsp\"></head></html>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());
            assertThat(sitemeshTargets(ctx, WEB + "/WEB-INF/views/a.jsp"))
                    .containsExactly(Ids.jspPage(WEB + "/WEB-INF/decorators/main.jsp"));
            assertThat(sitemeshTargets(ctx, WEB + "/WEB-INF/views/b.jsp"))
                    .containsExactly(Ids.jspPage(WEB + "/WEB-INF/decorators/pop.jsp"));
        }
    }

    @Test
    @DisplayName("F-011-AC24 Tiles: 정의의 template JSP → put-attribute JSP INCLUDES(INFERRED, via=tiles)를 만들고, 설정 파일 FILE 노드에 tilesDefinitions(이름=template, extends를 푼 값)·tilesAttributes(이름=JSP)를 싣는다. 와일드카드 정의는 원문 그대로이고 INCLUDES를 만들지 않는다")
    void tilesDefinitions() {
        String config = WEB + "/WEB-INF/tiles/tiles-def.xml";
        String admin = WEB + "/WEB-INF/tiles/tiles-admin.xml";
        String layout = WEB + "/WEB-INF/layout";
        try (TestProject project = TestProject.create()
                .file(config, """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE tiles-definitions PUBLIC "-//Apache Software Foundation//DTD Tiles Configuration 3.0//EN"
                            "http://tiles.apache.org/dtds/tiles-config_3_0.dtd">
                        <tiles-definitions>
                          <definition name="base" template="/WEB-INF/layout/main.jsp">
                            <put-attribute name="title" value="샘플" />
                            <put-attribute name="header" value="/WEB-INF/layout/header.jsp" />
                            <put-attribute name="body" value="" />
                            <put-attribute name="footer" value="/WEB-INF/layout/footer.jsp" />
                          </definition>
                          <definition name="order.list" extends="base">
                            <put-attribute name="body" value="/WEB-INF/views/order/list.jsp" />
                          </definition>
                          <definition name="*/*" extends="base">
                            <put-attribute name="body" value="/WEB-INF/views/{1}/{2}.jsp" />
                          </definition>
                          <definition name="popup" template="/WEB-INF/layout/popup.jsp">
                            <put-attribute name="body" value="order.fragment" />
                          </definition>
                          <definition name="order.fragment" template="/WEB-INF/views/order/fragment.jsp" />
                          <definition name="orphan" extends="nowhere">
                            <put-attribute name="body" value="/WEB-INF/views/order/list.jsp" />
                          </definition>
                          <definition name="loopA" extends="loopB" />
                          <definition name="loopB" extends="loopA" />
                        </tiles-definitions>
                        """)
                .file(admin, """
                        <tiles-definitions>
                          <definition name="admin.home" extends="base">
                            <put-attribute name="footer" value="/WEB-INF/layout/admin-footer.jsp" />
                            <put-attribute name="body" value="/WEB-INF/views/admin/home.jsp" />
                          </definition>
                          <definition name="struts.old" path="/WEB-INF/layout/popup.jsp">
                            <put name="body" value="/WEB-INF/views/admin/old.jsp" />
                            <put name="gone" value="/WEB-INF/views/admin/gone.jsp" />
                          </definition>
                        </tiles-definitions>
                        """)
                .file(layout + "/main.jsp", "<html><body><tiles:insertAttribute name=\"body\"/></body></html>\n")
                .file(layout + "/popup.jsp", "<html><body><tiles:insertAttribute name=\"body\"/></body></html>\n")
                .file(layout + "/header.jsp", "<div></div>\n").file(layout + "/footer.jsp", "<div></div>\n")
                .file(layout + "/admin-footer.jsp", "<div></div>\n")
                .file(VIEWS + "/order/list.jsp", "<div></div>\n").file(VIEWS + "/order/fragment.jsp", "<div></div>\n")
                .file(VIEWS + "/member/edit.jsp", "<div></div>\n")
                .file(VIEWS + "/admin/home.jsp", "<div></div>\n").file(VIEWS + "/admin/old.jsp", "<div></div>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            Node file = ctx.graph().node(Ids.file(config)).orElseThrow();
            assertThat(file.attrs().get("tilesDefinitions")).isEqualTo(List.of(
                    "base=/WEB-INF/layout/main.jsp", "order.list=/WEB-INF/layout/main.jsp", "*/*=/WEB-INF/layout/main.jsp",
                    "popup=/WEB-INF/layout/popup.jsp", "order.fragment=/WEB-INF/views/order/fragment.jsp", "orphan=", "loopA=",
                    "loopB="));
            assertThat(file.attrs().get("tilesAttributes")).isEqualTo(List.of(
                    "base=/WEB-INF/layout/header.jsp", "base=/WEB-INF/layout/footer.jsp",
                    "order.list=/WEB-INF/layout/header.jsp", "order.list=/WEB-INF/views/order/list.jsp",
                    "order.list=/WEB-INF/layout/footer.jsp",
                    "*/*=/WEB-INF/layout/header.jsp", "*/*=/WEB-INF/views/{1}/{2}.jsp", "*/*=/WEB-INF/layout/footer.jsp",
                    "popup=/WEB-INF/views/order/fragment.jsp", "orphan=/WEB-INF/views/order/list.jsp"));
            Node adminFile = ctx.graph().node(Ids.file(admin)).orElseThrow();
            // 다른 설정 파일의 정의를 extends한 것도 풀고, 하위 정의가 덮은 attribute는 덮은 값만 싣는다. Tiles 1의 <put>·path도 읽는다
            assertThat(adminFile.attrs().get("tilesDefinitions")).isEqualTo(List.of(
                    "admin.home=/WEB-INF/layout/main.jsp", "struts.old=/WEB-INF/layout/popup.jsp"));
            assertThat(adminFile.attrs().get("tilesAttributes")).isEqualTo(List.of(
                    "admin.home=/WEB-INF/layout/header.jsp", "admin.home=/WEB-INF/views/admin/home.jsp",
                    "admin.home=/WEB-INF/layout/admin-footer.jsp",
                    "struts.old=/WEB-INF/views/admin/old.jsp", "struts.old=/WEB-INF/views/admin/gone.jsp"));

            // 와일드카드가 없는 정의: template JSP → attribute JSP. 같은 대상은 한 번만
            assertThat(tilesTargets(ctx, layout + "/main.jsp")).containsExactly(
                    Ids.jspPage(layout + "/admin-footer.jsp"), Ids.jspPage(layout + "/footer.jsp"),
                    Ids.jspPage(layout + "/header.jsp"), Ids.jspPage(VIEWS + "/admin/home.jsp"),
                    Ids.jspPage(VIEWS + "/order/list.jsp"));
            Edge edge = Fixtures.include(ctx, layout + "/main.jsp", Ids.jspPage(VIEWS + "/order/list.jsp"));
            assertThat(edge.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(edge.attrString("via")).isEqualTo("tiles");
            assertThat(edge.attrString("path")).isEqualTo("/WEB-INF/views/order/list.jsp");
            assertThat(edge.location().file()).isEqualTo(config);
            assertThat(edge.location().startLine()).isEqualTo(12);
            // 와일드카드 정의의 attribute(/WEB-INF/views/{1}/{2}.jsp)로는 만들지 않는다(member/edit.jsp로 가는 엣지 없음)
            assertThat(tilesTargets(ctx, layout + "/main.jsp")).doesNotContain(Ids.jspPage(VIEWS + "/member/edit.jsp"));
            // 값이 다른 정의 이름이면 그 정의의 template으로, 없는 JSP는 미해결로
            assertThat(Fixtures.includes(ctx, layout + "/popup.jsp")).extracting(Edge::to).containsExactlyInAnyOrder(
                    Ids.jspPage(VIEWS + "/order/fragment.jsp"), Ids.jspPage(VIEWS + "/admin/old.jsp"),
                    "unresolved:jsp-include:" + VIEWS + "/admin/gone.jsp");
        }
    }
}
