package io.codeatlas.sql.parser;

import io.codeatlas.sql.ast.Ddl;
import io.codeatlas.sql.ast.Expr;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.ast.Query;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Oracle·DB2·PostgreSQL DDL 파서: CREATE TABLE/INDEX/VIEW/SEQUENCE/SYNONYM/ALIAS, ALTER TABLE, COMMENT ON.
 * 스토리지·파티션 같은 물리 절은 건너뛴다. 어떤 입력에도 예외를 던지지 않는다.
 * PostgreSQL의 DDL 표기는 다른 방언의 표기와 겹치지 않아 읽기 방식과 무관하게 읽는다(ADR-0012). 읽기 방식으로 가르는 것은
 * {@code CREATE INDEX CONCURRENTLY ON ..}의 {@code CONCURRENTLY}뿐이다.
 *
 * <p>사용: {@code DdlParser.isDdl(tokens)}로 확인한 뒤 {@code new DdlParser(tokens).parse()}.
 * 결과의 구문은 {@link Ddl} 구현, 조용히 건너뛸 구문이면 {@link Statement.Other}, 모르는 구문이면 {@link Statement.Unparsed}다.
 */
public final class DdlParser extends TokenCursor {

    /** 정보가 없어 조용히 건너뛰는 CREATE 대상. */
    private static final Set<String> SILENT_CREATE = Set.of(
            "USER", "ROLE", "TABLESPACE", "SCHEMA", "DATABASE", "DIRECTORY", "BUFFERPOOL", "CONTEXT", "PROFILE",
            "TYPE", "LIBRARY", "STOGROUP", "NICKNAME", "VARIABLE", "MASK", "PERMISSION", "CLUSTER", "DIMENSION",
            "OPERATOR", "OUTLINE", "PFILE", "SPFILE", "ROLLBACK", "UNDO", "TEMPORARY", "BIGFILE", "SMALLFILE",
            "SERVER", "WRAPPER", "AUDIT", "DISTINCT", "MODULE", "EVENT", "WORKLOAD", "THRESHOLD", "JAVA", "EDITION",
            "RESTORE", "FLASHBACK", "CONTROLFILE", "DISKGROUP", "LOCKDOWN", "PLUGGABLE", "REGULAR", "LARGE", "SYSTEM",
            // PostgreSQL(pg_dump의 부속 구문, F-019-R22)
            "EXTENSION", "DOMAIN", "COLLATION");

    /** 자동 증가 컬럼을 뜻하는 타입(F-019-R19): 이 타입의 컬럼은 identity다. */
    private static final Set<String> SERIAL_TYPES = Set.of("SMALLSERIAL", "SERIAL", "BIGSERIAL", "SERIAL2", "SERIAL4", "SERIAL8");

    private static final Set<String> COLUMN_OPTION_START = Set.of(
            "NOT", "NULL", "DEFAULT", "CONSTRAINT", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "GENERATED", "WITH",
            "AS", "ENABLE", "DISABLE", "IDENTITY", "VISIBLE", "INVISIBLE", "IMPLICITLY", "ENCRYPT", "SORT", "COLLATE");

    private static final Set<String> ALTER_ACTIONS = Set.of("ADD", "MODIFY", "ALTER", "DROP", "RENAME");

    /** 이 구문이 든 텍스트의 읽기 방식(ADR-0012). 안에서 만드는 구문 파서에도 그대로 넘긴다. */
    private final Syntax syntax;

    /** 기본 읽기 방식으로 읽는다. */
    public DdlParser(List<Token> tokens) {
        this(tokens, Syntax.DEFAULT);
    }

    /** @param syntax 읽기 방식. 토큰을 만든 렉서와 같은 값이어야 한다. null이면 기본이다 */
    public DdlParser(List<Token> tokens, Syntax syntax) {
        super(tokens);
        this.syntax = syntax == null ? Syntax.DEFAULT : syntax;
    }

    /** 이 파서의 읽기 방식. */
    public Syntax syntax() {
        return syntax;
    }

