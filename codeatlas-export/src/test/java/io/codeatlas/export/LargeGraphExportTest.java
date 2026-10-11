package io.codeatlas.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.CallTreeOptions;
import io.codeatlas.export.calltree.CallTrees;
import io.codeatlas.export.calltree.ImpactAnalysis;
import io.codeatlas.export.calltree.ImpactReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 엣지가 많은 그래프에서 출력기와 조회 API가 엣지 수에 비례하는 시간 안에 끝나는지 본다. */
class LargeGraphExportTest {

    private static final int TABLES = 200;
    private static final int COLUMNS_PER_TABLE = 20;
    private static final int STATEMENTS = 50_000;
    private static final int COLUMNS_PER_STATEMENT = 10;

    @TempDir
    Path dir;

    /**
     * 테이블 200개, 컬럼 4,000개, 구문 5만 개, 컬럼 엣지 50만 개. 구문의 60%가 한 테이블(데이터 적재 스크립트의 대상처럼)에 몰려
     * 그 테이블의 컬럼마다 들어오는 엣지가 만 개를 넘는다.
     */
    private static GraphModel largeGraph() {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (int t = 0; t < TABLES; t++) {
            String table = tableName(t);
            nodes.add(Node.builder(Ids.table(table), NodeKind.DB_TABLE).name(table).attr("declared", true)
                    .location(SourceLocation.of("db/schema.sql", t + 1)).build());
            for (int c = 0; c < COLUMNS_PER_TABLE; c++) {
                String column = Ids.column(table, "C" + c);
                nodes.add(Node.builder(column, NodeKind.DB_COLUMN).name("C" + c).attr("declared", true).attr("dataType", "NUMBER")
                        .attr("ordinal", c + 1).attr("primaryKey", c == 0 ? Boolean.TRUE : null).build());
                edges.add(Edge.of(Ids.table(table), column, EdgeKind.CONTAINS));
            }
        }
        for (int s = 0; s < STATEMENTS; s++) {
            int t = s % 10 < 6 ? 0 : 1 + s % (TABLES - 1);
            String table = tableName(t);
            boolean write = s % 2 == 0;
            String statement = Ids.sqlStatement("db/data-" + (s % 50) + ".sql", s + 1);
            SourceLocation at = SourceLocation.of("db/data-" + (s % 50) + ".sql", s + 1);
            nodes.add(Node.builder(statement, NodeKind.SQL_STATEMENT).name("stmt" + s).attr("declared", true)
                    .attr("statementType", write ? "INSERT" : "SELECT").location(at).build());
            EdgeKind kind = write ? EdgeKind.WRITES : EdgeKind.READS;
            edges.add(Edge.builder(statement, Ids.table(table), kind).attr("op", write ? "INSERT" : null).location(at).build());
            for (int c = 0; c < COLUMNS_PER_STATEMENT; c++) {
                edges.add(Edge.builder(statement, Ids.column(table, "C" + (s + c) % COLUMNS_PER_TABLE), kind)
                        .attr("op", write ? "INSERT" : null).location(at).build());
            }
        }
        // 엔드포인트 → 핸들러 → 구문 몇 개(콜트리와 엔드포인트 매트릭스가 큰 테이블을 지나게 한다)
        for (int e = 0; e < 20; e++) {
            String method = Ids.javaMethod("com.big.Api", "handle" + e);
            String endpoint = Ids.httpEndpoint("GET", "/big/" + e);
            nodes.add(Node.builder(method, NodeKind.JAVA_METHOD).name("handle" + e).attr("declared", true)
                    .location(SourceLocation.of("src/main/java/com/big/Api.java", 10 + e)).build());
            nodes.add(Node.builder(endpoint, NodeKind.HTTP_ENDPOINT).name("GET /big/" + e).attr("declared", true).build());
            edges.add(Edge.of(endpoint, method, EdgeKind.ROUTES_TO));
            edges.add(Edge.of(method, Ids.sqlStatement("db/data-" + (e % 50) + ".sql", e + 1), EdgeKind.EXECUTES));
        }
        return GraphModel.restore(null, nodes, edges, List.of());
    }

    private static String tableName(int index) {
        return String.format("T%03d", index);
    }

    @Test
    @DisplayName("F-015-AC33 테이블 200·컬럼 4천·구문 5만·컬럼 엣지 50만인 그래프에서 표준 출력기 전체와 조회 API가 제한 시간 안에 끝난다")
    void standardExportFinishesOnLargeGraph() {
        GraphModel graph = largeGraph();
        assertThat(graph.edgeCount()).isGreaterThan(550_000);
        Path out = dir.resolve("large");

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> new StandardExporter().export(graph, out, Map.of()));

        assertThat(out.resolve("erd/tables.md")).exists();
        assertThat(out.resolve("crud/table-statement.csv")).exists();
        assertThat(out.resolve("crud/table-endpoint.csv")).exists();
        assertThat(out.resolve("calltree/endpoints.md")).exists();
        assertThat(out.resolve("report.md")).exists();
        assertThat(read(out.resolve("erd/tables.md"))).contains("## T000\n").contains("- 참조하는 구문: 30000개 (읽기 15000, 쓰기 15000)");
        assertThat(read(out.resolve("report.md"))).contains("## 어떤 구문에서도 참조되지 않는 테이블 (0)");

        // 조회 API: 큰 테이블에서 거슬러 올라가는 영향도와 콜트리
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            ImpactReport report = ImpactAnalysis.analyze(graph, "T000");
            assertThat(report.statements()).hasSize(30_000);
            assertThat(report.endpoints()).isNotEmpty();
            assertThat(CallTrees.build(graph, Ids.table("T000"), CallTreeOptions.callers()).nodeCount()).isGreaterThan(1000);
        });
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
