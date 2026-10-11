---
status: proposed
date: 2026-10-11
decision-makers: 프로젝트 소유자가 방향을 고름(2026-10-11 "길 2로 진행"), 본문은 에이전트 초안 (소유자 검토 전)
---

# SQL 읽기 방식을 파서까지 넘기고, 방언끼리 겹치는 표기만 읽기 방식으로 가른다

## Context and Problem Statement
SQL·DDL·루틴 파서는 방언을 받지 않고 Oracle·DB2 문법의 합집합을 한 문법으로 읽는다(ADR-0004). 방언 판정은 노드에 `dialect`를 적고 방언별 여러 벌에서 한 벌을 고르는 데만 쓰이고, 파서에 닿는 방언 정보는 렉서의 "MySQL인가" 불리언과 루틴 파서의 "SQL PL인가" 불리언 둘뿐이다. PostgreSQL을 대상 방언으로 더하려는데(F-019), PostgreSQL은 같은 글자를 다른 뜻으로 쓰는 자리가 있다: `$$`는 Oracle·DB2에서 이름에 쓸 수 있는 글자이고 PostgreSQL에서는 문자열의 따옴표다. `#`도 그렇다(`T#1`과 `#>>`). PL/pgSQL은 `:=`를 써서 지금 규칙으로는 PL/SQL로 판정되지만 `PERFORM`, 콜론 없는 `NEW`, 달러 인용 본문은 PL/SQL에 없다. 새 방언의 문법을 어디에, 어떤 정보로 넣을지 정해야 한다.

## Decision Drivers
- 틀린 관계를 만들지 않는다. 표기를 추측으로 가르다 틀리면 없는 테이블과 컬럼이 그래프에 들어간다.
- Oracle·DB2·MySQL 입력의 기존 결과가 달라지지 않아야 한다.
- 파서를 방언 수만큼 복제하지 않는다. 방언 사이에 겹치는 문법이 대부분이고 오류 복구 규칙을 한곳에서 유지해야 한다(ADR-0004).
- 방언을 더 넣을 때 같은 길을 다시 쓸 수 있어야 한다.

## Considered Options
- 읽기 방식(기본, MySQL, PostgreSQL)을 렉서·분할기·파서·루틴 파서에 넘기고, 겹치는 표기만 그 값으로 가른다
- 방언을 넘기지 않고 합집합 문법에 PostgreSQL 문법을 더한다(MySQL 표기를 넣어 온 방식)
- 방언별 파서를 따로 두고 방언으로 라우팅한다

## Decision Outcome
Chosen option: "읽기 방식을 파서까지 넘긴다", because 겹치는 표기를 추측 없이 가를 수 있고, PostgreSQL 규칙이 PostgreSQL로 판정한 텍스트에만 적용되어 기존 결과를 건드리지 않으며, 파서는 하나로 남기 때문이다.

- 읽기 방식은 sql 모듈 안의 enum `io.codeatlas.sql.lexer.Syntax { DEFAULT, MYSQL, POSTGRES }`다. `DEFAULT`는 지금까지의 Oracle·DB2 합집합이다. Oracle과 DB2는 서로 겹치는 표기가 적어 지금처럼 한 문법으로 읽는다. 옵션의 `Dialect`(core)와는 다른 것이다: `Dialect`는 사용자가 고르는 대상 방언이고 `Syntax`는 한 텍스트를 읽는 규칙이다.
- 읽기 방식은 텍스트 단위로 한 번 정한다: SQL 파일은 파일의 방언(경로 → 내용 특징), 내장 소스는 생산자가 넘긴 방언 → 프로젝트 방언 → 그 텍스트의 내용 특징이다. 정하는 코드는 한곳(`DialectDetector`)에 둔다.
- 렉서(`Lexer`), 스크립트 분할기(`ScriptSplitter`), DML·DDL 파서(`SqlParser`, `DdlParser`)는 `Syntax`를 받는다. 지금의 `boolean mysql` 매개변수는 `Syntax`로 바꾼다.
- 루틴 본문 파서(`RoutineParser`)의 `boolean sqlPl`은 enum `RoutineLanguage { PLSQL, SQLPL, PLPGSQL }`로 바꾼다. 본문의 언어는 `Syntax`가 `POSTGRES`면 `PLPGSQL`, 아니면 지금 규칙(본문 특징 → 방언)으로 PL/SQL과 SQL PL을 가른다.
- 읽기 방식으로 가르는 것은 겹치는 표기와 그 방언에만 있는 문장뿐이다. 겹치지 않는 문법(`ON CONFLICT`, `DELETE … USING` 등)은 합집합 문법에 그대로 더한다. 어느 쪽인지 애매하면 읽기 방식으로 가른다(기존 결과를 지키는 쪽).

