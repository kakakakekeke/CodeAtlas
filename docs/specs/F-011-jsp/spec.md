---
type: spec
id: F-011
title: JSP 분석
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-011 JSP 분석

## 1. 배경
처리 흐름의 맨 앞은 화면이다. JSP는 다른 JSP를 포함하고, 폼·링크로 서버에 요청하고, 인라인 스크립트로 AJAX 요청을 보낸다. 이 관계가 그래프에 없으면 "화면 → Controller" 구간이 끊긴다(PRD 1, 6 "화면: JSP와 JavaScript 양쪽"). 이 기능은 JSP와 순수 HTML에서 페이지 노드, 포함 관계, 요청 지점을 뽑고, 인라인 스크립트는 `js` 내장 소스로 넘겨 F-010(web)이 분석하게 한다.

## 2. 범위
- 포함: `FileType.JSP`(`.jsp .jspf .tag`)와 `FileType.HTML`의 스캔, `JSP_PAGE` 노드, `INCLUDES`(페이지→페이지, 페이지→JS 모듈), 폼·링크·프레임·리다이렉트의 `HTTP_REQUEST`와 `REQUESTS`, 인라인 스크립트와 이벤트 핸들러의 `js` 내장 소스 등록. 레이아웃 프레임워크의 설정 파일(`FileType.XML` 가운데 루트가 `decorators`·`sitemesh`·`tiles-definitions`인 것)이 붙이는 페이지 관계(3차).
- 제외: JavaScript 파싱(F-010), 요청과 엔드포인트의 대응·뷰 이름과 JSP의 대응(F-012. Tiles 정의 이름을 뷰 이름과 맞추는 것 포함), 스크립틀릿 안의 Java 코드 분석, JSP 안의 `<tiles:insertAttribute>` 해석, `web.xml`의 `include-prelude`, Struts 등 Spring 외 태그 라이브러리의 폼.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-011-R1 | 시스템은 JSP 본문을 지시자, 스크립틀릿·식·선언, JSP 주석, HTML 주석, EL, 태그(액션·커스텀·HTML)와 속성, `<script>`·`<style>` 본문으로 구분하고 각 요소의 위치(줄·열)를 알아야 한다. | 사용자 요청 |
| F-011-R2 | 만약 태그가 닫히지 않았거나 잘못 중첩되었거나 따옴표가 닫히지 않았으면, 시스템은 멈추지 않고 그 뒤의 요소를 계속 읽어야 한다. | 사용자 요청, AGENTS.md |
| F-011-R3 | 시스템은 JSP·HTML 파일마다 `JSP_PAGE` 노드를 만들고 `FILE -CONTAINS→ JSP_PAGE` 엣지와 `pageType`, `taglibs`, `scriptletCount` 속성을 기록해야 한다. | 사용자 요청, design 3.3 |
| F-011-R4 | 시스템은 `<%@ include file %>`, `<jsp:include page>`, `<c:import url>`(내부 경로), 태그 파일 사용(`tagdir` 접두사의 태그)을 `INCLUDES`(JSP_PAGE→JSP_PAGE)로 기록해야 한다. 경로는 `/`로 시작하면 웹 루트 기준, 아니면 현재 파일 기준으로 해석한다. | 사용자 요청 |
| F-011-R5 | 만약 포함 대상 파일이 프로젝트에 없거나 경로에 EL·식이 있어 정적으로 알 수 없으면, 시스템은 분류 `jsp-include`의 미해결 엣지를 남겨야 한다. | 사용자 요청, AGENTS.md |
| F-011-R6 | 시스템은 `<script src>`가 가리키는 프로젝트 안의 JS 파일을 `INCLUDES`(JSP_PAGE→JS_MODULE)로 기록해야 한다. 정확히 일치하는 파일이 없고 경로가 그 src로 끝나는 JS 파일이 하나뿐이면 `INFERRED`로 연결하고, 외부 URL과 찾지 못한 src는 엣지 없이 `externalScripts` 속성에만 남긴다. | 사용자 요청 |
| F-011-R7 | 시스템은 `<form>`, Spring `<form:form>`, `<a href>`(`<area href>` 포함), `<iframe>`·`<frame>`의 `src`, `<c:redirect url>`, `<meta http-equiv="refresh">`를 `HTTP_REQUEST` 노드와 `REQUESTS`(JSP_PAGE→HTTP_REQUEST) 엣지로 기록해야 한다. | 사용자 요청 |
| F-011-R8 | 시스템은 요청 URL에서 컨텍스트 경로 표현을 떼고, 쿼리스트링과 `#fragment`를 떼고(쿼리는 `attrs.query`), 경로 중간의 EL·식을 `{*}`로 바꾸고, 같은 페이지에서 `<c:url var>`·`<spring:url var>`로 만든 변수를 그 URL로 풀어야 한다. 외부 URL은 `external=true`, 상대 URL은 원문 그대로 `relative=true`로 표시하고, `#`·`mailto:`·`tel:`·정적 자원·빈 값은 요청으로 만들지 않는다. | 사용자 요청 |
| F-011-R9 | 만약 요청 URL이 전부 동적이어서 정적 경로 조각이 하나도 없으면, 시스템은 분류 `http-url`의 미해결 `REQUESTS` 엣지를 남겨야 한다. | 사용자 요청, AGENTS.md |
| F-011-R10 | 시스템은 인라인 `<script>` 본문(type이 없거나 JavaScript 계열)을 `js` 내장 소스로 등록하고, 주인 노드 `JS_MODULE`(`js:module:<JSP 상대경로>`)을 만들어 `JSP_PAGE -CONTAINS→ JS_MODULE`로 이어야 한다. JavaScript는 파싱하지 않는다. | 사용자 요청, design 4.1 |
| F-011-R11 | 시스템은 내장 소스로 넘기는 스크립트 안의 JSP 요소를 줄 수가 보존되게 치환해야 한다(값을 내는 요소는 `__JSP_EXPR__`, `<c:url>`은 리터럴 경로, 제어 요소는 공백). | 사용자 요청 |
| F-011-R12 | 시스템은 이벤트 핸들러 속성(`on*`)과 `javascript:` URL의 코드를 같은 주인 모듈의 내장 소스(`inlineHandler=true`)로 등록하되, 한 페이지에서 값이 같은 것은 한 번만 등록해야 한다. | 사용자 요청 |
| F-011-R13 | 만약 파일 하나를 읽거나 분석하다 실패하면, 시스템은 `PARSE_FAILED` 진단을 남기고 나머지 파일을 계속 분석해야 한다. | AGENTS.md |
| F-011-R14 | 시스템은 내장 소스로 넘기는 스크립트에서 JSP 요소를 치환했으면, 치환한 구간과 그 자리의 원문을 담은 치환 목록을 `EmbeddedSource.attrs`의 `jspSubstitutions`로 함께 넘겨야 한다. 소비자(F-010-R14)가 식의 원문을 되살리는 데 쓴다. | 감사 U4, design 3.5 |
| F-011-R15 | 옵션 `web.skipVendor`가 `false`가 아닌 동안, 만약 JSP·HTML 파일이 벤더 폴더 안에 있으면(아래 "벤더 판정"의 경로 규칙), 시스템은 `JSP_PAGE` 노드(`skipped=vendor`)만 만들고 링크·요청·포함·내장 소스를 만들지 않으며 건너뛴 파일 수를 벤더 폴더마다 `FILE_SKIPPED`(INFO) 진단 1건으로 남겨야 한다. `web.vendorPatterns`(쉼표로 구분한 glob)에 맞는 파일도 건너뛴다. | 공개 프로젝트 검증 P2 (요청 3,061개 중 2,592개가 벤더 페이지) |
| F-011-R16 | 시스템은 페이지의 폼마다 이름·ID와 메서드를 모아 `JSP_PAGE` 노드의 `attrs.formMethods`(폼 표)로 싣고, `action`이 없어 현재 URL로 보내는 POST 폼의 수를 `attrs.selfPostForms`로 실어야 한다. 소비자(F-010-R24)가 스크립트의 폼 `action` 대입 지점에서 메서드를 정하는 데 쓴다. | 공개 프로젝트 검증 B15(e) |
| F-011-R17 | HTML 파일의 요소에 Thymeleaf 속성 `th:href`·`th:action`·`th:src`(또는 `data-th-*`)가 있으면, 시스템은 그 링크 식(`@{…}`)에서 URL을 읽어 요청(스크립트는 포함)으로 기록하고 같은 요소의 원형용 정적 `href`·`action`·`src`는 무시해야 한다. 변수 식과 경로 변수는 `{*}`, 링크 식의 매개변수는 쿼리로 본다. | 공개 프로젝트 검증 B15(f) |
| F-011-R18 | HTML 파일의 요소에 `th:replace`·`th:insert`·`th:include`(또는 `data-th-*`)가 있으면, 시스템은 그 조각 식의 템플릿 이름을 `templates/` 폴더 기준 `.html` 파일로 풀어 `INCLUDES`(JSP_PAGE→JSP_PAGE, `via=thymeleaf`)로 기록해야 한다. 찾지 못하거나 이름이 동적이면 분류 `jsp-include`의 미해결 엣지를 남긴다. | 공개 프로젝트 검증 B15(f) |
| F-011-R19 | 시스템은 JSP·HTML 페이지 하나를 읽기 시작할 때 그 상대 경로를 `AnalysisContext.progress`로 알려야 한다(메모리 부족 안내에 실린다). | F-001 (메모리 부족 안내) |
| F-011-R20 | 시스템은 CR 단독(뒤에 LF가 없는 `\r`)을 줄 끝으로 세어야 한다. 페이지 본문의 CR 단독은 읽을 때 LF로 맞추고(길이 보존) 위치의 줄·열, 스캐너의 줄 판정, 내장 소스 본문이 모두 그 기준을 따른다. | 재검증 N6 |
| F-011-R21 | 시스템은 SiteMesh가 붙이는 관계를 `INCLUDES`(내용 페이지 → 데코레이터 JSP, `INFERRED`, `via=sitemesh`)로 기록해야 한다: 페이지의 `<meta name="decorator" content="이름">`을 `decorators.xml`의 `<decorator name page>`와 맞추고, `meta`가 없는 페이지는 패턴으로 적용하는 데코레이터가 하나뿐이고 그 패턴이 모든 경로(`/*`)일 때만 그 데코레이터로 잇는다. SiteMesh 3(`sitemesh3.xml`의 `<mapping path decorator>`, `meta`의 값이 데코레이터 파일 경로)도 같다. 이름을 찾지 못하면 분류 `jsp-include`의 미해결 엣지를 남긴다. | 재검증 (레이아웃이 싣는 스크립트의 전역 호출 60여 건 누락) |
| F-011-R22 | 시스템은 Tiles 설정 파일의 정의를 읽어, 와일드카드가 없는 정의의 template JSP → 그 정의의 조각 JSP를 `INCLUDES`(`INFERRED`, `via=tiles`)로 기록하고, 설정 파일의 `FILE` 노드에 `tilesDefinitions`(정의 이름 → template 경로)와 `tilesAttributes`(정의 이름 → 조각 JSP 경로)를 실어야 한다. `extends`를 풀고(여러 설정 파일에 걸친 것 포함), 와일드카드 정의는 원문 그대로 싣되 `INCLUDES`는 만들지 않는다. | 재검증, F-012(뷰 이름 = 정의 이름), F-010-R33 |
| F-011-R23 | `<script src>`를 웹 루트 기준으로도, src 전체가 경로 끝과 같은 유일한 파일로도 찾지 못하면, 시스템은 파일 이름을 포함해 경로 끝에서부터 가장 길게(두 조각 이상) 일치하는 프로젝트 스크립트(벤더 폴더 제외)가 하나뿐일 때 그 파일로 `INFERRED` `INCLUDES`를 만들어야 한다. src가 압축본(`x.min.js`)인데 프로젝트에 없고 `x.js`를 같은 방법으로 찾으면 그 파일로 잇고 `minOf`에 src 경로를 남긴다. | 재검증 N13(a)(b) (zheng 61건) |
| F-011-R24 | 시스템은 접두사 있는 태그의 `url`·`href`·`action`·`src`·`dataUrl` 속성 값이 서버 경로(`/`로 시작하거나 컨텍스트 경로 식 + 경로)면 `HTTP_REQUEST`(`api=tag`, 메서드 `ANY`)와 `INFERRED` `REQUESTS`를 만들어야 한다. 다른 규칙이 다루는 태그(`<c:import>`, `<c:redirect>`, `<c:url>`, `<form:form>` 등)는 제외한다. | 재검증 N19 (jeesite JSP 22개 41곳) |
| F-011-R25 | 요청 URL 안의 EL이 `조건 ? '리터럴A' : '리터럴B'` 꼴이면, 시스템은 그 자리를 `{*}`로 두지 않고 A와 B를 넣은 후보 URL마다 요청을 만들고 `REQUESTS`를 `INFERRED`로 두어야 한다. | 재검증 N20 (`{*}` 세그먼트가 형제 엔드포인트 9개에 모호 매칭) |
| F-011-R26 | 시스템은 URL을 정규화할 때 가짜 경로를 만들지 않아야 한다: 컨텍스트 경로 식 뒤에 `/`가 겹치면 하나로 합치고(원문 자체가 `//`로 시작할 때만 외부 URL), `?` 없이 `&`로 이은 조각은 쿼리로 가른다(남은 경로에 정적 조각이 없으면 F-011-R9대로 미해결). | 재검증 N21 |
| F-011-R27 | 페이지의 SiteMesh 데코레이터 이름에 EL·식이 섞여 있고 정적인 부분이 맞는 데코레이터가 여럿이면, 시스템은 페이지 경로와 데코레이터 JSP 경로의 공통 디렉터리가 가장 긴 데코레이터만 이어야 한다. 가장 긴 것이 여럿이라 가리지 못하면 그것들 전부에 `attrs.ambiguous=true`를 붙여 잇는다. 동적 이름에서 온 엣지에는 고른 경우에도 `attrs.dynamicDecorator=true`를 붙인다. | 4차 재검증 M6 (jeesite 28건 중 14건이 다른 테마의 레이아웃) |
| F-011-R28 | `action`이 없는(또는 빈) `<form>`·`<form:form>`은 현재 URL로 제출된다. 시스템은 그런 폼마다 `HTTP_REQUEST`(`api=form`, 메서드는 폼의 것, `selfSubmit=true`, URL 자리는 `{self}`)와 `INFERRED` `REQUESTS`를 만들어야 한다. 다만 페이지의 스크립트가 폼의 `action`을 대입하거나, 폼을 제출하는 흔적(폼 안의 제출 버튼, `.submit()` 호출)이 없으면 만들지 않는다(5차: 흔적은 그 폼과 짝지어 본다, F-011-R30). HTML 파일의 `GET` 폼도 만들지 않는다. | 4차 재검증 M13(a) (POST 엔드포인트 3개의 화면 호출자가 0) |
| F-011-R29 | 시스템은 hidden·text `<input>`의 `value`가 서버 경로처럼 보이는 정적 값이면 그것을 `JSP_PAGE` 노드의 `attrs.formFields`(필드 표)로 실어야 한다. 소비자(F-010-R41)가 `frm.zip_url.value` 같은 식을 URL로 평가하는 데 쓴다. 필드 값 자체는 요청이 아니다. | 4차 재검증 M13(b) |
| F-011-R30 | 시스템은 `selfSubmit` 요청(F-011-R28)의 "제출하는 흔적"을 **그 폼과 짝지어** 판정해야 한다. 흔적은 (1) 그 폼 안의 제출 컨트롤(다만 `onclick`이 `return false`로 끝나는 컨트롤과, `onsubmit`이 `return false`로 끝나는 폼의 컨트롤은 제외)과 (2) 수신자가 그 폼을 가리킬 수 있는 `.submit()` 호출이다. 다른 이름·ID를 명시한 `.submit()`과 다른 창의 폼(`opener.…`)은 흔적이 아니고, 수신자를 알 수 없는 `.submit()`은 페이지에 `action` 없는 폼이 하나뿐일 때만 그 폼의 흔적이다. | 5차 재검증 (jeesite `testAuditView.jsp`: 제출되지 않는 폼의 `POST {self}`가 엔드포인트 3개에 `ambiguous`로 이어짐) |
| F-011-R31 | 요청 URL의 쿼리 문자열 안에 있는 EL 삼항은 후보로 펼치지 않아야 한다(F-011-R25의 예외). 경로가 하나로 정해지므로 요청은 하나이고, 그 자리는 쿼리의 `{*}`이며, 그것만으로 `REQUESTS`를 `INFERRED`로 낮추지 않는다. | 5차 재검증 (쿼리 값 하나만 남고 추론이 됨) |

