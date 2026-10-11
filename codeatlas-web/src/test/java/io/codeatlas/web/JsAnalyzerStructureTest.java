package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.web.analysis.JsProjectAnalysis;
import io.codeatlas.web.syntax.JsParser;
import io.codeatlas.web.syntax.TreeSitterJsParser;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsAnalyzerStructureTest {

    private static final String APP = """
            export function top() { function inner() {} inner(); }
            const arrow = async () => {};
            export const expr = function () {};
            class Service {
              constructor() {}
              async load() {}
              static create() {}
              handler = () => {};
            }
            var Grid = { render: function () {}, refresh() {}, nested: { deep() {} } };
            var Mod = (function ($) {
              function a() {}
              function hidden() {}
              return { a: a, b: function () {} };
            })(jQuery);
            App.init = function () {};
            function Legacy() {}
            Legacy.prototype.run = function () {};
            $(function () { top(); });
            setTimeout(function () {}, 10);
            el.onclick = function () {};
            export default function () {}
            """;

    @Test
    @DisplayName("F-010-AC3 모듈과 함수 노드를 이름 규칙대로 만들고 CONTAINS로 잇는다")
    void modulesAndFunctions() {
        try (TestProject project = TestProject.create().file("src/app.js", APP)) {
            G g = new G(project.run(new JsAnalyzer()));

            Node module = g.node(mod("src/app.js"));
            assertThat(module.kind()).isEqualTo(NodeKind.JS_MODULE);
            assertThat(module.declared()).isTrue();
            assertThat(g.hasEdge("file:src/app.js", EdgeKind.CONTAINS, mod("src/app.js"))).isTrue();

            assertThat(g.functions("src/app.js")).containsExactlyInAnyOrder(
                    "top", "top.inner", "arrow", "expr",
                    "Service", "Service.constructor", "Service.load", "Service.create", "Service.handler",
                    "Grid.render", "Grid.refresh", "Grid.nested.deep",
                    "Mod.a", "Mod.hidden", "Mod.b",
                    "App.init", "Legacy", "Legacy.run", "default");

            assertThat(g.targets(mod("src/app.js"), EdgeKind.CONTAINS)).contains(
                    fn("src/app.js", "top"), fn("src/app.js", "Service"), fn("src/app.js", "Grid.render"),
                    fn("src/app.js", "Mod.a"), fn("src/app.js", "App.init"), fn("src/app.js", "default"))
                    .doesNotContain(fn("src/app.js", "top.inner"), fn("src/app.js", "Service.load"));
            assertThat(g.hasEdge(fn("src/app.js", "top"), EdgeKind.CONTAINS, fn("src/app.js", "top.inner"))).isTrue();
            assertThat(g.hasEdge(fn("src/app.js", "Service"), EdgeKind.CONTAINS, fn("src/app.js", "Service.load"))).isTrue();
        }
    }

    @Test
    @DisplayName("F-010-AC3 함수 노드의 async, exported, kind, 이름, 위치를 기록한다")
    void functionAttributes() {
        try (TestProject project = TestProject.create().file("src/app.js", APP)) {
            G g = new G(project.run(new JsAnalyzer()));

            Node top = g.node(fn("src/app.js", "top"));
            assertThat(top.attrBoolean("exported")).isTrue();
            assertThat(top.attrBoolean("async")).isFalse();
            assertThat(top.attrBoolean("component")).isFalse();
            assertThat(top.attrString("kind")).isEqualTo("function");
            assertThat(top.name()).isEqualTo("top");
            assertThat(top.location().file()).isEqualTo("src/app.js");
            assertThat(top.location().startLine()).isEqualTo(1);

            Node arrow = g.node(fn("src/app.js", "arrow"));
            assertThat(arrow.attrBoolean("async")).isTrue();
            assertThat(arrow.attrBoolean("exported")).isFalse();
            assertThat(arrow.attrString("kind")).isEqualTo("arrow");

            assertThat(g.node(fn("src/app.js", "expr")).attrBoolean("exported")).isTrue();
            assertThat(g.node(fn("src/app.js", "Service")).attrString("kind")).isEqualTo("class");
            Node load = g.node(fn("src/app.js", "Service.load"));
            assertThat(load.attrString("kind")).isEqualTo("method");
            assertThat(load.attrBoolean("async")).isTrue();
            assertThat(load.name()).isEqualTo("load");
            assertThat(load.qualifiedName()).isEqualTo("src/app.js#Service.load");
            assertThat(g.node(fn("src/app.js", "Grid.render")).attrString("kind")).isEqualTo("method");
            assertThat(g.node(fn("src/app.js", "default")).attrBoolean("exported")).isTrue();
            assertThat(g.node(fn("src/app.js", "Mod.a")).attrBoolean("exported")).isFalse();
        }
    }

    @Test
    @DisplayName("F-010-AC3 이름 없는 콜백은 노드를 만들지 않고, 같은 이름이 겹치는 IIFE 안의 함수는 <anonymous>@줄:열로 구분한다")
    void anonymousCallbacks() {
        String src = """
                (function () { function init() {} init(); })();
                (function () { function init() {} init(); })();
                [1, 2].map(function (x) { return x; });
                """;
        try (TestProject project = TestProject.create().file("a.js", src)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.functions("a.js")).containsExactlyInAnyOrder("init", "<anonymous>@2:2.init");
            assertThat(g.hasEdge(mod("a.js"), EdgeKind.CALLS, fn("a.js", "init"))).isTrue();
            assertThat(g.hasEdge(mod("a.js"), EdgeKind.CALLS, fn("a.js", "<anonymous>@2:2.init"))).isTrue();
        }
    }

    @Test
    @DisplayName("F-010-AC3 CommonJS로 내보낸 함수의 이름과 exported")
    void commonJsExports() {
        String src = """
                function helper() {}
                module.exports = { helper, run: function () {}, stop() {} };
                exports.extra = function () {};
                module.exports.more = () => {};
                """;
        try (TestProject project = TestProject.create().file("lib.js", src)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.functions("lib.js")).containsExactlyInAnyOrder("helper", "run", "stop", "extra", "more");
            assertThat(g.node(fn("lib.js", "helper")).attrBoolean("exported")).isTrue();
            assertThat(g.node(fn("lib.js", "run")).attrBoolean("exported")).isTrue();
            assertThat(g.node(fn("lib.js", "extra")).attrBoolean("exported")).isTrue();
            assertThat(g.node(mod("lib.js")).attrString("moduleKind")).isEqualTo("commonjs");
        }
    }

    @Test
    @DisplayName("F-010-AC4 JSX를 반환하는 함수와 React.Component를 상속한 클래스만 component=true다")
    void components() {
        String src = """
                import React, { Component } from 'react';
                export function Hello({ name }) { return <div>{name}</div>; }
                const Arrow = () => <span/>;
                export class Legacy extends React.Component {
                  componentDidMount() {}
                  render() { return <Hello name="a" />; }
                }
                class Pure extends Component { render() { return null; } }
                function helper() { return 1; }
                function lower() { return <b/>; }
                class Plain { run() {} }
                """;
        try (TestProject project = TestProject.create().file("src/c.jsx", src)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).isEmpty();
            assertThat(g.node(fn("src/c.jsx", "Hello")).attrBoolean("component")).isTrue();
            assertThat(g.node(fn("src/c.jsx", "Arrow")).attrBoolean("component")).isTrue();
            assertThat(g.node(fn("src/c.jsx", "Legacy")).attrBoolean("component")).isTrue();
            assertThat(g.node(fn("src/c.jsx", "Pure")).attrBoolean("component")).isTrue();
            assertThat(g.node(fn("src/c.jsx", "helper")).attrBoolean("component")).isFalse();
            assertThat(g.node(fn("src/c.jsx", "lower")).attrBoolean("component")).isFalse();
            assertThat(g.node(fn("src/c.jsx", "Plain")).attrBoolean("component")).isFalse();
            assertThat(g.functions("src/c.jsx")).contains("Legacy", "Legacy.render", "Legacy.componentDidMount");
            assertThat(g.node(fn("src/c.jsx", "Legacy.render")).attrBoolean("component")).isFalse();
        }
    }

    @Test
    @DisplayName("F-010-AC2 구문 오류가 있어도 나머지를 분석하고 PARSE_FAILED(WARN)를 파일당 1건 남긴다")
    void syntaxErrorIsTolerated() {
        String broken = """
                function before() { return 1; }
                var x = = 3;
                function after() { before(); }
                """;
        try (TestProject project = TestProject.create().file("broken.js", broken).file("ok.js", "function fine() {}")) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).hasSize(1);
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED).get(0).severity()).isEqualTo(Severity.WARN);
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED).get(0).location().file()).isEqualTo("broken.js");
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED).get(0).location().startLine()).isEqualTo(2);
            assertThat(g.functions("broken.js")).contains("before", "after");
            assertThat(g.hasEdge(fn("broken.js", "after"), EdgeKind.CALLS, fn("broken.js", "before"))).isTrue();
            assertThat(g.functions("ok.js")).containsExactly("fine");
        }
    }

    @Test
    @DisplayName("F-010-AC2 파일 하나에서 예외가 나도 다른 파일은 분석하고 그 파일에 PARSE_FAILED를 남긴다")
    void fileFailureIsIsolated() {
        JsParser real = new TreeSitterJsParser();
        JsParser failing = (source, dialect) -> {
            if (source.contains("BOOM")) {
                throw new IllegalStateException("boom");
            }
            return real.parse(source, dialect);
        };
        try (TestProject project = TestProject.create().file("bad.js", "// BOOM\nfunction x() {}").file("ok.js", "function fine() {}")) {
            G g = new G(project.run(new JsAnalyzer(() -> failing)));

            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).hasSize(1);
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED).get(0).location().file()).isEqualTo("bad.js");
            assertThat(g.node(mod("bad.js")).declared()).isTrue();
            assertThat(g.functions("bad.js")).isEmpty();
            assertThat(g.functions("ok.js")).containsExactly("fine");
        }
    }

    @Test
    @DisplayName("F-010-AC2 파서를 쓸 수 없는 환경이면 ERROR 진단 1건을 남기고 모듈 노드만 만든다")
    void parserUnavailable() {
        JsParser unavailable = (source, dialect) -> {
            throw new io.codeatlas.web.syntax.ParserUnavailableException("지원하지 않는 OS", null);
        };
        try (TestProject project = TestProject.create().file("a.js", "function x() {}").file("b.ts", "export const y = 1;")) {
            G g = new G(project.run(new JsAnalyzer(() -> unavailable)));

            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).hasSize(1);
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED).get(0).severity()).isEqualTo(Severity.ERROR);
            assertThat(g.node(mod("a.js")).declared()).isTrue();
            assertThat(g.node(mod("b.ts")).declared()).isTrue();
            assertThat(g.hasEdge("file:b.ts", EdgeKind.CONTAINS, mod("b.ts"))).isTrue();
            assertThat(g.ids(NodeKind.JS_FUNCTION)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC17 압축된 스크립트는 모듈 노드만 만들고 내용을 분석하지 않는다")
    void minifiedFilesAreSkipped() {
        String longLine = "function a(){return 1}".repeat(2000);
        try (TestProject project = TestProject.create()
                .file("js/jquery.min.js", "function a(){}")
                .file("js/bundle.js", longLine)
                .file("js/app.js", "function real() {}")) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.node(mod("js/jquery.min.js")).attrString("skipped")).isEqualTo("minified");
            assertThat(g.node(mod("js/bundle.js")).attrString("skipped")).isEqualTo("minified");
            assertThat(g.functions("js/jquery.min.js")).isEmpty();
            assertThat(g.functions("js/bundle.js")).isEmpty();
            assertThat(g.functions("js/app.js")).containsExactly("real");
            assertThat(g.diagnostics(JsProjectAnalysis.FILE_SKIPPED)).hasSize(2);
            assertThat(g.diagnostics(JsProjectAnalysis.FILE_SKIPPED).get(0).severity()).isEqualTo(Severity.INFO);
        }
    }

    @Test
    @DisplayName("F-010 분석기는 ServiceLoader로 등록되어 있고 order는 소비자 표준값 200이다")
    void registeredAsService() {
        Analyzer found = null;
        for (Analyzer a : ServiceLoader.load(Analyzer.class)) {
            if (a instanceof JsAnalyzer) {
                found = a;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.order()).isEqualTo(200);
        assertThat(found.name()).isEqualTo("web");
        assertThat(Confidence.RESOLVED).isNotNull();
    }
}
