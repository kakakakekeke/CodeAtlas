package io.codeatlas.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.CallTreeOptions;
import io.codeatlas.export.calltree.CallTrees;
import io.codeatlas.export.calltree.CrudMatrix;
import io.codeatlas.export.calltree.CrudMatrix.Row;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import io.codeatlas.export.erd.ErdOptions;
import io.codeatlas.export.erd.TableSpecRenderer;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.ShopGraph;
import io.codeatlas.export.report.SummaryReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 데이터 적재 구문(스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 구문, {@code attrs.dataLoad=true})을 다루는 규칙(F-015-R28). */
class DataLoadTest {

    @TempDir
    Path dir;

    private static final String CODE_LOAD = Ids.sqlStatement("db/data/code.sql", 1);
    private static final String USER_LOAD = Ids.sqlStatement("db/data/code.sql", 200);
    private static final String USER_LOAD2 = Ids.sqlStatement("db/data/user.sql", 1);
    private static final String ONLY_LOAD = Ids.sqlStatement("db/data/user.sql", 50);

    /**
     * T_CODE: 매퍼 구문이 읽고 스크립트가 적재한다. T_USER, T_ONLY_LOADED: 스크립트만 적재한다.
     * 적재 구문 넷 가운데 하나는 rowCount가 없다(방어 처리).
     */
    private static GraphFixture graph() {
        GraphFixture f = GraphFixture.create();
        f.table("T_CODE");
        f.column("T_CODE", "CODE", "VARCHAR2(10)", 1, "pk");
        f.table("T_USER");
        f.table("T_ONLY_LOADED");
        String select = f.statement("com.l.CodeMapper", "selectCodes", "SELECT");
        f.reads(select, "T_CODE", "CODE");
        dataLoad(f, CODE_LOAD, "T_CODE", 120);
        f.graph().addEdge(Edge.builder(CODE_LOAD, Ids.column("T_CODE", "CODE"), EdgeKind.WRITES).attr("op", "INSERT").build());
        dataLoad(f, USER_LOAD, "T_USER", 30);
        dataLoad(f, USER_LOAD2, "T_USER", null);
        dataLoad(f, ONLY_LOAD, "T_ONLY_LOADED", 5);
        return f;
    }

    private static void dataLoad(GraphFixture f, String id, String table, Integer rows) {
        String rest = Ids.remainder(id);
        String file = rest.substring(0, rest.lastIndexOf(':'));
        int line = Integer.parseInt(rest.substring(rest.lastIndexOf(':') + 1));
        f.graph().declare(Node.builder(id, NodeKind.SQL_STATEMENT).name(rest).location(SourceLocation.of(file, line))
                .attr("statementType", "INSERT").attr("dataLoad", true).attr("rowCount", rows).build());
        f.writes(id, table, "INSERT");
    }

    @Test
    @DisplayName("F-015-AC34 F-013-AC19 데이터 적재 구문은 구문 기준 CRUD 매트릭스·테이블 명세의 구문 수·콜트리에서 기본으로 빠지고 옵션으로 들어온다")
    void dataLoadStatementsAreExcludedByDefault() {
        GraphModel g = graph().graph();

        CrudMatrix byStatement = CrudMatrix.of(g, Subject.STATEMENT);
        assertThat(byStatement.rows()).extracting(Row::id).containsExactly(Ids.mybatisStatement("com.l.CodeMapper", "selectCodes"));
        assertThat(byStatement.tables()).containsExactly("T_CODE");
        CrudMatrix withLoads = CrudMatrix.of(g, Subject.STATEMENT, CrudMatrix.DEFAULT_MAX_DEPTH, true);
        assertThat(withLoads.rows()).hasSize(5);
        assertThat(withLoads.cell(CODE_LOAD, "T_CODE")).isEqualTo("C");

        String spec = TableSpecRenderer.render(g);
        assertThat(spec).contains("## T_CODE\n\n- 정의: ").contains("- 참조하는 구문: 1개 (읽기 1, 쓰기 0)\n- 데이터 적재: 구문 1개, 120행\n");
        assertThat(spec).contains("- 참조하는 구문: 0개 (읽기 0, 쓰기 0)\n- 데이터 적재: 구문 2개, 30행 이상\n");
        String specWithLoads = TableSpecRenderer.render(g, ErdOptions.builder().includeViews(true).build(), true);
        assertThat(specWithLoads).contains("- 참조하는 구문: 2개 (읽기 1, 쓰기 1)\n").doesNotContain("데이터 적재");

        String callers = CallTrees.toText(CallTrees.build(g, Ids.table("T_CODE"), CallTreeOptions.callers()), false);
        assertThat(callers).isEqualTo("""
                [table] T_CODE
                └─ [sql] CodeMapper.selectCodes <SELECT> (R)
                """);
        assertThat(CallTrees.toText(CallTrees.build(g, Ids.table("T_CODE"),
                CallTreeOptions.builder().direction(CallTreeOptions.Direction.CALLERS).includeDataLoad(true).build()), false))
                .contains("[sql] db/data/code.sql:1 <INSERT> (C)");
    }

