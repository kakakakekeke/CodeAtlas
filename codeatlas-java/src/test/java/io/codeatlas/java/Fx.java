package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.testing.TestProject;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** 테스트 공용 도구: 가짜 프로젝트 실행, 그래프 조회, 라이브러리 스텁 컴파일. */
final class Fx {

    private static Path stubClasses;

    private Fx() {
    }

    /** 경로와 본문을 번갈아 준다. */
    static Map<String, String> files(String... pathAndContent) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pathAndContent.length; i += 2) {
            map.put(pathAndContent[i], pathAndContent[i + 1]);
        }
        return map;
    }

    /** jar 없이 분석한다. */
    static GraphModel analyze(String... pathAndContent) {
        return analyze(AnalysisOptions.defaults(), files(pathAndContent));
    }

    static GraphModel analyze(AnalysisOptions options, Map<String, String> files) {
        return analyzeCtx(options, files).graph();
    }

    static AnalysisContext analyzeCtx(AnalysisOptions options, Map<String, String> files) {
        try (TestProject project = TestProject.of(files).options(options)) {
            return project.run(new JavaAnalyzer());
        }
    }

    /** Spring·MyBatis·Batch 스텁을 classpath에 두고 분석한다. */
    static GraphModel analyzeWithStubs(String... pathAndContent) {
        return analyze(AnalysisOptions.builder().classpath(stubClasses()).build(), files(pathAndContent));
    }

    static AnalysisOptions extra(String key, String value) {
        return AnalysisOptions.builder().extra(key, value).build();
    }

    // ---- 그래프 조회 ----

    static Node node(GraphModel g, String id) {
        return g.node(id).orElseThrow(() -> new AssertionError("노드 없음: " + id + "\n있는 노드:\n  "
                + String.join("\n  ", g.nodes().stream().map(Node::id).filter(i -> !i.startsWith("file:")).sorted().toList())));
    }

    static List<Edge> edges(GraphModel g, String from, EdgeKind kind, String to) {
        return g.edges(from, kind, to);
    }

    static Edge edge(GraphModel g, String from, EdgeKind kind, String to) {
        List<Edge> found = g.edges(from, kind, to);
        if (found.isEmpty()) {
            throw new AssertionError("엣지 없음: " + from + " -" + kind + "-> " + to + "\n" + from + " 에서 나가는 엣지:\n  "
                    + String.join("\n  ", g.outgoing(from).stream().map(e -> e.kind() + " -> " + e.to() + " " + e.attrs()).toList())
                    + "\n" + to + " 로 들어오는 엣지:\n  "
                    + String.join("\n  ", g.incoming(to).stream().map(e -> e.from() + " -" + e.kind() + "->").toList()));
        }
        return found.get(0);
    }

    static boolean hasEdge(GraphModel g, String from, EdgeKind kind, String to) {
        return !g.edges(from, kind, to).isEmpty();
    }

    /** from에서 kind로 나가는 엣지의 대상 ID 목록. */
    static List<String> targets(GraphModel g, String from, EdgeKind kind) {
        return g.outgoing(from, kind).stream().map(Edge::to).toList();
    }

    static List<String> sources(GraphModel g, String to, EdgeKind kind) {
        return g.incoming(to, kind).stream().map(Edge::from).toList();
    }

    static List<String> diagnosticCodes(GraphModel g) {
        return g.diagnostics().stream().map(d -> d.code()).toList();
    }

    // ---- 라이브러리 스텁 ----

    /** 스텁 소스를 한 번 컴파일한 클래스 폴더. */
    static synchronized Path stubClasses() {
        if (stubClasses == null) {
            stubClasses = compile(Stubs.SOURCES);
        }
        return stubClasses;
    }

    /** 소스(경로→본문)를 임시 폴더에 컴파일하고 클래스 폴더를 돌려준다. */
    static Path compile(Map<String, String> sources) {
        try {
            Path src = Files.createTempDirectory("codeatlas-stub-src-");
            Path out = Files.createTempDirectory("codeatlas-stub-classes-");
            List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-proc:none", "-nowarn"));
            for (Map.Entry<String, String> e : sources.entrySet()) {
                Path file = src.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue());
                args.add(file.toString());
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            int rc = compiler.run(null, null, null, args.toArray(String[]::new));
            if (rc != 0) {
                throw new AssertionError("스텁 컴파일 실패");
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 클래스 폴더를 jar 바이트로 묶는다. */
    static byte[] jar(Path classesDir) {
        try {
            Path jar = Files.createTempFile("codeatlas-stub-", ".jar");
            try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os);
                 Stream<Path> walk = Files.walk(classesDir)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    jos.putNextEntry(new JarEntry(classesDir.relativize(p).toString().replace('\\', '/')));
                    jos.write(Files.readAllBytes(p));
                    jos.closeEntry();
                }
            }
            byte[] bytes = Files.readAllBytes(jar);
            Files.delete(jar);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
