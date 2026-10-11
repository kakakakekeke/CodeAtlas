---
type: tasks
id: F-010
updated: 2026-10-11
---

# F-010 JavaScript/TypeScript 분석 작업 목록

## 구현 (작업마다 테스트 먼저)
- [x] T0. tree-sitter 바인딩 확인(ADR-0005 Confirmation): Maven Central 좌표·버전·네이티브 포함 여부 확인, pom 의존 추가
- [x] T1. F-010-AC1 파서 스모크 테스트(JS, JSX, TS, TSX, 구문 오류) → 실패 확인 → `SyntaxNode`·`JsParser`·`TreeSitterJsParser` 구현 → 통과 (F-010-R1)
- [x] T2. F-010-AC2 구문 오류·파일 실패 격리 (F-010-R2)
- [x] T3. F-010-AC3, AC4 모듈·함수 노드, 이름 경로, CONTAINS, attrs (F-010-R3)
- [x] T4. F-010-AC5 IMPORTS와 경로·별칭 해석 (F-010-R4)
- [x] T5. F-010-AC6, AC7, AC8 CALLS 해석(스코프, import, 멤버, this, JSX, ref, 미해결) (F-010-R5)
- [x] T6. F-010-AC9 HTTP 요청 API별 추출과 메서드 결정 (F-010-R6)
- [x] T7. F-010-AC10, AC11 URL 식 평가, 쿼리·외부 URL, 미해결 (F-010-R7)
- [x] T8. F-010-AC12 axios 인스턴스 baseURL (F-010-R8)
- [x] T9. F-010-AC13 내장 소스(주인 모듈, 위치 변환, 조각 병합, `__JSP_EXPR__`) (F-010-R9)
- [x] T10. F-010-AC14, AC15 레거시 전역 해석 (F-010-R10)
- [x] T11. F-010-AC16 요청 래퍼 전파 (F-010-R11)
- [x] T11a. (추가) 샘플 대조에서 나온 버그: baseURL만 정적인 URL이 요청으로 나감, 경로 변수만 받는 함수가 래퍼로 취급됨, `this.m = this.m.bind(this)`가 메서드를 가림 → 재현 테스트 먼저 추가 후 수정
- [x] T11b. (추가) 프로젝트 모듈이 내보낸, 함수인지 알 수 없는 값을 직접 부르면 미해결로 남긴다 (F-010-R5)
- [x] T11c. (추가) 흔하지 않은 문법과 큰 입력에 대한 견고성 테스트 (F-010-R2)
- [x] T12. F-010-AC17 압축 파일 건너뛰기 (F-010-R12)
- [x] T13. F-010-AC18 TypeScript 관용 처리 (F-010-R1, R5, R6)
- [x] T14. `META-INF/services` 등록, `order()=200` 확인 테스트
- [x] T15. samples/sample-shop 으로 돌려 보고 expected 와 대조: 화면 요청(requestUrl 형태) 33건, JS 노드 38건 모두 일치. 금지 항목 위반 없음
- [x] T16. shade를 흉내 낸 단일 jar에서 네이티브 라이브러리 로드 확인(스크래치에서 수동 확인, JDK 24 경고 확인)

- [x] T17. (감사 E2) F-010-AC19, AC20 재현 테스트(`JsAnalyzerCallKindTest`) → 실패 확인(`f.bind`가 `call`로, 옵션 객체의 단축 프로퍼티가 누락) → `f.bind(x)`를 `ref`로, `{ success }`를 `ref`로 → 통과 (F-010-R13)
- [x] T18. (감사 U4) F-010-AC21 재현 테스트(`JsAnalyzerEmbeddedTest`의 `urlIsRestoredFromSubstitutions`, `urlFallbackWithoutSubstitutions`, `urlRestoredAcrossFragments`) → 실패 확인(`url`에 `__JSP_EXPR__`) → `Substitutions`(치환 목록 읽기·되살리기), 식이 놓인 조각 찾기 → 통과 (F-010-R14, F-011-R14와 짝)
- [x] T19. (감사) F-010-AC22 재현 테스트(`JsAnalyzerCallbackTest.propCallbacks`) → 실패 확인 → JSX 속성으로 넘긴 함수 수집, props 호출 지점에서 `viaProp` 엣지 → 통과 (F-010-R15)
- [x] T20. (감사) F-010-AC23 재현 테스트(`JsAnalyzerCallbackTest`의 `skippedCallbackCallsAreReported`, `skippedParameterCallbacks`) → 실패 확인 → 매개변수·props로 받은 함수의 생략 집계와 `CALLS_SKIPPED` 진단 → 통과 (F-010-R16)
- [x] T21. 샘플 회귀(jsp + web을 samples/sample-shop에 돌려 정답과 대조): 화면 요청 33건, JS 노드 38건 그대로. 달라진 것은 `ProductStock.constructor → handleSave`의 `callType`(call → ref), 인라인 스크립트 요청 3건의 `url`(JSP 원문), `CALLS_SKIPPED` 3건(OrderList.jsx:47, OrderDetail.tsx:76, common.js:37)뿐

