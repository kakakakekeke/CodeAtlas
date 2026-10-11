package io.codeatlas.cli;

import io.codeatlas.core.pipeline.Pipeline;
import io.codeatlas.core.pipeline.PipelineResult;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code analyze} 명령(설계 4.5). */
final class AnalyzeCommand implements Command {

    static final String DEFAULT_OUTPUT_DIR = "codeatlas-out";
    static final String DIAGNOSTICS_FILE = "diagnostics.md";
    /** {@code --exclude-tests}가 더하는 제외 패턴. 스캐너는 앞에 경로가 없는 패턴을 어느 깊이에서든 맞춘다(설계 4.2). */
    static final String TEST_SOURCES_GLOB = "src/test";

    /**
     * 해석한 인자.
     *
     * @param noExport true면 출력기를 실행하지 않는다
     * @param quiet true면 진행 메시지와 요약을 찍지 않는다
     */
    record Arguments(Path projectDir, Path outputDir, AnalysisOptions options, boolean help, boolean noExport, boolean quiet) {
    }

    @Override
    public String name() {
        return "analyze";
    }

    @Override
    public String synopsis() {
        return """
                codeatlas analyze <projectDir> [--ddl <파일 또는 폴더>]... [--classpath <jar 또는 폴더>]...
                                  [--out <dir>] [--encoding <이름>] [--dialect oracle|db2|postgres|auto] [--exclude <glob>]...
                                  [--exclude-tests] [--opt <key>=<value>]... [--no-export] [--quiet]""";
    }

    @Override
    public String help() {
        return """
                analyze 옵션:
                  --ddl <경로>         DDL 파일 또는 폴더. 여러 번 줄 수 있다. 프로젝트 밖의 파일은 ddl/<파일명>으로 다룬다.
                  --classpath <경로>   타입 해석에 쓸 jar 또는 폴더. 폴더를 주면 클래스 폴더로 쓰고 그 바로 아래의 jar도 함께 쓴다
                                       ('<폴더>/*'로 적어도 된다). 여러 번 주거나 경로 구분자로 이어 줄 수 있다.
                  --out <dir>          출력 폴더. 기본값은 ./codeatlas-out
                  --encoding <이름>    소스 인코딩. 주지 않으면 파일마다 UTF-8, MS949 순으로 판별한다.
                  --dialect <이름>     SQL 방언. oracle, db2, postgres(별칭 postgresql, pgsql), auto(기본값). 방언별 매퍼·DDL이 여러 벌이면
                                       어느 것을 쓸지도 이 값으로 고른다. 주지 않으면 프로젝트 설정이 가리키는 대상 방언(oracle, db2, postgres)
                                       → Oracle → DB2 → PostgreSQL → 그 계열(Tibero, Altibase) 순으로 고르고, 대상 방언 파일이 없을 때만
                                       그 밖의 방언을 쓴다. 그 밖의 방언(mysql 등)으로 분석하려면
                                       --opt mybatis.dialectVariant=<방언>을 준다(매퍼와 DDL 선택이 둘 다 이 값을 읽는다).
                  --exclude <glob>     분석에서 뺄 경로 패턴. 여러 번 줄 수 있다. 예: 'db/sample-data/**', '*.min.js'
                  --exclude-tests      테스트 소스를 뺀다. --exclude src/test 와 같다(어느 깊이의 src/test 폴더든). 기본은 포함이다.
                  --opt <key>=<value>  분석기, 링커, 출력기에 그대로 전달하는 추가 옵션. 여러 번 줄 수 있다. 예: --opt export.calltree.depth=5
                                       graph.json에 저장되어 export 명령이 이어받는다. 키 목록은 README의 "주요 옵션"에 있다.
                  --no-export          출력기를 실행하지 않는다. graph.json과 diagnostics.md만 쓴다.
                  --quiet              진행 메시지, 요약, 힌트를 찍지 않는다. 오류는 표준 오류에 나온다.

                출력 폴더에 graph.json과 diagnostics.md를 쓰고, 등록된 출력기를 모두 실행한다. 요약 끝에는 진단과 그래프에서 읽은
                힌트(방언별 매퍼, 많은 미해결 호출, 중복 DDL, 건너뛴 벤더 파일 등)가 해당할 때만 한 줄씩 붙는다.
                종료 코드: 0 성공(진단이 있어도), 1 실행 실패, 2 사용법 오류, 3 메모리 부족(JAVA_OPTS=-Xmx8g처럼 힙을 늘리거나
                큰 데이터 스크립트를 --exclude로 뺀다).
                """;
    }

