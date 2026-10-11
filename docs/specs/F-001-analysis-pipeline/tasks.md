---
type: tasks
id: F-001
updated: 2026-10-11
---

# F-001 분석 파이프라인과 그래프 모델 작업 목록

<!-- 이 프로젝트는 TDD로 한다. 작업마다 실패하는 테스트를 먼저 쓰고(RED), 통과시키고(GREEN), 정리한다(REFACTOR).
     이 기능의 RED는 대부분 "구현 클래스가 없어 테스트가 컴파일되지 않음"으로 확인했다. T12, T13은 단언 실패로 확인했다. -->

## 준비
- [x] T0. Maven 멀티모듈 뼈대(루트 pom, 11개 모듈, dependencyManagement, pluginManagement). 설계 2절이 바뀌어 springxml, jsp, viewer 모듈을 더했다.

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-001-AC6, AC7 테스트 작성 → 실패 확인 → 그래프 모델(Node, Edge, GraphModel) 구현 → 통과 (F-001-R5)
- [x] T2. F-001-AC8, AC9 테스트 작성 → 실패 확인 → ID 생성 헬퍼(Ids, DbName) 구현 → 통과 (F-001-R6)
- [x] T3. F-001-AC13 테스트 작성 → 실패 확인 → 매달린 엣지 처리 구현 → 통과 (F-001-R9)
- [x] T4. F-001-AC4, AC5 테스트 작성 → 실패 확인 → 인코딩 판별(TextDecoder, SourceFile.readText) 구현 → 통과 (F-001-R4)
- [x] T5. F-001-AC1, AC2, AC3 테스트 작성 → 실패 확인 → 파일 스캐너와 분류 구현 → 통과 (F-001-R1, R2, R3)
- [x] T6. F-001-AC19 테스트 작성 → 실패 확인 → EmbeddedSource와 AnalysisContext 기본 구현 → 통과 (F-001-R13)
- [x] T7. 테스트 지원 클래스 TestProject 테스트 작성 → 실패 확인 → 구현 → 통과 (다른 모듈의 단위 테스트용. 계획에 없던 작업)
- [x] T8. F-001-AC14, AC15 테스트 작성 → 실패 확인 → GraphJson 구현 → 통과 (F-001-R10)
- [x] T9. F-001-AC10, AC11, AC12 테스트 작성 → 실패 확인 → Pipeline 구현 → 통과 (F-001-R7, R8)
- [x] T10. F-001-AC20 테스트 작성 → 실패 확인 → GraphModel.replaceNode 구현 → 통과 (F-001-R14. 계획에 없던 작업)
- [x] T11. F-001-AC16, AC17 테스트 작성 → 실패 확인 → `analyze` 명령 구현 → 통과 (F-001-R11)
- [x] T12. F-001-AC18 통합 테스트(CodeatlasJarIT) 작성 → 실패 확인 → shade 실행 jar와 `bin/codeatlas` 구현 → 통과 (F-001-R12)
- [x] T13. F-001-AC2에 `src` 아래 빌드 산출물 이름 디렉터리 테스트 추가 → 실패 확인 → 제외 규칙 완화 → 통과 (F-001-R2. 계획에 없던 작업)

## CLI 보강과 실행 jar 패키징 (F-001 완료 뒤 추가)
- [x] T14. 하위 명령 레지스트리로 리팩터링(`Command`, `Main`의 레지스트리, 사용법을 명령에서 모아 만듦). 기존 `MainTest`가 그대로 통과하는 것으로 확인 (조회용 하위 명령을 붙이기 위한 준비)
- [x] T15. F-001-AC21 테스트 작성 → 실패 확인 → `analyze --opt <key>=<value>` 구현 → 통과 (F-001-R15)
- [x] T16. F-001-AC22, AC23 테스트 작성 → 실패 확인 → `analyze --no-export`, `--quiet` 구현 → 통과 (F-001-R16)
- [x] T17. F-001-AC24, AC25 테스트 작성 → 실패 확인 → `summary <graph.json>` 구현 → 통과 (F-001-R17)
- [x] T18. F-001-AC26, AC27 통합 테스트(CodeatlasJarIT) 작성 → 실패 확인(매니페스트 속성 없음, restricted method 경고) → 매니페스트에 `Enable-Native-Access: ALL-UNNAMED` 추가 → 통과 (F-001-R18)
- [x] T19. F-001-AC28 통합 테스트(CodeatlasJarIT) 작성 → 통과 (F-001-R18. viewer 모듈의 리소스가 shade에서 그대로 합쳐져 구현 변경 없이 통과했다. 외부 참조 검사 자체는 `externalReferenceDetection`으로 따로 확인)

