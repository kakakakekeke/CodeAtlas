package io.codeatlas.sql.script;

import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 스크립트를 실행 단위로 나눈다.
 *
 * <ul>
 *   <li>종결자: {@code ;}, 줄 단독 {@code /}(Oracle), DB2 {@code @}({@code --#SET TERMINATOR x} 지시문 또는 줄 끝의 {@code @}).</li>
 *   <li>빈 줄은 다음 토큰이 새 구문을 시작하는 키워드이고 앞 토큰이 이어지는 문맥이 아닐 때만 끊는다.</li>
 *   <li>{@code CREATE [OR REPLACE] PROCEDURE|FUNCTION|PACKAGE [BODY]|TRIGGER|TYPE BODY}와 익명 블록은 내부의 {@code ;}에서 끊지 않는다.</li>
 *   <li>SQL*Plus·CLP 줄 명령({@code SET ..}, {@code @file}, {@code EXIT})은 그 줄에서 끝난다. PROMPT/REM 줄은 버린다.</li>
 *   <li>종결자가 (거의) 없고 줄 시작에 구문 키워드가 여럿인 파일(HSQL {@code .script} 형식처럼 줄바꿈으로만 나눈 스크립트)은
 *       줄 처음의 구문 키워드에서도 끊는다(F-006-R22).</li>
 * </ul>
 *
 * <p>토큰을 통째로 만들어 두지 않는다: 렉서에서 흘려 읽고 지금 자르는 단위만큼만 쥔다. {@link #forEach}는 단위를 만드는 대로 넘겨주므로
 * 받는 쪽이 붙잡지 않으면 큰 스크립트도 단위 하나만큼의 메모리로 지나간다(F-006-R15).
 */
public final class ScriptSplitter {

    private static final Set<String> LINE_COMMANDS = Set.of(
            "SET", "SPOOL", "WHENEVER", "EXIT", "QUIT", "DEFINE", "UNDEFINE", "SHOW", "ACCEPT", "PAUSE", "TTITLE",
            "BTITLE", "HOST", "VARIABLE", "VAR", "PRINT", "CONNECT", "DISCONNECT", "ECHO", "EXEC", "EXECUTE",
            "TIMING", "CLEAR", "BREAK", "COMPUTE", "DESC", "DESCRIBE", "TERMINATE", "COMMIT", "ROLLBACK", "COLUMN");

    private static final Set<String> NEW_STATEMENT = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "COMMENT", "GRANT", "REVOKE",
            "TRUNCATE", "COMMIT", "ROLLBACK");

    private static final Set<String> CONTINUATION = Set.of(
            "UNION", "ALL", "MINUS", "INTERSECT", "EXCEPT", "AS", "IN", "EXISTS", "ON", "THEN", "OR", "AND", "AFTER",
            "BEFORE", "OF", "ELSE", "FOR", "INSTEAD", "NOT", "DISTINCT", "BY", "WHEN", "FROM", "WHERE", "SET",
            "INTO", "JOIN", "USING", "RETURN", "IS", "SELECT", "VALUES", "HAVING", "ANY", "SOME", "LIKE", "BETWEEN");

    /** 줄 시작에 오면 "이 파일에 읽을 내용이 있다"고 보는 키워드(F-006-R22의 진단 판단용). */
    private static final Set<String> CONTENT_KEYWORDS = Set.of(
            "CREATE", "INSERT", "UPDATE", "DELETE", "MERGE", "SELECT", "ALTER", "COMMENT", "CALL", "BEGIN", "DECLARE", "TRUNCATE");

    /** 파일을 한 번 훑어 얻은 모양: 종결자 수와 줄 시작 구문 키워드 수. */
    public record Shape(int terminators, int lineStartStatements, int contentLines, boolean terminatorDirective) {

        /** 줄바꿈으로만 구문을 나눈 스크립트로 보이는가: 종결자가 하나 이하인데 줄 시작 구문 키워드가 둘 이상이다. */
        public boolean lineSeparated() {
            return !terminatorDirective && terminators <= 1 && lineStartStatements >= 2;
        }

        /** 정의나 DML로 보이는 줄이 있는가. */
        public boolean hasContent() {
            return contentLines > 0;
        }
    }

    /** 렉서에서 흘려 읽은 토큰을 지금 자르는 단위만큼만 쥐는 창. 인덱스는 파일 처음부터 센 절대 위치다. */
    private static final class Window {
        private final Lexer.Stream source;
        private final ArrayList<Token> buffer = new ArrayList<>();
        private int base;
        private Token eof;

        Window(Lexer.Stream source) {
            this.source = source;
        }

        Token get(int index) {
            while (eof == null && index - base >= buffer.size()) {
                Token t = source.next();
                buffer.add(t);
                if (t.isEof()) {
                    eof = t;
                }
            }
            int at = index - base;
            if (at < 0) {
                throw new IllegalStateException("이미 놓아 준 토큰: " + index);
            }
            return at < buffer.size() ? buffer.get(at) : eof;
        }

        /** index 앞의 토큰을 놓아 준다. */
        void release(int index) {
            int drop = Math.min(index - base, buffer.size());
            if (drop >= 2048) {
                buffer.subList(0, drop).clear();
                base += drop;
            }
        }
    }

    private final String text;
    private final boolean fileMode;
    private final Shape shape;
    private final boolean lineMode;
    private final Window all;
    private final java.util.function.Consumer<ScriptUnit> sink;
    private final java.util.function.Consumer<Token> tokenListener;
    private int reported;
    private String customTerminator;
    private int i;

    private ScriptSplitter(String text, boolean fileMode, boolean mysql, java.util.function.Consumer<ScriptUnit> sink,
            java.util.function.Consumer<Token> tokenListener) {
        this.fileMode = fileMode;
        this.text = fileMode ? blankPromptLines(text == null ? "" : text) : (text == null ? "" : text);
        this.shape = fileMode ? shapeOf(this.text, mysql) : null;
        this.lineMode = fileMode && shape.lineSeparated();
        this.all = new Window(Lexer.stream(this.text, true, mysql));
        this.sink = sink;
        this.tokenListener = tokenListener;
    }

    /** SQL 파일을 나눈다. */
    public static List<ScriptUnit> split(String text) {
        List<ScriptUnit> units = new ArrayList<>();
        new ScriptSplitter(text, true, false, units::add, null).run();
        return units;
    }

    /**
     * SQL 파일을 나눠 단위를 만드는 대로 넘겨준다(큰 파일용). tokenListener가 있으면 주석을 포함한 모든 토큰을 순서대로 한 번씩 알려 준다
     * (방언 판정처럼 파일 전체를 보는 일을 같은 훑기에서 하도록).
     */
    public static Shape forEach(String text, java.util.function.Consumer<ScriptUnit> sink, java.util.function.Consumer<Token> tokenListener) {
        return forEach(text, sink, tokenListener, false);
    }

    /**
     * {@link #forEach(String, java.util.function.Consumer, java.util.function.Consumer)}와 같다.
     *
     * @param mysql MySQL로 판정한 파일이면 true: 줄 처음의 {@code #}을 줄 주석으로 읽는다(F-006-R27)
     */
    public static Shape forEach(String text, java.util.function.Consumer<ScriptUnit> sink, java.util.function.Consumer<Token> tokenListener,
            boolean mysql) {
        ScriptSplitter splitter = new ScriptSplitter(text, true, mysql, sink, tokenListener);
        splitter.run();
        return splitter.shape;
    }

    /** 내장 소스(매퍼 구문 본문)를 나눈다: 빈 줄, 줄 단독 {@code /}, 줄 명령, {@code @} 규칙을 쓰지 않는다. */
    public static List<ScriptUnit> splitEmbedded(String text) {
        return splitEmbedded(text, false);
    }

    /** {@link #splitEmbedded(String)}와 같되 MySQL로 판정한 SQL이면 줄 처음의 {@code #}을 주석으로 읽는다. */
    public static List<ScriptUnit> splitEmbedded(String text, boolean mysql) {
        List<ScriptUnit> units = new ArrayList<>();
        new ScriptSplitter(text, false, mysql, units::add, null).run();
        return units;
    }

    /**
     * 파일의 모양을 글자 단위로 한 번 훑어 센다(렉싱보다 훨씬 싸다): 문자열·주석 밖의 {@code ;}, 줄 단독 {@code /}, 줄 끝 {@code @}의 수와
     * 줄 처음(공백 뒤)에 오는 구문 키워드의 수.
     */
    public static Shape shapeOf(String text) {
        return shapeOf(text, false);
    }

    /** {@link #shapeOf(String)}와 같되 MySQL로 판정한 파일이면 줄 처음의 {@code #}부터 줄 끝까지를 주석으로 건너뛴다. */
    public static Shape shapeOf(String text, boolean mysql) {
        String s = text == null ? "" : text;
        int n = s.length();
        int terminators = 0;
        int lineStarts = 0;
        int content = 0;
        boolean directive = false;
        int codeCount = 0; // 이 줄에서 본 코드 조각 수(2에서 멈춘다)
        char first = 0;
        char last = 0;
        int p = 0;
        while (p <= n) {
            char c = p < n ? s.charAt(p) : '\n';
            if (c == '\n' || c == '\r') {
                // 줄 끝: LF, CRLF, CR 단독(F-006-R32). CRLF는 CR에서 한 번 세고 LF에서는 셀 것이 없다
                if ((codeCount == 1 && first == '/') || last == '@') {
                    terminators++;
                }
                codeCount = 0;
                first = 0;
                last = 0;
                p++;
                continue;
            }
            if ((c == '-' && p + 1 < n && s.charAt(p + 1) == '-') || (mysql && c == '#' && codeCount == 0 && !(p + 1 < n && s.charAt(p + 1) == '{'))) {
                if (s.regionMatches(true, p, "--#SET TERMINATOR", 0, 17)) {
                    directive = true;
                }
                p = lineEnd(s, p);
                continue;
            }
            if (c == '/' && p + 1 < n && s.charAt(p + 1) == '*') {
                int end = s.indexOf("*/", p + 2);
                p = end < 0 ? n : end + 2;
                continue;
            }
            if (Character.isWhitespace(c)) {
                p++;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int q = p + 1;
                while (q < n && s.charAt(q) != c && !(c != '\'' && (s.charAt(q) == '\n' || s.charAt(q) == '\r'))) {
                    q++;
                }
                p = Math.min(n, q + 1);
            } else if (codeCount == 0 && Character.isLetter(c)) {
                int e = p;
                while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '_')) {
                    e++;
                }
                if (e - p <= 10) {
                    String word = s.substring(p, e).toUpperCase(Locale.ROOT);
                    if (NEW_STATEMENT.contains(word)) {
                        lineStarts++;
                    }
                    if (CONTENT_KEYWORDS.contains(word)) {
                        content++;
                    }
                }
                p = e;
                c = 'a';
            } else {
                if (c == ';') {
                    terminators++;
                }
                p++;
            }
            if (codeCount == 0) {
                first = c;
            }
            last = c;
            if (codeCount < 2) {
                codeCount++;
            }
        }
        return new Shape(terminators, lineStarts, content, directive);
    }

    /** from부터 처음 나오는 줄 끝 글자(CR 또는 LF)의 위치. 없으면 글의 끝. */
    private static int lineEnd(String s, int from) {
        int i = from;
        int n = s.length();
        while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    /** PROMPT, REM, REMARK로 시작하는 줄은 임의의 글자가 올 수 있어 렉싱 전에 공백으로 바꾼다(위치는 유지). */
    private static String blankPromptLines(String s) {
        StringBuilder sb = null;
        int lineStart = 0;
        int n = s.length();
        while (lineStart < n) {
            int lineEnd = lineEnd(s, lineStart);
            int p = lineStart;
            while (p < lineEnd && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) {
                p++;
            }
            int wordEnd = p;
            while (wordEnd < lineEnd && Character.isLetter(s.charAt(wordEnd))) {
                wordEnd++;
            }
            String word = s.substring(p, wordEnd).toUpperCase(Locale.ROOT);
            boolean boundary = wordEnd == lineEnd || Character.isWhitespace(s.charAt(wordEnd));
            if (boundary && (word.equals("PROMPT") || word.equals("REM") || word.equals("REMARK"))) {
                if (sb == null) {
                    sb = new StringBuilder(s);
                }
                for (int k = p; k < lineEnd; k++) {
                    sb.setCharAt(k, ' ');
                }
            }
            lineStart = lineEnd + 1;
        }
        return sb == null ? s : sb.toString();
    }

    private void run() {
        while (!all.get(i).isEof()) {
            listen(i);
            all.release(i);
            Token t = all.get(i);
            if (t.type() == TokenType.COMMENT) {
                directive(t);
                i++;
                continue;
            }
            if (t.isSymbol(";") || isCustomTerminator(t) || isLoneSlash(i) || (isLineEndAt(i) && !startsLineCommand(i))) {
                i++;
                continue;
            }
            int start = i;
            List<Token> tokens = new ArrayList<>();
            ScriptUnit.Kind kind = classify(start);
            if (fileMode && kind == ScriptUnit.Kind.STATEMENT && startsLineCommand(start)) {
                lineCommand(tokens);
            } else if (kind == ScriptUnit.Kind.STATEMENT) {
                statement(tokens);
            } else {
                routine(tokens, kind);
            }
            if (i == start) {
                i++; // 안전장치
            }
            if (!tokens.isEmpty()) {
                Token first = tokens.get(0);
                Token last = tokens.get(tokens.size() - 1);
                sink.accept(new ScriptUnit(kind, tokens, text.substring(first.offset(), last.endOffset())));
            }
        }
        listen(i + 1);
    }

    /** 아직 알리지 않은 토큰을 index 앞까지 순서대로 알려 준다. */
    private void listen(int index) {
        if (tokenListener == null) {
            reported = index;
            return;
        }
        while (reported < index) {
            Token t = all.get(reported);
            tokenListener.accept(t);
            reported++;
            if (t.isEof()) {
                break;
            }
        }
    }

    private void directive(Token comment) {
        String c = comment.text().trim();
        String upper = c.toUpperCase(Locale.ROOT);
        if (upper.startsWith("--#SET TERMINATOR")) {
            String value = c.substring("--#SET TERMINATOR".length()).trim();
            customTerminator = value.isEmpty() || value.equals(";") ? null : value.substring(0, 1);
        }
    }

    private boolean isCustomTerminator(Token t) {
        return customTerminator != null && t.type() == TokenType.SYMBOL && t.text().equals(customTerminator);
    }

    /** 다음 (주석이 아닌) 토큰. 없으면 EOF. */
    private Token nextSignificant(int index) {
        return all.get(nextSignificantIndex(index));
    }

    /** 다음 (주석이 아닌) 토큰의 위치. 없으면 EOF의 위치. */
    private int nextSignificantIndex(int index) {
        int k = index + 1;
        while (all.get(k).type() == TokenType.COMMENT) {
            k++;
        }
        return k;
    }

    /** 그 줄에 혼자 있는 {@code /}. */
    private boolean isLoneSlash(int index) {
        if (!fileMode) {
            return false;
        }
        Token t = all.get(index);
        if (!t.isSymbol("/") || !t.firstOnLine()) {
            return false;
        }
        Token next = nextSignificant(index);
        return next.isEof() || next.line() > t.line();
    }

    /** 지시문 없이 쓰인 DB2 종결자: 줄 끝에 놓인 {@code @}. */
    private boolean isLineEndAt(int index) {
        if (!fileMode || customTerminator != null) {
            return false;
        }
        Token t = all.get(index);
        if (!t.isSymbol("@")) {
            return false;
        }
        Token next = nextSignificant(index);
        return next.isEof() || next.line() > t.line();
    }

    private boolean isTerminator(int index) {
        Token t = all.get(index);
        return isCustomTerminator(t) || isLoneSlash(index) || isLineEndAt(index);
    }

    private boolean startsLineCommand(int index) {
        if (!fileMode) {
            return false;
        }
        Token t = all.get(index);
        if (t.isSymbol("@") || t.isSymbol("!")) {
            return t.firstOnLine();
        }
        return t.type() == TokenType.IDENT && t.firstOnLine() && LINE_COMMANDS.contains(t.upper());
    }

    private ScriptUnit.Kind classify(int index) {
        Token t = all.get(index);
        if (t.is("BEGIN")) {
            return ScriptUnit.Kind.BLOCK;
        }
        if (t.is("DECLARE")) {
            Token next = nextSignificant(index);
            return next.is("GLOBAL") ? ScriptUnit.Kind.STATEMENT : ScriptUnit.Kind.BLOCK;
        }
        if (!t.is("CREATE")) {
            return ScriptUnit.Kind.STATEMENT;
        }
        int k = nextSignificantIndex(index);
        Token x = all.get(k);
        if (x.is("OR")) {
            k = nextSignificantIndex(nextSignificantIndex(k));
            x = all.get(k);
        }
        while (x.isAny("EDITIONABLE", "NONEDITIONABLE", "PUBLIC", "FORCE", "NO")) {
            k = nextSignificantIndex(k);
            x = all.get(k);
        }
        if (x.isAny("PROCEDURE", "FUNCTION", "TRIGGER", "PACKAGE")) {
            return ScriptUnit.Kind.ROUTINE;
        }
        if (x.is("TYPE") && nextSignificant(k).is("BODY")) {
            return ScriptUnit.Kind.ROUTINE;
        }
        return ScriptUnit.Kind.STATEMENT;
    }

    /** 줄 명령: 같은 줄의 토큰까지(중간에 ; 가 있으면 거기까지). */
    private void lineCommand(List<Token> tokens) {
        int line = all.get(i).line();
        while (!all.get(i).isEof()) {
            Token t = all.get(i);
            if (t.type() == TokenType.COMMENT) {
                if (t.line() > line) {
                    break;
                }
                i++;
                continue;
            }
            if (t.line() > line) {
                break;
            }
            if (t.isSymbol(";") || (isCustomTerminator(t) && !tokens.isEmpty())) {
                i++;
                break;
            }
            tokens.add(t);
            i++;
        }
    }

    private void statement(List<Token> tokens) {
        Token first = all.get(i);
        while (!all.get(i).isEof()) {
            Token t = all.get(i);
            if (t.type() == TokenType.COMMENT) {
                directive(t);
                i++;
                continue;
            }
            if (t.isSymbol(";") || (!tokens.isEmpty() && isTerminator(i))) {
                i++;
                return;
            }
            if (fileMode && !tokens.isEmpty() && t.blankLineBefore() && startsNewStatement(t, tokens.get(tokens.size() - 1), first, i)) {
                return;
            }
            if (lineMode && !tokens.isEmpty() && t.firstOnLine() && startsNewLine(t, tokens.get(tokens.size() - 1), first, i)) {
                return; // 줄바꿈으로만 나눈 스크립트: 줄 처음의 구문 키워드에서 끊는다(F-006-R22)
            }
            tokens.add(t);
            i++;
        }
    }

    private boolean startsNewStatement(Token t, Token prev, Token first, int index) {
        if (t.type() != TokenType.IDENT) {
            return false;
        }
        if (prev.type() == TokenType.SYMBOL && !prev.isSymbol(")") && !prev.isSymbol("*")) {
            return false;
        }
        if (prev.type() == TokenType.IDENT && CONTINUATION.contains(prev.upper())) {
            return false;
        }
        String u = t.upper();
        if (NEW_STATEMENT.contains(u)) {
            if (u.equals("DELETE") && first.is("MERGE") && nextSignificant(index).is("WHERE")) {
                return false;
            }
            return true;
        }
        if (u.equals("SELECT") || (u.equals("WITH") && nextSignificant(index).isIdentifier()
                && !nextSignificant(index).isAny("UR", "CS", "RS", "RR", "READ", "CHECK", "CASCADED", "LOCAL"))) {
            return !first.isAny("WITH", "INSERT", "CREATE", "DECLARE", "EXPLAIN", "MERGE") && !first.isSymbol("(");
        }
        return false;
    }

    /** 줄바꿈으로만 나눈 스크립트에서 줄 처음의 토큰 t가 새 구문(또는 줄 명령)을 여는가. */
    private boolean startsNewLine(Token t, Token prev, Token first, int index) {
        if (startsLineCommand(index)) {
            return true;
        }
        if (t.isAny("SELECT", "WITH")) {
            return false; // INSERT .. SELECT, CREATE VIEW .. AS SELECT처럼 줄을 바꿔 이어 쓰는 일이 흔하다
        }
        if (first.is("ALTER") && t.isAny("DROP", "ALTER") && alterAction(index)) {
            return false; // ALTER TABLE T 다음 줄의 DROP COLUMN .., ALTER COLUMN ..(DB2): 새 구문이 아니라 그 ALTER의 동작이다
        }
        return startsNewStatement(t, prev, first, index);
    }

    /**
     * ALTER로 시작한 단위에서 index의 줄 처음 DROP·ALTER가 그 ALTER의 동작인가: 단위에 아직 동작이 없거나({@code ALTER TABLE [스키마.]이름}뿐),
     * 바로 뒤가 동작의 대상({@code (}, COLUMN, CONSTRAINT, PRIMARY, UNIQUE, FOREIGN, CHECK, PARTITION)이다. 종결자가 하나 이하인 파일은
     * 줄바꿈으로만 나눈 스크립트일 수도, 여러 줄에 걸친 구문 하나일 수도 있어서 본다(F-006-R22).
     */
    private boolean alterAction(int index) {
        Token next = nextSignificant(index);
        if (next.isSymbol("(") || next.isAny("COLUMN", "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "PARTITION")) {
            return true;
        }
        int count = 0; // 단위의 시작부터 index 앞까지의 토큰 수(주석 제외)
        for (int k = index - 1; k >= 0 && count <= 5; k--) {
            Token t;
            try {
                t = all.get(k);
            } catch (IllegalStateException released) {
                return false;
            }
            if (t.type() == TokenType.COMMENT) {
                continue;
            }
            count++;
            if (t.is("ALTER") && t.firstOnLine()) {
                // ALTER TABLE name(3) 또는 ALTER TABLE schema . name(5)
                return count == 3 || count == 5;
            }
        }
        return false;
    }

    private void routine(List<Token> tokens, ScriptUnit.Kind kind) {
        boolean container = false;
        boolean trigger = false;
        if (kind == ScriptUnit.Kind.ROUTINE) {
            // 헤더 종류 판별
            for (int k = i; !all.get(k).isEof() && k < i + 12; k++) {
                Token t = all.get(k);
                if (t.is("PACKAGE") || t.is("TYPE")) {
                    container = true;
                    break;
                }
                if (t.is("TRIGGER")) {
                    trigger = true;
                    break;
                }
                if (t.isAny("PROCEDURE", "FUNCTION")) {
                    break;
                }
            }
        }
        BlockTracker tracker = new BlockTracker(container, trigger || kind == ScriptUnit.Kind.BLOCK);
        Token prev = null;
        int start = i;
        while (!all.get(i).isEof()) {
            Token t = all.get(i);
            if (t.type() == TokenType.COMMENT) {
                directive(t);
                i++;
                continue;
            }
            if (!tokens.isEmpty() && isTerminator(i)) {
                i++;
                return;
            }
            if (i > start && t.firstOnLine() && t.is("CREATE") && (tracker.depth > 0 || tracker.pending > 0)
                    && classifyIsCreateStatement(i)) {
                return; // 깨진 루틴: 다음 CREATE에서 다시 시작
            }
            Token next = nextSignificant(i);
            boolean semicolon = tracker.feed(t, prev, next);
            tokens.add(t);
            prev = t;
            i++;
            if (semicolon && tracker.depth == 0 && tracker.pending == 0 && customTerminator == null) {
                return;
            }
        }
    }

    /** 줄 처음의 CREATE가 실제 CREATE 문으로 보이는가. */
    private boolean classifyIsCreateStatement(int index) {
        Token next = nextSignificant(index);
        return next.isAny("OR", "TABLE", "INDEX", "UNIQUE", "VIEW", "SEQUENCE", "PROCEDURE", "FUNCTION", "PACKAGE",
                "TRIGGER", "SYNONYM", "PUBLIC", "GLOBAL", "MATERIALIZED", "TYPE", "ALIAS", "FORCE", "EDITIONABLE");
    }
}
