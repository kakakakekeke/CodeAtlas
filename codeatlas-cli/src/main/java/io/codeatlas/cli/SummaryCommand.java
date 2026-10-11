package io.codeatlas.cli;

import io.codeatlas.core.model.GraphModel;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code summary} 명령(설계 4.5): 저장된 graph.json의 요약을 다시 찍는다. */
final class SummaryCommand implements Command {

    @Override
    public String name() {
        return "summary";
    }

    @Override
    public String synopsis() {
        return "codeatlas summary <graph.json>";
    }

    @Override
    public String help() {
        return """
                summary:
                  저장된 graph.json(또는 그 파일이 든 출력 폴더)을 읽어 analyze가 끝에 찍는 것과 같은 요약을 표준 출력에 찍는다.
                  파일 수는 그래프의 FILE 노드로 세므로 FILE 노드를 만들지 않는 종류(OTHER, JAR)는 빠진다.
                """;
    }

    @Override
    public int run(String[] args, PrintStream out, PrintStream err) {
        Path target = null;
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                out.print(Main.usage(this));
                return Main.EXIT_OK;
            }
            if (arg.startsWith("--")) {
                throw new UsageException("알 수 없는 옵션: " + arg);
            }
            if (target != null) {
                throw new UsageException("graph.json은 하나만 줄 수 있다: " + arg);
            }
            target = Path.of(arg);
        }
        if (target == null) {
            throw new UsageException("graph.json 경로를 줘야 한다");
        }
        Path graphFile = GraphFiles.locate(target.toString());
        GraphModel graph = GraphFiles.read(graphFile);
        Path diagnosticsFile = graphFile.resolveSibling(AnalyzeCommand.DIAGNOSTICS_FILE);
        out.print(Reports.summary(graph, graphFile, Files.isRegularFile(diagnosticsFile) ? diagnosticsFile : null));
        out.flush();
        return Main.EXIT_OK;
    }
}
