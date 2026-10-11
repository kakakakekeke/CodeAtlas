package io.codeatlas.sql.ast;

import io.codeatlas.sql.lexer.Token;

/** 분석한 텍스트 안에서의 위치(줄·열 1부터, 끝은 마지막 글자 다음). 모르면 {@link #NONE}. */
public record Pos(int line, int column, int endLine, int endColumn) {

    public static final Pos NONE = new Pos(0, 0, 0, 0);

    public static Pos of(Token token) {
        return token == null ? NONE : new Pos(token.line(), token.column(), token.endLine(), token.endColumn());
    }

    public static Pos span(Token first, Token last) {
        if (first == null) {
            return NONE;
        }
        if (last == null) {
            return of(first);
        }
        return new Pos(first.line(), first.column(), last.endLine(), last.endColumn());
    }

    public boolean known() {
        return line > 0;
    }
}
