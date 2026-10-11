---
type: spec
id: F-006
title: SQL 파서(DML)
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-006 SQL 파서(DML)

## 1. 배경
콜트리를 테이블까지 잇고(PRD 목표 1) ERD의 관계를 조인에서 추론하려면(PRD 목표 2) SQL 구문이 어떤 테이블·컬럼을 읽고 쓰는지, 어떤 컬럼끼리 등치 조건으로 묶이는지 알아야 한다. SQL은 매퍼 XML 안(내장 소스)과 SQL 파일 양쪽에 있고, MyBatis가 전개한 SQL은 문법이 깨져 있을 수 있다. ADR-0004에 따라 오류를 허용하는 재귀 하강 파서를 직접 구현한다.

## 2. 범위
- 포함: Oracle·DB2 LUW의 SELECT/INSERT/UPDATE/DELETE/MERGE/CALL(및 TRUNCATE, LOCK TABLE, 익명 블록 안의 호출·DML) 파싱, 스크립트 분할, `sql` 내장 소스 소비, SQL 파일의 독립 DML 구문 처리, READS/WRITES/JOINS/CALLS 엣지 기록, 루틴 단위 확장 지점.
- 제외: DDL(F-007), 프로시저·함수·패키지·트리거 본문 분석(F-008), 뷰·시노님 이름 맞추기와 관계 추론(F-012, F-018), 문법 검증.
- 범위 밖 방언: MySQL·MariaDB, PostgreSQL, HSQL, MSSQL 등은 지원 대상이 아니다. 이런 SQL을 만나면 **죽지 않고, 틀린 사실을 만들지 않고, 경고를 쏟아내지 않는 것**까지만 목표로 한다(F-006-R18, F-007-R17).

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-006-R1 | 시스템은 SQL 텍스트를 줄·열 위치가 있는 토큰으로 나눠야 하며, 주석·힌트, 문자열(`''`, `q'[..]'`, `N'..'`), 따옴표 식별자, 바인드(`?`, `:name`, `#{..}`), `${..}` 잔재를 구분해야 한다. | 사용자 요청, ADR-0004 |
| F-006-R2 | 시스템은 SELECT, INSERT, UPDATE, DELETE, MERGE, CALL 구문을 식·테이블 참조·조인을 담은 구문 트리(AST)로 만들어야 한다. | PRD 목표 1, 사용자 요청 |
| F-006-R3 | 시스템은 구문이 참조한 테이블마다 구문 노드에서 `READS` 또는 `WRITES`(attrs.op) 엣지를 만들어야 한다. | 그래프 계약 3.4 |
| F-006-R4 | 컬럼의 소속 테이블이 확정되는 경우(별칭 한정, 스코프에 테이블 하나, 카탈로그상 유일), 시스템은 컬럼 `READS`/`WRITES` 엣지를 만들어야 한다. | 그래프 계약 3.4 |
| F-006-R5 | 시스템은 서로 다른 테이블 인스턴스의 컬럼끼리의 등치 조건을 테이블 인스턴스 쌍마다 하나의 `JOINS` 엣지(attrs.leftColumns, rightColumns, statement)로 기록해야 한다. (2026-10-11 문장 정리: "테이블 쌍마다"를 5절의 규칙대로 "테이블 인스턴스 쌍마다"로 고쳤다. 등치의 한쪽이 컬럼 하나만 감싼 형 변환·정리 함수인 경우는 F-006-R29가 정한다.) | 그래프 계약 3.4, PRD 목표 2 |
| F-006-R6 | 시스템은 CTE 이름, 서브쿼리·테이블 별칭, `DUAL`, `SYSIBM.SYSDUMMY1`, 함수 호출, 시퀀스, 주석·문자열 안의 글자를 테이블로 만들지 않아야 한다. | 사용자 요청 |
| F-006-R7 | 프로시저 호출 구문(`CALL`, `EXEC`, `{call ..}`, 익명 블록 안의 호출)을 만나면, 시스템은 statementType을 `CALL`로 하고 `CALLS` 엣지를 루틴 노드로 만들어야 한다. | 그래프 계약 3.4 |
| F-006-R8 | 만약 테이블 자리에 `__DYNAMIC__` 식별자가 있으면, 시스템은 `unresolved:sql-dynamic:<구문 ID>`로의 `UNRESOLVED` 엣지와 `DYNAMIC_SQL` 진단을 남겨야 한다. | 그래프 계약 4.1 |
| F-006-R9 | 시스템은 `sql` 내장 소스를 주인 노드 기준으로 분석해 같은 주인의 전개본 결과를 합치고 주인 노드에 `statementType`, `dialect`를 채워야 한다. | 그래프 계약 4.1 |
| F-006-R10 | 시스템은 SQL 파일을 구문 단위로 나누고 독립 DML 구문마다 `SQL_STATEMENT` 노드를 만들어야 하며, 루틴 단위(프로시저·함수·패키지·트리거·익명 블록)는 내부의 `;`에서 끊지 않고 확장 지점으로 넘겨야 한다. | 사용자 요청 |
| F-006-R11 | 만약 구문이 문법에 맞지 않거나 지원하지 않는 구문이면, 시스템은 예외로 멈추지 않고 건진 테이블·조인을 기록한 뒤 진단(`PARSE_FAILED`, `UNSUPPORTED_SYNTAX`)을 남겨야 한다. | AGENTS.md, ADR-0004 |
| F-006-R12 | 시스템은 방언(oracle, db2)을 옵션, 내장 소스 attrs, 파일 경로, 구문 특징 순으로 판정해 구문 노드의 `dialect`에 적어야 한다. (2026-10-11 고쳐 씀: `--dialect` 옵션은 구문 노드의 `dialect`로 찍지 않는다. 순서는 내장 소스 attrs·파일의 방언 → 구문 특징이다 — F-006-R37.) | 사용자 요청 |
| F-006-R13 | 시스템은 `JOINS` 엣지에, 조인한 양쪽 테이블 **인스턴스**의 컬럼 가운데 같은 구문의 조건(ON·WHERE, `(+)` 포함)에서 리터럴 또는 바인드와 `=`로 비교된 것을 `leftFixedColumns`/`rightFixedColumns`(List)로, 그 값을 `leftFixedValues`/`rightFixedValues`(List, `COL=값`, 바인드는 `COL=?`)로 남겨야 한다. 고정 컬럼이 없는 쪽에는 attrs를 넣지 않는다. | 독립 감사(U3-b), F-018-R9 |
| F-006-R14 | 인라인 뷰를 거친 UPDATE·DELETE·MERGE(`UPDATE (SELECT … FROM a JOIN b …) SET …`)에서 갱신되는 테이블 인스턴스에는, 시스템은 테이블 수준 `WRITES`만 만들고 테이블 수준 `READS`를 만들지 않아야 한다. 같은 구문에서 그 테이블을 다른 인스턴스로 다시 읽으면 `READS`를 만든다. | 독립 감사(U2), 그래프 계약 3.4 |
| F-006-R15 | SQL 스크립트 파일에서 값이 리터럴(과 시퀀스·함수 호출·상수 식)뿐이고 서브쿼리가 없는 INSERT가 한 파일의 같은 테이블에 10건 이상이면, 시스템은 그 구문들을 (파일, 테이블)마다 `SQL_STATEMENT` 노드 하나(ID는 그 테이블의 첫 INSERT 줄, attrs `statementType=INSERT`, `dataLoad=true`, `rowCount`, `sql`은 첫 구문)로 묶고 엣지는 테이블 `WRITES`와 컬럼 목록 합집합의 컬럼 `WRITES`만 만들어야 하며, 파일마다 `DATA_LOAD_GROUPED`(INFO) 진단 하나를 남겨야 한다. 묶은 구문의 토큰과 구문 트리는 쥐고 있지 않아야 한다. 매퍼·루틴·익명 블록 안의 INSERT와 서브쿼리·테이블을 읽는 INSERT는 묶지 않는다. | 공개 프로젝트 검증(B1) |
| F-006-R16 | 구문이 끝난 뒤 남은 토큰이 `SELECT`/`INSERT`/`UPDATE`/`DELETE`/`MERGE`/`WITH`/`CALL`로 시작하면, 시스템은 거기서 잘라 별도 구문으로 파싱해 같은 주인에 합쳐야 한다. 그 밖의 남은 토큰은 건너뛰되 쉼표 뒤를 FROM 목록으로 잇지 않아야 하며, 복구하며 건지는 테이블 이름은 `FROM`/`JOIN` 키워드 바로 뒤의 것만 인정해야 한다. | 공개 프로젝트 검증(B2) |
| F-006-R17 | 시스템은 테이블 이름 자리에서 예약어(`SELECT`, `FROM`, `WHERE`, `SET`, `VALUES`, `ON` 등)만 막고 `SAMPLE`, `LOG`, `MODEL`, `PARTITION` 같은 비예약 키워드는 이름으로 받아야 하며, 별칭 자리에서는 뒤 토큰이 진짜 절의 모양일 때만(`T SAMPLE (10)`, `T PARTITION (p)`, `T LOG ERRORS`) 절로 봐야 한다. | 공개 프로젝트 검증(B4) |
| F-006-R18 | 범위 밖 방언의 흔한 표기(MySQL 백틱 식별자·줄 처음 `#` 주석·`0x` 16진 리터럴·`LIMIT a, b`·`LIMIT n OFFSET m`·UPDATE/DELETE 끝의 `LIMIT`, PostgreSQL `expr::type`, MSSQL `IF EXISTS (..) DROP ..`)를 만나면, 시스템은 문제(problem)나 진단 없이 읽고 넘겨야 한다. | 공개 프로젝트 검증(방언 관용) |
| F-006-R19 | 조건식 자리에 `__DYNAMIC__`을 포함한 식별자가 오고 바로 뒤에 피연산자(바인드·리터럴)가 따르면, 시스템은 그 피연산자까지 불투명한 조건 하나로 삼키고 문제를 남기지 않아야 한다(구문 attrs.dynamic=true는 유지). | 공개 프로젝트 검증(B12) |
| F-006-R20 | 튜플 `NOT IN (서브쿼리)` 반조인이면, 시스템은 튜플의 컬럼 쌍을 `JOINS`로 만들지 않아야 한다. | 공개 프로젝트 검증(B13) |
| F-006-R21 | `DELETE 별칭 FROM 테이블 별칭 [JOIN ..]`, `UPDATE 별칭 SET .. FROM 테이블 별칭 ..`이면, 시스템은 앞의 이름을 FROM에서 그 별칭이 가리키는 테이블로 풀어 그 테이블에만 `WRITES`를 만들어야 한다. | 공개 프로젝트 검증(B24) |
| F-006-R22 | 종결자가 (거의) 없고 줄 시작에 구문 키워드가 여럿인 SQL 파일이면, 시스템은 줄 처음의 구문 키워드에서도 구문을 끊어야 한다. 정의나 DML로 보이는 줄이 있는데 테이블도 구문도 얻지 못한 SQL 파일에는 `PARSE_FAILED`(WARN) 진단("이 파일에서 읽은 것이 없음")을 남겨야 한다. | 공개 프로젝트 검증(B22) |
| F-006-R23 | SQL 파일(1패스와 구문 분석 패스 각각)과 내장 SQL(주인 구문마다)을 처리하기 시작할 때, 시스템은 그 파일의 프로젝트 기준 상대 경로(내장 SQL은 호스트 파일)를 `AnalysisContext.progress`로 알려야 한다. 힙이 모자라면 파이프라인이 이 값을 메모리 부족 안내에 싣는다(F-001-R39). | 공개 프로젝트 검증(큰 데이터 스크립트에서 메모리가 모자랐을 때 파일을 알 수 없었다), F-001-R39 |
| F-006-R24 | 시스템은 데이터 적재 묶기(F-006-R15)를 파일 단위로 판정해야 한다. **데이터 적재 파일**은 (a) 한 테이블의 리터럴 INSERT가 10건 이상인 묶음이 하나라도 생긴 파일이거나, (b) 구문이 리터럴 INSERT와 세션·트랜잭션 명령(COMMIT, SET 류), 조건 없는 `DELETE FROM T`·`TRUNCATE`뿐이고 리터럴 INSERT가 10건 이상인 파일이다. 적재 파일의 리터럴 INSERT는 건수와 상관없이 테이블별로 묶고(1건이어도 `dataLoad=true`, `rowCount=1`), 적재 파일의 조건 없는 `DELETE FROM T`·`TRUNCATE`(초기화 구문)의 구문 노드에는 `dataLoad=true`, `rowCount=0`을 붙여야 한다. 적재 파일이 아닌 파일(DDL·COMMENT·다른 DML과 섞인 소량의 INSERT)은 전처럼 구문마다 노드다. | 공개 프로젝트 재검증(N3: 묶이지 않은 스크립트 INSERT 17,734개가 CRUD 매트릭스 18,981행 중 17,735행) |
| F-006-R25 | 조건·절 사이(술어나 절이 끝난 자리: WHERE·ON·HAVING 조건 뒤, FROM 목록 뒤, SET 항목·VALUES 값·컬럼 목록의 이름 뒤, SET 항목 자리)에 `__DYNAMIC__`을 포함한 식별자가 홀로 있으면, 시스템은 그것을 불투명한 SQL 조각으로 건너뛰고 문제를 남기지 않아야 한다(구문 attrs.dynamic=true는 유지. F-006-R19를 보강한다). | 공개 프로젝트 재검증(N5: jeesite의 데이터 권한 필터 `… WHERE a.NM = #{nm} ${sqlMap.dsf} ORDER BY …`에서 `PARSE_FAILED`) |
| F-006-R26 | 시스템은 내부 표식 `__DYNAMIC__`을 사용자에게 보이는 글(진단 메시지, 미해결 `sql-dynamic` 노드의 이름과 엣지 `attrs.text`, `JOINS`의 고정 값)에 남기지 않아야 한다. 내장 소스 `attrs.dynamicParts`(치환 목록, 5절)가 있으면 n번째 `__DYNAMIC__`을 그 원문(`${…}`)으로 되돌리고, 없으면 `${…}`로 적는다. | 공개 프로젝트 재검증(N5) |
| F-006-R27 | 시스템은 줄 처음의 `#`을 줄 주석으로 읽는 MySQL 관용(F-006-R18)을 **MySQL로 판정한 SQL에서만** 적용해야 한다. 판정은 경로(내장 소스는 `attrs.dialect`)의 방언 이름, 옵션, 본문의 MySQL 특징(백틱 이름, `AUTO_INCREMENT`, `ENGINE=`) 순이다. 근거가 없으면 `#`은 주석이 아니며, 이름이 따르는 `#`(`#TMP`, `##G`)은 식별자의 시작이다(DB2·MSSQL의 임시 테이블 이름). | 대상 방언(DB2) 관점 점검 |
| F-006-R28 | 시스템은 SQL 스크립트 구문(과 데이터 적재 묶음, 루틴·패키지 노드)의 `dialect`를 옵션 → 경로의 방언 이름(Oracle·DB2일 때만) → 구문 특징 순으로만 넣어야 하고, 대상 외 방언으로 판정한 파일(경로에 다른 방언의 이름이 있거나 MySQL로 판정한 파일)에서는 구문 특징으로 Oracle·DB2를 추측해 넣지 않아야 한다(키를 비운다). `BIGINT`·`SMALLINT`만으로 DB2라고 판정하지 않는다. (2026-10-11 고쳐 씀: 맨 앞의 "옵션"은 빠졌다 — F-006-R37.) | 공개 프로젝트 재검증(N22: MySQL 덤프의 구문이 `DB2`, mysql·altibase·hsql 데이터 스크립트의 구문이 `ORACLE`) |
| F-006-R29 | 등치 조건(ON·WHERE의 `=`, `(+)` 포함, `IN (서브쿼리)`와 스칼라 서브쿼리 등치의 왼쪽)의 한쪽 또는 양쪽이 **단일 컬럼 하나만 감싼 형 변환·정리 함수**(중첩 포함. 컬럼 말고 다른 인자는 리터럴)이면, 시스템은 그 컬럼으로 `JOINS`를 만들고 엣지 attrs에 `wrapped=true`와 감싸인 쪽(`leftWrapped=true`/`rightWrapped=true`)을 남겨야 한다. 연산식, 값을 바꾸는 함수, 컬럼이 둘 이상 든 함수, 사용자 정의 함수로는 만들지 않아야 하고, 감싸이지 않은 조인에는 이 키들을 넣지 않아야 한다. 같은 조인을 감싸지 않은 형태로도 봤으면(같은 구문, 같은 주인의 다른 전개본) 한 엣지로 합치고 `wrapped`를 남기지 않아야 한다. | 공개 프로젝트 검증 3차(N17) |
| F-006-R30 | `IN`·비교(`=`, `= ANY`)의 서브쿼리가 집합 연산(UNION [ALL], INTERSECT, MINUS, EXCEPT)이면, 시스템은 각 분기의 선택 컬럼에 대해 `JOINS`를 만들어야 한다(튜플 IN이면 분기마다 위치별로). 튜플 `NOT IN (서브쿼리)`는 집합 연산이어도 만들지 않는다(F-006-R20). | 공개 프로젝트 검증 3차(N29) |
| F-006-R31 | 비한정 컬럼이 그 이름을 볼 수 있는 스코프의 선언된 테이블 어느 DDL에도 없으면, 시스템은 가장 안쪽 스코프에 실제 테이블이 하나뿐일 때 그 테이블의 컬럼으로 `READS`를 만들고(컬럼 노드는 참조로만 생겨 `declared=false`), 테이블이 여럿이면 엣지 없이 주인 구문마다 `UNKNOWN_COLUMN`(INFO) 진단 하나(컬럼 이름을 중복 없이, 후보 테이블과 함께)를 남겨야 한다. (2026-10-11 고쳐 씀: 전에는 "루틴 본문 안이면" 테이블이 하나여도 진단만 남겼다. 루틴 본문도 같은 규칙을 따른다 — F-008-R17.) 변수·매개변수, 바인드, 함수 이름, 의사 컬럼과 특수 이름, SELECT 별칭, 따옴표 이름, 동적 조각은 컬럼으로도 진단으로도 만들지 않아야 한다. | 공개 프로젝트 검증 3차(N18), AGENTS.md(해석하지 못한 참조를 버리지 않는다) |
| F-006-R32 | 시스템은 줄 끝을 LF, CRLF, CR 단독 셋 다 한 줄로 세야 한다(CRLF는 한 번). 토큰의 줄·열, 줄 처음 표시, 빈 줄 판정, `--`·`#` 줄 주석의 끝, 닫히지 않은 문자열·따옴표 이름의 끝, 줄 단독 `/`와 줄 끝 `@` 종결자, PROMPT 줄, 줄 명령, 스크립트 모양 판정(F-006-R22)이 줄 끝의 종류와 상관없이 같아야 하고, 구문 ID(`sql:stmt:<파일>:<줄>`, 루틴 구문 ID)의 줄이 같아야 한다. | 대상 방언 점검(다른 모듈은 CR 단독을 줄로 센다) |
| F-006-R33 | 식 자리(SELECT 목록, WHERE, ORDER BY, GROUP BY, HAVING, 함수 인자, SET 우변)에 비한정으로 쓴 이름이 파서가 절·수식어·함수 인자 키워드로도 쓰는 비예약 낱말(`LIMIT`, `OFFSET`, `WINDOW`, `ESCAPE`, `QUALIFY`, `BULK`, `LOOP`, `KEY`, `VALUE`, `TYPE`, `NAME`, `STATUS`, `COMMENT`, `DATE`, `LEVEL`, `CONTENT`, `NEW` 등)이면, 시스템은 뒤 토큰이 그 절의 모양일 때만 절로 읽고 그 밖에는 컬럼으로 읽어야 한다. `LEVEL`·`CONNECT_BY_ISLEAF`·`CONNECT_BY_ISCYCLE`은 그 이름이 보이는 스코프에 계층 쿼리(`CONNECT BY`)가 있을 때만 의사 컬럼이다. | 대상 방언 점검(한국 SI 스키마의 흔한 컬럼 이름) |
| F-006-R34 | 시스템은 5절 "대상 방언 구문 표"의 Oracle·DB2 구문을 문제(problem) 없이 파싱하고 그 구문의 테이블·컬럼·조인을 맞게 기록해야 하며, 구문의 키워드·타입 이름·결과 컬럼 정의(`JSON_TABLE`·`XMLTABLE`의 `COLUMNS` 절, `RETURNING 타입`, `ON OVERFLOW`, `STRIP`의 `LEADING`, `INCLUDE (컬럼 타입)`, `NTH_VALUE(..) FROM FIRST`, `RID(t)`의 테이블 지정자)를 컬럼이나 테이블로 만들지 않아야 한다. | 대상 방언 점검 |
| F-006-R35 | 시스템은 SQL 키워드 이름(절·연산자·구문 키워드: `INTERVAL`, `IGNORE`, `DISTINCT`, `JOIN` 등 `Keywords.EXPR_STOP`과 `NEVER_GUESSED_COLUMNS`)을 "DDL에 없는 컬럼"(F-006-R31)으로 추측하지도 `UNKNOWN_COLUMN`에 올리지도 않아야 하고, 파서가 해석하지 못한 구간(가볍지 않은 문제)을 남긴 구문에서는 DDL이 있는 테이블에 대해 DDL로 확정되는 컬럼만 기록해야 한다(추측한 컬럼뿐 아니라 별칭으로 한정한 이름과 SET·INSERT 컬럼도 DDL에 없으면 만들지 않는다). DDL에 그 이름의 컬럼이 있으면 키워드 이름이어도 컬럼이다. | 공개 프로젝트 재검증 3차(M3: `INTERVAL -6 MONTH`의 `INTERVAL` 컬럼, `UPDATE a JOIN b … SET`의 `JOIN` 컬럼) |
| F-006-R36 | 대상 외 방언(MySQL)의 다음 관용 표기를 만나면, 시스템은 문제 없이 읽고 틀린 테이블·컬럼을 만들지 않아야 한다: 사용자 변수 `@v`·`@@v`와 대입 `@v := 식`, `INSERT [LOW_PRIORITY\|DELAYED\|HIGH_PRIORITY] [IGNORE] INTO`, `REPLACE INTO`(INSERT로 기록), `INSERT INTO t SET a = …`, `… ON DUPLICATE KEY UPDATE a = VALUES(a)`, `UPDATE a JOIN b ON … SET`·`UPDATE a, b SET`(첫 테이블이 대상), `DELETE a[, b] FROM a JOIN b …`(앞의 이름들이 모두 대상), `INTERVAL 식 단위`, `x [NOT] REGEXP\|RLIKE 패턴`, 인덱스 힌트 `FORCE\|USE\|IGNORE INDEX (…)`, `STRAIGHT_JOIN`, 인접 문자열 리터럴. 문자열 안의 백슬래시 이스케이프(`'it\'s'`)는 MySQL로 판정한 입력에서만 받아야 한다(Oracle·DB2의 `ESCAPE '\'`와 충돌한다). | 공개 프로젝트 재검증 3차(M3: `INSERT IGNORE INTO t` → 테이블 `IGNORE`, `DELETE a, b FROM …` → 테이블 `A`) |
| F-006-R37 | 시스템은 구문 노드의 `dialect`를 (SQL 파일) 파일의 방언 — 경로의 방언 이름, 없으면 내용 특징(F-007-R24) —, (내장 소스) 생산자가 넘긴 `attrs.dialect` → 구문 특징이 확실할 때 → 없으면 키 없음 순으로 넣어야 하고, `--dialect` 옵션을 `dialect` 표기로 찍지 않아야 한다(옵션은 판정이 없을 때 루틴·블록의 문법을 고르는 데와 줄 처음 `#` 주석 판정에만 쓴다). 생산자가 대상 외 방언(`mysql` 등)이라고 알린 내장 소스에는 구문 특징(`SYSDATE` 등)으로 Oracle·DB2를 적지 않아야 한다. F-006-R12·R28의 "옵션이 먼저"를 대체한다. | 공개 프로젝트 재검증 3차(M12-c: `*_mysql.xml`의 구문에 `ORACLE`, `--dialect oracle`이면 db2·mysql 경로의 구문에도 `ORACLE`) |
| F-006-R38 | 조인 조건(ON·WHERE의 등치, `(+)` 포함)의 한쪽이 집합 연산(UNION 등)인 인라인 뷰·CTE의 컬럼이면, 시스템은 분기마다 선택 목록의 같은 자리에 있는 원본 테이블 컬럼으로 `JOINS`를 만들어야 한다(선택 항목이 단순 컬럼 참조인 분기만. `*`·식·리터럴이거나 컬럼 수가 모자란 분기는 건너뛴다). | 공개 프로젝트 재검증 3차(M13) |
| F-006-R39 | 시스템은 `PARSE_FAILED`의 메시지에 복구가 일어난 지점의 토큰과 기대한 것을 담아야 한다: 피연산자가 끝난 바로 뒤에 또 피연산자가 오면 "`<토큰>` 앞에 AND/OR 또는 연산자가 필요한 자리", 잘못 들어간 기호(`}`, `{`, `]`, `&` 등)면 "예상하지 못한 `<기호>`", 정말로 닫는 괄호 없이 끝났을 때만 "닫는 괄호가 없음"이다. | 공개 프로젝트 재검증 3차(원본 소스 결함 둘이 모두 "닫는 괄호가 없음"으로 나왔다) |
| F-006-R40 | DB가 제공하는 사전 뷰·카탈로그 테이블(Oracle의 `USER_`·`ALL_`·`DBA_` 사전 뷰와 `V$…`, `SYS.*`, DB2의 `SYSCAT.*`·`SYSIBM.*`·`SYSSTAT.*` 등)을 읽으면, 시스템은 테이블 노드를 만들되 `attrs.systemCatalog=true`를 붙여야 한다. 이름만 비슷한 프로젝트의 테이블(`USER_INFO`), DDL에 정의된 테이블, 사전 스키마 없이도 읽은 이름에는 붙이지 않는다. | 공개 프로젝트 재검증 3차("DDL에 없는 테이블"에 사전 뷰가 섞임) |
| F-006-R41 | 사전 스키마(`SYS`, `SYSCAT`, `SYSIBM`, `SYSSTAT`, `SYSIBMADM`, `SYSTOOLS`, `SYSPROC`, `SYSFUN`, `INFORMATION_SCHEMA`)로 한정한 테이블 참조면, 시스템은 테이블 이름과 상관없이 사전 뷰·카탈로그 테이블로 다뤄야 한다(F-006-R40의 `systemCatalog=true`): 이름이나 스키마를 따옴표·백틱으로 감쌌어도, 대소문자가 어떻든, 구문 일부를 해석하지 못해 구문 끝에서 건진 참조여도 같다. 한정자 없이도 읽은 이름(`TABLES`, `COLUMNS`)에는 전처럼 붙이지 않는다. | 공개 프로젝트 재검증 4차(jeesite `GenDataBaseDictDao.xml`의 ``information_schema.`TABLES` ``·`COLUMNS`가 일반 테이블로 남고 `TABLES`가 "의심"으로 나왔다) |
| F-006-R42 | 구문 특징으로 방언을 판정할 때(F-006-R12·R37), Oracle·DB2에 없는 MySQL 표기 — 괄호가 붙은 `SYSDATE()`, 따옴표 없는 수를 쓴 `INTERVAL [+\|-]n <단위>`, `ADDDATE(` — 가 하나라도 있으면, 시스템은 그 구문을 Oracle·DB2로 추측하지 않아야 한다(`dialect` 키 없음). Oracle의 `INTERVAL '6' MONTH`, 괄호 없는 `SYSDATE`, DB2의 `CURRENT DATE - 6 MONTHS`의 판정은 그대로여야 한다. | 공개 프로젝트 재검증 4차(재현 `my2`의 `ADDDATE(SYSDATE(), INTERVAL -6 MONTH)`에 `dialect=ORACLE`) |
## 4. 수용 기준

