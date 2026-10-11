---
type: spec
id: F-018
title: 테이블 관계 추론
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-018 테이블 관계 추론

## 1. 배경
운영 중인 Oracle·DB2 스키마에는 FK 제약 없이 SQL의 조인으로만 이어진 테이블이 많다. DDL의 FK만으로 ERD를 그리면 관계선이 거의 없다. 이 기능은 SQL 분석이 남긴 조인 조건(`JOINS`)과 키 정보로 테이블 관계를 추론해 PRD 목표 "분석 결과만으로 ERD를 그릴 수 있다"를 채운다. 추론한 관계는 DDL의 관계와 구분해 기록한다.

## 2. 범위
- 포함: `JOINS` 엣지를 테이블 쌍과 컬럼 대응별로 모으기, PK·UNIQUE로 방향과 카디널리티 판정(복합 키의 일부를 상수로 고정한 조인 포함), 형제 조인 거르기, `REFERENCES`(`INFERRED`) 생성, DDL FK와의 중복 제거, 자료형 불일치 표시.
- 제외: 조인 조건 추출(F-006), DDL FK 추출(F-007), ERD 출력(F-013), 컬럼 이름 유사성만으로 하는 추론.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-018-R1 | 시스템은 `JOINS` 엣지를 테이블 쌍과 컬럼 대응 집합(순서 무관)별로 모아, 묶음마다 `REFERENCES`(`INFERRED`, `attrs.source=INFERRED`) 엣지 하나를 만들어야 한다. 엣지에는 `columns`, `refColumns`, `evidenceCount`(근거 구문 수), `evidence`(구문 ID 최대 10개)를 남긴다. | 사용자 요청, design 3.4 |
| F-018-R2 | 한쪽의 조인 컬럼 집합만 그 테이블의 PK 또는 UNIQUE 전체와 같으면, 시스템은 그쪽을 부모로 하는 자식→부모 방향의 엣지를 만들고 `cardinality=N:1`, `directionKnown=true`를 남겨야 한다. | 사용자 요청 |
| F-018-R3 | 양쪽 모두 키 전체이면 `cardinality=1:1`, 어느 쪽도 키 전체가 아니거나 키 정보가 없으면 `cardinality=unknown`으로 하고, 두 경우 모두 `directionKnown=false`에 방향은 ID 사전순 작은 쪽→큰 쪽으로 고정해야 한다. | 사용자 요청 |
| F-018-R4 | 만약 같은 테이블 쌍·같은 컬럼 대응의 DDL `REFERENCES`가 이미 있으면, 시스템은 추론 엣지를 만들지 않아야 한다. | 사용자 요청 |
| F-018-R5 | 시스템은 자기 조인, `declared=false` 테이블의 조인도 같은 규칙으로 처리해야 한다. 뷰가 낀 조인은 F-018-R11을 따른다. (2026-10-11 고쳐 씀: 전에는 뷰와의 조인도 같은 규칙으로 처리해, 키 정보가 없는 뷰가 자식으로 단정됐다) | 사용자 요청, 공개 프로젝트 검증(B13) |
| F-018-R6 | 시스템은 조인 근거가 없는 테이블 쌍에는 관계를 만들지 않아야 한다. | 사용자 요청, AGENTS.md(추측 금지) |
| F-018-R7 | 옵션 `link.minJoinEvidence`(기본 1)가 주어지면, 시스템은 근거 구문 수가 그 값보다 적은 묶음에는 엣지를 만들지 않아야 한다. | 사용자 요청 |
| F-018-R8 | 대응하는 두 컬럼의 자료형 계열이 다르면, 시스템은 엣지를 만들되 `attrs.typeMismatch=true`를 남겨야 한다. | 사용자 요청 |
| F-018-R9 | 어느 쪽도 조인 컬럼만으로는 키 전체가 아닐 때, 한쪽의 조인 컬럼이 그 테이블의 PK(또는 UNIQUE)의 일부이고 나머지 키 컬럼이 같은 구문에서 상수로 고정돼 있으면(`JOINS`의 `leftFixedColumns`/`rightFixedColumns`, F-006-R13), 시스템은 그쪽을 부모로 하는 `N:1` 엣지를 만들고 `attrs.fixedColumns`(부모 쪽 고정 컬럼), `attrs.fixedValues`(근거 구문들의 서로 다른 `COL=값`, 최대 10개)를 남겨야 한다. 이 조건을 채운 구문만 근거로 세고, 채운 구문이 없으면 F-018-R3대로 `unknown`이다. | 독립 감사(U3-b) |
| F-018-R10 | 만약 방향을 정하지 못한(`unknown`) 묶음의 양쪽 조인 컬럼이 각각 같은 부모 테이블의 같은 컬럼을 참조하면(DDL `REFERENCES` 또는 이번 실행에서 `N:1`로 확정된 추론), 시스템은 그 묶음에 관계를 만들지 않아야 한다. | 독립 감사(U3-a) |
| F-018-R11 | 시스템은 키 정보(PK·UNIQUE)가 없는 쪽을 유일 쪽(부모)으로 판정하지 않아야 한다. 한쪽이라도 `DB_VIEW`인 조인은 기본으로 관계를 만들지 않는다. 옵션 `link.inferViewRelations=true`가 주어지면 만들되, 뷰에는 키 정보가 없으므로 상대가 PK 전체여도 `cardinality=unknown`, `directionKnown=false`(방향은 ID 사전순)로 해야 한다. | 공개 프로젝트 검증(B13, enterprise) |
| F-018-R12 | 상수로 채운 키(F-018-R9)를 판정할 때 시스템은 리터럴로 고정된 컬럼만 써야 한다. 바인드·변수와의 등치(`leftFixedValues`·`rightFixedValues`의 `COL=?`)로만 고정된 컬럼은 키의 나머지를 채운 것으로 보지 않는다. | 실전 재검증 2차 후속(2026-10-11)(N14-a) |
| F-018-R13 | 같은 테이블 쌍에 DDL `REFERENCES`가 있으면(컬럼 대응이 달라도), 시스템은 DDL과 반대 방향으로 판정된 추론 관계를 확정하지 않고 방향 미상(`directionKnown=false`, `cardinality=unknown`, `attrs.declaredOpposite=true`)으로 기록해야 한다. DDL과 같은 방향이면 그대로 만든다. | 실전 재검증 2차 후속(2026-10-11)(N14-b) |
| F-018-R14 | 키가 아닌 컬럼이 세 개 이상의 다른 테이블과 방향 미상으로 조인되면(다른 테이블들이 식별자로 쓰는 "사실상 키"), 시스템은 그 컬럼 쪽을 자식으로 확정하지 않고 방향 미상(`directionKnown=false`, `cardinality=unknown`, `attrs.pseudoKey=true`)으로 기록해야 한다. | 실전 재검증 2차 후속(2026-10-11)(N14-c) |
| F-018-R15 | 같은 테이블 쌍에 DDL `REFERENCES`가 한 방향으로만 있고 추론 관계의 방향을 정하지 못했으면(`directionKnown=false`. F-018-R13의 `declaredOpposite`는 제외), 시스템은 DDL FK의 방향을 따라 자식 → 부모로 기록하고 `directionKnown=true`, `attrs.directionFrom=DDL`을 남겨야 한다. `cardinality`는 판정한 그대로다(`unknown` 또는 `1:1`). 컬럼 대응이 DDL 관계와 같으면 전처럼 추론 엣지를 만들지 않는다. | 실전 재검증 3차 후속(2026-10-11)(M9: 바인드를 근거에서 뺀 뒤 COMTNCOMMENT–COMTNBBS가 방향 미상이 되어 사전순으로 DDL과 반대로 놓임) |

