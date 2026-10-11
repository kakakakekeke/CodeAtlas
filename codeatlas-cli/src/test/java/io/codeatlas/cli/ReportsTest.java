package io.codeatlas.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.SourceLocation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** analyze·summary가 찍는 요약({@link Reports#summary}). */
class ReportsTest {

    /** 요약에서 제목이 {@code title}로 시작하는 절(다음 빈 줄 앞까지). */
    private static String section(String summary, String title) {
        int start = summary.indexOf("\n" + title);
        assertThat(start).as(title).isNotNegative();
        int end = summary.indexOf("\n\n", start + 1);
        return end < 0 ? summary.substring(start + 1) : summary.substring(start + 1, end);
    }

    private static GraphModel loginGraph() {
        GraphModel graph = new GraphModel();
        graph.project(new ProjectInfo("p", "/work/p", Instant.parse("2026-10-11T00:00:00Z")));
        graph.declare(Node.of(UPDATE, NodeKind.JAVA_METHOD, "updateLoginIncorrect"));
        graph.declare(Node.of(FIND, NodeKind.JAVA_METHOD, "find"));
        graph.declare(Node.of(STATEMENT, NodeKind.SQL_STATEMENT, "updateLoginIncorrectCnt"));
        graph.addUnresolvedEdge(UPDATE, EdgeKind.EXECUTES, "mybatis-statement", "LoginUsr.updateLoginIncorrect{*}", SITE);
        graph.addUnresolvedEdge(FIND, EdgeKind.EXECUTES, "mybatis-statement", "LoginUsr.find{*}", SourceLocation.of("src/main/java/a/LoginDAO.java", 120));
        graph.addUnresolvedEdge(FIND, EdgeKind.CALLS, "java-call", "lib.call(…)", SourceLocation.of("src/main/java/a/LoginDAO.java", 121));
        return graph;
    }

    private static final String UPDATE = Ids.javaMethod("a.LoginDAO", "updateLoginIncorrect", "java.util.Map");
    private static final String FIND = Ids.javaMethod("a.LoginDAO", "find");
    private static final String STATEMENT = Ids.mybatisStatement("LoginUsr", "updateLoginIncorrectCnt");
    private static final SourceLocation SITE = SourceLocation.of("src/main/java/a/LoginDAO.java", 106);
    private static final String RESOLVED = Ids.unresolved("mybatis-statement", "LoginUsr.updateLoginIncorrect{*}");

    /** 링커가 접두 일치로 풀었다: 풀린 엣지(INFERRED, resolvedFrom)가 더해지고 미해결 노드에 resolvedBy가 붙는다(F-012-R42). */
    private static void resolveByPrefix(GraphModel graph) {
        graph.addEdge(Edge.builder(UPDATE, STATEMENT, EdgeKind.EXECUTES).inferred().location(SITE)
                .attr("prefixMatch", true).attr("resolvedFrom", RESOLVED).build());
        graph.putAttrs(RESOLVED, Map.of("resolvedBy", "prefixMatch"));
    }

    /** 절의 행에서 읽은 수의 합. */
    private static int sumOfRows(String section) {
        return section.lines().skip(1).filter(line -> line.matches("\\s+[A-Z_]+\\s+\\d+")).mapToInt(line -> Integer.parseInt(line.strip().split("\\s+")[1])).sum();
    }

    @Test
    @DisplayName("F-001-AC80 요약의 노드·엣지 종류별 수와 신뢰도별 수는 그래프 그대로 세고(종류별 합 = 총계), 접두 일치로 풀린 참조(resolvedBy)는 신뢰도 표 아래 한 줄로 알린다")
    void summaryCountsTheGraphAsIsAndNotesResolvedReferences() {
        GraphModel graph = loginGraph();

        String before = Reports.summary(graph, Path.of("graph.json"), null);
        assertThat(section(before, "노드 (총 6)")).containsPattern("UNRESOLVED\\s+3\\b");
        assertThat(section(before, "신뢰도별 엣지")).containsPattern("UNRESOLVED\\s+3\\b");
        assertThat(before).doesNotContain("resolvedBy");

        resolveByPrefix(graph);
        String after = Reports.summary(graph, Path.of("graph.json"), null);
        // 그래프의 수 그대로다: 풀린 참조의 미해결 노드와 엣지도 그래프에 남아 있다
        String nodes = section(after, "노드 (총 6)");
        assertThat(nodes).containsPattern("UNRESOLVED\\s+3\\b");
        assertThat(sumOfRows(nodes)).isEqualTo(6);
        assertThat(sumOfRows(section(after, "엣지 (총 4)"))).isEqualTo(4);
        String confidence = section(after, "신뢰도별 엣지");
        assertThat(confidence).containsPattern("UNRESOLVED\\s+3\\b").containsPattern("INFERRED\\s+1\\b");
        assertThat(sumOfRows(confidence)).isEqualTo(4);
        assertThat(confidence.lines().filter(line -> line.contains("resolvedBy"))).singleElement().asString()
                .isEqualTo("  (UNRESOLVED 가운데 참조 1개와 그리로 가는 엣지 1건은 같은 지점에서 접두 일치로 풀렸다: resolvedBy. "
                        + "report.md의 \"미해결 참조\"와 힌트는 이를 빼고 센다)");
    }

    @Test
    @DisplayName("F-001-AC81 diagnostics.md: 접두 일치로 풀린 참조의 UNRESOLVED_REFERENCE 진단은 지우지 않고 메시지 끝에 (접두 일치로 풀림)을 덧붙이며, 머리에 report.md와 수가 다른 까닭을 한 줄 적는다")
    void diagnosticsMarkdownMarksResolvedReferences() {
        GraphModel graph = loginGraph();
        String before = Reports.diagnosticsMarkdown(graph);
        assertThat(before).contains("- 진단 수: 3 (ERROR 0, WARN 3, INFO 0)").doesNotContain("풀림").doesNotContain("resolvedBy")
                .contains("| WARN | UNRESOLVED_REFERENCE | src/main/java/a/LoginDAO.java:106 | 해석하지 못한 참조(mybatis-statement): LoginUsr.updateLoginIncorrect{*} |");

        resolveByPrefix(graph);
        String after = Reports.diagnosticsMarkdown(graph);
        // 진단은 분석기가 남긴 기록이다: 건수는 그대로다
        assertThat(after).contains("- 진단 수: 3 (ERROR 0, WARN 3, INFO 0)").contains("| WARN | UNRESOLVED_REFERENCE | 3 |");
        assertThat(after).contains("- `UNRESOLVED_REFERENCE` 3건 가운데 1건은 링커가 같은 지점에서 접두 일치로 풀었다(목록의 메시지 끝에 `(접두 일치로 풀림)`). "
                + "`report.md`의 \"미해결 참조\"는 엣지를 세고 풀린 것을 빼므로 이 수와 다르다.");
        assertThat(after).contains("| WARN | UNRESOLVED_REFERENCE | src/main/java/a/LoginDAO.java:106 | 해석하지 못한 참조(mybatis-statement): LoginUsr.updateLoginIncorrect{*} (접두 일치로 풀림) |");
        assertThat(after.lines().filter(line -> line.endsWith("(접두 일치로 풀림) |"))).hasSize(1);
    }
}
