package io.codeatlas.cli;

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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code *} 와일드카드로 노드 찾기({@code find}. F-001-R50). 패턴은 이름 전체에 맞춘다(대소문자 무시): {@code *}는 글자 0개 이상이고
 * {@code .}·{@code /}·{@code #}도 넘는다. 견주는 이름은 조회 API({@code NodeFinder})가 정확히 같은지 볼 때 쓰는 것과 같다:
 * 노드 ID, ID의 나머지, qualifiedName, 이름, 표시 이름, 메서드의 매개변수 없는 꼴, 구문 이름, 엔드포인트의 {@code resolvedPath} 표기.
 */
final class NodeGlob {

    private NodeGlob() {
    }

    static boolean isGlob(String query) {
        return query != null && query.indexOf('*') >= 0;
    }

    /**
     * @param kinds 찾을 노드 종류. 비면 FILE과 JAVA_PACKAGE를 뺀 전부({@code NodeFinder}와 같다)
     * @return 맞는 노드. ID 순
     */
    static List<Node> find(GraphModel graph, String glob, NodeKind... kinds) {
        Set<NodeKind> wanted = kinds.length == 0 ? EnumSet.allOf(NodeKind.class) : EnumSet.copyOf(Arrays.asList(kinds));
        if (kinds.length == 0) {
            wanted.remove(NodeKind.FILE);
            wanted.remove(NodeKind.JAVA_PACKAGE);
        }
        Pattern pattern = Pattern.compile(Arrays.stream(glob.strip().split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*")),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        List<Node> found = new ArrayList<>();
        for (Node node : graph.nodes()) {
            if (wanted.contains(node.kind()) && names(graph, node).stream().anyMatch(name -> pattern.matcher(name).matches())) {
                found.add(node);
            }
        }
        found.sort(Comparator.comparing(Node::id));
        return found;
    }

    private static Set<String> names(GraphModel graph, Node node) {
        Set<String> names = new LinkedHashSet<>();
        String rest = Ids.remainder(node.id());
        names.add(node.id());
        names.add(rest);
        add(names, node.qualifiedName());
        add(names, node.name());
        String label = NodeLabels.label(graph, node.id());
        add(names, label);
        if (node.kind() == NodeKind.JAVA_METHOD) {
            names.add(beforeParen(rest));
            if (label != null) {
                names.add(beforeParen(label));
            }
            add(names, NodeLabels.methodLabel(rest, true));
        } else if (node.kind() == NodeKind.SQL_STATEMENT) {
            add(names, NodeLabels.statementName(node, node.id()));
        } else if (node.kind() == NodeKind.HTTP_ENDPOINT && node.attrString("resolvedPath") != null) {
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
}
