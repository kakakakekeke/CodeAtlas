package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 흔하지 않은 문법과 자기 참조가 있어도 분석이 죽지 않는지 본다. */
class JsAnalyzerRobustnessTest {

    @Test
    @DisplayName("F-010-AC2 다양한 문법(제너레이터, 게터, 태그드 템플릿, 구조 분해, 프래그먼트, 스프레드)에서 예외 없이 분석한다")
    void unusualSyntax() {
        String js = """
                export * as ns from './other';
                export * from './other';
                const { a, b: { c = 1, ...restB }, ...rest } = require('./other');
                let [x, , y = 2, ...zs] = [1, 2, 3];
                function* gen() { yield* gen(); }
                async function* agen() { for await (const v of agen()) { use(v); } }
                const obj = {
                  get value() { return this._v; },
                  set value(v) { this._v = v; },
                  ['computed' + 1]() {},
                  'quoted-key': function () {},
                  async *stream() {},
                  ...rest,
                };
                class A extends (cond ? Base1 : Base2) {
                  static #count = 0;
                  #secret() { return A.#count; }
                  static { A.init(); }
                  static init() {}
                  get size() { return this.#secret(); }
                  ['dyn']() {}
                }
                const tagged = html`<p>${a}</p>`;
                label: for (const k in obj) { if (k) continue label; }
                try { risky(); } catch { recover(); } finally { done(); }
                const F = () => <><Child {...rest} render={() => <b/>} />{[1].map((n) => <i key={n} />)}</>;
                function Child() { return null; }
                var self = self;
                var loopA = loopB, loopB = loopA;
                loopA();
                url = url + '/x';
                fetch(url);
                new (getClass())();
                (async () => { await import(`./dyn/${a}`); })();
                switch (a) { case 1: { const s = 1; break; } default: break; }
                do { x++; } while (x < 3);
                a?.b?.[c]?.(d);
                export default { name: 'thing', methods: { go() { this.stop(); }, stop() {} } };
                """;
        String ts = """
                import type { T1 } from './types';
                import def, { type T2, value } from './other';
                export abstract class Repo<T extends object = {}> implements I<T> {
                  protected constructor(private readonly http: Http, public name?: string) { super(); }
                  abstract find(id: string): Promise<T>;
                  find2 = async <K extends keyof T>(key: K): Promise<T[K]> => { return this.find('x') as any; };
                  @Memo({ ttl: 1 }) static of<U>(this: void, x: U): U { return x; }
                  declare field: string;
                  [key: string]: unknown;
                }
                export function overload(a: string): string;
                export function overload(a: number): number;
                export function overload(a: any): any { return a; }
                export const enum Color { Red, Green = 'g' }
                declare global { interface Window { APP: unknown } }
                declare module 'x' { export function ext(): void; }
                namespace Outer.Inner { export const v = 1; export function f() { return v; } }
                let u = <unknown>value as unknown as string satisfies string;
                const assert: (x: unknown) => asserts x is string = (x) => {};
                type Fn = typeof overload;
                function generic<A, B = A>(a: A, ...bs: B[]): [A, B[]] { return [a, bs]; }
                export = generic;
                """;
        try (TestProject project = TestProject.create()
                .file("src/hairy.jsx", js)
                .file("src/other.js", "export const a = 1; export const b = {}; export const value = 1; export default 1;")
                .file("src/hairy.ts", ts)) {
            G g = new G(project.run(new JsAnalyzer()));

            // 구문 오류 진단은 있을 수 있어도(문법 지원 범위) 분석 중 예외는 없어야 한다
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).allSatisfy(d -> assertThat(d.message()).doesNotContain("오류("));
            assertThat(g.diagnostics(DiagnosticCodes.PARSE_FAILED)).noneMatch(d -> d.message().contains("분석하지 못했다"));
            assertThat(g.functions("src/hairy.jsx")).contains("gen", "agen", "obj.value", "obj.quoted-key", "obj.stream",
                    "A", "A.init", "A.#secret", "A.size", "F", "Child", "default.methods.go", "default.methods.stop");
            assertThat(g.hasEdge(fn("src/hairy.jsx", "default.methods.go"), EdgeKind.CALLS, fn("src/hairy.jsx", "default.methods.stop"))).isTrue();
            assertThat(g.hasEdge(fn("src/hairy.jsx", "F"), EdgeKind.CALLS, fn("src/hairy.jsx", "Child"))).isTrue();
            assertThat(g.hasEdge(fn("src/hairy.jsx", "A.size"), EdgeKind.CALLS, fn("src/hairy.jsx", "A.#secret"))).isTrue();
            assertThat(g.functions("src/hairy.ts")).contains("Repo", "Repo.constructor", "Repo.find2", "Repo.of", "overload",
                    "Outer.Inner.f", "generic");
        }
    }

    @Test
    @DisplayName("F-010-AC2 깊게 중첩된 식과 큰 파일도 제한 시간 안에 분석한다")
    void largeInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("function f").append(i).append("(a) { return f").append((i + 1) % 3000).append("(a) + '/p").append(i)
                    .append("'; }\n");
        }
        sb.append("var deep = ");
        for (int i = 0; i < 2000; i++) {
            sb.append("'a' + ");
        }
        sb.append("'z';\nfetch('/x/' + deep);\n");
        try (TestProject project = TestProject.create().file("big.js", sb.toString())) {
            long start = System.nanoTime();
            G g = new G(project.run(new JsAnalyzer()));
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(g.functions("big.js")).hasSize(3000);
            assertThat(g.hasEdge(fn("big.js", "f0"), EdgeKind.CALLS, fn("big.js", "f1"))).isTrue();
            assertThat(millis).isLessThan(20_000);
        }
    }
}
