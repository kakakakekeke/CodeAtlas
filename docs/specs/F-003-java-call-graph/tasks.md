---
type: tasks
id: F-003
updated: 2026-10-11
---

# F-003 Java 콜 그래프 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-003-AC1 오버로드 구분 (F-003-R1)
- [x] T2. F-003-AC2, AC3 인터페이스 호출과 OVERRIDES, 제네릭 상위 타입 (F-003-R1, R5)
- [x] T3. F-003-AC4 static import, super, default, 가변 인자, 중첩 호출 (F-003-R1)
- [x] T4. F-003-AC5 생성자 호출과 INSTANTIATES (F-003-R2)
- [x] T5. F-003-AC6 람다, 메서드 참조, 익명·내부 클래스 (F-003-R3)
- [x] T6. F-003-AC7 초기화 코드의 소유자, enum 상수 본문 (F-003-R4)
- [x] T7. F-003-AC8 ACCESSES (F-003-R6)
- [x] T8. F-003-AC9 외부 호출 옵션과 요약 진단 (F-003-R7)
- [x] T9. F-003-AC10 해석 실패 호출 (F-003-R8)
- [x] T10. F-003-AC11 가장 가까운 후보 배제 (F-003-R9)
- [x] T11. F-003-AC12 external 타입의 OVERRIDES (F-003-R5)
- [x] T12. F-003-AC13 외부 호출의 JDK 판정을 F-002-R13으로 (F-003-R10)
- [x] T13. 미해석 호출을 모든 파일을 본 뒤에 해석하는 구조(`PendingCall`, `CallResolver`, 메서드 색인)와 F-003-AC14 합성 getter·setter·생성자 호출 (F-003-R11)
- [x] T14. F-003-AC15 체인의 타입 추적(빌더, getter, `var`, `@SuperBuilder`) (F-003-R11)
- [x] T15. F-003-AC16, AC17 메서드 참조·람다, 오버로드 후보의 `INFERRED`·`ambiguous` (F-003-R11)
- [x] T16. F-003-AC18 로그 필드 호출과 ACCESSES (F-003-R11)
- [x] T17. F-003-AC19 external 체인을 체인당 미해결 1건 + INFO 진단 1건으로 (F-003-R12)
- [x] T18. F-003-AC20 소스 없는 타입의 인자 불일치 호출 (F-003-R13)
- [x] T19. F-003-AC21 컴파일러가 보지 않은 코드의 선언 추적과 JDK 체인 (F-003-R14)
- [x] T20. F-003-AC22 enum 상수의 합성 생성자 호출 (F-003-R11)
- [x] T21. spec의 `callType=lambda` 언급 정리
- [x] T22. F-003-AC23 미해석 타입의 인자를 받는 외부 타입 생성자(`new`, `super(..)`)를 `<init>(?)` 호출로 (F-003-R15, R16; 독립 감사 E1)
- [x] T23. F-003-AC24 인자 수가 맞지 않는 "그럴듯한 후보"로 잇지 않기 (F-003-R15)
- [x] T24. F-003-AC25 인자 있는 enum 상수에 맞는 생성자가 없을 때 미해결 (F-003-R15, R16)
- [x] T25. F-003-AC26 체인 안쪽 링크가 사라지지 않게 (F-003-R16)
- [x] T26. F-003-AC27 미해결 호출 원문 `(…)`과 `argCount` (F-003-R8, R12)
- [x] T27. F-003-AC28 직접 상위마다의 OVERRIDES 확인 (F-003-R5. 기존 구현이 이미 충족해 테스트만 추가)
- [x] T28. 샘플 회귀(`SampleShopRegressionTest`): 정답 목록의 Java 엣지와 함정 대조
- [x] T29. F-003-AC29, AC30 매개변수 타입이 classpath에 없는 메서드의 OVERRIDES를 프로젝트 상위 타입에서 표기 비교로 찾기, 찾았으면 외부 상위 타입 추론을 하지 않기 (F-003-R17; 공개 프로젝트 검증 B7)
- [x] T30. F-003-AC31 `java-override` 미해결 원문을 `<이름>(…)` + `argCount`로 (F-003-R18)
- [x] T31. F-003-AC32, AC33 외부 타입을 상속한 프로젝트 타입으로 부르는 static·인스턴스 호출을 외부 상위 타입의 메서드로 INFERRED (F-003-R19; B19)
- [x] T32. F-003-AC34, AC35 리플렉션 기반 공통 서비스의 추론 호출(`callType=reflection`, `viaTypes`)과 `REFLECTION_CALL` 진단 (F-003-R20; B16)
- [x] T33. 공개 프로젝트 코퍼스(simple-backend, zheng, jeesite-v1)로 수정 전후 그래프 비교와 표본 대조
- [x] T34. design.md 3.4·3.5·3.6·3.7에 spec 8절의 "3차" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T35. F-003-AC36, AC37 미해석 타입 매개변수의 선언을 고른 호출에서 다른 오버로드 찾기(`UnresolvedParamOverloadCallTest`) → 자기 자신 호출 실패 확인 → 구현 → 통과 (F-003-R21)
- [x] T36. F-003-AC38, AC39 Lombok 합성 메서드가 낀 재정의의 `OVERRIDES`(`LombokOverridesTest`) → 실패 확인 → 구현 → 통과 (F-003-R22)
- [x] T37. 공개 프로젝트 코퍼스 전후 대조: 자기 자신으로의 `CALLS` zheng 2 → 1(남은 1건은 실제 재귀 `deleteDir`), jeesite-v1 22 → 21(나머지는 실제 재귀 17건과 인자 타입을 모르는 `INFERRED` 4건), simple-backend 0 → 0. 잘못 고른 오버로드가 바로잡힌 호출 jeesite-v1 16건·zheng 1건(`new Page(1, 1, -1)` 등). Lombok `OVERRIDES` simple-backend 0 → 7건
- [x] T38. design.md 3.4·3.5·7절에 spec 8절의 "2차 후속" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11: 3.4 `OVERRIDES` 행, 3.5 `lombok`, 3.6 `java-call` 행, 7절)
- [x] T39. (실전 재검증 2차 후속) F-003-AC40 풀지 못한 단일 import의 이름을 수신자·생성 타입으로 쓴 호출(`ImportShadowTest`) → 실패 확인 → `CallGraphExtractor.shadowedReceiver` → 통과 (F-003-R23)
- [x] T40. (N15) F-003-AC41, AC42 미해석 타입 인자의 오버로드를 타입 표기로 고르고 가리지 못하면 후보 전부로 `ambiguous`(`OverloadNotationTest`) → 실패 확인 → `amongOverloads`·`overloads`·`match`, 가변 인자 인식 → 통과 (F-003-R24). jeesite-v1: `addMessage` 96건 중 89건이 `(RedirectAttributes, …)`로 바뀜
- [x] T41. (N28-c, f, e) F-003-AC43~AC46 상속 호출·상속 필드·`getBean` 체인(`ResolvableReferencesTest`) → 실패 확인 → `implicitFallback`, `describe`·`describeReceiver`, `beanLookupType` → 통과 (F-003-R25~R27). F-003-AC10 테스트의 "상위 타입이 여럿이면 미해결" 단언을 새 기준으로 고쳤다
- [x] T42. design.md 3.5에 spec 8절 "실전 재검증 2차 후속" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T43. (실전 재검증 3차 후속, M2) F-003-AC47~AC51 인자 식의 타입 읽기(`OverloadArgumentTypeTest` 7개) → 실패 확인(7개 전부) → `CallGraphExtractor.estimatedType`·`declaredReturnType`·`pick`(확인하지 못한 자리가 없는 후보)·`trackedArguments`, `CallResolver.Candidate`·`Deferred`·`resolveDeferred`·`accepts`·`returnBinary`, `cannotBeUnresolvedType`의 erasure 판정 → 통과 (F-003-R28)
- [x] T44. (M2) F-003-AC52 후보가 하나뿐인 `ambiguous` 없애기(`OverloadArgumentTypeTest` 2개) → 실패 확인 → `CallResolver.inheritedCall`이 `ambiguous`를 쓰지 않음 → 통과 (F-003-R29). 기존 테스트 3개(F-003-AC10·AC33·AC43)의 "상위 타입이 여럿이면 `ambiguous`" 단언을 새 기준으로 고쳤다
- [x] T45. 공개 프로젝트 코퍼스 전후 대조(java 분석기만): `ambiguous` `CALLS`가 있는 호출 지점 공통컴포넌트 18 → 0, enterprise 12 → 0, simple-backend 4 → 0, zheng 3 → 0, jeesite-v1 18 → 11(남은 11곳은 인자가 소스 없는 타입의 메서드 결과라 알 수 없는 것). 2차 결과에서 한 대상으로 이어졌던 35곳이 같은 대상 하나로 돌아왔고, jeesite `BeanValidators.java:94`는 2차의 틀린 선택(`(ConstraintViolationException, String)`) 대신 `(Set, String)`으로 간다. 후보 하나에 `ambiguous`가 붙던 8곳은 0
- [x] T46. design.md 3.5·7절에 spec 8절 "실전 재검증 3차 후속" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11)

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
