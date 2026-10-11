---
type: tasks
id: F-009
updated: 2026-10-11
---

# F-009 MyBatis 매퍼 분석 작업 목록

<!-- 이 프로젝트는 TDD로 한다. 작업마다 실패하는 테스트를 먼저 쓰고(RED), 통과시키고(GREEN), 정리한다(REFACTOR).
     T1~T12의 RED는 테스트 51개를 먼저 쓰고 빈 MyBatisAnalyzer로 실행해 전부 실패하는 것으로 확인했다. T13은 단언 실패로 확인했다. -->

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-009-AC1, AC2, AC3 테스트 작성 → 실패 확인 → 위치를 아는 XML 트리 파서, 매퍼·구문·조각 노드와 속성 구현 → 통과 (F-009-R1, R2)
- [x] T2. F-009-AC4, AC5 테스트 작성 → 실패 확인 → 정적 구문의 길이 보존 본문 추출과 내장 소스 등록 구현 → 통과 (F-009-R3)
- [x] T3. F-009-AC6, AC7, AC8 테스트 작성 → 실패 확인 → 동적 태그 전개(if, where, set, trim, foreach, bind) 구현 → 통과 (F-009-R4)
- [x] T4. F-009-AC9, AC10 테스트 작성 → 실패 확인 → choose 분기별 전개본과 상한 구현 → 통과 (F-009-R5)
- [x] T5. F-009-AC11 테스트 작성 → 실패 확인 → 파라미터 치환과 DYNAMIC_SQL 진단 구현 → 통과 (F-009-R6)
- [x] T6. F-009-AC12, AC13, AC14 테스트 작성 → 실패 확인 → include 2패스 해석, property 치환, 미해결·순환 처리 구현 → 통과 (F-009-R7)
- [x] T7. F-009-AC15, AC16 테스트 작성 → 실패 확인 → selectKey 내장 소스와 databaseId 병합 구현 → 통과 (F-009-R8, R9)
- [x] T8. F-009-AC17, AC18, AC19 테스트 작성 → 실패 확인 → 타입 별칭 해석과 USES_TYPE 구현 → 통과 (F-009-R10)
- [x] T9. F-009-AC20 테스트 작성 → 실패 확인 → resultMap 타입·컬럼 대응 구현 → 통과 (F-009-R11)
- [x] T10. F-009-AC21, AC22, AC23 테스트 작성 → 실패 확인 → 파싱 실패 복구, 외부 엔티티 차단, sqlMap 진단 구현 → 통과 (F-009-R12)
- [x] T11. F-009-AC24 현장 패턴 픽스처 테스트 작성 → 실패 확인 → 보완 → 통과 (F-009-R4, R7)
- [x] T12. `META-INF/services` 등록과 ServiceLoader 탐색 테스트 (F-009-R1)
- [x] T13. 본문이 `MERGE INTO`로 시작하는 구문의 `statementType=MERGE` 테스트 추가 → 실패 확인 → 구현 → 통과 (F-009-R2. 계획에 없던 작업. 샘플 정답과 맞추려고 더했다)
- [x] T14. 경계 입력 테스트(EdgeCasesTest: 같은 조각 두 번 include, 조각의 databaseId, 중첩 trim·foreach, BMP 밖 문자, 빈 본문, p 네임스페이스)와 2MB 매퍼 테스트 추가 (F-009-R3, R4, R7, R10, R12. 계획에 없던 작업)

