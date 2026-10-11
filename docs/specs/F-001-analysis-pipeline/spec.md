---
type: spec
id: F-001
title: 분석 파이프라인과 그래프 모델
status: implemented     # draft | approved | implemented
updated: 2026-10-11
---

# F-001 분석 파이프라인과 그래프 모델

## 1. 배경
PRD의 세 목표(콜트리와 관계 분석, ERD, 클래스 다이어그램)는 Java, 매퍼 XML, SQL, 화면 스크립트의 분석 결과가 한 그래프에 모여야 이룰 수 있다. 이 기능은 그 그래프 모델과, 파일을 분류하고 분석기를 실행해 결과를 JSON으로 저장하는 뼈대와 명령줄 진입점을 만든다. 다른 모든 기능(F-002~F-016)이 이 위에서 동작한다.

## 2. 범위
- 포함: 그래프 모델과 노드 ID 생성 규칙, 파일 스캐너와 인코딩 판별, 분석기·링커·출력기 SPI와 파이프라인, 내장 소스 전달, `graph.json` 저장과 복원, `analyze`·`summary` 명령과 실행 jar, 저장된 그래프를 읽는 조회용 하위 명령(`erd`, `classdiagram`, `calltree`, `impact`, `crud`, `find`, `report`)과 `export` 명령의 명령줄(옵션, 종료 코드, 도움말).
- 제외: 언어별 분석기(F-002~F-011), 교차 연결(F-012), 다이어그램·콜트리·영향도·CRUD·리포트의 내용과 모양(F-013~F-015의 조회 API가 정한다), 뷰어(F-016).

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-001-R1 | 시스템은 프로젝트 폴더의 파일을 설계 4.2의 파일 종류로 분류해야 한다. XML은 루트 요소로 MyBatis 매퍼, Spring 설정, 그 밖의 XML을 구분한다. | design 4.2 |
| F-001-R2 | 시스템은 기본 제외 디렉터리와 `--exclude` glob에 맞는 파일을 분석 대상에서 빼야 한다. 단, `target`, `build`, `dist`, `out`, `bin`은 조상 경로에 `src` 디렉터리가 있으면 빼지 않는다. | design 4.2, 4.5 |
| F-001-R3 | 프로젝트 밖의 경로가 `--ddl`로 주어지면, 시스템은 그 파일을 `ddl/<파일명>` 형태의 가상 상대 경로로 분석 대상에 넣어야 한다. | design 4.1, PRD 3 (DDL은 파일로 받는다) |
| F-001-R4 | 시스템은 소스 파일을 UTF-8로 엄격하게 디코딩하고 실패하면 MS949로 읽어야 한다. `--encoding`이 주어지면 그 인코딩만 쓴다. | design 4.3, PRD 7 |
| F-001-R5 | 시스템은 같은 ID의 노드를 하나로 병합하고(`declare`, `reference` 규칙) `(from, to, kind, location)`이 같은 엣지를 한 번만 저장해야 한다. | design 3.2 |
| F-001-R6 | 시스템은 설계 3.3의 형식대로 노드 ID를 만드는 수단을 제공해야 한다. DB 객체 이름은 대문자로 정규화하고 따옴표로 감싼 식별자는 원문 그대로 둔다. | design 3.3 |
| F-001-R7 | 시스템은 `ServiceLoader`로 찾은 분석기, 링커, 출력기를 스캔 → 분석기(`order` 오름차순) → 링커(`order` 오름차순) → 마무리 → 저장 → 출력기 순서로 실행해야 한다. | design 1, 4.1 |
| F-001-R8 | 만약 분석기 하나가 예외를 던지면, 시스템은 `ERROR` 진단을 남기고 다음 분석기를 계속 실행해야 한다. | design 4.1, architecture 품질 목표 2 |
| F-001-R9 | 마무리 단계에서 엣지의 `from` 또는 `to`가 없는 노드를 가리키면, 시스템은 `UNRESOLVED` 노드를 만들고 그 엣지의 신뢰도를 `UNRESOLVED`로 바꿔야 한다. | design 3.2, PRD 5 (조용히 누락되는 것이 없다) |
| F-001-R10 | 시스템은 그래프를 설계 4.4 형식의 `graph.json`으로 ID 순으로 정렬해 저장하고, 그 파일에서 같은 그래프를 복원할 수 있어야 한다. | design 4.4, ADR-0006 |
| F-001-R11 | `analyze` 명령이 실행되면, 시스템은 출력 폴더에 `graph.json`과 `diagnostics.md`를 쓰고 요약(파일 종류별 개수, 노드·엣지 종류별 개수, 신뢰도별 엣지 수, 진단 수)을 표준 출력에 찍어야 한다. | design 4.5 |
| F-001-R12 | 시스템은 실행 가능한 jar 하나로 패키징되어야 한다. | PRD 7 (가정), ADR-0002 |
| F-001-R13 | 시스템은 분석기가 등록한 내장 소스를 순서가 뒤인 분석기가 언어별로 꺼낼 수 있게 해야 한다. | design 1, 4.1 |
| F-001-R14 | 시스템은 한 노드의 엣지를 모두 다른 노드로 옮기고 그 노드를 지우는 수단(`replaceNode`)을 제공해야 한다. | design 3.2 (F-012 링커가 참조 스텁을 합칠 때 쓴다) |
| F-001-R15 | `analyze`에 `--opt <key>=<value>`가 주어지면, 시스템은 그 값들을 `AnalysisOptions.extra`에 담아 분석기, 링커, 출력기에 전달해야 한다. | design 4.5 |
| F-001-R16 | `analyze`에 `--no-export`가 주어지면 시스템은 출력기를 실행하지 않아야 하고, `--quiet`가 주어지면 진행 메시지와 요약을 찍지 않아야 한다. 두 경우 모두 `graph.json`과 `diagnostics.md`는 쓴다. | design 4.5 |
| F-001-R17 | `summary <graph.json>` 명령이 실행되면, 시스템은 저장된 그래프에서 `analyze`가 찍는 것과 같은 요약을 표준 출력에 찍어야 한다. | design 4.5 |
| F-001-R18 | 실행 jar는 의존 모듈이 실행할 때 읽는 리소스(tree-sitter 네이티브 라이브러리, 뷰어 템플릿과 Mermaid 번들)를 담아야 하고, 네이티브 접근 경고 없이 다른 classpath 없이 동작해야 한다. | design 4.5, ADR-0002 |
| F-001-R19 | 조회용 하위 명령은 저장된 그래프를 첫 위치 인자 또는 `--graph <graph.json 또는 출력 폴더>`에서 읽어야 하고, 둘 다 없으면 현재 폴더의 `codeatlas-out`을 읽어야 한다. 결과는 표준 출력에 쓰고 `--out <파일>`이 주어지면 그 파일에 쓴다. | design 4.5, PRD 7 (명령줄로 실행) |
| F-001-R20 | 만약 조회용 하위 명령의 옵션이 잘못되면 시스템은 종료 코드 2와 그 명령의 사용법을, 그래프를 읽지 못하거나 대상이 없으면 종료 코드 1과 이유를 표준 오류에 내야 한다. 예외 스택을 내지 않는다. | design 4.5, PRD 5 (조용히 누락되는 것이 없다) |
| F-001-R21 | `help <명령>` 또는 `<명령> --help`가 실행되면, 시스템은 그 명령의 사용법과 옵션 설명만 표준 출력에 찍어야 한다. | design 4.5 |
| F-001-R22 | `erd` 명령이 실행되면, 시스템은 범위·표시 옵션을 F-013의 조회 API에 전달해 ERD를 Mermaid, PlantUML, DBML 중 하나로 내야 한다. `--spec`이 주어지면 테이블 명세(Markdown)를 낸다. | PRD 시나리오 1, F-013 |
| F-001-R23 | `classdiagram` 명령이 실행되면, 시스템은 범위·표시 옵션을 F-014의 조회 API에 전달해 클래스 다이어그램을 Mermaid나 PlantUML로 내야 한다. | PRD 시나리오 2, F-014 |
| F-001-R24 | `calltree <검색어 또는 노드 ID>` 명령이 실행되면, 시스템은 시작 노드를 찾아 F-015의 콜트리를 text, json, mermaid 중 하나로 내야 한다. 만약 후보가 여럿이면 번호·종류·ID·위치 목록과 좁히는 방법을 알리고 종료 코드 1로 끝내야 한다. | PRD 시나리오 3, F-015 |
| F-001-R25 | `impact <테이블 \| 테이블.컬럼 \| 노드 ID>` 명령이 실행되면, 시스템은 F-015의 영향도 리포트를 md나 json으로 내야 한다. | PRD 시나리오 4, F-015 |
| F-001-R26 | `crud` 명령이 실행되면, 시스템은 F-015의 CRUD 매트릭스를 주체(구문, 메서드, 엔드포인트, 루틴), 모양, 형식(csv, md)에 맞춰 내야 한다. | PRD 시나리오 4, F-015 |
| F-001-R27 | `find <검색어>` 명령이 실행되면, 시스템은 맞는 노드를 번호·종류·ID·위치 목록으로 내야 한다. | design 4.5 (calltree, impact에 줄 ID를 찾는 수단) |
| F-001-R28 | `report` 명령이 실행되면, 시스템은 저장된 그래프의 분석 요약 리포트(F-015의 `report.md`와 같은 내용)를 내야 한다. | PRD 5 (미해결이 리포트에 나온다), F-015 |
| F-001-R29 | `export <graph> --out <dir>` 명령이 실행되면, 시스템은 분석을 다시 하지 않고 저장된 그래프로 등록된 출력기를 모두 실행해야 한다. `--opt <key>=<value>`는 출력기에 전달한다. | design 4.5 |
| F-001-R30 | 시스템은 분석할 때 받은 옵션(방언, 인코딩, classpath·DDL 경로, 제외 패턴, `--opt`)을 `graph.json`의 `project.options`에 저장하고 복원해야 한다. 경로는 입력 그대로 저장한다. `schemaVersion`은 바꾸지 않고, 이 항목이 없는 파일은 빈 옵션으로 읽는다. | design 4.4 (통합 단계 개선) |
| F-001-R31 | 시스템은 파이프라인의 일부만 실행하는 수단을 제공해야 한다: 분석 없이 이미 있는 그래프로 출력기만 실행하고 실패(`EXPORTER_FAILED`, `SERVICE_LOAD_FAILED`)와 새 진단을 결과로 돌려주는 것, 출력기 없이 분석과 저장만 하는 것. | design 4.1 (통합 단계 개선) |
| F-001-R32 | `export` 명령이 실행되면, 시스템은 그래프에 저장된 `analyze` 때의 `--opt`를 기본값으로 쓰고 `export`의 `--opt`로 같은 키를 덮어써야 한다. | design 4.5 (통합 단계 개선) |
| F-001-R33 | 도움말과 README에 나오는 `--opt` 키 예시는 설계 3.8의 키여야 하고, 출력기가 읽는 키 목록은 설계 3.8의 출력기 키 표와 같아야 한다. | design 3.8 (통합 단계 개선) |
| F-001-R34 | `erd --spec`이 범위 옵션(`--table`, `--depth`, `--pattern`, `--views`)이나 관계 옵션(`--no-inferred`, `--min-evidence`)과 함께 주어지면, 시스템은 그 옵션을 ERD와 같은 규칙으로 적용한 테이블 명세를 내야 한다. | F-013-R13 (통합 단계 개선) |
| F-001-R35 | `erd`와 `classdiagram`은 찾지 못한 `--table`, `--type` 이름을, `impact`는 찾지 못한 대상과 이름이 비슷한 후보를 조회 API가 결과와 함께 돌려준 것으로 알려야 한다(명령이 이름 해석 규칙을 따로 흉내 내지 않는다). | F-013-R14, F-015-R16 (통합 단계 개선) |
| F-001-R36 | `reference`가 참조 노드(`declared=false`)를 새로 만들 때, 시스템은 `qualifiedName`을 노드 ID에서 접두사를 뗀 나머지로 채우고 `name`은 호출자가 준 값 그대로 두어야 한다(소비자가 ID에서 이름을 복원하지 않아도 되게). 이미 있는 노드는 바꾸지 않고, 나중의 `declare`는 지금처럼 자기 값으로 덮어쓰며, 마무리 단계가 만드는 `UNRESOLVED` 노드는 바꾸지 않는다. | design 3.2 (통합 단계 개선) |
| F-001-R37 | `GraphModel`의 `incoming(id, kinds…)`, `outgoing(id, kinds…)`, `edges(from, kind, to)`는 그 노드의 엣지 전체가 아니라 고른 종류의 엣지 수에 비례하는 일만 해야 한다(노드별·종류별 인덱스). 결과의 순서(엣지를 넣은 순서), 돌려주는 목록이 복사본이고 바꿀 수 없다는 것, 스레드 안전성, 공개 시그니처는 그대로다. | 실제 프로젝트 검증(엣지 500만 개 그래프에서 출력기가 끝나지 않음: 컬럼으로 들어오는 엣지 수만 개를 엣지마다 복사했다) |
| F-001-R38 | 마무리 단계가 매달린 엣지의 끝점으로 `UNRESOLVED` 노드를 만들 때, 시스템은 `attrs.expectedPrefix`와 함께 그 접두사에서 유도한 미해결 분류를 `attrs.category`에 채워야 한다: `mybatis:stmt` → `mybatis-statement`, `java:method` → `java-call`, `java:type` → `java-type`, `db:routine` → `db-routine`, `spring:bean` → `spring-bean-ref`, `js:module` → `js-module`, `js:function` → `js-call`, `jsp:page` → `jsp-include`, `http:endpoint` → `http-endpoint`, 그 밖에는 접두사의 `:`을 `-`로 바꾼 값. | 실제 프로젝트 검증(리포트에 `mybatis:stmt`, `java:method` 같은 접두사 절이 나옴), design 3.6 |
| F-001-R39 | 분석기·링커·마무리·저장·출력기 실행 중 `OutOfMemoryError`가 나면, 시스템은 계속하지 않고 어느 단계의 어떤 구현체가 무엇을 처리하던 중이었는지와 대처 방법(큰 데이터 스크립트를 `--exclude`로 빼거나 `-Xmx`를 늘린다)을 한 줄 메시지로 담은 `AnalysisOutOfMemoryError`(`OutOfMemoryError`의 하위 타입, 원인은 `getCause()`)로 바꿔 던져야 한다. 처리 중인 것은 구현체가 `AnalysisContext.progress(String)`로 알린 마지막 값이다(default 메서드라 이 훅을 모르는 구현체도 그대로 동작한다). | 실제 프로젝트 검증(큰 INSERT 스크립트에서 스택만 찍고 죽음) |
| F-001-R40 | `calltree` 명령은 기본으로 컨트롤러가 그리는 JSP 아래를 펼치지 않고(조회 API의 기본값과 같다, F-015-R33) 접근자 호출을 접어야 한다(표준 산출물과 같다, F-015-R35). `--views`가 주어지면 JSP 아래까지 펼치고, `--show-accessors`가 주어지면 접근자 호출도 보인다. 전에 쓰던 `--no-views`는 오류로 보지 않고 받되 아무 효과가 없으며 도움말에 적지 않는다. | 실전 검증 2차 수정(export API의 기본값 변경) |
| F-001-R41 | `calltree`와 `crud`는 데이터 적재 구문(`dataLoad=true`)을 기본으로 빼고 `--include-data-load`가 주어지면 넣어야 한다. `crud`의 도움말은 `--depth`의 뜻을 기준별로(엔드포인트 기준은 엔드포인트에서 구문까지의 호출 깊이, 그 밖에는 루틴 호출 걸음 수) 적어야 한다. | 실전 검증 2차 수정(F-015-R28) |
| F-001-R42 | 명령 실행 중 `OutOfMemoryError`가 나면, 시스템은 스택을 찍지 않고 이유와 대처 방법을 표준 오류에 두 줄로 알린 뒤 종료 코드 3(자원 부족)으로 끝나야 한다. 파이프라인이 던진 `AnalysisOutOfMemoryError`(F-001-R39)면 그 메시지(단계, 구현체, 처리 중이던 것, `--exclude`·`-Xmx` 안내)를 그대로 쓰고, 둘째 줄에 JVM 옵션을 주는 방법(`JAVA_OPTS`)을 적는다. `bin/codeatlas`는 `JAVA_OPTS` 환경 변수의 값을 `java`에 넘겨야 한다. | 실전 검증(큰 INSERT 스크립트에서 스택만 나오고 끝남), F-001-R39 |
| F-001-R43 | `analyze`에 `--exclude-tests`가 주어지면, 시스템은 어느 깊이에 있든 `src/test` 폴더 아래를 분석에서 빼야 한다(`--exclude src/test`와 같다). 주지 않으면 테스트 소스도 분석한다. | 실전 검증(테스트 코드가 미해결 호출의 대부분과 가짜 구현 줄을 만들었다) |
| F-001-R44 | `analyze`와 `summary`는 요약 끝에 "힌트" 절을 두고, 진단과 그래프에서 다음이 확인될 때만 한 줄씩 적어야 한다: 방언별 매퍼를 한 벌만 골랐음(`DIALECT_VARIANT_MAPPERS`. 쓴 방언과 바꾸는 옵션), 미해결 `java-call` 엣지가 50건을 넘는데 `--classpath`가 비었음, 그 미해결의 절반 넘게가 `src/test` 아래에서 나왔고 테스트를 빼지 않았음, 같은 테이블 정의가 여러 파일에 있음(SQL 파일의 `DUPLICATE_OBJECT_NAME`), 데이터 적재 INSERT를 묶었음(`DATA_LOAD_GROUPED`), upgrade 스크립트의 ALTER를 적용하지 않았음(`DDL_NOT_APPLIED`), 벤더 스크립트·페이지를 건너뛰었음(`skipped=vendor` 노드 수). 해당하는 것이 없으면 절 자체가 없고 `--quiet`면 요약과 함께 생략한다. 힌트에 적는 `--opt` 키는 설계 3.8의 키여야 한다(F-001-R33과 같은 대조). | 실전 검증 2차 수정 |
| F-001-R45 | `calltree` 명령은 기본으로 소스 없는 메서드의 호출 줄을 접어야 한다(표준 산출물과 같다, F-015-R35). `--show-external`이 주어지면 보인다. 전에 쓰던 `--hide-external`은 받되 효과가 없고 도움말에 없다. `--layouts`는 레이아웃 너머를 펼친다(F-015-R48). | 3차 검증(DAO 메서드마다 `~ EgovAbstractMapper#selectOne(?)` 줄) |
| F-001-R46 | `calltree`의 후보가 여럿일 때, 검색어와 표시 이름(ID의 나머지, qualifiedName, 엔드포인트의 `METHOD resolvedPath` 포함)이 정확히 같은 후보가 종류 우선순위(`HTTP_ENDPOINT` > `JAVA_METHOD` > `SQL_STATEMENT` > `DB_TABLE` > `DB_ROUTINE` > `BATCH_JOB` > `JSP_PAGE` > `JS_FUNCTION` > 그 밖)에서 가장 앞선 종류에 하나뿐이면, 시스템은 그것을 고르고 표준 오류에 다른 후보의 수와 `--kind` 안내 한 줄만 내야 한다. `find`는 전부 보인다. | 3차 검증(엔드포인트 1개 + 같은 글자의 요청 6개) |
| F-001-R47 | 힌트(R44)에 다음을 더한다: 화면 요청(클라이언트 라우트 제외)은 있는데 엔드포인트가 없으면 백엔드와 함께 분석하라는 안내, 엔드포인트는 있는데 요청이 없으면 프런트가 분석 범위에 없다는 안내. `DIALECT_VARIANT_DDL`의 메시지(선택된 DDL 방언, `--dialect`)와 `DIALECT_NOT_FOUND`의 메시지. 매퍼와 DDL에서 고른 방언이 다르면 `경고:` 한 줄. `UNKNOWN_COLUMN`의 건수와 SQL이 DDL에 없는 컬럼을 참조한다는 안내. 심각도가 `WARN`인 방언 진단(`DIALECT_VARIANT_MAPPERS`·`DIALECT_VARIANT_DDL`: 프로젝트 설정 때문에 대상 외 방언으로 분석됨, `DIALECT_NOT_FOUND`)은 힌트 절 맨 위에 `주의:`로 올린다. `DIALECT_VARIANT_DDL`이 있으면 SQL 파일의 `DUPLICATE_OBJECT_NAME` 힌트는 내지 않는다. | 3차 검증, sql·mybatis 계약 변경 |
| F-001-R48 | `--classpath`의 도움말과 README는 폴더를 주면 클래스 폴더로 쓰면서 그 바로 아래의 jar도 쓴다는 것(`<폴더>/*` 표기 포함)을 적어야 한다. | java 계약 변경 |
| F-001-R49 | `calltree`의 검색어가 `[METHOD ]<경로>` 꼴(경로가 `/`로 시작. 앞에 `http(s)://호스트`가 붙어도 된다)이고 `--kind`가 `HTTP_ENDPOINT`를 빼지 않았으면, 시스템은 그것을 URL로 보고 엔드포인트를 찾아야 한다: (1) 쿼리(`?…`)와 fragment(`#…`)를 뗀다. (2) `HTTP_ENDPOINT` 가운데 경로(`attrs.resolvedPath` 우선, 없으면 `attrs.path`)가 맞고 메서드가 같거나 엔드포인트가 `ANY`인 것이 후보다. 검색어에 메서드가 없으면 그 경로의 엔드포인트 전부다. (3) 글자 그대로 같은 경로가 없으면 경로 변수 패턴(`/orders/{id}`)에 구체 경로(`/orders/15`)를 맞춘다. (4) 그래도 없으면 맨 앞 한 세그먼트, 그다음 두 세그먼트(컨텍스트 경로)를 떼고 다시 찾는다. (5) 맞은 엔드포인트가 정확히 하나면 같은 경로의 화면 요청(`HTTP_REQUEST`)이 몇 개든 그것을 고르고, 다른 후보가 있거나 패턴·컨텍스트 경로로 맞췄으면 표준 오류에 한 줄로 알린다. 엔드포인트가 여럿이면 R24·R46대로 목록을 낸다. URL로 찾지 못하면 전과 같이 찾는다. `impact`와 `find`는 바뀌지 않는다. | 5차 검증 M11(`calltree 'GET /x.do'`가 `ANY /x.do`에 맞지 않고, 경로만 주면 엔드포인트 1개 + 요청 n개가 후보로 나오고, 컨텍스트 경로·쿼리가 붙으면 못 찾음) |
| F-001-R50 | `find`의 검색어에 `*`가 있으면, 시스템은 그것을 와일드카드로 보고 이름 전체에 대소문자를 가리지 않고 맞춰야 한다(그 글자 그대로인 노드 ID가 있으면 그 노드). 견주는 이름은 조회 API가 "이름이 같음"을 볼 때 쓰는 것과 같다(노드 ID, ID의 나머지, qualifiedName, 이름, 표시 이름, 메서드의 매개변수 없는 꼴, 구문 이름, 엔드포인트의 `resolvedPath` 표기). `*`가 없는 검색어는 전과 같이 찾되, 이름이 같은 노드에서 멈춰 부분 일치를 보이지 않았으면 검색어가 들어 있는 노드의 수와 `'*검색어*'`로 보는 방법을 표준 오류에 한 줄로 알려야 한다. | 5차 검증 M14(h)("정확히 같은 이름만 찾는다"는 보고) |
| F-001-R51 | 도움말과 README는 다음을 적어야 한다: `crud --by endpoint`는 forward로 넘어간 핸들러의 접근을 포함하고 redirect 대상은 제외한다(별개의 요청), CRUD 칸 끝의 `?`는 추론한 관계를 거쳐서만 닿은 칸이다(F-015), `calltree --show-accessors`는 부수 효과 없는 생성자(본문에서 호출이 나가지 않는 `<init>`)와 MyBatis Generator의 `*Example`·`*Example$Criteria` 빌더 호출도 함께 편다, `calltree --layouts`는 위 방향의 `[shared] <JSP> (포함하는 화면 n개)` 줄(여러 화면에 include되는 공통 JSP)도 펼친다. 동작은 export의 것이고(F-015) cli는 옵션을 `CallTreeOptions.hideAccessors`·`expandLayouts`로 넘긴다. | 5차 검증 M14(f) |
| F-001-R52 | 힌트의 매퍼·DDL 방언 불일치 경고(R47)는 진단 문구가 아니라 그래프에서 판정해야 한다: 매퍼 쪽은 매퍼 구문(`SQL_STATEMENT`)의 `attrs.dialectVariant`에서 가장 많은 값(값이 있는 구문이 없으면 `MYBATIS_MAPPER`의 같은 속성), DDL 쪽은 정의 위치가 있는 `DB_TABLE`마다 그 파일 경로의 방언 토큰(sql·mybatis와 같은 표와 규칙)에서 가장 많은 값. 둘이 다르면 `경고:` 한 줄을 내고, 어느 한쪽을 알 수 없으면(방언 변형이 아니거나 `all`로 합친 매퍼, 경로에 방언이 없는 DDL) 내지 않는다. 방언별 DDL 진단(`DIALECT_VARIANT_DDL`)이 없는 프로젝트(DDL이 한 벌뿐)에서도 판정한다. | sql·mybatis의 방언 진단 문구 변경(문구에 기대면 경고가 조용히 사라진다) |
| F-001-R53 | 방언 힌트(R44·R47)는 sql·mybatis의 방언 정책(고르는 순서: 명시값 → 프로젝트 설정이 가리키는 대상 방언 → Oracle → DB2 → Oracle 계열 → 설정의 대상 외 방언 → 경로순)을 따라야 한다: `DIALECT_NOT_TARGET`(방언별 여러 벌은 아니지만 SQL이 대상 외 방언으로 보임)이 있으면 그 메시지를 한 줄로 낸다. 방언 진단의 메시지에 옵션 안내(`mybatis.dialectVariant`)가 없어 덧붙일 때와 불일치 경고·`UNKNOWN_COLUMN` 안내에서, 대상 외 방언으로 바꾸는 방법은 `--dialect`가 아니라 `--opt mybatis.dialectVariant=<방언>`으로 적는다(`--dialect`는 oracle·db2·auto만 받고, 매퍼와 DDL 선택이 둘 다 `mybatis.dialectVariant`를 읽는다). 설정과 다르게 대상 방언을 골랐다는 INFO 진단은 `주의:`로 올리지 않고 그대로 옮긴다(`주의:`는 WARN일 때뿐이다: 대상 방언 파일이 없어 대상 외 방언을 씀, 요청한 방언의 파일이 없음). `analyze`의 도움말과 `--dialect`에 모르는 값을 줬을 때의 오류, README도 같은 내용이어야 한다. | sql·mybatis 4차 수정(방언 정책 변경, `DIALECT_NOT_TARGET`) |
| F-001-R54 | `impact`는 데이터 적재 구문(`dataLoad=true`)을 기본으로 빼고(뺀 수는 리포트가 알린다, F-015) `--include-data-load`가 주어지면 넣어야 한다. | export 계약 변경(`ImpactAnalysis.analyze(graph, 대상, 깊이, includeDataLoad)`), R41과 같은 옵션 |
| F-001-R55 | `calltree`의 URL 검색(R49)은 화면 요청의 메서드 후보를 따라야 한다: `HTTP_REQUEST`가 `httpMethod=ANY`이고 `attrs.httpMethods`(메서드를 하나로 정하지 못한 요청의 후보. `ANY`는 후보로 치지 않는다)가 있으면 검색어의 메서드가 그 후보에 든 것일 때만 맞고, 후보가 없는 `ANY` 요청은 전처럼 어느 메서드에나 맞는다. 맞는 엔드포인트가 하나도 없으면(프런트만 분석한 그래프, 그 메서드의 엔드포인트가 없는 경로) 검색어의 경로(쿼리·fragment만 뗀 것. 컨텍스트 경로는 떼지 않는다)와 글자 그대로 같은 경로의 화면 요청을 후보로 삼는다. `--kind`가 `HTTP_ENDPOINT`를 빼고 `HTTP_REQUEST`만 남겨도 URL로 찾은 요청이 후보다(남는 후보가 없으면 전처럼 이름으로 찾는다). `selfSubmit=true`인 요청(경로 자리가 `{self}`)은 경로가 없으므로 어느 URL에도 맞지 않고 `{self}`를 경로나 경로 변수로 보지 않는다. 후보 목록(`calltree`, `find`)은 메서드 후보가 있는 요청의 줄 끝에 `[POST\|PUT]`처럼 후보를 적는다(노드 ID는 `ANY` 그대로다). | 6차 검증(web·jsp·link가 `httpMethods`, `selfSubmit` 요청을 만든다: F-012-R39, R40. `calltree 'PUT /api/bbsUseInf'`가 `ANY /api/bbsUseInf` 요청을 찾지 못했다) |
| F-001-R56 | cli가 미해결을 세는 곳은 같은 지점에서 다른 방법으로 풀린 참조를 세지 않아야 한다: `attrs.resolvedBy`가 있는 `UNRESOLVED` 노드(F-012-R42)와 그리로 가는 `UNRESOLVED` 엣지. 힌트의 미해결 Java 호출 수(R44)에서 빼고, 요약(`analyze`, `summary`)의 노드 `UNRESOLVED` 수와 신뢰도별 엣지의 `UNRESOLVED` 수에서 빼며, 뺀 것이 있으면 신뢰도 표 아래에 뺀 노드·엣지 수를 한 줄로 알린다(총수는 그래프의 것 그대로다). `report.md`의 미해결 절은 export의 것이다(F-016). (5차 수정: 요약의 노드·신뢰도별 수는 그래프 그대로 세게 바뀌었다 → R58. 힌트는 그대로다.) | link 계약 추가(`resolvedBy`) |
| F-001-R57 | `calltree`·`crud` 도움말은 콜트리 줄 끝의 `(인자 구문)`(`EXECUTES`의 `viaArgument`), `(접두 일치)`(`prefixMatch`), `(동적 뷰 이름 후보)`(`RENDERS`의 `dynamicSegment`), 요청 이름의 `POST\|PUT`(메서드 후보)과 `(자기 페이지로 제출)`(`selfSubmit`)을 적어야 한다. `[ambiguous n]`은 후보가 둘 이상일 때만 붙는다는 것(n은 2 이상)과 후보 엔드포인트 여럿과 맞은 요청에도 쓰인다는 것을 적는다. 표기 자체는 export의 것이고(F-015) cli는 설명만 한다. | export·link 계약 변경(표시 이름, `ambiguous`는 후보 둘 이상일 때만) |
| F-001-R58 | cli가 그래프의 수를 적는 곳(`analyze`·`summary` 요약의 노드·엣지 종류별 수와 신뢰도별 엣지 수, 검증 리포트 머리의 그래프 수)은 **그래프 그대로** 세야 하고(종류별 합이 총수와 같다), 미해결 참조의 목록·건수·힌트만 같은 지점에서 다른 방법으로 풀린 참조(`resolvedBy`가 있는 `UNRESOLVED` 노드와 그리로 가는 엣지)를 뺀다. 두 수가 달라지는 곳에는 까닭을 한 줄 적는다: 요약은 신뢰도 표 아래에 풀린 참조·엣지 수, `diagnostics.md`는 머리에 한 줄을 적고 풀린 참조의 `UNRESOLVED_REFERENCE` 진단을 지우지 않은 채 메시지 끝에 `(접두 일치로 풀림)`을 덧붙이며(진단 건수 유지), 검증 하네스(`verify/SampleReport`)의 "미해결 노드"·"UNRESOLVED 엣지" 목록과 지점 근처의 미해결 엣지는 풀린 것을 빼고 뺀 수를 적는다. R56의 "요약의 수에서 뺀다"는 이 요구사항으로 바뀌었다(힌트의 미해결 Java 호출 수는 R56 그대로다). | 4차 재검증(공통컴포넌트: stdout 노드 종류별 합 48692 ≠ 총 48693, report.md 430 대 stdout 429, diagnostics.md의 풀린 참조) |
| F-001-R59 | `calltree`의 URL 검색은 화면 요청을 엔드포인트와 같은 순서로 찾아야 한다: 글자 그대로 같은 경로 → 요청의 경로 패턴(`{*}`, `{이름}`)에 구체 경로 맞추기(세그먼트 전체인 `{*}`는 한 세그먼트, 세그먼트의 일부인 `{*}`는 여러 세그먼트. 여럿이면 가장 좁은 것만. 검색어가 패턴 꼴이면 글자 그대로만) → 맨 앞 1~2 세그먼트(컨텍스트 경로)를 떼고 다시. 엔드포인트를 찾았을 때의 "같은 경로의 화면 요청"에도 같은 패턴 맞추기를 쓴다. 요청 하나만 맞았고 패턴·컨텍스트 경로로 맞췄으면 표준 오류에 알린다. URL 꼴 검색어로 아무것도 찾지 못하면 오류 문구에 (a) 메서드가 달라 못 찾은 경우 같은 경로의 다른 메서드 엔드포인트(없으면 화면 요청)와 (b) 패턴 꼴로도 찾을 수 있다는 안내를 덧붙인다. `selfSubmit` 요청은 전처럼 어느 URL에도 맞지 않는다. | 4차 재검증(`calltree 'PUT /api/schedule/1'`이 프런트만 분석한 그래프에서 실패, `--kind HTTP_REQUEST 'PUT /api/schedule/5'` 실패, POST 전용 경로에 GET을 줬을 때 안내 없음) |
| F-001-R60 | "화면 요청은 있는데 엔드포인트가 없다" 힌트(R44)는 `report.md`의 "화면 요청" 절과 같은 것을 같은 이름으로 세야 한다: 서버 요청(외부 URL·정적 페이지 링크·클라이언트 라우트를 뺀 화면 요청. F-015-R74의 조회 API). 서버 요청이 하나도 없으면 힌트를 내지 않는다. | 4차 재검증(react: 힌트 "화면 요청 99개" 대 report.md "서버 요청 97건") |
| F-001-R61 | `impact` 도움말과 README의 영향도 설명은 forward 경유의 구분(F-015-R77)을 적어야 한다: 이름 앞의 `[forward]`는 forward로 넘어간 다른 엔드포인트의 핸들러를 거쳐서만 닿은 항목이고 핸들러 자신의 호출 사슬로 닿는 경로가 있으면 붙지 않는다는 것, 경로의 `[forward]`와 개수 요약의 `직접`·`forward 경유`, JSON의 `viaForward`·`forward`, redirect 대상은 따라가지 않는다는 것(별개의 요청). | 실험(2026-10-11), export 계약 변경(`ImpactReport.Item.forward`) |

