package io.codeatlas.sql.parser;

import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * 토큰 목록 위의 커서와 공통 도구(미리 보기, 받아들이기, 괄호 건너뛰기, 문제 기록).
 * {@link SqlParser}, {@link DdlParser}가 상속하고, 프로시저 본문 파서(F-008)도 이 위에 만들 수 있다.
 */
public class TokenCursor {

    protected final List<Token> tokens;
    protected int pos;
    protected final List<ParseProblem> problems = new ArrayList<>();
    /** 파서가 열어 둔 괄호 수(구조상). 0이면 최상위다. */
    protected int openParens;

    /** tokens의 끝에 EOF가 없으면 붙인다. */
    public TokenCursor(List<Token> tokens) {
        if (tokens.isEmpty() || !tokens.get(tokens.size() - 1).isEof()) {
            List<Token> copy = new ArrayList<>(tokens);
            copy.add(Token.synthetic(TokenType.EOF, ""));
            this.tokens = copy;
        } else {
            this.tokens = tokens;
        }
    }

    public List<ParseProblem> problems() {
        return problems;
    }

    /** 현재 토큰 인덱스. */
    public int position() {
        return pos;
    }

    public void position(int newPosition) {
        this.pos = Math.max(0, Math.min(newPosition, tokens.size() - 1));
    }

    public Token peek() {
        return tokens.get(pos);
    }

    public Token peek(int ahead) {
        int i = pos + ahead;
        return i < 0 ? tokens.get(0) : tokens.get(Math.min(i, tokens.size() - 1));
    }

    /** 현재 토큰을 돌려주고 다음으로 간다. EOF에서는 움직이지 않는다. */
    public Token next() {
        Token t = tokens.get(pos);
        if (pos < tokens.size() - 1) {
            pos++;
        }
        return t;
    }

    /** 방금 소비한 토큰. 없으면 첫 토큰. */
    public Token previous() {
        return tokens.get(Math.max(0, pos - 1));
    }

    public boolean atEnd() {
        return peek().isEof();
    }

    public boolean at(String keyword) {
        return peek().is(keyword);
    }

    public boolean atAny(String... keywords) {
        return peek().isAny(keywords);
    }

    public boolean atSymbol(String symbol) {
        return peek().isSymbol(symbol);
    }

