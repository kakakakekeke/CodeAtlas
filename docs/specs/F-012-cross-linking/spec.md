---
type: spec
id: F-012
title: 교차 연결
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-012 교차 연결

## 1. 배경
분석기들은 서로를 모른 채 그래프 계약의 노드 ID로만 만난다. 그래서 매퍼 메서드와 SQL 구문, 화면 요청과 엔드포인트, 뷰 이름과 JSP, SQL이 참조한 이름과 뷰·시노님·프로시저 사이가 끊겨 있다. 이 기능은 모든 분석기가 끝난 뒤 그래프만 보고 그 사이를 잇는다. PRD 목표 "화면 → Controller → Service → Mapper → SQL → 테이블·프로시저로 이어지는 콜트리"가 이 연결에 달려 있다.

## 2. 범위
- 포함: DB 객체 정규화(테이블 참조 스텁 → 뷰·시노님 대상, 루틴 참조 → 정의된 루틴), 매퍼 메서드 ↔ 구문, HTTP 요청 ↔ 엔드포인트, 뷰 이름 ↔ JSP, redirect·forward ↔ 핸들러(뷰 이름 접두사와 서블릿 API), 빈 참조 → 타입·메서드, 인터페이스 주입 → 구현 후보, 선언되지 않은 Java 참조의 외부 표시, 프로퍼티 자리표시자(`${키}`)를 프로젝트 properties 값으로 푸는 일(엔드포인트 경로, 뷰 리졸버, 빈 이름), 멀티모듈 프로젝트에서 모듈 경계를 넘는 연결의 구분.
- 제외: 테이블 관계 추론(F-018), 분석기가 남기는 노드·엣지 자체(F-002~F-011, F-017), 콜트리 출력(F-015).

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-012-R1 | 시스템은 정의가 없는 테이블 참조(`db:table:X`, `declared=false`)를 같은 이름의 뷰 또는 시노님의 최종 대상 노드로 합쳐야 한다. 시노님을 거친 엣지에는 `attrs.viaSynonym`을 남긴다. `ALIAS_OF`의 대상도 정의된 객체로 맞춘다. | 사용자 요청, design 3.3 |
| F-012-R2 | 시스템은 스키마가 붙었거나, 패키지 시노님을 거쳤거나, 대소문자만 다른 루틴 참조를 정의된 루틴 노드로 합쳐야 한다. | 사용자 요청 |
| F-012-R3 | 만약 테이블·루틴 참조의 정의를 끝까지 찾지 못하면, 시스템은 그 참조 노드를 `declared=false`인 채로 두어야 한다. 루틴은 참조마다 `INFO` 진단 1건을 남긴다. | 사용자 요청, AGENTS.md(미해결 기록) |
| F-012-R4 | 시스템은 매퍼 구문(`namespace`, `statementId`)마다 `qualifiedName`이 namespace와 같은 타입의 같은 이름 메서드 전부에 `EXECUTES`(메서드→구문) 엣지를 만들어야 한다. | 사용자 요청, design 3.4 |
| F-012-R5 | 만약 매퍼 인터페이스의 추상 메서드에 대응하는 구문이 없으면, 시스템은 `WARN` 진단 `MAPPER_METHOD_WITHOUT_STATEMENT`를 남겨야 한다. | 사용자 요청 |
| F-012-R6 | `attrs.shortId=true`인 `EXECUTES`의 대상 구문이 없고 `statementId`가 같은 구문이 하나뿐이면, 시스템은 그 구문으로 `INFERRED` 엣지를 추가해야 한다. | 사용자 요청 |
| F-012-R7 | 시스템은 HTTP 메서드가 호환되고 경로가 세그먼트 단위로 맞는 요청과 엔드포인트를 `MATCHES`로 이어야 한다. 정확히 맞으면 `RESOLVED`다. | 사용자 요청, design 3.4 |
| F-012-R8 | 요청 URL에서 접두 세그먼트를 떼어야 맞거나 상대 URL의 끝이 엔드포인트 하나와만 맞으면, 시스템은 `INFERRED`로 잇고 근거(`strippedPrefix`, `relative`)를 attrs에 남겨야 한다. (2026-10-11 덧붙임: 접두 제거는 F-012-R20, 맨 앞의 `{*}`는 F-012-R17, 상대 URL은 F-012-R18이 조건을 좁힌다) | 사용자 요청, AGENTS.md(추론 구분) |
| F-012-R9 | 후보가 여럿이면 시스템은 가장 구체적인 것 하나를 고르고, 동률이면 전부 `INFERRED` + `attrs.ambiguous=true`로 이어야 한다. 만약 후보가 없고 외부 URL·정적 자원이 아니면, 미해결 엣지(`MATCHES`, 분류 `http-endpoint`)를 남겨야 한다. | 사용자 요청 |
| F-012-R10 | 시스템은 핸들러 메서드의 뷰 이름을 뷰 리졸버의 prefix·suffix로 풀어 `RENDERS`(메서드→JSP)를 만들어야 한다. 리졸버 정보가 없으면 `/<이름>.jsp`로 끝나는 JSP가 하나일 때만 `INFERRED`로 잇고, 못 찾으면 미해결 엣지(`RENDERS`, 분류 `view`)를 남긴다. | 사용자 요청, design 3.4 |
| F-012-R11 | 뷰 이름이 `redirect:`·`forward:`로 시작하면, 시스템은 그 경로와 맞는 엔드포인트의 핸들러 메서드로 `CALLS`(`INFERRED`, `attrs.callType`)를 만들어야 한다. | 사용자 요청 |
| F-012-R12 | 시스템은 빈을 가리키는 `INJECTS`·`USES`에 그 빈을 정의한 타입으로 가는 같은 종류의 엣지를 더하고, 스텝이 쓰는 타입의 역할 메서드(`execute`, `read`, `process`, `write`)로 가는 `USES`를 더해야 한다. 주입 대상의 구현이 하나로 정해지면 시스템은 그 구현 타입으로 가는 `INFERRED` `INJECTS`(`attrs.resolvedFrom=<선언된 대상 타입 ID>`)를 더해야 한다: 분석기가 후보를 적어 둔 주입(`attrs.candidates`)은 후보가 정확히 하나일 때 그 타입으로 잇고(여럿이면 잇지 않는다), 후보가 없는 인터페이스 주입은 빈으로 정의된 구현이 하나로 정해질 때만 잇는다. 구현 후보는 attr만으로 표현하지 않고 항상 이 엣지로 드러낸다. (2026-10-11 고쳐 씀: 전에는 `candidates`가 있는 주입을 건너뛰어, 후보가 하나로 정해진 주입이 attr로만 표현됐다) | 사용자 요청, 독립 감사(U1) |
| F-012-R13 | 메서드 노드에 `attrs.redirects`·`attrs.forwards`(서블릿 API `sendRedirect`·`RequestDispatcher.forward`의 경로 패턴 목록)가 있으면, 시스템은 그 경로와 맞는 엔드포인트의 핸들러 메서드로 `CALLS`(`INFERRED`, `attrs.callType=redirect\|forward`, `attrs.path`)를 만들어야 한다. forward 대상이 JSP 경로면 `RENDERS`(메서드→JSP)를 만들고, 맞는 것이 없으면 미해결 엣지를 남긴다. | 사용자 요청 |
| F-012-R14 | 모든 분석기가 끝난 뒤에도 선언되지 않은(`declared=false`) `JAVA_TYPE`·`JAVA_METHOD`·`JAVA_FIELD` 노드에 `external` attr가 없으면, 시스템은 `external=true`를 붙여야 한다. 선언된 노드, `external`이 이미 있는 노드, `UNRESOLVED` 노드는 건드리지 않는다. | 뷰어 검증(조정자 요청) |
| F-012-R15 | 엔드포인트 경로에 프로퍼티 자리표시자(`${키}`, `${키:기본값}`)가 있으면, 시스템은 프로젝트 properties·yml의 값(F-012-R24)으로 치환한 경로로 요청을 맞추고, 자리표시자를 전부 치환한 경로를 엔드포인트 노드의 `attrs.resolvedPath`에 남겨야 한다. 값을 찾지 못한 자리표시자 세그먼트는 와일드카드가 아니라 **알 수 없는 고정 조각**으로 다뤄, 요청의 대응 세그먼트가 `{*}`일 때만 `INFERRED`(`dynamicSegment`)로 맞춘다. 요청 경로가 "엔드포인트 경로에서 맨 앞 자리표시자 세그먼트를 뗀 나머지"와 맞으면(고정 세그먼트가 하나 이상 일치해야 한다) `INFERRED` + `attrs.strippedPlaceholder=<자리표시자 원문>`으로 잇는다. | 공개 프로젝트 검증(B5-a, jeesite) |
| F-012-R16 | 리터럴과 변수가 섞인 세그먼트(`comment-{theme}`, `view-{id}-{page}`, `{name}.json`, 요청의 `view-{*}-{*}`)는 세그먼트 전체를 와일드카드로 보지 않아야 한다. 리터럴 부분이 상대 세그먼트에서도 같은 자리에 맞아야 하고, 요청의 `{*}`가 엔드포인트의 리터럴 글자를 덮어야만 맞는 경우는 `INFERRED` + `dynamicSegment=true`다. | 공개 프로젝트 검증(B5-b) |
| F-012-R17 | 요청 URL 맨 앞의 `{*}`(`{*}/board`)는 경로 세그먼트 하나가 아니라 0개 이상의 알 수 없는 접두(기준 URL)로 다뤄야 한다. 나머지 경로 전체가 엔드포인트 경로 전체(또는 맨 앞 자리표시자 세그먼트를 뗀 나머지, 서블릿 매핑 접두를 뗀 경로)와 맞고 고정 세그먼트가 하나 이상 일치할 때만 `INFERRED` + `strippedPrefix={*}`로 잇는다. 메서드만 다른 여러 엔드포인트가 맞으면 요청 메서드가 `ANY`일 때만 전부(`ambiguous`), 아니면 메서드가 맞는 것만 잇는다. | 공개 프로젝트 검증(B5-c, simple-react) |
| F-012-R18 | 상대 URL(`attrs.relative=true` 또는 `/`·스킴·`{*}`로 시작하지 않는 URL)은 (1) 그 요청이 든 페이지를 그리는(`RENDERS`) 핸들러의 엔드포인트 경로를 기준 디렉터리로 풀어 전체 경로로 맞추고(`INFERRED`, `relative=true`, `relativeBase=<기준 디렉터리>`), (2) 기준을 모르면 엔드포인트의 **고정 세그먼트**로만 접미를 비교해 맞는 엔드포인트가 정확히 하나일 때만 `INFERRED`(`relative=true`)로 이어야 한다. 요청의 리터럴 조각을 엔드포인트의 경로 변수에 접미로 맞추지 않는다. redirect·forward의 상대 대상은 그 핸들러로 들어오는 엔드포인트 경로를 기준으로 삼는다. | 공개 프로젝트 검증(B5-d, zheng·cc) |
| F-012-R19 | 끝의 `/`만 다른 요청과 엔드포인트(`/a/b/`와 `/a/b`)는 맞는 것으로 보고 `attrs.trailingSlash=true`를 남겨야 한다. 신뢰도는 바꾸지 않는다. 끝 슬래시까지 같은 엔드포인트가 따로 있으면 그쪽을 고른다. | 공개 프로젝트 검증(B5-e) |
| F-012-R20 | 요청 `/`는 경로가 `/`인 엔드포인트와 맞춰야 한다. 접두 세그먼트·자리표시자 세그먼트를 떼고 맞추는 추론은 뗀 뒤에 고정 세그먼트가 하나 이상 일치할 때만 해야 한다(고정 조각이 없는 루트·변수 엔드포인트로 추론하지 않는다). | 공개 프로젝트 검증(B5-g) |
| F-012-R21 | 요청을 내는 파일과 핸들러 메서드 파일의 모듈 루트(가장 가까운 조상 `pom.xml`·`build.gradle`·`build.gradle.kts` 디렉터리)가 다르면: 같은 모듈에 맞는 엔드포인트가 있으면 그것만 잇고, 없으면 `INFERRED` + `attrs.crossModule=true`로 이어야 한다. 요청 파일의 모듈을 모르거나 그 모듈에 핸들러가 전혀 없으면(프런트 전용 모듈) 판단하지 않는다(2026-10-11 덧붙임: "그 모듈에 핸들러가 전혀 없으면"은 F-012-R36이 거뒀다). redirect·forward의 `CALLS`는 엔드포인트의 핸들러가 여러 모듈에 있으면 넘기는 쪽과 같은 모듈의 핸들러로만 잇는다. | 공개 프로젝트 검증(B5-h, zheng) |
| F-012-R22 | 뷰 리졸버 빈의 `property.prefix`·`property.suffix`가 프로퍼티 자리표시자면, 시스템은 프로젝트 properties 값(F-012-R24)으로 치환해 뷰 이름을 풀어야 한다. 값을 찾지 못한 리졸버는 쓰지 않는다. | 공개 프로젝트 검증(B10-a, jeesite) |
| F-012-R23 | 이름에 프로퍼티 자리표시자가 든 빈(`spring:bean:dataSource-${Globals.DbType}`)을 가리키는 `INJECTS`·`USES`는, 치환한 이름의 빈이 정의돼 있으면 그 빈으로 가는 같은 종류의 엣지(`attrs.resolvedFrom=<원래 빈 ID>`)를 더하고 이어서 F-012-R12대로 타입으로 풀어야 한다. 만약 값을 찾지 못했거나 치환한 이름의 빈이 없으면, 엣지를 더하지 않고 `INFO` 진단 `UNRESOLVED_BEAN_PLACEHOLDER` 1건에 이름과 건수를 모아 적는다. | 공개 프로젝트 검증(B10-b, enterprise) |
| F-012-R24 | 프로퍼티 자리표시자는 공용 헬퍼 하나로 조회한다: 프로젝트의 `.properties`·`.yml` 값을 모아, 참조하는 파일이 테스트 소스(`src/test/`)가 아니면 테스트 소스의 파일은 빼고, 참조하는 파일과 같은 모듈 루트에 그 키가 있으면 그 모듈의 값만 본다. 남은 값이 하나면 그 값으로 치환하고, 서로 다른 값이 둘 이상이면(파일·YAML 문서마다 다름) 모호라서 풀지 않는다. 어디에도 없으면 `${키:기본값}`의 기본값을 쓴다. 값 안의 자리표시자도 같은 규칙으로 푼다. | 공개 프로젝트 검증(B10) |
| F-012-R25 | 구문의 namespace 타입이 메서드를 상속만 했으면(선언은 상위 인터페이스에), 시스템이 만드는 `EXECUTES`(상위 메서드→구문, `INFERRED`)에는 `attrs.namespace`(구문의 namespace인 구체 매퍼 타입의 바이너리 이름)와 `attrs.inherited=true`, `attrs.declaredIn`이 있어야 한다. 선언이 namespace 타입 자신에 있으면 F-012-R4대로 `RESOLVED`이고 이 키들은 없다. | 공개 프로젝트 검증(B6, jeesite) |
| F-012-R26 | 빈 참조를 타입으로 풀 때(F-012-R12), 시스템은 테스트 소스(`src/test/`)의 빈 정의를 참조하는 쪽도 테스트 소스일 때만 후보로 쓰고, 참조하는 쪽과 같은 모듈 루트의 정의가 있으면 그것만 봐야 한다. 그렇게 좁히고도 정의 타입이 둘 이상이면 엣지를 만들지 않는다. | 공개 프로젝트 검증(B21) |
| F-012-R27 | 주입 후보를 고를 때(F-012-R12) 시스템은 `DEFINES_BEAN` 엣지가 있거나(빈의 `class`로 이어진 타입 포함) `JAVA_TYPE` `attrs.beanName`이 있는 타입만 빈으로 봐야 한다. `attrs.stereotype`은 보지 않는다(값이 `filter`·`interceptor`처럼 타입의 역할일 수 있다). 후보의 빈 이름은 `DEFINES_BEAN`의 빈 이름과 `beanName`의 값이다. | 실전 검증 2차 후속(java가 `stereotype=filter`·`interceptor`를 더했다) |
| F-012-R28 | 뷰 이름·redirect·forward로 만드는 `RENDERS`·`CALLS`와 그 미해결 엣지의 위치는, 메서드 attrs의 병렬 목록 `viewNameLines`·`redirectLines`·`forwardLines`(`줄:열`)가 있으면 그 호출 지점(파일은 메서드의 파일)이어야 한다. 병렬 목록이 없거나(옛 그래프) 길이가 다르거나 값을 읽을 수 없으면 전처럼 메서드의 위치다. | 실전 재검증 2차 후속(2026-10-11)(N7) |
| F-012-R29 | `attrs.clientRoute=true`인 `HTTP_REQUEST`(클라이언트 라우팅의 이동. web이 붙인다)는 맞는 엔드포인트가 있어도 `MATCHES`로 잇지 않고 미해결도 남기지 않아야 한다(`external`과 같은 취급). | 실전 재검증 2차 후속(2026-10-11)(N9-a) |
| F-012-R30 | Tiles 설정 파일의 FILE 노드에 `attrs.tilesDefinitions`(List: `<definition 이름>=<template JSP 경로>`)가 있으면, 시스템은 뷰 이름이 definition 이름과 맞을 때(정확한 이름이 먼저, 와일드카드 `*`는 `/`를 넘지 않는 조각, `**`는 `/`를 포함한 0개 이상의 글자, 값의 `{n}`은 n번째 와일드카드) 뷰 리졸버 규칙보다 먼저 그 template JSP로 `RENDERS`(`RESOLVED`, `attrs.via=tiles`, `attrs.definition`)를 만들어야 한다. `attrs.tilesAttributes`(List: `<definition 이름>=<put-attribute의 JSP 경로>`)의 JSP로도 `RENDERS`(`tilesAttribute=true`)를 만들되 없는 JSP는 미해결 없이 건너뛴다. template JSP를 찾지 못하면 뷰 미해결이다. `tilesDefinitions`가 없는 그래프에서는 기존 동작이다. | 실전 재검증 2차 후속(2026-10-11) |
| F-012-R31 | `attrs.statementPrefix`가 있는 미해결 `EXECUTES`(F-004-R20)마다, 시스템은 그 접두로 시작하는 매퍼 구문이 1~5개면 각각으로 `INFERRED` `EXECUTES`(`attrs.prefixMatch=true`, `statementPrefix`, `operation`, `resolvedFrom`)를 더해야 한다. 5개를 넘거나 없으면 더하지 않는다. 원래의 미해결 엣지는 남는다. | 실전 재검증 2차 후속(2026-10-11)(N28-a) |
| F-012-R32 | 메서드 attrs `viewNameCandidates`(F-004-R21)에 든 뷰 이름의 `RENDERS`는 JSP를 찾아도 `INFERRED` + `attrs.candidate=true`여야 한다. | 실전 재검증 2차 후속(2026-10-11)(N28-d) |
| F-012-R33 | redirect·forward 대상에 프로퍼티 자리표시자가 있으면(`redirect:${adminPath}/login`, F-004-R23) 시스템은 프로젝트 properties 값(F-012-R24)으로 치환해 엔드포인트와 맞춰야 한다. 값을 찾지 못한 자리표시자는 알 수 없는 조각(`{*}`)으로 읽는다. | 실전 재검증 2차 후속(2026-10-11)(N27) |
| F-012-R34 | `attrs.viewNames`가 있는 `HTTP_ENDPOINT`(`<mvc:view-controller>`, F-017-R16)에서도 시스템은 메서드와 같은 규칙으로 `RENDERS`(엔드포인트→JSP)와 redirect·forward `CALLS`(엔드포인트→핸들러 메서드)를 만들어야 한다. 뷰 이름은 먼저 뷰 이름 규칙(Tiles, 뷰 리졸버)으로 풀고, 그렇게 찾지 못한 이름이 `.jsp`·`.jspf`·`.html`로 끝나는 경로면 컨텍스트 루트 기준의 파일로 찾는다(F-012-R35). 엔드포인트에 `attrs.viewDeclarations`(List: `<파일>:<줄>:<열> <view-name>`)가 있으면 선언마다 하나씩, 그 선언의 파일과 가까운 JSP로 잇고 엣지의 위치는 그 선언이다. | 실전 재검증 2차 후속(2026-10-11)(N27) |
| F-012-R35 | 메서드 attrs `forwards`의 JSP 경로(`getRequestDispatcher(<경로>)`)는 뷰 이름이 아니라 컨텍스트 루트 기준의 경로로 다뤄야 한다: 알려진 웹 루트 바로 아래에 그 경로의 JSP가 있으면 `RESOLVED`, 없고 그 경로로 끝나는 JSP가 정확히 하나면 `INFERRED` + `attrs.suffixMatch=true`, 여럿이거나 없으면 `RENDERS`를 만들지 않고 뷰 미해결로 남긴다. 뷰 리졸버의 prefix·suffix는 적용하지 않는다. `forward:` 뷰 이름 접두는 기존 규칙이다. | 실전 재검증 2차 후속(2026-10-11)(N11) |
| F-012-R36 | 요청 파일의 모듈 루트를 알고 맞은 엔드포인트의 핸들러가 전부 다른 모듈이면, 요청 쪽 모듈에 핸들러가 하나도 없어도 시스템은 `INFERRED` + `attrs.crossModule=true`로 이어야 한다(F-012-R21의 "그 모듈에 핸들러가 전혀 없으면 판단하지 않는다"를 거둔다). 요청 파일의 모듈을 모르면 판단하지 않는다. | 실전 재검증 2차 후속(2026-10-11)(N12, zheng) |
| F-012-R37 | 모듈 경계(F-012-R21, R36)를 볼 때 시스템은 엔드포인트의 "선언"을 핸들러 메서드(`ROUTES_TO`)와 view-controller 선언(엔드포인트 attrs `viewDeclarations`의 파일. 없으면 `viewController=true`인 노드의 위치) 둘 다로 봐야 한다. 요청 파일의 모듈 루트에 그 엔드포인트의 선언이 있으면 같은 모듈 매칭이다(`crossModule` 없음, 신뢰도는 다른 근거대로). 선언이 여러 모듈에 있는 엔드포인트의 같은 모듈 매칭에는 `MATCHES` `attrs.module`(요청 모듈 루트의 프로젝트 상대 경로, 프로젝트 루트는 `.`)을 남긴다. redirect·forward `CALLS`도 같다: 넘기는 쪽 모듈에 핸들러가 있으면 그것으로만 잇고, view-controller 선언만 있으면 다른 모듈의 핸들러로 잇지 않는다(미해결도 남기지 않는다). | 실전 재검증 3차 후속(2026-10-11)(M5: zheng 11개 모듈의 `500.jsp → GET /`가 자기 모듈의 `<mvc:view-controller path="/">` 대신 cms-web의 `IndexController`로 `crossModule` 연결) |
| F-012-R38 | 요청 모듈에 선언이 없고 다른 모듈에만 있을 때, 요청 모듈이 독립 배포 단위(그 모듈에 `web.xml`이 있거나, `pom.xml`의 `<packaging>war</packaging>`·Gradle `war` 플러그인이거나, `@SpringBootApplication` 클래스가 있다)면 시스템은 다른 독립 배포 단위 모듈의 엔드포인트로 잇지 않고 미해결(`http-endpoint`)로 남겨야 한다: 다른 웹앱의 같은 경로는 다른 URL이다. 대상 모듈이 독립 배포 단위가 아니면(war에 함께 묶이는 라이브러리 모듈의 컨트롤러) 전처럼 `INFERRED` + `crossModule`이다. 요청 모듈이 독립 배포 단위가 아니면(정적 자원·프런트 모듈) 전과 같다(F-012-R36). redirect·forward `CALLS`도 같다. | 실전 재검증 3차 후속(2026-10-11)(M5: zheng api-server·oss-admin의 `/manage/index`가 cms-admin·upms-server의 컨트롤러로 이어진 죽은 링크 2건) |
| F-012-R39 | `HTTP_REQUEST`에 `attrs.httpMethods`(List: 메서드를 하나로 정하지 못한 요청의 메서드 후보. `httpMethod`는 `ANY`. web이 남긴다)가 있으면, 시스템은 그 메서드들의 엔드포인트와 메서드를 가리지 않는(`ANY`) 엔드포인트만 후보로 삼아야 한다. 후보가 여럿이면 전부 `INFERRED` + `ambiguous=true`, 없으면 미해결이다. | 실전 재검증 3차 후속(2026-10-11)(M1: `ANY /api/schedule/{*}`가 `DELETE`·`GET`으로도 이어짐) |
| F-012-R40 | `attrs.selfSubmit=true`인 `HTTP_REQUEST`(action 없는 폼: 현재 URL로 제출. 경로 없음, `httpMethod`는 폼 메서드. jsp가 남긴다)는, 그 요청을 낸 `JSP_PAGE`를 `RENDERS`하는 핸들러의 엔드포인트(view-controller면 그 엔드포인트)와 **경로가 같고** 요청 메서드에 맞는 엔드포인트로 `MATCHES`(`INFERRED`, `attrs.selfSubmit=true`)를 만들어야 한다. 그리는 핸들러가 여럿이면 각각 잇고(대상이 둘 이상이면 `ambiguous=true`), 그리는 핸들러를 모르거나 맞는 엔드포인트가 없으면 미해결(`http-endpoint`, 원문 `<메서드> (selfSubmit)`)이다. | 실전 재검증 3차 후속(2026-10-11)(같은 컨트롤러의 `@GetMapping("/x")` 화면 + `@PostMapping("/x")` 처리 관용구) |
| F-012-R41 | 접두 구문 ID 연결(F-012-R31)에서 시스템은 호출의 연산(`attrs.operation`)과 구문의 `statementType`이 맞는 구문만 후보로 삼아야 한다: 읽는 호출(`select*`, `queryFor*`, `list*`)은 `SELECT`·`CALL`, 쓰는 호출(`insert`, `update`, `delete`)은 `INSERT`·`UPDATE`·`DELETE`·`MERGE`·`CALL`. 쓰는 것끼리는 가리지 않는다(MyBatis는 insert·update·delete 태그를 서로 바꿔 불러도 동작한다). 연산이나 구문의 종류를 모르면 거르지 않는다. 후보 수(1~5)는 거른 뒤에 센다. | 실전 재검증 3차 후속(2026-10-11)(M7: `sql.insert("Board." + type)`이 `<select>` 구문에도 이어짐) |
| F-012-R42 | 접두로 풀린 미해결 `EXECUTES`를 소비자가 숨길 수 있어야 한다: 풀린 엣지의 `attrs.resolvedFrom`은 그 미해결 노드의 ID이고 위치는 미해결 엣지와 같다. 그 미해결 노드로 들어오는 엣지가 전부 풀렸으면 시스템은 그 `UNRESOLVED` 노드에 `attrs.resolvedBy=prefixMatch`를 남겨야 한다. (그래프에 엣지를 지우는 API가 없어 원래의 미해결 엣지는 남는다.) | 실전 재검증 3차 후속(2026-10-11)(M7) |
| F-012-R43 | 메서드 attrs `statementIdLiterals`(F-004-R24)의 문자열마다, 그 ID(`<namespace>.<id>`)의 매퍼 구문이 프로젝트에 실제로 있으면 시스템은 `EXECUTES`(메서드 → 구문, `INFERRED`, `attrs.viaArgument=true`, 위치는 `statementIdLiteralLines`의 자리)를 만들어야 한다. 그 메서드에서 그 구문으로 가는 `EXECUTES`가 이미 있으면 더하지 않고, 구문이 없는 문자열은 잇지도 미해결로 남기지도 않는다. | 실전 재검증 3차 후속(2026-10-11)(M13-a) |
| F-012-R44 | 뷰 이름의 동적 조각(`{*}`)이 하나이고 디렉터리 조각 전체일 때(`themes/{*}/frontIndex`. 마지막 조각이나 조각의 일부가 아님), 시스템은 뷰 리졸버로 푼 경로의 그 자리에 디렉터리 이름 하나를 넣어 실제로 있는 JSP가 1~5개면 각각 `RENDERS`(`INFERRED`, `attrs.dynamicSegment=true`)를 만들어야 한다. 둘 이상이면 `candidate=true`다. 리졸버로 찾지 못한 파일 경로 꼴의 이름(`/{*}/qa/index.html`)은 경로 접미사로 찾는다(`suffixMatch=true`). 후보가 없거나 5개를 넘으면 전처럼 뷰 미해결이다. | 실전 재검증 3차 후속(2026-10-11)(M13-c: jeesite 테마 뷰, zheng 도우미 뷰) |
| F-012-R45 | 시스템은 같은 (출발, 종류, 위치)에서 나가는 후보가 하나뿐인 엣지에 `attrs.ambiguous`를 붙이지 않아야 한다. 동률인 엔드포인트들이 같은 핸들러로 가는 redirect·forward `CALLS`는 엣지가 하나이고 `ambiguous`가 없다. | 실전 재검증 3차 후속(2026-10-11)(후보 하나짜리 정리) |
| F-012-R46 | 정적 파일 꼴의 상대 링크(마지막 조각이 `.html`·`.jsp`·`.js` 등 정적 자원 확장자인 리터럴)를 기준 디렉터리로 풀 때(F-012-R18), 시스템은 마지막 세그먼트가 경로 변수 하나(`/topic/{topicId}`)인 엔드포인트와는 맞추지 않아야 한다: 기준 디렉터리의 다른 파일을 가리키는 링크다. | 실전 재검증 3차 후속(2026-10-11)(F-012-R44로 Thymeleaf 시안 페이지에 그리는 핸들러가 생기자 `login.html` 같은 상대 링크 39건이 `/topic/{topicId}` 등에 맞은 것을 zheng 재실행에서 발견) |
| F-012-R47 | 동적 디렉터리 뷰(F-012-R44)의 후보는 근거가 있으면 다음 순서로 좁혀야 한다. (a) 동적 자리가 프로퍼티 자리표시자면(`/${app.name}/login.html`. F-004-R23·R26) 컨트롤러 모듈의 프로퍼티 값(F-012-R24)으로 그 디렉터리를 정한다: 값이 하나로 모이면 그 디렉터리의 후보만 남기고(`attrs.narrowedBy=property`), 그 디렉터리에 그 뷰가 없으면 다른 디렉터리의 것으로 잇지 않는다(뷰 미해결). (b) 값을 모르면, 동적 자리의 디렉터리 이름이 컨트롤러가 속한 모듈의 디렉터리 이름(모듈 루트의 마지막 경로 조각)과 같은 후보가 있을 때 그것만 남긴다(`narrowedBy=moduleName`). (c) 어느 근거도 없으면 전처럼 후보 전부다. 5개 상한은 좁힌 뒤에 본다. 좁혀서 하나가 남아도 `INFERRED` + `dynamicSegment=true`다(`candidate`는 둘 이상일 때만). 프로퍼티 값을 찾을 때 자기 자신을 가리키는 값(`app.name=${app.name}`, `@app.name@`: 빌드 도구의 필터링 자리)은 값으로 치지 않는다. | 실전 재검증 4차 후속(2026-10-11)(zheng `SignController#signin`의 틀린 후보 `zheng-admin/src/login.html`, 후보가 5개를 넘던 `/{*}/index.html` 2곳) |
| F-012-R48 | 웹앱이 아닌 모듈의 파일에서 나간 요청이 다른 모듈의 엔드포인트와 맞을 때(`crossModule`, F-012-R36·R38), 그 요청을 낸 페이지를 그리는(`RENDERS`) 핸들러가 모두 한 모듈에 있고(view-controller가 그리면 선언한 설정 파일의 모듈) 그 모듈에 선언(핸들러 또는 view-controller)이 있는 후보가 있으면, 시스템은 그 후보만 남기고 `MATCHES`에 `attrs.module=<그 모듈>`을 남겨야 한다(`crossModule=true`·`INFERRED`는 그대로. `module`은 F-012-R37과 같이 선언이 여러 모듈에 있는 엔드포인트에만 남긴다). 직접 그리는 핸들러가 없는 조각·스크립트는 그것을 포함하는(`INCLUDES`) 페이지를 거슬러 올라가 판단한다. 그리는 핸들러가 없거나, 여러 모듈에 있거나, 그 모듈에 선언이 있는 후보가 없으면 전과 같다. | 실전 재검증 4차 후속(2026-10-11)(zheng: `zheng-ui/zheng-cms-web/**` 템플릿의 `GET /` 요청 17건이 12개 모듈의 `index.jsp`와 섞임) |
| F-012-R49 | 요청의 접두 세그먼트를 추측으로 뗄 때(컨텍스트 경로·서블릿 매핑으로 보고 1~2개. F-012-R8), 떼려는 접두의 첫 세그먼트가 프로젝트에 선언된 엔드포인트 경로의 첫 고정 세그먼트로 쓰이고 있으면 시스템은 그 접두를 떼지 않아야 한다: 그 세그먼트는 실제 매핑의 일부이므로 `/manage/coder/index`는 `/index`가 아니라 없는 주소(미해결)다. 경로 변수(`/{id}/…`)나 값을 모르는 자리표시자로 시작하는 엔드포인트는 근거로 쓰지 않는다. 근거가 있는 접두(web.xml의 서블릿 매핑과 맞는 접두), 호스트, 맨 앞의 `{*}`(미지 접두)는 전과 같다. redirect·forward `CALLS`의 대상 매칭도 같다. | 실전 재검증 4차 후속(2026-10-11)(zheng `zheng-admin/src/index.html`의 `/manage/coder/index`·`/manage/map/index`가 `GET /index`로 이어짐) |

