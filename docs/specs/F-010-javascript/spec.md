---
type: spec
id: F-010
title: JavaScript/TypeScript 분석
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-010 JavaScript/TypeScript 분석

## 1. 배경
처리 흐름의 맨 앞은 화면 스크립트다. React 화면은 컴포넌트 → 훅 → API 모듈 → HTTP 요청으로, 레거시 화면은 JSP 인라인 스크립트 → 전역 함수 → `$.ajax`로 서버를 부른다. 이 구간이 그래프에 없으면 "화면 → Controller" 연결(F-012)의 출발점이 없다(PRD 1, 6 "화면: JSP와 JavaScript 양쪽"). 이 기능은 JS·TS 파일과 JSP 인라인 스크립트에서 모듈, 함수, import, 호출, HTTP 요청 지점을 뽑는다.

## 2. 범위
- 포함: `FileType.JS`(`.js .mjs .cjs .jsx`), `FileType.TS`(`.ts .tsx`) 파일과 `js` 내장 소스(F-011이 등록)의 분석. `JS_MODULE`, `JS_FUNCTION`, `HTTP_REQUEST` 노드와 `CONTAINS`, `IMPORTS`, `CALLS`, `REQUESTS` 엣지.
- 제외: 요청과 엔드포인트의 대응(F-012), JSP 자체의 분석과 `<script src>` 포함 관계(F-011), 타입 검사·타입 추론, Vue 단일 파일 컴포넌트(`.vue`), 번들러 설정(webpack·vite alias), 실행해야 알 수 있는 값.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-010-R1 | 시스템은 JS, JSX, TS, TSX 소스를 tree-sitter 문법으로 파싱해 구문 트리(종류, 자식, 필드, 원문, 위치)를 얻어야 한다. | ADR-0005, PRD 2 |
| F-010-R2 | 만약 소스에 구문 오류가 있으면, 시스템은 나머지 트리로 분석을 계속하고 그 파일에 `PARSE_FAILED`(WARN) 진단을 1건 남겨야 한다. 파일 하나를 읽거나 분석하다 예외가 나도 다른 파일의 분석은 계속해야 한다. | AGENTS.md |
| F-010-R3 | 시스템은 파일마다 `JS_MODULE` 노드와 `FILE -CONTAINS→ JS_MODULE` 엣지를 만들고, 이름을 정할 수 있는 함수마다 `JS_FUNCTION` 노드(`async`, `exported`, `component`, `kind`)와 `CONTAINS` 엣지(모듈→함수, 함수→중첩 함수)를 만들어야 한다. | 사용자 요청, design 3.3 |
| F-010-R4 | 시스템은 ESM `import`·`export … from`, `require()`, 동적 `import()`를 `IMPORTS`(모듈→모듈)로 기록해야 한다. 상대 경로(확장자 생략, 디렉터리 `index`)와 `tsconfig.json`·`jsconfig.json`의 `baseUrl`·`paths` 별칭을 해석하고, 패키지 import는 기록하지 않는다. 만약 상대·별칭 경로의 대상 파일을 찾지 못하면 분류 `js-module`의 미해결 엣지를 남겨야 한다. | 사용자 요청 |
| F-010-R5 | 시스템은 호출 식, `new`, JSX 요소, 인자로 넘긴 함수 참조의 대상을 스코프와 import로 해석해 `CALLS`(함수→함수)로 기록해야 한다. 대상이 프로젝트 밖이거나 무엇인지 알 수 없으면 기록하지 않고, 프로젝트 모듈에서 import한 이름을 불렀는데 대상 함수를 찾지 못한 경우에만 분류 `js-call`의 미해결 엣지를 남겨야 한다. | 사용자 요청, AGENTS.md |
| F-010-R6 | 시스템은 `fetch`, axios, jQuery AJAX, `XMLHttpRequest`, `sendBeacon`, `EventSource`, 화면 이동(`location`, `window.open`), 폼 `action` 대입을 `HTTP_REQUEST` 노드(`httpMethod`, `path`, `url`, `dynamic`, `api`)와 `REQUESTS` 엣지(가장 가까운 이름 있는 함수 또는 모듈 → 요청)로 기록해야 한다. HTTP 메서드는 명시한 값, 없으면 API의 기본값, 정적으로 알 수 없으면 `ANY`다. | 사용자 요청, design 3.3 |
| F-010-R7 | 시스템은 URL 식을 평가해 URL 패턴(`attrs.path`, 노드 ID의 URL)을 만들어야 한다: 문자열·템플릿 리터럴, `+` 연결, 상수 따라가기(같은 스코프·모듈, import한 모듈, 객체 상수의 프로퍼티), 알 수 없는 부분은 `{*}`. 쿼리스트링은 떼어 `attrs.query`에, 외부 URL은 `external=true`, 상대 URL은 `relative=true`로 표시한다. 만약 URL 식에 정적인 경로 조각이 하나도 없으면 `HTTP_REQUEST`를 만들지 않고 분류 `http-url`의 미해결 `REQUESTS` 엣지를 남겨야 한다. | 사용자 요청, AGENTS.md |
| F-010-R8 | 시스템은 `axios.create({ baseURL })`로 만든 인스턴스를 통한 요청의 URL 앞에 `baseURL`을 붙여야 한다. 인스턴스가 다른 모듈에서 export·import되어도 같다. | 사용자 요청 |
| F-010-R9 | `js` 내장 소스가 등록된 경우, 시스템은 그 주인 모듈(`js:module:<JSP 경로>`) 아래에 함수·호출·요청을 만들고 위치를 호스트 파일 기준으로 바꿔야 한다. 같은 주인의 조각들은 하나의 전역 스코프로 해석하고, URL 맨 앞의 `__JSP_EXPR__`는 컨텍스트 경로로 보아 떼고 그 밖의 자리에서는 `{*}`로 바꾼다. | design 4.1, F-011-R11 |
| F-010-R10 | 시스템은 모듈 문법을 쓰지 않는 스크립트(레거시 전역 스크립트)의 전역 이름을 해석해야 한다. 인라인 스크립트는 주인 JSP가 `INCLUDES`로 포함한 모듈들의 최상위 선언을 전역으로 본다. 같은 이름이 여러 모듈에 있으면 `INFERRED` + `attrs.ambiguous=true`로 기록한다. | 사용자 요청 |
| F-010-R11 | 시스템은 URL을 매개변수로 받아 요청에 쓰는 함수(요청 래퍼)의 호출 지점에서, 인자로 준 URL로 `HTTP_REQUEST`를 만들어야 한다(`attrs.via`에 래퍼 함수 ID). 경로 변수 하나만 받는 함수(`fetchOrder(id)`)의 호출 지점에는 만들지 않는다. | 제안 (레거시 공통 AJAX 래퍼), 검토 필요 |
| F-010-R12 | 만약 파일이 압축(minify)된 스크립트면, 시스템은 `JS_MODULE` 노드만 만들고 내용 분석을 건너뛰며 `INFO` 진단을 남겨야 한다. | 제안 (벤더 라이브러리 잡음 방지), 검토 필요 |
| F-010-R13 | 시스템은 함수를 실행하는 식과 함수를 넘기기만 하는 식을 구분해 기록해야 한다. `f.call(x)`·`f.apply(x)`는 `callType=call`, `f.bind(x)`와 인자·옵션 객체 값(단축 프로퍼티 `{ f }` 포함)·JSX 속성으로 넘긴 함수는 `callType=ref`다. `typeof f`, `f.name`·`f.length` 읽기, `export { f }`, 변수·객체·배열에 담기만 한 것은 `CALLS`로 기록하지 않는다. | 감사 E2, design 3.4 |
| F-010-R14 | `js` 내장 소스에서 만든 `HTTP_REQUEST`의 `url`과 미해결 `http-url`의 원문에는 내부 자리표시자(`__JSP_EXPR__`)나 치환된 값이 아니라 호스트 파일에 쓴 원문을 넣어야 한다. 내장 소스에 치환 목록(`attrs.jspSubstitutions`, F-011-R14)이 있으면 그것으로 되살리고, 없거나 읽을 수 없으면 `__JSP_EXPR__`를 `${…}`로 바꾼다. `path`(매칭용 정규화 값)는 바꾸지 않는다. | 감사 U4, design 3.5 |
| F-010-R15 | 컴포넌트 함수가 props로 받은 함수를 호출하는 지점에서, 시스템은 부모 컴포넌트가 JSX 속성으로 넘긴 프로젝트 함수로 `CALLS`(`INFERRED`, `callType=call`, `viaProp=<prop 이름>`)를 만들어야 한다. 그 컴포넌트를 쓰는 부모가 여럿이면 각각 만든다. 넘긴 값이 인라인 함수이거나 프로젝트 함수가 아니면 만들지 않는다. | 감사 (React 콜백 흐름 누락) |
| F-010-R16 | 만약 매개변수나 props로 받은 함수의 호출을 대상을 정하지 못해 기록하지 않았으면, 시스템은 그 건수를 모듈마다 `CALLS_SKIPPED`(INFO) 진단 1건으로 남겨야 한다. 프로젝트 밖 전역·패키지 호출과 이름 없는 콜백의 매개변수 호출은 세지 않는다. | 감사 (조용한 누락), AGENTS.md |
| F-010-R17 | 옵션 `web.skipVendor`가 `false`가 아닌 동안, 만약 파일이 벤더(서드파티) 스크립트면(아래 "벤더 판정"), 시스템은 `JS_MODULE` 노드(`skipped=vendor`)만 만들고 내용 분석을 건너뛰며 건너뛴 파일 수를 벤더 폴더마다 `FILE_SKIPPED`(INFO) 진단 1건으로 남겨야 한다. `web.vendorPatterns`(쉼표로 구분한 glob)에 맞는 파일도 벤더로 본다. `web.skipVendor=false`면 벤더 스크립트도 분석하되 `vendor=true`로 표시한다. | 공개 프로젝트 검증 P2 (분석 시간의 95%가 벤더 파일) |
| F-010-R18 | 시스템은 이 스크립트에 없는 전역 이름을 **같은 페이지가 싣는 스크립트**에서만 찾아야 한다. 인라인 스크립트는 주인 페이지가 싣는 모듈에서 먼저 찾고(F-010-R10), 거기 없거나 JS 파일끼리면 두 스크립트를 함께 싣는 페이지가 하나 이상 있을 때만 `INFERRED`(`resolution=global`, `viaPage`=그 페이지)로 잇는다. 함께 싣는 페이지를 모르면 잇지 않는다. 표준 내장·DOM·잘 알려진 라이브러리 전역 이름과 그 멤버 호출, 벤더 스크립트는 출처도 대상도 아니다. | 공개 프로젝트 검증 P1 (전역 이름 오연결 2,806건), AGENTS.md |
| F-010-R19 | 시스템은 실제 코드에서 흔한 다음 형태를 구문 오류로 처리하지 않아야 한다: 식별자로 쓴 `using`, 스크립트 안의 줄 맨 앞 HTML 주석 열기 `<!--`와 닫기 `-->`, JSX 텍스트·JSX 문자열 속성 안의 홑 `&`. 위치와 이름·문자열 값은 원문 그대로여야 한다. | 공개 프로젝트 검증 B14 |
| F-010-R20 | 시스템은 요청 옵션 객체가 변수여도 HTTP 메서드를 읽어야 한다: 객체 상수, 여러 번 대입한 변수(대입한 객체마다 후보), 요청 래퍼가 옵션 객체를 매개변수로 받아 넘기는 경우(호출 지점의 인자에서 읽는다), `$.ajax`의 옵션 변수(나중에 붙인 `opts.url`·`opts.type` 포함). 후보의 메서드가 서로 다르면 메서드마다 요청을 만들고 `REQUESTS`를 `INFERRED`로 둔다. | 공개 프로젝트 검증 B15(a) |
| F-010-R21 | 시스템은 URL 식이 객체의 프로퍼티일 때(`modeInfo.editURL`) 그 객체가 여러 값이 대입되는 변수이거나 React 상태(`const [v, setV] = useState({…})`, `setV({…})`)면, 대입한 객체마다 그 프로퍼티 값을 후보로 보고 후보마다 요청을 `INFERRED`로 만들어야 한다. | 공개 프로젝트 검증 B15(b) |
| F-010-R22 | 시스템은 한 호출 지점에서 나온 URL 후보를 정리해야 한다: 다른 후보의 "미지 접두 버전"(`/api/orders`가 있을 때의 `{*}/orders`)은 버리고, 자리만 잡은 빈 문자열 후보(`let url = ""`, `id \|\| ""`)는 다른 후보가 있으면 버린다. | 공개 프로젝트 검증 B15(c) |
| F-010-R23 | 요청 API가 아닌 호출·`new`의 인자가 옵션 객체(리터럴이나 객체 상수)이고 그 안에 서버 경로로 보이는 URL 프로퍼티가 있으면(`url`, `ajax`, `ajax.url`, `async.url`, `content: 'url:…'`), 시스템은 `api=option`인 `HTTP_REQUEST`와 `REQUESTS`를 `INFERRED`로 만들고 근거(`optionKey`, `callee`)를 남겨야 한다. 경로로 보이지 않는 값은 요청도 미해결도 만들지 않는다. | 공개 프로젝트 검증 B15(d) |
| F-010-R24 | 시스템은 폼 `action`을 바꾸는 지점의 HTTP 메서드를, 같은 함수에서 같은 폼에 대입한 `.method` 값, 없으면 그 스크립트를 싣는 페이지의 폼 표(`JSP_PAGE`의 `attrs.formMethods`, F-011-R16)에서 그 이름·ID의 폼 메서드로 정해야 한다. 폼을 알 수 없거나 페이지마다 다르면 `ANY`다. | 공개 프로젝트 검증 B15(e) |
| F-010-R25 | 내장 소스의 함수 이름에 JSP 식이 섞여 있으면(`function ${prefix}Open(){}`), 시스템은 노드 ID와 이름의 그 자리를 `{*}`로 쓰고 `dynamicName=true`를 붙여야 한다. 내부 자리표시자(`__JSP_EXPR__`)는 그래프에 남기지 않는다. | 공개 프로젝트 검증 (동적 함수 이름), design 3.9 |
| F-010-R26 | 시스템은 JS·TS 파일 하나, 내장 소스 하나를 읽기 시작할 때 그 파일(내장 소스는 주인 페이지)의 상대 경로를 `AnalysisContext.progress`로 알려야 한다(메모리 부족 안내에 실린다). | F-001 (메모리 부족 안내) |
| F-010-R27 | 한 호출 지점에서 메서드 후보와 URL 후보가 둘 다 여럿일 때, 시스템은 조합을 곱해 요청을 만들지 않아야 한다. 메서드와 URL이 같은 객체 변수(여러 번 대입한 변수, React 상태)의 프로퍼티면 그 변수에 대입된 객체 리터럴마다 짝지어 요청을 만들고(리터럴 하나 = 요청 하나), 짝을 알 수 없으면 URL 후보마다 메서드 `ANY` 하나로 둔다(메서드 후보가 하나뿐이면 그 메서드). 값을 쓰는 자리가 `if`/`else`의 한 가지 안이면 다른 가지의 대입은 후보에서 빼고, 조건이 `x === '리터럴'`이면 `x`의 후보를 그 가지에 맞게 좁힌다. F-010-R20의 "메서드마다 요청"은 URL 후보가 하나일 때만이다. | 재검증 N1 (가짜 요청 34건, 틀린 추론 MATCHES 6건) |
| F-010-R28 | 시스템은 `window.showModalDialog(url, …)`·`showModelessDialog`(접두 `window.` 없이도)의 첫 인자를 화면 이동 요청(`api=navigation`, `GET`)으로 기록해야 한다. 다만 같은 페이지가 싣는 스크립트(또는 그 스크립트 자신)에 같은 이름의 프로젝트 선언이 있으면 그 함수의 호출로 해석한다(요청은 요청 래퍼 전파가 만든다). | 재검증 N2 (요청이 있는 호출 지점 66 → 41) |
| F-010-R29 | 시스템은 CR 단독(뒤에 LF가 없는 `\r`)을 줄 끝으로 다뤄야 한다: 파서에는 LF로 보이게 하고(길이 보존), 줄 번호·열과 압축 파일 판정의 줄 수도 그렇게 센다. | 재검증 N6 (CR뿐인 파일이 한 줄로 읽혀 `PARSE_FAILED`, 모든 위치가 1행) |
| F-010-R30 | 클라이언트 라우터 패키지(`react-router…`, `vue-router`, `next/…`)를 import한 모듈에서, 시스템은 라우터 이동(`navigate(…)`, `history.push·replace(…)`, `router.push·replace(…)`)과 라우터 컴포넌트의 경로 속성(`Link`·`NavLink`·`Navigate`·`Redirect`의 `to`, `next/link`의 `href`)을 `api=route`, `clientRoute=true`인 `HTTP_REQUEST`로 기록해야 한다. `location` 이동(`api=navigation`)의 경로가 프로젝트가 선언한 클라이언트 라우트(`<Route path>`)와 같으면 그 요청에도 `clientRoute=true`를 붙인다. 라우터를 import하지 않은 모듈의 `navigate(…)`는 요청이 아니다. | 재검증 N9-a (`GET /login` 미해결 8건) |
| F-010-R31 | JS 파일(내장 소스가 아닌 것)의 문자열 리터럴에 JSP 태그·식이 그대로 적혀 있으면, 시스템은 URL을 평가할 때 jsp의 인라인 치환과 같은 규칙으로 읽어야 한다: `<c:url value='X'/>`는 `X`, `${…}`와 `<%=…%>`는 맨 앞이면 컨텍스트 경로, 그 밖에서는 `{*}`. `url`은 원문 그대로다. | 재검증 N9-b (요청 ID에 `<c:url …/>` 원문) |
| F-010-R32 | `api=option` 요청의 옵션에 메서드가 없으면, 시스템은 알려진 플러그인의 기본 메서드를 써야 한다(아래 "플러그인의 기본 메서드"). 모르는 플러그인은 `ANY`다. 옵션에 메서드 프로퍼티가 있는데 값을 모르면 `ANY`다. | 재검증 (zheng `api=option` 18건이 `ANY`) |
| F-010-R33 | 시스템은 레이아웃 프레임워크가 함께 싣는 스크립트의 전역을 해석해야 한다. SiteMesh(페이지 → 데코레이터 `INCLUDES`, `via=sitemesh`): 데코레이터가 싣는 스크립트의 전역을 내용 페이지의 스크립트가 쓰면 `INFERRED` + `resolution=global`로 잇는다. Tiles(설정 파일 `FILE` 노드의 `tilesDefinitions`·`tilesAttributes`): 정의마다 template이 싣는 스크립트와 그 정의의 조각 JSP가 싣는 스크립트를 한 묶음으로 보고(`viaPage`는 template), 와일드카드 정의는 조각 경로 패턴에 맞는 JSP마다 묶음을 만든다. `via=tiles` 엣지는 따라가지 않는다(서로 다른 화면의 본문끼리 함께 실리는 것으로 보이지 않게). | 재검증 (jeesite `jeesite.js` 전역 60여 건 누락), F-011-R21·R22 |
| F-010-R34 | F-010-R18의 근거(같은 페이지가 싣는 스크립트)로 찾지 못한 전역 호출(`f()`, `new F()`, `App.x()`)에 대해, 프로젝트(벤더·건너뛴 스크립트 제외)에서 그 이름으로 함수에 닿는 최상위 정의가 정확히 하나이고 그것이 JS 파일에 있으면, 시스템은 `INFERRED` `CALLS`(`loadUnverified=true`, `resolution`·`viaPage` 없음)로 이어야 한다. 정의가 여럿이면 잇지 않고 그 건수를 `CALLS_SKIPPED`에 더한다. 내장·라이브러리 전역 이름, ES 모듈의 호출, 함수 참조(`ref`)에는 적용하지 않는다. | 재검증 N13(e) (맞는 전역 호출이 조용히 빠짐: zheng 61, jeesite 약 80) |
| F-010-R35 | 페이지가 `INCLUDES`로 가리킨 스크립트가 내용을 분석하지 않은 압축본(`x.min.js`, `x-min.js`)이고 같은 폴더에 분석한 `x.js`가 있으면, 시스템은 그 페이지가 `x.js`를 싣는 것으로 보고 `INCLUDES`(JSP_PAGE→JS_MODULE, `INFERRED`, `via=script`, `minOf`=압축본 경로)를 더해야 한다. | 재검증 N13(b) |
| F-010-R36 | 시스템은 압축 여부를 이름이 아니라 내용으로 판정해야 한다(이름에 `.min`이 없어도 내용이 압축본이면 `skipped=minified`, `.min.js`라도 내용이 압축돼 있지 않으면 분석). 벤더 판정에 다음을 더한다: `plugins/<이름>/…` 아래, 이름에 `.debug.`·`-debug`·`.pack.`·`.bundle.`이 든 파일, 라이선스 문구와 `Author`·`Version` 표기가 함께 있는 머리 주석, 20만 자를 넘고 `function`이 200번 넘게 나오는 파일. | 재검증 N26 (`oryx.debug.js` 737KB가 분석됨) |
| F-010-R37 | 시스템은 다음도 요청으로 읽어야 한다: `location` 자체에 대입하는 화면 이동(`location = url`, `top.location = url` 등. `api=navigation`), 인자로 준 문자열이 `iframe:`·`url:`(jBox는 `get:`·`post:`도)로 시작하고 그 뒤가 서버 경로인 호출(`api=option`, `optionKey=arg`), layer의 `open({ type: 2, content: url })`(`api=option`, `optionKey=content`). 지금 주소를 다시 여는 대입(`location = location.href`)은 요청도 미해결도 아니다. | 재검증 N19 |
| F-010-R38 | 시스템은 URL을 굳힐 때 가짜 경로를 만들지 않아야 한다: `?` 없이 `&`로 이은 조각과 값을 모르는 접두 뒤의 `name=` 조각은 쿼리로 가르고(경로가 `{*}`뿐이면 기존 규칙대로 미해결), 조각 경계에서 겹친 `/`는 하나로 합치며(리터럴 하나가 `//`로 시작할 때만 외부 URL), 맨 앞의 컨텍스트 경로 조각은 하나만 떼고, 경로의 `;jsessionid=…`는 뗀다. | 재검증 N21 |
| F-010-R39 | 한 호출 지점의 URL 후보가 여럿이면, 시스템은 URL마다 요청 노드 하나를 만들고 그 URL에 짝지어진 메서드가 하나면 그 메서드, 여럿이면 메서드를 `ANY`로 두되 **후보 메서드를 `attrs.httpMethods`(List)로 남겨야 한다**(`httpMethod`와 노드 ID의 METHOD는 `ANY`). 후보 가운데 값을 모르는 메서드가 있으면 `httpMethods`를 붙이지 않는다. 메서드 식이 객체 변수의 프로퍼티이고 URL 식이 같은 객체의 프로퍼티에서 **파생된 식**(상수, 삼항, 템플릿, `+` 연결을 따라가 그 프로퍼티를 읽는 식)이면, 그 변수에 대입된 객체 리터럴마다 URL 식을 평가해 그 리터럴의 메서드와 짝지어야 한다. | 4차 재검증 M1 (`ANY /api/schedule/{*}`가 DELETE·GET·PUT 엔드포인트 셋에 이어짐) |
| F-010-R40 | 시스템은 머리 주석만으로 하는 벤더 판정을 좁혀야 한다: 머리 주석에 알려진 오픈소스 라이선스의 정식 문구, `@license` 태그, 라이선스 주소, 알려진 프로젝트 URL(버전 번호와 함께, "All rights reserved" 없이) 가운데 하나가 있을 때만 벤더 후보로 보고, 후보가 (1) 그 폴더에서 혼자만 걸렸고 파일 이름이 라이브러리 배포판의 꼴이 아니거나 (2) 프로젝트의 다른 스크립트가 그 파일의 최상위 함수를 5개 이상 부르면 벤더로 보지 않는다. `FILE_SKIPPED` 요약 진단에는 판정 근거(경로·옵션·이름·라이선스·생성물)를 붙여야 한다. | 4차 재검증 M10 (사내 스크립트가 `skipped=vendor`, 한 폴더 9개 가운데 하나만 벤더) |
| F-010-R41 | 시스템은 폼 필드의 값을 읽는 식(`document.<폼>.<필드>.value`, `<폼 변수>.<필드>.value`, `document.getElementById('<id>').value`, `$('#<id>').val()`)을 URL로 평가할 때, 그 스크립트를 싣는 페이지의 필드 표(`JSP_PAGE`의 `attrs.formFields`, F-011-R29)에서 그 필드에 든 URL을 읽어야 한다. 주인 페이지의 표에서 폼까지 맞춰 찾은 것이 아니면 `REQUESTS`는 `INFERRED`다. | 4차 재검증 M13(b) (hidden input의 팝업 URL 9건이 미해결) |
| F-010-R42 | 시스템은 JSX의 HTML 요소 `<a href>`·`<area href>`, `<form action>`, `<img src>`, `<iframe src>`·`<frame src>`의 값이 서버 경로 꼴이면 `HTTP_REQUEST`(`api=link`/`form`/`img`/`frame`, 메서드 `GET`. 폼은 `method` 속성)와 그 요소를 그리는 함수에서 나가는 `REQUESTS`로 기록해야 한다. 경로가 선언된 클라이언트 라우트와 같으면 `clientRoute=true`를 붙인다. 서버 경로 꼴이 아닌 값은 요청도 미해결도 만들지 않는다. | 4차 재검증 M13(c) |
| F-010-R43 | 시스템은 루트 `/`로 가는 화면 이동(`location.href = "/"`, `window.open("/")` 등 `api=navigation`)을 미해결이 아니라 `GET /` 요청으로 기록해야 한다. 라우트 선언에 `/`가 있으면 `clientRoute=true`다(F-010-R30의 규칙 그대로). | 4차 재검증 M13(d) (9건이 미해결 `http-url`) |
| F-010-R44 | 내장 소스의 문자열 리터럴(템플릿 리터럴의 조각 포함)에서 `__JSP_EXPR__` 자리의 원문(치환 목록, F-011-R14)이 `${조건 ? '리터럴A' : '리터럴B'}` 꼴이면, 시스템은 그 자리를 `{*}`로 두지 않고 A와 B를 넣은 후보 URL로 펼치고 `REQUESTS`를 `INFERRED`로 두어야 한다(F-011-R25와 같은 규칙). 다만 쿼리 값 자리의 삼항(그 문자열에서 앞에 `?`나 `&`가 있다)은 펼치지 않고 `{*}`로 둔다(5차 개정. F-011-R31과 같은 규칙). | 4차 재검증 M13(e) (N20 잔여), 5차 재검증 (jeesite `ckfinder.tag:16`의 `dts=${type eq 'thumb'?'1':'0'}`가 `dts=1`로만 남고 추론이 됨) |
| F-010-R45 | 시스템은 스크립트가 DOM에 넣는 HTML 문자열(`x.html(H)`, `x.append(H)`, `el.innerHTML = H`·`+=`, `document.write(H)`·`writeln`, 요소를 만드는 `$(H)`·`jQuery(H)`, `insertAdjacentHTML(pos, H)`) 안의 `<iframe src>`·`<frame src>`, `<a href>`·`<area href>`, `<form action>`, `<img src>`를 `HTTP_REQUEST`(`api=frame`/`link`/`form`/`img`, 메서드 `GET`. 폼은 `method` 속성)로 기록해야 한다. 문자열은 URL 식 평가(리터럴, `+` 연결, 템플릿 리터럴, 상수 따라가기, JSP 치환)로 읽고, 값이 서버 경로 꼴일 때만 요청을 만든다. 만약 속성 값에 정적인 조각이 하나도 없으면 요청을 만들지 않고 미해결(`http-url`)로 남긴다(`<img>`는 남기지 않는다). | 5차 재검증 (전자정부 템플릿의 팝업 11곳이 `.html('<iframe … src="' + "<c:url …/>" + '">')`로 열리는데 요청도 미해결도 없음) |

