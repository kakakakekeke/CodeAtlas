package io.codeatlas.core.testing;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.scan.ProjectScanner;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.Linker;
import io.codeatlas.core.spi.ProjectFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 모듈 단위 테스트용 가짜 프로젝트. 임시 디렉터리에 파일을 만들고, 실제 스캐너로 스캔한 컨텍스트를 만들어 분석기와 링커를 돌려 본다.
 * JUnit에 의존하지 않는다.
 *
 * <pre>{@code
 * try (TestProject project = TestProject.create()
 *         .file("src/main/java/com/a/B.java", "package com.a; class B {}")
 *         .file("mapper/OrderMapper.xml", "<mapper namespace=\"com.a.OrderMapper\">...</mapper>")) {
 *     AnalysisContext ctx = project.run(new MyAnalyzer());
 *     assertThat(ctx.graph().node(Ids.javaType("com.a.B"))).isPresent();
 * }
 * }</pre>
 *
 * <p>미리 채운 그래프나 내장 소스로 시작하려면 {@link #context()}로 컨텍스트를 받아 채운 뒤 {@link #run(AnalysisContext, Object...)}를 쓴다.
 * 파이프라인과 달리 분석기 예외를 삼키지 않고 그대로 던지고, 마무리 단계(매달린 엣지 처리)는 하지 않는다.
 * 필요하면 {@code ctx.graph().resolveDanglingEdges()}를 직접 부른다.
 */
public final class TestProject implements AutoCloseable {

    private final Path root;
    private final boolean deleteOnClose;
    private AnalysisOptions options = AnalysisOptions.defaults();

    private TestProject(Path root, boolean deleteOnClose) {
        this.root = root.toAbsolutePath().normalize();
        this.deleteOnClose = deleteOnClose;
    }

    /** 새 임시 디렉터리에 만든다. {@link #close()}가 디렉터리를 지운다. */
    public static TestProject create() {
        try {
            return new TestProject(Files.createTempDirectory("codeatlas-test-"), true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 이미 있는 디렉터리(JUnit {@code @TempDir}, {@code samples/} 아래 폴더 등)를 쓴다. {@link #close()}가 지우지 않는다. */
    public static TestProject at(Path directory) {
        return new TestProject(directory, false);
    }

    /** 상대 경로 → 본문 맵으로 새 임시 프로젝트를 만든다. */
    public static TestProject of(Map<String, String> files) {
        return create().files(files);
    }

    /** UTF-8 파일을 쓴다. 상위 디렉터리는 알아서 만든다. */
    public TestProject file(String relativePath, String content) {
        return file(relativePath, content, StandardCharsets.UTF_8);
    }

    /** 지정한 인코딩으로 파일을 쓴다. */
    public TestProject file(String relativePath, String content, Charset charset) {
        return bytes(relativePath, content.getBytes(charset));
    }

    /** 바이트 그대로 파일을 쓴다. */
    public TestProject bytes(String relativePath, byte[] content) {
        try {
            Path file = root.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.write(file, content);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public TestProject files(Map<String, String> files) {
        files.forEach(this::file);
        return this;
    }

    /** 이후 만드는 컨텍스트에 쓸 옵션. */
    public TestProject options(AnalysisOptions options) {
        this.options = options == null ? AnalysisOptions.defaults() : options;
        return this;
    }

    /** 프로젝트 루트(절대 경로). */
    public Path root() {
        return root;
    }

    /** 지금 파일 상태를 스캔해 새 컨텍스트를 만든다. 그래프에는 FILE 노드만 들어 있다. 부를 때마다 새 그래프다. */
    public AnalysisContext context() {
        GraphModel graph = new GraphModel();
        ProjectFiles files = ProjectScanner.scan(root, options, List.of(), graph::report);
        ProjectScanner.declareFileNodes(files, graph);
        return AnalysisContext.of(root, files, graph, options);
    }

    /**
     * 새 컨텍스트에서 분석기와 링커를 돌린다. 인자는 {@link Analyzer}나 {@link Linker}이고 섞어 줘도 된다.
     * 분석기 전부를 order 오름차순으로, 그다음 링커 전부를 order 오름차순으로 실행한다(같은 order면 준 순서).
     */
    public AnalysisContext run(Object... analyzersAndLinkers) {
        return run(context(), analyzersAndLinkers);
    }

    /** 주어진 컨텍스트에서 분석기와 링커를 돌린다. 예외는 삼키지 않는다. */
    public static AnalysisContext run(AnalysisContext ctx, Object... analyzersAndLinkers) {
        List<Analyzer> analyzers = new ArrayList<>();
        List<Linker> linkers = new ArrayList<>();
        for (Object component : analyzersAndLinkers) {
            boolean known = false;
            if (component instanceof Analyzer analyzer) {
                analyzers.add(analyzer);
                known = true;
            }
            if (component instanceof Linker linker) {
                linkers.add(linker);
                known = true;
            }
            if (!known) {
                throw new IllegalArgumentException("Analyzer나 Linker가 아님: " + component);
            }
        }
        analyzers.sort(Comparator.comparingInt(Analyzer::order));
        linkers.sort(Comparator.comparingInt(Linker::order));
        for (Analyzer analyzer : analyzers) {
            try {
                analyzer.analyze(ctx);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("분석기 '" + analyzer.name() + "' 실패: " + e, e);
            }
        }
        for (Linker linker : linkers) {
            try {
                linker.link(ctx);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("링커 '" + linker.name() + "' 실패: " + e, e);
            }
        }
        return ctx;
    }

    /** {@link #create()}로 만든 임시 디렉터리를 지운다. {@link #at(Path)}로 받은 디렉터리는 그대로 둔다. */
    @Override
    public void close() {
        if (!deleteOnClose || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