## 4. 수용 기준

### F-012-AC1 (대상: F-012-R1)
- Given `db:view:V`가 정의돼 있고 구문이 `db:table:V`(`declared=false`)를 읽는 그래프
- When 정규화 링커를 실행하면
- Then `READS`가 `db:view:V`를 가리키고 `db:table:V` 노드는 없다.

### F-012-AC2 (대상: F-012-R1)
- Given 시노님 `S1 → S2 → 테이블 T`(연쇄)와 `db:table:S1`을 읽는 구문
- When 정규화 링커를 실행하면
- Then `READS`가 `db:table:T`를 가리키고 `attrs.viaSynonym=S1`이며 스텁은 없다. `S1`의 `ALIAS_OF`도 `T`를 가리킨다.

### F-012-AC3 (대상: F-012-R1)
- Given 서로를 가리키는 시노님 `A → B → A`와 `db:table:A`를 읽는 구문
- When 정규화 링커를 실행하면
- Then 링커가 끝나고 스텁은 그대로 남으며 `WARN` 진단 `SYNONYM_CYCLE`이 있다.

### F-012-AC4 (대상: F-012-R1)
- Given 시노님 `S`의 `ALIAS_OF` 대상이 `db:table:P`(스텁)이고 `db:package:P`(또는 시퀀스·루틴)가 정의된 그래프
- When 정규화 링커를 실행하면
- Then `ALIAS_OF`가 정의된 객체 노드를 가리킨다.

