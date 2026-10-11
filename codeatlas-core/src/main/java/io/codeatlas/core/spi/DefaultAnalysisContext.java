package io.codeatlas.core.spi;

import io.codeatlas.core.model.GraphModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link AnalysisContext}의 기본 구현. */
final class DefaultAnalysisContext implements AnalysisContext {

    private final Path projectRoot;
    private final ProjectFiles files;
    private final GraphModel graph;
    private final AnalysisOptions options;
    private final List<EmbeddedSource> embedded = new CopyOnWriteArrayList<>();
    /** 여러 스레드가 덮어쓴다. 마지막에 쓴 값 하나만 본다. */
    private volatile String activity;

    DefaultAnalysisContext(Path projectRoot, ProjectFiles files, GraphModel graph, AnalysisOptions options) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.files = Objects.requireNonNull(files, "files");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.options = options == null ? AnalysisOptions.defaults() : options;
    }

    @Override
    public Path projectRoot() {
        return projectRoot;
    }

    @Override
    public ProjectFiles files() {
        return files;
    }

    @Override
    public GraphModel graph() {
        return graph;
    }

    @Override
    public AnalysisOptions options() {
        return options;
    }

    @Override
    public void addEmbedded(EmbeddedSource source) {
        embedded.add(Objects.requireNonNull(source, "source"));
    }

    @Override
    public List<EmbeddedSource> embedded(String language) {
        return embedded.stream().filter(s -> s.language().equals(language)).toList();
    }

    @Override
    public void progress(String activity) {
        this.activity = activity;
    }

    @Override
    public String currentActivity() {
        return activity;
    }
}
