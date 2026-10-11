package io.codeatlas.sql.routine;

import io.codeatlas.sql.parser.Keywords;
import java.util.List;
import java.util.Set;

/** 그래프에 호출로 남기지 않는 내장 패키지·프로시저. 카탈로그에 정의된 이름은 여기 있어도 호출로 본다(판정은 호출하는 쪽이 한다). */
final class Builtins {

    private Builtins() {
    }

    /** 내장 패키지·스키마 이름(첫 조각). */
    private static final Set<String> QUALIFIERS = Set.of(
            "SYS", "STANDARD", "HTP", "HTF", "OWA", "XMLTYPE", "ANYDATA", "SYSIBMADM", "SYSCAT", "SYSSTAT", "SYSTOOLS", "SQLJ",
            "NULLID", "ORDSYS", "MDSYS", "CTXSYS", "WMSYS", "XDB", "LBACSYS", "PLITBLM", "DEBUG_EXTPROC", "SYSIBMINTERNAL");

    private static final String[] PREFIXES = {"OWA_", "CTX_", "SDO_", "WPG_", "HTMLDB_", "WWV_", "ORA_"};

    /** PostgreSQL이 제공하는 스키마: 여기 든 함수는 사용자 루틴이 아니다. */
    private static final Set<String> POSTGRES_SCHEMAS = Set.of("PG_CATALOG", "INFORMATION_SCHEMA");

