package io.codeatlas.web.analysis;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 어느 페이지가 어느 스크립트를 싣는가(F-010-R18). 전역 이름을 다른 스크립트에서 찾을 때의 근거다.
 *
 * <p>페이지(JSP·HTML)가 싣는 스크립트는 그 페이지의 인라인 스크립트, {@code INCLUDES}로 가리킨 JS 모듈({@code <script src>}),
 * 그리고 그 페이지가 include하는 JSP가 (다시 include하는 것까지) 싣는 것이다. jsp 분석기(F-011)가 만든 {@code INCLUDES} 엣지를 읽는다.
 *
 * <p>레이아웃 프레임워크가 붙이는 관계는 따로 다룬다(F-010-R33).
 * <ul>
 *   <li>SiteMesh({@code via=sitemesh}, 페이지 → 데코레이터): 데코레이터가 싣는 스크립트는 그 페이지가 "직접 싣는" 것이 아니라
 *       "함께 실리는" 것이다. {@link #included}에는 넣지 않고 {@link #coLoaded}에만 넣는다(그래서 추론으로 기록된다).</li>
 *   <li>Tiles({@code via=tiles}, template → 조각): template 하나를 여러 정의가 쓰므로 엣지를 그대로 따라가면 서로 다른 화면의
 *       본문끼리 함께 실리는 것으로 보인다. 엣지는 따라가지 않고, 설정 파일 노드의 {@code tilesDefinitions}·{@code tilesAttributes}로
 *       정의마다 "template + 그 정의의 조각"을 한 묶음으로 만든다. 근거 페이지는 template이다.</li>
 * </ul>
 */
final class PageScripts {

    /** jsp가 Tiles 설정 파일의 FILE 노드에 싣는 attrs: {@code 정의 이름=template 경로} 목록. */
    static final String TILES_DEFINITIONS_ATTR = "tilesDefinitions";
    /** jsp가 Tiles 설정 파일의 FILE 노드에 싣는 attrs: {@code 정의 이름=조각 JSP 경로} 목록. */
    static final String TILES_ATTRIBUTES_ATTR = "tilesAttributes";
    private static final Pattern MIN_NAME = Pattern.compile("(?i)[.-]min(\\.(?:js|mjs|cjs))$");
    private static final Pattern TILES_PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");
    private static final Pattern TILES_WILDCARD = Pattern.compile("\\*\\*|\\*");

    /**
     * 함께 실리는 스크립트 묶음 하나.
     *
     * @param page 근거 페이지의 노드 ID(페이지 자신, Tiles 정의면 그 template)
     */
    private record Group(String page, List<ModuleInfo> modules) {
    }

    /** 페이지 ID → 그 페이지가 직접(레이아웃 관계를 거치지 않고) 싣는 모듈. */
    private final Map<String, List<ModuleInfo>> own = new HashMap<>();
    /** 페이지 ID 순의 페이지 묶음, 그 뒤에 Tiles 정의 묶음. */
    private final List<Group> groups = new ArrayList<>();
    private final Map<ModuleInfo, List<Group>> groupsOf = new HashMap<>();
    /** 모듈 → 그것을 싣는 페이지 ID들(오름차순). */
    private final Map<ModuleInfo, List<String>> pagesOf = new HashMap<>();
    private final Map<ModuleInfo, Map<ModuleInfo, String>> coLoaded = new HashMap<>();
    private final Map<String, List<String>> includedPages = new HashMap<>();
    private final GraphModel graph;
    private final Map<String, ModuleInfo> byId;

    private PageScripts(GraphModel graph, Map<String, ModuleInfo> byId) {
        this.graph = graph;
        this.byId = byId;
    }

    static PageScripts build(GraphModel graph, Map<String, ModuleInfo> byId) {
        PageScripts out = new PageScripts(graph, byId);
        Map<String, List<ModuleInfo>> full = new TreeMap<>();
        for (Node page : graph.nodes(NodeKind.JSP_PAGE)) {
            out.addMinifiedSiblings(page.id());
        }
        for (Node page : graph.nodes(NodeKind.JSP_PAGE)) {
            List<ModuleInfo> direct = out.closure(page.id(), false);
            if (!direct.isEmpty()) {
                out.own.put(page.id(), direct);
            }
            List<ModuleInfo> all = out.closure(page.id(), true);
            if (!all.isEmpty()) {
                full.put(page.id(), all);
            }
        }
        full.forEach((page, modules) -> {
            out.groups.add(new Group(page, modules));
            for (ModuleInfo m : modules) {
                out.pagesOf.computeIfAbsent(m, k -> new ArrayList<>()).add(page);
            }
        });
        try {
            out.addTilesGroups();
        } catch (RuntimeException e) {
            // Tiles 묶음만 빠진다.
        }
        for (Group group : out.groups) {
            for (ModuleInfo m : group.modules()) {
                out.groupsOf.computeIfAbsent(m, k -> new ArrayList<>()).add(group);
            }
        }
        return out;
    }

    /**
     * 페이지가 압축본({@code x.min.js})을 싣는데 그 내용은 분석하지 않았고 같은 폴더에 분석한 {@code x.js}가 있으면, 그 페이지가
     * {@code x.js}를 싣는 것으로 본다(F-010-R35). 근거를 그래프에 남긴다: {@code INCLUDES}(INFERRED, {@code minOf}=압축본 경로).
     */
    private void addMinifiedSiblings(String page) {
        List<Edge> edges = graph.outgoing(page, EdgeKind.INCLUDES);
        for (Edge e : edges) {
            ModuleInfo min = e.to().startsWith("js:module:") ? byId.get(e.to()) : null;
            if (min == null || !min.skipped || min.embedded) {
                continue;
            }
            Matcher name = MIN_NAME.matcher(min.path);
            if (!name.find()) {
                continue;
            }
            ModuleInfo sibling = byId.get(Ids.jsModule(min.path.substring(0, name.start()) + name.group(1)));
            if (sibling == null || sibling.skipped || sibling.vendor || !graph.edges(page, EdgeKind.INCLUDES, sibling.id).isEmpty()) {
                continue;
            }
            Edge.Builder edge = Edge.builder(page, sibling.id, EdgeKind.INCLUDES).attr("via", "script").attr("minOf", min.path);
            if (e.location() != null) {
                edge.location(e.location());
            }
            graph.addEdge(edge.inferred().build());
        }
    }

    /**
     * 페이지에서 INCLUDES를 따라가며 닿는 모듈들.
     *
     * @param layout SiteMesh 데코레이터로 가는 엣지도 따라갈 것인가. Tiles 엣지는 언제나 따라가지 않는다
     */
    private List<ModuleInfo> closure(String page, boolean layout) {
        Set<ModuleInfo> found = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(page);
        seen.add(page);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            ModuleInfo inline = byId.get(Ids.jsModule(Ids.remainder(current)));
            if (inline != null && inline.embedded) {
                found.add(inline);
            }
            for (Edge e : graph.outgoing(current, EdgeKind.INCLUDES)) {
                String to = e.to();
                if (to.startsWith("js:module:")) {
                    ModuleInfo target = byId.get(to);
                    if (target != null && seen.add(to)) {
                        found.add(target);
                    }
                } else if (to.startsWith("jsp:page:")) {
                    String via = e.attrString("via");
                    if ("tiles".equals(via) || ("sitemesh".equals(via) && !layout)) {
                        continue;
                    }
                    if (seen.add(to)) {
                        queue.add(to);
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    // ------------------------------------------------------------------ Tiles

    /** 설정 파일마다 정의별 묶음(template이 싣는 것 + 그 정의의 조각이 싣는 것)을 만든다. */
    private void addTilesGroups() {
        List<String> pagePaths = null;
        Set<List<Object>> seen = new HashSet<>();
        for (Node file : graph.nodes(NodeKind.FILE)) {
            if (!(file.attrs().get(TILES_DEFINITIONS_ATTR) instanceof List<?> definitions)) {
                continue;
            }
            if (pagePaths == null) {
                pagePaths = new ArrayList<>();
                for (Node page : graph.nodes(NodeKind.JSP_PAGE)) {
                    pagePaths.add(Ids.remainder(page.id()));
                }
                pagePaths.sort(null);
            }
            Map<String, List<String>> attributes = new LinkedHashMap<>();
            if (file.attrs().get(TILES_ATTRIBUTES_ATTR) instanceof List<?> entries) {
                for (Object entry : entries) {
                    String text = String.valueOf(entry);
                    int eq = text.indexOf('=');
                    if (eq > 0 && eq < text.length() - 1) {
                        attributes.computeIfAbsent(text.substring(0, eq), k -> new ArrayList<>()).add(text.substring(eq + 1));
                    }
                }
            }
            String config = Ids.remainder(file.id());
            for (Object entry : definitions) {
                String text = String.valueOf(entry);
                int eq = text.indexOf('=');
                if (eq <= 0 || eq == text.length() - 1) {
                    continue; // template을 모르는 정의
                }
                tilesDefinition(config, text.substring(0, eq), text.substring(eq + 1),
                        attributes.getOrDefault(text.substring(0, eq), List.of()), pagePaths, seen);
            }
        }
    }

    private void tilesDefinition(String config, String name, String template, List<String> attributes, List<String> pagePaths,
            Set<List<Object>> seen) {
        if (template.indexOf('{') >= 0) {
            return;
        }
        String templatePath = resolveTilesPath(config, template, pagePaths);
        if (templatePath == null) {
            return;
        }
        String normalized = template.startsWith("/") ? template.substring(1) : template;
        String webRoot = templatePath.substring(0, templatePath.length() - normalized.length());
        String templateId = Ids.jspPage(templatePath);
        Set<ModuleInfo> base = new LinkedHashSet<>(closure(templateId, true));
        List<String> bodies = new ArrayList<>();
        boolean wildcard = false;
        for (String attribute : attributes) {
            String path = attribute.startsWith("/") ? attribute.substring(1) : attribute;
            if (path.indexOf('{') < 0) {
                if (has(pagePaths, webRoot + path)) {
                    base.addAll(closure(Ids.jspPage(webRoot + path), true));
                }
                continue;
            }
            // 와일드카드 정의의 조각: 정의 이름의 * 자리에 무엇이 오든 그 경로 패턴에 맞는 JSP가 본문일 수 있다
            wildcard = true;
            Pattern pattern = tilesPattern(webRoot, path, name);
            for (String page : pagePaths) {
                if (!page.equals(templatePath) && pattern.matcher(page).matches()) {
                    bodies.add(page);
                }
            }
        }
        if (!wildcard || bodies.isEmpty()) {
            addGroup(templateId, new ArrayList<>(base), seen);
            return;
        }
        for (String body : bodies) {
            Set<ModuleInfo> modules = new LinkedHashSet<>(base);
            modules.addAll(closure(Ids.jspPage(body), true));
            addGroup(templateId, new ArrayList<>(modules), seen);
        }
    }

    /** 정렬된 경로 목록에 그 경로가 있는가. */
    private static boolean has(List<String> sortedPaths, String path) {
        return java.util.Collections.binarySearch(sortedPaths, path) >= 0;
    }

    private void addGroup(String page, List<ModuleInfo> modules, Set<List<Object>> seen) {
        if (modules.size() < 2) {
            return;
        }
        List<Object> key = new ArrayList<>(modules.size() + 1);
        key.add(page);
        key.addAll(modules);
        if (seen.add(key)) {
            groups.add(new Group(page, modules));
        }
    }

    /**
     * Tiles 설정에 적힌 경로(웹 루트 기준)를 프로젝트의 JSP 경로로. 설정 파일이 {@code WEB-INF} 아래면 그 부모가 웹 루트다.
     * 거기 없으면 경로 끝이 일치하는 JSP가 하나뿐일 때 그것으로 본다.
     */
    private static String resolveTilesPath(String config, String path, List<String> pagePaths) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        int webInf = config.indexOf("WEB-INF/");
        if (webInf == 0 || (webInf > 0 && config.charAt(webInf - 1) == '/')) {
            String candidate = config.substring(0, webInf) + relative;
            if (has(pagePaths, candidate)) {
                return candidate;
            }
        }
        String found = null;
        for (String page : pagePaths) {
            if (page.equals(relative) || page.endsWith("/" + relative)) {
                if (found != null) {
                    return null;
                }
                found = page;
            }
        }
        return found;
    }

    /**
     * 와일드카드 정의의 조각 경로({@code WEB-INF/views/{1}/{2}.jsp})를 JSP 경로에 맞춰 볼 정규식으로.
     * {@code {n}}은 정의 이름의 n번째 와일드카드다: {@code *}는 {@code /}를 뺀 글자들, {@code **}와 그 밖({@code {0}}, 정규식 정의)은 임의의 글자들.
     */
    private static Pattern tilesPattern(String webRoot, String path, String definitionName) {
        List<String> wildcards = new ArrayList<>();
        Matcher w = TILES_WILDCARD.matcher(definitionName);
        while (w.find()) {
            wildcards.add(w.group());
        }
        StringBuilder sb = new StringBuilder(Pattern.quote(webRoot));
        Matcher m = TILES_PLACEHOLDER.matcher(path);
        int position = 0;
        while (m.find()) {
            sb.append(Pattern.quote(path.substring(position, m.start())));
            int index = Integer.parseInt(m.group(1));
            boolean segment = index >= 1 && index <= wildcards.size() && wildcards.get(index - 1).equals("*");
            sb.append(segment ? "[^/]+" : ".+");
            position = m.end();
        }
        sb.append(Pattern.quote(path.substring(position)));
        return Pattern.compile(sb.toString());
    }

    // ------------------------------------------------------------------ 조회

    /** 페이지 자신과 그 페이지가 (다시 include하는 것까지) include하는 페이지들의 ID. */
    List<String> includedPages(String page) {
        return includedPages.computeIfAbsent(page, p -> {
            List<String> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(p);
            seen.add(p);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                out.add(current);
                for (Edge e : graph.outgoing(current, EdgeKind.INCLUDES)) {
                    if (e.to().startsWith("jsp:page:") && seen.add(e.to())) {
                        queue.add(e.to());
                    }
                }
            }
            return out;
        });
    }

    /**
     * 인라인 모듈의 주인 페이지가 (include한 JSP를 거쳐서라도) 직접 싣는 다른 모듈들. 파일 모듈이면 빈 목록.
     * 레이아웃(SiteMesh 데코레이터, Tiles template)이 싣는 것은 들어 있지 않다.
     */
    List<ModuleInfo> included(ModuleInfo module) {
        if (!module.embedded) {
            return List.of();
        }
        List<ModuleInfo> direct = own.get(Ids.jspPage(module.path));
        return direct == null ? List.of() : direct;
    }

    /** 이 모듈을 싣는 페이지들(ID 오름차순). 인라인 모듈이면 주인 페이지와 그 페이지를 include하는 페이지들이다. */
    List<String> pagesOf(ModuleInfo module) {
        return pagesOf.getOrDefault(module, List.of());
    }

    /**
     * 이 모듈과 한 페이지(또는 한 Tiles 정의)에 함께 실리는 다른 모듈 → 그렇게 싣는 페이지(여럿이면 ID가 가장 앞선 페이지,
     * 페이지가 없으면 Tiles template). 함께 싣는 페이지를 모르는 모듈은 들어 있지 않다.
     */
    Map<ModuleInfo, String> coLoaded(ModuleInfo module) {
        return coLoaded.computeIfAbsent(module, m -> {
            Map<ModuleInfo, String> out = new LinkedHashMap<>();
            for (Group group : groupsOf.getOrDefault(m, List.of())) {
                for (ModuleInfo other : group.modules()) {
                    if (other != m) {
                        out.putIfAbsent(other, group.page());
                    }
                }
            }
            return out;
        });
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "PageScripts(%d groups)", groups.size());
    }
}
