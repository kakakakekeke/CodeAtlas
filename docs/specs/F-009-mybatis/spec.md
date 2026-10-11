---
type: spec
id: F-009
title: MyBatis 매퍼 분석
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-009 MyBatis 매퍼 분석

## 1. 배경
콜트리가 Java에서 SQL과 테이블까지 이어지려면(PRD 목표 1) 매퍼 XML의 구문이 그래프 노드로 있어야 하고, 그 구문의 SQL이 SQL 분석기(F-006)가 읽을 수 있는 텍스트로 전개되어 있어야 한다. 이 기능은 매퍼 XML을 해석해 구문·조각 노드를 만들고, 동적 SQL을 테이블·컬럼·조인 정보를 빠뜨리지 않는 텍스트로 전개해 `sql` 내장 소스로 넘긴다.

## 2. 범위
- 포함: 매퍼 XML(루트 `mapper`) 해석, 구문·조각 노드와 `CONTAINS`·`INCLUDES`·`USES_TYPE` 엣지, 동적 SQL 전개와 `sql` 내장 소스 등록, 타입 별칭 해석(`mybatis-config.xml`, Spring 설정, `application.properties/yml`), resultMap의 타입과 컬럼 대응.
- 제외: SQL 본문 파싱(F-006. `READS`, `WRITES`, `JOINS`는 sql 모듈이 구문 노드에 붙인다), 매퍼 인터페이스 메서드 ↔ 구문 연결(`EXECUTES`, F-012), 애너테이션 SQL(`@Select` 등, F-004), iBatis 2.x `sqlMap`, OGNL 조건식 평가(예외: `<if>`와 `<when>`의 "식 == 리터럴" 꼴만 읽어 전개본을 나눈다, F-009-R14·R23), `<cache>`·`<cache-ref>`·`<parameterMap>`.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-009-R1 | 시스템은 매퍼 파일마다 `MYBATIS_MAPPER` 노드를, `<select\|insert\|update\|delete>`마다 `SQL_STATEMENT` 노드를, `<sql>`마다 `SQL_FRAGMENT` 노드를 그래프 계약의 ID로 만들고 `FILE→MAPPER`, `MAPPER→구문·조각`을 `CONTAINS`로 이어야 한다. | design 3.3, 3.4 |
| F-009-R2 | 시스템은 구문 노드에 `namespace`, `statementId`, `statementType`, `sql`, `dynamic` 속성을 채워야 한다. `statementType`은 태그 기준이고, `statementType="CALLABLE"`이거나 본문이 `{call`(또는 `CALL`)로 시작하면 `CALL`, 본문이 `MERGE INTO`로 시작하면 `MERGE`다. `sql`은 대표 전개본을 공백 정규화해 2000자까지 담는다. `namespace`와 `statementId`를 정하는 규칙은 F-009-R13에 있다. | design 3.5, 사용자 요청 |
| F-009-R3 | 시스템은 구문 본문을 주인이 그 구문 노드인 `sql` 내장 소스로 등록해야 한다. 동적 태그와 include가 없는 구문은 길이를 보존하는 치환을 써서 `exactPositions=true`와 본문의 정확한 시작 위치로 등록하고, 그 밖의 구문은 `exactPositions=false`와 구문 요소의 시작 위치로 등록한다. XML 주석 안의 내용은 넣지 않고 CDATA와 XML 엔티티는 풀어서 넣는다. `<bind>`만 있는 구문은 F-009-R17을 따른다. | design 4.1, 사용자 요청 |
| F-009-R4 | 시스템은 동적 태그를 조건 평가 없이 전개해야 한다. 전개본 1번은 모든 `<if>` 본문을 포함하고(조건이 "식 == 리터럴" 꼴인 `<if>`는 F-009-R14) `<choose>`는 첫 `<when>`을 택한다. `<where>`, `<set>`, `<trim>`은 접두·접미와 덮어쓰기 규칙을 적용하고, `<foreach>`는 `open + 본문 한 번 + close`로, `<bind>`는 무시한다. | 사용자 요청 |
| F-009-R5 | `<choose>`에 분기가 둘 이상 있으면, 시스템은 첫 `<when>`이 아닌 분기마다 그 분기만 바꾼 전개본을 하나씩 더 등록해야 한다. 만약 한 구문 정의의 전개본이 16개를 넘으면, 시스템은 16개까지만 등록하고 진단을 남겨야 한다. | 사용자 요청 |
| F-009-R6 | 시스템은 `#{...}`를 `?`로, `${...}`를 식별자 `__DYNAMIC__`으로 치환해야 한다. `${...}`가 남은 구문은 `dynamic=true`로 표시하고 `INFO` 진단 `DYNAMIC_SQL`을 구문 위치에 남긴다. | design 4.1, 사용자 요청 |
| F-009-R7 | `<include refid>`가 있으면, 시스템은 조각 본문을 그 자리에 전개하고 `INCLUDES` 엣지(구문 또는 조각 → 조각)를 만들어야 한다. refid에 `.`이 있으면 다른 namespace의 조각으로 보고, 모든 매퍼 파일을 읽은 뒤에 해석한다. `<property>`로 값이 정해진 `${name}`은 그 값으로 치환한다. 만약 조각을 찾지 못하거나 include가 순환하면, 시스템은 그 자리를 비우고 미해결 엣지 또는 진단을 남겨야 한다. | design 3.4, 사용자 요청 |
| F-009-R8 | `<selectKey>`가 있으면, 시스템은 그 본문을 구문 본문에서 빼고 같은 주인 노드의 별도 내장 소스(`attrs.selectKey=true`)로 등록해야 한다. | 사용자 요청 |
| F-009-R9 | 같은 namespace에 같은 id의 구문이 `databaseId`만 달리해 여러 번 나오면, 시스템은 노드를 하나만 만들고 정의마다 전개본을 등록하되 내장 소스 `attrs.dialect`에 `databaseId` 값을 넣어야 한다. | 사용자 요청 |
| F-009-R10 | 시스템은 구문의 `parameterType`, `resultType`, `resultMap`이 가리키는 Java 타입으로 `USES_TYPE` 엣지(`attrs.role` = `parameter`, `result`, `resultMap`)를 만들어야 한다. 타입 이름은 MyBatis 내장 별칭(JDK 타입, 엣지 없음), `<typeAlias>`, 별칭 패키지(`<package>`, Spring `typeAliasesPackage`, `mybatis.type-aliases-package`), FQN 순으로 해석한다. 어느 것도 아니면 프로젝트 Java 파일 중 단순 이름이 유일하게 일치하는 타입으로 `INFERRED` 엣지를, 없으면 미해결 엣지를 남긴다. | design 3.4, 사용자 요청 |
| F-009-R11 | 구문이 `resultMap`을 쓰면, 시스템은 그 resultMap(상속한 것 포함)의 `type`과 `association`·`collection`의 `javaType`·`ofType`으로 `USES_TYPE` 엣지를 만들고, 컬럼→프로퍼티 대응과 중첩 select가 가리키는 구문을 구문 노드의 속성으로 남겨야 한다. resultMap 자체의 노드는 만들지 않는다. | 사용자 요청 |
| F-009-R12 | 만약 매퍼 XML이 올바른 XML이 아니면, 시스템은 `PARSE_FAILED` 진단을 남기고 오류 지점 전에 끝난 구문만 등록한 뒤 다음 파일로 가야 한다. 시스템은 DTD나 외부 엔티티를 네트워크·파일에서 읽지 않아야 한다. 루트가 `sqlMap`인 파일은 `INFO` 진단 `UNSUPPORTED_SYNTAX` 한 건만 남긴다. | AGENTS.md(파일 하나의 실패가 전체를 멈추지 않는다), PRD 7(폐쇄망, iBatis 제외) |
| F-009-R13 | 구문·조각·resultMap의 `id`가 `<namespace>.`로 시작하면, 시스템은 namespace를 다시 붙이지 않고 그 `id`를 한정된 ID로 써야 한다(MyBatis `MapperBuilderAssistant.applyCurrentNamespace`와 같다). 노드의 `statementId`·`fragmentId`와 이름은 namespace를 뗀 짧은 id다. `refid`·`resultMap`·`extends` 참조는 점이 있으면 그대로, 없으면 namespace를 붙여 찾는다. 만약 `id`에 점이 있는데 namespace로 시작하지 않으면, 시스템은 원문을 그대로 한정된 ID로 쓰고 `WARN` 진단 `PARSE_FAILED`를 남겨야 한다. | 공개 프로젝트 검증(전자정부 템플릿: `<mapper namespace="loginDAO"><select id="loginDAO.actionLogin">`) |
| F-009-R14 | `<if>`의 조건이 "식 == 리터럴" 꼴이면, 시스템은 그 식을 구문 전체(include한 조각 포함)에서 값 하나로 고정해 값이 맞는 `<if>`의 본문만 넣어야 한다. 전개본 1번은 식마다 처음 나온 값을 쓰고, 식의 다른 값마다 그 식의 값만 바꾼 전개본을 하나씩 더 등록한다(`<choose>`와 같은 방식이고 상한 16개와 진단도 같다). 읽는 꼴은 `v == 'A'`, `v == "A"`, `v == 1`, `v == true`, `v eq 'A'`, 좌우가 바뀐 것, `v.equals('A')`, `v.equalsIgnoreCase('A')`, `'A'.equals(v)`, `'A'.toString()`, 식 전체나 항을 감싼 괄호, `and`·`&&`로 덧붙은 다른 조건(무시한다), 같은 식을 여러 리터럴과 `or`·`||`로 비교한 것(값 집합)이다. 식은 변수나 프로퍼티 경로다(`cond.type`, `list.get(0).dbName`). 그 밖의 조건(null 검사, `!=`, 부등호, 변수끼리 비교, 서로 다른 것을 `or`로 묶은 것)의 `<if>`는 모든 전개본에 넣는다. | 공개 프로젝트 검증(전자정부: `userSe == "GNR"/"ENT"/"USR"`마다 다른 문장, `pdKind`, `searchCnd`; jeesite: `dbName == 'oracle'/'mssql'/'mysql'`) |
| F-009-R15 | 한 namespace가 서로 다른 DB 방언의 매퍼 파일 둘 이상에 정의돼 있으면(방언은 파일 이름을 `_`·`-`·`.`로 나눈 토큰이나 폴더 이름에서 읽는다: `oracle`, `db2`, `tibero`, `altibase`, `postgres(ql)`, `mysql`, `maria(db)`, `mssql`·`sqlserver`, `cubrid`, `goldilocks`, `hsql(db)`, `h2`, `derby`, `sqlite`, `sybase`, `informix`), 시스템은 구문·조각·resultMap의 id마다 그 id를 정의한 방언 파일 가운데 한 방언의 정의만 써야 한다. 선호 순서는 옵션 `mybatis.dialectVariant=<방언>`, `--dialect`로 준 방언, 그다음 위 목록 순이다. 고르지 않은 정의로는 노드도 내장 소스도 만들지 않는다. 구문·조각·매퍼 노드에 `dialectVariant`(고른 방언)를, 내장 소스 `attrs.dialect`에 그 방언을 넣고, 구문마다가 아니라 분석 한 번에 `INFO` 진단 `DIALECT_VARIANT_MAPPERS` 한 건을 남긴다. `mybatis.dialectVariant=all`이면 고르지 않고 모든 방언 파일의 정의를 한 노드에 합친다(이때도 방언이 다른 파일 사이의 같은 id는 중복으로 보지 않는다). | 공개 프로젝트 검증(전자정부 템플릿: `EgovLoginUsr_SQL_oracle.xml` 등 6~8벌, `DUPLICATE_STATEMENT_ID` 205건) |
| F-009-R16 | 한 구문 노드에 등록할 전개본 가운데 본문(공백 정규화)과 `databaseId`가 같은 것이 여럿이면, 시스템은 정의가 여러 벌이어도 한 번만 등록하고 `variants`도 한 번만 세어야 한다. `<selectKey>` 본문도 같다. | 공개 프로젝트 검증(방언 6벌의 같은 본문이 6으로 세어짐) |
| F-009-R17 | 구문에 `<bind>`가 있고 다른 동적 태그와 include가 없으면, 시스템은 `<bind>` 요소를 같은 길이의 공백으로 지운 본문을 `exactPositions=true`로 등록해야 한다. 구문 노드의 `dynamic`은 true로 둔다. | 공개 프로젝트 검증(jpetstore: 컬럼 엣지 위치가 구문 시작 줄로 떨어짐) |
| F-009-R18 | 구문이나 조각 안에 알 수 없는 태그(구식 iBatis의 `<isEqual>`, `<isNotEmpty>`, `<isNull>`, `<dynamic>`, `<iterate>` 등)가 있으면, 시스템은 그 본문을 전개본에 넣고 태그마다가 아니라 구문·조각마다 `INFO` 진단 `UNSUPPORTED_SYNTAX` 한 건(태그 이름 나열)을 남겨야 한다. | 공개 프로젝트 검증(MyBatis 매퍼에 섞인 iBatis 잔재) |
| F-009-R19 | 시스템은 매퍼 XML 하나를 읽기 시작할 때와 그 파일의 구문·조각을 해석하기 시작할 때 그 파일의 상대 경로를 `AnalysisContext.progress`로 알려야 한다(메모리 부족 안내에 실린다). | F-001 (메모리 부족 안내) |
| F-009-R20 | 시스템은 방언별 매퍼를 가릴 때 sql 모듈의 방언별 DDL 선택(F-007-R18)과 **같은 방언 토큰 표·같은 읽는 규칙**을 써야 한다(5절 "방언 토큰 표"). 선호 순서는 `mybatis.dialectVariant=<방언>`, `--dialect`, 프로젝트 설정에서 읽은 방언(F-009-R22), Oracle, DB2, Oracle 계열(Tibero, Altibase), 그 밖의 방언(표의 순서)이다. DDL 쪽은 맨 앞 둘의 순서만 다르다(`--dialect`가 먼저). 요청한 방언(`mybatis.dialectVariant`, `--dialect`)의 매퍼 파일이 하나도 없으면, 시스템은 조용히 다른 방언을 쓰지 않고 `WARN` 진단 `DIALECT_NOT_FOUND`(위치 없음, 분석 한 번에 한 건, 무엇으로 대체했는지 포함)를 남겨야 한다. `all`이거나 방언별 매퍼가 여러 벌이 아니면 남기지 않는다. | 공개 프로젝트 재검증(N4: `--dialect db2`인데 DB2 매퍼가 없으면 조용히 Oracle) |
| F-009-R21 | 시스템은 `${...}`를 치환한 내장 소스의 `attrs.dynamicParts`에 치환 목록(5절 "치환 목록")을 실어야 하고, 구문·조각 노드의 `sql` 속성에는 `__DYNAMIC__` 대신 `${…}` 원문을 적어야 한다. 내장 소스 본문은 전과 같이 `__DYNAMIC__` 식별자다. | 공개 프로젝트 재검증(N5: 내부 표식이 `sql`, 진단, 미해결 원문에 보임) |
| F-009-R22 | 방언을 명시한 옵션(`mybatis.dialectVariant`, `--dialect`)이 없으면, 시스템은 프로젝트 설정 파일에서 읽은 방언(5절 "프로젝트 설정의 방언")을 Oracle보다 먼저 골라야 한다. 그렇게 고른 방언이 대상 방언(Oracle, DB2, Tibero, Altibase)이 아니면 요약 진단 `DIALECT_VARIANT_MAPPERS`를 `INFO`가 아니라 `WARN`으로 남기고, 메시지에 근거(`<파일>:<키>=<값>`)와 "대상 방언이 아니어서 SQL 해석이 부정확할 수 있음. `--dialect oracle`로 변경 가능"을 담아야 한다. 대상 방언이면 `INFO`에 근거만 담는다. sql 모듈(F-007-R21)과 같은 키 목록·같은 규칙이다. (2026-10-11 고쳐 씀: "Oracle보다 먼저"는 설정이 대상 방언(oracle, db2)을 가리킬 때만이다. 대상 외 방언을 가리키는 설정의 순위와 진단은 F-009-R24가 정한다.) | 공개 프로젝트 재검증(N30: `Globals.DbType=hsql`, `jdbc.type=mysql`인데 무조건 Oracle) |
| F-009-R23 | 시스템은 한 구문(include한 조각 포함) 안에서 조건이 같은 `<when>`이 함께 움직이게 `<choose>`를 전개해야 한다(F-009-R4·R5를 보강한다. 분기마다 전개본을 하나씩 더 등록하는 것, 상한 16개, 진단은 R5 그대로다). `<choose>`에는 조건이 참인 첫 `<when>`을, 없으면 `<otherwise>`를 넣고(그것도 없으면 본문 없음), 조건의 참·거짓은 구문 전체에서 고정한 상태로 정한다. `<when test>`가 F-009-R14의 등치 꼴이면 그 식의 값으로 본다: 같은 식을 쓰는 등치 `<if>`와 다른 `<choose>`의 `<when>`이 같은 값으로 움직이고, 식의 값이 어느 `<when>`에도 맞지 않으면 `<otherwise>`다. 등치 꼴이 아니면 test 문자열(공백 정규화) 자체의 참·거짓으로 본다: 문자열이 같은 `<when>`은 함께 참이거나 함께 거짓이고, test 문자열이 같은 `<if>`는 그 `<when>`이 거짓인 전개본에서 빠진다. 전개본 1번은 식마다 처음 나온 값을 쓰고 test 문자열은 모두 참으로 둔다(조건이 겹치지 않는 choose는 각자 첫 분기다). 다른 분기의 전개본은 그 분기가 골라지는 데 필요한 것만 바꾼 상태로 만든다(그 `<when>`은 참, 앞선 `<when>`은 거짓. `<otherwise>`면 그 choose의 모든 `<when>`이 거짓). 조건이 겹치지 않는 choose끼리는 조합하지 않는다. | 공개 프로젝트 검증(전자정부 공통컴포넌트 `EgovRssTagService_SQL_*.xml`: SELECT 목록의 `<choose>`와 FROM의 `<choose>`가 같은 test로 짝을 이루는데 분기가 엇갈린 전개본이 나와, 다른 분기의 테이블이 이 분기의 컬럼을 읽는다고 기록됨) |
| F-009-R24 | 시스템은 방언별 매퍼 여러 벌 가운데 쓸 방언을 5절 "방언 순위(F-009-R24)"로 골라야 한다(F-009-R20의 선호 순서와 F-009-R22의 "프로젝트 설정이 Oracle보다 먼저"를 대체한다. sql 모듈 F-007-R23과 같은 순위다). 프로젝트 설정이 대상 외 방언을 가리켜도 대상 방언(Oracle, DB2)과 그 계열의 매퍼 파일이 있으면 그쪽을 골라야 하고, 요약 진단 `DIALECT_VARIANT_MAPPERS`는 그때 INFO로 설정과 다르게 골랐다는 것과 설정대로 분석하는 방법을 알려야 하며, 대상 방언 파일이 하나도 없어 대상 외 방언을 쓰게 될 때만 WARN이어야 한다. 프로젝트 설정이 파일마다 엇갈리면 쓰지 않고 그 사실을 요약에 적어야 한다. | 공개 프로젝트 재검증 3차(M4) |
| F-009-R25 | 고른 방언의 파일에 없어 다른 방언 파일에서 가져온 구문이면, 시스템은 구문 노드에 `borrowedFrom=<방언>`을 붙이고 요약 진단에 그 구문 ID(namespace.id)를 20개까지 적어야 한다. 만약 그 namespace에서 선호 순서가 가장 앞인 방언의 파일에 같은 id의 구문이 XML 주석 안에 있으면, 시스템은 그 구문을 다른 방언에서 보충하지 않아야 하고(구문 노드를 만들지 않는다) 요약에 그 수와 ID를 적어야 한다. | 공개 프로젝트 재검증 3차(M4-e: 가져온 4개가 선택 방언 파일에서 XML 주석으로 뺀 구문이었다) |
| F-009-R26 | 방언 순위(F-009-R24)의 맨 끝, 곧 명시값·프로젝트 설정·대상 방언(Oracle, DB2)·그 계열(Tibero, Altibase) 어디에도 들지 않는 "그 밖의 방언"끼리는, 시스템은 sql 모듈(F-007-R28)과 **같은 규칙**으로 순서를 정해야 한다: 프로젝트의 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 표준 이름의 사전순으로 먼저고, 나머지가 사전순으로 그 뒤다(5절 "그 밖의 방언의 순서"). F-009-R24의 ⑦ "방언 토큰 표의 순서"를 대체하고 ①~⑥은 그대로다. | F-001 spec 5절 6차 수정의 "확인한 사실"(대상 방언 파일이 없고 매퍼·DDL이 mysql·postgres 두 벌인 프로젝트에서 매퍼는 postgres, DDL은 mysql을 골라 불일치 경고가 나왔다) |

