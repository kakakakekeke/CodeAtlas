---
status: proposed
date: 2026-10-11
decision-makers: 에이전트 초안 (프로젝트 소유자 승인 전)
---

# 방언별로 여러 벌인 SQL은 한 벌만 쓰고, 대상 방언을 먼저 고르는 순위로 고른다

## Context and Problem Statement
여러 DBMS를 지원하는 프레임워크와 템플릿(전자정부 표준프레임워크 계열, jeesite 등)은 같은 namespace·같은 id의 매퍼 구문과 같은 테이블의 DDL·데이터 적재 스크립트를 방언별 파일(`Xxx_SQL_oracle.xml`·`Xxx_SQL_mysql.xml`, `oracle/`·`mysql/` 폴더)로 여러 벌 두고 실행할 때 하나만 쓴다. 분석기는 프로젝트의 파일을 전부 읽으므로 이 여러 벌을 어떻게 다룰지, 한 벌만 쓴다면 무엇을 기준으로 고를지 정해야 한다. 이 도구의 대상 DB는 Oracle과 DB2다(PRD 6절).

## Decision Drivers
- 그래프에는 한 번의 실행에서 실제로 함께 쓰이는 SQL만 있어야 한다. 한 구문의 테이블이 방언들의 합집합이 되면 ERD, CRUD 매트릭스, 영향도가 모두 틀린다.
- 매퍼와 DDL에서 고른 방언이 같아야 한다. 매퍼는 mybatis 모듈, DDL과 데이터 적재 스크립트는 sql 모듈이 읽고 두 모듈은 서로 의존하지 않는다(ADR-0007).
- 대상 외 방언의 SQL은 "죽지 않고 틀린 사실을 만들지 않는" 수준으로만 읽는다(F-006-R18, F-007-R17). 대상 외 방언의 파일을 고르면 분석의 정확도가 떨어진다.
- 옵션 없이 돌렸을 때의 결과가 쓸 만해야 하고, 무엇을 골랐는지 사용자가 알 수 있어야 한다(조용한 누락 금지).
- 고른 결과를 사용자가 옵션으로 바꿀 수 있어야 한다.

## Considered Options
- 한 벌만 쓰고, 순위는 명시한 값 → 프로젝트 설정이 가리키는 대상 방언 → Oracle → DB2 → Oracle 계열 → 프로젝트 설정이 가리키는 대상 외 방언 → 그 밖
- 전부 병합한다
- 경로순으로 처음 만난 정의를 쓴다
- 프로젝트 설정이 가리키는 방언을 대상 방언보다 먼저 쓴다

## Decision Outcome
Chosen option: "한 벌만 쓰고 대상 방언을 먼저 고르는 순위", because 이 도구가 정확히 읽을 수 있는 방언의 SQL로 그래프를 만들고, 템플릿의 기본 설정에 끌려 대상 외 방언이 뽑히는 일을 막기 때문이다.

방언의 순위는 프로젝트에 하나다.

(2026-10-11 고침, 아직 제안 상태: PostgreSQL이 대상 방언이 되면서(F-019) 순위에 PostgreSQL이 들어갔다. 아래 목록은 그 뒤의 순위이고, 문맥·대안의 "대상 방언은 Oracle과 DB2"는 처음 쓴 때의 서술이다.)

1. 명시한 값: `--dialect oracle|db2|postgres`, `--opt mybatis.dialectVariant=<방언>`. sql은 `--dialect`가 먼저고 mybatis는 `mybatis.dialectVariant`가 먼저다
2. 프로젝트 설정(`Globals.DbType`, `jdbc.type`, JDBC URL, 드라이버 클래스 등)이 대상 방언(oracle, db2, postgres)을 가리키면 그 방언
3. Oracle
4. DB2
5. PostgreSQL (F-019-R4. Oracle·DB2 파일이 있는 프로젝트의 결과를 바꾸지 않도록 그 뒤에 둔다)
6. Oracle 계열(Tibero, Altibase). 프로젝트 설정이 이 가운데 하나를 가리키면 그것이 먼저다
7. 프로젝트 설정이 가리키는 대상 외 방언
8. 그 밖의 방언: 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 이름의 사전순으로 먼저, 나머지가 사전순으로 그 뒤(F-007-R28, F-009-R26. 처음에는 sql이 경로순으로 처음 만난 방언, mybatis가 방언 이름 표의 순서여서 대상 방언 파일이 없는 프로젝트에서 서로 다른 방언을 골랐다)

