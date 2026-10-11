---
type: spec
id: F-003
title: Java 콜 그래프
status: implemented     # draft | approved | implemented
updated: 2026-10-11
---

# F-003 Java 콜 그래프

## 1. 배경
PRD의 첫 목표는 IDE의 Call Hierarchy 수준의 콜트리다. 이 기능은 JDT가 해석한 바인딩으로 메서드 호출, 생성자 호출, 오버라이드, 필드 접근을 엣지로 만든다. 의존 jar가 없어도 프로젝트 안의 호출은 정확히 잇고, 해석하지 못한 호출은 버리지 않고 미해결로 남긴다.

## 2. 범위
- 포함: `CALLS`, `INSTANTIATES`, `OVERRIDES`, `ACCESSES`, 초기화 코드의 소유자 규칙, 외부(라이브러리·JDK) 호출 처리, 해석 실패 호출의 미해결 기록, Lombok 합성 멤버(F-002-R14)로의 호출 해석과 합성 메서드가 낀 재정의.
- 제외: 동적 디스패치의 실제 대상 추정(인터페이스 호출은 인터페이스 메서드로 잇고 구현은 `OVERRIDES`로 찾는다), 리플렉션(F-003-R20의 한 가지 꼴만 추론으로 잇는다), 암묵적 `super()` 호출, 컴파일러가 해석하지 못한 곳의 제네릭 타입 인자 추적.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-003-R1 | 시스템은 메서드 호출마다 정적으로 해석된 선언(제네릭 인스턴스가 아닌 선언)으로 `CALLS`를 만들고 `attrs.callType`(`static`, `virtual`, `interface`, `super`, `constructor`, `methodRef`)과 호출 위치를 남겨야 한다. | 사용자 요청, design 3.4 |
| F-003-R2 | 시스템은 `new`에 대해 생성자로의 `CALLS`와 `INSTANTIATES`를, `this(...)`/`super(...)`에 대해 생성자로의 `CALLS`를 만들어야 한다. | 사용자 요청 |
| F-003-R3 | 시스템은 람다 본문의 호출을 람다를 감싼 메서드에 귀속하고 `attrs.viaLambda=true`를 붙여야 하며, 메서드 참조는 `callType=methodRef`로, 익명 클래스의 메서드 안 호출은 그 익명 타입의 메서드에 귀속해야 한다. | 사용자 요청 |
| F-003-R4 | 시스템은 필드 초기화식·초기화 블록·enum 상수 인자의 코드를 합성 소유자에 귀속해야 한다: static은 `<clinit>()`, 인스턴스는 명시적 생성자가 없으면 기본 생성자, 하나면 그 생성자, 여럿이면 `<instance-init>()`. 합성 노드는 `attrs.synthetic=true`다. | 사용자 요청 |
| F-003-R5 | 시스템은 프로젝트 타입의 메서드가 상위 클래스·인터페이스의 메서드를 재정의·구현하면 `OVERRIDES`를 만들어야 한다(제네릭 상위 타입 포함, 가지마다 가장 가까운 선언). 대상은 직접 상위들이다: 상위 클래스 체인에서 가장 가까운 선언 하나와, 구현하는 인터페이스 계통마다 가장 가까운 선언 하나씩. 그 위의 선언은 잇지 않는다(소비자가 `OVERRIDES`를 전이적으로 따라간다). | 사용자 요청, design 3.4 |
| F-003-R6 | 시스템은 프로젝트 타입의 필드 읽기·쓰기마다 `ACCESSES`(`attrs.access`)를 만들어야 한다. | design 3.4 |
| F-003-R7 | 호출 대상이 소스 없는 타입의 메서드이면, 시스템은 옵션 `java.externalCalls`(`none`, `nonJdk`(기본), `all`)에 따라 external 참조 노드로의 `CALLS`를 만들거나 건너뛰고, 건너뛴 개수를 `INFO` 진단 `EXTERNAL_CALLS_SKIPPED` 1건으로 요약해야 한다. | 사용자 요청 |
| F-003-R8 | 만약 호출의 바인딩이 해석되지 않으면, 시스템은 미해결 엣지(`CALLS`, 분류 `java-call`, 원문 `<수신자>.<메서드명>(…)`, 인자 수는 엣지 `attrs.argCount`)를 남겨야 한다. 원문의 인자 자리에는 인자 수나 인자 값을 적지 않고 `(…)`(U+2026) 그대로 둔다. 단 수신자 타입이 classpath에 없는 외부 타입임이 분명하면 `<FQN>#<메서드명>(?)` external 메서드로의 호출로 다룬다. | 사용자 요청, AGENTS.md |
| F-003-R9 | 만약 컴파일러가 인자 불일치·모호함으로 가장 가까운 후보를 고른 호출이면, 시스템은 그 후보로 잇지 않고 미해결로 남겨야 한다. 단 Lombok 합성 멤버에 맞는 것이 있으면 F-003-R11을, 후보를 선언한 타입이 소스 없는 타입이면 F-003-R13을 따른다. | AGENTS.md (추측 금지) |
| F-003-R10 | 시스템은 외부 호출의 JDK 여부를 F-002-R13의 판정으로 정해야 한다. `javax.servlet` 등 라이브러리 타입의 호출·생성은 `java.externalCalls=nonJdk`(기본)에서 external 호출로 기록하고, `javax.sql` 등 JDK 타입의 것은 건너뛴다. | 사용자 요청 |
| F-003-R11 | 만약 컴파일러가 호출을 해석하지 못했는데(메서드 바인딩 없음, 또는 인자 불일치) 수신자 타입이 프로젝트 타입이면, 시스템은 모든 파일을 본 뒤 그 타입과 상위 타입에서 이름·인자 수가 맞는 Lombok 합성 멤버를 찾아 `CALLS`(`RESOLVED`, `attrs.lombok=true`)를 만들어야 한다(합성 생성자는 `CALLS` + `INSTANTIATES`). 체인(`Order.builder().id(1).build()`, `a.getB().getC()`)은 찾은 메서드의 반환 타입을 다음 링크의 수신자 타입으로 삼아 이어 가고, 그렇게 추적한 수신자에서는 선언된 메서드도 이름·인자 수로 찾는다. 메서드 참조와 람다 안의 호출도 같다. 이름·인자 수가 같은 후보가 여럿이면 `INFERRED`로 낮추고, 인자 타입으로 줄여도 여럿이면 남은 후보 모두에 `attrs.ambiguous=true`로 잇는다. 찾지 못하거나 추적이 끊기면 F-003-R8대로 미해결이다. | 사용자 요청 |
| F-003-R12 | 만약 소스 없는 타입(classpath에 없어 이름만 복원한 타입 포함)의 메서드가 돌려준 값에 이어 부르는 호출이라 해석할 수 없으면, 시스템은 링크마다가 아니라 체인당 미해결 엣지 1건(`attrs.externalChain=true`, 원문은 가장 바깥 링크의 `<수신자>.<메서드명>(…)`, `attrs.argCount`는 그 링크의 인자 수, 위치는 체인 식 전체)과 `INFO` 진단 `UNRESOLVED_REFERENCE` 1건만 남겨야 한다. 체인 중간의 인자 안에 있는 호출은 따로 해석한다. | 사용자 요청 |
| F-003-R13 | 만약 컴파일러가 인자 불일치로 고르지 못한 호출의 가장 가까운 후보를 선언한 타입이 소스 없는 타입이면, 시스템은 그 후보가 아니라 `<FQN>#<메서드명>(?)` external 메서드로의 호출로 다뤄야 한다(`java.externalCalls`에 따라 기록하거나 센다). | AGENTS.md (추측 금지), F-003-R8 |
| F-003-R14 | 컴파일러가 보지 않아 바인딩이 아예 없는 코드(해석되지 않은 호출에 넘긴 람다 본문 등)에서는, 시스템은 수신자 변수의 선언(지역 변수·매개변수·필드)과 타입 이름을 AST와 import로 직접 찾아 F-003-R11의 방식으로 호출을 해석해야 한다. 수신자가 JDK 타입이면 실행 중인 JDK에서 이름·인자 수로 메서드를 찾아 반환 타입을 따라간다(타입 변수를 돌려주면 거기서 끊긴다). | 사용자 요청 |
| F-003-R15 | 만약 호출의 인자에 해석되지 않은 값(classpath에 없는 타입의 값 등)이 있어 컴파일러가 호출 자체에는 오류를 보고하지 않고 "가장 그럴듯한" 후보를 주었으면, 시스템은 그 후보를 확인한 뒤에만 이어야 한다. (가) 후보의 인자 수가 호출과 맞지 않으면 F-003-R9처럼 그 후보로 잇지 않는다. (나) `new X(..)`·`super(..)`·enum 상수의 후보가 만들려는 타입(상위 클래스, 그 enum)의 생성자가 아니면(`java.lang.Object`, `java.lang.Enum`의 생성자) 버리고, 타입이 classpath에 없으면 `<FQN>#<init>(?)` external 호출로, 프로젝트 타입이면 F-003-R11·R8로 다룬다. | 독립 감사(E1), AGENTS.md (추측 금지) |
| F-003-R16 | 시스템은 실행 코드 안의 호출 지점(메서드 호출, `new`, `this(..)`/`super(..)`, 인자 있는 enum 상수, 메서드 참조)마다 `CALLS`, 미해결 엣지, `EXTERNAL_CALLS_SKIPPED` 집계 중 하나를 반드시 남겨야 한다. 체인당 미해결(F-003-R12)의 안쪽 링크는 바깥 링크의 엣지 1건이 대신한다. | 독립 감사(E1), AGENTS.md (미해결을 버리지 않는다) |
| F-003-R17 | 만약 메서드의 매개변수 타입 어딘가(타입 자체, 배열 원소, 제네릭 타입 인자)에 classpath에 없는 타입이 있어 컴파일러가 재정의를 판정하지 못하면, 시스템은 프로젝트 안의 상위 타입(클래스·인터페이스)에서 이름·매개변수 수가 같고 위치별 매개변수 타입의 표기가 같은 메서드를 찾아 `OVERRIDES`를 만들어야 한다(F-003-R5처럼 가지마다 가장 가까운 선언). 표기가 전부 같으면 `RESOLVED`, FQN을 한쪽만 알아 단순 이름까지만 비교한 자리가 있으면 `INFERRED`다. 프로젝트 상위 타입에서 찾았으면 classpath에 없는 상위 타입으로의 추론 엣지(`<FQN>#<이름>(?)`)는 만들지 않는다. `@Override`가 있는데 프로젝트 상위에 후보가 없을 때만 그 추론을 한다. | 공개 프로젝트 검증(B7) |
| F-003-R18 | 재정의 미해결 엣지(`OVERRIDES`, 분류 `java-override`)의 원문은 `java-call`과 같은 표기 `<이름>(…)`이어야 하고, 매개변수 수는 엣지 `attrs.argCount`에 남겨야 한다. | F-003-R8과의 일관성 |
| F-003-R19 | 만약 수신자가 프로젝트 타입(타입 이름으로 부른 static 호출, 그 타입의 식으로 부른 인스턴스 호출)인데 그 타입과 해석된 상위 타입에 이름·인자 수가 맞는 선언이 없고 그 타입의 계층에 classpath에 없는 상위 타입이 있으면, 시스템은 미해결로 두지 않고 그 상위 타입의 `<FQN>#<이름>(?)` external 메서드로 `CALLS`(`INFERRED`, `attrs.inheritedFrom`=그 FQN)를 만들어야 한다. static 호출은 상위 클래스의 것으로 본다. 인스턴스 호출은 classpath에 없는 상위 타입이 하나면 그 타입으로, 여럿이면 상위 클래스 쪽으로 `attrs.ambiguous=true`와 함께 잇고 `attrs.receiverType`에 수신자 타입을 남긴다. 상위 클래스가 없고 인터페이스만 여럿이면 미해결이다. | 공개 프로젝트 검증(B19) |
| F-003-R20 | 시스템은 `X.getClass().getDeclaredMethod("<문자열 상수>", ..)`(또는 `getMethod`)의 결과를 같은 메서드 안에서 `.invoke(X, ..)`한 리플렉션 호출을 추론으로 이어야 한다. X의 선언 타입이 프로젝트 타입이면 그 타입(과 프로젝트 상위 타입)의 이름·인자 수가 맞는 메서드로, 감싼 클래스의 타입 매개변수면 그 제네릭 클래스를 상속하며 그 타입 인자를 프로젝트 타입 T로 고정한 하위 타입마다 T의 메서드로 `CALLS`(`INFERRED`, `attrs.callType=reflection`, `attrs.receiverType`=T, 출발은 리플렉션 호출이 든 메서드, 위치는 `invoke` 호출)를 만든다. 타입 매개변수를 거친 엣지에는 그 T로 고정한 하위 타입들을 `attrs.viaTypes`에 남긴다. 그 꼴이 아니거나 대상을 정하지 못한 `Method.invoke`가 있는 메서드는 엣지 없이 메서드당 `INFO` 진단 `REFLECTION_CALL` 1건으로 남긴다. | 공개 프로젝트 검증(B16), AGENTS.md (추론은 `INFERRED`로) |

