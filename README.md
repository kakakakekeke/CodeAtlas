# CodeAtlas

Spring + MyBatis로 만든 웹·배치 Java 프로젝트의 소스(Java, 매퍼 XML, SQL·DDL·프로시저, JSP, JavaScript/TypeScript)를 정적 분석해 화면 → Controller → Service → Mapper → SQL → 테이블로 이어지는 관계 그래프를 만들고, 그 그래프에서 ERD, 클래스 다이어그램, 콜트리, 테이블 영향도를 뽑는 명령줄 도구다. 프로그램을 실행하거나 DB에 접속하지 않는다.

## 요구 사항

- JDK 21 이상
- 빌드할 때만 Maven 3.9
- 실행 중에 네트워크를 쓰지 않는다. 실행 jar 하나(`codeatlas.jar`)로 동작한다.

## 빌드

저장소 루트에서 실행한다. 실행 jar는 `codeatlas-cli/target/codeatlas.jar`에 생긴다.

```sh
mvn -q install -Dsample.verify=report   # 샘플 정답 대조는 리포트만 쓰고 단언하지 않는다
mvn -q install                          # 샘플 정답 대조까지 통과해야 끝난다
```

`bin/codeatlas`는 이 jar를 `java -jar`로 실행하는 셸 스크립트다(`CODEATLAS_JAR`, `JAVA_HOME`, `JAVA_OPTS` 환경 변수를 본다. JVM 힙은 `JAVA_OPTS=-Xmx8g bin/codeatlas analyze ...`처럼 준다). 셸이 없는 환경에서는 `java -jar codeatlas-cli/target/codeatlas.jar <명령> ...`으로 실행한다.

## 빠른 시작

```sh
bin/codeatlas analyze <프로젝트 폴더> --ddl <DDL 파일 또는 폴더> --classpath <의존 jar 또는 폴더> --out <출력 폴더>
```

- `--ddl`, `--classpath`는 여러 번 줄 수 있고 생략할 수 있다. `--classpath`에 폴더를 주면 그 바로 아래의 jar도 함께 쓴다. `--out`을 생략하면 `./codeatlas-out`에 쓴다.
- 분석 대상 프로젝트의 파일은 읽기만 한다.
- 그 밖의 옵션(`--encoding`, `--dialect oracle|db2|auto`, `--exclude <glob>`, `--exclude-tests`, `--no-export`, `--quiet`)은 `bin/codeatlas help analyze`로 본다.
- 실제 프로젝트에 처음 돌릴 때는 아래 "큰 프로젝트, 방언이 여러 벌인 프로젝트에 돌릴 때"를 먼저 본다.

끝나면 표준 출력에 요약(파일·노드·엣지 종류별 개수, 신뢰도별 엣지 수, 진단 수)이 나오고, 손볼 만한 것이 있으면 요약 끝에 "힌트"가 한 줄씩 붙는다(방언별 매퍼·DDL을 한 벌만 골랐음, 미해결 호출이 많은데 `--classpath`가 없음, 같은 테이블의 DDL이 여러 벌임, SQL이 DDL에 없는 컬럼을 참조함, 서버 요청은 있는데 엔드포인트가 없음(`report.md`의 "화면 요청"과 같은 서버 요청 수) 등). 프로젝트 설정 때문에 대상 외 방언으로 분석됐거나 요청한 방언의 파일이 없으면 힌트 맨 위에 `주의:`로, 매퍼와 DDL에서 고른 방언이 다르면 `경고:`로 나온다. 출력 폴더는 이렇게 채워진다.

| 경로 | 내용 |
|---|---|
| `graph.json` | 분석 결과 그래프 전체(노드, 엣지, 진단)와 분석할 때 준 옵션(`project.options`). 아래 조회 명령이 모두 이 파일을 읽는다 |
| `diagnostics.md` | 진단 목록(파싱 실패, 미해결 참조, 동적 SQL 등). 코드별 개수와 위치별 목록 |
| `report.md` | 분석 요약: 종류별 개수, 미해결 참조, 진단 상위 항목, 점검 목록(DDL에 없는 테이블과 그 테이블을 참조하는 구문 수, 참조되지 않는 테이블, 대상 테이블을 정적으로 알 수 없는 구문, 대응 구문이 없는 매퍼 메서드, 호출하는 곳이 없는 매퍼 구문, Java 호출자가 없는 매퍼 구문), 데이터 적재 스크립트 요약(파일, 테이블, 행 수) |
| `erd/` | `erd.mmd`(Mermaid), `erd.puml`(PlantUML), `erd.dbml`(DBML), `tables.md`(테이블 명세). ERD가 Mermaid가 그릴 수 있는 크기를 넘으면 `index.md`(목차), `erd-overview.mmd`(전체 테이블, 키 컬럼만), `erd-<영역>.mmd`(영역별 다이어그램)가 더 생긴다. 영역은 테이블 이름 접두사로 나누고, 접두사로 묶이지 않는 테이블(밑줄 없는 이름)은 관계로 이어진 테이블끼리 묶어 그 안에서 가장 많이 참조되는 테이블의 이름을 붙인다. 전체의 20% 넘는 테이블이 참조하는 허브(공통코드 등)는 영역에 넣지 않고 `index.md`에 "허브 테이블"로 따로 적는다 |
| `class-diagram/` | 패키지마다 `<패키지>.mmd`, `<패키지>.puml`, 그리고 목차 `index.md`. 큰 패키지의 Mermaid는 멤버를 줄이거나 `<패키지>-<n>.mmd`로 나누고 그 사실을 `index.md`의 비고에 적는다 |
| `crud/` | CRUD 매트릭스 CSV: `table-statement.csv`, `table-method.csv`, `table-endpoint.csv`, `table-routine.csv`. `README.md`는 칸 표기 설명이다(대·소문자, 끝의 `?`, `(동적)` 열, 데이터 적재 구문과 redirect 대상을 넣지 않는다는 것) |
| `calltree/` | `endpoints.md`(엔드포인트별 콜트리), `filters.md`(서블릿 필터와 인터셉터의 진입 메서드에서 시작하는 콜트리), `batch-jobs.md`(배치 잡별 콜트리. 스텝은 이름순이 아니라 흐름 순서). 엔드포인트가 50개를 넘으면 트리는 컨트롤러별 `endpoints/<컨트롤러>.md`로 나뉘고 `endpoints.md`는 색인(엔드포인트, 파일, 닿는 테이블)이 된다. 깊이 20까지 펼치고, 깊이 제한에 잘린 트리 끝에는 안내 줄이 붙는다. 접근자(getter·setter)와 소스 없는 라이브러리 메서드의 호출은 접고, 컨트롤러가 그리는 JSP는 이름만 적는다(아래 "주요 옵션"으로 바꾼다) |
| `viewer.html` | 그래프를 내장한 단일 HTML 뷰어. 브라우저로 그냥 연다 |

대상이 없는 산출물은 빈 파일로 쓰지 않고 생략한다. 테이블·뷰가 하나도 없으면(DDL도 SQL도 분석되지 않음) `erd/`와 `crud/`가, Java 타입이 없으면 `class-diagram/`이, 엔드포인트, 필터·인터셉터, 배치 잡이 없으면 `calltree/`의 해당 파일이 생기지 않고, `report.md` 머리에 `DB 객체 없음(DDL이나 SQL이 분석되지 않음)` 같은 줄이 남는다. 테이블에 접근하는 주체가 없는 CRUD 매트릭스 파일도 생기지 않는다(예: 프로시저·함수·트리거가 없는 프로젝트에는 `table-routine.csv`가 없다).

## 조회 명령

분석은 한 번만 하고, 그 뒤에는 저장된 `graph.json`에서 필요한 것을 뽑는다. 명령 목록은 `bin/codeatlas --help`, 명령별 옵션은 `bin/codeatlas help <명령>`으로 본다.

