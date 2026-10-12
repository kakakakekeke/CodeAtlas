package io.codeatlas.viewer;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.Exporter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 그래프를 내장한 자기완결형 단일 HTML 뷰어({@code viewer.html})를 만든다(F-016).
 *
 * <p>스크립트, 스타일, 다이어그램 라이브러리(Mermaid)를 모두 파일 안에 넣으므로 뷰어는 실행 중에 네트워크를 쓰지 않는다.
 * 그래프는 {@code graph.json}(설계 4.4)과 같은 내용을 반복 없이 적은 뷰어 내장 형식({@link CompactGraphJson}, F-016-R49)으로
 * 바꾸고 gzip으로 압축해 넣는다(F-016-R48).
 */
public final class ViewerExporter implements Exporter {

    /** 출력 폴더에 만드는 파일 이름. */
    public static final String FILE_NAME = "viewer.html";

    @Override
    public String name() {
        return "viewer";
    }

    @Override
    public void export(GraphModel graph, Path outputDir, Map<String, String> options) throws IOException {
        Files.createDirectories(outputDir);
        Path target = outputDir.resolve(FILE_NAME);
        try (Writer out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            ViewerHtml.write(out, data -> CompactGraphJson.write(graph, data));
        }
    }
}
