---
type: spec
id: F-013
title: ERD 출력
status: implemented        # draft | approved | implemented
updated: 2026-10-11
---

# F-013 ERD 출력

## 1. 배경
PRD 목표 "분석 결과만으로 ERD를 그릴 수 있다"와 시나리오 1을 맡는다. 그래프의 테이블, 컬럼, 외래 키(DDL)와 추론 관계(F-018)를 읽어 텍스트 기반 ERD로 낸다. DDL에 없는 테이블과 추론한 관계는 눈에 띄게 구분해 "조용히 누락되는 것이 없다"는 성공 지표를 지킨다.

## 2. 범위
- 포함: 그래프 → 공통 중간 모델(`ErdModel`) → Mermaid `erDiagram`, PlantUML(entity), DBML 렌더러. 범위·표시 옵션. 테이블 명세(`erd/tables.md`). `analyze` 때 `erd/` 아래 표준 산출물 출력.
- 제외: 이미지(SVG, PNG) 렌더링, 관계 추론 자체(F-018), CLI 하위 명령(조회 API만 제공한다), 시퀀스·시노님·루틴의 표시.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-013-R1 | 시스템은 그래프의 `DB_TABLE`, `DB_COLUMN`(`CONTAINS`), `REFERENCES`를 형식과 무관한 중간 모델(엔티티, 컬럼, 관계)로 만들어야 한다. 컬럼은 `ordinal` 순이다. | 사용자 요청 |
| F-013-R2 | 시스템은 중간 모델을 Mermaid `erDiagram`, PlantUML entity, DBML로 렌더링하고 PK, FK, 타입, NOT NULL, 주석(옵션)을 표시해야 한다. | 사용자 요청, PRD 목표 2 |
| F-013-R3 | 시스템은 DDL 관계를 실선으로, 추론 관계를 점선과 `(추론 n건)` 라벨로 그려야 한다. 만약 추론 관계의 `directionKnown`이 false이면, 시스템은 카디널리티 없이 그려야 한다. | 사용자 요청, AGENTS.md (추론 구분) |
| F-013-R4 | 시스템은 관계의 카디널리티를 FK 컬럼이 자식의 PK 전체 또는 UNIQUE이면 1:1, 아니면 N:1로 정하고, FK 컬럼이 nullable이면 부모 쪽을 0..1로 표기해야 한다. | 사용자 요청 |
| F-013-R5 | 만약 테이블이 `declared=false`이면, 시스템은 그 테이블을 표식과 함께 그리고 컬럼은 SQL에서 참조된 컬럼(컬럼 `READS`/`WRITES` 엣지가 있는 것)만 보여야 한다. | 사용자 요청, PRD 성공 지표 |
| F-013-R6 | 시스템은 범위 옵션(전체, 테이블 목록과 이웃 깊이, 이름 glob, 추론 관계 포함 여부, 최소 `evidenceCount`, 뷰 포함 여부)과 컬럼 표시 수준(전부, 키만, 없음)을 받아야 한다. | 사용자 요청 |
| F-013-R7 | 만약 식별자나 타입 문자열에 형식 문법에 안전하지 않은 문자(한글, `$`, `#`, 공백, 따옴표, 괄호, 쉼표)가 있으면, 시스템은 형식별 규칙으로 이스케이프해 출력이 문법을 깨지 않게 해야 한다. | 사용자 요청 |
| F-013-R8 | 시스템은 같은 그래프와 옵션에 대해 바이트까지 같은 출력을 내야 한다(이름·ID 순 정렬, 시각 정보 없음). | 사용자 요청 |
| F-013-R9 | 만약 컬럼·관계의 속성이 빠져 있으면, 시스템은 예외 없이 알 수 있는 것만 그려야 한다. | 사용자 요청 |
| F-013-R10 | 시스템은 테이블마다 컬럼 표, 키, 관계, 참조하는 구문 수를 담은 테이블 명세(Markdown)를 만들어야 한다. | 사용자 요청 |
| F-013-R11 | `analyze`가 실행되면, 시스템은 출력 폴더에 `erd/erd.mmd`, `erd/erd.puml`, `erd/erd.dbml`, `erd/tables.md`를 써야 한다. 옵션으로 끌 수 있다. | 사용자 요청 |
| F-013-R12 | 만약 그래프에 테이블과 뷰가 하나도 없으면, 시스템은 `erd/` 아래 파일을 빈 내용으로 쓰지 말고 생략해야 한다. | 통합 단계 개선 |
| F-013-R13 | 시스템은 테이블 명세에 ERD의 범위 옵션(테이블 목록과 이웃 깊이, 이름 glob, 뷰 포함)과 관계 옵션(추론 관계 포함, 최소 `evidenceCount`)을 ERD와 같은 규칙으로 적용할 수 있어야 한다. | 통합 단계 개선 |
| F-013-R14 | 시스템은 시작 테이블로 받은 이름 중 그래프에서 찾지 못한 것을 모델과 함께 돌려줘야 한다. 뷰의 이름이라서(뷰 미포함) 빠진 것은 구분할 수 있어야 한다. | 통합 단계 개선 |
| F-013-R15 | 만약 `erd/erd.mmd`의 글자 수가 한도(`export.mermaid.maxChars`, 기본 45000. Mermaid의 기본 `maxTextSize`는 50,000자다)를 넘으면, 시스템은 주제 영역(테이블 이름 접두사)별 `erd/erd-<영역>.mmd`, 키 컬럼만 남긴 전체 개요 `erd/erd-overview.mmd`(그래도 넘으면 컬럼 없이), 영역 목록과 영역 간 관계 수를 적은 `erd/index.md`를 써야 한다. PlantUML과 DBML은 나누지 않는다. | 실제 프로젝트 검증(테이블 200개에서 `erd.mmd` 89KB) |
| F-013-R16 | 시스템은 테이블 명세의 "참조하는 구문" 수에서 데이터 적재 구문(`attrs.dataLoad=true`)을 기본으로 빼고 `- 데이터 적재: 구문 n개, m행` 줄로 따로 적어야 한다(F-015-R28). | 실제 프로젝트 검증 |
| F-013-R17 | ERD를 영역으로 나눌 때(R15) 접두사로 묶이지 않는 테이블(같은 접두사가 3개 미만: 밑줄 없는 이름의 스키마에서는 전부)은 관계(`REFERENCES`)로 이어진 테이블끼리 묶어야 한다. 한도를 넘는 연결 요소는 그 안에서 가장 많이 참조되는 테이블들을 중심으로 나누고, 영역 이름은 그 영역에서 가장 많이 참조되는 테이블 이름이다. 전체 테이블의 20%를 넘는 테이블이 참조하는 테이블(허브)은 영역에 넣지 않고 `erd/index.md`에 "허브 테이블"로 따로 적는다(그것을 참조하는 영역마다 이름만 있는 외곽 엔티티로 나온다). | 3차 검증(공통컴포넌트의 영역이 `ALL-1`~`ALL-3` 이름순 조각) |
| F-013-R18 | 방향을 정했다가 거둔 추론 관계(`directionKnown=false`와 함께 오는 `declaredOpposite`, `pseudoKey`)는 라벨의 "방향 미상" 뒤에 까닭을 적어야 한다. | link 계약 변경 |
| F-013-R19 | DDL에 없는데 SQL이 참조하는 컬럼(정의가 있는 테이블의 `declared=false` 컬럼)은 ERD 본체에 넣지 않고, 테이블 명세의 그 테이블 아래에 "DDL에 없는데 SQL이 참조하는 컬럼"으로 구문과 함께 적어야 한다(`report.md`는 F-015-R51). | sql 계약 변경 |
| F-013-R20 | upgrade·patch 스크립트의 `ALTER TABLE`에서 온 외래 키(`REFERENCES`의 `fromUpgrade=true`)는 DDL 관계(실선)로 그리되 관계 라벨(Mermaid·PlantUML), DBML의 줄 끝 주석, 테이블 명세의 관계 줄에 `(upgrade 스크립트)`를 적어 구분해야 한다. | sql 계약 변경 |
| F-013-R21 | `erd/index.md`의 영역 표는 허브 테이블(R17)과의 관계를 "다른 영역과의 관계"에 넣지 않고 "허브와의 관계" 열에 따로 세어야 한다(허브가 있을 때만 열이 생긴다). "다른 영역과의 관계"의 합은 "영역 간 관계"의 관계 수 합의 두 배와 같아야 한다. | 5차 검증(공통컴포넌트: 다른 영역과의 관계가 전부 허브와의 관계인데 "영역 간 관계: 없음") |
| F-013-R22 | 관계로 묶이지 않고 남은 테이블(R17의 `기타`)은 이름의 공통 접두사로 다시 묶어야 한다: 가장 긴 접두사부터, 그 접두사를 가진 테이블 가운데 아직 묶이지 않은 것이 3개 이상이고 접두사가 3자 이상이면 한 영역(`COMTNSMS*`)이다. 묶이지 않는 나머지만 `기타`다. | 5차 검증(`기타`가 88개, 전체의 46%) |
| F-013-R23 | 관계로 묶은 영역과 이름 접두로 묶은 영역은 테이블 30개를 넘지 않아야 한다. 30개(또는 글자 수 한도)를 넘는 관계 묶음은 그 안에서 가장 많이 참조되는 테이블(중심 테이블)을 빼고 나머지를 다시 묶는다: 중심 테이블은 자기 이름의 영역에 있고 그것을 참조하는 다른 영역에는 이름만 나오며, `erd/index.md`에 "중심 테이블"로 적는다. | 5차 검증(`COMTNEMPLYRINFO` 영역이 68개 테이블 한 덩어리) |
| F-013-R24 | 영역 이름이 된 테이블에 논리명(`comment`)이 있으면, `erd/index.md`의 영역 이름은 `논리명(물리명)`이어야 한다. 파일 이름은 물리명 그대로다. | 5차 검증 |
| F-013-R25 | 테이블 명세에 시작 테이블(`ErdOptions.tables()`)을 주면, 시스템은 시작 테이블을 준 순서대로 맨 앞에, 나머지(이웃, 이름 패턴에 맞은 테이블)를 그 뒤에 이름순으로 내야 한다. 범위가 전체이거나 패턴뿐이면 이름순이다. | 5차 검증(`erd --spec --table T`에서 이웃이 먼저 나옴) |
| F-013-R26 | upgrade 스크립트에만 있고 현재 정의에 없는 외래 키(`fromUpgrade=true`이면서 `source=INFERRED`이거나 엣지 신뢰도가 `INFERRED`인 `REFERENCES`)는 점선으로 그리고 라벨·DBML 주석·테이블 명세에 `추론 n건` 대신 `(upgrade 스크립트, 현재 정의에 없음)`을 적어야 한다. | sql 계약 변경 |
| F-013-R27 | 만약 추론 관계가 방향은 알지만(`directionKnown=true`) `cardinality=unknown`이면(링커가 조인만으로는 방향을 정하지 못해 같은 테이블 쌍의 DDL 외래 키 방향을 따른 것: `directionFrom=DDL`, F-018-R15), 시스템은 자식→부모 방향을 지키되 R4의 규칙으로 N:1·1:1을 계산해 그리지 말고 카디널리티를 모른다고 표기해야 한다. 라벨·DBML 주석·테이블 명세에는 `(추론 n건, 방향은 DDL, 카디널리티 모름)`을 적고, Mermaid는 부모를 왼쪽에 둔 양쪽 `}o..o{`, PlantUML은 카디널리티 표시 없는 점선 화살표(`부모 <.. 자식`), DBML은 자식을 앞에 둔 `<>`로 쓴다. 방향 미상(R3)의 표기와 구별돼야 하고, 그런 관계가 있는 Mermaid·PlantUML에는 머리 주석에 뜻을 한 줄 적는다. | link 계약 변경(F-018-R15), 6차 검증(공통컴포넌트의 6건이 `\|\|..o{`(N:1)로 그려짐) |
| F-013-R28 | 추론 관계(점선: `source=INFERRED`, upgrade 스크립트에만 있는 외래 키 포함)에서 방향과 카디널리티를 그릴 때(R27의 "카디널리티 모름"과 방향 미상이 아닐 때), 시스템은 부모 쪽을 "0 또는 1"로 그려야 한다: 제약이 없어 부모 행이 반드시 있는지 알 수 없다(Mermaid·PlantUML `부모 \|o..o{ 자식`, 1:1이면 `\|o..o\|`. 엣지의 `cardinality`가 `N:1`이어도 같다). DBML에는 필수 여부 표기가 없어 관계 줄은 그대로 두고 머리 주석으로 알린다. Mermaid·PlantUML의 머리 주석과 뷰어의 범례·안내에도 적는다. DDL 외래 키(실선)는 R4 그대로다(FK 컬럼이 nullable이면 부모 쪽 0..1, 아니면 필수). | 4차 재검증(추론 N:1이 `부모 \|\|..o{ 자식`으로 부모 필수처럼 그려짐) |
| F-013-R29 | 시스템은 시스템 카탈로그(`attrs.systemCatalog=true`인 `DB_TABLE`·`DB_VIEW`: DB가 제공하는 데이터 사전)를 ERD(Mermaid, PlantUML, DBML)의 엔티티로도 관계의 끝으로도 넣지 않아야 한다(이름을 직접 줘도 범위에 들지 않는다). 뺀 수는 다이어그램 머리 주석, 나눈 ERD의 `erd/index.md`, `report.md` 머리에 한 줄로 적고, 전체 테이블 명세의 끝 "시스템 카탈로그" 절에 이름만 남긴다(범위를 좁힌 명세에는 붙이지 않는다). 조회 API는 뺀 이름을 `ErdModel.systemCatalogNames()`로 준다. 옵션 키는 없다. | 4차 재검증(jeesite: 카탈로그 12개와 그 사이의 추론 관계 7건이 "(DDL 없음)"으로 그려짐) |