## 4. 수용 기준

### F-018-AC1 (대상: F-018-R1, R2)
- Given `TB_ORDER`(PK `ORDER_ID`), `TB_MEMBER`(PK `MEMBER_ID`)와, 두 구문에서 나온 `JOINS`(`TB_ORDER.MEMBER_ID = TB_MEMBER.MEMBER_ID`; 한 구문은 좌우가 반대)
- When 관계 추론 링커를 실행하면
- Then `REFERENCES`(`TB_ORDER → TB_MEMBER`, `INFERRED`)가 하나 있고 `columns=[MEMBER_ID]`, `refColumns=[MEMBER_ID]`, `source=INFERRED`, `evidenceCount=2`, `evidence`에 두 구문 ID, `cardinality=N:1`, `directionKnown=true`다.

### F-018-AC2 (대상: F-018-R1)
- Given 같은 테이블 쌍을 12개 구문이 같은 컬럼으로 조인한 그래프
- When 관계 추론 링커를 실행하면
- Then `evidenceCount=12`이고 `evidence`는 10개다.

### F-018-AC3 (대상: F-018-R2)
- Given 복합 PK `(ORDER_ID, ITEM_SEQ)`인 `TB_ORDER_ITEM`과 PK `ORDER_ID`인 `TB_ORDER`의 조인 `TB_ORDER_ITEM.ORDER_ID = TB_ORDER.ORDER_ID`
- When 관계 추론 링커를 실행하면
- Then 복합 PK의 일부인 쪽은 부모가 아니고 `TB_ORDER`가 부모다(`TB_ORDER_ITEM → TB_ORDER`, `N:1`). 복합 PK 전체(`A.K1=B.K1 AND A.K2=B.K2`)로 조인한 쪽이 키면 그쪽이 부모다. UNIQUE 컬럼도 키로 본다.

