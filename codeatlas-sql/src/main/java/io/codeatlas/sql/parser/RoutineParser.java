package io.codeatlas.sql.parser;

import io.codeatlas.core.model.DbName;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 루틴 본문 파서(F-008). PL/SQL과 SQL PL의 합집합을 하나의 문법으로 받아 {@link Routine} 트리를 만든다(ADR-0004).
 *
 * <p>목적은 본문 어디에 있든 SQL 구문과 호출을 찾는 것이다. 블록과 제어 구조는 구조적으로 파싱하고, 식은 토큰 구간으로 두되
 * 안의 서브쿼리와 이름 참조를 뽑는다. SQL 구문은 {@link SqlParser}에 넘긴다.
 *
 * <p>어떤 입력에도 예외를 던지지 않는다. 규칙:
 * <ul>
 *   <li>{@code ;}은 괄호 깊이와 상관없이 문장을 끝낸다(괄호가 안 닫혀도 다음 문장은 산다).</li>
 *   <li>짝이 맞지 않는 {@code END}는 소비하지 않고 바깥 구조에 넘긴다. 본문이 끝난 뒤 남은 토큰도 문장으로 읽어 붙인다.</li>
 *   <li>모르는 문장은 {@link Routine.Unknown}으로 건너뛰고 그 안의 서브쿼리는 건진다.</li>
 * </ul>
 */
public final class RoutineParser extends TokenCursor {

    private static final int MAX_DEPTH = 60;

    /** 문장 목록을 끝내는 키워드. */
    private static final Set<String> LIST_END = Set.of("END", "EXCEPTION", "ELSIF", "ELSEIF", "ELSE", "WHEN", "UNTIL");