## 공개 프로젝트 검증에서 찾은 문제 (작업마다 재현 테스트 먼저. RED는 새 테스트 14개가 단언 실패하는 것으로 확인했다)
- [x] T15. F-009-AC25, AC26 테스트 작성(NamespaceIdTest) → 실패 확인 → `MapperIds`로 id 한정 규칙 구현(구문, 조각, resultMap) → 통과 (F-009-R13)
- [x] T16. F-009-AC27~AC30 테스트 작성(IfEqualityExpansionTest) → 실패 확인 → 조건식 파서 `IfTest`와 식의 값별 전개본 구현(`SqlExpander`) → 통과 (F-009-R14)
- [x] T17. F-009-AC31~AC33 테스트 작성(DialectVariantTest) → 실패 확인 → `DialectVariants`(방언 읽기, 선호 순서, id별 선택), 매퍼 노드 선언을 1패스 뒤로 옮김, 요약 진단, 옵션 `mybatis.dialectVariant` 구현 → 통과 (F-009-R15)
- [x] T18. F-009-AC34 테스트 작성 → 실패 확인 → 정의가 여러 벌일 때 같은 본문 전개본을 한 번만 등록 → 통과 (F-009-R16)
- [x] T19. F-009-AC35 테스트 작성(StaticPositionTest) → 실패 확인 → `<bind>`를 동적 태그에서 빼고 공백으로 지워 위치 보존 → 통과 (F-009-R17)
- [x] T20. F-009-AC36 테스트 작성(RobustnessTest) → 실패 확인 → 알 수 없는 태그 진단을 구문·조각마다 한 건으로 → 통과 (F-009-R18)
- [x] T21. 공개 프로젝트 실측에서 읽지 못한 조건(`list.get(0).dbName == …`, `equalsIgnoreCase`)을 AC29 테스트에 더함 → 실패 확인 → 파서 보완 → 통과 (F-009-R14)
- [x] (2차 후속) F-009-AC37 진행 상황 훅(`ProgressHookTest`) → 실패 확인 → `ctx.progress` 두 줄 → 통과 (F-009-R19. `mvn -q -pl codeatlas-mybatis clean test`, 76개)
- [x] T22. F-009-AC42~AC44 테스트 작성(ChooseCorrelationTest 7개) → 실패 확인(5개 단언 실패. 무관한 choose와 가려진 분기 2개는 회귀 방지용이라 처음부터 통과) → `<when test>`를 조건의 상태(등치 꼴은 식의 값, 그 밖은 test 문자열의 참·거짓)로 읽어 choose 분기를 고르게 함(`SqlExpander`: `Condition`, `select`, `withBranch`) → 통과 (F-009-R23. `mvn -q -pl codeatlas-mybatis clean test`, 83개. 공개 프로젝트 실측: 공통컴포넌트 `RssTagService.selectRssTagServiceTable` 전개본 5→3, 전체 전개본 합 1627→1625, jeesite 287→287, 상한 초과 진단 0→0, 나머지 5개 프로젝트는 출력이 전과 같다)
- [x] T23. (재검증 N4) F-009-AC38 테스트 작성(`DialectTokenTableTest`: sql 모듈 `DialectTokensTest`와 같은 표) → 실패 확인(10건: `ora`, 버전 숫자, `.`·`_`·`-` 밖의 구분자) → `DialectVariants.canonical`·`dialectOf`를 sql 모듈과 같은 규칙으로, `NAMES`·`PREFERRED` 공개 → 통과 (F-009-R20)
- [x] T24. (재검증 N4) F-009-AC39 테스트 작성(`DialectVariantTest.requestedDialectNotFound`) → 실패 확인 → `DialectVariants.missingRequests`, 진단 `DIALECT_NOT_FOUND`(WARN) → 통과 (F-009-R20)
- [x] T25. (재검증 N5) F-009-AC40 테스트 작성(`DynamicPartsTest`) → 실패 확인 → `SqlParams.Result.originals`·`encodeParts`·`restore`, `SqlExpander.Expansion.variantParts`, 내장 소스 `attrs.dynamicParts`, 구문·조각 `sql`의 원문 복원 → 통과 (F-009-R21). `DynamicSqlExpansionTest.parameterSubstitution`의 `sql` 기대값을 `__DYNAMIC__` → `${tableName}`·`${sortColumn}`으로 고쳤다
- [x] T26. (재검증 N30) F-009-AC41 테스트 작성(`ProjectDialectTableTest`: sql 모듈 `ProjectDialectTest`와 같은 표, `DialectVariantTest.projectSettingDialect`) → 실패 확인 → `ProjectDialect`(sql 모듈과 같은 구현을 따로 둔다), `DialectVariants`의 선호 순서에 설정 방언, 요약 진단의 WARN 경우 → 통과 (F-009-R22)
- [x] T27. (재검증) 남은 `mybatis-statement` 미해결의 원인 점검: enterprise 37건, 공통컴포넌트 12건, simple-backend 9건의 ID를 코퍼스의 모든 매퍼 XML(모든 방언 파일, iBatis sqlMap 포함)에서 찾았다. 58건 모두 어느 파일에도 정의가 없다(공통컴포넌트 2건은 같은 id가 다른 namespace `AdministCodeManageDAO`에만 있다). "고른 방언 파일에 없는 구문을 다음 순위 방언 파일에서 보충"하는 규칙은 동작한다(AC31의 `onlyMy`, AC41의 `onlyMy`). 코드 변경 없음

