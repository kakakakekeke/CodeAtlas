---
type: spec
id: F-019
title: PostgreSQL 지원
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-019 PostgreSQL 지원

## 1. 배경
지금까지 대상 DB는 Oracle과 DB2였고 PostgreSQL은 "죽지 않고 틀린 사실을 만들지 않는" 범위 밖 방언이었다(F-006-R18, F-007-R17). PostgreSQL 프로젝트를 실제로 넣어 보면 그 기준에도 못 미친다: pg_dump의 `ALTER TABLE ONLY`가 `ONLY`라는 테이블을 만들고, `$$ … $$` 본문을 읽지 못해 루틴의 SQL이 빠지며, 매개변수가 컬럼으로 기록된다(2026-10-11 실험). 사용자가 PostgreSQL을 추가하되 "방언을 파서까지 알려 주는" 방식(ADR-0012)으로 진행하라고 지시했다. PRD의 세 목표(콜트리·ERD·클래스 다이어그램) 가운데 ERD와 콜트리의 DB 쪽을 PostgreSQL 프로젝트에서도 쓸 수 있게 하는 기능이다.

## 2. 범위
- 포함: PostgreSQL을 Oracle·DB2와 같은 대상 방언으로 둔다. 방언 옵션·판정·선택, PostgreSQL의 DML·DDL(pg_dump 평문 포함)·PL/pgSQL 루틴·트리거, MyBatis 매퍼 안의 PostgreSQL SQL, 검증용 샘플.
- 제외: PostgreSQL에 접속해서 얻는 정보, pg_dump의 사용자 지정·디렉터리·tar 형식(이진), `plpgsql`·`sql` 외의 언어로 쓴 루틴의 본문(헤더만 기록), 규칙(`CREATE RULE`)·정책(`CREATE POLICY`)·집계·연산자 정의의 분석, `?`·`?|`·`?&` JSON 연산자(JDBC 바인드 `?`와 구분할 수 없다), 따옴표 없는 이름의 소문자 표기(이름은 지금처럼 대문자로 정규화한다).

## 3. 요구사항

용어: "읽기 방식"은 한 SQL 텍스트를 어떤 표기 규칙으로 읽을지다 — 기본(Oracle·DB2 합집합, 지금까지의 동작), MySQL, PostgreSQL 셋이다(ADR-0012). "대상 방언"은 이 도구가 정확히 읽는 것을 목표로 하는 방언이다.

### 3.1 옵션, 판정, 선택

| ID | 요구사항 | 출처 |
|---|---|---|
| F-019-R1 | 시스템은 `--dialect`의 값으로 `postgres`(별칭 `postgresql`, `pgsql`, 대소문자 무시)를 받아야 하고, 그 값은 `Dialect.POSTGRES`다. | 사용자 요청 |
| F-019-R2 | 시스템은 SQL 파일의 방언을 경로의 방언 이름(`postgres`·`postgresql`·`pgsql`), 확장자 `.pgsql`, 그다음 내용 특징(5절 "PostgreSQL 내용 특징")으로 PostgreSQL이라고 판정해야 한다. 경로의 방언 이름이 내용 특징보다 먼저다(F-007-R24와 같다). | 사용자 요청, F-007-R24 |
| F-019-R3 | 시스템은 구문 특징으로 방언을 판정할 때 PostgreSQL 특징(5절 "PostgreSQL 구문 특징")을 Oracle·DB2와 함께 점수로 매겨, PostgreSQL 점수가 가장 높으면 `POSTGRES`로 판정하고, PostgreSQL에만 있는 표기가 하나라도 있으면 Oracle·DB2로 판정하지 않아야 한다. 노드의 `dialect` 값은 `POSTGRES`다. | 사용자 요청, F-006-R28·R42 |
| F-019-R4 | 시스템은 대상 방언을 oracle, db2, postgres로 두고, 방언별 여러 벌에서 쓸 방언을 5절 "방언 순위(F-019-R4)"로 골라야 한다(F-007-R23·F-009-R24의 순위를 대체한다). sql, mybatis, cli 힌트가 같은 순위를 쓴다. | 사용자 요청, ADR-0010 |
| F-019-R5 | 프로젝트의 SQL 스크립트가 PostgreSQL뿐이면, 시스템은 `DIALECT_NOT_TARGET`을 남기지 않아야 한다. | F-007-R25 |
| F-019-R6 | 시스템은 SQL 텍스트마다 읽기 방식을 하나 정해 렉서, 스크립트 분할기, DML·DDL 파서, 루틴 파서에 넘겨야 하고, PostgreSQL에만 있는 표기(달러 인용, `#>`·`#>>` 같은 연산자, PL/pgSQL 문장)는 PostgreSQL 읽기 방식에서만 그렇게 읽어야 한다. | ADR-0012 |
| F-019-R7 | 시스템은 SQL 파일의 읽기 방식을 파일의 방언(F-019-R2)으로, 내장 소스(매퍼 SQL 등)의 읽기 방식을 생산자가 넘긴 `attrs.dialect` → 프로젝트 방언(`--dialect`, `mybatis.dialectVariant`, 프로젝트 설정, 스크립트에서 고른 방언) → 그 텍스트의 PostgreSQL 내용 특징 순으로 정해야 한다. | ADR-0012 |
| F-019-R8 | 읽기 방식이 기본이나 MySQL인 입력에 대해, 시스템은 이 기능이 들어오기 전과 같은 그래프와 진단을 만들어야 한다(방언 순위가 바뀌어 달라지는 선택은 F-019-R4의 것이다). | ADR-0012 |

### 3.2 렉서와 스크립트 분할

