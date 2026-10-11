package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.CrudMatrix.Layout;
import io.codeatlas.export.calltree.CrudMatrix.Row;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.fixture.GenericDaoGraph;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.TriggerShop;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 추론 경로로만 닿은 접근의 표시: CRUD 매트릭스 칸 끝의 {@code ?}, 영향도 항목의 {@code ~}와 근거.
 * "추론 걸음"은 콜트리에서 {@code ~}·{@code [?]}·{@code [trigger?]}가 붙는 걸음이되, 프레임워크의 규칙으로 정해지는 두 가지
 * (요청 전 처리 {@code [before]}, 수신자 문맥이 대상 하나를 고른 팬아웃 엣지)는 확정으로 본다.
 */
class InferredPathTest {

    private static final String FILE = "src/main/java/com/i/OrderController.java";

    private static List<String> cells(CrudMatrix matrix, String subjectId) {
        for (Row row : matrix.rows()) {
            if (row.id().equals(subjectId)) {
                return row.cells().entrySet().stream().map(e -> Ids.remainder(e.getKey()) + "=" + e.getValue()).sorted().toList();
            }
        }
        return List.of();
    }

    private static Edge inferredCall(GraphFixture f, String from, String to, int line, String callType, String flag) {
        Edge.Builder b = Edge.builder(from, to, EdgeKind.CALLS).inferred().attr("callType", callType)
                .location(SourceLocation.of(FILE, line, 10, line, 30));
        if (flag != null) {
            b.attr(flag, true);
        }
        return f.edge(b.build());
    }

    /**
     * <pre>
     * POST /order → OrderController#save
     *   ├─ OrderService#sure            ─EXECUTES→ selectSure (T_SURE, T_BOTH 읽기)
     *   ├─ ~ Codec#encode(String)       ─EXECUTES→ callProc ─CALLS→ PROC_X (T_PROC 읽고 갱신)      [ambiguous]
     *   └─ ~ Codec#encode(String, int)  ─EXECUTES→ selectGuess (T_GUESS, T_BOTH 읽기)              [ambiguous]
     * </pre>
     */
    private static final class Shop {
        final GraphFixture f = GraphFixture.create();
        final String handler = f.method("com.i.OrderController", "save");
        final String endpoint = f.endpoint("POST", "/order", handler);
        final String sure = f.method("com.i.OrderService", "sure");
        final String one = f.method("com.i.Codec", "encode", List.of("java.lang.String"), "java.lang.String", "public");
        final String two = f.method("com.i.Codec", "encode", List.of("java.lang.String", "int"), "java.lang.String", "public");
        final String selectSure = f.statement("com.i.OrderMapper", "selectSure", "SELECT");
        final String selectGuess = f.statement("com.i.OrderMapper", "selectGuess", "SELECT");
        final String callProc = f.statement("com.i.OrderMapper", "callProc", "CALL");

        Shop() {
            for (String table : List.of("T_SURE", "T_GUESS", "T_BOTH", "T_PROC")) {
                f.table(table);
            }
            f.edge(Edge.builder(handler, sure, EdgeKind.CALLS).attr("callType", "virtual").location(SourceLocation.of(FILE, 20)).build());
            inferredCall(f, handler, one, 30, "virtual", "ambiguous");
            inferredCall(f, handler, two, 30, "virtual", "ambiguous");
            f.reads(selectSure, "T_SURE").reads(selectSure, "T_BOTH");
            f.reads(selectGuess, "T_GUESS").reads(selectGuess, "T_BOTH");
            f.edge(sure, selectSure, EdgeKind.EXECUTES);
            f.edge(two, selectGuess, EdgeKind.EXECUTES);
            String routine = f.routine("PROC_X", "PROCEDURE");
            String inner = f.routineStatement(routine, 5, "UPDATE");
            f.reads(inner, "T_PROC").writes(inner, "T_PROC", "UPDATE");
            f.edge(callProc, routine, EdgeKind.CALLS);
            f.edge(one, callProc, EdgeKind.EXECUTES);
        }
    }

