---
type: spec
id: F-015
title: 콜트리와 영향도 조회
status: implemented        # draft | approved | implemented
updated: 2026-10-11
---

# F-015 콜트리와 영향도 조회

## 1. 배경
PRD 목표 1(IDE 수준의 콜트리와 관계 분석)과 시나리오 3(메서드의 호출하는 쪽·호출되는 쪽 콜트리, 매퍼 SQL과 테이블·프로시저까지), 시나리오 4(테이블·컬럼에서 SQL, 메서드, 화면을 거꾸로 찾기)를 맡는다. IDE의 호출 추적이 끊기는 언어 경계(화면 → 엔드포인트 → Java → 매퍼 구문 → 테이블·루틴)를 그래프의 엣지를 따라 한 트리로 잇는다.

## 2. 범위
- 포함: 콜트리 조회 API(양방향, 텍스트·JSON·Mermaid flowchart), 테이블·컬럼 영향도 리포트(Markdown, JSON), CRUD 매트릭스(CSV, Markdown), 시작 노드 찾기, `analyze` 때 쓰는 표준 산출물(`crud/`, `calltree/`, `report.md`)과 출력기 등록.
- 제외: CLI 하위 명령 자체(조회 API만 제공한다), 대화형 탐색(F-016), 호출 관계의 추출(F-003, F-012).

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-015-R1 | 시스템은 시작 노드(Java 메서드, 엔드포인트, JS 함수, JSP, 매퍼 구문, 테이블, 루틴, 배치 잡·스텝)에서 아래 방향(callees)으로 `REQUESTS`, `MATCHES`, `ROUTES_TO`, `CALLS`, `EXECUTES`, `RENDERS`, `INCLUDES`, `USES`, `READS`, `WRITES`, `CONTAINS`(루틴→구문, 잡→스텝, JSP→JS 모듈, JS 모듈→함수, JS 함수→함수)를 지정한 깊이까지 따라 트리를 만들어야 한다. `NEXT`는 따라가지 않는다(잡 아래 스텝의 순서를 정할 때만 본다, R23). 컬럼으로 가는 `READS`/`WRITES`는 테이블 줄 하나로 접는다(접근 글자는 R20). 쓰는 테이블에서는 `TRIGGERS_ON`을 거꾸로 따라 그 쓰기에 일어나는 트리거로 간다(R18). | 사용자 요청, PRD 시나리오 3 |
| F-015-R2 | 방향이 callers인 경우, 시스템은 R1의 엣지를 모두 거꾸로 따라 트리를 만들어야 한다. | 사용자 요청, PRD 시나리오 3 |
| F-015-R3 | 호출 대상이 인터페이스·추상 메서드이면, 시스템은 `OVERRIDES`를 거꾸로 따라 구현 메서드를 `[impl]` 자식으로 펼쳐야 한다. 주입 좁히기가 켜진 경우, 호출하는 타입의 `INJECTS`(`attrs.candidates`, 또는 `attrs.resolvedFrom`이 붙은 INFERRED 엣지)가 가리키는 구현만 펼친다. | 사용자 요청 |
| F-015-R4 | 방향이 callers이고 노드가 상위 메서드를 재정의한 메서드이면, 시스템은 상위 메서드의 호출자를 `[via interface]`로 표시해 포함해야 한다. | 사용자 요청 |
| F-015-R5 | 만약 자식이 경로상 조상과 같은 노드이면, 시스템은 `[cycle]`로 표시하고 펼치지 않아야 한다. 반복 억제 옵션이 켜진 경우 이미 펼친 노드는 `[see above]`로 표시한다. 만약 노드 수가 상한에 닿으면 전개를 멈추고 잘렸음을 표시한다. | 사용자 요청 |
| F-015-R6 | 시스템은 각 줄에 신뢰도(INFERRED `~`, UNRESOLVED `?`)와 소스 위치(`file:line`)를 표시하고, external 메서드 숨김 옵션을 받아야 한다. | 사용자 요청, AGENTS.md (미해결·추론 구분) |
| F-015-R7 | 시스템은 트리를 들여쓴 텍스트, JSON, Mermaid flowchart로 출력해야 한다. | 사용자 요청 |
| F-015-R8 | 테이블 또는 컬럼이 주어지면, 시스템은 그것을 읽고 쓰는 구문(C/R/U/D 구분) → 실행하는 메서드·루틴·배치 스텝 → 호출 사슬 → 엔드포인트 → 요청하는 화면(JS 함수, JSP)을 계층별로 모은 영향도 리포트를 Markdown과 JSON으로 내야 한다. | 사용자 요청, PRD 시나리오 4 |
| F-015-R9 | 시작이 컬럼인 경우, 시스템은 그 컬럼에 엣지가 있는 구문만 확정으로 넣고, 그 테이블의 컬럼 엣지가 하나도 없는 구문은 "테이블 수준에서만 확인됨"으로 따로 표기해야 한다. | 사용자 요청 |
| F-015-R10 | 시스템은 CRUD 매트릭스를 테이블 × 구문, 테이블 × 메서드(구문을 직접 실행하는 메서드), 테이블 × 엔드포인트(호출 사슬로 닿는 것, 깊이 상한), 테이블 × 루틴으로 만들고 CSV(UTF-8 BOM 옵션)와 Markdown으로 내야 한다. `MERGE`는 C와 U다. | 사용자 요청, PRD 시나리오 4 |
| F-015-R11 | `analyze`가 실행되면, 시스템은 `crud/table-statement.csv`, `crud/table-method.csv`, `crud/table-endpoint.csv`, `crud/table-routine.csv`, `calltree/endpoints.md`, `calltree/batch-jobs.md`, `report.md`를 써야 한다. 출력기는 `ServiceLoader`로 발견되고 산출물마다 옵션으로 끌 수 있다. | 사용자 요청, design 4.1 |
| F-015-R12 | 시스템은 `report.md`에 종류별 노드·엣지 수, 신뢰도별 엣지 수, 미해결 참조(분류별), 진단 코드별 집계와 상위 항목, DDL에 없는 테이블, 어떤 구문에서도 참조되지 않는 테이블, 대응 구문이 없는 매퍼 메서드를 적어야 한다. | 사용자 요청, PRD 성공 지표 |
| F-015-R13 | 시스템은 같은 입력에 바이트까지 같은 출력을 내야 한다. | 사용자 요청 |
| F-015-R14 | 만약 산출물의 대상이 그래프에 없으면, 시스템은 그 산출물을 빈 내용으로 쓰지 말고 생략해야 한다: 테이블·뷰가 없을 때의 `crud/`, 테이블에 접근하는 주체가 없는 CRUD 매트릭스 파일, 그릴 Java 타입이 없을 때의 `class-diagram/`, 엔드포인트가 없을 때의 `calltree/endpoints.md`, 배치 잡과 스텝이 없을 때의 `calltree/batch-jobs.md`. `report.md`에는 없는 대상마다 한 줄을 남긴다(테이블·뷰가 없으면 `DB 객체 없음(DDL이나 SQL이 분석되지 않음)`). | 통합 단계 개선 |
| F-015-R15 | 시스템은 `report.md`의 표에서 위치를 모르는 칸을 `-`로 적고, 칸 안의 `\|`와 줄바꿈이 표를 깨지 않게 해야 한다. | 통합 단계 개선 |
| F-015-R16 | 만약 영향도의 대상을 찾지 못하면, 시스템은 이름이 비슷한 테이블·뷰·컬럼 후보를 리포트에 담아야 한다. | 통합 단계 개선 |
| F-015-R17 | 시스템은 표준 출력기가 읽는 옵션 키의 목록을 공개해야 한다. | 통합 단계 개선 (F-001-R33) |
| F-015-R18 | 콜트리·영향도·테이블 × 엔드포인트 매트릭스가 "구문 —`WRITES`→ 테이블"에서 트리거로 가거나(callees) 트리거에서 그 테이블을 쓰는 구문으로 갈 때(callers), 시스템은 쓰기의 종류(`WRITES`의 `op`, 없으면 구문의 `statementType`)가 트리거 노드의 `triggerEvent`와 맞을 때만 따라가야 한다. `MERGE`는 `INSERT`와 `UPDATE` 둘 다에 해당한다. `UPDATE OF <컬럼>` 트리거는 구문의 `WRITES` 컬럼 엣지가 그 컬럼을 포함할 때만 맞다. 만약 맞는지 알 수 없으면(`triggerEvent`가 없거나 해석되지 않음, 컬럼 지정 트리거인데 구문에 그 테이블의 `WRITES` 컬럼 엣지가 하나도 없음, 쓰기의 종류를 모름) 따라가되 `[trigger?]`로 표시한다. | 독립 감사 E3 |
| F-015-R19 | 구현을 펼칠 때(R3) 시스템은 `OVERRIDES`를 전이적으로 거꾸로 따라 간접 구현까지 넣어야 한다. 주입으로 좁힐 때는 후보 구현 타입마다 그 타입에 선언된 재정의, 없으면 `EXTENDS`를 따라 올라가며 가장 가까운 상위 클래스의 구현을 고른다(후보 타입의 하위 클래스가 재정의한 것은 고르지 않는다). callers 방향(R4)에서는 노드가 전이적으로 재정의한 모든 상위 메서드의 호출자를 넣되, 주입이 다른 구현을 가리키는 호출자와 `super` 호출은 뺀다. | 독립 감사 E4 |
| F-015-R20 | 시스템은 테이블 단위의 접근 종류(CRUD 매트릭스의 칸, 콜트리의 테이블 줄, 테이블에서 시작한 영향도)에서 `R`을 테이블 수준 `READS` 엣지가 있을 때만 붙여야 한다. 컬럼으로 가는 `READS`는 `R`로 세지 않는다. 컬럼에서 시작한 영향도는 그 컬럼의 엣지를 그대로 쓴다. | 독립 감사 (CRUD의 R) |
| F-015-R21 | 시스템은 테이블 × 루틴 매트릭스에 루틴이 직접 가진 구문뿐 아니라 루틴 → 루틴 `CALLS`(본문의 호출과 본문 구문 안의 함수 호출)를 따라 닿는 구문의 접근도 넣어야 한다(걸음 수 상한, 순환 방지). 간접으로만 닿는 접근은 소문자로 적어 직접 접근(대문자)과 구분한다. | 독립 감사 |
| F-015-R22 | 표준 산출물의 콜트리 기본 깊이는 화면에서 테이블까지의 경로가 잘리지 않을 만큼(20)이어야 하고, 만약 트리가 깊이 제한에 잘리면 시스템은 그 트리 끝에 ``깊이 제한으로 잘림: `export.calltree.depth`로 조정`` 한 줄을 적어야 한다. | 독립 감사 |
| F-015-R23 | 시스템은 잡 아래의 스텝(콜트리)과 `calltree/batch-jobs.md`의 잡을 모르는 스텝을 이름순이 아니라 `NEXT` 흐름 순서로 내야 한다: 들어오는 `NEXT`가 없는 스텝부터 흐름을 따라, 분기는 ID 순, 순환은 한 번만, `NEXT`가 걸리지 않은 스텝은 뒤에 ID 순. | 독립 감사 |
| F-015-R24 | 시스템은 `report.md`에 "Java 호출자가 없는 매퍼 구문"을 따로 적어야 한다: 구문을 `EXECUTES`하는 것이 Java 메서드뿐이고, 그 메서드와 그것이 재정의·구현한 메서드, 그것을 재정의·구현한 메서드 어디에도 들어오는 `CALLS`·`USES`·`ROUTES_TO`가 없는 매퍼 구문이다(배치 스텝이 직접 `EXECUTES`하는 구문은 제외). | 독립 감사 |
| F-015-R25 | 시스템은 대상 테이블을 정적으로 알 수 없는 동적 구문(`unresolved:sql-dynamic:`으로 가는 `READS`/`WRITES`가 있는 구문)을 조용히 빼지 말고 드러내야 한다: CRUD 매트릭스의 마지막 열 `(동적)`과 구문 목록(구문, 접근 종류, 위치), 영향도 리포트의 별도 절과 JSON `dynamicStatements`, `report.md`의 "대상 테이블을 정적으로 알 수 없는 구문" 절. | 독립 감사 |
| F-015-R26 | 시스템은 루틴을 호출하는 구문(`SQL_STATEMENT —CALLS→ DB_ROUTINE`)의 행과 그 구문을 실행하는 메서드의 행에, 루틴의 구문(과 R21처럼 루틴이 부르는 루틴의 구문)이 접근하는 테이블을 넣어야 한다. 표기는 R21과 같다: 주체가 직접 하는 접근은 대문자, DB 루틴(프로시저·함수·트리거)을 거쳐서만 닿는 접근은 소문자다. 테이블 × 엔드포인트도 같은 표기를 쓴다. | 뷰어 검증에서 발견(조정자 추가 항목 11) |
| F-015-R27 | 시스템은 엣지가 수백만 개인 그래프에서도 표준 산출물과 조회 API를 엣지 수에 비례하는 시간 안에 끝내야 한다: 엣지마다 그 끝점의 엣지 전체를 다시 훑지 않는다(컬럼의 소속 테이블은 컬럼 ID에서 풀고, 노드의 엣지는 종류로 걸러 꺼내고, 리포트는 엣지 전체를 정렬하지 않는다). | 실제 프로젝트 검증(엣지 500만 개 그래프에서 `erd/tables.md`를 쓰다 멈춤) |
| F-015-R28 | 시스템은 데이터 적재 구문(`SQL_STATEMENT`의 `attrs.dataLoad=true`: 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 구문, `rowCount`=행 수)을 구문 기준 CRUD 매트릭스, 테이블 명세의 "참조하는 구문" 수, `report.md`의 테이블 점검, 콜트리에서 기본으로 빼고, `report.md`에 "데이터 적재 스크립트: 파일 n개, 테이블 m개, 행 k건" 절로 요약해야 한다. 옵션 `export.includeDataLoad=true`(조회 API는 인자·옵션)면 넣는다. 속성이 없는 그래프에서는 전과 같다. | 실제 프로젝트 검증 |
| F-015-R29 | 콜트리·테이블 × 엔드포인트 매트릭스·영향도가 "팬아웃 엣지"(상위 타입에서 물려받은 메서드가 하위 매퍼마다 하나씩 실행하는 구문으로 가는 `EXECUTES`, 제네릭 상위 메서드가 리플렉션으로 부르는 `callType=reflection` `CALLS`)를 따라갈 때, 시스템은 경로상 가장 최근의 구체 수신자 타입(호출 엣지의 `receiverType`, `super`·상속 메서드 호출로 이어받은 호출한 쪽의 타입)과 맞는 것만 따라가야 한다. 맞는 것이 하나도 없으면 전부 따라가되 `[?]`로 표시한다. 문맥이 없으면 상속 구문은 전부(표시 없음), 리플렉션 팬아웃은 전부 `[?]`로 따라간다. callers 방향은 대칭이다(구문의 namespace·리플렉션 엣지의 `viaTypes`와 맞는 호출자만 남긴다). | 실제 프로젝트 검증(B6: jeesite의 `CrudDao<T>` 1 → 25 팬아웃), java·link 계약 변경 |
| F-015-R30 | 엔드포인트가 기준(기본 50개, `export.calltree.splitOver`)을 넘으면, 시스템은 엔드포인트 콜트리를 컨트롤러(핸들러 메서드를 가진 타입, 모르면 URL의 첫 조각)별 `calltree/endpoints/<이름>.md`로 나누고 `calltree/endpoints.md`를 색인(엔드포인트 → 파일, 닿는 테이블)으로 써야 한다. | 실제 프로젝트 검증(19MB짜리 `endpoints.md`) |
| F-015-R31 | callees 트리의 자식은 이름순이 아니라 호출 지점 위치순(엣지 `location`의 줄, 같은 줄이면 열)이어야 한다. 위치를 모르는 호출은 그 뒤에 노드 ID 순, 구현 전개·테이블·트리거 줄은 그 뒤에 전과 같은 순서다. callers는 노드 ID 순 그대로다. | 실제 프로젝트 검증 |
| F-015-R32 | 시스템은 `report.md`의 "DDL에 없는 테이블"에 테이블마다 참조하는 구문 수를 적고, 참조 구문이 하나뿐인 테이블을 "의심(구문 파싱 복구에서 나왔을 수 있음)"으로 표시해야 한다. | 실제 프로젝트 검증(유령 테이블) |
| F-015-R33 | 콜트리 조회 API의 기본값은 컨트롤러가 그리는 JSP 아래를 펼치지 않는 것이어야 한다(`expandRenderedViews=false`, 표준 산출물과 같다). | 실제 프로젝트 검증(JSP → 요청 → 다음 엔드포인트로 내려가 노드 상한에서 잘림) |
| F-015-R34 | 주입 좁히기(R3, R19)는 호출 타입에서 구현 타입으로 바로 가는 `INJECTS`(XML 주입: `RESOLVED`, `resolvedFrom=spring:bean:…`, `via=xml` / 필드 타입이 구현 클래스인 주입)도 후보로 써야 한다. `[impl]` 줄의 근거 `OVERRIDES`가 추론이면 `~`를 붙이고, `super` 호출로 도착한 메서드에서는 구현으로 펼치지 않는다. | 뷰어 대조 |
| F-015-R35 | 표준 산출물의 콜트리는 접근자 호출(Lombok 합성 멤버, CALLS·EXECUTES가 나가지 않는 `getXxx`/`setXxx`/`isXxx`. 조건은 R43이 좁혔다)과 소스 없는 메서드의 호출 줄을 기본으로 접어야 한다(`export.calltree.hideAccessors`, `export.calltree.hideExternal`, 둘 다 기본 true). 조회 API의 기본값은 접지 않는 것이다. | 실제 프로젝트 검증(13만 줄짜리 `endpoints.md`) |
| F-015-R36 | 핸들러가 여러 모듈에 있는 엔드포인트(`ROUTES_TO`가 여럿)를 요청에서 따라갈 때, 시스템은 요청을 낸 파일과 같은 모듈 루트(위치의 파일 경로에서 `/src/main/`·`/src/test/` 앞부분)의 핸들러가 있으면 그것만 따라가야 한다. 엔드포인트에서 시작하면 전부 보이되 줄마다 `(모듈 <경로>)`를 붙인다. `MATCHES`에 `crossModule=true`가 있으면 추론(`~`)으로 표시한다. callers 방향은 대칭이다. | link 계약 변경 |
| F-015-R37 | 엔드포인트의 표시 이름은 `attrs.resolvedPath`가 있으면 그것을 쓰고 원문을 괄호로 덧붙여야 하고(`GET /a/sys/user/list (/${adminPath}/sys/user/list)`), 시작 노드 찾기는 `resolvedPath`로도 찾아야 한다. | link 계약 변경 |
| F-015-R38 | `callType=modelAttribute`인 `CALLS`(핸들러 → 같은 컨트롤러의 `@ModelAttribute` 메서드)는 핸들러의 자식 맨 앞에 `[before]`로, 리플렉션으로 추론한 호출(`callType=reflection`)은 `[reflect]`로 표시해야 한다. | java 계약 변경 |
| F-015-R39 | 필터·인터셉터(`JAVA_TYPE`의 `stereotype`이 `filter`·`interceptor`)가 있으면, 시스템은 그 진입 메서드(`doFilter`, `doFilterInternal`, `preHandle`, `postHandle`, `afterCompletion`, `afterConcurrentHandlingStarted`)에서 시작하는 callees 트리를 `calltree/filters.md`로 써야 한다. 없으면 파일을 만들지 않는다. | java 계약 변경 |
| F-015-R40 | `report.md`의 미해결 참조에서 엣지에 `argCount`가 있고 원문이 `(…)`로 끝나면, 시스템은 `name(인자 N개)`로 풀어 적어야 한다. | java 계약 변경 |
| F-015-R41 | `callType=modelAttribute` 엣지(R38의 `[before]`)는 "요청으로 진입한 핸들러" 아래에서만 따라가야 한다: 트리의 시작 노드이거나, 엔드포인트에서 `ROUTES_TO`로 도착했거나, 다른 핸들러의 redirect·forward로 도착한 메서드다. 다른 메서드가 Java 호출로 부른 핸들러 아래에는 붙지 않는다. callers 방향은 대칭이다: `@ModelAttribute` 메서드의 호출자로 올린 핸들러에서 위로는 엔드포인트(와 redirect·forward로 넘어오는 핸들러)로만 올라간다. | 3차 검증(jeesite `RoleController#save → form` 아래의 `[before] get`) |
| F-015-R42 | `callType=redirect\|forward`인 `CALLS`(다른 엔드포인트의 핸들러로 넘어가는 추론 호출)는 화면 이동(`RENDERS` 아래의 JSP)과 같이 다뤄야 한다: 기본은 `[redirect]`·`[forward]` 한 줄만 보이고 그 너머는 펼치지 않으며(callers에서는 넘어오는 쪽 핸들러 한 줄), `expandRenderedViews`(`export.calltree.expandViews`, cli `--views`)가 켜지면 펼친다. 테이블 × 엔드포인트 매트릭스와 영향도는 redirect를 따라가지 않고(브라우저가 새로 보내는 별개의 요청) forward는 따라간다(같은 요청 안의 이동). | 3차 검증(공통컴포넌트: 로그인 화면으로 가는 redirect 307건 아래 약 20줄씩 반복) |
| F-015-R43 | 접근자 접기(R35)는 이름만 보지 않아야 한다. 접는 것은 (a) Lombok이 만들 멤버(`synthetic=true`이고 `lombok`이 있음), (b) 이름이 `get`/`is`/`set` + 대문자이고 같은 타입에 대응하는 필드(`JAVA_FIELD`)가 있으며 매개변수가 getter 0개·setter 1개이고 `static`이 아닌 메서드뿐이고, 어느 쪽이든 그 메서드와 그것을 재정의한 메서드에서 `CALLS`·`EXECUTES`가 나가지 않아야 한다. 소스 없는 타입의 메서드는 접근자가 아니다(external 접기만 따른다). | 3차 검증(`isAuthenticated()`, `getNextStringId()`, `setDataSource(String)`, `setComplete()`가 숨음) |
| F-015-R44 | callers 방향에서 조건(R29의 "필요한 타입")을 넘겨받을 제네릭 상위 클래스의 호출자라도, 그 클래스의 구체 하위 타입이 하나 이상 있고 어느 것도 조건을 채우지 못하며 전부 그 메서드의 다른 팬아웃 대상을 고르면 호출자로 넣지 않아야 한다. | 3차 검증(jeesite `impact SYS_DICT`의 `TreeService#save` 경로) |
| F-015-R45 | 데이터 적재 구문(R28)은 구문 종류와 무관하게(`INSERT` 묶음, 적재 파일의 조건 없는 `DELETE`·`TRUNCATE`) 같은 규칙으로 빼야 하고, 값 자리에서 사용자 정의 함수를 부르는 적재 구문(`SQL_STATEMENT —CALLS→ DB_ROUTINE`)도 구문 기준 CRUD 매트릭스의 행이 되지 않아야 한다. | 3차 검증 |
| F-015-R46 | 핸들러 없이 화면을 그리는 엔드포인트(`<mvc:view-controller>`: `viewController=true`, 엔드포인트에서 바로 `RENDERS`)는 콜트리와 `calltree/endpoints.md`에서 `[jsp]` 줄로 이어져야 한다. 한 엔드포인트에서 모듈마다 `RENDERS`가 나가면 핸들러(R36)와 같은 규칙을 쓴다: 요청에서 오면 요청을 낸 화면과 같은 모듈의 것만, 엔드포인트에서 시작하면 전부 `(모듈 <경로>)`와 함께. | springxml 계약 변경 |
| F-015-R47 | 클라이언트 라우트(`HTTP_REQUEST`의 `clientRoute=true`: 서버 요청이 아니라 화면 안의 이동)는 콜트리에서 `[route] <경로>`(HTTP 메서드 없이)로 표시하고, 엔드포인트로 이어지지 않아도 미해결 줄을 내거나 `report.md`의 미해결 참조에 세지 않아야 한다. `report.md`에는 요청이 있을 때 "화면 요청" 절(엔드포인트에 연결됨, 연결되지 않음, 화면 내 이동, 연결률)을 둔다. | web 계약 변경 |
| F-015-R48 | 레이아웃 관계(JSP → JSP `INCLUDES`의 `via=sitemesh\|tiles`, 핸들러 → tiles template의 `RENDERS`: `via=tiles`이고 `tilesAttribute`가 아님)는 `[layout]` 한 줄만 보이고 그 너머를 따라가지 않아야 한다. `expandLayouts`(cli `--layouts`)가 켜질 때만 펼치고, `expandRenderedViews`와는 따로다(표준 산출물은 펼치지 않는다). callers에서는 그 JSP를 레이아웃으로 쓰는 화면을 `[layout] <JSP>를 쓰는 화면 n개` 한 줄로 접는다. 영향도와 테이블 × 엔드포인트 매트릭스도 따라가지 않는다. | jsp·link 계약 변경 |
| F-015-R49 | 한 호출 지점에서 오버로드를 가리지 못해 후보 전부로 이은 호출(`CALLS`의 `ambiguous=true`)이 둘 이상이면, 콜트리는 `[ambiguous n] Type#name` 한 줄로 묶고 그 아래에 후보를 펼쳐야 한다. | java 계약 변경 |
| F-015-R50 | 함께 싣는지 확인하지 못한 스크립트의 함수로 간 JS 호출(`CALLS`의 `loadUnverified=true`)은 추론(`~`)으로, 세부 표시 `(로드 미확인)`과 함께 보여야 한다. | web 계약 변경 |
| F-015-R51 | `report.md`는 DDL에 없는데 SQL이 참조하는 컬럼(정의가 있는 테이블의 `declared=false` 컬럼)이 있으면 "SQL과 DDL의 컬럼 불일치" 절(테이블, 컬럼, 참조하는 구문)을 둬야 한다. | sql 계약 변경 |
| F-015-R52 | 시스템은 CRUD 매트릭스에서 **추론한 관계를 거쳐서만 닿은 칸**의 끝에 `?`를 붙여야 한다(예: `R?`, `ru?`). 그 테이블에 닿는 확정 경로가 하나라도 있으면 붙이지 않는다. 테이블 × 엔드포인트는 호출 사슬의 걸음 가운데 "추론 걸음"을 거쳤는지로 정한다: 콜트리에서 `~`(신뢰도 `INFERRED`: `ambiguous` 호출, `prefixMatch` `EXECUTES`, 추론한 재정의, `inheritedFrom`, `crossModule` 등)·`[?]`·`[trigger?]`가 붙는 걸음이되, 프레임워크의 규칙으로 대상이 정해지는 셋 — 요청 전 처리(`callType=modelAttribute`), forward(`callType=forward`: 같은 요청 안의 서버 내부 이동. 대상 매칭이 `ambiguous`·`suffixMatch`·`crossModule`이면 추론), 수신자 문맥이 대상 하나를 고른 팬아웃 엣지(상속 매퍼 메서드의 구문, 리플렉션 호출) — 은 확정으로 본다. 팬아웃 엣지를 문맥 없이 전부 따라갔거나 문맥이 대상 여럿과 맞았으면 콜트리에 표시가 없어도 추론 걸음이다. 테이블 × 메서드는 그 구문(루틴)으로 가는 `EXECUTES` 자체가 `INFERRED`일 때만 붙인다. 구문·루틴 기준에는 붙이지 않는다. CSV의 머리줄과 열 구성은 바꾸지 않는다. | 3차 수정의 남은 일(형식 결정: 조정자) |
| F-015-R53 | 시스템은 표준 산출물에 `crud/README.md`(파일별 행의 주체, 칸의 표기: 대·소문자, `?`, `(동적)` 열, 넣지 않는 것: 데이터 적재 구문, redirect 대상)를 써야 한다(매트릭스 파일을 하나라도 쓸 때). 영향도 리포트는 추론한 관계를 거쳐서만 닿은 실행 주체·호출 사슬·엔드포인트·화면·배치 항목의 이름 앞에 `~`를, 경로 끝에 `(추론: <엣지>: <까닭>)`(그 경로에서 항목 쪽부터 처음 만나는 추론 엣지)을 붙이고 JSON에 `inferred`·`inference`를 넣어야 한다. 확정 경로가 하나라도 있으면 붙이지 않고 그 확정 경로를 적는다. | 3차 수정의 남은 일(형식 결정: 조정자) |
| F-015-R54 | `report.md`의 "화면 요청" 절은 서버 요청이 아닌 것(외부 URL `external=true`, 정적 페이지 링크, 클라이언트 라우트)을 분모에서 빼고 `서버 요청 n건 중 연결 a(확정 b, 추론 c), 미연결 d`와 연결률을 적어야 하며, 미연결 요청을 위치순으로 30개까지(파일:줄, 요청) "미연결 요청" 소절에 싣고 그리로 가는 링크를 둬야 한다(R47의 통계를 대체한다). | 3차 재검증 M14(a) |
| F-015-R55 | callees 트리에서 같은 줄에서 시작한 호출은 평가 순서로 놓아야 한다: 한 호출의 범위(엣지 `location`의 시작·끝)가 다른 호출의 범위를 포함하면 포함된 쪽이 먼저다(`f(g(x))`의 `g`). 나란한 호출은 전처럼 열 순서다(R31 보완). | 3차 재검증 M14(e) |
| F-015-R56 | 후보 엔드포인트 여럿과 맞은 요청(`MATCHES`의 `ambiguous=true`가 둘 이상)도 `[ambiguous n] <요청>` 한 줄 아래에 후보를 묶어야 한다(R49 확장). | 3차 재검증 M14(e) |
| F-015-R57 | 접근자 접기(R35, R43)는 부수 효과 없는 생성자(소스에 정의가 있는 `<init>`이고, 본문에서 나가는 호출이 역시 부수 효과 없는·소스 없는 생성자뿐이며 `EXECUTES`가 없음)와 MyBatis Generator의 조건 빌더 호출(메서드를 가진 타입의 단순 이름이 `*Example`이거나 그 중첩 타입 `*Example$Criteria` 등)도 접어야 한다. 같은 옵션(`export.calltree.hideAccessors`, cli `--show-accessors`)으로 편다. | 3차 재검증 M14(e): zheng에서 트리의 절반 |
| F-015-R58 | callers 트리에서 여러 화면에 들어가는 공통 JSP(5개 이상의 JSP가 `INCLUDES`로 포함하거나 레이아웃인 JSP)를 만나면, 시스템은 그 위(포함하는 화면 전부)를 펼치지 않고 `[shared] <JSP> (포함하는 화면 n개)` 한 줄로 내야 한다. `expandLayouts`(cli `--layouts`)가 켜지면 펼친다. 그 JSP에서 시작한 트리는 그대로 펼친다(레이아웃이면 R48의 "쓰는 화면 n개" 줄). | 3차 재검증 M14(i): 공통 헤더·메뉴 때문에 callers 1,180줄 |
| F-015-R59 | 다른 방언 파일에서 보충한 구문(`SQL_STATEMENT`의 `borrowedFrom=<방언>`)은 콜트리의 구문 줄에 `(다른 방언 <방언>에서 보충)`을 붙이고 `report.md`에 "다른 방언 파일에서 보충한 구문" 절(구문, 방언, 위치)로 적어야 한다. | mybatis 계약 변경(추가 중) |
| F-015-R60 | `report.md`의 "DDL에 없는 테이블"에서 시스템 카탈로그(테이블의 `systemCatalog=true`)는 표의 뒤에 따로 묶어 비고에 `시스템 카탈로그`를 적고 "의심"으로 표시하거나 세지 않아야 한다. | 3차 재검증 M14(g), sql 계약 변경(추가 중) |
| F-015-R61 | 영향도 리포트는 데이터 적재 구문(`dataLoad=true`)을 구문 목록(과 거기서 올라가는 계층)에서 기본으로 빼고 `데이터 적재 구문 n건 제외` 한 줄과 JSON `dataLoadExcluded`로 알려야 한다. 조회 API 인자로 넣을 수 있다(R28의 "영향도는 빼지 않는다"를 바꾼다). | 3차 재검증 M14(c) |
| F-015-R62 | 요청 → 엔드포인트 대응(`MATCHES`)에 `module`(그 요청의 웹 모듈 루트 경로)이 있으면, 그 요청에서 내려간 엔드포인트 아래로는 그 모듈의 핸들러(`ROUTES_TO`의 대상 메서드의 파일)와 그 모듈의 선언에서 나온 화면(엔드포인트 `RENDERS` 엣지의 위치가 그 모듈의 파일)만 따라가야 한다. 맞는 것이 없으면 아무것도 따라가지 않는다. 파일이 모듈에 드는지는 경로의 `src/main/`·`src/test/` 앞부분이 모듈과 같은지로(그런 조각이 없으면 모듈 경로 아래인지로) 본다. 위 방향도 같다: 핸들러(뷰 컨트롤러의 화면)에서 올라간 엔드포인트의 요청 가운데 `module`이 있는 것은 그 핸들러(그 선언)의 모듈일 때만 따라간다. `module`이 없으면 R36대로다. 콜트리, 영향도, CRUD(같은 탐색을 쓴다)에 모두 해당하고, 핸들러·화면이 둘 이상인 엔드포인트는 반복 억제(`[see above]`)와 영향도의 방문을 직전 노드별로 따로 본다. |
| F-015-R63 | 미해결 `EXECUTES`는 같은 출발 노드·같은 위치(파일, 시작 줄·열)에 `resolvedFrom`이 그 미해결 노드인 `EXECUTES`가 있으면(링커가 접두 일치 등 다른 방법으로 푼 것) 콜트리에 내지 않는다. `resolvedBy`가 있는 `UNRESOLVED` 노드로 가는 참조는 리포트의 "미해결 참조"에서 뺀다. |
| F-015-R64 | 추론한 실행·뷰 줄은 무엇으로 이었는지를 줄 끝의 세부 표시로 밝혀야 한다: 구문 ID가 접두만 맞은 실행(`EXECUTES`의 `prefixMatch`)은 `(접두 일치)`, 구문 ID를 인자로 받는 메서드에 넘긴 문자열로 이은 실행(`viaArgument`)은 `(인자 구문)`, 동적 조각이 든 뷰 이름으로 찾은 화면(`RENDERS`의 `dynamicSegment`)은 `(동적 뷰 이름 후보)`. 셋 다 추론 걸음(R52)이고 까닭은 `구문 ID 접두 일치(prefixMatch)`, `인자로 넘긴 구문 ID(viaArgument)`, `동적 조각이 든 뷰 이름의 후보(dynamicSegment)`다. |
| F-015-R65 | 요청의 표시 이름은 `httpMethod=ANY`이고 `httpMethods`가 있으면 메서드 자리에 그 목록을 `|`로 이어 적고(`POST|PUT /api/schedule`), 자기 페이지로 내는 폼(`selfSubmit=true`: ID와 `path`의 URL 자리가 `{self}`)은 `<METHOD> (자기 페이지로 제출)`로 적어야 한다. `{self}`를 URL처럼 보이지 않는다. |
| F-015-R66 | 동적 데코레이터(`INCLUDES`의 `via=sitemesh`, `dynamicDecorator=true`)는 추론 레이아웃 줄이고(까닭 `동적 데코레이터 이름(dynamicDecorator)`), 가리지 못해 후보 여럿으로 이은 것(`ambiguous=true`)은 R49·R56과 같이 `[ambiguous n] <그 화면의 이름>` 한 줄 아래에 후보를 묶는다. |
| F-015-R67 | `[ambiguous n]` 묶음은 아래 방향에서만 만들고 n은 2 이상이다(링커는 후보가 둘 이상일 때만 `ambiguous`를 붙인다. 후보가 하나뿐인 `ambiguous` 엣지는 묶지 않는다). 위 방향에서는 호출자·요청마다 지점이 달라 한 지점의 후보가 아니므로 묶지 않는다. |
| F-015-R68 | 위 방향(callers)에서 선언(핸들러 메서드, 뷰 컨트롤러 선언)이 여러 모듈에 있는 엔드포인트가 트리의 시작이면(어느 핸들러·선언에서 올라온 것이 아니면), 요청 줄에 그 요청의 `MATCHES.module`을 `(모듈 <경로>)`로 붙여야 한다. `module`이 없는 요청, 선언이 한 모듈뿐인 엔드포인트, 핸들러에서 올라온 엔드포인트(R62로 이미 그 모듈의 요청만 남는다)에는 붙이지 않는다. |
| F-015-R69 | `MATCHES`에 `crossModule=true`와 `module`이 함께 있으면(link 5차 수정: 정적 UI 모듈의 화면을 그리는 핸들러의 모듈) R36대로 추론으로 표시하되 R62의 규칙을 그대로 쓴다: 아래로는 그 모듈의 핸들러·그 모듈 선언의 화면만, 위로는 그 모듈의 핸들러(선언)에서 올라왔을 때만 그 요청을 따라간다. |
| F-015-R70 | 맞는 엔드포인트를 찾지 못한 action 없는 폼의 미해결 대상(`unresolved:http-endpoint:<METHOD> (selfSubmit)`)은 원문 대신 풀어 써야 한다(콜트리, 리포트의 "미해결 참조"). 그 요청을 낸 화면(`REQUESTS`의 출발인 `JSP_PAGE`)을 그리는 엔드포인트(화면으로 들어오는 `RENDERS`의 출발이 엔드포인트면 그것, 핸들러면 그 핸들러로 `ROUTES_TO`하는 엔드포인트)의 경로를 알면 찾던 URL로 `<METHOD> <경로> (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 <METHOD> 매핑 없음)`(경로가 여럿이면 3개까지 쉼표로, `외 n개`), 모르면 `<METHOD> (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 찾지 못함)`, 요청 문맥이 없으면(그 노드가 시작일 때) `<METHOD> (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 모르거나 그 경로에 <METHOD> 매핑 없음)`이다. |
| F-015-R71 | 후보가 여럿인 selfSubmit(`MATCHES`의 `ambiguous=true`, 요청의 `selfSubmit=true`)의 `[ambiguous n]` 묶음 줄은 요청 줄과 같은 문구를 되풀이하지 않고 `후보 엔드포인트`로 적어야 한다. 보통 요청의 묶음 줄은 R56대로 요청 이름이다. |
| F-015-R72 | 위 방향에서 엔드포인트가 트리의 시작이면(핸들러에서 `ROUTES_TO`로 올라온 것이 아니면) 그 URL로 넘어오는 forward·redirect를 호출자로 내야 한다: 그 엔드포인트의 핸들러로 들어오는 `callType`이 `forward`·`redirect`인 `CALLS`의 출발 메서드를 `[forward]`·`[redirect]` 한 줄씩(R42대로 그 위는 `expandRenderedViews`로만 펼친다). 핸들러가 엔드포인트 여럿을 받으면 엣지의 `path`(쿼리·fragment 제외)가 그 엔드포인트의 경로와 같은 것만 내고, 그 핸들러의 어느 엔드포인트와도 같지 않으면(컨텍스트 경로 등) 전부에 낸다. |
| F-015-R73 | 리포트의 종류별 노드·엣지 수와 신뢰도별 엣지 수는 그래프 그대로 세고, "미해결 참조"의 목록·건수만 R63·R47대로 뺀다. 건수가 신뢰도 `UNRESOLVED` 엣지 수와 다르면 그 절 머리에 까닭을 적어야 한다: ``신뢰도가 `UNRESOLVED`인 엣지 n건 가운데 m건은 같은 지점에서 접두 일치로 풀려(대상 노드의 `resolvedBy`) 여기서 뺐다``(`resolvedBy`가 `prefixMatch`가 아니면 "다른 방법으로"), 클라이언트 라우트의 대응은 "화면 요청" 절에서 센다는 것, 신뢰도는 `UNRESOLVED`가 아니지만 대상이 미해결 노드라 더한 엣지 수. 전부 풀려 0건이어도 적는다. |
| F-015-R74 | 화면 요청 통계(R54)는 조회 API(`SummaryReport.requestStats`)로도 줘야 한다: 전체, 확정·추론 연결, 미연결 요청(위치순), 외부 URL, 정적 페이지 링크, 클라이언트 라우트, 서버 요청 수. cli의 힌트가 리포트와 같은 수를 쓰는 데 쓴다(F-001-R60). |
| F-015-R75 | `dataLoad=true`인 구문의 `borrowedFrom=<방언>`(고른 방언의 파일에 그 테이블의 적재가 없어 다른 방언 파일의 묶음을 쓴 것. sql 5차 수정)은 매퍼 구문의 보충(R59)과 구분해야 한다: 리포트의 "다른 방언 파일에서 보충한 구문" 절에 넣지 않고, "데이터 적재 스크립트" 절에 ``이 가운데 묶음 n개는 고른 방언에 그 테이블의 적재가 없어 다른 방언 파일의 것이다(mysql 2개, …. 파일 이름 뒤에 `(다른 방언 파일: <방언>)`)`` 한 줄을 적고 그 파일 행의 이름 뒤에 `(다른 방언 파일: <방언>)`을 붙인다(표가 `export.report.maxItems`에 잘려 그 파일이 빠지면 `외 n개 파일.` 뒤에 `표에 없는 다른 방언 파일: …`로 이름을 남긴다). 콜트리의 구문 줄(R59의 자리)과 영향도(`includeDataLoad`)의 구문 이름에도 같은 표시를 붙인다. |
| F-015-R76 | 링커가 동적 뷰의 후보를 근거로 좁힌 화면(`RENDERS`의 `dynamicSegment=true` + `narrowedBy`)은 "후보"라 부르지 않고 근거를 적어야 한다: `narrowedBy=property`는 `(동적 뷰 이름: 프로퍼티 값으로 정함)`, `moduleName`은 `(동적 뷰 이름: 모듈 이름으로 정함)`(추론 표식 `~`는 그대로. 까닭은 `동적 조각이 든 뷰 이름을 프로퍼티 값으로 정함(narrowedBy=property)` 등). `narrowedBy`가 없는 `dynamicSegment`만 R64의 `(동적 뷰 이름 후보)`다. 동적 자리의 글자(`{*}`, `${app.name}`)가 아니라 `dynamicSegment` attr로 판단한다. |
| F-015-R77 | 영향도 리포트는 forward(`callType=forward`인 `CALLS`: `forward:`·`RequestDispatcher`로 다른 엔드포인트의 핸들러로 넘어감)를 **거쳐서만** 닿은 항목을 핸들러 자신의 호출 사슬로 닿은 항목과 구분해야 한다. Markdown: 항목의 이름 앞에 `[forward]`(추론 표시와 함께면 `~ [forward]`), 경로에서 forward로 넘어간 대상 핸들러의 이름 앞에 `[forward]`(콜트리의 `[forward]` 줄과 같은 자리), 넘어간 경로를 알면(엣지의 `path`) 경로 끝에 `(forward: <경로>)`, 머리의 개수 요약과 계층 제목에 직접·forward 경유의 수(`엔드포인트 43개(직접 39, forward 경유 4)`, `## 엔드포인트 (43: 직접 39, forward 경유 4)`), 머리에 안내 한 줄(``- `[forward]`는 forward로 넘어간 다른 엔드포인트의 핸들러를 거쳐서만 닿은 항목이다(n개. …)``). JSON: 항목마다 `viaForward`(boolean)와 `forward`(그 경로에서 항목 쪽부터 처음 만나는 forward. `from`: 넘기는 핸들러의 노드 ID, `to`: 넘어간 핸들러의 노드 ID, `target`: 넘어간 경로 또는 null. forward 경유가 아니면 null). 같은 확실함(R53의 확정·추론)의 경로 가운데 forward를 거치지 않는 것이 하나라도 있으면 붙이지 않고 그 경로를 적는다. 확정 경로와 추론 경로가 함께 있으면 R53대로 확정 경로가 먼저다(확정 경로가 forward 경유뿐이면 `[forward]`이고 `~`는 없다). forward 경유 항목이 없는 계층의 개수 표기와 그런 항목이 없는 리포트의 Markdown은 전과 같다. redirect는 R42대로 따라가지 않으므로 항목 자체가 없다. | 실험(2026-10-11: 공통컴포넌트 `impact COMTNGNRLMBER`의 엔드포인트 43개 가운데 4개가 forward 경유인데 구분이 없었다) |