- [x] T22. (공개 프로젝트 검증 B14) F-010-AC28, AC29, AC30 재현 테스트(`JsAnalyzerParsingTest`의 `usingAsIdentifier`, `htmlCommentsInScript`, `ampersandInJsxText`) → 실패 확인(`PARSE_FAILED`) → `TreeSitterJsParser.sanitize`에 길이 보존 입력 보정(`using`, 줄 맨 앞 `<!--`·`-->`, 홑 `&`) → 통과 (F-010-R19)
- [x] T23. (검증 P2) F-010-AC24, AC25 재현 테스트(`JsAnalyzerVendorTest`의 `vendorScriptsAreSkipped`, `vendorOptions`) → 실패 확인 → `VendorRules`(경로·파일 이름·머리 주석·glob), `skipped=vendor`, 폴더 단위 `FILE_SKIPPED`, 옵션 `web.skipVendor`·`web.vendorPatterns` → 통과 (F-010-R17)
- [x] T24. (검증 P1) F-010-AC26, AC27 재현 테스트(`JsAnalyzerEmbeddedTest`의 `coLoadedByOuterPage`, `builtinGlobalsAreNotLinked`)와 AC14·AC15 개정(`includedGlobals`, `classicScriptGlobals`) → 실패 확인 → `PageScripts`(페이지가 싣는 스크립트), `Evaluator.globals`를 "함께 싣는 페이지" 근거로 교체, `BUILTIN_GLOBALS`, `viaPage` → 통과 (F-010-R18). 프로젝트 전체 이름 매칭에 기대던 기존 테스트 2개(`JsAnalyzerRequestsTest.requestWrappers`, `JsAnalyzerCallbackTest.skippedParameterCallbacks`)는 두 파일을 함께 싣는 페이지를 픽스처에 더했다(기대값은 그대로)
- [x] T25. (검증 B15 a·b·c) F-010-AC31, AC32, AC33 재현 테스트(`JsAnalyzerRequestOptionsTest`의 `optionsVariableMethod`, `objectPropertyUrl`, `unknownPrefixVariants`) → 실패 확인 → 옵션 변수·옵션 매개변수의 메서드(`Evaluator.optionMethods`, `Template.optionsParam`), 객체 후보와 React 상태(`Sym.state`, `objectCandidates`, `memberCandidates`), 호출 지점 단위 후보 정리(`Candidate`, `declareCandidates`), 빈 문자열 후보 제거 → 통과 (F-010-R20, R21, R22)
- [x] T26. (검증 B15 d·e) F-010-AC34, AC35 재현 테스트(`pluginOptionUrls`, `formMethodFromPage`) → 실패 확인 → `emitOptionRequests`(`api=option`), `formMethod`(같은 함수의 `.method` 대입, 페이지의 `formMethods`) → 통과 (F-010-R23, R24. R24는 F-011-R16과 짝)
- [x] T27. (검증) F-010-AC36 재현 테스트(`dynamicFunctionNames`) → 실패 확인 → `Collector.register`에서 `__JSP_EXPR__` → `{*}`, `dynamicName` → 통과 (F-010-R25)
- [x] T28. (검증) F-010-AC37 확인 테스트(`edgeLocationsFollowCallOrder`): 엣지 위치가 이미 호출 지점이라 구현 변경 없이 통과
- [x] T29. 샘플 회귀(jsp + web을 samples/sample-shop에 돌려 전후 대조): 화면 요청 33건, JS 노드 38건 그대로. 달라진 것은 전역 `CALLS` 3건의 `viaPage` 추가와 `JSP_PAGE` 2개의 `formMethods` 추가뿐. `common.js`·`order.js`·`member.js`는 벤더로 판정되지 않는다
- [x] T30. 공개 프로젝트 전후 대조(jsp + web): jeesite-v1 web 49.3초 → 약 5초, `JS_FUNCTION` 9,268 → 884, 전역 `INFERRED` `CALLS` 2,806 → 25, `HTTP_REQUEST` 3,061 → 353. egovframe-template-simple-react 요청 172 → 134, 메서드 `ANY` 158 → 1. egovframe-web-sample 폼 요청 8건 `ANY` → `POST` 5·`GET` 3. zheng `api=option` 46건(소스 대조 46건 참)
- [x] (2차 후속) F-010-AC38 진행 상황 훅(`ProgressHookTest`) → 실패 확인 → `ctx.progress` 두 줄 → 통과 (F-010-R26. `mvn -q -pl codeatlas-web clean test`, 63개)

