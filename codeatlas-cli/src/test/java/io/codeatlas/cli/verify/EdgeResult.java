package io.codeatlas.cli.verify;

import io.codeatlas.core.model.Edge;

/**
 * must-have 엣지 항목의 판정.
 *
 * @param reason 실패 사유. 통과면 null
 * @param detail 사람이 읽는 보충 설명(느슨한 통과의 근거, 속성 차이, 그 파일의 요청 목록 등). 없으면 빈 문자열
 * @param matched 판정의 근거가 된 그래프의 엣지. 엣지를 찾지 못했으면 null
 * @param loose README가 허용한 느슨한 규칙(fromRoutine, fromFile의 줄, statement 불일치)으로 통과했으면 true
 * @param attrsMismatched 엣지는 있는데 정답의 attrs와 다르면 true (엄격 모드에서는 실패, 느슨한 모드에서는 통과)
 */
public record EdgeResult(ExpectedEdge expected, Verdict verdict, FailReason reason, String detail, Edge matched, boolean loose, boolean attrsMismatched) {
}