| F-003-R21 | 만약 컴파일러가 고른 선언의 매개변수 중 classpath에 없는 타입의 자리에 그 타입일 수 없는 해석된 타입의 인자(`String` 리터럴 등 계층에 미해석 상위 타입이 없는 타입)가 왔거나, 원시 타입·final 클래스 매개변수 자리에 classpath에 없는 타입의 값이 왔으면, 시스템은 그 선언을 믿지 않고 수신자 타입과 그 상위 타입에서 이름·인자 수가 같은 다른 선언을 찾아야 한다(생성자는 그 타입의 것만). 인자 타입과 맞는 후보가 정확히 하나면 그것으로 잇고(모든 자리를 해석된 타입으로 확인했으면 `RESOLVED`, 미해석 타입이 섞인 자리가 있으면 `INFERRED`), 없거나 여럿이면 F-003-R9처럼 미해결로 남긴다. 그런 인자가 없으면(인자도 미해석이면) F-003-R15대로다. | 공개 프로젝트 검증 2차 후속(zheng `thymeleaf(Model)`), AGENTS.md (추측 금지) |
| F-003-R22 | 시스템은 Lombok 합성 메서드(F-002-R14)가 낀 재정의에도 `OVERRIDES`(`RESOLVED`, `attrs.lombok=true`)를 만들어야 한다: 합성 메서드가 프로젝트 상위 타입(클래스·인터페이스)의 선언을 구현·재정의한 경우와, 상위 타입의 합성 메서드를 하위 타입의 선언 메서드나 합성 메서드가 재정의한 경우다. 모든 파일을 본 뒤 이름·매개변수 수·위치별 매개변수 타입의 표기(F-003-R17과 같은 표기)로 가지마다 가장 가까운 선언을 찾는다. static·private 메서드와 생성자는 대상이 아니다. | 공개 프로젝트 검증 2차 후속(simple-backend), F-003-R5 |
| F-003-R23 | 호출의 수신자(타입 이름 또는 값)나 만들려는 타입이 F-002-R20의 "풀지 못한 단일 import의 이름"이면, 시스템은 컴파일러가 다른 타입(`java.lang`, 같은 패키지)에서 찾아 준 메서드·생성자로 잇지 않고 import한 타입의 호출로 다뤄야 한다: 프로젝트 타입이면 그 타입에서 찾고, 아니면 `<FQN>#<이름>(?)` external 호출이다. | 실전 재검증 2차 후속(2026-10-11)(java.lang 오해석, N16) |
| F-003-R24 | 인자에 classpath에 없는 타입의 값이 있고 이름·인자 수가 맞는 후보(상위 타입 포함, 가변 인자 고려)가 둘 이상이면, 시스템은 컴파일러가 고른 "먼저 선언된 후보"를 그대로 쓰지 않고 인자와 매개변수의 타입을 위치별로 비교해야 한다(해석된 자리는 대입 가능 여부, 제네릭은 원시 타입으로, 양쪽 다 미해석인 자리는 표기: import로 복원한 FQN, 없으면 쓴 이름). 맞는 후보가 정확히 하나면 그것으로 잇고(컴파일러의 선택과 다르면 `INFERRED`), 가리지 못하면 한 후보를 고르지 않고 맞는 후보 전부(하나도 없으면 후보 전부)로 `INFERRED` + `attrs.ambiguous=true`로 이어야 한다. 생성자도 같다. 컴파일러가 인자 불일치로 본 호출도 인자에 미해석 타입이 있으면 같은 비교로 맞는 후보가 정확히 하나일 때 그것으로 `INFERRED`로 잇는다(아니면 F-003-R9대로 미해석 호출). | 실전 재검증 2차 후속(2026-10-11)(N15, jeesite `addMessage` 96건 중 89건) |
| F-003-R25 | 수신자 없는(또는 `this.`를 붙인) 미해석 호출을 프로젝트에서 찾지 못했고 감싼 타입의 classpath에 없는 상위 타입이 여럿이면, 시스템은 수신자를 적은 호출(F-003-R19)과 같은 규칙으로 상위 클래스 쪽의 `<FQN>#<이름>(?)`로 `INFERRED` + `attrs.ambiguous=true`(`inheritedFrom`)로 이어야 한다. classpath에 없는 상위 클래스가 없으면(인터페이스만 여럿) 전처럼 미해결이다. | 실전 재검증 2차 후속(2026-10-11)(N28-c) |
| F-003-R26 | 수신자가 필드인 호출에서, (1) 그 필드를 프로젝트의 다른 타입(상위 클래스)이 선언했고 필드 타입이 classpath에 없으면 시스템은 필드를 선언한 파일의 import로 타입을 복원해 `<FQN>#<이름>(?)` external 호출로 이어야 하고, (2) 수신자가 어디에도 선언이 없는 소문자 이름이고 감싼 타입에 classpath에 없는 상위 타입이 있으면(그 상위 타입에서 물려받은 필드) `WARN` 미해결이 아니라 체인 미해결(F-003-R12: `externalChain=true`, INFO)로 남겨야 한다. | 실전 재검증 2차 후속(2026-10-11)(N28-f) |
| F-003-R27 | 컴파일러가 풀지 못한 `getBean(X.class)`·`getBean(<이름>, X.class)` 호출의 결과에 이어 부른 호출은, X를 알 수 있으면 시스템이 X의 메서드로 `INFERRED`로 이어야 한다. | 실전 재검증 2차 후속(2026-10-11)(N28-e) |
| F-003-R28 | 오버로드를 가릴 때(F-003-R21, R24) 컴파일러가 타입을 주지 않은 인자 식의 타입을 시스템이 다음에서 읽어야 한다: (a) Lombok 합성 getter 호출은 그 필드의 선언 타입(합성 멤버 색인. 모든 파일을 본 뒤에 다시 가린다), (b) 컴파일러가 선언을 찾은 메서드 호출식은 그 선언의 반환 타입(이름·인자 수가 맞는 선언이 하나이거나 모두 같은 타입을 돌려줄 때만. 안쪽 인자를 몰라도 된다), (c) 문자열 리터럴(또는 `String`으로 읽힌 조각)이 낀 `+` 사슬은 `String`, 비교·논리식은 `boolean`, (d) 캐스트·`new T[]`·`new T(..)`는 적은 타입, 삼항은 양쪽이 같은 타입일 때 그 타입, `this`는 감싼 타입, 클래스 리터럴은 `Class`. `null`은 참조 타입 자리 어디에나 맞으므로 가르는 근거로 쓰지 않는다. 원시 타입·박싱 타입·`String`·배열은 그 밖의 타입 자리에 오지 않는 것으로 본다(`String` 값은 `byte[]` 자리에 오지 않는다). 맞는 후보가 여럿 남으면, 확인하지 못한 자리가 없는 후보(인자의 선언 타입 표기가 그 후보의 매개변수 타입 표기와 같다: 외부 타입 `MultipartFile` 인자 ↔ `(MultipartFile, …)` 대 `(File, …)`, catch 변수의 예외 타입)가 정확히 하나일 때 그것을 고른다. | 실전 재검증 3차 후속(2026-10-11)(M2: 타입으로 가릴 수 있는 호출 35곳이 `ambiguous`로 격하됨) |
| F-003-R29 | 시스템은 잇는 대상이 하나뿐인 호출 엣지에 `attrs.ambiguous`를 붙이지 않아야 한다. classpath에 없는 상위 타입이 여럿이어서 상위 클래스 쪽으로 고른 상속 호출(F-003-R19, R25)은 `INFERRED` + `inheritedFrom`만 남긴다(전에는 `ambiguous=true`였다). | 실전 재검증 3차 후속(2026-10-11)(M2: enterprise 6건, jeesite 2건) |

## 4. 수용 기준

### F-003-AC1 (대상: F-003-R1)
- Given 인자 타입만 다른 오버로드 `save(String)`, `save(int)`, `save(Order)`와 각각을 부르는 메서드
- When 분석하면
- Then 호출마다 인자 타입에 맞는 오버로드로 `CALLS`가 있고 위치가 호출 줄이다.

### F-003-AC2 (대상: F-003-R1, R5)
- Given 인터페이스 `OrderService`, 구현 `OrderServiceImpl`, 인터페이스 타입 필드로 호출하는 컨트롤러
- When 분석하면
- Then 호출은 인터페이스 메서드로 `callType=interface`이고, 구현 메서드에서 인터페이스 메서드로 `OVERRIDES`가 있다.

### F-003-AC3 (대상: F-003-R1, R5)
- Given `BaseService<T>.save(T)`와 이를 재정의한 `OrderService extends BaseService<Order>`, 재정의하지 않은 상위 메서드를 하위 타입 변수로 부르는 호출
- When 분석하면
- Then 상위 메서드 호출의 대상은 `BaseService#save(java.lang.Object)` 선언이고, `OrderService#save(Order)`에서 그 선언으로 `OVERRIDES`가 있다.

### F-003-AC4 (대상: F-003-R1)
- Given static import한 메서드 호출, 클래스 이름으로 부르는 static 호출, `super.method()`, 인터페이스 default 메서드 호출, 가변 인자 호출, `a.b().c()` 중첩 호출
- When 분석하면
- Then 각각 `callType`이 `static`, `static`, `super`, `interface`, `virtual`, `virtual`이고 중첩 호출은 `b`와 `c` 둘 다 엣지가 있다.

### F-003-AC5 (대상: F-003-R2)
- Given `new Order(1)`, `this(...)`, `super(...)`, 생성자를 선언하지 않은 클래스의 `new`
- When 분석하면
- Then 생성자로의 `CALLS`(`callType=constructor`)와 `INSTANTIATES`가 있고, 선언 없는 기본 생성자는 `synthetic=true` 노드다.

### F-003-AC6 (대상: F-003-R3)
- Given 람다 안의 호출, `this::handle`·`Order::getId`·`Order::new` 메서드 참조, 익명 클래스 메서드 안의 호출, 멤버 내부 클래스 메서드의 바깥 메서드 호출
- When 분석하면
- Then 람다 호출은 감싼 메서드에서 `viaLambda=true`로, 메서드 참조는 `callType=methodRef`로, 익명 클래스 안 호출은 `Outer$1#run()`에서 나간다.

