package io.codeatlas.export.common;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 노드를 사람이 읽는 짧은 이름으로 바꾼다. 콜트리, 영향도, CRUD 매트릭스, 리포트가 같은 표기를 쓴다.
 * 노드가 그래프에 없어도 ID만으로 이름을 만든다.
 */
public final class NodeLabels {

    private NodeLabels() {
    }

    /** ID에서 노드 종류를 알아낸다. 노드가 있으면 노드의 종류가 먼저다. */
    public static NodeKind kindOf(GraphModel graph, String id) {
        return graph.node(id).map(Node::kind).orElseGet(() -> Ids.kindOf(id).orElse(NodeKind.UNRESOLVED));
    }

    /**
     * 짧은 표시 이름.
     * <ul>
     * <li>메서드: {@code OrderService#find(Long)} (타입과 매개변수는 단순 이름)</li>
     * <li>구문: {@code OrderMapper.selectOrder <SELECT>}</li>
     * <li>엔드포인트·요청: {@code GET /orders/{id}}. 엔드포인트에 {@code resolvedPath}가 있으면
     *     {@code GET /a/sys/user/list (/${adminPath}/sys/user/list)}. 클라이언트 라우트({@code clientRoute=true})는 경로만: {@code /login}</li>
     * <li>JS 함수: {@code orderApi.js#fetchOrder}</li>
     * </ul>
     */
    public static String label(GraphModel graph, String id) {
        Node node = graph.node(id).orElse(null);
        NodeKind kind = node != null ? node.kind() : Ids.kindOf(id).orElse(NodeKind.UNRESOLVED);
        String rest = Ids.remainder(id);
        switch (kind) {
            case JAVA_METHOD:
                return methodLabel(rest, false);
            case JAVA_FIELD: {
                int hash = rest.indexOf('#');
                return hash < 0 ? rest : Texts.simpleName(rest.substring(0, hash)) + "#" + rest.substring(hash + 1);
            }
            case JAVA_TYPE:
                return Texts.simpleName(rest);
            case SQL_STATEMENT: {
                String type = node == null ? null : node.attrString("statementType");
                return statementName(node, id) + (Texts.isBlank(type) ? "" : " <" + type + ">");
            }
            case HTTP_ENDPOINT: {
                // 프로퍼티 자리표시자를 치환한 경로가 있으면 그것을 쓰고 원문을 괄호로 덧붙인다.
                String resolved = node == null ? null : node.attrString("resolvedPath");
                int space = rest.indexOf(' ');
                if (Texts.isBlank(resolved) || space < 0) {
                    return rest;
                }
                String raw = rest.substring(space + 1);
                return resolved.equals(raw) ? rest : rest.substring(0, space + 1) + Texts.oneLine(resolved) + " (" + raw + ")";
            }
            case HTTP_REQUEST: {
                if (node != null) {
                    String method = requestMethod(node);
                    // 자기 페이지로 내는 폼(action이 없다): ID와 path의 URL 자리가 {self}다. URL처럼 적지 않는다.
                    if (node.attrBoolean("selfSubmit")) {
                        return (Texts.isBlank(method) ? "" : method + " ") + "(" + SELF_SUBMIT + ")";
                    }
                    String path = Texts.isBlank(node.attrString("path")) ? node.attrString("url") : node.attrString("path");
                    if (!Texts.isBlank(path)) {
                        // 클라이언트 라우트(화면 안의 이동)는 HTTP 메서드가 없다: 경로만 적는다.
                        return (Texts.isBlank(method) || isClientRoute(node) ? "" : method + " ") + Texts.oneLine(path);
                    }
                }
                int at = rest.lastIndexOf('@');
                return at > 0 ? rest.substring(0, at) : rest;
            }
            case JS_FUNCTION: {
                int hash = rest.indexOf('#');
                if (hash < 0) {
                    return rest;
                }
                String path = rest.substring(0, hash);
                return path.substring(path.lastIndexOf('/') + 1) + rest.substring(hash);
            }
            case BATCH_STEP: {
                int slash = rest.indexOf('/');
                return slash < 0 ? rest : rest.substring(slash + 1);
            }
            case SQL_FRAGMENT: {
                int dot = rest.lastIndexOf('.');
                if (dot <= 0) {
                    return rest;
                }
                String namespace = rest.substring(0, dot);
                return namespace.substring(namespace.lastIndexOf('.') + 1) + rest.substring(dot);
            }
            case UNRESOLVED: {
                if (Ids.kindOf(id).isPresent() && Ids.kindOf(id).get() != NodeKind.UNRESOLVED) {
                    return labelById(id);
                }
                // 맞는 엔드포인트를 찾지 못한 action 없는 폼: 원문 "<METHOD> (selfSubmit)"을 풀어 쓴다(요청 문맥이 없어 까닭 둘을 함께 적는다).
                String selfMethod = unresolvedSelfSubmitMethod(graph, id);
                if (selfMethod != null) {
                    return selfMethod + " (" + SELF_SUBMIT + ": 이 화면을 그리는 엔드포인트를 모르거나 그 경로에 " + selfMethod + " 매핑 없음)";
                }
                // unresolved:<분류>:<원문> → 원문
                if (node != null && !Texts.isBlank(node.name()) && !node.name().equals(rest)) {
                    return Texts.oneLine(node.name());
                }
                int colon = rest.indexOf(':');
                return Texts.oneLine(id.startsWith("unresolved:") && colon >= 0 ? rest.substring(colon + 1) : rest);
            }
            default:
                return rest;
        }
    }

