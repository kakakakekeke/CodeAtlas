package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.TAGLIBS;
import static io.codeatlas.jsp.Fixtures.VIEWS;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
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
 * 4차 수정본을 공개 프로젝트에 다시 돌려 찾은 것(5차): 제출되지 않는 폼에 생긴 selfSubmit 요청(제출 흔적을 그 폼과 짝짓지 않음),
 * 쿼리 문자열 안의 EL 삼항이 경로가 같은 후보 둘로 갈려 추론이 됨.
 */
class JspAnalyzerCorpusRound5Test {

    private static List<Node> selfSubmits(AnalysisContext ctx, String page) {
        return ctx.graph().outgoing(Ids.jspPage(page), EdgeKind.REQUESTS).stream()
                .map(e -> ctx.graph().node(e.to()).orElseThrow())
                .filter(n -> n.kind() == NodeKind.HTTP_REQUEST && Boolean.TRUE.equals(n.attrs().get("selfSubmit"))).toList();
    }

    private static List<Integer> selfSubmitLines(AnalysisContext ctx, String page) {
        return selfSubmits(ctx, page).stream().map(n -> n.location().startLine()).sorted().toList();
    }

    @Test
    @DisplayName("F-011-AC33 selfSubmit 요청은 제출 흔적이 그 폼을 가리킬 때만 만든다: 다른 id·name의 .submit(), return false로 끝나는 제출 버튼, 다른 창의 폼은 흔적이 아니고, 수신자를 모르는 .submit()은 action 없는 폼이 하나뿐일 때만 흔적이다")
    void submitTracesArePairedWithTheForm() {
        String otherId = VIEWS + "/modules/oa/testAuditView.jsp";
        String blocked = VIEWS + "/utl/sys/htm/EgovComUtlHttpMonDetailLog.jsp";
        String byParameter = VIEWS + "/dam/app/EgovComDamAppraisalModify.jsp";
        String byHref = VIEWS + "/sym/ccm/acr/EgovAdministCodeRegist.jsp";
        String several = VIEWS + "/multi/several.jsp";
        String single = VIEWS + "/multi/single.jsp";
        String handlers = VIEWS + "/multi/handlers.jsp";
        String elsewhere = VIEWS + "/multi/elsewhere.jsp";
        String variables = VIEWS + "/multi/variables.jsp";
        String commented = VIEWS + "/multi/commented.jsp";
        try (TestProject project = TestProject.create()
                // jeesite: 검증 플러그인의 submitHandler는 #inputForm의 것인데 이 페이지에는 그 id의 폼이 없다
                .file(otherId, TAGLIBS + """
                        <script type="text/javascript">
                        $(document).ready(function() {
                            $("#inputForm").validate({
                                submitHandler: function(form){
                                    loading('...');
                                    form.submit();
                                }
                            });
                        });
                        </script>
                        <form:form class="form-horizontal">
                          <input id="btnCancel" class="btn" type="button" value="back" onclick="history.go(-1)"/>
                        </form:form>
                        """)
                // 폼 안의 유일한 제출 버튼이 제출을 막는다
                .file(blocked, TAGLIBS + """
                        <script type="text/javascript">
                        function fnList() { location.href = "<c:url value='/utl/sys/htm/list.do'/>"; }
                        </script>
                        <form name="Form" action="" method="post">
                          <input class="s_submit" type="submit" value="<spring:message code="button.list" />" onclick="fnList(); return false;" />
                          <button onClick="fnList();return false">목록</button>
                        </form>
                        """)
                // 제출 버튼은 막지만 폼을 인자로 받은 함수가 제출한다
                .file(byParameter, TAGLIBS + """
                        <script type="text/javascript">
                        function fn_egov_modify(form){
                            if(confirm("<spring:message code="common.save.msg" />")){
                                form.submit();
                            }
                        }
                        </script>
                        <form:form modelAttribute="knoAppraisal" name="knoAppraisal" method="post">
                          <input class="s_submit" type="submit" value="<spring:message code="button.save" />" onclick="fn_egov_modify(document.forms[0]); return false;" />
                        </form:form>
                        """)
                .file(byHref, TAGLIBS + """
                        <script type="text/javaScript" language="javascript">
                        <!--
                        /* 등록처리: it's */
                        function fn_egov_regist(form){
                            if(!validate(form)){ return; } else { form.submit(); }
                        }
                        -->
                        </script>
                        <form:form modelAttribute="AdministCode" name="AdministCode" method="post">
                          <a href="JavaScript:javascript:fn_egov_regist(document.AdministCode);">저장</a>
                        </form:form>
                        """)
                // 폼이 여럿: 이름·ID가 맞는 폼만. 수신자를 모르는 .submit()은 어느 폼의 것도 아니다
                .file(several, TAGLIBS + """
                        <form name="a" method="post"><input type="text" name="x"/></form>
                        <form name="b" method="post"><input type="text" name="x"/></form>
                        <form id="c" method="post"><input type="text" name="x"/></form>
                        <form id="d" method="post"><a class="go">go</a></form>
                        <form name="e" method="post"><input type="text" name="x"/></form>
                        <script>
                        function saveB() { document.b.submit(); }
                        function saveC() { $("#c").attr("target", "_self").submit(); }
                        $(".go").click(function(){ $(this).parents("form").submit(); });
                        function saveE() { document.forms[4].submit(); }
                        </script>
                        """)
                .file(single, TAGLIBS + """
                        <form class="only" method="post"><a class="go">go</a></form>
                        <form name="withAction" action="${ctx}/x/list.do" method="post"><input type="submit"/></form>
                        <script>
                        $(".go").click(function(){ $(this).parents("form").submit(); });
                        </script>
                        """)
                // 이벤트 처리기 안의 this.form·form은 그 요소가 든 폼이다. onsubmit이 막는 폼은 버튼으로 제출되지 않는다
                .file(handlers, TAGLIBS + """
                        <form name="f1" method="post"><select name="s" onchange="this.form.submit()"></select></form>
                        <form name="f2" method="post" onsubmit="return false;"><input type="submit"/></form>
                        <form name="f3" method="post"><a href="#" onclick="go(this); return false;">go</a></form>
                        <form name="f4" method="post"><input type="text"/></form>
                        <script>
                        function go(el) { el.form.submit(); }
                        </script>
                        """)
                .file(elsewhere, TAGLIBS + """
                        <form name="frm" method="post"><input type="text" name="x"/></form>
                        <script>
                        function done() { opener.document.frm.submit(); window.close(); }
                        function other() { document.other.submit(); document.getElementById("nope").submit(); }
                        </script>
                        """)
                .file(variables, TAGLIBS + """
                        <form id="vf" method="post"><input type="text" name="x"/></form>
                        <form id="vg" method="post"><input type="text" name="x"/></form>
                        <form name="legacy" method="post"><input type="text" name="x"/></form>
                        <script>
                        function save() { var f = document.getElementById("vf"); f.submit(); }
                        function old() { legacy.submit(); }
                        </script>
                        """)
                .file(commented, TAGLIBS + """
                        <form name="cm" method="post"><input type="text" name="x"/></form>
                        <script>
                        function save() {
                            // document.cm.submit();
                            /* document.cm.submit(); */
                            $.post("${ctx}/x/save.do", $("form").serialize());
                        }
                        </script>
                        <%-- <script>document.cm.submit();</script> --%>
                        <!-- <input type="submit" onclick="document.cm.submit()"/> -->
                        """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(selfSubmits(ctx, otherId)).isEmpty();
            assertThat(selfSubmits(ctx, blocked)).isEmpty();
            assertThat(selfSubmits(ctx, byParameter)).extracting(Fixtures::requestKey).containsExactly("POST {self}");
            assertThat(selfSubmits(ctx, byHref)).extracting(Fixtures::requestKey).containsExactly("POST {self}");
            Node request = selfSubmits(ctx, byParameter).get(0);
            assertThat(ctx.graph().edges(Ids.jspPage(byParameter), EdgeKind.REQUESTS, request.id()).get(0).confidence())
                    .isEqualTo(Confidence.INFERRED);
            // TAGLIBS가 6줄: 폼 b는 8행, c는 9행, e는 11행
            assertThat(selfSubmitLines(ctx, several)).containsExactly(8, 9, 11);
            assertThat(selfSubmitLines(ctx, single)).containsExactly(7);
            assertThat(selfSubmitLines(ctx, handlers)).containsExactly(7, 9);
            assertThat(selfSubmits(ctx, elsewhere)).isEmpty();
            assertThat(selfSubmitLines(ctx, variables)).containsExactly(7, 9);
            assertThat(selfSubmits(ctx, commented)).isEmpty();
            // 폼의 수는 요청을 만들었는지와 상관없이 그대로 센다
            assertThat(ctx.graph().node(Ids.jspPage(otherId)).orElseThrow().attrs()).containsEntry("selfPostForms", 1);
        }
    }

    @Test
    @DisplayName("F-011-AC34 쿼리 문자열 안의 EL 삼항은 후보로 펼치지 않는다: 경로가 하나로 정해지므로 요청 하나(쿼리는 {*})이고 추론이 아니다")
    void elTernaryInQueryIsNotExpanded() {
        String page = VIEWS + "/modules/cms/fileList.jsp";
        try (TestProject project = TestProject.create().file(page, """
                <c:set var="ctx" value="${pageContext.request.contextPath}"/>
                <a href="${ctx}/cms/file/list?type=${type eq 'thumb'?'1':'0'}&sm=${multi?1:0}">목록</a>
                <a href="${ctx}/cms/${own ? 'my' : 'all'}/list?dts=${type eq 'thumb'?'1':'0'}">경로와 쿼리 모두</a>
                """)) {
            AnalysisContext ctx = project.run(new JspAnalyzer());

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder("GET /cms/file/list", "GET /cms/my/list",
                    "GET /cms/all/list");
            Node list = Fixtures.request(ctx, "GET /cms/file/list");
            assertThat(list.attrString("query")).isEqualTo("type={*}&sm={*}");
            assertThat(ctx.graph().edges(Ids.jspPage(page), EdgeKind.REQUESTS, list.id()).get(0).confidence())
                    .isEqualTo(Confidence.RESOLVED);
            // 경로의 삼항은 종전대로 후보 둘(추론)이고, 그 URL의 쿼리 삼항은 {*}로 남는다
            Node mine = Fixtures.request(ctx, "GET /cms/my/list");
            assertThat(mine.attrString("query")).isEqualTo("dts={*}");
            assertThat(ctx.graph().edges(Ids.jspPage(page), EdgeKind.REQUESTS, mine.id()).get(0).confidence())
                    .isEqualTo(Confidence.INFERRED);
        }
    }
}
