package io.codeatlas.cli;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.calltree.CallTree;
import io.codeatlas.export.calltree.CallTreeOptions;
import io.codeatlas.export.calltree.CallTreeOptions.Direction;
import io.codeatlas.export.calltree.CallTrees;
import io.codeatlas.export.calltree.NodeFinder;
import io.codeatlas.export.common.NodeLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** {@code calltree} 명령(설계 4.5): 검색어나 노드 ID로 시작 노드를 찾아 콜트리(F-015)를 낸다. */
final class CallTreeCommand extends QueryCommand {

    /** 후보가 여럿일 때 보여 줄 최대 개수. */
    private static final int MAX_CANDIDATES = 30;

    private enum Format { TEXT, JSON, MERMAID }

    private static final Map<String, Format> FORMATS = new LinkedHashMap<>();

    static {
        FORMATS.put("text", Format.TEXT);
        FORMATS.put("json", Format.JSON);
        FORMATS.put("mermaid", Format.MERMAID);
    }

    @Override
    public String name() {
        return "calltree";
    }

    @Override
    public String synopsis() {
        return """
                codeatlas calltree <검색어 또는 노드 ID> [--callers] [--depth <n>] [--max-nodes <n>] [--no-impl] [--no-inject]
                                   [--no-collapse] [--show-external] [--show-accessors] [--views] [--layouts] [--include-data-load]
                                   [--no-location] [--kind <NodeKind>]... [--pick <번호>]
                                   [--format text|json|mermaid] [--graph <경로>] [--out <파일>]""";
    }

