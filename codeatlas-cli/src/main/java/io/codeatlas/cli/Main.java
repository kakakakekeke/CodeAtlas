package io.codeatlas.cli;

import io.codeatlas.core.pipeline.AnalysisOutOfMemoryError;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 명령줄 진입점. 종료 코드: 0 성공, 1 실행 실패, 2 사용법 오류, 3 자원 부족(메모리). */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;
    /** 자원 부족: 힙이 모자라 끝내지 못했다. */
    static final int EXIT_RESOURCE = 3;

    /** 하위 명령 레지스트리. 등록한 순서대로 사용법에 나온다. 새 명령은 여기에 더한다. */
    private static final Map<String, Command> COMMANDS = new LinkedHashMap<>();

    static {
        register(new AnalyzeCommand());
        register(new SummaryCommand());
        register(new ErdCommand());
        register(new ClassDiagramCommand());
        register(new CallTreeCommand());
        register(new ImpactCommand());
        register(new CrudCommand());
        register(new FindCommand());
        register(new ReportCommand());
        register(new ExportCommand());
    }

    private Main() {
    }

    private static void register(Command command) {
        if (COMMANDS.putIfAbsent(command.name(), command) != null) {
            throw new IllegalStateException("명령 이름이 겹친다: " + command.name());
        }
    }

    /** 등록된 하위 명령(등록한 순서). */
    static List<Command> commands() {
        return List.copyOf(COMMANDS.values());
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** 명령을 실행하고 종료 코드를 돌려준다. 테스트에서 직접 부를 수 있게 System.exit을 하지 않는다. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.print(usage());
            return EXIT_USAGE;
        }
        String name = args[0];
        switch (name) {
            case "--help", "-h" -> {
                out.print(usage());
                return EXIT_OK;
            }
            case "help" -> {
                if (args.length == 1) {
                    out.print(usage());
                    return EXIT_OK;
                }
                Command command = COMMANDS.get(args[1]);
                if (command == null) {
                    return usageError("알 수 없는 명령: " + args[1], null, err);
                }
                out.print(usage(command));
                return EXIT_OK;
            }
            case "--version", "-V", "version" -> {
                out.println("codeatlas " + version());
                return EXIT_OK;
            }
            default -> {
                Command command = COMMANDS.get(name);
                if (command == null) {
                    return usageError("알 수 없는 명령: " + name, null, err);
                }
                return execute(command, Arrays.copyOfRange(args, 1, args.length), out, err);
            }
        }
    }

    /** 명령 하나를 실행하고 명령이 던진 것을 종료 코드로 바꾼다. */
    static int execute(Command command, String[] args, PrintStream out, PrintStream err) {
        try {
            return command.run(args, out, err);
        } catch (UsageException e) {
            return usageError(e.getMessage(), command, err);
        } catch (CommandFailure e) {
            out.flush();
            err.println("오류: " + e.getMessage());
            return EXIT_FAILED;
        } catch (AnalysisOutOfMemoryError e) {
            // 파이프라인이 어느 단계의 무엇을 처리하던 중이었는지와 대처 방법을 한 줄로 담아 준다(설계 4.1).
            return outOfMemory(e.getMessage(), out, err);
        } catch (OutOfMemoryError e) {
            return outOfMemory("메모리 부족: 힙이 모자랐다(" + e.getMessage() + "). JVM 옵션 -Xmx로 힙을 늘려 다시 실행한다(예: -Xmx8g).", out, err);
        }
    }

    /** 스택 대신 안내 두 줄을 표준 오류에 내고 종료 코드 3을 돌려준다. 힙이 모자란 상태라 일을 더 하지 않는다. */
    private static int outOfMemory(String message, PrintStream out, PrintStream err) {
        out.flush();
        err.println("오류: " + message);
        err.println("  JVM 옵션은 JAVA_OPTS 환경 변수로 준다. 예: JAVA_OPTS=-Xmx8g codeatlas analyze <projectDir> (java -jar로 실행하면 java -Xmx8g -jar codeatlas.jar ...)");
        return EXIT_RESOURCE;
    }

    /** 사용법 오류를 알린다. 명령을 알면 그 명령의 사용법 항목만, 모르면 전체 사용법을 붙인다. */
    private static int usageError(String message, Command command, PrintStream err) {
        err.println("오류: " + message);
        err.println();
        if (command == null) {
            err.print(usage());
        } else {
            err.print(synopsisOf(command));
            err.println();
            err.println("옵션 설명: codeatlas help " + command.name());
        }
        return EXIT_USAGE;
    }

    /** 사용법 전체: 등록된 명령들의 사용법 항목. 옵션 설명은 {@code help <명령>}으로 본다. */
    static String usage() {
        StringBuilder sb = new StringBuilder("사용법:\n");
        for (Command command : COMMANDS.values()) {
            sb.append("  ").append(command.synopsis().replace("\n", "\n  ")).append('\n');
        }
        sb.append("  codeatlas help [<명령>] | --help | --version\n");
        sb.append("\n명령별 옵션 설명: codeatlas help <명령> (또는 codeatlas <명령> --help)\n");
        return sb.toString();
    }

    /** 명령 하나의 사용법 항목과 옵션 설명. {@code help <명령>}과 {@code <명령> --help}가 찍는다. */
    static String usage(Command command) {
        String help = command.help();
        return synopsisOf(command) + (help.isEmpty() ? "" : "\n" + help);
    }

    private static String synopsisOf(Command command) {
        return "사용법:\n  " + command.synopsis().replace("\n", "\n  ") + "\n";
    }

    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