- 그래프는 첫 인자나 `--graph`로 준다(`graph.json` 또는 출력 폴더). 생략하면 `./codeatlas-out`을 읽는다. `calltree`, `impact`, `find`는 첫 인자가 검색어라 `--graph`로만 준다.
- 결과는 표준 출력에 나온다. `--out <파일>`을 주면 파일에 쓴다.
- 종료 코드: 0 성공, 1 대상 없음(그래프 파일이 없음, 찾는 테이블·노드가 없음), 2 옵션 오류, 3 메모리 부족.

아래 예시는 출력 폴더가 `out`이라고 보고 적었다.

### 1. ERD 얻기

```sh
bin/codeatlas erd out                                      # 전체 테이블, Mermaid
bin/codeatlas erd out --table TB_ORDER --depth 1 --columns keys --format plantuml
bin/codeatlas erd out --pattern 'TB_ORDER*' --no-inferred --format dbml --out order.dbml
bin/codeatlas erd out --spec --out tables.md               # 테이블 명세(Markdown), 전체 테이블
bin/codeatlas erd out --spec --table TB_ORDER --depth 1    # TB_ORDER와 관계로 이어진 테이블만
```

Mermaid와 PlantUML에서 DDL의 외래 키는 실선, SQL의 JOIN 조건에서 추론한 관계는 점선이다(DBML은 주석으로 표시한다). 추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 "0 또는 1"로 그린다(`부모 |o..o{ 자식`). DDL 외래 키는 FK 컬럼이 NOT NULL이면 부모 필수(`||--o{`), nullable이면 `|o--o{`다. `--min-evidence <n>`으로 근거가 적은 추론 관계를 걸러 낸다. 뷰는 `--views`를 줘야 들어간다. DB가 제공하는 시스템 카탈로그(`USER_TABLES`, `information_schema.TABLES` 등)는 프로젝트 스키마가 아니라서 ERD에 그리지 않고(뺀 수는 다이어그램 머리 주석과 `report.md`에 나온다) 테이블 명세의 끝에 이름만 남긴다.

`--spec`은 ERD 대신 테이블마다 컬럼 표, PK, 관계, 참조하는 구문 수를 적은 명세를 낸다. 범위 옵션(`--table`, `--depth`, `--pattern`, `--views`)은 ERD와 같은 뜻이고, 범위를 좁히면 범위 밖 테이블과의 관계는 적지 않는다. `--table`로 준 이름을 찾지 못하면 그 이름을 표준 오류에 알린다.

### 2. 클래스 다이어그램

```sh
bin/codeatlas classdiagram out --package com.example.shop.order.service
bin/codeatlas classdiagram out --type OrderService --depth 1 --members none --format plantuml
```

`--package`는 하위 패키지를 포함한다(`--no-subpackages`로 끈다). `--type`은 그 타입과 관계로 이어진 이웃을 그린다. 타입이 60개(`--max-types`)를 넘으면 멤버를 생략하고 표준 오류에 알린다.

### 3. 메서드 콜트리

```sh
bin/codeatlas find getOrder --graph out                    # 노드 ID 찾기
bin/codeatlas find '*order*' --kind JAVA_TYPE --graph out  # * 와일드카드(부분 일치, 대소문자 무시)
bin/codeatlas calltree 'OrderServiceImpl#getOrder' --graph out             # 호출되는 쪽(callees)
bin/codeatlas calltree 'OrderServiceImpl#getOrder' --graph out --callers   # 호출하는 쪽
bin/codeatlas calltree 'GET /api/orders/{id}' --graph out --depth 5 --format mermaid
bin/codeatlas calltree 'OrderController#detail' --graph out --views        # 그리는 JSP 아래의 요청까지 펼친다
bin/codeatlas calltree 'GET /api/orders/{id}' --graph out --show-accessors # 접근자 호출도 보인다
```

```text
$ bin/codeatlas calltree 'GET /api/orders/{id}' --graph out --no-location
[endpoint] GET /api/orders/{id}
└─ OrderApiController#get(Long)
   └─ OrderService#getOrder(Long)
      └─ [impl] OrderServiceImpl#getOrder(Long)
         ├─ BaseService#getOrThrow(Long)
         │  ├─ BaseService#doFind(Long)
         │  │  └─ [impl] OrderServiceImpl#doFind(Long)
         │  │     └─ OrderMapper#selectOrderDetail(Long)
         │  │        └─ [sql] OrderMapper.selectOrderDetail <SELECT>
         │  │           ├─ [fragment] OrderMapper.orderColumns
         │  │           ├─ [table] TB_MEMBER (R)
         │  │           ├─ [table] TB_ORDER (R)
         │  │           ├─ [table] TB_ORDER_ITEM (R)
         │  │           └─ [table] TB_PRODUCT (R)
         │  └─ BizException#<init>(String, String)
         └─ OrderMapper#selectOrderItems(Long)
            └─ [sql] OrderMapper.selectOrderItems <SELECT>
               ├─ [table] TB_ORDER_HIST (R)
               ├─ [table] TB_ORDER_ITEM (R)
               └─ [table] TB_PRODUCT (R)
```

`--no-location`을 빼면 줄마다 소스 위치(`file:line`)가 붙는다. 호출은 이름순이 아니라 소스에 나온 순서(호출 지점의 줄)로 나온다. 접근자 호출은 접혀 있다: 위 트리에서 `OrderServiceImpl#getOrder`가 부르는 `OrderVO#setItems(List)`는 `--show-accessors`를 줘야 나온다(Lombok이 만들 멤버와, 같은 타입에 대응하는 필드가 있고 다른 호출이 나가지 않는 `getXxx`/`isXxx`/`setXxx`. `isAuthenticated()`나 `setComplete()`처럼 필드가 없는 메서드는 접지 않는다. 부수 효과 없는 생성자(본문에서 호출이 나가지 않는 `<init>`)와 MyBatis Generator의 `*Example`·`*Example$Criteria` 빌더 호출도 함께 접혀 있다가 이 옵션으로 펴진다). 소스가 없는 라이브러리·JDK 메서드의 호출도 표준 산출물처럼 접혀 있고 `--show-external`로 편다. 컨트롤러가 그리는 JSP는 `[jsp]`, redirect·forward로 넘어가는 다른 엔드포인트의 핸들러는 `[redirect]`·`[forward]` 한 줄로만 나오고, `--views`를 주면 그 너머(그 JSP의 include, 스크립트, 그 화면에서 나가는 요청과 그 요청의 엔드포인트, 넘어간 핸들러의 처리)까지 이어서 펼친다(화면 이동을 따라가므로 트리가 금방 커진다). 레이아웃(sitemesh 데코레이터, tiles template과 조각)은 `[layout]` 한 줄이고 `--layouts`로만 펼친다. 위 방향(`--callers`)에서 여러 화면에 include되는 공통 JSP는 `[shared] <JSP> (포함하는 화면 n개)` 한 줄로 접히고, 이것도 `--layouts`로 펼친다. 구문 아래에는 그 구문이 읽고 쓰는 테이블이 `[table] 이름 (R)`처럼 접근 종류(C/R/U/D)와 함께 이어진다. 테이블에서 거꾸로 올라갈 수도 있다: `bin/codeatlas calltree TB_ORDER_HIST --graph out --callers`는 그 테이블을 읽고 쓰는 구문 → 매퍼 메서드 → 서비스 → 컨트롤러 → 엔드포인트 → 화면 순으로 펼친다.

