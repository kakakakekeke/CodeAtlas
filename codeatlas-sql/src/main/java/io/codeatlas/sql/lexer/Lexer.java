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
 * </ul>
 */
public final class Lexer {

    private static final String[] TWO_CHAR_SYMBOLS = {
        "||", "!=", "<>", "^=", "<=", ">=", ":=", "=>", "..", "**", "<<", ">>", "~=", "::"
    };

    private final String src;
    private final int length;
    private final boolean keepComments;
    /** 줄 처음의 {@code #}을 줄 주석으로 읽는가(MySQL로 판정한 SQL에서만, F-006-R27). */
    private final boolean hashComments;
    private final List<Token> out = new ArrayList<>();
    /** 마지막으로 내보낸 토큰(주석 포함). */
    private Token last;
    private int pos;
    private int line = 1;
    private int lineStart;
    private boolean lineHasToken;
    private boolean blankPending;

    private Lexer(String src, boolean keepComments, boolean hashComments) {
        this.src = src == null ? "" : src;
        this.length = this.src.length();
        this.keepComments = keepComments;
        this.hashComments = hashComments;
    }

    /** 주석을 뺀 토큰 목록. 마지막은 항상 EOF다. */
    public static List<Token> tokenize(String sql) {
        return new Lexer(sql, false, false).run();
    }

    /**
     * 주석을 뺀 토큰 목록.
     *
     * @param mysql MySQL로 판정한 SQL이면 true: 줄 처음의 {@code #}을 줄 주석으로 읽는다(F-006-R27). 아니면 {@code #}은 이름의 일부다
     */
    public static List<Token> tokenize(String sql, boolean mysql) {
        return new Lexer(sql, false, mysql).run();
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
        return tokenizeAt(fragment, offset, line, column, false);
    }

    /** {@link #tokenizeAt(String, int, int, int)}와 같되 MySQL로 판정한 SQL이면 줄 처음의 {@code #}을 주석으로 읽는다. */
    public static List<Token> tokenizeAt(String fragment, int offset, int line, int column, boolean mysql) {
        List<Token> tokens = new Lexer(fragment, false, mysql).run();
        if (offset == 0 && line == 1 && column == 1) {
            return tokens;
        }
        List<Token> moved = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            moved.add(t.moved(offset, line - 1, column - 1));
        }
        return moved;
    }

    /** 주석 토큰을 포함한 목록(스크립트 분할기가 지시문 주석을 읽을 때 쓴다). 마지막은 항상 EOF다. */
    public static List<Token> tokenizeWithComments(String sql) {
        return new Lexer(sql, true, false).run();
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

    /** 흘려 읽는 렉서를 만든다. keepComments면 주석 토큰도 나온다. */
    public static Stream stream(String sql, boolean keepComments) {
        return new Stream(new Lexer(sql, keepComments, false));
    }

    /** {@link #stream(String, boolean)}과 같되 MySQL로 판정한 SQL이면 줄 처음의 {@code #}을 주석으로 읽는다. */
    public static Stream stream(String sql, boolean keepComments, boolean mysql) {
        return new Stream(new Lexer(sql, keepComments, mysql));
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
            } else if (stringPrefixLength() > 0) {
                lexString(start, startLine, startCol, pos + stringPrefixLength());
            } else if (isIdentStart(c) || (c == '$' && n == '{') || (c == '#' && hashStartsName())) {
                lexIdent(start, startLine, startCol);
            } else if (c == '?') {
                pos++;
                emit(TokenType.BIND, start, startLine, startCol, null, false);
            } else if (c == ':' && isLabelColon()) {
                lexSymbol(start, startLine, startCol, c);
            } else if (c == ':' && n != '=' && (isIdentPart(n) && n != '$' && n != '#')) {
                pos++;
                while (pos < length && isIdentPart(src.charAt(pos))) {
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
        while (end < length && isIdentPart(src.charAt(end))) {
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

    /** 현재 위치가 문자열 접두사(N, X, B, G, U&amp;, Q, NQ)이고 바로 뒤가 따옴표면 접두사 길이, 아니면 0. */
    private int stringPrefixLength() {
        char c = Character.toUpperCase(src.charAt(pos));
        char n = pos + 1 < length ? src.charAt(pos + 1) : '\0';
        char n2 = pos + 2 < length ? src.charAt(pos + 2) : '\0';
        if ((c == 'N' || c == 'X' || c == 'G' || c == 'Q' || c == 'B') && n == '\'') {
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
        int i = quotePos + 1;
        while (i < length) {
            char c = src.charAt(i);
            if (c == '\\' && hashComments && i + 1 < length) {
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

    /** 닫히지 않은 토큰을 그 줄 끝에서 끊는다. */
    private void endAtLineEnd(int from) {
        pos = Math.max(lineEnd(from), from);
    }

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
                emit(TokenType.QUOTED_IDENT, start, startLine, startCol, null, false);
                return;
            }
            i++;
        }
        endAtLineEnd(pos + 1);
        emit(TokenType.QUOTED_IDENT, start, startLine, startCol, null, true);
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
            } else if (isIdentPart(c)) {
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
}
