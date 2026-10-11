package io.codeatlas.jsp;

import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.ProjectFiles;
import io.codeatlas.core.spi.SourceFile;
import io.codeatlas.jsp.JspToken.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 페이지를 하나씩 분석하는 동안 함께 쓰는 프로젝트 단위 정보: 스크립트 파일 찾기, 정적 include한 조각의 선언. */
final class ProjectIndex {

    /**
     * 정적 include로 끼워 넣으면 따라 들어오는 선언.
     *
     * @param taglibs 접두사 → uri 또는 {@code tagdir:<경로>}
     * @param contextVars 컨텍스트 경로를 담은 변수 이름
     */
    record Declarations(Map<String, String> taglibs, Set<String> contextVars) {

        static final Declarations EMPTY = new Declarations(Map.of(), Set.of());
    }

    private final ProjectFiles files;
    /** 파일 이름 → 그 이름의 JS·TS 파일 경로들. */
    private final Map<String, List<String>> scriptsByName = new HashMap<>();
    /** 파일 이름 → 그 이름의 JSP·HTML 파일 경로들. */
    private final Map<String, List<String>> pagesByName = new HashMap<>();
    private final Map<String, Declarations> declarations = new HashMap<>();

    ProjectIndex(AnalysisContext ctx) {
        this.files = ctx.files();
        for (SourceFile file : files.ofType(FileType.JS, FileType.TS)) {
            scriptsByName.computeIfAbsent(file.fileName(), k -> new ArrayList<>()).add(file.relativePath());
        }
        for (SourceFile file : files.ofType(FileType.JSP, FileType.HTML)) {
            pagesByName.computeIfAbsent(file.fileName(), k -> new ArrayList<>()).add(file.relativePath());
        }
    }

    /** 경로가 {@code suffix}와 같거나 {@code /suffix}로 끝나는 JSP·HTML 파일들. */
    List<String> pagesEndingWith(String suffix) {
        String name = suffix.substring(suffix.lastIndexOf('/') + 1);
        List<String> matches = new ArrayList<>();
        for (String path : pagesByName.getOrDefault(name, List.of())) {
            if (path.equals(suffix) || path.endsWith("/" + suffix)) {
                matches.add(path);
            }
        }
        return matches;
    }

    /** JSP·HTML 파일이면 돌려준다. */
    Optional<SourceFile> page(String relativePath) {
        return relativePath == null ? Optional.empty()
                : files.byRelativePath(relativePath).filter(f -> f.type() == FileType.JSP || f.type() == FileType.HTML);
    }

    /** JS·TS 파일이면 돌려준다. */
    Optional<SourceFile> script(String relativePath) {
        return relativePath == null ? Optional.empty()
                : files.byRelativePath(relativePath).filter(f -> f.type() == FileType.JS || f.type() == FileType.TS);
    }

    /** 경로가 {@code suffix}와 같거나 {@code /suffix}로 끝나는 JS·TS 파일들. */
    List<String> scriptsEndingWith(String suffix) {
        String name = suffix.substring(suffix.lastIndexOf('/') + 1);
        List<String> matches = new ArrayList<>();
        for (String path : scriptsByName.getOrDefault(name, List.of())) {
            if (path.equals(suffix) || path.endsWith("/" + suffix)) {
                matches.add(path);
            }
        }
        return matches;
    }