## 4. 수용 기준

테스트 위치: `codeatlas-export/src/test/java/io/codeatlas/export/erd/`, 표준 산출물은 `StandardExporterTest`.

### F-013-AC1 (대상: F-013-R1)
- Given 테이블 2개, 컬럼, DDL FK 1개가 든 그래프
- When `Erd.model(graph, ErdOptions.defaults())`
- Then 엔티티 2개가 이름순으로, 컬럼이 `ordinal` 순으로, 관계 1개(자식→부모, 컬럼 목록 포함)가 나온다.

### F-013-AC2 (대상: F-013-R2)
- Given 같은 그래프
- When Mermaid로 렌더링
- Then `erDiagram`으로 시작하고 컬럼 줄에 타입, 이름, `PK`/`FK`, 주석이 있고 NOT NULL이 주석에 표시된다.

### F-013-AC3 (대상: F-013-R2)
- When PlantUML로 렌더링
- Then `@startuml`…`@enduml` 사이에 `entity`가 있고 NOT NULL 컬럼은 `*`로, PK는 구분선 위에 `<<PK>>`로 나온다.

### F-013-AC4 (대상: F-013-R2)
- When DBML로 렌더링
- Then `Table`, `[pk, not null]`, `Ref` 문장이 나오고 복합 FK는 `(a, b)` 표기로 나온다.

