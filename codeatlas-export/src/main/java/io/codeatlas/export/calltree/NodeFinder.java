package io.codeatlas.export.calltree;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.common.NodeLabels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 사용자가 준 문자열로 시작 노드를 찾는다. CLI가 노드 ID를 모르는 사용자 입력을 받을 때 쓴다.
 *
 * <p>맞추는 순서(앞 단계에서 하나라도 찾으면 거기서 멈춘다):
 * <ol>
 * <li>노드 ID가 같다.</li>
 * <li>qualifiedName, ID의 나머지, 이름, 짧은 표시 이름 중 하나와 같다. 메서드는 매개변수 없는 꼴({@code Type#name})도 본다.
 *     엔드포인트는 프로퍼티 자리표시자를 치환한 경로({@code resolvedPath})로도 찾는다.</li>
 * <li>2와 같되 대소문자를 가리지 않는다.</li>
 * <li>qualifiedName이나 ID의 나머지가 {@code . / # $} 경계 뒤에서 질의로 끝난다.</li>
 * <li>qualifiedName이나 ID의 나머지에 질의가 들어 있다(대소문자 무시).</li>
 * </ol>
 * 결과는 ID 순이다.
 */
public final class NodeFinder {

    private NodeFinder() {
    }

    /**
     * @param kinds 찾을 노드 종류. 비면 FILE과 JAVA_PACKAGE를 뺀 전부
     */
    public static List<Node> find(GraphModel graph, String query, NodeKind... kinds) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.strip();
        Set<NodeKind> wanted = kinds == null || kinds.length == 0 ? EnumSet.allOf(NodeKind.class) : EnumSet.copyOf(Arrays.asList(kinds));
        if (kinds == null || kinds.length == 0) {
            wanted.remove(NodeKind.FILE);
            wanted.remove(NodeKind.JAVA_PACKAGE);
        }
        Node exact = graph.node(q).orElse(null);
        if (exact != null && (kinds == null || kinds.length == 0 || wanted.contains(exact.kind()))) {
            return List.of(exact);
        }
        List<Node> nodes = new ArrayList<>();
        for (Node node : graph.nodes()) {
            if (wanted.contains(node.kind())) {
                nodes.add(node);
            }
        }
        nodes.sort(Comparator.comparing(Node::id));
        String lower = q.toLowerCase(Locale.ROOT);
        List<Node> sameCase = new ArrayList<>();
        List<Node> anyCase = new ArrayList<>();
        List<Node> suffix = new ArrayList<>();
        List<Node> contains = new ArrayList<>();
        for (Node node : nodes) {
            Set<String> names = names(graph, node);
            if (names.contains(q)) {
                sameCase.add(node);
            } else if (names.stream().anyMatch(n -> n.equalsIgnoreCase(q))) {
                anyCase.add(node);
            } else {
                String rest = Ids.remainder(node.id());
                String qualified = node.qualifiedName() == null ? "" : node.qualifiedName();
                String resolved = node.kind() == NodeKind.HTTP_ENDPOINT && node.attrString("resolvedPath") != null
                        ? node.attrString("resolvedPath") : "";
                if (endsAtBoundary(rest, q) || endsAtBoundary(qualified, q) || endsAtBoundary(resolved, q)) {
                    suffix.add(node);
                } else if (rest.toLowerCase(Locale.ROOT).contains(lower) || qualified.toLowerCase(Locale.ROOT).contains(lower)
                        || resolved.toLowerCase(Locale.ROOT).contains(lower)) {
                    contains.add(node);
                }
            }
        }
        for (List<Node> tier : List.of(sameCase, anyCase, suffix, contains)) {
            if (!tier.isEmpty()) {
                return tier;
            }
        }
        return List.of();
    }

    private static Set<String> names(GraphModel graph, Node node) {
        Set<String> names = new LinkedHashSet<>();
        String rest = Ids.remainder(node.id());
        names.add(rest);
        add(names, node.qualifiedName());
        add(names, node.name());
        String label = NodeLabels.label(graph, node.id());
        names.add(label);
        if (node.kind() == NodeKind.JAVA_METHOD) {
            names.add(beforeParen(rest));
            names.add(beforeParen(label));
            names.add(NodeLabels.methodLabel(rest, true));
        } else if (node.kind() == NodeKind.SQL_STATEMENT) {
            names.add(NodeLabels.statementName(node, node.id()));
        } else if (node.kind() == NodeKind.HTTP_ENDPOINT && node.attrString("resolvedPath") != null) {
            // 치환한 경로로도 찾는다: "/a/sys/user/list", "GET /a/sys/user/list"
            String resolved = node.attrString("resolvedPath");
            names.add(resolved);
            int space = rest.indexOf(' ');
            if (space > 0) {
                names.add(rest.substring(0, space + 1) + resolved);
            }
        }
        return names;
    }

    private static void add(Set<String> names, String value) {
        if (value != null && !value.isEmpty()) {
            names.add(value);
        }
    }

    private static String beforeParen(String s) {
        int paren = s.indexOf('(');
        return paren < 0 ? s : s.substring(0, paren);
    }

    private static boolean endsAtBoundary(String text, String query) {
        if (!text.endsWith(query) || text.length() == query.length()) {
            return false;
        }
        char before = text.charAt(text.length() - query.length() - 1);
        return before == '.' || before == '/' || before == '#' || before == '$';
    }
}
