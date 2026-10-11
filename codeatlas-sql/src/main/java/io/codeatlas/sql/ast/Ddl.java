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

    /**
     * 다른 테이블의 컬럼을 가져오는 절(F-019-R20): {@code (LIKE 원본 [INCLUDING ..])}, {@code INHERITS (부모, ..)}, {@code PARTITION OF 부모}.
     *
     * @param position {@link Kind#LIKE}가 놓인 자리: 그 앞에 적힌 자기 컬럼의 수. 다른 종류는 0이다(부모의 컬럼이 자기 컬럼보다 앞이다)
     * @param defaults 원본의 기본값도 가져오는가. LIKE는 {@code INCLUDING DEFAULTS|ALL}일 때만이다
     * @param keys 원본의 기본 키·유일 표시도 가져오는가({@code LIKE .. INCLUDING INDEXES|ALL}). 상속과 파티션은 가져오지 않는다
     * @param identity 원본의 identity 표시도 가져오는가({@code LIKE .. INCLUDING IDENTITY|ALL})
     */
    record ColumnSource(Kind kind, QName table, int position, boolean defaults, boolean keys, boolean identity, Pos pos) {

        public enum Kind {
            LIKE, INHERITS, PARTITION_OF
        }
    }

    /**
     * {@code asQuery}는 CREATE TABLE ... AS SELECT의 쿼리(없으면 null). {@code sources}는 컬럼을 가져올 다른 테이블(적힌 순서. 없으면 빈 목록).
     */
    record CreateTable(QName name, List<ColumnDef> columns, List<Constraint> constraints, Query asQuery,
            boolean temporary, List<ColumnSource> sources) implements Ddl {
        public CreateTable {
            columns = List.copyOf(columns);
            constraints = List.copyOf(constraints);
            sources = List.copyOf(sources);
        }

        /** 파티션이면 부모 테이블({@code PARTITION OF 부모}), 아니면 null. */
        public QName partitionOf() {
            for (ColumnSource s : sources) {
                if (s.kind() == ColumnSource.Kind.PARTITION_OF) {
                    return s.table();
                }
            }
            return null;
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

    /** PostgreSQL {@code ALTER TABLE 부모 ATTACH PARTITION 자식 ..}(pg_dump가 파티션을 내는 꼴, F-019-R20). 부모가 ALTER의 대상이다. */
    record AttachPartition(QName partition) implements AlterAction {
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
     *
     * @param name 인덱스 이름. PostgreSQL의 이름 없는 {@code CREATE INDEX ON t (..)}면 null이다(F-019-R21)
     * @param partial 조건이 붙은 부분 인덱스인가({@code .. WHERE 조건}). 부분 유일 인덱스는 테이블 전체의 유일 키가 아니다
     */
    record CreateIndex(QName name, boolean unique, QName table, List<String> columns, List<String> expressions, boolean partial) implements Ddl {
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
