---
type: spec
id: F-007
title: DDL 파서
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-007 DDL 파서

## 1. 배경
ERD(PRD 목표 2)는 테이블, 컬럼, 키, 외래 키를 알아야 그릴 수 있고, 이 도구는 DB에 접속하지 않으므로 DDL 파일이 유일한 원천이다. DDL에서 만든 카탈로그(테이블→컬럼, 키)는 F-006이 비한정 컬럼의 소속 테이블을 정하고 조인을 뽑을 때도 쓴다.

## 2. 범위
- 포함: Oracle·DB2 LUW의 CREATE TABLE, ALTER TABLE, COMMENT ON, CREATE INDEX, CREATE VIEW/MATERIALIZED VIEW, CREATE SEQUENCE, CREATE SYNONYM/ALIAS 파싱과 그래프 기록, 카탈로그 구성, 루틴 단위의 헤더(종류·이름·트리거 대상) 선언.
- 제외: 루틴 본문 분석(F-008), 시노님 대상의 실제 종류 맞추기(F-012), 추론 관계(F-018), 스토리지·파티션 등 물리 속성.
- 범위 밖 방언: MySQL·MariaDB, PostgreSQL, HSQL, MSSQL 등의 DDL은 지원 대상이 아니다. 죽지 않고, 틀린 사실(가짜 컬럼 등)을 만들지 않고, 경고를 쏟아내지 않는 것까지만 목표로 한다(F-007-R16, R17).

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-007-R1 | 시스템은 CREATE TABLE에서 `DB_TABLE` 노드(attrs.schema)와 컬럼마다 `DB_COLUMN` 노드(attrs.dataType, nullable, ordinal, defaultValue)를 선언하고 TABLE→CONTAINS→COLUMN 엣지를 만들어야 한다. | 그래프 계약 3.3~3.5 |
| F-007-R2 | 시스템은 인라인·테이블 수준·ALTER TABLE의 PRIMARY KEY와 UNIQUE를 컬럼 attrs(primaryKey, unique)와 테이블 attrs.primaryKey(컬럼 목록)로 기록해야 한다. | PRD 목표 2 |
| F-007-R3 | 시스템은 외래 키마다 자식→부모 `REFERENCES` 엣지(attrs.columns, refColumns, constraint, source=DDL, onDelete)를 만들어야 하며, 참조 컬럼이 생략되면 정의 순서와 무관하게 부모의 PK로 채워야 한다. | 그래프 계약 3.4 |
| F-007-R4 | 시스템은 ALTER TABLE의 컬럼 추가·변경(ADD, MODIFY, ALTER COLUMN)을 카탈로그와 컬럼 노드에 반영해야 한다. | 사용자 요청 |
| F-007-R5 | 시스템은 COMMENT ON TABLE/COLUMN의 주석을 노드 attrs.comment에 기록해야 한다. | 그래프 계약 3.5 |
| F-007-R6 | 시스템은 CREATE [UNIQUE] INDEX를 테이블 attrs.indexes와 컬럼 attrs.indexed로 기록해야 한다. | 사용자 요청 |
| F-007-R7 | 시스템은 CREATE VIEW/MATERIALIZED VIEW에서 `DB_VIEW` 노드와 컬럼 노드를 선언하고, 정의 쿼리를 `SQL_STATEMENT` 노드로 만들어 VIEW→CONTAINS→구문→READS→테이블로 이어야 한다. | 사용자 요청 |
| F-007-R8 | 시스템은 CREATE SEQUENCE를 `DB_SEQUENCE` 노드로, CREATE SYNONYM과 DB2 CREATE ALIAS를 `DB_SYNONYM` 노드와 `ALIAS_OF` 엣지로 기록해야 한다. | 그래프 계약 3.3, 3.4 |
| F-007-R9 | 시스템은 모든 파일의 DDL을 먼저 읽어 카탈로그를 만든 뒤 DML과 내장 소스를 분석해야 한다. | 사용자 요청 |
| F-007-R10 | 만약 지원하지 않는 구문이면, 시스템은 그 구문만 건너뛰고 `UNSUPPORTED_SYNTAX`(INFO) 진단을 남겨야 하며, DROP/GRANT/REVOKE/SET/COMMIT/CONNECT 같은 관리 구문은 진단 없이 건너뛰어야 한다. | AGENTS.md, 사용자 요청 |
| F-007-R11 | 만약 같은 테이블 이름이 서로 다른 스키마로 두 번 정의되면, 시스템은 `DUPLICATE_OBJECT_NAME` 진단을 남겨야 한다. | F-001 design 7절 |
| F-007-R12 | 루틴 단위(CREATE PROCEDURE/FUNCTION/PACKAGE/TRIGGER)를 만나면, 시스템은 헤더에서 종류와 이름을 읽어 `DB_ROUTINE`/`DB_PACKAGE` 노드를 선언하고 트리거는 대상 테이블로 `TRIGGERS_ON` 엣지를 만들어야 한다. | 그래프 계약 3.3, 3.4 |
| F-007-R13 | 시스템은 인덱스 항목의 내림차순 표시(`DESC`)와 함수 기반 인덱스의 식을 테이블 `attrs.indexes`의 항목에 보존해야 한다. | 독립 감사 |
| F-007-R14 | 같은 테이블·뷰의 CREATE가 여러 파일에 있으면(방언별 DDL 폴더), 시스템은 한 파일의 정의만 써야 한다. 고르는 순서는 (1) `--dialect`와 맞는 경로의 파일, (2) 옵션이 auto면 Oracle·DB2 경로 힌트가 있는 파일, (3) 그 밖에는 경로순 첫 파일이다. 선택되지 않은 정의는 그래프에 섞지 않고, 테이블마다가 아니라 파일 쌍마다 요약한 `DUPLICATE_OBJECT_NAME`(INFO) 진단을 남겨야 한다. 같은 FK와 같은 인덱스는 한 번만 기록해야 한다. | 공개 프로젝트 검증(B8) |
| F-007-R15 | CREATE TABLE이 다른(upgrade가 아닌) 파일에 있는 테이블에 대한 upgrade·patch·migration 스크립트의 ALTER TABLE이면, 시스템은 그것을 적용하지 않고 파일마다 `DDL_NOT_APPLIED`(INFO) 진단 하나를 남겨야 한다. | 공개 프로젝트 검증(B8) |
| F-007-R16 | 시스템은 MySQL의 테이블 수준 인덱스 절(`KEY 이름 (cols)`, `INDEX 이름 (cols)`, `UNIQUE KEY`, `FULLTEXT KEY`, `CONSTRAINT .. FOREIGN KEY`)을 컬럼이 아니라 테이블 수준 절로 읽어야 하며, `DEFAULT NULL`을 기본값 없음으로 처리해야 한다. | 공개 프로젝트 검증(B8b) |
| F-007-R17 | 범위 밖 방언의 DDL 표기(백틱 이름, `AUTO_INCREMENT`, `UNSIGNED`, 컬럼·테이블 `COMMENT '..'`, `CHARACTER SET`, `ON UPDATE ..`, `ENGINE=..` 같은 테이블 옵션, HSQL `CREATE MEMORY|CACHED TABLE`)를 만나면, 시스템은 진단 없이 테이블과 컬럼을 읽어야 한다. | 공개 프로젝트 검증(방언 관용) |
| F-007-R18 | 같은 테이블·뷰의 CREATE가 방언이 다른 파일 여러 벌에 있으면, 시스템은 **프로젝트 전체로 한 방언을 일관되게** 골라야 한다(F-007-R14의 고르는 순서를 대체한다). 방언의 순위는 프로젝트에 하나이고 ① `--dialect` ② `mybatis.dialectVariant=<방언>`(`auto`·`all`·모르는 값은 건너뜀) ③ 프로젝트 설정에서 읽은 방언(F-007-R21) ④ Oracle ⑤ DB2 ⑥ Oracle 계열(Tibero, Altibase) ⑦ 그 밖의 방언과 방언을 읽지 못한 파일은 경로순 첫 정의 파일의 것이 먼저다. 테이블마다 그 테이블을 정의한 파일 가운데 순위가 가장 앞선 방언의 파일을 쓴다(가장 앞선 방언의 파일에 정의가 없는 테이블만 다음 순위에서 가져온다). 방언은 mybatis 모듈의 방언별 매퍼 선택(F-009-R15·R20)과 **같은 방언 토큰 표·같은 읽는 규칙**으로 파일 이름과 폴더 이름에서 읽는다(5절 "방언 토큰 표"). 매퍼 쪽은 ①과 ②의 순서만 다르다. | 공개 프로젝트 재검증(N4: jeesite의 Activiti 테이블은 DB2 파일, 업무 테이블은 Oracle 파일의 정의가 쓰임. `mybatis.dialectVariant=mysql`이면 매퍼만 MySQL) |
| F-007-R19 | 방언이 다른 파일에 중복 정의된 테이블·뷰(또는 F-007-R20의 적재 묶음)가 있으면, 시스템은 분석 한 번에 요약 진단 `DIALECT_VARIANT_DDL` 한 건(위치 없음. 감지한 방언, 쓴 방언과 테이블 수, 다른 방언에서 보충한 테이블 수, 제외한 파일 수, 바꾸는 옵션)을 남겨야 한다. 요청한 방언(`--dialect`, `mybatis.dialectVariant`)의 파일이 하나도 없으면 `WARN` 진단 `DIALECT_NOT_FOUND`(무엇으로 대체했는지 포함)를 남겨야 한다. 방언이 다른 중복이 없으면 둘 다 남기지 않는다. | 공개 프로젝트 재검증(N4: `--dialect db2`인데 DB2 파일이 없으면 안내 없이 경로순 첫 파일) |
| F-007-R20 | 같은 테이블의 데이터 적재 묶음(F-006-R15·R24)이 방언이 다른 파일 여러 벌에 있으면, 시스템은 DDL과 같은 방언 순위로 한 벌만 남겨야 한다. 선택되지 않은 방언 파일의 묶음으로는 구문 노드를 만들지 않고, 묶음을 모두 버린 파일은 초기화 구문(조건 없는 DELETE·TRUNCATE)의 노드도 만들지 않으며 요약의 "제외한 파일"에 넣는다. 방언을 읽지 못한 파일의 묶음은 건드리지 않는다. | 공개 프로젝트 재검증(N3 보강: backend의 CRUD 256행 중 160행이 5개 방언 데이터 파일의 것) |
| F-007-R21 | 방언을 명시한 옵션이 없으면, 시스템은 프로젝트 설정 파일에서 읽은 방언(5절 "프로젝트 설정의 방언")을 Oracle보다 먼저 골라야 한다. 그렇게 고른 방언이 대상 방언(Oracle, DB2, Tibero, Altibase)이 아니면 요약 진단 `DIALECT_VARIANT_DDL`을 `INFO`가 아니라 `WARN`으로 남기고, 메시지에 근거(`<파일>:<키>=<값>`)와 "대상 방언이 아니어서 SQL 해석이 부정확할 수 있음. `--dialect oracle`로 변경 가능"을 담아야 한다. 대상 방언이면 `INFO`에 근거만 담는다. mybatis 모듈(F-009-R22)과 같은 키 목록·같은 규칙이다. (2026-10-11 고쳐 씀: "Oracle보다 먼저"는 설정이 대상 방언(oracle, db2)을 가리킬 때만이다. 대상 외 방언을 가리키는 설정의 순위와 진단은 F-007-R23이 정한다.) | 공개 프로젝트 재검증(N30) |
| F-007-R22 | 현재 정의가 다른 파일에 있는 테이블에 대한 upgrade·patch·migration 스크립트의 ALTER TABLE(그 스크립트가 같은 테이블의 선택되지 않은 CREATE를 담고 있어도)에서, 시스템은 외래 키 추가를 자식 컬럼과 부모 테이블(적어 준 참조 컬럼)이 현재 정의에 있으면 적용하고 `REFERENCES` 엣지에 `fromUpgrade=true`를 붙여야 한다. 적용하지 않은 ALTER는 종류별 건수(컬럼 추가, 컬럼 변경·삭제, 제약 추가)를, 적용한 외래 키는 그 건수를 `DDL_NOT_APPLIED` 메시지에 담아야 한다(F-007-R15를 보강한다). | 공개 프로젝트 재검증(N24: zheng `update_20170328_1_to_20170405_1.sql`의 FK 4개가 진단 없이 사라짐) |
| F-007-R23 | 시스템은 방언별 스크립트 여러 벌 가운데 쓸 방언을 5절 "방언 순위(F-007-R23)"로 골라야 한다(F-007-R18의 순서와 F-007-R21의 "프로젝트 설정이 Oracle보다 먼저"를 대체한다). 프로젝트 설정이 대상 외 방언을 가리켜도 대상 방언(Oracle, DB2)과 그 계열의 파일이 있으면 그쪽을 골라야 하고, 요약 진단 `DIALECT_VARIANT_DDL`은 그때 INFO로 설정과 다르게 골랐다는 것과 설정대로 분석하는 방법을 알려야 하며, 대상 방언 파일이 하나도 없어 대상 외 방언을 쓰게 될 때만 WARN이어야 한다. 프로젝트 설정이 파일마다 엇갈리면 쓰지 않고 그 사실을 요약에 적어야 한다. | 공개 프로젝트 재검증 3차(M4: 전자정부 계열을 옵션 없이 돌리면 MySQL 결과가 나왔다) |
| F-007-R24 | 경로에 방언 이름이 없는 SQL 파일이면, 시스템은 내용 특징으로 그 파일의 방언을 읽어야 한다: `CREATE MEMORY\|CACHED TABLE`·줄 처음의 `SET DATABASE`(hsql), 백틱 이름·`ENGINE=`·`AUTO_INCREMENT`(mysql), `VARCHAR2`·`NUMBER(`(oracle), `GENERATED ALWAYS AS IDENTITY`와 `) IN <테이블스페이스>`가 함께 있음(db2). | 공개 프로젝트 재검증 3차(M4-b: 이름에 방언이 없는 HSQL 스크립트를 "hsql 파일은 없음"이라 했다) |
| F-007-R25 | 방언별 여러 벌이 아닌 프로젝트라도 SQL 스크립트가 대상 외 방언뿐이면(MySQL 덤프뿐인 프로젝트), 시스템은 분석 한 번에 INFO 진단 `DIALECT_NOT_TARGET` 한 건("이 프로젝트의 SQL은 <방언>로 보임. 대상 방언이 아니어서 일부 구문을 해석하지 못할 수 있음")을 남겨야 한다. 대상 방언(과 그 계열)의 스크립트가 하나라도 있거나 그 방언을 옵션으로 요청했으면 남기지 않는다. | 공개 프로젝트 재검증 3차(M4-c) |
| F-007-R26 | upgrade 스크립트에서 가져온 외래 키(F-007-R22)는, 그 테이블의 현재 정의 파일이 다른 외래 키를 하나라도 담고 있거나 그 테이블에 upgrade가 아닌 외래 키가 이미 있으면, `REFERENCES`를 `INFERRED`(`source=DDL`, `fromUpgrade=true`, `note="현재 정의에 없음"`)로 만들어야 한다. 현재 정의가 외래 키를 전혀 담지 않는 스키마면 전과 같이 `RESOLVED`다. | 공개 프로젝트 재검증 3차(M8) |
| F-007-R27 | 방언별 스크립트가 여러 벌인 프로젝트에서, 고른 방언이 아닌 방언의 파일 가운데 정의를 하나도 쓰지 않은 파일은 통째로 고르지 않은 파일이다: 시스템은 그 파일의 데이터 적재 묶음(그 방언 파일에만 있는 테이블의 것 포함)과 DML 구문 노드를 만들지 않아야 한다(F-007-R20을 넓힌다). 그리고 방언별로 한 벌을 고른 중복 정의에는 `DUPLICATE_OBJECT_NAME`(INFO)의 "한 벌만 지정 권장"을 남기지 않아야 한다(요약 `DIALECT_VARIANT_DDL`이 알린다. 방언 이름이 없는 파일끼리의 중복에만 남긴다). (2026-10-11 고쳐 씀: 적재 묶음을 파일 단위로 모두 버리는 부분은 F-007-R29가 대체했다. 묶음은 테이블마다 고르고, 이 파일의 묶음은 고른 방언의 파일에 그 테이블의 적재가 없고 테이블이 DDL에 정의돼 있으면 `borrowedFrom`과 함께 남는다. DML 구문을 만들지 않는 것은 그대로다.) | 공개 프로젝트 재검증 3차(M12-a·b: cubrid comment 스크립트의 유령 테이블, 방언 8벌의 `ACT_GE_PROPERTY` INSERT) |
| F-007-R28 | 방언 순위(F-007-R23)의 맨 끝, 곧 명시값·프로젝트 설정·대상 방언(Oracle, DB2)·그 계열(Tibero, Altibase) 어디에도 들지 않는 "그 밖의 방언"끼리는, 시스템은 mybatis 모듈(F-009-R26)과 **같은 규칙**으로 순서를 정해야 한다: 프로젝트의 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 표준 이름의 사전순으로 먼저고, 나머지가 사전순으로 그 뒤다(5절 "그 밖의 방언의 순서"). F-007-R23의 ⑦ "경로순 첫 파일의 방언"을 대체하고 ①~⑥은 그대로다. 방언을 읽지 못한 파일은 전처럼 처음 물은 순서(경로순)로 서야 한다: 그 밖의 방언 파일 어느 것보다 경로상 먼저면 그 밖의 방언들 앞, 아니면 뒤다. DDL과 데이터 적재 스크립트에 같이 적용한다. | F-001 spec 5절 6차 수정의 "확인한 사실"(대상 방언 파일이 없고 매퍼·DDL이 mysql·postgres 두 벌인 프로젝트에서 매퍼는 postgres, DDL은 mysql을 골라 불일치 경고가 나왔다) |
| F-007-R29 | 방언별 스크립트가 여러 벌인 프로젝트에서, 시스템은 데이터 적재 묶음을 파일이 아니라 **테이블마다** 골라야 한다(F-007-R27의 "고르지 않은 방언의 파일의 적재 묶음을 모두 버린다"를 대체한다. 그 파일의 DML 구문을 만들지 않는 것과 `DUPLICATE_OBJECT_NAME` 규칙은 그대로다): 테이블의 적재 묶음을 가진 방언 파일들 가운데 방언 순위(F-007-R23·R28)가 가장 앞선 방언의 묶음만 노드로 만들고(그 방언의 파일이 여럿이면 모두), 남은 묶음이 고른 방언(`mainDialect`)의 파일 것이 아니면 구문 노드에 `borrowedFrom=<방언>`을 붙여야 한다. 고른 방언이 아니고 테이블·뷰 정의도 쓰이지 않은 파일의 묶음은 그 테이블이 DDL에 정의돼 있을 때만 남겨야 한다(정의 없는 테이블의 `declared=false` 노드를 늘리지 않는다). 초기화 구문(조건 없는 DELETE·TRUNCATE)은 버린 묶음의 테이블 것이면 만들지 않고, 고르지 않은 방언의 파일에서는 남은 묶음의 테이블 것만 만들며, 다른 방언 파일의 것이면 같은 `borrowedFrom`을 붙여야 한다. 방언을 읽지 못한 파일의 묶음은 고르는 대상이 아니다. 요약 진단은 만들지 않은 묶음 수에 더해 다른 방언 파일에서 가져온 묶음 수를 방언별로 적고, `DATA_LOAD_GROUPED`는 노드가 된 묶음·초기화 구문만 세야 한다. | 공개 프로젝트 재검증 4차(회귀: 공통컴포넌트의 HSQL 테스트 픽스처 `session/testdb.sql`의 묶음 6개, jeesite의 `db/jeesite_mysql.sql` 묶음 19개가 "고르지 않은 방언의 파일"로 통째로 빠져 sys·cms 테이블의 초기 데이터 정보가 없어졌다) |

