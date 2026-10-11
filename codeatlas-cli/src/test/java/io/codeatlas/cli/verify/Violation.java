package io.codeatlas.cli.verify;

import io.codeatlas.core.model.Edge;
import java.util.List;

/**
 * 함정(must-not) 항목 하나에 걸린 엣지들.
 *
 * @param rule 걸린 함정 항목
 * @param edges 그 항목에 맞는 그래프의 엣지(하나 이상)
 */
public record Violation(ExpectedEdge rule, List<Edge> edges) {
}