    /** 종류가 UNRESOLVED로 바뀐 노드(마무리 단계)의 이름을 원래 ID 모양대로 만든다. */
    private static String labelById(String id) {
        NodeKind byId = Ids.kindOf(id).orElse(NodeKind.UNRESOLVED);
        String rest = Ids.remainder(id);
        return byId == NodeKind.JAVA_METHOD ? methodLabel(rest, false) : byId == NodeKind.JAVA_TYPE ? Texts.simpleName(rest) : rest;
    }

    /**
     * 메서드 표시 이름. {@code com.a.B#save(java.lang.String,int[])} → {@code B#save(String, int[])}.
     *
     * @param qualified true면 타입을 전체 이름으로 둔다
     */
    public static String methodLabel(String methodRemainder, boolean qualified) {
        int hash = methodRemainder.indexOf('#');
        if (hash < 0) {
            return methodRemainder;
        }
        String type = methodRemainder.substring(0, hash);
        String member = methodRemainder.substring(hash + 1);
        int open = member.indexOf('(');
        String name = open < 0 ? member : member.substring(0, open);
        StringBuilder out = new StringBuilder(qualified ? type : Texts.simpleName(type)).append('#').append(name);
        if (open >= 0) {
            int close = member.lastIndexOf(')');
            String params = close > open ? member.substring(open + 1, close) : "";
            List<String> simple = new ArrayList<>();
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < params.length(); i++) {
                char c = params.charAt(i);
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                }
                if (c == ',' && depth == 0) {
                    simple.add(Texts.simpleTypeText(current.toString().strip()));
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            if (!current.toString().isBlank()) {
                simple.add(Texts.simpleTypeText(current.toString().strip()));
            }
            out.append('(').append(String.join(", ", simple)).append(')');
        }
        return out.toString();
    }

    /** 구문 이름(종류 표시 없음). 매퍼 구문은 {@code <매퍼 단순 이름>.<구문 ID>}, 그 밖에는 ID의 나머지. */
    public static String statementName(Node node, String id) {
        String rest = Ids.remainder(id);
        if (!id.startsWith("mybatis:stmt:")) {
            return rest;
        }
        String namespace = node == null ? null : node.attrString("namespace");
        String statementId = node == null ? null : node.attrString("statementId");
        if (Texts.isBlank(namespace) || Texts.isBlank(statementId)) {
            int dot = rest.lastIndexOf('.');
            if (dot < 0) {
                return rest;
            }
            namespace = rest.substring(0, dot);
            statementId = rest.substring(dot + 1);
        }
        return namespace.substring(namespace.lastIndexOf('.') + 1) + "." + statementId;
    }

