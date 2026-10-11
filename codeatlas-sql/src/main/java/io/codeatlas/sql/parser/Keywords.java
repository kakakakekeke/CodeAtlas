package io.codeatlas.sql.parser;

import java.util.Set;

/** 파서가 쓰는 키워드 집합. 모두 대문자다. */
public final class Keywords {

    private Keywords() {
    }

    /** 식의 첫 토큰이 될 수 없는 키워드(절 경계). 점이 뒤따르면 이름으로 본다. */
    public static final Set<String> EXPR_STOP = Set.of(
            "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "UNION", "MINUS", "INTERSECT", "EXCEPT",
            "AND", "OR", "THEN", "ELSE", "END", "WHEN", "ON", "JOIN", "INNER", "OUTER", "CROSS", "NATURAL",
            "INTO", "VALUES", "SET", "AS", "IS", "IN", "LIKE", "BETWEEN", "NOT", "FETCH", "OFFSET", "FOR",
            "CONNECT", "START", "WITH", "USING", "RETURNING", "LIMIT", "ASC", "DESC",
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "BULK", "ESCAPE", "WINDOW", "QUALIFY",
            "LEFT", "RIGHT", "FULL", "LOOP", "BY");

    /** {@link #EXPR_STOP}에 있지만 괄호가 뒤따르면 함수 이름인 것. */
    public static final Set<String> FUNCTION_KEYWORDS = Set.of("LEFT", "RIGHT", "INSERT");

    /**
     * {@link #EXPR_STOP}에 있지만 예약어가 아니어서 테이블 이름으로는 받는 절 키워드(F-006-R17).
     * {@code LIMIT}, {@code OFFSET}은 숫자·바인드가 뒤따르면 절이다.
     */
    public static final Set<String> SOFT_CLAUSE_KEYWORDS = Set.of("LIMIT", "OFFSET", "WINDOW", "QUALIFY", "ESCAPE", "BULK", "LOOP");

    /**
     * 별칭 자리에서 절의 시작일 수 있는 비예약 키워드({@link #EXPR_STOP}에 더해). 이 이름들은 테이블 이름 자리에서는 식별자이고,
     * 별칭 자리에서는 뒤 토큰을 보고 절인지 가린다({@code SqlParser}의 별칭 판정, F-006-R17).
     */
    public static final Set<String> ALIAS_STOP_EXTRA = Set.of(
            "PIVOT", "UNPIVOT", "MODEL", "PARTITION", "SUBPARTITION", "SAMPLE", "LOG", "WAIT", "NOWAIT", "SKIP",
            "APPLY", "LATERAL", "DEFAULT", "OVERRIDING", "MATCHED", "RETURN", "LOCK", "VERSIONS", "OPTIMIZE",
            "QUERYNO", "INCLUDE", "MATCH_RECOGNIZE", "DO", "ATOMIC", "SIBLINGS", "ROWS", "ONLY", "KEEP", "CALL", "DELETE");

    /** 함수 인자 안에 올 수 없어 닫는 괄호가 빠진 것으로 보는 키워드. */
    public static final Set<String> ARG_BREAK = Set.of(
            "WHERE", "GROUP", "HAVING", "UNION", "MINUS", "INTERSECT", "EXCEPT", "JOIN",
            "THEN", "ELSE", "END", "WHEN", "INTO", "VALUES", "SET");

    /** 인자 안에서 FROM, FOR를 쓰는 함수. */
    public static final Set<String> FROM_FUNCTIONS = Set.of("SUBSTRING", "OVERLAY", "POSITION", "EXTRACT", "TRIM", "XMLTABLE", "TRIM_ARRAY");

    /** 인자 안에 식 사이에 끼는 키워드. */
    public static final Set<String> ARG_KEYWORDS = Set.of(
            "USING", "SEPARATOR", "RETURNING", "PASSING", "COLUMNS", "CONTENT", "DOCUMENT", "ON", "PLACING");

    /** DB2 기간 단위({@code CURRENT DATE - 7 DAYS}). */
    public static final Set<String> DURATION_UNITS = Set.of(
            "YEAR", "YEARS", "MONTH", "MONTHS", "DAY", "DAYS", "HOUR", "HOURS", "MINUTE", "MINUTES",
            "SECOND", "SECONDS", "MICROSECOND", "MICROSECONDS");

    /** DB2 특수 레지스터({@code CURRENT xxx}). */
    public static final Set<String> CURRENT_REGISTERS = Set.of(
            "DATE", "TIME", "TIMESTAMP", "SCHEMA", "USER", "SERVER", "TIMEZONE", "SQLID", "PATH", "DEGREE",
            "ISOLATION", "LOCK", "CLIENT_ACCTNG", "CLIENT_APPLNAME", "CLIENT_USERID", "CLIENT_WRKSTNNAME", "MEMBER");

