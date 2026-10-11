package io.codeatlas.cli;

import io.codeatlas.core.model.Node;
import io.codeatlas.export.calltree.ImpactAnalysis;
import io.codeatlas.export.calltree.ImpactReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code impact} 명령(설계 4.5): 테이블이나 컬럼을 읽고 쓰는 구문, 메서드, 엔드포인트, 화면을 거꾸로 찾는다(F-015). */
final class ImpactCommand extends QueryCommand {

    /** 대상을 찾지 못했을 때 보여 줄 비슷한 이름의 최대 개수. */
    private static final int MAX_SUGGESTIONS = ImpactReport.MAX_SUGGESTIONS_SHOWN;

    private static final Map<String, Boolean> FORMATS = new LinkedHashMap<>();

    static {
        FORMATS.put("md", Boolean.FALSE);
        FORMATS.put("json", Boolean.TRUE);
    }

    @Override
    public String name() {
        return "impact";
    }

    @Override
    public String synopsis() {
        return """
                codeatlas impact <테이블 | 테이블.컬럼 | 노드 ID> [--depth <n>] [--include-data-load] [--format md|json]
                                 [--graph <경로>] [--out <파일>]""";
    }

    @Override
    public String help() {
        return """
                impact: 테이블, 뷰, 컬럼을 읽고 쓰는 구문(C/R/U/D) → 실행하는 메서드·루틴·배치 스텝 → 호출 사슬 → 엔드포인트 → 화면을
                  계층별로 모은 영향도 리포트를 낸다. 대상은 이름(ORDERS, ORDERS.STATUS, 대소문자 무시)이나
                  노드 ID(db:table:ORDERS, db:column:ORDERS.STATUS)로 준다. 메서드 같은 다른 노드는 calltree --callers를 쓴다.
                  --depth <n>          구문에서 거슬러 올라갈 최대 걸음 수. 기본값 30
                  --include-data-load  데이터 적재 구문(스크립트의 리터럴 INSERT를 테이블별로 묶은 것)도 넣는다. 기본은 빼고 뺀 수를
                                       리포트 머리에 알린다.
                  --format <형식>      md(기본값), json
                  이름 앞의 ~는 추론한 관계를 거쳐서만 닿은 항목이고(경로 끝의 '추론:'이 근거), [forward]는 forward로 넘어간 다른
                  엔드포인트의 핸들러를 거쳐서만 닿은 항목이다(뷰 이름 forward:나 서블릿 API. 핸들러 자신의 호출 사슬로 닿는 경로가
                  있으면 붙지 않는다). 경로에서는 넘어간 대상 핸들러 앞에 [forward]가, 넘어간 경로를 알면 끝에 (forward: <경로>)가
                  붙고, 머리의 개수 요약과 계층 제목은 '엔드포인트 43개(직접 39, forward 경유 4)'처럼 나뉜다.
                  json은 항목마다 viaForward(true/false)와 forward(from, to, target)를 낸다.
                  redirect로 넘어간 핸들러는 따라가지 않는다(브라우저가 새로 보내는 별개의 요청이다).
                """ + COMMON_HELP;
    }

    @Override
    String queryLabel() {
        return "대상(테이블, 테이블.컬럼, 노드 ID)";
    }

    @Override
    List<String> flags() {
        return List.of("--include-data-load");
    }

    @Override
    List<String> valueOptions() {
        return List.of("--depth", "--format");
    }

    @Override
    Query prepare(CommandLine line, String query) {
        int depth = line.number("--depth", ImpactAnalysis.DEFAULT_MAX_DEPTH, 1);
        boolean json = line.choice("--format", FORMATS, Boolean.FALSE);
        boolean includeDataLoad = line.has("--include-data-load");
        return (graph, err) -> {
            ImpactReport report = ImpactAnalysis.analyze(graph, query, depth, includeDataLoad);
            if (!report.found()) {
                StringBuilder reason = new StringBuilder("'" + query + "'에 맞는 테이블, 뷰, 컬럼을 찾지 못했다.");
                if (graph.hasNode(query.strip())) {
                    reason.append(" 영향도는 테이블·뷰·컬럼만 대상이다. 다른 노드는 'codeatlas calltree <노드 ID> --callers'를 쓴다.");
                } else {
                    reason.append(" 'codeatlas find <검색어> --kind DB_TABLE,DB_VIEW,DB_COLUMN'으로 이름을 찾는다.");
                    // 비슷한 이름 후보는 조회 API가 리포트에 담아 준다.
                    List<Node> similar = new ArrayList<>();
                    report.suggestions().forEach(s -> graph.node(s.id()).ifPresent(similar::add));
                    if (!similar.isEmpty()) {
                        reason.append("\n비슷한 이름:\n").append(NodeListing.table(graph, similar, MAX_SUGGESTIONS).stripTrailing());
                        if (similar.size() > MAX_SUGGESTIONS) {
                            reason.append("\n... 외 ").append(similar.size() - MAX_SUGGESTIONS).append("개");
                        }
                    }
                }
                throw new CommandFailure(reason.toString());
            }
            return json ? report.toJson() : report.toMarkdown();
        };
    }
}
