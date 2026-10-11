---
status: proposed
date: 2026-10-11
decision-makers: 에이전트 초안 (프로젝트 소유자 승인 전)
---

# 팬아웃 엣지는 그래프에 전부 남기고, 소비자가 수신자 문맥으로 좁힌다

## Context and Problem Statement
정적으로는 대상이 하나로 정해지지 않고 한 노드에서 여러 대상으로 퍼지는 관계가 있다. 인터페이스·상위 타입의 메서드 호출은 선언 메서드로 가고 구현은 여러 타입에 있다. 제네릭 상위 DAO의 메서드(`CrudDao<T>#findList`)는 메서드 노드가 하나인데 하위 매퍼마다 다른 구문을 실행한다. 제네릭 상위 클래스 안의 리플렉션 호출은 타입 인자를 고정한 하위 타입마다 대상이 다르다. 엔드포인트 노드는 메서드와 경로가 같으면 하나라 여러 웹 모듈의 핸들러가 한 노드에 모인다. 이런 관계를 그래프에 어떻게 기록하고, 콜트리·CRUD 매트릭스·영향도가 실제로 실행되는 대상만 따라가게 하는 일을 어디에서 할지 정해야 한다.

## Decision Drivers
- 추측으로 관계를 만들지 않고, 해석하지 못한 것을 버리지도 않는다(AGENTS.md). 가능한 대상을 지우면 조용한 누락이 된다.
- 어느 대상이 맞는지는 호출 경로에 달려 있다. 같은 `CrudDao#findList` 노드라도 `OrderService`를 거쳐 왔으면 `OrderDao`의 구문, `MemberService`를 거쳐 왔으면 `MemberDao`의 구문이다. 엣지 하나에는 경로가 없다.
- 노드 ID는 계약으로 고정돼 있고 모든 모듈이 그것으로 만난다(메서드는 선언 타입 기준, 엔드포인트는 메서드와 경로 기준). 그래프는 추가 전용이다(엣지를 지우는 API가 없다).
- 분석기는 서로를 참조하지 않는다(ADR-0006, ADR-0007). 좁히는 데 필요한 정보는 java, mybatis, link의 결과에 흩어져 있다.
- 공개 프로젝트 검증에서 제네릭 DAO 하나가 25개 매퍼의 구문으로 퍼져 콜트리와 엔드포인트 CRUD가 부풀었다(architecture.md 11절의 "팬아웃").

## Considered Options
- 그래프에는 가능한 대상을 전부 남기고 엣지에 문맥 속성을 붙여, 소비자(export, viewer)가 경로의 수신자 문맥으로 좁힌다
- 분석기·링커가 좁혀서 기록한다
- 좁히지 않는다

## Decision Outcome
Chosen option: "전부 남기고 소비자가 좁힌다", because 좁히는 데 필요한 문맥이 경로마다 달라 엣지에 담을 수 없고, 가능한 대상을 그래프에서 지우지 않아야 좁히지 못한 경우를 "전부, 불확실하다는 표시와 함께" 보여 줄 수 있기 때문이다.

분석기와 링커는 팬아웃 엣지를 전부 만들고, 소비자가 대상을 고르는 데 쓸 속성을 붙인다.

| 속성 | 붙는 곳 | 뜻 | 쓰는 모듈 |
|---|---|---|---|
| `receiverType` | `CALLS` | 수신자 타입이 대상의 선언 타입과 다를 때 그 타입 | java |
| `typeArguments` | `EXTENDS`, `IMPLEMENTS` | 하위 타입이 상위 타입에 넘긴 타입 인자 | java |
| `viaTypes` | `CALLS`(`callType=reflection`) | 대상 타입을 타입 인자로 고정한 하위 타입들 | java |
| `inherited`, `namespace`, `declaredIn` | `EXECUTES` | 상위 인터페이스에서 물려받은 매퍼 메서드가 실행하는 구문의 구체 매퍼 타입 | link |
| `module` | `MATCHES` | 선언이 여러 모듈에 있는 엔드포인트로 가는 같은 모듈 매칭의 모듈 루트 | link |
| `candidates`, `resolvedFrom` | `INJECTS` | 주입 대상에 대입할 수 있는 빈 타입, 하나로 좁힌 구현 | java, link |

소비자는 경로에서 가장 최근에 알려진 구체 수신자 타입(문맥)을 들고 내려가며 그 문맥과 맞는 엣지만 따라간다. 맞는 것이 하나도 없으면 전부 따라가되 불확실하다고 표시한다(콜트리의 `[?]`). 위 방향(호출자 찾기)도 대칭이다. 구현 전개는 `OVERRIDES`를 전이적으로 따라가고 주입 정보로 좁힌다. 규칙의 전문은 계약 3.4 "소비자가 지키는 규칙"에 있고, export는 `FlowNavigator` 한 곳에, viewer는 `js/core/calltree.js`에 구현한다.