| ID | 요구사항 | 출처 |
|---|---|---|
| F-019-R9 | PostgreSQL 읽기 방식에서, 시스템은 달러 인용 문자열(`$$…$$`, `$태그$…$태그$`)을 문자열 토큰 하나로 읽어야 하고, 그 안의 `;`에서 구문을 나누지 않아야 한다. 닫히지 않은 달러 인용은 입력 끝까지를 문자열 하나로 읽고 진단 1건으로 남긴다(분석은 멈추지 않는다. 그 뒤의 내용은 문자열 안이라 분석되지 않는다). | 사용자 요청 |
| F-019-R10 | PostgreSQL 읽기 방식에서, 시스템은 `E'…'` 이스케이프 문자열(백슬래시 이스케이프), 위치 매개변수 `$1`(바인드), 연산자 `->`·`->>`·`#>`·`#>>`·`@>`·`<@`·`&&`·`~`·`~*`·`!~`·`!~*`·`@@`를 토큰으로 읽어야 한다. MyBatis 자리표시자 `#{…}`·`${…}`는 그대로 자리표시자다. | 사용자 요청 |
| F-019-R11 | PostgreSQL 읽기 방식에서 `COPY 테이블 [(컬럼, ..)] FROM stdin`을 만나면, 시스템은 그 뒤의 데이터 줄을 줄 처음의 `\.`까지 구문으로 읽지 않고 건너뛰어야 하고, 줄 처음이 `\`인 psql 메타 명령(`\connect` 등)은 조용히 건너뛰어야 한다. | 사용자 요청 |

### 3.3 DML

| ID | 요구사항 | 출처 |
|---|---|---|
| F-019-R12 | 시스템은 `INSERT … ON CONFLICT [(컬럼, ..) \| ON CONSTRAINT 이름] [WHERE ..] DO NOTHING \| DO UPDATE SET .. [WHERE ..]`를 읽어, 대상 테이블 하나에 대한 쓰기로 기록하고(`DO UPDATE`의 대입 컬럼도 쓰기) `EXCLUDED`를 테이블이나 컬럼의 소속으로 만들지 않아야 한다. | 사용자 요청 |
| F-019-R13 | 시스템은 `DELETE FROM 대상 [별칭] USING 테이블, .. WHERE ..`를 읽어, 대상은 쓰기로, `USING`의 테이블은 읽기로 기록하고 `WHERE`의 등치 조건을 조인으로 기록해야 한다. | 사용자 요청 |
| F-019-R14 | 시스템은 `WITH` 절 안의 데이터 변경 구문(`INSERT`·`UPDATE`·`DELETE … [RETURNING ..]`)을 읽어 그 대상 테이블을 쓰기로 기록하고, CTE의 이름을 테이블 노드로 만들지 않아야 한다(`WITH RECURSIVE` 포함). | 사용자 요청 |
| F-019-R15 | 시스템은 다음 PostgreSQL 식과 절을 `PARSE_FAILED` 없이 읽고 틀린 테이블·컬럼을 만들지 않아야 한다: `식::타입`(배열 타입 `::text[]` 포함), `ARRAY[..]`와 첨자 `a[1]`, `= ANY(..)`, `interval '7 days'`, F-019-R10의 연산자, `ILIKE`·`SIMILAR TO`, `IS [NOT] DISTINCT FROM`, `집계(..) FILTER (WHERE ..)`, `SELECT DISTINCT ON (..)`, `[LEFT] JOIN LATERAL (..) 별칭 ON true`, `FROM 함수(..) [WITH ORDINALITY] [AS] 별칭(컬럼, ..)`, `ORDER BY .. NULLS FIRST\|LAST`, `LIMIT n\|ALL OFFSET m`, `FOR UPDATE\|NO KEY UPDATE\|SHARE\|KEY SHARE [OF 별칭, ..] [NOWAIT\|SKIP LOCKED]`, `RETURNING *`, `UPDATE\|DELETE … [ONLY] 테이블`, `TRUNCATE [TABLE] [ONLY] t, .. [RESTART IDENTITY] [CASCADE]`, `CREATE TABLE … AS … WITH [NO] DATA`. | 사용자 요청 |
| F-019-R16 | 시스템은 `nextval('시퀀스')`·`currval('시퀀스')`·`setval('시퀀스', ..)`의 첫 인자가 문자열 리터럴이면(뒤의 `::regclass` 포함, 스키마 한정 포함) 그 시퀀스에 대한 참조로 기록해야 한다(Oracle `seq.NEXTVAL`과 같은 엣지). 인자가 리터럴이 아니면 참조를 만들지 않는다. | 사용자 요청, F-006의 시퀀스 참조 |
| F-019-R17 | 시스템은 `pg_catalog`·`information_schema` 스키마의 객체와 스키마 없이 쓴 알려진 카탈로그 이름(`pg_class`·`pg_tables` 등의 목록과 `pg_stat_`·`pg_statio_`로 시작하는 이름)을 DB가 제공하는 시스템 카탈로그로 다뤄야 한다(Oracle 데이터 사전과 같은 취급). `pg_`로 시작한다는 것만으로는 카탈로그로 보지 않는다(`PG_USER`처럼 결제 업무 테이블 이름과 겹친다). 스키마 없이 쓴 이름의 이 규칙은 PostgreSQL 읽기 방식으로 읽은 구문에만 적용한다: 다른 읽기 방식에서는 `PG_SETTINGS`·`PG_STAT_DAILY` 같은 업무 테이블을 카탈로그로 표시하지 않는다(리뷰 뒤 고침). `FROM` 없는 `SELECT`는 더미 테이블 없이 읽는다. | F-006의 시스템 카탈로그 규칙 |

### 3.4 DDL

| ID | 요구사항 | 출처 |
|---|---|---|
| F-019-R18 | 시스템은 `ALTER TABLE [IF EXISTS] [ONLY] 이름 …`의 `ONLY`와 `IF EXISTS`를 수식어로 읽어, 제약(기본 키, 외래 키, 유일)과 컬럼 변경을 그 이름의 테이블에 적용해야 한다. 외래 키의 `DEFERRABLE`·`INITIALLY ..`·`NOT VALID`·`ON UPDATE ..`·`MATCH ..`는 받아 넘긴다. | 사용자 요청 |
| F-019-R19 | 시스템은 `CREATE [GLOBAL\|LOCAL] [TEMP\|TEMPORARY\|UNLOGGED] TABLE [IF NOT EXISTS] 이름 (..)`을 테이블 정의로 읽어야 하고, 타입 `smallserial`·`serial`·`bigserial`(과 `serial2`·`serial4`·`serial8`)의 컬럼을 `identity=true`로 기록해야 한다. 배열 타입(`text[]`)과 `timestamp with time zone` 같은 여러 단어 타입은 타입 텍스트 그대로 적는다. | 사용자 요청 |
| F-019-R20 | 시스템은 `CREATE TABLE a (LIKE b [INCLUDING ..])`와 `CREATE TABLE a (..) INHERITS (b, ..)`에서 b의 정의가 같은 분석에 있으면 b의 컬럼을 a의 컬럼으로 넣고(자기 컬럼보다 앞), `CREATE TABLE p PARTITION OF 부모 …`는 부모의 컬럼을 가진 테이블로 기록하되 `partitionOf=<부모>`를 붙여야 한다. b나 부모의 정의가 없으면 컬럼을 만들지 않는다. `PARTITION BY ..` 절은 받아 넘긴다. pg_dump가 내는 `ALTER TABLE [ONLY] 부모 ATTACH PARTITION 자식 …`도 자식에 `partitionOf=<부모>`를 붙인다(컬럼은 자식 자신의 정의다). | 사용자 요청 |
| F-019-R21 | 시스템은 `CREATE [UNIQUE] INDEX [CONCURRENTLY] [IF NOT EXISTS] [이름] ON [ONLY] 테이블 [USING 방식] (컬럼 [연산자클래스] [ASC\|DESC] [NULLS ..] \| (식), ..) [INCLUDE (..)] [WITH (..)] [TABLESPACE ..] [WHERE ..]`를 읽어 인덱스의 컬럼을 기록해야 한다. 식 항목은 컬럼으로 만들지 않는다. | 사용자 요청 |
| F-019-R22 | 시스템은 pg_dump가 내는 부속 구문(`SET ..`, `SELECT pg_catalog.set_config(..)`·`setval(..)`, `CREATE EXTENSION\|SCHEMA\|TYPE\|DOMAIN\|COLLATION ..`, `ALTER .. OWNER TO ..`, `ALTER SEQUENCE .. OWNED BY ..`, `ALTER TABLE .. ALTER COLUMN .. SET DEFAULT nextval(..)`, `GRANT`·`REVOKE`, `COMMENT ON EXTENSION\|SCHEMA\|TYPE\|FUNCTION ..`)을 `PARSE_FAILED`·`UNSUPPORTED_SYNTAX` 없이 넘기고 테이블이나 구문 노드를 만들지 않아야 한다. `pg_catalog.`을 붙여 쓴 `SELECT pg_catalog.setval(..)`은 덤프의 뒷정리이므로 시퀀스 참조도 만들지 않는다(F-019-R16은 매퍼·루틴과 그 밖의 스크립트 구문의 것이다). 컬럼 기본값 `nextval('…'::regclass)`(`CREATE TABLE`의 `DEFAULT`와 `ALTER COLUMN .. SET DEFAULT`)는 Oracle의 `DEFAULT seq.NEXTVAL`과 같은 방식으로 다룬다. | 사용자 요청, F-007-R17 |
| F-019-R23 | 시스템은 `COPY 테이블 [(컬럼, ..)] FROM stdin`과 그 데이터 줄을 그 테이블의 데이터 적재로 기록해야 한다(데이터 적재 묶음, `rowCount`는 데이터 줄 수). 파일에서 읽는 `COPY … FROM '경로'`와 `COPY … TO`는 적재로 기록하지 않고 조용히 넘긴다. | F-006의 데이터 적재 규칙 |

### 3.5 루틴과 트리거

| ID | 요구사항 | 출처 |
|---|---|---|
| F-019-R24 | 시스템은 `CREATE [OR REPLACE] FUNCTION\|PROCEDURE [스키마.]이름(매개변수, ..) [RETURNS 타입 \| RETURNS SETOF 타입 \| RETURNS TABLE (..)] 속성..`을 루틴으로 기록해야 한다. 속성(`LANGUAGE x`, `AS $$본문$$`, `IMMUTABLE`·`STABLE`·`VOLATILE`, `SECURITY DEFINER`, `SET ..` 등)은 순서와 무관하게 읽고, 매개변수는 `[IN\|OUT\|INOUT\|VARIADIC] [이름] 타입 [DEFAULT 식 \| = 식]`을 읽는다. 루틴의 `language`는 `plpgsql`이면 `PLPGSQL`, `sql`이면 `SQL`이고, 그 밖의 언어면 본문을 분석하지 않고 `language`를 넣지 않는다. 같은 이름의 오버로드는 하나로 본다. | 사용자 요청, F-008-R13 |
| F-019-R25 | 시스템은 PL/pgSQL 본문(`[<<레이블>>] [DECLARE 선언..] BEGIN 문장.. [EXCEPTION WHEN .. THEN ..] END`)을 읽어야 한다: 대입(`:=`, `=`), `IF/ELSIF/ELSE`, `CASE`, `LOOP`·`WHILE`·`FOR i IN a..b`·`FOR r IN 쿼리`·`FOR r IN EXECUTE ..`·`FOREACH x IN ARRAY ..`, `EXIT`·`CONTINUE [WHEN]`, `RETURN [식]`·`RETURN NEXT`·`RETURN QUERY 쿼리`, `PERFORM ..`, `RAISE ..`, `GET [STACKED] DIAGNOSTICS ..`, `ASSERT`, `NULL`, 커서(`OPEN`·`FETCH`·`MOVE`·`CLOSE`), `CALL`, `COMMIT`·`ROLLBACK`, 중첩 블록. 본문의 위치(줄·열)는 원본 파일 기준이다. | 사용자 요청, F-008-R1 |
| F-019-R26 | 시스템은 PL/pgSQL 본문의 SQL 구문(`SELECT .. INTO [STRICT] 변수`, `INSERT`·`UPDATE`·`DELETE [.. RETURNING .. INTO 변수]`, `FOR r IN 쿼리`·`RETURN QUERY`·`PERFORM`·커서의 쿼리)을 루틴에 속한 구문 노드로 만들고 읽기·쓰기·조인을 기록해야 하며, 매개변수·선언한 변수·레코드 변수·`FOUND`·`TG_` 변수를 컬럼으로 만들지 않아야 한다. | 사용자 요청, F-008-R2·R4 |
| F-019-R27 | 시스템은 PL/pgSQL의 호출 — `PERFORM f(..)`, `v := f(..)`, SQL 구문 안의 `f(..)`, `CALL p(..)` — 을 루틴 호출로 기록해야 한다(내장 함수 제외. `스키마.이름`의 스키마는 뺀다). | F-008-R5 |
| F-019-R28 | 시스템은 `EXECUTE 문자열식 [INTO ..] [USING ..]`을 동적 SQL로 다뤄야 한다: 문자열 리터럴과 그 연결, `format('..', ..)`의 서식 문자열에서 SQL을 풀 수 있으면 분석하고(`%I`·`%s` 자리에 온 문자열 리터럴 인자는 그 값으로 치환), 풀 수 없으면 기존 동적 SQL 규칙대로 미해결로 기록한다. | F-008-R8·R9 |
| F-019-R29 | 시스템은 `CREATE [OR REPLACE] [CONSTRAINT] TRIGGER 이름 {BEFORE\|AFTER\|INSTEAD OF} 사건 [OR 사건].. ON 테이블 [..] EXECUTE {FUNCTION\|PROCEDURE} 함수(..)`를 트리거 루틴으로 기록하고 테이블과의 `TRIGGERS_ON`, 함수로의 호출을 기록해야 한다. 트리거 함수 본문의 `NEW`·`OLD`는 변수이며, `NEW.컬럼`·`OLD.컬럼`으로 컬럼 사실을 만들지 않는다(함수만으로는 어느 테이블인지 알 수 없다). | 사용자 요청, F-008-R10 |
| F-019-R30 | 시스템은 `DO [LANGUAGE plpgsql] $$ … $$`를 익명 블록으로 읽어 그 안의 SQL 구문과 호출을 기록해야 한다. | F-008-R11 |
| F-019-R31 | 시스템은 `LANGUAGE sql` 루틴의 본문(달러 인용 안의 SQL 구문들, 또는 `RETURN 식`·`BEGIN ATOMIC .. END`)을 구문으로 읽어 루틴에 속한 구문 노드로 만들어야 하고, `$1`과 매개변수 이름을 컬럼으로 만들지 않아야 한다. | 사용자 요청 |

### 3.6 다른 모듈

| ID | 요구사항 | 출처 |
|---|---|---|
| F-019-R32 | 시스템(mybatis)은 방언별 매퍼 선택에서 F-019-R4의 순위를 쓰고, `databaseId`나 방언별 매퍼 파일에서 온 PostgreSQL 표시(`postgres`, `postgresql`, `pgsql`)를 내장 소스의 `attrs.dialect`로 넘겨야 한다. sql은 그 값을 PostgreSQL로 읽는다. | F-009-R24, 계약 3.9 |
| F-019-R33 | `--dialect postgres`인 동안, 시스템(springxml)은 배치 설정의 프로시저 이름 `A.B`를 스키마.루틴으로 읽어야 한다(DB2와 같다). | F-017의 프로시저 이름 규칙 |
| F-019-R34 | 시스템(cli)은 사용법, 알 수 없는 방언 오류 문구, 방언 힌트의 옵션 안내에 `postgres`를 넣고, 힌트의 방언 순위를 F-019-R4에 맞춰야 한다. | F-001-R53 |
| F-019-R35 | 시스템(link)은 테이블 관계를 추론할 때의 타입 비교에서 PostgreSQL의 타입 별칭을 같은 타입으로 봐야 한다: `serial`↔`integer`, `bigserial`↔`bigint`, `smallserial`↔`smallint`, `int`·`int4`↔`integer`, `int8`↔`bigint`, `int2`↔`smallint`, `varchar`↔`character varying`↔`text`, `bool`↔`boolean`, `timestamptz`↔`timestamp with time zone`, `decimal`↔`numeric`, `float4`↔`real`, `float8`↔`double precision`. | F-018의 `typeMismatch` |

### 3.7 검증

| ID | 요구사항 | 출처 |
|---|---|---|
| F-019-R36 | 시스템은 PostgreSQL 검증용 샘플 `samples/sample-pg`(pg_dump식 DDL, PL/pgSQL 루틴·트리거, MyBatis 매퍼, Spring 설정)와 그 정답 목록(있어야 할 노드·엣지, 있으면 안 되는 엣지)을 갖추고, sample-shop과 같은 방식의 통합 테스트(`SamplePgIT`)로 분석한 결과가 정답과 맞는지(통과율 100%, 함정 위반 0) 확인해야 한다. | PRD 검증 방법 |
| F-019-R37 | 시스템은 기존 샘플(`samples/sample-shop`)의 정답 대조에서 통과율 100%와 함정 위반 0을 유지해야 한다. | PRD 검증 방법 |

## 4. 수용 기준

테스트 이름이나 `@DisplayName`에 수용 기준 ID를 적는다. 각 기준의 입력은 5절의 예시 구문을 쓴다.

### F-019-AC1 (대상: F-019-R1)
- Given `--dialect postgres`, `--dialect PostgreSQL`, `--dialect pgsql`, `--dialect mysql`
- When cli가 옵션을 읽는다
- Then 앞의 셋은 `Dialect.POSTGRES`이고 `mysql`은 사용법 오류이며 오류 문구에 `oracle, db2, postgres, auto`가 있다. `Dialect.parse("postgres")`도 `POSTGRES`다.

### F-019-AC2 (대상: F-019-R2)
- Given `db/postgres/schema.sql`, `db/schema_pgsql.sql`, `db/fn.pgsql`, 경로에 방언 이름이 없고 본문에 달러 인용·`LANGUAGE plpgsql`·`ALTER TABLE ONLY`·`COPY … FROM stdin`·`bigserial` 가운데 하나가 있는 파일들, 경로가 `oracle/`인데 본문에 `::`가 있는 파일
- When sql이 파일의 방언을 판정한다
- Then 앞의 것들은 postgres이고 마지막은 oracle이다(경로가 먼저). 그 파일의 구문 노드 `dialect`는 `POSTGRES`다.

### F-019-AC3 (대상: F-019-R3)
- Given 내장 소스 `SELECT nextval('orders_id_seq')`, `SELECT a::text FROM t WHERE b ILIKE #{q}`, `SELECT NVL(a, 0) FROM t`, `SELECT a FROM t FETCH FIRST 1 ROWS ONLY WITH UR`, `SELECT a FROM t`
- When 구문 특징으로 판정한다
- Then 차례로 `POSTGRES`, `POSTGRES`, `ORACLE`, `DB2`, 키 없음이다.

