package io.codeatlas.jsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScriptSanitizerTest {

    private static String sanitize(String body) {
        String result = ScriptSanitizer.sanitize(body, Map.of());
        assertThat(result.chars().filter(c -> c == '\n').count())
                .as("줄 수 보존")
                .isEqualTo(body.chars().filter(c -> c == '\n').count());
        return result;
    }

    private static String spaces(int count) {
        return " ".repeat(count);
    }

    @Test
    @DisplayName("F-011-AC11 EL과 식은 __JSP_EXPR__로, 긴 요소는 길이를 보존한다")
    void elAndExpression() {
        String body = "var total = <%= totalCount %>;\nvar list = ${orderListJson};\n";
        String result = sanitize(body);

        assertThat(result).isEqualTo("var total = __JSP_EXPR__" + spaces(5) + ";\nvar list = __JSP_EXPR__" + spaces(4) + ";\n");
        assertThat(result).hasSameSizeAs(body);
    }

    @Test
    @DisplayName("F-011-AC11 토큰보다 짧은 요소는 줄 수만 보존한다")
    void shortElement() {
        String body = "var n = ${n};\nvar next = 1;\n";
        String result = sanitize(body);

        assertThat(result).isEqualTo("var n = __JSP_EXPR__;\nvar next = 1;\n");
    }

    @Test
    @DisplayName("F-011-AC11 문자열 안의 EL: 채움 공백은 닫는 따옴표 뒤에 둔다")
    void elInsideString() {
        String body = "var url = '${pageContext.request.contextPath}/order/list.do';\nvar b = \"${ctx}/b.do\";\n";
        String result = sanitize(body);

        assertThat(result).isEqualTo("var url = '__JSP_EXPR__/order/list.do'" + spaces(22) + ";\nvar b = \"__JSP_EXPR__/b.do\";\n");
        assertThat(result.split("\n")[0]).hasSameSizeAs(body.split("\n")[0]);
    }

    @Test
    @DisplayName("F-011-AC11 c:url은 문자열 안이면 경로만, 밖이면 따옴표를 붙인 리터럴")
    void curl() {
        String body = "$.post('<c:url value=\"/order/close.do\"/>', {id: 1});\n"
                + "location.href = <c:url value='/main.do'/>;\n"
                + "var v = \"<spring:url value='/order/${orderId}/view'/>\";\n";
        String result = sanitize(body);
        String[] lines = result.split("\n");

        assertThat(lines[0]).isEqualTo("$.post('/order/close.do'" + spaces(17) + ", {id: 1});");
        assertThat(lines[1]).isEqualTo("location.href = '/main.do'" + spaces(15) + ";");
        assertThat(lines[2]).startsWith("var v = \"/order/__JSP_EXPR__/view\"").endsWith(";");
        assertThat(result).hasSameSizeAs(body);
    }

    @Test
    @DisplayName("F-011-AC11 값을 내는 커스텀 태그는 __JSP_EXPR__")
    void valueTags() {
        String body = "var msg = \"<spring:message code='order.saved'/>\";\nvar name = '<c:out value=\"${member.name}\"/>';\n";
        String result = sanitize(body);
        String[] lines = result.split("\n");

        assertThat(lines[0]).startsWith("var msg = \"__JSP_EXPR__\"").endsWith(";");
        assertThat(lines[1]).startsWith("var name = '__JSP_EXPR__'").endsWith(";");
        assertThat(result).hasSameSizeAs(body);
    }

    @Test
    @DisplayName("F-011-AC11 스크립틀릿, 제어 태그, JSP 주석은 같은 길이의 공백으로 지우고 본문 JS는 남긴다")
    void controlElements() {
        String body = "<% if (admin) { %>\n"
                + "  showAdmin();\n"
                + "<% } %>\n"
                + "<c:if test=\"${not empty msg}\">alert('x');</c:if>\n"
                + "<c:forEach var=\"c\" items=\"${codes}\">codes.push('<c:out value=\"${c.code}\"/>');</c:forEach>\n"
                + "<%-- var hidden = '${secret}'; --%>\n"
                + "<c:set var=\"x\" value=\"1\"/>done();\n";
        String result = sanitize(body);
        String[] lines = result.split("\n");

        assertThat(lines[0]).isEqualTo(spaces(18));
        assertThat(lines[1]).isEqualTo("  showAdmin();");
        assertThat(lines[2]).isEqualTo(spaces(7));
        assertThat(lines[3]).isEqualTo(spaces(30) + "alert('x');" + spaces(7));
        assertThat(lines[4].trim()).startsWith("codes.push('__JSP_EXPR__'").endsWith(");");
        assertThat(lines[5]).isBlank();
        assertThat(lines[6]).isEqualTo(spaces(26) + "done();");
        assertThat(result).hasSameSizeAs(body);
    }

    @Test
    @DisplayName("F-011-AC11 여러 줄에 걸친 요소는 줄바꿈을 그대로 둔다")
    void multiLineElements() {
        String body = "<%\n  String a = \"x\";\n  int b = 1;\n%>\nvar c = ${fn:length(\n  list)};\nvar d = 1;\n";
        String result = sanitize(body);
        String[] lines = result.split("\n", -1);

        assertThat(lines[0]).isBlank();
        assertThat(lines[1]).isBlank();
        assertThat(lines[3]).isBlank();
        assertThat(lines[4]).startsWith("var c = __JSP_EXPR__");
        assertThat(lines[6]).isEqualTo("var d = 1;");
    }

    @Test
    @DisplayName("F-011-AC11 JS 주석 안의 따옴표를 문자열 시작으로 보지 않는다")
    void commentsDoNotOpenStrings() {
        String body = "// don't break\n"
                + "var a = <c:url value='/a.do'/>;\n"
                + "/* it's \"fine\" */ var b = <c:url value='/b.do'/>;\n"
                + "var s = \"it's\"; var c = <c:url value='/c.do'/>;\n"
                + "var t = 'a\\'b'; var d = <c:url value='/d.do'/>;\n";
        String result = sanitize(body);

        assertThat(result).contains("var a = '/a.do'").contains("var b = '/b.do'").contains("var c = '/c.do'")
                .contains("var d = '/d.do'");
    }

    @Test
    @DisplayName("F-011-AC11 페이지에서 만든 URL 변수는 그 경로로 치환한다")
    void urlVariables() {
        String body = "var listUrl = '${listUrl}';\nvar other = '${other}';\n";
        String result = ScriptSanitizer.sanitize(body, Map.of("listUrl", "/order/list.do"));

        assertThat(result).isEqualTo("var listUrl = '/order/list.do';\nvar other = '__JSP_EXPR__';\n");
    }

    @Test
    @DisplayName("F-011-AC11 var가 있는 c:url과 JS가 아닌 텍스트는 건드리지 않는다")
    void noOutputTagsAndPlainJs() {
        String body = "<c:url value=\"/x.do\" var=\"u\"/>\nif (a < b && c > d) { $('#list').html('<div class=\"row\">' + i + '</div>'); }\n";
        String result = sanitize(body);
        String[] lines = result.split("\n");

        assertThat(lines[0]).isBlank();
        assertThat(lines[1]).isEqualTo("if (a < b && c > d) { $('#list').html('<div class=\"row\">' + i + '</div>'); }");
    }

    /** 치환 목록의 약속(F-011 spec "치환 목록"): 치환한 구간을 원문으로 되돌리면 원래 본문이 나온다. */
    private static String restore(ScriptSanitizer.Sanitized sanitized) {
        String text = sanitized.text();
        StringBuilder sb = new StringBuilder();
        int position = 0;
        for (ScriptSanitizer.Substitution s : sanitized.substitutions()) {
            assertThat(s.offset()).as("겹치지 않고 오름차순").isGreaterThanOrEqualTo(position);
            sb.append(text, position, s.offset()).append(s.original());
            position = s.offset() + s.length();
        }
        return sb.append(text, position, text.length()).toString();
    }

    @Test
    @DisplayName("F-011-AC16 치환 목록은 치환한 구간(오프셋, 길이)과 그 자리의 원문을 담는다")
    void substitutionList() {
        String body = "$.getJSON('${ctx}/order/history.do', cb);\n$.post('<c:url value=\"/order/close.do\"/>', {});\n";
        ScriptSanitizer.Sanitized result = ScriptSanitizer.sanitizeTracked(body, JspScanner.scan(body, JspScanner.Mode.FRAGMENT), Map.of());

        assertThat(result.text()).isEqualTo(ScriptSanitizer.sanitize(body, Map.of()));
        // '${ctx}' → '__JSP_EXPR__': 토큰이 더 길어 채움 공백이 없다. <c:url>은 닫는 따옴표 뒤에 채움 공백 17칸이 따로 잡힌다.
        assertThat(result.substitutions()).containsExactly(
                new ScriptSanitizer.Substitution(11, 12, "${ctx}"),
                new ScriptSanitizer.Substitution(56, 15, "<c:url value=\"/order/close.do\"/>"),
                new ScriptSanitizer.Substitution(72, 17, ""));
        assertThat(result.text().substring(11, 23)).isEqualTo("__JSP_EXPR__");
        assertThat(result.text().substring(56, 71)).isEqualTo("/order/close.do");
        assertThat(result.text().substring(72, 89)).isBlank();
        assertThat(result.encodedSubstitutions())
                .isEqualTo("11,12,6:${ctx}56,15,32:<c:url value=\"/order/close.do\"/>72,17,0:");
        assertThat(restore(result)).isEqualTo(body);
    }

    @Test
    @DisplayName("F-011-AC16 치환 목록으로 되돌리면 원래 본문과 글자 하나까지 같다(문자열 안팎, 여러 줄 요소, URL 변수, 제어 태그)")
    void substitutionsRestoreOriginal() {
        List<String> bodies = List.of(
                "var total = <%= totalCount %>;\nvar list = ${orderListJson};\nvar n = ${n};\n",
                "var url = '${pageContext.request.contextPath}/order/list.do';\nvar b = \"${ctx}/b.do\" + '${a}${b}';\n",
                "location.href = <c:url value='/main.do'/>;\nvar v = \"<spring:url value='/order/${orderId}/view'/>\";\n",
                "<% if (admin) { %>\n  showAdmin();\n<% } %>\n<c:if test=\"${not empty msg}\">alert('x');</c:if>\n"
                        + "<%-- var hidden = '${secret}'; --%>\n<c:set var=\"x\" value=\"1\"/>done();\n",
                "<%\n  String a = \"x\";\n%>\nvar c = ${fn:length(\n  list)};\nvar s = '${a}<c:out value=\"${b\n}\"/>tail';\n",
                "var open = '${never.closed}/x\nvar next = '${listUrl}' + ${listUrl};\n",
                "$.get('/order/list.do<c:if test=\"${not empty q}\">?q=${q}</c:if>');\n",
                "no jsp here\n");
        for (String body : bodies) {
            ScriptSanitizer.Sanitized result = ScriptSanitizer.sanitizeTracked(body, JspScanner.scan(body, JspScanner.Mode.FRAGMENT),
                    Map.of("listUrl", "/order/list.do"));

            assertThat(result.text()).as(body).isEqualTo(ScriptSanitizer.sanitize(body, Map.of("listUrl", "/order/list.do")));
            assertThat(restore(result)).isEqualTo(body);
        }
        assertThat(ScriptSanitizer.sanitizeTracked("no jsp here\n", List.of(), Map.of()).encodedSubstitutions()).isEmpty();
    }
}
