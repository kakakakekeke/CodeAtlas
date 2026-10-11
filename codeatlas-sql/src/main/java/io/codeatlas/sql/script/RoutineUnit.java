package io.codeatlas.sql.script;

/**
 * 루틴 단위 하나(CREATE PROCEDURE/FUNCTION/PACKAGE [BODY]/TRIGGER)와 헤더에서 읽은 정보.
 *
 * @param unit 단위의 토큰과 원문. 토큰 위치는 파일 기준이다
 * @param header 헤더(종류, 이름, 매개변수, 패키지 멤버와 그 토큰 범위)
 * @param file 파일의 상대 경로
 * @param nodeId 이 단위의 그래프 노드 ID: 루틴이면 {@code db:routine:..}, 패키지면 {@code db:package:..}.
 *               패키지 멤버의 ID는 {@code SqlIds.routine(패키지 이름, 멤버 이름)}으로 만든다. 노드는 이미 선언돼 있다
 */
public record RoutineUnit(ScriptUnit unit, RoutineHeader header, String file, String nodeId) {
}
