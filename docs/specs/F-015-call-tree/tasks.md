---
type: tasks
id: F-015
updated: 2026-10-11
---

# F-015 콜트리와 영향도 조회 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-015-AC1, AC2, AC10 테스트 → 실패 확인 → `FlowNavigator`, `CallTrees`, `CallTreeNode` 구현 → 통과 (F-015-R1, R2)
- [x] T2. F-015-AC3, AC4, AC5 테스트 → 실패 확인 → 인터페이스 디스패치와 주입 좁히기 → 통과 (F-015-R3, R4)
- [x] T3. F-015-AC6, AC7 테스트 → 실패 확인 → 순환, 반복 억제, 깊이·노드 상한 → 통과 (F-015-R5)
- [x] T4. F-015-AC8, AC9 테스트 → 실패 확인 → `CallTreeRenderer`(텍스트, JSON, Mermaid) → 통과 (F-015-R6, R7)
- [x] T5. F-015-AC18 테스트 → 실패 확인 → `NodeFinder` → 통과 (F-015-R1)
- [x] T6. F-015-AC11, AC12 테스트 → 실패 확인 → `ImpactAnalysis`, `ImpactReport` → 통과 (F-015-R8, R9)
- [x] T7. F-015-AC13, AC14 테스트 → 실패 확인 → `CrudMatrix` → 통과 (F-015-R10)
- [x] T8. F-015-AC16 테스트 → 실패 확인 → `SummaryReport` → 통과 (F-015-R12)
- [x] T9. F-015-AC15, AC17 테스트 → 실패 확인 → `StandardExporter`, `META-INF/services` 등록 → 통과 (F-015-R11, R13)
- [x] T10. 조정자 확정 사항 반영: CONTAINS(JSP→모듈, 함수→함수), 요청 표시(`httpMethod + path`), 미해결 참조를 엣지 기준으로 집계 (F-015-R1, R12)
- [x] T11. 샘플 프로젝트 실제 분석 결과로 확인하며 찾은 문제를 재현 테스트 먼저 추가하고 수정: 미해결 노드의 위치는 호출 지점, 스텝이 메서드를 직접 USES하면 타입을 거치지 않음, 프래그먼트 표시 이름, JSP 줄의 위치 중복, `expandRenderedViews` 옵션 (F-015-R1, R6)

## 통합 단계 개선
- [x] T12. F-015-AC19 테스트(`StandardExporterTest.omitsArtifactsWithoutSubjects`, `SummaryReportTest.notesMissingSubjects`. 기존 "빈 그래프에서도 산출물을 쓴다" 테스트를 대체) → 실패 확인 → `GraphContents`, 대상 없는 산출물 생략, 리포트의 "없음" 줄 → 통과 (F-015-R14)
- [x] T13. F-015-AC20 테스트 → 실패 확인(위치 칸이 빈칸) → 위치 없는 칸을 `-`로 → 통과 (F-015-R15). `|`와 줄바꿈 이스케이프는 이미 되어 있어 테스트로 고정만 했다
- [x] T14. F-015-AC21 테스트 → 실패 확인 → `ImpactReport.suggestions()`, `Suggestion` → 통과 (F-015-R16)
- [x] T15. F-015-AC22 테스트 → `StandardExporter.OPTION_KEYS` (F-015-R17). 상수를 T12와 함께 먼저 넣고 테스트를 뒤에 써서 실패를 확인하지 못했다
- [x] T16. 샘플 산출물 점검에서 찾은 것: 루틴이 테이블에 접근하지 않는 그래프에서 `crud/table-routine.csv`가 머리글만 든 채로 생김 → 재현 테스트(F-015-AC19에 추가) → 실패 확인 → 행이 없는 매트릭스 생략 → 통과 (F-015-R14)

