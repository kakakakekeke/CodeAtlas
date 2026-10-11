---
type: tasks
id: F-017
updated: 2026-10-11
---

# F-017 Spring XML 설정 해석 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. 위치를 주는 XML 파서(StAX, 외부 DTD·엔티티 차단)와 F-017-AC12 테스트 → 실패 확인 → 구현 → 통과 (F-017-R12)
- [x] T2. F-017-AC1 빈 정의 테스트 → 실패 확인 → 구현 → 통과 (F-017-R1)
- [x] T3. F-017-AC2, AC3 주입 테스트 → 실패 확인 → 구현 → 통과 (F-017-R2, R3)
- [x] T4. F-017-AC4 상속·alias·util·import·component-scan 테스트 → 실패 확인 → 구현 → 통과 (F-017-R4)
- [x] T5. F-017-AC5 MyBatis 연결 설정 테스트 → 실패 확인 → 구현 → 통과 (F-017-R5)
- [x] T6. F-017-AC6 mvc·aop 테스트 → 실패 확인 → 구현 → 통과 (F-017-R6)
- [x] T7. F-017-AC7 배치 잡·스텝·전이 테스트 → 실패 확인 → 구현 → 통과 (F-017-R7)
- [x] T8. F-017-AC8 스텝 구성 요소(USES) 테스트 → 실패 확인 → 구현 → 통과 (F-017-R8)
- [x] T9. F-017-AC9 독립 스텝·parent 병합 테스트 → 실패 확인 → 구현 → 통과 (F-017-R9)
- [x] T10. F-017-AC10 리더·라이터의 SQL 실행(EXECUTES, 내장 소스) 테스트 → 실패 확인 → 구현 → 통과 (F-017-R10)
- [x] T11. F-017-AC11 web.xml 테스트 → 실패 확인 → 구현 → 통과 (F-017-R11)
- [x] T12. `META-INF/services` 등록과 ServiceLoader 테스트 (F-017-R1)
- [x] T13. 전자정부프레임워크 스타일 픽스처 프로젝트(`src/test/resources/fixtures/egov-shop`) 전체 실행 테스트 → `MapperConfigurer` 미인식 실패 확인 → 구현 → 통과 (F-017-R1~R12)
- [x] T14. 가정의 경계 상황 테스트(중복 빈 이름, factory-bean, 플레이스홀더 class, parent 순환, 한 빈을 reader·writer로 함께 사용) → parent 순환 StackOverflow와 엣지 합쳐짐 실패 확인 → 구현 → 통과 (F-017-R2, R3, R4, R8)
- [x] T15. F-017-AC13 이름이 같은 빈 정의의 노드 attrs·`otherDefinitions`·중복 진단 범위·참조 해석(`BeanNameAcrossModulesTest` 3개) → 실패 확인 → `ModulePaths`, `BeanRegistry`, `BeanGraphWriter` → 통과 (F-017-R13). 코퍼스: zheng `DUPLICATE_OBJECT_NAME` 23 → 0, egovframe-web-sample 5 → 1(남은 1건은 같은 모듈의 루트·서블릿 컨텍스트가 `messageSource`를 함께 정의한 것)
- [x] T16. F-017-AC14 `id`가 있는 확장 네임스페이스 요소를 빈으로 등록(`NamespaceElementBeanTest`) → 실패 확인 → `BeanParser.namespaceBean` → 통과 (F-017-R14). 코퍼스: zheng `dubbo:reference` 빈 12개, jpetstore-6·egovframe-web-sample의 `dataSource` 참조가 `unresolvedBeanRef`에서 `javax.sql.DataSource`로(2건, 3건 → 0)
- [x] T17. F-017-AC15 진행 상황 훅(`ProgressHookTest`) → 실패 확인 → 구현 → 통과 (F-017-R15)
- [x] T18. design.md 3.5·3.7에 spec 7.1절 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T19. (실전 재검증 2차 후속, N27) F-017-AC16 `mvc:view-controller`를 엔드포인트로(`BeanConfigFeaturesTest`) → 실패 확인 → `BeanParser.viewController` → 통과 (F-017-R16). F-017-AC6 테스트의 "엔드포인트 없음·진단 2건" 단언을 새 기준(엔드포인트 1개, `aop:config` 진단 1건)으로 고쳤다
- [x] T20. spec 7.2절의 계약 변경을 design.md에 반영 (문서 정리 라운드에서 반영함, 2026-10-11)
- [x] T21. (대상 방언 점검) F-017-AC17 줄 끝(`LineEndingTest` 9건: LF·CRLF·CR 단독·섞임) → 실패 확인(3건: CR 단독·섞임에서 시작 줄이 위쪽 줄로 밀림. 훑는 쪽을 고친 뒤에도 끝 열이 1 작음 — JDK StAX의 열 계산) → `XmlParser.LineCounter`(CR 단독), `scanTags`(끝 태그의 `>` 위치) → 통과 (F-017-R17). springxml 테스트 39 → 48개

## 마무리
- [x] samples/sample-shop 으로 실행해 결과 확인(정답 파일이 있으면 대조)
- [x] 모든 수용 기준 통과 확인
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음)
