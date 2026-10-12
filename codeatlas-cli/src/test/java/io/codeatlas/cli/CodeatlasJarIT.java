package io.codeatlas.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Node;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 패키징된 실행 jar를 별도 프로세스로 돌려 보는 통합 테스트. package 단계 뒤(failsafe)에 실행된다. */
class CodeatlasJarIT {

    @TempDir
    Path project;
    @TempDir
    Path out;

    private Path jar;

    @BeforeEach
    void setUp() throws IOException {
        jar = Path.of(System.getProperty("codeatlas.jar", "target/codeatlas.jar")).toAbsolutePath();
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/A.java"), "class A {}\n");
        Files.writeString(project.resolve("src/q.sql"), "select 1 from dual;\n");
    }

    private record Result(int exit, String output) {
    }

    /**
     * 실행 jar를 돌리는 명령. exec는 출력을 UTF-8로 읽으므로 자식 JVM의 표준 출력·오류도 UTF-8로 맞춘다.
     * 맞추지 않으면 기본값이 플랫폼 인코딩(한국어 Windows는 MS949, POSIX 로캘은 ASCII)이라 한글이 깨진다.
     */
    private List<String> javaJar(String... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", jar.toString()));
        command.addAll(List.of(args));
        return command;
    }

    private Result exec(List<String> command) throws Exception {
        return exec(null, command);
    }

    /** workingDir이 null이 아니면 그 폴더에서 실행한다. */
    private Result exec(Path workingDir, List<String> command) throws Exception {
        return exec(workingDir, Map.of(), command);
    }