시작 노드는 노드 ID, 이름, 이름의 일부로 찾는다. 후보가 여럿이면 번호·종류·ID·위치 목록을 보여 주고 끝나므로, ID를 그대로 주거나 `--pick <번호>`, `--kind JAVA_METHOD`로 좁힌다. 다만 검색어와 표시 이름이 정확히 같은 후보가 엔드포인트 > 메서드 > 구문 > 테이블 > 루틴 > 배치 잡 > JSP > JS 함수 순에서 가장 앞선 종류에 하나뿐이면 그것을 고른다(`'GET /orders/{id}'`처럼 엔드포인트와 같은 글자의 화면 요청이 여럿 있어도 엔드포인트의 트리가 나오고, 다른 후보의 수는 표준 오류에 한 줄로 알린다. `find`는 전부 보인다). 검색어가 `[METHOD ]/경로` 꼴이면 URL로 보고 엔드포인트를 찾는다: `calltree 'GET /egov/cmm/fms/FileDown.do?atchFileId=1'`처럼 브라우저 주소를 그대로 줘도 된다. 쿼리(`?…`)와 `#…`를 떼고, 메서드가 같거나 `ANY`인 엔드포인트(메서드를 안 주면 그 경로의 전부)에 맞추며, 경로 변수 패턴(`/orders/{id}`)에 구체 경로(`/orders/15`)를 줘도 맞는다. 못 찾으면 맨 앞 1~2 세그먼트(컨텍스트 경로)를 떼고 다시 찾는다. 맞은 엔드포인트가 하나면 같은 경로의 화면 요청이 몇 개든 그것을 고르고 표준 오류에 한 줄로 알린다(화면 요청에서 시작하려면 `--kind HTTP_REQUEST`). `find`는 이름이 같은 노드가 있으면 거기서 멈추므로(가려진 부분 일치의 수는 표준 오류에 알린다) 이름의 일부로 모두 찾으려면 `find '*order*'`처럼 `*` 와일드카드를 쓴다. 메서드뿐 아니라 엔드포인트, 매퍼 구문, 테이블, JSP, JS 함수, 배치 잡에서도 시작할 수 있다. 줄 앞의 `~`는 추론한 관계, `?`는 미해결이고, `[impl]`은 인터페이스·추상 메서드의 구현(재정의의 재정의 같은 간접 구현까지 펼치고, 주입 정보가 있으면 주입되는 구현으로 좁힌다), `[see above]`는 앞에서 이미 펼친 노드다. 그 밖의 표식:

- `[before]`: 핸들러보다 먼저 실행되는 같은 컨트롤러의 `@ModelAttribute` 메서드. 요청으로 진입한 핸들러(엔드포인트에서 온 핸들러, 시작 노드)의 자식 맨 앞에만 나온다. 다른 핸들러가 Java 호출로 부른 핸들러 아래에는 붙지 않는다.
- `[redirect]`, `[forward]`: 뷰 이름 `redirect:`·`forward:`나 서블릿 API로 다른 엔드포인트의 핸들러로 넘어감. 테이블 × 엔드포인트 CRUD와 `impact`에서 redirect 대상의 테이블은 원래 엔드포인트에 넣지 않고(브라우저가 새로 보내는 별개의 요청이다) forward 대상은 넣는다.
- `[route]`: 서버 요청이 아니라 화면 안의 이동(React Router 등의 클라이언트 라우트). 엔드포인트로 이어지지 않아도 미해결로 치지 않는다.
- `[layout]`: 레이아웃(데코레이터, tiles template·조각). 위 방향(`--callers`)에서는 그 레이아웃을 쓰는 화면을 `[layout] …를 쓰는 화면 n개` 한 줄로 접는다.
- `[ambiguous n]`: 오버로드를 가리지 못해 한 호출 지점에서 같은 이름의 메서드 n개로 이은 호출, 후보 엔드포인트 n개와 맞은 요청, 가리지 못한 동적 데코레이터의 후보 레이아웃 n개. 아래 줄이 후보다. n은 2 이상이다(후보가 하나면 붙지 않는다).
- `[reflect]`: 리플렉션(`getDeclaredMethod("이름").invoke(..)`)으로 부른다고 추론한 호출.
- `[?]`: 여러 타입으로 퍼지는 호출(상위 DAO에서 물려받은 매퍼 메서드의 구문, 리플렉션 호출의 대상)에서 지금 경로의 수신자 타입에 맞는 것을 고르지 못해 전부 따라간 줄. 맞는 것을 고를 수 있으면 그것만 나온다.
- `(모듈 <경로>)`: 같은 경로의 핸들러가 여러 웹 모듈에 있는 엔드포인트에서 시작했을 때 핸들러 줄에 붙는다. 화면의 요청에서 따라온 경우에는 그 요청의 모듈(분석기가 요청 ↔ 엔드포인트 대응에 적어 둔 모듈, 없으면 요청을 낸 파일의 경로)과 같은 모듈의 핸들러·화면만 따라간다.
- `(접두 일치)`, `(인자 구문)`, `(동적 뷰 이름 후보)`: 추론(`~`) 줄이 무엇으로 이어졌는지다. 구문 ID가 접두만 맞아 이은 구문, 구문 ID를 인자로 받는 메서드에 넘긴 문자열로 이은 구문, 동적 조각이 든 뷰 이름으로 찾은 화면. 동적 뷰의 후보를 모듈의 프로퍼티 값이나 모듈 디렉터리 이름으로 좁힌 화면은 "후보" 대신 `(동적 뷰 이름: 프로퍼티 값으로 정함)`, `(동적 뷰 이름: 모듈 이름으로 정함)`으로 나온다. 같은 지점의 미해결 구문 줄은 이렇게 풀렸으면 따로 나오지 않는다.
- `--callers`로 엔드포인트에서 시작하면 화면 요청과 함께 그 URL로 넘어오는 `[forward]`·`[redirect]` 핸들러가 한 줄씩 나오고, 핸들러가 여러 웹 모듈에 있는 엔드포인트면 요청 줄에 `(모듈 <경로>)`가 붙는다.
- 요청 이름의 `POST|PUT /api/x`는 호출에 따라 메서드가 달라지는 요청, `POST (자기 페이지로 제출)`은 action이 없는 폼(그 페이지를 그린 주소로 제출)이다. 맞는 엔드포인트를 찾지 못한 action 없는 폼은 `? [unresolved] POST /place/view.do (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 POST 매핑 없음)`처럼 찾던 주소와 까닭이 나오고(그리는 엔드포인트를 모르면 `…를 찾지 못함`), 후보가 여럿이면 `[ambiguous n] 후보 엔드포인트` 아래에 묶인다. URL 검색에서 `POST|PUT` 요청은 그 메서드로만 찾아지고, 맞는 엔드포인트가 없으면 같은 경로의 화면 요청을 찾는다: 화면 요청의 경로 패턴(`/api/schedule/{*}`)에도 구체 경로(`'PUT /api/schedule/12'`)가 맞고, 엔드포인트처럼 컨텍스트 경로를 떼고 다시 찾는다. URL로 아무것도 못 찾으면 오류에 같은 경로의 다른 메서드(`같은 경로에 다른 메서드의 엔드포인트가 있다: POST /x`)와 패턴 꼴로도 찾을 수 있다는 안내가 붙는다. action 없는 폼의 요청은 URL로 찾지 못한다(노드 ID로 연다). 후보 목록과 `find` 줄 끝에는 `[POST|PUT]`이 붙는다.

경로에 프로퍼티 자리표시자가 있는 엔드포인트(`@RequestMapping("${adminPath}/sys/user")`)는 프로젝트의 properties 값으로 푼 경로와 원문을 함께 보여 준다: `ANY /a/act/task (/${adminPath}/act/task)`. 검색어로는 어느 쪽을 줘도 찾는다.

테이블을 바꾸는 구문 아래에는 그 테이블에 걸린 트리거가 `[trigger]`로 이어진다. 구문의 쓰기 종류(INSERT/UPDATE/DELETE)가 트리거의 이벤트와 맞을 때만 나오고(`UPDATE OF <컬럼>` 트리거는 그 컬럼을 바꿀 때만), 맞는지 알 수 없으면 `[trigger?]`로 표시한다.

### 4. 테이블 영향도

