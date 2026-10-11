package io.codeatlas.export.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.ShopGraph;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryReportTest {

    @Test
    @DisplayName("F-015-AC16 report.md에 노드·엣지 수, 신뢰도, 미해결 참조, 진단, 테이블·매퍼 점검 목록이 있다")
    void rendersSummary() {
        ShopGraph shop = new ShopGraph();
        // 마무리 단계가 만드는 미해결 노드(가리키던 ID 그대로, expectedPrefix와 거기서 유도한 category)
        shop.f.calls(shop.implFind, "java:method:com.gone.X#y()");
        GraphModel g = shop.f.graph();
        g.resolveDanglingEdges();

        String md = SummaryReport.render(g);

        assertThat(md).startsWith("# CodeAtlas 분석 요약\n");
        assertThat(md).contains("- 노드 " + g.nodeCount() + "개, 엣지 " + g.edgeCount() + "개, 진단 " + g.diagnostics().size() + "개");
        // 종류별 수
        assertThat(md).contains("| JAVA_METHOD | " + g.nodes(NodeKind.JAVA_METHOD).size() + " |");
        assertThat(md).contains("| DB_TABLE | " + g.nodes(NodeKind.DB_TABLE).size() + " |");
        assertThat(md).contains("| CALLS | " + g.edges(EdgeKind.CALLS).size() + " |");
        assertThat(md).doesNotContain("| NEXT | 0 |");
        // 신뢰도별 수
        long inferred = g.edges().stream().filter(e -> e.confidence() == Confidence.INFERRED).count();
        assertThat(md).contains("| INFERRED | " + inferred + " |").contains("| UNRESOLVED | 2 |");
        // 미해결 참조: 엣지 기준으로 한 번씩, 분류별
        assertThat(md).contains("## 미해결 참조 (2)");
        assertThat(md).contains("### method (1)").contains("### java-call (1)");
        assertThat(md).contains("| `OrderServiceImpl#create(OrderDto)` | CALLS | `helper.unknown()` | `src/main/java/com/shop/order/OrderServiceImpl.java:77` |");
        assertThat(md).containsPattern("\\| `OrderServiceImpl#find\\(Long\\)` \\| CALLS \\| `java:method:com\\.gone\\.X#y\\(\\)` \\|");
        // 진단
        assertThat(md).contains("| PARSE_FAILED | 1 | 1 | 0 | 2 |").contains("| DYNAMIC_SQL | 0 | 0 | 1 | 1 |")
                .contains("| UNRESOLVED_REFERENCE | 0 | 2 | 0 | 2 |");
        assertThat(md).contains("| ERROR | - | 파일을 읽지 못함 |").contains("| WARN | `broken/BrokenMapper.xml:3` | 구문을 건너뜀 |");
        // UNRESOLVED_REFERENCE 진단은 항목을 다시 나열하지 않는다
        assertThat(md).doesNotContain("해석하지 못한 참조(method)");
        // 점검 목록
        assertThat(md).contains("## DDL에 없는 테이블 (2)").contains("| `DAILY_SALES` | 1 |").contains("| `TMP_UNDECLARED` | 1 |");
        assertThat(md).contains("## 어떤 구문에서도 참조되지 않는 테이블 (1)\n\n- `NEVER_USED`");
        assertThat(md).contains("## 대응 구문이 없는 매퍼 메서드 (1)\n\n- `com.shop.order.OrderMapper#noStatement()`");
        assertThat(md).contains("## 호출하는 곳이 없는 매퍼 구문 (1)\n\n- `com.shop.order.OrderMapper.unusedStatement`");
    }

    @Test
    @DisplayName("F-015-AC30 매퍼 메서드가 실행하지만 그 메서드를 부르는 Java 코드가 없는 구문을 'Java 호출자가 없는 매퍼 구문'으로 따로 낸다")
    void statementsWithoutJavaCallers() {
        // ShopGraph: OrderMapper#updateStatus는 updateStatus 구문을 EXECUTES하지만 어디서도 부르지 않는다
        String shop = SummaryReport.render(new ShopGraph().f.graph());
        assertThat(shop).containsPattern("## Java 호출자가 없는 매퍼 구문 \\(1\\)\n\n[^\n]+\n\n"
                + "- `com\\.shop\\.order\\.OrderMapper\\.updateStatus` \\(`src/main/resources/mapper/OrderMapper\\.xml:\\d+`\\)"
                + " ← `com\\.shop\\.order\\.OrderMapper#updateStatus\\(Long, String\\)`\n");
        // EXECUTES가 아예 없는 구문은 기존 절에 그대로 남고, 배치 스텝이 실행하는 구문(deleteOld)은 어느 쪽에도 없다
        assertThat(shop).contains("## 호출하는 곳이 없는 매퍼 구문 (1)\n\n- `com.shop.order.OrderMapper.unusedStatement`");
        assertThat(shop.substring(shop.indexOf("## Java 호출자가 없는 매퍼 구문"))).doesNotContain("deleteOld").doesNotContain("selectOrder")
                .doesNotContain("insertLog");

        GraphFixture f = GraphFixture.create();
        f.attr(f.type("com.q.Mapper", "interface", "public"), "stereotype", "mapper");
        f.type("com.q.Dao", "interface", "public");
        f.type("com.q.DaoImpl", "class", "public");
        f.type("com.q.SubMapper", "interface", "public");
        f.type("com.q.Service", "class", "public");
        f.type("com.q.Controller", "class", "public");
        String run = f.method("com.q.Service", "run");
        // (1) 인터페이스로 부른다: Service#run → Dao#save ⇐ DaoImpl#save -EXECUTES→ save
        String daoSave = f.method("com.q.Dao", "save", List.of(), "int");
        String implSave = f.method("com.q.DaoImpl", "save");
        f.edge(implSave, daoSave, EdgeKind.OVERRIDES);
        f.calls(run, daoSave);
        f.edge(implSave, f.statement("com.q.Mapper", "save", "INSERT"), EdgeKind.EXECUTES);
        // (2) 부르는 곳이 없다
        f.edge(f.method("com.q.Mapper", "dead", List.of(), "int"), f.statement("com.q.Mapper", "dead", "SELECT"), EdgeKind.EXECUTES);
        // (3) 배치 스텝이 그 메서드를 구성 요소로 쓴다
        String byStep = f.method("com.q.Mapper", "byStep", List.of(), "int");
        f.edge(byStep, f.statement("com.q.Mapper", "byStep", "SELECT"), EdgeKind.EXECUTES);
        f.job("j");
        f.edge(f.step("j", "s"), byStep, EdgeKind.USES, Map.of("role", "reader"));
        // (4) 엔드포인트의 핸들러가 직접 실행한다
        String direct = f.method("com.q.Controller", "direct");
        f.edge(direct, f.statement("com.q.Mapper", "direct", "SELECT"), EdgeKind.EXECUTES);
        f.endpoint("GET", "/direct", direct);
        // (5) 자기 자신만 부른다(재귀)
        String selfOnly = f.method("com.q.Mapper", "selfOnly", List.of(), "int");
        f.calls(selfOnly, selfOnly);
        f.edge(selfOnly, f.statement("com.q.Mapper", "selfOnly", "SELECT"), EdgeKind.EXECUTES);
        // (6) 부르는 곳 없는 메서드와 배치 스텝이 함께 실행한다
        String both = f.statement("com.q.Mapper", "both", "DELETE");
        f.edge(f.method("com.q.Mapper", "both", List.of(), "int"), both, EdgeKind.EXECUTES);
        f.edge(Ids.batchStep("j", "s"), both, EdgeKind.EXECUTES);
        // (7) 매퍼 메서드를 재정의한 쪽을 부른다: Service#run → SubMapper#find ─OVERRIDES→ Mapper#find -EXECUTES→ find
        String find = f.method("com.q.Mapper", "find", List.of(), "int");
        String subFind = f.method("com.q.SubMapper", "find", List.of(), "int");
        f.edge(subFind, find, EdgeKind.OVERRIDES);
        f.calls(run, subFind);
        f.edge(find, f.statement("com.q.Mapper", "find", "SELECT"), EdgeKind.EXECUTES);
        // (8) 매퍼 XML이 아닌 구문은 대상이 아니다
        f.edge(f.method("com.q.Service", "unusedRoutineCall"), f.routineStatement(f.routine("SP_X", "PROCEDURE"), 3, "SELECT"), EdgeKind.EXECUTES);

        String md = SummaryReport.render(f.graph());

        assertThat(md).containsPattern("## Java 호출자가 없는 매퍼 구문 \\(2\\)\n\n[^\n]+\n\n"
                + "- `com\\.q\\.Mapper\\.dead` \\([^)]+\\) ← `com\\.q\\.Mapper#dead\\(\\)`\n"
                + "- `com\\.q\\.Mapper\\.selfOnly` \\([^)]+\\) ← `com\\.q\\.Mapper#selfOnly\\(\\)`\n");
        assertThat(md.substring(md.indexOf("## Java 호출자가 없는 매퍼 구문"))).doesNotContain("Mapper.save").doesNotContain("byStep")
                .doesNotContain("direct").doesNotContain("both").doesNotContain("find").doesNotContain("SP_X");
        assertThat(md).contains("## 호출하는 곳이 없는 매퍼 구문 (0)\n\n없음\n");
        assertThat(SummaryReport.render(new GraphModel())).contains("## Java 호출자가 없는 매퍼 구문 (0)\n\n없음\n");
    }

    @Test
    @DisplayName("F-015-AC31 report.md에 대상 테이블을 정적으로 알 수 없는 구문을 구문 ID, 접근 종류, 위치와 함께 적는다")
    void listsDynamicStatements() {
        GraphFixture f = GraphFixture.create();
        f.table("TB_ORDER");
        f.dynamicAccess(f.statement("com.d.M", "selectBackup", "SELECT"), EdgeKind.READS, null, "__DYNAMIC__");
        f.dynamicAccess(f.routineStatement(f.routine("SP_PURGE", "PROCEDURE"), 42, "DELETE"), EdgeKind.WRITES, "DELETE", "DELETE FROM {*}");

        String md = SummaryReport.render(f.graph());

        assertThat(md).contains("\n## 대상 테이블을 정적으로 알 수 없는 구문 (2)\n").contains("| 구문 | CRUD | 위치 |\n|---|---|---|\n")
                .containsPattern("\\| `mybatis:stmt:com\\.d\\.M\\.selectBackup` \\| R \\| `src/main/resources/mapper/M\\.xml:\\d+` \\|\n")
                .contains("| `sql:stmt:SP_PURGE:42` | D | `db/sp_purge.prc:42` |\n");
        assertThat(SummaryReport.render(new ShopGraph().f.graph())).contains("## 대상 테이블을 정적으로 알 수 없는 구문 (0)\n\n없음\n");
    }

    @Test
    @DisplayName("F-015-AC42 'DDL에 없는 테이블'은 테이블마다 참조하는 구문 수를 적고, 참조 구문이 하나뿐인 테이블을 의심으로 표시한다")
    void flagsSuspiciousUndeclaredTables() {
        GraphFixture f = GraphFixture.create();
        f.table("REAL_T");
        String a = f.statement("com.u.M", "a", "SELECT");
        String b = f.statement("com.u.M", "b", "SELECT");
        String c = f.statement("com.u.M", "c", "UPDATE");
        f.reads(a, "LEGACY_T").reads(b, "LEGACY_T", "X").writes(c, "LEGACY_T", "UPDATE");
        f.reads(a, "GHOST");                    // 한 구문에서만 나온 이름
        f.undeclaredTable("JOIN_ONLY");         // 구문이 참조하지 않는 이름(관계의 끝점으로만 나옴)

        String md = SummaryReport.render(f.graph());

        assertThat(md).contains("## DDL에 없는 테이블 (3)\n").contains("| 테이블 | 참조하는 구문 수 | 구문 (최대 5개) | 비고 |\n|---|---|---|---|\n");
        assertThat(md).contains("| `GHOST` | 1 | `M.a` | 의심(구문 파싱 복구에서 나왔을 수 있음) |\n");
        assertThat(md).contains("| `LEGACY_T` | 3 | `M.a`, `M.b`, `M.c` |  |\n");
        assertThat(md).contains("| `JOIN_ONLY` | 0 |  |  |\n");
        assertThat(md).contains("참조하는 구문이 하나뿐인 테이블 1개는 의심으로 표시했다");
    }

    @Test
    @DisplayName("F-001-AC53 category가 없는 마무리 단계 노드(전에 만든 그래프)도 expectedPrefix에서 유도한 분류로 묶는다")
    void categorizesDanglingNodesOfOlderGraphs() {
        GraphFixture f = GraphFixture.create();
        String caller = f.method("com.o.A", "run");
        f.graph().declare(io.codeatlas.core.model.Node.builder("mybatis:stmt:ns.gone", NodeKind.UNRESOLVED).name("ns.gone")
                .attr("expectedPrefix", "mybatis:stmt").build());
        f.graph().addEdge(io.codeatlas.core.model.Edge.builder(caller, "mybatis:stmt:ns.gone", EdgeKind.EXECUTES).unresolved().build());

        String md = SummaryReport.render(f.graph());

        assertThat(md).contains("| mybatis-statement | 1 |").contains("### mybatis-statement (1)").doesNotContain("### mybatis:stmt");
    }

    @Test
    @DisplayName("F-015-AC20 진단 표: 위치 없는 진단의 위치 칸은 -, 메시지와 위치의 |와 줄바꿈은 표를 깨지 않게 이스케이프한다")
    void diagnosticTableCells() {
        GraphModel g = new GraphModel();
        g.report(Diagnostic.error("PARSE_FAILED", "위치 없는 진단", null));
        g.report(Diagnostic.warn("PARSE_FAILED", "a | b\n둘째 줄\r\n셋째 `줄`", SourceLocation.of("db/a|b.sql", 3)));
        g.report(Diagnostic.info("NOTE|X", "메모", SourceLocation.ofFile("src/A.java")));

        String md = SummaryReport.render(g);

        assertThat(md).contains("| ERROR | - | 위치 없는 진단 |\n");
        assertThat(md).contains("| WARN | `db/a\\|b.sql:3` | a \\| b 둘째 줄 셋째 `줄` |\n");
        assertThat(md).contains("| NOTE\\|X | 0 | 0 | 1 | 1 |\n");
        assertThat(md).contains("| INFO | `src/A.java` | 메모 |\n");
        // 표의 모든 행은 칸 수가 머리글과 같다(이스케이프하지 않은 |가 없다)
        String section = md.substring(md.indexOf("### PARSE_FAILED"));
        section = section.substring(0, section.indexOf("\n## "));
        for (String row : section.lines().filter(line -> line.startsWith("|")).toList()) {
            assertThat(row.replace("\\|", "").chars().filter(c -> c == '|').count()).as(row).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("F-014-AC14 F-015-AC19 DB 객체·Java 타입·엔드포인트·배치 잡이 없으면 리포트 머리에 한 줄씩 알린다")
    void notesMissingSubjects() {
        String empty = SummaryReport.render(new GraphModel());
        assertThat(empty).contains("- DB 객체 없음(DDL이나 SQL이 분석되지 않음)\n").contains("- Java 타입 없음(Java 소스가 분석되지 않음)\n")
                .contains("- 엔드포인트 없음\n").contains("- 배치 잡 없음\n");

        String full = SummaryReport.render(new ShopGraph().f.graph());
        assertThat(full).doesNotContain("DB 객체 없음").doesNotContain("Java 타입 없음").doesNotContain("엔드포인트 없음")
                .doesNotContain("배치 잡 없음");
    }

    @Test
    @DisplayName("F-015-AC16 빈 그래프에서도 리포트가 나온다")
    void emptyGraph() {
        String md = SummaryReport.render(new GraphModel());

        assertThat(md).contains("- 노드 0개, 엣지 0개, 진단 0개").contains("## 미해결 참조 (0)\n\n없음\n");
    }
}