## 4. 수용 기준

### F-007-AC1 (대상: F-007-R1)
- Given `CREATE TABLE SHOP.T (ID NUMBER(12) NOT NULL, NM VARCHAR2(100 CHAR) DEFAULT 'x', DT DATE DEFAULT SYSDATE) TABLESPACE TS;`와 DB2의 `GENERATED ALWAYS AS IDENTITY`, `WITH DEFAULT`, `FOR BIT DATA`, `IN ts` 절이 있는 테이블
- When 분석하면
- Then `db:table:T`(schema=SHOP)와 컬럼 노드가 선언되고 dataType(`NUMBER(12)`, `VARCHAR2(100 CHAR)`), nullable, ordinal, defaultValue, identity가 기대와 같으며 CONTAINS 엣지가 있다.

### F-007-AC2 (대상: F-007-R2)
- Given 인라인 `PRIMARY KEY`, `CONSTRAINT PK PRIMARY KEY (A, B)`, `ALTER TABLE T ADD PRIMARY KEY (ID)`, `UNIQUE (X)`
- When 분석하면
- Then PK 컬럼의 attrs.primaryKey=true, nullable=false이고 테이블 attrs.primaryKey가 컬럼 순서대로이며 UNIQUE 컬럼은 attrs.unique=true다.

### F-007-AC3 (대상: F-007-R3)
- Given 인라인 `REFERENCES P`, 테이블 수준 `FOREIGN KEY (A) REFERENCES P (ID) ON DELETE CASCADE`, `ALTER TABLE C ADD CONSTRAINT FK FOREIGN KEY (P_ID) REFERENCES P`이고 P의 정의가 파일 뒤쪽(또는 다른 파일)에 있음
- When 분석하면
- Then 자식→부모 REFERENCES 엣지에 columns, refColumns(생략 시 부모 PK), constraint, source=`DDL`, onDelete가 담긴다.

### F-007-AC4 (대상: F-007-R4)
- Given `ALTER TABLE T ADD (C1 NUMBER, C2 DATE)`, `ALTER TABLE T MODIFY C1 VARCHAR2(10) NOT NULL`, DB2 `ALTER TABLE T ALTER COLUMN C2 SET DATA TYPE TIMESTAMP`
- When 분석하면
- Then 컬럼 노드가 추가되고 dataType과 nullable이 바뀐 값이다.

### F-007-AC5 (대상: F-007-R5)
- Given `COMMENT ON TABLE T IS '주문'`, `COMMENT ON COLUMN T.ID IS '주문 ID'` (MS949 파일 포함)
- When 분석하면
- Then 테이블과 컬럼 노드의 attrs.comment가 그 한글 문자열이다.

### F-007-AC6 (대상: F-007-R6)
- Given `CREATE INDEX IX1 ON T (A, B DESC)`, `CREATE UNIQUE INDEX UX1 ON T (C)`
- When 분석하면
- Then 테이블 attrs.indexes에 두 인덱스가 있고 A, B, C 컬럼의 attrs.indexed=true다.

### F-007-AC7 (대상: F-007-R7)
- Given `CREATE OR REPLACE FORCE VIEW V (A, B) AS SELECT ...`와 컬럼 목록이 없는 `CREATE VIEW V2 AS SELECT o.id, m.nm AS member_nm FROM o, m WHERE o.mid = m.id`
- When 분석하면
- Then `db:view:V`, `db:view:V2`와 컬럼 노드(V2는 `ID`, `MEMBER_NM`)가 선언되고, 뷰→CONTAINS→구문→READS→`db:table:O`, `db:table:M`과 JOINS가 생긴다.

