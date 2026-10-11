package io.codeatlas.java;

import io.codeatlas.core.io.TextDecoder;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * JDT에 넘기기 전에 Java 파일의 앞부분(package, import)만 가볍게 훑는다.
 * 파일마다 인코딩과 패키지를 알아내 소스 루트를 추정하고, 프로젝트 전체의 단일 import를 모은다
 * (classpath에 없는 타입의 단순 이름 → FQN 복원에 쓴다).
 */
final class SourcePrescan {

    /** 분석할 파일 하나. */
    record Entry(SourceFile file, String charsetName, String packageName) {
    }

    final List<Entry> entries = new ArrayList<>();
    /** 추정한 소스 루트(절대 경로). */
    final Set<Path> sourceRoots = new LinkedHashSet<>();
    /** 단순 이름 → 프로젝트 어디선가 단일 import로 쓴 FQN들. */
    final Map<String, Set<String>> importedBySimpleName = new HashMap<>();
    /** 파일 이름으로 본 최상위 타입의 FQN(패키지 + 확장자를 뗀 파일 이름). */
    final Set<String> sourceTypes = new HashSet<>();
    /** 파일 이름으로 본 최상위 타입의 FQN → 그 파일의 import. 다른 파일에 쓴 타입 이름을 그 파일의 문맥으로 복원할 때 쓴다(F-003-R17). */
    final Map<String, FileImports> importsBySourceType = new HashMap<>();
    /**
     * {@code @Value("${키}")}가 붙은 필드: {@code <파일 이름으로 본 최상위 타입의 FQN>#<필드명>} → 자리표시자 원문({@code ${키}}).
     * 다른 파일(상위 클래스)의 필드를 이어 붙인 경로를 풀 때 쓴다(F-004-R23). 파일 순서와 상관없이 알 수 있게 미리 훑는다.
     */
    final Map<String, String> valueFields = new HashMap<>();

    /** {@code @Value("${키}")} 뒤에 오는 필드 선언(다른 애너테이션·수식어·타입을 건너뛴 이름). */
    private static final java.util.regex.Pattern VALUE_FIELD = java.util.regex.Pattern.compile(
            "@Value\\s*\\(\\s*\"(\\$\\{[^\"{}]+})\"\\s*\\)\\s*(?:@\\w[\\w.]*(?:\\([^)]*\\))?\\s*)*"
                    + "(?:(?:private|protected|public|static|final|transient|volatile)\\s+)*[\\w.]+(?:<[^;=(){}]*>)?(?:\\[\\])*\\s+(\\w+)\\s*[;=]");

    /**
     * 파일 하나의 import(static 제외).
     *
     * @param single 단일 import: 단순 이름 → FQN
     * @param onDemand 와일드카드 import의 패키지(또는 타입) 이름
     */
    record FileImports(Map<String, String> single, List<String> onDemand) {
        static final FileImports NONE = new FileImports(Map.of(), List.of());
    }

    static SourcePrescan scan(List<SourceFile> javaFiles, GraphModel graph) {
        SourcePrescan result = new SourcePrescan();
        for (SourceFile file : javaFiles) {
            try {
                TextDecoder.Decoded decoded = TextDecoder.decode(file.readBytes(), file.encoding());
                Header header = Header.parse(decoded.text());
                result.entries.add(new Entry(file, decoded.charset().name(), header.packageName));
                for (String imported : header.singleImports) {
                    int dot = imported.lastIndexOf('.');
                    if (dot > 0) {
                        result.importedBySimpleName.computeIfAbsent(imported.substring(dot + 1), k -> new TreeSet<>()).add(imported);
                    }
                }
                String fileName = file.fileName();
                String typeName = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
                if (!typeName.contains("-")) {
                    String sourceType = header.packageName.isEmpty() ? typeName : header.packageName + "." + typeName;
                    result.sourceTypes.add(sourceType);
                    Map<String, String> single = new HashMap<>();
                    for (String imported : header.singleImports) {
                        single.put(JavaProject.simpleName(imported), imported);
                    }
                    result.importsBySourceType.put(sourceType, new FileImports(single, List.copyOf(header.onDemandImports)));
                    if (decoded.text().contains("@Value")) {
                        java.util.regex.Matcher value = VALUE_FIELD.matcher(decoded.text());
                        while (value.find()) {
                            result.valueFields.putIfAbsent(sourceType + "#" + value.group(2), value.group(1));
                        }
                    }
                }
                result.addSourceRoot(file, header.packageName, graph);
            } catch (RuntimeException e) {
                graph.report(Diagnostic.error(DiagnosticCodes.FILE_NOT_READABLE, "Java 파일을 읽지 못함: " + e.getMessage(), file.location()));
            }
        }
        return result;
    }

