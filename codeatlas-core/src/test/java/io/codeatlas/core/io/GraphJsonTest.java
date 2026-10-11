package io.codeatlas.core.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphJsonTest {

    @TempDir
    Path dir;

    private static List<Runnable> steps(GraphModel g) {
        SourceLocation loc = SourceLocation.of("db/스키마.sql", 3, 1, 9, 2);
        return List.of(
                () -> g.declare(Node.builder("db:table:ORDERS", NodeKind.DB_TABLE).name("ORDERS").qualifiedName("HR.ORDERS")
                        .location(loc).attr("schema", "HR").attr("comment", "주문 \"테이블\"\n둘째 줄").build()),
                () -> g.declare(Node.builder("db:column:ORDERS.ID", NodeKind.DB_COLUMN).name("ID")
                        .attr("ordinal", 1).attr("nullable", false).attr("big", 12345678901L).attr("ratio", 0.5)
                        .attr("tags", List.of("pk", "숫자")).build()),
                () -> g.reference("db:table:CUSTOMER", NodeKind.DB_TABLE, "CUSTOMER"),
                () -> g.addEdge(Edge.of("db:table:ORDERS", "db:column:ORDERS.ID", EdgeKind.CONTAINS)),
                () -> g.addEdge(Edge.builder("db:table:ORDERS", "db:table:CUSTOMER", EdgeKind.REFERENCES).inferred().location(loc)
                        .attr("columns", List.of("CUST_ID")).attr("evidenceCount", 2).build()),
                () -> g.addEdge(Edge.builder("db:table:ORDERS", "db:table:CUSTOMER", EdgeKind.REFERENCES)
                        .location(SourceLocation.of("a.sql", 1)).build()),
                () -> g.addEdge(Edge.of("db:table:ORDERS", "db:table:CUSTOMER", EdgeKind.JOINS)),
                () -> g.addUnresolvedEdge("db:table:ORDERS", EdgeKind.READS, "sql-dynamic", "${t}", null));
    }

    private static GraphModel build(boolean reversed) {
        GraphModel g = new GraphModel();
        g.project(new ProjectInfo("demo", "/work/demo", Instant.parse("2026-10-11T01:02:03Z")));
        List<Runnable> steps = new ArrayList<>(steps(g));
        if (reversed) {
            Collections.reverse(steps);
        }
        steps.forEach(Runnable::run);
        g.report(Diagnostic.warn("PARSE_FAILED", "구문을 건너뜀", SourceLocation.of("db/스키마.sql", 5)));
        g.report(Diagnostic.info("NOTE", "위치 없음", null));
        return g;
    }

    @Test
    @DisplayName("F-001-AC14 graph.json으로 쓰고 다시 읽으면 노드, 엣지, 진단, 프로젝트 정보가 같다")
    void roundTrip() throws Exception {
        GraphModel original = build(false);
        Path file = dir.resolve("sub/graph.json");

        GraphJson.write(original, file);
        GraphModel restored = GraphJson.read(file);

        assertThat(restored.nodes()).containsExactlyInAnyOrderElementsOf(original.nodes());
        assertThat(restored.edges()).containsExactlyInAnyOrderElementsOf(original.edges());
        assertThat(restored.diagnostics()).containsExactlyInAnyOrderElementsOf(original.diagnostics());
        assertThat(restored.project()).isEqualTo(original.project());
        assertThat(restored.outgoing("db:table:ORDERS")).hasSize(5);
        assertThat(restored.node("db:table:CUSTOMER").orElseThrow().declared()).isFalse();
        assertThat(restored.node("db:column:ORDERS.ID").orElseThrow().attrs())
                .containsEntry("big", 12345678901L).containsEntry("ratio", 0.5).containsEntry("ordinal", 1);

        Path again = dir.resolve("again.json");
        GraphJson.write(restored, again);
        assertThat(Files.readString(again)).isEqualTo(Files.readString(file));
    }

    @Test
    @DisplayName("F-001-AC15 넣은 순서가 달라도 같은 파일이 나오고 노드는 ID 순, schemaVersion은 1이다")
    void deterministicOutput() throws Exception {
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");
        GraphJson.write(build(false), a);
        GraphJson.write(build(true), b);

        assertThat(Files.readString(b)).isEqualTo(Files.readString(a));

        JsonNode json = new ObjectMapper().readTree(a.toFile());
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("project").get("name").asText()).isEqualTo("demo");
        assertThat(json.get("project").get("root").asText()).isEqualTo("/work/demo");
        assertThat(json.get("project").get("analyzedAt").asText()).isEqualTo("2026-10-11T01:02:03Z");
        List<String> ids = new ArrayList<>();
        json.get("nodes").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).isSorted().hasSize(4);
        List<String> edgeKeys = new ArrayList<>();
        json.get("edges").forEach(e -> edgeKeys.add(e.get("from").asText() + "|" + e.get("to").asText() + "|" + e.get("kind").asText()));
        assertThat(edgeKeys).isSorted().hasSize(5);

        JsonNode orders = json.get("nodes").get(2);
        assertThat(orders.get("id").asText()).isEqualTo("db:table:ORDERS");
        assertThat(orders.get("kind").asText()).isEqualTo("DB_TABLE");
        assertThat(orders.get("qualifiedName").asText()).isEqualTo("HR.ORDERS");
        assertThat(orders.get("location").get("file").asText()).isEqualTo("db/스키마.sql");
        assertThat(orders.get("location").get("endColumn").asInt()).isEqualTo(2);
        assertThat(orders.get("attrs").get("declared").asBoolean()).isTrue();
        assertThat(json.get("nodes").get(0).get("location").isNull()).isTrue();
        JsonNode firstEdge = json.get("edges").get(0);
        assertThat(firstEdge.get("confidence").asText()).isEqualTo("RESOLVED");
        assertThat(firstEdge.has("location")).isTrue();
        assertThat(firstEdge.has("attrs")).isTrue();
        assertThat(json.get("diagnostics")).hasSize(3);
        assertThat(json.get("diagnostics").get(0).has("severity")).isTrue();
    }

    @Test
    @DisplayName("F-001-AC14 프로젝트 정보가 없는 빈 그래프도 왕복한다")
    void emptyGraph() throws Exception {
        Path file = dir.resolve("empty.json");
        GraphJson.write(new GraphModel(), file);
        GraphModel restored = GraphJson.read(file);
        assertThat(restored.nodes()).isEmpty();
        assertThat(restored.edges()).isEmpty();
        assertThat(restored.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-001-AC51 참조 노드의 ID에서 유도한 qualifiedName과 호출자가 준 name은 graph.json 왕복 뒤에도 같다")
    void referenceQualifiedNameRoundTrip() throws Exception {
        GraphModel original = new GraphModel();
        original.reference("java:type:jakarta.annotation.Resource", NodeKind.JAVA_TYPE, "Resource");
        original.reference("java:method:javax.servlet.http.HttpServletRequest#getContextPath(?)", NodeKind.JAVA_METHOD, "getContextPath");
        original.reference("db:column:TB_BROKEN.BROKEN_ID", NodeKind.DB_COLUMN, "BROKEN_ID");
        original.reference("js:module:static/js/app.js", NodeKind.JS_MODULE, "app.js");
        // 마무리 단계가 만드는 UNRESOLVED 노드는 qualifiedName이 ID 그대로다
        original.addEdge(Edge.of("java:type:jakarta.annotation.Resource", "java:type:com.gone.Missing", EdgeKind.DEPENDS_ON));
        original.resolveDanglingEdges();
        Path file = dir.resolve("refs.json");

        GraphJson.write(original, file);
        GraphModel restored = GraphJson.read(file);

        assertThat(restored.nodes()).containsExactlyInAnyOrderElementsOf(original.nodes());
        Map<String, String> qualifiedNames = new java.util.TreeMap<>();
        Map<String, String> names = new java.util.TreeMap<>();
        for (Node node : restored.nodes()) {
            qualifiedNames.put(node.id(), node.qualifiedName());
            names.put(node.id(), node.name());
        }
        assertThat(qualifiedNames).containsExactlyInAnyOrderEntriesOf(Map.of(
                "java:type:jakarta.annotation.Resource", "jakarta.annotation.Resource",
                "java:method:javax.servlet.http.HttpServletRequest#getContextPath(?)", "javax.servlet.http.HttpServletRequest#getContextPath(?)",
                "db:column:TB_BROKEN.BROKEN_ID", "TB_BROKEN.BROKEN_ID",
                "js:module:static/js/app.js", "static/js/app.js",
                "java:type:com.gone.Missing", "java:type:com.gone.Missing"));
        assertThat(names).containsExactlyInAnyOrderEntriesOf(Map.of(
                "java:type:jakarta.annotation.Resource", "Resource",
                "java:method:javax.servlet.http.HttpServletRequest#getContextPath(?)", "getContextPath",
                "db:column:TB_BROKEN.BROKEN_ID", "BROKEN_ID",
                "js:module:static/js/app.js", "app.js",
                "java:type:com.gone.Missing", "com.gone.Missing"));
        assertThat(restored.node("java:type:jakarta.annotation.Resource").orElseThrow().declared()).isFalse();

        JsonNode json = new ObjectMapper().readTree(file.toFile());
        JsonNode column = json.get("nodes").get(0);
        assertThat(column.get("id").asText()).isEqualTo("db:column:TB_BROKEN.BROKEN_ID");
        assertThat(column.get("name").asText()).isEqualTo("BROKEN_ID");
        assertThat(column.get("qualifiedName").asText()).isEqualTo("TB_BROKEN.BROKEN_ID");
    }

    @Test
    @DisplayName("F-001-AC43 분석 옵션을 project.options에 저장하고 다시 읽으면 같다(경로는 입력 그대로, schemaVersion은 1)")
    void optionsRoundTrip() throws Exception {
        AnalysisOptions options = AnalysisOptions.builder()
                .dialect(Dialect.ORACLE)
                .encoding(Charset.forName("MS949"))
                .classpath(Path.of("lib/a.jar")).classpath(Path.of("../shared/classes"))
                .ddlPath(Path.of("db/ddl")).ddlPath(Path.of("/abs/schema.sql"))
                .exclude("src/test/**").exclude("*.min.js")
                .extra("export.erd.columns", "keys").extra("b.key", "값 = \"x\"").extra("a.empty", "")
                .build();
        GraphModel graph = new GraphModel();
        graph.project(new ProjectInfo("demo", "/work/demo", Instant.parse("2026-10-11T01:02:03Z"), options));
        Path file = dir.resolve("graph.json");

        GraphJson.write(graph, file);
        GraphModel restored = GraphJson.read(file);

        assertThat(restored.project().options()).isEqualTo(options);
        assertThat(restored.project()).isEqualTo(graph.project());

        JsonNode json = new ObjectMapper().readTree(file.toFile());
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        JsonNode saved = json.get("project").get("options");
        assertThat(saved.get("dialect").asText()).isEqualTo("ORACLE");
        assertThat(saved.get("encoding").asText()).isEqualTo(Charset.forName("MS949").name());
        assertThat(saved.get("classpath")).extracting(JsonNode::asText).containsExactly("lib/a.jar", "../shared/classes");
        assertThat(saved.get("ddlPaths")).extracting(JsonNode::asText).containsExactly("db/ddl", "/abs/schema.sql");
        assertThat(saved.get("excludes")).extracting(JsonNode::asText).containsExactly("src/test/**", "*.min.js");
        List<String> extraKeys = new ArrayList<>();
        saved.get("extra").fieldNames().forEachRemaining(extraKeys::add);
        assertThat(extraKeys).containsExactly("a.empty", "b.key", "export.erd.columns");
        assertThat(saved.get("extra").get("b.key").asText()).isEqualTo("값 = \"x\"");

        Path again = dir.resolve("again.json");
        GraphJson.write(restored, again);
        assertThat(Files.readString(again)).isEqualTo(Files.readString(file));
    }

    @Test
    @DisplayName("F-001-AC43 옵션을 주지 않은 그래프와 options 항목이 없는 예전 graph.json은 빈 옵션으로 읽는다")
    void optionsDefaultWhenAbsent() throws Exception {
        assertThat(new ProjectInfo("demo", "/work/demo", null).options()).isEqualTo(AnalysisOptions.defaults());

        Path legacy = Files.writeString(dir.resolve("legacy.json"), """
                {"schemaVersion": 1,
                 "project": {"name": "old", "root": "/work/old", "analyzedAt": "2026-10-11T01:02:03Z"},
                 "nodes": [], "edges": [], "diagnostics": []}
                """);
        ProjectInfo project = GraphJson.read(legacy).project();
        assertThat(project.name()).isEqualTo("old");
        assertThat(project.options()).isEqualTo(AnalysisOptions.defaults());
        assertThat(project.options().extra()).isEmpty();
        assertThat(project.options().encoding()).isNull();

        Path partial = Files.writeString(dir.resolve("partial.json"), """
                {"schemaVersion": 1,
                 "project": {"name": "p", "root": "/p", "analyzedAt": null, "options": {"extra": {"k": "v"}, "encoding": "NO-SUCH-CHARSET"}},
                 "nodes": [], "edges": [], "diagnostics": []}
                """);
        AnalysisOptions options = GraphJson.read(partial).project().options();
        assertThat(options.extra()).containsExactly(Map.entry("k", "v"));
        assertThat(options.dialect()).isEqualTo(Dialect.AUTO);
        assertThat(options.encoding()).isNull();
        assertThat(options.classpath()).isEmpty();
    }

    @Test
    @DisplayName("모르는 schemaVersion이나 깨진 파일은 IOException으로 알린다")
    void rejectsUnknownVersion() throws Exception {
        Path future = Files.writeString(dir.resolve("future.json"), "{\"schemaVersion\": 99, \"nodes\": [], \"edges\": [], \"diagnostics\": []}");
        assertThatThrownBy(() -> GraphJson.read(future)).isInstanceOf(IOException.class).hasMessageContaining("schemaVersion");
        Path broken = Files.writeString(dir.resolve("broken.json"), "{ not json");
        assertThatThrownBy(() -> GraphJson.read(broken)).isInstanceOf(IOException.class);
    }
}