### F-007-AC8 (대상: F-007-R8)
- Given `CREATE SEQUENCE SEQ_A START WITH 1`, `CREATE PUBLIC SYNONYM S FOR SHOP.T`, DB2 `CREATE ALIAS A FOR SCH.T`
- When 분석하면
- Then `db:sequence:SEQ_A`, `db:synonym:S`, `db:synonym:A`가 선언되고 시노님에서 `db:table:T`로 ALIAS_OF 엣지가 있다.

### F-007-AC9 (대상: F-007-R9)
- Given 파일 이름 순서상 DML 파일이 DDL 파일보다 앞에 있고, DML이 비한정 컬럼으로 두 테이블을 조인함
- When 분석하면
- Then 카탈로그로 컬럼 소속이 정해져 컬럼 READS와 JOINS 엣지가 생긴다.

### F-007-AC10 (대상: F-007-R10)
- Given `DROP TABLE T; GRANT SELECT ON T TO U; SET CURRENT SCHEMA = X; COMMIT; CONNECT TO DB; ALTER SESSION SET ...; CREATE FOO BAR BAZ;`
- When 분석하면
- Then 앞의 관리 구문에는 진단이 없고 마지막 구문에만 `UNSUPPORTED_SYNTAX`(INFO)가 남으며 나머지 구문의 분석은 계속된다.

### F-007-AC11 (대상: F-007-R11)
- Given `CREATE TABLE A.T (...)`와 `CREATE TABLE B.T (...)`
- When 분석하면
- Then `DUPLICATE_OBJECT_NAME` 진단이 하나 남는다.

### F-007-AC12 (대상: F-007-R12)
- Given `CREATE OR REPLACE PROCEDURE P ...`, `CREATE FUNCTION F ...`, `CREATE PACKAGE BODY PKG AS PROCEDURE A ...; FUNCTION B ...; END;`, `CREATE TRIGGER TRG AFTER UPDATE ON SHOP.T ...`
- When 분석하면
- Then `db:routine:P`, `db:routine:F`, `db:package:PKG`, `db:routine:PKG.A`, `db:routine:PKG.B`, `db:routine:TRG`가 routineType과 함께 선언되고 PACKAGE→CONTAINS→ROUTINE, `db:routine:TRG`→TRIGGERS_ON→`db:table:T`가 있다.

### F-007-AC13 (대상: F-007-R13)
- Given `CREATE INDEX IX_ORDER_HIST_01 ON TB_ORDER_HIST(ORDER_ID, CHG_DT DESC)`, `CREATE INDEX IX2 ON T (A ASC, UPPER( NM )   DESC)`, `CREATE UNIQUE INDEX UX1 ON T (E, F DESC)`
- When 분석하면
- Then `attrs.indexes`의 항목이 `IX_ORDER_HIST_01(ORDER_ID,CHG_DT DESC)`, `IX2(A,UPPER(NM) DESC)`, `UX1 UNIQUE(E,F DESC)`이고, 컬럼의 `indexed`·`unique`와 테이블의 `uniqueKeys`(`E,F`)는 정렬 방향과 무관하게 전과 같다.

### F-007-AC14 (대상: F-007-R14)
- Given `DATABASE/altibase/`, `DATABASE/mysql/`, `DATABASE/oracle/`에 같은 테이블 둘·뷰 하나·시퀀스 하나의 DDL이 방언마다 있고(MySQL 쪽에만 있는 컬럼, `KEY` 절, 같은 이름의 FK와 인덱스 포함), `DATABASE/mysql/`의 ALTER 전용 파일과 방언 힌트가 없는 `DATABASE/common/`의 ALTER 파일이 있음. 그리고 `--dialect db2`/`oracle`을 준 경우, 방언 힌트가 없는 폴더들과 upgrade 스크립트에 같은 테이블이 있는 경우, 테이블마다 파일 하나씩인 두 폴더, 같은 FK·인덱스를 두 번 선언한 파일
- When 분석하면
- Then 테이블·컬럼·뷰·시퀀스의 위치와 타입이 모두 Oracle 파일의 것이고, MySQL·Altibase 파일과 `DATABASE/mysql/`의 ALTER는 반영되지 않으며 방언 힌트가 없는 폴더의 ALTER는 반영된다. `REFERENCES`와 `indexes` 항목, 뷰 정의 쿼리 구문 노드, FILE→CONTAINS는 하나씩이다. 진단은 `DUPLICATE_OBJECT_NAME`(INFO)이 버린 파일마다 하나("같은 테이블 3개의 정의가 A와(과) B에 중복됨; A의 정의를 사용. --exclude나 --ddl로 한 벌만 지정 권장")이고, 서로 다른 두 폴더 사이에 파일 쌍이 여럿이면 폴더 쌍 하나로 줄인다. `--dialect`를 주면 그 방언 경로의 정의를, 힌트가 없으면 경로순 첫 파일(upgrade 스크립트는 뒤로)의 정의를 쓴다. 같은 FK(인라인 + ALTER)와 같은 CREATE INDEX는 한 번만 기록되고 제약 이름은 채워진다.

### F-007-AC15 (대상: F-007-R15)
- Given `db/sys/schema.sql`의 `CREATE TABLE SYS_USR`와 같은 파일의 ALTER, `db/upgrade/upgrade20131129.sql`·`db/patch/p1.sql`·`db/migration/V2__x.sql`의 `ALTER TABLE SYS_USR ..`(컬럼 추가, 타입 변경, 컬럼 삭제, FK 추가), upgrade 파일의 `ALTER TABLE NOT_DEFINED ADD ..`
- When 분석하면
- Then SYS_USR의 컬럼과 타입은 `schema.sql`의 것 그대로이고 upgrade 쪽 FK는 생기지 않는다. CREATE TABLE이 없는 NOT_DEFINED의 ALTER는 전처럼 참조 노드에 반영된다. `DDL_NOT_APPLIED`(INFO)가 파일마다 하나(적용하지 않은 건수 포함) 남는다.

### F-007-AC16 (대상: F-007-R16)
- Given `CREATE TABLE T1 (ID VARCHAR(20) NOT NULL, BBS_ID CHAR(20) DEFAULT NULL, .., PRIMARY KEY (ID), KEY T1_ibfk_1 (BBS_ID), INDEX IX_T1_NM (NM, CD), UNIQUE KEY UK_T1_CD (CD), FULLTEXT KEY FT_T1_MEMO (MEMO), KEY IX_T1_USING USING BTREE (NM), CONSTRAINT T1_ibfk_1 FOREIGN KEY (BBS_ID) REFERENCES T_BBS (BBS_ID) ON DELETE CASCADE ON UPDATE CASCADE)`와 `KEY VARCHAR(10)`, `INDEX NUMBER(5)`처럼 KEY·INDEX라는 이름의 컬럼이 있는 테이블
- When 분석하면
- Then T1의 컬럼은 실제 컬럼 5개뿐이고(가짜 컬럼 `KEY`·`INDEX`·`FULLTEXT` 없음), `indexes`에 `T1_IBFK_1(BBS_ID)`, `IX_T1_NM(NM,CD)`, `IX_T1_USING(NM)`이 있고 컬럼 `indexed`·`unique`와 FK가 기록된다. `DEFAULT NULL` 컬럼에는 `defaultValue`가 없다. KEY·INDEX라는 이름의 컬럼(뒤가 타입)은 컬럼으로 남는다.

### F-007-AC17 (대상: F-007-R17)
- Given MySQL 덤프(백틱 이름, `int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT '..'`, `CHARACTER SET utf8 COLLATE utf8_bin`, `DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)`, `) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COMMENT='..'`, `# 주석`, `DROP TABLE IF EXISTS`, `SET FOREIGN_KEY_CHECKS=0`), HSQL `CREATE MEMORY TABLE SAMPLE(..)`·`CREATE CACHED TABLE PUBLIC.LOG(..)`, MSSQL `IF EXISTS (SELECT name FROM sysindexes ..) drop index ..`
- When 분석하면
- Then 진단 없이 테이블과 컬럼이 나온다. 타입은 `INT(10) UNSIGNED`, `AUTO_INCREMENT` 컬럼은 `identity=true`, 기본값은 `'1'`·`CURRENT_TIMESTAMP(3)`이고 이름은 대문자로 정규화된다. MSSQL의 `IF EXISTS .. DROP`은 구문 노드도 테이블도 만들지 않는다.

### F-007-AC18 (대상: F-007-R18)
- Given jeesite의 배치(`db/act/activiti.db2.create.engine.sql`·`activiti.mysql55.create.engine.sql`·`activiti.oracle.create.engine.sql`의 `ACT_X`, `db/sys/jeesite_mysql.sql`·`jeesite_oracle.sql`의 `SYS_USER`), `ddl/mysql/`·`ddl/postgres/`·`ddl/tibero/`에 같은 테이블, 선호 방언이 없는 배치(`ddl/a_mysql.sql`·`ddl/a_postgres.sql`의 `T_A`, `ddl/b/aa_postgres.sql`의 `T_B`·`T_C`, `ddl/b/zz_mysql.sql`의 `T_B`, `ddl/b/zz_sqlite.sql`의 `T_C`)
- When 옵션 없이, `--dialect db2`로, `mybatis.dialectVariant=MySQL`로, `--dialect oracle`과 `mybatis.dialectVariant=mysql`로, `mybatis.dialectVariant=auto`·`all`·`nosuchdb`로 분석하면
- Then 옵션이 없으면 `ACT_X`와 `SYS_USER`가 모두 oracle 파일의 정의다(전에는 `ACT_X`가 경로순으로 DB2였다). `--dialect db2`면 `ACT_X`는 db2 파일, db2 파일에 없는 `SYS_USER`는 oracle 파일의 정의다. `mybatis.dialectVariant=MySQL`이면 둘 다 mysql 파일, `--dialect oracle`을 함께 주면 oracle 파일이다. `auto`·`all`·모르는 값은 옵션이 없는 것과 같다. Tibero는 MySQL·PostgreSQL보다 먼저다. 선호 방언이 없으면 경로순 첫 정의 파일의 방언(mysql)이 프로젝트의 방언이라 `T_B`는 경로상 뒤에 있어도 mysql 파일의 정의를 쓰고, mysql에 없는 `T_C`만 다음 방언(postgres)에서 가져온다. (2026-10-11: 그 밖의 방언끼리는 경로순이 아니라 이름의 사전순이다, F-007-R28. 이 배치에서는 결과가 같다.)

### F-007-AC19 (대상: F-007-R19)
- Given 위 jeesite의 배치, `DATABASE/altibase/`·`mysql/`·`oracle/`에 같은 테이블이 있는 프로젝트를 `--dialect db2`로(DB2 파일 없음), altibase·mysql 파일만 있는 프로젝트를 `--dialect db2`와 `mybatis.dialectVariant=cubrid`로, 방언이 다른 중복이 없는 프로젝트(방언 없는 두 파일의 중복, 방언 파일 하나)
- When 분석하면
- Then `INFO` 진단 `DIALECT_VARIANT_DDL`이 정확히 한 건 있고(위치 없음) 메시지가 "방언별 DDL 3벌 감지(oracle, db2, mysql): oracle 파일을 사용(테이블 2개), 다른 방언에서 보충 0개, 제외한 파일 3개. … `--dialect …`로 변경" 꼴이다(`--dialect db2`면 "db2 파일을 사용(테이블 1개), 다른 방언에서 보충 1개(oracle 1개)"). DB2 파일이 없는데 `--dialect db2`면 oracle 파일의 정의를 쓰고 `WARN` 진단 `DIALECT_NOT_FOUND`(``db2(`--dialect`)``, "DDL·데이터 스크립트 파일이 없음", 감지한 방언, "oracle 파일을 사용")가 한 건 있다. 요청한 두 방언이 다 없으면 둘 다 적고 Oracle 계열(altibase)을 쓴다. 방언이 다른 중복이 없으면 두 진단 모두 없고 `DUPLICATE_OBJECT_NAME`만 전처럼 남는다.

