package io.codeatlas.web.analysis;

import io.codeatlas.web.syntax.JsDialect;
import io.codeatlas.web.syntax.JsParser;
import io.codeatlas.web.syntax.SyntaxNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * import 지정자를 프로젝트의 모듈로 푼다: 상대 경로(확장자 생략, 디렉터리 index), tsconfig.json·jsconfig.json의 baseUrl·paths 별칭.
 */
final class ModuleResolver {

    enum Kind {
        /** 프로젝트의 모듈. */
        MODULE,
        /** 패키지(node_modules). 엣지를 만들지 않는다. */
        PACKAGE,
        /** 스크립트가 아닌 자원(css, 이미지, json 등). 엣지를 만들지 않는다. */
        ASSET,
        /** 프로젝트 경로인데 파일을 찾지 못했다. */
        UNRESOLVED
    }

    /** text는 UNRESOLVED일 때 미해결 원문(프로젝트 기준 경로). */
    record Resolution(Kind kind, ModuleInfo module, String text) {
    }

    private static final List<String> EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs");
    private static final Set<String> ASSET_EXTENSIONS = Set.of("css", "scss", "sass", "less", "styl", "json", "png", "jpg",
            "jpeg", "gif", "svg", "webp", "ico", "bmp", "woff", "woff2", "ttf", "eot", "otf", "mp3", "mp4", "webm", "wav",
            "txt", "md", "html", "htm", "xml", "yaml", "yml", "graphql", "gql", "vue", "svelte", "wasm", "pdf", "csv");

    /** paths 항목 하나. pattern과 target에는 {@code *}가 최대 한 번 나온다. */
    private record Mapping(String pattern, List<String> targets) {
    }

    /** dir은 설정 파일의 폴더, baseDir은 baseUrl을 반영한 기준 폴더(프로젝트 기준 경로, 루트는 ""). */
    private record Config(String dir, String baseDir, boolean hasBaseUrl, List<Mapping> paths) {
    }

    private final Path root;
    private final Map<String, ModuleInfo> byPath;
    private final JsParser parser;
    private final Map<String, Optional<Config>> configByDir = new HashMap<>();
    private final Map<String, Resolution> cache = new HashMap<>();

    ModuleResolver(Path root, Map<String, ModuleInfo> byPath, JsParser parser) {
        this.root = root;
        this.byPath = byPath;
        this.parser = parser;
    }

    /** 프로젝트의 모듈로 풀리면 그 모듈, 아니면 null. */
    ModuleInfo module(ModuleInfo from, String spec) {
        Resolution r = resolve(from, spec);
        return r.kind() == Kind.MODULE ? r.module() : null;
    }

    Resolution resolve(ModuleInfo from, String spec) {
        return cache.computeIfAbsent(from.path + "\n" + spec, k -> doResolve(from, spec));
    }

    private Resolution doResolve(ModuleInfo from, String rawSpec) {
        String spec = rawSpec.trim();
        int q = spec.indexOf('?');
        if (q > 0) {
            // 번들러 쿼리(?raw, ?url)는 뗀다.
            spec = spec.substring(0, q);
        }
        if (spec.isEmpty()) {
            return new Resolution(Kind.PACKAGE, null, null);
        }
        String dir = parent(from.path);
        if (spec.equals(".") || spec.equals("..") || spec.startsWith("./") || spec.startsWith("../")) {
            String base = normalize(dir.isEmpty() ? spec : dir + "/" + spec);
            if (base == null) {
                return new Resolution(Kind.UNRESOLVED, null, rawSpec);
            }
            return file(base);
        }
        if (spec.startsWith("/")) {
            Resolution r = file(spec.substring(1));
            return r.kind() == Kind.MODULE ? r : new Resolution(Kind.PACKAGE, null, null);
        }
        Config config = config(dir);
        if (config == null) {
            return new Resolution(Kind.PACKAGE, null, null);
        }
        // paths: 접두사가 긴 패턴부터
        Mapping best = null;
        String bestStar = null;
        for (Mapping m : config.paths()) {
            String star = match(m.pattern(), spec);
            if (star != null && (best == null || prefixLength(m.pattern()) > prefixLength(best.pattern()))) {
                best = m;
                bestStar = star;
            }
        }
        if (best != null) {
            String firstCandidate = null;
            for (String target : best.targets()) {
                String candidate = normalize(join(config.baseDir(), target.replace("*", bestStar)));
                if (candidate == null) {
                    continue;
                }
                if (firstCandidate == null) {
                    firstCandidate = candidate;
                }
                Resolution r = file(candidate);
                if (r.kind() == Kind.MODULE || r.kind() == Kind.ASSET) {
                    return r;
                }
            }
            return new Resolution(Kind.UNRESOLVED, null, firstCandidate == null ? rawSpec : firstCandidate);
        }
        if (config.hasBaseUrl()) {
            String candidate = normalize(join(config.baseDir(), spec));
            if (candidate != null) {
                Resolution r = file(candidate);
                if (r.kind() == Kind.MODULE) {
                    return r;
                }
            }
        }
        // 관례: @/x, ~/x → <설정 파일 폴더>/src/x
        if (spec.startsWith("@/") || spec.startsWith("~/")) {
            String candidate = normalize(join(config.dir(), "src/" + spec.substring(2)));
            if (candidate != null) {
                Resolution r = file(candidate);
                return r.kind() == Kind.MODULE || r.kind() == Kind.ASSET ? r : new Resolution(Kind.UNRESOLVED, null, candidate);
            }
        }
        return new Resolution(Kind.PACKAGE, null, null);
    }

