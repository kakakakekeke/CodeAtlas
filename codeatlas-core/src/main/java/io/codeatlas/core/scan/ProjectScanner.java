package io.codeatlas.core.scan;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.ProjectFiles;
import io.codeatlas.core.spi.SourceFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 프로젝트 폴더를 훑어 분석 대상 파일 목록을 만든다. 파일은 읽기만 한다.
 *
 * <ul>
 * <li>기본 제외 디렉터리 중 {@link #ALWAYS_EXCLUDED_DIRECTORIES}는 어느 깊이에 있든 들어가지 않는다.
 *     {@link #BUILD_OUTPUT_DIRECTORIES}는 프로젝트 루트부터의 조상 경로에 {@code src} 디렉터리가 없을 때만 뺀다
 *     ({@code src/main/java/com/x/batch/out/}은 Java 패키지이므로 스캔한다).</li>
 * <li>{@code --exclude} glob은 상대 경로('/' 구분)에 맞춘다. {@code *.min.js}나 {@code generated}처럼
 *     경로 앞부분이 없는 패턴은 어느 깊이에서든 맞는다. 디렉터리가 맞으면 그 아래 전체를 뺀다.</li>
 * <li>{@code --ddl}로 직접 준 파일은 확장자와 상관없이 SQL로 넣는다. 폴더를 주면 그 아래의 SQL 확장자 파일을 넣는다.
 *     프로젝트 밖의 DDL은 {@code ddl/<파일명>} 가상 경로를 쓰고, 경로가 겹치면 {@code ddl/_2/<파일명>}처럼 번호를 붙인다.</li>
 * </ul>
 */
public final class ProjectScanner {

    /** 어디에 있든 제외하는 디렉터리 이름. */
    public static final Set<String> ALWAYS_EXCLUDED_DIRECTORIES = Set.of(".git", "node_modules", ".idea", ".gradle");

    /** 빌드 산출물 디렉터리 이름. 조상 경로에 {@code src} 디렉터리가 있으면 소스 패키지로 보고 제외하지 않는다. */
    public static final Set<String> BUILD_OUTPUT_DIRECTORIES = Set.of("target", "build", "dist", "out", "bin");

    /** 기본 제외 디렉터리 이름 전체(설계 4.2). */
    public static final Set<String> DEFAULT_EXCLUDED_DIRECTORIES =
            Set.of(".git", "node_modules", "target", "build", "dist", "out", ".idea", ".gradle", "bin");

    /** 이 이름의 디렉터리 아래에서는 빌드 산출물 이름을 제외하지 않는다. */
    private static final String SOURCE_ROOT_NAME = "src";

    private ProjectScanner() {
    }

    /** 진단을 버리고 스캔한다. */
    public static ProjectFiles scan(Path projectRoot, AnalysisOptions options) {
        return scan(projectRoot, options, List.of(), d -> { });
    }

    /**
     * @param excludedPaths 스캔하지 않을 경로(출력 폴더 등)
     * @param diagnostics 건너뛴 디렉터리, 없는 DDL 경로 같은 진단을 받는 곳
     * @throws UncheckedIOException 프로젝트 루트를 읽지 못하면
     */
    public static ProjectFiles scan(Path projectRoot, AnalysisOptions options, Collection<Path> excludedPaths, Consumer<Diagnostic> diagnostics) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Set<Path> excluded = new HashSet<>();
        for (Path p : excludedPaths) {
            excluded.add(p.toAbsolutePath().normalize());
        }
        Scan scan = new Scan(root, options, excluded, diagnostics);
        try {
            scan.walkProject();
            for (Path ddl : options.ddlPaths()) {
                scan.addDdlPath(ddl.toAbsolutePath().normalize());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("프로젝트를 스캔하지 못함: " + root, e);
        }
        return ProjectFiles.of(scan.files.values());
    }

    /**
     * 스캔한 파일마다 FILE 노드({@code file:<상대경로>}, {@code attrs.fileType})를 그래프에 정의한다.
     * 종류가 OTHER나 JAR인 파일은 만들지 않는다.
     */
    public static void declareFileNodes(ProjectFiles files, GraphModel graph) {
        for (SourceFile file : files.all()) {
            if (file.type() == FileType.OTHER || file.type() == FileType.JAR) {
                continue;
            }
            graph.declare(Node.builder(file.nodeId(), NodeKind.FILE)
                    .name(file.fileName())
                    .qualifiedName(file.relativePath())
                    .location(file.location())
                    .attr("fileType", file.type().name())
                    .build());
        }
    }

    private static final class Scan {
        private final Path root;
        private final Charset encoding;
        private final Set<Path> excludedPaths;
        private final Consumer<Diagnostic> diagnostics;
        private final List<PathMatcher> excludeMatchers = new ArrayList<>();
        private final Map<String, SourceFile> files = new TreeMap<>();

        Scan(Path root, AnalysisOptions options, Set<Path> excludedPaths, Consumer<Diagnostic> diagnostics) {
            this.root = root;
            this.encoding = options.encoding();
            this.excludedPaths = excludedPaths;
            this.diagnostics = diagnostics;
            for (String glob : options.excludes()) {
                addExclude(glob);
            }
        }

        private void addExclude(String glob) {
            String pattern = glob.trim().replace('\\', '/');
            while (pattern.endsWith("/") && pattern.length() > 1) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            boolean anchored = pattern.startsWith("/");
            if (anchored) {
                pattern = pattern.substring(1);
            }
            if (pattern.isEmpty()) {
                return;
            }
            try {
                excludeMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
                if (!anchored && !pattern.startsWith("**/")) {
                    excludeMatchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + pattern));
                }
            } catch (RuntimeException e) {
                diagnostics.accept(Diagnostic.warn(DiagnosticCodes.INVALID_OPTION, "제외 패턴을 해석하지 못해 무시함: " + glob, null));
            }
        }

        private boolean matchesExclude(String relativePath) {
            if (excludeMatchers.isEmpty()) {
                return false;
            }
            Path relative = Path.of(relativePath);
            for (PathMatcher matcher : excludeMatchers) {
                if (matcher.matches(relative)) {
                    return true;
                }
            }
            return false;
        }

        private String relative(Path absolute) {
            return root.relativize(absolute).toString().replace('\\', '/');
        }

        void walkProject() throws IOException {
            if (!Files.isDirectory(root)) {
                throw new IOException("프로젝트 폴더가 아님: " + root);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = relative(dir);
                    if (excludedPaths.contains(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (excludedByDefault(dir.getFileName().toString(), rel)) {
                        diagnostics.accept(Diagnostic.info(DiagnosticCodes.EXCLUDED_DIRECTORY,
                                "기본 제외 디렉터리라 스캔하지 않음: " + rel, SourceLocation.ofFile(rel)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return matchesExclude(rel) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !excludedPaths.contains(file)) {
                        String rel = relative(file);
                        if (!matchesExclude(rel)) {
                            files.put(rel, new SourceFile(file, rel, FileClassifier.classify(file), encoding));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    String rel = relative(file);
                    diagnostics.accept(Diagnostic.warn(DiagnosticCodes.FILE_NOT_READABLE,
                            "읽지 못해 건너뜀: " + rel + " (" + e + ")", SourceLocation.ofFile(rel)));
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        /** @param rel 디렉터리의 상대 경로(자기 이름 포함) */
        private boolean excludedByDefault(String name, String rel) {
            if (ALWAYS_EXCLUDED_DIRECTORIES.contains(name)) {
                return true;
            }
            if (!BUILD_OUTPUT_DIRECTORIES.contains(name)) {
                return false;
            }
            String[] segments = rel.split("/");
            for (int i = 0; i < segments.length - 1; i++) {
                if (segments[i].equals(SOURCE_ROOT_NAME)) {
                    return false;
                }
            }
            return true;
        }

        void addDdlPath(Path path) throws IOException {
            if (Files.isRegularFile(path)) {
                addDdlFile(path, path.getFileName().toString());
            } else if (Files.isDirectory(path)) {
                List<Path> found = new ArrayList<>();
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && FileClassifier.classifyByName(file.getFileName().toString()) == FileType.SQL) {
                            found.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        diagnostics.accept(Diagnostic.warn(DiagnosticCodes.FILE_NOT_READABLE, "읽지 못해 건너뜀: " + file + " (" + e + ")", null));
                        return FileVisitResult.CONTINUE;
                    }
                });
                found.sort(null);
                for (Path file : found) {
                    addDdlFile(file, path.relativize(file).toString().replace('\\', '/'));
                }
            } else {
                diagnostics.accept(Diagnostic.warn(DiagnosticCodes.FILE_NOT_FOUND, "DDL 경로가 없음: " + path, null));
            }
        }

        /** @param virtualName 프로젝트 밖 파일일 때 {@code ddl/} 아래에 쓸 이름 */
        private void addDdlFile(Path file, String virtualName) {
            if (isInsideRoot(file)) {
                String rel = relative(file);
                files.put(rel, new SourceFile(file, rel, FileType.SQL, encoding));
                return;
            }
            for (SourceFile existing : files.values()) {
                if (existing.absolutePath().equals(file)) {
                    return;
                }
            }
            String rel = "ddl/" + virtualName;
            for (int n = 2; files.containsKey(rel); n++) {
                rel = "ddl/_" + n + "/" + virtualName;
            }
            files.put(rel, new SourceFile(file, rel, FileType.SQL, encoding));
        }

        private boolean isInsideRoot(Path file) {
            return file.startsWith(root);
        }
    }
}