### F-018-AC4 (대상: F-018-R3)
- Given 양쪽 모두 PK 전체로 조인한 쌍, 어느 쪽도 PK가 아닌 쌍, PK 정보가 없는 쌍
- When 관계 추론 링커를 실행하면
- Then 각각 `cardinality=1:1`, `unknown`, `unknown`이고 모두 `directionKnown=false`이며 엣지는 ID가 작은 쪽에서 큰 쪽으로 간다.

### F-018-AC5 (대상: F-018-R4)
- Given DDL `REFERENCES`(`TB_ORDER → TB_MEMBER`, `columns=[MEMBER_ID]`, `refColumns=[MEMBER_ID]`, `source=DDL`)와 같은 컬럼의 `JOINS`, 다른 컬럼(`REG_ID = MEMBER_ID`)의 `JOINS`
- When 관계 추론 링커를 실행하면
- Then 같은 컬럼 대응의 추론 엣지는 없고 다른 컬럼 대응의 추론 엣지는 있다. `refColumns`가 없는 DDL 엣지는 부모의 PK로 채워 비교한다.

### F-018-AC6 (대상: F-018-R5)
- Given 자기 조인 `TB_CATEGORY.PARENT_ID = TB_CATEGORY.CATEGORY_ID`(PK `CATEGORY_ID`), 뷰와 테이블의 조인, `declared=false` 테이블과의 조인
- When 관계 추론 링커를 실행하면
- Then 자기 참조 `REFERENCES`(`TB_CATEGORY → TB_CATEGORY`, `columns=[PARENT_ID]`, `refColumns=[CATEGORY_ID]`)가 있고, 미선언 테이블과의 관계도 만들어진다. 뷰와의 관계는 기본으로 만들어지지 않는다(F-018-AC12. 2026-10-11 고쳐 씀: 전에는 뷰와의 관계도 만들어졌다). 같은 컬럼끼리의 자기 조인(`A.ID = A.ID`)은 관계가 아니다.

### F-018-AC7 (대상: F-018-R6)
- Given `MEMBER_ID` 컬럼이 양쪽에 있지만 `JOINS`가 없는 두 테이블, 컬럼 목록이 빈 `JOINS`
- When 관계 추론 링커를 실행하면
- Then 추론 엣지가 없다.

### F-018-AC8 (대상: F-018-R7)
- Given 근거 구문이 1건인 쌍과 2건인 쌍
- When 옵션 없이 실행하면 둘 다 만들어지고(`evidenceCount=1`, `2`), `link.minJoinEvidence=2`로 실행하면
- Then 2건인 쌍만 만들어진다. 값이 숫자가 아니면 `WARN` 진단 `INVALID_OPTION`을 남기고 1로 본다.

### F-018-AC9 (대상: F-018-R8)
- Given `dataType`이 `NUMBER(10)`과 `VARCHAR2(10)`인 컬럼의 조인, `NUMBER(10)`과 `INTEGER`인 컬럼의 조인
- When 관계 추론 링커를 실행하면
- Then 앞의 엣지만 `typeMismatch=true`다.