### F-012-AC5 (대상: F-012-R1)
- Given 시노님 `S`가 테이블 `T`를 가리키고, 구문이 `db:table:S`와 `db:column:S.C`를 읽으며 `S`와 `T`를 조인한 그래프
- When 정규화 링커를 실행하면
- Then 컬럼 참조는 `db:column:T.C`로 옮겨지고, 조인은 `T→T`(자기 조인)로 남으며 둘 다 `viaSynonym=S`다.

### F-012-AC6 (대상: F-012-R2)
- Given 정의된 `db:routine:PKG.PROC`와 참조 `db:routine:SHOP.PKG.PROC`, `db:routine:pkg.proc`, `db:routine:SYN.PROC`(`SYN`은 `PKG`의 시노님)
- When 정규화 링커를 실행하면
- Then 세 참조의 엣지가 모두 `db:routine:PKG.PROC`를 가리키고 시노님을 거친 엣지에는 `viaSynonym=SYN`이 있다.

### F-012-AC7 (대상: F-012-R2)
- Given 정의된 독립 프로시저 `db:routine:SP_X`와 참조 `db:routine:SHOP.SP_X`
- When 정규화 링커를 실행하면
- Then `SHOP`이 알려진 스키마면 신뢰도를 유지한 채 합치고, 알려지지 않았으면 `INFERRED` + `attrs.strippedQualifier=SHOP`으로 합친다. `SHOP`이 정의된 패키지 이름이면 합치지 않는다.

### F-012-AC8 (대상: F-012-R3)
- Given 어디에도 정의가 없는 `db:table:GHOST`, `db:routine:NOPE.RUN` 참조(노드가 없는 엣지 대상 포함)
- When 정규화 링커와 마무리 단계를 실행하면
- Then 두 노드는 원래 종류와 `declared=false`로 남고(UNRESOLVED가 아니다) 루틴에 대해 `INFO` 진단 `UNDEFINED_ROUTINE`이 1건 있다.

### F-012-AC9 (대상: F-012-R4)
- Given namespace가 `com.a.OrderMapper`인 구문 `find`와, 그 타입의 오버로드된 `find` 메서드 둘
- When 매퍼 바인딩 링커를 실행하면
- Then 두 메서드 모두에서 구문으로 가는 `RESOLVED` `EXECUTES`가 있다. `attrs.annotation=true`인 구문도 같다. 이미 같은 엣지가 있으면 더 만들지 않는다.

### F-012-AC10 (대상: F-012-R5)
- Given 매퍼 인터페이스에 추상 메서드 `a`(구문 있음), `b`(구문 없음), `default` 메서드 `c`
- When 매퍼 바인딩 링커를 실행하면
- Then `b`에 대해서만 `WARN` 진단 `MAPPER_METHOD_WITHOUT_STATEMENT`가 있다.

### F-012-AC11 (대상: F-012-R6)
- Given `attrs.shortId=true`인 `EXECUTES`(대상 `mybatis:stmt:selectOne`, 노드 없음)와 `statementId=selectOne`인 구문 하나
- When 매퍼 바인딩 링커를 실행하면
- Then 그 구문으로 가는 `INFERRED` `EXECUTES`가 추가되고 원래 엣지는 남는다. 같은 ID의 구문이 둘이면 추가하지 않는다. `shortId`가 없는 엣지는 건드리지 않는다.

### F-012-AC12 (대상: F-012-R7)
- Given 엔드포인트 `GET /orders/{id}`, `POST /orders`, `ANY /member/list.do`와 요청 `GET /orders/{*}`, `POST /orders`, `GET /member/list.do`
- When HTTP 연결 링커를 실행하면
- Then 각 요청이 대응하는 엔드포인트로 `RESOLVED` `MATCHES`를 갖는다. 메서드가 다른 엔드포인트(`GET /orders` 요청과 `POST /orders`)는 잇지 않는다. 요청의 경로는 `attrs.path`(정규화된 패턴) → `attrs.url`(원문) → 노드 ID 순으로 얻는다.

### F-012-AC13 (대상: F-012-R7)
- Given 엔드포인트 `/files/**`, `/item/{id:\d+}`, `/a/*.do`
- When 요청 `/files/a/b`, `/item/12`, `/item/abc`, `/a/x.do`, `/orders/`(엔드포인트 `/orders` 있음)를 맞추면
- Then 앞의 둘과 `/a/x.do`는 맞고, `/item/abc`는 맞지 않는다. 끝의 `/`만 다른 `/orders/`는 `/orders`와 맞는다(`trailingSlash=true`, F-012-AC34). (2026-10-11 고쳐 씀: 전에는 끝의 `/`가 다르면 맞지 않았다)

### F-012-AC14 (대상: F-012-R8)
- Given 엔드포인트 `GET /orders`와 web.xml의 `servletMappings=[/api/*]`
- When 요청 `GET /api/orders`, `GET /shop/app/orders`, `GET {*}/orders`를 맞추면
- Then 모두 `INFERRED`이고 `attrs.strippedPrefix`가 각각 `/api`, `/shop/app`, `{*}`다.

### F-012-AC15 (대상: F-012-R8)
- Given 엔드포인트 `/order/list.do`, `/member/list.do`, `/order/save.do`
- When `relative=true`인 요청 `save.do`와 `list.do`를 맞추면
- Then `save.do`는 `INFERRED`(`attrs.relative=true`)로 이어지고, 후보가 둘인 `list.do`는 미해결로 남는다.

### F-012-AC16 (대상: F-012-R9)
- Given 엔드포인트 `GET /orders/{id}`, `GET /orders/new`와 `GET /a/{x}`, `GET /a/{y}`
- When 요청 `GET /orders/new`, `GET /orders/{*}`, `GET /a/1`을 맞추면
- Then 앞의 둘은 각각 `/orders/new`, `/orders/{id}` 하나로 `RESOLVED`이고, `/a/1`은 두 엔드포인트 모두로 `INFERRED` + `ambiguous=true`다.

### F-012-AC17 (대상: F-012-R9)
- Given 엔드포인트와 맞지 않는 요청들: 일반 경로, `/resources/js/a.js`, 전부 동적인 `{*}`, 그리고 `external=true`인 요청(경로가 엔드포인트와 같아도)
- When HTTP 연결 링커를 실행하면
- Then 일반 경로와 `{*}`는 미해결 엣지(`MATCHES`, `unresolved:http-endpoint:...`)와 `WARN` 진단을 갖고, 외부 요청과 정적 자원에는 아무 엣지도 없다.

### F-012-AC18 (대상: F-012-R10)
- Given 뷰 리졸버 빈(`property.prefix=/WEB-INF/views/`, `property.suffix=.jsp`)과 `jsp:page:src/main/webapp/WEB-INF/views/order/list.jsp`, `viewNames=[order/list]`인 메서드
- When 뷰 연결 링커를 실행하면
- Then 메서드에서 그 JSP로 `RESOLVED` `RENDERS`가 있다. `application.properties`·`application.yml`의 `spring.mvc.view.prefix`/`suffix`로도 같다.

### F-012-AC19 (대상: F-012-R10)
- Given 리졸버 정보가 없는 그래프
- When `viewNames=[order/list]`를 풀면
- Then `/order/list.jsp`로 끝나는 JSP가 하나면 `INFERRED` `RENDERS`, 둘이거나 없으면 미해결 엣지(`RENDERS`, `unresolved:view:order/list`)다. 리졸버가 있는데 경로의 JSP가 없을 때와, 뷰 이름에 동적 조각(`{*}`)이 있을 때도 미해결이다.

### F-012-AC20 (대상: F-012-R11)
- Given 엔드포인트 `GET /order/list` → 핸들러 `list`와 `viewNames=[redirect:/order/list?page=1]`인 메서드 `save`
- When 뷰 연결 링커를 실행하면
- Then `save → list`로 `INFERRED` `CALLS`(`callType=redirect`)가 있고 `RENDERS`는 없다. `forward:`는 `callType=forward`다. 맞는 엔드포인트가 없으면 미해결 엣지(`CALLS`, 분류 `http-endpoint`)다.

### F-012-AC21 (대상: F-012-R12)
- Given `INJECTS`(타입→`spring:bean:orderDao`, `unresolvedBeanRef=true`)와 그 빈을 정의한 타입(`DEFINES_BEAN`), `@Bean` 메서드(`beanType`)로 정의한 다른 빈
- When 빈 참조 링커를 실행하면
- Then 정의 타입으로 가는 `INJECTS`가 추가되고(`attrs.resolvedFrom=<빈 ID>`) 기존 엣지는 남는다. `USES`(스텝→빈)도 같은 `role`로 타입을 가리키는 엣지가 추가된다. `INJECTS`의 출발점이 빈이면 그쪽도 정의 타입으로 푼다(`attrs.resolvedSource=<빈 ID>`).

### F-012-AC22 (대상: F-012-R12)
- Given `USES`(스텝→타입, `role=tasklet`)와 그 타입의 `execute` 메서드, `role=writer`인 타입의 상위 클래스에만 있는 `write` 메서드
- When 빈 참조 링커를 실행하면
- Then 스텝에서 `execute`, `write` 메서드로 가는 같은 `role`의 `USES`가 추가된다. 해당 메서드가 없으면 추가하지 않는다.

### F-012-AC23 (대상: F-012-R12)
- Given 인터페이스 `PaymentService`를 주입받는 타입과, 빈으로 정의된 구현이 하나인 경우 / 둘인 경우
- When 주입 후보 링커를 실행하면
- Then 하나면 구현 타입으로 `INFERRED` `INJECTS`(`resolvedFrom=<인터페이스 ID>`)가 추가된다. 둘이면 `qualifier`나 필드명이 빈 이름과 맞는 하나만 추가되고, 못 고르면 추가되지 않는다. 빈이 아닌 구현은 건너뛴다. (`attrs.candidates`가 있는 엣지는 F-012-AC24를 따른다)

### F-012-AC24 (대상: F-012-R12)
- Given `INJECTS`(주입받는 타입→인터페이스)에 `attrs.candidates`가 구현 타입 하나인 경우(구현 빈이 둘이고 `qualifier`로 좁혀진 것 포함), 둘인 경우, 후보가 그래프에 없는 타입이거나 주입받는 타입 자신인 경우, 이미 그 타입으로 가는 `INJECTS`가 있는 경우
- When 주입 후보 링커를 실행하면
- Then 후보가 하나면 그 타입으로 `INFERRED` `INJECTS`가 추가되고 `resolvedFrom=<선언된 대상 타입 ID>`, 원래 엣지의 `via`·`field`·`qualifier`와 위치가 옮겨지며 `candidates`는 옮기지 않는다. 원래 엣지는 남는다. 나머지 경우에는 추가되지 않는다(후보가 여럿이면 필드명이 빈 이름과 맞아도 링커가 다시 고르지 않는다). 주입 대상이 인터페이스가 아니어도 후보가 하나면 같다.

### F-012-AC25 (대상: F-012-R13)
- Given 엔드포인트 `GET /order/list` → `list`, `ANY /order/{id}` → `detail`과, `attrs.redirects=[/order/list?saved=1]`인 메서드, `attrs.forwards=[/order/{*}]`인 메서드, `forwards=[/WEB-INF/views/order/result.jsp]`인 메서드, 맞는 엔드포인트가 없는 경로와 외부 URL을 가진 메서드
- When 뷰 연결 링커를 실행하면
- Then 각각 `list`, `detail`로 `INFERRED` `CALLS`(`callType=redirect`/`forward`, `path`는 쿼리를 뗀 경로, `viewName` 없음)가 있고, JSP 경로로의 forward는 그 JSP로 `RENDERS`가 된다. 맞는 엔드포인트가 없으면 미해결 엣지(`CALLS`, 분류 `http-endpoint`)가 남고 외부 URL로는 아무것도 만들지 않는다. `viewNames` 없이 이 attrs만 있는 메서드도 처리한다.