    /** 테이블도 컬럼도 아닌 이름(의사 컬럼, 인자 없는 함수, 리터럴 키워드). */
    public static final Set<String> PSEUDO_COLUMNS = Set.of(
            "ROWNUM", "ROWID", "LEVEL", "SYSDATE", "SYSTIMESTAMP", "USER", "UID", "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "CURRENT_TIME", "CURRENT_USER", "CURRENT_SCHEMA", "LOCALTIMESTAMP", "SESSIONTIMEZONE", "DBTIMEZONE",
            "CONNECT_BY_ISLEAF", "CONNECT_BY_ISCYCLE", "SQLCODE", "SQLERRM", "SQLSTATE", "NULL", "TRUE", "FALSE",
            "DEFAULT", "ISO", "USA", "EUR", "JIS", "SESSION_USER", "SYSTEM_USER", "ORA_ROWSCN", "OBJECT_VALUE",
            "COLUMN_VALUE", "SQL", "INSERTING", "UPDATING", "DELETING", "SYSDUMMY1", "DUMMY");

    /**
     * 선언된 테이블의 DDL에 없는 컬럼으로 추측하지 않는 이름(F-006-R35): 절·연산자·구문 키워드다({@link #EXPR_STOP}과 함께 본다).
     * 해석하지 못한 대상 외 방언 표기({@code INTERVAL -6 MONTH}, {@code INSERT IGNORE})의 키워드가 컬럼이 되지 않게 한다.
     * {@code KEY}, {@code VALUE}, {@code TYPE}, {@code NAME}, {@code STATUS}처럼 컬럼 이름으로 흔한 낱말은 넣지 않는다.
     */
    public static final Set<String> NEVER_GUESSED_COLUMNS = Set.of(
            "INTERVAL", "IGNORE", "REPLACE", "DISTINCT", "ALL", "ANY", "SOME", "EXISTS", "CASE", "TABLE", "INDEX", "PRIMARY", "FOREIGN",
            "REFERENCES", "CONSTRAINT", "UNIQUE", "CHECK", "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "DUPLICATE", "FORCE",
            "STRAIGHT_JOIN", "REGEXP", "RLIKE", "ILIKE", "SIMILAR", "BINARY", "DIV", "XOR", "TOP", "NOLOCK", "OVER", "PARTITION",
            "PRECEDING", "FOLLOWING", "UNBOUNDED", "CURRENT", "PRIOR", "NOCYCLE", "SIBLINGS", "LATERAL", "APPLY", "PIVOT", "UNPIVOT",
            "MATCHED", "NULLS", "TIES", "SEPARATOR", "UNSIGNED", "SIGNED", "ZEROFILL", "COLLATE", "CHARSET", "AUTO_INCREMENT", "ENGINE",
            "IF", "ELSEIF", "WHILE", "REPEAT", "DECLARE", "BEGIN", "PROCEDURE", "FUNCTION", "TRIGGER", "RETURNS", "EXEC", "EXECUTE",
            "CALL", "SHOW", "DESCRIBE", "EXPLAIN", "UNLOCK", "DELAYED", "LOW_PRIORITY", "HIGH_PRIORITY");

    /** 진단 없이 건너뛰는 구문의 첫 키워드. */
    public static final Set<String> SILENT_STATEMENTS = Set.of(
            "COMMIT", "ROLLBACK", "SAVEPOINT", "SET", "GRANT", "REVOKE", "DROP", "CONNECT", "DISCONNECT", "TERMINATE",
            "PROMPT", "SPOOL", "WHENEVER", "EXIT", "QUIT", "DEFINE", "UNDEFINE", "SHOW", "REM", "REMARK", "ECHO",
            "ANALYZE", "PURGE", "RENAME", "FLASHBACK", "AUDIT", "NOAUDIT", "COLUMN", "ACCEPT", "PAUSE", "TTITLE",
            "BTITLE", "HOST", "VARIABLE", "VAR", "PRINT", "RUNSTATS", "REORG", "REFRESH", "LABEL", "UPDATE_STATISTICS",
            "USE", "ALTER", "RELEASE", "QUIESCE", "UNQUIESCE", "ACTIVATE", "DEACTIVATE", "BIND", "REBIND", "FLUSH",
            "CLEAR", "BREAK", "COMPUTE", "TIMING", "PASSWORD", "STARTUP", "SHUTDOWN", "ARCHIVE", "RECOVER", "DESCRIBE", "DESC");

    /** 테이블 이름 자리에 올 수 없는 예약어인가({@link #EXPR_STOP}에서 {@link #SOFT_CLAUSE_KEYWORDS}를 뺀 것). */
    public static boolean isReservedTableName(String upper) {
        return EXPR_STOP.contains(upper) && !SOFT_CLAUSE_KEYWORDS.contains(upper);
    }

    /** 내장 패키지 호출처럼 그래프에 남길 가치가 없는 호출인가. */
    public static boolean isBuiltinCall(String firstPartUpper) {
        return firstPartUpper.startsWith("DBMS_") || firstPartUpper.startsWith("UTL_")
                || firstPartUpper.equals("RAISE_APPLICATION_ERROR") || firstPartUpper.startsWith("APEX_")
                || firstPartUpper.equals("SYSPROC") || firstPartUpper.equals("SYSIBM") || firstPartUpper.equals("SYSFUN");
    }
}
