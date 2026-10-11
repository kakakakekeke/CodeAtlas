package io.codeatlas.core.spi;

import java.util.Locale;

/** SQL 방언 옵션({@code --dialect}). */
public enum Dialect {
    ORACLE, DB2,
    /** 지정하지 않음. SQL 분석기가 구문을 보고 판단한다. */
    AUTO;

    /** {@code oracle}, {@code db2}, {@code auto}(대소문자 무시)를 읽는다. 그 밖의 값이면 IllegalArgumentException. */
    public static Dialect parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