### F-013-AC5 (대상: F-013-R3)
- Given DDL 관계 1개, 추론 관계(evidenceCount=3) 1개, 방향 미상 추론 관계 1개
- When 세 형식으로 렌더링
- Then DDL은 실선(`--`), 추론은 점선(`..`)과 `(추론 3건)` 라벨이고, 방향 미상 관계는 PlantUML에서 카디널리티 없는 `..`, Mermaid에서는 양쪽 `}o..o{`와 `방향 미상` 라벨, DBML에서는 `<>`와 줄 끝 주석이다.

### F-013-AC6 (대상: F-013-R4)
- Given FK가 자식 PK 전체인 관계, nullable FK 관계, NOT NULL 일반 FK 관계
- When 모델을 만들면
- Then 각각 (자식 0..1 : 부모 1), (자식 N : 부모 0..1), (자식 N : 부모 1)이다. 엣지에 `cardinality` 속성(`N:1`, `1:1`)이 있으면 자식 쪽은 그것을 따르고 DDL 관계의 `unknown`이면 계산한다(추론 관계의 `unknown`은 계산하지 않는다: AC30). 테이블 속성 `uniqueKeys`의 복합 UNIQUE도 1:1로 본다.

### F-013-AC7 (대상: F-013-R5)
- Given `declared=false` 테이블과 그 컬럼 하나에 대한 `READS` 컬럼 엣지
- When 렌더링
- Then 그 테이블이 `(DDL 없음)` 표식과 함께 나오고 참조된 컬럼만 들어 있다.

### F-013-AC8 (대상: F-013-R6)
- Given A→B→C로 이어진 관계와 무관한 D
- When 테이블 목록 `[A]`, 깊이 1 / 깊이 2 / glob `C*`
- Then 각각 {A,B}, {A,B,C}, {C…}만 나온다.

### F-013-AC9 (대상: F-013-R6)
- Given 추론 관계(evidence 1, 3), 뷰 1개
- When `includeInferred(false)`, `minEvidence(2)`, `includeViews(true)`, 컬럼 수준 `KEYS`/`NONE`
- Then 각 옵션대로 관계·뷰·컬럼이 걸러진다.

### F-013-AC10 (대상: F-013-R7)
- Given 한글 테이블명, `$`·`#`·공백이 든 이름, 타입 `NUMBER(10,2)`, `VARCHAR2(20 CHAR)`, `TIMESTAMP WITH TIME ZONE`
- When 세 형식으로 렌더링
- Then Mermaid 출력이 erDiagram 문법 검증기를 통과하고, PlantUML 별칭과 DBML 따옴표 처리가 규칙대로다.

### F-013-AC11 (대상: F-013-R8)
- Given 같은 내용을 서로 다른 순서로 넣은 두 그래프
- When 세 형식으로 렌더링
- Then 출력이 같다.

### F-013-AC12 (대상: F-013-R9)
- Given 속성이 전혀 없는 컬럼, `columns` 없는 `REFERENCES`, 한쪽 끝 노드가 없는 `REFERENCES`
- When 렌더링
- Then 예외가 없고 타입은 `unknown`으로, 컬럼 없는 관계는 테이블 사이의 선으로만 나온다.

### F-013-AC13 (대상: F-013-R10)
- When `TableSpecRenderer.render(graph)`
- Then 테이블별 절에 컬럼 표, PK, 나가는·들어오는 관계, 참조하는 구문 수가 있다.

### F-013-AC14 (대상: F-013-R11)
- When `StandardExporter.export`
- Then `erd/` 아래 네 파일이 생기고, `export.erd=false`면 생기지 않는다.

### F-013-AC15 (대상: F-013-R12)
- Given 테이블과 뷰가 없는 그래프(빈 그래프, Java 타입만 있는 그래프)
- When `StandardExporter.export`
- Then `erd/` 아래에 파일이 없다. 뷰만 있는 그래프에서는 쓴다. (`report.md`에 남는 줄은 F-015-AC19)

### F-013-AC16 (대상: F-013-R13)
- Given A→B→C로 이어진 관계와 무관한 D, 뷰 하나
- When `TableSpecRenderer.render(graph, options)`를 테이블 목록 `[A]`·깊이 0/1/2, glob, 뷰 포함 여부를 바꿔 부르면
- Then 명세의 절이 ERD의 같은 옵션과 같은 테이블로만 이뤄지고, 범위를 좁혔으면 머리에 범위와 "범위 밖 테이블과의 관계는 적지 않는다"가 적힌다. 뷰는 `includeViews`를 줘야 든다. 컬럼 표시 수준은 적용하지 않는다. `render(graph)`는 전처럼 전체 테이블과 뷰다.

### F-013-AC17 (대상: F-013-R14)
- Given 시작 테이블 이름 여럿(있는 것, 없는 이름, 없는 노드 ID, 뷰 이름, 빈 문자열, 중복)
- When `Erd.model`을 만들면
- Then `unresolvedNames()`에 찾지 못한 이름이 준 순서대로, 원문 그대로, 중복 없이 들어 있고 `excludedViewNames()`에는 그중 뷰의 이름만 있다. `includeViews(true)`면 뷰 이름은 찾은 것이다. 범위가 전체이거나 패턴뿐이면 둘 다 비어 있다.

### F-013-AC18 (대상: F-013-R15)
- Given 접두사가 `SYS_`·`CMS_`인 테이블 12개씩, `ACT_RU_`·`ACT_HI_` 6개씩, 접두사가 제각각인 테이블 둘, 영역 사이의 FK 넷
- When `export.mermaid.maxChars=12000`으로 표준 산출물을 쓰면
- Then `erd/erd-ACT.mmd`, `erd-CMS.mmd`, `erd-SYS.mmd`, `erd-etc.mmd`, `erd-overview.mmd`, `index.md`가 생기고 각 다이어그램이 한도 안이며 문법 검증기를 통과한다. 영역 다이어그램에는 그 영역의 테이블이 컬럼까지, 관계로 이어진 다른 영역의 테이블이 이름만 나온다. 개요에는 전체 테이블이 키 컬럼만으로 나온다. `index.md`에 영역별 테이블 수·영역 안 관계 수·다른 영역과의 관계 수·파일과 영역 쌍별 관계 수가 있다. `erd.mmd`(전체)는 그대로 쓰되 첫 줄 아래에 한도를 넘는다는 주석이 붙는다. `erd.puml`·`erd.dbml`은 그대로다.
- 한도를 5400으로 줄이면 한도를 넘는 영역이 둘째 토큰까지로 나뉘고(`ACT_HI`, `ACT_RU`), 더 나눌 토큰이 없으면 이름순 조각(`SYS-1`, `SYS-2`)이 되며, 개요는 컬럼 없이 그린다. 한도 안이면(기본값) 나누지 않고 `index.md`도 없다. `0`이면 한도를 보지 않는다. 입력 순서를 뒤집어도 같다.
- 테스트: `LargeArtifactSplitTest.splitsErdBySubjectArea`.

