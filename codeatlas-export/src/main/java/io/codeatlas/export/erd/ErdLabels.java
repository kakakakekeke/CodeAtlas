package io.codeatlas.export.erd;

import io.codeatlas.export.erd.ErdModel.Entity;
import io.codeatlas.export.erd.ErdModel.EntityKind;
import io.codeatlas.export.erd.ErdModel.Relation;

/** 렌더러들이 함께 쓰는 표시 문구. */
final class ErdLabels {

    static final String UNDECLARED = "DDL 없음";
    static final String REFERENCED_COLUMN = "참조된 컬럼";
    static final String UNKNOWN_TYPE = "unknown";
    /** upgrade·patch 스크립트의 ALTER TABLE에서 온 외래 키({@code fromUpgrade=true})에 붙이는 표시. */
    static final String FROM_UPGRADE = "upgrade 스크립트";
    /** upgrade 스크립트에는 있는데 현재 정의(전체 DDL)에는 없는 외래 키({@code fromUpgrade=true}이면서 INFERRED)에 덧붙이는 표시. */
    static final String NOT_IN_CURRENT = "현재 정의에 없음";
    /** 방향(자식 → 부모)은 알지만 카디널리티는 정하지 못한 추론 관계({@link Relation#cardinalityUnknown()})에 붙이는 표시. */
    static final String CARDINALITY_UNKNOWN = "카디널리티 모름";
    /** 그 방향을 같은 테이블 쌍의 DDL 외래 키에서 얻었을 때({@code directionFrom=DDL}) 앞에 붙이는 표시. */
    static final String DIRECTION_FROM_DDL = "방향은 DDL";

    /** 추론 관계의 부모 쪽을 0..1로 그리는 까닭(Mermaid·PlantUML의 머리 주석에 덧붙인다). */
    static final String INFERRED_PARENT_OPTIONAL = "추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.";

    private ErdLabels() {
    }

    /** 뺀 시스템 카탈로그가 있을 때 머리 주석에 적는 문장. 없으면 null. */
    static String systemCatalogNote(ErdModel model) {
        int count = model.systemCatalogNames().size();
        return count == 0 ? null : "시스템 카탈로그 " + count + "개(DB가 제공하는 데이터 사전)는 프로젝트 스키마가 아니라서 뺐다.";
    }

    /** 엔티티 표시 이름. DDL에 없는 테이블과 뷰에는 접미 표식을 붙인다. */
    static String displayName(Entity e) {
        if (!e.declared()) {
            return e.name() + " (" + UNDECLARED + ")";
        }
        return e.name();
    }

    static boolean isView(Entity e) {
        return e.kind() == EntityKind.VIEW;
    }

    /**
     * 관계 라벨. DDL 관계는 제약 이름(없으면 컬럼 목록)이고 upgrade 스크립트에서 온 것이면 {@code (upgrade 스크립트)}를 덧붙인다.
     * 추론 관계는 {@code 컬럼 (추론 n건)}, 방향을 모르면 {@code 컬럼 (추론 n건, 방향 미상)}, 방향만 알고 카디널리티를 모르면
     * {@code 컬럼 (추론 n건, 방향은 DDL, 카디널리티 모름)}. 아무것도 없으면 빈 문자열.
     * upgrade 스크립트에만 있고 현재 정의에 없는 외래 키(추론 관계로 온다)는 {@code 제약 이름 또는 컬럼 (upgrade 스크립트, 현재 정의에 없음)}.
     */
    static String relationLabel(Relation r) {
        String columns = String.join(", ", r.columns());
        if (!r.inferred()) {
            String name = r.constraint() != null ? r.constraint() : columns;
            if (!r.fromUpgrade()) {
                return name;
            }
            return name.isEmpty() ? "(" + FROM_UPGRADE + ")" : name + " (" + FROM_UPGRADE + ")";
        }
        String note = inferredNote(r);
        String name = r.fromUpgrade() && r.constraint() != null ? r.constraint() : columns;
        return name.isEmpty() ? "(" + note + ")" : name + " (" + note + ")";
    }

    /** 엣지 attrs에서 방향을 거둔 까닭을 읽는다. 없으면 null. */
    static String directionNote(io.codeatlas.core.model.Edge e) {
        if (e.attrBoolean("declaredOpposite")) {
            return "선언된 FK와 반대";
        }
        return e.attrBoolean("pseudoKey") ? "유일하지 않은 키" : null;
    }

    /**
     * {@code 추론 3건} 또는 {@code 추론 3건, 방향 미상}. 근거 수를 모르면 {@code 추론}. 방향을 정했다가 거둔 까닭이 있으면
     * {@code 방향 미상: 선언된 FK와 반대}처럼 덧붙인다. 현재 정의에 없는 upgrade 외래 키는 근거 수 대신
     * {@code upgrade 스크립트, 현재 정의에 없음}이다(SQL에서 추론한 것이 아니라 스크립트에 적힌 것이라 근거 수가 없다).
     * 방향은 알지만 카디널리티를 모르면 {@code 추론 2건, 카디널리티 모름}이고, 그 방향이 DDL 외래 키에서 왔으면
     * {@code 추론 2건, 방향은 DDL, 카디널리티 모름}이다(F-013-R27).
     */
    static String inferredNote(Relation r) {
        String note = r.fromUpgrade() ? FROM_UPGRADE + ", " + NOT_IN_CURRENT : r.evidenceCount() > 0 ? "추론 " + r.evidenceCount() + "건" : "추론";
        if (!r.directionKnown()) {
            return note + ", 방향 미상" + (r.directionNote() == null ? "" : ": " + r.directionNote());
        }
        if (r.cardinalityUnknown()) {
            return note + ", " + ("DDL".equalsIgnoreCase(r.directionFrom()) ? DIRECTION_FROM_DDL + ", " : "") + CARDINALITY_UNKNOWN;
        }
        return note;
    }
}
