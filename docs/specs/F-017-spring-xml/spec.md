---
type: spec
id: F-017
title: Spring XML 설정 해석
status: implemented  # draft | approved | implemented
updated: 2026-10-11
---

# F-017 Spring XML 설정 해석

## 1. 배경
현장의 Spring 프로젝트(전자정부프레임워크 스타일 포함)는 빈 정의, 주입, 뷰 리졸버, MyBatis 연결, Spring Batch 잡 구성을 XML에 둔다. 이 정보가 없으면 Java 콜 그래프가 인터페이스 주입 지점과 배치 스텝 경계에서 끊기고, 배치 스텝이 실행하는 SQL(매퍼 구문 ID, 인라인 SQL)을 테이블까지 이을 수 없다. PRD 목표 "IDE 수준의 콜트리와 관계 분석"에 필요하다.

## 2. 범위
- 포함: `beans` 루트 XML의 빈 정의와 주입, alias, import, component-scan, util 컬렉션, parent 상속, MyBatis 관련 빈 속성, Spring Batch XML(job, step, flow, split, decision, tasklet, chunk, listener, 전이), 배치 리더·라이터 빈이 실행하는 SQL, `web.xml`의 DispatcherServlet 매핑.
- 제외: `<aop:config>`(진단만. 2026-10-11: `<mvc:view-controller>`의 엔드포인트 생성은 F-017-R16으로 범위에 들어왔다), autowire 속성에 따른 주입 추론, 프로퍼티 플레이스홀더 값 치환, 프로파일별 컨텍스트 구분, Java 타입 노드 정의(codeatlas-java 담당), 애너테이션 빈과의 연결(링커 담당).

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-017-R1 | 시스템은 `<bean>` 정의마다 `SPRING_BEAN` 노드(`spring:bean:<이름>`, 이름이 없으면 `<FQN>#<순번>`)와 `DEFINES_BEAN`(TYPE→BEAN) 엣지를 만들고 `class`, `scope`, 문자열 프로퍼티(`property.<이름>`, p: 네임스페이스 포함)를 attrs에 남겨야 한다. | 사용자 요청 |
| F-017-R2 | 시스템은 XML 주입(`property`/`constructor-arg`의 ref, p:·c: 의 `-ref`, 내부 빈, 컬렉션 안의 ref, `idref`, `lookup-method`)마다 `INJECTS`(주입받는 빈의 클래스→참조된 빈의 클래스, `via=xml`, `property`, `qualifier`)를 만들어야 하고 `depends-on`으로는 만들지 않아야 한다. | 사용자 요청 |
| F-017-R3 | 만약 참조된 빈의 클래스를 프로젝트의 XML에서 알 수 없으면, 시스템은 `INJECTS`의 대상을 `spring:bean:<이름>`으로 두고 `unresolvedBeanRef=true`를 붙여야 한다. | 사용자 요청 |
| F-017-R4 | 시스템은 `parent=` 상속(클래스·프로퍼티 병합), `<alias>`, `<util:list\|map\|set\|properties>`를 해석하고 `<import>`와 `<context:component-scan>`을 FILE 노드 attrs(`imports`, `componentScan`)로 남기며 `${...}`·`#{...}` 값은 원문 그대로 두어야 한다. | 사용자 요청 |
| F-017-R5 | 시스템은 MyBatis 연결 설정(`SqlSessionFactoryBean`, `MapperScannerConfigurer`, `<mybatis:scan>`)을 attrs로 남기고 `MapperFactoryBean`을 참조하는 주입의 대상을 `mapperInterface` 타입으로 해석해야 한다. | 사용자 요청 |
| F-017-R6 | `<aop:config>`를 만나면(2026-10-11 고쳐 씀: 전에는 `<mvc:view-controller>`도 여기에 들었다. 지금은 F-017-R16이 엔드포인트로 만든다), 시스템은 그래프 요소를 만들지 않고 `INFO` 진단 `UNSUPPORTED_SYNTAX`를 남겨야 하며, `<mvc:interceptors>` 같은 확장 요소 안의 `<bean>`은 일반 빈으로 처리해야 한다. | 사용자 요청 |
| F-017-R7 | 시스템은 Spring Batch 네임스페이스(접두사 무관, 기본 네임스페이스 포함)의 `job`, `step`, `decision`, `flow`, `split`을 `BATCH_JOB`·`BATCH_STEP` 노드와 `CONTAINS`(JOB→STEP)로, `next` 속성과 `<next>`·`<stop restart>` 전이를 `NEXT`(`attrs.on`)로 만들어야 한다. | 사용자 요청 |
| F-017-R8 | 시스템은 스텝의 tasklet, chunk(reader·processor·writer), listener를 `USES`(`attrs.role`)로 만들고, 대상은 참조한 빈의 클래스를 XML에서 알면 `java:type`, 모르면 `spring:bean`으로 두어야 한다. | 사용자 요청 |
| F-017-R9 | 시스템은 잡 밖의 독립 `<step>`을 `batch:step:?/<스텝>`으로 만들고, `parent`로 그것을 재사용한 스텝과 잡에 부모의 구성을 병합해야 한다. | 사용자 요청 |
| F-017-R10 | 스텝이 쓰는 빈이 MyBatis 리더·라이터(`queryId`, `statementId`), JDBC 리더·라이터(`sql`), `StoredProcedureItemReader`(`procedureName`)이면, 시스템은 `EXECUTES`(STEP→구문 또는 루틴)를 만들고 인라인 SQL은 `SQL_STATEMENT` 노드와 `sql` 내장 소스로 등록해야 한다. | 사용자 요청 |
| F-017-R11 | 시스템은 루트가 `web-app`인 XML에서 DispatcherServlet의 url-pattern과 `contextConfigLocation`을 FILE 노드 attrs로 남기고 엣지는 만들지 않아야 한다. | 사용자 요청 |
| F-017-R12 | 만약 XML 파일을 파싱하지 못하면, 시스템은 `PARSE_FAILED` 진단을 남기고 나머지 파일을 계속 분석해야 하며, 외부 DTD·XSD·엔티티를 읽지 않아야 한다. | AGENTS.md, PRD 가정(폐쇄망) |
| F-017-R13 | 이름이 같은 빈 정의가 여럿이면, 시스템은 `spring:bean:<이름>` 노드의 attrs와 위치를 main 쪽(테스트 소스 `src/test/`가 아닌 것)·파일 경로순 첫 정의의 것으로 두고 나머지 정의를 `attrs.otherDefinitions`(List: `<파일>:<줄> <class>`)로만 남겨야 한다. `DUPLICATE_OBJECT_NAME`은 같은 모듈 루트(가장 가까운 조상의 `pom.xml`·`build.gradle`·`build.gradle.kts` 디렉터리)의 같은 쪽(main끼리, test끼리)에서 겹친 정의에만 남긴다. 참조를 풀 때는 같은 파일의 정의 다음에, 테스트 설정의 정의는 테스트 설정에서만 후보로 쓰고 참조한 파일과 같은 모듈의 정의가 있으면 그것만 본다. | 실전 검증 2차 후속(zheng: 거짓 중복 경고 23건) |
| F-017-R14 | `<beans>` 바로 아래의 확장 네임스페이스 요소에 `id`가 있으면(`<jdbc:embedded-database id>`, `<jee:jndi-lookup id>`, `<dubbo:reference id>` 등), 시스템은 그 이름의 `SPRING_BEAN`을 등록하고 `attrs.element`에 요소 이름(`<네임스페이스 URI의 마지막 조각>:<로컬 이름>`)을 남겨야 한다. `class`는 알려진 요소의 대표 타입이고 모르면 비운다. | 실전 검증 2차 후속 |
| F-017-R15 | 시스템은 Spring XML과 web.xml 후보 파일 하나를 읽기 시작할 때 그 상대 경로를 `AnalysisContext.progress`로 알려야 한다. | F-001 (메모리 부족 안내) |
| F-017-R16 | `<mvc:view-controller path view-name>`을 만나면, 시스템은 `HTTP_ENDPOINT`(`GET <path>`, `attrs.viewController=true`)를 만들고 `view-name`을 `attrs.viewNames`(List, 중복 없이)에, 선언마다의 자리와 뷰 이름을 `attrs.viewDeclarations`(List: `<파일>:<줄>:<열> <view-name>`)에 남겨야 한다(같은 경로를 여러 설정 파일이 선언하면 한 노드에 쌓인다). 핸들러 메서드가 없으므로 `ROUTES_TO`는 만들지 않는다(엔드포인트 → JSP의 `RENDERS`는 링커가 만든다, F-012-R34). `path`가 없으면 만들지 않는다. (F-017-R6의 "`<mvc:view-controller>`는 진단만"을 바꾼다) | 실전 재검증 2차 후속(2026-10-11)(N27) |
| F-017-R17 | 시스템은 XML의 줄 끝을 LF, CRLF, CR 단독 셋 다 한 줄로 세야 하며(CRLF는 한 번), 빈·스텝 노드와 엣지의 위치(시작·끝 줄과 열, 속성 이름의 위치)와 `PARSE_FAILED`의 줄이 줄 끝의 종류와 상관없이 같아야 한다. | 대상 방언 점검(다른 모듈은 CR 단독을 줄로 센다) |

