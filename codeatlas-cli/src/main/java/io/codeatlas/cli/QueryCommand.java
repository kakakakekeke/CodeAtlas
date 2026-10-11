package io.codeatlas.cli;

import io.codeatlas.core.model.GraphModel;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 저장된 그래프를 읽어 텍스트 하나를 내는 조회용 하위 명령의 공통 뼈대(설계 4.5).
 *
 * <ul>
 * <li>그래프: 첫 위치 인자 또는 {@code --graph <graph.json 또는 출력 폴더>}. 없으면 현재 폴더의 {@code codeatlas-out}.
 *     위치 인자가 검색어인 명령({@link #queryLabel()})은 {@code --graph}로만 받는다.</li>
 * <li>결과: 표준 출력. {@code --out <파일>}을 주면 그 파일(UTF-8)에 쓴다.</li>
 * <li>종료 코드: 옵션이 잘못되면 2({@link UsageException}), 그래프를 읽지 못하거나 대상이 없으면 1({@link CommandFailure}).</li>
 * </ul>
 * 옵션은 그래프를 읽기 전에 모두 검사한다({@link #prepare}). 그래프를 읽은 뒤의 일은 {@link Query}가 한다.
 */
abstract class QueryCommand implements Command {

    static final String GRAPH_OPTION = "--graph";
    static final String OUT_OPTION = "--out";

    /** 공통 옵션 설명. 명령별 help 끝에 붙인다. */
    static final String COMMON_HELP = """
              --graph <경로>       graph.json 또는 analyze 출력 폴더. 기본값은 ./codeatlas-out
              --out <파일>         결과를 표준 출력 대신 이 파일(UTF-8)에 쓴다.
            """;

    /** 그래프를 읽은 뒤 결과 텍스트를 만드는 일. */
    @FunctionalInterface
    interface Query {
        /**
         * @param err 경고와 안내를 쓸 곳
         * @throws CommandFailure 대상이 없으면
         */
        String run(GraphModel graph, PrintStream err);
    }

    /** 값이 없는 옵션 이름. */
    abstract List<String> flags();

    /** 값이 있는 옵션 이름({@code --graph}, {@code --out} 제외). */
    abstract List<String> valueOptions();

    /** 위치 인자가 검색어(대상)인 명령이면 그 이름. 예: {@code 검색어}. null이면 위치 인자는 그래프 경로다. */
    String queryLabel() {
        return null;
    }

    /**
     * 옵션을 검사하고 그래프를 읽은 뒤 할 일을 돌려준다.
     *
     * @param query 검색어. {@link #queryLabel()}이 null이면 null
     * @throws UsageException 옵션 값이 잘못되면
     */
    abstract Query prepare(CommandLine line, String query);

    @Override
    public final int run(String[] args, PrintStream out, PrintStream err) {
        List<String> valueOptions = new ArrayList<>(valueOptions());
        valueOptions.add(GRAPH_OPTION);
        valueOptions.add(OUT_OPTION);
        CommandLine line = CommandLine.parse(args, flags(), valueOptions);
        if (line.help()) {
            out.print(Main.usage(this));
            return Main.EXIT_OK;
        }
        String query = null;
        String graphLocation = line.value(GRAPH_OPTION);
        List<String> positionals = line.positionals();
        if (queryLabel() != null) {
            if (positionals.isEmpty()) {
                throw new UsageException(queryLabel() + "를 줘야 한다");
            }
            if (positionals.size() > 1) {
                throw new UsageException(queryLabel() + "는 하나만 줄 수 있다(공백이 있으면 따옴표로 묶는다): " + positionals.get(1));
            }
            query = positionals.get(0);
        } else if (!positionals.isEmpty()) {
            if (positionals.size() > 1 || graphLocation != null) {
                throw new UsageException("그래프 경로는 하나만 줄 수 있다: " + positionals.get(positionals.size() - 1));
            }
            graphLocation = positionals.get(0);
        }
        Query prepared = prepare(line, query);

        GraphModel graph = GraphFiles.read(GraphFiles.locate(graphLocation));
        String text = prepared.run(graph, err);

        String target = line.value(OUT_OPTION);
        if (target == null) {
            out.print(text);
            out.flush();
        } else {
            Path file = Path.of(target).toAbsolutePath().normalize();
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CommandFailure("결과 파일을 쓰지 못했다: " + file + " (" + e.getMessage() + ")");
            }
            err.println("[codeatlas] 저장: " + file);
        }
        return Main.EXIT_OK;
    }
}