    /**
     * 종류 표시. Java 메서드는 빈 문자열이다(가장 흔해서 생략한다).
     * 루틴은 {@code routineType}을 소문자로 쓴다({@code procedure}, {@code function}, {@code trigger}).
     * 클라이언트 라우트인 요청은 {@code route}다.
     */
    public static String kindTag(GraphModel graph, String id) {
        Node node = graph.node(id).orElse(null);
        NodeKind kind = node != null ? node.kind() : Ids.kindOf(id).orElse(NodeKind.UNRESOLVED);
        return switch (kind) {
            case JAVA_METHOD -> "";
            case JAVA_TYPE -> "type";
            case JAVA_FIELD -> "field";
            case JAVA_PACKAGE -> "package";
            case SPRING_BEAN -> "bean";
            case HTTP_ENDPOINT -> "endpoint";
            case HTTP_REQUEST -> isClientRoute(node) ? "route" : "request";
            case BATCH_JOB -> "job";
            case BATCH_STEP -> "step";
            case MYBATIS_MAPPER -> "mapper";
            case SQL_STATEMENT -> "sql";
            case SQL_FRAGMENT -> "fragment";
            case DB_TABLE -> "table";
            case DB_VIEW -> "view";
            case DB_COLUMN -> "column";
            case DB_ROUTINE -> node == null || Texts.isBlank(node.attrString("routineType")) ? "routine"
                    : node.attrString("routineType").toLowerCase(Locale.ROOT);
            case DB_PACKAGE -> "db-package";
            case DB_SEQUENCE -> "sequence";
            case DB_SYNONYM -> "synonym";
            case JS_MODULE -> "module";
            case JS_FUNCTION -> "js";
            case JSP_PAGE -> "jsp";
            case FILE -> "file";
            case UNRESOLVED -> "unresolved";
        };
    }

    /** 자기 페이지로 내는 폼({@code selfSubmit=true})의 표시. */
    public static final String SELF_SUBMIT = "자기 페이지로 제출";

    /** 후보가 여럿인 자기 페이지 제출의 묶음 줄({@code [ambiguous n]}) 이름. 요청 줄의 문구를 되풀이하지 않는다. */
    public static final String SELF_SUBMIT_CANDIDATES = "후보 엔드포인트";

    /** 미해결 selfSubmit의 이름에 적는 "그리는 엔드포인트의 경로" 수의 상한. */
    private static final int MAX_SELF_SUBMIT_PATHS = 3;

    private static final String UNRESOLVED_ENDPOINT_PREFIX = "unresolved:http-endpoint:";
    private static final String SELF_SUBMIT_SUFFIX = " (selfSubmit)";

    /**
     * id가 "맞는 엔드포인트를 찾지 못한 action 없는 폼"의 미해결 대상({@code unresolved:http-endpoint:<METHOD> (selfSubmit)}. 링커가 남긴다)이면
     * 그 메서드, 아니면 null.
     */
    public static String unresolvedSelfSubmitMethod(GraphModel graph, String id) {
        if (id == null || !id.startsWith(UNRESOLVED_ENDPOINT_PREFIX) || !id.endsWith(SELF_SUBMIT_SUFFIX)) {
            return null;
        }
        String method = id.substring(UNRESOLVED_ENDPOINT_PREFIX.length(), id.length() - SELF_SUBMIT_SUFFIX.length()).strip();
        return method.isEmpty() || method.contains(" ") || method.contains("/") ? null : method;
    }

    /**
     * 요청 requestId에서 본 미해결 selfSubmit 대상의 이름. 그 요청을 낸 화면을 그리는 엔드포인트(화면으로 들어오는 {@code RENDERS}의 출발이
     * 엔드포인트이면 그것, 핸들러 메서드이면 그 메서드로 {@code ROUTES_TO}하는 엔드포인트. 링커가 찾는 방식과 같다)의 경로를 알면 찾던 URL로 적고
     * ({@code POST /place/view.do (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 POST 매핑 없음)}), 모르면 그 사실을 적는다
     * ({@code POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 찾지 못함)}). 미해결 selfSubmit 대상이 아니면 {@link #label}과 같다.
     */
    public static String unresolvedSelfSubmitLabel(GraphModel graph, String unresolvedId, String requestId) {
        String method = unresolvedSelfSubmitMethod(graph, unresolvedId);
        if (method == null) {
            return label(graph, unresolvedId);
        }
        List<String> paths = renderingEndpointPaths(graph, requestId);
        if (paths.isEmpty()) {
            return method + " (" + SELF_SUBMIT + ": 이 화면을 그리는 엔드포인트를 찾지 못함)";
        }
        String shown = String.join(", ", paths.subList(0, Math.min(MAX_SELF_SUBMIT_PATHS, paths.size())))
                + (paths.size() > MAX_SELF_SUBMIT_PATHS ? " 외 " + (paths.size() - MAX_SELF_SUBMIT_PATHS) + "개" : "");
        return method + " " + shown + " (" + SELF_SUBMIT + ": 이 화면을 그리는 엔드포인트의 경로에 " + method + " 매핑 없음)";
    }

