---
type: spec
id: F-008
title: 프로시저 분석
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-008 프로시저 분석

## 1. 배경
콜트리를 테이블까지 잇는(PRD 목표 1) 경로에는 DB 안에서 도는 코드가 끼어 있다. Java가 `{call PKG.PROC}`로 부른 프로시저가 어떤 테이블을 읽고 쓰는지, 다른 프로시저를 또 부르는지 모르면 영향 범위가 거기서 끊긴다. F-006·F-007은 루틴의 헤더만 읽어 노드를 선언했고 본문은 확장 지점(`RoutineUnitHandler`)으로 남겼다. 이 기능은 그 본문을 파싱해 SQL 구문, 루틴 간 호출, 동적 SQL 지점을 그래프에 남긴다. ADR-0004에 따라 오류를 허용하는 재귀 하강 파서를 직접 구현한다.

## 2. 범위
- 포함: PL/SQL(Oracle)과 SQL PL(DB2 LUW)의 프로시저·함수·패키지 본문·트리거 본문과 익명 블록의 구조 파싱(블록, 선언부, 제어 구조, 커서, 예외·핸들러), 본문 안의 SQL 구문 추출과 READS/WRITES/JOINS 기록, 루틴 간 CALLS, 변수 스코프, 동적 SQL(EXECUTE IMMEDIATE, OPEN FOR, PREPARE/EXECUTE, DBMS_SQL.PARSE) 처리, 루틴 노드 attrs 보강.
- 제외: 헤더 파싱과 루틴·패키지 노드 선언, TRIGGERS_ON(F-006·F-007이 한다), 흐름 분석(분기별 도달 가능성, 값 전파), 타입 검사, 오버로드 구분, Oracle 복합 트리거·`TYPE BODY`·Java 저장 프로시저, DB 링크 너머의 객체.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-008-R1 | 시스템은 루틴 본문을 블록, 선언, 제어 구조(IF, CASE, LOOP, WHILE, FOR, REPEAT, FORALL), 커서 조작, 예외 처리부·핸들러, 대입, 호출, SQL 구문으로 이루어진 구문 트리(AST)로 만들어야 한다. | 사용자 요청, ADR-0004 |
| F-008-R2 | 시스템은 본문 안의 SQL 구문마다 `SQL_STATEMENT` 노드(ID `sql:stmt:<루틴 이름>:<SQL 키워드가 시작하는 줄>`, attrs statementType·sql·dialect)를 선언하고 `ROUTINE -CONTAINS→ 구문 -READS/WRITES→ 테이블·컬럼`, `JOINS` 엣지를 만들어야 한다. | 그래프 계약 3.3, 3.4 |
| F-008-R3 | 시스템은 커서 선언의 쿼리, 커서 FOR 루프의 인라인 쿼리, `OPEN c FOR SELECT`, `SELECT … INTO`, `BULK COLLECT`, `FORALL` 안의 DML, `RETURNING INTO`, DB2 `SET v = (SELECT …)`, `VALUES … INTO`, `FOR v AS c CURSOR FOR SELECT … DO`, 조건식·대입식·인자 안의 서브쿼리를 모두 구문 노드로 만들어야 한다. | 사용자 요청 |
| F-008-R4 | 시스템은 매개변수, 선언부·중첩 블록의 변수, 커서, 루프 변수, 트리거 상관 이름을 스코프로 모아 SQL 구문 분석에 넘겨, 변수와 레코드 필드가 컬럼이나 테이블로 기록되지 않게 해야 하며, `%TYPE`·`%ROWTYPE`이 가리키는 테이블로는 엣지를 만들지 않아야 한다. | 사용자 요청 |
| F-008-R5 | 시스템은 루틴 본문의 프로시저 호출문(`proc(..)`, `pkg.proc(..)`, DB2 `CALL proc(..)`)과 식 안의 함수 호출을 `ROUTINE -CALLS→ ROUTINE` 엣지로 만들어야 하며, 내장 함수·내장 패키지 호출은 엣지로 만들지 않아야 한다. | 그래프 계약 3.4, 사용자 요청 |
| F-008-R6 | 패키지 본문 안에서 이름만으로 부른 같은 패키지의 루틴(명세에 없는 것 포함)이면, 시스템은 대상 ID를 `db:routine:<패키지>.<이름>`으로 해야 하며, 루틴 안에 중첩 선언된 지역 서브프로그램은 노드를 만들지 않고 그 본문의 SQL과 호출을 바깥 루틴의 것으로 기록해야 한다. | 사용자 요청 |
| F-008-R7 | 본문의 SQL 구문 안에서 사용자 정의 함수를 호출하면, 시스템은 `구문 -CALLS→ ROUTINE` 엣지를 만들어야 한다. | 그래프 계약 3.4 |
| F-008-R8 | 동적 SQL(`EXECUTE IMMEDIATE`, `OPEN c FOR <식>`, `DBMS_SQL.PARSE`, DB2 `PREPARE … FROM` / `EXECUTE IMMEDIATE`)의 식이 문자열 리터럴(리터럴끼리의 `\|\|` 연결, 같은 루틴에서 리터럴로 한 번만 대입된 변수 포함)로 완전히 풀리면, 시스템은 그 SQL을 파싱해 일반 구문처럼 기록하고 구문 attrs.dynamic=true를 붙여야 한다. | 사용자 요청 |
| F-008-R9 | 만약 동적 SQL의 식이 풀리지 않으면, 시스템은 구문 노드(attrs.dynamic=true, sql에 알아낸 조각과 `{*}`)와 `unresolved:sql-dynamic:<구문 ID>`로의 `UNRESOLVED` 엣지, `DYNAMIC_SQL`(WARN) 진단을 남겨야 하며 추측으로 테이블 엣지를 만들지 않아야 한다. | AGENTS.md, 그래프 계약 3.2 |
| F-008-R10 | 시스템은 트리거 본문을 일반 루틴과 같이 분석해야 하며 `:NEW`/`:OLD`, DB2 `REFERENCING NEW AS n OLD AS o`의 상관 이름을 테이블 별칭이나 컬럼으로 기록하지 않아야 한다. | 사용자 요청 |
| F-008-R11 | 시스템은 SQL 파일과 내장 소스의 익명 블록(`BEGIN … END`, `DECLARE …`)을 같은 본문 파서로 분석해 그 결과를 블록의 구문 노드 하나에 모아야 한다. | 사용자 요청 |
| F-008-R12 | 만약 본문이 깨졌거나(END 누락, 괄호 불균형) 지원하지 않는 구문이 있으면, 시스템은 예외로 멈추지 않고 그 문장만 건너뛰어 진단(`PARSE_FAILED`, `UNSUPPORTED_SYNTAX`)을 남기고, 건너뛴 구간 안의 SQL과 나머지 문장·다른 루틴의 분석을 계속해야 한다. | AGENTS.md, ADR-0004 |
| F-008-R13 | 시스템은 루틴 노드에 `language`(`PLSQL`\|`SQLPL`), `statementCount`, `dynamicSql`, 함수의 `returnType`을 기록해야 하며, 패키지 명세에 선언만 있고 본문이 없는 루틴에는 `bodyMissing=true`를 붙여야 한다. | 사용자 요청(제안) |
| F-008-R14 | 시스템은 루틴의 매개변수·반환 타입·선언에 쓰인 `%TYPE`·`%ROWTYPE`이 가리키는 테이블·컬럼을 루틴 노드의 `attrs.typeRefs`(List, `TB_ORDER.ORDER_STATUS` 또는 `TB_X`. 스키마 제거·대문자)에 남겨야 하며, 변수·레코드·커서·패키지 변수를 기준으로 한 것은 넣지 않아야 한다. 엣지는 만들지 않는다(F-008-R4). | 독립 감사(U5) |
| F-008-R15 | SQL 구문이 아닌 식(대입식, 조건식, 호출 인자, 선언 초기값) 안에 시퀀스 참조(`seq.NEXTVAL`, `seq.CURRVAL`, `NEXT VALUE FOR seq`, `PREVIOUS VALUE FOR seq`)가 있으면, 시스템은 그 문장을 `SQL_STATEMENT` 노드(`statementType=OTHER`, `attrs.sequence=true`)로 만들고 시퀀스로 `READS`(`attrs.sequence=true`)를 내야 한다. `x.NEXTVAL`의 `x`가 변수·레코드면 시퀀스로 보지 않는다. | 독립 감사 |
| F-008-R16 | 시스템은 선언 없이 생기는 이름도 변수로 알아봐야 한다: DB2 SQL PL의 `FOR v AS [c CURSOR FOR] SELECT … DO … END FOR`에서 루프 변수 `v`와 **선택 목록의 컬럼 이름(별칭, 별칭이 없으면 단순 컬럼 이름, `*`는 DDL을 아는 테이블의 컬럼)** 이 루프 본문에서 변수이고, 본문에서 값을 받는 이름(대입문의 대상, `INTO`·`FETCH … INTO` 대상)은 선언을 보지 못했어도 변수다. 레코드·루프 변수의 필드(`r.col`, `v_row.col`), `RETURNING … INTO`·`BULK COLLECT INTO` 대상, DB2의 `DECLARE … CONDITION` 이름과 `SQLCODE`·`SQLSTATE`, `GET DIAGNOSTICS` 대상, PL/SQL의 `SQL%ROWCOUNT`·`SQLERRM`·`SQLCODE`, 트리거의 `:NEW`·`:OLD`·`INSERTING`·`UPDATING`·`DELETING`은 컬럼이나 테이블로 기록하지 않아야 하고, 레코드 필드와 비교한 컬럼은 변수와 비교한 것으로 다뤄야 한다(`JOINS`의 고정 값 `COL=?`). PL/SQL 커서 FOR 루프(`FOR r IN (SELECT …) LOOP`)의 선택 목록 이름은 변수가 아니다. | 대상 방언(DB2) 점검 |
| F-008-R17 | 루틴 본문(익명 블록 포함)의 SQL 구문에서 비한정 컬럼이 선언된 테이블의 DDL에 없으면, 시스템은 매퍼 구문과 같은 규칙(F-006-R31)으로 스코프의 실제 테이블이 하나일 때 `declared=false` 컬럼 참조를 만들고 여럿이면 `UNKNOWN_COLUMN`(INFO)을 남겨야 한다. 만약 변수 스코프를 다 알지 못하는 자리(본문 파서가 구조 문제를 남긴 루틴, 컬럼을 모르는 테이블을 `SELECT *`로 도는 DB2 `FOR` 문의 본문)면, 시스템은 그 구문에서 참조도 진단도 만들지 않아야 한다. | 대상 방언 점검, AGENTS.md(해석하지 못한 참조를 버리지 않는다) |

