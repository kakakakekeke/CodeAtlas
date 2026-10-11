package io.codeatlas.sql.lexer;

/**
 * 한 SQL 텍스트를 읽는 규칙("읽기 방식", ADR-0012). 텍스트 단위로 한 번 정해({@code DialectDetector.syntaxOfFile},
 * {@code syntaxOfEmbedded}) 렉서, 스크립트 분할기, DML·DDL 파서, 루틴 파서에 넘긴다(F-019-R6).
 *
 * <p>옵션의 {@code Dialect}(core)와는 다른 것이다: {@code Dialect}는 사용자가 고르는 대상 방언이고 이것은 읽는 규칙이다.
 * 읽기 방식으로 가르는 것은 방언끼리 겹치는 표기({@code #}, {@code $$})와 그 방언에만 있는 문장뿐이다.
 */
public enum Syntax {

    /** 기본: Oracle·DB2 문법의 합집합. {@code #}과 {@code $}는 이름의 글자다. */
    DEFAULT,

    /** MySQL로 판정한 텍스트: 줄 처음의 {@code #}이 줄 주석이고 문자열 안의 백슬래시가 이스케이프다(F-006-R27, F-006-R36). */
    MYSQL,

    /** PostgreSQL로 판정한 텍스트: 달러 인용, {@code #>} 같은 연산자, PL/pgSQL 문장을 PostgreSQL 규칙으로 읽는다(F-019-R9~). */
    POSTGRES
}