## 독립 감사에서 찾은 오류 수정 (작업마다 재현 테스트 먼저)
- [x] T17. F-015-AC23 테스트(`CallTreeTest.triggersFireOnlyOnMatchingEvent`, `triggerCallersMatchEvent`, `parsesTriggerEvents`, `CrudMatrixTest.endpointMatrixRespectsTriggerEvent`, `ImpactAnalysisTest.triggerChainRespectsEvent`) → 실패 확인 → `TriggerEvent`, `FlowNavigator`의 트리거 판정과 `[trigger?]`, 영향도의 트리거별 방문 → 통과 (F-015-R18). 공유 픽스처 `ShopGraph`의 트리거에 `triggerEvent`(`AFTER INSERT OR UPDATE`)를 넣었다
- [x] T18. F-015-AC24, AC25 테스트(`CallTreeTest.transitiveImplementationsAndNearestOverride`, `callersThroughTransitiveOverrides`) → 실패 확인 → 전이적 구현 전개, 후보 타입의 가장 가까운 구현으로 좁히기, callers의 좁히기와 `super` 호출 제외 → 통과 (F-015-R19)
- [x] T19. F-015-AC26 테스트(`CrudMatrixTest.readOnlyFromTableLevelEdge`, `CallTreeTest.columnReadsAreNotTableReads`, `ImpactAnalysisTest.tableImpactIgnoresColumnReads`) → 실패 확인 → `Access.tableLetters` → 통과 (F-015-R20). spec 5절의 "사용자 검토 필요" 가정을 갱신
- [x] T20. F-015-AC27 테스트(`CrudMatrixTest.routineMatrixFollowsRoutineCalls`) → 실패 확인 → 루틴 호출을 따라간 간접 접근(소문자) → 통과 (F-015-R21)
- [x] T21. F-015-AC28 테스트(`StandardExporterTest.callTreeDefaultDepthAndTruncationNote`) → 실패 확인 → 기본 깊이 20, `CallTree.depthLimited()`, 안내 줄 → 통과 (F-015-R22)
- [x] T22. F-015-AC29 테스트(`CallTreeTest.stepsInFlowOrder`, `StandardExporterTest.batchJobsInFlowOrder`, 기존 `calleesFromJspAndJob`의 기대 순서 변경) → 실패 확인 → `BatchFlow` → 통과 (F-015-R23)
- [x] T23. F-015-AC30 테스트(`SummaryReportTest.statementsWithoutJavaCallers`) → 실패 확인 → `report.md`의 "Java 호출자가 없는 매퍼 구문" → 통과 (F-015-R24)
- [x] T24. F-015-AC31 테스트(`CrudMatrixTest.dynamicStatementsAreVisible`, `ImpactAnalysisTest.reportsDynamicStatements`, `SummaryReportTest.listsDynamicStatements`, `StandardExporterTest.dynamicStatementsInArtifacts`) → 실패 확인 → `DynamicStatement`, `(동적)` 열, 영향도·리포트의 절 → 통과 (F-015-R25)
- [x] T25. F-015-AC32 테스트(`CrudMatrixTest.callStatementsReachRoutineTables`) → 실패 확인 → 구문·메서드 매트릭스가 구문이 부르는 루틴의 테이블을 넣고 엔드포인트 매트릭스도 루틴 경유를 소문자로 → 통과 (F-015-R26). 기존 AC13 테스트의 기대값 셋(메서드가 실행하는 루틴 `r`·`cu`, 트리거를 거친 엔드포인트 칸 `c`)을 새 표기로 바꿨다
- [x] T26. 실제 샘플 분석 결과(`codeatlas-cli/target/final-out/graph.json`)로 임시 실행해 확인: `POST /order/save`의 `insertOrder` 아래에 `TRG_ORDER_STATUS_HIST`가 없음, `placeOrder`의 `[impl] VipDiscountPolicy#discount` → `DefaultDiscountPolicy#discount (super)`, `PKG_ORDER.CLOSE_DAILY`의 TB_ORDER `Ru`, 깊이 제한에 잘린 트리 0개, `salesSumStep` → `closeOrderStep` 순서, Java 호출자가 없는 매퍼 구문 9개(감사가 든 5개 포함), 동적 구문 3개, `callCloseOrder` 등 CALL을 실행하는 메서드의 행

