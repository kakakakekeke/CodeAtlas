package io.codeatlas.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.Exporter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewerExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SCRIPT = Pattern.compile("<script\\b([^>]*)>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    @TempDir
    Path out;

    private static GraphModel smallGraph() {
        GraphModel graph = new GraphModel();
        graph.project(new ProjectInfo("demo", "/work/demo", Instant.parse("2026-10-11T00:00:00Z")));
        graph.declare(Node.builder("java:type:com.a.OrderService", NodeKind.JAVA_TYPE)
                .name("OrderService").qualifiedName("com.a.OrderService")
                .location(SourceLocation.of("src/main/java/com/a/OrderService.java", 5, 1, 40, 2))
                .attr("typeKind", "class").attr("modifiers", List.of("public")).build());
        graph.declare(Node.builder("java:method:com.a.OrderService#save(java.lang.String)", NodeKind.JAVA_METHOD)
                .name("save").qualifiedName("com.a.OrderService#save(String)")
                .location(SourceLocation.of("src/main/java/com/a/OrderService.java", 10))
                .attr("returnType", "void").build());
        graph.declare(Node.builder("mybatis:stmt:com.a.OrderMapper.insert", NodeKind.SQL_STATEMENT)
                .name("insert").qualifiedName("com.a.OrderMapper.insert")
                .attr("statementType", "INSERT").attr("sql", "INSERT INTO ORDERS (ID) VALUES (?)").attr("dynamic", false).build());
        graph.reference("db:table:ORDERS", NodeKind.DB_TABLE, "ORDERS");
        graph.addEdge(Edge.of("java:type:com.a.OrderService", "java:method:com.a.OrderService#save(java.lang.String)", EdgeKind.CONTAINS));
        graph.addEdge(Edge.builder("java:method:com.a.OrderService#save(java.lang.String)", "mybatis:stmt:com.a.OrderMapper.insert", EdgeKind.EXECUTES)
                .location(SourceLocation.of("src/main/java/com/a/OrderService.java", 12)).build());
        graph.addEdge(Edge.builder("mybatis:stmt:com.a.OrderMapper.insert", "db:table:ORDERS", EdgeKind.WRITES).attr("op", "INSERT").build());
        graph.addEdge(Edge.builder("db:table:ORDERS", "db:table:CUSTOMERS", EdgeKind.REFERENCES).inferred()
                .attr("source", "INFERRED").attr("columns", List.of("CUSTOMER_ID")).attr("evidenceCount", 2).build());
        graph.report(Diagnostic.warn("UNRESOLVED_REFERENCE", "x.y 를 찾지 못했다", SourceLocation.of("a/B.java", 3)));
        graph.report(Diagnostic.error("PARSE_FAILED", "깨진 파일", null));
        graph.resolveDanglingEdges();
        return graph;
    }

    private static String export(GraphModel graph, Path dir) throws Exception {
        new ViewerExporter().export(graph, dir, Map.of());
        return Files.readString(dir.resolve("viewer.html"), StandardCharsets.UTF_8);
    }

    /** id가 같은 script 블록의 본문. 정규식이 첫 번째 종료 태그에서 끊으므로, 데이터가 문서를 깨면 JSON 해석이 실패한다. */
    private static String scriptBody(String html, String id) {
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            if (m.group(1).contains("id=\"" + id + "\"")) {
                return m.group(2);
            }
        }
        throw new AssertionError("script 블록이 없음: " + id);
    }

    private static JsonNode graphJson(GraphModel graph) throws IOException {
        StringWriter w = new StringWriter();
        GraphJson.write(graph, w);
        return MAPPER.readTree(w.toString());
    }

    @Test
    @DisplayName("F-016-AC1 viewer.html을 만들고, 내장 데이터가 graph.json과 같다")
    void exportsSingleHtmlWithEmbeddedGraph() throws Exception {
        GraphModel graph = smallGraph();

        String html = export(graph, out);

        assertThat(out.resolve("viewer.html")).isRegularFile();
        assertThat(html).startsWith("<!DOCTYPE html>").contains("<meta charset=\"utf-8\">");
        JsonNode embedded = MAPPER.readTree(scriptBody(html, "codeatlas-data"));
        assertThat(embedded).isEqualTo(graphJson(graph));
        assertThat(embedded.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(embedded.path("project").path("name").asText()).isEqualTo("demo");
        assertThat(embedded.path("nodes")).hasSize(graph.nodeCount());
        assertThat(embedded.path("edges")).hasSize(graph.edgeCount());
        assertThat(embedded.path("diagnostics")).hasSize(graph.diagnostics().size());
        assertThat(embedded.path("diagnostics").findValuesAsText("code")).contains("UNRESOLVED_REFERENCE", "PARSE_FAILED");
    }

    @Test
    @DisplayName("F-016-AC1 출력 폴더가 없으면 만들고, 이미 있는 viewer.html은 덮어쓴다")
    void createsDirectoryAndOverwrites() throws Exception {
        Path dir = out.resolve("nested/out");
        export(smallGraph(), dir);
        GraphModel second = new GraphModel();
        second.declare(Node.of("db:table:ONLY", NodeKind.DB_TABLE, "ONLY"));

        String html = export(second, dir);

        JsonNode embedded = MAPPER.readTree(scriptBody(html, "codeatlas-data"));
        assertThat(embedded.path("nodes")).hasSize(1);
        assertThat(embedded.path("nodes").get(0).path("id").asText()).isEqualTo("db:table:ONLY");
    }

    @Test
    @DisplayName("F-016-AC1 ServiceLoader로 viewer 출력기를 찾는다")
    void registeredAsService() {
        List<String> names = ServiceLoader.load(Exporter.class).stream().map(p -> p.get().name()).toList();
        assertThat(names).contains("viewer");
        assertThat(new ViewerExporter().name()).isEqualTo("viewer");
    }

    @Test
    @DisplayName("F-016-AC2 외부 주소를 참조하는 태그와 스타일이 없다")
    void noExternalReferences() throws Exception {
        String html = export(smallGraph(), out);

        String markup = SCRIPT.matcher(html).replaceAll("<script></script>");
        assertThat(markup).doesNotContainPattern("(?i)\\b(src|href|action|data|poster)\\s*=\\s*[\"']?\\s*(https?:)?//");
        assertThat(markup).doesNotContainPattern("(?i)<(link|iframe|img|object|embed|base)\\b");
        assertThat(markup).doesNotContainPattern("(?i)@import|url\\(");
        Matcher m = SCRIPT.matcher(html);
        int scripts = 0;
        while (m.find()) {
            scripts++;
            assertThat(m.group(1)).as("script 태그는 외부 파일을 가리키지 않는다").doesNotContainPattern("(?i)\\bsrc\\s*=");
        }
        assertThat(scripts).isEqualTo(3);
        assertThat(html).doesNotContain("@@DATA@@", "@@MERMAID@@", "/*@@JS@@*/", "/*@@CSS@@*/");
    }

    @Test
    @DisplayName("F-016-AC2 Mermaid가 파일 안에 들어 있다")
    void embedsMermaid() throws Exception {
        String html = export(smallGraph(), out);

        String body = scriptBody(html, "codeatlas-vendor-mermaid");
        JsonNode source = MAPPER.readTree(body);
        assertThat(source.isTextual()).isTrue();
        assertThat(source.asText().length()).isGreaterThan(1_000_000);
        assertThat(source.asText()).contains("globalThis[\"mermaid\"]").contains("erDiagram").contains("classDiagram");
        // 번들 원본과 글자 하나까지 같다
        try (var in = ViewerExporter.class.getResourceAsStream("vendor/mermaid.min.js")) {
            assertThat(in).as("빌드가 Mermaid를 클래스패스에 풀어 두었다").isNotNull();
            assertThat(source.asText()).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("F-016-AC3 데이터에 </script>, <!--, U+2028, 자리 표시 글자가 있어도 깨지지 않고 원래 값이 나온다")
    void survivesHostileData() throws Exception {
        String nasty = "</script><script>alert(1)</script><!-- </SCRIPT > ]]> \u2028\u2029 \\u003c \"q\" @@MERMAID@@ /*@@JS@@*/ 한글";
        GraphModel graph = smallGraph();
        graph.declare(Node.builder("unresolved:test:" + nasty, NodeKind.UNRESOLVED)
                .name(nasty).qualifiedName(nasty).attr("sql", nasty).attr("list", List.of(nasty, "</style>")).build());
        graph.report(Diagnostic.info("DYNAMIC_SQL", nasty, SourceLocation.ofFile(nasty)));

        String html = export(graph, out);

        JsonNode embedded = MAPPER.readTree(scriptBody(html, "codeatlas-data"));
        assertThat(embedded).isEqualTo(graphJson(graph));
        JsonNode node = null;
        for (JsonNode n : embedded.path("nodes")) {
            if (n.path("kind").asText().equals("UNRESOLVED") && n.path("name").asText().equals(nasty)) {
                node = n;
            }
        }
        assertThat(node).isNotNull();
        assertThat(node.path("attrs").path("sql").asText()).isEqualTo(nasty);
        // 문서 전체에 종료 태그는 script 블록 세 개의 것뿐이다
        assertThat(countOccurrences(html.toLowerCase(), "</script")).isEqualTo(3);
        assertThat(countOccurrences(html.toLowerCase(), "</style")).isEqualTo(1);
        assertThat(html).doesNotContain("<script>alert(1)");
        // 데이터 안의 자리 표시 글자는 그대로 남고, Mermaid가 그 자리에 들어가지 않았다
        assertThat(MAPPER.readTree(scriptBody(html, "codeatlas-vendor-mermaid")).isTextual()).isTrue();
    }

    @Test
    @DisplayName("F-016-AC1 계약대로 손으로 만든 픽스처 graph.json을 읽어 내보낸다 (Node 검사용 target/viewer-sample/viewer.html)")
    void exportsSampleFixture() throws Exception {
        Path fixture = Path.of("src/test/resources/fixtures/sample-graph.json");
        GraphModel graph = GraphJson.read(fixture);
        Path dir = Path.of("target/viewer-sample");

        String html = export(graph, dir);

        JsonNode embedded = MAPPER.readTree(scriptBody(html, "codeatlas-data"));
        JsonNode original = MAPPER.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
        assertThat(embedded.path("nodes")).hasSize(original.path("nodes").size());
        assertThat(embedded.path("edges")).hasSize(original.path("edges").size());
        assertThat(embedded.path("diagnostics")).hasSize(original.path("diagnostics").size());
        // GraphJson은 project.options(분석 옵션)를 항상 덧붙여 쓴다. 픽스처에 있던 항목이 그대로인지만 본다.
        original.path("project").fields().forEachRemaining(field ->
                assertThat(embedded.path("project").path(field.getKey()))
                        .as("project.%s", field.getKey())
                        .isEqualTo(field.getValue()));
    }

    private static int countOccurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
