package io.codeatlas.link;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 프로젝트의 {@code .properties}·{@code .yml} 값으로 프로퍼티 자리표시자({@code ${키}}, {@code ${키:기본값}})를 푸는 공용 헬퍼(F-012-R24).
 *
 * <p>링커가 자리표시자를 만나는 곳(엔드포인트 경로, 뷰 리졸버의 prefix·suffix, 빈 이름)은 모두 여기로 조회한다. 규칙은 하나다.
 * <ul>
 * <li>참조하는 파일이 테스트 소스({@code src/test/})가 아니면 테스트 소스의 프로퍼티 파일은 보지 않는다.</li>
 * <li>참조하는 파일과 같은 모듈 루트({@link ModuleRoots})에 그 키가 있으면 그 모듈의 값만 본다.</li>
 * <li>자기 자신을 가리키는 값({@code 키=${키}}, {@code 키=@키@}: 빌드 도구의 필터링 자리)은 값으로 치지 않는다.</li>
 * <li>남은 값이 하나로 모이면 그 값, 서로 다른 값이 둘 이상이면(파일·프로파일마다 다름) <b>모호</b>라서 풀지 않는다.
 * 어디에도 없으면 {@code ${키:기본값}}의 기본값, 그것도 없으면 풀지 않는다.</li>
 * </ul>
 * 실행 시점의 시스템 프로퍼티·환경 변수·프로파일 선택은 알 수 없으므로, 값이 갈리면 추측하지 않는다.
 */
final class ProjectProperties {

    enum Status { FOUND, MISSING, AMBIGUOUS }

    /** 키 하나의 조회 결과. {@code value}는 FOUND일 때만 있다. */
    record Lookup(Status status, String value) {
    }

    /**
     * 문자열 하나를 푼 결과.
     *
     * @param text 풀 수 있는 자리표시자를 치환한 문자열(못 푼 것은 원문 그대로 남는다)
     * @param unresolved 값을 못 찾았거나 모호해서 남은 자리표시자 원문({@code ${키}})
     */
    record Resolution(String text, boolean changed, List<String> unresolved) {
        /** 자리표시자가 하나도 남지 않았는가. */
        boolean complete() {
            return unresolved.isEmpty();
        }
    }

    /** @param values 그 파일에서 키마다 나온 값(순서대로. YAML 문서가 여럿이면 값도 여럿일 수 있다) */
    record FileValues(String file, Map<String, List<String>> values) {
        /** 그 파일의 마지막 값. 없으면 null. */
        String last(String key) {
            List<String> all = values.get(key);
            return all == null || all.isEmpty() ? null : all.get(all.size() - 1);
        }
    }

    private static final int MAX_DEPTH = 5;
    private static final Map<GraphModel, ProjectProperties> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private final List<FileValues> files;
    private final ModuleRoots modules;

    ProjectProperties(List<FileValues> files, ModuleRoots modules) {
        this.files = List.copyOf(files);
        this.modules = modules;
    }

    /**
     * 컨텍스트의 프로퍼티 파일을 읽는다. 같은 실행(같은 그래프)에서는 한 번만 읽고, 읽지 못한 파일은 WARN 진단 1건을 남기고 건너뛴다.
     */
    static ProjectProperties of(AnalysisContext ctx) {
        return CACHE.computeIfAbsent(ctx.graph(), graph -> read(ctx));
    }

    private static ProjectProperties read(AnalysisContext ctx) {
        List<FileValues> files = new ArrayList<>();
        for (SourceFile file : ctx.files().ofType(FileType.PROPERTIES, FileType.YAML)) {
            try {
                Map<String, List<String>> values = file.type() == FileType.YAML ? flattenYaml(file.readText()) : loadProperties(file.readText());
                files.add(new FileValues(file.relativePath(), values));
            } catch (UncheckedIOException | IOException | IllegalArgumentException e) {
                ctx.graph().report(Diagnostic.warn(DiagnosticCodes.FILE_NOT_READABLE,
                        "프로퍼티 값을 찾으려던 파일을 읽지 못함: " + file.relativePath() + " (" + e.getMessage() + ")", file.location()));
            }
        }
        return new ProjectProperties(files, ModuleRoots.of(ctx.files()));
    }

    /** 읽은 파일들(상대 경로순). */
    List<FileValues> files() {
        return files;
    }

    static boolean hasPlaceholder(String text) {
        return text != null && nextPlaceholder(text, 0) >= 0;
    }

    /**
     * 키 하나를 찾는다.
     *
     * @param contextFile 자리표시자를 쓴 파일(모듈과 테스트 소스 여부를 가린다). 모르면 null
     */
    Lookup lookup(String key, String contextFile) {
        return lookup(key, contextFile, 0);
    }

