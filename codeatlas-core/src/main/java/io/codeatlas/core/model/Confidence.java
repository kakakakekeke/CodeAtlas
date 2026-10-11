package io.codeatlas.core.model;

/** 엣지의 신뢰도. */
public enum Confidence {
    /** 정적 분석으로 확정한 관계. */
    RESOLVED,
    /** 근거에서 추론한 관계(예: JOIN 조건에서 추론한 FK). 근거 위치를 함께 남긴다. */
    INFERRED,
    /** 해석하지 못한 관계. 버리지 않고 미해결로 남긴다. */
    UNRESOLVED
}
