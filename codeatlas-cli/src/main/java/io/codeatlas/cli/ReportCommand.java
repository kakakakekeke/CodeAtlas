package io.codeatlas.cli;

import io.codeatlas.export.report.SummaryReport;
import java.util.List;

/** {@code report} 명령(설계 4.5): 저장된 그래프의 분석 요약 리포트(F-015의 report.md와 같은 내용)를 낸다. */
final class ReportCommand extends QueryCommand {

    @Override
    public String name() {
        return "report";
    }

    @Override
    public String synopsis() {
        return "codeatlas report [<graph.json 또는 출력 폴더>] [--max-items <n>] [--graph <경로>] [--out <파일>]";
    }

    @Override
    public String help() {
        return """
                report: 분석 요약(Markdown)을 낸다. 종류별 노드·엣지 수, 신뢰도별 엣지 수, 미해결 참조, 진단 집계와 상위 항목,
                  점검 목록(DDL에 없는 테이블, 참조되지 않는 테이블, 대응 구문이 없는 매퍼 메서드 등)이 들어 있다.
                  --max-items <n>      진단 코드마다 보여 줄 항목 수. 기본값 20, 0이면 전부.
                """ + COMMON_HELP;
    }

    @Override
    List<String> flags() {
        return List.of();
    }

    @Override
    List<String> valueOptions() {
        return List.of("--max-items");
    }

    @Override
    Query prepare(CommandLine line, String query) {
        int maxItems = line.number("--max-items", SummaryReport.DEFAULT_MAX_ITEMS, 0);
        return (graph, err) -> SummaryReport.render(graph, maxItems);
    }
}
