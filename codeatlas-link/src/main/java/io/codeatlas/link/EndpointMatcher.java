package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.link.PathPattern.Segment;
import io.codeatlas.link.PathPattern.SegmentType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 요청 URL을 그래프의 HTTP_ENDPOINT와 맞춘다. HTTP 연결과 redirect·forward 연결이 함께 쓴다.
 *
 * <p>맞추는 순서(앞 단계에서 맞으면 뒤는 보지 않는다):
 * <ol>
 * <li>경로 전체가 엔드포인트 경로 전체와 맞는다(프로퍼티 자리표시자는 값으로 치환한 경로를 쓴다). 추측이 없으면 RESOLVED.</li>
 * <li>엔드포인트 경로에서 맨 앞 자리표시자 세그먼트({@code ${adminPath}})를 뗀 나머지와 맞는다 → INFERRED, {@code strippedPlaceholder}.</li>
 * <li>요청의 접두 세그먼트(서블릿 매핑, 컨텍스트 경로 1~2개)를 떼면 맞는다 → INFERRED, {@code strippedPrefix}.
 * 서블릿 매핑이 아닌 접두는 그 첫 세그먼트가 선언된 엔드포인트 경로의 첫 고정 세그먼트로 쓰이면 떼지 않는다(F-012-R49).</li>
 * </ol>
 * 2·3단계는 고정 세그먼트가 하나 이상 일치할 때만 인정한다(고정 조각 없는 루트 핸들러로 끌려가지 않게).
 * 요청 맨 앞의 {@code {*}}는 0개 이상의 미지 접두이고, 상대 URL은 기준 디렉터리로 풀거나 고정 세그먼트의 접미로만 맞춘다.
 *
 * <p>엔드포인트를 첫 고정 세그먼트(상대 URL용으로 마지막 고정 세그먼트도)로 색인해 두므로 엔드포인트가 많아도 요청마다 전부 훑지 않는다.
 */
final class EndpointMatcher {

    /**
     * @param path 매칭에 쓰는 경로(자리표시자를 치환한 것. 못 풀었으면 원문 그대로)
     * @param rawPath 엔드포인트 노드에 적힌 경로
     * @param placeholder 원래 경로의 맨 앞 세그먼트가 자리표시자 하나면 그 원문({@code ${adminPath}}), 아니면 null
     * @param afterPlaceholder 맨 앞 자리표시자 세그먼트를 뗀 나머지(없으면 빈 목록)
     * @param handlerFiles ROUTES_TO 핸들러 메서드의 소스 파일
     * @param modules 핸들러 파일들의 모듈 루트
     * @param viewModules 핸들러 없이 뷰를 그린다고 선언한({@code <mvc:view-controller>}의 {@code viewDeclarations}) 설정 파일들의 모듈 루트
     */
    record Endpoint(Node node, String method, String path, String rawPath, List<Segment> segments, boolean trailingSlash,
            String placeholder, List<Segment> afterPlaceholder, Set<String> handlerFiles, Set<String> modules, Set<String> viewModules) {

        /** 이 엔드포인트를 선언한 모듈들: 핸들러 메서드가 있거나 view-controller 선언이 있는 모듈(F-012-R37). */
        Set<String> declaringModules() {
            if (viewModules.isEmpty()) {
                return modules;
            }
            Set<String> all = new LinkedHashSet<>(modules);
            all.addAll(viewModules);
            return all;
        }
    }

    /**
     * 맞출 요청.
     *
     * @param method 요청 메서드. 모르면 ANY
     * @param url 요청 URL 패턴(동적 조각은 {@code {*}})
     * @param relativeHint 분석기가 상대 URL이라고 표시했는지
     * @param sourceFile 요청을 낸 파일(모듈 경계 판단용). 모르면 null
     * @param bases 상대 URL의 기준 디렉터리 후보({@code /sso}, 루트는 빈 문자열). 모르면 빈 목록
     * @param methods 메서드를 하나로 정하지 못한 요청의 메서드 후보({@code attrs.httpMethods}). 있으면 그 메서드들의 엔드포인트와
     *                메서드를 가리지 않는 엔드포인트만 후보다(F-012-R39). 없으면 빈 목록
     */
    record Request(String method, String url, boolean relativeHint, String sourceFile, List<String> bases, List<String> methods) {

        Request(String method, String url, boolean relativeHint, String sourceFile, List<String> bases) {
            this(method, url, relativeHint, sourceFile, bases, List.of());
        }
    }

    /** 맞은 엔드포인트 하나와 그 엣지에 남길 근거. */
    record Hit(Endpoint endpoint, Map<String, Object> attrs) {
    }

    /**
     * 맞춘 결과.
     *
     * @param hits 맞은 엔드포인트. 둘 이상이면 동률이라 고르지 못한 것이다
     * @param confidence 엣지에 쓸 신뢰도
     * @param skipped 요청이 아닌 URL(javascript:, mailto:, 빈 값 등)이라 맞춰 보지 않았다
     * @param path 쿼리 문자열 등을 뗀 URL
     */
    record Result(List<Hit> hits, Confidence confidence, boolean skipped, String path) {
        boolean matched() {
            return !hits.isEmpty();
        }
    }