### F-007-AC20 (대상: F-007-R18)
- Given 방언 토큰 표와 경로 표(mybatis 모듈의 F-009-AC38 테스트 `DialectTokenTableTest`와 글자 그대로 같은 표)
- When `DialectTokens.canonical`과 `DialectTokens.ofPath`로 읽으면
- Then 표준 이름과 기본 선호 순서, 별칭, 끝의 버전 숫자, 파일 이름 토큰(뒤에서부터) → 폴더 이름(이름 전체) 순서가 F-009-AC38과 같다.

### F-007-AC21 (대상: F-007-R21)
- Given 설정 키·값 표(mybatis 모듈의 F-009-AC41 테스트 `ProjectDialectTableTest`와 글자 그대로 같은 표), 주석 줄이 섞인 `.properties`와 문서가 둘인 YAML, 그리고 jeesite의 배치에 (가) `src/main/resources/jeesite.properties`의 `#jdbc.type=oracle` / `jdbc.type=mysql`과 `src/test/resources/test.properties`의 `jdbc.type=db2`, (나) 같은 설정에 `--dialect oracle`, (다) `application.yml`의 `spring.datasource.driver-class-name: com.ibm.db2.jcc.DB2Driver`와 `url: ${DB_URL}`, (라) 두 설정 파일에 `Globals.DbType = mysql`과 `spring.datasource.url=jdbc:log4jdbc:db2://…`
- When 분석하면
- Then (가) 테이블이 모두 mysql 파일의 정의이고 요약 `DIALECT_VARIANT_DDL`이 `WARN`이며 메시지에 "프로젝트 설정(src/main/resources/jeesite.properties:jdbc.type=mysql)에 따라 mysql 선택", "이 도구의 대상 방언이 아니어서 SQL 해석이 부정확할 수 있음", "`--dialect oracle`로 변경 가능"이 있다. (나) oracle이고 `INFO`이며 "프로젝트 설정"이 없다. (다) `ACT_X`는 db2 파일, `SYS_USER`는 oracle 파일이고 요약은 `INFO`에 근거만 있다. (라) 설정을 쓰지 않는다(oracle).

### F-007-AC22 (대상: F-007-R22)
- Given `model/schema.sql`의 `CMS_ARTICLE`·`CMS_TOPIC`·`CMS_TAG`·`CMS_ARTICLE_TAG`(`TAG_ID` FK 포함)와, `CMS_ARTICLE`을 다시 만들고 FK 추가 다섯(현재 정의에 컬럼이 있는 것 둘, 이미 있는 것 하나, 자식 컬럼이 없는 것 하나, 부모 테이블이 정의되지 않은 것 하나)과 컬럼 추가·변경을 담은 `model/update_20170328_1_to_20170405_1.sql`
- When 분석하면
- Then `CMS_ARTICLE→CMS_TOPIC`, `CMS_ARTICLE_TAG→CMS_ARTICLE` `REFERENCES`가 `fromUpgrade=true`(위치는 upgrade 스크립트, 제약 이름 포함)로 생기고, 이미 있던 `CMS_ARTICLE_TAG→CMS_TAG`는 하나뿐이며 `fromUpgrade`가 없다. 컬럼이 없거나 부모가 없는 FK와 컬럼 추가·변경은 적용되지 않는다. `DDL_NOT_APPLIED`가 그 파일에 한 건이고 메시지에 "ALTER TABLE 4건을 적용하지 않음", "컬럼 추가 1", "컬럼 변경·삭제 1", "제약 추가 2", "외래 키 추가 2건", "fromUpgrade"가 있다.

### F-007-AC23 (대상: F-007-R20)
- Given `script/dml/altibase/`·`mysql/`·`oracle/`의 데이터 파일에 같은 테이블 `T_CODE`(12건)·`T_MENU`(3건)의 리터럴 INSERT와 `DELETE FROM T_CODE;`가 있고, mysql 파일에만 `T_MYSQL_ONLY`(2건)가 더 있으며, 방언 이름이 없는 `script/dml/common_data.sql`에 `T_CODE` 10건이 있는 프로젝트
- When 분석하면
- Then `T_CODE`·`T_MENU`의 묶음과 초기화 구문은 oracle 파일의 것만 노드가 되고, mysql 파일은 `T_MYSQL_ONLY` 묶음과 초기화 구문만, altibase 파일은 아무 노드도 만들지 않는다. 방언 이름이 없는 파일의 묶음은 그대로다. `DIALECT_VARIANT_DDL`이 한 건 있고 메시지가 "방언별 데이터 적재 스크립트 3벌 감지(oracle, altibase, mysql): oracle 파일을 사용, 제외한 파일 1개. … 묶음 4개를 만들지 않음" 꼴이다. `DATA_LOAD_GROUPED`는 묶음이 남은 파일에만 있다. (2026-10-11: F-007-R27·R29 뒤로는 어느 DDL에도 정의가 없는 `T_MYSQL_ONLY`의 묶음과 mysql 파일의 초기화 구문도 만들지 않는다. 요약은 "제외한 파일 2개", "묶음 5개를 만들지 않음"이다.)

### F-007-AC24 (대상: F-007-R23)
- Given 같은 테이블을 oracle·altibase·mysql 파일이 정의하는 프로젝트에 `Globals.DbType = mysql`, `jdbc.type=db2`(db2 파일 추가), `Globals.DbType=altibase`(tibero 파일 추가), `mybatis.dialectVariant=mysql`을 준 경우와, cubrid·mysql 파일뿐인 프로젝트에 `Globals.DbType=mysql`을 준 경우·설정이 없는 경우, 설정 파일 둘이 `mysql`과 `altibase`로 엇갈린 경우
- When 분석하면
- Then 설정이 mysql이어도 oracle 파일의 정의를 쓰고 요약이 INFO이며 "프로젝트 설정(…:Globals.DbType=mysql)과 다르게 대상 방언 oracle 파일을 사용", "설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`"이 있다. 설정이 db2면 db2 파일("…에 따라 db2 선택"), altibase면 oracle 파일, 옵션으로 mysql을 요청하면 mysql 파일(INFO)이다. 대상 방언 파일이 없으면 설정의 mysql(설정이 없으면 경로순 첫 방언 cubrid. 2026-10-11: F-007-R28 뒤로는 이름의 사전순 첫 방언이고 이 배치에서는 같은 cubrid다)을 쓰고 요약이 WARN이며 "이 도구의 대상 방언(Oracle, DB2)의 파일이 없어"가 있다. 엇갈린 설정은 쓰지 않고(oracle) 요약에 "프로젝트 설정이 엇갈려 쓰지 않음(…a.properties=mysql, …b.properties=altibase)"가 있다.

### F-007-AC25 (대상: F-007-R24)
- Given `src/main/resources/db/shtdb.sql`(`SET DATABASE …`, `CREATE MEMORY TABLE TB_A(…)`, 이 파일에만 있는 테이블 하나)과 `script/ddl/all_ddl.sql`(`VARCHAR2`·`NUMBER(3)`로 TB_A 정의), `Globals.DbType=hsql`인 프로젝트와, 백틱·`ENGINE=`의 `db/a_dump.sql`과 `GENERATED ALWAYS AS IDENTITY … IN TS_DATA`의 `db/b_schema.sql`이 같은 테이블을 정의하는 프로젝트
- When 분석하면
- Then 앞의 프로젝트는 "방언별 DDL 2벌 감지(oracle, hsql)"이고 TB_A는 oracle 파일의 정의, hsql 파일에만 있는 테이블은 보충이며("다른 방언에서 보충 1개(hsql 1개)") "hsql 파일은 없음"이라고 하지 않는다. 뒤의 프로젝트는 "방언별 DDL 2벌 감지(db2, mysql)"이고 db2 파일의 정의를 쓴다.

### F-007-AC26 (대상: F-007-R25)
- Given MySQL 덤프(`db/schema.sql`, `db/data.sql`)뿐인 프로젝트와 Oracle DDL 하나뿐인 프로젝트
- When 분석하면
- Then 앞의 프로젝트에 `DIALECT_NOT_TARGET`(INFO) 1건("이 프로젝트의 SQL은 mysql로 보임", "대상 방언이 아니어서 일부 구문을 해석하지 못할 수 있음")이 남고 `DIALECT_VARIANT_DDL`은 없다. 뒤의 프로젝트에는 진단이 없다.

### F-007-AC27 (대상: F-007-R26)
- Given 현재 정의 파일에 다른 외래 키(`FK_4`)가 있는 스키마의 upgrade 스크립트가 더한 외래 키 둘과, 현재 정의에 외래 키가 하나도 없는 스키마의 upgrade 스크립트가 더한 외래 키
- When 분석하면
- Then 앞의 둘은 `REFERENCES`가 `INFERRED`이고 `fromUpgrade=true`, `source=DDL`, `note="현재 정의에 없음"`이며 현재 정의의 `FK_4`는 `RESOLVED`다. 뒤의 것은 `RESOLVED`이고 `fromUpgrade=true`이며 `note`가 없다.

### F-007-AC28 (대상: F-007-R27)
- Given oracle·altibase·mysql로 같은 테이블을 정의하는 프로젝트에 cubrid comment 스크립트(`_CUB_SCHEMA_COMMENTS`에 INSERT 12건), 방언별 생성 스크립트 셋(`act.<방언>.create.sql`: CREATE TABLE과 INSERT 한 건씩), oracle 적재 파일, 방언 이름이 없는 적재 파일이 있는 경우와, 방언 이름이 없는 두 파일이 같은 테이블을 정의하는 경우, mysql 적재 파일에만 있는 테이블의 묶음이 있는 경우
- When 분석하면
- Then `_CUB_SCHEMA_COMMENTS` 테이블 노드가 없고 구문 노드는 oracle 생성 스크립트의 INSERT, oracle 적재 묶음, 방언 이름이 없는 파일의 묶음뿐이다. 방언별로 고른 중복에는 `DUPLICATE_OBJECT_NAME`이 없고 방언 이름이 없는 두 파일의 중복에만 1건("한 벌만 지정 권장")이 남는다. mysql 파일에만 있는 테이블의 묶음과 그 파일의 초기화 구문도 만들지 않는다("제외한 파일 2개", "묶음 5개를 만들지 않음").

### F-007-AC29 (대상: F-007-R28)
- Given 대상 방언 파일도 프로젝트 설정도 없이 같은 테이블을 `db/a_postgres.sql`과 `db/b_mysql.sql`이 정의하는 프로젝트(적재 스크립트만 두 벌인 경우 포함), cubrid·mysql·postgres 폴더의 DDL이 있는 프로젝트(매퍼가 없는 경우, mysql·postgres 매퍼 파일이 있는 경우, 거기에 `Globals.DbType=postgres`를 더한 경우), 방언 이름이 없는 파일이 postgres·mysql 파일보다 경로상 앞(`a_common.sql`)이거나 뒤(`z_common.sql`)인 프로젝트, 그리고 매퍼 경로 목록·스크립트 경로 목록·기대 순서의 표(mybatis 모듈 `DialectTokenTableTest`와 글자 그대로 같은 표)
- When 분석하면(표는 `DialectTokens.otherOrder`에 주면)
- Then postgres 파일이 경로상 먼저여도 mysql 파일의 정의를 쓰고 요약(WARN)이 "방언별 DDL 2벌 감지(mysql, postgres): mysql 파일을 사용(테이블 1개)"이다(적재 스크립트도 mysql 파일의 묶음만 남는다). cubrid·mysql·postgres는 매퍼가 없으면 cubrid, 매퍼 경로에 mysql·postgres가 있으면 mysql("3벌 감지(mysql, postgres, cubrid)"), 설정이 postgres면 postgres다. 방언 이름이 없는 파일이 경로상 앞이면 그 파일의 정의를, 뒤면 mysql 파일의 정의를 쓴다. 표의 순서는 양쪽에 다 있는 방언(사전순), 나머지(사전순)이고 대상 방언과 그 계열은 들어 있지 않다. cli에서 두 분석기를 함께 돌리면(`MainTest.mapperAndDdlAgreeOnOtherDialect`) 매퍼와 DDL이 같은 방언(mysql)을 고르고 불일치 경고가 없다.

