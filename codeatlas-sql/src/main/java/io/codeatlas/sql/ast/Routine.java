package io.codeatlas.sql.ast;

import io.codeatlas.sql.lexer.Token;
import java.util.List;

/**
 * 루틴 본문(PL/SQL, SQL PL)의 구문 트리. 두 방언의 합집합을 한 모양으로 담는다.
 *
 * <p>본문의 목적은 SQL 구문과 호출을 찾는 것이므로 식은 따로 트리로 만들지 않고 토큰 구간({@link Expr})으로 두되,
 * 그 안의 서브쿼리와 이름 참조(변수·함수 호출 후보)는 미리 뽑아 둔다. SQL 구문은 {@link Sql}에 F-006의 구문 트리로 들어 있다.
 */
public final class Routine {

    private Routine() {
    }

    /** 본문 안의 SQL 구문을 파싱하며 복구한 문제. */
    public record Problem(String message, Pos pos, boolean minor) {
    }

    /** 식 안의 이름. {@code call}이면 괄호(인자 목록)가 뒤따른다. */
    public record NameRef(QName name, boolean call) {
    }

    /**
     * 식: 토큰 구간과 그 안에서 찾은 서브쿼리, 이름 참조.
     *
     * @param tokens 식의 토큰(원문 순서)
     * @param subqueries 괄호로 감싼 서브쿼리({@code (SELECT ..)})
     * @param refs 점으로 이은 이름들(앞이 {@code .}이나 {@code %}인 것, {@code =>} 앞의 인자 이름은 제외)
     */
    public record Expr(List<Token> tokens, List<Sql> subqueries, List<NameRef> refs, Pos pos) {
        public Expr {
            tokens = List.copyOf(tokens);
            subqueries = List.copyOf(subqueries);
            refs = List.copyOf(refs);
            pos = pos == null ? Pos.NONE : pos;
        }

        public boolean isEmpty() {
            return tokens.isEmpty();
        }
    }

    /** 매개변수. mode는 {@code IN}, {@code OUT}, {@code IN OUT}(없으면 null). */
    public record Param(String name, String mode, String type, Expr defaultValue) {
    }

    // ------------------------------------------------------------------ 선언

    public sealed interface Decl {
        Pos pos();
    }

    /** 변수·상수·예외·조건·문장 이름 선언. DB2는 이름이 여럿일 수 있다. kind: {@code VARIABLE, CONSTANT, EXCEPTION, CONDITION, STATEMENT}. */
    public record Variable(List<String> names, String kind, String type, Expr init, Pos pos) implements Decl {
        public Variable {
            names = List.copyOf(names);
        }
    }

    /** 커서 선언. {@code query}는 정적 쿼리(없으면 null), {@code statementName}은 DB2 {@code CURSOR FOR s1}의 문장 이름. */
    public record Cursor(String name, List<Param> params, Sql query, String statementName, Pos pos) implements Decl {
        public Cursor {
            params = List.copyOf(params);
        }
    }

    /** {@code TYPE}, {@code SUBTYPE} 선언. */
    public record TypeDef(String name, Pos pos) implements Decl {
    }

    /** 서브프로그램(패키지 멤버 또는 중첩 선언). kind: {@code PROCEDURE, FUNCTION}. body가 null이면 전방 선언·명세다. */
    public record Subprogram(String kind, String name, List<Param> params, String returnType, Block body, Pos pos) implements Decl {
        public Subprogram {
            params = List.copyOf(params);
        }
    }

    /** DB2 {@code DECLARE CONTINUE|EXIT|UNDO HANDLER FOR 조건 문장}. */
    public record Handler(String action, String conditions, Stmt body, Pos pos) implements Decl {
    }

    /** {@code PRAGMA} 등 뽑을 것이 없는 선언. */
    public record OtherDecl(String keyword, Pos pos) implements Decl {
    }

    // ------------------------------------------------------------------ 문장

    public sealed interface Stmt {
        Pos pos();
    }