### F-019-AC4 (대상: F-019-R4)
- Given 같은 테이블·같은 namespace를 정의한 방언별 파일 조합 (가) oracle·postgres (나) db2·postgres (다) postgres·tibero (라) postgres·mysql (마) postgres·mysql에 프로젝트 설정이 mysql (바) oracle·postgres에 프로젝트 설정이 postgresql (사) oracle·postgres에 `--dialect postgres`
- When 옵션 없이(마·바는 설정만, 사는 옵션) 분석한다
- Then 고른 방언은 (가) oracle (나) db2 (다) postgres (라) postgres (마) postgres (바) postgres (사) postgres다. sql의 DDL 선택과 mybatis의 매퍼 선택이 같은 방언이고, 요약 진단의 수준은 (다)(라)(마)에서 WARN이 아니다(대상 방언을 골랐다).

### F-019-AC5 (대상: F-019-R5)
- Given PostgreSQL DDL 파일 하나뿐인 프로젝트
- When 분석한다
- Then `DIALECT_NOT_TARGET`이 없다. MySQL 덤프뿐인 프로젝트에는 전처럼 있다.

### F-019-AC6 (대상: F-019-R6, F-019-R8)
- Given 기본 읽기 방식의 `SELECT A#B, $$C FROM T#1`(이름에 `#`·`$`), DB2 `--#SET TERMINATOR @` 스크립트, MySQL 줄 처음 `#` 주석 스크립트
- When 각 읽기 방식으로 읽는다
- Then 기본에서는 `A#B`·`$$C`·`T#1`이 이름이고, 기존 MySQL·DB2 테스트가 모두 그대로 통과한다. 같은 텍스트를 PostgreSQL 읽기 방식으로 읽으면 `$$`가 달러 인용의 시작이다.