### F-003-AC7 (대상: F-003-R4)
- Given 인스턴스 필드 초기화식의 호출이 있는 클래스 셋(생성자 없음, 하나, 둘), static 필드 초기화식과 static 블록, 본문이 있는 enum 상수
- When 분석하면
- Then 소유자가 각각 기본 생성자(`synthetic`), 그 생성자, `<instance-init>()`, `<clinit>()`이고, enum 상수 본문의 메서드는 `Enum$1`의 메서드 노드이며 enum 생성자 호출은 `<clinit>()`에서 나간다.

### F-003-AC8 (대상: F-003-R6)
- Given 필드를 읽고, 대입하고, `count++` 하는 메서드와 다른 타입의 상수·enum 상수 참조
- When 분석하면
- Then 각각 `ACCESSES`가 `access=read` 또는 `write`로 있고 JDK 타입의 필드로는 없다.

### F-003-AC9 (대상: F-003-R7)
- Given classpath에 있는 라이브러리 메서드 호출과 JDK 메서드 호출
- When 기본 옵션, `java.externalCalls=none`, `all`로 분석하면
- Then 기본은 라이브러리 호출만 external 메서드 노드로 있고 JDK 호출 수가 `INFO` 진단 1건에 있으며, `none`은 둘 다 없고, `all`은 둘 다 있다.

### F-003-AC10 (대상: F-003-R8)
- Given jar가 없는 타입의 필드로 부르는 호출(`sqlSession.selectList(..)`), 그 결과에 이어 부르는 호출, import한 미해석 타입의 static 호출, 프로젝트 타입에 없는 메서드 호출
- When 분석하면
- Then 첫째와 셋째는 `<FQN>#<이름>(?)` external 호출이고, 둘째와 넷째는 `unresolved:java-call:...`로 가는 `UNRESOLVED` 엣지와 진단이다. 같은 파일의 프로젝트 내부 호출은 `RESOLVED`다.
- (2026-10-11 고쳐 씀) classpath에 없는 상위 타입이 여럿일 때의 기준은 F-003-R25·AC43으로 바뀌었다: 상위 클래스 쪽으로 `INFERRED` + `ambiguous=true`다(전에는 미해결).

### F-003-AC11 (대상: F-003-R9)
- Given 인자 수가 맞지 않는 생성자 호출과 메서드 호출
- When 분석하면
- Then 가장 가까운 후보로 가는 `CALLS`는 없고 미해결 엣지가 있다.

### F-003-AC12 (대상: F-003-R5)
- Given classpath에 있는 라이브러리 인터페이스를 구현한 클래스와, classpath에 없는 인터페이스를 `@Override`로 구현한 클래스
- When 분석하면
- Then 앞의 것은 external 메서드로 `OVERRIDES`가 있고, 뒤의 것은 `<FQN>#<이름>(?)`로 `INFERRED` `OVERRIDES`가 있다.

### F-003-AC13 (대상: F-003-R10)
- Given `HttpServletRequest.getParameter(..)` 호출, `new javax.servlet.http.HttpSessionWrapper()`, `javax.sql.DataSource.getConnection()` 호출, `new javax.naming.InitialContext()` (라이브러리 스텁이 classpath에 있을 때와 없을 때)
- When 기본 옵션으로 분석하면
- Then `javax.servlet` 쪽은 external 메서드로의 `CALLS`와 `INSTANTIATES`가 있고(jar가 없으면 `<FQN>#<이름>(?)`), `javax.sql`·`javax.naming` 쪽은 없으며 건너뛴 JDK 호출 수에 들어간다.

### F-003-AC14 (대상: F-003-R11)
- Given `@Data`·`@Getter`·`@Setter`·`@NoArgsConstructor`·`@AllArgsConstructor` 클래스와, 그 setter·getter·`isXxx()`·생성자를 부르는 코드, 하위 타입 변수로 부르는 상위 타입의 getter, 수신자 없이 또는 `this.`로 부르는 자기 getter, `super(..)`·`super.getX()`, `val` 변수, 소스에 직접 선언한 빌더 클래스 (lombok이 classpath에 없을 때, 스텁이 있을 때, 실제 jar가 있을 때)
- When 분석하면
- Then 세 경우 모두 합성 노드로 `CALLS`가 `RESOLVED`, `lombok=true`, 맞는 `callType`·`receiverType`으로 있고 합성 생성자 호출에는 `INSTANTIATES`가 있다. 소스에 선언한 메서드 호출에는 `lombok`이 없고, 합성 멤버에도 없는 호출(`setNothing`, 인자 수가 다른 `new`)은 미해결이다.

### F-003-AC15 (대상: F-003-R11)
- Given `Order.builder().id(1L).name("n").item(x).build()`, `o.getCustomer().getAddress().getCity()`, 합성 getter 뒤의 선언 메서드 `o.getCustomer().validate()`, `var c = o.getCustomer(); c.getAddress()`, `o.toBuilder()...build().getName()`, `@SuperBuilder` 하위 타입의 빌더로 부르는 상위 빌더 메서드, 없는 getter에서 끊기는 체인
- When 분석하면
- Then 체인의 링크마다 그 메서드로 `CALLS`가 있고(상위 빌더 메서드에는 `receiverType`), 호출한 메서드를 선언한 타입으로 `DEPENDS_ON`이 있으며, 끊긴 링크부터는 링크마다 미해결이다.

### F-003-AC16 (대상: F-003-R11, R13)
- Given `orders.stream().map(Order::getId).collect(..)`, 람다 안의 `o.setName("x")`·`o.getCustomer()`, `Order::new`
- When 분석하면
- Then 메서드 참조는 `callType=methodRef`, 람다 안 호출은 `viaLambda=true`로 합성 멤버에 이어지고, 메서드 참조 때문에 인자가 맞지 않게 된 `map`과 그 뒤의 `collect`는 JDK 호출로 세며 미해결이 없다.

### F-003-AC17 (대상: F-003-R11)
- Given 합성 getter의 결과에 이어 부르는 오버로드 `rename(String)`/`rename(Integer)`: 인자 타입을 알 수 없는 호출과 문자열 리터럴을 넘기는 호출
- When 분석하면
- Then 앞의 것은 두 후보 모두에 `INFERRED` + `ambiguous=true`로, 뒤의 것은 `rename(String)` 하나에 `INFERRED`로 있다.

### F-003-AC18 (대상: F-003-R11)
- Given `@Slf4j` 클래스의 `log.info(..)`(익명 클래스 안 포함), `@Log` 클래스의 `log.info(..)`, 로그 애너테이션이 없는 클래스의 `log.info(..)`
- When 분석하면
- Then 첫째는 `org.slf4j.Logger#info(?)` external 호출과 합성 필드 `log`로의 `ACCESSES`(read)가 있고, 둘째는 JDK 호출이라 `CALLS` 없이 `ACCESSES`만 있으며, 셋째는 미해결이다.

### F-003-AC19 (대상: F-003-R12)
- Given jar 없는 타입으로 시작하는 `new StepBuilder(..).chunk(10).reader(reader()).writer(w).build()`, `registry.addInterceptor(..).addPathPatterns(..).excludePathPatterns(..)`, external 호출의 결과를 받은 `var` 변수로 부르는 호출
- When 분석하면
- Then 첫 링크까지는 external 호출이고 인자 안의 `reader()`는 `RESOLVED`이며, 뒤따르는 링크들은 체인당 `externalChain=true`인 미해결 엣지 1건(위치는 체인 식 전체)과 `INFO` 진단 1건이다. `WARN` 진단은 없다.

### F-003-AC20 (대상: F-003-R13)
- Given classpath에 있는 라이브러리의 `util(String)`을 인자 둘로 부른 호출과, 인자 수가 맞지 않는 `StringBuilder.append(..)` 호출
- When 분석하면
- Then 앞의 것은 `lib.util.LibBase#util(?)`(`signatureUnknown=true`)로의 호출이고 `util(java.lang.String)`으로는 잇지 않으며, 뒤의 것은 JDK 호출로 센다. 미해결 진단은 없다.

### F-003-AC21 (대상: F-003-R14)
- Given `o.getName().trim().toUpperCase()`, `o.getItems().stream().map(..).sorted().toList().size()`, `o.getItems().get(0).hashCode()`, 그리고 `find(o.getId()).orElseThrow(() -> { .. })`의 람다 본문에서 매개변수·바깥 지역 변수·필드·람다 안 지역 변수로 부르는 합성·선언 메서드, `new Order(..)`, `Order.builder()`, `new ArrayList<>()`, `new IllegalStateException(..)`
- When 분석하면
- Then JDK 체인은 미해결 없이 JDK 호출로 세고, 원소 타입이 필요한 `get(0).hashCode()`만 체인당 1건으로 남으며, 람다 본문의 호출은 `viaLambda=true`로 이어지고(오버로드는 추적한 인자 타입으로 줄여 `INFERRED`) `WARN` 진단과 미해결 `DEPENDS_ON`이 없다.

### F-003-AC22 (대상: F-003-R11, R4)
- Given `@RequiredArgsConstructor` enum의 인자 있는 상수
- When 분석하면
- Then 상수마다 `<clinit>()`에서 합성 생성자로 `CALLS`가 있고 미해결 진단이 없다.

### F-003-AC23 (대상: F-003-R15, R16)
- Given jar가 없는 타입의 `new SqlSessionTemplate(factory, ExecutorType.BATCH)`·`new JobBuilder("j", jobRepository)`(인자에 미해석 타입의 값), 그 타입을 상속한 클래스의 `super(factory, ExecutorType.BATCH)`, 대조군 `new ModelAndView("main")`, 그리고 타입 인자만 classpath에 없는 프로젝트 타입의 `new Page<Model>(request, 20)`·`super(request, 10)`
- When 기본 옵션, `java.externalCalls=none`, 인자 수가 맞지 않는 스텁이 classpath에 있을 때로 분석하면
- Then 기본은 넷 다 `<FQN>#<init>(?)`로 `CALLS`(`callType=constructor`, `RESOLVED`)가 있고 `new`에는 `INSTANTIATES`도 있으며 `EXTERNAL_CALLS_SKIPPED`(JDK 호출로 잘못 센 것)가 없다. `none`은 `CALLS` 없이 라이브러리 호출 4건으로 센다. 스텁이 있으면 인자 수가 맞지 않는 생성은 `(?)`로, 맞는 생성은 해석된 생성자로 잇는다. `Page`의 생성은 해석된 프로젝트 생성자로 잇는다. 샘플의 `AppConfig.java:23`, `DailySalesJobConfig.java:51`·`59`·`69`도 같다.

### F-003-AC24 (대상: F-003-R15, R9)
- Given 미해석 타입의 값을 인자로 넘기면서 인자 수가 선언과 맞지 않는 `b.m(m, 1)`, `super.m(m, 2)`, `own(m, 3)`, `Base.s(m, 4)`, `new Base(m, 5)`, `super(m, 1)`, `this(m, x, 3)`와 인자 수가 맞는 `own(m)`
- When 분석하면
- Then 인자 수가 맞는 호출만 해석된 대상으로 이어지고 나머지는 `unresolved:java-call:b.m(…)` 등 미해결 엣지이며 `new Base(…)`에는 `INSTANTIATES`가 없다.

