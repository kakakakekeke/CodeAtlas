---
type: tasks
id: F-013
updated: 2026-10-11
---

# F-013 ERD 출력 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. 테스트 픽스처 빌더(`GraphFixture`)와 Mermaid 문법 검증기(테스트용) 작성
- [x] T2. F-013-AC1, AC6, AC12 테스트 → 실패 확인 → `ErdModel`, `ErdModelBuilder` 구현 → 통과 (F-013-R1, R4, R9)
- [x] T3. F-013-AC8, AC9 테스트 → 실패 확인 → `ErdOptions` 범위·표시 옵션 구현 → 통과 (F-013-R6)
- [x] T4. F-013-AC2, AC5, AC7, AC10 테스트 → 실패 확인 → `MermaidErdRenderer` 구현 → 통과 (F-013-R2, R3, R5, R7)
- [x] T5. F-013-AC3 테스트 → 실패 확인 → `PlantUmlErdRenderer` 구현 → 통과 (F-013-R2, R3, R7)
- [x] T6. F-013-AC4 테스트 → 실패 확인 → `DbmlErdRenderer` 구현 → 통과 (F-013-R2, R3, R7)
- [x] T7. F-013-AC11 테스트 → 실패 확인 → 정렬 규칙 정리 → 통과 (F-013-R8)
- [x] T8. F-013-AC13 테스트 → 실패 확인 → `TableSpecRenderer` 구현 → 통과 (F-013-R10)
- [x] T10. 링커 확정 사항 반영: `cardinality`(`N:1`/`1:1`/`unknown`), 테이블 속성 `primaryKey`·`uniqueKeys` → 테스트 추가 → 구현 (F-013-R4)
- [x] T11. 생성한 Mermaid를 실제 mermaid 파서로 확인하는 스크립트(`mermaid-check.cjs`)와 덤프 옵션 (F-013-R7)
- [x] T9. F-013-AC14 테스트 → 실패 확인 → `StandardExporter`의 ERD 산출물 → 통과 (F-013-R11)

## 통합 단계 개선
- [x] T12. F-013-AC15 테스트(`StandardExporterTest.omitsArtifactsWithoutSubjects`) → 실패 확인 → 테이블·뷰가 없으면 `erd/` 생략 → 통과 (F-013-R12)
- [x] T13. F-013-AC16 테스트 → 실패 확인 → `TableSpecRenderer.render(graph, ErdOptions)` → 통과 (F-013-R13)
- [x] T14. F-013-AC17 테스트 → 실패 확인 → `ErdModel.unresolvedNames()`, `excludedViewNames()` → 통과 (F-013-R14)
- [x] T15. 샘플 산출물 점검에서 찾은 것: 테이블 명세의 정의 위치가 `file:line:열`로 나옴 → 재현 테스트(F-013-AC13에 추가) → 실패 확인 → `file:line`으로 고침 → 통과 (F-013-R10)

## 실제 프로젝트 검증에서 찾은 문제 (2026-10-11)
- [x] T16. F-013-AC18 테스트(`LargeArtifactSplitTest.splitsErdBySubjectArea`) → 실패 확인(빈 옵션 처리 상태에서 단언 실패) → `ErdSplitter`, `StandardExporter`의 분할·개요·색인, `export.mermaid.maxChars` → 통과 (F-013-R15). 실제 그래프(cc-oracle, 테이블 200개): `erd.mmd` 87,168자 → 조각 3개(가장 큰 것 35,697자) + 개요 34,717자
- [x] T17. F-013-AC19(`DataLoadTest`) → 실패 확인 → `TableSpecRenderer.render(graph, options, includeDataLoad)` → 통과 (F-013-R16)
- [x] T18. 성능(F-015-R27): `TableSpecRenderer`가 READS/WRITES 엣지마다 부르는 `DbGraph.tableOfColumn`이 컬럼의 들어오는 엣지를 전부 복사하던 것을 컬럼 ID에서 먼저 풀게 고쳤다(재현 테스트는 F-015-AC33)

