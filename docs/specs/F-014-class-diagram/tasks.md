---
type: tasks
id: F-014
updated: 2026-10-11
---

# F-014 클래스 다이어그램 출력 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-014-AC1, AC5, AC7, AC8 테스트 → 실패 확인 → `ClassDiagramOptions`, `ClassDiagramModel`, `ClassDiagramBuilder` 구현 → 통과 (F-014-R1, R4, R6, R7)
- [x] T2. F-014-AC2, AC3 테스트 → 실패 확인 → `MermaidClassRenderer` 구현 → 통과 (F-014-R2, R3)
- [x] T3. F-014-AC4 테스트 → 실패 확인 → `PlantUmlClassRenderer` 구현 → 통과 (F-014-R2, R3)
- [x] T4. F-014-AC6, AC9, AC10, AC11 테스트 → 실패 확인 → 멤버 수준, 상한, 패키지 묶음, 관계 종류 옵션 → 통과 (F-014-R5, R8, R9, R10)
- [x] T5. F-014-AC12 테스트 → 실패 확인 → 정렬·방어 처리, classDiagram 문법 검증기(테스트용) → 통과 (F-014-R11)
- [x] T6. F-014-AC13 테스트 → 실패 확인 → `StandardExporter`의 클래스 다이어그램 산출물 → 통과 (F-014-R12)

## 독립 감사 후속
- [x] T7. F-014-AC14, AC15에 해당하는 테스트의 `@DisplayName`에 F-014 ID를 추가: `StandardExporterTest.omitsArtifactsWithoutSubjects`, `SummaryReportTest.notesMissingSubjects`(AC14), `ClassDiagramTest.reportsUnresolvedTypeNames`(AC15. `ClassDiagramModel.unresolvedNames()` 단위 테스트는 export 모듈에 이미 있었다). 동작은 바꾸지 않았다 (F-014-R13, R14)

## 실제 프로젝트 검증에서 찾은 문제 (2026-10-11)
- [x] T8. F-014-AC16 테스트(`LargeArtifactSplitTest.reducesAndSplitsClassDiagrams`) → 실패 확인 → `StandardExporter`의 멤버 줄이기, `ClassDiagramSplitter` → 통과 (F-014-R15). 실제 그래프(cc-oracle): 55,196자였던 `egovframework.com.sym.ccm.mmd`가 멤버 없이 그려져 한도 안에 든다

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음)

## 기록
- 문서 정리(2026-10-11): 통합 단계 개선에서 들어온 동작 둘을 spec에 F-014-R13(AC14), F-014-R14(AC15)로 적었다. 구현과 테스트는 이미 있었고 테스트 이름은 F-015-AC19, F-001-AC49다(코드는 고치지 않았다).
- 실패 확인(RED)은 테스트를 먼저 쓰고 실행해 확인했다. 새 API를 쓰는 테스트는 대부분 컴파일 실패로, 기존 API의 동작을 바꾸는 테스트는 단언 실패로 확인했다.
