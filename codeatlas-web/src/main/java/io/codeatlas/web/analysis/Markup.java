package io.codeatlas.web.analysis;

import io.codeatlas.web.analysis.UrlValue.Kind;
import io.codeatlas.web.analysis.UrlValue.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 평가한 문자열이 HTML 조각일 때 그 안의 태그가 가리키는 URL을 읽는다(F-010-R45): {@code <iframe src>}·{@code <frame src>},
 * {@code <a href>}·{@code <area href>}, {@code <form action>}, {@code <img src>}. 문자열은 조각의 나열({@link UrlValue})이라
 * 속성 값도 조각의 나열로 돌려준다: {@code '<iframe src="' + ctx + '/x.do?id=' + id + '">'}의 src는
 * {@code [CTX, "/x.do?id=", DYN]}이다.
 */
final class Markup {

    /** URL을 담는 요소 → 그 속성. */
    private static final Map<String, String> URL_ATTRIBUTES = Map.of("a", "href", "area", "href", "form", "action", "img", "src",
            "iframe", "src", "frame", "src");
    /** 정적이 아닌 조각을 문자열 안에 표시하는 문자 구간(사설 영역). 조각 하나가 글자 하나다. */
    private static final char PLACEHOLDER_BASE = '';
    private static final int MAX_PLACEHOLDERS = 0x1800;

    /**
     * 태그 하나의 URL 속성.
     *
     * @param element 요소 이름(소문자)
     * @param url 속성 값
     * @param method {@code <form>}의 method 속성 값(대문자). 속성이 없으면 null, 값을 모르면 {@code ANY}
     * @param ordinal 그 문자열에서 URL 속성이 있는 태그의 순번(같은 자리의 후보끼리 묶는 데 쓴다)
     * @param open 값이 이 문자열에서 끝나지 않았는가(닫는 따옴표가 없다. 뒤는 모르는 조각으로 붙였다)
     */
    record Link(String element, UrlValue url, String method, int ordinal, boolean open) {
    }

    private Markup() {
    }

    /** HTML로 보이는 문자열에서 URL 속성들을 읽는다. 태그가 없으면 빈 목록. */
    static List<Link> links(UrlValue html) {
        List<Part> dynamic = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Part p : html.parts()) {
            if (p.kind() == Kind.LIT) {
                sb.append(p.text());
            } else if (dynamic.size() < MAX_PLACEHOLDERS) {
                sb.append((char) (PLACEHOLDER_BASE + dynamic.size()));
                dynamic.add(p);
            } else {
                return List.of();
            }
        }
        String s = sb.toString();
        List<Link> out = new ArrayList<>();
        int at = s.indexOf('<');
        while (at >= 0) {
            int nameEnd = at + 1;
            while (nameEnd < s.length() && Character.isLetterOrDigit(s.charAt(nameEnd))) {
                nameEnd++;
            }
            String element = s.substring(at + 1, nameEnd).toLowerCase(Locale.ROOT);
            String wanted = URL_ATTRIBUTES.get(element);
            if (wanted == null || nameEnd >= s.length() || !Character.isWhitespace(s.charAt(nameEnd))) {
                at = s.indexOf('<', at + 1);
                continue;
            }
            int[] url = null;
            boolean open = false;
            String method = null;
            int i = nameEnd;
            while (i < s.length() && s.charAt(i) != '>') {
                char c = s.charAt(i);
                if (Character.isWhitespace(c) || c == '/') {
                    i++;
                    continue;
                }
                int attributeStart = i;
                while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && "=>/".indexOf(s.charAt(i)) < 0) {
                    i++;
                }
                String attribute = s.substring(attributeStart, i).toLowerCase(Locale.ROOT);
                if (i == attributeStart) {
                    i++; // 이름 없이 '='만 있다: 깨진 태그. 한 글자 넘긴다
                    continue;
                }
                int valueFrom = i;
                while (valueFrom < s.length() && Character.isWhitespace(s.charAt(valueFrom))) {
                    valueFrom++;
                }
                if (valueFrom >= s.length() || s.charAt(valueFrom) != '=') {
                    continue; // 값이 없는 속성
                }
                valueFrom++;
                while (valueFrom < s.length() && Character.isWhitespace(s.charAt(valueFrom))) {
                    valueFrom++;
                }
                int valueTo;
                boolean terminated = true;
                if (valueFrom < s.length() && (s.charAt(valueFrom) == '"' || s.charAt(valueFrom) == '\'')) {
                    char quote = s.charAt(valueFrom++);
                    valueTo = s.indexOf(quote, valueFrom);
                    if (valueTo < 0) {
                        valueTo = s.length();
                        terminated = false; // 값이 이 문자열에서 끝나지 않는다: 뒤는 다른 문장이 이어 쓴다
                    }
                    i = Math.min(valueTo + 1, s.length());
                } else {
                    valueTo = valueFrom;
                    while (valueTo < s.length() && !Character.isWhitespace(s.charAt(valueTo)) && s.charAt(valueTo) != '>') {
                        valueTo++;
                    }
                    i = valueTo;
                }
                if (attribute.equals(wanted) && url == null) {
                    url = new int[] {valueFrom, valueTo};
                    open = !terminated;
                } else if (attribute.equals("method") && element.equals("form") && method == null) {
                    String value = s.substring(valueFrom, valueTo).trim().toUpperCase(Locale.ROOT);
                    method = value.matches("GET|POST|PUT|DELETE|PATCH") ? value : value.isEmpty() ? null : "ANY";
                }
            }
            if (url != null) {
                out.add(new Link(element, value(s, url[0], url[1], open, dynamic, html.uncertain()), method, out.size(), open));
            }
            at = s.indexOf('<', Math.max(i, at + 1));
        }
        return out;
    }

    private static UrlValue value(String s, int from, int to, boolean open, List<Part> dynamic, boolean uncertain) {
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c >= PLACEHOLDER_BASE && c < PLACEHOLDER_BASE + dynamic.size()) {
                flush(literal, parts);
                parts.add(dynamic.get(c - PLACEHOLDER_BASE));
            } else {
                literal.append(c);
            }
        }
        flush(literal, parts);
        if (open) {
            parts.add(Part.DYN);
        }
        // 값의 앞뒤 공백은 URL이 아니다
        if (!parts.isEmpty() && parts.get(0).kind() == Kind.LIT) {
            parts.set(0, Part.lit(parts.get(0).text().stripLeading()));
        }
        int last = parts.size() - 1;
        if (last >= 0 && parts.get(last).kind() == Kind.LIT) {
            parts.set(last, Part.lit(parts.get(last).text().stripTrailing()));
        }
        return new UrlValue(parts, uncertain);
    }

    private static void flush(StringBuilder literal, List<Part> parts) {
        if (literal.length() > 0) {
            parts.add(Part.lit(literal.toString().replace("&amp;", "&")));
            literal.setLength(0);
        }
    }
}
