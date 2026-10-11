package io.codeatlas.cli;

import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.calltree.NodeFinder;
import java.util.List;

/** {@code find} 명령(설계 4.5): 검색어에 맞는 노드를 찾아 ID를 보여 준다. calltree와 impact에 줄 ID를 찾을 때 쓴다. */
final class FindCommand extends QueryCommand {

    static final int DEFAULT_LIMIT = 50;

    @Override
    public String name() {
        return "find";
    }

    @Override
    public String synopsis() {
        return "codeatlas find <검색어> [--kind <NodeKind>]... [--limit <n>] [--graph <경로>] [--out <파일>]";
    }

    @Override
    public String help() {
        return """
                find: 검색어에 맞는 노드를 한 줄에 하나씩(번호, 종류, 노드 ID, 위치) 낸다. calltree, impact에 줄 노드 ID를 찾을 때 쓴다.
                  맞추는 순서: 노드 ID가 같음 → 이름이 같음 → 대소문자만 다름 → 전체 이름이 . / # $ 뒤에서 검색어로 끝남 → 검색어가 들어 있음.
                  앞 단계에서 하나라도 찾으면 거기서 멈춘다(이름이 같은 노드가 있으면 부분 일치는 나오지 않고, 그 수를 표준 오류에 알린다).
                  검색어에 *를 쓰면 와일드카드다: 이름 전체에 맞추고 대소문자를 가리지 않는다. 부분 일치를 모두 보려면 '*order*',
                  앞·뒤만 맞추려면 'order*', '*Service#find*', '*.do'(--kind HTTP_ENDPOINT)처럼 쓴다. 셸이 풀지 않게 따옴표로 묶는다.
                  파일과 패키지 노드는 --kind로 골라야 나온다.
                  --kind <NodeKind>    이 종류만 찾는다. 여러 번 주거나 쉼표로 잇는다. 예: JAVA_TYPE, JAVA_METHOD, HTTP_ENDPOINT,
                                       SQL_STATEMENT, DB_TABLE, DB_COLUMN, DB_ROUTINE, JSP_PAGE, JS_FUNCTION, BATCH_JOB
                  --limit <n>          보여 줄 최대 개수. 기본값 50
                """ + COMMON_HELP;
    }

    @Override
    String queryLabel() {
        return "검색어";
    }

    @Override
    List<String> flags() {
        return List.of();
    }

    @Override
    List<String> valueOptions() {
        return List.of("--kind", "--limit");
    }

    @Override
    Query prepare(CommandLine line, String query) {
        NodeKind[] kinds = NodeListing.kinds(line.values("--kind"));
        int limit = line.number("--limit", DEFAULT_LIMIT, 1);
        return (graph, err) -> {
            List<Node> found;
            if (NodeGlob.isGlob(query)) {
                // 글자 그대로 그 ID인 노드가 있으면(경로에 *가 든 엔드포인트) 그것이고, 아니면 *를 와일드카드로 본다
                Node byId = graph.node(query.strip()).orElse(null);
                found = byId != null && (kinds.length == 0 || List.of(kinds).contains(byId.kind())) ? List.of(byId)
                        : NodeGlob.find(graph, query, kinds);
            } else {
                found = NodeFinder.find(graph, query, kinds);
            }
            if (found.isEmpty()) {
                throw new CommandFailure("'" + query + "'에 맞는 노드를 찾지 못했다" + (kinds.length > 0 ? "(--kind로 좁힌 범위)" : ""));
            }
            if (!NodeGlob.isGlob(query)) {
                // 이름이 같은 노드에서 멈췄으면 부분 일치는 보이지 않았다: 그 수와 보는 방법을 알린다
                String partial = "*" + query.strip() + "*";
                int containing = NodeGlob.find(graph, partial, kinds).size();
                if (containing > found.size()) {
                    err.println("이름이 같은(또는 이름이 검색어로 끝나는) 노드 " + found.size() + "개만 보였다. 검색어가 들어 있는 노드는 모두 "
                            + containing + "개다: codeatlas find '" + partial + "'로 본다.");
                }
            }
            if (found.size() > limit) {
                err.println("맞는 노드 " + found.size() + "개 중 " + limit + "개만 보였다. --limit으로 늘리거나 --kind로 좁힌다.");
            }
            return NodeListing.table(graph, found, limit);
        };
    }
}