### 세부 규칙

**함수 이름(JS_FUNCTION의 이름 경로)** — 바깥에서 안으로 `.`으로 잇는다.

| 형태 | 이름 경로 | kind |
|---|---|---|
| `function f(){}` | `f` | `function` |
| `const f = function(){}` / `const f = () => {}` / `useCallback(() => {})`·`memo(...)`·`forwardRef(...)`로 감싼 것 | `f` | `function` / `arrow` |
| 함수 안의 함수 `function outer(){ function inner(){} }` | `outer.inner` | |
| `class A { m(){} }`, 클래스 필드 `h = () => {}` | 클래스 `A`(`kind=class`), 메서드 `A.m`, `A.h` | `class`, `method` |
| `var G = { render: function(){}, refresh(){} }` | `G.render`, `G.refresh` (객체 자체는 노드가 아니다) | `method` |
| IIFE 모듈 `var M = (function(){ function a(){} return { a: a, b: function(){} }; })()` | `M.a`, `M.b` | |
| `App.init = function(){}`, `Foo.prototype.bar = function(){}` | `App.init`, `Foo.bar` | `method` |
| `module.exports = { foo(){} }`, `exports.foo = function(){}` | `foo` | |
| `export default function(){}` / `export default () => {}` | `default` | |
| 이름 없는 콜백 | 노드를 만들지 않는다. 안의 호출·요청은 가장 가까운 이름 있는 바깥 함수(없으면 모듈)에 귀속한다 | |

- 이름 없는 함수(콜백, IIFE) 안에서 선언된 이름 있는 함수는 그 이름 그대로 바깥 이름 경로 아래에 둔다. 같은 ID가 이미 있으면 `<anonymous>@줄:열.이름`으로 구분한다.
- `component=true`: 이름이 대문자로 시작하고 본문에 JSX가 있는 함수, 또는 `React.Component`·`Component`·`React.PureComponent`·`PureComponent`를 상속하고 `render`가 있는 클래스.
- `exported=true`: `export` 선언, `export { f }`, `export default f`, `module.exports`·`exports`로 내보낸 함수.

**CALLS의 `attrs.callType`** — `call`(호출 식), `constructor`(`new`), `jsx`(JSX 요소), `ref`(실행하지 않고 넘긴 함수 참조: 인자·JSX 속성·옵션 객체 값, `f.bind(x)`. 예: `onClick={handleSave}`, `error: showError`, `$.ajax({ success })`, `this.handleSave.bind(this)`).

**호출과 참조의 구분(F-010-R13)**

| 형태 | 기록 |
|---|---|
| `f()`, `f.call(x)`, `f.apply(x, args)`, `onClick={f()}` | `callType=call` |
| `onClick={() => f()}`, `list.forEach(function () { f(); })` | `callType=call`. 이름 없는 함수 안의 호출이므로 감싼 이름 있는 함수(없으면 모듈)에서 나간다 |
| `f.bind(x)` (`this.m = this.m.bind(this)`, `onClick={this.m.bind(this, id)}` 포함) | `callType=ref`. 자기 자신을 bind한 것(`self.bind`)은 기록하지 않는다 |
| `g(f)`, `el.addEventListener('click', f)`, `setTimeout(f)`, `p.then(f)`, `list.map(f)`, `new Promise(f)`, `onClick={f}`, `{ error: f }`·`{ f }`(인자로 준 옵션 객체) | `callType=ref` |
| `f.bind(x)()` | `ref`와 `call` 둘 다(같은 줄) |
| `typeof f`, `f.name`, `f.length`, `export { f }`, `const g = f`, `const o = { f }`, `[f]` | 기록하지 않는다 |

**props로 받은 함수의 호출(F-010-R15)** — 자식 컴포넌트 함수의 호출 지점이 다음 가운데 하나일 때만 본다: 첫 매개변수를 구조 분해해 꺼낸 이름(`function Child({ onSelect })`, `{ onSelect: pick }`의 `pick`), 첫 매개변수의 프로퍼티(`props.onSelect()`), 본문에서 그 매개변수를 구조 분해해 꺼낸 이름(`const { onSelect } = props`). `x.call(…)`·`x.apply(…)`로 부른 것도 같다. 부모 쪽은 JSX 요소가 그 컴포넌트로 풀리고(`callType=jsx`와 같은 해석) 속성 값이 프로젝트 함수의 참조(`onSelect={handleSelect}`, `onSelect={this.handleSelect}`, `onSelect={this.handleSelect.bind(this)}`)일 때다. 엣지는 호출 지점을 감싼 이름 있는 함수(자식)에서 부모가 넘긴 함수로 가고 위치는 자식의 호출 지점이다. 부모가 인라인 함수(`onSelect={(id) => handleSelect(id)}`)를 넘겼으면 그 본문의 호출은 부모 함수에서 나가는 `CALLS`로 이미 있으므로 자식에서 잇는 엣지는 만들지 않는다.

**기록하지 않은 호출의 집계(F-010-R16)** — 이름 있는 함수의 매개변수(구조 분해해 꺼낸 이름 포함)나 컴포넌트의 props 프로퍼티(`props.x`, 클래스의 `this.props.x`)가 가리키는 함수를 쓰는 지점 가운데 엣지를 만들지 못한 것을 센다.

- 직접 호출: `callback(res)`, `onSelect(id)`, `props.onSave()`, `callback.call(this, res)`.
- 다른 호출에 넘김: `success: callback`, `$.getJSON(url, data, callback)`, `onClick={onBack}`, `callback.bind(this)`. 그 매개변수가 함수를 받는다는 근거가 있을 때만 센다: 같은 매개변수를 직접 호출한 곳이 있거나, 그 함수를 호출하는 프로젝트 코드가 그 자리에 함수(리터럴이나 프로젝트 함수 참조)를 넘기거나, JSX 부모가 그 prop에 함수를 넘긴 경우다. 근거가 없으면(`url`, `data` 같은 값 매개변수) 세지 않는다.
- 세지 않는 것: 프로젝트 밖 전역·패키지 호출(`console.log`, `$`, `axios`), 이름 없는 콜백의 매개변수(`function (res) { res.done(); }`. 값을 주는 쪽이 라이브러리다), 받은 값의 메서드 호출(`param.go()`, `e.preventDefault()`, `props.label.trim()`. DOM·데이터 객체의 메서드와 구분할 수 없다), 배열 구조 분해로 받은 이름(`const [v, setV] = useState()`의 `setV`).
- 진단은 생략이 1건 이상인 모듈(파일, 인라인 스크립트는 그 JSP)마다 1건이고 위치는 그 파일이다. 메시지: `수신자를 알 수 없는 호출 n건을 기록하지 않았다(매개변수·props로 받은 함수: 직접 호출 a건, 다른 호출에 넘김 b건; 줄 …): <경로>`. 줄 번호는 10개까지 적는다.

**내장 소스의 원문 되살리기(F-010-R14)** — `url`과 미해결 원문은 URL 식이 놓인 구간의 원문이다(공백을 하나로 줄이고 120자까지). 식이 `js` 내장 소스에 있으면 그 조각의 `attrs.jspSubstitutions`(형식은 F-011 spec의 "치환 목록")에서 식의 구간에 걸친 치환을 원문으로 되돌린다: `'__JSP_EXPR__/order/history.do'` → `'${ctx}/order/history.do'`, `'/order/close.do'` → `'<c:url value="/order/close.do"/>'`. 닫는 따옴표 뒤의 채움 공백은 사라진다. 식이 요청 지점과 다른 조각에 있어도(옵션 객체 상수) 식이 놓인 조각의 치환 목록을 쓴다. 치환 목록이 없거나 형식이 맞지 않는 조각에서는 `__JSP_EXPR__`만 `${…}`로 바꾼다. JS·TS 파일의 원문은 손대지 않는다.

**해석하는 수신자** — `f()`(같은 스코프·모듈의 함수, import한 이름), `obj.m()`(같은 모듈·import한 모듈의 객체 리터럴·클래스·네임스페이스·IIFE 모듈의 멤버), `this.m()`(같은 클래스·객체, `var self = this` 포함), `new C()`로 만든 변수의 메서드, 생성자 함수의 `prototype` 메서드, 함수가 하나의 `return`으로 돌려주는 객체의 멤버(`const { reload } = useOrders()`), `f.call`·`f.apply`(호출), `f.bind`(참조), `React.lazy(() => import('./X'))`. `memo(X)`·`connect(a)(X)` 같은 HOC는 인자 가운데 컴포넌트가 하나뿐이면 그것으로 보고 `INFERRED`로 기록한다.

**미해결 `js-call`의 원문** — `<모듈 경로>#<내보낸 이름>`. 모듈 파일을 못 찾았으면 `<풀어 본 경로>#<이름>`. 내보낸 이름은 있지만 함수인지 알 수 없는 값(예: `export const useStore = create(...)`)을 직접 부른 경우도 여기에 든다.

**요청 래퍼(F-010-R11)** — 요청 지점의 URL 식에 그 요청을 감싼 이름 있는 함수의 매개변수가 들어 있으면 그 함수를 래퍼 후보로 본다. 래퍼 호출 지점에서 인자를 평가해 끼워 넣되, (1) 매개변수가 URL의 맨 앞에 오거나(`url: url`, `ctx + url`) (2) 인자의 정적 조각에 `/`가 있을 때(`send('GET', '/orders/' + id)`)만 요청을 만든다. 메서드가 매개변수면 인자에서 읽는다. 래퍼를 감싼 래퍼는 3단계까지 따라간다. 래퍼 안의 요청 지점은 원래 규칙대로 남는다(전부 동적이면 미해결).

**HTTP API 표**

| 형태 | api | 기본 메서드 |
|---|---|---|
| `fetch(url, { method })` | `fetch` | `GET` |
| `axios.get\|post\|put\|delete\|patch\|head\|options(url)`, `axios(url, cfg)`, `axios(cfg)`, `axios.request(cfg)`, 인스턴스도 같다 | `axios` | 메서드 이름 / `GET` |
| `$.ajax({ url, type\|method })`, `$.ajax(url, {...})` | `jquery` | `GET` |
| `$.get`, `$.getJSON`, `$.getScript` / `$.post` | `jquery` | `GET` / `POST` |
| `$(sel).load(url[, data])` | `jquery` | `GET` (data가 객체면 `POST`) |
| `xhr.open(method, url)` | `xhr` | 첫 인자 |
| `navigator.sendBeacon(url)` | `beacon` | `POST` |
| `new EventSource(url)` | `eventsource` | `GET` |
| `location.href = url`, `location = url`(`window.`·`top.`·`parent.`·`self.`·`document.location`에 대입하는 것도), `location.assign\|replace(url)`, `window.open(url)`, `window.showModalDialog(url, …)`·`showModelessDialog`(F-010-R28) | `navigation` | `GET` |
| 라우터를 import한 모듈의 `navigate(url)`, `history.push\|replace(url)`, `router.push\|replace(url)`, `<Link to>`·`<NavLink to>`·`<Navigate to>`·`<Redirect to>`, `next/link`의 `<Link href>`(F-010-R30) | `route` (`clientRoute=true`) | `GET` |
| `form.action = url`, `$(form).attr('action', url)` | `form` | 같은 함수의 `.method` 대입 → 페이지의 폼 표 → `ANY` (F-010-R24) |
| JSX의 HTML 요소: `<a href>`·`<area href>` / `<form action>` / `<img src>` / `<iframe src>`·`<frame src>` (F-010-R42) | `link` / `form` / `img` / `frame` | `GET` (폼은 `method` 속성 값, 값을 모르면 `ANY`) |
| 요청 API가 아닌 호출의 옵션 객체: `{ url }`, `{ ajax: '/x' }`, `{ ajax: { url } }`, `{ async: { url } }`, `{ content: 'url:/x' }`, layer의 `{ type: 2, content: '/x' }` | `option` (항상 `INFERRED`) | 같은 객체의 `method`·`type`·`mtype` / 알려진 플러그인의 기본값(F-010-R32) / `ANY` (`content`는 `GET`) |
| 요청 API가 아닌 호출의 문자열 인자가 `iframe:`·`url:`로 시작(jBox는 `get:`·`post:`도): `$.jBox("iframe:/x")`, `top.$.jBox.open("iframe:/x", …)` (F-010-R37) | `option` (`optionKey=arg`, 항상 `INFERRED`) | `GET` (`post:`는 `POST`) |

**벤더 판정(F-010-R17)** — jsp(F-011-R15)와 같은 규칙이다. 두 모듈이 의존을 공유하지 않아 각자 구현한다(`VendorRules`, jsp의 `VendorPaths`). 한쪽을 고치면 다른 쪽 문서와 구현도 고친다.

1. **경로 규칙**(파일을 읽지 않는다). 디렉터리 세그먼트를 앞에서부터 대소문자 구분 없이 보아, 아래에 처음 맞는 세그먼트까지가 "벤더 폴더"다.
   - `node_modules`, `bower_components`, `webjars`
   - `vendor`, `vendors`: 경로의 첫 세그먼트이거나, `src` 바로 아래이거나, 정적 자원 폴더 아래일 때. 업무 도메인 이름(거래처)으로 쓴 폴더(`src/pages/vendor`, `WEB-INF/views/vendor`)와 구분하기 위해서다
   - 정적 자원 폴더 아래의 `lib`, `libs`, `plugins`, `plugin`, `bootstrap`, `layer`, `angular`
   - `plugins/<이름>/…`: `plugins` 아래에 폴더가 하나 더 있는 배치(플러그인마다 폴더). 정적 자원 폴더 밖(모듈의 `src/plugins/checkbix/js/…`)이어도 벤더다. 화면 파일 영역(`WEB-INF`, `templates`) 안쪽의 `plugins/`와 `plugins` 바로 아래의 파일(`src/plugins/own.js`)은 아니다(F-010-R36)
   - "정적 자원 폴더 아래"는 조상에 `webapp`, `WebContent`, `WebRoot`, `static`, `public`, `assets`, `js`, `javascript`, `scripts`, `script` 가운데 하나가 있고 그 뒤에 `WEB-INF`가 끼어 있지 않다는 뜻이다(`WEB-INF` 안쪽은 화면 파일이다. `src/main/resources/templates`도 정적 자원 폴더가 아니다)
   - 잘 알려진 라이브러리 이름과 같거나 그 뒤에 버전이 붙은 이름(`zTree_v3`, `webuploader-0.1.5`): `ckeditor`, `ckfinder`, `tinymce`, `easyui`, `ztree`, `echarts`, `swfobject`, `webuploader`, `codemirror`, `ueditor`, `umeditor`, `kindeditor`, `xheditor`, `summernote`, `layui`, `laydate`, `My97DatePicker`, `select2`, `jqGrid`, `datatables`, `fullcalendar`, `pdfjs`, `angularjs`, `scriptaculous`, `mootools`, `extjs`, `highcharts`, `jqplot`, `jstree`, `treeTable`, `SuperSlide`, `supersized`, `mCustomScrollbar`, `malihu-custom-scrollbar-plugin`, `jerichotab`, `jbox`, `artdialog`, `lhgdialog`, `fancybox`, `uploadify`, `plupload`, `swfupload`, `zeroclipboard`, `syntaxhighlighter`, `es5-shim`, `es5-sham`, `requirejs`, `seajs`, `fullPage`, `sweetalert`, `videojs`, `video-js`, `mathjax`, `font-awesome`, `fontawesome`
   - `jquery`, 그리고 `jquery`·`bootstrap` 뒤에 `-`·`.`·`_`·숫자가 이어지는 이름(`jquery-validation`, `jquery.form`, `bootstrap-table-1.11.0`), `jqueryui`, `bootstrapvalidator`
   - 이름 뒤에 구분자와 점 있는 버전이 붙은 이름(`font-awesome-4.7.0`, `es5-shim-15.3.4.5`)
   - 정적 자원 폴더 아래의, 버전만으로 된 이름(`2.3.1`). 이때 벤더 폴더는 그 부모다(`static/foo/2.3.1` → `static/foo`)