    @Test
    @DisplayName("F-015-AC63 테이블 × 엔드포인트: 오버로드 후보(ambiguous)로만 닿은 칸 끝에 ?가 붙고(R?, ru?), 확정 경로가 하나라도 있으면 붙지 않는다")
    void ambiguousOnlyCellsAreMarked() {
        Shop shop = new Shop();
        GraphModel g = shop.f.graph();

        CrudMatrix m = CrudMatrix.of(g, Subject.ENDPOINT);

        assertThat(cells(m, shop.endpoint)).containsExactly("T_BOTH=R", "T_GUESS=R?", "T_PROC=ru?", "T_SURE=R");
        assertThat(m.cell(shop.endpoint, "T_GUESS")).isEqualTo("R?");
        // CSV와 Markdown에도 같은 글자가 나온다. 머리줄과 열 구성은 그대로다
        assertThat(m.toCsv(Layout.MATRIX, false)).startsWith("엔드포인트,위치,T_BOTH,T_GUESS,T_PROC,T_SURE\n").contains(",R,R?,ru?,R\n");
        assertThat(m.toMarkdown(Layout.LIST)).contains("| T_GUESS | `POST /order` | R? |").contains("| T_SURE | `POST /order` | R |");
        // 입력 순서를 뒤집어도 같다
        assertThat(CrudMatrix.of(shop.f.reversedCopy(), Subject.ENDPOINT).toCsv(Layout.MATRIX, false)).isEqualTo(m.toCsv(Layout.MATRIX, false));
        // 추론으로 먼저 닿은 메서드에 나중에 확정 경로로 닿으면 그 아래의 칸도 확정이다
        shop.f.calls(shop.sure, shop.two);
        assertThat(cells(CrudMatrix.of(shop.f.graph(), Subject.ENDPOINT), shop.endpoint))
                .containsExactly("T_BOTH=R", "T_GUESS=R", "T_PROC=ru?", "T_SURE=R");
    }

    @Test
    @DisplayName("F-015-AC63 테이블 × 엔드포인트: prefixMatch EXECUTES, 추론한 재정의, 후보 여럿 가운데서 맞춘 forward로만 닿은 칸은 ?이고, 요청 전 처리([before])와 보통의 forward(같은 요청 안의 이동)로 닿은 칸은 아니다")
    void otherInferredStepsAreMarked() {
        GraphFixture f = GraphFixture.create();
        for (String table : List.of("T_PREFIX", "T_FORWARD", "T_FORWARD_GUESS", "T_BEFORE", "T_IMPL")) {
            f.table(table);
        }
        String handler = f.method("com.i.OrderController", "save");
        String endpoint = f.endpoint("POST", "/order", handler);
        // 구문 ID 접두 일치로 이은 EXECUTES
        String prefixed = f.statement("com.i.OrderMapper", "selectOrderList_D", "SELECT");
        f.reads(prefixed, "T_PREFIX");
        f.edge(Edge.builder(handler, prefixed, EdgeKind.EXECUTES).inferred().attr("prefixMatch", true)
                .attr("statementPrefix", "com.i.OrderMapper.selectOrderList").build());
        // forward로 넘어간 핸들러(같은 요청)
        String target = f.method("com.i.OrderController", "list");
        f.endpoint("GET", "/order/list", target);
        inferredCall(f, handler, target, 40, "forward", null);
        String forwarded = f.statement("com.i.OrderMapper", "selectList", "SELECT");
        f.reads(forwarded, "T_FORWARD");
        f.edge(target, forwarded, EdgeKind.EXECUTES);
        // forward 대상을 후보 여럿 가운데서 맞춘 것(ambiguous)은 추론이다
        String guessed = f.method("com.i.OrderController", "guess");
        f.edge(Edge.builder(handler, guessed, EdgeKind.CALLS).inferred().attr("callType", "forward").attr("ambiguous", true)
                .location(SourceLocation.of(FILE, 41, 10, 41, 30)).build());
        String guessedStatement = f.statement("com.i.OrderMapper", "selectGuessed", "SELECT");
        f.reads(guessedStatement, "T_FORWARD_GUESS");
        f.edge(guessed, guessedStatement, EdgeKind.EXECUTES);
        // 요청 전 처리: 스프링이 늘 부른다
        String before = f.method("com.i.OrderController", "get");
        inferredCall(f, handler, before, 15, "modelAttribute", null);
        String loaded = f.statement("com.i.OrderMapper", "get", "SELECT");
        f.reads(loaded, "T_BEFORE");
        f.edge(before, loaded, EdgeKind.EXECUTES);
        // 추론한 OVERRIDES로 이어진 구현
        f.type("com.i.Policy", "interface", "public");
        String policy = f.method("com.i.Policy", "apply", List.of(), "void", "public", "abstract");
        f.type("com.i.DefaultPolicy", "class", "public");
        String impl = f.method("com.i.DefaultPolicy", "apply");
        f.edge(Edge.builder(impl, policy, EdgeKind.OVERRIDES).inferred().build());
        f.edge(Edge.builder(handler, policy, EdgeKind.CALLS).attr("callType", "interface").location(SourceLocation.of(FILE, 50)).build());
        String applied = f.statement("com.i.OrderMapper", "apply", "UPDATE");
        f.writes(applied, "T_IMPL", "UPDATE");
        f.edge(impl, applied, EdgeKind.EXECUTES);

        CrudMatrix m = CrudMatrix.of(f.graph(), Subject.ENDPOINT);

        assertThat(cells(m, endpoint)).containsExactly("T_BEFORE=R", "T_FORWARD=R", "T_FORWARD_GUESS=R?", "T_IMPL=U?", "T_PREFIX=R?");
    }