### 세부 규칙

**웹 루트** — 파일 경로의 조상 중 `WEB-INF`의 부모, 없으면 이름이 `webapp`·`WebContent`·`WebRoot`·`web`인 가장 가까운 조상, 없으면 `src/main/resources/` 아래의 `static`·`public`·`META-INF/resources`, 그것도 없으면 프로젝트 루트.

**컨텍스트 경로 표현** — `${pageContext.request.contextPath}`처럼 `contextPath`가 들어간 EL, `<%= request.getContextPath() %>`, `<c:url value='...'/>`·`<spring:url value='...'/>`(값의 경로가 그대로 남는다), 그리고 URL 맨 앞에 있고 바로 뒤가 `/`인 단일 변수 EL·식(`${ctx}/x`, `<%=ctx%>/x`). 속성 접근이 있는 EL(`${o.url}/x`)은 컨텍스트 경로로 보지 않고 `{*}`가 된다.

**HTTP_REQUEST** — ID는 `Ids.httpRequest(METHOD, 정규화한 URL, 파일, 줄)`. 정규화한 URL은 `attrs.path`에도 넣는다(링커가 ID를 쪼개지 않게). `attrs.url`은 속성 값 원문, `attrs.dynamic`은 경로에 `{*}`가 있을 때 true(쿼리만 동적이면 false). 메서드는 `<form>` 기본 `GET`, `<form:form>` 기본 `POST`, 값에 EL이 있으면 `ANY`, 나머지 요소는 `GET`. `api`는 `form`, `link`, `frame`, `redirect`, `refresh`, `include`, `forward`.

**내장 소스 치환 규칙(F-011-R11)** — web(F-010)과의 약속이다.

| 스크립트 안의 요소 | 치환 |
|---|---|
| EL `${...}` `#{...}`, 식 `<%= ... %>` | `__JSP_EXPR__` |
| 같은 페이지에서 `<c:url var="u" value="/x"/>`로 만든 변수의 `${u}` | `<c:url value="/x"/>`와 같게(문자열 안이면 `/x`) |
| `<c:out .../>`, `<spring:message .../>`, `<fmt:message .../>` 등 값을 내는 self-closing 커스텀 태그 | `__JSP_EXPR__` |
| `<c:url value="/x"/>`, `<spring:url value="/x"/>` (`var` 없음) | JS 문자열 안이면 `/x`, 밖이면 `'/x'`. 값 안의 EL은 `__JSP_EXPR__` |
| 스크립틀릿 `<% %>`, 선언 `<%! %>`, 지시자, JSP 주석 | 공백 |
| 여는·닫는 커스텀 태그(`<c:if>`, `<c:forEach>`, `<c:choose>` 등), 값을 내지 않는 태그(`<c:set>`, `<c:param>`, `var`가 있는 태그, `<jsp:include>`) | 공백 (태그만 지우고 사이의 본문은 남긴다) |

- 치환 결과는 원래 요소와 **줄 수가 같다**. 요소 안의 줄바꿈은 그대로 남긴다.
- 치환 토큰이 원래 요소보다 짧으면 모자란 만큼 공백을 채워 **줄의 길이도 같게** 한다.
  - JS 문자열 밖에서 치환했으면 공백을 토큰 바로 뒤에 둔다. 열이 전혀 어긋나지 않는다.
  - JS 문자열 안에서 치환했으면 공백을 **닫는 따옴표 바로 뒤**(그 줄에서 닫히지 않으면 줄 끝)에 둔다. 문자열 값에 공백이 끼면 URL이 망가지기 때문이다. 예: `'${pageContext.request.contextPath}/order/list.do'` → `'__JSP_EXPR__/order/list.do'` + 공백 22칸. 이때 그 문자열 안의 열만 실제보다 앞당겨지고 닫는 따옴표 뒤부터는 다시 맞는다.
- 치환 토큰이 원래 요소보다 길면(예: `${x}` → `__JSP_EXPR__`) 길이를 맞출 수 없으므로 **줄 수만 보존**한다. 그 줄에서 치환 지점 뒤의 열은 실제보다 뒤로 밀린다. 다른 줄은 영향이 없다.
- 내장 소스는 항상 `exactPositions=true`로 넘긴다(HTML 엔티티를 풀어야 했던 이벤트 핸들러만 `false`).
- URL 맨 앞의 `__JSP_EXPR__`는 컨텍스트 경로, 그 밖의 자리의 `__JSP_EXPR__`는 동적 조각으로 읽는다.
- 순수 HTML 파일은 JSP 요소를 인식하지 않으므로 본문을 그대로 넘긴다(`${x}`는 JS 템플릿 리터럴이다).

**치환 목록(F-011-R14)** — web(F-010)과의 약속이다. 키는 `jspSubstitutions`, 값은 치환 건마다 아래 기록을 구분자 없이 이어 붙인 문자열이다.

```
<offset>,<length>,<원문 길이>:<원문>
```

- `offset`, `length`: 넘긴 본문(`EmbeddedSource.text`)에서 치환 결과가 차지하는 구간의 시작 문자 오프셋(0부터)과 길이. 치환 토큰(또는 리터럴 경로)과 바로 뒤에 붙인 채움 공백·줄바꿈을 포함한다.
- `원문`: 그 자리에 원래 있던 JSP 요소의 원문. `원문 길이`만큼 그대로 읽는다(이스케이프 없음. 원문에 `:`·`,`·줄바꿈이 있어도 된다).
- 문자열 안에서 치환해 닫는 따옴표 뒤로 미룬 채움 공백은 원문이 빈 기록으로 따로 싣는다(`<offset>,<공백 수>,0:`). 문자열 안에서 흔적 없이 지운 요소(제어 태그)는 `length`가 0인 기록이다.
- 기록은 `offset` 오름차순이고 서로 겹치지 않는다. **모든 기록의 구간을 원문으로 바꾸면 원래 본문과 글자 하나까지 같다.**
- 예: `$.getJSON('${ctx}/order/history.do', cb);` → 본문 `$.getJSON('__JSP_EXPR__/order/history.do', cb);`, 치환 목록 `11,12,6:${ctx}`. `$.post('<c:url value="/order/close.do"/>', {});` → 본문 `$.post('/order/close.do'` + 공백 17칸 + `, {});`, 치환 목록 `8,15,32:<c:url value="/order/close.do"/>24,17,0:`.
- 치환한 것이 없으면(순수 HTML 포함) 키를 넣지 않는다.
- 엔티티를 푼 이벤트 핸들러(`exactPositions=false`)는 오프셋을 엔티티를 푼 뒤의 본문 기준으로 옮겨 싣는다. 엔티티가 치환 구간의 경계에 걸쳐 옮길 수 없으면 싣지 않는다.

**벤더 판정(F-011-R15)** — web(F-010-R17)과 같은 규칙이다. 두 모듈이 의존을 공유하지 않아 각자 구현한다(`VendorPaths`, web의 `VendorRules`). 한쪽을 고치면 다른 쪽 문서와 구현도 고친다. 페이지에는 아래 1과 2만 쓴다(파일 이름 규칙과 머리 주석 규칙은 스크립트용이다).

1. **경로 규칙**(파일을 읽지 않는다). 디렉터리 세그먼트를 앞에서부터 대소문자 구분 없이 보아, 아래에 처음 맞는 세그먼트까지가 "벤더 폴더"다.
   - `node_modules`, `bower_components`, `webjars`
   - `vendor`, `vendors`: 경로의 첫 세그먼트이거나, `src` 바로 아래이거나, 정적 자원 폴더 아래일 때. 업무 도메인 이름(거래처)으로 쓴 폴더(`src/pages/vendor`, `WEB-INF/views/vendor`)와 구분하기 위해서다
   - 정적 자원 폴더 아래의 `lib`, `libs`, `plugins`, `plugin`, `bootstrap`, `layer`, `angular`
   - `plugins/<이름>/…`: `plugins` 아래에 폴더가 하나 더 있는 배치(플러그인마다 폴더). 정적 자원 폴더 밖(모듈의 `src/plugins/<이름>/demo/index.html`)이어도 벤더다. 화면 파일 영역(`WEB-INF`, `templates`) 안쪽의 `plugins/`와 `plugins` 바로 아래의 파일은 아니다(3차, F-010-R36과 같은 문구)
   - "정적 자원 폴더 아래"는 조상에 `webapp`, `WebContent`, `WebRoot`, `static`, `public`, `assets`, `js`, `javascript`, `scripts`, `script` 가운데 하나가 있고 그 뒤에 `WEB-INF`가 끼어 있지 않다는 뜻이다(`WEB-INF` 안쪽은 화면 파일이다. `src/main/resources/templates`도 정적 자원 폴더가 아니다)
   - 잘 알려진 라이브러리 이름과 같거나 그 뒤에 버전이 붙은 이름(`zTree_v3`, `webuploader-0.1.5`): `ckeditor`, `ckfinder`, `tinymce`, `easyui`, `ztree`, `echarts`, `swfobject`, `webuploader`, `codemirror`, `ueditor`, `umeditor`, `kindeditor`, `xheditor`, `summernote`, `layui`, `laydate`, `My97DatePicker`, `select2`, `jqGrid`, `datatables`, `fullcalendar`, `pdfjs`, `angularjs`, `scriptaculous`, `mootools`, `extjs`, `highcharts`, `jqplot`, `jstree`, `treeTable`, `SuperSlide`, `supersized`, `mCustomScrollbar`, `malihu-custom-scrollbar-plugin`, `jerichotab`, `jbox`, `artdialog`, `lhgdialog`, `fancybox`, `uploadify`, `plupload`, `swfupload`, `zeroclipboard`, `syntaxhighlighter`, `es5-shim`, `es5-sham`, `requirejs`, `seajs`, `fullPage`, `sweetalert`, `videojs`, `video-js`, `mathjax`, `font-awesome`, `fontawesome`
   - `jquery`, 그리고 `jquery`·`bootstrap` 뒤에 `-`·`.`·`_`·숫자가 이어지는 이름(`jquery-validation`, `jquery.form`, `bootstrap-table-1.11.0`), `jqueryui`, `bootstrapvalidator`
   - 이름 뒤에 구분자와 점 있는 버전이 붙은 이름(`font-awesome-4.7.0`, `es5-shim-15.3.4.5`)
   - 정적 자원 폴더 아래의, 버전만으로 된 이름(`2.3.1`). 이때 벤더 폴더는 그 부모다(`static/foo/2.3.1` → `static/foo`)