2. **옵션 규칙**. `web.vendorPatterns`의 glob에 맞는 경로. `**`는 `/`를 포함한 임의의 글자, `*`는 `/`를 뺀 임의의 글자, `?`는 한 글자다. `/`가 없는 glob은 경로의 어느 세그먼트에든 맞으면 된다(`legacy-*.js`, `thirdparty`). `!`로 시작하는 glob(`!**/js/lib/**`)은 반대로 "벤더가 아니다"라고 못 박는 것이고 다른 모든 규칙보다 먼저다(잘못 벤더로 판정된 프로젝트 폴더를 되살리는 데 쓴다).
3. **파일 이름 규칙**(스크립트만). 확장자와 `.min`을 뗀 이름이 1의 라이브러리 이름 목록에 있거나(뒤에 버전이 붙은 꼴 포함), 파일 이름으로만 보는 이름(`jquery`, `jquery-ui`, `jqueryui`, `jquery-migrate`, `layer`, `angular`, `prototype`, `dojo`, `yui`, `zepto`, `underscore`, `lodash`, `backbone`, `knockout`, `handlebars`, `mustache`, `moment`, `raphael`, `flot`, `swiper`, `prettify`, `modernizr`, `html5shiv`, `respond`, `json2`, `excanvas`, `require`, `toastr`)이거나(`jquery-ui_1.12.1.custom.js`처럼 버전이 붙은 꼴 포함), `<이름>-<점 있는 버전>` 꼴(`prototype-1.5.1.js`)이다. `bootstrap.js`(앱 시작 코드의 이름으로도 흔하다)와 `jquery.<이름>.js`(프로젝트가 쓴 `jquery.common.js` 같은 파일이 있다)는 이름만으로는 보지 않는다. 진짜 라이브러리는 4의 머리 주석으로 걸린다. 빌드 산출물·배포판을 뜻하는 이름도 벤더다: 확장자와 `.min`을 뗀 이름에 `.debug`·`-debug`·`.pack`·`.packed`·`.bundle` 조각이 있다(`oryx.debug.js`, `foo-debug.js`, `lib.pack.js`, `app.bundle.js`. F-010-R36).
4. **머리 주석 규칙**(스크립트만, 파일을 읽은 뒤. 4차에서 좁힘, F-010-R40). 파일 맨 앞에 이어지는 주석(4,000자까지. 줄 머리의 `*`·`//`와 줄바꿈은 지우고 본다)에 다음 가운데 하나가 있으면 **벤더 후보**다.
   - `@license` 태그
   - 알려진 오픈소스 라이선스의 정식 문구: `Licensed under the Apache License`, `Licensed under (the) MIT`·`Dual licensed under the MIT and GPL licenses`·`Released under the MIT license`(`licensed`·`released`·`distributed`·`available` + `under` + 라이선스 이름), `MIT License`·`BSD License`·`Apache License`·`GPL licenses`·`MIT/GPL2 Licensed`(라이선스 이름 + `license`), `Permission is hereby granted, free of charge`, `Redistribution and use in source and binary forms`(BSD 재배포 조건), `GNU (Lesser) General Public License`, `Mozilla Public License`, `SPDX-License-Identifier`
   - 라이선스 주소: `opensource.org/licenses`, `apache.org/licenses`, `gnu.org/licenses`, `…/license`로 끝나는 주소(`jquery.org/license`)
   - 알려진 프로젝트 URL(`github.com/…`, `github.io/…`, `jquery.com`, `jquery.org`, `npmjs.com`, `code.google.com/p/…`). 버전 번호(`1.2`, `v2`)가 함께 있고 `All rights reserved`가 없을 때만이다(프로젝트가 저작권 표기에 자기 저장소 주소를 적는 경우가 있다)
   - 벤더 근거가 **아닌** 것: 저작권 표기(`Copyright`, `(c)`, `©`), `@author`·`@version`·`Author:`·`Version:`, 버전 번호, `/*!`로 시작하는 주석, 낱말 `license`, 위 목록에 없는 라이선스 안내문(`Icons are licenced under a Creative Commons …`)
   - **프로젝트 정합성 검사.** 후보는 모든 스크립트(인라인 스크립트 포함)를 읽은 뒤에 판정한다. 다음 가운데 하나면 벤더가 아니다(그대로 분석한다). (ⅰ) 같은 폴더의 스크립트 가운데 벤더(1·2·3·5로 판정한 것과 다른 후보)가 이 파일뿐이고 분석하는 스크립트가 하나 이상 있으며, 파일 이름이 라이브러리 배포판의 꼴(`.min.js`·`-min.js`, 이름에 점 있는 버전 `raphael.2.1.1.js`, `jquery`·`bootstrap` 뒤에 구분자가 이어지는 `jquery.form.js`, 1·3의 라이브러리 이름)이 아니다. 압축본(`skipped=minified`)은 어느 쪽으로도 세지 않는다. (ⅱ) 프로젝트의 다른 스크립트(벤더·압축본·다른 후보 제외)가 이 파일의 최상위 함수(이름으로 선언한 함수. 객체의 멤버는 세지 않는다)를 이름으로(`f()`, `window.f()`) 부르는 것이 서로 다른 함수 5개 이상이다. (ⅱ)는 20만 자 이하이고 함수 선언으로 보이는 줄이 5개 이상인 후보만 파싱해서 센다. 둘 다 아니면 벤더다.
5. **큰 생성물 규칙**(스크립트만, 파일을 읽은 뒤). 20만 자를 넘고 `function`이 200번 넘게 나오는 파일은 손으로 쓴 소스가 아니라 번들·라이브러리 묶음으로 본다(F-010-R36).

- **압축 판정(F-010-R36)**은 내용으로 한다: 1,000자 이상이면서 평균 줄 길이가 500자를 넘거나, 5,000자 넘는 한 줄이 파일의 절반을 넘는다(머리 주석 뒤에 한 줄로 압축한 것). 이름이 `.min.js`·`-min.js`면 압축본으로 보되, 줄이 10개 이상이고 가장 긴 줄이 500자 이하면 압축본이 아니다(이름만 min인 손으로 쓴 파일). 줄은 LF·CRLF·CR 단독으로 센다.
- web의 판정 순서: `!` glob(맞으면 벤더가 아니다) → 1 → 2 → 3(이름이 `.min.js`가 아닐 때) → (파일을 읽고) 압축 판정(`skipped=minified`, 파일마다 진단) → 3(이름이 `.min.js`인데 압축본이 아닐 때) → 5 → 4(후보로 두었다가 모든 스크립트를 읽은 뒤 정합성 검사).
- 진단 `FILE_SKIPPED`(INFO)는 벤더 폴더마다 1건이다: `벤더 스크립트 n개 건너뜀(<근거>): <벤더 폴더>`. 2·3·4·5로 판정한 파일은 그 파일이 든 폴더로 묶는다. 위치는 그 폴더 경로다. `<근거>`는 판정 규칙을 한 낱말로 적은 것이다(F-010-R40): `경로`(1), `옵션`(2), `이름`(3), `라이선스`(4), `생성물`(5). 한 폴더에 근거가 섞이면 이 순서로 근거마다 건수를 적는다(`이름 3, 라이선스 4`). 잘못 걸린 프로젝트 파일은 `web.vendorPatterns=!<glob>`로 되살린다. 압축본의 진단(`압축된 스크립트라 내용을 분석하지 않았다: <경로>`)은 종전대로 파일마다다.
- 벤더로 판정한 모듈은 건너뛰지 않고 분석했더라도(`web.skipVendor=false`) 전역 이름 해석(F-010-R18)의 출처와 대상에서 뺀다.

**전역 이름 해석(F-010-R18)** — "페이지가 싣는 스크립트"는 그 페이지(JSP·HTML)의 인라인 스크립트, `INCLUDES`로 가리킨 JS 모듈(`<script src>`), 그리고 그 페이지가 include하는 JSP가 (다시 include하는 것까지) 싣는 것이다.

| 호출하는 쪽 | 대상 | 기록 |
|---|---|---|
| 인라인 스크립트 | 주인 페이지가 싣는 모듈의 전역(유일) | `RESOLVED` (F-010-R10) |
| 인라인 스크립트 | 주인 페이지가 싣는 모듈 여럿에 같은 이름 | 각각 `INFERRED` + `ambiguous=true` |
| 인라인 스크립트(주인 페이지에는 없음), JS 파일 | 어떤 페이지가 두 스크립트를 함께 싣는다 | `INFERRED` + `resolution=global` + `viaPage=<그 페이지의 노드 ID>`(여럿이면 ID가 가장 앞선 페이지). 후보가 여럿이면 `ambiguous=true`도 붙고, 8개를 넘으면 기록하지 않는다 |
| 내용 페이지의 스크립트(인라인, 그 페이지가 싣는 JS 파일) | SiteMesh 데코레이터가 싣는 스크립트(페이지 → 데코레이터 `INCLUDES`, `via=sitemesh`) | `INFERRED` + `resolution=global` + `viaPage=<내용 페이지>`. 데코레이터 자신의 스크립트가 자기가 싣는 것을 쓰는 것은 종전 규칙(`RESOLVED`)이다 (F-010-R33) |
| Tiles 정의의 조각 JSP의 스크립트 | 그 정의의 template이 싣는 스크립트, 같은 정의의 다른 조각 | `INFERRED` + `resolution=global` + `viaPage=<template 페이지>` (F-010-R33) |
| 어느 쪽이든 | 두 스크립트를 함께 싣는 페이지를 모른다 | 호출(`call`, `constructor`)이고 그 이름으로 함수에 닿는 최상위 정의가 프로젝트에 정확히 하나이며 JS 파일에 있으면 `INFERRED` + `loadUnverified=true`(`resolution`·`viaPage` 없음). 정의가 여럿이면 기록하지 않고 `CALLS_SKIPPED`에 센다. 그 밖에는 기록하지 않는다 (F-010-R34) |

- 잇지 않는 전역 이름: 표준 내장(`Object`, `Array`, `String`, `Number`, `Date`, `Math`, `JSON`, `Promise`, `Map`, `Set`, `RegExp`, `Error`, `parseInt`, `encodeURIComponent` 등), DOM·브라우저(`window`, `document`, `navigator`, `location`, `console`, `setTimeout`, `setInterval`, `alert`, `confirm`, `fetch`, `XMLHttpRequest`, `FormData`, `localStorage` 등), 모듈 시스템과 잘 알려진 라이브러리(`require`, `define`, `$`, `jQuery`, `_`, `angular`, `Vue`, `React`, `axios`, `moment`, `echarts`, `layer`, `CKEDITOR`, `WdatePicker`, `$$`, `$A`, `$F` 등). 전체 목록은 `Evaluator.BUILTIN_GLOBALS`다. 멤버 접근(`document.getElementById`, `Date.now`, `console.log`, `window.document.x`)은 뿌리 이름으로 판정한다. 같은 파일에서 선언한 이름(`function $(id){}`)을 그 파일 안에서 부른 것은 종전대로 잇는다.
- 전역 상수(URL 상수, 컨텍스트 경로 변수)도 같은 규칙으로 찾는다. 함께 싣는 페이지를 근거로 찾은 상수를 쓴 요청의 `REQUESTS`는 `INFERRED`다. `loadUnverified` 규칙(F-010-R34)은 호출에만 쓰고 상수와 함수 참조(`ref`)에는 쓰지 않는다.
- **"페이지가 싣는 스크립트"의 보강.** 페이지가 가리킨 스크립트가 내용을 분석하지 않은 압축본(`x.min.js`)이고 같은 폴더에 분석한 `x.js`가 있으면 그 페이지가 `x.js`를 싣는 것으로 보고 `INCLUDES`(`INFERRED`, `via=script`, `minOf`=압축본 경로, 위치는 원래 `<script src>`)를 그래프에 더한다(F-010-R35). 압축본이 프로젝트에 아예 없는 경우는 jsp가 같은 attrs로 잇는다(F-011-R23).
- **레이아웃(F-010-R33).** SiteMesh 엣지(`via=sitemesh`)는 "직접 싣는 것"을 셀 때는 따라가지 않고 "함께 실리는 것"을 셀 때만 따라간다. 그래서 데코레이터가 싣는 스크립트의 전역은 `RESOLVED`가 아니라 추론이다. Tiles 엣지(`via=tiles`, template → 조각)는 전역 해석에서 따라가지 않는다: template 하나를 여러 정의가 쓰므로 엣지를 따라가면 서로 다른 화면의 본문끼리 함께 실리는 것이 된다. 대신 설정 파일 `FILE` 노드의 `tilesDefinitions`(`정의 이름=template 경로`)·`tilesAttributes`(`정의 이름=조각 JSP 경로`, F-011 spec "Tiles")로 정의마다 묶음을 만든다. 경로는 설정 파일이 `WEB-INF` 아래면 그 부모를 웹 루트로 풀고, 아니면 경로 끝이 일치하는 JSP가 하나뿐일 때 그것이다. 조각 경로에 `{n}`이 있으면(와일드카드 정의) 정의 이름의 n번째 `*`는 `/`를 뺀 글자들, `**`는 임의의 글자들로 보고 그 패턴에 맞는 JSP마다 "template + 정적 조각 + 그 JSP" 묶음을 만든다. template 경로에 `{n}`이 있거나 template을 찾지 못한 정의는 건너뛴다.
- **`loadUnverified`(F-010-R34).** 후보는 모듈 문법이 없는 스크립트와 인라인 스크립트의 최상위 선언이고 벤더·건너뛴 스크립트는 뺀다. 멤버 호출(`App.msg.alert()`)은 뿌리 이름의 선언들 가운데 그 멤버까지 따라가 함수에 닿는 것만 정의로 센다. 유일한 정의가 다른 페이지의 인라인 스크립트에 있으면 이 스크립트가 실을 길이 없으므로 잇지 않는다(세지도 않는다). 이 엣지를 거친 요청 래퍼 전파의 `REQUESTS`는 `INFERRED`다.

**요청 옵션의 메서드(F-010-R20)** — `fetch(url, opts)`, `axios(url, opts)`, `$.ajax(url, opts)`의 `opts`가 객체 리터럴이 아닐 때다.

| `opts` | 메서드 |
|---|---|
| 객체 상수(`const o = { method: 'PUT' }`), 나중에 멤버를 붙인 객체(`o.type = 'POST'`) | 그 값(`RESOLVED`) |
| 여러 번 대입한 변수(`let o = {}; o = { method: 'PATCH' }`) | 대입한 객체마다 읽는다. 빈 객체 `{}`는 자리만 잡은 것이라 뺀다. 메서드 프로퍼티가 없으면 `GET`. `INFERRED` |
| 후보의 메서드가 여럿(`method: modeStr`, `modeStr = c ? 'POST' : 'PUT'`) | URL 후보가 하나면 메서드마다 요청 노드(3개까지. 넘으면 `ANY`). URL 후보도 여럿이면 아래 "메서드와 URL의 짝"(F-010-R27). `INFERRED` |
| 감싼 이름 있는 함수의 매개변수(요청 래퍼의 옵션 매개변수) | 래퍼 안의 요청은 `ANY`, 래퍼 호출 지점에서 그 자리의 인자를 위 규칙으로 읽는다(인자를 주지 않았으면 `GET`) |
| 그 밖(이름 없는 콜백의 매개변수, 함수 호출 결과) | `ANY` |

**메서드와 URL의 짝(F-010-R27)** — 한 호출 지점(요청 래퍼의 호출 지점 포함)에서 후보 정리(F-010-R22) 뒤에 남은 경로가 둘 이상이고 메서드 후보도 둘 이상일 때다.

| 경우 | 기록 |
|---|---|
| URL 식과 메서드 식이 같은 객체 변수의 프로퍼티(`modeInfo.editURL`과 `modeInfo.method`. 메서드 식은 옵션 객체의 `method: modeInfo.method`, 요청 래퍼에 넘긴 옵션 변수·메서드 인자도 같다) | 그 변수에 대입된 객체 리터럴마다 (그 리터럴의 URL, 그 리터럴의 메서드) 요청 하나. URL 프로퍼티가 없는 리터럴은 건너뛰고, 메서드 프로퍼티가 없거나 값을 모르는 리터럴은 `ANY`. 모두 `INFERRED` |
| URL 식이 그 객체의 프로퍼티에서 파생된 식(`const editURL = c ? `${modeInfo.editURL}/${id}` : modeInfo.editURL`. 상수·여러 번 대입한 변수의 값, 삼항, 템플릿, `+`·`\|\|`·`??`를 6단계까지 따라가 `modeInfo.<프로퍼티>`를 읽는 식) (F-010-R39) | 그 변수에 대입된 객체 리터럴마다 변수의 값을 그 리터럴로 고정하고 URL 식을 평가한다(리터럴 하나가 URL 여럿을 낼 수 있다). 그 URL들에 그 리터럴의 메서드가 붙는다. URL 식이 읽는 프로퍼티가 없는 리터럴은 건너뛴다 |
| 그 밖(메서드와 URL이 서로 다른 변수의 여러 후보) | URL 후보마다 `ANY` 하나 + `httpMethods`=메서드 후보 전부(F-010-R39). `INFERRED` |
| 메서드 후보가 하나 | URL 후보마다 그 메서드(종전대로) |

**URL마다 노드 하나(F-010-R39)** — URL 후보가 둘 이상인 지점에서는 경로마다 요청 노드를 하나만 만든다. 그 경로에 모인 메서드(짝지은 리터럴들의 메서드, 또는 짝을 모르는 후보 전부)가 하나면 그 메서드이고, 둘 이상이면 `httpMethod=ANY`(ID의 METHOD도 `ANY`)에 `httpMethods`(모인 순서, 대문자, 중복 없음)를 붙이고 `REQUESTS`는 `INFERRED`다. 모인 것 가운데 `ANY`(값을 모르는 메서드)가 있으면 `httpMethods`를 붙이지 않는다. 예: 상태에 `{ method: "POST", editURL: "/schedule" }`와 `{ method: "PUT", editURL: "/schedule" }`를 넣고 위 `editURL`로 요청하면 `ANY /schedule`·`ANY /schedule/{*}` 둘 다 `httpMethods=[POST, PUT]`다(리터럴마다 URL이 다르면 `POST /board`, `POST /board/{*}`, `PUT /board/edit`, `PUT /board/edit/{*}`처럼 메서드가 정해진다). 링커는 `httpMethods`가 있으면 그 메서드의 엔드포인트(와 메서드를 가리지 않는 엔드포인트)로만 잇는다. URL 후보가 하나인 지점은 종전대로 메서드마다 노드다(F-010-R20).

후보를 모을 때 흐름 전체를 보지는 않지만 두 가지는 본다. (1) 값을 쓰는 자리와 대입이 같은 `if` 문의 `then` 가지와 `else` 가지에 따로 들어 있으면 그 대입은 후보가 아니다(콜백 안에서 쓰더라도 구문 트리의 조상으로 판정한다). 그렇게 걸러 후보가 하나도 남지 않으면 거르지 않는다. (2) 값을 쓰는 자리를 감싼 `if`·삼항의 조건이 그 변수를 문자열 리터럴과 비교하면(`x === 'POST'`, `'POST' != x`, `&&`로 이은 것의 한 항, `else` 가지에서는 반대) 후보 값을 그 가지에 맞게 좁힌다. 예: `if (modeStr === "POST") { opts = { method: modeStr }; send(url, opts) } else { opts = { method: modeStr }; send(url, opts) }` → 앞 지점은 `POST`, 뒤 지점은 `PUT`. URL 후보(`modeInfo.editURL`의 두 값)는 좁힐 근거가 없어 지점마다 둘 다 남는다.

**객체 프로퍼티 URL(F-010-R21)** — `obj.prop`의 `obj`가 객체 상수면 종전대로 그 값이다(`RESOLVED`). `obj`가 여러 번 대입한 변수이거나 React 상태면 후보 객체 가운데 그 프로퍼티가 있는 것마다 값을 읽어 후보로 삼는다(`INFERRED`). React 상태는 `const [v, setV] = useState(<객체 리터럴>)`로 선언한 `v`이고, 후보는 초기 객체와 `setV(<객체 리터럴>)`·`setV(prev => (<객체 리터럴>))`로 넘긴 객체다. 스프레드(`...modeInfo`)는 따라가지 않는다. `useState(fn)`(초기화 함수)·`useState(0)`의 상태는 값을 모르는 이름이다.

**후보 정리(F-010-R22)** — 한 호출 지점(요청 래퍼의 호출 지점 포함)에서 나온 후보들에 적용한다. 같은 메서드의 후보 A(`{*}<꼬리>`)와 B가 있고 B가 `{*}`로 시작하지 않으면서 A의 꼬리로 끝나면 A를 버린다(`env ?? '/api'` 같은 기준 URL 갈래에서 구체적인 쪽만 남긴다). 구체적인 후보가 없으면 `{*}/…`가 그대로 남는다. URL 식의 갈래(`||`, `??`, 삼항, 여러 번 대입) 가운데 빈 문자열은 다른 갈래가 있으면 후보에서 뺀다.

**옵션 객체의 URL(F-010-R23)** — HTTP API 표의 형태로 인식되지 않은 호출·`new`의 인자가 객체 리터럴이거나 객체 상수를 가리키는 이름일 때 본다.