## 실제 프로젝트 검증에서 찾은 문제 (작업마다 재현 테스트 먼저, 2026-10-11)
- [x] T27. F-015-AC33 테스트(`LargeGraphExportTest`) → 수정 전 jar로 돌려 60초 제한 초과 확인 → core `GraphModel`의 종류별 인덱스(F-001-R37), `DbGraph.tableOfColumn`은 컬럼 ID 먼저, `FlowNavigator`는 흐름 엣지 종류만 조회, `SummaryReport`는 엣지 전체를 정렬하지 않음 → 통과(2초대) (F-015-R27)
- [x] T28. F-015-AC34 테스트(`DataLoadTest`) → 실패 확인 → `DataLoads`, `CrudMatrix.of(…, includeDataLoad)`, `TableSpecRenderer.render(…, includeDataLoad)`, `SummaryReport.render(…, includeDataLoad)`, `CallTreeOptions.includeDataLoad`, `export.includeDataLoad` → 통과 (F-015-R28)
- [x] T29. F-015-AC35~AC39 테스트(`ReceiverNarrowingTest`, 픽스처 `GenericDaoGraph`) → `FlowNavigator`의 수신자 문맥(`Step.context`, `Context`, `arrivalKey`), `Marker.RECEIVER_UNCERTAIN`·`REFLECT`, `CallTrees`·`CrudMatrix`·`ImpactAnalysis`가 걸음을 넘겨 문맥을 잇게 → 통과 (F-015-R29)
- [x] T30. F-015-AC40, AC46 테스트(`LargeArtifactSplitTest`) → 실패 확인 → 컨트롤러별 파일과 색인, `CallTreeOptions.hideAccessors`, `FlowNavigator.isAccessor`, 표준 산출물의 접기 기본값 → 통과 (F-015-R30, R35)
- [x] T31. F-015-AC41 테스트(`CallTreeTest.childrenInCallSiteOrder`, 기존 `calleesFromEndpoint`의 기대 순서 변경) → 실패 확인 → 호출 지점 위치순 → 통과 (F-015-R31). 테스트 픽스처 `GraphFixture.calls`의 호출 지점 줄을 해시가 아니라 더한 순서대로 매기게 바꿨다
- [x] T32. F-015-AC42 테스트(`SummaryReportTest.flagsSuspiciousUndeclaredTables`) → 실패 확인 → 비고 열 → 통과 (F-015-R32)
- [x] T33. F-015-AC43 테스트(기존 `CallTreeTest.renderedViewsAsLeaves`를 새 기본값으로 고침) → 실패 확인 → `CallTreeOptions`의 기본값 → 통과 (F-015-R33)
- [x] T34. F-015-AC44, AC45 테스트(`DispatchNarrowingTest`) → 수정 전 jar로 돌려 실패 확인 → 구현 타입 주입 후보, `[impl]`의 신뢰도, `super` 도착 시 구현 전개 안 함 → 통과 (F-015-R34)
- [x] T35. F-015-AC47~AC51 테스트(`ModuleAndMarkerTest`) → 모듈별 핸들러, `resolvedPath` 표시·검색, `[before]`, `calltree/filters.md`, `argCount` 표기 → 통과 (F-015-R36~R40)
- [x] T36. 실제 그래프(jeesite, cc-oracle, zheng, egov 둘, jpetstore)로 임시 실행해 확인: jeesite의 `crud/table-endpoint.csv`에서 테이블 20개 이상인 행 149 → 1, `impact SYS_DICT`의 엔드포인트 149 → 12, cc-oracle의 `calltree/endpoints.md` 18.7MB 한 파일 → 색인 199KB + 컨트롤러별 166개(가장 큰 것 158KB)