2. **옵션 규칙**. `web.vendorPatterns`의 glob에 맞는 경로. `**`는 `/`를 포함한 임의의 글자, `*`는 `/`를 뺀 임의의 글자, `?`는 한 글자다. `/`가 없는 glob은 경로의 어느 세그먼트에든 맞으면 된다(`legacy-*.js`, `thirdparty`). `!`로 시작하는 glob(`!**/js/lib/**`)은 반대로 "벤더가 아니다"라고 못 박는 것이고 다른 모든 규칙보다 먼저다(잘못 벤더로 판정된 프로젝트 폴더를 되살리는 데 쓴다).

- 진단 `FILE_SKIPPED`(INFO)는 벤더 폴더마다 1건이다: `벤더 페이지 n개 건너뜀(<근거>): <벤더 폴더>`. 2로 판정한 파일은 그 파일이 든 폴더로 묶는다. 위치는 그 폴더 경로다. `<근거>`는 `경로`(1) 또는 `옵션`(2)이고 한 폴더에 섞이면 근거마다 건수를 적는다(`경로 3, 옵션 1`). web의 벤더 스크립트 진단과 같은 꼴이다(4차, F-010-R40).
- 건너뛴 페이지의 노드에는 `pageType`과 `skipped=vendor`만 있다(`taglibs` 등은 없다). `FILE -CONTAINS→ JSP_PAGE`는 만든다. 다른 페이지가 그 페이지를 include하면 `INCLUDES`는 종전대로 이어진다.
- 옵션 키는 web과 같다(`web.skipVendor`, `web.vendorPatterns`). 스크립트와 페이지를 따로 켜고 끄지 않는다.

**폼 표(F-011-R16)** — web(F-010)과의 약속이다. 키는 `formMethods`, 값은 `"<키>=<METHOD>"` 문자열의 목록(문서 순서)이다.

- `<키>`: 폼의 `name` 속성 값, 또는 `#` + `id` 속성 값. 한 폼에 둘 다 있으면 두 항목을 싣는다. Spring `<form:form>`은 `id`가 없으면 `modelAttribute`(`commandName`)가 id가 된다(`#sampleVO`). 이름·ID에 EL·식이 섞였으면 그 키는 싣지 않는다.
- `<METHOD>`: 대문자 메서드. `method`가 없으면 `<form>`은 `GET`, `<form:form>`은 `POST`. `method`에 EL·식이 섞였으면 `ANY`. 한 페이지에서 같은 키의 폼들이 메서드가 다르면 `ANY` 하나로 싣는다.
- 예: `<form:form modelAttribute="sampleVO" id="listForm" name="listForm" method="post">` → `listForm=POST`, `#listForm=POST`.
- 폼이 없으면 키를 넣지 않는다. 다른 파일을 include한 페이지의 표에는 그 파일의 폼이 들어 있지 않다(소비자가 `INCLUDES`를 따라가 합친다).
- `selfPostForms`: `action` 속성이 없거나 비어 있고 메서드가 `POST`인 폼의 수(0이면 키를 넣지 않는다). 이런 폼은 현재 URL로 보낸다. 4차부터 조건이 맞으면 `selfSubmit` 요청을 만든다(아래 "현재 URL로 제출하는 폼"). 건수는 요청을 만들었는지와 상관없이 종전대로 센다.

**현재 URL로 제출하는 폼(F-011-R28)** — 링커(F-012)와의 약속이다. `action` 속성이 없거나 비어 있는 `<form>`·Spring `<form:form>`(HTML 파일은 `th:action`도 없을 때)이 대상이다.

- 요청 노드: ID는 `Ids.httpRequest(METHOD, "{self}", 파일, 줄)`(예: `http:request:POST {self}@…/EgovCcmCmmnCodeRegist.jsp:94`), `httpMethod`는 폼의 메서드(`<form>` 기본 `GET`, `<form:form>` 기본 `POST`, EL이 섞이면 `ANY`), `path`는 `{self}`, `url`은 빈 문자열(적힌 URL이 없다), `dynamic=false`, `api=form`, `selfSubmit=true`, `<form:form>`이면 `modelAttribute`. 위치는 폼의 여는 태그다.
- `{self}`는 "이 페이지를 그리는 엔드포인트의 경로"라는 뜻이다. jsp는 그 경로를 모른다. 링커가 이 페이지를 `RENDERS`하는 핸들러의 엔드포인트와 같은 경로·이 메서드의 엔드포인트로 잇는다. `path`를 URL 패턴으로 맞추지 않는다.
- `REQUESTS`는 항상 `INFERRED`다: 폼이 정말 그대로 제출되는지(스크립트가 다른 파일에서 `action`을 바꾸지 않는지)는 모른다.
- 만들지 않는 경우: (1) 페이지 본문에 `action` 대입이 있다(`….action = …`, `attr('action', …)`·`prop('action', …)`·`setAttribute('action', …)`. 어느 폼의 것인지는 가리지 않는다). 그 지점에서 web이 요청을 만든다(F-010-R24). (2) 그 폼을 제출하는 흔적이 없다(아래 "제출 흔적과 폼의 짝"). 값을 모아 AJAX로 보내는 폼(`$.ajax({ data: $('#f').serialize() })`)이 흔하다. (3) HTML 파일의 `GET` 폼: 정적 페이지의 현재 URL은 그 파일이지 엔드포인트가 아니다.

**제출 흔적과 폼의 짝(F-011-R30)** — 폼마다 따로 판정한다. JavaScript를 파싱하지 않고 페이지 본문의 글자만 본다(`SubmitTraces`). HTML·JSP 주석과 스크립트 안의 JS 주석(`//`, `/* */`)은 읽지 않는다(스크립트 전체를 감싼 `<!--`·`-->`는 주석이 아니다).

- **제출 컨트롤**: 그 폼의 여는 태그와 닫는 태그 사이의 `<input type="submit|image">`, `type`이 `button`·`reset`이 아닌 `<button>`, `<form:button>`. 다만 컨트롤의 `onclick`이 `return false`로 끝나면(`onclick="fnList(); return false;"`) 그 버튼은 폼을 제출하지 않으므로 흔적이 아니다. 폼의 `onsubmit`이 `return false`로 끝나면 그 폼의 컨트롤은 모두 흔적이 아니다.
- **`.submit()` 호출**: 수신자 식을 읽어 폼과 맞춘다.

| 수신자 | 가리키는 폼 |
|---|---|
| `document.frm`, `document.forms.frm`, `document.forms['frm']`, `document.all.frm`, `document['frm']` | `name` 또는 `id`가 `frm`인 폼 |
| `document.forms[0]` | 페이지의 0번째 폼(문서 순서. `action`이 있는 폼도 센다). 페이지에 폼이 하나뿐이면 번호와 상관없이 그 폼 |
| `$("#f")`, `jQuery('form#f')`, `document.getElementById("f")`, `document.querySelector("#f")`, `$("[id=f]")` | `id`가 `f`인 폼(Spring `<form:form>`은 `id`가 없으면 `modelAttribute`) |
| `$("form[name=f]")`, `document.getElementsByName("f")[0]` | `name`이 `f`인 폼 |
| `$("#f").attr("target", "_self")` 처럼 요소를 옮기지 않는 메서드가 이어진 것, `$(document.frm)` | 처음 고른 폼 |
| 이벤트 처리기 속성(`on*`, `javascript:` URL) 안의 `this`, `this.form`, `form`, `$(this).parents("form")`·`closest("form")` | 그 속성이 적힌 요소를 품은 폼(폼의 여는 태그 자신 포함) |
| 함수의 매개변수(`function fn(form){ form.submit(); }`) | 그 함수를 이름으로 부르는 자리의 인자(`fn(document.frm)`, `fn(document.forms[0])`, 처리기 안의 `fn(this.form)`)를 위 규칙으로 읽는다. `el.form`(`fn(this)`로 받은 요소의 폼)도 같다. 인자를 또 다른 매개변수로 받은 경우는 몇 단계까지만 따라간다 |
| 검증 플러그인의 `submitHandler: function(form){ form.submit(); }` | `$("#f").validate({…})`의 `$("#f")` |
| 변수(`var f = document.getElementById("vf"); f.submit();`) | 스크립트에서 그 이름에 대입한 값들을 위 규칙으로 읽는다(함수 범위는 가리지 않는다) |
| 매개변수도 대입한 변수도 아닌 이름(`legacy.submit()`) | 페이지에 그 `name`·`id`의 폼이 있으면 그 폼(전역 이름으로 폼에 닿는 구형 코드) |
| `opener.document.frm`, `parent.…`, `top.…`, `window.opener.…`, `frames[…]` | 다른 창·프레임의 폼. 이 페이지의 어느 폼도 아니다 |
| 그 밖(`$("form")`, `$(".cls")`, `$(this).parents("form")`(스크립트 안), 스크립트 안의 `this`, 호출 결과, 호출하는 자리가 없는 매개변수) | 모른다 |

- 이름·ID가 맞는 호출이 하나라도 있으면 그 폼의 흔적이다. "모른다"인 호출은 페이지에 `action` 없는 폼이 **하나뿐일 때만** 그 폼의 흔적으로 본다(여럿이면 어느 폼의 흔적도 아니다). 다른 이름·ID를 명시한 호출은 이 폼의 흔적이 아니다(jeesite `testAuditView.jsp`의 `$("#inputForm").validate({ submitHandler … form.submit() })`: 그 페이지에 `inputForm`이 없다).
- 폼의 `name`·`id`에 EL·식이 섞였으면(`id="${prefix}Form"`) 값을 모르므로 이름·ID를 명시한 어느 호출과도 맞는 것으로 본다.
- 예: `<form name="Form" action="" method="post">` 안의 유일한 제출 버튼이 `<input type="submit" onclick="fnList(); return false;"/>`이고 페이지에 `.submit()`이 없다 → 요청 없음. `<form:form modelAttribute="knoAppraisal" name="knoAppraisal" method="post">` 안의 `<input type="submit" onclick="fn_modify(document.forms[0]); return false;"/>`와 `function fn_modify(form){ … form.submit(); }` → `POST {self}`(버튼은 막지만 인자로 받은 그 폼을 함수가 제출한다).

**필드 표(F-011-R29)** — web(F-010)과의 약속이다. 키는 `formFields`, 값은 아래 문자열의 목록(문서 순서, 중복 없음)이다. 항목이 없으면 키를 넣지 않는다.

```
<폼 키>.<필드 name>=<URL>      폼 안의 input. 폼 키는 폼 표와 같다(name, #id). 폼에 키가 둘이면 두 줄
.<필드 name>=<URL>             폼 밖의 input(또는 이름·ID가 없는 폼 안의 input)
#<input id>=<URL>              id가 있는 input
```

- 대상: HTML `<input>`의 `type`이 `hidden`·`text`이거나 없는 것. `name`·`id`에 EL이 섞였으면 그 키는 싣지 않는다.
- 값의 조건: 요청 URL로 정규화했을 때(F-011-R8) 정적인(동적 조각 `{*}`가 없는) 내부 경로이고, 원문이 컨텍스트 경로 식으로 시작하거나(`<c:url value='/x.do'/>`, `${pageContext.request.contextPath}/x.do`, `${ctx}/x`) 원문이 `/`로 시작하면서 `.do`로 끝나거나 확장자 없이 두 조각 이상(`/sym/cmm/list`)이다. `/list`(한 조각), `/x/view.jsp`(다른 확장자), `2020/01/01`, 외부 URL, `${user.id}`는 싣지 않는다.
- `<URL>`은 정규화한 경로이고 쿼리가 있으면 `?쿼리`를 붙인다.
- 예: `<form:form modelAttribute="userManageVO" name="userManageVO">` 안의 `<input type="hidden" name="zip_url" value="<c:url value='/sym/cmm/EgovCcmZipSearchPopup.do'/>"/>` → `userManageVO.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do`, `#userManageVO.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do`.
- 다른 파일을 include한 페이지의 표에는 그 파일의 필드가 들어 있지 않다(소비자가 `INCLUDES`를 따라가 합친다). 폼이 다른 파일에서 열렸으면 그 조각의 필드는 `.<필드>`로 실린다.

**Thymeleaf(F-011-R17, R18)** — HTML 파일에만 적용한다. 식을 평가하지 않고 정적인 조각만 읽는다.

