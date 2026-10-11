package io.codeatlas.cli;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.common.NodeLabels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 노드 후보 목록(번호, 종류, ID, 위치)과 {@code --kind} 값 해석. find, calltree, impact가 함께 쓴다. */
final class NodeListing {

    /** ID 열을 맞출 최대 폭. 이보다 긴 ID는 줄을 맞추지 않고 그대로 적는다. */
    private static final int MAX_ID_WIDTH = 80;

    private NodeListing() {
    }

    /**
     * 한 줄에 노드 하나. 번호는 1부터, 열은 공백으로 맞춘다. 위치를 모르는 노드는 위치 열이 비어 있다.
     * 메서드 후보가 있는 화면 요청({@code attrs.httpMethods}. ID에는 {@code ANY}로 적힌다)은 줄 끝에 {@code [POST|PUT]}처럼 후보를 적는다.
     *
     * @param limit 보여 줄 최대 개수
     */
    static String table(GraphModel graph, List<Node> nodes, int limit) {
        List<Node> shown = nodes.subList(0, Math.min(limit, nodes.size()));
        int numberWidth = String.valueOf(shown.size()).length();
        int kindWidth = shown.stream().mapToInt(n -> n.kind().name().length()).max().orElse(0);
        // ID가 아주 긴 노드(매개변수가 많은 메서드) 하나 때문에 모든 줄의 위치가 멀리 밀리지 않게 맞춤 폭에 상한을 둔다.
        int idWidth = Math.min(MAX_ID_WIDTH, shown.stream().mapToInt(n -> n.id().length()).max().orElse(0));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown.size(); i++) {
            Node node = shown.get(i);
            String location = NodeLabels.location(graph, node.id());
            String line = String.format("%" + numberWidth + "d  %-" + kindWidth + "s  %-" + idWidth + "s  %s",
                    i + 1, node.kind().name(), node.id(), location == null ? "" : location);
            List<String> methods = node.kind() == NodeKind.HTTP_REQUEST ? EndpointLookup.methodCandidates(node) : List.of();
            sb.append(line.stripTrailing()).append(methods.isEmpty() ? "" : "  [" + String.join("|", methods) + "]").append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code --kind} 값들을 노드 종류로 바꾼다. 쉼표로 여러 개를 줄 수 있고 대소문자를 가리지 않는다({@code java_method}, {@code java-method}).
     *
     * @throws UsageException 모르는 종류면
     */
    static NodeKind[] kinds(List<String> values) {
        Set<NodeKind> kinds = new LinkedHashSet<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    kinds.add(NodeKind.valueOf(part.strip().toUpperCase(Locale.ROOT).replace('-', '_')));
                } catch (IllegalArgumentException e) {
                    throw new UsageException("--kind 값을 알 수 없다: " + part.strip() + " ("
                            + Arrays.stream(NodeKind.values()).map(NodeKind::name).collect(Collectors.joining(", ")) + " 중 하나)");
                }
            }
        }
        return new ArrayList<>(kinds).toArray(new NodeKind[0]);
    }
}