## 4. 수용 기준

### F-017-AC1 (대상: F-017-R1)
- Given `<bean id="viewResolver" class="...InternalResourceViewResolver" p:order="1">`에 `prefix`, `suffix` 프로퍼티가 있고, id 없는 `<bean class="com.a.Foo"/>`가 둘 있는 XML
- When 분석하면
- Then `spring:bean:viewResolver`에 `class`, `property.prefix`, `property.suffix`, `property.order`가 있고 위치가 `<bean` 줄이며, `DEFINES_BEAN`(java:type→bean)이 있고, 이름 없는 빈은 `com.a.Foo#0`, `com.a.Foo#1`이다.

### F-017-AC2 (대상: F-017-R2)
- Given `property ref`, `<property><ref bean/></property>`, `constructor-arg ref`, `p:x-ref`, `c:x-ref`, 내부 빈, `<list>` 안의 ref, `<idref>`, `<lookup-method>`, `depends-on`을 쓰는 빈
- When 분석하면
- Then 각 주입마다 `INJECTS`(주입받는 클래스→참조된 클래스, `via=xml`, `property`, `qualifier`)가 있고 `depends-on` 대상으로 가는 엣지는 없다.

### F-017-AC3 (대상: F-017-R3)
- Given XML 어디에도 없는 빈 이름(`orderDao`)을 `ref`로 참조하는 빈
- When 분석하면
- Then `INJECTS`의 대상이 `spring:bean:orderDao`이고 `unresolvedBeanRef=true`, `qualifier=orderDao`다.

