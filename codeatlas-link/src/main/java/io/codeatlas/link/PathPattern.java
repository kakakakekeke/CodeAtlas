package io.codeatlas.link;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 세그먼트 단위로 나눈 URL 경로 패턴. 엔드포인트 경로와 요청 URL 양쪽에 쓴다.
 *
 * <p>세그먼트 종류:
 * <ul>
 * <li>LITERAL: 글자 그대로.</li>
 * <li>WILDCARD: {@code {var}}, {@code {var:regex}}, {@code *}, 요청의 {@code {*}}. 세그먼트 하나.</li>
 * <li>PARTIAL: 리터럴과 변수가 섞인 것({@code *.do}, {@code comment-{theme}}, {@code popup_{*}}). 세그먼트 전체가 와일드카드가 아니다:
 * 리터럴 부분이 상대 세그먼트에서도 같은 자리에 맞아야 한다.</li>
 * <li>DEEP: {@code **}. 0개 이상의 세그먼트.</li>
 * <li>PLACEHOLDER: 값을 찾지 못한 프로퍼티 자리표시자 하나가 세그먼트 전체인 것({@code ${adminPath}}).
 * 와일드카드가 아니라 <b>알 수 없는 고정 조각</b>이다. 요청의 {@code {*}}와만 추측으로 맞는다.</li>
 * </ul>
 * 끝의 {@code /}는 세그먼트로 두지 않고 {@link Path#trailingSlash()}로만 남긴다({@code /a/}와 {@code /a}는 같은 세그먼트 목록이다).
 */
final class PathPattern {

    enum SegmentType { LITERAL, WILDCARD, PARTIAL, DEEP, PLACEHOLDER }

    /**
     * @param regex WILDCARD는 변수의 정규식(없으면 null), PARTIAL은 세그먼트 전체 정규식
     * @param glob PARTIAL의 뼈대: 변수 자리를 {@code *} 하나로 바꾼 문자열. 그 밖에는 null
     * @param unknown PARTIAL 안에 값을 찾지 못한 자리표시자가 들어 있다
     */
    record Segment(SegmentType type, String text, Pattern regex, String glob, boolean unknown) {
        boolean literal() {
            return type == SegmentType.LITERAL;
        }

        /** 고정된 글자가 있는 세그먼트인가(LITERAL, 또는 리터럴 부분이 있는 PARTIAL). */
        boolean fixed() {
            return type == SegmentType.LITERAL || (type == SegmentType.PARTIAL && glob.chars().anyMatch(c -> c != '*'));
        }
    }

    /** 나눈 경로. */
    record Path(List<Segment> segments, boolean trailingSlash) {
    }

    /**
     * 맞춘 결과.
     *
     * @param cost 작을수록 구체적인 대응
     * @param fixed 고정 글자를 근거로 맞은 세그먼트 수
     * @param guess 추측이 섞였다: 요청의 동적 조각이 엔드포인트의 고정 글자(또는 값을 모르는 자리표시자)를 덮어야 맞는다
     */
    record Match(int cost, int fixed, boolean guess) {
    }

    /** 전부 동적인 요청 세그먼트({@code {*}})가 엔드포인트의 고정 세그먼트와 맞았을 때의 비용. 근거가 가장 약한 대응이다. */
    private static final int BLIND = 1000;
    /** 세그먼트 비용에 섞어 돌려주는 "추측" 표시 비트. */
    private static final int GUESS_FLAG = 1 << 20;
    private static final int NO_MATCH = -1;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^{}]*}");

    private PathPattern() {
    }

    /** 경로를 세그먼트로 나눈다. 앞의 {@code /}와 빈 세그먼트는 버린다. 중괄호 안의 {@code /}는 나누지 않는다. */
    static List<Segment> parse(String path) {
        return parsePath(path).segments();
    }

    /** 요청 URL을 나눈다. 요청에 남은 {@code ${…}}(EL 원문)는 동적 조각 {@code {*}}로 읽는다. */
    static Path parseRequest(String path) {
        return parsePath(path == null ? null : PLACEHOLDER.matcher(path).replaceAll("{*}"));
    }

    static Path parsePath(String path) {
        List<Segment> segments = new ArrayList<>();
        String p = path == null ? "" : path.trim();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            }
            if (c == '/' && depth == 0) {
                if (current.length() > 0) {
                    segments.add(segment(current.toString()));
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            segments.add(segment(current.toString()));
        }
        boolean trailingSlash = !segments.isEmpty() && p.endsWith("/");
        return new Path(List.copyOf(segments), trailingSlash);
    }

    static Segment segment(String text) {
        if (text.equals("**")) {
            return new Segment(SegmentType.DEEP, text, null, null, false);
        }
        if (text.equals("*")) {
            return new Segment(SegmentType.WILDCARD, text, null, null, false);
        }
        if (PLACEHOLDER.matcher(text).matches()) {
            return new Segment(SegmentType.PLACEHOLDER, text, null, null, true);
        }
        if (text.startsWith("{") && closingBrace(text, 0) == text.length() - 1) {
            return new Segment(SegmentType.WILDCARD, text, variableRegex(text.substring(1, text.length() - 1)), null, false);
        }
        if (text.indexOf('*') < 0 && text.indexOf('{') < 0) {
            return new Segment(SegmentType.LITERAL, text, null, null, false);
        }
        StringBuilder glob = new StringBuilder();
        Pattern regex = partialRegex(text, glob);
        return new Segment(SegmentType.PARTIAL, text, regex, glob.toString(), PLACEHOLDER.matcher(text).find());
    }

    /** 세그먼트 목록을 {@code /}로 이은 경로. */
    static String join(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            sb.append('/').append(s.text());
        }
        return sb.toString();
    }

    /**
     * 요청 세그먼트가 엔드포인트 세그먼트와 처음부터 끝까지 맞는지 본다.
     *
     * @return 맞지 않으면 null
     */
    static Match match(List<Segment> request, List<Segment> endpoint) {
        return match(request, 0, endpoint, 0);
    }

    private static Match match(List<Segment> request, int i, List<Segment> endpoint, int j) {
        if (j == endpoint.size()) {
            return i == request.size() ? new Match(0, 0, false) : null;
        }
        Segment e = endpoint.get(j);
        if (e.type() == SegmentType.DEEP) {
            Match best = null;
            for (int k = i; k <= request.size(); k++) {
                Match rest = match(request, k, endpoint, j + 1);
                if (rest != null) {
                    Match candidate = new Match(rest.cost() + 50 + (k - i), rest.fixed(), rest.guess());
                    if (best == null || candidate.cost() < best.cost()) {
                        best = candidate;
                    }
                }
            }
            return best;
        }
        if (i == request.size()) {
            return null;
        }
        Segment r = request.get(i);
        int cost = segmentCost(r, e);
        if (cost < 0) {
            return null;
        }
        Match rest = match(request, i + 1, endpoint, j + 1);
        if (rest == null) {
            return null;
        }
        boolean guess = (cost & GUESS_FLAG) != 0;
        return new Match((cost & ~GUESS_FLAG) + rest.cost(), rest.fixed() + (fixedMatch(r, e) ? 1 : 0), guess || rest.guess());
    }

    /**
     * 두 세그먼트가 고정 글자를 근거로 맞았는가: 양쪽 다 고정 글자가 있는 세그먼트다(리터럴=리터럴, {@code menu.json}과 {@code {name}.json},
     * {@code bbs{*}}와 {@code bbs}, 앞뒤 리터럴이 겹치는 PARTIAL 둘). 호출 전에 {@link #segmentCost}가 맞다고 한 쌍에만 쓴다.
     */
    private static boolean fixedMatch(Segment r, Segment e) {
        return r.fixed() && e.fixed();
    }

    /**
     * 세그먼트 한 쌍의 비용(맞지 않으면 음수). 추측이면 {@link #GUESS_FLAG}를 섞는다.
     * 고정 글자가 많이 맞을수록 작다: 리터럴=리터럴 0, 섞인 세그먼트의 리터럴이 맞음 1~3, 변수가 리터럴을 받음 2,
     * 변수가 섞인 요청 조각을 통째로 받음 4, 전부 동적인 요청 조각이 고정 글자와 맞음 {@link #BLIND}.
     */
    private static int segmentCost(Segment r, Segment e) {
        switch (e.type()) {
            case LITERAL -> {
                if (r.literal()) {
                    return r.text().equals(e.text()) ? 0 : NO_MATCH;
                }
                if (r.type() == SegmentType.PARTIAL) {
                    return r.regex().matcher(e.text()).matches() ? 3 | GUESS_FLAG : NO_MATCH;
                }
                return BLIND | GUESS_FLAG;
            }
            case WILDCARD -> {
                if (r.type() == SegmentType.PARTIAL) {
                    return 4;
                }
                if (!r.literal()) {
                    return 0;
                }
                return e.regex() == null || e.regex().matcher(r.text()).matches() ? 2 : NO_MATCH;
            }
            case PARTIAL -> {
                if (r.literal()) {
                    if (!e.regex().matcher(r.text()).matches()) {
                        return NO_MATCH;
                    }
                    return e.unknown() ? 1 | GUESS_FLAG : 1;
                }
                if (r.type() == SegmentType.WILDCARD) {
                    return BLIND | GUESS_FLAG;   // 전부 동적인 요청 조각이 엔드포인트의 고정 글자를 우연히 채워야 맞는다
                }
                if (r.glob().equals(e.glob())) {
                    return e.unknown() ? 1 | GUESS_FLAG : 1;
                }
                return compatible(r.glob(), e.glob()) ? 2 | GUESS_FLAG : NO_MATCH;
            }
            case PLACEHOLDER -> {
                // 알 수 없는 고정 조각: 요청도 그 자리를 모를 때({*})만 추측으로 맞춘다
                return r.type() == SegmentType.WILDCARD ? BLIND | GUESS_FLAG : NO_MATCH;
            }
            default -> {
                return NO_MATCH;
            }
        }
    }

    /**
     * 뼈대가 다른 두 섞인 세그먼트가 같은 문자열을 가리킬 수 있는가.
     * 양쪽 다 리터럴로 시작하고, 두 뼈대({@code *}는 0자 이상)를 모두 만족하는 문자열이 있어야 한다:
     * {@code list-*}와 {@code list-*.html}은 맞고, {@code view-*-*}와 {@code comment-*}는 앞 글자가 달라 불일치,
     * {@code *.html}과 {@code index-*.html}은 겹치는 근거가 확장자뿐이라 맞추지 않는다.
     */
    static boolean compatible(String a, String b) {
        return !a.startsWith("*") && !b.startsWith("*") && intersects(a, b);
    }

    private static boolean intersects(String a, String b) {
        int n = a.length();
        int m = b.length();
        boolean[][] dp = new boolean[n + 1][m + 1];
        for (int i = n; i >= 0; i--) {
            for (int j = m; j >= 0; j--) {
                boolean ok;
                if (i == n && j == m) {
                    ok = true;
                } else if (i < n && a.charAt(i) == '*') {
                    ok = dp[i + 1][j] || (j < m && dp[i][j + 1]);
                } else if (j < m && b.charAt(j) == '*') {
                    ok = dp[i][j + 1] || (i < n && dp[i + 1][j]);
                } else {
                    ok = i < n && j < m && a.charAt(i) == b.charAt(j) && dp[i + 1][j + 1];
                }
                dp[i][j] = ok;
            }
        }
        return dp[0][0];
    }

    private static int closingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** {@code {id:\d+}}의 정규식. 정규식이 없거나 해석하지 못하면 null(무엇이든 맞음). */
    private static Pattern variableRegex(String inside) {
        int colon = inside.indexOf(':');
        if (colon < 0) {
            return null;
        }
        try {
            return Pattern.compile(inside.substring(colon + 1).trim());
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** PARTIAL 세그먼트의 정규식을 만들고, 변수 자리를 {@code *}로 바꾼 뼈대를 {@code glob}에 쓴다. */
    private static Pattern partialRegex(String text, StringBuilder glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean placeholder = c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{' && closingBrace(text, i + 1) > 0;
            int open = placeholder ? i + 1 : i;
            int close = c == '{' || placeholder ? closingBrace(text, open) : -1;
            if (c == '*' || close > 0) {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    glob.append(literal);
                    literal.setLength(0);
                }
                Pattern variable = close > 0 && !placeholder ? variableRegex(text.substring(open + 1, close)) : null;
                regex.append(variable != null ? "(?:" + variable.pattern() + ")" : ".*");
                if (glob.length() == 0 || glob.charAt(glob.length() - 1) != '*') {
                    glob.append('*');
                }
                i = close > 0 ? close + 1 : i + 1;
            } else {
                literal.append(c);
                i++;
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
            glob.append(literal);
        }
        try {
            return Pattern.compile(regex.toString());
        } catch (PatternSyntaxException e) {
            return Pattern.compile(".*");
        }
    }
}
