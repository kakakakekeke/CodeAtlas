package io.codeatlas.jsp;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSP 속성 값에 적힌 URL을 요청 URL 패턴으로 정규화한다.
 *
 * <ul>
 *   <li>컨텍스트 경로 표현을 뗀다: {@code contextPath}가 든 EL, {@code <%= request.getContextPath() %>},
 *       {@code <c:url value>}(값만 남긴다), 맨 앞에 있고 바로 뒤가 {@code /}인 단일 변수 EL·식({@code ${ctx}/x}).</li>
 *   <li>{@code <c:url var>}로 만든 변수의 {@code ${var}}는 그 URL로 푼다.</li>
 *   <li>남은 EL·식·값을 내는 태그는 {@value #DYNAMIC}.</li>
 *   <li>쿼리스트링과 fragment를 뗀다.</li>
 * </ul>
 */
final class UrlNormalizer {

    static final String DYNAMIC = "{*}";

    enum Kind {
        /** 요청으로 기록한다. */
        REQUEST,
        /** 요청이 아니다(빈 값, #, mailto:, 정적 자원 등). */
        SKIP,
        /** {@code javascript:} URL. 코드로 넘긴다. */
        SCRIPT,
        /** 정적 경로 조각이 하나도 없다. 미해결로 남긴다. */
        UNRESOLVED
    }

    /**
     * @param path 정규화한 URL(쿼리·fragment 제외). REQUEST일 때만 의미가 있다
     * @param query 쿼리스트링. 없으면 null
     * @param dynamic path에 {@value #DYNAMIC}가 있는가
     * @param external 외부 URL({@code http(s)://}, {@code //})인가
     * @param relative {@code /}로 시작하지 않는 상대 URL인가
     */
    record Result(Kind kind, String path, String query, boolean dynamic, boolean external, boolean relative) {

        static Result of(Kind kind) {
            return new Result(kind, "", null, false, false, false);
        }
    }

    private static final char CONTEXT = '';
    /** 단일 변수 EL·식. 맨 앞에서 뒤에 '/'가 오면 컨텍스트 경로로 본다. */
    private static final char SIMPLE_DYNAMIC = '';
    private static final char OTHER_DYNAMIC = '';

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Set<String> STATIC_EXTENSIONS = Set.of("css", "png", "jpg", "jpeg", "gif", "ico", "svg", "bmp",
            "webp", "woff", "woff2", "ttf", "eot", "otf", "map");
    private static final int MAX_VARIABLE_DEPTH = 4;

    private final Map<String, String> urlVars;
    private final Set<String> contextVars;

    /**
     * @param urlVars {@code <c:url var>}로 만든 변수 → value 원문. 호출하는 쪽이 문서 순서대로 채운다
     * @param contextVars 컨텍스트 경로를 담은 변수 이름({@code <c:set var="ctx" value="${pageContext.request.contextPath}"/>})
     */
    UrlNormalizer(Map<String, String> urlVars, Set<String> contextVars) {
        this.urlVars = urlVars;
        this.contextVars = contextVars;
    }

    Result normalize(String raw) {
        if (raw == null) {
            return Result.of(Kind.SKIP);
        }
        StringBuilder composed = new StringBuilder();
        compose(raw, composed, 0);
        String url = composed.toString().replaceAll("[\\r\\n\\t]", "").trim().replace("&amp;", "&");

        boolean context = false;
        if (!url.isEmpty() && url.charAt(0) == CONTEXT) {
            context = true;
        } else if (url.length() > 1 && url.charAt(0) == SIMPLE_DYNAMIC && url.charAt(1) == '/') {
            context = true;
            url = url.substring(1);
        }
        url = url.replace(String.valueOf(CONTEXT), "")
                .replace(String.valueOf(SIMPLE_DYNAMIC), DYNAMIC)
                .replace(String.valueOf(OTHER_DYNAMIC), DYNAMIC);
        while (url.contains(DYNAMIC + DYNAMIC)) {
            url = url.replace(DYNAMIC + DYNAMIC, DYNAMIC);
        }
        if (context && !url.startsWith("/")) {
            url = "/" + url;
        }
        if (url.isEmpty() || url.startsWith("#")) {
            return Result.of(Kind.SKIP);
        }
        // 프로토콜 상대 URL(//host/x)은 원문 자체가 //로 시작할 때만이다. 컨텍스트 경로 식 뒤에 /가 겹친 것
        // (<c:url value='/'/>/x, ${ctx}//x)은 같은 서버의 경로이고 슬래시는 아래에서 하나로 합친다
        boolean external = url.startsWith("//") && raw.trim().startsWith("//");
        Matcher scheme = SCHEME.matcher(url);
        if (scheme.find()) {
            String name = scheme.group(1).toLowerCase(Locale.ROOT);
            if (name.equals("javascript")) {
                return Result.of(Kind.SCRIPT);
            }
            if (!name.equals("http") && !name.equals("https")) {
                return Result.of(Kind.SKIP);
            }
            external = true;
        }

        int fragment = url.indexOf('#');
        if (fragment >= 0) {
            url = url.substring(0, fragment);
        }
        String query = null;
        int question = url.indexOf('?');
        if (question >= 0) {
            query = url.substring(question + 1);
            url = url.substring(0, question);
        } else {
            // '?' 없이 '&'로 이은 것은 쿼리 조각이다(${ctx}${url}&extId=1). 경로로 두면 "/{*}&extId=1" 같은 가짜 경로가 된다
            int ampersand = url.indexOf('&');
            if (ampersand >= 0) {
                query = url.substring(ampersand + 1);
                url = url.substring(0, ampersand);
            }
        }
        String path = external ? url : url.replaceAll("/{2,}", "/");
        if (path.isEmpty()) {
            return Result.of(Kind.SKIP); // 쿼리만 있는 링크: 현재 URL로의 요청이라 경로를 모른다
        }
        boolean dynamic = path.contains(DYNAMIC);
        if (dynamic && path.replace(DYNAMIC, "").replace("/", "").replace(".", "").isEmpty()) {
            return Result.of(Kind.UNRESOLVED);
        }
        if (STATIC_EXTENSIONS.contains(extension(path))) {
            return Result.of(Kind.SKIP);
        }
        boolean relative = !external && !path.startsWith("/") && !path.startsWith(DYNAMIC);
        return new Result(Kind.REQUEST, path, query == null || query.isEmpty() ? null : query, dynamic, external, relative);
    }

    private static final Pattern TERNARY_LITERALS = Pattern.compile(
            "[^?]*\\?\\s*(['\"])([^'\"]*)\\1\\s*:\\s*(['\"])([^'\"]*)\\3\\s*", Pattern.DOTALL);
    /** 삼항 EL을 후보로 펼칠 때의 한계(2개면 후보 4개). */
    private static final int MAX_TERNARIES = 2;

    /**
     * URL 안의 EL이 {@code cond ? '리터럴A' : '리터럴B'} 꼴이면 그 자리를 A와 B로 바꾼 후보들을 돌려준다(F-011-R25).
     * {@code ${ctx}/oa/oaNotify/${oaNotify.self?'self':''}} → {@code …/self}, {@code …/}. 그런 EL이 없으면 원문 하나다.
     */
    static java.util.List<String> ternaryVariants(String raw) {
        java.util.List<String> variants = java.util.List.of(raw);
        if (raw == null || raw.indexOf('?') < 0 || !raw.contains("${")) {
            return variants;
        }
        java.util.List<JspToken> tokens = JspScanner.scan(raw, JspScanner.Mode.FRAGMENT);
        // 쿼리 문자열이 시작하는 자리: EL 밖의 첫 '?'(또는 '?' 없이 이은 '&'). 그 뒤의 삼항은 경로를 가르지 않으므로 펼치지 않는다
        // (경로가 같은 후보 둘이 되어 쿼리 값 하나만 남고 추론이 된다). 쿼리에서는 종전대로 {*}다
        int queryStart = raw.length();
        int position = 0;
        for (JspToken token : tokens) {
            int mark = firstQueryMark(raw, position, token.start());
            if (mark < 0 && token.type() == JspToken.Type.OPEN_TAG && ScriptSanitizer.isUrlTag(token)) {
                mark = firstQueryMark(raw, token.start(), token.end()); // <c:url value='/x?a=1'/>
            }
            if (mark >= 0) {
                queryStart = mark;
                break;
            }
            position = token.end();
        }
        int ternaries = 0;
        // 뒤에서부터 바꿔야 앞쪽 오프셋이 그대로다
        for (int i = tokens.size() - 1; i >= 0; i--) {
            JspToken token = tokens.get(i);
            if (token.type() != JspToken.Type.EL || raw.charAt(token.start()) != '$' || token.start() > queryStart) {
                continue;
            }
            Matcher m = TERNARY_LITERALS.matcher(token.content(raw));
            if (!m.matches() || ++ternaries > MAX_TERNARIES) {
                continue;
            }
            java.util.List<String> next = new java.util.ArrayList<>(variants.size() * 2);
            for (String variant : variants) {
                for (String literal : new String[] {m.group(2), m.group(4)}) {
                    String replaced = variant.substring(0, token.start()) + literal + variant.substring(token.end());
                    if (!next.contains(replaced)) {
                        next.add(replaced);
                    }
                }
            }
            variants = next;
        }
        return variants;
    }

    private static int firstQueryMark(String raw, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = raw.charAt(i);
            if (c == '?' || c == '&') {
                return i;
            }
        }
        return -1;
    }

    /** 값에 EL, 식, 스크립틀릿이 들어 있는가. */
    static boolean hasDynamicPart(String raw) {
        return raw.contains("${") || raw.contains("#{") || raw.contains("<%");
    }

    static boolean isIdentifier(String text) {
        return IDENTIFIER.matcher(text).matches();
    }

    /** EL 본문이 컨텍스트 경로를 뜻하는가(변수 이름은 보지 않는다). */
    static boolean isContextPathExpression(String el) {
        return el.toLowerCase(Locale.ROOT).contains("contextpath");
    }

    private static String extension(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot < 0 ? "" : last.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 원문을 훑어 리터럴은 그대로, JSP 요소는 표시 문자로 바꿔 붙인다. */
    private void compose(String raw, StringBuilder sb, int depth) {
        int position = 0;
        for (JspToken token : JspScanner.scan(raw, JspScanner.Mode.FRAGMENT)) {
            sb.append(raw, position, token.start());
            position = token.end();
            switch (token.type()) {
                case EL:
                    el(token.content(raw).trim(), sb, depth);
                    break;
                case EXPRESSION:
                    String code = token.content(raw).trim();
                    if (code.contains("getContextPath")) {
                        sb.append(CONTEXT);
                    } else {
                        sb.append(isIdentifier(code) ? SIMPLE_DYNAMIC : OTHER_DYNAMIC);
                    }
                    break;
                case OPEN_TAG:
                    tag(token, sb, depth);
                    break;
                default:
                    break; // 스크립틀릿, 주석, 닫는 태그는 아무것도 내지 않는다
            }
        }
        sb.append(raw, position, raw.length());
    }

    private void el(String body, StringBuilder sb, int depth) {
        if (isContextPathExpression(body) || contextVars.contains(body)) {
            sb.append(CONTEXT);
        } else if (urlVars.containsKey(body) && depth < MAX_VARIABLE_DEPTH) {
            compose(urlVars.get(body), sb, depth + 1);
        } else {
            sb.append(isIdentifier(body) ? SIMPLE_DYNAMIC : OTHER_DYNAMIC);
        }
    }

    private void tag(JspToken token, StringBuilder sb, int depth) {
        String local = token.localName();
        if (ScriptSanitizer.isUrlTag(token)) {
            if (!token.hasAttr("var") && depth < MAX_VARIABLE_DEPTH) {
                compose(token.attr("value"), sb, depth + 1);
            }
        } else if (local.equals("out") && token.hasAttr("value")) {
            if (depth < MAX_VARIABLE_DEPTH) {
                compose(token.attr("value"), sb, depth + 1);
            }
        } else if (token.selfClosing() && !token.hasAttr("var") && !local.equals("param") && !local.equals("set")) {
            sb.append(OTHER_DYNAMIC);
        }
    }
}
