package io.codeatlas.mybatis;

import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프로젝트 Java 파일의 "단순 이름 → FQN" 색인. Java를 파싱하지 않고 파일 이름과 {@code package} 선언만 본다.
 * 그래서 파일 이름과 같은 최상위 타입만 안다. 패키지 선언은 필요한 파일만 읽는다.
 */
final class JavaTypeIndex {

    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*;");

    /** 소문자 단순 이름 → 파일들. */
    private final Map<String, List<SourceFile>> bySimpleName = new HashMap<>();
    private final List<SourceFile> files;
    private final Map<String, String> packageCache = new HashMap<>();

    JavaTypeIndex(AnalysisContext ctx) {
        this.files = ctx.files().ofType(FileType.JAVA);
        for (SourceFile file : files) {
            String simple = simpleName(file);
            if (!simple.isEmpty() && !simple.equals("package-info") && !simple.equals("module-info")) {
                bySimpleName.computeIfAbsent(simple.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(file);
            }
        }
    }

    /** 단순 이름(대소문자 무시)이 같은 파일이 정확히 하나면 그 FQN, 아니면 null. */
    String uniqueBySimpleName(String simpleName) {
        List<SourceFile> candidates = bySimpleName.get(simpleName.toLowerCase(Locale.ROOT));
        if (candidates == null || candidates.size() != 1) {
            return null;
        }
        return fqn(candidates.get(0));
    }

    /**
     * 별칭 패키지에 든 타입들. 하위 패키지를 포함한다(MyBatis의 패키지 스캔과 같다).
     *
     * @param packageSpec 패키지 이름. {@code *}(한 조각), {@code **}(여러 조각) 와일드카드를 쓸 수 있다
     * @return 단순 이름 → FQN (파일 경로 순)
     */
    Map<String, String> typesInPackage(String packageSpec) {
        Map<String, String> result = new LinkedHashMap<>();
        Pattern pattern = packagePattern(packageSpec);
        String literalPrefix = literalPathPrefix(packageSpec);
        for (SourceFile file : files) {
            if (!literalPrefix.isEmpty() && !("/" + file.relativePath()).contains("/" + literalPrefix + "/")) {
                continue;
            }
            String pkg = packageOf(file);
            if (pkg != null && pattern.matcher(pkg).matches()) {
                String simple = simpleName(file);
                result.putIfAbsent(simple, pkg.isEmpty() ? simple : pkg + "." + simple);
            }
        }
        return result;
    }

    private String fqn(SourceFile file) {
        String pkg = packageOf(file);
        if (pkg == null) {
            return null;
        }
        return pkg.isEmpty() ? simpleName(file) : pkg + "." + simpleName(file);
    }

    /** 파일의 패키지 이름. 기본 패키지면 빈 문자열, 읽지 못하면 null. */
    private String packageOf(SourceFile file) {
        if (packageCache.containsKey(file.relativePath())) {
            return packageCache.get(file.relativePath());
        }
        String pkg;
        try {
            Matcher m = PACKAGE.matcher(file.readText());
            pkg = m.find() ? m.group(1).replaceAll("\\s+", "") : "";
        } catch (RuntimeException e) {
            pkg = null;
        }
        packageCache.put(file.relativePath(), pkg);
        return pkg;
    }

    private static String simpleName(SourceFile file) {
        String name = file.fileName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** 패키지 지정을 "그 패키지이거나 그 하위 패키지"에 맞는 정규식으로 바꾼다. */
    private static Pattern packagePattern(String spec) {
        StringBuilder regex = new StringBuilder();
        String[] segments = spec.trim().split("\\.");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.equals("**")) {
                // 0개 이상의 조각
                regex.append(i == 0 ? "(?:[\\w$]+\\.)*" : "(?:\\.[\\w$]+)*");
                if (i == 0) {
                    continue;
                }
            } else {
                if (i > 0 && !(i == 1 && segments[0].equals("**"))) {
                    regex.append("\\.");
                }
                regex.append(Pattern.quote(segment).replace("*", "\\E[\\w$]*\\Q"));
            }
        }
        regex.append("(?:\\.[\\w$]+)*");
        return Pattern.compile(regex.toString());
    }

    /** 와일드카드 앞까지의 패키지를 경로 조각으로. 파일을 읽기 전에 거르는 데 쓴다. */
    private static String literalPathPrefix(String spec) {
        StringBuilder path = new StringBuilder();
        for (String segment : spec.trim().split("\\.")) {
            if (segment.contains("*") || segment.isEmpty()) {
                break;
            }
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(segment);
        }
        return path.toString();
    }
}