- [x] T31. (재검증 N1) F-010-AC39, AC47 재현 테스트(`JsAnalyzerCorpusRound3Test`의 `methodAndUrlArePairedPerObjectLiteral`, `candidatesAreNarrowedByBranch`) → 실패 확인(`POST /board/{*}`·`PUT /board`가 생김, 가지마다 두 메서드) → 같은 객체의 프로퍼티면 리터럴 단위로 짝짓기(`Evaluator.candidateMember`·`pairedCandidates`, `Variant`), 짝을 모르면 URL마다 `ANY`(`declareCandidates`), 다른 `if` 가지의 대입 제외(`reachable`)와 조건으로 좁히기(`narrowByConditions`) → 통과 (F-010-R27)
- [x] T32. (재검증 N2) F-010-AC40 재현 테스트(`showModalDialogIsNavigation`) → 실패 확인 → `RequestMatcher`에 `showModalDialog`·`showModelessDialog`(같은 이름의 프로젝트 선언이 실려 있지 않을 때만) → 통과 (F-010-R28)
- [x] T33. (재검증 N6) F-010-AC41 재현 테스트(`carriageReturnOnlyLineEnds`) → 실패 확인(`PARSE_FAILED …:1`) → `TreeSitterJsParser.sanitize`에서 CR 단독 → LF, 줄 수 세기(`lineCount`)와 머리 주석 읽기도 CR 인식 → 통과 (F-010-R29)
- [x] T34. (재검증 N9-a) 재현: 지금 그래프의 `GET /login` 8건은 `navigate(…)`가 아니라 `window.location.href = URL.LOGIN`(프로젝트의 라우트 상수)에서 나온다. `navigate(…)`·`<Link to>`는 종전에 요청으로 잡히지 않았다. F-010-AC42 재현 테스트(`clientRoutes`) → 실패 확인 → 라우터 import 모듈의 이동·링크를 `api=route`·`clientRoute=true`로(`RequestMatcher.route`, `Collector.routeAttribute`, `emitRouteLinks`), `<Route path>` 선언과 맞는 `location` 이동에 `clientRoute=true`(`collectClientRoutes`, `isClientRoute`) → 통과 (F-010-R30)
- [x] T35. (재검증 N9-b) F-010-AC43 재현 테스트(`jspMarkupInScriptFile`) → 실패 확인(경로가 `<c:url …/>` 원문) → `Evaluator.jspMarkup` → 통과 (F-010-R31)
- [x] T36. (재검증) F-010-AC44 재현 테스트(`pluginDefaultMethods`) → 실패 확인(`ANY`) → `pluginDefaultMethod` → 통과. AC34 테스트(`pluginOptionUrls`)의 DataTables 기대값을 `ANY` → `GET`으로 개정 (F-010-R32)
- [x] T37. (재검증) F-010-AC45, AC46 재현 테스트(`decoratorScriptsAreGlobalForContentPages`, `tilesTemplateScriptsAreGlobalForBodies`) → 실패 확인 → `PageScripts`를 묶음(`Group`) 단위로 다시 씀: SiteMesh 엣지는 "함께 실리는 것"에만, Tiles는 엣지를 따라가지 않고 설정 파일 attrs로 정의별 묶음 → 통과 (F-010-R33. F-011-R21·R22와 짝)
- [x] T38. (재검증 N13 e) F-010-AC48 재현 테스트(`uniqueGlobalWithoutLoadEvidence`) → 실패 확인 → `Evaluator.projectGlobals`, `unverifiedGlobal`, `loadUnverified`, `CALLS_SKIPPED`의 "정의가 여럿인 전역 이름" → 통과. AC14·AC15 테스트(`includedGlobals`, `classicScriptGlobals`)의 "함께 싣는 페이지가 없으면 엣지가 없다"를 개정 (F-010-R34)
- [x] T39. (재검증 N13 b) F-010-AC49 재현 테스트(`minifiedScriptSibling`) → 실패 확인 → `PageScripts.addMinifiedSiblings` → 통과 (F-010-R35)
- [x] T40. (재검증 N19) F-010-AC50 재현 테스트(`locationAssignmentAndPrefixedUrls`) → 실패 확인(`location = url`이 암묵적 전역 `location`을 만들어 그 스크립트의 모든 `location` 이동이 안 잡힘) → `Collector.finish`에서 `location` 제외, `prefixedArgument`(`iframe:`·`url:`·jBox의 `get:`·`post:`), layer `type: 2`, 새로 고침 제외 → 통과 (F-010-R37)
- [x] T41. (재검증 N21) F-010-AC51 재현 테스트(`queryOnlyAndDoubleSlash`) → 실패 확인 → `UrlValue.resolve`(쿼리 조각 가르기, 경계 슬래시 합치기, 맨 앞 컨텍스트 하나만, `;jsessionid`) → 통과 (F-010-R38)
- [x] T42. (재검증 N26) F-010-AC52 재현 테스트(`vendorAndMinifiedByContent`) → 실패 확인 → `VendorRules`(`plugins/<이름>/`, 빌드 산출물 이름, 라이선스 문구 + Author·Version, 큰 생성물), 내용으로 본 압축 판정(`isMinified`) → 통과 (F-010-R36)
- [x] T43. 샘플 회귀(jsp + web을 samples/sample-shop에 돌려 전후 대조): 화면 요청 33건, JS 노드 38건 그대로. JS·JSP 노드와 `CALLS`·`REQUESTS`·`IMPORTS`·`INCLUDES` 엣지, 진단이 수정 전과 글자 하나까지 같다
- [x] T44. 공개 프로젝트 전후 대조(jsp + web): egovframe-template-simple-react 메서드×URL 곱 지점 12곳의 요청 56 → 28(짝지은 4곳은 소스대로 3건씩, 가지로 좁힌 6곳은 2건씩, 짝을 모르는 2곳은 `ANY` 2건씩), `api=route` 266건, `location` 이동 9건에 `clientRoute`. egovframe-common-components `showModalDialog` 호출 지점 71곳(주석 4곳 제외) 가운데 요청 41 → 66곳(나머지 5곳은 URL이 동적이라 미해결), `PARSE_FAILED` 4 → 3. jeesite-v1 전역 `CALLS`(`resolution=global`) 25 → 110(표본 10건 모두 참), `JS_FUNCTION` 884 → 339(`oryx.debug.js` 등 벤더 제외), `api=navigation` 5 → 26, `api=tag` 41. zheng `api=option`의 `ANY` 18 → 0(컨트롤러 매핑과 일치), `CALLS` 159 → 213

