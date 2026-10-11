package io.codeatlas.sql.lexer;

import java.util.Locale;

/**
 * 위치가 있는 SQL 토큰. 줄과 열은 1부터이고 끝 위치는 토큰의 마지막 글자 다음(제외)이다. offset은 원문 문자열 기준이다.
 */
public final class Token {

    /** MyBatis {@code ${}} 치환 자리를 나타내는 식별자 조각. */
    public static final String DYNAMIC = "__DYNAMIC__";

    private final TokenType type;
    private final String text;
    private final String upper;
    private final int offset;
    private final int endOffset;
    private final int line;
    private final int column;
    private final int endLine;
    private final int endColumn;
    private final boolean unterminated;
    private final boolean firstOnLine;
    private final boolean blankLineBefore;
    /** {@code COPY … FROM stdin}의 데이터 구간이면 그 데이터 줄 수, 아니면 -1. */
    private final int dataRows;

    Token(TokenType type, String text, String upper, int offset, int endOffset, int line, int column,
            int endLine, int endColumn, boolean unterminated, boolean firstOnLine, boolean blankLineBefore) {
        this(type, text, upper, offset, endOffset, line, column, endLine, endColumn, unterminated, firstOnLine, blankLineBefore, -1);
    }

    Token(TokenType type, String text, String upper, int offset, int endOffset, int line, int column,
            int endLine, int endColumn, boolean unterminated, boolean firstOnLine, boolean blankLineBefore, int dataRows) {
        this.type = type;
        this.text = text;
        this.upper = upper != null ? upper : (type == TokenType.IDENT ? text.toUpperCase(Locale.ROOT) : text);
        this.offset = offset;
        this.endOffset = endOffset;
        this.line = line;
        this.column = column;
        this.endLine = endLine;
        this.endColumn = endColumn;
        this.unterminated = unterminated;
        this.firstOnLine = firstOnLine;
        this.blankLineBefore = blankLineBefore;
        this.dataRows = dataRows;
    }

    /**
     * 조각만 따로 렉싱해 얻은 토큰을 원문에서의 위치로 옮긴 사본. 조각의 첫 줄에 있던 토큰만 열이 밀린다.
     *
     * @param offsetDelta 조각이 원문에서 시작하는 위치(0부터)
     * @param lineDelta 조각의 첫 줄이 원문의 몇째 줄인지에서 1을 뺀 값
     * @param firstLineColumnDelta 조각의 첫 글자가 원문 그 줄의 몇째 열인지에서 1을 뺀 값
     */
    Token moved(int offsetDelta, int lineDelta, int firstLineColumnDelta) {
        return new Token(type, text, upper, offset + offsetDelta, endOffset + offsetDelta, line + lineDelta,
                line == 1 ? column + firstLineColumnDelta : column, endLine + lineDelta,
                endLine == 1 ? endColumn + firstLineColumnDelta : endColumn, unterminated, firstOnLine, blankLineBefore, dataRows);
    }

    /**
     * 같은 위치에 글자만 바꾼 이름 토큰. PL/pgSQL의 {@code PERFORM 쿼리}를 {@code SELECT 쿼리}로 바꿔 구문 파서에 넘길 때 쓴다(F-019-R26):
     * 구문의 위치는 원문 그대로 남는다.
     */
    public Token renamed(String identifier) {
        return new Token(TokenType.IDENT, identifier, null, offset, endOffset, line, column, endLine, endColumn, unterminated, firstOnLine,
                blankLineBefore, dataRows);
    }

    /** 위치 없는 합성 토큰(테스트, 복구용). */
    public static Token synthetic(TokenType type, String text) {
        return new Token(type, text, null, 0, 0, 0, 0, 0, 0, false, false, false);
    }

    public TokenType type() {
        return type;
    }

    /** 원문 그대로. */
    public String text() {
        return text;
    }

    /** IDENT면 대문자 표기({@code ${..}}는 {@link #DYNAMIC}으로 바뀐다), 그 밖에는 원문. */
    public String upper() {
        return upper;
    }

    /** 원문에서의 시작 위치(0부터). */
    public int offset() {
        return offset;
    }

