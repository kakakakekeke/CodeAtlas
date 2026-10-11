package io.codeatlas.cli;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.pipeline.ExportResult;
import io.codeatlas.core.pipeline.Pipeline;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code export} 명령(설계 4.5): 분석을 다시 하지 않고 저장된 그래프로 등록된 출력기를 다시 실행한다({@code Pipeline.export}).
 * 출력기 옵션({@code --opt})만 바꿔 산출물을 다시 만들 때 쓴다. analyze 때의 {@code --opt}는 graph.json에서 이어받는다.
 */
final class ExportCommand implements Command {

    @Override
    public String name() {
        return "export";
    }

    @Override
    public String synopsis() {
        return """
                codeatlas export [<graph.json 또는 출력 폴더>] --out <dir> [--opt <key>=<value>]... [--quiet] [--graph <경로>]""";
    }

    @Override
    public String help() {
        return """
                export: 분석을 다시 하지 않고 저장된 그래프로 등록된 출력기(표준 산출물, viewer.html)를 다시 실행한다.
                  --out <dir>          산출물을 쓸 폴더(필수). 그래프가 있는 폴더를 그대로 줘도 된다. 그때는 graph.json과
                                       diagnostics.md를 건드리지 않고 산출물만 덮어쓴다. 다른 폴더면 graph.json과 diagnostics.md도 쓴다.
                  --opt <key>=<value>  출력기에 전달하는 옵션. 여러 번 줄 수 있다. analyze 때 준 --opt는 graph.json에 저장되어 있어
                                       그대로 이어받고, 여기서 같은 키를 주면 그 값으로 덮어쓴다(graph.json의 값은 바꾸지 않는다).
                                       예: --opt export.erd.columns=keys --opt export.calltree.depth=12 --opt export.crud=false
                  --quiet              진행 메시지를 찍지 않는다.
                  --graph <경로>       graph.json 또는 analyze 출력 폴더. 기본값은 ./codeatlas-out
                """;
    }

    @Override
    public int run(String[] args, PrintStream out, PrintStream err) {
        CommandLine line = CommandLine.parse(args, List.of("--quiet"), List.of("--out", "--opt", QueryCommand.GRAPH_OPTION));
        if (line.help()) {
            out.print(Main.usage(this));
            return Main.EXIT_OK;
        }
        String graphLocation = line.value(QueryCommand.GRAPH_OPTION);
        List<String> positionals = line.positionals();
        if (!positionals.isEmpty()) {
            if (positionals.size() > 1 || graphLocation != null) {
                throw new UsageException("그래프 경로는 하나만 줄 수 있다: " + positionals.get(positionals.size() - 1));
            }
            graphLocation = positionals.get(0);
        }
        if (line.value("--out") == null) {
            throw new UsageException("--out <dir>을 줘야 한다");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (String option : line.values("--opt")) {
            int separator = option.indexOf('=');
            if (separator <= 0) {
                throw new UsageException("--opt 값은 <key>=<value> 형태여야 한다: " + option);
            }
            options.put(option.substring(0, separator), option.substring(separator + 1));
        }
        boolean quiet = line.has("--quiet");

        Path graphFile = GraphFiles.locate(graphLocation);
        GraphModel graph = GraphFiles.read(graphFile);
        // analyze 때의 --opt(graph.json의 project.options.extra)가 기본이고 이 명령의 --opt가 덮어쓴다.
        Map<String, String> merged = new LinkedHashMap<>();
        if (graph.project() != null) {
            merged.putAll(graph.project().options().extra());
        }
        merged.putAll(options);

        Pipeline pipeline = Pipeline.discover();
        if (!quiet) {
            pipeline.onProgress(message -> err.println("[codeatlas] " + message));
        }
        Path outputDir = Path.of(line.value("--out")).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputDir);
            Path targetGraph = outputDir.resolve(Pipeline.GRAPH_FILE);
            // 그래프가 있는 폴더에 그대로 쓰면 graph.json과 diagnostics.md는 analyze가 쓴 그대로 둔다.
            boolean inPlace = Files.exists(targetGraph) && Files.isSameFile(targetGraph, graphFile);
            if (!inPlace) {
                GraphJson.write(graph, targetGraph);
            }
            ExportResult result = pipeline.export(graph, outputDir, merged);
            // 출력기가 남긴 진단(잘못된 옵션 값 등)은 조용히 넘기지 않는다.
            for (Diagnostic diagnostic : result.diagnostics()) {
                err.println(diagnostic.severity() + " " + diagnostic.code() + ": " + diagnostic.message());
            }
            if (!inPlace) {
                if (!result.diagnostics().isEmpty()) {
                    GraphJson.write(graph, targetGraph);
                }
                Files.writeString(outputDir.resolve(AnalyzeCommand.DIAGNOSTICS_FILE), Reports.diagnosticsMarkdown(graph));
            }
            if (result.failed()) {
                err.println("오류: 출력기 " + result.failures().size() + "개가 실패했다");
                return Main.EXIT_FAILED;
            }
            if (result.exporters().isEmpty()) {
                throw new CommandFailure("등록된 출력기가 없다");
            }
            if (!quiet) {
                out.println("산출물을 다시 만들었다: " + outputDir + " (출력기 " + result.exporters().size() + "개)");
                out.flush();
            }
            return Main.EXIT_OK;
        } catch (IOException e) {
            throw new CommandFailure("출력 폴더에 쓰지 못했다: " + outputDir + " (" + e.getMessage() + ")");
        }
    }
}
