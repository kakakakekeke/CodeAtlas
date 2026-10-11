package io.codeatlas.core.spi;

import io.codeatlas.core.model.GraphModel;
import java.nio.file.Path;
import java.util.Map;

/**
 * 출력기. 완성된 그래프를 읽어 출력 폴더에 파일을 만든다.
 * {@code META-INF/services/io.codeatlas.core.spi.Exporter}에 등록한다.
 */
public interface Exporter {

    String name();

    /**
     * @param outputDir 출력 폴더. 이미 만들어져 있고 graph.json이 들어 있다
     * @param options {@link AnalysisOptions#extra()}
     */
    void export(GraphModel graph, Path outputDir, Map<String, String> options) throws Exception;
}