매퍼는 id마다, DDL은 테이블마다 그 정의가 있는 방언 파일 가운데 순위가 가장 앞선 것을 쓴다. 순위가 프로젝트에 하나이므로, 가장 앞선 방언의 파일에 정의가 없는 것만 다음 순위에서 가져온다(매퍼 구문에는 `borrowedFrom`을 남긴다). `--dialect`는 `oracle`·`db2`·`postgres`·`auto`만 받고, 대상 외 방언으로 분석하려면 `--opt mybatis.dialectVariant=<방언>`을 준다(mybatis와 sql이 둘 다 읽는다). `mybatis.dialectVariant=all`은 매퍼를 전처럼 전부 합친다. 방언은 파일 이름의 토큰과 폴더 이름에서 읽고, sql은 경로에 이름이 없으면 내용 특징으로 읽는다. 규칙의 전문은 계약 3.5 공통 규칙 "방언 변형 선택"에 있다.

### Consequences
- Good, because 옵션 없이 돌려도 Oracle·DB2 파일이 있는 프로젝트는 그 방언의 구문과 테이블 정의만 그래프에 들어간다. 전자정부 simple backend 템플릿은 6벌 가운데 oracle 한 벌이 뽑힌다(루트 README의 힌트 예시).
- Good, because 매퍼와 DDL이 같은 순위를 쓰므로 한 프로젝트에서 같은 방언이 뽑힌다. 어긋나면 cli가 힌트에 경고를 낸다(F-001-R52).
- Good, because 고른 방언, 제외한 파일 수, 다른 방언에서 보충한 것, 설정과 다르게 골랐는지가 요약 진단 한 건(`DIALECT_VARIANT_MAPPERS`, `DIALECT_VARIANT_DDL`)에 나오고, 요청한 방언의 파일이 없으면 `DIALECT_NOT_FOUND`가 남는다.
- Bad, because 프로젝트 설정이 대상 외 방언을 가리키는데 대상 방언의 파일도 있으면, 실제 운영 방언이 아닌 쪽의 SQL로 그래프를 만든다. 그 프로젝트가 정말 MySQL로 운영된다면 옵션 없이 돌린 결과는 운영 중인 SQL의 관계가 아니다. 진단(INFO)과 힌트로 알릴 뿐이다.
- Bad, because 방언을 파일·폴더 이름과 내용 특징으로 읽는 휴리스틱이다. 낙타 표기로 붙여 쓴 이름(`OracleUserMapper.xml`)이나 방언 이름이 없는 배치는 방언 변형으로 알아보지 못한다(F-009 spec 알려진 한계).
- Bad, because 방언 토큰 표, 프로젝트 설정 키, 순위를 mybatis와 sql이 같은 내용으로 따로 구현한다. 한쪽만 고치면 매퍼와 DDL의 방언이 어긋난다. 양쪽 테스트에 같은 표를 두어 막는다.
- Bad, because 가장 앞선 방언의 파일에 없는 구문·테이블을 다음 순위 방언에서 보충하므로, 그 방언 고유의 SQL이 섞여 들어올 수 있다. 선택한 방언 파일에서 XML 주석으로 뺀 구문은 보충하지 않는다(F-009-R25).

### Confirmation
- mybatis: `DialectVariants`의 순위와 `DIALECT_VARIANT_MAPPERS`·`DIALECT_NOT_FOUND`를 F-009-R15·R20·R22·R24·R25의 수용 기준 테스트가 확인한다.
- sql: `DialectSelection`의 순위와 `DIALECT_VARIANT_DDL`·`DIALECT_NOT_FOUND`·`DIALECT_NOT_TARGET`을 F-007-R18~R27의 수용 기준 테스트가 확인한다.
- 두 모듈의 방언 토큰 표가 같은지는 양쪽 테스트(sql의 `DialectTokensTest`, mybatis의 `DialectTokenTableTest`)가 같은 표로 확인한다.
- cli: 힌트가 이 순위를 따르는지와 매퍼·DDL 방언 불일치 경고를 F-001-R52·R53의 수용 기준 테스트가 확인한다.
- 공개 프로젝트를 옵션 없이 다시 돌려 고른 방언을 확인한다. [확인 필요] 이 ADR을 쓰면서 다시 돌리지는 않았다.