### F-012-AC26 (대상: F-012-R14)
- Given `reference`로만 만들어진 `java:type:org.mybatis.spring.SqlSessionFactoryBean`·`JAVA_METHOD`·`JAVA_FIELD` 노드(`declared=false`, `external` 없음)와, 선언된 타입, `external=false`가 이미 있는 참조, `UNRESOLVED` 노드, `declared=false`인 테이블·빈 참조
- When 외부 참조 표시 링커(order 90)를 실행하면
- Then 앞의 세 노드에만 `external=true`가 붙고 나머지는 그대로다. 이 링커는 link 모듈의 링커 가운데 가장 먼저 돈다.

### F-012-AC27 (대상: F-012-R15, R24)
- Given properties에 `adminPath=/a`, `frontPath=/f`, `urlSuffix=.html`이 있고 엔드포인트 `ANY /${adminPath}/sys/user/form`, `ANY /${frontPath}/index-{siteId}${urlSuffix}`, `GET /${apiBase:/api}/ping`이 있는 그래프
- When 요청 `GET /a/sys/user/form`, `GET /f/index-1.html`, `GET /api/ping`, `GET /b/sys/user/form`을 맞추면
- Then 앞의 셋은 각 엔드포인트로 `RESOLVED`이고 엔드포인트 노드에 `resolvedPath`(`/a/sys/user/form`, `/f/index-{siteId}.html`, `/api/ping`)가 있다. `/b/…`는 미해결이다. 두 파일에 `adminPath` 값이 다르면 치환하지 않고(`resolvedPath` 없음) `/a/sys/user/form`도 미해결이다.

### F-012-AC28 (대상: F-012-R15)
- Given properties가 없고 엔드포인트 `ANY /${adminPath}`, `ANY /${frontPath}`, `ANY /${adminPath}/sys/user/form`이 있는 그래프
- When 요청 `GET /index-{*}`, `GET /x/sys/user/form`, `GET /{*}/sys/user/form`, `GET {*}/sys/user/form`, `GET /oa/oaNotify/{*}`, `GET {*}cordova_plugins.json`을 맞추면
- Then `/{*}/sys/user/form`만 `INFERRED` + `dynamicSegment=true`로, `{*}/sys/user/form`은 `INFERRED` + `strippedPrefix={*}` + `strippedPlaceholder=${adminPath}`로 이어지고 나머지는 미해결이다(자리표시자 세그먼트는 리터럴·섞인 조각과 맞지 않고, 접두를 떼고 남은 `{*}` 하나로 루트 핸들러에 붙지 않는다).

### F-012-AC29 (대상: F-012-R15)
- Given properties에 `adminPath=/a`, `frontPath=/f`, `urlSuffix=.html`이 있고 엔드포인트 `ANY /${adminPath}/sys/user/form`, `ANY /${adminPath}/oa/leave`, `ANY /${frontPath}/index-{siteId}${urlSuffix}`, `ANY /${frontPath}`, 값을 모르는 `ANY /${otherPath}/cms/link/form`, 그리고 `GET /sys/dict/form`과 `ANY /${adminPath}/sys/dict/form`이 함께 있는 그래프
- When 요청 `GET /sys/user/form`, `GET /oa/leave/`, `GET /index-{*}`, `GET /cms/link/form`, `GET /sys/dict/form`, `GET /`, `GET {*}/sys/user/form`을 맞추면
- Then 앞의 넷은 `INFERRED` + `strippedPlaceholder=<자리표시자 원문>`(끝 슬래시가 다르면 `trailingSlash=true`도)이고, `/sys/dict/form`은 정확히 맞는 `GET /sys/dict/form`으로 `RESOLVED`, `/`는 미해결(자리표시자뿐인 엔드포인트는 나머지가 없다), `{*}/sys/user/form`은 `strippedPrefix={*}`와 `strippedPlaceholder`가 함께 있다.

### F-012-AC30 (대상: F-012-R16)
- Given 엔드포인트 `GET /f/comment-{theme}`, `ANY /f/view-{categoryId}-{contentId}.html`, `ANY /f/list-{categoryId}.html`, `ANY /f/listc-{categoryId}-{customView}.html`, `GET /data/{name}.json`
- When 요청 `/f/view-{*}-{*}`, `/f/view-{*}-{*}.html`, `/f/list-{*}`, `/{*}/list-{*}`, `/f/comment-basic`, `/f/index-{*}`, `/data/menu.json`, `/data/menu.xml`을 맞추면
- Then `view-…` 요청은 `view-…` 엔드포인트 하나로만 이어지고(뼈대가 같으면 `RESOLVED`, 요청의 `{*}`가 `.html`을 덮어야 하면 `INFERRED` + `dynamicSegment`), `list-{*}`는 `list-…` 하나로만, `comment-basic`과 `menu.json`은 `RESOLVED`, `/f/index-{*}`와 `/data/menu.xml`은 미해결이다. 어느 요청도 `comment-{theme}`에 잘못 붙지 않는다.

### F-012-AC31 (대상: F-012-R17)
- Given 엔드포인트 `GET /board`, `POST /board`, `DELETE /schedule/{schdulId}`, `GET /board/{bbsId}/{nttId}`
- When 요청 `GET {*}/board`, `ANY {*}/board`, `DELETE {*}/board`, `GET {*}/board/{*}/{*}`, `GET {*}/x/board`를 맞추면
- Then `GET {*}/board`는 `GET /board` 하나로 `INFERRED` + `strippedPrefix={*}`, `ANY {*}/board`는 `GET /board`·`POST /board` 둘 다 `INFERRED` + `ambiguous`, `DELETE {*}/board`와 `{*}/x/board`는 미해결(`DELETE /schedule/{schdulId}`에 붙지 않는다), `{*}/board/{*}/{*}`는 `GET /board/{bbsId}/{nttId}`다.

### F-012-AC32 (대상: F-012-R18)
- Given 엔드포인트 `GET /news/article/{articleId}`, `GET /topic/{topicId}`, `GET /{page}`, `GET /order/detail/{id}`, `GET /catalog`와 `GET /catalog/`, `GET /a/test/test`
- When 기준을 알 수 없는 상대 요청 `article/index.html`, `../news/article/list`, `topic/index.html`, `signin`, `detail/{*}`, `catalog`, `../test/`를 맞추면
- Then `detail/{*}`는 `/order/detail/{id}`로, `catalog`는 `GET /catalog`로 `INFERRED`(`relative=true`)이고 나머지는 이어지지 않는다(정적 자원 확장자는 미해결 엣지도 남기지 않는다). 끝 슬래시만 다른 같은 엔드포인트는 후보 하나로 세고, 디렉터리를 가리키는 상대 링크(`../test/`)는 끝 슬래시 없는 엔드포인트의 접미로 잇지 않는다.

### F-012-AC33 (대상: F-012-R18)
- Given `GET /sso/index`의 핸들러가 `sso/index.jsp`를 그리고(`RENDERS`), 엔드포인트 `POST /sso/signin`, `POST /member/signin`, `GET /member/logout`이 있는 그래프
- When `sso/index.jsp` 안의 상대 요청 `POST signin`, `GET logout`, `POST ../member/signin`, `GET member/logout`과, 어느 핸들러도 그리지 않는 페이지의 `POST signin`을 맞추면
- Then `signin`은 `POST /sso/signin`으로 `INFERRED`(`relative=true`, `relativeBase=/sso`), `../member/signin`은 `POST /member/signin`으로 이어지고, `logout`은 미해결이다(기준 디렉터리에 없으면 다른 디렉터리의 같은 이름으로 잇지 않는다). 여러 조각인 `member/logout`은 엔드포인트 경로 전체와 같으므로 `INFERRED`(`relativeBase` 없음)로 잇는다. 기준을 모르는 페이지의 `signin`은 후보가 둘이라 미해결이다. 뷰 연결 링커가 만든 `RENDERS`도 같은 실행에서 기준으로 쓰인다. `viewNames=[redirect:list]`인 `POST /order/save`의 핸들러는 `GET /order/list`의 핸들러로 `CALLS`(`relativeBase=/order`)가 된다.

### F-012-AC34 (대상: F-012-R19)
- Given 엔드포인트 `GET /orders`, `GET /list/`, `GET /x`, `GET /x/`
- When 요청 `/orders/`, `/list`, `/orders`, `/x/`, `/x`를 맞추면
- Then `/orders/`와 `/list`는 각각 `RESOLVED` + `trailingSlash=true`로 이어지고, `/orders`에는 `trailingSlash`가 없다. `/x/`는 `GET /x/`로, `/x`는 `GET /x`로만 이어진다.

### F-012-AC35 (대상: F-012-R20)
- Given 엔드포인트 `GET /`, `GET /{page}`, `GET /orders`
- When 요청 `/`, `/shop/`, `/shop/app/{*}`, `/ctx/orders`, `http://localhost:8080/`, `/a/b/c`를 맞추면
- Then `/`는 `GET /`로 `RESOLVED`, 호스트가 붙은 `/`는 `INFERRED`, `/shop/`은 `/{page}`와 정확히 맞고(`strippedPrefix` 없음), `/ctx/orders`는 `strippedPrefix=/ctx`로 `GET /orders`에 이어진다. `/shop/app/{*}`와 `/a/b/c`는 미해결이다(접두를 떼고 남은 것에 일치하는 고정 세그먼트가 없다).

### F-012-AC36 (대상: F-012-R21)
- Given 모듈 `web-a`(pom.xml), `web-b`(build.gradle), `web-c`(pom.xml, 핸들러 없음)와, 핸들러가 두 모듈에 다 있는 `GET /manage/index`, `web-b`에만 있는 `GET /sso/login`, `web-a`의 `GET /item/{id}`, `web-b`의 `GET /item/new`
- When `web-a`의 JSP에서 `/manage/index`, `/sso/login`, `/item/new`를, 빌드 파일이 없는 `front/` 폴더와 `web-c`, `web-b`의 파일에서 `/sso/login`을 요청하면
- Then `web-a`의 `/manage/index`는 `RESOLVED`(`crossModule` 없음), `/sso/login`은 `INFERRED` + `crossModule=true`, `/item/new`는 같은 모듈의 `/item/{id}`로만 이어진다. `front/`·`web-c`·`web-b`의 `/sso/login`은 `RESOLVED`다. `web-a`의 핸들러가 `redirect:/manage/index`, `redirect:/sso/login`을 돌려주면 `CALLS`는 `web-a`의 `index`와 `web-b`의 `login`(`crossModule=true`)으로만 간다.

### F-012-AC37 (대상: F-012-R22)
- Given properties에 `web.view.prefix=/WEB-INF/views/`, `web.view.suffix=.jsp`가 있고 뷰 리졸버 빈의 `property.prefix=${web.view.prefix}`, `property.suffix=${web.view.suffix}`인 그래프
- When `viewNames=[modules/sys/userList]`를 풀면
- Then `/WEB-INF/views/modules/sys/userList.jsp`로 `RESOLVED` `RENDERS`(`resolverPrefix=/WEB-INF/views/`)가 있다. 값을 찾지 못한 자리표시자 리졸버는 쓰지 않아, 리졸버 정보가 없을 때처럼 `/<이름>.jsp`로 끝나는 JSP가 하나일 때만 `INFERRED`로 잇는다.

### F-012-AC38 (대상: F-012-R23)
- Given properties에 `Globals.DbType = mysql`이 있고, 정의된 빈 `dataSource-mysql`(클래스가 그래프의 타입)과 `dataSource-oracle`, 그리고 `spring:bean:dataSource-${Globals.DbType}`, `spring:bean:cache-${Globals.Cache}`(키 없음), `spring:bean:mq-${Globals.DbType}`(치환한 이름의 빈 없음)을 가리키는 `INJECTS`
- When 빈 참조 링커를 실행하면
- Then 첫 참조에는 `spring:bean:dataSource-mysql`로 가는 `INJECTS`(`resolvedFrom=<원래 빈 ID>`)와 그 빈의 타입으로 가는 `INJECTS`(`resolvedFrom=spring:bean:dataSource-mysql`)가 더해지고 원래 엣지는 남는다. 나머지 두 이름으로는 엣지가 더해지지 않고 `INFO` 진단 `UNRESOLVED_BEAN_PLACEHOLDER`가 1건(두 이름 포함) 있다.

### F-012-AC39 (대상: F-012-R24)
- Given 키마다 값이 하나인 파일, 값이 서로 다른 두 파일, 값 안에 다른 자리표시자가 든 키, 테스트 소스의 프로퍼티 파일, 모듈마다 값이 다른 키, 문서(프로파일)마다 값이 다른 YAML
- When 공용 헬퍼로 조회·치환하면
- Then 값이 하나로 모이면 치환하고(여러 파일에 같은 값 포함), 값이 갈리면 모호라서 원문 `${키}`를 남기고 `${키:기본값}`으로도 덮지 않는다. 키가 어디에도 없을 때만 기본값을 쓴다. 테스트 소스의 값은 참조하는 파일이 테스트 소스일 때만 보고, 참조하는 파일과 같은 모듈의 값을 먼저 쓴다(자기 모듈에 없으면 프로젝트 전체). YAML은 중첩 키를 점으로 이어 읽는다.

### F-012-AC40 (대상: F-012-R25)
- Given `CrudDao#findList`를 상속만 한 `UserDao`와 직접 선언한 `RoleDao`, 구문 `UserDao.findList`, `RoleDao.findList`
- When 매퍼 바인딩 링커를 실행하면
- Then `CrudDao#findList → UserDao.findList`는 `INFERRED`이고 `namespace=com.a.UserDao`, `inherited=true`, `declaredIn=<CrudDao 타입 ID>`가 있다. `RoleDao#findList → RoleDao.findList`는 `RESOLVED`이고 이 키들이 없으며, `CrudDao#findList`에서 `RoleDao.findList`로 가는 엣지는 없다.

### F-012-AC41 (대상: F-012-R26)
- Given 테스트 소스의 `@Bean` 메서드만 정의한 빈 `dataSource`(와 위치가 테스트 소스인 `class`만 있는 빈)을 main의 XML과 테스트의 XML이 참조하는 그래프, 그리고 두 모듈이 같은 이름으로 정의한 빈 `userService`를 각 모듈과 모듈 밖의 파일이 참조하는 그래프
- When 빈 참조 링커를 실행하면
- Then main의 참조에는 타입으로 가는 엣지가 더해지지 않고 테스트의 참조에만 더해진다. `userService` 참조는 참조하는 쪽과 같은 모듈의 정의 타입으로만 이어지고, 모듈로 고를 수 없는 참조에는 엣지가 더해지지 않는다.

### F-012-AC42 (대상: F-012-R27)
- Given 인터페이스를 구현한 타입 셋: `stereotype=filter`만 있는 타입, `stereotype=interceptor`만 있는 타입, `stereotype=filter`에 `beanName`과 `DEFINES_BEAN`이 있는 타입. 그리고 `stereotype=interceptor`, `beanName=vip`(DEFINES_BEAN 없음)인 타입과 `qualifier=vip`·`qualifier=vipDiscountPolicy`인 주입
- When 주입 후보 링커를 실행하면
- Then 빈으로 정의된 필터 하나로만 `INFERRED` `INJECTS`가 더해지고(`stereotype`만 있는 둘은 후보가 아니다), `qualifier=vip`인 주입은 `beanName=vip`인 타입으로 이어지며 기본 이름(`vipDiscountPolicy`)으로는 맞추지 않는다.