- 읽는 프로퍼티(`attrs.optionKey`): `url`, `ajax`(문자열 값), `ajax.url`, `async.url`, `content`(값이 `'url:…'`로 시작할 때. jquery-confirm).
- 메서드: `url`은 같은 객체의, `ajax.url`·`async.url`은 그 안쪽 객체의 `method`·`type`·`mtype` 값이 HTTP 메서드 이름이면 그것, 그런 프로퍼티가 있는데 값을 모르면 `ANY`, 프로퍼티가 없으면 플러그인의 기본 메서드(아래), 모르는 플러그인이면 `ANY`. `content`는 `GET`.
- **플러그인의 기본 메서드(F-010-R32)**: 피호출자 식의 마지막 이름과 옵션 키로 고른다. `bootstrapTable`의 `url` → `GET`(옵션 `method`의 기본값 `get`), `jqGrid`의 `url` → `GET`(`mtype`), `DataTable`·`dataTable`의 `ajax`·`ajax.url` → `GET`, 피호출자에 `zTree`가 든 호출(`$.fn.zTree.init`)의 `async.url` → `POST`(`async.type`), easyui의 `datagrid`·`treegrid`·`combogrid`·`combobox`·`combotree`·`propertygrid`의 `url` → `POST`.
- 문자열 인자(F-010-R37): 인자가 문자열 식이고 값이 `iframe:`·`url:`로 시작하면(피호출자에 `jbox`가 든 호출은 `get:`·`post:`도) 접두를 뗀 나머지가 서버 경로일 때 `optionKey=arg`인 요청을 만든다. layer는 피호출자가 `layer.open`(`top.layer.open` 등)이고 같은 객체에 `type: 2`가 있을 때 `content`를 URL로 읽는다.
- 서버 경로로 보는 값: 컨텍스트 경로를 뗀 뒤(알 수 없는 접두 `{*}`는 허용) `/`로 시작하고, 첫 경로 조각이 정적이며, 정적 자원 확장자(`css js map png jpg jpeg gif ico svg bmp webp woff woff2 ttf eot otf swf mp3 mp4 wav pdf`)로 끝나지 않는다. 상대 파일(`data.json`), 외부 URL, `#`, 값을 모르는 식은 아무것도 만들지 않는다.
- 요청 노드와 엣지의 위치는 호출 식이 시작하는 줄이다. `attrs.callee`는 피호출자 식의 원문(80자까지)이다. `REQUESTS`는 항상 `INFERRED`다.

**폼 메서드(F-010-R24)** — 폼 식에서 읽는 키: `document.NAME`·`document.forms.NAME`·`document.forms['NAME']`·`document.all.NAME` → `NAME`, `document.getElementById('ID')`·`$('#ID')` → `#ID`, `$('form[name=NAME]')` → `NAME`, 그런 값을 한 번 대입한 변수(`var frm = document.detailForm`). 폼 표는 jsp가 `JSP_PAGE` 노드의 `attrs.formMethods`에 싣는 `"<키>=<METHOD>"` 문자열 목록이다(형식은 F-011 spec "폼 표"). 인라인 스크립트는 주인 페이지(와 그 페이지가 include하는 페이지)의 표를 먼저 보고, 없으면(그리고 JS 파일은) 그 스크립트를 싣는 페이지들의 표에서 찾아 모두 같을 때만 쓴다. 같은 함수(이름 없는 콜백 포함) 안에서 같은 폼 식에 `.method = '리터럴'`을 대입했으면 그것이 먼저다.

**클라이언트 라우트(F-010-R30)** — 서버 요청이 아니라 클라이언트 라우터가 처리하는 화면 전환이다. 링커가 엔드포인트와 맞추지 않고 미해결로도 남기지 않도록 `clientRoute=true`를 붙인다.

- 적용 조건: 그 모듈이 `react-router…`, `vue-router`, `next`·`next/…`를 import(`require` 포함)한다.
- 호출: `navigate(x)`(이름이 `navigate`이거나 `useNavigate()`로 받은 변수이고 프로젝트 함수가 아니다), `X.push(x)`·`X.replace(x)`·`X.navigate(x)`(X의 마지막 이름이 `history`·`router`·`$router`이거나 `useHistory()`·`useRouter()`·`createBrowserHistory()` 등으로 받은 변수). 인자가 문자열 식이면 그것, 객체면 `pathname`(react-router)·`path`(vue-router)다. `navigate(-1)`, `{ name: 'user' }`처럼 경로가 없는 인자는 아무것도 만들지 않는다.
- JSX: 요소 이름이 라우터 패키지에서 import한 `Link`·`NavLink`·`Navigate`·`Redirect`이고 속성이 `to`(문자열, `{식}`, `{{ pathname: 식 }}`), 또는 `next/link`의 기본 import이고 속성이 `href`다. 요청은 그 요소를 그리는 함수에서 나가고 위치는 요소의 여는 태그다.
- 경로를 정적으로 알 수 없으면 조용히 건너뛴다(미해결 `http-url`을 남기지 않는다). 루트 `/`도 기록한다. 외부 URL은 기록하지 않는다. 요청 래퍼 전파(F-010-R11)도 같다(`const go = (path) => navigate(path)`의 호출 지점).
- `<Route path="…">`(react-router의 `Route`)는 요청이 아니라 라우트 선언이다. 선언된 절대 경로(`/`로 시작, `:id` 조각과 끝의 `*` 허용)를 모아 두고, `location` 이동·`window.open`(`api=navigation`)의 경로가 그 가운데 하나와 맞으면 그 요청에 `clientRoute=true`를 붙인다(`api`는 `navigation` 그대로). `window.location.href = URL.LOGIN`처럼 브라우저가 페이지를 다시 받더라도 그 경로를 처리하는 것은 클라이언트 라우터이기 때문이다.

**JSX 요소의 서버 요청(F-010-R42)** — 요소 이름이 소문자인 JSX 요소(HTML 요소)만 본다. 컴포넌트(`<Link>` 등)는 F-010-R30의 몫이다.

- 읽는 속성: `<a>`·`<area>`의 `href`(`api=link`), `<form>`의 `action`(`api=form`), `<img>`의 `src`(`api=img`), `<iframe>`·`<frame>`의 `src`(`api=frame`). 값은 문자열이거나 `{식}`이고 URL 식 평가(F-010-R7)로 읽는다(후보가 여럿이면 후보마다).
- 서버 경로 꼴(F-010-R23의 "서버 경로로 보는 값")일 때만 요청을 만든다: `/`로 시작하고 첫 조각이 정적이며 정적 자원 확장자가 아니다. `<img src={logo}>`(import한 자원), `/assets/logo.png`, 외부 URL, `#!`, 상대 경로, 값을 모르는 식은 요청도 미해결도 만들지 않는다.
- 메서드는 `GET`이다. `<form>`은 `method` 속성이 HTTP 메서드 이름이면 그것, 값을 모르는 식이면 `ANY`, 없거나 빈 값이면 `GET`이다.
- 경로가 선언된 `<Route path>`와 맞으면 `clientRoute=true`를 붙인다(`api`는 그대로). 라우터 컴포넌트 대신 `<a href={URL.ADMIN_NOTICE}>`로 화면을 옮기는 코드다.
- 요청은 그 요소를 그리는 함수에서 나가고 위치는 요소의 여는 태그다. `<script src>`, `<link href>`, `<video>`·`<audio>`·`<source>`는 보지 않는다.

**HTML 문자열 안의 태그(F-010-R45)** — 팝업을 `$('<div>').html('<iframe src="' + url + '">').dialog()`로 여는 화면, 목록을 문자열로 조립해 `innerHTML`에 넣는 화면이 대상이다. JSX 요소의 서버 요청(F-010-R42)과 같은 꼴의 요청을 만든다.

- **읽는 자리(DOM에 넣는 식)**: `x.html(H)`, `x.append(H, …)`(인자마다), `el.innerHTML = H`·`el.innerHTML += H`, `document.write(H)`·`document.writeln(H)`(수신자가 `document`이거나 `….document`), `el.insertAdjacentHTML(위치, H)`, 선언되지 않은 `$`·`jQuery`의 `$(H)`. H가 문자열·템플릿 리터럴·`+` 연결·이름·멤버 식일 때만 평가한다. 그 밖의 자리(`return '<a href=…>'`, 변수에 담기만 한 문자열, `.text(H)`, `.prepend`·`.after`·`.before`·`outerHTML`)는 읽지 않는다.
- **읽는 방법**: H를 URL 식 평가(F-010-R7)로 평가한 값(조각의 나열)에서 `<iframe`·`<frame`·`<a`·`<area`·`<form`·`<img` 태그를 찾아 그 요소의 URL 속성(`src`/`href`/`action`) 값을 조각의 나열로 떼어 낸다. 따옴표는 홑·겹·없음을 모두 읽고, 다른 속성 값 안의 `src=`는 속성이 아니다. `&amp;`는 `&`로 읽는다. 값이 그 문자열에서 끝나지 않으면(닫는 따옴표가 없다. 뒤를 다른 문장이 이어 쓴다) 뒤에 모르는 조각 하나를 붙인다.
- **요청으로 만드는 값**: 서버 경로 꼴(F-010-R23의 "서버 경로로 보는 값": `/`로 시작하고 첫 조각이 정적이며 정적 자원 확장자가 아니다. 맨 앞의 컨텍스트 경로 식은 뗀다)일 때만. `#`, `javascript:…`, 빈 값, 정적 자원(`/images/btn.gif`), 외부 URL, 상대 경로(`sub/x.do`)는 요청도 미해결도 만들지 않는다. `<img>`의 `src`가 그 문자열에서 끝나지 않으면(확장자를 모른다) 만들지 않는다.
- **미해결**: 값에 정적인 조각이 하나도 없으면(`'<a href="' + item.url + '">'`) 그 자리에 미해결 `REQUESTS`(`http-url`, 원문은 H의 원문)를 남긴다. `<img>`는 남기지 않는다(데이터로 그리는 그림이 흔하다).
- **요청 래퍼**: 값이 이름 있는 함수의 매개변수면(`addTab: function(title, url){ … '<iframe src="' + url + '">' … }`) 그 함수를 요청 래퍼로 등록해 호출 지점에서 요청을 만든다(F-010-R11. `via`=그 함수). 호출 지점의 값도 서버 경로 꼴일 때만이다(`Tab.addTab('홈', 'home')`은 요청이 아니다). `<img>`는 래퍼로 등록하지 않는다.
- `api`는 `frame`(`<iframe>`·`<frame>`), `link`(`<a>`·`<area>`), `form`(`<form>`), `img`. 메서드는 `GET`이고 `<form>`은 같은 태그의 `method` 속성이 HTTP 메서드 이름이면 그것, 값을 모르면 `ANY`다. 경로가 선언된 클라이언트 라우트와 맞으면 `clientRoute=true`다.
- 요청은 그 식이 놓인 함수(또는 모듈)에서 나가고, 위치는 **H 식**이다(호출 식의 첫 줄이 아니다: `$('<div>')`와 `.html('<iframe …>')`가 다른 줄에 있는 꼴이 흔하다). `url`은 H의 원문이다(공백을 하나로 줄이고 120자에서 자른다. 인라인 스크립트는 JSP에 쓴 그대로). 한 문자열에 URL 태그가 여럿이면 태그마다 요청이다. H의 평가 결과가 여러 갈래면(`if`/`else`로 대입한 `page`) 갈래마다 요청이고 `INFERRED`다.
- 예: `.html('<iframe style="border: 0px; " src="' + "<c:url value='/sym/cmm/EgovCcmZipSearchList.do'/>" +'" width="100%" height="100%"></iframe>')` → `GET /sym/cmm/EgovCcmZipSearchList.do`(`api=frame`). `var page = "${pageContext.request.contextPath}/sym/mnu/mpm/EgovMainMenuLeft.do?vStartP="+menuNo; … .html('<iframe … src="' + page + '" …>')` → `GET /sym/mnu/mpm/EgovMainMenuLeft.do`(쿼리 `vStartP={*}`).

**루트로 가는 화면 이동(F-010-R43)** — `api=navigation`(`location` 이동, `window.open`, `showModalDialog`, 그 요청 래퍼의 호출 지점)의 URL이 정확히 `/`(컨텍스트 경로 식 + `/` 포함, 동적 조각 없음)면 정적 조각이 없어도 `GET /` 요청을 만든다. 다른 API의 `/`(`$.get("/")`)와 값을 모르는 이동은 종전대로 미해결이다.

**폼 필드의 URL(F-010-R41)** — hidden 필드에 팝업 URL을 두고 스크립트가 꺼내 여는 화면(`<input type="hidden" name="zip_url" value="<c:url value='/sym/ccm/zip/EgovCcmZipSearchPopup.do'/>">` + `window.showModalDialog(frm.zip_url.value, …)`)을 잇는다. 필드 표는 jsp가 `JSP_PAGE` 노드의 `attrs.formFields`에 싣는 `"<폼 키>.<필드 name>=<경로>"`·`"#<input id>=<경로>"` 문자열 목록이다(형식은 F-011 spec "필드 표").

| 식 | 표에서 찾는 키 |
|---|---|
| `document.frm.zip_url.value`, `document.forms['frm'].zip_url.value`, `document.getElementById('f').zip_url.value` (폼 식은 F-010-R24의 폼 키 규칙으로 읽는다) | `frm.zip_url`, `#f.zip_url` |
| `frm.zip_url.value` (`frm`이 매개변수 등이라 어느 폼인지 모른다) | 폼을 가리지 않고 필드 이름이 `zip_url`인 항목(`….zip_url`) |
| `document.getElementById('zipUrl').value`, `$('#zipUrl').val()`(인자 없음) | `#zipUrl` |

- 인라인 스크립트는 주인 페이지(와 그 페이지가 include하는 페이지)의 표를 먼저 본다. 거기서 폼 키까지 맞춰 찾았으면 값은 확정이다(`RESOLVED`). 없으면(그리고 JS 파일은) 그 스크립트를 싣는 페이지들의 표에서 찾는다. 값이 페이지마다 다르면 후보마다 요청을 만든다(8개까지). 필드 이름만으로 찾은 것, 싣는 페이지에서 찾은 것은 `INFERRED`다.
- 어느 페이지가 싣는지 모르는 JS 파일은 그 키(필드 이름)의 URL이 프로젝트 전체의 표에 정확히 하나일 때만 그것으로 본다(`INFERRED`. F-010-R34와 같은 약한 근거).
- 표에 없으면 종전대로 값을 모르는 식이다(요청 지점이면 미해결). `x.value = …`(대입), `$('#id').val(v)`(값을 넣는 호출)는 읽는 식이 아니다. `url`은 원문 식이다(`frm.zip_url.value`).

**인라인 스크립트의 EL 삼항(F-010-R44)** — 내장 소스의 문자열 리터럴과 템플릿 리터럴 조각에 `__JSP_EXPR__`가 있고 그 조각에 치환 목록이 있으면, 자리마다 원문을 찾아 `${…? '<A>' : '<B>'}`(따옴표는 홑·겹, 조건에 `?`가 없다. F-011-R25와 같은 판정)일 때 그 자리를 A와 B로 바꾼 후보를 만든다(한 문자열에서 2개까지, 후보 4개). 후보에서 온 요청의 `REQUESTS`는 `INFERRED`이고 `url`은 원문 그대로다. 예: `location.href = "${ctx}/sys/user/${t == 'a' ? 'form' : 'list'}?id=1"` → `GET /sys/user/form`, `GET /sys/user/list`(쿼리 `id=1`). 갈래가 리터럴이 아닌 삼항, 삼항이 아닌 EL, 치환 목록이 없는 조각은 종전대로 `{*}`다. (5차 개정) 쿼리 값 자리의 삼항은 펼치지 않는다: 그 문자열 리터럴(또는 템플릿 조각)에서 자리표시자 앞에 `?`나 `&`가 있으면 경로를 가르지 않는 것이므로 `{*}`로 두고, 그것만으로 추론이 되지 않는다. 예: `"/&action=js&dts=${type eq 'thumb'?'1':'0'}&sm=${m?1:0}"` → 쿼리 `…&dts={*}&sm={*}`. `?`가 앞선 다른 문자열 조각에 있고 이 조각에 `&`도 없으면(`url + "${c ? 'a' : 'b'}"`) 종전대로 펼친다.

**URL 굳히기 보정(F-010-R38)**

| 평가한 조각 | 굳힌 결과 |
|---|---|
| `{*}` + `&popupId=` + `{*}` (`url = url + "&popupId=" + id`) | 경로 `{*}`, 쿼리 `popupId={*}` → 정적 경로가 없으므로 요청을 만들지 않는다(그 지점의 다른 후보가 없으면 미해결) |
| `/x/view.do&id=` + `{*}` | 경로 `/x/view.do`, 쿼리 `id={*}` |
| `/` + `/twitter/login.do` (`var ctx = "<c:url value='/'/>"`) | `/twitter/login.do` (외부 URL이 아니다) |
| `//cdn.example.com/x` (리터럴 하나) | 외부 URL 그대로 |
| 컨텍스트 경로 + 컨텍스트 경로처럼 보이는 조각 + `&extId=…` (`${ctx}${url}&extId=…`) | 맨 앞 하나만 떼고 나머지는 `{*}` → 경로 `{*}` → 미해결 |
| `/x/menu;JSESSIONID=` + `{*}` | `/x/menu` |

**파서 입력 보정(F-010-R19)** — 파서에 넘기는 글자만 길이를 보존해 바꾼다. 노드의 원문은 원래 소스에서 읽으므로 이름·문자열 값·위치는 그대로다.

| 원문 | 파서가 보는 글자 | 조건 |
|---|---|---|
| `using` | `usinq` | 낱말 하나이고 바로 뒤에 이름이 오지 않는다(`using x = …` 선언이 아니다) |
| `<!--` | `//--` | 그 줄에서 앞에 공백만 있다 |
| `-->` | `//>` | 그 줄에서 앞에 공백만 있다 |
| `&` | `\|` | `&&`·`&=`·엔티티(`&amp;`, `&#39;`)의 일부가 아니다 |
| CR(`\r`) | LF(`\n`) | 바로 뒤가 LF가 아니다(CR 단독 줄 끝. F-010-R29) |

## 4. 수용 기준

### F-010-AC1 (대상: F-010-R1)
- Given JS, JSX, TS(제네릭·타입 단언·optional chaining), TSX 소스
- When 파서로 파싱하면
- Then 오류 없는 트리가 나오고 함수 선언, 호출 식, JSX 요소 노드의 종류·필드·원문·줄·열을 읽을 수 있다

### F-010-AC2 (대상: F-010-R2)
- Given 가운데에 구문 오류가 있는 JS 파일과 정상 파일
- When 분석하면
- Then 오류 파일에 `PARSE_FAILED`(WARN) 진단이 정확히 1건 있고, 오류 앞뒤의 함수 노드와 정상 파일의 노드가 만들어진다

### F-010-AC3 (대상: F-010-R3)
- Given 함수 선언, 화살표 함수 대입, 중첩 함수, 클래스와 메서드, 객체 리터럴 메서드, IIFE 모듈, `App.init = function(){}`, `export default`가 든 파일
- When 분석하면
- Then `js:module:<경로>`와 세부 규칙표대로의 `js:function:<경로>#<이름 경로>` 노드가 있고, `FILE→MODULE→함수→중첩 함수` `CONTAINS`와 `async`·`exported`·`kind`가 맞으며, 이름 없는 콜백의 노드는 없다

### F-010-AC4 (대상: F-010-R3)
- Given JSX를 반환하는 함수 컴포넌트, `React.Component`를 상속한 클래스 컴포넌트, 소문자 이름의 일반 함수
- When 분석하면
- Then 앞의 둘만 `component=true`이고 클래스 컴포넌트는 `ClassName`과 `ClassName.render` 노드가 있다

### F-010-AC5 (대상: F-010-R4)
- Given 확장자를 생략한 상대 import, 디렉터리 `index` import, `export … from`, `require()`, 동적 `import()`, `tsconfig.json`의 `@/*` 별칭 import, 패키지 import, 없는 상대 경로 import
- When 분석하면
- Then 앞의 여섯은 `RESOLVED` `IMPORTS`, 패키지는 엣지 없음, 없는 경로는 `unresolved:js-module:…`로 가는 `UNRESOLVED` `IMPORTS`와 진단이 있다

### F-010-AC6 (대상: F-010-R5)
- Given 같은 모듈 함수 호출, named·default·namespace import 호출, 한 단계 re-export, `obj.method()`, `this.method()`, 콜백 안의 호출, 패키지·전역(`console`, `$`)·매개변수 호출
- When 분석하면
- Then 앞의 것들은 `CALLS`(콜백 안의 호출은 바깥의 이름 있는 함수에서)가 있고, 패키지·전역·매개변수 호출은 엣지가 없다

### F-010-AC7 (대상: F-010-R5)
- Given 컴포넌트가 `<Child />`를 렌더링하고, 훅 `useOrders()`를 부르고, `onClick={handleSave}`로 핸들러를 넘기는 React 코드
- When 분석하면
- Then `callType=jsx`, `call`, `ref`인 `CALLS`가 각각 있다

### F-010-AC8 (대상: F-010-R5)
- Given 프로젝트 모듈에서 import했지만 그 모듈에 없는 이름을 호출하는 코드
- When 분석하면
- Then `unresolved:js-call:…`로 가는 `UNRESOLVED` `CALLS`와 `UNRESOLVED_REFERENCE` 진단이 있다

### F-010-AC9 (대상: F-010-R6)
- Given HTTP API 표의 각 형태를 쓰는 코드
- When 분석하면
- Then 형태마다 `HTTP_REQUEST` 노드가 표의 `api`와 메서드(명시값 또는 기본값)로 있고, 그 요청을 감싼 이름 있는 함수(없으면 모듈)에서 `REQUESTS` 엣지가 있으며, 메서드 식이 동적이면 `ANY`다

