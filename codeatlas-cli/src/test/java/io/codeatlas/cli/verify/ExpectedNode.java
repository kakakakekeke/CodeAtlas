package io.codeatlas.cli.verify;

import io.codeatlas.core.model.NodeKind;
import java.util.Map;

/**
 * must-have-nodes.json의 항목 하나.
 *
 * @param source 항목이 있던 파일 이름
 * @param index 그 파일 안의 순번(0부터)
 * @param attrs 기대 속성. 없으면 빈 맵
 */
public record ExpectedNode(String source, int index, String id, NodeKind kind, Map<String, Object> attrs, boolean lowCertainty, String note) {
}
