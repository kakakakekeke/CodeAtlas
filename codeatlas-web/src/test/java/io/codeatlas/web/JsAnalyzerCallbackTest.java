package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.web.analysis.JsProjectAnalysis;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** props·매개변수로 받은 함수의 호출(F-010-R15, F-010-R16). */
class JsAnalyzerCallbackTest {

    private static final String APP = """
            import React, { useState } from 'react';
            import OrderList from './OrderList';
            import Detail from './Detail';
            import { track } from './track';
            import { Row } from './Row';
            function App() {
              const [id, setId] = useState(null);
              const handleSelect = (orderId) => { setId(orderId); };
              function handleBack() {}
              return (
                <div>
                  <OrderList onSelect={handleSelect} onExport={track} onOther={setId} title="주문" />
                  <Detail onBack={handleBack} onInline={() => handleBack()} onClose={handleBack} label="x" />
                  <Row onPick={track} label="a" />
                </div>
              );
            }
            export default App;
            """;
    private static final String ADMIN = """
            import OrderList from './OrderList';
            export function Admin() {
              const pick = (id) => {};
              return <OrderList onSelect={pick} />;
            }
            """;
    private static final String ORDER_LIST = """
            export default function OrderList({ onSelect, onExport: exportAll, onOther, title }) {
              const click = () => { exportAll(); };
              return (
                <a onClick={() => onSelect(1)}
                   onBlur={() => onOther(2)} onFocus={click}>{title}</a>
              );
            }
            """;
    private static final String DETAIL = """
            const Detail = (props) => {
              const back = () => props.onBack();
              return (
                <button onClick={back} onFocus={() => props.onInline()}
                        onBlur={props.onClose}>{props.label.trim()}</button>
              );
            };
            export default Detail;
            """;

    private static final String ROW = """
            export function Row(props) {
              const { onPick, label } = props;
              return <li onClick={() => onPick(label)}>{label}</li>;
            }
            """;
    private static final String LEGACY = """
            import React from 'react';
            import Detail from './Detail';
            export class Legacy extends React.Component {
              handleBack() {}
              render() { return <Detail onBack={this.handleBack.bind(this)} />; }
            }
            """;

    private static TestProject react() {
        return TestProject.create()
                .file("src/Legacy.jsx", LEGACY)
                .file("src/Row.jsx", ROW)
                .file("src/App.jsx", APP)
                .file("src/Admin.jsx", ADMIN)
                .file("src/OrderList.jsx", ORDER_LIST)
                .file("src/Detail.jsx", DETAIL)
                .file("src/track.js", "export function track() {}\n");
    }

    private static List<Diagnostic> skipped(G g, String path) {
        return g.diagnostics(JsProjectAnalysis.CALLS_SKIPPED).stream()
                .filter(d -> d.location() != null && path.equals(d.location().file())).toList();
    }

    @Test
    @DisplayName("F-010-AC22 컴포넌트가 props로 받은 함수를 호출하면, 부모가 JSX에서 넘긴 프로젝트 함수로 INFERRED CALLS(callType=call, viaProp)를 만든다")
    void propCallbacks() {
        try (TestProject project = react()) {
            G g = new G(project.run(new JsAnalyzer()));
            String list = fn("src/OrderList.jsx", "OrderList");
            String handleSelect = fn("src/App.jsx", "App.handleSelect");
            String pick = fn("src/Admin.jsx", "Admin.pick");

            // 구조 분해 매개변수 { onSelect }: 부모가 둘이면 각각
            for (String target : List.of(handleSelect, pick)) {
                Edge e = g.edge(list, EdgeKind.CALLS, target);
                assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
                assertThat(e.attrString("callType")).isEqualTo("call");
                assertThat(e.attrString("viaProp")).isEqualTo("onSelect");
                assertThat(e.location().file()).isEqualTo("src/OrderList.jsx");
                assertThat(e.location().startLine()).as("자식의 호출 지점").isEqualTo(4);
            }
            // 다른 이름으로 받은 것({ onExport: exportAll })도 prop 이름으로 찾고, 호출한 중첩 함수에서 나간다
            Edge export = g.edge(fn("src/OrderList.jsx", "OrderList.click"), EdgeKind.CALLS, fn("src/track.js", "track"));
            assertThat(export.attrString("viaProp")).isEqualTo("onExport");
            assertThat(export.confidence()).isEqualTo(Confidence.INFERRED);
            // props.onBack(): props 매개변수의 멤버 호출
            Edge back = g.edge(fn("src/Detail.jsx", "Detail.back"), EdgeKind.CALLS, fn("src/App.jsx", "App.handleBack"));
            assertThat(back.attrString("viaProp")).isEqualTo("onBack");
            assertThat(back.attrString("callType")).isEqualTo("call");
            // 본문에서 props를 구조 분해해 꺼낸 것(const { onPick } = props)도 props로 받은 함수다
            assertThat(g.edge(fn("src/Row.jsx", "Row"), EdgeKind.CALLS, fn("src/track.js", "track")).attrString("viaProp"))
                    .isEqualTo("onPick");
            // 부모가 메서드를 bind해서 넘긴 것(this.handleBack.bind(this))도 그 메서드를 넘긴 것이다
            assertThat(g.edge(fn("src/Detail.jsx", "Detail.back"), EdgeKind.CALLS, fn("src/Legacy.jsx", "Legacy.handleBack"))
                    .attrString("viaProp")).isEqualTo("onBack");

            // 넘긴 값이 프로젝트 함수가 아니거나(useState의 setter) 인라인 화살표 함수면 엣지를 더하지 않는다
            assertThat(g.targets(list, EdgeKind.CALLS)).containsExactlyInAnyOrder(handleSelect, pick, fn("src/OrderList.jsx", "OrderList.click"));
            assertThat(g.targets(fn("src/Detail.jsx", "Detail"), EdgeKind.CALLS)).containsExactly(fn("src/Detail.jsx", "Detail.back"));
            // 부모 쪽은 그대로다: 넘긴 참조는 ref, 인라인 화살표 함수의 본문은 부모에 귀속
            String app = fn("src/App.jsx", "App");
            assertThat(g.edge(app, EdgeKind.CALLS, handleSelect).attrString("callType")).isEqualTo("ref");
            assertThat(g.graph().edges(app, EdgeKind.CALLS, fn("src/App.jsx", "App.handleBack")).stream()
                    .map(e -> e.attrString("callType")).sorted().toList()).containsExactly("call", "ref", "ref");
        }
    }