### F-010-AC10 (대상: F-010-R7)
- Given 템플릿 리터럴 URL, `+` 연결, 모듈 상수·import한 상수·객체 상수 프로퍼티를 쓴 URL, 쿼리스트링이 붙은 URL, 외부 URL, 삼항 연산으로 고르는 URL
- When 분석하면
- Then URL 패턴이 `/orders/{*}`, `/api/products/{*}/stock`처럼 평가되고 `dynamic`, `query`, `external`이 맞으며 삼항의 두 갈래가 각각 요청이 된다

### F-010-AC11 (대상: F-010-R7)
- Given URL이 매개변수 하나뿐이거나 함수 호출 결과인 요청(axios 인스턴스의 `baseURL`만 정적인 경우 포함)
- When 분석하면
- Then `HTTP_REQUEST`는 없고 `unresolved:http-url:<원문 식>`으로 가는 `UNRESOLVED` `REQUESTS` 엣지와 진단이 있다

### F-010-AC12 (대상: F-010-R8)
- Given `axios.create({ baseURL: '/api' })`를 default export하는 모듈과 그것을 import해 `client.get('/orders')`, ``client.put(`/orders/${id}/status`)``를 부르는 모듈
- When 분석하면
- Then 요청 URL이 `/api/orders`, `/api/orders/{*}/status`다

### F-010-AC13 (대상: F-010-R9)
- Given 한 JSP의 인라인 스크립트 조각 2개(둘째가 첫째의 전역 함수를 부르고, `'__JSP_EXPR__/order/close.do'`와 `'/order/' + __JSP_EXPR__ + '/view'`로 요청)
- When 분석하면
- Then 함수·요청이 `js:module:<JSP 경로>` 아래에 있고 위치가 JSP 파일의 줄이며, 조각 사이의 호출이 `CALLS`로 이어지고 URL이 `/order/close.do`, `/order/{*}/view`다

### F-010-AC14 (대상: F-010-R10, F-010-R18)
- Given JSP가 `INCLUDES`로 포함한 JS 파일의 전역 함수·IIFE 모듈 멤버를 인라인 스크립트가 호출하고, 같은 이름의 전역 함수가 포함한 두 파일에 있는 경우. 그리고 이 JSP는 싣지 않지만 이 JSP를 include하는 바깥 페이지가 싣는 스크립트의 전역과, 어느 페이지도 함께 싣지 않는 스크립트의 전역을 호출하는 경우
- When 분석하면
- Then 유일한 대상은 `RESOLVED` `CALLS`, 이름이 겹친 대상은 각각 `INFERRED` + `ambiguous=true` `CALLS`이고, 바깥 페이지가 함께 싣는 스크립트의 전역은 `INFERRED` + `resolution=global` + `viaPage`로 이어지며, 함께 싣는 페이지가 없는 전역으로는 엣지가 없다
- 개정(2026-10-11, F-010-R18): 종전에는 포함하지 않은 스크립트의 전역을 프로젝트 전체에서 이름으로 찾았다
- 개정(2026-10-11 3차, F-010-R34): "함께 싣는 페이지가 없는 전역으로는 엣지가 없다"는 정의가 여럿이거나 다른 페이지의 인라인에만 있을 때만이다. 정의가 프로젝트에 하나뿐이고 JS 파일에 있으면 `INFERRED` + `loadUnverified=true` 엣지가 있다(테스트의 `none.js#nowhere`)

### F-010-AC15 (대상: F-010-R10, F-010-R18)
- Given 모듈 문법이 없는 JS 파일들(한쪽이 다른 쪽의 전역 함수를 호출)과, 그 가운데 둘을 함께 싣는 페이지
- When 분석하면
- Then 함께 실리는 두 파일 사이에는 `INFERRED` `CALLS`(`attrs.resolution=global`, `viaPage`=그 페이지)가 있고, 어느 페이지도 함께 싣지 않는 파일에서 나가는 전역 호출과 ES 모듈의 전역 호출에는 엣지가 없다
- 개정(2026-10-11, F-010-R18): 종전에는 함께 싣는 페이지가 없어도 프로젝트 전체에서 이름으로 찾아 이었다
- 개정(2026-10-11 3차, F-010-R34): 어느 페이지도 함께 싣지 않는 파일에서 나가는 전역 호출도, 그 이름의 정의가 프로젝트에 하나뿐이면 `INFERRED` + `loadUnverified=true`(`resolution`·`viaPage` 없음)로 이어진다. ES 모듈의 전역 호출에는 여전히 엣지가 없다

### F-010-AC16 (대상: F-010-R11)
- Given `function ajaxPost(url, data){ $.ajax({ url: url, type: 'POST' }) }`와 `ajaxPost(BASE + '/close.do', …)` 호출
- When 분석하면
- Then 래퍼 안의 요청은 미해결(`http-url`)로 남고, 호출 지점에 `POST /order/close.do` `HTTP_REQUEST`(`via`=래퍼 함수 ID)와 호출한 함수에서의 `REQUESTS`가 있다. `fetchOrder(orderId)`처럼 경로 변수만 넘기는 호출 지점에는 요청이 생기지 않는다

### F-010-AC17 (대상: F-010-R12)
- Given `jquery.min.js`처럼 이름이 `.min.js`로 끝나거나 한 줄이 매우 긴 파일
- When 분석하면
- Then `JS_MODULE` 노드(`skipped=minified`)만 있고 함수 노드는 없으며 `INFO` 진단이 있다

### F-010-AC18 (대상: F-010-R1, F-010-R5, F-010-R6)
- Given 인터페이스·타입 별칭·enum·제네릭 호출(`client.get<T>(…)`)·타입 단언·데코레이터가 든 TS/TSX 코드
- When 분석하면
- Then 타입 선언은 노드를 만들지 않고, 함수·호출·요청은 JS와 같게 추출되며, enum 멤버 상수를 URL로 쓸 수 있다

### F-010-AC19 (대상: F-010-R13)
- Given 생성자의 `this.handleSave = this.handleSave.bind(this)`, 인자와 JSX 속성으로 넘긴 `f.bind(x)`, `f.call(x)`, `f.apply(x, [])`, `f.bind(x)()`, 자기 자신을 bind한 함수
- When 분석하면
- Then bind는 `callType=ref`인 `CALLS` 하나씩이고(`call`이 아니다), `call`·`apply`는 `callType=call`이며, `f.bind(x)()`는 `ref`와 `call`이 모두 있고, 자기 자신을 bind한 것은 엣지가 없다

### F-010-AC20 (대상: F-010-R13)
- Given `typeof f`, `f.name + f.length`, `export { f }`, `const o = { f }`, `[f]`, `const g = f`와 `addEventListener('click', f)`, `setTimeout(f)`, `p.then(f)`, `list.map(f)`, `new Promise(f)`, `$.ajax({ error: f, success })`, 그리고 JSX의 `onClick={f}`·`onClick={f()}`·`onClick={() => f()}`
- When 분석하면
- Then 앞의 여섯은 `CALLS`가 없고, 인자·옵션 객체(단축 프로퍼티 포함)·`onClick={f}`는 `callType=ref`, `onClick={f()}`와 `onClick={() => f()}`는 감싼 함수에서 나가는 `callType=call`이다

### F-010-AC21 (대상: F-010-R14)
- Given 치환 목록이 있는 내장 소스의 요청(`'__JSP_EXPR__/order/history.do'` ← `'${ctx}/order/history.do'`, `'/order/close.do'` ← `'<c:url value="/order/close.do"/>'`, 채움 공백이 낀 `+` 연결, 문자열 안에서 지운 `<c:if>`, 전부 동적인 `__JSP_EXPR__`, 요청 래퍼 호출 지점, 다른 조각의 옵션 객체), 치환 목록이 없거나 깨진 내장 소스, `__JSP_EXPR__`라는 이름을 쓴 JS 파일
- When 분석하면
- Then `url`과 미해결 원문이 JSP에 쓴 그대로이고 `path`·`query`·노드 ID는 그대로이며, 치환 목록이 없거나 깨진 조각은 `'${…}/order/close.do'`처럼 나오고, 어느 노드의 ID와 `url`에도 `__JSP_EXPR__`가 없으며(JS 파일의 원문 제외), JS 파일의 `url`은 원문 그대로다

### F-010-AC22 (대상: F-010-R15)
- Given 자식 컴포넌트가 `{ onSelect }`, `{ onExport: exportAll }`, `props.onBack()`, `const { onPick } = props`로 받은 함수를 호출하고, 부모 둘이 `onSelect={handleSelect}`·`onSelect={pick}`을, 다른 부모들이 프로젝트 함수(`onExport={track}`, `onBack={handleBack}`, `onBack={this.handleBack.bind(this)}`), useState의 setter(`onOther={setId}`), 인라인 화살표 함수(`onInline={() => handleBack()}`)를 넘기는 React 코드
- When 분석하면
- Then 자식의 호출 지점을 감싼 함수에서 부모가 넘긴 프로젝트 함수마다 `INFERRED` `CALLS`(`callType=call`, `viaProp=<prop 이름>`, 위치는 자식의 호출 지점)가 있고, setter와 인라인 화살표 함수를 넘긴 prop에는 엣지가 없으며, 부모 쪽의 `callType=ref`와 인라인 함수 본문의 `call`은 그대로다

### F-010-AC23 (대상: F-010-R16)
- Given AC22의 코드와, `function ajaxPost(url, data, callback) { $.ajax({ url: url, data: data, success: callback }) }`(다른 파일이 셋째 인자로 함수를 넘겨 호출), `done.call(this, res)`, `fn.bind(null)`과 `fn()`, 이름 없는 콜백의 매개변수 호출, 전역 호출이 든 레거시 스크립트
- When 분석하면
- Then 생략이 있는 모듈마다 `CALLS_SKIPPED`(INFO) 진단이 정확히 1건(위치는 그 파일) 있고 메시지에 "수신자를 알 수 없는 호출 n건"과 직접 호출·넘김 건수, 줄 번호가 있으며, 값 매개변수(`url`, `data`)·이름 없는 콜백의 매개변수·전역 호출·받은 값의 메서드 호출은 세지 않고, 생략이 없는 모듈에는 진단이 없다

### F-010-AC24 (대상: F-010-R17)
- Given 벤더 폴더(`static/ckeditor/…`, `static/zTree_v3/…`, `static/bootstrap/2.3.1/…`, `js/lib/…`, 최상위 `vendor/`, `src/vendor/`, `…/webjars/…`)의 파일, `prototype-1.5.1.js`·`jquery-ui_1.12.1.custom.js`·`swfobject.js`, 머리 주석에 라이선스 배너(`/*! Foo v1.2.3 …`, `@license`, `Copyright (c) … MIT`, jQuery 플러그인의 배너)가 있는 파일, 그리고 프로젝트 코드(사내 저작권 표기만 있는 `/*!` 주석의 `common.js`, `order.js`, `src/lib/api.js`, 도메인 폴더 `src/pages/vendor/…`·`WEB-INF/views/plugin/…`, `jquery.common.js`, `bootstrap.js`)와 `jquery.min.js`
- When 분석하면
- Then 벤더 파일은 `JS_MODULE`(`skipped=vendor`)만 있고 함수·요청·호출 대상이 되지 않으며, `FILE_SKIPPED`(INFO)가 벤더 폴더마다 1건(`벤더 스크립트 n개 건너뜀: <폴더>`) 있고, 프로젝트 코드는 그대로 분석되며 `jquery.min.js`는 `skipped=minified`다
- 개정(2026-10-11 4차, F-010-R40): 진단 메시지에 판정 근거가 붙는다(`벤더 스크립트 2개 건너뜀(경로): …/static/ckeditor`, `벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): …/js`). 머리 주석으로 걸리는 파일(`banner.js`의 `foo.org/license`, `@license`, `Released under the MIT license`, `Dual licensed under the MIT and GPL licenses`)은 같은 폴더에 벤더 후보·벤더 파일이 여럿이라 그대로 벤더다

### F-010-AC25 (대상: F-010-R17)
- Given 같은 프로젝트에 `web.skipVendor=false`를 준 경우와 `web.vendorPatterns=**/thirdparty/**, legacy-*.js,member.js, !**/js/lib/**,!swfobject.js`를 준 경우
- When 분석하면
- Then 앞의 경우 벤더 스크립트도 분석되고 `vendor=true`가 붙으며(전역 이름의 호출 대상은 아니다), 뒤의 경우 glob에 맞는 파일이 기본 규칙에 더해 `skipped=vendor`이고 `!` glob에 맞는 파일은 기본 규칙에 걸려도 분석된다

### F-010-AC26 (대상: F-010-R18)
- Given 레이아웃 JSP가 메뉴 JSP를 include하고 `common.js`·`menu.js`를 싣는다. 메뉴 JSP의 인라인 스크립트가 `common.js`의 전역 함수·상수와 `menu.js`의 전역 함수를 쓰고, `menu.js`가 그 인라인 함수를 부른다. 같은 이름의 전역을 가진 `admin.js`는 다른 페이지만 싣는다
- When 분석하면
- Then 인라인 함수 → `common.js`·`menu.js`, `menu.js` → 인라인 함수의 `CALLS`가 `INFERRED` + `resolution=global` + `viaPage=<레이아웃 페이지>`로 있고 `admin.js`로는 엣지가 없으며(`ambiguous`도 아니다), 함께 실리는 스크립트의 상수로 푼 요청의 `REQUESTS`는 `INFERRED`다

### F-010-AC27 (대상: F-010-R18)
- Given 한 페이지가 `$`·`_`·`jQuery`를 선언한 파일, `Date`·`JSON`·`console`·`document.getElementById`·`window.setTimeout`·`Promise`·`Math`를 선언한 파일, 프로젝트 함수 `helper`가 든 파일을 함께 싣고, 다른 파일과 인라인 스크립트가 그 이름들을 호출하는 경우
- When 분석하면
- Then 내장·라이브러리 이름으로는 `CALLS`가 없고 `helper`와 같은 파일의 함수로만 이어진다

### F-010-AC28 (대상: F-010-R19)
- Given `var using; using = function (props) {…}; if (using) { using(); }`와 `{ using: using }`, `options.using`이 든 파일
- When 분석하면
- Then `PARSE_FAILED`가 없고 함수 이름이 `using` 그대로이며 그 함수로 가는 호출이 이어진다

### F-010-AC29 (대상: F-010-R19)
- Given 함수 선언 뒤에 줄 맨 앞 `<!--`와 `-->`, `//-->`가 있는 스크립트
- When 분석하면
- Then `PARSE_FAILED`가 없고 주석 줄 뒤의 선언과 호출이 원래 줄 번호로 추출된다

### F-010-AC30 (대상: F-010-R19)
- Given JSX 텍스트에 `R&D, 인력 & 조직 &amp; 기타`, JSX 문자열 속성에 `"R&D"`·`"/list?a=1&b=2"`, 식에 `mask & 4`가 있는 `.jsx` 파일
- When 분석하면
- Then 그 줄에 `PARSE_FAILED`가 없고 그 파일의 컴포넌트 판정·호출·요청이 추출된다

### F-010-AC31 (대상: F-010-R20)
- Given `fetch(SERVER_URL + url, requestOptions)`를 하는 요청 래퍼와, `const requestOptions = { method: "GET" }`·`let requestOptions = {}; requestOptions = { method: "PATCH" }`·갈래마다 `{ method: modeStr }`(`"POST"`/`"PUT"`)·매개변수·인라인 객체를 넘기는 호출들, `fetch('/direct', opts)`, `$.ajax(settings)`(객체 상수, 나중에 붙인 `s.url`·`s.type`)
- When 분석하면
- Then 호출 지점의 요청 메서드가 각각 `GET`, `PATCH`(`INFERRED`), `POST`와 `PUT`(둘 다 `INFERRED`), `ANY`, 인라인 값이고, 직접 호출과 `$.ajax`도 변수에서 읽은 메서드이며, 래퍼 안의 요청은 `ANY`로 남는다

### F-010-AC32 (대상: F-010-R21)
- Given `const [modeInfo, setModeInfo] = useState({ mode })`와 갈래마다 `setModeInfo({ ...modeInfo, editURL: … })`를 부르는 컴포넌트가 `fetch(modeInfo.editURL, …)`을 하고, 객체 상수의 프로퍼티·여러 번 대입한 객체의 프로퍼티·값을 모르는 프로퍼티·`useState(() => helper)`의 상태를 쓰는 코드
- When 분석하면
- Then 상태와 여러 번 대입한 객체의 프로퍼티 URL은 후보마다 `INFERRED` 요청이 되고(`url`은 `modeInfo.editURL`), 객체 상수의 프로퍼티는 `RESOLVED`이며, 값을 모르는 프로퍼티는 미해결(`http-url`)로 남고 초기화 함수의 상태를 호출해도 `CALLS`가 없다

### F-010-AC33 (대상: F-010-R22)
- Given `const BASE = import.meta.env.VITE_API ?? "/api"`를 앞에 붙인 요청과 그런 요청 래퍼의 호출, 값을 모르는 접두만 있는 요청, 삼항으로 고른 접두의 요청, `let url = ""`·`id || ""`·`id ?? ''`가 섞인 URL
- When 분석하면
- Then 첫 경우는 `/api/…` 하나만 있고(`{*}/…`는 없다), 둘째는 `{*}/items` 그대로이며, 셋째는 두 갈래가 모두 있고, 빈 문자열에서 나온 `/api`·`…/update/` 같은 반쪽 요청은 없다

### F-010-AC34 (대상: F-010-R23)
- Given 인라인 스크립트의 `$table.bootstrapTable({ url: '${ctx}/manage/user/list', method: 'get' })`, `$.dialog({ content: 'url:${ctx}/manage/user/create' })`, `var setting = { async: { url, type: 'post' } }; $.fn.zTree.init($('#t'), setting)`, `datagrid({ url, method: 'post' })`, `DataTable({ ajax: '/api/rows' })`·`DataTable({ ajax: { url, type: 'POST' } })`, 경로가 아닌 값(`data.json`, 외부 URL, 이미지, 모르는 값, `#`), 문자열 `content`, 그리고 `$.ajax({ url })`
- When 분석하면
- Then 앞의 것들은 `api=option`인 `HTTP_REQUEST`(`optionKey`, `callee`, 메서드는 옵션 값·`GET`(`content`)·`ANY`)와 `INFERRED` `REQUESTS`가 있고 위치는 호출 식의 줄이며, 경로가 아닌 값은 요청도 미해결도 없고, `$.ajax`는 종전대로 `api=jquery`·`RESOLVED` 하나뿐이다
- 개정(2026-10-11 3차, F-010-R32): 메서드를 적지 않은 `DataTable({ ajax: '/api/rows' })`의 메서드는 `ANY`가 아니라 플러그인의 기본값 `GET`이다

### F-010-AC35 (대상: F-010-R24)
- Given 페이지의 `attrs.formMethods`가 `listForm=POST`, `#listForm=POST`, `detailForm=POST`, `#detailForm=POST`, `searchForm=GET`, `dyn=ANY`이고 인라인 스크립트가 `document.listForm.action`, `document.detailForm.action` + `.method = 'get'`, `let frm = document.detailForm; frm.action`, `document.forms['searchForm'].action`, `document.getElementById('listForm').action`, `$('#detailForm').attr('action', …)`, 매개변수 폼·표에 없는 폼의 `action`을 대입하는 경우. 그리고 두 페이지가 싣는 JS 파일이 두 페이지에서 메서드가 같은 폼과 다른 폼의 `action`을 대입하는 경우
- When 분석하면
- Then 메서드가 각각 `POST`, `GET`(같은 함수의 `.method` 대입), `POST`, `GET`, `POST`, `POST`, `ANY`이고, JS 파일의 것은 같은 폼이면 그 메서드, 다르면 `ANY`이며, 다른 함수(다른 콜백)의 `.method` 대입은 쓰지 않는다

### F-010-AC36 (대상: F-010-R25)
- Given 내장 소스의 `function __JSP_EXPR__Open() {…}`, 그 안의 중첩 함수, `var __JSP_EXPR__Grid = { reload: function () {} }`, 그것들을 부르는 함수
- When 분석하면
- Then 함수 노드가 `js:function:<JSP>#{*}Open`, `{*}Preview.inner`, `{*}Grid.reload`이고 이름도 같으며 `dynamicName=true`이고, 호출 엣지가 이어지며, 어느 노드의 ID와 이름에도 `__JSP_EXPR__`가 없다

### F-010-AC37 (대상: F-010-R5, F-010-R6)
- Given 한 함수가 여러 줄에 걸쳐 함수 호출(인자 안의 호출, 한 줄에 두 번 호출 포함)과 요청(`$.post`, `location.href`)을 하는 코드
- When 분석하면
- Then 그 함수에서 나가는 `CALLS`·`REQUESTS` 엣지의 위치가 각 호출 식이 시작하는 줄·열이어서, 위치순으로 늘어놓으면 소스의 호출 순서다

### F-010-AC38 (대상: F-010-R26)
- Given JS 파일 둘과 `progress` 호출을 기록하는 컨텍스트
- When 분석하면
- Then 두 파일의 상대 경로가 경로순으로 알려진다.

