package io.codeatlas.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path project;
    @TempDir
    Path work;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8), new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private static void write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void fakeProject() throws IOException {
        write(project, "src/main/java/com/a/OrderService.java", "package com.a;\npublic class OrderService { void save() {} }\n");
        write(project, "src/main/resources/mapper/OrderMapper.xml",
                "<?xml version=\"1.0\"?>\n<mapper namespace=\"com.a.OrderMapper\">\n<select id=\"find\">select * from orders</select>\n</mapper>\n");
        write(project, "db/query.sql", "select * from orders;\n");
        write(project, "db/gen/skip.sql", "select 1 from dual;\n");
        RecordingAnalyzer.reset();
        RecordingExporter.lastOptions = null;
    }

    @AfterEach
    void resetHooks() {
        RecordingAnalyzer.reset();
    }

    @Test
    @DisplayName("F-001-AC16 analyze는 graph.json과 diagnostics.md를 쓰고 요약을 표준 출력에 찍는다")
    void analyzeWritesOutputs() throws Exception {
        Path outDir = work.resolve("result");
        Path ddl = work.resolve("external/schema.sql");
        write(work, "external/schema.sql", "create table orders (id number);\n");
        Path jar = work.resolve("lib/dep.jar");

        int exit = run("analyze", project.toString(), "--out", outDir.toString(), "--ddl", ddl.toString(),
                "--classpath", jar + java.io.File.pathSeparator + work.resolve("classes"), "--classpath=" + work.resolve("more.jar"),
                "--encoding", "MS949", "--dialect", "db2", "--exclude", "db/gen/**");

        assertThat(exit).as(err()).isZero();
        assertThat(outDir.resolve("graph.json")).exists();
        assertThat(outDir.resolve("diagnostics.md")).exists();
        assertThat(outDir.resolve("cli-test-exporter.txt")).exists();

        GraphModel graph = GraphJson.read(outDir.resolve("graph.json"));
        assertThat(graph.nodes(NodeKind.FILE)).extracting(n -> n.id()).contains(
                "file:src/main/java/com/a/OrderService.java",
                "file:src/main/resources/mapper/OrderMapper.xml",
                "file:db/query.sql",
                "file:ddl/schema.sql").doesNotContain("file:db/gen/skip.sql");
        assertThat(graph.node("file:src/main/resources/mapper/OrderMapper.xml").orElseThrow().attrs()).containsEntry("fileType", "MYBATIS_MAPPER");
        assertThat(graph.edges("java:type:clitest.Marker", EdgeKind.EXTENDS, "java:type:clitest.Missing"))
                .singleElement().extracting(Edge::confidence).isEqualTo(Confidence.UNRESOLVED);

        assertThat(RecordingAnalyzer.lastOptions.encoding()).isEqualTo(Charset.forName("MS949"));
        assertThat(RecordingAnalyzer.lastOptions.dialect()).isEqualTo(Dialect.DB2);
        assertThat(RecordingAnalyzer.lastOptions.excludes()).containsExactly("db/gen/**");
        assertThat(RecordingAnalyzer.lastOptions.ddlPaths()).containsExactly(ddl);
        assertThat(RecordingAnalyzer.lastOptions.classpath()).containsExactly(jar, work.resolve("classes"), work.resolve("more.jar"));

        String summary = out();
        assertThat(summary).containsPattern("파일 \\(총 4\\)");
        assertThat(summary).containsPattern("JAVA\\s+1\\b");
        assertThat(summary).containsPattern("MYBATIS_MAPPER\\s+1\\b");
        assertThat(summary).containsPattern("SQL\\s+2\\b");
        assertThat(summary).containsPattern("노드 \\(총 \\d+\\)");
        assertThat(summary).containsPattern("FILE\\s+4\\b");
        assertThat(summary).containsPattern("UNRESOLVED\\s+\\d+");
        assertThat(summary).containsPattern("엣지 \\(총 \\d+\\)");
        assertThat(summary).containsPattern("EXTENDS\\s+\\d+");
        assertThat(summary).contains("신뢰도별 엣지");
        assertThat(summary).containsPattern("RESOLVED\\s+\\d+");
        assertThat(summary).containsPattern("INFERRED\\s+\\d+");
        assertThat(summary).containsPattern("진단 \\(총 \\d+\\)");
        assertThat(summary).containsPattern("WARN\\s+\\d+");
        assertThat(summary).contains(outDir.resolve("graph.json").toString());

        String report = Files.readString(outDir.resolve("diagnostics.md"));
        assertThat(report).startsWith("# 진단 리포트");
        assertThat(report).contains("| WARN | CLI_TEST |").contains("표 \\| 문자와 줄바꿈이 든 메시지");
        assertThat(report).contains("UNRESOLVED_REFERENCE");
    }

    @Test
    @DisplayName("F-001-AC16 --out을 주지 않으면 현재 폴더의 codeatlas-out에 쓴다")
    void defaultOutputDirectory() {
        assertThat(AnalyzeCommand.parse(new String[] {project.toString()}).outputDir())
                .isEqualTo(Path.of("codeatlas-out"));
    }

    @Test
    @DisplayName("F-001-AC17 없는 프로젝트 경로면 0이 아닌 종료 코드와 이유를 낸다")
    void missingProject() {
        int exit = run("analyze", work.resolve("nope").toString(), "--out", work.resolve("o").toString());
        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("nope");
        assertThat(work.resolve("o/graph.json")).doesNotExist();
    }

    @Test
    @DisplayName("F-001-AC17 알 수 없는 옵션, 빠진 값, 잘못된 값이면 사용법 오류(2)로 끝난다")
    void usageErrors() {
        assertThat(run("analyze", project.toString(), "--bogus")).isEqualTo(2);
        assertThat(err()).contains("--bogus");
        assertThat(run("analyze", project.toString(), "--out")).isEqualTo(2);
        assertThat(run("analyze", project.toString(), "--dialect", "mysql")).isEqualTo(2);
        assertThat(err()).contains("mysql");
        assertThat(run("analyze", project.toString(), "--encoding", "no-such-charset")).isEqualTo(2);
        assertThat(run("analyze")).isEqualTo(2);
        assertThat(run("analyze", project.toString(), "extra")).isEqualTo(2);
        assertThat(run("frobnicate")).isEqualTo(2);
        assertThat(run()).isEqualTo(2);
        assertThat(err()).contains("codeatlas analyze <projectDir>");
        assertThat(out()).isEmpty();
    }

    @Test
    @DisplayName("--help는 사용법을 표준 출력에 찍고 0으로 끝난다")
    void help() {
        assertThat(run("--help")).isZero();
        assertThat(out()).contains("codeatlas analyze <projectDir>").contains("--ddl").contains("--exclude");
        assertThat(run("analyze", "--help")).isZero();
        assertThat(run("--version")).isZero();
    }

    @Test
    @DisplayName("F-001-AC21 --opt key=value는 여러 번 줄 수 있고 AnalysisOptions.extra로 분석기와 출력기에 전달된다")
    void extraOptions() {
        int exit = run("analyze", project.toString(), "--out", work.resolve("o").toString(),
                "--opt", "calltree.depth=5", "--opt=viewer.title=주문 = 관리", "--opt", "flag=");

        assertThat(exit).as(err()).isZero();
        assertThat(RecordingAnalyzer.lastOptions.extra())
                .containsEntry("calltree.depth", "5").containsEntry("viewer.title", "주문 = 관리").containsEntry("flag", "").hasSize(3);
        assertThat(RecordingExporter.lastOptions).containsEntry("calltree.depth", "5").containsEntry("viewer.title", "주문 = 관리");
    }

    @Test
    @DisplayName("F-001-AC43 analyze 때 준 옵션(방언, 인코딩, classpath·DDL 경로, 제외 패턴, --opt)이 graph.json의 project.options에 입력 그대로 저장된다")
    void storesOptionsInGraphJson() throws IOException {
        Path outDir = work.resolve("o");
        write(work, "ddl/schema.sql", "create table orders (id number);\n");
        String ddl = work.resolve("ddl").toString();

        int exit = run("analyze", project.toString(), "--out", outDir.toString(), "--no-export", "--quiet",
                "--ddl", ddl, "--ddl", "relative/missing.sql", "--classpath", "lib/a.jar" + File.pathSeparator + "lib/b",
                "--dialect", "oracle", "--encoding", "UTF-8", "--exclude", "db/gen/**",
                "--opt", "export.erd.columns=keys", "--opt", "link.minJoinEvidence=2");

        assertThat(exit).as(err()).isZero();
        AnalysisOptions saved = GraphJson.read(outDir.resolve("graph.json")).project().options();
        assertThat(saved).isEqualTo(RecordingAnalyzer.lastOptions);
        assertThat(saved.ddlPaths()).containsExactly(Path.of(ddl), Path.of("relative/missing.sql"));
        assertThat(saved.classpath()).containsExactly(Path.of("lib/a.jar"), Path.of("lib/b"));
        assertThat(saved.dialect()).isEqualTo(Dialect.ORACLE);
        assertThat(saved.encoding()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(saved.excludes()).containsExactly("db/gen/**");
        assertThat(saved.extra()).containsOnly(Map.entry("export.erd.columns", "keys"), Map.entry("link.minJoinEvidence", "2"));
    }

    @Test
    @DisplayName("F-001-AC21 --opt 값에 '='가 없거나 키가 비면 사용법 오류(2)로 끝난다")
    void extraOptionUsageErrors() {
        assertThat(run("analyze", project.toString(), "--opt", "novalue")).isEqualTo(2);
        assertThat(err()).contains("novalue");
        assertThat(run("analyze", project.toString(), "--opt", "=v")).isEqualTo(2);
        assertThat(run("analyze", project.toString(), "--opt")).isEqualTo(2);
        assertThat(RecordingAnalyzer.lastOptions).isNull();
    }

    @Test
    @DisplayName("F-001-AC22 --no-export는 출력기를 실행하지 않고 graph.json과 diagnostics.md만 쓴다")
    void noExport() {
        Path outDir = work.resolve("o");

        int exit = run("analyze", project.toString(), "--out", outDir.toString(), "--no-export");

        assertThat(exit).as(err()).isZero();
        assertThat(outDir.resolve("graph.json")).exists();
        assertThat(outDir.resolve("diagnostics.md")).exists();
        assertThat(outDir.resolve("cli-test-exporter.txt")).doesNotExist();
        assertThat(RecordingExporter.lastOptions).isNull();
        assertThat(RecordingAnalyzer.lastOptions).isNotNull();
        assertThat(out()).contains("CodeAtlas 분석 결과");
        assertThat(err()).doesNotContain("출력기");
        assertThat(run("analyze", project.toString(), "--no-export=true")).isEqualTo(2);
    }

    @Test
    @DisplayName("F-001-AC23 --quiet는 진행 메시지와 요약을 찍지 않고 결과 파일은 그대로 쓴다")
    void quiet() {
        Path outDir = work.resolve("o");

        int exit = run("analyze", project.toString(), "--out", outDir.toString(), "--quiet");

        assertThat(exit).isZero();
        assertThat(out()).isEmpty();
        assertThat(err()).isEmpty();
        assertThat(outDir.resolve("graph.json")).exists();
        assertThat(outDir.resolve("diagnostics.md")).exists();
        assertThat(outDir.resolve("cli-test-exporter.txt")).exists();

        // 오류는 --quiet여도 나온다
        assertThat(run("analyze", work.resolve("nope").toString(), "--quiet")).isEqualTo(1);
        assertThat(err()).contains("nope");
    }

    @Test
    @DisplayName("F-001-AC16 --quiet가 없으면 진행 메시지가 표준 오류에 나온다")
    void progressGoesToStderr() {
        assertThat(run("analyze", project.toString(), "--out", work.resolve("o").toString())).isZero();
        assertThat(err()).contains("[codeatlas] ").contains("출력기 cli-test-exporter");
    }

    @Test
    @DisplayName("F-001-AC57 분석 중 힙이 모자라면 스택 대신 단계·구현체·처리 중이던 것과 대처 방법을 표준 오류에 알리고 종료 코드 3으로 끝난다")
    void outOfMemoryDuringAnalysis() {
        RecordingAnalyzer.outOfMemoryWhile = "db/big-data.sql";

        int exit = run("analyze", project.toString(), "--out", work.resolve("o").toString());

        assertThat(exit).isEqualTo(3);
        assertThat(err()).contains("오류: 메모리 부족").contains("분석기").contains("cli-test-recording").contains("db/big-data.sql")
                .contains("--exclude").contains("-Xmx").contains("JAVA_OPTS");
        assertThat(err()).doesNotContain("\tat ").doesNotContain("java.lang.OutOfMemoryError");
        assertThat(out()).doesNotContain("CodeAtlas 분석 결과");

        // --quiet여도 안내는 나온다
        errBytes.reset();
        assertThat(run("analyze", project.toString(), "--out", work.resolve("o").toString(), "--quiet")).isEqualTo(3);
        assertThat(err()).contains("메모리 부족").contains("db/big-data.sql");
    }

    @Test
    @DisplayName("F-001-AC58 파이프라인 밖에서 난 OutOfMemoryError도 스택 없이 안내하고 종료 코드 3으로 끝난다")
    void outOfMemoryOutsidePipeline() {
        Command failing = new Command() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String synopsis() {
                return "codeatlas boom";
            }

            @Override
            public String help() {
                return "";
            }

            @Override
            public int run(String[] args, PrintStream out, PrintStream err) {
                throw new OutOfMemoryError("Java heap space");
            }
        };

        int exit = Main.execute(failing, new String[0], new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(3);
        assertThat(err()).contains("오류: 메모리 부족").contains("Java heap space").contains("-Xmx").contains("JAVA_OPTS");
        assertThat(err()).doesNotContain("\tat ");
    }

    @Test
    @DisplayName("F-001-AC59 --exclude-tests는 어느 깊이에 있든 src/test 폴더를 분석에서 빼고, 주지 않으면 테스트 소스도 분석한다")
    void excludeTests() throws Exception {
        write(project, "src/test/java/com/a/OrderServiceTest.java", "package com.a;\nclass OrderServiceTest {}\n");
        write(project, "module-b/src/test/resources/fixture.sql", "select 1 from dual;\n");
        write(project, "module-b/src/main/java/com/b/test/Keep.java", "package com.b.test;\nclass Keep {}\n");

        assertThat(run("analyze", project.toString(), "--out", work.resolve("all").toString())).as(err()).isZero();
        GraphModel all = GraphJson.read(work.resolve("all/graph.json"));
        assertThat(all.nodes(NodeKind.FILE)).extracting(n -> n.id())
                .contains("file:src/test/java/com/a/OrderServiceTest.java", "file:module-b/src/test/resources/fixture.sql");

        assertThat(run("analyze", project.toString(), "--out", work.resolve("main").toString(), "--exclude-tests")).as(err()).isZero();
        GraphModel main = GraphJson.read(work.resolve("main/graph.json"));
        assertThat(main.nodes(NodeKind.FILE)).extracting(n -> n.id())
                .contains("file:src/main/java/com/a/OrderService.java", "file:module-b/src/main/java/com/b/test/Keep.java")
                .noneMatch(id -> id.contains("src/test/"));
        assertThat(RecordingAnalyzer.lastOptions.excludes()).containsExactly(AnalyzeCommand.TEST_SOURCES_GLOB);
        assertThat(main.project().options().excludes()).containsExactly(AnalyzeCommand.TEST_SOURCES_GLOB);

        assertThat(run("analyze", project.toString(), "--exclude-tests=yes")).isEqualTo(2);
        assertThat(run("help", "analyze")).isZero();
        assertThat(out()).contains("--exclude-tests");
    }

    @Test
    @DisplayName("F-001-AC60 analyze와 summary의 요약 끝에는 해당하는 힌트만 한 줄씩 나오고 해당이 없으면 힌트 절이 없다")
    void hintsAfterSummary() {
        Path outDir = work.resolve("o");
        assertThat(run("analyze", project.toString(), "--out", outDir.toString())).as(err()).isZero();
        assertThat(out()).doesNotContain("힌트");

        RecordingAnalyzer.extra = ctx -> {
            ctx.graph().report(Diagnostic.info("DIALECT_VARIANT_MAPPERS",
                    "방언별 매퍼 2벌 감지(oracle, mysql): oracle 파일을 사용, 제외한 파일 3개.", null));
            for (int i = 0; i < 60; i++) {
                ctx.graph().addUnresolvedEdge("java:type:clitest.Marker", EdgeKind.CALLS, "java-call", "lib.call" + i + "(…)",
                        SourceLocation.of(i < 40 ? "src/test/java/com/a/T.java" : "src/main/java/com/a/M.java", i + 1));
            }
        };
        outBytes.reset();
        assertThat(run("analyze", project.toString(), "--out", outDir.toString())).as(err()).isZero();
        String analyzed = out();
        assertThat(analyzed).contains("\n힌트\n")
                .contains("oracle 파일을 사용").contains("--opt mybatis.dialectVariant=")
                .contains("미해결 Java 호출 60건").contains("--classpath")
                .contains("40건").contains("--exclude-tests");
        assertThat(analyzed.indexOf("힌트")).isGreaterThan(analyzed.indexOf("진단 (총"));

        // summary도 저장된 그래프와 옵션으로 같은 힌트를 낸다
        outBytes.reset();
        assertThat(run("summary", outDir.toString())).isZero();
        assertThat(tables(out())).isEqualTo(tables(analyzed));

        // --classpath를 줬고 테스트를 이미 뺐으면 그 안내는 나오지 않는다. --quiet면 요약도 힌트도 없다
        outBytes.reset();
        assertThat(run("analyze", project.toString(), "--out", outDir.toString(), "--classpath", work.resolve("lib.jar").toString(),
                "--exclude-tests")).as(err()).isZero();
        assertThat(out()).contains("힌트").contains("mybatis.dialectVariant").doesNotContain("--classpath").doesNotContain("--exclude-tests");
        outBytes.reset();
        assertThat(run("analyze", project.toString(), "--out", outDir.toString(), "--quiet")).isZero();
        assertThat(out()).isEmpty();
    }

    private static String tables(String summary) {
        return summary.substring(summary.indexOf("\n파일 (총"));
    }

    @Test
    @DisplayName("F-001-AC24 summary <graph.json>은 저장된 그래프에서 analyze가 찍은 것과 같은 요약 표를 다시 찍는다")
    void summaryOfStoredGraph() {
        Path outDir = work.resolve("o");
        assertThat(run("analyze", project.toString(), "--out", outDir.toString())).isZero();
        String analyzed = out();
        outBytes.reset();
        errBytes.reset();

        int exit = run("summary", outDir.resolve("graph.json").toString());

        assertThat(exit).as(err()).isZero();
        assertThat(err()).isEmpty();
        String summary = out();
        assertThat(summary).startsWith("CodeAtlas 분석 결과: " + project.getFileName());
        assertThat(summary).contains(outDir.resolve("graph.json").toString()).contains(outDir.resolve("diagnostics.md").toString());
        assertThat(summary).containsPattern("파일 \\(총 4\\)").containsPattern("MYBATIS_MAPPER\\s+1\\b").containsPattern("SQL\\s+2\\b");
        assertThat(tables(summary)).isEqualTo(tables(analyzed));

        // 출력 폴더를 줘도 그 안의 graph.json을 읽는다
        outBytes.reset();
        assertThat(run("summary", outDir.toString())).isZero();
        assertThat(tables(out())).isEqualTo(tables(analyzed));
    }

    @Test
    @DisplayName("F-001-AC25 summary는 인자가 없거나 많으면 사용법 오류(2), 파일이 없거나 graph.json이 아니면 실행 실패(1)로 끝난다")
    void summaryErrors() throws IOException {
        assertThat(run("summary")).isEqualTo(2);
        assertThat(run("summary", "a.json", "b.json")).isEqualTo(2);
        assertThat(run("summary", "--bogus")).isEqualTo(2);
        assertThat(err()).contains("codeatlas summary <graph.json>");

        assertThat(run("summary", work.resolve("nope.json").toString())).isEqualTo(1);
        assertThat(err()).contains("nope.json");
        write(work, "bad.json", "{\"schemaVersion\": 99}");
        assertThat(run("summary", work.resolve("bad.json").toString())).isEqualTo(1);
        assertThat(out()).isEmpty();

        assertThat(run("summary", "--help")).isZero();
        assertThat(out()).contains("codeatlas summary <graph.json>");
    }

    @Test
    @DisplayName("하위 명령은 레지스트리에 등록된 순서대로 사용법에 나온다")
    void commandRegistry() {
        assertThat(Main.commands()).extracting(Command::name).startsWith("analyze", "summary");
        assertThat(run("--help")).isZero();
        assertThat(out()).contains("--opt <key>=<value>").contains("--no-export").contains("--quiet").contains("codeatlas summary <graph.json>");
        assertThat(out().indexOf("codeatlas analyze")).isLessThan(out().indexOf("codeatlas summary"));
    }

    /** 방언별 매퍼(aDAO.findList)와 방언별 DDL(TB_A)이 있는 프로젝트. 매퍼는 mapper/A_SQL_<방언>.xml, DDL은 db/<방언>/ddl_<방언>.sql이다. */
    private Path dialectProject(String name, String[] mapperDialects, String[] ddlDialects) throws IOException {
        Path root = work.resolve(name);
        for (String dialect : mapperDialects) {
            write(root, "src/main/resources/mapper/A_SQL_" + dialect + ".xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
                    + "<mapper namespace=\"aDAO\">\n  <select id=\"findList\" resultType=\"map\">\n"
                    + "    SELECT a.ID, a.NM FROM TB_A a WHERE a.NM = #{nm} ORDER BY a.ID\n  </select>\n</mapper>\n");
        }
        for (String dialect : ddlDialects) {
            write(root, "db/" + dialect + "/ddl_" + dialect + ".sql", "CREATE TABLE TB_A (ID VARCHAR(10) NOT NULL, NM VARCHAR(20), PRIMARY KEY (ID));\n");
        }
        return root;
    }

    @Test
    @DisplayName("F-007-AC29 F-009-AC47 F-019-AC4 대상 방언 파일도 프로젝트 설정도 없는 프로젝트에서 mybatis와 sql 분석기가 같은 방언을 고른다: 매퍼·DDL이 mysql·sqlite 두 벌이면 둘 다 mysql, 한쪽에만 있는 방언(cubrid)이 사전순으로 앞서도 양쪽에 다 있는 mysql. 불일치 경고가 없다")
    void mapperAndDdlAgreeOnOtherDialect() throws Exception {
        // PostgreSQL이 대상 방언이 되면서(F-019-R4) "그 밖의 방언"의 예시를 postgres에서 sqlite로 바꿨다. postgres가 낀 조합은 아래 테스트가 본다
        String[][][] projects = {
                {{"mysql", "sqlite"}, {"mysql", "sqlite"}},
                {{"cubrid", "mysql", "sqlite"}, {"mysql", "sqlite"}},
                {{"mysql", "sqlite"}, {"cubrid", "mysql", "sqlite"}}};
        for (int i = 0; i < projects.length; i++) {
            Path root = dialectProject("dialect" + i, projects[i][0], projects[i][1]);
            Path outDir = work.resolve("dialect-out" + i);
            outBytes.reset();
            assertThat(run("analyze", root.toString(), "--out", outDir.toString(), "--no-export")).as(err()).isZero();

            GraphModel graph = GraphJson.read(outDir.resolve("graph.json"));
            assertThat(graph.node("mybatis:stmt:aDAO.findList").orElseThrow().attrString("dialectVariant")).as("매퍼 " + i).isEqualTo("mysql");
            assertThat(graph.node("db:table:TB_A").orElseThrow().location().file()).as("DDL " + i).isEqualTo("db/mysql/ddl_mysql.sql");
            assertThat(out()).as("힌트 " + i).contains("방언별 매퍼").contains("방언별 DDL").doesNotContain("경고: 매퍼는");
        }
    }

    @Test
    @DisplayName("F-019-AC4 mybatis와 sql 분석기가 PostgreSQL을 같은 순위로 고른다: (가) oracle·postgres면 둘 다 oracle (다) postgres·tibero면 둘 다 postgres "
            + "(라) postgres·mysql이면 둘 다 postgres (사) oracle·postgres에 --dialect postgres면 둘 다 postgres. 대상 방언을 골랐으므로 주의(WARN)도 불일치 경고도 없다")
    void mapperAndDdlAgreeOnPostgres() throws Exception {
        // {매퍼·DDL의 방언들, 고를 방언, --dialect 값(없으면 null)}
        String[][][] cases = {
                {{"oracle", "postgres"}, {"oracle"}, {null}},
                {{"postgres", "tibero"}, {"postgres"}, {null}},
                {{"mysql", "postgres"}, {"postgres"}, {null}},
                {{"oracle", "postgres"}, {"postgres"}, {"postgres"}}};
        for (int i = 0; i < cases.length; i++) {
            String expected = cases[i][1][0];
            String option = cases[i][2][0];
            Path root = dialectProject("pg" + i, cases[i][0], cases[i][0]);
            Path outDir = work.resolve("pg-out" + i);
            outBytes.reset();
            int exit = option == null ? run("analyze", root.toString(), "--out", outDir.toString(), "--no-export")
                    : run("analyze", root.toString(), "--out", outDir.toString(), "--no-export", "--dialect", option);
            assertThat(exit).as(err()).isZero();

            GraphModel graph = GraphJson.read(outDir.resolve("graph.json"));
            assertThat(graph.node("mybatis:stmt:aDAO.findList").orElseThrow().attrString("dialectVariant")).as("매퍼 " + i).isEqualTo(expected);
            assertThat(graph.node("db:table:TB_A").orElseThrow().location().file()).as("DDL " + i)
                    .isEqualTo("db/" + expected + "/ddl_" + expected + ".sql");
            assertThat(graph.diagnostics()).as("진단 " + i)
                    .filteredOn(d -> d.code().startsWith("DIALECT_")).isNotEmpty()
                    .noneMatch(d -> d.severity() == io.codeatlas.core.model.Severity.WARN);
            assertThat(out()).as("힌트 " + i).contains("방언별 매퍼").contains("방언별 DDL").doesNotContain("경고: 매퍼는").doesNotContain("주의: ");
        }
    }
}