### F-006-AC1 (대상: F-006-R1)
- Given `SELECT /*+ INDEX(t) */ 'it''s', q'[a'b]', "My Col", :id, #{name}, ${col} FROM t -- 주석`
- When 렉서로 토큰화하면
- Then 주석과 힌트는 토큰에서 빠지고, 문자열 2개, 따옴표 식별자 1개, 바인드 2개, `__DYNAMIC__` 식별자 1개가 줄·열과 함께 나온다.

### F-006-AC2 (대상: F-006-R2)
- Given WITH, 집합 연산, 서브쿼리, ANSI JOIN, `(+)`, 계층 쿼리, 분석 함수, CASE를 쓰는 Oracle·DB2 구문 모음
- When 파싱하면
- Then 문제(problem) 없이 구문 트리가 만들어지고 구문 종류(statementType)가 기대와 같다.

### F-006-AC3 (대상: F-006-R3)
- Given `INSERT INTO a (x) SELECT y FROM b`, `UPDATE a SET x = 1`, `DELETE FROM a`, `MERGE INTO a USING b ON (...)`
- When 분석하면
- Then 대상 테이블은 WRITES(op=INSERT/UPDATE/DELETE/MERGE), 나머지는 READS로 나오고, `SELECT *`의 READS에는 `star=true`가 붙는다.

### F-006-AC4 (대상: F-006-R4)
- Given 카탈로그에 `A(ID, NAME)`, `B(ID, A_ID)`가 있고 `SELECT name, a_id FROM a, b`
- When 분석하면
- Then `A.NAME`, `B.A_ID` 컬럼 READS가 나오고, 카탈로그가 없으면 두 컬럼 엣지는 만들지 않는다.

### F-006-AC5 (대상: F-006-R5)
- Given ON, WHERE, `(+)`, USING, 상관 서브쿼리, `IN (SELECT col ...)`, 튜플 IN, 인라인 뷰·CTE를 거친 등치 조건
- When 분석하면
- Then 테이블 인스턴스 쌍마다 JOINS 엣지 하나에 대응 순서대로 컬럼이 담기고, 셀프 조인은 from=to이며, 값을 바꾸는 식(연산, `SUBSTR`·`||` 등)이 끼었거나 소속을 모르는 조건은 JOINS가 없다. (2026-10-11 문장 정리: 전에는 "식이 끼었으면 JOINS가 없다"였다. 컬럼 하나만 감싼 형 변환·정리 함수(`TRIM`, `UPPER`, `TO_CHAR` 등)는 F-006-R29에 따라 그 컬럼의 조인이고 `wrapped=true`가 붙는다. 같은 테이블의 같은 컬럼끼리만 묶인 조건은 남기지 않는다.)

### F-006-AC6 (대상: F-006-R6)
- Given CTE, 인라인 뷰 별칭, `FROM DUAL`, `SEQ.NEXTVAL`, `NEXT VALUE FOR SEQ`, `TABLE(f(..))`, 문자열 `'FROM X'`가 들어간 구문
- When 분석하면
- Then 이것들은 테이블 노드가 되지 않고, 시퀀스는 `db:sequence:` 노드로의 READS(attrs.sequence=true)가 된다.

### F-006-AC7 (대상: F-006-R7)
- Given `CALL P(?)`, `{call PKG.PROC(?, ?)}`, `{? = call F(?)}`, `BEGIN PKG.PROC(1); END;`
- When 분석하면
- Then statementType이 `CALL`이고 `db:routine:P`, `db:routine:PKG.PROC`, `db:routine:F`로 CALLS 엣지가 생긴다.

### F-006-AC8 (대상: F-006-R8)
- Given 내장 소스 `SELECT * FROM __DYNAMIC__ WHERE id = ? ORDER BY __DYNAMIC__`
- When 분석하면
- Then 주인 구문에서 `unresolved:sql-dynamic:<구문 ID>`로 UNRESOLVED READS 엣지가 하나 생기고 `DYNAMIC_SQL` 진단이 남으며 주인 attrs.dynamic=true다.

### F-006-AC9 (대상: F-006-R9)
- Given 같은 주인의 전개본 둘(`... FROM a WHERE AND x = ?`, `... FROM a, b WHERE a.id = b.a_id`)
- When 분석하면
- Then 주인 노드에 READS A, READS B, JOINS A→B가 모이고 `statementType=SELECT`가 붙는다.

### F-006-AC10 (대상: F-006-R10)
- Given `;`, 줄 단독 `/`, `@` 종결자, 빈 줄이 섞인 SQL 파일과 `CREATE PROCEDURE ... END;` 단위
- When 분석하면
- Then DML 구문마다 `sql:stmt:<파일>:<줄>` 노드와 FILE→CONTAINS가 생기고, 프로시저는 한 단위로 잡혀 `DB_ROUTINE` 노드가 선언되며 "본문 미분석" INFO 진단이 남는다. (2026-10-11 문장 정리: "본문 미분석" 진단은 헤더만 읽는 핸들러 `HeaderOnlyRoutineHandler`를 끼웠을 때의 것이다. F-008 이후 기본 핸들러는 본문을 분석하며 이 진단을 남기지 않는다.)

### F-006-AC11 (대상: F-006-R11)
- Given 괄호가 닫히지 않은 SELECT, 임의 위치에서 자른 SQL, 괄호를 지운 SQL
- When 분석하면
- Then 예외 없이 끝나고, FROM/JOIN/INTO/UPDATE 대상 테이블이 READS/WRITES로 남으며 `PARSE_FAILED` 진단이 기록된다.

### F-006-AC12 (대상: F-006-R12)
- Given 옵션 dialect=AUTO, 경로 `db/db2/x.sql`의 구문, `NVL`·`(+)`를 쓰는 내장 소스, attrs.dialect=db2인 내장 소스
- When 분석하면
- Then 구문 노드의 dialect가 각각 `DB2`, `ORACLE`, `DB2`다.

### F-006-AC13 (대상: F-006-R13)
- Given `… FROM TB_ORDER O, TB_CODE C WHERE O.ORDER_STATUS = C.CODE(+) AND C.GROUP_CD(+) = 'ORDER_STATUS'`, ON 절에 `C.GROUP_CD = 'ORDER_STATUS' AND C.CODE = H.ORDER_STATUS`가 있고 WHERE에 `H.ORDER_ID = #{orderId}`가 있는 구문, 상관 서브쿼리 안에 상수 조건이 있는 구문, `<>`·범위·OR·NOT·`IN (여럿)`·`IN (바인드)`·`= NULL` 조건이 섞인 구문, 같은 주인의 전개본 가운데 일부에만 상수 조건이 있는 경우, 루틴 본문에서 매개변수와 비교한 구문
- When 분석하면
- Then `JOINS`에 `rightFixedColumns=[GROUP_CD]`, `rightFixedValues=[GROUP_CD=ORDER_STATUS]`(조인 방향에 따라 `left…`)가 붙고, 바인드와 루틴 변수는 `COL=?`다. `IN (리터럴 하나)`와 숫자 리터럴은 고정이고 나머지 조건은 고정이 아니며, 상수 조건이 없는 쪽에는 attrs가 없다. 전개본을 합치면 모든 전개본에 있는 고정 컬럼만 남는다. 샘플의 TB_CODE 조인 3건(`OrderMapper.selectOrderList`, `orderDao.selectOrderHistory`, `orderDao.selectPaymentsByOrder`)에 `GROUP_CD` 고정이 남는다.

### F-006-AC14 (대상: F-006-R14)
- Given `UPDATE (SELECT D.DELIVERY_STATUS, D.SHIP_DT, O.ORDER_STATUS FROM TB_DELIVERY D, TB_ORDER O WHERE D.ORDER_ID = O.ORDER_ID AND …) SET DELIVERY_STATUS = 'SHIP', SHIP_DT = SYSDATE`(샘플 `orderDao.xml:113`), `DELETE FROM (SELECT * FROM ord WHERE …)`, 인라인 뷰의 서브쿼리에서 대상 테이블을 다시 읽는 UPDATE
- When 분석하면
- Then 갱신되는 `TB_DELIVERY`에는 `WRITES`(op=UPDATE)만 있고 테이블 수준 `READS`가 없으며, 조건으로만 읽는 `TB_ORDER`는 `READS`다. 대상 테이블의 컬럼 `READS`와 `JOINS`는 그대로 있다. 서브쿼리에서 같은 테이블을 다시 읽으면 테이블 수준 `READS`가 남는다.

