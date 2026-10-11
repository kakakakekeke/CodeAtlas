package io.codeatlas.web.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * URL 식을 평가한 값 하나. 조각의 나열이다.
 *
 * @param parts 조각들
 * @param uncertain 흐름을 보지 않고 고른 후보(여러 번 대입된 변수)에서 나왔는가
 */
record UrlValue(List<Part> parts, boolean uncertain) {

    enum Kind {
        /** 정적 문자열. */
        LIT,
        /** 알 수 없는 값. */
        DYN,
        /** 컨텍스트 경로로 보이는 값. 맨 앞이면 떼고 그 밖에서는 동적 조각이다. */
        CTX,
        /** 이름 있는 함수의 매개변수. 호출 지점의 인자로 바꿔 끼울 수 있다. */
        PARAM
    }

    record Part(Kind kind, String text, FunctionInfo fn, int index, boolean ctxLike) {
        static Part lit(String text) {
            return new Part(Kind.LIT, text, null, -1, false);
        }

        static final Part DYN = new Part(Kind.DYN, "", null, -1, false);
        static final Part CTX = new Part(Kind.CTX, "", null, -1, false);

        static Part param(FunctionInfo fn, int index, boolean ctxLike) {
            return new Part(Kind.PARAM, "", fn, index, ctxLike);
        }
    }

    static final UrlValue EMPTY = new UrlValue(List.of(), false);
    static final UrlValue DYNAMIC = new UrlValue(List.of(Part.DYN), false);
    static final UrlValue CONTEXT = new UrlValue(List.of(Part.CTX), false);

    static UrlValue literal(String text) {
        return text.isEmpty() ? EMPTY : new UrlValue(List.of(Part.lit(text)), false);
    }

    UrlValue concat(UrlValue other) {
        if (parts.isEmpty() && !uncertain) {
            return other;
        }
        if (other.parts.isEmpty() && !other.uncertain) {
            return this;
        }
        List<Part> out = new ArrayList<>(parts.size() + other.parts.size());
        out.addAll(parts);
        out.addAll(other.parts);
        return new UrlValue(out, uncertain || other.uncertain);
    }

    UrlValue asUncertain() {
        return uncertain ? this : new UrlValue(parts, true);
    }

    boolean hasParam() {
        for (Part p : parts) {
            if (p.kind() == Kind.PARAM) {
                return true;
            }
        }
        return false;
    }

    /** 맨 앞 조각(컨텍스트 경로 제외)이 그 함수의 매개변수인가: 매개변수가 URL 자체를 준다. */
    boolean startsWithParam() {
        for (Part p : parts) {
            if (p.kind() == Kind.CTX || (p.kind() == Kind.LIT && p.text().isEmpty())) {
                continue;
            }
            return p.kind() == Kind.PARAM;
        }
        return false;
    }