## 기록 (2026-10-11 실제 프로젝트 검증 라운드)
- 실패 확인: AC33·AC44·AC45는 재현 테스트를 수정 전 jar(`~/.m2`에 있던 것을 따로 떠 둔 것)에 대고 실행해 실패를 확인했다. AC34·AC40·AC42·AC46과 F-013-AC18·F-014-AC16은 새 API에 빈 구현을 먼저 넣고 단언 실패를 확인했다. AC35~AC39와 AC47~AC51은 테스트를 먼저 썼지만 새 표식(`Marker.RECEIVER_UNCERTAIN` 등)이 없어 컴파일 실패로만 확인했다(동작의 실패는 수정 전 jar로 잰 jeesite 수치 149행·149개로 확인했다).
- 기존 테스트에서 고친 기대값: `CallTreeTest.calleesFromEndpoint`(자식 순서), `renderedViewsAsLeaves`(기본값), `SummaryReportTest.rendersSummary`(마무리 단계 노드의 분류 `java:method` → `java-call`).
- 다른 문서·모듈과 어긋난 곳(이 작업의 쓰기 범위 밖이라 고치지 않았다): 설계 3.8의 출력기 키 표에 `export.includeDataLoad`, `export.mermaid.maxChars`, `export.calltree.hideAccessors`, `export.calltree.splitOver`가 없고 `export.calltree.hideExternal`의 기본값이 false로 적혀 있다(cli의 F-001-AC47 테스트가 이 표와 `StandardExporter.OPTION_KEYS`를 대조하므로 표를 고치기 전에는 그 테스트가 실패한다). cli `calltree`는 `--no-views`가 없으면 `expandRenderedViews(true)`를 넘긴다. 뷰어의 콜트리 규칙(자식 순서, 접근자 접기, 수신자 문맥, 모듈별 핸들러, 표식)도 다시 맞춰야 한다.

