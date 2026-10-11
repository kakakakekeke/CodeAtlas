package io.codeatlas.sql.ast;

import java.util.List;

/** DDL 구문. 이름은 원문 그대로 들고 있고 정규화는 쓰는 쪽에서 한다. */
public interface Ddl extends Statement {

    /**
     * 컬럼 정의(또는 ALTER의 변경 내용). ALTER ... MODIFY처럼 일부만 주어지면 주어지지 않은 항목은 null이다.
     *
     * @param notNull NOT NULL이면 true, NULL이면 false, 지정이 없으면 null
     * @param reference 인라인 REFERENCES 제약(없으면 null)
     */
    record ColumnDef(String name, String dataType, Boolean notNull, String defaultValue, boolean primaryKey,
            boolean unique, boolean identity, boolean virtual, Constraint reference, Pos pos) {
    }

    /**
     * 제약. kind: {@code PRIMARY KEY, UNIQUE, FOREIGN KEY, CHECK}, MySQL의 테이블 수준 인덱스 절 {@code INDEX}({@code KEY 이름 (cols)}),
     * 기록하지 않는 절 {@code OTHER}(전문·공간 인덱스). FK가 아니면 refTable은 null.
     */
    record Constraint(String kind, String name, List<String> columns, QName refTable, List<String> refColumns,
            String onDelete, Pos pos) {
        public Constraint {
            columns = List.copyOf(columns);
            refColumns = List.copyOf(refColumns);
        }
    }

    /** {@code asQuery}는 CREATE TABLE ... AS SELECT의 쿼리(없으면 null). */
    record CreateTable(QName name, List<ColumnDef> columns, List<Constraint> constraints, Query asQuery,
            boolean temporary) implements Ddl {
        public CreateTable {
            columns = List.copyOf(columns);
            constraints = List.copyOf(constraints);
        }
    }

    sealed interface AlterAction {
    }

    record AddColumn(ColumnDef column) implements AlterAction {
    }

    record ModifyColumn(ColumnDef column) implements AlterAction {
    }

    record AddConstraint(Constraint constraint) implements AlterAction {
    }

    record DropColumn(String name) implements AlterAction {
    }

    record RenameColumn(String from, String to) implements AlterAction {
    }

    record AlterTable(QName name, List<AlterAction> actions) implements Ddl {
        public AlterTable {
            actions = List.copyOf(actions);
        }
    }

    /** target: {@code TABLE} 또는 {@code COLUMN}. COLUMN이면 name의 마지막 조각이 컬럼이다. */
    record CommentOn(String target, QName name, String text) implements Ddl {
    }

    /**
     * columns에는 단순 컬럼 이름만, expressions에는 인덱스 항목 전체의 표시용 텍스트가 들어간다.
     * 내림차순 항목은 표시용 텍스트 끝에 {@code " DESC"}가 붙는다({@code CHG_DT DESC}, {@code UPPER(NM) DESC}). 두 목록의 항목 수가 같으면 식 인덱스가 아니다.
     */
    record CreateIndex(QName name, boolean unique, QName table, List<String> columns, List<String> expressions) implements Ddl {
        public CreateIndex {
            columns = List.copyOf(columns);
            expressions = List.copyOf(expressions);
        }
    }

    record CreateView(QName name, List<String> columns, Query query, boolean materialized) implements Ddl {
        public CreateView {
            columns = List.copyOf(columns);
        }
    }

    record CreateSequence(QName name) implements Ddl {
    }

    record CreateSynonym(QName name, QName target, boolean isPublic) implements Ddl {
    }

    /** 한 구문이 여러 DDL로 풀리는 경우(DB2 {@code COMMENT ON t (c1 IS .., c2 IS ..)}). */
    record Multi(List<Ddl> statements) implements Ddl {
        public Multi {
            statements = List.copyOf(statements);
        }
    }
}
