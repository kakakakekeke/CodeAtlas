package io.codeatlas.java;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.codeatlas.java.Fx.hasEdge;
import static org.assertj.core.api.Assertions.assertThat;

/** F-002-R17: classpath로 준 폴더 바로 아래의 jar도 classpath에 넣는다({@code WEB-INF/lib}, {@code target/dependency} 같은 폴더를 그대로 줄 수 있게). */
class ClasspathFolderTest {

    /** 와일드카드 import만 써서 jar가 classpath에 없으면 상위 타입의 FQN을 복원할 수 없는 소스. */
    private static final String[] SOURCES = {
            "src/com/a/Mine.java", "package com.a; import lib.util.*; import loose.*; public class Mine extends LibBase { Loose loose; }",
    };

    private static String type(String name) {
        return Ids.javaType(name);
    }

    /** 프로젝트 밖의 폴더: 바로 아래에 스텁 jar와 풀린 클래스({@code loose/Loose.class}), 한 단계 아래 폴더에 다른 jar. */
    private static Path libFolder() {
        try {
            Path dir = Fx.compile(Fx.files("loose/Loose.java", "package loose; public class Loose { }"));
            Files.write(dir.resolve("stubs.jar"), Fx.jar(Fx.stubClasses()));
            Path nested = Files.createDirectories(dir.resolve("nested"));
            Files.write(nested.resolve("deep.JAR"), Fx.jar(Fx.compile(Fx.files("deep/Deep.java", "package deep; public class Deep { }"))));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static GraphModel analyze(Path classpathEntry, String... sources) {
        return Fx.analyze(AnalysisOptions.builder().classpath(classpathEntry).build(), Fx.files(sources));
    }

    @Test
    @DisplayName("F-002-AC22 classpath로 준 폴더 바로 아래의 jar를 타입 해석에 쓰고, 그 폴더는 클래스 폴더로도 남는다")
    void jarsDirectlyUnderClasspathFolder() {
        GraphModel g = analyze(libFolder(), SOURCES);

        assertThat(hasEdge(g, type("com.a.Mine"), EdgeKind.EXTENDS, type("lib.util.LibBase"))).isTrue();
        assertThat(hasEdge(g, type("com.a.Mine"), EdgeKind.ASSOCIATES, type("loose.Loose"))).isTrue();
        assertThat(g.nodes(NodeKind.UNRESOLVED)).isEmpty();
        assertThat(Fx.diagnosticCodes(g)).doesNotContain("FILE_NOT_FOUND", "UNRESOLVED_REFERENCE");
    }

    @Test
    @DisplayName("F-002-AC22 한 단계 아래 폴더의 jar는 쓰지 않는다")
    void nestedJarsAreNotUsed() {
        GraphModel g = analyze(libFolder(),
                "src/com/a/Uses.java", "package com.a; import deep.*; public class Uses extends Deep { }");

        assertThat(hasEdge(g, type("com.a.Uses"), EdgeKind.EXTENDS, type("deep.Deep"))).isFalse();
        assertThat(g.nodes(NodeKind.UNRESOLVED)).extracting(n -> n.name()).containsExactly("Deep");
    }

    @Test
    @DisplayName("F-002-AC22 자바 관례의 와일드카드 표기 dir/* 도 받아들인다(그 폴더의 jar만. 대문자 .JAR 포함). 폴더가 없으면 FILE_NOT_FOUND")
    void wildcardEntry() {
        Path dir = libFolder();
        GraphModel g = analyze(dir.resolve("*"), SOURCES);

        assertThat(hasEdge(g, type("com.a.Mine"), EdgeKind.EXTENDS, type("lib.util.LibBase"))).isTrue();
        // dir/* 는 jar만 뜻한다: 풀린 클래스(loose.Loose)는 classpath에 없다
        assertThat(g.nodes(NodeKind.UNRESOLVED)).extracting(n -> n.name()).containsExactly("Loose");
        assertThat(Fx.diagnosticCodes(g)).doesNotContain("FILE_NOT_FOUND");

        GraphModel upper = analyze(dir.resolve("nested").resolve("*"),
                "src/com/a/Uses.java", "package com.a; import deep.*; public class Uses extends Deep { }");
        assertThat(hasEdge(upper, type("com.a.Uses"), EdgeKind.EXTENDS, type("deep.Deep"))).isTrue();

        GraphModel missing = analyze(dir.resolve("no-such-folder").resolve("*"), SOURCES);
        assertThat(Fx.diagnosticCodes(missing)).contains("FILE_NOT_FOUND");
    }
}