### F-013-AC19 (대상: F-013-R16)
- Then 데이터 적재 구문이 쓰는 테이블의 절에 `- 참조하는 구문: 1개 (읽기 1, 쓰기 0)` 다음 줄로 `- 데이터 적재: 구문 1개, 120행`이 나온다. `TableSpecRenderer.render(graph, options, true)`면 구문 수에 넣고 그 줄은 없다.
- 테스트: `DataLoadTest`(F-015-AC34와 같은 테스트).

### F-013-AC20 (대상: F-013-R17)
- Given 밑줄 없는 이름의 테이블 40개: `COMTNBBS`·`COMTNUSER`를 중심으로 한 별 모양 둘(잎 5개씩, `COMTNBBS → COMTNUSER`), 잎 9개가 참조하는 `COMTCCODE`, 관계가 없는 테이블 27개
- Then 한도 5500에서 `erd-COMTNBBS.mmd`, `erd-COMTNUSER.mmd`, `erd-etc.mmd`가 생기고(이어진 12개가 한도를 넘어 중심 둘로 나뉜다) `ALL-n`은 없다. 허브 `COMTCCODE`와 다른 영역의 테이블은 이름만 나온다. `index.md`에 영역별 수치, `| COMTNBBS | COMTNUSER | 1 |`, "## 허브 테이블"의 `| COMTCCODE | 9 |`, 관계로 묶었다는 안내가 있다. 한도 7000이면 12개가 한 영역 `COMTNUSER`다. 입력 순서를 뒤집어도 같다. 접두사 영역(`SYS_` 4개)이 함께 있으면 그 영역은 그대로이고 나머지만 관계로 묶는다.
- 테스트: `LargeArtifactSplitTest.splitsErdByRelationClusters`, `clustersLeftoverTablesBesidePrefixAreas`.

### F-013-AC21 (대상: F-013-R18)
- Then 라벨이 `ID (추론 2건, 방향 미상: 선언된 FK와 반대)`, `ID (추론 3건, 방향 미상: 유일하지 않은 키)`이고 까닭이 없으면 전처럼 `방향 미상`이다. 테스트: `ErdTest.labelsWhyDirectionIsUnknown`.

### F-013-AC22 (대상: F-013-R19)
- Then 그 컬럼이 `erd/erd.mmd`에 없고 `erd/tables.md`의 테이블 아래에 ``- `SUB_DN` — 구문 1개: `UserMapper.selectUser` ``로 나온다. 테스트: `StandardExporterTest.undeclaredColumnsOfDeclaredTables`.

### F-013-AC23 (대상: F-013-R20)
- Then `fromUpgrade=true`인 DDL 관계의 라벨이 `FK_ORDERS_COUPON (upgrade 스크립트)`(제약 이름이 없으면 `STORE_ID (upgrade 스크립트)`)이고 실선이며, 테이블 명세의 양쪽 관계 줄 끝에 `(upgrade 스크립트)`, DBML의 `Ref` 줄 끝에 `// upgrade 스크립트`가 붙는다. 표시가 없는 관계는 전과 같다. 테스트: `ErdTest.marksForeignKeysFromUpgradeScripts`. 고정 값(`fixedValues`)은 표준 산출물이 적지 않는다(뷰어만 적는다. 첫 `=`에서만 자르는 것은 F-016-AC67이 고정한다).

### F-013-AC24 (대상: F-013-R21)
- Given AC20의 스키마(별 둘 `COMTNBBS`·`COMTNUSER`, 잎 9개가 참조하는 허브 `COMTCCODE`), 한도 5500
- Then 영역 표의 머리가 `| 영역 | 테이블 수 | 영역 안 관계 | 다른 영역과의 관계 | 허브와의 관계 | 파일 | 글자 수 |`이고 `| COMTNBBS | 6 | 5 | 1 | 4 |`, `| COMTNUSER | 6 | 5 | 1 | 5 |`다. "다른 영역과의 관계"의 합(2)이 "영역 간 관계"의 합(1)의 두 배다. 허브가 없으면(AC18의 스키마) 표의 모양은 전과 같다.
- 테스트: `LargeArtifactSplitTest.countsHubRelationsApartFromOtherAreas`, 뷰어 `erd-areas.test.js`.

### F-013-AC25 (대상: F-013-R22)
- Given 관계가 없는 밑줄 없는 이름의 테이블 15개(`COMTNBBSA~C`, `COMTNSMS`·`COMTNSMSLOG`·`COMTNSMSRECPTN`, `COMTSC1~3`, `COMTSA`, `COMTSB`, `COMTNX`, `COMTHLOG`, `ROLES`, `USERS`)
- Then 영역이 `COMT*`(`COMTHLOG`, `COMTNX`, `COMTSA`, `COMTSB`: 더 긴 접두사로 묶이지 않고 남은 것), `COMTNBBS*`, `COMTNSMS*`, `COMTSC*`, `기타`(`ROLES`, `USERS`)이고 파일은 `erd-COMT.mmd`처럼 `*` 없이 쓴다. `index.md`에 이름의 공통 접두사로 묶었다는 안내가 있다. 접두사가 3자에 못 미치면(`ABX`, `ABY`, `ABZ`) 묶지 않고, 접두사·관계·이름 어느 것으로도 묶이지 않으면 전처럼 `ALL`이다. 군집이 30개를 넘으면 이름순 조각(`COMTNLOG*-1`, `COMTNLOG*-2`)이다. 입력 순서를 뒤집어도 같다.
- 테스트: `LargeArtifactSplitTest.clustersLeftoverTablesByNamePrefix`.

### F-013-AC26 (대상: F-013-R23)
- Given `COMTNUSER`를 6개 테이블이 참조하는 33개짜리 관계 묶음(`COMTNUSER`를 빼면 사슬 12·12·4개와 1·1·2개 조각으로 갈린다)과 접두사 영역 `SYS_` 12개
- Then 관계 영역이 `COMTNBBS`(12), `COMTNFILE`(12), `COMTNMENU`(4), `COMTNUSER`(5: 중심 + 3개 미만 조각)이고 모두 30개 이하다. `erd-COMTNBBS.mmd`에 `COMTNUSER`가 이름만 나온다. `index.md`에 "## 중심 테이블"의 `| COMTNUSER | 6 | …COMTNBBS, COMTNFILE, COMTNMENU |`와 영역 간 관계 3건이 있다. 중심을 빼도 갈리지 않는 묶음(고리 40개, 사슬 70개)은 서로 먼 테이블들을 중심으로 나눠 30개 이하로 고르게 나뉜다(사슬 70개 → 9·17·26·18개). 입력 순서를 뒤집어도 같다.
- 테스트: `LargeArtifactSplitTest.splitsLargeRelationAreaAroundCenterTable`.

### F-013-AC27 (대상: F-013-R24)
- Then AC26의 스키마에서 논리명이 있는 `COMTNUSER`(사용자), `COMTNBBS`(`게시판`+줄바꿈+`|마스터`)의 영역이 `사용자(COMTNUSER)`, `게시판 \|마스터(COMTNBBS)`로(표 안이라 `|`는 `\|`), 논리명이 없는 영역과 접두사 영역은 이름 그대로 나온다. 영역 간 관계와 중심 테이블의 표에도 같은 이름이 나온다. 파일은 `erd-COMTNUSER.mmd`다. 40자를 넘는 논리명은 40자에서 자르고 `…`를 붙인다.
- 테스트: `LargeArtifactSplitTest.labelsRelationAreasWithLogicalName`.