## 4. 수용 기준

### F-008-AC1 (대상: F-008-R1)
- Given 선언부, 중첩 블록, IF/ELSIF/ELSE, CASE 문, LOOP/WHILE/FOR/REPEAT, 레이블(`<<l>>`, `l:`), GOTO, 예외 처리부, DB2 핸들러·CONDITION·SIGNAL·GET DIAGNOSTICS, PRAGMA, PIPE ROW, `$IF`가 들어간 PL/SQL·SQL PL 본문
- When 본문 파서로 파싱하면
- Then 문제(problem) 없이 구문 트리가 만들어지고, 제어 구조 안쪽 깊이에 있는 SQL 구문과 호출문이 트리에 모두 들어 있다.

### F-008-AC2 (대상: F-008-R2)
- Given `SELECT … INTO`, `UPDATE`, `INSERT … SELECT`(조인 포함), `DELETE`, `MERGE`가 있는 독립 프로시저
- When 분석하면
- Then 구문마다 `sql:stmt:<루틴>:<SQL 키워드 줄>` 노드(statementType, sql, dialect, 위치)가 선언되고 루틴→CONTAINS→구문, 구문→READS/WRITES(op)→테이블·컬럼, JOINS(attrs.statement=구문 ID)가 생긴다.

### F-008-AC3 (대상: F-008-R3)
- Given `CURSOR c IS SELECT`, `FOR r IN (SELECT …) LOOP`, `OPEN c FOR SELECT`, `BULK COLLECT INTO`, `FORALL … UPDATE`, `RETURNING INTO`, `IF EXISTS (SELECT …)`, DB2 `DECLARE c CURSOR FOR SELECT`, `SET v = (SELECT …)`, `VALUES … INTO`, `FOR v AS c CURSOR FOR SELECT … DO`
- When 분석하면
- Then 각 자리의 쿼리가 구문 노드가 되고(커서는 SELECT 키워드 줄) 테이블 READS/WRITES가 기대와 같다.