    /** 문장을 시작하는 키워드(선언과 구분할 때 쓴다). */
    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "VALUES", "IF", "CASE", "LOOP", "WHILE", "FOR", "REPEAT",
            "FORALL", "OPEN", "FETCH", "CLOSE", "EXECUTE", "PREPARE", "CALL", "SET", "RETURN", "RAISE", "SIGNAL", "RESIGNAL",
            "GOTO", "LEAVE", "ITERATE", "EXIT", "CONTINUE", "NULL", "COMMIT", "ROLLBACK", "SAVEPOINT", "GET", "PIPE", "LOCK",
            "TRUNCATE", "END");

    /** 식 안에서 줄 첫머리에 나오면 앞 문장의 종결자가 빠진 것으로 보는 키워드. */
    private static final Set<String> EXPR_LINE_STOPS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "SELECT", "BEGIN", "DECLARE", "IF", "LOOP", "WHILE", "FETCH", "OPEN", "CLOSE",
            "COMMIT", "ROLLBACK", "RETURN", "FORALL", "GOTO", "RAISE", "CALL", "EXIT");

    /** SQL 구문 안에서 줄 첫머리에 나오면 종결자가 빠진 것으로 보는 키워드. */
    private static final Set<String> SQL_LINE_STOPS = Set.of(
            "BEGIN", "DECLARE", "EXCEPTION", "ELSIF", "ELSEIF", "COMMIT", "ROLLBACK", "IF", "WHILE", "GOTO", "RAISE", "LOOP");

    /** 본문에 올 수 있지만 뽑을 것이 없는 구문(DDL, 세션 제어 등)의 첫 키워드. */
    private static final Set<String> PASS_THROUGH = Set.of(
            "CREATE", "ALTER", "DROP", "GRANT", "REVOKE", "RENAME", "COMMENT", "ANALYZE", "REFRESH", "RUNSTATS", "REORG",
            "FLUSH", "EXPLAIN", "ALLOCATE", "ASSOCIATE", "RELEASE", "CONNECT", "DISCONNECT");

    /** 문장 키워드지만 괄호가 바로 뒤따르면 같은 이름의 프로시저 호출로 보는 것. */
    private static final Set<String> CALLABLE_KEYWORDS = Set.of(
            "OPEN", "FETCH", "CLOSE", "EXECUTE", "EXEC", "PREPARE", "RAISE", "SIGNAL", "RESIGNAL", "GOTO", "LEAVE", "ITERATE",
            "COMMIT", "ROLLBACK", "SAVEPOINT", "GET", "PIPE", "LOCK", "TRUNCATE", "MERGE", "INSERT", "REPEAT", "LOOP", "FORALL",
            "CONTINUE", "NULL", "CALL");

    /** {@code SET} 뒤에 오면 대입이 아니라 세션 설정인 이름. */
    private static final Set<String> SET_REGISTERS = Set.of(
            "CURRENT", "PATH", "SCHEMA", "ENCRYPTION", "SESSION", "ISOLATION", "PASSTHRU", "EVENT", "INTEGRITY", "CONNECTION",
            "ROLE", "TRANSACTION", "CONSTRAINT", "CONSTRAINTS", "SERVEROUTPUT", "WRITE", "USAGE", "WORKLOAD", "CURRENT_SCHEMA");

    private final boolean sqlPl;
    private final int sqlDepth;
    private final List<Routine.Decl> rootDeclarations = new ArrayList<>();
    private final Deque<List<Routine.Decl>> declarationSinks = new ArrayDeque<>();
    private final List<Routine.Stmt> pending = new ArrayList<>();
    private int depth;

    /**
     * @param tokens 본문의 토큰(주석 제외)
     * @param sqlPl SQL PL 문법으로 읽을지(블록 안의 {@code DECLARE}를 선언문으로 본다). false면 PL/SQL
     */
    public RoutineParser(List<Token> tokens, boolean sqlPl) {
        this(tokens, sqlPl, 0);
    }

    RoutineParser(List<Token> tokens, boolean sqlPl, int sqlDepth) {
        super(stripConditionalCompilation(tokens));
        this.sqlPl = sqlPl;
        this.sqlDepth = sqlDepth;
    }

    // ------------------------------------------------------------------ 진입점

    /**
     * 본문 하나를 파싱한다.
     *
     * @param declarationSection true면 선언부로 시작한다(PL/SQL 서브프로그램의 {@code IS/AS} 다음). false면 문장 하나
     *                           (SQL PL 본문, 트리거 본문, 익명 블록: {@code DECLARE}/{@code BEGIN}/단일 문장)
     */
    public Routine.Block parseBody(boolean declarationSection) {
        int start = pos;
        Routine.Block body;
        if (declarationSection) {
            List<Routine.Decl> declarations = declarations();
            body = bodyAfterDeclarations(null, declarations, start);
        } else {
            Routine.Stmt s = statementSingle();
            if (s instanceof Routine.Block b && rootDeclarations.isEmpty()) {
                body = b;
            } else {
                body = new Routine.Block(null, rootDeclarations, s == null ? List.of() : List.of(s), List.of(), false, span(start, pos));
            }
        }
        return withLeftovers(body);
    }

    /** {@code PROCEDURE|FUNCTION 이름 ..}로 시작하는 서브프로그램 하나(패키지 멤버)를 파싱한다. */
    public Routine.Subprogram parseSubprogram() {
        if (!atAny("PROCEDURE", "FUNCTION") || !peek(1).isIdentifier()) {
            problem("서브프로그램이 있어야 할 자리: " + peek().text(), peek(), false);
            Routine.Block body = withLeftovers(new Routine.Block(null, List.of(), List.of(), List.of(), false, Pos.of(peek())));
            return new Routine.Subprogram("PROCEDURE", "", List.of(), null, body, body.pos());
        }
        Routine.Subprogram sp = subprogram();
        if (sp.body() == null) {
            return sp;
        }
        return new Routine.Subprogram(sp.kind(), sp.name(), sp.params(), sp.returnType(), withLeftovers(sp.body()), sp.pos());
    }

    /**
     * 패키지 수준 구간(멤버 사이)을 파싱한다: 선언들과, {@code BEGIN}이 있으면 초기화부의 문장들.
     * 선언만 있으면 문장이 빈 블록이 나온다.
     */
    public Routine.Block parsePackageSection() {
        int start = pos;
        List<Routine.Decl> declarations = declarations();
        if (at("BEGIN")) {
            return block(null, declarations, start);
        }
        return new Routine.Block(null, declarations, List.of(), List.of(), false, span(start, pos));
    }

    /** 토큰 전체를 식 하나로 읽는다(트리거의 {@code WHEN (조건)} 등). */
    public Routine.Expr parseExpression() {
        Routine.Expr e = expr(pos, eofIndex());
        pos = eofIndex();
        return e;
    }

    /** 본문이 끝난 뒤 남은 토큰을 문장으로 읽어 붙인다(END 짝이 어긋난 본문에서 뒤쪽 SQL을 건진다). */
    private Routine.Block withLeftovers(Routine.Block body) {
        List<Routine.Stmt> extra = new ArrayList<>();
        List<Routine.Decl> extraDeclarations = new ArrayList<>();
        boolean reported = false;
        while (!atEnd()) {
            if (acceptSymbol(";") || acceptSymbol("/")) {
                continue;
            }
            if (!reported) {
                problem("본문이 끝난 뒤에 토큰이 남음(BEGIN/END 짝이 맞지 않음): " + peek().text(), peek(), false);
                reported = true;
            }
            Token t = peek();
            if (t.type() == TokenType.IDENT && LIST_END.contains(t.upper())) {
                next();
                if (t.is("END")) {
                    if (atAny("IF", "LOOP", "CASE", "WHILE", "FOR", "REPEAT")) {
                        next();
                    }
                    if (peek().isIdentifier() && peek(1).isSymbol(";")) {
                        next();
                    }
                }
                continue;
            }
            int before = pos;
            declarationSinks.push(extraDeclarations);
            try {
                extra.addAll(statements());
            } finally {
                declarationSinks.pop();
            }
            if (pos == before) {
                next();
            }
        }
        if (extra.isEmpty() && extraDeclarations.isEmpty()) {
            return body;
        }
        List<Routine.Decl> declarations = new ArrayList<>(body.declarations());
        declarations.addAll(extraDeclarations);
        List<Routine.Stmt> statements = new ArrayList<>(body.statements());
        statements.addAll(extra);
        return new Routine.Block(body.label(), declarations, statements, body.handlers(), body.atomic(), body.pos());
    }

    // ------------------------------------------------------------------ 조건부 컴파일

    /** {@code $IF .. $THEN}, {@code $ELSIF .. $THEN}, {@code $ELSE}, {@code $END}, {@code $ERROR .. $END}를 걷어낸다(가지의 본문은 모두 남긴다). */
    private static List<Token> stripConditionalCompilation(List<Token> tokens) {
        boolean any = false;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (isDirective(tokens, i)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return tokens;
        }
        List<Token> out = new ArrayList<>(tokens.size());
        int i = 0;
        int n = tokens.size();
        while (i < n) {
            if (isDirective(tokens, i)) {
                String word = tokens.get(i + 1).upper();
                i += 2;
                if (word.equals("IF") || word.equals("ELSIF")) {
                    while (i < n && !(isDirective(tokens, i) && tokens.get(i + 1).is("THEN")) && !tokens.get(i).isEof()) {
                        i++;
                    }
                    if (i < n && !tokens.get(i).isEof()) {
                        i += 2;
                    }
                } else if (word.equals("ERROR")) {
                    while (i < n && !(isDirective(tokens, i) && tokens.get(i + 1).is("END")) && !tokens.get(i).isEof()) {
                        i++;
                    }
                    if (i < n && !tokens.get(i).isEof()) {
                        i += 2;
                    }
                }
                continue;
            }
            out.add(tokens.get(i));
            i++;
        }
        return out;
    }

    private static boolean isDirective(List<Token> tokens, int i) {
        if (i + 1 >= tokens.size()) {
            return false;
        }
        Token a = tokens.get(i);
        Token b = tokens.get(i + 1);
        return a.isSymbol("$") && b.type() == TokenType.IDENT && a.endOffset() == b.offset()
                && b.isAny("IF", "THEN", "ELSIF", "ELSE", "END", "ERROR");
    }

    // ------------------------------------------------------------------ 토큰 구간 도구

    private int eofIndex() {
        return tokens.size() - 1;
    }

    private Token tokenAt(int index) {
        return tokens.get(Math.max(0, Math.min(index, eofIndex())));
    }

    private Pos span(int from, int toExclusive) {
        if (toExclusive <= from) {
            return Pos.of(tokenAt(from));
        }
        return Pos.span(tokenAt(from), tokenAt(toExclusive - 1));
    }

    private String text(int from, int toExclusive) {
        if (toExclusive <= from) {
            return "";
        }
        return joinTokens(tokens.subList(from, Math.min(toExclusive, eofIndex()))).replace(" %", "%");
    }

    private static String norm(Token t) {
        return DbName.normalize(t.text());
    }

    /** from부터 처음 나오는 {@code ;}의 인덱스(괄호 깊이 무시). 없으면 EOF 인덱스. */
    private int semicolon(int from) {
        int n = eofIndex();
        for (int i = from; i < n; i++) {
            if (tokens.get(i).isSymbol(";")) {
                return i;
            }
        }
        return n;
    }

    /** 선언부에서: 다음 {@code ;}까지 건너뛴다. */
    private void skipToSemicolon() {
        pos = semicolon(pos);
        acceptSymbol(";");
    }

    /** 문장의 끝을 소비한다. 꼬리에 남은 토큰은 식의 끝 규칙으로 건너뛴다(다음 문장이나 END를 삼키지 않는다). */
    private void endStatement() {
        if (acceptSymbol(";") || atEnd() || atListEnd()) {
            return;
        }
        pos = Math.max(pos, exprEnd(pos, false));
        acceptSymbol(";");
    }

    /** open 위치의 여는 괄호와 짝인 닫는 괄호의 인덱스. limit(제외) 안에 없으면 limit. */
    private int matchingParen(int open, int limit) {
        int d = 0;
        for (int i = open; i < limit; i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                d++;
            } else if (t.isSymbol(")")) {
                d--;
                if (d == 0) {
                    return i;
                }
            }
        }
        return limit;
    }

    private boolean prevIsDot(int index, int from) {
        return index > from && tokens.get(index - 1).isSymbol(".");
    }

    /** index의 토큰이 쿼리를 시작하는가(SELECT, WITH 이름, 괄호 뒤의 SELECT/WITH). */
    private boolean queryStartsAt(int index) {
        int i = index;
        while (tokenAt(i).isSymbol("(") && i < eofIndex()) {
            i++;
        }
        Token t = tokenAt(i);
        if (t.is("SELECT")) {
            return true;
        }
        if (i == index && t.is("VALUES")) {
            return true;
        }
        return t.is("WITH") && tokenAt(i + 1).isIdentifier() && !tokenAt(i + 1).isAny("UR", "CS", "RS", "RR");
    }

    /**
     * from에서 시작하는 SQL 구문의 끝(제외): 첫 {@code ;}. 종결자가 빠진 경우를 위해 CASE 밖의 {@code END}와
     * 줄 첫머리의 블록 키워드에서도 끊는다.
     */
    private int sqlEnd(int from) {
        int caseDepth = 0;
        int n = eofIndex();
        int i = from;
        while (i < n) {
            Token t = tokens.get(i);
            if (t.isSymbol(";")) {
                break;
            }
            if (t.type() == TokenType.IDENT && !prevIsDot(i, from)) {
                String u = t.upper();
                Token after = tokenAt(i + 1);
                if (u.equals("CASE")) {
                    caseDepth++;
                } else if (u.equals("END")) {
                    if (caseDepth > 0) {
                        caseDepth--;
                    } else if (i > from && (t.firstOnLine() || after.isSymbol(";") || after.isEof() || after.isAny("IF", "LOOP"))) {
                        problem("문장 종결자(;)가 없음", t, false);
                        break;
                    }
                } else if (i > from && caseDepth == 0 && t.firstOnLine() && SQL_LINE_STOPS.contains(u)
                        && !after.isSymbol(",") && !after.isSymbol(".") && !after.isSymbol(")") && !after.isSymbol("=")) {
                    problem("문장 종결자(;)가 없음", t, false);
                    break;
                }
            }
            i++;
        }
        return i;
    }

    /**
     * 현재 위치에서 시작하는 식의 끝(제외)을 찾는다: {@code ;}, 짝 없는 {@code )}, 괄호·CASE 밖의 stops 키워드,
     * CASE 밖의 {@code END}/{@code ELSE}/{@code ELSIF}/{@code EXCEPTION}, 줄 첫머리의 문장 키워드.
     */
    private int exprEnd(int from, boolean stopAtComma, String... stops) {
        int parens = 0;
        int caseDepth = 0;
        int n = eofIndex();
        int i = from;
        while (i < n) {
            Token t = tokens.get(i);
            if (t.isSymbol(";")) {
                break;
            }
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                if (parens == 0) {
                    break;
                }
                parens--;
            } else if (stopAtComma && parens == 0 && caseDepth == 0 && t.isSymbol(",")) {
                break;
            } else if (t.type() == TokenType.IDENT && !prevIsDot(i, from)) {
                String u = t.upper();
                if (u.equals("CASE")) {
                    caseDepth++;
                } else if (u.equals("END")) {
                    if (caseDepth > 0) {
                        caseDepth--;
                    } else {
                        break;
                    }
                } else if (caseDepth == 0 && parens == 0) {
                    if (contains(stops, u)) {
                        break;
                    }
                    if (u.equals("ELSIF") || u.equals("ELSEIF") || u.equals("ELSE") || (u.equals("EXCEPTION") && tokenAt(i + 1).is("WHEN"))) {
                        break;
                    }
                    if (i > from && t.firstOnLine() && EXPR_LINE_STOPS.contains(u) && !tokenAt(i + 1).isSymbol(".")) {
                        problem("문장 종결자(;)가 없음", t, false);
                        break;
                    }
                }
            }
            i++;
        }
        return i;
    }

    private static boolean contains(String[] words, String word) {
        for (String w : words) {
            if (w.equals(word)) {
                return true;
            }
        }
        return false;
    }

    /** 현재 위치부터 식을 읽는다. */
    private Routine.Expr scanExpr(String... stops) {
        int end = exprEnd(pos, false, stops);
        Routine.Expr e = expr(pos, end);
        pos = end;
        return e;
    }

    /** 토큰 구간을 식으로 만든다: 서브쿼리와 이름 참조를 뽑는다. */
    private Routine.Expr expr(int from, int toExclusive) {
        int to = Math.min(toExclusive, eofIndex());
        if (to <= from) {
            return new Routine.Expr(List.of(), List.of(), List.of(), Pos.of(tokenAt(from)));
        }
        List<Routine.Sql> subqueries = new ArrayList<>();
        List<Routine.NameRef> refs = new ArrayList<>();
        int i = from;
        while (i < to) {
            Token t = tokens.get(i);
            if (t.isSymbol("(") && i + 1 < to && (tokens.get(i + 1).is("SELECT")
                    || (tokens.get(i + 1).is("WITH") && tokenAt(i + 2).isIdentifier()))) {
                int close = matchingParen(i, to);
                subqueries.add(sql(i + 1, close));
                i = Math.min(to, close + 1);
                continue;
            }
            if (t.isIdentifier() && !(i > from && (tokens.get(i - 1).isSymbol(".") || tokens.get(i - 1).isSymbol("%")))) {
                List<String> parts = new ArrayList<>();
                parts.add(t.text());
                Token last = t;
                int j = i + 1;
                while (j + 1 < to && tokens.get(j).isSymbol(".") && tokens.get(j + 1).isIdentifier()) {
                    last = tokens.get(j + 1);
                    parts.add(last.text());
                    j += 2;
                }
                boolean call = j < to && tokens.get(j).isSymbol("(");
                boolean argumentName = j < to && tokens.get(j).isSymbol("=>");
                if (!argumentName) {
                    refs.add(new Routine.NameRef(new QName(parts, null, Pos.span(t, last)), call));
                }
                i = j;
                continue;
            }
            i++;
        }
        return new Routine.Expr(tokens.subList(from, to), subqueries, refs, span(from, to));
    }

    /** 토큰 구간을 SQL 구문으로 파싱한다. */
    private Routine.Sql sql(int from, int toExclusive) {
        int to = Math.max(from, Math.min(toExclusive, eofIndex()));
        List<Token> sub = new ArrayList<>(tokens.subList(from, to));
        Statement statement;
        List<QName> salvaged = List.of();
        List<Routine.Problem> found = new ArrayList<>();
        if (sub.isEmpty() || sqlDepth >= SqlParser.MAX_DEPTH) {
            statement = new Statement.Empty();
        } else {
            SqlParser parser = new SqlParser(sub);
            parser.depth = sqlDepth;
            ParseResult result = parser.parseStatement();
            statement = result.statement();
            salvaged = result.salvagedReads();
            for (ParseProblem p : result.problems()) {
                found.add(new Routine.Problem(p.message(), p.pos(), p.minor()));
            }
        }
        return new Routine.Sql(statement, salvaged, found, sub, intoTargets(sub), span(from, to));
    }

    /** {@code SELECT .. INTO a, b}, {@code RETURNING .. INTO a}의 대상 변수 이름. */
    private static List<String> intoTargets(List<Token> sub) {
        if (sub.isEmpty()) {
            return List.of();
        }
        boolean selectLike = sub.get(0).isAny("SELECT", "WITH", "VALUES");
        boolean afterReturning = false;
        List<String> names = new ArrayList<>();
        int parens = 0;
        for (int i = 0; i < sub.size(); i++) {
            Token t = sub.get(i);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.isAny("RETURNING", "RETURN")) {
                afterReturning = true;
            } else if (parens == 0 && t.is("INTO") && (selectLike || afterReturning) && !(i > 0 && sub.get(i - 1).isAny("INSERT", "MERGE"))) {
                int j = i + 1;
                while (j < sub.size()) {
                    Token x = sub.get(j);
                    if (x.type() == TokenType.BIND) {
                        names.add(x.text().substring(x.text().startsWith(":") ? 1 : 0).toUpperCase(java.util.Locale.ROOT));
                    } else if (x.isIdentifier() && !x.isAny("FROM", "USING", "WHERE", "LIMIT")) {
                        names.add(DbName.normalize(x.text()));
                    } else {
                        break;
                    }
                    j++;
                    int d = 0;
                    while (j < sub.size() && !(d == 0 && (sub.get(j).isSymbol(",") || sub.get(j).isAny("FROM", "USING", "WHERE", "LIMIT")))) {
                        if (sub.get(j).isSymbol("(")) {
                            d++;
                        } else if (sub.get(j).isSymbol(")")) {
                            d--;
                        }
                        j++;
                    }
                    if (j < sub.size() && sub.get(j).isSymbol(",")) {
                        j++;
                    } else {
                        break;
                    }
                }
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ 선언부

    /** PL/SQL 선언부를 {@code BEGIN}, {@code END}, 끝까지 읽는다. */
    private List<Routine.Decl> declarations() {
        List<Routine.Decl> list = new ArrayList<>();
        while (!atEnd() && !at("BEGIN") && !at("END")) {
            if (acceptSymbol(";")) {
                continue;
            }
            Token t = peek();
            if (t.type() == TokenType.IDENT && STATEMENT_KEYWORDS.contains(t.upper())) {
                problem("선언부에 문장이 있음(BEGIN이 없음): " + t.text(), t, false);
                break;
            }
            int before = pos;
            Routine.Decl d = declaration();
            if (d != null) {
                list.add(d);
            }
            if (pos == before) {
                next();
            }
        }
        return list;
    }

    private Routine.Decl declaration() {
        int start = pos;
        Token t = peek();
        if (t.is("CURSOR") && peek(1).isIdentifier()) {
            return cursorDeclaration();
        }
        if (t.isAny("TYPE", "SUBTYPE") && peek(1).isIdentifier() && !peek(1).isAny("IS", "CONSTANT")) {
            next();
            String name = norm(next());
            skipToSemicolon();
            return new Routine.TypeDef(name, span(start, pos));
        }
        if (t.is("PRAGMA")) {
            next();
            skipToSemicolon();
            return new Routine.OtherDecl("PRAGMA", span(start, pos));
        }
        if (t.isAny("PROCEDURE", "FUNCTION") && peek(1).isIdentifier()) {
            return subprogram();
        }
        if (t.isIdentifier()) {
            String name = norm(next());
            if (at("EXCEPTION")) {
                skipToSemicolon();
                return new Routine.Variable(List.of(name), "EXCEPTION", null, null, span(start, pos));
            }
            boolean constant = accept("CONSTANT");
            int typeStart = pos;
            int parens = 0;
            while (!atEnd() && !atSymbol(";")) {
                if (atSymbol("(")) {
                    parens++;
                } else if (atSymbol(")")) {
                    parens--;
                } else if (parens <= 0 && (atSymbol(":=") || at("DEFAULT"))) {
                    break;
                }
                next();
            }
            String type = text(typeStart, pos);
            Routine.Expr init = null;
            if (atSymbol(":=") || at("DEFAULT")) {
                next();
                init = scanExpr();
            }
            acceptSymbol(";");
            return new Routine.Variable(List.of(name), constant ? "CONSTANT" : "VARIABLE", type, init, span(start, pos));
        }
        problem("선언을 해석하지 못함: " + t.text(), t, false);
        skipToSemicolon();
        return null;
    }

    private Routine.Decl cursorDeclaration() {
        int start = pos;
        next(); // CURSOR
        String name = norm(next());
        List<Routine.Param> params = atSymbol("(") ? parameters() : List.of();
        while (!atEnd() && !atSymbol(";") && !at("IS")) {
            next();
        }
        Routine.Sql query = null;
        if (accept("IS")) {
            int end = sqlEnd(pos);
            query = sql(pos, end);
            pos = end;
        }
        acceptSymbol(";");
        return new Routine.Cursor(name, params, query, null, span(start, pos));
    }

    /** {@code (이름 [IN|OUT|IN OUT] [NOCOPY] 타입 [:= 식], ..)} 또는 DB2의 {@code (IN 이름 타입, ..)}. */
    private List<Routine.Param> parameters() {
        int open = pos;
        int close = matchingParen(open, semicolon(open));
        List<Routine.Param> params = new ArrayList<>();
        int segStart = open + 1;
        int parens = 0;
        for (int i = open + 1; i <= close && i <= eofIndex(); i++) {
            Token t = tokens.get(i);
            boolean end = i == close;
            if (!end && t.isSymbol("(")) {
                parens++;
            } else if (!end && t.isSymbol(")")) {
                parens--;
            }
            if (end || (parens == 0 && t.isSymbol(","))) {
                Routine.Param p = parameter(segStart, i);
                if (p != null) {
                    params.add(p);
                }
                segStart = i + 1;
            }
        }
        pos = Math.min(close + 1, eofIndex());
        return params;
    }

    private Routine.Param parameter(int from, int to) {
        if (to <= from) {
            return null;
        }
        int i = from;
        String mode = null;
        if (tokens.get(i).isAny("IN", "OUT", "INOUT") && i + 1 < to && tokens.get(i + 1).isIdentifier()
                && !tokens.get(i + 1).isAny("OUT", "NOCOPY")) {
            mode = tokens.get(i).upper();
            i++;
        }
        if (!tokens.get(i).isIdentifier()) {
            return null;
        }
        String name = norm(tokens.get(i));
        i++;
        StringBuilder m = new StringBuilder(mode == null ? "" : mode);
        while (i < to && tokens.get(i).isAny("IN", "OUT", "INOUT", "NOCOPY")) {
            if (!tokens.get(i).is("NOCOPY")) {
                m.append(m.length() == 0 ? "" : " ").append(tokens.get(i).upper());
            }
            i++;
        }
        int typeStart = i;
        while (i < to && !tokens.get(i).isSymbol(":=") && !tokens.get(i).is("DEFAULT")) {
            i++;
        }
        String type = text(typeStart, i);
        Routine.Expr defaultValue = i < to ? expr(i + 1, to) : null;
        return new Routine.Param(name, m.length() == 0 ? null : m.toString(), type, defaultValue);
    }

    /** {@code RETURN} 뒤의 타입 표기: 이름[.이름][%속성][(..)]. */
    private String typeText() {
        int start = pos;
        if (peek().isIdentifier()) {
            next();
            while (atSymbol(".") && peek(1).isIdentifier()) {
                next();
                next();
            }
            if (atSymbol("%") && peek(1).isIdentifier()) {
                next();
                next();
            }
            if (atSymbol("(")) {
                pos = Math.min(matchingParen(pos, semicolon(pos)) + 1, eofIndex());
            }
        }
        return text(start, pos);
    }

    private Routine.Subprogram subprogram() {
        int start = pos;
        String kind = next().upper();
        String name = norm(next());
        if (depth >= MAX_DEPTH) {
            problem("중첩이 너무 깊어 건너뜀", peek(), false);
            pos = eofIndex();
            return new Routine.Subprogram(kind, name, List.of(), null, null, span(start, pos));
        }
        depth++;
        try {
            List<Routine.Param> params = atSymbol("(") ? parameters() : List.of();
            String returnType = null;
            while (!atEnd() && !atSymbol(";") && !atAny("IS", "AS", "BEGIN")) {
                if (atAny("RETURN", "RETURNS") && returnType == null) {
                    next();
                    returnType = typeText();
                } else if (atSymbol("(")) {
                    pos = Math.min(matchingParen(pos, semicolon(pos)) + 1, eofIndex());
                } else {
                    next();
                }
            }
            if (atSymbol(";") || atEnd()) {
                acceptSymbol(";");
                return new Routine.Subprogram(kind, name, params, returnType, null, span(start, pos));
            }
            Routine.Block body;
            if (acceptAny("IS", "AS")) {
                if (atAny("LANGUAGE", "EXTERNAL")) {
                    skipToSemicolon();
                    return new Routine.Subprogram(kind, name, params, returnType, null, span(start, pos));
                }
                int bodyStart = pos;
                body = bodyAfterDeclarations(null, declarations(), bodyStart);
            } else {
                body = block(null, List.of(), pos);
            }
            return new Routine.Subprogram(kind, name, params, returnType, body, span(start, pos));
        } finally {
            depth--;
        }
    }

    /** 선언부 다음: {@code BEGIN}이 있으면 블록, 없으면 문제를 남기고 이어지는 문장들을 본문으로 본다. */
    private Routine.Block bodyAfterDeclarations(String label, List<Routine.Decl> declarations, int start) {
        if (at("BEGIN")) {
            return block(label, declarations, start);
        }
        problem("BEGIN이 없음", peek(), false);
        if (atEnd()) {
            return new Routine.Block(label, declarations, List.of(), List.of(), false, span(start, pos));
        }
        return blockBody(label, declarations, false, start);
    }

    // ------------------------------------------------------------------ 블록과 문장 목록

    /** 현재 위치가 {@code BEGIN}이다. */
    private Routine.Block block(String label, List<Routine.Decl> declarations, int start) {
        next(); // BEGIN
        boolean atomic = false;
        if (accept("ATOMIC")) {
            atomic = true;
        } else if (at("NOT") && peek(1).is("ATOMIC")) {
            next();
            next();
        }
        return blockBody(label, declarations, atomic, start);
    }

    private Routine.Block blockBody(String label, List<Routine.Decl> declarations, boolean atomic, int start) {
        List<Routine.Decl> all = new ArrayList<>(declarations);
        List<Routine.Stmt> body;
        List<Routine.ExceptionHandler> handlers = new ArrayList<>();
        declarationSinks.push(all);
        try {
            body = statements();
            if (at("EXCEPTION")) {
                next();
                while (at("WHEN")) {
                    Token when = next();
                    List<String> names = new ArrayList<>();
                    while (!atEnd() && !at("THEN") && !atSymbol(";")) {
                        Token x = next();
                        if (x.isIdentifier() && !x.is("OR")) {
                            names.add(x.upper());
                        }
                    }
                    if (!accept("THEN")) {
                        problem("예외 처리부에 THEN이 없음", peek(), false);
                    }
                    handlers.add(new Routine.ExceptionHandler(names, statements(), Pos.of(when)));
                }
            }
        } finally {
            declarationSinks.pop();
        }
        endOf(null);
        return new Routine.Block(label, all, body, handlers, atomic, span(start, pos));
    }

    /**
     * 구조의 끝을 소비한다. kind가 null이면 블록의 {@code END [이름];}, 아니면 {@code END kind [레이블];}.
     * 짝이 맞지 않으면 문제를 남기고 소비하지 않는다(바깥 구조가 처리한다).
     */
    private void endOf(String kind) {
        if (!at("END")) {
            problem(kind == null ? "END가 없음" : "END " + kind + "가 없음", peek(), false);
            return;
        }
        Token after = peek(1);
        if (kind == null) {
            if (after.isAny("IF", "LOOP", "CASE", "WHILE", "FOR", "REPEAT")) {
                problem("블록의 END가 없음(END " + after.upper() + "를 만남)", peek(), false);
                return;
            }
            next();
        } else {
            if (!after.is(kind)) {
                problem("END " + kind + "가 없음", peek(), false);
                return;
            }
            next();
            next();
        }
        if (peek().isIdentifier() && (peek(1).isSymbol(";") || peek(1).isEof())
                && !(peek().type() == TokenType.IDENT && LIST_END.contains(peek().upper()))) {
            next(); // 이름·레이블
        }
        acceptSymbol(";");
    }

    private boolean atListEnd() {
        Token t = peek();
        return t.type() == TokenType.IDENT && LIST_END.contains(t.upper()) && !peek(1).isSymbol(":=") && !peek(1).isSymbol(".");
    }

    private List<Routine.Stmt> statements() {
        List<Routine.Stmt> list = new ArrayList<>();
        while (!atEnd()) {
            if (acceptSymbol(";")) {
                continue;
            }
            if (atListEnd()) {
                break;
            }
            int before = pos;
            Routine.Stmt s = statement();
            if (s != null) {
                list.add(s);
            }
            if (!pending.isEmpty()) {
                list.addAll(pending);
                pending.clear();
            }
            if (pos == before) {
                next();
            }
        }
        return list;
    }

    /** 문장 하나(핸들러 본문, 단일 문장 본문). 한 문장이 여러 대입으로 풀리면 블록으로 감싼다. */
    private Routine.Stmt statementSingle() {
        Routine.Stmt s = statement();
        if (pending.isEmpty()) {
            return s;
        }
        List<Routine.Stmt> all = new ArrayList<>();
        if (s != null) {
            all.add(s);
        }
        all.addAll(pending);
        pending.clear();
        return new Routine.Block(null, List.of(), all, List.of(), false, all.get(0).pos());
    }

    private void addDeclaration(Routine.Decl d) {
        if (declarationSinks.isEmpty()) {
            rootDeclarations.add(d);
        } else {
            declarationSinks.peek().add(d);
        }
    }

    // ------------------------------------------------------------------ 문장

    private Routine.Stmt statement() {
        if (depth >= MAX_DEPTH) {
            problem("중첩이 너무 깊어 건너뜀", peek(), false);
            return unknown(pos);
        }
        depth++;
        try {
            return statement0();
        } finally {
            depth--;
        }
    }

    private Routine.Stmt statement0() {
        String label = null;
        while (true) {
            if (atSymbol("<<") && peek(1).isIdentifier() && peek(2).isSymbol(">>")) {
                next();
                label = norm(next());
                next();
            } else if (peek().type() == TokenType.IDENT && peek(1).isSymbol(":") && !atListEnd()) {
                label = norm(next());
                next();
            } else {
                break;
            }
        }
        if (atEnd() || atListEnd() || atSymbol(";")) {
            return null; // 레이블만 있음
        }
        int start = pos;
        Token t = peek();
        if (t.type() == TokenType.BIND || t.type() == TokenType.QUOTED_IDENT) {
            return assignOrCall(start);
        }
        if (t.type() != TokenType.IDENT) {
            return unknown(start);
        }
        Token n = peek(1);
        if (n.isSymbol(":=") || n.isSymbol(".")) {
            return assignOrCall(start); // 키워드와 같은 이름의 변수·패키지
        }
        if (n.isSymbol("(") && CALLABLE_KEYWORDS.contains(t.upper())) {
            return assignOrCall(start); // 키워드와 같은 이름의 사용자 프로시저: PREPARE(1);
        }
        switch (t.upper()) {
            case "DECLARE":
                return declareStatement(label, start);
            case "BEGIN":
                return block(label, List.of(), start);
            case "IF":
                return ifStatement(start);
            case "CASE":
                return caseStatement(start);
            case "LOOP": {
                next();
                List<Routine.Stmt> body = statements();
                endOf("LOOP");
                return new Routine.Loop(Routine.LoopKind.BASIC, label, null, null, null, null, body, span(start, pos));
            }
            case "WHILE": {
                next();
                Routine.Expr condition = scanExpr("LOOP", "DO");
                String end = at("DO") ? "WHILE" : "LOOP";
                if (!acceptAny("LOOP", "DO")) {
                    problem("WHILE에 LOOP/DO가 없음", peek(), false);
                }
                List<Routine.Stmt> body = statements();
                endOf(end);
                return new Routine.Loop(Routine.LoopKind.WHILE, label, null, condition, null, null, body, span(start, pos));
            }
            case "REPEAT": {
                next();
                List<Routine.Stmt> body = statements();
                Routine.Expr until = accept("UNTIL") ? scanExpr() : null;
                endOf("REPEAT");
                return new Routine.Loop(Routine.LoopKind.REPEAT, label, null, until, null, null, body, span(start, pos));
            }
            case "FOR":
                return forLoop(label, start);
            case "FORALL":
                return forAll(start);
            case "SELECT":
            case "INSERT":
            case "UPDATE":
            case "DELETE":
            case "MERGE":
            case "WITH":
            case "VALUES":
            case "LOCK":
            case "TRUNCATE":
                return sqlStatement();
            case "OPEN":
                return openStatement(start);
            case "FETCH": {
                next();
                acceptAny("NEXT", "PRIOR", "FIRST", "LAST", "CURRENT");
                accept("FROM");
                String cursor = cursorName();
                Routine.Expr into = null;
                acceptSeq("BULK", "COLLECT");
                if (accept("INTO")) {
                    into = scanExpr("LIMIT");
                }
                if (accept("LIMIT")) {
                    scanExpr();
                }
                endStatement();
                return new Routine.Fetch(cursor, into, span(start, pos));
            }
            case "CLOSE": {
                next();
                String cursor = cursorName();
                endStatement();
                return new Routine.Close(cursor, span(start, pos));
            }
            case "EXECUTE":
            case "EXEC":
                return executeStatement(start);
            case "PREPARE": {
                next();
                String name = peek().isIdentifier() ? norm(next()) : null;
                while (!atEnd() && !atSymbol(";") && !at("FROM")) {
                    next();
                }
                Routine.Expr sql = accept("FROM") ? scanExpr() : null;
                endStatement();
                return new Routine.Prepare(name, sql, span(start, pos));
            }
            case "CALL":
                next();
                if (!peek().isIdentifier()) {
                    return unknown(start);
                }
                return assignOrCall(start);
            case "SET":
                return setStatement(start);
            case "RETURN": {
                next();
                if (!atSymbol("(") && queryStartsAt(pos)) {
                    int end = sqlEnd(pos);
                    Routine.Sql query = sql(pos, end);
                    pos = end;
                    acceptSymbol(";");
                    return new Routine.Return(null, query, span(start, pos));
                }
                Routine.Expr value = atSymbol(";") || atEnd() ? null : scanExpr();
                acceptSymbol(";");
                return new Routine.Return(value, null, span(start, pos));
            }
            case "RAISE":
            case "SIGNAL":
            case "RESIGNAL": {
                String kind = next().upper();
                Routine.Expr detail = scanExpr();
                endStatement();
                return new Routine.Raise(kind, detail, span(start, pos));
            }
            case "GOTO":
            case "LEAVE":
            case "ITERATE": {
                String kind = next().upper();
                String target = peek().isIdentifier() ? norm(next()) : null;
                endStatement();
                return new Routine.Jump(kind, target, null, span(start, pos));
            }
            case "EXIT":
            case "CONTINUE": {
                String kind = next().upper();
                String target = peek().isIdentifier() && !at("WHEN") ? norm(next()) : null;
                Routine.Expr when = accept("WHEN") ? scanExpr() : null;
                endStatement();
                return new Routine.Jump(kind, target, when, span(start, pos));
            }
            case "NULL":
            case "COMMIT":
            case "ROLLBACK":
            case "SAVEPOINT":
            case "PRAGMA":
            case "GET": {
                String keyword = next().upper();
                endStatement();
                return new Routine.Simple(keyword, null, span(start, pos));
            }
            case "PIPE": {
                next();
                accept("ROW");
                Routine.Expr detail = scanExpr();
                endStatement();
                return new Routine.Simple("PIPE ROW", detail, span(start, pos));
            }
            default:
                if (PASS_THROUGH.contains(t.upper()) && n.type() == TokenType.IDENT) {
                    String keyword = next().upper();
                    int end = semicolon(pos);
                    Routine.Expr detail = expr(pos, end);
                    pos = end;
                    acceptSymbol(";");
                    return new Routine.Simple(keyword, detail, span(start, pos));
                }
                return assignOrCall(start);
        }
    }

    private Routine.Stmt sqlStatement() {
        int end = sqlEnd(pos);
        Routine.Sql s = sql(pos, end);
        pos = end;
        acceptSymbol(";");
        return s;
    }

    private Routine.Stmt ifStatement(int start) {
        next(); // IF
        List<Routine.Branch> branches = new ArrayList<>();
        List<Routine.Stmt> otherwise = List.of();
        Routine.Expr condition = scanExpr("THEN");
        if (!accept("THEN")) {
            problem("IF에 THEN이 없음", peek(), false);
        }
        branches.add(new Routine.Branch(condition, statements()));
        while (true) {
            if (atAny("ELSIF", "ELSEIF")) {
                next();
                Routine.Expr c = scanExpr("THEN");
                if (!accept("THEN")) {
                    problem("ELSIF에 THEN이 없음", peek(), false);
                }
                branches.add(new Routine.Branch(c, statements()));
            } else if (at("ELSE")) {
                next();
                otherwise = statements();
                break;
            } else {
                break;
            }
        }
        endOf("IF");
        return new Routine.If(branches, otherwise, span(start, pos));
    }

    private Routine.Stmt caseStatement(int start) {
        next(); // CASE
        Routine.Expr selector = at("WHEN") ? null : scanExpr("WHEN");
        List<Routine.Branch> branches = new ArrayList<>();
        List<Routine.Stmt> otherwise = List.of();
        while (at("WHEN")) {
            next();
            Routine.Expr c = scanExpr("THEN");
            if (!accept("THEN")) {
                problem("CASE의 WHEN에 THEN이 없음", peek(), false);
            }
            branches.add(new Routine.Branch(c, statements()));
        }
        if (accept("ELSE")) {
            otherwise = statements();
        }
        endOf("CASE");
        return new Routine.Case(selector, branches, otherwise, span(start, pos));
    }

    private Routine.Stmt forLoop(String label, int start) {
        next(); // FOR
        String variable = peek().isIdentifier() ? norm(next()) : null;
        if (accept("AS")) {
            // DB2: FOR v AS [c [ASENSITIVE|..] CURSOR [WITH HOLD] FOR] select DO .. END FOR
            String cursor = null;
            if (peek().isIdentifier() && (peek(1).is("CURSOR") || (peek(1).isAny("ASENSITIVE", "INSENSITIVE", "SENSITIVE") && peek(2).is("CURSOR")))) {
                cursor = norm(next());
                while (!atEnd() && !atSymbol(";") && !at("FOR")) {
                    next();
                }
                accept("FOR");
            }
            int end = pos;
            int parens = 0;
            int n = eofIndex();
            while (end < n) {
                Token t = tokens.get(end);
                if (t.isSymbol(";")) {
                    break;
                }
                if (t.isSymbol("(")) {
                    parens++;
                } else if (t.isSymbol(")")) {
                    parens--;
                } else if (parens <= 0 && t.is("DO")) {
                    break;
                }
                end++;
            }
            Routine.Sql query = sql(pos, end);
            pos = end;
            if (!accept("DO")) {
                problem("FOR에 DO가 없음", peek(), false);
            }
            List<Routine.Stmt> body = statements();
            endOf("FOR");
            return new Routine.Loop(Routine.LoopKind.FOR_QUERY, label, variable, null, cursor, query, body, true, span(start, pos));
        }
        if (!accept("IN")) {
            problem("FOR에 IN이 없음", peek(), false);
        }
        accept("REVERSE");
        Routine.LoopKind kind;
        Routine.Expr control = null;
        Routine.Sql query = null;
        String cursor = null;
        if (atSymbol("(") && queryStartsAt(pos + 1)) {
            int close = matchingParen(pos, semicolon(pos));
            query = sql(pos + 1, close);
            pos = Math.min(close + 1, eofIndex());
            kind = Routine.LoopKind.FOR_QUERY;
        } else {
            control = scanExpr("LOOP");
            boolean range = control.tokens().stream().anyMatch(x -> x.isSymbol(".."));
            kind = range ? Routine.LoopKind.FOR_RANGE : Routine.LoopKind.FOR_CURSOR;
            if (!range && !control.refs().isEmpty()) {
                cursor = control.refs().get(0).name().display();
            }
        }
        if (!accept("LOOP")) {
            problem("FOR에 LOOP가 없음", peek(), false);
        }
        List<Routine.Stmt> body = statements();
        endOf("LOOP");
        return new Routine.Loop(kind, label, variable, control, cursor, query, body, span(start, pos));
    }

    private Routine.Stmt forAll(int start) {
        next(); // FORALL
        String index = peek().isIdentifier() ? norm(next()) : null;
        accept("IN");
        int from = pos;
        int parens = 0;
        int n = eofIndex();
        int i = pos;
        while (i < n) {
            Token t = tokens.get(i);
            if (t.isSymbol(";")) {
                break;
            }
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens <= 0 && t.isAny("INSERT", "UPDATE", "DELETE", "MERGE", "EXECUTE") && !prevIsDot(i, from)) {
                break;
            }
            i++;
        }
        Routine.Expr bounds = expr(from, i);
        pos = i;
        Routine.Stmt dml = atSymbol(";") || atEnd() ? null : statement();
        acceptSymbol(";");
        return new Routine.ForAll(index, bounds, dml, span(start, pos));
    }

    /** 커서 이름: 이름 사슬 또는 바인드. 없으면 null. */
    private String cursorName() {
        if (peek().type() == TokenType.BIND) {
            return next().text().toUpperCase(java.util.Locale.ROOT);
        }
        if (!peek().isIdentifier()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(norm(next()));
        while (atSymbol(".") && peek(1).isIdentifier()) {
            next();
            sb.append('.').append(norm(next()));
        }
        return sb.toString();
    }

    private Routine.Stmt openStatement(int start) {
        next(); // OPEN
        String cursor = cursorName();
        Routine.Expr args = null;
        if (atSymbol("(")) {
            int close = matchingParen(pos, semicolon(pos));
            args = expr(pos + 1, close);
            pos = Math.min(close + 1, eofIndex());
        }
        Routine.Sql query = null;
        Routine.Expr dynamic = null;
        Routine.Expr using = null;
        if (accept("FOR")) {
            if (queryStartsAt(pos)) {
                int end = sqlEnd(pos);
                query = sql(pos, end);
                pos = end;
            } else {
                dynamic = scanExpr("USING");
            }
        }
        if (accept("USING")) {
            using = scanExpr();
        }
        endStatement();
        return new Routine.Open(cursor, args, query, dynamic, using, span(start, pos));
    }

    private Routine.Stmt executeStatement(int start) {
        next(); // EXECUTE | EXEC
        if (accept("IMMEDIATE")) {
            Routine.Expr sql = scanExpr("INTO", "USING", "RETURNING", "RETURN", "BULK");
            Routine.Expr rest = atSymbol(";") || atEnd() ? null : scanExpr();
            endStatement();
            return new Routine.ExecuteImmediate(sql, rest, span(start, pos));
        }
        if (peek().isIdentifier() && !peek(1).isSymbol("(") && !peek(1).isSymbol(".")) {
            String name = norm(next());
            Routine.Expr rest = atSymbol(";") || atEnd() ? null : scanExpr();
            endStatement();
            return new Routine.Execute(name, rest, span(start, pos));
        }
        if (peek().isIdentifier()) {
            return assignOrCall(start); // EXEC proc(..)
        }
        return unknown(start);
    }

    /** 이름 사슬을 읽는다(현재 위치가 이름 또는 바인드). */
    private QName nameChain() {
        Token first = next();
        Token last = first;
        List<String> parts = new ArrayList<>();
        String head = first.text();
        if (first.type() == TokenType.BIND && head.startsWith(":") && head.length() > 1) {
            head = head.substring(1);
        }
        parts.add(head);
        while (atSymbol(".") && peek(1).isIdentifier()) {
            next();
            last = next();
            parts.add(last.text());
        }
        return new QName(parts, null, Pos.span(first, last));
    }

    private Routine.Stmt setStatement(int start) {
        next(); // SET
        if (atSymbol("(")) {
            // SET (a, b) = (..)
            int close = matchingParen(pos, semicolon(pos));
            List<QName> targets = new ArrayList<>();
            int i = pos + 1;
            while (i < close) {
                Token t = tokens.get(i);
                if (t.isIdentifier() && !(i > pos + 1 && tokens.get(i - 1).isSymbol("."))) {
                    List<String> parts = new ArrayList<>();
                    parts.add(t.text());
                    Token last = t;
                    while (i + 2 < close && tokens.get(i + 1).isSymbol(".") && tokens.get(i + 2).isIdentifier()) {
                        last = tokens.get(i + 2);
                        parts.add(last.text());
                        i += 2;
                    }
                    targets.add(new QName(parts, null, Pos.span(t, last)));
                }
                i++;
            }
            pos = Math.min(close + 1, eofIndex());
            if (acceptSymbol("=")) {
                Routine.Expr value = scanExpr();
                int end = pos;
                acceptSymbol(";");
                return new Routine.Assign(targets, null, value, statementTokens(start, end), span(start, pos));
            }
            pos = start + 1;
        } else if ((peek().isIdentifier() && !(peek().type() == TokenType.IDENT && SET_REGISTERS.contains(peek().upper())))
                || peek().type() == TokenType.BIND) {
            int save = pos;
            Routine.Assign first = setAssignment(start);
            if (first != null) {
                while (acceptSymbol(",")) {
                    if (!peek().isIdentifier() && peek().type() != TokenType.BIND) {
                        break;
                    }
                    Routine.Assign more = setAssignment(pos);
                    if (more == null) {
                        break;
                    }
                    pending.add(more);
                }
                endStatement();
                return first;
            }
            pos = save;
        }
        // SET TRANSACTION, SET CURRENT SCHEMA .., SET SCHEMA x 등
        int end = semicolon(pos);
        Routine.Expr detail = expr(pos, end);
        pos = end;
        acceptSymbol(";");
        return new Routine.Simple("SET", detail, span(start, pos));
    }

    /** {@code 이름[(첨자)] = 식} 하나. 대입 꼴이 아니면 null(위치는 호출한 쪽이 되돌린다). */
    private Routine.Assign setAssignment(int start) {
        QName target = nameChain();
        Routine.Expr index = null;
        if (atSymbol("(")) {
            int close = matchingParen(pos, semicolon(pos));
            index = expr(pos + 1, close);
            pos = Math.min(close + 1, eofIndex());
        }
        if (!acceptSymbol("=")) {
            return null;
        }
        int end = exprEnd(pos, true);
        Routine.Expr value = expr(pos, end);
        pos = end;
        return new Routine.Assign(List.of(target), index, value, statementTokens(start, pos), span(start, pos));
    }

    /** 문장의 토큰 구간(원문을 다시 얻는 데 쓴다). */
    private List<Token> statementTokens(int from, int toExclusive) {
        int to = Math.max(from, Math.min(toExclusive, eofIndex()));
        return tokens.subList(from, to);
    }

    /** 대입문({@code 대상 := 식}) 또는 프로시저 호출문({@code 이름[(인자)]}). {@code CALL} 다음에도 온다. */
    private Routine.Stmt assignOrCall(int start) {
        boolean callKeyword = tokenAt(start).is("CALL") && pos > start;
        int nameStart = pos;
        Token first = peek();
        QName name = nameChain();
        if (atSymbol("@") && peek(1).type() == TokenType.IDENT) {
            next();
            next();
            while (atSymbol(".") && peek(1).type() == TokenType.IDENT) {
                next();
                next();
            }
        }
        Routine.Expr args = null;
        int callEnd = pos;
        boolean chained = false;
        if (atSymbol("(")) {
            int close = matchingParen(pos, semicolon(pos));
            args = expr(pos + 1, close);
            pos = Math.min(close + 1, eofIndex());
            callEnd = pos;
            while ((atSymbol(".") && peek(1).isIdentifier()) || atSymbol("(")) {
                chained = true;
                if (atSymbol("(")) {
                    pos = Math.min(matchingParen(pos, semicolon(pos)) + 1, eofIndex());
                } else {
                    next();
                    next();
                }
            }
        }
        if (atSymbol(":=") && !callKeyword) {
            next();
            Routine.Expr value = scanExpr();
            int end = pos;
            acceptSymbol(";");
            return new Routine.Assign(List.of(name), args, value, statementTokens(start, end), span(start, pos));
        }
        boolean ends = atSymbol(";") || atEnd() || atListEnd();
        if (callKeyword && !ends) {
            // CALL p(..) INTO :x 같은 꼬리
            pos = semicolon(pos);
            ends = true;
        }
        if (ends && first.type() != TokenType.BIND) {
            if (chained) {
                Routine.Expr whole = expr(nameStart, pos);
                acceptSymbol(";");
                return new Routine.Simple("EXPRESSION", whole, span(start, pos));
            }
            Routine.Call call = new Routine.Call(name, args, callKeyword, tokens.subList(nameStart, callEnd), span(start, callEnd));
            acceptSymbol(";");
            return call;
        }
        return unknown(start);
    }

    /** 해석하지 못한 문장: 문장 끝까지 건너뛰고 그 안의 서브쿼리를 건진다. */
    private Routine.Stmt unknown(int start) {
        int end = Math.max(exprEnd(start, false), Math.min(pos, eofIndex()));
        Token keyword = tokenAt(start);
        Routine.Expr salvage = expr(start, end);
        pos = end;
        if (!acceptSymbol(";") && pos == start) {
            next();
        }
        String word = keyword.type() == TokenType.IDENT ? keyword.upper() : keyword.text();
        return new Routine.Unknown(word, salvage, span(start, Math.max(end, start + 1)));
    }

    // ------------------------------------------------------------------ DECLARE

    private Routine.Stmt declareStatement(String label, int start) {
        next(); // DECLARE
        if (atAny("CONTINUE", "EXIT", "UNDO") && peek(1).is("HANDLER")) {
            String action = next().upper();
            next(); // HANDLER
            accept("FOR");
            int conditionStart = pos;
            while (true) {
                if (at("NOT") && peek(1).is("FOUND")) {
                    next();
                    next();
                } else if (at("SQLSTATE")) {
                    next();
                    accept("VALUE");
                    if (peek().type() == TokenType.STRING) {
                        next();
                    }
                } else if (atAny("SQLEXCEPTION", "SQLWARNING")) {
                    next();
                } else if (peek().isIdentifier() && (peek(1).isSymbol(",") || !startsStatement(peek()))) {
                    next();
                } else {
                    break;
                }
                if (!acceptSymbol(",")) {
                    break;
                }
            }
            String conditions = text(conditionStart, pos);
            Routine.Stmt body = atSymbol(";") || atEnd() ? null : statementSingle();
            acceptSymbol(";");
            addDeclaration(new Routine.Handler(action, conditions, body, span(start, pos)));
            return null;
        }
        if (peek().isIdentifier() && (peek(1).is("CURSOR")
                || (peek(1).isAny("ASENSITIVE", "INSENSITIVE", "SENSITIVE", "SCROLL", "NO") && (peek(2).is("CURSOR") || peek(3).is("CURSOR"))))) {
            String name = norm(next());
            while (!atEnd() && !atSymbol(";") && !at("FOR")) {
                next();
            }
            Routine.Sql query = null;
            String statementName = null;
            if (accept("FOR")) {
                if (queryStartsAt(pos)) {
                    int end = sqlEnd(pos);
                    query = sql(pos, end);
                    pos = end;
                } else if (peek().isIdentifier()) {
                    statementName = norm(next());
                }
            }
            endStatement();
            addDeclaration(new Routine.Cursor(name, List.of(), query, statementName, span(start, pos)));
            return null;
        }
        if (at("GLOBAL") && peek(1).is("TEMPORARY")) {
            int end = semicolon(pos);
            Routine.Expr detail = expr(pos, end);
            pos = end;
            acceptSymbol(";");
            return new Routine.Simple("DECLARE GLOBAL TEMPORARY TABLE", detail, span(start, pos));
        }
        if (peek().isIdentifier() && peek(1).isAny("CONDITION", "STATEMENT")) {
            String name = norm(next());
            String kind = next().upper();
            endStatement();
            addDeclaration(new Routine.Variable(List.of(name), kind, null, null, span(start, pos)));
            return null;
        }
        if (at("BEGIN") || atAny("CURSOR", "TYPE", "SUBTYPE", "PRAGMA", "PROCEDURE", "FUNCTION") || !peek().isIdentifier()) {
            return bodyAfterDeclarations(label, declarations(), start);
        }
        if (!db2StyleDeclare()) {
            return bodyAfterDeclarations(label, declarations(), start);
        }
        // DB2: DECLARE a, b 타입 [DEFAULT 식];
        List<String> names = new ArrayList<>();
        names.add(norm(next()));
        while (atSymbol(",") && peek(1).isIdentifier()) {
            next();
            names.add(norm(next()));
        }
        int typeStart = pos;
        int parens = 0;
        while (!atEnd() && !atSymbol(";")) {
            if (atSymbol("(")) {
                parens++;
            } else if (atSymbol(")")) {
                parens--;
            } else if (parens <= 0 && atAny("DEFAULT", "CONSTANT")) {
                break;
            }
            next();
        }
        String type = text(typeStart, pos);
        boolean constant = at("CONSTANT");
        Routine.Expr init = acceptAny("DEFAULT", "CONSTANT") ? scanExpr() : null;
        endStatement();
        addDeclaration(new Routine.Variable(names, constant ? "CONSTANT" : "VARIABLE", type, init, span(start, pos)));
        return null;
    }

    private static boolean startsStatement(Token t) {
        return t.type() == TokenType.IDENT && (STATEMENT_KEYWORDS.contains(t.upper()) || t.isAny("BEGIN", "DECLARE"));
    }

    /**
     * {@code DECLARE 이름 ..}이 DB2의 선언문 하나인가(아니면 PL/SQL 블록의 선언부 시작).
     * 선언 다음에 무엇이 오는지로 가른다: 또 {@code DECLARE}나 문장이 오면 DB2, 선언이 이어지면 PL/SQL, {@code BEGIN}이면 문법 플래그를 따른다.
     */
    private boolean db2StyleDeclare() {
        if (peek(1).isSymbol(",")) {
            return true;
        }
        int semi = semicolon(pos);
        if (semi >= eofIndex()) {
            return sqlPl;
        }
        Token n = tokenAt(semi + 1);
        if (n.is("DECLARE")) {
            return true;
        }
        if (n.isAny("CURSOR", "TYPE", "SUBTYPE", "PRAGMA", "PROCEDURE", "FUNCTION")) {
            return false;
        }
        if (n.is("BEGIN") || n.isEof()) {
            return sqlPl;
        }
        if (n.type() == TokenType.IDENT && tokenAt(semi + 2).isSymbol(":")) {
            return true; // 레이블
        }
        if (n.type() == TokenType.IDENT && STATEMENT_KEYWORDS.contains(n.upper()) && !tokenAt(semi + 2).isSymbol(":=")) {
            return true;
        }
        return sqlPl;
    }
}