## 3차 수정: 공개 프로젝트 재검증에서 찾은 문제 (작업마다 재현 테스트 먼저, 2026-10-11)
- [x] T37. F-015-AC52 테스트(`RequestFlowTest.beforeOnlyUnderRequestEnteredHandler`, `beforeCallersClimbOnlyToEndpoints`) → 실패 확인 → `FlowNavigator`의 요청 진입 판정(`requestEntry`), callers의 `entryOnly`, `arrivalKey`의 `|entry`·`|before`, 걸음 키의 호출 방식 → 통과 (F-015-R41)
- [x] T38. F-015-AC53 테스트(`redirectAndForwardTargetsAreLeaves`, `redirectIsAnotherRequestForwardIsNot`) → 실패 확인 → `Marker.REDIRECT`·`FORWARD`, `CallTrees`의 화면 이동 잎, `CrudMatrix`·`ImpactAnalysis`가 redirect를 따라가지 않음 → 통과 (F-015-R42). 기존 `ModuleAndMarkerTest.writesFilterCallTrees`의 기대값 `(redirect)` → `~ [redirect]`
- [x] T39. F-015-AC54 테스트(`AccessorFoldingTest`) → 실패 확인 → `FlowNavigator.isAccessor`(필드 대응, 매개변수 수, static, external 제외) → 통과 (F-015-R43). `LargeArtifactSplitTest`의 픽스처에 필드를 더했다
- [x] T40. F-015-AC55 테스트(`ReceiverNarrowingTest.genericIntermediateWithoutMatchingSubtypeIsDropped`) → 2차 jeesite 그래프로 원인 확인(조건을 넘겨받은 `TreeService#save`가 위에서 전부 걸러져도 남음) → 실패 확인 → `noSubtypeCanSatisfy` → 통과 (F-015-R44)
- [x] T41. F-015-AC56 테스트(`DataLoadTest.dataLoadStatementsCallingRoutinesAreExcluded`) → 실패 확인 → `CrudMatrix`의 STATEMENT 분기가 CALLS로 구문을 모을 때 적재 구문을 거름 → 통과 (F-015-R45)
- [x] T42. F-015-AC57 테스트(`viewControllerEndpointRendersPage`, `viewControllerPagesPerModule`) → 앞의 것은 기존 규칙으로 이미 통과(기대값의 위치 표기만 고침), 모듈별 선택은 구현 → 통과 (F-015-R46)
- [x] T43. F-015-AC58 테스트(`clientRoutesAreNotUnresolvedRequests`) → 실패 확인 → `NodeLabels`의 `[route]`, 미해결 `MATCHES` 건너뛰기, `SummaryReport`의 "화면 요청" 절 → 통과 (F-015-R47)
- [x] T44. F-015-AC59~AC61 테스트(`layoutsAreNotFollowed`, `ambiguousCallsAreGrouped`, `unverifiedScriptLoadIsMarked`) → `CallTreeOptions.expandLayouts`, `Marker.LAYOUT`, `Step.ambiguous`·`layout()`, `CallTrees`의 묶음 줄과 "쓰는 화면 n개" 줄, `loadUnverified` → 통과 (F-015-R48~R50). 조정자가 작업 중에 더한 항목이라 새 API가 없어 컴파일 실패로만 실패를 확인했다
- [x] T45. F-015-AC62 테스트(`StandardExporterTest.undeclaredColumnsOfDeclaredTables`) → 실패 확인 → `UndeclaredColumns`, `SummaryReport`의 "SQL과 DDL의 컬럼 불일치" 절 → 통과 (F-015-R51)
- [x] T46. 실제 그래프로 전후 비교(같은 2차 재검증 그래프, 수정 전 jar와 수정 후 클래스): 공통컴포넌트 `calltree/endpoints/` 5,345,892 → 3,789,616바이트(166개 파일), jeesite `impact SYS_DICT`의 호출 사슬 31 → 30(`TreeService#save` 경로가 빠짐). 새 java·link 그래프(jeesite, zheng, enterprise)로도 표준 산출물을 만들어 뷰어 대조를 통과시켰다
- [x] 남은 일이던 것: `ambiguous` 경로로 닿은 CRUD 칸·영향도 행의 추론 표시 → 4차 수정 T47