### F-003-AC25 (대상: F-003-R15, R16, R9)
- Given 생성자 `Color(int)`·`Color()`가 있는 enum의 상수 `RED(1)`, `GREEN(1, 2)`, `BLUE`, `BLACK(Missing.X, 1)`과 생성자를 선언하지 않은 enum의 상수 `A(1)`, `B`, `C(Missing.X, 2)`
- When 분석하면
- Then `RED`·`BLUE`만 선언한 생성자로 `CALLS`가 있고, 맞는 생성자가 없는 인자 있는 상수는 `unresolved:java-call:GREEN(…)` 등 미해결 엣지(`argCount`)이며, `java.lang.Enum`의 생성자 호출로 세지 않는다. 인자 없는 상수 `B`에는 아무것도 만들지 않는다.

### F-003-AC26 (대상: F-003-R16, R12)
- Given 수신자가 소스 없는 타입의 반환값인 안쪽 링크와, 그 결과에 이어 부르지만 체인 미해결이 아니라 external 호출(F-003-R13)로 남는 바깥 링크
- When 미해석 호출을 해석하면
- Then 바깥 링크의 external `CALLS`와 함께 안쪽 링크의 체인 미해결 엣지(`externalChain=true`)가 남는다.

### F-003-AC27 (대상: F-003-R8, R12)
- Given 같은 수신자·이름에 인자 수만 다른 미해결 호출 `vo.set("a")`·`vo.set("a", "b", "c")`, `new Vo(1, 2)`, 메서드 참조 `vo::nothing`, external 체인 `registry.add(null).order(1, 2)`
- When 분석하면
- Then 원문은 `vo.set(…)`, `new Vo(…)`, `vo::nothing`, `registry.add(null).order(…)`이고(끝이 `(<숫자>)`인 원문은 없다), `vo.set(…)` 노드 하나로 가는 엣지 둘에 `argCount`가 1과 3으로 위치와 함께 남으며, 메서드 참조의 엣지에는 `argCount`가 없다.

### F-003-AC28 (대상: F-003-R5)
- Given `C extends B implements I`(B와 I에 모두 `m()`, B는 I를 구현하지 않음)의 `C#m()`, `only()`를 B에서 물려받아 I를 충족하는 `D`, `E extends B implements J, K`(J는 I를 확장하고 `m()`을 다시 선언)의 `E#m()`, `Q extends P`(P가 I를 구현)의 `Q#m()`
- When 분석하면
- Then `C#m()`·`D#m()`에서 `B#m()`과 `I#m()` 둘로, `E#m()`에서 `B#m()`·`J#m()`·`K#m()`으로(`I#m()`은 아님), `Q#m()`에서 `P#m()` 하나로 `OVERRIDES`가 있고, `D#only()` 노드와 그 엣지는 없다.

### F-003-AC29 (대상: F-003-R17)
- Given `interface Svc { String list(String q, PaginationInfo p); String one(String q); }`(`ext.lib.PaginationInfo`는 classpath에 없음), `class SvcImpl2 implements Svc`, 미해석 외부 클래스도 상속한 `class SvcImpl extends AbstractBase implements Svc`(`@Override`), 그리고 제네릭 상위 타입(`Base<T>`의 `find(T, P)`를 `Impl implements Base<String>`이 `find(String, P)`로 구현), 타입 인자만 미해석인 매개변수(`Map<String, P>`), 해석된 자리의 타입·배열 차원·매개변수 수가 다른 오버로드, 상위의 static·private 메서드, 상위 클래스와 인터페이스 양쪽에 선언이 있는 클래스와 그 하위 클래스
- When 분석하면
- Then `SvcImpl2#list`·`SvcImpl#list`에서 `Svc#list`로 `OVERRIDES`가 `RESOLVED`로 있고 `ext.lib.AbstractBase#list(?)` 노드와 그 엣지는 없으며, 제네릭 상위는 타입 인자를 넣어 비교해 이어지고, 타입·차원·수가 다르거나 static·private인 것으로는 잇지 않으며, 양쪽 가지의 가장 가까운 선언으로만 잇는다.

### F-003-AC30 (대상: F-003-R17)
- Given 와일드카드 import라 FQN을 복원할 수 없는 `run(Q q)` 인터페이스와 `run(ext.lib.Q q)` 구현, 단순 이름은 같지만 import한 FQN이 다른(`ext.lib.P` / `other.P`) 인터페이스와 클래스, 프로젝트 상위에 후보가 없고 classpath에 없는 인터페이스를 `@Override`로 구현한 메서드
- When 분석하면
- Then 첫째는 `INFERRED` `OVERRIDES`, 둘째는 `OVERRIDES` 없음, 셋째는 지금처럼 `<FQN>#<이름>(?)`로 `INFERRED` `OVERRIDES`다.

### F-003-AC31 (대상: F-003-R18)
- Given classpath에 없는 상위 타입이 둘이라 어느 것의 메서드인지 모르는 `@Override` 메서드 `run(String, int)`와 `stop()`
- When 분석하면
- Then `unresolved:java-override:run(…)`·`stop(…)`로 가는 `UNRESOLVED` `OVERRIDES`에 `argCount`가 2와 0으로 있고, 끝이 `(<숫자>)`인 원문은 없다.

### F-003-AC32 (대상: F-003-R19)
- Given `class StringUtils extends org.apache.commons.lang3.StringUtils { static String own(String) }`(프로젝트, 상위는 classpath에 없음)와 `StringUtils.isBlank(x)`, `StringUtils.own(x)`, 인자 수가 다른 `StringUtils.own(x, 1)`, 외부 클래스와 외부 인터페이스를 함께 가진 타입의 static 호출, 외부 인터페이스만 구현한 타입의 static 호출
- When 분석하면
- Then `isBlank`는 `org.apache.commons.lang3.StringUtils#isBlank(?)`로 `INFERRED` `CALLS`(`callType=static`, `inheritedFrom`)이고 미해결이 없으며, `own(x)`는 프로젝트 메서드로, `own(x, 1)`은 외부 상위 클래스의 `own(?)`로 이어지고, 인터페이스가 함께 있어도 상위 클래스의 것으로(`ambiguous` 없이) 잇고, 상위 클래스가 없으면 미해결이다.

### F-003-AC33 (대상: F-003-R19)
- Given classpath에 없는 `BaseDao`를 상속한 `Dao`와 그 하위 `SubDao`의 변수로 부르는 `save(..)`, 외부 클래스와 외부 인터페이스를 함께 가진 타입의 변수로 부르는 호출, 외부 인터페이스만 둘인 타입의 호출, 그리고 상위 클래스가 classpath에 있는 타입의 없는 메서드 호출
- When 분석하면
- Then 앞의 둘은 `org.ext.BaseDao#save(?)`로 `INFERRED`(`callType=virtual`, `inheritedFrom`, `receiverType`은 각각 `Dao`·`SubDao`), 셋째는 상위 클래스 쪽으로 `INFERRED` + `ambiguous=true`, 넷째는 미해결이고, classpath에 있는 상위 타입에 없는 메서드는 추론하지 않고 미해결로 둔다.

### F-003-AC34 (대상: F-003-R20)
- Given `abstract class BaseServiceImpl<M, R, E> { M mapper; .. }`의 `mapper.getClass().getDeclaredMethod("selectByExample", ..)` + `method.invoke(mapper, ..)`(지역 변수 경유), `this.mapper.getClass().getMethod("insert", ..).invoke(this.mapper, ..)`(한 식), 그 클래스를 `<UserMapper, ..>`·`<RoleMapper, ..>`·`<org.ext.ExtMapper, ..>`로 상속한 클래스들과 그 하위 클래스, 중간 제네릭 클래스를 거친 상속, 선언 타입이 프로젝트 타입인 필드로 같은 꼴을 쓴 메서드
- When 분석하면
- Then 리플렉션 호출이 든 상위 메서드에서 `UserMapper`·`RoleMapper`의 이름·인자 수가 맞는 메서드(상위 인터페이스에 선언한 것 포함)로 `CALLS`가 `INFERRED`, `callType=reflection`, `receiverType`=그 매퍼 타입, `viaTypes`=그 매퍼로 고정한 하위 타입들로 있고, 인자 수가 다른 오버로드와 외부 타입 인자로는 없다. 하위 타입 변수로 그 상위 메서드를 부른 호출 엣지에는 `receiverType`이 있다. 프로젝트 타입 필드의 것은 그 타입의 메서드로 바로 이어진다(`viaTypes` 없음).

### F-003-AC35 (대상: F-003-R20)
- Given 메서드 이름이 상수가 아닌 `getDeclaredMethod(name)`, 찾은 객체와 다른 객체에 `invoke`하는 코드, 꼴은 맞지만 타입 인자를 프로젝트 타입으로 고정한 하위 클래스가 없는 경우
- When 분석하면
- Then 그 메서드들에서 나가는 `CALLS`가 없고 메서드마다 `INFO` 진단 `REFLECTION_CALL`이 1건씩 있다.

### F-003-AC36 (대상: F-003-R21)
- Given `Base#thymeleaf(String)`·`go(String, Model)`·`both(Object)`/`both(CharSequence)`·`exact(Object)`/`exact(String)`(`Model`은 classpath에 없음)과, 하위 클래스의 `thymeleaf(Model)` 안에서 부른 `thymeleaf("/index")`, `exact(Model)` 안의 `exact("x")`, `only(Model)` 안의 `only("none")`, `both(Model)` 안의 `both("many")`, `go(Model, Model)` 안의 `go("/x", b)`, 그리고 `jump(Task, String)` 안에서 미해석 타입 `Act`의 값을 넘긴 `jump(task, act)`(같은 클래스에 `jump(Task, Act)`)
- When 분석하면
- Then `thymeleaf("/index")`는 자기 자신이 아니라 `Base#thymeleaf(String)`으로 `RESOLVED`이고, `exact("x")`는 인자 타입과 정확히 같은 `exact(String)`으로, `only`·`both`는 `unresolved:java-call:only(…)`·`both(…)`(`argCount=1`)로, `go`와 `jump`는 미해석 타입이 섞인 유일한 후보(`Base#go(String, Model)`, `jump(Task, Act)`)로 `INFERRED`다.

### F-003-AC37 (대상: F-003-R21)
- Given 미해석 타입 매개변수의 메서드에 그 미해석 타입의 값, 그 타입을 구현한 프로젝트 타입의 값, import한 미해석 타입과 이름이 같아 컴파일러가 `java.lang` 타입으로 풀어 버린 변수(`import org.ext.model.Process`)를 넘긴 호출과, 하위 타입 변수로 부른 상위 오버로드 `c.thymeleaf("/index")`
- When 분석하면
- Then 앞의 셋은 컴파일러가 고른 선언 그대로(재귀 호출은 자기 자신으로 `RESOLVED`)이고, 마지막은 `Base#thymeleaf(String)`으로 `receiverType`과 함께 이어진다.

### F-003-AC38 (대상: F-003-R22)
- Given `interface HasId { Long getId(); void setId(Long id); String label(); static String getKind() }`, 제네릭 `Identifiable<K, V extends Number>`(`getKey`, `setKey(K)`, `setRank(V)`), 추상 클래스의 `getName()`·`setName(Integer)`를 상위로 둔 `@Getter @Setter class Order`(lombok이 classpath에 없을 때, 스텁이 있을 때, 실제 jar가 있을 때)
- When 분석하면
- Then 합성 `getId()`·`setId(Long)`·`getName()`·`getKey()`·`setKey(String)`·`setRank(Integer)`에서 상위 선언으로 `OVERRIDES`가 `RESOLVED`, `lombok=true`, 위치는 그 필드로 있고(제네릭 상위는 타입 인자를 넣어 비교), 매개변수 타입이 다른 `setName(String)`과 static `getKind()`에는 없으며, 소스에 선언한 `label()`의 엣지에는 `lombok`이 없다.

