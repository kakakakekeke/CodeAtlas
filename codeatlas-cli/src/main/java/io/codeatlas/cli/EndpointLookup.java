package io.codeatlas.cli;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL로 엔드포인트 찾기({@code calltree}의 검색어가 {@code [METHOD ]<경로>} 꼴일 때. F-001-R49).
 *
 * <ol>
 * <li>검색어에서 {@code scheme://host}, 쿼리({@code ?…}), fragment({@code #…})를 뗀다.</li>
 * <li>{@code HTTP_ENDPOINT} 가운데 경로({@code attrs.resolvedPath} 우선, 없으면 {@code attrs.path})가 맞고, 메서드가 같거나 엔드포인트가
 *     {@code ANY}인 것이 후보다. 검색어에 메서드가 없으면 그 경로의 엔드포인트 전부다.</li>
 * <li>경로는 글자 그대로 같은 것을 먼저 찾고, 없으면 경로 변수({@code {id}}, {@code {id:정규식}}, {@code ${프로퍼티}})와
 *     와일드카드({@code *}, {@code **})가 든 패턴에 구체 경로를 맞춘다. 패턴이 여럿 맞으면 변수·와일드카드가 가장 적은 것만 남긴다.</li>
 * <li>하나도 없으면 맨 앞 한 세그먼트, 그다음 두 세그먼트(컨텍스트 경로)를 떼고 다시 찾는다.</li>
 * <li>그래도 엔드포인트가 없으면 같은 경로의 화면 요청({@code HTTP_REQUEST})을 찾는다(F-001-R55). 프런트만 분석한 그래프나 그 메서드의
 *     엔드포인트가 없는 경로가 그렇다. 엔드포인트와 같은 순서다(F-001-R59): 글자 그대로 같은 경로 → 요청의 경로 패턴
 *     ({@code /api/schedule/{*}}, {@code {이름}})에 구체 경로 맞추기 → 맨 앞 1~2 세그먼트를 떼고 다시.</li>
 * </ol>
 * 엔드포인트를 찾았을 때 함께 돌려주는 "같은 경로의 화면 요청"도 글자 그대로 같은 것이 없으면 요청의 경로 패턴에 구체 경로를 맞춘다.
 * 화면 요청의 메서드: 요청이 {@code ANY}이고 메서드 후보({@code attrs.httpMethods}. 메서드를 하나로 정하지 못한 요청에 web이 남긴다)가
 * 있으면 검색어의 메서드가 그 후보에 든 것일 때만 맞는다. 후보가 없는 {@code ANY}는 어느 메서드에나 맞는다.
 * action 없는 폼의 요청({@code selfSubmit}. 경로 자리가 {@code {self}})은 경로가 없으므로 어느 URL에도 맞지 않는다.
 *
 * <p>그래프를 바꾸지 않고 관계도 만들지 않는다. 시작 노드를 고르는 데만 쓴다.
 */
final class EndpointLookup {

    /** 컨텍스트 경로로 보고 떼어 볼 세그먼트 수의 상한. */
    static final int MAX_CONTEXT_SEGMENTS = 2;

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "ANY");
    private static final String ANY = "ANY";
    /** 메서드를 하나로 정하지 못한 요청의 메서드 후보(설계 3.5 {@code HTTP_REQUEST}. 그때 {@code httpMethod}는 {@code ANY}다). */
    private static final String METHOD_CANDIDATES = "httpMethods";
    private static final Pattern URL_QUERY = Pattern.compile("(?:([A-Za-z]+)\\s+)?(?:https?://[^/\\s?#]+)?(/\\S*)");

    private EndpointLookup() {
    }

    /**
     * 찾은 결과.
     *
     * @param endpoints   맞은 엔드포인트(ID 순). 엔드포인트 없이 화면 요청만 찾았으면 비어 있다(그때 {@code requests}는 비어 있지 않다)
     * @param requests    같은 경로(와 메서드)의 화면 요청(ID 순)
     * @param path        엔드포인트(없으면 요청)를 찾은 경로(쿼리·fragment·컨텍스트 경로를 뗀 것)
     * @param contextPath 떼어 낸 컨텍스트 경로. 떼지 않았으면 null
     * @param byPattern   글자 그대로가 아니라 경로 변수·와일드카드 패턴으로 맞췄는지
     */
    record Match(List<Node> endpoints, List<Node> requests, String path, String contextPath, boolean byPattern) {
    }

    /** 검색어를 읽은 것: 메서드(없으면 null)와 쿼리·fragment·끝 슬래시를 뗀 경로. */
    private record UrlQuery(String method, String path) {
    }

    /** 검색어가 {@code [METHOD ]<경로>} 꼴이면 읽은 것, 아니면 null. */
    private static UrlQuery parse(String query) {
        if (query == null) {
            return null;
        }
        Matcher m = URL_QUERY.matcher(query.strip());
        if (!m.matches()) {
            return null;
        }
        String method = m.group(1) == null ? null : m.group(1).toUpperCase(Locale.ROOT);
        if (method != null && !METHODS.contains(method)) {
            return null;
        }
        return new UrlQuery(method, trimTrailingSlash(stripQueryAndFragment(m.group(2))));
    }

    /** 검색어가 URL 꼴({@code [METHOD ]/경로})인가. */
    static boolean isUrlQuery(String query) {
        return parse(query) != null;
    }

    /** 검색어가 {@code [METHOD ]<경로>} 꼴이고 맞는 엔드포인트(없으면 같은 경로의 화면 요청)가 있으면 그 결과, 아니면 null. */
    static Match find(GraphModel graph, String query) {
        UrlQuery url = parse(query);
        return url == null ? null : find(graph, url.method(), url.path());
    }

    /**
     * 메서드가 달라 찾지 못한 URL의 "같은 경로의 다른 메서드": 검색어에 메서드가 있을 때, 메서드를 가리지 않고 찾은 것 가운데 그 메서드에
     * 맞지 않는 엔드포인트(없으면 화면 요청). 오류 안내에만 쓴다(F-001-R59).
     *
     * @param wantsEndpoints 엔드포인트를 찾는 중인가({@code --kind}로 빼지 않았다)
     * @param wantsRequests 화면 요청을 찾는 중인가
     */
    static List<Node> otherMethods(GraphModel graph, String query, boolean wantsEndpoints, boolean wantsRequests) {
        UrlQuery url = parse(query);
        if (url == null || url.method() == null) {
            return List.of();
        }
        Match any = find(graph, null, url.path());
        if (any == null) {
            return List.of();
        }
        if (wantsEndpoints) {
            List<Node> endpoints = any.endpoints().stream().filter(n -> !methodMatches(url.method(), n)).toList();
            if (!endpoints.isEmpty()) {
                return endpoints;
            }
        }
        return wantsRequests ? any.requests().stream().filter(n -> !methodMatches(url.method(), n)).toList() : List.of();
    }

    private static Match find(GraphModel graph, String method, String full) {
        List<Node> endpoints = new ArrayList<>(graph.nodes(NodeKind.HTTP_ENDPOINT));
        endpoints.sort(Comparator.comparing(Node::id));
        String path = full;
        for (int stripped = 0; stripped <= MAX_CONTEXT_SEGMENTS; stripped++) {
            if (stripped > 0) {
                int next = path.indexOf('/', 1);
                if (next < 0) {
                    break; // 남는 경로가 없다
                }
                path = path.substring(next);
            }
            List<Node> literal = new ArrayList<>();
            List<Node> pattern = new ArrayList<>();
            int best = Integer.MAX_VALUE;
            for (Node endpoint : endpoints) {
                if (!methodMatches(method, endpoint)) {
                    continue;
                }
                String resolved = endpoint.attrString("resolvedPath");
                String raw = rawPath(endpoint);
                if (samePath(resolved, path) || samePath(raw, path)) {
                    literal.add(endpoint);
                    continue;
                }
                String template = resolved != null ? resolved : raw;
                int looseness = template == null ? -1 : matchLooseness(template, path);
                if (looseness >= 0 && looseness <= best) {
                    if (looseness < best) {
                        pattern.clear();
                        best = looseness;
                    }
                    pattern.add(endpoint);
                }
            }
            List<Node> found = literal.isEmpty() ? pattern : literal;
            if (!found.isEmpty()) {
                String contextPath = stripped == 0 ? null : full.substring(0, full.length() - path.length());
                return new Match(List.copyOf(found), requests(graph, method, full, path).nodes(), path, contextPath, literal.isEmpty());
            }
        }
        // 엔드포인트가 없다: 같은 경로의 화면 요청만이라도 찾는다. 엔드포인트와 같은 순서다: 글자 그대로 → 요청의 경로 패턴({*}, {이름})에
        // 구체 경로 맞추기 → 맨 앞 1~2 세그먼트(컨텍스트 경로)를 떼고 다시.
        path = full;
        for (int stripped = 0; stripped <= MAX_CONTEXT_SEGMENTS; stripped++) {
            if (stripped > 0) {
                int next = path.indexOf('/', 1);
                if (next < 0) {
                    break;
                }
                path = path.substring(next);
            }
            RequestHits hits = requests(graph, method, path, path);
            if (!hits.nodes().isEmpty()) {
                String contextPath = stripped == 0 ? null : full.substring(0, full.length() - path.length());
                return new Match(List.of(), hits.nodes(), path, contextPath, hits.byPattern());
            }
        }
        return null;
    }

    /** 찾은 화면 요청(ID 순)과, 글자 그대로가 아니라 요청의 경로 패턴으로 맞췄는지. */
    private record RequestHits(List<Node> nodes, boolean byPattern) {
    }

    /**
     * 같은 경로(컨텍스트 경로를 떼기 전이나 뗀 뒤)의 화면 요청. 검색어에 메서드가 있으면 {@link #methodMatches 메서드가 맞는} 요청만.
     * 글자 그대로 같은 요청을 먼저 찾고, 없으면 요청의 경로 패턴({@code /api/schedule/{*}}, {@code /board/{bbsId}/{nttId}})에 구체 경로
     * ({@code /api/schedule/12})를 엔드포인트 패턴과 같은 방식으로 맞춘다({@link #matchLooseness}, {@link #requestTemplate}.
     * 여럿이면 가장 좁은 것만).
     * 검색어의 경로가 패턴 꼴이면({@code {}, {@code *}가 들어 있다) 글자 그대로만 찾는다.
     * 경로가 {@code {self}}인 요청(action 없는 폼)은 경로가 없으므로 어느 쪽으로도 맞지 않는다.
     */
    private static RequestHits requests(GraphModel graph, String method, String full, String path) {
        List<Node> literal = new ArrayList<>();
        List<Node> pattern = new ArrayList<>();
        int best = Integer.MAX_VALUE;
        boolean concrete = isConcrete(full) && isConcrete(path);
        for (Node request : graph.nodes(NodeKind.HTTP_REQUEST)) {
            String requestPath = request.attrString("path");
            if (requestPath == null || !requestPath.startsWith("/") || request.attrBoolean("selfSubmit") || !methodMatches(method, request)) {
                continue;
            }
            String clean = trimTrailingSlash(stripQueryAndFragment(requestPath));
            if (clean.equals(full) || clean.equals(path)) {
                literal.add(request);
                continue;
            }
            if (!concrete) {
                continue;
            }
            String template = requestTemplate(clean);
            int looseness = matchLooseness(template, full);
            int afterContext = path.equals(full) ? -1 : matchLooseness(template, path);
            if (looseness < 0 || (afterContext >= 0 && afterContext < looseness)) {
                looseness = afterContext;
            }
            if (looseness >= 0 && looseness <= best) {
                if (looseness < best) {
                    pattern.clear();
                    best = looseness;
                }
                pattern.add(request);
            }
        }
        List<Node> found = literal.isEmpty() ? pattern : literal;
        found.sort(Comparator.comparing(Node::id));
        return new RequestHits(List.copyOf(found), literal.isEmpty() && !pattern.isEmpty());
    }

    /**
     * 화면 요청의 경로를 {@link #matchLooseness}가 읽는 패턴으로 바꾼다. 요청의 {@code {*}}는 "정적으로 알 수 없는 조각"이다(설계 3.9):
     * 세그먼트 전체인 {@code {*}}({@code /api/schedule/{*}})는 경로 변수처럼 한 세그먼트에 맞추고, 세그먼트의 일부인 {@code {*}}
     * ({@code /api{*}}: 뒤가 통째로 동적)는 여러 세그먼트에 맞는 것으로 둔다(느슨함 100: 더 좁은 패턴이 있으면 그것만 남는다).
     */
    static String requestTemplate(String path) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (int at = path.indexOf(DYNAMIC, from); at >= 0; at = path.indexOf(DYNAMIC, from)) {
            int end = at + DYNAMIC.length();
            boolean wholeSegment = (at == 0 || path.charAt(at - 1) == '/') && (end == path.length() || path.charAt(end) == '/');
            out.append(path, from, at).append(wholeSegment ? "{_}" : DYNAMIC);
            from = end;
        }
        return out.append(path.substring(from)).toString();
    }

    private static final String DYNAMIC = "{*}";

    /** 경로 변수나 와일드카드가 없는 구체 경로인가. */
    private static boolean isConcrete(String path) {
        return path.indexOf('{') < 0 && path.indexOf('*') < 0;
    }

    /**
     * 검색어의 메서드가 노드(엔드포인트나 화면 요청)에 맞는가. 검색어에 메서드가 없으면 전부 맞는다. 노드의 메서드와 같으면 맞고,
     * 노드가 {@code ANY}면 메서드 후보({@code httpMethods})가 없을 때는 전부, 있을 때는 그 후보에 든 메서드만 맞는다.
     */
    private static boolean methodMatches(String wanted, Node node) {
        String actual = methodOf(node);
        if (wanted == null || wanted.equalsIgnoreCase(actual)) {
            return true;
        }
        if (!ANY.equalsIgnoreCase(actual)) {
            return false;
        }
        List<String> candidates = methodCandidates(node);
        return candidates.isEmpty() || candidates.contains(wanted);
    }

    /** {@code attrs.httpMethods}의 메서드들(대문자). 없으면 빈 목록. {@code ANY}는 후보가 아니라서 뺀다(링커와 같다, F-012-R39). */
    static List<String> methodCandidates(Node node) {
        List<String> candidates = new ArrayList<>();
        for (String candidate : node.attrList(METHOD_CANDIDATES)) {
            String method = candidate == null ? "" : candidate.strip().toUpperCase(Locale.ROOT);
            if (!method.isEmpty() && !ANY.equals(method) && !candidates.contains(method)) {
                candidates.add(method);
            }
        }
        return candidates;
    }

    /** {@code attrs.httpMethod}. 없으면 ID의 나머지({@code METHOD 경로})에서 읽는다. */
    private static String methodOf(Node node) {
        String method = node.attrString("httpMethod");
        if (method != null) {
            return method;
        }
        String rest = Ids.remainder(node.id());
        int space = rest.indexOf(' ');
        return space > 0 ? rest.substring(0, space) : ANY;
    }

    /** {@code attrs.path}. 없으면 ID의 나머지에서 읽는다. */
    private static String rawPath(Node endpoint) {
        String path = endpoint.attrString("path");
        if (path != null) {
            return path;
        }
        String rest = Ids.remainder(endpoint.id());
        int space = rest.indexOf(' ');
        return space > 0 ? rest.substring(space + 1) : null;
    }

    private static boolean samePath(String endpointPath, String path) {
        return endpointPath != null && trimTrailingSlash(endpointPath).equals(path);
    }

    private static String trimTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** 중괄호 밖의 첫 {@code ?}나 {@code #}부터 뗀다({@code {id:[0-9]+?}} 같은 경로 변수의 정규식은 건드리지 않는다). */
    static String stripQueryAndFragment(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if ((c == '?' || c == '#') && depth == 0) {
                return i == 0 ? "/" : path.substring(0, i);
            }
        }
        return path;
    }

    /**
     * 경로 패턴(template)에 구체 경로가 맞으면 패턴의 느슨함(변수·와일드카드 수, {@code **}는 100)을, 패턴이 아니거나 맞지 않으면 -1을 돌려준다.
     * 경로 변수 {@code {name}}, {@code {name:정규식}}, 풀리지 않은 프로퍼티 {@code ${name}}, {@code *}는 한 세그먼트 안의 글자들에,
     * {@code **}와 {@code {*name}}은 여러 세그먼트에 맞는다.
     */
    static int matchLooseness(String template, String path) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        int looseness = 0;
        String t = trimTrailingSlash(template);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            String part = null;
            if (c == '{' || (c == '$' && i + 1 < t.length() && t.charAt(i + 1) == '{')) {
                int open = c == '{' ? i : i + 1;
                int close = closingBrace(t, open);
                if (close > 0) {
                    boolean catchAll = c == '{' && t.startsWith("*", open + 1);
                    part = catchAll ? ".*" : "[^/]+";
                    looseness += catchAll ? 100 : 1;
                    i = close;
                }
            } else if (c == '*') {
                if (i + 1 < t.length() && t.charAt(i + 1) == '*') {
                    // "/**"로 끝나면 그 앞 경로 자체에도 맞는다: /api/** ↔ /api
                    boolean trailing = i + 2 == t.length() && literal.length() > 0 && literal.charAt(literal.length() - 1) == '/';
                    if (trailing) {
                        literal.setLength(literal.length() - 1);
                        part = "(?:/.*)?";
                    } else {
                        part = ".*";
                    }
                    looseness += 100;
                    i++;
                } else {
                    part = "[^/]*";
                    looseness += 1;
                }
            }
            if (part == null) {
                literal.append(c);
            } else {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(part);
            }
        }
        if (looseness == 0) {
            return -1;
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.matches(regex.toString(), path) ? looseness : -1;
    }

    /** {@code open} 위치의 여는 중괄호에 짝이 되는 닫는 중괄호의 위치. 없으면 -1. */
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
}