## 3차 수정 (2026-10-11)
- [x] T19. F-013-AC20 테스트 → 실패 확인(`ALL-1`…로 나뉨) → `ErdSplitter.clusterByRelations`·`splitAroundCenters`, `Split.hubs`·`byRelations`, `erd/index.md`의 "허브 테이블" → 통과 (F-013-R17). 공통컴포넌트(2차 재검증 그래프, 테이블 190개): `ALL-1`~`ALL-3` → `COMTNEMPLYRINFO`(68개) 등 관계 영역 9개 + `기타-1`·`기타-2`, 허브 `COMTCCMMNDETAILCODE`(52개 테이블이 참조)
- [x] T20. F-013-AC21 테스트 → 실패 확인 → `Relation.directionNote`, `ErdLabels.directionNote` → 통과 (F-013-R18)
- [x] T21. F-013-AC22 테스트 → 실패 확인 → `UndeclaredColumns`, `ErdModelBuilder`가 정의 있는 테이블의 `declared=false` 컬럼을 빼고 `TableSpecRenderer`가 따로 적음 → 통과 (F-013-R19)
- [x] T22. (4차 수정) F-013-AC23 테스트(`ErdTest.marksForeignKeysFromUpgradeScripts`) → 빈 구현(`Relation.fromUpgrade`)을 넣고 실행해 단언 실패 확인 → `ErdModelBuilder`, `ErdLabels.FROM_UPGRADE`·`relationLabel`, `TableSpecRenderer.relationDetail`, `DbmlErdRenderer` → 통과 (F-013-R20). `JOINS`의 `wrapped`는 표준 산출물이 `JOINS`를 적지 않아 뷰어의 엣지 요약에만 넣었다(F-016-AC67). `fixedValues`를 첫 `=`에서만 자르는지: 표준 산출물은 고정 값을 적지 않고, 뷰어의 구현은 이미 그랬다(테스트로 고정)

## 5차 수정 (2026-10-11)
- [x] T23. F-013-AC24 테스트(`LargeArtifactSplitTest.countsHubRelationsApartFromOtherAreas`) → 빈 구현(`Area.hubRelations=0`)에서 단언 실패 확인 → `ErdSplitter.Area.hubRelations`, `StandardExporter.writeSplitErd`의 "허브와의 관계" 열 → 통과 (F-013-R21)
- [x] T24. F-013-AC25 테스트(`clustersLeftoverTablesByNamePrefix`) → 실패 확인(`ALL-n`으로 나뉨) → `ErdSplitter.clusterByNames`, `Split.byNames` → 통과 (F-013-R22)
- [x] T25. F-013-AC26 테스트(`splitsLargeRelationAreaAroundCenterTable`) → 빈 구현(`Split.centers` 빈 목록)에서 단언 실패 확인(33개가 한 영역) → `ErdSplitter.RelationClusterer`(중심 테이블을 빼고 다시 묶기), `MAX_AREA_TABLES`, `Split.centers`, `index.md`의 "중심 테이블" → 통과. 사슬 70개가 낱개로 흩어지는 것을 테스트로 재현하고(실패 확인) 중심을 서로 먼 테이블로 고르게 고침 (F-013-R23)
- [x] T26. F-013-AC27 테스트(`labelsRelationAreasWithLogicalName`) → 빈 구현(`Area.label=name`)에서 단언 실패 확인 → `ErdSplitter.label`, `index.md`의 영역 이름 → 통과 (F-013-R24)
- [x] T27. F-013-AC28 테스트(`ErdTest.putsStartTablesFirstInTableSpec`) → 실패 확인 → `TableSpecRenderer.startTablesFirst` → 통과 (F-013-R25)
- [x] T28. F-013-AC29 테스트(`ErdTest.marksUpgradeForeignKeysMissingFromCurrentDefinition`) → 실패 확인 → `ErdModelBuilder.isInferred`, `ErdLabels.NOT_IN_CURRENT`·`inferredNote`·`relationLabel` → 통과 (F-013-R26). 다시 분석한 zheng 그래프(`source=DDL` + 신뢰도 `INFERRED` + `fromUpgrade`)에서 4건이 점선과 그 라벨로 나오는 것을 확인
- [x] T29. 뷰어(`js/core/erd.js`의 `splitAreas`·`exportModel`·`exportRelationLabel`·`relationLabel`, `js/ui/view-erd.js`)를 같은 규칙으로 고침: 테스트 먼저(`erd-areas.test.js`, `erd.test.js`의 `F-013-AC24~AC29 (뷰어)`), 대조 테스트 `sample-export.test.js`의 ERD 부분을 새 `index.md` 형식에 맞춤(`CODEATLAS_MERMAID_MAX_CHARS`로 한도를 바꾼 산출물도 견준다)
- 확인(설치 없이): export 모듈의 클래스로 실제 그래프의 ERD 산출물만 써서(임시 드라이버) 뷰어 대조 테스트를 돌렸다. 공통컴포넌트(기본·Oracle), jeesite·zheng(기본 한도와 낮춘 한도), 전자정부 템플릿 둘, jpetstore, 갈래를 채운 합성 그래프(한도 3000·12000·45000)에서 `index.md`·`erd-<영역>.mmd`가 뷰어와 글자까지 같고, 노드·엣지 순서를 뒤집어도 파일이 같다. jeesite·zheng의 기본 한도 산출물은 고치기 전과 바이트까지 같다. CLI로 만든 산출물 전체(통합 빌드)로는 확인하지 못했다