    /** 현재부터 연속한 토큰이 주어진 키워드 순서와 같은가. */
    public boolean atSeq(String... keywords) {
        for (int i = 0; i < keywords.length; i++) {
            if (!peek(i).is(keywords[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean accept(String keyword) {
        if (at(keyword)) {
            next();
            return true;
        }
        return false;
    }

    public boolean acceptAny(String... keywords) {
        if (atAny(keywords)) {
            next();
            return true;
        }
        return false;
    }

    public boolean acceptSeq(String... keywords) {
        if (atSeq(keywords)) {
            for (int i = 0; i < keywords.length; i++) {
                next();
            }
            return true;
        }
        return false;
    }

    public boolean acceptSymbol(String symbol) {
        if (atSymbol(symbol)) {
            next();
            return true;
        }
        return false;
    }

    /** 여는 괄호를 소비한다(호출 전에 확인했다고 본다). */
    protected void open() {
        if (atSymbol("(")) {
            next();
        }
        openParens++;
    }

    /** 닫는 괄호를 소비한다. 없으면 문제를 남기고 그대로 둔다. */
    protected void close() {
        if (atSymbol(")")) {
            next();
        } else {
            problem(unexpectedMessage(peek(), "닫는 괄호가 없음"), peek(), false);
        }
        if (openParens > 0) {
            openParens--;
        }
    }

    /** 현재 토큰이 여는 괄호면 짝이 맞는 닫는 괄호까지(포함) 건너뛴다. 짝이 없으면 끝까지 간다. */
    public void skipBalanced() {
        if (!atSymbol("(")) {
            return;
        }
        int depth = 0;
        while (!atEnd()) {
            Token t = next();
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
                if (depth == 0) {
                    return;
                }
            }
        }
    }

    /** 지금 열려 있는 괄호를 닫는 {@code )} 앞까지(소비하지 않음) 건너뛴다. 안쪽 괄호는 짝을 맞춰 넘는다. */
    public void skipToCloseParen() {
        int depth = 0;
        while (!atEnd()) {
            Token t = peek();
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                if (depth == 0) {
                    return;
                }
                depth--;
            }
            next();
        }
    }

    /**
     * 구문이 이어질 수 없는 자리의 토큰 t에 대한 문제 메시지: 복구가 일어난 지점의 토큰과 기대한 것을 담는다(F-006-R39).
     * 피연산자가 끝난 바로 뒤에 또 피연산자가 오면 AND/OR나 연산자가 빠진 것이고, 닫는 중괄호 같은 기호면 잘못 들어간 글자다.
     * 어느 쪽도 아니면 fallback(호출한 자리의 일반 메시지)이다.
     */
    protected String unexpectedMessage(Token t, String fallback) {
        if (t.isEof()) {
            return fallback;
        }
        if (t.type() == TokenType.SYMBOL && (t.isSymbol("}") || t.isSymbol("{") || t.isSymbol("]") || t.isSymbol("[") || t.isSymbol("\\")
                || t.isSymbol("&") || t.isSymbol("`") || t.isSymbol("~") || t.isSymbol("^") || t.isSymbol("$"))) {
            return "예상하지 못한 `" + t.text() + "`";
        }
        Token before = pos > 0 ? tokens.get(pos - 1) : null;
        boolean operandStart = t.type() == TokenType.STRING || t.type() == TokenType.NUMBER || t.type() == TokenType.BIND
                || t.type() == TokenType.QUOTED_IDENT || (t.type() == TokenType.IDENT && !Keywords.EXPR_STOP.contains(t.upper()));
        boolean afterOperand = before != null && (before.type() == TokenType.STRING || before.type() == TokenType.NUMBER
                || before.type() == TokenType.BIND || before.isIdentifier() || before.isSymbol(")") || before.isSymbol("(+)"));
        if (operandStart && afterOperand && !t.isDynamic() && !before.isDynamic()) {
            return "`" + t.text() + "` 앞에 AND/OR 또는 연산자가 필요한 자리";
        }
        return fallback;
    }

    protected void problem(String message, Token at, boolean minor) {
        problems.add(new ParseProblem(message, Pos.of(at), minor));
    }

    /** 토큰 구간 [from, to)를 정리된 한 줄 텍스트로 만든다({@code NUMBER(5,2)}, {@code VARCHAR2(10 CHAR)}). */
    public static String joinTokens(List<Token> list) {
        StringBuilder sb = new StringBuilder();
        Token prev = null;
        for (Token t : list) {
            if (t.isEof()) {
                break;
            }
            if (prev != null && needsSpace(prev, t)) {
                sb.append(' ');
            }
            sb.append(t.type() == TokenType.IDENT ? t.upper() : t.text());
            prev = t;
        }
        return sb.toString();
    }

    private static boolean needsSpace(Token prev, Token t) {
        if (t.isSymbol(",") || t.isSymbol(")") || t.isSymbol(".") || t.isSymbol("(")) {
            return prev.isSymbol(")") && t.isSymbol("(");
        }
        if (prev.isSymbol("(") || prev.isSymbol(".") || prev.isSymbol(",")) {
            return false;
        }
        if (t.isSymbol("::") || prev.isSymbol("::") || t.isSymbol("[") || t.isSymbol("]") || prev.isSymbol("[")) {
            return false; // PostgreSQL 캐스트와 배열 표기는 붙여 적는다: '{}'::JSONB, ARRAY[1,2], TEXT[]
        }
        if (prev.type() == TokenType.SYMBOL && !prev.isSymbol(")") && prev.endOffset() == t.offset() && prev.endOffset() > 0) {
            return false;
        }
        return true;
    }
}