## 4. 수용 기준

### F-001-AC1 (대상: F-001-R1)
- Given `.java`, `.sql`, `.pkb`, `.js`, `.tsx`, `.d.ts`, `.jsp`, `.properties`, `.yml`, `.jar` 파일과 루트 요소가 각각 `mapper`, `beans`, `project`인 XML 파일이 있는 폴더
- When 그 폴더를 스캔하면
- Then 각 파일이 `JAVA`, `SQL`, `SQL`, `JS`, `TS`, `OTHER`, `JSP`, `PROPERTIES`, `YAML`, `JAR`, `MYBATIS_MAPPER`, `SPRING_XML`, `XML`로 분류되고 상대 경로의 구분자는 `/`다.

### F-001-AC2 (대상: F-001-R2)
- Given `node_modules/`, `target/`, `.git/` 아래와 `src/gen/` 아래에 파일이 있는 폴더
- When `--exclude`에 `src/gen/**`를 주고 스캔하면
- Then 그 디렉터리들 아래의 파일은 결과에 없고 나머지 파일은 있다. `src/main/java/com/x/batch/out/` 아래의 파일은 결과에 있고, `src/node_modules/` 아래의 파일은 없다.

### F-001-AC3 (대상: F-001-R3)
- Given 프로젝트 폴더 밖에 있는 `schema.sql`
- When 그 파일을 DDL 경로로 주고 스캔하면
- Then 결과에 상대 경로가 `ddl/schema.sql`이고 종류가 `SQL`인 파일이 있다.