### F-018-AC10 (대상: F-018-R9)
- Given `TB_CODE`(PK `GROUP_CD, CODE`)와 `TB_ORDER`(PK `ORDER_ID`)의 조인 `TB_ORDER.ORDER_STATUS = TB_CODE.CODE`에 `TB_CODE` 쪽 고정 컬럼 `GROUP_CD`(값 `ORDER_STATUS`)와 키가 아닌 `USE_YN`이 붙은 구문, 좌우가 반대이거나 값이 다르거나 바인드로 고정한 구문, 상수 조건 없이 `CODE`만 조인한 구문
- When 관계 추론 링커를 실행하면
- Then `REFERENCES`(`TB_ORDER → TB_CODE`, `N:1`, `directionKnown=true`, `columns=[ORDER_STATUS]`, `refColumns=[CODE]`, `fixedColumns=[GROUP_CD]`, `fixedValues`에 서로 다른 `GROUP_CD=…`)가 있고 `evidenceCount`·`evidence`·위치는 조건을 채운 구문만 센다. 조건을 채운 구문이 하나도 없으면 `unknown`이고 `fixedColumns`가 없다. 한쪽이 상수 없이도 PK 전체면(`TB_ORDER.ORDER_ID = TB_ORDER_ITEM.ORDER_ID AND TB_ORDER_ITEM.ITEM_SEQ = 1`) 그쪽이 부모인 `N:1` 그대로이고, 양쪽 다 상수로 채워야 키면 `1:1`이다. `link.minJoinEvidence`는 조건을 채운 구문 수로 따진다.

### F-018-AC11 (대상: F-018-R10)
- Given `TB_ORDER_ITEM.ORDER_ID → TB_ORDER`(DDL), `TB_ORDER_HIST.ORDER_ID = TB_ORDER.ORDER_ID`(조인, 이번 실행에서 `N:1`로 추론됨)와 형제 조인 `TB_ORDER_HIST.ORDER_ID = TB_ORDER_ITEM.ORDER_ID`, 같은 꼴의 `TB_DAILY_SALES.PRODUCT_ID = TB_ORDER_ITEM.PRODUCT_ID`; 한쪽만 부모를 참조하는 조인; 서로 다른 부모를 참조하는 조인; 두 컬럼 조인에서 한 컬럼만 같은 부모를 가리키는 경우
- When 관계 추론 링커를 실행하면(형제 조인의 `JOINS`가 부모 조인보다 먼저 있어도)
- Then 형제 조인 두 건에는 `REFERENCES`가 없고 진단도 없다. 부모로 가는 `N:1` 추론은 그대로 있다. 나머지 경우는 전과 같이 `unknown` 엣지가 만들어진다.

### F-018-AC12 (대상: F-018-R11)
- Given 뷰 `COMVNUSERMASTER`(키 정보 없음)와 `LETTNLOGINPOLICY`(PK `EMPLYR_ID`)의 조인 `COMVNUSERMASTER.USER_ID = LETTNLOGINPOLICY.EMPLYR_ID`, `LETTNBBS.FRST_REGISTER_ID = COMVNUSERMASTER.ESNTL_ID`; 그리고 키 정보가 없는(`declared=false`) 테이블 `EXT_POINT`가 PK 테이블 `TB_MEMBER`, 또 다른 키 없는 테이블과 조인된 그래프(키 없는 쪽에 상수 고정 컬럼이 있어도)
- When 옵션 없이 실행하면 뷰가 낀 조인에는 `REFERENCES`가 없다. `link.inferViewRelations=true`로 실행하면
- Then 뷰가 낀 관계가 만들어지되 모두 `cardinality=unknown`, `directionKnown=false`이고 방향은 ID 사전순이다(`LETTNLOGINPOLICY → COMVNUSERMASTER`, `columns=[EMPLYR_ID]`, `refColumns=[USER_ID]`: 뷰를 자식으로 단정하지 않는다). 키 없는 테이블은 어느 경우에도 부모가 되지 않는다: 상대가 PK 전체면 그쪽이 부모인 `N:1`, 상대도 키가 없으면 `unknown`이다.

### F-018-AC13 (대상: F-018-R12)
- Given 복합 PK `(ATCH_FILE_ID, FILE_SN)`인 테이블과 `ATCH_FILE_ID`로 조인하면서 `FILE_SN = ?`로만 고정한 구문, 그리고 키의 나머지 두 컬럼 중 하나만 리터럴이고 다른 하나는 바인드인 구문
- When 추론 링커를 실행하면
- Then 두 관계 모두 방향 미상(`unknown`, `directionKnown=false`)이고 `fixedColumns`·`fixedValues`가 없다. (F-018-AC10의 "바인드로 고정한 근거도 센다"는 이 기준으로 바뀌어, 리터럴 근거만 세고 `fixedValues`에 `COL=?`가 들어가지 않는다.)