### F-008-AC4 (대상: F-008-R4)
- Given 카탈로그에 없는 테이블 하나를 읽는 구문에서 매개변수, 지역 변수, 중첩 블록 변수, 루프 레코드의 필드(`r.col`), `%TYPE`·`%ROWTYPE` 변수, 패키지 명세의 상수를 조건에 쓰는 본문
- When 분석하면
- Then 변수 이름의 컬럼 노드, 레코드·커서 이름의 테이블 노드, `%TYPE`·`%ROWTYPE`이 가리키는 테이블로의 엣지가 없고, 실제 컬럼의 READS는 있다.

### F-008-AC5 (대상: F-008-R5)
- Given 프로시저 호출문(`sp_a(1)`, `pkg.b`), DB2 `CALL sp_c(?)`, 대입식의 함수 호출(`v := fn_x(1) + pkg.f(2)`), 내장 호출(`DBMS_OUTPUT.PUT_LINE`, `NVL`, `RAISE_APPLICATION_ERROR`), 정의되지 않은 식 안의 함수(`v := unknown_fn(1)`), 컬렉션 접근(`v_tab(i)`, `v_tab.COUNT`)
- When 분석하면
- Then 정의된 루틴과 호출문 형태의 미정의 이름으로만 루틴→CALLS→루틴 엣지가 생기고(미정의 대상은 declared=false), 내장 호출·식 안의 미정의 함수·컬렉션 접근은 엣지가 없다.

### F-008-AC6 (대상: F-008-R6)
- Given 패키지 본문에서 private 프로시저와 같은 패키지 함수를 이름만으로 부르고, 패키지 밖의 독립 프로시저도 부르며, 한 프로시저 안에 지역 프로시저가 중첩 선언돼 있음
- When 분석하면
- Then 같은 패키지 호출은 `db:routine:PKG.NAME`으로, 독립 프로시저는 `db:routine:NAME`으로 가고, 지역 서브프로그램의 노드는 없으며 그 본문의 SQL 구문과 호출은 바깥 루틴에서 나간다.

### F-008-AC7 (대상: F-008-R7)
- Given 본문 안의 `SELECT fn_x(a), get_amt(b) INTO … FROM t`(fn_x는 독립 함수, get_amt는 같은 패키지 함수)
- When 분석하면
- Then 그 구문 노드에서 `db:routine:FN_X`, `db:routine:PKG.GET_AMT`로 CALLS 엣지가 생긴다.

### F-008-AC8 (대상: F-008-R8)
- Given `EXECUTE IMMEDIATE 'DELETE FROM T1 WHERE ID = :1' USING v`, `v_sql := 'UPDATE T2 ' || 'SET X = 1'; EXECUTE IMMEDIATE v_sql;`, `OPEN c FOR 'SELECT * FROM T3'`, DB2 `PREPARE s FROM 'INSERT INTO T4 VALUES (1)'; EXECUTE s;`
- When 분석하면
- Then 각 지점에 구문 노드(attrs.dynamic=true)가 생기고 T1 WRITES(DELETE), T2 WRITES(UPDATE), T3 READS, T4 WRITES(INSERT)가 RESOLVED로 기록되며 `DYNAMIC_SQL` 진단은 없다.

### F-008-AC9 (대상: F-008-R9)
- Given `v_sql := 'INSERT INTO ' || v_table || ' SELECT * FROM TB_ORDER'; EXECUTE IMMEDIATE v_sql;`과 DB2 `SET V_SQL = 'DELETE FROM ' || P_TABLE_NM || ' WHERE …'; PREPARE S1 FROM V_SQL; EXECUTE S1;`
- When 분석하면
- Then 구문 노드(dynamic=true, sql에 `{*}`)에서 `unresolved:sql-dynamic:<구문 ID>`로 UNRESOLVED WRITES(op=INSERT / DELETE) 엣지가 하나 생기고 `DYNAMIC_SQL`(WARN) 진단이 남으며, 그 구문에서 `db:table:*`로 가는 엣지는 없고 루틴 attrs.dynamicSql=true다.

### F-008-AC10 (대상: F-008-R10)
- Given `:NEW.col`·`:OLD.col`을 쓰는 Oracle 행 트리거(DECLARE 있음), `REFERENCING NEW AS n OLD AS o … BEGIN ATOMIC … END`인 DB2 트리거
- When 분석하면
- Then 트리거→CONTAINS→구문→READS/WRITES가 생기고 `N`, `O`, `NEW`, `OLD` 이름의 테이블 노드나 그 필드의 컬럼 엣지는 없다.

### F-008-AC11 (대상: F-008-R11)
- Given SQL 파일의 `DECLARE v NUMBER; BEGIN FOR r IN (SELECT id FROM t1) LOOP UPDATE t2 SET x = v WHERE id = r.id; sp_a(r.id); END LOOP; END;`와 같은 본문의 내장 소스
- When 분석하면
- Then 블록마다 구문 노드는 하나이고 그 노드에서 T1 READS, T2 WRITES, `db:routine:SP_A` CALLS가 나가며, statementType은 첫 쓰기 구문(`UPDATE`)이고 `V`·`R` 이름의 컬럼·테이블은 없다.

### F-008-AC12 (대상: F-008-R12)
- Given END가 빠진 본문, 괄호가 닫히지 않은 구문, 모르는 구문이 낀 본문, 임의 위치에서 자르거나 글자를 망가뜨린 루틴 스크립트
- When 분석하면
- Then 예외나 무한 루프 없이 끝나고(ERROR 진단 없음), 깨진 곳 앞의 SQL 구문은 기록되며, 모르는 구문에는 `UNSUPPORTED_SYNTAX`가, 깨진 구문에는 `PARSE_FAILED`가 남고 같은 파일의 다른 루틴은 정상 분석된다.

### F-008-AC13 (대상: F-008-R13)
- Given PL/SQL 함수, 동적 SQL이 있는 DB2 프로시저, 명세에만 선언되고 본문이 없는 패키지 프로시저
- When 분석하면
- Then 루틴 노드에 language(`PLSQL`/`SQLPL`), statementCount, dynamicSql, returnType이 붙고 본문 없는 패키지 루틴은 bodyMissing=true다.

### F-008-AC14 (대상: F-008-R2, R5, R9)
- Given `samples/sample-shop`
- When `SqlAnalyzer`만으로 분석하면
- Then `expected/`의 루틴 관련 must-have 엣지(루틴 안 구문의 READS/WRITES, JOINS, 루틴→루틴 CALLS, TRIGGERS_ON)가 모두 있고, 루틴 관련 must-not 함정 위반이 없으며, `expected-unresolved.md`의 U5·U6이 미해결 엣지와 `DYNAMIC_SQL` 진단으로 남는다.