### F-001-AC4 (대상: F-001-R4)
- Given 한글이 들어 있는 MS949 인코딩 파일, 같은 내용의 UTF-8 파일, BOM이 붙은 UTF-8 파일
- When 인코딩을 지정하지 않고 각 파일의 본문을 읽으면
- Then 세 파일 모두 같은 문자열이 나오고 BOM 문자는 들어 있지 않다.

### F-001-AC5 (대상: F-001-R4)
- Given UTF-8로도 읽히는 바이트로 된 파일
- When 인코딩을 `ISO-8859-1`로 지정해 읽으면
- Then 판별 없이 지정한 인코딩으로 디코딩한 문자열이 나온다.

### F-001-AC6 (대상: F-001-R5)
- Given `reference`로 먼저 만들어진 노드(`declared=false`)
- When 같은 ID로 위치와 속성을 가진 노드를 `declare`하면
- Then 노드는 하나이고 `declared=true`이며 위치는 `declare`한 쪽 것이고, 양쪽 속성이 합쳐지되 같은 키는 새 값이다. 그 뒤 같은 ID를 `reference`하면 기존 노드가 그대로 돌아온다.

### F-001-AC7 (대상: F-001-R5)
- Given 비어 있는 그래프
- When `(from, to, kind, location)`이 같은 엣지를 두 번, 위치만 다른 엣지를 한 번 더하면
- Then 엣지는 두 개이고 `outgoing(from)`과 `incoming(to)`도 두 개를 돌려준다.

### F-001-AC8 (대상: F-001-R6)
- Given 설계 3.3의 예시 값
- When ID 생성 수단으로 ID를 만들면
- Then `java:method:com.a.B#save(java.lang.String,int[])`, `java:method:com.a.B#<init>()`, `http:endpoint:GET /orders/{id}`, `http:endpoint:ANY /orders`, `batch:step:?/load`, `mybatis:stmt:com.a.OrderMapper.selectOne`이 나온다.

### F-001-AC9 (대상: F-001-R6)
- Given 테이블 이름 `hr.orders`, `"Orders"`, 컬럼 이름 `order_id`
- When 테이블과 컬럼 ID를 만들면
- Then `db:table:ORDERS`, `db:table:Orders`, `db:column:ORDERS.ORDER_ID`가 나온다.

### F-001-AC10 (대상: F-001-R7)
- Given `order`가 200과 50인 분석기 둘, 링커 하나, 출력기 하나
- When 파이프라인을 실행하면
- Then 실행 순서는 분석기(50) → 분석기(200) → 링커 → 출력기이고, 출력기가 실행될 때 `graph.json`이 이미 있다. 종류가 `OTHER`, `JAR`가 아닌 스캔한 파일마다 `FILE` 노드가 있다.

### F-001-AC11 (대상: F-001-R7)
- Given `META-INF/services`에 등록된 분석기
- When 파이프라인이 구현체를 탐색하면
- Then 그 분석기가 실행 목록에 들어 있다.

### F-001-AC12 (대상: F-001-R8)
- Given 예외를 던지는 분석기와 그 뒤 순서의 정상 분석기
- When 파이프라인을 실행하면
- Then 정상 분석기가 만든 노드가 그래프에 있고, 실패한 분석기 이름이 들어간 `ERROR` 진단이 하나 있다.

### F-001-AC13 (대상: F-001-R9)
- Given `to`가 등록되지 않은 ID `java:method:com.a.B#x()`인 `RESOLVED` 엣지
- When 마무리 단계를 실행하면
- Then 그 ID로 `kind=UNRESOLVED`, `expectedPrefix=java:method`인 노드가 생기고 엣지의 신뢰도는 `UNRESOLVED`다. 양쪽 노드가 모두 있는 엣지는 바뀌지 않는다.

### F-001-AC14 (대상: F-001-R10)
- Given 위치와 속성(문자열, 숫자, 불리언, 문자열 목록)이 있는 노드, 엣지, 진단이 든 그래프
- When `graph.json`으로 쓰고 다시 읽으면
- Then 노드, 엣지, 진단, 프로젝트 정보가 원래와 같다.

### F-001-AC15 (대상: F-001-R10)
- Given 같은 내용을 서로 다른 순서로 넣은 두 그래프
- When 각각 `graph.json`으로 쓰면
- Then 두 파일의 내용이 같고, 노드는 ID 순이며 `schemaVersion`은 1이다.

### F-001-AC16 (대상: F-001-R11)
- Given Java 파일, 매퍼 XML, SQL 파일이 있는 작은 프로젝트 폴더
- When `analyze <projectDir> --out <dir>`을 실행하면
- Then 종료 코드는 0이고 `<dir>/graph.json`과 `<dir>/diagnostics.md`가 생기며, 표준 출력에 파일 종류별 개수, 노드·엣지 종류별 개수, 신뢰도별 엣지 수, 진단 수가 나온다.

### F-001-AC17 (대상: F-001-R11)
- Given 존재하지 않는 프로젝트 경로 또는 알 수 없는 옵션
- When `analyze`를 실행하면
- Then 종료 코드는 0이 아니고 표준 오류에 이유가 나온다.

### F-001-AC18 (대상: F-001-R12)
- Given 루트에서 `mvn install`을 마친 저장소
- When `java -jar codeatlas-cli/target/codeatlas.jar analyze <projectDir>`를 실행하면
- Then 다른 classpath 없이 실행되어 `graph.json`이 생긴다.

### F-001-AC19 (대상: F-001-R13)
- Given `sql` 내장 소스 둘과 `js` 내장 소스 하나를 등록한 컨텍스트
- When `embedded("sql")`을 부르면
- Then 등록한 순서대로 `sql` 조각 둘만 나온다. `exactPositions=true`인 조각은 조각 안의 줄·열을 호스트 파일 위치로 바꿀 수 있고, `false`인 조각은 `origin`을 돌려준다.

### F-001-AC20 (대상: F-001-R14)
- Given 참조 스텁 `db:table:V_ORDERS`로 들어오고 나가는 엣지와 정의된 `db:view:V_ORDERS` 노드
- When `replaceNode("db:table:V_ORDERS", "db:view:V_ORDERS")`를 부르면
- Then 엣지는 모두 뷰 노드로 옮겨지고(종류, 신뢰도, 위치, 속성 유지) 스텁 노드는 없다. 이미 있는 엣지와 겹치면 하나만 남고, 옮겨서 생긴 self-loop은 원래 self-edge였던 것만 남는다. 스텁 노드가 없으면 아무것도 바뀌지 않는다.

### F-001-AC21 (대상: F-001-R15)
- Given 작은 프로젝트 폴더
- When `analyze`에 `--opt calltree.depth=5`, `--opt=viewer.title=주문 = 관리`, `--opt flag=`을 주면
- Then 분석기가 받은 `options().extra()`와 출력기가 받은 `options`에 세 키가 그 값으로 들어 있다(값 안의 `=`와 빈 값 유지). `--opt novalue`, `--opt =v`, 값 없는 `--opt`는 종료 코드 2로 끝나고 분석은 실행되지 않는다.

### F-001-AC22 (대상: F-001-R16)
- Given 출력기가 하나 등록된 상태
- When `analyze <projectDir> --out <dir> --no-export`를 실행하면
- Then 종료 코드는 0이고 `<dir>/graph.json`과 `<dir>/diagnostics.md`가 생기며 출력기는 실행되지 않는다. 요약은 그대로 표준 출력에 나온다.

### F-001-AC23 (대상: F-001-R16)
- Given 작은 프로젝트 폴더
- When `analyze <projectDir> --out <dir> --quiet`를 실행하면
- Then 표준 출력과 표준 오류가 비어 있고 결과 파일과 출력기 산출물은 그대로 생긴다. 프로젝트 폴더가 없으면 `--quiet`여도 표준 오류에 이유가 나오고 종료 코드는 1이다.

### F-001-AC24 (대상: F-001-R17)
- Given `analyze`로 만든 출력 폴더
- When `summary <dir>/graph.json` 또는 `summary <dir>`을 실행하면
- Then 종료 코드는 0이고 `analyze`가 찍었던 것과 같은 표(파일, 노드, 엣지, 신뢰도별 엣지, 진단)가 표준 출력에 나온다.

