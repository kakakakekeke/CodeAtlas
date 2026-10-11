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

    /**
     * MySQL에만 있는 표기: 백틱으로 감싼 이름, {@code AUTO_INCREMENT}, 테이블 옵션 {@code ENGINE=}.
     * 이 패턴과 아래 내용 특징 패턴들은 {@link ContentScan}이 후보 자리에서만 맞춰 본다: 대안을 더하면 그 후보 표도 고친다.
     */
    static final java.util.regex.Pattern MYSQL_FEATURES = java.util.regex.Pattern.compile(
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

    /**
     * 본문에 MySQL에만 있는 표기(백틱 이름, {@code AUTO_INCREMENT}, {@code ENGINE=})가 있는가. 경로와 옵션으로 방언을 모를 때
     * MySQL 관용 표기(줄 처음 {@code #} 주석)를 받을지, 구문의 방언을 Oracle·DB2로 적지 않을지 정하는 데 쓴다(F-006-R27, F-006-R28).
     */
    public static boolean mysqlFeatures(String text) {
        return text != null && ContentScan.of(text, ContentScan.F_MYSQL, false).found(ContentScan.F_MYSQL);
    }

    static final java.util.regex.Pattern HSQL_FEATURES = java.util.regex.Pattern.compile(
            "\\bCREATE\\s+(MEMORY|CACHED)\\s+TABLE\\b|^\\s*SET\\s+DATABASE\\b", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE);
    static final java.util.regex.Pattern ORACLE_FEATURES = java.util.regex.Pattern.compile(
            "\\bN?VARCHAR2\\b|\\bNUMBER\\s*\\(", java.util.regex.Pattern.CASE_INSENSITIVE);
    static final java.util.regex.Pattern DB2_IDENTITY = java.util.regex.Pattern.compile(
            "\\bGENERATED\\s+(ALWAYS|BY\\s+DEFAULT)\\s+AS\\s+IDENTITY\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    static final java.util.regex.Pattern DB2_TABLESPACE = java.util.regex.Pattern.compile(
            "\\)\\s*IN\\s+\"?[A-Za-z_][A-Za-z0-9_]*\"?", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * PostgreSQL 내용 특징(F-019-R2). 글자 기준으로 찾되(렉서를 거치지 않는다) <b>코드에 있는 것만</b> 센다: {@code --} 줄 주석,
     * {@code /* *}{@code /} 블록 주석, 작은따옴표 문자열 안에서 시작하는 일치는 세지 않는다({@link CodePositions}). Oracle·DB2 스크립트의
     * 주석("change owner to ..")이나 문자열 속 낱말로 그 파일을 PostgreSQL이라고 판정하지 않기 위해서다. 찾는 일은 {@link ContentScan}이 한다.
     *
     * <p>첫째 묶음(캡처 그룹 1)이 <b>강한 특징</b>이다 — 다른 방언의 스크립트에는 나오지 않는 구문이다:
     * <ul>
     *   <li>달러 인용: {@code AS $$}, {@code AS $태그$}, {@code DO $$}. {@code AS}·{@code DO} 뒤의 것만 본다: Oracle·DB2에서는 {@code $}가 이름의
     *       글자이고({@code A$$B}) PL/SQL 조건부 컴파일이 {@code $$PLSQL_UNIT}, {@code $IF}를 쓴다.</li>
     *   <li>{@code LANGUAGE plpgsql}(옛 표기 {@code LANGUAGE 'plpgsql'}은 따옴표째로), {@code ALTER TABLE [IF EXISTS] ONLY},
     *       {@code COPY 이름 [(..)] FROM stdin}, {@code CREATE EXTENSION}, {@code ::regclass}, {@code SET search_path}, {@code pg_catalog.}.</li>
     * </ul>
     * 나머지가 <b>약한 특징</b>이다 — 낱말 하나나 짧은 기호라 다른 방언의 스크립트에도 나올 수 있다. Oracle·DB2 특징이 없을 때만 PostgreSQL로
     * 판정한다({@link #content}):
     * <ul>
     *   <li>{@code OWNER TO}, 타입 {@code bigserial}·{@code smallserial}·{@code jsonb}·{@code bytea}·{@code timestamptz}.</li>
     *   <li>{@code serial}은 컬럼 정의의 타입 자리({@code (}나 {@code ,} 뒤의 이름 다음이고 뒤가 {@code ,}·{@code )}·제약 키워드)에 있을 때만 본다:
     *       {@code SERIAL}이라는 컬럼과 Oracle의 {@code SERIAL#}이 있다.</li>
     *   <li>JSON 경로 연산자 {@code #>}·{@code #>>}. 앞이 이름 글자가 아닐 때만 본다({@code SERIAL#>0}은 이름 {@code SERIAL#}과 {@code >}다).
     *       내장 소스의 읽기 방식을 정하는 데 쓴다(F-019-R7): 기본 읽기 방식에서는 {@code #}이 이름의 글자라 이 연산자를 읽지 못한다.</li>
     * </ul>
     */
    static final java.util.regex.Pattern POSTGRES_FEATURES = java.util.regex.Pattern.compile(
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

    /** PostgreSQL 내용 특징의 세기({@link ContentScan#postgres}): 코드에 없다, 약한 것만 있다, 강한 것이 있다. */
    private static final int PG_NONE = 0;
    private static final int PG_WEAK = 1;
    private static final int PG_STRONG = 2;

    /**
     * 본문에서 코드가 아닌 구간({@code --} 줄 주석, {@code /* *}{@code /} 블록 주석, 작은따옴표 문자열)이 어디서 끝나는지 알려 준다.
     * 앞에서부터 걸으면서 쓴다: 구간이 시작하는 자리에서 {@link #regionEndAt}이 준 끝으로 건너뛰면, 건너뛰지 않고 밟은 자리가 코드다.
     * 물어보는 자리는 앞서 물어본 자리보다 뒤여야 한다(닫는 자리가 없는 {@code q'..'} 구분자를 기억해 두기 때문이다).
     *
     * <p>렉서와 같은 규칙으로 읽는다: 문자열 안의 {@code ''}는 따옴표 하나, 닫히지 않은 문자열은 그 줄 끝에서 끝난다. Oracle의
     * {@code q'[..]'}와 PostgreSQL의 {@code E'..\'..'}(백슬래시 이스케이프)도 문자열 하나로 읽어 그 안의 따옴표에서 상태가 어긋나지 않게 한다.
     * 큰따옴표 이름과 달러 인용 본문은 코드로 본다(달러 인용 본문은 PL/pgSQL 코드다).
     */
    static final class CodePositions {
        private final String text;
        private final int length;
        /** 닫는 자리({@code ]'} 등)가 뒤에 없다고 알아낸 {@code q'..'} 구분자. */
        private String missingClosers = "";

        CodePositions(String text) {
            this.text = text;
            this.length = text.length();
        }

        /** i에서 주석이나 문자열이 시작하면 그 끝(다음 글자의 자리), 아니면 i. */
        int regionEndAt(int i) {
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
     * 파일의 방언과 내장 소스의 읽기 방식은 Oracle·DB2 특징과의 순서까지 보는 {@link #content}로 정한다.
     */
    public static boolean postgresFeatures(String text) {
        return text != null && ContentScan.of(text, ContentScan.F_POSTGRES, true).postgres != PG_NONE;
    }

    /**
     * 본문의 내용 특징을 한 번 훑어 얻은 판정. 파일의 방언(F-007-R24, F-019-R2)과 읽기 방식(F-019-R7)이 같은 판정에서 나오므로,
     * 파일당 한 번만 구해 {@link #syntaxOfFile(String, Dialect, Content, Syntax)}에 넘긴다.
     *
     * @param dialect 내용 특징으로 읽은 방언(표준 이름). 확실한 표기가 없으면 null
     * @param mysql 본문에 MySQL에만 있는 표기가 있는가({@link #mysqlFeatures}). HSQL 특징이 함께 있으면 방언은 {@code hsql}이어도 true다
     */
    public record Content(String dialect, boolean mysql) {
        private static final Content NONE = new Content(null, false);
    }

    /**
     * 본문의 내용 특징 판정. 경로에 방언 이름이 없는 스크립트의 방언(F-007-R24, F-019-R2)과, 생산자도 프로젝트도 방언을 알려 주지 않은
     * 텍스트의 읽기 방식(F-019-R7)을 정할 때 쓴다. 방언은 이 순서로 본다:
     * <ol>
     *   <li>{@code CREATE MEMORY|CACHED TABLE}·줄 처음의 {@code SET DATABASE}(HSQL)</li>
     *   <li>백틱 이름·{@code ENGINE=}·{@code AUTO_INCREMENT}(MySQL)</li>
     *   <li>PostgreSQL의 강한 특징({@link #POSTGRES_FEATURES})</li>
     *   <li>{@code VARCHAR2}·{@code NUMBER(}(Oracle)</li>
     *   <li>{@code GENERATED ALWAYS AS IDENTITY}와 {@code ) IN 테이블스페이스}가 함께 있음(DB2)</li>
     *   <li>PostgreSQL의 약한 특징: Oracle·DB2 특징이 없을 때만 PostgreSQL이다</li>
     * </ol>
     * PostgreSQL 특징은 코드에 있는 것만 센다(주석·문자열 안의 것은 세지 않는다). 확실한 표기가 없으면 방언이 null이다.
     * 본문은 길이에 비례하는 일로 한 번만 훑는다({@link ContentScan}).
     */
    public static Content content(String text) {
        if (text == null || text.isEmpty()) {
            return Content.NONE;
        }
        ContentScan scan = ContentScan.of(text, ContentScan.ALL, false);
        return new Content(dialectOf(scan, text), scan.found(ContentScan.F_MYSQL));
    }

    private static String dialectOf(ContentScan scan, String text) {
        if (scan.found(ContentScan.F_HSQL)) {
            return "hsql";
        }
        if (scan.found(ContentScan.F_MYSQL)) {
            return "mysql";
        }
        if (scan.postgres == PG_STRONG) {
            return "postgres";
        }
        if (scan.found(ContentScan.F_ORACLE)) {
            return "oracle";
        }
        if (scan.found(ContentScan.F_DB2_IDENTITY) && DB2_TABLESPACE.matcher(text).find()) {
            return "db2";
        }
        return scan.postgres == PG_WEAK ? "postgres" : null;
    }

    /** 파일 본문의 특징으로 읽은 방언 이름(표준 이름): {@link #content}의 방언이다. 확실한 표기가 없으면 null. */
    public static String contentDialect(String text) {
        return content(text).dialect();
    }

    /**
     * 본문을 앞에서부터 한 번 훑어 내용 특징 패턴들({@link #HSQL_FEATURES}, {@link #MYSQL_FEATURES}, {@link #POSTGRES_FEATURES},
     * {@link #ORACLE_FEATURES}, {@link #DB2_IDENTITY})이 맞는 자리가 있는지 본다. 답은 패턴마다 본문 전체에 {@code find()}를 돌린 것과 같다
     * (PostgreSQL 패턴은 코드 자리에서 시작한 일치만 센다). 다른 것은 드는 일뿐이다: 정규식을 모든 글자에서 시도하지 않고 패턴이 시작할 수
     * 있는 자리("후보")에서만 그 자리에 고정해 맞춰 본다. 리터럴 INSERT 덤프처럼 후보가 드문 큰 본문이 가벼운 훑기 한 번으로 끝난다.
     *
     * <p>후보는 대안이 시작하는 글자로 거른다. 놓치는 자리가 없도록 넉넉하게 거르고, 맞는지는 패턴이 정한다:
     * <ul>
     *   <li>낱말로 시작하는 대안({@code \bCREATE ..}, {@code \bOWNER ..} 등): 앞 글자가 낱말 글자가 아니고 첫 두 글자가 그 낱말의 것인 자리</li>
     *   <li>기호로 시작하는 대안: 백틱, {@code ::}, {@code #>}</li>
     *   <li>{@code [(,] 이름 serial ..}: {@code (}·{@code ,} 뒤에 이름과 공백이 오고 그다음이 {@code s}인 자리</li>
     *   <li>{@code ^\s*SET DATABASE}: 줄 처음에서 공백을 넘긴 글자가 {@code s}인 자리</li>
     * </ul>
     * 패턴에 대안을 더하거나 첫 낱말을 바꾸면 {@link #word}의 표도 함께 고친다. 어긋나면 {@code ContentFeatureScanTest}가 잡는다.
     */
    static final class ContentScan {
        static final int F_HSQL = 1;
        static final int F_MYSQL = 1 << 1;
        static final int F_POSTGRES = 1 << 2;
        static final int F_ORACLE = 1 << 3;
        static final int F_DB2_IDENTITY = 1 << 4;
        static final int ALL = F_HSQL | F_MYSQL | F_POSTGRES | F_ORACLE | F_DB2_IDENTITY;

        private final String text;
        private final int length;
        /** PostgreSQL 특징을 세기를 가리지 않고 하나만 찾는가(강한 것이 뒤에 더 있어도 약한 것에서 멈춘다). */
        private final boolean anyPostgres;
        /** 아직 찾고 있는 패턴들. 다 찾으면 훑기를 멈춘다. */
        private int pending;
        /** 맞는 자리를 찾은 패턴들(PostgreSQL 제외). */
        private int found;
        /** 코드 자리에서 찾은 PostgreSQL 내용 특징의 세기. */
        int postgres = PG_NONE;
        private java.util.regex.Matcher hsql;
        private java.util.regex.Matcher mysql;
        private java.util.regex.Matcher postgresMatcher;
        private java.util.regex.Matcher oracle;
        private java.util.regex.Matcher db2Identity;
        /** 줄 처음에서 마지막으로 넘긴 공백 구간 [blankFrom, blankTo). 빈 줄이 이어질 때 같은 공백을 줄마다 다시 넘기지 않는다. */
        private int blankFrom = -1;
        private int blankTo = -1;
        /** 공백 뒤 이 자리에서 시작하는 {@code SET DATABASE}는 없다고 확인했다(같은 공백 구간의 다른 줄 처음도 답이 같다). */
        private int noSetDatabaseAt = -1;

        private ContentScan(String text, int features, boolean anyPostgres) {
            this.text = text;
            this.length = text.length();
            this.pending = features;
            this.anyPostgres = anyPostgres;
        }

        /** text에서 features(F_*)의 패턴을 찾는다. */
        static ContentScan of(String text, int features, boolean anyPostgres) {
            ContentScan scan = new ContentScan(text, features, anyPostgres);
            scan.run();
            return scan;
        }

        boolean found(int feature) {
            return (found & feature) != 0;
        }

        private void run() {
            CodePositions regions = new CodePositions(text);
            int regionEnd = 0; // 지금까지 지나온 주석·문자열의 끝: 이보다 앞은 코드가 아니다
            lineStart(0);
            for (int i = 0; i < length && pending != 0; i++) {
                char c = text.charAt(i);
                boolean code = false; // PostgreSQL 패턴을 찾는 동안만 따진다
                if ((pending & F_POSTGRES) != 0 && i >= regionEnd) {
                    int end = c == '-' || c == '/' || c == '\'' ? regions.regionEndAt(i) : i;
                    if (end > i) {
                        regionEnd = end;
                    } else {
                        code = true;
                    }
                }
                switch (c) {
                    case '\n', '\u0085', '\u2028', '\u2029' -> lineStart(i + 1);
                    case '\r' -> {
                        if (charAt(i + 1) != '\n') { // \r\n\uc740 \uc904 \ub05d \ud558\ub098\ub2e4: \uadf8 \uc0ac\uc774\ub294 \uc904 \ucc98\uc74c\uc774 \uc544\ub2c8\ub2e4
                            lineStart(i + 1);
                        }
                    }
                    case '`' -> attempt(F_MYSQL, i);
                    case ':' -> {
                        if (code && charAt(i + 1) == ':') {
                            attempt(F_POSTGRES, i);
                        }
                    }
                    case '#' -> {
                        if (code && charAt(i + 1) == '>') {
                            attempt(F_POSTGRES, i);
                        }
                    }
                    case '(', ',' -> {
                        if (code && serialColumnAhead(i + 1)) {
                            attempt(F_POSTGRES, i);
                        }
                    }
                    default -> {
                        if (asciiLetter(c) && !(i > 0 && wordChar(text.charAt(i - 1)))) {
                            word(i, c, code);
                        }
                    }
                }
            }
        }

        /**
         * 낱말이 시작하는 자리 i(앞 글자가 낱말 글자가 아니다)의 첫 두 글자가 어느 대안의 첫 낱말과 같으면 그 패턴을 맞춰 본다.
         * 낱말로 시작하는 대안은 모두 {@code \b}로 시작하고 대소문자를 가리지 않는다.
         */
        private void word(int i, char first, boolean code) {
            char second = charAt(i + 1);
            if (second >= 128) {
                return;
            }
            switch ((first | 0x20) << 8 | (second | 0x20)) {
                // PostgreSQL: AS·DO $..$, LANGUAGE, ALTER, COPY, SET, pg_catalog, OWNER, bigserial, smallserial, jsonb, bytea, timestamptz
                case 'a' << 8 | 's', 'd' << 8 | 'o', 'l' << 8 | 'a', 'a' << 8 | 'l', 'c' << 8 | 'o', 's' << 8 | 'e', 'p' << 8 | 'g', 'o' << 8 | 'w',
                        'b' << 8 | 'i', 's' << 8 | 'm', 'j' << 8 | 's', 'b' << 8 | 'y', 't' << 8 | 'i' -> {
                    if (code) {
                        attempt(F_POSTGRES, i);
                    }
                }
                // CREATE MEMORY|CACHED TABLE(HSQL), CREATE EXTENSION(PostgreSQL)
                case 'c' << 8 | 'r' -> {
                    attempt(F_HSQL, i);
                    if (code) {
                        attempt(F_POSTGRES, i);
                    }
                }
                // MySQL: AUTO_INCREMENT, ENGINE=
                case 'a' << 8 | 'u', 'e' << 8 | 'n' -> attempt(F_MYSQL, i);
                // Oracle: NVARCHAR2, VARCHAR2, NUMBER(
                case 'n' << 8 | 'v', 'v' << 8 | 'a', 'n' << 8 | 'u' -> attempt(F_ORACLE, i);
                // DB2: GENERATED .. AS IDENTITY
                case 'g' << 8 | 'e' -> attempt(F_DB2_IDENTITY, i);
                default -> {
                }
            }
        }

        /**
         * 줄 처음 p: {@code ^\s*SET\s+DATABASE}(HSQL)는 줄 처음에서 시작해 공백을 넘긴 자리의 {@code SET}으로 이어진다. 공백 뒤의 글자가
         * {@code s}일 때만 맞춰 본다. 같은 공백 구간에 줄 처음이 여럿이어도(빈 줄) 공백 뒤의 자리가 같으니 한 번만 맞춰 본다.
         * p는 정규식이 보는 줄 처음이어야 한다(본문의 처음이거나 줄 끝 글자 바로 뒤. {@code \r\n}의 사이는 아니다).
         */
        private void lineStart(int p) {
            if ((pending & F_HSQL) == 0 || p >= length) {
                return;
            }
            if (p < blankFrom || p > blankTo) {
                blankFrom = p;
                blankTo = skipSpaces(p);
            }
            if (blankTo != noSetDatabaseAt && (charAt(blankTo) | 0x20) == 's') {
                attempt(F_HSQL, p);
                noSetDatabaseAt = blankTo;
            }
        }

        /**
         * {@code (}·{@code ,} 바로 뒤 j부터 {@code \s*"?이름"?\s+} 다음이 {@code s}인가: 컬럼 정의의 {@code 이름 serial}일 수 있는 자리다.
         * 값 목록의 {@code (1, 'a', ..}나 컬럼 목록의 {@code (id, name}은 여기서 걸러진다.
         */
        private boolean serialColumnAhead(int j) {
            j = skipSpaces(j);
            if (charAt(j) == '"') {
                j++;
            }
            char c = charAt(j);
            if (!asciiLetter(c) && c != '_') {
                return false;
            }
            do {
                j++;
            } while (wordChar(charAt(j)));
            if (charAt(j) == '"') {
                j++;
            }
            int afterName = j;
            j = skipSpaces(j);
            return j > afterName && (charAt(j) | 0x20) == 's';
        }

        /** 패턴을 i에 고정해 맞춰 본다. 맞으면 적고, 더 찾을 것이 없는 패턴은 그만 찾는다. */
        private void attempt(int feature, int i) {
            if ((pending & feature) == 0) {
                return;
            }
            java.util.regex.Matcher m = matcher(feature);
            m.region(i, length);
            if (!m.lookingAt()) {
                return;
            }
            if (feature != F_POSTGRES) {
                found |= feature;
                pending &= ~feature;
            } else if (m.start(1) >= 0) {
                postgres = PG_STRONG;
                pending &= ~F_POSTGRES;
            } else {
                postgres = PG_WEAK; // 강한 특징이 뒤에 있는지 계속 본다
                if (anyPostgres) {
                    pending &= ~F_POSTGRES;
                }
            }
        }

        private java.util.regex.Matcher matcher(int feature) {
            return switch (feature) {
                case F_HSQL -> hsql != null ? hsql : (hsql = anchored(HSQL_FEATURES));
                case F_MYSQL -> mysql != null ? mysql : (mysql = anchored(MYSQL_FEATURES));
                case F_POSTGRES -> postgresMatcher != null ? postgresMatcher : (postgresMatcher = anchored(POSTGRES_FEATURES));
                case F_ORACLE -> oracle != null ? oracle : (oracle = anchored(ORACLE_FEATURES));
                default -> db2Identity != null ? db2Identity : (db2Identity = anchored(DB2_IDENTITY));
            };
        }

        /**
         * 구간의 시작에 고정해 맞춰 보는 매처. 구간 밖도 본문 전체를 기준으로 본다: {@code \b}와 뒤돌아보기는 구간 앞의 글자를 보고,
         * {@code ^}는 구간의 시작이 아니라 실제 줄 처음에서만 맞는다. 그래서 그 자리에서의 답이 {@code find()}와 같다.
         */
        private java.util.regex.Matcher anchored(java.util.regex.Pattern pattern) {
            return pattern.matcher(text).useTransparentBounds(true).useAnchoringBounds(false);
        }

        /** i의 글자. 본문 밖이면 {@code \0}. */
        private char charAt(int i) {
            return i < length ? text.charAt(i) : '\0';
        }

        /** from부터 공백({@code \s}: 빈칸, 탭, 줄 끝 글자, 세로 탭, 폼 피드)을 넘긴 자리. */
        private int skipSpaces(int from) {
            int i = from;
            while (i < length) {
                char c = text.charAt(i);
                if (c != ' ' && (c < '\t' || c > '\r')) {
                    break;
                }
                i++;
            }
            return i;
        }

        private static boolean asciiLetter(char c) {
            int lower = c | 0x20;
            return c < 128 && lower >= 'a' && lower <= 'z';
        }

        /** 정규식의 낱말 글자({@code \w}): 영문자, 숫자, 밑줄. */
        private static boolean wordChar(char c) {
            return asciiLetter(c) || (c >= '0' && c <= '9') || c == '_';
        }
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

    /** 내용 판정으로 정한 읽기 방식: MySQL 특징이 먼저고, 그다음 PostgreSQL로 판정한 본문이다. */
    private static Syntax syntaxOfContent(Content content) {
        if (content.mysql()) {
            return Syntax.MYSQL;
        }
        return "postgres".equals(content.dialect()) ? Syntax.POSTGRES : Syntax.DEFAULT;
    }

    /** 본문을 보기 전에 정해지는 파일의 읽기 방식: 경로의 방언 이름 → {@code --dialect} → 프로젝트 방언. 정해지지 않으면 null. */
    private static Syntax syntaxBeforeContent(String pathToken, Dialect optionDialect, Syntax projectSyntax) {
        if (pathToken != null) {
            return syntaxOfName(pathToken);
        }
        Dialect option = fromOption(optionDialect);
        if (option != null) {
            return option == Dialect.POSTGRES ? Syntax.POSTGRES : Syntax.DEFAULT;
        }
        return projectSyntax;
    }

    /**
     * SQL 파일의 읽기 방식(F-019-R7). 경로의 방언 이름 → {@code --dialect} → 프로젝트 방언 → 본문의 내용 특징 순으로 정한다.
     * 읽기 방식은 여기와 {@link #syntaxOfEmbedded}에서만 정한다(ADR-0012).
     *
     * @param pathToken 경로에서 읽은 파일의 방언(표준 이름, {@link DialectSelection#dialectOfFile}). 없으면 null
     * @param optionDialect {@code --dialect}. 없으면(auto) null
     * @param content 파일 본문의 내용 판정({@link #content}). 파일의 방언을 정할 때 이미 구한 것을 넘긴다. 경로에 방언 이름이 있으면
     *                쓰지 않으므로 null이어도 된다
     * @param projectSyntax 프로젝트 방언이 정한 읽기 방식({@link DialectSelection#declaredSyntax}). 없으면 null
     */
    public static Syntax syntaxOfFile(String pathToken, Dialect optionDialect, Content content, Syntax projectSyntax) {
        Syntax decided = syntaxBeforeContent(pathToken, optionDialect, projectSyntax);
        return decided != null ? decided : syntaxOfContent(content);
    }

    /** {@link #syntaxOfFile(String, Dialect, Content, Syntax)}와 같되 본문을 받는다: 내용 판정이 필요할 때만 본문을 훑는다. */
    public static Syntax syntaxOfFile(String pathToken, Dialect optionDialect, String text, Syntax projectSyntax) {
        Syntax decided = syntaxBeforeContent(pathToken, optionDialect, projectSyntax);
        return decided != null ? decided : syntaxOfContent(content(text));
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
        return projectSyntax != null ? projectSyntax : syntaxOfContent(content(text));
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