    @Test
    @DisplayName("F-010-AC23 props·매개변수로 받은 함수의 호출 가운데 대상을 정하지 못한 것은 모듈마다 INFO 진단 CALLS_SKIPPED 1건으로 건수를 남긴다")
    void skippedCallbackCallsAreReported() {
        try (TestProject project = react()) {
            G g = new G(project.run(new JsAnalyzer()));

            // OrderList.jsx: onOther(2) 하나(onSelect, exportAll은 viaProp으로 이어졌다)
            List<Diagnostic> list = skipped(g, "src/OrderList.jsx");
            assertThat(list).hasSize(1);
            assertThat(list.get(0).severity()).isEqualTo(Severity.INFO);
            assertThat(list.get(0).message()).contains("수신자를 알 수 없는 호출 1건").contains("src/OrderList.jsx").contains("줄 5");
            // Detail.jsx: props.onInline() 직접 호출 1건 + 함수를 받는 prop을 다시 넘긴 onBlur={props.onClose} 1건.
            // props.label.trim()처럼 받은 값의 메서드를 부른 것은 세지 않는다
            List<Diagnostic> detail = skipped(g, "src/Detail.jsx");
            assertThat(detail).hasSize(1);
            assertThat(detail.get(0).message()).contains("수신자를 알 수 없는 호출 2건").contains("직접 호출 1건").contains("다른 호출에 넘김 1건");
            // 생략한 것이 없는 모듈에는 진단이 없다
            assertThat(skipped(g, "src/App.jsx")).isEmpty();
            assertThat(skipped(g, "src/Admin.jsx")).isEmpty();
            assertThat(skipped(g, "src/track.js")).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC23 매개변수 콜백: 직접 호출과, 호출하는 쪽이 함수를 넘기는 매개변수를 옵션·인자로 다시 넘긴 것을 세고 전역·패키지·데이터 값의 호출은 세지 않는다")
    void skippedParameterCallbacks() {
        String common = """
                function ajaxPost(url, data, callback) {
                  $.ajax({ url: url, type: 'POST', data: data, success: callback, error: showError });
                }
                function ajaxGet(url, done) {
                  $.get(url, function (res) {
                    done.call(this, res);
                    console.log(res.length);
                    res.items.forEach(function (item) { item.run(); });
                  });
                }
                function notCallback(url, data) {
                  $.post(url, data);
                }
                function later(fn) {
                  setTimeout(fn.bind(null), 10);
                  fn();
                }
                function showError(xhr) { alert(xhr.status); }
                """;
        String order = """
                function close(id) {
                  ajaxPost('/order/close.do', { id: id }, function (res) { reload(); });
                  notCallback('/order/x.do', { id: id });
                }
                function reload() {}
                """;
        try (TestProject project = TestProject.create().file("js/common.js", common).file("js/order.js", order)) {
            // 두 파일을 함께 싣는 페이지가 있어야 order.js의 ajaxPost 호출이 common.js로 이어진다(F-010-R18)
            G g = new G(project.run(G.page("web/order.jsp", "js/common.js", "js/order.js"), new JsAnalyzer()));

            List<Diagnostic> skipped = skipped(g, "js/common.js");
            assertThat(skipped).hasSize(1);
            // done.call(this, res) 직접 호출 1건 + success: callback 넘김 1건. url·data(함수를 받는다는 근거가 없다), res·item(이름 없는 콜백의
            // 매개변수), console·$·alert(전역)는 세지 않는다
            // later: fn() 직접 호출 1건 + fn.bind(null) 넘김 1건
            assertThat(skipped.get(0).message()).contains("수신자를 알 수 없는 호출 4건").contains("직접 호출 2건")
                    .contains("다른 호출에 넘김 2건").contains("줄 2, 6, 15, 16").contains("js/common.js");
            assertThat(skipped.get(0).location().file()).isEqualTo("js/common.js");
            assertThat(skipped(g, "js/order.js")).isEmpty();
            // 생략은 그대로다: 매개변수 호출에서 나가는 엣지는 없다
            assertThat(g.targets(fn("js/common.js", "ajaxGet"), EdgeKind.CALLS)).isEmpty();
            assertThat(g.targets(fn("js/common.js", "ajaxPost"), EdgeKind.CALLS)).containsExactly(fn("js/common.js", "showError"));
            assertThat(g.targets(mod("js/common.js"), EdgeKind.CALLS)).isEmpty();
        }
    }
}