### F-017-AC4 (대상: F-017-R4)
- Given 추상 부모 빈(프로퍼티 ref 포함)과 `parent=`로 상속한 자식 빈, `<alias>`, `<util:list>`, `<import>`, `<context:component-scan>`, `${...}` 값이 있는 XML 두 개
- When 분석하면
- Then 자식 빈에 부모의 class와 프로퍼티가 병합되고 상속한 주입의 `INJECTS`가 자식 클래스에서 나가며, alias로 참조해도 원래 빈의 클래스로 해석되고, util 컬렉션이 빈으로 잡히며, FILE 노드에 `imports`, `componentScan`이 있고 `${...}` 값이 원문 그대로다.

### F-017-AC5 (대상: F-017-R5)
- Given `SqlSessionFactoryBean`(mapperLocations, configLocation, typeAliasesPackage), `MapperScannerConfigurer`(basePackage), `<mybatis:scan>`, `MapperFactoryBean`(mapperInterface)과 그 매퍼 빈을 주입받는 서비스 빈
- When 분석하면
- Then 빈과 FILE 노드 attrs에 MyBatis 설정이 남고, 서비스의 `INJECTS` 대상이 `mapperInterface`의 타입이다.

### F-017-AC6 (대상: F-017-R6)
- Given `<mvc:view-controller>`, `<aop:config>`, `<mvc:interceptors>` 안의 `<bean>`이 있는 XML
- When 분석하면
- Then `aop:config`에 `INFO` 진단 `UNSUPPORTED_SYNTAX`가 있고 인터셉터 빈이 `SPRING_BEAN`으로 잡힌다. (2026-10-11 고쳐 씀: `<mvc:view-controller>`는 진단 대신 `HTTP_ENDPOINT`가 된다, F-017-AC16)

### F-017-AC7 (대상: F-017-R7)
- Given `next` 속성, `<next on to>`, `<end on>`, `<fail on>`, `<stop on restart>`, `<decision>`, `<split>`, `<flow parent>`를 쓰는 잡 XML(접두사 `batch:`와 기본 네임스페이스 두 형태)
- When 분석하면
- Then `BATCH_JOB`, `BATCH_STEP`(`batch:step:<잡>/<스텝>`), `CONTAINS`(JOB→STEP)이 있고 `NEXT`의 `on`이 전이 조건과 같으며(`next` 속성은 `on` 없음) 결정자는 `stepType=decision`이다.

### F-017-AC8 (대상: F-017-R8)
- Given `<tasklet ref>`, `<chunk reader processor writer>`, `<listeners><listener ref|class>`가 있고 일부 빈만 XML에 정의된 잡
- When 분석하면
- Then `USES`의 `role`이 `tasklet`, `reader`, `processor`, `writer`, `listener`이고, XML에 class가 있는 빈은 `java:type`, 없는 빈은 `spring:bean`이 대상이다.

### F-017-AC9 (대상: F-017-R9)
- Given 잡 밖의 추상 `<step id="baseStep">`(chunk 설정·리스너 포함), 그것을 `parent`로 쓰는 잡 안의 스텝, `<job parent>`
- When 분석하면
- Then `batch:step:?/baseStep`이 있고, 자식 스텝에 부모의 reader·listener `USES`가 병합되며 자식이 준 값이 부모 값을 덮어쓰고, 자식 잡이 부모 잡의 리스너를 물려받는다.