### F-013-AC28 (대상: F-013-R25)
- Given A→B→C와 무관한 D, 뷰 V_A
- Then 테이블 `[C_T, A_T]`·깊이 1의 명세가 `C_T`, `A_T`, `B_T` 순이다. 대소문자를 달리 적거나 노드 ID로 줘도, 같은 이름을 두 번 줘도, 찾지 못한 이름이 섞여도 같다. 패턴을 함께 주면 시작 테이블이 앞이고 나머지는 이름순이다. 범위가 전체이거나 패턴뿐이면 이름순이다. ERD 모델(다이어그램)의 순서는 그대로 이름순이다.
- 테스트: `ErdTest.putsStartTablesFirstInTableSpec`.

### F-013-AC29 (대상: F-013-R26)
- Given `fromUpgrade=true`인 `REFERENCES` 셋: `source=INFERRED`(제약 이름 있음), `source` 없이 엣지 신뢰도만 `INFERRED`(제약 이름 없음), `source=DDL`인데 엣지 신뢰도가 `INFERRED`(sql 분석기가 실제로 내는 모양. `note="현재 정의에 없음"`이 함께 온다)
- Then 셋 다 점선이고 라벨이 `FK_ORDERS_COUPON (upgrade 스크립트, 현재 정의에 없음)`, `STORE_ID (upgrade 스크립트, 현재 정의에 없음)`이며 `추론 n건`은 없다. 그 컬럼에 `FK` 표시가 없다. 테이블 명세의 양쪽 관계 줄과 DBML의 줄 끝 주석에 같은 문구가 있다. 최소 근거 수 옵션에 걸리지 않고, 추론 관계를 빼면 함께 빠진다. 같은 컬럼의 외래 키가 현재 정의에도 있으면 그것만 그린다.
- 테스트: `ErdTest.marksUpgradeForeignKeysMissingFromCurrentDefinition`, 뷰어 `erd.test.js`.

### F-013-AC30 (대상: F-013-R27)
- Given 부모 PK가 `(NTT_ID, BBS_ID)`이고 DDL FK도 그 둘인 `COMMENT_T → BBS`에 추론 관계(`columns=[NTT_ID]`, `directionKnown=true`, `directionFrom=DDL`, `cardinality=unknown`, 근거 2건), 견줄 것으로 방향 미상 추론 관계(`LOG_A`–`LOG_B`)와 확정된 `N:1` 추론 관계(`SCRAP → COMMENT_T`)
- Then 모델의 그 관계는 `directionKnown=true`, 자식·부모 쪽 모두 `UNKNOWN`, `cardinalityUnknown()=true`, `directionFrom=DDL`이다. Mermaid는 `BBS }o..o{ COMMENT_T : "NTT_ID (추론 2건, 방향은 DDL, 카디널리티 모름)"`이고(`||..o{`가 아니다) 머리 주석에 `%% '카디널리티 모름'은 방향(왼쪽이 부모)만 DDL 외래 키로 확인하고 …` 한 줄이 붙는다. PlantUML은 `BBS <.. COMMENT_T : NTT_ID (추론 2건, 방향은 DDL, 카디널리티 모름)`과 머리 주석 한 줄, DBML은 `Ref: COMMENT_T.NTT_ID <> BBS.NTT_ID // 추론 2건, 방향은 DDL, 카디널리티 모름`, 테이블 명세는 양쪽 테이블에 `- → BBS (NTT_ID → NTT_ID) (추론 2건, 방향은 DDL, 카디널리티 모름)`·`- ← COMMENT_T (…)`다. 방향 미상(`LOG_B }o..o{ LOG_A : "TX_ID (추론 2건, 방향 미상)"`, PlantUML `..`, DBML `<>`와 `방향 미상` 주석)과 확정된 N:1(`||..o{`, DBML `>`)은 전과 같다. 그런 관계가 없는 그래프의 출력은 머리 주석까지 전과 같다.
- `directionFrom`이 없어도 추론 관계의 `cardinality=unknown`은 계산하지 않고 라벨은 `(추론 1건, 카디널리티 모름)`이다. `directionFrom=DDL`이어도 `cardinality=1:1`이면 `||..o|`와 `(추론 1건)` 그대로다.
- 테스트: `ErdTest.drawsDirectionWithoutCardinalityWhenOnlyDirectionIsKnown`, `ErdTest.keepsKnownCardinalityAndTreatsAnyInferredUnknownAsUnknown`, 뷰어 `erd-direction-only.test.js`(같은 그래프로 Mermaid 글자가 같은지, 영역의 글자 수, 화면의 범례·요약).

### F-013-AC31 (대상: F-013-R28)
- Given DDL 외래 키 둘(FK 컬럼이 NOT NULL인 `ORDERS → CUSTOMER`, nullable인 `ORDERS → COUPON`)과 추론 관계 셋(NOT NULL 컬럼의 `ORDER_HIST → ORDERS`, 자식의 PK 전체인 `ORDER_EXT → ORDERS`, `cardinality=N:1`인 `PAY → ORDERS`), 방향 미상 관계 하나
- Then 모델의 추론 관계는 부모 쪽이 `ZERO_OR_ONE`이고, Mermaid는 `ORDERS |o..o{ ORDER_HIST`, `ORDERS |o..o| ORDER_EXT`, `ORDERS |o..o{ PAY`, `CUSTOMER ||--o{ ORDERS`, `COUPON |o--o{ ORDERS`이며 `||..`는 없다. 방향 미상은 전처럼 `}o..o{`다. 머리 주석은 `%% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. 추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.`이고 PlantUML도 같은 표기·같은 문장이다. DBML은 `Ref: ORDER_HIST.ORDER_ID > ORDERS.ORDER_ID // 추론 2건` 그대로이고 추론 관계가 있을 때만 머리에 `// 추론 관계(줄 끝 주석 '추론')는 제약이 없어 부모가 반드시 있는지 알 수 없다.`가 있다.
- 테스트: `ErdRound7Test.inferredRelationsDoNotClaimMandatoryParent`, `explicitCardinalityDoesNotMakeInferredParentMandatory`. 뷰어 `round7.test.js`의 `F-016-AC79`.
- 기대값을 바꾼 기존 테스트(추론 관계의 `||..` → `|o..`, 머리 주석): `ErdTest.distinguishesInferredRelations`(AC5), `marksUpgradeForeignKeysMissingFromCurrentDefinition`(AC29), `drawsDirectionWithoutCardinalityWhenOnlyDirectionIsKnown`·`keepsKnownCardinalityAndTreatsAnyInferredUnknownAsUnknown`(AC30), `StandardExporterTest.writesErd`.

### F-013-AC32 (대상: F-013-R29)
- Given `systemCatalog=true`인 DDL 없는 테이블 둘(`USER_TABLES`, `USER_TAB_COLUMNS`)과 그 사이의 추론 관계, `systemCatalog`가 없는 DDL 없는 테이블 `TMP_GHOST`
- Then 모델의 엔티티·관계에 카탈로그가 없고 `systemCatalogNames()`는 그 둘이다. 세 형식 모두 `USER_TABLES`가 없고 `TMP_GHOST`는 있으며 머리 주석에 `시스템 카탈로그 2개(DB가 제공하는 데이터 사전)는 프로젝트 스키마가 아니라서 뺐다.`가 있다(Mermaid의 엔티티·관계 수도 뺀 수다). 카탈로그가 없으면 그 줄이 없다. `tables("USER_TABLES")`로 줘도 엔티티가 없고 `unresolvedNames()`에 들어 있다.
- 테스트: `ErdRound7Test.systemCatalogsAreNotDrawn`. 뷰어 `round7.test.js`의 `F-016-AC80`.