    /** DDL 파서가 맡는 구문인가: CREATE(루틴 제외), ALTER, COMMENT ON, DECLARE GLOBAL TEMPORARY TABLE. */
    public static boolean isDdl(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return false;
        }
        Token first = tokens.get(0);
        if (first.is("CREATE") || first.is("ALTER")) {
            return true;
        }
        if (first.is("COMMENT")) {
            return tokens.size() > 1 && tokens.get(1).is("ON");
        }
        return first.is("DECLARE") && tokens.size() > 1 && tokens.get(1).is("GLOBAL");
    }

    public ParseResult parse() {
        Token first = peek();
        Statement statement;
        if (accept("CREATE")) {
            statement = create();
        } else if (accept("ALTER")) {
            statement = accept("TABLE") && skipAlterModifiers() && peek().isIdentifier() ? alterTable() : new Statement.Other("ALTER", false);
        } else if (at("COMMENT") && peek(1).is("ON")) {
            next();
            next();
            statement = commentOn();
        } else if (atSeq("DECLARE", "GLOBAL", "TEMPORARY", "TABLE")) {
            pos += 4;
            statement = createTable(true);
        } else {
            statement = new Statement.Unparsed(first.upper());
        }
        Token last = tokens.size() >= 2 ? tokens.get(tokens.size() - 2) : first;
        return new ParseResult(statement, problems, List.of(), Pos.span(first, last));
    }

    /**
     * {@code ALTER TABLE [IF EXISTS] [ONLY] 이름}의 수식어를 넘긴다(F-019-R18). 바로 뒤가 테이블 이름이 될 수 없으면
     * ({@code ALTER TABLE ONLY ADD ..}) 그 낱말이 테이블 이름이다: 그대로 둔다.
     *
     * @return 항상 true(조건식에 이어 쓰려고)
     */
    private boolean skipAlterModifiers() {
        if (atSeq("IF", "EXISTS") && startsTableName(peek(2))) {
            pos += 2;
        }
        if (at("ONLY") && startsTableName(peek(1))) {
            next();
        }
        return true;
    }

    private static boolean startsTableName(Token t) {
        return t.isIdentifier() && !(t.type() == TokenType.IDENT && ALTER_ACTIONS.contains(t.upper()));
    }

    private Statement create() {
        if (acceptSeq("OR", "REPLACE")) {
            // 다음으로
        }
        boolean unique = false;
        boolean isPublic = false;
        boolean materialized = false;
        boolean temporary = false;
        while (true) {
            if (atAny("FORCE", "EDITIONABLE", "NONEDITIONABLE", "EDITIONING", "BITMAP", "SHARDED", "DUPLICATED",
                    "IMMUTABLE", "BLOCKCHAIN", "SUMMARY")) {
                next();
            } else if (at("NO") && peek(1).is("FORCE")) {
                next();
                next();
            } else if (at("UNIQUE")) {
                next();
                unique = true;
            } else if (at("PUBLIC") && peek(1).isAny("SYNONYM", "ALIAS")) {
                next();
                isPublic = true;
            } else if (at("MATERIALIZED") && peek(1).is("VIEW")) {
                next();
                materialized = true;
            } else if (atAny("GLOBAL", "PRIVATE", "LOCAL") && peek(1).isAny("TEMPORARY", "TEMP")) {
                next();
            } else if (atAny("TEMPORARY", "TEMP") && peek(1).is("TABLE")) {
                next();
                temporary = true;
            } else if (at("UNLOGGED") && peek(1).isAny("TABLE", "SEQUENCE")) {
                next(); // PostgreSQL: 로그를 남기지 않는 테이블. 임시 테이블이 아니다(F-019-R19)
            } else if (atAny("MEMORY", "CACHED", "TEXT") && peek(1).is("TABLE")) {
                next(); // HSQL: CREATE MEMORY|CACHED|TEXT TABLE (F-007-R17)
            } else {
                break;
            }
        }
        if (accept("TABLE")) {
            return createTable(temporary);
        }
        if (accept("INDEX")) {
            return createIndex(unique);
        }
        if (accept("VIEW")) {
            return createView(materialized);
        }
        if (accept("SEQUENCE")) {
            if (atSeq("IF", "NOT", "EXISTS") && peek(3).isIdentifier()) {
                pos += 3;
            }
            if (!peek().isIdentifier()) {
                return new Statement.Unparsed("CREATE SEQUENCE");
            }
            QName name = name();
            pos = tokens.size() - 1;
            return new Ddl.CreateSequence(name);
        }
        if (atAny("SYNONYM", "ALIAS")) {
            next();
            return createSynonym(isPublic);
        }
        String keyword = peek().type() == TokenType.IDENT ? peek().upper() : peek().text();
        boolean silent = SILENT_CREATE.contains(keyword) || (keyword.equals("PUBLIC") && peek(1).is("DATABASE"));
        pos = tokens.size() - 1;
        return silent ? new Statement.Other("CREATE " + keyword, false) : new Statement.Unparsed("CREATE " + keyword);
    }

    private QName name() {
        SqlParser sub = sub();
        QName name = sub.qualifiedName();
        pos = sub.position();
        return name;
    }

    private SqlParser sub() {
        SqlParser sub = new SqlParser(tokens, syntax);
        sub.position(pos);
        return sub;
    }

    private void adopt(SqlParser sub) {
        pos = sub.position();
        problems.addAll(sub.problems());
    }

    // ------------------------------------------------------------------ CREATE TABLE

    private Statement createTable(boolean temporary) {
        if (atSeq("IF", "NOT", "EXISTS")) {
            pos += 3;
        }
        if (!peek().isIdentifier()) {
            problem("테이블 이름이 없음", peek(), false);
            pos = tokens.size() - 1;
            return new Statement.Unparsed("CREATE TABLE");
        }
        QName name = name();
        List<Ddl.ColumnDef> columns = new ArrayList<>();
        List<Ddl.Constraint> constraints = new ArrayList<>();
        List<Ddl.ColumnSource> sources = new ArrayList<>();
        Query asQuery = null;
        if (atSeq("PARTITION", "OF") && peek(2).isIdentifier()) {
            // PostgreSQL: CREATE TABLE p PARTITION OF 부모 [(컬럼 옵션·제약, ..)] FOR VALUES .. | DEFAULT (F-019-R20).
            // 뒤의 괄호에 적힌 것은 부모 컬럼의 옵션과 제약이다: 컬럼 정의처럼 읽어 부모에게서 온 컬럼 위에 얹는다
            Token start = next();
            next();
            sources.add(new Ddl.ColumnSource(Ddl.ColumnSource.Kind.PARTITION_OF, name(), 0, true, false, true, Pos.of(start)));
        }
        if (atSymbol("(") && !(peek(1).is("SELECT") || peek(1).is("WITH"))) {
            open();
            elementList(columns, constraints, sources);
            close();
        }
        while (!atEnd()) {
            if (at("AS") && (peek(1).is("SELECT") || peek(1).is("WITH") || (peek(1).isSymbol("(") && lookaheadIsQuery(1)))) {
                next();
                SqlParser sub = sub();
                asQuery = sub.parseQuery();
                adopt(sub);
                break;
            }
            if (at("INHERITS") && peek(1).isSymbol("(")) {
                // PostgreSQL: (..) INHERITS (부모, ..)
                next();
                open();
                while (peek().isIdentifier()) {
                    QName parent = name();
                    sources.add(new Ddl.ColumnSource(Ddl.ColumnSource.Kind.INHERITS, parent, 0, true, false, false, parent.pos()));
                    if (!acceptSymbol(",")) {
                        break;
                    }
                }
                skipToCloseParen();
                close();
                continue;
            }
            if (atSymbol("(")) {
                skipBalanced();
            } else {
                next();
            }
        }
        pos = tokens.size() - 1;
        return new Ddl.CreateTable(name, columns, constraints, asQuery, temporary, sources);
    }

    private boolean lookaheadIsQuery(int from) {
        int i = from;
        while (peek(i).isSymbol("(")) {
            i++;
        }
        return peek(i).is("SELECT") || peek(i).is("WITH");
    }

    /**
     * 괄호 안의 컬럼 정의와 테이블 수준 제약 목록. 닫는 괄호는 소비하지 않는다.
     *
     * @param sources {@code LIKE 원본 [INCLUDING ..]} 항목을 담을 곳(F-019-R20). null이면 그 항목을 읽지 않고 건너뛴다(ALTER TABLE .. ADD (..))
     */
    private void elementList(List<Ddl.ColumnDef> columns, List<Ddl.Constraint> constraints, List<Ddl.ColumnSource> sources) {
        while (!atEnd() && !atSymbol(")")) {
            int before = pos;
            if (sources != null && at("LIKE") && peek(1).isIdentifier()) {
                sources.add(likeClause(columns.size()));
            } else if (atIndexClause()) {
                constraints.add(indexClause());
            } else if (atTableConstraint()) {
                Ddl.Constraint c = tableConstraint();
                if (c != null) {
                    constraints.add(c);
                }
            } else if (peek().isIdentifier() && !atAny("PERIOD", "SUPPLEMENTAL", "LIKE")) {
                columns.add(columnDef(false));
            }
            skipToElementEnd();
            if (!acceptSymbol(",")) {
                break;
            }
            if (pos == before) {
                next();
            }
        }
    }

    /**
     * {@code LIKE 원본 [INCLUDING|EXCLUDING 항목]..}: 원본의 컬럼을 이 자리에 가져온다. 기본값·기본 키·identity는 그 항목(또는 {@code ALL})을
     * {@code INCLUDING}으로 적었을 때만 따라온다(뒤에 적은 것이 앞의 것을 덮는다).
     *
     * @param position 이 절 앞에 적힌 자기 컬럼의 수
     */
    private Ddl.ColumnSource likeClause(int position) {
        Token start = next(); // LIKE
        QName table = name();
        boolean defaults = false;
        boolean keys = false;
        boolean identity = false;
        while (atAny("INCLUDING", "EXCLUDING") && peek(1).isIdentifier()) {
            boolean including = next().is("INCLUDING");
            Token what = next();
            boolean all = what.is("ALL");
            if (all || what.is("DEFAULTS")) {
                defaults = including;
            }
            if (all || what.is("INDEXES")) {
                keys = including;
            }
            if (all || what.is("IDENTITY")) {
                identity = including;
            }
        }
        return new Ddl.ColumnSource(Ddl.ColumnSource.Kind.LIKE, table, position, defaults, keys, identity, Pos.of(start));
    }

    /** 다음 쉼표나 (현재 괄호의) 닫는 괄호 앞까지 건너뛴다. */
    private void skipToElementEnd() {
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
            } else if (depth == 0 && t.isSymbol(",")) {
                return;
            }
            next();
        }
    }

    private boolean atTableConstraint() {
        if (at("CONSTRAINT")) {
            return true;
        }
        if (at("PRIMARY") && peek(1).is("KEY")) {
            return true;
        }
        if (at("FOREIGN") && peek(1).is("KEY")) {
            return true;
        }
        if (at("UNIQUE") && (peek(1).isSymbol("(") || peek(1).isAny("KEY", "INDEX") || atUniqueNulls(1))) {
            return true;
        }
        return at("CHECK") && peek(1).isSymbol("(");
    }

    /** ahead부터 {@code NULLS [NOT] DISTINCT (}인가(PostgreSQL 유일 제약의 수식어). */
    private boolean atUniqueNulls(int ahead) {
        if (!peek(ahead).is("NULLS")) {
            return false;
        }
        int distinct = peek(ahead + 1).is("NOT") ? ahead + 2 : ahead + 1;
        return peek(distinct).is("DISTINCT") && peek(distinct + 1).isSymbol("(");
    }

    /**
     * MySQL의 테이블 수준 인덱스 절인가: {@code KEY|INDEX [이름] [USING ..] (컬럼, ..)}, {@code FULLTEXT|SPATIAL KEY ..}(F-007-R16).
     * {@code KEY VARCHAR(10)}처럼 KEY·INDEX라는 이름의 컬럼(괄호 안이 숫자인 타입)과 가린다.
     */
    private boolean atIndexClause() {
        if (atAny("FULLTEXT", "SPATIAL") && peek(1).isAny("KEY", "INDEX")) {
            return true;
        }
        if (!atAny("KEY", "INDEX")) {
            return false;
        }
        Token a = peek(1);
        Token b = peek(2);
        if (a.isSymbol("(")) {
            return b.isIdentifier();
        }
        if (!a.isIdentifier()) {
            return false;
        }
        if (b.is("USING")) {
            return true;
        }
        return b.isSymbol("(") && peek(3).isIdentifier() && !peek(3).is("MAX");
    }

    /** 인덱스 절 하나. 보통 인덱스는 kind {@code INDEX}, 전문·공간 인덱스는 기록하지 않는 {@code OTHER}다. */
    private Ddl.Constraint indexClause() {
        Token start = peek();
        boolean plain = !atAny("FULLTEXT", "SPATIAL");
        if (!plain) {
            next();
        }
        next(); // KEY | INDEX
        String name = null;
        if (peek().isIdentifier() && !at("USING")) {
            name = next().text();
        }
        if (accept("USING") && peek().isIdentifier()) {
            next();
        }
        List<String> cols = atSymbol("(") ? nameList() : List.of();
        return new Ddl.Constraint(plain ? "INDEX" : "OTHER", name, cols, null, List.of(), null, Pos.of(start));
    }

    private Ddl.Constraint tableConstraint() {
        Token start = peek();
        String constraintName = null;
        if (accept("CONSTRAINT")) {
            if (peek().isIdentifier() && !atAny("PRIMARY", "UNIQUE", "FOREIGN", "CHECK")) {
                constraintName = next().text();
            }
        }
        if (acceptSeq("PRIMARY", "KEY")) {
            List<String> cols = atSymbol("(") ? nameList() : List.of();
            return new Ddl.Constraint("PRIMARY KEY", constraintName, cols, null, List.of(), null, Pos.of(start));
        }
        if (accept("UNIQUE")) {
            acceptAny("KEY", "INDEX");
            if (atUniqueNulls(0)) {
                pos += peek(1).is("NOT") ? 3 : 2; // PostgreSQL: UNIQUE NULLS [NOT] DISTINCT (cols)
            }
            if (peek().isIdentifier() && !at("USING")) {
                Token n = next(); // MySQL: UNIQUE KEY 이름 (cols)
                if (constraintName == null) {
                    constraintName = n.text();
                }
            }
            if (accept("USING") && peek().isIdentifier()) {
                next();
            }
            List<String> cols = atSymbol("(") ? nameList() : List.of();
            return new Ddl.Constraint("UNIQUE", constraintName, cols, null, List.of(), null, Pos.of(start));
        }
        if (acceptSeq("FOREIGN", "KEY")) {
            if (peek().isIdentifier() && !atSymbol("(")) {
                Token n = next(); // DB2: FOREIGN KEY 이름 (cols)
                if (constraintName == null) {
                    constraintName = n.text();
                }
            }
            List<String> cols = atSymbol("(") ? nameList() : List.of();
            return references(constraintName, cols, start);
        }
        if (accept("CHECK")) {
            skipBalanced();
            return new Ddl.Constraint("CHECK", constraintName, List.of(), null, List.of(), null, Pos.of(start));
        }
        return null;
    }

    private Ddl.Constraint references(String constraintName, List<String> columns, Token start) {
        if (!accept("REFERENCES") || !peek().isIdentifier()) {
            problem("FOREIGN KEY에 REFERENCES가 없음", peek(), false);
            return null;
        }
        QName ref = name();
        List<String> refColumns = atSymbol("(") ? nameList() : List.of();
        String onDelete = null;
        while (true) {
            if (at("MATCH") && peek(1).isAny("FULL", "PARTIAL", "SIMPLE")) {
                pos += 2; // 받아 넘긴다(F-019-R18). 뒤의 DEFERRABLE·INITIALLY ..·NOT VALID는 부른 쪽이 넘긴다
                continue;
            }
            if (!(at("ON") && peek(1).isAny("DELETE", "UPDATE"))) {
                break;
            }
            next();
            boolean delete = next().is("DELETE");
            String action = null;
            if (accept("CASCADE")) {
                action = "CASCADE";
            } else if (acceptSeq("SET", "NULL")) {
                action = "SET NULL";
            } else if (acceptSeq("SET", "DEFAULT")) {
                action = "SET DEFAULT";
            } else if (accept("RESTRICT")) {
                action = "RESTRICT";
            } else if (acceptSeq("NO", "ACTION")) {
                action = "NO ACTION";
            }
            if (delete) {
                onDelete = action;
            }
        }
        return new Ddl.Constraint("FOREIGN KEY", constraintName, columns, ref, refColumns, onDelete, Pos.of(start));
    }

    /** {@code (a [ASC|DESC], b, ..)} 의 이름(원문). */
    private List<String> nameList() {
        List<String> list = new ArrayList<>();
        open();
        while (!atEnd() && !atSymbol(")")) {
            if (peek().isIdentifier()) {
                list.add(next().text());
            }
            skipToElementEnd();
            if (!acceptSymbol(",")) {
                break;
            }
        }
        close();
        return list;
    }

    /**
     * 컬럼 정의 하나. 쉼표나 닫는 괄호 앞에서 멈춘다.
     *
     * @param alterContext true면 ALTER TABLE의 다음 동작 키워드(ADD, MODIFY, ALTER, DROP, RENAME)에서도 멈춘다
     */
    private Ddl.ColumnDef columnDef(boolean alterContext) {
        Token nameToken = next();
        String dataType = null;
        if (!atColumnEnd(alterContext) && !(peek().type() == TokenType.IDENT && COLUMN_OPTION_START.contains(peek().upper()))) {
            dataType = dataType();
        }
        Boolean notNull = null;
        String defaultValue = null;
        boolean primaryKey = false;
        boolean unique = false;
        boolean identity = dataType != null && SERIAL_TYPES.contains(dataType); // serial 류 타입은 자동 증가다(F-019-R19)
        boolean virtual = false;
        Ddl.Constraint reference = null;
        String constraintName = null;
        while (!atColumnEnd(alterContext)) {
            int before = pos;
            Token t = peek();
            if (t.is("CONSTRAINT") && peek(1).isIdentifier()) {
                next();
                constraintName = next().text();
            } else if (t.is("NOT") && peek(1).is("NULL")) {
                next();
                next();
                notNull = true;
            } else if (t.is("NOT")) {
                next();
                next();
            } else if (t.is("NULL")) {
                next();
                notNull = false;
            } else if (t.is("DEFAULT") || (t.is("WITH") && peek(1).is("DEFAULT"))) {
                accept("WITH");
                next();
                acceptSeq("ON", "NULL");
                String value = defaultValue(alterContext);
                if (value != null) {
                    defaultValue = value;
                }
            } else if (t.is("PRIMARY") && peek(1).is("KEY")) {
                next();
                next();
                primaryKey = true;
            } else if (t.is("UNIQUE")) {
                next();
                unique = true;
            } else if (t.is("REFERENCES")) {
                Ddl.Constraint c = references(constraintName, List.of(nameToken.text()), t);
                if (c != null) {
                    reference = c;
                }
                constraintName = null;
            } else if (t.is("CHECK") && peek(1).isSymbol("(")) {
                next();
                skipBalanced();
            } else if (t.is("GENERATED")) {
                next();
                if (!accept("ALWAYS") && acceptSeq("BY", "DEFAULT")) {
                    acceptSeq("ON", "NULL");
                }
                if (accept("AS")) {
                    if (accept("IDENTITY")) {
                        identity = true;
                        skipBalanced();
                    } else if (atSymbol("(")) {
                        virtual = true;
                        skipBalanced();
                    }
                }
            } else if (t.is("AS") && peek(1).isSymbol("(")) {
                next();
                skipBalanced();
                virtual = true;
            } else if (t.is("IDENTITY")) {
                next();
                identity = true;
                skipBalanced();
            } else if (t.is("USING") && peek(1).is("INDEX")) {
                next();
                next();
                if (atSymbol("(")) {
                    skipBalanced();
                } else if (accept("TABLESPACE")) {
                    next();
                } else if (peek().isIdentifier() && !(peek().type() == TokenType.IDENT && COLUMN_OPTION_START.contains(peek().upper()))) {
                    next();
                }
            } else if (t.is("CCSID") || t.is("COLLATE")) {
                next();
                next();
            } else if (t.is("AUTO_INCREMENT") || t.is("AUTOINCREMENT")) {
                next(); // MySQL·SQLite의 자동 증가: IDENTITY와 같은 뜻이다(F-007-R17)
                identity = true;
            } else if (t.is("COMMENT") && peek(1).type() == TokenType.STRING) {
                next(); // MySQL의 컬럼 주석: 건너뛴다
                next();
            } else if (t.is("CHARACTER") && peek(1).is("SET")) {
                next();
                next();
                next();
            } else if (t.is("ON") && peek(1).is("UPDATE")) {
                next(); // MySQL: ON UPDATE CURRENT_TIMESTAMP[(n)]
                next();
                if (peek().isIdentifier()) {
                    next();
                    if (atSymbol("(")) {
                        skipBalanced();
                    }
                }
            } else if (t.isSymbol("(")) {
                skipBalanced();
            } else {
                next();
            }
            if (pos == before) {
                next();
            }
        }
        return new Ddl.ColumnDef(nameToken.text(), dataType, notNull, defaultValue, primaryKey, unique, identity, virtual,
                reference, Pos.of(nameToken));
    }

    private boolean atColumnEnd(boolean alterContext) {
        Token t = peek();
        if (t.isEof() || t.isSymbol(",") || t.isSymbol(")") || t.isSymbol(";")) {
            return true;
        }
        return alterContext && t.type() == TokenType.IDENT && ALTER_ACTIONS.contains(t.upper());
    }

    /** 데이터 타입을 정리된 텍스트로 읽는다({@code NUMBER(5,2)}, {@code TIMESTAMP(6) WITH TIME ZONE}, {@code CHAR(1) FOR BIT DATA}). */
    private String dataType() {
        int start = pos;
        Token first = next();
        while (atSymbol(".") && peek(1).isIdentifier()) {
            next();
            next();
        }
        if (atSymbol("%") && peek(1).type() == TokenType.IDENT) {
            next();
            next();
        }
        switch (first.upper()) {
            case "DOUBLE":
                accept("PRECISION");
                break;
            case "LONG":
                acceptAny("RAW", "VARCHAR", "VARGRAPHIC");
                break;
            case "NATIONAL":
                acceptAny("CHARACTER", "CHAR");
                accept("VARYING");
                break;
            case "CHARACTER":
            case "CHAR":
            case "NCHAR":
            case "BINARY":
            case "BIT":
                if (!accept("VARYING")) {
                    acceptSeq("LARGE", "OBJECT");
                }
                break;
            case "INTERVAL":
                if (atAny("YEAR", "DAY")) {
                    next();
                    skipBalanced();
                    if (accept("TO")) {
                        next();
                    }
                }
                break;
            default:
                break;
        }
        if (atSymbol("(")) {
            skipBalanced();
        }
        while (atAny("UNSIGNED", "SIGNED", "ZEROFILL")) {
            next(); // MySQL 숫자 타입의 수식어: 타입의 일부로 적는다
        }
        while (true) {
            if (atSeq("WITH", "TIME", "ZONE") || atSeq("WITHOUT", "TIME", "ZONE")) {
                pos += 3;
            } else if (atSeq("WITH", "LOCAL", "TIME", "ZONE")) {
                pos += 4;
            } else if (at("FOR") && peek(1).isAny("BIT", "SBCS", "MIXED") && peek(2).is("DATA")) {
                pos += 3;
            } else {
                break;
            }
        }
        StringBuilder text = new StringBuilder(joinTokens(tokens.subList(start, pos)));
        // PostgreSQL 배열 타입(F-019-R19): text[], integer[][], text[3], integer ARRAY[4]. 타입 텍스트에 붙여 적는다
        if (at("ARRAY") && (peek(1).isSymbol("[") || peek(1).isSymbol(",") || peek(1).isSymbol(")") || peek(1).isEof())) {
            next();
            text.append(" ARRAY");
        }
        while (atSymbol("[") && (peek(1).isSymbol("]") || (peek(1).type() == TokenType.NUMBER && peek(2).isSymbol("]")))) {
            next();
            text.append('[');
            if (!atSymbol("]")) {
                text.append(next().text());
            }
            next();
            text.append(']');
        }
        return text.toString();
    }

    /** DEFAULT 뒤의 값. 값이 없으면(DB2 {@code WITH DEFAULT}) null. */
    private String defaultValue(boolean alterContext) {
        if (atColumnEnd(alterContext)) {
            return null;
        }
        Token t = peek();
        if (t.type() == TokenType.IDENT && (t.isAny("NOT", "CONSTRAINT", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "GENERATED", "ENABLE", "DISABLE"))) {
            return null;
        }
        int start = pos;
        if (t.is("NULL")) {
            next();
            return ""; // DEFAULT NULL은 기본값이 없는 것과 같다(F-007-R16). 빈 값은 "기본값 없음"으로 적용된다
        }
        SqlParser sub = sub();
        Expr e = sub.parseAdditive();
        if (sub.position() == start || e instanceof Expr.Missing) {
            return null;
        }
        pos = sub.position();
        return joinTokens(tokens.subList(start, pos));
    }

    // ------------------------------------------------------------------ ALTER TABLE

    private Statement alterTable() {
        QName name = name();
        if (atSeq("OWNER", "TO")) {
            // PostgreSQL: 소유자 변경. pg_dump는 시퀀스·뷰의 소유자도 ALTER TABLE로 낸다: 테이블에 관한 사실이 없다(F-019-R22)
            pos = tokens.size() - 1;
            return new Statement.Other("ALTER", false);
        }
        List<Ddl.AlterAction> actions = new ArrayList<>();
        if (syntax == Syntax.POSTGRES && atSeq("ATTACH", "PARTITION") && peek(2).isIdentifier()) {
            // PostgreSQL: ALTER TABLE [ONLY] 부모 ATTACH PARTITION 자식 { FOR VALUES .. | DEFAULT }. 경계 식은 읽지 않는다(F-019-R20)
            pos += 2;
            actions.add(new Ddl.AttachPartition(name()));
            pos = tokens.size() - 1;
            return new Ddl.AlterTable(name, actions);
        }
        while (!atEnd()) {
            int before = pos;
            if (accept("ADD")) {
                if (atSymbol("(")) {
                    open();
                    List<Ddl.ColumnDef> columns = new ArrayList<>();
                    List<Ddl.Constraint> constraints = new ArrayList<>();
                    elementList(columns, constraints, null);
                    close();
                    columns.forEach(c -> actions.add(new Ddl.AddColumn(c)));
                    constraints.forEach(c -> actions.add(new Ddl.AddConstraint(c)));
                } else if (atIndexClause()) {
                    actions.add(new Ddl.AddConstraint(indexClause())); // MySQL: ALTER TABLE t ADD INDEX|KEY 이름 (cols)
                } else if (atTableConstraint()) {
                    Ddl.Constraint c = tableConstraint();
                    if (c != null) {
                        actions.add(new Ddl.AddConstraint(c));
                    }
                } else {
                    if (accept("COLUMN") && atSeq("IF", "NOT", "EXISTS")) {
                        pos += 3; // ADD COLUMN IF NOT EXISTS 이름 타입
                    }
                    if (peek().isIdentifier() && !atAny("PARTITION", "SUPPLEMENTAL", "PERIOD", "RESTRICT", "MATERIALIZED", "VERSIONING")) {
                        actions.add(new Ddl.AddColumn(columnDef(true)));
                    }
                }
            } else if (accept("MODIFY")) {
                if (atSymbol("(")) {
                    open();
                    while (!atEnd() && !atSymbol(")")) {
                        if (peek().isIdentifier()) {
                            actions.add(new Ddl.ModifyColumn(columnDef(false)));
                        }
                        skipToElementEnd();
                        if (!acceptSymbol(",")) {
                            break;
                        }
                    }
                    close();
                } else if (peek().isIdentifier() && !atAny("CONSTRAINT", "PRIMARY", "UNIQUE", "PARTITION", "SUBPARTITION", "LOB", "NESTED", "DEFAULT")) {
                    accept("COLUMN");
                    actions.add(new Ddl.ModifyColumn(columnDef(true)));
                } else {
                    skipToNextAction();
                }
            } else if (at("ALTER")) {
                next();
                accept("COLUMN");
                if (peek().isIdentifier() && !atAny("FOREIGN", "CHECK", "CONSTRAINT")) {
                    actions.add(alterColumn());
                } else {
                    skipToNextAction();
                }
            } else if (accept("DROP")) {
                if (accept("COLUMN")) {
                    if (atSeq("IF", "EXISTS") && peek(2).isIdentifier()) {
                        pos += 2; // DROP COLUMN IF EXISTS 이름
                    }
                    if (peek().isIdentifier()) {
                        actions.add(new Ddl.DropColumn(next().text()));
                    }
                } else if (atSymbol("(")) {
                    nameList().forEach(n -> actions.add(new Ddl.DropColumn(n)));
                }
                skipToNextAction();
            } else if (accept("RENAME")) {
                if (accept("COLUMN") && peek().isIdentifier()) {
                    String from = next().text();
                    if (accept("TO") && peek().isIdentifier()) {
                        actions.add(new Ddl.RenameColumn(from, next().text()));
                    }
                }
                skipToNextAction();
            } else if (atSymbol("(")) {
                skipBalanced();
            } else {
                next();
            }
            if (pos == before) {
                next();
            }
        }
        return new Ddl.AlterTable(name, actions);
    }

    private void skipToNextAction() {
        while (!atEnd() && !(peek().type() == TokenType.IDENT && ALTER_ACTIONS.contains(peek().upper()))) {
            if (atSymbol("(")) {
                skipBalanced();
            } else {
                next();
            }
        }
    }

    /**
     * DB2 {@code ALTER COLUMN c SET DATA TYPE t | SET NOT NULL | DROP NOT NULL | SET DEFAULT x | DROP DEFAULT}.
     * PostgreSQL의 {@code ALTER COLUMN c TYPE t [USING 식]}, {@code ADD GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY [(..)]}도 읽는다(F-019-R18).
     */
    private Ddl.AlterAction alterColumn() {
        Token nameToken = next();
        String dataType = null;
        Boolean notNull = null;
        String defaultValue = null;
        boolean identity = false;
        while (!atEnd()) {
            int before = pos;
            if (acceptSeq("SET", "DATA", "TYPE")) {
                dataType = dataType();
            } else if (at("TYPE") && peek(1).isIdentifier()) {
                next();
                dataType = dataType();
            } else if (at("ADD") && peek(1).is("GENERATED")) {
                pos += 2;
                if (!accept("ALWAYS")) {
                    acceptSeq("BY", "DEFAULT");
                }
                if (acceptSeq("AS", "IDENTITY")) {
                    identity = true;
                    skipBalanced(); // (SEQUENCE NAME .. START WITH ..)
                }
            } else if (acceptSeq("SET", "NOT", "NULL")) {
                notNull = true;
            } else if (acceptSeq("DROP", "NOT", "NULL")) {
                notNull = false;
            } else if (acceptSeq("SET", "DEFAULT")) {
                defaultValue = defaultValue(true);
            } else if (acceptSeq("DROP", "DEFAULT")) {
                defaultValue = "";
            } else if (at("SET") || (at("DROP") && peek(1).isAny("IDENTITY", "EXPRESSION", "GENERATED", "COLUMN") && !peek(1).is("COLUMN"))) {
                next();
                while (!atEnd() && !(peek().type() == TokenType.IDENT && ALTER_ACTIONS.contains(peek().upper())) && !at("SET")) {
                    if (atSymbol("(")) {
                        skipBalanced();
                    } else {
                        next();
                    }
                }
            } else {
                break;
            }
            if (pos == before) {
                next();
            }
        }
        return new Ddl.ModifyColumn(new Ddl.ColumnDef(nameToken.text(), dataType, notNull, defaultValue, false, false, identity, false,
                null, Pos.of(nameToken)));
    }

    // ------------------------------------------------------------------ COMMENT, INDEX, VIEW, SYNONYM

    private Statement commentOn() {
        if (atAny("TABLE", "COLUMN") && peek(1).isIdentifier()) {
            String target = next().upper();
            QName name = name();
            String text = accept("IS") ? stringValue() : null;
            pos = tokens.size() - 1;
            return text == null ? new Statement.Other("COMMENT", false) : new Ddl.CommentOn(target, name, text);
        }
        if (peek().isIdentifier() && !atAny("INDEX", "MATERIALIZED", "VIEW", "SEQUENCE", "PROCEDURE", "FUNCTION",
                "TRIGGER", "PACKAGE", "TYPE", "CONSTRAINT", "SCHEMA", "ALIAS", "ROLE", "TABLESPACE", "EDITION", "OPERATOR", "INDEXTYPE", "MINING", "VARIABLE")) {
            // DB2: COMMENT ON table (c1 IS '..', c2 IS '..')
            QName table = name();
            if (atSymbol("(")) {
                List<Ddl> list = new ArrayList<>();
                open();
                while (!atEnd() && !atSymbol(")")) {
                    if (peek().isIdentifier() && peek(1).is("IS")) {
                        Token column = next();
                        next();
                        String text = stringValue();
                        if (text != null) {
                            List<String> parts = new ArrayList<>(table.rawParts());
                            parts.add(column.text());
                            list.add(new Ddl.CommentOn("COLUMN", new QName(parts, null, Pos.of(column)), text));
                        }
                    }
                    skipToElementEnd();
                    if (!acceptSymbol(",")) {
                        break;
                    }
                }
                close();
                pos = tokens.size() - 1;
                return new Ddl.Multi(list);
            }
        }
        pos = tokens.size() - 1;
        return new Statement.Other("COMMENT", false);
    }

    /** 문자열 리터럴의 값(따옴표를 벗기고 {@code ''}를 {@code '}로). 이어 쓴 리터럴은 붙인다. 문자열이 아니면 null. */
    private String stringValue() {
        if (peek().type() != TokenType.STRING) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        while (peek().type() == TokenType.STRING) {
            sb.append(unquote(next().text()));
            if (atSymbol("||") && peek(1).type() == TokenType.STRING) {
                next();
            }
        }
        return sb.toString();
    }

    /** 문자열 리터럴 토큰의 값. */
    public static String unquote(String literal) {
        int q = literal.indexOf('\'');
        if (q < 0) {
            return literal;
        }
        String prefix = literal.substring(0, q).toUpperCase(java.util.Locale.ROOT);
        String body = literal.substring(q + 1);
        if (body.endsWith("'")) {
            body = body.substring(0, body.length() - 1);
        }
        if (prefix.endsWith("Q") && body.length() >= 2) {
            return body.substring(1, body.length() - 1);
        }
        return body.replace("''", "'");
    }

    /**
     * {@code CREATE [UNIQUE] INDEX [CONCURRENTLY] [IF NOT EXISTS] [이름] ON [ONLY] 테이블 [USING 방식] (항목, ..) [INCLUDE (..)] [WITH (..)]
     * [TABLESPACE ..] [WHERE ..]}(F-019-R21). 항목은 {@code 컬럼 [COLLATE ..] [연산자클래스] [ASC|DESC] [NULLS ..]}거나 식이다.
     * 연산자 클래스와 인덱스 방식은 컬럼이 아니고, 식 항목은 표시용 텍스트로만 남는다.
     */
    private Statement createIndex(boolean unique) {
        if (at("CONCURRENTLY") && (syntax == Syntax.POSTGRES || !peek(1).is("ON"))) {
            next(); // 기본 읽기 방식에서 바로 뒤가 ON이면 CONCURRENTLY라는 이름의 인덱스다(전과 같다)
        }
        if (atSeq("IF", "NOT", "EXISTS")) {
            pos += 3;
        }
        QName name = null;
        if (!(at("ON") && peek(1).isIdentifier() && !peek(1).is("ON"))) {
            // 이름 없는 CREATE INDEX ON t (..)가 아니다
            if (!peek().isIdentifier()) {
                return new Statement.Unparsed("CREATE INDEX");
            }
            name = name();
        }
        if (!accept("ON") || !peek().isIdentifier() || at("CLUSTER")) {
            pos = tokens.size() - 1;
            return new Statement.Other("CREATE INDEX", false);
        }
        if (at("ONLY") && peek(1).isIdentifier() && !peek(1).is("USING")) {
            next(); // ON ONLY 테이블
        }
        QName table = name();
        if (at("USING") && peek(1).isIdentifier() && peek(2).isSymbol("(")) {
            pos += 2; // 인덱스 방식(btree, gin, ..)
        } else if (peek().isIdentifier() && atSymbol("(") == false && peek(1).isSymbol("(")) {
            next(); // 테이블 별칭
        }
        List<String> columns = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        if (atSymbol("(")) {
            open();
            while (!atEnd() && !atSymbol(")")) {
                int start = pos;
                skipToElementEnd();
                int end = pos;
                if (end - start > 2 && tokens.get(end - 2).is("NULLS") && tokens.get(end - 1).isAny("FIRST", "LAST")) {
                    end -= 2;
                }
                // 정렬 방향: DESC만 표시에 남긴다(ASC는 기본값이라 적지 않는다)
                boolean descending = false;
                while (end > start && tokens.get(end - 1).isAny("ASC", "DESC")) {
                    descending |= tokens.get(end - 1).is("DESC");
                    end--;
                }
                if (end > start) {
                    String suffix = descending ? " DESC" : "";
                    if (isIndexColumn(start, end)) {
                        columns.add(tokens.get(start).text());
                        expressions.add(io.codeatlas.core.model.DbName.normalize(tokens.get(start).text()) + suffix);
                    } else {
                        expressions.add(joinTokens(tokens.subList(start, end)) + suffix);
                    }
                }
                if (!acceptSymbol(",")) {
                    break;
                }
            }
            close();
        }
        // 뒤의 INCLUDE (..)·WITH (..)·TABLESPACE는 넘긴다. WHERE가 있으면 부분 인덱스다
        boolean partial = false;
        while (!atEnd()) {
            if (atSymbol("(")) {
                skipBalanced();
                continue;
            }
            partial |= at("WHERE");
            next();
        }
        pos = tokens.size() - 1;
        return new Ddl.CreateIndex(name, unique, table, columns, expressions, partial);
    }

    /**
     * 인덱스 항목 [start, end)가 컬럼 하나인가(정렬 방향과 NULLS 절은 뗀 뒤다): {@code 컬럼}, {@code 컬럼 연산자클래스}, {@code 컬럼 COLLATE 이름 [연산자클래스]}.
     * 연산자 클래스는 {@code 스키마.이름}이나 {@code 이름(매개변수)} 꼴일 수 있다. 그 밖은 식이다.
     */
    private boolean isIndexColumn(int start, int end) {
        if (!tokens.get(start).isIdentifier()) {
            return false;
        }
        int i = start + 1;
        if (i < end && tokens.get(i).is("COLLATE")) {
            i = skipDottedName(i + 1, end);
            if (i < 0) {
                return false;
            }
        }
        if (i < end) {
            i = skipDottedName(i, end);
            if (i < 0) {
                return false;
            }
            if (i < end && tokens.get(i).isSymbol("(")) {
                int depth = 0;
                for (; i < end; i++) {
                    if (tokens.get(i).isSymbol("(")) {
                        depth++;
                    } else if (tokens.get(i).isSymbol(")") && --depth == 0) {
                        i++;
                        break;
                    }
                }
            }
        }
        return i == end;
    }

    /** from에서 {@code 이름[.이름]..}을 읽고 그 뒤 위치를 돌려준다. 이름이 아니면 -1. */
    private int skipDottedName(int from, int end) {
        if (from >= end || !tokens.get(from).isIdentifier()) {
            return -1;
        }
        int i = from + 1;
        while (i + 1 < end && tokens.get(i).isSymbol(".") && tokens.get(i + 1).isIdentifier()) {
            i += 2;
        }
        return i;
    }

    private Statement createView(boolean materialized) {
        if (atSeq("IF", "NOT", "EXISTS") && peek(3).isIdentifier()) {
            pos += 3; // CREATE MATERIALIZED VIEW IF NOT EXISTS 이름
        }
        if (!peek().isIdentifier()) {
            return new Statement.Unparsed("CREATE VIEW");
        }
        QName name = name();
        List<String> columns = new ArrayList<>();
        if (atSymbol("(") && !lookaheadIsQuery(0)) {
            columns = nameList();
        }
        Query query = null;
        while (!atEnd()) {
            if (at("AS") && (peek(1).is("SELECT") || peek(1).is("WITH") || peek(1).is("VALUES") || (peek(1).isSymbol("(") && lookaheadIsQuery(1)))) {
                next();
                SqlParser sub = sub();
                query = sub.parseQuery();
                adopt(sub);
                break;
            }
            if (atSymbol("(")) {
                skipBalanced();
            } else {
                next();
            }
        }
        if (query == null) {
            problem("뷰 정의 쿼리를 찾지 못함", peek(), false);
        } else if (!atEnd()) {
            problem("해석하지 못한 토큰: " + peek().text(), peek(), false);
        }
        pos = tokens.size() - 1;
        return new Ddl.CreateView(name, columns, query, materialized);
    }

    private Statement createSynonym(boolean isPublic) {
        if (!peek().isIdentifier()) {
            return new Statement.Unparsed("CREATE SYNONYM");
        }
        QName name = name();
        if (!accept("FOR")) {
            pos = tokens.size() - 1;
            return new Statement.Unparsed("CREATE SYNONYM");
        }
        if (atAny("TABLE", "SEQUENCE", "MODULE") && peek(1).isIdentifier()) {
            next();
        }
        if (!peek().isIdentifier()) {
            pos = tokens.size() - 1;
            return new Statement.Unparsed("CREATE SYNONYM");
        }
        QName target = name();
        pos = tokens.size() - 1;
        return new Ddl.CreateSynonym(name, target, isPublic);
    }
}