| 값 | 읽는 방법 |
|---|---|
| `@{/order/list}` | `/order/list` |
| `@{/order/{id}/view(id=${o.id},tab='c')}` | `/order/{*}/view`, 쿼리 `tab=c`. 경로 변수에 쓰인 매개변수는 쿼리가 아니다 |
| `@{/order/list(page=${p},size=10)}` | `/order/list`, 쿼리 `page={*}&size=10` |
| `@{'/news/article/' + ${a.id}}`, `@{\|/news/tag/${t.alias}\|}` | `/news/article/{*}`, `/news/tag/{*}` |
| `@{${menu.url}}`, `${policy.action}` | 정적 조각이 없다 → 미해결(`http-url`). 원문은 속성 값 그대로 |
| `@{~/x}`, `@{http://host/x}`, `@{//host/x}` | `/x`, 외부 URL |
| `@{${base} + '/js/main.js'}`(접두가 동적) | `{*}/js/main.js` |

- `th:href`는 `<a>`·`<area>`, `th:action`은 `<form>`, `th:src`는 `<iframe>`·`<frame>`·`<script>`에서 읽는다(`<link>`·`<img>`는 종전대로 요청이 아니다). `HTTP_REQUEST`의 `url`은 `th:` 속성 값 원문이다.
- `@{…}`는 컨텍스트 경로를 스스로 붙이므로 맨 앞의 변수 식을 컨텍스트 경로로 떼지 않는다(`{*}/…`로 남는다).
- `<script th:src>`: 링크 식이 정적이면 그 경로로 스크립트 파일을 찾는다(F-011-R6과 같은 방법). 접두가 동적이면 원형용 정적 `src`가 가리키는 파일의 경로가 같은 꼬리(`/js/main.js`)로 끝날 때만 그 파일로 `INCLUDES`(`INFERRED`)를 만들고, 아니면 `externalScripts`에 `th:src` 원문을 남긴다.
- 조각 식: `fragments/head :: common('제목')`, `~{fragments/nav :: menu}`, `fragments/footer`(선택자 없음), `~{/layout/side.html :: side}`. 템플릿 이름은 이 파일의 가장 가까운 조상 `templates` 폴더 기준이고 확장자가 없으면 `.html`을 붙인다. `templates` 폴더 밖의 파일이면 현재 폴더 기준으로 찾고, 없으면 경로 끝이 일치하는 HTML 파일이 하나뿐일 때 `INFERRED`로 잇는다. `:: name`·`this :: name`(같은 파일의 조각)은 포함이 아니다. 한 페이지에서 같은 대상은 첫 사용만 기록한다. `INCLUDES`의 `attrs.via`는 `thymeleaf`, `path`는 속성 값 원문이다.

**줄 끝(F-011-R20)** — 줄 끝은 LF, CRLF, CR 단독이다. CRLF의 CR은 그 줄에 속한다(줄을 하나 더 세지 않는다). 페이지 본문을 읽은 직후 CR 단독을 LF로 바꾸므로(길이 그대로) 오프셋·열은 원래 파일과 같고, 내장 소스로 넘기는 본문의 줄 끝도 LF다(web이 같은 방식으로 센다, F-010-R29).

**SiteMesh(F-011-R21)** — 설정 파일은 `FileType.XML` 가운데 루트 요소로 가린다(파일 이름은 보지 않는다). 설정은 그 파일의 웹 루트(같은 규칙: `WEB-INF`의 부모 등)에 속한 페이지에 적용하고, 페이지의 웹 루트에 설정이 없는데 프로젝트에 설정이 하나뿐이면 그것을 쓴다.

| 설정 | 읽는 방법 |
|---|---|
| `<decorators defaultdir="/WEB-INF/views">` `<decorator name="default" page="layouts/default.jsp"/>` (루트 `decorators`, SiteMesh 2) | 이름 `default` → `<웹 루트>/WEB-INF/views/layouts/default.jsp`. `page`가 `/`로 시작하면 웹 루트 기준이고 아니면 `defaultdir` 아래다(그렇게 찾지 못하면 반대로도 찾는다) |
| `<decorator name="main" page="main.jsp"><pattern>/*</pattern></decorator>` | 그 데코레이터의 URL 패턴. `<excludes>` 아래의 `<pattern>`은 데코레이터의 것이 아니다 |
| `<sitemesh><mapping path="/*" decorator="/WEB-INF/decorators/main.jsp"/>` (루트 `sitemesh`, SiteMesh 3) | 패턴 `/*`로 적용하는 이름 없는 데코레이터. `path`가 없으면 모든 경로다. `decorator`가 없는 `mapping`(제외)은 읽지 않는다 |

| 페이지 | 기록 |
|---|---|
| `<meta name="decorator" content="default"/>`(대소문자 무시) | 그 이름의 데코레이터 JSP로 `INCLUDES`(`INFERRED`, `via=sitemesh`, `path`=`content` 값, 위치=`meta` 태그) |
| `content="cms_default_${site.theme}"` (EL이 섞임) | 정적인 부분이 맞는 이름의 데코레이터가 후보다(8개를 넘으면 잇지 않는다). 후보가 하나면 그것, 여럿이면 페이지와 공통 디렉터리가 가장 긴 것(F-011-R27, 아래). 엣지에 `dynamicDecorator=true` |
| `content="/WEB-INF/decorators/pop.jsp"` (경로) | 그 파일(SiteMesh 3) |
| 이름을 찾지 못함 | 미해결 `INCLUDES`(`jsp-include`, 원문 `decorator:<content 값>`) |
| `meta`가 없음 | 패턴이 있는 데코레이터가 그 웹 루트에 하나뿐이고 그 패턴이 하나이며 `/*`·`*`·`/**`·`/` 가운데 하나면 그 데코레이터로(`path`=패턴, 위치=설정 파일의 그 데코레이터). 그 밖에는 잇지 않는다(URL 패턴과 JSP 파일의 대응을 모른다) |

- 패턴으로 잇는 것은 내용을 분석한 `.jsp`·HTML 페이지만이고, 데코레이터 자신(`meta`로 지목된 것 포함), 다른 페이지가 포함하는 조각, `.jspf`·`.tag`는 뺀다.
- **동적 이름의 후보 고르기(F-011-R27).** 페이지 파일의 디렉터리와 후보 데코레이터 JSP의 디렉터리를 앞에서부터 견주어 같은 조각의 수가 가장 많은 후보만 남긴다. 테마마다 폴더를 나눈 배치에서 `…/themes/basic/frontList.jsp`는 `…/themes/basic/layouts/default.jsp`로만 이어지고 `…/themes/weixin/layouts/default.jsp`로는 이어지지 않는다. 가장 긴 것이 둘 이상이면(두 테마 폴더 밖의 `…/front/index.jsp`) 가리지 못한 것이므로 남은 후보 전부에 `ambiguous=true`를 붙인다. 고른 엣지와 후보가 하나뿐이던 엣지에는 `ambiguous`가 없다. 동적 이름에서 온 엣지는 모두 `dynamicDecorator=true`다(정적 이름의 엣지에는 두 키가 없다).
- 방향은 "내용 페이지 → 데코레이터"다. 실행 때 한 화면을 이루는 것은 (그 내용 페이지, 데코레이터)이고 데코레이터 하나를 여러 내용 페이지가 쓰기 때문이다. 반대 방향이면 데코레이터를 거쳐 서로 다른 내용 페이지끼리 한 화면인 것처럼 보인다.

**Tiles(F-011-R22)** — 루트가 `tiles-definitions`인 XML. Tiles 2·3의 `<definition name template extends>`·`<put-attribute name value>`와 Tiles 1(Struts)의 `<definition name path|page extends>`·`<put name value>`를 읽는다. `FILE` 노드 attrs는 링커(F-012)·web(F-010-R33)과의 약속이다.

- `tilesDefinitions`: `"<정의 이름>=<template 경로>"` 목록(그 파일의 정의, 문서 순서). template은 `extends`를 따라가 찾은 최종 값이다(다른 설정 파일의 정의를 extends한 것 포함. 같은 이름이 여럿이면 같은 파일 → 같은 웹 루트 → 첫 번째 순). 끝내 없거나 extends가 돌면 값이 빈 문자열(`orphan=`)이다. 와일드카드 정의(`*/*`, `admin/**`, `REGEXP:…`)는 이름과 값을 원문 그대로 싣는다(`{1}` 유지).
- `tilesAttributes`: `"<정의 이름>=<조각 JSP 경로>"` 목록. 정의 하나에 여러 줄이다. 물려받은 attribute도 넣고(상위 정의의 순서를 따른다) 하위 정의가 같은 이름을 덮으면 덮은 값만 넣는다. 값이 `.jsp`·`.jspx`·`.html`로 끝나는 것만 넣는다. 값이 다른 정의의 이름이면 그 정의의 template으로 바꿔 넣고, 그것도 아니면(제목 같은 문자열, 빈 값) 뺀다.
- 경로는 설정 파일에 적힌 그대로다(웹 루트 기준 `/WEB-INF/…`). 읽는 쪽은 설정 파일이 `WEB-INF` 아래면 그 부모를 웹 루트로 풀고, 아니면 경로 끝이 일치하는 JSP가 하나뿐일 때 그것으로 본다(jsp도 같은 방법으로 푼다).
- 예: `<definition name="base" template="/WEB-INF/layout/main.jsp"><put-attribute name="header" value="/WEB-INF/layout/header.jsp"/><put-attribute name="body" value=""/></definition>`, `<definition name="order.list" extends="base"><put-attribute name="body" value="/WEB-INF/views/order/list.jsp"/></definition>`, `<definition name="*/*" extends="base"><put-attribute name="body" value="/WEB-INF/views/{1}/{2}.jsp"/></definition>` → `tilesDefinitions` = `[base=/WEB-INF/layout/main.jsp, order.list=/WEB-INF/layout/main.jsp, */*=/WEB-INF/layout/main.jsp]`, `tilesAttributes` = `[base=/WEB-INF/layout/header.jsp, order.list=/WEB-INF/layout/header.jsp, order.list=/WEB-INF/views/order/list.jsp, */*=/WEB-INF/layout/header.jsp, */*=/WEB-INF/views/{1}/{2}.jsp]`.
- `INCLUDES`: 와일드카드가 없는 정의마다 template JSP → 조각 JSP(`INFERRED`, `via=tiles`, `path`=attribute 값 원문, 위치=설정 파일의 그 `<put-attribute>`. 물려받은 것은 상위 정의의 것). 같은 (template, 조각)은 한 번만 만든다. 조각 JSP가 프로젝트에 없으면 미해결(`jsp-include`, 원문은 프로젝트 기준 경로). template을 찾지 못한 정의, 와일드카드 정의, `{n}`이 든 경로로는 만들지 않는다(와일드카드 정의의 본문은 뷰 이름을 알아야 정해진다. 링커의 몫).

**스크립트 찾기(F-011-R6, F-011-R23)** — 순서대로: (1) 웹 루트 기준 정확한 파일(`RESOLVED`. JSP의 상대 src는 건너뛴다) → (2) src 전체가 경로 끝과 같은 파일이 하나뿐(`INFERRED`) → (3) 그런 파일이 없으면, 파일 이름이 같은 스크립트 가운데 끝에서부터 경로 조각이 가장 길게 일치하는 것이 하나뿐이고 두 조각 이상 일치(`INFERRED`. `/resources/ui-admin/js/common.js` ↔ `ui-admin/src/js/common.js`. 벤더 폴더의 파일은 후보가 아니다) → (4) src가 `x.min.js`·`x-min.js`면 `x.js`를 (1)~(3)으로 찾아 `INFERRED` + `minOf=<정규화한 src>` → (5) `externalScripts`. (2)의 후보가 둘 이상이면 (3)으로 가지 않는다.

**커스텀 태그의 URL 속성(F-011-R24)** — 접두사 있는 태그(`jsp:` 제외)의 속성 이름이 `url`·`href`·`action`·`src`·`dataUrl`(대소문자 무시)일 때 본다. 값을 요청 URL로 정규화해 경로가 `/`로 시작하고 외부 URL이 아니며 `.js`로 끝나지 않으면 `HTTP_REQUEST`(`api=tag`, `httpMethod=ANY`, `url`=속성 값 원문)를 만든다. 그 태그가 정말 요청을 보내는지, 어떤 메서드인지 모르므로 `REQUESTS`는 `INFERRED`다. 상대 경로, 값을 모르는 식(`${dynamicUrl}`), 정적 자원은 요청도 미해결도 만들지 않는다. 제외하는 태그: `import`, `redirect`, `url`, `param`, `set`, `out`, `if`, `when`, `forEach`, `message`, `eval`, `include`, `forward`, Spring `<form:form>`(종전 규칙이 다룬다).

**EL 삼항(F-011-R25)** — 요청 URL(링크, 폼, 프레임, 커스텀 태그 속성)에 적용한다. EL 본문이 `…? '<A>' : '<B>'`(따옴표는 홑·겹, 조건에 `?`가 없다)로 끝나면 그 EL을 A와 B로 바꾼 후보를 만든다(한 URL에서 2개까지, 후보 4개). 후보 가운데 요청으로 정규화되는 것마다 `HTTP_REQUEST`를 만들고(`url`은 속성 값 원문 그대로) `REQUESTS`는 `INFERRED`다. 갈래가 리터럴이 아닌 삼항(`${a ? b : 'x'}`)은 종전대로 `{*}`다. 쿼리 문자열 안의 삼항(EL 밖에 적힌 첫 `?`, 또는 `?` 없이 이은 `&` 뒤의 것. `<c:url value='/x?a=1'/>` 뒤도 같다)은 펼치지 않는다(F-011-R31): `${ctx}/cms/file/list?type=${type eq 'thumb'?'1':'0'}` → 요청 `GET /cms/file/list` 하나, 쿼리 `type={*}`, `REQUESTS`는 `RESOLVED`. 경로와 쿼리에 모두 있으면 경로의 것만 펼친다.