### F-017-AC10 (대상: F-017-R10)
- Given 스텝이 쓰는 `MyBatisPagingItemReader`(queryId), `MyBatisBatchItemWriter`(statementId), `JdbcCursorItemReader`(sql), `JdbcBatchItemWriter`(sql, `:name` 바인드), `StoredProcedureItemReader`(procedureName)와, 어떤 스텝도 쓰지 않는 리더 빈
- When 분석하면
- Then 스텝에서 `mybatis:stmt:<ns>.<id>`, `sql:stmt:<파일>:<줄>`, `db:routine:<이름>`으로 가는 `EXECUTES`가 있고, 인라인 SQL은 `SQL_STATEMENT` 노드와 `sql` 내장 소스(본문 원문 그대로)로 등록되며, 쓰이지 않는 빈에서는 엣지와 구문 노드가 생기지 않는다.

### F-017-AC11 (대상: F-017-R11)
- Given DispatcherServlet을 `*.do`와 `/api/*`에 매핑하고 `contextConfigLocation`을 준 `web.xml`
- When 분석하면
- Then `web.xml`의 FILE 노드에 `servletMappings`, `contextConfigLocations`, `servletContextConfigLocations`가 있고 엣지는 늘지 않는다.

### F-017-AC12 (대상: F-017-R12)
- Given 깨진 XML 하나, 외부 DTD와 외부 엔티티를 선언한 XML 하나, 정상 XML 하나
- When 분석하면
- Then 깨진 파일에 `PARSE_FAILED` 진단이 남고 정상 파일의 빈이 잡히며, 외부 엔티티의 내용이 그래프에 들어오지 않고 DOCTYPE(DTD) 방식 파일도 해석된다.

### F-017-AC13 (대상: F-017-R13)
- Given 모듈 둘(`web-a`, `web-b`, 각각 `pom.xml`)의 main 설정과 `web-a`의 테스트 설정이 모두 `dataSource`를 다른 클래스로 정의하고, 각 모듈의 다른 설정 파일과 테스트 설정이 `ref="dataSource"`로 참조하는 프로젝트. 테스트 설정과 다른 모듈의 main 설정에만 있는 `cache`. 그리고 한 모듈의 main 설정 두 파일과 다른 모듈의 설정이 `notifier`를 정의한 프로젝트
- When 분석하면
- Then 앞의 프로젝트에는 `DUPLICATE_OBJECT_NAME`이 없고, `dataSource` 노드의 `class`·`property.url`·위치는 `web-a` main의 것이며 `otherDefinitions`에 `web-b` main, `web-a` test 순으로 `<파일>:<줄> <class>`가 있다. `DEFINES_BEAN`은 세 타입 모두에서 온다. `cache`는 경로가 뒤여도 main 쪽 정의의 attrs를 갖는다. 참조는 각각 같은 모듈의 main 정의(테스트 설정은 같은 파일의 정의) 타입으로 이어지고 `unresolvedBeanRef`가 없다. 뒤의 프로젝트에는 `WARN` 1건이 `web-a`의 두 파일만 적어 남는다.

### F-017-AC14 (대상: F-017-R14)
- Given `<db:embedded-database id="dataSource">`(접두사 `db`, 네임스페이스 `.../schema/jdbc`), `<jee:jndi-lookup id>`(`expected-type` 있는 것과 없는 것), `<dubbo:reference id interface>`, `<task:executor id>`, 모르는 네임스페이스의 `<x:custom id>`·id 없는 것·id가 `${...}`인 것, 그리고 그 빈들을 `ref`로 주입받는 `<bean>`
- When 분석하면
- Then `dataSource`는 `element=jdbc:embedded-database`, `class=javax.sql.DataSource`인 `SPRING_BEAN`이고 `DEFINES_BEAN`·`CONTAINS`가 있으며 주입은 `java:type:javax.sql.DataSource`로 간다. `dubbo:reference`는 `interface`가 `class`이고, `jndi-lookup`은 `expected-type`이 있으면 그것, 없으면 `class` 없이 `spring:bean:`(`unresolvedBeanRef=true`)으로 참조된다. `x:custom`은 `class` 없는 빈이고, id가 없거나 플레이스홀더인 요소는 빈이 되지 않는다.

### F-017-AC15 (대상: F-017-R15)
- Given Spring XML 둘과 web.xml 하나, `progress` 호출을 기록하는 컨텍스트
- When 분석하면
- Then 세 파일의 상대 경로가 각각 알려지고 Spring XML은 경로순이다.

### F-017-AC16 (대상: F-017-R16)
- Given `<mvc:view-controller path="/" view-name="/index.jsp"/>`, `/`로 시작하지 않는 path, `redirect:` 뷰 이름, `view-name`이 없는 것, `path`가 없는 것
- When 분석하면
- Then `http:endpoint:GET /` 노드가 `viewController=true`, `viewNames=[/index.jsp]`, `viewDeclarations=[<파일>:<줄>:<열> /index.jsp]`, 위치는 그 요소로 선언된다. 두 모듈의 설정 파일이 같은 경로를 선언하면 노드는 하나이고 `viewNames`·`viewDeclarations`에 둘 다 있다. path는 `/`로 시작하게 정규화된다. `view-name`이 없으면 `viewNames`가 없고, `path`가 없는 요소는 엔드포인트를 만들지 않는다. `ROUTES_TO`와 `UNSUPPORTED_SYNTAX` 진단은 없다.