```sh
bin/codeatlas impact TB_ORDER --graph out                  # 테이블을 읽고 쓰는 구문 → 메서드 → 엔드포인트 → 화면
bin/codeatlas impact TB_ORDER.ORDER_STATUS --graph out --format json
bin/codeatlas impact TB_ORDER --graph out --include-data-load   # 데이터 적재 스크립트의 INSERT도 넣는다
bin/codeatlas crud out --by endpoint --format md           # 엔드포인트 × 테이블 CRUD 매트릭스
bin/codeatlas crud out --by method --bom --out crud.csv    # 엑셀용 CSV
bin/codeatlas crud out --include-data-load                 # 데이터 적재 스크립트의 INSERT도 넣는다
```

컬럼을 대상으로 하면 그 컬럼을 쓰는 것이 확인된 구문과, 테이블은 참조하지만 컬럼을 해석하지 못한 구문("테이블 수준에서만 확인됨")을 나눠 보여 준다. 대상 이름을 찾지 못하면 이름이 비슷한 테이블·뷰·컬럼을 10개까지 보여 주고 종료 코드 1로 끝난다. 추론한 관계를 거쳐서만 닿은 항목은 이름 앞에 `~`가 붙고 경로 끝에 `추론:` 근거(그 경로에서 처음 만나는 추론 엣지와 까닭)가 붙는다. 확정 경로가 하나라도 있으면 붙지 않는다.

CRUD 매트릭스의 칸은 C(INSERT), R(조회), U(UPDATE), D(DELETE)를 이어 쓴 것이다(`MERGE`는 C와 U). 읽는 법은 이렇다.

- **R은 그 테이블을 조회할 때만 붙는다.** 바꾸는 대상 테이블의 조건절에서 컬럼을 읽는 것은 R로 세지 않는다. `DELETE FROM T WHERE ...`는 `D`, 조건이 있는 `UPDATE`는 `U`, `MERGE`의 대상 테이블은 `CU`다. 같은 구문이 서브쿼리나 `USING`에서 그 테이블을 따로 조회하면 `RD`, `RU`가 된다.
- **대문자는 직접 접근, 소문자는 DB 루틴을 거친 접근이다.** 프로시저·함수·트리거의 본문을 거쳐서만 닿는 테이블은 소문자로 적는다. 예를 들어 프로시저를 호출하는 매퍼 구문의 행에는 그 프로시저(와 그것이 부르는 루틴)가 읽고 쓰는 테이블이 `r`, `cd`처럼 나온다. 한 칸에 `Ru`처럼 섞일 수 있고(직접 조회 + 루틴을 거친 UPDATE), 같은 글자가 둘 다면 대문자만 적는다.
- **끝의 `?`는 추론한 관계를 거쳐서만 닿은 칸이다.** 예: `R?`, `ru?`. 그 테이블에 닿는 확정 경로가 하나라도 있으면 붙지 않는다. 어떤 걸음을 추론으로 치는지는 산출물의 `crud/README.md`에 있다.
- **데이터 적재 스크립트는 기본으로 빠진다.** 코드·샘플 데이터를 넣는 SQL 스크립트의 INSERT 묶음(아래 "큰 프로젝트" 절)은 애플리케이션이 실행하는 구문이 아니라서 매트릭스와 콜트리에 넣지 않는다. `--include-data-load`(조회 명령)나 `--opt export.includeDataLoad=true`(표준 산출물)로 넣는다. 영향도(`impact`)도 기본으로 빼고 뺀 수를 리포트 머리에 알리며(`데이터 적재 구문 n건 제외`) `--include-data-load`로 넣는다.
- **엔드포인트 행의 이름**은 콜트리와 같은 표기다. 프로퍼티 자리표시자가 풀린 엔드포인트는 `ANY /a/act/task (/${adminPath}/act/task)`처럼 푼 경로 뒤에 원문이 괄호로 붙는다.
- **`(동적)` 열은 대상 테이블을 알 수 없는 접근이다.** 테이블 이름이 실행 시점에 정해지는 동적 SQL(`${...}`, 풀리지 않은 `EXECUTE IMMEDIATE`)은 어느 테이블 열에도 넣을 수 없어 맨 끝의 `(동적)` 열에 접근 종류만 적는다. 그런 구문에 닿는 행이 없는 매트릭스에는 이 열이 생기지 않는다. 구문 목록은 `report.md`의 "대상 테이블을 정적으로 알 수 없는 구문" 절에 있다.

`--by routine`은 프로시저·함수·트리거가 본문에서 읽고 쓰는 테이블(대문자)과 그 루틴이 부르는 다른 루틴이 읽고 쓰는 테이블(소문자)을 낸다. `--by endpoint`는 엔드포인트에서 호출을 따라 닿는 구문의 테이블이고, 트리거가 쓰는 테이블은 그 트리거를 일으키는 쓰기를 거칠 때만 들어간다. forward로 넘어간 핸들러의 접근은 그 엔드포인트에 포함하고, redirect 대상의 접근은 넣지 않는다(브라우저가 새로 보내는 별개의 요청이다).

### 그 밖의 명령

```sh
bin/codeatlas report out                                   # report.md와 같은 분석 요약
bin/codeatlas summary out                                  # analyze가 끝에 찍는 개수 요약
bin/codeatlas export out --out out2 --opt export.erd.columns=keys   # 분석 없이 산출물만 다시 생성
```

`export`는 `analyze` 때 준 `--opt`를 `graph.json`에서 이어받고, `export`에 준 `--opt`가 같은 키를 덮어쓴다. `--out`에 그래프가 있는 폴더를 그대로 주면 `graph.json`과 `diagnostics.md`는 두고 산출물만 다시 쓴다.

## 주요 옵션 (`--opt <key>=<value>`)

`analyze`와 `export`에 여러 번 줄 수 있다. 분석 옵션(`java.*`, `mybatis.*`, `web.*`, `link.*`)은 `analyze`에서만 뜻이 있고, 출력 옵션(`export.*`)은 `export`로 다시 줄 수 있다. `analyze`에 준 값은 `graph.json`에 저장되어 `export`의 기본값이 된다. 잘못된 값은 `INVALID_OPTION` 진단을 남기고 기본값을 쓴다.

| 키 | 기본값 | 뜻 |
|---|---|---|
| `java.externalCalls` | `nonJdk` | 소스가 없는 라이브러리 메서드 호출을 기록할 범위. `none`, `nonJdk`(JDK 호출은 뺀다), `all` |
| `java.includeJdkTypes` | `false` | `true`면 JDK 타입(`java.`, `javax.`, `jdk.`)으로의 연관·의존 관계도 만든다 |
| `mybatis.dialectVariant` | `auto` | 매퍼가 방언별 파일로 여러 벌일 때 쓸 방언. `auto`(`--dialect`, 프로젝트 설정이 가리키는 대상 방언, 그다음 Oracle → DB2 → 그 계열 → 그 밖의 순), 방언 이름(`oracle`, `db2`, `tibero`, `mysql` 등), `all`(고르지 않고 전부 합친다). 방언 이름을 주면 방언별 DDL도 그 방언의 파일을 고른다. `--dialect`는 oracle·db2만 받으므로 그 밖의 방언(mysql 등)으로 분석하려면 이 옵션을 쓴다 |
| `web.skipVendor` | `true` | 벤더(서드파티) 스크립트와 벤더 폴더의 페이지를 내용 분석 없이 건너뛴다. `false`면 분석한다 |
| `web.vendorPatterns` | 없음 | 벤더로 볼 경로를 쉼표로 구분한 glob으로 더한다. `!`로 시작하는 glob은 "벤더가 아니다" |
| `link.minJoinEvidence` | `1` | JOIN에서 관계를 추론할 때 필요한 최소 근거 구문 수 |
| `link.inferViewRelations` | `false` | 뷰가 낀 JOIN에서도 관계를 추론한다(방향은 정하지 않는다). ERD에서 보려면 `export.erd.views`도 켠다 |
| `export.erd`, `export.classDiagram`, `export.crud`, `export.calltree`, `export.report` | `true` | `false`면 그 산출물을 쓰지 않는다 |
| `export.erd.columns` | `all` | `erd/`의 컬럼 표시 수준. `all`, `keys`, `none` |
| `export.erd.views` | `false` | `erd/`에 뷰를 넣는다 |
| `export.classDiagram.members` | `all` | `class-diagram/`의 멤버 표시 수준. `all`, `public`, `none` |
| `export.includeDataLoad` | `false` | 데이터 적재 스크립트의 INSERT 묶음을 CRUD 매트릭스, 콜트리, 테이블 명세의 구문 수, 리포트의 테이블 점검에 넣는다 |
| `export.mermaid.maxChars` | `45000` | Mermaid 다이어그램 하나의 글자 수 한도. 넘으면 ERD는 영역별로 나누고 클래스 다이어그램은 멤버를 줄이거나 나눈다. `0`이면 보지 않는다 |
| `export.calltree.depth` | `20` | `calltree/`의 트리 깊이. 깊이 제한에 잘린 트리 끝에는 안내 줄이 붙는다 |
| `export.calltree.expandViews` | `false` | 컨트롤러가 그리는 JSP 아래의 요청과 redirect·forward로 넘어간 핸들러의 아래까지 펼친다(레이아웃은 펼치지 않는다) |
| `export.calltree.hideAccessors` | `true` | `calltree/`에서 접근자 호출 줄을 접는다(Lombok이 만들 멤버, 같은 타입에 대응하는 필드가 있는 `getXxx`/`isXxx`/`setXxx`) |
| `export.calltree.hideExternal` | `true` | `calltree/`에서 소스 없는 라이브러리·JDK 메서드의 호출 줄을 접는다 |
| `export.calltree.splitOver` | `50` | 엔드포인트가 이 수를 넘으면 엔드포인트 콜트리를 컨트롤러별 파일로 나눈다. `0`이면 나누지 않는다 |
| `export.crud.bom` | `true` | `crud/` CSV에 UTF-8 BOM을 붙인다 |

