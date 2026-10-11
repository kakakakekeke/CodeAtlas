---
type: spec
id: F-014
title: 클래스 다이어그램 출력
status: implemented        # draft | approved | implemented
updated: 2026-10-11
---

# F-014 클래스 다이어그램 출력

## 1. 배경
PRD 목표 "분석 결과만으로 클래스 다이어그램을 그릴 수 있다"와 시나리오 2(패키지나 클래스를 골라 클래스 다이어그램을 얻는다)를 맡는다. Java 구조 분석(F-002)이 그래프에 남긴 타입, 멤버, 관계를 범위 옵션에 따라 골라 UML 표기의 텍스트 다이어그램으로 낸다.

## 2. 범위
- 포함: 그래프 → 중간 모델(`ClassDiagramModel`) → Mermaid `classDiagram`, PlantUML class 렌더러. 범위·표시 옵션. `analyze` 때 `class-diagram/` 아래 패키지별 다이어그램과 `index.md` 출력.
- 제외: 이미지 렌더링, 배치(레이아웃) 조정, 시퀀스 다이어그램, CLI 하위 명령(조회 API만 제공한다).

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-014-R1 | 시스템은 범위 안의 `JAVA_TYPE`과 그 멤버(`CONTAINS`의 `JAVA_FIELD`, `JAVA_METHOD`), 타입 사이 관계를 중간 모델로 만들어야 한다. | 사용자 요청 |
| F-014-R2 | 시스템은 UML 표기로 가시성(`+ - # ~`), static, abstract, `<<interface>>`·`<<enumeration>>`·`<<record>>`·`<<annotation>>`·스테레오타입(`<<Service>>` 등), 제네릭을 표시해야 한다. | 사용자 요청 |
| F-014-R3 | 시스템은 상속(실선 삼각), 구현(점선 삼각), 연관(실선 화살표, 필드명과 다중도), 의존(점선 화살표)을 Mermaid와 PlantUML로 그려야 한다. | 사용자 요청, PRD 목표 3 |
| F-014-R4 | 시스템은 범위를 패키지 접두(하위 패키지 포함 여부) 또는 타입 목록과 이웃 깊이로 받아야 한다. | 사용자 요청, PRD 시나리오 2 |
| F-014-R5 | 시스템은 멤버 표시 수준(전부, public만, 없음)과 getter·setter 숨김 옵션을 받아야 한다. | 사용자 요청 |
| F-014-R6 | 시스템은 `external` 타입을 기본으로 빼야 한다. external 상위 타입 표시 옵션이 켜진 경우, 시스템은 범위 안 타입의 external 상위 타입을 이름만 그려야 한다. | 사용자 요청 |
| F-014-R7 | 시스템은 익명·로컬 타입을 기본으로 빼고, 중첩 타입 제외 옵션을 받아야 한다. | 사용자 요청 |
| F-014-R8 | 만약 범위의 타입 수가 상한을 넘으면, 시스템은 멤버를 생략하고 경고 문구를 다이어그램 주석으로 남겨야 한다. | 사용자 요청 |
| F-014-R9 | 패키지 묶음 옵션이 켜진 경우, 시스템은 타입을 패키지별로 묶어야 한다(Mermaid `namespace`, PlantUML `package`). | 사용자 요청 |
| F-014-R10 | 시스템은 그릴 관계 종류(`EXTENDS`, `IMPLEMENTS`, `ASSOCIATES`, `DEPENDS_ON`, `INJECTS`)를 고르게 해야 한다. `INJECTS`는 기본으로 끈다. | 사용자 요청 |
| F-014-R11 | 시스템은 같은 입력에 바이트까지 같은 출력을 내고, 속성이 빠진 노드에서도 예외 없이 그려야 한다. | 사용자 요청 |
| F-014-R12 | `analyze`가 실행되면, 시스템은 타입이 직접 들어 있는 패키지마다 `class-diagram/<패키지>.mmd`와 `.puml`을, 그리고 `class-diagram/index.md`를 써야 한다. 만약 패키지 수가 상한을 넘으면, 상위 패키지로 묶어야 한다. | 사용자 요청 |
| F-014-R13 | 만약 그릴 Java 타입이 그래프에 없으면(기본 옵션으로 그려지는 타입이 0개), 시스템은 `class-diagram/` 산출물(패키지별 파일과 `index.md`)을 빈 내용으로 쓰지 말고 생략해야 하며, `report.md`의 머리에 `Java 타입 없음(Java 소스가 분석되지 않음)` 한 줄을 남겨야 한다. | 통합 단계 개선 (같은 규칙이 F-015-R14에 산출물 전체에 대해 적혀 있다) |
| F-014-R14 | 시작 타입 이름(`ClassDiagramOptions.types`) 중 범위의 후보에서 찾지 못한 것이 있으면, 시스템은 그 이름들을 모델과 함께 `ClassDiagramModel.unresolvedNames()`로 돌려줘야 한다(준 순서대로, 앞뒤 공백만 뗀 원문으로, 중복 없이). | 통합 단계 개선 (F-001-R35의 조회 API 부분) |
| F-014-R15 | 만약 패키지 하나의 Mermaid 클래스 다이어그램이 글자 수 한도(`export.mermaid.maxChars`, 기본 45000)를 넘으면, 시스템은 멤버를 public만 → 없음 순으로 줄이고, 그래도 넘으면 이름순 조각(`class-diagram/<패키지>-<n>.mmd`)으로 나누고, 그 사실을 `index.md`의 비고에 적어야 한다. PlantUML은 줄이거나 나누지 않는다. | 실제 프로젝트 검증(클래스 다이어그램 94KB) |