### F-017-AC17 (대상: F-017-R17)
- Given 여러 줄에 걸친 `<bean>`(속성이 다음 줄에 있음), 여러 줄 주석, `p:` 속성 주입, 컬렉션 안의 `<ref>`, 배치 잡·스텝이 있는 Spring XML을 LF, CRLF, CR 단독, 줄마다 섞은 줄 끝으로 쓴 것과, 같은 방식으로 쓴 깨진 XML
- When 분석하면
- Then 모든 노드와 엣지의 위치(시작 줄·열, 끝 줄·열)가 LF일 때와 같고, CR 단독 파일에서 시작 태그의 `<`와 속성 이름의 줄·열이 맞으며, `PARSE_FAILED`의 줄도 같다.

## 5. 가정
- 프로젝트 안의 모든 Spring XML을 하나의 빈 이름 공간으로 본다(컨텍스트 계층, 프로파일을 구분하지 않는다. 노드 ID는 `spring:bean:<이름>` 하나다). 같은 이름이 같은 범위(모듈 루트 + main·test)에서 여러 번 정의되면 `WARN` 진단 `DUPLICATE_OBJECT_NAME`을 남기고(정의가 모두 프로파일 안이면 `INFO`), 참조를 풀 때는 같은 파일의 정의를 먼저 쓰고, 그것이 없으면 F-017-R13대로 좁힌 뒤에도 정의들의 클래스가 서로 다르면 대상을 `spring:bean:<이름>`(`unresolvedBeanRef=true`)으로 둔다.
- 이름이 같은 빈 정의(F-017-R13)의 세부
  - 조상에 빌드 파일이 없는 설정 파일끼리는 한 범위로 본다(빌드 파일이 하나도 없는 프로젝트에서는 전과 같이 전체가 한 범위다). 같은 모듈의 main 설정 둘(루트 컨텍스트와 서블릿 컨텍스트)에 같은 이름이 있는 것은 컨텍스트 계층을 구분하지 않으므로 지금처럼 `WARN`이다.
  - 노드의 attrs를 정하는 첫 정의는 "테스트 소스가 아닌 것 먼저, 그 안에서 파일 경로순·문서 순"이다. 나머지 정의의 `class`·프로퍼티·별칭은 노드에 섞지 않고 `otherDefinitions`에 `<파일>:<줄> <class>`(클래스를 모르면 `<파일>:<줄>`)로만 남긴다. 순서는 같은 기준이다.
  - 정의마다의 엣지(`CONTAINS`, `DEFINES_BEAN`, 그 정의의 주입 `INJECTS`)는 전과 같이 모두 만든다. 링커가 참조하는 쪽의 모듈로 정의를 고를 때(F-012-R26) 이 엣지들을 쓴다.
  - 참조를 풀 때: main 설정의 참조는 테스트 설정의 정의를 보지 않는다(main 쪽 정의가 하나도 없으면 전과 같이 전체를 본다). 테스트 설정의 참조는 main·test 정의를 모두 본다. 참조한 파일의 모듈을 모르면 모듈로 좁히지 않는다.
  - java 분석기가 같은 이름으로 만든 빈(`@Bean`, 스테레오타입)과는 지금처럼 노드 하나로 합쳐진다(그래프의 병합 규칙: 위치는 먼저 정의한 쪽, 같은 키의 attrs는 나중 쪽). `otherDefinitions`와 `DUPLICATE_OBJECT_NAME`은 XML 정의끼리만 본다.
- `id`가 있는 확장 네임스페이스 요소(F-017-R14)의 세부
  - `<beans>`(중첩 `<beans profile>` 포함) 바로 아래의 요소만 본다. 이 분석기가 따로 해석하는 요소(`util:list|set|map|properties`, 배치의 `job`·`step`·`flow`, `context:*`, `mybatis:scan`, `mvc:view-controller`, `aop:config`)는 해당하지 않는다. `aop:config` 안의 `aop:pointcut id`처럼 다른 요소 안에 든 것은 등록하지 않는다.
  - 요소의 내용(속성, 자식)은 해석하지 않는다. 그 안의 `<bean>`은 전과 같이 일반 빈으로 읽고, 요소 종류마다 한 번 남기는 `INFO` 진단 `UNSUPPORTED_SYNTAX`도 그대로다.
  - 대표 타입을 아는 요소: `jdbc:embedded-database` → `javax.sql.DataSource`, `jee:jndi-lookup` → `expected-type` 속성(없으면 모름), `dubbo:reference` → `interface` 속성, `task:executor` → `org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor`, `task:scheduler` → `...ThreadPoolTaskScheduler`, `tx:advice` → `org.springframework.transaction.interceptor.TransactionInterceptor`. 타입을 알면 `DEFINES_BEAN`을 만들고 참조의 대상이 그 `java:type`이 된다. 모르면 util 컬렉션처럼 `spring:bean:<이름>`으로 참조된다.
  - `attrs.element`의 접두사는 소스에 쓴 접두사가 아니라 네임스페이스 URI의 마지막 조각이다(`xmlns:db="http://www.springframework.org/schema/jdbc"`의 `<db:embedded-database>` → `jdbc:embedded-database`). URI가 없으면 쓴 접두사다. util 컬렉션의 `util:list` 등은 전과 같다.