### F-018-AC14 (대상: F-018-R13)
- Given DDL `ITEM → ORDER`가 있는 두 테이블에서, `ORDER.LAST_ITEM_ID = ITEM.ITEM_ID`(ITEM의 PK 전체)인 조인과 `ITEM.PREV_ORDER_ID = ORDER.ORDER_ID`인 조인
- When 추론 링커를 실행하면
- Then 앞의 것(`ORDER → ITEM`으로 판정될 것)은 `directionKnown=false`, `declaredOpposite=true`이고, 뒤의 것은 `ITEM → ORDER`로 확정된다.

### F-018-AC15 (대상: F-018-R14)
- Given `EMP.ESNTL_ID`(EMP의 키가 아님)가 세 테이블의 키 아닌 컬럼과 조인되고, PK가 `ESNTL_ID`인 `MEMBER`와도 조인되는 그래프. 그리고 조인 상대가 `MEMBER` 하나뿐인 보통의 FK 컬럼
- When 추론 링커를 실행하면
- Then `EMP`–`MEMBER` 관계는 `directionKnown=false`, `pseudoKey=true`이고 보통의 FK 컬럼은 전처럼 N:1로 확정된다.

### F-018-AC16 (대상: F-018-R15)
- Given DDL `COMTNCOMMENT(NTT_ID, BBS_ID) → COMTNBBS(NTT_ID, BBS_ID)`와, 어느 쪽도 키가 아닌 조인 `COMTNBBS.NTT_ID = COMTNCOMMENT.NTT_ID`, DDL과 컬럼 대응이 같은 조인, DDL이 없는 쌍의 방향 미상 조인
- When 관계 추론 링커를 실행하면
- Then `NTT_ID` 관계는 `COMTNCOMMENT → COMTNBBS`로 `directionKnown=true`, `directionFrom=DDL`, `cardinality=unknown`이다. DDL과 같은 대응의 추론 엣지는 없고, DDL이 없는 쌍은 전처럼 `directionKnown=false`다.