## 4. 수용 기준

### F-009-AC1 (대상: F-009-R1)
- Given namespace가 `com.a.OrderMapper`이고 `<select id="selectOne">`, `<sql id="cols">`가 있는 매퍼 파일
- When 분석하면
- Then `mybatis:mapper:com.a.OrderMapper`, `mybatis:stmt:com.a.OrderMapper.selectOne`, `mybatis:fragment:com.a.OrderMapper.cols` 노드가 있고, 파일 노드에서 매퍼로, 매퍼에서 구문과 조각으로 `CONTAINS` 엣지가 있다. 구문 노드의 위치는 `<select` 시작 줄·열이다.

### F-009-AC2 (대상: F-009-R2)
- Given `parameterType`, `resultType`, `fetchSize`가 있는 정적 `<select>`와 `<insert>`, `<update>`, `<delete>`, 본문이 `MERGE INTO`로 시작하는 `<update>`
- When 분석하면
- Then 각 구문 노드의 `statementType`은 `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`이고 `namespace`, `statementId`, `tag`, `parameterType`, `resultType`, `fetchSize`가 원문대로 있으며 `sql`은 공백이 한 칸으로 정리된 본문, `dynamic`은 false다. 2000자를 넘는 본문의 `sql`은 2000자다.

### F-009-AC3 (대상: F-009-R2)
- Given `statementType="CALLABLE"`인 `<select>`와, 속성 없이 본문이 `{call PKG.P(#{a, mode=IN}, #{b, mode=OUT, jdbcType=VARCHAR})}`인 `<update>`
- When 분석하면
- Then 두 구문의 `statementType`은 `CALL`이고 내장 소스 본문에서 매개변수는 `?`다.

### F-009-AC4 (대상: F-009-R3)
- Given 동적 태그와 include가 없고 `#{id}`가 둘째 줄에 있는 `<select>`
- When 분석하면
- Then 내장 소스는 하나이고 `exactPositions=true`이며, `origin`은 본문의 첫 글자 위치다. 내장 소스 본문에서 찾은 `TB_ORDER`와 `#{id}` 뒤 토큰의 줄·열을 `locationOf`로 바꾸면 원본 파일의 그 글자 위치와 같다.