### F-001-AC25 (대상: F-001-R17)
- Given 없는 경로, `schemaVersion`이 다른 JSON 파일, 인자가 없거나 둘인 경우
- When `summary`를 실행하면
- Then 앞의 둘은 종료 코드 1과 표준 오류의 이유로, 뒤의 둘은 종료 코드 2와 사용법으로 끝난다.

### F-001-AC26 (대상: F-001-R18)
- Given 패키징한 `codeatlas.jar`와 테스트 classpath의 tree-sitter 바인딩 jar들
- When 실행 jar의 항목과 매니페스트를 보면
- Then 바인딩 jar마다 든 `lib/*.{dylib,so,dll}`이 같은 크기로 전부 들어 있고 매니페스트에 `Enable-Native-Access: ALL-UNNAMED`가 있다.

### F-001-AC27 (대상: F-001-R18)
- Given `.js`, `.tsx`, `.ts` 파일이 있는 작은 프로젝트
- When `java -jar codeatlas.jar analyze <projectDir>`(또는 `bin/codeatlas`)를 실행하면
- Then 각 파일의 함수가 `JS_FUNCTION` 노드로 나오고, 출력에 restricted method 경고(`enable-native-access`)가 없으며 `ANALYZER_FAILED` 진단이 없다.

### F-001-AC28 (대상: F-001-R18)
- Given 작은 프로젝트
- When 실행 jar로 `analyze`를 실행하면
- Then 출력 폴더에 `viewer.html`이 생기고, 그 안에 외부 URL(스킴이 있거나 `//`로 시작)을 가리키는 `script`, `link` 태그가 없다.

### F-001-AC29 (대상: F-001-R19, R20)
- Given `graph.json`이 든 출력 폴더
- When `report <graph.json>`, `report <폴더>`, `report --graph <폴더>`, 그리고 그 폴더의 상위 폴더에서(폴더 이름이 `codeatlas-out`일 때) 인자 없이 `report`를 실행하면
- Then 모두 종료 코드 0이고 같은 내용이 표준 출력에 나온다. 그래프 경로를 둘 주면 종료 코드 2다. 없는 경로나 `schemaVersion`이 다른 파일이면 종료 코드 1이고 표준 오류에 경로가 나오며 표준 출력은 비어 있다.

### F-001-AC30 (대상: F-001-R19, R20)
- Given 저장된 그래프
- When `erd <graph> --out <파일>`을 실행하면
- Then 결과가 그 파일(UTF-8)에 쓰이고 표준 출력은 비어 있다. 없는 상위 폴더는 만들어진다. 대상이 없어 실패한 명령은 파일을 만들지 않는다. 알 수 없는 옵션, 값이 빠진 옵션, 값을 붙인 플래그, 빠지거나 둘 이상인 검색어는 종료 코드 2이고 표준 오류에 그 명령의 사용법 항목이 나온다(다른 명령의 사용법은 나오지 않는다).

### F-001-AC31 (대상: F-001-R21)
- When 등록된 명령마다 `help <명령>`, `<명령> --help`, `<명령> -h`를 실행하면
- Then 셋 다 종료 코드 0이고 `사용법:`으로 시작하는 같은 내용(그 명령의 사용법 항목과 옵션 설명)이 표준 출력에 나온다. `help <없는 명령>`은 종료 코드 2다. 인자 없는 `help`와 `--help`는 모든 명령의 사용법 항목과 `help <명령>` 안내를 찍는다.

### F-001-AC32 (대상: F-001-R22)
- Given 테이블 3개, 뷰 1개, DDL 외래 키 1개, 추론 관계 1개가 있는 그래프
- When `erd`를 옵션 없이, `--format plantuml|dbml`로, 그리고 `--table`, `--depth`, `--pattern`, `--no-inferred`, `--min-evidence`, `--views`, `--columns`, `--no-comments`를 주어 실행하면
- Then 출력이 같은 옵션으로 `Erd.render`를 부른 결과와 같고, 옵션을 준 출력은 기본 출력과 다르다. 형식·컬럼 수준·깊이 값이 잘못되면 종료 코드 2다.

### F-001-AC33 (대상: F-001-R22, R20)
- When `erd --spec`을 실행하면
- Then `TableSpecRenderer.render(graph, ErdOptions.defaults())`의 결과(전체 테이블, 뷰 제외)가 나온다. `--spec`을 표시·형식 옵션(`--columns`, `--no-comments`, `--format`)과 같이 주면 종료 코드 2다. (처음에는 "다른 `erd` 옵션 전부와 같이 주면 2"였다. 범위·관계 옵션은 F-001-AC48로 바뀌었다.)
- 테이블이 없는 그래프, 찾지 못한 `--table`만 준 경우, `--views` 없이 뷰 이름만 준 경우는 종료 코드 1이고 표준 오류에 이유(찾지 못한 이름, `--views` 안내)가 나온다. `--table` 중 일부만 찾지 못하면 그 이름을 표준 오류에 알리고 나머지를 그린다(종료 코드 0).

### F-001-AC34 (대상: F-001-R23)
- Given 두 패키지에 타입 4개와 의존·연관 관계가 있는 그래프
- When `classdiagram`을 옵션 없이, `--format plantuml`로, 그리고 `--package`, `--no-subpackages`, `--type`, `--depth`, `--members`, `--hide-accessors`, `--relations`, `--external`, `--external-supertypes`, `--no-nested`, `--anonymous`, `--max-types`, `--group-by-package`를 주어 실행하면
- Then 출력이 같은 옵션으로 `ClassDiagrams.render`를 부른 결과와 같다. 타입 수 상한을 넘겨 멤버를 생략하면 그 경고가 표준 오류에 나온다.

### F-001-AC35 (대상: F-001-R23, R20)
- When 타입이 없는 그래프에서, 또는 타입이 없는 `--package`나 찾지 못한 `--type`만 주어 `classdiagram`을 실행하면
- Then 종료 코드 1이고 표준 오류에 이유가 나온다. `--type` 중 일부만 찾지 못하면 그 이름을 알리고 나머지를 그린다. `--relations`, `--members`, `--format`, `--max-types` 값이 잘못되면 종료 코드 2다.

### F-001-AC36 (대상: F-001-R24)
- Given 엔드포인트 → 컨트롤러 → 서비스 → 매퍼 메서드 → 구문 → 테이블로 이어진 그래프
- When `calltree 'GET /orders/{id}'`, `calltree <메서드 노드 ID>`, `calltree orders --callers`, `--format json|mermaid`, 그리고 `--depth`, `--max-nodes`, `--no-impl`, `--no-inject`, `--no-collapse`, `--hide-external`, `--no-views`, `--no-location`을 주어 실행하면
- Then 출력이 같은 옵션으로 만든 `CallTrees.toText`/`toJson`/`toMermaid`의 결과와 같다.

### F-001-AC37 (대상: F-001-R24, R20)
- Given 이름이 `find`인 메서드 둘, 이름이 `selectOrder`인 메서드와 매퍼 구문
- When `calltree find`를 실행하면
- Then 종료 코드 1이고 표준 출력은 비어 있으며 표준 오류에 후보 수, 줄마다 `번호 종류 노드 ID 위치`, `--pick`과 `--kind` 안내가 나온다. `--pick 2`는 두 번째 후보의 트리를 내고, 후보 수보다 큰 번호는 종료 코드 1이다. `--kind sql_statement`는 구문만 남겨 트리를 낸다. 모르는 `--kind` 값은 종료 코드 2, 맞는 노드가 없으면 종료 코드 1과 `find` 안내다.

### F-001-AC38 (대상: F-001-R25, R20)
- When `impact ORDERS`, `impact orders.status --format json`, `impact db:table:CUSTOMER --depth 1`을 실행하면
- Then 출력이 `ImpactAnalysis.analyze(...)`의 `toMarkdown()`/`toJson()`과 같다.
- 찾지 못한 이름이면 종료 코드 1이고 표준 오류에 이름이 비슷한 DB 노드 목록이 나온다. 테이블·뷰·컬럼이 아닌 노드 ID면 종료 코드 1과 `calltree` 안내가 나온다. DB 노드가 없는 그래프에서도 예외 없이 종료 코드 1이다.

### F-001-AC39 (대상: F-001-R26, R20)
- When `crud`를 옵션 없이, 그리고 `--by`, `--layout`, `--format`, `--bom`, `--depth`를 주어 실행하면
- Then 출력이 `CrudMatrix.of(graph, 주체, 깊이)`의 `toCsv(모양, bom)`/`toMarkdown(모양)`과 같다.
- 테이블 접근이 있는 주체가 없으면(루틴이 없는 그래프의 `--by routine`, DB 노드가 없는 그래프, 깊이가 모자란 `--by endpoint`) 종료 코드 1이다. 잘못된 값과 `--format md --bom`은 종료 코드 2다.

### F-001-AC40 (대상: F-001-R27, R20)
- When `find find`를 실행하면
- Then 한 줄에 후보 하나씩 `번호 종류 노드 ID 위치`가 표준 출력에 나온다. `--kind`(쉼표·반복, 대소문자 무시)로 종류를 좁히고, 결과가 `--limit`보다 많으면 그만큼만 내고 전체 개수를 표준 오류에 알린다. 맞는 노드가 없으면 종료 코드 1, `--limit 0`이나 모르는 `--kind`는 종료 코드 2다. 80자를 넘는 ID 하나가 다른 줄의 위치 열을 80자 넘게 밀지 않는다.

### F-001-AC41 (대상: F-001-R28)
- When `report`와 `report --max-items 0`을 실행하면
- Then 출력이 `SummaryReport.render(graph, n)`과 같다. 노드가 없는 그래프에서도 종료 코드 0이다. 정수가 아니거나 음수인 `--max-items`는 종료 코드 2다.

### F-001-AC42 (대상: F-001-R29)
- Given `graph.json`이 든 폴더와 등록된 출력기
- When `export <폴더> --out <새 폴더> --opt export.report.maxItems=1 --opt export.crud=false`를 실행하면
- Then 분석기는 실행되지 않고, 출력기가 그 옵션을 받으며, 새 폴더에 `graph.json`(원본과 같은 내용), `diagnostics.md`, `report.md`, `erd/`, `class-diagram/`, `calltree/`, `viewer.html`이 생기고 `crud/`는 없다.
- `--out`에 그래프가 있는 폴더를 그대로 주면 `graph.json`은 바이트까지 그대로이고 산출물만 다시 쓰인다. `--quiet`면 표준 출력과 표준 오류가 비어 있다. `--out`이 없거나 `--opt` 값이 `<key>=<value>`가 아니면 종료 코드 2이고 아무것도 쓰지 않는다.

### F-001-AC43 (대상: F-001-R30)
- Given 방언, 인코딩, classpath 둘, DDL 경로 둘(상대·절대), 제외 패턴 둘, `extra` 셋을 가진 옵션
- When 그 옵션을 가진 그래프를 `graph.json`으로 쓰고 다시 읽으면
- Then `project().options()`가 원래와 같고, 파일의 `project.options`에 경로가 입력 그대로, `extra`가 키 순으로 들어 있으며 `schemaVersion`은 1이다. `options` 항목이 없는 파일과 옵션 없이 만든 `ProjectInfo`는 빈 옵션이고, 모르는 인코딩 이름은 지정이 없는 것으로 읽는다.
- `Pipeline.analyze`/`run`이 만든 그래프와 `analyze` 명령이 쓴 `graph.json`에 그때의 옵션이 들어 있다.

### F-001-AC44 (대상: F-001-R31)
- Given 분석기, 링커, 출력기 셋(정상, 예외를 던지는 것, 진단을 남기는 것)을 가진 파이프라인과 이미 있는 그래프
- When `Pipeline.export(graph, outputDir, options)`를 부르면
- Then 분석기와 링커는 실행되지 않고 출력기만 이름순으로 그 옵션을 받아 실행된다. 없는 출력 폴더는 만들어지고 `graph.json`은 쓰이지 않는다. 결과의 `exporters()`는 실행한 이름, `diagnostics()`는 이 실행에서 새로 남은 진단, `failures()`는 `EXPORTER_FAILED` 진단이고 `failed()`는 true다. 실패가 없으면 `failed()`는 false다.
- `ServiceLoader`가 만들지 못한 출력기가 있으면 결과와 그래프에 `SERVICE_LOAD_FAILED`가 남고 `failed()`는 true다.

### F-001-AC45 (대상: F-001-R31)
- When `withoutExporters()`로 얻은 파이프라인의 `run`을 실행하면
- Then 분석기와 링커는 그대로 실행되고 `graph.json`이 쓰이며 출력기는 실행되지 않는다. 원래 파이프라인의 출력기는 그대로다. 만들지 못한 출력기의 `SERVICE_LOAD_FAILED`는 남지 않고, 분석기 탐색은 평소와 같다.

### F-001-AC46 (대상: F-001-R32)
- Given `--opt` 셋이 저장된 `graph.json`
- When `export <폴더> --out <새 폴더>`를 `--opt` 없이, 그리고 같은 키 하나와 새 키 하나를 `--opt`로 주어 실행하면
- Then 출력기는 각각 저장된 값 그대로, 저장된 값에 `--opt`를 덮어쓴 값을 받는다. 새 폴더의 `graph.json`에는 원래 분석 때의 옵션이 그대로 남는다. 옵션이 저장되지 않은 그래프에서는 `--opt`만 받는다. `help export`에 이어받는다는 설명이 있다.

### F-001-AC47 (대상: F-001-R33)
- When 설계 3.8의 표, 모든 명령의 `help`, `README.md`를 읽으면
- Then 도움말과 README의 `--opt <key>=` 예시, README의 옵션 표, 분석 요약의 힌트(F-001-R44)에 나오는 키가 모두 설계 3.8의 키이고, 설계 3.8의 `export.` 키 집합이 `StandardExporter.OPTION_KEYS`와 같다. `help analyze`에 `--opt calltree.depth`(없는 키)가 없다.

### F-001-AC48 (대상: F-001-R34, R20)
- When `erd --spec`을 `--table ORDERS --depth 1`, `--pattern`, `--no-inferred --min-evidence 2`, `--views`와 함께 실행하면
- Then 출력이 같은 `ErdOptions`로 부른 `TableSpecRenderer.render(graph, options)`와 같다. 뷰는 `--views`를 줘야 든다.
- 찾지 못한 `--table`만 주면 종료 코드 1과 그 이름, 뷰 이름을 `--views` 없이 주면 종료 코드 1과 `--views` 안내, 일부만 찾지 못하면 경고와 함께 나머지를 낸다(종료 코드 0). `--depth` 값이 잘못되면 종료 코드 2다.

