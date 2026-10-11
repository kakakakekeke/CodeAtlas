package io.codeatlas.core.spi;

import java.util.Locale;

/** SQL 방언 옵션({@code --dialect}). */
public enum Dialect {
    ORACLE("oracle"), DB2("db2"),
    /** PostgreSQL(F-019). */
    POSTGRES("postgres"),
    /** 지정하지 않음. SQL 분석기가 구문을 보고 판단한다. */
    AUTO(null);

    private final String canonicalName;

    Dialect(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    /**
     * 대상 방언의 표준 이름: {@code oracle}, {@code db2}, {@code postgres}. 방언별 파일 여러 벌에서 한 벌을 고를 때 쓰는 이름이다.
     * {@link #AUTO}는 방언이 아니므로 null이다.
     */
    public String canonicalName() {
        return canonicalName;
    }

    /**
     * 표준 이름이 가리키는 대상 방언({@link #canonicalName()}의 반대). 대상 방언의 표준 이름이 아니면 null이다: null, 대상 외 방언({@code mysql}),
     * 별칭({@code postgresql}), 대문자 표기. 별칭과 {@code auto}까지 읽는 것은 {@link #parse}다.
     */
    public static Dialect ofCanonicalName(String name) {
        if (name == null) {
            return null;
        }
        for (Dialect dialect : values()) {
            if (name.equals(dialect.canonicalName)) {
                return dialect;
            }
        }
        return null;
    }

    /**
     * {@code oracle}, {@code db2}, {@code postgres}(별칭 {@code postgresql}, {@code pgsql}), {@code auto}(대소문자 무시)를 읽는다.
     * 그 밖의 값이면 IllegalArgumentException.
     */
    public static Dialect parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String name = value.trim().toUpperCase(Locale.ROOT);
        if (name.equals("POSTGRESQL") || name.equals("PGSQL")) {
            return POSTGRES;
        }
        return valueOf(name);
    }
}