## 4. 수용 기준

테스트 위치: `codeatlas-export/src/test/java/io/codeatlas/export/calltree/`, `…/report/`, `StandardExporterTest`.

### F-015-AC1 (대상: F-015-R1)
- Given JSP → 요청 → 엔드포인트 → 컨트롤러 → 서비스 → 매퍼 메서드 → 구문 → 테이블(컬럼 엣지 포함)로 이어진 그래프
- When 엔드포인트에서 callees 트리를 만들면
- Then 테이블까지 한 줄기로 이어지고, 테이블은 구문 아래에 한 번만(컬럼 엣지를 접어서) 접근 종류와 함께 나온다. `NEXT`로만 이어진 스텝은 자식이 아니다.

### F-015-AC2 (대상: F-015-R2)
- When 테이블에서 callers 트리를 만들면
- Then 구문 → 매퍼 메서드 → 서비스 → 컨트롤러 → 엔드포인트 → 요청 → JS 함수·JSP 순으로 거슬러 올라간다.

### F-015-AC3 (대상: F-015-R3)
- Given 인터페이스 메서드를 호출하는 메서드와 구현 2개
- When callees 트리
- Then 인터페이스 메서드 아래에 구현 2개가 `[impl]`로 나오고 각 구현의 호출이 이어진다.

