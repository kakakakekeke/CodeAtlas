package io.codeatlas.core.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.ProjectFiles;
import io.codeatlas.core.spi.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectScannerTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        write(root, relative, content);
    }

    private static Path write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Map<String, FileType> types(ProjectFiles files) {
        return files.all().stream().collect(Collectors.toMap(SourceFile::relativePath, SourceFile::type));
    }

    @Test
    @DisplayName("F-001-AC1 확장자와 XML 루트 요소로 파일 종류를 분류한다")
    void classifiesFiles() throws Exception {
        write("src/main/java/com/a/B.java", "package com.a; class B {}");
        write("db/q.sql", "select 1 from dual;");
        write("db/pkg_order.PKB", "create or replace package body pkg_order as end;");
        write("web/app.js", "function a() {}");
        write("web/App.tsx", "export const A = () => null;");
        write("web/types.d.ts", "declare const x: number;");
        write("web/WEB-INF/views/list.jsp", "<html></html>");
        write("web/index.html", "<html></html>");
        write("conf/app.properties", "a=b");
        write("conf/app.yml", "a: b");
        write("lib/dep.jar", "PK");
        write("readme.txt", "hello");
        write("mapper/OrderMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- 주문 매퍼 -->
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.a.OrderMapper"><select id="a">select 1</select></mapper>
                """);
        write("conf/context.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"><bean id="a" class="com.a.B"/></beans>
                """);
        write("conf/security.xml", "<beans:beans xmlns:beans=\"http://www.springframework.org/schema/beans\"></beans:beans>");
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>");
        write("conf/broken.xml", "this is not xml <<<");

        ProjectFiles files = ProjectScanner.scan(root, AnalysisOptions.defaults());

        assertThat(types(files)).containsOnly(
                Map.entry("src/main/java/com/a/B.java", FileType.JAVA),
                Map.entry("db/q.sql", FileType.SQL),
                Map.entry("db/pkg_order.PKB", FileType.SQL),
                Map.entry("web/app.js", FileType.JS),
                Map.entry("web/App.tsx", FileType.TS),
                Map.entry("web/types.d.ts", FileType.OTHER),
                Map.entry("web/WEB-INF/views/list.jsp", FileType.JSP),
                Map.entry("web/index.html", FileType.HTML),
                Map.entry("conf/app.properties", FileType.PROPERTIES),
                Map.entry("conf/app.yml", FileType.YAML),
                Map.entry("lib/dep.jar", FileType.JAR),
                Map.entry("readme.txt", FileType.OTHER),
                Map.entry("mapper/OrderMapper.xml", FileType.MYBATIS_MAPPER),
                Map.entry("conf/context.xml", FileType.SPRING_XML),
                Map.entry("conf/security.xml", FileType.SPRING_XML),
                Map.entry("pom.xml", FileType.XML),
                Map.entry("conf/broken.xml", FileType.XML));

        assertThat(files.ofType(FileType.MYBATIS_MAPPER, FileType.SPRING_XML)).extracting(SourceFile::relativePath)
                .containsExactly("conf/context.xml", "conf/security.xml", "mapper/OrderMapper.xml");
        assertThat(files.byRelativePath("db/q.sql")).get().satisfies(f -> {
            assertThat(f.absolutePath()).isEqualTo(root.toAbsolutePath().normalize().resolve("db/q.sql"));
            assertThat(f.readText()).isEqualTo("select 1 from dual;");
        });
        assertThat(files.countsByType()).containsEntry(FileType.SQL, 2).containsEntry(FileType.XML, 2);
        assertThat(files.all()).extracting(SourceFile::relativePath).isSorted();
    }

    @Test
    @DisplayName("F-001-AC2 기본 제외 디렉터리와 --exclude glob에 맞는 파일을 뺀다")
    void excludes() throws Exception {
        write("src/A.java", "class A {}");
        write("src/gen/G.java", "class G {}");
        write("src/app.min.js", "x");
        write("node_modules/x/index.js", "x");
        write("target/classes/A.class", "x");
        write(".git/config", "x");
        write("sub/build/out.js", "x");
        write("sub/bin/run.sql", "x");
        write("legacy/old/Old.java", "class Old {}");

        List<Diagnostic> diagnostics = new ArrayList<>();
        AnalysisOptions options = AnalysisOptions.builder().exclude("src/gen/**").exclude("*.min.js").exclude("old").build();
        ProjectFiles files = ProjectScanner.scan(root, options, List.of(), diagnostics::add);

        assertThat(files.all()).extracting(SourceFile::relativePath).containsExactly("src/A.java");
        assertThat(diagnostics).extracting(Diagnostic::code).containsOnly("EXCLUDED_DIRECTORY");
        assertThat(diagnostics).extracting(d -> d.location().file())
                .containsExactlyInAnyOrder(".git", "node_modules", "target", "sub/build", "sub/bin");
    }

    @Test
    @DisplayName("F-001-AC2 src 아래의 build, out, bin, dist, target은 패키지일 수 있으므로 제외하지 않는다")
    void buildLikeDirectoriesUnderSrcAreKept() throws Exception {
        write("src/main/java/com/x/batch/out/Writer.java", "class Writer {}");
        write("src/main/java/com/x/build/B.java", "class B {}");
        write("src/main/java/com/x/bin/C.java", "class C {}");
        write("src/main/java/com/x/dist/D.java", "class D {}");
        write("src/main/java/com/x/target/T.java", "class T {}");
        write("module/src/main/resources/out/q.sql", "select 1 from dual;");
        write("src/node_modules/x/index.js", "x");
        write("src/.git/config", "x");
        write("src/.idea/a.xml", "<a/>");
        write("src/.gradle/a.properties", "a=b");
        write("module/target/classes/A.class", "x");
        write("out/production/A.class", "x");

        List<Diagnostic> diagnostics = new ArrayList<>();
        ProjectFiles files = ProjectScanner.scan(root, AnalysisOptions.defaults(), List.of(), diagnostics::add);

        assertThat(files.all()).extracting(SourceFile::relativePath).containsExactly(
                "module/src/main/resources/out/q.sql",
                "src/main/java/com/x/batch/out/Writer.java",
                "src/main/java/com/x/bin/C.java",
                "src/main/java/com/x/build/B.java",
                "src/main/java/com/x/dist/D.java",
                "src/main/java/com/x/target/T.java");
        assertThat(diagnostics).extracting(d -> d.location().file()).containsExactlyInAnyOrder(
                "src/node_modules", "src/.git", "src/.idea", "src/.gradle", "module/target", "out");
    }

    @Test
    @DisplayName("F-001-AC2 따로 넘긴 제외 경로(출력 폴더)는 스캔하지 않는다")
    void excludedPaths() throws Exception {
        write("src/A.java", "class A {}");
        write("codeatlas-out/graph.json", "{}");
        ProjectFiles files = ProjectScanner.scan(root, AnalysisOptions.defaults(), List.of(root.resolve("codeatlas-out")), d -> { });
        assertThat(files.all()).extracting(SourceFile::relativePath).containsExactly("src/A.java");
    }

    @Test
    @DisplayName("F-001-AC3 프로젝트 밖의 DDL은 ddl/<파일명> 가상 경로로 들어온다")
    void externalDdl(@TempDir Path outside) throws Exception {
        write("src/A.java", "class A {}");
        write("ddl/schema.sql", "create table in_project (id number);");
        write("build/generated.ddl", "create table gen (id number);");
        Path single = write(outside, "one/tables.txt", "create table t (id number);");
        Path sameName = write(outside, "two/schema.sql", "create table s (id number);");
        write(outside, "dir/a.sql", "create table a (id number);");
        write(outside, "dir/nested/b.ddl", "create table b (id number);");
        write(outside, "dir/readme.md", "not sql");

        List<Diagnostic> diagnostics = new ArrayList<>();
        AnalysisOptions options = AnalysisOptions.builder()
                .ddlPath(single).ddlPath(sameName).ddlPath(outside.resolve("dir"))
                .ddlPath(root.resolve("build/generated.ddl")).ddlPath(outside.resolve("missing.sql")).build();
        ProjectFiles files = ProjectScanner.scan(root, options, List.of(), diagnostics::add);

        assertThat(types(files)).containsOnly(
                Map.entry("src/A.java", FileType.JAVA),
                Map.entry("ddl/schema.sql", FileType.SQL),
                Map.entry("ddl/tables.txt", FileType.SQL),
                Map.entry("ddl/_2/schema.sql", FileType.SQL),
                Map.entry("ddl/a.sql", FileType.SQL),
                Map.entry("ddl/nested/b.ddl", FileType.SQL),
                Map.entry("build/generated.ddl", FileType.SQL));
        assertThat(files.byRelativePath("ddl/_2/schema.sql").orElseThrow().readText()).contains("table s");
        assertThat(files.byRelativePath("ddl/schema.sql").orElseThrow().readText()).contains("in_project");
        assertThat(diagnostics).filteredOn(d -> d.code().equals("FILE_NOT_FOUND")).hasSize(1);
    }

    @Test
    @DisplayName("F-001-AC4 스캔 옵션의 encoding은 SourceFile에 전달된다")
    void forcedEncodingPropagates() throws Exception {
        Files.write(root.resolve("a.sql"), "가".getBytes(StandardCharsets.UTF_8));
        AnalysisOptions options = AnalysisOptions.builder().encoding(StandardCharsets.ISO_8859_1).build();
        SourceFile file = ProjectScanner.scan(root, options).byRelativePath("a.sql").orElseThrow();
        assertThat(file.readText()).isEqualTo(new String("가".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }
}