    @Override
    public int run(String[] args, PrintStream out, PrintStream err) {
        Arguments arguments = parse(args);
        if (arguments.help()) {
            out.print(Main.usage(this));
            return Main.EXIT_OK;
        }
        return execute(arguments, out, err);
    }

    /**
     * {@code analyze} 뒤의 인자를 해석한다. 값이 있는 옵션은 {@code --opt 값}과 {@code --opt=값} 둘 다 받는다.
     *
     * @throws UsageException 인자가 잘못되면
     */
    static Arguments parse(String[] args) {
        Path projectDir = null;
        Path outputDir = Path.of(DEFAULT_OUTPUT_DIR);
        AnalysisOptions.Builder options = AnalysisOptions.builder();
        boolean help = false;
        boolean noExport = false;
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                help = true;
                continue;
            }
            if (arg.equals("--no-export")) {
                noExport = true;
                continue;
            }
            if (arg.equals("--quiet")) {
                quiet = true;
                continue;
            }
            if (arg.equals("--exclude-tests")) {
                options.exclude(TEST_SOURCES_GLOB);
                continue;
            }
            if (!arg.startsWith("--")) {
                if (projectDir != null) {
                    throw new UsageException("프로젝트 폴더는 하나만 줄 수 있다: " + arg);
                }
                projectDir = Path.of(arg);
                continue;
            }
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            switch (name) {
                case "--ddl", "--classpath", "--out", "--encoding", "--dialect", "--exclude", "--opt" -> {
                    if (value == null) {
                        if (i + 1 >= args.length) {
                            throw new UsageException(name + " 옵션에 값이 없다");
                        }
                        value = args[++i];
                    }
                }
                default -> throw new UsageException("알 수 없는 옵션: " + name);
            }
            switch (name) {
                case "--ddl" -> options.ddlPath(Path.of(value));
                case "--classpath" -> {
                    for (String entry : value.split(File.pathSeparator)) {
                        if (!entry.isBlank()) {
                            options.classpath(Path.of(entry));
                        }
                    }
                }
                case "--out" -> outputDir = Path.of(value);
                case "--encoding" -> {
                    try {
                        options.encoding(Charset.forName(value));
                    } catch (IllegalArgumentException e) {
                        throw new UsageException("알 수 없는 인코딩: " + value);
                    }
                }
                case "--dialect" -> {
                    try {
                        options.dialect(Dialect.parse(value));
                    } catch (IllegalArgumentException e) {
                        throw new UsageException("알 수 없는 방언: " + value + " (oracle, db2, postgres, auto 중 하나). 그 밖의 방언으로 분석하려면 "
                                + "--opt mybatis.dialectVariant=<방언>을 쓴다");
                    }
                }
                case "--exclude" -> options.exclude(value);
                case "--opt" -> {
                    // 첫 '='까지가 키다. 값에는 '='가 들어갈 수 있고 비어 있어도 된다.
                    int separator = value.indexOf('=');
                    if (separator <= 0) {
                        throw new UsageException("--opt 값은 <key>=<value> 형태여야 한다: " + value);
                    }
                    options.extra(value.substring(0, separator), value.substring(separator + 1));
                }
                default -> throw new IllegalStateException(name);
            }
        }
        if (projectDir == null && !help) {
            throw new UsageException("프로젝트 폴더를 줘야 한다");
        }
        return new Arguments(projectDir, outputDir, options.build(), help, noExport, quiet);
    }

    static int execute(Arguments arguments, PrintStream out, PrintStream err) {
        Path root = arguments.projectDir().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            err.println("오류: 프로젝트 폴더가 없거나 폴더가 아니다: " + root);
            return Main.EXIT_FAILED;
        }
        Path outputDir = arguments.outputDir().toAbsolutePath().normalize();
        try {
            Pipeline pipeline = arguments.noExport() ? Pipeline.discover().withoutExporters() : Pipeline.discover();
            if (!arguments.quiet()) {
                pipeline.onProgress(message -> err.println("[codeatlas] " + message));
            }
            PipelineResult result = pipeline.run(root, arguments.options(), outputDir);
            Path diagnosticsFile = outputDir.resolve(DIAGNOSTICS_FILE);
            Files.writeString(diagnosticsFile, Reports.diagnosticsMarkdown(result.graph()));
            if (!arguments.quiet()) {
                out.print(Reports.summary(result, diagnosticsFile));
                out.flush();
            }
            return Main.EXIT_OK;
        } catch (IOException | UncheckedIOException e) {
            err.println("오류: 분석을 끝내지 못했다: " + e.getMessage());
            return Main.EXIT_FAILED;
        }
    }
}