### Consequences
- Good, because 그래프가 가능한 대상을 빠짐없이 갖고 있어, 좁히는 규칙을 고쳐도 다시 분석할 필요가 없다(`export` 명령으로 산출물만 다시 만든다).
- Good, because 좁히지 못한 경우가 숨지 않는다. 전부 따라간 줄에 `[?]`가 붙고, 그런 걸음을 거쳐서만 닿은 CRUD 칸에는 `?`가, 영향도 항목에는 `~`와 근거가 붙는다.
- Good, because 분석기는 자기 언어의 사실만 기록한다. java는 호출의 수신자 타입과 타입 인자를, link는 구문의 namespace를 남기고 서로의 결과를 몰라도 된다.
- Bad, because `graph.json`의 엣지를 그대로 따라가면 틀린다. 그래프를 직접 읽는 쪽(다른 도구, 질의)은 계약 3.4의 규칙을 다시 구현해야 한다.
- Bad, because 같은 규칙이 두 곳에 있다. export(Java)와 viewer(JavaScript)가 따로 구현하므로 한쪽만 고치면 표준 산출물과 뷰어의 콜트리가 달라진다.
- Bad, because 문맥으로 좁히는 일 자체가 휴리스틱이다. 문맥은 호출 엣지의 `receiverType`, `super`·자기 객체 호출로 이어받은 타입, 그 타입이 `typeArguments`나 참조(`DEPENDS_ON`·`ASSOCIATES`·`INJECTS`)로 묶는 타입에서 얻고, `static`·생성자·메서드 참조 호출에서 끊긴다. 문맥이 없거나 여러 대상과 맞으면 전부 따라간다.
- Bad, because 팬아웃 엣지가 그래프에 그대로 남아 엣지 수와 `graph.json`·`viewer.html`의 크기가 는다.

### Confirmation
- export: F-015-R29(수신자 문맥), R34(주입으로 좁히기), R36·R62(모듈), R44(위 방향)의 수용 기준 테스트.
- viewer: F-016-R23·R41의 수용 기준 테스트(`codeatlas-viewer/src/test/js`). 표준 산출물과 같은 결과가 나오는지는 조각 테스트와 수동 대조로만 확인했다(F-016 tasks의 미결 항목).
- link·java: 속성을 남기는지는 F-012-R25·R37, F-002-R15, F-003-R20의 수용 기준 테스트.

## Pros and Cons of the Options

### 분석기·링커가 좁혀서 기록
- Good, because 그래프의 엣지가 곧 답이라 소비자가 단순해지고 export와 viewer가 같은 결과를 낸다.
- Bad, because 한 메서드 노드에서 나가는 엣지는 그 메서드를 누가 불렀는지와 무관하게 하나의 집합이다. 경로별로 다른 대상을 기록하려면 노드를 호출 문맥마다 복제하거나(메서드 ID에 수신자 타입을 넣는다) 경로를 엣지에 적어야 한다. 앞의 것은 계약의 ID 규칙과 모든 모듈의 참조를 바꾸고 노드 수를 하위 타입 수만큼 늘리며, 뒤의 것은 경로의 수가 조합으로 늘어난다.
- Bad, because 좁히지 못한 대상을 버리면 조용한 누락이고, 남기면 결국 소비자가 다시 골라야 한다.
- Bad, because 좁히는 데는 java(타입 인자, 수신자), mybatis·link(구문의 namespace), springxml·java(주입)의 결과가 모두 필요하다. 링커의 한 단계로 둘 수는 있지만 위의 노드 복제 문제는 그대로다.

### 좁히지 않음
- Good, because 구현이 없고 그래프와 산출물이 같은 것을 보여 준다.
- Bad, because 제네릭 상위 DAO·서비스를 쓰는 프로젝트에서 엔드포인트 하나가 모든 매퍼의 같은 id 구문에 닿는다. jeesite에서 `CrudDao<T>`의 메서드 하나가 25개 매퍼의 구문으로 퍼져 콜트리와 엔드포인트 CRUD, 영향도가 부풀었다(F-015-R29의 출처 B6).
- Bad, because 여러 웹 모듈의 같은 경로 엔드포인트에서 다른 모듈의 핸들러와 화면까지 따라가 없는 흐름을 보여 준다. zheng에서 11개 모듈의 `500.jsp → GET /`가 자기 모듈의 view-controller가 아니라 다른 모듈의 컨트롤러로 이어졌다(F-012-R37의 출처 M5).

## More Information
- 관련 기능: F-002(R15 `typeArguments`), F-003(R20 리플렉션의 `viaTypes`, `receiverType`), F-012(R12 주입 후보, R25 물려받은 매퍼 메서드, R37 `module`), F-015(R19·R29·R34·R36·R44·R62), F-016(R23·R41)
- 계약: `docs/specs/F-001-analysis-pipeline/design.md` 3.4("소비자가 지키는 규칙"), 3.5(엣지 attrs), 7절("팬아웃 엣지는 수신자 문맥이 있어야 좁힌다", "엔드포인트 ID에 모듈이 없다")
- 코드: `codeatlas-export`의 `io.codeatlas.export.calltree.FlowNavigator`, `codeatlas-viewer`의 `js/core/calltree.js`, `codeatlas-link`의 `MapperBindingLinker`·`EndpointMatcher`
- 다시 볼 조건: 그래프를 직접 읽는 다른 소비자가 생길 때(좁힌 결과를 파생 엣지로 저장할지 다시 본다), export와 viewer의 결과가 어긋나는 일이 되풀이될 때(규칙을 한 곳에서 생성해 공유할지 다시 본다).