    @Test
    @DisplayName("F-015-AC34 report.md는 데이터 적재 구문을 테이블 점검에서 빼고 '데이터 적재 스크립트: 파일 n개, 테이블 m개, 행 k건' 절로 요약한다")
    void reportSummarizesDataLoads() {
        GraphModel g = graph().graph();

        String md = SummaryReport.render(g);

        assertThat(md).contains("\n## 데이터 적재 스크립트\n\n파일 2개, 테이블 3개, 행 155건 이상(행 수를 모르는 구문 1개). ");
        assertThat(md).contains("| 파일 | 테이블 수 | 행 수 |\n|---|---|---|\n| `db/data/code.sql` | 2 | 150 |\n| `db/data/user.sql` | 2 | 5 이상 |\n");
        // 애플리케이션 구문이 참조하지 않는 테이블: 적재만 되는 테이블은 그 사실을 적는다
        assertThat(md).contains("## 어떤 구문에서도 참조되지 않는 테이블 (2)\n\n")
                .containsPattern("- `T_ONLY_LOADED` \\(`db/tables\\.sql:\\d+`\\) — 데이터 적재 스크립트만 참조\n")
                .containsPattern("- `T_USER` \\(`db/tables\\.sql:\\d+`\\) — 데이터 적재 스크립트만 참조\n");

        String all = SummaryReport.render(g, SummaryReport.DEFAULT_MAX_ITEMS, true);
        assertThat(all).contains("## 어떤 구문에서도 참조되지 않는 테이블 (0)").contains("## 데이터 적재 스크립트\n");

        // dataLoad 속성이 없는 그래프(전에 만든 그래프)에는 절이 없고 나머지는 그대로다
        String shop = SummaryReport.render(new ShopGraph().f.graph());
        assertThat(shop).doesNotContain("데이터 적재").contains("## 어떤 구문에서도 참조되지 않는 테이블 (1)\n\n- `NEVER_USED`");
        assertThat(TableSpecRenderer.render(new ShopGraph().f.graph())).doesNotContain("데이터 적재");
    }

    @Test
    @DisplayName("F-015-AC34 표준 산출물: export.includeDataLoad=true면 데이터 적재 구문이 crud CSV·tables.md·report.md에 들어온다")
    void standardExporterOption() throws Exception {
        GraphModel g = graph().graph();

        Path off = dir.resolve("off");
        new StandardExporter().export(g, off, Map.of());
        assertThat(Files.readString(off.resolve("crud/table-statement.csv"), StandardCharsets.UTF_8)).doesNotContain("db/data/");
        assertThat(Files.readString(off.resolve("erd/tables.md"), StandardCharsets.UTF_8)).contains("- 데이터 적재: 구문 1개, 120행\n");
        assertThat(Files.readString(off.resolve("report.md"), StandardCharsets.UTF_8)).contains("## 어떤 구문에서도 참조되지 않는 테이블 (2)");

        Path on = dir.resolve("on");
        new StandardExporter().export(g, on, Map.of("export.includeDataLoad", "true"));
        assertThat(Files.readString(on.resolve("crud/table-statement.csv"), StandardCharsets.UTF_8)).contains("db/data/code.sql:1,");
        assertThat(Files.readString(on.resolve("erd/tables.md"), StandardCharsets.UTF_8)).contains("- 참조하는 구문: 2개 (읽기 1, 쓰기 1)\n");
        assertThat(Files.readString(on.resolve("report.md"), StandardCharsets.UTF_8)).contains("## 어떤 구문에서도 참조되지 않는 테이블 (0)");
        assertThat(StandardExporter.OPTION_KEYS).contains("export.includeDataLoad");
    }

    @Test
    @DisplayName("F-015-AC56 값 자리에서 사용자 정의 함수를 부르는 데이터 적재 구문(dataLoad=true 구문의 CALLS→루틴)과 적재 파일의 DELETE·TRUNCATE(dataLoad=true)도 구문 기준 CRUD 매트릭스에서 기본으로 빠진다")
    void dataLoadStatementsCallingRoutinesAreExcluded() throws Exception {
        GraphFixture f = graph();
        f.table("T_SEQ");
        String function = f.routine("FN_NEXT_ID", "FUNCTION");
        f.reads(f.routineStatement(function, 3, "SELECT"), "T_SEQ");
        // INSERT INTO T_USER VALUES (FN_NEXT_ID(), ...): 적재 구문이 함수를 부른다
        f.edge(USER_LOAD, function, EdgeKind.CALLS);
        // 적재 파일의 조건 없는 DELETE: 구문 종류와 무관하게 dataLoad=true면 같은 규칙이다
        String clear = Ids.sqlStatement("db/data/user.sql", 90);
        f.graph().declare(Node.builder(clear, NodeKind.SQL_STATEMENT).name("db/data/user.sql:90").location(SourceLocation.of("db/data/user.sql", 90))
                .attr("statementType", "DELETE").attr("dataLoad", true).build());
        f.writes(clear, "T_USER", "DELETE");
        // 적재가 아닌 구문이 같은 함수를 부르면 전처럼 행이 있다
        String select = f.statement("com.l.CodeMapper", "nextId", "SELECT");
        f.edge(select, function, EdgeKind.CALLS);
        GraphModel g = f.graph();

        CrudMatrix byStatement = CrudMatrix.of(g, Subject.STATEMENT);
        assertThat(byStatement.rows()).extracting(Row::id).doesNotContain(USER_LOAD, clear).contains(select);
        assertThat(byStatement.cell(select, "T_SEQ")).isEqualTo("r");
        CrudMatrix withLoads = CrudMatrix.of(g, Subject.STATEMENT, CrudMatrix.DEFAULT_MAX_DEPTH, true);
        assertThat(withLoads.cell(USER_LOAD, "T_USER")).isEqualTo("C");
        assertThat(withLoads.cell(USER_LOAD, "T_SEQ")).isEqualTo("r");
        assertThat(withLoads.cell(clear, "T_USER")).isEqualTo("D");

        Path out = dir.resolve("load-calls");
        new StandardExporter().export(g, out, Map.of());
        assertThat(Files.readString(out.resolve("crud/table-statement.csv"), StandardCharsets.UTF_8))
                .doesNotContain("db/data/code.sql").doesNotContain("db/data/user.sql").contains("CodeMapper.nextId");
    }
}
