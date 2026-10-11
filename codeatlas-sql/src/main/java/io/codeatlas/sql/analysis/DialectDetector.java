package io.codeatlas.sql.analysis;

import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 방언 판정: 옵션 → 내장 소스 attrs → 파일 경로 → 구문 특징. 판정하지 못하면 null. */
public final class DialectDetector {

    private static final Set<String> ORACLE_WORDS = Set.of(
            "NVL", "NVL2", "DECODE", "SYSDATE", "SYSTIMESTAMP", "ROWNUM", "DUAL", "VARCHAR2", "NVARCHAR2", "NEXTVAL",
            "CURRVAL", "MINUS", "TO_NUMBER", "ROWID", "PLS_INTEGER", "DBMS_OUTPUT", "RAISE_APPLICATION_ERROR",
            "SQLERRM", "ROWTYPE", "NOCACHE", "NOCYCLE", "LISTAGG", "ADD_MONTHS", "REGEXP_LIKE", "SYNONYM", "TABLESPACE");

    private static final Set<String> DB2_WORDS = Set.of(
            "SYSIBM", "SYSDUMMY1", "SYSPROC", "SYSCAT", "ATOMIC", "DB2SQL", "VARGRAPHIC", "DBCLOB", "CCSID",
            "BUFFERPOOL", "RUNSTATS", "DECFLOAT", "SQLSTATE", "RESIGNAL", "ITERATE", "ELSEIF");

    /** MySQL에만 있는 표기: 백틱으로 감싼 이름, {@code AUTO_INCREMENT}, 테이블 옵션 {@code ENGINE=}. */
    private static final java.util.regex.Pattern MYSQL_FEATURES = java.util.regex.Pattern.compile(
            "`[A-Za-z_][A-Za-z0-9_$]*`|\\bAUTO_INCREMENT\\b|\\bENGINE\\s*=\\s*[A-Za-z]", java.util.regex.Pattern.CASE_INSENSITIVE);

    private DialectDetector() {
    }

    /** 옵션에 방언이 지정돼 있으면 그것을, 아니면 null. */
    public static Dialect fromOption(Dialect option) {
        return option == null || option == Dialect.AUTO ? null : option;
    }