## 4. 수용 기준

테스트 위치: `codeatlas-export/src/test/java/io/codeatlas/export/classdiagram/`, 표준 산출물은 `StandardExporterTest`.

### F-014-AC1 (대상: F-014-R1)
- Given 타입 3개(인터페이스, 구현 클래스, 다른 패키지 클래스), 필드, 메서드
- When `ClassDiagrams.model(graph, 옵션(패키지 접두))`
- Then 범위의 타입과 멤버가 ID 순으로, 범위 안 관계만 나온다.

### F-014-AC2 (대상: F-014-R2)
- When Mermaid로 렌더링
- Then `-`, `+`, `#`, `~` 가시성, static `$`, abstract `*`, `<<interface>>`, `<<Service>>`, 제네릭 `~T~`가 나온다. 생성자는 타입 이름으로 나온다.

### F-014-AC3 (대상: F-014-R3)
- When Mermaid로 렌더링
- Then `<|--`, `<|..`, `--> "*" … : 필드명`, `..>`가 관계 종류대로 나온다.

### F-014-AC4 (대상: F-014-R2, R3)
- When PlantUML로 렌더링
- Then `interface`, `abstract class`, `enum`, `{static}`, `{abstract}`, `<|--`, `<|..`, `-->`, `..>`가 나온다.

### F-014-AC5 (대상: F-014-R4)
- Given `com.shop.order`, `com.shop.order.dto`, `com.shop.member` 패키지
- When 접두 `com.shop.order`(하위 포함/미포함), 타입 목록 + 깊이 0/1
- Then 각 범위에 맞는 타입만 나온다.

### F-014-AC6 (대상: F-014-R5)
- When 멤버 `PUBLIC`, `NONE`, getter·setter 숨김
- Then private 멤버·전체 멤버·접근자가 각각 빠진다.

### F-014-AC7 (대상: F-014-R6)
- Given external 상위 타입을 상속하는 타입
- When 기본 옵션 / external 상위 타입 표시 / external 포함
- Then 각각 external 타입이 없음 / 이름만 있고 상속선이 있음 / 멤버까지 있음.

### F-014-AC8 (대상: F-014-R7)
- Given 중첩 타입 `Outer$Inner`, 익명 타입 `Outer$1`, 로컬 타입 `Outer$1Local`
- When 기본 옵션 / 중첩 제외
- Then 익명·로컬은 항상 빠지고, 중첩 제외 때 `Outer$Inner`도 빠진다.

### F-014-AC9 (대상: F-014-R8)
- Given 타입 5개, 상한 3
- Then 멤버 줄이 없고 `%%`(Mermaid)·`'`(PlantUML) 주석에 경고가 있다.