### F-006-AC15 (대상: F-006-R15)
- Given SQL 파일에 `INSERT INTO TB_CODE (CD, NM) VALUES ('A', '가');` 꼴의 리터럴 INSERT가 TB_CODE에 12건, TB_USR에 2건 있고(컬럼 목록이 구문마다 다르고 값에 `SEQ.NEXTVAL`, `UPPER('d')`, `1 + 2`가 섞임), 다중 행 `VALUES (..), (..)`, `INSERT ALL INTO .. VALUES .. SELECT * FROM DUAL`, 컬럼 목록 없는 INSERT, `INSERT .. SELECT 1, 'a' FROM DUAL UNION ALL ..`, 서브쿼리가 있는 `INSERT .. SELECT`·`VALUES ((SELECT ..))`, 깨진 INSERT, 사용자 정의 함수를 부르는 INSERT, 매퍼·루틴·익명 블록 안의 INSERT, 20만 건짜리 파일
- When 분석하면
- Then TB_CODE의 12건은 `sql:stmt:<파일>:<첫 INSERT 줄>` 노드 하나(`dataLoad=true`, `rowCount=12`, `sql`은 첫 구문)가 되고 엣지는 테이블 `WRITES` 하나와 컬럼 합집합의 `WRITES`뿐이며(시퀀스 `READS` 없음), 파일에 `DATA_LOAD_GROUPED`(INFO) "데이터 적재 구문 12건을 테이블별 1개로 묶음"이 하나 남는다. 9건 이하인 테이블의 INSERT, 서브쿼리·테이블을 읽는 INSERT, 깨진 INSERT, 매퍼·루틴·블록 안의 INSERT는 전과 같이 구문마다 노드다. `rowCount`는 행 수다(다중 행 VALUES와 INSERT ALL은 행마다 센다). 사용자 정의 함수를 부른 적재 구문은 묶음 노드에서 `CALLS`가 나간다. 20만 건짜리 파일은 구문 노드 하나, 엣지 6개로 끝난다.

### F-006-AC16 (대상: F-006-R16)
- Given `SELECT MBR_ID AS ID, MBR_NM AS NAME FROM TB_GNR WHERE MBR_ID = ? SELECT ENT_ID AS ID, CO_NM AS NAME, BIZ_NO AS NO FROM TB_ENT WHERE ENT_ID = ?`(MyBatis의 배타적 `<if>`마다 완결된 문장이 든 구문), 종결자 없이 이어진 UPDATE 셋, DELETE·INSERT·MERGE·WITH·CALL이 이어진 조각, 둘째 SELECT 키워드가 빠진 조각, 해석하지 못한 꼬리의 `FROM TB_B B, COL_X, COL_Y JOIN TB_C C`
- When 파싱하고 분석하면
- Then 구문은 `Statement.Sequence`로 나뉘어 문제 없이 파싱되고 테이블은 TB_GNR·TB_ENT뿐이다(CO_NM, BIZ_NO가 테이블이 되지 않는다). UPDATE 셋은 셋 다 `WRITES`로 잡힌다. 구문 안의 키워드(`INSERT .. SELECT`, `UNION ALL SELECT`, `FOR UPDATE`, `WITH UR`, MERGE의 `UPDATE`/`DELETE WHERE`)에서는 끊지 않는다. 쉼표 뒤의 이름은 테이블이 되지 않고, 꼬리에서는 TB_B, TB_C만 건진다.

### F-006-AC17 (대상: F-006-R17)
- Given 파서가 절의 시작으로 쓰는 비예약 키워드 전부(`SAMPLE`, `LOG`, `MODEL`, `PARTITION`, `INCLUDE`, `KEEP`, `ROWS`, `ONLY`, `SKIP`, `WAIT`, `RETURN`, `CALL`, `DO`, `PIVOT`, `LOCK`, `DEFAULT` 등 `Keywords.ALIAS_STOP_EXTRA`)와 흔한 이름(`KEY`, `VALUE`, `TYPE`, `LEVEL` 등)을 테이블 이름·테이블 별칭·컬럼 이름으로 쓴 SELECT/JOIN/INSERT/UPDATE/DELETE/MERGE, 진짜 절(`T SAMPLE (10)`, `T PARTITION (P1) X`, `T PIVOT (..) P`, `.. LOG ERRORS ..`, `INSERT INTO T DEFAULT VALUES`, `FOR UPDATE SKIP LOCKED`), 테이블이 빠진 구문(`FROM WHERE ..`, `UPDATE SET ..`)
- When 파싱하고 분석하면
- Then 키워드 이름의 테이블·별칭·컬럼은 문제 없이 `READS`/`WRITES`와 컬럼 엣지가 되고, 진짜 절은 절로 읽히며, 테이블이 빠진 구문은 문제로 남고 예약어가 테이블이 되지 않는다.

### F-006-AC18 (대상: F-006-R18)
- Given ``SELECT `id`, `key` FROM `sys_user` ``, `A.DT::date = ?::date`, `(A.X || A.Y)::varchar`, `?::varchar[]`, `A.TS::timestamp without time zone`, `LIMIT ? OFFSET ?`, `LIMIT #{offset}, #{limit}`, `UPDATE .. LIMIT 1`, 줄 처음의 `# 주석`, `0x89504E..` 리터럴
- When 렉싱·파싱하면
- Then 문제 없이 읽힌다. 백틱 식별자는 따옴표 식별자 토큰이고 이름은 대문자로 정규화한다(`SYS_USER`, 컬럼 `KEY`). `::`는 기호 하나이고 캐스트가 낀 식은 `CAST(..)`와 같게 다룬다.

### F-006-AC19 (대상: F-006-R19)
- Given MyBatis Generator의 `Example_Where_Clause`가 전개된 `WHERE ( __DYNAMIC__ ? )`, `( __DYNAMIC__ and __DYNAMIC__ ? and __DYNAMIC__ ? and ? and __DYNAMIC__ ( ? , ? ) )`, 리터럴이 따르는 `__DYNAMIC__ 'A'`
- When 파싱하고 분석하면
- Then 문제가 없고 테이블은 그대로 기록되며 구문 attrs.dynamic=true다. 테이블 자리가 아니므로 미해결 엣지는 없다.

### F-006-AC20 (대상: F-006-R20)
- Given `(A.BBS_ID, F.USR_ID) NOT IN (SELECT BBS_ID, TRGT_ID FROM TB_BBS_USE)`(왼쪽 튜플이 두 테이블에 걸침), `(A.K1, A.K2) NOT IN (SELECT ..)`, 긍정 튜플 `IN`, 단일 컬럼 `NOT IN (SELECT ..)`, `NOT EXISTS`의 상관 조건, 스칼라 서브쿼리 등치 `A.AUTH_CD = (SELECT C.AUTH_CD FROM TB_USR_AUTH C WHERE ..)`
- When 분석하면
- Then 튜플 `NOT IN`에서는 `JOINS`가 없고(서브쿼리 안쪽 테이블끼리의 조인은 그대로), 긍정 `IN`/`EXISTS`와 단일 컬럼 `NOT IN`, `NOT EXISTS`의 상관 조건은 전과 같이 `JOINS`다. 스칼라 서브쿼리의 등치는 서브쿼리 테이블과 바깥 테이블만 잇고 바깥의 서로 다른 두 테이블을 잇지 않는다.

### F-006-AC21 (대상: F-006-R21)
- Given `DELETE RNS FROM TB_RECIPE_STEP RNS WHERE RNS.RECIPE_ID = ?`, `DELETE D FROM TB_DTL D INNER JOIN TB_MST M ON D.MST_ID = M.MST_ID WHERE ..`, `UPDATE D SET D.ST = M.ST FROM TB_DTL D JOIN TB_MST M ON ..`
- When 분석하면
- Then 별칭(`RNS`, `D`)은 테이블이 되지 않고 그것이 가리키는 테이블에만 `WRITES`가 생기며, 함께 조인한 테이블은 `READS`, ON 조건은 `JOINS`다. 보통의 `DELETE FROM T A`, `DELETE T WHERE ..`는 전과 같다.

### F-006-AC22 (대상: F-006-R22)
- Given 세미콜론 없이 줄마다 구문 하나인 스크립트(`DROP TABLE IF EXISTS SAMPLE` / `CREATE MEMORY TABLE SAMPLE(..)` / `SET SCHEMA PUBLIC` / `INSERT INTO SAMPLE VALUES(..)`), 여러 줄에 걸친 구문이 있는 보통 스크립트, 모르는 CREATE 문만 있는 파일, DROP·COMMIT만 있는 파일, 주석뿐인 파일
- When 분석하면
- Then 줄바꿈으로만 나눈 스크립트는 줄 처음의 구문 키워드에서 나뉘어 테이블과 구문이 진단 없이 나오고, 보통 스크립트는 줄에서 끊기지 않는다. 읽을 것이 있어 보이는데 아무것도 얻지 못한 파일에만 `PARSE_FAILED`(WARN) "이 파일에서 읽은 것이 없음"이 하나 남는다.

### F-006-AC23 (대상: F-006-R23)
- Given SQL 파일 둘(`db/a_schema.sql`, `db/b_query.sql`)과 매퍼 XML에서 온 내장 SQL 하나
- When 분석하면
- Then `AnalysisContext.progress`에 두 파일의 경로가 1패스에서 한 번, 구문 분석 패스에서 한 번씩 차례로 들어오고, 내장 SQL은 호스트 파일의 경로로 들어온다. 끝난 뒤 `currentActivity()`는 마지막으로 처리한 것이다.

### F-006-AC24 (대상: F-006-R24)
- Given (가) `TB_B` 3건, `TB_A` 12건, `TB_C` 1건의 리터럴 INSERT와 `UPDATE`, 조건 있는 `DELETE`, `DELETE FROM TB_OLD;`, `COMMIT`이 있는 파일, (나) `SET DEFINE OFF`, `DELETE FROM T1;`, `TRUNCATE TABLE T2;`와 `T1` 4건·`T2` 4건·`T3` 3건의 리터럴 INSERT, `COMMIT`뿐인 파일과 같은 꼴에 INSERT가 9건인 파일, (다) `CREATE TABLE`·`COMMENT ON`과 `T1` 7건·`T2` 5건이 섞인 파일, INSERT 12건(테이블마다 6건)에 `SELECT` 하나가 낀 파일
- When 분석하면
- Then (가) `TB_B`·`TB_A`·`TB_C`가 테이블마다 묶음 하나(`dataLoad=true`, `rowCount` 3·12·1)이고 `UPDATE`와 조건 있는 `DELETE`는 보통의 구문, `DELETE FROM TB_OLD`는 `dataLoad=true`·`rowCount=0`·`statementType=DELETE`다. `DATA_LOAD_GROUPED`가 한 건이고 메시지에 "INSERT 16건", "3개", "10건 미만인 테이블 2개", "초기화 구문 … 1건"이 있다. (나) 모든 구문 노드가 `dataLoad=true`다(묶음 셋, 초기화 구문 둘). INSERT가 9건이면 전처럼 구문마다 노드이고 `dataLoad`가 없다. (다) 둘 다 적재 파일이 아니라 구문마다 노드이고 `dataLoad`와 진단이 없다(샘플 `04_comments_and_codes.sql`의 TB_CODE 7건이 이 경우다).

### F-006-AC25 (대상: F-006-R25)
- Given `SELECT a.ID FROM TB_A a WHERE a.NM = ? __DYNAMIC__ ORDER BY a.ID`, 조건 사이에 여러 번 낀 것(`… = ? __DYNAMIC__ AND a.Y = 2 __DYNAMIC__ GROUP BY a.ID __DYNAMIC__ ORDER BY …`), FROM 목록 뒤(`FROM TB_A a __DYNAMIC__ WHERE …`), ON 조건 뒤, 서브쿼리 안(`(SELECT … WHERE a.DEL = 0 __DYNAMIC__) x`), `UPDATE TB_A SET A = 1 __DYNAMIC__ WHERE …`, `SET A = 1, __DYNAMIC__ WHERE …`, `SET __DYNAMIC__ WHERE …`, `VALUES (? __DYNAMIC__)`, 컬럼 목록 `(A __DYNAMIC__)`
- When 파싱하고 분석하면
- Then 문제(problem)가 없고 `PARSE_FAILED`가 없다. 테이블·컬럼·조인은 그 조각이 없을 때와 같고 구문 attrs.dynamic=true다. 테이블 자리가 아니므로 미해결 엣지는 없다. 테이블 자리(`FROM __DYNAMIC__`), 선택 목록·정렬 항목 자리의 `__DYNAMIC__`은 전과 같다.

### F-006-AC26 (대상: F-006-R26)
- Given `attrs.dynamicParts`가 있는 내장 소스(`UPDATE __DYNAMIC__ SET …` + `16:${businessTable}`, `SELECT * FROM TB_LOG___DYNAMIC__ T, __DYNAMIC__ U …` + 두 건, 위치를 보존한 본문에서 공백으로 채운 자리), 치환 목록이 없는 내장 소스, 목록의 건수가 본문과 맞지 않는 내장 소스, 구문이 통째로 치환이라 종류를 모르는 내장 소스(`__DYNAMIC__ TB_A SET X = 1`), 문자열 리터럴 안의 치환(`A.NM = '__DYNAMIC__'`)
- When 분석하면
- Then 미해결 `sql-dynamic` 노드의 이름·엣지 `attrs.text`·`DYNAMIC_SQL` 메시지에 `${businessTable}`, `TB_LOG_${ym}` 같은 원문이 보이고 `JOINS`의 고정 값도 `NM=${type}`이다. 목록이 없거나 맞지 않으면 `${…}`(`TB_LOG_${…}`)로 보이고, "지원하지 않는 구문" 메시지의 키워드 자리도 `${…}`다. 어느 진단 메시지, 노드 이름, 엣지 attrs에도 `__DYNAMIC__`이 없다. 위치를 보존한 내장 소스의 컬럼 엣지 위치는 치환 목록이 있어도 같다.

### F-006-AC27 (대상: F-006-R27)
- Given `INSERT INTO SESSION.T_OUT (A)` / `SELECT S.A FROM` / `#TMP_SRC S` / `WHERE S.A > 0;`을 경로 `db/work.sql`·`db/db2/work.sql`·`db/work_oracle.sql`과 `--dialect db2`로, `# 주석` 줄이 낀 `CREATE TABLE`을 경로 `db/mysql/schema.sql`·`db/schema_mariadb.sql`(`--dialect oracle`과 함께)·`mybatis.dialectVariant=mysql`·백틱과 `ENGINE=`이 있는 본문·근거 없는 `db/schema.sql`로, 내장 소스를 `attrs.dialect`=`mysql`·`db2`·없음으로
- When 렉싱하고 분석하면
- Then MySQL이라는 근거가 없으면 `#TMP_SRC`가 식별자 하나이고 `db:table:#TMP_SRC`의 `READS`가 생기며 진단이 없다. MySQL로 판정한 파일·내장 소스에서만 줄 처음의 `#`부터 줄 끝까지가 주석이다. 근거 없는 파일의 `# 주석` 줄은 주석으로 지워지지 않는다. `#{..}` 바인드, 이름 안의 `#`(`col#1`), 홀로 있는 `#`은 전과 같다.

### F-006-AC28 (대상: F-006-R28)
- Given `bigint`·`smallint`를 쓰는 덤프를 경로 `db/mysql/dump.sql`·`db/dump_mariadb.sql`·`db/hsql/dump.sql`·`db/dump.sql`로, 백틱과 `ENGINE=`이 있는 `db/dump.sql`, `SYSDATE`를 쓰는 리터럴 INSERT 12건을 `script/dml/altibase/data.sql`·`script/dml/data_mysql.sql`·`script/dml/tibero/data.sql`·`db/data_oracle.sql`·`db/db2/data.sql`·`db/data.sql`로, `--dialect db2`와 함께, `CURRENT TIMESTAMP`를 쓰는 구문, `CAST(B AS BIGINT)`만 있는 구문
- When 분석하면
- Then 대상 외 방언의 파일(경로의 방언 이름, MySQL 특징)과 `BIGINT`뿐인 구문의 노드에는 `dialect`가 없다(전에는 `DB2`·`ORACLE`). `--dialect db2`면 모두 `DB2`, 경로에 `oracle`·`db2`가 있으면 그 방언, 방언 이름이 없으면 구문 특징(`SYSDATE`→`ORACLE`, `CURRENT TIMESTAMP`→`DB2`)이다.

### F-006-AC29 (대상: F-006-R29)
- Given `… FROM TB_LOG a LEFT OUTER JOIN TB_CODE c ON TRIM(a.SE_CODE) = c.CODE WHERE c.CODE_ID = 'COM033'`, `LTRIM`·`RTRIM`·`UPPER`·`LOWER`·`TO_CHAR(x[, 'fmt'])`·`TO_NUMBER`·`CAST(x AS …)`·`x::type`·`NVL(x, 리터럴)`·`COALESCE(x, 리터럴)`·DB2 `CHAR`/`VARCHAR`/`INTEGER`/`INT`/`DECIMAL(x, p, s)`/`BIGINT`·`TRIM(BOTH ' ' FROM x)`·중첩(`TRIM(UPPER(x))`)으로 감싼 조건, 오른쪽·양쪽이 감싸인 조건, `TRIM(a.X) = c.CODE(+)`, `TRIM(a.X) IN (SELECT c.CODE …)`, `UPPER(a.X) = (SELECT c.CODE …)`, 그리고 `a.X || a.Y = …`·`a.X + 1 = …`·`SUBSTR`·`REPLACE`·`DECODE`·`LPAD`·`CONCAT(a.X, a.Y)`·`NVL(a.X, a.Y)`·`NVL(a.X, #{v})`·`TRIM(a.X || 'A')`·`MY_FUNC(a.X)`·`PKG.TRIM(a.X)`로 감싼 조건, 같은 조인을 감싼 형태와 안 감싼 형태로 둘 다 쓴 구문·전개본, 여러 컬럼 조인에서 한 쌍만 감싼 구문
- When 분석하면
- Then 형 변환·정리 함수로 감싼 컬럼은 그 컬럼의 `JOINS`가 되고 `wrapped=true`와 감싸인 쪽의 `leftWrapped`/`rightWrapped`만 붙는다(고정 컬럼 attrs는 그대로). 값을 바꾸는 식·함수와 사용자 함수로는 `JOINS`가 없다. 보통 조인에는 세 키가 없고, 감싼 형태와 안 감싼 형태를 둘 다 본 조인은 엣지 하나에 세 키가 없다. 모든 근거가 감싼 것이면 좌우가 뒤집힌 전개본을 합쳐도 같은 쪽에 남는다. 여러 컬럼 조인은 한 쌍이라도 (그 쌍의 모든 근거가) 감싸였으면 `wrapped=true`다.