### F-009-AC5 (대상: F-009-R3)
- Given 본문에 `<![CDATA[ ... <= ... ]]>`, `&lt;`, `&gt;=`, 그리고 SQL이 들어 있는 XML 주석이 있는 정적 구문
- When 분석하면
- Then 내장 소스 본문에 `<=`, `<`, `>=`가 그대로 있고 주석 안의 테이블 이름은 없으며, `exactPositions=true`이고 CDATA 안 토큰의 위치가 원본과 같다.

### F-009-AC6 (대상: F-009-R4)
- Given `<where>` 안에 `AND`로 시작하는 `<if>` 셋이 있는 구문, 그리고 본문이 빈 `<where>`가 있는 구문
- When 분석하면
- Then 첫 구문의 전개본은 하나이고 `WHERE A = ? AND B = ? AND C = ?`처럼 세 조건이 모두 있으며 맨 앞 `AND`는 없다. 둘째 구문에는 `WHERE`가 없다. 두 구문의 `dynamic`은 true이고 `exactPositions`는 false, `origin`은 구문 요소의 시작 위치다.

### F-009-AC7 (대상: F-009-R4)
- Given `<set>` 안에 쉼표로 끝나는 `<if>`들이 있는 UPDATE, `<trim prefix="(" suffix=")" suffixOverrides=",">`로 컬럼 목록과 값 목록을 만드는 INSERT, `<trim prefix="WHERE" prefixOverrides="AND |OR ">`가 있는 SELECT
- When 분석하면
- Then UPDATE 전개본은 `SET A = ?, B = ? WHERE`로 끝 쉼표가 없고, INSERT 전개본은 `( A, B ) VALUES ( ?, ? )` 꼴이며, SELECT 전개본은 `WHERE X = ?`로 맨 앞 `AND`가 없다.

### F-009-AC8 (대상: F-009-R4)
- Given `IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>`와 `<bind>`가 있는 구문
- When 분석하면
- Then 전개본에 `IN ( ? )`가 한 번 있고 `<bind>`의 내용은 없다. `open`·`close`와 본문은 MyBatis처럼 공백을 넣지 않고 잇는다(`IN<foreach open="(">#{id}</foreach>` → `IN(?)`).

### F-009-AC9 (대상: F-009-R5)
- Given `<when>` 둘과 `<otherwise>`가 있는 `<choose>` 하나, `<when>` 둘인 `<choose>` 하나가 있는 구문
- When 분석하면
- Then 전개본은 4개(1 + 2 + 1)이고 `attrs.variant`가 `1`~`4`다. 1번은 두 choose의 첫 `<when>`을 담고, 나머지는 한 choose만 다른 분기로 바뀐 것이다. 모든 분기의 본문이 어느 전개본엔가 있다.

### F-009-AC10 (대상: F-009-R5)
- Given 분기가 20개인 `<choose>`가 있는 구문
- When 분석하면
- Then 전개본은 16개이고 그 구문 위치에 전개본 상한을 알리는 `WARN` 진단이 하나 있다.

### F-009-AC11 (대상: F-009-R6)
- Given `FROM ${tableName} WHERE ID = #{id, jdbcType=NUMERIC} ORDER BY ${sortColumn}`인 구문
- When 분석하면
- Then 내장 소스 본문은 `FROM __DYNAMIC__ WHERE ID = ? ORDER BY __DYNAMIC__`이고, 구문 노드의 `dynamic`은 true이며, 구문 위치에 `INFO` 진단 `DYNAMIC_SQL`이 하나 있다.

### F-009-AC12 (대상: F-009-R7)
- Given 다른 파일의 매퍼(namespace `common`)에 있는 조각 `pagingHeader`, `pagingFooter`를 `common.pagingHeader`로 include하고, 같은 매퍼의 조각 `cols`(다시 조각 `baseCols`를 include)를 include하는 구문. 파일 이름 순서상 include하는 매퍼가 먼저다.
- When 분석하면
- Then 전개본에 네 조각의 본문이 순서대로 들어 있고, `INCLUDES` 엣지가 구문→`common.pagingHeader`, 구문→`common.pagingFooter`, 구문→`cols`, 조각 `cols`→조각 `baseCols`로 있다(신뢰도 `RESOLVED`). 구문→`baseCols` 엣지는 없다.

### F-009-AC13 (대상: F-009-R7)
- Given `${alias}.ORDER_ID`가 든 조각과 그것을 `<property name="alias" value="O"/>`로 include하는 구문
- When 분석하면
- Then 전개본에 `O.ORDER_ID`가 있고 `__DYNAMIC__`은 없으며 구문의 `dynamic`은 false, `DYNAMIC_SQL` 진단은 없다.

### F-009-AC14 (대상: F-009-R7)
- Given 없는 조각 `nowhere.frag`를 include하는 구문, 그리고 서로를 include하는 조각 `a`, `b`와 `a`를 include하는 구문
- When 분석하면
- Then 첫 구문에서 `unresolved:mybatis-fragment:nowhere.frag`로 신뢰도 `UNRESOLVED`인 `INCLUDES` 엣지와 `UNRESOLVED_REFERENCE` 진단이 있고 나머지 본문은 전개된다. 둘째 구문은 분석이 끝나고(무한 반복 없음) 순환을 알리는 진단이 있다.

### F-009-AC15 (대상: F-009-R8)
- Given `<selectKey keyProperty="id" resultType="long" order="BEFORE">SELECT SEQ_ORDER.NEXTVAL FROM DUAL</selectKey>`가 앞에 있는 정적 INSERT
- When 분석하면
- Then 같은 구문 노드를 주인으로 하는 내장 소스가 둘이다. `attrs.selectKey=true`인 것의 본문은 `SELECT SEQ_ORDER.NEXTVAL FROM DUAL`이고, 다른 하나의 본문은 `INSERT`로 시작하며 `SEQ_ORDER`가 없다. 둘 다 `exactPositions=true`이고 위치가 원본과 같다.

### F-009-AC16 (대상: F-009-R9)
- Given id가 같고 `databaseId`가 `oracle`, `db2`인 `<select>` 둘
- When 분석하면
- Then 구문 노드는 하나이고 내장 소스는 둘이며 `attrs.dialect`가 각각 `oracle`, `db2`다. 노드의 `databaseIds`는 `[oracle, db2]`다.

### F-009-AC17 (대상: F-009-R10)
- Given `mybatis-config.xml`에 `<typeAlias alias="orderVO" type="com.a.vo.OrderVO"/>`가 있고, 구문 넷이 각각 `parameterType="OrderVO"`(대소문자 다름), `resultType="com.a.vo.ItemVO"`, `resultType="hashmap"`, `parameterType="java.util.Map"`을 쓴다
- When 분석하면
- Then 앞의 둘은 `java:type:com.a.vo.OrderVO`(`role=parameter`), `java:type:com.a.vo.ItemVO`(`role=result`)로 `RESOLVED`인 `USES_TYPE` 엣지가 있고, 뒤의 둘은 `USES_TYPE` 엣지가 없으며 미해결 진단도 없다.

### F-009-AC18 (대상: F-009-R10)
- Given `mybatis-config.xml`의 `<package name="com.a.vo"/>`, Spring XML의 `typeAliasesPackage` 값 `com.b.model`, `application.properties`의 `mybatis.type-aliases-package=com.c.dto`, `application.yml`의 `mybatis: type-aliases-package: com.d.dto`와 각 패키지의 Java 파일
- When 그 패키지의 클래스를 단순 이름(소문자 시작)으로 쓰는 구문들을 분석하면
- Then 구문마다 해당 FQN의 `java:type:` 노드로 `RESOLVED`인 `USES_TYPE` 엣지가 있다.

### F-009-AC19 (대상: F-009-R10)
- Given 별칭 설정이 없고, 단순 이름이 `MemberVO`인 Java 파일이 프로젝트에 하나, `DupVO`인 파일이 둘 있다
- When `resultType`이 `MemberVO`, `DupVO`, `NoSuchVO`인 구문들을 분석하면
- Then `MemberVO`는 그 파일의 FQN으로 `INFERRED` 엣지가, `DupVO`와 `NoSuchVO`는 `unresolved:mybatis-type:<이름>`으로 `UNRESOLVED` 엣지와 `UNRESOLVED_REFERENCE` 진단이 있다.

### F-009-AC20 (대상: F-009-R11)
- Given `type="orderVO"`이고 `<association javaType>`, `<collection ofType select="selectItems">`, `<id>`·`<result>` 대응이 있는 resultMap, 그것을 `extends`하는 resultMap, 그리고 뒤의 것을 `resultMap`으로 쓰는 구문
- When 분석하면
- Then 구문에서 세 타입으로 `role=resultMap`인 `USES_TYPE` 엣지가 있고, 구문 노드의 `resultColumns`에 부모와 자식의 `COLUMN=property` 대응이 있으며(association 안의 것은 `COLUMN=member.memberNm` 꼴), `nestedSelects`에 `<namespace>.selectItems`가 있다. resultMap 노드와 중첩 select로의 `INCLUDES` 엣지는 없다. 없는 resultMap을 가리키는 구문에는 `unresolved:mybatis-resultmap:<이름>` 미해결 엣지가 있다.

### F-009-AC21 (대상: F-009-R12)
- Given 정상 `<select id="ok">` 뒤에 닫히지 않은 `<if>`가 있는 `<select id="bad">`가 오는 매퍼 파일과, 정상인 다른 매퍼 파일
- When 분석하면
- Then 깨진 파일 위치의 `PARSE_FAILED` 진단이 하나 있고, `ok` 구문 노드와 다른 파일의 구문 노드는 있으며 `bad` 구문 노드는 없다. 분석기는 예외를 던지지 않는다.

### F-009-AC22 (대상: F-009-R12)
- Given 접속할 수 없는 주소의 DOCTYPE(`http://mybatis.org/dtd/mybatis-3-mapper.dtd`)과, 로컬 파일을 가리키는 외부 엔티티 선언이 있는 매퍼 파일
- When 분석하면
- Then 네트워크 없이 분석이 끝나 구문 노드가 있고, 외부 엔티티가 가리키는 파일의 내용은 어느 내장 소스에도 없다.

### F-009-AC23 (대상: F-009-R12)
- Given 루트 요소가 `sqlMap`인 XML 파일, 그리고 `<cache>`, `<cache-ref>`, `<parameterMap>`이 있는 매퍼 파일
- When 분석하면
- Then `sqlMap` 파일에 대해 `INFO` 진단 `UNSUPPORTED_SYNTAX`가 정확히 한 건 있고 노드는 만들어지지 않는다. 매퍼 파일의 세 요소에 대해서는 진단이 없다.

### F-009-AC24 (대상: F-009-R4, F-009-R7)
- Given 현장 패턴 픽스처(페이징 공통 조각 include, 검색 조건 `<if>` 묶음 조각, `<foreach>` IN 절, `<choose>` 정렬, `<trim>` INSERT 컬럼 목록, MERGE, 프로시저 호출, `${tableName}` 동적 테이블, selectKey 시퀀스)
- When 분석하면
- Then 구문마다 기대한 전개본 문자열(공백 정규화 후)이 나온다.

### F-009-AC25 (대상: F-009-R13)
- Given namespace가 `signDAO`이고 `<select id="signDAO.actionSign">`, `<select id="plain">`가 있는 매퍼
- When 분석하면
- Then 구문 노드는 `mybatis:stmt:signDAO.actionSign`, `mybatis:stmt:signDAO.plain` 둘뿐이고(`signDAO.signDAO.actionSign`은 없다), 앞의 것의 `namespace`는 `signDAO`, `statementId`와 이름은 `actionSign`이며 내장 소스의 주인도 그 노드다. 진단은 없다.

