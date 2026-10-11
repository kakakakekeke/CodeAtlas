package io.codeatlas.cli.verify;

/** 정답 항목이 실패한 사유 분류. */
public enum FailReason {
    FROM_NODE_MISSING("출발 노드 없음"),
    TO_NODE_MISSING("대상 노드 없음"),
    BOTH_NODES_MISSING("양쪽 노드 없음"),
    EDGE_MISSING("양쪽 노드는 있는데 엣지 없음"),
    EDGE_UNRESOLVED("엣지는 있으나 UNRESOLVED"),
    ATTR_MISMATCH("attrs 불일치"),
    NODE_MISSING("노드 없음"),
    KIND_MISMATCH("노드 종류 불일치");

    private final String label;

    FailReason(String label) {
        this.label = label;
    }

    /** 리포트에 쓰는 한국어 이름. */
    public String label() {
        return label;
    }
}
