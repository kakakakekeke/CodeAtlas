package io.codeatlas.export.erd;

import java.util.List;
import java.util.Optional;

/**
 * 형식과 무관한 ERD 중간 모델. 엔티티는 이름순, 컬럼은 ordinal순, 관계는 (자식, 부모, 컬럼, 제약 이름)순이다.
 *
 * @param unresolvedNames {@link ErdOptions#tables()}로 준 시작 테이블 이름 중 범위의 후보(테이블, 뷰를 넣었으면 뷰)에서
 *                        찾지 못한 것. 준 순서대로, 원문 그대로, 중복 없이
 * @param excludedViewNames unresolvedNames 가운데 뷰의 이름이라서({@code includeViews=false}) 빠진 것.
 *                          {@code includeViews(true)}를 주면 범위에 든다
 * @param systemCatalogNames 후보에서 뺀 시스템 카탈로그(DB가 제공하는 데이터 사전: {@code attrs.systemCatalog=true}) 테이블·뷰의 이름(이름순).
 *                           프로젝트 스키마가 아니라서 엔티티로도 관계의 끝으로도 넣지 않는다(F-013-R29)
 */
public record ErdModel(List<Entity> entities, List<Relation> relations, List<String> unresolvedNames, List<String> excludedViewNames,
        List<String> systemCatalogNames) {

    public ErdModel {
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
        unresolvedNames = unresolvedNames == null ? List.of() : List.copyOf(unresolvedNames);
        excludedViewNames = excludedViewNames == null ? List.of() : List.copyOf(excludedViewNames);
        systemCatalogNames = systemCatalogNames == null ? List.of() : List.copyOf(systemCatalogNames);
    }

    /** 뺀 시스템 카탈로그가 없는 모델. */
    public ErdModel(List<Entity> entities, List<Relation> relations, List<String> unresolvedNames, List<String> excludedViewNames) {
        this(entities, relations, unresolvedNames, excludedViewNames, List.of());
    }

    /** 찾지 못한 이름이 없는 모델. */
    public ErdModel(List<Entity> entities, List<Relation> relations) {
        this(entities, relations, List.of(), List.of());
    }

    /** 엔티티 종류. */
    public enum EntityKind { TABLE, VIEW }

    /** 관계 한쪽 끝의 개수. */
    public enum Cardinality {
        /** 정확히 하나. */
        ONE("1"),
        /** 없거나 하나. */
        ZERO_OR_ONE("0..1"),
        /** 여럿(0 이상). */
        MANY("N"),
        /** 모름(방향을 모르는 추론 관계, 그리고 방향만 알고 카디널리티는 정하지 못한 추론 관계). */
        UNKNOWN("?");

        private final String label;

        Cardinality(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param id 노드 ID
     * @param declared false면 DDL에 없고 SQL에서만 참조된 테이블
     * @param columns 표시 수준 옵션을 적용한 컬럼
     * @param totalColumns 옵션 적용 전 컬럼 수
     */
    public record Entity(String id, String name, EntityKind kind, boolean declared, String schema, String comment,
            List<Column> columns, int totalColumns) {
        public Entity {
            columns = List.copyOf(columns);
        }

        public Optional<Column> column(String columnName) {
            return columns.stream().filter(c -> c.name().equals(columnName)).findFirst();
        }
    }

    /**
     * @param dataType 없으면 null
     * @param nullable 모르면 null
     * @param foreignKey DDL 외래 키에 쓰인 컬럼
     * @param ordinal 없으면 0
     * @param referencedOnly 정의는 없고 SQL이나 관계에서만 참조된 컬럼
     */
    public record Column(String name, String dataType, Boolean nullable, boolean primaryKey, boolean foreignKey, boolean unique,
            int ordinal, String defaultValue, String comment, boolean referencedOnly) {

        /** NOT NULL이 확실하면 true(PK 포함). */
        public boolean notNull() {
            return primaryKey || Boolean.FALSE.equals(nullable);
        }
    }

    /**
     * 자식 → 부모 관계.
     *
     * @param child 자식 엔티티 이름
     * @param parent 부모 엔티티 이름
     * @param constraint 제약 이름. 없으면 null
     * @param inferred 추론 관계면 true
     * @param evidenceCount 추론 근거 수. 모르면 0
     * @param directionKnown false면 어느 쪽이 부모인지 모른다
     * @param childSide 자식 쪽 개수. 방향을 모르거나 카디널리티를 모르면 {@link Cardinality#UNKNOWN}
     * @param parentSide 부모 쪽 개수. 방향을 모르거나 카디널리티를 모르면 {@link Cardinality#UNKNOWN}
     * @param directionNote 방향을 정했다가 거둔 까닭(링커의 {@code declaredOpposite}: 선언된 FK와 반대 방향, {@code pseudoKey}: 부모 쪽
     *        키가 유일하지 않음). 없으면 null
     * @param fromUpgrade upgrade·patch 스크립트의 {@code ALTER TABLE}에서 온 외래 키({@code REFERENCES}의 {@code fromUpgrade=true}).
     *        DDL 관계로 그리되 라벨에 {@code (upgrade 스크립트)}를 적는다. 현재 정의에 없는 것은 추론 관계({@code inferred=true})로 오고
     *        점선에 {@code (upgrade 스크립트, 현재 정의에 없음)}을 적는다
     * @param directionFrom 조인만으로는 방향을 정하지 못해 다른 근거를 따랐으면 그 근거(링커의 {@code directionFrom}. 값 {@code DDL}: 같은
     *        테이블 쌍의 DDL 외래 키 방향). 없으면 null
     */
    public record Relation(String child, String parent, String childId, String parentId, List<String> columns, List<String> refColumns,
            String constraint, boolean inferred, int evidenceCount, boolean directionKnown, Cardinality childSide,
            Cardinality parentSide, String directionNote, boolean fromUpgrade, String directionFrom) {

        public Relation(String child, String parent, String childId, String parentId, List<String> columns, List<String> refColumns,
                String constraint, boolean inferred, int evidenceCount, boolean directionKnown, Cardinality childSide,
                Cardinality parentSide, String directionNote, boolean fromUpgrade) {
            this(child, parent, childId, parentId, columns, refColumns, constraint, inferred, evidenceCount, directionKnown, childSide,
                    parentSide, directionNote, fromUpgrade, null);
        }

        public Relation(String child, String parent, String childId, String parentId, List<String> columns, List<String> refColumns,
                String constraint, boolean inferred, int evidenceCount, boolean directionKnown, Cardinality childSide,
                Cardinality parentSide) {
            this(child, parent, childId, parentId, columns, refColumns, constraint, inferred, evidenceCount, directionKnown, childSide,
                    parentSide, null, false);
        }

        public Relation(String child, String parent, String childId, String parentId, List<String> columns, List<String> refColumns,
                String constraint, boolean inferred, int evidenceCount, boolean directionKnown, Cardinality childSide,
                Cardinality parentSide, String directionNote) {
            this(child, parent, childId, parentId, columns, refColumns, constraint, inferred, evidenceCount, directionKnown, childSide,
                    parentSide, directionNote, false);
        }

        public Relation {
            columns = List.copyOf(columns);
            refColumns = List.copyOf(refColumns);
        }

        /**
         * 방향(자식 → 부모)은 알지만 카디널리티는 모르는 관계인가(추론 관계의 {@code directionKnown=true}, {@code cardinality=unknown}).
         * 렌더러는 방향을 지키되 N:1이나 1:1로 단정하는 표기를 쓰지 않는다. 방향 미상({@code directionKnown=false})과는 다르다.
         */
        public boolean cardinalityUnknown() {
            return directionKnown && childSide == Cardinality.UNKNOWN;
        }
    }

    public Optional<Entity> entity(String name) {
        return entities.stream().filter(e -> e.name().equals(name)).findFirst();
    }
}
