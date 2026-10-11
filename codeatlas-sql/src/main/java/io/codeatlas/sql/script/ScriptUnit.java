package io.codeatlas.sql.script;

import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.lexer.Token;
import java.util.List;

/**
 * 스크립트에서 잘라 낸 실행 단위 하나.
 *
 * @param kind 종류
 * @param tokens 단위의 토큰(주석 제외, EOF 없음). STATEMENT는 종결자를 뺀 것, ROUTINE·BLOCK은 끝의 {@code ;}까지 포함한다.
 *               토큰의 줄·열·offset은 원문 전체 기준이다
 * @param text 원문에서 잘라 낸 본문(첫 토큰부터 마지막 토큰까지)
 */
public record ScriptUnit(Kind kind, List<Token> tokens, String text) {

    public enum Kind {
        /** 구문 하나(DML, DDL, 줄 명령). */
        STATEMENT,
        /** CREATE PROCEDURE/FUNCTION/PACKAGE [BODY]/TRIGGER/TYPE BODY. */
        ROUTINE,
        /** 익명 블록(BEGIN/DECLARE). */
        BLOCK
    }

    public ScriptUnit {
        tokens = List.copyOf(tokens);
    }

    public Token first() {
        return tokens.get(0);
    }

    public Token last() {
        return tokens.get(tokens.size() - 1);
    }

    public int startLine() {
        return first().line();
    }

    public int startColumn() {
        return first().column();
    }

    public int endLine() {
        return last().endLine();
    }

    public int endColumn() {
        return last().endColumn();
    }

    public Pos pos() {
        return Pos.span(first(), last());
    }

    /** 첫 토큰의 대문자 표기(키워드 판별용). */
    public String keyword() {
        return first().upper();
    }
}
