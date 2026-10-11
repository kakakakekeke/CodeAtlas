package io.codeatlas.sql.parser;

import io.codeatlas.sql.lexer.Syntax;

/** 루틴 본문의 언어. 본문 파서({@link RoutineParser})가 어느 문법으로 읽을지를 정한다(ADR-0012). */
public enum RoutineLanguage {

    /** Oracle PL/SQL: {@code DECLARE}는 블록의 선언부를 연다. */
    PLSQL,

    /** DB2 SQL PL: 블록 안의 {@code DECLARE}는 선언문 하나다. */
    SQLPL,

    /**
     * PostgreSQL PL/pgSQL. {@code LANGUAGE sql} 루틴의 본문과 SQL 표준 본문({@code RETURN 식}, {@code BEGIN ATOMIC})도 이 언어의 파서로 읽는다
     * (루틴 노드의 {@code language}는 헤더의 {@code LANGUAGE}에서 따로 정한다, F-019-R24).
     */
    PLPGSQL;

    /**
     * 본문의 언어를 정한다: 읽기 방식이 PostgreSQL이면 PL/pgSQL이고, 아니면 지금까지의 규칙(본문 특징 → 방언)으로 가른 결과를 따른다.
     *
     * @param syntax 본문이 든 텍스트의 읽기 방식. null이면 기본이다
     * @param sqlPl PostgreSQL이 아닐 때, 본문 특징과 방언으로 SQL PL이라고 판정했는가
     */
    public static RoutineLanguage of(Syntax syntax, boolean sqlPl) {
        if (syntax == Syntax.POSTGRES) {
            return PLPGSQL;
        }
        return sqlPl ? SQLPL : PLSQL;
    }

    /** 이 언어의 본문 안에 든 SQL 구문을 읽는 방식. */
    public Syntax syntax() {
        return this == PLPGSQL ? Syntax.POSTGRES : Syntax.DEFAULT;
    }
}