### F-015-AC4 (대상: F-015-R3)
- Given 호출하는 타입 → 인터페이스 타입 `INJECTS`에 `candidates`가 구현 하나를 가리킴(또는 `resolvedFrom`이 붙은 INFERRED `INJECTS`)
- Then 그 구현만 펼친다. 좁히기를 끄면 둘 다 펼친다.

### F-015-AC5 (대상: F-015-R4)
- When 구현 메서드에서 callers 트리
- Then 인터페이스 메서드의 호출자가 `[via interface]`로 나온다.

### F-015-AC6 (대상: F-015-R5)
- Given a → b → a 재귀, 두 경로에서 호출되는 공통 메서드
- Then 재등장한 a는 `[cycle]`이고 펼치지 않는다. 공통 메서드의 두 번째 등장은 `[see above]`이고, 옵션을 끄면 다시 펼친다.

### F-015-AC7 (대상: F-015-R5)
- When 깊이 1 / 노드 수 상한 3
- Then 깊이를 넘는 자식이 없고 잘린 노드에 `[depth limit]`가 붙는다 / 노드가 3개이고 트리에 잘림 표시가 있다.

### F-015-AC8 (대상: F-015-R6)
- Given INFERRED 엣지, UNRESOLVED 엣지, external 메서드 호출
- Then 텍스트에 `~`, `?` 표시와 `file:line`이 있고, external 숨김을 켜면 external 메서드 줄이 없다.

### F-015-AC9 (대상: F-015-R7)
- Then JSON은 `id`, `kind`, `children`을 가진 트리이고, Mermaid는 `flowchart`로 시작해 flowchart 문법 검증기를 통과한다.

### F-015-AC10 (대상: F-015-R1, R2)
- Given 테이블에 `TRIGGERS_ON`이 걸린 트리거 루틴(`triggerEvent`가 `AFTER INSERT OR UPDATE`)과 루틴 안의 구문
- Then callees에서 쓰는 구문 → 테이블 → 트리거 → 트리거 안의 구문으로 이어지고, callers에서 트리거 → 테이블 → 그 테이블을 쓰는 구문으로 거슬러 간다.

### F-015-AC11 (대상: F-015-R8)
- When 테이블의 영향도
- Then 구문(C/R/U/D), 실행 메서드·루틴·스텝, 호출 사슬의 메서드, 엔드포인트, 화면(JS 함수, JSP), 배치 잡이 계층별로 나오고 Markdown 표와 JSON에 모두 있다.

### F-015-AC12 (대상: F-015-R9)
- Given 컬럼 엣지가 있는 구문 A(대상 컬럼), 구문 B(다른 컬럼만), 구문 C(테이블 엣지만)
- When 컬럼의 영향도
- Then A는 확정, C는 "테이블 수준에서만 확인됨", B는 없다.

### F-015-AC13 (대상: F-015-R10)
- Then 네 매트릭스의 칸이 기대한 C/R/U/D다(MERGE는 `CU`, 엔드포인트는 인터페이스 디스패치를 거쳐 닿는 테이블 포함). 메서드가 실행하는 루틴의 구문, 엔드포인트가 트리거를 거쳐 닿는 테이블은 소문자다(R26).

### F-015-AC14 (대상: F-015-R10)
- Then CSV는 BOM 옵션에 따라 `﻿`로 시작하고 쉼표·따옴표가 든 값이 이스케이프된다. Markdown 표가 나온다.

### F-015-AC15 (대상: F-015-R11)
- When `StandardExporter.export`
- Then R11의 파일이 생기고 `ServiceLoader`로 찾을 수 있으며 `export.crud=false` 등으로 끌 수 있다.

### F-015-AC16 (대상: F-015-R12)
- Then `report.md`에 R12의 항목이 모두 있다.

### F-015-AC17 (대상: F-015-R13)
- Given 같은 내용을 다른 순서로 넣은 두 그래프
- Then 표준 산출물 전체가 파일 단위로 바이트까지 같다.

### F-015-AC18 (대상: F-015-R1)
- When `NodeFinder.find(graph, "OrderService#create")`처럼 ID 대신 이름 일부를 주면
- Then 맞는 노드가 ID 순으로 나온다(정확한 ID·qualifiedName이 먼저).

### F-015-AC19 (대상: F-015-R14)
- Given 빈 그래프, Java 타입만 있는 그래프, 테이블만 있는 그래프, 테이블과 그것을 읽는 구문만 있는 그래프, 엔드포인트만 있는 그래프, 배치 스텝만 있는 그래프
- When `StandardExporter.export`
- Then 각각 `report.md`만 / `class-diagram/`과 `report.md`만 / `erd/`와 `report.md`만(행이 없는 CRUD 파일은 없다) / `crud/table-statement.csv`만 더 / `calltree/endpoints.md`는 있고 `batch-jobs.md`는 없음 / 그 반대다.
- `report.md`(와 `SummaryReport.render`)의 머리에 없는 대상마다 `- DB 객체 없음(DDL이나 SQL이 분석되지 않음)`, `- Java 타입 없음(Java 소스가 분석되지 않음)`, `- 엔드포인트 없음`, `- 배치 잡 없음`이 한 줄씩 있고, 전부 있는 그래프에는 이런 줄이 없다.

### F-015-AC20 (대상: F-015-R15)
- Given 위치 없는 진단, 메시지에 `|`·줄바꿈·백틱이 든 진단, 파일 이름에 `|`가 든 위치, 코드에 `|`가 든 진단
- When `SummaryReport.render`
- Then 위치 없는 진단의 위치 칸은 `-`이고, `|`는 `\|`로, 줄바꿈은 공백으로 바뀌어 모든 행의 칸 수가 머리글과 같다.

### F-015-AC21 (대상: F-015-R16)
- Given 이름에 `ORDER`가 든 테이블 둘, 컬럼 하나, 뷰 하나와 이름이 `order`인 메서드
- When `ImpactAnalysis.analyze(graph, "order")`
- Then `found()`는 false이고 `suggestions()`에 테이블·뷰·컬럼 넷이 ID 순으로(메서드는 없이) ID, 종류, 표시 이름, 위치와 함께 들어 있다. Markdown에 "비슷한 이름" 목록이, JSON에 `suggestions`가 나온다. 비슷한 이름이 없거나, DB 노드가 아닌 노드 ID를 주었거나, 대상을 찾았으면 비어 있다.

### F-015-AC22 (대상: F-015-R17)
- When `StandardExporter.OPTION_KEYS`의 모든 키에 해석할 수 없는 값을 주고 실행하면
- Then 키마다 `INVALID_OPTION` 진단이 하나씩 남는다(목록의 키는 모두 읽는다). `StandardExporter` 소스에서 읽는 `export.` 키는 모두 목록에 있다.

### F-015-AC23 (대상: F-015-R18)
- Given 한 테이블에 걸린 트리거 셋(`AFTER UPDATE OF ORDER_STATUS`, `BEFORE INSERT OR DELETE`, `triggerEvent` 없음)과 그 테이블을 쓰는 구문들(INSERT, ORDER_STATUS를 쓰는 UPDATE, 다른 컬럼만 쓰는 UPDATE, 컬럼 엣지가 없는 UPDATE, DELETE, ORDER_STATUS를 쓰는 MERGE, TRUNCATE)과 읽는 구문
- When 각 구문에서 callees 트리를, 각 트리거에서 callers 트리를 만들면
- Then INSERT 구문 아래에는 INSERT 트리거와 `[trigger?]`가 붙은 사건 미상 트리거만 있고 `UPDATE OF ORDER_STATUS` 트리거는 없다. 그 컬럼을 쓰는 UPDATE와 MERGE 아래에는 컬럼 지정 트리거가 있고, 다른 컬럼만 쓰는 UPDATE 아래에는 없으며, 컬럼 엣지가 없는 UPDATE 아래에는 `[trigger?]`로 있다. MERGE는 INSERT 트리거에도 간다. TRUNCATE와 읽는 구문 아래에는 트리거가 없다. 테이블에서 시작하면 트리거가 모두 표시 없이 나온다. callers에서는 트리거 → 테이블 아래에 그 트리거를 일으키는 구문만(불확실한 것은 `[trigger?]`로) 나온다.
- 테이블 × 엔드포인트 매트릭스: INSERT만 하는 엔드포인트의 행에 `UPDATE OF` 트리거가 쓰는 테이블이 없다.
- 영향도: 트리거가 쓰는 테이블의 영향도에 그 트리거를 일으키는 구문의 엔드포인트만 있다. 한 테이블의 두 트리거가 같은 테이블을 쓰면 엔드포인트마다 자기 트리거를 거치는 경로가 나온다.
- `triggerEvent` 해석: 시점(`BEFORE`, `AFTER`, `INSTEAD OF`, `NO CASCADE BEFORE`)을 떼고, `OR`로 이은 사건과 `UPDATE OF` 뒤의 컬럼 목록(쉼표, 따옴표 식별자)을 읽는다. 사건을 알아보지 못하면 "모름"이다.