### F-006-AC30 (대상: F-006-R30)
- Given `A.X IN (SELECT B.X FROM TB_B B UNION [ALL] SELECT C.Y FROM TB_C C)`, 분기 셋, 괄호로 묶은 분기, `INTERSECT`·`MINUS`·`EXCEPT`, 튜플 `(A.K1, A.K2) IN (… UNION …)`, `A.X = (… UNION …)`, `A.X = ANY (… UNION …)`, 단일 컬럼 `NOT IN (… UNION …)`, 한 분기의 선택 항목이 리터럴·식이거나 컬럼 수가 다른 경우, 분기 안쪽에 조인이 있는 경우, 튜플 `NOT IN (… UNION …)`, 집합 연산인 인라인 뷰·CTE
- When 분석하면
- Then 분기마다 `TB_A(X)=TB_B(X)`, `TB_A(X)=TB_C(Y)`처럼 `JOINS`가 하나씩 생기고(튜플이면 위치별 컬럼 쌍), 선택 항목이 컬럼이 아니거나 컬럼 수가 맞지 않는 분기만 빠진다. 분기 안쪽의 조인은 그대로다. 튜플 `NOT IN`은 만들지 않고, 인라인 뷰·CTE가 집합 연산이면 전과 같이 원본으로 추적하지 않는다.

### F-006-AC31 (대상: F-006-R31)
- Given DDL에 `TB_EMP(EMP_ID, NM, DEPT_ID)`, `TB_DEPT(DEPT_ID, DEPT_NM)`가 있고 `SELECT emp_id AS id, nm FROM TB_EMP WHERE sub_dn = #{dn}`, `UPDATE TB_EMP SET pw = #{pw} WHERE emp_idd = #{id}`, 두 테이블을 조인하고 `no_col`·`other_col`을 비한정으로 쓴 구문(전개본 둘), 서브쿼리 안의 비한정 `no_col`, 바깥 스코프에만 있는 컬럼을 쓴 상관 서브쿼리, `SELECT *` 인라인 뷰(페이징 포장)를 거친 `no_col`, 자기 조인, DDL이 없는 테이블이 낀 스코프, 의사 컬럼·`CURRENT DATE`·시퀀스·SELECT 별칭(GROUP BY·HAVING·ORDER BY)·따옴표 이름·`__DYNAMIC__`·함수 인자 자리의 자료형과 날짜 단위(`CONVERT(FLOAT, …)`, `DATEADD(DAY, …)`, `TRUNC(d, MONTH)`)·MySQL `SQL_CALC_FOUND_ROWS`·파서가 큰 문제를 남긴 구문(`SELECT TOP 10 …`), 컬럼 목록이 분명한(또는 동적인) 인라인 뷰, 뷰가 낀 스코프, `<choose>` 조합이 만든 어긋난 짝의 전개본, 루틴 본문과 SQL 파일의 구문
- When 분석하면
- Then 테이블이 하나뿐인 구문은 `TB_EMP.SUB_DN`, `TB_EMP.EMP_IDD`로 컬럼 `READS`가 생기고(`SET pw`의 `WRITES TB_EMP.PW`는 전과 같다) 그 컬럼 노드는 `declared=false`이며 진단은 없다. 테이블이 여럿이면 컬럼 엣지 없이 주인 구문에 `UNKNOWN_COLUMN`(INFO) 1건이 남고 메시지에 `NO_COL, OTHER_COL`(한 번씩)과 후보 테이블 `TB_EMP, TB_DEPT`, 주인 구문 ID가 있다(전개본이 여럿이어도 1건). 서브쿼리 안쪽 스코프의 테이블이 하나면 그 테이블의 컬럼이고, 바깥 스코프에 카탈로그상 그 컬럼이 있으면 전과 같이 바깥 것이다. DDL이 없는 테이블이 낀 스코프는 전의 규칙을 따르고 진단이 없다. 제외 대상은 컬럼 참조도 진단도 만들지 않는다. 같은 주인의 다른 전개본이 읽는 테이블의 DDL에 그 이름이 있으면 어긋난 짝의 참조·진단을 만들지 않는다. 루틴 본문도 같은 규칙이다(테이블이 하나면 `declared=false` 참조, 여럿이면 구문마다 진단 1건. 2026-10-11 고쳐 씀: 전에는 루틴 본문이면 테이블이 하나여도 진단만 남겼다 — F-008-R17). 변수·매개변수는 컬럼도 메시지의 이름도 아니다. SQL 파일의 구문도 구문마다 1건이다. `samples/sample-shop`의 그래프는 수정 전후가 같다.

### F-006-AC32 (대상: F-006-R32)
- Given 같은 내용을 LF, CRLF, CR 단독, 줄마다 섞은 줄 끝으로 쓴 SQL(여러 줄 주석과 `--` 주석, 빈 줄, 줄 단독 `/`, PROMPT 줄, `SET DEFINE OFF`, 프로시저, DB2의 줄 끝 `@`, 줄바꿈으로만 나눈 스크립트), CR 단독으로 끝난 줄의 닫히지 않은 따옴표 이름·`#{..`, MySQL로 판정한 SQL의 `#` 주석, SQL 파일과 프로시저 파일
- When 렉싱하고 나누고 분석하면
- Then 토큰의 줄·열·줄 처음 표시·빈 줄 표시와 주석의 범위가 넷 다 같고, 단위의 종류와 시작 줄이 같으며, 구문 노드가 `sql:stmt:db/query.sql:2`·`:6`·`:9`, `sql:stmt:SP_LINES:5`·`:10`으로 같고 위치와 `sql`이 같다. 진단은 없다.

### F-006-AC33 (대상: F-006-R33)
- Given 비예약 키워드 185개(`LIMIT`, `OFFSET`, `WINDOW`, `ESCAPE`, `ROWS`, `ONLY`, `SAMPLE`, `LOG`, `KEY`, `VALUE`, `TYPE`, `NAME`, `STATUS`, `COMMENT`, `DATE`, `TIMESTAMP`, `YEAR`, `MONTH`, `DAY`, `USER`, `SIZE`, `LEVEL`, `MODE`, `ACTION`, `POSITION`, `RESULT`, `QUALIFY`, `BULK`, `LOOP`, 기간 단위, 분석 함수·잠금·피벗 절의 낱말, 함수 인자 키워드, 특수 레지스터 이름, 스크립트 명령의 첫 낱말 등)를 컬럼 이름으로 쓴 구문 35가지(SELECT 목록, 별칭·`DISTINCT`·함수 인자·`CASE` 안, WHERE의 `=`·비교·`IS NULL`·`IN`·`LIKE`·`BETWEEN`·괄호·우변·서브쿼리, DELETE, ORDER BY, GROUP BY·HAVING, INSERT 컬럼 목록, UPDATE SET, 별칭 한정)와 조인·MERGE, 진짜 절(`LIMIT 10`, `LIMIT ? OFFSET ?`, `OFFSET 10 ROWS FETCH NEXT ..`, `WINDOW w AS (..)`와 `OVER w`, `LIKE .. ESCAPE '\'`, `BULK COLLECT INTO`, `QUALIFY 조건`), 피연산자가 빠진 깨진 구문(`WHERE ID = LIMIT 10`), `LEVEL`을 쓴 계층 쿼리와 보통 쿼리
- When DDL에 그 컬럼이 있는 카탈로그와 빈 카탈로그로 파싱하고 분석하면
- Then 문제(problem) 없이 파싱되고 카탈로그가 있으면 그 컬럼의 읽기·쓰기가 정확히 나오며, 카탈로그가 없어도 테이블은 그대로이고 키워드가 테이블이 되지 않는다(의사 컬럼·특수 이름이 아니면 하나뿐인 테이블의 컬럼이 된다). 진짜 절은 절로 읽히고, 깨진 구문의 `LIMIT`·`OFFSET`은 컬럼이 되지 않는다. `LEVEL`은 `CONNECT BY`가 있는 구문(안쪽 서브쿼리 포함)에서는 의사 컬럼이고 없는 구문에서는 DDL을 모르는 하나뿐인 테이블의 컬럼이며, DDL이 있는 테이블에 그 컬럼이 없으면 추측하지 않는다.

### F-006-AC34 (대상: F-006-R34)
- Given 5절 "대상 방언 구문 표"의 Oracle 구문 47가지와 DB2 구문 45가지(구문마다 SQL 하나)
- When 빈 카탈로그로 파싱하고 분석하면
- Then 구문마다 문제(problem)가 없고 읽은 테이블, 쓴 테이블(op), 조인(컬럼 대응), 읽은 컬럼, 쓴 컬럼이 표의 기대와 정확히 같으며 `UNKNOWN_COLUMN`이 없다. 테이블 뒤의 컬럼 목록(`TB_A AS X (C1, C2, C3)`)은 DDL을 알면 위치로 원래 컬럼에 대응한다. DB 링크가 붙은 시퀀스와 `NEXT VALUE FOR`는 시퀀스 읽기이고 `DUAL`·`SYSIBM.SYSDUMMY1`은 테이블이 아니다.

### F-006-AC35 (대상: F-006-R35)
- Given DDL이 있는 `TB_A`를 읽는 `… WHERE REG_DT < SOME_FN(INTERVAL, 6) AND NM = OTHER_FN(DISTINCT, IGNORE)`, `… WHERE DEL_YN = 'N'`, 해석하지 못한 토큰이 낀 `UPDATE TB_A a SOMETHING STRANGE b SET a.NM = 1, a.GHOST = 2, PHANTOM = 3 WHERE a.ID = 1 AND a.NO_COL = 2`, 같은 꼴의 문제 없는 구문
- When 분석하면
- Then 키워드 이름(`INTERVAL`, `DISTINCT`, `IGNORE`)은 `declared=false` 컬럼도 `UNKNOWN_COLUMN`도 되지 않고, 키워드가 아닌 `DEL_YN`은 전과 같이 `TB_A`의 컬럼이다. 해석하지 못한 구간이 있는 구문은 `PARSE_FAILED`가 남고 DDL에 없는 이름(`GHOST`, `PHANTOM`, `NO_COL`, `SOMETHING`)의 컬럼이 하나도 없으며 테이블 `WRITES`는 그대로다. 문제 없는 구문에서는 한정한 이름과 SET 컬럼이 전과 같이 참조가 된다.

### F-006-AC36 (대상: F-006-R36)
- Given DDL이 있는 `TB_A`·`TB_B`를 쓰는 MySQL 매퍼 구문(내장 소스 `attrs.dialect=mysql`): `ADDDATE(SYSDATE(), INTERVAL -6 MONTH)`·`INTERVAL #{n} DAY`·`INTERVAL STATUS DAY`·`NOW() - INTERVAL 1 WEEK`, `INSERT IGNORE INTO TB_B …`, `REPLACE INTO TB_A …`, `INSERT INTO TB_B SET A_ID = …`, `… ON DUPLICATE KEY UPDATE NM = VALUES(NM), STATUS = STATUS + 1`, `UPDATE TB_A a JOIN TB_B b ON a.ID = b.A_ID SET a.NM = b.VAL …`, `DELETE a, b FROM TB_A a INNER JOIN TB_B b ON …`, `SELECT @RNUM := @RNUM + 1 AS RNUM, T.* FROM (… ORDER BY a.REG_DT DESC) T, (SELECT @RNUM := 0) R`, `FORCE INDEX (…) STRAIGHT_JOIN … REGEXP '^a' … NOT REGEXP #{p}`, `NM = 'it\'s'`와, Oracle의 `INTERVAL '6' MONTH`·`INTERVAL '1 12' DAY TO HOUR`, `LIKE '%\_%' ESCAPE '\'`
- When 분석하면
- Then `PARSE_FAILED`·`UNSUPPORTED_SYNTAX`·`UNKNOWN_COLUMN`이 없고 DDL에 없는 테이블·컬럼(`IGNORE`, `A`, `INTERVAL`, `JOIN`)이 생기지 않는다. `INSERT IGNORE`·`REPLACE`·`INSERT … SET`은 대상 테이블과 컬럼의 `WRITES`(op=INSERT), 다중 테이블 UPDATE는 첫 테이블 `WRITES`와 나머지 `READS`·`JOINS`, 다중 테이블 DELETE는 두 테이블 모두 `WRITES`(op=DELETE)다. 사용자 변수가 든 구문은 주 테이블(`TB_A`)을 읽고 `REG_DT`가 `TB_A`의 컬럼이다. 백슬래시 이스케이프는 mysql 내장 소스에서만 문자열의 일부이고, Oracle 표기의 해석은 전과 같다.

### F-006-AC37 (대상: F-006-R37)
- Given `db/db2/x.sql`과 `attrs.dialect=db2`인 내장 소스를 `--dialect oracle`로, `db/mysql/dump.sql`을 `--dialect db2`로, 경로에 방언이 없고 `FETCH FIRST … WITH UR`를 쓰는 파일과 특징이 없는 파일을 옵션 없이
- When 분석하면
- Then db2 경로의 구문과 db2 내장 소스의 구문은 `--dialect oracle`이어도 `dialect=DB2`이고, mysql 경로의 구문에는 `--dialect db2`여도 `dialect`가 없다. 방언 이름이 없는 파일은 구문 특징이 확실하면 그 방언, 아니면 키가 없다.

### F-006-AC38 (대상: F-006-R38)
- Given `FROM TB_A A JOIN (SELECT K FROM T1 UNION ALL SELECT K FROM T2) U ON A.K = U.K`, 별칭과 위치로 맞춘 분기(`SELECT B.ID AS REF_ID … UNION SELECT C.C_ID …`)와 `(+)` 조건, 선택 항목이 식·리터럴인 분기, 집합 연산인 CTE, 집합 연산 뷰 둘의 조인, `*`인 분기
- When 분석하면
- Then 분기마다 원본 테이블 컬럼으로 `JOINS`가 생기고(`TB_A(K)=T1(K)`, `TB_A(K)=T2(K)`. 뷰 둘이면 분기 쌍마다), 선택 항목이 단순 컬럼이 아닌 분기와 `*`인 분기는 빠진다.

### F-006-AC39 (대상: F-006-R39)
- Given `… WHERE 1=1 STTUS_CODE LIKE ? AND …`(AND 빠짐. 인라인 뷰 안과 최상위), `… LIKE '%' || ? }} '%' …`(오타 기호), `… LIKE '%' || ? '%' GROUP BY …`(연결 연산자 빠짐), 닫는 괄호 없이 끝난 서브쿼리와 함수 호출
- When 파싱하면
- Then 첫 문제의 메시지가 차례로 "`STTUS_CODE` 앞에 AND/OR 또는 연산자가 필요한 자리", "예상하지 못한 `}`", "`'%'` 앞에 AND/OR 또는 연산자가 필요한 자리"이고, 정말로 괄호가 없는 둘만 "닫는 괄호가 없음"이다.

### F-006-AC40 (대상: F-006-R40)
- Given `USER_TAB_COLUMNS`·`ALL_TABLES`·`DBA_USERS`·`V$SESSION`·`SYSCAT.TABLES`·`SYSIBM.SYSCOLUMNS`·`SYSSTAT.TABLES`를 읽는 구문과, `USER_INFO`·`USER_ROLES_MAP`(DDL 없음)·`ALL_CODES`(DDL 있음)·`APP.TABLES`를 읽는 구문
- When 분석하면
- Then 앞의 사전 뷰·카탈로그 테이블 노드에 `systemCatalog=true`가 붙고(`declared=false`), 프로젝트의 테이블에는 붙지 않는다. 스키마를 뗀 이름이 같은 `TABLES`는 사전 스키마 없이도 읽었으므로 붙지 않는다.

### F-006-AC41 (대상: F-006-R41)
- Given ``information_schema.`TABLES` ``·``information_schema.`COLUMNS` ``·`INFORMATION_SCHEMA.COLUMNS`(해석하지 못하는 `g.[value]`가 섞인 MSSQL 구문)·`` `information_schema`.`KEY_COLUMN_USAGE` ``·`Information_Schema.ROUTINES`·`sys.sysobjects`·`sys.extended_properties`를 읽는 구문, 해석하지 못한 토큰 뒤의 `FROM INFORMATION_SCHEMA.SCHEMATA`, 그리고 한정자 없는 `COLUMNS`·`` `VIEWS` ``를 읽는 구문, `information_schema.TRIGGERS`와 한정자 없는 `TRIGGERS`를 함께 읽는 파일
- When 분석하면
- Then `TABLES`·`KEY_COLUMN_USAGE`·`ROUTINES`·`SYSOBJECTS`·`EXTENDED_PROPERTIES`·`SCHEMATA` 노드에 `systemCatalog=true`가 붙고(`declared=false`), 한정자 없이도 읽은 `COLUMNS`·`TRIGGERS`·`VIEWS`에는 붙지 않는다. 한정자 없는 `COLUMNS`가 없는 프로젝트에서는 `COLUMNS`에도 붙는다.

### F-006-AC42 (대상: F-006-R42)
- Given `ADDDATE(SYSDATE(), INTERVAL -6 MONTH)`, `SYSDATE()`, `NVL(..)`과 `ADDDATE(REG_DT, 7)`, `SYSDATE - INTERVAL 6 MONTH`, `SYSDATE + INTERVAL -1 DAY`가 든 구문과, Oracle의 `SYSDATE - INTERVAL '6' MONTH`·`IN (SYSDATE)`·`SYSDATE(+)`·`ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -6)`, DB2의 `CURRENT DATE - 6 MONTHS WITH UR`, `INTERVAL`이 컬럼 이름인 Oracle 구문(`INTERVAL - 1 > 0 … SYSDATE`)
- When 구문 특징으로 방언을 판정하면(`DialectDetector.fromTokens`), 그리고 방언 이름이 없는 SQL 파일로 분석하면
- Then 앞의 다섯은 판정하지 않고(null) 구문 노드에 `dialect`가 없다. 뒤의 Oracle·DB2 구문은 전처럼 `ORACLE`·`DB2`다.

