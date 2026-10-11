package io.codeatlas.jsp;

import static io.codeatlas.jsp.Fixtures.TAGLIBS;
import static io.codeatlas.jsp.Fixtures.VIEWS;
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

class JspAnalyzerRequestTest {

    private static final String LIST = VIEWS + "/order/list.jsp";

    private static AnalysisContext analyze(TestProject project) {
        return project.run(new JspAnalyzer());
    }

    @Test
    @DisplayName("F-011-AC7 폼, form:form, 링크, 프레임, 리다이렉트, meta refresh를 HTTP_REQUEST와 REQUESTS로 기록한다")
    void requestElements() {
        // TAGLIBS는 6줄이다. 아래 본문은 7번째 줄부터.
        try (TestProject project = TestProject.create().file(LIST, TAGLIBS + """
                <html><head><meta http-equiv="Refresh" content="600; URL=${ctx}/session/extend.do"></head><body>
                <form id="searchForm" name="searchForm" action="<c:url value='/order/list.do'/>" method="get">
                    <input type="text" name="memberNm" value="<c:out value='${cond.memberNm}'/>">
                    <button type="submit">검색</button>
                </form>
                <form name="excelForm" action="/order/excel.do"></form>
                <form:form action="${ctx}/order/save.do" modelAttribute="orderVO" id="orderForm">
                    <form:input path="memberId"/>
                </form:form>
                <form:form action="/order/update.do" method="put" commandName="order"></form:form>
                <a href="${ctx}/order/form.do" class="btn">주문 등록</a>
                <iframe src="/popup/zipcode.do" name="zipFrame"></iframe>
                <frame src="<%=request.getContextPath()%>/menu/left.do">
                <c:if test="${empty sessionScope.loginMember}"><c:redirect url="/login.do"/></c:if>
                </body></html>
                """)) {
            AnalysisContext ctx = analyze(project);

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder(
                    "GET /session/extend.do",
                    "GET /order/list.do",
                    "GET /order/excel.do",
                    "POST /order/save.do",
                    "PUT /order/update.do",
                    "GET /order/form.do",
                    "GET /popup/zipcode.do",
                    "GET /menu/left.do",
                    "GET /login.do");

            Node search = Fixtures.request(ctx, "GET /order/list.do");
            assertThat(search.id()).isEqualTo(Ids.httpRequest("GET", "/order/list.do", LIST, 8));
            assertThat(search.kind()).isEqualTo(NodeKind.HTTP_REQUEST);
            assertThat(search.attrString("httpMethod")).isEqualTo("GET");
            assertThat(search.attrString("url")).isEqualTo("<c:url value='/order/list.do'/>");
            assertThat(search.attrString("path")).isEqualTo("/order/list.do");
            assertThat(search.attrString("api")).isEqualTo("form");
            assertThat(search.attrBoolean("dynamic")).isFalse();
            assertThat(search.location().startLine()).isEqualTo(8);

            List<Edge> edges = ctx.graph().edges(Ids.jspPage(LIST), EdgeKind.REQUESTS, search.id());
            assertThat(edges).hasSize(1);
            assertThat(edges.get(0).confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(edges.get(0).location().startLine()).isEqualTo(8);
            assertThat(edges.get(0).location().startColumn()).isEqualTo(1);

            assertThat(Fixtures.request(ctx, "GET /order/excel.do").attrString("api")).isEqualTo("form");
            Node save = Fixtures.request(ctx, "POST /order/save.do");
            assertThat(save.attrString("api")).isEqualTo("form");
            assertThat(save.attrString("modelAttribute")).isEqualTo("orderVO");
            assertThat(Fixtures.request(ctx, "PUT /order/update.do").attrString("modelAttribute")).isEqualTo("order");
            assertThat(Fixtures.request(ctx, "GET /order/form.do").attrString("api")).isEqualTo("link");
            assertThat(Fixtures.request(ctx, "GET /popup/zipcode.do").attrString("api")).isEqualTo("frame");
            assertThat(Fixtures.request(ctx, "GET /menu/left.do").attrString("api")).isEqualTo("frame");
            assertThat(Fixtures.request(ctx, "GET /login.do").attrString("api")).isEqualTo("redirect");
            assertThat(Fixtures.request(ctx, "GET /session/extend.do").attrString("api")).isEqualTo("refresh");
            assertThat(ctx.graph().edges(EdgeKind.REQUESTS)).hasSize(9)
                    .allMatch(e -> e.from().equals(Ids.jspPage(LIST)));
        }
    }

    @Test
    @DisplayName("F-011-AC8 컨텍스트 경로 제거, 쿼리·fragment 분리, 경로 중간의 동적 조각은 {*}")
    void urlNormalization() {
        try (TestProject project = TestProject.create().file(LIST, TAGLIBS + """
                <a href="${pageContext.request.contextPath}/member/list.do">회원</a>
                <a href="${ctx}/order/detail.do?orderId=${o.orderId}&amp;page=1#top">상세</a>
                <a href="<c:url value='/order/${o.orderId}/view'/>">보기</a>
                <a href="<%=request.getContextPath()%>/board/list.do?page=<%=i%>">게시판</a>
                <a href='<c:url value="/order/print.do"><c:param name="orderId" value="${o.orderId}"/></c:url>'>인쇄</a>
                <a href="${ctx}/member/${m.memberId}/orders/${o.orderId}">주문</a>
                <a href="<%=ctx%>/legacy/main.do">구형</a>
                <a href="${ctx}">홈</a>
                <a href="${o.targetUrl}/items">동적 접두</a>
                """)) {
            AnalysisContext ctx = analyze(project);

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder(
                    "GET /member/list.do",
                    "GET /order/detail.do",
                    "GET /order/{*}/view",
                    "GET /board/list.do",
                    "GET /order/print.do",
                    "GET /member/{*}/orders/{*}",
                    "GET /legacy/main.do",
                    "GET /",
                    "GET {*}/items");

            Node detail = Fixtures.request(ctx, "GET /order/detail.do");
            assertThat(detail.attrString("query")).isEqualTo("orderId={*}&page=1");
            assertThat(detail.attrString("url")).isEqualTo("${ctx}/order/detail.do?orderId=${o.orderId}&amp;page=1#top");
            assertThat(detail.attrBoolean("dynamic")).isFalse();
            assertThat(detail.attr("relative")).isNull();
            assertThat(detail.attr("external")).isNull();

            assertThat(Fixtures.request(ctx, "GET /order/{*}/view").attrBoolean("dynamic")).isTrue();
            assertThat(Fixtures.request(ctx, "GET /board/list.do").attrString("query")).isEqualTo("page={*}");
            assertThat(Fixtures.request(ctx, "GET /member/{*}/orders/{*}").attrBoolean("dynamic")).isTrue();
            assertThat(Fixtures.request(ctx, "GET {*}/items").attrBoolean("dynamic")).isTrue();
            assertThat(Fixtures.request(ctx, "GET /member/list.do").attr("query")).isNull();
        }
    }

    @Test
    @DisplayName("F-011-AC8 c:url·spring:url의 var로 만든 변수를 뒤의 ${var}에서 푼다")
    void urlVariables() {
        try (TestProject project = TestProject.create().file(LIST, TAGLIBS + """
                <a href="${excelUrl}">정의보다 앞</a>
                <c:url value="/order/excel.do" var="excelUrl"/>
                <spring:url value="/order/print/${order.id}" var="printUrl" htmlEscape="true"/>
                <c:url var="pagingUrl" value="/order/list.do">
                    <c:param name="memberNm" value="${cond.memberNm}"/>
                </c:url>
                <a href="${excelUrl}">엑셀</a>
                <a href="${printUrl}?copies=2">인쇄</a>
                <form action="${pagingUrl}" method="post"></form>
                <c:forEach var="p" begin="1" end="${totalPage}">
                    <a href="${pagingUrl}&pageNo=${p}">${p}</a>
                </c:forEach>
                """)) {
            AnalysisContext ctx = analyze(project);

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder(
                    "GET /order/excel.do", "GET /order/print/{*}", "POST /order/list.do", "GET /order/list.do");
            assertThat(Fixtures.request(ctx, "GET /order/print/{*}").attrString("query")).isEqualTo("copies=2");
            // 정의보다 앞에서 쓴 변수는 풀지 못한다
            assertThat(ctx.graph().outgoing(Ids.jspPage(LIST), EdgeKind.REQUESTS))
                    .filteredOn(e -> e.confidence() == Confidence.UNRESOLVED)
                    .extracting(Edge::to).containsExactly(Ids.unresolved("http-url", "${excelUrl}"));
        }
    }

    @Test
    @DisplayName("F-011-AC8 외부 URL은 external, 상대 URL은 원문 그대로 relative")
    void externalAndRelative() {
        try (TestProject project = TestProject.create().file(LIST, TAGLIBS + """
                <a href="https://www.example.com/help/guide?lang=ko">도움말</a>
                <a href="//static.example.com/terms">약관</a>
                <a href="list.do">목록</a>
                <a href="../order/view.do?orderId=1">보기</a>
                <form action="save.do" method="POST"></form>
                <a href="<c:url value='popup/zip.do'/>">우편번호</a>
                """)) {
            AnalysisContext ctx = analyze(project);

            assertThat(Fixtures.requestKeys(ctx)).containsExactlyInAnyOrder(
                    "GET https://www.example.com/help/guide",
                    "GET //static.example.com/terms",
                    "GET list.do",
                    "GET ../order/view.do",
                    "POST save.do",
                    "GET popup/zip.do");
            Node help = Fixtures.request(ctx, "GET https://www.example.com/help/guide");
            assertThat(help.attrBoolean("external")).isTrue();
            assertThat(help.attrString("query")).isEqualTo("lang=ko");
            assertThat(help.attr("relative")).isNull();
            assertThat(Fixtures.request(ctx, "GET //static.example.com/terms").attrBoolean("external")).isTrue();
            assertThat(Fixtures.request(ctx, "GET list.do").attrBoolean("relative")).isTrue();
            assertThat(Fixtures.request(ctx, "GET ../order/view.do").attrBoolean("relative")).isTrue();
            assertThat(Fixtures.request(ctx, "GET ../order/view.do").attrString("query")).isEqualTo("orderId=1");
            assertThat(Fixtures.request(ctx, "POST save.do").attrBoolean("relative")).isTrue();
            assertThat(Fixtures.request(ctx, "GET popup/zip.do").attrBoolean("relative")).isTrue();
        }
    }

    @Test
    @DisplayName("F-011-AC8 #, javascript:, mailto:, tel:, 정적 자원, 빈 값, 주석 안의 링크는 요청이 아니다")
    void excluded() {
        try (TestProject project = TestProject.create().file(LIST, TAGLIBS + """
                <a href="#">맨 위</a>
                <a href="#section2">2절</a>
                <a href="javascript:void(0);">없음</a>
                <a href="mailto:admin@example.com">메일</a>
                <a href="tel:02-000-0000">전화</a>
                <a href="${ctx}/css/print.css">css</a>
                <a href="/images/logo.PNG?v=2">로고</a>
                <a href="">빈 값</a>
                <a name="anchor">앵커</a>
                <a href="?pageNo=2">2</a>
                <form name="noAction" method="post"></form>
                <iframe src="about:blank"></iframe>
                <%-- <a href="/admin/secret.do">관리자</a> --%>
                <!-- <a href="/admin/old.do">옛 링크</a> -->
                <a href="/order/real.do">진짜</a>
                """)) {
            AnalysisContext ctx = analyze(project);

            assertThat(Fixtures.requestKeys(ctx)).containsExactly("GET /order/real.do");
            assertThat(ctx.graph().nodes(NodeKind.UNRESOLVED)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-011-AC9 전부 동적인 URL은 미해결 엣지(http-url)로 남긴다")
    void fullyDynamic() {
        try (TestProject project = TestProject.create().file(LIST, TAGLIBS + """
                <a href="${detailUrl}">상세</a>
                <form action="<%= target %>" method="post"></form>
                <a href="${menu.url}?menuId=${menu.id}">메뉴</a>
                <a href="${ctx}/${menu.path}">메뉴2</a>
                """)) {
            AnalysisContext ctx = analyze(project);

            assertThat(ctx.graph().nodes(NodeKind.HTTP_REQUEST)).isEmpty();
            List<Edge> edges = ctx.graph().outgoing(Ids.jspPage(LIST), EdgeKind.REQUESTS);
            assertThat(edges).hasSize(4).allMatch(e -> e.confidence() == Confidence.UNRESOLVED);
            assertThat(edges).extracting(Edge::to).containsExactly(
                    Ids.unresolved("http-url", "${detailUrl}"),
                    Ids.unresolved("http-url", "<%= target %>"),
                    Ids.unresolved("http-url", "${menu.url}?menuId=${menu.id}"),
                    Ids.unresolved("http-url", "${ctx}/${menu.path}"));
            assertThat(edges.get(0).location().startLine()).isEqualTo(7);
            assertThat(ctx.graph().node(edges.get(0).to()).orElseThrow().attrString("category")).isEqualTo("http-url");
            assertThat(ctx.graph().diagnostics())
                    .filteredOn(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(4);
        }
    }

    @Test
    @DisplayName("F-011-AC2 깨진 태그가 섞인 구형 페이지에서도 뒤의 요청을 뽑는다")
    void brokenLegacyPage() {
        try (TestProject project = TestProject.create().file(LIST, """
                <%@ page contentType="text/html; charset=EUC-KR" %>
                <% String ctx = request.getContextPath();
                   if (session.getAttribute("user") == null) { %>
                <table><tr><td><font color="red">로그인 필요</td></tr>
                <% } else { %>
                <a href="<%=ctx%>/board/write.do>글쓰기</a>
                <div class="list"
                <form name=frm action=<%=ctx%>/board/search.do method=post>
                <input type=text name=keyword value="<%= request.getParameter("keyword") %>">
                </table>
                <% } %>
                <a href='<%=ctx%>/board/list.do'>목록</a></div></span>
                """)) {
            AnalysisContext ctx = analyze(project);

            assertThat(Fixtures.requestKeys(ctx))
                    .contains("POST /board/search.do", "GET /board/list.do", "GET /board/write.do");
            assertThat(ctx.graph().diagnostics()).noneMatch(d -> d.code().equals(DiagnosticCodes.PARSE_FAILED));
            assertThat(ctx.graph().node(Ids.jspPage(LIST)).orElseThrow().attrInt("scriptletCount", 0)).isEqualTo(7);
        }
    }
}
