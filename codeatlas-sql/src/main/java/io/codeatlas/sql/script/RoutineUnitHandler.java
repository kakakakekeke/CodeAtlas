package io.codeatlas.sql.script;

/**
 * 루틴 단위의 본문을 분석하는 확장 지점. {@code SqlAnalyzer}가 루틴 단위마다(파일 순서대로) 한 번 부른다.
 * 부르기 전에 모든 파일의 DDL이 카탈로그에 들어가 있고, 모든 루틴·패키지 노드(멤버 포함)가 선언돼 있다.
 *
 * <p>{@code SqlAnalyzer}의 기본 핸들러는 본문을 분석하는 {@code io.codeatlas.sql.routine.RoutineBodyHandler}(F-008)다.
 * {@link HeaderOnlyRoutineHandler}는 본문을 분석하지 않고 INFO 진단만 남긴다(헤더만 필요할 때 명시해서 쓴다).
 * 구현이 던진 예외는 {@code SqlAnalyzer}가 잡아 그 단위만 실패로 기록한다.
 */
@FunctionalInterface
public interface RoutineUnitHandler {

    void handle(RoutineUnit unit, RoutineContext context);
}