    @Override
    public String help() {
        return """
                calltree: 노드 하나에서 시작하는 콜트리를 낸다. 화면 → 엔드포인트 → Java → 매퍼 구문 → 테이블·루틴까지 이어진다.
                  시작 노드는 노드 ID, 이름(OrderService#find, 'GET /orders/{id}', ORDERS 등)이나 그 일부로 찾는다.
                  후보가 여럿이면 목록(번호, 종류, ID, 위치)을 표준 오류에 내고 종료 코드 1로 끝난다. 다만 검색어와 표시 이름이
                  정확히 같은 후보가 엔드포인트 > 메서드 > 구문 > 테이블 > 루틴 > 배치 잡 > JSP > JS 함수 순에서 가장 앞선 종류에
                  하나뿐이면 그것을 고르고(예: 엔드포인트와 같은 글자의 화면 요청들), 다른 후보의 수만 표준 오류에 알린다.
                  검색어가 '[METHOD ]/경로' 꼴이면 URL로 보고 엔드포인트를 찾는다('GET /egov/cmm/fms/FileDown.do?atchFileId=1'):
                  쿼리(?…)와 #…를 떼고, 메서드가 같거나 ANY인 엔드포인트(메서드를 안 주면 그 경로의 전부)에 맞추며, 경로 변수
                  패턴(/orders/{id})에 구체 경로(/orders/15)를 줘도 맞는다. 못 찾으면 맨 앞 1~2 세그먼트(컨텍스트 경로)를 떼고
                  다시 찾는다. 맞은 엔드포인트가 하나면 같은 경로의 화면 요청이 몇 개든 그것을 고르고 표준 오류에 한 줄로 알린다.
                  화면 요청에서 시작하려면 --kind HTTP_REQUEST를 준다. 맞는 엔드포인트가 없으면(프런트만 분석한 그래프 등)
                  같은 경로의 화면 요청을 찾는다. 화면 요청의 경로 패턴('/api/schedule/{*}')에도 구체 경로('/api/schedule/12')가
                  맞고, 엔드포인트처럼 컨텍스트 경로를 떼고 다시 찾는다. 메서드 후보가 있는 요청(이름이 'POST|PUT /경로',
                  ID에는 ANY)은 그 후보에 든 메서드에만 맞고(후보 목록의 줄 끝에 [POST|PUT]로 적는다), 후보가 없는 ANY 요청은
                  어느 메서드에나 맞는다. URL로 찾지 못하면 같은 경로에 다른 메서드의 엔드포인트(없으면 화면 요청)가 있는지 알려 준다.
                  action 없는 폼의 요청(자기 페이지로 제출)은 경로가 없어 URL로는 찾지 못한다: 그 화면(JSP)이나 노드 ID로 찾는다.
                  --callers            위 방향(시작 노드를 호출·실행·요청하는 쪽). 기본은 아래 방향(callees)이다.
                  --depth <n>          따라갈 최대 걸음 수. 기본값 10
                  --max-nodes <n>      트리의 줄 수 상한. 기본값 2000
                  --no-impl            인터페이스·추상 메서드를 구현으로 펼치지 않는다.
                  --no-inject          주입(INJECTS) 정보로 구현을 좁히지 않고 구현을 모두 펼친다.
                  --no-collapse        이미 펼친 노드를 다시 만나도 [see above]로 줄이지 않는다.
                  --show-external      소스가 없는 메서드(라이브러리, JDK)의 호출도 보인다. 기본은 표준 산출물처럼 접는다.
                  --show-accessors     접근자 호출도 보인다. 기본은 표준 산출물처럼 접는다(Lombok이 만들 멤버와, 같은 타입에 대응하는
                                       필드가 있고 다른 호출이 나가지 않는 getXxx/isXxx/setXxx). 함께 접혀 있던 부수 효과 없는
                                       생성자(본문에서 호출이 나가지 않는 <init>)와 MyBatis Generator의 *Example·*Example$Criteria
                                       빌더 호출도 이 옵션으로 편다.
                  --views              화면 이동 너머까지 펼친다: 컨트롤러가 그리는 JSP 아래(include, 스크립트, 그 화면에서 나가는 요청)와
                                       redirect·forward로 넘어가는 핸들러의 아래. 기본은 그 줄만 적고 펼치지 않는다.
                  --layouts            레이아웃(sitemesh 데코레이터, tiles template과 조각) 너머까지 펼친다. 기본은 [layout] 한 줄만 적고,
                                       위 방향에서는 그 레이아웃을 쓰는 화면을 "쓰는 화면 n개" 한 줄로 접는다(--views와는 따로다).
                                       위 방향(--callers)의 "[shared] <JSP> (포함하는 화면 n개)" 줄(여러 화면에 include되는
                                       공통 JSP)도 이 옵션으로 펼친다.
                  --include-data-load  데이터 적재 구문(스크립트의 리터럴 INSERT를 테이블별로 묶은 것)도 넣는다. 기본은 뺀다.
                  --no-location        text 형식에서 소스 위치(file:line)를 뺀다.
                  --kind <NodeKind>    후보를 이 종류로 좁힌다. 여러 번 주거나 쉼표로 잇는다. 예: JAVA_METHOD, SQL_STATEMENT, DB_TABLE
                  --pick <번호>        후보 목록에서 이 번호의 노드를 고른다.
                  --format <형식>      text(기본값), json, mermaid
                  줄 앞의 ~는 추론(INFERRED), ?는 미해결(UNRESOLVED) 관계다. [impl]은 구현, [before]는 요청으로 진입한 핸들러보다 먼저
                  실행되는 @ModelAttribute 메서드, [redirect]·[forward]는 다른 엔드포인트의 핸들러로 넘어감, [route]는 화면 안의 이동
                  (클라이언트 라우트), [layout]은 레이아웃, [ambiguous n]은 오버로드를 가리지 못해 후보 n개로 이은 호출이나
                  후보 엔드포인트 n개와 맞은 요청(아래 줄이 후보. n은 2 이상: 후보가 하나뿐이면 붙지 않는다),
                  [reflect]는 리플렉션으로 추론한 호출, [?]는 수신자 타입에 맞는 대상을 고르지 못해 전부 따라간 줄이다.
                  줄 끝의 괄호는 그 관계를 어떻게 이었는지다. (인자 구문)은 구문 ID를 인자로 받는 메서드에 넘긴 문자열로 이은 구문 실행,
                  (접두 일치)는 구문 ID의 앞부분만 정해져 있어 그 접두로 시작하는 구문으로 이은 실행,
                  (동적 뷰 이름 후보)는 뷰 이름의 동적 자리에 실제 폴더 이름을 넣어 찾은 JSP다(셋 다 추론이라 ~가 붙는다).
                  그 후보를 모듈의 프로퍼티 값이나 모듈 폴더 이름으로 좁힌 것은 (동적 뷰 이름: 프로퍼티 값으로 정함),
                  (동적 뷰 이름: 모듈 이름으로 정함)으로 적는다.
                  요청 이름의 POST|PUT은 메서드를 하나로 정하지 못한 요청의 메서드 후보이고,
                  (자기 페이지로 제출)은 action이 없는 폼이 현재 URL로 보내는 요청이다(그 화면을 그리는 핸들러의 엔드포인트로 잇는다).
                  맞는 엔드포인트가 없으면 '? [unresolved] POST /경로 (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에
                  POST 매핑 없음)'처럼 찾던 주소와 까닭을 적고, 후보가 여럿이면 '[ambiguous n] 후보 엔드포인트' 아래에 묶는다.
                  위 방향(--callers)에서 엔드포인트가 시작이면 그 URL로 넘어오는 [forward]·[redirect] 핸들러도 한 줄씩 나오고,
                  핸들러가 여러 웹 모듈에 있는 엔드포인트면 요청 줄에 (모듈 <경로>)를 붙인다.
                """ + COMMON_HELP;
    }

