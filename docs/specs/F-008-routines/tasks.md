---
type: tasks
id: F-008
updated: 2026-10-11
---

# F-008 프로시저 분석 작업 목록

테스트: `mvn -q -pl codeatlas-sql test`

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-008-AC1 테스트 → 루틴 AST(`ast/Routine`)와 본문 파서(`parser/RoutineParser`): 블록, 선언부, 제어 구조, 레이블, 핸들러, `$IF` 건너뛰기. 렉서의 `L1:LOOP` 레이블 수정(테스트 먼저) (F-008-R1)
- [x] T2. F-008-AC2, AC3 테스트 → 본문 핸들러(`routine/RoutineBodyHandler`): 구문 노드, CONTAINS, READS/WRITES/JOINS, 커서·루프·식 안의 쿼리 (F-008-R2, R3)
- [x] T3. F-008-AC4, AC10 테스트 → 변수 스코프, 패키지 수준 변수(카탈로그), 트리거 상관 이름 (F-008-R4, R10)
- [x] T4. F-008-AC5, AC6, AC7 테스트 → 호출 판정, 패키지 내부 호출, 지역 서브프로그램, 구문 안의 함수 호출 (F-008-R5, R6, R7)
- [x] T5. F-008-AC8, AC9 테스트 → 동적 SQL 풀기와 미해결 기록 (F-008-R8, R9)
- [x] T6. F-008-AC11 테스트 → 익명 블록(SQL 파일, 내장 소스)을 본문 파서로 교체, `SqlParser.block()` 위임 (F-008-R11)
- [x] T7. F-008-AC12 테스트(퍼즈성 포함) → 오류 복구, 진단, 루틴 단위 격리 (F-008-R12)
- [x] T8. F-008-AC13 테스트 → 루틴 노드 attrs 보강 (F-008-R13)
- [x] T9. F-008-AC14 테스트 → `SqlAnalyzer` 기본 핸들러 교체, samples/sample-shop 정답 대조 (F-008-R2, R5, R9): 루틴 관련 must-have 엣지 45개 전부 구문 ID까지 일치, DB·루틴 관련 must-not 규칙 30개 위반 0, U5·U6 미해결 기록
- [x] T10. (추가) 전이 테이블, BEGIN 없는 트리거 본문, 키워드와 같은 이름의 호출, `SET ROW = 레코드`, 패키지 멤버 격리(`BlockTracker`의 괄호 복구), `parameters` 표기 정리 (F-008-R5, R10, R12, R13)
- [x] T11. (감사 후속 U5) F-008-AC15 테스트(`RoutineAnalyzerTest.typeRefs`, `SampleShopRoutineTest.typeRefsOfSampleRoutines`) → 실패 확인 → `RoutineBodyHandler.typeRefs`(토큰 스캔)와 루틴·패키지 노드 attrs (F-008-R14)
- [x] T12. (감사 후속) F-008-AC16 테스트(`RoutineAnalyzerTest.sequencesIn*`, `sequenceInAnonymousBlock`, `SampleShopRoutineTest.sequenceInSetStatement`) → 실패 확인 → `BodyWalker.sequences`, `Routine.Assign.tokens`, `StatementFacts.sequence` 공개 (F-008-R15). `SampleShopRoutineTest.cleanParse`의 `SP_SETTLE_MONTHLY.statementCount` 기대값을 6 → 7로 고쳤다
- [x] T13. (공개 프로젝트 검증 후속) F-006-R16의 영향 확인: `RoutineParserTest.brokenBodies`의 종결자 빠진 `UPDATE … DELETE …` 기대를 `Sequence(Update, Delete)`로 고침(둘 다 잡힌다). F-006-R15(데이터 적재 묶기)가 루틴 본문·익명 블록에 적용되지 않음을 `DataLoadTest.embeddedAndRoutineInsertsAreNotGrouped`로 확인. 루틴 관련 샘플 정답(must-have 45, must-not 30) 그대로 통과

- [x] T14. (대상 방언 점검 2차) F-008-AC17 테스트(`RoutineImplicitVariableTest.db2ForLoopColumnsAreVariables`·`db2ForLoopStar`·`recordFieldIsAConstantForFixedColumns`) → 실패 확인(DB2 `FOR` 본문의 `SRC_ID`·`UP_NM`·`PARENT_ID`·`ID`·`ST`에 `UNKNOWN_COLUMN` 6건, 레코드 필드와 비교한 컬럼의 고정 값 없음) → `Routine.Loop.columnsInScope`, `RoutineParser.forLoop`, `BodyWalker.declareQueryColumns`, `VarScope.markOpen`·`isOpen`, `FactExtractor.constant`(레코드 필드) (F-008-R16)
- [x] T15. (대상 방언 점검 2차) F-008-AC17 점검 테스트(`RoutineImplicitVariableTest.plsqlRecordsAndSpecialNames`·`specialVariablesAndTriggers`): PL/SQL 커서 FOR 루프 레코드, `%ROWTYPE`·`RECORD` 필드, `RETURNING`/`BULK COLLECT INTO` 대상, `SQL%ROWCOUNT`·`SQLERRM`·`SQLCODE`, DB2 `SQLCODE`·`SQLSTATE`·`CONDITION`·`GET DIAGNOSTICS`, 트리거의 `:NEW`/`:OLD`/`INSERTING`/`UPDATING`/`DELETING`과 상관 이름 — 처음부터 통과했다(전부터 변수·의사 식별자로 다뤘다. 회귀 방지로 고정) (F-008-R16)
- [x] T16. (대상 방언 점검 2차) F-008-AC18 테스트(`RoutineImplicitVariableTest.undeclaredColumnsInRoutineBody`·`assignedNamesAreVariables`·`noGuessWhenBodyHasParseProblems`) → 실패 확인(참조 없이 진단만, 선언을 못 본 패키지 변수와 놓친 선언의 변수에 `UNKNOWN_COLUMN`) → `FactExtractor.undeclared`(루틴 본문 제한 삭제)·`scopeComplete`, `BodyWalker.assignedNames`·`scopeReliable`, `RoutineBodyHandler`(본문 파서의 문제 여부 전달) (F-008-R17). `UnknownColumnTest.routineBody`의 기대를 "참조 없음·진단 2건" → "`TB_EMP.NO_COL` 참조 1개·진단 1건(테이블이 여럿인 구문)"으로 고쳤다(F-006-R31의 문장도 함께 고쳤다). samples/sample-shop의 mybatis+sql 그래프는 수정 전후가 같다

## 마무리
- [x] 대상 방언 점검 2차(T14~T16) 뒤 `RoutineImplicitVariableTest` 8건 포함 sql 모듈 테스트 1,324개 통과(같은 날 재검증 3차 후속까지 넣은 뒤 1,347개)
- [x] 모든 수용 기준 통과 확인 (sql 모듈 테스트 283개 통과: F-008 완료 시점 265 + 감사 후속 18)
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음: 모듈 구조는 그대로. sql 모듈에 패키지 `routine`, 클래스 `parser.RoutineParser`, `ast.Routine` 추가)