## 5. 가정
- 컬럼 엣지: UPDATE/DELETE/MERGE 대상 테이블은 테이블 수준 WRITES만 만들고 테이블 수준 READS는 따로 만들지 않는다. WHERE나 SET 우변에서 읽은 대상 테이블 컬럼은 컬럼 READS로 남긴다. 인라인 뷰를 거친 DML에서는 SET(또는 INSERT 컬럼 목록)의 컬럼이 원본으로 추적되는 테이블 인스턴스, 원본을 못 찾았으면 인라인 뷰의 테이블이 하나일 때 그 인스턴스가 대상이다. 그 테이블을 읽는 인스턴스가 모두 이런 대상일 때만 테이블 수준 READS를 뺀다(F-006-R14). 어느 테이블이 갱신되는지 정하지 못하면 쓰기를 만들지 않고 읽기만 남긴다.
- **고정 컬럼(F-006-R13)**: AND로만 이어진 조건에서 `컬럼 = 상수`(좌우 무관)와 `컬럼 IN (리터럴 하나)`를 본다. 상수는 리터럴(문자열·숫자·부호 붙은 숫자·`CURRENT DATE` 같은 특수 레지스터), 바인드(`?`, `:name`, `#{..}`), 루틴 본문의 변수·매개변수다. OR·NOT 아래의 조건, `<>`·범위·`LIKE`·`IN (여럿)`·`IN (바인드 하나)`(MyBatis `<foreach>`를 한 번 전개한 모양일 수 있다)·`= NULL`·함수 호출 결과와의 비교는 고정이 아니다. 서브쿼리 안의 조건은 바깥이 OR 아래여도 그 서브쿼리의 인스턴스에 대해 따로 본다. 인라인 뷰·CTE를 거친 컬럼은 원본 인스턴스로 추적하고, 인라인 뷰의 선택 목록에 놓인 바인드(`SELECT #{dt} AS SALES_DT …`)와의 등치는 보지 않는다.
- 고정 값 표기: 문자열 리터럴은 따옴표를 떼고(`''`는 `'` 하나로) 적고, 그 밖의 리터럴은 원문, 바인드와 변수는 `?`다. `…FixedColumns`는 처음 나온 순서, `…FixedValues`는 컬럼 순서대로 `COL=값`을 나열한다(전개본마다 값이 다르면 한 컬럼에 값이 여럿이다).
- 같은 구문의 다른 인스턴스 쌍이나 같은 주인의 다른 전개본에서 같은 조인(테이블 쌍과 컬럼 대응이 같거나 포함 관계)을 또 보면 고정 컬럼은 **양쪽 모두에 있는 것만** 남기고 값은 모은다. 한 곳에서라도 상수 조건 없이 조인했다면 고정이라고 말할 수 없기 때문이다. `<if>` 안의 상수 조건이 고정으로 남는지는 mybatis가 그 조건이 빠진 전개본을 넘기는지에 달렸다.
- 비한정 컬럼: 가장 안쪽 스코프부터 본다. 카탈로그상 그 컬럼을 가진 릴레이션이 하나면 그것, 둘 이상이면 모호(엣지 없음). 카탈로그가 모르는 릴레이션이 하나뿐이고 나머지에는 확실히 없으면 그 릴레이션의 컬럼으로 본다(서브쿼리 안에서도 같다). 스코프의 모든 릴레이션에 확실히 없을 때만 바깥 스코프로 나간다. `ROWNUM`, `SYSDATE`, `USER` 같은 의사 컬럼·특수 이름은 카탈로그에 컬럼으로 있을 때만 컬럼이다. `LEVEL`은 계층 쿼리(`CONNECT BY`)의 스코프에서만 의사 컬럼이다(F-006-R33. 전에는 어느 구문에서나 의사 컬럼이었다).
- JOINS는 **테이블 인스턴스 쌍**마다 하나다. 같은 구문에서 같은 테이블을 별칭 둘로 조인하면(`CODE c1`, `CODE c2`) 엣지 둘이 된다. 컬럼 대응까지 같은 엣지는 구문 안에서 하나로 합친다. 같은 주인의 전개본 사이에서는 컬럼 대응 집합이 포함 관계면 큰 쪽 하나만 남긴다.
- JOINS의 방향(from/to)은 그 인스턴스 쌍에서 처음 나온 조건의 좌변 테이블이 from이다. `CONNECT BY PRIOR a = b`는 같은 테이블의 셀프 조인으로 기록한다. `x = (SELECT col ...)`, `x = ANY (SELECT col ...)`, 단일 컬럼 `NOT IN (SELECT col ...)`도 등치 조건으로 본다. 튜플 `NOT IN (서브쿼리)`만 예외다(F-006-R20, 아래 "반조인"). NATURAL JOIN은 JOINS를 만들지 않는다. 같은 테이블의 같은 컬럼끼리만 묶인 조건(`a.id = b.id`, 둘 다 같은 테이블)은 관계 정보가 없어 남기지 않는다.
- JOINS 엣지의 위치는 조건의 좌변 컬럼 위치다. 위치를 보존하지 않는 내장 소스에서는 origin이고, 같은 구문에서 같은 from→to 엣지가 둘 이상이면 그래프의 중복 제거(from, to, kind, location)에 걸리지 않게 둘째부터 시작·끝 열을 1씩 민다.
- 인라인 뷰·CTE가 집합 연산(UNION 등)이면 그 컬럼은 원본 테이블로 추적하지 않는다. 재귀 CTE의 자기 참조도 추적하지 않는다.
- TRUNCATE는 statementType=`DELETE`, WRITES(op=`DELETE`, truncate=true)로, LOCK TABLE은 statementType=`OTHER`, READS(lock=true)로 기록한다.
- statementType: 한 주인에 구문이 여럿이면(selectKey, 익명 블록) 첫 쓰기 구문(INSERT/UPDATE/DELETE/MERGE), 없으면 CALL, SELECT, OTHER 순이다. 파싱 결과가 OTHER이고 생산자가 이미 채운 값이 있으면 덮어쓰지 않는다.
- 두 부분 이름 호출 `X.Y(..)`: 카탈로그에 패키지 X가 있으면 `Ids.routine(X, Y)`, X가 DDL에서 스키마로 쓰인 이름이면 `Ids.routine(Y)`, 모르면 패키지로 보고 `Ids.routine(X, Y)`. 세 부분 `S.P.N`은 `Ids.routine(P, N)`. (`SqlIds.routineOfCall`)
- SQL 식 안의 함수 호출은, DDL 파일에 정의된 루틴(또는 패키지 소속 루틴) 이름과 같을 때만 CALLS를 만든다. 익명 블록 안의 `DBMS_*`, `UTL_*` 호출과 `RAISE_APPLICATION_ERROR`는 CALLS로 만들지 않는다.
- 카탈로그에 시노님으로 정의된 이름은 테이블 엣지는 시노님 이름(`db:table:<시노님>`) 그대로 두고(링커가 맞춘다), 컬럼 엣지와 JOINS는 시노님이 가리키는 테이블로 만든다.
- `__DYNAMIC__`은 식별자가 그 문자열을 **포함**하는지로 판별한다(`TB_LOG___DYNAMIC__`). 미해결 엣지 attrs.text에 원문 조각을 남긴다. 스키마 자리만 동적이면(`__DYNAMIC__.TB_B`) 테이블은 그대로 기록하고 dynamic=true만 붙인다.
- 빈 줄은, 다음 토큰이 새 구문을 시작하는 키워드이고 앞 토큰이 이어지는 문맥(집합 연산자, `(`, `,` 등)이 아닐 때만 구문을 끊는다. 내장 소스에서는 빈 줄, 줄 단독 `/`, `@` 종결자, 줄 명령 규칙을 쓰지 않는다.
- dialect 값은 `ORACLE`, `DB2`이고 판정하지 못하면 attrs에 넣지 않는다.
- 진단: SQL 파일은 파서가 복구한 문제가 하나라도 있으면 구문마다 `PARSE_FAILED`(WARN) 하나를 남긴다. 내장 소스에서는 MyBatis 전개 때문에 생기는 가벼운 문제(빠진 피연산자, 끝에 남은 쉼표, 빈 IN 목록)는 남기지 않고, 겹친 `FROM`·`WHERE`도 조용히 받아들인다.
- 위치의 끝(endLine, endColumn)은 마지막 글자 다음(제외)이다.
- SQL 파일의 익명 블록(`BEGIN .. END;`)은 구문 노드 하나로 만들고 안의 호출과 DML만 건진다(변수, 제어 흐름은 보지 않는다). (F-008 이후 갱신: 익명 블록은 루틴 본문 파서에 위임한다. 구문 노드가 하나인 것은 같고, 변수 스코프와 제어 구조 안쪽의 구문·동적 SQL까지 본다. F-008-R11)

- **데이터 적재 파일(F-006-R24)**: 묶을지는 파일을 다 읽은 뒤 정한다(`DataLoadGrouper.endOfFile`). 기준(10건)에 못 미친 후보는 전처럼 테이블마다 9건까지 쥐고 있다가, 적재 파일이면 테이블별 묶음으로 넘기고 아니면 보통의 구문으로 돌린다. (b)의 "세션·트랜잭션 명령"은 `COMMIT`·`ROLLBACK`·`SAVEPOINT`, `SET ..`·`ALTER SESSION ..`·`USE ..`, SQL*Plus·CLP 줄 명령(`PROMPT`, `SPOOL`, `WHENEVER`, `EXIT`, `QUIT`, `DEFINE`, `UNDEFINE`, `SHOW`, `ECHO`, `CONNECT`, `DISCONNECT`, `TERMINATE`)이다. 리터럴이 아닌 INSERT, 조건 있는 DELETE, UPDATE, SELECT, DDL, 루틴·블록, `LOCK TABLE`, 모르는 구문이 하나라도 있으면 (b)가 아니다((a)는 그와 무관하다). 초기화 구문은 파서가 문제 없이 읽은, 조건·조인·RETURNING이 없는 `DELETE [FROM] T`와 `TRUNCATE [TABLE] T`다(대상이 동적이면 아니다). 초기화 구문은 묶지 않고 구문마다 노드로 두되 `dataLoad=true`, `rowCount=0`을 붙인다(소비자가 적재 구문과 함께 기본으로 뺀다. `rowCount=0`이라 행 수 합계가 "n행 이상"이 되지 않는다). 적재 파일의 그 밖의 구문(UPDATE, 조건 있는 DELETE, `INSERT .. SELECT`)은 보통의 구문이다. 진단 문구는 "데이터 적재 파일: 리터럴 INSERT n건을 테이블별 구문 m개로 묶음(10건 미만인 테이블 k개 포함), 초기화 구문(조건 없는 DELETE·TRUNCATE) j건도 dataLoad로 표시"다(괄호와 뒤 구절은 해당할 때만). 같은 테이블의 적재 묶음이 방언이 다른 파일 여러 벌에 있으면 한 방언의 것만 남긴다(F-007-R20).
- **홀로 있는 `${}` 조각(F-006-R25)**: jeesite의 `${sqlMap.dsf}`처럼 실행할 때 ` AND (…)`나 조인 절 통째로 바뀌는 치환이다. 식이 끝난 자리(피연산자 뒤)의 동적 식별자는 뒤 토큰이 조건이나 절의 끝(`AND`, `OR`, `)`, `;`, 끝, 또 다른 동적 식별자, `WHERE`·`GROUP`·`HAVING`·`ORDER`·`UNION` 같은 절 키워드, 조인 키워드)일 때 건너뛴다. 뒤가 `FROM`이나 쉼표면 건너뛰지 않는다(선택 목록의 별칭 자리다: `SELECT a.ID ${alias} FROM`). FROM 목록 뒤의 동적 식별자는 괄호 깊이와 상관없이 건너뛴다. SET 절에서는 `=`·`.`·`(`가 따르지 않는 동적 식별자를 항목이 아니라 조각으로 보고, 컬럼 목록에서는 이름 뒤의 동적 식별자를 건너뛴다. 조각 안에 무엇이 있는지는 알 수 없으므로(테이블을 더 읽을 수도 있다) 구문의 `dynamic=true`가 그 표시다.
- **내부 표식의 표시(F-006-R26)**: 치환 목록(`attrs.dynamicParts`)이 있고 건수가 본문의 `__DYNAMIC__` 수와 같으면, sql은 렉싱하기 전에 본문의 n번째 `__DYNAMIC__`을 n번째 원문으로 되돌린다(렉서는 `${…}`를 `__DYNAMIC__` 식별자와 같게 읽고 토큰의 원문만 `${…}`다. 그래서 메시지·미해결 원문·고정 값에 원문이 그대로 나온다). 위치를 보존한 내장 소스(`exactPositions=true`)에서는 mybatis가 채운 공백(뒤쪽, 뒤에 글자가 붙어 있으면 앞쪽)까지 원문으로 바꿔 길이를 유지한다. 유지할 수 없거나 바로 앞이 `:`이면(바인드 표기와 섞인다) 그 자리는 그대로 둔다. 그렇게 남은 것과 치환 목록이 없는 내장 소스(java의 애너테이션 SQL 등)의 `__DYNAMIC__`은 글로 내보낼 때 `${…}`로 바꾼다(`DynamicText.display`: 진단 메시지, 미해결 노드의 이름과 `attrs.text`, `JOINS`의 고정 값). 미해결 노드의 ID(`unresolved:sql-dynamic:<구문 ID>`)에는 원래 표식이 없다. SQL 파일의 `${…}`는 원래 원문 그대로다.
- **치환 목록(F-006-R26. mybatis 모듈 F-009 spec 5절과 같은 문구다)**: 내장 소스 `attrs`의 키는 `dynamicParts`다. 값은 본문의 n번째 `__DYNAMIC__` 자리에 있던 `${…}` 원문을 등장 순서대로, 건마다 `<원문 길이>:<원문>`으로 구분자 없이 이어 붙인 문자열이다(길이는 UTF-16 글자 수. 예: `13:${sqlMap.dsf}10:${orderBy}`). 원문은 `${`부터 `}`까지 매퍼에 쓴 그대로이고, 안에 줄바꿈이 있으면 줄바꿈을 낀 공백 묶음을 공백 한 칸으로 바꾼다(값에 줄바꿈이 없다). `${…}`가 없는 내장 소스에는 키가 없다. 주석이나 문자열 안의 `${…}`도 본문에서 치환되므로 목록에 든다(글자 그대로의 n번째다).
- **`#` 주석과 MySQL 판정(F-006-R27)**: SQL 파일은 ① 경로의 방언 이름(F-007 spec 5절 "방언 토큰 표")이 `mysql`·`mariadb`면 MySQL, 다른 방언 이름이면 아니다. ② 방언 이름이 없으면 `--dialect`를 줬을 때 아니다. ③ `mybatis.dialectVariant`나 프로젝트 설정(F-007-R21)이 `mysql`·`mariadb`면 MySQL이다. ④ 그 밖에는 본문에 MySQL에만 있는 표기(백틱으로 감싼 이름, `AUTO_INCREMENT`, `ENGINE=`)가 있으면 MySQL이다. 내장 소스는 ①이 `attrs.dialect`(생산자가 넘긴 `databaseId`나 방언별 매퍼 파일의 방언)다. 경로의 방언 이름을 옵션보다 먼저 보는 까닭은 파일 하나에 대한 근거가 프로젝트 전체에 대한 옵션보다 구체적이기 때문이다(`--dialect oracle`로 분석해도 `*_mysql.sql`은 MySQL 파일이다). MySQL이 아닌 SQL에서 `#`은 이름 글자다: `#` 다음(또는 `##` 다음)이 글자나 `_`면 식별자의 시작이고(`#TMP`, `##G_TMP`), 아니면 전처럼 기호다. 백틱 이름, `::`, `LIMIT`, `0x` 리터럴은 Oracle·DB2 문법과 겹치지 않아 방언과 상관없이 받는다.
- **줄바꿈으로만 나눈 스크립트에서 ALTER의 동작 줄(F-006-R22 보강)**: 종결자가 하나 이하인 파일은 구문 하나짜리 파일일 수도 있다. `ALTER TABLE T` 다음 줄의 `DROP COLUMN X`, DB2의 `ALTER COLUMN C SET DATA TYPE ..`는 새 구문이 아니라 그 ALTER의 동작이다: ALTER로 시작한 단위에서 줄 처음의 `DROP`·`ALTER`는, 단위에 아직 동작이 없거나(`ALTER TABLE [스키마.]이름`뿐) 바로 뒤가 `(`·`COLUMN`·`CONSTRAINT`·`PRIMARY`·`UNIQUE`·`FOREIGN`·`CHECK`·`PARTITION`이면 끊지 않는다(직전 수정에서 생긴 회귀를 대상 방언 점검에서 찾았다).
- **스크립트 구문의 `dialect`(F-006-R28)**: 파일의 방언은 `--dialect` → 경로의 방언 이름이 `oracle`·`db2`일 때 그 방언 → (경로에 방언 이름이 없고 MySQL로 판정하지 않은 파일만) 파일 전체의 구문 특징 점수 → 그래도 모르면 구문 하나의 특징이다. 경로에 다른 방언의 이름(`mysql`, `altibase`, `tibero`, `hsql` 등)이 있거나 MySQL로 판정한 파일은 "대상 외 방언의 파일"이라 `dialect`를 넣지 않는다(Tibero·Altibase는 Oracle 계열이지만 계약의 값이 `ORACLE`·`DB2`뿐이라 비운다). 구문 특징 점수에서 `SMALLINT`·`BIGINT`를 뺐다(DB2만의 표기가 아니다). 루틴 본문의 구문은 루틴 단위의 특징(PL/SQL·SQL PL 문법)으로 판정한 값을 그대로 쓴다. 내장 소스의 판정(옵션 → `attrs.dialect` → 구문 특징)은 바꾸지 않았다.
- **데이터 적재 묶기(F-006-R15)**: 대상은 SQL 스크립트 파일의 구문 단위(STATEMENT)뿐이다. 후보는 파서가 문제 없이 읽은 INSERT 가운데 값 자리에 서브쿼리가 없고(리터럴, 바인드, 시퀀스, 함수 호출, `SYSDATE` 같은 상수 이름과 그것들의 연산·CASE는 된다), 대상이 이름 테이블이며, `INSERT FIRST/ALL WHEN` 조건과 `RETURNING`이 없는 것이다. `INSERT .. SELECT`는 원본이 `SELECT 상수.. FROM DUAL`(또는 `SYSIBM.SYSDUMMY1`, FROM 없음)이나 그것들의 `UNION [ALL]`이고 조건·그룹·정렬·CTE가 없을 때만 후보다.
- 묶기 시작하는 기준은 **한 (파일, 테이블)의 후보 구문 10건 이상**이다(`DataLoadGrouper.MIN_STATEMENTS`). 그런 묶음이 없고 INSERT뿐인 파일도 아니면(F-006-R24) 코드 몇 줄을 넣는 보통의 구문으로 보고 전처럼 구문마다 노드를 만든다(샘플 `04_comments_and_codes.sql`의 TB_CODE 7건이 이 경우이고 정답 목록이 구문마다의 노드를 요구한다). 기준에 닿을 때까지는 후보를 쥐고 있다가(테이블마다 9건까지) 닿으면 묶음으로 넘기고 놓아 준다.
- 묶음의 열쇠는 구문의 **첫 대상 테이블**이다. `INSERT ALL`이 여러 테이블에 넣으면 첫 대상 테이블의 묶음에 들어가고 다른 대상 테이블의 `WRITES`도 그 노드에서 나간다. `rowCount`는 구문 수가 아니라 행 수다(다중 행 VALUES는 행마다, INSERT ALL은 INTO 절마다, `SELECT .. UNION ALL`은 가지마다, `DEFAULT VALUES`는 1). 진단의 "n건"은 구문 수다.
- 묶음 노드에는 값 자리의 시퀀스 `READS`와 `dummyTable`을 남기지 않는다. 다만 값 자리에서 부른 함수가 DDL에 정의된 루틴이면 그 `CALLS`는 묶음 노드에서 낸다(카탈로그는 모든 파일을 본 뒤에야 완성되므로 "내장 함수만 부른 구문"인지는 묶는 시점에 알 수 없다. 호출 사실을 버리지 않으려고 이렇게 했다).
- 큰 스크립트: 파일은 통째로 읽되 토큰은 흘려 만든다(`Lexer.Stream`, `ScriptSplitter.forEach`). 나눈 단위 가운데 루틴과 뷰·CTAS만 토큰을 쥐고 있고, 그 밖의 구문은 본문과 위치만 남겼다가 다시 볼 때 렉싱한다(`Lexer.tokenizeAt`). 방언 판정도 같은 훑기에서 한다(`DialectDetector.Scorer`).
- **연달아 든 구문(F-006-R16)**: 새 구문으로 보는 것은 구문이 끝난 자리(괄호 밖)의 `SELECT`/`INSERT`/`UPDATE`/`DELETE`/`MERGE`, 이름이 따르는 `CALL`, CTE를 여는 `WITH`다. 나뉜 구문의 사실은 같은 주인에 합치고 구문 종류는 "첫 쓰기 구문, 없으면 CALL, SELECT" 규칙을 따른다. SQL 파일에서도 같아서, 종결자가 빠져 이어진 구문은 경고 없이 한 구문 노드에 합쳐진다. 루틴 본문에서 종결자가 빠져 이어진 구문도 `Statement.Sequence`로 나온다.
- 최상위 SELECT 뒤에 남은 토큰을 건너뛸 때 쉼표를 만나도 FROM 목록으로 잇지 않는다(전에는 이어서 읽어 컬럼이 테이블이 됐다). 대신 `FROM a x y, b`처럼 별칭 뒤에 군더더기가 낀 구문의 `b`는 놓친다. 구문 끝의 복구(salvage)도 `FROM`/`JOIN` 바로 뒤의 이름 하나만 건진다.
- **키워드와 같은 이름(F-006-R17)**: 테이블 이름 자리에서 막는 것은 `Keywords.EXPR_STOP`의 예약어뿐이다. `LIMIT`, `OFFSET`, `WINDOW`, `QUALIFY`, `ESCAPE`, `BULK`, `LOOP`(`Keywords.SOFT_CLAUSE_KEYWORDS`)는 테이블 이름으로는 받지만(`LIMIT`·`OFFSET`은 숫자·바인드가 따르면 절) 별칭 자리에서는 절 키워드다(따옴표로 감싸면 이름이다). 컬럼 자리(식 자리)에서는 뒤 토큰이 절의 모양이 아니면 컬럼이다(F-006-R33, 아래 "키워드와 같은 이름의 컬럼". 2026-10-11 고쳐 씀: 전에는 컬럼 자리에서도 절 키워드였다). 별칭 자리의 `RETURN`·`CALL`·`DO`는 뒤에 절이나 목록의 끝이 오면 별칭, 식·문장이 오면 절·구문의 시작이다. `INSERT INTO ONLY (A, B) ..`의 `ONLY`는 테이블이고 `FROM ONLY (T)`는 DB2의 ONLY 절이다.
- **범위 밖 방언(F-006-R18)**: 백틱 식별자는 MySQL에서 대소문자를 가리지 않고 매퍼가 따옴표 없이 같은 이름을 쓰므로 대문자로 정규화한다(토큰 본문은 `"NAME"`, 위치는 백틱 포함). 줄 처음의 `#`은 줄 주석이다(`#{..}`와 이름 안의 `#`은 아니다). MySQL의 백슬래시 이스케이프(`'it\'s'`)는 Oracle·DB2의 `ESCAPE '\'`와 충돌해 받지 않는다(그런 문자열이 든 구문은 `PARSE_FAILED`로 남는다). `IF [NOT] EXISTS (..)` 뒤가 `DROP`이거나 `ALTER .. DROP`이면 관리 구문으로 건너뛰고 괄호 안의 시스템 테이블은 읽은 테이블로 만들지 않는다.
- **반조인(F-006-R20)**: 튜플 `NOT IN (서브쿼리)`는 "이 조합이 없다"는 조건이라 컬럼 쌍을 하나씩 떼어 관계의 근거로 삼지 않는다. 단일 컬럼 `NOT IN`과 `NOT EXISTS`의 상관 조건은 키 대응이 분명해 그대로 `JOINS`다(샘플 정답 `OrderMapper.deleteOrder`의 `TB_PAYMENT↔TB_ORDER`가 `NOT EXISTS` 상관 조건이다).
- **줄바꿈으로만 나눈 스크립트(F-006-R22)**: 문자열·주석 밖의 종결자(`;`, 줄 단독 `/`, 줄 끝 `@`)가 하나 이하이고 줄 처음의 구문 키워드(`INSERT`, `UPDATE`, `DELETE`, `MERGE`, `CREATE`, `ALTER`, `DROP`, `COMMENT`, `GRANT`, `REVOKE`, `TRUNCATE`, `COMMIT`, `ROLLBACK`)가 둘 이상인 파일이다(`ScriptSplitter.shapeOf`). 이런 파일에서는 빈 줄 규칙과 같은 문맥 검사(앞 토큰이 이어지는 문맥이 아님)를 줄 처음의 키워드에 적용한다. 줄을 바꿔 이어 쓰는 일이 흔한 `SELECT`·`WITH`에서는 끊지 않는다.
- "읽은 것이 없음" 진단은 파일에서 DDL 구문, 루틴, 구문 노드, 적재 묶음을 하나도 얻지 못했고 줄 처음에 `CREATE`·`INSERT`·`UPDATE`·`DELETE`·`MERGE`·`SELECT`·`ALTER`·`COMMENT`·`CALL`·`BEGIN`·`DECLARE`·`TRUNCATE`가 있는 줄이 조용히 건너뛴 관리 구문(`CREATE USER`, `ALTER SESSION` 등) 수보다 많을 때 남긴다. `DROP`·`GRANT`·`COMMIT`만 있는 파일과 빈 파일에는 남기지 않는다.