- [x] (재검증 3차 M4) F-009-AC45 테스트(`DialectPolicyTest.targetDialectBeatsForeignSetting`·`foreignDialectOnlyWhenNoTargetFiles`)와 `DialectVariantTest.projectSettingDialect`의 기대 수정 → 실패 확인(설정이 mysql·hsql이면 그 방언 파일을 고름) → `DialectVariants`(순위, `setting`·`conflicting`·`requested`·`bestDialect`), `ProjectDialect.readAll`·`Reading`, `MyBatisAnalyzer`의 요약 문구 (F-009-R24). 안내의 옵션은 `--opt mybatis.dialectVariant=<방언>`이다
- [x] (재검증 3차 M4-e) F-009-AC46 테스트(`DialectPolicyTest.borrowedStatements`) → 실패 확인(`borrowedFrom` 없음, 주석으로 뺀 구문을 mysql에서 가져옴) → `MyBatisAnalyzer.dropCommentedOut`·`commentedIds`·`mainDialect`·`listIds`, 구문 노드 `borrowedFrom` (F-009-R25)
- [x] (방언 불일치 수정) F-009-AC47 테스트(`DialectPolicyTest.otherDialectsByName`·`otherDialectsOnBothSidesFirst`, `DialectTokenTableTest.otherDialectOrder`: sql 모듈과 같은 표) → 실패 확인(mysql·postgres 두 벌에서 postgres를 고름) → `DialectVariants.otherOrder`, `MyBatisAnalyzer.selectDialectVariants`가 매퍼·SQL 파일 경로로 순서를 계산해 넘김 (F-009-R26). 기대값을 고친 기존 테스트: `DialectVariantTest.picksOneDialectAutomatically`·`fallsBackPerStatement`·`requestedDialectNotFound`(mysql·hsql의 순서가 hsql 먼저로 바뀜)

## 마무리
- [x] 방언 불일치 수정(F-009-R26) 뒤 `mvn -q -pl codeatlas-mybatis test` 194개 통과(2026-10-11)
- [x] 재검증 3차 후속 뒤 `mvn -q -pl codeatlas-mybatis clean test` 187개 통과(이전 184 + `DialectPolicyTest` 3)
- [x] 공개 프로젝트 실측(읽기 전용 실행기): 전자정부 템플릿 2종에서 이중 namespace 노드 10→0, 16→0, `DUPLICATE_STATEMENT_ID` 96→0, 205→0, jeesite에서 방언 문법이 섞인 전개본 34→0. 샘플은 노드·엣지·내장 소스·진단이 전과 같다
- [x] 모든 수용 기준 통과 확인 (`mvn -q -pl codeatlas-mybatis clean test`, 2026-10-11 재검증 후속 뒤 184개. 처음 구현 때는 61개, 2차 뒤 76개였다)
- [x] samples/sample-shop 으로 돌려 보고 결과 확인 (must-have-nodes.json의 `mybatis:` 72개 중 XML에서 오는 70개 일치. 나머지 2개는 `@Select` 애너테이션 구문으로 java 모듈 몫)
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음)