### F-012-AC43 (대상: F-012-R28)
- Given `viewNames`(JSP 뷰, `redirect:`, `forward:` 엔드포인트, `forward:` JSP, 없는 뷰)와 `viewNameLines`, `redirects`·`forwards`와 `redirectLines`·`forwardLines`가 있는 메서드. 그리고 병렬 목록이 없는 메서드, 길이가 다른 메서드, 읽을 수 없는 값(`x:y`)이 섞인 메서드
- When 뷰 연결 링커를 실행하면
- Then 각 `RENDERS`·`CALLS`·미해결 엣지(와 그 진단)의 위치는 메서드의 파일의 그 `줄:열`이다(열이 없으면 줄만). 병렬 목록이 없거나 길이가 다르면 전부 메서드 위치이고, 읽을 수 없는 항목만 메서드 위치다.

### F-012-AC44 (대상: F-012-R29)
- Given 경로가 같은 엔드포인트가 있는 `clientRoute=true` 요청, 맞는 엔드포인트가 없는 `clientRoute=true` 요청, 같은 경로의 보통 요청
- When HTTP 연결 링커를 실행하면
- Then `clientRoute` 요청에는 `MATCHES`도 미해결도 진단도 없고 보통 요청은 전처럼 이어진다.

### F-012-AC45 (대상: F-012-R30)
- Given 뷰 리졸버 빈, `tilesDefinitions`(`order.list=/WEB-INF/layouts/main.jsp` 등)가 있는 FILE 노드, 리졸버 규칙으로도 맞는 같은 이름의 JSP, Tiles 정의에 없는 뷰 이름
- When 뷰 연결 링커를 실행하면
- Then 정의에 있는 뷰 이름은 template JSP로만 `RENDERS`(`RESOLVED`, `via=tiles`, `definition`, 리졸버 키 없음, 위치는 호출 지점)가 되고, 없는 뷰 이름은 리졸버 규칙대로다. `tilesDefinitions`가 없는 FILE 노드만 있으면 기존 동작이다.

### F-012-AC46 (대상: F-012-R30)
- Given 와일드카드 정의(`*/*=…/{1}Layout.jsp`, `*.popup=…`, `admin/**=…`), 같은 뷰 이름과 맞는 정확한 이름의 정의, template JSP가 없는 정의, 동적 뷰 이름(`admin/{*}`), `*`가 `/`를 넘어야 맞는 뷰 이름
- When 뷰 연결 링커를 실행하면
- Then `{n}`을 치환한 template JSP로 잇고 `definition`은 정의 이름 원문이다. 정확한 이름이 와일드카드보다 먼저다. `**`는 `/`를 포함해 맞는다. template JSP가 없는 정의·동적 뷰 이름·어느 정의와도 맞지 않는 이름은 뷰 미해결이다.

### F-012-AC47 (대상: F-012-R30)
- Given `tilesAttributes`(와일드카드 정의의 본문 `/WEB-INF/views/{1}/{2}.jsp`, 헤더 JSP, 없는 JSP, 다른 정의의 것)와 template이 빈 정의
- When 뷰 연결 링커를 실행하면
- Then 맞은 정의의 template과 attribute JSP들로 `RENDERS`(`via=tiles`, `definition`, attribute 쪽은 `tilesAttribute=true`)가 생기고, 없는 attribute JSP는 미해결 없이 건너뛴다. template이 빈 정의는 건너뛰어 그 뷰 이름은 리졸버 규칙(여기서는 미해결)으로 간다.

### F-012-AC48 (대상: F-012-R32)
- Given `viewNames` 셋 가운데 둘이 `viewNameCandidates`에 든 메서드(JSP는 모두 있다)
- When 뷰 연결 링커를 실행하면
- Then 후보 둘의 `RENDERS`는 `INFERRED` + `candidate=true`, 나머지는 `RESOLVED`다.

### F-012-AC49 (대상: F-012-R33)
- Given `adminPath=/a`인 properties, 엔드포인트 `/a/login`·`/a`·`/login`, 뷰 이름 `redirect:${adminPath}/login?x=1`·`redirect:${adminPath}`·`redirect:${unknownPath}/login`, `redirects`의 `${adminPath}/login`
- When 뷰 연결 링커를 실행하면
- Then 앞의 셋(뷰 이름 둘과 `redirects`)은 치환한 경로의 핸들러로 `CALLS`(`path`는 치환한 경로, `viewName`은 원문)이고, 값을 모르는 것은 `{*}/login`으로 읽어 `/login`의 핸들러로 `INFERRED`(`strippedPrefix={*}`)다.

### F-012-AC50 (대상: F-012-R34)
- Given `viewNames`가 `/index.jsp`, `about`, `redirect:/order/list`인 `HTTP_ENDPOINT` 셋(`viewController=true`), 웹 루트의 `index.jsp`와 `WEB-INF/views/index.jsp`, 뷰 리졸버 빈
- When 뷰 연결 링커를 실행하면
- Then 첫째는(리졸버로 푼 경로에 JSP가 없으므로) 웹 루트의 `index.jsp`로 `RESOLVED` `RENDERS`(위치는 엔드포인트의 위치), 둘째는 리졸버로 푼 JSP로 `RENDERS`, 셋째는 그 엔드포인트의 핸들러로 `INFERRED` `CALLS`(`callType=redirect`)다. 두 모듈의 설정 파일이 `GET /`에 `/index.jsp`를 선언했고(`viewDeclarations`) 리졸버가 `prefix=/WEB-INF/jsp`, `suffix=`이면, 선언마다 그 모듈의 `WEB-INF/jsp/index.jsp`로 `RESOLVED` `RENDERS`가 하나씩 생기고 위치는 그 선언이다.

### F-012-AC51 (대상: F-012-R35)
- Given `forwards`가 웹 루트 바로 아래에 있는 JSP 경로(같은 경로가 `WEB-INF/jsp` 아래에도 있다), `WEB-INF/jsp` 아래에만 있는 경로, 두 곳에 접미사로 맞는 경로인 메서드들과 `forward:` 뷰 이름으로 같은 경로를 쓴 핸들러
- When 뷰 연결 링커를 실행하면
- Then 첫째는 웹 루트의 JSP 하나로 `RESOLVED`(`suffixMatch`·리졸버 키 없음), 둘째는 `INFERRED` + `suffixMatch=true`, 셋째는 뷰 미해결이다. `forward:` 뷰 이름은 전처럼 `RESOLVED`다.

### F-012-AC52 (대상: F-012-R31)
- Given `statementPrefix=LoginUsr.updateLoginIncorrect`인 미해결 `EXECUTES`와 그 접두로 시작하는 구문 둘(다른 namespace의 같은 id 포함), 접두로 시작하는 구문이 여섯인 경우, 하나도 없는 경우
- When 매퍼 바인딩 링커를 실행하면
- Then 첫째에는 구문 둘로 `INFERRED` `EXECUTES`(`prefixMatch=true`, `statementPrefix`, `operation`, 위치는 원래 엣지의 것)가 더해지고 원래 엣지는 남는다. 나머지 둘에는 더해지지 않는다.

### F-012-AC53 (대상: F-012-R36)
- Given 컨트롤러가 없는 웹 모듈·컨트롤러가 있는 모듈·빌드 파일이 없는 폴더에서 `GET /`를 요청하고, `GET /`의 핸들러가 컨트롤러가 있는 모듈에만 있는 프로젝트
- When HTTP 연결 링커를 실행하면
- Then 컨트롤러가 없는 모듈의 요청은 `INFERRED` + `crossModule=true`, 같은 모듈의 요청은 `RESOLVED`, 모듈을 모르는 요청은 판단하지 않아 `RESOLVED`다. (F-012-AC36의 "핸들러가 없는 모듈: 판단하지 않는다"는 이 기준으로 바뀌었다.)

### F-012-AC54 (대상: F-012-R37)
- Given war 모듈 셋(cms-web, pay-web, upms-server)이 모두 `<mvc:view-controller path="/">`를 선언하고 핸들러 메서드는 cms-web에만 있는 `GET /`(노드 하나, `viewDeclarations` 셋), pay-web·cms-web의 JSP에서 낸 `GET /` 요청. 그리고 선언이 한 모듈에만 있는 엔드포인트로의 같은 모듈 요청
- When HTTP 연결 링커를 실행하면
- Then 두 요청 모두 `RESOLVED`이고 `crossModule`이 없으며 `module`은 각각 `pay-web`, `cms-web`이다. 선언이 한 모듈뿐인 엔드포인트의 매칭에는 `module`이 없다.

### F-012-AC55 (대상: F-012-R38)
- Given 핸들러가 war 모듈 cms-admin에만 있는 `GET /manage/index`와, war 모듈·`web.xml`이 있는 모듈·`@SpringBootApplication` 클래스가 있는 모듈·jar 모듈(정적 자원)·cms-admin 자신에서 낸 같은 요청
- When HTTP 연결 링커를 실행하면
- Then 앞의 셋은 미해결(`http-endpoint`)이고, jar 모듈의 요청은 `INFERRED` + `crossModule=true`, cms-admin의 요청은 `RESOLVED`다.

### F-012-AC56 (대상: F-012-R37, R38)
- Given `GET /`를 view-controller로만 선언한 war 모듈 pay-web의 핸들러, 핸들러가 있는 cms-web의 핸들러, 선언이 없는 war 모듈 api-server의 핸들러가 각각 `redirect:/`를 돌려준다
- When 뷰 연결 링커를 실행하면
- Then pay-web의 핸들러에는 `CALLS`가 없고(미해결도 없다), cms-web의 핸들러는 자기 모듈의 `IndexController#index`로 가며, api-server의 핸들러는 미해결(`unresolved:http-endpoint:GET /`)이다.

### F-012-AC57 (대상: F-012-R39)
- Given 엔드포인트 `DELETE|GET|PUT /schedule/{id}`, `POST /schedule`, 서블릿 매핑 `/api/*`와 요청 `ANY /api/schedule/{*}`(`httpMethods=[POST, PUT]`). 그리고 `POST /item`·`PUT /item`·`DELETE /item`, 메서드를 가리지 않는 `/legacy.do`, `GET /only-get`으로 가는 같은 후보의 요청들
- When HTTP 연결 링커를 실행하면
- Then 첫 요청은 `PUT /schedule/{id}`로만 가고 `ambiguous`가 없다(`httpMethods`가 없으면 전처럼 셋 모두). `/item`은 `POST`·`PUT` 둘로 `INFERRED` + `ambiguous`, `/legacy.do`는 그 엔드포인트로, `/only-get`은 미해결이다.

### F-012-AC58 (대상: F-012-R40)
- Given `GET /user/form`의 핸들러가 그리는 JSP 안의 `selfSubmit=true` 요청(POST, GET)과 `POST /user/form`·`POST /user/other` 엔드포인트. 그리고 두 핸들러(`GET /board/new`, 메서드를 가리지 않는 `/board/edit.do`)가 그리는 JSP의 POST 폼, 그리는 핸들러가 없는 JSP의 폼, `GET /view`만 있는 화면의 POST 폼
- When HTTP 연결 링커를 실행하면
- Then POST 폼은 `POST /user/form`으로, GET 폼은 `GET /user/form`으로 `INFERRED` + `selfSubmit=true`다. 두 핸들러가 그리는 폼은 `POST /board/new`와 `/board/edit.do` 둘로 가고, 나머지 둘은 미해결이다.

### F-012-AC59 (대상: F-012-R41)
- Given 접두 `Board.`으로 시작하는 구문 7개(INSERT 둘, DELETE, CALL, SELECT 둘, 종류 없음)와 `insert`·`selectList`·종류를 모르는 연산의 접두 미해결 `EXECUTES`
- When 매퍼 바인딩 링커를 실행하면
- Then `insert` 호출은 INSERT 둘·DELETE·CALL·종류 없는 구문으로, `selectList` 호출은 SELECT 둘·CALL·종류 없는 구문으로 `INFERRED` `EXECUTES`가 있고, 종류를 모르는 연산은 거르지 않아 7개라 잇지 않는다.

### F-012-AC60 (대상: F-012-R42)
- Given 접두로 풀리는 미해결 `EXECUTES`, 같은 미해결 노드로 들어오는 엣지 가운데 하나만 풀리는 경우, 후보가 5개를 넘어 풀리지 않는 경우
- When 매퍼 바인딩 링커를 실행하면
- Then 풀린 엣지의 `resolvedFrom`은 미해결 노드 ID이고 위치가 미해결 엣지와 같다. 들어오는 엣지가 전부 풀린 미해결 노드에만 `resolvedBy=prefixMatch`가 있다.

### F-012-AC61 (대상: F-012-R43)
- Given `statementIdLiterals=[ZipManageDAO.insertExcelZip, fail.common.msg, ZipManageDAO.selectZip]`(병렬 `statementIdLiteralLines`)인 메서드, 구문 `ZipManageDAO.insertExcelZip`·`ZipManageDAO.selectZip`, 그 메서드에서 `selectZip`으로 가는 기존 `EXECUTES`
- When 매퍼 바인딩 링커를 실행하면
- Then `insertExcelZip`으로 `EXECUTES`(`INFERRED`, `viaArgument=true`, 위치는 그 인자의 줄)가 하나 더해지고, 구문이 없는 문자열과 이미 이어진 구문에는 아무것도 더하지 않는다. 미해결 노드가 없다.

### F-012-AC62 (대상: F-012-R44)
- Given 리졸버(`/WEB-INF/views/`, `.jsp`)와 `themes/basic/frontIndex.jsp`·`themes/weixin/frontIndex.jsp`·`themes/basic/frontMap.jsp`, 뷰 이름 `…/themes/{*}/frontIndex`·`…/themes/{*}/frontMap`. 그리고 후보가 6개인 이름, 동적 조각이 마지막 조각·조각의 일부·둘인 이름, 후보가 없는 이름, 파일 경로 꼴의 `/{*}/qa/index.html`
- When 뷰 연결 링커를 실행하면
- Then `frontIndex`는 두 JSP로 `INFERRED` + `candidate=true` + `dynamicSegment=true`, `frontMap`은 한 JSP로 `INFERRED` + `dynamicSegment=true`(`candidate` 없음)다. 후보가 6개이거나 꼴이 맞지 않거나 후보가 없는 이름은 전처럼 뷰 미해결이고, `/{*}/qa/index.html`은 그 경로로 끝나는 페이지로 `suffixMatch=true`다.

### F-012-AC63 (대상: F-012-R45)
- Given 메서드만 다른 두 엔드포인트가 같은 핸들러로 가는 경로와 다른 핸들러로 가는 경로, 각각으로 `forward:`하는 핸들러, `ANY` 요청, `selfSubmit` 폼
- When 뷰 연결·HTTP 연결 링커를 실행하면
- Then 같은 핸들러로 가는 forward `CALLS`는 하나이고 `ambiguous`가 없다. 그래프의 `ambiguous=true` 엣지는 같은 (출발, 종류, 위치)에 둘 이상 있다.

### F-012-AC64 (대상: F-012-R46)
- Given `GET /topic/{topicId}`의 핸들러가 그리는 페이지 안의 상대 링크 `login.html`, `list.html`(`GET /topic/list.html`이 있다), `42`
- When HTTP 연결 링커를 실행하면
- Then `login.html`에는 `MATCHES`가 없고(미해결도 없다: 정적 자원), `list.html`은 `GET /topic/list.html`로, `42`는 `GET /topic/{topicId}`로 간다.

