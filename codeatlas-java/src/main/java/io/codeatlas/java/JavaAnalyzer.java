package io.codeatlas.java;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Java 분석기. 프로젝트의 모든 Java 소스를 Eclipse JDT Core로 한 번에 파싱하고(바인딩 해석 포함)
 * 구조(F-002), 콜 그래프(F-003), Spring·MyBatis(F-004), Spring Batch(F-005)를 그래프에 쓴다.
 *
 * <p>의존 jar가 classpath에 없는 것이 기본이라고 보고, 해석되지 않는 타입은 import로 이름을 복원하거나 미해결로 남긴다.
 */
public final class JavaAnalyzer implements Analyzer {

    /** 대상 소스의 문법 수준(PRD 7). */
    private static final String SOURCE_LEVEL = JavaCore.VERSION_21;

    @Override
    public String name() {
        return "java";
    }

    @Override
    public int order() {
        return ORDER_PRODUCER;
    }

    @Override
    public void analyze(AnalysisContext ctx) {
        List<SourceFile> javaFiles = ctx.files().ofType(FileType.JAVA);
        if (javaFiles.isEmpty()) {
            return;
        }
        GraphModel graph = ctx.graph();
        JavaOptions options = JavaOptions.from(ctx.options(), graph);
        SourcePrescan prescan = SourcePrescan.scan(javaFiles, graph);
        JavaProject project = new JavaProject(ctx, options, prescan);

        String[] classpath = classpath(ctx);
        String[] sourcepath = prescan.sourceRoots.stream().map(Path::toString).toArray(String[]::new);
        // JDT가 표준 오류에 직접 쓰는 내부 로그는 분석 중이던 파일의 진단으로 바꾼다(F-002-R18)
        Set<String> reportedJdtLogs = new HashSet<>();
        try (JdtLog.Capture ignored = JdtLog.capture(line -> reportJdtLog(project, reportedJdtLogs, line))) {
            parse(project, prescan.entries, classpath, sourcepath);
        }

        ctx.progress("Java 미해석 호출·빈·배치 후처리");
        project.calls.resolvePending();
        new SpringLinker(project).run();
        new BatchLinker(project).run();
        if (project.skippedJdkCalls > 0 || project.skippedExternalCalls > 0) {
            graph.report(Diagnostic.info(JavaProject.EXTERNAL_CALLS_SKIPPED,
                    "기록하지 않은 외부 메서드 호출: JDK " + project.skippedJdkCalls + "건, 그 밖의 라이브러리 "
                            + project.skippedExternalCalls + "건 (옵션 " + JavaOptions.KEY_EXTERNAL_CALLS + ")", null));
        }
    }

