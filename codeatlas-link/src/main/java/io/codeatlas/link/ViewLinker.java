package io.codeatlas.link;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Linker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 뷰 연결(F-012-R10, R11). 핸들러 메서드의 {@code attrs.viewNames}를 JSP와 잇는다.
 *
 * <ul>
 * <li>뷰 리졸버(빈의 property.prefix/suffix, 또는 properties·yml의 spring.mvc.view.prefix/suffix)로 푼 경로의 JSP → RENDERS(RESOLVED).
 * 빈의 값이 프로퍼티 자리표시자면 프로젝트 properties 값으로 치환한다({@link ProjectProperties}).</li>
 * <li>리졸버 정보가 없으면 {@code /<이름>.jsp}로 끝나는 JSP가 하나일 때만 RENDERS(INFERRED).</li>
 * <li>{@code redirect:}·{@code forward:}는 경로가 맞는 엔드포인트의 핸들러로 CALLS(INFERRED, callType).</li>
 * <li>메서드 attrs {@code redirects}·{@code forwards}(서블릿 API의 sendRedirect·forward 경로, F-012-R13)도 같은 방식으로 잇는다.</li>
 * <li>동적 조각이 디렉터리 하나인 뷰 이름({@code themes/{*}/x}, {@code /${app.name}/login.html})은 그 자리에 들어갈 수 있는 실제 디렉터리의
 * JSP를 후보로 삼고, 프로퍼티 값·모듈 디렉터리 이름으로 좁혀 RENDERS(INFERRED, {@code dynamicSegment}, {@code narrowedBy})를 만든다
 * (F-012-R44, R47).</li>
 * <li>못 찾으면 미해결 엣지(RENDERS, 분류 {@value #CATEGORY}).</li>
 * <li>Tiles 설정 파일의 FILE 노드 attrs {@code tilesDefinitions}(jsp 분석기가 남긴다)에 뷰 이름과 맞는 definition이 있으면
 * 리졸버 규칙보다 먼저 그 definition의 template JSP로 RENDERS(RESOLVED, {@code via=tiles}, {@code definition})를 만들고,
 * {@code tilesAttributes}의 JSP(본문·조각)로도 잇는다(F-012-R30).</li>
 * </ul>
 *
 * <p>엣지의 위치는 메서드 attrs의 병렬 목록 {@code viewNameLines}·{@code redirectLines}·{@code forwardLines}({@code 줄:열})가 있으면
 * 그 호출 지점이고(파일은 메서드의 파일), 없으면(옛 그래프) 메서드의 위치다(F-012-R28).
 */
public final class ViewLinker implements Linker {

    static final String CATEGORY = "view";
    /** 메서드 attrs: 서블릿 API로 리다이렉트·포워드하는 경로 패턴(컨텍스트 경로 제외, 동적 조각은 {@code {*}}). java 분석기가 채운다. */
    static final String REDIRECTS = "redirects";
    static final String FORWARDS = "forwards";
    /** 메서드 attrs: {@code viewNames}·{@code redirects}·{@code forwards}와 같은 순서의 호출 지점({@code 줄:열}). java 분석기가 채운다. */
    static final String VIEW_NAME_LINES = "viewNameLines";
    static final String REDIRECT_LINES = "redirectLines";
    static final String FORWARD_LINES = "forwardLines";
    /** 메서드 attrs: {@code viewNames} 가운데 지역 변수에 대입된 리터럴 후보를 펼쳐 만든 이름들. java 분석기가 채운다. */
    static final String VIEW_NAME_CANDIDATES = "viewNameCandidates";
    /** Tiles 설정 파일의 FILE 노드 attrs: {@code <definition 이름>=<template JSP 경로>}. jsp 분석기가 채운다. */
    static final String TILES_DEFINITIONS = "tilesDefinitions";
    /** Tiles 설정 파일의 FILE 노드 attrs: {@code <definition 이름>=<put-attribute의 JSP 경로>}. 정의 하나에 여러 줄일 수 있다. */
    static final String TILES_ATTRIBUTES = "tilesAttributes";
    /** 뷰 이름에서 정적으로 알 수 없는 조각의 자리 표시(java 분석기가 남긴다). */
    private static final String DYNAMIC = "{*}";
    /** 동적 디렉터리 자리에 들어갈 수 있는 JSP가 이 수를 넘으면 잇지 않는다(추측이 된다). */
    private static final int MAX_DIRECTORY_CANDIDATES = 5;
    /** 프로퍼티 자리표시자 하나({@code ${키}}, {@code ${키:기본값}}). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^{}]*}");
    private static final String PREFIX_KEY = "spring.mvc.view.prefix";
    private static final String SUFFIX_KEY = "spring.mvc.view.suffix";
    private static final List<String> WEB_ROOTS = List.of(
            "/src/main/webapp", "/WebContent", "/webapp", "/web", "/public",
            "/src/main/resources/META-INF/resources", "/src/main/resources/static", "/src/main/resources/templates");

    private record Resolver(String prefix, String suffix) {
    }

    /** @param path {@code /}로 시작하는 프로젝트 상대 경로 */
    private record Page(Node node, String path) {
    }

    /** @param directory 뷰 이름의 동적 디렉터리 자리에 들어간 디렉터리 이름 */
    private record DirectoryCandidate(Page page, String directory) {
    }

    @Override
    public String name() {
        return "link-view";
    }

    @Override
    public int order() {
        return 220;
    }

    @Override
    public void link(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        List<Node> handlers = new ArrayList<>(graph.nodes(NodeKind.JAVA_METHOD).stream()
                .filter(m -> !m.attrList("viewNames").isEmpty() || !m.attrList(REDIRECTS).isEmpty() || !m.attrList(FORWARDS).isEmpty()).toList());
        // 핸들러 메서드 없이 뷰를 바로 그리는 엔드포인트(mvc:view-controller. springxml이 viewNames를 남긴다, F-012-R34)
        List<Node> viewEndpoints = graph.nodes(NodeKind.HTTP_ENDPOINT).stream().filter(e -> !e.attrList("viewNames").isEmpty()).toList();
        if (handlers.isEmpty() && viewEndpoints.isEmpty()) {
            return;
        }
        Run run = new Run(graph, collectResolvers(ctx), new EndpointMatcher(ctx), collectTiles(graph), ProjectProperties.of(ctx));
        for (Node endpoint : viewEndpoints) {
            for (Declaration declaration : declarations(endpoint)) {
                // 뷰 이름은 뷰 리졸버를 거친다. 그렇게 찾지 못한 파일 경로 꼴의 이름(/index.jsp)은 컨텍스트 루트 기준의 파일로 본다
                run.linkOrContextRoot(declaration.context(), declaration.viewName(), declaration.context().location());
            }
        }
        for (Node handler : handlers) {
            // 지역 변수 값의 후보를 펼쳐 만든 뷰 이름(java가 viewNameCandidates에 적는다): 어느 것이 그려질지는 실행해야 안다(F-012-R32)
            Set<String> candidates = Set.copyOf(handler.attrList(VIEW_NAME_CANDIDATES));
            for (Site site : sites(handler, "viewNames", VIEW_NAME_LINES)) {
                run.link(handler, site.value(), site.at(), candidates.contains(site.value()));
            }
            // 서블릿 API로 넘어가는 경로(F-012-R13): response.sendRedirect(..), getRequestDispatcher(..).forward(..)
            for (Site site : sites(handler, REDIRECTS, REDIRECT_LINES)) {
                run.linkDispatch(handler, null, site.value(), "redirect", site.at());
            }
            for (Site site : sites(handler, FORWARDS, FORWARD_LINES)) {
                String path = EndpointMatcher.stripQuery(site.value());
                if (path.endsWith(".jsp") || path.endsWith(".jspf")) {
                    // getRequestDispatcher(<경로>)의 경로는 뷰 이름이 아니라 컨텍스트 루트 기준의 파일 경로다(F-012-R35)
                    run.contextRootPage(handler, site.value(), path, site.at());
                } else {
                    run.forward(handler, site.value(), site.value(), site.at());
                }
            }
        }
    }

    // ---- 엔드포인트의 뷰 선언 (F-012-R34) ----

    /**
     * 엔드포인트가 뷰를 바로 그린다는 선언 하나.
     *
     * @param context 선언한 자리를 위치로 가진 엔드포인트 노드(그래프에 넣지 않는다). 같은 경로의 JSP가 여러 모듈에 있을 때
     *                선언한 설정 파일과 가까운 것을 고르고, 엣지의 위치로도 쓴다
     */
    private record Declaration(Node context, String viewName) {
    }

    /**
     * 엔드포인트의 뷰 선언들. springxml이 남긴 {@code viewDeclarations}({@code <파일>:<줄>:<열> <view-name>})가 있으면 선언마다 하나,
     * 없으면 {@code viewNames}마다 엔드포인트의 위치로 하나다. 엔드포인트 노드는 메서드와 경로가 같으면 하나라
     * 여러 모듈의 {@code <mvc:view-controller path="/">}가 한 노드에 모인다.
     */
    private static List<Declaration> declarations(Node endpoint) {
        List<Declaration> declarations = new ArrayList<>();
        for (String entry : endpoint.attrList("viewDeclarations")) {
            int space = entry == null ? -1 : entry.indexOf(' ');
            if (space <= 0 || entry.substring(space + 1).isBlank()) {
                continue;
            }
            String[] where = entry.substring(0, space).split(":");
            if (where.length < 2) {
                continue;
            }
            try {
                int line = Integer.parseInt(where[where.length == 2 ? 1 : where.length - 2]);
                int column = where.length == 2 ? 0 : Integer.parseInt(where[where.length - 1]);
                String file = String.join(":", java.util.Arrays.copyOf(where, where.length == 2 ? 1 : where.length - 2));
                SourceLocation location = SourceLocation.of(file, line, column, line, column);
                declarations.add(new Declaration(endpoint.toBuilder().location(location).build(), entry.substring(space + 1).trim()));
            } catch (NumberFormatException e) {
                // 읽을 수 없는 항목은 건너뛴다
            }
        }
        if (declarations.isEmpty()) {
            for (Site site : sites(endpoint, "viewNames", VIEW_NAME_LINES)) {
                declarations.add(new Declaration(endpoint.toBuilder().location(site.at()).build(), site.value()));
            }
        }
        return declarations;
    }

    // ---- 호출 지점 (F-012-R28) ----

    /**
     * 메서드 attrs 목록의 값 하나와 그 엣지에 쓸 위치.
     *
     * @param at 병렬 목록의 {@code 줄:열}로 만든 위치(파일은 메서드의 파일). 병렬 목록이 없거나 읽을 수 없으면 메서드의 위치
     */
    private record Site(String value, SourceLocation at) {
    }

    /** 목록의 값들(중복과 빈 값 제외, 나온 순서)과 같은 순서의 병렬 목록에서 읽은 위치. */
    private static List<Site> sites(Node handler, String valuesKey, String linesKey) {
        List<String> values = handler.attrList(valuesKey);
        List<String> lines = handler.attrList(linesKey);
        boolean parallel = lines.size() == values.size();
        List<Site> sites = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value == null || value.isBlank() || !seen.add(value.trim())) {
                continue;
            }
            SourceLocation at = parallel ? callSite(handler.location(), lines.get(i)) : handler.location();
            sites.add(new Site(value.trim(), at));
        }
        return sites;
    }

    /** {@code 줄:열}(또는 {@code 줄})을 메서드 파일 안의 위치로 바꾼다. 읽을 수 없으면 메서드의 위치 그대로. */
    static SourceLocation callSite(SourceLocation method, String lineAndColumn) {
        if (method == null || lineAndColumn == null) {
            return method;
        }
        String[] parts = lineAndColumn.trim().split(":");
        try {
            int line = Integer.parseInt(parts[0].trim());
            int column = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (line <= 0 || column < 0 || parts.length > 2) {
                return method;
            }
            return SourceLocation.of(method.file(), line, column, line, column);
        } catch (NumberFormatException e) {
            return method;
        }
    }

    // ---- Tiles 정의 수집 (F-012-R30) ----

    /**
     * Tiles definition 하나.
     *
     * @param name definition 이름 원문({@code *}·{@code **} 와일드카드가 있을 수 있다)
     * @param template template JSP 경로({@code {n}} 원문)
     * @param file 정의한 설정 파일(프로젝트 상대 경로)
     * @param pattern 와일드카드 이름이면 뷰 이름과 맞출 정규식, 아니면 null
     * @param attributes put-attribute의 JSP 경로들({@code {n}} 원문)
     */
    private record TilesDefinition(String name, String template, String file, Pattern pattern, List<String> attributes) {

        /** 뷰 이름이 이 definition과 맞으면 와일드카드에 잡힌 조각들(없으면 빈 목록), 맞지 않으면 null. */
        List<String> match(String viewName) {
            if (pattern == null) {
                return name.equals(viewName) ? List.of() : null;
            }
            Matcher m = pattern.matcher(viewName);
            if (!m.matches()) {
                return null;
            }
            List<String> groups = new ArrayList<>();
            for (int i = 1; i <= m.groupCount(); i++) {
                groups.add(m.group(i));
            }
            return groups;
        }

        /** 경로의 {@code {n}}을 n번째 와일드카드에 잡힌 조각으로 바꾼다. */
        static String substitute(String path, List<String> groups) {
            String result = path;
            for (int i = 0; i < groups.size(); i++) {
                result = result.replace("{" + (i + 1) + "}", groups.get(i));
            }
            return result;
        }
    }

    /** definition 이름의 {@code *}는 {@code /}를 넘지 않는 조각, {@code **}는 {@code /}를 포함한 0개 이상의 글자다. */
    private static Pattern tilesPattern(String name) {
        if (name.indexOf('*') < 0) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < name.length()) {
            if (name.startsWith("**", i)) {
                regex.append("(.*)");
                i += 2;
            } else if (name.charAt(i) == '*') {
                regex.append("([^/]*)");
                i++;
            } else {
                int next = name.indexOf('*', i);
                next = next < 0 ? name.length() : next;
                regex.append(Pattern.quote(name.substring(i, next)));
                i = next;
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static List<TilesDefinition> collectTiles(GraphModel graph) {
        List<TilesDefinition> definitions = new ArrayList<>();
        for (Node file : graph.nodes(NodeKind.FILE)) {
            List<String> entries = file.attrList(TILES_DEFINITIONS);
            if (entries.isEmpty()) {
                continue;
            }
            Map<String, List<String>> attributes = new HashMap<>();
            for (String entry : file.attrList(TILES_ATTRIBUTES)) {
                int eq = entry == null ? -1 : entry.indexOf('=');
                if (eq > 0 && !entry.substring(eq + 1).isBlank()) {
                    attributes.computeIfAbsent(entry.substring(0, eq).trim(), k -> new ArrayList<>()).add(entry.substring(eq + 1).trim());
                }
            }
            String path = Ids.remainder(file.id());
            for (String entry : entries) {
                int eq = entry == null ? -1 : entry.indexOf('=');
                // template이 없는 정의(값이 빈 문자열)는 건너뛴다
                if (eq <= 0 || entry.substring(eq + 1).isBlank()) {
                    continue;
                }
                String name = entry.substring(0, eq).trim();
                definitions.add(new TilesDefinition(name, entry.substring(eq + 1).trim(), path, tilesPattern(name),
                        attributes.getOrDefault(name, List.of())));
            }
        }
        return definitions;
    }

    // ---- 뷰 리졸버 수집 ----

    /**
     * 뷰 리졸버의 prefix·suffix. 빈의 값이 프로퍼티 자리표시자({@code ${web.view.prefix}})면 프로젝트 properties 값으로 치환하고,
     * 값을 찾지 못한 리졸버는 쓰지 않는다(F-012-R22).
     */
    private static Set<Resolver> collectResolvers(AnalysisContext ctx) {
        ProjectProperties properties = ProjectProperties.of(ctx);
        Set<Resolver> resolvers = new LinkedHashSet<>();
        for (Node bean : ctx.graph().nodes(NodeKind.SPRING_BEAN)) {
            String className = bean.attrString("class");
            if (className == null || !className.endsWith("ViewResolver")) {
                continue;
            }
            String file = bean.location() == null ? null : bean.location().file();
            ProjectProperties.Resolution prefix = properties.resolve(bean.attrString("property.prefix"), file);
            ProjectProperties.Resolution suffix = properties.resolve(bean.attrString("property.suffix"), file);
            if (prefix.complete() && suffix.complete()) {
                addResolver(resolvers, prefix.text(), suffix.text());
            }
        }
        for (ProjectProperties.FileValues file : properties.files()) {
            addResolver(resolvers, file.last(PREFIX_KEY), file.last(SUFFIX_KEY));
        }
        return resolvers;
    }

    private static void addResolver(Set<Resolver> resolvers, String prefix, String suffix) {
        if (prefix != null || suffix != null) {
            resolvers.add(new Resolver(prefix == null ? "" : prefix.trim(), suffix == null ? "" : suffix.trim()));
        }
    }

    // ---- 연결 ----

    private static final class Run {
        private final GraphModel graph;
        private final Set<Resolver> resolvers;
        private final EndpointMatcher matcher;
        private final List<TilesDefinition> tiles;
        private final ProjectProperties properties;
        private final Map<String, List<Page>> pagesByFileName = new HashMap<>();

        Run(GraphModel graph, Set<Resolver> resolvers, EndpointMatcher matcher, List<TilesDefinition> tiles, ProjectProperties properties) {
            this.graph = graph;
            this.resolvers = resolvers;
            this.matcher = matcher;
            this.tiles = tiles;
            this.properties = properties;
            for (Node jsp : graph.nodes(NodeKind.JSP_PAGE)) {
                Page page = new Page(jsp, "/" + Ids.remainder(jsp.id()));
                pagesByFileName.computeIfAbsent(fileName(page.path()), k -> new ArrayList<>()).add(page);
            }
        }

        /**
         * @param at 엣지에 쓸 위치(호출 지점, 모르면 메서드의 위치)
         * @param candidate 여러 후보 가운데 하나인 뷰 이름이다: RENDERS를 INFERRED + {@code candidate=true}로 만든다
         */
        void link(Node handler, String viewName, SourceLocation at, boolean candidate) {
            this.candidate = candidate;
            try {
                link(handler, viewName, at);
            } finally {
                this.candidate = false;
            }
        }

        /** 지금 잇는 뷰 이름이 후보 가운데 하나인가({@link #link(Node, String, SourceLocation, boolean)}). */
        private boolean candidate;

        /**
         * 뷰 이름 규칙(Tiles, 리졸버, redirect·forward)으로 잇고, 찾지 못한 이름이 파일 경로 꼴({@code /index.jsp})이면
         * 컨텍스트 루트 기준의 그 파일로 찾는다(F-012-R34).
         */
        void linkOrContextRoot(Node handler, String viewName, SourceLocation at) {
            String path = EndpointMatcher.stripQuery(viewName);
            contextRootFallback = !viewName.contains(":") && (path.endsWith(".jsp") || path.endsWith(".jspf") || path.endsWith(".html"));
            sameModuleOnly = true;
            try {
                link(handler, viewName, at);
            } finally {
                contextRootFallback = false;
                sameModuleOnly = false;
            }
        }

        /**
         * 설정 파일의 선언에서 온 뷰인가: 선언한 파일의 모듈을 알면 그 모듈의 JSP만 후보로 본다. 여러 모듈이 같은 경로를 선언하고
         * 리졸버는 프로젝트 전체의 것을 쓰므로, 그 모듈에 JSP가 없을 때 다른 모듈의 같은 경로 JSP로 잇지 않기 위해서다.
         */
        private boolean sameModuleOnly;

        /** 뷰 이름 규칙으로 찾지 못하면 컨텍스트 루트 기준의 파일로 한 번 더 찾는가. */
        private boolean contextRootFallback;

        /**
         * 컨텍스트 루트 기준의 JSP 경로(F-012-R35): 알려진 웹 루트 바로 아래에 그 경로의 파일이 있으면 RESOLVED,
         * 없고 그 경로로 끝나는 JSP가 하나뿐이면 INFERRED + {@code suffixMatch=true}, 여럿이거나 없으면 뷰 미해결이다.
         * 뷰 리졸버의 prefix·suffix는 적용하지 않는다.
         */
        void contextRootPage(Node handler, String viewName, String path, SourceLocation at) {
            String wanted = normalize(path);
            List<Page> suffix = inDeclaringModule(find(wanted), handler);
            List<Page> exact = suffix.stream().filter(p -> atWebRoot(p, wanted)).toList();
            if (!exact.isEmpty()) {
                render(handler, viewName, exact, Confidence.RESOLVED, Map.of(), at);
            } else if (suffix.size() == 1) {
                render(handler, viewName, suffix, Confidence.INFERRED, Map.of("suffixMatch", true), at);
            } else {
                unresolvedView(handler, viewName, at);
            }
        }

        /** 페이지가 알려진 웹 루트(또는 프로젝트 루트) 바로 아래의 그 경로에 있는가. */
        private static boolean atWebRoot(Page page, String path) {
            String root = page.path().substring(0, page.path().length() - path.length());
            return root.isEmpty() || WEB_ROOTS.stream().anyMatch(root::endsWith);
        }

        private void link(Node handler, String viewName, SourceLocation at) {
            if (viewName.startsWith("redirect:")) {
                linkDispatch(handler, viewName, viewName.substring("redirect:".length()).trim(), "redirect", at);
            } else if (viewName.startsWith("forward:")) {
                forward(handler, viewName, viewName.substring("forward:".length()).trim(), at);
            } else if (viewName.contains(DYNAMIC)) {
                // 실행 시점에 정해지는 뷰 이름: 비슷한 JSP가 있어도 추측으로 잇지 않는다.
                // 다만 동적 조각이 디렉터리 하나뿐이면 그 자리에 들어갈 수 있는 실제 디렉터리의 JSP를 후보로 잇는다(F-012-R44)
                if (!dynamicDirectory(handler, viewName, viewName, null, at)) {
                    unresolvedView(handler, viewName, at);
                }
            } else if (placeholderDirectory(handler, viewName, at)) {
                // 디렉터리 자리가 프로퍼티 자리표시자 하나인 이름(/${app.name}/login.html)은 동적 디렉터리 뷰로 다뤘다(F-012-R47)
                return;
            } else if (tiles(handler, viewName, at)) {
                // Tiles 정의에 있는 뷰 이름은 Tiles 뷰 리졸버가 먼저 푼다(F-012-R30)
                return;
            } else if (resolvers.isEmpty()) {
                // 리졸버 정보가 없다: /<이름>.jsp로 끝나는 JSP가 하나뿐일 때만 추론한다
                List<Page> found = find(normalize("/" + viewName + ".jsp"));
                if (found.size() == 1) {
                    render(handler, viewName, found, Confidence.INFERRED, Map.of(), at);
                } else {
                    unresolvedView(handler, viewName, at);
                }
            } else {
                for (Resolver resolver : resolvers) {
                    List<Page> found = inDeclaringModule(find(normalize("/" + resolver.prefix() + viewName + resolver.suffix())), handler);
                    if (!found.isEmpty()) {
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        attrs.put("resolverPrefix", resolver.prefix());
                        attrs.put("resolverSuffix", resolver.suffix());
                        render(handler, viewName, found, Confidence.RESOLVED, attrs, at);
                        return;
                    }
                }
                unresolvedView(handler, viewName, at);
            }
        }

        /**
         * 디렉터리 자리 전체가 프로퍼티 자리표시자 하나인 뷰 이름({@code /${app.name}/login.html}, F-004-R23·R26)을 동적 디렉터리 뷰로 다룬다:
         * 그 자리를 {@code {*}}로 읽고, 컨트롤러 모듈의 프로퍼티 값({@link ProjectProperties})이 하나로 모이면 그 디렉터리의 후보만 남긴다(F-012-R47).
         *
         * @return 그런 꼴의 이름이라 여기서 다뤘으면(이었거나 뷰 미해결로 남겼으면) true. false면 다른 규칙으로 넘어간다
         */
        private boolean placeholderDirectory(Node handler, String viewName, SourceLocation at) {
            Matcher placeholder = PLACEHOLDER.matcher(viewName);
            if (!placeholder.find()) {
                return false;
            }
            String pattern = viewName.substring(0, placeholder.start()) + DYNAMIC + viewName.substring(placeholder.end());
            if (pattern.contains("${") || !directoryShape(pattern)) {
                return false;
            }
            String sourceFile = handler.location() == null ? null : handler.location().file();
            ProjectProperties.Resolution value = properties.resolve(placeholder.group(), sourceFile);
            if (!dynamicDirectory(handler, viewName, pattern, value.complete() ? value.text().trim() : null, at)) {
                unresolvedView(handler, viewName, at);
            }
            return true;
        }

        /** 동적 조각({@code {*}})이 하나이고 디렉터리 조각 전체인가(마지막 조각이나 조각의 일부가 아니다). */
        private static boolean directoryShape(String pattern) {
            int dynamic = pattern.indexOf(DYNAMIC);
            if (dynamic < 0 || dynamic != pattern.lastIndexOf(DYNAMIC)) {
                return false;
            }
            String before = pattern.substring(0, dynamic);
            String after = pattern.substring(dynamic + DYNAMIC.length());
            return (before.isEmpty() || before.endsWith("/")) && after.startsWith("/") && after.length() >= 2 && !after.endsWith("/");
        }

        /**
         * 동적 조각이 디렉터리 하나인 뷰 이름({@code themes/{*}/frontIndex}): 리졸버로 푼 경로에서 그 자리에 디렉터리 이름 하나를 넣어
         * 실제로 있는 JSP를 후보로 삼고, 근거가 있으면 좁힌다(F-012-R47).
         * <ol>
         * <li>그 자리가 프로퍼티에서 왔고 값을 알면({@code knownDirectory}) 그 디렉터리의 후보만 남긴다({@code narrowedBy=property}).
         * 그 디렉터리에 뷰가 없으면 다른 디렉터리의 것으로 잇지 않는다.</li>
         * <li>값을 모르면, 디렉터리 이름이 핸들러가 속한 모듈의 디렉터리 이름(모듈 루트의 마지막 조각)과 같은 후보가 있을 때 그것만 남긴다
         * ({@code narrowedBy=moduleName}).</li>
         * <li>어느 근거도 없으면 후보 전부다.</li>
         * </ol>
         * 남은 후보가 1~{@value #MAX_DIRECTORY_CANDIDATES}개면 각각 RENDERS(INFERRED, {@code dynamicSegment=true})를 만든다(F-012-R44).
         * 둘 이상이면 어느 것이 그려질지는 실행해야 알므로 {@code candidate=true}다. 리졸버로 찾지 못한 파일 경로 꼴의 이름
         * ({@code /{*}/qa/index.html})은 경로 접미사로 찾는다({@code suffixMatch=true}).
         * 동적 조각이 둘 이상이거나, 조각의 일부이거나, 마지막 조각이면 다루지 않는다.
         *
         * @param viewName 엣지의 {@code viewName}에 남길 원문
         * @param pattern 동적 자리를 {@code {*}}로 적은 이름(원문에 자리표시자가 없으면 원문과 같다)
         * @param knownDirectory 프로퍼티 값으로 안 동적 자리의 디렉터리 이름. 모르면 null
         * @return 이었으면 true. false면 전처럼 뷰 미해결이다
         */
        private boolean dynamicDirectory(Node handler, String viewName, String pattern, String knownDirectory, SourceLocation at) {
            if (!directoryShape(pattern)) {
                return false;
            }
            List<DirectoryCandidate> found = List.of();
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (resolvers.isEmpty()) {
                found = candidatesInDeclaringModule(findWithDirectory(normalize("/" + pattern + ".jsp")), handler);
            }
            for (Resolver resolver : resolvers) {
                found = candidatesInDeclaringModule(findWithDirectory(normalize("/" + resolver.prefix() + pattern + resolver.suffix())), handler);
                if (!found.isEmpty()) {
                    attrs.put("resolverPrefix", resolver.prefix());
                    attrs.put("resolverSuffix", resolver.suffix());
                    break;
                }
            }
            String path = EndpointMatcher.stripQuery(pattern);
            if (found.isEmpty() && (path.endsWith(".jsp") || path.endsWith(".jspf") || path.endsWith(".html"))) {
                found = candidatesInDeclaringModule(findWithDirectory(normalize("/" + path)), handler);
                attrs.put("suffixMatch", true);
            }
            String narrowedBy = null;
            if (knownDirectory != null) {
                found = found.stream().filter(c -> c.directory().equals(knownDirectory)).toList();
                narrowedBy = "property";
            } else if (!found.isEmpty()) {
                String moduleName = moduleDirectoryName(handler);
                List<DirectoryCandidate> ofModule = found.stream().filter(c -> c.directory().equals(moduleName)).toList();
                if (!ofModule.isEmpty()) {
                    found = ofModule;
                    narrowedBy = "moduleName";
                }
            }
            if (found.isEmpty() || found.size() > MAX_DIRECTORY_CANDIDATES) {
                return false;
            }
            attrs.put("dynamicSegment", true);
            if (narrowedBy != null) {
                attrs.put("narrowedBy", narrowedBy);
            }
            if (found.size() > 1 || candidate) {
                attrs.put("candidate", true);
            }
            for (DirectoryCandidate candidate : found) {
                graph.addEdge(Edge.builder(handler.id(), candidate.page().node().id(), EdgeKind.RENDERS).inferred().location(at)
                        .attr("viewName", viewName).attrs(attrs).build());
            }
            return true;
        }

        /** 핸들러(선언) 파일이 속한 모듈의 디렉터리 이름(모듈 루트의 마지막 경로 조각). 모듈을 모르거나 프로젝트 루트면 null. */
        private String moduleDirectoryName(Node handler) {
            String root = matcher.modules().rootOf(handler.location() == null ? null : handler.location().file());
            if (root == null || root.isEmpty()) {
                return null;
            }
            return root.substring(root.lastIndexOf('/') + 1);
        }

        /** 경로 패턴의 {@code {*}} 자리에 디렉터리 이름 하나를 넣은 경로로 끝나는 JSP들과 그 자리에 들어간 디렉터리 이름. */
        private List<DirectoryCandidate> findWithDirectory(String pattern) {
            int dynamic = pattern.indexOf(DYNAMIC);
            Pattern regex = Pattern.compile(".*" + Pattern.quote(pattern.substring(0, dynamic)) + "([^/]+)"
                    + Pattern.quote(pattern.substring(dynamic + DYNAMIC.length())));
            List<DirectoryCandidate> candidates = new ArrayList<>();
            for (Page page : pagesByFileName.getOrDefault(fileName(pattern), List.of())) {
                Matcher m = regex.matcher(page.path());
                if (m.matches()) {
                    candidates.add(new DirectoryCandidate(page, m.group(1)));
                }
            }
            return candidates;
        }

        /** {@link #inDeclaringModule(List, Node)}의 후보 판. */
        private List<DirectoryCandidate> candidatesInDeclaringModule(List<DirectoryCandidate> found, Node handler) {
            List<Page> kept = inDeclaringModule(found.stream().map(DirectoryCandidate::page).toList(), handler);
            return kept.size() == found.size() ? found : found.stream().filter(c -> kept.contains(c.page())).toList();
        }

        /**
         * 뷰 이름과 맞는 Tiles definition이 있으면 그 template JSP로 RENDERS를 만든다(없는 JSP면 뷰 미해결).
         * definition의 put-attribute JSP(본문·조각)로도 잇되, 없는 JSP는 미해결 없이 건너뛴다.
         *
         * @return 맞는 definition이 있었으면 true(리졸버 규칙으로 넘어가지 않는다)
         */
        private boolean tiles(Node handler, String viewName, SourceLocation at) {
            if (tiles.isEmpty()) {
                return false;
            }
            // 핸들러와 같은 모듈의 설정 파일에 정의가 있으면 그것만 본다
            String sourceFile = handler.location() == null ? null : handler.location().file();
            List<TilesDefinition> candidates = tiles.stream().filter(d -> d.match(viewName) != null).toList();
            List<TilesDefinition> sameModule = candidates.stream().filter(d -> matcher.modules().sameModule(sourceFile, d.file())).toList();
            if (!sameModule.isEmpty()) {
                candidates = sameModule;
            }
            // 정확한 이름이 와일드카드보다 먼저고, 그다음은 정의된 순서다
            TilesDefinition chosen = candidates.stream().filter(d -> d.pattern() == null).findFirst()
                    .orElse(candidates.isEmpty() ? null : candidates.get(0));
            if (chosen == null) {
                return false;
            }
            List<String> groups = chosen.match(viewName);
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("via", "tiles");
            attrs.put("definition", chosen.name());
            render(handler, viewName, find(normalize(TilesDefinition.substitute(chosen.template(), groups))), Confidence.RESOLVED, attrs, at);
            attrs.put("tilesAttribute", true);
            for (String attribute : chosen.attributes()) {
                List<Page> found = find(normalize(TilesDefinition.substitute(attribute, groups)));
                if (!found.isEmpty()) {
                    render(handler, viewName, found, Confidence.RESOLVED, attrs, at);
                }
            }
            return true;
        }

        /**
         * forward 대상 하나. JSP 경로면 그 JSP로 RENDERS, 아니면 경로가 맞는 엔드포인트의 핸들러로 CALLS다.
         *
         * @param viewName RENDERS의 {@code viewName}과 미해결 원문에 쓸 이름({@code forward:} 뷰 이름 전체, attrs에서 왔으면 경로)
         */
        void forward(Node handler, String viewName, String target, SourceLocation at) {
            String path = EndpointMatcher.stripQuery(target);
            if (path.endsWith(".jsp") || path.endsWith(".jspf")) {
                render(handler, viewName, find(normalize(path)), Confidence.RESOLVED, Map.of(), at);
            } else {
                linkDispatch(handler, viewName.equals(target) ? null : viewName, target, "forward", at);
            }
        }

        private void render(Node handler, String viewName, List<Page> found, Confidence confidence, Map<String, Object> attrs,
                            SourceLocation at) {
            List<Page> chosen = narrow(inDeclaringModule(found, handler), handler);
            if (chosen.isEmpty()) {
                unresolvedView(handler, viewName, at);
                return;
            }
            for (Page page : chosen) {
                Edge.Builder edge = Edge.builder(handler.id(), page.node().id(), EdgeKind.RENDERS)
                        .location(at).attr("viewName", viewName).attrs(attrs);
                if (chosen.size() > 1) {
                    edge.inferred().attr("ambiguous", true);
                } else {
                    edge.confidence(confidence);
                }
                if (candidate) {
                    edge.inferred().attr("candidate", true);
                }
                graph.addEdge(edge.build());
            }
        }

        private void unresolvedView(Node handler, String viewName, SourceLocation at) {
            if (contextRootFallback) {
                contextRootFallback = false;
                contextRootPage(handler, viewName, EndpointMatcher.stripQuery(viewName), at);
                return;
            }
            graph.addUnresolvedEdge(handler.id(), EdgeKind.RENDERS, CATEGORY, viewName, at);
        }

        /**
         * @param viewName 뷰 이름에서 온 것이면 그 이름, 메서드 attrs(redirects·forwards)에서 온 것이면 null
         * @param at 엣지에 쓸 위치(호출 지점, 모르면 메서드의 위치)
         */
        void linkDispatch(Node handler, String viewName, String target, String callType, SourceLocation at) {
            String method = callType.equals("redirect") ? "GET" : Ids.ANY_METHOD;
            String sourceFile = handler.location() == null ? null : handler.location().file();
            // 대상의 프로퍼티 자리표시자(redirect:${adminPath}/login)는 프로젝트 properties 값으로 푼다(F-012-R33).
            // 값을 찾지 못한 자리표시자는 알 수 없는 조각({*})이다: 맨 앞이면 알 수 없는 접두로 맞춘다
            if (target.contains("${")) {
                target = properties.resolve(target, sourceFile).text().replaceAll("\\$\\{[^}]*}", "{*}");
            }
            // 상대 대상(redirect:list)의 기준은 이 핸들러로 들어오는 엔드포인트 경로의 디렉터리다
            EndpointMatcher.Result result = matcher.match(new EndpointMatcher.Request(method, target, false, sourceFile,
                    matcher.baseDirectoriesOfHandler(handler.id())));
            if (result.skipped()) {
                return;
            }
            boolean added = false;
            // 대상 핸들러 → 그 엣지의 근거. 동률인 엔드포인트들이 같은 핸들러로 갈 수 있으므로 먼저 모은다
            Map<String, Map<String, Object>> targets = new LinkedHashMap<>();
            for (EndpointMatcher.Hit hit : result.hits()) {
                List<String> routedHandlers = routedHandlers(hit.endpoint(), sourceFile);
                // 넘기는 쪽 모듈이 그 경로를 view-controller로만 선언했다: 화면으로 가는 것이지 다른 모듈의 핸들러를 부르는 것이 아니다(F-012-R37)
                added |= routedHandlers.isEmpty() && declaresViewOnly(hit.endpoint(), sourceFile);
                for (String routed : routedHandlers) {
                    targets.putIfAbsent(routed, hit.attrs());
                }
            }
            for (Map.Entry<String, Map<String, Object>> entry : targets.entrySet()) {
                Edge.Builder edge = Edge.builder(handler.id(), entry.getKey(), EdgeKind.CALLS).inferred().location(at)
                        .attr("callType", callType).attr("path", result.path());
                if (viewName != null) {
                    edge.attr("viewName", viewName);
                }
                Map<String, Object> attrs = new LinkedHashMap<>(entry.getValue());
                // 후보가 하나뿐인 엣지에는 ambiguous를 붙이지 않는다(F-012-R45)
                attrs.remove("ambiguous");
                if (targets.size() > 1) {
                    attrs.put("ambiguous", true);
                }
                graph.addEdge(edge.attrs(attrs).build());
                added = true;
            }
            boolean external = target.contains("://") || target.startsWith("//");
            if (!added && !external) {
                graph.addUnresolvedEdge(handler.id(), EdgeKind.CALLS, HttpMatchLinker.CATEGORY, method + " " + result.path(), at);
            }
        }

        /**
         * 엔드포인트의 핸들러 메서드들. 엔드포인트 노드는 메서드와 경로가 같으면 하나라 여러 웹 모듈의 핸들러가 모여 있을 수 있다:
         * 넘기는 쪽과 같은 모듈의 핸들러가 있으면 그것만 고른다.
         */
        private List<String> routedHandlers(EndpointMatcher.Endpoint endpoint, String sourceFile) {
            List<Edge> routes = graph.outgoing(endpoint.node().id(), EdgeKind.ROUTES_TO);
            List<String> same = routes.stream().map(Edge::to)
                    .filter(to -> graph.node(to).map(Node::location).filter(l -> matcher.modules().sameModule(sourceFile, l.file())).isPresent())
                    .toList();
            if (!same.isEmpty()) {
                return same;
            }
            return declaresViewOnly(endpoint, sourceFile) ? List.of() : routes.stream().map(Edge::to).toList();
        }

        /** 넘기는 쪽 모듈에 그 엔드포인트의 핸들러는 없고 view-controller 선언만 있는가. */
        private boolean declaresViewOnly(EndpointMatcher.Endpoint endpoint, String sourceFile) {
            String module = matcher.modules().rootOf(sourceFile);
            return module != null && endpoint.viewModules().contains(module) && !endpoint.modules().contains(module);
        }

        /** 경로가 path로 끝나는 JSP. */
        private List<Page> find(String path) {
            return pagesByFileName.getOrDefault(fileName(path), List.of()).stream().filter(p -> p.path().endsWith(path)).toList();
        }

        /** {@link #sameModuleOnly}이고 핸들러(선언) 파일의 모듈을 알면 그 모듈의 페이지만 남긴다. */
        private List<Page> inDeclaringModule(List<Page> found, Node handler) {
            String sourceFile = handler.location() == null ? null : handler.location().file();
            if (!sameModuleOnly || matcher.modules().rootOf(sourceFile) == null) {
                return found;
            }
            return found.stream().filter(p -> matcher.modules().sameModule(sourceFile, p.path().substring(1))).toList();
        }

        /** 같은 경로의 JSP가 여럿이면 알려진 웹 루트 바로 아래의 것, 그다음 핸들러 소스와 경로가 가장 가까운 것으로 좁힌다. */
        private List<Page> narrow(List<Page> found, Node handler) {
            if (found.size() <= 1) {
                return found;
            }
            List<Page> candidates = found;
            List<Page> underWebRoot = found.stream().filter(Run::underWebRoot).toList();
            if (!underWebRoot.isEmpty()) {
                candidates = underWebRoot;
            }
            if (candidates.size() > 1 && handler.location() != null && handler.location().file() != null) {
                String source = "/" + handler.location().file();
                int best = candidates.stream().mapToInt(p -> commonDirectories(source, p.path())).max().orElse(0);
                List<Page> nearest = candidates.stream().filter(p -> commonDirectories(source, p.path()) == best).toList();
                if (best > 0) {
                    candidates = nearest;
                }
            }
            return candidates;
        }

        private static boolean underWebRoot(Page page) {
            int webInf = page.path().indexOf("/WEB-INF/");
            String root = webInf >= 0 ? page.path().substring(0, webInf) : page.path();
            return WEB_ROOTS.stream().anyMatch(known -> webInf >= 0 ? root.endsWith(known) || root.isEmpty() : root.contains(known + "/"));
        }

        private static int commonDirectories(String a, String b) {
            String[] x = a.split("/");
            String[] y = b.split("/");
            int n = 0;
            while (n < x.length - 1 && n < y.length - 1 && x[n].equals(y[n])) {
                n++;
            }
            return n - 1;   // 맨 앞의 빈 조각은 세지 않는다
        }

        private static String normalize(String path) {
            return ("/" + path).replaceAll("/{2,}", "/");
        }

        private static String fileName(String path) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }
}