### F-015-AC24 (대상: F-015-R19)
- Given `DiscountPolicy#discount`(인터페이스) ⇐ `DefaultDiscountPolicy#discount` ⇐ `VipDiscountPolicy#discount`(`OVERRIDES`는 가장 가까운 상위로만, Vip은 `super.discount`를 부름), 재정의가 없는 `GoldDiscountPolicy extends VipDiscountPolicy`, 호출하는 타입 넷(`candidates`가 Vip, Default, Gold, 주입 정보 없음)
- When 각 호출 메서드에서 callees 트리
- Then 주입 정보가 없으면 `[impl]`로 Default와 Vip 둘 다 나온다. 후보가 Vip이면 `[impl] VipDiscountPolicy#discount` → `DefaultDiscountPolicy#discount (super)`만, 후보가 Default이면 `[impl] DefaultDiscountPolicy#discount`만(하위 클래스 Vip의 것은 없다), 후보가 Gold이면 가장 가까운 상위 클래스의 `[impl] VipDiscountPolicy#discount`가 나온다.

### F-015-AC25 (대상: F-015-R19)
- Given AC24의 그래프
- When `VipDiscountPolicy#discount`와 `DefaultDiscountPolicy#discount`에서 callers 트리
- Then Vip 쪽에는 인터페이스 메서드의 호출자 가운데 Default를 주입받는 것을 뺀 나머지가 `[via interface]`로 나오고, 자기 자신의 `super` 호출은 호출자로 나오지 않는다. Default 쪽에는 Default를 주입받는 호출자와 주입 정보가 없는 호출자가 `[via interface]`로, `VipDiscountPolicy#discount`가 `(super)`로 나온다(Vip을 주입받는 호출자는 Vip을 거쳐서 닿는다).

### F-015-AC26 (대상: F-015-R20)
- Given 테이블 수준 `WRITES`와 조건절 컬럼의 `READS`만 있는 DELETE·UPDATE, 서브쿼리에서 같은 테이블을 읽어 테이블 수준 `READS`도 있는 UPDATE, SELECT
- Then 구문 매트릭스의 칸이 `D`, `U`, `RU`, `R`이고 메서드 매트릭스도 같다. 콜트리의 테이블 줄(callees, callers)도 `(D)`, `(U)`, `(RU)`다. 테이블의 영향도에서 접근이 `D`, `U`이고, 조건절 컬럼의 영향도에서는 `R`, 갱신하는 컬럼의 영향도에서는 `U`다.

### F-015-AC27 (대상: F-015-R21)
- Given `CLOSE_DAILY`(TB_ORDER·TB_DELIVERY를 읽음) → `CLOSE_ORDER`(TB_ORDER를 갱신, 구문 안에서 `FN_CODE` 호출) → `WRITE_HIST`(TB_ORDER_HIST에 입력), `CLOSE_ORDER` → `CLOSE_DAILY` 재귀, `WRITE_HIST` 자기 호출
- When 테이블 × 루틴 매트릭스
- Then `CLOSE_DAILY`의 칸이 TB_ORDER `Ru`, TB_DELIVERY `R`, TB_ORDER_HIST `c`, TB_CODE `r`이고 `CLOSE_ORDER`의 TB_ORDER는 `rU`다. CSV의 머리줄과 열은 그대로다. 걸음 수 상한 1이면 `CLOSE_ORDER`까지만(TB_ORDER_HIST·TB_CODE 칸이 빈다), 0이면 직접 접근만 나온다.

### F-015-AC28 (대상: F-015-R22)
- Given 엔드포인트에서 테이블까지 15걸음인 그래프
- When `StandardExporter.export`(옵션 없음 / `export.calltree.depth=8`)
- Then 기본값에서는 `calltree/endpoints.md`의 머리에 `깊이 20`이 있고 트리가 테이블 줄까지 이어지며 `[depth limit]` 줄과 안내 줄이 없다. 깊이 8에서는 테이블에 닿지 못하고 트리의 코드 블록 바로 뒤에 ``깊이 제한으로 잘림: `export.calltree.depth`로 조정`` 한 줄이 있다. `calltree/batch-jobs.md`도 같다.

### F-015-AC29 (대상: F-015-R23)
- Given `zFirst → aSecond → mThird`, `aSecond → bFail`(분기), `mThird → aSecond`(재시도 순환), `NEXT`가 없는 `bLone`, `cAlone`이 든 잡 / 시작 스텝 없이 순환뿐인 스텝 셋 / 잡을 모르는 스텝 `load → extract`와 `alone`
- Then 잡의 callees 트리에서 스텝이 `zFirst, aSecond, bFail, mThird, bLone, cAlone` 순이고 입력 순서를 뒤집어도 같다. 순환뿐이면 ID가 앞선 스텝부터 흐름을 따른다. `calltree/batch-jobs.md`의 `(잡 미상)` 절이 `load, extract, alone` 순이다.

### F-015-AC30 (대상: F-015-R24)
- Given 매퍼 메서드가 실행하지만 그 메서드를 부르는 곳이 없는 구문, 인터페이스로 불리고 구현이 실행하는 구문, 재정의한 쪽이 불리는 매퍼 메서드의 구문, 스텝이 메서드를 `USES`하는 구문, 엔드포인트의 핸들러가 직접 실행하는 구문, 자기 자신만 부르는 메서드의 구문, 메서드와 배치 스텝이 함께 실행하는 구문, `EXECUTES`가 없는 구문
- When `SummaryReport.render`
- Then "Java 호출자가 없는 매퍼 구문"에 첫째와 자기 호출뿐인 것만 `구문 (위치) ← 실행 메서드` 꼴로 나오고, `EXECUTES`가 없는 구문은 "호출하는 곳이 없는 매퍼 구문"에 그대로 나온다.

### F-015-AC31 (대상: F-015-R25)
- Given `unresolved:sql-dynamic:`으로 가는 `READS`가 있는 매퍼 구문, 정적 테이블과 동적 접근이 함께 있는 구문, 동적 `WRITES(op=INSERT)`가 있는 루틴 안의 구문
- Then 구문 매트릭스에 세 구문의 행이 있고 CSV의 마지막 열이 `(동적)`이며 칸이 `R`, `R`, `C`다(`tables()`에는 실제 테이블만). `dynamicStatements()`에 세 구문이 접근 종류·위치·원문 조각과 함께 있고, Markdown 표 뒤에 "대상 테이블을 정적으로 알 수 없는 구문" 목록이 붙는다. 행·열을 바꾼 모양에서는 마지막 행, 목록형에서는 테이블 이름 `(동적)`인 줄이다. 그 구문에 닿는 메서드·엔드포인트·루틴의 매트릭스에도 `(동적)` 열이 생긴다. 동적 구문이 없는 그래프의 출력에는 이 열과 목록이 없다.
- 영향도: 대상의 구문에는 넣지 않고 `dynamicStatements()`, Markdown의 별도 절, JSON `dynamicStatements`에 나온다(컬럼 대상에도). 동적 구문이 없거나 대상을 찾지 못했으면 비어 있다.
- `report.md`: "대상 테이블을 정적으로 알 수 없는 구문 (N)" 절에 구문 ID, 접근 종류, 위치가 있다. 표준 산출물의 `crud/table-statement.csv`에도 `(동적)` 열이 생긴다.

### F-015-AC32 (대상: F-015-R26)
- Given 메서드 —`EXECUTES`→ `{call CLOSE_ORDER}` 구문 —`CALLS`→ `CLOSE_ORDER`(TB_ORDER를 읽고 갱신) → `WRITE_HIST`(TB_ORDER_HIST에 입력), 같은 CALL 구문과 TB_ORDER를 읽는 구문을 함께 실행하는 메서드, CALL을 실행하는 메서드를 부르는 엔드포인트
- Then 메서드 매트릭스에 CALL을 실행하는 메서드의 행이 있고 칸이 TB_ORDER `ru`, TB_ORDER_HIST `c`다. 직접 읽기도 하는 메서드는 TB_ORDER `Ru`다. 구문 매트릭스의 CALL 구문 행과 엔드포인트 매트릭스의 칸도 `ru`, `c`다. 걸음 수 상한 0이면 루틴 안으로 들어가지 않고, 1이면 구문이 부른 루틴까지, 2부터 그 루틴이 부른 루틴까지 넣는다.

### F-015-AC33 (대상: F-015-R27)
- Given 테이블 200개, 컬럼 4,000개, 구문 5만 개, 컬럼 엣지 50만 개인 그래프(구문의 60%가 한 테이블에 몰려 그 테이블의 컬럼마다 들어오는 엣지가 만 개를 넘는다)
- When `StandardExporter.export`와 큰 테이블의 영향도·callers 콜트리를 실행하면
- Then 표준 출력기 전체가 60초 안에, 조회 API가 30초 안에 끝나고 `erd/tables.md`의 구문 수가 맞다.
- 테스트: `LargeGraphExportTest.standardExportFinishesOnLargeGraph`. core 쪽은 F-001-AC52.

### F-015-AC34 (대상: F-015-R28)
- Given 매퍼 구문이 읽는 테이블 하나와, 데이터 적재 구문 넷(`dataLoad=true`, 하나는 `rowCount` 없음)이 적재하는 테이블 셋
- Then 구문 기준 매트릭스에 적재 구문의 행이 없고(`includeDataLoad=true`면 있다), 테이블 명세의 "참조하는 구문"에서 빠지고 `- 데이터 적재: 구문 n개, m행` 줄이 따로 나오며(행 수를 모르는 구문이 섞이면 `m행 이상`), 테이블의 callers 콜트리에 적재 구문이 없다(옵션으로 넣는다).
- `report.md`: "데이터 적재 스크립트" 절에 `파일 2개, 테이블 3개, 행 155건 이상(행 수를 모르는 구문 1개)`와 파일별 표가 있고, 적재만 되는 테이블은 "어떤 구문에서도 참조되지 않는 테이블"에 `— 데이터 적재 스크립트만 참조`와 함께 나온다. `dataLoad` 속성이 없는 그래프에는 절이 없다.
- 표준 산출물: `export.includeDataLoad=true`면 `crud/table-statement.csv`, `erd/tables.md`, `report.md`의 테이블 점검에 들어온다.
- 테스트: `DataLoadTest`.

### F-015-AC35 (대상: F-015-R29)
- Given `CrudDao#findList`에서 `DictDao`·`UserDao`·`LogDao`의 `findList` 구문으로 가는 추론 `EXECUTES`(`namespace` + `inherited`, `namespace` + `declaredIn`, 엣지 attrs 없음 세 꼴)와, 그 메서드를 `receiverType=DictDao`로, 수신자 타입 없이, 구문이 없는 `OrphanDao`로 부르는 엔드포인트
- When 각 엔드포인트에서 callees 트리
- Then `receiverType=DictDao`이면 `DictDao.findList`만, 수신자 타입이 없으면 셋 다(표시 없음), `OrphanDao`이면 셋 다 `[?]`로 나온다(`Marker.RECEIVER_UNCERTAIN`, JSON `markers`의 `?`). 같은 메서드를 수신자 타입이 다른 두 곳에서 부르면 호출 지점마다 따로 펼친다. 수신자가 namespace의 하위 인터페이스여도 맞는다.

### F-015-AC36 (대상: F-015-R29)
- Given `CrudService#findPage`·`#save`(안에서 수신자 타입 없이 `CrudDao`의 메서드를 부른다)를 상속한 `DictService`(DAO는 `DEPENDS_ON`으로만 드러남), `UserService`(상위 타입 엣지에 `typeArguments`, `save`를 재정의하고 `super.save` 호출, 다른 DAO도 주입받음), `MixService`(DAO 둘을 참조)
- Then `receiverType=DictService`로 부른 `CrudService#findPage` 아래에는 `DictDao.findList`만, `UserService#save` → `CrudService#save (super)` 아래에는 `UserDao.insert`만(타입 인자가 정한다), `MixService` 경유에는 참조하는 두 DAO의 구문이 나온다. 상위 서비스의 메서드에서 바로 시작하면 전부다. 문맥이 다르면 같은 메서드도 `[see above]`로 접지 않고 따로 펼친다.

### F-015-AC37 (대상: F-015-R29)
- When 구문 `DictDao.findList`, `UserDao.insert`, `LogDao.insert`에서 callers 트리
- Then 상속 메서드 위로는 수신자 타입(또는 호출한 쪽 서비스가 묶는 DAO)이 그 구문의 namespace와 맞는 호출자만 나온다. 다른 namespace와 맞는 호출자는 없고, 어느 것과도 맞지 않는 호출자(`OrphanDao`)는 `[?]`로, 수신자 타입을 모르는 호출자는 표시 없이 남는다. 재정의한 상위 메서드의 호출자(`[via interface]`)도 수신자 타입이 이 메서드를 가진 타입과 무관하면 넣지 않는다.

### F-015-AC38 (대상: F-015-R29)
- Then 테이블 × 엔드포인트 매트릭스에서 엔드포인트마다 자기 DAO의 테이블만 나오고, 테이블의 영향도에는 그 DAO를 쓰는 엔드포인트만 나온다(여러 테이블에서 같은 상속 메서드로 올라가도 테이블마다 따로 좁힌다).

### F-015-AC39 (대상: F-015-R29, R38)
- Given `BaseServiceImpl#selectAll`에서 매퍼마다 나가는 `callType=reflection` `CALLS`(`receiverType`=매퍼, `viaTypes`=그 매퍼로 타입 인자를 고정한 하위 서비스)와, `viaTypes`가 없는 리플렉션 호출
- Then 호출 엣지의 `receiverType`이 `viaTypes`의 타입(또는 그 상·하위 타입: 인터페이스로 불러도 구현 타입이 맞는다)인 엣지만 따라가고 줄에 `[reflect]`가 붙는다. 문맥 없이 시작하면 전부 `[?] [reflect]`다. `viaTypes`가 없는 리플렉션 호출은 그대로 따라간다. callers와 영향도도 같다.
- 테스트(AC35~AC39): `ReceiverNarrowingTest`.

### F-015-AC40 (대상: F-015-R30)
- Given 엔드포인트 63개(컨트롤러 31개, 단순 이름이 같은 컨트롤러 둘, 핸들러가 없는 엔드포인트 둘)
- Then `calltree/endpoints/<컨트롤러 단순 이름>.md`가 컨트롤러마다 있고(단순 이름이 겹치면 전체 이름, 핸들러를 모르면 URL의 첫 조각), `calltree/endpoints.md`는 `| 엔드포인트 | 파일 | 닿는 테이블 |` 색인이다. 50개 이하이면 전처럼 한 파일이고 `export.calltree.splitOver`로 기준을 바꾼다(0이면 나누지 않는다).

### F-015-AC41 (대상: F-015-R31)
- Given 한 메서드에서 줄 10(열 5와 30), 12, 15(EXECUTES), 20에서 나가는 호출, 위치 없는 호출 둘, 테이블 둘을 읽는 구문
- Then 자식이 `10:5, 10:30, 12, 15, 20, 위치 없는 것(이름순)` 순이고 구문 아래의 테이블은 ID 순이다. 같은 대상을 여러 번 부르면 처음 부른 자리에 한 번 나온다. 입력 순서를 뒤집어도 같다. callers는 노드 ID 순이다.

### F-015-AC42 (대상: F-015-R32)
- Given DDL에 없는 테이블 셋(구문 셋이 참조, 구문 하나가 참조, 구문이 참조하지 않음)
- Then 표가 `| 테이블 | 참조하는 구문 수 | 구문 (최대 5개) | 비고 |`이고 구문 하나짜리에만 `의심(구문 파싱 복구에서 나왔을 수 있음)`이 있으며, 절 머리에 의심으로 표시한 테이블 수가 있다.

### F-015-AC43 (대상: F-015-R33)
- Then `CallTreeOptions.callees()`·`callers()`의 `expandRenderedViews()`가 false이고, 기본 옵션의 트리는 `RENDERS`로 닿은 JSP에서 끝난다. `expandRenderedViews(true)`면 그 화면의 요청과 다음 엔드포인트까지 펼친다. JSP에서 시작하면 옵션과 무관하게 펼친다.

### F-015-AC44 (대상: F-015-R34)
- Given 인터페이스 구현 둘과, 구현 타입으로 XML 주입(`RESOLVED`, `resolvedFrom=spring:bean:…`, `via=xml`)을 받는 호출 타입, 구현 클래스를 필드로 주입받는 호출 타입, 주입 정보가 없는 호출 타입, 인터페이스로 가는 후보 없는 주입과 구현 타입 주입이 함께 있는 호출 타입
- Then 앞의 둘은 주입받는 구현만 `[impl]`로 펼치고 뒤의 둘은 전부 펼친다. 좁히기를 끄면 전부다. callers에서도 다른 구현을 주입받는 호출자는 빠진다.

### F-015-AC45 (대상: F-015-R34)
- Then 추론 `OVERRIDES`로 이어진 구현(그것을 거친 간접 구현 포함)의 줄은 `~ [impl] …`이다. 재정의한 메서드가 `super`로 부른 인터페이스 default 메서드 아래에는 `[impl]` 줄이 없다(보통의 호출로 도착하면 전처럼 펼친다).
- 테스트(AC44, AC45): `DispatchNarrowingTest`.