### F-019-AC7 (대상: F-019-R7)
- Given 매퍼 구문 `SELECT c.profile #>> '{a,b}' FROM customers c WHERE c.id = #{id}`가 든 프로젝트에 (가) `spring.datasource.url=jdbc:postgresql://…` (나) `--dialect postgres` (다) 매퍼의 `databaseId="postgresql"` (라) 아무 표시 없음 (마) `--dialect oracle`
- When 분석한다
- Then (가)(나)(다)는 `PARSE_FAILED` 없이 `CUSTOMERS.PROFILE`·`CUSTOMERS.ID` 읽기가 기록된다. (라)는 그 구문의 `#>>`가 PostgreSQL 내용 특징이므로 PostgreSQL로 읽는다. (마)는 기본 읽기 방식이다.

### F-019-AC8 (대상: F-019-R9)
- Given `CREATE FUNCTION f() RETURNS int AS $body$ BEGIN DELETE FROM t; RETURN 1; END; $body$ LANGUAGE plpgsql; SELECT 1;`과 닫히지 않은 `$$`
- When PostgreSQL 읽기 방식으로 나눈다
- Then 실행 단위는 함수 하나와 `SELECT 1` 둘이고, 닫히지 않은 경우 진단 1건과 함께 나머지 파일 분석이 끝난다.

### F-019-AC9 (대상: F-019-R10)
- Given `a->>'k'`, `a #>> '{x,y}'`, `tags @> ARRAY['a']`, `memo ~* 'vip'`, `E'it\'s'`, `WHERE id = $1`, `#{id}`
- When PostgreSQL 읽기 방식으로 토큰을 만든다
- Then 연산자가 각각 기호 토큰 하나이고, `E'it\'s'`는 문자열 하나, `$1`과 `#{id}`는 바인드다.

### F-019-AC10 (대상: F-019-R11, F-019-R23)
- Given `COPY public.customers (id, email) FROM stdin;` 뒤에 탭으로 나눈 데이터 두 줄과 `\.`, 그 뒤의 `SELECT pg_catalog.setval('public.orders_id_seq', 10, true);`, 그리고 `\connect shop`
- When 분석한다
- Then 데이터 줄에서 나온 진단이 없고, `CUSTOMERS`의 데이터 적재 묶음(`rowCount=2`)이 있으며, `setval` 구문과 `\connect`에서 나온 구문 노드·진단이 없다(`COPY` 뒤의 구문이 데이터에 먹히지 않았다). `COPY t FROM '/tmp/a.csv'`는 적재가 아니다.

### F-019-AC11 (대상: F-019-R12)
- Given `INSERT INTO customers (email, name) VALUES (#{e}, #{n}) ON CONFLICT (email) DO UPDATE SET name = EXCLUDED.name RETURNING id`와 `… ON CONFLICT DO NOTHING`
- When 분석한다
- Then `PARSE_FAILED`가 없고 쓰기는 `CUSTOMERS`(컬럼 `EMAIL`, `NAME`)뿐이며 `EXCLUDED` 테이블이나 `EXCLUDED.NAME` 컬럼이 없다.

### F-019-AC12 (대상: F-019-R13)
- Given `DELETE FROM order_items oi USING orders o WHERE o.id = oi.order_id AND o.status = 'NEW'`
- When 분석한다
- Then 쓰기 `ORDER_ITEMS`, 읽기 `ORDERS`(컬럼 `ID`, `STATUS`), 조인 `ORDER_ITEMS.ORDER_ID = ORDERS.ID`가 기록된다.

### F-019-AC13 (대상: F-019-R14)
- Given `WITH RECURSIVE t AS (SELECT id FROM orders WHERE id = #{id} UNION ALL SELECT o.id FROM orders o JOIN t ON o.customer_id = t.id), del AS (DELETE FROM session_cache WHERE k = #{k} RETURNING k) SELECT t.id, (SELECT count(*) FROM del) FROM t`
- When 분석한다
- Then 읽기 `ORDERS`, 쓰기 `SESSION_CACHE`가 있고 `T`·`DEL` 테이블 노드가 없으며 `PARSE_FAILED`가 없다.

### F-019-AC14 (대상: F-019-R15)
- Given F-019-R15에 적은 식과 절을 하나씩 담은 구문들(5절 "DML 예시")
- When PostgreSQL 읽기 방식으로 분석한다
- Then 구문마다 `PARSE_FAILED`가 없고 읽기·쓰기 테이블이 예시의 기대와 같으며, 별칭·함수 별칭·타입 이름이 테이블이나 컬럼이 되지 않는다.

### F-019-AC15 (대상: F-019-R16)
- Given 매퍼 구문 `SELECT nextval('orders_id_seq')`, `INSERT INTO orders (id) VALUES (nextval('public.orders_id_seq'::regclass))`, `SELECT setval('s', 1)`, `SELECT nextval(v_name)`
- When 분석한다
- Then 앞의 셋은 시퀀스 `ORDERS_ID_SEQ`·`S` 참조가 Oracle `seq.NEXTVAL`과 같은 엣지로 기록되고 마지막은 참조가 없다. 컬럼 기본값의 `nextval(..)`은 Oracle `DEFAULT seq.NEXTVAL`이 지금 내는 결과와 같은 꼴이다.

### F-019-AC16 (대상: F-019-R17)
- Given `SELECT relname FROM pg_class`, `SELECT * FROM pg_catalog.pg_tables`, `SELECT * FROM information_schema.columns`, `SELECT now()`, 그리고 기본 읽기 방식의 Oracle 매퍼 구문 `SELECT … FROM PG_STAT_DAILY`·`FROM PG_SETTINGS`
- When 앞의 넷은 PostgreSQL 읽기 방식으로, 마지막은 기본 읽기 방식으로 분석한다
- Then 앞의 셋은 시스템 카탈로그로 표시되고 "DDL에 없는 테이블" 의심 대상이 아니며, `SELECT now()`는 테이블 참조가 없고 진단이 없다. Oracle 매퍼의 `PG_STAT_DAILY`·`PG_SETTINGS`에는 `systemCatalog`가 붙지 않는다.

### F-019-AC17 (대상: F-019-R18)
- Given `CREATE TABLE public.orders (id bigint NOT NULL, customer_id bigint)`와 `ALTER TABLE ONLY public.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);`, `ALTER TABLE ONLY public.orders ADD CONSTRAINT fk FOREIGN KEY (customer_id) REFERENCES public.customers(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;`, `ALTER TABLE IF EXISTS ONLY public.orders ADD COLUMN memo text;`
- When 분석한다
- Then `ORDERS`의 기본 키가 `ID`이고 `ORDERS → CUSTOMERS` 외래 키(DDL 출처)가 있으며 `MEMO` 컬럼이 있고, `ONLY`·`IF`·`EXISTS`라는 테이블이 없다.

