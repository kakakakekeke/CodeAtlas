package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.ImpactReport.Forward;
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.ShopGraph;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 영향도에서 forward 경유의 구분: {@code forward:}로 넘어간 다른 핸들러를 거쳐서만 닿은 항목은 핸들러 자신의 호출 사슬로 닿은 항목과
 * 따로 표시한다(이름 앞과 경로의 {@code [forward]}, 개수 요약, JSON {@code viaForward}·{@code forward}).
 */
class ForwardImpactTest {

    private static final String CONTROLLER = "com.m.MemoController";
    private static final String FILE = "src/main/java/com/m/MemoController.java";
    private static final String PAGE = "src/main/webapp/memo/regist.jsp";

    private static Edge forward(GraphFixture f, String from, String to, int line, String path) {
        return f.edge(Edge.builder(from, to, EdgeKind.CALLS).inferred().attr("callType", "forward").attr("path", path)
                .attr("viewName", "forward:" + path).location(SourceLocation.of(FILE, line)).build());
    }

    private static Edge call(GraphFixture f, String from, String to, int line) {
        return f.edge(Edge.builder(from, to, EdgeKind.CALLS).attr("callType", "virtual").location(SourceLocation.of(FILE, line)).build());
    }

    /**
     * <pre>
     * GET  /memo/list.do   → list   → MemoService#findList ─EXECUTES→ selectList (TB_MEMO 읽기)
     * POST /memo/insert.do → insert ─forward→ list                                   (forward로만 닿는다)
     * GET  /memo/both.do   → both   ─forward→ list
     *                               └→ MemoFacade#load → MemoFacade#inner → findList (직접 닿는 길이 더 길다)
     * POST /memo/guess.do  → guess  ─forward→ list
     *                               └~ Codec#encode(String, int) → findList          (직접 닿는 길은 추론뿐이다)
     * regist.jsp ─요청→ POST /memo/insert.do
     * </pre>
     */
    private static final class Memo {
        final GraphFixture f = GraphFixture.create();
        final String list = f.method(CONTROLLER, "list");
        final String insert = f.method(CONTROLLER, "insert");
        final String both = f.method(CONTROLLER, "both");
        final String guess = f.method(CONTROLLER, "guess");
        final String findList = f.method("com.m.MemoService", "findList");
        final String load = f.method("com.m.MemoFacade", "load");
        final String inner = f.method("com.m.MemoFacade", "inner");
        final String encodeOne = f.method("com.m.Codec", "encode", List.of("java.lang.String"), "java.lang.String", "public");
        final String encodeTwo = f.method("com.m.Codec", "encode", List.of("java.lang.String", "int"), "java.lang.String", "public");
        final String selectList = f.statement("com.m.MemoMapper", "selectList", "SELECT");
        final String listEndpoint = f.endpoint("GET", "/memo/list.do", list);
        final String insertEndpoint = f.endpoint("POST", "/memo/insert.do", insert);
        final String bothEndpoint = f.endpoint("GET", "/memo/both.do", both);
        final String guessEndpoint = f.endpoint("POST", "/memo/guess.do", guess);
        final String page = f.jsp(PAGE);
        final String request = f.request("POST", "/memo/insert.do", PAGE, 12, page, insertEndpoint);

        Memo() {
            f.table("TB_MEMO");
            f.reads(selectList, "TB_MEMO");
            f.edge(findList, selectList, EdgeKind.EXECUTES);
            call(f, list, findList, 20);
            forward(f, insert, list, 40, "/memo/list.do");
            forward(f, both, list, 60, "/memo/list.do");
            call(f, both, load, 55);
            call(f, load, inner, 10);
            call(f, inner, findList, 15);
            forward(f, guess, list, 80, "/memo/list.do");
            for (String candidate : List.of(encodeOne, encodeTwo)) {
                f.edge(Edge.builder(guess, candidate, EdgeKind.CALLS).inferred().attr("callType", "virtual").attr("ambiguous", true)
                        .location(SourceLocation.of(FILE, 75, 10, 75, 30)).build());
            }
            call(f, encodeTwo, findList, 5);
        }
    }