## 6차 수정 (2026-10-11)
- [x] T30. F-013-AC30 테스트(`ErdTest.drawsDirectionWithoutCardinalityWhenOnlyDirectionIsKnown`, `keepsKnownCardinalityAndTreatsAnyInferredUnknownAsUnknown`) → 실행해 단언 실패 확인(모델이 `[MANY, ONE]`, Mermaid가 `P ||..o{ C1 : "P_ID (추론 1건)"`) → `ErdModelBuilder.cardinality`(추론 관계의 `cardinality=unknown`은 계산하지 않음), `Relation.directionFrom`·`cardinalityUnknown()`, `ErdLabels.inferredNote`(`방향은 DDL, 카디널리티 모름`), `MermaidErdRenderer`(머리 주석 한 줄), `PlantUmlErdRenderer`(`부모 <.. 자식`), `DbmlErdRenderer`(`<>`) → 통과 (F-013-R27). 테이블 명세는 `inferredNote`를 써서 따로 고치지 않았다
- [x] T31. 기존 테스트 `ErdTest.computesCardinality`(F-013-AC6)의 "추론 관계의 `unknown`이면 계산한다" 경우가 R27과 어긋나 실패 → 그 경우를 DDL 관계의 `unknown`으로 옮기고(계산한다는 단언은 그대로) 추론 관계 쪽은 AC30으로 옮김. spec의 AC6 문장과 5절 가정을 맞춰 고침
- [x] T32. 뷰어를 같은 규칙으로 고침: 테스트 먼저(`erd-direction-only.test.js`의 `F-013-AC30 (뷰어)` 6개, 실패 확인) → `js/core/erd.js`의 `relationOf`·`exportModel`·`exportRelationLabel`·`exportHeader`(영역 글자 수 계산 포함)·`markers`·`relationLabel`·`toMermaid`(`cardinalityUnknown` 순번)·`LEGEND`, `js/ui/view-erd.js`의 범례 그림·요약·안내. 범례 항목 수를 단언하던 기존 테스트 넷(`erd.test.js`, `real-shapes.test.js`, `viewer-html.test.js` 2곳)을 다섯 항목으로 고침
- 확인(설치 없이): export 모듈의 클래스로 실제 그래프의 ERD 산출물만 써서(임시 드라이버) 뷰어 대조 테스트(`sample-export.test.js`의 ERD 두 개)를 돌렸다. 공통컴포넌트(6건, 영역 30개), jeesite, zheng, sample-shop에서 `erd.mmd`·`index.md`·`erd-<영역>.mmd`가 뷰어와 글자까지 같다. 공통컴포넌트의 바뀐 영역 다이어그램 넷은 실제 mermaid 11.17.2 파서(`mermaid-check.cjs`)를 통과했다. 그런 관계가 없는 다섯 프로젝트의 `erd/`는 고치기 전과 바이트까지 같다. PlantUML의 `<..`는 실제 파서로 확인하지 못했다. CLI로 만든 산출물 전체(통합 빌드)로는 확인하지 못했다
- 남은 것: 뷰어의 관계 상세(엣지 요약, `js/core/graph.js`의 `edgeSummary`)는 이 작업의 범위가 아니라 고치지 않았다(지금은 `NTT_ID → NTT_ID · 추론 2건`). 그린 선에 화살촉을 더하려면 `js/ui/diagram.js`가 `cardinalityUnknown` 순번을 받아 처리해야 한다. F-016 spec의 범례 문장(항목 넷)과 F-001 design.md는 이 작업에서 고치지 않았다

