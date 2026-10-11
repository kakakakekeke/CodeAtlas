package io.codeatlas.web.syntax;

import java.nio.file.Files;
import java.nio.file.Path;

/** 개발용: 구문 트리를 들여쓰기로 찍는다. {@code TreeDump <파일>} */
public final class TreeDump {

    private TreeDump() {
    }

    public static void main(String[] args) throws Exception {
        String src = Files.readString(Path.of(args[0]));
        SyntaxTree tree = new TreeSitterJsParser().parse(src, JsDialect.forFileName(args[0]));
        System.out.println(dump(tree.root()));
    }

    public static String dump(SyntaxNode root) {
        StringBuilder sb = new StringBuilder();
        dump(root, 0, sb);
        return sb.toString();
    }

    private static void dump(SyntaxNode node, int depth, StringBuilder sb) {
        if (!node.isNamed() && !node.isError()) {
            return;
        }
        sb.append("  ".repeat(depth));
        if (node.fieldName() != null) {
            sb.append(node.fieldName()).append(": ");
        }
        sb.append(node.type());
        if (node.namedChildren().isEmpty()) {
            String text = node.text().replace("\n", "\\n");
            sb.append(" '").append(text.length() > 40 ? text.substring(0, 40) + "…" : text).append('\'');
        }
        sb.append('\n');
        for (SyntaxNode child : node.children()) {
            dump(child, depth + 1, sb);
        }
    }
}
