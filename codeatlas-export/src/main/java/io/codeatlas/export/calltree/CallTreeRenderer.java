package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.calltree.CallTreeNode.Marker;
import io.codeatlas.export.calltree.CallTreeOptions.Direction;
import io.codeatlas.export.common.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 콜트리를 텍스트, JSON, Mermaid flowchart로 바꾼다. */
final class CallTreeRenderer {

    private CallTreeRenderer() {
    }

    // ---- 텍스트 ----

    static String text(CallTree tree, boolean locations) {
        StringBuilder out = new StringBuilder();
        textNode(out, tree.root(), "", "", locations);
        if (tree.truncated()) {
            out.append("... 노드 수 상한 ").append(tree.options().maxNodes()).append("개에 닿아 나머지를 생략했다\n");
        }
        return out.toString();
    }

    private static void textNode(StringBuilder out, CallTreeNode node, String prefix, String childPrefix, boolean locations) {
        out.append(prefix).append(line(node, locations)).append('\n');
        List<CallTreeNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            boolean last = i == children.size() - 1;
            textNode(out, children.get(i), childPrefix + (last ? "└─ " : "├─ "), childPrefix + (last ? "   " : "│  "), locations);
        }
    }

    /**
     * 한 줄: {@code [~|?] [앞 표식] [종류] 이름 (세부)  file:line [뒤 표식]}.
     * 앞 표식은 {@code [impl]}, {@code [via interface]}, {@code [trigger?]}, {@code [?]}(수신자 타입과 맞는 구문을 고르지 못함),
     * {@code [reflect]}, {@code [before]}, {@code [redirect]}, {@code [forward]}, 뒤 표식은 {@code [cycle]}, {@code [see above]}, {@code [depth limit]}이다.
     * 예: {@code ~ [impl] OrderServiceImpl#find(Long)  src/.../OrderServiceImpl.java:42 [see above]}
     */
    static String line(CallTreeNode node, boolean locations) {
        StringBuilder s = new StringBuilder();
        if (node.confidence() == Confidence.INFERRED) {
            s.append("~ ");
        } else if (node.confidence() == Confidence.UNRESOLVED) {
            s.append("? ");
        }
        for (Marker m : node.markers()) {
            if (m.leading()) {
                s.append('[').append(m.text()).append("] ");
            }
        }
        if (!node.kindTag().isEmpty()) {
            s.append('[').append(node.kindTag()).append("] ");
        }
        s.append(node.label());
        if (node.detail() != null && !node.detail().isEmpty()) {
            s.append(" (").append(node.detail()).append(')');
        }
        // JSP나 JS 모듈처럼 이름이 곧 파일 경로인 줄에는 위치를 다시 적지 않는다.
        if (locations && node.location() != null && !node.location().equals(node.label())) {
            s.append("  ").append(node.location());
        }
        for (Marker m : node.markers()) {
            if (!m.leading()) {
                s.append(" [").append(m.text()).append(']');
            }
        }
        return s.toString();
    }

    // ---- JSON ----

    static String json(CallTree tree) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("start", tree.root().id());
        root.put("direction", tree.direction().name());
        root.put("nodeCount", tree.nodeCount());
        root.put("truncated", tree.truncated());
        root.put("root", jsonNode(tree.root()));
        return Json.write(root);
    }

    private static Map<String, Object> jsonNode(CallTreeNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", node.id());
        m.put("kind", node.kind().name());
        m.put("label", node.label());
        m.put("location", node.location());
        m.put("via", node.via() == null ? null : node.via().name());
        m.put("callSite", node.callSite());
        m.put("confidence", node.confidence().name());
        m.put("detail", node.detail());
        List<String> markers = new ArrayList<>();
        node.markers().forEach(marker -> markers.add(marker.text()));
        m.put("markers", markers);
        List<Object> children = new ArrayList<>();
        node.children().forEach(c -> children.add(jsonNode(c)));
        m.put("children", children);
        return m;
    }

    // ---- Mermaid ----

    static String mermaid(CallTree tree) {
        Map<String, String> names = new LinkedHashMap<>();
        List<String> definitions = new ArrayList<>();
        Set<String> edges = new LinkedHashSet<>();
        mermaidNode(tree.root(), null, tree.direction(), names, definitions, edges);
        StringBuilder out = new StringBuilder("flowchart TD\n");
        if (tree.truncated()) {
            out.append("    %% 노드 수 상한 ").append(tree.options().maxNodes()).append("개에 닿아 나머지를 생략했다\n");
        }
        definitions.forEach(d -> out.append("    ").append(d).append('\n'));
        edges.forEach(e -> out.append("    ").append(e).append('\n'));
        return out.toString();
    }

    private static void mermaidNode(CallTreeNode node, String parentName, Direction direction, Map<String, String> names,
            List<String> definitions, Set<String> edges) {
        String name = names.get(node.id());
        if (name == null) {
            name = "n" + names.size();
            names.put(node.id(), name);
            definitions.add(name + shape(node));
        }
        if (parentName != null) {
            String label = null;
            boolean dashed = false;
            if (node.markers().contains(Marker.IMPL)) {
                label = "impl";
                dashed = true;
            } else if (node.markers().contains(Marker.VIA_INTERFACE)) {
                label = "via interface";
                dashed = true;
            } else if (node.markers().contains(Marker.TRIGGER_UNCERTAIN)) {
                label = "trigger?";
                dashed = true;
            } else if (node.markers().contains(Marker.RECEIVER_UNCERTAIN)) {
                label = "?";
                dashed = true;
            } else if (node.markers().contains(Marker.REFLECT)) {
                label = "reflect";
                dashed = true;
            } else if (node.markers().contains(Marker.BEFORE)) {
                label = "before";
                dashed = true;
            } else if (node.markers().contains(Marker.REDIRECT)) {
                label = "redirect";
                dashed = true;
            } else if (node.markers().contains(Marker.FORWARD)) {
                label = "forward";
                dashed = true;
            } else if (node.markers().contains(Marker.LAYOUT)) {
                label = "layout";
                dashed = true;
            } else if (node.confidence() == Confidence.INFERRED) {
                label = "~";
                dashed = true;
            } else if (node.confidence() == Confidence.UNRESOLVED) {
                label = "?";
                dashed = true;
            } else if (node.detail() != null && !node.detail().isEmpty()) {
                label = node.detail();
            }
            String arrow = (dashed ? "-.->" : "-->") + (label == null ? "" : "|\"" + escape(label) + "\"|");
            edges.add(direction == Direction.CALLEES ? parentName + " " + arrow + " " + name : name + " " + arrow + " " + parentName);
        }
        for (CallTreeNode child : node.children()) {
            mermaidNode(child, name, direction, names, definitions, edges);
        }
    }

    /** 엔드포인트는 둥근 모양, 테이블·뷰는 원통, 나머지는 사각형. */
    private static String shape(CallTreeNode node) {
        if (node.kind() == NodeKind.HTTP_ENDPOINT) {
            return "([\"" + escape(node.label()) + "\"])";
        }
        if (node.kind() == NodeKind.DB_TABLE || node.kind() == NodeKind.DB_VIEW) {
            return "[(\"" + escape(node.label()) + "\")]";
        }
        String text = node.kindTag().isEmpty() ? node.label() : "[" + node.kindTag() + "] " + node.label();
        return "[\"" + escape(text) + "\"]";
    }

    /** 따옴표 라벨 안에서 HTML로 해석되거나 문법을 깨는 문자를 Mermaid 엔티티 코드로 바꾼다. */
    private static String escape(String s) {
        return s.replace("#", "#35;").replace("\"", "#quot;").replace("<", "#lt;").replace(">", "#gt;").replace("|", "#124;")
                .replaceAll("[\\r\\n]+", " ");
    }
}