### F-013-AC33 (대상: F-013-R29)
- Then 전체 테이블 명세의 머리에 `- 시스템 카탈로그 2개는 명세와 ERD에서 뺐다(아래 "시스템 카탈로그" 절에 이름만 적는다)`가 있고, 테이블 수는 카탈로그를 뺀 수이며, `## USER_TABLES` 절은 없고 끝에 `## 시스템 카탈로그 (2)` 절이 이름 둘만 적는다. 범위를 좁힌 명세와 카탈로그가 없는 그래프의 명세에는 없다. `report.md` 머리에 `- 시스템 카탈로그 2개는 ERD에서 제외(이름은 `erd/tables.md`의 "시스템 카탈로그" 절)`, 나눈 ERD의 `erd/index.md`에 `- 시스템 카탈로그 2개는 ERD에서 제외했다(…)`가 있고 `erd-overview.mmd`에 카탈로그가 없다.
- 테스트: `ErdRound7Test.systemCatalogsAreListedByNameOnly`.

## 5. 가정
- 7차 수정(2026-10-11, 4차 재검증 후속. R28·R29):
  - 추론 관계의 부모 쪽(R28): "점선이면 부모 쪽 0..1"로 단순하게 뒀다. 자식 컬럼이 NOT NULL이면 값은 반드시 있지만 그 값의 부모 행이 있다는 보장(제약)이 없다. upgrade 스크립트에만 있는 외래 키(R26)도 점선이라 같은 규칙이다(현재 정의에 제약이 없다). 엣지의 `cardinality`가 `N:1`·`1:1`을 말해도 부모 쪽은 0..1이고 자식 쪽만 그 값을 따른다. DBML은 `>`·`-`·`<>`뿐이라 관계 줄을 바꾸지 않았다.
  - `samples/sample-shop/expected/erd-expected.md`의 Mermaid는 추론 관계를 `||..o{`로 적어 두었다(사람이 보는 기대 그림. 기계 대조는 관계의 쌍과 방향만 본다). 샘플은 고치지 않았으므로 표기가 산출물(`|o..o{`)과 다르다.
  - 시스템 카탈로그(R29): 모델을 만드는 단계에서 뺀다(범위·이웃·관계·영역 나누기가 모두 뺀 뒤의 것을 본다). 이름을 직접 줘도 그리지 않는 것은 "프로젝트 스키마가 아니다"를 일관되게 지키려는 것이다(`erd --table USER_TABLES`는 "테이블을 찾지 못했다"가 된다). `report.md`의 "DDL에 없는 테이블" 절은 전처럼 카탈로그를 뒤에 따로 묶어 구문 수와 함께 싣는다(F-015-R60). 카탈로그 판정은 sql의 `systemCatalog` attr만 본다(이름으로 짐작하지 않는다).
- 방향만 아는 추론 관계(R27, 2026-10-11 6차). 사용자 검토가 필요하다.
  - 판정: 추론 관계(`inferred`)이고 방향을 알며 엣지의 `cardinality`가 `unknown`(대소문자 무시)인 것. `directionFrom`은 라벨의 "방향은 DDL"에만 쓴다(값이 `DDL`일 때). 실제 그래프에서 이 조합은 `directionFrom=DDL`로만 온다(F-018-R15). `cardinality` 속성이 아예 없는 추론 관계(옛 그래프)와 DDL 관계는 전처럼 R4로 계산한다. 그래서 "추론 관계의 `unknown`이면 계산한다"던 AC6의 한 줄이 바뀌었다(테스트의 그 경우를 DDL 관계로 옮겼다).
  - 왜 계산하지 않는가: R4의 계산은 "부모 쪽은 유일하다"(외래 키)를 전제로 자식 컬럼만 본다. 이 관계는 부모 쪽 조인 컬럼이 키의 일부일 뿐이라(`COMTNBBS`의 PK는 `NTT_ID, BBS_ID`인데 조인은 `NTT_ID`만) 그 전제가 없다. `||..o{`로 그리면 모르는 것을 N:1로 확정해 그리게 된다.
  - 표기를 고른 까닭. Mermaid erDiagram에는 카디널리티 없는 선도 화살표도 없다: 다른 관계처럼 부모를 왼쪽에 두고 끝 표시는 어느 쪽도 "하나"라고 하지 않는 가장 약한 `}o`·`o{`(0개 이상)로 둔다. 방향 미상도 같은 끝 표시라서 둘은 라벨(`방향 미상` / `방향은 DDL, 카디널리티 모름`)로 갈리고, `}o..o{`가 N:M으로 읽히지 않게 머리 주석에 한 줄을 적는다(주석에는 중괄호를 쓰지 않는다). PlantUML은 화살표가 있어 카디널리티 없이 방향만 그린다(`부모 <.. 자식`: 화살촉이 부모 쪽). DBML은 `>`가 N:1이라는 뜻이라 쓰지 않고 `<>`와 줄 끝 주석으로 적는다(방향은 자식을 앞에 둔 순서와 주석으로만 남는다).
  - 머리 주석은 그런 관계가 그 다이어그램에 있을 때만 붙는다(영역 다이어그램은 영역마다 따로 본다). 그런 관계가 없는 프로젝트의 산출물은 바이트까지 전과 같다. 글자 수 한도(R15)를 재는 글자에 이 줄도 든다. `erd/index.md`에는 범례를 적지 않는다(각 `.mmd`의 머리 주석이 범례다).
  - 뷰어(F-016)도 같은 규칙이다: 표준 산출물의 Mermaid 글자·영역의 글자 수가 같고, 화면의 ERD는 끝 표시를 지우지 않은 `}o..o{`와 같은 라벨로 그리며(방향 미상은 전처럼 끝 표시를 지운다) 범례에 "추론 관계 (카디널리티 모름)" 항목이 있다.
  - 실제 그래프(공통컴포넌트): 6건(`COMTNCOMMENT → COMTNBBS` 등)이 `||..o{ … (추론 n건)`에서 `}o..o{ … (추론 n건, 방향은 DDL, 카디널리티 모름)`으로 바뀌었다. jeesite·zheng·sample-shop·enterprise·jpetstore의 `erd/`는 바이트까지 전과 같다.