- **함수로 감싼 조인 키(F-006-R29)**: 감싸도 "그 컬럼의 값으로 비교한다"고 보는 함수는 `TRIM`·`LTRIM`·`RTRIM`·`STRIP`, `UPPER`·`LOWER`·`UCASE`·`LCASE`, `TO_CHAR`·`TO_NCHAR`·`TO_NUMBER`·`TO_DATE`, `CAST`(`x::type` 포함), `NVL`·`COALESCE`·`IFNULL`·`VALUE`, DB2의 형 변환 함수 `CHAR`·`VARCHAR`·`INTEGER`·`INT`·`SMALLINT`·`BIGINT`·`DECIMAL`·`DEC`·`NUMERIC`·`DOUBLE`·`FLOAT`·`REAL`이다(`FactExtractor.WRAPPERS`). 컬럼은 첫 인자여야 하고(`TRIM(BOTH ' ' FROM x)`만 뒤) 나머지 인자는 리터럴(부호 붙은 숫자 포함)이어야 한다. 바인드가 낀 `NVL(x, #{v})`, 한정된 이름(`PKG.TRIM`), 카탈로그에 같은 이름의 사용자 루틴이 있는 함수, 분석 함수 절(`OVER` 등)이 붙은 호출은 아니다. 적용 범위는 ON·WHERE의 `=`(`(+)`, `CONNECT BY PRIOR` 포함)와 `IN (서브쿼리)`·스칼라 서브쿼리 등치의 **왼쪽**이다. 서브쿼리의 선택 목록에서 감싼 컬럼(`a.X IN (SELECT TRIM(c.CODE) …)`), USING, 고정 컬럼 판정(`TRIM(c.CODE_ID) = 'X'`)에는 적용하지 않는다.
- 감싸인 표시는 **컬럼 쌍마다** 센다: 그 쌍의 모든 근거(같은 구문의 다른 조건·다른 인스턴스 쌍, 같은 주인의 다른 전개본)가 어느 한쪽이라도 감싼 조건일 때만 감싸인 쌍이고, 그때 감싸인 적이 있는 쪽을 모은다. 엣지의 `wrapped`는 감싸인 쌍이 하나라도 있으면 true, `leftWrapped`(`from` 쪽)·`rightWrapped`(`to` 쪽)는 그 쪽이 감싸인 쌍이 있으면 true다(여러 컬럼 조인에서 어느 쌍인지는 남기지 않는다). 전개본을 합칠 때 한쪽에만 있는 컬럼 쌍의 표시는 그대로 둔다. 소비자(관계 추론)는 `wrapped=true`인 조인의 양쪽 컬럼 타입이 다를 수 있음(`CHAR(3)` 대 `VARCHAR2(15)`)을 알고 써야 한다.
- **집합 연산 서브쿼리(F-006-R30)**: 분기는 집합 연산을 편 SELECT 한 덩어리들이다(괄호로 묶은 분기, 연산자 종류 무관. `MINUS`·`EXCEPT`의 오른쪽 분기도 "같은 값을 비교한다"는 근거로 본다). 분기마다 따로 판정해 선택 항목이 컬럼이 아니거나(`*`, 식, 리터럴) 컬럼 수가 왼쪽과 다른 분기는 건너뛴다. 조인의 위치는 모두 왼쪽 컬럼의 자리다.
- **DDL에 없는 비한정 컬럼(F-006-R31)**: 보이는 모든 스코프의 릴레이션에 카탈로그상 확실히 없을 때만 본다(DDL이 없는 테이블이 끼어 있으면 위의 "비한정 컬럼" 규칙 그대로다 — 불일치가 아니라 모름이다). 기준 스코프는 더미 테이블(`DUAL`)이 아닌 릴레이션이 있는 **가장 안쪽** 스코프다. 그 스코프의 릴레이션이 테이블 하나이거나, `*`로 테이블 하나를 통째로 내보낸 인라인 뷰·CTE 하나이거나(페이징 포장), 같은 테이블의 인스턴스뿐이면(자기 조인) 그 테이블의 컬럼으로 읽기를 만든다. 뷰도 테이블과 같다. 그 밖에는 추측하지 않는다: 후보 테이블이 둘 이상이거나 테이블 옆에 인라인 뷰가 있으면 `UNKNOWN_COLUMN` 진단만 남기고, 후보에 뷰나 ALTER로만 본 테이블이 끼어 있으면(컬럼 목록이 다 있다고 말할 수 없다) 진단도 남기지 않으며, 컬럼 목록이 분명한 인라인 뷰·CTE 하나뿐인데 거기 없는 이름, 선택 목록에 `${}`가 든 인라인 뷰는 아무것도 만들지 않는다.
- 컬럼으로 보지 않는 이름(읽기도 진단도 없음): `Keywords.PSEUDO_COLUMNS`의 의사 컬럼·특수 이름, 루틴의 변수·매개변수(`FactExtractor.variables`), 카탈로그에 루틴으로 정의된 이름(괄호 없이 부른 함수), 그 구문의 SELECT 목록 별칭(GROUP BY·HAVING·ORDER BY에서 별칭을 쓰는 방언), 따옴표·백틱 이름(MySQL의 `"문자열"`), `__DYNAMIC__`이 든 이름, 함수의 직접 인자로 온 자료형·날짜 단위 이름(`FLOAT`, `SIGNED`, `DAY`, `MONTH`, `SQL_TSI_*` 등 `FactExtractor.NON_COLUMN_ARGUMENTS`)과 `CONVERT`의 모든 이름 인자(문자셋), MySQL의 SELECT 수식어(`SQL_CALC_FOUND_ROWS` 등). **파서가 가볍지 않은 문제를 남긴 구문에서는 이 규칙 전체를 쓰지 않는다**(이름이 컬럼인지 믿을 수 없다. 예: `SELECT TOP 10 …`).
- 전개본 조합의 가짜 짝: mybatis가 `<choose>` 둘(컬럼 목록, 테이블)을 조합해 "이 테이블의 컬럼 목록 + 저 테이블"인 전개본을 넘길 수 있다(공개 프로젝트의 `RssTagService.selectRssTagServiceTable`). 그래서 추측으로 붙인 읽기와 미확인 컬럼은, **그 추측을 한 전개본은 읽지 않았지만 같은 주인의 다른 전개본(익명 블록이면 다른 구문)이 읽는 테이블**의 DDL에 그 이름의 컬럼이 있으면 버린다(`StatementFacts.dropGuessesExplainedBy`). 같은 전개본이 함께 읽는 테이블은 근거가 아니다. 별칭으로 한정해 쓴 컬럼(`a.NO_SUCH_COL`)은 추측이 아니므로 전과 같이 항상 참조가 된다.
- (2026-10-11 고쳐 씀) 루틴 본문(익명 블록 포함)의 구문도 같은 규칙이다(F-008-R17): 테이블이 하나면 `declared=false` 읽기, 여럿이면 진단. 전에는 변수 스코프가 놓친 이름(DB2 `FOR row AS SELECT c AS x … DO … END FOR`가 암묵적으로 만드는 컬럼 변수 `x`)일 수 있어 진단만 남겼는데, 본문 파서가 그 이름들을 변수로 등록하게 됐다(F-008-R16). 변수 스코프를 다 알지 못하는 자리(본문 파서가 문제를 남긴 루틴, 컬럼을 모르는 테이블을 `SELECT *`로 도는 DB2 `FOR` 문의 본문)에서는 읽기도 진단도 만들지 않는다(`FactExtractor.scopeComplete`). 루틴 본문에서 리터럴로 풀린 동적 SQL은 보통 구문과 같은 규칙이다.
- `UNKNOWN_COLUMN`은 주인 구문(매퍼 구문, SQL 파일 구문, 루틴 본문의 구문, 익명 블록)마다 1건이고 위치는 처음 나온 컬럼의 자리(위치를 보존하지 않는 내장 소스에서는 구문)다. 메시지는 `DDL에 없는 컬럼이라 소속 테이블을 정하지 못함: <컬럼, …> (후보 테이블: <테이블, …>) (<주인 구문 ID>)`다. 테이블이 하나여서 참조로 만든 경우에는 진단이 없다(별칭으로 한정한 `a.NO_SUCH_COL`과 같다. `declared=false`인 `DB_COLUMN` 노드가 그 표시다).

