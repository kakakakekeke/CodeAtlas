package io.codeatlas.sql.ast;

import java.util.List;

/**
 * 쿼리 식: {@code [WITH ...] body [ORDER BY ...] [페이징·잠금 절]}.
 * {@code tail}에는 OFFSET/FETCH/LIMIT 절의 식이 들어간다. {@code forUpdateOf}는 {@code FOR UPDATE OF} 컬럼이다.
 */
public record Query(List<Cte> ctes, QueryBody body, List<Expr> orderBy, List<Expr> tail, boolean forUpdate,
        List<QName> forUpdateOf, Pos pos) {

    public Query {
        ctes = List.copyOf(ctes);
        orderBy = List.copyOf(orderBy);
        tail = List.copyOf(tail);
        forUpdateOf = List.copyOf(forUpdateOf);
        pos = pos == null ? Pos.NONE : pos;
    }

    /**
     * {@code name [(columns)] AS (query)}. PostgreSQL의 데이터 변경 CTE({@code name AS (INSERT|UPDATE|DELETE … [RETURNING ..])}, F-019-R14)면
     * {@code dml}이 그 구문이고 {@code query}는 빈 쿼리다. 보통의 CTE면 {@code dml}은 null이다.
     */
    public record Cte(String name, List<String> columns, Query query, Pos pos, Statement dml) {
        public Cte {
            columns = List.copyOf(columns);
        }

        public Cte(String name, List<String> columns, Query query, Pos pos) {
            this(name, columns, query, pos, null);
        }
    }

    /** 선택 목록 항목. alias는 없으면 null(정규화하지 않은 원문). */
    public record SelectItem(Expr expr, String alias) {
    }

    public sealed interface QueryBody {
    }

    /** SELECT 한 덩어리. 없는 절은 null 또는 빈 목록. {@code distinctOn}은 PostgreSQL {@code SELECT DISTINCT ON (식, ..)}의 식이다(F-019-R15). */
    public record Select(boolean distinct, List<SelectItem> items, List<Expr> into, List<TableRef> from, Expr where,
            Expr startWith, Expr connectBy, List<Expr> groupBy, Expr having, Pos pos, List<Expr> distinctOn) implements QueryBody {
        public Select {
            items = List.copyOf(items);
            into = List.copyOf(into);
            from = List.copyOf(from);
            groupBy = List.copyOf(groupBy);
            distinctOn = List.copyOf(distinctOn);
        }

        public Select(boolean distinct, List<SelectItem> items, List<Expr> into, List<TableRef> from, Expr where,
                Expr startWith, Expr connectBy, List<Expr> groupBy, Expr having, Pos pos) {
            this(distinct, items, into, from, where, startWith, connectBy, groupBy, having, pos, List.of());
        }
    }

    /** 집합 연산. op: {@code UNION, UNION ALL, MINUS, EXCEPT, INTERSECT}. */
    public record SetOperation(String op, QueryBody left, QueryBody right) implements QueryBody {
    }

    public record Values(List<List<Expr>> rows) implements QueryBody {
        public Values {
            rows = List.copyOf(rows);
        }
    }

    /** 괄호로 감싼 쿼리. */
    public record Nested(Query query) implements QueryBody {
    }
}
