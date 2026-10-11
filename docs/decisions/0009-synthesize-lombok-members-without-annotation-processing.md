---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자 위임, 에이전트 제안 (소유자 검토 필요)
---

# Lombok 멤버는 애너테이션 처리기를 돌리지 않고 분석기가 합성한다

## Context and Problem Statement
Lombok을 쓰는 프로젝트에서는 getter, setter, 생성자, 빌더, `log` 필드가 소스에 없다. Java 분석(ADR-0003)은 소스를 파싱해 바인딩을 해석하므로 이 멤버들을 부르는 호출이 해석되지 않고, 콜트리가 DTO와 빌더에서 끊기며 `@RequiredArgsConstructor`로 받는 생성자 주입도 보이지 않는다. 이 멤버들을 어떻게 그래프에 넣을지 정해야 한다.

## Decision Drivers
- 의존 jar가 없거나 컴파일되지 않는 프로젝트도 분석해야 한다(PRD 7절의 가정: jar는 옵션이고 없으면 미해결로 남긴다).
- 분석 대상 프로젝트의 파일은 읽기만 한다(AGENTS.md). 사용자에게 사전 변환 단계를 요구하지 않는다.
- 추측으로 관계를 만들지 않는다. 확정하지 못한 것은 `INFERRED`나 미해결로 구분한다.

## Considered Options
- 분석기가 Lombok 애너테이션을 보고 생길 멤버를 합성하고, 해석되지 않은 호출을 이름과 인자 수로 합성 멤버에서 찾는다
- Lombok 애너테이션 처리기를 JDT 또는 javac에 붙여 실행한다
- delombok한 소스를 입력으로 요구한다
- 아무것도 하지 않고 미해결로 둔다

## Decision Outcome
Chosen option: "분석기가 합성한다", because lombok jar가 classpath에 없어도, 프로젝트가 컴파일되지 않아도 같은 결과를 내고 사용자에게 사전 단계를 요구하지 않기 때문이다.

java 모듈은 Lombok 애너테이션을 import와 단순 이름으로 알아보고(F-002-R14), 생길 멤버를 `synthetic=true`, `lombok=<애너테이션 이름>`인 보통의 `JAVA_METHOD`·`JAVA_FIELD`·`JAVA_TYPE` 노드로 만든다. JDT가 해석하지 못한 호출은 모든 파일을 본 뒤 수신자 타입과 그 상위 타입에서 이름·인자 수가 맞는 합성 멤버를 찾아 잇는다(F-003-R11, R14). 스테레오타입 클래스의 합성 생성자가 하나면 그 매개변수가 된 필드를 생성자 주입으로 본다(F-004-R3).

### Consequences
- Good, because lombok이 classpath에 없을 때, 애너테이션 스텁만 있을 때, 실제 jar가 있을 때 같은 노드와 엣지가 나온다(F-002-AC14, F-003-AC14).
- Good, because 합성 멤버가 보통의 노드 ID를 쓰므로 콜트리, 클래스 다이어그램, 링커가 따로 처리할 것이 없다. `lombok` 속성으로 구분만 한다.
- Bad, because 컴파일러가 아니라 이름과 인자 수로 호출을 잇는다. 같은 이름·인자 수의 후보가 여럿이면(오버로드) 신뢰도가 `INFERRED`로 내려가고, 인자 타입으로 줄여도 남으면 후보 모두에 `ambiguous=true`로 잇는다.
- Bad, because JDT가 해석하지 못한 곳에서는 제네릭 타입 인자를 따라가지 않는다. `getItems().get(0).getPrice()`처럼 원소 타입이 필요한 체인은 거기서 끊겨 미해결로 남는다.
- Bad, because 지원하지 않는 것이 있다: `@Delegate`, `@UtilityClass`, `@Accessors(prefix=..)`, `lombok.config`, `@SuperBuilder`의 `BuilderImpl`, `@Data`의 `canEqual`, `@Jacksonized`, `@StandardException` 등(F-002 spec 5절). 이들이 만드는 멤버로의 호출은 미해결로 남는다.
- Bad, because Lombok의 생성 규칙(접근자 이름, 생성자 매개변수 선정, 빌더 구조)을 분석기 안에 다시 적었다. Lombok이 아닌 애너테이션 처리기가 만드는 코드는 다루지 않는다.

### Confirmation
F-002-AC14~AC18(합성 노드), F-003-AC14~AC18·AC21·AC22(합성 멤버로의 호출과 체인), F-004-AC13(생성자 주입)의 테스트로 확인한다. Lombok이 아닌 같은 이름의 애너테이션(프로젝트가 만든 `@Getter`, Spring의 `@Value`)에는 합성하지 않는 것을 F-002-AC18이 확인한다.

## Pros and Cons of the Options

### Lombok 애너테이션 처리기를 JDT 또는 javac에 붙여 실행
- Good, because Lombok이 실제로 만드는 멤버와 정확히 같은 것을 얻는다.
- Bad, because lombok jar가 classpath에 있어야 하고, 처리기가 도는 컴파일 과정이 성립해야 한다. 의존 jar 없이도 분석한다는 전제와 맞지 않는다.
- [확인 필요] JDT `ASTParser`의 일괄 파싱에 Lombok 처리기를 붙일 수 있는지는 확인하지 않았다.

### delombok한 소스를 요구
- Good, because 분석기는 보통의 Java 소스만 다루면 된다.
- Bad, because 사용자가 분석 전에 대상 프로젝트에서 Lombok을 실행해 별도 소스 트리를 만들어야 한다. 분석 결과의 위치(파일, 줄)도 원본이 아니라 그 변환된 소스의 것이 된다.

### 미해결로 둠
- Good, because 구현이 없고 추측도 없다.
- Bad, because Lombok을 쓰는 프로젝트에서 DTO 접근자와 빌더 호출이 전부 미해결 진단이 되어 다른 미해결을 가리고, Lombok 생성자로 받는 주입(`INJECTS`)이 그래프에 없다.

## More Information
- 관련 기능: F-002(R14), F-003(R11, R14), F-004(R3)
- 계약: `docs/specs/F-001-analysis-pipeline/design.md` 3.3(합성 멤버 ID), 3.5(`synthetic`, `lombok`, `ambiguous`)
- 다시 볼 조건: 지원하지 않는 Lombok 기능 때문에 실제 프로젝트에서 미해결이 많이 남을 때, 다른 애너테이션 처리기가 만드는 코드의 지원이 필요해질 때.