**URL 정규화 보정(F-011-R26)** — `<c:url value='/'/>/twitter/login.do`, `${ctx}//x.do` → `/twitter/login.do`, `/x.do`(같은 서버). 원문이 `//host/x`로 시작하면 외부 URL 그대로. `${ctx}/x/view.do&id=${id}` → 경로 `/x/view.do`, 쿼리 `id={*}`. `${ctx}${url}&extId=${extId}` → 경로가 `/{*}`뿐이라 미해결(`http-url`).

## 4. 수용 기준

### F-011-AC1 (대상: F-011-R1)
- Given 지시자, 스크립틀릿, 식, 선언, JSP 주석, HTML 주석, EL, 커스텀 태그, 속성 값 안에 `<c:url .../>`와 EL이 든 HTML 태그, `<script>` 본문이 있는 JSP
- When 스캐너가 읽으면
- Then 요소별 토큰이 종류, 이름, 속성, 줄·열과 함께 나오고 주석 안의 태그는 토큰으로 나오지 않는다

### F-011-AC2 (대상: F-011-R2)
- Given 닫히지 않은 태그, 닫히지 않은 따옴표, 닫히지 않은 `<%`·`<%--`·`<script>`, 잘못 중첩된 태그가 있는 JSP
- When 스캐너가 읽으면
- Then 예외 없이 끝까지 읽고 깨진 지점 뒤의 태그를 토큰으로 낸다

### F-011-AC3 (대상: F-011-R3)
- Given taglib 지시자 2개와 스크립틀릿·식이 있는 `.jsp`, `.jspf`, `.tag` 파일
- When 분석하면
- Then 파일마다 `jsp:page:<경로>` 노드와 `FILE -CONTAINS→` 엣지가 있고 `pageType`, `taglibs`, `scriptletCount`가 맞다

### F-011-AC4 (대상: F-011-R4)
- Given `<%@ include file="/WEB-INF/views/common/footer.jspf" %>`, `<jsp:include page="../common/header.jsp"/>`, `<c:import url="/WEB-INF/views/common/menu.jsp"/>`, `<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>` + `<t:layout>`을 쓰는 JSP와 그 대상 파일들
- When 분석하면
- Then 네 대상 모두에 `RESOLVED` `INCLUDES` 엣지가 있다(웹 루트는 `WEB-INF`의 부모로 판정)

### F-011-AC5 (대상: F-011-R5)
- Given 없는 파일을 가리키는 include와 `<jsp:include page="${viewName}.jsp"/>`
- When 분석하면
- Then 둘 다 `unresolved:jsp-include:...`로 가는 `UNRESOLVED` `INCLUDES` 엣지와 `UNRESOLVED_REFERENCE` 진단이 있다

### F-011-AC6 (대상: F-011-R6)
- Given `${ctx}/resources/js/order.js`, `<c:url value='/resources/js/common.js'/>`, `<%=request.getContextPath()%>/js/legacy.js`, 물리 경로가 다른 `/static/js/util.js`, 외부 CDN URL, 없는 파일을 `<script src>`로 쓰는 JSP
- When 분석하면
- Then 앞의 셋은 `RESOLVED`, 넷째는 `INFERRED` `INCLUDES`(→`js:module:<경로>`)이고, CDN과 없는 파일은 엣지 없이 `externalScripts`에 있다

### F-011-AC7 (대상: F-011-R7)
- Given 검색 `<form action method="get">`, method 없는 `<form>`, `<form:form action modelAttribute>`, `<a href>`, `<iframe src>`, `<c:redirect url>`, `<meta http-equiv="refresh" content="0;url=...">`
- When 분석하면
- Then 각각 `HTTP_REQUEST` 노드(`httpMethod`, `url`, `api`, `dynamic`)와 `REQUESTS` 엣지가 있고, 메서드는 form 기본 `GET`, form:form 기본 `POST`다

### F-011-AC8 (대상: F-011-R8)
- Given 컨텍스트 경로 접두(`${pageContext.request.contextPath}`, `${ctx}`, `<c:url>`, `<%=request.getContextPath()%>`), 쿼리스트링과 fragment, 경로 중간의 EL, `<c:url var="u">` 뒤의 `${u}`, 외부 URL, 상대 URL, 그리고 `#`·`javascript:`·`mailto:`·`.css`·빈 값인 링크
- When 분석하면
- Then URL이 규칙대로 정규화되고(`/order/{*}/view`, `attrs.query`, `external`, `relative`) 제외 대상은 `HTTP_REQUEST`가 없다

### F-011-AC9 (대상: F-011-R9)
- Given `<a href="${detailUrl}">`, `<form action="<%= target %>">`
- When 분석하면
- Then `unresolved:http-url:...`로 가는 `UNRESOLVED` `REQUESTS` 엣지가 있다

### F-011-AC10 (대상: F-011-R10)
- Given 인라인 `<script>` 2개, `type="text/template"` 스크립트 1개, `src`만 있는 스크립트 1개가 있는 JSP
- When 분석하면
- Then `js` 내장 소스가 2개이고 주인은 `js:module:<JSP 경로>`, origin은 본문 시작 위치이며, `JS_MODULE` 노드와 `JSP_PAGE -CONTAINS→ JS_MODULE` 엣지가 있다

### F-011-AC11 (대상: F-011-R11)
- Given EL, `<%= %>`, `<c:out>`, 따옴표 안팎의 `<c:url>`, 스크립틀릿, `<c:if>`·`<c:forEach>`, 여러 줄에 걸친 요소가 든 스크립트
- When 분석하면
- Then 내장 소스의 본문이 치환 규칙표대로이고 줄 수가 원문과 같으며, 원래 요소가 토큰보다 길면 길이도 같다

### F-011-AC12 (대상: F-011-R12)
- Given `onclick="fn_search(1)"`이 두 번, `onchange="fn_change(this)"`, `href="javascript:fn_detail('${o.id}')"`, `href="javascript:void(0)"`이 있는 JSP
- When 분석하면
- Then `inlineHandler=true`인 내장 소스가 3개(중복 제거, `void(0)` 제외)이고 origin이 속성 값의 시작 위치다

### F-011-AC13 (대상: F-011-R3, F-011-R10)
- Given 폼, 링크, `<script src>`, 템플릿 리터럴 `${id}`가 든 인라인 스크립트가 있는 `.html` 파일
- When 분석하면
- Then `pageType=html`인 `JSP_PAGE`가 있고 요청·포함이 추출되며 스크립트 본문은 치환 없이 그대로 넘어간다

### F-011-AC14 (대상: F-011-R13)
- Given 읽을 수 없는 JSP 하나와 정상 JSP 하나
- When 분석하면
- Then 읽을 수 없는 파일에 `PARSE_FAILED` 진단이 있고 정상 JSP의 노드는 만들어진다

### F-011-AC15 (대상: F-011-R4, F-011-R8)
- Given taglib 지시자와 `<c:set var="ctx">`를 정적 include한 조각(`taglibs.jspf`)에 둔 JSP
- When 분석하면
- Then 포함한 조각에서 선언한 `tagdir` 접두사의 태그가 `INCLUDES`로 잡힌다

### F-011-AC16 (대상: F-011-R14)
- Given 문자열 안팎의 EL·`<%= %>`·`<c:url>`·URL 변수, 제어 태그와 스크립틀릿, 여러 줄에 걸친 요소, 닫히지 않은 문자열이 든 스크립트와, 치환할 것이 없는 스크립트, EL이 든 `javascript:` URL, 엔티티(`&amp;`)와 EL이 함께 든 이벤트 핸들러, 순수 HTML 파일의 스크립트
- When 분석하면
- Then 치환한 내장 소스의 `attrs.jspSubstitutions`가 위 형식이고(예: `14,12,6:${ctx}`), 모든 기록의 구간을 원문으로 바꾸면 원래 본문과 같으며, 엔티티를 푼 핸들러는 오프셋이 푼 본문 기준이고, 치환이 없는 스크립트와 HTML 파일에는 키가 없다

### F-011-AC17 (대상: F-011-R15)
- Given 벤더 폴더(`static/ckeditor/…`, `static/bootstrap/2.3.1/docs/…`, `static/ckfinder/…/connector.jsp`, `js/lib/demo/…`)의 HTML·JSP와 벤더가 아닌 폴더의 HTML·JSP(도메인 이름으로 쓴 `WEB-INF/views/vendor/…`·`WEB-INF/views/layer/…`·`resources/templates/plugin/…` 포함). 그리고 `web.skipVendor=false`를 준 경우와 `web.vendorPatterns=**/thirdparty/**,help,!**/ckeditor/_samples/**`를 준 경우
- When 분석하면
- Then 벤더 폴더의 페이지는 `JSP_PAGE`(`pageType`, `skipped=vendor`)와 `FILE -CONTAINS→`만 있고 요청·포함·내장 소스가 없으며 `FILE_SKIPPED`(INFO)가 벤더 폴더마다 1건(`벤더 페이지 n개 건너뜀: <폴더>`) 있고, 벤더가 아닌 페이지는 그대로 분석된다. `web.skipVendor=false`면 모두 분석되고 진단이 없으며, glob에 맞는 페이지는 기본 규칙에 더해 건너뛰고 `!` glob에 맞는 페이지는 기본 규칙에 걸려도 분석한다
- 개정(2026-10-11 4차): 진단 메시지에 판정 근거가 붙는다(`벤더 페이지 2개 건너뜀(경로): …/static/ckeditor`, glob으로 건너뛴 것은 `(옵션)`)

### F-011-AC18 (대상: F-011-R16)
- Given `<form:form modelAttribute id name method="post">`(action 없음), `<form:form modelAttribute>`(id·method·action 없음), `<form name method="get" action>`, `<form id action>`(method 없음), `method="${m}"`인 폼, 대문자 태그의 POST 폼(action 없음), 같은 이름으로 메서드가 다른 두 폼이 있는 JSP와 폼이 없는 JSP
- When 분석하면
- Then 앞 페이지의 `attrs.formMethods`가 `[listForm=POST, #listForm=POST, #detailVO=POST, searchForm=GET, #plain=GET, dyn=ANY, upper=POST, twice=ANY]`이고 `selfPostForms`가 3이며 폼 요소의 요청은 종전대로이고, 폼이 없는 페이지에는 두 키가 없다

### F-011-AC19 (대상: F-011-R17)
- Given Thymeleaf 템플릿의 `th:href="@{/}"`, `@{'/news/article/' + ${article.articleId}}`, `@{/news/list(page=${page.number},size=10)}`, `@{/news/{id}/view(id=${a.id},tab='c')}`, `@{|/news/tag/${tag.alias}|}`, `@{${menu.url}}`, `@{http://example.com/ext}`, `${plain.url}`, `th:action`(정적 `action="#"`과 함께), `<iframe th:src>`, `data-th-href`, 정적 `href`만 있는 링크, `<link th:href>`, `<script th:src>`(정적인 것, 접두가 동적인 것)
- When 분석하면
- Then 요청이 `th:` 식에서 읽은 URL로 만들어지고(`/news/article/{*}`, 쿼리 `page={*}&size=10`·`tab=c`, `external`) 원형용 정적 값으로 만든 요청은 없으며, 전부 동적인 식은 미해결(`http-url`, 원문은 속성 값)이고, 정적 `th:src`는 스크립트 `INCLUDES`가 되며, 접두가 동적인 `th:src`는 원형 `src`가 가리키는 파일이 같은 꼬리로 끝날 때만 `INFERRED` `INCLUDES`이고 아니면 `externalScripts`에 남는다

### F-011-AC20 (대상: F-011-R18)
- Given `templates/order/list.html`의 `th:replace="fragments/head :: common('주문')"`, `th:insert="~{fragments/nav :: menu}"`, `th:include="fragments/footer"`, `<th:block th:replace="~{/layout/side.html :: side}"/>`, `:: local`, `this :: local`, `${dynamicView} :: body`, 없는 템플릿, 같은 템플릿의 두 번째 사용과 그 템플릿 파일들
- When 분석하면
- Then 네 템플릿 파일로 `RESOLVED` `INCLUDES`(`via=thymeleaf`, `path`=속성 값, 위치=그 태그)가 하나씩 있고, 같은 파일의 조각은 엣지가 없으며, 동적 이름과 없는 템플릿은 `unresolved:jsp-include:…` 미해결 엣지다

### F-011-AC21 (대상: F-011-R19)
- Given JSP 둘과 `progress` 호출을 기록하는 컨텍스트
- When 분석하면
- Then 두 파일의 상대 경로가 경로순으로 알려진다.

### F-011-AC22 (대상: F-011-R20)
- Given 줄 끝이 CR뿐인 JSP(링크, 인라인 스크립트, `<jsp:include>`)와 CRLF·CR·LF가 섞인 JSP
- When 분석하면
- Then 요청·포함의 줄 번호와 내장 소스의 origin이 CR마다 늘어난 값이고, 내장 소스 본문의 줄 끝은 LF이며, CRLF는 줄 하나다. `LineMap`은 CR 단독을 줄 끝으로, CRLF의 CR을 그 줄의 글자로 센다