### F-009-AC26 (대상: F-009-R13)
- Given 같은 매퍼에 `<resultMap id="signDAO.signMap">`, `<sql id="signDAO.cols">`, 그것을 짧은 이름(`resultMap="signMap"`, `refid="cols"`)으로 쓰는 구문과 한정된 이름(`signDAO.signMap`, `signDAO.cols`)으로 쓰는 구문, 그리고 `<select id="elsewhere.thing">`
- When 분석하면
- Then 조각 노드는 `mybatis:fragment:signDAO.cols` 하나이고(`fragmentId`는 `cols`), 두 구문 모두 그 조각으로 `RESOLVED`인 `INCLUDES`와 resultMap 타입으로 `USES_TYPE`이 있으며 미해결 노드는 없다. `mybatis:stmt:elsewhere.thing` 노드가 있고(`statementId`는 `elsewhere.thing`) 그 id를 알리는 `WARN` 진단 `PARSE_FAILED`가 한 건 있다.

### F-009-AC27 (대상: F-009-R14)
- Given `<if test="userSe == &quot;GNR&quot;">`, `"ENT"`, `"USR"` 셋이 (사이에 주석만 두고) 이어져 있고 본문이 저마다 완결된 SELECT인 구문, 그리고 `pageUnit == 5`, `pageUnit == 10`마다 다른 꼬리(`) TB ) WHERE RN BETWEEN …`)를 붙이는 구문
- When 분석하면
- Then 첫 구문의 전개본은 셋이고 각 전개본은 한 분기의 SELECT만 담는다(`variants`는 3, 대표 `sql`은 첫 값의 분기). 둘째 구문의 전개본은 둘이고 각자 꼬리 하나만 있다. 진단은 없다.

### F-009-AC28 (대상: F-009-R14)
- Given `dbName == 'oracle'`·`dbName == 'mysql'`인 `<if>` 쌍이 include한 조각과 구문 본문(다른 `<if>` 안)에 한 번씩 있는 구문, 그리고 `kind == 'D'`·`kind == 'M'`인 `<if>`가 SELECT 목록, `<where>`, GROUP BY에 흩어져 있는(순서도 섞인) 구문
- When 분석하면
- Then 구문마다 전개본이 둘이고, 한 전개본에는 같은 값의 본문만 있다(`'%'||?||'%'`끼리, `concat('%',?,'%')`끼리 / `DAY_*`끼리, `MONTH_*`끼리). null 검사 `<if>`의 본문은 두 전개본 모두에 있다.

### F-009-AC29 (대상: F-009-R14)
- Given `a eq 1`/`a eq 2`, `b.equals("X")`/`"Y".equals(b)`, `c != null and c == 'P'`/`c != null and (c == 'Q')`/`(c != null) && c != '' && c == 'R'.toString()`, `cond.type == 10`/`5 == cond.type`, `e == 'Y' or e == 'N'`과 `e == 'Y'`·`e == 'N'`·`e == 'Z'`, `list.get(0).dbName == 'oracle'`/`… == 'mssql' or … == 'mysql'`, `tbl.equalsIgnoreCase('a')`/`('b')`인 `<if>`들이 있는 구문들, 그리고 null 검사·`== null`·변수끼리 비교·부등호·`!=`·서로 다른 것의 `or`·값이 하나뿐인 등치만 있는 구문
- When 분석하면
- Then 앞의 구문들은 값마다 전개본이 하나씩이고(`or`로 묶은 `<if>`의 본문은 그 값들의 전개본에 모두 있고, 본문이 같아지는 `mssql`·`mysql`은 전개본 하나다), 마지막 구문은 모든 `<if>` 본문이 든 전개본 하나다.

### F-009-AC30 (대상: F-009-R14)
- Given 한 식을 20개 값과 비교하는 `<if>` 20개가 있는 구문, 그리고 값 둘인 등치 `<if>` 쌍과 분기 둘인 `<choose>`가 함께 있는 구문
- When 분석하면
- Then 첫 구문의 전개본은 16개이고 그 구문 위치에 `WARN` 진단 `UNSUPPORTED_SYNTAX`가 하나 있다. 둘째 구문의 전개본은 3개(1번, 등치 값만 바꾼 것, choose 분기만 바꾼 것)다.

### F-009-AC31 (대상: F-009-R15)
- Given namespace가 같은 매퍼 파일 `Stock_SQL_altibase.xml`, `Stock_SQL_mysql.xml`, `Stock_SQL_hsql.xml`, `Stock_SQL_oracle.xml`. 구문 `find`와 조각 `cols`는 파일마다 다른 테이블을 보고, `onlyMy`는 mysql 파일에만, `myAndHsql`은 mysql·hsql 파일에만 있다
- When 옵션 없이 분석하면
- Then `find`의 전개본은 oracle 파일의 것 하나뿐이고(조각도 oracle 파일의 것) 구문·조각·매퍼 노드의 `dialectVariant`는 `oracle`, 위치는 oracle 파일, 내장 소스의 `attrs.dialect`는 `oracle`이다. `onlyMy`와 `myAndHsql`은 mysql 파일의 정의로 남고 `dialectVariant`는 `mysql`이다. 매퍼를 `CONTAINS`하는 파일은 정의가 쓰인 oracle·mysql 파일뿐이다. `DUPLICATE_STATEMENT_ID`는 없고 `INFO` 진단 `DIALECT_VARIANT_MAPPERS`가 정확히 한 건 있으며 메시지에 방언 수(4벌), 고른 방언, 옵션 키가 있다.

### F-009-AC32 (대상: F-009-R15)
- Given AC31의 파일들(경우에 따라 `mapper/db2/Stock.xml`을 더한다)
- When (가) `--dialect db2`로, (나) `--dialect oracle`과 `mybatis.dialectVariant=MySQL`로, (다) `mybatis.dialectVariant=nosuchdb`로, (라) `mybatis.dialectVariant=all`로 분석하면
- Then (가) 폴더 이름에서 방언을 읽은 db2 파일의 정의를 쓴다. (나) mysql 파일의 정의를 쓴다. (다) `WARN` 진단 `INVALID_OPTION`을 남기고 oracle을 쓴다. (라) `find`의 전개본은 방언 파일 수만큼이고 저마다 자기 파일의 조각을 쓰며 `attrs.dialect`가 파일의 방언이다. 구문에 `dialectVariant`는 없고 `DUPLICATE_STATEMENT_ID`도 없으며 요약 진단은 한 건이다.

### F-009-AC33 (대상: F-009-R15, F-009-R9)
- Given 방언을 읽을 수 없는 두 파일(`mapper/a/Stock.xml`, `mapper/b/Stock.xml`)에 같은 namespace·id의 구문이 있는 프로젝트, 그리고 방언이 같은 두 파일(`mapper/a/Stock_SQL_oracle.xml`, `mapper/b/Stock_SQL_oracle.xml`)에 그런 구문이 있는 프로젝트
- When 분석하면
- Then 둘 다 전개본을 모두 등록하고 `WARN` 진단 `DUPLICATE_STATEMENT_ID`가 한 건 있으며 `DIALECT_VARIANT_MAPPERS` 진단과 `dialectVariant`는 없다.

### F-009-AC34 (대상: F-009-R16)
- Given `mybatis.dialectVariant=all`로 분석하는, 본문이 같은 구문 `same`이 방언 파일 넷에 있는 프로젝트. 그리고 한 파일에 본문과 `<selectKey>`가 같은 구문이 두 번 정의된 매퍼
- When 분석하면
- Then `same`의 내장 소스는 하나(`variant=1`, `exactPositions=true`)이고 `variants`는 1이다. 둘째 매퍼의 구문은 내장 소스가 본문 하나와 selectKey 하나이고 `variants`는 1이며 `DUPLICATE_STATEMENT_ID`는 그대로 한 건 있다.

### F-009-AC35 (대상: F-009-R17)
- Given 본문 앞과 중간에 `<bind>`가 있고 다른 동적 태그가 없는 `<select>`
- When 분석하면
- Then 내장 소스는 하나이고 `exactPositions=true`이며 본문에 `<bind>`의 내용이 없고, 본문에서 찾은 토큰의 위치를 `locationOf`로 바꾸면 원본 파일의 그 글자 위치와 같다. 구문 노드의 `dynamic`은 true다.

### F-009-AC36 (대상: F-009-R18)
- Given `<dynamic>`, `<isNotEmpty>`, `<isNull>`, `<isEqual>` 둘, `<iterate>`가 든 구문, `<isNotNull>` 하나가 든 구문, 그런 태그가 없는 구문
- When 분석하면
- Then 앞의 두 구문은 태그 안의 본문이 모두 든 전개본이 있고, `INFO` 진단 `UNSUPPORTED_SYNTAX`가 구문마다 한 건씩 모두 두 건 있으며 메시지에 구문 ID와 그 구문의 태그 이름들이 있다.

### F-009-AC37 (대상: F-009-R19)
- Given 매퍼 XML 둘과 `progress` 호출을 기록하는 컨텍스트
- When 분석하면
- Then 두 파일의 상대 경로가 경로순으로 알려진다.

### F-009-AC38 (대상: F-009-R20)
- Given 방언 토큰 표(`oracle`, `ORACLE`, `ora`, `oracle11`, `db2`, `mysql55`, `postgresql`, `pgsql`, `maria`, `sqlserver2012`, `hsqldb`, `sqlite3` … / `auto`, `all`, `sql`, `oracledb`, 빈 문자열)와 경로 표(`mapper/EgovLoginUsr_SQL_oracle.xml`, `mapper/Order.postgresql.xml`, `db/act/activiti.mysql55.create.history.sql`, `mapper/db2/sub/Stock.xml`, `db/oracle/mysql_compat.sql`, `db/oracle_to_mysql.sql`, `mapper/OracleUserMapper.xml`, `db/my-oracle-scripts/schema.sql` …). 이 두 표는 sql 모듈의 F-007-AC20 테스트(`DialectTokensTest`)와 글자 그대로 같다
- When `DialectVariants.canonical`과 `DialectVariants.dialectOf`로 읽으면
- Then 표준 이름과 기본 선호 순서가 `oracle, db2, tibero, altibase, postgres, mysql, mariadb, mssql, cubrid, goldilocks, hsql, h2, derby, sqlite, sybase, informix`이고, 별칭·버전 숫자가 붙은 토큰은 표준 이름으로, 방언이 아닌 토큰은 없음으로 읽힌다. 경로는 파일 이름의 토큰(뒤에서부터)이 폴더 이름보다 먼저고, 폴더는 이름 전체가 방언 토큰일 때만 읽힌다.

### F-009-AC39 (대상: F-009-R20)
- Given altibase·mysql·hsql·oracle 매퍼 네 벌이 있는 프로젝트
- When (가) `--dialect db2`로, (나) `--dialect db2`와 `mybatis.dialectVariant=cubrid`로, (다) `--dialect db2`와 `mybatis.dialectVariant=mysql`로, (라) `--dialect db2`와 `mybatis.dialectVariant=all`로 분석하고, (마) 방언 파일이 하나뿐인 프로젝트를 `--dialect db2`로 분석하면
- Then (가) oracle 파일의 정의를 쓰고 `WARN` 진단 `DIALECT_NOT_FOUND`가 한 건 있다(위치 없음, 메시지에 ``db2(`--dialect`)``, "매퍼 파일이 없음", 감지한 방언 목록, "oracle 파일을 사용"). (나) 메시지에 ``cubrid(`mybatis.dialectVariant`), db2(`--dialect`)``가 이 순서로 있다. (다)(라)(마) 진단이 없다.