    private Lookup lookup(String key, String contextFile, int depth) {
        boolean fromTest = ModuleRoots.isTestPath(contextFile);
        String module = modules.rootOf(contextFile);
        Set<String> all = new LinkedHashSet<>();
        Set<String> sameModule = new LinkedHashSet<>();
        for (FileValues file : files) {
            List<String> declared = file.values().get(key);
            if (declared == null || (!fromTest && ModuleRoots.isTestPath(file.file()))) {
                continue;
            }
            // 자기 자신을 가리키는 값(app.name=${app.name}, @app.name@)은 빌드 도구가 채우는 자리일 뿐 값이 아니다(F-012-R47)
            List<String> values = declared.stream().filter(v -> !selfReference(key, v)).toList();
            all.addAll(values);
            if (module != null && module.equals(modules.rootOf(file.file()))) {
                sameModule.addAll(values);
            }
        }
        Set<String> candidates = sameModule.isEmpty() ? all : sameModule;
        if (candidates.isEmpty()) {
            return new Lookup(Status.MISSING, null);
        }
        if (candidates.size() > 1) {
            return new Lookup(Status.AMBIGUOUS, null);
        }
        String value = candidates.iterator().next();
        if (hasPlaceholder(value)) {
            // 값 안의 자리표시자도 같은 규칙으로 푼다. 끝까지 못 풀면 값을 모르는 것이다
            if (depth >= MAX_DEPTH) {
                return new Lookup(Status.MISSING, null);
            }
            Resolution nested = resolve(value, contextFile, depth + 1);
            if (!nested.complete()) {
                return new Lookup(Status.MISSING, null);
            }
            value = nested.text();
        }
        return new Lookup(Status.FOUND, value);
    }

    /** 값이 그 키 자신의 자리표시자 하나뿐인가({@code ${키}}, Maven 리소스 필터링의 {@code @키@}). */
    private static boolean selfReference(String key, String value) {
        String v = value == null ? "" : value.trim();
        return v.equals("${" + key + "}") || v.equals("@" + key + "@");
    }

    /** 문자열 안의 자리표시자를 푼다. 못 푼 것은 원문 그대로 남기고 {@link Resolution#unresolved()}에 적는다. */
    Resolution resolve(String text, String contextFile) {
        return resolve(text, contextFile, 0);
    }

    private Resolution resolve(String text, String contextFile, int depth) {
        if (text == null || !hasPlaceholder(text)) {
            return new Resolution(text, false, List.of());
        }
        StringBuilder out = new StringBuilder();
        List<String> unresolved = new ArrayList<>();
        boolean changed = false;
        int at = 0;
        while (true) {
            int start = nextPlaceholder(text, at);
            if (start < 0) {
                out.append(text, at, text.length());
                break;
            }
            int end = closing(text, start + 1);
            out.append(text, at, start);
            String original = text.substring(start, end + 1);
            String inside = text.substring(start + 2, end);
            int colon = inside.indexOf(':');
            String key = (colon >= 0 ? inside.substring(0, colon) : inside).trim();
            String fallback = colon >= 0 ? inside.substring(colon + 1) : null;
            Lookup found = key.isEmpty() ? new Lookup(Status.MISSING, null) : lookup(key, contextFile, depth);
            String value = null;
            if (found.status() == Status.FOUND) {
                value = found.value();
            } else if (found.status() == Status.MISSING && fallback != null) {
                Resolution nested = depth < MAX_DEPTH ? resolve(fallback, contextFile, depth + 1) : new Resolution(fallback, false, List.of());
                value = nested.complete() ? nested.text() : null;
            }
            if (value == null) {
                out.append(original);
                unresolved.add(original);
            } else {
                out.append(value);
                changed = true;
            }
            at = end + 1;
        }
        return new Resolution(out.toString(), changed, List.copyOf(unresolved));
    }

    /** {@code from}부터 처음 나오는 닫힌 {@code ${…}}의 시작 위치. 없으면 -1. */
    private static int nextPlaceholder(String text, int from) {
        int start = text.indexOf("${", from);
        while (start >= 0) {
            if (closing(text, start + 1) > start) {
                return start;
            }
            start = text.indexOf("${", start + 2);
        }
        return -1;
    }

    /** {@code open} 위치의 여는 중괄호와 짝이 맞는 닫는 중괄호. 없으면 -1. */
    private static int closing(String text, int open) {
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

    // ---- 파일 읽기 ----

    private static Map<String, List<String>> loadProperties(String text) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            values.put(key, List.of(properties.getProperty(key).trim()));
        }
        return values;
    }

    /**
     * 들여쓰기로 중첩된 {@code key: value} 줄만 읽는 간이 YAML 해석. 목록과 여러 줄 값은 건너뛴다.
     * {@code ---}로 나뉜 문서(프로파일)마다 같은 키가 나오면 값을 모두 모은다.
     */
    static Map<String, List<String>> flattenYaml(String text) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        Deque<int[]> indents = new ArrayDeque<>();
        Deque<String> keys = new ArrayDeque<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) {
                continue;
            }
            if (raw.startsWith("---")) {
                indents.clear();
                keys.clear();
                continue;
            }
            int indent = raw.length() - raw.stripLeading().length();
            String line = raw.strip();
            int colon = line.indexOf(':');
            if (line.startsWith("-") || colon <= 0) {
                continue;
            }
            while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                indents.pop();
                keys.pop();
            }
            String key = unquote(line.substring(0, colon).trim());
            String value = line.substring(colon + 1).trim();
            if (!value.startsWith("\"") && !value.startsWith("'")) {
                int comment = value.indexOf(" #");
                if (comment >= 0) {
                    value = value.substring(0, comment).trim();
                }
                if (value.startsWith("#")) {
                    value = "";
                }
            }
            indents.push(new int[] {indent});
            keys.push(key);
            if (!value.isEmpty()) {
                List<String> path = new ArrayList<>(keys);
                Collections.reverse(path);
                List<String> all = values.computeIfAbsent(String.join(".", path), k -> new ArrayList<>());
                String unquoted = unquote(value);
                if (!all.contains(unquoted)) {
                    all.add(unquoted);
                }
            }
        }
        return values;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')) {
            int end = s.indexOf(s.charAt(0), 1);
            return end > 0 ? s.substring(1, end) : s.substring(1);
        }
        return s;
    }
}
