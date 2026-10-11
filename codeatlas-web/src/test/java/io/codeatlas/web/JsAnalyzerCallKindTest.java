package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 호출과 "호출이 아닌 함수 참조"를 가르는 규칙(F-010-R13). */
class JsAnalyzerCallKindTest {

    private static List<String> callTypes(G g, String from, String to) {
        return g.graph().edges(from, EdgeKind.CALLS, to).stream().map(e -> e.attrString("callType")).sorted().toList();
    }

    @Test
    @DisplayName("F-010-AC19 f.bind(x)는 실행이 아니므로 callType=ref로, f.call(x)·f.apply(x)는 callType=call로 기록한다")
    void bindIsReference() {
        String src = """
                import React, { Component } from 'react';
                function target() {}
                function other() {}
                function applied() {}
                class Stock extends Component {
                  constructor(props) {
                    super(props);
                    this.handleSave = this.handleSave.bind(this);
                  }
                  handleSave(id) {}
                  render() { return <button onClick={this.handleSave.bind(this, 1)}>x</button>; }
                }
                export function binder(el) {
                  const bound = target.bind(null);
                  el.addEventListener('click', other.bind(null, 1));
                }
                export function caller() {
                  target.call(null);
                  applied.apply(null, []);
                }
                export default Stock;
                """;
        try (TestProject project = TestProject.create().file("src/Stock.jsx", src)) {
            G g = new G(project.run(new JsAnalyzer()));
            String path = "src/Stock.jsx";

            // 샘플 ProductStock.jsx:11과 같은 모양: 생성자가 메서드를 "호출"하는 것이 아니다
            assertThat(callTypes(g, fn(path, "Stock.constructor"), fn(path, "Stock.handleSave"))).containsExactly("ref");
            Edge bind = g.edge(fn(path, "Stock.constructor"), EdgeKind.CALLS, fn(path, "Stock.handleSave"));
            assertThat(bind.location().startLine()).isEqualTo(8);
            assertThat(callTypes(g, fn(path, "Stock.render"), fn(path, "Stock.handleSave"))).containsExactly("ref");
            assertThat(callTypes(g, fn(path, "binder"), fn(path, "target"))).containsExactly("ref");
            assertThat(callTypes(g, fn(path, "binder"), fn(path, "other"))).containsExactly("ref");
            // call·apply는 그 자리에서 실행한다
            assertThat(callTypes(g, fn(path, "caller"), fn(path, "target"))).containsExactly("call");
            assertThat(callTypes(g, fn(path, "caller"), fn(path, "applied"))).containsExactly("call");
        }
    }

    @Test
    @DisplayName("F-010-AC19 bind 결과를 그 자리에서 부르면(f.bind(x)()) 호출도 기록하고, 자기 자신을 bind한 것은 기록하지 않는다")
    void bindThenInvoke() {
        String src = """
                function target() {}
                export function now() { target.bind(null)(); }
                export function self() { return self.bind(null); }
                """;
        try (TestProject project = TestProject.create().file("src/a.js", src)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(callTypes(g, fn("src/a.js", "now"), fn("src/a.js", "target"))).containsExactly("call", "ref");
            assertThat(g.targets(fn("src/a.js", "self"), EdgeKind.CALLS)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC20 typeof·프로퍼티 읽기·export·객체나 배열에 담기는 CALLS를 만들지 않고, 인자·옵션 객체로 넘긴 함수는 callType=ref다")
    void referencesAreNotCalls() {
        String src = """
                function tTypeof() {}
                function tName() {}
                function tExport() {}
                function tShort() {}
                function tArray() {}
                function tAssign() {}
                function tListener() {}
                function tTimeout() {}
                function tThen() {}
                function tMap() {}
                function tPromise() {}
                function tOptPair() {}
                function success() {}
                export { tExport };
                export function probes(el, list, p) {
                  if (typeof tTypeof === 'function') {}
                  const n = tName.name + tName.length;
                  const holder = { tShort };
                  const arr = [tArray];
                  const alias = tAssign;
                  el.addEventListener('click', tListener);
                  setTimeout(tTimeout, 10);
                  p.then(tThen);
                  list.map(tMap);
                  new Promise(tPromise);
                  $.ajax({ url: '/x', error: tOptPair, success });
                  return [n, holder, arr, alias];
                }
                """;
        try (TestProject project = TestProject.create().file("src/refs.js", src)) {
            G g = new G(project.run(new JsAnalyzer()));
            String path = "src/refs.js";
            String probes = fn(path, "probes");

            assertThat(g.targets(probes, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn(path, "tListener"), fn(path, "tTimeout"), fn(path, "tThen"), fn(path, "tMap"), fn(path, "tPromise"),
                    fn(path, "tOptPair"), fn(path, "success"));
            for (Edge e : g.edges(probes, EdgeKind.CALLS)) {
                assertThat(e.attrString("callType")).as(e.to()).isEqualTo("ref");
            }
            // 모듈 최상위의 export { tExport }도 호출이 아니다
            assertThat(g.targets(G.mod(path), EdgeKind.CALLS)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC20 JSX 속성: onClick={f}는 ref, onClick={f()}는 call, onClick={() => f()}는 감싼 함수에서 나가는 call이다")
    void jsxAttributeKinds() {
        String src = """
                function passed() {}
                function invoked() {}
                function wrapped() {}
                export function View() {
                  return (
                    <div>
                      <button onClick={passed}>a</button>
                      <button onClick={invoked()}>b</button>
                      <button onClick={() => wrapped()}>c</button>
                    </div>
                  );
                }
                """;
        try (TestProject project = TestProject.create().file("src/View.jsx", src)) {
            G g = new G(project.run(new JsAnalyzer()));
            String path = "src/View.jsx";
            String view = fn(path, "View");

            assertThat(callTypes(g, view, fn(path, "passed"))).containsExactly("ref");
            assertThat(callTypes(g, view, fn(path, "invoked"))).containsExactly("call");
            assertThat(callTypes(g, view, fn(path, "wrapped"))).containsExactly("call");
            assertThat(g.functions(path)).containsExactlyInAnyOrder("passed", "invoked", "wrapped", "View");
        }
    }
}