### F-009-AC40 (대상: F-009-R21)
- Given 정적 구문 `SELECT a.ID FROM TB_A a WHERE a.NM = #{nm} ${sqlMap.dsf} ORDER BY ${ orderBy }`, `<where>`·`<if>`가 있고 `${schema}.TB_A`와 `${sqlMap.dsf}`를 쓰는 구문, `${` 안에 줄바꿈이 있는 구문, `${}`가 없는 구문, `UPDATE ${businessTable} SET …`, `TB_LOG_${ym}`과 조각 `ORDER BY ${orderBy}`를 include한 구문
- When 분석하면
- Then 내장 소스의 `attrs.dynamicParts`가 각각 `13:${sqlMap.dsf}12:${ orderBy }`(위치를 보존한 본문), `9:${schema}13:${sqlMap.dsf}`(전개본), `9:${ cond }`(줄바꿈을 낀 공백은 한 칸)이고 `${}`가 없는 구문에는 키가 없다. 구문 노드의 `sql`은 `UPDATE ${businessTable} SET PROC_INS_ID = ? WHERE ID = ?`, `SELECT * FROM TB_LOG_${ym} ORDER BY ${orderBy}`이고 조각 노드의 `sql`은 `ORDER BY ${orderBy}`다(어느 노드의 `sql`에도 `__DYNAMIC__`이 없다). 내장 소스 본문은 전과 같이 `TB_LOG___DYNAMIC__ … __DYNAMIC__`이다.

### F-009-AC41 (대상: F-009-R22)
- Given 설정 키·값 표(`Globals.DbType=hsql`, `jdbc.type=mssql`, `mybatis.configuration.database-id=db2`, `spring.datasource.url=jdbc:oracle:thin:@…`·`jdbc:log4jdbc:mysql://…`·`jdbc:Altibase://…`, `spring.datasource.driver-class-name=com.ibm.db2.jcc.DB2Driver`·`com.mysql.cj.jdbc.Driver`, `${…}`가 든 값, 방언과 무관한 키 … 이 표는 sql 모듈의 F-007-AC21 테스트(`ProjectDialectTest`)와 글자 그대로 같다). 그리고 매퍼 네 벌(altibase·mysql·hsql·oracle)이 있는 프로젝트에 (가) `src/main/resources/…/globals.properties`의 `#Globals.DbType = oracle` / `Globals.DbType = hsql`과 `src/test/resources/globals.properties`의 `Globals.DbType = altibase`, (나) 같은 설정에 `--dialect oracle`, (다) `application.yml`의 `spring.datasource.url: jdbc:Altibase://…`, (라) 두 설정 파일에 `hsql`과 `mysql`
- When 분석하면
- Then 표의 값은 기대한 방언으로 읽힌다(주석 줄, `${…}`, `src/test` 아래 파일은 읽지 않는다). (가) hsql 파일의 정의를 쓰고(hsql 파일에 없는 구문은 다음 순위에서 가져온다) 요약 `DIALECT_VARIANT_MAPPERS`가 `WARN`이며 메시지에 "프로젝트 설정(src/main/resources/egovframework/egovProps/globals.properties:Globals.DbType=hsql)에 따라 hsql 선택", "이 도구의 대상 방언이 아니어서 SQL 해석이 부정확할 수 있음", "`--dialect oracle`로 변경 가능"이 있다. (나) oracle을 쓰고 요약은 `INFO`이며 "프로젝트 설정"이 없다. (다) altibase를 쓰고 요약은 `INFO`에 근거만 있다. (라) 설정을 쓰지 않고 oracle을 쓴다.

### F-009-AC42 (대상: F-009-R23)
- Given SELECT 목록과 FROM에 `<choose>`가 하나씩 있고 둘 다 `<when test="t.equalsIgnoreCase('tb_log')">`, `<when test="t.equalsIgnoreCase('tb_zip')">`, `<otherwise>`로 된 구문, 그리고 같은 모양에서 SELECT 목록의 `<choose>`가 include한 조각 안에 있는 구문
- When 분석하면
- Then 구문마다 전개본은 셋뿐이고(`variants`는 3) 같은 분기끼리만 짝을 이룬다: `tb_log`의 컬럼과 `tb_log`, `tb_zip`의 컬럼과 `tb_zip`, `*`와 `<otherwise>`의 테이블. 한 분기의 컬럼이 다른 분기의 테이블과 함께 든 전개본은 없다. 진단은 없다.

### F-009-AC43 (대상: F-009-R23)
- Given `<when test="searchCnd != null and searchCnd != ''">`와 `<otherwise>`로 된 `<choose>` 둘(한쪽 test는 공백만 다르다)과 같은 test의 `<if>`, 다른 test의 `<if>`가 있는 구문, 그리고 test가 서로 겹치지 않는 `<choose>` 둘(분기 셋, 분기 둘)과 `<if>` 하나가 있는 구문
- When 분석하면
- Then 첫 구문의 전개본은 둘이다: 두 `<when>`과 두 `<if>`가 든 것, 두 `<otherwise>`와 다른 test의 `<if>`만 든 것. 둘째 구문의 전개본은 4개(1 + 2 + 1)이고 1번은 두 choose의 첫 `<when>`, 나머지는 한 choose만 다른 분기로 바뀐 것이며 `<if>` 본문은 모두에 있다(F-009-AC9와 같은 결과).

### F-009-AC44 (대상: F-009-R23)
- Given `kind == 'D'`·`kind == 'M'`인 `<if>` 쌍 뒤에 `<when test="kind == 'M'">`, `<when test="kind == 'D'">`, `<otherwise>`로 된 `<choose>`가 있는 구문, `<when>`의 값이 일부만 겹치는 `<choose>` 둘(`t == 'a'`·`'b'` / `t == 'a'`·`'c'`, 둘 다 `<otherwise>` 있음)이 있는 구문, `<otherwise>`가 없는 `<choose>`와 있는 `<choose>`가 같은 `<when>`을 쓰는 구문, 한 `<choose>` 안에 같은 조건의 `<when>`이 둘 있는 구문
- When 분석하면
- Then 첫 구문의 전개본은 셋이다: 1번은 식에서 처음 나온 값 `D`의 `<if>`와 `D`의 `<when>`(choose의 첫 `<when>`이 아니다), 다음은 `M`끼리, 마지막은 두 `<if>`가 빠지고 `<otherwise>`만 든 것. 둘째 구문의 전개본은 넷이고 한쪽 choose에만 있는 값(`b`, `c`)에서 다른 choose는 `<otherwise>`다. 셋째 구문은 전개본이 둘이고 `<otherwise>` 쪽 전개본에 `<otherwise>`가 없는 choose의 본문이 없다. 넷째 구문은 가려진 둘째 `<when>`의 본문도 전개본 하나로 남는다.

### F-009-AC45 (대상: F-009-R24)
- Given 같은 namespace의 매퍼가 cubrid·mysql·oracle·altibase 파일로 있는 프로젝트에 `Globals.DbType = mysql`, `Globals.DbType=altibase`, `mybatis.dialectVariant=mysql`을 준 경우와, cubrid·mysql·postgres 파일뿐인 프로젝트에 `Globals.DbType=cubrid`를 준 경우, 설정 파일 둘이 `mysql`과 `altibase`로 엇갈린 경우
- When 분석하면
- Then 설정이 mysql이어도 oracle 파일의 구문을 쓰고(`dialectVariant=oracle`) 요약이 INFO 1건이며 "프로젝트 설정(…:Globals.DbType=mysql)과 다르게 대상 방언 oracle 파일을 사용", "설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`"이 있다. 설정이 altibase여도 oracle이고, 옵션으로 mysql을 요청하면 mysql(INFO)이다. 대상 방언 파일이 없으면 설정의 cubrid를 쓰고 요약이 WARN이며 "이 도구의 대상 방언(Oracle, DB2)의 파일이 없어"가 있다. 엇갈린 설정은 쓰지 않고 요약에 "프로젝트 설정이 엇갈려 쓰지 않음(…)"이 있다.

### F-009-AC46 (대상: F-009-R25)
- Given oracle 파일에는 `find`만 있고 `commentedInOracle`은 XML 주석 안에 있으며, mysql 파일에 `find`·`onlyMy`·`commentedInOracle`이 있는 방언별 매퍼
- When 분석하면
- Then `find`에는 `borrowedFrom`이 없고, `onlyMy`는 mysql 파일의 본문으로 등록되며 `dialectVariant=mysql`, `borrowedFrom=mysql`이다. `commentedInOracle` 구문 노드는 없다. 요약(INFO)에 "oracle 파일에 없는 구문은 다른 방언 파일에서 가져왔다(mysql 1개: aDAO.onlyMy)"와 "oracle 파일에서 XML 주석으로 뺀 구문 1개는 보충하지 않음(aDAO.commentedInOracle)"이 있다.

### F-009-AC47 (대상: F-009-R26)
- Given 대상 방언 파일도 프로젝트 설정도 없이 같은 namespace의 매퍼가 mysql·postgres 파일로 있는 프로젝트, cubrid·mysql·postgres 파일로 있는 프로젝트(SQL 스크립트가 없는 경우, `db/mysql/`·`db/postgres/`에 스크립트가 있는 경우, 거기에 `Globals.DbType=postgres`를 더한 경우), 그리고 매퍼 경로 목록·스크립트 경로 목록·기대 순서의 표(sql 모듈 `DialectTokensTest`와 글자 그대로 같은 표)
- When 분석하면(표는 `DialectVariants.otherOrder`에 주면)
- Then mysql·postgres 두 벌은 mysql 파일의 구문을 쓰고(`dialectVariant=mysql`) 요약(WARN)이 "방언별 매퍼 2벌 감지(mysql, postgres): mysql 파일을 사용"이다. cubrid·mysql·postgres는 스크립트가 없으면 cubrid, 스크립트 경로에 mysql·postgres가 있으면 mysql("3벌 감지(mysql, postgres, cubrid)"), 설정이 postgres면 postgres다. 표의 순서는 양쪽에 다 있는 방언(사전순), 나머지(사전순)이고 대상 방언과 그 계열은 들어 있지 않다. cli에서 두 분석기를 함께 돌리면(`MainTest.mapperAndDdlAgreeOnOtherDialect`) 매퍼와 DDL이 같은 방언(mysql)을 고르고 불일치 경고가 없다.