    /** environment의 변수를 더해(같은 이름은 덮어써) 실행한다. */
    private Result exec(Path workingDir, Map<String, String> environment, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // 이 변수가 있으면 JVM이 "Picked up ..." 줄을 출력 앞에 붙여 출력 검사가 실행 환경에 따라 달라진다
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        builder.environment().putAll(environment);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), output);
    }

    @Test
    @DisplayName("F-001-AC18 java -jar codeatlas.jar analyze가 다른 classpath 없이 실행되어 graph.json을 만든다")
    void runsAsSingleJar() throws Exception {
        assertThat(jar).exists();
        Result result = exec(javaJar("analyze", project.toString(), "--out", out.toString()));

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).contains("CodeAtlas 분석 결과").containsPattern("JAVA\\s+1\\b");
        GraphModel graph = GraphJson.read(out.resolve("graph.json"));
        assertThat(graph.nodes(NodeKind.FILE)).extracting(n -> n.id()).contains("file:src/A.java", "file:src/q.sql");
        assertThat(out.resolve("diagnostics.md")).exists();
    }

    @Test
    @DisplayName("F-001-AC18 실행 jar에는 서명 파일이 없고 Main-Class가 들어 있다")
    void jarLayout() throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            assertThat(file.getManifest().getMainAttributes().getValue("Main-Class")).isEqualTo("io.codeatlas.cli.Main");
            List<String> signatures = new ArrayList<>();
            file.stream().map(e -> e.getName()).filter(n -> n.matches("META-INF/[^/]+\\.(SF|DSA|RSA|EC)")).forEach(signatures::add);
            assertThat(signatures).isEmpty();
            assertThat(file.getEntry("io/codeatlas/core/pipeline/Pipeline.class")).isNotNull();
            assertThat(file.getEntry("com/fasterxml/jackson/databind/ObjectMapper.class")).isNotNull();
        }
    }

    @Test
    @DisplayName("F-001-AC18 bin/codeatlas 스크립트로도 실행된다")
    void runsThroughScript() throws Exception {
        Path script = Path.of(System.getProperty("codeatlas.script", "../bin/codeatlas")).toAbsolutePath().normalize();
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "유닉스 셸 스크립트");
        assertThat(script).exists().isExecutable();

        Result result = exec(List.of(script.toString(), "analyze", project.toString(), "--out", out.toString()));

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(out.resolve("graph.json")).exists();
    }

    @Test
    @DisplayName("F-001-AC62 bin/codeatlas는 JAVA_OPTS 환경 변수의 JVM 옵션(-Xmx 등)을 java에 넘긴다")
    void scriptPassesJavaOpts() throws Exception {
        Path script = Path.of(System.getProperty("codeatlas.script", "../bin/codeatlas")).toAbsolutePath().normalize();
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "유닉스 셸 스크립트");

        // -XX:+PrintCommandLineFlags는 JVM이 받은 플래그를 표준 출력에 찍는다. -Xmx256m은 MaxHeapSize=268435456으로 나온다.
        Result result = exec(null, Map.of("JAVA_OPTS", "-Xmx256m -XX:+PrintCommandLineFlags"), List.of(script.toString(), "--version"));

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).contains("-XX:MaxHeapSize=268435456").contains("codeatlas ");

        Result plain = exec(null, Map.of("JAVA_OPTS", ""), List.of(script.toString(), "--version"));
        assertThat(plain.exit()).as(plain.output()).isZero();
        assertThat(plain.output()).doesNotContain("MaxHeapSize=268435456");
    }

    @Test
    @DisplayName("F-001-AC63 실행 jar로 샘플 프로젝트를 분석하면 표준 산출물이 지금의 규칙대로 나오고, export로 엔드포인트 콜트리를 컨트롤러별로 나눌 수 있다")
    void sampleOutputsFollowCurrentRules() throws Exception {
        Path sample = Path.of(System.getProperty("sample.project", "../samples/sample-shop")).toAbsolutePath().normalize();
        assertThat(sample).isDirectory();

        Result analyzed = cli(null, "analyze", sample.toString(), "--out", out.toString());

        assertThat(analyzed.exit()).as(analyzed.output()).isZero();
        assertThat(analyzed.output()).contains("CodeAtlas 분석 결과").doesNotContain("EXPORTER_FAILED");
        for (String file : List.of("graph.json", "diagnostics.md", "report.md", "viewer.html", "erd/erd.mmd", "erd/tables.md",
                "class-diagram/index.md", "crud/table-endpoint.csv", "calltree/endpoints.md", "calltree/batch-jobs.md")) {
            assertThat(out.resolve(file)).as(file).isNotEmptyFile();
        }
        String endpoints = Files.readString(out.resolve("calltree/endpoints.md"), StandardCharsets.UTF_8);
        // 깊이 20, 트리당 500개, 화면 이동은 펼치지 않음, 새 표식의 범례
        assertThat(endpoints).startsWith("# 엔드포인트 콜트리").contains("깊이 20, 트리당 노드 500개까지")
                .contains("`[reflect]`").contains("`[before]`").contains("export.calltree.expandViews=true").contains("[table]");
        assertThat(out.resolve("calltree/endpoints")).doesNotExist();

        // 엔드포인트가 기준을 넘으면 컨트롤러별 파일로 나뉘고 endpoints.md는 색인이 된다(기준을 1로 낮춰 확인한다)
        Path split = out.resolve("split");
        Result exported = cli(null, "export", out.toString(), "--out", split.toString(), "--opt", "export.calltree.splitOver=1");
        assertThat(exported.exit()).as(exported.output()).isZero();
        assertThat(Files.readString(split.resolve("calltree/endpoints.md"), StandardCharsets.UTF_8))
                .contains("export.calltree.splitOver").contains("이 파일은 색인이다").contains("| 엔드포인트 | 파일 | 닿는 테이블 |");
        try (var files = Files.list(split.resolve("calltree/endpoints"))) {
            assertThat(files.filter(f -> f.getFileName().toString().endsWith(".md")).count()).isGreaterThan(1);
        }
    }

    @Test
    @DisplayName("F-001-AC26 실행 jar에는 tree-sitter 바인딩 jar들의 네이티브 라이브러리(lib/)가 빠짐없이 들어 있고 매니페스트가 네이티브 접근을 허용한다")
    void jarKeepsNativeLibraries() throws Exception {
        // 테스트 classpath에 있는 tree-sitter 바인딩 jar(web 모듈의 의존)에서 lib/ 리소스 목록을 얻어 실행 jar와 맞춰 본다.
        List<Path> bindingJars = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry);
            if (path.getFileName().toString().matches("tree-sitter.*\\.jar")) {
                bindingJars.add(path);
            }
        }
        assertThat(bindingJars).as("classpath의 tree-sitter 바인딩 jar").hasSizeGreaterThanOrEqualTo(4);

        try (JarFile shaded = new JarFile(jar.toFile())) {
            int libraries = 0;
            for (Path bindingJar : bindingJars) {
                try (JarFile binding = new JarFile(bindingJar.toFile())) {
                    List<JarEntry> natives = binding.stream()
                            .filter(e -> e.getName().matches("lib/[^/]+\\.(dylib|so|dll)")).toList();
                    assertThat(natives).as("%s의 네이티브 라이브러리", bindingJar.getFileName()).isNotEmpty();
                    for (JarEntry library : natives) {
                        JarEntry packaged = shaded.getJarEntry(library.getName());
                        assertThat(packaged).as(library.getName()).isNotNull();
                        assertThat(packaged.getSize()).as(library.getName()).isEqualTo(library.getSize());
                        libraries++;
                    }
                }
            }
            assertThat(libraries).isGreaterThanOrEqualTo(4);
            assertThat(shaded.getManifest().getMainAttributes().getValue("Enable-Native-Access")).isEqualTo("ALL-UNNAMED");
        }
    }

    @Test
    @DisplayName("F-001-AC27 실행 jar 하나로 JS와 TSX 파일을 분석해 JS_FUNCTION 노드가 나오고 네이티브 접근 경고가 없다")
    void analyzesScriptsFromSingleJar() throws Exception {
        Files.createDirectories(project.resolve("web"));
        Files.writeString(project.resolve("web/app.js"), "function loadOrders() {\n  return fetch('/api/orders');\n}\n");
        Files.writeString(project.resolve("web/Badge.tsx"),
                "export function Badge(props: { label: string }) {\n  return <span>{props.label}</span>;\n}\n");
        Files.writeString(project.resolve("web/util.ts"), "export function double(x: number): number {\n  return x * 2;\n}\n");
        Result result = exec(javaJar("analyze", project.toString(), "--out", out.toString()));

        assertThat(result.exit()).as(result.output()).isZero();
        GraphModel graph = GraphJson.read(out.resolve("graph.json"));
        assertThat(graph.nodes(NodeKind.JS_FUNCTION)).extracting(Node::id)
                .contains("js:function:web/app.js#loadOrders", "js:function:web/Badge.tsx#Badge", "js:function:web/util.ts#double");
        assertThat(graph.diagnostics()).extracting(d -> d.code()).doesNotContain("ANALYZER_FAILED", "SERVICE_LOAD_FAILED");
        // JDK 24+: 네이티브 접근이 허용되지 않은 채 System.load를 부르면 표준 오류에 restricted method 경고가 나온다.
        assertThat(result.output().toLowerCase(Locale.ROOT)).doesNotContain("restricted method").doesNotContain("enable-native-access");
    }

    @Test
    @DisplayName("F-001-AC27 bin/codeatlas 스크립트로 실행해도 네이티브 접근 경고가 없다")
    void scriptRunsWithoutNativeAccessWarning() throws Exception {
        Path script = Path.of(System.getProperty("codeatlas.script", "../bin/codeatlas")).toAbsolutePath().normalize();
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "유닉스 셸 스크립트");
        Files.writeString(project.resolve("src/app.js"), "function start() {}\n");

        Result result = exec(List.of(script.toString(), "analyze", project.toString(), "--out", out.toString()));

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(GraphJson.read(out.resolve("graph.json")).nodes(NodeKind.JS_FUNCTION)).extracting(Node::id).contains("js:function:src/app.js#start");
        assertThat(result.output().toLowerCase(Locale.ROOT)).doesNotContain("restricted method").doesNotContain("enable-native-access");
    }

    private static final Pattern INLINE_BODY = Pattern.compile("(<(script|style)\\b[^>]*>).*?(</\\2\\s*>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EXTERNAL_REFERENCE = Pattern.compile(
            "<(?:script|link)\\b[^>]*?\\b(?:src|href)\\s*=\\s*[\"']?\\s*(?:[a-z][a-z0-9+.-]*:)?//[^\\s\"'>]*", Pattern.CASE_INSENSITIVE);

    /** HTML에서 외부 URL(스킴이 있거나 //로 시작)을 가리키는 script, link 태그. 인라인 script·style의 본문은 보지 않는다. */
    static List<String> externalReferences(String html) {
        String tagsOnly = INLINE_BODY.matcher(html).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(3)));
        List<String> found = new ArrayList<>();
        Matcher matcher = EXTERNAL_REFERENCE.matcher(tagsOnly);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    @DisplayName("F-001-AC28 외부 참조 검사는 script src와 link href의 외부 URL만 잡는다")
    void externalReferenceDetection() {
        assertThat(externalReferences("<html><script src=\"https://cdn.example.com/mermaid.js\"></script>"
                + "<LINK rel=stylesheet href='//fonts.example.com/a.css'><script src=app.js></script></html>")).hasSize(2);
        assertThat(externalReferences("<script>var s = '<script src=\"https://x.example/a.js\">';</script>"
                + "<style>@import url(https://x.example/a.css);</style><a href=\"https://x.example\">링크</a><link rel=\"icon\" href=\"data:,\">")).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC28 실행 jar로 analyze하면 출력 폴더에 viewer.html이 생기고 외부 URL을 가리키는 script, link 태그가 없다")
    void writesSelfContainedViewer() throws Exception {
        Result result = exec(javaJar("analyze", project.toString(), "--out", out.toString()));

        assertThat(result.exit()).as(result.output()).isZero();
        Path viewer = out.resolve("viewer.html");
        assertThat(viewer).exists();
        String html = Files.readString(viewer, StandardCharsets.UTF_8);
        assertThat(html).containsIgnoringCase("<script").contains("file:src/A.java");
        assertThat(externalReferences(html)).isEmpty();
        assertThat(GraphJson.read(out.resolve("graph.json")).diagnostics()).extracting(d -> d.code()).doesNotContain("EXPORTER_FAILED");
    }

    // ---------------------------------------------------------------- 조회용 하위 명령 스모크

    /** 실행 jar로 하위 명령을 돌린다. 표준 출력과 표준 오류를 합쳐 돌려준다. */
    private Result cli(Path workingDir, String... args) throws Exception {
        return exec(workingDir, javaJar(args));
    }

    /** 테스트용 주문 그래프를 graph.json으로 저장한 출력 폴더. */
    private Path storedGraph() throws IOException {
        Path dir = out.resolve("codeatlas-out");
        ShopGraphFixture.save(ShopGraphFixture.shop(), dir);
        return dir;
    }

    @Test
    @DisplayName("F-001-AC32 실행 jar의 erd가 저장된 그래프에서 Mermaid ERD를 낸다")
    void erdFromJar() throws Exception {
        Result result = cli(null, "erd", storedGraph().toString());

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("erDiagram").contains("ORDERS").contains("CUSTOMER");
    }

    @Test
    @DisplayName("F-001-AC34 실행 jar의 classdiagram이 패키지를 골라 PlantUML 클래스 다이어그램을 낸다")
    void classDiagramFromJar() throws Exception {
        Result result = cli(null, "classdiagram", storedGraph().toString(), "--package", "com.shop.order", "--format", "plantuml");

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("@startuml").contains("OrderService").doesNotContain("AuditHelper");
    }

    @Test
    @DisplayName("F-001-AC36 실행 jar의 calltree가 엔드포인트에서 테이블까지 이어진 텍스트 트리를 낸다")
    void callTreeFromJar() throws Exception {
        Result result = cli(null, "calltree", "GET /orders/{id}", "--graph", storedGraph().toString());

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).contains("OrderController#detail").contains("selectOrder").contains("ORDERS");
    }

    @Test
    @DisplayName("F-001-AC38 실행 jar의 impact가 컬럼의 영향도 리포트를 낸다")
    void impactFromJar() throws Exception {
        Result result = cli(null, "impact", "ORDERS.STATUS", "--graph", storedGraph().toString());

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("# 영향도: ORDERS.STATUS").contains("selectOrder").contains("GET /orders/{id}");
    }

    @Test
    @DisplayName("F-001-AC39 실행 jar의 crud가 메서드별 CRUD 매트릭스를 Markdown 표로 낸다")
    void crudFromJar() throws Exception {
        Result result = cli(null, "crud", storedGraph().toString(), "--by", "method", "--format", "md");

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("|").contains("OrderMapper").contains("ORDERS");
    }

    @Test
    @DisplayName("F-001-AC40 실행 jar의 find가 후보 노드 목록을 낸다")
    void findFromJar() throws Exception {
        Result result = cli(null, "find", "find", "--graph", storedGraph().toString());

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).contains(ShopGraphFixture.SERVICE_FIND).contains(ShopGraphFixture.AUDIT_FIND).contains("JAVA_METHOD");
    }

    @Test
    @DisplayName("F-001-AC41 F-001-AC29 실행 jar의 report는 그래프 경로를 주지 않으면 현재 폴더의 codeatlas-out을 읽는다")
    void reportFromJarUsesDefaultFolder() throws Exception {
        Path dir = storedGraph();

        Result result = cli(dir.getParent(), "report");

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("# CodeAtlas 분석 요약");

        // codeatlas-out이 없는 폴더에서는 이유를 알리고 1로 끝난다
        Result missing = cli(project, "report");
        assertThat(missing.exit()).as(missing.output()).isEqualTo(1);
        assertThat(missing.output()).contains("codeatlas-out").doesNotContain("Exception");
    }

    @Test
    @DisplayName("F-001-AC42 실행 jar의 export가 저장된 그래프로 표준 산출물과 viewer.html을 다시 만든다")
    void exportFromJar() throws Exception {
        Path target = out.resolve("regen");

        Result result = cli(null, "export", storedGraph().toString(), "--out", target.toString(), "--opt", "export.crud=false");

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(target.resolve("graph.json")).exists();
        assertThat(target.resolve("diagnostics.md")).exists();
        assertThat(target.resolve("report.md")).exists();
        assertThat(target.resolve("erd/erd.mmd")).exists();
        assertThat(target.resolve("viewer.html")).exists();
        assertThat(target.resolve("crud")).doesNotExist();
        assertThat(result.output()).doesNotContain("EXPORTER_FAILED").doesNotContain("Exception");
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 방언별 매퍼 두 벌(oracle, mysql)이 있는 작은 프로젝트. DDL은 준 방언들의 폴더(db/<방언>/ddl_<방언>.sql)에 둔다. */
    private Path dialectProject(String name, String... ddlDialects) throws IOException {
        Path root = project.resolve(name);
        String head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n<mapper namespace=\"aDAO\">\n  <select id=\"findList\" resultType=\"map\">\n";
        String tail = "\n  </select>\n</mapper>\n";
        write(root, "src/main/resources/mapper/A_SQL_oracle.xml",
                head + "    SELECT a.ID, a.NM FROM TB_A a WHERE a.NM = #{nm} AND ROWNUM &lt;= 10 ORDER BY a.ID" + tail);
        write(root, "src/main/resources/mapper/A_SQL_mysql.xml",
                head + "    SELECT a.ID, a.NM FROM TB_A a WHERE a.NM = #{nm} ORDER BY a.ID LIMIT 10" + tail);
        for (String dialect : ddlDialects) {
            String varchar = dialect.equals("oracle") ? "VARCHAR2" : "VARCHAR";
            write(root, "db/" + dialect + "/ddl_" + dialect + ".sql", "CREATE TABLE TB_A (ID " + varchar + "(10) NOT NULL, NM " + varchar
                    + "(20), PRIMARY KEY (ID));\nCREATE TABLE TB_B (ID " + varchar + "(10) NOT NULL, A_ID " + varchar + "(10));\n");
        }
        return root;
    }

    /** 분석한 그래프에서 읽은 (매퍼 구문의 dialectVariant, 테이블 TB_A를 정의한 파일의 방언 폴더). */
    private static String[] chosenDialects(Path outDir) throws IOException {
        GraphModel graph = GraphJson.read(outDir.resolve("graph.json"));
        Node statement = graph.node("mybatis:stmt:aDAO.findList").orElseThrow();
        Node table = graph.node("db:table:TB_A").orElseThrow();
        String file = table.location().file();
        assertThat(file).matches("db/[a-z0-9]+/ddl_[a-z0-9]+\\.sql");
        return new String[] {statement.attrString("dialectVariant"), file.split("/")[1]};
    }

    @Test
    @DisplayName("F-001-AC72 실행 jar: 매퍼·DDL 방언 불일치 경고는 진단 문구가 아니라 그래프(구문의 dialectVariant, 테이블 정의 파일의 경로)를 따른다")
    void dialectMismatchHintFollowsGraph() throws Exception {
        // 매퍼는 방언별 두 벌 가운데 oracle, DDL은 mysql 한 벌뿐: 방언별 DDL 진단(DIALECT_VARIANT_DDL)이 없어도 경고가 나온다
        Path mismatchOut = out.resolve("mismatch");
        Result mismatch = cli(null, "analyze", dialectProject("mismatch", "mysql").toString(), "--out", mismatchOut.toString(),
                "--opt", "mybatis.dialectVariant=oracle");
        assertThat(mismatch.exit()).as(mismatch.output()).isZero();
        assertThat(chosenDialects(mismatchOut)).containsExactly("oracle", "mysql");
        assertThat(mismatch.output()).contains("힌트").contains("경고: 매퍼는 oracle, DDL은 mysql");
        Result summary = cli(null, "summary", mismatchOut.toString());
        assertThat(summary.output()).contains("경고: 매퍼는 oracle, DDL은 mysql");

        // 재현 p3: 프로젝트 설정 없이 매퍼와 DDL이 모두 방언별 여러 벌이면 양쪽 다 oracle을 골라 경고가 없다
        Path p3Out = out.resolve("p3");
        Result p3 = cli(null, "analyze", dialectProject("p3", "oracle", "mysql", "altibase").toString(), "--out", p3Out.toString());
        assertThat(p3.exit()).as(p3.output()).isZero();
        assertThat(chosenDialects(p3Out)).containsExactly("oracle", "oracle");
        assertThat(p3.output()).contains("방언별 매퍼").contains("방언별 DDL").doesNotContain("경고: 매퍼는");

        // 재현 c1: 프로젝트 설정(Globals.DbType=mysql)이 대상 외 방언이어도 대상 방언 파일이 있으면 양쪽 다 oracle을 고른다.
        // 그 사실은 INFO로 알리므로 "주의:"가 없고, 방언이 같으니 불일치 경고도 없다
        Path c1 = dialectProject("c1", "oracle", "mysql");
        write(c1, "src/main/resources/globals.properties", "Globals.DbType = mysql\n");
        Path c1Out = out.resolve("c1");
        Result c1Result = cli(null, "analyze", c1.toString(), "--out", c1Out.toString());
        assertThat(c1Result.exit()).as(c1Result.output()).isZero();
        assertThat(chosenDialects(c1Out)).containsExactly("oracle", "oracle");
        assertThat(c1Result.output()).contains("방언별 매퍼").contains("방언별 DDL").contains("Globals.DbType=mysql")
                .doesNotContain("주의:").doesNotContain("경고: 매퍼는");
        assertThat(GraphJson.read(c1Out.resolve("graph.json")).diagnostics()).filteredOn(d -> d.code().startsWith("DIALECT_"))
                .isNotEmpty().allMatch(d -> d.severity() == io.codeatlas.core.model.Severity.INFO);

        // 설정대로(대상 외 방언으로) 분석하려면 --opt mybatis.dialectVariant: 매퍼와 DDL 둘 다 이 값을 읽는다. --dialect mysql은 옵션 오류다
        Path c1MysqlOut = out.resolve("c1-mysql");
        Result c1Mysql = cli(null, "analyze", c1.toString(), "--out", c1MysqlOut.toString(), "--opt", "mybatis.dialectVariant=mysql");
        assertThat(c1Mysql.exit()).as(c1Mysql.output()).isZero();
        assertThat(chosenDialects(c1MysqlOut)).containsExactly("mysql", "mysql");
        assertThat(c1Mysql.output()).doesNotContain("경고: 매퍼는");
        Result rejected = cli(null, "analyze", c1.toString(), "--out", out.resolve("c1-rejected").toString(), "--dialect", "mysql");
        assertThat(rejected.exit()).as(rejected.output()).isEqualTo(2);
        assertThat(rejected.output()).contains("mybatis.dialectVariant");
    }

    @Test
    @DisplayName("F-001-AC73 실행 jar: 방언별 여러 벌이 아닌데 SQL이 대상 외 방언(mysql)으로 보이는 프로젝트는 힌트 한 줄(DIALECT_NOT_TARGET)로 알린다")
    void notTargetDialectHint() throws Exception {
        Path root = project.resolve("not-target");
        write(root, "db/mysql/schema.sql", "CREATE TABLE `tb_a` (\n  `id` int(11) NOT NULL AUTO_INCREMENT,\n  `nm` varchar(50) DEFAULT NULL,\n"
                + "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");
        write(root, "src/main/resources/mapper/A.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE mapper PUBLIC "
                + "\"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n<mapper namespace=\"aDAO\">\n"
                + "  <select id=\"findList\" resultType=\"map\">SELECT a.id, a.nm FROM tb_a a ORDER BY a.id LIMIT 10</select>\n</mapper>\n");
        Path target = out.resolve("not-target");

        Result result = cli(null, "analyze", root.toString(), "--out", target.toString());

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(GraphJson.read(target.resolve("graph.json")).diagnostics()).extracting(d -> d.code()).contains("DIALECT_NOT_TARGET");
        assertThat(result.output()).contains("힌트");
        String hints = result.output().substring(result.output().indexOf("힌트"));
        assertThat(hints).contains("mysql").contains("대상 방언");
    }

    @Test
    @DisplayName("F-001-AC31 실행 jar의 help <명령>이 그 명령의 사용법을 낸다")
    void commandHelpFromJar() throws Exception {
        Result result = cli(null, "help", "calltree");

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("사용법:").contains("codeatlas calltree").contains("--callers").doesNotContain("codeatlas analyze");
    }
}