### F-007-AC30 (대상: F-007-R29)
- Given (1) oracle·mysql DDL이 같은 테이블을 정의하고 HSQL 테스트 픽스처 둘(`sec/testdb.sql`, `sess/testdb.sql`: 같은 `CREATE MEMORY TABLE`과 INSERT)이 있는 프로젝트, (2) Oracle DDL과 이름에 방언이 없는 HSQL 초기화 스크립트(`db/shtdb.sql`: 같은 테이블의 정의와 INSERT 12건)만 있는 프로젝트, (3) oracle·mysql·postgres DDL이 `SYS_A`·`CMS_A`·`OA_A`를 정의하고 oracle 적재 파일에는 `CMS_A`만, mysql 적재 파일에는 셋 다(와 `DELETE FROM CMS_A`·`DELETE FROM SYS_A`), postgres 적재 파일에는 `SYS_A`·`OA_A`, 방언 이름이 없는 파일에 `CMS_A`가 있는 프로젝트, (4) mysql 적재 파일이 정의 없는 `T_GHOST`와 mysql DDL에만 있는 `T_MY_ONLY`에 넣는 프로젝트
- When 분석하면
- Then (1) 두 픽스처의 묶음 4개가 모두 노드가 되고 `borrowedFrom=hsql`이며 `DATA_LOAD_GROUPED`가 두 파일에 있다. 요약에 "만들지 않음"이 없고 "oracle 파일에 적재가 없는 테이블의 묶음 4개는 다른 방언 파일의 것을 씀(hsql 4개)"가 있다. (2) `shtdb.sql`의 묶음이 `borrowedFrom=hsql`, `rowCount=12`로 남는다(테이블 정의는 Oracle 파일의 것). (3) `CMS_A`는 oracle 파일의 묶음과 초기화 구문만(`borrowedFrom` 없음), `SYS_A`·`OA_A`는 mysql 파일의 묶음과 `SYS_A`의 초기화 구문만(`borrowedFrom=mysql`. 초기화 구문은 `rowCount=0`) 노드가 되고, mysql의 `CMS_A` 묶음·초기화 구문과 postgres 파일의 것은 노드가 되지 않는다. 방언 이름이 없는 파일의 묶음은 그대로다. 요약에 "다른 방언 파일의 묶음 3개를 만들지 않음", "묶음 2개는 다른 방언 파일의 것을 씀(mysql 2개)"가 있고 mysql 파일의 `DATA_LOAD_GROUPED`는 "리터럴 INSERT 14건을 테이블별 구문 2개로 묶음 … 초기화 구문 … 1건"이다. (4) `T_GHOST` 테이블 노드와 그 묶음은 없고 `T_MY_ONLY`의 묶음만 `borrowedFrom=mysql`로 남는다.

