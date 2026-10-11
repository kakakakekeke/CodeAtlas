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
 * 언어가 {@link RoutineLanguage#PLPGSQL}이면 PL/pgSQL로 읽는다(F-019-R25): {@code PERFORM}, {@code EXECUTE 문자열식}, {@code =} 대입,
 * 괄호 없는 {@code FOR r IN 쿼리 LOOP}, {@code RETURN QUERY|NEXT}, {@code INTO [STRICT]} 대상 등 PostgreSQL에만 있거나 다르게 읽는 문장은
 * 그 언어에서만 켠다(ADR-0012). PL/SQL·SQL PL로 읽을 때의 결과는 달라지지 않는다.
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

    /** 본문의 언어. 안의 SQL 구문은 이 언어의 읽기 방식({@link RoutineLanguage#syntax()})으로 읽는다. */
    private final RoutineLanguage language;
    /** SQL PL 문법으로 읽는가(블록 안의 {@code DECLARE}를 선언문으로 본다). PL/SQL과 PL/pgSQL은 선언부다. */
    private final boolean sqlPl;
    /** PL/pgSQL 문법으로 읽는가. PostgreSQL에만 있는 문장과 PL/SQL·SQL PL과 겹치는 표기는 이 값이 참일 때만 그렇게 읽는다(ADR-0012). */
    private final boolean pg;
    private final int sqlDepth;
    private final List<Routine.Decl> rootDeclarations = new ArrayList<>();
    private final Deque<List<Routine.Decl>> declarationSinks = new ArrayDeque<>();
    private final List<Routine.Stmt> pending = new ArrayList<>();
    private int depth;

    /**
     * @param tokens 본문의 토큰(주석 제외)
     * @param language 본문의 언어. {@link RoutineLanguage#SQLPL}이면 블록 안의 {@code DECLARE}를 선언문으로 본다. null이면 PL/SQL
     */
    public RoutineParser(List<Token> tokens, RoutineLanguage language) {
        this(tokens, language, 0);
    }

    RoutineParser(List<Token> tokens, RoutineLanguage language, int sqlDepth) {
        super(stripConditionalCompilation(tokens));
        this.language = language == null ? RoutineLanguage.PLSQL : language;
        this.sqlPl = this.language == RoutineLanguage.SQLPL;
        this.pg = this.language == RoutineLanguage.PLPGSQL;
        this.sqlDepth = sqlDepth;
    }

    /** 이 파서가 읽는 본문의 언어. */
    public RoutineLanguage language() {
        return language;
    }

    // ------------------------------------------------------------------ 진입점

    /**
     * 본문 하나를 파싱한다.
     *
     * @param declarationSection true면 선언부로 시작한다(PL/SQL 서브프로그램의 {@code IS/AS} 다음). false면 문장 하나
     *                           (SQL PL 본문, 트리거 본문, 익명 블록: {@code DECLARE}/{@code BEGIN}/단일 문장)
     */
    public Routine.Block parseBody(boolean declarationSection) {
        if (pg) {
            // PL/pgSQL 본문 처음의 컴파일러 지시문(#variable_conflict use_column, #option dump): 그 줄을 건너뛴다
            while (atSymbol("#") && peek(1).type() == TokenType.IDENT && peek(1).line() == peek().line()) {
                int line = peek().line();
                while (!atEnd() && peek().line() == line) {
                    next();
                }
            }
        }
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

    /**
     * {@code ;}로 나뉜 문장들을 블록 없이 읽는다: PostgreSQL {@code LANGUAGE sql} 루틴의 본문(F-019-R31).
     * 끝의 {@code ;}는 없어도 된다.
     */
    public Routine.Block parseStatementList() {
        int start = pos;
        List<Routine.Decl> declarations = new ArrayList<>();
        List<Routine.Stmt> list;
        declarationSinks.push(declarations);
        try {
            list = statements();
        } finally {
            declarationSinks.pop();
        }
        return withLeftovers(new Routine.Block(null, declarations, list, List.of(), false, span(start, pos)));
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
        return sql(from, toExclusive, false);
    }

    /**
     * 토큰 구간을 SQL 구문으로 파싱한다.
     *
     * @param perform PL/pgSQL {@code PERFORM 쿼리}면 true: 첫 토큰을 같은 위치의 {@code SELECT}로 바꿔 읽는다(구문의 토큰과 위치는 원문 그대로다)
     */
    private Routine.Sql sql(int from, int toExclusive, boolean perform) {
        int to = Math.max(from, Math.min(toExclusive, eofIndex()));
        List<Token> sub = new ArrayList<>(tokens.subList(from, to));
        Statement statement;
        List<QName> salvaged = List.of();
        List<Routine.Problem> found = new ArrayList<>();
        List<Token> parsed = sub;
        List<String> into;
        if (pg) {
            // PL/pgSQL의 INTO [STRICT] 대상은 구문의 어디에나 올 수 있다: 떼어 내고 나머지를 구문으로 읽는다(F-019-R26)
            List<String> names = new ArrayList<>();
            parsed = withoutIntoClause(sub, names);
            into = names;
            if (perform && !parsed.isEmpty()) {
                parsed = new ArrayList<>(parsed);
                parsed.set(0, parsed.get(0).renamed("SELECT"));
            }
        } else {
            into = intoTargets(sub);
        }
        if (sub.isEmpty() || sqlDepth >= SqlParser.MAX_DEPTH) {
            statement = new Statement.Empty();
        } else {
            SqlParser parser = new SqlParser(parsed, language.syntax());
            parser.depth = sqlDepth;
            ParseResult result = parser.parseStatement();
            statement = result.statement();
            salvaged = result.salvagedReads();
            for (ParseProblem p : result.problems()) {
                found.add(new Routine.Problem(p.message(), p.pos(), p.minor()));
            }
        }
        return new Routine.Sql(statement, salvaged, found, sub, into, span(from, to));
    }

    /**
     * PL/pgSQL 본문 SQL의 {@code INTO [STRICT] 대상, ..}을 뗀 토큰 목록을 돌려주고 대상 이름(첫 조각, 정규화)을 names에 더한다.
     * 괄호 밖의 첫 {@code INTO}만 보고 {@code INSERT INTO}·{@code MERGE INTO}의 것은 그대로 둔다. 대상은 {@code 이름[.필드][[첨자]]}다.
     */
    private static List<Token> withoutIntoClause(List<Token> sub, List<String> names) {
        int parens = 0;
        int n = sub.size();
        for (int i = 1; i < n; i++) {
            Token t = sub.get(i);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.is("INTO") && !sub.get(i - 1).isAny("INSERT", "MERGE")) {
                int j = i + 1;
                if (j + 1 < n && sub.get(j).is("STRICT") && (sub.get(j + 1).isIdentifier() || sub.get(j + 1).type() == TokenType.BIND)
                        && !sub.get(j + 1).is("FROM")) {
                    j++;
                }
                List<String> found = new ArrayList<>();
                while (j < n && (sub.get(j).isIdentifier() || sub.get(j).type() == TokenType.BIND)) {
                    found.add(norm(sub.get(j)));
                    j++;
                    while (j + 1 < n && sub.get(j).isSymbol(".") && sub.get(j + 1).isIdentifier()) {
                        j += 2;
                    }
                    while (j < n && sub.get(j).isSymbol("[")) {
                        int d = 0;
                        while (j < n) {
                            if (sub.get(j).isSymbol("[")) {
                                d++;
                            } else if (sub.get(j).isSymbol("]") && --d == 0) {
                                j++;
                                break;
                            }
                            j++;
                        }
                    }
                    if (j + 1 < n && sub.get(j).isSymbol(",")) {
                        j++;
                    } else {
                        break;
                    }
                }
                if (found.isEmpty()) {
                    return sub;
                }
                names.addAll(found);
                List<Token> out = new ArrayList<>(sub.subList(0, i));
                out.addAll(sub.subList(Math.min(j, n), n));
                return out;
            }
        }
        return sub;
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
            if (acceptSymbol(";") || (pg && accept("DECLARE"))) {
                continue; // PL/pgSQL은 선언부 안의 여분의 DECLARE를 허용한다
            }
            Token t = peek();
            if (t.type() == TokenType.IDENT && STATEMENT_KEYWORDS.contains(t.upper())
                    && !(pg && PG_VARIABLE_KEYWORDS.contains(t.upper()) && peek(1).isIdentifier())) {
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
        if (pg) {
            if (t.isIdentifier()) {
                return pgDeclaration(start);
            }
            problem("선언을 해석하지 못함: " + t.text(), t, false);
            skipToSemicolon();
            return null;
        }
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

    /**
     * PL/pgSQL 선언 하나: {@code 이름 [CONSTANT] 타입 [COLLATE ..] [NOT NULL] [{DEFAULT | := | =} 식];}, {@code 이름 ALIAS FOR $1;},
     * {@code 이름 [[NO] SCROLL] CURSOR [(인자, ..)] {FOR | IS} 쿼리;}. PL/SQL의 {@code TYPE}·{@code PRAGMA}·지역 서브프로그램은 없다
     * ({@code type}은 변수 이름일 수 있다).
     */
    private Routine.Decl pgDeclaration(int start) {
        String name = norm(next());
        int k = pos;
        if (tokenAt(k).is("NO") && tokenAt(k + 1).is("SCROLL")) {
            k += 2;
        } else if (tokenAt(k).is("SCROLL")) {
            k++;
        }
        if (tokenAt(k).is("CURSOR")) {
            pos = Math.min(k + 1, eofIndex());
            List<Routine.Param> params = atSymbol("(") ? parameters() : List.of();
            Routine.Sql query = null;
            if (acceptAny("FOR", "IS")) {
                int end = sqlEnd(pos);
                query = sql(pos, end);
                pos = end;
            } else {
                skipToSemicolon();
            }
            acceptSymbol(";");
            return new Routine.Cursor(name, params, query, null, span(start, pos));
        }
        boolean constant = accept("CONSTANT");
        int typeStart = pos;
        int typeEnd = -1;
        int parens = 0;
        while (!atEnd() && !atSymbol(";")) {
            if (atSymbol("(")) {
                parens++;
            } else if (atSymbol(")")) {
                parens--;
            } else if (parens <= 0 && (atSymbol(":=") || atSymbol("=") || at("DEFAULT"))) {
                break;
            } else if (parens <= 0 && typeEnd < 0 && pos > typeStart && ((at("NOT") && peek(1).is("NULL")) || at("COLLATE"))) {
                typeEnd = pos;
            }
            next();
        }
        String type = text(typeStart, typeEnd < 0 ? pos : typeEnd);
        Routine.Expr init = null;
        if (atSymbol(":=") || atSymbol("=") || at("DEFAULT")) {
            next();
            init = scanExpr();
        }
        acceptSymbol(";");
        return new Routine.Variable(List.of(name), constant ? "CONSTANT" : "VARIABLE", type, init, span(start, pos));
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
        if (pg) {
            if (n.isSymbol("=") || n.isSymbol("[")) {
                return assignOrCall(start); // PL/pgSQL: v = 식, arr[1] := 식
            }
            Routine.Stmt s = pgStatement(label, start, t);
            if (s != null) {
                return s;
            }
        } else if (n.isSymbol("(") && CALLABLE_KEYWORDS.contains(t.upper())) {
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
        if (pg && at("NO") && peek(1).is("SCROLL")) {
            next();
            next();
        } else if (pg) {
            accept("SCROLL");
        }
        if (accept("FOR")) {
            if (pg && at("EXECUTE")) {
                next(); // OPEN c FOR EXECUTE 문자열식 [USING ..]
                dynamic = scanExpr("USING");
            } else if (queryStartsAt(pos)) {
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
        if (pg && args == null) {
            // PL/pgSQL 대입 대상의 첨자와 필드: arr[1], rec.items[2].qty
            while (atSymbol("[")) {
                int close = matchingBracket(pos, semicolon(pos));
                if (args == null) {
                    args = expr(pos + 1, close);
                }
                pos = Math.min(close + 1, eofIndex());
                while (atSymbol(".") && peek(1).isIdentifier()) {
                    next();
                    next();
                }
            }
        }
        if ((atSymbol(":=") || (pg && atSymbol("="))) && !callKeyword) {
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

    // ------------------------------------------------------------------ PL/pgSQL 문장 (F-019-R25)

    /** 문장 키워드지만 PL/pgSQL 선언부에서 타입이 뒤따르면 변수 이름으로 보는 것({@code values text[];}, {@code lock boolean;}). */
    private static final Set<String> PG_VARIABLE_KEYWORDS = Set.of(
            "VALUES", "OPEN", "CLOSE", "FETCH", "GET", "SET", "LOCK", "CALL", "PREPARE", "PIPE", "LEAVE", "ITERATE", "SIGNAL", "RESIGNAL",
            "REPEAT", "SAVEPOINT", "TRUNCATE", "MERGE");

    /** PL/pgSQL 본문에서 뽑을 것이 없는 SQL 명령의 첫 키워드({@code SET}은 대입이 아니라 설정이다). */
    private static final Set<String> PG_PASS_THROUGH = Set.of(
            "SET", "RESET", "NOTIFY", "LISTEN", "UNLISTEN", "DISCARD", "VACUUM", "REINDEX", "CLUSTER", "COPY", "SHOW", "CHECKPOINT", "SECURITY");

    /** open 위치의 여는 대괄호와 짝인 닫는 대괄호의 인덱스. limit(제외) 안에 없으면 limit. */
    private int matchingBracket(int open, int limit) {
        int d = 0;
        for (int i = open; i < limit; i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("[")) {
                d++;
            } else if (t.isSymbol("]")) {
                d--;
                if (d == 0) {
                    return i;
                }
            }
        }
        return limit;
    }

    /**
     * PL/pgSQL에만 있거나 PL/SQL·SQL PL과 다르게 읽는 문장. 여기서 다루지 않는 문장이면 null(공통 규칙으로 읽는다).
     * 현재 위치는 문장의 첫 토큰 t다.
     */
    private Routine.Stmt pgStatement(String label, int start, Token t) {
        switch (t.upper()) {
            case "PERFORM":
                return pgPerform(start);
            case "EXECUTE": {
                next();
                Routine.Expr sql = scanExpr("INTO", "USING");
                Routine.Expr rest = atSymbol(";") || atEnd() ? null : scanExpr();
                endStatement();
                return new Routine.ExecuteImmediate(sql, rest, span(start, pos));
            }
            case "FOR":
                return pgFor(label, start);
            case "FOREACH": {
                next();
                String variable = peek().isIdentifier() ? norm(next()) : null;
                while (!atEnd() && !atSymbol(";") && !at("IN") && !at("LOOP")) {
                    next(); // 대상이 여럿이거나 SLICE n
                }
                accept("IN");
                accept("ARRAY");
                Routine.Expr array = scanExpr("LOOP");
                if (!accept("LOOP")) {
                    problem("FOREACH에 LOOP가 없음", peek(), false);
                }
                List<Routine.Stmt> body = statements();
                endOf("LOOP");
                return new Routine.Loop(Routine.LoopKind.FOR_RANGE, label, variable, array, null, null, body, span(start, pos));
            }
            case "RETURN": {
                if (peek(1).is("QUERY") && (peek(2).type() == TokenType.IDENT || peek(2).isSymbol("("))) {
                    next();
                    next();
                    if (accept("EXECUTE")) {
                        Routine.Expr sql = scanExpr("USING");
                        Routine.Expr rest = atSymbol(";") || atEnd() ? null : scanExpr();
                        endStatement();
                        return new Routine.ExecuteImmediate(sql, rest, span(start, pos));
                    }
                    int end = sqlEnd(pos);
                    Routine.Sql query = sql(pos, end);
                    pos = end;
                    acceptSymbol(";");
                    return new Routine.Return(null, query, span(start, pos));
                }
                if (peek(1).is("NEXT") && !peek(2).isSymbol(".") && !peek(2).isSymbol("(")) {
                    next();
                    next();
                    Routine.Expr value = atSymbol(";") || atEnd() ? null : scanExpr();
                    acceptSymbol(";");
                    return new Routine.Return(value, null, span(start, pos));
                }
                return null; // RETURN [식]: 공통 규칙
            }
            case "CREATE": {
                // CREATE [TEMP] TABLE t AS 쿼리: PL/pgSQL 본문에는 DDL을 그대로 쓴다. 쿼리가 읽는 테이블과 채우는 테이블을 구문으로 남긴다.
                // 그 밖의 CREATE는 공통 규칙대로 넘긴다
                int end = semicolon(pos);
                List<Token> sub = new ArrayList<>(tokens.subList(pos, end));
                if (sqlDepth < SqlParser.MAX_DEPTH && DdlParser.isDdl(sub)) {
                    ParseResult result = new DdlParser(sub, language.syntax()).parse();
                    if (result.statement() instanceof io.codeatlas.sql.ast.Ddl.CreateTable table && table.asQuery() != null) {
                        List<Routine.Problem> found = new ArrayList<>();
                        for (ParseProblem p : result.problems()) {
                            found.add(new Routine.Problem(p.message(), p.pos(), p.minor()));
                        }
                        pos = end;
                        acceptSymbol(";");
                        return new Routine.Sql(result.statement(), result.salvagedReads(), found, sub, List.of(), span(start, end));
                    }
                }
                return null;
            }
            case "ASSERT": {
                next();
                Routine.Expr detail = scanExpr();
                endStatement();
                return new Routine.Simple("ASSERT", detail, span(start, pos));
            }
            case "FETCH":
            case "MOVE": {
                // FETCH [방향 {FROM | IN}] 커서 INTO 대상, MOVE [방향 {FROM | IN}] 커서: 커서 이름은 INTO(없으면 끝) 바로 앞의 이름이다
                boolean fetch = next().is("FETCH");
                int end = semicolon(pos);
                int intoAt = -1;
                for (int i = pos; i < end; i++) {
                    if (tokens.get(i).is("INTO")) {
                        intoAt = i;
                        break;
                    }
                }
                int nameEnd = intoAt >= 0 ? intoAt : end;
                String cursor = nameEnd > pos && tokens.get(nameEnd - 1).isIdentifier() ? norm(tokens.get(nameEnd - 1)) : null;
                Routine.Expr into = null;
                if (intoAt >= 0) {
                    pos = intoAt + 1;
                    into = scanExpr();
                } else {
                    pos = end;
                }
                endStatement();
                return fetch ? new Routine.Fetch(cursor, into, span(start, pos)) : new Routine.Simple("MOVE", null, span(start, pos));
            }
            default:
                if (t.is("COPY") && peek(1).isSymbol("(")) {
                    return sqlStatement(); // COPY (쿼리) TO ..: 괄호 안의 쿼리를 읽는 구문이다(F-019-R38)
                }
                if (PG_PASS_THROUGH.contains(t.upper()) && (peek(1).type() == TokenType.IDENT || peek(1).isSymbol(";") || peek(1).isEof())) {
                    String keyword = next().upper();
                    int end = semicolon(pos);
                    Routine.Expr detail = expr(pos, end);
                    pos = end;
                    acceptSymbol(";");
                    return new Routine.Simple(keyword, detail, span(start, pos));
                }
                return null;
        }
    }

    /**
     * {@code PERFORM}: 함수 호출 하나뿐이면({@code PERFORM f(..);}) 호출문이고, 그 밖에는({@code PERFORM 1 FROM t WHERE ..}) 결과를 버리는
     * {@code SELECT} 구문이다(F-019-R26·R27).
     */
    private Routine.Stmt pgPerform(int start) {
        next(); // PERFORM
        if (peek().isIdentifier()) {
            int nameStart = pos;
            QName name = nameChain();
            if (atSymbol("(")) {
                int close = matchingParen(pos, semicolon(pos));
                if (close < eofIndex() && tokens.get(close).isSymbol(")") && (tokenAt(close + 1).isSymbol(";") || close + 1 >= eofIndex())) {
                    Routine.Expr args = expr(pos + 1, close);
                    pos = Math.min(close + 1, eofIndex());
                    int callEnd = pos;
                    acceptSymbol(";");
                    return new Routine.Call(name, args, false, tokens.subList(nameStart, callEnd), span(start, callEnd));
                }
            }
            pos = nameStart;
        }
        int end = sqlEnd(pos);
        Routine.Sql query = sql(start, end, true);
        pos = end;
        acceptSymbol(";");
        return query;
    }

    /**
     * PL/pgSQL의 {@code FOR}: {@code FOR i IN [REVERSE] a..b [BY n] LOOP}, {@code FOR r IN 쿼리 LOOP}(괄호 없이 쓴다),
     * {@code FOR r IN EXECUTE 문자열식 [USING ..] LOOP}, {@code FOR r IN 커서[(인자)] LOOP}.
     */
    private Routine.Stmt pgFor(String label, int start) {
        next(); // FOR
        String variable = peek().isIdentifier() && !at("IN") ? norm(next()) : null;
        while (!atEnd() && !atSymbol(";") && !at("IN") && !at("LOOP")) {
            next(); // 대상이 여럿(FOR a, b IN ..)이거나 필드(rec.f): 첫 이름만 루프 변수로 둔다
        }
        if (!accept("IN")) {
            problem("FOR에 IN이 없음", peek(), false);
        }
        if (at("EXECUTE")) {
            next();
            Routine.Expr dynamic = scanExpr("USING", "LOOP");
            Routine.Expr using = accept("USING") ? scanExpr("LOOP") : null;
            int headEnd = pos;
            if (!accept("LOOP")) {
                problem("FOR에 LOOP가 없음", peek(), false);
            }
            List<Routine.Stmt> body = statements();
            endOf("LOOP");
            // 동적 SQL 지점과 루프를 한 블록으로 묶는다: 루프 변수는 루프 안에서만 보인다
            Routine.Stmt execute = new Routine.ExecuteImmediate(dynamic, using, span(start, headEnd));
            Routine.Stmt loop = new Routine.Loop(Routine.LoopKind.FOR_CURSOR, label, variable, null, null, null, body, span(start, pos));
            return new Routine.Block(null, List.of(), List.of(execute, loop), List.of(), false, span(start, pos));
        }
        accept("REVERSE");
        // 머리의 끝: 괄호 밖의 LOOP
        int loopAt = pos;
        int parens = 0;
        boolean range = false;
        int n = eofIndex();
        while (loopAt < n) {
            Token t = tokens.get(loopAt);
            if (t.isSymbol(";")) {
                break;
            }
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens <= 0 && t.is("LOOP") && !prevIsDot(loopAt, pos)) {
                break;
            } else if (parens <= 0 && t.isSymbol("..")) {
                range = true;
            }
            loopAt++;
        }
        Routine.LoopKind kind;
        Routine.Expr control = null;
        Routine.Sql query = null;
        String cursor = null;
        if (range) {
            control = expr(pos, loopAt);
            kind = Routine.LoopKind.FOR_RANGE;
        } else if (queryStartsAt(pos)) {
            boolean wrapped = atSymbol("(") && matchingParen(pos, loopAt) == loopAt - 1;
            query = wrapped ? sql(pos + 1, loopAt - 1) : sql(pos, loopAt);
            kind = Routine.LoopKind.FOR_QUERY;
        } else {
            control = expr(pos, loopAt);
            kind = Routine.LoopKind.FOR_CURSOR;
            if (!control.refs().isEmpty()) {
                cursor = control.refs().get(0).name().display();
            }
        }
        pos = loopAt;
        if (!accept("LOOP")) {
            problem("FOR에 LOOP가 없음", peek(), false);
        }
        List<Routine.Stmt> body = statements();
        endOf("LOOP");
        return new Routine.Loop(kind, label, variable, control, cursor, query, body, span(start, pos));
    }

    // ------------------------------------------------------------------ DECLARE

    private Routine.Stmt declareStatement(String label, int start) {
        next(); // DECLARE
        if (pg) {
            return bodyAfterDeclarations(label, declarations(), start); // PL/pgSQL의 DECLARE는 언제나 블록의 선언부다
        }
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