### F-003-AC39 (대상: F-003-R22)
- Given `@Data class Base`(private getter 하나 포함), 그 하위의 하위 `Sub`가 선언한 `getName()`·`toString()`·매개변수 타입이 다른 `setCount(long)`·`getSecret()`, `Sub`를 상속하고 `@Getter` 필드 `name`을 가진 `Leaf`
- When 분석하면
- Then `Sub#getName()`·`Sub#toString()`에서 `Base`의 합성 메서드로 `OVERRIDES`(`lombok=true`, 위치는 메서드 이름)가 있고, `setCount(long)`과 private 합성 getter에 대한 `getSecret()`에는 없으며, `Leaf`의 합성 `getName()`은 가장 가까운 선언 `Sub#getName()`으로만 잇는다.

### F-003-AC40 (대상: F-003-R23)
- Given `import org.activiti.bpmn.model.Process;`(jar 없음)인 파일의 `process.getId()`, `new Process()`, `created.destroy()`(`java.lang.Process`에 `destroy()`가 있다), 그리고 같은 패키지에 `abbr`을 선언한 프로젝트 `StringUtils`가 있는데 commons `StringUtils`를 import한 파일의 `StringUtils.join(..)`·`StringUtils.abbr(..)`
- When 분석하면
- Then 호출은 `org.activiti.bpmn.model.Process#getId(?)`·`#<init>(?)`·`#destroy(?)`와 `org.apache.commons.lang3.StringUtils#join(?)`·`#abbr(?)`로 가고 `INSTANTIATES`도 import한 타입이다. import 없이 같은 패키지에서 부른 `StringUtils.abbr`은 프로젝트 메서드다.

### F-003-AC41 (대상: F-003-R24)
- Given 상위 클래스의 `addMessage(Model, String...)`·`addMessage(RedirectAttributes, String...)`(두 타입 모두 jar 없음), `parseFileInf(Map<String, MultipartFile>, String)`·`parseFileInf(List<MultipartFile>, String)`와, `RedirectAttributes`·`List<MultipartFile>` 매개변수를 넘겨 부르는 하위 클래스(가변 인자에 값이 여럿·없는 호출, 후보가 하나뿐인 호출 포함)
- When 분석하면
- Then `addMessage(redirectAttributes, ..)`는 `(RedirectAttributes, String[])`로만 `INFERRED`로 가고 `ambiguous`가 없다. `parseFileInf(files, ..)`는 `(List, String)`이다. `addMessage(model)`은 `(Model, String[])`이고 후보가 하나뿐인 호출은 전과 같다.

### F-003-AC42 (대상: F-003-R24)
- Given 미해석 타입의 메서드 결과(`thing.attributes()`, `thing.stream()`)를 넘긴 `log(..)`(후보 `log(Model)`, `log(RedirectAttributes)`)와 `new Builder(.., "x")`(후보 `(Reader, String)`, `(InputStream, String)`). 그리고 `getBean(String)`·`getBean(Class<T>)`가 있는 타입에 `Holder.getBean(JedisPool.class)`(`JedisPool` jar 없음)
- When 분석하면
- Then `log`와 생성자 호출은 후보 둘 모두로 `INFERRED` + `ambiguous=true`이고 `INSTANTIATES`는 하나다. `getBean(JedisPool.class)`는 `getBean(java.lang.Class)`로 `INFERRED`이고 미해결이 없다.

### F-003-AC43 (대상: F-003-R25)
- Given classpath에 없는 `AbstractSessionDAO`를 상속하고, classpath에 없는 인터페이스를 상속한 프로젝트 인터페이스를 구현한 클래스의 `this.generateSessionId(..)`와 `assignSessionId(..)`
- When 분석하면
- Then 둘 다 `org.apache.shiro.session.mgt.eis.AbstractSessionDAO#<이름>(?)`로 `INFERRED` + `ambiguous=true`(`inheritedFrom`)다. (F-003-AC10의 "상위 타입이 여럿이면 미해결"은 이 기준으로 바뀌었다.)

### F-003-AC44 (대상: F-003-R26)
- Given 상위 클래스가 `protected Logger logger`(`org.slf4j.Logger` import, jar 없음)를 선언하고 import가 없는 하위 클래스가 `logger.debug(..)`·`this.logger.info(..)`를 부르는 프로젝트
- When 분석하면
- Then 호출이 `org.slf4j.Logger#debug(?)`·`#info(?)`로 간다.

### F-003-AC45 (대상: F-003-R26)
- Given classpath에 없는 상위 클래스를 상속한 클래스의 `egovLogger.debug(..)`(`egovLogger`는 어디에도 선언이 없다)
- When 분석하면
- Then 미해결 `CALLS`에 `externalChain=true`가 있고 진단은 `INFO` 1건이다.

### F-003-AC46 (대상: F-003-R27)
- Given 타입이 classpath에 없는 `context` 필드의 `context.getBean(Helper.class).help()`, `context.getBean("helper", Helper.class).help()`, `context.getBean(name()).toString()`
- When 분석하면
- Then 앞의 둘은 `com.a.Helper#help()`로 `INFERRED`이고, 타입을 알 수 없는 셋째는 전처럼 체인 미해결이다.

### F-003-AC47 (대상: F-003-R28)
- Given `enc(String)`, `enc(String, String)`, `enc(String, byte[])`와, `@Getter` 클래스의 `String`·`byte[]` 필드 getter를 인자로 넘긴 `Scrty.enc(vo.getPassword(), vo.getId())`·`enc("", vo.getId())`·`enc("", vo.getSalt())`. 그리고 호출하는 파일이 Lombok 타입의 파일보다 먼저 분석되는 배치
- When 분석하면
- Then 앞의 둘은 `(String, String)`으로만, 셋째는 `(String, byte[])`로만 가고 `ambiguous`가 없다. 파일 순서와 상관없다.

### F-003-AC48 (대상: F-003-R28)
- Given `put(String, Object)`·`put(byte[], Object)`와, 안쪽 인자의 타입을 모르는 프로젝트 메서드 호출을 넘긴 `put(StrUtil.ser(session.attr()), ..)`(`ser`의 오버로드가 모두 `String`을 돌려준다), `put(StrUtil.bytes(..), ..)`(`byte[]`), `put(StrUtil.mixed(..), ..)`(오버로드마다 `String`·`byte[]`)
- When 분석하면
- Then 첫째는 `put(String, Object)`, 둘째는 `put(byte[], Object)`로만 가고, 셋째는 두 후보 모두로 `INFERRED` + `ambiguous=true`다.

### F-003-AC49 (대상: F-003-R28)
- Given `get(String)`·`get(byte[])`, `set(String, String, int)`·`set(byte[], byte[], int)`와 `get(PREFIX + "_" + session.getId())`, `set(PREFIX + "_" + session.getId(), StrUtil.ser(session), (int) session.getTimeout() / 1000)`(`session`의 타입은 classpath에 없다)
- When 분석하면
- Then 각각 `get(String)`, `set(String, String, int)`으로만 가고 `ambiguous`가 없다.

### F-003-AC50 (대상: F-003-R28)
- Given `ImportExcel(File, int, int)`·`ImportExcel(MultipartFile, int, int)`에 `MultipartFile` 매개변수를 넘긴 `new ImportExcel(file, 1, 0)`, `extract(Set<? extends ConstraintViolation>, String)`·`extract(ConstraintViolationException, String)`에 catch 변수 `ConstraintViolationException ex`·`Set<? extends ConstraintViolation>` 매개변수·`e.getConstraintViolations()`를 넘긴 호출(모두 jar 없음)
- When 분석하면
- Then 앞의 셋은 인자의 타입 표기와 같은 후보 하나로만 가고(`(MultipartFile, int, int)`, `(ConstraintViolationException, String)`, `(Set, String)`), 타입을 알 수 없는 `e.getConstraintViolations()`는 전처럼 두 후보 모두로 `ambiguous`다.

### F-003-AC51 (대상: F-003-R28)
- Given `put(String, Object)`·`put(byte[], Object)`에 `flag ? "a" : PREFIX + session.getId()`, `new byte[session.size()]`, `(String) session.attr()`, `null`, `session.attr()`를 넘긴 호출
- When 분석하면
- Then 앞의 셋은 각각 `String`·`byte[]`·`String` 후보 하나로 가고, `null`과 알 수 없는 값은 두 후보 모두로 `INFERRED` + `ambiguous=true`다.

### F-003-AC52 (대상: F-003-R29)
- Given classpath에 없는 타입을 상속한 클래스 안의 static 중첩 클래스가 classpath에 없는 `HttpServletRequestWrapper`를 상속하고 `getContextPath()`·`this.getScheme()`을 부르는 프로젝트. 그리고 오버로드를 가리지 못한 호출과 상위 타입이 여럿인 상속 호출이 섞인 프로젝트
- When 분석하면
- Then 상속 호출은 `…HttpServletRequestWrapper#<이름>(?)`로 `INFERRED` + `inheritedFrom`이고 `ambiguous`가 없다. 그래프의 `ambiguous=true` 엣지는 같은 (출발, 위치)에 둘 이상 있다.

## 5. 가정
- 컴파일러가 고른 선언을 믿지 않는 경우(F-003-R21)의 세부
  - 발생 조건: 컴파일러(JDT)는 classpath에 없는 매개변수 타입에 무엇이든 맞는다고 보고, classpath에 없는 타입의 값도 어떤 매개변수에든 맞는다고 본다. 그래서 하위 클래스의 `thymeleaf(Model)` 안에서 상위의 `thymeleaf(String)`을 부른 `thymeleaf("/index")`가 자기 자신으로, `new Page(1, 1, -1)`이 `Page(HttpServletRequest, HttpServletResponse, int)`로 해석된다.
  - "그 타입일 수 없는 인자"는 해석된 타입이고 그 계층(상위 클래스·인터페이스 전부)에 classpath에 없는 타입이 없는 것이다(원시 타입 포함). 미해석 타입을 상속·구현한 프로젝트 타입, `null`, 타입 변수, 타입을 모르는 식은 그 타입일 수 있으므로 컴파일러의 선택을 그대로 믿는다. 가변 인자 자리는 보지 않는다.
  - 이 파일이 같은 단순 이름을 다른 FQN으로 단일 import했으면 그 인자의 바인딩은 믿지 않는다. import한 타입이 classpath에 없을 때 컴파일러가 그 이름을 `java.lang`의 타입으로 풀어 버리기 때문이다(`import org.activiti.bpmn.model.Process` → `java.lang.Process`).
  - 후보는 수신자의 타입(수신자가 없으면 컴파일러가 고른 선언을 물려받은 가장 안쪽의 감싼 타입, `super.m()`은 상위 클래스)과 그 해석된 상위 타입에서 모으고, 같은 매개변수의 선언은 가까운 쪽만 남긴다. 다른 타입의 private 메서드는 뺀다. 타입 이름으로 부른 호출은 static 메서드만 본다. 수신자의 타입이 타입 변수이거나 static import한 메서드면 찾지 않는다(미해결).
  - 후보가 인자를 받는지는 자리마다 본다: 양쪽 다 해석된 자리는 대입 가능 여부(박싱·확장 포함, 제네릭은 erasure), 양쪽 다 미해석인 자리는 배열 차원과 표기(F-003-R17과 같은 표기 비교), 한쪽만 미해석인 자리는 분명히 맞지 않는 것(위의 두 경우)만 뺀다. 맞는 후보가 여럿이면 해석된 자리의 타입이 전부 정확히 같은 후보가 하나일 때만 그것을 쓴다.
  - 다른 후보를 찾지 못하면 인자 불일치(F-003-R9)와 같게 다룬다: Lombok 합성 멤버에 맞는 것이 있으면 그쪽으로(F-003-R11), 후보를 선언한 타입이 소스 없는 타입이면 `<FQN>#<이름>(?)`로(F-003-R13), 아니면 미해결이다.
  - 생성자(`new`, `this(..)`, `super(..)`)에도 같은 판정을 한다. enum 상수의 생성자는 다른 후보를 찾지 않는다(미해결).