### F-001-AC49 (대상: F-001-R35)
- Given 시작 타입 이름 여럿(있는 것, 없는 것, external 타입)
- When `ClassDiagrams.model`을 만들면
- Then 모델의 `unresolvedNames()`에 찾지 못한 이름이 준 순서대로 중복 없이 들어 있다(external 타입은 `includeExternal`을 줘야 후보다). `classdiagram` 명령은 그 목록을, `erd` 명령은 `ErdModel.unresolvedNames()`·`excludedViewNames()`(F-013-AC17)를, `impact` 명령은 `ImpactReport.suggestions()`(F-015-AC21)를 쓴다. 명령의 동작은 F-001-AC33, AC35, AC38 그대로다.
- 이 수용 기준의 조회 API 부분은 F-014(클래스 다이어그램)의 것이다. F-014 spec은 이번 작업의 쓰기 범위 밖이라 여기에 적었다. (이후 F-014-R14, F-014-AC15로도 적었다. 테스트 이름은 F-001-AC49 그대로다.)

### F-001-AC50 (대상: F-001-R36)
- Given 비어 있는 그래프, 또는 같은 ID의 노드가 이미 있는 그래프(`declare`된 것, 먼저 `reference`된 것)
- When `reference(id, kind, name)`을 부르면
- Then 새로 만든 노드의 `qualifiedName`은 `Ids.remainder(id)`이고 `name`은 준 값이다. 예: `java:type:jakarta.annotation.Resource` → `jakarta.annotation.Resource`, `java:method:javax.servlet.http.HttpServletRequest#getContextPath(?)` → `javax.servlet.http.HttpServletRequest#getContextPath(?)`, `java:field:com.a.B#count` → `com.a.B#count`, `db:column:TB_BROKEN.BROKEN_ID` → `TB_BROKEN.BROKEN_ID`, `db:table:`·`db:view:`·`db:routine:`·`db:sequence:` → 이름, `mybatis:stmt:` → `namespace.id`, `spring:bean:` → 빈 이름, `js:module:`·`jsp:page:` → 경로, 그 밖의 접두사는 나머지 전체. 이미 있는 노드는 `name`과 `qualifiedName`이 그대로 돌아온다. 그 뒤에 같은 ID를 `declare`하면 `declare`한 노드의 `name`과 `qualifiedName`이 된다(`qualifiedName`을 주지 않은 `declare`는 `name`과 같다). 마무리 단계가 만드는 `UNRESOLVED` 노드는 전처럼 `name`이 ID의 나머지, `qualifiedName`이 ID 그대로다.

### F-001-AC51 (대상: F-001-R36, R10)
- Given `reference`로 만든 참조 노드(타입, 메서드, 컬럼, JS 모듈)와 마무리 단계가 만든 `UNRESOLVED` 노드가 있는 그래프
- When `graph.json`으로 쓰고 다시 읽으면
- Then 노드마다 `name`과 `qualifiedName`이 쓰기 전과 같다(참조 노드는 호출자가 준 `name`과 ID에서 유도한 `qualifiedName`).

### F-001-AC52 (대상: F-001-R37)
- Given 한 컬럼으로 `WRITES`, `CONTAINS`, `READS`, `WRITES` 순으로 들어오는 엣지 / 들어오는 `WRITES`가 20만 개이고 `CONTAINS`가 하나인 컬럼
- Then 종류를 하나 또는 여럿(같은 종류를 두 번 줘도) 골라 조회한 결과가 넣은 순서 그대로이고 바꿀 수 없는 목록이다. 마무리 단계가 신뢰도를 바꾼 엣지가 조회에 그대로 보이고, `replaceNode`로 옮긴 엣지는 전처럼 뒤에 붙는다. 20만 개짜리 컬럼에서 `incoming(id, CONTAINS)`를 20만 번 불러도 20초 안에 끝난다(전에는 호출마다 20만 개를 복사했다).
- 테스트: `GraphModelTest.kindFilteredLookupsKeepOrderAndScale`.

### F-001-AC53 (대상: F-001-R38)
- Given 여러 접두사의 없는 노드를 가리키는 엣지
- When `resolveDanglingEdges`
- Then 만들어진 `UNRESOLVED` 노드마다 `expectedPrefix`와 R38의 표대로 유도한 `category`가 있다(`Ids.danglingCategory`). 분석기가 `addUnresolvedEdge`로 직접 남긴 노드의 `category`는 그대로이고 `expectedPrefix`가 없다.
- 테스트: `GraphModelTest.danglingNodesGetCategory`. 소비자 쪽(`category` 없는 전의 그래프도 같은 분류로 묶는다)은 export의 `SummaryReportTest.categorizesDanglingNodesOfOlderGraphs`.

### F-001-AC54 (대상: F-001-R39)
- Given `ctx.progress("db/data/insert-all.sql")`을 부른 뒤 `OutOfMemoryError`를 던지는 분석기 / 진행을 알리지 않고 던지는 링커
- When `Pipeline.analyze`
- Then `AnalysisOutOfMemoryError`가 나오고 `stage()`(`분석기`/`링커`), `component()`(구현체 이름), `activity()`(알린 값 또는 null)가 맞으며, 한 줄 메시지에 `메모리 부족`, 구현체 이름, 처리 중이던 파일, `--exclude`, `-Xmx`가 있고 원인이 원래 오류다. 뒤의 분석기는 실행되지 않는다. 분석기가 바뀌면 앞 분석기가 알린 값은 지워진다. `progress`를 구현하지 않은 `AnalysisContext` 구현체도 컴파일되고 `currentActivity()`가 null이다.
- 테스트: `PipelineTest.explainsOutOfMemory`.

### F-001-AC55 (대상: F-001-R40)
- Given 핸들러가 접근자(`OrderVO#getName`)를 부르고 JSP를 그리며 그 JSP에 다른 엔드포인트로 가는 요청이 있는 그래프
- When `calltree <엔드포인트>` / `--views` / `--show-accessors` / `--no-views`
- Then 기본 출력은 `CallTreeOptions`의 `hideAccessors(true)`, `expandRenderedViews(false)`로 만든 트리와 같아 JSP 이름까지만 나오고 접근자 줄이 없다. `--views`면 JSP 아래의 요청과 그 핸들러가, `--show-accessors`면 접근자 줄이 나온다. `--no-views`는 종료 코드 0에 기본과 같은 출력이고 `help calltree`에는 없다.
- 테스트: `QueryCommandsTest.callTreeDefaultsFoldViewsAndAccessors`.

### F-001-AC56 (대상: F-001-R41)
- Given `dataLoad=true`인 구문이 테이블 하나에만 쓰는 그래프
- When `calltree <그 테이블> --callers`, `crud`를 `--include-data-load` 없이 / 주고 실행하면
- Then 없이 실행하면 그 구문과 테이블이 나오지 않고, 주면 조회 API에 `includeDataLoad=true`를 넘긴 결과와 같다. `help crud`는 `--depth`를 기준별로 설명한다.
- 테스트: `QueryCommandsTest.includeDataLoad`.

### F-001-AC57 (대상: F-001-R42)
- Given `ctx.progress("db/big-data.sql")` 뒤에 `OutOfMemoryError`를 던지는 테스트용 분석기(힙을 실제로 채우지 않는다)
- When `analyze`(`--quiet`가 있든 없든)
- Then 종료 코드가 3이고 표준 오류에 `오류: 메모리 부족`, 단계(`분석기`), 분석기 이름, 처리 중이던 파일, `--exclude`, `-Xmx`, `JAVA_OPTS`가 있으며 스택과 예외 클래스 이름은 없다. 요약은 찍지 않는다.
- 테스트: `MainTest.outOfMemoryDuringAnalysis`.

### F-001-AC58 (대상: F-001-R42)
- Given 파이프라인 밖에서 `OutOfMemoryError`를 던지는 명령
- When 실행하면
- Then 종료 코드가 3이고 표준 오류에 `오류: 메모리 부족`, 원래 메시지, `-Xmx`, `JAVA_OPTS`가 있으며 스택은 없다.
- 테스트: `MainTest.outOfMemoryOutsidePipeline`.

### F-001-AC59 (대상: F-001-R43)
- Given 루트의 `src/test/…`, 하위 모듈의 `module-b/src/test/…`, 그리고 패키지 이름에 `test`가 든 `module-b/src/main/java/com/b/test/Keep.java`
- When `analyze`를 `--exclude-tests` 없이 / 주고 실행하면
- Then 없이 실행하면 테스트 파일의 `FILE` 노드가 있고, 주면 `src/test/` 아래 파일이 하나도 없으며 `src/main` 아래 파일은 그대로다. 분석기가 받은 옵션과 `graph.json`의 `project.options.excludes`에 `src/test`가 들어 있다. `--exclude-tests=yes`는 사용법 오류(2)다.
- 테스트: `MainTest.excludeTests`.

### F-001-AC60 (대상: F-001-R44)
- Given 보통의 작은 프로젝트 / 분석기가 `DIALECT_VARIANT_MAPPERS` 진단과 미해결 `java-call` 60건(그중 40건이 `src/test` 아래)을 남기는 프로젝트
- When `analyze`, `summary`, `analyze --classpath <jar> --exclude-tests`, `analyze --quiet`
- Then 앞의 프로젝트에는 힌트 절이 없다. 뒤의 프로젝트에는 진단 절 뒤에 `힌트`가 나오고 쓴 방언, `--opt mybatis.dialectVariant=`, `미해결 Java 호출 60건`과 `--classpath`, `40건`과 `--exclude-tests`가 있다. `summary`도 같은 표와 힌트를 낸다. `--classpath`를 주고 테스트를 뺀 실행에는 그 두 안내가 없고, `--quiet`면 표준 출력이 비어 있다.
- 테스트: `MainTest.hintsAfterSummary`.

### F-001-AC61 (대상: F-001-R44)
- Given 모든 힌트 조건을 갖춘 그래프(방언별 매퍼 진단, 미해결 `java-call` 61건 중 31건이 테스트, SQL 파일과 Spring XML의 `DUPLICATE_OBJECT_NAME` 한 건씩, `DATA_LOAD_GROUPED`·`DDL_NOT_APPLIED` 한 건씩, `skipped=vendor` 노드 둘과 `skipped=minified` 노드 하나) / 조건에 못 미치는 그래프들
- When 힌트를 만들면
- Then 앞의 그래프에서는 일곱 줄이 정해진 순서로 나오고, 빈 이름 중복(Spring XML)과 압축 스크립트는 세지 않는다. 미해결이 50건 이하이거나, `--classpath`를 줬고 테스트 비중이 절반 이하이거나, 테스트를 이미 뺐으면 그 힌트가 없다. 프로젝트 정보가 없는 그래프에서도 예외 없이 빈 목록이다.
- 테스트: `HintsTest.everyHint`, `HintsTest.noHintWhenNotApplicable`. 힌트의 `--opt` 키 대조는 `QueryCommandsTest.optionKeyExamplesAreRealKeys`(F-001-AC47)가 한다.

### F-001-AC62 (대상: F-001-R42)
- When `JAVA_OPTS="-Xmx256m -XX:+PrintCommandLineFlags"`로 `bin/codeatlas --version`을 실행하면
- Then JVM이 받은 플래그 출력에 `-XX:MaxHeapSize=268435456`이 있다. `JAVA_OPTS`가 비면 없다.
- 테스트: `CodeatlasJarIT.scriptPassesJavaOpts`.

### F-001-AC63 (대상: F-001-R12, R29)
- When 실행 jar로 `samples/sample-shop`을 분석하고, 그 출력 폴더를 `export --opt export.calltree.splitOver=1`로 다시 내보내면
- Then 분석 출력에 `graph.json`, `report.md`, `viewer.html`, `erd/`, `class-diagram/`, `crud/`, `calltree/endpoints.md`, `calltree/batch-jobs.md`가 있고 `endpoints.md`에 `깊이 20, 트리당 노드 500개까지`, 표식 범례(`[reflect]`, `[before]`), 화면 이동을 펼치지 않는다는 안내가 있으며 `calltree/endpoints/`는 없다. 다시 내보낸 폴더에서는 `endpoints.md`가 색인이고 `calltree/endpoints/`에 컨트롤러별 파일이 둘 이상 있다.
- 테스트: `CodeatlasJarIT.sampleOutputsFollowCurrentRules`.

### F-001-AC64 (대상: F-001-R45)
- Then `calltree`의 기본 결과에 소스 없는 메서드의 줄이 없고 `--show-external`이면 `CallTreeOptions.hideAccessors(true)`만 준 결과와 같다. `--hide-external`은 기본과 같은 결과이고 `help calltree`와 `--help`에 없다.

### F-001-AC65 (대상: F-001-R46)
- Given `POST /orders` 엔드포인트 하나와 같은 글자의 화면 요청 둘
- Then `calltree 'POST /orders'`는 엔드포인트의 트리를 내고 표준 오류는 `HTTP_ENDPOINT`, `다른 후보 2개`, `--kind`가 든 한 줄이다. `find`는 셋을 다 보인다. `--kind HTTP_REQUEST`로 좁히면 전처럼 후보 목록(종료 코드 1)이고 `--pick`으로 고른다. 표시 이름이 정확히 같지 않은 검색어(`find`, `selectOrder`)는 전처럼 목록을 낸다.
- 테스트(AC64, AC65): `QueryCommandsTest.callTreeFoldsExternalByDefault`, `callTreePrefersExactMatchOfLeadingKind`.

### F-001-AC66 (대상: F-001-R47)
- Then 요청 2개와 클라이언트 라우트 1개만 있는 그래프는 `화면 요청 2개 … 엔드포인트가 없다 … 백엔드` 힌트 하나, 엔드포인트만 있는 그래프는 `엔드포인트 1개 … 화면 요청이 없다 … 프런트` 힌트 하나를 낸다. 둘 다 있거나 클라이언트 라우트뿐이면 없다.

### F-001-AC67 (대상: F-001-R47)
- Then `DIALECT_NOT_FOUND`는 맨 위에 `주의:`로, `DIALECT_VARIANT_MAPPERS`·`DIALECT_VARIANT_DDL`은 메시지 그대로(없으면 옵션 안내를 덧붙여) 나오고, 매퍼는 mysql·DDL은 oracle이면 `경고: 매퍼는 mysql, DDL은 oracle …` 한 줄이 더 붙는다. 같은 방언이거나 매퍼 방언을 알 수 없으면(`all`로 합침) 경고가 없다(어느 방언을 골랐는지는 그래프에서 읽는다, F-001-AC72). 두 방언 진단이 `WARN`이면 둘 다 `주의:`로 시작하고 SQL 파일의 `DUPLICATE_OBJECT_NAME` 힌트는 없다.

### F-001-AC68 (대상: F-001-R47)
- Then `UNKNOWN_COLUMN` 진단 3건이면 `UNKNOWN_COLUMN 3건`과 `--ddl`이 든 힌트 하나가 나온다.
- 테스트(AC66~AC68): `HintsTest`.