## 4차 수정: 3차의 남은 일과 3차 재검증 M14 (작업마다 재현 테스트 먼저, 2026-10-11)
- [x] T47. F-015-AC63~AC66 테스트(`InferredPathTest`, `StandardExporterTest.crudReadmeExplainsNotation`) → 빈 구현(`Item.inference`)을 넣고 실행해 단언 실패 확인(새 테스트 9개 + 기대값을 바꾼 기존 2개) → `Step.inference`, `FlowNavigator.inferenceOf`·`Pick`·`singleTarget`, `CrudMatrix`의 두 상태 탐색과 `?`, `ImpactAnalysis`의 확실함 3단계, `ImpactReport`의 `~`·`추론:`, `CrudReadme` → 통과 (F-015-R52, R53). 기존 기대값을 바꾼 곳: `CrudMatrixTest.byEndpoint`·`csvAndMarkdown`(`AUDIT_LOG`가 추론 호출로만 닿아 `C?`), `RequestFlowTest.redirectIsAnotherRequestForwardIsNot`(forward로만 닿은 칸 `R?`), `ReceiverNarrowingTest.matrixAndImpactAreNarrowed`(`/mix`·`/raw`·`/odd`의 칸에 `?`), `StandardExporterTest.omitsArtifactsWithoutSubjects`(`crud/README.md`)
- [x] T48. F-015-AC67 테스트(`RequestFlowTest.requestStatsSeparateExternalAndInferred`, `clientRoutesAreNotUnresolvedRequests`의 기대값) → `SummaryReport.requests`·`staticPageLink` → 통과 (F-015-R54). 처음 판(서버 요청/연결됨 가운데 추론/외부 URL 행)은 테스트를 먼저 쓰고 실패를 실행으로 확인했다. 조정자가 형식을 다시 정한 뒤의 판(확정·추론·미연결, 정적 페이지 링크, 미연결 목록)은 기대값을 먼저 고쳤지만 구현 전에 따로 실행하지 않았다
- [x] T49. F-015-AC68~AC74 테스트(`Round5Test`) → 빈 구현(`isBuilderCall`, `dataLoadExcluded`, `analyze(…, includeDataLoad)`)을 넣고 실행해 7개 모두 단언 실패 확인 → 끝 위치 순 정렬, `MATCHES` 묶음, 순수 생성자·빌더 접기, `Marker.SHARED`·`includingPages`, `borrowedFrom`, `systemCatalog`, 영향도의 적재 구문 제외 → 통과 (F-015-R55~R61). 기존 `RequestFlowTest.layoutsAreNotFollowed`의 callers 기대값을 `[shared]`로 바꿨다
- [x] T50. 실제 그래프(corpus-eval3의 graph.json을 `codeatlas export`로 다시 내보냄)로 확인: 결과는 spec 5절 "4차 수정의 세부"
- [x] T51. 조정자 결정(forward로만 닿은 칸은 추론으로 두지 않는다): `InferredPathTest.otherInferredStepsAreMarked`·`RequestFlowTest.redirectIsAnotherRequestForwardIsNot`의 기대값을 먼저 바꿔 실행으로 실패 확인(둘 다) → `FlowNavigator.inferenceOf`가 `callType=forward`를 확정으로(대상 매칭이 `ambiguous`·`suffixMatch`·`crossModule`이면 추론) → 통과 (F-015-R52). 같은 코퍼스 그래프를 다시 내보낸 수치는 아래 기록
  - T51의 수치(새 jar로 다시 내보낸 CRUD의 `?` 칸): 공통컴포넌트 엔드포인트 기준 275/2,670 → 0/2,670, enterprise 14/415 → 1/415(후보 여럿에서 맞춘 forward), 메서드 기준은 그대로(공통컴포넌트 3/1,899, jeesite 237/312).

## 5차 수정: 분석기 4차 수정이 그래프에 새로 남기는 것 (작업마다 재현 테스트 먼저, 2026-10-11)
- [x] T52. F-015-AC75~AC80 테스트(`Round6Test` 8개) → 실행해 8개 모두 단언 실패 확인 → `FlowNavigator`(`matchModule`·`inModule`·`declaredIn`, `resolvedAnotherWay`, `relationDetail`, `inferenceOf`의 까닭, `INCLUDES`의 `ambiguous`, 핸들러 여럿인 엔드포인트의 `contextSensitive`), `CallTrees`(`includes` 묶음), `ImpactAnalysis`(엔드포인트를 핸들러마다 따로 방문), `NodeLabels`(`requestMethod`, `SELF_SUBMIT`), `SummaryReport`(`resolvedAnotherWay`) → 통과
- [x] T53. 실제 그래프에서 본 것: 위 방향 트리에서 저마다 후보 대응(`ambiguous`)인 요청들이 `[ambiguous n] <엔드포인트>` 한 줄로 묶였다(뷰어는 묶지 않는다) → `Round6Test.ambiguousGroupsOnlyDownward`를 쓰고 실행해 실패 확인 → 묶음을 아래 방향에서만 만든다(R67) → 통과
- [x] T54. 후보가 하나뿐인 `ambiguous`를 가정한 곳 정리(R67): 코드에는 없다(묶음은 처음부터 둘 이상일 때만 만든다). `Round5Test.ambiguousMatchesAreGrouped`의 "후보가 하나뿐인 요청"에서 `ambiguous`를 뗐다. `RequestFlowTest.ambiguousCallsAreGrouped`의 후보 하나짜리 엣지는 전 그래프와의 호환을 못박는 것이라 그대로 뒀다.
- [x] T55. 콜트리 범례(`StandardExporter.LEGEND`)와 `crud/README.md`의 추론 까닭 목록에 새 표시를 더했다. 실제 그래프 9개(sample-shop과 공개 프로젝트 8개를 새 분석기로 다시 분석)에서 표준 산출물 ↔ 뷰어 대조 통과. 결과는 spec 5절 "5차 수정의 세부".
- [x] design.md 반영(spec 8절. 문서 정리 라운드에서 반영함, 2026-10-11)