전체 목록은 [그래프 계약 문서](docs/specs/F-001-analysis-pipeline/design.md)의 3.8절에 있다.

## 큰 프로젝트, 방언이 여러 벌인 프로젝트에 돌릴 때

공개 프로젝트(전자정부 표준프레임워크 템플릿·공통컴포넌트, jeesite 등)에 돌려 보며 확인한 것이다. 무엇을 골랐고 무엇을 건너뛰었는지는 분석이 끝날 때 요약 끝의 "힌트"와 `diagnostics.md`에 나온다.

```text
힌트
  - 방언별 매퍼 6벌 감지(oracle, tibero, altibase, hsql, cubrid, mysql): oracle 파일을 사용, 제외한 파일 50개. 프로젝트 설정(src/main/resources/application.properties:Globals.DbType=hsql)과 다르게 대상 방언 oracle 파일을 사용. 설정대로 분석하려면 `--opt mybatis.dialectVariant=hsql`. `--opt mybatis.dialectVariant=<방언|all>`로 변경
  - 방언별 DDL 6벌 감지(oracle, tibero, altibase, hsql, cubrid, mysql): oracle 파일을 사용(테이블 21개), 다른 방언에서 보충 0개, 제외한 파일 9개. 데이터 적재 스크립트도 같은 규칙으로 골라 다른 방언 파일의 묶음 54개를 만들지 않음. 프로젝트 설정(src/main/resources/application.properties:Globals.DbType=hsql)과 다르게 대상 방언 oracle 파일을 사용. 설정대로 분석하려면 `--opt mybatis.dialectVariant=hsql`. `--dialect <oracle|db2>`(그 밖의 방언은 `--opt mybatis.dialectVariant=<방언>`)로 변경
  - 미해결 Java 호출 722건: 의존 jar(또는 클래스 폴더)를 --classpath로 주면 라이브러리 타입이 해석되어 줄어든다
  - 미해결 Java 호출 722건 가운데 653건이 테스트 코드(src/test)에서 나왔다: --exclude-tests로 테스트 소스를 뺄 수 있다
  - 데이터 적재 스크립트 1개 파일의 INSERT를 테이블별 구문으로 묶었다: CRUD 매트릭스·콜트리·테이블 점검에서는 기본으로 빠진다(넣으려면 --opt export.includeDataLoad=true, 분석에서 아예 빼려면 --exclude)
```

(전자정부 simple backend 템플릿을 옵션 없이 분석했을 때의 힌트다.)