### F-010-AC39 (대상: F-010-R27)
- Given 상태 `modeInfo`에 갈래마다 `{ method: "POST", editURL: "/board" }`, `{ method: "PUT", editURL: `/board/${id}` }`, `{ method: "POST", editURL: "/boardReply" }`, `{ editURL: "/boardDraft" }`(메서드 없음)를 넣고 `requestFetch(modeInfo.editURL, { method: modeInfo.method })`(요청 래퍼, 옵션 객체 상수)와 `fetch(modeInfo.editURL, { method: modeInfo.method })`를 하는 컴포넌트. 메서드는 다른 변수(`modeStr = c ? "POST" : "PUT"`)에서, URL은 상태에서 오는 호출. 메서드 후보가 하나인 호출, URL 후보가 하나인 호출. 여러 번 대입한 객체 변수 `cfg`의 `$.ajax({ url: cfg.url, type: cfg.type })`
- When 분석하면
- Then 첫 두 지점의 요청은 `POST /board`, `PUT /board/{*}`, `POST /boardReply`, `ANY /boardDraft`뿐이고(`POST /board/{*}`, `PUT /board`, `PUT /boardReply`가 없다) 모두 `INFERRED`이며, 짝을 모르는 지점은 URL마다 `ANY` 하나, 메서드가 하나면 URL마다 그 메서드, URL이 하나면 메서드마다 요청이고, `cfg`는 `POST /a/save`와 `DELETE /b/remove`다
- 개정(2026-10-11 4차, F-010-R39): "짝을 모르는 지점은 URL마다 `ANY` 하나"에 후보 메서드가 `httpMethods`로 붙는다(AC53)

### F-010-AC40 (대상: F-010-R28)
- Given 인라인 스크립트의 `window.showModalDialog("/pop/list.do", self, "…")`, `showModelessDialog('${ctx}/pop/detail.do?id=' + id, …)`, `showModalDialog(url, …)`(`url`은 상수), 값을 모르는 인자. 그리고 `window.showModalDialog = function (arg1, …) { window.open(arg1, …) }`를 선언한 JS 파일을 싣는 다른 페이지의 `window.showModalDialog('/pop/b.do', …)`
- When 분석하면
- Then 앞 페이지의 세 지점에 `api=navigation`·`GET` 요청(`RESOLVED`)이 있고 값을 모르는 지점은 미해결(`http-url`)이며 그 함수로 가는 `CALLS`는 없고, 뒤 페이지의 지점은 그 프로젝트 함수로 가는 `CALLS`와 요청 래퍼 전파로 생긴 요청(`via`=그 함수)이다

### F-010-AC41 (대상: F-010-R29)
- Given 줄 끝이 CR뿐인 스크립트(`a = g(form) \r\tb = h(a);` 포함), CR·CRLF·LF가 섞인 스크립트, 20KB를 넘는 CR뿐인 스크립트
- When 분석하면
- Then `PARSE_FAILED`가 없고 함수·요청·호출의 줄 번호와 열이 CR마다 늘어난 값이며(CRLF는 줄 하나) 큰 파일이 `skipped=minified`가 아니다

### F-010-AC42 (대상: F-010-R30)
- Given `react-router-dom`을 import한 모듈의 `navigate(URL.LOGIN)`, `navigate({ pathname: URL.ERROR }, …)`, `navigate(-1)`, ``navigate(`/inform/notice/${id}`)``, `<Link to={URL.MAIN}>`, `<NavLink to="/about">`, `<Link to={{ pathname: URL.NOTICE }}>`, `<Navigate to={URL.LOGIN}>`, `window.location.href = URL.LOGIN`, `fetch('/api/notice')`, `items.push('/not/a/route')`와 `<Route path={URL.LOGIN}>`·`<Route path="/inform/notice/:id">` 선언. react-router v5(`this.props.history.push`, `useHistory().replace`, `<Redirect to>`), `next/router`·`next/link`, `vue-router`(`router.push({ path })`, `{ name }`), 라우터를 import하지 않은 모듈의 `navigate(…)`·`history.push(…)`
- When 분석하면
- Then 라우터 이동과 링크는 `api=route`·`clientRoute=true`·`GET`인 `HTTP_REQUEST`와 `REQUESTS`(감싼 함수에서, 링크는 그 요소를 그리는 함수에서)가 있고, `navigate(-1)`·`{ name }`·`items.push`·`<a href>`·`<Route path>`는 요청이 없으며, 선언된 라우트로 가는 `location` 이동은 `api=navigation` + `clientRoute=true`, 서버 경로로 가는 이동과 `fetch`에는 `clientRoute`가 없고, 라우터를 import하지 않은 모듈에는 요청이 없다
- 개정(2026-10-11 4차, F-010-R42): `<a href="/plain/anchor">`는 요청이 없는 것이 아니라 `api=link`인 `GET /plain/anchor` 요청이다(선언된 라우트가 아니므로 `clientRoute`가 없다)

### F-010-AC43 (대상: F-010-R31)
- Given JS 파일의 `form.action = "<c:url value='/sym/mnu/mpm/EgovMenuListUpdt.do'/>"`, `location.href = "${pageContext.request.contextPath}/menu/list.do?id=" + id`, `$.post('<%=request.getContextPath()%>/menu/save.do')`, `$.get('${ctx}/menu/${menuId}/detail.do')`, `<spring:url value="…"/>`, 템플릿 리터럴 `` `/tpl/${id}/view` ``
- When 분석하면
- Then 요청의 `path`가 `/sym/mnu/mpm/EgovMenuListUpdt.do`, `/menu/list.do`(쿼리 `id={*}`), `/menu/save.do`, `/menu/{*}/detail.do`, `/menu/tree.do`, `/tpl/{*}/view`이고 `url`은 원문이며 어느 요청 ID에도 `<c:url`, `<%=`, `${`가 없다

### F-010-AC44 (대상: F-010-R32)
- Given 메서드를 적지 않은 `bootstrapTable({ url })`, `DataTable({ ajax: '/x' })`, `dataTable({ ajax: { url } })`, `$.fn.zTree.init(el, { async: { url } })`, `datagrid({ url })`, `treegrid({ url })`, `jqGrid({ url })`, 모르는 플러그인의 `{ url }`·`init({ async: { url } })`, 그리고 메서드를 적은 `bootstrapTable({ url, method: 'post' })`·zTree의 `type: 'get'`
- When 분석하면
- Then 메서드가 각각 `GET`, `GET`, `GET`, `POST`, `POST`, `POST`, `GET`, `ANY`, `ANY`이고 적은 것은 적은 값이며 모두 `api=option`·`INFERRED`다

### F-010-AC45 (대상: F-010-R33)
- Given 내용 페이지 둘이 `via=sitemesh` `INCLUDES`로 데코레이터를 가리키고, 데코레이터가 include한 조각이 `jeesite.js`를 싣는다. 내용 페이지의 인라인 스크립트와 한 페이지가 직접 싣는 `form.js`가 `jeesite.js`의 전역을 호출하고, 데코레이터의 인라인 스크립트도 호출한다. 데코레이터를 쓰지 않는 페이지도 같은 전역을 호출한다
- When 분석하면
- Then 내용 페이지(와 그 페이지가 싣는 JS 파일)에서 `jeesite.js`로 가는 `CALLS`가 `INFERRED` + `resolution=global` + `viaPage=<그 내용 페이지>`이고, 페이지가 직접 싣는 스크립트로 가는 것과 데코레이터 자신의 호출은 `RESOLVED`이며, 데코레이터를 쓰지 않는 페이지의 호출은 `resolution=global`이 아니고(`loadUnverified`로만 이어진다) 내용 페이지끼리는 이어지지 않는다

### F-010-AC46 (대상: F-010-R33)
- Given Tiles 설정 파일 노드의 `tilesDefinitions`(`base`, `order.list`, `*/*`가 모두 `main.jsp`, template이 없는 `broken=`)와 `tilesAttributes`(`header.jsp`, `order.list=…/order/list.jsp`, `*/*=/WEB-INF/views/{1}/{2}.jsp`), template이 싣는 `common.js`, `via=tiles` 엣지(template → header, list), 본문 JSP 둘(`order/list.jsp`, `member/edit.jsp`)과 패턴에 맞지 않는 JSP의 인라인 스크립트
- When 분석하면
- Then 본문과 header의 스크립트에서 `common.js`로 가는 `CALLS`가 `INFERRED` + `resolution=global` + `viaPage=<template>`이고(와일드카드 패턴에 맞는 `member/edit.jsp`도), 같은 정의의 다른 조각(header)의 전역도 이어지며, 서로 다른 정의의 본문끼리(`order/list.jsp` → `member/edit.jsp`의 함수)는 이어지지 않고, 패턴에 맞지 않는 JSP의 호출은 `resolution=global`이 아니다

### F-010-AC47 (대상: F-010-R27)
- Given `if (modeStr === "POST") { …콜백 안에서… requestOptions = { method: modeStr, … }; requestFetch(modeInfo.editURL, requestOptions) } else { requestOptions = { method: modeStr, … }; requestFetch(modeInfo.editURL, requestOptions) }`(`modeStr`은 `"POST"`/`"PUT"`, URL 후보 둘). 가지마다 `url`·`method`를 대입하고 그 가지에서 `fetch(url, { method })`를 하는 함수, 가지로 가를 수 없는 독립 후보, `verb !== 'DELETE'`·`'DELETE' == verb && type` 조건
- When 분석하면
- Then 앞 지점의 요청은 `POST`뿐, 뒤 지점은 `PUT`뿐이고(URL 후보는 둘 다), 가지마다 대입한 함수는 지점마다 그 가지의 URL·메서드 하나, 가를 수 없는 것은 URL마다 `ANY`, 부정·뒤집힌 비교와 `&&`도 좁혀진다

### F-010-AC48 (대상: F-010-R34)
- Given 아무 스크립트도 싣지 않는 페이지의 인라인 스크립트가 `onlyHere()`(JS 파일 하나에만 정의), `twice()`(두 파일에 정의), `inlineElsewhere()`(다른 페이지의 인라인에만 정의), `alert()`, `nowhere()`(정의 없음), `App.start()`(한 파일의 객체 멤버)를 호출한다. JS 파일과 ES 모듈도 `onlyHere()`를 호출한다
- When 분석하면
- Then `onlyHere`와 `App.start`로 가는 `CALLS`가 `INFERRED` + `loadUnverified=true`(`viaPage`·`resolution` 없음)로 있고(JS 파일의 호출도), `twice`·`inlineElsewhere`·`alert`·`nowhere`와 ES 모듈의 호출에는 엣지가 없으며, 그 페이지의 `CALLS_SKIPPED` 메시지에 "수신자를 알 수 없는 호출 1건"과 "정의가 여럿인 전역 이름 1건"이 있다

### F-010-AC49 (대상: F-010-R35)
- Given 페이지가 `js/app.min.js`와 `js/solo.min.js`(둘 다 압축본)를 싣고 `js/app.js`(같은 함수의 원본)만 프로젝트에 있다. 인라인 스크립트가 두 파일의 함수를 호출한다
- When 분석하면
- Then `app.min.js`는 `skipped=minified`이고, 페이지 → `js/app.js` `INCLUDES`(`INFERRED`, `via=script`, `minOf=js/app.min.js`)가 생기며 호출이 `app.js`의 함수로 이어지고, 원본이 없는 `solo.min.js`의 함수로는 이어지지 않는다

### F-010-AC50 (대상: F-010-R37, F-010-R38)
- Given `location = '${ctx}/x/list'`, `top.location`·`parent.location`·`document.location`·`window.location`·`self.location`에 대입, `top.$.jBox.open("iframe:${ctx}/x/pop?id=1", …)`, `$.jBox("iframe:/x/pop2/" + id)`, `$.jBox('get:/x/get')`·`'post:/x/post'`, `layer.open({ type: 2, content: '/x/layer' })`·`{ type: 1, … }`, 접두 뒤가 경로가 아닌 문자열(`"iframe: 쓰는 법"`, `"url:" + x`, jBox가 아닌 호출의 `'get:ok'`, `'html:…'`), `location = location.href`, `'get:/x/menu;JSESSIONID=' + sid`
- When 분석하면
- Then `location` 대입은 `api=navigation`·`GET` 요청이고, 접두 문자열과 layer는 `api=option`(`optionKey=arg`/`content`, `callee`, `INFERRED`)이며 `post:`만 `POST`이고, 경로가 아닌 것과 `type: 1`·새로 고침은 요청도 미해결도 없고, 세션 ID가 붙은 경로는 `/x/menu`다

### F-010-AC51 (대상: F-010-R38)
- Given `var url = "/pwm/openPopup.do?"; url = url + "fileUrl=" + f; url = url + "&popupId=" + id; window.open(url)`, `var ctx = "/"; window.open(ctx + "/twitter/login.do")`, `fetch('//cdn.example.com/lib/x')`, `$.get("${ctx}${url}${…}&extId=" + id + "&isAll=1")`, `location.href = '/x/view.do&id=' + id`
- When 분석하면
- Then 첫 지점의 요청은 `GET /pwm/openPopup.do` 하나뿐이고, 둘째는 `/twitter/login.do`(외부 아님), 셋째는 외부 URL 그대로, 넷째는 요청 노드 없이 미해결(`http-url`) 하나, 다섯째는 경로 `/x/view.do`·쿼리 `id={*}`이며, 어느 요청 ID에도 `&`나 `=`가 없다

### F-010-AC52 (대상: F-010-R36)
- Given `oryx.debug.js`, `app.bundle.js`, `lib.pack.js`, `foo-debug.js`, `admin/src/plugins/checkbix/js/checkbix.js`, `Author`·`Version`·`Licensed under the MIT License`가 든 머리 주석의 파일, 20만 자를 넘는 함수 6,000개짜리 파일, 한 줄로 압축한 1,300자 파일(이름에 `.min` 없음), 압축하지 않은 12줄짜리 `progress.min.js`, `admin/src/plugins/own.js`, 보통 파일
- When 분석하면
- Then 앞의 일곱은 `skipped=vendor`이고 함수 노드가 없으며, 한 줄 압축 파일은 `skipped=minified`, `progress.min.js`·`plugins` 바로 아래의 파일·보통 파일은 분석된다

### F-010-AC53 (대상: F-010-R39)
- Given 상태 `modeInfo`에 `{ method: "POST", editURL: "/schedule" }`와 `{ method: "PUT", editURL: "/schedule" }`를 넣고 `const editURL = modeInfo.mode === "M" ? `${modeInfo.editURL}/${props.id}` : modeInfo.editURL`를 요청 래퍼에 `{ method: modeInfo.method }`와 함께 넘기는 컴포넌트. 상태 `info`에 `{ method: "POST", url: "/board" }`·`{ method: "PUT", url: "/board/edit" }`를 넣고 `const u = props.id ? info.url + "/" + props.id : info.url; fetch(u, { method: info.method })`. 메서드가 다른 변수(`modeStr = c ? "POST" : "PUT"`)에서 오는 `fetch(info.url, { method: modeStr })`, 메서드를 모르는 `fetch(info.url, { method: props.m })`, 메서드가 정해진 요청
- When 분석하면
- Then 첫 지점의 요청은 `ANY /api/schedule`·`ANY /api/schedule/{*}`이고 둘 다 `httpMethod=ANY`·`httpMethods=[POST, PUT]`·`INFERRED`이며, 둘째 지점은 `POST /board`·`POST /board/{*}`·`PUT /board/edit`·`PUT /board/edit/{*}`뿐이고(`httpMethods` 없음), 셋째 지점은 URL마다 `ANY` + `httpMethods=[POST, PUT]`, 넷째 지점은 `ANY`이고 `httpMethods`가 없으며, 메서드가 정해진 요청에도 없다

### F-010-AC54 (대상: F-010-R40)
- Given 머리 주석이 `/*! Copyright … @author … @version 1.2.0 */`, `@Class Name … @version 1.0 Copyright (C) 2020 by ACME`, `Icons are licenced under a Creative Commons … License` + `@author`, `// … v1.0.0 (c) ACME`인 사내 스크립트. Apache 라이선스 문구가 있지만 폴더에서 혼자만 걸린 `own3.js`. 프로젝트 스크립트 옆의 `jquery.form.js`(MIT·GPL 문구)와 `widget.min.js`(압축돼 있지 않음, `MIT License`). 한 폴더에 `github.com` URL + 버전, SPDX 식별자, `@license`, BSD 재배포 조건, GPL 문구가 든 파일 다섯. `Released under the MIT license` 파일 둘 가운데 하나만 다른 스크립트가 최상위 함수 5개를 부르는 폴더. 폴더에 혼자 있는 라이선스 파일. 그리고 경로·이름·라이선스 근거가 섞인 프로젝트
- When 분석하면
- Then 사내 스크립트 넷과 `own3.js`, 함수 5개가 불리는 파일은 분석되고(`skipped`·`vendor` 없음), `jquery.form.js`·`widget.min.js`·다섯 파일·불리지 않는 파일·혼자 있는 파일은 `skipped=vendor`이며, `FILE_SKIPPED` 메시지가 `벤더 스크립트 1개 건너뜀(라이선스): <폴더>`, `…(경로): …`, `…4개 건너뜀(이름 2, 라이선스 2): …` 꼴이다

### F-010-AC55 (대상: F-010-R41)
- Given 페이지의 `formFields`가 `userManageVO.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do`, `#userManageVO.zip_url=…`, `userManageVO.cal_url=/sym/cmm/EgovNormalCalPopup.do?mode=1`, `#calUrl=…`이고 인라인 스크립트가 `window.open(document.userManageVO.zip_url.value)`, `var u = $('#calUrl').val(); window.showModalDialog(u, …)`, `location.href = document.getElementById('calUrl').value`, 표에 없는 필드, `$('#calUrl').val('x')`를 쓴다. 그 페이지와 다른 페이지(`frm2.zip_url`은 같은 값, `frm2.cal_url`은 다른 값)가 함께 싣는 JS 파일의 `function f(frm) { var url = frm.zip_url.value; window.showModalDialog(url, …) }`, `window.open(frm.cal_url.value)`, `window.open(frm.keyword.value)`. 어느 페이지도 싣지 않는 JS 파일의 `frm.zip_url.value`·`frm.cal_url.value`
- When 분석하면
- Then 인라인의 세 지점은 그 URL로 가는 `api=navigation` 요청이고(폼을 아는 식은 `RESOLVED`, `url`은 원문 식, 쿼리 `mode=1`), 표에 없는 필드와 값을 넣는 `val(x)`는 요청이 없으며, JS 파일의 `zip_url`은 요청 하나(`INFERRED`), `cal_url`은 두 값 모두, `keyword`는 미해결이고, 싣는 페이지를 모르는 파일은 프로젝트에 값이 하나뿐인 `zip_url`만 요청(`INFERRED`)이고 값이 갈리는 `cal_url`은 미해결이다

### F-010-AC56 (대상: F-010-R42)
- Given `<Route path="/admin/notice">`를 선언한 모듈의 컴포넌트가 `<img src={`/api/file/${props.id}`}>`, `<img src={logo}>`(import), `<img src="/assets/logo.png">`, `<a href="/api/schedule/export?type=1">`, `<a href="/admin/notice">`, `<a href="#!">`, 외부 URL·값을 모르는 식·상대 경로의 `<a>`, `<form action="/api/upload.do" method="post">`, `<form action={"/api/search"}>`, `<form action="/api/dyn" method={props.m}>`, 빈 `action`, `<iframe src="/report/view.do">`, `<Link to="/admin/notice">`, `<script src>`를 그린다
- When 분석하면
- Then 그 컴포넌트 함수에서 `GET /api/file/{*}`(`api=img`, 위치는 그 요소, `url`은 원문 식), `GET /api/schedule/export`(`api=link`, 쿼리 `type=1`, `RESOLVED`), `GET /admin/notice`(`api=link`, `clientRoute=true`), `POST /api/upload.do`·`GET /api/search`·`ANY /api/dyn`(`api=form`), `GET /report/view.do`(`api=frame`) 요청이 있고 `<Link>`는 종전대로 `api=route`이며, 나머지는 요청도 미해결도 없다

### F-010-AC57 (대상: F-010-R43)
- Given `<Route path={URL.MAIN}>`(`URL.MAIN`은 `/`)를 선언한 프로젝트의 `window.location.href = "/"`, `location.replace(URL.MAIN)`. 그리고 라우트 선언이 없는 프로젝트의 `location.href = "/"`, `location.href = ctx + "/"`, `location.href = u`(매개변수), `$.get("/")`
- When 분석하면
- Then 앞 프로젝트의 두 지점은 `api=navigation`·`clientRoute=true`인 `GET /` 요청이고 미해결이 없으며, 뒤 프로젝트의 앞 두 지점은 `clientRoute` 없는 `GET /`, 매개변수 이동과 `$.get("/")`는 종전대로 미해결이다

### F-010-AC58 (대상: F-010-R44)
- Given 치환 목록이 있는 인라인 스크립트의 `location.href = "${ctx}/sys/user/${t == 'a' ? 'form' : 'list'}?id=1"`, `$.post('${ctx}/oa/oaNotify/${oaNotify.self?"self":""}', {})`, `$.get('${ctx}/sys/role/${a ? b : 'x'}/view')`, 템플릿 리터럴 `` `${ctx}/sys/menu/${empty m.id ? 'add' : 'edit'}/` + id ``, `$.get('${ctx}/sys/dict/${dict.type}')`와, 치환 목록이 없는 인라인 스크립트의 같은 삼항
- When 분석하면
- Then 요청이 `GET /sys/user/form`·`GET /sys/user/list`(쿼리 `id=1`, `dynamic=false`, `url`은 원문, `INFERRED`), `POST /oa/oaNotify/self`·`POST /oa/oaNotify/`, `GET /sys/menu/add/{*}`·`GET /sys/menu/edit/{*}`이고, 갈래가 변수인 삼항과 삼항이 아닌 EL은 `{*}` 하나(`RESOLVED`)이며, 치환 목록이 없으면 `{*}` 그대로다