    /** 요청을 낸 화면({@code REQUESTS}의 출발인 {@code JSP_PAGE})을 그리는 엔드포인트들의 경로(중복 없이, 사전순). */
    private static List<String> renderingEndpointPaths(GraphModel graph, String requestId) {
        java.util.Set<String> paths = new java.util.TreeSet<>();
        if (requestId == null) {
            return List.of();
        }
        for (io.codeatlas.core.model.Edge requests : graph.incoming(requestId, io.codeatlas.core.model.EdgeKind.REQUESTS)) {
            if (kindOf(graph, requests.from()) != NodeKind.JSP_PAGE) {
                continue;
            }
            for (io.codeatlas.core.model.Edge renders : graph.incoming(requests.from(), io.codeatlas.core.model.EdgeKind.RENDERS)) {
                NodeKind from = kindOf(graph, renders.from());
                if (from == NodeKind.HTTP_ENDPOINT) {
                    addEndpointPath(graph, renders.from(), paths);
                } else if (from == NodeKind.JAVA_METHOD) {
                    for (io.codeatlas.core.model.Edge routes : graph.incoming(renders.from(), io.codeatlas.core.model.EdgeKind.ROUTES_TO)) {
                        addEndpointPath(graph, routes.from(), paths);
                    }
                }
            }
        }
        return new ArrayList<>(paths);
    }

    private static void addEndpointPath(GraphModel graph, String endpointId, java.util.Set<String> into) {
        Node endpoint = graph.node(endpointId).orElse(null);
        if (endpoint == null || endpoint.kind() != NodeKind.HTTP_ENDPOINT) {
            return;
        }
        String path = Texts.isBlank(endpoint.attrString("resolvedPath")) ? endpoint.attrString("path") : endpoint.attrString("resolvedPath");
        if (Texts.isBlank(path)) {
            String rest = Ids.remainder(endpointId);
            int space = rest.indexOf(' ');
            path = space < 0 ? rest : rest.substring(space + 1);
        }
        into.add(Texts.oneLine(path));
    }

    /**
     * 요청의 HTTP 메서드 표시. {@code httpMethod=ANY}인데 가능한 메서드를 아는 요청({@code httpMethods=[POST, PUT]}: 같은 호출이
     * 조건에 따라 메서드를 바꾼다)은 {@code POST|PUT}으로 적는다.
     */
    public static String requestMethod(Node request) {
        String method = request.attrString("httpMethod");
        List<String> several = request.attrList("httpMethods");
        if ((Texts.isBlank(method) || "ANY".equalsIgnoreCase(method)) && !several.isEmpty()) {
            return String.join("|", several);
        }
        return method;
    }

    /** 클라이언트 라우트인지: 서버 요청이 아니라 화면 안의 이동(React Router 등)을 적은 {@code HTTP_REQUEST}({@code clientRoute=true}). */
    public static boolean isClientRoute(Node node) {
        return node != null && node.kind() == NodeKind.HTTP_REQUEST && node.attrBoolean("clientRoute");
    }

    /** {@code [종류] 이름}. 종류 표시가 없으면 이름만. */
    public static String tagged(GraphModel graph, String id) {
        String tag = kindTag(graph, id);
        return tag.isEmpty() ? label(graph, id) : "[" + tag + "] " + label(graph, id);
    }

    /** {@code file:line}. 줄을 모르면 파일만, 위치가 없으면 null. */
    public static String location(SourceLocation location) {
        if (location == null) {
            return null;
        }
        return location.startLine() > 0 ? location.file() + ":" + location.startLine() : location.file();
    }

    public static String location(GraphModel graph, String id) {
        return graph.node(id).map(n -> location(n.location())).orElse(null);
    }
}
