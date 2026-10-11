---
type: tasks
id: F-011
updated: 2026-10-11
---

# F-011 JSP 분석 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-011-AC1, AC2 스캐너 테스트 작성 → 실패 확인 → `JspScanner` 구현 → 통과 (F-011-R1, R2)
- [x] T2. F-011-AC3 테스트 → 실패 확인 → `JspAnalyzer`의 페이지 노드 → 통과 (F-011-R3)
- [x] T3. F-011-AC4, AC5, AC15 테스트 → 실패 확인 → 웹 루트 판정과 include 해석 → 통과 (F-011-R4, R5)
- [x] T4. F-011-AC6 테스트 → 실패 확인 → `<script src>` 해석 → 통과 (F-011-R6)
- [x] T5. F-011-AC7, AC8, AC9 테스트 → 실패 확인 → `UrlNormalizer`와 요청 추출 → 통과 (F-011-R7, R8, R9)
- [x] T6. F-011-AC10, AC11 테스트 → 실패 확인 → `ScriptSanitizer`와 내장 소스 등록 → 통과 (F-011-R10, R11)
- [x] T7. F-011-AC12 테스트 → 실패 확인 → 이벤트 핸들러 내장 소스 → 통과 (F-011-R12)
- [x] T8. F-011-AC13 테스트 → 실패 확인 → HTML 파일 처리 → 통과 (F-011-R3, R10)
- [x] T9. F-011-AC14 테스트 → 실패 확인 → 파일 단위 실패 격리 → 통과 (F-011-R13)
- [x] T10. `META-INF/services` 등록과 `ServiceLoader` 확인 테스트
- [x] T11. (추가) 구형 페이지 테스트: 대문자 태그, 따옴표 없는 속성, MS949·CRLF, 커스텀 태그의 이벤트 속성 (F-011-R1, R12)
- [x] T12. (추가) 깨진 요소가 많은 한 줄짜리 입력의 시간 한계 테스트 → 스캐너 탐색 범위 제한 (F-011-R2)

- [x] T13. (감사 U4) F-011-AC16 재현 테스트(`ScriptSanitizerTest`의 `substitutionList`·`substitutionsRestoreOriginal`, `JspAnalyzerScriptTest`의 `substitutionAttr`·`noSubstitutionsInHtml`) → 실패 확인 → `ScriptSanitizer.sanitizeTracked`(치환 목록), 내장 소스 attrs `jspSubstitutions`, 엔티티를 푼 핸들러의 오프셋 옮기기 → 통과 (F-011-R14, F-010-R14와 짝)

- [x] T14. (공개 프로젝트 검증 P2) F-011-AC17 재현 테스트(`JspAnalyzerCorpusFixTest`의 `vendorPagesAreSkipped`, `vendorOptions`) → 실패 확인 → `VendorPaths`(web과 같은 경로 규칙), 벤더 폴더의 페이지는 노드만(`skipped=vendor`), 폴더 단위 `FILE_SKIPPED` → 통과 (F-011-R15, F-010-R17과 짝)
- [x] T15. (검증 B15 e) F-011-AC18 재현 테스트(`formTable`) → 실패 확인 → 폼 표 `formMethods`, `selfPostForms` → 통과 (F-011-R16, F-010-R24와 짝)
- [x] T16. (검증 B15 f) F-011-AC19, AC20 재현 테스트(`thymeleafLinks`, `thymeleafScriptPrototype`, `thymeleafFragments`) → 실패 확인(원형용 정적 `href`를 읽음) → `Thymeleaf`(링크 식·조각 식 읽기), `th:` 속성 우선, `th:replace` 등의 `INCLUDES` → 통과 (F-011-R17, R18)
- [x] T17. (검증 B14 b) 스크립트 안의 `<!--`·`-->`: jsp는 본문을 그대로 넘기고 web이 파서 입력에서 보정하기로 했다(F-010-R19, 재현 테스트는 web의 `htmlCommentsInScript`). jsp 변경 없음
- [x] T18. 공개 프로젝트 전후 대조(jsp + web): jeesite-v1 `HTTP_REQUEST` 3,061 → 353(벤더 페이지 제외), egovframe-common-components 2,920 → 2,683, zheng Thymeleaf 링크 39건이 `th:` 식의 URL로(종전에는 원형용 상대 경로)
- [x] (2차 후속) F-011-AC21 진행 상황 훅(`ProgressHookTest`) → 실패 확인 → `ctx.progress` 한 줄 → 통과 (F-011-R19. `mvn -q -pl codeatlas-jsp clean test`, 57개)