### F-011-AC23 (대상: F-011-R21)
- Given `decorators.xml`(`defaultdir`, 이름 다섯, 주석 처리된 데코레이터, `/`로 시작하는 `page`)과 `<meta name="decorator" content="default">`(대문자 태그 포함), `content="cms_default_${site.theme}"`, 없는 이름, `meta`가 없는 페이지. 그리고 패턴이 하나(`/*`)인 설정, 패턴이 둘인 설정, `sitemesh3.xml`의 `mapping`과 경로를 적은 `meta`
- When 분석하면
- Then `meta`가 있는 페이지에서 그 데코레이터 JSP로 `INCLUDES`(`INFERRED`, `via=sitemesh`, `path`=이름, 위치=`meta` 태그)가 있고, EL이 섞인 이름은 정적인 부분이 맞는 데코레이터마다, 없는 이름은 `unresolved:jsp-include:decorator:<이름>`이며, 패턴이 `/*` 하나뿐인 설정에서는 `meta`가 없는 페이지도 이어지되(`meta`가 있으면 그것이 먼저) 데코레이터 자신·포함되는 조각·`.jspf`는 빠지고, 패턴이 둘이면 잇지 않으며, SiteMesh 3도 같다
- 개정(2026-10-11 4차, F-011-R27): "EL이 섞인 이름은 정적인 부분이 맞는 데코레이터마다"는 페이지와 공통 디렉터리 길이가 같아 가리지 못할 때만이다(이 테스트의 `…/cms/front/index.jsp`가 그렇다. 엣지에 `ambiguous=true`). 가릴 수 있으면 하나만 잇는다(AC30)

### F-011-AC24 (대상: F-011-R22)
- Given Tiles 설정 파일 둘: `base`(template, 제목·header·빈 body·footer), `order.list`(extends, body), `*/*`(extends, `{1}/{2}.jsp`), `popup`(body가 다른 정의 이름), template만 있는 정의, 없는 정의를 extends한 정의, 서로를 extends하는 두 정의, 다른 파일의 `base`를 extends하고 footer를 덮은 정의, Tiles 1의 `path`·`<put>` 정의(없는 JSP 포함)
- When 분석하면
- Then 두 파일의 `FILE` 노드에 `tilesDefinitions`·`tilesAttributes`가 위 형식대로 있고(extends를 푼 template, 물려받고 덮은 attribute, 와일드카드 원문, template 없는 정의는 `이름=`), 와일드카드가 없는 정의의 template → 조각 `INCLUDES`(`INFERRED`, `via=tiles`, 위치=설정 파일의 `<put-attribute>`)가 대상마다 한 번씩 있으며, 와일드카드 정의의 본문으로는 엣지가 없고, 없는 JSP는 미해결(`jsp-include`)이다

### F-011-AC25 (대상: F-011-R23)
- Given `<script src="${basePath}/resources/ui-admin/js/common.js">`(디스크에는 `ui-admin/src/js/common.js`와 `other/src/util/common.js`), 가장 길게 맞는 후보가 둘인 src, 파일 이름만 맞는 src, 벤더 폴더(`plugins/tool/`)의 파일만 맞는 src, 프로젝트에 `app.js`만 있는 `app.min.js`, 아무것도 없는 `gone.min.js`
- When 분석하면
- Then 첫째는 `ui-admin/src/js/common.js`로 `INFERRED` `INCLUDES`, 다섯째는 `app.js`로 `INFERRED` + `minOf=/resources/ui-admin/js/app.min.js`이고, 나머지는 엣지 없이 `externalScripts`에 문서 순서로 남는다

### F-011-AC26 (대상: F-011-R24)
- Given `<sys:treeselect url="/sys/office/treeData?type=2">`, `<my:link href="${ctx}/sys/user/list">`, `<my:grid dataUrl="${ctx}/sys/user/data/${user.id}">`, URL 속성이 아닌 `uploadPath`, 정적 이미지 `src`, 상대 경로, 값을 모르는 식, 외부 URL, `<c:import url="/common/menu.do">`, `<c:url var>`
- When 분석하면
- Then 앞의 셋은 `api=tag`·`ANY`인 `HTTP_REQUEST`(쿼리 `type=2`, `url`은 원문, 위치는 그 태그)와 `INFERRED` `REQUESTS`가 있고, `<c:import>`는 종전대로 `api=include`·`GET` 하나뿐이며, 나머지는 요청도 미해결도 없고, 태그 파일의 `INCLUDES`는 그대로다

### F-011-AC27 (대상: F-011-R25)
- Given `<a href="${ctx}/oa/oaNotify/${oaNotify.self?'self':''}">`, `<form:form action="${ctx}/oa/oaNotify/${oaNotify.self ? 'self' : 'all'}/search" method="post">`, 갈래가 변수인 삼항이 든 링크
- When 분석하면
- Then 요청이 `GET /oa/oaNotify/self`·`GET /oa/oaNotify/`, `POST /oa/oaNotify/self/search`·`POST /oa/oaNotify/all/search`로 있고(`url`은 원문, `dynamic=false`, `REQUESTS`는 `INFERRED`) 변수가 섞인 삼항은 `{*}` 그대로(`RESOLVED`)다

### F-011-AC28 (대상: F-011-R26)
- Given `<a href="<c:url value='/'/>/twitter/login.do">`, `${ctx}//twitter/me.do`, `//cdn.example.com/lib/x`, `${ctx}${url}&extId=${extId}`, `${ctx}/x/view.do&id=${id}`
- When 분석하면
- Then 앞의 둘은 `/twitter/login.do`·`/twitter/me.do`(외부 아님), 셋째는 외부 URL, 넷째는 미해결(`http-url`, 원문은 속성 값), 다섯째는 경로 `/x/view.do`·쿼리 `id={*}`다

### F-011-AC29 (대상: F-011-R15)
- Given `admin/src/plugins/fancyplayer/demo/index.html`, `admin/src/plugins/readme.html`, `WEB-INF/views/plugins/board/list.jsp`, `resources/templates/plugins/cms/index.html`
- When 분석하면
- Then 첫째만 `skipped=vendor`이고 나머지 셋은 분석된다

### F-011-AC30 (대상: F-011-R27)
- Given `decorators.xml`의 `cms_default_basic`(`…/themes/basic/layouts/default.jsp`)·`cms_default_weixin`(`…/themes/weixin/layouts/default.jsp`)·`pop_small`·`default`와, `content="cms_default_${site.theme}"`인 `…/themes/basic/frontList.jsp`·`…/themes/weixin/frontList.jsp`·`…/front/index.jsp`, `content="pop_${size}"`인 페이지, `content="default"`인 페이지
- When 분석하면
- Then 테마 폴더의 두 페이지는 자기 테마의 데코레이터 하나로만 이어지고(`INFERRED`, `dynamicDecorator=true`, `ambiguous` 없음, `path`는 원문), 테마 폴더 밖의 페이지는 두 데코레이터 모두에 `dynamicDecorator=true`·`ambiguous=true`로 이어지며, 후보가 하나뿐인 동적 이름은 `dynamicDecorator=true`만, 정적 이름의 엣지에는 두 키가 없다

### F-011-AC31 (대상: F-011-R28)
- Given `.submit()`을 부르는 스크립트와 `action` 없는 `<form:form modelAttribute name method="post">`·`<form:form commandName>`(제출 버튼)이 있는 JSP. `<FORM name>`(`type="image"` 버튼)·`action`이 있는 폼·`action=""`이고 `method="${m}"`인 폼(`<button>`)이 있는 JSP. 스크립트가 `document.board.action = …`을 대입하는 JSP, `attr('action', …)`을 쓰는 JSP, 제출 버튼도 `.submit()`도 없이 `$.ajax`로 보내는 JSP(`type="button"` 버튼). `action` 없는 GET 폼·POST 폼과 `th:action` 폼이 있는 HTML 파일
- When 분석하면
- Then 첫 JSP에 `POST {self}` 요청 둘(ID `http:request:POST {self}@<파일>:<줄>`, `api=form`, `path={self}`, `url` 빈 값, `dynamic=false`, `selfSubmit=true`, `modelAttribute`, `REQUESTS`는 `INFERRED`), 둘째 JSP에 `GET {self}`·`ANY {self}`가 있고 `action`이 있는 폼의 요청에는 `selfSubmit`이 없으며, `action`을 대입하는 두 JSP와 AJAX로 보내는 JSP에는 없고, HTML 파일에는 `POST {self}` 하나뿐이며, `selfPostForms`는 종전대로다

### F-011-AC32 (대상: F-011-R29)
- Given `<form:form modelAttribute="userManageVO" name="userManageVO" action=…>` 안의 `<input type="hidden" name="zip_url" value="<c:url value='/sym/cmm/EgovCcmZipSearchPopup.do'/>">`, `name="cal_url" id="calUrl" value="${pageContext.request.contextPath}/…?mode=1"`, 대문자 태그의 text input(`/sym/cmm/list`), 값이 `/list`·`/sym/cmm/view.jsp`·동적 경로·`${user.id}`·`2020/01/01`·외부 URL인 input, checkbox, `name`에 EL이 든 input. 폼 밖의 `id`만 있는 input과 `name`만 있는 input, `<form id="byId">` 안의 type 없는 input. 그리고 경로 값이 없는 페이지
- When 분석하면
- Then 앞 페이지의 `attrs.formFields`가 `[userManageVO.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do, #userManageVO.zip_url=…, userManageVO.cal_url=/sym/cmm/EgovNormalCalPopup.do?mode=1, #userManageVO.cal_url=…, #calUrl=…, userManageVO.plain=/sym/cmm/list, #userManageVO.plain=…, #outside=/sym/cmm/outside.do, .loose=/a/b.do, #byId.go=/x/y/go.do]`이고, 필드 값으로 만든 요청은 없으며, 뒤 페이지에는 키가 없다

### F-011-AC33 (대상: F-011-R30)
- Given (가) `$("#inputForm").validate({ submitHandler: function(form){ form.submit(); } })`와 `type="button"` 버튼뿐인 `<form:form class="form-horizontal">`. (나) 유일한 제출 버튼이 `onclick="fnList(); return false;"`인 `<form name="Form" action="" method="post">`(`<button onClick="…;return false">` 포함). (다) `onclick="fn_egov_modify(document.forms[0]); return false;"`인 제출 버튼과 `function fn_egov_modify(form){ … form.submit(); }`. (라) `<!--`·`-->`로 감싼 스크립트의 같은 함수를 `href="JavaScript:javascript:fn_egov_regist(document.AdministCode);"`로 부르는 페이지. (마) `action` 없는 폼 다섯(`a`, `b`, `#c`, `#d`, `e`)과 `document.b.submit()`, `$("#c").attr("target", "_self").submit()`, `$(this).parents("form").submit()`(스크립트), `document.forms[4].submit()`. (바) `action` 없는 폼 하나 + `action` 있는 폼 하나와 수신자를 모르는 `.submit()`. (사) `onchange="this.form.submit()"`이 든 폼, `onsubmit="return false;"`인 폼(제출 버튼 있음), `onclick="go(this); return false;"`와 `function go(el){ el.form.submit(); }`, 아무것도 없는 폼. (아) `opener.document.frm.submit()`, `document.other.submit()`, `document.getElementById("nope").submit()`뿐인 페이지의 `<form name="frm">`. (자) `var f = document.getElementById("vf"); f.submit();`와 `legacy.submit()`이 있는 페이지의 폼 `#vf`, `#vg`, `legacy`. (차) `.submit()`이 JS 주석·JSP 주석·HTML 주석 안에만 있는 페이지
- When 분석하면
- Then (가)(나)(아)(차)에는 `selfSubmit` 요청이 없고, (다)(라)에는 `POST {self}`(`INFERRED`)가 있으며, (마)는 `b`·`#c`·`e`에만, (바)는 `action` 없는 폼에, (사)는 첫째와 셋째 폼에만, (자)는 `#vf`와 `legacy`에만 있고, `selfPostForms`는 종전대로다

### F-011-AC34 (대상: F-011-R31)
- Given `<a href="${ctx}/cms/file/list?type=${type eq 'thumb'?'1':'0'}&sm=${multi?1:0}">`, `<a href="${ctx}/cms/${own ? 'my' : 'all'}/list?dts=${type eq 'thumb'?'1':'0'}">`
- When 분석하면
- Then 첫째는 요청 `GET /cms/file/list` 하나(쿼리 `type={*}&sm={*}`, `REQUESTS`는 `RESOLVED`)이고, 둘째는 경로의 삼항만 펼쳐 `GET /cms/my/list`·`GET /cms/all/list`(쿼리 `dts={*}`, `INFERRED`)다