- 이름 없는 빈의 순번(`<FQN>#0`, `#1`)은 프로젝트 전체에서 파일 경로순·문서 순으로 센다. Spring은 컨텍스트별로 세므로 실제 이름과 다를 수 있다.
- 내부 빈은 Spring에서 이름으로 참조할 수 없으므로 `spring:bean:<FQN>#inner@<파일>:<줄>` 이름으로 노드를 만들고 `inner=true`를 붙인다.
- 정적 팩터리(`class` + `factory-method`)의 빈 클래스는 `class` 속성 값으로 본다. `factory-bean` 빈은 클래스를 알 수 없는 것으로 본다.
- 주입받는 쪽의 클래스를 알 수 없으면(`factory-bean` 빈, util 컬렉션) `INJECTS`의 출발을 `spring:bean:<이름>`으로 두고 `unresolvedBeanSource=true`를 붙인다. 클래스 없는 추상 빈(템플릿)에서는 엣지를 만들지 않고 상속한 자식에서 만든다.
- util 컬렉션 빈은 클래스가 프로젝트 타입이 아니므로 `DEFINES_BEAN`을 만들지 않고, 참조할 때 대상은 `spring:bean:<이름>`이다.
- `<end on>`, `<fail on>`은 도착 스텝이 없어 `NEXT` 엣지 대신 스텝 attrs(`endOn`, `failOn`)에 남긴다. `<stop on restart>`는 `restart` 스텝으로 가는 `NEXT`(`transition=stop`)와 attrs `stopOn`을 남긴다.
- 잡 밖 `<flow id>` 안의 스텝은 `batch:step:?/<스텝>`으로 만들고 `attrs.flow`에 흐름 이름을 적는다. 잡 안에서 그 흐름을 참조한 스텝(`stepType=flow`)에서 흐름의 첫 스텝으로 `NEXT`(`transition=flow`)를 잇는다. split은 `stepType=split` 스텝 하나와 각 흐름의 첫 스텝으로 가는 `NEXT`(`transition=split`)로 표현한다.
- 배치 리더·라이터 클래스는 패키지와 상관없이 단순 이름(`MyBatisPagingItemReader` 등)으로 알아본다. `CompositeItemWriter` 같은 위임 빈은 `delegate`, `delegates` 프로퍼티를 따라가 실제 리더·라이터를 찾는다.
- `JdbcPagingItemReader`는 `sql` 프로퍼티가 없으므로 `queryProvider`의 `selectClause`, `fromClause`, `whereClause`를 Spring과 같은 방식으로 이어 붙인 SQL을 쓴다(`composed=true`).
- `procedureName`이 `A.B` 꼴이면 `A`를 패키지로 본다(`db:routine:A.B`). 방언이 DB2로 지정된 경우에만 스키마로 보고 뺀다. 원문은 엣지 `attrs.procedureName`에 남긴다.
- (2026-10-11 폐기) ~~`mvc:view-controller`는 엔드포인트를 만들지 않는다(범위 제외, 진단만).~~ F-017-R16이 엔드포인트로 만든다.
- DispatcherServlet은 `servlet-class`의 단순 이름이 `DispatcherServlet`인 것만 알아본다.
- 파싱에 실패한 파일의 `PARSE_FAILED` 진단은 `ERROR`로 남긴다(그 파일에서는 아무것도 얻지 못하므로). DOCTYPE은 읽지 않고 건너뛰므로 DTD에 선언한 엔티티(`&name;`)를 쓰는 파일은 파싱 실패가 된다.
- `<import>` 대상은 프로젝트의 Spring XML 가운데서 찾는다(`classpath:`·`classpath*:`·`/`로 시작하면 경로 끝이 맞는 파일, 아니면 import한 파일 기준 상대 경로, Ant 패턴 지원). 찾지 못하면 `INFO` 진단 `UNRESOLVED_REFERENCE`를 남긴다.
- 해석하지 않는 확장 네임스페이스 요소(`tx:advice`, `task:*`, `util:constant` 등)는 파일마다 요소 종류별로 한 번 `INFO` 진단 `UNSUPPORTED_SYNTAX`를 남기고 그 안의 `<bean>`만 읽는다. `*:annotation-driven`, `context:annotation-config`, `aop:aspectj-autoproxy`, `mvc:resources`, `mvc:default-servlet-handler`, `batch:job-repository`는 진단 없이 넘긴다.
- 전자정부프레임워크의 `MapperConfigurer`는 `MapperScannerConfigurer`와 같이 다룬다.
- 엣지의 근거 위치는 참조를 적은 요소다. p:·c: 속성과 chunk의 `reader`·`processor`·`writer` 속성은 그 속성의 위치를 쓴다(한 빈을 두 역할로 써도 엣지가 합쳐지지 않게).
- 스텝의 `<listeners>`는 Spring Batch와 같이 부모 스텝의 것을 대체하고, `merge="true"`일 때만 합친다. 자식에 `<listeners>`가 없으면 부모 것을 물려받는다. 잡의 리스너도 같다. 부모 잡의 스텝은 물려받지 않는다.
- 파티션 스텝(`<partition step>`)은 `stepType=partition`이고 실제로 도는 스텝으로 `NEXT`(`transition=partition`)를 잇는다. 잡 스텝(`<job ref>`)은 `stepType=job`, `jobRef`만 남긴다.