### F-008-AC15 (대상: F-008-R14)
- Given `TB_ORDER.ORDER_ID%TYPE`·`SHOP.TB_ORDER.ORDER_STATUS%TYPE` 매개변수, `tb_order%ROWTYPE`·`SHOP.TB_ORDER_ITEM%ROWTYPE` 변수, 커서 기준 `C%ROWTYPE`, 변수 기준 `V_ST%TYPE`·`R_ORD.ORDER_STATUS%TYPE`, 패키지 변수 기준 `PKG_T.G_LIMIT%TYPE`, 레코드 타입 필드의 `TB_MEMBER.MEMBER_NM%TYPE`이 있는 패키지 루틴과, 명세에만 있는 함수, 독립 함수, 트리거, 패키지 수준 변수
- When 분석하면
- Then 루틴 노드의 `typeRefs`가 나온 순서대로 `[TB_ORDER.ORDER_ID, TB_ORDER.ORDER_STATUS, TB_ORDER, TB_ORDER_ITEM, TB_MEMBER.MEMBER_NM]`이고(커서·변수·패키지 변수 기준은 없다), 명세에만 있는 함수·독립 함수·트리거에도 같은 방식으로 붙으며, 패키지 수준 선언의 것은 패키지 노드에 붙는다. 참조가 없는 루틴에는 키가 없다. 이 참조로는 테이블·컬럼 노드나 엣지가 생기지 않는다. 샘플의 `PKG_ORDER.CLOSE_ORDER`는 `[TB_ORDER.ORDER_STATUS]`, `FN_GET_CODE_NM`은 `[TB_CODE.CODE_NM]`이다.

### F-008-AC16 (대상: F-008-R15)
- Given PL/SQL의 `V_ID := SEQ_A.NEXTVAL;`, `V_ID := SHOP.SEQ_B.CURRVAL + 1;`, `IF SEQ_C.NEXTVAL > 10 THEN`, `SP_LOG(SEQ_KNOWN.NEXTVAL);`, 선언 초기값 `V_INIT NUMBER := SEQ_INIT.NEXTVAL;`, 레코드 필드 `R.NEXTVAL`과, SQL PL의 `SET V_ID = NEXT VALUE FOR SEQ_SETTLE_ID;`, `SET V_PREV = PREVIOUS VALUE FOR BATCH.SEQ_SETTLE_ID;`, `SET V_ID = NEXTVAL FOR SEQ_OLD;`, `VALUES NEXT VALUE FOR SEQ_LOG INTO V_ID;`, 익명 블록의 `V := SEQ_BLOCK.NEXTVAL;`
- When 분석하면
- Then 각 문장의 줄에 `sql:stmt:<루틴>:<줄>` 노드(`statementType=OTHER`, `sequence=true`, `sql`은 대입문이면 문장 원문, `routine`)와 루틴→CONTAINS가 생기고, 그 노드에서 `db:sequence:<이름>`(스키마 제거)으로 `READS`(`sequence=true`, `RESOLVED`)가 나간다. `R.NEXTVAL`로는 아무것도 생기지 않고, 익명 블록에서는 블록의 구문 노드에서 `READS`가 나간다. 루틴의 `statementCount`는 이 노드도 센다. 샘플 `sp_settle_monthly.db2:62`가 `sql:stmt:SP_SETTLE_MONTHLY:62` → `db:sequence:SEQ_SETTLE_ID`로 남는다.

### F-008-AC17 (대상: F-008-R16)
- Given DDL이 있는 `TB_A`·`TB_C`·`TB_LOG`와 DDL이 없는 `TB_X`를 쓰는 루틴들: (가) DB2 `FOR R AS SELECT A.ID AS SRC_ID, A.NM, A.ST AS STATUS_CD, UPPER(A.NM) AS UP_NM FROM TB_A A … DO … END FOR`와 커서 이름을 준 `FOR ROW2 AS C2 CURSOR WITH HOLD FOR SELECT C.C_ID, C.A_ID AS PARENT_ID …`의 본문에서 `R.UP_NM`, `SRC_ID`, `STATUS_CD`, `NM`, `PARENT_ID`를 쓰는 UPDATE·INSERT·DELETE, 루프가 끝난 뒤 `STATUS_CD`를 쓰는 UPDATE, `FOR R AS SELECT * FROM TB_A`와 `SELECT * FROM TB_NO_DDL`, (나) PL/SQL의 `FOR R IN 커서 LOOP`·`FOR Q IN (SELECT …) LOOP`의 `R.NM`, `%ROWTYPE`·`RECORD`·커서 `%ROWTYPE` 변수의 필드, `RETURNING … INTO`·`BULK COLLECT INTO`·`FORALL`, `SQL%ROWCOUNT`·`SQL%NOTFOUND`·`SQLERRM`·`SQLCODE`, (다) DB2의 `DECLARE SQLCODE`·`SQLSTATE`, `DECLARE … CONDITION FOR SQLSTATE`, 핸들러, `GET DIAGNOSTICS`, `SIGNAL 조건`, `CURRENT USER`·`DAYS(CURRENT DATE)`, `REFERENCING NEW AS N OLD AS O` 트리거와 Oracle 트리거의 `:NEW`·`:OLD`·`INSERTING`·`UPDATING('ST')`·`DELETING`, (라) `A.ST = R.ST`처럼 레코드 필드와 비교한 조인 구문
- When 분석하면
- Then 진단이 없고 테이블은 넷뿐이며, `TB_X`의 컬럼 노드는 구문이 실제로 쓴 컬럼뿐이다(변수 이름의 컬럼, `R`·`C2`·`V_ROW`·`N`·`O` 이름의 테이블이 없다). DB2 `FOR` 본문에서 대상 테이블에 같은 이름의 컬럼이 있으면 컬럼이 이기고(`TB_C.NM`), 루프가 끝난 뒤의 `STATUS_CD`는 `TB_X`의 컬럼이다. DDL을 아는 테이블의 `*`는 그 테이블의 컬럼이 모두 변수이고, 모르는 테이블의 `*`를 도는 루프 안에서는 DDL에 없는 이름으로 컬럼도 진단도 만들지 않는다. PL/SQL 커서 FOR 루프에서는 선택 목록의 이름이 변수가 아니어서 `TB_X.NM`·`TB_X.CID`가 컬럼이다. (라)의 `JOINS`에는 `rightFixedValues=[ST=?]`가 붙는다.