### F-010-AC59 (대상: F-010-R45)
- Given JS 파일의 `$('<div id="modalPan"></div>')`(다음 줄) `.html('<iframe style="border: 0px; " src="' + "<c:url value='/sym/cmm/EgovCcmZipSearchList.do'/>" +'" width="100%" height="100%"></iframe>')`, `<c:url value='/sym/log/InqireLoginLog.do?logId='/>" + logId`를 이어 붙인 것, 상수 `page`를 끼운 것, `innerHTML = "<a href='/board/view.do?id=" + id + "'>…</a> <a href='#'>…</a> <a href='javascript:…'>…</a>"`, 템플릿 리터럴을 넣는 `.append()`(`<a href="/board/${id}/edit">`, `<img src="/images/btn.gif">`, `<img src='/file/download.do?id=${id}'>`), `document.write('<form name="f" method="post" action="/board/save.do">…')`, `$('<form action="/board/excel.do"></form>')`, `insertAdjacentHTML('beforeend', '<a href=/board/more.do>…')`, 매개변수 `url`을 끼운 `<a href>`·`<img src>`, 외부 URL·상대 경로·빈 값의 `<a href>`, DOM에 넣지 않는 문자열, 태그가 없는 `.html()`, `.text('<a href=…>')`, `html += …`로 만든 변수를 넣는 `.html(html)`, `el.innerHTML += '<a href="/board/plus.do">…'`, 닫는 따옴표 없이 끝나는 `document.write("<img … src='" + imgpath + "menu_folder")`, 그 래퍼를 `fn_links(1, '/board/byWrapper.do')`와 `fn_links(2, 'home')`으로 부르는 함수. 그리고 인라인 스크립트의 `"${pageContext.request.contextPath}/uss/umt/cmm/EgovIdDplctCnfirmView.do?"`를 끼운 `<iframe src>`
- When 분석하면
- Then `api=frame`인 `GET /sym/cmm/EgovCcmZipSearchList.do`(위치는 `.html(…)`의 문자열 식이 있는 줄, `url`은 원문, `RESOLVED`), `GET /sym/log/InqireLoginLog.do`(쿼리 `logId={*}`), `GET /sym/prm/EgovProgramListSearchNew.do`가 있고, 링크·폼·이미지는 `GET /board/view.do`(`link`), `GET /board/{*}/edit`(`link`), `GET /file/download.do`(`img`), `POST /board/save.do`(`form`), `GET /board/excel.do`(`form`), `GET /board/more.do`(`link`), `GET /board/item.do`, `GET /board/plus.do`뿐이며(끝나지 않은 `<img src>`와 `'home'`을 넘긴 호출 지점에는 요청이 없다), 매개변수 `<a href>` 자리는 미해결(`http-url`, 원문은 그 문자열 식)이고 호출 지점에 `GET /board/byWrapper.do`(`api=link`, `via`=그 함수)가 생기며, 인라인 스크립트의 것은 `GET /uss/umt/cmm/EgovIdDplctCnfirmView.do`(`url`에 JSP 원문)다

### F-010-AC60 (대상: F-010-R44)
- Given 치환 목록이 있는 인라인 스크립트의 `var url = "${ctxStatic}/ckfinder/open.do?type=${ctype}&start=" + year + "/&action=js&dts=${type eq 'thumb'?'1':'0'}&sm=${selectMultiple?1:0}"; window.open(url);`와 `location.href = "${ctx}/oa/notify/${self ? 'self' : 'all'}/list?own=${self ? '1' : '0'}"`
- When 분석하면
- Then 앞은 요청 `GET /ckfinder/open.do` 하나(쿼리 `type={*}&start={*}/&action=js&dts={*}&sm={*}`, `RESOLVED`)이고, 뒤는 경로의 삼항만 펼친 `GET /oa/notify/self/list`·`GET /oa/notify/all/list`(쿼리 `own={*}`, `INFERRED`)다

## 5. 가정
- 파서는 Maven Central의 tree-sitter-ng 바인딩(`io.github.bonede:tree-sitter` 0.26.6, `tree-sitter-javascript` 0.25.0, `tree-sitter-typescript` 0.23.2, `tree-sitter-tsx` 0.23.2)이다. 네이티브 라이브러리는 jar의 `lib/` 리소스에 들어 있고 실행 때 임시 파일로 풀어 `System.load`로 올린다(네트워크 불필요, shade한 단일 jar에서도 같은 방식).
- 지원 OS·CPU는 바인딩이 싣고 있는 범위다: Linux(glibc) x86_64·aarch64, macOS x86_64·aarch64, Windows x86_64. 그 밖(Alpine musl, Windows arm64 등)에서는 `JS_MODULE` 노드만 만들고 `PARSE_FAILED`(ERROR) 진단 1건을 남긴 뒤 JS 분석을 건너뛴다(다른 분석기는 그대로 돈다).
- JDK 24에서는 `System.load` 호출에 대해 "restricted method" 경고가 표준 오류로 나온다(동작은 한다). `--enable-native-access=ALL-UNNAMED`를 주면 사라진다.
- `.js` 파일도 JSX를 받는다(javascript 문법이 JSX를 포함). Flow 타입 주석은 구문 오류로 처리된다.
- 클래스는 컴포넌트가 아니어도 `kind=class`인 `JS_FUNCTION` 노드를 만든다(메서드의 부모, `new`·JSX의 대상).
- 최상위 코드와 모듈 수준 콜백의 호출·요청은 `JS_MODULE`에서 나가는 `CALLS`·`REQUESTS`로 기록한다.
- 함수 참조를 넘기는 것(`callType=ref`)도 `CALLS`로 기록한다. React 핸들러와 jQuery 콜백이 콜트리에서 끊기지 않게 하기 위해서다. `f.bind(x)`도 실행이 아니라 참조다(F-010-R13). `ref`의 대상이 클래스이거나 자기 자신이면 기록하지 않는다.
- `viaProp` 엣지는 항상 `INFERRED`다. 어느 부모가 그린 자식인지는 실행해야 알 수 있어, 그 컴포넌트를 쓰는 모든 부모가 넘긴 함수를 후보로 잇기 때문이다.
- `CALLS_SKIPPED`의 위치는 파일이고 줄은 메시지에 적는다(파일 단위 집계이기 때문). 요청으로 기록된 호출(`$.ajax(...)` 자체)은 생략이 아니다.
- `HTTP_REQUEST`의 속성 키는 jsp(F-011)와 같다: `path`는 정규화한 URL 패턴(baseURL 반영, 컨텍스트 경로 제거, 쿼리·fragment 제외, 동적 조각 `{*}`. 노드 ID의 URL과 같고 링커가 매칭에 쓴다), `url`은 URL 식의 원문(120자까지. 인라인 스크립트면 JSP에 쓴 그대로, F-010-R14), `query`, `httpMethod`, `dynamic`, `api`, `external`, `relative`(`/`로 시작하지 않는 상대 URL). 요청 노드와 `REQUESTS` 엣지의 위치는 호출 식이 시작하는 줄이다.
- 위치(`SourceLocation`)의 끝은 마지막 문자의 다음 자리(제외)다.
- URL 맨 앞의 동적 조각은 이름에 `ctx`나 `context`가 들어간 변수·함수 호출(`ctx + '/a'`, `getContextPath() + '/a'`)일 때만 컨텍스트 경로로 보아 뗀다. 그 밖의 동적 접두사는 `{*}/a`로 남긴다(알 수 없는 접두사).
- `var`·`let` 변수도 대입이 한 번뿐이면 상수처럼 따라간다. 대입이 여러 번이면 각 값을 후보로 보고 요청을 후보마다 만들되 `REQUESTS` 엣지를 `INFERRED`로 둔다(흐름을 보지 않기 때문).
- 모듈 문법이 없는 JS 파일끼리의 전역 호출은 두 파일을 함께 싣는 페이지가 있을 때 그것을 근거로 `INFERRED`로 기록한다(F-010-R18). 종전의 "프로젝트 전체에서 이름으로 찾기"는 공개 프로젝트에서 오탐을 대량으로 만들어(`$(…)` 1,972건이 `prototype.js#$`로) 폐기했다(2026-10-11). 3차(2026-10-11)에서 좁은 형태로 되살렸다(F-010-R34): 근거를 못 찾은 **호출**에 한해, 내장·라이브러리 이름이 아니고 정의가 프로젝트에 정확히 하나이며 JS 파일에 있을 때만 잇고 `loadUnverified=true`로 구분한다. 동적 로더(`loadScript(…)`)나 `<c:import>`로 끼워진 화면처럼 함께 싣는 근거를 정적으로 얻을 수 없는 맞는 호출이 조용히 빠지는 것(zheng 61, jeesite 약 80)이 더 나쁘다고 판단했다.
- `tsconfig.json`·`jsconfig.json`은 import하는 파일의 디렉터리에서 프로젝트 루트까지 올라가며 가장 가까운 것을 쓴다. `paths`가 맞지 않고 설정 파일이 있으면 관례 `@/` → `<설정 파일 폴더>/src/`를 시도한다. `extends`는 따라가지 않는다.
- 압축 파일 판정: 이름이 `.min.js`·`-min.js`로 끝나거나, 20KB 이상이면서 평균 줄 길이가 500자를 넘는다. 진단 코드는 `FILE_SKIPPED`(INFO)다. 벤더 폴더 안의 압축본은 `skipped=vendor`다(경로 규칙이 먼저다).
- (4차에서 바꿈, F-010-R40) 벤더 판정의 머리 주석 규칙은 저작권 표기·`@author`·`@version`·버전 번호·`/*!`를 근거로 쓰지 않는다. 정식 라이선스 문구·`@license`·라이선스 주소·(버전과 함께 적힌) 프로젝트 URL만 근거이고, 그것도 벤더 "후보"일 뿐이라 프로젝트 정합성 검사를 거친다. 종전 규칙("저작권 + 라이선스 낱말", "`/*!` + 버전 번호", "라이선스 문구 + Author·Version")은 사내 스크립트(`/*! Copyright … @author … @version 1.2.0 */`), 출처를 적어 둔 프로젝트 파일(jeesite의 `ProcessDiagramCanvas.js`: `Icons are licenced under a Creative Commons …` + `@author`)을 벤더로 만들었다. 프로젝트가 자기 저장소 주소를 저작권 표기에 적는 실제 사례(jeesite의 `Copyright &copy; … <a href="https://github.com/thinkgem/jeesite">JeeSite</a> All rights reserved.`)가 있어 프로젝트 URL에는 조건을 붙였다.
- `httpMethods`는 `httpMethod=ANY`인 요청에만 붙는다(F-010-R39). `httpMethod`는 계약상 단일 값이라 그대로 두고, 링커가 대응을 좁히는 데 쓰는 보조 정보다. `ANY` + `httpMethods=[POST, PUT]` 노드 하나는 "이 URL로 POST나 PUT을 보낸다(어느 쪽인지 모른다)"는 뜻이지 둘 다 보낸다는 뜻이 아니다. 메서드마다 노드를 만들지 않는 것은 한쪽이 소스에 없는 요청(`PUT /schedule`)일 수 있어서다.
- JSX 요소의 서버 요청(F-010-R42)은 "서버 경로 꼴"로만 가린다. 선언되지 않은 클라이언트 경로로 가는 `<a href="/about">`도 `api=link` 요청으로 남는다(`location.href = '/about'`을 `api=navigation`으로 남기는 것과 같다).
- 루트 `/`로 가는 화면 이동(F-010-R43)은 라우트 선언이 없으면 서버(앱)의 루트로 가는 요청이다. 링커가 `/`를 처리하는 엔드포인트가 있으면 잇는다.
- 벤더 스크립트를 건너뛰는 것이 기본이다. 필요하면 `web.skipVendor=false`로 끈다. 벤더로 잘못 판정된 프로젝트 파일은 `FILE_SKIPPED` 진단(폴더 단위)과 모듈의 `skipped=vendor`로 드러난다.
- (폐기, 2026-10-11 3차) ~~메서드 후보가 여럿이고 URL 후보도 여럿이면 조합마다 요청을 만든다.~~ 조합을 곱하면 소스에 없는 요청(`PUT /board`, `POST /board/{*}`)이 생기고 그것이 엔드포인트와 맞아 틀린 `MATCHES`까지 만든다(공개 프로젝트에서 가짜 요청 34건, 틀린 추론 `MATCHES` 6건). 같은 객체에서 온 것은 짝짓고, 짝을 모르면 URL마다 `ANY` 하나로 둔다(F-010-R27). `ANY`는 같은 경로의 다른 메서드 엔드포인트와도 이어질 수 있지만 "모른다"는 사실 그대로다.
- `api=option` 요청의 메서드는 옵션에 적힌 값이 없으면 알려진 플러그인의 기본값이다(F-010-R32. 2026-10-11 3차에서 바꿈: 종전에는 채우지 않고 `ANY`였다). 기본값은 그 플러그인의 문서에 적힌 값이고 공개 프로젝트의 컨트롤러 매핑과 대조했다(zheng: bootstrapTable 15건이 `GET`, zTree 3건이 `POST` 매핑). jquery-confirm의 `content: 'url:…'`는 `GET`이다(그 라이브러리가 `$.get`으로 읽는다).
- `api=route` 요청과 `clientRoute=true`인 요청은 서버 요청이 아니다. 그래도 `HTTP_REQUEST`로 남기는 것은 화면 전환도 "이 함수가 어디로 보내는가"의 일부이기 때문이다. 링커는 이 요청을 엔드포인트와 맞추지 않고 미해결(`http-endpoint`)로도 남기지 않는다(F-012의 몫).
- `location` 이동의 `clientRoute` 판정은 `<Route path>` 선언이 근거다. 같은 프로젝트에 같은 경로의 서버 엔드포인트가 함께 있는 구성(한 저장소에 SPA와 서버)에서는 그 요청이 서버로 갈 수도 있지만, 그 경로를 클라이언트 라우트로 선언했다면 화면은 SPA가 그린다고 본다.
- `showModalDialog`의 판정은 이름으로 한다. 그 이름을 프로젝트가 다시 선언해 같은 페이지가 싣고 있으면 그 함수가 먼저다(대체 구현이 `window.open`으로 열면 요청 래퍼 전파로 같은 요청이 생긴다).
- 큰 생성물 규칙(20만 자, `function` 200번)은 낱말을 세는 것이지 구문을 보지 않는다. 그 크기의 손으로 쓴 프로젝트 스크립트가 있으면 `web.vendorPatterns=!<glob>`로 되살린다.
- `el.onclick = function(){}`처럼 이름이 `on` + 소문자인 프로퍼티에 대입한 함수는 이벤트 핸들러 등록으로 보고 이름 없는 콜백처럼 다룬다(노드를 만들지 않는다).
- 인라인 스크립트가 주인 페이지에 없는 스크립트의 전역을 부르면, 그 JSP를 include하는 페이지가 그 스크립트를 함께 싣는 경우에만 `INFERRED`(`resolution=global`, `viaPage`)로 기록한다. 후보가 8개를 넘으면 기록하지 않는다(F-010-R18).
- `window.x = 값`은 그 모듈의 전역 `x` 선언으로 본다(`location` 등 창 속성 제외). `window.f()`는 전역 `f()`와 같다.
- `location.href = '#'`, `'javascript:…'`, `mailto:` 등은 요청이 아니므로 조용히 건너뛴다. 폼 `action` 대입과 `$(sel).load(x)`는 URL에 정적 조각이 있을 때만 요청으로 본다(같은 모양의 다른 코드가 많아 미해결로도 남기지 않는다).

## 6. 미확정 사항
- 없음. 사용자가 미확정 사항은 기본값으로 진행하라고 위임했다(2026-10-11). 위 가정은 검토가 필요하다.