    @Test
    @DisplayName("F-015-AC63 테이블 × 엔드포인트: 수신자 문맥이 대상 하나를 고른 상속 구문은 확정이고, 고르지 못했거나([?]) 문맥이 없거나 여럿을 고르면 ?다. [trigger?]를 거쳐도 ?다")
    void fanOutSelectedByReceiverIsCertain() {
        GenericDaoGraph dao = new GenericDaoGraph();
        CrudMatrix m = CrudMatrix.of(dao.f.graph(), Subject.ENDPOINT);

        assertThat(cells(m, dao.getDict)).containsExactly("SYS_DICT=R");
        assertThat(cells(m, dao.getDictDirect)).containsExactly("SYS_DICT=R");
        assertThat(cells(m, dao.postUser)).containsExactly("SYS_USER=C");
        // MixService는 DAO 둘을 참조한다: 어느 구문이 실행되는지 정해지지 않는다
        assertThat(cells(m, dao.postMix)).containsExactly("SYS_DICT=C?", "SYS_LOG=C?");
        // 수신자 타입을 모른다(표시 없이 전부 따라간 줄) / 구문이 없는 하위 DAO([?])
        assertThat(cells(m, dao.getRaw)).containsExactly("SYS_DICT=R?", "SYS_LOG=R?", "SYS_USER=R?");
        assertThat(cells(m, dao.getOdd)).containsExactly("SYS_DICT=R?", "SYS_LOG=R?", "SYS_USER=R?");

        // 사건을 모르는 트리거([trigger?])를 거쳐서만 닿은 테이블
        TriggerShop shop = new TriggerShop();
        shop.f.table("TB_AUDIT");
        String unknown = shop.f.trigger("TRG_ORDER_AUDIT", "TB_ORDER", null);
        shop.f.writes(shop.f.routineStatement(unknown, 3, "INSERT"), "TB_AUDIT", "INSERT");
        CrudMatrix triggers = CrudMatrix.of(shop.f.graph(), Subject.ENDPOINT);
        assertThat(CallTrees.toText(CallTrees.build(shop.f.graph(), shop.saveEndpoint, CallTreeOptions.callees()), false))
                .contains("[trigger?] [trigger] TRG_ORDER_AUDIT");
        assertThat(triggers.cell(shop.saveEndpoint, "TB_ORDER")).isEqualTo("C");
        assertThat(triggers.cell(shop.saveEndpoint, "TB_EVENT")).isEqualTo("c");
        assertThat(triggers.cell(shop.saveEndpoint, "TB_AUDIT")).isEqualTo("c?");
    }