    /**
     * JDT 내부 로그 한 줄을 WARN 진단으로 남긴다. 위치는 그때 분석 중이던 파일이고(일괄 파싱 중이면 위치 없음),
     * 같은 파일의 같은 메시지는 한 번만 남긴다. 로그가 났어도 JDT는 그 조회만 빈 결과로 돌려주고 분석은 이어진다.
     */
    private void reportJdtLog(JavaProject project, Set<String> reported, String line) {
        SourceFile file = project.currentFile;
        String summary = JdtLog.summarize(line);
        if (reported.add((file == null ? "" : file.relativePath()) + "\n" + summary)) {
            project.graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                    "JDT가 타입 정보를 읽다가 낸 내부 오류를 넘기고 계속 분석함: " + summary
                            + " — classpath에 없는 타입 때문이면 그 타입이 든 jar를 classpath에 주면 없어진다",
                    file == null ? null : file.location()));
        }
    }

    /**
     * 옵션의 classpath와 프로젝트 안에서 스캔된 jar. 없는 경로는 뺀다.
     *
     * <p>폴더를 주면 클래스 폴더로 넣고, 그 폴더 바로 아래(한 단계)의 jar도 넣는다({@code WEB-INF/lib}, Maven {@code target/dependency},
     * Gradle {@code libs} 같은 폴더를 그대로 줄 수 있게. F-002-R17). 자바 관례의 와일드카드 표기 {@code dir/*}는 그 폴더의 jar만 뜻한다.
     */
    private static String[] classpath(AnalysisContext ctx) {
        Set<String> entries = new LinkedHashSet<>();
        for (Path p : ctx.options().classpath()) {
            String last = p.getFileName() == null ? "" : p.getFileName().toString();
            boolean wildcard = last.equals("*") || last.equalsIgnoreCase("*.jar");
            Path target = !wildcard ? p : p.getParent() == null ? Path.of("") : p.getParent();
            if (!Files.exists(target) || (wildcard && !Files.isDirectory(target))) {
                ctx.graph().report(Diagnostic.warn(DiagnosticCodes.FILE_NOT_FOUND, "classpath 경로가 없음: " + p, null));
                continue;
            }
            if (!wildcard) {
                entries.add(target.toAbsolutePath().toString());
            }
            if (Files.isDirectory(target)) {
                entries.addAll(jarsDirectlyUnder(target, ctx.graph()));
            }
        }
        for (SourceFile jar : ctx.files().ofType(FileType.JAR)) {
            entries.add(jar.absolutePath().toString());
        }
        return entries.toArray(String[]::new);
    }

    /** 폴더 바로 아래의 jar 파일(이름순, 절대 경로). 하위 폴더는 보지 않는다. */
    private static List<String> jarsDirectlyUnder(Path directory, GraphModel graph) {
        List<String> jars = new ArrayList<>();
        try (Stream<Path> children = Files.list(directory)) {
            children.filter(child -> child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(child))
                    .map(child -> child.toAbsolutePath().toString()).sorted().forEach(jars::add);
        } catch (IOException | UncheckedIOException e) {
            graph.report(Diagnostic.warn(DiagnosticCodes.FILE_NOT_READABLE, "classpath 폴더를 읽지 못함: " + directory + " (" + e.getMessage() + ")", null));
        }
        return jars;
    }

    /**
     * 파일 묶음을 한 번에 파싱한다. JDT가 예외로 끝나면 아직 처리하지 못한 파일을 반으로 나눠 다시 시도해
     * 문제가 되는 파일 하나만 실패로 남긴다. 묶음의 마지막 파일에서 끝났으면 그 파일만 새 파서로 한 번 더 시도한다(F-002-R19):
     * 앞 파일을 분석하며 아직 컴파일되지 않은 타입을 물은 탓에 컴파일러 상태가 깨진 경우라, 따로 파싱하면 된다.
     */
    private void parse(JavaProject project, List<SourcePrescan.Entry> entries, String[] classpath, String[] sourcepath) {
        Map<String, SourcePrescan.Entry> pending = new HashMap<>();
        String[] paths = new String[entries.size()];
        String[] encodings = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            SourcePrescan.Entry entry = entries.get(i);
            paths[i] = entry.file().absolutePath().toString();
            encodings[i] = entry.charsetName();
            pending.put(paths[i], entry);
        }
        // 메모리 부족 안내에 실릴 "처리 중이던 것"(F-002-R16): 일괄 파싱 중에는 파일을 특정할 수 없다
        project.ctx.progress("Java 소스 " + entries.size() + "개 일괄 파싱");
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setStatementsRecovery(true);
            Map<String, String> compilerOptions = JavaCore.getOptions();
            JavaCore.setComplianceOptions(SOURCE_LEVEL, compilerOptions);
            parser.setCompilerOptions(compilerOptions);
            parser.setEnvironment(classpath, sourcepath, null, true);
            parser.createASTs(paths, encodings, new String[0], new FileASTRequestor() {
                @Override
                public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                    SourcePrescan.Entry entry = pending.remove(sourceFilePath);
                    if (entry != null) {
                        analyzeUnit(project, entry.file(), cu);
                    }
                }
            }, null);
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            List<SourcePrescan.Entry> rest = new ArrayList<>();
            for (SourcePrescan.Entry entry : entries) {
                if (pending.containsKey(entry.file().absolutePath().toString())) {
                    rest.add(entry);
                }
            }
            if (rest.size() == 1 && entries.size() > 1) {
                // 남은 것이 하나여도 묶음에서 떨어진 것이면 혼자서는 될 수 있다
                parse(project, rest, classpath, sourcepath);
                return;
            }
            if (rest.size() <= 1) {
                for (SourcePrescan.Entry entry : rest) {
                    project.graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED,
                            "Java 파서가 예외로 끝남: " + e, entry.file().location()));
                }
                return;
            }
            if (rest.size() == entries.size()) {
                // 한 파일도 처리하지 못했으면 반으로 나눈다
                int mid = rest.size() / 2;
                parse(project, rest.subList(0, mid), classpath, sourcepath);
                parse(project, rest.subList(mid, rest.size()), classpath, sourcepath);
            } else {
                parse(project, rest, classpath, sourcepath);
            }
            return;
        }
        for (SourcePrescan.Entry entry : pending.values()) {
            project.graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "Java 파서가 결과를 주지 않음", entry.file().location()));
        }
    }

    /** 파일 하나를 분석한다. 여기서 난 예외는 그 파일의 진단으로 남기고 다음 파일로 간다. */
    private void analyzeUnit(JavaProject project, SourceFile file, CompilationUnit cu) {
        project.ctx.progress(file.relativePath());
        project.currentFile = file;
        try {
            reportSyntaxErrors(project.graph, file, cu);
            Unit unit = new Unit(project, file, cu);
            new StructureExtractor(unit).run();
            new CallGraphExtractor(unit).run();
            new SpringExtractor(unit).run();
            new BatchExtractor(unit).run();
        } catch (RuntimeException | StackOverflowError e) {
            project.graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "Java 파일 분석 중 예외: " + e, file.location()));
        } finally {
            project.currentFile = null;
        }
    }

    private static void reportSyntaxErrors(GraphModel graph, SourceFile file, CompilationUnit cu) {
        int count = 0;
        IProblem first = null;
        for (IProblem problem : cu.getProblems()) {
            if (problem.isError() && (problem.getID() & IProblem.Syntax) != 0) {
                count++;
                if (first == null) {
                    first = problem;
                }
            }
        }
        if (first != null) {
            graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                    "구문 오류 " + count + "건. 복구된 부분만 분석함 (첫 오류: " + first.getMessage() + ")",
                    file.location(Math.max(first.getSourceLineNumber(), 0))));
        }
    }
}