## 5. 가정
- `scriptletCount`는 스크립틀릿 `<% %>`, 식 `<%= %>`, 선언 `<%! %>`의 합이다.
- `taglibs`는 `접두사=uri` 또는 `접두사=tagdir:<경로>` 문자열의 목록이다.
- 위치(`SourceLocation`)의 끝은 요소의 마지막 글자 **다음** 위치다(끝 열 제외). 요청·포함 엣지의 위치는 그 태그 전체, 내장 소스의 origin은 본문(또는 속성 값) 구간이다.
- `PARSE_FAILED` 진단의 심각도는 `ERROR`다.
- `<script src>`는 JS 파일뿐 아니라 TS 파일(`FileType.TS`)도 대상으로 찾는다(`<script type="module" src="/src/main.tsx">`).
- 대상 `JS_MODULE` 노드는 jsp가 `reference`만 한다(`declared=false`). web이 그 파일을 분석하면서 `declare`한다.
- `<c:import url>`의 외부 URL(`http://` 등)은 포함도 요청도 만들지 않는다.
- HTML 태그는 `on`으로 시작하는 속성을 모두 이벤트 핸들러로 본다. 커스텀 태그(`<form:select onchange>`)는 알려진 이벤트 이름만 본다(`only`, `once` 같은 일반 속성과 구분).
- 메서드에 EL이 섞인 폼(`method="${m}"`)은 `ANY`다.
- `<jsp:forward page>`도 대상이 JSP 파일이면 `INCLUDES`(`attrs.via=forward`)로 기록한다. 화면이 다른 화면으로 넘어가는 관계를 나타낼 엣지 종류가 계약에 따로 없다.
- `<jsp:include>`, `<c:import>`, `<jsp:forward>`의 대상이 프로젝트에 없고 페이지 확장자(`.jsp .jspf .tag .tagx .html .htm .inc`)도 아니면(예: `/common/menu.do`) 서버 요청으로 보고 `HTTP_REQUEST`(`api=include` 또는 `forward`, `GET`)를 만든다. 링커가 엔드포인트와 맞출 수 있게 하기 위해서다.
- 미해결 include의 원문 자리에는 정적으로 풀린 경우 프로젝트 기준 경로(예: `src/main/webapp/WEB-INF/views/x.jsp`)를, 동적인 경우 속성 값 원문을 쓴다.
- 같은 태그 파일을 한 페이지에서 여러 번 써도 `INCLUDES`는 첫 사용 위치에 한 번만 만든다.
- JSP의 상대 `<script src>`는 요청 URL 기준이라 정적으로 알 수 없으므로 경로 끝 일치(`INFERRED`)로만 찾는다. HTML 파일의 상대 src는 파일 위치 기준으로 찾는다.
- 쿼리만 있는 링크(`?page=2`)는 현재 URL로의 요청이라 경로를 알 수 없으므로 요청으로 만들지 않는다. `action`이 없는 폼은 4차부터 `selfSubmit` 요청으로 만든다(F-011-R28. 종전에는 만들지 않았다): 경로는 여전히 모르지만 "이 페이지를 그리는 엔드포인트의 경로"라는 것은 알고, 링커가 `RENDERS`로 그 경로를 안다.
- `selfSubmit` 요청을 만드는 조건(페이지에 `action` 대입이 없고 그 폼을 제출하는 흔적이 있다)은 페이지 본문의 글자만 보는 간이 판정이다(5차부터 흔적을 폼과 짝짓는다, F-011-R30. `action` 대입은 종전대로 페이지 단위다). 조건을 둔 것은 공개 프로젝트에서 `action` 없는 POST 폼 19개(전자정부 템플릿) 가운데 14개가 스크립트로 `action`을 대입하고 있었고, 다른 프로젝트(zheng)의 30개는 대부분 AJAX로 보내는 폼이었기 때문이다. 조건 없이 만들면 "그 페이지를 그리는 엔드포인트로 POST한다"는 틀린 대응이 생긴다.
- 제출 컨트롤의 `onclick`이 `return false`로 끝나면 그 버튼은 폼을 제출하지 않는 것으로 본다(F-011-R30). 그 처리기가 부르는 함수가 폼을 제출하면(`fn(document.frm); return false;`) `.submit()` 호출 쪽 규칙이 그것을 흔적으로 잡는다. `return fn();`·`return confirm(…)`처럼 값에 따라 갈리는 것은 막지 않는 것으로 본다.
- 숨긴 제출 버튼(`<div style="display:none"><input type="submit"></div>`)도 제출 컨트롤로 센다(공통컴포넌트 `EgovMtgPlaceImageDetail.jsp:59`. 스타일은 보지 않는다).
- 동적 SiteMesh 데코레이터의 후보를 디렉터리로 고르는 것(F-011-R27)은 "테마·모듈마다 폴더를 나누고 그 안에 레이아웃을 둔다"는 배치를 근거로 한 추론이다(엣지는 종전대로 `INFERRED`).
- `<script src>`가 있는 스크립트의 본문은 브라우저가 무시하므로 내장 소스로 넘기지 않는다.
- `javascript:void(0)`, `javascript:;`, `return false;`처럼 하는 일이 없는 핸들러는 넘기지 않는다.
- taglib 선언과 컨텍스트 경로 변수는 정적 include(`<%@ include %>`)로 끼워 넣은 파일의 것까지 따라가서 쓴다(지시자 include는 본문을 그대로 붙이는 것이므로 추측이 아니다).
- HTML 주석 안의 태그·스크립트는 읽지 않는다(조건부 주석 포함).
- 벤더 폴더의 JSP(`ckfinder/core/connector/java/connector.jsp` 등)도 HTML과 같이 건너뛴다. 라이브러리가 싣고 온 서버 쪽 조각이지 이 프로젝트의 화면이 아니다.
- 스크립트 안의 HTML 주석 열기 `<!--`와 닫기 `-->`는 jsp가 지우지 않고 그대로 넘긴다. web이 파서 입력에서 한 줄 주석으로 보정한다(F-010-R19).
- `<form:form>`이 스크립트 본문 안에 적혀 있어도(템플릿 스크립트) 폼 표에 싣는다.
- Thymeleaf 속성은 파일에 `xmlns:th` 선언이 있는지 보지 않고 속성 이름(`th:*`, `data-th-*`)만으로 판정한다.

## 6. 미확정 사항
- 없음. 사용자가 미확정 사항은 기본값으로 진행하라고 위임했다(2026-10-11). 위 가정은 검토가 필요하다.

### 알려진 한계
- 스크립틀릿 안의 Java 코드(`response.sendRedirect`, `out.print`로 찍는 태그 등)는 분석하지 않는다. 개수만 `scriptletCount`에 남긴다.
- `<c:set var="u" value="/x"/>`로 만든 URL 변수는 추적하지 않는다(`<c:url var>`, `<spring:url var>`만).
- 변수 추적은 같은 파일 안에서 문서 순서로만 한다. 다른 파일에서 만든 URL 변수는 풀지 못해 미해결(`http-url`)이 된다.
- 속성 값 안에 인라인으로 쓴 `<c:url>`의 `<c:param>`은 무시한다(그 쿼리 파라미터는 `attrs.query`에 없다). `<c:url var>` 정의의 `<c:param>`만 쿼리로 붙인다(뒤에서 `${u}&page=2`처럼 이어 쓰는 경우 경로와 쿼리를 바르게 가르기 위해서다).
- 컨텍스트 경로로 보는 접두는 단일 변수(`${ctx}`, `<%=ctx%>`)와 `contextPath`가 든 식뿐이다. `<%=Const.CTX%>/x.do`나 `${app.root}/x.do`는 `{*}/x.do`가 된다.
- 폼의 `action`을 스크립트에서 바꾸는 경우(`document.frm.action = ...`)는 web(F-010)의 몫이고, 폼 요소 자체에는 요청이 없거나 원래 `action`만 남는다. 메서드는 폼 표(`formMethods`)로 web에 넘긴다(F-011-R16).
- `selfSubmit` 요청(F-011-R28): 다른 JS 파일의 함수가 그 폼의 `action`을 대입하면(페이지 본문에는 대입이 없다) 틀린 요청이 남는다(`INFERRED`). 반대로 다른 파일의 함수가 `.submit()`을 부르고 폼 안에 제출 버튼이 없으면 만들지 않는다. 한 페이지에 폼이 여럿이면 `action` 대입이 어느 폼의 것인지 가리지 않고 모든 폼에 대해 만들지 않는다. (5차에서 해소) ~~제출 버튼의 `onclick`이 제출을 막는지(`return false`)는 보지 않는다.~~ `return false`로 끝나는 제출 버튼은 흔적으로 세지 않는다(F-011-R30). 링커가 그 페이지를 그리는 엔드포인트를 찾지 못하면(정적 HTML, 직접 여는 JSP) 대응 없는 요청으로 남는다.
- 제출 흔적의 짝(F-011-R30)은 글자만 보는 판정이다. 남은 것: (1) 제출 버튼이 `return false`로 막히고 그 처리기가 부르는 함수가 다른 JS 파일에서 폼을 제출하면 요청을 만들지 않는다(4차에는 버튼만으로 만들었다). (2) `action` 없는 폼이 여럿인 페이지에서 수신자를 모르는 `.submit()`(`$(this).parents("form").submit()`, `$("form").submit()`)은 어느 폼의 흔적도 아니다. (3) `document.forms[n]`의 n은 이 파일에 적힌 폼만 센다(include한 조각의 폼은 세지 못한다). (4) 변수는 함수 범위를 가리지 않고 이름으로만 따라간다(다른 함수의 같은 이름 변수에 대입한 값도 후보다). 객체의 메서드로 정의한 함수(`obj.save = function(form){…}`, `save: function(form){…}`)의 매개변수는 호출 자리를 찾지 못해 "모른다"다. (5) jQuery로 건 처리기(`$("#f").submit(function(){…})`)도 `.submit(` 호출로 센다(그 폼이 제출된다는 흔적으로 본다). `trigger("submit")`·`requestSubmit()`은 읽지 않는다. (6) 문자열·정규식 리터럴 안의 따옴표·중괄호가 함수의 범위 판정을 흐트러뜨릴 수 있다(그러면 "모른다"가 된다).
- 필드 표(F-011-R29): 스크립트가 넣는 값, `<form:hidden path>`·`<form:input>`(값이 모델에서 온다), `<select>`·`<textarea>`, 동적인 경로(`${ctx}/x/${kind}.do`)는 싣지 않는다. 폼의 여닫음은 토큰 순서로만 본다(`<c:if>`로 갈라 연 폼, 다른 파일에서 연 폼은 어긋날 수 있다).
- 동적 데코레이터(F-011-R27): 페이지와 레이아웃이 같은 폴더 계통에 있지 않은 배치(`views/cms/front/x.jsp` ↔ `layouts/themes/basic.jsp`)에서는 가리지 못해 후보 전부가 `ambiguous=true`로 남는다. EL의 값(`site.theme`)은 평가하지 않는다.
- 폼 표는 페이지 단위다. 이름·ID에 EL이 섞인 폼(`id="${prefix}Form"`), 스크립트가 만들어 내는 폼(`document.createElement('form')`), Struts `<html:form>`은 표에 없다.
- Thymeleaf: 식을 평가하지 않는다. `th:method`, `th:attr="href=@{…}"`, `th:onclick`의 URL, 인라인 스크립트의 `[[@{/x}]]`·`/*[[@{/x}]]*/`(그 스크립트는 web에서 구문 오류가 될 수 있다), 조건식·삼항으로 고르는 링크(`${a} ? @{/x} : @{/y}`), 레이아웃 방언(`layout:decorate`, `layout:fragment`)은 읽지 않는다. FreeMarker·Velocity 템플릿은 다루지 않는다.
- `th:src`의 접두가 동적이고 원형 `src`가 없거나 가리키는 파일이 다르면 스크립트 포함을 잇지 못한다(`externalScripts`에만 남는다).
- (해소, 2026-10-11 3차) ~~레이아웃 프레임워크가 붙이는 레이아웃 JSP와 내용 JSP의 관계는 기록하지 않는다.~~ SiteMesh와 Tiles 설정 파일을 읽어 기록한다(F-011-R21·R22). 남은 것: SiteMesh의 URL 패턴이 여럿이거나 `/admin/*`처럼 일부 경로만일 때 `meta`가 없는 페이지(URL과 JSP 파일의 대응을 모른다), `<excludes>` 패턴(패턴 하나로 모든 페이지에 적용할 때 제외 경로의 페이지에도 엣지가 생긴다), Java 설정으로 만든 SiteMesh 3 필터·Tiles 정의, `web.xml`에서 다른 위치로 지정한 설정 파일은 루트 요소로만 찾는다. Tiles 정의 안에 중첩한 이름 없는 정의, `<put-list-attribute>`, `cascade`는 읽지 않는다. JSP 안의 `<tiles:insertAttribute>`·`<tiles:insertDefinition>`도 읽지 않는다(정의 쪽에서만 잇는다).
- (해소, 2026-10-11 3차) ~~`<script src>`가 다른 모듈의 자원을 다른 URL로 가리키면 `externalScripts`에만 남는다.~~ 경로 끝에서부터 두 조각 이상 가장 길게 맞는 파일이 하나뿐이면 잇는다(F-011-R23). 파일 이름 한 조각만 맞거나 후보가 둘 이상이면 여전히 `externalScripts`다.
- 커스텀 태그의 URL 속성(F-011-R24)은 속성 이름으로만 본다. 그 태그가 값을 URL로 쓰지 않아도(`<my:x url="/로 시작하는 다른 뜻의 값">`) 요청(추론)이 되고, 다른 이름의 URL 속성(`link`, `path`, `checkUrl`)은 읽지 않는다. 태그 파일이 값 앞에 다른 접두(`${ctx}${adminPath}`)를 붙여도 그 접두는 모른다(링커의 접두 처리에 맡긴다).
- EL 삼항(F-011-R25)은 속성 값의 요청 URL에만 적용한다. 인라인 스크립트 안의 같은 EL은 jsp 쪽에서는 종전대로 `__JSP_EXPR__`로 넘긴다. (4차에서 해소) web이 치환 목록의 원문을 읽어 문자열 안의 삼항 EL을 후보 둘로 펼친다(F-010-R44). 치환 약속은 바뀌지 않았다.
- JS 문자열로 조립한 태그(`'<iframe src="…">'`, `'<a href="…">'`)는 jsp가 읽지 않는다. (5차에서 해소) 스크립트가 그 문자열을 DOM에 넣는 자리(`.html(…)`, `.append(…)`, `innerHTML`, `document.write(…)`, `$(…)`, `insertAdjacentHTML`)에서 web이 읽는다(F-010-R45). 속성 값 밖에서 조립한 태그(`<img src="<c:url …/>">`는 요청 대상이 아니다)는 읽지 않는다.
- 닫히지 않은 따옴표는 "줄을 넘긴 뒤 HTML 태그가 나오면 깨진 것"으로 판정한다. 여러 줄짜리 속성 값 안에 HTML 태그 문자열이 있으면 값이 첫 줄에서 잘릴 수 있다.
- 스크립트 치환에서 JS 문자열 안팎 판정은 따옴표·주석만 보는 간이 판정이다. 정규식 리터럴 안의 따옴표가 있으면 틀릴 수 있다.
- 치환 목록은 내장 소스마다 치환한 JSP 요소의 원문을 한 번 더 들고 있다(스크립트 안의 스크립틀릿·JSP 주석 포함). 내장 소스는 분석이 끝나면 버려지고 `graph.json`에는 들어가지 않는다.
- 스크립트 안의 JSP 식이 JS 식별자에 붙어 있으면(`function ${prefix}Open() {}`) 치환 결과가 `__JSP_EXPR__Open`이라는 이름이 된다. web은 함수 노드의 이름과 ID에서 그 자리를 `{*}`로 쓰고 `dynamicName=true`를 붙인다(2026-10-11 2차 수정으로 고쳐 씀: 전에는 `__JSP_EXPR__`가 이름에 그대로 남았다. F-010-R25).
- `<c:choose>`의 갈래처럼 서버에서 하나만 남는 스크립트 조각은 전부 남으므로 같은 변수 선언이 여러 번 보일 수 있다.
- `web.xml`의 `include-prelude`·`welcome-file`은 다루지 않는다. Struts `<html:form action>` 등 접두사 있는 태그의 URL 속성은 `api=tag`·`ANY` 요청(추론)으로만 잡힌다(F-011-R24. 폼 표에는 없다).

