package io.codeatlas.web.syntax;

import java.util.ArrayList;
import java.util.List;

/** {@link SyntaxNode}의 불변 구현. 파서가 준 트리를 한 번 복사해 두므로 네이티브 메모리의 수명과 무관하다. */
final class TreeNode implements SyntaxNode {

    private final String source;
    private final String type;
    private final String fieldName;
    private final boolean named;
    private final boolean error;
    private final boolean missing;
    private final int startOffset;
    private final int endOffset;
    private final int startLine;
    private final int startColumn;
    private final int endLine;
    private final int endColumn;
    private final TreeNode parent;
    private List<SyntaxNode> children = List.of();
    private List<SyntaxNode> namedChildren;

    TreeNode(String source, String type, String fieldName, boolean named, boolean error, boolean missing,
            int startOffset, int endOffset, int startLine, int startColumn, int endLine, int endColumn, TreeNode parent) {
        this.source = source;
        this.type = type;
        this.fieldName = fieldName;
        this.named = named;
        this.error = error;
        this.missing = missing;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.startLine = startLine;
        this.startColumn = startColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
        this.parent = parent;
    }

    void addChild(TreeNode child) {
        if (children.isEmpty()) {
            children = new ArrayList<>(4);
        }
        children.add(child);
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public boolean isNamed() {
        return named;
    }

    @Override
    public boolean isError() {
        return error;
    }

    @Override
    public boolean isMissing() {
        return missing;
    }

    @Override
    public String text() {
        int s = Math.max(0, Math.min(startOffset, source.length()));
        int e = Math.max(s, Math.min(endOffset, source.length()));
        return source.substring(s, e);
    }

    @Override
    public int startLine() {
        return startLine;
    }

    @Override
    public int startColumn() {
        return startColumn;
    }

    @Override
    public int endLine() {
        return endLine;
    }

    @Override
    public int endColumn() {
        return endColumn;
    }

    @Override
    public int startOffset() {
        return startOffset;
    }

    @Override
    public int endOffset() {
        return endOffset;
    }

    @Override
    public SyntaxNode parent() {
        return parent;
    }

    @Override
    public String fieldName() {
        return fieldName;
    }

    @Override
    public List<SyntaxNode> children() {
        return children;
    }

    @Override
    public List<SyntaxNode> namedChildren() {
        if (namedChildren == null) {
            List<SyntaxNode> out = new ArrayList<>(children.size());
            for (SyntaxNode c : children) {
                if (c.isNamed()) {
                    out.add(c);
                }
            }
            namedChildren = out;
        }
        return namedChildren;
    }

    @Override
    public SyntaxNode field(String name) {
        for (SyntaxNode c : children) {
            if (name.equals(c.fieldName())) {
                return c;
            }
        }
        return null;
    }

    @Override
    public List<SyntaxNode> fields(String name) {
        List<SyntaxNode> out = new ArrayList<>(2);
        for (SyntaxNode c : children) {
            if (name.equals(c.fieldName())) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return type + "@" + startLine + ":" + startColumn;
    }
}
