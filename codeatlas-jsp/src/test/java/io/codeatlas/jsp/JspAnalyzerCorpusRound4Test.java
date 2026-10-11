package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.TAGLIBS;
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

/**
 * 3차 수정본을 공개 프로젝트에 다시 돌려 찾은 것(4차): 동적 SiteMesh 데코레이터가 다른 테마의 레이아웃에도 이어짐,
 * action 없는 폼(현재 URL로 제출), hidden input에 든 URL.
 */
class JspAnalyzerCorpusRound4Test {

    private static List<Edge> sitemesh(AnalysisContext ctx, String page) {
        return Fixtures.includes(ctx, page).stream().filter(e -> "sitemesh".equals(e.attrString("via"))).toList();
    }

    private static List<Node> selfSubmits(AnalysisContext ctx, String page) {
        return ctx.graph().outgoing(Ids.jspPage(page), EdgeKind.REQUESTS).stream()
                .map(e -> ctx.graph().node(e.to()).orElseThrow())
                .filter(n -> n.kind() == NodeKind.HTTP_REQUEST && Boolean.TRUE.equals(n.attrs().get("selfSubmit"))).toList();
    }

    @Test
    @DisplayName("F-011-AC30 이름에 EL이 든 SiteMesh 데코레이터: 후보가 여럿이면 페이지와 공통 디렉터리가 가장 긴 데코레이터 하나만 잇고(dynamicDecorator), 가리지 못하면 후보 전부에 ambiguous=true")
    void dynamicDecoratorPicksOwnTheme() {
        String themes = VIEWS + "/modules/cms/front/themes";
        String basicPage = themes + "/basic/frontList.jsp";
        String weixinPage = themes + "/weixin/frontList.jsp";
        String outside = VIEWS + "/modules/cms/front/index.jsp";
        String single = VIEWS + "/modules/sys/one.jsp";
        String fixed = VIEWS + "/modules/sys/fixed.jsp";
        String meta = "<html><head>\n<meta name=\"decorator\" content=\"cms_default_${site.theme}\"/>\n</head></html>\n";
        try (TestProject project = TestProject.create()
                .file(WEB + "/WEB-INF/decorators.xml", """
                        <decorators defaultdir="/WEB-INF/views">
                        	<decorator name="default" page="layouts/default.jsp" />
                        	<decorator name="cms_default_basic" page="modules/cms/front/themes/basic/layouts/default.jsp" />
                        	<decorator name="cms_default_weixin" page="modules/cms/front/themes/weixin/layouts/default.jsp" />
                        	<decorator name="pop_small" page="layouts/pop.jsp" />
                        </decorators>
                        """)
                .file(VIEWS + "/layouts/default.jsp", "<html><sitemesh:body/></html>\n")
                .file(VIEWS + "/layouts/pop.jsp", "<html><sitemesh:body/></html>\n")
                .file(themes + "/basic/layouts/default.jsp", "<html><sitemesh:body/></html>\n")
                .file(themes + "/weixin/layouts/default.jsp", "<html><sitemesh:body/></html>\n")
                .file(basicPage, meta)
                .file(weixinPage, meta)
                .file(outside, meta)
                .file(single, "<html><head><meta name=\"decorator\" content=\"pop_${size}\"/></head></html>\n")
                .file(fixed, "<html><head><meta name=\"decorator\" content=\"default\"/></head></html>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            // 같은 themes/<x>/ 아래의 데코레이터 하나만
            assertThat(sitemesh(ctx, basicPage)).extracting(Edge::to)
                    .containsExactly(Ids.jspPage(themes + "/basic/layouts/default.jsp"));
            assertThat(sitemesh(ctx, weixinPage)).extracting(Edge::to)
                    .containsExactly(Ids.jspPage(themes + "/weixin/layouts/default.jsp"));
            Edge picked = sitemesh(ctx, basicPage).get(0);
            assertThat(picked.confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(picked.attrs()).containsEntry("dynamicDecorator", true).doesNotContainKey("ambiguous");
            assertThat(picked.attrString("path")).isEqualTo("cms_default_${site.theme}");
            // 두 테마와 공통 디렉터리 길이가 같은 페이지: 가리지 못하므로 후보 전부, ambiguous
            assertThat(sitemesh(ctx, outside)).extracting(Edge::to).containsExactlyInAnyOrder(
                    Ids.jspPage(themes + "/basic/layouts/default.jsp"), Ids.jspPage(themes + "/weixin/layouts/default.jsp"));
            assertThat(sitemesh(ctx, outside)).allSatisfy(e -> {
                assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
                assertThat(e.attrs()).containsEntry("dynamicDecorator", true).containsEntry("ambiguous", true);
            });
            // 후보가 하나뿐인 동적 이름
            assertThat(sitemesh(ctx, single)).extracting(Edge::to).containsExactly(Ids.jspPage(VIEWS + "/layouts/pop.jsp"));
            assertThat(sitemesh(ctx, single).get(0).attrs()).containsEntry("dynamicDecorator", true).doesNotContainKey("ambiguous");
            // 정적인 이름에는 두 표시가 없다
            assertThat(sitemesh(ctx, fixed).get(0).attrs()).doesNotContainKeys("dynamicDecorator", "ambiguous");
        }
    }

    @Test
    @DisplayName("F-011-AC31 action 없는 폼(현재 URL로 제출): 폼마다 selfSubmit=true인 HTTP_REQUEST({self}, api=form, 폼의 메서드)와 INFERRED REQUESTS를 만든다. 스크립트가 action을 대입하거나 제출하는 흔적이 없으면 만들지 않는다")
    void selfSubmitForms() {
        String regist = VIEWS + "/sym/ccm/EgovCcmCmmnCodeRegist.jsp";
        String search = VIEWS + "/sym/ccm/EgovSearch.jsp";
        String scripted = VIEWS + "/cop/bbs/EgovNoticeRegist.jsp";
        String ajax = VIEWS + "/manage/article/create.jsp";
        String button = VIEWS + "/sym/ccm/EgovButton.jsp";
        try (TestProject project = TestProject.create()
                .file(regist, TAGLIBS + """
                        <script type="text/javascript">
                        function fn_egov_regist(form) {
                            if (confirm("저장?")) { form.submit(); }
                        }
                        </script>
                        <form:form modelAttribute="cmmnCode" name="cmmnCode" method="post">
                          <form:input path="codeId"/>
                          <a href="#" onclick="fn_egov_regist(document.cmmnCode); return false;">저장</a>
                        </form:form>
                        <form:form commandName="second">
                          <input type="submit" value="저장"/>
                        </form:form>
                        """)
                .file(search, TAGLIBS + """
                        <FORM name="searchForm">
                          <input type="text" name="keyword"/>
                          <input type="image" src="/images/btn.gif"/>
                        </FORM>
                        <form name="withAction" action="${ctx}/sym/ccm/list.do" method="post">
                          <input type="submit"/>
                        </form>
                        <form name="blank" action="" method="${m}">
                          <button>보내기</button>
                        </form>
                        """)
                .file(scripted, TAGLIBS + """
                        <script type="text/javascript">
                        function fn_regist() {
                            document.board.action = "<c:url value='/cop/bbs/insertBoardArticle.do'/>";
                            document.board.submit();
                        }
                        </script>
                        <form:form modelAttribute="board" name="board" method="post"></form:form>
                        """)
                .file(ajax, TAGLIBS + """
                        <form id="createForm" method="post">
                          <input type="text" name="title"/>
                          <a href="javascript:;" onclick="createSubmit();">저장</a>
                          <button type="button" onclick="createSubmit();">저장</button>
                        </form>
                        <script>
                        function createSubmit() {
                            $.ajax({ type: 'post', url: '${ctx}/manage/article/create', data: $('#createForm').serialize() });
                        }
                        </script>
                        """)
                .file("src/main/resources/static/mock/search.html", """
                        <html><body>
                        <form><input type="text" name="q"/><button>검색</button></form>
                        <form method="post"><input type="submit"/></form>
                        <form th:action="@{/mock/save}" method="post"><input type="submit"/></form>
                        </body></html>
                        """)
                .file(button, TAGLIBS + """
                        <form name="byAttr" method="post"><input type="submit"/></form>
                        <script>$('form[name=byAttr]').attr('action', '${ctx}/x/save.do');</script>
                        """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            List<Node> forms = selfSubmits(ctx, regist);
            assertThat(forms).extracting(Fixtures::requestKey).containsExactly("POST {self}", "POST {self}");
            Node first = forms.get(0);
            assertThat(first.id()).isEqualTo("http:request:POST {self}@" + regist + ":12");
            assertThat(first.attrs()).containsEntry("api", "form").containsEntry("httpMethod", "POST")
                    .containsEntry("path", "{self}").containsEntry("selfSubmit", true).containsEntry("dynamic", false)
                    .containsEntry("modelAttribute", "cmmnCode").containsEntry("url", "");
            assertThat(forms.get(1).attrs()).containsEntry("modelAttribute", "second");
            assertThat(ctx.graph().edges(Ids.jspPage(regist), EdgeKind.REQUESTS, first.id()).get(0).confidence())
                    .isEqualTo(Confidence.INFERRED);
            // HTML 폼(메서드 없음 → GET, EL 메서드 → ANY). action이 있는 폼은 종전대로이고 selfSubmit이 아니다
            assertThat(selfSubmits(ctx, search)).extracting(Fixtures::requestKey).containsExactly("GET {self}", "ANY {self}");
            assertThat(Fixtures.request(ctx, "POST /sym/ccm/list.do").attrs()).doesNotContainKey("selfSubmit");
            // 스크립트가 action을 대입하는 페이지, 제출 흔적이 없는 페이지(AJAX로 보낸다)에는 만들지 않는다
            assertThat(selfSubmits(ctx, scripted)).isEmpty();
            assertThat(selfSubmits(ctx, ajax)).isEmpty();
            assertThat(selfSubmits(ctx, button)).isEmpty();
            // HTML 파일: 정적 페이지의 GET 폼은 그 파일을 다시 여는 것이라 만들지 않는다. POST 폼만 만든다
            assertThat(selfSubmits(ctx, "src/main/resources/static/mock/search.html")).extracting(Fixtures::requestKey)
                    .containsExactly("POST {self}");
            // 건수는 종전대로 남는다
            assertThat(ctx.graph().node(Ids.jspPage(regist)).orElseThrow().attrs()).containsEntry("selfPostForms", 2);
            assertThat(ctx.graph().node(Ids.jspPage(scripted)).orElseThrow().attrs()).containsEntry("selfPostForms", 1);
        }
    }

    @Test
    @DisplayName("F-011-AC32 hidden·text input의 경로처럼 보이는 정적 value를 페이지의 formFields(<폼 키>.<필드>=<경로>, #<id>=<경로>)로 싣는다")
    void hiddenUrlFields() {
        String page = VIEWS + "/cmm/uss/umt/EgovUserInsert.jsp";
        String none = VIEWS + "/cmm/uss/umt/EgovNone.jsp";
        try (TestProject project = TestProject.create()
                .file(page, TAGLIBS + """
                        <form:form modelAttribute="userManageVO" name="userManageVO" method="post" action="${ctx}/uss/umt/EgovUserInsert.do">
                          <input type="hidden" name="zip_url" value="<c:url value='/sym/cmm/EgovCcmZipSearchPopup.do'/>" />
                          <input type="hidden" name="cal_url" id="calUrl" value="${pageContext.request.contextPath}/sym/cmm/EgovNormalCalPopup.do?mode=1" />
                          <INPUT TYPE="text" NAME="plain" VALUE="/sym/cmm/list">
                          <input type="hidden" name="single" value="/list"/>
                          <input type="hidden" name="page" value="/sym/cmm/view.jsp"/>
                          <input type="hidden" name="dyn" value="${ctx}/sym/${kind}/x.do"/>
                          <input type="hidden" name="userId" value="${user.id}"/>
                          <input type="hidden" name="date" value="2020/01/01"/>
                          <input type="hidden" name="ext" value="http://example.com/a/b"/>
                          <input type="checkbox" name="chk" value="/sym/cmm/chk.do"/>
                          <input type="hidden" name="${n}" value="/sym/cmm/elname.do"/>
                        </form:form>
                        <input type="hidden" id="outside" value="<c:url value='/sym/cmm/outside.do'/>"/>
                        <input type="hidden" name="loose" value="/a/b.do"/>
                        <form id="byId"><input name="go" value="/x/y/go.do"/></form>
                        """)
                .file(none, TAGLIBS + "<form name=\"f\"><input type=\"hidden\" name=\"id\" value=\"${id}\"/></form>\n")) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(ctx.graph().node(Ids.jspPage(page)).orElseThrow().attrs().get("formFields")).isEqualTo(List.of(
                    "userManageVO.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do",
                    "#userManageVO.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do",
                    "userManageVO.cal_url=/sym/cmm/EgovNormalCalPopup.do?mode=1",
                    "#userManageVO.cal_url=/sym/cmm/EgovNormalCalPopup.do?mode=1",
                    "#calUrl=/sym/cmm/EgovNormalCalPopup.do?mode=1",
                    "userManageVO.plain=/sym/cmm/list",
                    "#userManageVO.plain=/sym/cmm/list",
                    "#outside=/sym/cmm/outside.do",
                    ".loose=/a/b.do",
                    "#byId.go=/x/y/go.do"));
            assertThat(ctx.graph().node(Ids.jspPage(none)).orElseThrow().attrs()).doesNotContainKey("formFields");
            // 필드 값은 요청이 아니다
            assertThat(Fixtures.requestKeys(ctx)).containsExactly("POST /uss/umt/EgovUserInsert.do");
        }
    }
}