- Lombok 합성 메서드가 낀 재정의(F-003-R22)의 세부
  - 둘 다 소스에 선언한 메서드면 이 판정은 엣지를 만들지 않는다(컴파일러의 판정 F-003-R5와 표기 비교 F-003-R17에 맡긴다). 가장 가까운 선언이 소스에 선언한 것이면 그 위의 합성 메서드로는 잇지 않는다.
  - 매개변수 타입은 메서드 ID의 표기(erasure의 바이너리 이름, classpath에 없는 타입은 프로젝트 전체에서 정한 이름)로 비교한다. 상위 타입이 제네릭이고 후보의 매개변수가 타입 매개변수 그 자체(`K`)면, 하위 타입이 상위 타입에 넘긴 타입 인자(`EXTENDS`·`IMPLEMENTS`의 `attrs.typeArguments`, F-002-R15)를 넣어 비교한다. `List<K>`처럼 타입 인자 안에 든 타입 매개변수는 erasure(`java.util.List`)로 비교한다.
  - 엣지의 위치는 출발 메서드가 합성이면 그 노드의 위치(필드나 애너테이션), 선언한 메서드면 메서드 이름이다. `@SuperBuilder` 빌더 타입끼리의 `build()` 같은 합성-합성 재정의도 잇는다. static `builder()`는 재정의가 아니라 잇지 않는다.
  - 컴파일러가 합성 메서드를 보지 못해 이미 만든 엣지는 그대로 둔다: 합성 메서드 위의 선언으로 간 `OVERRIDES`(가장 가까운 선언이 아니게 된다. 소비자는 전이적으로 따라가므로 결과는 같다)와, `@Override`인데 해석된 상위에 선언이 없어 classpath에 없는 상위 타입으로 추론한 `OVERRIDES`·`java-override` 미해결.
  - record의 암묵적 접근자가 인터페이스 메서드를 구현한 경우는 보지 않는다(Lombok이 아니다).
- 미해석 호출의 해석(F-003-R11, R14)의 세부
  - 컴파일러가 수신자 타입을 알고도 선언된 멤버에서 찾지 못한 호출은 합성 멤버만 후보다(R9의 "가까운 후보로 잇지 않는다"를 지킨다). 수신자 타입을 직접 추적한 호출(체인의 뒤쪽 링크, `var` 변수, 컴파일러가 보지 않은 코드)은 선언된 메서드도 후보다.
  - 후보는 수신자 타입에서 시작해 프로젝트 안의 상위 타입으로 올라가며 모으고, 매개변수가 같은 선언은 가까운 쪽만 남긴다. 생성자는 그 타입의 것만 본다. 수신자가 타입 이름이면 static 메서드만 본다.
  - 인자 타입으로 후보를 줄일 때는 분명히 맞지 않는 것(문자열 자리에 숫자, 프로젝트 타입 사이의 상속 관계 없음 등)만 뺀다. 전부 빠지면 줄이지 않는다.
  - 메서드 참조는 인자 수를 모르므로 이름만으로 찾는다.
  - 빌더 메서드는 수신자의 타입을 돌려주는 것으로 본다(`@SuperBuilder`의 상위 빌더 메서드를 하위 빌더로 불러도 체인이 이어진다).
  - 추적한 수신자가 프로젝트 타입인데 선언이 없고 이름·인자 수가 `java.lang.Object`의 메서드와 맞으면 JDK 호출로, 소스 없는 상위 타입이 정확히 하나면 그 타입의 `<FQN>#<이름>(?)` 호출(`INFERRED`)로 다룬다.
  - 해석된 호출의 `DEPENDS_ON`(F-002 가정)에 맞춰, 이렇게 찾은 메서드를 선언한 타입으로도 `DEPENDS_ON`을 만든다(이미 `EXTENDS`·`IMPLEMENTS`·`ASSOCIATES`·`DEPENDS_ON`이 있으면 만들지 않는다).
  - 컴파일러가 보지 않은 코드의 타입 이름은 import → 그 파일에 선언한 타입 → 같은 패키지·와일드카드 import한 패키지의 프로젝트 타입(소스 파일 이름 기준) → `java.lang` → 와일드카드 import한 JDK 패키지 순으로 푼다. 그 안의 필드 접근(`ACCESSES`)은 만들지 않는다.
  - 미해결 엣지와 진단은 모든 파일을 본 뒤에 쓴다.
- 한계: 컴파일러가 해석하지 못한 곳에서는 제네릭 타입 인자를 따라가지 않는다. `getItems().get(0).getPrice()`, `findById(..).map(Order::getMember).orElse(null)`의 결과, 해석되지 않은 호출에 넘긴 람다의 타입 없는 매개변수(`x -> x.getPrice()`)에서 추적이 끊긴다. 앞의 둘은 체인당 `INFO` 1건, 마지막은 `WARN` 미해결이다.
- `super(...)`와 `this(...)`의 `callType`은 `constructor`, `super.m()`은 `super`다.
- 익명 클래스 생성은 익명 타입의 생성자로 `CALLS` + 익명 타입으로 `INSTANTIATES`를 만든다. 익명 타입의 생성자에서 상위 클래스 생성자로 가는 `CALLS`는 만들지 않는다.
- 복합 대입(`+=`)과 `++`/`--`의 `access`는 `write`다. 같은 위치의 엣지는 하나만 저장되기 때문이다. 필드 선언 자체의 초기화는 접근으로 치지 않는다.
- `OVERRIDES`의 대상이 JDK 메서드이면 `java.externalCalls=all`일 때만 만든다. `java.lang.Object`의 메서드는 항상 뺀다.
- classpath에 없는 상위 타입이 정확히 하나이고 수신자 없는 호출이 해석되지 않으면 그 상위 타입의 메서드 호출로 본다(예: `SqlSessionDaoSupport.getSqlSession()`). 둘 이상이면 미해결이다.
  - 이렇게 만든 엣지에도 `attrs.inheritedFrom`(그 상위 타입의 FQN)을 남긴다(F-003-R19와 같은 표시).
- 프로젝트 타입을 수신자로 한 호출의 외부 상위 타입 추론(F-003-R19)의 세부
  - 컴파일러는 classpath에 없는 상위 타입의 멤버를 볼 수 없어 "없는 메서드"라고만 한다. 수신자 타입과 해석된 상위 타입에 이름이 같고 인자 수가 맞는 선언(가변 인자 포함)이 하나라도 있으면 인자 타입이 맞지 않은 것으로 보고 F-003-R9대로 미해결로 둔다. Lombok 합성 멤버에 맞는 것이 있으면 그쪽이 먼저다(F-003-R11).
  - classpath에 없는 상위 타입의 FQN은 그 이름을 상위 타입으로 적은 파일의 import로 복원한다(`EXTENDS`·`IMPLEMENTS` 엣지의 대상과 같은 이름). 복원하지 못하면 추론하지 않고 미해결로 둔다.
  - static 메서드는 인터페이스에서 물려받지 않으므로 타입 이름으로 부른 호출은 상위 클래스 사슬 끝의 미해석 클래스만 후보다(인터페이스가 함께 있어도 `ambiguous`를 붙이지 않는다).
  - 상위 타입이 classpath에 있으면(jar가 있음) 컴파일러의 판정을 믿는다: 없는 메서드는 미해결이다.
  - 수신자 타입을 직접 추적한 호출(체인의 뒤쪽 링크, `var` 변수, 컴파일러가 보지 않은 코드)의 기존 추론(소스 없는 상위 타입이 정확히 하나)에도 같은 표시(`inheritedFrom`, 명시적 수신자면 `receiverType`)와 "여럿이면 상위 클래스 쪽으로 `ambiguous`" 규칙을 적용한다. 이 경로는 그래프의 `EXTENDS`·`IMPLEMENTS`로 판단하므로 classpath에 있는 외부 상위 타입도 후보로 센다.
  - **수신자 없는 호출은 기존 가정 그대로다**: classpath에 없는 상위 타입이 둘 이상이면(클래스 + 인터페이스 포함) 미해결이다. 명시적 수신자의 규칙(상위 클래스 쪽으로 `ambiguous`)과 다르다.
- 매개변수 타입이 classpath에 없는 메서드의 재정의(F-003-R17)의 세부
  - 발생 조건: 컴파일러(JDT)는 classpath에 없는 타입이 쓰인 자리마다 서로 다른 "없는 타입"을 만들고 재정의 판정은 매개변수 타입의 동일성으로 한다. 그래서 매개변수 중 하나라도(배열 원소, `Map<String, Missing>`의 타입 인자 포함) classpath에 없는 타입이면 같은 파일·같은 패키지·같은 import여도 `IMethodBinding.overrides`는 항상 실패한다. 그 타입이 반환 타입·`throws`·매개변수 애너테이션에만 있거나, 매개변수 타입이 "미해석 타입을 상속한 프로젝트 타입"이거나, jar가 classpath에 있으면 실패하지 않는다.
  - 비교는 위치별이다. 양쪽 다 해석된 자리는 erasure의 바이너리 이름이 같아야 한다(상위 타입이 제네릭이면 그 타입 인자를 넣은 매개변수 타입으로 비교한다. 타입 인자만 미해석인 `List<Missing>`은 `java.util.List`로 비교한다). 한쪽만 미해석인 자리는 맞지 않는 것으로 본다. 양쪽 다 미해석인 자리는 배열 차원이 같고 표기가 같아야 한다.
  - 표기는 그 이름을 쓴 파일의 import로 복원한 FQN이고(F-002의 복원 규칙과 같다. 후보 쪽은 후보를 선언한 파일의 import를 쓴다), 복원하지 못하면 소스에 쓴 이름이다. 양쪽 FQN이 같거나, 양쪽 다 복원하지 못했고 쓴 이름이 같으면 일치(`RESOLVED`)다. 한쪽만 FQN을 알면 단순 이름이 같을 때 `INFERRED`로 잇는다.
  - 후보는 static·private이 아닌 메서드다. 접근 범위(패키지 전용)와 반환 타입은 보지 않는다. 최상위 타입 이름이 파일 이름과 다른 파일에 선언한 후보는 그 파일의 import를 알 수 없어 전체 이름으로 쓴 것만 FQN으로 본다.