### F-015-AC46 (대상: F-015-R35)
- Then 표준 산출물의 콜트리에 호출 없는 getter, Lombok 합성 setter, 소스 없는 메서드의 줄이 없고(다른 메서드를 부르는 getter는 남는다) 머리에 접었다는 안내와 옵션 이름이 있다. 옵션을 false로 주면 나온다. 조회 API의 기본 옵션은 전부 보이고 `hideAccessors(true)`로 접는다.

### F-015-AC47 (대상: F-015-R36)
- Given `GET /user/list`의 핸들러가 `web-admin`과 `web-front` 모듈에 하나씩, 두 모듈의 화면과 핸들러가 없는 `web-other` 모듈의 화면(그 `MATCHES`에 `crossModule=true`)
- Then `web-admin` 화면에서 내려가면 `web-admin`의 핸들러만, `web-other` 화면에서는 `~ [endpoint]` 아래에 두 핸들러가 `(모듈 web-admin)`, `(모듈 web-front)`와 함께 나온다. 엔드포인트에서 시작해도 둘 다 모듈 표시와 함께 나온다. `web-admin` 핸들러의 callers에 `web-front` 화면의 요청은 없다. 핸들러가 한 모듈에만 있으면 모듈 표시가 없다.

### F-015-AC48 (대상: F-015-R37)
- Then `resolvedPath`가 있는 엔드포인트의 표시 이름(콜트리, 매트릭스의 행)이 `ANY /a/sys/user/list (/${adminPath}/sys/user/list)`이고, `NodeFinder.find`가 치환한 경로·`METHOD 경로`·경로의 일부·원문 어느 것으로도 찾는다. `resolvedPath`가 없으면 전과 같다.

### F-015-AC49 (대상: F-015-R38)
- Then `callType=modelAttribute` 호출의 대상이 (엣지의 위치와 무관하게) 핸들러의 자식 맨 앞에 `~ [before] …`로 나오고(여럿이면 ID 순) 세부 표시 괄호는 없다. JSON `markers`의 `before`, Mermaid 엣지 라벨 `before`다. callers에서는 핸들러 줄에 붙는다.

### F-015-AC50 (대상: F-015-R39)
- Then 필터·인터셉터가 있으면 `calltree/filters.md`에 진입 메서드마다 절이 있고(`init` 같은 메서드와 진입 메서드가 없는 타입은 없다) redirect로 넘어가는 핸들러가 `~ [redirect] …` 한 줄로 나온다(R42). 없으면 파일이 없다.

### F-015-AC51 (대상: F-015-R40)
- Then `argCount`가 있는 미해결 엣지의 대상이 `vo.set(인자 2개)`로, 없는 엣지는 원문 `vo.set(…)` 그대로 나온다.
- 테스트(AC47~AC51): `ModuleAndMarkerTest`.

### F-015-AC52 (대상: F-015-R41)
- Given `save`가 `form`을 Java 호출로 부르고 둘 다 `get`(`@ModelAttribute`)으로 `callType=modelAttribute` 엣지가 있는 컨트롤러
- Then `POST …/save` 트리의 `save` 아래에는 `~ [before] get`이 있고 그 아래 `form`에는 없다. `form`의 엔드포인트와 `form`에서 시작한 트리에는 있다. 같은 트리에서 요청으로 진입한 `form`과 Java 호출로 닿은 `form`은 따로 펼친다. `get`의 callers에서 `[before] form`·`[before] save` 위에는 엔드포인트만 나오고(`form` 위에 `save`가 없다) 평범한 호출자는 그대로 나온다. 엔드포인트 매트릭스와 영향도의 경로도 같다.

### F-015-AC53 (대상: F-015-R42)
- Then redirect·forward 대상은 `~ [redirect] …`·`~ [forward] …` 한 줄이고 자식이 없다(`Marker.REDIRECT`·`FORWARD`, JSON `markers`, Mermaid 라벨. 세부 표시 괄호는 없다). `expandRenderedViews(true)`면 펼친다. 넘어간 쪽에서 시작하면 그대로 펼친다. callers에서 넘어오는 쪽 핸들러도 한 줄이다.
- 테이블 × 엔드포인트: redirect하는 엔드포인트의 행에 대상 핸들러의 테이블이 없고, forward하는 엔드포인트의 행에는 있다. 영향도도 같다.

### F-015-AC54 (대상: F-015-R43)
- Then 필드가 있는 `getTitle()`·`setTitle(String)`·`isOpen()`·`isNotice()`(필드 `isNotice`)·`getURL()`(필드 `URL`)과 Lombok 합성 멤버는 접고, 필드가 없는 `getNextStringId()`·`isAuthenticated()`·`setComplete()`, 매개변수 수가 다른 `getItem(int)`·`setItem(int, String)`, static `getInstance()`·`setDataSource(String)`, `getter()`, Lombok이 아닌 합성 메서드, 소스 없는 타입의 `isCompleted()`, 자신이나 재정의한 메서드가 호출을 하는 getter는 접지 않는다.
- 테스트: `AccessorFoldingTest`.

### F-015-AC55 (대상: F-015-R44)
- Given `TreeService<D, T> extends CrudService<D, T>`(타입 인자가 타입 변수)의 `move`가 `super`로 `CrudService#findPage`를 부르고, 하위 타입은 `LogDao`를 묶는 `LogService`뿐
- Then `DictDao.findList`의 callers와 `SYS_DICT`의 영향도에 `TreeService#move`가 없고, `LogDao.findList` 쪽에는 `TreeService#move → LogController#move → POST /log/move`가 있다. 하위 타입이 어느 것을 묶는지 알 수 없으면 전처럼 남긴다.

### F-015-AC56 (대상: F-015-R45)
- Then 루틴을 부르는 적재 구문과 적재 파일의 `DELETE`(`dataLoad=true`)는 구문 기준 매트릭스와 `crud/table-statement.csv`에 행이 없고 `includeDataLoad=true`면 있다(루틴의 테이블은 소문자).

### F-015-AC57 (대상: F-015-R46)
- Then 뷰 컨트롤러 엔드포인트의 트리가 `[endpoint] GET /login` → `[jsp] …/login.jsp`이고 `calltree/endpoints.md`(나뉘면 URL 첫 조각의 파일)에 그대로 나온다. 두 모듈이 같은 엔드포인트를 선언하면 엔드포인트에서 시작할 때 `(모듈 …)`이 붙고, 요청에서 오면 같은 모듈의 화면만 나온다.

### F-015-AC58 (대상: F-015-R47)
- Then `clientRoute=true`인 요청은 `[route] /login`으로 나오고 미해결 `MATCHES`가 있어도 그 아래에 줄이 없다. `report.md`의 "미해결 참조"에 세지 않고 "화면 요청 (N)" 절의 `화면 내 이동(클라이언트 라우트)`에 센다. 연결률은 클라이언트 라우트를 뺀 요청으로 낸다. 요청이 없으면 절이 없다.

### F-015-AC59 (대상: F-015-R48)
- Then 화면에서 데코레이터로 가는 줄, 핸들러에서 tiles template으로 가는 줄이 `[layout] [jsp] …`이고 자식이 없다(본문 JSP는 `[jsp]`). `expandRenderedViews(true)`여도 레이아웃은 펼치지 않고 `expandLayouts(true)`면 펼친다. 데코레이터의 callers에는 화면마다 줄이 나오지 않고 `[layout] <JSP>를 쓰는 화면 3개` 한 줄이 나온다(옵션을 켜면 화면마다). 영향도의 화면에 그 레이아웃을 쓰는 화면이 들지 않는다.

### F-015-AC60 (대상: F-015-R49)
- Then 같은 호출 지점의 `ambiguous=true` 호출 둘이 `~ [ambiguous 2] FileScrty#encrypt` 아래에 후보 둘로 나오고(종류 표시 `ambiguous 2`), 후보가 하나뿐인 호출 지점과 보통의 호출은 그대로다.

### F-015-AC61 (대상: F-015-R50)
- Then `loadUnverified=true`인 JS 호출이 `~ [js] common.js#fnSearch (로드 미확인)`으로 나온다(callers도).
- 테스트(AC52, AC53, AC57~AC61): `RequestFlowTest`.

### F-015-AC62 (대상: F-015-R51)
- Then 정의가 있는 테이블의 `declared=false` 컬럼이 `report.md`의 "SQL과 DDL의 컬럼 불일치 (1)" 표에 테이블·컬럼·구문과 함께 나오고, 없으면 절이 없다. 테스트: `StandardExporterTest.undeclaredColumnsOfDeclaredTables`(F-013-AC22와 함께).

아래 AC63~AC74는 4차 수정(2026-10-11: 3차의 남은 일과 3차 재검증 M14)에서 더했다. 테스트: `InferredPathTest`(AC63, AC64, AC66), `StandardExporterTest.crudReadmeExplainsNotation`(AC65), `RequestFlowTest`(AC67), `Round5Test`(AC68~AC74).

### F-015-AC63 (대상: F-015-R52)
- Given 엔드포인트에서 확정 호출로 닿는 구문(`T_SURE`, `T_BOTH`)과, 같은 호출 지점의 `ambiguous=true` 후보 둘로만 닿는 구문(`T_GUESS`·`T_BOTH`, CALL 구문을 거친 루틴의 `T_PROC`)
- Then 테이블 × 엔드포인트의 칸이 `T_SURE=R`, `T_BOTH=R`, `T_GUESS=R?`, `T_PROC=ru?`이고 CSV·Markdown에 그대로 나온다(머리줄·열은 그대로, 입력 순서를 뒤집어도 같다). 추론으로 먼저 닿은 메서드에 확정 경로로도 닿게 하면 그 아래의 칸에서 `?`가 없어진다.
- `prefixMatch` `EXECUTES`, 추론한 `OVERRIDES`의 구현, 후보 여럿 가운데서 맞춘(`ambiguous`) forward로만 닿은 칸은 `?`이고, `[before]`와 보통의 forward로만 닿은 칸은 아니다.
- 상속 매퍼 메서드: 수신자 문맥이 구문 하나를 고른 엔드포인트의 칸은 확정이고, 수신자 타입을 모르거나(전부 따라감) 구문이 없는 하위 DAO이거나(`[?]`) DAO 둘을 참조하는 서비스를 거친(둘을 고름) 칸은 `?`다. 사건을 모르는 트리거(`[trigger?]`)를 거쳐서만 닿은 칸도 `?`다(`c?`).

### F-015-AC64 (대상: F-015-R52)
- Then 테이블 × 메서드에서 상속 매퍼 메서드(`CrudDao#findList`: 추론 `EXECUTES`)의 칸은 전부 `R?`이고, 확정 `EXECUTES`만 가진 메서드의 칸에는 붙지 않으며, 같은 메서드가 확정 `EXECUTES`로도 닿는 테이블의 칸에는 붙지 않는다. 구문·루틴 기준 매트릭스에는 `?`가 없다.

### F-015-AC65 (대상: F-015-R53)
- Then 표준 산출물에 `crud/README.md`가 있고(쓴 매트릭스 파일만 파일 표에 나온다. 매트릭스 파일이 하나도 없으면 README도 없다) 대문자·소문자·`?`·`(동적)`·`W`·데이터 적재 제외(`export.includeDataLoad=true`)를 설명한다. 같은 입력이면 바이트까지 같다. 추론 호출로만 닿은 엔드포인트의 칸이 `crud/table-endpoint.csv`에 `R?`로 나온다.

### F-015-AC66 (대상: F-015-R53)
- Then AC63의 그래프에서 `T_GUESS`의 영향도: 실행 메서드에는 표시가 없고, 그것을 `ambiguous` 호출로 부른 핸들러와 그 위의 엔드포인트에 `OrderController#save() → Codec#encode(String, int): 후보 여럿(ambiguous)`가 근거로 붙는다. 화면 항목의 근거는 화면 쪽에서 처음 만나는 추론 엣지(`crossModule` 대응)다. Markdown에 `| ~ \`POST /order\` | endpoint |`와 `추론: …`, 머리의 안내 줄이 있고 JSON에 `inferred`·`inference`가 있다. 확정 경로가 있는 테이블(`T_SURE`, `T_BOTH`)의 엔드포인트에는 표시가 없고 경로는 확정 경로다.
- 상속 매퍼 메서드로 올라간 걸음은 "확인 대기"다: 그 메서드 자신과 조건을 넘겨받은 상위 서비스 메서드는 `~`이고, 수신자가 맞는 호출자(`DictController#list`)부터 확정이다. `[?]`·수신자 미상·DAO 둘을 참조하는 서비스를 거친 엔드포인트는 `~`다.

### F-015-AC67 (대상: F-015-R54)
- Then "화면 요청 (N)" 절에 `서버 요청 5건 중 연결 3(확정 1, 추론 2), 미연결 2. 엔드포인트 연결률 60%.`와 표(서버 요청, 연결됨(확정), 연결됨(추론), 미연결, 외부 URL, 정적 페이지 링크, 화면 내 이동), 분모에서 뺀 것의 안내, `### 미연결 요청` 표(위치, 요청)가 있다. 프로젝트의 정적 `.html` 파일을 가리키는 링크와 정적 페이지에서 나가는 `.html` 링크는 정적 페이지 링크이고, 서버 페이지에서 나가는 프로젝트에 없는 `.html` 경로는 미연결이다. `crossModule` 대응은 추론으로 센다. 미연결이 30개를 넘으면 `외 n건.`을 적는다.

### F-015-AC68 (대상: F-015-R55)
- Given 10줄의 `outer(inner(innermost()), x); after();`와 9줄의 `before()`
- Then 자식이 `before, innermost, inner, outer, after` 순이다(입력 순서를 뒤집어도 같다).

### F-015-AC69 (대상: F-015-R56)
- Then 엔드포인트 둘과 `ambiguous=true`로 맞은 요청 아래가 `~ [ambiguous 2] GET /list` → 후보 엔드포인트 둘이고, 후보가 하나뿐인 요청은 그대로다.

### F-015-AC70 (대상: F-015-R57)
- Then `hideAccessors`가 켜지면 `Vo#<init>`(상위 생성자만 부름), `UserExample#<init>`·`#createCriteria`, `UserExample$Criteria#andIdEqualTo` 줄이 접히고 다른 메서드를 부르는 `Loader#<init>`과 보통의 호출은 남는다. 조회 API의 기본 옵션에서는 전부 보인다.

### F-015-AC71 (대상: F-015-R58)
- Given 다섯 화면이 include하는 `menu.jsp`와 네 화면이 include하는 `small.jsp`에 같은 요청
- Then 엔드포인트의 callers에서 `menu.jsp`는 `[shared] [jsp] …/menu.jsp (포함하는 화면 5개)` 한 줄이고 `small.jsp`는 전처럼 화면마다 올라간다. `expandLayouts(true)`면 펼치고, `menu.jsp`에서 시작하면 그대로 펼친다. sitemesh 데코레이터도 위로 올라가다 만나면 `[shared]`다(AC59의 기대값을 바꿨다).

### F-015-AC72 (대상: F-015-R59)
- Then `borrowedFrom=mysql`인 구문의 줄이 `[sql] UserMapper.selectUser <SELECT> (다른 방언 mysql에서 보충)`이고(callers에서는 `(R, 다른 방언 mysql에서 보충)`) `report.md`에 "다른 방언 파일에서 보충한 구문 (1)" 표가 있다. 그런 구문이 없으면 절이 없다.

### F-015-AC73 (대상: F-015-R60)
- Then `systemCatalog=true`인 DDL에 없는 테이블은 표의 뒤에 비고 `시스템 카탈로그`로 나오고, 참조 구문이 하나여도 의심 수에 들지 않으며, 머리에 `DB가 제공하는 시스템 카탈로그(데이터 사전) n개는 뒤에 따로 묶었다`가 붙는다.

### F-015-AC74 (대상: F-015-R61)
- Then 적재 구문 둘과 매퍼 구문 하나가 접근하는 테이블의 영향도에 구문이 하나만 나오고 `dataLoadExcluded()`가 2이며 Markdown에 `- 데이터 적재 구문 2건 제외`, JSON에 `"dataLoadExcluded": 2`가 있다. `analyze(graph, target, depth, true)`면 셋 다 나오고 줄이 없다.

아래 AC75~AC80은 5차 수정(2026-10-11: java·link, web·jsp 4차 수정이 그래프에 새로 남기는 것)에서 더했다. 테스트: `Round6Test`.

### F-015-AC75 (대상: F-015-R62)
- Given 두 웹 모듈(`admin-web`, `front-web`)이 같은 경로의 핸들러와 뷰 컨트롤러 화면을 가졌고, 화면은 `WebContent/` 아래에 있어 경로로는 모듈을 알 수 없으며, 요청의 `MATCHES`에 `module`이 있다
- Then `admin-web` 화면의 콜트리에는 `AdminController#list()`와 `admin-web/…/index.jsp`만 나온다. `module`이 없는 요청은 두 핸들러가 `(모듈 …)`과 함께 나오고, `module=batch`처럼 맞는 핸들러가 없으면 엔드포인트 아래가 비며 앞의 전개를 `[see above]`로 가리키지 않는다. `AdminController#list()`의 위 방향 트리에는 `admin-web` 화면의 요청만, `front-web/…/index.jsp`의 위 방향 트리에는 `front-web` 화면의 요청만 나온다. 영향도는 `T_FRONT`에서 `front-web` 화면만, 두 핸들러가 모두 닿는 `T_SHARED`에서 두 화면을 모두 낸다.

### F-015-AC76 (대상: F-015-R63)
- Then 같은 위치의 `EXECUTES` 둘(미해결 `LoginUsr.updateLoginIncorrect{*}`, `resolvedFrom`이 그 노드인 접두 일치 구문)이 있는 메서드의 콜트리에 `~ [sql] LoginUsr.updateLoginIncorrectENT <UPDATE> (접두 일치)`만 나오고 미해결 줄은 없다. 다른 위치의 미해결 구문 줄은 그대로 나온다. 리포트는 `## 미해결 참조 (1)`이고 `resolvedBy`가 있는 노드는 목록에 없다.