- [x] T45. (4차 재검증 M1) F-010-AC53 재현 테스트(`JsAnalyzerCorpusRound4Test.methodCandidatesAreKept`) → 실패 확인(`ANY`에 후보가 없음, 파생 URL이 짝을 잃음) → URL마다 노드 하나 + `httpMethods`(`declareCandidates`), 파생 식의 짝짓기(`Evaluator.referenceTo`, 객체 변수를 후보 리터럴로 고정하고 URL 식 평가 `pairedCandidates`) → 통과 (F-010-R39)
- [x] T46. (4차 M10) F-010-AC54 재현 테스트(`vendorBannerIsNarrowed`) → 실패 확인(`own1.js`가 `skipped=vendor`) → `VendorRules.hasLicenseBanner`를 정식 문구·`@license`·라이선스 주소·프로젝트 URL로 좁힘, 머리 주석 후보를 미뤄 두었다가 정합성 검사(`settleBannerCandidates`, `calledTopLevelFunctions`, `libraryShapedName`), `FILE_SKIPPED`에 근거 → 통과. AC24 테스트(`vendorScriptsAreSkipped`)의 진단 메시지 기대값을 개정 (F-010-R40)
- [x] T47. (4차 M13 b) F-010-AC55 재현 테스트(`hiddenFieldUrls`) → 실패 확인 → `Evaluator.fieldValues`(`.value`·`.val()`), `JsProjectAnalysis.fieldUrls`·`fieldTable`(페이지의 `formFields`), 싣는 페이지를 모르는 파일은 프로젝트에 값이 하나일 때만 → 통과 (F-010-R41. F-011-R29와 짝)
- [x] T48. (4차 M13 c) F-010-AC56 재현 테스트(`jsxServerRequests`) → 실패 확인 → `Collector.hostAttribute`, `emitHostLinks` → 통과. AC42 테스트(`clientRoutes`)의 `<a href="/plain/anchor">` 기대값을 개정(요청 없음 → `api=link`) (F-010-R42)
- [x] T49. (4차 M13 d) F-010-AC57 재현 테스트(`rootNavigation`) → 실패 확인(미해결 `"/"`) → `api=navigation`의 루트 경로 허용(`isRoot`) → 통과 (F-010-R43)
- [x] T50. (4차 M13 e) F-010-AC58 재현 테스트(`elTernaryInInlineScript`) → 실패 확인(`{*}`) → `Evaluator.embeddedLiterals`, `Substitutions.originalAt` → 통과 (F-010-R44. F-011-R14의 치환 목록을 읽는다)
- [x] T51. 샘플 회귀(jsp + web을 samples/sample-shop에 돌려 전후 대조): 화면 요청 33건, JS 노드 38건 그대로. 노드·엣지·진단이 수정 전과 글자 하나까지 같다(벤더 진단 없음)
- [x] T52. 공개 프로젝트 전후 대조(jsp + web): egovframe-template-simple-react `ANY` 요청 5건 가운데 4건에 `httpMethods=[POST, PUT]`(나머지 하나는 래퍼 자신), 미해결 `http-url` 9 → 0(루트 이동 9건이 `clientRoute`), JSX 요청 4건(`<a href>` 3건은 `clientRoute`, `<img src>` 1건). jeesite-v1 벤더 판정은 `ProcessDiagramCanvas.js` 하나만 벤더 → 분석으로 바뀌고(분석 136 → 137, 벤더 942 → 941) 그 파일의 `CALLS` 208건이 생김. zheng·전자정부 3종의 벤더 판정은 그대로. egovframe-enterprise-business-template 미해결 `http-url` 7 → 3(hidden 필드의 팝업 URL 4건), egovframe-common-components 43 → 39(4건)