## sample-shop 통합 검증 하네스 (codeatlas-cli의 테스트 소스, `io.codeatlas.cli.verify`)
- [x] T20. 정답 대조기 단위 테스트(`ExpectedCheckerTest`) 작성 → 실패 확인 → `ExpectedSet`, `ExpectedChecker`, `AttrMatcher`, `VerificationResult` 구현 → 통과. 비교 규칙은 `samples/sample-shop/README.md` 5절
- [x] T21. 리포트 단위 테스트(`SampleReportTest`) 작성 → 실패 확인 → `SampleReport`, `DiagnosticChecks`, `UnresolvedPoints` 구현 → 통과
- [x] T22. 통합 테스트 `SampleShopIT`(failsafe). Pipeline API로 `samples/sample-shop`을 분석해 정답과 대조하고 임계값으로 단언한다. 분석기·링커 기능이 모두 들어온 뒤 단언 모드(기본)로 통과한다(문서 정리 때 `codeatlas-cli/target/sample-shop-report.md`의 판정 표로 확인)

하네스 실행
- 단언 없이 리포트만: `mvn -q -pl codeatlas-cli verify -Dsample.verify=report` → `codeatlas-cli/target/sample-shop-report.md`(사람용), `sample-shop-report.json`(기계용), `sample-shop-graph.json`(분석한 그래프)
- 임계값으로 단언(기본): `mvn -q -pl codeatlas-cli verify`. 기본 임계값은 파이프라인이 예외 없이 끝남, `broken/` 파일 3개가 각각 `PARSE_FAILED`/`UNSUPPORTED_SYNTAX` 진단으로 남음, 함정 위반 0, must-have 엣지·노드 통과율 100%(`certainty=low` 제외)
- 조정: `-Dsample.verify.minEdgePassRate=<%>`, `-Dsample.verify.minNodePassRate=<%>`, `-Dsample.verify.maxViolations=<n>`, `-Dsample.verify.edgeAttrs=lenient`(엣지 attrs 불일치를 통과로). 자세한 것은 `SampleShopIT`의 주석
- cli는 다른 모듈을 `~/.m2`에 설치된 jar로 쓴다. 모듈을 고친 뒤에는 그 모듈을 install해야 리포트에 반영된다
- `SampleShopIT`이 RED인 동안 루트의 `mvn -q install`은 cli의 verify에서 실패한다(cli는 마지막 모듈이라 다른 모듈은 설치된다). cli까지 설치하려면 `mvn -q install -Dsample.verify=report`