### F-015-AC77 (대상: F-015-R64)
- Then `viaArgument=true`인 `EXECUTES`의 줄은 `~ [sql] BatchMapper.selectClosedOrders <SELECT> (인자 구문)`(위 방향은 `~ JobConfig#closedOrderReader() (인자 구문)`)이고, 테이블 × 메서드 칸은 `R?`, 영향도 항목의 근거는 `… → [sql] BatchMapper.selectClosedOrders <SELECT>: 인자로 넘긴 구문 ID(viaArgument)`다.

### F-015-AC78 (대상: F-015-R64)
- Then `dynamicSegment=true`인 `RENDERS` 후보 둘은 `~ [jsp] … (동적 뷰 이름 후보)` 두 줄로 나오고, 그 화면의 위 방향 트리에도 `~ BlogController#index() (동적 뷰 이름 후보)`로 나온다.

### F-015-AC79 (대상: F-015-R65)
- Then `httpMethod=ANY`, `httpMethods=[POST, PUT]`인 요청의 이름은 `POST|PUT /api/schedule`, `selfSubmit=true`인 요청은 `POST (자기 페이지로 제출)`, `httpMethods`가 없는 `ANY` 요청은 `ANY /plain` 그대로다. 리포트에 `{self}`가 나오지 않는다(표의 칸에서는 `|`를 `\|`로 적는다).

### F-015-AC80 (대상: F-015-R66, F-015-R67)
- Then 같은 선언 지점의 `ambiguous=true` 동적 데코레이터 둘은 `~ [ambiguous 2] <그 화면>` 아래에 `~ [layout] [jsp] …` 두 줄로 묶이고, 후보가 하나인 동적 데코레이터는 `~ [layout] [jsp] …` 한 줄이다. 엔드포인트의 위 방향 트리에서는 저마다 후보 대응인 요청 둘이 묶이지 않고 `~ [request] …` 두 줄로 나온다.

### F-015-AC81 (대상: F-015-R68)
- Given 두 웹 모듈(admin-web, front-web)이 같은 경로의 핸들러를 가진 엔드포인트와, `module`이 적힌 요청 둘·`module`이 없는 요청 하나 / 선언이 한 모듈뿐인 엔드포인트와 `module`이 적힌 요청
- Then 엔드포인트에서 시작한 위 방향 트리의 요청 줄은 `[request] GET /user/list (모듈 admin-web)`, `[request] GET /user/list`, `[request] GET /user/list (모듈 front-web)`이다. 핸들러에서 올라온 엔드포인트의 요청 줄과 선언이 한 모듈뿐인 엔드포인트의 요청 줄에는 표식이 없다.
- 테스트: `Round7Test.callersTagRequestModules`.

### F-015-AC82 (대상: F-015-R69)
- Given 같은 엔드포인트로 가는 `crossModule=true`, `module=front-web`인 추론 `MATCHES`(정적 UI 모듈의 화면)
- Then 그 화면의 아래 방향 트리는 `~ [endpoint] GET /user/list` 아래에 `FrontController#list()`만 나오고, `FrontController#list()`의 위 방향 트리에는 그 요청이 `~ [request]`로 나오며 `AdminController#list()`의 트리에는 없다. 엔드포인트에서 시작하면 `~ [request] GET /user/list (모듈 front-web)`이다.
- 테스트: `Round7Test.crossModuleMatchesWithModuleFollowThatModule`(따라가는 부분은 처음부터 통과: R62의 구현이 `crossModule`과 무관하다. 마지막 단언만 R68로 실패했다).

### F-015-AC83 (대상: F-015-R70)
- Given `GET /place/view.do`의 핸들러가 그리는 화면과 아무도 그리지 않는 조각 화면에 selfSubmit 요청이 하나씩 있고 둘 다 `unresolved:http-endpoint:POST (selfSubmit)`로 이어진 그래프
- Then 콜트리의 미해결 줄은 `? [unresolved] POST /place/view.do (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 POST 매핑 없음)`과 `? [unresolved] POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 찾지 못함)`이고, 그 노드의 이름(문맥 없음)은 `POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 모르거나 그 경로에 POST 매핑 없음)`이다. 리포트의 "미해결 참조"에도 같은 두 이름이 나오고 `(selfSubmit)`은 어디에도 없다.
- 테스트: `Round7Test.unresolvedSelfSubmitIsSpelledOut`.

### F-015-AC84 (대상: F-015-R71)
- Given 후보 엔드포인트 둘과 `ambiguous`로 이어진 selfSubmit 요청과 보통 요청
- Then selfSubmit의 묶음 줄은 `~ [ambiguous 2] 후보 엔드포인트`, 보통 요청의 묶음 줄은 `~ [ambiguous 2] GET /cns/{*}`다.
- 테스트: `Round7Test.ambiguousSelfSubmitGroupIsNotARepeat`.

### F-015-AC85 (대상: F-015-R72)
- Given `ANY /bbs/list.do`의 핸들러로 forward하는 핸들러, redirect하는 핸들러, 보통의 Java 호출로 부르는 메서드, 그 URL의 화면 요청 / 핸들러 하나가 엔드포인트 둘(`/bbs/a.do`, `/bbs/b.do`)을 받고 `path=/bbs/a.do?mode=1`인 forward와 어느 것과도 다른 `path`의 forward
- Then 엔드포인트에서 시작한 위 방향 트리는 `[request] GET /bbs/list.do`(와 그 화면), `~ [forward] BbsController#insert()`, `~ [redirect] BbsController#update()`이고 Java 호출만 한 메서드는 없다. 핸들러에서 시작한 트리의 엔드포인트 아래에는 forward·redirect를 다시 적지 않는다. 둘째 그래프에서 `/bbs/a.do`에는 두 forward가, `/bbs/b.do`에는 `path`가 어느 것과도 다른 forward만 나온다.
- 테스트: `Round7Test.endpointCallersIncludeForwardingHandlers`, `Round7Test.forwardsGoToTheEndpointOfTheirPath`.

### F-015-AC86 (대상: F-015-R73)
- Given 미해결 `EXECUTES` 둘 가운데 하나의 대상에 `resolvedBy=prefixMatch`가 있는 그래프 / 둘 다 있는 그래프
- Then 종류별 노드 수의 `UNRESOLVED`는 2, 종류별 엣지 수의 `EXECUTES`는 `3 | 0 | 1 | 2`, 신뢰도별 `UNRESOLVED`는 2 그대로이고, `## 미해결 참조 (1)`의 머리 문장 뒤에 ``신뢰도가 `UNRESOLVED`인 엣지 2건 가운데 1건은 같은 지점에서 접두 일치로 풀려(대상 노드의 `resolvedBy`) 여기서 뺐다.``가 붙는다. 둘 다 풀리면 `## 미해결 참조 (0)` 아래가 `없음. 신뢰도가 … 2건은 … 여기서 뺐다.`다. `resolvedBy`가 없으면 그 문장이 없다.
- 테스트: `Round7Test.reportExplainsResolvedReferences`.

### F-015-AC87 (대상: F-015-R74)
- Then 연결된 요청, 미연결 요청, 외부 URL, 클라이언트 라우트가 하나씩인 그래프에서 `requestStats`는 전체 4, 서버 요청 2, 연결 1, 미연결 1, 외부 1, 클라이언트 라우트 1이고 리포트의 `서버 요청 2건 중 연결 1(확정 1, 추론 0), 미연결 1.`과 같다.
- 테스트: `Round7Test.requestStatsMatchTheReport`(처음부터 통과: 리포트의 계산을 그대로 꺼낸 API다).

### F-015-AC88 (대상: F-015-R75)
- Given 적재 묶음 넷(하나는 `borrowedFrom` 없음, 둘은 `mysql`, 하나는 `hsql`)과 `borrowedFrom=mysql`인 매퍼 구문 하나
- Then 리포트의 "다른 방언 파일에서 보충한 구문 (1)"에는 매퍼 구문만 있고 적재 파일은 없다. "데이터 적재 스크립트" 절에 ``이 가운데 묶음 3개는 고른 방언에 그 테이블의 적재가 없어 다른 방언 파일의 것이다(hsql 1개, mysql 2개. 파일 이름 뒤에 `(다른 방언 파일: <방언>)`).``가 있고 파일 행은 `` `db/mysql/data.sql` (다른 방언 파일: mysql) ``이다. 파일 수 상한이 1이면 표에는 `db/hsql/data.sql`만 있고 `외 2개 파일. 표에 없는 다른 방언 파일: `db/mysql/data.sql` (mysql).`이 붙는다. 영향도(`includeDataLoad=true`)의 구문 이름은 `db/mysql/data.sql:1 <INSERT> (다른 방언 파일: mysql)`, 콜트리의 줄은 `[sql] db/mysql/data.sql:1 <INSERT> (C, 다른 방언 파일: mysql)`이다.
- 테스트: `Round7Test.borrowedDataLoadsAreMarked`, `Round7Test.borrowedDataLoadsStayOutOfBorrowedMapperStatements`.

### F-015-AC89 (대상: F-015-R76)
- Given 한 핸들러의 `dynamicSegment=true`인 `RENDERS` 셋(`narrowedBy=property`, `narrowedBy=moduleName`, `narrowedBy` 없이 `candidate=true`. `viewName`은 `/${app.name}/blog/index.jsp`)
- Then 줄은 `~ [jsp] … (동적 뷰 이름: 프로퍼티 값으로 정함)`, `~ [jsp] … (동적 뷰 이름 후보)`, `~ [jsp] … (동적 뷰 이름: 모듈 이름으로 정함)`이고 위 방향도 같은 표시다. `crud/README.md`의 추론 까닭 목록에 `narrowedBy`가 있다.
- 테스트: `Round7Test.narrowedDynamicViewsNameTheirEvidence`.

### F-015-AC90 (대상: F-015-R77)
- Given `POST /memo/insert.do`의 핸들러 `insert`가 `forward:/memo/list.do`로 `list`에 넘어가고(`callType=forward`, `path=/memo/list.do`) `list`만 구문(`TB_MEMO` 조회)에 닿는 그래프. `GET /memo/both.do`의 핸들러 `both`는 `list`로 forward도 하고 같은 서비스를 다른 메서드 둘을 거쳐(forward 경로보다 길게) 부르기도 한다. `POST /memo/guess.do`의 핸들러 `guess`는 `list`로 forward하고, 같은 구문에 닿는 다른 메서드를 `ambiguous` 호출로도 부른다. `insert.do`를 요청하는 JSP가 있다
- Then `TB_MEMO`의 영향도에서 `list.do`·`both.do`는 forward 경유가 아니고(`both.do`의 경로는 더 길어도 forward를 거치지 않는 경로다) `insert.do`·`guess.do`와 그 핸들러, `insert.do`를 요청하는 요청·JSP는 forward 경유다. `guess.do`는 추론 표시가 없다(확정 경로가 forward 경유로 있다). forward 경유 항목의 `forward()`는 넘기는 핸들러 → 넘어간 핸들러와 경로 `/memo/list.do`다
- Markdown: 머리 요약에 `호출 사슬 7개(직접 5, forward 경유 2)`, `엔드포인트 4개(직접 2, forward 경유 2)`, `화면 2개(forward 경유 2)`, 제목 `## 엔드포인트 (4: 직접 2, forward 경유 2)`, 행 ``| [forward] `POST /memo/insert.do` | endpoint |``, 경로에 ``→ `MemoController#insert()` → `[forward] MemoController#list()` →``와 끝의 `(forward: /memo/list.do)`, 머리의 안내 줄이 있다. 직접 닿는 행에는 표시가 없다. JSON에 `"viaForward": true`와 `"forward": {"from": …, "to": …, "target": "/memo/list.do"}`, 직접 닿는 항목에 `"viaForward": false`·`"forward": null`이 있다
- forward가 없는 그래프의 Markdown에는 `forward`라는 글자가 없다(전과 같다). redirect로만 이어진 엔드포인트는 영향도에 없다(AC53 그대로)
- 추론한 forward(대상 매칭이 `ambiguous`)로만 닿은 엔드포인트의 행은 ``| ~ [forward] `…` |``이다
- 테스트: `ForwardImpactTest`.

## 5. 가정
- 6차 수정(2026-10-11, forward 경유의 구분. R77):
  - 확인한 것: 영향도의 JSON에는 forward 여부를 알 수 있는 필드가 없었다(`inferred`·`inference`·`path`의 노드 ID뿐이고 forward는 R52대로 확정 걸음이라 `inferred`에도 드러나지 않는다). redirect는 R42대로 영향도가 올라가지 않는다(`ImpactAnalysis.climb`이 `Step.redirect()`를 건너뛴다. AC53). 뷰어(F-016)에는 영향도 화면이 없어 맞출 표시가 없다(뷰어의 콜트리는 `[forward]`를 이미 보이고, 테이블 × 엔드포인트 CRUD는 cli와 같은 규칙이다).
  - 표기는 기존 것에 맞췄다: 이름 앞의 `[forward]`는 `calltree --callers`가 넘어오는 쪽 핸들러에 붙이는 표식과 같은 글자이고 놓는 자리는 추론 표시 `~`(R53)와 같다. 경로(호출 방향으로 적는다)에서는 아래 방향 콜트리처럼 넘어간 대상 핸들러에 붙인다. 표의 열은 늘리지 않았다(컬럼 대상일 때의 "확인 수준" 열은 그대로다).
  - "forward 경유"는 적힌 경로의 성질이다: 가장 확실한 경로들(R53) 가운데 forward를 거치지 않는 것이 없을 때 붙는다. 직접 닿는 길이 추론뿐이고 확정으로 닿는 길이 forward 경유뿐이면 확정 경로를 적고 `[forward]`를 붙인다(`~`의 뜻 "확정 경로가 하나도 없다"를 바꾸지 않으려는 선택이다). 계층(실행 주체·호출 사슬 등)을 정하는 가장 짧은 걸음 수는 전과 같다.
  - 한 경로에 forward가 여럿이면 경로에는 전부 표식이 붙고 `forward`(JSON)와 `(forward: <경로>)`는 항목 쪽에서 처음 만나는 것이다. 넘기는 핸들러에서 넘어간 핸들러로 가는 forward 엣지가 여럿이면 `path`가 사전순으로 앞선 것을 적는다.
  - 테이블 × 엔드포인트 CRUD의 칸에는 forward 경유 표시를 더하지 않았다(범위 밖. README와 `crud/README.md`가 forward 대상의 접근을 넣는다고 밝히고 있다).
- 5차 수정(2026-10-11, 4차 재검증 후속. R68~R76):
  - 모듈 표식(R68)은 "엔드포인트가 시작일 때"만 붙인다: 핸들러에서 올라온 엔드포인트는 R62로 이미 그 모듈의 요청만 남아 표식이 군더더기다(기존 AC75의 기대값도 그대로다). 선언이 여러 모듈에 있는지는 `ROUTES_TO` 대상 메서드의 파일과 뷰 컨트롤러 `RENDERS`의 위치에서 읽은 모듈 루트가 둘 이상인지로 본다.
  - 미해결 selfSubmit(R70): 그리는 엔드포인트는 링커(F-012-R40)와 같은 방식으로 찾는다(포함하는 쪽을 거슬러 올라가지 않는다). 경로를 알았는데 미해결이면 까닭은 "그 경로에 그 메서드로 맞는 엔드포인트가 없다"이고(모듈 경계 규칙으로 잇지 않은 드문 경우도 같은 문구가 된다), 모르면 "그리는 엔드포인트를 찾지 못함"이다: 지시의 예시 문구("…엔드포인트에 POST 매핑 없음")는 경로를 모르는 경우에 맞지 않아 둘로 나눴다. 진단 `UNRESOLVED_REFERENCE`의 메시지(`diagnostics.md`)는 분석기가 남긴 원문(`POST (selfSubmit)`) 그대로다.
  - forward·redirect 호출자(R72): 링커는 넘어가는 경로를 엔드포인트에 맞춘 뒤 **핸들러 메서드**로 `CALLS`를 잇는다(대상이 엔드포인트인 `CALLS`는 코퍼스에 jeesite 1건뿐이고 그것은 전부터 나왔다). 그래서 엔드포인트에서 시작하면 화면 요청만 보였다. 핸들러에서 올라온 엔드포인트에 다시 적지 않는 것은 같은 줄이 핸들러의 호출자에 이미 있기 때문이다. 영향도·CRUD는 핸들러에서 엔드포인트로 올라가므로 바뀌지 않는다.
  - 미해결 수의 각주(R73): 리포트의 표는 전부터 그래프 그대로였다(바뀐 것은 각주뿐이다). cli 요약이 그 수에서 빼던 것은 F-001-R58로 그래프 그대로 세게 바뀌었다.
  - 데이터 적재의 `borrowedFrom`(R75): 요약 문장은 파일이 아니라 묶음(구문) 수를 센다(sql은 테이블 단위로 고른다). 파일 하나에 여러 방언이 섞일 수는 없지만 방어적으로 쉼표로 잇는다.
  - 좁혀진 동적 뷰(R76): `narrowedBy`가 없는 `dynamicSegment`는 `candidate`가 없어도(후보가 하나뿐이어도) 전처럼 `(동적 뷰 이름 후보)`다: 근거 없이 디렉터리 이름을 넣어 찾은 것이라 "정함"이 아니다. export·viewer·cli에 `viewName`의 `{*}` 글자를 보고 판단하는 코드는 없었다(변경 없음).