    /** 정적 조각에 경로 구분자가 있는가(식별자 하나가 아니라 경로를 담은 값인가). */
    boolean hasPathLiteral() {
        for (Part p : parts) {
            if (p.kind() == Kind.LIT && p.text().indexOf('/') >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 전부 정적 문자열이면 그 문자열, 아니면 null. */
    String literalOrNull() {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p.kind() != Kind.LIT) {
                return null;
            }
            sb.append(p.text());
        }
        return sb.toString();
    }

    /**
     * baseURL 뒤에 이 URL을 붙인다. 이 URL이 절대 URL이면 그대로다. 사이의 {@code /}는 하나로 맞춘다.
     */
    UrlValue withBase(UrlValue base) {
        if (base == null || base.parts.isEmpty()) {
            return this;
        }
        if (!parts.isEmpty() && parts.get(0).kind() == Kind.LIT && parts.get(0).text().matches("(?i)^([a-z][a-z0-9+.-]*:)?//.*")) {
            return this;
        }
        if (parts.isEmpty()) {
            return new UrlValue(base.parts, base.uncertain || uncertain);
        }
        List<Part> out = new ArrayList<>(base.parts);
        List<Part> rest = new ArrayList<>(parts);
        Part last = out.get(out.size() - 1);
        Part first = rest.get(0);
        boolean lastSlash = last.kind() == Kind.LIT && last.text().endsWith("/");
        boolean firstSlash = first.kind() == Kind.LIT && first.text().startsWith("/");
        if (lastSlash && firstSlash) {
            out.set(out.size() - 1, Part.lit(last.text().substring(0, last.text().length() - 1)));
        } else if (!lastSlash && !firstSlash && first.kind() == Kind.LIT) {
            out.add(Part.lit("/"));
        }
        out.addAll(rest);
        return new UrlValue(out, base.uncertain || uncertain);
    }

    /** 평가 결과를 URL 패턴으로 굳힌다. */
    Resolved resolve() {
        List<Part> normalized = new ArrayList<>(parts.size());
        boolean leading = true;
        // 맨 앞의 컨텍스트 경로 조각은 하나만 뗀다. 그 뒤에 또 오는 것(${ctx}${url})은 값을 모르는 조각이다
        boolean contextTaken = false;
        for (Part p : parts) {
            Kind kind = p.kind();
            if (kind == Kind.LIT && p.text().isEmpty()) {
                continue;
            }
            if (kind == Kind.PARAM) {
                kind = p.ctxLike() ? Kind.CTX : Kind.DYN;
            }
            if (kind == Kind.CTX) {
                if (leading && !contextTaken) {
                    contextTaken = true;
                    continue;
                }
                kind = Kind.DYN;
            }
            leading = false;
            normalized.add(kind == Kind.LIT ? p : Part.DYN);
        }
        StringBuilder sb = new StringBuilder();
        boolean dynamic = false;
        boolean lastDynamic = false;
        for (Part p : normalized) {
            if (p.kind() == Kind.LIT) {
                String text = p.text();
                // 조각의 경계에서 겹친 슬래시는 하나로: 컨텍스트 경로 "/" + "/x", ctx + "//x". 리터럴 하나가 //로 시작하는
                // 프로토콜 상대 URL(//host/x)과 scheme 뒤(http:/ + /host)는 그대로 둔다
                int last = sb.length() - 1;
                boolean afterSlash = last >= 0 && sb.charAt(last) == '/' && !(last >= 1 && sb.charAt(last - 1) == ':');
                if (text.startsWith("/") && (afterSlash || (sb.length() == 0 && contextTaken && text.startsWith("//")))) {
                    text = text.substring(1);
                }
                sb.append(text);
                lastDynamic = false;
            } else {
                dynamic = true;
                if (!lastDynamic) {
                    sb.append(DYNAMIC_TOKEN);
                }
                lastDynamic = true;
            }
        }
        String full = sb.toString().trim();
        String lower = full.toLowerCase(java.util.Locale.ROOT);
        boolean ignorable = full.isEmpty() && parts.stream().allMatch(p -> p.kind() == Kind.LIT)
                || lower.startsWith("javascript:") || lower.startsWith("#") || lower.startsWith("mailto:")
                || lower.startsWith("tel:") || lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("about:");
        String path = full;
        String query = null;
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        } else {
            // '?' 없이 쿼리 조각만 이어 붙인 문자열(url + "&popupId=" + id, 값을 모르는 접두 뒤의 "name=" 조각):
            // 경로가 아니라 쿼리다. 경로로 두면 "{*}&popupId={*}" 같은 가짜 경로가 생긴다
            int amp = path.indexOf('&');
            if (amp >= 0) {
                query = path.substring(amp + 1);
                path = path.substring(0, amp);
            } else if (QUERY_AFTER_UNKNOWN.matcher(path).matches()) {
                query = path.substring(DYNAMIC_TOKEN.length());
                path = DYNAMIC_TOKEN;
            }
        }
        // 세션 ID를 경로에 실어 보내는 꼴(/x;jsessionid=…)은 경로가 아니다
        path = JSESSIONID.matcher(path).replaceAll("");
        boolean external = path.matches("(?i)^(https?:)?//.*");
        boolean staticPath = path.replace(DYNAMIC_TOKEN, "").chars().anyMatch(Character::isLetterOrDigit);
        return new Resolved(full, path, query, dynamic, external, staticPath, ignorable);
    }

    static final String DYNAMIC_TOKEN = "{*}";
    private static final java.util.regex.Pattern JSESSIONID = java.util.regex.Pattern.compile("(?i);jsessionid=[^/?#]*");
    /** 값을 모르는 접두 바로 뒤에 {@code name=}이 오는 꼴: 앞선 문자열에 쿼리 조각을 덧붙인 것이다. */
    private static final java.util.regex.Pattern QUERY_AFTER_UNKNOWN = java.util.regex.Pattern
            .compile("\\{\\*\\}[A-Za-z_][\\w.\\[\\]-]*=.*");

    /**
     * 굳힌 URL.
     *
     * @param full 쿼리를 포함한 전체
     * @param path 쿼리와 fragment를 뗀 URL 패턴(노드 ID에 들어간다)
     * @param query 쿼리스트링. 없으면 null
     * @param dynamic 동적 조각이 있는가
     * @param external 외부 URL인가
     * @param staticPath 경로에 정적인 조각이 하나라도 있는가
     * @param ignorable 요청이 아닌 값({@code #}, {@code javascript:} 등)인가
     */
    record Resolved(String full, String path, String query, boolean dynamic, boolean external, boolean staticPath,
            boolean ignorable) {
    }
}