- 영역 나누기의 5차 수정(R21~R24, 2026-10-11). 순서는 접두사 영역(R15) → 허브 빼기와 관계 묶기(R17) → 큰 묶음 나누기 → 이름 접두 군집 → `기타` → 이름순 조각이다. 뷰어(F-016)도 같은 규칙이고, 같은 그래프에서 `erd/index.md`·`erd-<영역>.mmd`와 글자까지 같다.
  - 큰 묶음 나누기(R23): 관계 묶음(연결 요소)이 테이블 30개(`ErdSplitter.MAX_AREA_TABLES`)를 넘거나 글자 수 한도를 넘으면, 그 안에서 가장 많이 참조되는 테이블(같으면 이름순)을 **중심 테이블**로 삼아 빼고 나머지를 다시 연결 요소로 묶는다. 3개 이상인 조각은 저마다 영역이 되고(이름은 그 조각에서 가장 많이 참조되는 테이블. 아직 한도를 넘으면 같은 방법으로 또 나눈다), 3개 미만인 조각은 중심과 함께 **중심 이름의 영역**이 된다. 허브와 달리 중심은 영역 안에 있어 컬럼까지 나오고, 그것을 참조하는 다른 영역에는 (다른 영역의 테이블이 다 그렇듯) 이름만 나온다. 중심의 영역은 테이블이 3개 미만이어도 영역이다(중심 하나와 이름만 있는 이웃들). 중심과 다른 영역 사이의 관계는 "영역 간 관계"로 센다.
  - 중심을 빼도 갈리지 않으면(가장 큰 조각이 묶음의 3/4을 넘으면: 고리, 사슬의 끝, 촘촘한 덩어리) 중심 k개에 가까운 테이블끼리 나눈다(R17의 방법). 첫 중심은 가장 많이 참조되는 테이블이고 다음 중심은 이미 고른 중심들에서 관계로 가장 먼 테이블이다(같으면 많이 참조되는 순, 이름순). 3차까지는 많이 참조되는 순으로만 골랐는데, 사슬에서는 이웃한 테이블들이 중심이 돼 낱개로 흩어져서 바꿨다. 이때 생긴 영역은 `index.md`의 "중심 테이블"에 적지 않는다.
  - 중심의 영역 자체가 한도를 넘으면(잎이 30개가 넘는 별) 이름순 조각(`<중심>-1`, `<중심>-2`)으로 자른다. 중심은 한 조각에 있고 다른 조각에는 이름만 나온다.
  - 이름 접두 군집(R22): 이름을 대문자로 바꿔 한 글자씩 따라 내려가며(밑줄도 한 글자다), 가장 깊은(긴) 접두사부터 묶는다. 어떤 접두사를 가진 테이블 가운데 더 긴 접두사로 묶이지 않고 남은 것이 3개 이상이고 접두사가 3자(`MIN_NAME_PREFIX`) 이상이면 그것들이 한 영역이고 이름은 `<접두사>*`다. 그래서 `COMTNNTWRK*`와 `COMTNN*`이 함께 있으면 `COMTNN*`에는 `COMTNNTWRK…`가 없다. 30개를 넘는 군집은 이름순 조각이다. 관계가 둘 사이에만 있는 테이블 쌍은 관계 묶음이 못 되고(3개 미만) 이름으로 갈릴 수 있다: 그 관계는 "영역 간 관계"로 나온다.
  - 30개 상한은 관계로 묶은 영역과 이름 접두 군집에만 건다. 접두사 영역(R15)과 `기타`, `ALL`은 전처럼 글자 수 한도만 본다(접두사가 잘 나오는 프로젝트의 결과를 바꾸지 않으려고). 사용자 검토가 필요하다.
  - 허브와의 관계(R21): 영역의 "다른 영역과의 관계"는 한쪽 끝이 다른 영역인 관계이고, 다른 끝이 허브인 관계는 "허브와의 관계"다. 허브가 없으면 그 열을 쓰지 않아 접두사 영역뿐인 프로젝트의 `index.md`는 전과 같다.
  - 표시 이름(R24): 영역 이름이 된 테이블(관계 묶음에서 가장 많이 참조되는 테이블, 중심 테이블)의 `comment`를 한 줄로 만들어 `논리명(영역 이름)`으로 적는다(40자에서 자른다). 이름순 조각이면 `논리명(COMTNUSER-2)`다. 접두사 영역, 이름 접두 군집, `기타`에는 논리명이 없다. 영역의 정렬과 파일 이름은 물리명이다. 이름 접두 군집의 파일 이름에는 `*`를 넣지 않는다(`erd-COMTNSMS.mmd`. 다른 영역과 겹치면 `_2`).
  - 실제 그래프(공통컴포넌트, 테이블 191개): 영역 10개(가장 큰 것 `기타` 88개, `COMTNEMPLYRINFO` 68개) → 30개(가장 큰 것 `COMTNFILE` 30개, 중심 `COMTNEMPLYRINFO`의 영역 15개, 이름 접두 군집 14개, `기타` 14개). 접두사 영역뿐인 jeesite·zheng은 기본 한도에서 나뉘지 않아 그대로이고, 한도를 낮추면 jeesite의 `기타` 6개 가운데 `ACT_`로 시작하는 4개가 `ACT_*`가 된다.
