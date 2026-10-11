package io.codeatlas.jsp;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.scan.FileClassifier;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import io.codeatlas.jsp.JspToken.Type;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 레이아웃 프레임워크가 붙이는 페이지 관계(F-011-R21, R22). JSP 본문에는 include가 없지만 실행 때 한 화면으로 합쳐지는 것들이다.
 *
 * <ul>
 *   <li>SiteMesh: 내용 페이지 → 데코레이터 JSP. 페이지의 {@code <meta name="decorator" content="이름">}과 {@code decorators.xml}
 *       (SiteMesh 3은 {@code sitemesh3.xml})을 맞춘다. {@code INCLUDES}(INFERRED, {@code via=sitemesh}).</li>
 *   <li>Tiles: 정의의 template JSP → 그 정의가 끼우는 조각 JSP. {@code INCLUDES}(INFERRED, {@code via=tiles}). 뷰 이름이 JSP 경로가
 *       아니라 정의 이름이므로, 정의 이름 → template·조각 경로를 설정 파일의 FILE 노드 attrs({@value #TILES_DEFINITIONS_ATTR},
 *       {@value #TILES_ATTRIBUTES_ATTR})로 남긴다. 링커가 뷰 이름을 JSP로 잇는 데, web이 전역 스크립트의 범위를 정하는 데 쓴다.</li>
 * </ul>
 *
 * 설정 파일은 XML이지만 태그와 속성만 읽으면 되므로 JSP 스캐너로 읽는다(DTD를 받으러 나가지 않고 위치도 얻는다).
 * 페이지를 다 분석한 뒤 {@link #finish}가 엣지를 만든다.
 */
final class Layouts {

    /** Tiles 설정 파일의 FILE 노드 attrs 키: {@code <정의 이름>=<template JSP 경로>} 목록(extends를 푼 값, 없으면 빈 값). */
    static final String TILES_DEFINITIONS_ATTR = "tilesDefinitions";
    /** Tiles 설정 파일의 FILE 노드 attrs 키: {@code <정의 이름>=<조각 JSP 경로>} 목록(물려받은 것 포함). */
    static final String TILES_ATTRIBUTES_ATTR = "tilesAttributes";

    private static final Set<String> CATCH_ALL = Set.of("/*", "*", "/**", "/");
    private static final Pattern VIEW_FILE = Pattern.compile("(?i).*\\.(?:jsp|jspx|html)");
    private static final int MAX_DYNAMIC_CANDIDATES = 8;
    /** 이름에 EL이 섞인 {@code <meta name="decorator">}에서 온 {@code via=sitemesh} 엣지의 attrs 키(boolean, F-011-R27). */
    static final String DYNAMIC_DECORATOR_ATTR = "dynamicDecorator";

    /** 페이지가 고른 데코레이터({@code <meta name="decorator">}). */
    private record DecoratorRef(String page, String name, SourceLocation location) {
    }

    /**
     * 데코레이터 하나.
     *
     * @param name 이름. SiteMesh 3의 mapping이면 null
     * @param page 데코레이터 JSP의 프로젝트 기준 경로. 프로젝트에서 찾지 못했으면 null
     * @param patterns 이 데코레이터를 적용할 URL 패턴
     */
    private record Decorator(String name, String page, List<String> patterns, SourceLocation location) {
    }

    private record TilesAttribute(String name, String value, SourceLocation location) {
    }

    private static final class TilesDefinition {
        final String name;
        final String config;
        final String template;
        final String parent;
        final SourceLocation location;
        final List<TilesAttribute> attributes = new ArrayList<>();

        TilesDefinition(String name, String config, String template, String parent, SourceLocation location) {
            this.name = name;
            this.config = config;
            this.template = template;
            this.parent = parent;
            this.location = location;
        }

        boolean wildcard() {
            return name.indexOf('*') >= 0 || name.startsWith("REGEXP:");
        }
    }

    private final List<DecoratorRef> refs = new ArrayList<>();

    void decoratorMeta(String page, String name, SourceLocation location) {
        refs.add(new DecoratorRef(page, name, location));
    }

    /**
     * @param pages 내용을 분석한 페이지의 경로(벤더로 건너뛴 것 제외)
     */
    void finish(AnalysisContext ctx, ProjectIndex index, List<String> pages) {
        GraphModel graph = ctx.graph();
        // 웹 루트 → 그 웹 애플리케이션의 데코레이터들
        Map<String, List<Decorator>> decorators = new LinkedHashMap<>();
        List<TilesDefinition> definitions = new ArrayList<>();
        Map<String, SourceFile> tilesFiles = new LinkedHashMap<>();
        for (SourceFile file : ctx.files().ofType(FileType.XML)) {
            try {
                String root = FileClassifier.sniffXmlRoot(file.absolutePath());
                if (root == null) {
                    continue;
                }
                switch (root) {
                    case "decorators" -> decorators.computeIfAbsent(WebPaths.webRootOf(file.relativePath()), k -> new ArrayList<>())
                            .addAll(sitemesh2(file, index));
                    case "sitemesh" -> decorators.computeIfAbsent(WebPaths.webRootOf(file.relativePath()), k -> new ArrayList<>())
                            .addAll(sitemesh3(file, index));
                    case "tiles-definitions" -> {
                        tilesFiles.put(file.relativePath(), file);
                        definitions.addAll(tiles(file));
                    }
                    default -> {
                    }
                }
            } catch (IOException | RuntimeException e) {
                // 읽지 못한 설정 파일은 없는 것으로 본다. 레이아웃 관계만 빠진다
            }
        }
        sitemeshEdges(graph, index, decorators, pages);
        if (!definitions.isEmpty()) {
            tilesEdges(graph, index, definitions, tilesFiles);
        }
    }

    // ------------------------------------------------------------------ SiteMesh

    private static SourceLocation location(SourceFile file, LineMap lines, JspToken token) {
        return file.location(lines.line(token.start()), lines.column(token.start()), lines.line(token.end()),
                lines.column(token.end()));
    }

    /** {@code decorators.xml}: {@code <decorators defaultdir><decorator name page><pattern>…}. */
    private static List<Decorator> sitemesh2(SourceFile file, ProjectIndex index) {
        String text = LineMap.loneCrToLf(file.readText());
        LineMap lines = new LineMap(text);
        String webRoot = WebPaths.webRootOf(file.relativePath());
        List<Decorator> out = new ArrayList<>();
        String defaultDir = "";
        List<String> patterns = null;
        List<JspToken> tokens = JspScanner.scan(text, JspScanner.Mode.HTML);
        for (int i = 0; i < tokens.size(); i++) {
            JspToken token = tokens.get(i);
            if (token.type() == Type.CLOSE_TAG && token.name().equals("decorator")) {
                patterns = null;
            }
            if (token.type() != Type.OPEN_TAG) {
                continue;
            }
            switch (token.name()) {
                case "decorators" -> defaultDir = token.hasAttr("defaultdir") ? token.attr("defaultdir").trim() : "";
                case "decorator" -> {
                    String name = token.attr("name");
                    String page = token.attr("page");
                    if (name == null || name.isBlank() || page == null || page.isBlank()) {
                        patterns = null;
                        continue;
                    }
                    List<String> own = new ArrayList<>();
                    out.add(new Decorator(name.trim(), decoratorPage(index, webRoot, defaultDir, page.trim()), own,
                            location(file, lines, token)));
                    patterns = token.selfClosing() ? null : own;
                }
                case "pattern" -> {
                    // <excludes> 아래의 pattern은 데코레이터의 것이 아니다
                    if (patterns != null && i + 1 < tokens.size() && tokens.get(i + 1).type() == Type.CLOSE_TAG) {
                        String pattern = text.substring(token.end(), tokens.get(i + 1).start()).trim();
                        if (!pattern.isEmpty()) {
                            patterns.add(pattern);
                        }
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    /**
     * 데코레이터의 page 속성을 JSP 파일로. {@code /}로 시작하면 웹 루트 기준이고 아니면 defaultdir 아래다. 그렇게 찾지 못하면
     * 반대로도 찾아 본다(defaultdir을 붙인 절대 경로를 쓰는 설정이 있다).
     */
    private static String decoratorPage(ProjectIndex index, String webRoot, String defaultDir, String page) {
        String underDefault = defaultDir + (page.startsWith("/") ? "" : "/") + page;
        String first = page.startsWith("/") ? page : underDefault;
        String second = page.startsWith("/") ? underDefault : "/" + page;
        for (String candidate : List.of(first, second)) {
            String path = WebPaths.resolve(webRoot, "", candidate.startsWith("/") ? candidate : "/" + candidate);
            if (index.page(path).isPresent()) {
                return path;
            }
        }
        return null;
    }

    /** {@code sitemesh3.xml}: {@code <sitemesh><mapping path="/*" decorator="/x.jsp"/>}. path가 없으면 모든 경로다. */
    private static List<Decorator> sitemesh3(SourceFile file, ProjectIndex index) {
        String text = LineMap.loneCrToLf(file.readText());
        LineMap lines = new LineMap(text);
        String webRoot = WebPaths.webRootOf(file.relativePath());
        List<Decorator> out = new ArrayList<>();
        for (JspToken token : JspScanner.scan(text, JspScanner.Mode.HTML)) {
            if (token.type() != Type.OPEN_TAG || !token.name().equals("mapping") || !token.hasAttr("decorator")) {
                continue;
            }
            String pattern = token.hasAttr("path") ? token.attr("path").trim() : "/*";
            for (String decorator : token.attr("decorator").split(",")) {
                String path = decorator.trim();
                if (path.isEmpty()) {
                    continue;
                }
                String resolved = WebPaths.resolve(webRoot, "", path.startsWith("/") ? path : "/" + path);
                out.add(new Decorator(null, index.page(resolved).isPresent() ? resolved : null, List.of(pattern),
                        location(file, lines, token)));
            }
        }
        return out;
    }

    private void sitemeshEdges(GraphModel graph, ProjectIndex index, Map<String, List<Decorator>> decorators, List<String> pages) {
        Set<String> decoratorPages = new HashSet<>();
        decorators.values().forEach(list -> list.forEach(d -> {
            if (d.page() != null) {
                decoratorPages.add(d.page());
            }
        }));
        Set<String> chosen = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (DecoratorRef ref : refs) {
            chosen.add(ref.page());
            List<Decorator> known = decoratorsFor(decorators, ref.page());
            List<String> targets = new ArrayList<>();
            boolean dynamic = UrlNormalizer.hasDynamicPart(ref.name());
            boolean ambiguous = false;
            if (dynamic) {
                // cms_default_${site.theme}: 정적인 부분이 맞는 데코레이터가 후보다
                Pattern pattern = dynamicName(ref.name());
                if (pattern != null) {
                    for (Decorator d : known) {
                        if (d.name() != null && d.page() != null && pattern.matcher(d.name()).matches() && !targets.contains(d.page())) {
                            targets.add(d.page());
                        }
                    }
                }
                if (targets.size() > MAX_DYNAMIC_CANDIDATES) {
                    targets.clear();
                }
                if (targets.size() > 1) {
                    // 후보가 여럿이면 페이지와 같은 폴더(themes/<x>/) 아래의 데코레이터를 고른다. 가리지 못하면 후보 전부가 모호한 추론이다
                    targets = closestByDirectory(ref.page(), targets);
                    ambiguous = targets.size() > 1;
                }
            } else {
                for (Decorator d : known) {
                    if (ref.name().equals(d.name()) && d.page() != null) {
                        targets.add(d.page());
                        break;
                    }
                }
                if (targets.isEmpty() && (ref.name().startsWith("/") || WebPaths.hasPageExtension(ref.name()))) {
                    // SiteMesh 3: content가 데코레이터 파일의 경로다
                    String path = WebPaths.resolve(WebPaths.webRootOf(ref.page()), WebPaths.directoryOf(ref.page()), ref.name());
                    if (index.page(path).isPresent()) {
                        targets.add(path);
                    }
                }
            }
            if (targets.isEmpty()) {
                graph.addUnresolvedEdge(Ids.jspPage(ref.page()), EdgeKind.INCLUDES, PageAnalysis.CATEGORY_INCLUDE,
                        "decorator:" + ref.name(), ref.location());
                continue;
            }
            for (String target : targets) {
                decoratorPages.add(target);
                if (!target.equals(ref.page()) && seen.add(ref.page() + "\n" + target)) {
                    Edge.Builder edge = Edge.builder(Ids.jspPage(ref.page()), Ids.jspPage(target), EdgeKind.INCLUDES)
                            .location(ref.location()).attr("via", "sitemesh").attr("path", ref.name());
                    if (dynamic) {
                        edge.attr(DYNAMIC_DECORATOR_ATTR, true);
                    }
                    if (ambiguous) {
                        edge.attr("ambiguous", true);
                    }
                    graph.addEdge(edge.inferred().build());
                }
            }
        }
        // meta가 없는 페이지: 패턴으로 적용하는 데코레이터가 하나뿐이고 그 패턴이 모든 경로일 때만 안다(URL과 JSP의 대응을 모른다)
        for (Map.Entry<String, List<Decorator>> e : decorators.entrySet()) {
            Decorator only = null;
            int patterned = 0;
            for (Decorator d : e.getValue()) {
                if (!d.patterns().isEmpty()) {
                    patterned++;
                    only = d;
                }
            }
            if (patterned != 1 || only.page() == null || only.patterns().size() != 1 || !CATCH_ALL.contains(only.patterns().get(0))) {
                continue;
            }
            String webRoot = e.getKey();
            for (String page : pages) {
                String lower = page.toLowerCase(Locale.ROOT);
                if (!WebPaths.webRootOf(page).equals(webRoot) || chosen.contains(page) || decoratorPages.contains(page)
                        || lower.endsWith(".jspf") || lower.endsWith(".tag") || included(graph, page)) {
                    continue;
                }
                graph.addEdge(Edge.builder(Ids.jspPage(page), Ids.jspPage(only.page()), EdgeKind.INCLUDES)
                        .location(only.location()).attr("via", "sitemesh").attr("path", only.patterns().get(0)).inferred().build());
            }
        }
    }

    /**
     * 동적 이름의 후보 가운데 페이지와 공통 디렉터리가 가장 긴 것들(F-011-R27). 테마마다 폴더를 나눈 배치
     * ({@code themes/basic/…}의 페이지 ↔ {@code themes/basic/layouts/default.jsp})에서 다른 테마의 레이아웃을 걸러 낸다.
     * 가장 긴 것이 여럿이면 가리지 못한 것이므로 그것들을 모두 돌려준다.
     */
    private static List<String> closestByDirectory(String page, List<String> candidates) {
        String[] own = WebPaths.directoryOf(page).split("/");
        int best = -1;
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            String[] other = WebPaths.directoryOf(candidate).split("/");
            int common = 0;
            while (common < own.length && common < other.length && own[common].equals(other[common])) {
                common++;
            }
            if (common > best) {
                best = common;
                out.clear();
            }
            if (common == best) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** 다른 페이지가 포함하는 조각인가(조각은 스스로 응답이 되지 않으므로 데코레이터가 붙지 않는다). */
    private static boolean included(GraphModel graph, String page) {
        for (Edge e : graph.incoming(Ids.jspPage(page), EdgeKind.INCLUDES)) {
            if (e.from().startsWith("jsp:page:")) {
                return true;
            }
        }
        return false;
    }

    /** 그 페이지의 웹 애플리케이션에 선언된 데코레이터들. 그 웹 루트에 설정이 없고 프로젝트에 설정이 하나뿐이면 그것. */
    private static List<Decorator> decoratorsFor(Map<String, List<Decorator>> decorators, String page) {
        List<Decorator> own = decorators.get(WebPaths.webRootOf(page));
        if (own != null) {
            return own;
        }
        return decorators.size() == 1 ? decorators.values().iterator().next() : List.of();
    }

    /** EL이 섞인 이름을 정규식으로: EL 자리는 임의의 글자. 정적인 글자가 하나도 없으면 null. */
    private static Pattern dynamicName(String name) {
        StringBuilder sb = new StringBuilder();
        boolean anyStatic = false;
        int position = 0;
        for (JspToken token : JspScanner.scan(name, JspScanner.Mode.FRAGMENT)) {
            if (token.start() > position) {
                sb.append(Pattern.quote(name.substring(position, token.start())));
                anyStatic = true;
            }
            sb.append(".*");
            position = token.end();
        }
        if (position < name.length()) {
            sb.append(Pattern.quote(name.substring(position)));
            anyStatic = true;
        }
        return anyStatic ? Pattern.compile(sb.toString()) : null;
    }

    // ------------------------------------------------------------------ Tiles

    /**
     * Tiles 설정 파일의 정의들. Tiles 2·3의 {@code <definition name template extends>}와 {@code <put-attribute name value>},
     * Tiles 1(Struts)의 {@code <definition name path|page extends>}와 {@code <put name value>}를 읽는다.
     */
    private static List<TilesDefinition> tiles(SourceFile file) {
        String text = LineMap.loneCrToLf(file.readText());
        LineMap lines = new LineMap(text);
        List<TilesDefinition> out = new ArrayList<>();
        TilesDefinition current = null;
        for (JspToken token : JspScanner.scan(text, JspScanner.Mode.HTML)) {
            if (token.type() == Type.CLOSE_TAG && token.name().equals("definition")) {
                current = null;
            }
            if (token.type() != Type.OPEN_TAG) {
                continue;
            }
            if (token.name().equals("definition")) {
                String name = token.attr("name");
                if (name == null || name.isBlank()) {
                    current = null;
                    continue;
                }
                String template = firstAttr(token, "template", "path", "page");
                TilesDefinition definition = new TilesDefinition(name.trim(), file.relativePath(), template,
                        firstAttr(token, "extends"), location(file, lines, token));
                out.add(definition);
                current = token.selfClosing() ? null : definition;
            } else if ((token.name().equals("put-attribute") || token.name().equals("put")) && current != null) {
                String name = token.attr("name");
                String value = token.attr("value");
                if (name != null && !name.isBlank() && value != null) {
                    current.attributes.add(new TilesAttribute(name.trim(), value.trim(), location(file, lines, token)));
                }
            }
        }
        return out;
    }

    private static String firstAttr(JspToken token, String... names) {
        for (String name : names) {
            String value = token.attr(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void tilesEdges(GraphModel graph, ProjectIndex index, List<TilesDefinition> definitions,
            Map<String, SourceFile> files) {
        Map<String, List<TilesDefinition>> byName = new HashMap<>();
        for (TilesDefinition d : definitions) {
            byName.computeIfAbsent(d.name, k -> new ArrayList<>()).add(d);
        }
        Map<String, List<String>> definitionAttrs = new LinkedHashMap<>();
        Map<String, List<String>> attributeAttrs = new LinkedHashMap<>();
        files.keySet().forEach(path -> {
            definitionAttrs.put(path, new ArrayList<>());
            attributeAttrs.put(path, new ArrayList<>());
        });
        Set<String> seen = new HashSet<>();
        for (TilesDefinition d : definitions) {
            String template = template(d, byName, new HashSet<>());
            definitionAttrs.get(d.config).add(d.name + "=" + (template == null ? "" : template));
            String webRoot = WebPaths.webRootOf(d.config);
            String templatePage = template == null || template.indexOf('{') >= 0 ? null : page(index, webRoot, template);
            for (TilesAttribute attribute : attributes(d, byName, new HashSet<>()).values()) {
                String value = attribute.value();
                if (!VIEW_FILE.matcher(value).matches()) {
                    // 값이 다른 정의의 이름이면 그 정의의 template이 조각이다. 그 밖의 문자열 값(제목 등)은 조각이 아니다
                    TilesDefinition nested = pick(byName.get(value), d);
                    value = nested == null ? null : template(nested, byName, new HashSet<>());
                    if (value == null) {
                        continue;
                    }
                }
                attributeAttrs.get(d.config).add(d.name + "=" + value);
                if (d.wildcard() || templatePage == null || value.indexOf('{') >= 0) {
                    continue; // 와일드카드 정의의 본문은 뷰 이름을 알아야 정해진다(링커의 몫)
                }
                String target = page(index, webRoot, value);
                String resolved = WebPaths.resolve(webRoot, "", value.startsWith("/") ? value : "/" + value);
                String key = templatePage + "\n" + (target != null ? target : resolved);
                if ((target != null && target.equals(templatePage)) || !seen.add(key)) {
                    continue;
                }
                if (target == null) {
                    graph.addUnresolvedEdge(Ids.jspPage(templatePage), EdgeKind.INCLUDES, PageAnalysis.CATEGORY_INCLUDE,
                            resolved == null ? value : resolved, attribute.location());
                } else {
                    graph.addEdge(Edge.builder(Ids.jspPage(templatePage), Ids.jspPage(target), EdgeKind.INCLUDES)
                            .location(attribute.location()).attr("via", "tiles").attr("path", attribute.value()).inferred().build());
                }
            }
        }
        files.forEach((path, file) -> {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put(TILES_DEFINITIONS_ATTR, definitionAttrs.get(path));
            attrs.put(TILES_ATTRIBUTES_ATTR, attributeAttrs.get(path));
            graph.putAttrs(file.nodeId(), attrs);
        });
    }

    /** 같은 이름의 정의가 여럿이면 같은 설정 파일의 것, 없으면 같은 웹 루트의 것, 그것도 없으면 첫 번째. */
    private static TilesDefinition pick(List<TilesDefinition> candidates, TilesDefinition from) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (TilesDefinition c : candidates) {
            if (c.config.equals(from.config) && c != from) {
                return c;
            }
        }
        String webRoot = WebPaths.webRootOf(from.config);
        for (TilesDefinition c : candidates) {
            if (c != from && WebPaths.webRootOf(c.config).equals(webRoot)) {
                return c;
            }
        }
        return candidates.get(0) == from ? (candidates.size() > 1 ? candidates.get(1) : null) : candidates.get(0);
    }

    /** extends를 따라가 찾은 template 경로(설정에 적힌 그대로). 끝내 없거나 extends가 돌면 null. */
    private static String template(TilesDefinition d, Map<String, List<TilesDefinition>> byName, Set<TilesDefinition> visiting) {
        if (d.template != null) {
            return d.template;
        }
        if (d.parent == null || !visiting.add(d)) {
            return null;
        }
        TilesDefinition parent = pick(byName.get(d.parent), d);
        return parent == null ? null : template(parent, byName, visiting);
    }

    /** 물려받은 것을 포함한 attribute들(이름 → 값). 상위 정의의 순서를 따르고 하위 정의가 같은 이름을 덮는다. */
    private static Map<String, TilesAttribute> attributes(TilesDefinition d, Map<String, List<TilesDefinition>> byName,
            Set<TilesDefinition> visiting) {
        Map<String, TilesAttribute> out = new LinkedHashMap<>();
        if (!visiting.add(d)) {
            return out;
        }
        if (d.parent != null) {
            TilesDefinition parent = pick(byName.get(d.parent), d);
            if (parent != null) {
                out.putAll(attributes(parent, byName, visiting));
            }
        }
        for (TilesAttribute attribute : d.attributes) {
            out.put(attribute.name(), attribute);
        }
        return out;
    }

    /** 설정에 적힌 경로(웹 루트 기준)의 JSP. 웹 루트에서 못 찾으면 경로 끝이 일치하는 페이지가 하나뿐일 때 그것. 없으면 null. */
    private static String page(ProjectIndex index, String webRoot, String path) {
        String resolved = WebPaths.resolve(webRoot, "", path.startsWith("/") ? path : "/" + path);
        if (index.page(resolved).isPresent()) {
            return resolved;
        }
        List<String> candidates = index.pagesEndingWith(path.startsWith("/") ? path.substring(1) : path);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
}