- **줄 끝(F-006-R32)**: 줄을 끝내는 글자는 LF와, 뒤에 LF가 없는 CR이다(`Lexer.isLineBreak`. mybatis·jsp의 `LineMap`, web의 규칙과 같다). CRLF의 CR은 공백이다. `--` 줄 주석과 MySQL `#` 주석은 CR이든 LF든 처음 나오는 줄 끝 글자 앞에서 끝나고(주석 토큰의 글에 CR이 들어가지 않는다), 닫히지 않은 문자열·따옴표 이름·백틱 이름·`#{`·`${`도 같은 자리에서 끊는다. `ScriptSplitter.shapeOf`(글자 단위 훑기)와 PROMPT·REM 줄 지우기도 같은 글자를 줄 끝으로 본다. 원문의 글자와 오프셋은 바꾸지 않는다(위치를 옮겨 다시 렉싱하는 `Lexer.tokenizeAt`와 구문 원문 자르기가 오프셋을 쓴다). 내장 소스의 줄·열을 호스트 파일 위치로 바꾸는 일(`EmbeddedSource.locationOf`)은 core의 몫이다.
- **키워드와 같은 이름의 컬럼(F-006-R33)**: 식의 첫 토큰이 될 수 없는 절 키워드(`Keywords.EXPR_STOP`) 가운데 예약어가 아닌 것은 뒤 토큰으로 가린다(`SqlParser.softKeywordIsName`). `LIMIT`·`OFFSET`은 숫자·바인드·`${}`가 따를 때만 절, `ESCAPE`는 문자열·바인드가 따를 때만, `BULK`는 `COLLECT`가 따를 때만, `WINDOW`는 `이름 AS`가 따를 때만, `MERGE`는 `INTO`가 따를 때만 절이다. `QUALIFY`·`LOOP`는 절 뒤에 임의의 식·문장이 오므로 거꾸로, 뒤 토큰이 피연산자가 끝난 자리의 것(연산자, 쉼표, 닫는 괄호, `FROM`·`IS`·`IN`·`LIKE`·`AND`·`ASC` 등, 끝)일 때만 이름이다. 그래서 피연산자가 빠진 깨진 구문(`WHERE ID = LIMIT 10`)의 절은 전처럼 절이다. 함수 인자 사이에 끼는 키워드(`SEPARATOR`, `PASSING`, `COLUMNS`, `CONTENT`, `DOCUMENT`, `PLACING`)는 바로 뒤에서 피연산자가 끝나면 그 이름의 컬럼이다(`MAX(CONTENT)`, `NVL(CONTENT, '')`). `NEW 타입(..)`의 `NEW`는 뒤가 `IN` 같은 키워드면 컬럼이다. 별칭 자리(테이블 별칭, AS 없는 SELECT 별칭)는 바꾸지 않았다(`SELECT A LIMIT FROM T`의 `LIMIT`은 여전히 별칭이 아니다). `USER`, `UID`, `ROWID`, `SQL`, `DUMMY`, `ISO` 같은 `Keywords.PSEUDO_COLUMNS`의 이름은 DDL에 컬럼으로 있을 때만 컬럼이다(Oracle·DB2에서 `USER`는 특수 레지스터라 따옴표 없이는 컬럼 이름이 될 수 없다). 한정해 쓴 `a.USER`는 컬럼이다(`ROWID`만 예외). `LEVEL`·`CONNECT_BY_ISLEAF`·`CONNECT_BY_ISCYCLE`은 그 이름이 보이는 스코프(바깥 포함) 가운데 `START WITH`/`CONNECT BY`가 있는 SELECT가 있을 때만 의사 컬럼이다: Oracle은 `CONNECT BY` 없는 구문의 `LEVEL`을 받지 않으므로(ORA-01788) 그런 구문의 `LEVEL`은 컬럼이다(DB2 스키마의 `LEVEL` 컬럼). 다만 DDL이 있는 테이블에 그 컬럼이 없으면 F-006-R31의 추측은 하지 않는다(전과 같이 제외 대상이다). 이름 붙인 윈도 참조 `SUM(x) OVER w`도 함께 받는다.
- **대상 방언 구문 표(F-006-R34)**: `OracleDb2SyntaxTest`의 표가 기준이다. Oracle — `MERGE … USING (SELECT … FROM DUAL) … WHEN MATCHED THEN UPDATE … WHERE … DELETE WHERE …`/`INSERT … WHERE`, `INSERT ALL|FIRST [WHEN … THEN] INTO … SELECT`, `FOR UPDATE [OF t.col, …] NOWAIT|WAIT n|SKIP LOCKED`, 재귀 `WITH … SEARCH … CYCLE …`, `LISTAGG([DISTINCT] … [ON OVERFLOW …]) WITHIN GROUP (ORDER BY …) [OVER (…)]`, `KEEP (DENSE_RANK FIRST|LAST ORDER BY …)`, `XMLAGG(XMLELEMENT(이름, …) ORDER BY …).EXTRACT(…).GETSTRINGVAL()`, `XMLELEMENT("이름", XMLATTRIBUTES(… AS …), XMLFOREST(…))`, `REGEXP_LIKE`·`REGEXP_SUBSTR`, `CONNECT BY NOCYCLE PRIOR`·`SYS_CONNECT_BY_PATH`·`CONNECT_BY_ROOT`·`ORDER SIBLINGS BY`, `(+)` 다중 조건, `PARTITION (p)`·`SUBPARTITION (sp)`·`PARTITION FOR (…)`(DML 포함), `AS OF TIMESTAMP|SCN`, `PIVOT`/`UNPIVOT [INCLUDE NULLS]`, `OFFSET n ROWS FETCH FIRST|NEXT n [PERCENT] ROWS ONLY|WITH TIES`, `JSON_VALUE(… RETURNING … ON ERROR)`·`JSON_QUERY`·`JSON_EXISTS`·`IS JSON`·`JSON_OBJECT(KEY k VALUE v …)`·`JSON_ARRAYAGG`, `JSON_TABLE(… COLUMNS (…))`·`XMLTABLE(… PASSING … COLUMNS …)`(컬럼 절은 건너뛴다), JSON 점 표기·객체 속성 `별칭.컬럼.속성`(둘째 조각이 컬럼이고, 조인·고정 컬럼의 근거로는 쓰지 않는다), DB 링크 `t@link`·`스키마.t@link.domain`(DML, 시퀀스 포함. 링크를 뗀 이름으로 기록한다), 힌트 `/*+ … */`(DML 포함), 분석 함수의 윈도 절·`IGNORE NULLS`·`OVER ()`·`NTH_VALUE(…) FROM FIRST`, `TABLE(패키지.함수(…))`·`TABLE(CAST(:b AS 타입))`, `CROSS APPLY`, `ROLLUP`·`CUBE`·`GROUPING SETS`, 날짜 리터럴·`INTERVAL`·`AT TIME ZONE`, 파티션 외부 조인, `RETURNING … INTO`, `SET (a, b) = (서브쿼리)`, 컬렉션 조건 `MEMBER OF`·`IS OF (타입)`·`IS A SET`. DB2 — `FETCH FIRST [n] ROW[S] ONLY`, `WITH UR|CS|RS|RR [USE AND KEEP … LOCKS] [SKIP LOCKED DATA] [WAIT FOR OUTCOME]`, `FOR READ ONLY`·`FOR FETCH ONLY`, `OPTIMIZE FOR n ROW[S]`, `QUERYNO n`, `FOR UPDATE OF col, …`, `FROM FINAL|NEW|OLD TABLE (INSERT|UPDATE|DELETE … [INCLUDE (컬럼 타입)])`(안쪽 DML의 쓰기를 기록한다. `INCLUDE` 컬럼은 대상 테이블의 컬럼이 아니다), `VALUES (…)` 단독·`VALUES … INTO`·`VALUES (서브쿼리) INTO`, `MERGE … USING (VALUES (?, ?)) AS s (a, b)`·`WHEN MATCHED AND … THEN DELETE`·`ELSE IGNORE`, `CURRENT DATE|TIMESTAMP|TIME|SCHEMA|USER|SERVER`·`CURRENT_DATE`·`USER`·`SESSION_USER`, `col CONCAT col`, `DAYS(x)`·레이블 기간(`1 DAY`, `3 MONTHS`, `? DAYS`, `(식) DAYS`), `ROW_NUMBER() OVER()`·`ROWNUMBER()`, `LATERAL (…) AS t`·`TABLE (서브쿼리) AS t`·`TABLE(함수(…)) AS t (c1 [타입], …)`·`TABLE(VALUES …) AS t (n)`·`XMLTABLE(… PASSING x AS "d" COLUMNS …)`, 상관 이름 뒤 컬럼 목록 `AS t (c1, c2)`(인라인 뷰와 테이블. CTE는 정의의 `WITH t (c1, c2) AS (…)`), `EXCEPT [ALL]`·`INTERSECT`, 스키마 한정 `SCHEMA.TABLE`(스키마를 뗀 이름으로 기록), `SYSIBM.SYSDUMMY1`, `SET (a, b) = (?, ?)`, 다중 행 `VALUES`, 형 변환 함수·`CHAR(d, ISO)`·`STRIP(x, LEADING, '0')`, `LEFT(…)`·`RIGHT(…)`·`INSERT(…)` 함수, `FOR SYSTEM_TIME AS OF`, `ONLY (t) 별칭`, `RID(t)`·`RID_BIT(t)`.
- 테이블 뒤의 컬럼 목록(`FROM TB_A AS X (C1, C2)`): 컬럼을 위치대로 바꿔 부른다. DDL을 알면 `X.C1`은 그 테이블의 첫 컬럼이고, 모르면 바꿔 부른 이름이 어느 컬럼인지 알 수 없어 컬럼 엣지와 그 이름의 조인을 만들지 않는다(전에는 `TB_A.C1`이라는 없는 컬럼을 만들었다).
- **키워드 이름과 깨진 구문(F-006-R35)**: 추측하지 않는 이름은 `Keywords.EXPR_STOP`(절 경계 키워드)과 `Keywords.NEVER_GUESSED_COLUMNS`(절·연산자·구문 키워드 가운데 컬럼 이름으로 쓰이지 않는 것: `INTERVAL`, `IGNORE`, `REPLACE`, `DISTINCT`, `EXISTS`, `CASE`, `REGEXP`, `PARTITION`, `OVER` 등)다. `KEY`, `VALUE`, `TYPE`, `NAME`, `STATUS`, `COMMENT`처럼 컬럼 이름으로 흔한 낱말은 넣지 않았다(DDL이 낡아 빠진 컬럼일 수 있다). DDL이 없는 테이블 하나를 읽는 구문에서는 전과 같이 그 테이블의 컬럼이 된다(F-006-R33의 표가 이 경우다). "해석하지 못한 구간이 있는 구문"은 파서가 가볍지 않은 문제를 하나라도 남긴 구문이다(F-006-R31에서 추측을 막던 조건과 같다): DDL이 있는 테이블에 대해서는 한정한 이름(`a.NO_COL`)과 SET·INSERT 컬럼도 DDL에 있을 때만 엣지가 된다. DDL이 없는 테이블의 컬럼은 확정할 수 없으므로 전과 같다.
- **MySQL 관용 표기(F-006-R36)**: `@`는 식의 첫 자리에 이름이 바로 붙어 올 때만 사용자 변수다(Oracle의 `t@link`, DB2의 `@` 종결자와 겹치지 않는다). `@v := 식`은 값 식을 읽는다. `INTERVAL`은 뒤가 `[부호]숫자 단위` 또는 `이름 단위`일 때만 MySQL의 기간이고(Oracle의 `INTERVAL '6' MONTH`는 전과 같이 리터럴), `INTERVAL + 1`처럼 단위가 없으면 `INTERVAL`이라는 컬럼의 산술이다. `VALUES(컬럼)`은 `ON DUPLICATE KEY UPDATE` 안에서만 함수다. `REPLACE INTO`는 구문 종류와 `op`가 INSERT다. 다중 테이블 DELETE의 둘째 대상부터는 `Statement.Delete.moreTargets`로 넘어가 `WRITES`(op=DELETE)가 된다. `ON DUPLICATE KEY UPDATE`의 항목은 읽고 넘긴다(대상 테이블은 같고, 그 컬럼의 쓰기는 따로 남기지 않는다). 백슬래시 이스케이프는 렉서의 MySQL 플래그(줄 처음 `#` 주석과 같은 판정, F-006-R27)가 켜진 입력에서만 받는다.
- **구문의 `dialect`(F-006-R37)**: SQL 파일은 파일의 방언(경로의 이름, 없으면 내용 특징)이 oracle·db2면 그 값, 다른 방언이면 키 없음, 방언을 읽지 못했으면 파일 전체의 구문 특징 점수 → 구문 하나의 특징 → 키 없음이다. `--dialect`는 판정이 없는 파일의 루틴·익명 블록을 PL/SQL로 읽을지 SQL PL로 읽을지 고르는 힌트(`RoutineContext.fallbackDialect`)와 MySQL 판정(F-006-R27)에만 쓴다. 내장 소스는 `attrs.dialect`가 oracle·db2면 그 값, 다른 방언 이름이면 키 없음, 없으면 구문 특징이다.
- **집합 연산 뷰와의 조인(F-006-R38)**: 인라인 뷰·CTE의 모양에 분기들의 모양을 함께 들고 다니다가(`Derived.branches`), 조인 조건의 컬럼이 그런 뷰의 것이면 그 컬럼의 자리(첫 분기의 선택 목록에서의 위치)에 있는 각 분기의 원본을 쓴다. 컬럼 읽기(`READS`)는 분기 안에서 이미 기록된다. 집합 연산 뷰를 다시 고른 서브쿼리(`IN (SELECT V.X FROM V)`)와 컬럼 목록으로 이름을 바꾼 것(`AS u (c1, c2)`, `WITH u (c1) AS …`)은 추적하지 않는다.
- **복구 메시지(F-006-R39)**: 괄호를 닫을 자리와 구문이 끝난 자리에서 예상하지 못한 토큰을 만났을 때 고른다(`TokenCursor.unexpectedMessage`). "피연산자가 끝난 뒤"는 앞 토큰이 이름·리터럴·바인드·닫는 괄호·`(+)`인 것이고 "피연산자"는 이름(절 키워드 제외)·리터럴·바인드다. `${}` 조각 옆에서는 이 메시지를 쓰지 않는다. 진단에는 전과 같이 첫 문제의 메시지와 나머지 건수가 실린다.
- **사전 뷰·카탈로그 테이블(F-006-R40)**: 사전 스키마는 `SYS`, `SYSCAT`, `SYSIBM`, `SYSSTAT`, `SYSIBMADM`, `SYSTOOLS`, `SYSPROC`, `SYSFUN`, `INFORMATION_SCHEMA`다. 스키마 없이 쓴 이름은 `V$`·`GV$`·`V_$`로 시작하거나, `USER_`·`ALL_`·`DBA_` 뒤가 알려진 사전 뷰 이름(`TABLES`, `TAB_COLUMNS`, `CONSTRAINTS`, `INDEXES`, `OBJECTS`, `SEQUENCES`, `USERS` 등 `FactExtractor.DICTIONARY_VIEWS`)이거나, 접두사 없는 사전 이름(`TAB`, `COLS`, `DICT`, `NLS_SESSION_PARAMETERS` 등)일 때다. 표시는 분석이 끝날 때 붙인다: 그 이름을 읽은 모든 구문이 사전 테이블로 읽었고 DDL에 정의가 없을 때만이다. `DUAL`·`SYSIBM.SYSDUMMY1`은 전과 같이 테이블 노드를 만들지 않는다.
- **사전 스키마로 한정한 참조(F-006-R41)**: `FactExtractor.isSystemCatalog`는 조각이 둘 이상인 이름이면 마지막 조각 바로 앞의 한정자만 보고(대문자로 바꿔 사전 스키마 목록과 견준다) 이름은 보지 않는다. 전에는 이름을 따옴표로 감싼 참조를 사전 테이블에서 뺐는데, 렉서가 백틱 이름을 따옴표 이름과 같이 다뤄 ``information_schema.`TABLES` ``가 빠졌다. 따옴표 이름을 빼는 규칙은 한정자 없는 이름(`"USER_TABLES"`)에만 남는다. 구문 끝에서 건진 참조(`salvagedReads`)도 같은 판정을 거친다(`Run.readNamed`). 표시는 전처럼 분석이 끝날 때 붙이므로, 같은 이름을 한정자 없이 읽은 구문이 하나라도 있으면 붙지 않는다.
- **MySQL 표기와 방언 추측(F-006-R42)**: `DialectDetector.Scorer`가 토큰을 흘려 받으며 본다. `SYSDATE`·`ADDDATE` 바로 뒤가 `(`이거나, 단위(`MICROSECOND`, `SECOND`, `MINUTE`, `HOUR`, `DAY`, `WEEK`, `MONTH`, `QUARTER`, `YEAR`) 바로 앞이 숫자 리터럴이고 그 앞이 `INTERVAL`(또는 `INTERVAL` 뒤의 `+`·`-`)이면 MySQL 표기다. 하나라도 있으면 점수와 상관없이 판정하지 않는다(Oracle도 DB2도 아니다). 단위까지 봐야 하는 까닭은 `INTERVAL`이 컬럼 이름일 수 있어서다(`INTERVAL - 1 > 0`). 구문마다, 내장 소스는 주인마다, 방언을 읽지 못한 파일은 파일 전체로 판정하는 자리에 같이 적용된다. `DATE_ADD`·`DATE_SUB`·`NOW()`·`IFNULL` 같은 다른 MySQL 함수는 보지 않는다(사용자 정의 함수 이름과 겹칠 수 있고, 그것만으로는 Oracle 점수가 생기지 않는다).

### 알려진 한계
- 한 줄에 구문이 둘 이상이면 `sql:stmt:<파일>:<줄>` ID가 같아 한 노드로 합쳐진다.
- Oracle 복합 트리거(COMPOUND TRIGGER), `CREATE JAVA SOURCE`, `MODEL` 절, `MATCH_RECOGNIZE`는 지원하지 않는다(건너뛰거나 진단).
- XML/JSON 함수의 특수 인자(`XMLTABLE ... COLUMNS`, `JSON_TABLE`)는 관대하게 건너뛰므로 그 안의 컬럼은 놓칠 수 있다. `COLUMNS` 절은 통째로 건너뛴다(F-006-R34): 결과 컬럼의 정의라 원본 컬럼이 없지만, `PATH` 식 안에서 바깥 컬럼을 쓴 드문 경우는 놓친다.
- (2026-10-11 대상 방언 점검에서 확인한 미지원 구문) Oracle `MODEL` 절·`MATCH_RECOGNIZE`(위와 같다), `WITH FUNCTION … SELECT`(인라인 PL/SQL 함수), SQL*Plus 치환 변수 `&var`, `OF` 없는 `x MEMBER y`, DB2 z/OS의 부정 기호 `¬=`. 이런 구문은 `PARSE_FAILED`로 남고 앞에서 읽은 테이블은 기록된다. `JSON_OBJECT('k' IS v)`의 `IS` 표기와 CTE를 참조하며 붙인 컬럼 목록(`FROM cte AS x (c1, c2)`)은 문제 없이 넘어가지만 그 값·컬럼의 읽기를 놓친다.
- 여러 테이블에 걸친 `USING`(`T1 JOIN T2 USING (ID) JOIN T3 USING (ID)`)의 둘째 조인은 카탈로그가 없으면 왼쪽의 어느 테이블과 잇는지 정하지 못해 `JOINS`가 없다.
- 별칭 자리의 절 키워드: `SELECT A LIMIT FROM T`, `FROM T OFFSET`처럼 `LIMIT`·`OFFSET`·`WINDOW` 등을 AS 없는 별칭으로 쓴 것은 읽지 못한다(AS를 쓰거나 따옴표로 감싸면 읽는다).
- PL/SQL 본문 안의 SQL에서 지역 변수는 `FactExtractor.variables`로 알려 주지 않으면, 테이블이 하나뿐이고 카탈로그에 없을 때 컬럼으로 오인될 수 있다. (해소됨, F-008) 루틴 본문과 익명 블록은 본문 파서가 변수 스코프를 모아 넘긴다(F-008-R4).
- (해소됨, F-008) ~~DB2 레이블이 `L1:LOOP`처럼 붙어 있으면 `:LOOP`가 바인드로 렉싱된다.~~ 렉서가 이름 바로 뒤의 `:` 다음이 블록을 여는 키워드(`LOOP`, `WHILE`, `FOR`, `REPEAT`, `BEGIN`)이면 레이블로 읽는다(`LexerTest`의 F-008-AC1). `L1:LOOPS`처럼 키워드가 아닌 이름은 그대로 바인드다.

