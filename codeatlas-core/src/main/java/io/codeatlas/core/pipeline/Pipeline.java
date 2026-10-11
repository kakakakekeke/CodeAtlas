package io.codeatlas.core.pipeline;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.scan.ProjectScanner;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.Exporter;
import io.codeatlas.core.spi.Linker;
import io.codeatlas.core.spi.ProjectFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * 분석 파이프라인. 스캔 → 분석기 전부(order 오름차순) → 링커 전부(order 오름차순) → 마무리(매달린 엣지 처리) → 저장 → 출력기.
 * 분석기, 링커, 출력기 하나가 예외를 던지면 ERROR 진단을 남기고 다음 것으로 간다.
 * 힙이 모자라면({@link OutOfMemoryError}) 계속하지 않고, 어느 단계의 어떤 구현체가 무엇을 처리하던 중이었는지를 담은
 * {@link AnalysisOutOfMemoryError}로 바꿔 던진다(처리 중인 파일은 구현체가 {@code AnalysisContext.progress}로 알린 값이다).
 *
 * <pre>{@code
 * PipelineResult result = Pipeline.discover().run(projectDir, AnalysisOptions.defaults(), outputDir);   // 전체
 * Pipeline.discover().withoutExporters().run(projectDir, options, outputDir);                           // 분석과 graph.json 저장만
 * ExportResult exported = Pipeline.discover().export(GraphJson.read(graphFile), outputDir, options);    // 저장된 그래프로 출력기만
 * }</pre>
 */
public final class Pipeline {

    /** 출력 폴더에 쓰는 그래프 파일 이름. */
    public static final String GRAPH_FILE = "graph.json";

    private final List<Analyzer> analyzers;
    private final List<Linker> linkers;
    private final List<Exporter> exporters;
    private final List<LoadFailure> loadFailures = new ArrayList<>();
    private Consumer<String> progress = message -> { };

    /** 구현체를 직접 준다. 분석기와 링커는 order 오름차순(같으면 이름순)으로 정렬해 둔다. */
    public Pipeline(Collection<? extends Analyzer> analyzers, Collection<? extends Linker> linkers, Collection<? extends Exporter> exporters) {
        List<Analyzer> sortedAnalyzers = new ArrayList<>(analyzers);
        sortedAnalyzers.sort(Comparator.comparingInt(Analyzer::order).thenComparing(Analyzer::name));
        List<Linker> sortedLinkers = new ArrayList<>(linkers);
        sortedLinkers.sort(Comparator.comparingInt(Linker::order).thenComparing(Linker::name));
        List<Exporter> sortedExporters = new ArrayList<>(exporters);
        sortedExporters.sort(Comparator.comparing(Exporter::name));
        this.analyzers = List.copyOf(sortedAnalyzers);
        this.linkers = List.copyOf(sortedLinkers);
        this.exporters = List.copyOf(sortedExporters);
    }