    /**
     * 본문 안의 SQL 구문 하나.
     *
     * @param statement F-006의 구문 트리
     * @param salvagedReads 해석하지 못한 꼬리에서 건진 테이블 이름
     * @param tokens 구문의 토큰(끝의 {@code ;} 제외)
     * @param intoTargets {@code INTO} 뒤의 대상 변수 이름(정규화)
     * @param pos 구문의 범위. 시작 줄이 SQL 키워드가 있는 줄이다
     */
    public record Sql(Statement statement, List<QName> salvagedReads, List<Problem> problems, List<Token> tokens,
            List<String> intoTargets, Pos pos) implements Stmt {
        public Sql {
            salvagedReads = List.copyOf(salvagedReads);
            problems = List.copyOf(problems);
            tokens = List.copyOf(tokens);
            intoTargets = List.copyOf(intoTargets);
        }
    }

    /** PL/SQL 예외 처리부의 {@code WHEN 이름 [OR 이름] THEN 문장들}. */
    public record ExceptionHandler(List<String> exceptions, List<Stmt> statements, Pos pos) {
        public ExceptionHandler {
            exceptions = List.copyOf(exceptions);
            statements = List.copyOf(statements);
        }
    }

    /** 블록({@code [DECLARE ..] BEGIN .. [EXCEPTION ..] END}, DB2 복합문). DB2의 {@code DECLARE} 문은 declarations에 모인다. */
    public record Block(String label, List<Decl> declarations, List<Stmt> statements, List<ExceptionHandler> handlers,
            boolean atomic, Pos pos) implements Stmt {
        public Block {
            declarations = List.copyOf(declarations);
            statements = List.copyOf(statements);
            handlers = List.copyOf(handlers);
        }
    }

    /** 조건과 그 안의 문장들(IF/ELSIF, CASE의 WHEN). */
    public record Branch(Expr condition, List<Stmt> statements) {
        public Branch {
            statements = List.copyOf(statements);
        }
    }

    public record If(List<Branch> branches, List<Stmt> otherwise, Pos pos) implements Stmt {
        public If {
            branches = List.copyOf(branches);
            otherwise = List.copyOf(otherwise);
        }
    }

    /** CASE 문. selector는 단순 CASE의 비교 대상(검색 CASE면 null). */
    public record Case(Expr selector, List<Branch> branches, List<Stmt> otherwise, Pos pos) implements Stmt {
        public Case {
            branches = List.copyOf(branches);
            otherwise = List.copyOf(otherwise);
        }
    }

    public enum LoopKind {
        /** {@code LOOP .. END LOOP} */
        BASIC,
        /** {@code WHILE 조건 LOOP|DO} */
        WHILE,
        /** {@code FOR i IN lo..hi LOOP}, PL/pgSQL {@code FOREACH x IN ARRAY 식 LOOP}(control이 배열 식이다) */
        FOR_RANGE,
        /** {@code FOR r IN 커서[(인자)] LOOP} */
        FOR_CURSOR,
        /** {@code FOR r IN (SELECT ..) LOOP}, DB2 {@code FOR v AS [c CURSOR FOR] SELECT .. DO} */
        FOR_QUERY,
        /** DB2 {@code REPEAT .. UNTIL 조건 END REPEAT} */
        REPEAT
    }

    /**
     * 반복문.
     *
     * @param variable 루프 변수(FOR), 없으면 null
     * @param control WHILE·REPEAT의 조건, FOR_RANGE의 범위, FOR_CURSOR의 커서 식
     * @param cursor FOR_CURSOR의 커서 이름, DB2 FOR의 커서 이름
     * @param query FOR_QUERY의 쿼리
     * @param columnsInScope DB2 {@code FOR v AS [c CURSOR FOR] SELECT .. DO}면 true: 쿼리의 선택 목록에 놓인 컬럼 이름(별칭)이
     *                       루프 본문에서 그대로 변수로 보인다({@code v.col}뿐 아니라 {@code col}로도 쓴다, F-008-R16)
     */
    public record Loop(LoopKind kind, String label, String variable, Expr control, String cursor, Sql query,
            List<Stmt> statements, boolean columnsInScope, Pos pos) implements Stmt {
        public Loop {
            statements = List.copyOf(statements);
        }

        public Loop(LoopKind kind, String label, String variable, Expr control, String cursor, Sql query, List<Stmt> statements, Pos pos) {
            this(kind, label, variable, control, cursor, query, statements, false, pos);
        }
    }