    @Test
    @DisplayName("F-015-AC64 테이블 × 메서드: EXECUTES 자체가 추론일 때만 ?가 붙는다(상속 매퍼 메서드의 행). 구문·루틴 기준에는 붙지 않는다")
    void methodMatrixMarksInferredExecutes() {
        GenericDaoGraph dao = new GenericDaoGraph();
        GraphFixture f = dao.f;
        // 같은 메서드가 확정 EXECUTES로도 SYS_DICT를 읽는다 / 확정 EXECUTES만 가진 보통의 매퍼 메서드
        String plain = f.method(GenericDaoGraph.PKG + "DictDao", "findOne");
        String findOne = f.statement(GenericDaoGraph.PKG + "DictDao", "findOne", "SELECT");
        f.reads(findOne, "SYS_DICT");
        f.edge(plain, findOne, EdgeKind.EXECUTES);
        GraphModel g = f.graph();

        CrudMatrix methods = CrudMatrix.of(g, Subject.METHOD);
        assertThat(cells(methods, dao.daoFindList)).containsExactly("SYS_DICT=R?", "SYS_LOG=R?", "SYS_USER=R?");
        assertThat(cells(methods, dao.daoInsert)).containsExactly("SYS_DICT=C?", "SYS_LOG=C?", "SYS_USER=C?");
        assertThat(cells(methods, plain)).containsExactly("SYS_DICT=R");
        f.edge(dao.daoFindList, findOne, EdgeKind.EXECUTES);
        assertThat(cells(CrudMatrix.of(f.graph(), Subject.METHOD), dao.daoFindList)).containsExactly("SYS_DICT=R", "SYS_LOG=R?", "SYS_USER=R?");

        for (Subject subject : List.of(Subject.STATEMENT, Subject.ROUTINE)) {
            assertThat(CrudMatrix.of(new Shop().f.graph(), subject).rows().stream().flatMap(r -> r.cells().values().stream()))
                    .as(subject.name()).isNotEmpty().noneMatch(c -> c.contains("?"));
        }
    }