### F-001-AC69 (대상: F-001-R49)
- Given `ANY /cmm/fms/FileDown.do` 엔드포인트와 그 경로의 화면 요청 둘(`GET`), `resolvedPath`가 `/a/sys/user/list`인 `ANY /${adminPath}/sys/user/list`, `GET`·`DELETE /orders/{id}`, `GET /orders/new`, `GET /orders/{id}/items/{itemId:[0-9]+}`
- When `calltree`에 `'GET /cmm/fms/FileDown.do'`, `/cmm/fms/FileDown.do`, `'POST /egov/cmm/fms/FileDown.do?atchFileId=…'`, `http://localhost:8080/portal/egov/cmm/fms/FileDown.do?…`, `'GET /a/sys/user/list'`, `'/jeesite/a/sys/user/list?pageNo=2'`, `'GET /orders/15'`, `'DELETE /shop/orders/15?force=true'`, `/orders/new`, `/orders/15/items/3`, `/orders/15`, `'PUT /orders/15'`를 주면
- Then 앞의 넷은 `ANY /cmm/fms/FileDown.do`의 트리를 내고(요청이 둘 있으면 표준 오류 한 줄에 `HTTP_ENDPOINT`, 그 ID, `다른 후보 2개`, `--kind`. 컨텍스트 경로를 뗐으면 그 경로), 다음 둘은 `resolvedPath`로 찾은 엔드포인트, `GET /orders/15`는 `GET /orders/{id}`(표준 오류에 `경로 변수`), `DELETE /shop/orders/15`는 `DELETE /orders/{id}`, `/orders/new`는 글자 그대로 같은 엔드포인트(표준 오류 없음), `/orders/15/items/3`은 정규식이 붙은 경로 변수의 엔드포인트다. 메서드 없는 `/orders/15`는 엔드포인트 둘이 맞아 목록(종료 코드 1)이고 `--pick`으로 고른다. `PUT /orders/15`와 세 세그먼트를 떼야 맞는 경로는 종료 코드 1이다. `--kind HTTP_ENDPOINT`를 줘도 같고, `--kind HTTP_REQUEST`면 요청만 찾는다. `find`와 `impact`는 전과 같다.
- 테스트: `QueryCommandsTest.callTreeFindsEndpointByUrl`.

### F-001-AC70 (대상: F-001-R50, R27)
- When `find`에 `OrderServ`, `orderserv`, `ORDERSERVICE`, `orders`, `'*orders*'`, `'order*' --kind DB_TABLE`, `'*Service#f*'`, `'*.order.*#*Order' --kind JAVA_METHOD`, `'GET /orders/*'`, `'zzz*'`를 주면
- Then 앞의 둘은 이름에 그 글자가 든 노드들을(대소문자 무시), `ORDERSERVICE`는 대소문자만 다른 타입 하나를 낸다. `orders`는 이름이 같은 테이블 하나만 내고 표준 오류 한 줄에 `이름이 같은`과 `'*orders*'`가 있다. `'*orders*'`는 테이블, 뷰, 컬럼, 엔드포인트를 모두 내고 표준 오류가 비어 있다. `'order*'`는 `ORDERS`, `ORDER_HIST`만, `'*Service#f*'`는 `OrderService#find` 하나, `'*.order.*#*Order'`는 매개변수 없는 꼴에 맞은 매퍼 메서드 둘, `'GET /orders/*'`는 엔드포인트 하나다. `'zzz*'`는 종료 코드 1이다. `help find`에 와일드카드 설명이 있다.
- 테스트: `QueryCommandsTest.findPartialCaseInsensitiveAndWildcard`.

### F-001-AC71 (대상: F-001-R51)
- Then `help crud`에 `forward`, `redirect`, `별개의 요청`, `끝의 ?`가, `help calltree`에 `<init>`, `*Example`, `*Example$Criteria`, `[shared]`, `포함하는 화면 n개`가 있고 README에 `` 끝의 `?` ``, `` `*Example` ``, `[shared]`가 있다. `calltree --callers --layouts --show-accessors`의 출력은 `CallTreeOptions`의 `expandLayouts(true)`, `hideAccessors(false)`로 만든 트리와 같다.
- 테스트: `QueryCommandsTest.helpExplainsCrudMarksAndCallTreeFolds`.

### F-001-AC72 (대상: F-001-R52)
- Given 방언 진단의 문구가 `: <방언> 파일을 사용`이 아닌 그래프(매퍼 구문의 `dialectVariant`는 oracle 둘·mysql 하나, 테이블 정의 파일은 mysql 경로 둘·oracle 경로 하나·방언 없는 경로 하나) / 진단 문구는 서로 다른 방언을 말하지만 구문과 테이블 경로가 모두 oracle인 그래프 / `dialectVariant`가 없는 구문뿐인 그래프 / 테이블 경로에 방언이 없는 그래프 / `MYBATIS_MAPPER`에만 값이 있는 그래프
- Then 첫 그래프는 `경고: 매퍼는 oracle, DDL은 mysql …`을 내고, 둘째·셋째·넷째는 경고가 없으며, 다섯째는 매퍼 노드의 값으로 판정한다(같은 수로 갈리면 양쪽에 공통인 방언이 있을 때 경고하지 않는다). 경로의 방언 토큰은 sql·mybatis의 표와 같은 결과다(별칭, 끝의 버전 숫자, 파일 이름 토큰이 폴더보다 먼저, 폴더는 이름 전체).
- 실행 jar: 매퍼가 방언별 두 벌(oracle, mysql)이고 DDL이 `db/mysql/` 한 벌뿐인 프로젝트를 `--opt mybatis.dialectVariant=oracle`로 분석하면 `DIALECT_VARIANT_DDL` 진단이 없어도 `analyze`와 `summary`에 `경고: 매퍼는 oracle, DDL은 mysql`이 나온다. 매퍼와 DDL이 모두 여러 벌이고 프로젝트 설정이 없으면(재현 p3) 양쪽 다 oracle이라 경고가 없다. `Globals.DbType=mysql`이 있으면(재현 c1) 그래프에서 양쪽이 고른 방언이 같을 때 경고가 없고 다를 때만 그 이름으로 나온다.
- 테스트: `HintsTest.dialectMismatchComesFromGraphNotFromMessages`, `HintsTest.dialectOfPath`, `HintsTest.ddlDialectHints`(AC67. 그래프에 구문과 테이블을 넣게 고침), `CodeatlasJarIT.dialectMismatchHintFollowsGraph`.

### F-001-AC73 (대상: F-001-R53)
- Given `DIALECT_NOT_TARGET`(INFO)만 있는 그래프 / 옵션 안내가 없는 WARN `DIALECT_VARIANT_MAPPERS`·`DIALECT_VARIANT_DDL`("대상 방언의 파일이 없어 … 파일을 씀")과 `DIALECT_NOT_FOUND`가 있고 매퍼 구문은 postgres, 테이블 정의 파일은 mysql인 그래프 / "프로젝트 설정(…=mysql)과 다르게 대상 방언 oracle 파일을 사용"이라는 INFO `DIALECT_VARIANT_MAPPERS`만 있는 그래프 / `UNKNOWN_COLUMN`이 있는 그래프
- Then 첫 그래프는 그 메시지 한 줄을 낸다. 둘째는 `주의:` 세 줄이 모두 `--opt mybatis.dialectVariant=<방언>`을 담고(메시지가 마침표 없이 끝나도 덧붙인 안내와 붙지 않는다) 넷째 줄 `경고: 매퍼는 postgres, DDL은 mysql …`이 `--opt mybatis.dialectVariant=<방언>`과 "둘 다"를 담으며, 어느 줄에도 대상 외 방언을 `--dialect`로 주라는 문구가 없다. 셋째는 `주의:` 없이 메시지 그대로 한 줄이다. 넷째의 안내에 `--opt mybatis.dialectVariant=<방언>`이 있다.
- 실행 jar: mysql 표기의 DDL 한 벌과 매퍼 하나뿐인 프로젝트는 진단 `DIALECT_NOT_TARGET`과 함께 힌트에 `mysql`·`대상 방언`이 든 줄이 나온다. `Globals.DbType=mysql`이고 매퍼·DDL이 oracle·mysql 두 벌인 프로젝트(재현 c1)는 그래프에서 양쪽 다 oracle을 고르고 방언 진단이 모두 INFO이며 요약에 `주의:`와 `경고: 매퍼는`이 없다. 같은 프로젝트를 `--opt mybatis.dialectVariant=mysql`로 분석하면 양쪽 다 mysql이고, `--dialect mysql`은 종료 코드 2에 `mybatis.dialectVariant` 안내다.
- 테스트: `HintsTest.dialectPolicyHints`, `CodeatlasJarIT.notTargetDialectHint`, `CodeatlasJarIT.dialectMismatchHintFollowsGraph`(c1 부분을 조건부 단언에서 "양쪽 다 oracle"로 강화), `QueryCommandsTest.impactIncludeDataLoad`의 `help analyze`·`--dialect mysql` 부분. F-001-AC67의 WARN 예시 메시지는 지금 나오는 문구로 바꿨다.

### F-001-AC74 (대상: F-001-R54)
- Given `dataLoad=true`인 구문만 쓰는 테이블 `ORDER_HIST`
- When `impact ORDER_HIST`를 `--include-data-load` 없이 / 주고 실행하면
- Then 없이 실행하면 `ImpactAnalysis.analyze(graph, 대상, 깊이, false)`의 결과와 같아 `데이터 적재 구문 1건 제외`가 있고 그 구문이 없으며, 주면 `includeDataLoad=true`의 결과와 같다(`--format json`, `--depth`와 함께여도). `--include-data-load=yes`는 종료 코드 2다. `help impact`와 전체 사용법에 옵션이 있다.
- 테스트: `QueryCommandsTest.impactIncludeDataLoad`.

### F-001-AC75 (대상: F-001-R55)
- Given `POST`·`GET /api/schedule` 엔드포인트와 같은 경로의 화면 요청 셋(`ANY` + `httpMethods=[POST, PUT]`, 후보 없는 `ANY`, `GET`) / 엔드포인트 없이 그 요청들만 있는 그래프 / `ANY` + `httpMethods=[POST, PUT]` 요청 하나뿐인 그래프
- When `EndpointLookup.find`에 `POST /api/schedule`, `GET /api/schedule?page=2`, `/api/schedule`, `ANY /api/schedule`, `PUT /api/schedule`, `DELETE /api/schedule`, `PUT /ctx/api/schedule`을 주면
- Then `POST`의 요청은 후보 요청과 후보 없는 `ANY` 요청, `GET`의 요청은 후보 없는 `ANY` 요청과 `GET` 요청이다(후보가 `[POST, PUT]`인 요청은 없다). 메서드가 없으면 셋 다, `ANY`는 `ANY` 요청 둘이다. 엔드포인트가 없는 그래프의 `PUT`은 엔드포인트 목록이 비고 요청이 후보 요청과 후보 없는 `ANY` 요청인 결과이고(`DELETE`는 후보 없는 `ANY` 요청 하나), 경로가 다르거나 컨텍스트 경로가 붙으면 null이다. 요청 하나뿐인 그래프의 `GET`은 null이다.
- 명령: 요청 하나뿐인 그래프에서 `calltree 'PUT /api/schedule'`, `'post /api/schedule?id=3'`, `/api/schedule`, `'PUT /api/schedule' --kind HTTP_REQUEST`는 그 요청의 트리를 내고 표준 오류가 비며, `'GET /api/schedule'`은 종료 코드 1(`찾지 못했다`)이다. 엔드포인트가 있는 그래프에서 `'POST /api/schedule'`과 `'GET /api/schedule'`은 각 엔드포인트의 트리와 `다른 후보 2개`, `'POST /api/schedule' --kind HTTP_REQUEST`는 요청 둘의 목록(후보 요청의 줄 끝에 `[POST|PUT]`. `HTTP_ENDPOINT` 없음)이고 `--pick`으로 고른다. `'PUT /api/schedule'`은 요청 둘의 목록, `--kind HTTP_ENDPOINT`를 주면 `찾지 못했다`다. 후보 목록의 줄은 `<번호>  HTTP_REQUEST  <ID>  <위치>  [POST|PUT]`이고 후보가 없는 요청의 줄은 전과 같다.
- 테스트: `EndpointLookupTest.methodMatchesRequestCandidates`, `EndpointLookupTest.findsRequestsWhenNoEndpointMatches`, `QueryCommandsTest.callTreeUrlMatchesRequestMethodCandidates`, `NodeListingTest.showsRequestMethodCandidates`.

### F-001-AC76 (대상: F-001-R55)
- Given `POST /user/form` 엔드포인트와 그리로 이어진(`MATCHES`, `INFERRED`, `selfSubmit`) `POST {self}` 요청 / 그 요청만 있는 그래프
- Then `EndpointLookup.find`의 `POST /user/form`은 엔드포인트 하나와 빈 요청 목록이고, `POST {self}`, `{self}`, `/{self}`, `POST /anything`은 null이다(요청만 있는 그래프에서도 전부 null). `calltree 'POST /user/form'`은 엔드포인트의 트리를 내고 표준 오류가 비며, `--kind HTTP_REQUEST`를 주거나 `/{self}`를 주면 `찾지 못했다`다. 요청의 노드 ID를 주면 그 요청의 트리를 낸다.
- 테스트: `EndpointLookupTest.selfSubmitRequestIsNotUrlCandidate`, `QueryCommandsTest.callTreeUrlIgnoresSelfSubmitRequests`(둘 다 처음부터 통과: `{self}`는 `/`로 시작하지 않아 URL 꼴이 아니고 어느 경로와도 같지 않다).

### F-001-AC77 (대상: F-001-R56)
- Given 미해결 `java-call` 60건 가운데 15건의 대상 노드에 `resolvedBy`가 있는 그래프 / main 40건·test 45건 가운데 test 20건의 대상에 `resolvedBy`가 있는 그래프 / 미해결 엣지 셋(`EXECUTES` 둘, `CALLS` 하나) 가운데 하나의 대상에 `resolvedBy=prefixMatch`가 있고 풀린 `INFERRED` `EXECUTES`가 하나 더 있는 그래프
- Then 첫 그래프는 힌트가 없고(남는 45건은 50건을 넘지 않는다), 둘째는 `미해결 Java 호출 65건`과 `--classpath` 한 줄뿐이다(테스트 25건은 절반을 넘지 않는다). 셋째의 요약은 `노드 (총 6)`에 `UNRESOLVED 2`, `신뢰도별 엣지`에 `UNRESOLVED 2`·`INFERRED 1`이고 그 아래에 `참조 1개`, `엣지 1건`, `resolvedBy`가 든 한 줄이 있다. `resolvedBy`가 없을 때는 `UNRESOLVED 3`이고 그 줄이 없다.
- 테스트: `HintsTest.unresolvedCallsResolvedElsewhereAreNotCounted`. 요약 부분(셋째 그래프)은 R58로 바뀌어 `ReportsTest.summaryExcludesReferencesResolvedElsewhere`를 AC80의 `summaryCountsTheGraphAsIsAndNotesResolvedReferences`로 바꿨다(기대값: `UNRESOLVED 2` → `UNRESOLVED 3`과 새 알림 줄).

