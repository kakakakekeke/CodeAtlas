---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자 위임, 에이전트 제안 (소유자 검토 필요)
---

# SQL은 DB 객체를 스키마 없는 이름의 `db:table:`로 참조하고, 뷰·시노님·루틴 맞추기는 링커가 한다

## Context and Problem Statement
SQL 구문의 `FROM X`에서 `X`는 테이블일 수도, 뷰나 시노님일 수도 있고, DDL에 정의가 없을 수도 있다. SQL은 대부분 스키마 없이 이름을 쓰는데 DDL에는 스키마가 붙어 있기도 하다. SQL을 분석하는 쪽이 참조에 어떤 노드 ID를 쓰고, 그 이름을 정의된 객체와 누가 언제 맞출지 정해야 한다.

## Decision Drivers
- DDL과 SQL에서 같은 객체가 같은 ID로 만나야 ERD와 콜트리가 이어진다.
- DDL 없이 SQL만 있어도 참조가 그래프에 남아야 한다.
- DB 객체를 참조하는 모듈이 sql 하나가 아니다(springxml은 프로시저 리더의 `procedureName`으로 `db:routine:`을 참조한다).
- 그래프는 노드와 엣지를 더하는 방식으로만 쌓인다(엣지를 지우는 API가 없다).

## Considered Options
- 참조는 항상 스키마를 뺀 대문자 이름의 `db:table:<이름>`(루틴은 `db:routine:<이름>`)으로 만들고, 링커가 그 참조 스텁을 뷰·시노님의 대상·정의된 루틴 노드로 합친다(`GraphModel.replaceNode`)
- 테이블 ID에 스키마를 넣는다(`db:table:<스키마>.<이름>`)
- SQL 분석기가 DDL 카탈로그로 객체의 종류를 직접 판정해 처음부터 맞는 ID(`db:view:`, 시노님의 대상)로 참조한다

## Decision Outcome
Chosen option: "스키마 없는 이름으로 참조하고 링커가 합친다", because 참조하는 쪽은 이름만 알면 되고, 이름을 정의와 맞추는 규칙(뷰, 시노님 사슬, 스키마·패키지 한정자)이 링커 한 곳에 모이기 때문이다.

스키마는 `attrs.schema`에 둔다. 링커의 첫 단계(DB 객체 정규화, order 100)가 정의 없는 `db:table:X` 스텁을 `db:view:X`나 시노님의 최종 대상으로 합치고, 한정자만 다른 루틴 참조를 정의된 루틴으로 합친다. 시노님을 거친 엣지에는 `viaSynonym`, 스키마인지 확실하지 않은 한정자를 뗀 루틴 엣지에는 `strippedQualifier`와 `INFERRED`가 붙는다. 정의를 찾지 못한 참조는 `declared=false` 노드로 남는다.

### Consequences
- Good, because DDL이 없거나 일부만 있어도 SQL의 테이블 참조가 `declared=false` 노드로 남아 ERD에 표식과 함께 나온다(F-013-R5).
- Good, because mybatis·java·springxml이 넘긴 SQL과 SQL 파일의 SQL, springxml의 루틴 참조가 같은 정규화를 거친다.
- Bad, because 같은 이름의 테이블이 여러 스키마에 있으면 한 노드로 합쳐진다. 스키마가 서로 다르게 적힌 정의끼리만 `DUPLICATE_OBJECT_NAME` 진단으로 드러나고, 어느 스키마의 테이블을 참조했는지는 구분하지 못한다.
- Bad, because 그래프가 추가 전용이라 우회가 생겼다. 노드를 합치는 `replaceNode`를 core에 따로 넣었고(스텁의 attrs는 옮기지 않는다), 정규화 전에 끝점이 없는 `db:table:`·`db:routine:` 엣지에 참조 노드를 먼저 만들어 두어야 마무리 단계가 `UNRESOLVED`로 바꾸지 않는다. 링커가 돌기 전의 그래프(분석기 단위 테스트가 보는 그래프)에서는 뷰를 읽는 엣지가 `db:table:`을 가리킨다.
- Bad, because 이름 해석이 완전히 한 곳에 있지는 않다. sql 모듈도 자체 카탈로그로 두 부분 이름의 패키지·스키마를 가리고 시노님이 가리키는 테이블의 컬럼을 찾는다(F-006 가정).

### Confirmation
link 모듈의 정규화 테스트(F-012-R1~R3의 수용 기준)가 뷰·시노님·루틴 스텁이 합쳐지고 엣지가 옮겨지는지 확인한다. sql 모듈 테스트는 참조가 `db:table:`로 나가는지, 스키마가 다른 같은 이름 정의에 진단이 남는지 확인한다. 전체는 `samples/sample-shop` 대조가 본다.

## Pros and Cons of the Options

### 테이블 ID에 스키마 포함
- Good, because 스키마가 다른 같은 이름의 테이블을 구분한다.
- Bad, because SQL은 대부분 스키마 없이 테이블을 쓰므로 DDL의 ID와 SQL의 ID가 어긋난다. 스키마 없이 쓴 이름이 어느 스키마의 것인지는 접속 설정에 달려 있어 소스만으로는 알 수 없다.

### SQL 분석기가 카탈로그로 종류를 직접 판정
- Good, because 처음부터 맞는 ID로 엣지를 만들므로 노드를 합치는 단계가 필요 없다.
- Bad, because DDL을 파싱한 카탈로그를 가진 것은 sql 모듈뿐이다. springxml처럼 DB 객체를 참조하는 다른 모듈은 판정할 수 없어 같은 맞추기 규칙이 두 곳 이상에 생긴다.
- Bad, because 시노님이 가리키는 대상의 종류(테이블, 뷰, 시퀀스, 루틴, 패키지)를 따라가는 일까지 SQL 분석기가 맡게 된다. 이 일은 F-006·F-007의 범위에서 빼고 F-012에 두었다.

## More Information
- 관련 기능: F-006, F-007(참조와 정의), F-012(정규화), F-018(정규화된 ID로 관계 추론)
- 계약: `docs/specs/F-001-analysis-pipeline/design.md` 3.2(`replaceNode`), 3.3, 3.5(뷰·시노님 정규화), 7절(한계)
- 다시 볼 조건: 같은 이름의 테이블을 여러 스키마에 둔 프로젝트를 분석해야 할 때.