## 조회용 하위 명령과 export (codeatlas-cli)
<!-- RED는 QueryCommandsTest 26개가 "알 수 없는 명령"(종료 코드 2)으로 실패하는 것으로 확인했다. 실행 jar 스모크(CodeatlasJarIT)는 구현 전에 함께 썼고 구현 뒤에 처음 돌렸다. -->
- [x] T23. F-001-AC29, AC30, AC31 테스트 작성 → 실패 확인 → 공통 뼈대(`CommandLine`, `QueryCommand`, `GraphFiles`, `CommandFailure`), `help <명령>`, 명령별 사용법 오류 구현 → 통과 (F-001-R19, R20, R21). `summary`도 `GraphFiles`를 쓰게 정리
- [x] T24. F-001-AC32, AC33 테스트 작성 → 실패 확인 → `erd` 구현 → 통과 (F-001-R22)
- [x] T25. F-001-AC34, AC35 테스트 작성 → 실패 확인 → `classdiagram` 구현 → 통과 (F-001-R23)
- [x] T26. F-001-AC36, AC37 테스트 작성 → 실패 확인 → `calltree`와 후보 목록(`NodeListing`) 구현 → 통과 (F-001-R24)
- [x] T27. F-001-AC38 테스트 작성 → 실패 확인 → `impact` 구현 → 통과 (F-001-R25)
- [x] T28. F-001-AC39 테스트 작성 → 실패 확인 → `crud` 구현 → 통과 (F-001-R26)
- [x] T29. F-001-AC40 테스트 작성 → 실패 확인 → `find` 구현 → 통과 (F-001-R27). ID 열 맞춤 폭 상한(`NodeListingTest`)은 구현 뒤에 테스트를 더했다
- [x] T30. F-001-AC41 테스트 작성 → 실패 확인 → `report` 구현 → 통과 (F-001-R28)
- [x] T31. F-001-AC42 테스트 작성 → 실패 확인 → `export` 구현 → 통과 (F-001-R29)
- [x] T32. 실행 jar 스모크(`CodeatlasJarIT`에 명령마다 하나, `help <명령>`, 기본 폴더 `codeatlas-out`) → 통과
- [x] T33. 실행 jar로 `samples/sample-shop`을 분석해 명령을 하나씩 돌려 보고 출력 점검. 루트 `README.md`(사용자용) 작성

## 통합 단계 개선 (core, cli)
<!-- RED: core의 T34~T36은 새 API가 없어 테스트가 컴파일되지 않는 것으로, cli의 T37~T39는 단언 실패 4건(erdSpec, erdSpecWithScope, exportInheritsStoredOptions, optionKeyExamplesAreRealKeys)으로 확인했다. -->
- [x] T34. F-001-AC43 테스트 작성 → 실패 확인 → `ProjectInfo.options`, `GraphJson`의 `project.options` 저장·복원, `Pipeline`이 옵션을 그래프에 담음 → 통과 (F-001-R30). cli의 `MainTest.storesOptionsInGraphJson`은 core를 설치한 뒤에 써서 처음부터 통과했다
- [x] T35. F-001-AC44 테스트 작성 → 실패 확인 → `Pipeline.export`, `ExportResult` 구현 → 통과 (F-001-R31)
- [x] T36. F-001-AC45 테스트 작성 → 실패 확인 → `Pipeline.withoutExporters` 구현 → 통과 (F-001-R31)
- [x] T37. `ExportCommand`가 직접 돌리던 출력기 루프를 `Pipeline.export`로, `AnalyzeCommand --no-export`의 클래스로더 우회를 `withoutExporters()`로 교체. 기존 F-001-AC22, AC42 테스트가 그대로 통과하는 것으로 확인 (F-001-R31)
- [x] T38. F-001-AC46 테스트 작성 → 실패 확인 → `export`가 저장된 `--opt`를 이어받음, 도움말 갱신 → 통과 (F-001-R32)
- [x] T39. F-001-AC47 테스트 작성 → 실패 확인(`help analyze`의 `--opt calltree.depth=5`) → 예시를 `export.calltree.depth=5`로 고침 → 통과 (F-001-R33). 출력기 키 목록은 export 모듈의 `StandardExporter.OPTION_KEYS`(F-015-AC22)
- [x] T40. F-001-AC48 테스트 작성, F-001-AC33 테스트 수정 → 실패 확인 → `erd --spec`이 범위·관계 옵션을 받게 함 → 통과 (F-001-R34)
- [x] T41. `erd`, `classdiagram`, `impact`가 이름마다 모델을 따로 만들거나 `NodeFinder`를 직접 불러 확인하던 부분을 조회 API의 `unresolvedNames()`, `excludedViewNames()`, `suggestions()`로 교체. 기존 F-001-AC33, AC35, AC38 테스트가 그대로 통과하는 것으로 확인 (F-001-R35)
- [x] T42. 실행 jar로 `samples/sample-shop`을 분석해 `erd`, `erd --spec --table TB_ORDER --depth 1`, `impact TB_ORDER`, `impact TB_ORDER.ORDER_STATUS`, `crud --by statement|method|endpoint|routine`, `calltree`(엔드포인트의 callees, 테이블의 callers), `export`(옵션 이어받기), README의 예시 명령을 돌려 보고 README를 실제 출력에 맞춤. 찾은 export 쪽 문제 둘은 F-015 tasks T16에 있다
- [x] T43. design.md 갱신: 3.1(`ProjectInfo`), 3.7, 3.8(키 대조 테스트), 4.1(`export`, `withoutExporters`, `ExportResult`), 4.4(`project.options`), 4.5(`--no-export`, `erd --spec`, `export`)
- [x] T44. F-001-AC50, AC51 테스트 작성 → 실패 확인(단언 실패 3건: `referenceDerivesQualifiedNameFromId`, `referenceKeepsExistingQualifiedName`, `referenceQualifiedNameRoundTrip`. 참조 노드의 `qualifiedName`이 `Resource`, `run`처럼 단순 이름이었다) → `GraphModel.reference`가 새 노드의 `qualifiedName`을 `Ids.remainder(id)`로 채움 → 통과 (F-001-R36). 공개 시그니처는 그대로다. 다른 모듈 테스트의 `qualifiedName` 단언은 모두 `declare`된 노드가 대상이라 바뀌는 기대값이 없다(grep으로 확인, 다른 모듈 테스트는 돌리지 않았다)