### Consequences
- Good, because `$$`, `#`, `E'..'`, `$1` 같은 표기를 방언으로 정확히 읽는다.
- Good, because PostgreSQL 전용 규칙이 `Syntax.POSTGRES`에서만 켜져 Oracle·DB2·MySQL 테스트가 그대로 통과해야 한다는 것을 회귀 기준으로 쓸 수 있다.
- Good, because 다음 방언(MSSQL 등)을 넣을 때 enum에 값을 더하고 같은 통로를 쓴다.
- Bad, because 파서를 만드는 자리(20여 곳)의 시그니처가 바뀐다.
- Bad, because 읽기 방식을 잘못 정하면 그 텍스트 전체를 다른 규칙으로 읽는다. 경로에도 설정에도 표시가 없는 PostgreSQL 매퍼는 그 구문에 PostgreSQL 내용 특징이 있을 때만 PostgreSQL로 읽는다. 무엇으로 읽었는지는 구문의 `dialect`로 드러난다.
- Bad, because 아키텍처 문서의 품질 목표 "기존 분석기 코드를 고치지 않고 새 방언을 추가한다"는 SQL에서는 여전히 이루지 못한다. 방언 추가는 sql 모듈 안의 수정이다.

### Confirmation
- 기본·MySQL 읽기 방식의 기존 테스트가 고치지 않고 통과한다(F-019-AC6). 방언 순위 변경으로 기대가 바뀌는 테스트는 F-019-R4의 것만이다.
- 같은 텍스트가 읽기 방식에 따라 다르게 읽히는 자리(`$$`, `#`)를 렉서 테스트가 양쪽으로 확인한다.
- `samples/sample-shop`의 정답 대조가 통과율 100%를 유지한다(F-019-AC35).

## Pros and Cons of the Options

### 방언을 넘기지 않고 합집합에 더한다
- Good, because 구조와 시그니처가 그대로다.
- Bad, because `$$`·`#`처럼 겹치는 표기는 주변 토큰으로 추측해야 하고, 추측이 틀리면 Oracle·DB2 쪽 결과가 깨진다.
- Bad, because PL/pgSQL을 PL/SQL 파서의 추측 분기로 넣으면 `:=`가 있는 본문에서 두 언어를 가를 근거가 없다.

### 방언별 파서를 따로 두고 라우팅한다
- Good, because 방언마다 문법이 섞이지 않는다.
- Bad, because DML의 대부분(SELECT, 조인, 서브쿼리, CTE)과 오류 복구 규칙을 방언 수만큼 유지해야 한다.
- Bad, because 방언을 판정하지 못한 텍스트(매퍼의 평범한 SELECT)를 읽을 파서가 따로 필요하다. 지금의 합집합 문법이 그 역할을 한다.

## More Information
- 관련 기능: F-019(PostgreSQL 지원), F-006, F-007, F-008
- 관련 결정: ADR-0004(직접 만든 오류 허용 파서. 이 결정은 그 안에서 방언 정보를 넘기는 방법을 정한다), ADR-0010(방언 여러 벌에서 한 벌 고르기)
- 코드: `codeatlas-sql`의 `lexer/Syntax`, `parser/RoutineLanguage`, `analysis/DialectDetector`
- 다시 볼 조건: Oracle과 DB2 사이에서도 읽기 방식을 갈라야 하는 표기가 나올 때(`DEFAULT`를 둘로 나눈다), 한 파일에 방언이 섞인 프로젝트가 나올 때.