### F-014-AC10 (대상: F-014-R9)
- When 패키지 묶음을 켜면
- Then Mermaid에 `namespace`, PlantUML에 `package` 블록이 나온다.

### F-014-AC11 (대상: F-014-R10)
- When 관계 종류를 `EXTENDS`만 / `INJECTS` 포함으로 주면
- Then 고른 종류의 선만 나온다.

### F-014-AC12 (대상: F-014-R11)
- Given 같은 내용을 다른 순서로 넣은 두 그래프, 속성이 없는 타입·메서드, `$`와 한글이 든 이름
- Then 출력이 같고 예외가 없으며 Mermaid 출력이 classDiagram 문법 검증기를 통과한다.

### F-014-AC13 (대상: F-014-R12)
- When `StandardExporter.export`
- Then 패키지마다 `.mmd`·`.puml`과 `index.md`가 생긴다. 패키지 상한 1을 주면 공통 상위 패키지 파일 하나로 묶인다. `export.classDiagram=false`면 생기지 않는다.

### F-014-AC14 (대상: F-014-R13)
- Given 빈 그래프, 테이블만 있는 그래프, Java 타입만 있는 그래프
- When `StandardExporter.export`
- Then 앞의 둘에는 `class-diagram/` 아래 파일이 하나도 없고 `report.md`의 머리에 `- Java 타입 없음(Java 소스가 분석되지 않음)`이 있다. Java 타입만 있는 그래프에는 `class-diagram/<패키지>.mmd`·`.puml`·`index.md`가 있고 그 줄이 없다.
- 테스트: `StandardExporterTest`의 "F-013-AC15 F-014-AC14 F-015-AC19 대상이 없는 산출물은 …", `SummaryReportTest`의 "F-014-AC14 F-015-AC19 DB 객체·Java 타입·엔드포인트·배치 잡이 없으면 …"(같은 동작이 F-015-R14에도 적혀 있어 ID를 함께 적는다).

### F-014-AC15 (대상: F-014-R14)
- Given 시작 타입 이름 여럿(있는 것, 없는 것, 같은 이름의 반복, 빈 문자열, external 타입)
- When `ClassDiagrams.model`을 만들면
- Then 모델의 `unresolvedNames()`에 찾지 못한 이름이 준 순서대로 중복 없이 들어 있다(빈 문자열은 넣지 않는다). external 타입은 `includeExternal`을 줘야 후보이고, 주지 않으면 찾지 못한 이름이다. 시작 타입 이름을 주지 않았으면(기본 옵션, 패키지 접두만) 빈 목록이다.
- 테스트: `ClassDiagramTest`의 "F-014-AC15 F-001-AC49 시작 타입 이름 중 그래프에서 찾지 못한 것을 모델과 함께 unresolvedNames()로 돌려준다"(같은 동작이 F-001-R35에도 적혀 있어 ID를 함께 적는다). `classdiagram` 명령이 이 목록을 쓰는 동작은 F-001-AC35다.

### F-014-AC16 (대상: F-014-R15)
- Given 타입 10개(타입마다 private 필드·메서드 12개씩과 public 메서드 4개, 사슬로 상속)인 패키지와 작은 패키지
- When 한도를 전체 길이의 절반 / public만 그린 길이보다 조금 작게 / 330자로 주고 표준 산출물을 쓰면
- Then 차례로 public 멤버만 그린 다이어그램 / 멤버 없는 다이어그램 / 이름순 조각 `<패키지>-1.mmd`, `-2.mmd`…가 나온다. 조각은 한도 안이고 문법 검증기를 통과하며, 다른 조각의 타입과의 관계는 그 타입을 이름만 그려 잇는다. `index.md`의 비고에 "Mermaid 글자 수 한도 때문에 public 멤버만 그렸다." / "멤버를 생략했다." / "이름순 n개 조각으로 나눴다"가 있고 조각의 링크가 있다. 나눴을 때 `<패키지>.mmd`(전체, 멤버 없음)에는 한도를 넘는다는 주석이 붙는다. `.puml`과 작은 패키지의 파일은 그대로다. 입력 순서를 뒤집어도 같다.
- 테스트: `LargeArtifactSplitTest.reducesAndSplitsClassDiagrams`.