    /** {@code oracle}, {@code db2}(대소문자 무시)를 읽는다. 그 밖의 값이면 null. */
    public static Dialect fromName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("oracle") || n.equals("ora")) {
            return Dialect.ORACLE;
        }
        if (n.startsWith("db2")) {
            return Dialect.DB2;
        }
        return null;
    }

    /** 경로 힌트: {@code /oracle/}, {@code /db2/} 디렉터리, {@code .db2}·{@code .pks}·{@code .pkb} 같은 확장자. */
    public static Dialect fromPath(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        String p = "/" + relativePath.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (p.contains("/db2/") || p.endsWith(".db2") || p.endsWith(".spsql")) {
            return Dialect.DB2;
        }
        if (p.contains("/oracle/") || p.contains("/ora/") || p.endsWith(".pks") || p.endsWith(".pkb")
                || p.endsWith(".pls") || p.endsWith(".plb")) {
            return Dialect.ORACLE;
        }
        return null;
    }

    /**
     * 본문에 MySQL에만 있는 표기(백틱 이름, {@code AUTO_INCREMENT}, {@code ENGINE=})가 있는가. 경로와 옵션으로 방언을 모를 때
     * MySQL 관용 표기(줄 처음 {@code #} 주석)를 받을지, 구문의 방언을 Oracle·DB2로 적지 않을지 정하는 데 쓴다(F-006-R27, F-006-R28).
     */
    public static boolean mysqlFeatures(String text) {
        return text != null && MYSQL_FEATURES.matcher(text).find();
    }

    private static final java.util.regex.Pattern HSQL_FEATURES = java.util.regex.Pattern.compile(
            "\\bCREATE\\s+(MEMORY|CACHED)\\s+TABLE\\b|^\\s*SET\\s+DATABASE\\b", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE);
    private static final java.util.regex.Pattern ORACLE_FEATURES = java.util.regex.Pattern.compile(
            "\\bN?VARCHAR2\\b|\\bNUMBER\\s*\\(", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DB2_IDENTITY = java.util.regex.Pattern.compile(
            "\\bGENERATED\\s+(ALWAYS|BY\\s+DEFAULT)\\s+AS\\s+IDENTITY\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DB2_TABLESPACE = java.util.regex.Pattern.compile(
            "\\)\\s*IN\\s+\"?[A-Za-z_][A-Za-z0-9_]*\"?", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 파일 본문의 특징으로 읽은 방언 이름(표준 이름). 경로에 방언 이름이 없는 스크립트의 방언을 정할 때 쓴다(F-007-R24):
     * {@code CREATE MEMORY|CACHED TABLE}·줄 처음의 {@code SET DATABASE}(HSQL), 백틱 이름·{@code ENGINE=}·{@code AUTO_INCREMENT}(MySQL),
     * {@code VARCHAR2}·{@code NUMBER(}(Oracle), {@code GENERATED ALWAYS AS IDENTITY}와 {@code ) IN 테이블스페이스}가 함께 있음(DB2).
     * 확실한 표기가 없으면 null이다.
     */
    public static String contentDialect(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (HSQL_FEATURES.matcher(text).find()) {
            return "hsql";
        }
        if (mysqlFeatures(text)) {
            return "mysql";
        }
        if (ORACLE_FEATURES.matcher(text).find()) {
            return "oracle";
        }
        if (DB2_IDENTITY.matcher(text).find() && DB2_TABLESPACE.matcher(text).find()) {
            return "db2";
        }
        return null;
    }

    /** MySQL의 {@code INTERVAL n 단위}(따옴표 없는 수)에 오는 단위. Oracle은 {@code INTERVAL '6' MONTH}처럼 문자열을 쓴다. */
    private static final Set<String> INTERVAL_UNITS = Set.of(
            "MICROSECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR");

    /** 구문 특징으로 판정한다. 양쪽 점수가 같거나 둘 다 0이면 null. MySQL 표기가 있으면 null이다({@link Scorer}). */
    public static Dialect fromTokens(List<Token> tokens) {
        Scorer scorer = new Scorer();
        for (Token t : tokens) {
            scorer.accept(t);
        }
        return scorer.result();
    }

    /**
     * 토큰을 하나씩 받아 방언 점수를 매긴다. 파일을 토큰 목록으로 통째로 만들지 않고 흘려 읽으면서 판정할 때 쓴다.
     * 토큰을 다 준 뒤 {@link #result()}를 한 번 부른다.
     *
     * <p>Oracle·DB2에 없는 MySQL 표기가 하나라도 있으면 점수와 상관없이 판정하지 않는다(F-006-R42): 괄호가 붙은 {@code SYSDATE()},
     * 따옴표 없는 수를 쓴 {@code INTERVAL [-]n 단위}, {@code ADDDATE}. {@code SYSDATE} 한 단어로 Oracle이라고 추측하지 않기 위해서다.
     */
    public static final class Scorer {
        private int oracle;
        private int db2;
        private boolean mysql;
        private Token first;
        private Token second;
        /** 바로 앞에 점수를 매긴 토큰 셋(가까운 것부터): {@code INTERVAL [-] n} 뒤의 단위를 알아보는 데 쓴다. */
        private Token prev1;
        private Token prev2;
        private Token prev3;

        public void accept(Token t) {
            if (first == null) {
                first = t;
            } else if (second == null) {
                second = t;
            } else {
                score(first, second, t);
                first = second;
                second = t;
            }
        }

        /** 양쪽 점수가 같거나 둘 다 0이면 null. */
        public Dialect result() {
            if (first != null) {
                score(first, second, null);
            }
            if (second != null) {
                score(second, null, null);
            }
            first = null;
            second = null;
            if (mysql) {
                return null;
            }
            if (oracle > db2) {
                return Dialect.ORACLE;
            }
            if (db2 > oracle) {
                return Dialect.DB2;
            }
            return null;
        }

        private void score(Token t, Token next, Token next2) {
            mysql |= mysqlIdiom(t, next);
            prev3 = prev2;
            prev2 = prev1;
            prev1 = t;
            weigh(t, next, next2);
        }

        /** Oracle·DB2에 없는 MySQL 표기의 자리인가: {@code SYSDATE (}, {@code ADDDATE (}, {@code INTERVAL [+|-] 수} 바로 뒤의 단위. */
        private boolean mysqlIdiom(Token t, Token next) {
            if (t.type() != TokenType.IDENT) {
                return false;
            }
            String u = t.upper();
            if (u.equals("SYSDATE") || u.equals("ADDDATE")) {
                return next != null && next.isSymbol("(");
            }
            if (!INTERVAL_UNITS.contains(u) || prev1 == null || prev1.type() != TokenType.NUMBER || prev2 == null) {
                return false;
            }
            return prev2.is("INTERVAL") || ((prev2.isSymbol("-") || prev2.isSymbol("+")) && prev3 != null && prev3.is("INTERVAL"));
        }

        private void weigh(Token t, Token next, Token next2) {
            if (t.type() == TokenType.SYMBOL) {
                if (t.isSymbol("(+)") || t.isSymbol(":=")) {
                    oracle += 2;
                }
                return;
            }
            if (t.type() == TokenType.COMMENT) {
                if (t.text().toUpperCase(Locale.ROOT).startsWith("--#SET TERMINATOR")) {
                    db2 += 3;
                }
                return;
            }
            if (t.type() != TokenType.IDENT) {
                return;
            }
            String u = t.upper();
            if (ORACLE_WORDS.contains(u)) {
                oracle++;
            } else if (DB2_WORDS.contains(u)) {
                db2++;
            } else if (next != null) {
                if (u.equals("FETCH") && next.isAny("FIRST", "NEXT")) {
                    db2++;
                } else if (u.equals("WITH") && next.isAny("UR", "CS", "RS", "RR")) {
                    db2 += 2;
                } else if (u.equals("CURRENT") && next.isAny("TIMESTAMP", "DATE", "TIME", "SCHEMA")) {
                    db2 += 2;
                } else if (u.equals("NEXT") && next.is("VALUE") && next2 != null && next2.is("FOR")) {
                    db2 += 2;
                } else if (u.equals("LANGUAGE") && next.is("SQL")) {
                    db2 += 2;
                } else if (u.equals("CONNECT") && next.is("BY")) {
                    oracle += 2;
                } else if (u.equals("START") && next.is("WITH") && next2 != null && !(next2.type() == TokenType.NUMBER)) {
                    oracle++;
                } else if (u.equals("OPTIMIZE") && next.is("FOR")) {
                    db2++;
                } else if (u.equals("FOR") && next.isAny("FETCH", "READ") && next2 != null && next2.is("ONLY")) {
                    db2++;
                } else if (u.equals("VALUE") && next.isSymbol("(")) {
                    db2++;
                }
            }
        }
    }
}
