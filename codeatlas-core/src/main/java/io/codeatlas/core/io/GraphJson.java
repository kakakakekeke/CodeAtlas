package io.codeatlas.core.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * graph.json 저장과 복원(설계 4.4). UTF-8로 쓰고, 노드·엣지·진단을 한 줄에 하나씩 정렬해 써서 두 결과를 줄 단위로 비교할 수 있다.
 *
 * <ul>
 * <li>노드: ID 순</li>
 * <li>엣지: from, to, kind, location 순</li>
 * <li>진단: location, severity, code, message 순</li>
 * <li>attrs: 키 순</li>
 * </ul>
 *
 * <p>{@code project.options}에는 분석할 때 준 옵션({@link AnalysisOptions})을 담는다. 경로는 입력 그대로의 문자열이되 구분자는
 * {@code /}로 쓰고, {@code extra}는 키 순이다. 이 항목이 없는 파일(예전에 쓴 것)은 빈 옵션으로 읽는다.
 */
public final class GraphJson {

    /** 이 클래스가 쓰고 읽는 schemaVersion. */
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Comparator<SourceLocation> BY_LOCATION = Comparator.nullsFirst(
            Comparator.comparing(SourceLocation::file)
                    .thenComparingInt(SourceLocation::startLine)
                    .thenComparingInt(SourceLocation::startColumn)
                    .thenComparingInt(SourceLocation::endLine)
                    .thenComparingInt(SourceLocation::endColumn));

    private static final Comparator<Edge> EDGE_ORDER = Comparator.comparing(Edge::from)
            .thenComparing(Edge::to)
            .thenComparing(e -> e.kind().name())
            .thenComparing(Edge::location, BY_LOCATION);

    private static final Comparator<Diagnostic> DIAGNOSTIC_ORDER = Comparator.comparing(Diagnostic::location, BY_LOCATION)
            .thenComparing(Diagnostic::severity)
            .thenComparing(Diagnostic::code)
            .thenComparing(Diagnostic::message);

    private GraphJson() {
    }