    private void addSourceRoot(SourceFile file, String packageName, GraphModel graph) {
        Path dir = file.absolutePath().getParent();
        if (dir == null) {
            return;
        }
        if (packageName.isEmpty()) {
            sourceRoots.add(dir);
            return;
        }
        String[] segments = packageName.split("\\.");
        Path root = dir;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (root == null || root.getFileName() == null || !root.getFileName().toString().equals(segments[i])) {
                graph.report(Diagnostic.info(JavaProject.SOURCE_ROOT_MISMATCH,
                        "패키지 선언(" + packageName + ")과 파일 경로가 맞지 않아 소스 루트를 정하지 못함", file.location()));
                return;
            }
            root = root.getParent();
        }
        if (root != null) {
            sourceRoots.add(root);
        }
    }

    /** 파일 머리의 package와 import. 주석과 문자열을 건너뛰며 첫 타입 선언 전까지만 읽는다. */
    static final class Header {
        String packageName = "";
        final List<String> singleImports = new ArrayList<>();
        final List<String> onDemandImports = new ArrayList<>();

        static Header parse(String text) {
            Header header = new Header();
            int i = 0;
            int n = text.length();
            while (i < n) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                    int end = text.indexOf('\n', i);
                    i = end < 0 ? n : end + 1;
                } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                    int end = text.indexOf("*/", i + 2);
                    i = end < 0 ? n : end + 2;
                } else if (c == '"' || c == '\'') {
                    i = skipLiteral(text, i);
                } else if (Character.isJavaIdentifierStart(c)) {
                    int start = i;
                    while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) {
                        i++;
                    }
                    String word = text.substring(start, i);
                    switch (word) {
                        case "package" -> {
                            int end = text.indexOf(';', i);
                            if (end < 0) {
                                return header;
                            }
                            header.packageName = compact(text.substring(i, end));
                            i = end + 1;
                        }
                        case "import" -> {
                            int end = text.indexOf(';', i);
                            if (end < 0) {
                                return header;
                            }
                            String name = compact(text.substring(i, end));
                            if (name.startsWith("static ")) {
                                // static import는 타입 이름 복원에 쓰지 않는다
                            } else if (name.endsWith(".*")) {
                                header.onDemandImports.add(name.substring(0, name.length() - 2));
                            } else if (!name.endsWith("*")) {
                                header.singleImports.add(name);
                            }
                            i = end + 1;
                        }
                        case "class", "interface", "enum", "record" -> {
                            return header;
                        }
                        default -> {
                        }
                    }
                } else if (c == '{') {
                    return header;
                } else {
                    i++;
                }
            }
            return header;
        }

        private static int skipLiteral(String text, int start) {
            char quote = text.charAt(start);
            int i = start + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                } else if (c == quote || c == '\n') {
                    return i + 1;
                } else {
                    i++;
                }
            }
            return i;
        }

        /** 주석과 공백을 걷어낸다. {@code static} 뒤의 공백 하나만 남긴다. */
        private static String compact(String raw) {
            String s = raw.replaceAll("/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ").trim();
            boolean isStatic = s.startsWith("static") && s.length() > 6 && Character.isWhitespace(s.charAt(6));
            if (isStatic) {
                s = s.substring(6);
            }
            s = s.replaceAll("\\s+", "");
            return isStatic ? "static " + s : s;
        }
    }
}
