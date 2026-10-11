package io.codeatlas.cli.verify;

import io.codeatlas.core.model.EdgeKind;
import java.util.List;
import java.util.Map;

/**
 * must-have-edges*.json, must-not-have-edges*.json의 항목 하나. 필드의 뜻은 samples/sample-shop/README.md 5절을 따른다.
 *
 * @param source 항목이 있던 파일 이름
 * @param index 그 파일 안의 순번(0부터)
 * @param from 출발 노드 ID. 함정에서는 와일드카드일 수 있다. 요청 형태의 MATCHES 항목이면 null
 * @param kind 엣지 종류 이름. 함정에서는 {@code *}일 수 있다
 * @param line fromFile과 함께 쓰는 줄 번호. 없으면 0
 */
public record ExpectedEdge(
        String source, int index, String from, String to, String kind,
        List<String> altFrom, List<String> altTo, Map<String, Object> attrs,
        boolean undirected, String statement, String statementRoutine, String fromRoutine,
        String fromFile, int line, String httpMethod, String requestUrl, String rawUrl,
        boolean lowCertainty, String note) {

    /** {@code fromFile/requestUrl/httpMethod → to} 형태의 MATCHES 항목인가. */
    public boolean isRequestMatch() {
        return from == null && fromFile != null;
    }

    /** 종류가 와일드카드가 아니면 그 EdgeKind, 와일드카드면 null. */
    public EdgeKind edgeKind() {
        return "*".equals(kind) ? null : EdgeKind.valueOf(kind);
    }

    /** 리포트용 출발 쪽 표기. */
    public String fromLabel() {
        if (isRequestMatch()) {
            return fromFile + (line > 0 ? ":" + line : "") + " " + httpMethod + " " + requestUrl;
        }
        return from;
    }
}