    /** 원문에서의 끝 위치(포함하지 않음). */
    public int endOffset() {
        return endOffset;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public int endLine() {
        return endLine;
    }

    public int endColumn() {
        return endColumn;
    }

    /** 닫는 따옴표나 주석 끝이 없는 토큰. */
    public boolean unterminated() {
        return unterminated;
    }

    /** 그 줄에서 (주석을 빼고) 처음 나온 토큰. */
    public boolean firstOnLine() {
        return firstOnLine;
    }

    /** 앞 토큰과 이 토큰 사이에 빈 줄이 있다. */
    public boolean blankLineBefore() {
        return blankLineBefore;
    }

    /**
     * {@code COPY … FROM stdin} 뒤의 데이터 구간을 나타내는 토큰이면 그 데이터 줄 수(줄 처음의 {@code \.} 앞까지), 아니면 -1(F-019-R11).
     * 이 토큰은 주석과 같은 종류({@link TokenType#COMMENT})라 주석을 빼는 {@link Lexer#tokenize}에서는 나오지 않는다. 본문은 쥐고 있지 않다.
     */
    public int copyDataRows() {
        return dataRows;
    }

    // ---- 달러 인용 문자열(PostgreSQL 읽기 방식, F-019-R9) ----

    /** PostgreSQL 달러 인용 문자열({@code $$..$$}, {@code $태그$..$태그$})인가. PostgreSQL 읽기 방식의 렉서만 이런 토큰을 낸다. */
    public boolean dollarQuoted() {
        return type == TokenType.STRING && text.length() >= 2 && text.charAt(0) == '$' && text.indexOf('$', 1) > 0;
    }

    /** 여는 구분자({@code $태그$})의 길이. 달러 인용이 아니면 0. */
    private int dollarDelimiterLength() {
        return dollarQuoted() ? text.indexOf('$', 1) + 1 : 0;
    }

    /** 달러 인용의 태그({@code $body$}면 {@code body}, {@code $$}면 빈 문자열). 달러 인용이 아니면 null. */
    public String dollarTag() {
        return dollarQuoted() ? text.substring(1, dollarDelimiterLength() - 1) : null;
    }

    /** 달러 인용의 안쪽 본문(구분자 제외, 원문 그대로). 닫히지 않았으면 여는 구분자 뒤 전부다. 달러 인용이 아니면 null. */
    public String dollarBody() {
        if (!dollarQuoted()) {
            return null;
        }
        int open = dollarDelimiterLength();
        boolean closed = !unterminated && text.length() >= open * 2;
        return text.substring(open, closed ? text.length() - open : text.length());
    }

    /**
     * 달러 인용 본문의 첫 글자가 원문에서 놓인 위치(0부터). 본문을 {@link Lexer#tokenizeAt}으로 다시 토큰으로 만들 때
     * {@link #bodyLine()}, {@link #bodyColumn()}과 함께 넘기면 토큰의 위치가 원문 기준이 된다({@link Lexer#tokenizeBody}가 그렇게 한다).
     * 달러 인용이 아니면 토큰의 시작 위치다.
     */
    public int bodyOffset() {
        return offset + dollarDelimiterLength();
    }

    /** 달러 인용 본문의 첫 글자가 놓인 줄(1부터). 여는 구분자에는 줄바꿈이 없으므로 토큰의 줄과 같다. */
    public int bodyLine() {
        return line;
    }

    /** 달러 인용 본문의 첫 글자가 놓인 열(1부터). */
    public int bodyColumn() {
        return column + dollarDelimiterLength();
    }

    /** 따옴표 없는 이름이 주어진 키워드(대문자)와 같은가. */
    public boolean is(String keyword) {
        return type == TokenType.IDENT && upper.equals(keyword);
    }

    public boolean isAny(String... keywords) {
        if (type != TokenType.IDENT) {
            return false;
        }
        for (String k : keywords) {
            if (upper.equals(k)) {
                return true;
            }
        }
        return false;
    }

    public boolean isSymbol(String symbol) {
        return type == TokenType.SYMBOL && text.equals(symbol);
    }

    /** IDENT 또는 QUOTED_IDENT. */
    public boolean isIdentifier() {
        return type == TokenType.IDENT || type == TokenType.QUOTED_IDENT;
    }

    /** {@code ${}} 치환 자리가 들어 있는 식별자. */
    public boolean isDynamic() {
        return type == TokenType.IDENT && upper.contains(DYNAMIC);
    }

    public boolean isEof() {
        return type == TokenType.EOF;
    }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + line + ":" + column;
    }
}
