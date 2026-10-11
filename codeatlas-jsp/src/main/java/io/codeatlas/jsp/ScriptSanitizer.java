package io.codeatlas.jsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 인라인 스크립트(또는 이벤트 핸들러) 안의 JSP 요소를 JavaScript로 읽을 수 있는 모양으로 바꾼다.
 * JavaScript 자체는 해석하지 않는다. 문자열 안인지 밖인지만 간이로 판정한다.
 *
 * <p>규칙(F-011 spec의 치환 규칙표)
 * <ul>
 *   <li>값을 내는 요소(EL, {@code <%= %>}, {@code <c:out/>} 등)는 {@value #EXPR}.</li>
 *   <li>{@code <c:url value="/x"/>}는 문자열 안이면 {@code /x}, 밖이면 {@code '/x'}.</li>
 *   <li>값을 내지 않는 요소(스크립틀릿, JSP 주석, 제어 태그의 여는·닫는 태그)는 공백.</li>
 *   <li>줄 수는 항상 보존한다. 치환 결과가 원래 요소보다 짧으면 모자란 만큼 공백을 채워 길이도 보존한다.
 *       문자열 안에서 치환했으면 그 공백은 문자열 값에 끼지 않게 닫는 따옴표 뒤(닫히지 않으면 줄 끝)에 둔다.</li>
 * </ul>
 *
 * <p>무엇을 무엇으로 바꿨는지는 {@link Sanitized#substitutions() 치환 목록}으로 함께 돌려준다. 소비자(web)가 식의 원문을
 * 되살리는 데 쓴다(F-011-R14).
 */
final class ScriptSanitizer {

    /** 내장 소스 attrs에서 치환 목록을 싣는 키. */
    static final String SUBSTITUTIONS_ATTR = "jspSubstitutions";

    /**
     * 치환 한 건. 치환 결과의 {@code [offset, offset + length)} 구간이 원래 본문의 {@code original} 자리다.
     * 채움 공백만 따로 떨어진 구간은 {@code original}이 빈 문자열이다.
     *
     * @param offset 치환 결과에서의 시작 문자 오프셋(0부터)
     * @param length 치환 결과에서의 길이(치환 토큰과 바로 뒤의 채움 공백·줄바꿈 포함)
     * @param original 그 자리에 원래 있던 JSP 요소의 원문
     */
    record Substitution(int offset, int length, String original) {
    }

    /**
     * 치환 결과.
     *
     * @param text 치환한 본문
     * @param substitutions 치환 목록. 오프셋 오름차순이고 서로 겹치지 않는다. 각 구간을 원문으로 되돌리면 원래 본문과 같다
     */
    record Sanitized(String text, List<Substitution> substitutions) {

        /**
         * attrs에 싣는 문자열: 건마다 {@code <offset>,<length>,<원문 길이>:<원문>}을 구분자 없이 이어 붙인다
         * (원문은 길이만큼 그대로 읽으므로 이스케이프가 없다). 치환이 없으면 빈 문자열.
         */
        String encodedSubstitutions() {
            StringBuilder sb = new StringBuilder();
            for (Substitution s : substitutions) {
                sb.append(s.offset()).append(',').append(s.length()).append(',').append(s.original().length()).append(':')
                        .append(s.original());
            }
            return sb.toString();
        }
    }

    /** 값을 내는 JSP 요소 자리에 넣는 식별자. URL 맨 앞이면 컨텍스트 경로, 그 밖에는 동적 조각으로 읽는다. */
    static final String EXPR = "__JSP_EXPR__";

    /** self-closing이어도 아무것도 출력하지 않는(또는 무엇을 출력할지 알 수 없는) 태그의 로컬 이름. */
    private static final Set<String> NO_OUTPUT = Set.of("set", "remove", "param", "usebean", "setproperty", "setlocale",
            "setbundle", "settimezone", "requestencoding", "include", "import", "forward", "redirect", "insertattribute",
            "dobody", "invoke", "attribute", "body");

    private static final int MAX_VARIABLE_DEPTH = 3;

    private final String body;
    private final Map<String, String> urlVars;
    private final StringBuilder out;
    private final JsState state = new JsState();
    /** 문자열 안에서 치환해 미뤄 둔 채움 공백 수. */
    private int pending;
    private final List<Substitution> substitutions = new ArrayList<>();

    private ScriptSanitizer(String body, Map<String, String> urlVars) {
        this.body = body;
        this.urlVars = urlVars;
        this.out = new StringBuilder(body.length() + 32);
    }

    /**
     * @param body 스크립트 본문 원문
     * @param urlVars 페이지에서 {@code <c:url var>}로 만든 변수 → {@code value} 원문. {@code ${var}}를 그 경로로 바꾼다
     */
    static String sanitize(String body, Map<String, String> urlVars) {
        return sanitize(body, JspScanner.scan(body, JspScanner.Mode.FRAGMENT), urlVars);
    }

    /** 이미 {@link JspScanner.Mode#FRAGMENT}로 읽은 토큰이 있을 때. */
    static String sanitize(String body, List<JspToken> tokens, Map<String, String> urlVars) {
        return sanitizeTracked(body, tokens, urlVars).text();
    }

    /** {@link #sanitize(String, List, Map)}와 같은 치환을 하고 치환 목록도 돌려준다. */
    static Sanitized sanitizeTracked(String body, List<JspToken> tokens, Map<String, String> urlVars) {
        if (tokens.isEmpty()) {
            return new Sanitized(body, List.of());
        }
        ScriptSanitizer sanitizer = new ScriptSanitizer(body, urlVars);
        int position = 0;
        for (JspToken token : tokens) {
            sanitizer.text(position, token.start());
            sanitizer.element(token);
            position = token.end();
        }
        sanitizer.text(position, body.length());
        sanitizer.flushPending();
        return new Sanitized(sanitizer.out.toString(), List.copyOf(sanitizer.substitutions));
    }

    private void text(int from, int to) {
        for (int i = from; i < to; i++) {
            char c = body.charAt(i);
            if (c == '\n' || c == '\r') {
                flushPending();
            }
            boolean wasInString = state.inString();
            out.append(c);
            state.feed(c);
            if (wasInString && !state.inString()) {
                flushPending();
            }
        }
    }

    private void element(JspToken token) {
        String original = token.text(body);
        String replacement = replacement(token);
        int start = out.length();
        out.append(replacement);
        // 원래 요소의 나머지 자리: 줄바꿈은 그대로, 그 밖에는 치환 결과보다 긴 만큼만 공백
        StringBuilder tail = new StringBuilder();
        boolean hasNewline = false;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (c == '\n' || c == '\r') {
                tail.append(c);
                hasNewline = true;
            } else if (i >= replacement.length()) {
                tail.append(' ');
            }
        }
        if (state.inString() && !hasNewline) {
            pending += tail.length();
            substitutions.add(new Substitution(start, replacement.length(), original));
            return;
        }
        // 앞에서 미뤄 둔 채움 공백도 이 요소의 구간에 넣는다(구간이 겹치지 않게).
        for (; pending > 0; pending--) {
            out.append(' ');
        }
        out.append(tail);
        substitutions.add(new Substitution(start, out.length() - start, original));
        if (hasNewline) {
            state.feed('\n');
        }
    }

    private void flushPending() {
        if (pending > 0) {
            substitutions.add(new Substitution(out.length(), pending, ""));
        }
        for (; pending > 0; pending--) {
            out.append(' ');
        }
    }

    private String replacement(JspToken token) {
        switch (token.type()) {
            case EL:
                String name = token.content(body).trim();
                String variable = urlVars.get(name);
                return variable != null ? urlLiteral(variable, 1) : EXPR;
            case EXPRESSION:
                return EXPR;
            case OPEN_TAG:
                if (isUrlTag(token)) {
                    return token.hasAttr("var") ? "" : urlLiteral(token.attr("value"), 1);
                }
                if (!token.selfClosing() || token.hasAttr("var")
                        || NO_OUTPUT.contains(token.localName().toLowerCase(java.util.Locale.ROOT))
                        || token.localName().startsWith("directive.")) {
                    return "";
                }
                return EXPR;
            default:
                return "";
        }
    }

    static boolean isUrlTag(JspToken token) {
        return !token.prefix().isEmpty() && token.localName().equals("url") && token.hasAttr("value");
    }

    private String urlLiteral(String value, int depth) {
        String inner = urlValue(value, depth);
        if (state.inString()) {
            return inner;
        }
        char quote = inner.indexOf('\'') >= 0 ? '"' : '\'';
        return quote + inner + quote;
    }

    /** {@code <c:url>}의 value 원문을 경로 문자열로. 안의 EL은 {@value #EXPR}가 된다. */
    private String urlValue(String value, int depth) {
        StringBuilder sb = new StringBuilder();
        int position = 0;
        for (JspToken token : JspScanner.scan(value, JspScanner.Mode.FRAGMENT)) {
            sb.append(value, position, token.start());
            position = token.end();
            switch (token.type()) {
                case EL:
                    String variable = urlVars.get(token.content(value).trim());
                    sb.append(variable != null && depth < MAX_VARIABLE_DEPTH ? urlValue(variable, depth + 1) : EXPR);
                    break;
                case EXPRESSION:
                    sb.append(EXPR);
                    break;
                case OPEN_TAG:
                    if (token.selfClosing() && !token.hasAttr("var")
                            && !NO_OUTPUT.contains(token.localName().toLowerCase(java.util.Locale.ROOT))) {
                        sb.append(EXPR);
                    }
                    break;
                default:
                    break;
            }
        }
        sb.append(value, position, value.length());
        return sb.toString().trim();
    }

    /** JavaScript 본문에서 지금 문자열·주석 안인지 따라가는 간이 상태 기계. 정규식 리터럴은 구분하지 않는다. */
    private static final class JsState {

        private enum Mode { CODE, SINGLE, DOUBLE, TEMPLATE, LINE_COMMENT, BLOCK_COMMENT }

        private Mode mode = Mode.CODE;
        private boolean escaped;
        private char previous;

        boolean inString() {
            return mode == Mode.SINGLE || mode == Mode.DOUBLE || mode == Mode.TEMPLATE;
        }

        void feed(char c) {
            char prev = previous;
            previous = c;
            switch (mode) {
                case CODE:
                    if (c == '\'') {
                        mode = Mode.SINGLE;
                    } else if (c == '"') {
                        mode = Mode.DOUBLE;
                    } else if (c == '`') {
                        mode = Mode.TEMPLATE;
                    } else if (c == '/' && prev == '/') {
                        mode = Mode.LINE_COMMENT;
                    } else if (c == '*' && prev == '/') {
                        mode = Mode.BLOCK_COMMENT;
                        previous = 0;
                    }
                    break;
                case SINGLE:
                case DOUBLE:
                case TEMPLATE:
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if ((mode == Mode.SINGLE && c == '\'') || (mode == Mode.DOUBLE && c == '"')
                            || (mode == Mode.TEMPLATE && c == '`')) {
                        mode = Mode.CODE;
                        previous = 0;
                    } else if (c == '\n' && mode != Mode.TEMPLATE) {
                        mode = Mode.CODE; // 닫히지 않은 문자열은 줄 끝에서 끝난 것으로 본다
                    }
                    break;
                case LINE_COMMENT:
                    if (c == '\n') {
                        mode = Mode.CODE;
                    }
                    break;
                case BLOCK_COMMENT:
                    if (c == '/' && prev == '*') {
                        mode = Mode.CODE;
                        previous = 0;
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