## 5. 가정
<!-- 사용자가 미확정 사항은 기본값을 정해 진행하라고 위임했다(2026-10-11). 전부 검토가 필요하다. -->
- 조건식(`test`)은 평가하지 않는다. 다만 "식 == 리터럴" 꼴의 `<if>`는 식의 값마다 전개본을 나눈다(F-009-R14). 그 꼴이 아닌 조건으로 배타적인 `<if>` 둘(`v == 'A'` / `v != 'A'`, `x != null` / `x == null`)은 한 전개본에 함께 들어가 문법이 어긋날 수 있고, SQL 파서가 오류를 허용한다고 전제한다.
- 등치 조건의 식은 글자 그대로 비교한다(`dbName`과 `list.get(0).dbName`은 다른 식이다). 리터럴은 따옴표를 떼고 비교하므로 `'1'`과 `1`은 같은 값이다. 한 조건에서 같은 식을 `and`로 두 번 비교하면(`v == 'A' and v == 'B'`) 읽지 않는다.
- 한 식의 어느 값에도 해당하지 않는 경우(모든 등치 `<if>`가 빠진 본문)의 전개본은 `<choose>`가 요구할 때만 만든다: `<otherwise>`의 전개본, 또는 다른 것을 보는 뒤쪽 `<when>`의 전개본에서 앞선 등치 `<when>`을 거짓으로 만들 때다(F-009-R23). `<if>`만 있는 식에서는 전과 같이 만들지 않는다.
- `<choose>`와 조건의 상태(F-009-R23). 전개본 하나는 "구문 전체에서 고정한 조건의 상태" 하나다. 상태는 (1) 등치 조건의 식 → 값(기본은 그 식에서 처음 나온 값. `<if>`와 `<when>`을 가리지 않고 구문에 나온 순서로 센다. "어느 값도 아님"도 값이다), (2) 등치 꼴이 아닌 `<when>`의 test 문자열 → 참·거짓(기본은 참)으로 이뤄진다. `<choose>`는 이 상태에서 참인 첫 `<when>`, 없으면 `<otherwise>`를 넣는다. choose의 다른 분기마다 "그 분기가 골라지는 데 필요한 것만 바꾼" 상태의 전개본을 하나씩 더 만든다: 고르려는 `<when>`의 식을 그 값으로(또는 test를 참으로), 그보다 앞선 `<when>` 가운데 아직 참인 것은 식을 "어느 값도 아님"으로(또는 test를 거짓으로) 바꾼다. `<otherwise>`는 모든 `<when>`을 그렇게 거짓으로 만든다. 상태가 같은 전개본은 한 번만 만든다.
- 조건이 겹치지 않는 choose·if만 있는 구문의 전개본과 그 순서는 F-009-R5·R14만 있던 때와 같다. 달라지는 것은 한 구문 안에서 (가) 같은 식을 보는 등치 `<when>`이 여러 `<choose>`에 있거나 등치 `<if>`와 같은 식을 볼 때, (나) 같은 test 문자열이 여러 `<when>`(또는 `<when>`과 `<if>`)에 있을 때뿐이다. 이때 1번 전개본의 choose 분기는 첫 `<when>`이 아니라 식의 기본값에 맞는 `<when>`(없으면 `<otherwise>`)일 수 있다.
- test 문자열은 공백만 정규화해 글자 그대로 비교한다(`a != null`과 `a!=null`, `a != null`과 `null != a`는 다른 조건이다). 부정 관계(`x != null` / `x == null`)는 읽지 않는다. 등치의 리터럴도 글자 그대로 비교하므로 `equalsIgnoreCase('A')`와 `equalsIgnoreCase('a')`는 다른 값이다. test가 없는 `<when>`은 다른 것과 묶이지 않는다.
- 한 `<choose>` 안에서 앞의 `<when>`에 가려 상태로는 고를 수 없는 분기(같은 조건의 `<when>`이 둘, 덧붙은 조건만 다른 등치 `<when>` 둘)는 그 choose만 억지로 그 분기를 고른 전개본으로 남긴다(본문을 잃지 않으려고).
- **방언 토큰 표(F-009-R20. sql 모듈 F-007 spec 5절과 같은 문구다. 한쪽을 고치면 다른 쪽도 고친다)**: 표준 이름은 `oracle`, `db2`, `tibero`, `altibase`, `postgres`, `mysql`, `mariadb`, `mssql`, `cubrid`, `goldilocks`, `hsql`, `h2`, `derby`, `sqlite`, `sybase`, `informix`이고 이 순서가 기본 선호 순서다(앞의 넷 Oracle, DB2, Tibero, Altibase가 대상 방언과 그 계열이다). 별칭은 `ora`→oracle, `postgresql`·`pgsql`→postgres, `maria`→mariadb, `sqlserver`→mssql, `hsqldb`→hsql이다. 대소문자를 가리지 않고, 표에 없는 토큰은 끝의 숫자(버전)를 떼고 한 번 더 본다(`mysql55`→mysql, `oracle11`→oracle). 경로에서는 파일 이름(마지막 확장자 제외)을 글자·숫자가 아닌 문자로 나눈 토큰을 **뒤에서부터** 보고, 없으면 폴더 이름을 가까운 것부터 본다(폴더는 이름 전체가 방언 토큰일 때만). 낙타 표기로 붙여 쓴 이름(`OracleUserMapper.xml`)과 이름의 일부만 방언인 폴더(`my-oracle-scripts/`)는 읽지 않는다.
- **선호 순서(F-009-R20, R22)**: 매퍼는 ① `mybatis.dialectVariant=<방언>` ② `--dialect` ③ 프로젝트 설정에서 읽은 방언 ④ 방언 토큰 표의 순서(Oracle, DB2, Tibero, Altibase, 그 밖)다. DDL·데이터 스크립트(F-007-R18)는 ①과 ②의 순서만 다르다(`--dialect`가 먼저, 없으면 `mybatis.dialectVariant`). 그래서 `--dialect oracle`과 `mybatis.dialectVariant=mysql`을 함께 주면 매퍼는 MySQL, DDL은 Oracle이다(매퍼만 다른 방언으로 보려는 경우를 위한 것이다). 하나만 주면 두 쪽이 같은 방언을 고른다. 표의 뒤쪽(대상 외 방언끼리)에서는 매퍼가 표의 순서, DDL이 "경로순 첫 파일의 방언"이라 다를 수 있다. 맞추려면 옵션 하나를 준다. (2026-10-11: 이 차이는 F-009-R26·F-007-R28로 없앴다. 아래 "그 밖의 방언의 순서".)
- **프로젝트 설정의 방언(F-009-R22. sql 모듈 F-007 spec 5절과 같은 문구다)**: `.properties`와 `.yml`·`.yaml`에서 읽는다. 방언 이름을 값으로 갖는 키는 `Globals.DbType`, `jdbc.type`, `mybatis.configuration.database-id`이고 값을 방언 토큰 표로 읽는다. `spring.datasource.url`은 `jdbc:` 뒤의 조각에서(래퍼 `log4jdbc`·`p6spy`·`jtds`·`tc`는 건너뛴다. `jdbc:oracle:thin:…`, `jdbc:log4jdbc:mysql:…`, `jdbc:informix-sqli:…`), `spring.datasource.driver-class-name`은 클래스 이름의 조각(점으로 나눈 것)이 방언 토큰이거나 방언 토큰으로 시작하면(`DB2Driver`, `OracleDriver`) 그 방언으로 읽는다. 키는 대소문자와 `-`·`_`를 가리지 않는다(`driverClassName`). YAML은 들여쓰기로 중첩된 키를 점으로 이어 읽는다. 주석 처리된 줄, `${…}`가 든 값, `src/test` 아래의 파일은 보지 않는다. 읽은 방언이 여러 파일·프로파일(YAML 문서)에 걸쳐 하나로 모이지 않으면(서로 다른 방언) 쓰지 않는다. 설정에서 읽은 방언의 매퍼 파일이 없으면 진단 없이 다음 순위로 간다(명시한 요청이 아니다).
- **치환 목록(F-009-R21. sql 모듈 F-006 spec 5절과 같은 문구다)**: 내장 소스 `attrs`의 키는 `dynamicParts`다. 값은 본문의 n번째 `__DYNAMIC__` 자리에 있던 `${…}` 원문을 등장 순서대로, 건마다 `<원문 길이>:<원문>`으로 구분자 없이 이어 붙인 문자열이다(길이는 UTF-16 글자 수. 예: `13:${sqlMap.dsf}10:${orderBy}`). 원문은 `${`부터 `}`까지 매퍼에 쓴 그대로이고, 안에 줄바꿈이 있으면 줄바꿈을 낀 공백 묶음을 공백 한 칸으로 바꾼다(값에 줄바꿈이 없다). `${…}`가 없는 내장 소스에는 키가 없다. 주석이나 문자열 안의 `${…}`도 본문에서 치환되므로 목록에 든다(글자 그대로의 n번째다). `<include>`의 `<property>`로 값이 정해진 `${name}`은 치환이 아니라 목록에 없다.
- 구문·조각 노드의 `sql`은 대표 전개본(1번)의 `__DYNAMIC__`을 그 전개본의 치환 목록으로 되돌린 글이다(`#{}`는 전처럼 `?`).
- 방언별 매퍼(F-009-R15)에서 방언을 읽지 못한 파일의 정의는 고르는 대상이 아니라 그대로 남는다. 그래서 `Foo.xml`과 `Foo_oracle.xml`처럼 방언 파일이 하나뿐이면 방언 변형으로 보지 않고 지금처럼 합친 뒤 `DUPLICATE_STATEMENT_ID`를 남긴다. 고른 방언의 파일에 없는 id는 선호 순서상 다음 방언 파일의 정의를 쓴다(구문을 잃지 않으려고). 요약 진단은 프로젝트 전체에 대한 것이라 위치가 없다.
- 조각 정의가 여러 파일에 있으면(방언 파일을 전부 합칠 때, 중복 정의) include를 시작한 구문과 같은 파일, 그다음 같은 방언 파일의 정의를 먼저 쓴다.
- `id`에 점이 있는데 namespace로 시작하지 않는 요소(F-009-R13)의 진단 코드는 `PARSE_FAILED`(`WARN`)로 두었다. MyBatis가 매퍼를 읽다 실패하는 입력이기 때문이다.
- `<foreach>`의 `separator`는 한 번만 반복하므로 쓰지 않는다.
- include 안쪽 조각에 있는 한정하지 않은 refid는 MyBatis와 같이 include를 시작한 구문의 namespace에서 먼저 찾는다. 거기에 없고 그 조각이 정의된 namespace에 있으면 그것을 쓰고 엣지를 `INFERRED`로 둔다.
- `<include>`의 property 치환은 MyBatis와 같이 포함된 조각 안에서만 한다. 설정 파일의 `<properties>` 전역 변수는 치환하지 않는다(`__DYNAMIC__`이 된다).
- 길이를 보존하지 못하는 치환(11자보다 짧은 `${...}`)이 있는 정적 구문은 `exactPositions=false`로 넘긴다.
- 내장 소스 본문은 줄바꿈을 유지한다(SQL 줄 주석 `--`이 뒤 내용을 삼키지 않게). 노드의 `sql` 속성만 한 줄로 정리한다.
- 타입 별칭은 대소문자를 가리지 않는다. 별칭 패키지는 하위 패키지까지 보고, 파일 이름과 같은 최상위 타입만 별칭으로 본다(`@Alias`, 중첩 클래스는 보지 않는다). Java 파일의 패키지는 `package` 선언을 읽어 안다.
- FQN이 `java.`, `javax.`, `jakarta.`로 시작하면 JDK·표준 타입으로 보고 엣지를 만들지 않는다. 그 밖의 FQN은 프로젝트에 소스가 없어도 `java:type:` 참조 노드(`declared=false`)로 `RESOLVED` 엣지를 만든다.
- 한 별칭이 서로 다른 타입 둘 이상에 걸리면(패키지 스캔 충돌) 해석하지 않고 미해결로 남긴다.
- namespace가 없는 매퍼 파일은 ID를 만들 수 없으므로 `PARSE_FAILED`로 건너뛴다.
- 같은 namespace·id·`databaseId`인 구문이 여러 번 정의되면(여러 파일 포함) 노드 하나에 전개본을 모두 등록하고 `WARN` 진단 `DUPLICATE_STATEMENT_ID`를 남긴다. 방언별 파일로 여러 벌인 경우는 중복이 아니라 방언 변형으로 다룬다(F-009-R15).
- 파싱 실패 진단의 심각도는 `ERROR`다. 오류가 난 구문(닫히지 않은 구문)은 본문이 불완전하므로 등록하지 않는다.
- `mybatis-config.xml`은 루트가 `configuration`이고 `<typeAliases>`가 있는 XML로 판별한다(파일 이름은 보지 않는다).
- `statementType`의 `MERGE`는 본문의 첫 단어만 보고 정한다(SQL을 파싱하지 않는다). sql 모듈이 더 정확한 값으로 덮어써도 된다.
- 위치(`SourceLocation`)의 끝 줄·열은 마지막 글자의 다음 위치다(끝 제외). 계약에 정해진 바가 없어 이렇게 두었다.
- 위치를 보존한 본문에서는 `#{...}`와 엔티티 자리를 공백으로 채우므로(`#{id}` → `    ?`, `&lt;=` → `   <=`) 문자열 리터럴 안에 있던 엔티티 주변의 공백이 원문과 달라질 수 있다. 따옴표로 풀리는 엔티티(`&apos;`, `&quot;`)나 DTD에 정의한 엔티티가 있으면 위치 보존을 포기하고 `exactPositions=false`로 넘긴다.
- 같은 본문이 되는 전개본은 한 번만 등록한다. 한 구문의 정의가 여러 벌일 때도 같다(F-009-R16).
- 알 수 없는 태그(구식 iBatis 태그 등)는 본문만 전개하고 `INFO` 진단 `UNSUPPORTED_SYNTAX`를 구문·조각마다 한 건 남긴다(F-009-R18). iBatis 태그의 조건(`<isEqual compareValue>` 등)은 읽지 않으므로 배타적인 분기도 한 전개본에 함께 들어간다.
- `<bind>`는 본문의 모양을 바꾸지 않으므로 위치 보존을 막지 않지만(F-009-R17) 구문의 `dynamic`은 전과 같이 true로 둔다.
- **방언 순위(F-009-R24. sql 모듈 F-007 spec 5절과 같은 문구다. 한쪽을 고치면 다른 쪽도 고친다. 위 "선호 순서(F-009-R20, R22)"를 대체한다)**: ① 명시값 — DDL·데이터 스크립트는 `--dialect`, 그다음 `mybatis.dialectVariant=<방언>`; 매퍼는 `mybatis.dialectVariant=<방언>`, 그다음 `--dialect` ② 프로젝트 설정이 대상 방언(oracle, db2)을 가리키면 그 방언 ③ Oracle ④ DB2 ⑤ Oracle 계열(Tibero, Altibase. 프로젝트 설정이 이 가운데 하나를 가리키면 그것이 먼저) ⑥ 프로젝트 설정이 가리키는 대상 외 방언 ⑦ 그 밖의 방언: 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 이름의 사전순으로 먼저, 나머지가 사전순으로 그 뒤(F-007-R28·F-009-R26. 2026-10-11 고쳐 씀: 전에는 DDL·데이터 스크립트가 경로순 첫 파일의 방언, 매퍼가 방언 토큰 표의 순서여서 대상 방언 파일이 없는 프로젝트에서 서로 다른 방언을 골랐다). 설정이 mysql·hsql 같은 대상 외 방언이어도 대상 방언의 파일이 있으면 그쪽을 고른다(이 도구의 대상 방언은 Oracle·DB2이고, 전자정부 템플릿의 기본값은 `Globals.DbType=mysql`·`hsql`이다). 요약 진단(`DIALECT_VARIANT_DDL`/`DIALECT_VARIANT_MAPPERS`)은 그때 INFO로 "프로젝트 설정(<파일>:<키>=<값>)과 다르게 대상 방언 <방언> 파일을 사용. 설정대로 분석하려면 `--opt mybatis.dialectVariant=<설정의 방언>`"을 적는다(`--dialect`는 oracle·db2만 받으므로 대상 외 방언은 이 옵션으로 준다. 이 옵션은 DDL 선택에도 쓰인다). 설정에 따라 고른 방언이 대상 방언(과 그 계열)이면 "프로젝트 설정(…)에 따라 <방언> 선택"이다. 대상 방언과 그 계열의 파일이 하나도 없어 ⑥·⑦로 대상 외 방언을 쓰게 될 때만 WARN이고 "이 도구의 대상 방언(Oracle, DB2)의 파일이 없어 <방언> 파일을 씀: SQL 해석이 부정확할 수 있음"을 적는다(명시해 요청한 방언이면 INFO다). 프로젝트 설정이 파일마다 엇갈리면 쓰지 않고 요약에 "프로젝트 설정이 엇갈려 쓰지 않음(<파일>=<방언>, …)"을 적는다.
- **보충한 구문(F-009-R25)**: 대표 방언은 감지한 방언(선호 순서) 가운데 구문을 하나라도 고른 첫 방언이다. 구문의 `dialectVariant`가 대표 방언과 다르면 보충한 구문이고 `borrowedFrom`에 그 방언을 적는다(`dialectVariant`와 값이 같다. 소비자가 "고른 방언의 SQL이 아닌 구문"을 한 번에 가리게 하려는 표시다). 요약에는 방언별 수와 구문 ID를 적되 ID는 요약 하나에 모두 합쳐 20개까지고 넘으면 "외 n개"다. 주석 처리 판정은 그 namespace의 파일 가운데 선호 순서가 가장 앞인 방언의 파일 본문에서 XML 주석(`<!-- … -->`) 안의 `<select|insert|update|delete … id="…">`를 찾는다. 조각(`<sql>`)과 resultMap에는 적용하지 않는다. `mybatis.dialectVariant=all`이면 고르지 않으므로 둘 다 없다.
- **그 밖의 방언의 순서(F-009-R26. sql 모듈 F-007 spec 5절과 같은 문구다. 한쪽을 고치면 다른 쪽도 고친다)**: "그 밖의 방언"은 방언 토큰 표에서 대상 방언과 그 계열(oracle, db2, tibero, altibase)을 뺀 것이다. 두 분석기는 서로 의존하지 않으므로(ADR-0007) 각자 프로젝트의 파일 목록(`AnalysisContext.files()`)에서 같은 순서를 계산한다: 스캐너가 `MYBATIS_MAPPER`로 분류한 파일들의 경로에서 읽은 방언과 `SQL`로 분류한 파일들의 경로에서 읽은 방언(방언 토큰 표의 경로 규칙 그대로)의 교집합이 표준 이름의 사전순으로 먼저고, 나머지 방언이 사전순으로 그 뒤다. 사전순은 `cubrid`, `derby`, `goldilocks`, `h2`, `hsql`, `informix`, `mariadb`, `mssql`, `mysql`, `postgres`, `sqlite`, `sybase`다. 파일 내용은 읽지 않는다. 이 순서는 다른 방언에서 보충할 때(고른 방언 파일에 없는 구문·테이블)와 요약 진단의 방언 나열에도 쓰인다: 전에는 mysql과 hsql에만 있는 구문을 mysql에서 가져왔고 이제는 hsql에서 가져온다. 사전순으로 정한 까닭: 방언 토큰 표의 순서(postgres, mysql, …)는 근거가 없는 나열이고, 경로순은 폴더 이름을 방언 이름으로 두는 배치에서 사전순과 같다(sql 모듈의 기존 테스트는 기대값을 바꾸지 않고 통과했다). [확인 필요] 공개 프로젝트를 다시 돌려 고른 방언이 바뀌는지는 재 보지 않았다. 두 모듈의 규칙이 같은지는 양쪽 테스트의 같은 표(`DialectTokenTableTest.otherDialectOrder`, sql의 `DialectTokensTest.otherDialectOrder`)가 확인한다.

