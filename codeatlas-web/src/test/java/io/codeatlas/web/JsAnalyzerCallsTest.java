package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsAnalyzerCallsTest {

    @Test
    @DisplayName("F-010-AC6 같은 모듈, import(named·default·namespace·re-export), obj.method, this.method, 콜백 안의 호출을 CALLS로 잇고 프로젝트 밖 호출은 잇지 않는다")
    void calls() {
        String a = """
                import def, { named, renamed as alias } from './b';
                import * as ns from './b';
                import { viaIndex } from './re';
                import _ from 'lodash';
                function local() {}
                const obj = { m() { this.n(); }, n: function () {} };
                class K {
                  run() { this.step(); K.util(); }
                  step() {}
                  static util() {}
                }
                export function main(param) {
                  local(); named(); alias(); def(); ns.other(); viaIndex(); obj.m();
                  new K().run();
                  const k = new K();
                  k.step();
                  [1].forEach(function () { local(); });
                  _.map([]); console.log(1); $('#x').hide(); param.go(); param();
                  unknownGlobal();
                  local.call(null); local.apply(null, []);
                }
                """;
        try (TestProject project = TestProject.create()
                .file("src/a.js", a)
                .file("src/b.js", "export function named() {}\nexport function renamed() {}\nexport function other() {}\nexport default function def() {}\n")
                .file("src/re.js", "export { viaIndex } from './c';")
                .file("src/c.js", "export const viaIndex = () => {};")) {
            G g = new G(project.run(new JsAnalyzer()));
            String main = fn("src/a.js", "main");

            assertThat(g.targets(main, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn("src/a.js", "local"), fn("src/b.js", "named"), fn("src/b.js", "renamed"), fn("src/b.js", "def"),
                    fn("src/b.js", "other"), fn("src/c.js", "viaIndex"), fn("src/a.js", "obj.m"),
                    fn("src/a.js", "K"), fn("src/a.js", "K.run"), fn("src/a.js", "K.step"));
            assertThat(g.targets(fn("src/a.js", "obj.m"), EdgeKind.CALLS)).containsExactly(fn("src/a.js", "obj.n"));
            assertThat(g.targets(fn("src/a.js", "K.run"), EdgeKind.CALLS))
                    .containsExactlyInAnyOrder(fn("src/a.js", "K.step"), fn("src/a.js", "K.util"));

            Edge direct = g.edge(main, EdgeKind.CALLS, fn("src/a.js", "local"));
            assertThat(direct.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(direct.attrString("callType")).isEqualTo("call");
            assertThat(direct.location().startLine()).isEqualTo(13);
            // 콜백 안의 호출도 main에서 나간다(위치만 다르다)
            assertThat(g.graph().edges(main, EdgeKind.CALLS, fn("src/a.js", "local"))).hasSizeGreaterThanOrEqualTo(2);
            assertThat(g.edge(main, EdgeKind.CALLS, fn("src/a.js", "K")).attrString("callType")).isEqualTo("constructor");

            assertThat(g.ids(NodeKind.UNRESOLVED)).isEmpty();
            assertThat(g.diagnostics(DiagnosticCodes.UNRESOLVED_REFERENCE)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC6 CommonJS require로 가져온 함수와 구조 분해한 함수 호출을 잇는다")
    void commonJsCalls() {
        try (TestProject project = TestProject.create()
                .file("lib/util.js", "function fmt() {}\nfunction parse() {}\nmodule.exports = { fmt, parse };\n")
                .file("lib/single.js", "module.exports = function run() {};\n")
                .file("lib/main.js", """
                        const util = require('./util');
                        const { parse } = require('./util');
                        const run = require('./single');
                        const fs = require('fs');
                        function go() { util.fmt(); parse(); run(); fs.readFileSync('x'); }
                        module.exports = go;
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.targets(fn("lib/main.js", "go"), EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn("lib/util.js", "fmt"), fn("lib/util.js", "parse"), fn("lib/single.js", "run"));
            assertThat(g.targets(mod("lib/main.js"), EdgeKind.IMPORTS))
                    .containsExactlyInAnyOrder(mod("lib/util.js"), mod("lib/single.js"));
            assertThat(g.node(fn("lib/main.js", "go")).attrBoolean("exported")).isTrue();
        }
    }

    @Test
    @DisplayName("F-010-AC7 JSX 요소, 훅 호출, 핸들러 참조를 callType=jsx, call, ref인 CALLS로 잇는다")
    void reactCalls() {
        try (TestProject project = TestProject.create()
                .file("src/api.js", "export const fetchOrders = () => fetch('/api/orders');\n")
                .file("src/hooks/useOrders.js", """
                        import { useState, useCallback } from 'react';
                        import { fetchOrders } from '../api';
                        export function useOrders() {
                          const [orders, setOrders] = useState([]);
                          const load = useCallback(async () => { setOrders(await fetchOrders()); }, []);
                          return { orders, reload: load };
                        }
                        """)
                .file("src/pages/Row.jsx", "export default function Row() { return <tr/>; }\n")
                .file("src/pages/OrderList.jsx", """
                        import React from 'react';
                        import { useOrders } from '../hooks/useOrders';
                        import Row from './Row';
                        import * as ui from './ui';
                        export default function OrderList() {
                          const { orders, reload } = useOrders();
                          const handleSave = () => { reload(); };
                          return <div><Row /><ui.Panel /><button onClick={handleSave}>x</button><span>{orders.length}</span></div>;
                        }
                        """)
                .file("src/pages/ui.jsx", "export const Panel = () => <div/>;\n")
                .file("src/App.jsx", """
                        import { createRoot } from 'react-dom/client';
                        import OrderList from './pages/OrderList';
                        const Lazy = React.lazy(() => import('./pages/Row'));
                        function App() { return <main><OrderList /><Lazy /></main>; }
                        createRoot(document.getElementById('root')).render(<App />);
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String list = fn("src/pages/OrderList.jsx", "OrderList");
            String hook = fn("src/hooks/useOrders.js", "useOrders");

            assertThat(g.edge(list, EdgeKind.CALLS, hook).attrString("callType")).isEqualTo("call");
            assertThat(g.edge(list, EdgeKind.CALLS, fn("src/pages/Row.jsx", "Row")).attrString("callType")).isEqualTo("jsx");
            assertThat(g.edge(list, EdgeKind.CALLS, fn("src/pages/ui.jsx", "Panel")).attrString("callType")).isEqualTo("jsx");
            assertThat(g.edge(list, EdgeKind.CALLS, fn("src/pages/OrderList.jsx", "OrderList.handleSave")).attrString("callType"))
                    .isEqualTo("ref");
            // 훅이 돌려준 객체의 멤버를 구조 분해해 부른 것도 따라간다
            assertThat(g.functions("src/hooks/useOrders.js")).containsExactlyInAnyOrder("useOrders", "useOrders.load");
            assertThat(g.targets(fn("src/pages/OrderList.jsx", "OrderList.handleSave"), EdgeKind.CALLS))
                    .containsExactly(fn("src/hooks/useOrders.js", "useOrders.load"));
            assertThat(g.targets(fn("src/hooks/useOrders.js", "useOrders.load"), EdgeKind.CALLS))
                    .containsExactly(fn("src/api.js", "fetchOrders"));
            // App → OrderList, lazy로 가져온 Row, 모듈 최상위의 <App />
            String app = fn("src/App.jsx", "App");
            assertThat(g.targets(app, EdgeKind.CALLS)).containsExactlyInAnyOrder(list, fn("src/pages/Row.jsx", "Row"));
            assertThat(g.edge(mod("src/App.jsx"), EdgeKind.CALLS, app).attrString("callType")).isEqualTo("jsx");
            assertThat(g.node(app).attrBoolean("component")).isTrue();
            assertThat(g.ids(NodeKind.UNRESOLVED)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC8 프로젝트 모듈에서 import한 이름의 대상 함수를 찾지 못하면 미해결 CALLS를 남긴다")
    void unresolvedCalls() {
        try (TestProject project = TestProject.create()
                .file("src/b.js", "export function real() {}\nexport const store = create((set) => ({}));\nexport const LIMIT = 10;\n")
                .file("src/a.js", """
                        import { gone, real, store, LIMIT } from './b';
                        import x from './missing';
                        import * as ns from './b';
                        export function f() { gone(); x(); real(); ns.nothing(); }
                        export function g() { store(); LIMIT.toFixed(2); ns.LIMIT.toFixed(2); }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String f = fn("src/a.js", "f");

            assertThat(g.targets(f, EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn("src/b.js", "real"), "unresolved:js-call:src/b.js#gone", "unresolved:js-call:src/missing#default",
                    "unresolved:js-call:src/b.js#nothing");
            assertThat(g.edge(f, EdgeKind.CALLS, "unresolved:js-call:src/b.js#gone").confidence()).isEqualTo(Confidence.UNRESOLVED);
            // 내보낸 이름은 있지만 함수인지 알 수 없는 값(라이브러리 호출 결과)을 부른 것도 미해결로 남긴다.
            // 그 값의 멤버를 부르는 것(LIMIT.toFixed)은 프로젝트 함수 호출이 아니므로 기록하지 않는다.
            assertThat(g.targets(fn("src/a.js", "g"), EdgeKind.CALLS)).containsExactly("unresolved:js-call:src/b.js#store");
            // import 1건 + 호출 4건
            assertThat(g.diagnostics(DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(5);
        }
    }

    @Test
    @DisplayName("F-010-AC6 생성자 함수와 prototype 메서드, 네임스페이스에 붙인 함수 호출을 잇는다")
    void legacyObjects() {
        String src = """
                function Grid(el) { this.el = el; this.draw = function () {}; }
                Grid.prototype.load = function () { this.draw(); this.render(); };
                Grid.prototype.render = function () {};
                var App = App || {};
                App.util = {};
                App.util.fmt = function () {};
                App.start = function () {
                  var g = new Grid('#g');
                  g.load();
                  App.util.fmt();
                };
                """;
        try (TestProject project = TestProject.create().file("js/legacy.js", src)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.functions("js/legacy.js")).containsExactlyInAnyOrder(
                    "Grid", "Grid.draw", "Grid.load", "Grid.render", "App.util.fmt", "App.start");
            assertThat(g.targets(fn("js/legacy.js", "App.start"), EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn("js/legacy.js", "Grid"), fn("js/legacy.js", "Grid.load"), fn("js/legacy.js", "App.util.fmt"));
            assertThat(g.targets(fn("js/legacy.js", "Grid.load"), EdgeKind.CALLS)).containsExactlyInAnyOrder(
                    fn("js/legacy.js", "Grid.draw"), fn("js/legacy.js", "Grid.render"));
        }
    }

    @Test
    @DisplayName("F-010-AC18 TypeScript: 타입 선언은 노드를 만들지 않고 함수·호출은 JS와 같게 추출한다")
    void typeScript() {
        String ts = """
                import client from './client';
                export interface Product { id: number }
                export type Q = Partial<Product>;
                export enum Api { Products = '/products', Stock = '/stock' }
                declare function ext(): void;
                function over(a: string): void;
                function over(a: any): void {}
                export async function fetchProducts(q: Q = {}): Promise<Product[]> {
                  const res = await client.get<Product[]>(Api.Products, { params: q });
                  over('x');
                  return (res?.data as Product[])!;
                }
                export class Svc<T> {
                  @Log() async load(id: number): Promise<T> { return (await client.get<T>(`${Api.Products}/${id}`)).data; }
                  private helper = (x: T) => x;
                  run(): void { this.helper(null as any); }
                }
                abstract class Base { abstract m(): void; }
                namespace Util { export function fmt(n: number): string { return String(n); } }
                export const run = () => Util.fmt(1);
                """;
        String tsx = """
                import React from 'react';
                type Props = { items: string[] };
                const Item = ({ value }: { value: string }) => <li>{value}</li>;
                export default function List({ items }: Props) {
                  return <ul>{items.map((i) => <Item key={i} value={i as string} />)}</ul>;
                }
                """;
        try (TestProject project = TestProject.create()
                .file("src/client.ts", "import axios from 'axios';\nconst client = axios.create({ baseURL: '/api' });\nexport default client;\n")
                .file("src/product.ts", ts)
                .file("src/List.tsx", tsx)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).isEmpty();
            assertThat(g.functions("src/product.ts")).containsExactlyInAnyOrder(
                    "over", "fetchProducts", "Svc", "Svc.load", "Svc.helper", "Svc.run", "Base", "Util.fmt", "run");
            assertThat(g.targets(fn("src/product.ts", "fetchProducts"), EdgeKind.CALLS)).containsExactly(fn("src/product.ts", "over"));
            assertThat(g.targets(fn("src/product.ts", "run"), EdgeKind.CALLS)).containsExactly(fn("src/product.ts", "Util.fmt"));
            assertThat(g.targets(fn("src/product.ts", "Svc.run"), EdgeKind.CALLS)).containsExactly(fn("src/product.ts", "Svc.helper"));
            assertThat(g.requestsFrom(fn("src/product.ts", "fetchProducts"))).containsExactly("GET /api/products");
            assertThat(g.requestsFrom(fn("src/product.ts", "Svc.load"))).containsExactly("GET /api/products/{*}");
            assertThat(g.node(fn("src/product.ts", "fetchProducts")).attrBoolean("async")).isTrue();

            assertThat(g.edge(fn("src/List.tsx", "List"), EdgeKind.CALLS, fn("src/List.tsx", "Item")).attrString("callType")).isEqualTo("jsx");
            assertThat(g.node(fn("src/List.tsx", "List")).attrBoolean("component")).isTrue();
            assertThat(g.node(fn("src/List.tsx", "Item")).attrBoolean("component")).isTrue();
            assertThat(g.ids(NodeKind.UNRESOLVED)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC6 생성자에서 this.m = this.m.bind(this)로 묶은 메서드도 this.m() 호출로 잇는다")
    void boundMethods() {
        String src = """
                import React, { Component } from 'react';
                class Stock extends Component {
                  constructor(props) { super(props); this.handleSave = this.handleSave.bind(this); }
                  async handleSave(id) {}
                  render() { return <button onClick={() => this.handleSave(1)} onBlur={this.handleSave}>x</button>; }
                }
                export default Stock;
                """;
        try (TestProject project = TestProject.create().file("src/Stock.jsx", src)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.functions("src/Stock.jsx")).containsExactlyInAnyOrder("Stock", "Stock.constructor", "Stock.handleSave", "Stock.render");
            assertThat(g.graph().edges(fn("src/Stock.jsx", "Stock.render"), EdgeKind.CALLS, fn("src/Stock.jsx", "Stock.handleSave")).stream()
                    .map(e -> e.attrString("callType")).toList()).containsExactlyInAnyOrder("call", "ref");
        }
    }
}