    @Test
    @DisplayName("F-015-AC66 영향도: 추론 경로로만 닿은 호출 사슬·엔드포인트·화면 항목에 ~와 근거(첫 추론 엣지)가 붙고, 확정 경로가 있으면 그 경로를 적는다")
    void impactMarksInferredItems() {
        Shop shop = new Shop();
        GraphFixture f = shop.f;
        // 화면: JSP의 요청이 엔드포인트와 다른 모듈로 맞춰졌다(crossModule)
        String jsp = f.jsp("web/src/main/webapp/order.jsp");
        String request = f.request("POST", "/order", "web/src/main/webapp/order.jsp", 12, jsp, null);
        f.edge(Edge.builder(request, shop.endpoint, EdgeKind.MATCHES).attr("crossModule", true).build());
        GraphModel g = f.graph();

        ImpactReport guess = ImpactAnalysis.analyze(g, "T_GUESS");
        Item executor = guess.executors().get(0);
        assertThat(executor.id()).isEqualTo(shop.two);
        assertThat(executor.inference()).isNull();
        Item handler = guess.callers().stream().filter(i -> i.id().equals(shop.handler)).findFirst().orElseThrow();
        assertThat(handler.inference()).isEqualTo("OrderController#save() → Codec#encode(String, int): 후보 여럿(ambiguous)");
        Item endpoint = guess.endpoints().get(0);
        assertThat(endpoint.inference()).isEqualTo(handler.inference());
        assertThat(guess.screens()).isNotEmpty().allMatch(i -> i.inference() != null);
        // 화면 쪽에서 본 첫 추론 엣지는 요청 ↔ 엔드포인트 대응이다
        Item page = guess.screens().stream().filter(i -> i.id().equals(jsp)).findFirst().orElseThrow();
        assertThat(page.inference()).isEqualTo("[request] POST /order → [endpoint] POST /order: 다른 모듈의 핸들러(crossModule)");
        String md = guess.toMarkdown();
        assertThat(md).contains("| ~ `POST /order` | endpoint |").contains("추론: OrderController#save() → Codec#encode(String, int): 후보 여럿(ambiguous)")
                .contains("`~`는 추론한 관계를 거쳐서만 닿은 항목이다");
        assertThat(guess.toJson()).contains("\"inferred\": true").contains("\"inference\": \"OrderController#save() → Codec#encode(String, int): 후보 여럿(ambiguous)\"");

        // 확정 경로가 있는 테이블: 엔드포인트에 표시가 없고 경로는 확정 경로다(화면은 여전히 추론한 대응을 거친다)
        ImpactReport sure = ImpactAnalysis.analyze(g, "T_SURE");
        assertThat(sure.endpoints().get(0).inference()).isNull();
        assertThat(sure.callers()).allMatch(i -> i.inference() == null);
        assertThat(sure.toMarkdown()).contains("| `POST /order` | endpoint |").doesNotContain("| ~ `POST /order` | endpoint |")
                .contains("| ~ `POST /order` | request |");
        assertThat(sure.toJson()).contains("\"inferred\": false");
        ImpactReport both = ImpactAnalysis.analyze(g, "T_BOTH");
        Item bothEndpoint = both.endpoints().get(0);
        assertThat(bothEndpoint.inference()).isNull();
        assertThat(bothEndpoint.path()).containsExactly(shop.endpoint, shop.handler, shop.sure, shop.selectSure);
        // 추론이 하나도 없는 리포트에는 안내 줄이 없다
        assertThat(ImpactAnalysis.analyze(new GenericDaoGraph().f.graph(), "NOPE").toMarkdown()).doesNotContain("추론한 관계를 거쳐서만");
    }

    @Test
    @DisplayName("F-015-AC66 영향도: 상속 매퍼 메서드는 수신자가 맞는 호출자가 확인해 줄 때 확정이고, 그 메서드 자신과 [?]·수신자 미상 호출자는 ~다")
    void impactConfirmsFanOutByReceiver() {
        GenericDaoGraph dao = new GenericDaoGraph();
        ImpactReport dict = ImpactAnalysis.analyze(dao.f.graph(), "SYS_DICT");

        Item inherited = dict.executors().stream().filter(i -> i.id().equals(dao.daoFindList)).findFirst().orElseThrow();
        assertThat(inherited.inference()).contains("CrudDao#findList(Object) → [sql] DictDao.findList").contains("수신자 타입");
        assertThat(inferredLabels(dict.endpoints())).containsExactly("GET /odd", "GET /raw", "POST /mix");
        assertThat(dict.endpoints()).extracting(Item::label).containsExactly("GET /dict", "GET /dict/direct", "GET /odd", "GET /raw", "POST /mix");
        Item viaService = dict.callers().stream().filter(i -> i.label().equals("CrudService#findPage(Object)")).findFirst().orElseThrow();
        assertThat(viaService.inference()).as("조건을 넘겨받은 상위 서비스는 아직 확인되지 않았다").isNotNull();
        Item controller = dict.callers().stream().filter(i -> i.label().equals("DictController#list()")).findFirst().orElseThrow();
        assertThat(controller.inference()).isNull();
    }

    private static List<String> inferredLabels(List<Item> items) {
        return items.stream().filter(i -> i.inference() != null).map(Item::label).toList();
    }
}