- [x] T19. (재검증 N6) F-011-AC22 재현 테스트(`JspAnalyzerLayoutTest.carriageReturnOnlyLineEnds`) → 실패 확인(모든 위치가 1행) → `LineMap`이 CR 단독을 줄 끝으로, 페이지 본문의 CR 단독을 LF로(`LineMap.loneCrToLf`) → 통과 (F-011-R20, F-010-R29와 짝)
- [x] T20. (재검증) F-011-AC23 재현 테스트(`sitemeshDecoratorByMeta`, `sitemeshDecoratorByPattern`) → 실패 확인 → `Layouts`(`decorators.xml`·`sitemesh3.xml` 읽기, `meta`와 맞추기, 패턴 하나일 때 전 페이지) → 통과 (F-011-R21)
- [x] T21. (재검증) F-011-AC24 재현 테스트(`tilesDefinitions`) → 실패 확인 → `Layouts`(정의·extends·attribute, `FILE` attrs `tilesDefinitions`·`tilesAttributes`, template → 조각 `INCLUDES`) → 통과 (F-011-R22. attrs 형식은 링커 담당과 맞춘 것)
- [x] T22. (재검증 N13 a·b) F-011-AC25 재현 테스트(`JspAnalyzerCorpusRound3Test.scriptByLongestSuffix`) → 실패 확인 → `ProjectIndex.scriptByLongestSuffix`, 압축본 → 원본(`minOf`) → 통과 (F-011-R23)
- [x] T23. (재검증 N19) F-011-AC26 재현 테스트(`customTagUrlAttributes`) → 실패 확인 → `PageAnalysis.tagUrlAttributes`(`api=tag`) → 통과 (F-011-R24)
- [x] T24. (재검증 N20) F-011-AC27 재현 테스트(`elTernaryCandidates`) → 실패 확인(`{*}` 하나) → `UrlNormalizer.ternaryVariants` → 통과 (F-011-R25)
- [x] T25. (재검증 N21) F-011-AC28 재현 테스트(`contextSlashAndQueryOnly`) → 실패 확인(`//twitter/…`가 외부 URL, `GET /{*}&extId=…`) → `UrlNormalizer.normalize` 보정 → 통과 (F-011-R26)
- [x] T26. (재검증 N26) F-011-AC29 재현 테스트(`pluginFoldersAreVendor`) → 실패 확인 → `VendorPaths`에 `plugins/<이름>/` 규칙(web과 같은 문구) → 통과 (F-011-R15)
- [x] T27. 공개 프로젝트 전후 대조(jsp + web): jeesite-v1 `via=sitemesh` `INCLUDES` 106건(`meta`가 있는 페이지 92개: `default` 71, `blank` 7, 이름에 EL이 든 14개는 테마 둘로. 찾지 못한 이름 0건), `api=tag` 요청 41건, EL 삼항 2곳이 후보 URL 둘씩. zheng `<script src>` `INCLUDES` 110 → 126(다른 모듈의 `zheng-admin/src/js/common.js` 등). samples/sample-shop은 수정 전과 같다

