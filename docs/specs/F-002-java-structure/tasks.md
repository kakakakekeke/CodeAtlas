---
type: tasks
id: F-002
updated: 2026-10-11
---

# F-002 Java 구조 분석 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. 일괄 파싱 기반(소스 루트 추정, classpath, 인코딩)과 F-002-AC1 테스트 → 실패 확인 → 구현 → 통과 (F-002-R1)
- [x] T2. F-002-AC2 타입·패키지 노드와 CONTAINS (F-002-R2)
- [x] T3. F-002-AC3 메서드·필드 노드와 attrs (F-002-R3)
- [x] T4. F-002-AC4 EXTENDS, IMPLEMENTS (F-002-R4)
- [x] T5. F-002-AC5 ASSOCIATES (F-002-R5)
- [x] T6. F-002-AC6 DEPENDS_ON과 JDK 옵션 (F-002-R6, R8)
- [x] T7. F-002-AC7 ANNOTATED_BY (F-002-R7)
- [x] T8. F-002-AC8 classpath 옵션과 프로젝트 안의 jar (F-002-R8, R12)
- [x] T9. F-002-AC9 classpath 없는 타입의 FQN 복원과 미해결 (F-002-R9)
- [x] T10. F-002-AC10 구문 오류 파일 (F-002-R10)
- [x] T11. F-002-AC11 MS949 (F-002-R11)
- [x] T12. F-002-AC12, AC13 JDK 타입 판정을 출처 기준으로, 이름 규칙의 `javax.` 목록을 실행 중인 JDK와 대조 (F-002-R13)
- [x] T13. F-002-AC14 Lombok `@Getter`/`@Setter` 합성 메서드와 필드 ACCESSES (F-002-R14)
- [x] T14. F-002-AC15 `@Data`, `@Value`, 생성자 애너테이션, `@ToString`, `@EqualsAndHashCode` (F-002-R14)
- [x] T15. F-002-AC16 `@Builder`, `@SuperBuilder` (F-002-R14)
- [x] T16. F-002-AC17 로그 필드, `@With`, `@Accessors` (F-002-R14)
- [x] T17. F-002-AC18 Lombok이 아닌 같은 이름의 애너테이션 (F-002-R14)
- [x] T18. F-002-AC19, AC20 `EXTENDS`·`IMPLEMENTS`의 `typeArguments`(`SupertypeTypeArgumentsTest`) → 실패 확인 → 구현 → 통과 (F-002-R15). 코퍼스: zheng 129개, jeesite-v1 95개 엣지에 값이 생겼다(simple-backend는 제네릭 상위 타입이 없다)
- [x] T19. F-002-AC21 진행 상황 훅(`ProgressHookTest`) → 실패 확인 → 구현 → 통과 (F-002-R16)
- [x] T20. design.md 3.5에 spec 8절의 "2차 후속" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11: 3.5 엣지 attrs `typeArguments`, 3.4 소비자 규칙, 4.1)
- [x] T21. (실전 재검증 2차 후속) F-002-AC22 classpath 폴더의 jar 펼치기와 `dir/*`(`ClasspathFolderTest`) → 실패 확인 → `JavaAnalyzer.classpath` → 통과 (F-002-R17)
- [x] T22. (N9-c) F-002-AC23, AC24 JDT 내부 로그 가로채기와 묶음에서 떨어진 파일의 단독 재파싱(`JdtLogCaptureTest`): jeesite-v1에서 원인 파일을 찾아(`JaxbMapper` → `Exceptions`) 같은 꼴의 재현 프로젝트로 실패 확인 → `JdtLog`, `JavaAnalyzer.parse` → 통과 (F-002-R18, R19)
- [x] T23. (java.lang 오해석, N16) F-002-AC25, AC26 단일 import의 우선(`ImportShadowTest`) → 실패 확인(5개 중 4개) → `JavaProject.importedInstead`·`parameterName`, `Unit.ref`·`unresolvedImports` → 통과 (F-002-R20)
- [x] T24. design.md 3.3·3.7·4.5에 spec 8절 "실전 재검증 2차 후속" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11)

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