## 5. 가정
- **판정 순서(F-018-R2, R3, R9)**: (1) 한쪽만 조인 컬럼이 키 전체면 그쪽이 부모(`N:1`), 양쪽 다면 `1:1`. 이 단계에서는 고정 컬럼을 보지 않는다. (2) 어느 쪽도 아니면 근거 엣지마다 "조인 컬럼 ⊆ 키이고 키의 나머지가 그 엣지의 고정 컬럼에 다 있는가"를 양쪽에 대해 본다. 한쪽만 채운 엣지가 있고 반대쪽만 채운 엣지가 없으면 그쪽이 부모(`N:1`), 양쪽 다 채운 엣지만 있으면 `1:1`(`fixedColumns`는 `to` 쪽 것), 서로 다른 쪽을 채운 엣지가 섞였거나 채운 엣지가 없으면 `unknown`이다. (1)을 먼저 보는 것은, 자식의 복합 PK 일부를 상수로 채운 조인(`ITEM_SEQ = 1`, 뷰 `VW_PRODUCT_STOCK`의 `WAREHOUSE_CD = 'W01'`)이 "양쪽 다 키"로 보여 이미 확정되던 `N:1`의 방향을 잃지 않게 하기 위해서다.
- 키가 아닌 고정 컬럼(`USE_YN = 'Y'`)은 판정과 `fixedColumns`에 넣지 않는다. `fixedColumns`는 부모 키에서 조인 컬럼을 뺀 나머지(키 순서)다. `fixedValues`는 근거 엣지의 부모 쪽 `…FixedValues` 가운데 그 컬럼의 것만 나온 순서대로 모은다(바인드는 `COL=?`).
- 상수로 채운 키로 확정할 때 근거 수(`evidenceCount`, `link.minJoinEvidence` 비교)는 조건을 채운 구문만 센다. 그 수가 최소 근거 수에 못 미치면 확정하지 않고 전체 근거로 `unknown`을 만든다.
- **형제 조인(F-018-R10)**: 컬럼 대응마다 양쪽 컬럼이 같은 부모 컬럼(`테이블.컬럼`)을 참조해야 하고, 그 부모 테이블이 모든 대응에서 같아야 한다. 부모 참조는 DDL `REFERENCES`(참조 컬럼이 생략됐으면 부모 PK로 채운 것)와 이번 실행의 `N:1` 추론(상수로 채운 키 포함)에서 컬럼 단위로 모은다. 그래서 링커는 두 단계로 돈다: 모든 묶음을 판정한 뒤 `unknown` 후보를 거르고 기록한다. `1:1`과 `N:1`은 거르지 않는다. 걸러진 묶음에는 진단을 남기지 않는다(`JOINS` 엣지는 그대로 있다).
- F-012의 DB 객체 정규화(order 100) 뒤인 order 300에서 실행한다.
- **뷰가 낀 조인(F-018-R11)**: 뷰는 대개 마스터 테이블을 감싼 것이라 조인 컬럼이 실제로는 유일할 수 있다(`COMVNUSERMASTER.USER_ID`). 키 정보가 없다는 이유로 뷰를 자식(N 쪽)으로 두면 방향이 뒤집힌 채 `directionKnown=true`가 된다. 그래서 기본으로는 만들지 않고, 옵션을 켠 경우에도 방향을 정하지 않는다. 방향 미상이므로 형제 조인 판정(F-018-R10)의 부모 참조로도 쓰이지 않는다. 뷰인지는 정규화 뒤의 노드 ID(`db:view:`)로 본다. `link.inferViewRelations`는 `true`(대소문자 무관)일 때만 켜진 것으로 본다. `JOINS` 엣지는 옵션과 상관없이 그대로 있다.
- 키 정보가 없는 **테이블**(DDL이 없거나 PK·UNIQUE 선언이 없는 테이블)과 PK 전체의 조인은 전과 같이 PK 쪽이 부모인 `N:1`이다. 키 없는 쪽이 부모로 판정되는 길은 없다(키 전체 판정과 상수로 채운 키 판정 모두 그 테이블의 키 목록이 있어야 한다).
- `JOINS`의 `leftColumns`는 `from` 테이블, `rightColumns`는 `to` 테이블의 컬럼이고 같은 위치끼리 대응한다. 두 목록의 길이가 다르면 짧은 쪽까지만 쓴다.
- 근거 구문은 `attrs.statement`로 센다. 없으면 엣지의 위치를 근거 하나로 센다.
- PK는 테이블의 `attrs.primaryKey`(List), 없으면 `CONTAINS`로 이어진 컬럼의 `attrs.primaryKey=true`에서 얻는다. UNIQUE는 컬럼의 `attrs.unique=true`(단일 컬럼)와 테이블의 `attrs.uniqueKeys`(각 원소가 쉼표로 이은 컬럼 목록, 있으면)에서 얻는다.
- 한쪽이 PK 전체이고 다른 쪽이 UNIQUE 전체여도 "양쪽 다 키"로 보아 `1:1`, `directionKnown=false`다.
- DDL 우선은 방향과 상관없이 같은 테이블 쌍·같은 컬럼 대응이면 적용한다. `attrs.source`가 `INFERRED`가 아닌 `REFERENCES`를 DDL로 본다.
- 엣지의 위치는 첫 근거 `JOINS`의 위치다. 한 테이블 쌍에 컬럼 대응이 다른 관계가 여럿이면 서로 다른 근거 위치를 고른다(그래프가 `(from, to, kind, location)`으로 중복을 없애기 때문). 겹치지 않는 위치가 없으면 `INFO` 진단 `RELATION_NOT_RECORDED`를 남긴다.
- 자료형 계열: 숫자(`NUMBER`, `NUMERIC`, `DECIMAL`, `INTEGER`, `INT`, `SMALLINT`, `BIGINT`, `FLOAT`, `DOUBLE`, `REAL`, `DECFLOAT`), 문자(`CHAR`, `VARCHAR`, `VARCHAR2`, `NCHAR`, `NVARCHAR2`, `CLOB`, `GRAPHIC`, `VARGRAPHIC`), 날짜(`DATE`, `TIME`, `TIMESTAMP`). 길이·정밀도 차이는 불일치로 보지 않는다. 한쪽이라도 자료형을 모르면 표시하지 않는다.