    /** {@code FORALL i IN 범위 DML}. */
    public record ForAll(String index, Expr bounds, Stmt statement, Pos pos) implements Stmt {
    }

    /**
     * 대입({@code t := 식}, DB2 {@code SET t = 식}). 튜플 대입이면 targets가 여럿이다. index는 대상의 첨자 식(없으면 null).
     *
     * @param tokens 문장의 토큰(끝의 {@code ;} 제외). 식 안의 시퀀스 참조를 구문 노드로 남길 때 원문을 얻는 데 쓴다(F-008-R15)
     */
    public record Assign(List<QName> targets, Expr index, Expr value, List<Token> tokens, Pos pos) implements Stmt {
        public Assign {
            targets = List.copyOf(targets);
            tokens = List.copyOf(tokens);
        }

        public Assign(List<QName> targets, Expr index, Expr value, Pos pos) {
            this(targets, index, value, List.of(), pos);
        }
    }

    /**
     * 프로시저 호출문.
     *
     * @param args 인자 목록의 식(괄호가 없으면 null)
     * @param callKeyword DB2 {@code CALL} 문이면 true
     * @param tokens 이름부터 닫는 괄호까지의 토큰
     */
    public record Call(QName name, Expr args, boolean callKeyword, List<Token> tokens, Pos pos) implements Stmt {
        public Call {
            tokens = List.copyOf(tokens);
        }
    }

    /** {@code OPEN c [(인자)] [FOR 쿼리 | FOR 식 [USING ..]]}. query와 dynamic 중 하나만(또는 둘 다 null) 채워진다. */
    public record Open(String cursor, Expr args, Sql query, Expr dynamic, Expr using, Pos pos) implements Stmt {
    }

    public record Fetch(String cursor, Expr into, Pos pos) implements Stmt {
    }

    public record Close(String cursor, Pos pos) implements Stmt {
    }

    /**
     * {@code EXECUTE IMMEDIATE 식 [INTO ..] [USING ..] [RETURNING ..]}. rest는 식 뒤의 절들.
     * PL/pgSQL의 {@code EXECUTE 식 [INTO ..] [USING ..]}, {@code RETURN QUERY EXECUTE 식}, {@code FOR r IN EXECUTE 식}의 동적 SQL 지점도 이것이다.
     */
    public record ExecuteImmediate(Expr sql, Expr rest, Pos pos) implements Stmt {
    }

    /** DB2 {@code PREPARE 이름 FROM 식}. */
    public record Prepare(String name, Expr sql, Pos pos) implements Stmt {
    }

    /** DB2 {@code EXECUTE 이름 [INTO ..] [USING ..]}. */
    public record Execute(String name, Expr rest, Pos pos) implements Stmt {
    }

    /** {@code RETURN [식]}. DB2 테이블 함수의 {@code RETURN SELECT ..}면 query가 채워진다. */
    public record Return(Expr value, Sql query, Pos pos) implements Stmt {
    }

    /** 제어 이동. kind: {@code GOTO, EXIT, CONTINUE, LEAVE, ITERATE}. */
    public record Jump(String kind, String label, Expr when, Pos pos) implements Stmt {
    }

    /** 예외 발생. kind: {@code RAISE, SIGNAL, RESIGNAL}. detail은 뒤따르는 토큰들. */
    public record Raise(String kind, Expr detail, Pos pos) implements Stmt {
    }

    /**
     * 뽑을 것이 거의 없는 문장: {@code NULL, COMMIT, ROLLBACK, SAVEPOINT, PRAGMA, GET DIAGNOSTICS, PIPE ROW, SET(레지스터), DDL} 등.
     * detail은 뒤따르는 토큰들(서브쿼리만 본다. PIPE ROW는 호출도 본다).
     */
    public record Simple(String keyword, Expr detail, Pos pos) implements Stmt {
    }

