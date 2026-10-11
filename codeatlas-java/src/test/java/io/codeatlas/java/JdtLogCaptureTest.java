package io.codeatlas.java;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.codeatlas.java.Fx.hasEdge;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-002-R18, R19: JDT가 내부 오류를 표준 오류에 직접 쓰는 것(headless에서 {@code Util.log}가 {@code System.err}로 떨어진다)을 가로채
 * 그 파일의 WARN 진단으로 바꾸고, 그 여파로 일괄 파싱에서 떨어진 파일은 따로 다시 파싱해 건진다.
 *
 * <p>재현(jeesite-v1의 {@code JaxbMapper} → {@code Exceptions}와 같은 꼴): jar의 타입이 classpath에 없는 타입을 참조하고,
 * 아직 컴파일러가 보지 않은 프로젝트 타입 B의 메서드 시그니처에 그 없는 타입이 있을 때, 먼저 분석하는 파일 A가 B의 메서드 목록을
 * 물으면 JDT가 {@code AbortCompilation}을 내고 "Status ERROR: org.eclipse.jdt.core …"를 찍는다.
 */
class JdtLogCaptureTest {

    /** {@code lib2.Holder}는 있고 그 메서드가 참조하는 {@code missing.Req}는 없는 클래스 폴더. */
    private static Path brokenLibrary() {
        Path classes = Fx.compile(Fx.files(
                "missing/Req.java", "package missing; public class Req { }",
                "lib2/Holder.java", "package lib2; public class Holder { public void keep(missing.Req request) { } }"));
        try {
            Files.delete(classes.resolve("missing/Req.class"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return classes;
    }

    private static final String[] SOURCES = {
            "src/com/a/A.java", """
                    package com.a;
                    import lib2.Holder;
                    import other.Unknown;
                    public class A {
                        Holder holder;
                        void run(Unknown unknown) {
                            try {
                                helper();
                            } catch (Exception e) {
                                throw B.wrap(unknown.cause());
                            }
                        }
                        void helper() { }
                    }
                    """,
            "src/com/a/B.java", """
                    package com.a;
                    import missing.Req;
                    public class B {
                        public static RuntimeException wrap(Exception e) { return new RuntimeException(e); }
                        public static Object request(Req request) { return describe(request); }
                        static Object describe(Object value) { return value; }
                    }
                    """,
    };

    private record Run(GraphModel graph, String stderr) {
    }

    /** 표준 오류를 버퍼로 바꿔 놓고 분석한다. */
    private static Run analyzeCapturingStderr() {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream replaced = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setErr(replaced);
            GraphModel g = Fx.analyze(AnalysisOptions.builder().classpath(brokenLibrary()).build(), Fx.files(SOURCES));
            // 분석이 끝나면 표준 오류는 분석 전의 것으로 돌아와 있다
            assertThat(System.err).isSameAs(replaced);
            System.err.println("다른 출력은 그대로 나간다");
            return new Run(g, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(original);
        }
    }

    @Test
    @DisplayName("F-002-AC23 JDT가 표준 오류에 쓰는 내부 로그(Status ERROR … AbortCompilation)는 표준 오류에 내지 않고 분석 중이던 파일의 WARN 진단으로 남긴다")
    void jdtInternalLogBecomesDiagnostic() {
        Run run = analyzeCapturingStderr();

        assertThat(run.stderr()).doesNotContain("Status ERROR").doesNotContain("org.eclipse.jdt");
        assertThat(run.stderr()).contains("다른 출력은 그대로 나간다");

        List<Diagnostic> warnings = run.graph().diagnostics().stream()
                .filter(d -> d.code().equals("PARSE_FAILED") && d.message().contains("missing.Req")).toList();
        assertThat(warnings).hasSize(1);
        Diagnostic warning = warnings.get(0);
        assertThat(warning.severity()).isEqualTo(Severity.WARN);
        assertThat(warning.location().file()).isEqualTo("src/com/a/A.java");
        assertThat(warning.message()).doesNotContain("Status ERROR").doesNotContain("org.eclipse.jdt").contains("classpath");
    }

    @Test
    @DisplayName("F-002-AC24 그 여파로 일괄 파싱에서 예외로 떨어진 파일은 따로 다시 파싱해 구조와 호출을 남긴다(ERROR 진단 없음)")
    void fileDroppedFromBatchIsParsedAlone() {
        GraphModel g = analyzeCapturingStderr().graph();

        assertThat(g.diagnostics()).noneMatch(d -> d.severity() == Severity.ERROR);
        // 먼저 분석한 파일의 결과는 그대로다
        String run = Ids.javaMethod("com.a.A", "run", "other.Unknown");
        assertThat(hasEdge(g, run, EdgeKind.CALLS, Ids.javaMethod("com.a.A", "helper"))).isTrue();
        assertThat(hasEdge(g, run, EdgeKind.CALLS, Ids.javaMethod("com.a.B", "wrap", "java.lang.Exception"))).isTrue();
        // 떨어졌던 파일의 타입·메서드·호출이 있다
        assertThat(Fx.node(g, Ids.javaType("com.a.B")).declared()).isTrue();
        String request = Ids.javaMethod("com.a.B", "request", "missing.Req");
        assertThat(Fx.node(g, request).declared()).isTrue();
        assertThat(Fx.node(g, Ids.javaMethod("com.a.B", "wrap", "java.lang.Exception")).declared()).isTrue();
        assertThat(hasEdge(g, request, EdgeKind.CALLS, Ids.javaMethod("com.a.B", "describe", "java.lang.Object"))).isTrue();
    }

    @Test
    @DisplayName("F-002-AC23 JDT 로그가 없는 분석에서는 진단을 더하지 않고 표준 오류도 건드리지 않는다")
    void noLogNoDiagnostic() {
        PrintStream original = System.err;
        GraphModel g = Fx.analyze("src/com/a/Plain.java", "package com.a; public class Plain { void f() { } }");

        assertThat(System.err).isSameAs(original);
        assertThat(Fx.diagnosticCodes(g)).doesNotContain("PARSE_FAILED");
    }

    @Test
    @DisplayName("F-002-AC23 가로채는 동안에도 JDT 로그가 아닌 표준 오류 출력은 그대로 나가고, 가로채기가 끝나면 원래 스트림으로 돌아온다")
    void otherOutputPassesThrough() {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream replaced = new PrintStream(captured, true, StandardCharsets.UTF_8);
        List<String> logged = new java.util.ArrayList<>();
        try {
            System.setErr(replaced);
            try (JdtLog.Capture ignored = JdtLog.capture(logged::add)) {
                System.err.println("[codeatlas] 진행 상황");
                System.err.println("Status ERROR: org.eclipse.jdt.core code=4 Could not retrieve declared methods x.Y: boom");
                System.err.println("Status 200 OK");
            }
            assertThat(System.err).isSameAs(replaced);
        } finally {
            System.setErr(original);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("[codeatlas] 진행 상황").contains("Status 200 OK").doesNotContain("org.eclipse.jdt");
        assertThat(logged).containsExactly("Status ERROR: org.eclipse.jdt.core code=4 Could not retrieve declared methods x.Y: boom");
        assertThat(JdtLog.summarize(logged.get(0))).isEqualTo("Could not retrieve declared methods (boom)");
        assertThat(JdtLog.summarize("Status ERROR: org.eclipse.jdt.core code=4 Could not retrieve declared methods "
                + "org.eclipse.jdt.internal.compiler.problem.AbortCompilation: Pb(347) The type a.B cannot be resolved. It is indirectly referenced from required type c.D"))
                .isEqualTo("Could not retrieve declared methods (The type a.B cannot be resolved. It is indirectly referenced from required type c.D)");
    }
}