### 알려진 한계
- 그 밖의 방언의 순서(F-009-R26)는 경로만 본다. 그래서 대상 방언 파일이 없는 프로젝트에서 매퍼와 DDL의 방언이 아직 어긋날 수 있는 경우가 남는다: (1) sql 모듈이 경로가 아니라 내용 특징으로 방언을 읽은 스크립트(F-007-R24)는 "양쪽에 다 있는 방언"에 들지 않는다. (2) 경로에는 양쪽에 다 있는 방언이어도 한쪽에서는 여러 벌이 아닐 수 있다(그 방언의 매퍼 파일이 다른 방언과 namespace를 나누지 않거나, 그 방언의 DDL이 다른 방언과 같은 테이블을 정의하지 않는다). 그러면 그쪽은 여러 벌인 방언들 가운데 순서가 가장 앞선 것을 고른다. (3) 양쪽에 다 있는 방언이 하나도 없으면 각자 자기 쪽 방언의 사전순 첫째를 고른다. 어긋나면 cli가 힌트에 `경고:`를 낸다(F-001-R52). `--opt mybatis.dialectVariant=<방언>`으로 맞춘다.
- Java 설정(`setTypeAliasesPackage(...)`, `@MapperScan`)과 `@Alias` 애너테이션에서 오는 별칭은 읽지 않는다. 이런 별칭은 단순 이름이 유일하면 `INFERRED`, 아니면 미해결로 남는다.
- `mybatis-config.xml`의 `<properties>`와 Spring 프로퍼티 치환식(`${...}`)으로 준 별칭 패키지는 풀지 않는다(진단을 남긴다).
- `<selectKey>`의 `resultType`, `<parameterMap>`, 구문의 `parameterMap` 속성으로는 `USES_TYPE`을 만들지 않는다.
- "식 == 리터럴" 꼴이 아닌 조건으로 배타적인 `<if>`는 한 전개본에 함께 들어가므로 전개본이 문법에 맞지 않을 수 있다.
- 등치 조건의 값이나 `<choose>` 분기가 많아 전개본이 16개를 넘으면 넘는 값·분기의 본문은 어느 전개본에도 없다(진단은 남는다). 전에는 `<if>` 본문이 전부 1번 전개본에 있었으므로, 한 식을 17개 넘는 값과 비교하는 구문은 전보다 덜 보인다. 공개 프로젝트 7개에서는 이런 구문이 없었다(최대 11개).
- 등치 꼴이 아닌 `<if>`끼리는 test가 글자 그대로 같아도 "함께 빠진" 전개본을 따로 만들지 않는다(모든 전개본에 함께 들어간다). 그런 전개본은 1번 전개본의 일부라 새로 알려 주는 테이블·컬럼이 없고, 검색 조건 `<if>`가 많은 구문에서 전개본이 test 가짓수만큼 늘어 상한 16개를 넘기 때문이다. 같은 test의 `<when>`이 거짓으로 고정된 전개본에서만 빠진다(F-009-R23).
- 바깥 조건과 모순이라 MyBatis에서도 실행되지 않는 분기(`<when test="K">`의 `<otherwise>` 안에 다시 `<when test="K">`, `<if test="t == 'A'">` 안의 `<when test="t == 'B'">`)의 본문은 어느 전개본에도 없다.
- 파일 이름을 낙타 표기로 붙여 쓴 방언(`OracleUserMapper.xml`)은 읽지 않는다.
- XML 선언 앞에 공백이나 빈 줄이 있는 파일은 XML 파서가 거부한다(`PARSE_FAILED`). MyBatis도 같은 파일을 읽지 못한다.

## 6. 미확정 사항
- 없음. 가정으로 정한 기본값은 5절에 있다.

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: 없음

## 8. 그래프 계약에 더하자고 제안하는 것
design.md에 반영할 것(2026-10-11, 그 밖의 방언의 순서 F-009-R26·F-007-R28. ~~아직 반영 전~~ design.md 반영됨(2026-10-11: 3.5 "방언 변형 선택" ⑦, 7절 한계 표). F-007 spec 8절의 같은 날짜 항목과 한 묶음이다): 3.5 공통 규칙 "방언 변형 선택"의 ⑦을 "그 밖의 방언: 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 이름의 사전순으로 먼저, 나머지가 사전순으로 그 뒤(mybatis와 sql이 같은 파일 목록에서 같은 순서를 계산한다). sql의 방언을 읽지 못한 파일은 처음 물은 순서(경로순)로 그 밖의 방언들 앞이나 뒤에 선다"로 바꾼다. 7절 한계 표의 "대상 방언의 파일이 없으면 매퍼와 DDL의 방언이 갈릴 수 있다" 행은 남은 경우(이 spec 5절 알려진 한계의 (1)~(3))로 고쳐 쓴다. ADR-0010의 순위 7번은 같은 날 고쳤다.

