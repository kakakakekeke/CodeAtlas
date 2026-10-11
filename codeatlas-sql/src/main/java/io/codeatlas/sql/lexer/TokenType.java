package io.codeatlas.sql.lexer;

/** SQL 토큰 종류. 키워드는 따로 두지 않고 {@link #IDENT}의 대문자 표기로 비교한다. */
public enum TokenType {
    /** 따옴표 없는 이름(키워드 포함). */
    IDENT,
    /** 큰따옴표로 감싼 식별자. */
    QUOTED_IDENT,
    /** 문자열 리터럴({@code '..'}, {@code N'..'}, {@code q'[..]'}). */
    STRING,
    NUMBER,
    /** 바인드 자리({@code ?}, {@code :name}, {@code :1}, MyBatis {@code #{..}}). */
    BIND,
    /** 연산자와 구두점. */
    SYMBOL,
    /** 주석과 힌트. {@link Lexer#tokenizeWithComments}에서만 나온다. */
    COMMENT,
    EOF
}
