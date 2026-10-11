package io.codeatlas.web.syntax;

import java.util.List;

/**
 * 구문 트리 노드. 분석 로직은 이 인터페이스만 보고 특정 파서 바인딩(tree-sitter)을 모른다.
 *
 * <p>노드 종류({@link #type()})와 필드 이름은 tree-sitter의 javascript/typescript 문법이 쓰는 이름을 따른다
 * (예: {@code call_expression}의 {@code function}, {@code arguments}). 줄과 열은 1부터 세고, 끝 위치는 마지막 문자의
 * 다음 자리(제외)다.
 */
public interface SyntaxNode {

    /** 노드 종류. 이름 없는 토큰은 토큰 문자열 그대로다(예: {@code "("}, {@code "async"}). */
    String type();

    /** 문법에서 이름이 붙은 노드인가(토큰이 아닌가). */
    boolean isNamed();

    /** 파서가 해석하지 못한 구간(ERROR 노드)인가. */
    boolean isError();

    /** 파서가 복구하면서 끼워 넣은, 원문에 없는 노드인가. */
    boolean isMissing();

    /** 이 노드의 원문. */
    String text();

    int startLine();

    int startColumn();

    int endLine();

    /** 끝 열. 마지막 문자 다음 자리다(제외). */
    int endColumn();

    /** 원문에서의 시작 문자 오프셋(0부터). */
    int startOffset();

    /** 원문에서의 끝 문자 오프셋(제외). */
    int endOffset();

    /** 부모. 루트면 null. */
    SyntaxNode parent();

    /** 부모 안에서 이 노드가 맡은 필드 이름. 없으면 null. */
    String fieldName();

    /** 토큰을 포함한 모든 자식. */
    List<SyntaxNode> children();

    /** 이름이 붙은 자식만(주석 포함). */
    List<SyntaxNode> namedChildren();

    /** 그 필드의 첫 자식. 없으면 null. */
    SyntaxNode field(String name);

    /** 그 필드의 자식 전부. */
    List<SyntaxNode> fields(String name);

    /** 종류가 같은 첫 자식. 없으면 null. */
    default SyntaxNode child(String type) {
        for (SyntaxNode c : children()) {
            if (c.type().equals(type)) {
                return c;
            }
        }
        return null;
    }

    /** 그 종류의 자식(토큰 포함)이 있는가. 예: {@code hasChild("async")}. */
    default boolean hasChild(String type) {
        return child(type) != null;
    }

    default boolean is(String type) {
        return type().equals(type);
    }

    default boolean isAny(String... types) {
        for (String t : types) {
            if (type().equals(t)) {
                return true;
            }
        }
        return false;
    }
}