### 그래프 계약에 추가를 제안하는 것
design.md에 반영됨(2026-10-11): 아래 항목은 F-001 design.md 3.3~3.6·3.9에 있다. 이번 라운드의 `jspSubstitutions`는 3.9 표에 행을 더했다(형식은 이 문서 3절 "치환 목록"을 가리킨다). 위치의 끝이 제외라는 것은 3.5 공통 규칙에 있다.

design.md에 반영됨(2026-10-11, 2차): 아래 다섯 항목은 F-001 design.md에 있다. 옵션 키는 3.8, `skipped`·`formMethods`·`selfPostForms`는 3.5(JSP_PAGE)와 3.9(폼 표를 넘기는 길), `via=thymeleaf`는 3.4·3.5, `FILE_SKIPPED`는 3.7, Thymeleaf 링크 식은 3.3(HTTP_REQUEST를 만드는 것)에 넣었다.

- 3.8 옵션 키: `web.skipVendor`, `web.vendorPatterns`를 jsp도 읽는다(F-010 spec의 제안과 같은 행)
- 3.5 `JSP_PAGE` attrs: `skipped`(`vendor`), `formMethods`(List<String>. `"<키>=<METHOD>"`, 키는 폼 name 또는 `#id`. jsp가 넣고 web이 읽는다), `selfPostForms`(int)
- 3.5 `INCLUDES` attrs: `via` 값 `thymeleaf`(`th:replace`·`th:insert`·`th:include`). `<script th:src>`의 `via`는 `script` 그대로다
- 3.7 `FILE_SKIPPED`: jsp도 낸다(벤더 폴더의 페이지를 건너뜀, 폴더마다 1건)
- 3.3 `HTTP_REQUEST`를 만드는 것에 "HTML의 Thymeleaf 링크 식(`th:href`·`th:action`·`th:src`)" 추가. `attrs.url`은 `th:` 속성 값 원문이다

**3차(2026-10-11) — design.md에 반영이 필요하다(이 라운드에서는 design.md를 고치지 않았다. 공유 문서라 담당자가 반영한다)**

design.md 반영됨(2026-10-11): `via=sitemesh|tiles`는 3.4(INCLUDES 행)·3.5(엣지 attrs 표), `tilesDefinitions`·`tilesAttributes`는 3.5(FILE (Tiles 설정)), `minOf`는 3.4·3.5, `api=tag`와 `INFERRED`일 수 있는 `REQUESTS`는 3.3·3.4·3.5, `decorator:<이름>`은 3.6, jsp가 읽는 XML은 4.2, 줄 세기는 3.5 공통 규칙 "줄 끝은 LF, CRLF, CR 단독이다"에 넣었다. 마지막 항목의 [확인 필요]는 코드로 확인했다: sql의 `Lexer`·`ScriptSplitter`와 springxml의 `XmlParser`도 CR 단독을 줄로 센다. java(JDT)만 [확인 필요]로 남겼다.

- 3.4·3.5 `INCLUDES`(JSP_PAGE→JSP_PAGE) attrs: `via` 값 `sitemesh`(내용 페이지 → 데코레이터 JSP. 항상 `INFERRED`. `path`는 `meta`의 `content` 값 또는 URL 패턴), `tiles`(Tiles 정의의 template JSP → 조각 JSP. 항상 `INFERRED`. `path`는 attribute 값 원문이고 위치는 설정 파일이다). 두 엣지는 "실행 때 한 화면으로 합쳐진다"는 뜻이지 JSP 본문에 include가 있다는 뜻이 아니다. `via=tiles` 엣지는 template 하나에서 여러 정의의 조각으로 퍼지므로, 소비자가 "한 화면"을 따질 때는 엣지가 아니라 `tilesDefinitions`·`tilesAttributes`로 정의 단위로 묶어야 한다
- 3.5 `FILE` 노드(Tiles 설정 파일) attrs: `tilesDefinitions`(List<String>. `<정의 이름>=<template 경로>`. extends를 푼 최종 값, 없으면 빈 값, 와일드카드 정의는 원문), `tilesAttributes`(List<String>. `<정의 이름>=<조각 JSP 경로>`. 물려받은 것 포함, 덮은 것은 덮은 값만, 값이 다른 정의 이름이면 그 template, `{n}` 유지). 경로는 설정에 적힌 그대로(웹 루트 기준). jsp가 넣고 링커(뷰 이름 → JSP)와 web(전역 스크립트의 범위)이 읽는다
- 3.4·3.5 `INCLUDES`(JSP_PAGE→JS_MODULE) attrs: `minOf`(페이지가 가리킨 것은 압축본이고 이 엣지는 그 원본 스크립트로 이은 것. 값은 압축본의 src 경로. `INFERRED`)
- 3.5 `HTTP_REQUEST` attrs: `api` 값 `tag`(접두사 있는 태그의 URL 속성. 메서드 `ANY`, `REQUESTS`는 항상 `INFERRED`)
- 3.4 `REQUESTS`(JSP_PAGE→): `INFERRED`일 수 있다(`api=tag`, EL 삼항의 후보 URL)
- 3.6 `jsp-include`: 원문 `decorator:<이름>`(찾지 못한 SiteMesh 데코레이터)이 더해진다
- 4.2: jsp가 `FileType.XML` 파일도 읽는다(루트가 `decorators`·`sitemesh`·`tiles-definitions`인 것만)
- 공통 규칙으로 올릴 것: "줄 끝은 LF, CRLF, CR 단독이다(CRLF는 줄 하나)". 지금 계약에는 줄을 세는 규칙이 없고 모듈마다 따로 센다(web·jsp·mybatis는 CR 단독을 센다. sql의 `Lexer`·`ScriptSplitter`, springxml의 `XmlParser`는 LF만 세는 것으로 보인다 [확인 필요])

**4차(2026-10-11) — design.md에 반영이 필요하다(이 라운드에서도 design.md는 고치지 않았다)**

design.md 반영됨(2026-10-11): `selfSubmit`과 `{self}`는 3.3(HTTP_REQUEST)·3.5(공통 규칙 "`HTTP_REQUEST`의 `path`와 `url`", 노드 attrs 표)·3.9(자리표시자 표), `formFields`와 `selfPostForms`는 3.5(JSP_PAGE)와 3.9, `dynamicDecorator`·`ambiguous`는 3.5 엣지 attrs 표(INCLUDES (JSP)), `FILE_SKIPPED` 메시지는 3.7에 넣었다. `selfPostForms`의 설명에는 "요청을 만들 수 없다"는 말이 없었다(뺄 것이 없었고, `selfSubmit` 요청과의 관계를 더했다).

- 3.5 `HTTP_REQUEST` attrs: `selfSubmit`(boolean. `action` 없는 폼이 현재 URL로 제출하는 요청. jsp가 넣고 링커가 읽는다: 이 페이지를 `RENDERS`하는 핸들러의 엔드포인트와 같은 경로·이 메서드의 엔드포인트로 잇는다. `REQUESTS`는 항상 `INFERRED`). 3.3·3.9: 이 요청의 ID와 `path`의 URL 자리는 `{self}`다(`http:request:POST {self}@<파일>:<줄>`). `{self}`는 URL 패턴이 아니므로 `path`로 엔드포인트를 맞추면 안 된다. `url`은 빈 문자열이다
- 3.5 `JSP_PAGE` attrs: `formFields`(List<String>. `<폼 name 또는 #id>.<필드 name>=<URL>`, `.<필드 name>=<URL>`, `#<input id>=<URL>`. hidden·text input에 든 서버 경로. jsp가 넣고 web이 읽는다. 3.9의 "내장 소스 attrs가 아닌 길로 넘기는 것"에 폼 표와 나란히 적는다). `selfPostForms`의 설명에서 "요청을 만들 수 없다"를 뺀다
- 3.5 `INCLUDES`(JSP_PAGE→JSP_PAGE, `via=sitemesh`) attrs: `dynamicDecorator`(boolean. 데코레이터 이름에 EL이 섞여 정적인 부분으로 맞춘 엣지), `ambiguous`(boolean. 동적 이름의 후보를 가리지 못해 후보 전부에 이은 엣지)
- 3.7 `FILE_SKIPPED`: 벤더 페이지 요약 진단의 메시지가 `벤더 페이지 n개 건너뜀(<근거>): <폴더>`로 바뀐다(근거는 `경로`·`옵션`)

**5차(2026-10-11) — design.md에 직접 반영했다**: `selfSubmit` 요청을 만드는 조건이 "페이지에 제출 흔적이 있다"에서 "그 폼을 제출하는 흔적이 있다"로 좁아졌다(3.5 `selfSubmit` 행, 3.4 `REQUESTS`(JSP_PAGE→) 행, 7절 한계). attr 이름·값과 ID 규칙은 그대로다. 쿼리 안의 EL 삼항은 후보로 펼치지 않는다(3.4의 "EL 삼항으로 갈린 후보 URL"은 경로의 삼항만이다).

- `JSP_PAGE` attrs: `pageType`(`jsp`,`jspf`,`tag`,`html`), `taglibs`(List), `scriptletCount`(int), `externalScripts`(List)
- `HTTP_REQUEST` attrs: `path`(정규화한 URL, 쿼리 제외), `query`(쿼리스트링), `external`(boolean), `relative`(boolean), `modelAttribute`(`<form:form>`), `api` 값 `frame`, `redirect`, `refresh`, `include`, `forward`
- `INCLUDES` 엣지 attrs: `via`(`directive`,`action`,`import`,`tag`,`forward`,`script`), `path`(속성 값 원문)
- `JS_MODULE` attrs: `inline`(boolean, JSP 인라인 스크립트의 주인 모듈)
- `EmbeddedSource.attrs`(js): `inlineHandler`(`"true"`), `attribute`(핸들러 속성 이름), `scriptType`(`<script type>` 원문), `jspSubstitutions`(치환 목록. 형식은 3절 "치환 목록". 3.9 표에 행 추가가 필요하다)
- 미해결 분류: `jsp-include`, `http-url`
- `SourceLocation`의 끝 위치가 포함인지 제외인지 계약에 없다. jsp는 제외(마지막 글자 다음)로 썼다
- 3.3 표의 `HTTP_REQUEST`, `JSP_PAGE` "만드는 모듈"과 3.4 표의 `INCLUDES`(JSP), `REQUESTS`(JSP_PAGE→) "만드는 모듈"에 jsp 추가

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: 없음
