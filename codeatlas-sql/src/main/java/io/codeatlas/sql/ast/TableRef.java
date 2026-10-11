package io.codeatlas.sql.ast;

import java.util.List;

/** FROM 절과 DML 대상에 오는 테이블 참조. alias는 없으면 null(원문). */
public sealed interface TableRef {

    /** 이름으로 가리킨 테이블·뷰·CTE. {@code pivot}이면 PIVOT/UNPIVOT이 붙어 컬럼을 알 수 없다. */
    record Named(QName name, String alias, List<String> columnAliases, boolean pivot) implements TableRef {
        public Named {
            columnAliases = List.copyOf(columnAliases);
        }
    }

    /** 인라인 뷰. {@code lateral}이면 왼쪽 테이블을 참조할 수 있다. */
    record Derived(Query query, String alias, List<String> columnAliases, boolean lateral, boolean pivot) implements TableRef {
        public Derived {
            columnAliases = List.copyOf(columnAliases);
        }
    }

    /** {@code TABLE(f(..))}, {@code XMLTABLE(..)} 같은 테이블 함수. */
    record Function(Expr call, String alias) implements TableRef {
    }

    /** 조인. type: {@code INNER, LEFT, RIGHT, FULL, CROSS, CROSS APPLY, OUTER APPLY}. */
    record Join(TableRef left, String type, boolean natural, TableRef right, Expr on, List<String> using) implements TableRef {
        public Join {
            using = List.copyOf(using);
        }
    }

    /** DB2 {@code FINAL|NEW|OLD TABLE (dml)}. */
    record Dml(Statement statement, String alias) implements TableRef {
    }

    /** 테이블 자리에 있지만 해석하지 못한 것. */
    record Unparsed(String text) implements TableRef {
    }
}