### F-008-AC18 (대상: F-008-R17)
- Given DDL이 있는 테이블 하나를 읽으며 DDL에 없는 `DEL_YN`·`USE_AT`·`SORT_NO`·`SEQ_NO`를 조건·정렬에 쓰고 `UPD_ID`를 SET하는 프로시저(매개변수·지역 변수·루프 레코드와 함께), 두 테이블을 조인하고 DDL에 없는 `GHOST_COL`을 쓴 구문, 선언을 볼 수 없는 패키지 변수에 `:=`와 `INTO`로 값을 넣고 다음 구문에서 쓰는 패키지 본문, 선언부가 깨져 변수 하나를 놓친 프로시저
- When 분석하면
- Then 테이블이 하나인 구문의 DDL에 없는 이름은 그 테이블의 `declared=false` 컬럼이 되어 구문 노드에서 `READS`/`WRITES`가 나가고, 조인 구문에는 `UNKNOWN_COLUMN`(INFO) 1건(`GHOST_COL`, 후보 `TB_A, TB_C`)만 남는다. 변수·매개변수 이름의 컬럼은 없다. 본문에서 값을 받은 이름은 변수라 컬럼도 진단도 없다. 본문 파서가 문제를 남긴 루틴은 `PARSE_FAILED`가 남고 그 본문의 구문에서는 DDL에 없는 이름으로 컬럼도 `UNKNOWN_COLUMN`도 만들지 않는다(테이블 `WRITES`는 그대로다).