## 7차 수정 (2026-10-11, 4차 재검증 후속)
- [x] T33. F-013-AC31 테스트(`ErdRound7Test` 2개) → 실행해 단언 실패 확인 → `ErdModelBuilder.cardinality`(추론이면 부모 쪽 0..1), `ErdLabels.INFERRED_PARENT_OPTIONAL`, Mermaid·PlantUML·DBML 머리 주석 → 통과 (F-013-R28). 기존 테스트의 기대값(`||..` → `|o..`, 머리 주석) 수정: `ErdTest` 4개, `StandardExporterTest.writesErd`
- [x] T34. F-013-AC32·AC33 테스트(`ErdRound7Test` 2개) → 실패 확인(`systemCatalogNames`는 빈 구현을 먼저 넣어 단언 실패로 확인) → `ErdModelBuilder.collectCandidates`, `ErdModel.systemCatalogNames`, `ErdLabels.systemCatalogNote`, `TableSpecRenderer`(머리 줄, 끝의 절), `StandardExporter`(`erd/index.md`), `SummaryReport`(머리 줄) → 통과 (F-013-R29)
- [x] T35. 뷰어를 같은 규칙으로 고침: 테스트 먼저(`round7.test.js`의 `F-016-AC79`·`AC80`, 실패 확인) → `js/core/erd.js`의 `exportModel`·`exportHeader`·`markers`·`selectScope`·`LEGEND`, `js/ui/view-erd.js`(요약의 "시스템 카탈로그 n개 제외", 범례 그림, 안내) → 통과. 기존 뷰어 테스트의 기대값 수정: `erd.test.js`, `erd-direction-only.test.js`, `erd-areas.test.js`, `new-attrs.test.js`, `real-shapes.test.js`, `viewer-html.test.js`
- 실제 그래프(jeesite, S5의 그래프를 새 출력기로 다시 냄): ERD 엔티티 67 → 57(카탈로그 10개 제외. sql의 이번 수정 뒤에는 12개), 관계 67 → 60(카탈로그 사이의 추론 관계 7건 제외), 추론 관계 37건이 모두 `|o..`(전에는 `||..` 22건, `|o..` 15건)

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음)

## 기록
- ERD 테스트(T2~T8)는 테스트를 먼저 썼지만 처음 실행은 구현 뒤였다(13개 중 1개가 이름 패턴 범위에서 실패 → 구현 수정). 그 뒤 작업(T9~T11과 F-014, F-015)은 실패를 실행으로 확인하고 구현했다.