### F-001-AC78 (대상: F-001-R57)
- Then `help calltree`에 `(인자 구문)`, `구문 ID를 인자로 받는 메서드`, `(접두 일치)`, `(동적 뷰 이름 후보)`, `POST|PUT`, `(자기 페이지로 제출)`, `[ambiguous n]`, `n은 2 이상`, `후보 엔드포인트`, `메서드 후보`, `같은 경로의 화면 요청`이 있고 `[ambiguous 1]`은 없다. `help crud`에 `끝의 ?`와 함께 `인자 구문`, `접두 일치`, `동적 뷰 이름`이 있다.
- 테스트: `QueryCommandsTest.helpExplainsInferenceDetailsAndRequestNames`, `QueryCommandsTest.callTreeUrlMatchesRequestMethodCandidates`(도움말 부분).

### F-001-AC79 (대상: F-001-R44)
- Given `FILE_SKIPPED` 진단 셋(`벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): static/lib`, `벤더 페이지 29개 건너뜀(경로): static/ckeditor`, 압축 스크립트 1건)만 있는 그래프 / 거기에 `skipped=vendor` 노드 셋과 `skipped=minified` 노드 하나를 더한 그래프
- Then 첫 그래프는 벤더 힌트가 없고, 둘째는 `스크립트·페이지 3개`가 든 한 줄이며 메시지의 수(`7개`, `29개`)는 나오지 않는다.
- 테스트: `HintsTest.vendorHintCountsNodesNotDiagnosticMessages`(처음부터 통과: 힌트는 노드로 세고 진단 메시지를 읽지 않는다).

### F-001-AC80 (대상: F-001-R58)
- Given 미해결 엣지 셋(`EXECUTES` 둘, `CALLS` 하나) 가운데 하나의 대상에 `resolvedBy=prefixMatch`가 있고 풀린 `INFERRED` `EXECUTES`가 하나 더 있는 그래프
- Then 요약은 `노드 (총 6)`에 `UNRESOLVED 3`, `신뢰도별 엣지`에 `UNRESOLVED 3`·`INFERRED 1`이고 노드·엣지·신뢰도 절마다 행의 합이 총수(6, 4, 4)와 같다. 신뢰도 표 아래의 한 줄은 `(UNRESOLVED 가운데 참조 1개와 그리로 가는 엣지 1건은 같은 지점에서 접두 일치로 풀렸다: resolvedBy. report.md의 "미해결 참조"와 힌트는 이를 빼고 센다)`다. `resolvedBy`가 없으면 그 줄이 없다.
- 테스트: `ReportsTest.summaryCountsTheGraphAsIsAndNotesResolvedReferences`.

### F-001-AC81 (대상: F-001-R58)
- Given 같은 그래프
- Then `diagnostics.md`의 진단 수와 코드별 개수는 그대로(`UNRESOLVED_REFERENCE` 3건)이고, 머리에 ``- `UNRESOLVED_REFERENCE` 3건 가운데 1건은 링커가 같은 지점에서 접두 일치로 풀었다(목록의 메시지 끝에 `(접두 일치로 풀림)`). `report.md`의 "미해결 참조"는 엣지를 세고 풀린 것을 빼므로 이 수와 다르다.`` 한 줄이 있으며, 그 참조의 진단 줄만 메시지가 `… LoginUsr.updateLoginIncorrect{*} (접두 일치로 풀림)`으로 끝난다. `resolvedBy`가 없으면 머리 줄도 표시도 없다.
- 테스트: `ReportsTest.diagnosticsMarkdownMarksResolvedReferences`.

### F-001-AC82 (대상: F-001-R60)
- Given 서버 요청 둘, 클라이언트 라우트 하나, 외부 URL 요청 둘이 있고 엔드포인트가 없는 그래프 / 외부 URL 요청만 있는 그래프
- Then 첫 그래프의 힌트는 `서버 요청 2건`(`SummaryReport.requestStats`의 서버 요청 수, `report.md`의 `서버 요청 2건 중`과 같다)이고 `외부 URL`·`report.md`를 언급하며 4는 나오지 않는다. 둘째 그래프는 힌트가 없다.
- 테스트: `HintsTest.frontOnlyHintCountsServerRequestsLikeTheReport`. 문구가 바뀐 기존 테스트: `HintsTest.everyHint`·`frontAndBackHints`(`화면 요청 n개` → `서버 요청 n건`).

### F-001-AC83 (대상: F-001-R59)
- Given 화면 요청만 있는 그래프(`POST|PUT /api/schedule/{*}`, `GET /api/schedule/{*}`, `DELETE /api/board/{bbsId}/{nttId}`와 AC75의 요청 셋) / 거기에 `ANY /api{*}`를 더한 그래프 / `PUT /schedule/{schdulId}` 엔드포인트를 더한 그래프
- Then `PUT /api/schedule/1`은 `{*}` 요청 하나(패턴으로 맞춤), `GET /api/schedule/1?x=1`은 GET 요청, `DELETE /api/schedule/1`은 null(메서드 후보에 없다), `DELETE /api/board/BBS_1/15`는 이름 있는 변수의 요청, `PUT /api/schedule/{*}`는 글자 그대로(패턴 아님), `PUT /ctx/api/schedule/1`은 컨텍스트 경로 `/ctx`를 떼고 같은 요청이다. `ANY /api{*}`가 있어도 `PUT /api/schedule/1`은 더 좁은 `/api/schedule/{*}`만이고 `PUT /api/unknown/1/2`는 `/api{*}`다. `PUT /api/schedule/1/files`는 null이다. 엔드포인트가 있는 그래프의 `PUT /api/schedule/5`는 엔드포인트(컨텍스트 경로 `/api`)와 같은 경로의 요청 `{*}` 하나이고, 패턴 꼴 검색어 `PUT /schedule/{schdulId}`의 요청 목록은 비어 있다. `calltree 'PUT /api/schedule/1'`은 그 요청의 트리를 내고 표준 오류에 `경로 변수 패턴에 맞춤`을 알리며, `--kind HTTP_REQUEST 'PUT /api/schedule/5'`는 그 요청의 트리를 낸다.
- 테스트: `EndpointLookupTest.concretePathMatchesRequestPatterns`·`sameUrlRequestsMatchByPattern`, `QueryCommandsTest.callTreeUrlMatchesRequestPatterns`. 기대값을 바꾼 기존 테스트: `EndpointLookupTest.findsRequestsWhenNoEndpointMatches`(`PUT /ctx/api/schedule`가 null → 컨텍스트 경로 `/ctx`를 떼고 찾음).

### F-001-AC84 (대상: F-001-R59)
- Given `POST /cop/insert.do`·`PUT /cop/{id}` 엔드포인트만 있는 그래프 / `GET /api/schedule` 요청만 있는 그래프
- Then `calltree 'GET /cop/insert.do'`는 1로 끝나고 오류에 `같은 경로에 다른 메서드의 엔드포인트가 있다: POST /cop/insert.do`와 `경로 변수가 든 URL은 패턴 꼴('/orders/{id}', 화면 요청은 '/api/orders/{*}')로도 찾는다`가 있다. `DELETE /ctx/cop/15`는 `PUT /cop/{id}`를 알린다. 같은 경로가 없으면 패턴 안내만, URL 꼴이 아닌 검색어에는 둘 다 없다. 요청만 있는 그래프의 `POST /api/schedule`은 `같은 경로에 다른 메서드의 화면 요청이 있다: GET /api/schedule`이다.
- 테스트: `EndpointLookupTest.otherMethodsOfTheSamePath`, `QueryCommandsTest.callTreeUrlSuggestsOtherMethodsAndPatternForm`.

### F-001-AC85 (대상: F-001-R58)
- Given 미해결 `EXECUTES` 둘 가운데 하나의 대상에 `resolvedBy=prefixMatch`가 있는 그래프의 검증 리포트
- Then 머리의 그래프 수는 `UNRESOLVED 2` 그대로이고, `### 미해결 노드 (1)`·`### UNRESOLVED 엣지 (1)` 아래에 뺀 수가 한 줄씩 있으며 풀린 노드는 목록과 지점 근처의 엣지(`U1`)에 없다. JSON에는 `resolvedElsewhereNodes`·`resolvedElsewhereEdges`가 1이다.
- 테스트: `SampleReportTest.unresolvedListsExcludeReferencesResolvedElsewhere`.

### F-001-AC86 (대상: F-001-R53)
- Given `DIALECT_VARIANT_DDL` 메시지에 sql의 새 문장(`… 만들지 않음. oracle 파일에 적재가 없는 테이블의 묶음 18개는 다른 방언 파일의 것을 씀(mysql 12개, hsql 6개).`)이 붙은 그래프 / "만들지 않음" 없이 그 문장만 있는 그래프 / 메시지 끝에 옵션 안내가 이미 있는 그래프
- Then 힌트는 그 문장을 그대로 옮기고 옵션 안내(`--dialect <oracle|db2>`, `mybatis.dialectVariant`)를 한 번만 붙이며, 이미 있으면 메시지 그대로다.
- 테스트: `HintsTest.ddlVariantHintCarriesBorrowedDataLoadSentence`(처음부터 통과: 힌트는 메시지를 옮기고 `mybatis.dialectVariant`가 있는지만 본다).

### F-001-AC87 (대상: F-001-R61)
- Then `help impact`에 `[forward]`, `forward 경유`, `viaForward`, redirect를 따라가지 않는다는 것(`별개의 요청`)이 있고, README의 "테이블 영향도" 절에 `[forward]`·`forward 경유`·`viaForward`가 있다. forward로만 닿는 엔드포인트가 있는 그래프에서 `impact`의 출력은 `ImpactAnalysis`의 Markdown·JSON 그대로다(`[forward]`와 `"viaForward": true`가 나온다).
- 테스트: `QueryCommandsTest.impactMarksForwardOnlyItems`.

## 5. 가정
- 8차 수정(2026-10-11, 4차 재검증 후속):
  - 미해결 수의 규칙(R58): "그래프의 수는 그래프 그대로, 미해결 참조의 목록·건수·힌트만 풀린 것을 뺀다"로 통일했다. 7차 수정의 가정("요약의 `UNRESOLVED`에서 뺀다", "검증 하네스의 목록은 빼지 않는다")은 이 규칙으로 바뀌었다. 진단과 미해결 노드를 잇는 구조화된 값은 여전히 없어서 `diagnostics.md`는 메시지로 맞춘다: core가 미해결 노드와 함께 남기는 진단의 메시지 `해석하지 못한 참조(<분류>): <원문>`과 노드의 `category`·이름. 분석기가 다른 문구로 남긴 진단(예: java의 `요청 경로가 상수가 아님: …`)은 맞지 않아 표시가 붙지 않는다(`resolvedBy`는 `mybatis-statement`에만 붙으므로 지금은 해당이 없다). `resolvedBy`가 `prefixMatch`가 아닌 값이면 "다른 방법으로 풀림"으로 적는다.
  - URL 검색(R59): 7차 수정의 가정("요청만 찾을 때 컨텍스트 경로를 떼지 않고 경로 변수 패턴도 맞추지 않는다")을 뒤집었다. 요청 쪽 패턴 맞추기는 검색어가 구체 경로일 때만 한다(패턴 꼴 검색어 `PUT /schedule/{schdulId}`가 요청 `/schedule/{*}`와 맞아 "다른 후보" 알림이 새로 생기는 것을 피한다). 요청의 `{*}`는 Spring의 `{*name}`(여러 세그먼트)이 아니라 "알 수 없는 조각"이라서, 세그먼트 전체면 한 세그먼트로 맞추고 세그먼트의 일부(`/api{*}`)일 때만 여러 세그먼트로 본다: 세그먼트 전체인 `{*}`가 실제로는 여러 세그먼트일 수도 있지만(`'/api/' + path`) 그렇게 보면 공통 fetch 래퍼의 `/api{*}` 같은 요청이 모든 URL에 맞는다. 컨텍스트 경로를 떼다 보면 짧은 경로(`/1`)가 `/{*}` 요청에 맞을 수 있다(엔드포인트 쪽과 같은 한계. 알림에 뗀 경로가 나온다).
  - "다른 메서드" 안내(R59)는 메서드를 가리지 않고 같은 규칙으로 다시 찾은 결과 가운데 그 메서드에 맞지 않는 것이다. `--kind`로 뺀 종류는 안내하지 않는다.
  - 힌트(R60): "엔드포인트는 있는데 화면 요청이 없다" 쪽은 전처럼 클라이언트 라우트가 아닌 요청이 하나도 없을 때다(외부 URL 요청만 있어도 프런트 소스는 분석 범위에 있다).
- 7차 수정(2026-10-11, web·jsp·link의 새 그래프 꼴 반영):
  - URL 검색의 메서드 후보(R55): 메서드 후보는 화면 요청에만 있지만(F-012-R39) 판정 함수는 엔드포인트와 요청에 같이 쓴다(엔드포인트에 `httpMethods`가 없으면 전과 같다). 검색어의 `ANY`는 전처럼 `ANY`인 노드에만 맞는다. 요청만 찾을 때 컨텍스트 경로를 떼지 않고 경로 변수 패턴(`/api/schedule/{*}` ↔ `/api/schedule/12`)도 맞추지 않는다: 요청의 경로는 화면이 보낸 그대로이고, 동적 요청은 전에도 URL 검색의 "같은 경로의 화면 요청"에 들지 않았다. `'POST|PUT /경로'`(표시 이름 그대로)는 URL 꼴로 읽지 않고(`|`는 메서드 글자가 아니다) 이름 검색(`NodeFinder`)으로 넘어간다.
  - 엔드포인트 없이 요청만 맞았을 때 후보가 하나면 알림 없이 그 요청에서 시작한다. `--kind HTTP_REQUEST`로 좁힌 URL 검색의 결과가 전과 달라지는 경우: 같은 경로에 이름이 다른 요청(`GET /x`와 `ANY /x`)이 함께 있으면 전에는 이름이 같은 것만 후보였고 이제는 메서드가 맞는 것 전부가 후보다(검색어와 이름이 정확히 같은 것이 하나면 R46대로 그것을 고른다).
  - `{self}`(R55): `calltree 'POST {self}'`나 `'{self}'`는 URL 꼴이 아니라 이름 검색이고, 그 이름(qualifiedName `POST {self}`)의 요청들이 후보로 나온다. URL로 다루는 것이 아니라 "자기 페이지로 제출하는 폼"을 이름으로 찾은 것이어서 그대로 뒀다(조회 API `NodeFinder`는 export의 것이다).
  - 후보 목록의 `[POST|PUT]`(R55)은 cli가 `attrs.httpMethods`에서 직접 읽어 적는다(표시 이름을 쓰지 않는다: 목록은 노드 ID를 적는다). `find`의 목록에도 같이 나온다.
  - 요약의 `UNRESOLVED`(R56): 풀린 것을 빼면 종류별 수의 합이 총수보다 작아진다. 총수는 그래프의 노드·엣지 수 그대로 두고 뺀 수를 한 줄로 알린다. `진단` 절의 수는 바꾸지 않는다(풀린 참조의 `UNRESOLVED_REFERENCE` 진단은 그래프에 남아 있고, 진단과 미해결 노드를 잇는 구조화된 값이 없다). 검증 하네스(`io.codeatlas.cli.verify.SampleReport`)의 미해결 목록은 사람이 대조하는 원자료라 빼지 않았다(sample-shop에는 `resolvedBy` 노드가 없다).
  - `[ambiguous 1]`이나 "후보 1개"를 가정한 코드·테스트는 cli에 없었다(도움말 문구만 고쳤다). `FILE_SKIPPED` 메시지를 읽는 코드도 없었다(R44의 벤더 힌트는 노드로 센다. AC79로 고정).