- 리터럴 고정(F-018-R12): 한 컬럼에 리터럴 값과 `?`가 함께 있으면(전개본마다 다름) 리터럴로 고정된 것으로 본다. 값 목록이 없는 고정 컬럼(옛 그래프)은 그대로 쓴다. 바인드로만 고정된 근거는 N:1 근거에서 빠지지만 관계 자체의 근거로는 남는다(방향 미상 관계의 `evidence`).
- DDL과 반대 방향(F-018-R13): DDL 관계가 두 방향 모두에 있으면 낮추지 않는다. 자기 참조는 대상이 아니다. 낮춘 관계의 끝점은 다른 방향 미상 관계와 같이 ID 사전순이다.
- 사실상 키(F-018-R14): 상대 테이블 수는 "형제 조인으로 걸러지지 않은 방향 미상 관계"에서만 센다(같은 부모를 가리키는 자식끼리의 조인이 많은 보통의 FK 컬럼이 걸리지 않게). 컬럼 집합 단위로 세고 문턱은 3이다. 낮춘 관계는 다른 컬럼의 부모 판정(형제 조인)에는 이미 쓰였을 수 있다.
- DDL 방향 따르기(F-018-R15): 형제 조인을 거른 뒤(F-018-R10), "사실상 키" 판정(F-018-R14)까지 끝난 판정에 적용한다. `pseudoKey`로 방향을 거둔 관계도 DDL이 한 방향으로 선언했으면 그 방향을 따르고 `pseudoKey` 표시는 남는다. DDL이 두 방향 모두 선언한 쌍(서로 참조)과 자기 참조는 따르지 않는다. `declaredOpposite`(키 근거로는 반대 방향)는 근거가 어긋나므로 방향 미상 그대로다.
- 한쪽만 PK인 조인의 방향(알려진 한계. 실전 재검증 4차 후속에서 검토, 2026-10-11): `A.ESNTL_ID = B.SCRTY_DTRMN_TRGET_ID`처럼 B의 조인 컬럼이 PK 전체면 B가 부모인 `N:1`이다(F-018-R2). 1:1에 가까운 부속 테이블(키를 공유하는 설정·상세 테이블: 보안설정 행이 회원을 가리키는 선택적 0..1)은 의미상 방향이 반대일 수 있지만 정적 정보만으로는 가리지 못하므로 규칙을 바꾸지 않았다. "같은 구문의 같은 조인 조건에서 UNION 분기로 나온 형제 테이블 가운데 하나라도 방향 미상(`pseudoKey` 등)이면 나머지 형제도 방향 미상으로 맞춘다"는 규칙을 검토했으나 넣지 않았다: 집합 연산 인라인 뷰의 분기마다 만든 `JOINS`(F-006-R38)는 보통의 `JOINS`와 attrs(`leftColumns`, `rightColumns`, `statement`)·위치(구문 전체)가 같아 엣지로는 분기에서 온 것인지 알 수 없고, "같은 구문에서 같은 테이블의 같은 컬럼과 조인한 테이블들"로 넓히면 한 부모의 PK를 참조하는 여러 자식의 맞는 `N:1`(`ORDER_ITEM.ORDER_ID = ORDERS.ID AND PAYMENT.ORDER_ID = ORDERS.ID`)까지 흔든다. sql이 분기 `JOINS`에 표시를 남기면 다시 볼 수 있다. 공개 프로젝트에서 해당하는 것: 공통컴포넌트 `COMTNENTRPRSMBER → COMTNEMPLYRSCRTYESTBS` 1건(형제 `COMTNEMPLYRINFO`·`COMTNGNRLMBER`는 `pseudoKey`), enterprise `LETTNGNRLMBER`·`LETTNENTRPRSMBER`·`LETTNEMPLYRINFO → LETTNEMPLYRSCRTYESTBS` 3건(셋 다 `N:1`이라 형제 규칙으로도 바뀌지 않는다). design.md 7절에 한계로 적었다.