- `mvc:view-controller`(F-017-R16): 메서드는 `GET`으로 둔다. 노드의 `name`·`qualifiedName`은 java가 만드는 엔드포인트와 같은 `GET <경로>`다. 같은 경로의 엔드포인트를 java가 이미 만들었으면 한 노드로 합쳐진다. `view-name`이 없을 때 Spring이 경로에서 정하는 기본 뷰 이름은 만들지 않는다. `status-code`만 있는 것과 `<mvc:redirect-view-controller>`·`<mvc:status-controller>`는 다루지 않는다.
- **줄 끝(F-017-R17)**: 시작 태그와 속성 이름의 위치는 본문을 직접 훑어 구한다(`XmlParser.scanTags`). 줄을 끝내는 글자는 XML 파서와 같이 LF와, 뒤에 LF가 없는 CR이다. 끝 위치는 StAX가 주는 줄을 쓰되, JDK의 StAX가 홀로 있는 CR로 끝난 줄 다음(내용 영역에서 시작하는 줄)의 열을 하나 적게 주므로, 요소가 닫히는 자리(끝 태그 또는 빈 요소 `/>`의 `>`)도 같은 훑기에서 문서 순서대로 구해 n번째 END_ELEMENT에 붙인다. 훑어 구한 줄이 StAX의 줄과 다르면(훑기가 어긋난 깨진 문서) StAX의 값을 그대로 쓴다.

## 6. 미확정 사항
- 없음. (사용자가 미확정 사항은 기본값으로 진행하라고 위임했다. 기본값은 5장에 있다.)

## 7. 계약 추가·예외 (design.md 반영 요청)
design.md에 반영됨(2026-10-11): 아래 항목은 모두 F-001 design.md 3.4(엣지 조합과 예외)·3.5(attrs)·3.6(미해결 분류)·3.9에 있다. 이번 감사 오류 수정 라운드에서 이 기능이 더한 제안은 없다. 이 모듈이 `java:type:` 참조로만 만든 타입 가운데 끝내 선언되지 않은 것에는 링커가 `external=true`를 붙인다(F-012-R14, design 3.5).