### F-012-AC65 (대상: F-012-R47)
- Given 여러 모듈 프로젝트. (1) 모듈 `ucenter`의 핸들러가 `/${app.name}/login.html`을 돌려주고, 그 모듈의 프로퍼티에 `app.name=${app.name}`(config)과 `app.name=member-web`(프로파일 둘)이 있고, 페이지 `ui/member-web/login.html`·`admin/src/login.html`·`ui/ucenter/login.html`이 있다. (2) 프로퍼티 값이 가리키는 디렉터리에 그 뷰가 없는 이름, 값을 모르는(없거나 갈리는) 자리표시자의 이름. (3) 모듈 `ucenter/ucenter-web`의 핸들러가 `/{*}/login.html`·`/{*}/index.html`을 돌려주고 `ui/ucenter-web/`에 둘 다 있으며 `index.html`은 다른 디렉터리 6곳에도 있다. (4) 근거가 없는 `themes/{*}/frontIndex`(테마 둘)와 후보 6개인 이름
- When 뷰 연결 링커를 실행하면
- Then (1) `ui/member-web/login.html` 하나로 `INFERRED` + `dynamicSegment=true` + `narrowedBy=property`(`candidate` 없음, `viewName`은 원문 그대로)다. (2) 값이 가리키는 디렉터리에 뷰가 없으면 뷰 미해결이고, 값을 모르는 자리표시자는 `{*}`처럼 다룬다. (3) 둘 다 `ui/ucenter-web/`의 페이지 하나로 `narrowedBy=moduleName`이다(후보가 7개였던 `index.html`도). (4) 테마 둘은 전처럼 `candidate=true`(`narrowedBy` 없음), 6개는 미해결이다. `ProjectProperties`는 `app.name=${app.name}`·`@at.name@`를 값으로 치지 않는다.

### F-012-AC66 (대상: F-012-R48)
- Given 루트 `pom.xml`(war 아님)과 war 모듈 `cms-web`·`pay-web`·`upms-server`, 여러 모듈의 view-controller가 모인 `GET /`(핸들러는 `cms-web`), 루트 모듈의 페이지 `ui/cms-web/qa/index.html`(`cms-web`의 핸들러가 그린다)과 그 페이지가 포함하는 조각·스크립트, 그리는 핸들러가 없는 페이지, 두 모듈의 핸들러가 그리는 페이지, 그 엔드포인트의 선언이 없는 모듈의 핸들러가 그리는 페이지, 선언이 한 모듈뿐인 엔드포인트로 가는 요청
- When HTTP 연결 링커를 실행하면
- Then `cms-web`의 핸들러가 그리는 페이지(와 그 조각·스크립트)의 `GET /` 요청은 `INFERRED` + `crossModule=true` + `module=cms-web`이다. view-controller가 그리는 페이지는 선언한 설정 파일의 모듈이다. 그리는 핸들러가 없거나 여러 모듈이거나 그 모듈에 선언이 없거나 동적 디렉터리 뷰의 여러 후보 가운데 하나로만 그려지면 `crossModule`만 있고, 선언이 한 모듈뿐인 엔드포인트에는 `module`이 없다. 같은 경로를 두 모듈이 따로 선언한 엔드포인트 둘과 맞으면 그리는 모듈의 것만 남는다(`ambiguous` 없음).

### F-012-AC67 (대상: F-012-R49)
- Given 엔드포인트 `GET /index`·`/manage/index`·`/manage/user/list`·`/orders`와 요청 `/manage/coder/index`, `/manage/orders`, `http://localhost:8080/manage/coder/index`, `{*}/manage/coder/index`, `/manage/index`, `/shop/orders`, `/shop/app/index`, 핸들러의 `redirect:/manage/coder/index`. 그리고 서블릿 매핑 `/api/*`와 엔드포인트 `/api/ping`·`/{tenant}/home`·`/${adminPath}/menu`(값 모름)가 있는 프로젝트의 요청 `/api/orders`, `/t1/orders`, `/admin/orders`
- When 뷰 연결·HTTP 연결 링커를 실행하면
- Then `/manage/…`의 죽은 링크 넷과 redirect는 미해결이다(`GET /index`·`/orders`로 잇지 않는다). `/manage/index`는 `RESOLVED`, `/shop/orders`는 `strippedPrefix=/shop`, `/shop/app/index`는 `strippedPrefix=/shop/app`으로 전과 같다. 서블릿 매핑 접두(`/api`)는 `/api/…` 엔드포인트가 있어도 떼고, 경로 변수·값 모르는 자리표시자로 시작하는 엔드포인트는 `/t1`·`/admin`을 떼는 것을 막지 않는다.

## 5. 가정
- 링커 순서: 외부 참조 표시 90 → DB 객체 정규화 100 → 매퍼 바인딩 200 → 뷰 연결 220 → HTTP 연결 225 → 빈 참조 230 → 주입 후보 240 → 관계 추론(F-018) 300. (2026-10-11 고쳐 씀: HTTP 연결을 210에서 225로 옮겼다. 상대 URL의 기준 디렉터리를 뷰 연결이 만든 `RENDERS`에서 얻기 때문이다(F-012-R18). 뷰 연결은 `MATCHES`를 쓰지 않는다)
- **요청을 맞추는 순서(F-012-R7~R9, R15~R21)**: (1) 경로 전체가 엔드포인트 경로 전체(자리표시자는 치환한 경로)와 맞는가 → (2) 엔드포인트 경로에서 맨 앞 자리표시자 세그먼트를 뗀 나머지와 맞는가(`strippedPlaceholder`) → (3) 요청의 접두 세그먼트(서블릿 매핑, 그다음 1개·2개)를 떼면 맞는가(`strippedPrefix`). 앞 단계에서 맞으면 뒤는 보지 않고, (2)·(3)은 고정 세그먼트가 하나 이상 일치해야 한다. 각 단계에서 모듈 경계로 후보를 좁힌 뒤(F-012-R21) 가장 구체적인 것을 고른다. 맨 앞이 `{*}`인 요청은 그 `{*}`를 뗀 나머지로 (1)·(2)와 서블릿 매핑 접두만 본다. `{*}text`처럼 `{*}`가 첫 조각에 붙은 URL은 (1)만 본다.
- **구체성(비용)**: 리터럴=리터럴 0 < 섞인 세그먼트의 리터럴이 맞음(뼈대가 같거나 리터럴 요청이 정규식에 맞음 1, 뼈대가 달라 요청의 `{*}`가 리터럴을 덮어야 함 2, 섞인 요청 조각이 리터럴 엔드포인트 조각과 맞음 3) ≈ 변수가 리터럴을 받음 2 < 변수가 섞인 요청 조각을 받음 4 < 전부 동적인 요청 조각(`{*}`)이 고정 글자·자리표시자와 맞음 1000. 끝 슬래시가 다르면 1을 더한다. 추측(요청의 `{*}`가 고정 글자를 덮음, 값 모르는 자리표시자)이 섞이면 `INFERRED` + `dynamicSegment=true`다.
- **섞인 세그먼트끼리의 대응(F-012-R16)**: 변수 자리를 `*`로 바꾼 뼈대가 같으면 맞는다. 뼈대가 다르면 양쪽 다 리터럴로 시작하고 두 뼈대를 모두 만족하는 문자열이 있을 때만 맞는다(`list-*`와 `list-*.html`). 겹치는 근거가 확장자뿐인 것(`*.html`과 `index-*.html`)은 맞추지 않는다.
- **자리표시자가 든 엔드포인트 경로(F-012-R15)**: 값은 핸들러 메서드 파일을 기준으로 조회한다. 핸들러가 여러 모듈에 있고 모듈마다 다르게 풀리면 치환하지 않는다. `adminPath=/a`처럼 값이 `/`로 시작해 겹친 `/`는 하나로 줄인다. 일부만 풀린 경로는 매칭에는 쓰되 `resolvedPath`로 남기지 않는다. 노드에 `resolvedPath`가 이미 있으면 그 값을 쓴다. `strippedPlaceholder`는 값을 찾았든 못 찾았든 같은 규칙이다(JSP의 `${ctx}`가 `contextPath + adminPath`인 프로젝트에서 요청 경로에는 `adminPath`가 없다).
- **상대 URL(F-012-R18)**: 요청이 든 페이지는 요청의 위치 파일로 찾는다(`jsp:page:<파일>`). 그 페이지를 include하는 다른 페이지의 핸들러는 기준으로 삼지 않는다. 기준 디렉터리는 엔드포인트 경로에서 마지막 세그먼트를 뗀 것이고(끝이 `/`면 경로 그대로) 경로 변수는 `{*}`로 둔다. 자리표시자나 섞인 세그먼트가 든 디렉터리는 기준으로 쓰지 않는다. 기준이 여럿이면 각각 풀어 맞는 것을 모으고 여럿이면 `ambiguous`다. 기준으로 푼 경로에 엔드포인트가 없을 때, 조각이 둘 이상이고 `..`이 없는 상대 경로가 엔드포인트 경로 전체와 같으면(맨 앞의 `/`를 빠뜨린 `action="cop/bbs/list.do"`) 그 엔드포인트 하나로 잇는다.
- **모듈 경계(F-012-R21)**: 엔드포인트 노드는 메서드와 경로가 같으면 하나라 여러 웹 모듈의 핸들러(`ROUTES_TO`)가 한 노드에 모인다. `MATCHES`는 노드로 가므로 "같은 모듈의 핸들러만"을 엣지로 표현하지 못한다(소비자가 `ROUTES_TO`를 따라갈 때 요청 파일과 같은 모듈의 핸들러를 골라야 한다. 6절). 핸들러 위치를 모르는 엔드포인트는 다른 모듈이라고 단정하지 않는다.
- **빈 정의의 파일(F-012-R26)**: `DEFINES_BEAN`의 출발 노드(타입, `@Bean` 메서드)의 위치이고, `DEFINES_BEAN` 없이 `class`로 찾은 정의는 빈 노드의 위치다. 참조하는 쪽의 파일은 엣지의 위치, 없으면 출발 노드의 위치다. 주입 후보 링커가 쓰는 "빈으로 정의된 타입" 목록은 전과 같이 모든 정의를 본다.
- 외부 참조 표시(F-012-R14): springxml·mybatis는 XML의 클래스 이름을 `java:type:` 참조로만 만들고, 그 타입을 java 분석기가 나중에 선언할지는 만드는 시점에 알 수 없다. 그래서 링커가 "끝내 선언되지 않은" Java 참조에 표시한다. java가 직접 참조한 외부 타입에는 java가 이미 붙여 둔다. 프로젝트 소스에 있어야 할 타입인데 분석 범위에서 빠진(소스 루트 밖, 파싱 실패) 타입도 같은 표시를 받는다(소스를 보지 못한 타입이라는 뜻에서 같다).
- 서블릿 API의 redirect·forward(F-012-R13): java 분석기가 메서드 노드에 넣는 `redirects`·`forwards`는 컨텍스트 경로를 뗀 경로 패턴이고 동적 조각은 `{*}`다. 처리는 뷰 이름 접두사(`redirect:`·`forward:`)와 같다: redirect는 `GET`, forward는 `ANY`로 엔드포인트를 맞추고, 엣지의 위치는 메서드의 위치, 맞는 엔드포인트가 없으면 미해결 `CALLS`(분류 `http-endpoint`), 스킴이 붙은 대상은 미해결로 남기지 않는다. forward 대상이 `.jsp`·`.jspf`로 끝나면 그 경로로 끝나는 JSP로 `RENDERS`(`viewName`에 그 경로)를 만들고, 그런 JSP가 없으면 `forward:` 뷰 이름과 같이 미해결 `RENDERS`(분류 `view`)다. 뷰 이름에서 온 엣지와 달리 `CALLS`에 `viewName`은 없다. 같은 메서드의 `redirect:` 뷰 이름과 대상이 겹치면 엣지는 하나다.
- 주입 후보(F-012-R12): `candidates`는 java 분석기가 qualifier → 필드명 → `@Primary` → 전부 순으로 고른 `JAVA_TYPE` 노드 ID 목록이다(design 3.5). 링커는 이 목록을 다시 좁히지 않는다. 후보가 그래프에 없는 타입이면 엣지를 만들지 않는다. `candidates` 길로 만든 엣지에는 `matchedBy`가 없다(무엇으로 골랐는지는 java가 정했다).
- 엣지를 지우는 API가 없으므로 시노님 경유 표시는 "속성을 붙인 엣지를 먼저 넣고 `replaceNode`로 합치는" 방식으로 한다. 합쳐서 생긴 자기 참조 엣지는 `JOINS`(실제 자기 조인)만 남긴다.
- 테이블 스텁과 이름이 같은 시퀀스·루틴·패키지만 정의돼 있을 때는 그 스텁으로 들어오는 엣지가 전부 `ALIAS_OF`일 때만 합친다.
- 시노님의 최종 대상이 정의돼 있지 않아도 스텁은 그 대상 노드(`declared=false`)로 합친다. 시노님과 대상의 이름이 같으면 그대로 둔다.
- 노드가 없는 `db:table:`·`db:routine:` 엣지 끝점은 `declared=false` 노드로 만들어 마무리 단계가 UNRESOLVED로 바꾸지 않게 한다.
- 2조각 루틴 이름 `A.B`에서 `A`가 알려진 스키마(`attrs.schema`에 나온 값)도 패키지도 아닌데 독립 루틴 `B`가 있으면 `INFERRED`로 합친다.
- 상위 인터페이스에서 물려받은 매퍼 메서드는 구문과 `INFERRED`(`attrs.declaredIn`, `attrs.namespace`, `attrs.inherited=true`)로 잇는다(F-012-R25). 제네릭 상위 DAO의 메서드 노드 하나에서 하위 매퍼마다 엣지가 하나씩 퍼지므로, 소비자는 호출 엣지의 `attrs.receiverType`과 이 `namespace`가 같은 엣지만 따라간다.
- 짧은 ID(`shortId`) 추론은 엣지를 추가만 한다. 원래의 매달린 엣지는 마무리 단계가 미해결로 만든다.
- 경로 비교는 대소문자를 가리고, `.do` 같은 확장자는 글자 그대로 비교한다. 끝의 `/`만 다른 것은 맞는 것으로 본다(F-012-R19. 2026-10-11 고쳐 씀: 전에는 글자 그대로 비교했다). 겹친 `/`는 하나로 본다. 쿼리 문자열과 `#` 뒤는 뗀다.
- 요청의 동적 세그먼트(`{*}`)가 엔드포인트의 고정 세그먼트와 맞은 경우는 `INFERRED` + `attrs.dynamicSegment=true`다.
- `external=true`인 요청은 맞춰 보지 않는다(코디네이터가 확정한 HTTP_REQUEST 계약). `external` 표시 없이 `http://호스트/...` 형태로 온 URL은 호스트를 뗀 경로로 맞추되 항상 `INFERRED` + `strippedPrefix=<스킴과 호스트>`다.
- 엔드포인트가 맞았는데 `ROUTES_TO` 핸들러가 없으면 redirect·forward는 미해결로 남긴다. 스킴이 붙은 redirect 대상은 맞는 것이 없어도 미해결로 남기지 않는다.
- 뷰 이름에 동적 조각(`{*}`)이 있으면 이름이 비슷한 JSP가 있어도 잇지 않는다(추측 금지).
- `relative` 속성이 없어도 `/`, 스킴, `{*}`로 시작하지 않는 URL은 상대 URL로 본다. 맨 앞이 `{*}/`인 URL은 `relative` 속성이 있어도 미지 접두로 다룬다(F-012-R17).
- 요청 URL에 남아 있는 `${…}`(정규화되지 않은 원문으로 맞출 때)는 `{*}`로 읽는다.
- 정적 자원은 확장자로 판단한다(`.js .css .png .jpg .gif .svg .ico .html .jsp .woff .map .pdf` 등). `.json`, `.xml`은 정적 자원으로 보지 않는다.
- 뷰 리졸버 빈은 `attrs.class`의 단순 이름이 `ViewResolver`로 끝나고 `property.prefix`나 `property.suffix`가 있는 빈이다.
- 같은 경로의 JSP가 여러 웹 루트에 있으면 핸들러 소스와 경로가 가장 가까운 것을 고르고, 못 고르면 전부 `INFERRED` + `ambiguous=true`다.
- `forward:`의 대상이 `.jsp`로 끝나면 엔드포인트가 아니라 JSP로 `RENDERS`를 만든다.
- `@Bean` 메서드가 정의한 빈의 타입은 `beanType`(엣지, 메서드, 빈 순), 빈의 `class`, 메서드의 `returnType` 순으로 찾는다. 그래프에 없는 타입(라이브러리)으로는 엣지를 만들지 않는다.
- 주입 후보의 빈 이름은 `DEFINES_BEAN`의 빈 이름과 타입의 `attrs.beanName`(java가 스테레오타입 빈에 적는 이름: 명시한 이름, 없으면 기본 이름)이다(F-012-R27). 링커가 단순 이름에서 기본 이름을 다시 만들지 않는다: `beanName`도 `DEFINES_BEAN`도 없는 타입은 `stereotype`이 있어도 빈이 아니다(web.xml에 등록한 필터, 추상 클래스, 스테레오타입이 붙은 인터페이스). `qualifier`가 있는데 맞는 후보가 없으면 필드명으로 넘어가지 않는다.