- 테이블 명세의 순서(R25): 시작 테이블은 ERD의 범위와 같은 규칙(노드 ID, 이름, 대소문자 무시)으로 찾는다. 한 이름이 여러 테이블에 맞으면 그 테이블들이 이름순으로 이어진다.
- 현재 정의에 없는 upgrade 외래 키(R26): 추론 관계와 같이 다룬다(점선, 컬럼에 `FK` 표시 없음, 같은 컬럼의 DDL 관계가 있으면 그리지 않음, `includeInferred=false`면 빠짐). 다만 SQL에서 추론한 것이 아니라서 최소 근거 수(`minEvidence`)로는 거르지 않고, 라벨의 이름은 제약 이름(없으면 컬럼)이다. `source=DDL`이어도 `fromUpgrade=true`이고 엣지 신뢰도가 `INFERRED`면 이 경우로 본다. 엣지의 `note` 속성은 읽지 않는다. Mermaid 머리 주석의 `추론 n개`에는 이 관계도 든다.
- 관계로 묶기(R17, 2026-10-11 3차): 관계는 ERD 모델의 관계(옵션대로 추론 관계 포함)이고 방향을 가리지 않고 이웃으로 본다. "참조하는 테이블 수"는 그 테이블을 부모로 하는 관계의 서로 다른 자식 수다(자기 참조 제외). 허브 판정은 접두사로 묶이지 않은 테이블만 대상으로 하고 전체 테이블 수에 견준다. 큰 요소는 중심 k개(요소 안에서 많이 참조되는 순, 같으면 이름순)를 정해 테이블마다 관계를 따라 가장 가까운 중심(같으면 순위가 앞선 중심)에 붙이고, 모든 조각이 한도 안에 드는 가장 작은 k를 쓴다. 3개 미만인 요소·조각은 `기타`다. 관계로 묶은 영역의 이름이 접두사 영역과 겹치면 `-2`를 붙인다. 접두사로도 관계로도 묶이는 것이 하나도 없을 때만 전처럼 `ALL`이다. 허브는 영역의 테이블 수에 들지 않는다(영역의 테이블 수 합 + 허브 수 = 전체). 뷰어(F-016)도 같은 규칙이다. 5차에서 바뀐 것(큰 요소는 먼저 중심 테이블을 빼고 다시 묶고, 중심 k개는 서로 먼 테이블로 고르며, 3개 미만으로 남은 것은 `기타`로 가기 전에 이름 접두로 묶는다)은 위 "영역 나누기의 5차 수정"에 적었다.
- 테이블 명세에 범위를 주면 범위 밖 테이블과의 관계는 적지 않는다(ERD와 같은 모델을 쓴다). 그 테이블의 FK 표시(`FK`)는 컬럼 표에 그대로 남는다.
- 표준 산출물 `erd/tables.md`는 전처럼 전체 테이블과 뷰가 대상이다(`export.erd.views`와 무관). CLI `erd --spec`은 ERD와 같이 뷰를 기본으로 빼므로 둘의 대상이 다르다. 사용자 검토가 필요하다.
- 테이블 명세의 정의 위치는 다른 산출물과 같이 `file:line`으로 적는다.
- Mermaid erDiagram은 카디널리티 없는 선을 그릴 수 없다. 방향 미상 관계는 양쪽 `}o`/`o{`(제약 없음)와 `방향 미상` 라벨로 대신한다(방향만 아는 관계도 같은 끝 표시에 다른 라벨이다: R27). DBML도 선 모양 구분이 없어 추론 관계는 `Ref` 줄 끝 주석(`// 추론 n건`)으로 구분하고 방향 미상은 `<>`로 쓴다. 컬럼을 모르는 관계는 DBML에서 `// Ref(컬럼 미상): A > B` 주석으로 남긴다.
- Mermaid의 타입 단어에는 쉼표와 공백을 쓸 수 없어 `_`로 바꾼다(`NUMBER(10,2)` → `NUMBER(10_2)`). PlantUML은 원문을 유지하고, DBML은 `이름` 또는 `이름(숫자[,숫자])` 꼴이 아니면 큰따옴표로 감싼다.
- Mermaid 엔티티명이 `[A-Za-z_][A-Za-z0-9_]*`가 아니면 큰따옴표 이름을 쓴다(Mermaid 9.2 이상). 컬럼명은 안전한 문자로 바꾸고 원래 이름을 주석에 남긴다(남는 글자가 없으면 `col<순번>`). DDL에 없는 테이블은 이름 뒤에 ` (DDL 없음)`, 뷰는 Mermaid에서 ` (VIEW)`를 붙인다(PlantUML은 `<<undeclared>>`, `<<view>>` 스테레오타입, DBML은 note).
- 엣지 `cardinality` 속성(링커 확정: `N:1`, `1:1`, `unknown`)이 있으면 자식 쪽은 그것을 따른다. 없거나 DDL 관계의 `unknown`이면 R4의 규칙으로 계산한다. 추론 관계의 `unknown`은 계산하지 않는다(R27). 자식 쪽의 "1"은 0..1로 그린다(부모마다 자식이 없을 수 있다). 부모 쪽은 FK 컬럼이 nullable이면 0..1이다.
- PK와 UNIQUE는 컬럼 속성 `primaryKey`, `unique`와 테이블 속성 `primaryKey`(List), `uniqueKeys`(List, 원소 `COL1,COL2`)에서 읽는다.
- `directionKnown`이 없으면 true로 본다. 추론 여부는 `attrs.source=INFERRED`(없으면 엣지 신뢰도 `INFERRED`)로 판단한다. `evidenceCount`가 없으면 라벨은 `(추론)`이고 최소 근거 수 판단에서는 1로 본다.
- 같은 테이블 쌍·같은 컬럼에 DDL 관계와 추론 관계가 모두 있으면 DDL 관계만 그린다.
- 이웃 깊이는 테이블 목록으로 준 시작 테이블에만 적용한다. 이름 패턴에 맞은 테이블은 넓히지 않는다. 이웃 깊이의 기본값은 1이다.
- 컬럼 수준 `KEYS`는 PK, FK, 그리고 그려지는 관계의 양 끝 컬럼이다.
- 뷰는 기본으로 넣지 않는다(링커가 `db:table:X`를 `db:view:X`로 합치므로 뷰를 넣으면 뷰가 끝점인 관계도 나온다). 주석 표시는 기본으로 켠다.
- 테이블 명세의 "참조하는 구문 수"는 테이블 또는 그 컬럼으로 `READS`/`WRITES`가 있는 구문의 수다.
- 생성한 Mermaid는 두 가지로 확인했다. (1) 단위 테스트: mermaid jison 문법의 토큰 규칙을 옮긴 자체 검증기(테스트 코드 `MermaidSyntax`). (2) 수동: 테스트가 만든 텍스트 전부와 샘플 프로젝트 산출물을 실제 mermaid 11.17.2, 12.0.0의 `mermaid.parse`로 확인했다(`codeatlas-export/src/test/resources/mermaid-check.cjs`, 뷰어 모듈 작업용으로 임시 폴더에 있던 번들 사용). PlantUML과 DBML은 실제 파서로 확인하지 못했다.

- ERD 분할(R15): 영역은 테이블 이름을 `_`로 나눈 첫 토큰(대문자)이다. 토큰이 하나뿐인 이름은 그 이름이 영역이다(마지막 토큰은 접두사로 쓰지 않는다). 한 영역의 다이어그램이 한도를 넘으면 둘째 토큰까지로 다시 묶고, 테이블이 3개 미만인 묶음은 `기타`(파일 `erd-etc.mmd`)로 합치고, 그래도 한도를 넘는 묶음은 이름순으로 가장 적은 수의 조각으로 자른다(`<영역>-1`, `<영역>-2`…). 접두사로 묶이는 테이블이 하나도 없으면(밑줄 없는 이름: 전자정부 공통컴포넌트의 `COMTN…`) 전체를 `ALL`로 보고 이름순으로 자른다. 영역 다이어그램의 관계는 한쪽 끝이라도 그 영역인 것 전부이고, 다른 영역의 테이블은 컬럼과 주석 없이 이름만 그린다. 영역 다이어그램의 컬럼 표시 수준과 주석은 `export.erd.*` 옵션을 따른다. 테이블 하나짜리 조각이 한도를 넘으면 그대로 둔다. 전체 `erd.mmd`를 없애지 않은 것은 `maxTextSize`를 올려 쓰는 환경과 이 파일을 읽는 다른 도구를 위해서다. 조회 API(`Erd.render`)는 나누지 않는다(나누는 것은 `ErdSplitter.split`).

## 6. 미확정 사항
- 없음(사용자가 기본값으로 진행하도록 위임했다. 5절의 가정은 사용자 검토가 필요하다).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: [0006](../../decisions/0006-graph-json-and-static-viewer.md)

## 8. 계약 추가 제안 (design.md 반영 요청)
design.md 반영됨(7차 수정, 2026-10-11. 직접 고쳤다): R28·R29는 F-001 design.md 3.4 "ERD 영역" 항목 끝에 넣었다.

design.md 반영됨(2026-10-11): 아래 항목은 F-001 design.md 3.4 "소비자가 지키는 규칙"(ERD 영역 항목)과 3.5 공통 규칙 "추론한 `REFERENCES`의 방향"·엣지 attrs 표(`directionFrom`)에 넣었다. upgrade 스크립트의 외래 키 표기(F-013-R26)도 같은 3.4 항목에 적었다. PlantUML 표기를 실제 파서로 확인하지 못했다는 것은 7절에 있다.
- design.md에 반영할 것(F-001 design 3.5 공통 규칙 "ERD 영역"·"추론한 `REFERENCES`의 방향"): 추론한 `REFERENCES`가 `directionKnown=true`이면서 `cardinality=unknown`이면(`directionFrom=DDL`, F-018-R15) ERD 출력과 뷰어는 자식→부모 방향을 지키되 카디널리티를 계산해 단정하지 않고 "카디널리티 모름"으로 표기한다(Mermaid `}o..o{`와 라벨 `(추론 n건, 방향은 DDL, 카디널리티 모름)`, PlantUML `부모 <.. 자식`, DBML `<>`. F-013-R27).
