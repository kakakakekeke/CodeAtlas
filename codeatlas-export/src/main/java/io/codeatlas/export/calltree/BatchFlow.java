package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 배치 스텝을 실행 흐름({@code NEXT}) 순서로 늘어놓는다. 콜트리의 잡 아래 스텝과 {@code calltree/batch-jobs.md}의 절 순서에 쓴다.
 *
 * <ul>
 * <li>들어오는 NEXT가 없는 스텝(시작 스텝)부터 NEXT를 따라간다. 스텝은 그것으로 오는 스텝들 뒤에 놓인다.</li>
 * <li>분기(한 스텝에서 나가는 NEXT가 여럿)는 대상의 ID 순이다.</li>
 * <li>순환(재시도처럼 앞 스텝으로 돌아가는 NEXT)은 무시한다. 시작 스텝 없이 순환뿐이면 ID가 앞선 스텝부터 따라간다.</li>
 * <li>NEXT가 하나도 걸리지 않은 스텝은 맨 뒤에 ID 순으로 둔다.</li>
 * </ul>
 * 주어진 스텝 밖으로 나가거나 밖에서 들어오는 NEXT는 보지 않는다. 입력 순서와 무관하게 같은 결과를 낸다.
 */
public final class BatchFlow {

    private BatchFlow() {
    }

    /** stepIds를 흐름 순서로 늘어놓은 새 목록. 중복은 한 번만 나온다. */
    public static List<String> order(GraphModel graph, Collection<String> stepIds) {
        Set<String> steps = new TreeSet<>(stepIds);
        Set<String> hasIncoming = new HashSet<>();
        Set<String> connected = new HashSet<>();
        for (String step : steps) {
            for (String next : successors(graph, step, steps)) {
                connected.add(step);
                connected.add(next);
                if (!next.equals(step)) {
                    hasIncoming.add(next);
                }
            }
        }
        // 깊이 우선 탐색의 끝난 순서를 뒤집으면 흐름 순서(위상 순서)가 된다. 형제를 ID 역순으로 돌아야 뒤집었을 때 ID 순이다.
        List<String> finished = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<String> starts = new ArrayList<>();
        for (String step : steps) {
            if (connected.contains(step) && !hasIncoming.contains(step)) {
                starts.add(step);
            }
        }
        Collections.reverse(starts);
        for (String start : starts) {
            visit(graph, start, steps, visited, finished);
        }
        Collections.reverse(finished);
        List<String> ordered = new ArrayList<>(finished);
        // 시작 스텝에서 닿지 않는 순환: ID가 앞선 스텝부터 하나씩 따라간다.
        for (String step : steps) {
            if (connected.contains(step) && !visited.contains(step)) {
                List<String> loop = new ArrayList<>();
                visit(graph, step, steps, visited, loop);
                Collections.reverse(loop);
                ordered.addAll(loop);
            }
        }
        for (String step : steps) {
            if (!connected.contains(step)) {
                ordered.add(step);
            }
        }
        return ordered;
    }

    private static void visit(GraphModel graph, String step, Set<String> steps, Set<String> visited, List<String> finished) {
        if (!visited.add(step)) {
            return;
        }
        List<String> next = successors(graph, step, steps);
        Collections.reverse(next);
        for (String n : next) {
            visit(graph, n, steps, visited, finished);
        }
        finished.add(step);
    }

    /** step에서 NEXT로 가는 스텝(주어진 범위 안, ID 순, 중복 없이). */
    private static List<String> successors(GraphModel graph, String step, Set<String> steps) {
        Set<String> next = new TreeSet<>(Comparator.naturalOrder());
        for (Edge e : graph.outgoing(step, EdgeKind.NEXT)) {
            if (steps.contains(e.to())) {
                next.add(e.to());
            }
        }
        return new ArrayList<>(next);
    }
}