- 산출물 생략(R14)은 파일을 쓰지 않는 것뿐이다. 같은 폴더에 전에 쓴 파일이 있어도 지우지 않는다. "그릴 Java 타입"은 기본 옵션의 클래스 다이어그램에 드는 타입(소스에서 정의를 본 타입)이고, 리포트의 "Java 타입 없음"은 정의를 본 `JAVA_TYPE`(`external` 아님)이 하나도 없을 때다.
- CRUD의 `R`(R20, 감사 결과로 확정): `R`은 테이블 수준 `READS` 엣지가 있을 때만 붙는다. 설계 3.4대로 sql 분석기는 `UPDATE`·`DELETE`·`MERGE`의 대상 테이블에 테이블 수준 `READS`를 만들지 않으므로 `DELETE FROM T WHERE x = ?`는 `D`, 단순 `UPDATE`는 `U`다. 서브쿼리나 `USING`에서 같은 테이블을 읽어 테이블 수준 `READS`가 있으면 `RD`·`RU`다. 전에는 조건절 컬럼의 `READS`를 테이블로 접어 모든 조건절 있는 `DELETE`·`UPDATE`가 `RD`·`RU`로 나왔다(앞 판의 "사용자 검토 필요" 가정을 이 결정으로 대체한다). 컬럼으로 가는 `WRITES`는 전처럼 테이블로 접는다(op가 붙어 있다). 컬럼 `READS`만 있는 테이블(계약상 없어야 한다)은 콜트리에 줄은 나오되 글자가 없다. 뷰어(F-016)와 같은 규칙이다.
- 트리거 사건(R18): `triggerEvent`는 공백과 쉼표로 나눠 `INSERT`·`UPDATE`·`DELETE`만 사건으로 읽고 나머지 낱말(시점, `OR`)은 버린다. `UPDATE OF` 뒤의 낱말은 `OR`·`ON`·`FOR`·`REFERENCING`·`WHEN` 앞까지 컬럼 이름이다(따옴표 없는 것은 대문자, 비교는 대소문자 무시). `truncate=true`인 `WRITES`는 DML 트리거를 일으키지 않는 것으로 본다. `MERGE`의 컬럼 엣지는 `INSERT` 쪽과 `UPDATE` 쪽을 구분할 수 없어서 `UPDATE OF` 컬럼이 그 안에 있으면 맞는 것으로 본다. 테이블에서 시작한 callees(쓰는 구문을 모른다)는 걸린 트리거를 표시 없이 모두 낸다. `[trigger?]`는 callees에서는 트리거 줄에, callers에서는 구문 줄에 붙는다. 조회 API에서는 `Marker.TRIGGER_UNCERTAIN`, JSON `markers`의 `trigger?`, Mermaid 엣지 라벨 `trigger?`다. 영향도는 불확실한 것도 넣는다(표시는 하지 않는다).
- 간접 접근의 소문자(R21, R26): 칸의 글자는 C, R, U, D, W 순이고 같은 글자가 직접·간접 둘 다면 대문자만 적는다(예: `Ru`, `rU`, `cu`). 칸을 대문자로 바꾸면 직접·간접을 합친 CRUD다(뷰어의 메서드 기준 칸과 같다). CSV의 머리줄과 열 구성은 바꾸지 않는다. 주체별 "직접"의 뜻: 구문은 자기 SQL, 메서드는 직접 `EXECUTES`하는 구문(메서드가 루틴을 바로 `EXECUTES`하면 그 루틴의 구문도 간접이다), 루틴은 자기 본문의 구문, 엔드포인트는 호출 사슬로 닿은 구문 가운데 DB 루틴 본문에 들지 않은 것(트리거 본문의 구문도 간접이다). 구문·메서드·루틴 매트릭스는 트리거를 따라가지 않는다(엔드포인트만 따라간다). 걸음 수 상한은 엔드포인트 매트릭스의 깊이 옵션(`export.crud.depth`, 조회 API의 `maxDepth`)을 함께 쓴다: 구문→루틴, 메서드→루틴, 루틴→루틴이 한 걸음이다. 새 옵션 키는 만들지 않았다(키 목록은 설계 3.8과 맞춰야 한다).
- 동적 구문(R25): `(동적)` 열은 동적 구문에 닿는 주체가 있는 매트릭스에만 생긴다. 구문 목록(`dynamicStatements()`, Markdown의 목록)은 구문 기준 매트릭스에만 채운다. 영향도의 동적 구문은 대상과 무관하게 그래프 전체의 것이다(대상에 닿는지 알 수 없어서 전부 알린다). 동적 접근에는 컬럼 규칙을 쓰지 않는다(`READS`는 R).
- "Java 호출자가 없는 매퍼 구문"(R24)은 한 단계만 본다: 실행 메서드를 부르는 메서드가 다시 어디서도 불리지 않는지는 따지지 않는다. 같은 재정의 묶음 안의 호출(자기 호출 포함)은 호출로 세지 않는다. `resultMap`의 중첩 select(`nestedSelects`)로만 쓰이는 구문도 여기 나올 수 있다.
- 배치 스텝의 순서(R23): 스텝은 그것으로 오는 스텝들 뒤에 놓인다(깊이 우선 탐색의 끝난 순서를 뒤집은 것). 잡의 자식 가운데 스텝이 아닌 것(리스너 타입 등)은 스텝 뒤에 ID 순이다. 주어진 스텝 밖으로 나가는 `NEXT`는 보지 않는다.
- 호출 줄의 세부 표시에 `super`를 더했다(`CALLS`의 `callType=super`): `DefaultDiscountPolicy#discount(…) (super)`.
- 영향도의 비슷한 이름 후보는 `NodeFinder.find(graph, 대상, DB_TABLE, DB_VIEW, DB_COLUMN)`의 결과 전부다. Markdown은 10개까지 보이고 나머지는 개수만 적는다.
- 화면 쪽을 잇기 위해 `CONTAINS`(JSP_PAGE→JS_MODULE, JS_MODULE→JS_FUNCTION, JS_FUNCTION→JS_FUNCTION)도 따라간다(뷰어 F-016과 같은 규칙, 조정자 확정).
- 배치 스텝: 링커가 `USES`(STEP→METHOD)를 더했으면 그 메서드로 바로 간다. 스텝이 타입이나 빈만 가리키면 `SPRING_BEAN` ←`DEFINES_BEAN`— 타입·메서드, 타입 —`CONTAINS`→ public 메서드(생성자 제외)로 펼친다. 같은 스텝이 그 타입의 메서드를 직접 `USES`하면 타입·빈 줄은 생략한다. callers 방향도 같다.
- 인터페이스·추상 메서드 판단: 메서드 `modifiers`에 `abstract`가 있거나, 소유 타입의 `typeKind`가 `interface`이고 `static`이 아니거나, 들어오는 `CALLS`의 `callType`이 `interface`.
- `candidates`의 값은 타입 ID, 바이너리 이름, 빈 ID·이름 중 하나로 본다. INFERRED `INJECTS`는 호출 타입 → 구현 타입 엣지이고 `resolvedFrom`이 인터페이스 타입 ID(또는 바이너리 이름)다(링커 확정). 그런 엣지가 정확히 하나일 때만 좁힌다. 후보 타입마다 그 타입이 실제로 실행하는 구현 하나를 고른다(R19: 자기 재정의, 없으면 가장 가까운 상위 클래스의 것). 호출된 메서드 자신에 본문이 있고 후보 타입이 그것을 그대로 쓰면 펼칠 구현이 없다. 좁힌 결과가 비면(후보가 어느 구현과도 맞지 않으면) 좁히지 않는다. callers 방향에도 같은 좁히기를 적용한다. 주입 정보는 호출된 메서드를 선언한 타입으로 가는 `INJECTS`에서만 찾는다(상위 인터페이스에 선언된 메서드를 하위 인터페이스 타입으로 주입받아 부르는 경우는 좁히지 않는다).
- 구현으로 펼치는 것은 본문이 있는 메서드뿐이다. 전이적으로 따라가다 만난 추상 메서드와 인터페이스의 추상 메서드(`abstract`이거나, 인터페이스의 `default`·`static`·`private`이 아닌 메서드)는 거쳐 가기만 한다.
- external 메서드: 노드 `external=true`, 소유 타입 `external=true`, 또는 `declared=false`인 `JAVA_METHOD`.
- 줄의 소스 위치는 노드의 정의 위치다. 정의가 없는 노드(미해결, 참조만 된 것)는 엣지의 근거 위치(호출 지점)를 적는다. 이름이 곧 파일 경로인 줄(JSP, JS 모듈)에는 위치를 다시 적지 않는다. JSON에는 둘 다 넣는다.
- 같은 노드로 가는 엣지가 여럿이면 한 줄로 합친다(신뢰도는 가장 확실한 것, 호출 지점은 가장 앞선 것). 수신자 문맥이 다른 호출은 합치지 않는다(R29). callees의 자식 순서(R31, 2026-10-11 변경: 전에는 전부 노드 ID 순): ① `[before]`(R38, ID 순) ② 호출 지점 위치순(줄, 열)으로 놓는 줄 — 한 파일 안에서 순서가 있는 엣지(`CALLS`, `EXECUTES`, `REQUESTS`, `INCLUDES`, `RENDERS`, `USES`)이고 줄 번호가 있는 것 ③ 나머지(위치 없는 호출, 구현 전개, 테이블 접근, 트리거, 엔드포인트의 핸들러 등)는 노드 ID 순. 잡 아래의 스텝만 `NEXT` 흐름 순서다(R23). 위치를 비교할 때 파일은 보지 않는다(한 노드에서 나가는 호출은 같은 파일이다). callers는 호출자마다 파일이 달라 노드 ID 순 그대로다.
- 반복 억제: 끝까지 펼친 노드는 다시 만나면 `[see above]`. 깊이 제한에 잘린 채 펼친 노드는 더 깊이 볼 수 있을 때만 다시 펼친다. 결과가 문맥에 따라 달라지는 노드(구현이 둘 이상이거나 본문이 있는 메서드에 재정의가 하나 있는 디스패치 대상, 트리거가 걸린 테이블)는 문맥별로 따로 본다.
- `expandRenderedViews` 옵션: 메서드가 `RENDERS`로 가리키는 JSP 아래와 redirect·forward로 넘어간 핸들러의 아래(R42)를 펼칠지. 공통 헤더의 메뉴 링크가 다른 엔드포인트의 트리를 끌고 오는 것을 샘플에서 확인해 넣었다. 조회 API와 표준 산출물(`calltree/endpoints.md`) 모두 기본은 펼치지 않음이다(R33, 2026-10-11 변경: 조회 API의 기본이 펼침이었는데 실제 프로젝트에서 JSP → 요청 → 다음 엔드포인트로 내려가 노드 상한에서 잘렸다). 표준 산출물은 `export.calltree.expandViews=true`로 켠다. CLI `calltree`는 지금 `--no-views`가 없으면 `expandRenderedViews(true)`를 명시해 넘기므로 CLI의 기본 동작은 CLI 쪽에서 바꿔야 한다(이 기능의 범위 밖).
- 수신자 문맥(R29)의 세부. (1) 문맥을 정하는 순서: 호출 엣지의 `receiverType`(java가 수신자의 정적 타입이 대상의 선언 타입과 다를 때 남기는 바이너리 이름. 타입 ID로 와도 읽는다) → `callType=super`이거나, 호출한 쪽 타입이 대상의 선언 타입과 같거나 그 하위 타입인데 `receiverType`이 없는 호출(자기 객체에 대고 부른 것)은 호출한 쪽의 문맥(없으면 호출한 쪽의 타입. 같은 타입 안의 호출은 문맥이 없으면 없다) → 그 밖의 호출(다른 객체)은 호출한 쪽의 문맥을 "감싼 객체의 타입"으로 한 걸음만 넘긴다(상위 서비스 안의 `dao.x()`: 수신자가 타입 매개변수로 선언된 필드). `static`·`constructor`·`methodRef` 호출은 문맥을 끊는다. 문맥은 그것에 따라 아래가 달라지는 메서드(팬아웃 엣지가 나가거나, 자기 객체 호출·구현 전개로 그런 메서드에 닿는 것)로 갈 때만 싣는다. (2) 상속 구문인지: `EXECUTES`의 namespace(엣지의 `namespace`, 없으면 구문 노드의 `namespace`, 없으면 구문 ID)가 메서드를 가진 타입과 다르고, 엣지에 `inherited=true`나 `declaredIn`이 있거나 namespace 타입이 그 타입의 하위 타입이다. (3) 맞는지: 두 타입이 `EXTENDS`/`IMPLEMENTS`로 상·하위 관계이거나, 문맥 타입(과 그 상위 타입)이 "묶는" 타입 가운데 namespace(또는 그 하위 타입)가 있다. 묶는 타입은 상위 타입으로 가는 `EXTENDS`/`IMPLEMENTS` 엣지의 `typeArguments`(타입 인자 목록. **지금 java가 남기지 않는 속성이라 제안 사항이다**)가 하나라도 있으면 그것만, 없으면 `DEPENDS_ON`·`ASSOCIATES`·`INJECTS`로 참조하는 타입 전부다. 뒤쪽은 타입 인자가 아닌 타입(주입받는 다른 DAO)도 섞여 넓게 잡힌다(jeesite의 `ArticleService`는 `ArticleDao` 대신 `ArticleDao`·`ArticleDataDao`·`CategoryDao` 셋). 리플렉션 팬아웃은 `viaTypes`와의 상·하위 관계만 본다. (4) callers: 구문에서 상속 메서드로 올라갈 때 namespace를, 리플렉션 팬아웃 엣지를 거슬러 갈 때 `viaTypes`를 "필요한 타입"으로 싣는다. 호출 엣지마다 `receiverType`(없으면 호출한 쪽 메서드를 가진 타입)이 그것과 맞으면 남기고, 그 메서드의 다른 팬아웃 대상과 맞으면 빼고, 어느 것과도 맞지 않으면 — 호출한 쪽 타입에 하위 타입이 있으면(제네릭 상위 클래스) 조건을 호출한 쪽으로 넘겨 그 위에서 다시 판정하고, 아니면 `[?]`로 남긴다(수신자 타입 표시 없이 다른 객체를 부른 호출자는 표시 없이 남긴다). (5) 메서드 기준 매트릭스(`crud/table-method.csv`)는 좁히지 않는다: 상속 메서드의 행에는 전처럼 모든 하위 매퍼의 테이블이 나온다(메서드 하나만 보면 수신자를 알 수 없다). (6) 반복 억제와 방문 표시의 키에 문맥을 넣는다(문맥이 다르면 같은 메서드도 따로 펼친다).
- 재정의한 상위 메서드의 호출자(`[via interface]`, R4): 호출 엣지의 `receiverType`(없고 상속받은 메서드를 자기 객체에 대고 부른 것이면 호출한 쪽의 타입)이 이 메서드를 가진 타입과 상·하위 관계가 아니고, 이 타입의 하위 타입 가운데 그 수신자 타입의 하위 타입도 없으면 넣지 않는다(R29와 함께 넣었다. `DictService`로 부른 `CrudService#save`는 `TreeService#save`로 디스패치될 수 없다).
- 주입 좁히기의 구현 타입 주입(R34): 호출 타입에서 나가는 `INJECTS`의 대상이 호출된 메서드를 선언한 타입의 (전이적) 하위 타입이고 소스에 정의가 있는 구체 클래스(인터페이스·애너테이션·`abstract`가 아님)이면 신뢰도와 `resolvedFrom`의 형식을 가리지 않고 후보다. 다만 선언 타입 자신으로 가는 주입이 후보(`candidates`) 없이 따로 있으면 어느 구현이 들어오는지 모르는 주입이 있는 것이므로 이 규칙으로 좁히지 않는다(그 선언 타입이 구체 클래스면 그것도 후보에 넣고 좁힌다). `[impl]` 줄의 신뢰도는 그 구현까지 온 `OVERRIDES` 사슬에 `RESOLVED`가 아닌 엣지가 있으면 `INFERRED`다(처음 닿은 경로 기준).
- 접근자 접기(R35): Lombok이 만들 멤버(`synthetic=true`이고 `lombok`이 있는 메서드)이거나 이름이 `get`/`set`/`is` + 소문자가 아닌 글자로 시작하는 메서드이고, 그 메서드와 그것을 (전이적으로) 재정의한 메서드 어디에서도 `CALLS`·`EXECUTES`가 나가지 않는다. 생성자는 아니다. callees에서 `CALLS`로 닿은 줄만 접는다(시작 노드, 구현 전개 줄, callers는 접지 않는다). 소스 없는 메서드(external)는 전과 같은 판정이고(`declared=false`인 `JAVA_METHOD` 포함: java의 `inheritedFrom` 호출 대상도 여기 든다) 표준 산출물의 기본값만 바꿨다.
- 엔드포인트 콜트리 분할(R30): 묶음은 핸들러(`ROUTES_TO` 대상 가운데 ID가 앞선 메서드)를 가진 타입이고, 파일 이름은 그 타입의 단순 이름(대소문자를 무시하고 겹치면 겹치는 것 모두 전체 이름)이다. 핸들러를 모르면 경로에서 `{…}`·`$…`가 아닌 첫 조각이다. 색인의 "닿는 테이블"은 그 트리에 나온 테이블·뷰와 접근 글자를 이름순으로 8개까지 적는다(트리가 노드 수 상한에 잘렸으면 일부만 나온다). 전에 쓴 `calltree/endpoints/` 아래 파일을 지우지는 않는다.
- 데이터 적재 구문(R28): `rowCount`가 숫자가 아니면 행 수를 모르는 구문으로 세고 합계에 `이상`을 붙인다. 파일은 구문 노드의 위치(없으면 구문 ID), 테이블은 그 구문의 `WRITES` 대상이다. 영향도(`ImpactAnalysis`)는 적재 구문을 빼지 않는다(테이블을 바꾸면 적재 스크립트도 영향을 받는다). 엔드포인트·메서드·루틴 기준 매트릭스에도 같은 인자가 있지만 적재 구문은 그 주체들에 닿지 않는다. "DDL에 없는 테이블"의 구문 수도 적재 구문을 뺀 수다(적재만 되는 테이블은 비고에 `데이터 적재 스크립트만 참조`).
- 모듈 루트(R36): 노드 위치의 파일 경로가 `src/main/`·`src/test/`로 시작하면 루트 모듈(표시는 `.`), 중간에 `/src/main/`·`/src/test/`가 있으면 그 앞부분, 둘 다 아니면 모른다. 요청의 모듈을 모르거나 같은 모듈의 핸들러가 없으면 전부 따라간다. 테이블 × 엔드포인트 매트릭스는 엔드포인트에서 시작하므로 모든 모듈의 핸들러를 합친다.
- `report.md`의 미해결 분류: 마무리 단계가 만든 노드는 core가 `category`를 채운다(F-001-R38). `category` 없이 `expectedPrefix`만 있는 전의 그래프도 같은 규칙(`Ids.danglingCategory`)으로 묶는다(`mybatis:stmt` → `mybatis-statement`).
- 영향도는 화면에서 그 화면을 그리는 컨트롤러 쪽(`RENDERS`의 역방향)으로는 거슬러 가지 않는다(테이블과 무관한 엔드포인트가 딸려 오기 때문이다).
- 영향도의 계층: 구문에서 한 걸음인 메서드·루틴·스텝은 "실행 주체", 엔드포인트, 화면(요청 지점, JS 함수, JS 모듈, JSP), 배치(스텝, 잡), 나머지는 "호출 사슬"이다. 경로는 가장 짧은 것 하나다.
- `WRITES`에 `op`가 없으면 구문의 `statementType`으로 정하고, 그래도 모르면 `W`로 적는다(추측하지 않는다).
- 테이블 × 메서드에는 메서드가 `EXECUTES`하는 루틴의 구문과, 실행한 구문이 부르는 루틴의 구문도 넣는다(R26, 소문자). 테이블 × 루틴은 루틴이 직접 가진 구문(대문자)과 부르는 루틴의 구문(소문자)이다(R21). 테이블 × 구문에는 테이블에 직접 접근하지 않고 루틴만 부르는 구문(CALL)의 행도 있다. 테이블 × 엔드포인트는 콜트리와 같은 규칙으로 따라가되 화면 흐름(`RENDERS`, `REQUESTS`, `MATCHES`)은 따라가지 않고, 트리거가 쓰는 테이블은 그 트리거를 일으키는 쓰기를 거칠 때만 포함한다(R18).
- 매트릭스의 기본 모양은 행이 주체(구문·메서드·엔드포인트·루틴), 열이 테이블이다. 행·열을 바꾼 모양(`MATRIX_BY_TABLE`)과 목록형(`LIST`)이 있다. 접근이 하나도 없는 주체와 테이블은 넣지 않는다(동적 구문의 접근만 있는 주체는 넣는다).
- 표준 산출물에 `crud/table-routine.csv`(테이블 × 루틴)도 쓴다.
- 기본값: 콜트리 깊이 10, 노드 수 상한 2000, 매트릭스 깊이(엔드포인트의 호출 사슬, 루틴 호출) 20, 영향도 깊이 30, 표준 산출물의 콜트리 깊이 20·트리당 노드 500, CSV BOM 켬. 표준 산출물의 콜트리는 접근자·external 접기 켬(R35), 엔드포인트 50개 초과면 컨트롤러별 파일(R30). 표준 산출물의 콜트리 깊이는 8이었는데 샘플의 `DELETE /api/orders/{id}`가 테이블에 닿기 전에 잘려서 20으로 올렸다(R22. 샘플에서는 20에서 깊이 제한과 노드 수 상한에 걸리는 트리가 없다). 조회 API(`CallTreeOptions`)의 기본 깊이 10은 그대로다. 깊이 제한 안내 줄은 표준 산출물에만 붙는다(조회 API의 텍스트 출력은 그대로다. `CallTree.depthLimited()`로 알 수 있다).
- 매퍼 메서드: 소유 타입의 `stereotype`이 `mapper`이거나 같은 이름의 `MYBATIS_MAPPER` 노드가 있는 타입의, `static`·`default`·`private`이 아닌 메서드.
- `report.md`의 미해결 참조는 엣지 기준으로 한 번씩 세고(신뢰도 `UNRESOLVED`이거나 대상이 `UNRESOLVED` 노드인 엣지) 대상 노드의 `category`, 없으면 `expectedPrefix`로 묶어 전부 싣는다. 진단 `UNRESOLVED_REFERENCE`는 건수만 적고 항목을 다시 나열하지 않는다(조정자 확정). 다른 진단의 상위 항목은 코드별 20개다. 호출하는 곳이 없는 매퍼 구문(`EXECUTES`가 하나도 없는 구문) 목록과 Java 호출자가 없는 매퍼 구문(R24) 목록, 대상 테이블을 정적으로 알 수 없는 구문(R25) 목록도 싣는다. 동적 구문은 "미해결 참조"의 `sql-dynamic` 분류에도 엣지 단위로 나온다.
- 미해결 `java-call`의 원문(`…getJobParameters(0)`처럼 끝의 괄호에 인자 수가 든 표기)은 java 분석기가 만든 것을 그대로 보인다. 엣지에는 인자 수를 따로 담은 attrs가 없어서(`externalChain=true`뿐이다) 출력 쪽에서 `name(인자 2개)`처럼 풀어 쓰지 않는다. 표기를 바꾸는 것은 java 모듈의 몫이다.
- 출력기 옵션의 값이 잘못되면 `WARN` 진단 `INVALID_OPTION`을 남기고 기본값을 쓴다.
- `report.md`에는 시각과 절대 경로를 넣지 않는다(같은 입력에 같은 출력).
- Mermaid 문법 확인은 F-013과 같다(자체 검증기 + 실제 mermaid 11.17.2·12.0.0 수동 확인).
- 3차 수정(2026-10-11)의 세부. (1) `[before]`(R41): redirect·forward로 도착한 핸들러도 "요청으로 진입"한 것으로 본다(스프링이 요청을 다시 디스패치한다). 요청으로 진입한 핸들러와 Java 호출로 닿은 같은 핸들러는 아래가 달라 반복 억제·방문 표시의 키를 따로 쓴다(`arrivalKey`의 `|entry`). 같은 대상으로 가는 보통의 호출과 `super`·요청 전 처리·redirect·forward 호출은 한 줄로 합치지 않는다. (2) redirect·forward(R42): 표기를 괄호 `(redirect)`에서 표식 `[redirect]`로 바꿨다. CRUD·영향도에서 redirect를 따라가게 하는 옵션은 두지 않았다(옵션 키를 늘리지 않았다. 필요하면 `calltree --views`로 본다). (3) 접근자(R43): 대응하는 필드는 `getFoo`/`setFoo`/`isFoo` ↔ `foo`, 그리고 `Foo`(`getURL` ↔ `URL`), `isFoo` ↔ `isFoo`다. 상위 타입의 필드를 읽는 접근자와 인터페이스의 접근자(필드가 없다)는 접지 않는다. 매개변수 수는 메서드 ID의 시그니처에서 세고 시그니처를 모르면(`(?)`) 접지 않는다. (4) 레이아웃(R48): callers의 "쓰는 화면 n개" 줄과 `[ambiguous n]` 줄은 그래프의 노드가 아니다(JSON의 `id`는 `layout-users:<JSP ID>`, `ambiguous:<호출한 노드 ID>@<줄>:<열>`). tiles template 하나에서 조각 여럿으로 가는 `INCLUDES`는 조각마다 `[layout]` 한 줄이다. (5) `[ambiguous n]`(R49): 묶는 것은 callees뿐이고 묶음 줄도 노드 수에 센다. 후보가 `[impl]`이거나 호출 지점의 줄을 모르면 묶지 않는다. **CRUD 매트릭스와 영향도는 후보 전부를 따르지만 그 경로로 닿은 칸·행에 추론 표시를 하지 않는다**(매트릭스의 칸과 영향도의 항목에는 지금 신뢰도 표기가 없다. CSV 형식을 바꾸는 일이라 따로 정해야 한다). (6) `candidate`·`suffixMatch`(`RENDERS`), `prefixMatch`(`EXECUTES`), `ambiguous`(`CALLS`) 엣지는 분석기가 `INFERRED`로 만들므로 따로 처리하지 않아도 `~`가 붙는다. JSP → JS `INCLUDES`의 `minOf`는 콜트리에 표시하지 않는다. (7) 뷰 컨트롤러(R46)의 `[jsp]` 전개는 기존 규칙(어느 노드에서든 `RENDERS`를 따른다)으로 이미 됐고, 모듈별 선택만 새로 넣었다.
- 4차 수정(2026-10-11)의 세부. (1) 추론 걸음(R52)의 판정은 걸음(`FlowNavigator.Step.inference()`: 까닭 문구, 확정이면 null)에 싣는다. 같은 노드로 가는 엣지가 여럿이면 하나라도 확정일 때 확정이다. "수신자 문맥이 대상 하나를 고름"은 상속 구문이면 고른 엣지들의 namespace가 하나, 리플렉션이면 같은 호출 지점에서 고른 대상이 하나일 때다. 칸의 `?`는 칸 단위다(그 테이블의 접근 가운데 일부만 추론 경로여도 확정 경로가 하나 있으면 붙지 않는다). `(동적)` 열에도 같은 규칙이다. 엔드포인트 탐색은 "추론으로만 닿음/확정 경로 있음" 두 상태로 방문을 따로 세어, 추론으로 먼저 닿은 자리에 확정 경로로 다시 닿으면 그 아래를 다시 따라간다. (2) 실제 그래프에서 잰 것(같은 코퍼스 그래프를 다시 내보냄): 처음에 "콜트리의 `~`·`[?]`가 붙는 걸음 전부"로 정했더니 jeesite에서 엔드포인트 칸 911개 가운데 773개(상속 매퍼 구문 611, `[before]` 162)가 `?`가 되어 표시가 뜻을 잃었다. 그래서 요청 전 처리와 "수신자가 고른 팬아웃"을 확정으로 뺐다. 그 뒤: jeesite 엔드포인트 0/913·메서드 237/312(상속 DAO 메서드 11행), 공통컴포넌트 엔드포인트 275/2,670(전부 forward로만 닿은 칸, 158행)·메서드 3(`prefixMatch`), enterprise 14/415(forward), zheng·front-back·jpetstore 0. 그 뒤 조정자가 forward를 확정으로 정했다(같은 요청 안의 서버 내부 이동. 대상 매칭이 `ambiguous`·`suffixMatch`·`crossModule`일 때만 추론): 바뀐 수치는 tasks T51에 적었다. (3) 영향도(R53): 위 방향에서는 상속 매퍼 메서드(리플렉션 팬아웃)로 올라간 걸음을 "확인 대기"로 두고 수신자가 맞는 호출자(`CALLS`)가 확인한다. 방문은 확정 < 확인 대기 < 추론 순으로 더 확실한 것이 이긴다(경로는 가장 확실한 것 가운데 가장 짧은 것). 계층(실행 주체)은 가장 짧은 걸음 수로 정한다. 구문 항목에는 표시하지 않는다. (4) 화면 요청(R54): 정적 페이지 링크는 엔드포인트에 연결되지 않았고 경로(쿼리·fragment 제외)가 `.html`·`.htm`으로 끝나며, 프로젝트에 그 파일이 있거나(요청 파일의 폴더 기준 상대 경로, 또는 어느 폴더든 그 경로로 끝나는 `JSP_PAGE`·`FILE` 노드) 요청을 낸 파일 자신이 정적 페이지인 요청이다(zheng-ui의 시안 링크 189건). 서버 페이지에서 나가는 프로젝트에 없는 `.html` 경로는 서버 요청으로 둔다(`*.html`을 서블릿에 매핑한 프로젝트가 있다). 미연결 요청의 정렬은 파일, 줄, ID 순이다. (5) 같은 줄의 순서(R55)는 끝 위치(끝 줄, 끝 열) 순으로 구현했다: 포함된 호출이 먼저 끝난다. 끝을 모르는 위치는 시작을 끝으로 본다. (6) 공통 JSP(R58)의 기준 5는 상수(`CallTrees.SHARED_PAGE_THRESHOLD`)이고 옵션으로 두지 않았다. 포함하는 화면 수는 그 JSP로 가는 `INCLUDES`의 서로 다른 출발 JSP 수다(레이아웃 포함). 영향도와 CRUD에는 영향이 없다. (7) 빌더 판정(R57)은 타입 이름만 본다(`XxxExample`과 그 중첩 타입의 모든 메서드·생성자). 이름이 `Example`로 끝나는 보통의 타입이 있으면 그 메서드도 접힌다(옵션으로 편다). (8) `borrowedFrom`·`systemCatalog`는 분석기 쪽이 추가 중인 속성이라 실제 그래프로는 확인하지 못했다(손으로 만든 그래프의 테스트만 있다). (9) 영향도의 데이터 적재 제외(R61)를 켜는 cli 옵션은 두지 않았다(조회 API 인자만).
- 5차 수정(2026-10-11)의 세부. (1) 모듈 판정(R62): 링커가 적는 `module`은 모듈 루트 경로다(실제 값 예: `zheng-api/zheng-api-server`). 루트 모듈은 빈 문자열이나 `.`로 읽고 끝의 `/`는 뗀다. 핸들러는 메서드 노드의 파일, 뷰 컨트롤러의 화면은 `RENDERS` 엣지의 위치(없으면 화면의 파일)로 본다. `module`이 있는 대응은 엄격하게 거른다(맞는 것이 없다고 전부로 넓히지 않는다). CRUD의 엔드포인트 기준은 엔드포인트에서 시작해 `MATCHES`를 거치지 않으므로 결과가 달라지지 않는다(같은 탐색을 써서 규칙은 적용된다). (2) R63의 "같은 위치"는 엣지 위치의 파일·시작 줄·시작 열이 같다는 뜻이다(둘 다 위치가 없어도 같다고 본다). 진단 `UNRESOLVED_REFERENCE`의 건수는 그래프에 남은 대로다. (3) R64의 세부 표시는 표식(`[…]`)이 아니라 줄 끝의 괄호다(`(모듈 …)`, `(로드 미확인)`과 같은 자리). `prefixMatch`는 그동안 `~`만 붙고 무엇으로 이었는지가 줄에 없어서 `viaArgument`와 함께 넣었다. `MATCHES`의 `dynamicSegment`(요청의 `{*}`가 고정 조각과 맞음)에는 붙이지 않는다. (4) R65의 `selfSubmit` 요청이 맞는 엔드포인트를 찾지 못했을 때 미해결 대상의 원문(`POST (selfSubmit)`)은 링커가 만든 이름 그대로 적는다. (5) 실제 그래프(새 분석기로 다시 분석, `codeatlas export`): 공통컴포넌트 미해결 참조 1,228 → 1,227(`LoginUsr.updateLoginIncorrect{*}`), `(접두 일치)` 3줄, `(인자 구문)` 2줄, `(자기 페이지로 제출)` 요청 10개; zheng `(동적 뷰 이름 후보)` 21개 엣지, `module`이 있는 대응 19개(`GET /` 13, `GET /manage/index` 6: `zheng-api-server`의 `500.jsp`에서 내려가면 그 모듈의 `index.jsp`만 나온다); jeesite 동적 데코레이터 14개(모두 후보 하나, `ambiguous` 없음); react 템플릿 `POST|PUT` 요청 4개; sample-shop `(인자 구문)` 1줄(`DailySalesJobConfig#closedOrderReader`). (6) forward 결정(R52, T51)의 수치: 공통컴포넌트 테이블 × 엔드포인트의 `?` 칸 275 → 0(전부 forward로만 닿은 칸이었다), enterprise 14 → 1(남은 1칸은 forward 대상을 후보 여럿에서 맞춘 `~ [forward]`), 메서드 기준(공통컴포넌트 3, jeesite 237)은 그대로다.

