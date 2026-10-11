package io.codeatlas.cli.verify;

/**
 * must-have 노드 항목의 판정.
 *
 * @param reason 실패 사유. 통과면 null
 * @param detail 속성 차이 등 보충 설명. 없으면 빈 문자열
 */
public record NodeResult(ExpectedNode expected, Verdict verdict, FailReason reason, String detail) {
}