## 5. 가정
- dataType은 원문을 대문자로 바꾸고 공백을 정리한 형태다(`NUMBER(5,2)`, `VARCHAR2(10 CHAR)`, `TIMESTAMP(6) WITH TIME ZONE`, `CHAR(1) FOR BIT DATA`). `CCSID`는 타입에 넣지 않는다.
- nullable은 `NOT NULL` 또는 PK이면 false, 그 밖에는 true다. defaultValue는 원문을 정리한 텍스트다(`'x'`, `SYSDATE`, `CURRENT TIMESTAMP`). 값 없는 `WITH DEFAULT`와 `DEFAULT NULL`은 defaultValue를 넣지 않는다(F-007-R16. `ALTER .. MODIFY c DEFAULT NULL`은 기본값을 지운다).
- UNIQUE(제약 또는 유니크 인덱스): 컬럼 하나면 컬럼 attrs.unique=true, 여럿이면 테이블 attrs.uniqueKeys(List, 원소는 `COL1,COL2`)에 넣는다.
- 같은 테이블이 **한 파일 안에서** 여러 번 정의되면(스키마가 같거나 한쪽이 없음) 컬럼을 합친다(같은 이름은 뒤의 정의가 덮어쓴다). 둘 다 스키마가 있고 서로 다를 때만 `DUPLICATE_OBJECT_NAME`(WARN)을 남긴다. **여러 파일**에 정의되면 한 파일의 것만 쓴다(F-007-R14, 아래).
- **정의 고르기(F-007-R14)**: DDL은 받은 대로 모아 뒀다가 모든 파일을 본 뒤 반영한다. CREATE TABLE/VIEW를 먼저(고른 파일의 것만), 그다음 ALTER TABLE·COMMENT ON·CREATE INDEX를 받은 순서대로 반영한다. 그래서 주석 파일이 DDL 파일보다 경로상 앞에 있어도 주석이 붙는다.
- **방언 토큰 표(F-007-R18. mybatis 모듈 F-009 spec 5절과 같은 문구다. 한쪽을 고치면 다른 쪽도 고친다)**: 표준 이름은 `oracle`, `db2`, `tibero`, `altibase`, `postgres`, `mysql`, `mariadb`, `mssql`, `cubrid`, `goldilocks`, `hsql`, `h2`, `derby`, `sqlite`, `sybase`, `informix`이고 이 순서가 기본 선호 순서다(앞의 넷 Oracle, DB2, Tibero, Altibase가 대상 방언과 그 계열이다). 별칭은 `ora`→oracle, `postgresql`·`pgsql`→postgres, `maria`→mariadb, `sqlserver`→mssql, `hsqldb`→hsql이다. 대소문자를 가리지 않고, 표에 없는 토큰은 끝의 숫자(버전)를 떼고 한 번 더 본다(`mysql55`→mysql, `oracle11`→oracle). 경로에서는 파일 이름(마지막 확장자 제외)을 글자·숫자가 아닌 문자로 나눈 토큰을 **뒤에서부터** 보고, 없으면 폴더 이름을 가까운 것부터 본다(폴더는 이름 전체가 방언 토큰일 때만). 낙타 표기로 붙여 쓴 이름(`OracleUserMapper.xml`)과 이름의 일부만 방언인 폴더(`my-oracle-scripts/`)는 읽지 않는다. (`DialectTokens`. sql 모듈은 여기에 더해 Oracle·DB2 확장자 힌트 `.pks`·`.pkb`·`.pls`·`.plb`, `.db2`·`.spsql`도 본다: `DialectSelection.dialectOfFile`.)
- **방언 순위(F-007-R18)**: `DialectSelection`이 프로젝트에 하나인 순위를 갖는다. ① `--dialect` ② `mybatis.dialectVariant=<방언>` ③ 프로젝트 설정에서 읽은 방언 ④ Oracle ⑤ DB2 ⑥ Tibero, Altibase. 그 밖의 방언과 방언을 읽지 못한 파일(한 묶음으로 본다)은 처음 물은 순서로 그 뒤에 선다: 중복 정의가 있는 테이블의 정의 파일을 경로순으로(upgrade 스크립트는 뒤) 묻기 때문에 "경로순 첫 정의 파일의 방언"이 먼저다. 정의 파일 둘을 견줄 때는 upgrade 스크립트가 아닌 것, 방언 순위가 앞선 것, 경로순으로 앞선 것 순이다. 시퀀스·시노님도 같은 순서로 한 벌만 쓴다.
- 매퍼 쪽(F-009-R20)과의 차이: 매퍼는 ① `mybatis.dialectVariant` ② `--dialect`다(명시한 `mybatis.dialectVariant`가 매퍼에는 먼저). 둘을 함께 주면 매퍼와 DDL이 서로 다른 방언을 고를 수 있다(의도한 것이다). ⑦의 대상 외 방언끼리는 매퍼가 토큰 표의 순서, DDL이 경로순이라 다를 수 있다. 맞추려면 옵션 하나를 준다. (2026-10-11: 이 차이는 F-007-R28·F-009-R26으로 없앴다. 아래 "그 밖의 방언의 순서".)
- **요약 진단(F-007-R19)**: "방언 변형"은 정의한 파일들에서 읽은 방언이 둘 이상인 테이블·뷰다. 그런 테이블을 정의한 방언 파일이 "방언 변형 파일"이고, 요약의 "테이블 a개"는 가장 앞선 방언의 변형 파일에서 정의를 쓴 테이블·뷰 수, "보충 b개"는 다른 방언의 변형 파일에서 쓴 수(그 방언에만 있는 테이블 포함), "제외한 파일 c개"는 쓴 것이 하나도 없는 변형 파일(과 F-007-R20의 묶음을 모두 버린 적재 파일) 수다. 파일 쌍마다의 `DUPLICATE_OBJECT_NAME`(INFO)은 전처럼 함께 남는다(버린 파일이 어느 것인지는 그쪽에 있다). `DIALECT_NOT_FOUND`는 방언 변형이 있을 때만, 요청한 방언(순위대로 보다가 파일이 있는 요청에서 멈춘다)의 정의·적재 파일이 하나도 없을 때 남긴다. 프로젝트 설정에서 읽은 방언의 파일이 없는 것은 경고하지 않고(명시한 요청이 아니다) 요약 메시지에 "프로젝트 설정(…)의 방언 hsql 파일은 없어 쓰지 않음"만 덧붙인다(매퍼는 그 방언을 골랐을 수 있다). 묶음을 모두 버린 적재 파일에는 "이 파일에서 읽은 것이 없음"(F-006-R22) 경고를 내지 않는다.
- **프로젝트 설정의 방언(F-007-R21. mybatis 모듈 F-009 spec 5절과 같은 문구다)**: `.properties`와 `.yml`·`.yaml`에서 읽는다. 방언 이름을 값으로 갖는 키는 `Globals.DbType`, `jdbc.type`, `mybatis.configuration.database-id`이고 값을 방언 토큰 표로 읽는다. `spring.datasource.url`은 `jdbc:` 뒤의 조각에서(래퍼 `log4jdbc`·`p6spy`·`jtds`·`tc`는 건너뛴다. `jdbc:oracle:thin:…`, `jdbc:log4jdbc:mysql:…`, `jdbc:informix-sqli:…`), `spring.datasource.driver-class-name`은 클래스 이름의 조각(점으로 나눈 것)이 방언 토큰이거나 방언 토큰으로 시작하면(`DB2Driver`, `OracleDriver`) 그 방언으로 읽는다. 키는 대소문자와 `-`·`_`를 가리지 않는다(`driverClassName`). YAML은 들여쓰기로 중첩된 키를 점으로 이어 읽는다. 주석 처리된 줄, `${…}`가 든 값, `src/test` 아래의 파일은 보지 않는다. 읽은 방언이 여러 파일·프로파일(YAML 문서)에 걸쳐 하나로 모이지 않으면(서로 다른 방언) 쓰지 않는다. (`ProjectDialect`. 두 모듈이 같은 구현을 따로 둔다.)
- **데이터 적재 스크립트의 방언(F-007-R20)**: 방언을 읽은 적재 파일들 가운데 같은 테이블의 묶음이 둘 이상의 방언에 있으면, 순위가 가장 앞선 방언의 파일 것만 남긴다(같은 방언의 파일이 여럿이면 모두 남긴다). 테이블 단위로 고르므로 한 방언에만 있는 테이블의 묶음은 그 파일에 남는다. 묶음을 모두 버린 파일만 "제외한 파일"이고 그 파일의 초기화 구문도 노드로 만들지 않는다. 그 파일의 다른 구문(UPDATE 등)은 보통의 구문으로 남는다.
- **upgrade 스크립트의 외래 키(F-007-R22)**: 대상은 현재(선택된) CREATE TABLE이 upgrade 스크립트가 아닌 다른 파일에 있는 테이블에 대한 upgrade 스크립트의 ALTER TABLE이다. 그 스크립트가 같은 테이블의 CREATE를 담고 있어 "선택되지 않은 정의 파일"이어도 같다(전에는 이 경우 FK가 집계 없이 사라졌다). `ADD [CONSTRAINT ..] FOREIGN KEY`는 자식 컬럼이 모두 현재 정의에 있고 부모 테이블이 선언돼 있으며 적어 준 참조 컬럼이 부모에 있을 때 적용한다. 같은 FK가 현재 정의에 이미 있으면 빠진 정보만 채우고 `fromUpgrade`를 붙이지 않는다(적용 건수에도 세지 않는다). 진단의 "ALTER TABLE n건"은 적용하지 않은 동작이 하나라도 있는 구문 수이고, 종류별 건수는 동작 수다. 적용하지 않은 것이 없고 FK만 적용한 파일에도 `DDL_NOT_APPLIED` 한 건("외래 키 추가 k건은 … 적용함. 적용하지 않은 ALTER TABLE은 없음")을 남긴다. 방언이 다른 중복 파일(upgrade가 아닌 선택되지 않은 정의 파일)의 ALTER는 전처럼 적용하지도 세지도 않는다(`DUPLICATE_OBJECT_NAME`·`DIALECT_VARIANT_DDL`로 갈음).
- (F-007-R18 이전의 설명. 고르는 순서는 위 "방언 순위"가 대체했다) 경로 힌트: `DialectDetector.fromPath`의 Oracle·DB2 힌트(`/oracle/`, `/ora/`, `/db2/`, 확장자)와, 경로에 든 DB 제품 이름(파일 이름의 조각 → 가까운 폴더 순으로 `oracle`, `ora`, `db2`, `mysql`, `maria`, `mssql`, `sqlserver`, `postgres`, `altibase`, `tibero`, `cubrid`, `goldilocks`, `hsql`, `h2`, `derby`, `sqlite`, `informix`, `sybase`)을 쓴다. `--dialect`가 있으면 그 방언의 경로가, auto면 Oracle·DB2 힌트가 있는 파일이 먼저다(둘 다 있으면 경로순). upgrade 스크립트(아래)는 언제나 뒤로 간다.
- 선택된 정의가 있는 테이블에 대한 ALTER TABLE·COMMENT ON·CREATE INDEX는 (a) 선택된 파일의 것, (b) 그 테이블을 정의하지 않은 파일의 것 가운데 방언 이름이 없거나 선택된 파일과 같은 것만 반영한다. 선택되지 않은 정의 파일의 구문과, 정의가 여러 벌인 테이블에 대한 다른 방언 경로의 구문은 반영하지 않는다(진단은 파일 쌍 요약으로 갈음한다). CREATE가 어디에도 없는 테이블의 ALTER·INDEX는 전처럼 참조 노드에 반영한다.
- 뷰와 시퀀스·시노님도 같은 순서로 한 벌만 쓴다(뷰 정의 쿼리 구문 노드가 방언 수만큼 생기지 않는다). 시퀀스·시노님의 중복은 진단 없이 줄인다.
- 중복 진단의 "테이블 n개"는 테이블과 뷰의 가짓수다. 스키마가 서로 다른 중복(둘 다 스키마가 있음)은 요약에 넣지 않고 전처럼 `DUPLICATE_OBJECT_NAME`(WARN)을 테이블마다 남긴다. 진단의 위치는 버린 파일이다.
- **upgrade 스크립트의 ALTER(F-007-R15)**: 경로의 폴더 이름에 `upgrade`·`patch`·`migration`·`migrate`가 들었거나 파일 이름이 `upgrade`·`patch`·`migrat`·`update<글자 아님>`으로 시작하면 upgrade 스크립트로 본다. 이런 파일의 ALTER TABLE은 대상 테이블의 선택된 CREATE TABLE이 upgrade 스크립트가 아닌 다른 파일에 있을 때 반영하지 않는다(현재 DDL에 이미 들어 있을 과거의 변경이 현재 정의를 덮어 유령 컬럼이 생기는 것을 막는다). CREATE TABLE도 migration 폴더에 있으면(Flyway식) 순서대로 반영한다. "아직 현재 DDL에 반영되지 않은 upgrade"는 놓친다.
- **MySQL 인덱스 절(F-007-R16)**: `KEY|INDEX [이름] [USING ..] (컬럼, ..)`는 CREATE INDEX와 같게 `indexes`(`이름(COL,..)`)와 컬럼 `indexed`로 기록하고, `UNIQUE KEY|INDEX 이름 (..)`는 UNIQUE 제약으로, `FULLTEXT|SPATIAL KEY`는 기록하지 않는다. `KEY VARCHAR(10)`처럼 괄호 안이 숫자면 KEY·INDEX라는 이름의 컬럼이다. `ALTER TABLE t ADD INDEX|KEY ..`도 같다.
- 범위 밖 방언의 표기(F-007-R17): `AUTO_INCREMENT`는 `identity=true`, `UNSIGNED`·`ZEROFILL`은 타입 텍스트의 일부(`INT(10) UNSIGNED`), 컬럼·테이블 `COMMENT '..'`는 건너뛴다(주석으로 기록하지 않는다). HSQL `CREATE MEMORY|CACHED|TEXT TABLE`은 보통 테이블이고 `CREATE TEMP TABLE`은 `temporary=true`다.
- CREATE TABLE 없이 ALTER TABLE이나 CREATE INDEX만 있는 테이블은 참조 노드(declared=false)로 두고 제약, 인덱스, 컬럼은 기록한다.
- `ALTER TABLE ... DROP COLUMN`과 `RENAME COLUMN`은 카탈로그에 반영한다. 그 밖의 ALTER 동작과 ALTER TABLE이 아닌 ALTER 문은 조용히 건너뛴다.
- `CREATE TABLE ... AS SELECT`는 테이블 선언과 함께 SQL_STATEMENT 노드(statementType=`INSERT`)를 만들어 새 테이블로 WRITES(op=INSERT), 원본으로 READS를 남긴다. 컬럼은 선택 목록의 별칭·단순 컬럼에서 얻는다.
- 뷰 컬럼은 명시 목록이 있으면 그것을, 없으면 선택 목록의 별칭이나 단순 컬럼 이름을 쓴다. `*`는 카탈로그에 원본이 있을 때만 펼친다. 식이고 별칭이 없는 항목은 컬럼으로 만들지 않는다. 뷰 컬럼의 dataType은 원본이 단순 컬럼일 때 그 타입이다. ordinal은 만들어진 컬럼의 순번이다.
- 뷰 정의 쿼리의 구문 노드 ID는 `sql:stmt:<파일>:<CREATE 문의 시작 줄>`이다. 뷰도 카탈로그에 들어가므로 뷰를 읽는 구문의 컬럼 소속 판정에 쓰인다(테이블 엣지는 계약대로 `db:table:<뷰 이름>` 참조다).
- ALIAS_OF의 대상은 항상 `Ids.table(대상)` 참조다(대상이 뷰·시퀀스·루틴·패키지인지는 링커가 맞춘다). 시노님 attrs.target에 원문 대상(`SCHEMA.NAME[@LINK]`)을 남긴다.
- 패키지 명세·본문 안의 PROCEDURE/FUNCTION은 이름과 매개변수만 읽어 `db:routine:<패키지>.<이름>`으로 선언한다(오버로드는 한 노드로 합쳐진다. 본문 안의 중첩 서브프로그램은 선언하지 않는다). 위치는 본문 쪽이 있으면 본문 쪽이다.
- 인덱스 표기: attrs.indexes 항목은 `IX1(A,B)` 또는 `UX1 UNIQUE(C)` 형태의 문자열이고 식 인덱스는 식을 공백을 정리한 원문으로 적는다(`IX2(UPPER(D),A)`, 식별자는 대문자). 내림차순 항목은 뒤에 ` DESC`를 붙인다(`IX1(A,B DESC)`, `IX2(UPPER(D) DESC)`). `ASC`는 기본값이라 적지 않는다(F-007-R13).
- 루틴 본문 분석은 이 기능의 범위가 아니다. 헤더만 읽고 단위마다 `UNSUPPORTED_SYNTAX`(INFO)를 남기는 핸들러는 `HeaderOnlyRoutineHandler`이고, 이 기능의 테스트가 명시해서 쓴다. (F-008 이후 갱신: `SqlAnalyzer`의 기본 핸들러는 본문까지 분석하는 `RoutineBodyHandler`로 바뀌었다. 기본 실행에서는 이 진단이 본문마다 남지 않는다.)
- 스키마 이름은 따옴표 안의 앞뒤 공백을 뗀다(db2look의 `"SCH     "`).
- **방언 순위(F-007-R23. mybatis 모듈 F-009 spec 5절과 같은 문구다. 한쪽을 고치면 다른 쪽도 고친다. 위 "방언 순위(F-007-R18)"의 순서를 대체한다)**: ① 명시값 — DDL·데이터 스크립트는 `--dialect`, 그다음 `mybatis.dialectVariant=<방언>`; 매퍼는 `mybatis.dialectVariant=<방언>`, 그다음 `--dialect` ② 프로젝트 설정이 대상 방언(oracle, db2)을 가리키면 그 방언 ③ Oracle ④ DB2 ⑤ Oracle 계열(Tibero, Altibase. 프로젝트 설정이 이 가운데 하나를 가리키면 그것이 먼저) ⑥ 프로젝트 설정이 가리키는 대상 외 방언 ⑦ 그 밖의 방언: 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 이름의 사전순으로 먼저, 나머지가 사전순으로 그 뒤(F-007-R28·F-009-R26. 2026-10-11 고쳐 씀: 전에는 DDL·데이터 스크립트가 경로순 첫 파일의 방언, 매퍼가 방언 토큰 표의 순서여서 대상 방언 파일이 없는 프로젝트에서 서로 다른 방언을 골랐다). 설정이 mysql·hsql 같은 대상 외 방언이어도 대상 방언의 파일이 있으면 그쪽을 고른다(이 도구의 대상 방언은 Oracle·DB2이고, 전자정부 템플릿의 기본값은 `Globals.DbType=mysql`·`hsql`이다). 요약 진단(`DIALECT_VARIANT_DDL`/`DIALECT_VARIANT_MAPPERS`)은 그때 INFO로 "프로젝트 설정(<파일>:<키>=<값>)과 다르게 대상 방언 <방언> 파일을 사용. 설정대로 분석하려면 `--opt mybatis.dialectVariant=<설정의 방언>`"을 적는다(`--dialect`는 oracle·db2만 받으므로 대상 외 방언은 이 옵션으로 준다. 이 옵션은 DDL 선택에도 쓰인다). 설정에 따라 고른 방언이 대상 방언(과 그 계열)이면 "프로젝트 설정(…)에 따라 <방언> 선택"이다. 대상 방언과 그 계열의 파일이 하나도 없어 ⑥·⑦로 대상 외 방언을 쓰게 될 때만 WARN이고 "이 도구의 대상 방언(Oracle, DB2)의 파일이 없어 <방언> 파일을 씀: SQL 해석이 부정확할 수 있음"을 적는다(명시해 요청한 방언이면 INFO다). 프로젝트 설정이 파일마다 엇갈리면 쓰지 않고 요약에 "프로젝트 설정이 엇갈려 쓰지 않음(<파일>=<방언>, …)"을 적는다.
- **내용 특징으로 읽는 방언(F-007-R24)**: 경로(파일 이름의 토큰, 폴더 이름, Oracle·DB2 확장자 힌트)에 방언이 없을 때만 본다(`DialectDetector.contentDialect`). hsql → mysql → oracle → db2 순으로 확인하고 확실한 표기가 없으면 방언을 읽지 못한 파일이다. 이렇게 읽은 방언은 경로에서 읽은 것과 똑같이 쓰인다: 방언별 여러 벌의 판정과 선택, 요약의 방언 목록, 구문 노드의 `dialect`(oracle·db2일 때). 줄 처음 `#` 주석을 받을지(F-006-R27)는 전과 같이 경로의 방언 이름이 먼저다.
- **고르지 않은 방언의 파일(F-007-R27)**: 고른 방언은 감지한 방언 가운데 순위가 가장 앞선 것이다(`DialectSelection.mainDialect`). 방언을 읽은 파일 가운데 그 방언이 아니고 테이블·뷰 정의를 하나도 쓰지 않은 파일이 대상이다: 적재 묶음을 모두 버리고(요약의 "묶음 n개를 만들지 않음"에 든다) DML 구문(INSERT, UPDATE, DELETE, 초기화 구문)을 노드로 만들지 않는다. 그 파일의 CREATE·ALTER·COMMENT와 루틴은 전과 같이 다룬다(정의가 선택되지 않았으면 쓰이지 않는다). 다른 방언에서 정의를 보충해 온 파일과 방언을 읽지 못한 파일은 그대로다. 방언별 여러 벌이 감지되지 않은 프로젝트에는 적용하지 않는다.
- **적재 묶음은 테이블마다 고른다(F-007-R29. 위 "데이터 적재 스크립트의 방언(F-007-R20)"과 "고르지 않은 방언의 파일(F-007-R27)"의 적재 묶음 부분을 대체한다)**: `SqlAnalyzer.selectLoadVariants`가 DDL 반영 뒤에 한 번 고른다. ① 방언을 읽은 적재 파일들에서 테이블 → 그 테이블의 묶음을 가진 파일 목록을 만든다. 같은 테이블의 묶음이 둘 이상의 방언에 있으면 전처럼 방언별 적재 스크립트로 감지한다(DDL 중복이 없어도 요약이 나온다). ② 방언별 여러 벌이 감지된 프로젝트(`mainDialect`가 있다)에서만, 테이블마다 그 파일들의 방언 가운데 순위가 가장 앞선 방언을 고르고 다른 방언 파일의 묶음을 버린다. 고른 방언(`mainDialect`)의 파일에 그 테이블의 적재가 있으면 언제나 그 방언이 뽑힌다. ③ 남은 묶음의 파일이 고른 방언의 것이 아니면 `borrowedFrom=<그 파일의 방언>`을 붙인다. 단 그 파일이 정의를 하나도 쓰지 않은 파일(F-007-R27의 대상)이면 테이블이 카탈로그에 정의돼 있을 때만(시노님은 풀어서 본다) 남기고, 아니면 버린다: 방언별 comment 스크립트의 `_CUB_SCHEMA_COMMENTS`처럼 어느 DDL에도 없는 테이블의 유령 노드를 방언 파일마다 만들지 않기 위해서다. 정의를 보충해 온 파일(HSQL 테스트 픽스처처럼 그 방언에만 있는 테이블을 정의한 파일)의 묶음은 전처럼 정의 여부를 보지 않는다. ④ 버린 묶음의 테이블을 비우는 초기화 구문은 노드로 만들지 않는다. 고르지 않은 방언의 파일(DML 구문을 만들지 않는 파일)에서는 남은 묶음의 테이블을 비우는 초기화 구문만 만든다. 다른 방언 파일의 초기화 구문에도 `borrowedFrom`이 붙는다. ⑤ 묶음이 있었는데 하나도 남지 않은 파일이 "제외한 파일"이다. 요약의 "묶음 n개를 만들지 않음"은 ②와 ③에서 버린 묶음 수, "묶음 m개는 다른 방언 파일의 것을 씀(<방언> k개)"은 `borrowedFrom`을 붙인 묶음 수다(초기화 구문은 세지 않는다). 알려진 한계: 고른 방언의 파일에 그 테이블의 적재가 조금이라도 있으면 다른 방언 파일의 더 큰 묶음은 버려진다(jeesite `SYS_MENU`: `db/gen/example_oracle.sql`의 10행이 남고 `db/jeesite_mysql.sql`의 97행은 빠진다). 앱이 실제로 실행하는 스크립트인지는 보지 않는다(simple-backend의 `src/main/resources/db/shtdb.sql`은 10개 테이블이 모두 `DATABASE/all_sht_data_oracle.sql`에 있어 전처럼 빠진다). `borrowedFrom`은 매퍼 구문의 같은 attr(F-009-R25)와 뜻이 같아 소비자의 "다른 방언 파일에서 보충한 구문" 목록에 적재 묶음도 들어간다.
- **upgrade 외래 키의 신뢰도(F-007-R26)**: "현재 정의 파일이 다른 외래 키를 담고 있다"는 그 테이블의 선택된 CREATE TABLE이 있는 파일에 upgrade가 아닌 외래 키(인라인, 그 파일의 ALTER)가 하나라도 있다는 뜻이다. 그 테이블에 다른 파일(제약 스크립트)의 외래 키가 이미 있어도 같다. 현재 정의가 외래 키를 적는 스키마인데 이 외래 키만 없으면 지금은 없는 제약일 수 있어 추론으로 낮춘다.
- **그 밖의 방언의 순서(F-007-R28. mybatis 모듈 F-009 spec 5절과 같은 문구다. 한쪽을 고치면 다른 쪽도 고친다)**: "그 밖의 방언"은 방언 토큰 표에서 대상 방언과 그 계열(oracle, db2, tibero, altibase)을 뺀 것이다. 두 분석기는 서로 의존하지 않으므로(ADR-0007) 각자 프로젝트의 파일 목록(`AnalysisContext.files()`)에서 같은 순서를 계산한다: 스캐너가 `MYBATIS_MAPPER`로 분류한 파일들의 경로에서 읽은 방언과 `SQL`로 분류한 파일들의 경로에서 읽은 방언(방언 토큰 표의 경로 규칙 그대로)의 교집합이 표준 이름의 사전순으로 먼저고, 나머지 방언이 사전순으로 그 뒤다. 사전순은 `cubrid`, `derby`, `goldilocks`, `h2`, `hsql`, `informix`, `mariadb`, `mssql`, `mysql`, `postgres`, `sqlite`, `sybase`다. 파일 내용은 읽지 않는다. 이 순서는 다른 방언에서 보충할 때(고른 방언 파일에 없는 구문·테이블)와 요약 진단의 방언 나열에도 쓰인다: 전에는 mysql과 hsql에만 있는 구문을 mysql에서 가져왔고 이제는 hsql에서 가져온다. 사전순으로 정한 까닭: 방언 토큰 표의 순서(postgres, mysql, …)는 근거가 없는 나열이고, 경로순은 폴더 이름을 방언 이름으로 두는 배치에서 사전순과 같다(sql 모듈의 기존 테스트는 기대값을 바꾸지 않고 통과했다). [확인 필요] 공개 프로젝트를 다시 돌려 고른 방언이 바뀌는지는 재 보지 않았다. 두 모듈의 규칙이 같은지는 양쪽 테스트의 같은 표(`DialectTokenTableTest.otherDialectOrder`, sql의 `DialectTokensTest.otherDialectOrder`)가 확인한다.
- sql 쪽 구현(F-007-R28): `DialectSelection.rank`는 그 밖의 방언 가운데 하나를 처음 물을 때 그 밖의 방언 전부에 위 순서로 한꺼번에 순위를 준다(물은 순서와 무관하다). 방언을 읽지 못한 파일(키 `""`)만 전처럼 처음 물은 순서로 선다. 그래서 방언을 읽지 못한 파일은 그 밖의 방언들 사이에 끼지 않고 통째로 앞이나 뒤에 선다(전에는 경로순으로 사이에 낄 수 있었다). 파일 목록 없이 만든 `DialectSelection`(`defaults()`, 단위 테스트)은 양쪽에 다 있는 방언이 없는 것으로 보고 사전순만 쓴다. 내용 특징으로 읽은 방언(F-007-R24)도 순위는 같은 표에서 받지만 "양쪽에 다 있는 방언"에는 들지 않는다(mybatis가 스크립트 내용을 읽지 않으므로 경로만 센다).