## Pros and Cons of the Options

### 전부 병합
- Good, because 고르는 규칙이 없고 어느 방언의 SQL도 빠지지 않는다.
- Bad, because 한 구문 노드의 테이블·컬럼이 방언들의 합집합이 되고, 같은 테이블의 컬럼·키·인덱스·외래 키가 여러 DDL에서 섞인다. 공개 프로젝트 1차 검증에서 방언별 매퍼와 DDL을 합친 결과가 그랬다(architecture.md 11절의 "방언 병합". 같은 외래 키와 인덱스가 여러 번 기록됐고 방언 6벌의 같은 본문이 전개본 6개로 세어졌다: F-007-R14, F-009-R16의 출처).
- Bad, because 대상 외 방언의 파일까지 전부 읽으므로 그 방언의 구문을 끝까지 읽지 못해 생기는 없는 테이블(유령 테이블)이 그래프에 들어온다. 한 벌을 고른 뒤에도 고르지 않은 방언의 파일에서 유령 테이블이 나와 그 파일의 DML 구문까지 만들지 않게 했다(cubrid comment 스크립트. F-007-R27의 출처).
- Bad, because 방언 수만큼의 데이터 적재 묶음이 CRUD 매트릭스에 들어간다(전자정부 backend의 CRUD 256행 가운데 160행이 5개 방언 데이터 파일의 것이었다. F-007-R20의 출처).

### 경로순으로 선택
- Good, because 규칙이 단순하고 결과가 결정적이다.
- Bad, because 테이블마다 다른 방언이 뽑힌다. jeesite에서 Activiti 테이블은 DB2 파일, 업무 테이블은 Oracle 파일의 정의가 쓰였다(F-007-R18의 출처 N4).
- Bad, because 폴더 이름의 사전순이 방언을 정한다. `altibase`·`cubrid`가 `oracle`보다 앞선다.

### 프로젝트 설정 우선
- Good, because 설정은 그 프로젝트가 실제로 쓰는 방언을 가리킬 가능성이 높다.
- Bad, because 템플릿과 프레임워크의 저장소에 들어 있는 설정은 개발용 기본값이다. 공개 프로젝트 3차 검증에서 전자정부 계열을 옵션 없이 돌리자 MySQL 결과가 나왔다(F-007-R23·F-009-R24의 출처 M4).
- Bad, because 대상 외 방언의 SQL은 이 도구가 정확히 읽지 못한다. 대상 방언의 파일이 있는데도 덜 정확한 쪽을 기본으로 고르게 된다.
- 이 대안은 3차 수정에서 한 번 채택했다가(F-007-R21, F-009-R22) 4차 수정에서 "설정이 대상 방언을 가리킬 때만 먼저"로 바꿨다.

## More Information
- 관련 기능: F-006(구문의 `dialect` 표기, 데이터 적재 묶음), F-007(DDL·데이터 적재 스크립트 선택), F-009(매퍼 선택), F-001(힌트)
- 계약: `docs/specs/F-001-analysis-pipeline/design.md` 3.5(공통 규칙 "방언 변형 선택", "같은 테이블의 중복 정의", "방언별 매퍼", "데이터 적재 구문"), 3.7(`DIALECT_VARIANT_MAPPERS`, `DIALECT_VARIANT_DDL`, `DIALECT_NOT_FOUND`, `DIALECT_NOT_TARGET`), 3.8(`mybatis.dialectVariant`), 7절
- 코드: `codeatlas-sql`의 `DialectSelection`·`DialectTokens`·`ProjectDialect`, `codeatlas-mybatis`의 `DialectVariants`·`ProjectDialect`
- 다시 볼 조건: 대상 DB에 다른 방언을 더할 때, 프로젝트 설정이 가리키는 방언과 다르게 골라 틀린 결과가 난 실제 프로젝트가 나올 때, mybatis와 sql의 방언 표가 어긋나는 일이 생길 때(공용 코드를 core로 올릴지 다시 본다).