- 리플렉션 호출의 추론(F-003-R20)의 세부
  - 보는 꼴은 `java.lang.reflect.Method#invoke` 하나다. `invoke`의 수신자는 `getDeclaredMethod`/`getMethod` 호출 자체이거나, 그런 호출만 대입된 지역 변수여야 한다(대입이 여럿이면 각각). 메서드 이름은 문자열 상수, 그 호출의 수신자는 `X.getClass()`, `invoke`의 첫 인자는 같은 변수 X(지역 변수, 매개변수, `this.`를 붙였거나 붙이지 않은 필드)여야 한다.
  - 인자 수는 `getDeclaredMethod`의 둘째 인자부터의 개수다. `Class[]` 배열을 통째로 넘겨 셀 수 없으면 `invoke`의 인자 수를, 그것도 셀 수 없으면 이름만으로 찾는다. 같은 대상 타입에서 후보가 둘 이상이면 `attrs.ambiguous=true`를 붙인다.
  - 타입 인자는 하위 타입에서 본 상위 타입의 것이다(중간 제네릭 클래스를 거쳐 정해진 것 포함). 타입 인자가 타입 변수·와일드카드·배열·소스 없는 타입이면 그 하위 타입으로는 잇지 않는다. 추상 하위 클래스도 `viaTypes`에 넣는다.
  - 같은 선언(공통 상위 인터페이스의 메서드)에 여러 대상 타입이 닿으면 엣지는 하나이고 `receiverType` 대신 `attrs.receiverTypes`(List)에 대상 타입들을 남긴다.
  - 이 엣지에서는 `DEPENDS_ON`을 만들지 않는다. `getClass`·`getDeclaredMethod`·`invoke` 자체는 JDK 호출로 센다.
  - `REFLECTION_CALL` 진단은 메서드당 1건(처음 나온 `invoke`의 위치)이다. 한 메서드에 추론한 호출과 추론하지 못한 호출이 함께 있어도 1건이다.
- 해석된 호출이라도 인자에 classpath에 없는 타입이 있고 이름·인자 수가 같은 후보가 둘 이상이면 신뢰도를 `INFERRED`로 낮춘다.
- 수신자 타입이 호출 대상의 선언 타입과 다르면 `attrs.receiverType`(바이너리 이름)을 남긴다. 구현 클래스를 고르는 쪽에서 쓴다.
- 미해결 호출의 원문은 공백을 한 칸으로 줄이고 200자를 넘으면 자른다(잘린 원문은 `...`로 끝나고 `(…)`가 붙지 않는다). 형태는 `<수신자>.<이름>(…)`, 수신자 없는 호출은 `<이름>(…)`, `new <타입>(…)`, `this(…)`, `super(…)`, `super.<이름>(…)`, enum 상수는 `<상수 이름>(…)`, 메서드 참조는 `<수신자>::<이름>`이다. 체인의 안쪽 링크는 소스에 쓴 인자 그대로다(`registry.add(null).order(…)`).
- 인자 수만 다른 미해결 호출은 같은 미해결 노드로 모인다. 엣지는 호출 위치마다 따로 남고 `argCount`로 구분한다. 메서드 참조처럼 인자 수를 모르면 `argCount`를 넣지 않는다.
- 컴파일러가 준 후보를 믿는 조건(F-003-R15)의 세부
  - "해석되지 않은 값"은 타입 바인딩이 없거나 복구된 바인딩인 인자다. 그런 인자가 없으면 컴파일러의 오류 보고(인자 불일치 등, F-003-R9)만 본다.
  - 인자 수는 매개변수 수와 같거나, 가변 인자면 `매개변수 수 - 1` 이상이어야 한다. 인자 수가 맞으면 후보를 믿는다(같은 이름·인자 수의 후보가 둘 이상이면 아래 가정대로 `INFERRED`).
  - 생성자의 타입 비교는 erasure로 한다. 타입 인자만 classpath에 없는 `new Page<Missing>(..)`은 `Page`의 생성자로 잇는다.
  - `super(..)`·`super.m(..)`의 믿을 수 없는 후보를 선언한 타입이 소스 없는(classpath에는 있는) 상위 클래스면 F-003-R13대로 `<FQN>#<이름>(?)` 호출로 다룬다.
- 인자 있는 enum 상수에 맞는 선언 생성자가 없으면 Lombok 합성 생성자에서 찾고(F-003-R11), 없으면 `<상수 이름>(…)` 미해결이다. 인자 없는 상수는 생성자를 선언했을 때만 `CALLS`를 만든다.
- 람다 안 호출의 `callType`은 대상에 따른 값(`static`/`virtual`/`interface`) 그대로이고 람다 경유 여부는 `viaLambda=true`로만 표시한다.
- `INSTANTIATES`의 대상이 소스 없는 타입이면 `java.externalCalls`와 같은 기준으로 만들거나 뺀다. enum 상수는 생성자를 선언했을 때만 `<clinit>()`에서 생성자로 `CALLS`를 만들고, 본문이 있으면 그 익명 타입으로 `INSTANTIATES`를 만든다.
- record의 암묵적 `equals`/`hashCode`/`toString` 호출은 JDK 메서드 호출로 센다.
- classpath에 없는 타입의 결과에 이어 부르는 호출(빌더 체인 등)은 반환 타입을 알 수 없어 해석하지 못한다. 체인당 미해결 1건으로 남기고(F-003-R12) jar를 주면 해석된다.
- `OVERRIDES`의 JDK 판정과 `INSTANTIATES`의 기록 여부도 F-002-R13의 판정을 따른다.
- `OVERRIDES`의 "직접 상위"는 타입 계층을 위로 올라가며 가지마다 처음 만나는 선언이다. 상위 클래스가 이미 구현한 인터페이스를 하위 클래스가 `implements`에 다시 적으면 그 인터페이스의 선언으로도 잇는다(소스에 적은 직접 상위이기 때문이다).
- 호출 지점인데 아무것도 남기지 않는 자리(F-003-R16의 예외): 실행 코드가 아닌 곳(애너테이션 값), 배열 생성 참조(`int[]::new`), 인자 없는 enum 상수의 암묵적 생성자, 익명 클래스 생성의 상위 생성자 호출(익명 타입의 생성자로만 잇는다), 암묵적 `super()`.
- 한계
  - 상속으로만 충족되는 인터페이스 메서드(`D extends B implements I`에서 `D`에 선언이 없고 `B`에서 물려받은 `only()`가 `I#only()`를 충족)는 엣지를 만들 메서드 노드가 `D`에 없어 `OVERRIDES`를 만들지 않는다. `B#only()`에서 `I#only()`로도 잇지 않는다(`B`는 `I`를 구현하지 않는다).
  - 인자에 해석되지 않은 값이 있고 인자 수가 맞는 후보는, F-003-R21이 가려내는 경우(원시 타입·final 클래스 자리의 미해석 값, 미해석 타입 자리의 해석된 값)가 아니면 인자 타입이 실제로 맞는지 확인하지 않고 믿는다.
  - classpath에 없는 타입을 거쳐 `java.lang.Object`의 메서드로 해석된 호출(`super.toString()`, 수신자 없는 `hashCode()`)은 JDK 호출로 센다. 그 타입이 재정의했는지는 알 수 없다.
  - Lombok 합성 메서드가 classpath에 없는 상위 타입의 메서드를 구현한 경우(`@Getter` 클래스가 jar 없는 `UserDetails`를 구현)에는 `OVERRIDES`를 만들지 않는다(상위 선언을 알 수 없다. F-003-R22는 프로젝트 상위 타입만 본다).
  - 인자의 타입을 알 수 없는 호출(미해석 타입의 메서드가 돌려준 값을 넘긴 `extractMessage(e.getConstraintViolations())` 등)은 F-003-R21로 가릴 수 없다. 이름·인자 수가 같은 후보가 둘 이상이면 지금처럼 컴파일러가 고른 선언으로 `INFERRED`다(자기 자신 호출로 남을 수 있다).
  - import한 타입이 classpath에 없을 때 컴파일러가 같은 단순 이름의 `java.lang` 타입으로 풀어 버린 바인딩(`Process`, `Module` 등)은 F-003-R21의 판정에서만 걸러 낸다. 구조 분석(F-002)의 `ASSOCIATES`·`DEPENDS_ON`과 메서드 ID의 매개변수 타입에는 그 `java.lang` 타입이 그대로 쓰인다.
  - `Class.forName(..).newInstance()`, `Constructor.newInstance`, `MethodHandle`, 프록시(`InvocationHandler`) 같은 다른 리플렉션은 보지 않는다(진단도 없다). 유틸리티 메서드로 감싼 리플렉션(`Reflections.invokeMethod(obj, "name", ..)`)은 호출하는 쪽에서 대상을 알 수 없다.

- 미해석 타입 인자의 오버로드 고르기(F-003-R24)의 세부
  - 후보는 수신자의 타입(수신자가 없으면 컴파일러가 고른 선언을 물려받은 가장 안쪽의 감싼 타입, 그것도 모르면 그 선언의 타입)과 그 상위 타입에서 이름이 같고 인자 수가 맞는 선언이다(가변 인자는 `인자 수 ≥ 매개변수 수 − 1`). 가까운 타입의 선언이 같은 매개변수의 먼 선언을 가린다. F-003-R21의 비교(`acceptsArguments`)를 그대로 쓴다: 가변 인자의 둘째 값부터는 원소 타입과 맞추고, 가변 인자 자리의 미해석 타입은 배열·원소 어느 쪽 차원이든 받는다.
  - 맞는 후보가 하나이고 그것이 컴파일러의 선택이면 전과 같다(같은 타입에 같은 이름·매개변수 수의 선언이 여럿이면 `INFERRED`, 아니면 `RESOLVED`). 다르면 `INFERRED`다. 해석된 자리의 타입이 전부 정확히 같은 후보가 하나뿐이면 그것을 고른다.
  - 가리지 못한 호출은 프로젝트 메서드든 소스 없는 타입의 메서드든 후보 전부로 잇는다(예: `new XMLMapperBuilder(<미해석>, ..)`는 `(InputStream, …)`와 `(XPathParser, …)` 둘 다). `java.externalCalls` 설정은 그대로 적용된다. 체인의 다음 호출은 첫 후보의 반환 타입으로 이어 간다.
  - 인자 식의 타입은 컴파일러가 준 바인딩에서 얻는다(지역 변수·매개변수·필드의 선언 타입, `new X(..)`, 캐스트, 리터럴 모두 바인딩이 있다). 바인딩이 아예 없는 자리(컴파일러가 보지 않은 코드)는 "모른다"로 두어 후보를 가리지 않는다.
- 인자 식의 타입 읽기(F-003-R28)의 세부
  - 식의 모양으로 읽는 것((b)~(d))은 파일을 보는 동안 한다. 그래도 타입을 모르는 인자가 "컴파일러가 선언을 찾지 못한 호출"(Lombok 합성 getter, 합성 멤버의 체인)이면 후보를 적어 두었다가 모든 파일을 본 뒤 그 호출이 해석된 대상의 반환 타입으로 다시 가린다. 분명히 맞지 않는 후보를 빼고 하나만 남으면 그것으로 `INFERRED`, 여럿이면 뒤에 알게 된 자리의 타입이 전부 정확히 같은 하나, 그래도 가리지 못하면 남은 후보 전부로 `ambiguous`다. 전부 빠지면 원래 후보 그대로다.
  - 합성 getter와 소스에 선언한 메서드의 반환 타입은 erasure의 바이너리 이름(원시 타입·배열 포함)으로 적어 둔다. 타입 변수를 돌려주는 메서드(`<T> T get()`)와 오버로드마다 반환 타입이 다른 호출은 모른다.
  - 뒤에 다시 가릴 때의 비교는 이름 기준이다: 같은 이름이거나 매개변수가 `Object`면 맞고, 원시·박싱·`String`은 같은 종류(문자열, 숫자, 불리언, 문자)끼리와 그 타입의 정해진 상위 타입(`CharSequence`, `Number`, `Serializable`, `Comparable` 등)에만 맞으며, 배열은 배열과 `Object`·`Serializable`·`Cloneable`에만 맞는다. 프로젝트 타입끼리는 상위 타입 관계가 있으면 맞고, 그 밖에는 판단하지 않는다.
  - 가리지 못한 호출이 소스 없는 타입의 메서드 여럿이면 JDK 호출로 세는 수(`EXTERNAL_CALLS_SKIPPED`)도 후보 수가 아니라 남은 후보 수만큼이다. 하나로 가려진 호출은 한 번만 센다(전에는 `sb.append(<미해석> + "x")` 같은 호출을 후보 수만큼 셌다).
  - 확인하지 못한 자리가 없는 후보를 고르는 규칙은 "해석된 자리의 타입이 전부 정확히 같은 후보가 하나"인 기존 규칙 다음에 본다(그 규칙으로 이미 하나가 정해지면 그대로다).