## 6. 미확정 사항
- 없음(사용자가 기본값으로 진행하도록 위임했다. 5절의 가정은 검토 대상이다).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: [0004](../../decisions/0004-hand-written-tolerant-sql-parser.md)
- 계약 추가 제안(구현 뒤 정리): 본 문서 8절

## 8. 계약 추가 제안 (F-001 design.md에 반영 요청)
design.md 반영됨(2026-10-11, 5차 수정 F-006-R41·R42): 3.4 "DB의 사전 뷰·카탈로그 테이블"과 3.5 DB_TABLE attrs `systemCatalog`(사전 스키마로 한정한 참조는 이름·따옴표·백틱·대소문자와 상관없이 해당), 3.5 공통 규칙 "`dialect` 값"(MySQL 표기가 있으면 구문 특징으로 판정하지 않는다). 루트 README "DDL에 없는 테이블" 항목에 ``information_schema.`TABLES` `` 예를 더했다.

design.md 반영 요청(2026-10-11, 재검증 3차 후속. ~~아직 반영 전~~):
design.md 반영됨(2026-10-11): `dialect`와 `--dialect`의 쓰임은 3.5 공통 규칙 "`dialect` 값"·3.8 표 아래 문단·3.9(`sql` 내장 소스의 `dialect`), `systemCatalog`는 3.4·3.5(DB_TABLE), 집합 연산 인라인 뷰·CTE의 `JOINS`는 3.4 `JOINS` 행, `declared=false` 컬럼과 SQL 키워드 이름은 3.4 "DDL에 없는 컬럼 참조", MySQL 표기와 `REPLACE INTO`는 3.4, `PARSE_FAILED` 메시지는 3.7에 넣었다. 7절에는 "집합 연산 인라인 뷰는 추적하지 않는다"는 행이 없어 고칠 것이 없었다.
- 3.5 "`dialect` 값"과 3.8의 `--dialect` 설명: 구문 노드의 `dialect`는 파일·내장 소스에서 판정한 것만이다(F-006-R37). `--dialect`는 방언별 여러 벌에서 고를 방언(F-007-R23)과 판정이 없는 루틴의 문법 선택에 쓰이고 구문에 찍히지 않는다.
- 3.5 DB_TABLE attrs `systemCatalog`(boolean): DB가 제공하는 사전 뷰·카탈로그 테이블(F-006-R40). `declared=false`와 함께 온다. 소비자는 "DDL에 없는 테이블" 목록에서 뺀다.
- 3.4 `JOINS`: 집합 연산인 인라인 뷰·CTE와의 조인은 분기마다 원본 테이블로 만든다(F-006-R38). 7절의 "집합 연산 인라인 뷰는 추적하지 않는다" 한계를 고친다.
- 3.4 `declared=false` 컬럼: SQL 키워드 이름과 해석하지 못한 구간이 있는 구문에서는 만들지 않는다(F-006-R35). 3.4 "대상 외 방언 관용"에 MySQL 표기(F-006-R36)를 더한다. `WRITES`의 `op=INSERT`에 `REPLACE INTO`가 든다.
- 3.7 `PARSE_FAILED`(WARN)의 메시지: 복구 지점의 토큰과 기대한 것을 적는다(F-006-R39).

design.md 반영 요청(2026-10-11, 대상 방언 점검 2차. ~~아직 반영 전~~):
design.md 반영됨(2026-10-11): 줄 세기는 3.5 공통 규칙 "줄 끝은 LF, CRLF, CR 단독이다", 루틴 본문의 `declared=false` 컬럼과 `UNKNOWN_COLUMN`은 3.4 "DDL에 없는 컬럼 참조"·3.7, 의사 컬럼과 상관 이름의 컬럼 목록은 3.4, 레코드 필드와 비교한 고정 값은 3.5 공통 규칙 "`JOINS`의 고정 컬럼"에 넣었다. "루틴 본문에서는 테이블이 하나여도 진단만"은 design.md에 들어간 적이 없어 지울 것이 없었다.
- 3.3 "구문 ID의 줄"(SQL 파일의 구문, 루틴 본문의 구문): 줄은 LF, CRLF, CR 단독을 모두 한 줄로 세어 정한다(F-006-R32). CR 단독으로 저장된 파일에서 전에는 모든 구문이 1줄로 모여 ID가 합쳐졌다.
- 3.4·3.5의 `declared=false` 컬럼 참조 규칙 (4)("선언된 테이블의 DDL에 없는 비한정 컬럼인데 스코프의 실제 테이블이 하나뿐일 때"): 루틴 본문과 익명 블록의 구문에도 적용한다(F-008-R17). 3.7 `UNKNOWN_COLUMN`의 설명에서 "루틴 본문에서는 테이블이 하나여도 진단만"을 지운다(테이블이 여럿일 때만 남는다). "루틴 본문에서는 변수를 컬럼으로 잘못 알릴 수 있다"는 소비자 주의도 지운다(DB2 `FOR` 문의 암묵 변수를 등록한다 — F-008-R16).
- 3.4의 의사 컬럼 설명: `LEVEL`·`CONNECT_BY_ISLEAF`·`CONNECT_BY_ISCYCLE`은 계층 쿼리(`CONNECT BY`)에서만 의사 컬럼이고, 그 밖의 구문에서는 DDL을 모르는 하나뿐인 테이블의 컬럼 참조가 된다(F-006-R33).
- 3.5 `JOINS`의 고정 값: 루틴 본문에서 레코드·루프 변수의 필드(`r.col`, `v_row.col`)와 비교한 컬럼도 변수와 비교한 것으로 보고 `COL=?`로 남긴다(전에는 비한정 변수만 봤다 — F-008-R16).
- 3.4의 컬럼 참조: 상관 이름 뒤에 컬럼 목록을 둔 테이블(`FROM T AS X (C1, C2)`)의 `X.C1`은 DDL을 알면 그 테이블의 첫 컬럼이고 모르면 컬럼 엣지를 만들지 않는다. `별칭.컬럼.속성`(Oracle JSON 점 표기, 객체 속성)은 둘째 조각의 컬럼 읽기다(F-006-R34).

design.md 반영 요청(2026-10-11, 3차 재검증 후속. ~~아직 반영 전~~):
design.md 반영됨(2026-10-11): 적재 파일 판정과 초기화 구문은 3.3(데이터 적재 묶음 구문)·3.5(공통 규칙 "데이터 적재 구문", SQL_STATEMENT `dataLoad`·`rowCount`)·7절, `DATA_LOAD_GROUPED` 문구는 3.7, 대상 외 방언 파일의 `dialect`는 3.5 공통 규칙 "`dialect` 값", `dynamicParts`와 `__DYNAMIC__`은 3.9, 줄 처음 `#` 주석은 3.4와 7절에 넣었다.
- 3.3 "데이터 적재 묶음 구문"·3.5 공통 규칙 "데이터 적재 구문"·7절: "10건 이상이면 묶는다"를 "데이터 적재 파일(10건 이상인 묶음이 하나라도 생긴 파일, 또는 리터럴 INSERT·세션 명령·초기화 구문뿐이고 INSERT가 10건 이상인 파일)의 리터럴 INSERT는 건수와 상관없이 테이블별로 묶는다(1건이어도 `dataLoad=true`, `rowCount=1`). 적재 파일이 아니면 구문마다 노드다"로 바꾼다. "9건 이하는 전처럼 구문마다 노드"는 적재 파일이 아닐 때만 참이다.
- 3.5 SQL_STATEMENT `dataLoad`·`rowCount`: 적재 파일의 조건 없는 `DELETE FROM T`·`TRUNCATE`(초기화 구문)도 `dataLoad=true`이고 `rowCount=0`, `statementType=DELETE`다(묶음이 아니라 구문 하나다). 소비자는 `dataLoad=true`를 기본으로 빼면 되고(지금 규칙 그대로), 적재 묶음만 세려면 `statementType=INSERT`로 가른다.
- 3.7 `DATA_LOAD_GROUPED`: 문구가 "데이터 적재 파일: 리터럴 INSERT n건을 테이블별 구문 m개로 묶음(10건 미만인 테이블 k개 포함), 초기화 구문 … j건도 dataLoad로 표시"로 바뀌었다(적재 파일마다 1건인 것은 같다).
- 3.5 "`dialect` 값": 대상 외 방언으로 판정한 스크립트 파일(경로에 `mysql`·`altibase`·`hsql` 등의 이름이 있거나 본문이 MySQL)의 구문에는 키를 넣지 않는다(구문 특징으로 추측하지 않는다).
- 3.9: `sql` 내장 소스 attrs `dynamicParts`(F-009 spec 8절에 같은 요청이 있다). 자리표시자 `__DYNAMIC__` 행: "`attrs.text`에 원문 조각"은 `${…}` 원문(치환 목록이 없으면 `${…}`)이고 `__DYNAMIC__`은 그래프에 남지 않는다. 조건·절 사이에 홀로 있는 `__DYNAMIC__`은 불투명한 조각으로 건너뛴다(진단 없음, `dynamic=true`).
- 3.4의 "대상 외 방언 관용": 줄 처음의 `#` 주석은 MySQL로 판정한 SQL에서만 받는다. 그 밖에서는 `#TMP` 같은 이름이 식별자다.

**(3차, ~~design.md에 아직 반영 전~~)** 공개 프로젝트 검증 3차(N17·N29·N18)에서 생긴 계약이다.
design.md 반영됨(2026-10-11): `wrapped`·`leftWrapped`·`rightWrapped`는 3.5(공통 규칙 "함수로 감싼 조인 키", 엣지 attrs 표의 JOINS), 감싼 조인과 집합 연산 서브쿼리는 3.4 `JOINS` 행, `UNKNOWN_COLUMN`은 3.7, `declared=false` 컬럼 참조 규칙은 3.4 "DDL에 없는 컬럼 참조"에 넣었다. 마지막 항목의 "루틴 본문에서는 변수를 컬럼으로 잘못 알릴 수 있다"는 위 "대상 방언 점검 2차"에서 없어졌으므로 옮기지 않았다.

- JOINS 엣지 attrs(3.5 엣지 attrs 표의 JOINS 행): `wrapped`(boolean. 조인 컬럼 쌍 가운데 형 변환·정리 함수로 감싼 컬럼끼리의 등치로만 본 쌍이 있으면 true), `leftWrapped`·`rightWrapped`(boolean. `from`·`to` 쪽 컬럼이 감싸인 쌍이 있으면 true). 감싸이지 않은 조인에는 세 키를 넣지 않는다. 같은 컬럼 쌍을 감싸지 않은 조건으로도 봤으면(같은 구문, 같은 주인의 다른 전개본) 그 쌍은 감싸인 것이 아니다(고정 컬럼을 합치는 방식과 같다). 대상 함수와 범위는 5절 "함수로 감싼 조인 키".
- 3.4의 `JOINS` 설명에 덧붙일 것: "등치의 한쪽이 컬럼 하나만 감싼 형 변환·정리 함수여도 만든다(`wrapped=true`, F-006-R29). `IN`·비교의 서브쿼리가 집합 연산이면 분기마다 만든다(F-006-R30)."
- 진단 코드(3.7, "모듈이 정의한 코드" 표): `UNKNOWN_COLUMN` — INFO, "DDL에 없는 컬럼이라 소속 테이블을 정하지 못함: <컬럼, …> (후보 테이블: <테이블, …>) (<주인 구문 ID>)", 주인 구문마다 1건, 위치는 처음 나온 컬럼(없으면 구문), 모듈 sql(`io.codeatlas.sql.SqlDiagnosticCodes`). 컬럼 엣지는 만들지 않는다.
- `declared=false` 컬럼 참조 규칙(3.4 또는 3.5의 컬럼 `READS`/`WRITES` 설명): SQL이 참조했지만 DDL에 없는 컬럼은 `reference("db:column:T.C", DB_COLUMN, "C")`로 만든다(`declared=false`). 생기는 경우는 (1) 별칭·테이블 이름으로 한정한 컬럼, (2) INSERT 컬럼 목록·SET의 컬럼, (3) DDL이 없는 테이블이 스코프에 하나뿐일 때의 비한정 컬럼(전부터 있던 규칙), (4) **선언된 테이블의 DDL에 없는 비한정 컬럼인데 스코프의 실제 테이블이 하나뿐일 때**(F-006-R31, 새 규칙)다. 선언된 테이블이 여럿이면 만들지 않고 `UNKNOWN_COLUMN`을 남긴다.
- 소비자가 알아야 할 것: `wrapped=true`인 `JOINS`는 양쪽 컬럼의 타입·길이가 다를 수 있다(관계 추론이 타입 일치를 근거로 쓰면 이 표시를 본다). `declared=true`인 테이블 아래의 `declared=false` 컬럼은 "DDL과 SQL이 어긋난 곳"(DDL이 낡았거나 SQL이 틀렸다)이므로 ERD에서는 숨기거나 따로 표시하는 편이 맞다. `UNKNOWN_COLUMN`은 INFO이고 틀린 엣지를 만들지 않으려는 표시다. 루틴 본문에서는 변수를 컬럼으로 잘못 알릴 수 있다(5절).

아래는 2차 라운드의 항목이다.
design.md에 반영됨(2026-10-11, 2차): `dataLoad`·`rowCount`는 3.3(데이터 적재 묶음 구문의 ID)·3.5(공통 규칙 "데이터 적재 구문", SQL_STATEMENT 행), `DATA_LOAD_GROUPED`와 `PARSE_FAILED`의 새 WARN 경우는 3.7, 튜플 `NOT IN`은 3.4 `JOINS` 행, 복구 규칙·대상 외 방언 관용은 3.4의 설명 항목, 소비자 규칙은 3.4 "소비자가 지키는 규칙"(데이터 적재 구문은 기본으로 뺀다), 남은 한계는 7절에 넣었다. 처리 중인 파일 알리기(F-006-R23)는 4.1에 있다.

- SQL_STATEMENT attrs(3.5): `dataLoad`(boolean, SQL 파일의 데이터 적재 INSERT를 테이블별로 묶은 노드면 true), `rowCount`(int, 묶은 행 수). 이 노드의 `sql`은 묶음의 첫 구문이고 ID는 `sql:stmt:<파일>:<그 테이블의 첫 INSERT 줄>`이다(3.3의 "SQL 파일의 구문" ID 형식 그대로). 나가는 엣지는 테이블·컬럼 `WRITES`(`op=INSERT`)와, 값 자리에서 부른 사용자 정의 함수가 있을 때의 `CALLS`뿐이다.
- 진단 코드(3.7, "모듈이 정의한 코드" 표): `DATA_LOAD_GROUPED` — INFO, "데이터 적재 구문 n건을 테이블별 m개로 묶음", 묶음이 생긴 SQL 파일마다 1건, 모듈 sql(`io.codeatlas.sql.SqlDiagnosticCodes`).
- 진단 코드(3.7) `PARSE_FAILED`의 WARN에 경우 추가: "정의나 DML로 보이는 줄이 있는데 테이블도 구문도 얻지 못한 SQL 파일"(파일마다 1건, 위치는 파일).
- 3.4의 JOINS 설명에 "튜플 `NOT IN (서브쿼리)`의 컬럼 쌍으로는 만들지 않는다"를 덧붙인다.
- 소비자가 알아야 할 것: `dataLoad=true` 구문은 **구문 여러 개를 대표하는 노드**다. CRUD 매트릭스에서는 C(INSERT) 한 건으로 세면 되고(행 수가 필요하면 `rowCount`), 콜트리·영향 분석에서는 보통의 SQL 파일 구문과 같게 다뤄도 된다(호출하는 Java 코드가 없다). 리포트의 "SQL 구문 수"는 이 노드를 1로 세므로 전보다 크게 줄어든다.

아래는 이전 라운드의 항목이다.
design.md에 반영됨(2026-10-11): 아래 항목은 모두 F-001 design.md에 있다. 이번 라운드의 `JOINS` 고정 컬럼은 3.5(공통 규칙 "`JOINS`의 고정 컬럼"과 엣지 attrs 표)에, 인라인 뷰를 거친 DML의 `READS` 규칙은 3.4에, `<if>` 안의 상수 조건이 고정으로 남는 한계는 7절에 넣었다.

- (2026-10-11 감사 후속, design.md에 반영됨(2026-10-11)) JOINS 엣지 attrs: `leftFixedColumns`·`rightFixedColumns`(List, `from`·`to` 테이블 인스턴스의 고정 컬럼), `leftFixedValues`·`rightFixedValues`(List, `COL=값`. 바인드·변수는 `COL=?`). 고정 컬럼이 없으면 키를 넣지 않는다. 3.5 엣지 attrs 표의 JOINS 행에 추가.
- (2026-10-11 감사 후속, design.md에 반영됨(2026-10-11)) 3.4의 "UPDATE·DELETE·MERGE의 대상 테이블에는 테이블 수준 `WRITES`만 만든다"에 "인라인 뷰를 거쳐 갱신되는 테이블 인스턴스도 대상이다. 같은 구문에서 다른 인스턴스로 다시 읽으면 `READS`를 만든다"를 덧붙인다.
- SQL_STATEMENT attrs: `dummyTable`(`DUAL` 또는 `SYSIBM.SYSDUMMY1`을 쓴 구문에 그 이름).
- READS 엣지 attrs: `star`(boolean, `SELECT *`로 읽음), `sequence`(boolean, 대상이 DB_SEQUENCE), `lock`(boolean, LOCK TABLE).
- WRITES 엣지 attrs: `truncate`(boolean).
- UNRESOLVED(`sql-dynamic`)로 가는 READS/WRITES 엣지 attrs: `text`(원문 조각), WRITES면 `op`.
- SQL 파일 구문 노드의 `name`은 `<파일명>:<줄>`, attrs.sql은 공백을 정리한 원문(2000자 상한).
- READS 엣지의 to에 `DB_SEQUENCE`를 허용(`SEQ.NEXTVAL`, `NEXT VALUE FOR SEQ`).
- UNRESOLVED 분류 `sql-dynamic`: `unresolved:sql-dynamic:<구문 ID>`.
