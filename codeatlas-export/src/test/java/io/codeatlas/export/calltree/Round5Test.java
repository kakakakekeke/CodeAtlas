package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.CallTreeOptions.Direction;
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.report.SummaryReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 3차 재검증(M14)에서 나온 콜트리·영향도·리포트의 표기와 접기 규칙. */
class Round5Test {

    private static final String FILE = "src/main/java/com/n/OrderController.java";

    private static String callees(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callees()), false);
    }

    private static String text(GraphModel g, String start, CallTreeOptions options) {
        return CallTrees.toText(CallTrees.build(g, start, options), false);
    }

    private static void call(GraphFixture f, String from, String to, String callType, int line, int column, int endColumn) {
        f.edge(Edge.builder(from, to, EdgeKind.CALLS).attr("callType", callType).location(SourceLocation.of(FILE, line, column, line, endColumn)).build());
    }

    @Test
    @DisplayName("F-015-AC68 한 줄의 중첩 호출 f(g(x))는 평가 순서대로 안쪽(g)이 먼저 나온다(범위가 포함된 쪽 먼저). 나란한 호출은 전처럼 열 순서다")
    void nestedCallsInEvaluationOrder() {
        GraphFixture f = GraphFixture.create();
        String caller = f.method("com.n.OrderController", "save");
        String before = f.method("com.n.Util", "before");
        String outer = f.method("com.n.Util", "outer");
        String inner = f.method("com.n.Util", "inner");
        String innermost = f.method("com.n.Util", "innermost");
        String after = f.method("com.n.Util", "after");
        // 10줄: outer(inner(innermost()), x); after();   9줄: before();
        call(f, caller, outer, "static", 10, 5, 40);
        call(f, caller, after, "static", 10, 45, 52);
        call(f, caller, inner, "static", 10, 11, 30);
        call(f, caller, innermost, "static", 10, 17, 29);
        call(f, caller, before, "static", 9, 5, 13);

        assertThat(callees(f.graph(), caller)).isEqualTo("""
                OrderController#save()
                ├─ Util#before()
                ├─ Util#innermost()
                ├─ Util#inner()
                ├─ Util#outer()
                └─ Util#after()
                """);
        assertThat(callees(f.reversedCopy(), caller)).isEqualTo(callees(f.graph(), caller));
    }

    @Test
    @DisplayName("F-015-AC69 후보 엔드포인트 여럿과 맞은 요청(MATCHES의 ambiguous=true)도 [ambiguous n] 한 줄 아래에 후보를 묶는다")
    void ambiguousMatchesAreGrouped() {
        GraphFixture f = GraphFixture.create();
        String page = f.jsp("src/main/webapp/list.jsp");
        String a = f.endpoint("GET", "/a/list", f.method("com.n.AController", "list"));
        String b = f.endpoint("GET", "/b/list", f.method("com.n.BController", "list"));
        String request = f.request("GET", "/list", "src/main/webapp/list.jsp", 7, page, null);
        for (String endpoint : List.of(a, b)) {
            f.edge(Edge.builder(request, endpoint, EdgeKind.MATCHES).inferred().attr("ambiguous", true).attr("relative", true).build());
        }
        // 후보가 하나뿐인 요청은 그대로다(링커는 후보가 둘 이상일 때만 ambiguous를 붙인다)
        String single = f.request("GET", "/a/list", "src/main/webapp/list.jsp", 9, page, null);
        f.edge(Edge.builder(single, a, EdgeKind.MATCHES).inferred().attr("relative", true).build());

        assertThat(callees(f.graph(), page)).isEqualTo("""
                [jsp] src/main/webapp/list.jsp
                ├─ [request] GET /list
                │  └─ ~ [ambiguous 2] GET /list
                │     ├─ ~ [endpoint] GET /a/list
                │     │  └─ AController#list()
                │     └─ ~ [endpoint] GET /b/list
                │        └─ BController#list()
                └─ [request] GET /a/list
                   └─ ~ [endpoint] GET /a/list [see above]
                """);
    }

    @Test
    @DisplayName("F-015-AC70 접근자 접기(hideAccessors)는 부수 효과 없는 생성자(본문에서 나가는 호출이 순수 생성자뿐인 <init>)와 MyBatis Generator의 *Example·*Example$Criteria 빌더 호출도 접는다")
    void pureConstructorsAndExampleBuildersAreFolded() {
        GraphFixture f = GraphFixture.create();
        for (String type : List.of("com.n.OrderController", "com.n.Base", "com.n.Vo", "com.n.Loader", "com.n.UserExample", "com.n.UserExample$Criteria",
                "com.n.OrderService")) {
            f.type(type, "class", "public");
        }
        String handler = f.method("com.n.OrderController", "save");
        String baseInit = f.method("com.n.Base", Ids.CONSTRUCTOR_NAME);
        String voInit = f.method("com.n.Vo", Ids.CONSTRUCTOR_NAME);
        String loaderInit = f.method("com.n.Loader", Ids.CONSTRUCTOR_NAME);
        String load = f.method("com.n.Loader", "load");
        String exampleInit = f.method("com.n.UserExample", Ids.CONSTRUCTOR_NAME);
        String createCriteria = f.method("com.n.UserExample", "createCriteria");
        String criteriaInit = f.method("com.n.UserExample$Criteria", Ids.CONSTRUCTOR_NAME);
        String andId = f.method("com.n.UserExample$Criteria", "andIdEqualTo", List.of("java.lang.Long"), "com.n.UserExample$Criteria", "public");
        String addCriterion = f.method("com.n.UserExample$Criteria", "addCriterion", List.of("java.lang.String"), "void", "protected");
        String run = f.method("com.n.OrderService", "run");
        call(f, voInit, baseInit, "constructor", 3, 9, 16);
        call(f, loaderInit, load, "virtual", 4, 9, 15);
        call(f, createCriteria, criteriaInit, "constructor", 5, 9, 30);
        call(f, andId, addCriterion, "virtual", 6, 9, 30);
        call(f, handler, voInit, "constructor", 10, 9, 17);
        call(f, handler, loaderInit, "constructor", 11, 9, 21);
        call(f, handler, exampleInit, "constructor", 12, 9, 26);
        call(f, handler, createCriteria, "virtual", 13, 9, 33);
        call(f, handler, andId, "virtual", 13, 9, 50);
        call(f, handler, run, "virtual", 14, 9, 22);
        GraphModel g = f.graph();

        assertThat(text(g, handler, CallTreeOptions.builder().hideAccessors(true).build())).isEqualTo("""
                OrderController#save()
                ├─ Loader#<init>()
                │  └─ Loader#load()
                └─ OrderService#run()
                """);
        // 조회 API의 기본값은 접지 않는다
        assertThat(callees(g, handler)).contains("Vo#<init>()").contains("UserExample#createCriteria()").contains("UserExample.Criteria#andIdEqualTo(Long)");
        FlowNavigator navigator = new FlowNavigator(g, true, true);
        assertThat(navigator.isAccessor(voInit)).isTrue();
        assertThat(navigator.isAccessor(baseInit)).isTrue();
        assertThat(navigator.isAccessor(loaderInit)).as("다른 메서드를 부르는 생성자는 남긴다").isFalse();
        assertThat(navigator.isAccessor(andId)).isTrue();
        assertThat(navigator.isAccessor(exampleInit)).isTrue();
        assertThat(navigator.isAccessor(run)).isFalse();
        assertThat(navigator.isBuilderCall(andId)).isTrue();
        assertThat(navigator.isBuilderCall(voInit)).isFalse();
    }

    @Test
    @DisplayName("F-015-AC71 callers: 여러 화면에 들어가는 공통 JSP(5개 이상의 페이지가 include하거나 레이아웃인 것)는 그 위로 펼치지 않고 [shared] <JSP> (포함하는 화면 n개) 한 줄로 낸다")
    void sharedPagesAreNotClimbed() {
        GraphFixture f = GraphFixture.create();
        String handler = f.method("com.n.LoginController", "logout");
        String endpoint = f.endpoint("GET", "/logout", handler);
        String menu = f.jsp("src/main/webapp/inc/menu.jsp");
        String small = f.jsp("src/main/webapp/inc/small.jsp");
        f.request("GET", "/logout", "src/main/webapp/inc/menu.jsp", 4, menu, endpoint);
        f.request("GET", "/logout", "src/main/webapp/inc/small.jsp", 4, small, endpoint);
        for (int i = 1; i <= 5; i++) {
            String page = f.jsp("src/main/webapp/p" + i + ".jsp");
            f.edge(Edge.builder(page, menu, EdgeKind.INCLUDES).attr("via", "directive").build());
            if (i <= 4) {
                f.edge(Edge.builder(page, small, EdgeKind.INCLUDES).attr("via", "directive").build());
            }
        }
        GraphModel g = f.graph();

        String callers = text(g, endpoint, CallTreeOptions.callers());
        assertThat(callers).contains("""
                ├─ [request] GET /logout
                │  └─ [shared] [jsp] src/main/webapp/inc/menu.jsp (포함하는 화면 5개)
                └─ [request] GET /logout
                   └─ [jsp] src/main/webapp/inc/small.jsp
                      ├─ [jsp] src/main/webapp/p1.jsp
                """);
        assertThat(callers).doesNotContain("p5.jsp");
        // --layouts(expandLayouts)를 켜면 전처럼 화면마다 올라간다. 공통 JSP에서 시작하면 그대로 펼친다
        assertThat(text(g, endpoint, CallTreeOptions.builder().direction(Direction.CALLERS).expandLayouts(true).build())).contains("p5.jsp");
        assertThat(text(g, menu, CallTreeOptions.callers())).contains("[jsp] src/main/webapp/p5.jsp").doesNotContain("[shared]");
    }

    @Test
    @DisplayName("F-015-AC72 다른 방언 파일에서 보충한 구문(borrowedFrom)은 콜트리 구문 줄에 (다른 방언 <방언>에서 보충)이 붙고 report.md에 목록 절이 생긴다")
    void borrowedStatementsAreMarked() {
        GraphFixture f = GraphFixture.create();
        f.table("T_USER");
        String mapper = f.method("com.n.UserMapper", "selectUser");
        String borrowed = f.statement("com.n.UserMapper", "selectUser", "SELECT");
        f.attr(borrowed, "borrowedFrom", "mysql").attr(borrowed, "dialectVariant", "oracle");
        f.reads(borrowed, "T_USER");
        f.edge(mapper, borrowed, EdgeKind.EXECUTES);
        String plain = f.statement("com.n.UserMapper", "countUser", "SELECT");
        f.reads(plain, "T_USER");
        GraphModel g = f.graph();

        assertThat(callees(g, mapper)).isEqualTo("""
                UserMapper#selectUser()
                └─ [sql] UserMapper.selectUser <SELECT> (다른 방언 mysql에서 보충)
                   └─ [table] T_USER (R)
                """);
        assertThat(text(g, Ids.table("T_USER"), CallTreeOptions.callers())).contains("[sql] UserMapper.selectUser <SELECT> (R, 다른 방언 mysql에서 보충)");
        String md = SummaryReport.render(g);
        assertThat(md).contains("## 다른 방언 파일에서 보충한 구문 (1)\n").contains("| `com.n.UserMapper.selectUser` | mysql | `src/main/resources/mapper/UserMapper.xml:");
        assertThat(SummaryReport.render(GraphFixture.create().graph())).doesNotContain("보충한 구문");
    }

    @Test
    @DisplayName("F-015-AC73 report.md의 DDL에 없는 테이블: 시스템 카탈로그(systemCatalog=true)는 뒤에 따로 묶고 의심 표시를 붙이지 않는다")
    void systemCatalogTablesAreNotSuspicious() {
        GraphFixture f = GraphFixture.create();
        String ghost = f.undeclaredTable("TB_GHOST");
        String catalog = f.undeclaredTable("ALL_TAB_COLUMNS");
        f.attr(catalog, "systemCatalog", true);
        f.reads(f.statement("com.n.M", "a", "SELECT"), "TB_GHOST");
        f.reads(f.statement("com.n.M", "b", "SELECT"), "ALL_TAB_COLUMNS");
        assertThat(ghost).isNotNull();

        assertThat(SummaryReport.render(f.graph())).contains("""
                ## DDL에 없는 테이블 (2)

                SQL에서 참조했지만 DDL에서 정의를 찾지 못한 테이블이다. 참조하는 구문이 하나뿐인 테이블 1개는 의심으로 표시했다(오타이거나, 구문을 끝까지 파싱하지 못해 복구하면서 나온 이름일 수 있다). DB가 제공하는 시스템 카탈로그(데이터 사전) 1개는 뒤에 따로 묶었다.

                | 테이블 | 참조하는 구문 수 | 구문 (최대 5개) | 비고 |
                |---|---|---|---|
                | `TB_GHOST` | 1 | `M.a` | 의심(구문 파싱 복구에서 나왔을 수 있음) |
                | `ALL_TAB_COLUMNS` | 1 | `M.b` | 시스템 카탈로그 |
                """);
    }

    @Test
    @DisplayName("F-015-AC74 영향도: 데이터 적재 구문(dataLoad=true)은 구문 목록에서 기본으로 빼고 '데이터 적재 n건 제외' 한 줄을 적는다")
    void impactExcludesDataLoads() {
        GraphFixture f = GraphFixture.create();
        f.table("T_CODE");
        String select = f.statement("com.n.CodeMapper", "selectCode", "SELECT");
        f.reads(select, "T_CODE");
        for (int line : List.of(3, 90)) {
            String load = Ids.sqlStatement("db/data/code.sql", line);
            f.graph().declare(io.codeatlas.core.model.Node.builder(load, io.codeatlas.core.model.NodeKind.SQL_STATEMENT)
                    .location(SourceLocation.of("db/data/code.sql", line)).attr("statementType", "INSERT").attr("dataLoad", true).attr("rowCount", 10).build());
            f.writes(load, "T_CODE", "INSERT");
        }

        ImpactReport report = ImpactAnalysis.analyze(f.graph(), "T_CODE");
        assertThat(report.statements()).extracting(Item::id).containsExactly(select);
        assertThat(report.dataLoadExcluded()).isEqualTo(2);
        assertThat(report.toMarkdown()).contains("- 데이터 적재 구문 2건 제외").contains("## 구문 (1)");
        assertThat(report.toJson()).contains("\"dataLoadExcluded\": 2");
        ImpactReport with = ImpactAnalysis.analyze(f.graph(), "T_CODE", ImpactAnalysis.DEFAULT_MAX_DEPTH, true);
        assertThat(with.statements()).hasSize(3);
        assertThat(with.dataLoadExcluded()).isZero();
        assertThat(with.toMarkdown()).doesNotContain("제외");
    }
}
