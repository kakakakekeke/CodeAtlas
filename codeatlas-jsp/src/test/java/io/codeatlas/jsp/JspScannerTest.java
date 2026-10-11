package io.codeatlas.jsp;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.jsp.JspToken.Type;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JspScannerTest {

    private static List<JspToken> scan(String text) {
        return JspScanner.scan(text, JspScanner.Mode.JSP);
    }

    private static List<JspToken> ofType(List<JspToken> tokens, Type type) {
        return tokens.stream().filter(t -> t.type() == type).toList();
    }

    private static List<String> openTagNames(List<JspToken> tokens) {
        return ofType(tokens, Type.OPEN_TAG).stream().map(JspToken::name).toList();
    }

    @Test
    @DisplayName("F-011-AC1 지시자, 스크립틀릿, 식, 선언, 주석, EL을 구분한다")
    void jspElements() {
        String text = """
                <%@ page language="java" contentType="text/html; charset=UTF-8" %>
                <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                <%! private int count = 0; %>
                <% String today = "x"; %>
                <%-- 주석 안의 <a href="/hidden.do">링크</a> <% int x; %> --%>
                <!-- HTML 주석 <a href="/hidden2.do">링크</a> -->
                <p><%= today %> ${user.name} #{deferred}</p>
                """;
        List<JspToken> tokens = scan(text);

        List<JspToken> directives = ofType(tokens, Type.DIRECTIVE);
        assertThat(directives).extracting(JspToken::name).containsExactly("page", "taglib");
        assertThat(directives.get(1).attr("prefix")).isEqualTo("c");
        assertThat(directives.get(1).attr("uri")).isEqualTo("http://java.sun.com/jsp/jstl/core");
        assertThat(directives.get(0).attr("contentType")).isEqualTo("text/html; charset=UTF-8");

        assertThat(ofType(tokens, Type.DECLARATION)).hasSize(1);
        assertThat(ofType(tokens, Type.SCRIPTLET)).hasSize(1);
        assertThat(ofType(tokens, Type.EXPRESSION)).hasSize(1);
        assertThat(ofType(tokens, Type.EXPRESSION).get(0).content(text).trim()).isEqualTo("today");
        assertThat(ofType(tokens, Type.JSP_COMMENT)).hasSize(1);
        assertThat(ofType(tokens, Type.HTML_COMMENT)).hasSize(1);
        assertThat(ofType(tokens, Type.EL)).extracting(t -> t.content(text)).containsExactly("user.name", "deferred");
        // 주석 안의 태그는 토큰이 아니다
        assertThat(openTagNames(tokens)).containsExactly("p");
    }

    @Test
    @DisplayName("F-011-AC1 태그와 속성: 따옴표 안의 EL, 식, 중첩 커스텀 태그")
    void tagsAndAttributes() {
        String text = """
                <jsp:include page="/WEB-INF/views/common/header.jsp" flush="true"/>
                <form id="searchForm" action="<c:url value="/order/list"/>" method=get>
                  <input type="text" name="memberNm" value="<c:out value='${cond.memberNm}'/>" disabled>
                  <a href="<%=request.getContextPath()%>/order/view.do?id=<%= request.getParameter("id") %>">보기</a>
                  <a href='${ctx}/order/detail?orderId=${o.orderId}' class="${empty x ? "a" : 'b'}">상세</a>
                  <option value="1" <c:if test="${x == 1}">selected</c:if>>하나</option>
                </form>
                """;
        List<JspToken> tokens = scan(text);

        assertThat(openTagNames(tokens)).containsExactly("jsp:include", "form", "input", "a", "a", "option");
        JspToken include = tokens.stream().filter(t -> t.isOpenTag("jsp:include")).findFirst().orElseThrow();
        assertThat(include.selfClosing()).isTrue();
        assertThat(include.attr("page")).isEqualTo("/WEB-INF/views/common/header.jsp");

        JspToken form = tokens.stream().filter(t -> t.isOpenTag("form")).findFirst().orElseThrow();
        assertThat(form.attr("action")).isEqualTo("<c:url value=\"/order/list\"/>");
        assertThat(form.attr("method")).isEqualTo("get");
        assertThat(form.attr("ID")).as("속성 이름은 대소문자를 가리지 않는다").isEqualTo("searchForm");

        JspToken input = tokens.stream().filter(t -> t.isOpenTag("input")).findFirst().orElseThrow();
        assertThat(input.attr("value")).isEqualTo("<c:out value='${cond.memberNm}'/>");
        assertThat(input.hasAttr("disabled")).isTrue();

        List<JspToken> links = tokens.stream().filter(t -> t.isOpenTag("a")).toList();
        assertThat(links.get(0).attr("href"))
                .isEqualTo("<%=request.getContextPath()%>/order/view.do?id=<%= request.getParameter(\"id\") %>");
        assertThat(links.get(1).attr("href")).isEqualTo("${ctx}/order/detail?orderId=${o.orderId}");
        assertThat(links.get(1).attr("class")).isEqualTo("${empty x ? \"a\" : 'b'}");

        assertThat(ofType(tokens, Type.CLOSE_TAG)).extracting(JspToken::name).containsExactly("a", "a", "option", "form");
    }

    @Test
    @DisplayName("F-011-AC1 script와 style 본문은 raw text이고 위치를 안다")
    void rawTextAndPositions() {
        String text = "<html>\n"
                + "<style> a > b { color: red; } </style>\n"
                + "<script type=\"text/javascript\">\n"
                + "  if (a < b && c > d) { document.write('<div>x</div>'); }\n"
                + "  var url = '<c:url value=\"/x.do\"/>';\n"
                + "</script>\n"
                + "<a href=\"/after.do\">뒤</a>\n";
        List<JspToken> tokens = scan(text);

        assertThat(openTagNames(tokens)).containsExactly("html", "style", "script", "a");
        List<JspToken> raw = ofType(tokens, Type.RAW_TEXT);
        assertThat(raw).hasSize(2);
        assertThat(raw.get(1).text(text)).contains("document.write('<div>x</div>')").contains("<c:url value=\"/x.do\"/>");

        LineMap lines = new LineMap(text);
        JspToken script = tokens.stream().filter(t -> t.isOpenTag("script")).findFirst().orElseThrow();
        assertThat(lines.line(script.start())).isEqualTo(3);
        assertThat(lines.column(script.start())).isEqualTo(1);
        assertThat(lines.line(raw.get(1).start())).isEqualTo(3);
        assertThat(lines.column(raw.get(1).start())).isEqualTo(32);
        JspToken link = tokens.stream().filter(t -> t.isOpenTag("a")).findFirst().orElseThrow();
        assertThat(lines.line(link.start())).isEqualTo(7);
        JspAttribute href = link.attribute("href").orElseThrow();
        assertThat(text.substring(href.valueStart(), href.valueEnd())).isEqualTo("/after.do");
    }

    @Test
    @DisplayName("F-011-AC1 FRAGMENT 모드는 JSP 요소와 접두사 있는 태그만 토큰으로 낸다")
    void fragmentMode() {
        String text = "var a = '<div>' + ${count} + <%= n %>; <c:if test=\"${x}\">go('<c:url value='/a.do'/>');</c:if> i<n";
        List<JspToken> tokens = JspScanner.scan(text, JspScanner.Mode.FRAGMENT);

        assertThat(ofType(tokens, Type.EL)).hasSize(1);
        assertThat(ofType(tokens, Type.EXPRESSION)).hasSize(1);
        assertThat(openTagNames(tokens)).containsExactly("c:if", "c:url");
        assertThat(ofType(tokens, Type.CLOSE_TAG)).extracting(JspToken::name).containsExactly("c:if");
    }

    @Test
    @DisplayName("F-011-AC1 HTML 모드는 EL과 스크립틀릿을 인식하지 않는다")
    void htmlMode() {
        String text = "<p>${notEl} <% notScriptlet %></p><a href=\"${x}/a\">a</a>";
        List<JspToken> tokens = JspScanner.scan(text, JspScanner.Mode.HTML);

        assertThat(ofType(tokens, Type.EL)).isEmpty();
        assertThat(ofType(tokens, Type.SCRIPTLET)).isEmpty();
        assertThat(openTagNames(tokens)).containsExactly("p", "a");
    }

    @Test
    @DisplayName("F-011-AC1 이스케이프한 \\${ 는 EL이 아니다")
    void escapedEl() {
        List<JspToken> tokens = scan("<p>\\${literal} ${real}</p>");
        assertThat(ofType(tokens, Type.EL)).hasSize(1);
    }

    @Test
    @DisplayName("F-011-AC2 닫히지 않은 태그와 잘못 중첩된 태그에서도 계속 읽는다")
    void brokenTags() {
        String text = """
                <div class="box"
                <table><tr><td>
                <b><i>겹침</b></i>
                </div></span>
                <a href="/next.do">다음</a>
                <input name="q" value=
                """;
        List<JspToken> tokens = scan(text);

        assertThat(openTagNames(tokens)).containsExactly("div", "table", "tr", "td", "b", "i", "a", "input");
        assertThat(tokens.stream().filter(t -> t.isOpenTag("a")).findFirst().orElseThrow().attr("href")).isEqualTo("/next.do");
    }

    @Test
    @DisplayName("F-011-AC2 닫히지 않은 따옴표는 그 줄에서 끊고 다음 태그를 읽는다")
    void brokenQuote() {
        String text = """
                <a href="/broken.do>깨진 링크</a>
                <p>본문</p>
                <a href="/ok.do">정상</a>
                """;
        List<JspToken> tokens = scan(text);

        List<JspToken> links = tokens.stream().filter(t -> t.isOpenTag("a")).toList();
        assertThat(links).hasSize(2);
        assertThat(links.get(1).attr("href")).isEqualTo("/ok.do");
        assertThat(openTagNames(tokens)).contains("p");
    }

    @Test
    @DisplayName("F-011-AC2 닫히지 않은 스크립틀릿, JSP 주석, script, EL에서 예외가 나지 않는다")
    void unterminated() {
        assertThat(scan("<a href=\"/a.do\">a</a><% int x = 1;")).anyMatch(t -> t.isOpenTag("a"));
        assertThat(scan("<a href=\"/a.do\">a</a><%-- 끝이 없다")).anyMatch(t -> t.isOpenTag("a"));
        assertThat(scan("<a href=\"/a.do\">a</a><script>var a = 1;")).anyMatch(t -> t.type() == Type.RAW_TEXT);
        assertThat(scan("<a href=\"/a.do\">a</a> ${unclosed <b>x</b>")).anyMatch(t -> t.isOpenTag("b"));
        assertThat(scan("<a href=\"/a.do")).anyMatch(t -> t.isOpenTag("a"));
        assertThat(scan("<")).isNotNull();
        assertThat(scan("<%@")).isNotNull();
        assertThat(scan("")).isEmpty();
    }

    @Test
    @DisplayName("F-011-AC2 깨진 요소가 수만 개인 한 줄짜리 입력도 금방 끝난다")
    void pathologicalInputIsLinear() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("<a href='/broken").append(i).append(".do>x</a> ${unclosed <b title=\"q>y</b> <c:x a=<td> ");
        }
        String text = sb.toString();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            for (JspScanner.Mode mode : JspScanner.Mode.values()) {
                assertThat(JspScanner.scan(text, mode)).isNotEmpty();
            }
            ScriptSanitizer.sanitize(text, java.util.Map.of());
        });
    }

    @Test
    @DisplayName("F-011-AC2 임의로 자른 입력에서도 예외 없이 끝난다")
    void truncatedInputs() {
        String text = """
                <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                <form action="<c:url value='/a.do'/>" method="post"><input value="${a ? 'x' : "y"}"></form>
                <script>var u = '${ctx}/b.do'; <% if (x) { %> go(); <% } %></script>
                <%-- c --%><!-- h --><a href=<%= u %> onclick="go('<c:out value="${v}"/>')">x</a>
                """;
        for (int i = 0; i <= text.length(); i++) {
            String cut = text.substring(0, i);
            for (JspScanner.Mode mode : JspScanner.Mode.values()) {
                List<JspToken> tokens = JspScanner.scan(cut, mode);
                for (JspToken token : tokens) {
                    assertThat(token.start()).isBetween(0, cut.length());
                    assertThat(token.end()).isBetween(token.start(), cut.length());
                }
            }
        }
    }
}