    /** classpath의 {@code META-INF/services}에 등록된 구현체를 {@link ServiceLoader}로 찾아 파이프라인을 만든다. */
    public static Pipeline discover() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return discover(loader != null ? loader : Pipeline.class.getClassLoader());
    }

    public static Pipeline discover(ClassLoader loader) {
        List<LoadFailure> failures = new ArrayList<>();
        Pipeline pipeline = new Pipeline(
                load(Analyzer.class, loader, failures),
                load(Linker.class, loader, failures),
                load(Exporter.class, loader, failures));
        pipeline.loadFailures.addAll(failures);
        return pipeline;
    }

    /** ServiceLoader가 만들지 못한 구현체 하나. type은 SPI 인터페이스다. */
    private record LoadFailure(Class<?> type, String message) {
    }

    private static <T> List<T> load(Class<T> type, ClassLoader loader, List<LoadFailure> failures) {
        List<T> found = new ArrayList<>();
        Iterator<T> iterator = ServiceLoader.load(type, loader).iterator();
        int errors = 0;
        while (errors < 100) {
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                found.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                errors++;
                failures.add(new LoadFailure(type, type.getSimpleName() + " 구현체를 만들지 못함: " + e.getMessage()));
            }
        }
        return found;
    }

    /** 진행 상황 메시지를 받을 곳(예: {@code System.err::println}). */
    public Pipeline onProgress(Consumer<String> listener) {
        this.progress = listener == null ? message -> { } : listener;
        return this;
    }

    /**
     * 분석기와 링커는 그대로 두고 출력기만 뺀 파이프라인을 돌려준다. {@link #run}이 스캔부터 graph.json 저장까지만 한다.
     * 출력기를 쓰지 않으므로 만들지 못한 출력기의 {@code SERVICE_LOAD_FAILED}는 남기지 않는다(분석기·링커 것은 그대로 남긴다).
     * 진행 메시지를 받는 곳은 이어받는다.
     */
    public Pipeline withoutExporters() {
        Pipeline pipeline = new Pipeline(analyzers, linkers, List.of());
        for (LoadFailure failure : loadFailures) {
            if (failure.type() != Exporter.class) {
                pipeline.loadFailures.add(failure);
            }
        }
        pipeline.progress = progress;
        return pipeline;
    }

    /** 실행 순서대로 정렬된 분석기. */
    public List<Analyzer> analyzers() {
        return analyzers;
    }

    public List<Linker> linkers() {
        return linkers;
    }

    public List<Exporter> exporters() {
        return exporters;
    }

    /**
     * 전체를 실행한다. 출력 폴더에 graph.json을 쓰고 출력기를 모두 실행한다.
     * 출력 폴더가 프로젝트 안에 있으면 그 폴더는 스캔에서 뺀다.
     *
     * @throws IOException 출력 폴더나 graph.json을 쓰지 못하면
     * @throws java.io.UncheckedIOException 프로젝트 폴더를 읽지 못하면
     */
    public PipelineResult run(Path projectRoot, AnalysisOptions options, Path outputDir) throws IOException {
        Path out = outputDir.toAbsolutePath().normalize();
        AnalysisContext ctx = analyze(projectRoot, options, List.of(out));
        GraphModel graph = ctx.graph();

        Files.createDirectories(out);
        Path graphFile = out.resolve(GRAPH_FILE);
        try {
            GraphJson.write(graph, graphFile);
        } catch (OutOfMemoryError e) {
            throw outOfMemory("저장", null, GRAPH_FILE, e);
        }
        progress.accept("저장: " + graphFile);

        int diagnosticsBefore = graph.diagnostics().size();
        runExporters(graph, out, ctx.options().extra());
        if (graph.diagnostics().size() != diagnosticsBefore) {
            GraphJson.write(graph, graphFile);
        }
        return new PipelineResult(ctx, graphFile);
    }

    /**
     * 분석 없이 이미 있는 그래프(보통 {@code GraphJson.read}로 읽은 것)로 출력기만 이름순으로 실행한다.
     * 출력 폴더가 없으면 만든다. graph.json은 쓰지 않는다(출력기 계약대로 출력 폴더에 graph.json이 있어야 하면 부르는 쪽이 둔다).
     * 출력기가 예외로 끝나면 {@code EXPORTER_FAILED}를, 만들지 못한 출력기가 있었으면 {@code SERVICE_LOAD_FAILED}를 그래프에 남기고
     * 나머지를 계속 실행한다. 이 실행에서 새로 남은 진단은 결과에 담겨 돌아온다.
     *
     * @param options 출력기에 줄 옵션(설계 3.8). null이면 빈 맵
     * @throws IOException 출력 폴더를 만들지 못하면
     */
    public ExportResult export(GraphModel graph, Path outputDir, Map<String, String> options) throws IOException {
        Path out = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(out);
        int diagnosticsBefore = graph.diagnostics().size();
        for (LoadFailure failure : loadFailures) {
            if (failure.type() == Exporter.class) {
                graph.report(Diagnostic.error(DiagnosticCodes.SERVICE_LOAD_FAILED, failure.message(), null));
            }
        }
        List<String> executed = runExporters(graph, out, options == null ? Map.of() : Map.copyOf(options));
        List<Diagnostic> diagnostics = graph.diagnostics();
        return new ExportResult(executed, diagnostics.subList(diagnosticsBefore, diagnostics.size()));
    }

    private List<String> runExporters(GraphModel graph, Path out, Map<String, String> options) {
        List<String> executed = new ArrayList<>();
        for (Exporter exporter : exporters) {
            long start = System.nanoTime();
            try {
                exporter.export(graph, out, options);
            } catch (OutOfMemoryError e) {
                throw outOfMemory("출력기", exporter.name(), null, e);
            } catch (Exception | StackOverflowError | LinkageError e) {
                graph.report(Diagnostic.error(DiagnosticCodes.EXPORTER_FAILED, "출력기 '" + exporter.name() + "' 실패: " + e, null));
            }
            executed.add(exporter.name());
            progress.accept("출력기 " + exporter.name() + " (" + millisSince(start) + "ms)");
        }
        return executed;
    }

    /** 스캔부터 마무리 단계까지만 실행한다. 파일을 쓰지 않는다. */
    public AnalysisContext analyze(Path projectRoot, AnalysisOptions options) {
        return analyze(projectRoot, options, List.of());
    }

    private AnalysisContext analyze(Path projectRoot, AnalysisOptions options, Collection<Path> excludedPaths) {
        AnalysisOptions opts = options == null ? AnalysisOptions.defaults() : options;
        Path root = projectRoot.toAbsolutePath().normalize();
        GraphModel graph = new GraphModel();
        Path name = root.getFileName();
        graph.project(new ProjectInfo(name == null ? root.toString() : name.toString(),
                root.toString().replace('\\', '/'), Instant.now().truncatedTo(ChronoUnit.SECONDS), opts));
        for (LoadFailure failure : loadFailures) {
            graph.report(Diagnostic.error(DiagnosticCodes.SERVICE_LOAD_FAILED, failure.message(), null));
        }

        ProjectFiles files = ProjectScanner.scan(root, opts, excludedPaths, graph::report);
        ProjectScanner.declareFileNodes(files, graph);
        progress.accept("스캔: 파일 " + files.size() + "개");
        AnalysisContext ctx = AnalysisContext.of(root, files, graph, opts);

        for (Analyzer analyzer : analyzers) {
            long start = System.nanoTime();
            ctx.progress(null);
            try {
                analyzer.analyze(ctx);
            } catch (OutOfMemoryError e) {
                throw outOfMemory("분석기", analyzer.name(), ctx.currentActivity(), e);
            } catch (Exception | StackOverflowError | LinkageError e) {
                graph.report(Diagnostic.error(DiagnosticCodes.ANALYZER_FAILED, "분석기 '" + analyzer.name() + "' 실패: " + e, null));
            }
            progress.accept("분석기 " + analyzer.name() + " (" + millisSince(start) + "ms)");
        }
        for (Linker linker : linkers) {
            long start = System.nanoTime();
            ctx.progress(null);
            try {
                linker.link(ctx);
            } catch (OutOfMemoryError e) {
                throw outOfMemory("링커", linker.name(), ctx.currentActivity(), e);
            } catch (Exception | StackOverflowError | LinkageError e) {
                graph.report(Diagnostic.error(DiagnosticCodes.LINKER_FAILED, "링커 '" + linker.name() + "' 실패: " + e, null));
            }
            progress.accept("링커 " + linker.name() + " (" + millisSince(start) + "ms)");
        }
        ctx.progress(null);
        try {
            int unresolved = graph.resolveDanglingEdges();
            progress.accept("마무리: 매달린 엣지에서 미해결 노드 " + unresolved + "개");
        } catch (OutOfMemoryError e) {
            throw outOfMemory("마무리", null, null, e);
        }
        return ctx;
    }

    /**
     * 안내를 담은 오류로 바꾼다. 안내를 만들 메모리조차 없으면 원래 오류를 그대로 돌려준다.
     * 이미 바꾼 오류(안쪽 단계에서 올라온 것)는 다시 감싸지 않는다.
     */
    private static OutOfMemoryError outOfMemory(String stage, String component, String activity, OutOfMemoryError cause) {
        if (cause instanceof AnalysisOutOfMemoryError) {
            return cause;
        }
        try {
            return new AnalysisOutOfMemoryError(stage, component, activity, cause);
        } catch (OutOfMemoryError again) {
            return cause;
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