## 5. 가정
- 타입의 패키지는 바이너리 이름에서 마지막 `.` 앞부분으로 구한다. 익명·로컬 타입은 바이너리 이름의 마지막 `$` 뒤가 숫자로 시작하는 것으로 판단한다(속성 `anonymous`, `local`이 있으면 그것도 본다).
- 다이어그램 안의 타입 표시는 단순 이름이다. 같은 단순 이름이 겹치면 Mermaid 식별자는 전체 이름에서 `.`을 `_`로 바꾼 것을 쓰고 라벨로 구분한다.
- 멤버 안의 타입 문자열은 패키지를 떼고 보인다(`java.util.List<com.a.Order>` → `List<Order>`).
- 인터페이스·애너테이션 타입의 멤버에 가시성 수정자가 없으면 public으로 본다.
- getter·setter는 이름과 매개변수 수로만 판단한다(`getX()`, `isX()`, `setX(v)`).
- `lambda$`로 시작하는 합성 메서드는 그리지 않는다.
- 멤버와 타입은 ID 순으로 정렬한다(소스 순서가 아니다).
- 타입 수 상한의 기본값은 60, `analyze`의 패키지 수 상한 기본값은 200이다.
- external 상위 타입 표시는 기본으로 끈다.
- `INJECTS`는 `..>`에 `inject` 라벨로 그린다.
- 범위에 패키지 접두와 타입 목록을 함께 주면 둘의 합집합이다. 이웃 깊이의 기본값은 1이고 고른 관계 종류를 양방향으로 따라간다.
- Mermaid namespace 이름은 패키지의 `.`을 `_`로 바꾼 것이다(점이 든 namespace는 버전에 따라 파싱이 달라서). Mermaid 예약어와 같은 클래스 식별자에는 `_`를 붙이고 라벨로 원래 이름을 보인다. 제네릭 타입은 라벨 대신 `~T~`를 쓴다.
- external 상위 타입에는 그래프에 노드가 없는 상위 타입(마무리 단계 전)과 `UNRESOLVED` 노드도 포함한다.
- `analyze`의 패키지 묶음은 패키지 이름을 앞에서 d개 조각까지만 남겨 묶음 수가 상한 이하가 되는 가장 큰 d를 쓴다. 묶은 다이어그램은 하위 패키지를 포함하고 패키지 묶음(namespace)을 켠다. 이름 없는 패키지의 파일명은 `_default_`다.
- 대상이 없어 `class-diagram/`을 생략할 때(F-014-R13) 전에 같은 폴더에 써 둔 파일을 지우지는 않는다. "그릴 Java 타입"은 기본 옵션으로 그려지는 타입이다(external, 익명·로컬 타입은 세지 않는다).
- Mermaid 문법 확인은 F-013과 같다(자체 검증기 + 실제 mermaid 11.17.2·12.0.0 수동 확인). PlantUML은 실제 파서로 확인하지 못했다.

- 글자 수 한도(R15): 옵션으로 준 멤버 표시 수준(`export.classDiagram.members`)보다 낮은 수준만 시도한다. 조각은 타입을 ID 순으로 같은 크기로 나눈 것이고, 멤버 없는 다이어그램이 모두 한도 안에 드는 가장 적은 조각 수를 고른 뒤 조각마다 한도 안에 드는 가장 자세한 수준으로 그린다. 조각에는 한쪽 끝이라도 그 조각의 타입인 관계가 전부 나오고 다른 조각의 타입은 이름만 그린다(관계가 한 타입에 몰려 있으면 그 타입이 든 조각은 한도를 넘을 수 있다. 타입 하나짜리 조각은 더 나누지 않는다). 조회 API(`ClassDiagrams.render`)는 줄이거나 나누지 않는다(나누는 것은 `ClassDiagramSplitter.split`).

## 6. 미확정 사항
- 없음(5절의 가정은 사용자 검토가 필요하다).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: 없음
