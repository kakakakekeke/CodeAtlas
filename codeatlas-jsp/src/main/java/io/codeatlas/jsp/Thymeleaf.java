package io.codeatlas.jsp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thymeleaf 속성 값의 식을 읽는다(F-011-R17, R18). 식을 평가하지는 않는다: 링크 식({@code @{…}})에서 정적인 경로 조각만 건지고
 * 변수 식은 동적 조각으로 본다.
 */
final class Thymeleaf {

    /**
     * 링크 식을 {@link UrlNormalizer}가 읽을 수 있는 꼴로 바꾼 것에서 동적 조각 자리에 넣는 EL. 속성 접근이 있는 EL이라
     * 컨텍스트 경로로 읽히지 않고 {@code {*}}가 된다({@code @{…}}는 컨텍스트 경로를 스스로 붙이므로 뗄 것이 없다).
     */
    static final String DYNAMIC = "${th.dynamic}";

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final Pattern EXPRESSION = Pattern.compile("[$*#@~]\\{");

    private Thymeleaf() {
    }

    /** 태그에서 {@code th:<name>} 또는 {@code data-th-<name>} 속성을 찾는다. 없으면 null. */
    static JspAttribute attribute(JspToken token, String name) {
        return token.attribute("th:" + name).or(() -> token.attribute("data-th-" + name)).orElse(null);
    }

    /**
     * {@code th:href}·{@code th:action}·{@code th:src}의 값을 URL 원문 꼴로 바꾼다: 리터럴 경로는 그대로, 변수 식과 경로 변수
     * ({@code {id}})는 {@link #DYNAMIC}, 링크 식의 매개변수({@code (a=${x},b=1)})는 쿼리스트링으로.
     */
    static String linkToUrl(String value) {
        String v = value.trim();
        if (v.startsWith("@{") && v.endsWith("}") && matchingBrace(v, 1) == v.length() - 1) {
            return linkBody(v.substring(2, v.length() - 1).trim());
        }
        return expression(v, Set.of());
    }

    /** {@code @{…}} 안쪽: 경로 식 + 선택적인 {@code (이름=값,…)} 매개변수. */
    private static String linkBody(String body) {
        String path = body;
        String parameters = null;
        if (body.endsWith(")")) {
            int open = matchingOpenParenthesis(body);
            if (open > 0) {
                path = body.substring(0, open).trim();
                parameters = body.substring(open + 1, body.length() - 1);
            }
        }
        Set<String> pathVariables = new LinkedHashSet<>();
        String url = expression(path, pathVariables);
        if (url.startsWith("~/")) {
            url = url.substring(1); // 서버 기준 경로
        }
        if (parameters == null) {
            return url;
        }
        StringBuilder query = new StringBuilder();
        for (String parameter : splitTopLevel(parameters, ',')) {
            int eq = parameter.indexOf('=');
            String name = (eq < 0 ? parameter : parameter.substring(0, eq)).trim();
            if (name.isEmpty() || pathVariables.contains(name)) {
                continue; // 경로 변수에 들어가는 값은 쿼리가 아니다
            }
            String valueText = eq < 0 ? "" : parameter.substring(eq + 1).trim();
            query.append(query.length() == 0 ? (url.indexOf('?') >= 0 ? "&" : "?") : "&").append(name).append('=')
                    .append(parameterValue(valueText));
        }
        return url + query;
    }

