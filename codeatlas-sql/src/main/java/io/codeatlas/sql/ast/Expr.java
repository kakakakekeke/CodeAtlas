package io.codeatlas.sql.ast;

import java.util.List;

/** SQL 식. 모든 구현은 불변이다. */
public sealed interface Expr {

    /** 컬럼(또는 변수) 참조. {@code outerJoin}은 Oracle {@code (+)} 표시. */
    record ColumnRef(QName name, boolean outerJoin) implements Expr {
    }

    /** {@code *} 또는 {@code t.*}. qualifier는 없으면 null. */
    record Star(QName qualifier) implements Expr {
    }

    /** 문자열, 숫자, NULL, 날짜 리터럴, 특수 레지스터({@code CURRENT DATE}) 등. */
    record Literal(String text) implements Expr {
    }

    record Bind(String text) implements Expr {
    }

    /**
     * 함수 호출. {@code extras}에는 OVER, KEEP, WITHIN GROUP, FILTER 절 안의 식이 들어간다.
     * CAST, EXTRACT, TRIM 같은 특수형도 이 노드로 만든다.
     */
    record FuncCall(QName name, List<Expr> args, List<Expr> extras) implements Expr {
        public FuncCall {
            args = List.copyOf(args);
            extras = List.copyOf(extras);
        }
    }

    /**
     * 이항 연산. op: {@code AND OR = <> < > <= >= + - * / || LIKE} 등(비교 연산자는 {@code !=}, {@code ^=}도 {@code <>}로 통일).
     * PostgreSQL 연산자({@code -> ->> #> #>> @> <@ && ~ ~* !~ !~* @@})와 첨자 접근({@code a[i]}는 op {@code []})도 이 노드다(F-019-R15).
     */
    record Binary(String op, Expr left, Expr right) implements Expr {
    }

    /** 단항 연산. op: {@code NOT - + PRIOR CONNECT_BY_ROOT IS NULL IS NOT NULL IS}. */
    record Unary(String op, Expr operand) implements Expr {
    }

    record Case(Expr operand, List<WhenClause> whens, Expr otherwise) implements Expr {
        public Case {
            whens = List.copyOf(whens);
        }
    }

    record WhenClause(Expr when, Expr then) {
    }

    /** 괄호로 감싼 서브쿼리(스칼라 서브쿼리, 비교의 우변). */
    record Subquery(Query query) implements Expr {
    }

    record Exists(Query query) implements Expr {
    }

    /** {@code left [NOT] IN (list | subquery)}. 둘 중 하나만 채워진다. */
    record In(Expr left, boolean not, List<Expr> list, Query subquery) implements Expr {
        public In {
            list = list == null ? List.of() : List.copyOf(list);
        }
    }

    record Between(Expr value, Expr low, Expr high, boolean not) implements Expr {
    }

    /** {@code (a, b, c)}. PostgreSQL 배열 생성자 {@code ARRAY[a, b, c]}도 이 노드다. */
    record Tuple(List<Expr> items) implements Expr {
        public Tuple {
            items = List.copyOf(items);
        }
    }

    /**
     * {@code seq.NEXTVAL}, {@code seq.CURRVAL}, {@code NEXT VALUE FOR seq}, {@code PREVIOUS VALUE FOR seq}.
     * PostgreSQL {@code nextval('seq')}·{@code currval('seq')}·{@code setval('seq', ..)}는 그 함수 호출의 첫 인자가 이 노드다
     * (op는 {@code NEXTVAL}, {@code CURRVAL}, {@code SETVAL}, F-019-R16).
     */
    record SequenceRef(QName sequence, String op) implements Expr {
    }

    /** 있어야 할 자리에 식이 없다(MyBatis 전개 잔재 등). */
    record Missing() implements Expr {
    }

    /** 해석하지 못해 건너뛴 구간. */
    record Unparsed(String text) implements Expr {
    }
}