design.md 반영 요청(2026-10-11, 재검증 3차 후속·방언 정책. ~~아직 반영 전~~. F-007 spec 8절의 같은 날짜 항목과 한 묶음이다):
design.md 반영됨(2026-10-11): `borrowedFrom`은 3.5(공통 규칙 "방언별 매퍼", 노드 attrs 표), `DIALECT_VARIANT_MAPPERS`의 수준과 문구는 3.7, 방언 순위와 XML 주석으로 뺀 id는 3.5 공통 규칙 "방언 변형 선택"·"방언별 매퍼"에 넣었다(4.1이 아니라 3.5에 모았다). 정책의 근거와 대안은 ADR-0010(제안 상태)에 적었다.
- 3.5 SQL_STATEMENT(매퍼 구문) attrs: `borrowedFrom`(String. 고른 방언의 파일에 없어 다른 방언 파일에서 가져온 구문이면 그 방언. `dialectVariant`와 같은 값이다. 없으면 키를 넣지 않는다).
- 3.7 `DIALECT_VARIANT_MAPPERS`: 수준과 문구가 바뀌었다(설정과 다르게 대상 방언을 골랐으면 INFO와 안내, 대상 방언 파일이 없어 대상 외 방언을 쓸 때만 WARN, 엇갈린 설정, 보충한 구문의 ID 목록, XML 주석으로 뺀 구문).
- 4.1 방언 선택: 방언 순위(F-009-R24). 선택한 방언 파일에서 XML 주석으로 뺀 id는 다른 방언에서 보충하지 않는다.

이 모듈이 쓰는, 계약 3.5 표에 아직 없는 키다. 계약 문서에는 조정자가 반영한다.

design.md에 반영됨(2026-10-11): 아래 첫 표의 항목은 모두 F-001 design.md 3.5·3.6·3.7·3.9에 있다.

design.md에 반영됨(2026-10-11, 2차): 아래 표의 항목은 F-001 design.md에 있다. `mybatis.dialectVariant`는 3.8, `dialectVariant`·`statementId`·`variants`는 3.5(공통 규칙 "방언별 매퍼"와 노드 attrs 표), 점 있는 id의 ID 규칙은 3.3, 내장 소스의 `dialect`는 3.9, 진단은 3.7, 등치 `<if>`의 전개본은 4.1과 7절에 넣었다.

| 대상 | 키 | 뜻 |
|---|---|---|
| 옵션 키(3.8) | `mybatis.dialectVariant` | `auto`(기본), 방언 이름(`oracle`, `db2`, `tibero`, `altibase`, `postgres`, `mysql`, `mariadb`, `mssql`, `cubrid`, `goldilocks`, `hsql`, `h2` 등), `all`. 방언별 매퍼 여러 벌 가운데 쓸 방언. `all`은 전부 합친다. 모르는 값은 `WARN` `INVALID_OPTION`을 남기고 `auto`. 읽는 모듈: mybatis |
| MYBATIS_MAPPER, SQL_STATEMENT(매퍼), SQL_FRAGMENT attrs(3.5) | `dialectVariant` | 방언별 매퍼 여러 벌 가운데 이 노드의 정의를 가져온 파일의 방언(소문자 표준 이름). 방언 변형이 아니거나 `all`이면 없다 |
| SQL_STATEMENT(매퍼) attrs(3.5) | `statementId` | namespace를 뗀 짧은 id. XML의 id가 `<namespace>.`로 시작하면 그 뒤. 점이 있는데 namespace로 시작하지 않는 id는 원문 그대로이고, 이때 노드 ID는 `mybatis:stmt:<원문 id>`다 |
| | `variants` | 본문과 `databaseId`가 같은 전개본은 한 번만 센다(정의가 여러 벌이어도) |
| `sql` 내장 소스 attrs(3.9) | `dialect` | `databaseId`가 없고 방언별 파일에서 온 구문이면 그 파일의 방언(`oracle`, `mysql` 등) |
| 진단 코드(3.7) | `DIALECT_VARIANT_MAPPERS` | `INFO`. 방언별 매퍼 여러 벌을 감지함. 분석 한 번에 한 건, 위치 없음. 메시지에 감지한 방언, 쓴 방언, 제외한 파일 수, 옵션 키 |
| | `DUPLICATE_STATEMENT_ID` | 방언별 파일 사이의 같은 id에는 더 남기지 않는다 |
| | `PARSE_FAILED`(`WARN`) | 요소 id에 점이 있는데 namespace로 시작하지 않음(원문을 ID로 씀) |
| | `UNSUPPORTED_SYNTAX`(`INFO`) | 알 수 없는 태그는 태그마다가 아니라 구문·조각마다 한 건 |
| 7절 위험 표의 "`<if>` 안의 상수 조건도 고정 컬럼이 된다"와 4.1의 전개본 설명 | | "모든 `<if>` 본문을 넣은 전개본"에 예외가 생겼다: "식 == 리터럴" 꼴의 `<if>`는 값마다 전개본이 나뉜다(F-009-R14). 한 구문의 전개본 수가 늘고 전개본마다 한 값의 본문만 있다 |

design.md 반영 요청(2026-10-11, 3차 재검증 후속. ~~아직 반영 전~~):
design.md 반영됨(2026-10-11): `DIALECT_NOT_FOUND`와 `DIALECT_VARIANT_MAPPERS`는 3.7, `mybatis.dialectVariant`의 별칭·버전 숫자·읽는 모듈(sql)은 3.8, 방언 토큰을 읽는 규칙은 3.5 공통 규칙 "방언 변형 선택", `dynamicParts`와 `__DYNAMIC__`은 3.9, 구문·조각의 `sql`은 3.5 노드 attrs 표에 넣었다. 이 블록의 "`auto`의 순서"와 "프로젝트 설정에서 읽은 대상 외 방언을 골랐으면 `WARN`"은 위 블록(F-009-R24)이 대체했으므로 코드대로 위 블록의 내용을 적었다.
- 3.7 "모듈이 정의한 코드": `DIALECT_NOT_FOUND` — `WARN`. 요청한 방언(`mybatis.dialectVariant`, `--dialect`)의 매퍼 파일(mybatis)·DDL·데이터 스크립트 파일(sql)이 하나도 없어 다른 방언으로 대체함. 모듈마다 분석 한 번에 1건, 위치 없음. 메시지에 요청한 방언과 옵션 이름, 감지한 방언, 대체한 방언. 모듈 mybatis, sql.
- 3.7 `DIALECT_VARIANT_MAPPERS`: 심각도가 `INFO` 또는 `WARN`이다. 프로젝트 설정에서 읽은 방언을 골랐고 그것이 대상 방언(Oracle, DB2, Tibero, Altibase)이 아니면 `WARN`이고 메시지에 근거(`<파일>:<키>=<값>`)와 `--dialect oracle` 안내가 있다.
- 3.8 `mybatis.dialectVariant`: 값에 별칭 `ora`와 끝에 버전 숫자가 붙은 이름(`mysql55`)도 받는다. `auto`의 순서는 "`--dialect`, 프로젝트 설정에서 읽은 방언, 목록 순서"다. 읽는 모듈에 sql을 더한다(DDL·데이터 스크립트 선택, F-007-R18).
- 3.5 공통 규칙 "방언별 매퍼": 방언은 파일 이름을 글자·숫자가 아닌 문자로 나눈 토큰(뒤에서부터)이나 폴더 이름에서 읽는다(전에는 `_`·`-`·`.`만 나눴다). 토큰 표와 프로젝트 설정 키는 F-009 spec 5절.
- 3.9 `sql` 내장 소스 attrs: `dynamicParts` — 치환 목록(형식은 F-009 spec 5절 "치환 목록"). 넣는 모듈 mybatis. 자리표시자 표의 `__DYNAMIC__` 행에 "이 식별자는 분석 중에만 쓰는 것이라 그래프에 남기지 않는다: 구문·조각의 `sql`, sql 분석기의 진단 메시지, 미해결 `sql-dynamic`의 이름과 `attrs.text`는 치환 목록으로 `${…}` 원문을 되살리고, 목록이 없는 내장 소스(java의 애너테이션 SQL 등)에서는 `${…}`로 적는다"를 더한다.
- 3.5 SQL_STATEMENT(매퍼 구문)·SQL_FRAGMENT의 `sql`: `${…}` 자리는 원문 그대로다(전에는 `__DYNAMIC__`).

design.md 반영 요청(2026-10-11, 3차. ~~아직 반영 전~~ design.md 반영됨(2026-10-11): 4.1 내장 소스 규칙의 전개본 설명): 4.1의 전개본 설명에 "한 구문 안에서 조건이 같은 `<when>`은 함께 움직인다"를 더한다(F-009-R23). 노드·엣지·내장 소스의 키와 진단 코드는 바뀌지 않는다. 바뀌는 것은 전개본의 내용과 개수뿐이다: 조건이 겹치는 `<choose>`가 있는 구문은 분기가 엇갈린 전개본이 없어져 `variants`가 줄 수 있고, `<otherwise>`의 전개본에서는 같은 식의 등치 `<if>` 본문이 빠진다.

| 대상 | 키 | 뜻 |
|---|---|---|
| MYBATIS_MAPPER | `namespace` | 매퍼 namespace |
| SQL_STATEMENT (매퍼) | `tag` | `select`, `insert`, `update`, `delete` |
| | `parameterType`, `resultType`, `resultMap` | XML 속성 원문 |
| | `databaseIds`(List) | 이 구문의 정의에 붙은 `databaseId` 값들 |
| | `fetchSize`(int), `timeout`(int), `useGeneratedKeys`(boolean), `keyProperty`, `jdbcStatementType` | XML 속성. `jdbcStatementType`은 XML의 `statementType`(`STATEMENT`, `PREPARED`, `CALLABLE`) |
| | `variants`(int) | 등록한 전개본 수(selectKey 제외) |
| | `statementType` 값 `MERGE` | 태그가 `update`·`insert`여도 본문이 `MERGE INTO`로 시작하면 mybatis가 채운다 |
| | `selectKey`(boolean) | `<selectKey>`가 있으면 true |
| | `resultColumns`(List) | resultMap의 `COLUMN=property` 대응. 200개까지 |
| | `nestedSelects`(List) | resultMap의 중첩 select가 가리키는 구문 `<namespace>.<id>` |
| SQL_FRAGMENT | `namespace`, `fragmentId`, `sql`, `dynamic` | 조각 단독 전개본(2000자) |
| USES_TYPE 엣지 | `role` | `parameter`, `result`, `resultMap` |
| | `alias` | XML에 적힌 이름이 FQN과 다를 때 그 원문 |
| `sql` 내장 소스 attrs | `variant` | 구문 안의 전개본 번호(1부터) |
| | `dialect` | `databaseId` 값(소문자) |
| | `selectKey` | `true`면 `<selectKey>` 본문 |
| | `databaseId` | `databaseId` 원문 |
| UNRESOLVED 분류 | `mybatis-fragment`, `mybatis-type`, `mybatis-resultmap` | 못 찾은 조각, 타입, resultMap |
| 진단 코드 | `DUPLICATE_STATEMENT_ID` | 같은 구문 ID가 같은 `databaseId`로 여러 번 정의됨 |
