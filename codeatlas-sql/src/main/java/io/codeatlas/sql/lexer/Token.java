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

    Token(TokenType type, String text, String upper, int offset, int endOffset, int line, int column,
            int endLine, int endColumn, boolean unterminated, boolean firstOnLine, boolean blankLineBefore) {
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
                endLine == 1 ? endColumn + firstLineColumnDelta : endColumn, unterminated, firstOnLine, blankLineBefore);
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