    @Override
    String queryLabel() {
        return "검색어(또는 노드 ID)";
    }

    @Override
    List<String> flags() {
        // --no-views와 --hide-external은 전에 쓰던 옵션이다. 지금은 그것이 기본이라 받기만 하고 아무 일도 하지 않는다(도움말에는 없다).
        return List.of("--callers", "--no-impl", "--no-inject", "--no-collapse", "--show-external", "--hide-external", "--show-accessors",
                "--views", "--no-views", "--layouts", "--include-data-load", "--no-location");
    }

    @Override
    List<String> valueOptions() {
        return List.of("--depth", "--max-nodes", "--kind", "--pick", "--format");
    }

    @Override
    Query prepare(CommandLine line, String query) {
        CallTreeOptions defaults = CallTreeOptions.callees();
        CallTreeOptions options = CallTreeOptions.builder()
                .direction(line.has("--callers") ? Direction.CALLERS : Direction.CALLEES)
                .maxDepth(line.number("--depth", defaults.maxDepth(), 0))
                .maxNodes(line.number("--max-nodes", defaults.maxNodes(), 1))
                .expandImplementations(!line.has("--no-impl"))
                .narrowByInjection(!line.has("--no-inject"))
                .collapseRepeated(!line.has("--no-collapse"))
                .hideExternal(!line.has("--show-external"))
                .hideAccessors(!line.has("--show-accessors"))
                .expandRenderedViews(line.has("--views"))
                .expandLayouts(line.has("--layouts"))
                .includeDataLoad(line.has("--include-data-load"))
                .build();
        boolean locations = !line.has("--no-location");
        Function<CallTree, String> renderer = switch (line.choice("--format", FORMATS, Format.TEXT)) {
            case TEXT -> tree -> CallTrees.toText(tree, locations);
            case JSON -> CallTrees::toJson;
            case MERMAID -> CallTrees::toMermaid;
        };
        NodeKind[] kinds = NodeListing.kinds(line.values("--kind"));
        int pick = line.number("--pick", 0, 1);
        return (graph, err) -> {
            // [METHOD ]<경로> 꼴이면 먼저 URL로 엔드포인트와 같은 경로의 화면 요청을 찾는다(--kind로 둘 다 뺐으면 하지 않는다).
            // --kind로 뺀 종류는 후보에서 빼고, 남는 것이 없으면 전처럼 이름으로 찾는다.
            boolean wantsEndpoints = wants(kinds, NodeKind.HTTP_ENDPOINT);
            boolean wantsRequests = wants(kinds, NodeKind.HTTP_REQUEST);
            EndpointLookup.Match url = wantsEndpoints || wantsRequests ? EndpointLookup.find(graph, query) : null;
            List<Node> urlEndpoints = url != null && wantsEndpoints ? url.endpoints() : List.of();
            List<Node> candidates = new ArrayList<>(urlEndpoints);
            if (url != null && wantsRequests) {
                candidates.addAll(url.requests());
            }
            if (candidates.isEmpty()) {
                url = null;
                candidates = NodeFinder.find(graph, query, kinds);
            }
            if (candidates.isEmpty()) {
                throw new CommandFailure("'" + query + "'에 맞는 노드를 찾지 못했다" + (kinds.length > 0 ? "(--kind로 좁힌 범위)" : "")
                        + urlAdvice(graph, query, wantsEndpoints, wantsRequests) + ". 'codeatlas find <검색어>'로 노드 ID를 찾는다");
            }
            Node start;
            if (pick > 0) {
                if (pick > candidates.size()) {
                    throw new CommandFailure("--pick " + pick + ": 후보는 " + candidates.size() + "개다.\n"
                            + NodeListing.table(graph, candidates, MAX_CANDIDATES).stripTrailing());
                }
                start = candidates.get(pick - 1);
            } else if (url != null && urlEndpoints.size() == 1) {
                // 엔드포인트가 정확히 하나면 같은 경로의 화면 요청이 몇 개든 그것을 고른다
                start = urlEndpoints.get(0);
                String notice = urlNotice(query, start, url, candidates.size() - 1);
                if (notice != null) {
                    err.println(notice);
                }
            } else if (url != null && url.endpoints().isEmpty() && candidates.size() == 1) {
                // 엔드포인트 없이 화면 요청 하나만 맞았다: 글자 그대로 맞았으면 알림 없이, 패턴이나 컨텍스트 경로로 맞췄으면 그 사실을 알린다
                start = candidates.get(0);
                String notice = urlNotice(query, start, url, 0);
                if (notice != null) {
                    err.println(notice);
                }
            } else if (candidates.size() > 1) {
                Node exact = exactMatchOfLeadingKind(graph, query, candidates);
                if (exact == null) {
                    throw new CommandFailure(ambiguous(query, candidates.size())
                            + NodeListing.table(graph, candidates, MAX_CANDIDATES).stripTrailing()
                            + (candidates.size() > MAX_CANDIDATES ? "\n... 외 " + (candidates.size() - MAX_CANDIDATES) + "개" : ""));
                }
                start = exact;
                err.println("[codeatlas] '" + query + "'와 이름이 같은 " + exact.kind() + " " + exact.id() + "를 골랐다. 다른 후보 "
                        + (candidates.size() - 1) + "개는 --kind <NodeKind>로 고른다(목록은 codeatlas find)");
            } else {
                start = candidates.get(0);
            }
            return renderer.apply(CallTrees.build(graph, start.id(), options));
        };
    }