## 5차 수정(4차 재검증 후속, 2026-10-11)
- [x] T55. F-015-AC81~AC88 테스트(`Round7Test` 9개) → 실행해 8개 단언 실패 확인(AC87은 처음부터 통과, AC82는 마지막 단언만 실패) → `FlowNavigator`(`addMatch`의 세부 표시, `declaredInSeveralModules`, `requestEntriesInto`·`samePath`), `NodeLabels`(`unresolvedSelfSubmitMethod`·`unresolvedSelfSubmitLabel`, `SELF_SUBMIT_CANDIDATES`), `CallTrees`(미해결 selfSubmit 줄의 이름, 묶음 줄 이름, 적재 묶음의 표시), `SummaryReport`(`unresolvedDifference`, `RequestStats`·`requestStats`, 적재 절), `DataLoads`(`borrowedNote`, 파일별·방언별 집계), `ImpactAnalysis`(구문 이름) → 통과 (F-015-R68~R75)
- [x] T56. F-015-AC89·AC88(둘째) 테스트(`Round7Test` 2개) → 실패 확인 → `FlowNavigator.relationDetail`·`inferenceOf`(`narrowedBy`), `CrudReadme`, `DataLoads.Summary.borrowed`, 리포트의 묶음 수 문장 → 통과 (F-015-R75, R76)
- [x] T57. 실제 그래프(S5, 고치기 전 분석기의 그래프)에서 본 것: zheng `GET /manage/index`의 위 방향 요청 6줄에 `(모듈 zheng-cms/zheng-cms-admin)` 3, `(모듈 zheng-upms/zheng-upms-server)` 3. enterprise `ANY /cop/com/selectBBSUseInfsByTrget.do`의 위 방향이 빈 트리 → `~ [forward]` 2줄. 공통컴포넌트 report.md `미해결 참조 (1227)`에 각주(1228건 가운데 1건), 미해결 selfSubmit 1건의 대상이 `POST /uss/ion/mtg/selectMtgPlaceImage.do (자기 페이지로 제출: …)`
- [x] design.md 3.4 반영(직접 고침)

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음)

## 기록
- 감사 수정(T17~T25)의 실패 확인: 새 API에 빈 구현(자리 표시)을 먼저 넣어 컴파일되게 한 뒤 테스트를 돌려 단언 실패 20건을 확인하고 구현했다. T25(AC32)는 T20의 구현을 넓히면서 테스트와 함께 썼고, 새 경로를 잠시 빼고 돌려 실패(메서드 행 없음)를 확인했다.
- 다른 문서와 어긋난 곳(이 작업의 쓰기 범위 밖이라 고치지 않았다): 설계 3.8과 루트 `README.md`의 `export.calltree.depth` 기본값 표기(8 → 20), 설계 3.8의 `export.crud.depth` 설명(엔드포인트 매트릭스뿐 아니라 루틴 호출의 걸음 수에도 쓴다).
- 실패 확인(RED)은 테스트를 먼저 쓰고 실행해 확인했다. 새 API를 쓰는 테스트는 대부분 컴파일 실패로, 기존 API의 동작을 바꾸는 테스트는 단언 실패로 확인했다.