### F-019-AC18 (대상: F-019-R19)
- Given `CREATE UNLOGGED TABLE IF NOT EXISTS session_cache (k text PRIMARY KEY, v bytea)`, `CREATE TEMP TABLE tmp_x (id serial, tags text[], at timestamp with time zone DEFAULT now())`
- When 분석한다
- Then 두 테이블이 정의로 기록되고 `TMP_X.ID`가 `identity=true`, `TAGS`의 타입이 `TEXT[]`, `AT`의 타입이 `TIMESTAMP WITH TIME ZONE`이다.

### F-019-AC19 (대상: F-019-R20)
- Given `orders(id, amount)`가 정의된 뒤 `CREATE TABLE audit_log (LIKE public.orders INCLUDING ALL)`, `CREATE TABLE vip_orders (grade int) INHERITS (orders)`, `CREATE TABLE orders_2026 PARTITION OF orders FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')`, 그리고 정의가 없는 `ghost`를 `LIKE`한 테이블
- When 분석한다
- Then `AUDIT_LOG`는 `ID`·`AMOUNT`, `VIP_ORDERS`는 `ID`·`AMOUNT`·`GRADE`, `ORDERS_2026`은 `ID`·`AMOUNT`와 `partitionOf=ORDERS`를 가지며, `ghost`를 `LIKE`한 테이블은 컬럼이 없다.

### F-019-AC20 (대상: F-019-R21)
- Given `CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_c ON public.customers USING gin (profile jsonb_path_ops)`, `CREATE UNIQUE INDEX idx_o ON public.orders USING btree (customer_id, ordered_at DESC NULLS LAST) WHERE (status <> 'SHIPPED')`, `CREATE INDEX idx_l ON customers (lower(email))`
- When 분석한다
- Then `CUSTOMERS`에 `IDX_C(PROFILE)`, `ORDERS`에 유일 인덱스 `IDX_O(CUSTOMER_ID, ORDERED_AT)`가 기록되고, `IDX_L`은 컬럼 없는 인덱스로 기록되거나 생략되되 `LOWER`·`JSONB_PATH_OPS`·`GIN`이라는 컬럼이 없다.

### F-019-AC21 (대상: F-019-R22)
- Given pg_dump 머리말과 부속 구문(5절 "pg_dump 예시")만 있는 파일
- When 분석한다
- Then `PARSE_FAILED`·`UNSUPPORTED_SYNTAX`가 없고 테이블·구문 노드가 없다.

### F-019-AC22 (대상: F-019-R24)
- Given 5절 "루틴 예시"의 `close_order`(본문 뒤에 `LANGUAGE plpgsql`이 오는 꼴과 앞에 오는 꼴), `RETURNS TABLE (id bigint, name text)` 함수, `LANGUAGE c` 함수
- When 분석한다
- Then 루틴 노드의 `routineType`·`parameters`(`P_ORDER_ID BIGINT`, `P_MEMO TEXT DEFAULT NULL`)·`returnType`·`schema`가 맞고 `language`가 `PLPGSQL`, `dialect`가 `POSTGRES`다. `LANGUAGE c` 함수는 노드만 있고 `language`가 없으며 진단이 없다.

### F-019-AC23 (대상: F-019-R25)
- Given F-019-R25의 문장을 모두 담은 본문(5절 "루틴 예시")
- When 본문을 파싱한다
- Then `PARSE_FAILED`·`UNSUPPORTED_SYNTAX`가 없고, 본문 안 구문의 위치가 원본 파일의 줄·열이다.

### F-019-AC24 (대상: F-019-R26)
- Given `close_order` 본문의 `UPDATE orders SET status = 'SHIPPED', memo = coalesce(p_memo, memo) WHERE id = p_order_id`, `FOR r IN SELECT order_id, qty FROM order_items WHERE order_id = p_order_id LOOP INSERT INTO audit_log (id, memo) VALUES (r.order_id, 'x' || r.qty::text); END LOOP`, `SELECT count(*) INTO v_cnt FROM orders WHERE customer_id = v_id`
- When 분석한다
- Then 루틴에 속한 구문 노드가 문장마다 하나씩 있고(UPDATE, SELECT, INSERT, SELECT) 읽기·쓰기가 맞으며, `ORDERS.P_MEMO`·`ORDERS.P_ORDER_ID`·`ORDERS.V_ID`·`R` 같은 컬럼·테이블이 없다. 루틴의 `statementCount`가 0이 아니다.

### F-019-AC25 (대상: F-019-R27)
- Given 본문의 `PERFORM public.touch_customer(p_order_id);`, `v := calc_total(p_id);`, `SELECT calc_total(o.id) INTO v FROM orders o;`, `CALL purge_old(30);`, `PERFORM pg_sleep(1);`
- When 분석하고 링커를 돌린다
- Then 루틴에서 `TOUCH_CUSTOMER`·`CALC_TOTAL`·`PURGE_OLD`로의 호출이 있고 `PG_SLEEP` 호출은 없다.

### F-019-AC26 (대상: F-019-R28)
- Given `EXECUTE 'DELETE FROM session_cache WHERE k = $1' USING p_k;`, `EXECUTE format('DELETE FROM %I WHERE k = $1', 'session_cache') USING p_k;`, `EXECUTE format('DELETE FROM %I', p_table);`
- When 분석한다
- Then 앞의 둘은 `SESSION_CACHE` 쓰기가 기록되고 마지막은 기존 동적 SQL 미해결 기록과 같은 꼴로 남는다.

### F-019-AC27 (대상: F-019-R29)
- Given 트리거 함수 `trg_orders_audit()`(본문: `INSERT INTO audit_log (id, memo) VALUES (NEW.id, TG_OP); RETURN NEW;`)와 `CREATE TRIGGER orders_audit AFTER INSERT OR UPDATE ON public.orders FOR EACH ROW EXECUTE FUNCTION public.trg_orders_audit();`
- When 분석한다
- Then 트리거 루틴 `ORDERS_AUDIT`가 `ORDERS`에 `TRIGGERS_ON`, `TRG_ORDERS_AUDIT`를 호출하고, 함수의 구문이 `AUDIT_LOG` 쓰기를 기록하며, `NEW`·`TG_OP`에서 나온 테이블·컬럼이 없다.

### F-019-AC28 (대상: F-019-R30)
- Given `DO $$ BEGIN DELETE FROM session_cache; PERFORM touch_customer(1); END $$;`
- When 분석한다
- Then 익명 블록의 구문이 `SESSION_CACHE` 쓰기를, 블록이 `TOUCH_CUSTOMER` 호출을 기록하고 `UNSUPPORTED_SYNTAX`가 없다.

### F-019-AC29 (대상: F-019-R31)
- Given `CREATE PROCEDURE purge_old(p_days int) LANGUAGE sql AS $$ DELETE FROM audit_log WHERE at < now() - (p_days || ' days')::interval; $$;`와 `CREATE FUNCTION cnt(bigint) RETURNS bigint LANGUAGE sql AS 'SELECT count(*) FROM orders WHERE customer_id = $1';`, `CREATE FUNCTION one() RETURNS int LANGUAGE sql RETURN 1;`
- When 분석한다
- Then 루틴의 `language`가 `SQL`이고 구문 노드가 `AUDIT_LOG` 쓰기(`AT` 읽기)와 `ORDERS` 읽기를 기록하며 `P_DAYS` 컬럼이 없다.

### F-019-AC30 (대상: F-019-R32)
- Given 같은 namespace의 매퍼가 `Order_SQL_oracle.xml`·`Order_SQL_postgres.xml`로 있는 프로젝트와, `databaseId="postgresql"` 구문이 든 매퍼
- When mybatis가 (가) 옵션 없이 (나) `--dialect postgres`로 분석한다
- Then (가)는 oracle, (나)는 postgres 파일의 구문을 쓰고, postgres에서 온 구문의 내장 소스 `attrs.dialect`가 `postgres`(databaseId면 `postgresql`)다. sql의 방언 토큰 표와 mybatis의 표가 같다(양쪽 표 테스트).