    private static Item of(List<Item> items, String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> forwarded(List<Item> items) {
        return items.stream().filter(Item::viaForward).map(Item::label).toList();
    }

    @Test
    @DisplayName("F-015-AC90 영향도: forward로 넘어간 핸들러를 거쳐서만 닿은 호출 사슬·엔드포인트·화면 항목은 forward 경유로 구분하고, forward를 거치지 않는 같은 확실함의 경로가 있으면 그 경로를 적는다")
    void marksItemsReachedOnlyThroughForward() {
        Memo m = new Memo();
        ImpactReport report = ImpactAnalysis.analyze(m.f.graph(), "TB_MEMO");

        assertThat(report.endpoints()).extracting(Item::label)
                .containsExactly("GET /memo/both.do", "GET /memo/list.do", "POST /memo/guess.do", "POST /memo/insert.do");
        assertThat(forwarded(report.endpoints())).containsExactly("POST /memo/guess.do", "POST /memo/insert.do");
        assertThat(forwarded(report.callers())).containsExactlyInAnyOrder("MemoController#guess()", "MemoController#insert()");
        assertThat(report.callers()).hasSize(7);
        assertThat(report.screens()).hasSize(2).allMatch(Item::viaForward);
        assertThat(report.executors()).noneMatch(Item::viaForward);
        assertThat(report.statements()).noneMatch(Item::viaForward);

        // forward로만 닿는 엔드포인트: 경로에서 넘어간 대상 핸들러에 [forward]가 붙고, 넘긴 쪽·넘어간 쪽·경로를 안다
        Item insert = of(report.endpoints(), m.insertEndpoint);
        assertThat(insert.path()).containsExactly(m.insertEndpoint, m.insert, m.list, m.findList, m.selectList);
        assertThat(insert.pathLabels()).containsExactly("[endpoint] POST /memo/insert.do", "MemoController#insert()",
                "[forward] MemoController#list()", "MemoService#findList()", "[sql] MemoMapper.selectList <SELECT>");
        assertThat(insert.forward()).isEqualTo(new Forward(m.insert, m.list, "/memo/list.do"));
        assertThat(insert.inferred()).as("forward는 추론 걸음이 아니다(F-015-R52)").isFalse();
        assertThat(of(report.screens(), m.page).forward()).isEqualTo(insert.forward());

        // 넘어간 쪽 핸들러와 그 엔드포인트는 직접 닿는다
        assertThat(of(report.endpoints(), m.listEndpoint).viaForward()).isFalse();
        assertThat(of(report.endpoints(), m.listEndpoint).forward()).isNull();
        assertThat(of(report.callers(), m.list).viaForward()).isFalse();

        // forward로도 닿고 자기 호출 사슬로도 닿으면 직접이다: 더 길어도 forward를 거치지 않는 경로를 적는다
        Item both = of(report.endpoints(), m.bothEndpoint);
        assertThat(both.viaForward()).isFalse();
        assertThat(both.path()).containsExactly(m.bothEndpoint, m.both, m.load, m.inner, m.findList, m.selectList);
        assertThat(both.pathLabels()).noneMatch(label -> label.startsWith("[forward]"));

        // 직접 닿는 길은 추론뿐이고 확정으로 닿는 길은 forward 경유뿐이면: 확정 경로를 적고 forward 경유로 표시한다(~는 없다)
        Item guess = of(report.endpoints(), m.guessEndpoint);
        assertThat(guess.viaForward()).isTrue();
        assertThat(guess.inferred()).isFalse();
        assertThat(guess.path()).containsExactly(m.guessEndpoint, m.guess, m.list, m.findList, m.selectList);

        // 입력 순서를 뒤집어도 같다
        assertThat(ImpactAnalysis.analyze(m.f.reversedCopy(), "TB_MEMO").toMarkdown()).isEqualTo(report.toMarkdown());
    }

    @Test
    @DisplayName("F-015-AC90 영향도 Markdown·JSON: 이름 앞과 경로의 [forward], 경로 끝의 (forward: <경로>), 직접·forward 경유 개수, 안내 줄, viaForward·forward")
    void rendersForwardMarks() {
        Memo m = new Memo();
        ImpactReport report = ImpactAnalysis.analyze(m.f.graph(), "TB_MEMO");

        String md = report.toMarkdown();
        assertThat(md).contains("- 구문 1개, 실행 주체 1개, 호출 사슬 7개(직접 5, forward 경유 2), 엔드포인트 4개(직접 2, forward 경유 2), "
                + "화면 2개(forward 경유 2), 배치 0개\n");
        assertThat(md).contains("- `[forward]`는 forward로 넘어간 다른 엔드포인트의 핸들러를 거쳐서만 닿은 항목이다(6개.");
        assertThat(md).contains("\n## 호출 사슬 (7: 직접 5, forward 경유 2)\n").contains("\n## 엔드포인트 (4: 직접 2, forward 경유 2)\n")
                .contains("\n## 화면 (2: forward 경유 2)\n").contains("\n## 실행하는 메서드·루틴·배치 스텝 (1)\n");
        assertThat(md).contains("| [forward] `POST /memo/insert.do` | endpoint | `" + FILE + ":")
                .contains("` | `[endpoint] POST /memo/insert.do` → `MemoController#insert()` → `[forward] MemoController#list()` → "
                        + "`MemoService#findList()` → `[sql] MemoMapper.selectList <SELECT>` (forward: /memo/list.do) |");
        assertThat(md).contains("| [forward] `MemoController#insert()` | method |").contains("| [forward] `POST /memo/insert.do` | request |")
                .contains("| [forward] `" + PAGE + "` | jsp |").contains("| [forward] `POST /memo/guess.do` | endpoint |");
        // 직접 닿는 행에는 표시가 없다
        assertThat(md).contains("| `GET /memo/list.do` | endpoint |").contains("| `GET /memo/both.do` | endpoint |")
                .contains("| `MemoController#list()` | method |").doesNotContain("| ~ ");

        String json = report.toJson();
        assertThat(json).contains("\"viaForward\": true").contains("\"viaForward\": false").contains("\"forward\": null")
                .contains("\"from\": \"" + m.insert + "\"").contains("\"to\": \"" + m.list + "\"").contains("\"target\": \"/memo/list.do\"");
    }

    @Test
    @DisplayName("F-015-AC90 forward가 없는 그래프의 영향도 Markdown은 전과 같고(forward라는 글자가 없다), redirect로만 이어진 엔드포인트는 없으며, 추론한 forward로만 닿은 행은 ~ [forward]다")
    void plainReportsAreUnchangedAndInferredForwardsKeepBothMarks() {
        ShopGraph shop = new ShopGraph();
        ImpactReport plain = ImpactAnalysis.analyze(shop.f.graph(), "ORDERS");
        assertThat(plain.toMarkdown()).doesNotContain("forward").contains("## 엔드포인트 (2)").contains("엔드포인트 2개, 화면");
        assertThat(plain.all()).noneMatch(Item::viaForward);
        assertThat(plain.toJson()).contains("\"viaForward\": false").doesNotContain("\"viaForward\": true");

        GraphFixture f = GraphFixture.create();
        f.table("TB_MEMO");
        String list = f.method(CONTROLLER, "list");
        String far = f.method(CONTROLLER, "far");
        String moved = f.method(CONTROLLER, "moved");
        String selectList = f.statement("com.m.MemoMapper", "selectList", "SELECT");
        f.reads(selectList, "TB_MEMO");
        f.edge(list, selectList, EdgeKind.EXECUTES);
        f.endpoint("GET", "/memo/list.do", list);
        f.endpoint("GET", "/memo/far.do", far);
        f.endpoint("GET", "/memo/moved.do", moved);
        // 후보 여럿 가운데서 맞춘 forward는 추론 걸음이다(F-015-R52). 넘어간 경로를 모르는 forward(서블릿 API)
        f.edge(Edge.builder(far, list, EdgeKind.CALLS).inferred().attr("callType", "forward").attr("ambiguous", true)
                .location(SourceLocation.of(FILE, 30)).build());
        f.edge(Edge.builder(moved, list, EdgeKind.CALLS).inferred().attr("callType", "redirect").attr("path", "/memo/list.do")
                .location(SourceLocation.of(FILE, 50)).build());
        GraphModel g = f.graph();

        ImpactReport report = ImpactAnalysis.analyze(g, "TB_MEMO");
        assertThat(report.endpoints()).extracting(Item::label).containsExactly("GET /memo/far.do", "GET /memo/list.do");
        Item farEndpoint = report.endpoints().get(0);
        assertThat(farEndpoint.viaForward()).isTrue();
        assertThat(farEndpoint.inferred()).isTrue();
        assertThat(farEndpoint.forward()).isEqualTo(new Forward(far, list, null));
        String md = report.toMarkdown();
        assertThat(md).contains("| ~ [forward] `GET /memo/far.do` | endpoint |").contains("`[forward] MemoController#list()`")
                .contains("엔드포인트 2개(직접 1, forward 경유 1)").doesNotContain("(forward: ");
        assertThat(report.toJson()).contains("\"target\": null");
    }
}