- [x] T28. (4차 재검증 M6) F-011-AC30 재현 테스트(`JspAnalyzerCorpusRound4Test.dynamicDecoratorPicksOwnTheme`) → 실패 확인(두 테마 모두에 이어짐) → `Layouts.closestByDirectory`, `dynamicDecorator`·`ambiguous` → 통과 (F-011-R27)
- [x] T29. (4차 M13 a) F-011-AC31 재현 테스트(`selfSubmitForms`) → 실패 확인 → `PageAnalysis.selfSubmitRequests`(`{self}`, `selfSubmit=true`, `action` 대입·제출 흔적 판정, HTML의 GET 폼 제외) → 통과 (F-011-R28. attrs 이름은 링커 담당과 맞춘 것)
- [x] T30. (4차 M13 b) F-011-AC32 재현 테스트(`hiddenUrlFields`) → 실패 확인 → `PageAnalysis.inputField`, 열린 폼 추적(`openFormKeys`), `formFields` → 통과 (F-011-R29, F-010-R41과 짝)
- [x] T31. (4차 M10 c) 벤더 페이지 요약 진단에 판정 근거: AC17 테스트(`vendorPagesAreSkipped`, `vendorOptions`)의 메시지 기대값 개정 → 실패 확인 → `VendorPaths.skipped`(근거), `JspAnalyzer` → 통과 (F-011-R15, F-010-R40과 같은 꼴)
- [x] T32. 공개 프로젝트 전후 대조(jsp + web): jeesite-v1 `via=sitemesh` `INCLUDES` 106 → 92(동적 이름 28건 → 14건, 모두 자기 테마. `ambiguous` 0건). `selfSubmit` 요청: egovframe-enterprise-business-template 5건(`action` 없는 POST 폼 19개 가운데 스크립트가 `action`을 대입하지 않는 5개), egovframe-common-components 10건, jeesite-v1 5건, zheng 3건. `formFields`: enterprise 11개 페이지, common-components 79개 페이지. samples/sample-shop은 수정 전과 같다

- [x] T33. (5차 재검증) F-011-AC33 재현 테스트(`JspAnalyzerCorpusRound5Test.submitTracesArePairedWithTheForm`) → 실패 확인(`testAuditView.jsp`에 `POST {self}`) → `SubmitTraces`(`.submit()`의 수신자를 폼의 이름·ID·순번·위치와 맞춤, 매개변수는 호출 자리의 인자, `submitHandler`는 `validate`의 수신자, 주석 제외), `PageAnalysis`의 제출 컨트롤을 토큰으로(`onclick`·`onsubmit`의 `return false`), 폼 목록(`forms`) → 통과. AC31 테스트(`selfSubmitForms`)는 기대값 그대로 통과 (F-011-R30)
- [x] T34. (5차 재검증) F-011-AC34 재현 테스트(`elTernaryInQueryIsNotExpanded`) → 실패 확인(쿼리 `type=0`) → `UrlNormalizer.ternaryVariants`가 쿼리 시작 뒤의 EL을 건너뜀 → 통과 (F-011-R31, F-010-R44 개정과 짝)
- [x] T35. 공개 프로젝트 전후 대조(전체 파이프라인): `selfSubmit` 요청 egovframe-common-components 10 → 7(사라진 것: `EgovComUtlHttpMonDetailLog.jsp:52`, `EgovComUtlProcessMonLogDetail.jsp:52`, `EgovCnsltPasswordConfirm.jsp:90`. 4차에 참으로 확인된 6건과 숨긴 제출 버튼의 `EgovMtgPlaceImageDetail.jsp:59`는 남음), jeesite-v1 5 → 4(`testAuditView.jsp:33`이 사라져 `ambiguous` `MATCHES` 3건이 없어짐), egovframe-enterprise-business-template 5 → 5, zheng 3 → 3. jeesite-v1의 쿼리 삼항 링크 5건(`…/delete?id=…${… ? '&isRe=true' : ''}`)이 `INFERRED` → `RESOLVED`. samples/sample-shop은 수정 전과 같다

## 마무리
- [x] 모든 수용 기준 통과 확인 (`mvn -q -pl codeatlas-jsp clean test`, 56개. 3차 뒤 66개, 4차 뒤 69개, 5차 뒤 71개)
- [x] samples/sample-shop 으로 돌려 expected 의 JSP 관련 정답과 대조 (INCLUDES 11건, JSP 요소의 요청 8건, 인라인 스크립트 요청 7건의 줄 위치 모두 일치)
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음)