### F-019-AC31 (대상: F-019-R33)
- Given 배치 XML의 스텝이 프로시저 `batch.sp_settle`을 실행하는 설정
- When `--dialect postgres`로 분석한다
- Then 실행 대상이 스키마를 뗀 `db:routine:SP_SETTLE`이다(DB2와 같다). 옵션이 없으면 전처럼 패키지.프로시저로 본 `db:routine:BATCH.SP_SETTLE`이다.

### F-019-AC32 (대상: F-019-R34)
- Given `analyze --help`, `--dialect foo`, `DIALECT_VARIANT_DDL`·`DIALECT_NOT_FOUND` 진단이 있는 그래프
- When cli가 출력한다
- Then 사용법과 오류 문구에 `postgres`가 있고 힌트의 옵션 안내가 `--dialect <oracle|db2|postgres>`다. 계약 문서 4.5의 사용법과 README가 같은 값을 적는다.

### F-019-AC33 (대상: F-019-R35)
- Given `orders.customer_id bigint` = `customers.id bigserial` 조인과 `a.code varchar(10)` = `b.code character varying(10)` 조인, `a.memo text` = `b.memo varchar(200)` 조인, `a.n integer` = `b.n text` 조인
- When 관계를 추론한다
- Then 앞의 셋은 `typeMismatch`가 없고 마지막은 있다.

### F-019-AC34 (대상: F-019-R36)
- Given `samples/sample-pg`와 그 `expected/`
- When 통합 테스트가 옵션 없이 분석해 정답과 대조한다(`-Dsample.verify=report`면 단언 없이 리포트만 낸다)
- Then 있어야 할 노드·엣지의 통과율이 100%이고 있으면 안 되는 엣지 위반이 0이며, `PARSE_FAILED`·`UNSUPPORTED_SYNTAX`가 샘플의 "일부러 넣은 미지원 구문" 목록에 있는 것뿐이다.

### F-019-AC35 (대상: F-019-R37)
- Given `samples/sample-shop`
- When `mvn -q -pl codeatlas-cli verify`를 돌린다
- Then 통과율 100%, 함정 위반 0이 유지된다.

## 5. 가정

- 가정: PostgreSQL을 "범위 밖 방언을 덜 틀리게"가 아니라 Oracle·DB2와 같은 대상 방언으로 넣는다. 사용자가 방식(ADR-0012)만 골랐고 지원 수준은 정하지 않았으므로, 조사 때 권한 순서의 끝(대상 방언 승격)까지를 범위로 잡았다.
- 가정: **방언 순위(F-019-R4)**: ① 명시값 — sql은 `--dialect oracle|db2|postgres`가 먼저, mybatis는 `mybatis.dialectVariant`가 먼저 ② 프로젝트 설정이 가리키는 대상 방언(oracle, db2, postgres) ③ Oracle ④ DB2 ⑤ PostgreSQL ⑥ Oracle 계열(Tibero, Altibase. 설정이 이 가운데 하나를 가리키면 그것이 먼저) ⑦ 프로젝트 설정이 가리키는 대상 외 방언 ⑧ 그 밖의 방언(F-007-R28·F-009-R26의 순서 그대로). PostgreSQL을 Oracle·DB2 뒤에 둔 것은 Oracle·DB2 파일이 있는 프로젝트의 기존 결과를 바꾸지 않기 위해서다. Oracle·DB2 파일이 없고 PostgreSQL 파일이 있는 프로젝트는 결과가 달라진다(전에는 Tibero·Altibase나 사전순으로 앞선 mysql 등을 골랐다).
- 가정: 방언 표준 이름 목록의 순서는 `oracle`, `db2`, `postgres`, `tibero`, `altibase`, `mysql`, `mariadb`, `mssql`, `cubrid`, `goldilocks`, `hsql`, `h2`, `derby`, `sqlite`, `sybase`, `informix`로 바꾼다(앞의 다섯이 기본 선호 순서). 별칭은 그대로다.
- 가정: **PostgreSQL 내용 특징**(파일 본문, F-019-R2): 달러 인용(`$$`, `$태그$`), `LANGUAGE plpgsql`, `ALTER TABLE ONLY`, `COPY … FROM stdin`, `CREATE EXTENSION`, `::regclass`, `SET search_path`, `pg_catalog.`, 타입 `serial`·`bigserial`·`smallserial`·`jsonb`·`bytea`·`timestamptz`, `OWNER TO`. HSQL·MySQL 특징이 있으면 그쪽이 먼저고(기존 순서), PostgreSQL 특징이 Oracle·DB2 특징보다 먼저다(`NUMBER(`·`VARCHAR2`는 PostgreSQL 파일에 나오지 않는다).
  - (2026-10-11 리뷰 뒤 고침) 내용 특징은 **코드에 있는 것만** 센다: `--`·`/* */` 주석과 작은따옴표 문자열 안의 일치는 세지 않는다. 특징을 강한 것(`AS`·`DO` 뒤의 달러 인용, `LANGUAGE plpgsql`, `ALTER TABLE ONLY`, `COPY … FROM stdin`, `CREATE EXTENSION`, `::regclass`, `SET search_path`, `pg_catalog.`)과 약한 것(`OWNER TO`, 타입 이름, 컬럼 타입 자리의 `serial`, `#>`·`#>>`)으로 나눈다. 판정 순서는 HSQL → MySQL → PostgreSQL 강한 특징 → Oracle → DB2 → PostgreSQL 약한 특징이다(약한 특징은 Oracle·DB2 특징이 없을 때만 PostgreSQL로 판정한다). 처음 구현은 원문 전체에 맞추고 Oracle 특징보다 먼저 적용해, 주석에 `owner to`가 있는 Oracle 스크립트를 PostgreSQL로 읽었다(F-019 이전 결과에서 달라진 회귀였다).
- 가정: **PostgreSQL 구문 특징**(토큰 점수, F-019-R3): `::`, `ILIKE`, `nextval(`·`currval(`·`setval(`(괄호가 붙은 꼴), 달러 인용, `ON CONFLICT`, `RETURNING`(단독으로는 0점. Oracle에도 있다), `LIMIT`(MySQL과 겹쳐 0점), `NOW()`, `->>`·`#>>`·`@>`, 타입 `SERIAL`·`BIGSERIAL`·`JSONB`·`BYTEA`·`TIMESTAMPTZ`, `PLPGSQL`, `PG_CATALOG`, `PERFORM`, `RAISE NOTICE|EXCEPTION`, `DISTINCT ON`. PostgreSQL에만 있는 표기(달러 인용, `::`, `ILIKE`, 괄호 붙은 `nextval(`)가 있으면 `:=`·`NEXTVAL`로 Oracle 점수를 주지 않는다.
- 구현하며 좁힌 판정 규칙(2026-10-11, 위 두 "특징" 가정을 다음처럼 좁혔다. 다른 방언의 기존 판정을 바꾸지 않기 위해서다):
  - 달러 인용은 `AS`·`DO` 바로 뒤의 것만 특징으로 본다. 맨 `$$`는 Oracle 이름(`A$$B`)과 PL/SQL 조건부 컴파일(`$$PLSQL_UNIT`)에도 나온다.
  - `serial` 류는 컬럼 정의의 타입 자리일 때만 특징이다(`SERIAL`이라는 컬럼 이름, Oracle `SERIAL#`). Informix·MySQL의 `id SERIAL` DDL은 다른 표시가 없으면 postgres로 판정될 수 있다.
  - 내용 특징에 `#>`·`#>>`(앞이 이름 글자가 아닐 때)를 더했다(F-019-AC7 (라)).
  - `NOW()`와 `->>`는 MySQL에도 있어 점수를 주지 않는다. 괄호 붙은 `nextval(`는 첫 인자가 문자열일 때만 PostgreSQL 전용 표기다(MariaDB `NEXTVAL(seq)`).
  - PostgreSQL 전용 표기가 있으면 `FETCH FIRST`·`LANGUAGE SQL`의 DB2 점수도 주지 않는다(`LANGUAGE SQL AS $$..$$` 함수가 동점이 되지 않게).
  - 프로젝트 방언이 기본 읽기 방식을 강제하는 것은 `--dialect oracle|db2`뿐이다. 프로젝트 설정·`mybatis.dialectVariant`·스크립트가 다른 방언을 가리켜도 본문 특징을 본다(기존 MySQL 규칙과 같다). 스크립트에서 고른 방언은 postgres일 때만 내장 소스의 읽기 방식이 된다. 프로젝트가 PostgreSQL로 선언됐으면 백틱이 있는 본문도 PostgreSQL로 읽는다.
  - F-019-AC6의 `$$C`: 기본 읽기 방식의 렉서는 이름이 `$`로 시작하지 못해 `$$C`를 기호 둘과 이름 `C`로 읽는다. 그 동작을 그대로 두고(F-019-R8), 이름 안의 `$$`는 `A$$B`·`C$$`로 확인한다.