    private static final Pattern HOST = Pattern.compile("^(?:[a-zA-Z][a-zA-Z0-9+.-]*:)?//[^/]*");
    private static final Pattern NON_REQUEST = Pattern.compile("^(?:javascript|mailto|tel|data|about|blob|sms):.*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "js", "mjs", "css", "map", "png", "jpg", "jpeg", "gif", "svg", "ico", "bmp", "webp",
            "html", "htm", "jsp", "jspf", "woff", "woff2", "ttf", "eot", "otf", "pdf", "zip", "txt", "swf", "mp3", "mp4");
    static final String RESOLVED_PATH = "resolvedPath";
    private static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String UNKNOWN_PREFIX = "{*}";

    private final GraphModel graph;
    private final ModuleRoots modules;
    private final List<Endpoint> all = new ArrayList<>();
    private final Map<String, Endpoint> byNodeId = new HashMap<>();
    private final Index full = new Index();
    /** 맨 앞 자리표시자 세그먼트를 뗀 나머지로 만든 색인. */
    private final Index afterPlaceholder = new Index();
    private final Map<String, List<Endpoint>> byLast = new HashMap<>();
    private final List<Endpoint> partialLast = new ArrayList<>();
    private final List<Endpoint> roots = new ArrayList<>();
    /** 지금 맞추는 요청의 메서드 후보({@link Request#methods()}). 맞추는 동안만 채워 둔다. */
    private Set<String> methodCandidates = Set.of();
    /** web.xml의 서블릿 매핑에서 얻은 접두 세그먼트(예: {@code /api/*} → [api]). */
    private final List<List<String>> servletPrefixes = new ArrayList<>();

    /** 첫 세그먼트로 찾는 색인. */
    private static final class Index {
        final Map<String, List<Endpoint>> byFirst = new HashMap<>();
        final List<Endpoint> wildFirst = new ArrayList<>();
        final List<Endpoint> everything = new ArrayList<>();

        void add(Endpoint endpoint, List<Segment> segments) {
            everything.add(endpoint);
            if (segments.get(0).literal()) {
                byFirst.computeIfAbsent(segments.get(0).text(), k -> new ArrayList<>()).add(endpoint);
            } else {
                wildFirst.add(endpoint);
            }
        }

        List<Endpoint> candidates(List<Segment> request) {
            if (request.isEmpty()) {
                return List.of();
            }
            if (!request.get(0).literal()) {
                return everything;
            }
            List<Endpoint> indexed = byFirst.getOrDefault(request.get(0).text(), List.of());
            if (wildFirst.isEmpty()) {
                return indexed;
            }
            List<Endpoint> candidates = new ArrayList<>(indexed);
            candidates.addAll(wildFirst);
            return candidates;
        }
    }

    EndpointMatcher(AnalysisContext ctx) {
        this(ctx.graph(), ProjectProperties.of(ctx), ModuleRoots.of(ctx.files()));
    }

    EndpointMatcher(GraphModel graph, ProjectProperties properties, ModuleRoots modules) {
        this.graph = graph;
        this.modules = modules;
        for (Node node : graph.nodes(NodeKind.HTTP_ENDPOINT)) {
            add(endpoint(node, properties));
        }
        // Spring Boot 메인 클래스가 있는 모듈은 독립 배포 단위다(F-012-R38)
        for (Edge annotated : graph.incoming(Ids.javaType(SPRING_BOOT_APPLICATION), EdgeKind.ANNOTATED_BY)) {
            Node type = graph.node(annotated.from()).orElse(null);
            if (type != null && type.location() != null && !ModuleRoots.isTestPath(type.location().file())) {
                modules.markWebApp(modules.rootOf(type.location().file()));
            }
        }
        for (Node file : graph.nodes(NodeKind.FILE)) {
            for (String mapping : file.attrList("servletMappings")) {
                if (mapping.endsWith("/*") && mapping.length() > 2) {
                    List<String> prefix = PathPattern.parse(mapping.substring(0, mapping.length() - 2)).stream().map(Segment::text).toList();
                    if (!prefix.isEmpty() && !servletPrefixes.contains(prefix)) {
                        servletPrefixes.add(prefix);
                    }
                }
            }
        }
    }

