package io.codeatlas.web.analysis;

import io.codeatlas.web.syntax.SyntaxNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 구문 트리를 읽는 작은 도우미들. 노드 종류와 필드 이름은 tree-sitter javascript/typescript 문법의 것이다. */
final class Syntax {

    /** 첫 인자로 받은 함수를 그대로 감싸 돌려주는 React 래퍼. 감싼 함수에 대입 대상의 이름을 주고, 호출 결과를 그 함수로 본다. */
    static final Set<String> FUNCTION_WRAPPERS = Set.of("useCallback", "memo", "forwardRef", "observer",
            "React.useCallback", "React.memo", "React.forwardRef");

    private Syntax() {
    }

    /** 괄호와 TypeScript의 타입 단언({@code as}, {@code satisfies}, {@code !}, {@code <T>x})을 벗긴다. */
    static SyntaxNode unwrap(SyntaxNode node) {
        SyntaxNode n = node;
        while (n != null) {
            SyntaxNode inner;
            switch (n.type()) {
                case "parenthesized_expression", "as_expression", "satisfies_expression", "non_null_expression" ->
                        inner = firstNamed(n);
                case "type_assertion" -> inner = lastNamed(n);
                default -> {
                    return n;
                }
            }
            if (inner == null) {
                return n;
            }
            n = inner;
        }
        return null;
    }

    /**
     * 호출 식의 피호출자. tree-sitter-typescript는 {@code await f<T>(x)}의 function 필드를 await_expression으로 주므로
     * 그것도 벗긴다.
     */
    static SyntaxNode callee(SyntaxNode call) {
        SyntaxNode fn = unwrap(call.field("function"));
        while (fn != null && fn.is("await_expression")) {
            SyntaxNode inner = unwrap(firstNamed(fn));
            if (inner == null) {
                break;
            }
            fn = inner;
        }
        return fn;
    }

    /** 호출·new 식의 인자 노드(주석 제외). 태그드 템플릿이면 빈 목록. */
    static List<SyntaxNode> arguments(SyntaxNode callOrNew) {
        SyntaxNode args = callOrNew.field("arguments");
        if (args == null || !args.is("arguments")) {
            return List.of();
        }
        return named(args);
    }

    /** 주석을 뺀 이름 있는 자식. */
    static List<SyntaxNode> named(SyntaxNode node) {
        List<SyntaxNode> out = new ArrayList<>();
        for (SyntaxNode c : node.namedChildren()) {
            if (!c.is("comment")) {
                out.add(c);
            }
        }
        return out;
    }

    static SyntaxNode firstNamed(SyntaxNode node) {
        for (SyntaxNode c : node.namedChildren()) {
            if (!c.is("comment")) {
                return c;
            }
        }
        return null;
    }

    static SyntaxNode lastNamed(SyntaxNode node) {
        List<SyntaxNode> all = named(node);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    static boolean isFunction(SyntaxNode node) {
        return node != null && node.isAny("function_expression", "arrow_function", "generator_function",
                "function_declaration", "generator_function_declaration", "function");
    }

    static boolean isClass(SyntaxNode node) {
        return node != null && node.isAny("class", "class_declaration", "abstract_class_declaration");
    }

    static boolean isIdentifier(SyntaxNode node) {
        return node != null && node.isAny("identifier", "shorthand_property_identifier", "type_identifier");
    }

    /** 문자열 리터럴의 내용(따옴표 제외, 흔한 이스케이프 해제). */
    static String stringContent(SyntaxNode string) {
        StringBuilder sb = new StringBuilder();
        for (SyntaxNode c : string.namedChildren()) {
            if (c.is("string_fragment")) {
                sb.append(c.text());
            } else if (c.is("escape_sequence")) {
                sb.append(unescape(c.text()));
            }
        }
        return sb.toString();
    }

    static String unescape(String escape) {
        if (escape.length() < 2) {
            return escape;
        }
        char c = escape.charAt(1);
        return switch (c) {
            case 'n', 'r', 't', 'b', 'f', 'v', '0' -> "";
            case 'u', 'x' -> {
                try {
                    String hex = escape.substring(2).replace("{", "").replace("}", "");
                    yield new String(Character.toChars(Integer.parseInt(hex, 16)));
                } catch (RuntimeException e) {
                    yield "";
                }
            }
            case '\n', '\r' -> "";
            default -> String.valueOf(c);
        };
    }

    /** 객체 리터럴 키, 클래스 멤버 이름. 계산된 키는 문자열 리터럴일 때만 이름을 준다. 모르면 null. */
    static String propertyName(SyntaxNode key) {
        if (key == null) {
            return null;
        }
        return switch (key.type()) {
            case "property_identifier", "identifier", "private_property_identifier", "number",
                    "shorthand_property_identifier", "shorthand_property_identifier_pattern", "type_identifier" -> key.text();
            case "string" -> stringContent(key);
            case "computed_property_name" -> {
                SyntaxNode inner = unwrap(firstNamed(key));
                yield inner != null && inner.is("string") ? stringContent(inner) : null;
            }
            default -> null;
        };
    }

    /** 멤버 식의 프로퍼티 이름. {@code a.b}와 {@code a['b']}. 모르면 null. */
    static String memberName(SyntaxNode member) {
        if (member.is("member_expression")) {
            SyntaxNode p = member.field("property");
            return p == null ? null : p.text();
        }
        if (member.is("subscript_expression")) {
            SyntaxNode index = unwrap(member.field("index"));
            return index != null && index.is("string") ? stringContent(index) : null;
        }
        return null;
    }

    /**
     * {@code a.b.c} 같은 체인을 이름 목록으로. 뿌리는 식별자나 {@code this}여야 한다. 체인이 아니면 null.
     */
    static List<String> chain(SyntaxNode expr) {
        List<String> out = new ArrayList<>();
        SyntaxNode n = unwrap(expr);
        while (n != null) {
            if (isIdentifier(n) || n.is("this")) {
                out.add(0, n.text());
                return out;
            }
            if (n.isAny("member_expression", "subscript_expression")) {
                String name = memberName(n);
                if (name == null) {
                    return null;
                }
                out.add(0, name);
                n = unwrap(n.field("object"));
            } else {
                return null;
            }
        }
        return null;
    }

    /** 이항 식의 연산자 문자열. */
    static String operator(SyntaxNode binary) {
        SyntaxNode op = binary.field("operator");
        return op == null ? "" : op.type();
    }

    /** 진단과 미해결 원문에 쓸 한 줄 요약. */
    static String brief(SyntaxNode node) {
        return brief(node.text());
    }

    static String brief(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }
}