## 실제 프로젝트 검증에서 찾은 문제 (core, 2026-10-11)
<!-- RED: 새 API에 빈 구현(`Ids.danglingCategory`가 null, `AnalysisContext.progress` 빈 default, 던지지 않는 `AnalysisOutOfMemoryError`)을 먼저 넣고 테스트 3개의 단언 실패를 확인했다(AC52는 20초 제한 초과). -->
- [x] T45. F-001-AC52 테스트 작성 → 실패 확인(제한 시간 초과) → `GraphModel`의 엣지 저장을 노드별·종류별 인덱스(`EdgeRef`, `Adjacency`)로 바꿈 → 통과 (F-001-R37). 기존 `GraphModelTest`, `GraphModelReplaceNodeTest`, `GraphJsonTest`는 그대로 통과
- [x] T46. F-001-AC53 테스트 작성 → 실패 확인 → `Ids.danglingCategory`, `resolveDanglingEdges`가 `category`를 채움 → 통과 (F-001-R38)
- [x] T47. F-001-AC54 테스트 작성 → 실패 확인 → `AnalysisContext.progress`·`currentActivity`(default 메서드), `AnalysisOutOfMemoryError`, `Pipeline`의 변환 → 통과 (F-001-R39)
- [x] T48. design.md 3.5·3.6(마무리 단계 노드의 `category`), 4.1(`AnalysisContext.progress`, `AnalysisOutOfMemoryError`) 반영 → T56. cli의 최상위 처리(안내와 종료 코드) → T51. 분석기가 파일을 시작할 때 `ctx.progress(상대 경로)`를 부르는 것 → T57

