package io.codeatlas.web.syntax;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 0단계 스모크 테스트: tree-sitter 바인딩이 이 환경에서 JS, JSX, TS, TSX를 파싱하는지 확인한다(ADR-0005). */
class TreeSitterJsParserTest {

    private final JsParser parser = new TreeSitterJsParser();

    @Test
    @DisplayName("F-010-AC1 JS를 파싱해 함수 선언과 호출 식을 얻는다")
    void parsesJavaScript() {
        SyntaxTree tree = parser.parse("// 한글 주석\nfunction load(id) { return fetch('/api/' + id); }\n", JsDialect.JAVASCRIPT);

        assertThat(tree.hasError()).isFalse();
        assertThat(tree.root().type()).isEqualTo("program");
        SyntaxNode fn = find(tree.root(), "function_declaration").get(0);
        assertThat(fn.field("name").text()).isEqualTo("load");
        assertThat(fn.startLine()).isEqualTo(2);
        assertThat(fn.startColumn()).isEqualTo(1);
        // 끝 위치는 마지막 문자의 다음 자리(제외)다
        assertThat(fn.endLine()).isEqualTo(2);
        assertThat(fn.endColumn()).isEqualTo(fn.text().length() + 1);
        SyntaxNode call = find(tree.root(), "call_expression").get(0);
        assertThat(call.field("function").text()).isEqualTo("fetch");
        assertThat(call.field("arguments").namedChildren().get(0).text()).isEqualTo("'/api/' + id");
        assertThat(call.parent().type()).isEqualTo("return_statement");
    }

    @Test
    @DisplayName("F-010-AC1 JSX를 파싱한다")
    void parsesJsx() {
        SyntaxTree tree = parser.parse("const App = () => <div><Child name=\"가\" /></div>;", JsDialect.JAVASCRIPT);

        assertThat(tree.hasError()).isFalse();
        SyntaxNode el = find(tree.root(), "jsx_self_closing_element").get(0);
        assertThat(el.field("name").text()).isEqualTo("Child");
        assertThat(el.startColumn()).isEqualTo(24);
    }

    @Test
    @DisplayName("F-010-AC1 TypeScript를 파싱한다 (제네릭, 타입 단언, optional chaining)")
    void parsesTypeScript() {
        String src = """
                interface Order { id: number }
                export async function load<T extends Order>(id: number): Promise<T | undefined> {
                  const res = await client.get<T>(`/orders/${id}`);
                  return (res?.data as T)!;
                }
                """;
        SyntaxTree tree = parser.parse(src, JsDialect.TYPESCRIPT);

        assertThat(tree.hasError()).isFalse();
        assertThat(find(tree.root(), "interface_declaration")).hasSize(1);
        SyntaxNode call = find(tree.root(), "call_expression").get(0);
        // tree-sitter-typescript는 `await f<T>(x)`의 function 필드를 await_expression으로 준다. 분석 쪽에서 벗긴다.
        assertThat(call.field("function").text()).endsWith("client.get");
        assertThat(call.field("type_arguments")).isNotNull();
        assertThat(find(tree.root(), "template_string")).hasSize(1);
    }

    @Test
    @DisplayName("F-010-AC1 TSX를 파싱한다")
    void parsesTsx() {
        String src = """
                type Props = { items: string[] };
                export default function List({ items }: Props) {
                  return <ul>{items.map((i) => <Item key={i} value={i as string} />)}</ul>;
                }
                """;
        SyntaxTree tree = parser.parse(src, JsDialect.TSX);

        assertThat(tree.hasError()).isFalse();
        assertThat(find(tree.root(), "jsx_self_closing_element")).hasSize(1);
        assertThat(find(tree.root(), "as_expression")).hasSize(1);
    }

    @Test
    @DisplayName("F-010-AC2 구문 오류가 있어도 트리를 주고 오류 위치를 알려 준다")
    void toleratesSyntaxErrors() {
        SyntaxTree tree = parser.parse("function ok() { return 1; }\nfunction broken( { \nfunction after() { ok(); }\n", JsDialect.JAVASCRIPT);

        assertThat(tree.hasError()).isTrue();
        assertThat(tree.firstError()).isNotNull();
        assertThat(tree.firstError().startLine()).isGreaterThanOrEqualTo(2);
        assertThat(find(tree.root(), "function_declaration")).isNotEmpty();
    }

    @Test
    @DisplayName("F-010-AC1 파일 확장자로 문법을 고른다")
    void dialectByFileName() {
        assertThat(JsDialect.forFileName("a/b.js")).isEqualTo(JsDialect.JAVASCRIPT);
        assertThat(JsDialect.forFileName("a/b.JSX")).isEqualTo(JsDialect.JAVASCRIPT);
        assertThat(JsDialect.forFileName("a/b.mjs")).isEqualTo(JsDialect.JAVASCRIPT);
        assertThat(JsDialect.forFileName("a/b.ts")).isEqualTo(JsDialect.TYPESCRIPT);
        assertThat(JsDialect.forFileName("a/b.tsx")).isEqualTo(JsDialect.TSX);
        assertThat(JsDialect.forFileName("a/b.jsp")).isEqualTo(JsDialect.JAVASCRIPT);
    }

    static List<SyntaxNode> find(SyntaxNode root, String type) {
        List<SyntaxNode> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static void collect(SyntaxNode node, String type, List<SyntaxNode> out) {
        if (node.type().equals(type)) {
            out.add(node);
        }
        for (SyntaxNode child : node.children()) {
            collect(child, type, out);
        }
    }
}