- [x] T53. (5차 재검증) F-010-AC59 재현 테스트(`JsAnalyzerCorpusRound5Test.markupStringRequests`) → 실패 확인(요청 없음) → `Markup`(평가한 문자열에서 태그의 URL 속성을 조각의 나열로 떼기), `JsProjectAnalysis.markupExpressions`·`emitMarkupRequests`(DOM에 넣는 자리, 서버 경로 꼴만, 정적 조각이 없으면 미해결, 매개변수면 요청 래퍼 `Template.markup`), `Collector.augmented`(`innerHTML +=` 지점) → 통과 (F-010-R45)
- [x] T54. (5차 재검증) F-010-AC60 재현 테스트(`elTernaryInQueryIsNotExpanded`) → 실패 확인(쿼리 `dts=1`) → `Evaluator.embeddedLiterals`가 쿼리 값 자리의 삼항을 펼치지 않음(`inQuery`) → 통과. AC58 테스트는 기대값 그대로 통과 (F-010-R44 개정, F-011-R31과 짝)
- [x] T55. 샘플 회귀(전체 파이프라인을 samples/sample-shop에 돌려 전후 대조): 노드·엣지·진단이 수정 전과 같다
- [x] T56. 공개 프로젝트 전후 대조(전체 파이프라인, 새 요청 51건을 모두 소스와 대조): egovframe-enterprise-business-template 새 요청 11건(지적된 11곳 전부, `MATCHES` 11건), egovframe-common-components 28건(`page` 상수를 끼운 `<iframe src>` 27건과 `innerHTML`의 `<a href>` 1건, `MATCHES` 28건. `if`/`else`로 갈린 `page` 4곳 8건은 `INFERRED`), zheng 12건(모두 요청 래퍼 `Tab.addTab`의 호출 지점, `MATCHES` 11건), jeesite-v1 0건. 새 미해결 `http-url` 7건(데이터로 그리는 메뉴·파일 링크 6건, 래퍼 자신 1건). jeesite-v1 `ckfinder.tag:16`의 쿼리가 `dts={*}`로, `REQUESTS`가 `INFERRED` → `RESOLVED`. 처음 돌린 결과에서 거짓 4건(끝나지 않은 `<img src>`의 `/images/…/menu_folder{*}`)과 래퍼의 상대 경로 3건(`home`, `crud.html`)을 보고 조건을 좁혔다

## 마무리
- [x] 모든 수용 기준 통과 확인 (`mvn -q -pl codeatlas-web clean test`, 62개. 3차 뒤 77개, 4차 뒤 83개, 5차 뒤 85개)
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (문서 정리 때 확인: architecture.md 4·5절에 반영돼 있다)
