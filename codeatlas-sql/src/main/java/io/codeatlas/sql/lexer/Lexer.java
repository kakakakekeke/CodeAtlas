package io.codeatlas.sql.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Oracle·DB2 SQL(과 PL/SQL, SQL PL) 공용 렉서. 어떤 입력에도 예외를 던지지 않는다.
 *
 * <ul>
 *   <li>줄 끝은 LF, CRLF, CR 단독 셋 다 한 줄로 센다(F-006-R32). 줄 주석과 닫히지 않은 토큰도 그 줄 끝에서 끝난다.</li>
 *   <li>주석({@code --}, {@code /* *}{@code /})과 힌트는 {@link #tokenize}에서 빠지고 {@link #tokenizeWithComments}에서만 나온다.</li>
 *   <li>닫히지 않은 문자열·따옴표 식별자는 그 줄 끝에서 끊고 {@link Token#unterminated()}로 표시한다.</li>
 *   <li>MyBatis 잔재: {@code #{..}}는 BIND, {@code ${..}}는 {@link Token#DYNAMIC} 식별자(앞뒤 이름 글자와 붙으면 한 식별자)다.</li>
 *   <li>범위 밖 방언의 흔한 표기도 받아 준다(F-006-R18): MySQL 백틱 식별자는 따옴표 식별자, PostgreSQL 캐스트 {@code ::}는 기호 하나다.</li>
 *   <li>PostgreSQL 읽기 방식({@link Syntax#POSTGRES})에서만(F-019-R9, F-019-R10): 달러 인용({@code $$..$$}, {@code $태그$..$태그$})은 문자열 토큰 하나,
 *       {@code E'..'}는 백슬래시 이스케이프를 쓰는 문자열, {@code $1}은 BIND, {@code -> ->> #> #>> @> <@ && ~* !~ !~* @@}는 기호 하나이고
 *       {@code #}은 이름의 글자가 아니다.
 *       대문자가 없는 따옴표 이름({@code "orders"})은 본문을 대문자로 바꿔 따옴표 없는 이름과 같은 이름으로 낸다. 다른 읽기 방식에서는 이 글자들을 전처럼 읽는다(F-019-R8).</li>
 * </ul>
 */
public final class Lexer {

    private static final String[] TWO_CHAR_SYMBOLS = {
        "||", "!=", "<>", "^=", "<=", ">=", ":=", "=>", "..", "**", "<<", ">>", "~=", "::"
    };

    /** PostgreSQL 읽기 방식에서만 기호 하나로 읽는 연산자(F-019-R10). 긴 것부터 맞춘다. */
    private static final String[] POSTGRES_SYMBOLS = {
        "->>", "#>>", "!~*", "->", "#>", "@>", "<@", "&&", "~*", "!~", "@@"
    };

    private final String src;
    private final int length;
    private final boolean keepComments;
    /** 이 텍스트의 읽기 방식(ADR-0012). 방언끼리 겹치는 표기를 이것으로 가른다. */
    private final Syntax syntax;
    /** 줄 처음의 {@code #}을 줄 주석으로 읽는가(MySQL로 판정한 SQL에서만, F-006-R27). */
    private final boolean hashComments;
    /** PostgreSQL 읽기 방식인가: 달러 인용, {@code E'..'}, {@code $1}, PostgreSQL 연산자를 읽고 {@code #}을 이름의 글자로 보지 않는다(F-019-R9·R10). */
    private final boolean postgres;
    /**
     * psql 스크립트로 읽는가(PostgreSQL 읽기 방식의 SQL 파일, F-019-R11): {@code COPY … FROM stdin;} 뒤의 데이터 줄을 줄 처음의 {@code \.}까지
     * 건너뛰고, 줄 처음이 {@code \}인 메타 명령 줄을 건너뛴다. 구문 조각이나 내장 소스를 읽을 때는 켜지 않는다.
     */
    private final boolean psqlScript;
    /** psql 스크립트: 다음 토큰이 구문의 첫 토큰이다. */
    private boolean statementStart = true;
    /** psql 스크립트: 지금 구문이 COPY로 시작했다. */
    private boolean copyStatement;
    /** psql 스크립트: 지금 COPY 구문에서 (괄호 밖의) {@code FROM stdin}을 봤다. */
    private boolean copyFromStdin;
    private boolean afterCopyFrom;
    private int copyParens;
    private final List<Token> out = new ArrayList<>();
    /** 마지막으로 내보낸 토큰(주석 포함). */
    private Token last;
    private int pos;
    private int line = 1;
    private int lineStart;
    private boolean lineHasToken;
    private boolean blankPending;

    private Lexer(String src, boolean keepComments, Syntax syntax) {
        this(src, keepComments, syntax, false);
    }

    private Lexer(String src, boolean keepComments, Syntax syntax, boolean script) {
        this.src = src == null ? "" : src;
        this.length = this.src.length();
        this.keepComments = keepComments;
        this.syntax = syntax == null ? Syntax.DEFAULT : syntax;
        this.hashComments = this.syntax == Syntax.MYSQL;
        this.postgres = this.syntax == Syntax.POSTGRES;
        this.psqlScript = script && this.postgres;
    }

    /** 주석을 뺀 토큰 목록(기본 읽기 방식). 마지막은 항상 EOF다. */
    public static List<Token> tokenize(String sql) {
        return tokenize(sql, Syntax.DEFAULT);
    }

    /**
     * 주석을 뺀 토큰 목록.
     *
     * @param syntax 읽기 방식. {@link Syntax#MYSQL}이면 줄 처음의 {@code #}을 줄 주석으로 읽는다(F-006-R27). 기본이면 {@code #}은 이름의 일부다
     */
    public static List<Token> tokenize(String sql, Syntax syntax) {
        return new Lexer(sql, false, syntax).run();
    }

    /**
     * 원문에서 잘라 낸 조각을 렉싱하되 토큰의 위치는 원문 기준으로 돌려준다(주석 제외, 마지막은 EOF).
     * 큰 스크립트의 구문을 토큰 없이 본문만 쥐고 있다가 필요할 때 다시 렉싱하는 데 쓴다(F-006-R15).
     *
     * @param offset 조각의 첫 글자가 원문에서 놓인 위치(0부터)
     * @param line 조각의 첫 글자가 놓인 줄(1부터)
     * @param column 조각의 첫 글자가 놓인 열(1부터)
     */
    public static List<Token> tokenizeAt(String fragment, int offset, int line, int column) {
        return tokenizeAt(fragment, offset, line, column, Syntax.DEFAULT);
    }

    /** {@link #tokenizeAt(String, int, int, int)}와 같되 조각을 주어진 읽기 방식으로 읽는다(원문을 읽은 방식과 같아야 한다). */
    public static List<Token> tokenizeAt(String fragment, int offset, int line, int column, Syntax syntax) {
        List<Token> tokens = new Lexer(fragment, false, syntax).run();
        if (offset == 0 && line == 1 && column == 1) {
            return tokens;
        }
        List<Token> moved = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            moved.add(t.moved(offset, line - 1, column - 1));
        }
        return moved;
    }

    /**
     * 달러 인용 문자열 토큰의 안쪽 본문을 다시 토큰으로 만든다. 토큰의 위치(offset·줄·열)는 원문 기준이다(F-019-R25의 본문 위치).
     * 달러 인용이 아닌 토큰이면 EOF뿐인 목록이다.
     *
     * @param dollarQuoted {@link Token#dollarQuoted()}인 토큰
     * @param syntax 본문을 읽을 방식(보통 원문을 읽은 방식 그대로, {@link Syntax#POSTGRES})
     */
    public static List<Token> tokenizeBody(Token dollarQuoted, Syntax syntax) {
        String body = dollarQuoted.dollarBody();
        return tokenizeAt(body == null ? "" : body, dollarQuoted.bodyOffset(), dollarQuoted.bodyLine(), dollarQuoted.bodyColumn(), syntax);
    }

    /** 주석 토큰을 포함한 목록(스크립트 분할기가 지시문 주석을 읽을 때 쓴다). 마지막은 항상 EOF다. */
    public static List<Token> tokenizeWithComments(String sql) {
        return new Lexer(sql, true, Syntax.DEFAULT).run();
    }

    /**
     * 토큰을 하나씩 내주는 렉서. 큰 스크립트를 토큰 목록으로 통째로 만들지 않고 흘려 읽을 때 쓴다(F-006-R15).
     * {@link #next()}는 끝에 이르면 EOF 토큰을 계속 돌려준다.
     */
    public static final class Stream {
        private final Lexer lexer;
        private int cursor;
        private boolean finished;
        private Token eof;

        private Stream(Lexer lexer) {
            this.lexer = lexer;
        }

        public Token next() {
            while (true) {
                if (cursor < lexer.out.size()) {
                    Token t = lexer.out.get(cursor++);
                    if (cursor == lexer.out.size()) {
                        lexer.out.clear();
                        cursor = 0;
                    }
                    if (t.isEof()) {
                        eof = t;
                    }
                    return t;
                }
                if (finished) {
                    return eof;
                }
                if (!lexer.step()) {
                    lexer.finish();
                    finished = true;
                }
            }
        }
    }

    /** 흘려 읽는 렉서를 만든다(기본 읽기 방식). keepComments면 주석 토큰도 나온다. */
    public static Stream stream(String sql, boolean keepComments) {
        return stream(sql, keepComments, Syntax.DEFAULT);
    }

    /** {@link #stream(String, boolean)}과 같되 주어진 읽기 방식으로 읽는다. */
    public static Stream stream(String sql, boolean keepComments, Syntax syntax) {
        return new Stream(new Lexer(sql, keepComments, syntax));
    }

    /**
     * SQL 스크립트 파일을 흘려 읽는 렉서(주석 포함). PostgreSQL 읽기 방식이면 psql 스크립트의 규칙도 읽는다(F-019-R11):
     * {@code COPY … FROM stdin;} 뒤의 데이터 줄은 줄 처음의 {@code \.}까지 구문으로 읽지 않고 {@link Token#copyDataRows()}가 줄 수인
     * 주석 종류 토큰 하나로 내며, 줄 처음이 {@code \}인 메타 명령 줄({@code \connect} 등)은 주석으로 낸다.
     */
    public static Stream scriptStream(String sql, Syntax syntax) {
        return new Stream(new Lexer(sql, true, syntax, true));
    }

    private List<Token> run() {
        while (step()) {
            // 토큰 하나씩
        }
        finish();
        return out;
    }

    private void finish() {
        int col = pos - lineStart + 1;
        add(new Token(TokenType.EOF, "", "", length, length, line, col, line, col, false, !lineHasToken, blankPending));
    }

    private void add(Token token) {
        out.add(token);
        last = token;
        if (psqlScript && token.type() != TokenType.COMMENT) {
            trackCopy(token);
        }
    }

    /**
     * psql 스크립트에서 {@code COPY 이름 [(..)] FROM stdin [..];}을 따라간다: 그 구문을 끝내는 {@code ;} 바로 뒤가 데이터 구간이다(F-019-R11).
     * 구문은 {@code ;}에서 끝난다고 본다(달러 인용과 문자열 안의 {@code ;}는 토큰 안이라 여기 오지 않는다).
     */
    private void trackCopy(Token t) {
        if (t.isSymbol(";")) {
            boolean data = copyFromStdin;
            statementStart = true;
            copyStatement = false;
            copyFromStdin = false;
            if (data) {
                lexCopyData();
            }
            return;
        }
        if (statementStart) {
            statementStart = false;
            copyStatement = t.is("COPY");
            afterCopyFrom = false;
            copyParens = 0;
            return;
        }
        if (!copyStatement) {
            return;
        }
        if (t.isSymbol("(")) {
            copyParens++;
        } else if (t.isSymbol(")")) {
            copyParens = Math.max(0, copyParens - 1);
        } else if (copyParens == 0) {
            if (afterCopyFrom && t.is("STDIN")) {
                copyFromStdin = true;
            }
            afterCopyFrom = t.is("FROM");
        }
    }

    /**
     * {@code COPY … FROM stdin;} 뒤의 데이터 구간을 건너뛴다: 그 줄의 나머지는 버리고, 다음 줄부터 줄 처음의 {@code \.}(그 줄에 그것뿐)까지가
     * 데이터 줄이다. {@code \.} 없이 입력이 끝나면 끝까지다. 주석을 내는 렉서면 데이터 줄 수를 든 토큰 하나를 낸다(본문은 쥐지 않는다).
     */
    private void lexCopyData() {
        advanceTo(lineBreakEnd(lineEnd(pos)));
        int start = pos;
        int startLine = line;
        int startCol = pos - lineStart + 1;
        int rows = 0;
        boolean closed = false;
        while (pos < length) {
            int end = lineEnd(pos);
            int contentEnd = end;
            while (contentEnd > pos && (src.charAt(contentEnd - 1) == ' ' || src.charAt(contentEnd - 1) == '\t')) {
                contentEnd--;
            }
            if (contentEnd - pos == 2 && src.charAt(pos) == '\\' && src.charAt(pos + 1) == '.') {
                pos = end;
                closed = true;
                break;
            }
            rows++;
            advanceTo(lineBreakEnd(end));
        }
        lineHasToken = false;
        if (keepComments) {
            int endCol = Math.max(1, pos - lineStart + 1);
            out.add(new Token(TokenType.COMMENT, closed ? "\\." : "", null, start, pos, startLine, startCol, line, endCol, !closed, false, false, rows));
        }
    }

    /** i가 줄 끝 글자(CR, LF, CRLF)면 그 다음 위치, 아니면 i. */
    private int lineBreakEnd(int i) {
        if (i < length && src.charAt(i) == '\r') {
            i++;
        }
        if (i < length && src.charAt(i) == '\n') {
            i++;
        }
        return i;
    }

    /** 토큰(또는 버리는 주석) 하나를 읽는다. 입력이 끝났으면 false. */
    private boolean step() {
        {
            skipWhitespace();
            if (pos >= length) {
                return false;
            }
            int start = pos;
            int startLine = line;
            int startCol = pos - lineStart + 1;
            char c = src.charAt(pos);
            char n = pos + 1 < length ? src.charAt(pos + 1) : '\0';
            if (c == '-' && n == '-') {
                pos = lineEnd(pos);
                emitComment(start, startLine, startCol, false);
            } else if (c == '/' && n == '*') {
                int end = src.indexOf("*/", pos + 2);
                boolean open = end < 0;
                advanceTo(open ? length : end + 2);
                emitComment(start, startLine, startCol, open);
            } else if (hashComments && c == '#' && n != '{' && !lineHasToken) {
                // MySQL의 줄 주석: 줄 처음의 # (MyBatis #{..}와 이름 안의 #은 아니다). MySQL로 판정한 SQL에서만 본다:
                // DB2·MSSQL에서는 #으로 시작하는 이름이 있다(F-006-R27)
                pos = lineEnd(pos);
                emitComment(start, startLine, startCol, false);
            } else if (psqlScript && c == '\\' && !lineHasToken) {
                // psql 메타 명령(\connect, \restrict ..): 줄 처음의 \부터 그 줄 끝까지 건너뛴다(F-019-R11)
                pos = lineEnd(pos);
                emitComment(start, startLine, startCol, false);
            } else if (c == '\'') {
                lexString(start, startLine, startCol, pos);
            } else if (c == '"') {
                lexQuotedIdent(start, startLine, startCol);
            } else if (c == '`') {
                lexBacktickIdent(start, startLine, startCol);
            } else if (isDigit(c) || (c == '.' && isDigit(n))) {
                lexNumber(start, startLine, startCol);
            } else if (c == '#' && n == '{') {
                lexBraced(start, startLine, startCol, TokenType.BIND);
            } else if (postgres && c == '$' && n != '{' && lexDollar(start, startLine, startCol)) {
                // 달러 인용 문자열 또는 위치 매개변수 $1 (PostgreSQL 읽기 방식에서만, F-019-R9·R10)
            } else if (stringPrefixLength() > 0) {
                lexString(start, startLine, startCol, pos + stringPrefixLength());
            } else if (isIdentStart(c) || (c == '$' && n == '{') || (c == '#' && !postgres && hashStartsName())) {
                lexIdent(start, startLine, startCol);
            } else if (c == '?') {
                pos++;
                emit(TokenType.BIND, start, startLine, startCol, null, false);
            } else if (c == ':' && isLabelColon()) {
                lexSymbol(start, startLine, startCol, c);
            } else if (c == ':' && n != '=' && (isIdentPart(n) && n != '$' && n != '#')) {
                pos++;
                while (pos < length && identPart(src.charAt(pos))) {
                    pos++;
                }
                emit(TokenType.BIND, start, startLine, startCol, null, false);
            } else {
                lexSymbol(start, startLine, startCol, c);
            }
        }
        return true;
    }

    /**
     * 현재 위치의 {@code :}이 DB2 레이블의 끝인가: 바로 앞에 이름이 붙어 있고 바로 뒤가 블록을 여는 키워드다({@code L1:LOOP}).
     * 이 경우 {@code :LOOP}를 바인드로 읽지 않는다.
     */
    private boolean isLabelColon() {
        Token prev = last;
        if (prev == null || prev.type() != TokenType.IDENT || prev.endOffset() != pos) {
            return false;
        }
        int end = pos + 1;
        while (end < length && identPart(src.charAt(end))) {
            end++;
        }
        String word = src.substring(pos + 1, end).toUpperCase(Locale.ROOT);
        return word.equals("LOOP") || word.equals("WHILE") || word.equals("FOR") || word.equals("REPEAT") || word.equals("BEGIN");
    }

    /** i의 글자가 줄을 끝내는가: LF, 또는 뒤에 LF가 없는 CR(CRLF는 LF에서 한 번만 센다, F-006-R32). */
    private boolean isLineBreak(int i) {
        char c = src.charAt(i);
        return c == '\n' || (c == '\r' && (i + 1 >= length || src.charAt(i + 1) != '\n'));
    }

    /** from부터 처음 나오는 줄 끝 글자(CR 또는 LF)의 위치. 없으면 입력의 끝. */
    private int lineEnd(int from) {
        int i = Math.max(0, from);
        while (i < length) {
            char c = src.charAt(i);
            if (c == '\n' || c == '\r') {
                break;
            }
            i++;
        }
        return Math.min(i, length);
    }

    private void skipWhitespace() {
        int newlines = 0;
        while (pos < length) {
            char c = src.charAt(pos);
            if (isLineBreak(pos)) {
                newlines++;
                pos++;
                line++;
                lineStart = pos;
                lineHasToken = false;
            } else if (Character.isWhitespace(c) || c == '﻿' || c == ' ') {
                pos++;
            } else {
                break;
            }
        }
        if (newlines >= 2) {
            blankPending = true;
        }
    }

    /** pos를 target까지 옮기며 줄 번호를 센다. */
    private void advanceTo(int target) {
        while (pos < target) {
            if (isLineBreak(pos)) {
                line++;
                lineStart = pos + 1;
            }
            pos++;
        }
    }

    private void emitComment(int start, int startLine, int startCol, boolean unterminated) {
        if (!keepComments) {
            return;
        }
        int endCol = Math.max(1, pos - lineStart + 1);
        add(new Token(TokenType.COMMENT, src.substring(start, pos), null, start, pos, startLine, startCol,
                line, endCol, unterminated, false, false));
    }

    private void emit(TokenType type, int start, int startLine, int startCol, String upper, boolean unterminated) {
        int endCol = Math.max(1, pos - lineStart + 1);
        add(new Token(type, src.substring(start, pos), upper, start, pos, startLine, startCol,
                line, endCol, unterminated, !lineHasToken, blankPending));
        lineHasToken = true;
        blankPending = false;
    }

    /**
     * 현재 위치가 문자열 접두사(N, X, B, G, U&amp;, Q, NQ)이고 바로 뒤가 따옴표면 접두사 길이, 아니면 0.
     * PostgreSQL 읽기 방식에서는 이스케이프 문자열의 {@code E}도 접두사다(F-019-R10).
     */
    private int stringPrefixLength() {
        char c = Character.toUpperCase(src.charAt(pos));
        char n = pos + 1 < length ? src.charAt(pos + 1) : '\0';
        char n2 = pos + 2 < length ? src.charAt(pos + 2) : '\0';
        if ((c == 'N' || c == 'X' || c == 'G' || c == 'Q' || c == 'B' || (c == 'E' && postgres)) && n == '\'') {
            return 1;
        }
        if (c == 'N' && Character.toUpperCase(n) == 'Q' && n2 == '\'') {
            return 2;
        }
        if (c == 'U' && n == '&' && n2 == '\'') {
            return 2;
        }
        return 0;
    }

    /** quotePos는 여는 따옴표 위치. */
    private void lexString(int start, int startLine, int startCol, int quotePos) {
        boolean qQuote = quotePos > start && Character.toUpperCase(src.charAt(quotePos - 1)) == 'Q';
        if (qQuote && quotePos + 1 < length) {
            char open = src.charAt(quotePos + 1);
            char close = switch (open) {
                case '[' -> ']';
                case '{' -> '}';
                case '(' -> ')';
                case '<' -> '>';
                default -> open;
            };
            int end = src.indexOf(close + "'", quotePos + 2);
            if (end >= 0) {
                advanceTo(end + 2);
                emit(TokenType.STRING, start, startLine, startCol, null, false);
                return;
            }
            endAtLineEnd(quotePos + 1);
            emit(TokenType.STRING, start, startLine, startCol, null, true);
            return;
        }
        // PostgreSQL의 E'..'만 백슬래시 이스케이프를 쓴다. 접두사 없는 문자열의 백슬래시는 글자 그대로다(F-019-R10)
        boolean backslash = hashComments || (postgres && quotePos == start + 1 && Character.toUpperCase(src.charAt(start)) == 'E');
        int i = quotePos + 1;
        while (i < length) {
            char c = src.charAt(i);
            if (c == '\\' && backslash && i + 1 < length) {
                // MySQL의 백슬래시 이스케이프('it\'s'): MySQL로 판정한 입력에서만 받는다(Oracle·DB2의 ESCAPE '\'와 충돌한다, F-006-R36)
                i += 2;
                continue;
            }
            if (c == '\'') {
                if (i + 1 < length && src.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                advanceTo(i + 1);
                emit(TokenType.STRING, start, startLine, startCol, null, false);
                return;
            }
            i++;
        }
        endAtLineEnd(quotePos + 1);
        emit(TokenType.STRING, start, startLine, startCol, null, true);
    }

    /**
     * PostgreSQL 읽기 방식의 {@code $}: 숫자가 따르면 위치 매개변수({@code $1}, BIND), {@code $$}나 {@code $태그$}면 달러 인용 문자열이다.
     * 달러 인용은 같은 구분자가 다시 나올 때까지가 문자열 토큰 하나이고 안의 {@code ;}·따옴표·다른 태그는 본문이다. 닫는 구분자가 없으면
     * 입력 끝까지를 닫히지 않은 문자열로 낸다(PostgreSQL도 끝까지 문자열로 읽는다. 줄 끝에서 끊으면 본문의 글자가 구문으로 읽힌다).
     *
     * @return 토큰을 냈으면 true. 홀로 있는 {@code $}면 false(기호로 읽는다)
     */
    private boolean lexDollar(int start, int startLine, int startCol) {
        int p = pos + 1;
        if (p < length && isDigit(src.charAt(p))) {
            while (p < length && isDigit(src.charAt(p))) {
                p++;
            }
            pos = p;
            emit(TokenType.BIND, start, startLine, startCol, null, false);
            return true;
        }
        if (p < length && isIdentStart(src.charAt(p))) {
            p++;
            while (p < length && (src.charAt(p) == '_' || Character.isLetterOrDigit(src.charAt(p)))) {
                p++;
            }
        }
        if (p >= length || src.charAt(p) != '$') {
            return false;
        }
        String delimiter = src.substring(pos, p + 1);
        int close = src.indexOf(delimiter, p + 1);
        advanceTo(close < 0 ? length : close + delimiter.length());
        emit(TokenType.STRING, start, startLine, startCol, null, close < 0);
        return true;
    }

    /** 닫히지 않은 토큰을 그 줄 끝에서 끊는다. */
    private void endAtLineEnd(int from) {
        pos = Math.max(lineEnd(from), from);
    }

    /**
     * 큰따옴표로 감싼 식별자. 본문은 원문 그대로 둔다. PostgreSQL 읽기 방식에서만, 본문에 대문자가 없으면 본문을 대문자로 바꾼
     * {@code "NAME"}으로 둔다(위치는 따옴표를 포함한 원문 범위다): PostgreSQL은 따옴표 없는 이름을 소문자로 접으므로 {@code "orders"}와
     * {@code orders}가 같은 객체인데, 이 도구는 따옴표 없는 이름을 대문자로 정규화한다. 대문자가 하나라도 든 이름({@code "camelCase"},
     * {@code "ORDERS"})은 PostgreSQL에서도 따옴표 없는 이름과 다른 객체라 원문 그대로다. 닫히지 않으면 그 줄 끝에서 끊는다.
     */
    private void lexQuotedIdent(int start, int startLine, int startCol) {
        int i = pos + 1;
        while (i < length) {
            char c = src.charAt(i);
            if (c == '\n' || c == '\r') {
                break;
            }
            if (c == '"') {
                if (i + 1 < length && src.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                pos = i + 1;
                String folded = postgres ? foldedQuotedName(src.substring(start + 1, i)) : null;
                if (folded == null) {
                    emit(TokenType.QUOTED_IDENT, start, startLine, startCol, null, false);
                } else {
                    int endCol = Math.max(1, pos - lineStart + 1);
                    add(new Token(TokenType.QUOTED_IDENT, "\"" + folded + "\"", null, start, pos, startLine, startCol,
                            line, endCol, false, !lineHasToken, blankPending));
                    lineHasToken = true;
                    blankPending = false;
                }
                return;
            }
            i++;
        }
        endAtLineEnd(pos + 1);
        emit(TokenType.QUOTED_IDENT, start, startLine, startCol, null, true);
    }

    /**
     * PostgreSQL에서 따옴표 없는 이름과 같은 이름이 되는 따옴표 이름의 본문(대문자가 없는 것)이면 대문자로 바꾼 본문, 아니면 null.
     * 따옴표 없는 이름의 정규화(대문자)와 같은 값이 되게 한다.
     */
    public static String foldedQuotedName(String inner) {
        if (!inner.equals(inner.toLowerCase(Locale.ROOT))) {
            return null; // 대문자가 있다: 원문 그대로
        }
        String upper = inner.toUpperCase(Locale.ROOT);
        return upper.equals(inner) ? null : upper;
    }

    /**
     * MySQL 백틱 식별자({@code `name`}). 따옴표 식별자 토큰으로 만들되, MySQL에서는 이 이름이 대소문자를 가리지 않고 매퍼가 따옴표 없이
     * 같은 이름을 쓰므로 본문은 대문자로 바꾼 {@code "NAME"}으로 둔다(위치는 백틱을 포함한 원문 범위다). 닫히지 않으면 그 줄 끝에서 끊는다.
     */
    private void lexBacktickIdent(int start, int startLine, int startCol) {
        int lineEnd = lineEnd(pos + 1);
        int close = src.indexOf('`', pos + 1);
        boolean closed = close >= 0 && close < lineEnd;
        int innerEnd;
        if (closed) {
            innerEnd = close;
            pos = close + 1;
        } else {
            endAtLineEnd(pos + 1);
            innerEnd = pos;
        }
        String inner = src.substring(start + 1, innerEnd).toUpperCase(Locale.ROOT).replace("\"", "\"\"");
        int endCol = Math.max(1, pos - lineStart + 1);
        add(new Token(TokenType.QUOTED_IDENT, "\"" + inner + "\"", null, start, pos, startLine, startCol,
                line, endCol, !closed, !lineHasToken, blankPending));
        lineHasToken = true;
        blankPending = false;
    }

    private void lexNumber(int start, int startLine, int startCol) {
        if (src.charAt(pos) == '0' && pos + 2 < length && (src.charAt(pos + 1) == 'x' || src.charAt(pos + 1) == 'X')
                && Character.digit(src.charAt(pos + 2), 16) >= 0) {
            // MySQL·MSSQL의 16진 리터럴 0x89AB..(BLOB 덤프)
            pos += 2;
            while (pos < length && Character.digit(src.charAt(pos), 16) >= 0) {
                pos++;
            }
            emit(TokenType.NUMBER, start, startLine, startCol, null, false);
            return;
        }
        while (pos < length && isDigit(src.charAt(pos))) {
            pos++;
        }
        if (pos < length && src.charAt(pos) == '.' && !(pos + 1 < length && src.charAt(pos + 1) == '.')) {
            pos++;
            while (pos < length && isDigit(src.charAt(pos))) {
                pos++;
            }
        }
        if (pos < length && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            int p = pos + 1;
            if (p < length && (src.charAt(p) == '+' || src.charAt(p) == '-')) {
                p++;
            }
            if (p < length && isDigit(src.charAt(p))) {
                while (p < length && isDigit(src.charAt(p))) {
                    p++;
                }
                pos = p;
            }
        }
        emit(TokenType.NUMBER, start, startLine, startCol, null, false);
    }

    /** {@code #{..}} 또는 {@code ${..}}를 닫는 중괄호까지 읽는다. 닫히지 않으면 줄 끝까지. */
    private void lexBraced(int start, int startLine, int startCol, TokenType type) {
        boolean closed = skipBraced();
        emit(type, start, startLine, startCol, null, !closed);
    }

    private boolean skipBraced() {
        int close = src.indexOf('}', pos + 2);
        if (close < 0 || lineEnd(pos + 2) < close) {
            endAtLineEnd(pos + 2);
            return false;
        }
        pos = close + 1;
        return true;
    }

    /** 현재 위치의 {@code #}이 이름을 여는가: {@code #TMP}, {@code ##G_TMP}(DB2·MSSQL의 임시 테이블 이름). {@code #{..}}와 홀로 있는 #은 아니다. */
    private boolean hashStartsName() {
        int p = pos + 1;
        if (p < length && src.charAt(p) == '#') {
            p++;
        }
        return p < length && isIdentStart(src.charAt(p));
    }

    private void lexIdent(int start, int startLine, int startCol) {
        StringBuilder upper = new StringBuilder();
        boolean unterminated = false;
        while (pos < length) {
            char c = src.charAt(pos);
            char n = pos + 1 < length ? src.charAt(pos + 1) : '\0';
            if (c == '$' && n == '{') {
                if (!skipBraced()) {
                    unterminated = true;
                }
                upper.append(Token.DYNAMIC);
            } else if (c == '#' && n == '{') {
                break;
            } else if (identPart(c)) {
                upper.append(c);
                pos++;
            } else {
                break;
            }
        }
        emit(TokenType.IDENT, start, startLine, startCol, upper.toString().toUpperCase(Locale.ROOT), unterminated);
    }

    private void lexSymbol(int start, int startLine, int startCol, char c) {
        if (c == '(') {
            // (+) : 사이 공백 허용
            int p = pos + 1;
            while (p < length && (src.charAt(p) == ' ' || src.charAt(p) == '\t')) {
                p++;
            }
            if (p < length && src.charAt(p) == '+') {
                p++;
                while (p < length && (src.charAt(p) == ' ' || src.charAt(p) == '\t')) {
                    p++;
                }
                if (p < length && src.charAt(p) == ')') {
                    pos = p + 1;
                    add(new Token(TokenType.SYMBOL, "(+)", null, start, pos, startLine, startCol,
                            line, Math.max(1, pos - lineStart + 1), false, !lineHasToken, blankPending));
                    lineHasToken = true;
                    blankPending = false;
                    return;
                }
            }
        }
        if (postgres) {
            for (String s : POSTGRES_SYMBOLS) {
                if (src.startsWith(s, pos)) {
                    pos += s.length();
                    emit(TokenType.SYMBOL, start, startLine, startCol, null, false);
                    return;
                }
            }
        }
        if (pos + 1 < length) {
            String two = src.substring(pos, pos + 2);
            for (String s : TWO_CHAR_SYMBOLS) {
                if (s.equals(two)) {
                    pos += 2;
                    emit(TokenType.SYMBOL, start, startLine, startCol, null, false);
                    return;
                }
            }
        }
        pos++;
        emit(TokenType.SYMBOL, start, startLine, startCol, null, false);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || c == '$' || c == '#' || Character.isLetterOrDigit(c);
    }

    /** 이 읽기 방식에서 이름에 올 수 있는 글자인가: PostgreSQL에서는 {@code #}이 연산자의 글자다({@code #>}, {@code #>>}). {@code $}는 어디서나 이름의 글자다. */
    private boolean identPart(char c) {
        return isIdentPart(c) && !(postgres && c == '#');
    }
}
