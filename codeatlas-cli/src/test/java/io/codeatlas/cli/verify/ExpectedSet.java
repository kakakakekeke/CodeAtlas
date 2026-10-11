package io.codeatlas.cli.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.NodeKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정답 목록(expected 디렉터리의 JSON 파일들).
 *
 * <p>읽는 파일: {@code must-have-edges.json}, {@code must-have-edges-extra.json}, {@code must-have-nodes.json},
 * {@code must-not-have-edges.json}, {@code must-not-have-edges-extra.json}. {@code -extra} 파일은 없어도 된다.
 */
public record ExpectedSet(List<ExpectedEdge> mustHaveEdges, List<ExpectedNode> mustHaveNodes, List<ExpectedEdge> mustNotEdges) {

    public static final String MUST_HAVE_EDGES = "must-have-edges.json";
    public static final String MUST_HAVE_EDGES_EXTRA = "must-have-edges-extra.json";
    public static final String MUST_HAVE_NODES = "must-have-nodes.json";
    public static final String MUST_NOT_EDGES = "must-not-have-edges.json";
    public static final String MUST_NOT_EDGES_EXTRA = "must-not-have-edges-extra.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ExpectedSet {
        mustHaveEdges = List.copyOf(mustHaveEdges);
        mustHaveNodes = List.copyOf(mustHaveNodes);
        mustNotEdges = List.copyOf(mustNotEdges);
    }

    /** expected 디렉터리에서 읽는다. */
    public static ExpectedSet load(Path dir) throws IOException {
        List<ExpectedEdge> edges = new ArrayList<>();
        edges.addAll(edges(read(dir, MUST_HAVE_EDGES, true), MUST_HAVE_EDGES, false));
        edges.addAll(edges(read(dir, MUST_HAVE_EDGES_EXTRA, false), MUST_HAVE_EDGES_EXTRA, false));
        List<ExpectedNode> nodes = nodes(read(dir, MUST_HAVE_NODES, true), MUST_HAVE_NODES);
        List<ExpectedEdge> mustNot = new ArrayList<>();
        mustNot.addAll(edges(read(dir, MUST_NOT_EDGES, true), MUST_NOT_EDGES, true));
        mustNot.addAll(edges(read(dir, MUST_NOT_EDGES_EXTRA, false), MUST_NOT_EDGES_EXTRA, true));
        return new ExpectedSet(edges, nodes, mustNot);
    }

    /** JSON 문자열에서 바로 만든다(테스트용). */
    public static ExpectedSet parse(String mustHaveEdgesJson, String mustHaveNodesJson, String mustNotEdgesJson) {
        return new ExpectedSet(edges(mustHaveEdgesJson, MUST_HAVE_EDGES, false), nodes(mustHaveNodesJson, MUST_HAVE_NODES),
                edges(mustNotEdgesJson, MUST_NOT_EDGES, true));
    }

    private static String read(Path dir, String name, boolean required) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            if (required) {
                throw new IOException("정답 파일이 없다: " + file);
            }
            return "[]";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static JsonNode array(String json, String source) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException(source + ": 최상위가 배열이 아니다");
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(source + ": JSON을 해석하지 못했다", e);
        }
    }

    private static List<ExpectedEdge> edges(String json, String source, boolean wildcards) {
        List<ExpectedEdge> result = new ArrayList<>();
        int index = 0;
        for (JsonNode item : array(json, source)) {
            String where = source + "[" + index + "]";
            String kind = text(item, "kind");
            if (kind == null) {
                throw new IllegalArgumentException(where + ": kind가 없다");
            }
            if (!(wildcards && kind.equals("*"))) {
                try {
                    EdgeKind.valueOf(kind);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(where + ": 모르는 엣지 종류 " + kind);
                }
            }
            String from = text(item, "from");
            String to = text(item, "to");
            String fromFile = text(item, "fromFile");
            if (to == null) {
                throw new IllegalArgumentException(where + ": to가 없다");
            }
            if (from == null && (fromFile == null || text(item, "requestUrl") == null || text(item, "httpMethod") == null)) {
                throw new IllegalArgumentException(where + ": from이 없으면 fromFile, httpMethod, requestUrl이 있어야 한다");
            }
            result.add(new ExpectedEdge(source, index, from, to, kind,
                    strings(item.get("altFrom")), strings(item.get("altTo")), attrs(item.get("attrs")),
                    item.path("undirected").asBoolean(false), text(item, "statement"), text(item, "statementRoutine"), text(item, "fromRoutine"),
                    fromFile, item.path("line").asInt(0), text(item, "httpMethod"), text(item, "requestUrl"), text(item, "rawUrl"),
                    "low".equals(text(item, "certainty")), text(item, "note")));
            index++;
        }
        return result;
    }

    private static List<ExpectedNode> nodes(String json, String source) {
        List<ExpectedNode> result = new ArrayList<>();
        int index = 0;
        for (JsonNode item : array(json, source)) {
            String where = source + "[" + index + "]";
            String id = text(item, "id");
            String kindName = text(item, "kind");
            if (id == null || kindName == null) {
                throw new IllegalArgumentException(where + ": id와 kind가 있어야 한다");
            }
            NodeKind kind;
            try {
                kind = NodeKind.valueOf(kindName);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": 모르는 노드 종류 " + kindName);
            }
            result.add(new ExpectedNode(source, index, id, kind, attrs(item.get("attrs")), "low".equals(text(item, "certainty")), text(item, "note")));
            index++;
        }
        return result;
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(v -> result.add(v.asText()));
        } else if (array != null && array.isTextual()) {
            result.add(array.asText());
        }
        return List.copyOf(result);
    }

    /** 값은 String, Integer/Long/Double, Boolean, List&lt;String&gt;으로 바꾼다(그래프 attrs와 같은 타입). */
    private static Map<String, Object> attrs(JsonNode object) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (object != null && object.isObject()) {
            object.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if (value.isBoolean()) {
                    result.put(entry.getKey(), value.booleanValue());
                } else if (value.isIntegralNumber()) {
                    result.put(entry.getKey(), value.canConvertToInt() ? (Object) value.intValue() : (Object) value.longValue());
                } else if (value.isNumber()) {
                    result.put(entry.getKey(), value.doubleValue());
                } else if (value.isArray()) {
                    result.put(entry.getKey(), strings(value));
                } else if (!value.isNull()) {
                    result.put(entry.getKey(), value.asText());
                }
            });
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