## 실전 검증 2차 수정 반영 (cli, 2026-10-11)
<!-- RED: T49는 기존 F-001-AC47이 단언 실패(export 키 4개가 design 3.8에 없음)하는 것으로, T50~T54는 빈 구현(`Hints.of`가 빈 목록, `Main.execute`가 OutOfMemoryError를 잡지 않음, `--exclude-tests` 미구현)에서 단언 실패 4건과 테스트 JVM 중단(OutOfMemoryError가 JUnit까지 올라감)으로 확인했다. -->
- [x] T49. F-001-AC47이 실패하는 것 확인(`export.includeDataLoad`, `export.mermaid.maxChars`, `export.calltree.hideAccessors`, `export.calltree.splitOver`가 design 3.8에 없음) → design 3.8 표에 export 키 4개와 `link.inferViewRelations`, `mybatis.dialectVariant`, `web.skipVendor`, `web.vendorPatterns` 추가, `export.calltree.hideExternal` 기본값 true로 → 통과 (F-001-R33)
- [x] T50. F-001-AC55, AC56 테스트 작성 → 실패 확인 → `calltree`의 `--views`·`--show-accessors`·`--include-data-load`(기본은 펼치지 않고 접는다, `--no-views`는 받기만), `crud --include-data-load`, `crud --depth` 도움말 → 통과 (F-001-R40, R41)
- [x] T51. F-001-AC57, AC58 테스트 작성 → 실패 확인 → `Main.execute`가 `AnalysisOutOfMemoryError`·`OutOfMemoryError`를 잡아 안내 두 줄과 종료 코드 3 → 통과 (F-001-R42). 테스트용 분석기 `RecordingAnalyzer`에 `outOfMemoryWhile` 훅 추가
- [x] T52. F-001-AC59 테스트 작성 → 실패 확인 → `analyze --exclude-tests` → 통과 (F-001-R43)
- [x] T53. F-001-AC60, AC61 테스트 작성, F-001-AC47에 힌트의 키 대조 추가 → 실패 확인 → `Hints`, `Reports.summary`가 힌트 절을 붙임 → 통과 (F-001-R44)
- [x] T54. `SampleReport.categoryOf`의 "매달린 엣지:" 분기 제거(core가 마무리 단계 노드에 `category`를 채운다, F-001-R38). `SampleReportTest.danglingNodesAreGroupedByCategory`로 분류명 확인
- [x] T55. F-001-AC62, AC63 통합 테스트(`CodeatlasJarIT.scriptPassesJavaOpts`, `sampleOutputsFollowCurrentRules`) 작성 → 통과 (`bin/codeatlas`는 이미 `JAVA_OPTS`를 넘기고 있어 스크립트 변경은 없다)
- [x] T56. design.md에 2차 수정의 계약 변경 반영(1절, 3.3~3.9, 4.1, 4.5, 7절), README·architecture.md·prd.md 갱신
- [x] T57. 분석기가 파일을 시작할 때 `ctx.progress(상대 경로)`를 부르게 함: sql은 이 라운드에서(F-006-R23), java·mybatis·springxml·web·jsp는 각 모듈 담당이 넣었다(java는 F-002-R16. 코드에서 확인, 2026-10-11). 링커와 출력기는 부르지 않는다(design 7절에 한계로 적었다)

## 3차 수정 (cli, 2026-10-11)
- [x] F-001-AC64, AC65 테스트 → 실패 확인 → `CallTreeCommand`의 기본값(`--show-external`, `--layouts`)과 `exactMatchOfLeadingKind` → 통과 (F-001-R45, R46)
- [x] F-001-AC66~AC68 테스트 → 실패 확인 → `Hints`의 프런트·백엔드 안내, DDL 방언·`DIALECT_NOT_FOUND`·방언 불일치 경고, `UNKNOWN_COLUMN`, `주의:` 올리기 → 통과 (F-001-R47)
- [x] `--classpath` 도움말·README 문구 (F-001-R48). 실제 실행으로 확인했다
- [x] design.md 3.4(소비자가 지키는 규칙), 3.8(`export.calltree.expandViews`·`hideAccessors`의 뜻), 4.5(`calltree` 옵션, 힌트 표) 갱신

## 5차 수정 (cli, 2026-10-11)
<!-- RED: 새 테스트 5개의 단언 실패를 실행으로 확인했다(`callTreeFindsEndpointByUrl`: 후보 2개로 종료 코드 1, `findPartialCaseInsensitiveAndWildcard`: 가려진 부분 일치 알림이 없음, `helpExplainsCrudMarksAndCallTreeFolds`: 도움말에 문구 없음, `dialectMismatchComesFromGraphNotFromMessages`: 경고 없음, `dialectOfPath`: 빈 구현이 null). 실행 jar 테스트 `dialectMismatchHintFollowsGraph`도 고치기 전 jar로 실패(그래프는 oracle/mysql인데 경고 없음)를 확인했다. -->
- [x] F-001-AC69 테스트 → 실패 확인 → `EndpointLookup`(URL로 엔드포인트 찾기), `CallTreeCommand`가 URL 꼴 검색어에 먼저 적용, 도움말 → 통과 (F-001-R49). 실제 그래프(공통컴포넌트 `*.do`, jeesite `${adminPath}`, front-back `/api/…`, zheng 경로 변수)로 확인
- [x] F-001-AC70 테스트 → 실패 확인(부분 일치·대소문자 무시는 처음부터 통과, 와일드카드와 알림에서 실패) → `NodeGlob`, `FindCommand`의 `*` 와일드카드와 가려진 부분 일치 알림, 도움말 → 통과 (F-001-R50)
- [x] F-001-AC71 테스트 → 실패 확인 → `help crud`·`help calltree`·README 문구 → 통과 (F-001-R51). `--show-accessors`·`--layouts`가 `CallTreeOptions.hideAccessors`·`expandLayouts`로 넘어가는 것은 이미 돼 있어 테스트로만 고정했다
- [x] F-001-AC72 테스트 → 실패 확인 → `Hints`의 방언 불일치 판정을 그래프(구문의 `dialectVariant`, 테이블 정의 파일 경로의 방언 토큰)로 바꿈, 문구에서 방언을 읽던 정규식 제거 → 통과 (F-001-R52). F-001-AC67의 테스트(`ddlDialectHints`)는 그래프에 구문과 테이블을 넣게 고쳤다
- [x] design.md 4.5(`calltree`의 URL 조회, `find`의 와일드카드, 힌트 표의 방언 불일치 조건) 갱신

