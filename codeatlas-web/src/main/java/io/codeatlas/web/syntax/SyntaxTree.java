package io.codeatlas.web.syntax;

/**
 * 파싱 결과.
 *
 * @param root 루트 노드({@code program})
 * @param firstError 첫 구문 오류 노드(ERROR 또는 MISSING). 오류가 없으면 null
 */
public record SyntaxTree(SyntaxNode root, SyntaxNode firstError) {

    /** 구문 오류가 하나라도 있었는가. 오류가 있어도 나머지 트리는 쓸 수 있다. */
    public boolean hasError() {
        return firstError != null;
    }
}