    /** 오류 안내에 적는 "같은 경로의 다른 메서드"의 최대 개수. */
    private static final int MAX_OTHER_METHODS = 5;

    /**
     * URL 꼴 검색어로 아무것도 찾지 못했을 때 오류 문구에 덧붙일 안내(앞에 마침표를 둔 문장들). URL 꼴이 아니면 빈 문자열.
     * 메서드가 달라 못 찾았으면 같은 경로의 다른 메서드 엔드포인트(없으면 화면 요청)를 알리고, 경로 변수가 든 URL은 패턴 꼴로도
     * 찾을 수 있다고 알린다(F-001-R59).
     */
    private static String urlAdvice(GraphModel graph, String query, boolean wantsEndpoints, boolean wantsRequests) {
        if ((!wantsEndpoints && !wantsRequests) || !EndpointLookup.isUrlQuery(query)) {
            return "";
        }
        StringBuilder advice = new StringBuilder();
        List<Node> others = EndpointLookup.otherMethods(graph, query, wantsEndpoints, wantsRequests);
        if (!others.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Node other : others) {
                String name = NodeLabels.label(graph, other.id());
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
            advice.append(". 같은 경로에 다른 메서드의 ").append(others.get(0).kind() == NodeKind.HTTP_ENDPOINT ? "엔드포인트가" : "화면 요청이")
                    .append(" 있다: ").append(String.join(", ", names.subList(0, Math.min(MAX_OTHER_METHODS, names.size()))))
                    .append(names.size() > MAX_OTHER_METHODS ? " 외 " + (names.size() - MAX_OTHER_METHODS) + "개" : "");
        }
        advice.append(". 경로 변수가 든 URL은 패턴 꼴('/orders/{id}', 화면 요청은 '/api/orders/{*}')로도 찾는다");
        return advice.toString();
    }

    /** {@code --kind}가 없거나 이 종류를 포함하는가. */
    private static boolean wants(NodeKind[] kinds, NodeKind kind) {
        return kinds.length == 0 || List.of(kinds).contains(kind);
    }

