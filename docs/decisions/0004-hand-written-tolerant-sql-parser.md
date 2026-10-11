---
status: accepted
date: 2026-10-11
decision-makers: 프로젝트 소유자 위임, 에이전트 제안 (소유자 검토 필요)
---

# SQL, DDL, 프로시저 파서는 오류를 허용하는 재귀 하강 파서로 직접 구현한다

## Context and Problem Statement
Oracle과 DB2 두 방언의 DML, DDL, 프로시저(PL/SQL, SQL PL)에서 테이블, 컬럼, 조인 조건, 호출 관계를 뽑아야 한다. MyBatis 동적 SQL은 태그를 걷어낸 뒤에도 문법적으로 완전하지 않을 수 있다. 사용자는 필요하면 파서를 직접 구현해도 된다고 했다.

## Decision Drivers
- 두 방언을 한 구조로 다룬다.
- 모르는 구문이나 깨진 SQL에서도 알아낸 부분(테이블, 조인)은 건진다.
- 실패한 구간을 진단으로 남긴다.
- 외부 의존 없이 jar에 들어간다.

## Considered Options
- 재귀 하강 파서 직접 구현 (공통 코어 + 방언별 확장)
- ANTLR 문법(grammars-v4의 PL/SQL 등) 사용
- JSqlParser 사용
- 정규식 기반 추출

## Decision Outcome
Chosen option: "재귀 하강 파서 직접 구현", because 오류 복구 지점과 방언 분기를 직접 통제할 수 있고, 이 도구에 필요한 것은 완전한 문법 검증이 아니라 관계 추출이기 때문이다.

### Consequences
- Good, because 모르는 구문은 토큰을 건너뛰고 계속 진행하게 만들 수 있다.
- Bad, because 방언 구문을 직접 채워야 하고, 드문 구문은 지원하지 못한 채 남는다. 지원하지 못한 구문은 진단으로 드러나게 한다.

### Confirmation
방언별 SQL 조각 모음을 단위 테스트로 두고, 추출한 테이블·컬럼·조인이 기대와 같은지 확인한다. 파싱하지 못한 구문은 진단 개수로 센다.

## Pros and Cons of the Options

### ANTLR 문법 사용
- Good, because PL/SQL은 공개 문법이 있다.
- Bad, because 문법에 맞지 않는 입력에서 건질 수 있는 정보가 적고, 방언마다 다른 문법과 다른 트리를 다뤄야 한다. DB2 SQL PL 공개 문법의 수준은 확인하지 못했다.

### JSqlParser
- Good, because 표준 DML은 바로 된다.
- Bad, because 프로시저 본문을 다루지 못하고 방언 구문에서 예외로 끝난다.

### 정규식 기반 추출
- Bad, because 서브쿼리, 별칭, CTE를 구분하지 못해 관계를 잘못 만든다.