- `INJECTS`의 대상이 `SPRING_BEAN`일 수 있다(`unresolvedBeanRef=true`). 출발이 `SPRING_BEAN`일 수 있다(`unresolvedBeanSource=true`).
- `USES`의 `role`에 `decider`, `partitioner`를 더했다. 잡 리스너는 `USES`(BATCH_JOB→대상, `role=listener`)로 표현한다.
- `CONTAINS`(FILE→SPRING_BEAN, FILE→BATCH_JOB, FILE→잡 밖 BATCH_STEP)를 만든다. 내부 빈은 제외한다.
- 새 attrs 키
  - SPRING_BEAN: `class`, `scope`, `abstract`, `parent`, `factoryBean`, `factoryMethod`, `initMethod`, `destroyMethod`, `autowire`, `primary`, `aliases`(List), `property.<이름>`(String, 컬렉션이면 List. map·props는 `키=값`), `inner`, `element`(`util:list` 등 bean이 아닌 요소로 정의된 경우. 2차 후속, design.md에 반영됨(2026-10-11, 2차): `id`가 있는 확장 네임스페이스 요소의 `<네임스페이스>:<로컬 이름>`(`jdbc:embedded-database`, `jee:jndi-lookup`, `dubbo:reference` 등)도 이 키에 온다), `otherDefinitions`(List<String>, 2차 후속, design.md에 반영됨(2026-10-11, 2차): 이름이 같은 다른 XML 정의들 `<파일>:<줄> <class>`. 노드의 나머지 attrs와 위치는 main 쪽·경로순 첫 정의의 것이다), `values`(List, util 컬렉션의 문자열 원소), `collectionClass`·`location`(util 요소의 속성), `profile`, `mapperInterface`
  - FILE(SPRING_XML): `imports`(List, 원문), `importedFiles`(List, 프로젝트에서 찾은 상대 경로), `componentScan`(List), `mybatisScan`(List), `mybatisMapperLocations`(List), `mybatisConfigLocation`, `mybatisTypeAliasesPackage`(List), `propertyLocations`(List)
  - FILE(web.xml): `servletMappings`(List, DispatcherServlet의 url-pattern), `dispatcherServlets`(List, 서블릿 이름), `servletContextConfigLocations`(List), `contextConfigLocations`(List)
  - BATCH_JOB: `abstract`, `parent`, `restartable`, `incrementer`
  - BATCH_STEP: `stepType`(`tasklet`, `chunk`, `decision`, `flow`, `split`, `partition`, `job`, `step`), `abstract`, `parent`, `commitInterval`, `flow`, `flowRef`, `jobRef`, `endOn`·`failOn`·`stopOn`(List)
  - INJECTS: `property`, `qualifier`, `unresolvedBeanRef`, `unresolvedBeanSource`, `inheritedFrom`, `idref`, `innerBean`
  - USES: `bean`(참조한 빈 이름), `inheritedFrom`
  - NEXT: `on`, `transition`(`stop`, `flow`, `split`, `partition`)
  - EXECUTES: `role`, `bean`, `procedureName`
  - SQL_STATEMENT(인라인 SQL): `sql`(원문), `bean`, `composed`, `dynamic`(본문에 `#{...}`·`${...}`가 있으면 true)
- 미해결 노드 분류(`unresolved:<분류>:<원문>`): `spring-bean-ref`(플레이스홀더로 준 빈 이름), `mybatis-statement`(네임스페이스가 없거나 플레이스홀더인 구문 ID), `db-routine`(플레이스홀더인 프로시저 이름)
- 인라인 SQL의 내장 소스는 `exactPositions=false`다(속성값·CDATA를 거친 본문이라 호스트 파일의 연속 구간이 아니다). `origin`은 `sql` 프로퍼티 요소의 위치다.

### 7.1 실전 검증 2차 후속
design.md에 반영됨(2026-10-11, 2차): `otherDefinitions`와 `element`의 확장 네임스페이스 요소는 3.5 노드 attrs 표(SPRING_BEAN), `DUPLICATE_OBJECT_NAME`의 좁힌 범위는 3.7, 빈 노드가 이름 하나에 하나라는 한계는 7절에 넣었다. 처리 중인 파일 알리기(F-017-R15)는 4.1에 있다.
- design 3.5 SPRING_BEAN attrs: `otherDefinitions`(List<String>, springxml) 추가, `element`의 설명에 확장 네임스페이스 요소 추가(위 목록).
- design 3.7 `DUPLICATE_OBJECT_NAME`(springxml): "같은 모듈 루트의 같은 쪽(main·test)에서 같은 이름의 빈이 여러 번 정의됨"으로 좁힌다. 다른 모듈·테스트 설정의 같은 이름은 진단하지 않는다.
- design 3.3 ID 규칙은 그대로다(`spring:bean:<이름>` 하나). 모듈별 노드로 나누지 않았다.
- 소비자(export·viewer)가 알아야 할 것: 빈 노드의 `class`는 정의 하나(main 쪽 첫 정의)의 것이다. 멀티모듈 프로젝트에서 다른 모듈의 구현 클래스는 `otherDefinitions`와 `DEFINES_BEAN` 엣지(정의마다 하나, 위치가 그 정의)로 본다.

### 7.2 실전 재검증 2차 후속 (2026-10-11) — design.md 반영 요청
design.md 반영됨(2026-10-11): `HTTP_ENDPOINT`의 만드는 모듈(springxml)은 3.3, `viewController`·`viewNames`·`viewDeclarations`는 3.5 노드 attrs 표, `ROUTES_TO`가 없다는 것과 엔드포인트에서 나가는 `RENDERS`는 3.4에 넣었다.
- design 3.3 `HTTP_ENDPOINT`의 만드는 모듈에 springxml을 더한다(`<mvc:view-controller>`). 3.5 HTTP_ENDPOINT attrs: `viewController`(boolean), `viewNames`(List: view-name), `viewDeclarations`(List: `<파일>:<줄>:<열> <view-name>`. 선언마다 하나. link가 읽어 엔드포인트 → JSP `RENDERS`를 선언마다 만든다). 3.4 `ROUTES_TO` 설명에 "`viewController=true`인 엔드포인트에는 `ROUTES_TO`가 없다"를 더한다(F-017-R16).

## 8. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: 없음
