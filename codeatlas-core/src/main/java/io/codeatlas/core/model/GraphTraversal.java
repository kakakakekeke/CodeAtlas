package io.codeatlas.core.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 그래프 이웃 탐색 편의 메서드. */
public final class GraphTraversal {

    /** 탐색 방향. */
    public enum Direction {
        /** 나가는 엣지를 따라간다(호출되는 쪽, 포함되는 쪽). */
        OUT,
        /** 들어오는 엣지를 거꾸로 따라간다(호출하는 쪽, 포함하는 쪽). */
        IN
    }

    private GraphTraversal() {
    }

    /**
     * startId에서 엣지를 따라 닿는 노드 ID를 너비 우선 순서로 돌려준다. startId 자신은 넣지 않는다. 순환이 있어도 끝난다.
     *
     * @param maxDepth 따라갈 최대 엣지 수. 1이면 바로 옆 이웃만
     * @param kinds 따라갈 엣지 종류. 비면 모든 종류
     */
    public static Set<String> reachable(GraphModel graph, String startId, Direction direction, int maxDepth, EdgeKind... kinds) {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(startId);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(startId);
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            Deque<String> next = new ArrayDeque<>();
            for (String id : frontier) {
                List<Edge> edges = direction == Direction.OUT ? graph.outgoing(id, kinds) : graph.incoming(id, kinds);
                for (Edge edge : edges) {
                    String other = direction == Direction.OUT ? edge.to() : edge.from();
                    if (seen.add(other)) {
                        visited.add(other);
                        next.add(other);
                    }
                }
            }
            frontier = next;
        }
        return visited;
    }
}