### 알려진 한계
- 그 밖의 방언의 순서(F-007-R28)는 경로만 본다. 그래서 대상 방언 파일이 없는 프로젝트에서 매퍼와 DDL의 방언이 아직 어긋날 수 있는 경우가 남는다: (1) 경로가 아니라 내용 특징으로 방언을 읽은 스크립트(F-007-R24)는 "양쪽에 다 있는 방언"에 들지 않는다. (2) 경로에는 양쪽에 다 있는 방언이어도 한쪽에서는 여러 벌이 아닐 수 있다(그 방언의 매퍼 파일이 다른 방언과 namespace를 나누지 않거나, 그 방언의 DDL이 다른 방언과 같은 테이블을 정의하지 않는다). 그러면 그쪽은 여러 벌인 방언들 가운데 순서가 가장 앞선 것을 고른다. (3) 양쪽에 다 있는 방언이 하나도 없으면 각자 자기 쪽 방언의 사전순 첫째를 고른다. 어긋나면 cli가 힌트에 `경고:`를 낸다(F-001-R52). `--opt mybatis.dialectVariant=<방언>`으로 맞춘다.
- `CREATE TYPE`(명세), `CREATE TABLE ... LIKE`, 객체 테이블, 중첩 테이블 저장 절은 조용히 건너뛴다. `TYPE BODY`는 지원하지 않는 루틴 단위로 INFO 진단만 남긴다.
- CHECK 제약, 스토리지·파티션 절은 기록하지 않는다.
- FK의 참조 컬럼이 생략됐는데 부모 정의가 없거나 PK 컬럼 수가 다르면 refColumns를 넣지 않는다.

## 6. 미확정 사항
- 없음(사용자가 기본값으로 진행하도록 위임했다. 5절의 가정은 검토 대상이다).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: [0004](../../decisions/0004-hand-written-tolerant-sql-parser.md)

## 8. 계약 추가 제안 (F-001 design.md에 반영 요청)
design.md 반영됨(2026-10-11, 5차 수정 F-007-R29): 3.3 "데이터 적재 묶음 구문", 3.5 공통 규칙 "데이터 적재 구문"(테이블 단위 선택, `borrowedFrom`, 정의 없는 테이블), 3.5 노드 attrs 표 `borrowedFrom`(데이터 적재 구문), 3.7 `DIALECT_VARIANT_DDL`(가져온 묶음 수 문구)·`DATA_LOAD_GROUPED`(노드가 된 것만 센다), 7절 한계 표("방언별 적재 스크립트는 테이블마다 한 방언의 묶음만 남는다"). 루트 README "방언별 DDL" 항목의 적재 묶음 문장도 고쳤다.

design.md에 반영할 것(2026-10-11, 그 밖의 방언의 순서 F-007-R28·F-009-R26. ~~아직 반영 전~~ design.md 반영됨(2026-10-11: 3.5 "방언 변형 선택" ⑦, 7절 한계 표). F-009 spec 8절의 같은 날짜 항목과 한 묶음이다): 3.5 공통 규칙 "방언 변형 선택"의 ⑦을 "그 밖의 방언: 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 이름의 사전순으로 먼저, 나머지가 사전순으로 그 뒤(mybatis와 sql이 같은 파일 목록에서 같은 순서를 계산한다). sql의 방언을 읽지 못한 파일은 처음 물은 순서(경로순)로 그 밖의 방언들 앞이나 뒤에 선다"로 바꾼다. 7절 한계 표의 "대상 방언의 파일이 없으면 매퍼와 DDL의 방언이 갈릴 수 있다" 행은 남은 경우(이 spec 5절 알려진 한계의 (1)~(3))로 고쳐 쓴다. ADR-0010의 순위 7번은 같은 날 고쳤다.