- 6차 수정(2026-10-11, sql·mybatis 4차 수정 반영): 방언 정책 자체는 sql·mybatis의 것이고 cli는 그 진단을 옮길 뿐이다(R53). 옵션 안내를 덧붙일지는 메시지에 `mybatis.dialectVariant`가 있는지로 정한다(`--dialect`라는 글자만으로는 정하지 않는다: `DIALECT_NOT_FOUND`의 메시지는 요청한 옵션 이름으로 `--dialect`를 적는다). `DIALECT_NOT_TARGET` 힌트는 메시지 그대로다(바꿀 옵션이 없다: 방언별 파일이 한 벌뿐이다). 확인한 사실(재현 프로젝트를 실제로 분석): 대상 방언 파일이 없는 프로젝트(매퍼·DDL이 mysql·postgres 두 벌)에서 매퍼는 postgres(방언 목록 순), DDL은 mysql(경로순)을 골라 불일치 경고가 나온다. 이것은 sql·mybatis의 고르는 순서가 서로 다른 것이고 cli는 경고로 드러낼 뿐이다. (고쳐짐, 2026-10-11: F-007-R28·F-009-R26으로 두 분석기가 그 밖의 방언끼리 같은 순서 — 매퍼와 SQL 스크립트 양쪽의 경로에 다 있는 방언 먼저, 그 안에서 이름의 사전순 — 를 쓴다. 이 재현은 이제 매퍼와 DDL이 둘 다 mysql을 골라 경고가 없다: `MainTest.mapperAndDdlAgreeOnOtherDialect`. 남은 어긋남은 F-007 spec 5절 알려진 한계에 있다.)
- 5차 수정(2026-10-11):
  - URL로 엔드포인트 찾기(R49)는 `calltree`의 시작 노드를 고르는 규칙일 뿐이고 그래프를 바꾸지 않는다. 메서드로 읽는 단어는 `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, `TRACE`, `ANY`뿐이다(대소문자 무시). 경로 변수는 `{이름}`, `{이름:정규식}`(정규식은 보지 않고 한 세그먼트 안의 글자들로 본다), 풀리지 않은 `${프로퍼티}`이고 `*`는 한 세그먼트 안, `**`·`{*이름}`은 여러 세그먼트에 맞는다. 패턴이 여럿 맞으면 변수·와일드카드가 가장 적은 것만 남긴다. 끝의 `/` 하나는 무시한다. `;jsessionid=…` 같은 매트릭스 파라미터는 떼지 않는다. 컨텍스트 경로를 떼는 것은 추측이라(없는 경로의 뒤쪽이 다른 엔드포인트와 우연히 같을 수 있다) 뗀 경로를 표준 오류에 항상 알린다. 같은 경로에 `GET`과 `ANY` 엔드포인트가 함께 있으면 둘 다 후보이고, 검색어가 그중 하나의 표시 이름과 같으면 R46이 그것을 고른다. "같은 경로의 화면 요청"은 요청의 `attrs.path`(쿼리를 뗀 것)가 검색어의 경로(컨텍스트 경로를 떼기 전이나 뗀 뒤)와 글자 그대로 같고, 검색어에 메서드가 있으면 메서드가 같거나 `ANY`인 요청이다.
  - `find`(R50)는 `*`만 와일드카드다(`?`는 아니다). 단계별로 멈추는 규칙(이름이 같은 노드가 있으면 부분 일치를 내지 않는다)은 `calltree`가 같은 조회 API(`NodeFinder`, export 모듈)를 쓰므로 바꾸지 않았다. 확인한 사실: 부분 일치와 대소문자 무시는 전부터 됐고(`find UserContr`, `find usercontroller`), "정확히 같은 이름만 찾는다"로 보인 것은 이름이 같은 노드가 하나라도 있으면 거기서 멈추기 때문이다(jeesite에서 `find user`는 이름이 `user`인 필드 7개만 냈다. `user`가 든 노드는 552개). `*`는 안 됐다. 와일드카드 일치는 cli가 한다(`NodeGlob`). `calltree`의 검색어에는 와일드카드를 넣지 않았다.
  - 방언 불일치(R52): 방언 이름 표는 cli에 따로 둔다(`Hints`. sql의 `DialectTokens`, mybatis의 `DialectVariants`와 같은 표이고 `HintsTest.dialectOfPath`가 sql 쪽 테스트의 표를 옮겨 대조한다. 그쪽 표가 바뀌면 여기도 고쳐야 한다). 가장 많은 값으로 정하므로 주 방언의 테이블보다 다른 방언에서 보충한 테이블이 더 많은 프로젝트에서는 DDL 방언을 보충한 쪽으로 읽는다. 힌트에 남은 문구 의존: 방언 진단의 메시지에 `mybatis.dialectVariant`·`--dialect`가 이미 들어 있는지 보고 옵션 안내를 덧붙일지 정하는 것뿐이다(없으면 덧붙인다. 문구가 바뀌어도 안내가 겹치거나 빠지지 않는다).
- 3차 수정(2026-10-11): 후보 고르기(R46)는 `calltree`에만 있다. `impact`는 대상을 테이블·뷰·컬럼에서만 찾으므로(`ImpactAnalysis.resolve`) 요청 노드와 섞이지 않는다. 매퍼·DDL 방언의 비교(R47)는 두 진단 메시지의 `: <방언> 파일을 사용`에서 방언을 읽는다(진단에 구조화된 값이 없다). 문구가 바뀌면 경고가 나오지 않을 뿐이다. (5차 수정에서 그래프로 판정하게 바꿨다: R52.) `--classpath`(R48)는 `--classpath <폴더>`와 `--classpath '<폴더>/*'`로 jackson jar 셋을 담은 폴더를 줘서 라이브러리 메서드의 시그니처가 `(?)`에서 풀리는 것을 확인했다.
- 분석 대상 프로젝트의 파일은 읽기만 한다. 출력 폴더가 프로젝트 안에 있으면 그 폴더는 스캔에서 뺀다.
- 실행 환경은 JDK 21 이상이다. (ADR-0002)
- 조회용 하위 명령의 옵션 이름과 기본값은 사용자가 정한 목록을 조회 API에 맞춰 조정한 것이다: ERD 주석은 API 기본값이 켜짐이라 `--comments` 대신 `--no-comments`를 두었다. `--spec`은 처음에는 API가 범위를 받지 않아 전체 테이블과 뷰의 명세만 냈고, 지금은 ERD와 같은 범위 옵션을 받는다(F-001-R34). 그래서 `--spec`만 주면 뷰는 빠지고 `--views`를 줘야 든다. `impact`의 대상은 테이블·뷰·컬럼뿐이다. 사용자 검토가 필요하다.
- `calltree`의 후보 목록과 경고는 표준 오류에 쓴다. 표준 출력이나 `--out` 파일에는 결과만 들어간다.
- `export`의 `--opt`는 그 실행에만 쓰고 `graph.json`에 저장하지 않는다(그래프는 분석 결과이고 `export`는 그것을 바꾸지 않는다). 사용자 검토가 필요하다.
- `Pipeline.export`는 만들지 못한 구현체 가운데 출력기 것만 `SERVICE_LOAD_FAILED`로 남긴다(분석기·링커는 이 실행에서 쓰지 않는다).

- `--exclude-tests`(R43)가 더하는 패턴은 `src/test`다. 처음 제안은 `**/src/test/**`였지만 스캐너(설계 4.2)는 `**/`로 시작하는 패턴을 루트의 `src/test`에 맞추지 않으므로(단일 모듈 프로젝트가 빠진다), 앞에 경로가 없는 패턴을 어느 깊이에서든 맞추는 규칙을 썼다. Gradle의 `src/integrationTest` 같은 다른 테스트 소스 세트는 빼지 않는다(`--exclude`로 따로 준다).
- `calltree`의 `--hide-external`은 그대로 켜는 옵션이다(표준 산출물은 기본으로 접지만 조회 명령은 기본으로 보인다). 접근자만 기본으로 접는다(R40). 사용자 검토가 필요하다.
- 힌트(R44)의 문구는 계약이 아니다. 조건(진단 코드, 분류, 50건, 절반)과 "해당할 때만 한 줄"이 요구사항이다. 미해결 `java-call`은 대상 노드의 `category`로 세고(마무리 단계가 만든 `java:method` 매달린 노드도 `java-call`이다, R38) 위치는 엣지의 것을 쓴다. 옵션은 그래프에 저장된 것(`project.options`)을 읽으므로 `summary`도 같은 힌트를 낸다.
- 종료 코드 3(R42)은 메모리 부족에만 쓴다. 힙이 모자란 상태라 결과 파일을 마저 쓰지 않는다(쓰다 만 `graph.json`이 남을 수 있다).
- 메모리 부족 안내(R39): `AnalysisOutOfMemoryError`는 `Pipeline.run`·`analyze`·`export`에서 나온다. `Exception`·`StackOverflowError`·`LinkageError`와 달리 다음 구현체로 넘어가지 않는다(힙이 모자란 채로 계속해도 결과를 믿을 수 없다). 안내를 만들 메모리도 없으면 원래 오류가 그대로 나온다. `AnalysisContext.progress`는 값 하나를 덮어쓰기만 하므로(기본 구현체는 `volatile` 필드) 파일마다 불러도 되고, 여러 스레드로 파일을 처리하는 분석기에서는 마지막에 알린 스레드의 값이 남는다. 분석기들이 이 훅을 부르게 하는 것(파일 하나를 시작할 때 상대 경로를 알림)과 CLI가 이 오류를 잡아 스택 대신 한 줄과 종료 코드로 끝내는 것은 각 모듈의 몫이다.
- 미해결 분류(R38): `js:function` → `js-call`은 설계 3.6의 분류(대상을 찾지 못한 JS 호출)에 맞춘 것이다. `category`가 생겨도 마무리 단계가 만든 노드인지는 전처럼 `expectedPrefix`의 유무로 구분한다(설계 3.6의 "분류 없이 `expectedPrefix`만 있는 미해결 노드" 문장은 고쳐야 한다).
- 엣지 인덱스(R37): 엣지마다 넣은 순번을 두고, 종류를 여럿 고르면 종류별 목록을 순번으로 합친다. `replaceNode`는 합쳐지는 노드 쪽 인덱스를 통째로 떼어 엣지가 많은 노드를 합칠 때 엣지 수의 제곱만큼 일하지 않는다. `edges(kind)`·`nodes(kind)`는 전처럼 전체를 훑는다.

## 6. 미확정 사항
- [확인 필요] 대상 프로젝트 규모. 그래프 전체를 메모리에 올리는 방식의 한계를 재지 않았다. (design 7. 기능 동작은 막지 않으므로 구현은 진행했다.)

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 설계: [design.md](design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: [0002](../../decisions/0002-implement-in-java-21-maven.md), [0006](../../decisions/0006-graph-json-and-static-viewer.md)

## 8. design.md에 반영할 것

design.md 반영됨(8차 수정, 2026-10-11. 직접 고쳤다): 4.5의 요약 설명(그래프 그대로 세고 한 줄로 알림, `diagnostics.md`의 표시: R58), 힌트 표의 "서버 요청" 행(R60), `calltree`의 URL 검색(요청의 경로 패턴·컨텍스트 경로, 못 찾았을 때의 안내: R59), 3.4 "풀린 미해결 `EXECUTES`는 숨길 수 있다"의 셈 규칙.
(7차 수정. design.md는 이 작업에서 고치지 않았다.)

design.md 반영됨(2026-10-11): `calltree`의 URL 조회와 후보 목록의 `[POST|PUT]`, 도움말이 설명하는 표시는 4.5(`calltree` 항목), 요약의 `UNRESOLVED` 수와 알림 줄은 4.5(`analyze` 설명), 힌트의 미해결 Java 호출 수는 4.5 힌트 표에 넣었다. 벤더 힌트는 바뀐 것이 없어 그대로 두었다(메시지 꼴은 3.7 `FILE_SKIPPED`에 있다). 요청만 찾는 URL 검색의 한계는 7절에 더했다.
- 4.5 `calltree`의 URL 조회: 화면 요청의 메서드 후보(`attrs.httpMethods`)를 따르는 일치 판정, 엔드포인트가 없을 때 같은 경로의 화면 요청을 후보로 삼는 것, `--kind HTTP_REQUEST`만 줘도 URL로 요청을 찾는 것, `selfSubmit` 요청(`{self}`)은 URL 후보가 아니라는 것(F-001-R55).
- 4.5 후보 목록(`calltree`, `find`): 열은 번호, 종류, 노드 ID, 위치이고, 메서드 후보가 있는 화면 요청은 줄 끝에 `[POST|PUT]`이 붙는다(F-001-R55).
- 4.5 요약(`analyze`, `summary`): 노드 `UNRESOLVED` 수와 신뢰도별 `UNRESOLVED` 엣지 수는 `resolvedBy`가 있는 미해결 노드와 그리로 가는 엣지를 뺀 수이고, 뺀 것이 있으면 신뢰도 표 아래에 한 줄(`(같은 지점에서 다른 방법으로 풀린 참조 n개와 그리로 가는 엣지 m건은 UNRESOLVED에 세지 않았다: resolvedBy)`)이 붙는다(F-001-R56).
- 4.5 힌트 표: 미해결 Java 호출 수는 `resolvedBy`가 있는 대상으로 가는 엣지를 빼고 센다(F-001-R56). 벤더 힌트는 `FILE_SKIPPED` 메시지가 아니라 `skipped=vendor` 노드 수다(바뀐 것 없음. 메시지 꼴이 `벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): <폴더>`로 바뀌어도 같다).
- 4.5 도움말: `calltree`·`crud`가 `(인자 구문)`, `(접두 일치)`, `(동적 뷰 이름 후보)`, `POST|PUT`, `(자기 페이지로 제출)`, `[ambiguous n]`(n은 2 이상, 후보 엔드포인트 여럿과 맞은 요청 포함)을 설명한다(F-001-R57).
