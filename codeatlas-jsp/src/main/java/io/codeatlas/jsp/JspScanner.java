package io.codeatlas.jsp;

import io.codeatlas.jsp.JspToken.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * JSP·HTML 본문을 요소 단위로 끊는 스캐너. JSP는 올바른 XML도 HTML도 아니므로 직접 읽는다.
 *
 * <p>오류 허용: 닫히지 않은 태그·따옴표·스크립틀릿·주석을 만나도 예외를 던지지 않고 가능한 지점에서 다시 맞춘다.
 * 토큰은 시작 오프셋 순이고 서로 겹치지 않는다. 속성 값 안에 든 EL·식·중첩 태그는 따로 토큰으로 내지 않고
 * 속성 값 원문에 그대로 둔다(필요한 쪽이 {@link Mode#FRAGMENT}로 다시 읽는다).
 */
final class JspScanner {

    enum Mode {
        /** JSP 파일: JSP 요소와 HTML 태그 모두. */
        JSP,
        /** 순수 HTML 파일: HTML 태그와 주석만. EL과 스크립틀릿은 텍스트다. */
        HTML,
        /** 스크립트 본문이나 속성 값: JSP 요소와 접두사 있는 태그({@code <c:url>})만. 그 밖의 {@code <}는 텍스트다. */
        FRAGMENT
    }

    /** EL 하나가 걸칠 수 있는 최대 줄바꿈 수. 넘으면 EL이 아니라고 본다. */
    private static final int MAX_EL_NEWLINES = 5;
    /** EL 하나의 최대 길이. 닫는 중괄호를 찾느라 파일 끝까지 가는 일을 막는다. */
    private static final int MAX_EL_LENGTH = 4_000;
    /** 따옴표로 감싼 속성 값의 최대 길이. 넘으면 따옴표가 닫히지 않은 것으로 본다. */
    private static final int MAX_ATTRIBUTE_LENGTH = 200_000;

    private final String s;
    private final int n;
    private final boolean jsp;
    private final boolean html;
    private final List<JspToken> out = new ArrayList<>();

    private JspScanner(String text, Mode mode) {
        this.s = text;
        this.n = text.length();
        this.jsp = mode != Mode.HTML;
        this.html = mode != Mode.FRAGMENT;
    }

    static List<JspToken> scan(String text, Mode mode) {
        JspScanner scanner = new JspScanner(text == null ? "" : text, mode);
        scanner.run();
        return scanner.out;
    }

    private void run() {
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int next = -1;
            if (c == '<') {
                next = markup(i);
            } else if (jsp && isElStart(i) && !(i > 0 && s.charAt(i - 1) == '\\')) {
                int end = elEnd(i);
                if (end > 0) {
                    out.add(JspToken.simple(Type.EL, i, end, i + 2, end - 1));
                    next = end;
                }
            }
            i = next > i ? next : i + 1;
        }
    }

    /** {@code <}에서 시작하는 요소를 읽어 토큰을 내고 다음 위치를 돌려준다. 요소가 아니면 -1. */
    private int markup(int i) {
        if (jsp && s.startsWith("<%", i)) {
            return jspElement(i);
        }
        if (html && s.startsWith("<!--", i)) {
            int close = s.indexOf("-->", i + 4);
            int end = close < 0 ? n : close + 3;
            out.add(JspToken.simple(Type.HTML_COMMENT, i, end, Math.min(i + 4, end), close < 0 ? n : close));
            return end;
        }
        if (html && s.startsWith("<![CDATA[", i)) {
            int close = s.indexOf("]]>", i + 9);
            return close < 0 ? n : close + 3;
        }
        if (html && (s.startsWith("<!", i) || s.startsWith("<?", i))) {
            int close = s.indexOf('>', i + 2);
            return close < 0 ? n : close + 1;
        }
        if (!isTagStart(i)) {
            return -1;
        }
        if (!html && !isPrefixedTagAt(i)) {
            return -1;
        }
        JspToken tag = parseTag(i);
        out.add(tag);
        if (html && tag.type() == Type.OPEN_TAG && !tag.selfClosing()
                && (tag.name().equalsIgnoreCase("script") || tag.name().equalsIgnoreCase("style"))) {
            int rawEnd = rawTextEnd(tag.end(), tag.name());
            out.add(JspToken.simple(Type.RAW_TEXT, tag.end(), rawEnd, tag.end(), rawEnd));
            return rawEnd;
        }
        return tag.end();
    }

    private int jspElement(int i) {
        if (s.startsWith("<%--", i)) {
            int close = s.indexOf("--%>", i + 4);
            int end = close < 0 ? n : close + 4;
            out.add(JspToken.simple(Type.JSP_COMMENT, i, end, Math.min(i + 4, end), close < 0 ? n : close));
            return end;
        }
        int close = s.indexOf("%>", i + 2);
        int contentEnd = close < 0 ? n : close;
        int end = close < 0 ? n : close + 2;
        char kind = i + 2 < n ? s.charAt(i + 2) : ' ';
        if (kind == '@') {
            out.add(directive(i, Math.min(i + 3, contentEnd), contentEnd, end));
        } else if (kind == '=') {
            out.add(JspToken.simple(Type.EXPRESSION, i, end, Math.min(i + 3, contentEnd), contentEnd));
        } else if (kind == '!') {
            out.add(JspToken.simple(Type.DECLARATION, i, end, Math.min(i + 3, contentEnd), contentEnd));
        } else {
            out.add(JspToken.simple(Type.SCRIPTLET, i, end, Math.min(i + 2, contentEnd), contentEnd));
        }
        return end;
    }

    private JspToken directive(int start, int contentStart, int contentEnd, int end) {
        int i = skipWhitespace(contentStart, contentEnd);
        int nameStart = i;
        while (i < contentEnd && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        String name = s.substring(nameStart, i);
        List<JspAttribute> attributes = new ArrayList<>();
        while (true) {
            i = skipWhitespace(i, contentEnd);
            if (i >= contentEnd) {
                break;
            }
            int attrStart = i;
            while (i < contentEnd && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '=') {
                i++;
            }
            String attrName = s.substring(attrStart, i);
            int afterName = i;
            i = skipWhitespace(i, contentEnd);
            if (i < contentEnd && s.charAt(i) == '=') {
                i = skipWhitespace(i + 1, contentEnd);
                int valueStart = i;
                int valueEnd;
                if (i < contentEnd && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                    char quote = s.charAt(i);
                    valueStart = i + 1;
                    int close = s.indexOf(quote, valueStart);
                    valueEnd = close < 0 || close > contentEnd ? contentEnd : close;
                    i = Math.min(valueEnd + 1, contentEnd);
                } else {
                    while (i < contentEnd && !Character.isWhitespace(s.charAt(i))) {
                        i++;
                    }
                    valueEnd = i;
                }
                attributes.add(new JspAttribute(attrName, s.substring(valueStart, valueEnd), valueStart, valueEnd, true));
            } else if (!attrName.isEmpty()) {
                attributes.add(new JspAttribute(attrName, "", afterName, afterName, false));
            } else {
                i++;
            }
        }
        return new JspToken(Type.DIRECTIVE, start, end, name, List.copyOf(attributes), false, contentStart, contentEnd);
    }

    // ---- 태그 ----

    /** {@code <name} 또는 {@code </name}으로 시작하는가. */
    private boolean isTagStart(int i) {
        int j = i + 1;
        if (j < n && s.charAt(j) == '/') {
            j++;
        }
        return j < n && isNameStart(s.charAt(j));
    }

    /** {@code <prefix:name} 또는 {@code </prefix:name} 뒤에 공백, {@code /}, {@code >}가 오는가. */
    private boolean isPrefixedTagAt(int i) {
        int j = i + 1;
        if (j < n && s.charAt(j) == '/') {
            j++;
        }
        if (j >= n || !isNameStart(s.charAt(j))) {
            return false;
        }
        int nameEnd = nameEnd(j);
        if (nameEnd >= n) {
            return false;
        }
        int colon = -1;
        for (int k = j; k < nameEnd - 1; k++) {
            if (s.charAt(k) == ':') {
                colon = k;
                break;
            }
        }
        if (colon < 0 || !isNameStart(s.charAt(colon + 1))) {
            return false;
        }
        char after = s.charAt(nameEnd);
        return Character.isWhitespace(after) || after == '/' || after == '>';
    }

    /** 접두사 없는 HTML 태그처럼 보이는가: {@code </name>} 또는 {@code <name} 뒤에 공백, {@code /}, {@code >}. */
    private boolean looksLikePlainTag(int i) {
        if (!isTagStart(i)) {
            return false;
        }
        int j = s.charAt(i + 1) == '/' ? i + 2 : i + 1;
        int nameEnd = nameEnd(j);
        if (nameEnd >= n) {
            return false;
        }
        char after = s.charAt(nameEnd);
        return Character.isWhitespace(after) || after == '/' || after == '>';
    }

    private int nameEnd(int from) {
        int j = from;
        while (j < n && isNameChar(s.charAt(j))) {
            j++;
        }
        return j;
    }

    private static boolean isNameStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == ':';
    }

    /** {@code i}의 {@code <}에서 시작하는 여는·닫는 태그 하나를 읽는다. 닫는 {@code >}가 없으면 가능한 지점에서 끊는다. */
    private JspToken parseTag(int start) {
        boolean closing = s.charAt(start + 1) == '/';
        int nameStart = closing ? start + 2 : start + 1;
        int i = nameEnd(nameStart);
        String name = s.substring(nameStart, i);
        List<JspAttribute> attributes = new ArrayList<>();
        boolean selfClosing = false;
        while (true) {
            i = skipWhitespace(i, n);
            if (i >= n) {
                break;
            }
            char c = s.charAt(i);
            if (c == '>') {
                i++;
                break;
            }
            if (c == '/') {
                if (i + 1 < n && s.charAt(i + 1) == '>') {
                    selfClosing = true;
                    i += 2;
                    break;
                }
                i++;
                continue;
            }
            if (jsp && s.startsWith("<%", i)) {
                int close = s.indexOf("%>", i + 2);
                i = close < 0 ? n : close + 2;
                continue;
            }
            if (jsp && isElStart(i)) {
                int end = elEnd(i);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (c == '<') {
                if (jsp && isPrefixedTagAt(i)) {
                    // <option <c:if test="..">selected</c:if>> 처럼 태그 안에 든 커스텀 태그
                    i = parseTag(i).end();
                    continue;
                }
                // 닫히지 않은 태그: 새 태그가 시작됐으므로 여기서 끊는다
                break;
            }
            int attrStart = i;
            while (i < n) {
                char a = s.charAt(i);
                if (Character.isWhitespace(a) || a == '=' || a == '>' || a == '<'
                        || (a == '/' && i + 1 < n && s.charAt(i + 1) == '>')) {
                    break;
                }
                i++;
            }
            if (i == attrStart) {
                i++; // 진행 보장
                continue;
            }
            String attrName = s.substring(attrStart, i);
            int afterName = i;
            int j = skipWhitespace(i, n);
            if (j >= n || s.charAt(j) != '=') {
                attributes.add(new JspAttribute(attrName, "", afterName, afterName, false));
                continue;
            }
            i = skipWhitespace(j + 1, n);
            if (i >= n) {
                attributes.add(new JspAttribute(attrName, "", i, i, true));
                break;
            }
            char q = s.charAt(i);
            if (q == '"' || q == '\'') {
                int valueStart = i + 1;
                int close = quotedEnd(valueStart, q);
                if (close >= 0) {
                    attributes.add(new JspAttribute(attrName, s.substring(valueStart, close), valueStart, close, true));
                    i = close + 1;
                } else {
                    // 닫히지 않은 따옴표: 그 줄의 첫 '>' 또는 줄 끝에서 끊는다
                    int stop = brokenValueEnd(valueStart);
                    attributes.add(new JspAttribute(attrName, s.substring(valueStart, stop), valueStart, stop, true));
                    i = stop;
                }
            } else {
                int valueStart = i;
                i = unquotedEnd(i);
                attributes.add(new JspAttribute(attrName, s.substring(valueStart, i), valueStart, i, true));
            }
        }
        return new JspToken(closing ? Type.CLOSE_TAG : Type.OPEN_TAG, start, Math.min(i, n), name,
                List.copyOf(attributes), selfClosing, start, start);
    }

    /**
     * 따옴표로 감싼 속성 값의 닫는 따옴표 위치. 값 안의 스크립틀릿·EL·중첩 커스텀 태그는 통째로 건너뛴다
     * (그 안에 같은 따옴표가 있어도 된다). 따옴표가 닫히지 않은 것으로 보이면 -1.
     */
    private int quotedEnd(int from, char quote) {
        int i = from;
        int limit = Math.min(n, from + MAX_ATTRIBUTE_LENGTH);
        boolean sawNewline = false;
        while (i < limit) {
            char c = s.charAt(i);
            if (c == quote) {
                return i;
            }
            if (c == '\n') {
                sawNewline = true;
            } else if (jsp && c == '<' && s.startsWith("<%", i)) {
                int close = s.indexOf("%>", i + 2);
                if (close < 0) {
                    return -1;
                }
                i = close + 2;
                continue;
            } else if (jsp && isElStart(i)) {
                int end = elEnd(i);
                if (end > 0) {
                    i = end;
                    continue;
                }
            } else if (jsp && c == '<' && isPrefixedTagAt(i)) {
                i = parseTag(i).end();
                continue;
            } else if (c == '<' && sawNewline && looksLikePlainTag(i)) {
                // 줄을 넘긴 뒤 HTML 태그가 나오면 따옴표를 빠뜨린 것으로 본다
                return -1;
            }
            i++;
        }
        return -1;
    }

    /** 따옴표가 닫히지 않은 값의 끝: 그 줄의 첫 {@code >} 또는 줄 끝. 스크립틀릿 안의 {@code >}는 세지 않는다. */
    private int brokenValueEnd(int from) {
        int i = from;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '>' || c == '\n') {
                break;
            }
            if (jsp && s.startsWith("<%", i)) {
                int close = s.indexOf("%>", i + 2);
                if (close < 0) {
                    return n;
                }
                i = close + 2;
                continue;
            }
            i++;
        }
        return i;
    }

    private int unquotedEnd(int from) {
        int i = from;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '>') {
                break;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '>') {
                break;
            }
            if (jsp && s.startsWith("<%", i)) {
                int close = s.indexOf("%>", i + 2);
                i = close < 0 ? n : close + 2;
                continue;
            }
            if (jsp && isElStart(i)) {
                int end = elEnd(i);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return i;
    }

    // ---- EL, raw text ----

    private boolean isElStart(int i) {
        char c = s.charAt(i);
        return (c == '$' || c == '#') && i + 1 < n && s.charAt(i + 1) == '{';
    }

    /** {@code ${}의 끝(닫는 중괄호 다음) 위치. EL로 볼 수 없으면 -1. */
    private int elEnd(int start) {
        int i = start + 2;
        int limit = Math.min(n, start + MAX_EL_LENGTH);
        int depth = 0;
        int newlines = 0;
        while (i < limit) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int close = stringEnd(i, c);
                if (close < 0) {
                    return -1;
                }
                i = close + 1;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i + 1;
                }
                depth--;
            } else if (c == '<' && i + 1 < n && (s.charAt(i + 1) == '/' || s.charAt(i + 1) == '%')) {
                return -1;
            } else if (c == '\n' && ++newlines > MAX_EL_NEWLINES) {
                return -1;
            }
            i++;
        }
        return -1;
    }

    /** EL 안 문자열 리터럴의 닫는 따옴표 위치. 같은 줄에서 닫히지 않으면 -1. */
    private int stringEnd(int open, char quote) {
        for (int i = open + 1; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            } else if (c == '\n') {
                return -1;
            }
        }
        return -1;
    }

    /** {@code </script} 또는 {@code </style}의 위치. JSP 주석과 스크립틀릿 안은 건너뛴다. 없으면 끝까지. */
    private int rawTextEnd(int from, String tagName) {
        int i = from;
        int nameLength = tagName.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '<') {
                if (jsp && s.startsWith("<%--", i)) {
                    int close = s.indexOf("--%>", i + 4);
                    i = close < 0 ? n : close + 4;
                    continue;
                }
                if (jsp && s.startsWith("<%", i)) {
                    int close = s.indexOf("%>", i + 2);
                    i = close < 0 ? n : close + 2;
                    continue;
                }
                if (i + 2 + nameLength <= n && s.charAt(i + 1) == '/'
                        && s.regionMatches(true, i + 2, tagName, 0, nameLength)) {
                    return i;
                }
            }
            i++;
        }
        return n;
    }

    private int skipWhitespace(int from, int limit) {
        int i = from;
        while (i < limit && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