- **방언별 매퍼**: 같은 namespace의 매퍼가 `Xxx_SQL_oracle.xml`, `Xxx_SQL_mysql.xml`처럼 방언별 파일(또는 `oracle/`, `mysql/` 폴더)로 여러 벌 있으면 한 방언의 정의만 쓴다. 전부 합치면 한 구문의 테이블이 방언들의 합집합이 되기 때문이다. 고르는 순서는 직접 준 값(`--dialect oracle|db2`, `--opt mybatis.dialectVariant=<방언>`) → 프로젝트 설정(`Globals.DbType`, JDBC URL 등)이 가리키는 대상 방언(Oracle, DB2) → Oracle → DB2 → Tibero·Altibase → 설정이 가리키는 그 밖의 방언 → 나머지다. 이 도구의 대상은 Oracle과 DB2라서, 프로젝트 설정이 mysql이어도 oracle 파일이 있으면 oracle 파일을 쓰고 그 사실을 힌트와 `DIALECT_VARIANT_MAPPERS`(INFO)로 알린다. 설정대로 분석하려면 `--opt mybatis.dialectVariant=mysql`처럼 직접 정한다(`--dialect`는 oracle·db2·auto만 받는다. `all`은 전처럼 전부 합친다). 대상 방언의 파일이 하나도 없어 그 밖의 방언을 썼을 때만 경고(WARN, 힌트의 `주의:`)다. 고른 방언의 파일에 없는 구문은 다음 순서의 방언 파일에서 가져온다.
- **방언별 DDL**: 같은 테이블의 `CREATE TABLE`이 여러 파일에 있으면 한 파일의 정의만 쓴다(컬럼, 키, 인덱스, 외래 키가 섞이지 않게). 경로(파일·폴더 이름)에 방언 이름이 있는 방언별 DDL이면 방언별 매퍼와 같은 순서로 한 방언의 파일을 고르고(`--opt mybatis.dialectVariant=<방언>`도 같이 읽는다) 감지한 방언, 쓴 방언, 제외한 파일 수를 `DIALECT_VARIANT_DDL` 한 건으로 알린다. 방언과 상관없이 같은 테이블이 여러 파일에 있으면(설치 스크립트와 덤프 등) 경로순 첫 파일을 쓰고 무엇을 버렸는지 `DUPLICATE_OBJECT_NAME`(INFO)으로 알린다. 매퍼와 DDL에서 고른 방언이 서로 다르면 힌트에 `경고:` 한 줄이 나온다. 방언별 여러 벌은 아니지만 SQL이 mysql 같은 대상 외 방언으로 보이면 `DIALECT_NOT_TARGET`(INFO)과 힌트 한 줄로 알린다. 원하는 DDL만 쓰려면 `--ddl <그 파일 또는 폴더>`로 주고 나머지 DDL 폴더는 `--exclude`로 뺀다. 데이터 적재 묶음은 테이블마다 고른다: 고른 방언의 파일에 그 테이블의 적재가 있으면 그것만 쓰고, 없으면 순위가 가장 앞선 다른 방언 파일의 묶음을 쓴다(구문에 `borrowedFrom=<방언>`이 붙고 요약에 "다른 방언 파일의 것을 씀"으로 수가 나온다. DDL에 정의가 없는 테이블의 묶음은 가져오지 않는다). 고르지 않은 방언의 파일에 든 그 밖의 DML 구문은 만들지 않는다. `upgrade`·`patch`·`migration` 폴더의 `ALTER TABLE`은 이미 반영된 변경으로 보고 적용하지 않는다(`DDL_NOT_APPLIED`). 다만 그 스크립트의 외래 키 추가는 대상 테이블·컬럼이 현재 정의에 있으면 관계로 넣고, 현재 정의가 외래 키를 적는 스키마인데 그 외래 키만 없으면 추론(점선, `upgrade 스크립트, 현재 정의에 없음`)으로 표시한다. 대상 방언의 파일이 하나도 없는 프로젝트에서는 매퍼와 DDL이 같은 순서로 고른다: 매퍼와 SQL 스크립트 양쪽의 경로에 다 있는 방언이 먼저고 그 안에서 방언 이름의 사전순이다(mysql·postgres 두 벌이면 둘 다 mysql). 방언 이름이 경로에 없는 스크립트가 있거나 한쪽에서만 여러 벌인 방언이 있으면 서로 다른 방언을 고를 수 있다. 그때는 `경고:` 힌트를 보고 `--opt mybatis.dialectVariant=<방언>`으로 맞춘다.
- **데이터 적재 스크립트**: 데이터 적재 파일(한 테이블에 넣는 리터럴 INSERT가 10건 이상인 묶음이 하나라도 있는 SQL 스크립트, 또는 리터럴 INSERT와 `COMMIT`·`SET` 같은 명령, 조건 없는 `DELETE`·`TRUNCATE`뿐이고 INSERT가 10건 이상인 스크립트)의 리터럴 INSERT는 구문마다 노드를 만들지 않고 (파일, 테이블)마다 하나로 묶는다(`DATA_LOAD_GROUPED`. 그 파일에서는 1건짜리도 묶는다). 그 파일의 조건 없는 `DELETE`·`TRUNCATE`(초기화 구문)도 적재 구문으로 표시해 함께 뺀다. DDL이나 다른 DML과 섞인 소량의 INSERT는 묶지 않는다. 묶음은 `report.md`의 "데이터 적재 스크립트" 절에 파일·테이블·행 수로 요약되고(고른 방언의 파일에 그 테이블의 적재가 없어 다른 방언 파일의 묶음을 쓴 것은 묶음 수를 한 줄로 적고 파일 이름 뒤에 `(다른 방언 파일: mysql)`처럼 밝힌다. `impact --include-data-load`와 콜트리의 구문 줄에도 같은 표시가 붙는다) CRUD 매트릭스와 콜트리에서는 기본으로 빠진다. 수십만 건짜리 덤프도 이 방식으로 지나가지만, 분석할 필요가 없는 데이터 폴더는 `--exclude 'db/data/**'`처럼 빼는 편이 빠르다.
- **테스트 코드**: 기본으로 `src/test`도 분석한다. 테스트는 모의 객체·테스트 라이브러리 호출이 많아 미해결 호출의 대부분을 차지하고, 인터페이스의 테스트용 구현이 콜트리의 `[impl]` 줄로 섞인다. `--exclude-tests`를 주면 어느 깊이에 있든 `src/test` 폴더를 뺀다. 위 템플릿에서는 이 옵션 하나로 미해결 엣지가 755개에서 81개로, 진단이 769건에서 95건으로 줄었다.
- **벤더 스크립트**: `node_modules`·`webjars`, 정적 자원 폴더 아래의 `lib`·`plugins`, jQuery·CKEditor 같은 알려진 라이브러리의 파일과 폴더, 빌드 산출물로 보이는 큰 생성물, 머리 주석에 정식 라이선스 문구나 `@license`가 있는 스크립트는 내용을 분석하지 않고 건너뛴다(`FILE_SKIPPED`. 폴더마다 한 건으로 요약하고 메시지에 판정 근거를 적는다: `벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): <폴더>`). 라이선스 주석만으로 걸린 파일은 그 폴더에서 혼자만 걸렸거나 프로젝트의 다른 스크립트가 그 파일의 최상위 함수를 5개 이상 부르면 프로젝트 파일로 보고 분석한다. 압축된(minified) 스크립트도 건너뛴다(이름이 아니라 내용으로 판정한다). 프로젝트가 직접 쓴 파일이 걸렸으면 `--opt 'web.vendorPatterns=!static/js/common/**'`처럼 "벤더가 아니다"라고 알리고, 반대로 벤더로 볼 경로는 `!` 없이 더한다. 전부 분석하려면 `--opt web.skipVendor=false`.
- **`--classpath`**: 의존 jar(또는 컴파일된 클래스 폴더)를 주면 라이브러리 타입과 그 반환값에 이어 부르는 호출이 해석되어 미해결(`java-call`, `java-type`)이 줄고, 라이브러리 상위 타입을 거친 재정의·호출이 추론(`~`) 대신 확정으로 나온다. 주지 않아도 프로젝트 안의 호출은 이어지고, 프로젝트 폴더 안에 있는 jar(`WEB-INF/lib/*.jar` 등)는 따로 주지 않아도 쓴다. 폴더를 주면 클래스 폴더로 쓰면서 그 바로 아래의 jar도 함께 쓴다(`--classpath lib`, `--classpath 'lib/*'` 둘 다 된다. 하위 폴더까지 내려가지는 않는다). 여러 곳이면 여러 번 주거나 경로 구분자로 잇는다.
- **메모리**: 그래프 전체를 메모리에 올린다. 힙이 모자라면 스택 대신 어느 단계에서 무엇을 처리하다 모자랐는지와 대처 방법을 알리고 종료 코드 3으로 끝난다. 큰 데이터 스크립트를 `--exclude`로 빼거나 `JAVA_OPTS=-Xmx8g bin/codeatlas analyze ...`로 힙을 늘려 다시 돌린다(`java -jar`로 직접 실행할 때는 `java -Xmx8g -jar codeatlas.jar ...`). 아래는 힙을 일부러 24MB로 줄여 돌렸을 때의 출력이다.

  ```text
  오류: 메모리 부족: 분석기 'java' 실행 중 힙이 모자랐다(처리 중: src/main/java/egovframework/com/config/EgovConfigWebDispatcherServlet.java). 큰 데이터 스크립트(INSERT 덤프 등)는 --exclude로 제외하거나 JVM 옵션 -Xmx로 힙을 늘려 다시 실행한다(예: -Xmx8g).
    JVM 옵션은 JAVA_OPTS 환경 변수로 준다. 예: JAVA_OPTS=-Xmx8g codeatlas analyze <projectDir> (java -jar로 실행하면 java -Xmx8g -jar codeatlas.jar ...)
  ```
- **산출물이 커질 때**: 엔드포인트가 50개를 넘으면 엔드포인트 콜트리가 컨트롤러별 파일로 나뉘고, ERD와 클래스 다이어그램은 Mermaid가 그릴 수 있는 크기(다이어그램 하나에 45,000자)를 넘으면 나뉘거나 멤버가 줄어든다. 전자정부 공통컴포넌트(엔드포인트 1,202개, 테이블 190개)에서 `calltree/endpoints/`에 컨트롤러별 파일 166개(합쳐 3.8MB)와 `erd/index.md`·`erd-overview.mmd`·영역별 ERD 11개(관계로 묶은 영역 9개와 기타 2개. 허브 테이블은 공통코드 `COMTCCMMNDETAILCODE`)가 나오는 것을 확인했다.

## 결과 읽는 법

