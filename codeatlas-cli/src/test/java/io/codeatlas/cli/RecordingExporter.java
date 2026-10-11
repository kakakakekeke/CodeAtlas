package io.codeatlas.cli;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.Exporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** CLI 테스트용 출력기. 받은 옵션을 기록하고 출력 폴더에 표시 파일을 남긴다. */
public class RecordingExporter implements Exporter {

    static volatile Map<String, String> lastOptions;

    @Override
    public String name() {
        return "cli-test-exporter";
    }

    @Override
    public void export(GraphModel graph, Path outputDir, Map<String, String> options) throws Exception {
        lastOptions = options;
        Files.writeString(outputDir.resolve("cli-test-exporter.txt"), "nodes=" + graph.nodeCount());
    }
}