### 알려진 한계
- 타입 정보가 없다. `x.foo()`에서 `x`가 매개변수이거나 무엇인지 알 수 없으면 호출을 기록하지 않는다(추측하지 않는다). 함수 인자로 넘어온 객체, 배열 원소, `Map`에 담은 함수, 프레임워크가 주입하는 값(Vue의 `this.$refs`)을 통한 호출이 여기에 든다. 이 가운데 매개변수·props로 받은 **함수**의 호출만 건수를 `CALLS_SKIPPED`로 남긴다(F-010-R16). 받은 객체의 메서드 호출(`opts.success(res)`, `param.go()`)은 DOM·데이터 객체의 메서드와 구분할 수 없어 세지도 않는다.
- props로 받은 함수는 한 경우만 잇는다(F-010-R15): 함수 컴포넌트가 직접 **호출**하는 지점 ← JSX 부모가 프로젝트 함수 참조를 넘김. 다음은 잇지 않고 건수만 센다: 클래스 컴포넌트의 `this.props.onSave()`(`const { onSave } = this.props`로 꺼내 부른 것은 세지도 않는다), 받은 prop을 다시 넘기는 것(`<button onClick={onBack}>`, 손자에게 내려 주는 prop drilling. 부모가 자기 prop을 넘긴 경우 그 위 조상까지 따라가지 않는다), 부모가 넘긴 인라인 함수, 스프레드(`<Child {...handlers} />`)·`React.cloneElement`·컨텍스트로 넘긴 함수, 훅이나 일반 함수가 옵션 객체로 받은 콜백.
- 매개변수 콜백(`ajaxPost(url, data, callback)`의 `callback`)을 호출하는 쪽이 넘긴 함수와 잇지 않는다. 건수만 남긴다. 요청 래퍼의 URL 매개변수만 호출 지점으로 전파한다(F-010-R11).
- `x = f.bind(…)` 뒤의 `x()`를 따로 추적하지 않는다. 변수·프로퍼티에 한 번만 대입한 경우에는 기존 값 평가(대입이 한 번인 변수 따라가기)로 `x()`가 `f` 호출로 풀리지만, 여러 번 대입하거나 인자로 넘긴 뒤 호출하는 것은 풀리지 않는다. 부분 적용한 인자(`ajaxPost.bind(null, '/x')`)는 요청 래퍼 전파에 쓰지 않는다.
- 대입으로 등록하는 핸들러(`el.onclick = f`, `window.onload = f`)는 `ref`로도 기록하지 않는다. JSX 속성의 객체 안에 넣은 함수(`options={{ onRowClick: f }}`)도 같다.
- 내장 소스의 함수 이름에 JSP 식이 섞이면(`function ${prefix}Open() {}`) 이름과 노드 ID의 그 자리가 `{*}`다(F-010-R25). 접두사만 다른 두 함수(`${a}Open`, `${b}Open`)는 같은 ID가 되어 한 노드로 합쳐진다. 원문(`${prefix}Open`)은 싣지 않는다.
- (해소, 2026-10-11 3차) ~~레이아웃 프레임워크가 붙이는 레이아웃 JSP와 내용 JSP 사이에는 `INCLUDES`가 없어 "함께 싣는 페이지"를 알 수 없다.~~ jsp가 SiteMesh·Tiles 관계를 기록하고(F-011-R21·R22) web이 그것으로 전역을 잇는다(F-010-R33). 다른 모듈의 자원을 `/resources/<모듈>/…`로 싣는 `<script src>`도 jsp가 경로 접미사로 푼다(F-011-R23). 남은 것: SiteMesh의 URL 패턴(`<pattern>/admin/*</pattern>`)이 여럿일 때 `meta`가 없는 페이지, Java 설정으로 만든 SiteMesh 3·Tiles 구성, 동적 로더(`loadScript(ctx + '/js/x.js')`), `<c:import url="….do">`로 끼워진 화면이 싣는 스크립트는 "함께 싣는 근거"가 없다(그 호출은 `loadUnverified` 규칙으로만 이어진다).
- 메서드와 URL의 짝(F-010-R27)은 같은 객체의 프로퍼티(또는 거기서 파생된 식, F-010-R39)일 때만 안다. (4차에서 고침) ~~`const editURL = c ? `${modeInfo.editURL}/${id}` : modeInfo.editURL`처럼 URL을 다시 조립하면 짝을 잃고 `ANY`가 된다.~~ 파생된 식은 리터럴마다 평가해 짝짓는다. 다만 삼항의 조건(`modeInfo.mode === "M"`)이 어느 리터럴과 맞는지는 보지 않으므로, 두 리터럴의 URL이 같으면 그 URL들에 두 메서드가 모두 모여 `ANY` + `httpMethods`가 된다(실제는 `POST /schedule`, `PUT /schedule/{id}`뿐이어도 `ANY /schedule`·`ANY /schedule/{*}` 둘 다 `[POST, PUT]`). 함수 호출로 만든 URL(`buildUrl(modeInfo.editURL)`)은 파생으로 보지 않는다. 분기 좁히기는 `if`의 `then`/`else` 가지와 `x === '리터럴'` 조건만 본다: `switch`, 조기 `return`, 다른 변수와 엮인 조건(`modeStr`은 `POST`로 좁혀도 `modeInfo.editURL`의 후보는 그대로다)은 보지 않는다. 그래서 가지마다 호출 지점이 있는 화면에서 URL 후보 둘 가운데 하나는 여전히 소스에 없는 조합이다(공개 프로젝트에서 6개 지점).
- 클라이언트 라우트(F-010-R30): Vue의 `this.$router.push(…)`는 그 파일이 `vue-router`를 import할 때만 본다(`.vue` 단일 파일 컴포넌트는 분석 대상이 아니다). 객체로 선언한 라우트(`createBrowserRouter([{ path }])`, Vue의 `routes: [{ path }]`), 부모 라우트에 상대적인 `<Route path="notice">`, 이름으로 가는 이동(`{ name: 'user' }`)은 읽지 않는다. 상대 경로 이동(`navigate('detail')`)은 `relative=true`로 기록한다.
- `loadUnverified` 엣지(F-010-R34)는 그 스크립트가 정말 함께 실리는지 확인하지 못한 것이다. 이름이 우연히 같은 다른 화면의 함수로 이어질 수 있다(정의가 하나뿐일 때만 잇는 것으로 줄였다). 함수 참조(`setTimeout(tick)`)와 전역 상수에는 적용하지 않는다.
- 플러그인의 기본 메서드(F-010-R32)는 피호출자 이름으로만 고른다. 같은 이름의 다른 플러그인이나 전역 기본값을 바꾼 프로젝트(`$.fn.bootstrapTable.defaults.method = 'post'`)는 알 수 없다.
- 접두 문자열 인자(F-010-R37)의 `iframe:`·`url:`은 피호출자를 가리지 않는다. 그 문자열을 URL로 쓰지 않는 함수에 넘긴 것도 값이 서버 경로 모양이면 `api=option` 요청(추론)이 된다. 벤더 문서 페이지를 여는 것(`iframe:${ctxStatic}/jquery-jbox/2.3/docs/jbox-demo.html`)도 요청으로 남는다.
- 이름이 `<이름>.<점 있는 버전>.min.js`인 프로젝트 파일(zheng의 `zheng.jprogress.0.1.min.js`)은 내용이 압축돼 있지 않아도 벤더 판정의 파일 이름 규칙(3)에 걸려 `skipped=vendor`다. 분석하려면 `web.vendorPatterns=!**/zheng.jprogress.*`처럼 되살린다.
- 요청 래퍼가 옵션 객체를 받아 프로퍼티를 꺼내 쓰는 형태(`function call(opts) { $.ajax({ url: opts.url }) }`)는 래퍼로 전파하지 않는다. 그 호출 지점은 `api=option` 요청(추론)으로만 잡힌다.
- `api=option` 요청은 그 호출이 정말 요청을 보내는지 모르는 추론이다. 프로젝트 함수에 넘긴 설정 객체의 `url`도 잡힌다. JSX 속성의 객체(`options={{ url }}`), 스프레드, 두 단계 넘게 중첩된 객체, `server`·`uploadUrl`·`editurl` 같은 다른 키는 읽지 않는다.
- 벤더 판정의 정합성 검사(F-010-R40): 모든 스크립트에 같은 라이선스 헤더를 붙인 오픈소스 프로젝트는 한 폴더의 파일이 전부 후보가 되어 "혼자만 걸렸다"로 되살아나지 못한다(최상위 함수 5개 이상이 불리는 파일만 되살아난다). `web.vendorPatterns=!<glob>`로 되살린다. 반대로, 프로젝트 스크립트 폴더에 혼자 놓인 이름 모를 라이브러리(`js/somelib.js`, 라이선스 배너)는 분석 대상이 된다(이름이 `jquery.*`·`*.min.js`·`<이름>-<버전>` 꼴이면 벤더로 남는다). 라이선스 문구도 URL도 없는 배너(`/*! Foo v1.2.3 */`)의 라이브러리는 머리 주석으로는 걸리지 않는다. 함수 호출 세기는 이름으로 부른 최상위 함수만 본다(`App.util.x()` 같은 네임스페이스 호출은 세지 않는다).
- 폼 필드의 URL(F-010-R41): 필드 표에 있는 것(값이 정적 경로인 hidden·text input)만 읽는다. 폼이 매개변수면 필드 이름만으로 찾으므로 같은 이름의 필드를 가진 다른 폼의 값이 후보에 섞일 수 있다(`INFERRED`). 스크립트가 값을 바꾼 필드(`frm.zip_url.value = …` 뒤에 읽기), `$('[name=zip_url]').val()`·`$(frm).find(…)` 같은 다른 선택자, `form.elements['x'].value`는 읽지 않는다. Prototype.js의 `new Ajax.Request(url, …)`처럼 요청 API로 보지 않는 호출의 URL은 필드를 풀어도 요청이 되지 않는다.
- JSX 요소의 서버 요청(F-010-R42): `<a>`·`<area>`·`<form>`·`<img>`·`<iframe>`·`<frame>`만 본다. 스프레드 속성(`<a {...props}>`), 컴포넌트로 감싼 링크(`<Button href>`), `<form onSubmit>`에서 보내는 요청(그 핸들러의 `fetch`가 요청이다), `formAction` 속성은 보지 않는다.
- 인라인 스크립트의 EL 삼항(F-010-R44)은 문자열·템플릿 리터럴 안의 EL만 펼친다. 문자열 밖의 EL(`var u = ${c ? "'/a'" : "'/b'"}`), `<c:choose>`로 가른 스크립트, JS 파일에 적힌 EL(F-010-R31)은 종전대로다.
- (5차에서 해소) ~~JS 문자열로 조립한 태그 안의 URL(`'<iframe src="' + ctx + '/x.do">'`, `'<a href="…">'`, `'<img src="<c:url …/>">'`)은 읽지 않는다.~~ DOM에 넣는 자리의 문자열은 읽는다(F-010-R45). 남은 것: (1) DOM에 넣는 자리가 아닌 문자열은 읽지 않는다. 표 플러그인의 formatter가 `return '<a href="…">'`로 돌려주는 링크, 템플릿 함수가 만들어 다른 함수에 넘기는 HTML, `.prepend()`·`.after()`·`.before()`·`.replaceWith()`·`outerHTML`이 그렇다. (2) `.html()`·`.append()`는 수신자가 jQuery 객체인지 보지 않는다(같은 이름의 다른 메서드에 URL 태그가 든 문자열을 넘기면 요청이 된다). (3) 태그가 여러 문장에 걸쳐 조립되면(`document.write("<a href='")` 뒤에 `document.write(url + "'>")`) 첫 문장에 적힌 만큼만 읽는다. (4) 값이 통째로 동적인 `<a href>`(메뉴 URL을 데이터로 그리는 화면)는 미해결 `http-url`로 남는다. (5) `<script src>`·`<link href>`·`<video>` 등 다른 요소와 `onclick="location.href='…'"` 같은 속성 안의 코드는 읽지 않는다. (6) `$(H)`의 둘째 인자로 주는 속성 객체(`$('<a>', { href: '/x' })`)와 `document.createElement` 뒤의 `el.src = …`(iframe)는 읽지 않는다.
- JSX 텍스트 안의 `&&`(`<p>a && b</p>`)는 여전히 구문 오류다. 홑 `&`만 보정한다. 파서 입력 보정은 문자열·주석 안의 같은 글자도 바꾸지만 원문은 원래 소스에서 읽으므로 결과에는 드러나지 않는다. `&`를 `|`로 보이게 한 탓에 이항 연산자 `&`의 종류는 트리에서 `|`로 읽힌다(분석은 두 연산자를 쓰지 않는다).
- `using` 뒤에 이름이 바로 오는 꼴(`using in obj`, `using instanceof X`)은 보정하지 않는다.
- EL이 코드 조각을 만들어 내는 인라인 스크립트(`{ ${cond ? "\"a\":1," : ""} "b": 2 }`)와 `<script>` 안에 HTML 템플릿을 주석 트릭으로 넣은 것(`<!--/*-->` … )은 구문 오류로 남는다.
- 엔티티를 풀어야 했던 이벤트 핸들러의 `url`은 치환한 자리만 JSP 원문이고 나머지는 엔티티를 푼 글자다(`&amp;` → `&`).
- 흐름을 보지 않는다. 변수 값은 선언과 대입 식만 모아서 본다. 조건에 따라 달라지는 값은 후보 전부가 요청이 된다(`INFERRED`).
- URL을 만드는 함수 호출은 따라가지 않는다(`buildUrl('a', 'b')`, `[a, b].join('/')` → `{*}`). 이름에 `ctx`·`context`가 든 호출만 컨텍스트 경로로 본다.
- `axios.defaults.baseURL = …`, 인터셉터에서 바꾸는 URL, 요청 config의 `params`는 반영하지 않는다. `axios.create`의 `baseURL`만 본다.
- 요청으로 보는 API는 3절의 표가 전부다. `ky`, `superagent`, `useSWR`, `react-query`, GraphQL 클라이언트, WebSocket은 다루지 않는다. 클라이언트 라우터 이동은 `api=route`로 따로 기록한다(F-010-R30. 2026-10-11 3차). 받는 쪽을 모르는 HTTP 클라이언트 변수(`http.get(url)`에서 `http`가 매개변수)도 요청으로 보지 않는다(`$http`, `$axios`, `this.$http`는 axios처럼 본다).
- `form.action = url`의 폼을 알 수 없으면(`form`이 매개변수, `document.forms[0]`, `this.form`, 표에 없는 이름) 메서드는 `ANY`다. `$(f).attr('method', …)`로 바꾼 메서드와 다른 함수에서 대입한 `.method`는 보지 않는다.
- Vue 단일 파일 컴포넌트(`.vue`), Flow 타입 주석, 번들러 별칭(webpack·vite), `tsconfig.json`의 `extends`, AMD(`define([...], fn)`)의 의존 배열은 다루지 않는다.
- 같은 이름으로 다시 선언한 함수는 한 노드로 합친다(`<c:choose>` 갈래가 모두 남은 인라인 스크립트 포함).
- 계산된 키(`obj[key] = function(){}`), `Object.assign(Foo.prototype, {...})`, `$.extend(App, {...})`로 붙인 멤버는 이름을 주지 못한다.
- 데코레이터 안의 식은 분석하지 않는다.
- tree-sitter-typescript 0.23.2는 `await f<T>(x)`의 피호출자를 `await` 식으로 준다. 분석 쪽에서 벗겨서 쓴다.

### 그래프 계약에 추가를 제안하는 것
design.md에 반영됨(2026-10-11): 아래 항목은 F-001 design.md에 있다. 이번 라운드의 것은 3.4(`callType` 표의 `ref`에 `f.bind(x)`), 3.5(공통 규칙의 `url`, CALLS (JS)의 `viaProp`), 3.6(`http-url` 원문), 3.7(`CALLS_SKIPPED`), 3.9(`jspSubstitutions`), 7절(함수 이름에 남는 `__JSP_EXPR__`, props 콜백을 한 형태만 잇는 한계)에 넣었다. 마지막 항목의 "링커가 정해야 할 것"은 3.9의 `{*}` 행에 정해져 있다.

design.md에 반영됨(2026-10-11, 2차): 아래 여덟 항목은 F-001 design.md에 있다. 옵션 키는 3.8, `skipped=vendor`·`vendor`·`dynamicName`·`api=option`·`optionKey`·`callee`·`viaPage`·`resolution`의 새 뜻은 3.5(공통 규칙 "벤더(서드파티) 파일"과 attrs 표), 함수 이름의 `{*}`는 3.3·3.9, `FILE_SKIPPED`는 3.7, `formMethods`는 3.5(JSP_PAGE)와 3.9, 한 호출 지점의 여러 요청은 3.3, 전역 호출·`api=option`·벤더 판정의 한계는 7절에 넣었다.

- 3.8 옵션 키: `web.skipVendor`(`true`/`false`, 기본 `true`. web·jsp가 읽는다), `web.vendorPatterns`(쉼표로 구분한 glob, 기본 없음. `!`로 시작하는 glob은 "벤더가 아니다". web·jsp가 읽는다)
- 3.5 `JS_MODULE` attrs: `skipped` 값 `vendor` 추가, `vendor`(boolean. 벤더로 판정했지만 `web.skipVendor=false`라 분석한 모듈)
- 3.5 `JS_FUNCTION` attrs: `dynamicName`(boolean). 3.3·3.9: `JS_FUNCTION`의 ID·이름에도 `{*}`가 쓰인다(함수 이름에 섞인 JSP 식). 7절의 "함수 이름에 남는 `__JSP_EXPR__`" 항목은 해소됐다
- 3.5 `HTTP_REQUEST` attrs: `api` 값 `option`(요청 API가 아닌 호출의 옵션 객체에서 읽은 URL. `REQUESTS`는 항상 `INFERRED`), `optionKey`(`url`, `ajax`, `ajax.url`, `async.url`, `content`), `callee`(피호출자 식 원문)
- 3.5 `CALLS`(JS) attrs: `viaPage`(두 스크립트를 함께 싣는 페이지의 노드 ID). `resolution=global`의 뜻이 "프로젝트 전체에서 찾음"에서 "두 스크립트를 함께 싣는 페이지를 근거로 다른 스크립트에서 찾음"으로 바뀐다
- 3.7 `FILE_SKIPPED`: 벤더 스크립트·벤더 페이지를 건너뛴 것도 이 코드다(폴더마다 1건. web, jsp)
- web이 읽는 `JSP_PAGE` attrs: `formMethods`(jsp가 넣는다. F-011 spec "폼 표")
- 한 호출 지점에서 메서드만 다른 `HTTP_REQUEST`가 둘 이상 생길 수 있다(ID의 METHOD가 다르다)

**3차(2026-10-11) — design.md에 반영이 필요하다(이 라운드에서는 design.md를 고치지 않았다. 공유 문서라 담당자가 반영한다)**

design.md 반영됨(2026-10-11): `clientRoute`·`api=route`·`optionKey=arg`는 3.5 노드 attrs 표(HTTP_REQUEST), `loadUnverified`와 `viaPage`의 Tiles 경우는 3.5 엣지 attrs 표(CALLS (JS)), web이 만드는 `INCLUDES`의 `minOf`는 3.4·3.5, `via=sitemesh|tiles`와 `tilesDefinitions`·`tilesAttributes`는 3.4·3.5·3.9, `CALLS_SKIPPED`는 3.7, URL 후보마다 노드 하나는 3.3(HTTP_REQUEST), 벤더 판정은 3.5 공통 규칙 "벤더(서드파티) 파일", 한계는 7절(전역 호출 행을 해소로 고치고 클라이언트 라우트 행을 더했다)에 넣었다.

- 3.5 `HTTP_REQUEST` attrs: `clientRoute`(boolean. 서버 요청이 아니라 클라이언트 라우터가 처리하는 화면 전환. web이 넣고 링커가 읽어 매칭 대상에서 빼며 미해결도 남기지 않는다), `api` 값 `route`(라우터 이동·링크. 항상 `clientRoute=true`). `api=navigation`인 요청에도 `clientRoute=true`가 붙을 수 있다(경로가 선언된 `<Route path>`와 같을 때)
- 3.5 `HTTP_REQUEST` attrs: `optionKey` 값 `arg`(옵션 객체가 아니라 `iframe:`·`url:` 접두 문자열 인자에서 읽음)
- 3.5 `CALLS`(JS) attrs: `loadUnverified`(boolean. 함께 싣는 근거 없이 "그 이름의 정의가 프로젝트에 하나뿐"이라는 것만으로 이은 전역 호출. 항상 `INFERRED`이고 `resolution`·`viaPage`가 없다). `resolution=global`의 뜻은 그대로다(함께 싣는 페이지가 확인됨). `viaPage`는 Tiles 정의로 묶인 경우 template 페이지의 ID다
- 3.4·3.5 `INCLUDES`(JSP_PAGE→JS_MODULE): web도 만든다(`minOf`가 붙은 것. 압축본을 싣는 페이지를 그 원본 스크립트로 이음). attrs `minOf`(압축본의 경로: web이 만든 것은 프로젝트 기준 파일 경로, jsp가 만든 것은 정규화한 src 경로)
- web이 읽는 것: `INCLUDES`의 `via` 값 `sitemesh`·`tiles`(jsp가 만든다), `FILE` 노드의 `tilesDefinitions`·`tilesAttributes`(jsp가 만든다. 형식은 F-011 spec "Tiles")
- 3.7 `CALLS_SKIPPED`: 메시지에 "정의가 여럿인 전역 이름 n건"이 더해질 수 있고(전체 건수에 포함), 그런 건만 있는 모듈에도 진단이 난다
- 3.3: 한 호출 지점에서 메서드가 `ANY`인 `HTTP_REQUEST`가 URL 후보마다 하나씩 생길 수 있다(메서드와 URL의 짝을 모를 때). "메서드 × URL 조합마다 요청"은 없어졌다
- 3.5 공통 규칙 "벤더(서드파티) 파일": `plugins/<이름>/…`, 빌드 산출물 이름(`.debug.`, `.pack.`, `.bundle.`), 큰 생성물, 내용으로 본 압축 판정이 더해졌다(F-010 spec "벤더 판정")
- 7절(한계): "레이아웃이 싣는 스크립트의 전역을 잇지 못한다"는 해소. 클라이언트 라우터 이동은 `api=route`로 기록한다

**4차(2026-10-11) — design.md에 반영이 필요하다(이 라운드에서도 design.md는 고치지 않았다)**

design.md 반영됨(2026-10-11): `httpMethods`는 3.3(HTTP_REQUEST)·3.5(공통 규칙 "요청의 메서드 후보", 노드 attrs 표), `api`의 `img`와 web이 만드는 `link`·`form`·`frame`, `GET /`는 3.3·3.5, `formFields`는 3.5(JSP_PAGE)와 3.9, `FILE_SKIPPED` 메시지는 3.7, 라이선스 판정은 3.5 공통 규칙 "벤더(서드파티) 파일", 한계는 7절(벤더 판정 행, 메서드와 URL의 짝 행)에 넣었다.

- 3.5 `HTTP_REQUEST` attrs: `httpMethods`(List<String>. `httpMethod=ANY`인 요청의 후보 메서드, 대문자. web이 넣고 링커가 읽어 그 메서드의 엔드포인트와 메서드를 가리지 않는 엔드포인트로만 잇는다. 없으면 종전대로 모든 메서드). 3.3: "한 호출 지점에서 URL 후보가 여럿이면 URL마다 노드 하나(메서드가 여럿 모이면 `ANY` + `httpMethods`)"
- 3.5 `HTTP_REQUEST` attrs: `api` 값 `img`(JSX `<img src>`)를 더하고, `link`·`form`·`frame`을 web도 만든다(JSX의 HTML 요소). 이 요청에도 `clientRoute=true`가 붙을 수 있다. `api=navigation`의 경로가 `/`일 수 있다(ID는 `http:request:GET /@<파일>:<줄>`)
- web이 읽는 `JSP_PAGE` attrs: `formFields`(jsp가 넣는다. F-011 spec "필드 표")
- 3.7 `FILE_SKIPPED`: 벤더 요약 진단의 메시지가 `벤더 스크립트 n개 건너뜀(<근거>): <폴더>`(jsp는 `벤더 페이지 n개 건너뜀(<근거>): <폴더>`)로 바뀐다. 근거는 `경로`·`옵션`·`이름`·`라이선스`·`생성물`(jsp는 `경로`·`옵션`)
- 3.5 공통 규칙 "벤더(서드파티) 파일": "라이선스 배너가 있는 스크립트"는 "정식 라이선스 문구·`@license`·라이선스 주소가 있고 프로젝트 정합성 검사를 통과하지 못한 스크립트"로 좁혀졌다
- 7절(한계): 벤더 판정 행에 "모든 파일에 같은 라이선스 헤더를 붙인 프로젝트"를 더한다

**5차(2026-10-11) — design.md에 직접 반영했다**: web이 만드는 `api=frame`·`link`·`form`·`img` 요청의 출처에 "DOM에 넣는 HTML 문자열 안의 태그"가 더해졌다(3.3 `HTTP_REQUEST`의 만드는 것, 3.5 `api` 행, 3.6 `http-url`, 7절 한계). 새 attr는 없다. 이 요청의 위치는 HTML 문자열 식이고 `url`은 그 식의 원문이다.

- `JS_FUNCTION` attrs: `kind`(`function`, `arrow`, `method`, `class`)
- `JS_MODULE` attrs: `moduleKind`(`esm`, `commonjs`, `script`. 인라인 모듈은 jsp가 만든 `inline=true` 그대로), `skipped`(`minified`)
- `HTTP_REQUEST` attrs: `path`, `query`, `external`, `relative`(jsp와 공통), `via`(요청 래퍼 함수의 노드 ID), `api` 값 `beacon`, `eventsource`, `navigation` 추가. `url`은 원문 식
- `CALLS`(JS) attrs: `callType` 값 `call`, `constructor`, `jsx`, `ref`; `resolution`(`global`: 전역 이름을 프로젝트 전체에서 찾음); `ambiguous`(boolean); `viaProp`(props로 받은 함수를 호출한 지점에서 JSX 부모가 넘긴 함수로 이은 엣지의 prop 이름. 항상 `INFERRED`, `callType=call`)
- `callType=ref`의 뜻에 "`f.bind(x)`"를 더한다(실행하지 않는 함수 참조)
- 진단 코드: `CALLS_SKIPPED`(INFO, 매개변수·props로 받은 함수의 호출 가운데 대상을 정하지 못해 기록하지 않은 건수. 모듈마다 1건)
- 3.5 공통 규칙의 `url`: "인라인 스크립트에서 온 요청도 JSP에 쓴 원문이다. 생산자가 치환 목록을 싣지 않은 내장 소스에서는 `__JSP_EXPR__` 자리가 `${…}`로 나온다"
- 3.9 표의 `js` 내장 소스 attrs에 `jspSubstitutions`(jsp가 넣고 web이 읽는다. 형식은 F-011 spec)
- `IMPORTS` attrs: `spec`(import 지정자 원문)
- `CALLS`·`REQUESTS`의 from으로 `JS_MODULE`(최상위 코드와 모듈 수준 콜백)
- `JS_FUNCTION` 노드로 클래스(`kind=class`)도 만든다. 3.3 표의 이름 규칙에 "이름 없는 함수 안의 선언이 겹치면 `<anonymous>@줄:열.이름`" 추가
- 미해결 분류: `js-module`, `js-call`, `http-url`(jsp와 공통)
- 진단 코드: `FILE_SKIPPED`(INFO, 압축 스크립트를 건너뜀)
- `HTTP_REQUEST` 패턴 맨 앞의 `{*}`(예: `{*}/tail`)는 "알 수 없는 접두사(컨텍스트 경로나 base URL)"라는 뜻이다. 링커가 정해야 할 것: `{*}`가 경로 조각 하나에만 맞는지, 조각 일부(`/api{*}`)에도 맞는지

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: [0005 tree-sitter](../../decisions/0005-use-tree-sitter-for-javascript.md)
- 내장 소스 치환 규칙: [F-011 JSP 분석](../F-011-jsp/spec.md)