    /**
     * 런타임 경로와 디스크 경로가 다른 스크립트 찾기(F-011-R23): 파일 이름이 같은 JS·TS 파일 가운데, src의 끝에서부터 경로 조각이
     * 가장 길게 일치하는 것이 하나뿐이면 그 파일. {@code /resources/ui-admin/js/common.js} ↔ {@code ui-admin/src/js/common.js}
     * ({@code js/common.js} 두 조각 일치). 파일 이름 한 조각만 맞는 것은 근거가 약해 보지 않고, 벤더 폴더의 파일은 후보가 아니다.
     *
     * @return 그런 파일이 없거나 가장 길게 맞는 것이 둘 이상이면 null
     */
    String scriptByLongestSuffix(String src) {
        List<String> wanted = segments(src);
        if (wanted.size() < 2) {
            return null;
        }
        String best = null;
        int bestCount = 1;
        boolean tie = false;
        for (String path : scriptsByName.getOrDefault(wanted.get(wanted.size() - 1), List.of())) {
            if (VendorPaths.vendorDirectory(path) != null) {
                continue;
            }
            List<String> have = segments(path);
            int count = 0;
            while (count < wanted.size() && count < have.size()
                    && wanted.get(wanted.size() - 1 - count).equals(have.get(have.size() - 1 - count))) {
                count++;
            }
            if (count > bestCount) {
                best = path;
                bestCount = count;
                tie = false;
            } else if (count == bestCount && count >= 2) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty() && !segment.equals(".") && !segment.equals("..")) {
                out.add(segment);
            }
        }
        return out;
    }

    /** 이 파일을 정적 include했을 때 따라 들어오는 taglib 선언과 컨텍스트 경로 변수(그 파일이 다시 include한 것 포함). */
    Declarations declarationsOf(String relativePath) {
        return declarationsOf(relativePath, new HashSet<>());
    }

    private Declarations declarationsOf(String relativePath, Set<String> visiting) {
        Declarations known = declarations.get(relativePath);
        if (known != null) {
            return known;
        }
        if (!visiting.add(relativePath)) {
            return Declarations.EMPTY; // 순환 include
        }
        Map<String, String> taglibs = new LinkedHashMap<>();
        Set<String> contextVars = new LinkedHashSet<>();
        Optional<SourceFile> file = page(relativePath);
        if (file.isPresent()) {
            try {
                String text = LineMap.loneCrToLf(file.get().readText());
                String webRoot = WebPaths.webRootOf(relativePath);
                String directory = WebPaths.directoryOf(relativePath);
                for (JspToken token : JspScanner.scan(text, JspScanner.Mode.JSP)) {
                    if (token.type() == Type.DIRECTIVE && token.name().equals("taglib")) {
                        taglib(token, taglibs);
                    } else if (token.type() == Type.DIRECTIVE && token.name().equals("include") && token.hasAttr("file")) {
                        String target = WebPaths.resolve(webRoot, directory, token.attr("file").trim());
                        if (target != null) {
                            Declarations nested = declarationsOf(target, visiting);
                            taglibs.putAll(nested.taglibs());
                            contextVars.addAll(nested.contextVars());
                        }
                    } else if (isContextVariable(token)) {
                        contextVars.add(token.attr("var").trim());
                    }
                }
            } catch (RuntimeException e) {
                // 읽지 못한 조각은 그 파일을 분석할 때 진단이 남는다. 여기서는 선언이 없는 것으로 본다
            }
        }
        visiting.remove(relativePath);
        Declarations result = new Declarations(taglibs, contextVars);
        declarations.put(relativePath, result);
        return result;
    }

    /** taglib 지시자를 접두사 → uri 또는 {@code tagdir:<경로>}로 기록한다. */
    static void taglib(JspToken directive, Map<String, String> into) {
        String prefix = directive.attr("prefix");
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        String tagdir = directive.attr("tagdir");
        String uri = directive.attr("uri");
        if (tagdir != null && !tagdir.isBlank()) {
            into.put(prefix.trim(), "tagdir:" + tagdir.trim());
        } else {
            into.put(prefix.trim(), uri == null ? "" : uri.trim());
        }
    }

    /** {@code <c:set var="ctx" value="${pageContext.request.contextPath}"/>} 꼴인가. */
    static boolean isContextVariable(JspToken token) {
        if (token.type() != Type.OPEN_TAG || token.prefix().isEmpty() || !token.localName().equals("set")) {
            return false;
        }
        String var = token.attr("var");
        String value = token.attr("value");
        if (var == null || var.isBlank() || value == null) {
            return false;
        }
        String trimmed = value.trim();
        boolean singleEl = trimmed.startsWith("${") && trimmed.endsWith("}") && trimmed.indexOf("${", 2) < 0;
        boolean singleExpression = trimmed.startsWith("<%=") && trimmed.endsWith("%>");
        return (singleEl && UrlNormalizer.isContextPathExpression(trimmed))
                || (singleExpression && trimmed.contains("getContextPath"));
    }
}