    private Endpoint endpoint(Node node, ProjectProperties properties) {
        String rest = Ids.remainder(node.id());
        int space = rest.indexOf(' ');
        String method = node.attrString("httpMethod");
        String raw = node.attrString("path");
        if (method == null) {
            method = space > 0 ? rest.substring(0, space) : Ids.ANY_METHOD;
        }
        if (raw == null) {
            raw = space > 0 ? rest.substring(space + 1) : rest;
        }
        Set<String> handlerFiles = new LinkedHashSet<>();
        Set<String> handlerModules = new LinkedHashSet<>();
        for (Edge route : graph.outgoing(node.id(), EdgeKind.ROUTES_TO)) {
            Node handler = graph.node(route.to()).orElse(null);
            if (handler != null && handler.location() != null && handler.location().file() != null) {
                handlerFiles.add(handler.location().file());
                String module = modules.rootOf(handler.location().file());
                if (module != null) {
                    handlerModules.add(module);
                }
            }
        }
        // 핸들러 없이 뷰를 그리는 선언(springxml의 viewDeclarations: <파일>:<줄>:<열> <view-name>)의 모듈
        Set<String> viewModules = new LinkedHashSet<>();
        for (String declaration : node.attrList("viewDeclarations")) {
            int cut = declaration == null ? -1 : declaration.indexOf(' ');
            String where = cut > 0 ? declaration.substring(0, cut) : declaration;
            String[] parts = where == null ? new String[0] : where.split(":");
            if (parts.length >= 2) {
                String module = modules.rootOf(String.join(":", java.util.Arrays.copyOf(parts, Math.max(1, parts.length - 2))));
                if (module != null) {
                    viewModules.add(module);
                }
            }
        }
        if (viewModules.isEmpty() && node.attrBoolean("viewController") && node.location() != null) {
            String module = modules.rootOf(node.location().file());
            if (module != null) {
                viewModules.add(module);
            }
        }
        String recorded = node.attrString(RESOLVED_PATH);
        String path = recorded != null && !recorded.isBlank() ? recorded : resolve(raw, handlerFiles, properties);
        PathPattern.Path parsed = PathPattern.parsePath(path);
        List<Segment> rawSegments = PathPattern.parse(raw);
        String placeholder = null;
        List<Segment> after = List.of();
        if (!rawSegments.isEmpty() && rawSegments.get(0).type() == SegmentType.PLACEHOLDER) {
            placeholder = rawSegments.get(0).text();
            int at = raw.indexOf(placeholder);
            after = PathPattern.parse(resolve(raw.substring(at + placeholder.length()), handlerFiles, properties));
        }
        return new Endpoint(node, Ids.httpMethod(method), path, raw, parsed.segments(), parsed.trailingSlash(),
                placeholder, after, Set.copyOf(handlerFiles), Set.copyOf(handlerModules), Set.copyOf(viewModules));
    }

    /**
     * 경로의 프로퍼티 자리표시자를 치환한다. 핸들러 파일마다(모듈마다) 값이 다르게 풀리면 어느 것인지 알 수 없으므로 원문을 그대로 둔다.
     * 못 푼 자리표시자는 원문 그대로 남는다(PLACEHOLDER 세그먼트가 된다).
     */
    private static String resolve(String raw, Set<String> handlerFiles, ProjectProperties properties) {
        if (!ProjectProperties.hasPlaceholder(raw)) {
            return raw;
        }
        Set<String> resolved = new LinkedHashSet<>();
        if (handlerFiles.isEmpty()) {
            resolved.add(properties.resolve(raw, null).text());
        } else {
            handlerFiles.forEach(file -> resolved.add(properties.resolve(raw, file).text()));
        }
        if (resolved.size() != 1) {
            return raw;
        }
        String text = resolved.iterator().next();
        return text.equals(raw) ? raw : ("/" + text).replaceAll("/{2,}", "/");
    }

    private void add(Endpoint endpoint) {
        all.add(endpoint);
        byNodeId.put(endpoint.node().id(), endpoint);
        List<Segment> segments = endpoint.segments();
        if (segments.isEmpty()) {
            roots.add(endpoint);
            return;
        }
        full.add(endpoint, segments);
        if (!endpoint.afterPlaceholder().isEmpty()) {
            afterPlaceholder.add(endpoint, endpoint.afterPlaceholder());
        }
        Segment last = segments.get(segments.size() - 1);
        if (last.literal()) {
            byLast.computeIfAbsent(last.text(), k -> new ArrayList<>()).add(endpoint);
        } else if (last.type() == SegmentType.PARTIAL) {
            partialLast.add(endpoint);
        }
    }

    /** 자리표시자를 전부 치환한 엔드포인트 노드에 {@code attrs.resolvedPath}를 남긴다(일부만 풀린 경로는 남기지 않는다). */
    void recordResolvedPaths() {
        for (Endpoint endpoint : all) {
            if (!endpoint.path().equals(endpoint.rawPath()) && !ProjectProperties.hasPlaceholder(endpoint.path())
                    && endpoint.node().attrString(RESOLVED_PATH) == null) {
                graph.putAttrs(endpoint.node().id(), Map.of(RESOLVED_PATH, endpoint.path()));
            }
        }
    }

    ModuleRoots modules() {
        return modules;
    }

    /**
     * 페이지 안의 상대 URL이 기준으로 삼을 디렉터리: 그 페이지를 그리는(RENDERS) 핸들러로 가는 엔드포인트 경로의 디렉터리.
     * 경로에 값을 모르는 자리표시자나 섞인 세그먼트가 있는 엔드포인트는 기준으로 쓰지 않는다. 경로 변수는 {@code {*}}로 둔다.
     */
    List<String> baseDirectoriesOfPage(String pageId) {
        Set<String> bases = new LinkedHashSet<>();
        for (Edge renders : graph.incoming(pageId, EdgeKind.RENDERS)) {
            if (renders.confidence() != Confidence.UNRESOLVED) {
                bases.addAll(baseDirectoriesOfHandler(renders.from()));
            }
        }
        return List.copyOf(bases);
    }

    /** 핸들러 메서드로 가는 엔드포인트 경로의 디렉터리들. {@code redirect:list} 같은 상대 대상의 기준이다. */
    List<String> baseDirectoriesOfHandler(String handlerId) {
        Set<String> bases = new LinkedHashSet<>();
        for (Edge route : graph.incoming(handlerId, EdgeKind.ROUTES_TO)) {
            Endpoint endpoint = byNodeId.get(route.from());
            if (endpoint == null) {
                continue;
            }
            List<Segment> directory = endpoint.trailingSlash() || endpoint.segments().isEmpty()
                    ? endpoint.segments() : endpoint.segments().subList(0, endpoint.segments().size() - 1);
            if (directory.stream().allMatch(s -> s.literal() || s.type() == SegmentType.WILDCARD)) {
                StringBuilder base = new StringBuilder();
                directory.forEach(s -> base.append('/').append(s.literal() ? s.text() : UNKNOWN_PREFIX));
                bases.add(base.toString());
            }
        }
        return List.copyOf(bases);
    }

