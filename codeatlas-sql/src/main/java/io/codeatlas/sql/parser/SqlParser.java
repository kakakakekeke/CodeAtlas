package io.codeatlas.sql.parser;

import io.codeatlas.sql.ast.Expr;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.ast.Query;
import io.codeatlas.sql.ast.Query.Cte;
import io.codeatlas.sql.ast.Query.QueryBody;
import io.codeatlas.sql.ast.Query.SelectItem;
import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.ast.TableRef;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle·DB2 DML을 받아들이는 오류 허용 재귀 하강 파서(ADR-0004). 두 방언의 합집합을 하나의 문법으로 받는다.
 *
 * <p>어떤 입력에도 예외를 던지지 않는다. 문법에 맞지 않는 곳은 {@link ParseProblem}을 남기고 다음 동기화 지점
 * (절 키워드, 닫는 괄호, 구문 끝)에서 이어 간다. 진행하지 못하는 반복은 토큰을 강제로 소비한다.
 *
 * <p>PostgreSQL 문법(F-019-R12~R16)은 방언끼리 겹치지 않는 것이면 읽기 방식과 무관하게 이 합집합 문법에 있다({@code ON CONFLICT},
 * {@code DELETE … USING}, 데이터 변경 CTE, {@code DISTINCT ON}, {@code ->>} 같은 연산자, {@code ARRAY[..]}, {@code nextval('seq')} 등).
 * 다른 방언과 겹치는 표기와 PostgreSQL에만 있는 문장만 {@link Syntax#POSTGRES}에서 켠다(ADR-0012): 수식어 {@code ONLY 이름}
 * ({@code ONLY}라는 테이블과 겹친다), 이름이 든 첨자 {@code a[i]}(MSSQL의 대괄호 이름과 겹친다), {@code COPY}, 트랜잭션 명령 {@code BEGIN}.
 *
 * <p>공개 진입점: {@link #parse(String)}(구문 하나), {@link #parseStatement()}, {@link #parseQuery()},
 * {@link #parseExpression()}, {@link #parseTableRefs()}, {@link #qualifiedName()}.
 */
public final class SqlParser extends TokenCursor {

    static final int MAX_DEPTH = 100;

    /** 중첩 깊이. 본문 파서({@link RoutineParser})가 안쪽 구문을 파싱할 때 이어받는다. */
    int depth;

    /** tokens는 구문 하나의 토큰(종결자 제외)이다. */
    /** MySQL {@code ON DUPLICATE KEY UPDATE} 항목을 읽는 중: {@code VALUES(컬럼)}을 함수로 받는다. */
    private boolean valuesFunction;

    /** 이 구문이 든 텍스트의 읽기 방식(ADR-0012). 안에서 만드는 하위 파서에도 그대로 넘긴다. */
    private final Syntax syntax;

    /** 기본 읽기 방식으로 읽는다. */
    public SqlParser(List<Token> tokens) {
        this(tokens, Syntax.DEFAULT);
    }

    /** @param syntax 읽기 방식. 토큰을 만든 렉서와 같은 값이어야 한다. null이면 기본이다 */
    public SqlParser(List<Token> tokens, Syntax syntax) {
        super(tokens);
        this.syntax = syntax == null ? Syntax.DEFAULT : syntax;
    }

    /** 이 파서의 읽기 방식. */
    public Syntax syntax() {
        return syntax;
    }

    private boolean postgres() {
        return syntax == Syntax.POSTGRES;
    }

    /** 구문 하나를 렉싱하고 파싱한다(기본 읽기 방식). */
    public static ParseResult parse(String sql) {
        return parse(sql, Syntax.DEFAULT);
    }

    /** 구문 하나를 주어진 읽기 방식으로 렉싱하고 파싱한다. */
    public static ParseResult parse(String sql, Syntax syntax) {
        return new SqlParser(Lexer.tokenize(sql, syntax), syntax).parseStatement();
    }

    // ------------------------------------------------------------------ 구문

    /**
     * 구문을 끝까지 파싱한다. 구문이 끝난 뒤 남은 토큰이 새 구문의 시작(SELECT/INSERT/UPDATE/DELETE/MERGE/WITH/CALL)이면 거기서 잘라
     * 따로 파싱해 {@link Statement.Sequence}로 묶는다(F-006-R16). 그 밖의 남은 토큰은 문제로 기록하고 FROM/JOIN 바로 뒤의 테이블 이름만 건진다.
     */
    public ParseResult parseStatement() {
        Token first = peek();
        for (Token t : tokens) {
            if (t.unterminated()) {
                problem(t.dollarQuoted() ? "닫히지 않은 달러 인용" : "닫히지 않은 문자열 또는 식별자", t, false);
                break;
            }
        }
        List<Statement> list = new ArrayList<>();
        Statement statement = statement();
        list.add(statement);
        List<QName> salvaged = new ArrayList<>();
        while (true) {
            if (acceptSymbol(";")) {
                continue; // 끝의 종결자
            }
            if (peek().isDynamic() && !(statement instanceof Statement.Unparsed)) {
                next(); // 구문 끝에 붙은 ${} 치환
                continue;
            }
            if (atEnd()) {
                break;
            }
            if (!(statement instanceof Statement.Unparsed) && atStatementStart()) {
                int before = pos;
                statement = statement();
                list.add(statement);
                if (pos == before) {
                    next(); // 안전장치
                }
                continue;
            }
            problem(unexpectedMessage(peek(), "해석하지 못한 토큰: " + peek().text()), peek(), false);
            if (!(statement instanceof Statement.Unparsed)) {
                salvage(salvaged);
            }
            pos = tokens.size() - 1;
            break;
        }
        Token last = tokens.size() >= 2 ? tokens.get(tokens.size() - 2) : first;
        Statement result = list.size() == 1 ? list.get(0) : new Statement.Sequence(list);
        return new ParseResult(result, problems, salvaged, Pos.span(first, last));
    }

    /** 현재 토큰이 새 구문을 여는 키워드인가(구문이 끝난 자리에서만 묻는다). */
    private boolean atStatementStart() {
        if (atAny("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE")) {
            return true;
        }
        if (at("CALL")) {
            return peek(1).isIdentifier();
        }
        return atCteStart() && (peek(2).is("AS") || peek(2).isSymbol("("));
    }

    private Statement statement() {
        while (acceptSymbol(";")) {
            // 빈 구문
        }
        if (atEnd()) {
            return new Statement.Empty();
        }
        Token t = peek();
        if (t.isSymbol("{")) {
            return jdbcCall();
        }
        if (t.isSymbol("(")) {
            return new Statement.Select(parseQuery());
        }
        if (t.type() != TokenType.IDENT) {
            return unparsed();
        }
        switch (t.upper()) {
            case "EXPLAIN": {
                int mark = pos;
                while (!atEnd() && !at("FOR")) {
                    next();
                }
                if (accept("FOR")) {
                    return statement();
                }
                pos = mark;
                return unparsed();
            }
            case "SELECT":
            case "VALUES":
                return new Statement.Select(parseQuery());
            case "WITH": {
                Token start = peek();
                List<Cte> ctes = parseCtes();
                if (atAny("INSERT", "UPDATE", "DELETE", "MERGE")) {
                    return new Statement.WithDml(ctes, statement());
                }
                return new Statement.Select(queryRest(ctes, start));
            }
            case "INSERT":
                return insert();
            case "REPLACE":
                if (peek(1).is("INTO") || peek(1).isAny("LOW_PRIORITY", "DELAYED")) {
                    return insert(); // MySQL REPLACE INTO: 쓰는 테이블로는 INSERT와 같다(F-006-R36)
                }
                return unparsed();
            case "UPDATE":
                return update();
            case "DELETE":
                return delete();
            case "MERGE":
                return merge();
            case "CALL":
                next();
                return callRest();
            case "EXEC":
            case "EXECUTE":
                return exec();
            case "TRUNCATE":
                return truncate();
            case "LOCK":
                return lockTable();
            case "BEGIN":
            case "DECLARE": {
                Statement pg = postgres() ? postgresBeginOrDeclare() : null;
                return pg != null ? pg : block();
            }
            case "COPY":
                return postgres() ? copy() : unparsed();
            case "IF":
                return ifExistsDrop();
            default:
                if (postgres() && t.is("REFRESH") && peek(1).is("MATERIALIZED")) {
                    // PostgreSQL REFRESH MATERIALIZED VIEW: 지원하지 않는 구문이다. DB2의 REFRESH TABLE처럼 조용히 넘기지 않고 드러낸다
                    return unparsed();
                }
                if (Keywords.SILENT_STATEMENTS.contains(t.upper())
                        || (postgres() && (t.isAny("END", "ABORT") || (t.is("START") && peek(1).is("TRANSACTION"))))) {
                    // PostgreSQL 읽기 방식에서는 END·ABORT·START TRANSACTION도 트랜잭션 명령이다
                    String keyword = t.upper();
                    pos = tokens.size() - 1;
                    return new Statement.Other(keyword, false);
                }
                return unparsed();
        }
    }

    /**
     * PostgreSQL 읽기 방식의 {@code BEGIN}·{@code DECLARE}: {@code BEGIN [TRANSACTION|WORK|ISOLATION ..]}은 트랜잭션 명령이라 조용히 넘기고,
     * {@code DECLARE 이름 .. CURSOR .. FOR 쿼리}는 그 쿼리를 읽는다. 어느 쪽도 아니면 null(익명 블록으로 읽는다).
     */
    private Statement postgresBeginOrDeclare() {
        if (at("BEGIN")) {
            Token n = peek(1);
            if (n.isEof() || n.isSymbol(";") || n.isAny("TRANSACTION", "WORK", "ISOLATION", "READ", "DEFERRABLE", "NOT")) {
                pos = tokens.size() - 1;
                return new Statement.Other("BEGIN", false);
            }
            return null;
        }
        if (!peek(1).isIdentifier()) {
            return null;
        }
        for (int i = 2; i <= 6; i++) {
            if (!peek(i).is("CURSOR")) {
                continue;
            }
            for (int k = i + 1; k <= i + 3; k++) {
                if (peek(k).is("FOR")) {
                    pos += k + 1;
                    return new Statement.Select(parseQuery());
                }
            }
            return null;
        }
        return null;
    }

    /**
     * PostgreSQL {@code COPY}(F-019-R23). {@code COPY 테이블 [(컬럼, ..)] FROM stdin ..}만 데이터 적재다. 파일에서 읽는 {@code COPY .. FROM '경로'},
     * {@code COPY .. TO ..}, {@code COPY (쿼리) TO ..}는 조용히 넘긴다.
     */
    private Statement copy() {
        next(); // COPY
        Statement.Copy copy = null;
        if (peek().isIdentifier() && !atTableNameStop()) {
            QName table = qualifiedName();
            List<QName> columns = atSymbol("(") ? columnNameList() : List.of();
            if (at("FROM") && peek(1).is("STDIN")) {
                copy = new Statement.Copy(table, columns);
            }
        }
        pos = tokens.size() - 1;
        return copy != null ? copy : new Statement.Other("COPY", false);
    }

    /**
     * MSSQL의 {@code IF [NOT] EXISTS (..) DROP ..}와 {@code IF EXISTS (..) ALTER TABLE .. DROP ..}는 관리 구문이다: 조용히 건너뛴다(F-006-R18).
     * 그 밖의 IF는 모르는 구문이다.
     */
    private Statement ifExistsDrop() {
        int mark = pos;
        next(); // IF
        accept("NOT");
        if (accept("EXISTS") && atSymbol("(")) {
            skipBalanced();
            boolean drop = at("DROP");
            if (at("ALTER")) {
                for (int i = pos; i < tokens.size() && !drop; i++) {
                    drop = tokens.get(i).is("DROP");
                }
            }
            if (drop) {
                pos = tokens.size() - 1;
                return new Statement.Other("IF", false);
            }
        }
        pos = mark;
        return unparsed();
    }

    private Statement unparsed() {
        String keyword = peek().type() == TokenType.IDENT ? peek().upper() : peek().text();
        pos = tokens.size() - 1;
        return new Statement.Unparsed(keyword);
    }

    // ------------------------------------------------------------------ 쿼리

    /** {@code [WITH ..] 본문 [ORDER BY ..] [페이징·잠금 절]}을 파싱한다. */
    public Query parseQuery() {
        Token start = peek();
        if (++depth > MAX_DEPTH) {
            depth--;
            problem("중첩이 너무 깊어 건너뜀", peek(), false);
            skipToCloseParen();
            return emptyQuery(start);
        }
        try {
            List<Cte> ctes = atCteStart() ? parseCtes() : List.of();
            return queryRest(ctes, start);
        } finally {
            depth--;
        }
    }

    private Query emptyQuery(Token at) {
        return new Query(List.of(), emptySelect(at), List.of(), List.of(), false, List.of(), Pos.of(at));
    }

    private static Query.Select emptySelect(Token at) {
        return new Query.Select(false, List.of(), List.of(), List.of(), null, null, null, List.of(), null, Pos.of(at));
    }

    private boolean atCteStart() {
        return at("WITH") && peek(1).isIdentifier() && !peek(1).isAny("UR", "CS", "RS", "RR", "READ", "CHECK", "CASCADED", "LOCAL");
    }

    private List<Cte> parseCtes() {
        next(); // WITH
        if (at("RECURSIVE") && peek(1).isIdentifier() && !peek(1).is("AS")) {
            next();
        }
        List<Cte> list = new ArrayList<>();
        while (peek().isIdentifier() && !atAny("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "VALUES")) {
            Token nameToken = next();
            List<String> columns = atSymbol("(") ? identList() : List.of();
            if (!accept("AS")) {
                problem("CTE에 AS가 없음", peek(), false);
            }
            if (at("NOT") && peek(1).is("MATERIALIZED")) {
                next();
            }
            accept("MATERIALIZED");
            Query query;
            Statement dml = null;
            if (atSymbol("(") && peek(1).isAny("INSERT", "UPDATE", "DELETE", "MERGE") && !peek(2).isSymbol("(")) {
                // PostgreSQL의 데이터 변경 CTE: name AS (INSERT|UPDATE|DELETE … [RETURNING ..]) (F-019-R14)
                query = emptyQuery(peek());
                open();
                if (++depth > MAX_DEPTH) {
                    problem("중첩이 너무 깊어 건너뜀", peek(), false);
                    skipToCloseParen();
                } else {
                    dml = statement();
                }
                depth--;
                close();
            } else if (atSymbol("(")) {
                open();
                query = parseQuery();
                close();
            } else {
                problem("CTE 본문이 없음", peek(), false);
                query = emptyQuery(peek());
            }
            // SEARCH, CYCLE 절은 건너뛴다
            while (atAny("SEARCH", "CYCLE")) {
                while (!atEnd() && !atSymbol(",") && !atAny("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE") && !atSymbol(")")) {
                    if (atSymbol("(")) {
                        skipBalanced();
                    } else {
                        next();
                    }
                }
            }
            list.add(new Cte(nameToken.text(), columns, query, Pos.of(nameToken), dml));
            if (!acceptSymbol(",")) {
                break;
            }
        }
        return list;
    }

    private Query queryRest(List<Cte> ctes, Token start) {
        QueryBody body = setOperations(queryTerm());
        return queryTail(ctes, body, start);
    }

    private Query queryTail(List<Cte> ctes, QueryBody body, Token start) {
        List<Expr> orderBy = new ArrayList<>();
        List<Expr> tail = new ArrayList<>();
        List<QName> forUpdateOf = new ArrayList<>();
        boolean forUpdate = false;
        while (!atEnd()) {
            int before = pos;
            if (at("ORDER") && (peek(1).is("BY") || (peek(1).is("SIBLINGS") && peek(2).is("BY")))) {
                next();
                accept("SIBLINGS");
                next();
                orderItems(orderBy);
            } else if (at("ORDER") && peek(1).isEof()) {
                next();
                problem("ORDER 뒤가 잘림", peek(), true);
            } else if (at("OFFSET")) {
                next();
                if (!atAny("ROW", "ROWS")) {
                    tail.add(parseAdditive());
                }
                acceptAny("ROW", "ROWS");
            } else if (at("FETCH") && peek(1).isAny("FIRST", "NEXT")) {
                next();
                next();
                if (!atAny("ROW", "ROWS", "PERCENT")) {
                    tail.add(parseAdditive());
                }
                accept("PERCENT");
                acceptAny("ROW", "ROWS");
                if (!accept("ONLY")) {
                    acceptSeq("WITH", "TIES");
                }
            } else if (at("LIMIT")) {
                next();
                if (at("ALL") && !peek(1).isSymbol(".") && !peek(1).isSymbol("(")) {
                    next(); // PostgreSQL LIMIT ALL: 제한 없음
                } else {
                    tail.add(parseAdditive());
                }
                if (acceptSymbol(",") || accept("OFFSET")) {
                    tail.add(parseAdditive());
                }
            } else if (atWithData()) {
                // CREATE TABLE … AS 쿼리 WITH [NO] DATA
                next();
                accept("NO");
                next();
            } else if (at("FOR") && lockClauseLength() > 0) {
                // FOR UPDATE, PostgreSQL의 FOR NO KEY UPDATE | SHARE | KEY SHARE
                pos += lockClauseLength();
                forUpdate = true;
                if (accept("OF")) {
                    do {
                        if (!peek().isIdentifier()) {
                            break;
                        }
                        forUpdateOf.add(qualifiedName());
                    } while (acceptSymbol(","));
                }
                if (accept("WAIT")) {
                    if (peek().type() == TokenType.NUMBER || peek().type() == TokenType.BIND) {
                        next();
                    }
                } else if (!accept("NOWAIT")) {
                    acceptSeq("SKIP", "LOCKED");
                }
            } else if (at("FOR") && peek(1).isAny("READ", "FETCH") && peek(2).is("ONLY")) {
                next();
                next();
                next();
            } else if (at("WITH") && peek(1).isAny("UR", "CS", "RS", "RR")) {
                next();
                next();
            } else if (at("USE") && peek(1).is("AND") && peek(2).is("KEEP")) {
                // DB2 잠금 요청 절: [WITH RS|RR | FOR READ ONLY] USE AND KEEP SHARE|UPDATE|EXCLUSIVE LOCKS
                next();
                next();
                next();
                acceptAny("SHARE", "UPDATE", "EXCLUSIVE");
                accept("LOCKS");
            } else if (at("SKIP") && peek(1).is("LOCKED") && peek(2).is("DATA")) {
                next();
                next();
                next();
            } else if (at("WAIT") && peek(1).is("FOR") && peek(2).is("OUTCOME")) {
                next();
                next();
                next();
            } else if (at("USE") && peek(1).is("CURRENTLY") && peek(2).is("COMMITTED")) {
                next();
                next();
                next();
            } else if (at("WITH") && peek(1).is("READ") && peek(2).is("ONLY")) {
                next();
                next();
                next();
            } else if (at("WITH") && (peek(1).is("CHECK") || (peek(1).isAny("CASCADED", "LOCAL") && peek(2).is("CHECK")))) {
                next();
                acceptAny("CASCADED", "LOCAL");
                next();
                accept("OPTION");
                if (accept("CONSTRAINT") && peek().isIdentifier()) {
                    next();
                }
            } else if (at("OPTIMIZE") && peek(1).is("FOR")) {
                next();
                next();
                if (peek().type() == TokenType.NUMBER || peek().type() == TokenType.BIND) {
                    next();
                }
                acceptAny("ROW", "ROWS");
            } else if (at("QUERYNO")) {
                next();
                if (peek().type() == TokenType.NUMBER) {
                    next();
                }
            } else {
                break;
            }
            if (pos == before) {
                next();
            }
        }
        return new Query(ctes, body, orderBy, tail, forUpdate, forUpdateOf, Pos.span(start, previous()));
    }

    /** 현재 토큰 {@code FOR}에서 시작하는 행 잠금 절의 머리 길이(토큰 수): {@code FOR UPDATE|SHARE}는 2, {@code FOR KEY SHARE}는 3, {@code FOR NO KEY UPDATE}는 4. 아니면 0. */
    private int lockClauseLength() {
        if (peek(1).isAny("UPDATE", "SHARE")) {
            return 2;
        }
        if (peek(1).is("KEY") && peek(2).is("SHARE")) {
            return 3;
        }
        return peek(1).is("NO") && peek(2).is("KEY") && peek(3).is("UPDATE") ? 4 : 0;
    }

    /** 현재 위치가 {@code WITH [NO] DATA}인가({@code DATA}라는 CTE의 {@code WITH data AS (..)}는 아니다). */
    private boolean atWithData() {
        if (!at("WITH")) {
            return false;
        }
        if (peek(1).is("NO")) {
            return peek(2).is("DATA");
        }
        return peek(1).is("DATA") && !peek(2).is("AS") && !peek(2).isSymbol("(");
    }

    /**
     * 현재 위치가 PostgreSQL {@code ON CONFLICT} 절인가(F-019-R12): {@code ON CONFLICT (}, {@code ON CONFLICT ON CONSTRAINT},
     * {@code ON CONFLICT DO}. {@code CONFLICT}라는 컬럼으로 시작하는 조인 조건({@code ON conflict = 1})은 아니다.
     */
    private boolean atOnConflict() {
        return at("ON") && peek(1).is("CONFLICT")
                && (peek(2).isSymbol("(") || peek(2).is("DO") || (peek(2).is("ON") && peek(3).is("CONSTRAINT")));
    }

    private void orderItems(List<Expr> into) {
        do {
            into.add(parseExpression());
            acceptAny("ASC", "DESC");
            if (accept("NULLS")) {
                acceptAny("FIRST", "LAST");
            }
            while (peek().isDynamic()) {
                next(); // ${dir} 같은 동적 정렬 방향
            }
        } while (acceptSymbol(","));
    }

    private boolean atSetOperator() {
        return atAny("UNION", "MINUS", "INTERSECT", "EXCEPT");
    }

    private QueryBody setOperations(QueryBody left) {
        while (atSetOperator()) {
            String op = next().upper();
            if (accept("ALL")) {
                op = op + " ALL";
            } else {
                accept("DISTINCT");
            }
            QueryBody right = queryTerm();
            left = new Query.SetOperation(op, left, right);
        }
        return left;
    }

    private QueryBody queryTerm() {
        if (atSymbol("(")) {
            open();
            Query q = parseQuery();
            close();
            return new Query.Nested(q);
        }
        if (at("VALUES")) {
            return values();
        }
        if (at("SELECT")) {
            return selectCore();
        }
        problem("SELECT가 있어야 할 자리: " + describe(peek()), peek(), false);
        return emptySelect(peek());
    }

    private static String describe(Token t) {
        return t.isEof() ? "<끝>" : t.text();
    }

    private QueryBody values() {
        next(); // VALUES
        List<List<Expr>> rows = new ArrayList<>();
        do {
            if (atSymbol("(") && !peek(1).isAny("SELECT", "WITH")) {
                open();
                rows.add(exprList());
                close();
            } else {
                rows.add(List.of(parseExpression()));
            }
        } while (acceptSymbol(","));
        if (accept("INTO")) {
            do {
                parseExpression();
            } while (acceptSymbol(","));
        }
        return new Query.Values(rows);
    }

    private QueryBody selectCore() {
        Token start = next(); // SELECT
        boolean distinct = false;
        List<Expr> distinctOn = List.of();
        if (atAny("DISTINCT", "UNIQUE")) {
            next();
            distinct = true;
            if (at("ON") && peek(1).isSymbol("(")) {
                // PostgreSQL SELECT DISTINCT ON (식, ..): 식은 읽은 것으로 남긴다(F-019-R15)
                next();
                open();
                distinctOn = exprList();
                close();
            }
        } else if (at("ALL") && !peek(1).isSymbol(".") && !peek(1).isSymbol(",")) {
            next();
        }
        List<SelectItem> items = selectList();
        List<Expr> into = new ArrayList<>();
        if (at("BULK") && peek(1).is("COLLECT")) {
            next();
            next();
        }
        if (accept("INTO")) {
            do {
                if (at("FROM") || atEnd()) {
                    break;
                }
                into.add(parseAdditive());
            } while (acceptSymbol(","));
        }
        List<TableRef> from = new ArrayList<>();
        if (accept("FROM")) {
            from.addAll(parseTableRefs());
        }
        Expr where = null;
        Expr startWith = null;
        Expr connectBy = null;
        Expr having = null;
        List<Expr> groupBy = new ArrayList<>();
        boolean garbageReported = false;
        while (!atEnd()) {
            int before = pos;
            if (at("WHERE")) {
                next();
                if (at("CURRENT") && peek(1).is("OF")) {
                    next();
                    next();
                    if (peek().isIdentifier()) {
                        next();
                    }
                } else {
                    where = and(where, parseExpression());
                }
            } else if (at("START") && peek(1).is("WITH")) {
                next();
                next();
                startWith = and(startWith, parseExpression());
            } else if (at("CONNECT") && peek(1).is("BY")) {
                next();
                next();
                accept("NOCYCLE");
                connectBy = and(connectBy, parseExpression());
            } else if (at("GROUP") && peek(1).is("BY")) {
                next();
                next();
                do {
                    if (at("GROUPING") && peek(1).is("SETS")) {
                        next();
                        next();
                    }
                    groupBy.add(parseExpression());
                } while (acceptSymbol(","));
            } else if (at("HAVING")) {
                next();
                having = and(having, parseExpression());
            } else if (at("QUALIFY")) {
                next();
                having = and(having, parseExpression());
            } else if (at("WINDOW") && peek(1).isIdentifier() && peek(2).is("AS")) {
                next();
                do {
                    next();
                    accept("AS");
                    skipBalanced();
                } while (acceptSymbol(",") && peek().isIdentifier());
            } else if (at("FROM")) {
                next();
                from.addAll(parseTableRefs());
            } else if (peek().isDynamic()) {
                next(); // FROM 목록이나 절 뒤에 홀로 있는 ${} 조각(조인 절·조건이 통째로 치환되는 자리): 건너뛴다(F-006-R25)
            } else if (openParens == 0 && atSymbol(")")) {
                problem("짝이 없는 닫는 괄호", peek(), false);
                next();
            } else if ((at("AND") || at("OR")) && openParens == 0) {
                // 조건이 끊겼다 이어지는 경우: WHERE의 연속으로 본다
                problem("절 밖에 놓인 조건", peek(), false);
                next();
                where = and(where, parseExpression());
            } else if (openParens == 0 && !atSetOperator() && !atQueryTail() && !atSymbol(";") && !atOnConflict()) {
                // 최상위 SELECT 뒤의 해석하지 못한 토큰: 다음 절이나 조인까지 건너뛴다
                if (atStatementStart()) {
                    break; // 다음 구문이 이어진다: parseStatement가 잘라 따로 파싱한다(F-006-R16)
                }
                if (atJoinStart() && !from.isEmpty()) {
                    TableRef left = from.remove(from.size() - 1);
                    from.add(joinChain(left));
                } else if (atSymbol(",") && peek(1).isEof()) {
                    problem("구문 끝에 남은 쉼표", peek(), true);
                    next();
                } else {
                    // 쉼표를 만나도 FROM 목록으로 잇지 않는다: 이어진 다른 문장의 선택 목록일 수 있다(컬럼이 테이블로 둔갑한다)
                    if (!garbageReported) {
                        problem(unexpectedMessage(peek(), "해석하지 못한 토큰: " + peek().text()), peek(), false);
                        garbageReported = true;
                    }
                    next();
                }
            } else {
                break;
            }
            if (pos == before) {
                next();
            }
        }
        return new Query.Select(distinct, items, into, from, where, startWith, connectBy, groupBy, having, Pos.of(start), distinctOn);
    }

    private boolean atQueryTail() {
        if (at("ORDER") || at("OFFSET") || at("LIMIT") || at("OPTIMIZE") || at("QUERYNO") || at("RETURNING") || at("LOG")) {
            return true;
        }
        if (at("FETCH") && peek(1).isAny("FIRST", "NEXT")) {
            return true;
        }
        if (at("FOR") && (peek(1).isAny("READ", "FETCH") || lockClauseLength() > 0)) {
            return true;
        }
        if (atWithData()) {
            return true;
        }
        if ((at("SKIP") && peek(1).is("LOCKED") && peek(2).is("DATA")) || (at("WAIT") && peek(1).is("FOR") && peek(2).is("OUTCOME"))
                || (at("USE") && ((peek(1).is("AND") && peek(2).is("KEEP")) || (peek(1).is("CURRENTLY") && peek(2).is("COMMITTED"))))) {
            return true; // DB2: SKIP LOCKED DATA, WAIT FOR OUTCOME, USE AND KEEP .. LOCKS, USE CURRENTLY COMMITTED
        }
        return at("WITH") && peek(1).isAny("UR", "CS", "RS", "RR", "READ", "CHECK", "CASCADED", "LOCAL");
    }

    private static Expr and(Expr left, Expr right) {
        return left == null ? right : new Expr.Binary("AND", left, right);
    }

    private List<SelectItem> selectList() {
        List<SelectItem> items = new ArrayList<>();
        while (true) {
            if (atSelectListEnd()) {
                if (items.isEmpty()) {
                    problem("선택 목록이 비어 있음", peek(), false);
                } else {
                    problem("쉼표 뒤에 선택 항목이 없음", peek(), true);
                }
                break;
            }
            int before = pos;
            Expr e;
            if (atSymbol("*")) {
                next();
                e = new Expr.Star(null);
            } else {
                e = parseExpression();
            }
            String alias = null;
            if (accept("AS")) {
                if (peek().isIdentifier() || peek().type() == TokenType.STRING) {
                    alias = next().text();
                }
            } else if (!atAliasStop()) {
                alias = next().text();
            }
            items.add(new SelectItem(e, alias));
            if (!acceptSymbol(",")) {
                break;
            }
            if (pos == before) {
                next();
            }
        }
        return items;
    }

    private boolean atSelectListEnd() {
        Token t = peek();
        return t.isEof() || t.isSymbol(")") || t.isSymbol(";")
                || t.isAny("FROM", "INTO", "WHERE", "GROUP", "HAVING", "ORDER", "UNION", "MINUS", "INTERSECT", "EXCEPT")
                || (t.is("BULK") && peek(1).is("COLLECT"));
    }

    // ------------------------------------------------------------------ 테이블 참조

    /** FROM 뒤의 테이블 참조 목록(쉼표 구분, 조인 포함). */
    public List<TableRef> parseTableRefs() {
        List<TableRef> list = new ArrayList<>();
        while (true) {
            if (atTableListEnd()) {
                problem("테이블 이름이 있어야 할 자리: " + describe(peek()), peek(), list.isEmpty() ? false : true);
                break;
            }
            int before = pos;
            list.add(joinChain(tablePrimary()));
            if (!acceptSymbol(",")) {
                break;
            }
            if (pos == before) {
                next();
            }
        }
        return list;
    }

    private boolean atTableListEnd() {
        Token t = peek();
        if (t.isEof() || t.isSymbol(")") || t.isSymbol(";") || t.isSymbol(",")) {
            return true;
        }
        return t.type() == TokenType.IDENT && !peek(1).isSymbol(".") && !peek(1).isSymbol("(")
                && t.isAny("WHERE", "GROUP", "HAVING", "ORDER", "UNION", "MINUS", "INTERSECT", "EXCEPT", "SET", "ON",
                        "CONNECT", "START", "FETCH", "FOR", "WITH", "SELECT", "WHEN", "JOIN", "USING", "VALUES");
    }

    private boolean atJoinStart() {
        Token t = peek();
        if (t.is("JOIN") || (t.is("STRAIGHT_JOIN") && peek(1).isIdentifier())) {
            return true;
        }
        if (t.is("NATURAL") || t.is("INNER")) {
            return peek(1).isAny("JOIN", "INNER", "LEFT", "RIGHT", "FULL");
        }
        if (t.isAny("LEFT", "RIGHT", "FULL")) {
            return peek(1).isAny("JOIN", "OUTER");
        }
        if (t.is("CROSS")) {
            return peek(1).isAny("JOIN", "APPLY");
        }
        return t.is("OUTER") && peek(1).is("APPLY");
    }

    private TableRef joinChain(TableRef left) {
        while (true) {
            if (at("PARTITION") && peek(1).is("BY")) {
                // 파티션 외부 조인: PARTITION BY (..) 건너뜀
                next();
                next();
                if (atSymbol("(")) {
                    skipBalanced();
                } else {
                    do {
                        parseAdditive();
                    } while (acceptSymbol(","));
                }
                continue;
            }
            if (!atJoinStart()) {
                break;
            }
            boolean natural = accept("NATURAL");
            String type = "INNER";
            if ((at("CROSS") || at("OUTER")) && peek(1).is("APPLY")) {
                type = next().upper() + " APPLY";
                next();
                TableRef right = tablePrimary();
                left = new TableRef.Join(left, type, false, right, null, List.of());
                continue;
            }
            if (atAny("INNER", "LEFT", "RIGHT", "FULL", "CROSS")) {
                type = next().upper();
                accept("OUTER");
            }
            if (!accept("JOIN") && !accept("STRAIGHT_JOIN")) {
                problem("JOIN이 있어야 할 자리: " + describe(peek()), peek(), false);
                break;
            }
            TableRef right = tablePrimary();
            Expr on = null;
            List<String> using = List.of();
            if (!atOnConflict() && accept("ON")) {
                on = parseExpression();
            } else if (at("USING") && peek(1).isSymbol("(")) {
                next();
                using = identList();
            }
            left = new TableRef.Join(left, type, natural, right, on, using);
        }
        // 중첩 조인의 남은 ON
        while (at("ON") && !atOnConflict() && left instanceof TableRef.Join j) {
            next();
            left = new TableRef.Join(j.left(), j.type(), j.natural(), j.right(), and(j.on(), parseExpression()), j.using());
        }
        return left;
    }

    /** 현재 위치에서 여는 괄호를 건너뛴 첫 토큰이 SELECT/WITH/VALUES인가. */
    private boolean queryStartsAfterParens() {
        int i = 0;
        while (peek(i).isSymbol("(")) {
            i++;
        }
        return i > 0 && (peek(i).is("SELECT") || peek(i).is("VALUES") || (peek(i).is("WITH") && peek(i + 1).isIdentifier()));
    }

    private TableRef tablePrimary() {
        Token t = peek();
        if (t.isSymbol("(")) {
            if (queryStartsAfterParens()) {
                return derived(false);
            }
            open();
            TableRef inner = joinChain(tablePrimary());
            close();
            tableAlias();
            return inner;
        }
        if (t.is("LATERAL") && peek(1).isSymbol("(")) {
            next();
            if (queryStartsAfterParens()) {
                return derived(true);
            }
        }
        if (t.is("LATERAL") && functionNameAt(1)) {
            // PostgreSQL LATERAL 함수(..): 왼쪽 테이블의 컬럼을 인자로 받는 테이블 함수(F-019-R15)
            next();
            t = peek();
        }
        if (t.isAny("TABLE", "THE") && peek(1).isSymbol("(")) {
            next();
            if (queryStartsAfterParens()) {
                return derived(true);
            }
            open();
            Expr call = parseExpression();
            close();
            acceptSymbol("(+)");
            return new TableRef.Function(call, functionAlias());
        }
        if (t.isAny("FINAL", "NEW", "OLD") && peek(1).is("TABLE") && peek(2).isSymbol("(")) {
            next();
            next();
            open();
            Statement dml = statement();
            close();
            return new TableRef.Dml(dml, tableAlias());
        }
        if (t.is("ONLY") && onlyClauseAhead()) {
            next();
            open();
            TableRef inner = tablePrimary();
            close();
            // DB2: FROM ONLY (T) 별칭 — 별칭은 괄호 밖에 온다
            String alias = tableAlias();
            if (alias != null && inner instanceof TableRef.Named n && n.alias() == null) {
                return new TableRef.Named(n.name(), alias, n.columnAliases(), n.pivot());
            }
            return inner;
        }
        if (bareOnlyAhead()) {
            next(); // PostgreSQL FROM ONLY 테이블: 수식어다
            t = peek();
        }
        if (atTableNameStop()) {
            problem("테이블 이름이 있어야 할 자리: " + describe(t), t, false);
            if (t.type() == TokenType.BIND || t.type() == TokenType.STRING || t.type() == TokenType.NUMBER) {
                next();
                tableAlias();
            }
            return new TableRef.Unparsed(t.text());
        }
        QName name = qualifiedName();
        if (atSymbol("(")) {
            // 테이블 함수: name(args)
            Expr call = functionCall(name);
            acceptSeq("WITH", "ORDINALITY"); // PostgreSQL: 순번 컬럼을 붙인다
            return new TableRef.Function(call, functionAlias());
        }
        return named(name);
    }

    /**
     * 테이블 함수 뒤의 {@code [AS] 별칭 [(c1, c2 ..)]}. DB2는 상관 이름 뒤에 컬럼 목록({@code AS T (SEQ, TOKEN)}, 타입을 적은
     * {@code AS T (C1 INT, C2 VARCHAR(10))})을 둔다. 목록은 건너뛴다(함수 결과의 컬럼은 추적하지 않는다).
     */
    private String functionAlias() {
        if (at("AS") && peek(1).isSymbol("(")) {
            // PostgreSQL: 별칭 없이 컬럼 정의 목록만 — FROM f(..) AS (a int, b text)
            next();
            skipBalanced();
            return null;
        }
        String alias = tableAlias();
        if (alias != null && atSymbol("(")) {
            skipBalanced();
        }
        return alias;
    }

    /** ahead 위치부터 {@code 이름[.이름] (} 꼴인가(함수 호출의 머리). */
    private boolean functionNameAt(int ahead) {
        int i = ahead;
        if (!peek(i).isIdentifier() || (peek(i).type() == TokenType.IDENT && Keywords.isReservedTableName(peek(i).upper()))) {
            return false;
        }
        i++;
        while (peek(i).isSymbol(".") && peek(i + 1).isIdentifier()) {
            i += 2;
        }
        return peek(i).isSymbol("(");
    }

    /**
     * 현재 토큰이 PostgreSQL의 수식어 {@code ONLY}인가(괄호 없는 {@code ONLY 테이블}, F-019-R15). PostgreSQL 읽기 방식에서만 본다:
     * 다른 방언에서는 {@code ONLY}라는 테이블과 그 별칭({@code UPDATE ONLY O SET ..})으로 읽는 표기다. 뒤가 테이블 이름이 될 수 있어야 한다.
     */
    private boolean bareOnlyAhead() {
        if (!postgres() || !at("ONLY")) {
            return false;
        }
        Token n = peek(1);
        if (n.type() == TokenType.QUOTED_IDENT) {
            return true;
        }
        return n.type() == TokenType.IDENT && (peek(2).isSymbol(".") || !Keywords.isReservedTableName(n.upper()));
    }

    private TableRef named(QName name) {
        tableModifiers();
        String alias = tableAlias();
        indexHints();
        List<String> columnAliases = List.of();
        if (alias != null && atSymbol("(") && peek(1).isIdentifier() && (peek(2).isSymbol(",") || peek(2).isSymbol(")"))) {
            columnAliases = identList();
        }
        boolean pivot = false;
        while (atAny("PIVOT", "UNPIVOT")) {
            pivot = true;
            next();
            accept("XML");
            if (atAny("INCLUDE", "EXCLUDE") && peek(1).is("NULLS")) {
                next();
                next();
            }
            skipBalanced();
            String pivotAlias = tableAlias();
            if (pivotAlias != null) {
                alias = pivotAlias;
            }
        }
        return new TableRef.Named(name, alias, columnAliases, pivot);
    }

    /** MySQL 인덱스 힌트 {@code FORCE|USE|IGNORE INDEX|KEY [FOR JOIN|ORDER BY|GROUP BY] (..)}를 건너뛴다(F-006-R36). */
    private void indexHints() {
        while (atIndexHint()) {
            next();
            next();
            if (accept("FOR")) {
                if (!accept("JOIN")) {
                    acceptAny("ORDER", "GROUP");
                    accept("BY");
                }
            }
            skipBalanced();
        }
    }

    private boolean atIndexHint() {
        return atAny("FORCE", "USE", "IGNORE") && peek(1).isAny("INDEX", "KEY") && (peek(2).isSymbol("(") || peek(2).is("FOR"));
    }

    /** 테이블 이름 뒤의 파티션, 샘플, 플래시백 절을 건너뛴다. */
    private void tableModifiers() {
        while (true) {
            if (atAny("PARTITION", "SUBPARTITION") && (peek(1).isSymbol("(") || (peek(1).is("FOR") && peek(2).isSymbol("(")))) {
                next();
                accept("FOR");
                skipBalanced();
            } else if (at("SAMPLE") && (peek(1).isSymbol("(") || peek(1).is("BLOCK"))) {
                next();
                accept("BLOCK");
                skipBalanced();
                if (accept("SEED")) {
                    skipBalanced();
                }
            } else if (at("VERSIONS") && peek(1).is("BETWEEN")) {
                next();
                next();
                acceptAny("SCN", "TIMESTAMP");
                parseAdditive();
                accept("AND");
                parseAdditive();
            } else if (at("AS") && peek(1).is("OF")) {
                next();
                next();
                acceptAny("SCN", "TIMESTAMP");
                parseAdditive();
            } else if (at("FOR") && peek(1).isAny("SYSTEM_TIME", "BUSINESS_TIME")) {
                next();
                next();
                if (acceptSeq("AS", "OF")) {
                    parseAdditive();
                } else if (accept("FROM")) {
                    parseAdditive();
                    accept("TO");
                    parseAdditive();
                } else if (accept("BETWEEN")) {
                    parseAdditive();
                    accept("AND");
                    parseAdditive();
                }
            } else {
                break;
            }
        }
    }

    private TableRef derived(boolean lateral) {
        open();
        Query q = parseQuery();
        close();
        boolean pivot = false;
        String alias = null;
        List<String> columnAliases = List.of();
        while (true) {
            if (atAny("PIVOT", "UNPIVOT")) {
                pivot = true;
                next();
                accept("XML");
                if (atAny("INCLUDE", "EXCLUDE") && peek(1).is("NULLS")) {
                    next();
                    next();
                }
                skipBalanced();
                continue;
            }
            String a = tableAlias();
            if (a == null) {
                break;
            }
            alias = a;
            if (atSymbol("(") && peek(1).isIdentifier() && (peek(2).isSymbol(",") || peek(2).isSymbol(")"))) {
                columnAliases = identList();
            }
            if (!atAny("PIVOT", "UNPIVOT")) {
                break;
            }
        }
        return new TableRef.Derived(q, alias, columnAliases, lateral, pivot);
    }

    /** {@code [AS] alias}. 없으면 null. */
    private String tableAlias() {
        if (at("AS") && !peek(1).is("OF")) {
            if (peek(1).isIdentifier()) {
                next();
                return next().text();
            }
            return null;
        }
        Token t = peek();
        if (t.type() == TokenType.QUOTED_IDENT) {
            return next().text();
        }
        if (!atAliasStop() && !t.isDynamic()) {
            return next().text();
        }
        return null;
    }

    /**
     * 테이블 이름 자리의 현재 토큰이 이름이 될 수 없는가(F-006-R17). 예약어(SELECT, FROM, WHERE, SET, VALUES, ON ..)만 막고
     * {@code SAMPLE}, {@code LOG}, {@code PARTITION} 같은 비예약 키워드는 이름으로 받는다. 점이 뒤따르면 한정자다.
     */
    private boolean atTableNameStop() {
        Token t = peek();
        if (t.type() == TokenType.QUOTED_IDENT) {
            return false;
        }
        if (t.type() != TokenType.IDENT) {
            return true;
        }
        if (peek(1).isSymbol(".")) {
            return false;
        }
        String u = t.upper();
        if (u.equals("LIMIT") || u.equals("OFFSET")) {
            return peek(1).type() == TokenType.NUMBER || peek(1).type() == TokenType.BIND;
        }
        return Keywords.isReservedTableName(u);
    }

    /**
     * 별칭 자리의 현재 토큰이 별칭이 될 수 없는가(F-006-R17). 식의 첫 토큰이 될 수 없는 절 키워드는 언제나 별칭이 아니고,
     * 그 밖의 비예약 키워드({@link Keywords#ALIAS_STOP_EXTRA})는 뒤 토큰이 진짜 절의 모양일 때만 절로 본다
     * ({@code T SAMPLE (10)}, {@code T PARTITION (p)}, {@code T LOG ERRORS}, {@code T DEFAULT VALUES}).
     */
    private boolean atAliasStop() {
        Token t = peek();
        if (t.type() == TokenType.QUOTED_IDENT) {
            return false;
        }
        if (t.type() != TokenType.IDENT) {
            return true;
        }
        String u = t.upper();
        if (Keywords.EXPR_STOP.contains(u)) {
            return true;
        }
        if (atIndexHint() || (u.equals("STRAIGHT_JOIN") && peek(1).isIdentifier())) {
            return true; // MySQL: T FORCE INDEX (..), T STRAIGHT_JOIN U
        }
        if (!Keywords.ALIAS_STOP_EXTRA.contains(u)) {
            return false;
        }
        Token n = peek(1);
        switch (u) {
            case "PIVOT":
            case "UNPIVOT":
                return n.isSymbol("(") || n.isAny("XML", "INCLUDE", "EXCLUDE");
            case "MODEL":
                return n.isAny("DIMENSION", "PARTITION", "MAIN", "REFERENCE", "RETURN", "UNIQUE", "IGNORE", "KEEP", "UNTIL");
            case "PARTITION":
            case "SUBPARTITION":
                return n.isSymbol("(") || n.isAny("BY", "FOR");
            case "SAMPLE":
                return n.isSymbol("(") || n.is("BLOCK");
            case "LOG":
                return n.is("ERRORS");
            case "SKIP":
                return n.is("LOCKED");
            case "WAIT":
                return n.type() == TokenType.NUMBER || n.type() == TokenType.BIND;
            case "LATERAL":
            case "INCLUDE":
            case "MATCH_RECOGNIZE":
            case "KEEP":
                return n.isSymbol("(");
            case "DEFAULT":
                return n.is("VALUES");
            case "OVERRIDING":
                return n.isAny("SYSTEM", "USER");
            case "LOCK":
                return n.is("IN");
            case "VERSIONS":
                return n.isAny("BETWEEN", "PERIOD");
            case "OPTIMIZE":
                return n.is("FOR");
            case "QUERYNO":
                return n.type() == TokenType.NUMBER;
            case "RETURN":
            case "CALL":
            case "DO":
                // 뒤에 절이나 목록의 끝이 오면 별칭, 식·문장이 오면 RETURN 절·CALL 문·루프 본문의 시작이다
                return !(n.isEof() || n.isSymbol(",") || n.isSymbol(")") || n.isSymbol(";")
                        || n.isAny("WHERE", "GROUP", "HAVING", "ORDER", "UNION", "MINUS", "INTERSECT", "EXCEPT", "JOIN", "INNER",
                                "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "ON", "USING", "CONNECT", "START", "SET", "FROM"));
            default:
                return false;
        }
    }

    /** {@code (a, b, c)} 형태의 이름 목록(원문). 깨진 입력도 받아들인다. */
    private List<String> identList() {
        List<String> list = new ArrayList<>();
        open();
        while (!atEnd() && !atSymbol(")")) {
            if (peek().isIdentifier()) {
                list.add(next().text());
            } else if (!atSymbol(",")) {
                break;
            }
            if (!acceptSymbol(",")) {
                break;
            }
        }
        close();
        return list;
    }

    /** {@code a.b.c[@link]} */
    public QName qualifiedName() {
        Token first = next();
        Token last = first;
        List<String> parts = new ArrayList<>();
        parts.add(first.text());
        while (atSymbol(".") && peek(1).isIdentifier()) {
            next();
            last = next();
            parts.add(last.text());
        }
        String link = dbLink();
        return new QName(parts, link, Pos.span(first, last));
    }

    private String dbLink() {
        if (atSymbol("@") && peek(1).type() == TokenType.IDENT) {
            next();
            StringBuilder link = new StringBuilder(next().text());
            while (atSymbol(".") && peek(1).type() == TokenType.IDENT) {
                next();
                link.append('.').append(next().text());
            }
            return link.toString();
        }
        return null;
    }

    // ------------------------------------------------------------------ DML

    private TableRef dmlTarget() {
        return dmlTarget(false);
    }

    /** 현재 위치가 {@code ONLY (이름)} 꼴인가({@code ONLY}라는 테이블의 컬럼 목록 {@code ONLY (A, B)}와 가린다). */
    private boolean onlyClauseAhead() {
        if (!peek(1).isSymbol("(") || !peek(2).isIdentifier()) {
            return false;
        }
        int i = 3;
        while (peek(i).isSymbol(".") && peek(i + 1).isIdentifier()) {
            i += 2;
        }
        return peek(i).isSymbol(")");
    }

    /** @param insertTarget INSERT의 대상이면 true: {@code ONLY (..)}를 받지 않는다({@code ONLY}라는 테이블의 컬럼 목록이다) */
    private TableRef dmlTarget(boolean insertTarget) {
        if (atSymbol("(")) {
            if (queryStartsAfterParens()) {
                open();
                Query q = parseQuery();
                close();
                return new TableRef.Derived(q, tableAlias(), List.of(), false, false);
            }
            problem("테이블 이름이 있어야 할 자리: (", peek(), false);
            skipBalanced();
            return new TableRef.Unparsed("(");
        }
        if (at("TABLE") && peek(1).isSymbol("(")) {
            next();
            open();
            Expr call = parseExpression();
            close();
            return new TableRef.Function(call, tableAlias());
        }
        if (at("ONLY") && onlyClauseAhead() && !insertTarget) {
            next();
            open();
            TableRef inner = dmlTarget(false);
            close();
            return inner;
        }
        if (!insertTarget && bareOnlyAhead()) {
            next(); // PostgreSQL UPDATE|DELETE [FROM] ONLY 테이블: 수식어다
        }
        Token t = peek();
        if (atTableNameStop()) {
            problem("테이블 이름이 있어야 할 자리: " + describe(t), t, false);
            return new TableRef.Unparsed(t.text());
        }
        QName name = qualifiedName();
        tableModifiers();
        return new TableRef.Named(name, tableAlias(), List.of(), false);
    }

    /** {@code (c1, t.c2, ..)}. 닫는 괄호가 없거나 쉼표가 남아도 받아들인다. */
    private List<QName> columnNameList() {
        List<QName> columns = new ArrayList<>();
        open();
        while (!atEnd() && !atSymbol(")")) {
            if (peek().isIdentifier() && !peek().isAny("VALUES", "SELECT", "FROM", "WHERE", "SET", "INTO")) {
                columns.add(qualifiedName());
                while (peek().isDynamic()) {
                    next(); // 이름 뒤에 홀로 있는 ${} 조각(컬럼 목록의 나머지가 치환되는 자리, F-006-R25)
                }
            } else {
                break;
            }
            if (!acceptSymbol(",")) {
                break;
            }
            if (atSymbol(")")) {
                problem("쉼표 뒤에 이름이 없음", peek(), true);
            }
        }
        close();
        return columns;
    }

    private Statement insert() {
        next(); // INSERT | REPLACE
        // MySQL 수식어: INSERT [LOW_PRIORITY|DELAYED|HIGH_PRIORITY] [IGNORE] INTO (F-006-R36)
        while (atAny("LOW_PRIORITY", "DELAYED", "HIGH_PRIORITY", "IGNORE") && (peek(1).is("INTO") || peek(1).isAny("IGNORE", "LOW_PRIORITY", "DELAYED", "HIGH_PRIORITY"))) {
            next();
        }
        List<Statement.InsertTarget> targets = new ArrayList<>();
        List<Expr> conditions = new ArrayList<>();
        Query source = null;
        if (atAny("ALL", "FIRST") && peek(1).isAny("INTO", "WHEN")) {
            next();
            while (!atEnd()) {
                if (accept("WHEN")) {
                    conditions.add(parseExpression());
                    accept("THEN");
                } else if (accept("ELSE")) {
                    continue;
                } else if (accept("INTO")) {
                    TableRef target = dmlTarget(true);
                    List<QName> columns = atSymbol("(") && !queryStartsAfterParens() ? columnNameList() : List.of();
                    List<List<Expr>> values = new ArrayList<>();
                    if (accept("VALUES") && atSymbol("(")) {
                        open();
                        values.add(exprList());
                        close();
                    }
                    targets.add(new Statement.InsertTarget(target, columns, values));
                } else {
                    break;
                }
            }
            if (at("SELECT") || at("WITH") || atSymbol("(")) {
                source = parseQuery();
            } else {
                problem("INSERT ALL에 SELECT가 없음", peek(), false);
            }
            return new Statement.Insert(targets, source, conditions, List.of());
        }
        if (!accept("INTO")) {
            problem("INSERT에 INTO가 없음", peek(), false);
        }
        TableRef target = dmlTarget(true);
        List<QName> columns = atSymbol("(") && !queryStartsAfterParens() ? columnNameList() : List.of();
        includeColumns();
        if (accept("OVERRIDING")) {
            acceptAny("SYSTEM", "USER");
            accept("VALUE");
        }
        List<List<Expr>> values = new ArrayList<>();
        if (accept("VALUES")) {
            do {
                if (atSymbol("(")) {
                    open();
                    values.add(exprList());
                    close();
                } else if (!atEnd()) {
                    values.add(List.of(parseExpression()));
                } else {
                    problem("VALUES 뒤가 잘림", peek(), true);
                }
            } while (acceptSymbol(","));
        } else if (acceptSeq("DEFAULT", "VALUES")) {
            // 값 없음
        } else if (at("SELECT") || atCteStart() || atSymbol("(")) {
            source = parseQuery();
        } else if (at("SET") && columns.isEmpty() && peek(1).isIdentifier()) {
            // MySQL INSERT INTO t SET a = .., b = ..: SET 항목의 이름이 컬럼 목록이고 값이 VALUES다
            next();
            List<QName> names = new ArrayList<>();
            List<Expr> row = new ArrayList<>();
            for (Statement.Assignment a : assignments()) {
                names.addAll(a.columns());
                row.add(a.value());
            }
            columns = names;
            values.add(row);
        } else {
            problem("INSERT에 VALUES나 SELECT가 없음", peek(), false);
        }
        targets.add(new Statement.InsertTarget(target, columns, values));
        if (acceptSeq("ON", "DUPLICATE", "KEY", "UPDATE")) {
            // MySQL ON DUPLICATE KEY UPDATE a = VALUES(a), ..: 대상 테이블은 같다. 항목은 읽고 넘긴다. 여기서만 VALUES(컬럼)이 함수다
            valuesFunction = true;
            try {
                assignments();
            } finally {
                valuesFunction = false;
            }
        }
        List<Statement.Assignment> conflictAssignments = List.of();
        List<Expr> conflictConditions = new ArrayList<>();
        if (atOnConflict()) {
            // PostgreSQL ON CONFLICT [(컬럼|식, ..) [WHERE ..] | ON CONSTRAINT 이름] DO NOTHING | DO UPDATE SET .. [WHERE ..] (F-019-R12).
            // 대상 테이블은 같다. DO UPDATE의 대입 항목은 그 테이블에 쓰는 컬럼이다
            next();
            next();
            if (atSymbol("(")) {
                skipBalanced(); // 충돌 대상(유일 인덱스의 컬럼·식): 읽기도 쓰기도 아니다
            } else if (acceptSeq("ON", "CONSTRAINT") && peek().isIdentifier()) {
                next();
            }
            if (accept("WHERE")) {
                conflictConditions.add(parseExpression());
            }
            if (!accept("DO")) {
                problem("ON CONFLICT에 DO가 없음", peek(), false);
            } else if (accept("UPDATE")) {
                accept("SET");
                conflictAssignments = assignments();
                if (accept("WHERE")) {
                    conflictConditions.add(parseExpression());
                }
            } else if (!accept("NOTHING")) {
                problem("ON CONFLICT DO 뒤에 NOTHING이나 UPDATE가 없음", peek(), false);
            }
        }
        List<Expr> returning = returningClause();
        if (at("LOG") && peek(1).is("ERRORS")) {
            pos = tokens.size() - 1;
        }
        return new Statement.Insert(targets, source, conditions, returning, conflictAssignments, conflictConditions);
    }

    /**
     * DB2 데이터 변경 테이블 참조({@code FROM FINAL TABLE (INSERT ..)})의 {@code INCLUDE (이름 타입, ..)}를 건너뛰고 그 컬럼 이름(정규화)을
     * 돌려준다. 결과에만 붙는 컬럼이고 대상 테이블의 컬럼이 아니다. 절이 없으면 빈 목록.
     */
    private List<String> includeColumns() {
        if (!(at("INCLUDE") && peek(1).isSymbol("("))) {
            return List.of();
        }
        next();
        List<String> names = new ArrayList<>();
        int depth = 0;
        boolean itemStart = false;
        while (!atEnd()) {
            Token t = next();
            if (t.isSymbol("(")) {
                depth++;
                itemStart = depth == 1;
            } else if (t.isSymbol(")")) {
                depth--;
                if (depth == 0) {
                    break;
                }
            } else if (depth == 1 && t.isSymbol(",")) {
                itemStart = true;
            } else if (itemStart && t.isIdentifier()) {
                names.add(io.codeatlas.core.model.DbName.normalize(t.text()));
                itemStart = false;
            }
        }
        return names;
    }

    private List<Expr> returningClause() {
        List<Expr> list = new ArrayList<>();
        if (atAny("RETURNING", "RETURN")) {
            next();
            do {
                if (atEnd() || at("INTO") || at("BULK")) {
                    break;
                }
                list.add(parseExpression());
            } while (acceptSymbol(","));
            acceptSeq("BULK", "COLLECT");
            if (accept("INTO")) {
                do {
                    if (atEnd()) {
                        break;
                    }
                    parseAdditive();
                } while (acceptSymbol(","));
            }
        }
        return list;
    }

    private Statement update() {
        next(); // UPDATE
        TableRef target = dmlTarget();
        List<TableRef> joined = new ArrayList<>();
        if (target instanceof TableRef.Named && (atJoinStart() || (atSymbol(",") && peek(1).isIdentifier()))) {
            // MySQL UPDATE a JOIN b ON .. SET, UPDATE a, b SET ..: 첫 테이블이 대상이고 나머지는 함께 읽는 테이블이다(F-006-R36)
            TableRef.Named first = (TableRef.Named) target;
            joined.add(joinChain(new TableRef.Unparsed("")));
            while (acceptSymbol(",")) {
                joined.add(joinChain(tablePrimary()));
            }
            target = first;
        }
        List<String> include = includeColumns();
        if (!accept("SET")) {
            problem("UPDATE에 SET이 없음", peek(), false);
        }
        List<Statement.Assignment> assignments = assignments();
        if (!include.isEmpty()) {
            // INCLUDE 컬럼에 대입한 항목: 대상 테이블의 컬럼이 아니다. 값 식은 남긴다(읽은 컬럼이 있다)
            List<Statement.Assignment> kept = new ArrayList<>();
            for (Statement.Assignment a : assignments) {
                boolean included = a.columns().size() == 1 && a.columns().get(0).size() == 1 && include.contains(a.columns().get(0).name());
                kept.add(included ? new Statement.Assignment(List.of(), a.value()) : a);
            }
            assignments = kept;
        }
        List<TableRef> from = accept("FROM") ? new ArrayList<>(parseTableRefs()) : new ArrayList<>();
        from.addAll(0, joined);
        if (joined.isEmpty() && !from.isEmpty() && target instanceof TableRef.Named n && n.alias() == null && n.name().size() == 1) {
            // MSSQL·PostgreSQL식 UPDATE 별칭 SET .. FROM 테이블 별칭: 대상은 FROM에서 그 별칭이 가리키는 테이블이다(F-006-R21)
            TableRef.Named real = detach(from, n.name().name());
            if (real != null) {
                target = real;
            }
        }
        Expr where = whereClause();
        dmlTail();
        List<Expr> returning = returningClause();
        return new Statement.Update(target, assignments, from, where, returning);
    }

    /** MySQL의 UPDATE·DELETE 끝에 오는 {@code ORDER BY .. LIMIT n}을 받아 넘긴다(F-006-R18). */
    private void dmlTail() {
        if (at("ORDER") && peek(1).is("BY")) {
            next();
            next();
            orderItems(new ArrayList<>());
        }
        if (at("LIMIT") && (peek(1).type() == TokenType.NUMBER || peek(1).type() == TokenType.BIND)) {
            next();
            parseAdditive();
        }
    }

    /**
     * refs(조인 트리 포함)에서 별칭(없으면 이름)이 name인 이름 테이블을 찾아 떼어 낸다. 떼어 낸 자리에는 빈 자리 표시를 둬
     * 조인 구조와 ON 조건은 남긴다. 없으면 null.
     */
    private static TableRef.Named detach(List<TableRef> refs, String name) {
        TableRef.Named[] found = new TableRef.Named[1];
        for (int i = 0; i < refs.size() && found[0] == null; i++) {
            refs.set(i, detach(refs.get(i), name, found));
        }
        return found[0];
    }

    private static TableRef detach(TableRef ref, String name, TableRef.Named[] found) {
        if (found[0] != null) {
            return ref;
        }
        if (ref instanceof TableRef.Named n) {
            String visible = n.alias() != null ? io.codeatlas.core.model.DbName.normalize(n.alias())
                    : (n.name().anyDynamic() ? null : n.name().name());
            if (name.equals(visible)) {
                found[0] = n;
                return new TableRef.Unparsed("");
            }
            return ref;
        }
        if (ref instanceof TableRef.Join j) {
            TableRef left = detach(j.left(), name, found);
            TableRef right = detach(j.right(), name, found);
            return found[0] == null ? ref : new TableRef.Join(left, j.type(), j.natural(), right, j.on(), j.using());
        }
        return ref;
    }

    private boolean atAssignmentEnd() {
        Token t = peek();
        return t.isEof() || t.isSymbol(")") || t.isSymbol(";")
                || (t.isAny("WHERE", "FROM", "RETURNING", "WHEN", "DELETE", "ELSE", "LOG") && !peek(1).isSymbol("=") && !peek(1).isSymbol("."));
    }

    private List<Statement.Assignment> assignments() {
        List<Statement.Assignment> list = new ArrayList<>();
        boolean fragment = false;
        while (true) {
            // 항목 자리에 홀로 있는 ${} 조각(SET 항목들이 통째로 치환되는 자리): 항목이 아니라 조각으로 건너뛴다(F-006-R25)
            while (peek().isDynamic() && !peek(1).isSymbol("=") && !peek(1).isSymbol(".") && !peek(1).isSymbol("(")) {
                next();
                fragment = true;
                acceptSymbol(",");
            }
            if (atAssignmentEnd()) {
                if (!fragment) {
                    problem("SET 항목이 없음", peek(), true);
                }
                break;
            }
            int before = pos;
            if (atSymbol("(")) {
                List<QName> columns = columnNameList();
                Expr value;
                if (acceptSymbol("=")) {
                    value = parseExpression();
                } else {
                    problem("SET 항목에 = 가 없음", peek(), false);
                    value = new Expr.Missing();
                }
                list.add(new Statement.Assignment(columns, value));
            } else if (peek().isIdentifier()) {
                QName column = qualifiedName();
                Expr value;
                if (acceptSymbol("=")) {
                    value = parseExpression();
                } else {
                    problem("SET 항목에 = 가 없음", peek(), false);
                    value = new Expr.Missing();
                }
                list.add(new Statement.Assignment(List.of(column), value));
            } else {
                problem("SET 항목을 해석하지 못함: " + describe(peek()), peek(), false);
                break;
            }
            if (!acceptSymbol(",")) {
                break;
            }
            if (pos == before) {
                next();
            }
        }
        return list;
    }

    private Expr whereClause() {
        if (!accept("WHERE")) {
            return null;
        }
        if (at("CURRENT") && peek(1).is("OF")) {
            next();
            next();
            if (peek().isIdentifier()) {
                next();
            }
            return null;
        }
        return parseExpression();
    }

    private Statement delete() {
        next(); // DELETE
        if (peek().isIdentifier() && !at("FROM") && peek(1).isSymbol(",") && multiDeleteAhead()) {
            // MySQL DELETE a, b FROM a JOIN b ..: 앞의 이름들은 FROM의 테이블을 가리키는 별칭이다. 모두 지우는 대상이다(F-006-R36)
            List<QName> leading = new ArrayList<>();
            do {
                leading.add(qualifiedName());
                if (atSymbol(".") && peek(1).isSymbol("*")) {
                    next();
                    next();
                }
            } while (acceptSymbol(","));
            next(); // FROM
            List<TableRef> from = new ArrayList<>(parseTableRefs());
            List<TableRef> found = new ArrayList<>();
            for (QName name : leading) {
                TableRef.Named real = name.anyDynamic() ? null : detach(from, name.name());
                found.add(real != null ? real : new TableRef.Named(name, null, List.of(), false));
            }
            Expr where = whereClause();
            dmlTail();
            return new Statement.Delete(found.get(0), from, where, returningClause(), found.subList(1, found.size()));
        }
        if (peek().isIdentifier() && !at("FROM") && peek(1).is("FROM") && !atTableNameStop()) {
            // MySQL·MSSQL식 DELETE 별칭 FROM 테이블 별칭 [JOIN ..]: 앞의 이름은 FROM의 테이블을 가리키는 별칭이다(F-006-R21)
            QName leading = qualifiedName();
            next(); // FROM
            List<TableRef> from = new ArrayList<>(parseTableRefs());
            TableRef.Named real = leading.anyDynamic() ? null : detach(from, leading.name());
            TableRef target = real != null ? real : new TableRef.Named(leading, null, List.of(), false);
            Expr where = whereClause();
            dmlTail();
            return new Statement.Delete(target, from, where, returningClause());
        }
        accept("FROM");
        TableRef target = dmlTarget();
        includeColumns();
        List<TableRef> from = new ArrayList<>();
        if (accept("USING")) {
            // PostgreSQL DELETE FROM 대상 [별칭] USING 테이블, .. WHERE ..: USING의 테이블은 함께 읽는 테이블이다(F-019-R13).
            // MySQL의 DELETE FROM a USING a JOIN b ..는 대상을 USING에 다시 적는다: 그 자리는 읽는 테이블이 아니라 대상이다
            from.addAll(parseTableRefs());
            if (target instanceof TableRef.Named n && n.alias() == null && !n.name().anyDynamic()) {
                TableRef.Named real = detach(from, n.name().name());
                if (real != null) {
                    target = real;
                }
            }
        }
        Expr where = whereClause();
        dmlTail();
        List<Expr> returning = returningClause();
        return new Statement.Delete(target, from, where, returning);
    }

    /** 현재 위치가 {@code 이름[.*], 이름[.*] .. FROM} 꼴인가(MySQL 다중 테이블 DELETE). */
    private boolean multiDeleteAhead() {
        int i = 0;
        while (true) {
            if (!peek(i).isIdentifier() || peek(i).is("FROM")) {
                return false;
            }
            i++;
            while (peek(i).isSymbol(".") && (peek(i + 1).isIdentifier() || peek(i + 1).isSymbol("*"))) {
                i += 2;
            }
            if (peek(i).is("FROM")) {
                return true;
            }
            if (!peek(i).isSymbol(",")) {
                return false;
            }
            i++;
        }
    }

    private Statement merge() {
        next(); // MERGE
        if (!accept("INTO")) {
            problem("MERGE에 INTO가 없음", peek(), false);
        }
        TableRef target = dmlTarget();
        TableRef source = null;
        if (accept("USING")) {
            source = joinChain(tablePrimary());
        } else {
            problem("MERGE에 USING이 없음", peek(), false);
        }
        Expr on = accept("ON") ? parseExpression() : null;
        List<Statement.MergeAction> actions = new ArrayList<>();
        while (at("WHEN")) {
            next();
            boolean matched = !accept("NOT");
            accept("MATCHED");
            Expr condition = accept("AND") ? parseExpression() : null;
            accept("THEN");
            if (accept("UPDATE")) {
                accept("SET");
                List<Statement.Assignment> assignments = assignments();
                Expr where = accept("WHERE") ? parseExpression() : null;
                Expr deleteWhere = null;
                if (at("DELETE") && peek(1).is("WHERE")) {
                    next();
                    next();
                    deleteWhere = parseExpression();
                }
                actions.add(new Statement.MergeAction(matched, condition, "UPDATE", assignments, List.of(), List.of(), where, deleteWhere));
            } else if (accept("INSERT")) {
                List<QName> columns = atSymbol("(") ? columnNameList() : List.of();
                List<Expr> values = new ArrayList<>();
                if (accept("VALUES")) {
                    if (atSymbol("(")) {
                        open();
                        values.addAll(exprList());
                        close();
                    } else if (!atEnd() && !at("WHEN")) {
                        values.add(parseExpression());
                    }
                }
                Expr where = accept("WHERE") ? parseExpression() : null;
                actions.add(new Statement.MergeAction(matched, condition, "INSERT", List.of(), columns, values, where, null));
            } else if (accept("DELETE")) {
                actions.add(new Statement.MergeAction(matched, condition, "DELETE", List.of(), List.of(), List.of(), null, null));
            } else {
                // SIGNAL 등: 다음 WHEN까지 건너뜀
                while (!atEnd() && !at("WHEN") && !at("ELSE")) {
                    next();
                }
            }
        }
        acceptSeq("ELSE", "IGNORE");
        if (at("LOG") && peek(1).is("ERRORS")) {
            pos = tokens.size() - 1;
        }
        return new Statement.Merge(target, source, on, actions);
    }

    private Statement callRest() {
        if (!peek().isIdentifier()) {
            problem("호출할 루틴 이름이 없음", peek(), false);
            pos = tokens.size() - 1;
            return new Statement.Unparsed("CALL");
        }
        QName name = qualifiedName();
        List<Expr> args = new ArrayList<>();
        if (atSymbol("(")) {
            open();
            genericArgs(args, "");
            close();
        }
        if (accept("INTO")) {
            parseAdditive();
        }
        return new Statement.Call(name, args);
    }

    private Statement exec() {
        next(); // EXEC | EXECUTE
        if (at("IMMEDIATE")) {
            pos = tokens.size() - 1;
            return new Statement.Other("EXECUTE IMMEDIATE", true);
        }
        if (peek().type() == TokenType.BIND && peek(1).isSymbol(":=")) {
            next();
            next();
        }
        return callRest();
    }

    private Statement jdbcCall() {
        next(); // {
        if (peek().type() == TokenType.BIND && peek(1).isSymbol("=")) {
            next();
            next();
        }
        if (!accept("CALL")) {
            return unparsed();
        }
        Statement call = callRest();
        acceptSymbol("}");
        return call;
    }

    private Statement truncate() {
        next(); // TRUNCATE
        accept("TABLE");
        if (bareOnlyAhead()) {
            next();
        }
        if (!peek().isIdentifier()) {
            problem("테이블 이름이 있어야 할 자리: " + describe(peek()), peek(), false);
            return unparsed();
        }
        // PostgreSQL TRUNCATE [TABLE] [ONLY] t, .. [RESTART IDENTITY] [CASCADE]: 쉼표로 이은 테이블을 모두 비운다(F-019-R15)
        List<Statement> list = new ArrayList<>();
        list.add(new Statement.Truncate(qualifiedName()));
        while (atSymbol(",") && (peek(1).isIdentifier())) {
            next();
            if (bareOnlyAhead()) {
                next();
            }
            list.add(new Statement.Truncate(qualifiedName()));
        }
        pos = tokens.size() - 1;
        return list.size() == 1 ? list.get(0) : new Statement.Sequence(list);
    }

    private Statement lockTable() {
        next(); // LOCK
        if (!accept("TABLE")) {
            return unparsed();
        }
        List<QName> names = new ArrayList<>();
        do {
            if (!peek().isIdentifier() || at("IN")) {
                break;
            }
            names.add(qualifiedName());
            tableModifiers();
        } while (acceptSymbol(","));
        pos = tokens.size() - 1;
        return new Statement.LockTable(names);
    }

    // ------------------------------------------------------------------ 익명 블록

    /**
     * 익명 블록을 본문 파서({@link RoutineParser}, F-008)로 읽어 안의 SQL 구문과 프로시저 호출문을 모은다.
     * 변수 스코프와 호출 판정까지 하는 분석은 {@code RoutineBodyHandler.analyzeBlock}이 한다. 여기서는 구문 트리만 돌려준다.
     */
    private Statement block() {
        List<Statement> found = new ArrayList<>();
        boolean[] dynamic = {false};
        int end = tokens.size() - 1;
        if (depth + 1 < MAX_DEPTH) {
            List<Token> sub = new ArrayList<>(tokens.subList(pos, end));
            RoutineParser parser = new RoutineParser(sub, RoutineLanguage.of(syntax, peek(1).is("ATOMIC")), depth + 1);
            Routine.Block body = parser.parseBody(false);
            problems.addAll(parser.problems());
            Routine.visit(body, new Routine.Visitor() {
                @Override
                public void statement(Routine.Stmt s) {
                    if (s instanceof Routine.Call c) {
                        if (!Keywords.isBuiltinCall(c.name().parts().get(0))) {
                            subParse(c.tokens(), found, true);
                        }
                    } else if (s instanceof Routine.ExecuteImmediate || s instanceof Routine.Prepare
                            || (s instanceof Routine.Open o && o.dynamic() != null)) {
                        dynamic[0] = true;
                    }
                }

                @Override
                public void sql(Routine.Sql q) {
                    for (Routine.Problem p : q.problems()) {
                        problems.add(new ParseProblem(p.message(), p.pos(), p.minor()));
                    }
                    Statement st = q.statement();
                    if (!(st instanceof Statement.Empty) && !(st instanceof Statement.Unparsed)) {
                        found.add(st);
                    }
                }
            });
        } else {
            problem("중첩이 너무 깊어 건너뜀", peek(), false);
        }
        pos = end;
        return new Statement.Block(found, dynamic[0]);
    }

    private void subParse(List<Token> sub, List<Statement> found, boolean call) {
        if (sub.isEmpty()) {
            return;
        }
        SqlParser parser = new SqlParser(new ArrayList<>(sub), syntax);
        parser.depth = depth + 1;
        if (parser.depth > MAX_DEPTH) {
            return;
        }
        Statement s = call ? parser.callRest() : parser.statement();
        while (parser.acceptSymbol(";")) {
            // 종결자
        }
        if (!parser.atEnd()) {
            parser.problem("해석하지 못한 토큰: " + parser.peek().text(), parser.peek(), false);
        }
        problems.addAll(parser.problems);
        if (!(s instanceof Statement.Empty) && !(s instanceof Statement.Unparsed)) {
            found.add(s);
        }
    }

    // ------------------------------------------------------------------ 구문 끝의 복구

    /** 남은 토큰에서 FROM/JOIN 바로 뒤의 테이블 이름을 건진다(EXTRACT 같은 함수 안의 FROM은 제외). */
    private void salvage(List<QName> into) {
        List<Boolean> fromFunctionStack = new ArrayList<>();
        while (!atEnd()) {
            Token t = peek();
            if (t.isSymbol("(")) {
                Token prev = pos > 0 ? previous() : t;
                fromFunctionStack.add(prev.type() == TokenType.IDENT && Keywords.FROM_FUNCTIONS.contains(prev.upper()));
                next();
            } else if (t.isSymbol(")")) {
                if (!fromFunctionStack.isEmpty()) {
                    fromFunctionStack.remove(fromFunctionStack.size() - 1);
                }
                next();
            } else if ((t.is("FROM") || t.is("JOIN")) && !(pos > 0 && previous().is("DISTINCT"))
                    && (fromFunctionStack.isEmpty() || !fromFunctionStack.get(fromFunctionStack.size() - 1))) {
                next();
                // 키워드 바로 뒤의 이름 하나만 확실한 테이블이다. 쉼표 뒤는 잇지 않는다(F-006-R16)
                if (peek().isIdentifier() && !atTableNameStop()) {
                    QName name = qualifiedName();
                    if (!atSymbol("(")) {
                        into.add(name);
                        if (!atAliasStop()) {
                            next();
                        }
                    }
                }
            } else {
                next();
            }
        }
    }

    // ------------------------------------------------------------------ 식

    /** 조건을 포함한 식 하나. */
    public Expr parseExpression() {
        if (++depth > MAX_DEPTH) {
            depth--;
            problem("중첩이 너무 깊어 건너뜀", peek(), false);
            skipToCloseParen();
            return new Expr.Unparsed("...");
        }
        try {
            Expr left = parseAnd();
            while (at("OR")) {
                next();
                left = new Expr.Binary("OR", left, parseAnd());
            }
            return left;
        } finally {
            depth--;
        }
    }

    private Expr parseAnd() {
        Expr left = parseNot();
        skipLoneDynamic();
        while (at("AND")) {
            next();
            left = new Expr.Binary("AND", left, parseNot());
            skipLoneDynamic();
        }
        return left;
    }

    /** 조건이나 절이 끝났다는 것을 알리는 키워드(홀로 있는 동적 조각 뒤에 올 수 있는 것). FROM과 쉼표는 없다: 선택 목록의 별칭 자리다. */
    private static final java.util.Set<String> AFTER_FRAGMENT = java.util.Set.of(
            "AND", "OR", "WHERE", "GROUP", "HAVING", "ORDER", "UNION", "MINUS", "INTERSECT", "EXCEPT", "FETCH", "OFFSET", "LIMIT",
            "FOR", "CONNECT", "START", "WITH", "RETURNING", "WHEN", "THEN", "ELSE", "END", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "CROSS", "NATURAL", "ON", "SET", "VALUES");

    /**
     * 식이 끝난 자리에 홀로 있는 동적 식별자({@code ${sqlMap.dsf}} 같은, 실행할 때 조건이나 절 통째로 바뀌는 치환)를 불투명한 SQL 조각으로
     * 건너뛴다(F-006-R25). 뒤 토큰이 조건이나 절의 끝일 때만이다: 이름 일부가 동적인 테이블·컬럼, 선택 목록의 별칭 자리는 건드리지 않는다.
     */
    private void skipLoneDynamic() {
        while (peek().isDynamic()) {
            Token after = peek(1);
            boolean end = after.isEof() || after.isSymbol(")") || after.isSymbol(";") || after.isDynamic()
                    || (after.type() == TokenType.IDENT && AFTER_FRAGMENT.contains(after.upper()));
            if (!end) {
                return;
            }
            next();
        }
    }

    private Expr parseNot() {
        if (at("NOT")) {
            next();
            if (++depth > MAX_DEPTH) {
                depth--;
                skipToCloseParen();
                return new Expr.Unparsed("...");
            }
            try {
                return new Expr.Unary("NOT", parseNot());
            } finally {
                depth--;
            }
        }
        return parsePredicate();
    }

    private Expr parsePredicate() {
        Expr left = parseAdditive();
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.SYMBOL) {
                String op = comparisonOperator();
                if (op == null) {
                    if (t.isSymbol("(+)")) {
                        next();
                        continue;
                    }
                    break;
                }
                if (atAny("ANY", "SOME", "ALL") && peek(1).isSymbol("(")) {
                    next();
                }
                Expr right = parseAdditive();
                left = new Expr.Binary(op, left, right);
                continue;
            }
            if (t.is("IS")) {
                next();
                boolean not = accept("NOT");
                if (accept("NULL")) {
                    left = new Expr.Unary(not ? "IS NOT NULL" : "IS NULL", left);
                } else if (acceptSeq("DISTINCT", "FROM")) {
                    left = new Expr.Binary("IS DISTINCT FROM", left, parseAdditive());
                } else if (at("OF") && (peek(1).isSymbol("(") || (peek(1).is("TYPE") && peek(2).isSymbol("(")))) {
                    // Oracle 객체 타입 조건: x IS [NOT] OF [TYPE] (타입, ..)
                    next();
                    accept("TYPE");
                    skipBalanced();
                    left = new Expr.Unary("IS", left);
                } else if (acceptSeq("A", "SET")) {
                    left = new Expr.Unary("IS", left); // 컬렉션 조건: x IS [NOT] A SET
                } else {
                    for (int i = 0; i < 3 && peek().type() == TokenType.IDENT && !Keywords.EXPR_STOP.contains(peek().upper()); i++) {
                        next();
                    }
                    left = new Expr.Unary("IS", left);
                }
                continue;
            }
            boolean not = false;
            if (t.is("NOT") && (peek(1).isAny("IN", "BETWEEN", "LIKE", "LIKEC", "LIKE2", "LIKE4", "REGEXP", "RLIKE")
                    || (peek(1).isAny("MEMBER", "SUBMULTISET") && peek(2).is("OF"))
                    || (peek(1).is("ILIKE") && operandStartsAt(2)) || (peek(1).is("SIMILAR") && peek(2).is("TO")))) {
                next();
                not = true;
            }
            if (atAny("MEMBER", "SUBMULTISET") && peek(1).is("OF")) {
                // Oracle 컬렉션 조건: x [NOT] MEMBER OF 컬렉션, x [NOT] SUBMULTISET OF 컬렉션
                String op = next().upper() + " OF";
                next();
                left = new Expr.Binary(not ? "NOT " + op : op, left, parseAdditive());
                continue;
            }
            if (at("IN")) {
                next();
                left = inRest(left, not);
                continue;
            }
            if (at("BETWEEN")) {
                next();
                Expr low = parseAdditive();
                Expr high;
                if (accept("AND")) {
                    high = parseAdditive();
                } else {
                    problem("BETWEEN에 AND가 없음", peek(), false);
                    high = new Expr.Missing();
                }
                left = new Expr.Between(left, low, high, not);
                continue;
            }
            if (atAny("REGEXP", "RLIKE") && (peek(1).type() == TokenType.STRING || peek(1).type() == TokenType.BIND
                    || (peek(1).isIdentifier() && !(peek(1).type() == TokenType.IDENT && Keywords.EXPR_STOP.contains(peek(1).upper()))))) {
                next(); // MySQL 정규식 연산자: x [NOT] REGEXP 패턴
                left = new Expr.Binary(not ? "NOT LIKE" : "LIKE", left, parseAdditive());
                continue;
            }
            // PostgreSQL x [NOT] SIMILAR TO 패턴 [ESCAPE ..]과 x [NOT] ILIKE 패턴: LIKE처럼 읽는다(F-019-R15).
            // ILIKE는 뒤에 피연산자가 올 때만 연산자다(ILIKE라는 별칭과 가린다)
            boolean similarTo = at("SIMILAR") && peek(1).is("TO");
            if (similarTo || atAny("LIKE", "LIKEC", "LIKE2", "LIKE4") || (at("ILIKE") && operandStartsAt(1))) {
                next();
                if (similarTo) {
                    next();
                }
                Expr right = parseAdditive();
                if (accept("ESCAPE")) {
                    parseAdditive();
                }
                left = new Expr.Binary(not ? "NOT LIKE" : "LIKE", left, right);
                continue;
            }
            break;
        }
        return left;
    }

    /** 현재 토큰이 비교 연산자면 소비하고 정규화한 연산자를 돌려준다. 아니면 null. */
    private String comparisonOperator() {
        Token t = peek();
        switch (t.text()) {
            case "=":
                next();
                return "=";
            case "<>":
            case "!=":
            case "^=":
            case "~=":
                next();
                return "<>";
            case "<=":
            case ">=":
                next();
                return t.text();
            case "<":
                next();
                if (atSymbol(">") && peek().offset() == t.endOffset() + 1) {
                    next();
                    return "<>";
                }
                if (atSymbol("=")) {
                    next();
                    return "<=";
                }
                return "<";
            case ">":
                next();
                if (atSymbol("=")) {
                    next();
                    return ">=";
                }
                return ">";
            case "!":
                if (peek(1).isSymbol("=")) {
                    next();
                    next();
                    return "<>";
                }
                return null;
            default:
                return null;
        }
    }

    private Expr inRest(Expr left, boolean not) {
        if (atSymbol("(")) {
            if (queryStartsAfterParens()) {
                open();
                Query q = parseQuery();
                close();
                return new Expr.In(left, not, null, q);
            }
            open();
            List<Expr> list = exprList();
            if (list.isEmpty()) {
                problem("IN 목록이 비어 있음", peek(), true);
            }
            close();
            return new Expr.In(left, not, list, null);
        }
        if (canStartOperand()) {
            return new Expr.In(left, not, List.of(parseAdditive()), null);
        }
        problem("IN 뒤에 목록이 없음", peek(), true);
        return new Expr.In(left, not, List.of(), null);
    }

    /** 닫는 괄호 앞까지 쉼표로 나뉜 식 목록. 비어 있거나 쉼표가 남아도 받아들인다. */
    private List<Expr> exprList() {
        List<Expr> list = new ArrayList<>();
        if (atSymbol(")")) {
            return list;
        }
        while (true) {
            if (atSymbol(")") || atEnd()) {
                problem("쉼표 뒤에 식이 없음", peek(), true);
                break;
            }
            int before = pos;
            list.add(parseExpression());
            if (!acceptSymbol(",")) {
                break;
            }
            if (pos == before) {
                next();
            }
        }
        return list;
    }

    private boolean canStartOperand() {
        Token t = peek();
        switch (t.type()) {
            case EOF:
                return false;
            case SYMBOL:
                return t.isSymbol("(") || t.isSymbol("-") || t.isSymbol("+");
            case IDENT:
                return !Keywords.EXPR_STOP.contains(t.upper()) || peek(1).isSymbol(".")
                        || (Keywords.FUNCTION_KEYWORDS.contains(t.upper()) && peek(1).isSymbol("("));
            default:
                return true;
        }
    }

    /**
     * 비교·논리 연산을 뺀 식. 산술·연결 연산 위에 PostgreSQL의 그 밖의 연산자({@code -> ->> #> #>> @> <@ && ~ ~* !~ !~* @@})를 둔다(F-019-R15):
     * 비교보다 먼저, 덧셈보다 나중에 묶인다.
     */
    public Expr parseAdditive() {
        Expr left = parseSum();
        while (true) {
            String op = postgresOperator();
            if (op == null) {
                break;
            }
            left = new Expr.Binary(op, left, parseSum());
        }
        return left;
    }

    /** t 바로 뒤에 공백 없이 next가 붙어 있는가. */
    private static boolean adjacent(Token t, Token next) {
        return !next.isEof() && next.offset() == t.endOffset() && next.endOffset() > next.offset();
    }

    /**
     * 현재 위치가 PostgreSQL 연산자면 소비하고 그 연산자를 돌려준다. 아니면 null(아무것도 소비하지 않는다).
     * PostgreSQL 읽기 방식의 렉서는 이 연산자들을 기호 하나로 낸다. 다른 읽기 방식의 렉서는 붙어 있는 기호 둘·셋으로 내므로
     * ({@code -}{@code >>}, {@code @}{@code >}, {@code ~}{@code *}) 여기서 붙여 읽는다: 이 연산자들은 다른 방언의 문법과 겹치지 않는다.
     * {@code #>}·{@code #>>}는 PostgreSQL 읽기 방식에서만 있다(다른 방식에서는 {@code #}이 이름의 글자다).
     * MySQL의 사용자 변수 비교({@code a<@v})와 시스템 변수({@code @@v})는 연산자가 아니다.
     */
    private String postgresOperator() {
        Token t = peek();
        if (t.type() != TokenType.SYMBOL) {
            return null;
        }
        Token n = peek(1);
        boolean glued = adjacent(t, n);
        switch (t.text()) {
            case "->": case "->>": case "#>": case "#>>": case "@>": case "<@": case "&&": case "~*": case "!~": case "!~*": case "@@":
                next();
                return t.text();
            case "~":
                next();
                if (glued && n.isSymbol("*")) {
                    next();
                    return "~*";
                }
                return "~";
            case "-":
                if (glued && (n.isSymbol(">") || n.isSymbol(">>"))) {
                    next();
                    next();
                    return "-" + n.text();
                }
                return null;
            case "@":
                if (glued && n.isSymbol(">")) {
                    next();
                    next();
                    return "@>";
                }
                if (glued && n.isSymbol("@") && !variableFollows(n)) {
                    next();
                    next();
                    return "@@";
                }
                return null;
            case "<":
                if (glued && n.isSymbol("@") && !variableFollows(n)) {
                    next();
                    next();
                    return "<@";
                }
                return null;
            case "&":
                // SQL*Plus의 치환 변수(&&이름)는 이름이 붙어 있다: 그 꼴은 연산자가 아니다
                if (glued && n.isSymbol("&") && !adjacent(n, peek(2))) {
                    next();
                    next();
                    return "&&";
                }
                return null;
            case "!":
                if (glued && n.isSymbol("~")) {
                    next();
                    next();
                    if (atSymbol("*") && adjacent(n, peek())) {
                        next();
                        return "!~*";
                    }
                    return "!~";
                }
                return null;
            default:
                return null;
        }
    }

    /** at({@code @}) 바로 뒤에 이름이나 {@code @}가 붙어 있는가: MySQL의 변수({@code @v}, {@code @@v})다. at은 현재 토큰의 다음 토큰이다. */
    private boolean variableFollows(Token at) {
        Token after = peek(2);
        return adjacent(at, after) && (after.isIdentifier() || after.isSymbol("@"));
    }

    /** ahead 위치의 토큰이 피연산자를 열 수 있는가(문자열, 수, 바인드, 여는 괄호, 절 키워드가 아닌 이름). */
    private boolean operandStartsAt(int ahead) {
        Token x = peek(ahead);
        switch (x.type()) {
            case STRING: case NUMBER: case BIND: case QUOTED_IDENT:
                return true;
            case SYMBOL:
                return x.isSymbol("(");
            case IDENT:
                return !Keywords.EXPR_STOP.contains(x.upper()) || peek(ahead + 1).isSymbol(".");
            default:
                return false;
        }
    }

    /** 산술·연결 연산. */
    private Expr parseSum() {
        Expr left = parseMultiplicative();
        while (true) {
            String op;
            if (atSymbol("-") && adjacent(peek(), peek(1)) && (peek(1).isSymbol(">") || peek(1).isSymbol(">>"))) {
                break; // 붙여 쓴 -> 와 ->> 는 뺄셈이 아니라 PostgreSQL·MySQL의 JSON 연산자다(postgresOperator가 읽는다)
            }
            if (atSymbol("+") || atSymbol("-") || atSymbol("||")) {
                op = next().text();
            } else if (at("CONCAT") && !peek(1).isSymbol("(")) {
                next();
                op = "||";
            } else {
                break;
            }
            Expr right = parseMultiplicative();
            if (peek().type() == TokenType.IDENT && Keywords.DURATION_UNITS.contains(peek().upper())) {
                next();
            }
            left = new Expr.Binary(op, left, right);
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (atSymbol("*") || atSymbol("/")) {
            String op = next().text();
            left = new Expr.Binary(op, left, parseUnary());
        }
        return left;
    }

    private Expr parseUnary() {
        if (atSymbol("-") || atSymbol("+")) {
            String op = next().text();
            if (++depth > MAX_DEPTH) {
                depth--;
                skipToCloseParen();
                return new Expr.Unparsed("...");
            }
            try {
                return new Expr.Unary(op, parseUnary());
            } finally {
                depth--;
            }
        }
        if (at("PRIOR") && !peek(1).isSymbol(".") && !peek(1).isSymbol("(") && canStartOperandAt(1)) {
            next();
            return new Expr.Unary("PRIOR", parseUnary());
        }
        if (at("CONNECT_BY_ROOT") && canStartOperandAt(1)) {
            next();
            return new Expr.Unary("CONNECT_BY_ROOT", parseUnary());
        }
        Expr e = parsePrimary();
        while (true) {
            if (atSymbol("::")) {
                // PostgreSQL 캐스트 expr::type: 식이 낀 것으로 본다(CAST와 같게 다룬다, F-006-R18)
                next();
                castType();
                e = new Expr.FuncCall(QName.of("CAST"), List.of(e), List.of());
            } else if (subscriptAhead(e)) {
                e = subscript(e);
            } else {
                break;
            }
        }
        while (at("AT") && (peek(1).is("LOCAL") || (peek(1).is("TIME") && peek(2).is("ZONE")))) {
            next();
            if (accept("LOCAL")) {
                continue;
            }
            next();
            next();
            parsePrimary();
        }
        return e;
    }

    /**
     * 현재 위치가 방금 읽은 식 e의 PostgreSQL 첨자({@code a[1]}, {@code a[1:2]}, {@code (ARRAY[..])[i]})인가(F-019-R15).
     * PostgreSQL 읽기 방식이면 대괄호가 오면 첨자다. 그 밖의 읽기 방식에서는 대괄호 안이 수나 바인드로 시작할 때만 첨자다:
     * MSSQL은 대괄호로 이름을 감싸므로({@code SELECT a [별칭]}) 이름이 든 대괄호를 첨자로 읽으면 그 이름이 컬럼으로 둔갑한다.
     */
    private boolean subscriptAhead(Expr e) {
        if (!atSymbol("[") || e instanceof Expr.Missing || e instanceof Expr.Unparsed || e instanceof Expr.Star) {
            return false;
        }
        return postgres() || peek(1).type() == TokenType.NUMBER || peek(1).type() == TokenType.BIND;
    }

    /** {@code [식]}, {@code [식:식]}을 읽어 첨자 접근(op {@code []})으로 만든다. 첨자 식 안의 컬럼은 읽은 것으로 남는다. */
    private Expr subscript(Expr base) {
        next(); // [
        Expr e = base;
        while (!atEnd() && !atSymbol("]")) {
            int before = pos;
            if (acceptSymbol(":")) {
                continue; // 구간 a[1 : 2], a[:2]
            }
            if (peek().type() == TokenType.BIND && peek().text().length() > 1 && peek().text().charAt(0) == ':'
                    && Character.isDigit(peek().text().charAt(1))) {
                next(); // 구간의 위쪽 끝이 붙은 꼴 a[1:2] — 렉서가 :2를 바인드로 낸다
                continue;
            }
            e = new Expr.Binary("[]", e, parseExpression());
            if (pos == before) {
                break;
            }
        }
        if (!acceptSymbol("]")) {
            problem("닫는 대괄호가 없음", peek(), false);
        }
        return e;
    }

    /** PostgreSQL 배열 생성자 {@code ARRAY[식, ..]}(중첩 {@code [[1, 2], [3, 4]]} 포함). 현재 토큰은 {@code [}다. */
    private Expr arrayConstructor() {
        next(); // [
        List<Expr> items = new ArrayList<>();
        if (++depth > MAX_DEPTH) {
            depth--;
            problem("중첩이 너무 깊어 건너뜀", peek(), false);
            return new Expr.Unparsed("...");
        }
        try {
            while (!atEnd() && !atSymbol("]")) {
                int before = pos;
                items.add(atSymbol("[") ? arrayConstructor() : parseExpression());
                if (!acceptSymbol(",") || pos == before) {
                    break;
                }
            }
        } finally {
            depth--;
        }
        if (!acceptSymbol("]")) {
            problem("닫는 대괄호가 없음", peek(), false);
        }
        return new Expr.Tuple(items);
    }

    /**
     * PostgreSQL 시퀀스 함수의 첫 인자로 온 문자열 리터럴({@code 'seq'}, {@code 'public.seq'}, {@code '"Seq"'})에서 시퀀스 이름을 읽는다.
     * 이름 꼴이 아니면 null. 문자열 안의 이름은 PostgreSQL이 읽는 이름이므로 PostgreSQL 읽기 방식의 렉서와 같은 규칙을 쓴다:
     * 대문자가 없는 따옴표 이름({@code '"orders_id_seq"'})은 따옴표 없는 이름과 같다({@link Lexer#foldedQuotedName}).
     */
    public static QName sequenceName(Token string) {
        String text = string.text();
        if (text.length() < 3 || text.charAt(0) != '\'' || text.charAt(text.length() - 1) != '\'') {
            return null;
        }
        String inner = text.substring(1, text.length() - 1).trim();
        if (!SEQUENCE_NAME.matcher(inner).matches()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        java.util.regex.Matcher m = NAME_PART.matcher(inner);
        while (m.find()) {
            String part = m.group();
            String folded = part.startsWith("\"") ? Lexer.foldedQuotedName(part.substring(1, part.length() - 1)) : null;
            parts.add(folded == null ? part : "\"" + folded + "\"");
        }
        return new QName(parts, null, Pos.of(string));
    }

    private static final String NAME_PART_PATTERN = "(?:\"[^\"]+\"|[\\p{L}_][\\p{L}\\p{N}_$]*)";
    private static final java.util.regex.Pattern NAME_PART = java.util.regex.Pattern.compile(NAME_PART_PATTERN);
    private static final java.util.regex.Pattern SEQUENCE_NAME = java.util.regex.Pattern.compile(
            NAME_PART_PATTERN + "(?:\\." + NAME_PART_PATTERN + ")*");

    /** {@code ::} 뒤의 타입 이름을 건너뛴다: {@code numeric(10,2)}, {@code character varying}, {@code timestamp without time zone}, {@code varchar[]}. */
    private void castType() {
        if (!peek().isIdentifier()) {
            return;
        }
        Token first = next();
        while (atSymbol(".") && peek(1).isIdentifier()) {
            next();
            next();
        }
        if (first.is("DOUBLE")) {
            accept("PRECISION");
        } else if (first.isAny("CHARACTER", "CHAR", "BIT", "NATIONAL")) {
            acceptAny("CHARACTER", "CHAR");
            accept("VARYING");
        }
        if (atSymbol("(")) {
            skipBalanced();
        }
        if (atAny("WITH", "WITHOUT") && peek(1).is("TIME") && peek(2).is("ZONE")) {
            next();
            next();
            next();
        }
        while (atSymbol("[")) {
            next();
            if (peek().type() == TokenType.NUMBER) {
                next();
            }
            acceptSymbol("]");
        }
    }

    private boolean canStartOperandAt(int ahead) {
        Token t = peek(ahead);
        return t.isIdentifier() && !(t.type() == TokenType.IDENT && Keywords.EXPR_STOP.contains(t.upper()));
    }

    private Expr parsePrimary() {
        Token t = peek();
        switch (t.type()) {
            case NUMBER:
            case STRING:
                next();
                // 이어 붙은 문자열 리터럴
                while (t.type() == TokenType.STRING && peek().type() == TokenType.STRING) {
                    next();
                }
                return new Expr.Literal(t.text());
            case BIND:
                next();
                while (atSymbol(".") && peek(1).isIdentifier()) {
                    next();
                    next();
                }
                return new Expr.Bind(t.text());
            case QUOTED_IDENT:
                return identifierExpr();
            case IDENT:
                if (Keywords.EXPR_STOP.contains(t.upper()) && !peek(1).isSymbol(".")
                        && !(Keywords.FUNCTION_KEYWORDS.contains(t.upper()) && peek(1).isSymbol("("))
                        && !(valuesFunction && t.is("VALUES") && peek(1).isSymbol("(")) && !softKeywordIsName()) {
                    problem("식이 빠짐: " + t.text() + " 앞", t, true);
                    return new Expr.Missing();
                }
                return identifierExpr();
            case SYMBOL:
                if (t.isSymbol("(")) {
                    return parenExpr();
                }
                if (t.isSymbol("@") && (peek(1).isIdentifier() || peek(1).isSymbol("@")) && peek(1).offset() == t.endOffset()) {
                    // MySQL 사용자 변수 @v, 시스템 변수 @@v, 대입 @v := 식(F-006-R36): 컬럼도 테이블도 아니다. 대입이면 값 식을 읽는다
                    next();
                    acceptSymbol("@");
                    if (peek().isIdentifier()) {
                        next();
                    }
                    while (atSymbol(".") && peek(1).isIdentifier()) {
                        next();
                        next();
                    }
                    if (acceptSymbol(":=")) {
                        return parseAdditive();
                    }
                    return new Expr.Bind("@");
                }
                if (t.isSymbol("*")) {
                    next();
                    return new Expr.Star(null);
                }
                if (t.isSymbol(")") || t.isSymbol(",") || t.isSymbol(";") || t.isSymbol("=") || t.isSymbol("<>")
                        || t.isSymbol("<") || t.isSymbol(">") || t.isSymbol("<=") || t.isSymbol(">=") || t.isSymbol("!=")
                        || t.isSymbol("||") || t.isSymbol("}")) {
                    problem("식이 빠짐: " + t.text() + " 앞", t, true);
                    return new Expr.Missing();
                }
                problem("식을 해석하지 못함: " + t.text(), t, false);
                return new Expr.Unparsed(t.text());
            default:
                problem("식이 빠짐: 끝", t, true);
                return new Expr.Missing();
        }
    }

    /**
     * 식 자리의 현재 토큰이 절 키워드로도 쓰이는 비예약 낱말인데 여기서는 이름(컬럼)으로 쓰였는가(F-006-R33).
     * {@code SELECT LIMIT FROM ACCT}, {@code WHERE OFFSET > 0}, {@code ORDER BY WINDOW}처럼 뒤 토큰이 그 절의 모양이 아니면 이름이다.
     * 절의 모양({@code LIMIT 10}, {@code OFFSET ? ROWS}, {@code ESCAPE '\'}, {@code BULK COLLECT}, {@code WINDOW w AS}, {@code MERGE INTO})이면
     * 피연산자가 빠진 깨진 구문이므로 전처럼 절로 둔다.
     */
    private boolean softKeywordIsName() {
        Token n = peek(1);
        switch (peek().upper()) {
            case "LIMIT":
            case "OFFSET":
                return !(n.type() == TokenType.NUMBER || n.type() == TokenType.BIND || n.isDynamic());
            case "ESCAPE":
                return !(n.type() == TokenType.STRING || n.type() == TokenType.BIND);
            case "BULK":
                return !n.is("COLLECT");
            case "WINDOW":
                return !(n.isIdentifier() && !n.is("AS") && peek(2).is("AS"));
            case "MERGE":
                return !n.is("INTO") && endsOperand(n);
            case "QUALIFY":
            case "LOOP":
                return endsOperand(n);
            default:
                return false;
        }
    }

    /** 피연산자 하나가 끝난 바로 뒤에 올 수 있는 토큰인가(앞 토큰이 이름으로 쓰였다는 표시). */
    private static boolean endsOperand(Token n) {
        if (n.isEof()) {
            return true;
        }
        if (n.type() == TokenType.SYMBOL) {
            switch (n.text()) {
                case ",": case ")": case ";": case "=": case "<": case ">": case "<=": case ">=": case "<>": case "!=": case "^=":
                case "~=": case "!": case "+": case "-": case "*": case "/": case "||": case "(+)": case "::":
                    return true;
                default:
                    return false;
            }
        }
        return n.isAny("FROM", "AS", "IS", "IN", "LIKE", "BETWEEN", "NOT", "AND", "OR", "ASC", "DESC", "NULLS", "THEN", "ELSE", "END",
                "WHEN", "INTO", "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "MINUS", "INTERSECT", "EXCEPT");
    }

    private Expr parenExpr() {
        if (peek(1).is("SELECT") || (peek(1).is("WITH") && peek(2).isIdentifier())) {
            open();
            Query q = parseQuery();
            close();
            return new Expr.Subquery(q);
        }
        Token start = peek();
        open();
        if (atSymbol(")")) {
            close();
            return new Expr.Tuple(List.of()); // GROUPING SETS의 빈 묶음 ()
        }
        Expr e = parseExpression();
        if (e instanceof Expr.Subquery s && atSetOperator()) {
            QueryBody body = setOperations(new Query.Nested(s.query()));
            Query q = queryTail(List.of(), body, start);
            close();
            return new Expr.Subquery(q);
        }
        if (atSymbol(",")) {
            List<Expr> items = new ArrayList<>();
            items.add(e);
            while (acceptSymbol(",")) {
                if (atSymbol(")") || atEnd()) {
                    problem("쉼표 뒤에 식이 없음", peek(), true);
                    break;
                }
                int before = pos;
                items.add(parseExpression());
                if (pos == before && !atSymbol(",") && !atSymbol(")")) {
                    break;
                }
            }
            close();
            return new Expr.Tuple(items);
        }
        close();
        return e;
    }

    private Expr caseExpr() {
        next(); // CASE
        Expr operand = at("WHEN") ? null : parseExpression();
        List<Expr.WhenClause> whens = new ArrayList<>();
        while (accept("WHEN")) {
            Expr when = parseExpression();
            if (!accept("THEN")) {
                problem("CASE에 THEN이 없음", peek(), false);
            }
            whens.add(new Expr.WhenClause(when, parseExpression()));
        }
        Expr otherwise = accept("ELSE") ? parseExpression() : null;
        if (!accept("END")) {
            problem("CASE에 END가 없음", peek(), false);
        }
        return new Expr.Case(operand, whens, otherwise);
    }

    private Expr identifierExpr() {
        Token t = peek();
        Token n = peek(1);
        if (t.type() == TokenType.IDENT) {
            switch (t.upper()) {
                case "CASE":
                    if (!n.isSymbol(".")) {
                        return caseExpr();
                    }
                    break;
                case "NULL":
                case "TRUE":
                case "FALSE":
                    if (!n.isSymbol(".") && !n.isSymbol("(")) {
                        next();
                        return new Expr.Literal(t.upper());
                    }
                    break;
                case "DATE":
                case "TIMESTAMP":
                case "TIME":
                    if (n.type() == TokenType.STRING) {
                        next();
                        next();
                        return new Expr.Literal(t.upper() + " " + n.text());
                    }
                    break;
                case "INTERVAL":
                    if (n.type() == TokenType.STRING || n.type() == TokenType.NUMBER || n.type() == TokenType.BIND) {
                        next();
                        next();
                        while (peek().type() == TokenType.IDENT && INTERVAL_UNITS.contains(peek().upper())) {
                            next();
                            if (atSymbol("(")) {
                                skipBalanced();
                            }
                            if (!accept("TO")) {
                                break;
                            }
                        }
                        return new Expr.Literal("INTERVAL " + n.text());
                    }
                    if (intervalValueAhead()) {
                        // MySQL INTERVAL 식 단위: INTERVAL -6 MONTH, INTERVAL a.DAYS DAY(F-006-R36). 식 안의 컬럼은 읽은 것으로 남긴다
                        next();
                        Expr amount = parseUnary();
                        if (peek().type() == TokenType.IDENT && INTERVAL_UNITS.contains(peek().upper())) {
                            next();
                        }
                        return amount;
                    }
                    break;
                case "CURRENT":
                    if (n.type() == TokenType.IDENT && Keywords.CURRENT_REGISTERS.contains(n.upper())) {
                        next();
                        next();
                        return new Expr.Literal("CURRENT " + n.upper());
                    }
                    break;
                case "NEXT":
                case "PREVIOUS":
                    if (n.is("VALUE") && peek(2).is("FOR") && peek(3).isIdentifier()) {
                        next();
                        next();
                        next();
                        return new Expr.SequenceRef(qualifiedName(), t.is("NEXT") ? "NEXTVAL" : "CURRVAL");
                    }
                    break;
                case "NEXTVAL":
                case "PREVVAL":
                    if (n.is("FOR") && peek(2).isIdentifier()) {
                        next();
                        next();
                        return new Expr.SequenceRef(qualifiedName(), t.is("NEXTVAL") ? "NEXTVAL" : "CURRVAL");
                    }
                    break;
                case "EXISTS":
                    if (n.isSymbol("(")) {
                        next();
                        open();
                        Query q = parseQuery();
                        close();
                        return new Expr.Exists(q);
                    }
                    break;
                case "CURSOR":
                case "MULTISET":
                case "ARRAY":
                    if (t.is("ARRAY") && n.isSymbol("[")) {
                        next();
                        return arrayConstructor(); // PostgreSQL ARRAY[..]
                    }
                    if (n.isSymbol("(") && (peek(2).is("SELECT") || peek(2).is("WITH"))) {
                        next();
                        open();
                        Query q = parseQuery();
                        close();
                        return new Expr.Subquery(q);
                    }
                    break;
                case "NEW":
                    // NEW 타입이름(..): 뒤가 IN·IS 같은 키워드면 NEW라는 컬럼이다(NEW IN (1, 2))
                    if (n.type() == TokenType.IDENT && !Keywords.EXPR_STOP.contains(n.upper()) && (peek(2).isSymbol("(") || peek(2).isSymbol("."))) {
                        next();
                    }
                    break;
                default:
                    break;
            }
        }
        Token first = next();
        Token last = first;
        List<String> parts = new ArrayList<>();
        parts.add(first.text());
        while (atSymbol(".")) {
            Token after = peek(1);
            if (after.isSymbol("*")) {
                next();
                next();
                return new Expr.Star(new QName(parts, null, Pos.span(first, last)));
            }
            if (after.isIdentifier()) {
                next();
                last = next();
                parts.add(last.text());
                continue;
            }
            next();
            problem("점 뒤에 이름이 없음", peek(), false);
            break;
        }
        String link = dbLink();
        QName name = new QName(parts, link, Pos.span(first, last));
        if (parts.size() >= 2 && last.isAny("NEXTVAL", "CURRVAL") && !atSymbol("(")) {
            return new Expr.SequenceRef(name.parent(), last.upper());
        }
        if (atSymbol("(")) {
            return functionCall(name);
        }
        boolean outer = acceptSymbol("(+)");
        if (atSymbol("%") && peek(1).type() == TokenType.IDENT) {
            next();
            Token attr = next();
            return new Expr.Literal(name.display() + "%" + attr.upper());
        }
        if (name.anyDynamic()) {
            // ${} 치환이 조건 조각 전체일 수 있다(MyBatis Generator의 "and ${criterion.condition} #{value}"):
            // 뒤따르는 피연산자까지 불투명한 조건 하나로 삼킨다(F-006-R19)
            while (peek().type() == TokenType.BIND || peek().type() == TokenType.STRING || peek().type() == TokenType.NUMBER) {
                next();
            }
        }
        return new Expr.ColumnRef(name, outer);
    }

    /** MySQL {@code INTERVAL 식 단위}의 단위(복합 단위 포함). */
    private static final java.util.Set<String> INTERVAL_UNITS = java.util.Set.of(
            "MICROSECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR", "SECOND_MICROSECOND", "MINUTE_MICROSECOND",
            "MINUTE_SECOND", "HOUR_MICROSECOND", "HOUR_SECOND", "HOUR_MINUTE", "DAY_MICROSECOND", "DAY_SECOND", "DAY_MINUTE", "DAY_HOUR",
            "YEAR_MONTH", "MICROSECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS", "WEEKS", "MONTHS", "YEARS");

    /** 현재 토큰 {@code INTERVAL} 뒤가 {@code [부호]숫자 단위} 또는 {@code 이름[.이름] 단위}인가. */
    private boolean intervalValueAhead() {
        int i = 1;
        if (peek(i).isSymbol("-") || peek(i).isSymbol("+")) {
            // 단위가 따라야 한다: INTERVAL이라는 컬럼의 산술(INTERVAL + 1)과 가린다
            return peek(i + 1).type() == TokenType.NUMBER && peek(i + 2).type() == TokenType.IDENT && INTERVAL_UNITS.contains(peek(i + 2).upper());
        }
        if (peek(i).type() == TokenType.NUMBER) {
            return peek(i + 1).type() == TokenType.IDENT && INTERVAL_UNITS.contains(peek(i + 1).upper());
        }
        if (!peek(i).isIdentifier() || (peek(i).type() == TokenType.IDENT && Keywords.EXPR_STOP.contains(peek(i).upper()))) {
            return false;
        }
        i++;
        while (peek(i).isSymbol(".") && peek(i + 1).isIdentifier()) {
            i += 2;
        }
        return peek(i).type() == TokenType.IDENT && INTERVAL_UNITS.contains(peek(i).upper());
    }

    private Expr functionCall(QName name) {
        String fn = name.size() == 1 ? name.name() : "";
        List<Expr> args = new ArrayList<>();
        List<Expr> extras = new ArrayList<>();
        open();
        switch (fn) {
            case "CAST":
            case "TREAT":
            case "XMLCAST":
                args.add(parseExpression());
                skipToCloseParen();
                break;
            case "EXTRACT":
                if (peek().type() == TokenType.IDENT && peek(1).is("FROM")) {
                    next();
                    next();
                    args.add(parseExpression());
                } else {
                    genericArgs(args, fn);
                }
                break;
            case "XMLELEMENT":
            case "XMLATTRIBUTES":
            case "XMLFOREST":
                // 첫 인자는 요소 이름(식별자)일 수 있다: 컬럼으로 보지 않는다
                accept("NAME");
                if (fn.equals("XMLELEMENT") && peek().isIdentifier() && (peek(1).isSymbol(",") || peek(1).isSymbol(")"))) {
                    next();
                }
                genericArgs(args, fn);
                break;
            case "STRIP":
                // DB2 STRIP(문자열 [, B|L|T|BOTH|LEADING|TRAILING [, 문자]]): 둘째 인자의 키워드는 컬럼이 아니다
                if (!atSymbol(")")) {
                    args.add(parseExpression());
                }
                if (atSymbol(",") && peek(1).isAny("B", "L", "T", "BOTH", "LEADING", "TRAILING") && (peek(2).isSymbol(",") || peek(2).isSymbol(")"))) {
                    next();
                    next();
                }
                if (!atSymbol(")")) {
                    genericArgs(args, fn);
                }
                break;
            case "NEXTVAL":
            case "CURRVAL":
            case "SETVAL": {
                // PostgreSQL nextval('seq')·currval('seq')·setval('seq', ..): 첫 인자가 문자열 리터럴이면 그 시퀀스의 참조다(F-019-R16).
                // 리터럴이 아니거나(이름을 받는 MariaDB의 NEXTVAL(seq), 변수) 이름 꼴이 아니면 보통의 인자로 둔다
                Token first = peek();
                QName sequence = first.type() == TokenType.STRING && !first.unterminated()
                        && (peek(1).isSymbol(")") || peek(1).isSymbol(",") || peek(1).isSymbol("::")) ? sequenceName(first) : null;
                if (sequence != null) {
                    next();
                    while (atSymbol("::")) {
                        next();
                        castType(); // 'seq'::regclass
                    }
                    args.add(new Expr.SequenceRef(sequence, fn));
                }
                genericArgs(args, fn);
                break;
            }
            case "TRIM":
                acceptAny("LEADING", "TRAILING", "BOTH");
                if (!at("FROM") && !atSymbol(")")) {
                    args.add(parseExpression());
                }
                if (accept("FROM")) {
                    args.add(parseExpression());
                }
                if (!atSymbol(")")) {
                    genericArgs(args, fn);
                }
                break;
            default:
                genericArgs(args, fn);
                break;
        }
        close();
        while (true) {
            if (at("WITHIN") && peek(1).is("GROUP") && peek(2).isSymbol("(")) {
                next();
                next();
                open();
                if (acceptSeq("ORDER", "BY")) {
                    orderItems(extras);
                }
                skipToCloseParen();
                close();
            } else if (at("KEEP") && peek(1).isSymbol("(")) {
                next();
                open();
                while (!atEnd() && !at("ORDER") && !atSymbol(")")) {
                    next();
                }
                if (acceptSeq("ORDER", "BY")) {
                    orderItems(extras);
                }
                skipToCloseParen();
                close();
            } else if (at("FILTER") && peek(1).isSymbol("(")) {
                next();
                open();
                accept("WHERE");
                extras.add(parseExpression());
                skipToCloseParen();
                close();
            } else if (atAny("IGNORE", "RESPECT") && peek(1).is("NULLS")) {
                next();
                next();
            } else if (at("FROM") && peek(1).isAny("FIRST", "LAST") && peek(2).isAny("OVER", "IGNORE", "RESPECT")) {
                // NTH_VALUE(x, n) FROM FIRST|LAST [IGNORE NULLS] OVER (..): 이 FROM은 FROM 절이 아니다
                next();
                next();
            } else if (at("OVER") && peek(1).isIdentifier() && !peek(1).isDynamic()
                    && !(peek(1).type() == TokenType.IDENT && Keywords.EXPR_STOP.contains(peek(1).upper()))) {
                // 이름 붙인 윈도 참조: SUM(x) OVER w (WINDOW w AS (..) 절과 짝이다). OVER가 별칭이면 뒤에 이름이 올 수 없다
                next();
                next();
            } else if (at("OVER") && peek(1).isSymbol("(")) {
                next();
                open();
                if (peek().isIdentifier() && !atAny("PARTITION", "ORDER", "ROWS", "RANGE", "GROUPS")) {
                    next();
                }
                if (acceptSeq("PARTITION", "BY")) {
                    do {
                        extras.add(parseExpression());
                    } while (acceptSymbol(","));
                }
                if (at("ORDER") && (peek(1).is("BY") || peek(1).is("SIBLINGS"))) {
                    next();
                    accept("SIBLINGS");
                    accept("BY");
                    orderItems(extras);
                }
                skipToCloseParen();
                close();
            } else {
                break;
            }
        }
        // 메서드 체인: f(x).attr, f(x).m(y)
        while (atSymbol(".") && peek(1).isIdentifier()) {
            next();
            next();
            if (atSymbol("(")) {
                open();
                genericArgs(extras, "");
                close();
            }
        }
        acceptSymbol("(+)");
        return new Expr.FuncCall(name, args, extras);
    }

    /** 함수 인자를 관대하게 읽는다. 닫는 괄호는 소비하지 않는다. */
    private void genericArgs(List<Expr> args, String fn) {
        acceptAny("DISTINCT", "UNIQUE");
        if (at("ALL") && !peek(1).isSymbol(",") && !peek(1).isSymbol(")") && !peek(1).isSymbol(".")) {
            next();
        }
        boolean fromAllowed = Keywords.FROM_FUNCTIONS.contains(fn);
        while (!atEnd() && !atSymbol(")")) {
            int before = pos;
            Token t = peek();
            if (t.isSymbol(",")) {
                next();
                continue;
            }
            if (t.isSymbol(";")) {
                break;
            }
            if (t.type() == TokenType.IDENT && !peek(1).isSymbol(".")) {
                String u = t.upper();
                if (Keywords.ARG_BREAK.contains(u) || ((u.equals("FROM") || u.equals("FOR")) && !fromAllowed)) {
                    break;
                }
                if (peek(1).isSymbol("=>")) {
                    next();
                    next();
                    continue;
                }
                if (u.equals("AS")) {
                    next();
                    skipArgRest();
                    continue;
                }
                if ((u.equals("ON") || u.equals("RETURNING")) && !peek(1).isSymbol("(")) {
                    // 인자 끝의 꼬리 절: LISTAGG(.. ON OVERFLOW TRUNCATE ..), JSON_VALUE(.. RETURNING NUMBER DEFAULT 0 ON ERROR), NULL ON NULL
                    next();
                    skipArgRest();
                    continue;
                }
                if (u.equals("USING") && peek(1).type() == TokenType.IDENT && peek(2).isSymbol(")")) {
                    // 문자셋 이름: CONVERT(x USING utf8), TRANSLATE(x USING NCHAR_CS)
                    next();
                    next();
                    continue;
                }
                if (u.equals("COLUMNS") && !endsOperand(peek(1))) {
                    // JSON_TABLE·XMLTABLE의 COLUMNS 절: 결과 컬럼의 정의(이름 타입 PATH ..)다. 함수의 닫는 괄호 앞까지 건너뛴다
                    skipToCloseParen();
                    break;
                }
                // 인자 사이에 끼는 키워드(PASSING x, SEPARATOR ',', CONTENT x). 바로 뒤에서 피연산자가 끝나면 그 이름의 컬럼이다(MAX(CONTENT))
                boolean argKeyword = Keywords.ARG_KEYWORDS.contains(u) && !peek(1).isSymbol("(")
                        && (Keywords.EXPR_STOP.contains(u) || !endsOperand(peek(1)));
                if (u.equals("FROM") || u.equals("FOR") || argKeyword) {
                    next();
                    continue;
                }
                if (u.equals("ORDER") && peek(1).is("BY")) {
                    next();
                    next();
                    continue;
                }
                if ((u.equals("SELECT") || (u.equals("WITH") && peek(1).isIdentifier()))) {
                    args.add(new Expr.Subquery(parseQuery()));
                    continue;
                }
                if (u.equals("KEY") && fn.startsWith("JSON_OBJECT") && !endsOperand(peek(1))) {
                    next(); // JSON_OBJECT(KEY 'k' VALUE v): KEY는 키 식 앞의 키워드다
                    continue;
                }
                if (u.equals("NAME") && peek(1).isIdentifier() && !peek(1).isSymbol(",")) {
                    next();
                    next();
                    continue;
                }
            }
            if (t.isSymbol("*")) {
                next();
                args.add(new Expr.Star(null));
                continue;
            }
            Expr e = parseExpression();
            args.add(e);
            acceptAny("ASC", "DESC");
            if (at("NULLS") && peek(1).isAny("FIRST", "LAST")) {
                next();
                next();
            }
            if (atAny("IGNORE", "RESPECT") && peek(1).is("NULLS")) {
                next();
                next();
            }
            if (pos != before && at("VALUE") && fn.startsWith("JSON_OBJECT") && !peek(1).isSymbol(",") && !peek(1).isSymbol(")")) {
                next(); // JSON_OBJECT('k' VALUE v): 값 식이 따른다
                continue;
            }
            if (pos == before) {
                // 식으로 읽지 못한 토큰: 이 인자의 나머지를 건너뛴다
                next();
                skipArgRest();
            } else if (!atSymbol(",") && !atSymbol(")") && !atEnd() && !isArgContinuation(fromAllowed)) {
                skipArgRest();
            }
        }
    }

    private boolean isArgContinuation(boolean fromAllowed) {
        Token t = peek();
        if (t.type() != TokenType.IDENT) {
            return t.isSymbol(";");
        }
        String u = t.upper();
        return Keywords.ARG_BREAK.contains(u) || u.equals("FROM") || u.equals("FOR") || u.equals("AS")
                || Keywords.ARG_KEYWORDS.contains(u) || (u.equals("ORDER") && peek(1).is("BY"));
    }

    /** 현재 인자의 나머지를 다음 쉼표나 닫는 괄호 앞까지 건너뛴다. */
    private void skipArgRest() {
        int d = 0;
        while (!atEnd()) {
            Token t = peek();
            if (t.isSymbol("(")) {
                d++;
            } else if (t.isSymbol(")")) {
                if (d == 0) {
                    return;
                }
                d--;
            } else if (d == 0 && (t.isSymbol(",") || t.isSymbol(";")
                    || (t.type() == TokenType.IDENT && Keywords.ARG_BREAK.contains(t.upper())))) {
                return;
            } else if (d == 0 && t.is("COLUMNS") && !endsOperand(peek(1))) {
                return; // XMLTABLE(.. PASSING x AS "d" COLUMNS ..): COLUMNS 절은 따로 건너뛴다
            }
            next();
        }
    }
}
