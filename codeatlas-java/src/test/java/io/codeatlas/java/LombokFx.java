package io.codeatlas.java;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.AnalysisOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Lombok 테스트 도구. 같은 소스를 Lombok이 classpath에 없을 때, 애너테이션 스텁이 있을 때,
 * (~/.m2에 있으면) 실제 lombok jar가 있을 때로 분석해 결과가 같은지 본다. 어느 경우에도 애너테이션 처리기는 돌지 않는다.
 */
final class LombokFx {

    private static Path stubClasses;

    private LombokFx() {
    }

    /** classpath 구성마다 분석해 단언을 실행한다. 단언의 첫 인자는 구성 이름이다. */
    static void eachClasspath(Map<String, String> files, BiConsumer<String, GraphModel> assertions) {
        assertions.accept("lombok 없음", Fx.analyze(AnalysisOptions.defaults(), files));
        assertions.accept("lombok 스텁", Fx.analyze(AnalysisOptions.builder().classpath(stubClasses()).build(), files));
        realJar().ifPresent(jar ->
                assertions.accept("lombok jar " + jar.getFileName(), Fx.analyze(AnalysisOptions.builder().classpath(jar).build(), files)));
    }

    static synchronized Path stubClasses() {
        if (stubClasses == null) {
            stubClasses = Fx.compile(sources());
        }
        return stubClasses;
    }

    /** 로컬 Maven 저장소에 lombok jar가 있으면 가장 높은 버전의 것. */
    static Optional<Path> realJar() {
        Path dir = Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "projectlombok", "lombok");
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            return walk.filter(p -> p.getFileName().toString().matches("lombok-[0-9.]+\\.jar"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> sources() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("lombok/AccessLevel.java", "package lombok; public enum AccessLevel { PUBLIC, MODULE, PROTECTED, PACKAGE, PRIVATE, NONE }");
        s.put("lombok/Getter.java", anno("lombok", "Getter", "AccessLevel value() default AccessLevel.PUBLIC; boolean lazy() default false;"));
        s.put("lombok/Setter.java", anno("lombok", "Setter", "AccessLevel value() default AccessLevel.PUBLIC;"));
        s.put("lombok/Data.java", anno("lombok", "Data", "String staticConstructor() default \"\";"));
        s.put("lombok/Value.java", anno("lombok", "Value", "String staticConstructor() default \"\";"));
        s.put("lombok/NonNull.java", anno("lombok", "NonNull", ""));
        s.put("lombok/NoArgsConstructor.java", anno("lombok", "NoArgsConstructor",
                "String staticName() default \"\"; AccessLevel access() default AccessLevel.PUBLIC; boolean force() default false;"));
        s.put("lombok/AllArgsConstructor.java", anno("lombok", "AllArgsConstructor",
                "String staticName() default \"\"; AccessLevel access() default AccessLevel.PUBLIC;"));
        s.put("lombok/RequiredArgsConstructor.java", anno("lombok", "RequiredArgsConstructor",
                "String staticName() default \"\"; AccessLevel access() default AccessLevel.PUBLIC;"));
        s.put("lombok/Builder.java", anno("lombok", "Builder",
                "String builderMethodName() default \"builder\"; String buildMethodName() default \"build\"; "
                        + "String builderClassName() default \"\"; boolean toBuilder() default false; String setterPrefix() default \"\"; "
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) @interface Default { }"));
        s.put("lombok/Singular.java", anno("lombok", "Singular", "String value() default \"\";"));
        s.put("lombok/ToString.java", anno("lombok", "ToString", ""));
        s.put("lombok/EqualsAndHashCode.java", anno("lombok", "EqualsAndHashCode", ""));
        s.put("lombok/With.java", anno("lombok", "With", "AccessLevel value() default AccessLevel.PUBLIC;"));
        s.put("lombok/experimental/Accessors.java", anno("lombok.experimental", "Accessors",
                "boolean fluent() default false; boolean chain() default false; String[] prefix() default {};"));
        s.put("lombok/experimental/SuperBuilder.java", anno("lombok.experimental", "SuperBuilder",
                "String builderMethodName() default \"builder\"; String buildMethodName() default \"build\"; boolean toBuilder() default false;"));
        s.put("lombok/experimental/FieldDefaults.java", anno("lombok.experimental", "FieldDefaults",
                "boolean makeFinal() default false; lombok.AccessLevel level() default lombok.AccessLevel.NONE;"));
        s.put("lombok/extern/slf4j/Slf4j.java", anno("lombok.extern.slf4j", "Slf4j", "String topic() default \"\";"));
        s.put("lombok/extern/log4j/Log4j2.java", anno("lombok.extern.log4j", "Log4j2", "String topic() default \"\";"));
        s.put("lombok/extern/java/Log.java", anno("lombok.extern.java", "Log", "String topic() default \"\";"));
        s.put("lombok/extern/apachecommons/CommonsLog.java", anno("lombok.extern.apachecommons", "CommonsLog", "String topic() default \"\";"));
        return s;
    }

    private static String anno(String pkg, String name, String members) {
        return "package " + pkg + ";\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.SOURCE)\npublic @interface " + name
                + " { " + members + " }\n";
    }
}