    /** 출처·기준 정보 없이 맞춘다. */
    Result match(String httpMethod, String url, boolean relativeHint) {
        return match(new Request(httpMethod, url, relativeHint, null, List.of()));
    }

    /** 요청 하나를 맞춘다. */
    Result match(Request request) {
        methodCandidates = candidateSet(request);
        try {
            return doMatch(request);
        } finally {
            methodCandidates = Set.of();
        }
    }

    private static Set<String> candidateSet(Request request) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String m : request.methods()) {
            if (m != null && !m.isBlank()) {
                candidates.add(Ids.httpMethod(m));
            }
        }
        candidates.remove(Ids.ANY_METHOD);
        return candidates;
    }

    /**
     * 요청 메서드가 엔드포인트 메서드와 맞는가. 메서드 후보가 있는 요청(F-012-R39)은 그 메서드들의 엔드포인트와 메서드를 가리지 않는
     * 엔드포인트만 받는다. 그 밖에는 같거나 어느 한쪽이 ANY면 맞는다.
     */
    private boolean methodFits(String requestMethod, Endpoint endpoint) {
        if (!methodCandidates.isEmpty()) {
            return endpoint.method().equals(Ids.ANY_METHOD) || methodCandidates.contains(endpoint.method());
        }
        return endpoint.method().equals(requestMethod) || endpoint.method().equals(Ids.ANY_METHOD) || requestMethod.equals(Ids.ANY_METHOD);
    }

    private boolean methodExact(String requestMethod, Endpoint endpoint) {
        return endpoint.method().equals(requestMethod) || methodCandidates.contains(endpoint.method());
    }

    /**
     * 같은 주소의 엔드포인트: base와 경로가 같고(끝 슬래시 포함) 요청 메서드에 맞는 엔드포인트들. action 없는 폼이
     * 자기를 그린 엔드포인트의 경로로 제출하는 것을 잇는 데 쓴다(F-012-R40). 모듈 경계 규칙은 같다.
     */
    Result matchSamePath(String endpointNodeId, Request request) {
        Endpoint base = byNodeId.get(endpointNodeId);
        if (base == null) {
            return unmatched("");
        }
        methodCandidates = candidateSet(request);
        try {
            String method = Ids.httpMethod(request.method());
            String address = PathPattern.join(base.segments()) + (base.trailingSlash() ? "/" : "");
            List<Scored> hits = new ArrayList<>();
            for (Endpoint endpoint : all) {
                if (methodFits(method, endpoint)
                        && address.equals(PathPattern.join(endpoint.segments()) + (endpoint.trailingSlash() ? "/" : ""))) {
                    hits.add(new Scored(endpoint, 0, 0, false, methodExact(method, endpoint), false, false));
                }
            }
            return finish(narrow(hits, request), new LinkedHashMap<>(), true, base.path());
        } finally {
            methodCandidates = Set.of();
        }
    }

    private Result doMatch(Request request) {
        String method = Ids.httpMethod(request.method());
        String cleaned = stripQuery(request.url() == null ? "" : request.url().trim());
        if (cleaned.isEmpty() || NON_REQUEST.matcher(cleaned).matches()) {
            return new Result(List.of(), Confidence.UNRESOLVED, true, cleaned);
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        String path = cleaned;
        Matcher host = HOST.matcher(cleaned);
        boolean hostStripped = host.find();
        if (hostStripped) {
            attrs.put("strippedPrefix", host.group());
            path = cleaned.substring(host.end());
        }
        PathPattern.Path parsed = PathPattern.parseRequest(path);
        List<Segment> segments = parsed.segments();
        boolean slash = parsed.trailingSlash();
        // 맨 앞의 {*}: 알 수 없는 기준 URL(컨텍스트 경로, base URL). 경로 세그먼트 하나가 아니라 0개 이상의 미지 접두다
        boolean leadingUnknown = !hostStripped && !path.startsWith("/") && !segments.isEmpty()
                && segments.get(0).type() == SegmentType.WILDCARD && segments.get(0).text().equals(UNKNOWN_PREFIX);
        boolean absolute = hostStripped || path.startsWith("/") || path.startsWith(UNKNOWN_PREFIX);
        boolean relative = !hostStripped && !leadingUnknown && (request.relativeHint() || !absolute);

        if (leadingUnknown) {
            return matchUnknownPrefix(method, segments.subList(1, segments.size()), slash, request, attrs, cleaned);
        }
        if (relative) {
            return matchRelative(method, segments, slash, request, attrs, cleaned);
        }
        if (segments.isEmpty()) {
            // 요청 / : 루트 엔드포인트와만 맞는다
            return finish(narrow(score(method, segments, slash, roots, false), request), attrs, false, cleaned);
        }
        if (segments.stream().noneMatch(Segment::fixed)) {
            // 고정된 조각이 하나도 없는 URL은 어느 엔드포인트와도 맞출 근거가 없다
            return unmatched(cleaned);
        }
        Narrowed hits = narrow(score(method, segments, slash, full.candidates(segments), false), request);
        // {*}text 처럼 미지 접두가 첫 조각에 붙은 URL은 전체 경로로만 맞춘다(무엇을 떼야 할지 알 수 없다)
        boolean strippable = hostStripped || path.startsWith("/");
        if (hits.scored().isEmpty() && strippable) {
            hits = narrow(withFixed(score(method, segments, slash, afterPlaceholder.candidates(segments), true)), request);
        }
        if (hits.scored().isEmpty() && strippable) {
            // 맨 앞 세그먼트가 선언된 엔드포인트 경로의 첫 세그먼트면 실제 매핑의 일부다: 컨텍스트 경로로 보고 떼지 않는다(F-012-R49).
            // web.xml의 서블릿 매핑과 맞는 접두는 근거가 있으므로 그대로 뗀다
            boolean mappedFirst = segments.get(0).literal() && full.byFirst.containsKey(segments.get(0).text());
            for (int strip : stripCounts(segments)) {
                if (mappedFirst && !servletPrefix(segments, strip)) {
                    continue;
                }
                List<Segment> rest = segments.subList(strip, segments.size());
                hits = narrow(withFixed(score(method, rest, slash, full.candidates(rest), false)), request);
                if (!hits.scored().isEmpty()) {
                    attrs.merge("strippedPrefix", PathPattern.join(segments.subList(0, strip)), (a, b) -> a.toString() + b);
                    break;
                }
            }
        }
        return finish(hits, attrs, false, cleaned);
    }

    /** {@code {*}/rest}: 나머지 경로 전체가 엔드포인트 경로 전체(또는 맨 앞 자리표시자를 뗀 나머지)와 맞을 때만 맞춘다. */
    private Result matchUnknownPrefix(String method, List<Segment> rest, boolean slash, Request request, Map<String, Object> attrs, String cleaned) {
        if (rest.stream().noneMatch(Segment::fixed)) {
            return unmatched(cleaned);
        }
        attrs.put("strippedPrefix", UNKNOWN_PREFIX);
        Narrowed hits = narrow(withFixed(score(method, rest, slash, full.candidates(rest), false)), request);
        if (hits.scored().isEmpty()) {
            hits = narrow(withFixed(score(method, rest, slash, afterPlaceholder.candidates(rest), true)), request);
        }
        if (hits.scored().isEmpty()) {
            for (List<String> prefix : servletPrefixes) {
                if (prefix.size() < rest.size() && startsWith(rest, prefix)) {
                    List<Segment> tail = rest.subList(prefix.size(), rest.size());
                    hits = narrow(withFixed(score(method, tail, slash, full.candidates(tail), false)), request);
                    if (!hits.scored().isEmpty()) {
                        attrs.put("strippedPrefix", UNKNOWN_PREFIX + PathPattern.join(rest.subList(0, prefix.size())));
                        break;
                    }
                }
            }
        }
        return finish(hits, attrs, true, cleaned);
    }

    /**
     * 상대 URL. 기준 디렉터리를 알면 그 아래 경로로 풀어 전체 경로로 맞추고(없으면 다른 디렉터리의 같은 이름으로 잇지 않는다),
     * 모르면 엔드포인트의 고정 세그먼트 접미와 맞는 것이 정확히 하나일 때만 잇는다.
     */
    private Result matchRelative(String method, List<Segment> segments, boolean slash, Request request, Map<String, Object> attrs, String cleaned) {
        int ups = 0;
        int skip = 0;
        while (skip < segments.size() && (segments.get(skip).text().equals(".") || segments.get(skip).text().equals(".."))) {
            if (segments.get(skip).text().equals("..")) {
                ups++;
            }
            skip++;
        }
        List<Segment> tail = segments.subList(skip, segments.size());
        if (tail.stream().noneMatch(Segment::fixed)) {
            return unmatched(cleaned);
        }
        attrs.put("relative", true);
        // 정적 파일 꼴의 상대 링크(login.html)는 기준 디렉터리의 다른 파일을 가리킨다: 경로 변수의 값으로 보지 않는다(F-012-R46)
        Segment lastSegment = tail.get(tail.size() - 1);
        boolean staticFile = !slash && lastSegment.literal() && isStaticResource(lastSegment.text());
        if (!request.bases().isEmpty()) {
            Map<Endpoint, Scored> found = new LinkedHashMap<>();
            Map<Endpoint, String> usedBase = new HashMap<>();
            for (String base : request.bases()) {
                List<Segment> target = new ArrayList<>(PathPattern.parse(base));
                for (int i = 0; i < ups && !target.isEmpty(); i++) {
                    target.remove(target.size() - 1);
                }
                target.addAll(tail);
                for (Scored hit : score(method, target, slash, full.candidates(target), false)) {
                    if (staticFile && endsWithVariable(hit.endpoint())) {
                        continue;
                    }
                    Scored previous = found.get(hit.endpoint());
                    if (previous == null || hit.cost() < previous.cost()) {
                        found.put(hit.endpoint(), hit);
                        usedBase.put(hit.endpoint(), base);
                    }
                }
            }
            Narrowed hits = narrow(new ArrayList<>(found.values()), request);
            Result result = finish(hits, attrs, true, cleaned);
            result.hits().forEach(hit -> hit.attrs().put("relativeBase", usedBase.get(hit.endpoint())));
            if (result.matched() || ups > 0 || tail.size() < 2) {
                return result;
            }
            // 기준 디렉터리 아래에는 없다. 여러 조각 상대 경로가 엔드포인트 경로 전체와 같으면 맨 앞의 /를 빠뜨린 것으로 본다
            // (<form action="cop/bbs/list.do">). 한 조각짜리는 다른 디렉터리의 같은 이름일 수 있어 잇지 않는다
            List<Scored> whole = suffixHits(method, tail, slash).stream()
                    .filter(hit -> hit.endpoint().segments().size() == tail.size()).toList();
            Narrowed wholePath = narrow(whole, request);
            wholePath = wholePath.with(collapseSlashVariants(wholePath.scored()));
            return wholePath.scored().size() == 1 ? finish(wholePath, attrs, true, cleaned) : result;
        }
        Narrowed hits = narrow(suffixHits(method, tail, slash), request);
        hits = hits.with(collapseSlashVariants(hits.scored()));
        if (hits.scored().size() != 1) {
            return unmatched(cleaned);
        }
        return finish(hits, attrs, true, cleaned);
    }

    /** 엔드포인트 경로의 마지막 세그먼트가 경로 변수 하나({@code {id}})인가. */
    private static boolean endsWithVariable(Endpoint endpoint) {
        List<Segment> segments = endpoint.segments();
        return !segments.isEmpty() && segments.get(segments.size() - 1).type() == SegmentType.WILDCARD;
    }

    /** 확장자로 본 정적 자원 여부. 맞는 엔드포인트가 없을 때 미해결로 남길지 정하는 데 쓴다. */
    static boolean isStaticResource(String url) {
        String path = stripQuery(url == null ? "" : url);
        int slash = path.lastIndexOf('/');
        String last = path.substring(slash + 1);
        int dot = last.lastIndexOf('.');
        return dot >= 0 && STATIC_EXTENSIONS.contains(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String stripQuery(String url) {
        int cut = url.length();
        for (char c : new char[] {'?', '#'}) {
            int at = url.indexOf(c);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        return url.substring(0, cut);
    }

    private static Result unmatched(String cleaned) {
        return new Result(List.of(), Confidence.UNRESOLVED, false, cleaned);
    }

    /**
     * @param viaPlaceholder 맨 앞 자리표시자 세그먼트를 뗀 나머지와 맞았다
     * @param slashDiffers 끝 슬래시가 요청과 다르다
     */
    private record Scored(Endpoint endpoint, int cost, int fixed, boolean guess, boolean methodExact, boolean viaPlaceholder, boolean slashDiffers) {
    }

    /**
     * 모듈 경계로 좁힌 후보.
     *
     * @param module 요청 모듈에 선언이 있어 같은 모듈 매칭으로 좁혔으면 그 모듈 루트, crossModule 요청의 페이지를 그리는 모듈로
     *               좁혔으면 그 모듈 루트(F-012-R48), 아니면 null
     */
    private record Narrowed(List<Scored> scored, boolean crossModule, String module) {

        Narrowed with(List<Scored> replaced) {
            return new Narrowed(replaced, crossModule, module);
        }
    }

    private List<Scored> score(String method, List<Segment> request, boolean slash, Collection<Endpoint> candidates, boolean viaPlaceholder) {
        List<Scored> hits = new ArrayList<>();
        for (Endpoint endpoint : candidates) {
            boolean exact = methodExact(method, endpoint);
            if (!methodFits(method, endpoint)) {
                continue;
            }
            PathPattern.Match match = PathPattern.match(request, viaPlaceholder ? endpoint.afterPlaceholder() : endpoint.segments());
            if (match != null) {
                boolean slashDiffers = slash != endpoint.trailingSlash();
                hits.add(new Scored(endpoint, match.cost() + (slashDiffers ? 1 : 0), match.fixed(), match.guess(), exact, viaPlaceholder, slashDiffers));
            }
        }
        return hits;
    }

    /** 상대 URL의 접미 매칭: 엔드포인트의 끝 세그먼트들이 고정 세그먼트일 때만(경로 변수는 요청의 {@code {*}}와만) 맞춘다. */
    private List<Scored> suffixHits(String method, List<Segment> request, boolean slash) {
        Segment last = request.get(request.size() - 1);
        Collection<Endpoint> candidates;
        if (last.literal()) {
            List<Endpoint> list = new ArrayList<>(byLast.getOrDefault(last.text(), List.of()));
            list.addAll(partialLast);
            candidates = list;
        } else {
            candidates = all;
        }
        List<Scored> hits = new ArrayList<>();
        for (Endpoint endpoint : candidates) {
            boolean exact = methodExact(method, endpoint);
            if (!methodFits(method, endpoint)) {
                continue;
            }
            List<Segment> target = endpoint.segments();
            if (target.size() < request.size() || (slash && !endpoint.trailingSlash())) {
                continue;   // 디렉터리를 가리키는 상대 링크(../test/)는 끝 슬래시가 없는 엔드포인트의 접미로 보지 않는다
            }
            target = target.subList(target.size() - request.size(), target.size());
            boolean anchored = true;
            for (int i = 0; i < target.size() && anchored; i++) {
                Segment e = target.get(i);
                anchored = e.fixed() || (e.type() == SegmentType.WILDCARD && request.get(i).type() == SegmentType.WILDCARD);
            }
            if (!anchored) {
                continue;
            }
            PathPattern.Match match = PathPattern.match(request, target);
            if (match != null && match.fixed() > 0) {
                boolean slashDiffers = slash != endpoint.trailingSlash();
                hits.add(new Scored(endpoint, match.cost() + (slashDiffers ? 1 : 0), match.fixed(), match.guess(), exact, false, slashDiffers));
            }
        }
        return hits;
    }

    /** 끝 슬래시만 다른 같은 엔드포인트({@code /catalog}와 {@code /catalog/})는 후보 하나로 센다. 요청과 끝 슬래시가 같은 쪽을 남긴다. */
    private static List<Scored> collapseSlashVariants(List<Scored> hits) {
        Map<String, Scored> byPath = new LinkedHashMap<>();
        for (Scored hit : hits) {
            String key = hit.endpoint().method() + " " + PathPattern.join(hit.endpoint().segments());
            byPath.merge(key, hit, (a, b) -> b.cost() < a.cost() ? b : a);
        }
        return new ArrayList<>(byPath.values());
    }

    private static List<Scored> withFixed(List<Scored> hits) {
        return hits.stream().filter(s -> s.fixed() > 0).toList();
    }

    /**
     * 모듈 경계(F-012-R21, R36~R38). 요청을 낸 파일의 모듈에 그 엔드포인트의 선언(핸들러 메서드 또는 view-controller 선언)이 있는
     * 후보가 있으면 그것만 남긴다(같은 모듈 매칭). 하나도 없고 후보가 모두 다른 모듈의 것이면:
     * <ul>
     * <li>요청 모듈이 독립 배포 단위(웹앱)면 다른 웹앱 모듈의 엔드포인트로는 잇지 않는다: 다른 웹앱의 같은 경로는 다른 URL이다.
     * 웹앱이 아닌 모듈(war에 함께 묶이는 라이브러리)의 엔드포인트만 {@code crossModule}로 남긴다.</li>
     * <li>요청 모듈이 웹앱이 아니면(정적 자원·프런트 모듈) {@code crossModule}로 표시한다. 그 페이지를 그리는 핸들러가 모두 한 모듈에
     * 있고 그 모듈에 선언이 있는 후보가 있으면 그 후보만 남기고 그 모듈을 대상 모듈로 적는다(F-012-R48).</li>
     * </ul>
     * 요청 파일의 모듈을 모르면(빌드 파일이 없는 프런트 폴더) 판단하지 않는다.
     */
    private Narrowed narrow(List<Scored> hits, Request request) {
        if (hits.isEmpty()) {
            return new Narrowed(hits, false, null);
        }
        String module = modules.rootOf(request.sourceFile());
        if (module == null) {
            return new Narrowed(hits, false, null);
        }
        // 선언 위치를 모르는 엔드포인트는 다른 모듈이라고 단정하지 않는다
        List<Scored> same = hits.stream()
                .filter(s -> s.endpoint().declaringModules().contains(module) || s.endpoint().declaringModules().isEmpty()).toList();
        if (!same.isEmpty()) {
            return new Narrowed(same, false, module);
        }
        if (modules.isWebApp(module)) {
            List<Scored> bundled = hits.stream()
                    .filter(s -> s.endpoint().declaringModules().stream().anyMatch(m -> !modules.isWebApp(m))).toList();
            return new Narrowed(bundled, !bundled.isEmpty(), null);
        }
        // 정적 UI 모듈의 페이지: 그 페이지를 그리는 핸들러의 모듈이 하나로 정해지고 그 모듈에 선언이 있는 후보가 있으면
        // 그 모듈의 웹앱으로 가는 요청이다(F-012-R48). crossModule은 그대로다
        String rendering = renderingModule(request.sourceFile());
        if (rendering != null) {
            List<Scored> declared = hits.stream().filter(s -> s.endpoint().declaringModules().contains(rendering)).toList();
            if (!declared.isEmpty()) {
                return new Narrowed(declared, true, rendering);
            }
        }
        return new Narrowed(hits, true, null);
    }

    /** 한 파일에서 거슬러 올라가 볼 포함 관계의 상한(순환·지나치게 넓은 레이아웃 방지). */
    private static final int MAX_INCLUDERS = 200;

    /**
     * 요청을 낸 파일(페이지 또는 스크립트)을 그리는 쪽의 모듈(F-012-R48): 그 페이지를 {@code RENDERS}하는 핸들러 메서드의 모듈
     * (view-controller가 그리면 선언한 설정 파일의 모듈)이다. 직접 그리는 핸들러가 없는 조각·스크립트는 그것을 포함하는
     * ({@code INCLUDES}) 페이지를 거슬러 올라가 모은다. 동적 디렉터리 뷰의 여러 후보 가운데 하나인 {@code RENDERS}
     * ({@code dynamicSegment} + {@code candidate})는 근거로 쓰지 않는다.
     *
     * @return 그리는 쪽이 모두 한 모듈이면 그 모듈 루트. 그리는 쪽이 없거나, 여러 모듈이거나, 모듈을 모르는 것이 섞였으면 null
     */
    private String renderingModule(String sourceFile) {
        if (sourceFile == null) {
            return null;
        }
        Set<String> found = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        List<String> queue = new ArrayList<>(List.of(Ids.jspPage(sourceFile), Ids.jsModule(sourceFile)));
        for (int i = 0; i < queue.size() && i < MAX_INCLUDERS; i++) {
            String id = queue.get(i);
            if (!seen.add(id) || !graph.hasNode(id)) {
                continue;
            }
            for (Edge renders : graph.incoming(id, EdgeKind.RENDERS)) {
                if (renders.confidence() == Confidence.UNRESOLVED) {
                    continue;
                }
                if (renders.attrBoolean("dynamicSegment") && renders.attrBoolean("candidate")) {
                    // 동적 디렉터리 뷰의 여러 후보 가운데 하나: 그 핸들러가 정말 이 페이지를 그리는지 모르므로 근거로 쓰지 않는다
                    continue;
                }
                Node renderer = graph.node(renders.from()).orElse(null);
                // view-controller 엔드포인트는 여러 모듈의 선언이 한 노드에 모인다: 엣지의 위치(선언한 설정 파일)로 모듈을 가린다
                String file = renderer != null && renderer.kind() == NodeKind.JAVA_METHOD && renderer.location() != null
                        ? renderer.location().file() : renders.location() == null ? null : renders.location().file();
                String module = modules.rootOf(file);
                if (module == null) {
                    return null;
                }
                found.add(module);
            }
            for (Edge includes : graph.incoming(id, EdgeKind.INCLUDES)) {
                if (includes.confidence() != Confidence.UNRESOLVED
                        && graph.node(includes.from()).filter(n -> n.kind() == NodeKind.JSP_PAGE).isPresent()) {
                    queue.add(includes.from());
                }
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    private Result finish(Narrowed narrowed, Map<String, Object> attrs, boolean inferredOnly, String cleaned) {
        List<Scored> best = best(narrowed.scored());
        if (best.isEmpty()) {
            return unmatched(cleaned);
        }
        boolean guess = best.stream().anyMatch(Scored::guess);
        if (guess) {
            attrs.put("dynamicSegment", true);
        }
        if (best.size() > 1) {
            attrs.put("ambiguous", true);
        }
        if (narrowed.crossModule()) {
            attrs.put("crossModule", true);
        }
        boolean viaPlaceholder = best.stream().anyMatch(Scored::viaPlaceholder);
        Confidence confidence = inferredOnly || best.size() > 1 || guess || viaPlaceholder || narrowed.crossModule()
                || attrs.containsKey("strippedPrefix") ? Confidence.INFERRED : Confidence.RESOLVED;
        List<Hit> hits = new ArrayList<>();
        for (Scored scored : best) {
            Map<String, Object> own = new LinkedHashMap<>(attrs);
            if (scored.viaPlaceholder()) {
                own.put("strippedPlaceholder", scored.endpoint().placeholder());
            }
            if (scored.slashDiffers()) {
                own.put("trailingSlash", true);
            }
            if (narrowed.module() != null && scored.endpoint().declaringModules().size() > 1) {
                // 여러 모듈의 선언이 모인 엔드포인트: 소비자가 그 모듈의 핸들러·화면만 따라가게 모듈을 남긴다(F-012-R37)
                own.put("module", narrowed.module().isEmpty() ? "." : narrowed.module());
            }
            hits.add(new Hit(scored.endpoint(), own));
        }
        return new Result(hits, confidence, false, cleaned);
    }

    /** 가장 구체적인(비용이 가장 낮은) 후보들. 메서드가 정확히 같은 쪽이 ANY보다 앞선다. 동률이면 여럿이 남는다. */
    private static List<Scored> best(List<Scored> hits) {
        if (hits.size() <= 1) {
            return hits;
        }
        Comparator<Scored> order = Comparator.comparingInt(Scored::cost).thenComparing(s -> s.methodExact() ? 0 : 1);
        Scored top = hits.stream().min(order).orElseThrow();
        return hits.stream().filter(s -> order.compare(s, top) == 0)
                .sorted(Comparator.comparing(s -> s.endpoint().node().id())).toList();
    }

    /** 떼어 볼 접두 세그먼트 수: 서블릿 매핑과 맞는 접두, 그다음 1개, 2개. 뗀 뒤에 세그먼트가 하나는 남아야 한다. */
    private List<Integer> stripCounts(List<Segment> segments) {
        Set<Integer> counts = new LinkedHashSet<>();
        for (List<String> prefix : servletPrefixes) {
            if (prefix.size() < segments.size() && startsWith(segments, prefix)) {
                counts.add(prefix.size());
            }
        }
        for (int n = 1; n <= 2; n++) {
            if (n < segments.size()) {
                counts.add(n);
            }
        }
        return new ArrayList<>(counts);
    }

    /** 맨 앞 n개 세그먼트가 web.xml의 서블릿 매핑에서 읽은 접두인가(근거가 있는 접두). */
    private boolean servletPrefix(List<Segment> segments, int n) {
        return servletPrefixes.stream().anyMatch(prefix -> prefix.size() == n && n < segments.size() && startsWith(segments, prefix));
    }

    private static boolean startsWith(List<Segment> segments, List<String> prefix) {
        for (int i = 0; i < prefix.size(); i++) {
            if (!segments.get(i).literal() || !segments.get(i).text().equals(prefix.get(i))) {
                return false;
            }
        }
        return true;
    }
}