- 구현하며 정한 것(3단계, 2026-10-11): PostgreSQL 읽기 방식에서도 이름 안의 `$`는 이름 글자다(`a$b`). 달러 인용은 토큰이 `$`로 시작할 때만이고, 이름 글자에서 빠지는 것은 `#`뿐이다. `--dialect oracle|db2`를 주면 기본 읽기 방식이 강제되어 `#>>`를 읽지 못한다(F-019-AC7 (마): `PARSE_FAILED` 1건과 테이블 읽기만 남는다). 기본 읽기 방식에서 첨자는 수·바인드로 시작하는 `a[1]`만 읽는다(이름이 든 대괄호는 MSSQL의 이름 표기와 겹친다). 읽지 못하는 것으로 남긴 표기: `BETWEEN SYMMETRIC`, `TABLESAMPLE`, `~~`, `COLLATE "x"`, `(행).필드`, `OPERATOR(..)`, `ORDER BY .. USING <`, 구문 중간의 psql 메타 명령(`\gset`).
- 구현하며 정한 것(4단계 DDL, 2026-10-11):
  - `LIKE`는 PostgreSQL처럼 절이 놓인 자리에 컬럼을 넣고(F-019-R20의 "자기 컬럼보다 앞"은 `INHERITS`·`PARTITION OF`의 것이다), 타입과 NOT NULL은 항상, 기본값은 `INHERITS`·`PARTITION OF`와 `LIKE .. INCLUDING DEFAULTS|ALL`일 때, 기본 키·유일은 `LIKE .. INCLUDING INDEXES|ALL`일 때만 가져온다. 주석은 가져오지 않는다.
  - 부모·원본의 정의는 뒤 구문이나 뒤 파일에 있어도 된다. 다만 부모·원본에 `ALTER TABLE`로 더한 컬럼은 자식·파티션·복사본에 따라오지 않는다. 뷰를 `LIKE`하면 컬럼이 없다.
  - 부모·원본의 정의가 없는 테이블은 컬럼이 다 알려지지 않은 테이블로 표시해, 그 테이블의 컬럼 참조를 `UNKNOWN_COLUMN`으로 단정하지 않는다.
  - 겹치지 않는 DDL 문법은 합집합에 넣었으므로(ADR-0012) 기본·MySQL 읽기 방식에서도 다음이 달라진다: `id SERIAL` 컬럼이 `identity=true`, `(LIKE b)`가 b의 컬럼을 가져옴, 이름 없는 `CREATE INDEX ON t (a)`가 인덱스로 기록됨, `ALTER TABLE ONLY t`·`ALTER TABLE IF EXISTS t`가 t에 적용됨. F-019-R8의 "전과 같은 결과"는 이 문법을 쓰지 않는 입력에 대한 것이다.
  - `REFRESH MATERIALIZED VIEW`, `CREATE POLICY|RULE|STATISTICS|FOREIGN TABLE`은 지원하지 않고 `UNSUPPORTED_SYNTAX`로 남긴다. `CREATE TEMPORARY VIEW`는 기존 규칙대로 조용히 넘어간다.
- 구현하며 정한 것(5단계 루틴, 2026-10-11):
  - `PERFORM f(..);`는 구문 노드 없이 루틴에서 루틴으로 가는 호출이다. `PERFORM 1 FROM t ..`처럼 쿼리인 것은 `SELECT` 구문 노드다.
  - 트리거 루틴(`CREATE TRIGGER`)에는 본문이 없으므로 `language`·`statementCount`·`dynamicSql`을 붙이지 않는다. `NEW`·`OLD`·`TG_*`는 `RETURNS trigger`인 함수에서만 변수다.
  - 동적 SQL의 `dynamicKind`는 `EXECUTE`다(계약 3.5에 값을 더했다). `format()`은 `%I`·`%s`·`%L`·`%%`·`%1$s`와 `quote_ident`·`quote_literal`, 인자 뒤의 캐스트까지 푼다.
  - PL/pgSQL 식의 `nextval('seq')`와 `PERFORM setval('seq', ..)`는 Oracle 루틴의 `seq.NEXTVAL`(F-008-R15)과 같은 꼴로 남긴다. 본문의 `CREATE [TEMP] TABLE t AS 쿼리`는 t에 대한 쓰기와 쿼리의 읽기를 가진 구문이다.
  - `parameters`: 이름 없는 매개변수는 타입만, `IN OUT`은 `INOUT`, 기본값은 적힌 대로(`= 0`, `DEFAULT NULL`). `statementCount`는 그 루틴 노드에 속한 구문 노드의 수다(오버로드 여러 개나 같은 함수의 재정의가 있어도 그래프의 구문 노드 수와 같다. 리뷰 뒤 고침: 처음에는 본문마다 더해 재정의에서 실제 노드 수와 어긋났다). 같은 루틴이 여러 파일에서 정의되면 어느 것이 나중 것인지 파일 이름으로는 알 수 없으므로(`V10` < `V2`) 본문을 모두 남긴다. 루틴의 중복 정의를 알리는 진단은 없다(계약 3.7의 `DUPLICATE_OBJECT_NAME`은 테이블·뷰·빈만 다룬다. Oracle·DB2 루틴도 같다).
  - 알려진 한계: 같은 이름의 함수와 트리거는 한 노드로 합쳐진다(PostgreSQL에서는 이름 공간이 다르다). `LANGUAGE`가 없는 문자열 본문 함수와 plpgsql이 아닌 `DO`는 조용히 넘긴다. `LANGUAGE sql` 본문에서 `(`로 시작하는 구문은 `UNSUPPORTED_SYNTAX`다. 작은따옴표 본문에서 `''`가 있는 줄은 그 뒤의 열이 조금 밀린다(줄은 맞다). `FOR a, b IN ..`은 첫 대상만 루프 변수다. 선언부에서 `SELECT`·`IF` 같은 키워드와 같은 이름의 변수는 읽지 못한다. 트리거의 `REFERENCING NEW TABLE AS x`의 이름을 함수 본문의 `FROM`에 쓰면 테이블로 남는다. 본문의 `COPY`·`CREATE VIEW`는 진단 없이 넘긴다.
- 검증 결과(2026-10-11, 리뷰 지적 네 건을 고친 뒤 다시 돌린 것): 루트 `mvn clean install` 통과(단위 테스트와 통합 테스트 2790건). `samples/sample-pg` 엣지 122/122·노드 139/139·함정 위반 0·목록 밖 진단 0, `samples/sample-shop` 엣지 417/417·노드 418/418·함정 위반 0. 변경 전 커밋의 jar와 새 jar로 `samples/sample-shop`과 `~/workspace/codeatlas-corpus`의 공개 프로젝트 8개를 옵션 없이 분석해 `graph.json`을 비교했더니 노드와 엣지는 하나도 달라지지 않았고 방언 진단의 문구(대상 방언에 PostgreSQL, 방언 나열 순서)만 달랐다. 공개 프로젝트 둘(전자정부 enterprise-business-template, common-components)을 `--dialect postgres`로 돌리면 postgres 매퍼 구문 201개·1245개와 DDL이 전과 같은 수의 진단으로 읽히고 구문의 `dialect`가 `POSTGRES`로 찍힌다.
- 가정: PostgreSQL 버전은 현재 지원되는 버전대(13 이상)의 문법을 기준으로 한다. `EXECUTE PROCEDURE`(옛 표기)도 받는다.
- 가정: 따옴표 없는 이름은 지금처럼 대문자로 정규화한다(PostgreSQL은 소문자로 접지만 맞추는 결과는 같다). ERD와 리포트에는 `CUSTOMERS`처럼 대문자로 나온다. 따옴표로 감싼 이름(`"camelCase"`)은 기존 규칙대로 원문 그대로다.
  - (2026-10-11 리뷰 뒤 고침) PostgreSQL 읽기 방식에서 따옴표로 감싼 이름에 대문자가 없으면(`"orders"`, `"user"`) 따옴표 없는 이름과 같은 이름으로 정규화한다(`ORDERS`, `USER`). PostgreSQL에서 둘은 같은 객체다. 대문자가 든 따옴표 이름(`"createdAt"`, `"ORDERS"`)은 원문 그대로다. 그래서 따옴표로 감싼 전부 대문자인 이름(`"ORDERS"`)은 따옴표 없는 `orders`와 같은 노드가 된다(PostgreSQL에서는 다른 객체다. 둘이 한 스키마에 함께 있는 경우만 틀린다). `nextval('"seq"')`의 이름과 `format('%I', ..)`이 만든 이름도 같은 규칙이다. 처음 구현은 따옴표 이름을 원문 그대로 두어, 이름을 모두 따옴표로 감싼 DDL과 따옴표 없이 쓴 SQL이 서로 다른 테이블이 됐다.