    /** 확장자와 index를 붙여 가며 찾는다. base는 프로젝트 기준 경로. */
    private Resolution file(String base) {
        ModuleInfo m = byPath.get(base);
        if (m != null) {
            return new Resolution(Kind.MODULE, m, null);
        }
        for (String ext : EXTENSIONS) {
            m = byPath.get(base + ext);
            if (m != null) {
                return new Resolution(Kind.MODULE, m, null);
            }
        }
        for (String ext : EXTENSIONS) {
            m = byPath.get(base.isEmpty() ? "index" + ext : base + "/index" + ext);
            if (m != null) {
                return new Resolution(Kind.MODULE, m, null);
            }
        }
        // TypeScript ESM 관례: './a.js'라고 쓰고 실제 파일은 a.ts
        String lower = base.toLowerCase(Locale.ROOT);
        for (String[] pair : new String[][] {{".js", ".ts"}, {".js", ".tsx"}, {".jsx", ".tsx"}, {".mjs", ".mts"}, {".cjs", ".cts"}}) {
            if (lower.endsWith(pair[0])) {
                m = byPath.get(base.substring(0, base.length() - pair[0].length()) + pair[1]);
                if (m != null) {
                    return new Resolution(Kind.MODULE, m, null);
                }
            }
        }
        String name = base.substring(base.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!EXTENSIONS.contains("." + ext) && (ASSET_EXTENSIONS.contains(ext) || exists(base))) {
                return new Resolution(Kind.ASSET, null, null);
            }
        }
        return new Resolution(Kind.UNRESOLVED, null, base);
    }

    private boolean exists(String relative) {
        try {
            return Files.isRegularFile(root.resolve(relative));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ tsconfig / jsconfig

    /** dir에서 프로젝트 루트까지 올라가며 가장 가까운 tsconfig.json 또는 jsconfig.json을 찾는다. 없으면 null. */
    private Config config(String dir) {
        String d = dir;
        while (true) {
            Optional<Config> c = configByDir.computeIfAbsent(d, this::loadConfig);
            if (c.isPresent()) {
                return c.get();
            }
            if (d.isEmpty()) {
                return null;
            }
            d = parent(d);
        }
    }

    private Optional<Config> loadConfig(String dir) {
        for (String name : List.of("tsconfig.json", "jsconfig.json")) {
            Path file = root.resolve(dir.isEmpty() ? name : dir + "/" + name);
            try {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                return Optional.of(parseConfig(dir, Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                // 읽지 못한 설정 파일은 없는 것으로 친다. 별칭 없이 진행한다.
                return Optional.of(new Config(dir, dir, false, List.of()));
            }
        }
        return Optional.empty();
    }

    /** 주석과 끝 쉼표가 있는 JSON이므로 JS 객체 리터럴로 파싱한다. */
    private Config parseConfig(String dir, String text) {
        SyntaxNode object = firstObject(parser.parse("(" + text + ")", JsDialect.JAVASCRIPT).root());
        SyntaxNode options = object == null ? null : property(object, "compilerOptions");
        String baseDir = dir;
        boolean hasBaseUrl = false;
        List<Mapping> paths = new ArrayList<>();
        if (options != null && options.is("object")) {
            SyntaxNode baseUrl = property(options, "baseUrl");
            if (baseUrl != null && baseUrl.is("string")) {
                String normalized = normalize(join(dir, Syntax.stringContent(baseUrl)));
                if (normalized != null) {
                    baseDir = normalized;
                    hasBaseUrl = true;
                }
            }
            SyntaxNode pathsNode = property(options, "paths");
            if (pathsNode != null && pathsNode.is("object")) {
                for (SyntaxNode pair : pathsNode.namedChildren()) {
                    if (!pair.is("pair")) {
                        continue;
                    }
                    String pattern = Syntax.propertyName(pair.field("key"));
                    SyntaxNode value = pair.field("value");
                    if (pattern == null || value == null || !value.is("array")) {
                        continue;
                    }
                    List<String> targets = new ArrayList<>();
                    for (SyntaxNode t : value.namedChildren()) {
                        if (t.is("string")) {
                            targets.add(Syntax.stringContent(t));
                        }
                    }
                    paths.add(new Mapping(pattern, targets));
                }
            }
        }
        return new Config(dir, baseDir, hasBaseUrl, paths);
    }

    private static SyntaxNode firstObject(SyntaxNode root) {
        Deque<SyntaxNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            SyntaxNode n = queue.poll();
            if (n.is("object")) {
                return n;
            }
            queue.addAll(n.namedChildren());
        }
        return null;
    }

    private static SyntaxNode property(SyntaxNode object, String key) {
        for (SyntaxNode pair : object.namedChildren()) {
            if (pair.is("pair") && key.equals(Syntax.propertyName(pair.field("key")))) {
                return pair.field("value");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 경로

    /** 패턴이 맞으면 {@code *}에 해당하는 부분(없으면 ""), 안 맞으면 null. */
    private static String match(String pattern, String spec) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return pattern.equals(spec) ? "" : null;
        }
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        if (spec.length() >= prefix.length() + suffix.length() && spec.startsWith(prefix) && spec.endsWith(suffix)) {
            return spec.substring(prefix.length(), spec.length() - suffix.length());
        }
        return null;
    }

    private static int prefixLength(String pattern) {
        int star = pattern.indexOf('*');
        return star < 0 ? pattern.length() + 1 : star;
    }

    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String join(String dir, String rest) {
        if (rest.startsWith("/")) {
            return rest.substring(1);
        }
        return dir.isEmpty() ? rest : dir + "/" + rest;
    }

    /** {@code .}과 {@code ..}를 정리한다. 프로젝트 루트를 벗어나면 null. */
    static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else {
                parts.addLast(p);
            }
        }
        return String.join("/", parts);
    }
}
