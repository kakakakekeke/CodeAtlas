package io.codeatlas.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.Exporter;
import io.codeatlas.core.spi.Linker;
import io.codeatlas.core.spi.SourceFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineTest {

    @TempDir
    Path project;
    @TempDir
    Path out;

    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void files() throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/A.java"), "class A {}");
        Files.writeString(project.resolve("src/q.sql"), "select 1 from dual;");
        Files.writeString(project.resolve("notes.txt"), "memo");
    }

    private Analyzer analyzer(String name, int order, ThrowingConsumer body) {
        return new Analyzer() {
            @Override public String name() { return name; }
            @Override public int order() { return order; }
            @Override public void analyze(AnalysisContext ctx) throws Exception {
                log.add(name);
                body.accept(ctx);
            }
        };
    }

    private Linker linker(String name, int order, ThrowingConsumer body) {
        return new Linker() {
            @Override public String name() { return name; }
            @Override public int order() { return order; }
            @Override public void link(AnalysisContext ctx) throws Exception {
                log.add(name);
                body.accept(ctx);
            }
        };
    }

    interface ThrowingConsumer {
        void accept(AnalysisContext ctx) throws Exception;
    }

    @Test
    @DisplayName("F-001-AC10 스캔 → 분석기(order순) → 링커(order순) → 마무리 → 저장 → 출력기 순서로 실행한다")
    void runsInOrder() throws Exception {
        Exporter exporter = new Exporter() {
            @Override public String name() { return "exp"; }
            @Override public void export(GraphModel graph, Path outputDir, Map<String, String> options) throws Exception {
                log.add("exp graph.json=" + Files.exists(outputDir.resolve("graph.json")) + " opt=" + options.get("k")
                        + " danglingResolved=" + graph.node("java:type:Missing").isPresent());
                Files.writeString(outputDir.resolve("exported.txt"), "ok");
            }
        };
        Pipeline pipeline = new Pipeline(
                List.of(analyzer("a200", 200, ctx -> { }),
                        analyzer("a50", 50, ctx -> {
                            ctx.graph().declare(Node.of("java:type:A", NodeKind.JAVA_TYPE, "A"));
                            ctx.graph().addEdge(Edge.of("java:type:A", "java:type:Missing", EdgeKind.EXTENDS));
                        })),
                List.of(linker("l2", 2, ctx -> { }), linker("l1", 1, ctx -> { })),
                List.of(exporter));

        PipelineResult result = pipeline.run(project, AnalysisOptions.builder().extra("k", "v").build(), out);

        assertThat(log).containsExactly("a50", "a200", "l1", "l2", "exp graph.json=true opt=v danglingResolved=true");
        assertThat(result.graphFile()).isEqualTo(out.resolve("graph.json")).exists();
        assertThat(out.resolve("exported.txt")).exists();
        GraphModel graph = result.graph();
        assertThat(graph.nodes(NodeKind.FILE)).extracting(Node::id).containsExactlyInAnyOrder("file:src/A.java", "file:src/q.sql");
        assertThat(result.files().all()).extracting(SourceFile::relativePath).containsExactly("notes.txt", "src/A.java", "src/q.sql");
        assertThat(graph.edges("java:type:A", EdgeKind.EXTENDS, "java:type:Missing"))
                .singleElement().extracting(Edge::confidence).isEqualTo(Confidence.UNRESOLVED);
        assertThat(graph.project().name()).isEqualTo(project.getFileName().toString());
        assertThat(graph.project().analyzedAt()).isNotNull();

        GraphModel saved = GraphJson.read(result.graphFile());
        assertThat(saved.nodes()).containsExactlyInAnyOrderElementsOf(graph.nodes());
        assertThat(saved.edges()).containsExactlyInAnyOrderElementsOf(graph.edges());
    }

    @Test
    @DisplayName("F-001-AC11 META-INF/services에 등록한 분석기를 ServiceLoader로 찾는다")
    void discoversByServiceLoader() {
        Pipeline pipeline = Pipeline.discover();
        assertThat(pipeline.analyzers()).extracting(Analyzer::name).contains("discovered-test");
        assertThat(pipeline.analyze(project, AnalysisOptions.defaults()).graph().node("java:type:discovered.Marker")).isPresent();
    }

    @Test
    @DisplayName("F-001-AC12 분석기 하나가 예외를 던져도 ERROR 진단을 남기고 다음 분석기를 실행한다")
    void isolatesAnalyzerFailure() {
        Pipeline pipeline = new Pipeline(
                List.of(analyzer("boom", 10, ctx -> { throw new IllegalStateException("터짐"); }),
                        analyzer("deep", 15, ctx -> recurse(0)),
                        analyzer("ok", 20, ctx -> ctx.graph().declare(Node.of("java:type:Ok", NodeKind.JAVA_TYPE, "Ok")))),
                List.of(linker("badlink", 1, ctx -> { throw new java.io.IOException("링크 실패"); }),
                        linker("oklink", 2, ctx -> ctx.graph().declare(Node.of("java:type:Linked", NodeKind.JAVA_TYPE, "Linked")))),
                List.of());

        GraphModel graph = pipeline.analyze(project, AnalysisOptions.defaults()).graph();

        assertThat(log).containsExactly("boom", "deep", "ok", "badlink", "oklink");
        assertThat(graph.node("java:type:Ok")).isPresent();
        assertThat(graph.node("java:type:Linked")).isPresent();
        List<Diagnostic> errors = graph.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
        assertThat(errors).extracting(Diagnostic::code).containsExactly("ANALYZER_FAILED", "ANALYZER_FAILED", "LINKER_FAILED");
        assertThat(errors.get(0).message()).contains("boom").contains("터짐");
        assertThat(errors.get(1).message()).contains("deep").contains("StackOverflowError");
        assertThat(errors.get(2).message()).contains("badlink");
    }

    @Test
    @DisplayName("F-001-AC54 분석 중 OutOfMemoryError가 나면 어떤 분석기가 어떤 파일을 처리하던 중이었는지와 --exclude·-Xmx 안내를 담아 다시 던진다")
    void explainsOutOfMemory() {
        Pipeline pipeline = new Pipeline(
                List.of(analyzer("first", 10, ctx -> ctx.progress("src/A.java")),
                        analyzer("sql", 20, ctx -> {
                            assertThat(ctx.currentActivity()).isNull();   // 분석기가 바뀌면 앞 분석기의 진행 표시는 지워진다
                            ctx.progress("db/small.sql");
                            ctx.progress("db/data/insert-all.sql");
                            assertThat(ctx.currentActivity()).isEqualTo("db/data/insert-all.sql");
                            throw new OutOfMemoryError("Java heap space");
                        }),
                        analyzer("never", 30, ctx -> { })),
                List.of(), List.of());

        assertThatThrownBy(() -> pipeline.analyze(project, AnalysisOptions.defaults()))
                .isInstanceOf(OutOfMemoryError.class)
                .isInstanceOfSatisfying(AnalysisOutOfMemoryError.class, e -> {
                    assertThat(e.stage()).isEqualTo("분석기");
                    assertThat(e.component()).isEqualTo("sql");
                    assertThat(e.activity()).isEqualTo("db/data/insert-all.sql");
                    assertThat(e.getMessage()).contains("메모리 부족").contains("분석기 'sql'").contains("db/data/insert-all.sql")
                            .contains("--exclude").contains("-Xmx").doesNotContain("\n");
                    assertThat(e.getCause()).isInstanceOf(OutOfMemoryError.class).hasMessage("Java heap space");
                });
        assertThat(log).containsExactly("first", "sql");

        // 처리 중인 파일을 알리지 않은 분석기·링커도 이름은 알린다.
        Pipeline silent = new Pipeline(List.of(),
                List.of(linker("joins", 1, ctx -> { throw new OutOfMemoryError("GC overhead limit exceeded"); })), List.of());
        assertThatThrownBy(() -> silent.analyze(project, AnalysisOptions.defaults()))
                .isInstanceOfSatisfying(AnalysisOutOfMemoryError.class, e -> {
                    assertThat(e.stage()).isEqualTo("링커");
                    assertThat(e.component()).isEqualTo("joins");
                    assertThat(e.activity()).isNull();
                    assertThat(e.getMessage()).contains("링커 'joins'").contains("-Xmx");
                });

        // 훅을 구현하지 않은 컨텍스트(기존 구현체)는 그대로 컴파일되고 아무 일도 하지 않는다.
        AnalysisContext legacy = new AnalysisContext() {
            @Override public Path projectRoot() { return project; }
            @Override public io.codeatlas.core.spi.ProjectFiles files() { return io.codeatlas.core.spi.ProjectFiles.of(List.of()); }
            @Override public GraphModel graph() { return new GraphModel(); }
            @Override public AnalysisOptions options() { return AnalysisOptions.defaults(); }
            @Override public void addEmbedded(io.codeatlas.core.spi.EmbeddedSource source) { }
            @Override public List<io.codeatlas.core.spi.EmbeddedSource> embedded(String language) { return List.of(); }
        };
        legacy.progress("x");
        assertThat(legacy.currentActivity()).isNull();
    }

    private static int recurse(int n) {
        return recurse(n + 1) + 1;
    }

    @Test
    @DisplayName("F-001-AC12 출력기 실패도 격리하고 그 진단까지 graph.json에 남긴다")
    void isolatesExporterFailure() throws Exception {
        Exporter bad = new Exporter() {
            @Override public String name() { return "badexp"; }
            @Override public void export(GraphModel graph, Path outputDir, Map<String, String> options) { throw new IllegalStateException("x"); }
        };
        Exporter good = new Exporter() {
            @Override public String name() { return "goodexp"; }
            @Override public void export(GraphModel graph, Path outputDir, Map<String, String> options) { log.add("goodexp"); }
        };
        PipelineResult result = new Pipeline(List.of(), List.of(), List.of(bad, good)).run(project, AnalysisOptions.defaults(), out);

        assertThat(log).containsExactly("goodexp");
        assertThat(GraphJson.read(result.graphFile()).diagnostics()).extracting(Diagnostic::code).contains("EXPORTER_FAILED");
    }

    private Exporter exporter(String name, ThrowingExport body) {
        return new Exporter() {
            @Override public String name() { return name; }
            @Override public void export(GraphModel graph, Path outputDir, Map<String, String> options) throws Exception {
                log.add(name + " opt=" + options.get("k"));
                body.accept(graph, outputDir);
            }
        };
    }

    interface ThrowingExport {
        void accept(GraphModel graph, Path outputDir) throws Exception;
    }

    @Test
    @DisplayName("F-001-AC43 파이프라인이 analyze 때의 옵션을 그래프의 project().options()에 담아 graph.json에 저장한다")
    void storesOptionsInGraph() throws Exception {
        AnalysisOptions options = AnalysisOptions.builder().ddlPath(Path.of("db/ddl")).exclude("gen/**").extra("k", "v").build();
        Pipeline pipeline = new Pipeline(List.of(), List.of(), List.of());

        assertThat(pipeline.analyze(project, options).graph().project().options()).isEqualTo(options);
        PipelineResult result = pipeline.run(project, options, out);
        assertThat(GraphJson.read(result.graphFile()).project().options()).isEqualTo(options);
        assertThat(pipeline.analyze(project, null).graph().project().options()).isEqualTo(AnalysisOptions.defaults());
    }

    @Test
    @DisplayName("F-001-AC44 export는 분석 없이 저장된 그래프로 출력기만 이름순으로 실행하고 실패와 새 진단을 결과에 담는다")
    void exportRunsExportersOnly() throws Exception {
        GraphModel graph = new GraphModel();
        graph.declare(Node.of("java:type:A", NodeKind.JAVA_TYPE, "A"));
        graph.report(Diagnostic.info("NOTE", "분석 때 남은 진단", null));
        Pipeline pipeline = new Pipeline(
                List.of(analyzer("a", 1, ctx -> { })),
                List.of(linker("l", 1, ctx -> { })),
                List.of(exporter("z-ok", (g, dir) -> Files.writeString(dir.resolve("z.txt"), String.valueOf(g.nodeCount()))),
                        exporter("m-bad", (g, dir) -> { throw new IllegalStateException("터짐"); }),
                        exporter("a-warn", (g, dir) -> g.report(Diagnostic.warn("INVALID_OPTION", "값이 잘못됨", null)))));
        List<String> progress = new ArrayList<>();
        pipeline.onProgress(progress::add);
        Path target = out.resolve("deep/dir");

        ExportResult result = pipeline.export(graph, target, Map.of("k", "v"));

        assertThat(log).containsExactly("a-warn opt=v", "m-bad opt=v", "z-ok opt=v");
        assertThat(target.resolve("z.txt")).hasContent("1");
        assertThat(target.resolve("graph.json")).doesNotExist();
        assertThat(result.exporters()).containsExactly("a-warn", "m-bad", "z-ok");
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("INVALID_OPTION", "EXPORTER_FAILED");
        assertThat(result.failed()).isTrue();
        assertThat(result.failures()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(Severity.ERROR);
            assertThat(d.code()).isEqualTo("EXPORTER_FAILED");
            assertThat(d.message()).contains("m-bad").contains("터짐");
        });
        assertThat(graph.diagnostics()).extracting(Diagnostic::code).containsExactly("NOTE", "INVALID_OPTION", "EXPORTER_FAILED");
        assertThat(progress).hasSize(3).allMatch(message -> message.startsWith("출력기 "));

        log.clear();
        ExportResult clean = new Pipeline(List.of(), List.of(), List.of(exporter("only", (g, dir) -> { })))
                .export(new GraphModel(), target, null);
        assertThat(log).containsExactly("only opt=null");
        assertThat(clean.failed()).isFalse();
        assertThat(clean.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC44 만들지 못한 출력기는 SERVICE_LOAD_FAILED로 export 결과에 담기고 실패로 본다")
    void exportReportsServiceLoadFailure() throws Exception {
        Pipeline pipeline = Pipeline.discover(loaderWithBrokenExporter());
        GraphModel graph = new GraphModel();

        ExportResult result = pipeline.export(graph, out, Map.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.failures()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("SERVICE_LOAD_FAILED");
            assertThat(d.message()).contains("Exporter").contains("no.such.MissingExporter");
        });
        assertThat(graph.diagnostics()).extracting(Diagnostic::code).containsExactly("SERVICE_LOAD_FAILED");
    }

    @Test
    @DisplayName("F-001-AC45 withoutExporters는 분석기·링커는 그대로 두고 graph.json만 쓰며 출력기를 실행하지 않는다")
    void withoutExportersSkipsExporters() throws Exception {
        Pipeline full = new Pipeline(
                List.of(analyzer("a", 1, ctx -> ctx.graph().declare(Node.of("java:type:A", NodeKind.JAVA_TYPE, "A")))),
                List.of(linker("l", 1, ctx -> { })),
                List.of(exporter("exp", (g, dir) -> Files.writeString(dir.resolve("exported.txt"), "x"))));
        List<String> progress = new ArrayList<>();
        full.onProgress(progress::add);

        Pipeline pipeline = full.withoutExporters();
        PipelineResult result = pipeline.run(project, AnalysisOptions.defaults(), out);

        assertThat(pipeline.exporters()).isEmpty();
        assertThat(pipeline.analyzers()).extracting(Analyzer::name).containsExactly("a");
        assertThat(pipeline.linkers()).extracting(Linker::name).containsExactly("l");
        assertThat(full.exporters()).hasSize(1);
        assertThat(log).containsExactly("a", "l");
        assertThat(result.graphFile()).exists();
        assertThat(out.resolve("exported.txt")).doesNotExist();
        assertThat(GraphJson.read(result.graphFile()).node("java:type:A")).isPresent();
        assertThat(progress).isNotEmpty().noneMatch(message -> message.startsWith("출력기 "));

        // 출력기를 만들지 못한 것은 출력기를 쓰지 않는 실행에서는 진단으로 남기지 않는다. 분석기 탐색은 평소와 같다.
        Pipeline discovered = Pipeline.discover(loaderWithBrokenExporter());
        assertThat(discovered.analyze(project, AnalysisOptions.defaults()).graph().diagnostics())
                .extracting(Diagnostic::code).contains("SERVICE_LOAD_FAILED");
        Pipeline analysisOnly = discovered.withoutExporters();
        assertThat(analysisOnly.analyzers()).extracting(Analyzer::name).contains("discovered-test");
        assertThat(analysisOnly.analyze(project, AnalysisOptions.defaults()).graph().diagnostics())
                .extracting(Diagnostic::code).doesNotContain("SERVICE_LOAD_FAILED");
    }

    /** 없는 클래스를 출력기로 등록한 classpath. ServiceLoader가 그 구현체를 만들지 못한다. */
    private ClassLoader loaderWithBrokenExporter() throws Exception {
        Path services = Files.createDirectories(out.resolve("broken-cp/META-INF/services"));
        Files.writeString(services.resolve(Exporter.class.getName()), "no.such.MissingExporter\n");
        return new URLClassLoader(new URL[] {out.resolve("broken-cp").toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    @DisplayName("F-001-AC2 출력 폴더가 프로젝트 안에 있으면 스캔하지 않는다")
    void outputDirInsideProjectIsNotScanned() throws Exception {
        Path inside = project.resolve("codeatlas-out");
        Pipeline pipeline = new Pipeline(List.of(), List.of(), List.of());
        pipeline.run(project, AnalysisOptions.defaults(), inside);
        Files.writeString(inside.resolve("viewer.html"), "<html></html>");
        PipelineResult second = pipeline.run(project, AnalysisOptions.defaults(), inside);

        assertThat(second.files().all()).extracting(SourceFile::relativePath).containsExactly("notes.txt", "src/A.java", "src/q.sql");
    }
}