## 6차 수정 (cli, 2026-10-11: sql·mybatis 4차 수정 반영)
<!-- RED: 단위 테스트 `HintsTest.dialectPolicyHints`(DIALECT_NOT_TARGET 힌트 없음), `QueryCommandsTest.impactIncludeDataLoad`(알 수 없는 옵션)의 실패와, 실행 jar 테스트 `notTargetDialectHint`(힌트 절 없음), `dialectMismatchHintFollowsGraph`(`--dialect mysql` 오류에 `mybatis.dialectVariant` 안내 없음. c1의 "양쪽 다 oracle" 단언은 새 sql·mybatis에서 처음부터 통과)의 실패를 실행으로 확인했다. -->
- [x] F-001-AC73 테스트 → 실패 확인 → `Hints`의 `DIALECT_NOT_TARGET` 한 줄, 대상 외 방언 안내를 `--opt mybatis.dialectVariant=<방언>`으로, 덧붙이는 안내 앞의 마침표, `analyze` 도움말과 `--dialect` 오류 문구 → 통과 (F-001-R53). 재현 프로젝트 c1~c6, my1, p3과 simple backend 템플릿으로 확인하고 README의 힌트 예시를 실제 출력으로 바꿨다
- [x] F-001-AC74 테스트 → 실패 확인 → `impact --include-data-load` → 통과 (F-001-R54)
- [x] README(방언별 매퍼·DDL, `mybatis.dialectVariant`, 진단 목록, `impact`)와 design.md 4.5(힌트 표, `impact` 옵션) 갱신

