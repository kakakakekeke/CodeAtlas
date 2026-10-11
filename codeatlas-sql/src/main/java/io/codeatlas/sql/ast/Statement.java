package io.codeatlas.sql.ast;

import io.codeatlas.sql.ast.Query.Cte;
import java.util.List;

/** 구문 하나. DML 구문은 여기, DDL 구문은 {@link Ddl}에 있다. */
public interface Statement {

    record Select(Query query) implements Statement {
    }

    /** INSERT 대상 하나({@code INSERT ALL}이면 여러 개). */
    record InsertTarget(TableRef target, List<QName> columns, List<List<Expr>> values) {
        public InsertTarget {
            columns = List.copyOf(columns);
            values = List.copyOf(values);
        }
    }

    /**
     * {@code conditions}는 {@code INSERT FIRST/ALL WHEN ..}의 조건, {@code source}는 SELECT 원본(없으면 null).
     * {@code conflictAssignments}는 PostgreSQL {@code ON CONFLICT .. DO UPDATE SET ..}의 대입 항목, {@code conflictConditions}는 그 절의
     * {@code WHERE} 조건들이다(F-019-R12). 절이 없으면 둘 다 비어 있다.
     */
    record Insert(List<InsertTarget> targets, Query source, List<Expr> conditions, List<Expr> returning,
            List<Assignment> conflictAssignments, List<Expr> conflictConditions) implements Statement {
        public Insert {
            targets = List.copyOf(targets);
            conditions = List.copyOf(conditions);
            returning = List.copyOf(returning);
            conflictAssignments = List.copyOf(conflictAssignments);
            conflictConditions = List.copyOf(conflictConditions);
        }

        public Insert(List<InsertTarget> targets, Query source, List<Expr> conditions, List<Expr> returning) {
            this(targets, source, conditions, returning, List.of(), List.of());
        }
    }

    /** {@code col = expr} 또는 {@code (c1, c2) = (subquery)}. */
    record Assignment(List<QName> columns, Expr value) {
        public Assignment {
            columns = List.copyOf(columns);
        }
    }

    record Update(TableRef target, List<Assignment> assignments, List<TableRef> from, Expr where, List<Expr> returning) implements Statement {
        public Update {
            assignments = List.copyOf(assignments);
            from = List.copyOf(from);
            returning = List.copyOf(returning);
        }
    }

    /** {@code from}은 대상 말고 함께 읽는 테이블({@code DELETE 별칭 FROM t 별칭 JOIN u ..}의 나머지, F-006-R21). 보통은 비어 있다. */
    record Delete(TableRef target, List<TableRef> from, Expr where, List<Expr> returning, List<TableRef> moreTargets) implements Statement {
        public Delete {
            from = List.copyOf(from);
            returning = List.copyOf(returning);
            moreTargets = List.copyOf(moreTargets);
        }

        /** moreTargets: MySQL의 {@code DELETE a, b FROM a JOIN b ..}에서 첫 대상 말고 함께 지우는 테이블(F-006-R36). 보통은 비어 있다. */
        public Delete(TableRef target, List<TableRef> from, Expr where, List<Expr> returning) {
            this(target, from, where, returning, List.of());
        }

        public Delete(TableRef target, Expr where, List<Expr> returning) {
            this(target, List.of(), where, returning);
        }
    }

    /** MERGE의 WHEN 절 하나. kind: {@code UPDATE, INSERT, DELETE}. */
    record MergeAction(boolean matched, Expr condition, String kind, List<Assignment> assignments,
            List<QName> insertColumns, List<Expr> insertValues, Expr where, Expr deleteWhere) {
        public MergeAction {
            assignments = List.copyOf(assignments);
            insertColumns = List.copyOf(insertColumns);
            insertValues = List.copyOf(insertValues);
        }
    }

    record Merge(TableRef target, TableRef source, Expr on, List<MergeAction> actions) implements Statement {
        public Merge {
            actions = List.copyOf(actions);
        }
    }

    /** {@code CALL}, {@code EXEC}, {@code {call ..}}, 블록 안의 프로시저 호출. */
    record Call(QName routine, List<Expr> args) implements Statement {
        public Call {
            args = List.copyOf(args);
        }
    }

    record Truncate(QName table) implements Statement {
    }

    /** PostgreSQL {@code COPY 테이블 [(컬럼, ..)] FROM stdin}: 뒤따르는 데이터 줄을 그 테이블에 넣는다(F-019-R23). 컬럼 목록이 없으면 비어 있다. */
    record Copy(QName table, List<QName> columns) implements Statement {
        public Copy {
            columns = List.copyOf(columns);
        }
    }

    record LockTable(List<QName> tables) implements Statement {
        public LockTable {
            tables = List.copyOf(tables);
        }
    }

    /** 앞에 WITH 절이 붙은 DML. */
    record WithDml(List<Cte> ctes, Statement dml) implements Statement {
        public WithDml {
            ctes = List.copyOf(ctes);
        }
    }

    /**
     * 익명 블록({@code BEGIN .. END}). 본문 파서({@link Routine})로 읽은 뒤 안에서 찾은 SQL 구문과 프로시저 호출문만 담는다.
     * {@code dynamic}이면 동적 SQL 실행(EXECUTE IMMEDIATE, PREPARE, OPEN FOR 식)이 있었다.
     */
    record Block(List<Statement> statements, boolean dynamic) implements Statement {
        public Block {
            statements = List.copyOf(statements);
        }
    }

    /**
     * 한 텍스트에 종결자 없이(또는 {@code ;}로) 연달아 든 구문들(F-006-R16). MyBatis의 배타적 {@code <if>}마다 완결된 문장이 든
     * 구문을 전개하면 이렇게 된다. 사실은 같은 주인에 합친다.
     */
    record Sequence(List<Statement> statements) implements Statement {
        public Sequence {
            statements = List.copyOf(statements);
        }
    }

    /** 알아봤지만 뽑을 것이 없는 구문(COMMIT, SET 등). {@code dynamic}이면 동적 SQL 실행이다. */
    record Other(String keyword, boolean dynamic) implements Statement {
    }

    /** 해석하지 못한 구문. */
    record Unparsed(String keyword) implements Statement {
    }

    /** 토큰이 없는 빈 구문. */
    record Empty() implements Statement {
    }
}
