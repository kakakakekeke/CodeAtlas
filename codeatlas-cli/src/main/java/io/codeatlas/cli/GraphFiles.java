package io.codeatlas.cli;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.pipeline.Pipeline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 저장된 그래프({@code graph.json})를 찾아 읽는다. 조회용 하위 명령이 함께 쓴다. */
final class GraphFiles {

    private GraphFiles() {
    }

    /**
     * 그래프 파일 경로를 정한다. 폴더를 주면 그 안의 {@code graph.json}이다.
     *
     * @param location graph.json 또는 analyze 출력 폴더. null이면 현재 폴더의 {@code codeatlas-out}
     * @throws CommandFailure 파일이 없으면
     */
    static Path locate(String location) {
        Path file = Path.of(location == null ? AnalyzeCommand.DEFAULT_OUTPUT_DIR : location).toAbsolutePath().normalize();
        if (Files.isDirectory(file)) {
            file = file.resolve(Pipeline.GRAPH_FILE);
        }
        if (!Files.isRegularFile(file)) {
            throw new CommandFailure("그래프 파일이 없다: " + file
                    + (location == null ? " (analyze를 먼저 실행하거나 graph.json 또는 출력 폴더의 경로를 준다)" : ""));
        }
        return file;
    }

    /** @throws CommandFailure 읽지 못하거나 graph.json 형식이 아니면 */
    static GraphModel read(Path file) {
        try {
            return GraphJson.read(file);
        } catch (IOException | RuntimeException e) {
            throw new CommandFailure("그래프 파일을 읽지 못했다: " + file + " (" + e.getMessage() + ")");
        }
    }
}