- 호출 지점(F-012-R28): `줄:열`의 열이 없으면(`160`) 줄만 아는 위치다. 끝 위치는 시작과 같게 둔다. 같은 메서드의 `redirect:` 뷰 이름과 `redirects`의 대상이 겹칠 때, 두 호출 지점이 다르면 엣지도 둘이다(전에는 위치가 메서드로 같아 하나였다).
- Tiles(F-012-R30): 항목은 첫 `=`에서 자른다. `extends`는 jsp 분석기가 풀어 최종 template 경로를 넣는다고 본다. 핸들러와 같은 모듈의 설정 파일에 맞는 정의가 있으면 그것만 본다. 맞는 정의가 여럿이면 정확한 이름, 그다음 정의된 순서의 첫 와일드카드 정의다. JSP는 뷰 연결과 같은 방식(그 경로로 끝나는 `JSP_PAGE`, 여럿이면 핸들러와 가까운 것)으로 찾는다. 동적 뷰 이름(`{*}`)은 Tiles 정의와 맞추지 않는다. template → 본문의 정적 `INCLUDES`는 jsp가 만들고 링커는 더하지 않는다.
- 접두로 찾은 구문(F-012-R31): `mybatis:stmt:<접두>`로 시작하는 구문 노드 ID를 본다(namespace와 id를 `.`으로 이은 전체 이름의 접두). 후보 상한은 5다.
- redirect 대상의 자리표시자(F-012-R33): 값은 넘기는 메서드의 파일을 기준으로 조회한다(F-012-R24). 풀지 못한 자리표시자를 `{*}`로 바꾼 경로가 미해결 원문에도 쓰인다.
- 컨텍스트 루트 기준 경로(F-012-R35): 웹 루트는 뷰 연결이 아는 목록(`src/main/webapp`, `WebContent`, `webapp`, `web`, `public`, `src/main/resources/META-INF/resources`·`static`·`templates`)이고 프로젝트 루트 바로 아래도 웹 루트로 본다. 같은 경로가 여러 모듈의 웹 루트에 있으면 전처럼 핸들러와 가까운 것을 고른다.
- `HTTP_ENDPOINT`에서 나가는 엣지(F-012-R34): `RENDERS`·`CALLS`의 출발 노드가 엔드포인트다. 위치는 그 선언(`viewDeclarations`의 파일·줄·열), 선언 목록이 없으면 엔드포인트의 위치다. 엔드포인트 노드는 메서드와 경로가 같으면 하나라 여러 모듈의 `<mvc:view-controller path="/">`가 한 노드에 모이므로(zheng: 12개 모듈) 선언마다 따로 풀어야 각 모듈의 JSP로 간다. 선언한 파일의 모듈 루트를 알면 그 모듈의 JSP만 후보로 본다(리졸버는 프로젝트 전체의 것을 쓰므로, 그 모듈에 JSP가 없을 때 다른 모듈의 같은 경로 JSP로 잇지 않는다. 없으면 뷰 미해결). **조정자가 준 규칙("`.jsp`로 끝나는 view-name은 컨텍스트 루트 기준 파일")과 순서가 다르다**: zheng의 `view-name="/index.jsp"`는 리졸버(`prefix=/WEB-INF/jsp`, `suffix=`)를 거쳐 `WEB-INF/jsp/index.jsp`가 되므로 리졸버를 먼저 본다. 그 페이지의 상대 URL을 풀 때의 기준 디렉터리(F-012-R18)로는 아직 쓰지 않는다.
- 모듈 경계의 선언과 독립 배포 단위(F-012-R37, R38)의 세부
  - `viewDeclarations` 항목(`<파일>:<줄>:<열> <view-name>`)의 파일로 모듈을 정한다. 선언 위치를 모르는 엔드포인트(핸들러도 view-controller 선언도 없음)는 전처럼 다른 모듈이라고 단정하지 않는다.
  - 독립 배포 단위는 모듈 루트 기준이다: `web.xml`은 가장 가까운 빌드 파일이 그 모듈의 것일 때만 그 모듈의 것이고(테스트 소스 제외), `<packaging>war</packaging>`은 그 모듈의 `pom.xml` 본문에서, Gradle은 `id 'war'`·`apply plugin: 'war'`에서 읽는다. Spring Boot 메인은 `org.springframework.boot.autoconfigure.SpringBootApplication`으로 가는 `ANNOTATED_BY`가 있는 타입의 파일(테스트 소스 제외)로 본다.
  - 요청 모듈이 독립 배포 단위여서 전체 경로 매칭이 막히면, 접두 세그먼트를 뗀 매칭(F-012-R8) 등 뒤 단계는 계속 본다(같은 규칙이 적용된다).
  - `attrs.module`은 "따라갈 핸들러·화면이 여러 모듈에 있는 엔드포인트"에만 남긴다. 소비자는 `module`이 있으면 `ROUTES_TO` 핸들러의 파일과 엔드포인트 `RENDERS`의 위치(view-controller 선언 파일)가 그 모듈 루트 아래인 것만 따라간다.
- 메서드 후보(F-012-R39): 후보에 든 메서드의 엔드포인트는 "메서드가 정확히 같은" 것으로 쳐서 `ANY` 엔드포인트보다 앞선다. `httpMethods`에 `ANY`가 들어 있으면 무시한다.
- action 없는 폼(F-012-R40): 요청을 낸 페이지는 그 요청으로 들어오는 `REQUESTS`의 `JSP_PAGE`(없으면 요청 위치의 파일)다. 폼이 다른 페이지에 포함되는 조각(include)에 있어도 포함하는 쪽을 거슬러 올라가지 않는다(그 조각을 직접 그리는 핸들러가 없으면 미해결). "경로가 같다"는 엔드포인트 경로(자리표시자를 치환한 것)가 끝 슬래시까지 같다는 뜻이다. 모듈 경계 규칙(F-012-R37, R38)은 같다.
- 접두 구문 연결의 연산(F-012-R41): `operation`은 java가 남긴 호출 메서드 이름이다(`selectOne`, `selectList`, `insert`, …; iBATIS의 `queryForList` 등은 읽는 호출). `flushStatements`처럼 어느 쪽도 아닌 이름과 `statementType`이 없는 구문은 거르지 않는다.
- 동적 디렉터리 뷰(F-012-R44): 리졸버가 여럿이면 후보가 처음 나온 리졸버의 것만 쓴다. 리졸버가 없으면 `/<이름>.jsp`로 끝나는 JSP에서 찾는다. 후보를 모듈로 좁히지 않는다(템플릿이 다른 모듈에 있는 프로젝트가 있다). 하나뿐인 후보도 "동적 값이 그 디렉터리"라는 추론이라 `INFERRED`다.
- 정적 파일 꼴의 상대 링크(F-012-R46): 기준 디렉터리로 푼 매칭에만 적용한다. `/`로 시작하는 요청(`/download/report.pdf` ↔ `/download/{name}`)과 고정 세그먼트로 맞는 엔드포인트는 전과 같다.
- 동적 디렉터리 뷰의 후보 좁히기(F-012-R47): 자리표시자가 하나이고 디렉터리 조각 전체일 때만 다룬다(그 밖의 꼴의 자리표시자 이름은 전과 같다). 프로퍼티 조회는 F-012-R24의 규칙 그대로다(핸들러 파일의 모듈에 그 키가 있으면 그 모듈의 값만, 없으면 프로젝트 전체에서 하나로 모일 때). 핸들러가 다른 모듈의 도우미를 불러 만든 이름도 핸들러 파일의 모듈로 찾는다. 모듈 디렉터리 이름은 핸들러 파일의 모듈 루트가 프로젝트 루트이거나 모듈을 모르면 쓰지 않는다. `narrowedBy`는 근거가 맞은 엣지에 모두 남긴다(후보가 처음부터 하나였어도). F-012-R44의 "후보를 모듈로 좁히지 않는다"는 "후보 JSP가 있는 모듈로 거르지 않는다"는 뜻으로 그대로다(템플릿은 다른 모듈에 있다).
- crossModule 요청의 대상 모듈(F-012-R48): 요청을 낸 파일은 요청 노드의 위치 파일이고, 그 파일의 `JSP_PAGE`·`JS_MODULE` 노드에서 시작한다. 포함 관계는 `JSP_PAGE`에서 들어오는 `INCLUDES`(미해결 제외)를 200개까지 거슬러 올라간다. 그리는 쪽의 모듈을 모르는 것이 하나라도 섞이면 판단하지 않는다. 동적 디렉터리 뷰의 여러 후보 가운데 하나인 `RENDERS`(`dynamicSegment` + `candidate`)는 그 핸들러가 정말 그 페이지를 그리는지 모르므로 근거로 쓰지 않는다. 요청 파일의 모듈을 모르는 경우(빌드 파일이 없는 프런트 폴더: `crossModule`이 없다)와 웹앱 모듈의 요청은 다루지 않는다. 뷰 연결(220)이 HTTP 연결(225)보다 먼저 돌므로 F-012-R47로 좁힌 `RENDERS`가 근거가 된다.
- 실제 매핑의 첫 세그먼트(F-012-R49): "선언된 엔드포인트 경로의 첫 고정 세그먼트"는 프로젝트 전체(모든 모듈)의 엔드포인트에서 모은다(자리표시자를 값으로 푼 경로 기준). 접두의 첫 세그먼트만 본다(`/ctx/manage/orders`의 둘째 세그먼트는 보지 않는다). 근거가 있는 접두는 지금 web.xml의 `servletMappings`(`/api/*`)뿐이다: 프로퍼티의 컨텍스트 경로(`server.servlet.context-path`)는 읽지 않는다. 컨텍스트 경로 이름이 엔드포인트의 첫 세그먼트와 같은 프로젝트(`/board`에 배포한 앱의 `/board/list.do`를 `/board/board/list.do`로 부르는 화면)에서는 맞는 연결이 미해결로 남는다. 공개 프로젝트 8개와 재현 프로젝트에서 추측으로 뗀 세그먼트 접두 `strippedPrefix`는 9건뿐이고(zheng 2, 재현 7) 이 규칙에 걸린 것은 zheng의 죽은 링크 2건이다.

## 6. 미확정 사항
- 없음. 사용자가 미확정 사항은 기본값을 정해 진행하라고 위임했다(위 가정).