- **신뢰도**: 모든 관계(엣지)에는 신뢰도가 있다. `RESOLVED`는 소스에서 확정한 관계, `INFERRED`는 추론한 관계(JOIN 조건에서 추론한 테이블 관계, 후보가 여럿이라 확정하지 못한 호출 등), `UNRESOLVED`는 대상을 해석하지 못한 참조다. 콜트리에서는 `~`(추론)와 `?`(미해결)로, ERD에서는 점선(추론)으로 구분한다. 추론 엣지에는 까닭이 속성으로 붙는다(`graph.json`과 뷰어의 상세에서 본다). 자주 보는 것:
  - `ambiguous`: 한 지점의 후보를 가리지 못해 둘 이상으로 이었다(오버로드, 후보 엔드포인트, 같은 경로의 JSP). 후보가 하나뿐이면 붙지 않는다. 콜트리의 `[ambiguous n]`이다.
  - `crossModule`: 요청을 낸 파일의 모듈에는 그 엔드포인트의 선언(핸들러, view-controller)이 없고 다른 모듈에만 있다. 같은 모듈에 선언이 있으면 그것으로만 잇는다. 요청 모듈과 대상 모듈이 둘 다 웹앱(web.xml이 있거나 war 패키징이거나 `@SpringBootApplication`이 있는 모듈)이면 다른 URL로 보고 잇지 않으며 미해결로 남긴다. 웹앱이 아닌 모듈(정적 UI 모듈)의 페이지에서 나간 요청은, 그 페이지를 그리는 핸들러가 모두 한 모듈에 있으면 그 모듈의 선언으로만 잇는다(여러 모듈이 선언한 `GET /` 같은 엔드포인트에는 `module`도 남는다).
  - `dynamicSegment`: 요청 URL의 알 수 없는 조각(`{*}`)이 엔드포인트의 고정 조각과 맞았다. 뷰 이름에서는 동적인 디렉터리 자리에 실제 폴더를 넣어 찾은 JSP다. 그 자리가 프로퍼티에서 온 값이면(`"/" + props.get("app.name") + "/login.html"`) 컨트롤러 모듈의 프로퍼티 값으로, 값을 모르면 컨트롤러 모듈의 폴더 이름과 같은 폴더로 후보를 좁힌다(`narrowedBy`가 `property`·`moduleName`). 근거가 없으면 후보(5개까지)를 모두 남긴다.
  - `prefixMatch`, `viaArgument`, `selfSubmit`, `loadUnverified`: 구문 ID의 접두만 맞음, 구문 ID 문자열을 라이브러리 메서드에 인자로 넘김, action 없는 폼을 그 화면의 주소로 이음, 함께 싣는 근거 없이 프로젝트에 하나뿐인 전역 함수로 이음.
  - 테이블 관계의 `directionKnown=false`는 어느 쪽이 부모인지 정하지 못한 추론 관계다(ERD에서 카디널리티 없는 선). 조인만으로는 방향을 몰라 DDL의 외래 키 방향을 따른 관계는 방향만 그리고 카디널리티는 모름으로 적는다.
- **미해결은 누락이 아니다**: 해석하지 못한 참조는 버리지 않고 `UNRESOLVED` 노드로 남긴다. `report.md`의 "미해결 참조" 절에 분류별(`java-call`, `http-url` 등)로 참조한 곳과 위치가 나온다. 같은 지점에서 다른 방법(구문 ID 접두 일치 등)으로 풀린 참조는 이 절의 목록·건수와 힌트에서 뺀다. 그래프의 수(요약과 `report.md`의 종류별 노드·엣지 수, 신뢰도별 `UNRESOLVED` 수)는 그래프 그대로 세므로 두 수가 다를 수 있고, 그때는 다른 곳마다 까닭이 한 줄 붙는다(`report.md`: "신뢰도가 `UNRESOLVED`인 엣지 n건 가운데 m건은 … 접두 일치로 풀려 여기서 뺐다", 요약: 신뢰도 표 아래, `diagnostics.md`: 머리 한 줄과 그 진단 메시지 끝의 `(접두 일치로 풀림)`). 추측으로 관계를 만들지 않으므로, 이 목록이 사람이 직접 확인해야 할 지점이다. 해석하지 못한 Java 호출은 인자를 적지 않고 `chunkContext.getStepContext().getJobParameters(…)`처럼 나오고(인자 수는 그래프의 엣지 속성 `argCount`에 있다), JSP 인라인 스크립트의 요청 URL은 JSP에 쓴 그대로(`'${ctx}/order/history.do'`) 나온다.
- **조용히 빠뜨리지 않고 알리는 것**: 대상을 정하지 못해 기록하지 않은 것은 건수를 진단으로 남긴다. `EXTERNAL_CALLS_SKIPPED`는 설정에 따라 기록하지 않은 라이브러리·JDK 호출 수, `CALLS_SKIPPED`는 매개변수나 props로 받은 함수라서 대상을 알 수 없는 JavaScript 호출 수(파일마다 1건, 줄 번호 포함)다. 테이블을 알 수 없는 동적 SQL은 `report.md`의 "대상 테이블을 정적으로 알 수 없는 구문"에, 실행하는 메서드는 있지만 그 메서드를 부르는 곳이 없는 매퍼 구문은 "Java 호출자가 없는 매퍼 구문"에 나온다.
- **진단**: `diagnostics.md`에 심각도별로 나온다. `ERROR`는 파일이나 구문을 읽지 못한 것(`PARSE_FAILED`), `WARN`은 일부만 분석했거나 참조를 해석하지 못한 것(`UNRESOLVED_REFERENCE`, 요청한 방언의 파일이 없어 다른 방언으로 대체한 `DIALECT_NOT_FOUND`), `INFO`는 참고 사항(`DYNAMIC_SQL`, `EXTERNAL_CALLS_SKIPPED`, `CALLS_SKIPPED`, SQL이 DDL에 없는 컬럼을 참조하는데 소속 테이블을 정하지 못한 `UNKNOWN_COLUMN`, 그리고 무엇을 골랐거나 건너뛰었는지 알리는 `DIALECT_VARIANT_MAPPERS`, `DIALECT_VARIANT_DDL`, `DIALECT_NOT_TARGET`, `DUPLICATE_OBJECT_NAME`, `DATA_LOAD_GROUPED`, `DDL_NOT_APPLIED`, `FILE_SKIPPED`, `REFLECTION_CALL`)이다. 방언 요약 두 가지는 대상 방언의 파일이 없어 그 밖의 방언을 썼을 때 `WARN`이다. 파일 하나의 실패가 전체 분석을 멈추지 않으며, 진단이 있어도 종료 코드는 0이다.
- **DDL에 없는 테이블**: DDL에 정의가 없고 SQL에서만 참조된 테이블은 그래프에서 `declared=false`다. ERD와 테이블 명세에 "DDL 없음" 표식과 함께 나오고, 컬럼은 SQL에서 참조된 것만 보인다. `--ddl`을 빠뜨렸거나 다른 스키마의 테이블일 때 생긴다. `report.md`의 "DDL에 없는 테이블"은 테이블마다 참조하는 구문 수를 적고, 구문 하나에서만 나온 테이블은 "의심"으로 표시한다(오타이거나 구문을 끝까지 읽지 못해 생긴 이름일 수 있다). DB가 제공하는 사전 뷰·카탈로그 테이블(`USER_TABLES`, `SYSCAT.TABLES`, ``information_schema.`TABLES` `` 등. 사전 스키마로 한정한 참조는 이름과 상관없이 해당한다)은 "시스템 카탈로그"로 따로 묶고 의심으로 치지 않으며, ERD에는 그리지 않고 테이블 명세에는 이름만 남긴다. DDL에 정의가 있는 테이블인데 SQL이 DDL에 없는 컬럼을 참조하면 그 컬럼은 ERD에 넣지 않고 테이블 명세와 `report.md`의 "SQL과 DDL의 컬럼 불일치"에 따로 적는다.

## 알려진 한계

정적 분석으로 알 수 없는 것은 채우지 않는다. 자세한 것은 각 기능 spec의 "가정"과 "알려진 한계" 절을 본다([Java 호출](docs/specs/F-003-java-call-graph/spec.md), [SQL](docs/specs/F-006-sql-parser/spec.md), [MyBatis](docs/specs/F-009-mybatis/spec.md), [JavaScript](docs/specs/F-010-javascript/spec.md), [JSP](docs/specs/F-011-jsp/spec.md)).

