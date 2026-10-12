package io.codeatlas.viewer;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codeatlas.core.io.GraphJson;
import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.SourceLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 뷰어 내장 형식(F-016-R49): 그래프를 {@code viewer.html}에 넣을 때 쓰는 줄인 JSON.
 *
 * <p>{@code graph.json}(설계 4.4)은 엣지마다 양 끝의 노드 ID와 파일 경로, 키 이름을 다시 적어서 큰 그래프에서는 대부분이 같은 글자의
 * 반복이다. 이 형식은 같은 내용을 반복 없이 적는다. {@code graph.json}의 계약과는 별개이고 뷰어({@code js/core/compact.js})만 읽는다.
 * <pre>
 * { "compact": 1, "schemaVersion": 1, "project": {…}, "nodes": […], "edges": […], "diagnostics": […], "strings": […] }
 *   strings      반복되는 글자(종류, 신뢰도, 심각도, 진단 코드, 파일 경로)를 처음 나온 순서로 한 번씩 적은 표. 아래의 "번호"는 이 표의 자리다.
 *   nodes[i]     [id, 종류 번호, name, qualifiedName, 위치, attrs]
 *   edges[i]     [from, to, 종류 번호, 신뢰도 번호, 위치, attrs]   from·to는 nodes의 자리. 그래프에 없는 노드면 ID 글자 그대로
 *   diagnostics  [심각도 번호, 코드 번호, message, 위치]
 *   위치         [파일 번호, startLine, startColumn, endLine, endColumn]. 없으면 0
 * </pre>
 * 줄 끝의 칸은 기본값이면 적지 않는다: attrs {@code {}}, 위치 없음, 신뢰도 {@code RESOLVED}. 위치의 끝 칸도 같다:
 * endColumn = startColumn, endLine = startLine, startColumn = 0, startLine = 0.
 * 노드·엣지·진단의 순서와 attrs의 키 순서는 {@code graph.json}과 같다.
 */
final class CompactGraphJson {

    /** 이 클래스가 쓰는 형식의 판. 뷰어는 자기가 아는 판만 읽는다. */
    static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonGenerator json;
    private final Map<String, Integer> stringAt = new HashMap<>();
    private final List<String> strings = new ArrayList<>();

    private CompactGraphJson(JsonGenerator json) {
        this.json = json;
    }

    /** 그래프를 줄인 JSON(UTF-8)으로 쓴다. out은 닫지 않는다. */
    static void write(GraphModel graph, OutputStream out) throws IOException {
        JsonGenerator json = MAPPER.getFactory().createGenerator(out, JsonEncoding.UTF8);
        json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        new CompactGraphJson(json).write(graph);
        json.flush();
    }

    private void write(GraphModel graph) throws IOException {
        List<Node> nodes = GraphJson.orderedNodes(graph);
        Map<String, Integer> nodeAt = new HashMap<>(nodes.size() * 2);
        for (int i = 0; i < nodes.size(); i++) {
            nodeAt.put(nodes.get(i).id(), i);
        }

        json.writeStartObject();
        json.writeNumberField("compact", VERSION);
        json.writeNumberField("schemaVersion", GraphJson.SCHEMA_VERSION);
        json.writeObjectField("project", GraphJson.projectJson(graph.project()));

        json.writeArrayFieldStart("nodes");
        for (Node node : nodes) {
            boolean hasAttrs = !node.attrs().isEmpty();
            json.writeStartArray();
            json.writeString(node.id());
            json.writeNumber(ref(node.kind().name()));
            json.writeString(node.name());
            json.writeString(node.qualifiedName());
            writeTail(node.location(), hasAttrs ? node.attrs() : null);
            json.writeEndArray();
        }
        json.writeEndArray();

        json.writeArrayFieldStart("edges");
        for (Edge edge : GraphJson.orderedEdges(graph)) {
            boolean hasAttrs = !edge.attrs().isEmpty();
            json.writeStartArray();
            writeEnd(edge.from(), nodeAt);
            writeEnd(edge.to(), nodeAt);
            json.writeNumber(ref(edge.kind().name()));
            if (edge.confidence() != Confidence.RESOLVED || edge.location() != null || hasAttrs) {
                json.writeNumber(ref(edge.confidence().name()));
            }
            writeTail(edge.location(), hasAttrs ? edge.attrs() : null);
            json.writeEndArray();
        }
        json.writeEndArray();

        json.writeArrayFieldStart("diagnostics");
        for (Diagnostic diagnostic : GraphJson.orderedDiagnostics(graph)) {
            json.writeStartArray();
            json.writeNumber(ref(diagnostic.severity().name()));
            json.writeNumber(ref(diagnostic.code()));
            json.writeString(diagnostic.message());
            writeTail(diagnostic.location(), null);
            json.writeEndArray();
        }
        json.writeEndArray();

        // 표는 줄을 쓰면서 채워지므로 맨 끝에 쓴다.
        json.writeArrayFieldStart("strings");
        for (String text : strings) {
            json.writeString(text);
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    /** 글자의 표 번호. 처음 보는 글자는 표 끝에 더한다. */
    private int ref(String text) {
        Integer at = stringAt.get(text);
        if (at == null) {
            at = strings.size();
            strings.add(text);
            stringAt.put(text, at);
        }
        return at;
    }

    /** 엣지의 한쪽 끝: 노드의 자리. 그래프에 없는 노드는 ID를 그대로 쓴다. */
    private void writeEnd(String id, Map<String, Integer> nodeAt) throws IOException {
        Integer at = nodeAt.get(id);
        if (at == null) {
            json.writeString(id);
        } else {
            json.writeNumber(at);
        }
    }

    /** 줄 끝의 위치와 attrs. attrs가 없으면(null) 적지 않고, 위치까지 없으면 둘 다 적지 않는다. */
    private void writeTail(SourceLocation location, Map<String, Object> attrs) throws IOException {
        if (location == null && attrs == null) {
            return;
        }
        if (location == null) {
            json.writeNumber(0);
        } else {
            writeLocation(location);
        }
        if (attrs != null) {
            json.writeObject(new TreeMap<>(attrs));
        }
    }

    private void writeLocation(SourceLocation location) throws IOException {
        int[] cells = {location.startLine(), location.startColumn(), location.endLine(), location.endColumn()};
        int count = cells.length;
        if (cells[3] == cells[1]) {
            count = 3;
            if (cells[2] == cells[0]) {
                count = 2;
                if (cells[1] == 0) {
                    count = cells[0] == 0 ? 0 : 1;
                }
            }
        }
        json.writeStartArray();
        json.writeNumber(ref(location.file()));
        for (int i = 0; i < count; i++) {
            json.writeNumber(cells[i]);
        }
        json.writeEndArray();
    }
}
