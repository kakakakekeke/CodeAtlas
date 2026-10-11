package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 프로젝트에서 구문 오류로 잡히던 형태(B14): 식별자 using, 스크립트 안의 HTML 주석, JSX 텍스트의 &amp;. */
class JsAnalyzerParsingTest {

    @Test
    @DisplayName("F-010-AC28 using을 식별자로 쓴 코드(var using; using = function(){})를 구문 오류 없이 분석하고 이름은 using 그대로다")
    void usingAsIdentifier() {
        String source = """
                function position(options) {
                  var using;
                  using = function (props) { inner(props); };
                  if (using) { using(); }
                  apply({ using: using }, options.using);
                }
                function inner(p) {}
                function apply(o) {}
                function using2() { position({}); }
                """;
        try (TestProject project = TestProject.create().file("js/pos.js", source)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).isEmpty();
            assertThat(g.functions("js/pos.js")).containsExactlyInAnyOrder("position", "position.using", "inner", "apply", "using2");
            assertThat(g.node(fn("js/pos.js", "position.using")).name()).isEqualTo("using");
            assertThat(g.hasEdge(fn("js/pos.js", "position.using"), EdgeKind.CALLS, fn("js/pos.js", "inner"))).isTrue();
            assertThat(g.hasEdge(fn("js/pos.js", "position"), EdgeKind.CALLS, fn("js/pos.js", "position.using"))).isTrue();
        }
    }

    @Test
    @DisplayName("F-010-AC29 스크립트 안의 HTML 주석 열기(<!--)와 닫기(-->)가 함수 선언 뒤에 와도 구문 오류 없이 분석한다")
    void htmlCommentsInScript() {
        String source = """
                function first() {
                }

                \t\t<!--
                \t\tvar state = 0;
                function second() { first(); }
                \t\t-->
                function third() { second(); }
                //-->
                """;
        try (TestProject project = TestProject.create().file("js/legacy.js", source)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).isEmpty();
            assertThat(g.functions("js/legacy.js")).containsExactlyInAnyOrder("first", "second", "third");
            assertThat(g.hasEdge(fn("js/legacy.js", "second"), EdgeKind.CALLS, fn("js/legacy.js", "first"))).isTrue();
            assertThat(g.edge(fn("js/legacy.js", "third"), EdgeKind.CALLS, fn("js/legacy.js", "second")).location().startLine())
                    .isEqualTo(8);
        }
    }

    @Test
    @DisplayName("F-010-AC30 JSX 텍스트와 JSX 문자열 속성 안의 &(R&D, a=1&b=2)로 구문 오류를 내지 않고 그 파일의 함수·호출·요청을 분석한다")
    void ampersandInJsxText() {
        String source = """
                import { load } from './api';
                export function History() {
                  const flags = mask & 4;
                  return <div title="R&D">R&D, 인력 & 조직 &amp; 기타<a href="/list?a=1&b=2" onClick={load}>목록</a></div>;
                }
                export function Next() { fetch('/api/next'); return <p>ok && done</p>; }
                """;
        try (TestProject project = TestProject.create().file("src/History.jsx", source)
                .file("src/api.js", "export function load() {}\n")) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED).stream().map(d -> d.location().startLine()).toList())
                    .as("&&가 든 JSX 텍스트는 여전히 구문 오류다(한계). 4번째 줄의 &는 오류가 아니다").doesNotContain(4);
            assertThat(g.functions("src/History.jsx")).contains("History", "Next");
            assertThat(g.node(fn("src/History.jsx", "History")).attrBoolean("component")).isTrue();
            assertThat(g.hasEdge(fn("src/History.jsx", "History"), EdgeKind.CALLS, fn("src/api.js", "load"))).isTrue();
            assertThat(g.requestsFrom(fn("src/History.jsx", "Next"))).containsExactly("GET /api/next");
            assertThat(g.node(mod("src/History.jsx")).attrString("moduleKind")).isEqualTo("esm");
        }
    }
}