- **동적 SQL**: MyBatis의 `${...}` 치환이 남은 구문은 그 자리의 이름(테이블, 정렬 컬럼 등)을 알 수 없다(`DYNAMIC_SQL` 진단). `<if>`, `<choose>` 같은 동적 태그는 조건을 평가하지 않고 전개한다(`<if>` 본문을 모두 포함하고 `<choose>`는 갈래마다 따로 본다). 조건이 `type == 'A'`처럼 값 비교인 `<if>`는 값마다 따로 전개해 서로 다른 값의 본문이 한 문장에 섞이지 않게 한다. 한 구문의 전개는 16개까지라 그보다 많은 값·갈래의 본문은 빠지고 진단이 남는다.
- **리플렉션과 생성 코드**: 리플렉션은 `x.getClass().getDeclaredMethod("이름", ..)`의 결과를 같은 메서드에서 `invoke(x, ..)`하는 한 가지 형태만 추론으로 잇고(`[reflect]`), 그 밖의 `Method.invoke`는 건수만 `REFLECTION_CALL` 진단으로 남긴다. `Class.forName`, 프록시, 유틸리티로 감싼 리플렉션과 암묵적 `super()` 호출은 호출 관계에 없다. Lombok이 만들 멤버(getter, setter, 생성자, 빌더, 로그 필드)는 애너테이션을 보고 합성해 잇지만, 지원하지 않는 애너테이션(`@Delegate` 등)과 다른 코드 생성기가 만드는 메서드는 없다. 인터페이스 호출은 인터페이스 메서드로 잇고 구현은 콜트리에서 `[impl]`로 펼친다.
- **주입과 콜백**: 콜트리는 인터페이스 호출의 구현을 호출하는 클래스의 주입 정보(qualifier, 필드명, `@Primary`로 고른 후보)로 좁히고, 주입 정보가 없으면 구현을 전부 펼친다. React에서 props로 받은 함수의 호출은 "함수 컴포넌트가 직접 호출하고 부모가 JSX 속성으로 프로젝트 함수를 넘긴" 한 가지 형태만 추론으로 잇고(`~`), 그 밖의 콜백 호출은 건수만 `CALLS_SKIPPED`로 남긴다.
- **의존 jar가 없을 때**: `--classpath`를 주지 않아도 프로젝트 안의 호출은 이어진다. 라이브러리 타입은 import 문으로 이름을 복원하고, 복원하지 못한 타입·호출은 미해결(`java-type`, `java-call`)로 남는다. jar를 주면 미해결이 줄어든다.
- **JSP 스크립틀릿**: `<% ... %>` 안의 Java 코드는 분석하지 않는다(개수만 기록한다).
- **화면 스크립트의 전역 함수**: 다른 스크립트 파일의 전역 함수 호출은 두 스크립트를 함께 싣는 페이지가 확인될 때 잇는다(같은 페이지의 `<script src>`, include로 이어진 페이지, SiteMesh 데코레이터와 Tiles 정의로 묶인 레이아웃과 내용 페이지). 그런 근거가 없으면 그 이름의 함수 정의가 프로젝트에 하나뿐일 때만 추론으로 잇고(`~`, `(로드 미확인)`), 정의가 여럿이면 잇지 않고 건수를 `CALLS_SKIPPED`에 더한다. Java 설정으로 만든 SiteMesh·Tiles 구성, 일부 경로에만 걸린 SiteMesh 패턴, 스크립트를 동적으로 싣는 로더는 근거로 읽지 못한다.
- **화면 요청**: `action` 없는 폼은 그 폼을 제출하는 흔적(막지 않는 제출 버튼, 그 폼을 가리키는 `.submit()`)이 그 페이지에 있고 그 페이지를 직접 그리는 핸들러가 있을 때만 그 주소의 엔드포인트로 잇는다(include되는 조각의 폼은 미해결로 남고, 다른 JS 파일의 함수만 제출하는 폼에는 요청이 없다). 스크립트가 문자열로 만든 태그(`'<iframe src="…">'`, `'<a href="…">'`)의 URL은 그 문자열을 DOM에 넣는 자리(`.html()`, `.append()`, `innerHTML`, `document.write()`, `$()`)에서만 요청으로 읽는다. React Router 등의 클라이언트 라우트는 라우터 패키지를 import한 파일의 이동과 `<Route path>`로 선언한 경로만 알아본다(객체로 선언한 라우트는 읽지 못해 서버 요청으로 남는다). 한 호출 지점의 URL과 메서드가 여럿으로 갈리면 짝을 모르는 요청은 `POST|PUT /x`처럼 메서드 후보로 남는다.
- **설정 파일의 SQL**: `.properties` 파일의 값이 SQL 구문 꼴(`SELECT … FROM`, `INSERT INTO`, `UPDATE … SET`, `DELETE FROM`, `MERGE INTO`, `WITH … AS (`, `{call …}`)이면 구문으로 읽어 영향도와 CRUD 매트릭스에 넣는다(구문 이름은 `<파일>:<키의 줄>`이고 `find <키>`로 찾는다). 누가 실행하는지는 알 수 없어 호출 사슬은 이어지지 않는다. 메시지 번들(이름에 `message`·`i18n`·`label`이 들었거나 `Resources`·로케일 접미사로 끝나는 파일)과 `SELECT 1 FROM DUAL` 같은 연결 확인 쿼리는 읽지 않는다. `.yml`의 값과 Java 문자열에 든 SQL은 읽지 않는다.
- **프로퍼티 값**: `${adminPath}` 같은 자리표시자는 프로젝트의 properties·yml 파일에 값이 하나로 정해질 때만 푼다. 실행 시점의 시스템 프로퍼티, 환경 변수, 프로파일 선택은 알 수 없다.
- **여러 모듈**: 엔드포인트와 스프링 빈은 이름(메서드와 경로, 빈 이름)이 같으면 모듈이 달라도 한 노드다. 콜트리는 요청을 낸 파일과 같은 모듈의 핸들러·화면을 골라 따라가지만 그래프의 노드는 나뉘지 않는다. 서로 다른 웹앱 모듈 사이의 같은 경로는 다른 URL로 보고 잇지 않으므로, 게이트웨이나 프록시로 한 주소 아래 묶인 여러 war 사이의 링크는 미해결로 남는다.
- **JavaScript**: 타입 정보가 없어 수신자가 무엇인지 알 수 없는 `x.foo()` 호출은 기록하지 않는다. URL을 만드는 함수 호출은 따라가지 않아 요청 URL의 일부가 `{*}`가 될 수 있다. Vue 단일 파일 컴포넌트(`.vue`)는 다루지 않는다.
- **지원 OS**: JS·TS 파서의 네이티브 라이브러리가 Linux(glibc) x86_64·aarch64, macOS x86_64·aarch64, Windows x86_64용만 들어 있다. 그 밖의 환경에서는 JS·TS 분석만 건너뛰고 `PARSE_FAILED` 진단을 남긴다.
- **SQL**: 대상 DB는 Oracle과 DB2다. Oracle 복합 트리거, `MODEL` 절, `MATCH_RECOGNIZE`는 지원하지 않는다. MySQL 등 그 밖의 방언은 "멈추지 않고 틀린 테이블을 만들지 않는" 수준으로만 읽는다(백틱 이름, `LIMIT`, `AUTO_INCREMENT` 같은 표기는 넘기지만 그 방언 고유의 구문을 분석하지는 않는다).
- **범위 밖**: iBatis 2.x(sqlMap), JPA 등 Spring·MyBatis 외의 프레임워크는 다루지 않는다.

## 문서

- 무엇을 왜 만드는가: [docs/prd.md](docs/prd.md)
- 기능 목록: [docs/features.md](docs/features.md), 기능별 명세: `docs/specs/F-xxx-이름/`
- 그래프 계약(노드·엣지 종류, ID 규칙)과 CLI 설계: [docs/specs/F-001-analysis-pipeline/design.md](docs/specs/F-001-analysis-pipeline/design.md)
- 구조: [docs/architecture.md](docs/architecture.md), 결정 기록: `docs/decisions/`
- 검증용 샘플 프로젝트: [samples/sample-shop](samples/sample-shop/README.md)
- 개발 규칙: [AGENTS.md](AGENTS.md)