    /** 그래프를 파일에 쓴다. 상위 폴더가 없으면 만든다. */
    public static void write(GraphModel graph, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(graph, writer);
        }
    }

    /** 그래프를 JSON 문자열로 쓴다. writer는 닫지 않는다. */
    public static void write(GraphModel graph, Writer writer) throws IOException {
        List<Node> nodes = new ArrayList<>(graph.nodes());
        nodes.sort(Comparator.comparing(Node::id));
        List<Edge> edges = new ArrayList<>(graph.edges());
        edges.sort(EDGE_ORDER);
        List<Diagnostic> diagnostics = new ArrayList<>(graph.diagnostics());
        diagnostics.sort(DIAGNOSTIC_ORDER);

        writer.write("{\n\"schemaVersion\": " + SCHEMA_VERSION + ",\n\"project\": ");
        writer.write(MAPPER.writeValueAsString(projectJson(graph.project())));
        writer.write(",\n\"nodes\": [");
        writeLines(writer, nodes.stream().map(GraphJson::nodeJson).toList());
        writer.write("],\n\"edges\": [");
        writeLines(writer, edges.stream().map(GraphJson::edgeJson).toList());
        writer.write("],\n\"diagnostics\": [");
        writeLines(writer, diagnostics.stream().map(GraphJson::diagnosticJson).toList());
        writer.write("]\n}\n");
        writer.flush();
    }

    /**
     * graph.json을 읽어 그래프를 복원한다.
     *
     * @throws IOException 파일을 읽지 못하거나, JSON이 아니거나, schemaVersion이 다르면
     */
    public static GraphModel read(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
        if (root == null || !root.isObject()) {
            throw new IOException("graph.json 형식이 아님: " + file);
        }
        int version = root.path("schemaVersion").asInt(-1);
        if (version != SCHEMA_VERSION) {
            throw new IOException("지원하지 않는 schemaVersion " + version + " (지원: " + SCHEMA_VERSION + "): " + file);
        }
        try {
            List<Node> nodes = new ArrayList<>();
            for (JsonNode n : root.path("nodes")) {
                nodes.add(Node.builder(n.path("id").asText(), NodeKind.valueOf(n.path("kind").asText()))
                        .name(text(n, "name"))
                        .qualifiedName(text(n, "qualifiedName"))
                        .location(location(n.get("location")))
                        .attrs(attrs(n.get("attrs")))
                        .build());
            }
            List<Edge> edges = new ArrayList<>();
            for (JsonNode e : root.path("edges")) {
                edges.add(Edge.builder(e.path("from").asText(), e.path("to").asText(), EdgeKind.valueOf(e.path("kind").asText()))
                        .confidence(Confidence.valueOf(e.path("confidence").asText(Confidence.RESOLVED.name())))
                        .location(location(e.get("location")))
                        .attrs(attrs(e.get("attrs")))
                        .build());
            }
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (JsonNode d : root.path("diagnostics")) {
                diagnostics.add(new Diagnostic(Severity.valueOf(d.path("severity").asText()), d.path("code").asText(),
                        text(d, "message"), location(d.get("location"))));
            }
            return GraphModel.restore(project(root.get("project")), nodes, edges, diagnostics);
        } catch (IllegalArgumentException e) {
            throw new IOException("graph.json 내용을 해석하지 못함: " + file + " (" + e.getMessage() + ")", e);
        }
    }

    // ---- 쓰기 ----

    private static void writeLines(Writer writer, List<Map<String, Object>> items) throws IOException {
        for (int i = 0; i < items.size(); i++) {
            writer.write(i == 0 ? "\n" : ",\n");
            writer.write(MAPPER.writeValueAsString(items.get(i)));
        }
        if (!items.isEmpty()) {
            writer.write("\n");
        }
    }

    private static Map<String, Object> projectJson(ProjectInfo project) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", project == null ? null : project.name());
        json.put("root", project == null ? null : project.root());
        json.put("analyzedAt", project == null || project.analyzedAt() == null ? null : project.analyzedAt().toString());
        json.put("options", project == null ? null : optionsJson(project.options()));
        return json;
    }

    private static Map<String, Object> optionsJson(AnalysisOptions options) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("dialect", options.dialect().name());
        json.put("encoding", options.encoding() == null ? null : options.encoding().name());
        json.put("classpath", options.classpath().stream().map(GraphJson::portablePath).toList());
        json.put("ddlPaths", options.ddlPaths().stream().map(GraphJson::portablePath).toList());
        json.put("excludes", options.excludes());
        json.put("extra", new TreeMap<>(options.extra()));
        return json;
    }

    private static String portablePath(Path path) {
        return portablePath(path.toString(), path.getFileSystem().getSeparator());
    }

    /** 이 파일 시스템의 구분자를 /로 바꾼다. 구분자가 /인 곳에서 \는 이름의 일부이므로 그대로 둔다. */
    static String portablePath(String path, String separator) {
        return path.replace(separator, "/");
    }

    private static Map<String, Object> nodeJson(Node node) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", node.id());
        json.put("kind", node.kind().name());
        json.put("name", node.name());
        json.put("qualifiedName", node.qualifiedName());
        json.put("location", locationJson(node.location()));
        json.put("attrs", new TreeMap<>(node.attrs()));
        return json;
    }

    private static Map<String, Object> edgeJson(Edge edge) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("from", edge.from());
        json.put("to", edge.to());
        json.put("kind", edge.kind().name());
        json.put("confidence", edge.confidence().name());
        json.put("location", locationJson(edge.location()));
        json.put("attrs", new TreeMap<>(edge.attrs()));
        return json;
    }

    private static Map<String, Object> diagnosticJson(Diagnostic diagnostic) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("severity", diagnostic.severity().name());
        json.put("code", diagnostic.code());
        json.put("message", diagnostic.message());
        json.put("location", locationJson(diagnostic.location()));
        return json;
    }

    private static Map<String, Object> locationJson(SourceLocation location) {
        if (location == null) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("file", location.file());
        json.put("startLine", location.startLine());
        json.put("startColumn", location.startColumn());
        json.put("endLine", location.endLine());
        json.put("endColumn", location.endColumn());
        return json;
    }

    // ---- 읽기 ----

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static ProjectInfo project(JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        String name = text(json, "name");
        String root = text(json, "root");
        String analyzedAt = text(json, "analyzedAt");
        JsonNode options = json.get("options");
        boolean hasOptions = options != null && options.isObject();
        if (name == null && root == null && analyzedAt == null && !hasOptions) {
            return null;
        }
        return new ProjectInfo(name, root, analyzedAt == null ? null : Instant.parse(analyzedAt), hasOptions ? options(options) : null);
    }

    /** 빠진 항목은 기본값이다. 이 JVM이 모르는 인코딩 이름은 지정이 없는 것(null)으로 읽는다. */
    private static AnalysisOptions options(JsonNode json) {
        AnalysisOptions.Builder builder = AnalysisOptions.builder();
        String dialect = text(json, "dialect");
        if (dialect != null) {
            builder.dialect(Dialect.valueOf(dialect));
        }
        String encoding = text(json, "encoding");
        if (encoding != null) {
            try {
                builder.encoding(Charset.forName(encoding));
            } catch (IllegalArgumentException e) {
                builder.encoding(null);
            }
        }
        json.path("classpath").forEach(entry -> builder.classpath(Path.of(entry.asText())));
        json.path("ddlPaths").forEach(entry -> builder.ddlPath(Path.of(entry.asText())));
        json.path("excludes").forEach(entry -> builder.exclude(entry.asText()));
        json.path("extra").fields().forEachRemaining(entry -> builder.extra(entry.getKey(), entry.getValue().asText()));
        return builder.build();
    }

    private static SourceLocation location(JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        return new SourceLocation(json.path("file").asText(),
                json.path("startLine").asInt(0), json.path("startColumn").asInt(0),
                json.path("endLine").asInt(0), json.path("endColumn").asInt(0));
    }

    private static Map<String, Object> attrs(JsonNode json) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (json == null || !json.isObject()) {
            return attrs;
        }
        json.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            Object converted;
            if (value.isTextual()) {
                converted = value.asText();
            } else if (value.isBoolean()) {
                converted = value.asBoolean();
            } else if (value.isIntegralNumber()) {
                converted = value.asLong();
            } else if (value.isNumber()) {
                converted = value.asDouble();
            } else if (value.isArray()) {
                List<String> list = new ArrayList<>();
                value.forEach(item -> list.add(item.asText()));
                converted = list;
            } else {
                converted = null;
            }
            attrs.put(entry.getKey(), converted);
        });
        return attrs;
    }
}