    /**
     * 호출문 자리({@code PERFORM f(..)}, 트리거의 {@code EXECUTE FUNCTION f(..)})에 흔히 오는 PostgreSQL 내장 함수.
     * {@code PG_}로 시작하는 이름은 목록 없이 내장으로 본다({@link #isPostgresBuiltin}).
     */
    private static final Set<String> POSTGRES_FUNCTIONS = Set.of(
            // 시퀀스, 설정, 알림, 잠금
            "NEXTVAL", "CURRVAL", "SETVAL", "LASTVAL", "SET_CONFIG", "CURRENT_SETTING", "TXID_CURRENT", "LO_IMPORT", "LO_EXPORT", "LO_UNLINK",
            // 시각
            "NOW", "CLOCK_TIMESTAMP", "STATEMENT_TIMESTAMP", "TRANSACTION_TIMESTAMP", "TIMEOFDAY", "AGE", "DATE_TRUNC", "DATE_PART", "EXTRACT",
            "MAKE_DATE", "MAKE_TIME", "MAKE_TIMESTAMP", "MAKE_TIMESTAMPTZ", "MAKE_INTERVAL", "TO_CHAR", "TO_DATE", "TO_TIMESTAMP", "TO_NUMBER",
            // 문자열
            "FORMAT", "CONCAT", "CONCAT_WS", "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "OCTET_LENGTH", "BIT_LENGTH", "LOWER", "UPPER", "INITCAP",
            "TRIM", "LTRIM", "RTRIM", "BTRIM", "LPAD", "RPAD", "SUBSTRING", "SUBSTR", "POSITION", "STRPOS", "OVERLAY", "REPLACE", "TRANSLATE",
            "SPLIT_PART", "REPEAT", "REVERSE", "LEFT", "RIGHT", "STARTS_WITH", "CHR", "ASCII", "MD5", "ENCODE", "DECODE", "TO_HEX",
            "QUOTE_IDENT", "QUOTE_LITERAL", "QUOTE_NULLABLE", "REGEXP_REPLACE", "REGEXP_MATCH", "REGEXP_MATCHES", "REGEXP_SPLIT_TO_ARRAY",
            "REGEXP_SPLIT_TO_TABLE", "STRING_TO_ARRAY", "ARRAY_TO_STRING", "STRING_AGG",
            // 조건, 수
            "COALESCE", "NULLIF", "GREATEST", "LEAST", "NUM_NULLS", "NUM_NONNULLS", "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "TRUNC", "MOD",
            "POWER", "SQRT", "EXP", "LN", "LOG", "SIGN", "RANDOM", "SETSEED", "WIDTH_BUCKET", "ISFINITE",
            // 집계, 창
            "COUNT", "SUM", "AVG", "MIN", "MAX", "BOOL_AND", "BOOL_OR", "EVERY", "ARRAY_AGG", "JSON_AGG", "JSONB_AGG", "JSON_OBJECT_AGG",
            "JSONB_OBJECT_AGG", "ROW_NUMBER", "RANK", "DENSE_RANK", "PERCENT_RANK", "CUME_DIST", "NTILE", "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE",
            "NTH_VALUE",
            // 배열, 집합
            "ARRAY_LENGTH", "ARRAY_UPPER", "ARRAY_LOWER", "ARRAY_NDIMS", "ARRAY_APPEND", "ARRAY_PREPEND", "ARRAY_CAT", "ARRAY_REMOVE",
            "ARRAY_REPLACE", "ARRAY_POSITION", "ARRAY_POSITIONS", "CARDINALITY", "UNNEST", "GENERATE_SERIES", "GENERATE_SUBSCRIPTS",
            // JSON
            "TO_JSON", "TO_JSONB", "ROW_TO_JSON", "ARRAY_TO_JSON", "JSON_BUILD_OBJECT", "JSONB_BUILD_OBJECT", "JSON_BUILD_ARRAY",
            "JSONB_BUILD_ARRAY", "JSON_OBJECT", "JSONB_OBJECT", "JSONB_SET", "JSONB_INSERT", "JSONB_STRIP_NULLS", "JSONB_PRETTY", "JSON_TYPEOF",
            "JSONB_TYPEOF", "JSON_EXTRACT_PATH", "JSONB_EXTRACT_PATH", "JSON_EXTRACT_PATH_TEXT", "JSONB_EXTRACT_PATH_TEXT", "JSON_ARRAY_LENGTH",
            "JSONB_ARRAY_LENGTH", "JSON_ARRAY_ELEMENTS", "JSONB_ARRAY_ELEMENTS", "JSON_ARRAY_ELEMENTS_TEXT", "JSONB_ARRAY_ELEMENTS_TEXT",
            "JSON_EACH", "JSONB_EACH", "JSON_EACH_TEXT", "JSONB_EACH_TEXT", "JSON_OBJECT_KEYS", "JSONB_OBJECT_KEYS", "JSON_POPULATE_RECORD",
            "JSONB_POPULATE_RECORD", "JSON_POPULATE_RECORDSET", "JSONB_POPULATE_RECORDSET", "JSON_TO_RECORD", "JSONB_TO_RECORD",
            "JSON_TO_RECORDSET", "JSONB_TO_RECORDSET", "JSONB_PATH_QUERY", "JSONB_PATH_EXISTS", "JSONB_PATH_MATCH",
            // 전문 검색, 확장, 시스템 정보
            "TO_TSVECTOR", "TO_TSQUERY", "PLAINTO_TSQUERY", "PHRASETO_TSQUERY", "WEBSEARCH_TO_TSQUERY", "TS_RANK", "TS_RANK_CD", "TS_HEADLINE",
            "SETWEIGHT", "GEN_RANDOM_UUID", "UUID_GENERATE_V1", "UUID_GENERATE_V4", "CRYPT", "GEN_SALT", "DIGEST", "HMAC", "DBLINK", "DBLINK_EXEC",
            "DBLINK_CONNECT", "DBLINK_DISCONNECT", "VERSION", "CURRENT_DATABASE", "CURRENT_SCHEMAS", "HAS_TABLE_PRIVILEGE", "HAS_SCHEMA_PRIVILEGE",
            "OBJ_DESCRIPTION", "COL_DESCRIPTION", "TO_REGCLASS", "TO_REGPROC", "TO_REGTYPE", "ROW", "CAST", "EXISTS", "ARRAY",
            // 내장 트리거 함수
            "SUPPRESS_REDUNDANT_UPDATES_TRIGGER", "TSVECTOR_UPDATE_TRIGGER", "TSVECTOR_UPDATE_TRIGGER_COLUMN");

    /** {@code pg_catalog.f(..)}, {@code information_schema.f(..)}처럼 PostgreSQL이 제공하는 스키마로 한정한 호출인가. */
    static boolean isPostgresCatalogSchema(List<String> parts) {
        return parts.size() >= 2 && POSTGRES_SCHEMAS.contains(parts.get(parts.size() - 2));
    }

    /**
     * PostgreSQL 내장 함수인가(F-019-R27). PostgreSQL 읽기 방식의 본문에서만 묻는다. 카탈로그에 같은 이름의 루틴이 정의돼 있으면
     * 호출하는 쪽이 먼저 그것을 고른다.
     *
     * @param parts 호출 이름의 정규화한 조각(스키마 포함)
     */
    static boolean isPostgresBuiltin(List<String> parts) {
        String name = parts.get(parts.size() - 1);
        return isPostgresCatalogSchema(parts) || name.startsWith("PG_") || POSTGRES_FUNCTIONS.contains(name);
    }

    /** @param parts 호출 이름의 정규화한 조각 */
    static boolean isBuiltin(List<String> parts) {
        String first = parts.get(0);
        if (Keywords.isBuiltinCall(first)) {
            return true;
        }
        if (parts.size() == 1) {
            return false;
        }
        if (QUALIFIERS.contains(first)) {
            return true;
        }
        for (String p : PREFIXES) {
            if (first.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