## 7차 수정 (cli, 2026-10-11: web·jsp·link의 새 그래프 꼴 반영)
<!-- RED: 새 테스트 7개의 단언 실패를 실행으로 확인했다(`EndpointLookupTest.methodMatchesRequestCandidates`: `GET`의 요청에 후보가 `[POST, PUT]`인 요청이 들어 있음, `findsRequestsWhenNoEndpointMatches`: null, `QueryCommandsTest.callTreeUrlMatchesRequestMethodCandidates`: `PUT /api/schedule`을 찾지 못해 종료 코드 1, `NodeListingTest.showsRequestMethodCandidates`: 줄 끝에 `[POST|PUT]` 없음, `HintsTest.unresolvedCallsResolvedElsewhereAreNotCounted`: 60건으로 힌트가 나옴, `ReportsTest.summaryExcludesReferencesResolvedElsewhere`: `UNRESOLVED 3`, `QueryCommandsTest.helpExplainsInferenceDetailsAndRequestNames`: 도움말에 문구 없음). 처음부터 통과한 것(동작이 이미 그랬다): `EndpointLookupTest.selfSubmitRequestIsNotUrlCandidate`, `QueryCommandsTest.callTreeUrlIgnoresSelfSubmitRequests`, `HintsTest.vendorHintCountsNodesNotDiagnosticMessages`. -->
- [x] F-001-AC75 테스트 → 실패 확인 → `EndpointLookup`의 메서드 판정(`httpMethods`)과 엔드포인트가 없을 때의 요청 찾기, `CallTreeCommand`가 `--kind HTTP_REQUEST`만 줘도 URL로 요청을 찾음, `NodeListing`의 줄 끝 `[POST|PUT]`, 도움말 → 통과 (F-001-R55). 실제 그래프(react 템플릿의 `PUT /api/bbsUseInf`, `POST /api/schedule`)로 확인
- [x] F-001-AC76 테스트(처음부터 통과) → 변경 없음: `selfSubmit` 요청(`{self}`)은 URL 후보가 아니다 (F-001-R55). 공통컴포넌트 그래프로 확인
- [x] F-001-AC77 테스트 → 실패 확인 → `UnresolvedRefs`(`resolvedBy` 판정), `Hints`의 미해결 Java 호출 수, `Reports`의 요약(노드·신뢰도별 `UNRESOLVED` 수와 알림 한 줄) → 통과 (F-001-R56). 공통컴포넌트 그래프의 `summary`로 확인(노드 430 → 429, 엣지 1228 → 1227)
- [x] F-001-AC78 테스트 → 실패 확인 → `help calltree`·`help crud` 문구 → 통과 (F-001-R57)
- [x] F-001-AC79 테스트(처음부터 통과) → 변경 없음: 벤더 힌트는 `skipped=vendor` 노드로 센다 (F-001-R44)
- [x] design.md 4.5와 루트 README 반영(spec 8절. 문서 정리 라운드에서 반영함, 2026-10-11)

## 8차 수정(4차 재검증 후속, 2026-10-11)
- [x] F-001-AC80·AC81 테스트(`ReportsTest` 2개) → 실패 확인 → `Reports.summary`(그래프 그대로 세고 알림 한 줄), `Reports.diagnosticsMarkdown`(머리 줄, `(접두 일치로 풀림)`), `UnresolvedRefs.how`·`resolvedBy` → 통과 (F-001-R58). 공통컴포넌트 그래프의 `summary`: 노드 `UNRESOLVED` 429 → 430(종류별 합 48693 = 총계), 신뢰도 `UNRESOLVED` 1227 → 1228 + 알림 줄
- [x] F-001-AC85 테스트(`SampleReportTest`) → 실패 확인 → `verify/SampleReport`의 미해결 노드·엣지 목록, 지점 근처의 엣지, JSON → 통과 (F-001-R58)
- [x] F-001-AC82 테스트(`HintsTest`) → 실패 확인 → `Hints.frontAndBack`이 `SummaryReport.requestStats`의 서버 요청 수를 쓴다 → 통과 (F-001-R60). react 그래프: "화면 요청 99개" → "서버 요청 97건"
- [x] F-001-AC83·AC84 테스트(`EndpointLookupTest` 3개, `QueryCommandsTest` 2개) → 실패 확인 → `EndpointLookup`(요청 패턴·컨텍스트 경로, `requestTemplate`, `otherMethods`, `isUrlQuery`), `CallTreeCommand`(요청 하나만 맞았을 때의 알림, `urlAdvice`, 도움말) → 통과 (F-001-R59)
- [x] F-001-AC86 테스트(`HintsTest`, 처음부터 통과) → 변경 없음: `DIALECT_VARIANT_DDL`의 새 문장도 그대로 옮긴다 (F-001-R53)
- [x] design.md 4.5·3.4와 루트 README 반영

## 9차 수정(영향도의 forward 경유 구분, 2026-10-11)
- [x] F-001-AC87 테스트(`QueryCommandsTest.impactMarksForwardOnlyItems`) → 실패 확인(도움말에 `[forward]`가 없음. 명령의 출력 단언은 export가 먼저 고쳐져 있어 처음부터 통과) → `ImpactCommand`의 도움말, 루트 README의 "테이블 영향도" 절 → 통과 (F-001-R61)

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경
- [x] AGENTS.md의 빌드·테스트 명령 갱신
- [x] design.md에 구현하면서 더한 것 반영
- [x] 구조가 바뀌었으면 architecture.md 갱신 (모듈 구성은 조정자가 이미 반영했다)
