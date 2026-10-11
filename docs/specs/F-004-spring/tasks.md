---
type: tasks
id: F-004
updated: 2026-10-11
---

# F-004 Spring 해석 (애너테이션) 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-004-AC1, AC2 스테레오타입과 빈 (jar 없음/있음) (F-004-R1, R12)
- [x] T2. F-004-AC3 `@Bean` (F-004-R2)
- [x] T3. F-004-AC4 INJECTS (F-004-R3)
- [x] T4. F-004-AC5 주입 후보 (F-004-R4)
- [x] T5. F-004-AC6 엔드포인트 (F-004-R5)
- [x] T6. F-004-AC7 뷰 이름 (F-004-R6)
- [x] T7. F-004-AC8 뷰 리졸버 프로퍼티 (F-004-R7)
- [x] T8. F-004-AC9 `@Mapper` (F-004-R8)
- [x] T9. F-004-AC10, AC11 SqlSession 호출의 EXECUTES와 미해결 (F-004-R9, R10, R12)
- [x] T10. F-004-AC12 애너테이션 SQL (F-004-R11)
- [x] T11. F-004-AC13 Lombok 생성자 주입을 합성 생성자(F-002-R14)의 매개변수와 맞춤 (F-004-R3)
- [x] T12. F-004-AC14, AC15 Java 코드의 redirect·forward 경로를 `redirects`·`forwards`로, 동적이면 미해결 (F-004-R13; 독립 감사 U6)
- [x] T13. F-004-AC16 다른 메서드(핸들러·헬퍼)를 부르는 반환식의 뷰 이름을 전이적으로 합치기 (F-004-R14; 공개 프로젝트 검증 B20)
- [x] T14. F-004-AC17 `@ModelAttribute` 메서드로의 `CALLS`(`callType=modelAttribute`) (F-004-R15; B17)
- [x] T15. F-004-AC18 요청 경로의 `${…}` 자리표시자 이름을 `placeholders`로, 정규화가 깨뜨리지 않는지 고정 (F-004-R16; B5a)
- [x] T16. F-004-AC19 필터·인터셉터·인증 핸들러의 redirect·forward 확인과 빠진 형태(`encodeRedirectURL`, `RedirectStrategy`, Shiro `WebUtils`) 추가 (F-004-R17; B18)
- [x] T17. F-004-AC20 필터·인터셉터 타입에 `stereotype=filter`·`interceptor` (F-004-R18; B18)
- [x] T18. design.md 3.4·3.5에 spec 8절의 "3차" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T19. (2차 후속) `@Component`·`@Service`가 붙은 필터·인터셉터가 타입 `stereotype`은 `filter`·`interceptor`여도 `beanName`·`DEFINES_BEAN`·`SPRING_BEAN`을 그대로 만드는지 확인: 이미 그렇게 동작해 F-004-AC20 테스트에 `beanName`·`DEFINES_BEAN` 단언만 더했다(코드 변경 없음). 링커 쪽 수정은 F-012-R27
- [x] T20. (실전 재검증 2차 후속, N7) F-004-AC21 호출 지점 병렬 목록(`ViewCallSiteTest`) → 실패 확인 → `SpringExtractor`·`SpringLinker`·`SpringModel.Views` → 통과 (F-004-R19)
- [x] T21. (N28-a, b, d) F-004-AC22, AC23 구문 ID의 접두·지역 변수, 뷰 이름 후보(`ResolvableReferencesTest`) → 실패 확인 → `SpringExtractor.executes`·`expandLocals` → 통과 (F-004-R20, R21)
- [x] T22. (N23, N27) F-004-AC24, AC25 이름이 같은 스테레오타입 빈, `@Value` 필드의 자리표시자(`StereotypeBeanAndValueFieldTest`) → 실패 확인 → `SpringLinker.declareStereotypeBeans`, `SourcePrescan.valueFields`·`SpringExtractor.placeholderTemplate` → 통과 (F-004-R22, R23)
- [x] T23. design.md 3.5·3.6·3.9에 spec 8절 "실전 재검증 2차 후속" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T24. (실전 재검증 3차 후속, M13-a) F-004-AC26 구문 ID처럼 보이는 문자열 상수 인자(`ViewHelperAndStatementLiteralTest`) → 실패 확인 → `SpringExtractor.statementIdArguments` → 통과 (F-004-R24). 처음에는 프로젝트 메서드의 인자도 넣었다가, 공통컴포넌트 재실행에서 테스트 코드의 `assertEquals`·`getMappedStatement`·프로젝트 도우미가 구문과 이어지는 것을 보고 라이브러리 메서드 호출로 좁혔다(좁힌 단언은 구현과 함께 고쳐 따로 실패를 확인하지 않았다)
- [x] T25. (M13-b) F-004-AC27 뷰 이름 도우미에 호출 지점의 인자 대입(`ViewHelperAndStatementLiteralTest` 3개) → 실패 확인 → `SpringExtractor.parameterTemplate`·`flattenConcat`·`otherOverload`·`choseUnresolvedParameter`, `SpringModel.Views.parameterTemplates`·`ViewDelegation.arguments`, `SpringLinker.substitute` → 통과 (F-004-R25). `SpringControllerFlowTest`(F-004-AC16)의 "`thymeleaf("/index")`는 미해결" 단언 둘을 새 기준(`//index.html`로 풀림)으로 고쳤다
- [x] T26. design.md 3.5·3.9에 spec 8절 "실전 재검증 3차 후속" 항목 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T27. (실전 재검증 4차 후속) F-004-AC28 프로퍼티를 읽는 꼴의 호출(`ViewHelperAndStatementLiteralTest`) → 실패 확인 → `SpringExtractor.propertyRead`(`parameterTemplate`·`placeholderTemplate`에서 부른다) → 통과 (F-004-R26). 기존 AC27 테스트 2개의 기대값을 `/{*}/…`에서 `/${app.name}/…`로 고쳤다(픽스처의 `Props.get("app.name")`). design.md 3.5·3.9 반영

## 마무리
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
