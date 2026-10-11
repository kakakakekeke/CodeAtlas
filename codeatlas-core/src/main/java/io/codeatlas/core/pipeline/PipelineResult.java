package io.codeatlas.core.pipeline;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.ProjectFiles;
import java.nio.file.Path;

/**
 * 파이프라인 실행 결과.
 *
 * @param context 분석에 쓴 컨텍스트
 * @param graphFile 저장한 graph.json
 */
public record PipelineResult(AnalysisContext context, Path graphFile) {

    public GraphModel graph() {
        return context.graph();
    }

    public ProjectFiles files() {
        return context.files();
    }
}