    /**
     * URL로 엔드포인트 하나를 골랐을 때 표준 오류에 낼 한 줄. 검색어가 그 엔드포인트를 글자 그대로 가리키고 다른 후보도 없으면 null이다.
     * 알리는 것: 경로 변수 패턴으로 맞췄음, 컨텍스트 경로를 뗐음, 고르지 않은 후보(같은 경로의 화면 요청)의 수.
     */
    private static String urlNotice(String query, Node endpoint, EndpointLookup.Match url, int others) {
        List<String> how = new ArrayList<>();
        if (url.contextPath() != null) {
            how.add("컨텍스트 경로 " + url.contextPath() + "를 떼고 " + url.path() + "로 찾음");
        }
        if (url.byPattern()) {
            how.add("경로 변수 패턴에 맞춤");
        }
        if (how.isEmpty() && others == 0) {
            return null;
        }
        StringBuilder line = new StringBuilder("[codeatlas] '" + query + "'에 맞는 " + endpoint.kind() + " " + endpoint.id() + "를 골랐다");
        if (!how.isEmpty()) {
            line.append('(').append(String.join(", ", how)).append(')');
        }
        if (others > 0) {
            line.append(". 다른 후보 ").append(others).append("개(같은 경로의 화면 요청)는 --kind HTTP_REQUEST로 고른다");
        }
        return line.toString();
    }

    /** 표시 이름이 검색어와 정확히 같은 후보를 고를 때의 종류 우선순위. 여기 없는 종류는 그 뒤다. */
    private static final List<NodeKind> KIND_PRIORITY = List.of(NodeKind.HTTP_ENDPOINT, NodeKind.JAVA_METHOD, NodeKind.SQL_STATEMENT,
            NodeKind.DB_TABLE, NodeKind.DB_ROUTINE, NodeKind.BATCH_JOB, NodeKind.JSP_PAGE, NodeKind.JS_FUNCTION);

    /**
     * 후보 가운데 표시 이름(엔드포인트는 {@code resolvedPath} 표기도)이 검색어와 정확히 같은 것을 종류 우선순위로 견줘,
     * 가장 앞선 종류에 하나뿐이면 그것을 돌려준다. 그런 후보가 없거나 그 종류에 여럿이면 null(전처럼 목록을 낸다).
     * 엔드포인트 하나와 같은 글자의 화면 요청 여러 개가 함께 나오는 경우를 위한 것이다.
     */
    static Node exactMatchOfLeadingKind(GraphModel graph, String query, List<Node> candidates) {
        String q = query.strip();
        Map<Integer, List<Node>> byRank = new java.util.TreeMap<>();
        for (Node node : candidates) {
            if (exactNames(graph, node).contains(q)) {
                int rank = KIND_PRIORITY.indexOf(node.kind());
                byRank.computeIfAbsent(rank < 0 ? KIND_PRIORITY.size() : rank, k -> new ArrayList<>()).add(node);
            }
        }
        if (byRank.isEmpty()) {
            return null;
        }
        List<Node> leading = byRank.values().iterator().next();
        return leading.size() == 1 ? leading.get(0) : null;
    }

    /** 노드를 정확히 가리키는 이름: 표시 이름, ID의 나머지, qualifiedName, 엔드포인트의 {@code METHOD resolvedPath}. */
    private static List<String> exactNames(GraphModel graph, Node node) {
        List<String> names = new ArrayList<>();
        names.add(NodeLabels.label(graph, node.id()));
        names.add(Ids.remainder(node.id()));
        if (node.qualifiedName() != null) {
            names.add(node.qualifiedName());
        }
        String resolved = node.kind() == NodeKind.HTTP_ENDPOINT ? node.attrString("resolvedPath") : null;
        if (resolved != null) {
            String rest = Ids.remainder(node.id());
            int space = rest.indexOf(' ');
            names.add(space > 0 ? rest.substring(0, space + 1) + resolved : resolved);
        }
        return names;
    }

    private static String ambiguous(String query, int count) {
        return "'" + query + "'에 맞는 노드가 " + count + "개다. 노드 ID를 그대로 주거나 --pick <번호>, --kind <NodeKind>로 좁힌다.\n";
    }
}