- 후보가 하나뿐인 `ambiguous`(F-003-R29): 상위 후보가 여럿이라는 사실은 엣지에 남지 않는다(`INFERRED` + `inheritedFrom`은 상위 타입이 하나일 때와 같다). 리플렉션 호출(F-003-R20)의 `ambiguous`는 같은 대상 타입에서 후보가 둘 이상일 때만 붙고 후보마다 엣지가 있다.
- 상속 필드의 호출(F-003-R26): (2)는 이름이 변수로도 타입으로도 풀리지 않고(import한 타입 이름이면 static 호출로 본다) 소문자로 시작할 때만 적용한다. 그 필드의 타입은 알 수 없으므로 external 메서드 노드를 만들지 않는다.
- `getBean`(F-003-R27): 메서드 이름이 `getBean`이고 인자가 1~2개이며 마지막 인자가 클래스 리터럴(`X.class`, 원시·배열 제외)인 호출만 본다(수신자 타입은 따지지 않는다). 컴파일러가 그 호출을 풀었으면(jar가 있거나 프로젝트의 제네릭 도우미) 컴파일러의 타입을 그대로 쓴다. `getBean("이름")`만으로 빈의 타입을 찾아 잇는 일은 하지 않는다(캐스트하거나 타입을 적은 변수에 받으면 그 타입으로 이어진다).

## 6. 미확정 사항
- 없음 (사용자 위임에 따라 5의 가정으로 진행).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: [0003](../../decisions/0003-use-jdt-core-for-java.md)

## 8. 계약 추가 제안 (design 3.4, 3.5에 반영 요청)
design.md에 반영됨(2026-10-11): 아래 항목은 모두 F-001 design.md에 있다. 이번 라운드의 것은 3.3(`<init>(?)` 생성자 ID), 3.4(`OVERRIDES`는 직접 상위로만, 소비자가 전이적으로 따라간다), 3.5(`argCount`, `externalChain`의 원문), 3.6(`java-call` 원문 형식), 7절(인자 수만 확인하는 한계, 상속으로만 충족되는 인터페이스 메서드)에 넣었다.

- 합성 메서드 ID: `java:method:<타입>#<clinit>()`, `java:method:<타입>#<instance-init>()` (`attrs.synthetic=true`)
- classpath에 없는 타입의 메서드 ID: `java:method:<FQN>#<이름>(?)` (`external=true`, `signatureUnknown=true`)
- CALLS attrs: `viaLambda`(boolean), `receiverType`(바이너리 이름), `lombok`(boolean: 대상이 Lombok 합성 멤버), `ambiguous`(boolean: 이름·인자 수로 찾은 후보가 여럿 남았다. `INFERRED`와 함께), `externalChain`(boolean: 체인당 하나로 줄인 미해결 엣지) — design 3.5에 반영됨
- (design.md에 반영됨(2026-10-11), 3.5) CALLS attrs: `argCount`(int: 미해결 `java-call` 엣지의 인자 수. 메서드 참조에는 없다). design 3.5의 CALLS (Java) 표에 행 추가
- (design.md에 반영됨(2026-10-11)) design 3.5 `externalChain` 행과 3.6 `java-call` 행의 원문 설명 `<수신자>.<메서드명>(<인자 수>)` → `<수신자>.<메서드명>(…)`(인자 수는 `argCount`)
- 진단: 체인당 미해결(F-003-R12)의 `UNRESOLVED_REFERENCE`는 심각도가 `INFO`다
- `callType`에 `lambda` 값은 없다(design 3.4에서 폐기)
- JAVA_METHOD attrs: `external`(boolean), `signatureUnknown`(boolean)
- 미해결 분류: `java-call`, `java-override`
- 옵션(extra): `java.externalCalls` = `none` | `nonJdk` | `all`
- 진단 코드: `EXTERNAL_CALLS_SKIPPED`

### 공개 프로젝트 검증 3차(B7, B16, B19)에서 더한 것
design.md에 반영됨(2026-10-11, 2차): `reflection`은 3.4(`CALLS` 표, `callType` 표), `inheritedFrom`·`viaTypes`·`receiverTypes`·`ambiguous`·`receiverType`·OVERRIDES `argCount`는 3.5, `java-override` 원문은 3.6, `REFLECTION_CALL`은 3.7, `OVERRIDES`가 `INFERRED`일 수 있다는 것은 3.4, 소비자 규칙은 3.4 "소비자가 지키는 규칙"(팬아웃 엣지)에 넣었다.
- design 3.4 `callType` 표: 값 `reflection` 추가(만드는 모듈 java. `Method.invoke`의 대상을 추론한 호출, 항상 `INFERRED`, F-003-R20)
- design 3.5 CALLS (Java) attrs: `inheritedFrom`(String: 프로젝트 타입이 소스 없는 상위 타입에서 물려받았다고 추론한 메서드의 호출. 값은 그 상위 타입의 FQN이고 대상 메서드는 `<FQN>#<이름>(?)`. `INFERRED`와 함께), `viaTypes`(List: `callType=reflection` 엣지에서 대상 타입을 그 타입 인자로 고정한 하위 타입의 바이너리 이름들), `receiverTypes`(List: 같은 선언에 여러 대상 타입이 닿은 `reflection` 엣지. `receiverType` 대신)
- design 3.5 `ambiguous` 행: "외부 상위 타입이 여럿이라 상위 클래스 쪽으로 고른 호출(`inheritedFrom`과 함께)"에도 붙는다
- design 3.5 `receiverType` 행: `reflection` 엣지에서는 리플렉션으로 부른 객체의 타입(타입 인자로 정해진 프로젝트 타입)이다
- design 3.5 OVERRIDES attrs: `argCount`(int: 미해결 `java-override` 엣지의 매개변수 수)
- design 3.6 `java-override` 행의 원문: `<이름>(<매개변수 수>)` → `<이름>(…)`(수는 `argCount`)
- design 3.7 진단 코드: `REFLECTION_CALL`(`INFO`, java: 대상을 정적으로 알 수 없어 기록하지 않은 `Method.invoke`가 있는 메서드. 메서드당 1건)
- design 3.4 `OVERRIDES` 행: 매개변수 타입이 classpath에 없으면 표기 비교로 잇고, 일부만 비교했으면 `INFERRED`다(F-003-R17)
- 소비자(export·viewer)가 알아야 할 것: `reflection` 엣지는 상위 메서드 하나에서 모든 대상 타입으로 나간다. 콜트리에서 특정 하위 타입을 거쳐 온 호출이면(직전 `CALLS`의 `receiverType`이 가리키는 타입 또는 그 구현 타입이 `viaTypes`에 든 엣지만) 그 엣지로 좁힌다

### 실전 검증 2차 후속에서 더한 것
design.md에 반영됨(2026-10-11, 2차): OVERRIDES `lombok`은 3.5, Lombok 합성 메서드가 낀 재정의는 3.4 `OVERRIDES` 행, F-003-R21의 예외는 7절 "인자 수만 확인한다" 행에 넣었다(7절의 "Lombok 합성 메서드가 낀 재정의" 행은 해소로 표시).
- design 3.5 OVERRIDES attrs: `lombok`(boolean: 출발이나 대상이 Lombok 합성 메서드인 재정의. 모든 파일을 본 뒤 표기 비교로 만든 것이고 `RESOLVED`다. F-003-R22)
- design 3.4 `OVERRIDES` 행: Lombok 합성 메서드가 낀 재정의도 만든다(프로젝트 상위 타입만)
- design 7절 한계: "인자 수만 확인한다"에 F-003-R21의 예외(미해석 매개변수 자리의 해석된 인자, 원시 타입·final 클래스 자리의 미해석 값은 다른 오버로드를 찾는다)를 더한다
- 새 노드·엣지 종류, ID 규칙, 진단 코드는 없다
- 소비자(export·viewer)가 알아야 할 것: 콜트리가 구현 메서드를 `OVERRIDES`로 찾을 때 `lombok=true` 엣지도 같은 뜻이다(인터페이스의 `getId()` 호출 아래에 구현 클래스의 합성 getter가 나온다). 합성 메서드에는 본문이 없으므로 그 아래로 더 내려갈 것은 필드 `ACCESSES`뿐이다

### 실전 재검증 2차 후속(2026-10-11)에서 더한 것 — design.md 반영 요청
design.md 반영됨(2026-10-11): F-003-R24의 `ambiguous`는 3.5 엣지 attrs 표(CALLS (Java))와 7절("인자 수만 확인한다" 행)에 넣었다. 수신자 없는 상속 호출의 `ambiguous`(F-003-R25)는 아래 3차 후속(F-003-R29)에서 없어졌으므로 "붙지 않는다"로 적었다.
- design 3.5 엣지 attrs `CALLS`의 `ambiguous`: "인자에 classpath에 없는 타입이 있어 오버로드를 가리지 못한 호출은 후보 전부로 `INFERRED` + `ambiguous=true`"(java, F-003-R24)를 더한다. 전에는 java의 해석된 호출 경로가 컴파일러가 고른 한 후보로만 이었다. 수신자 없는 상속 호출의 `ambiguous`(F-003-R25)도 같은 키다.
- 소비자(export·viewer)가 알아야 할 것: 한 호출 지점에서 같은 이름의 메서드 여럿으로 가는 `ambiguous` `CALLS`가 늘었다(jeesite-v1 3 → 34건). 콜트리에서는 같은 줄·같은 이름의 후보가 나란히 나온다.

### 실전 재검증 3차 후속(2026-10-11)에서 더한 것 — design.md 반영 요청
design.md 반영됨(2026-10-11): `ambiguous`가 후보 둘 이상일 때만 붙는다는 것은 3.5 공통 규칙("`ambiguous`는 후보가 둘 이상일 때만 붙는다")과 엣지 attrs 표의 `ambiguous`·`inheritedFrom` 행, 3.4 "소비자가 지키는 규칙"(추론의 근거 표시), 인자 식의 타입을 읽는 범위(F-003-R28)는 7절 "인자 수만 확인한다" 행에 넣었다.
- design 3.5 엣지 attrs `CALLS`의 `ambiguous`: "같은 (출발, 위치)에서 나가는 후보가 둘 이상일 때만 붙는다"를 더하고, "수신자 없는 상속 호출의 `ambiguous`(F-003-R25)"·"외부 상위가 여럿인 상속 호출의 `ambiguous`(F-003-R19)"는 지운다: 그 호출은 `INFERRED` + `inheritedFrom`만 남는다(F-003-R29). `inheritedFrom`의 설명에서도 "여럿이면 `ambiguous`"를 뺀다.
- design 7절 "인자 수만 확인한다" 행: 미해석 타입 인자가 있는 호출에서 인자 식의 타입을 읽는 범위(F-003-R28: 합성 getter, 선언 반환 타입, 문자열 사슬, 표기 일치)를 더한다.
- 새 노드·엣지 종류, attrs 키, 진단 코드는 없다.
- 소비자(export·viewer)가 알아야 할 것: `[ambiguous n]` 묶음은 항상 n ≥ 2다. 전에 후보 하나로 `ambiguous`가 붙던 상속 호출(`getContextPath()` 등)은 보통의 추론 호출(`~`)로 나온다.