design.md 반영 요청(2026-10-11, 재검증 3차 후속·방언 정책. ~~아직 반영 전~~):
design.md 반영됨(2026-10-11): 방언 순위는 3.5 공통 규칙 "방언 변형 선택"(새 항목)과 "같은 테이블의 중복 정의"에, 진단의 수준·문구와 `DIALECT_NOT_TARGET`, `DUPLICATE_OBJECT_NAME`의 범위는 3.7, `fromUpgrade`의 `INFERRED`·`note`는 3.5(공통 규칙과 엣지 attrs 표의 REFERENCES), 고르지 않은 방언 파일의 적재 묶음·DML은 3.3·3.5 "데이터 적재 구문"에 넣었다. 방언 선택 설명은 4.1이 아니라 3.5에 모았다(4.1에는 방언 선택 설명이 없었다). 정책의 근거와 대안은 ADR-0010(제안 상태)에 적었다.
- 3.5 공통 규칙 "같은 테이블의 중복 정의"와 4.1의 방언 선택 설명: 순서를 "방언 순위(F-007-R23·F-009-R24)"로 바꾼다 — 명시값 → 프로젝트 설정이 가리키는 대상 방언 → Oracle → DB2 → Oracle 계열 → 설정이 가리키는 대상 외 방언 → 경로순(매퍼는 표의 순서). "프로젝트 설정이 Oracle보다 먼저"는 설정이 대상 방언일 때만 참이다.
- 3.7 진단: `DIALECT_VARIANT_DDL`·`DIALECT_VARIANT_MAPPERS`의 수준과 문구(설정과 다르게 대상 방언을 골랐으면 INFO와 안내, 대상 방언 파일이 없어 대상 외 방언을 쓸 때만 WARN, 엇갈린 설정). 새 코드 `DIALECT_NOT_TARGET`(INFO, 분석 한 번에 1건, 위치 없음, 모듈 sql `SqlDiagnosticCodes`): 방언별 여러 벌이 아닌데 SQL 스크립트가 대상 외 방언뿐이다. `DUPLICATE_OBJECT_NAME`(INFO)은 방언별로 한 벌을 고른 중복에는 남기지 않는다.
- 3.5 `REFERENCES`: `fromUpgrade=true`인 엣지는 현재 정의가 외래 키를 적는 스키마면 `INFERRED`이고 `note="현재 정의에 없음"`이 붙는다(F-007-R26). 소비자는 `fromUpgrade`와 신뢰도를 함께 본다.
- 3.5 공통 규칙 "데이터 적재 구문"과 3.3: 고르지 않은 방언의 파일에서는 적재 묶음과 DML 구문 노드를 만들지 않는다(그 방언 파일에만 있는 테이블의 것 포함, F-007-R27).

design.md 반영 요청(2026-10-11, 3차 재검증 후속. ~~아직 반영 전~~):
design.md 반영됨(2026-10-11): `DIALECT_VARIANT_DDL`·`DIALECT_NOT_FOUND`·`DDL_NOT_APPLIED`는 3.7, `fromUpgrade`는 3.5 엣지 attrs 표(REFERENCES), 적재 묶음의 방언 선택은 3.5 "데이터 적재 구문", `--dialect`와 `mybatis.dialectVariant`의 읽는 모듈(sql)은 3.8, 힌트는 4.5(이미 있었다)에 있다. 이 블록의 방언 순위(F-007-R18)와 "프로젝트 설정에서 읽은 대상 외 방언을 골랐으면 `WARN`"은 위 블록(F-007-R23)이 대체했으므로 코드대로 위 블록의 내용을 적었다.
- 3.7 "모듈이 정의한 코드": `DIALECT_VARIANT_DDL` — `INFO`(프로젝트 설정에서 읽은 대상 외 방언을 골랐으면 `WARN`). 방언별 DDL·데이터 적재 스크립트 여러 벌을 감지해 한 방언을 고름. 분석 한 번에 1건, 위치 없음. 메시지: "방언별 DDL n벌 감지(<방언 목록>): <방언> 파일을 사용(테이블 a개), 다른 방언에서 보충 b개, 제외한 파일 c개. …". 코드 상수 `io.codeatlas.sql.SqlDiagnosticCodes`. 모듈 sql.
- 3.7: `DIALECT_NOT_FOUND` — `WARN`. 요청한 방언(`--dialect`, `mybatis.dialectVariant`)의 DDL·데이터 스크립트 파일(sql)·매퍼 파일(mybatis)이 하나도 없어 다른 방언으로 대체함. 모듈마다 분석 한 번에 1건, 위치 없음. 모듈 sql, mybatis.
- 3.7 `DDL_NOT_APPLIED`: 메시지에 종류별 건수(컬럼 추가, 컬럼 변경·삭제, 제약 추가)와 적용한 외래 키 건수가 든다. 외래 키만 적용하고 적용하지 않은 것이 없는 파일에도 1건이 남는다.
- 3.5 엣지 attrs `REFERENCES`: `fromUpgrade`(boolean) — upgrade·patch·migration 스크립트의 `ALTER TABLE … ADD FOREIGN KEY`에서 온 DDL 관계(`source=DDL`). 위치는 그 스크립트다. 현재 정의의 파일에는 없는 FK라는 뜻이다.
- 3.5 공통 규칙 "같은 테이블의 중복 정의": 고르는 순서를 F-007-R18의 방언 순위로 바꾼다(`--dialect` → `mybatis.dialectVariant` → 프로젝트 설정 → Oracle → DB2 → Tibero·Altibase → 경로순 첫 파일의 방언. 프로젝트 전체로 한 순위). 방언은 파일 이름의 토큰과 폴더 이름에서 읽는다(토큰 표는 F-007 spec 5절).
- 3.5 공통 규칙 "데이터 적재 구문": 같은 테이블의 적재 묶음이 방언이 다른 파일 여러 벌에 있으면 한 방언의 것만 노드가 된다(F-007-R20).
- 3.8: `--dialect` 설명에 "방언별 DDL·데이터 스크립트의 방언(없으면 `mybatis.dialectVariant`, 프로젝트 설정 순)"을, `mybatis.dialectVariant`의 "읽는 모듈"에 sql을 더한다.
- 4.5 분석 요약의 힌트: `DIALECT_VARIANT_DDL`·`DIALECT_NOT_FOUND`가 있으면 메시지를 옮긴다(아래 "cli가 알아야 할 것").

cli(힌트) 담당이 알아야 할 것: (1) 새 진단 `DIALECT_VARIANT_DDL`(INFO 또는 WARN)과 `DIALECT_NOT_FOUND`(WARN, sql과 mybatis가 각각 최대 1건)는 위치가 없다. 메시지에 이미 옵션 안내(`--dialect <oracle|db2>`, `--opt mybatis.dialectVariant=<방언>`, `--dialect oracle`)가 들어 있어 그대로 옮기면 된다. (2) 방언별 DDL이 여러 벌이면 `DUPLICATE_OBJECT_NAME`(INFO, 파일 쌍마다)도 전처럼 함께 남는다: 힌트를 둘 다 내면 겹치므로 `DIALECT_VARIANT_DDL`이 있으면 중복 정의 힌트를 접는 편이 낫다. (3) `DATA_LOAD_GROUPED`의 메시지 문구가 바뀌었다("데이터 적재 파일: 리터럴 INSERT n건을 테이블별 구문 m개로 묶음…"). 코드로만 세면 영향이 없다. (4) `DIALECT_VARIANT_MAPPERS`가 `WARN`일 수 있다(프로젝트 설정에서 대상 외 방언을 읽었을 때).

design.md에 반영됨(2026-10-11, 2차): 한 파일의 정의만 쓰는 규칙과 고르는 순서, DDL 반영 순서는 3.5 공통 규칙 "같은 테이블의 중복 정의", `indexes`의 MySQL `KEY`와 `DEFAULT NULL`은 3.5 노드 attrs 표(DB_TABLE, DB_COLUMN), `DUPLICATE_OBJECT_NAME`의 INFO 경우와 `DDL_NOT_APPLIED`는 3.7, `--dialect`가 중복 정의의 파일도 정한다는 것은 3.8 표 아래 문단, 남은 한계는 7절에 넣었다.

- 진단 코드(3.7) `DUPLICATE_OBJECT_NAME`에 경우 추가: **INFO**, "같은 테이블·뷰의 정의가 여러 파일에 있어 한 파일의 것만 씀"(sql). 테이블마다가 아니라 (쓴 파일, 버린 파일) 쌍마다 1건이고 폴더 쌍으로 줄일 수 있다. 위치는 버린 파일. 기존의 "서로 다른 스키마" 경우는 그대로 WARN이다.
- 진단 코드(3.7, "모듈이 정의한 코드" 표): `DDL_NOT_APPLIED` — INFO, "upgrade·patch·migration 스크립트의 ALTER TABLE n건을 적용하지 않음", 파일마다 1건, 모듈 sql(`io.codeatlas.sql.SqlDiagnosticCodes`).
- 3.5 DB_TABLE·DB_COLUMN 설명에 덧붙일 것: 한 테이블의 `location`, 컬럼, 키, `indexes`, DDL `REFERENCES`는 모두 **선택된 한 파일**(과 그 방언·공통 경로의 ALTER·INDEX·COMMENT)에서 온다. 고르는 순서는 `--dialect` → Oracle·DB2 경로 힌트 → 경로순이다. `indexes`에는 MySQL의 테이블 수준 `KEY 이름 (cols)`도 `이름(COL,..)`로 들어간다. `DEFAULT NULL`은 `defaultValue`를 넣지 않는다.
- 3.8 옵션 설명에 덧붙일 것: `--dialect`는 구문의 방언 판정뿐 아니라 중복된 테이블 정의 가운데 어느 파일의 것을 쓸지도 정한다.

아래는 이전 라운드의 항목이다.
design.md에 반영됨(2026-10-11): 아래 항목은 모두 F-001 design.md 3.4·3.5에 있다. 이번 라운드의 `indexes` 형식(` DESC`, 식 인덱스)은 3.5 DB_TABLE 행에 넣었다.

- (2026-10-11 감사 후속, design.md에 반영됨(2026-10-11)) DB_TABLE attrs `indexes`의 형식 설명을 `IX1(A,B DESC)`, `UX1 UNIQUE(C)`, 식 인덱스는 `IX2(UPPER(D),A)`로 고친다(내림차순 항목 뒤에 ` DESC`).
- DB_TABLE attrs: `primaryKey`(List, PK 컬럼 순서대로), `uniqueKeys`(List, 복합 UNIQUE마다 `COL1,COL2`), `indexes`(List), `temporary`(boolean).
- DB_VIEW attrs: `materialized`(boolean).
- DB_COLUMN attrs: `unique`(boolean), `identity`(boolean), `indexed`(boolean), `virtual`(boolean). 뷰 컬럼에는 `nullable`, `primaryKey`를 넣지 않는다.
- DB_SEQUENCE attrs: `schema`.
- DB_ROUTINE attrs: `schema`, `package`(소속 패키지 이름), 트리거의 `triggerEvent`(예: `AFTER UPDATE OF ORDER_STATUS`).
- DB_PACKAGE attrs: `dialect`, `schema`, `hasBody`(boolean).
- DB_SYNONYM attrs: `schema`, `target`(원문 대상 이름), `public`(boolean).
- REFERENCES attrs: `onDelete`(`CASCADE`, `SET NULL`, `SET DEFAULT`, `RESTRICT`, `NO ACTION`).
- CONTAINS: FILE→DB_TABLE/DB_VIEW/DB_SEQUENCE/DB_SYNONYM/DB_ROUTINE/DB_PACKAGE/SQL_STATEMENT, DB_VIEW→SQL_STATEMENT(정의 쿼리).