## 5. 가정
- **`%TYPE`·`%ROWTYPE` 참조(F-008-R14)**: 루틴 단위의 토큰에서 `이름%TYPE`·`이름%ROWTYPE`을 찾는다(헤더의 매개변수·반환 타입, 선언부, 중첩 블록, 레코드 타입의 필드, 커서 매개변수 모두). `%TYPE`은 두 조각 `테이블.컬럼`과 세 조각 `스키마.테이블.컬럼`, `%ROWTYPE`은 한 조각 `테이블`과 두 조각 `스키마.테이블`만 받는다. 첫 조각이 그 루틴에서 선언된 이름(매개변수, 변수, 커서, 루프 변수, 패키지 수준 변수·커서)이거나 `:NEW`·`:OLD`면 뺀다. 첫 조각이 카탈로그의 패키지 이름이면 패키지 변수·커서로 보고 뺀다(같은 이름의 테이블이 카탈로그에 있으면 테이블로 본다). 테이블인지 뷰인지, DDL에 있는지는 따지지 않는다(이름만 적는다). 패키지 루틴은 오버로드를 한 노드에 모으고, 명세에만 있는 루틴(`bodyMissing`)은 명세의 것을 적는다. 패키지 수준 선언의 참조는 패키지 노드에 명세와 본문 것을 합쳐 적는다. DB2의 `ANCHOR [DATA TYPE] TO`·`ANCHOR ROW OF`는 보지 않는다.
- **식 안의 시퀀스(F-008-R15)**: 본문 파서가 식으로 읽은 모든 자리(대입식, IF·WHILE 조건, 호출 인자, RETURN 값, 선언 초기값, 트리거의 WHEN 조건)의 토큰에서 찾는다. 식 안의 서브쿼리는 그 구문이 따로 기록하므로 건너뛴다. `NEXT VALUE FOR`·`PREVIOUS VALUE FOR`(DB2의 옛 표기 `NEXTVAL FOR`·`PREVVAL FOR` 포함)는 문법만으로 시퀀스다. `x.NEXTVAL`·`x.CURRVAL`은 첫 조각이 스코프의 변수·레코드가 아니면 시퀀스로 본다(SQL 구문 안의 `x.NEXTVAL`을 F-006이 다루는 방식과 같다. 카탈로그에 시퀀스가 없어도 `declared=false` 참조로 남는다). 구문 노드의 줄은 대입문이면 문장의 첫 줄, 그 밖에는 식의 첫 줄이고, `sql`은 대입문이면 문장 원문(`SET V = NEXT VALUE FOR SEQ`), 그 밖에는 식의 원문이다. 한 식에 시퀀스가 여럿이면 노드 하나에서 READS가 여럿 나간다. 익명 블록에서는 구문 노드를 따로 만들지 않고 블록의 주인 노드에서 READS가 나간다.
- **호출 판정**: (1) 지역 서브프로그램 이름이면 엣지 없음(같은 루틴 안). (2) 패키지 본문 안의 한 조각 이름이 그 패키지의 루틴이면 `db:routine:<패키지>.<이름>`. (3) 카탈로그에 정의된 루틴이면 그 ID. (4) 정의돼 있지 않고 알려진 내장(`DBMS_*`, `UTL_*`, `APEX_*`, `SYSPROC.*`, `SYSIBM.*`, `SYSFUN.*`, `RAISE_APPLICATION_ERROR` 등)이면 무시. (5) 그 밖에는 **프로시저 호출문 형태(문장 자리의 호출, `CALL`)만** `reference`(declared=false)로 CALLS를 만든다. 식 안의 미정의 함수는 내장 함수, 컬렉션 접근(`v_tab(i)`), 형 변환과 구분할 수 없으므로 엣지를 만들지 않는다. 이름의 첫 조각이 변수·커서면(`v_tab.DELETE`, `rec.field`) 호출이 아니다.
- 두 부분 이름의 ID는 F-006과 같다(`SqlIds.routineOfCall`: 패키지 → 스키마 → 모르면 패키지로 본다).
- CALLS 엣지는 호출 지점마다 하나(위치 = 호출 이름)이고 `attrs.callType`은 `procedure`(호출문) 또는 `function`(식 안)이다. 자기 자신을 부르면 self-loop이다.
- **구문 ID의 줄**: SQL 키워드(SELECT/INSERT/UPDATE/DELETE/MERGE/WITH/VALUES)가 있는 줄이다. 커서 선언과 `FOR … IN (SELECT`, `OPEN … FOR SELECT`는 그 안의 SELECT 줄, 동적 SQL은 `EXECUTE IMMEDIATE`/`PREPARE`/`OPEN … FOR`/`DBMS_SQL.PARSE` 문장의 첫 줄이다. 한 루틴의 같은 줄에 구문이 둘이면 한 노드로 합쳐진다(F-006과 같은 한계).
- 구문 노드의 `name`은 `<루틴의 짧은 이름>:<줄>`(패키지 소속이어도 패키지 이름 없이), `qualifiedName`은 패키지 소속이면 `<패키지>.<루틴>:<줄>`, `attrs.sql`은 공백을 정리한 원문(2000자 상한)이다. 동적 SQL은 풀린 문자열을, 풀리지 않으면 조각 사이에 `{*}`를 넣은 문자열을 적는다.
- **동적 SQL 풀기**: 문자열 리터럴, 리터럴끼리의 `||`(DB2 `CONCAT`), 괄호, 그리고 변수는 "그 루틴 안에서 대입(`:=`, `SET`, 선언 초기값)이 정확히 한 번이고 그 값이 다시 완전히 풀리는 것"만 푼다. 매개변수, 대입이 두 번 이상인 변수, 함수 호출 결과는 풀지 않는다. 흐름(분기, 루프)은 보지 않는다.
- **풀리지 않은 동적 SQL의 리터럴 조각**: 조각 안에 테이블 자리가 완전한 형태로 있어도(`' SELECT * FROM TB_ORDER WHERE …'`) 테이블 엣지를 만들지 않는다. 샘플 정답(`expected-unresolved.md` U5)이 이 경우를 "단정하지 않는다"로 두었기 때문이다. 조각은 구문 노드의 `sql`에 남는다. 미해결 엣지의 종류는 첫 리터럴의 첫 키워드로 정한다(INSERT/UPDATE/DELETE/MERGE → WRITES + op, 그 밖에는 READS).
- DB2 `PREPARE s FROM <식>`이 구문 노드를 만들고 `EXECUTE s`, `OPEN c`(c가 `CURSOR FOR s`)는 노드를 따로 만들지 않는다.
- **암묵 변수(F-008-R16)**: DB2 `FOR` 문의 선택 목록 이름은 `FactExtractor.outputColumns`(뷰 컬럼을 유도하는 것과 같은 규칙)로 얻는다: 별칭, 별칭이 없으면 단순 컬럼 이름이고 식뿐인 항목은 이름이 없다. `*`는 카탈로그가 그 테이블의 컬럼을 알 때만 펼친다. 이름을 다 알 수 없으면(컬럼을 모르는 테이블의 `*`, 해석하지 못한 쿼리) 그 루프의 스코프를 "열린 스코프"로 표시한다(`VarScope.markOpen`). 이 이름들은 루프 본문에서만 변수다(쿼리 자신과 루프가 끝난 뒤에는 아니다). DB2의 이름 해석 순서와 같이, 구문의 테이블에 같은 이름의 컬럼이 확실히 있으면 컬럼이 이긴다(F-006 규칙). 본문에서 값을 받는 이름은 본문을 걷기 전에 한 번 모아 가장 바깥 스코프에 넣는다(`BodyWalker.assignedNames`: 대입문 대상의 첫 조각, `INTO` 대상, `FETCH … INTO` 대상). 다른 파일의 패키지 명세에만 선언돼 있어 선언을 보지 못한 패키지 변수가 이 경우다. 레코드 필드를 상수로 보는 것(`FactExtractor.constant`)은 첫 조각이 변수이고 그 이름이 구문의 릴레이션(테이블 별칭)이 아닐 때다.
- **루틴 본문의 DDL에 없는 컬럼(F-008-R17)**: 본문 파서(`RoutineParser`)가 구조 문제를 하나라도 남긴 본문(선언을 해석하지 못함, BEGIN/END 짝이 어긋남, 종결자 누락)은 변수 스코프를 믿지 않는다(`BodyWalker.scopeReliable`). SQL 구문 하나의 파싱 문제는 그 구문에만 영향을 준다(F-006-R31: 큰 문제가 남은 구문에서는 추측하지 않는다). 패키지 본문은 멤버마다 따로 본다. 믿을 수 없는 자리에서는 `declared=false` 참조도 `UNKNOWN_COLUMN`도 만들지 않는다(전에는 진단을 남겼다. 본문에 이미 `PARSE_FAILED`가 남는다). 익명 블록(SQL 파일, 내장 소스)도 같다.
- **변수 스코프**: 서브프로그램, DECLARE 블록, DB2 복합문, FOR·FORALL 루프, 핸들러마다 스코프를 만든다. 패키지 명세·본문의 패키지 수준 변수·상수·커서는 그 패키지의 모든 루틴에서 보인다(명세와 본문이 다른 파일이어도). 변수라도 카탈로그상 그 테이블의 컬럼이 확실하면 컬럼이 이긴다(F-006 규칙).
- **방언(문법) 판정**: 헤더와 본문 사이에 `IS`/`AS`가 있으면 PL/SQL, 없으면 SQL PL이다. 트리거·익명 블록은 `BEGIN ATOMIC`, `REFERENCING`, `DECLARE … HANDLER/CURSOR FOR`, `MODE DB2SQL`이 있거나 파일 방언이 DB2이면서 `:=`가 없으면 SQL PL이다. `attrs.language`가 이 값이고, 구문 노드의 `dialect`는 파일 방언(F-006 규칙)이다.
- 패키지 초기화부(`BEGIN … END pkg`)의 SQL 구문과 호출은 패키지 노드를 주인으로 기록한다(`DB_PACKAGE -CONTAINS→ SQL_STATEMENT`, `DB_PACKAGE -CALLS→ ROUTINE`, 구문 ID는 `sql:stmt:<패키지>:<줄>`).
- 오버로드된 패키지 루틴은 F-007대로 한 노드이고 본문들의 구문과 호출이 그 노드에 모인다.
- 익명 블록: 주인은 블록의 구문 노드(SQL 파일이면 `sql:stmt:<파일>:<줄>`, 내장 소스면 생산자가 만든 노드) 하나다. 안의 구문 사실을 합쳐 주인에서 READS/WRITES/JOINS/CALLS를 낸다. statementType은 F-006 규칙(첫 쓰기 구문 → CALL → SELECT → OTHER)이다. 익명 블록의 호출 판정은 루틴과 같되 미정의 호출문은 F-006처럼 참조로 CALLS를 만든다.
- 진단: 루틴 본문의 SQL 구문에서 파서가 복구한 문제는 구문마다 `PARSE_FAILED`(WARN) 하나, 본문 파서가 건너뛴 문장은 `UNSUPPORTED_SYNTAX`(INFO) 하나다. 내장 소스의 익명 블록에서는 F-006처럼 가벼운 문제를 남기지 않는다.
- `LOCK TABLE`, `TRUNCATE`(동적 SQL 안)는 F-006 규칙대로 기록한다. `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `SET TRANSACTION`은 구문 노드를 만들지 않는다.
- DB2 `DECLARE GLOBAL TEMPORARY TABLE`은 본문에서 건너뛴다(세션 테이블은 카탈로그에 넣지 않는다). 그 테이블을 읽고 쓰는 구문은 `db:table:<이름>` 참조(declared=false)로 기록된다.
- 트리거의 전이 테이블(`REFERENCING NEW TABLE AS nt`, `NEW_TABLE`, `OLD TABLE`, `OLD_TABLE`)은 FROM에 와도 테이블 엣지를 만들지 않는다(컬럼을 알 수 없는 릴레이션으로 다룬다).
- BEGIN이 없는 트리거 본문(DB2의 단일 문장, Oracle의 `CALL proc(..)`)은 `FOR EACH ROW|STATEMENT [MODE DB2SQL] [WHEN (..)]` 다음을 문장 하나로 읽는다. 트리거 헤더의 `WHEN (조건)` 안의 서브쿼리와 함수 호출도 본다.
- 문장 키워드와 이름이 같은 사용자 프로시저 호출(`PREPARE(1);`, `LOCK(4);`처럼 괄호가 바로 뒤따르는 것)은 호출문으로 읽는다.
- PL/SQL의 `UPDATE t SET ROW = 레코드`에서 `ROW`는 컬럼으로 기록하지 않는다(테이블 WRITES만).
- 루틴 노드의 `parameters`는 `이름 모드 타입` 순서로 맞춘다(DB2의 `IN P_ID INT` → `P_ID IN INT`). 모드가 없으면 `이름 타입`이다.
- 본문 분석을 위해 F-006·F-007의 코드를 고친 곳: 렉서가 `L1:LOOP`(레이블이 블록 키워드에 붙은 꼴)의 `:LOOP`를 바인드로 읽지 않는다. 블록 깊이 추적(`BlockTracker`)이 괄호 안의 `;`에서 괄호 수를 0으로 되돌린다(괄호가 안 닫힌 패키지 멤버가 뒤의 멤버를 삼키지 않게). `SqlParser`의 익명 블록 처리가 본문 파서에 위임한다.
- (2026-10-11 공개 프로젝트 검증 후속) 본문의 SQL 구문 하나로 넘어온 구간에 종결자 없이 구문이 연달아 들어 있으면(`UPDATE T1 SET A = 1` 다음 줄의 `DELETE FROM T2;`) SQL 파서가 구문 경계에서 잘라 `Statement.Sequence`로 돌려주고, 사실은 그 구간의 구문 노드 하나에 합쳐진다(F-006-R16). 전에는 뒤 구문이 "해석하지 못한 토큰"으로 버려졌다.
- (같은 후속) SQL 파일의 데이터 적재 INSERT 묶기(F-006-R15)는 루틴 본문과 익명 블록에는 적용하지 않는다. 본문의 INSERT는 리터럴뿐이어도 구문마다 노드다(`DataLoadTest.embeddedAndRoutineInsertsAreNotGrouped`).
- (같은 후속) 본문의 SQL에서도 키워드와 같은 이름의 테이블·별칭을 받는다(F-006-R17). 다만 DB2 `FOR v AS c CURSOR FOR SELECT … DO`의 `DO`와 PL/SQL 커서 FOR 루프의 `LOOP`는 본문 파서가 쿼리의 끝으로 먼저 자르므로 그 자리의 별칭으로는 쓸 수 없다.

### 알려진 한계
- 동적 SQL의 값 전파는 한 루틴 안의 단일 대입만 본다. 다른 루틴에서 조립해 넘긴 문자열, 조건에 따라 달라지는 문자열, 패키지 수준 상수에 담은 문자열은 미해결이다. `SELECT .. INTO`, `FETCH .. INTO`의 대상이 된 변수는 대입으로 세지만, 프로시저의 OUT 인자로 바뀌는 값은 보지 않는다.
- (해소됨, F-008-R15) ~~식 안의 시퀀스 참조(`v := seq.NEXTVAL`, `SET v = NEXT VALUE FOR seq`)는 SQL 구문이 아니어서 기록하지 않는다.~~ 구문 노드(`sequence=true`)와 시퀀스 READS로 기록한다. 본문 파서가 식으로 읽지 않는 자리(`SET CURRENT SCHEMA` 같은 레지스터 대입, 건너뛴 문장)의 시퀀스는 여전히 보지 않는다.
- 패키지 멤버의 경계는 F-007의 헤더 스캔을 따른다. 본문이 심하게 깨져 블록 깊이가 어긋나면 뒤따르는 멤버의 구문이 앞 멤버에 귀속될 수 있다(구문 자체는 건진다).
- `DBMS_SQL.PARSE(cursor, <식>, …)`는 둘째 인자만 본다.
- Oracle 복합 트리거(COMPOUND TRIGGER)의 시점 블록, 객체 타입 메서드(`TYPE BODY`), `CALL` 사양(`LANGUAGE JAVA`)은 본문을 분석하지 않는다.
- 레이블이나 루틴 이름으로 한정한 변수(`proc.var`)는 변수로 본다(한정자가 테이블 별칭이 아닐 때 F-006이 컬럼으로 만들지 않는다).
- 식 안에서 괄호 없이 부른 함수(`v := fn_now;`)는 카탈로그에 그 이름의 루틴이 있고 같은 이름의 변수가 없을 때만 호출로 본다.
- DB 링크를 붙인 호출(`proc@link`)은 링크를 뗀 이름으로 기록한다.
- (F-008-R17의 한계) 선언을 볼 수 없고 값을 받지도 않는 이름은 변수인지 알 수 없다: 분석 대상에 명세가 없는 패키지의 **상수**를 본문에서 이름만으로 읽은 것, 괄호 없이 부른 미정의 함수, DB2 전역 변수(`CREATE VARIABLE`)를 이름만으로 읽은 것. 이런 이름이 DDL이 있는 테이블 하나를 읽는 구문에 있으면 그 테이블의 `declared=false` 컬럼이 된다(테이블이 여럿이면 `UNKNOWN_COLUMN`). 명세와 본문이 함께 있으면 생기지 않는다.
- DB2 `FOR` 문의 선택 목록이 `*`이고 그 테이블의 DDL이 없으면 루프의 컬럼 변수 이름을 알 수 없다. 그 본문에서 DDL이 **없는** 테이블 하나를 읽는 구문은 그 이름을 여전히 그 테이블의 컬럼으로 읽는다(F-006의 "카탈로그가 모르는 릴레이션이 하나뿐" 규칙).

## 6. 미확정 사항
- 없음(사용자가 기본값으로 진행하도록 위임했다. 5절의 가정은 검토 대상이다).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: [0004](../../decisions/0004-hand-written-tolerant-sql-parser.md)
- 앞선 기능: [F-006](../F-006-sql-parser/spec.md), [F-007](../F-007-ddl-parser/spec.md)

## 8. 계약 추가 제안 (F-001 design.md에 반영 요청)
design.md 반영 요청(2026-10-11, 대상 방언 점검 2차. ~~아직 반영 전~~. F-006 spec 8절의 같은 날짜 항목과 한 묶음이다):
design.md 반영됨(2026-10-11): 루틴 본문의 `declared=false` 컬럼과 `UNKNOWN_COLUMN`은 3.4 "DDL에 없는 컬럼 참조"와 3.7, 루틴 본문의 변수는 3.4(새 항목), 레코드 필드와 비교한 고정 값은 3.5 공통 규칙 "`JOINS`의 고정 컬럼"에 넣었다.
- `declared=false` 컬럼 참조와 `UNKNOWN_COLUMN`(3.4·3.5·3.7): 루틴 본문과 익명 블록의 구문도 매퍼 구문과 같은 규칙이다(테이블이 하나면 참조, 여럿이면 진단). 변수 스코프를 다 알지 못하는 자리(본문에 `PARSE_FAILED`가 남은 루틴, 컬럼을 모르는 테이블의 `*`를 도는 DB2 `FOR` 본문)에서는 둘 다 만들지 않는다.
- 3.4 "루틴 본문의 변수": DB2 `FOR v AS SELECT …`의 선택 목록 이름과, 선언을 보지 못했어도 본문에서 값을 받는 이름(대입, `INTO`)은 변수다. 레코드 필드와 비교한 컬럼은 `JOINS`의 고정 값에 `COL=?`로 남는다.

2026-10-11 감사 후속으로 추가한 것. design.md에 반영됨(2026-10-11): `typeRefs`는 3.4(`%TYPE` 규칙)와 3.5(DB_ROUTINE·DB_PACKAGE), 식 안의 시퀀스는 3.3(구문 ID의 줄)·3.4(`READS`)·3.5(SQL_STATEMENT `sequence`, `statementCount`), 남은 한계는 7절에 넣었다.
- DB_ROUTINE·DB_PACKAGE attrs `typeRefs`(List): `%TYPE`·`%ROWTYPE`이 가리키는 `테이블.컬럼` 또는 `테이블`(스키마 제거·대문자, 나온 순서, 중복 없음). 없으면 키를 넣지 않는다. 3.4의 "`%TYPE`·`%ROWTYPE`이 가리키는 테이블 …로는 엣지를 만들지 않는다"에 "대신 루틴 노드의 `typeRefs`에 이름을 남긴다"를 덧붙인다. 컬럼 자료형 변경의 영향 범위를 볼 때 쓴다(엣지 종류를 새로 만들지는 않았다).
- SQL_STATEMENT attrs `sequence`(boolean): 루틴 본문의 SQL 구문이 아닌 문장(대입문 등)에서 식 안의 시퀀스 참조만으로 만든 구문 노드. `statementType=OTHER`다. 3.3의 "루틴 본문의 구문" 행의 줄 규칙에 "식 안의 시퀀스 참조만 있는 문장은 그 문장(대입문) 또는 식의 첫 줄"을, 3.4 `READS` 행의 시퀀스 설명에 "루틴 본문의 식 안 참조 포함"을 덧붙인다.
- DB_ROUTINE attrs `statementCount`는 위의 시퀀스 구문 노드도 센다.

2026-10-11 문서 정리에서 아래 제안을 코드와 대조해 F-001 design.md 3.3~3.7에 반영했다(보류 항목은 design 7절에 미구현으로 적었다). 대조하며 고친 것: 구문 노드의 `name`은 루틴의 짧은 이름을 쓰고 전체 이름은 `qualifiedName`에 든다. `cursor`는 커서 선언뿐 아니라 `OPEN c FOR SELECT`와 이름 있는 커서 FOR 루프의 쿼리에도 붙는다.

- DB_ROUTINE attrs: `language`(`PLSQL`|`SQLPL`), `returnType`(함수), `statementCount`(본문에서 만든 구문 노드 수), `dynamicSql`(boolean, 동적 SQL 지점이 있음), `bodyMissing`(boolean, 패키지 명세에만 있고 본문이 없음).
- SQL_STATEMENT(루틴 안): `name`은 `<루틴의 짧은 이름>:<줄>`, attrs `routine`(주인 노드 ID. 패키지 초기화부면 패키지 ID), 커서 선언이면 `cursor`(커서 이름), 동적 SQL이면 `dynamic=true`와 `dynamicKind`(`EXECUTE IMMEDIATE`, `OPEN FOR`, `PREPARE`, `DBMS_SQL`).
- CALLS(ROUTINE→ROUTINE) attrs: `callType` 값에 `procedure`(호출문), `function`(식 안의 호출) 추가.
- CONTAINS: `DB_PACKAGE→SQL_STATEMENT`, CALLS: `DB_PACKAGE→ROUTINE`(패키지 초기화부와 패키지 수준 선언의 초기값·커서 쿼리).
- DB_ROUTINE attrs `parameters`의 원소 형식: `이름 [모드] 타입`.
- UNRESOLVED(`sql-dynamic`)로 가는 엣지: 루틴의 동적 SQL에서도 같은 분류를 쓴다. attrs.text는 `{*}`가 든 SQL 조각.
- (보류) 풀리지 않은 동적 SQL의 리터럴 조각에서 읽은 테이블을 `INFERRED` 엣지(attrs.partial=true)로 남기는 안: 샘플 정답이 판단을 미뤄 구현하지 않았다. 넣기로 정하면 `{*}` 자리를 F-006의 `__DYNAMIC__` 식별자로 바꿔 파싱하는 방법으로 구현할 수 있다.
