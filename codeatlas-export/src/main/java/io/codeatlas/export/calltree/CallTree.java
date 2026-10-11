package io.codeatlas.export.calltree;

/**
 * 콜트리 조회 결과.
 *
 * @param root 시작 노드
 * @param nodeCount 트리의 줄 수(시작 노드 포함)
 * @param truncated 노드 수 상한에 닿아 일부를 생략했으면 true
 */
public record CallTree(CallTreeNode root, CallTreeOptions options, int nodeCount, boolean truncated) {

    public CallTreeOptions.Direction direction() {
        return options.direction();
    }

    /** 깊이 제한({@link CallTreeOptions#maxDepth()})에 걸려 펼치지 못한 노드가 하나라도 있으면 true. */
    public boolean depthLimited() {
        return depthLimited(root);
    }

    private static boolean depthLimited(CallTreeNode node) {
        if (node.markers().contains(CallTreeNode.Marker.DEPTH_LIMIT)) {
            return true;
        }
        for (CallTreeNode child : node.children()) {
            if (depthLimited(child)) {
                return true;
            }
        }
        return false;
    }
}