- 가정: 파티션(`PARTITION OF`)은 테이블 노드로 두고 `partitionOf`만 붙인다. ERD에서 파티션을 접어 보이는 일은 이 기능의 범위가 아니다.
- 가정: 같은 이름의 오버로드 함수는 하나로 본다(F-007·F-008의 기존 가정과 같다).
- 가정: 트리거 함수의 `NEW.컬럼`은 어느 테이블의 컬럼인지 함수만으로는 알 수 없으므로 컬럼 사실을 만들지 않는다. `CREATE TRIGGER`로 이어진 테이블을 따라가 채우는 것은 추측이 섞이므로(한 함수를 여러 테이블의 트리거가 쓴다) 하지 않는다.
- 가정: `COPY … FROM stdin`의 데이터 줄 수를 `rowCount`로 적는다. 데이터의 값은 읽지 않는다.
- 가정: 샘플은 기존 `samples/sample-shop`에 섞지 않고 `samples/sample-pg`로 따로 둔다. sample-shop에 PostgreSQL DDL을 더하면 방언 여러 벌 선택이 끼어들어 기존 정답이 흔들린다.
- 가정: 실제 PostgreSQL에서 샘플 SQL을 실행해 보지 않는다(DB 없음. sample-shop과 같다).

### 예시 구문

DML 예시(F-019-AC14). 테이블 `customers(id, email, name, tags, profile, created_at)`, `orders(id, customer_id, status, amount, memo, ordered_at)`가 정의돼 있다.

```sql
SELECT o.id, o.amount::text AS amt, c.profile->>'grade' AS grade, c.profile #>> '{addr,city}' AS city
  FROM orders o JOIN customers c ON c.id = o.customer_id
 WHERE c.name ILIKE '%' || #{q} || '%' AND c.tags @> ARRAY[#{tag}]::text[] AND o.memo ~* 'vip'
   AND o.ordered_at >= now() - interval '7 days'
 ORDER BY o.ordered_at DESC NULLS LAST LIMIT #{size} OFFSET #{offset}
-- 읽기: ORDERS, CUSTOMERS. 조인: ORDERS.CUSTOMER_ID = CUSTOMERS.ID

SELECT DISTINCT ON (c.id) c.id, x.amount, g.n
  FROM customers c
  LEFT JOIN LATERAL (SELECT amount FROM orders o WHERE o.customer_id = c.id ORDER BY ordered_at DESC LIMIT 1) x ON true
  CROSS JOIN generate_series(1, 3) AS g(n)
 WHERE c.id = ANY(#{ids}::bigint[])
   FOR UPDATE OF c SKIP LOCKED
-- 읽기: CUSTOMERS, ORDERS. X·G·GENERATE_SERIES는 테이블이 아니다

SELECT c.id, count(o.id) FILTER (WHERE o.status = 'PAID') AS paid, c.tags[1]
  FROM customers c LEFT JOIN orders o ON o.customer_id = c.id
 WHERE o.status IS DISTINCT FROM 'NEW' GROUP BY c.id
-- 읽기: CUSTOMERS, ORDERS

UPDATE ONLY orders o SET status = 'SHIPPED' FROM customers c WHERE c.id = o.customer_id AND c.email = #{email} RETURNING *
-- 쓰기: ORDERS. 읽기: CUSTOMERS

TRUNCATE TABLE ONLY orders, customers RESTART IDENTITY CASCADE
-- 쓰기: ORDERS, CUSTOMERS
```

pg_dump 예시(F-019-AC21).

```sql
SET statement_timeout = 0;
SET client_encoding = 'UTF8';
SELECT pg_catalog.set_config('search_path', '', false);
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';
CREATE SCHEMA shop;
ALTER SCHEMA shop OWNER TO app;
CREATE TYPE public.order_status AS ENUM ('NEW', 'PAID', 'SHIPPED');
CREATE DOMAIN public.email AS text CHECK (VALUE ~ '@');
ALTER TYPE public.order_status OWNER TO app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
```

루틴 예시(F-019-AC22~AC24).

```sql
CREATE OR REPLACE FUNCTION public.close_order(p_order_id bigint, p_memo text DEFAULT NULL)
RETURNS integer
LANGUAGE plpgsql
AS $$
<<main>>
DECLARE
    v_cnt integer := 0;
    v_id orders.customer_id%TYPE;
    r record;
    c CURSOR FOR SELECT id FROM orders;
BEGIN
    UPDATE orders SET status = 'SHIPPED', memo = coalesce(p_memo, memo) WHERE id = p_order_id;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;
    FOR r IN SELECT order_id, qty FROM order_items WHERE order_id = p_order_id LOOP
        INSERT INTO audit_log (id, memo) VALUES (r.order_id, 'x' || r.qty::text);
        CONTINUE WHEN r.qty = 0;
    END LOOP;
    FOR i IN 1..3 LOOP NULL; END LOOP;
    WHILE v_cnt > 10 LOOP v_cnt := v_cnt - 1; EXIT WHEN v_cnt = 5; END LOOP;
    IF NOT FOUND THEN
        RAISE NOTICE 'no items for %', p_order_id;
    ELSIF v_cnt > 1 THEN
        RAISE EXCEPTION 'too many' USING ERRCODE = 'P0001';
    ELSE
        v_cnt = 1;
    END IF;
    SELECT count(*) INTO STRICT v_cnt FROM orders WHERE customer_id = v_id;
    PERFORM public.touch_customer(p_order_id);
    EXECUTE format('DELETE FROM %I WHERE k = $1', 'session_cache') USING p_order_id::text;
    BEGIN
        INSERT INTO session_cache (k) VALUES ('x');
    EXCEPTION WHEN unique_violation OR others THEN
        NULL;
    END;
    RETURN v_cnt;
EXCEPTION WHEN no_data_found THEN
    RETURN -1;
END main;
$$;
```

## 6. 미확정 사항
사용자가 2026-10-11에 방식만 정하고 진행을 지시했으므로 아래는 가정(5절)을 기본값으로 두고 진행한다. 검토가 필요하다.

- [확인 필요] 지원 수준: 대상 방언 승격까지인지, 범위 밖 방언의 틀린 사실 제거까지인지. 가정은 승격이다.
- [확인 필요] 방언 순위에서 PostgreSQL의 자리(Oracle·DB2 뒤, Oracle 계열 앞). mysql·postgres 두 벌이면 전에는 mysql, 이제는 postgres를 고른다.
- [확인 필요] ERD와 리포트의 이름 표기(대문자 유지).
- [확인 필요] 같은 루틴이 여러 파일에서 정의됐을 때(마이그레이션의 `CREATE OR REPLACE`) 알리는 진단이 없다. Oracle·DB2도 같다. 진단을 더하려면 계약 3.7을 먼저 고쳐야 한다.
- [확인 필요] 파티션 테이블을 ERD에서 따로 보일지 접을지.

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 설계: [design.md](design.md)
- 작업: [tasks.md](tasks.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md) 3.5, 3.7, 3.8, 4.5
- 관련 기능: F-006(SQL 파서), F-007(DDL 파서), F-008(프로시저 분석), F-009(MyBatis), F-017(Spring XML), F-018(관계 추론)
- 관련 ADR: [ADR-0004](../../decisions/0004-hand-written-tolerant-sql-parser.md), [ADR-0010](../../decisions/0010-select-one-dialect-variant-by-target-first-ranking.md), [ADR-0012](../../decisions/0012-pass-sql-syntax-to-parsers.md)