    /** 해석하지 못해 건너뛴 문장. salvage에서 서브쿼리를 건진다. */
    public record Unknown(String keyword, Expr salvage, Pos pos) implements Stmt {
    }

    // ------------------------------------------------------------------ 순회

    /** 트리 순회 콜백. 원문 순서(전위)로 불린다. */
    public interface Visitor {
        default void statement(Stmt statement) {
        }

        default void declaration(Decl declaration) {
        }

        default void expression(Expr expression) {
        }

        /** 구문 자리의 SQL과 식·커서·루프 안의 쿼리 모두. */
        default void sql(Sql sql) {
        }
    }

    public static void visit(List<? extends Stmt> statements, Visitor v) {
        for (Stmt s : statements) {
            visit(s, v);
        }
    }

    public static void visit(Stmt s, Visitor v) {
        if (s == null) {
            return;
        }
        v.statement(s);
        if (s instanceof Sql q) {
            v.sql(q);
        } else if (s instanceof Block b) {
            for (Decl d : b.declarations()) {
                visit(d, v);
            }
            visit(b.statements(), v);
            for (ExceptionHandler h : b.handlers()) {
                visit(h.statements(), v);
            }
        } else if (s instanceof If i) {
            for (Branch br : i.branches()) {
                visit(br.condition(), v);
                visit(br.statements(), v);
            }
            visit(i.otherwise(), v);
        } else if (s instanceof Case c) {
            visit(c.selector(), v);
            for (Branch br : c.branches()) {
                visit(br.condition(), v);
                visit(br.statements(), v);
            }
            visit(c.otherwise(), v);
        } else if (s instanceof Loop l) {
            if (l.query() != null) {
                v.sql(l.query());
            }
            if (l.kind() != LoopKind.REPEAT) {
                visit(l.control(), v);
            }
            visit(l.statements(), v);
            if (l.kind() == LoopKind.REPEAT) {
                visit(l.control(), v);
            }
        } else if (s instanceof ForAll f) {
            visit(f.bounds(), v);
            visit(f.statement(), v);
        } else if (s instanceof Assign a) {
            visit(a.index(), v);
            visit(a.value(), v);
        } else if (s instanceof Call c) {
            visit(c.args(), v);
        } else if (s instanceof Open o) {
            visit(o.args(), v);
            if (o.query() != null) {
                v.sql(o.query());
            }
            visit(o.dynamic(), v);
            visit(o.using(), v);
        } else if (s instanceof Fetch f) {
            visit(f.into(), v);
        } else if (s instanceof ExecuteImmediate e) {
            visit(e.sql(), v);
            visit(e.rest(), v);
        } else if (s instanceof Prepare p) {
            visit(p.sql(), v);
        } else if (s instanceof Execute e) {
            visit(e.rest(), v);
        } else if (s instanceof Return r) {
            if (r.query() != null) {
                v.sql(r.query());
            }
            visit(r.value(), v);
        } else if (s instanceof Jump j) {
            visit(j.when(), v);
        } else if (s instanceof Raise r) {
            visit(r.detail(), v);
        } else if (s instanceof Simple x) {
            visit(x.detail(), v);
        } else if (s instanceof Unknown u) {
            visit(u.salvage(), v);
        }
    }

    public static void visit(Decl d, Visitor v) {
        if (d == null) {
            return;
        }
        v.declaration(d);
        if (d instanceof Variable x) {
            visit(x.init(), v);
        } else if (d instanceof Cursor c) {
            for (Param p : c.params()) {
                visit(p.defaultValue(), v);
            }
            if (c.query() != null) {
                v.sql(c.query());
            }
        } else if (d instanceof Subprogram sp) {
            for (Param p : sp.params()) {
                visit(p.defaultValue(), v);
            }
            if (sp.body() != null) {
                visit(sp.body(), v);
            }
        } else if (d instanceof Handler h) {
            visit(h.body(), v);
        }
    }

    public static void visit(Expr e, Visitor v) {
        if (e == null) {
            return;
        }
        v.expression(e);
        for (Sql q : e.subqueries()) {
            v.sql(q);
        }
    }
}
