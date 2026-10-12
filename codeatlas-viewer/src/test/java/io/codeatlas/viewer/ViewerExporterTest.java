package io.codeatlas.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.Exporter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
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

    /** id가 같은 script 블록의 본문. 정규식이 첫 번째 종료 태그에서 끊으므로, 데이터가 문서를 깨면 블록을 풀지 못한다. */
    private static String scriptBody(String html, String id) {
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            if (m.group(1).contains("id=\"" + id + "\"")) {
                return m.group(2);
            }
        }
        throw new AssertionError("script 블록이 없음: " + id);
    }

    /** 내장 블록(gzip으로 압축하고 base64로 감싼 글자)을 원래 글자로 푼다. */
    private static String unpack(String block) throws IOException {
        assertThat(block).as("내장 블록에는 base64 글자만 있다").matches("[A-Za-z0-9+/]+=*");
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(block)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** viewer.html에 내장된 데이터(뷰어 내장 형식: F-016-R49). */
    private static JsonNode embeddedData(String html) throws IOException {
        return MAPPER.readTree(unpack(scriptBody(html, "codeatlas-data")));
    }

    /** viewer.html에 내장된 그래프를 graph.json 모양으로 되돌린 것. 뷰어의 {@code core/compact.js}와 같은 규칙이다. */
    private static JsonNode embeddedGraph(String html) throws IOException {
        JsonNode data = embeddedData(html);
        assertThat(data.path("compact").asInt()).isEqualTo(1);
        JsonNode strings = data.get("strings");
        ObjectNode graph = MAPPER.createObjectNode();
        graph.set("schemaVersion", data.get("schemaVersion"));
        graph.set("project", data.get("project"));
        ArrayNode nodes = graph.putArray("nodes");
        for (JsonNode row : data.get("nodes")) {
            ObjectNode node = nodes.addObject();
            node.set("id", row.get(0));
            node.set("kind", strings.get(row.get(1).asInt()));
            node.set("name", row.get(2));
            node.set("qualifiedName", row.get(3));
            node.set("location", location(row.get(4), strings));
            node.set("attrs", row.has(5) ? row.get(5) : MAPPER.createObjectNode());
        }
        ArrayNode edges = graph.putArray("edges");
        for (JsonNode row : data.get("edges")) {
            ObjectNode edge = edges.addObject();
            edge.set("from", row.get(0).isNumber() ? data.get("nodes").get(row.get(0).asInt()).get(0) : row.get(0));
            edge.set("to", row.get(1).isNumber() ? data.get("nodes").get(row.get(1).asInt()).get(0) : row.get(1));
            edge.set("kind", strings.get(row.get(2).asInt()));
            edge.put("confidence", row.has(3) ? strings.get(row.get(3).asInt()).asText() : "RESOLVED");
            edge.set("location", location(row.get(4), strings));
            edge.set("attrs", row.has(5) ? row.get(5) : MAPPER.createObjectNode());
        }
        ArrayNode diagnostics = graph.putArray("diagnostics");
        for (JsonNode row : data.get("diagnostics")) {
            ObjectNode diagnostic = diagnostics.addObject();
            diagnostic.set("severity", strings.get(row.get(0).asInt()));
            diagnostic.set("code", strings.get(row.get(1).asInt()));
            diagnostic.set("message", row.get(2));
            diagnostic.set("location", location(row.get(3), strings));
        }
        return graph;
    }

    private static JsonNode location(JsonNode row, JsonNode strings) {
        if (row == null || !row.isArray()) {
            return MAPPER.nullNode();
        }
        int startLine = row.has(1) ? row.get(1).asInt() : 0;
        int startColumn = row.has(2) ? row.get(2).asInt() : 0;
        ObjectNode location = MAPPER.createObjectNode();
        location.set("file", strings.get(row.get(0).asInt()));
        location.put("startLine", startLine);
        location.put("startColumn", startColumn);
        location.put("endLine", row.has(3) ? row.get(3).asInt() : startLine);
        location.put("endColumn", row.has(4) ? row.get(4).asInt() : startColumn);
        return location;
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
        JsonNode embedded = embeddedGraph(html);
        assertThat(embedded).isEqualTo(graphJson(graph));
        assertThat(embedded.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(embedded.path("project").path("name").asText()).isEqualTo("demo");
        assertThat(embedded.path("nodes")).hasSize(graph.nodeCount());
        assertThat(embedded.path("edges")).hasSize(graph.edgeCount());
        assertThat(embedded.path("diagnostics")).hasSize(graph.diagnostics().size());
        assertThat(embedded.path("diagnostics").findValuesAsText("code")).contains("UNRESOLVED_REFERENCE", "PARSE_FAILED");
    }

    @Test
    @DisplayName("F-016-AC96 뷰어 내장 형식: 종류·파일은 글자 표의 번호로, 엣지의 양 끝은 노드의 자리로 적고 기본값인 끝 칸은 적지 않는다")
    void writesCompactForm() throws Exception {
        String file = "src/A.java";
        List<Node> nodes = List.of(
                Node.builder("java:type:com.a.A", NodeKind.JAVA_TYPE).name("A").qualifiedName("com.a.A")
                        .location(SourceLocation.of(file, 5, 1, 40, 2)).attr("typeKind", "class").attr("modifiers", List.of("public")).build(),
                Node.builder("java:method:com.a.A#run()", NodeKind.JAVA_METHOD).name("run").qualifiedName("com.a.A#run()")
                        .location(SourceLocation.of(file, 10)).build(),
                Node.builder("db:table:T", NodeKind.DB_TABLE).name("T").qualifiedName("T").build(),
                Node.builder("db:table:U", NodeKind.DB_TABLE).name("U").qualifiedName("U").attr("declared", false).build(),
                Node.builder("file:src/A.java", NodeKind.FILE).name("A.java").qualifiedName(file).location(SourceLocation.ofFile(file)).build());
        List<Edge> edges = List.of(
                Edge.of("java:type:com.a.A", "java:method:com.a.A#run()", EdgeKind.CONTAINS),
                Edge.builder("java:method:com.a.A#run()", "java:method:x.Missing#go()", EdgeKind.CALLS).confidence(Confidence.UNRESOLVED)
                        .location(SourceLocation.of(file, 12, 3, 12, 3)).build(),
                Edge.builder("java:method:com.a.A#run()", "db:table:T", EdgeKind.WRITES).attr("op", "INSERT").build(),
                Edge.builder("db:table:T", "db:table:U", EdgeKind.REFERENCES).inferred().location(SourceLocation.of(file, 20, 4, 22, 4))
                        .attr("columns", List.of("A", "B")).attr("evidenceCount", 2).build());
        List<Diagnostic> diagnostics = List.of(
                Diagnostic.warn("PARSE_FAILED", "구문을 건너뜀", SourceLocation.of(file, 3)),
                Diagnostic.info("NOTE", "위치 없음", null));
        GraphModel graph = GraphModel.restore(new ProjectInfo("demo", "/work/demo", Instant.parse("2026-10-12T00:00:00Z")), nodes, edges, diagnostics);

        String html = export(graph, out);

        JsonNode data = embeddedData(html);
        List<String> keys = new java.util.ArrayList<>();
        data.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("compact", "schemaVersion", "project", "nodes", "edges", "diagnostics", "strings");
        assertThat(data.get("compact").asInt()).isEqualTo(1);
        assertThat(data.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(data.get("project")).isEqualTo(graphJson(graph).get("project"));
        // 표의 글자는 처음 나온 순서다: 노드(종류, 파일) → 엣지(종류, 신뢰도, 파일) → 진단(심각도, 코드, 파일)
        assertThat(data.get("strings")).isEqualTo(MAPPER.readTree("""
                ["DB_TABLE", "FILE", "src/A.java", "JAVA_METHOD", "JAVA_TYPE", "REFERENCES", "INFERRED", "WRITES", "RESOLVED",
                 "CALLS", "UNRESOLVED", "CONTAINS", "INFO", "NOTE", "WARN", "PARSE_FAILED"]"""));
        // 노드는 ID 순. 끝의 attrs {}와 없는 위치는 적지 않고, attrs만 있으면 위치 자리에 0을 둔다. 위치는 기본값과 같은 끝 칸을 뗀다.
        assertThat(data.get("nodes")).isEqualTo(MAPPER.readTree("""
                [["db:table:T", 0, "T", "T"],
                 ["db:table:U", 0, "U", "U", 0, {"declared": false}],
                 ["file:src/A.java", 1, "A.java", "src/A.java", [2]],
                 ["java:method:com.a.A#run()", 3, "run", "com.a.A#run()", [2, 10]],
                 ["java:type:com.a.A", 4, "A", "com.a.A", [2, 5, 1, 40, 2], {"modifiers": ["public"], "typeKind": "class"}]]"""));
        // 엣지는 graph.json 순서. 양 끝은 노드의 자리이고 그래프에 없는 노드는 ID 그대로다. RESOLVED뿐인 엣지는 세 칸이다.
        assertThat(data.get("edges")).isEqualTo(MAPPER.readTree("""
                [[0, 1, 5, 6, [2, 20, 4, 22], {"columns": ["A", "B"], "evidenceCount": 2}],
                 [3, 0, 7, 8, 0, {"op": "INSERT"}],
                 [3, "java:method:x.Missing#go()", 9, 10, [2, 12, 3]],
                 [4, 3, 11]]"""));
        assertThat(data.get("diagnostics")).isEqualTo(MAPPER.readTree("""
                [[12, 13, "위치 없음"],
                 [14, 15, "구문을 건너뜀", [2, 3]]]"""));
        assertThat(embeddedGraph(html)).isEqualTo(graphJson(graph));
    }

    @Test
    @DisplayName("F-016-AC1 출력 폴더가 없으면 만들고, 이미 있는 viewer.html은 덮어쓴다")
    void createsDirectoryAndOverwrites() throws Exception {
        Path dir = out.resolve("nested/out");
        export(smallGraph(), dir);
        GraphModel second = new GraphModel();
        second.declare(Node.of("db:table:ONLY", NodeKind.DB_TABLE, "ONLY"));

        String html = export(second, dir);

        JsonNode embedded = embeddedGraph(html);
        assertThat(embedded.path("nodes")).hasSize(1);
        assertThat(embedded.path("nodes").get(0).path("id").asText()).isEqualTo("db:table:ONLY");
        assertThat(embedded).isEqualTo(graphJson(second));
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

        String source = unpack(scriptBody(html, "codeatlas-vendor-mermaid"));
        assertThat(source.length()).isGreaterThan(1_000_000);
        assertThat(source).contains("globalThis[\"mermaid\"]").contains("erDiagram").contains("classDiagram");
        // 번들 원본과 글자 하나까지 같다
        try (var in = ViewerExporter.class.getResourceAsStream("vendor/mermaid.min.js")) {
            assertThat(in).as("빌드가 Mermaid를 클래스패스에 풀어 두었다").isNotNull();
            assertThat(source).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
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

        JsonNode embedded = embeddedGraph(html);
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
        assertThat(unpack(scriptBody(html, "codeatlas-vendor-mermaid"))).contains("erDiagram");
    }

    @Test
    @DisplayName("F-016-AC93 F-016-AC96 데이터와 Mermaid를 gzip으로 압축해 넣어 viewer.html이 graph.json보다 훨씬 작다")
    void embedsCompressedBlocks() throws Exception {
        GraphModel graph = smallGraph();
        String owner = "com.shop.order.service.impl.OrderSettlementServiceImpl";
        graph.declare(Node.builder("java:type:" + owner, NodeKind.JAVA_TYPE).name("OrderSettlementServiceImpl").qualifiedName(owner).build());
        for (int i = 0; i < 3000; i++) {
            String method = "java:method:" + owner + "#settle" + i + "(java.lang.String,java.util.List)";
            graph.declare(Node.builder(method, NodeKind.JAVA_METHOD).name("settle" + i).qualifiedName(owner + "#settle" + i + "(String,List)")
                    .location(SourceLocation.of("src/main/java/com/shop/order/service/impl/OrderSettlementServiceImpl.java", 10 + i)).build());
            graph.addEdge(Edge.of("java:type:" + owner, method, EdgeKind.CONTAINS));
            if (i > 0) {
                graph.addEdge(Edge.builder(method, "java:method:" + owner + "#settle" + (i - 1) + "(java.lang.String,java.util.List)", EdgeKind.CALLS)
                        .location(SourceLocation.of("src/main/java/com/shop/order/service/impl/OrderSettlementServiceImpl.java", 11 + i)).build());
            }
        }
        StringWriter json = new StringWriter();
        GraphJson.write(graph, json);
        int jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8).length;
        assertThat(jsonBytes).isGreaterThan(1_500_000);

        String html = export(graph, out);

        String data = scriptBody(html, "codeatlas-data");
        assertThat(embeddedGraph(html)).isEqualTo(graphJson(graph));
        int compactBytes = unpack(data).getBytes(StandardCharsets.UTF_8).length;
        assertThat(compactBytes).as("F-016-AC96 줄인 JSON이 graph.json(%d바이트)의 절반보다 작다", jsonBytes).isLessThan(jsonBytes / 2);
        assertThat(data.length()).as("데이터 블록이 graph.json(%d바이트)의 5분의 1보다 작다", jsonBytes).isLessThan(jsonBytes / 5);
        String mermaid = scriptBody(html, "codeatlas-vendor-mermaid");
        assertThat(mermaid.length()).as("Mermaid 블록이 번들 원본의 절반보다 작다").isLessThan(unpack(mermaid).getBytes(StandardCharsets.UTF_8).length / 2);
        assertThat(Files.size(out.resolve("viewer.html"))).as("파일 전체가 graph.json과 Mermaid 번들을 그대로 넣은 것보다 작다").isLessThan(jsonBytes + 1_500_000L);
    }

    @Test
    @DisplayName("F-016-AC1 계약대로 손으로 만든 픽스처 graph.json을 읽어 내보낸다 (Node 검사용 target/viewer-sample/viewer.html)")
    void exportsSampleFixture() throws Exception {
        Path fixture = Path.of("src/test/resources/fixtures/sample-graph.json");
        GraphModel graph = GraphJson.read(fixture);
        Path dir = Path.of("target/viewer-sample");

        String html = export(graph, dir);

        JsonNode embedded = embeddedGraph(html);
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
