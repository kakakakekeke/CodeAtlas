package io.codeatlas.cli.verify;

/** 정답 항목 하나의 판정. */
public enum Verdict {
    /** 있다 (RESOLVED 엣지, 또는 노드). */
    PASS,
    /** 있지만 신뢰도가 INFERRED인 엣지다. 통과로 치되 따로 센다. */
    INFERRED_PASS,
    FAIL;

    public boolean passed() {
        return this != FAIL;
    }
}