## 6. 미확정 사항
- 없음. 사용자가 미확정 사항은 기본값을 정해 진행하라고 위임했다(위 가정).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 설계(그래프 계약): [../F-001-analysis-pipeline/design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 기능: [F-012 교차 연결](../F-012-cross-linking/spec.md)

## 8. 계약 추가 제안 (design.md 반영 요청)
design.md에 반영됨(2026-10-11): 아래 항목은 모두 F-001 design.md 3.4·3.5·3.7·3.8에 있다.

- (2026-10-11 감사 후속, design.md에 반영됨(2026-10-11): 3.5 공통 규칙 "상수로 채운 복합 키"·"형제 조인은 관계로 만들지 않는다"와 엣지 attrs 표) `REFERENCES` attrs(link): `fixedColumns`(List, 상수로 채운 부모 키 컬럼), `fixedValues`(List, `COL=값` 최대 10개. 바인드는 `COL=?`). 상수로 채운 키로 확정한 관계에만 있다. 3.5 공통 규칙 "추론한 `REFERENCES`의 방향"에 "형제 조인(양쪽이 같은 부모의 같은 컬럼을 참조)은 관계로 만들지 않는다"를 덧붙인다.
- `REFERENCES` attrs: `evidence`(List), `cardinality`(`N:1`, `1:1`, `unknown`), `directionKnown`(boolean), `typeMismatch`(boolean).
- DB_TABLE attrs: `primaryKey`(List), 선택으로 `uniqueKeys`(List, 원소는 `A,B` 형태). DB_COLUMN attrs: `unique`(boolean).
- `REFERENCES`의 끝점에 DB_VIEW 허용, 옵션 키 `link.minJoinEvidence`, 진단 코드 `RELATION_NOT_RECORDED`(INFO).
- (2026-10-11 공개 프로젝트 검증 후속. design.md에 반영됨(2026-10-11, 2차): 3.8 표, 3.4 `REFERENCES` 행, 3.5 공통 규칙 "추론한 `REFERENCES`의 방향") 3.8 옵션 키 표에 `link.inferViewRelations` | `true`/`false` (false) | 뷰가 낀 조인으로도 추론 `REFERENCES`를 만든다(방향은 정하지 않는다: `cardinality=unknown`, `directionKnown=false`) | link. 3.4 `REFERENCES` 행의 "추론 관계는 끝점이 DB_VIEW일 수 있다"에 "(옵션 `link.inferViewRelations=true`일 때만)"을 더한다. 3.5 공통 규칙 "추론한 `REFERENCES`의 방향"에 "키 정보가 없는 쪽은 부모로 판정하지 않는다. 뷰가 낀 관계는 항상 `directionKnown=false`다"를 더한다. ERD 출력 옵션 `export.erd.views`는 뷰 노드를 그릴지만 정하므로, 뷰 관계선을 보려면 두 옵션을 함께 켜야 한다(F-013 쪽 문서에 한 줄 필요).

### 실전 재검증 2차 후속 (2026-10-11) — design.md 반영 요청
design.md 반영됨(2026-10-11): `declaredOpposite`·`pseudoKey`는 3.5(공통 규칙 "추론한 `REFERENCES`의 방향", 엣지 attrs 표), `fixedValues`에 `COL=?`가 들어가지 않는다는 것은 3.5(공통 규칙 "상수로 채운 복합 키", 엣지 attrs 표)에 넣었다.
- design 3.5 REFERENCES(추론) 엣지 attrs: `declaredOpposite`(boolean: 같은 테이블 쌍의 DDL 관계가 반대 방향이라 방향을 확정하지 않음), `pseudoKey`(boolean: 자식으로 판정된 쪽 컬럼이 여러 테이블의 식별자로 쓰이는 "사실상 키"라 방향을 확정하지 않음). 둘 다 `directionKnown=false`·`cardinality=unknown`과 함께 온다. `fixedValues`에는 이제 `COL=?`가 들어가지 않는다(F-018-R12).
- 소비자(export의 ERD·테이블 명세): 방향 미상 관계가 늘고 N:1 확정이 줄었다. 새 attrs는 표시만 더하면 된다(없어도 동작은 같다).

### 실전 재검증 3차 후속 (2026-10-11) — design.md 반영 요청
design.md 반영됨(2026-10-11): `directionFrom`은 3.5(공통 규칙 "추론한 `REFERENCES`의 방향", 엣지 attrs 표)에 넣었다.
- design 3.5 REFERENCES(추론) 엣지 attrs: `directionFrom`(String, 값 `DDL`: 조인만으로는 방향을 정하지 못해 같은 테이블 쌍의 DDL FK 방향을 따랐다. `directionKnown=true`이고 `cardinality`는 `unknown`이나 `1:1`일 수 있다. F-018-R15). 3.5 공통 규칙 "추론한 `REFERENCES`의 방향"에 이 경우를 더한다.
- 소비자(export의 ERD·테이블 명세): `directionKnown=true`인데 `cardinality=unknown`인 추론 관계가 생긴다. 방향은 그리되 N:1로 단정하는 표기(까마귀발)는 `cardinality`를 보고 정한다.