남은 한계 (공개 프로젝트 검증에서 확인, link만으로는 풀 수 없다)
- 엔드포인트 노드가 모듈을 구분하지 않는다(`http:endpoint:<METHOD> <경로>`). 같은 경로의 핸들러가 여러 웹 모듈에 있으면 `MATCHES`를 따라간 소비자가 다른 모듈의 핸들러까지 보게 된다. 링커는 같은 모듈에 핸들러가 없는 연결에 `crossModule=true`를 붙일 뿐이다.
- 빈 노드도 이름 하나에 하나다(`spring:bean:<이름>`). 링커는 타입으로 푸는 단계에서만 모듈·테스트 소스를 가린다(F-012-R26). 빈 노드의 `class`·위치가 어느 정의의 것인지는 분석기 몫이다.
- 요청 메서드를 모르는(`ANY`) 요청이 끝 슬래시만 남은 꼴(`/board/{*}/`: 마지막 변수가 빈 문자열로 평가된 URL)로 오면 F-012-R19 때문에 조각 수가 하나 적은 엔드포인트(`PUT /board/{nttId}`)에 `INFERRED` + `trailingSlash=true`로 이어질 수 있다.
- 프로퍼티 값은 정적 파일에서만 얻는다. 실행 시점의 시스템 프로퍼티·환경 변수·프로파일 선택으로 값이 바뀌는 프로젝트에서는 `resolvedPath`가 실제와 다를 수 있다(값이 갈리면 풀지 않는다).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 설계(그래프 계약): [../F-001-analysis-pipeline/design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 기능: [F-018 테이블 관계 추론](../F-018-relation-inference/spec.md)

## 8. 계약 추가 제안 (design.md 반영 요청)
2026-10-11 감사 후속으로 추가한 것. design.md에 반영됨(2026-10-11): 링커 순서(90)는 1절, `candidates`가 하나일 때의 `INFERRED` `INJECTS`는 3.5 공통 규칙, `redirects`·`forwards`와 forward 대상 JSP의 `RENDERS`는 3.4·3.5, `external`을 쓰는 모듈(link)은 3.5 노드 attrs 표, 남은 한계(파싱 실패 타입도 `external`)는 7절에 넣었다. 아래 나머지 항목도 design.md에 있다.
- 3.5 공통 규칙 "`INJECTS`의 `candidates`"에 "후보가 정확히 하나면 링커가 그 타입으로 `INFERRED` `INJECTS`(`resolvedFrom`)를 더한다. 여럿이면 엣지는 선언된 대상 타입으로 가는 것 하나뿐이고 후보는 attr에만 있다"를 덧붙인다. 3.4 `INJECTS`의 link 행("인터페이스 주입의 구현 후보를 하나로 좁힌 것")은 그대로 맞다.
- JAVA_METHOD attrs `redirects`·`forwards`(List, java가 쓴다): 서블릿 API로 넘어가는 경로 패턴. 3.4 `CALLS`의 redirect·forward 행 설명을 "뷰 이름 `redirect:`·`forward:` 또는 메서드 attrs `redirects`·`forwards`가 가리키는 엔드포인트의 핸들러"로, 3.5 `CALLS (redirect·forward)` 행에 "`viewName`은 뷰 이름에서 온 엣지에만 있다"를 덧붙인다. `RENDERS`(link)는 forward 대상 JSP에도 만든다.
- `external`(JAVA_TYPE·JAVA_METHOD·JAVA_FIELD): 쓰는 모듈에 link 추가("어느 분석기도 선언하지 않은 참조에 붙인다"). 링커 순서에 `link-external-reference`(90) 추가.

- 엣지 attrs: `viaSynonym`(정규화로 옮긴 엣지), `strippedQualifier`(루틴), `strippedPrefix`·`relative`·`ambiguous`·`dynamicSegment`(MATCHES), `viewName`·`resolverPrefix`·`resolverSuffix`·`ambiguous`(RENDERS), `callType=redirect|forward`·`path`(CALLS), `resolvedFrom`·`resolvedSource`·`matchedBy`(INJECTS, USES, EXECUTES), `declaredIn`(EXECUTES).
- 엣지 종류의 from→to 확장: `CALLS` METHOD→METHOD에 redirect·forward(만드는 모듈 link), `USES` STEP→METHOD(link), `REFERENCES`·`JOINS`·`READS`의 끝점에 DB_VIEW.
- 진단 코드: `MAPPER_METHOD_WITHOUT_STATEMENT`(WARN), `UNDEFINED_ROUTINE`(INFO), `SYNONYM_CYCLE`(WARN).
- 미해결 분류: `http-endpoint`, `view`.

### 8.1 공개 프로젝트 검증 후속 (2026-10-11)
design.md에 반영됨(2026-10-11, 2차): 링커 순서는 1절(architecture.md 5·8절 포함), `resolvedPath`와 `MATCHES`·`EXECUTES` attrs는 3.5, 자리표시자가 든 빈 이름은 3.4(`INJECTS`의 예외 행), 자리표시자 약속은 3.9와 3.5 공통 규칙 "프로퍼티 자리표시자", `UNRESOLVED_BEAN_PLACEHOLDER`는 3.7, 남은 한계 네 항목은 7절, 소비자 규칙은 3.4 "소비자가 지키는 규칙"에 넣었다. 빈 판정(F-012-R27)은 3.5 공통 규칙 "빈으로 정의된 타입"에 있다.
- **링커 순서(1절, architecture.md)**: HTTP 연결 `link-http-match`의 order를 210 → **225**로 옮겼다(뷰 연결 220 뒤). "뒤 단계는 앞 단계의 결과를 쓴다"의 목록에 `RENDERS`(HTTP 연결이 상대 URL의 기준으로 쓴다)를 더한다.
- **HTTP_ENDPOINT 노드 attrs(3.5)**: `resolvedPath`(String, 쓰는 모듈 link) — 경로의 프로퍼티 자리표시자를 프로젝트 properties 값으로 전부 치환한 경로. 치환할 것이 없거나 값을 찾지 못했으면 없다. 노드 ID와 `path`는 그대로다.
- **MATCHES 엣지 attrs(3.5)**: `strippedPlaceholder`(String, 떼고 맞춘 엔드포인트 경로 맨 앞의 자리표시자 원문 `${adminPath}`. 항상 `INFERRED`), `trailingSlash`(boolean, 끝의 `/`만 다름. 신뢰도는 그대로), `crossModule`(boolean, 요청 파일의 모듈에 그 엔드포인트의 핸들러가 없음. 항상 `INFERRED`), `relativeBase`(String, 상대 URL을 푼 기준 디렉터리). `strippedPrefix` 설명에 "`{*}`는 0개 이상의 미지 접두", `dynamicSegment` 설명에 "섞인 세그먼트의 리터럴이나 값 모르는 자리표시자를 요청의 `{*}`가 덮는 경우 포함"을 더한다. redirect·forward `CALLS`에도 같은 키가 붙을 수 있다(이미 "`MATCHES`와 같은 키"로 적혀 있다).
- **EXECUTES 엣지 attrs(3.5)**: link가 만든 "물려받은 매퍼 메서드" 엣지에 `namespace`(String, 구문의 namespace인 구체 매퍼 타입의 바이너리 이름), `inherited`(boolean), `declaredIn`(선언 타입 ID). java가 쓰는 `EXECUTES.namespace`(SqlSession 호출의 namespace)와 키 이름이 같고 뜻도 같다(구문의 namespace). 소비자 규칙(3.4): 상위 DAO 메서드에서 나가는 `inherited=true` 엣지는 호출 엣지의 `receiverType`과 `namespace`가 같은 것만 따라간다.
- **INJECTS·USES(3.4, 3.5)**: 자리표시자가 든 빈 이름을 치환해 정의된 빈으로 가는 엣지(JAVA_TYPE→SPRING_BEAN, `resolvedFrom=<원래 빈 ID>`)를 link가 만든다.
- **자리표시자 약속(3.9)**: `{*}` 행의 "패턴 맨 앞의 `{*}`는 … 링커가 떼고 맞춘다"에 "0개 이상의 세그먼트로 보고, 나머지 경로 전체가 엔드포인트 경로 전체와 맞을 때만"을 더한다. 프로퍼티 자리표시자 `${키}`·`${키:기본값}`(엔드포인트 `path`, 뷰 리졸버 `property.prefix`/`suffix`, 빈 이름)는 링커가 프로젝트 properties·yml 값으로 푼다는 행을 더한다.
- **진단 코드(3.7)**: `UNRESOLVED_BEAN_PLACEHOLDER`(INFO, link) — 이름에 프로퍼티 자리표시자가 든 빈 참조를 풀지 못함(이름별 건수를 한 건에 요약).
- **7절(위험·한계)**: 위 6절 "남은 한계" 네 항목.

### 8.2 실전 재검증 2차 후속 (2026-10-11) — design.md 반영 요청
design.md 반영됨(2026-10-11): 출발이 `HTTP_ENDPOINT`인 `RENDERS`·`CALLS`와 엣지의 위치는 3.4(엣지 표)와 7절("서블릿 API의 redirect·forward" 행), `RENDERS`의 `via`·`definition`·`tilesAttribute`·`candidate`·`suffixMatch`와 `EXECUTES`의 `prefixMatch`·`statementPrefix`는 3.5 엣지 attrs 표, `tilesDefinitions`·`tilesAttributes`·`clientRoute`·`viewController`·`viewNames`·`viewDeclarations`는 3.5 노드 attrs 표, `crossModule`은 3.5(8.3의 정의로 적었다)에 넣었다.
- **3.4 `RENDERS`**: from에 `HTTP_ENDPOINT`를 더한다(`viewController=true`인 엔드포인트 → JSP_PAGE. link). `CALLS`(redirect·forward)의 from에도 `HTTP_ENDPOINT`를 더한다(F-012-R34). "링커가 만든 `CALLS`·`RENDERS`의 위치는 호출 지점이 아니라 그 메서드의 위치다"(7절)는 "`viewNameLines` 등이 있으면 호출 지점, 없으면 메서드의 위치"로 고친다(F-012-R28). 3.4 소비자 규칙 "호출되는 쪽의 자식 순서는 호출 지점 순"은 그대로 맞고, 이제 `RENDERS`·redirect·forward도 실제 호출 줄로 정렬된다.
- **3.5 RENDERS 엣지 attrs**: `via`(`tiles`), `definition`(맞은 Tiles definition 이름 원문), `tilesAttribute`(boolean: definition의 put-attribute JSP로 이은 것), `candidate`(boolean: 후보 뷰 이름. 항상 `INFERRED`), `suffixMatch`(boolean: 컨텍스트 루트 기준 경로를 접미사로만 맞춤. 항상 `INFERRED`).
- **3.5 EXECUTES 엣지 attrs**: `prefixMatch`(boolean: 구문 ID의 리터럴 접두로 찾은 구문. 항상 `INFERRED`), `statementPrefix`.
- **3.5 FILE 노드 attrs**(jsp가 쓴다, link가 읽는다): `tilesDefinitions`(List: `<definition 이름>=<template JSP 경로>`), `tilesAttributes`(List: `<definition 이름>=<put-attribute의 JSP 경로>`).
- **3.5 HTTP_REQUEST attrs**: `clientRoute`(boolean. web이 쓴다) — "링커가 맞추지 않고 미해결도 남기지 않는다"(F-012-R29). **HTTP_ENDPOINT attrs**: `viewController`, `viewNames`, `viewDeclarations`(springxml이 쓴다, F-017-R16).
- **3.5 MATCHES `crossModule`**: "요청 쪽 모듈에 핸들러가 없어도 붙는다"로 고친다(F-012-R36). 3.4 소비자 규칙 "핸들러가 여러 모듈에 있는 엔드포인트"는 그대로다.
- 소비자(export·viewer)가 알아야 할 것: (1) `RENDERS`·`CALLS`의 출발이 `HTTP_ENDPOINT`일 수 있다(핸들러 메서드 없이 엔드포인트에서 바로 JSP). 엔드포인트 콜트리가 `ROUTES_TO`만 따라가면 이 뷰가 빠진다. (2) 한 핸들러에서 Tiles template과 본문 JSP로 `RENDERS`가 여럿 나간다(`via=tiles`, 같은 `viewName`). (3) `candidate=true`·`suffixMatch=true`·`prefixMatch=true`는 추론 표시(`~`) 대상이다. (4) 프런트 전용 모듈(빌드 파일이 있는)의 요청은 이제 전부 `crossModule=true`(추론)다.

### 8.3 실전 재검증 3차 후속 (2026-10-11) — design.md 반영 요청
design.md 반영됨(2026-10-11): `MATCHES`의 `module`·`selfSubmit`과 `crossModule`의 새 정의는 3.5 엣지 attrs 표와 3.4 "소비자가 지키는 규칙"(핸들러가 여러 모듈에 있는 엔드포인트), `httpMethods`·`selfSubmit`은 3.5(공통 규칙 "요청의 메서드 후보", 노드 attrs 표)와 3.6(`http-endpoint`), `viaArgument`와 `prefixMatch`의 연산 조건은 3.4·3.5, `resolvedBy`와 "풀린 미해결 `EXECUTES`"는 3.5(공통 규칙 "풀기 전의 대상", UNRESOLVED 노드)와 3.4, `RENDERS`의 `dynamicSegment`는 3.4·3.5·3.9(`{*}` 행), `ambiguous`는 3.5 공통 규칙, 한계 두 항목은 7절에 넣었다. 소비자 쪽(export가 `MATCHES.module`·`resolvedBy`를 읽는지)은 코드에서 확인하지 못해 3.4에 [확인 필요]로 적었다(export는 수정 중이다).
- **3.5 MATCHES 엣지 attrs**: `module`(String: 같은 모듈 매칭의 모듈 루트, 프로젝트 상대 경로. 프로젝트 루트는 `.`. 선언이 여러 모듈에 있는 엔드포인트에만 남긴다. F-012-R37), `selfSubmit`(boolean: action 없는 폼을 그 화면의 엔드포인트와 같은 경로로 이은 것. 항상 `INFERRED`. F-012-R40). `crossModule`의 설명을 "요청 모듈에 그 엔드포인트의 선언(핸들러 메서드 또는 view-controller 선언)이 없고, 요청 모듈이 독립 배포 단위가 아니거나 대상 모듈이 독립 배포 단위가 아닐 때"로 고친다(F-012-R37, R38). 3.4 소비자 규칙 "핸들러가 여러 모듈에 있는 엔드포인트"에 "`MATCHES.module`이 있으면 그 모듈의 핸들러(`ROUTES_TO` 대상의 파일)와 화면(엔드포인트 `RENDERS`의 위치)만 따라간다"를 더한다.
- **3.5 HTTP_REQUEST attrs**(읽는 모듈 link): `httpMethods`(List\<String>: 메서드 후보. web이 쓴다. F-012-R39), `selfSubmit`(boolean: action 없는 폼. 경로 없음. jsp가 쓴다. F-012-R40). 3.6 `http-endpoint` 행의 원문 꼴에 `<메서드> (selfSubmit)`를 더한다.
- **3.5 EXECUTES 엣지 attrs**: `viaArgument`(boolean: 구문 ID 문자열을 라이브러리 메서드에 인자로 넘긴 호출을 그 구문과 이은 것. 항상 `INFERRED`. link. F-012-R43). `prefixMatch`의 설명에 "호출의 `operation`과 구문의 `statementType`이 맞는 구문만"(F-012-R41)을 더한다.
- **3.5 UNRESOLVED 노드 attrs**: `resolvedBy`(String, 값 `prefixMatch`: 그 미해결 노드로 들어오는 접두 `EXECUTES`가 전부 풀렸다. link. F-012-R42). 공통 규칙에 "미해결 `EXECUTES` 엣지 e는 같은 출발·같은 위치에 `resolvedFrom = e.to`인 `EXECUTES`가 있으면 풀린 것이다(`shortId`·`prefixMatch` 공통)"를 더한다.
- **3.5 RENDERS 엣지 attrs**: `dynamicSegment`(boolean: 뷰 이름의 동적 디렉터리 자리에 실제 디렉터리를 넣어 찾은 JSP. 항상 `INFERRED`. 후보가 둘 이상이면 `candidate=true`도 있다. F-012-R44). `candidate`·`suffixMatch`의 쓰이는 곳에 이 경우를 더한다.
- **3.5 `ambiguous`(MATCHES, CALLS, RENDERS)**: "같은 (출발, 종류, 위치)에서 나가는 후보가 둘 이상일 때만 붙는다"(F-012-R45).
- **7절(한계)**: (1) 다른 웹앱 모듈의 엔드포인트로는 잇지 않으므로, 게이트웨이·프록시로 한 주소 아래 묶인 여러 war 사이의 링크는 미해결로 남는다. (2) action 없는 폼은 그 JSP를 직접 그리는 핸들러가 있을 때만 잇는다(include된 조각의 폼은 미해결).
- 소비자(export·viewer)가 알아야 할 것: (1) 엔드포인트 하나에 여러 모듈의 선언이 모인 경우(`GET /`) 화면 → 엔드포인트 → 핸들러·뷰를 따라갈 때 `MATCHES.module`로 좁혀야 "다른 모듈의 화면에서 온 요청이 이 모듈의 핸들러로 간다"는 없는 경로가 나오지 않는다. (2) 미해결 `EXECUTES`는 풀린 짝(`resolvedFrom`)이 있으면 콜트리에서 숨긴다. report의 미해결 집계에서도 `resolvedBy`가 있는 `UNRESOLVED` 노드는 빼는 편이 맞다. (3) `selfSubmit`·`viaArgument`·`dynamicSegment`는 추론의 근거 표시다(표시만 더하면 된다). (4) `httpMethods`가 있는 요청의 표시 메서드는 `ANY`가 아니라 후보 목록이 낫다.

### 8.4 실전 재검증 4차 후속 (2026-10-11)
design.md에 직접 반영했다(2026-10-11): 3.4 "소비자가 지키는 규칙"(핸들러가 여러 모듈에 있는 엔드포인트), 3.5 엣지 attrs 표(`RENDERS`의 `narrowedBy`, `MATCHES`의 `module`·`crossModule`·`strippedPrefix`), 3.5 공통 규칙(동적 디렉터리 뷰), 3.9(`${키}`를 쓰는 곳), 7절(한계).
- **3.5 RENDERS 엣지 attrs**: `narrowedBy`(String, 값 `property`·`moduleName`: 동적 디렉터리 뷰의 후보를 고른 근거. `dynamicSegment=true`인 엣지에만 있다. F-012-R47)
- **3.5 MATCHES 엣지 attrs**: `module`이 `crossModule=true`와 함께 올 수 있다(요청을 낸 페이지를 그리는 핸들러의 모듈. F-012-R48). 소비자 규칙은 같다(그 모듈의 핸들러와 그 모듈 선언의 `RENDERS`만 따라간다).
- 소비자(export·viewer)가 알아야 할 것: `dynamicSegment=true`인 `RENDERS`의 `viewName`에 `{*}` 대신 `${키}`가 올 수 있다(프로퍼티에서 온 자리). 미해결 뷰의 원문도 그렇다.
- **3.5 MATCHES `strippedPrefix`**: 추측으로 떼는 세그먼트 접두는 그 첫 세그먼트가 선언된 엔드포인트 경로의 첫 고정 세그먼트면 떼지 않는다(F-012-R49). 7절에 한계 한 줄.
