package io.codeatlas.sql.analysis;

import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 방언 판정: 옵션 → 내장 소스 attrs → 파일 경로 → 구문 특징. 판정하지 못하면 null.
 * 대상 방언은 Oracle, DB2, PostgreSQL이다(F-019-R4).
 */
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

    /**
     * {@code oracle}, {@code db2}, {@code postgres}(별칭 {@code postgresql}, {@code pgsql})를 대소문자를 가리지 않고 읽는다(F-019-R2).
     * 그 밖의 값이면 null.
     */
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
        if (n.startsWith("postgres") || n.startsWith("pgsql")) {
            return Dialect.POSTGRES;
        }
        return null;
    }

    /**
     * 경로 힌트: {@code /oracle/}, {@code /db2/}, {@code /postgres/}·{@code /postgresql/}·{@code /pgsql/} 디렉터리,
     * {@code .db2}·{@code .pks}·{@code .pkb}·{@code .pgsql} 같은 확장자.
     */
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
        if (p.contains("/postgres/") || p.contains("/postgresql/") || p.contains("/pgsql/") || p.endsWith(".pgsql")) {
            return Dialect.POSTGRES;
        }
        return null;
    }

    /** 대상 방언의 표준 이름({@link DialectTokens#NAMES}): {@code oracle}, {@code db2}, {@code postgres}. 대상 방언이 아니면(null, AUTO) null. */
    public static String tokenOf(Dialect dialect) {
        if (dialect == null) {
            return null;
        }
        return switch (dialect) {
            case ORACLE -> "oracle";
            case DB2 -> "db2";
            case POSTGRES -> "postgres";
            default -> null;
        };
    }

    /** 방언 표준 이름이 대상 방언이면 그 방언. 대상 외 방언(mysql 등)이나 null이면 null. */
    public static Dialect targetOf(String token) {
        if (token == null) {
            return null;
        }
        return switch (token) {
            case "oracle" -> Dialect.ORACLE;
            case "db2" -> Dialect.DB2;
            case "postgres" -> Dialect.POSTGRES;
            default -> null;
        };
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
     * PostgreSQL 내용 특징(F-019-R2). 글자 기준으로 찾되(렉서를 거치지 않는다) <b>코드에 있는 것만</b> 센다: {@code --} 줄 주석,
     * {@code /* *}{@code /} 블록 주석, 작은따옴표 문자열 안에서 시작하는 일치는 세지 않는다({@link CodePositions}). Oracle·DB2 스크립트의
     * 주석("change owner to ..")이나 문자열 속 낱말로 그 파일을 PostgreSQL이라고 판정하지 않기 위해서다.
     *
     * <p>첫째 묶음(캡처 그룹 1)이 <b>강한 특징</b>이다 — 다른 방언의 스크립트에는 나오지 않는 구문이다:
     * <ul>
     *   <li>달러 인용: {@code AS $$}, {@code AS $태그$}, {@code DO $$}. {@code AS}·{@code DO} 뒤의 것만 본다: Oracle·DB2에서는 {@code $}가 이름의
     *       글자이고({@code A$$B}) PL/SQL 조건부 컴파일이 {@code $$PLSQL_UNIT}, {@code $IF}를 쓴다.</li>
     *   <li>{@code LANGUAGE plpgsql}(옛 표기 {@code LANGUAGE 'plpgsql'}은 따옴표째로), {@code ALTER TABLE [IF EXISTS] ONLY},
     *       {@code COPY 이름 [(..)] FROM stdin}, {@code CREATE EXTENSION}, {@code ::regclass}, {@code SET search_path}, {@code pg_catalog.}.</li>
     * </ul>
     * 나머지가 <b>약한 특징</b>이다 — 낱말 하나나 짧은 기호라 다른 방언의 스크립트에도 나올 수 있다. Oracle·DB2 특징이 없을 때만 PostgreSQL로
     * 판정한다({@link #contentDialect}):
     * <ul>
     *   <li>{@code OWNER TO}, 타입 {@code bigserial}·{@code smallserial}·{@code jsonb}·{@code bytea}·{@code timestamptz}.</li>
     *   <li>{@code serial}은 컬럼 정의의 타입 자리({@code (}나 {@code ,} 뒤의 이름 다음이고 뒤가 {@code ,}·{@code )}·제약 키워드)에 있을 때만 본다:
     *       {@code SERIAL}이라는 컬럼과 Oracle의 {@code SERIAL#}이 있다.</li>
     *   <li>JSON 경로 연산자 {@code #>}·{@code #>>}. 앞이 이름 글자가 아닐 때만 본다({@code SERIAL#>0}은 이름 {@code SERIAL#}과 {@code >}다).
     *       내장 소스의 읽기 방식을 정하는 데 쓴다(F-019-R7): 기본 읽기 방식에서는 {@code #}이 이름의 글자라 이 연산자를 읽지 못한다.</li>
     * </ul>
     */
    private static final java.util.regex.Pattern POSTGRES_FEATURES = java.util.regex.Pattern.compile(
            "(\\b(?:AS|DO)\\s+\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$"
                    + "|\\bLANGUAGE\\s+'?plpgsql\\b"
                    + "|\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?ONLY\\b"
                    + "|\\bCOPY\\s+[^\\s;(]+\\s*(?:\\([^;)]*\\)\\s*)?FROM\\s+stdin\\b"
                    + "|\\bCREATE\\s+EXTENSION\\b"
                    + "|::\\s*regclass\\b"
                    + "|\\bSET\\s+search_path\\b"
                    + "|\\bpg_catalog\\s*\\.)"
                    + "|\\bOWNER\\s+TO\\b"
                    + "|\\b(?:bigserial|smallserial|jsonb|bytea|timestamptz)\\b"
                    + "|[(,]\\s*\"?[A-Za-z_][A-Za-z0-9_]*\"?\\s+serial\\s*(?:[,)]|(?:PRIMARY|NOT|NULL|UNIQUE|REFERENCES|CHECK|DEFAULT|CONSTRAINT)\\b)"
                    + "|(?<![A-Za-z0-9_$#])#>>?",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** {@link #postgresLevel}의 값: PostgreSQL 내용 특징이 코드에 없다, 약한 것만 있다, 강한 것이 있다. */
    private static final int PG_NONE = 0;
    private static final int PG_WEAK = 1;
    private static final int PG_STRONG = 2;

    /**
     * 본문의 코드 자리에 있는 PostgreSQL 내용 특징의 세기. 일치 후보를 앞에서부터 보면서 {@link CodePositions}로 그 자리가 주석·문자열
     * 밖인지 확인한다. 후보와 스캐너가 모두 앞으로만 가므로 본문 길이에 비례하는 일만 한다(원문을 복사하거나 지우지 않는다).
     *
     * @param any true면 세기를 가리지 않고 첫 특징에서 멈춘다(강한 것이 뒤에 더 있어도 약한 것으로 답할 수 있다)
     */
    private static int postgresLevel(String text, boolean any) {
        if (text == null || text.isEmpty()) {
            return PG_NONE;
        }
        java.util.regex.Matcher m = POSTGRES_FEATURES.matcher(text);
        CodePositions code = new CodePositions(text);
        int level = PG_NONE;
        int from = 0;
        while (from <= text.length() && m.find(from)) {
            boolean strong = m.start(1) >= 0;
            if ((strong || level == PG_NONE) && code.isCode(m.start())) {
                if (strong) {
                    return PG_STRONG;
                }
                if (any) {
                    return PG_WEAK;
                }
                level = PG_WEAK;
            }
            // 주석·문자열 안에서 시작한 후보는 버리되, 그 안쪽에서 시작하는 다른 후보를 놓치지 않게 한 글자 뒤부터 다시 찾는다
            from = m.start() + 1;
        }
        return level;
    }

    /**
     * 본문의 한 자리가 코드인지({@code --} 줄 주석, {@code /* *}{@code /} 블록 주석, 작은따옴표 문자열 밖인지) 알려 준다.
     * 앞에서부터 주석·문자열 상태를 따라가며 한 방향으로만 전진한다: 물어보는 자리는 앞서 물어본 자리보다 뒤여야 한다.
     *
     * <p>렉서와 같은 규칙으로 읽는다: 문자열 안의 {@code ''}는 따옴표 하나, 닫히지 않은 문자열은 그 줄 끝에서 끝난다. Oracle의
     * {@code q'[..]'}와 PostgreSQL의 {@code E'..\'..'}(백슬래시 이스케이프)도 문자열 하나로 읽어 그 안의 따옴표에서 상태가 어긋나지 않게 한다.
     * 큰따옴표 이름과 달러 인용 본문은 코드로 본다(달러 인용 본문은 PL/pgSQL 코드다).
     */
    private static final class CodePositions {
        private final String text;
        private final int length;
        /** 코드 상태에서 다음에 볼 자리. */
        private int pos;
        /** 마지막으로 지나간 주석·문자열의 끝(그 다음 글자의 자리). */
        private int regionEnd;
        /** 닫는 자리({@code ]'} 등)가 뒤에 없다고 알아낸 {@code q'..'} 구분자. */
        private String missingClosers = "";

        CodePositions(String text) {
            this.text = text;
            this.length = text.length();
        }

        boolean isCode(int target) {
            if (target < regionEnd) {
                return false; // 앞서 본 주석·문자열 안이다
            }
            while (pos <= target && pos < length) {
                int end = regionEndAt(pos);
                if (end > pos) {
                    regionEnd = end;
                    pos = end;
                    if (target < end) {
                        return false;
                    }
                } else {
                    pos++;
                }
            }
            return true;
        }

        /** i에서 주석이나 문자열이 시작하면 그 끝(다음 글자의 자리), 아니면 i. */
        private int regionEndAt(int i) {
            char c = text.charAt(i);
            char n = i + 1 < length ? text.charAt(i + 1) : '\0';
            if (c == '-' && n == '-') {
                return lineEnd(i + 2);
            }
            if (c == '/' && n == '*') {
                int close = text.indexOf("*/", i + 2);
                return close < 0 ? length : close + 2;
            }
            return c == '\'' ? stringEnd(i) : i;
        }

        /** quote의 작은따옴표로 시작하는 문자열의 끝. */
        private int stringEnd(int quote) {
            char prefix = quote > 0 ? Character.toUpperCase(text.charAt(quote - 1)) : '\0';
            boolean prefixed = (prefix == 'Q' || prefix == 'E') && !(quote > 1 && nameChar(text.charAt(quote - 2)) && !nationalQ(quote));
            if (prefixed && prefix == 'Q' && quote + 1 < length) {
                // Oracle q'[..]', q'{..}', q'(..)', q'<..>', q'!..!': 닫는 구분자와 따옴표가 붙은 자리까지
                char open = text.charAt(quote + 1);
                char close = switch (open) {
                    case '[' -> ']';
                    case '{' -> '}';
                    case '(' -> ')';
                    case '<' -> '>';
                    default -> open;
                };
                // 닫는 자리가 없다고 한 번 알아낸 구분자는 다시 찾지 않는다(뒤쪽에도 없다): 끝까지 훑는 일을 구분자마다 한 번만 한다
                int end = missingClosers.indexOf(close) >= 0 ? -1 : text.indexOf(close + "'", quote + 2);
                if (end < 0) {
                    missingClosers += close;
                    return Math.max(lineEnd(quote + 1), quote + 1);
                }
                return end + 2;
            }
            boolean backslash = prefixed && prefix == 'E';
            int i = quote + 1;
            while (i < length) {
                char c = text.charAt(i);
                if (c == '\\' && backslash && i + 1 < length) {
                    i += 2;
                } else if (c != '\'') {
                    i++;
                } else if (i + 1 < length && text.charAt(i + 1) == '\'') {
                    i += 2;
                } else {
                    return i + 1;
                }
            }
            return Math.max(lineEnd(quote + 1), quote + 1);
        }

        /** quote 앞의 {@code Q}가 {@code NQ'..'}의 것인가(그 앞은 이름 글자가 아니다). */
        private boolean nationalQ(int quote) {
            return Character.toUpperCase(text.charAt(quote - 1)) == 'Q' && Character.toUpperCase(text.charAt(quote - 2)) == 'N'
                    && !(quote > 2 && nameChar(text.charAt(quote - 3)));
        }

        private static boolean nameChar(char c) {
            return c == '_' || c == '$' || c == '#' || Character.isLetterOrDigit(c);
        }

        /** from부터 처음 나오는 줄 끝 글자(CR, LF)의 자리. 없으면 본문의 끝. */
        private int lineEnd(int from) {
            int i = Math.min(Math.max(0, from), length);
            while (i < length && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                i++;
            }
            return i;
        }
    }

    /**
     * 본문의 코드 자리(주석·문자열 밖)에 PostgreSQL 내용 특징({@link #POSTGRES_FEATURES})이 있는가. 강한 것과 약한 것을 가리지 않는다.
     * 파일의 방언과 내장 소스의 읽기 방식은 Oracle·DB2 특징과의 순서까지 보는 {@link #contentDialect}로 정한다.
     */
    public static boolean postgresFeatures(String text) {
        return postgresLevel(text, true) != PG_NONE;
    }

    /**
     * 파일 본문의 특징으로 읽은 방언 이름(표준 이름). 경로에 방언 이름이 없는 스크립트의 방언(F-007-R24, F-019-R2)과, 생산자도 프로젝트도
     * 방언을 알려 주지 않은 내장 소스의 읽기 방식(F-019-R7)을 정할 때 쓴다. 이 순서로 본다:
     * <ol>
     *   <li>{@code CREATE MEMORY|CACHED TABLE}·줄 처음의 {@code SET DATABASE}(HSQL)</li>
     *   <li>백틱 이름·{@code ENGINE=}·{@code AUTO_INCREMENT}(MySQL)</li>
     *   <li>PostgreSQL의 강한 특징({@link #POSTGRES_FEATURES})</li>
     *   <li>{@code VARCHAR2}·{@code NUMBER(}(Oracle)</li>
     *   <li>{@code GENERATED ALWAYS AS IDENTITY}와 {@code ) IN 테이블스페이스}가 함께 있음(DB2)</li>
     *   <li>PostgreSQL의 약한 특징: Oracle·DB2 특징이 없을 때만 PostgreSQL이다</li>
     * </ol>
     * PostgreSQL 특징은 코드에 있는 것만 센다(주석·문자열 안의 것은 세지 않는다). 확실한 표기가 없으면 null이다.
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
        int postgres = postgresLevel(text, false);
        if (postgres == PG_STRONG) {
            return "postgres";
        }
        if (ORACLE_FEATURES.matcher(text).find()) {
            return "oracle";
        }
        if (DB2_IDENTITY.matcher(text).find() && DB2_TABLESPACE.matcher(text).find()) {
            return "db2";
        }
        return postgres == PG_WEAK ? "postgres" : null;
    }

    // ------------------------------------------------------------------ 읽기 방식(ADR-0012, F-019-R7)

    /**
     * 방언 표준 이름({@link DialectTokens#NAMES})의 읽기 방식: MySQL 계열은 {@link Syntax#MYSQL}, postgres는 {@link Syntax#POSTGRES},
     * 그 밖의 방언은 기본이다. 방언을 모르면(null) null.
     */
    public static Syntax syntaxOfName(String dialect) {
        if (dialect == null) {
            return null;
        }
        if (DialectTokens.isMySql(dialect)) {
            return Syntax.MYSQL;
        }
        return "postgres".equals(dialect) ? Syntax.POSTGRES : Syntax.DEFAULT;
    }

    /**
     * 프로젝트 방언이 정하는 읽기 방식. 정하지 못하면 null이고, 그때는 텍스트의 내용 특징으로 정한다.
     *
     * <ol>
     *   <li>{@code --dialect}가 있으면 그 방언의 것이다({@code postgres}면 PostgreSQL, {@code oracle}·{@code db2}면 기본).
     *       이 경우 내용 특징을 보지 않는다.</li>
     *   <li>{@code mybatis.dialectVariant}나 프로젝트 설정이 MySQL 계열·PostgreSQL을 가리키면 그것이다. 그 밖의 방언을 가리키면 정하지 않는다
     *       (지금까지처럼 본문의 MySQL 특징을 본다, F-019-R8).</li>
     *   <li>스크립트에서 고른 방언이 PostgreSQL이면 PostgreSQL이다. 그 밖의 방언으로는 정하지 않는다(같은 이유).</li>
     * </ol>
     *
     * @param optionDialect {@code --dialect}. 없으면(auto) null
     * @param declaredDialect {@code mybatis.dialectVariant}, 없으면 프로젝트 설정이 가리키는 방언(표준 이름). 없으면 null
     * @param scriptDialect 스크립트에서 고른 방언(표준 이름). 아직 모르거나 없으면 null
     */
    public static Syntax syntaxOfProject(Dialect optionDialect, String declaredDialect, String scriptDialect) {
        Dialect option = fromOption(optionDialect);
        if (option != null) {
            return option == Dialect.POSTGRES ? Syntax.POSTGRES : Syntax.DEFAULT;
        }
        Syntax declared = syntaxOfName(declaredDialect);
        if (declared == Syntax.MYSQL || declared == Syntax.POSTGRES) {
            return declared;
        }
        return "postgres".equals(scriptDialect) ? Syntax.POSTGRES : null;
    }

    /** 본문의 내용 특징으로 정한 읽기 방식: MySQL 특징이 먼저고, 그다음 PostgreSQL로 판정한 본문({@link #contentDialect})이다. */
    private static Syntax syntaxOfContent(String text) {
        if (mysqlFeatures(text)) {
            return Syntax.MYSQL;
        }
        return "postgres".equals(contentDialect(text)) ? Syntax.POSTGRES : Syntax.DEFAULT;
    }

    /**
     * SQL 파일의 읽기 방식(F-019-R7). 경로의 방언 이름 → {@code --dialect} → 프로젝트 방언 → 본문의 내용 특징 순으로 정한다.
     * 읽기 방식은 여기와 {@link #syntaxOfEmbedded}에서만 정한다(ADR-0012).
     *
     * @param pathToken 경로에서 읽은 파일의 방언(표준 이름, {@link DialectSelection#dialectOfFile}). 없으면 null
     * @param optionDialect {@code --dialect}. 없으면(auto) null
     * @param text 파일 본문
     * @param projectSyntax 프로젝트 방언이 정한 읽기 방식({@link DialectSelection#declaredSyntax}). 없으면 null
     */
    public static Syntax syntaxOfFile(String pathToken, Dialect optionDialect, String text, Syntax projectSyntax) {
        if (pathToken != null) {
            return syntaxOfName(pathToken);
        }
        Dialect option = fromOption(optionDialect);
        if (option != null) {
            return option == Dialect.POSTGRES ? Syntax.POSTGRES : Syntax.DEFAULT;
        }
        return projectSyntax != null ? projectSyntax : syntaxOfContent(text);
    }

    /**
     * 내장 소스(매퍼 SQL 등)의 읽기 방식(F-019-R7). 생산자가 넘긴 {@code attrs.dialect} → 프로젝트 방언 → 그 텍스트의 내용 특징 순으로 정한다.
     *
     * @param attrsDialect 생산자가 넘긴 방언 이름({@code attrs.dialect}). 없거나 방언 이름이 아니면 건너뛴다
     * @param projectSyntax 프로젝트 방언이 정한 읽기 방식({@link DialectSelection#projectSyntax}: {@code --dialect},
     *                      {@code mybatis.dialectVariant}, 프로젝트 설정, 스크립트에서 고른 방언). 없으면 null
     * @param text 내장 소스의 본문
     */
    public static Syntax syntaxOfEmbedded(String attrsDialect, Syntax projectSyntax, String text) {
        String hint = DialectTokens.canonical(attrsDialect);
        if (hint != null) {
            return syntaxOfName(hint);
        }
        return projectSyntax != null ? projectSyntax : syntaxOfContent(text);
    }

    /** MySQL의 {@code INTERVAL n 단위}(따옴표 없는 수)에 오는 단위. Oracle은 {@code INTERVAL '6' MONTH}처럼 문자열을 쓴다. */
    private static final Set<String> INTERVAL_UNITS = Set.of(
            "MICROSECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR");

    /** PostgreSQL에만 있는 타입 이름(구문 특징). {@code SERIAL}은 컬럼 정의의 타입 자리에서만 본다({@link Scorer}). */
    private static final Set<String> POSTGRES_TYPES = Set.of("BIGSERIAL", "SMALLSERIAL", "JSONB", "BYTEA", "TIMESTAMPTZ");

    /** 컬럼 정의에서 타입 바로 뒤에 오는 제약 키워드. */
    private static final Set<String> AFTER_COLUMN_TYPE = Set.of(
            "PRIMARY", "NOT", "NULL", "UNIQUE", "REFERENCES", "CHECK", "DEFAULT", "CONSTRAINT");

    /**
     * 구문 특징으로 판정한다(Oracle, DB2, PostgreSQL). 가장 높은 점수가 0이거나 둘 이상이 같으면 null.
     * MySQL 표기가 있으면 null이다({@link Scorer}).
     */
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
     *
     * <p>PostgreSQL 구문 특징(F-019-R3)도 함께 매긴다. PostgreSQL에만 있는 표기 — 달러 인용, {@code ::}, {@code ILIKE},
     * 문자열을 받는 {@code nextval('..')}·{@code currval('..')}·{@code setval('..')} — 가 하나라도 있으면 Oracle·DB2로 판정하지 않고,
     * PostgreSQL에도 있는 표기로 준 점수({@code :=}·{@code NEXTVAL}·{@code CURRVAL}의 Oracle 점수, {@code FETCH FIRST}·{@code LANGUAGE SQL}의
     * DB2 점수)를 뺀다. MySQL에도 있는 표기({@code NOW()}, {@code LIMIT}, {@code ->>})와 Oracle에도 있는 {@code RETURNING}은 점수가 없다.
     */
    public static final class Scorer {
        private int oracle;
        private int db2;
        private int postgres;
        /** PostgreSQL에도 있는 표기로 준 Oracle 점수({@code :=}, {@code NEXTVAL}, {@code CURRVAL}). */
        private int oracleShared;
        /** PostgreSQL에도 있는 표기로 준 DB2 점수({@code FETCH FIRST|NEXT}, {@code LANGUAGE SQL}). */
        private int db2Shared;
        /** PostgreSQL에만 있는 표기를 봤다. */
        private boolean postgresOnly;
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

        /**
         * 점수가 가장 높은 방언. 가장 높은 점수가 0이거나 둘 이상이 같으면 null이다. PostgreSQL에만 있는 표기가 있었으면
         * Oracle·DB2로는 판정하지 않는다(F-019-R3).
         */
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
            int o = postgresOnly ? oracle - oracleShared : oracle;
            int d = postgresOnly ? db2 - db2Shared : db2;
            if (postgres > o && postgres > d) {
                return Dialect.POSTGRES;
            }
            if (postgresOnly) {
                return null;
            }
            if (o > d && o > postgres) {
                return Dialect.ORACLE;
            }
            if (d > o && d > postgres) {
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

        /** t 바로 뒤에 공백 없이 next가 붙어 있는가. */
        private static boolean adjacent(Token t, Token next) {
            return next != null && next.offset() == t.endOffset();
        }

        /**
         * 달러 인용을 여는 자리인가. PostgreSQL 읽기 방식의 렉서는 달러 인용을 문자열 토큰 하나로 낸다({@code $}로 시작하는 문자열).
         * 기본 읽기 방식의 렉서는 {@code $$}를 기호 둘로, {@code $태그$}를 기호 {@code $}와 {@code $}로 끝나는 이름으로 낸다:
         * 이 경우 바로 앞이 {@code AS}나 {@code DO}일 때만 달러 인용으로 본다(PL/SQL 조건부 컴파일의 {@code $$PLSQL_UNIT}이 같은 꼴이다).
         * 부를 때 prev2가 t의 앞 토큰이다.
         */
        private boolean dollarQuote(Token t, Token next) {
            if (t.type() == TokenType.STRING) {
                return t.text().startsWith("$");
            }
            if (!t.isSymbol("$") || prev2 == null || !prev2.isAny("AS", "DO") || !adjacent(t, next)) {
                return false;
            }
            return next.isSymbol("$") || (next.type() == TokenType.IDENT && next.text().indexOf('$') == next.text().length() - 1);
        }

        /** PostgreSQL 구문 특징이면 점수를 매기고 true. 부를 때 prev2가 t의 앞 토큰, prev3이 그 앞 토큰이다. */
        private boolean weighPostgres(Token t, Token next, Token next2) {
            if (dollarQuote(t, next)) {
                postgres += 2;
                postgresOnly = true;
                return true;
            }
            if (t.type() == TokenType.SYMBOL) {
                if (t.isSymbol("::")) {
                    postgres += 2;
                    postgresOnly = true;
                    return true;
                }
                // JSON 경로 연산자 #>, #>>와 포함 연산자 @>: 기본 읽기 방식의 렉서는 붙어 있는 기호 둘로 낸다. PostgreSQL 렉서는 기호 하나로 낸다
                if (t.isSymbol("#>") || t.isSymbol("#>>") || t.isSymbol("@>")
                        || (t.isSymbol("#") && adjacent(t, next) && (next.isSymbol(">") || next.isSymbol(">>")))
                        || (t.isSymbol("@") && adjacent(t, next) && next.isSymbol(">"))) {
                    postgres += 2;
                    return true;
                }
                return false;
            }
            if (t.type() != TokenType.IDENT) {
                return false;
            }
            String u = t.upper();
            if (u.equals("NEXTVAL") || u.equals("CURRVAL") || u.equals("SETVAL")) {
                if (next == null || !next.isSymbol("(")) {
                    return false; // seq.NEXTVAL(Oracle 표기)은 아래에서 본다
                }
                // 괄호가 붙은 꼴: Oracle 표기가 아니다. 첫 인자가 문자열이면 PostgreSQL이다(MariaDB의 NEXTVAL(seq)는 이름을 받는다)
                if (next2 != null && next2.type() == TokenType.STRING) {
                    postgres += 2;
                    postgresOnly = true;
                }
                return true;
            }
            if (u.equals("ILIKE")) {
                postgres += 2;
                postgresOnly = true;
                return true;
            }
            if (u.equals("PLPGSQL") || u.equals("PG_CATALOG")) {
                postgres += 2;
                return true;
            }
            if (u.equals("PERFORM") || POSTGRES_TYPES.contains(u)) {
                postgres++;
                return true;
            }
            if (u.equals("SERIAL")) {
                // 컬럼 정의의 타입 자리일 때만: ( 이름 SERIAL ,  — SERIAL이라는 컬럼 이름으로는 점수를 주지 않는다
                boolean afterName = prev2 != null && (prev2.type() == TokenType.IDENT || prev2.type() == TokenType.QUOTED_IDENT)
                        && prev3 != null && (prev3.isSymbol("(") || prev3.isSymbol(","));
                boolean beforeEnd = next != null && (next.isSymbol(",") || next.isSymbol(")")
                        || (next.type() == TokenType.IDENT && AFTER_COLUMN_TYPE.contains(next.upper())));
                if (afterName && beforeEnd) {
                    postgres++;
                    return true;
                }
                return false;
            }
            if (next == null) {
                return false;
            }
            if ((u.equals("ON") && next.is("CONFLICT")) || (u.equals("RAISE") && next.isAny("NOTICE", "EXCEPTION"))
                    || (u.equals("DISTINCT") && next.is("ON") && next2 != null && next2.isSymbol("("))) {
                postgres += 2;
                return true;
            }
            return false;
        }

        private void weigh(Token t, Token next, Token next2) {
            if (weighPostgres(t, next, next2)) {
                return;
            }
            if (t.type() == TokenType.SYMBOL) {
                if (t.isSymbol("(+)")) {
                    oracle += 2;
                } else if (t.isSymbol(":=")) {
                    oracle += 2;
                    oracleShared += 2; // PL/pgSQL도 :=로 대입한다
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
                if (u.equals("NEXTVAL") || u.equals("CURRVAL")) {
                    oracleShared++;
                }
            } else if (DB2_WORDS.contains(u)) {
                db2++;
            } else if (next != null) {
                if (u.equals("FETCH") && next.isAny("FIRST", "NEXT")) {
                    db2++;
                    db2Shared++; // PostgreSQL에도 FETCH FIRST n ROWS ONLY가 있다
                } else if (u.equals("WITH") && next.isAny("UR", "CS", "RS", "RR")) {
                    db2 += 2;
                } else if (u.equals("CURRENT") && next.isAny("TIMESTAMP", "DATE", "TIME", "SCHEMA")) {
                    db2 += 2;
                } else if (u.equals("NEXT") && next.is("VALUE") && next2 != null && next2.is("FOR")) {
                    db2 += 2;
                } else if (u.equals("LANGUAGE") && next.is("SQL")) {
                    db2 += 2;
                    db2Shared += 2; // PostgreSQL 루틴에도 LANGUAGE sql이 있다
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