    private static String parameterValue(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'") && value.indexOf('\'', 1) == value.length() - 1) {
            return value.substring(1, value.length() - 1);
        }
        return value.matches("[A-Za-z0-9_.-]*") && !value.isEmpty() && Character.isDigit(value.charAt(0)) ? value : DYNAMIC;
    }

    /**
     * 식을 URL 원문 꼴로. 따옴표 리터럴·{@code |리터럴 치환|}·따옴표 없는 경로 글자는 그대로 잇고, 변수 식({@code ${…}},
     * {@code *{…}}, {@code #{…}}, 중첩 {@code @{…}})과 그 밖의 토큰(식별자, 호출)은 동적 조각으로 바꾼다.
     */
    private static String expression(String text, Set<String> pathVariables) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'') {
                int end = text.indexOf('\'', i + 1);
                if (end < 0) {
                    end = n;
                }
                out.append(literal(text.substring(i + 1, Math.min(end, n)), pathVariables));
                i = end + 1;
            } else if (c == '|') {
                int end = text.indexOf('|', i + 1);
                if (end < 0) {
                    end = n;
                }
                out.append(literal(text.substring(i + 1, Math.min(end, n)), pathVariables));
                i = end + 1;
            } else if ((c == '$' || c == '*' || c == '#' || c == '@' || c == '~') && i + 1 < n && text.charAt(i + 1) == '{') {
                int end = matchingBrace(text, i + 1);
                out.append(DYNAMIC);
                i = end < 0 ? n : end + 1;
            } else if (c == '+' || Character.isWhitespace(c)) {
                i++; // 연결 연산자와 공백
            } else if (c == '/' || c == '~' || c == '.' || c == '?' || c == '{' || c == '#') {
                // 따옴표 없는 경로 글자들: @{/order/{id}/view}, @{/a.do?x=1}
                int end = i;
                while (end < n && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '\'' && text.charAt(end) != '|'
                        && !(end + 1 < n && text.charAt(end + 1) == '{' && "$*#@".indexOf(text.charAt(end)) >= 0)) {
                    end++;
                }
                out.append(literal(text.substring(i, end), pathVariables));
                i = end;
            } else {
                // 식별자, 숫자, 호출 등 값을 모르는 토큰. 스킴이 붙은 리터럴(http://…)은 그대로 둔다
                int end = i;
                while (end < n && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '+' && text.charAt(end) != '\''
                        && text.charAt(end) != '|') {
                    end++;
                }
                String token = text.substring(i, end);
                out.append(token.matches("(?i)[a-z][a-z0-9+.-]*:.*") || token.matches("[A-Za-z0-9_-]+(/.*|\\.[A-Za-z0-9]+)")
                        ? literal(token, pathVariables) : DYNAMIC);
                i = end;
            }
        }
        return out.toString();
    }

    /** 리터럴 안의 변수 식과 경로 변수({@code {id}})를 동적 조각으로 바꾼다. */
    private static String literal(String text, Set<String> pathVariables) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            Matcher expression = EXPRESSION.matcher(text);
            int at = expression.find(i) ? expression.start() : -1;
            String plain = text.substring(i, at < 0 ? text.length() : at);
            Matcher variable = PATH_VARIABLE.matcher(plain);
            StringBuilder replaced = new StringBuilder();
            while (variable.find()) {
                pathVariables.add(variable.group(1));
                variable.appendReplacement(replaced, Matcher.quoteReplacement(DYNAMIC));
            }
            variable.appendTail(replaced);
            out.append(replaced);
            if (at < 0) {
                break;
            }
            int end = matchingBrace(text, at + 1);
            out.append(DYNAMIC);
            i = end < 0 ? text.length() : end + 1;
        }
        return out.toString();
    }

    /** open 위치의 {@code {}에 짝이 맞는 {@code }}의 위치. 없으면 -1. */
    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 맨 끝의 {@code )}에 짝이 맞는 {@code (}의 위치(따옴표와 중괄호 안은 건너뛴다). 없으면 -1. */
    private static int matchingOpenParenthesis(String text) {
        int depth = 0;
        int braces = 0;
        boolean quoted = false;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (quoted) {
                continue;
            } else if (c == '}') {
                braces++;
            } else if (c == '{') {
                braces--;
            } else if (braces > 0) {
                continue;
            } else if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && (c == '{' || c == '(')) {
                depth++;
            } else if (!quoted && (c == '}' || c == ')')) {
                depth--;
            } else if (!quoted && depth == 0 && c == separator) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    /**
     * 조각 식({@code th:replace="fragments/head :: common"}, {@code ~{fragments/nav :: menu}}, {@code fragments/footer})의
     * 템플릿 이름.
     *
     * @param template 템플릿 이름(확장자 없음, 앞의 {@code /} 없음). 같은 파일의 조각이면 null
     * @param dynamic 템플릿 이름에 식이 있어 정적으로 알 수 없는가
     */
    record Fragment(String template, boolean dynamic) {
    }

    static Fragment fragment(String value) {
        String v = value.trim();
        if (v.startsWith("~{") && v.endsWith("}")) {
            v = v.substring(2, v.length() - 1).trim();
        }
        int selector = v.indexOf("::");
        String template = (selector < 0 ? v : v.substring(0, selector)).trim();
        if (template.length() >= 2 && template.startsWith("'") && template.endsWith("'")) {
            template = template.substring(1, template.length() - 1).trim();
        }
        if (template.isEmpty() || template.equals("this")) {
            return new Fragment(null, false);
        }
        if (EXPRESSION.matcher(template).find() || template.contains("__") || template.indexOf('\'') >= 0
                || template.indexOf('+') >= 0 || template.indexOf(' ') >= 0) {
            return new Fragment(null, true);
        }
        int parameters = template.indexOf('(');
        if (parameters > 0) {
            template = template.substring(0, parameters).trim();
        }
        while (template.startsWith("/")) {
            template = template.substring(1);
        }
        return new Fragment(template.isEmpty() ? null : template, false);
    }
}
