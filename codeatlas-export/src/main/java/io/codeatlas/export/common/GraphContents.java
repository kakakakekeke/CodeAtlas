package io.codeatlas.export.common;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;

/**
 * 그래프에 산출물의 대상이 있는지. 표준 출력기는 대상이 없는 산출물을 빈 파일로 쓰지 않고 생략하고,
 * 요약 리포트는 같은 판단으로 "없음"을 한 줄 적는다.
 */
public final class GraphContents {

    private GraphContents() {
    }

    /** 테이블이나 뷰가 하나라도 있으면 true(DDL에 없이 SQL에서 참조만 된 테이블 포함). ERD, 테이블 명세, CRUD 매트릭스의 대상이다. */
    public static boolean hasDbObjects(GraphModel graph) {
        return !graph.nodes(NodeKind.DB_TABLE).isEmpty() || !graph.nodes(NodeKind.DB_VIEW).isEmpty();
    }

    /** 소스에서 정의를 본 Java 타입이 하나라도 있으면 true(참조만 된 타입과 라이브러리 타입은 세지 않는다). 클래스 다이어그램의 대상이다. */
    public static boolean hasJavaTypes(GraphModel graph) {
        for (Node type : graph.nodes(NodeKind.JAVA_TYPE)) {
            if (type.declared() && !type.attrBoolean("external")) {
                return true;
            }
        }
        return false;
    }

    /** 엔드포인트가 하나라도 있으면 true. 엔드포인트 콜트리의 대상이다. */
    public static boolean hasEndpoints(GraphModel graph) {
        return !graph.nodes(NodeKind.HTTP_ENDPOINT).isEmpty();
    }

    /** 배치 잡이나 스텝이 하나라도 있으면 true. 배치 잡 콜트리의 대상이다. */
    public static boolean hasBatchJobs(GraphModel graph) {
        return !graph.nodes(NodeKind.BATCH_JOB).isEmpty() || !graph.nodes(NodeKind.BATCH_STEP).isEmpty();
    }
}