## 6. 미확정 사항
- 없음(5절의 가정은 사용자 검토가 필요하다).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: 없음

## 8. design.md에 반영할 것
design.md 반영됨(6차 수정, 2026-10-11. 직접 고쳤다): R77은 3.4 "소비자가 지키는 규칙"의 "영향도의 forward 경유 표시"에 넣었다.

design.md 반영됨(5차 수정, 2026-10-11. 직접 고쳤다): R68·R69·R72는 3.4 "핸들러가 여러 모듈에 있는 엔드포인트", R70·R71은 "요청의 표시 이름", R73은 "풀린 미해결 `EXECUTES`는 숨길 수 있다", R74·R75는 "화면 요청 통계", R76은 "추론의 근거 표시"에 넣었다.

design.md 반영됨(2026-10-11): 첫 항목(R62~R67)은 F-001 design.md 3.4 "소비자가 지키는 규칙"의 "핸들러가 여러 모듈에 있는 엔드포인트", "풀린 미해결 `EXECUTES`는 숨길 수 있다", "추론의 근거 표시", "요청의 표시 이름", "추론 경로의 표시"에 넣었다. 둘째 항목(읽는 쪽 설명)은 3.5 표에 따로 열을 두지 않고 그 3.4 항목들과 3.5의 각 attr 행(`module`, `viaArgument`, `prefixMatch`, `dynamicSegment`, `dynamicDecorator`, `resolvedBy`, `httpMethods`, `selfSubmit`)에 적었다. 셋째 항목은 3.8에 산출물 표가 없어 3.4 "추론의 근거 표시"에 `crud/README.md`를 적는 것으로 대신했다.
- 3.4(표준 산출물의 규칙): `MATCHES.module`이 있으면 엔드포인트에서 그 모듈의 핸들러와 그 모듈 선언의 화면만 따라간다(양방향, R62). 미해결 `EXECUTES`의 숨김과 `resolvedBy` 노드의 미해결 집계 제외(R63). 줄 끝의 `(접두 일치)`·`(인자 구문)`·`(동적 뷰 이름 후보)`(R64). 요청 이름의 `POST|PUT`·`(자기 페이지로 제출)`(R65). 동적 데코레이터의 추론 레이아웃 줄과 `[ambiguous n]` 묶음, 묶음은 아래 방향에서만(R66, R67).
- 3.5(엣지 attrs)의 읽는 쪽 설명: `MATCHES.module`, `EXECUTES.resolvedFrom`·`viaArgument`, `RENDERS.dynamicSegment`, `INCLUDES.dynamicDecorator`, 노드 `resolvedBy`·`httpMethods`·`selfSubmit`을 export·viewer가 어떻게 쓰는지.
- 3.8(산출물 표): 콜트리 표기 설명과 `crud/README.md`의 추론 까닭 목록에 위 표시가 들어갔다.
