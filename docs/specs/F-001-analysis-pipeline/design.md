---
type: design
id: F-001
status: approved        # draft | approved
updated: 2026-10-11
---

# F-001 분석 파이프라인과 그래프 모델 설계 (그래프 계약)

이 문서는 모든 모듈이 공유하는 계약이다. 분석기끼리는 서로를 참조하지 않고 이 문서의 노드 ID 규칙으로만 만난다. 계약을 바꿔야 하면 이 문서를 먼저 고치고 영향받는 모듈을 함께 고친다.

## 1. 접근 방식
- `codeatlas-core`가 그래프 모델, 파일 스캐너, SPI, 파이프라인, JSON 저장을 제공한다.
- 분석기(`Analyzer`)는 프로젝트 파일을 읽어 그래프에 노드와 엣지를 쓴다. 링커(`Linker`)는 모든 분석기가 끝난 뒤 그래프만 보고 엣지를 더한다. 출력기(`Exporter`)는 그래프를 읽어 파일을 만든다.
- 구현체는 `java.util.ServiceLoader`(`META-INF/services`)로 찾는다.
- 파이프라인 순서: 스캔 → 분석기 전부(`order()` 오름차순) → 링커 전부(`order()` 오름차순) → 마무리(매달린 엣지 처리) → 저장 → 출력기.
- 다른 언어 안에 박힌 코드(매퍼 XML 안의 SQL, `@Select`의 SQL, XML 빈의 인라인 SQL, JSP 안의 인라인 스크립트)는 **내장 소스**(`EmbeddedSource`, 4.1)로 넘긴다([ADR-0007](../../decisions/0007-pass-embedded-code-via-embedded-sources.md)). 내장 소스를 만드는 분석기(생산자)는 그 언어를 분석하는 분석기(소비자)를 직접 호출하지 않는다. 생산자가 컨텍스트에 등록하면 순서가 뒤인 소비자가 꺼내서 분석한다.
- 표준 `order()`: 생산자 100 (java, springxml, mybatis, jsp) → 소비자 200 (sql은 `sql` 내장 소스와 SQL·DDL 파일, web은 `js` 내장 소스와 JS·TS 파일). 같은 order끼리는 서로 의존하지 않는다.
- 링커의 `order()`(모두 link 모듈): 외부 참조 표시 90(`link-external-reference`) → DB 객체 정규화 100(`link-db-normalize`) → 매퍼 바인딩 200(`link-mapper-binding`) → 뷰 연결 220(`link-view`) → HTTP 연결 225(`link-http-match`) → 빈 참조 풀기 230(`link-bean-reference`) → 주입 후보 240(`link-injection-candidate`) → 테이블 관계 추론 300(`link-relation-inference`). 뒤 단계는 앞 단계의 결과(`external` 표시, 정규화된 테이블·뷰 ID, `RENDERS`, 타입으로 푼 빈 참조)를 쓴다. HTTP 연결은 2차 수정(2026-10-11)에서 210에서 225로 옮겼다: 상대 URL의 기준 디렉터리를 뷰 연결이 만든 `RENDERS`에서 얻는다(F-012-R18). 뷰 연결은 `MATCHES`를 쓰지 않는다. 외부 참조 표시(`link-external-reference`, F-012-R14)는 어느 분석기도 선언하지 않은(`declared=false`) `JAVA_TYPE`·`JAVA_METHOD`·`JAVA_FIELD` 노드에 `external=true`를 붙인다(3.5).

## 2. 모듈과 패키지

| 모듈 | 루트 패키지 | 의존 |
|---|---|---|
| codeatlas-core | `io.codeatlas.core` | (외부: Jackson) |
| codeatlas-java | `io.codeatlas.java` | core (외부: JDT Core). Java 구조, 콜 그래프, 애너테이션 기반 Spring·Spring Batch |
| codeatlas-springxml | `io.codeatlas.springxml` | core. Spring XML 빈 설정, Spring Batch XML 잡 설정 |
| codeatlas-sql | `io.codeatlas.sql` | core. SQL·DDL·프로시저 파서, `sql` 내장 소스 소비 |
| codeatlas-mybatis | `io.codeatlas.mybatis` | core. 매퍼 XML 해석, 동적 SQL 전개, `sql` 내장 소스 생산 |
| codeatlas-web | `io.codeatlas.web` | core (외부: tree-sitter). JS·TS 분석, `js` 내장 소스 소비 |
| codeatlas-jsp | `io.codeatlas.jsp` | core. JSP 분석, `js` 내장 소스 생산 |
| codeatlas-link | `io.codeatlas.link` | core. 교차 연결, 테이블 관계 추론 |
| codeatlas-export | `io.codeatlas.export` | core. ERD, 클래스 다이어그램, 콜트리, CRUD 매트릭스 |
| codeatlas-viewer | `io.codeatlas.viewer` | core. 단일 HTML 뷰어 출력기 |
| codeatlas-cli | `io.codeatlas.cli` | 전부 |

core 외의 모듈은 서로 의존하지 않는다(cli 제외).

core의 패키지: `model`(그래프 모델, `Ids`), `spi`(SPI와 컨텍스트), `scan`(`ProjectScanner`, `FileClassifier`), `io`(`GraphJson`, `TextDecoder`), `pipeline`(`Pipeline`, `PipelineResult`), `testing`(`TestProject`, 4.6).

Maven `groupId`는 `io.codeatlas`, 버전은 `0.1.0-SNAPSHOT`. 테스트는 JUnit 5와 AssertJ.

## 3. 데이터 모델

### 3.1 타입 (패키지 `io.codeatlas.core.model`)

```java
public enum Confidence { RESOLVED, INFERRED, UNRESOLVED }

/** file은 프로젝트 루트 기준 상대 경로, 구분자는 '/'. 줄과 열은 1부터. 모르면 0. */
public record SourceLocation(String file, int startLine, int startColumn, int endLine, int endColumn) {}

public final class Node {
    String id();                 // 3.3의 규칙
    NodeKind kind();
    String name();               // 짧은 이름 (예: 메서드명, 테이블명)
    String qualifiedName();      // 사람이 읽는 전체 이름 (예: com.a.B#save(String))
    SourceLocation location();   // 정의 위치, 없으면 null
    Map<String,Object> attrs();  // 3.5의 키. 값은 String, Number, Boolean, List<String>만
}

public final class Edge {
    String from(); String to();  // 노드 ID
    EdgeKind kind();
    Confidence confidence();
    SourceLocation location();   // 근거 위치 (호출 지점, SQL 구문 위치 등), 없으면 null
    Map<String,Object> attrs();
}

public record Diagnostic(Severity severity, String code, String message, SourceLocation location) {}
// Severity: INFO, WARN, ERROR. code 예: PARSE_FAILED, UNSUPPORTED_SYNTAX, UNRESOLVED_REFERENCE, DYNAMIC_SQL
```

구현하면서 더한 것 (위 시그니처는 그대로다)
- `Node`와 `Edge`는 불변이다. `Node.of(id, kind, name)`, `Node.builder(id, kind).name(..).qualifiedName(..).location(..).attr(k, v).build()`, `Edge.of(from, to, kind[, location])`, `Edge.builder(from, to, kind).confidence(..)|.inferred()|.unresolved().location(..).attr(k, v).build()`로 만든다. `toBuilder()`, `withAttr`, `Node.withLocation`, `Edge.withConfidence`, `Edge.withEndpoints`는 바꾼 복사본을 돌려준다.
- `name`을 주지 않으면 ID에서 접두사를 뺀 나머지, `qualifiedName`을 주지 않으면 `name`과 같다. 엣지 신뢰도의 기본값은 `RESOLVED`다.
- attrs에 허용 타입이 아닌 값을 넣으면 `IllegalArgumentException`, `null`은 무시한다. 정수는 `int` 범위면 `Integer`, 넘으면 `Long`, 실수는 `Double`로 맞춰 저장한다(JSON 왕복 뒤에도 같은 값이 되게). enum 값은 이름 문자열로 저장한다.
- 속성 조회 편의(`HasAttrs`): `attr`, `attrString`, `attrBoolean`, `attrInt(key, 기본값)`, `attrList`, 그리고 `Node.declared()`.
- `SourceLocation.of(file, line)`(열 0), `SourceLocation.of(file, sl, sc, el, ec)`, `SourceLocation.ofFile(file)`. `file`의 `\`는 `/`로 바꾼다.
- `Diagnostic.info|warn|error(code, message, location)`. 코드 상수는 `DiagnosticCodes`에 있다: `PARSE_FAILED`, `UNSUPPORTED_SYNTAX`, `UNRESOLVED_REFERENCE`, `DYNAMIC_SQL`, `DUPLICATE_OBJECT_NAME`, 그리고 core가 쓰는 `ANALYZER_FAILED`, `LINKER_FAILED`, `EXPORTER_FAILED`, `SERVICE_LOAD_FAILED`, `EXCLUDED_DIRECTORY`, `FILE_NOT_FOUND`, `FILE_NOT_READABLE`, `INVALID_OPTION`.
- `ProjectInfo(name, root, analyzedAt, options)` record: `graph.json`의 `project` 항목. `GraphModel.project()`로 읽고 파이프라인이 채운다. `options`는 분석할 때 준 `AnalysisOptions`(4.1)이고 주지 않으면 `AnalysisOptions.defaults()`다. 3인자 생성자 `ProjectInfo(name, root, analyzedAt)`도 그대로 쓸 수 있다.

### 3.2 GraphModel (스레드 안전)

```java
public final class GraphModel {
    /** 정의를 등록한다. 같은 ID가 있으면 병합: 새 attrs가 덮어쓰고, declared는 true가 이기며, location은 declared=true인 쪽이 이긴다. */
    Node declare(Node node);
    /** 참조만 등록한다. 없으면 attrs.declared=false인 노드를 만들고, 있으면 기존 노드를 돌려준다. */
    Node reference(String id, NodeKind kind, String name);
    /** (from,to,kind,location)이 같은 엣지는 한 번만 저장한다. to가 아직 없는 ID여도 된다. */
    Edge addEdge(Edge edge);
    Optional<Node> node(String id);
    Collection<Node> nodes();  Collection<Node> nodes(NodeKind kind);
    Collection<Edge> edges();  Collection<Edge> edges(EdgeKind kind);
    List<Edge> outgoing(String nodeId);  List<Edge> incoming(String nodeId);
    void report(Diagnostic d);  List<Diagnostic> diagnostics();
}
```

- `declare`로 만든 노드는 `declared=true`가 자동으로 붙는다.
- `declare` 병합의 세부: `kind`, `name`, `qualifiedName`은 새 값을 쓴다. `location`은 먼저 정의된 쪽 것을 유지하고, 기존 노드가 참조만 된 것이었거나 위치가 없었으면 새 위치를 쓴다.
- `reference`가 새로 만드는 노드: `name`은 준 값 그대로, `qualifiedName`은 ID에서 접두사를 뗀 나머지(`Ids.remainder(id)`)다. 예: `java:type:jakarta.annotation.Resource` → `jakarta.annotation.Resource`, `java:method:a.B#run(?)` → `a.B#run(?)`, `db:column:T.C` → `T.C`, `js:module:static/js/app.js` → `static/js/app.js`. 이미 있는 노드는 그대로 돌려주고, 나중에 `declare`하면 위 병합 규칙대로 `declare`의 값이 된다.
- `addEdge`에서 겹치는 엣지는 먼저 들어온 것이 남는다(나중 것의 신뢰도와 attrs는 버린다). 돌려주는 값은 그래프에 남은 엣지다.
- 조회 메서드가 돌려주는 컬렉션은 그 시점의 복사본이고 등록한 순서다.
- 마무리 단계: 엣지의 `to` 또는 `from`이 없는 노드를 가리키면 `kind=UNRESOLVED`, `attrs.expectedPrefix=<ID 접두사>`인 노드를 만들고 그 엣지의 신뢰도를 `UNRESOLVED`로 바꾼다. 이 노드의 ID는 엣지가 가리키던 ID 그대로이고 `declared=false`다. `expectedPrefix`에는 끝의 콜론이 없다(예: `java:method`). 만든 노드마다 `WARN` 진단 `UNRESOLVED_REFERENCE`를 남긴다. `GraphModel.resolveDanglingEdges()`가 이 일을 하고 만든 노드 수를 돌려준다.
- 해석하지 못한 참조를 분석기가 직접 남길 때는 `unresolved:<분류>:<원문>` ID로 `UNRESOLVED` 노드를 `declare`하고 엣지 신뢰도를 `UNRESOLVED`로 둔다. 동시에 진단 `UNRESOLVED_REFERENCE`를 남긴다.

구현하면서 더한 메서드

```java
    /** 이미 있는 노드에 속성을 덧붙인다(같은 키는 덮어쓴다). 노드가 없으면 아무것도 하지 않는다. 남이 만든 노드에 정보를 더할 때 쓴다. */
    Optional<Node> putAttrs(String id, Map<String,?> attrs);
    /** unresolved:<category>:<text> 노드 declare + 신뢰도 UNRESOLVED 엣지 + WARN 진단 UNRESOLVED_REFERENCE를 한 번에. 노드에 attrs.category가 붙는다. */
    Edge addUnresolvedEdge(String from, EdgeKind kind, String category, String text, SourceLocation location);
    /**
     * oldId 노드를 newId로 합친다. oldId의 들어오고 나가는 엣지를 전부 newId로 옮기고(중복 제거 규칙 적용, 기존 엣지가 남는다) oldId 노드를 지운다.
     * 옮겨서 생긴 self-loop은 버리고 원래 oldId→oldId였던 엣지만 남긴다. 노드 attrs는 옮기지 않는다. newId 노드가 없어도 엣지는 옮긴다.
     * oldId 노드가 없거나 두 ID가 같으면 아무 일도 하지 않고 false. 링커(F-012)가 db:table:X 참조 스텁을 뷰·시노님 대상 노드로 합칠 때 쓴다.
     */
    boolean replaceNode(String oldId, String newId);
    List<Edge> edges(String from, EdgeKind kind, String to);
    List<Edge> outgoing(String nodeId, EdgeKind... kinds);   List<Edge> incoming(String nodeId, EdgeKind... kinds);   // kinds가 비면 전부
    List<Node> successors(String nodeId, EdgeKind... kinds); List<Node> predecessors(String nodeId, EdgeKind... kinds); // 중복 없이, 그래프에 있는 노드만
    boolean hasNode(String id);  int nodeCount();  int edgeCount();
    int resolveDanglingEdges();                               // 마무리 단계
    ProjectInfo project();  void project(ProjectInfo p);
    static GraphModel restore(ProjectInfo, Collection<Node>, Collection<Edge>, Collection<Diagnostic>);  // 병합 없이 그대로 복원(GraphJson.read용)
```

- `GraphTraversal.reachable(graph, startId, Direction.OUT|IN, maxDepth, EdgeKind... kinds)`: 엣지를 따라 닿는 노드 ID를 너비 우선 순서로 돌려준다(시작 노드 제외, 순환 안전).

### 3.3 노드 종류와 ID 규칙

이름의 대소문자: Java·JS는 원문 그대로, DB 객체는 대문자로 정규화(따옴표로 감싼 식별자는 원문 그대로). PostgreSQL 읽기 방식에서는 따옴표로 감싼 이름에 대문자가 없으면(`"orders"`) 따옴표 없는 이름과 같은 이름으로 정규화한다(`ORDERS`): PostgreSQL은 따옴표 없는 이름을 소문자로 접으므로 둘이 같은 객체다. 대문자가 든 따옴표 이름(`"createdAt"`)은 원문 그대로다(F-019).

| NodeKind | ID 형식 | 만드는 모듈 |
|---|---|---|
| `FILE` | `file:<상대경로>` | core |
| `JAVA_PACKAGE` | `java:package:<패키지명>` | java |
| `JAVA_TYPE` | `java:type:<바이너리 이름>` 예: `java:type:com.a.Outer$Inner` | java (참조만: springxml, mybatis) |
| `JAVA_METHOD` | `java:method:<타입 바이너리 이름>#<메서드명>(<매개변수 타입 erasure의 바이너리 이름, 쉼표 구분, 공백 없음>)` 생성자의 메서드명은 `<init>`. 배열은 `[]` 접미사. 예: `java:method:com.a.B#save(java.lang.String,int[])` | java |
| `JAVA_FIELD` | `java:field:<타입 바이너리 이름>#<필드명>` | java |
| `SPRING_BEAN` | `spring:bean:<빈 이름>` | java(스테레오타입, `@Bean`), springxml(XML 빈) |
| `HTTP_ENDPOINT` | `http:endpoint:<METHOD> <경로 패턴>` METHOD는 대문자, 지정이 없으면 `ANY`. 경로는 `/`로 시작, 경로 변수는 `{name}` 그대로. 예: `http:endpoint:GET /orders/{id}`. 요청 경로에 프로퍼티 자리표시자가 있으면 값으로 바꾸지 않고 `${이름}` 그대로 둔다(예: `http:endpoint:GET /${adminPath}/sys/user/list`. 이름은 `attrs.placeholders`, 링커가 푼 경로는 `attrs.resolvedPath`, 3.5·3.9) | java, springxml(`<mvc:view-controller>`: 핸들러 메서드 없이 뷰를 바로 그리는 엔드포인트. 메서드는 `GET`이고 `attrs.viewController=true`다. F-017-R16) |
| `HTTP_REQUEST` | `http:request:<METHOD> <URL 패턴>@<파일>:<줄>` 동적 부분은 `{*}`(3.9). URL 패턴은 `attrs.path`와 같다. 한 호출 지점의 URL 후보가 하나면 메서드마다 요청이 하나씩 생길 수 있고(ID의 METHOD가 다르다), URL 후보가 여럿이면 메서드와 곱하지 않고 **URL마다 노드 하나**다: 그 URL에 짝지어진 메서드가 하나면 그 메서드, 여럿이거나 짝을 모르면 `ANY`이고 후보 메서드는 `attrs.httpMethods`에 둔다(F-010-R27·R39). `action` 없는 폼이 현재 URL로 제출하는 요청(`selfSubmit=true`)은 URL 자리가 `{self}`다: `http:request:POST {self}@<파일>:<줄>`(3.9, F-011-R28). 경로가 루트뿐인 요청의 ID는 `http:request:GET /@<파일>:<줄>`이다 | web(JS·TS, 인라인 스크립트, JSX의 HTML 요소 `<a href>`·`<form action>`·`<img src>`·`<iframe src>`, DOM에 넣는 HTML 문자열 안의 같은 태그(F-010-R45), 클라이언트 라우터 이동), jsp(폼, 링크, frame, redirect, refresh, 서버로 가는 include·forward, 접두사 있는 커스텀 태그의 URL 속성, HTML의 Thymeleaf 링크 식 `th:href`·`th:action`·`th:src`) |
| `BATCH_JOB` | `batch:job:<잡 이름>` | java(Java 설정), springxml(XML 설정) |
| `BATCH_STEP` | `batch:step:<잡 이름>/<스텝 이름>` 잡을 모르면(잡 밖 `<flow>`의 스텝 포함) `batch:step:?/<스텝 이름>` | java, springxml |
| `MYBATIS_MAPPER` | `mybatis:mapper:<namespace>` | mybatis |
| `SQL_STATEMENT` | 매퍼 구문: `mybatis:stmt:<namespace>.<id>` / SQL 파일의 구문: `sql:stmt:<파일>:<시작 줄>` / 루틴 안의 구문: `sql:stmt:<루틴 ID에서 접두사 뺀 이름>:<줄>`(패키지 소속이면 `sql:stmt:<패키지>.<루틴>:<줄>`, 아래 "구현하면서 정한 ID 형식") | mybatis(매퍼 XML 구문), java(애너테이션 SQL. `mybatis:stmt:<인터페이스 바이너리 이름>.<메서드명>`), sql(SQL 파일의 구문, 뷰 정의 쿼리, `CREATE TABLE AS SELECT`, 루틴·트리거 본문과 패키지 초기화부의 구문), springxml(배치 리더·라이터 빈의 인라인 SQL. `sql:stmt:<XML 파일>:<줄>`) |
| `SQL_FRAGMENT` | `mybatis:fragment:<namespace>.<id>` | mybatis |
| `DB_TABLE` | `db:table:<이름>` 스키마를 뺀 대문자 이름. 스키마는 `attrs.schema` | sql (참조만: link) |
| `DB_VIEW` | `db:view:<이름>` | sql |
| `DB_COLUMN` | `db:column:<테이블 또는 뷰 이름>.<컬럼명>` | sql (참조만: link) |
| `DB_ROUTINE` | `db:routine:<이름>` 패키지 소속이면 `db:routine:<패키지>.<이름>`. 프로시저, 함수, 트리거 공통 (`attrs.routineType`) | sql (참조만: springxml, link) |
| `DB_PACKAGE` | `db:package:<이름>` | sql |
| `DB_SEQUENCE` | `db:sequence:<이름>` | sql |
| `DB_SYNONYM` | `db:synonym:<이름>` | sql |
| `JS_MODULE` | `js:module:<상대경로>` JSP 안의 인라인 스크립트는 JSP 경로를 그대로 쓴다 | web(JS·TS 파일), jsp(인라인 스크립트의 주인 모듈. `<script src>` 대상은 참조만) |
| `JS_FUNCTION` | `js:function:<상대경로>#<이름 경로>` 이름 경로는 바깥에서 안으로 `.`으로 잇는다(클래스 메서드는 `Class.method`). 클래스도 이 종류로 만든다(`attrs.kind=class`) | web |
| `JSP_PAGE` | `jsp:page:<상대경로>` `.html`·`.htm` 파일도 이 종류로 만든다(`attrs.pageType=html`) | jsp |
| `UNRESOLVED` | `unresolved:<분류>:<원문>` 분류는 3.6. 마무리 단계가 만든 노드의 ID는 엣지가 가리키던 ID 그대로다(3.2) | 분석기·링커(3.6), core(마무리 단계) |

구현하면서 정한 ID 형식 (위 규칙에 더한 것)

| 대상 | ID 형식 | 비고 |
|---|---|---|
| 소스에 없는 합성 메서드 | `java:method:<타입>#<clinit>()`(static 초기화 코드의 주인), `java:method:<타입>#<instance-init>()`(생성자가 둘 이상인 타입의 인스턴스 초기화 코드의 주인), 기본 생성자 `java:method:<타입>#<init>()` | `attrs.synthetic=true`. record의 표준 생성자·접근자, enum의 `values`/`valueOf`도 같은 표시로 만든다 |
| Lombok이 만들 멤버 | 보통의 메서드·필드·타입 ID 그대로다. 메서드는 `java:method:<타입>#<이름>(<매개변수 타입>)`(getter·setter·`withXxx`·`equals`/`hashCode`/`toString`·`builder()`·`toBuilder()`·static 팩터리), 생성자는 `java:method:<타입>#<init>(<필드 타입들>)`, 로그 필드는 `java:field:<타입>#log`, 빌더 타입은 `java:type:<타입>$<빌더 클래스 이름>`(기본 `<타입 단순 이름>Builder`, `builderClassName`을 따른다)과 그 메서드(필드별 메서드, `@Singular`의 단수·복수·`clear`, `build()`) | 애너테이션 처리기를 돌리지 않고 Lombok 애너테이션을 보고 만든다([ADR-0009](../../decisions/0009-synthesize-lombok-members-without-annotation-processing.md), F-002-R14). `attrs.synthetic=true`, `attrs.lombok=<애너테이션 단순 이름>`. 위치는 그 멤버를 만들게 한 필드 또는 애너테이션이다. 소스에 이미 있는 멤버는 만들지 않고(메서드는 이름과 매개변수 수로 판단한다), 합성 생성자가 있으면 기본 생성자 `<init>()` 노드는 만들지 않는다. Lombok은 import와 단순 이름으로 알아보므로 jar가 classpath에 없어도 같은 ID가 나온다 |
| classpath에 없는 타입의 메서드 | `java:method:<FQN>#<이름>(?)`, 생성자는 `java:method:<FQN>#<init>(?)` | 매개변수를 알 수 없다. `external=true`, `signatureUnknown=true`, `declared=false`. classpath에 없는 타입의 `new X(..)`·`super(..)`는 `<init>(?)`로 가는 `CALLS`(`callType=constructor`)가 된다. 컴파일러가 대신 준 후보(`java.lang.Object`의 생성자, 인자 수가 다른 선언)로는 잇지 않는다(F-003-R15) |
| 매개변수 타입이 classpath에 없는 메서드 선언 | 매개변수 자리에 프로젝트 전체의 단일 import에서 유일하게 정해지는 FQN, 정해지지 않으면 소스에 쓴 이름 | jar를 주면 ID가 달라질 수 있다. 단일 import한 이름은 컴파일러가 `java.lang`이나 같은 패키지의 같은 단순 이름으로 풀어 버렸어도 import의 FQN이 매개변수 자리에 들어간다(F-002-R20). 그 자리의 타입 참조도 `java:type:java.lang.<이름>`이 아니라 import한 external 타입으로 간다 |
| 이름 없는 XML 빈 | `spring:bean:<FQN>#<n>` (n은 0부터, 프로젝트 전체에서 파일 경로순·문서 순) | Spring은 컨텍스트별로 세므로 실제 이름과 다를 수 있다 |
| XML 내부 빈 | `spring:bean:<FQN>#inner@<파일>:<줄>` | `attrs.inner=true`. 이름으로 참조할 수 없는 빈이다 |
| 이름 없는 JS 함수 | **미구현(노드를 만들지 않는다).** 이름 조각 `<anonymous>@<줄>:<열>`(`Ids.jsAnonymous`)은 계약에 있지만 이름 없는 함수 자체의 `JS_FUNCTION` 노드를 만드는 모듈은 없다. 안의 호출·요청은 가장 가까운 이름 있는 바깥 함수(없으면 모듈)에 귀속한다(F-010 세부 규칙표) | 이름 없는 함수 안의 선언이 이미 있는 ID와 겹칠 때만 이름 경로에 `<anonymous>@<줄>:<열>.<이름>` 조각을 넣어 구분한다 |
| JSP 식이 섞인 JS 함수 이름 | `js:function:<JSP 경로>#{*}Open`처럼 이름과 ID에서 JSP 식이 있던 자리가 `{*}`다(인라인 스크립트의 `function ${prefix}Open() {}`) | `attrs.dynamicName=true`. 원문(`${prefix}Open`)은 싣지 않는다. 접두사만 다른 두 함수(`${a}Open`, `${b}Open`)는 ID가 같아 한 노드로 합쳐진다(F-010-R25) |
| 매퍼 구문·조각·resultMap의 id에 점이 있을 때 | id가 `<namespace>.`로 시작하면 namespace를 다시 붙이지 않고 그 id가 한정된 ID다: `<mapper namespace="loginDAO"><select id="loginDAO.actionLogin">` → `mybatis:stmt:loginDAO.actionLogin`(MyBatis의 `applyCurrentNamespace`와 같다). 점이 있는데 namespace로 시작하지 않으면 원문 id 그대로 `mybatis:stmt:<원문 id>`다 | 앞의 경우 노드의 `statementId`·`fragmentId`와 `name`은 namespace를 뗀 짧은 id다. 뒤의 경우 `WARN` 진단 `PARSE_FAILED`를 남긴다(MyBatis는 오류로 본다). `refid`·`resultMap`·`extends` 참조는 점이 있으면 그대로, 없으면 namespace를 붙여 찾는다(F-009-R13) |
| 데이터 적재 묶음 구문 | `sql:stmt:<파일>:<그 테이블의 첫 INSERT 줄>` (SQL 파일 구문의 ID 형식 그대로) | 데이터 적재 파일의 리터럴 INSERT는 구문마다가 아니라 (파일, 테이블)마다 노드 하나다. `attrs.dataLoad=true`, `attrs.rowCount`. 적재 파일의 판정과 묶는 규칙은 3.5 공통 규칙 "데이터 적재 구문"에 있다(F-006-R15·R24). 방언별 스크립트가 여러 벌이면 묶음은 테이블마다 한 방언의 것만 노드가 된다: 고른 방언의 파일에 그 테이블의 적재가 없으면 다른 방언 파일의 묶음이 `borrowedFrom`과 함께 남는다. 고르지 않은 방언의 파일의 DML 구문 노드는 만들지 않는다(F-007-R20·R27·R29) |
| 애너테이션 SQL 구문 | `mybatis:stmt:<인터페이스 바이너리 이름>.<메서드명>` | `attrs.annotation=true` |
| XML 빈의 인라인 SQL | `sql:stmt:<XML 파일>:<sql 프로퍼티 요소의 줄>` | `attrs.bean`에 빈 이름 |
| properties 파일의 값에 든 SQL | `sql:stmt:<properties 파일>:<키가 있는 줄>` (SQL 파일 구문의 ID 형식 그대로) | `attrs.propertyKey`에 키. `FILE -CONTAINS→` 이 구문. 누가 실행하는지는 알 수 없어 `EXECUTES`는 없다. 어떤 값을 SQL로 보는지는 F-006-R43·R44 |
| 뷰 정의 쿼리 | `sql:stmt:<파일>:<CREATE 문의 시작 줄>` | `DB_VIEW -CONTAINS→` 이 구문 |
| 루틴(프로시저·함수·트리거) 본문의 구문 | `sql:stmt:<루틴 이름>:<줄>`. 루틴 이름은 `db:routine:` 뒤의 것 그대로다(패키지 소속이면 `<패키지>.<이름>`). 줄은 SQL 키워드(`SELECT`/`INSERT`/`UPDATE`/`DELETE`/`MERGE`/`WITH`/`VALUES`)가 있는 줄이고, 커서 선언·`FOR … IN (SELECT`·`OPEN … FOR SELECT`는 그 안의 `SELECT` 줄, 동적 SQL은 `EXECUTE IMMEDIATE`/`PREPARE`/`OPEN … FOR`/`DBMS_SQL.PARSE` 문장의 첫 줄이다. SQL 구문이 아닌 식(대입식, 조건식, 호출 인자, 선언 초기값) 안의 시퀀스 참조만으로 만든 구문(`attrs.sequence=true`, F-008-R15)은 대입문이면 그 문장의 첫 줄, 그 밖에는 식의 첫 줄이다 | `name`은 `<루틴의 짧은 이름>:<줄>`, `qualifiedName`은 패키지 소속일 때 `<패키지>.<루틴>:<줄>`(아니면 `name`과 같다). `attrs.routine`에 주인 노드 ID. 지역 서브프로그램은 노드를 만들지 않고 그 본문의 구문을 바깥 루틴의 것으로 만든다. 오버로드된 패키지 루틴은 한 노드라 구문도 그 노드에 모인다. 한 루틴의 같은 줄에 구문이 둘이면 한 노드로 합쳐진다(F-008 가정) |
| 패키지 초기화부와 패키지 수준 선언(초기값, 커서 쿼리)의 구문 | `sql:stmt:<패키지>:<줄>` | 주인은 `DB_PACKAGE` 노드다(`attrs.routine`에 `db:package:<패키지>`) |
| 익명 블록(`BEGIN … END`, `DECLARE …`) | 따로 만들지 않는다. SQL 파일이면 블록의 구문 노드 `sql:stmt:<파일>:<줄>` 하나, 내장 소스면 생산자가 만든 주인 노드 하나 | 블록 안의 구문·호출에서 나온 `READS`/`WRITES`/`JOINS`/`CALLS`를 그 한 노드에서 낸다(F-008-R11) |

- ID는 직접 조립하지 않고 `io.codeatlas.core.model.Ids`의 static 메서드로 만든다: `file`, `javaPackage`, `javaType`, `javaMethod(type, name, params...)`, `javaConstructor`, `javaField`, `springBean`, `httpEndpoint(method, path)`, `httpRequest(method, url, file, line)`, `batchJob`, `batchStep(job, step)`, `mybatisMapper`, `mybatisStatement(namespace, id)`, `mybatisFragment`, `sqlStatement(file, line)`, `routineStatement(routineIdOrName, line)`, `table`, `view`, `column(table, column)`, `routine(name)`, `routine(package, name)`, `dbPackage`, `sequence`, `synonym`, `jsModule`, `jsFunction(path, namePath)`, `jsAnonymous(line, column)`, `jspPage`, `unresolved(category, text)`.
- DB 이름 정규화는 `Ids.dbName(식별자)`와 `DbName.parse("schema.name")`이 한다. `Ids.table("hr.orders")`처럼 한정된 이름을 주면 마지막 조각만 쓴다. `"My.Table"`처럼 따옴표 안의 점은 나누지 않고, 따옴표 안의 `""`는 `"` 하나로 본다. `Ids.routine(name)`에 `a.b`를 주면 `a`를 스키마로 보고 버리므로, 패키지 소속 루틴은 `Ids.routine(package, name)`을 쓴다.
- `Ids.httpEndpoint`는 METHOD를 대문자로 바꾸고(null·빈 값은 `ANY`), 경로 앞에 `/`를 붙이고 겹친 `/`를 하나로 줄인다(`Ids.normalizePath`). 끝의 `/`는 건드리지 않는다.
- `Ids.javaMethod`는 매개변수 타입 안의 공백을 지운다.
- `Ids.prefixOf(id)`, `Ids.remainder(id)`, `Ids.kindOf(id)`로 ID에서 접두사, 나머지, 종류를 얻는다. `NodeKind.idPrefix()`는 종류의 접두사다(`SQL_STATEMENT`는 `mybatis:stmt`, `sql:stmt` 둘).
- `FILE` 노드는 core가 스캔 직후 만든다. 종류가 `OTHER`, `JAR`인 파일은 만들지 않는다. `name`은 파일 이름, `qualifiedName`은 상대 경로, `attrs.fileType`은 `FileType` 이름이다.

SQL에서 참조했지만 DDL에 없는 테이블은 `reference("db:table:X", DB_TABLE, "X")`로 만든다(`declared=false`). SQL 분석 쪽은 테이블인지 뷰인지 모르므로 항상 `db:table:`로 참조하고, 뷰·시노님으로 정의된 이름과 맞추는 일은 링커(F-012)가 한다.

### 3.4 엣지 종류

| EdgeKind | from → to | 뜻 | 만드는 모듈 |
|---|---|---|---|
| `CONTAINS` | 아래 "CONTAINS의 조합" 표 | 포함 | 각 모듈 |
| `EXTENDS` | JAVA_TYPE→JAVA_TYPE | 상속 | java |
| | JAVA_TYPE→JAVA_TYPE (둘 다 Lombok 합성 빌더 타입) | 하위 타입과 상위 타입에 모두 `@SuperBuilder`가 있으면 하위의 빌더 타입이 상위의 빌더 타입을 상속한다. 모든 파일을 본 뒤에 만든다 | java |
| `IMPLEMENTS` | JAVA_TYPE→JAVA_TYPE | 인터페이스 구현 | java |
| `ASSOCIATES` | JAVA_TYPE→JAVA_TYPE | 필드로 가진 타입 | java |
| `DEPENDS_ON` | JAVA_TYPE→JAVA_TYPE | 메서드 시그니처·본문에서 쓰는 타입 (ASSOCIATES, EXTENDS, IMPLEMENTS에 없는 것만). 호출한 메서드·접근한 필드를 선언한 타입도 넣는다 | java |
| `OVERRIDES` | JAVA_METHOD→JAVA_METHOD | 상위 타입 메서드 재정의·구현. **직접 상위로만** 잇는다: 타입 계층을 위로 올라가며 가지(상위 클래스, 소스에 적은 인터페이스 각각)마다 처음 만나는 선언이다(F-003-R5). `C extends B implements I`에서 `B`와 `I`에 모두 선언이 있으면 둘 다로 잇고, 상위 클래스가 이미 재정의한 메서드의 더 위 선언으로는 잇지 않는다. 더 위의 선언(간접 상위)까지 필요한 소비자는 `OVERRIDES`를 전이적으로 따라간다(아래 "소비자가 지키는 규칙"). 보통 `RESOLVED`지만 **`INFERRED`일 수 있다**: 매개변수 타입이 classpath에 없어 컴파일러가 재정의를 판정하지 못하면 프로젝트 상위 타입에서 이름·매개변수 수·위치별 타입 표기로 찾아 잇고, FQN을 한쪽만 알아 단순 이름까지만 비교한 자리가 있으면 `INFERRED`다(F-003-R17). `@Override`가 있는데 프로젝트 상위에 후보가 없고 classpath에 없는 상위 타입이 하나면 그 타입의 `(?)` 메서드로 가는 `INFERRED` 엣지다. Lombok 합성 메서드가 낀 재정의(합성 메서드가 프로젝트 상위 타입의 선언을 구현·재정의한 경우, 상위 타입의 합성 메서드를 하위의 선언·합성 메서드가 재정의한 경우)도 모든 파일을 본 뒤 같은 표기 비교로 만든다(`RESOLVED`, `lombok=true`. F-003-R22) | java |
| `CALLS` | JAVA_METHOD→JAVA_METHOD | Java 호출 | java |
| | JAVA_METHOD→JAVA_METHOD (`callType=modelAttribute`) | 요청 핸들러 → 같은 컨트롤러(프로젝트 상위 클래스 포함)의 `@ModelAttribute` **메서드**. Spring이 요청마다 핸들러보다 먼저 부른다. 핸들러마다 하나씩 있고 항상 `INFERRED`, 위치는 호출 지점이 아니라 그 `@ModelAttribute` 메서드의 선언이다. `@InitBinder` 메서드로는 만들지 않는다(F-004-R15) | java |
| | JAVA_METHOD→JAVA_METHOD (`callType=reflection`) | `X.getClass().getDeclaredMethod("<상수>", ..)`(또는 `getMethod`)의 결과를 같은 메서드에서 `.invoke(X, ..)`한 리플렉션 호출의 대상. 항상 `INFERRED`, 위치는 `invoke` 호출이다. X가 감싼 클래스의 타입 매개변수면 그 타입 인자를 프로젝트 타입으로 고정한 하위 타입마다 대상이 생겨 한 메서드에서 여러 타입으로 퍼진다(`viaTypes`, 3.5). 이 엣지에서는 `DEPENDS_ON`을 만들지 않는다(F-003-R20) | java |
| | JAVA_METHOD→JAVA_METHOD (`callType=redirect` 또는 `forward`) | 뷰 이름 `redirect:`·`forward:` 또는 메서드 attrs `redirects`·`forwards`(서블릿 API `sendRedirect`·`RequestDispatcher.forward/include`의 경로, 3.5)가 가리키는 엔드포인트의 핸들러. 항상 `INFERRED`. 위치는 메서드 attrs의 병렬 목록(`viewNameLines`·`redirectLines`·`forwardLines`, 3.5)이 있으면 그 호출 지점이고 없으면 메서드의 위치다(F-012-R28). 출발이 `HTTP_ENDPOINT`일 수 있다: `viewController=true`인 엔드포인트의 view-name이 `redirect:`·`forward:`일 때다(F-012-R34) | link |
| | JAVA_METHOD→UNRESOLVED(`http-endpoint`) (`callType=redirect` 또는 `forward`) | 경로를 전혀 알 수 없는(전부 동적인) `sendRedirect`·`forward`·`include`(java, F-004-R13), 맞는 엔드포인트가 없는 redirect·forward 대상(link) | java, link |
| | JS_FUNCTION 또는 JS_MODULE→JS_FUNCTION | JS 호출. 최상위 코드와 모듈 수준 콜백의 호출은 JS_MODULE에서 나간다 | web |
| | SQL_STATEMENT→DB_ROUTINE | SQL 안의 프로시저·함수 호출 | sql |
| | DB_ROUTINE→DB_ROUTINE (`callType=procedure` 또는 `function`) | 루틴·트리거 본문의 호출. 호출 지점마다 하나이고 자기 자신을 부르면 self-loop이다. 정의되지 않은 대상은 `declared=false` 참조다 | sql |
| | DB_PACKAGE→DB_ROUTINE (`callType=procedure` 또는 `function`) | 패키지 초기화부와 패키지 수준 선언의 호출 | sql |
| `INSTANTIATES` | JAVA_METHOD→JAVA_TYPE | `new` | java |
| `ACCESSES` | JAVA_METHOD→JAVA_FIELD | 필드 접근. Lombok 합성 getter·setter에서 그 필드로 가는 접근(`read`/`write`)과, 합성 `log` 필드를 값으로 쓴 자리의 읽기도 만든다. `@ToString`·`@EqualsAndHashCode`·`@With`·빌더 메서드·합성 생성자에서는 만들지 않는다 | java |
| `ANNOTATED_BY` | JAVA_TYPE/JAVA_METHOD/JAVA_FIELD→JAVA_TYPE | 애너테이션 | java |
| `DEFINES_BEAN` | JAVA_TYPE→SPRING_BEAN | 빈 정의 (스테레오타입, XML `<bean class>`) | java, springxml |
| | JAVA_METHOD→SPRING_BEAN | `@Bean` 메서드 | java |
| `INJECTS` | JAVA_TYPE→JAVA_TYPE (주입받는 타입→선언된 대상 타입) | 의존성 주입 | java, springxml |
| | JAVA_TYPE→JAVA_TYPE (주입받는 타입→구현 타입, `INFERRED`) | 인터페이스 주입의 구현 후보를 하나로 좁힌 것. `attrs.resolvedFrom`에 인터페이스 타입 ID | link |
| | 예외: JAVA_TYPE→SPRING_BEAN (`unresolvedBeanRef=true`), SPRING_BEAN→JAVA_TYPE 또는 SPRING_BEAN (`unresolvedBeanSource=true`) | XML에서 참조한 빈이나 주입받는 빈의 클래스를 알 수 없을 때. 링커가 빈을 정의한 타입을 찾으면 타입→타입 엣지를 더한다(원래 엣지는 남는다). 참조한 빈 이름에 프로퍼티 자리표시자가 있으면(`dataSource-${Globals.DbType}`) 링커가 프로젝트 properties 값으로 치환한 이름의 빈이 정의돼 있을 때 그 빈으로 가는 같은 종류의 엣지를 더한다(`resolvedFrom`=원래 빈 ID. `USES`도 같다, F-012-R23). 풀지 못하면 진단 `UNRESOLVED_BEAN_PLACEHOLDER`(3.7) | springxml, link |
| `ROUTES_TO` | HTTP_ENDPOINT→JAVA_METHOD | 요청 매핑. 경로가 상수가 아니면 UNRESOLVED(`http-endpoint`)→JAVA_METHOD. `viewController=true`인 엔드포인트(springxml의 `<mvc:view-controller>`)에는 `ROUTES_TO`가 없다(F-017-R16) | java |
| `RENDERS` | JAVA_METHOD→JSP_PAGE | 뷰 이름으로 JSP를 가리킴. forward 대상(`forward:` 뷰 이름, 메서드 attrs `forwards`)이 `.jsp`·`.jspf`로 끝나면 엔드포인트가 아니라 그 JSP로 잇는다. 뷰 이름과 맞는 Tiles definition이 있으면 뷰 리졸버 규칙보다 먼저 그 template JSP와 put-attribute JSP로 잇는다(`via=tiles`. 한 핸들러에서 같은 `viewName`의 `RENDERS`가 여럿 나간다. F-012-R30). 동적 조각이 디렉터리 하나인 뷰 이름(`themes/{*}/index`)은 그 자리에 실제 디렉터리를 넣어 찾은 JSP로 잇는다(`dynamicSegment=true`, `INFERRED`. F-012-R44). 위치는 호출 지점이다(`viewNameLines`·`forwardLines`가 없으면 메서드의 위치. F-012-R28) | link (뷰 이름이 상수가 아닐 때의 미해결 엣지는 java) |
| | HTTP_ENDPOINT→JSP_PAGE | 핸들러 메서드 없이 뷰를 바로 그리는 엔드포인트(`viewController=true`). 엔드포인트 노드는 메서드와 경로가 같으면 하나라 여러 모듈의 선언이 한 노드에 모이므로, 선언(`viewDeclarations`)마다 따로 풀어 그 선언 파일의 모듈에 있는 JSP로 잇는다. 위치는 그 선언이다(F-012-R34) | link |
| `REQUESTS` | JS_FUNCTION 또는 JS_MODULE→HTTP_REQUEST | 스크립트의 HTTP 요청 지점 | web |
| | JSP_PAGE→HTTP_REQUEST | 폼, 링크 등 태그의 요청 지점. `INFERRED`일 수 있다: 접두사 있는 커스텀 태그의 URL 속성(`api=tag`), 경로의 EL 삼항으로 갈린 후보 URL(쿼리 문자열 안의 삼항은 펼치지 않는다. F-011-R31), `action` 없는 폼(`selfSubmit=true`) | jsp |
| `MATCHES` | HTTP_REQUEST→HTTP_ENDPOINT | 요청 URL과 엔드포인트 대응. `clientRoute=true`인 요청은 맞추지 않고 미해결도 남기지 않는다(F-012-R29). `selfSubmit=true`인 요청은 URL이 아니라 그 폼이 든 페이지를 그리는 엔드포인트의 경로로 맞춘다(F-012-R40). 모듈 경계는 3.5 `crossModule`·`module`을 본다 | link |
| `IMPORTS` | JS_MODULE→JS_MODULE | import, require | web |
| `INCLUDES` | SQL_STATEMENT/SQL_FRAGMENT→SQL_FRAGMENT | `<include refid>` | mybatis |
| | JSP_PAGE→JSP_PAGE | include 지시자·액션, `<c:import>`, 태그 파일, `<jsp:forward>`, Thymeleaf 조각 포함(`th:replace`·`th:insert`·`th:include`, `via=thymeleaf`) | jsp |
| | JSP_PAGE→JSP_PAGE (`via=sitemesh` 또는 `tiles`) | 레이아웃 프레임워크가 붙이는 관계. `sitemesh`는 내용 페이지 → 데코레이터 JSP(페이지의 `<meta name="decorator">`와 `decorators.xml`·`sitemesh3.xml`을 맞춘다), `tiles`는 Tiles 정의의 template JSP → 조각 JSP다. 항상 `INFERRED`다. JSP 본문에 include가 있다는 뜻이 아니라 실행 때 한 화면으로 합쳐진다는 뜻이다. `via=tiles` 엣지는 template 하나에서 여러 정의의 조각으로 퍼지므로 "한 화면"은 엣지가 아니라 `FILE` 노드의 `tilesDefinitions`·`tilesAttributes`(3.5)로 정의 단위로 묶는다(F-011-R21·R22) | jsp |
| | JSP_PAGE→JS_MODULE | `<script src>`. 다른 모듈의 자원을 다른 URL로 싣는 `src`는 경로 끝에서부터 두 조각 이상 가장 길게 맞는 파일이 하나뿐일 때 잇는다(F-011-R23). 페이지가 가리킨 것이 압축본이고 그 원본 스크립트가 프로젝트에 있으면 원본으로도 잇는다(`minOf`, `INFERRED`. jsp와 web이 만든다) | jsp, web |
| `EXECUTES` | JAVA_METHOD→SQL_STATEMENT | `SqlSession` 계열 호출(java), 매퍼 인터페이스 메서드와 구문의 대응·짧은 구문 ID 추론(link). 상위 인터페이스에서 물려받은 매퍼 메서드는 상위의 메서드 노드 하나에서 하위 매퍼마다 그 매퍼의 구문으로 `INFERRED` 엣지가 하나씩 나간다(`inherited=true`, `namespace`, `declaredIn`. 3.5, F-012-R25). 구문 ID가 "리터럴 접두 + 동적"인 호출은 java가 미해결 엣지에 접두를 남기고(`statementPrefix`) 링커가 그 접두로 시작하는 구문마다 `INFERRED` 엣지를 더한다(`prefixMatch=true`. F-004-R20, F-012-R31·R41). 구문 ID 문자열을 라이브러리 메서드에 인자로 넘긴 호출은 그 ID의 구문이 실제로 있을 때 `INFERRED` 엣지로 잇는다(`viaArgument=true`. F-004-R24, F-012-R43) | java, link |
| | BATCH_STEP→SQL_STATEMENT | MyBatis 리더·라이터의 구문 ID, JDBC 리더·라이터의 인라인 SQL | java, springxml |
| | BATCH_STEP→DB_ROUTINE | 프로시저 리더의 `procedureName` | springxml |
| | JAVA_METHOD→DB_ROUTINE | Java 코드에서 루틴을 직접 실행 | **미구현.** 만드는 모듈이 없다(7절) |
| `USES_TYPE` | SQL_STATEMENT→JAVA_TYPE | parameterType, resultType, resultMap의 타입 | mybatis |
| `READS` | SQL_STATEMENT→DB_TABLE, DB_COLUMN, DB_SEQUENCE (정규화 뒤에는 DB_VIEW도) | 조회. 시퀀스는 `seq.NEXTVAL`·`seq.CURRVAL`, `NEXT VALUE FOR seq`·`PREVIOUS VALUE FOR seq`(`sequence=true`). 루틴 본문의 SQL 구문이 아닌 식 안의 시퀀스 참조도 구문 노드를 만들어 여기서 낸다(F-008-R15) | sql |
| `WRITES` | SQL_STATEMENT→DB_TABLE 또는 DB_COLUMN (정규화 뒤에는 DB_VIEW도) | 변경 | sql |
| `JOINS` | DB_TABLE→DB_TABLE (정규화 뒤에는 끝점이 DB_VIEW일 수 있다) | 한 구문 안의 등치 조건. 테이블 인스턴스 쌍마다 하나. 튜플 `NOT IN (서브쿼리)`의 컬럼 쌍으로는 만들지 않는다(긍정 `IN`·`EXISTS`, 단일 컬럼 `NOT IN`, `NOT EXISTS`의 상관 조건은 만든다. F-006-R20). 등치의 한쪽이나 양쪽이 컬럼 하나만 감싼 형 변환·정리 함수여도 만든다(`wrapped=true`, 3.5. F-006-R29). `IN`·비교의 서브쿼리가 집합 연산이면 분기마다 만들고(F-006-R30), 집합 연산인 인라인 뷰·CTE와의 조인도 분기마다 원본 테이블로 만든다(F-006-R38) | sql |
| `REFERENCES` | DB_TABLE→DB_TABLE (자식→부모. 추론 관계는 옵션 `link.inferViewRelations=true`일 때만 끝점이 DB_VIEW일 수 있다) | 외래 키 | sql(DDL), link(추론) |
| `ALIAS_OF` | DB_SYNONYM→대상. sql은 대상을 항상 `db:table:<대상>` 참조로 만들고, 링커가 최종 대상(DB_TABLE, DB_VIEW, DB_SEQUENCE, DB_ROUTINE, DB_PACKAGE)으로 합친다 | 시노님 | sql, link |
| `USES` | BATCH_STEP→JAVA_TYPE 또는 JAVA_METHOD(`@Bean` 메서드) | 스텝 구성 요소 | java |
| | BATCH_STEP→JAVA_TYPE 또는 SPRING_BEAN(클래스를 모르는 빈) | 스텝 구성 요소 | springxml |
| | BATCH_JOB→JAVA_TYPE 또는 SPRING_BEAN (`role=listener`) | 잡 리스너 | springxml |
| | BATCH_STEP→JAVA_METHOD | 구성 요소 타입의 역할 메서드(리더의 `read` 등)로 이은 것. 빈 끝점을 타입으로 푼 엣지도 더한다 | link |
| `NEXT` | BATCH_STEP→BATCH_STEP | 스텝 흐름 | java, springxml |
| `TRIGGERS_ON` | DB_ROUTINE(트리거)→DB_TABLE | 트리거 대상 | sql |

CONTAINS의 조합

| from → to | 만드는 모듈 |
|---|---|
| FILE→JAVA_TYPE(최상위), JAVA_PACKAGE→JAVA_TYPE(최상위), JAVA_TYPE→JAVA_METHOD·JAVA_FIELD·중첩 타입(로컬·익명 타입은 감싼 타입이 가진다. Lombok 합성 멤버와 합성 빌더 타입도 그 타입이 가진다) | java |
| BATCH_JOB→BATCH_STEP | java, springxml |
| FILE→SPRING_BEAN(내부 빈 제외), FILE→BATCH_JOB, FILE→BATCH_STEP(잡 밖의 스텝) | springxml |
| FILE→MYBATIS_MAPPER, MYBATIS_MAPPER→SQL_STATEMENT·SQL_FRAGMENT | mybatis |
| FILE→DB_TABLE·DB_VIEW·DB_SEQUENCE·DB_SYNONYM·DB_ROUTINE·DB_PACKAGE·SQL_STATEMENT, DB_TABLE·DB_VIEW→DB_COLUMN, DB_PACKAGE→DB_ROUTINE, DB_VIEW→SQL_STATEMENT(정의 쿼리) | sql |
| DB_ROUTINE→SQL_STATEMENT(루틴·트리거 본문의 구문), DB_PACKAGE→SQL_STATEMENT(패키지 초기화부와 패키지 수준 선언의 구문) | sql |
| FILE→JS_MODULE, JS_MODULE→JS_FUNCTION, JS_FUNCTION→JS_FUNCTION(중첩 함수, 클래스 메서드) | web |
| FILE→JSP_PAGE, JSP_PAGE→JS_MODULE(그 페이지 인라인 스크립트의 주인 모듈) | jsp |

- Java 설정으로 만든 스프링 빈·배치 잡, 애너테이션 SQL 구문, XML 빈의 인라인 SQL 구문에는 FILE에서 오는 `CONTAINS`가 없다. 정의 위치는 노드의 `location`과 `definedBy`(배치)에서 얻는다.
- 루틴(프로시저·함수·트리거)이 테이블을 읽고 쓰는 관계는 `ROUTINE -CONTAINS→ SQL_STATEMENT -READS/WRITES→ TABLE`로 표현한다. 루틴에서 테이블로 바로 가는 엣지는 없다. 본문의 SQL 구문 안에서 부른 사용자 정의 함수는 그 구문에서 나가는 `SQL_STATEMENT -CALLS→ DB_ROUTINE`이고, 문장 자리의 호출과 식 안의 함수 호출은 루틴에서 나가는 `DB_ROUTINE -CALLS→ DB_ROUTINE`이다.
- 루틴 본문의 호출 판정(F-008 5절): 내장 함수·내장 패키지(`DBMS_*`, `UTL_*` 등) 호출과 변수·컬렉션 접근은 엣지로 만들지 않는다. 정의를 찾지 못한 이름은 **프로시저 호출문 형태(문장 자리의 호출, `CALL`)일 때만** `declared=false` 참조로 `CALLS`를 만들고, 식 안의 미정의 함수는 만들지 않는다. 패키지 본문에서 이름만으로 부른 같은 패키지의 루틴은 `db:routine:<패키지>.<이름>`이다.
- `%TYPE`·`%ROWTYPE`이 가리키는 테이블, 트리거의 `:NEW`/`:OLD`와 전이 테이블(`REFERENCING NEW TABLE AS …`)로는 엣지를 만들지 않는다. `%TYPE`·`%ROWTYPE`이 가리키는 테이블·컬럼의 이름은 엣지 대신 루틴·패키지 노드의 `attrs.typeRefs`에 남긴다(F-008-R14, 3.5).
- SQL 분석 결과의 READS/WRITES는 테이블 엣지를 항상 만들고, 컬럼의 소속 테이블을 확정할 수 있으면 컬럼 엣지도 만든다. UPDATE·DELETE·MERGE의 대상 테이블에는 테이블 수준 `WRITES`만 만든다(테이블 수준 `READS`를 따로 만들지 않는다). 인라인 뷰를 거쳐 갱신되는 테이블 인스턴스(`UPDATE (SELECT … FROM a JOIN b …) SET …`)도 대상이다. 같은 구문에서 그 테이블을 다른 인스턴스로 다시 읽으면(서브쿼리, `USING`) 테이블 수준 `READS`를 만든다(F-006-R14). WHERE나 SET 우변에서 읽은 대상 테이블의 컬럼은 컬럼 수준 `READS`로만 남는다. MySQL의 `REPLACE INTO`는 `WRITES`(`op=INSERT`)다(F-006-R36).
- **DDL에 없는 컬럼 참조(3차 수정, F-006-R31·R35, F-008-R17).** SQL이 참조했지만 DDL에 없는 컬럼은 `reference("db:column:T.C", DB_COLUMN, "C")`로 만든다(`declared=false`). 생기는 경우는 (1) 별칭·테이블 이름으로 한정한 컬럼, (2) INSERT 컬럼 목록·SET의 컬럼, (3) DDL이 없는 테이블이 스코프에 하나뿐일 때의 비한정 컬럼, (4) 선언된 테이블의 DDL에 없는 비한정 컬럼인데 가장 안쪽 스코프의 실제 테이블이 하나뿐일 때다. (4)에서 테이블이 여럿이면 엣지를 만들지 않고 주인 구문마다 진단 `UNKNOWN_COLUMN` 1건을 남긴다(3.7). 변수·매개변수, 바인드, 함수 이름, 의사 컬럼, SELECT 별칭, 따옴표 이름, 동적 조각과 SQL 키워드 이름(`INTERVAL`, `IGNORE`, `JOIN` 등. DDL에 그 이름의 컬럼이 있으면 컬럼이다)은 컬럼으로도 진단으로도 만들지 않는다. 파서가 해석하지 못한 구간을 남긴 구문에서는 DDL이 있는 테이블에 대해 DDL로 확정되는 컬럼만 기록한다. 루틴 본문과 익명 블록의 구문도 같은 규칙이고, 변수 스코프를 다 알지 못하는 자리(본문 파서가 구조 문제를 남긴 루틴, 컬럼을 모르는 테이블을 `SELECT *`로 도는 DB2 `FOR` 문의 본문)에서는 참조도 진단도 만들지 않는다. 소비자는 정의가 있는 테이블 아래의 `declared=false` 컬럼을 "DDL과 SQL이 어긋난 곳"으로 다룬다(아래 "소비자가 지키는 규칙").
- 의사 컬럼과 컬럼 이름의 구분(F-006-R33·R34): `LEVEL`·`CONNECT_BY_ISLEAF`·`CONNECT_BY_ISCYCLE`은 그 이름이 보이는 스코프에 계층 쿼리(`CONNECT BY`)가 있을 때만 의사 컬럼이고 그 밖에는 컬럼 참조다. 절 키워드로도 쓰는 비예약 낱말(`LIMIT`, `KEY`, `VALUE`, `TYPE`, `NAME`, `STATUS`, `DATE` 등)은 뒤 토큰이 그 절의 모양일 때만 절로 읽는다. 상관 이름 뒤에 컬럼 목록을 둔 테이블(`FROM T AS X (C1, C2)`)의 `X.C1`은 DDL을 알면 그 테이블의 첫 컬럼이고 모르면 컬럼 엣지를 만들지 않는다. `별칭.컬럼.속성`(Oracle JSON 점 표기, 객체 속성)은 둘째 조각의 컬럼 읽기다.
- 루틴 본문의 변수(F-008-R16): DB2 `FOR v AS SELECT … DO`의 루프 변수와 선택 목록의 컬럼 이름, 선언을 보지 못했어도 본문에서 값을 받는 이름(대입문의 대상, `INTO`·`FETCH … INTO` 대상)은 변수다. 레코드·루프 변수의 필드(`r.col`)와 비교한 컬럼은 변수와 비교한 것으로 보아 `JOINS`의 고정 값에 `COL=?`로 남는다(3.5). PL/SQL 커서 FOR 루프의 선택 목록 이름은 변수가 아니다.
- **DB의 사전 뷰·카탈로그 테이블(F-006-R40).** Oracle의 `USER_`·`ALL_`·`DBA_` 사전 뷰와 `V$…`, `SYS.*`, DB2의 `SYSCAT.*`·`SYSIBM.*`·`SYSSTAT.*` 등을 읽으면 테이블 노드를 만들되 `systemCatalog=true`를 붙인다(3.5). DDL에 없는 것이 당연한 테이블이라는 표시다. 사전 스키마(`SYS`, `SYSCAT`, `SYSIBM`, `SYSSTAT`, `INFORMATION_SCHEMA` 등)로 한정한 참조는 이름과 상관없이 표기한다: 이름이나 스키마를 따옴표·백틱으로 감쌌어도, 대소문자가 어떻든, 구문 일부를 해석하지 못해 건진 참조여도 같다(``information_schema.`TABLES` ``. 5차 수정, F-006-R41). 한정자 없이도 읽은 이름(`TABLES`, `COLUMNS`)에는 붙이지 않는다.
- SQL 파싱의 복구와 대상 외 방언(2차 수정, F-006-R16~R18·R22, F-007-R16·R17). 한 텍스트에서 구문이 끝난 뒤 남은 토큰이 `SELECT`/`INSERT`/`UPDATE`/`DELETE`/`MERGE`/`WITH`/`CALL`로 시작하면 거기서 잘라 별도 구문으로 파싱해 같은 주인에 합친다. 그 밖의 남은 토큰은 건너뛰되 쉼표 뒤를 FROM 목록으로 잇지 않고, 복구하며 건지는 테이블 이름은 `FROM`/`JOIN` 바로 뒤의 것만 인정한다(없는 테이블을 만들지 않기 위해서다). 테이블 이름 자리에서는 예약어만 막는다(`SAMPLE`, `LOG`, `MODEL`, `PARTITION` 같은 비예약 키워드는 테이블 이름이 될 수 있다). 대상 방언은 Oracle과 DB2이고, 그 밖의 방언은 "죽지 않고 틀린 사실이나 경고 폭주를 만들지 않는" 수준으로만 읽는다: MySQL의 백틱 식별자(대문자로 정규화), 줄 처음의 `#` 주석(3차 수정부터 MySQL로 판정한 SQL에서만 받는다. 판정은 경로나 내장 소스 `attrs.dialect`의 방언 이름, 옵션, 본문의 MySQL 특징 순이고, 근거가 없으면 `#TMP` 같은 이름은 식별자다. F-006-R27), `LIMIT`, `AUTO_INCREMENT`·`UNSIGNED`·`COMMENT '..'`·`ENGINE=..` 같은 컬럼·테이블 옵션과 테이블 수준 `KEY`·`INDEX` 절, PostgreSQL의 `expr::type`, HSQL의 `CREATE MEMORY|CACHED TABLE`, MSSQL의 `IF EXISTS (..) DROP ..`, 종결자 없이 줄마다 구문 하나인 스크립트(줄 처음의 구문 키워드에서 끊는다)를 진단 없이 읽는다. 4차 수정에서 MySQL 표기를 더 받는다(F-006-R36): 사용자 변수 `@v`·`@@v`, `INSERT … IGNORE INTO`, `REPLACE INTO`, `INSERT INTO t SET …`, `ON DUPLICATE KEY UPDATE`, `UPDATE a JOIN b … SET`(첫 테이블이 대상), `DELETE a[, b] FROM a JOIN b`(앞의 이름들이 대상), `INTERVAL 식 단위`, `REGEXP`·`RLIKE`, 인덱스 힌트, `STRAIGHT_JOIN`. 문자열 안의 백슬래시 이스케이프는 MySQL로 판정한 입력에서만 받는다. 그 방언 고유의 구문을 뜻까지 분석하지는 않는다(7절).
- `EXECUTES` JAVA_METHOD→DB_ROUTINE은 **미구현**이다(java 모듈은 `SqlSession` 계열 호출과 애너테이션 SQL만 본다). Java에서 프로시저로 가는 경로는 `JAVA_METHOD -EXECUTES→ SQL_STATEMENT -CALLS→ DB_ROUTINE`(매퍼 구문의 `{call …}`)으로 이어진다.
- 미해결 참조는 어느 종류의 엣지든 `to`(ROUTES_TO는 `from`)가 `UNRESOLVED` 노드일 수 있다(3.6).

`CALLS`의 `attrs.callType` 값

| 값 | 뜻 | 만드는 모듈 |
|---|---|---|
| `static`, `virtual`, `interface` | 대상 메서드에 따른 호출 방식 | java |
| `super` | `super.m()` | java |
| `constructor` | `new`, `this(...)`, `super(...)`, enum 상수의 생성자 호출. JS의 `new` | java, web |
| `methodRef` | 메서드 참조(`A::m`, `A::new`) | java |
| `call`, `jsx`, `ref` | JS 함수 호출(`f.call(x)`·`f.apply(x)` 포함), JSX 요소로 쓴 컴포넌트, 함수를 실행하지 않고 참조만 넘김(핸들러·콜백 등록, 옵션 객체 값, JSX 속성, `f.bind(x)`. F-010-R13) | web |
| `redirect`, `forward` | 뷰 이름 접두사(`redirect:`·`forward:`) 또는 서블릿 API(`sendRedirect`, `RequestDispatcher.forward`·`include`)로 다른 핸들러로 넘어감. `include`도 `forward`다 | link (경로를 전혀 알 수 없을 때의 미해결 엣지는 java) |
| `modelAttribute` | 요청 핸들러보다 먼저 실행되는 같은 컨트롤러의 `@ModelAttribute` 메서드. 항상 `INFERRED`이고 위치는 대상 메서드의 선언이다(F-004-R15) | java |
| `reflection` | `Method.invoke`의 대상을 한 가지 꼴(`X.getClass().getDeclaredMethod("상수", ..)` → `.invoke(X, ..)`)에서 추론한 호출. 항상 `INFERRED`(F-003-R20) | java |
| `procedure`, `function` | 루틴 본문(또는 패키지 초기화부)에서 문장 자리의 호출(`proc(..)`, `pkg.proc(..)`, DB2 `CALL proc(..)`) / 식 안의 함수 호출 | sql |

- **`lambda` 값은 폐기했다.** 람다 안의 호출도 `callType`은 실제 호출 방식(`static`/`virtual`/`interface` 등)이고, 람다를 거쳤다는 사실은 `attrs.viaLambda=true`로만 표시한다.
- SQL_STATEMENT→DB_ROUTINE `CALLS`에는 `callType`이 없다. 미해결 `java-call` 엣지에도 `callType`이 없다(`argCount`만 있다, 3.5).

소비자가 지키는 규칙 (그래프를 읽는 쪽이 계약을 바르게 해석하려면 필요한 것. export의 구현(`io.codeatlas.export.calltree.FlowNavigator`) 기준이고 세부는 F-015 spec의 R18~R21·R25·R26·R28~R39와 5절, 뷰어는 F-016 spec을 본다. "수신자 문맥"부터 "데이터 적재 구문"까지는 2차 수정(2026-10-11)에서 더했다)
- **`OVERRIDES`는 전이적으로 따라간다.** 엣지는 직접 상위로만 있으므로, 구현을 펼칠 때는 `OVERRIDES`를 거꾸로 전이적으로 따라 간접 구현까지 넣고, 호출자를 찾을 때는 그 메서드가 전이적으로 재정의한 모든 상위 메서드의 호출자를 넣는다. 주입(`INJECTS`의 `INFERRED` 엣지)으로 구현을 좁힐 때는 후보 타입에 선언된 재정의, 없으면 `EXTENDS`를 따라 가장 가까운 상위 클래스의 것을 고른다.
- **트리거는 사건이 맞을 때만 따라간다.** `구문 -WRITES→ 테이블 ←TRIGGERS_ON- 트리거`를 따라갈 때 쓰기의 종류(`WRITES`의 `op`, 없으면 구문의 `statementType`. `MERGE`는 `INSERT`와 `UPDATE` 둘 다)가 트리거의 `triggerEvent`에 있어야 한다. `UPDATE OF <컬럼>` 트리거는 그 구문의 `WRITES` 컬럼 엣지에 그 컬럼이 있을 때만 맞다. `truncate=true`인 쓰기는 트리거를 일으키지 않는 것으로 본다. 맞는지 알 수 없으면(`triggerEvent`가 없거나 해석되지 않음, 컬럼 지정 트리거인데 그 테이블의 `WRITES` 컬럼 엣지가 없음, 쓰기의 종류를 모름) 따라가되 불확실하다고 표시한다(콜트리의 `[trigger?]`).
- **테이블 단위 접근의 `R`은 테이블 수준 `READS`만 센다.** 컬럼으로 가는 `READS`는 테이블의 `R`로 접지 않는다(위 규칙대로 단순 `UPDATE`·`DELETE`의 대상 테이블에는 테이블 수준 `READS`가 없으므로 `U`·`D`다). 컬럼으로 가는 `WRITES`는 테이블로 접는다. `MERGE`는 C와 U다(3.5).
- **루틴·트리거를 거친 접근은 소문자로 구분한다.** CRUD 매트릭스에서 주체가 직접 하는 접근은 대문자, DB 루틴(프로시저·함수·트리거)의 본문을 거쳐서만 닿는 접근은 소문자다(같은 글자가 둘 다면 대문자만). 루틴 → 루틴 `CALLS`와 구문 → 루틴 `CALLS`를 걸음 수 상한(`export.crud.depth`, 3.8)까지 따라간다.
- **동적 구문은 빼지 않고 따로 드러낸다.** `unresolved:sql-dynamic:`으로 가는 `READS`/`WRITES`가 있는 구문은 대상 테이블을 알 수 없으므로 테이블 없이 따로 낸다(CRUD 매트릭스의 `(동적)` 열, `report.md`의 "대상 테이블을 정적으로 알 수 없는 구문").
- **팬아웃 엣지는 수신자 문맥으로 좁힌다.** 그래프에는 전부 남기고 소비자가 좁히는 구조다(근거와 대안은 [ADR-0011](../../decisions/0011-keep-fanout-edges-and-narrow-in-consumers.md), 제안 상태). 한 노드에서 여러 구체 타입으로 퍼지는 엣지가 둘 있다: 상위 타입에서 물려받은 매퍼 메서드의 `EXECUTES`(`inherited=true`, 하위 매퍼마다 하나)와 제네릭 상위 메서드의 `callType=reflection` `CALLS`(대상 타입마다 하나). 이런 엣지를 따라갈 때는 경로에서 가장 최근에 알려진 구체 수신자 타입과 맞는 것만 따라간다. 문맥은 호출 엣지의 `receiverType`에서 얻고, `super` 호출과 상속받은 메서드를 자기 객체에 대고 부른 호출은 호출한 쪽의 문맥(없으면 호출한 쪽의 타입)을 이어받는다. 그 밖의 호출(다른 객체의 메서드)은 호출한 쪽의 문맥을 "감싼 객체의 타입"으로 한 걸음만 넘긴다(상위 서비스 안의 `this.dao.x()`). `static`·`constructor`·`methodRef` 호출은 문맥을 끊는다. 상속 `EXECUTES`는 그 엣지의 `namespace`(없으면 구문 노드의 `namespace`)가 문맥 타입과 맞는 것만, 리플렉션 엣지는 문맥 타입(또는 그 구현 타입)이 `viaTypes`에 든 것만 따라간다. "맞다"는 두 타입이 `EXTENDS`·`IMPLEMENTS`로 상·하위 관계이거나, 문맥 타입(과 그 상위 타입)이 그 타입을 묶는다는 뜻이다: 상위 타입으로 가는 `EXTENDS`·`IMPLEMENTS` 엣지의 `typeArguments`(3.5. `OrderService extends CrudService<OrderDao, Order>`의 `OrderDao`), 그것이 없으면 `DEPENDS_ON`·`ASSOCIATES`·`INJECTS`로 참조하는 타입이다. 맞는 것이 하나도 없으면 전부 따라가되 불확실하다고 표시한다(콜트리의 `[?]`). 문맥이 아예 없으면 상속 구문은 표시 없이 전부, 리플렉션 팬아웃은 `[?]`로 전부 따라간다(F-015-R29).
- **호출자 방향은 대칭이다.** 구문에서 거슬러 올라갈 때는 구문의 namespace와 맞는 수신자로 부른 호출자만, 리플렉션 엣지를 거슬러 올라갈 때는 `viaTypes`와 맞는 호출자만 남긴다. 재정의한 상위 메서드의 호출자를 넣을 때도 그 호출의 수신자 타입이 이 메서드를 가진 타입과 상·하위 관계가 아니면 넣지 않는다.
- **주입으로 구현을 좁힐 때 구현 타입으로 바로 가는 주입도 쓴다.** 호출하는 타입에서 나가는 `INJECTS`의 대상이 호출된 메서드를 선언한 타입의 하위 구체 클래스면(XML 주입: `via=xml`·`resolvedFrom=spring:bean:…`, 필드 타입이 구현 클래스인 주입) 신뢰도와 상관없이 후보다. 선언 타입 자신으로 가는 주입이 `candidates` 없이 따로 있으면 이 규칙으로는 좁히지 않는다. 구현까지 온 `OVERRIDES` 사슬에 `RESOLVED`가 아닌 엣지가 있으면 그 구현 줄도 추론이다. `super` 호출로 도착한 메서드는 구현으로 펼치지 않는다(F-015-R34).
- **핸들러가 여러 모듈에 있는 엔드포인트.** 엔드포인트 노드는 메서드와 경로가 같으면 하나라 여러 웹 모듈의 핸들러(`ROUTES_TO`)가 한 노드에 모인다. 요청에서 따라갈 때는 요청을 낸 파일과 모듈 루트(위치의 파일 경로에서 `/src/main/`·`/src/test/` 앞부분. `src/`로 바로 시작하면 루트 모듈)가 같은 핸들러가 있으면 그것만 따라간다. 엔드포인트에서 시작하면 전부 따라가고 줄마다 모듈을 밝힌다. `MATCHES`에 `crossModule=true`가 있으면 추론으로 표시한다(F-015-R36). 4차 수정부터 링커는 선언(핸들러 메서드, view-controller 선언)이 여러 모듈에 있는 엔드포인트로 가는 같은 모듈 매칭에 `MATCHES.module`(모듈 루트)을 남긴다(3.5, F-012-R37): 이 값이 있으면 그 요청에서 내려간 엔드포인트 아래로는 그 모듈의 핸들러(`ROUTES_TO` 대상 메서드의 파일)와 그 모듈의 선언에서 나온 화면(엔드포인트에서 나가는 `RENDERS`의 위치가 그 모듈의 파일)만 따라가고, 맞는 것이 없으면 아무것도 따라가지 않는다. 위 방향도 같다: 핸들러(뷰 컨트롤러의 화면)에서 올라간 엔드포인트의 요청 가운데 `module`이 있는 것은 그 핸들러(그 선언)의 모듈일 때만 따라간다. `module`이 없으면 위의 경로 규칙 그대로다(F-015-R62). 5차 수정부터 `module`은 `crossModule=true`인 `MATCHES`에도 붙을 수 있다(정적 UI 모듈의 페이지를 그리는 핸들러의 모듈. 3.5, F-012-R48): 따라가는 규칙은 같다(그 모듈의 핸들러와 그 모듈 선언의 화면만, 위 방향도 같다. F-015-R69). 위 방향에서 선언이 여러 모듈에 있는 엔드포인트가 트리의 시작이면(어느 핸들러에서 올라온 것이 아니면) 요청 줄마다 그 요청의 `MATCHES.module`을 `(모듈 <경로>)`로 붙인다(`module`이 없는 요청과 선언이 한 모듈뿐인 엔드포인트에는 붙이지 않는다. 뷰어는 관계 이름에 `MATCHES(모듈 <경로>)`. F-015-R68). 엔드포인트가 시작일 때는 그 URL로 넘어오는 forward·redirect도 호출자다: 그 엔드포인트의 핸들러로 들어오는 `callType=forward|redirect`인 `CALLS`를 `[forward]`·`[redirect]` 한 줄씩 낸다(핸들러가 엔드포인트 여럿을 받으면 엣지의 `path`가 그 엔드포인트의 경로와 같은 것만, 어느 것과도 같지 않으면 전부에. 핸들러에서 올라온 엔드포인트에는 다시 적지 않는다. F-015-R72).
- **풀린 미해결 `EXECUTES`는 숨길 수 있다(4차 수정).** 엣지를 지우는 API가 없어 링커가 푼 뒤에도 원래의 미해결 엣지가 남는다. 미해결 `EXECUTES` 엣지 e는 같은 출발·같은 위치에 `resolvedFrom = e.to`인 `EXECUTES`가 있으면 풀린 것이다(`shortId`·`prefixMatch` 공통). 들어오는 접두 엣지가 전부 풀린 미해결 노드에는 `resolvedBy=prefixMatch`가 붙는다(3.5, F-012-R42). 콜트리는 풀린 미해결 `EXECUTES`를 내지 않고, `resolvedBy`가 있는 `UNRESOLVED` 노드로 가는 참조는 리포트의 "미해결 참조"와 cli 힌트의 미해결 수에서 뺀다(F-015-R63, F-001-R56). 그래프의 노드·엣지와 진단 `UNRESOLVED_REFERENCE`의 건수는 그대로다(분석기가 미해결 엣지를 만들 때 남긴 진단은 지우지 않는다). 5차 수정부터 한 가지 규칙으로 센다(F-001-R58, F-015-R73): 그래프의 수(종류별 노드·엣지 수, 신뢰도별 엣지 수: `report.md`의 표, cli 요약, 검증 리포트의 머리)는 그래프 그대로이고, "미해결 참조"의 목록·건수·힌트만 풀린 것을 뺀다. 두 수가 다른 곳에는 까닭을 한 줄 적는다: `report.md`의 "미해결 참조" 머리(``신뢰도가 `UNRESOLVED`인 엣지 n건 가운데 m건은 같은 지점에서 접두 일치로 풀려(대상 노드의 `resolvedBy`) 여기서 뺐다.``), cli 요약의 신뢰도 표 아래, `diagnostics.md`의 머리(풀린 참조의 진단은 지우지 않고 메시지 끝에 `(접두 일치로 풀림)`을 덧붙인다), 뷰어 진단 보기의 "미해결 참조".
- **중간 정보는 상세에 쏟지 않는다.** 분석기가 다음 단계에 넘기려고 남긴 `JAVA_METHOD`의 `statementIdLiterals`·`statementIdLiteralLines`와 `JSP_PAGE`의 `formFields`는 링커·web이 읽는 중간 정보다. 뷰어는 노드 상세의 속성 표에 넣지 않고 접힌 "분석 중간 정보" 아래에 두며 속성 수에도 세지 않는다(F-016-R42).
- **추론의 근거 표시.** `candidate`·`suffixMatch`·`dynamicSegment`(`RENDERS`), `prefixMatch`·`viaArgument`(`EXECUTES`), `selfSubmit`(`MATCHES`), `dynamicDecorator`(`INCLUDES`), `loadUnverified`(JS `CALLS`)는 모두 `INFERRED` 엣지에 붙는 까닭 표시다. 따라가는 규칙은 바뀌지 않고 추론 걸음의 까닭으로 쓰인다. 콜트리는 줄 끝에 `(접두 일치)`(`prefixMatch`), `(인자 구문)`(`viaArgument`), `(동적 뷰 이름 후보)`(`RENDERS`의 `dynamicSegment`)를 적는다(F-015-R64). 뷰어는 같은 표시를 관계 이름에 붙인다(`EXECUTES(접두 일치)`, `EXECUTES(인자 구문)`, `RENDERS(동적 뷰 이름 후보)`. F-016-R41). 링커가 동적 뷰의 후보를 근거로 좁힌 엣지(`dynamicSegment=true` + `narrowedBy`, 3.5)는 "후보"라 부르지 않고 근거를 적는다: `narrowedBy=property`는 `(동적 뷰 이름: 프로퍼티 값으로 정함)`, `moduleName`은 `(동적 뷰 이름: 모듈 이름으로 정함)`이고 추론 표식 `~`는 그대로다. `narrowedBy`가 없는 `dynamicSegment`만 `(동적 뷰 이름 후보)`다(F-015-R76). 동적 자리의 글자는 `{*}`일 수도 프로퍼티 자리표시자(`${app.name}`)일 수도 있으므로 소비자는 `viewName`의 글자가 아니라 `dynamicSegment` attr로 판단한다. 표준 산출물 `crud/README.md`의 추론 까닭 목록에도 이 표시들이 들어 있다. 동적 데코레이터는 추론 레이아웃 줄이다(F-015-R66). `ambiguous=true`는 같은 (출발, 종류, 위치)에서 나가는 후보가 둘 이상일 때만 붙으므로 `[ambiguous n]` 묶음은 항상 n ≥ 2이고, 묶음은 아래 방향에서만 만든다(위 방향은 호출자·요청마다 지점이 달라 한 지점의 후보가 아니다. F-003-R29, F-012-R45, F-015-R67).
- **요청의 표시 이름.** `httpMethod=ANY`이고 `httpMethods`가 있으면 메서드 자리에 그 목록을 `|`로 이어 적는다(`POST|PUT /api/schedule`). `selfSubmit=true`인 요청은 `<METHOD> (자기 페이지로 제출)`로 적고 `{self}`를 URL처럼 보이지 않는다(F-015-R65). 맞는 엔드포인트를 찾지 못한 selfSubmit의 미해결 대상(원문 `<METHOD> (selfSubmit)`, 3.6)도 풀어 쓴다(콜트리, `report.md`의 "미해결 참조", 뷰어. F-015-R70): 그 요청을 낸 화면(`REQUESTS`의 출발인 `JSP_PAGE`)을 그리는 엔드포인트(화면으로 들어오는 `RENDERS`의 출발이 엔드포인트면 그것, 핸들러면 그 핸들러로 `ROUTES_TO`하는 엔드포인트)의 경로를 알면 찾던 URL로 `POST /place/view.do (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 POST 매핑 없음)`, 모르면 `POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 찾지 못함)`, 요청 문맥이 없는 곳(그 노드가 시작일 때)은 `POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 모르거나 그 경로에 POST 매핑 없음)`이다. 후보가 여럿인 selfSubmit의 `[ambiguous n]` 묶음 줄은 요청 줄의 문구를 되풀이하지 않고 `후보 엔드포인트`로 적는다(보통 요청의 묶음 줄은 전처럼 요청 이름. F-015-R71).
- **엔드포인트의 표시 이름.** `resolvedPath`가 있으면 그것을 쓰고 원문을 괄호로 덧붙인다(`GET /a/sys/user/list (/${adminPath}/sys/user/list)`). 이름으로 찾을 때도 `resolvedPath`를 본다(F-015-R37).
- **호출되는 쪽의 자식 순서는 호출 지점 순이다.** `callType=modelAttribute`(`[before]`)가 맨 앞, 그다음 엣지 `location`의 줄·열 순(`CALLS`, `EXECUTES`, `REQUESTS`, `INCLUDES`, `RENDERS`, `USES`), 위치를 모르는 호출과 구현 전개·테이블 접근·트리거는 그 뒤에 노드 ID 순이다. 호출하는 쪽은 노드 ID 순이다(F-015-R31).
- **표식.** 콜트리의 `[before]`는 `callType=modelAttribute`(핸들러보다 먼저 실행), `[reflect]`는 `callType=reflection`, `[?]`는 수신자 타입에 맞는 대상을 고르지 못해 전부 따라간 줄이다. `~`(추론)·`?`(미해결)·`[impl]`·`[trigger?]`는 전과 같다(F-015-R29·R38).
- **접근자와 소스 없는 메서드는 접을 수 있다.** 접근자는 Lombok이 만들 멤버(`synthetic=true`이고 `lombok`이 있음)이거나, 이름이 `get`·`is`·`set` + 대문자이고 같은 타입에 대응하는 필드(`JAVA_FIELD`: `getFoo`/`isFoo`/`setFoo` ↔ `foo`. `Foo`, `isFoo`도 본다)가 있으며 매개변수가 getter 0개·setter 1개이고 `static`이 아닌 메서드이면서, 그 메서드와 그것을 재정의한 메서드 어디에서도 `CALLS`·`EXECUTES`가 나가지 않는 것이다(생성자와 소스 없는 타입의 메서드 제외. 3차 수정에서 이름만 보던 것을 좁혔다, F-015-R43). 호출되는 쪽 트리에서 `CALLS`로 닿은 줄만 접는다. 표준 산출물은 접근자와 소스 없는 메서드를 기본으로 접고(3.8 `export.calltree.hideAccessors`·`hideExternal`), 조회 API의 기본값은 접지 않는다(F-015-R35).
- **필터와 인터셉터는 요청 처리의 진입점이다.** `stereotype`이 `filter`·`interceptor`인 타입의 진입 메서드(`doFilter`, `doFilterInternal`, `preHandle`, `postHandle`, `afterCompletion`, `afterConcurrentHandlingStarted`)에서 시작하는 트리를 따로 낸다(`calltree/filters.md`, F-015-R39). 이 두 `stereotype`은 빈이라는 뜻이 아니다(3.5).
- **데이터 적재 구문은 기본으로 뺀다.** `dataLoad=true`인 구문은 애플리케이션이 실행하는 구문이 아니라 스크립트의 INSERT 여러 건을 대표하는 노드다. 구문 기준 CRUD 매트릭스, 테이블 명세의 구문 수, 리포트의 테이블 점검, 콜트리에서 기본으로 빼고 리포트에 "파일 n개, 테이블 m개, 행 k건"으로 요약한다(`rowCount`). 넣으려면 `export.includeDataLoad=true`(조회 API는 인자·옵션). 영향도는 빼지 않는다(테이블을 바꾸면 적재 스크립트도 영향을 받는다. F-015-R28). 구문 종류와 무관하다: 적재 파일의 조건 없는 `DELETE`·`TRUNCATE`, 값 자리에서 함수를 부르는 적재 구문(`CALLS`→루틴)도 같은 규칙으로 뺀다(F-015-R45).
- **요청의 경계(3차 수정, 2026-10-11).** ① `callType=modelAttribute`(`[before]`)는 "요청으로 진입한 핸들러"(트리의 시작 노드, 엔드포인트에서 `ROUTES_TO`로 도착, 다른 핸들러의 redirect·forward로 도착) 아래에서만 따라간다. 호출자 방향에서는 `@ModelAttribute` 메서드의 호출자로 올린 핸들러에서 위로 엔드포인트(와 redirect·forward로 넘어오는 핸들러)로만 올라간다(F-015-R41). ② `callType=redirect|forward`는 화면 이동이다: 콜트리는 `[redirect]`·`[forward]` 한 줄만 보이고 그 너머는 `expandRenderedViews`를 켜야 펼친다. 테이블 × 엔드포인트 CRUD와 영향도는 redirect를 따라가지 않고(별개의 요청) forward는 따라간다(F-015-R42). ③ 핸들러 없는 엔드포인트(`viewController=true`)는 `RENDERS`를 바로 따라가고, 모듈마다 `RENDERS`가 있으면 핸들러와 같은 모듈 규칙을 쓴다(F-015-R46). ④ `clientRoute=true`인 요청은 `[route] <경로>`이고 엔드포인트로 이어지지 않아도 미해결로 치지 않는다(`report.md`의 "화면 요청"에서 따로 센다, F-015-R47). ⑤ 레이아웃(JSP → JSP `INCLUDES`의 `via=sitemesh|tiles`, `RENDERS`의 `via=tiles`이고 `tilesAttribute`가 아닌 것)은 `[layout]` 한 줄이고 `expandLayouts`를 켜야 따라간다. 호출자 방향에서는 "쓰는 화면 n개"로 접고, CRUD·영향도는 따라가지 않는다(F-015-R48). ⑥ 한 호출 지점의 `ambiguous=true` 호출 여럿은 `[ambiguous n]` 한 줄 아래에 묶는다(F-015-R49). `loadUnverified=true`인 JS 호출은 추론으로, `(로드 미확인)`과 함께 보인다(F-015-R50).
- **조건을 넘겨받는 제네릭 상위 클래스.** 호출자 방향에서 조건(구문의 namespace 등)을 넘겨받을 제네릭 상위 클래스의 구체 하위 타입이 모두 다른 팬아웃 대상을 고르면 그 호출자는 넣지 않는다(F-015-R44).
- **DDL에 없는데 SQL이 참조하는 컬럼.** 정의가 있는 테이블의 `declared=false` 컬럼은 ERD에 넣지 않고 테이블 명세와 `report.md`("SQL과 DDL의 컬럼 불일치")에 따로 적는다(F-013-R19, F-015-R51).
- **ERD 영역.** 큰 ERD는 테이블 이름 접두사로 나누고, 접두사로 묶이지 않는 테이블은 관계로 이어진 테이블끼리 묶는다(영역 이름은 그 안에서 가장 많이 참조되는 테이블, 전체의 20% 넘는 테이블이 참조하는 허브는 영역 밖. F-013-R17). `directionKnown=false`와 함께 온 `declaredOpposite`·`pseudoKey`는 라벨에 까닭을 적는다(F-013-R18). 추론 관계가 `directionKnown=true`이면서 `cardinality=unknown`이면(`directionFrom=DDL`) 자식→부모 방향은 지키되 N:1·1:1을 계산해 그리지 않고 "카디널리티 모름"으로 표기한다(라벨 `(추론 n건, 방향은 DDL, 카디널리티 모름)`, Mermaid는 부모를 왼쪽에 둔 양쪽 `}o..o{`, PlantUML은 `부모 <.. 자식`, DBML은 자식을 앞에 둔 `<>`. F-013-R27). upgrade 스크립트에만 있고 현재 정의에 없는 외래 키(`fromUpgrade=true`이고 신뢰도가 `INFERRED`인 `REFERENCES`)는 점선으로 그리고 `추론 n건` 대신 `(upgrade 스크립트, 현재 정의에 없음)`을 적는다(F-013-R26). 추론 관계(점선)의 부모 쪽은 "0 또는 1"로 그린다: 제약이 없어 부모가 반드시 있는지 알 수 없다(Mermaid·PlantUML `부모 |o..o{ 자식`, 1:1이면 `|o..o|`. 엣지의 `cardinality`가 `N:1`이어도 같다. DBML에는 필수 여부 표기가 없어 머리 주석으로만 알린다). DDL 외래 키(실선)는 전처럼 FK 컬럼이 nullable이면 `|o--`, 아니면 `||--`다(F-013-R28). `systemCatalog=true`인 테이블·뷰(DB가 제공하는 데이터 사전)는 프로젝트 스키마가 아니라서 ERD(Mermaid, PlantUML, DBML, 뷰어)의 엔티티로도 관계의 끝으로도 넣지 않고, 다이어그램 머리 주석·`erd/index.md`·`report.md` 머리에 뺀 수를 한 줄 적으며, 전체 테이블 명세(`erd/tables.md`)의 끝 "시스템 카탈로그" 절에 이름만 남긴다(옵션 키는 없다. F-013-R29).
- **추론 경로의 표시(4차 수정, 2026-10-11).** 걸음마다 "추론 걸음"인지를 정한다: 콜트리에서 `~`(신뢰도 `INFERRED`)·`[?]`·`[trigger?]`가 붙는 걸음이되, 프레임워크의 규칙으로 대상이 정해지는 셋 — `callType=modelAttribute`, `callType=forward`(같은 요청 안의 서버 내부 이동. 대상 매칭이 `ambiguous`·`suffixMatch`·`crossModule`이면 추론), 수신자 문맥이 대상 하나를 고른 팬아웃 엣지(상속 매퍼 메서드의 `EXECUTES`, 리플렉션 `CALLS`) — 은 엣지가 `INFERRED`여도 확정으로 본다. 팬아웃 엣지를 문맥 없이 전부 따라갔거나 문맥이 대상 여럿과 맞았으면 표시가 없어도 추론 걸음이다. CRUD 매트릭스는 추론 걸음을 거쳐서만 닿은 칸의 끝에 `?`를 붙이고(`R?`, `ru?`. 확정 경로가 하나라도 있으면 붙이지 않는다. 테이블 × 메서드는 `EXECUTES` 자체가 `INFERRED`일 때, 구문·루틴 기준에는 붙이지 않는다. 그래서 `prefixMatch`·`viaArgument`로 이은 `EXECUTES`로만 닿은 칸은 `?`이고, forward로만 닿은 칸은 `?`가 아니다), 영향도는 그런 항목에 `~`와 근거(처음 만나는 추론 엣지)를 붙인다. 표기 설명은 표준 산출물 `crud/README.md`에 있다(F-015-R52·R53).
- **영향도의 forward 경유 표시(2026-10-11).** 영향도는 forward(`callType=forward`인 `CALLS`)를 따라 올라가되(같은 요청 안의 이동), forward를 **거쳐서만** 닿은 항목을 핸들러 자신의 호출 사슬로 닿은 항목과 구분한다: 이름 앞의 `[forward]`(추론과 겹치면 `~ [forward]`), 경로의 넘어간 대상 핸들러 앞의 `[forward]`와 끝의 `(forward: <엣지의 path>)`, 머리 요약·계층 제목의 `직접 a, forward 경유 b`, JSON의 `viaForward`·`forward`(`from`, `to`, `target`). 같은 확실함의 경로 가운데 forward를 거치지 않는 것이 하나라도 있으면 붙이지 않고 그 경로를 적는다(확정 경로가 추론 경로보다 먼저다). redirect는 따라가지 않으므로 표시할 항목이 없다. forward는 여전히 추론 걸음이 아니다(F-015-R77).
- **콜트리의 순서·묶음·접기 보완(3차 재검증).** 같은 줄에서 시작한 호출은 평가 순서(범위가 포함된 쪽 먼저: 엣지 `location`의 끝 위치 순)로 놓는다(F-015-R55). `ambiguous=true`인 `MATCHES` 여럿도 `[ambiguous n]` 한 줄 아래에 묶는다(R56). 접근자 접기는 부수 효과 없는 생성자(나가는 호출이 순수·외부 생성자뿐이고 `EXECUTES`가 없는 `<init>`)와 MyBatis Generator의 `*Example`·`*Example$Criteria` 타입의 메서드도 접는다(R57). 호출자 방향에서 5개 이상의 JSP가 `INCLUDES`로 포함하거나 레이아웃인 JSP는 그 위를 펼치지 않고 `[shared] <JSP> (포함하는 화면 n개)` 한 줄로 낸다(`expandLayouts`로 편다, R58). 구문의 `borrowedFrom=<방언>`은 구문 줄에 `(다른 방언 <방언>에서 보충)`으로 보인다(R59).
- **화면 요청 통계.** 서버 요청이 아닌 요청(외부 URL `external=true`, 정적 페이지 링크: 엔드포인트에 연결되지 않은 `.html`·`.htm` 경로로서 그 파일이 프로젝트에 있거나 요청을 낸 파일이 정적 페이지인 것, 클라이언트 라우트)을 분모에서 빼고 연결을 확정·추론(`crossModule` 포함)으로 나눠 센다(F-015-R54). "DDL에 없는 테이블"에서 `systemCatalog=true`인 테이블은 의심으로 치지 않는다(R60). 영향도는 `dataLoad=true` 구문을 기본으로 빼고 뺀 수를 알린다(R61). 이 통계는 조회 API(`SummaryReport.requestStats`)로도 주고 cli의 "엔드포인트 없음" 힌트가 같은 서버 요청 수를 쓴다(F-015-R74, F-001-R60). `dataLoad=true`인 구문의 `borrowedFrom=<방언>`(고른 방언의 파일에 그 테이블의 적재가 없어 다른 방언 파일의 묶음을 쓴 것. sql)은 매퍼 구문의 보충과 구분한다: `report.md`의 "다른 방언 파일에서 보충한 구문" 절과 뷰어의 "보충한 구문"에 넣지 않고, "데이터 적재 스크립트" 절에 묶음 수와 방언별 수를 한 줄로 적고 파일 이름 뒤에 `(다른 방언 파일: <방언>)`을 붙이며, 콜트리 줄과 영향도(`--include-data-load`)의 구문 이름에도 같은 표시를 붙인다(F-015-R75).

### 3.5 attrs 키

값은 String, Number, Boolean, List\<String>만 쓴다(3.1). boolean 키가 없으면 false로 읽는다(참일 때만 넣는 키가 많다). 여기에 없는 키를 추가할 때는 이 절의 표에 적는다.

#### 공통 규칙 (구현하면서 확정)
- **위치의 끝은 제외다.** `SourceLocation`의 `endLine`·`endColumn`은 마지막 글자의 다음 자리를 가리킨다. 줄만 아는 위치는 열이 0이다.
- **줄 끝은 LF, CRLF, CR 단독이다.** 셋 다 줄 하나로 센다(CRLF는 한 번). 위치의 줄과 줄 번호가 든 ID(`sql:stmt:<파일>:<줄>`, 루틴 안의 구문, `http:request:…@<파일>:<줄>`)가 줄 끝의 종류와 상관없이 같다. sql(F-006-R32. 전에는 CR 단독으로 저장된 파일의 구문이 모두 1줄로 모여 ID가 합쳐졌다), mybatis, springxml, jsp, web의 줄 계산에서 확인했다. [확인 필요] java는 JDT의 줄 계산에 맡긴다(CR 단독을 어떻게 세는지 이 저장소의 코드로는 확인하지 못했다).
- **`ambiguous`는 후보가 둘 이상일 때만 붙는다(4차 수정).** `CALLS`, `MATCHES`, `RENDERS`, `INCLUDES`(`via=sitemesh`)의 `ambiguous=true`는 같은 (출발, 종류, 위치)에서 나가는 후보를 가리지 못해 둘 이상으로 이었다는 뜻이다. 후보를 하나로 고른 엣지에는 붙이지 않는다(F-003-R29, F-012-R45). 전에 후보 하나로 `ambiguous`가 붙던 상속 호출(`inheritedFrom`)은 `INFERRED`만 남는다.
- **`HTTP_REQUEST`의 `path`와 `url`.** `path`는 정규화한 URL 패턴이다(컨텍스트 경로 제거, 쿼리·fragment 제외, 동적 조각은 `{*}`). 노드 ID의 URL 부분과 같고 링커가 엔드포인트와 맞출 때 쓴다. `url`은 소스에 쓴 원문(식)이며 표시용이다. 쿼리 문자열은 `query`에 따로 둔다. JSP 인라인 스크립트에서 온 요청의 `url`도 JSP에 쓴 원문이다(`'${ctx}/order/history.do'`): web이 내장 소스의 치환 목록(`jspSubstitutions`, 3.9)으로 되살린다. 치환 목록이 없거나 읽을 수 없는 내장 소스에서는 `__JSP_EXPR__` 자리가 `${…}`로 나온다(F-010-R14). 미해결 `http-url`의 원문도 같다. `path`와 노드 ID는 되살리지 않는다(정규화한 값 그대로). 예외로 `selfSubmit=true`인 요청(action 없는 폼)은 `path`와 ID의 URL 자리가 `{self}`이고 `url`은 빈 문자열이다. `{self}`는 URL 패턴이 아니므로 `path`로 엔드포인트를 맞추면 안 된다(3.9, F-011-R28).
- **요청의 메서드 후보.** `httpMethod`는 단일 값이다. 한 호출 지점의 URL 후보가 여럿이고 한 URL에 메서드가 여럿 모이면(메서드와 URL의 짝을 모른다) `httpMethod=ANY`로 두고 후보를 `httpMethods`(대문자 List)에 남긴다. 링커는 `httpMethods`가 있으면 그 메서드의 엔드포인트와 메서드를 가리지 않는(`ANY`) 엔드포인트로만 잇고, 없으면 종전대로 모든 메서드와 맞춘다. 후보에 든 메서드의 엔드포인트는 "메서드가 정확히 같은" 것으로 쳐서 `ANY` 엔드포인트보다 앞선다(F-010-R39, F-012-R39).
- **`REFERENCES`의 `columns`·`refColumns`는 컬럼 이름 목록이다.** `columns`는 `from` 테이블, `refColumns`는 `to` 테이블의 컬럼이고 같은 위치끼리 대응한다. DDL에서 참조 컬럼을 생략했고 부모의 PK를 알 수 없으면 `refColumns`가 없다. `JOINS`의 `leftColumns`(`from`)·`rightColumns`(`to`)도 같은 방식이다.
- **`JOINS`의 고정 컬럼.** 조인한 양쪽 테이블 인스턴스의 컬럼 가운데 같은 구문의 조건(ON·WHERE)에서 리터럴이나 바인드와 `=`로 비교된 것을 `leftFixedColumns`(`from`)·`rightFixedColumns`(`to`)에, 그 값을 `leftFixedValues`·`rightFixedValues`에 `COL=값`으로 남긴다(문자열 리터럴은 따옴표를 떼고, 바인드·변수는 `COL=?`). AND로만 이어진 `컬럼 = 상수`와 `컬럼 IN (리터럴 하나)`만 본다. 고정 컬럼이 없는 쪽에는 키를 넣지 않는다. 같은 조인을 같은 주인의 다른 곳(다른 인스턴스 쌍, 다른 전개본)에서 또 보면 양쪽 모두에 있는 고정 컬럼만 남는다(F-006-R13). 루틴 본문에서 레코드·루프 변수의 필드(`r.col`, `v_row.col`)와 비교한 컬럼도 변수와 비교한 것으로 보아 `COL=?`로 남긴다(F-008-R16). 값에 `${…}` 치환이 든 자리는 내부 표식(`__DYNAMIC__`)이 아니라 `${…}` 원문으로 적는다(F-006-R26).
- **함수로 감싼 조인 키(3차 수정).** 등치의 한쪽이나 양쪽이 단일 컬럼 하나만 감싼 형 변환·정리 함수(중첩 포함. 컬럼 말고 다른 인자는 리터럴)면 그 컬럼으로 `JOINS`를 만들고 `wrapped=true`와 감싸인 쪽(`leftWrapped`·`rightWrapped`)을 남긴다. 연산식, 값을 바꾸는 함수, 컬럼이 둘 이상 든 함수, 사용자 정의 함수로는 만들지 않는다. 같은 컬럼 쌍을 감싸지 않은 조건으로도 봤으면(같은 구문, 같은 주인의 다른 전개본) 한 엣지로 합치고 세 키를 넣지 않는다. `wrapped=true`인 조인은 양쪽 컬럼의 타입·길이가 다를 수 있다(F-006-R29. 대상 함수는 F-006 spec 5절 "함수로 감싼 조인 키").
- **추론한 `REFERENCES`의 방향.** `directionKnown=false`이면 어느 쪽이 부모인지 정하지 못한 것이므로 `from`→`to` 방향에 뜻이 없다(양쪽 다 키이거나 양쪽 다 키가 아님). 키 정보가 없는 쪽은 부모로 판정하지 않는다. 조인으로 정한 방향을 거두는 경우가 둘 있다(3차 수정): 같은 테이블 쌍의 DDL 관계가 반대 방향이면 `declaredOpposite=true`(F-018-R13), 자식으로 판정된 쪽 컬럼이 셋 이상의 테이블과 방향 미상으로 조인되는 "사실상 키"면 `pseudoKey=true`(F-018-R14)를 붙이고 `directionKnown=false`·`cardinality=unknown`으로 기록한다. 반대로 조인만으로는 방향을 정하지 못했지만 같은 테이블 쌍에 DDL `REFERENCES`가 한 방향으로만 있으면 그 방향을 따르고 `directionFrom=DDL`을 남긴다(4차 수정, F-018-R15): 이때 `directionKnown=true`지만 `cardinality`는 조인으로 정한 값 그대로라 `unknown`이나 `1:1`일 수 있다. 소비자는 방향은 그리되 N:1로 단정하는 표기는 `cardinality`를 보고 정한다. `declaredOpposite`인 관계는 근거가 어긋나므로 DDL 방향을 따르지 않는다. 뷰가 낀 조인으로는 기본으로 추론 관계를 만들지 않고, 옵션 `link.inferViewRelations=true`(3.8)로 켜도 뷰가 낀 관계는 항상 `cardinality=unknown`, `directionKnown=false`다(뷰는 키 정보가 없어 자식으로 두면 방향이 뒤집힐 수 있다. F-018-R11).
- **같은 테이블의 중복 정의.** 같은 테이블·뷰의 `CREATE`가 여러 파일에 있으면(방언별 DDL 폴더, 설치 스크립트와 덤프) 한 테이블의 `location`, 컬럼, 키, `indexes`, DDL `REFERENCES`는 모두 **선택된 한 파일**(과 그 방언·공통 경로의 `ALTER`·`CREATE INDEX`·`COMMENT`)에서 온다. 고르는 순서(4차 수정에서 바꿨다. F-007-R18·R23): upgrade·patch·migration 스크립트가 아닌 파일 → 방언 순위(아래 "방언 변형 선택")가 앞선 방언의 파일 → 경로순 첫 파일이다. 방언 순위는 프로젝트에 하나라, 가장 앞선 방언의 파일에 정의가 없는 테이블만 다음 순위의 방언 파일에서 가져온다(보충). 고르지 않은 방언의 파일 가운데 정의를 하나도 쓰지 않은 파일은 통째로 고르지 않은 파일이고, 그 파일의 `ALTER`·`CREATE INDEX`·`COMMENT`, 데이터 적재 묶음, DML 구문 노드를 만들지 않는다(F-007-R27). DDL은 `CREATE`를 먼저, 그다음 `ALTER`·`COMMENT`·`CREATE INDEX`를 반영하고, `CREATE TABLE`이 다른 파일에 있는 테이블에 대한 upgrade·patch·migration 스크립트의 `ALTER TABLE`은 이미 반영된 변경으로 보고 적용하지 않는다. 예외로 그 스크립트의 외래 키 추가는 자식 컬럼과 부모 테이블(적어 준 참조 컬럼)이 현재 정의에 있으면 적용하고 `REFERENCES`에 `fromUpgrade=true`를 붙인다(F-007-R22). 그 테이블의 현재 정의 파일이 다른 외래 키를 하나라도 담고 있거나 그 테이블에 upgrade가 아닌 외래 키가 이미 있으면(현재 정의가 외래 키를 적는 스키마인데 이것만 빠져 있다) 그 엣지는 `INFERRED`이고 `note="현재 정의에 없음"`이 붙는다. 현재 정의가 외래 키를 전혀 담지 않으면 `RESOLVED`다(F-007-R26). 소비자는 `fromUpgrade`와 신뢰도를 함께 본다. 버린 정의와 적용하지 않은 `ALTER`는 진단으로 알린다(3.7): 방언별 여러 벌에서 한 벌을 고른 것은 요약 `DIALECT_VARIANT_DDL` 한 건, 방언 이름이 없는 파일끼리의 중복은 `DUPLICATE_OBJECT_NAME`(INFO), 적용하지 않은 `ALTER`는 `DDL_NOT_APPLIED`다(F-007-R14·R15·R19·R27).
- **방언 변형 선택(4차 수정. 결정의 근거와 대안은 [ADR-0010](../../decisions/0010-select-one-dialect-variant-by-target-first-ranking.md), 제안 상태).** 매퍼(mybatis)와 SQL 스크립트(sql: DDL, 데이터 적재)가 방언별 파일로 여러 벌 있으면 전부 합치지 않고 한 벌만 쓴다. 방언의 순위는 프로젝트에 하나다: ① 명시한 값(`--dialect`, `mybatis.dialectVariant=<방언>`. sql은 `--dialect`가 먼저고 mybatis는 `mybatis.dialectVariant`가 먼저다. `auto`·`all`과 모르는 값은 건너뛴다) ② 프로젝트 설정이 가리키는 방언이 대상 방언(oracle, db2, postgres)이면 그 방언 ③ Oracle ④ DB2 ⑤ PostgreSQL(F-019-R4. 전에는 대상 외 방언이었다) ⑥ Oracle 계열(Tibero, Altibase. 설정이 이 가운데 하나를 가리키면 그것이 먼저) ⑦ 프로젝트 설정이 가리키는 대상 외 방언 ⑧ 그 밖의 방언(mybatis와 sql이 같은 순서를 쓴다: 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 이름의 사전순으로 먼저, 나머지 방언이 사전순으로 그 뒤다. sql의 "방언을 읽지 못한 파일"은 경로순으로 그 앞이나 뒤에 선다. F-007-R28, F-009-R26). 그래서 프로젝트 설정이 대상 외 방언(전자정부 템플릿의 `Globals.DbType=mysql`)을 가리켜도 대상 방언의 파일이 있으면 그쪽을 쓴다. `--dialect`는 `oracle`·`db2`·`postgres`·`auto`만 받으므로 대상 외 방언으로 분석하려면 `--opt mybatis.dialectVariant=<방언>`을 준다(mybatis와 sql이 둘 다 읽는다). 방언은 파일 이름(마지막 확장자 제외)을 글자·숫자가 아닌 문자로 나눈 토큰을 뒤에서부터, 그다음 폴더 이름(이름 전체가 방언 토큰일 때만)을 가까운 것부터 보아 읽는다. 표에 없는 토큰은 끝의 숫자(버전)를 떼고 한 번 더 본다(`mysql55`). sql은 경로에 방언 이름이 없으면 Oracle·DB2 확장자 힌트(`.pks`, `.db2` 등)와 내용 특징(`CREATE MEMORY TABLE`은 hsql, 백틱·`ENGINE=`·`AUTO_INCREMENT`는 mysql, 달러 인용 `$$`·`LANGUAGE plpgsql`·`ALTER TABLE ONLY`·`COPY … FROM stdin`·`serial` 타입 등은 postgres, `VARCHAR2`·`NUMBER(`는 oracle 등. F-007-R24, F-019-R2)으로 읽는다. 프로젝트 설정의 방언은 `.properties`·`.yml`의 `Globals.DbType`, `jdbc.type`, `mybatis.configuration.database-id`, `spring.datasource.url`(JDBC URL), `spring.datasource.driver-class-name`에서 읽고(`src/test` 아래와 `${…}`가 든 값은 보지 않는다), 파일·프로파일마다 엇갈리면 쓰지 않는다. 토큰 표와 설정 키는 mybatis와 sql이 같은 내용을 따로 구현한다(모듈끼리 의존하지 않는다. 양쪽 테스트에 같은 표를 둔다). 무엇을 골랐는지는 요약 진단 `DIALECT_VARIANT_MAPPERS`·`DIALECT_VARIANT_DDL`, 요청한 방언의 파일이 없으면 `DIALECT_NOT_FOUND`, 여러 벌은 아니지만 스크립트가 대상 외 방언뿐이면 `DIALECT_NOT_TARGET`으로 알린다(3.7. F-007-R18~R25, F-009-R20·R22·R24).
- **데이터 적재 구문.** SQL 스크립트 파일에서 한 테이블에 넣는 리터럴 INSERT(값이 리터럴·시퀀스·함수 호출·상수 식뿐이고 서브쿼리가 없는 것. 다중 행 `VALUES`, `INSERT ALL … SELECT … FROM DUAL`, `INSERT … SELECT 리터럴 FROM DUAL` 포함)는 그 파일이 **데이터 적재 파일**이면 구문마다가 아니라 (파일, 테이블)마다 `SQL_STATEMENT` 노드 하나로 묶는다. 적재 파일은 (a) 한 테이블의 리터럴 INSERT가 10건 이상인 묶음이 하나라도 생긴 파일이거나, (b) 구문이 리터럴 INSERT와 세션·트랜잭션 명령(`COMMIT`, `SET` 류), 조건 없는 `DELETE FROM T`·`TRUNCATE`뿐이고 리터럴 INSERT가 10건 이상인 파일이다(3차 수정에서 파일 단위 판정으로 바꿨다. F-006-R24). 적재 파일의 리터럴 INSERT는 건수와 상관없이 테이블별로 묶는다(1건이어도 `dataLoad=true`, `rowCount=1`). 묶음 노드는 `dataLoad=true`, `rowCount`(묶은 행 수)를 갖고, ID는 그 테이블의 첫 INSERT 줄, `sql`은 묶음의 첫 구문이며, 나가는 엣지는 테이블·컬럼 `WRITES`(`op=INSERT`)와 값 자리에서 부른 사용자 정의 함수의 `CALLS`뿐이다. `INSERT ALL`이 여러 테이블에 넣으면 첫 대상 테이블의 묶음에 든다. 적재 파일의 조건 없는 `DELETE FROM T`·`TRUNCATE`(초기화 구문)는 묶음이 아니라 구문 하나의 노드지만 `dataLoad=true`, `rowCount=0`을 붙인다(`statementType=DELETE`). 적재 묶음만 세려면 `statementType=INSERT`로 가른다. 적재 파일이 아닌 파일(DDL·COMMENT·다른 DML과 섞인 소량의 INSERT)은 구문마다 노드다. 적재 파일마다 진단 `DATA_LOAD_GROUPED` 1건을 남긴다(F-006-R15). 같은 테이블의 적재 묶음이 방언이 다른 파일 여러 벌에 있으면 "방언 변형 선택"의 순위로 한 방언의 것만 노드가 되고, 묶음을 모두 버린 파일은 초기화 구문의 노드도 만들지 않는다(F-007-R20). 고르는 단위는 파일이 아니라 **테이블**이다(5차 수정, F-007-R29): 방언별 스크립트가 여러 벌인 프로젝트에서 테이블마다 그 테이블의 묶음을 가진 방언 파일들 가운데 순위가 가장 앞선 방언의 묶음만 남기고(그 방언의 파일이 여럿이면 모두 남긴다), 남은 묶음이 고른 방언(요약 진단의 "<방언> 파일을 사용")의 파일 것이 아니면 `borrowedFrom=<방언>`을 붙인다. 그래서 고른 방언의 파일에 적재가 없는 테이블의 초기 데이터는 다른 방언 파일의 묶음으로 남는다. 고른 방언이 아니고 테이블 정의도 쓰이지 않은 파일의 묶음은 그 테이블이 DDL에 정의돼 있을 때만 남긴다(정의가 없는 테이블의 `declared=false` 노드를 방언 파일마다 만들지 않는다). 초기화 구문은 그 테이블의 묶음이 남은 파일의 것만 노드가 되고, 다른 방언 파일의 것이면 같은 `borrowedFrom`이 붙는다. 방언을 읽지 못한 파일의 묶음은 고르는 대상이 아니다(항상 노드가 된다). 매퍼 구문 등 내장 소스의 INSERT는 묶지 않는다. 소비자는 이 노드를 기본으로 뺀다(3.4 "소비자가 지키는 규칙").
- **프로퍼티 자리표시자.** `${키}`·`${키:기본값}`이 든 값(엔드포인트의 `path`, 뷰 리졸버 빈의 `property.prefix`·`property.suffix`, 참조한 빈 이름)은 분석기가 원문 그대로 남기고 링커가 프로젝트의 `.properties`·`.yml` 값으로 푼다(F-012-R24). 참조하는 파일이 테스트 소스가 아니면 테스트 소스의 프로퍼티 파일은 보지 않고, 같은 모듈 루트에 키가 있으면 그 모듈의 값만 본다. 값이 하나로 모이면 그 값, 서로 다른 값이 둘 이상이면(파일·프로파일마다 다름) 풀지 않고, 어디에도 없으면 기본값을 쓴다. 푼 값은 원문을 덮어쓰지 않고 따로 남긴다(`resolvedPath`, 새 엣지의 `resolvedFrom`). 메서드의 `viewNames`·`redirects`·`forwards`에서 `@Value("${키}")`가 붙은 필드를 이어 붙인 자리도 `{*}`가 아니라 자리표시자 원문이다(`redirect:${adminPath}/login`. F-004-R23). 링커는 redirect·forward 대상의 자리표시자를 값으로 치환해 엔드포인트와 맞추고, 값을 찾지 못한 자리표시자는 알 수 없는 조각(`{*}`)으로 읽는다(F-012-R33). 5차 수정: 뷰 이름 연결식의 "프로퍼티를 읽는 꼴의 호출"(`<수신자>.<get·getProperty·getString·getValue·getConfig>("키")`: 수신자를 적었고 인자가 키 꼴의 문자열 상수 하나. `PropertiesFileUtil.getInstance().get("app.name")`)도 그 자리에 `${키}`를 남긴다(F-004-R26). 그 키가 정말 프로퍼티인지는 링커가 값을 찾아 확인하고, 못 찾으면 `{*}`로 읽는다. 뷰 이름의 디렉터리 자리 전체가 자리표시자 하나면(`/${app.name}/login.html`) 링커는 그 값의 디렉터리에 있는 뷰로만 잇는다(`RENDERS`의 `dynamicSegment`·`narrowedBy=property`. F-012-R47). 프로퍼티 값을 찾을 때 자기 자신을 가리키는 값(`app.name=${app.name}`, `app.name=@app.name@`: 빌드 도구의 리소스 필터링 자리)은 값으로 치지 않는다(F-012-R47).
- **방언별 매퍼.** 한 namespace가 서로 다른 DB 방언의 매퍼 파일 둘 이상에 정의돼 있으면(방언을 읽는 규칙과 순위는 위 "방언 변형 선택") mybatis는 id마다 한 방언의 정의만 쓴다. 고르지 않은 정의로는 노드도 내장 소스도 만들지 않는다. 고른 방언은 노드의 `dialectVariant`와 내장 소스의 `dialect`(3.9)에 남고, 분석 한 번에 진단 `DIALECT_VARIANT_MAPPERS` 1건이 나온다. `all`은 옵션 `mybatis.dialectVariant`(3.8)로 준다(F-009-R15). 감지한 방언(순위 순) 가운데 구문을 하나라도 고른 첫 방언이 대표 방언이고, 대표 방언의 파일에 없어 다른 방언 파일에서 가져온 구문에는 `borrowedFrom=<방언>`이 붙는다. 대표 방언의 파일에서 XML 주석으로 뺀 id는 다른 방언에서 보충하지 않는다(구문 노드를 만들지 않는다. F-009-R25).
- **벤더(서드파티) 파일.** web과 jsp는 벤더 폴더(`node_modules`·`bower_components`·`webjars`, 정적 자원 폴더 아래의 `lib`·`plugins` 등, 알려진 라이브러리 이름이나 이름 뒤에 버전이 붙은 폴더·파일)를 벤더로 판정해 내용을 분석하지 않는다. web은 여기에 `plugins/<이름>/…`, 빌드 산출물 이름(`.debug.`, `.pack.`, `.bundle.`), 큰 생성물, 내용으로 본 압축 판정을 더 보고(3차 수정), 머리 주석으로는 정식 라이선스 문구·`@license`·라이선스 주소가 있으면서 프로젝트 정합성 검사를 통과하지 못한 스크립트만 벤더로 본다(4차 수정에서 "라이선스 배너가 있는 스크립트"를 좁혔다. F-010-R40). 노드는 만들고 `skipped=vendor`를 붙이며(`JS_MODULE`, `JSP_PAGE`) 진단 `FILE_SKIPPED`를 폴더마다 1건 남긴다(메시지에 판정 근거가 붙는다, 3.7). 규칙의 전체 목록은 F-010 spec "벤더 판정"과 F-011 spec에 있고 옵션은 `web.skipVendor`·`web.vendorPatterns`(3.8)다.
- **상수로 채운 복합 키.** 조인 컬럼만으로는 어느 쪽도 키 전체가 아닐 때, 한쪽의 조인 컬럼이 PK(또는 UNIQUE)의 일부이고 나머지 키 컬럼이 그 구문에서 상수로 고정돼 있으면(`JOINS`의 고정 컬럼) 그쪽을 부모로 하는 `N:1`로 확정하고 `fixedColumns`·`fixedValues`를 남긴다(F-018-R9). 공통코드 테이블 조인(`C.GROUP_CD = 'ORDER_STATUS' AND C.CODE = O.ORDER_STATUS`)이 이 꼴이다. 근거 수는 이 조건을 채운 구문만 센다. 3차 수정부터 리터럴로 고정된 컬럼만 근거로 쓴다: 바인드·변수와의 등치(`COL=?`)는 그 구문이 한 행을 고른다는 뜻일 뿐 그 테이블이 부모라는 근거가 아니다. 그래서 `fixedValues`에는 `COL=?`가 들어가지 않는다(F-018-R12).
- **형제 조인은 관계로 만들지 않는다.** 방향을 정하지 못한(`unknown`) 묶음의 양쪽 조인 컬럼이 같은 부모 테이블의 같은 컬럼을 참조하면(DDL `REFERENCES` 또는 같은 실행에서 `N:1`로 확정된 추론) 추론 `REFERENCES`를 만들지 않는다(F-018-R10). 진단은 없고 `JOINS` 엣지는 그대로 있다.
- **CRUD에서 `MERGE`는 C와 U다.** `WRITES`의 `op`는 `INSERT`(C), `UPDATE`(U), `DELETE`(D), `MERGE`(C+U)다. `op`가 없으면 구문의 `statementType`으로 정하고, 그래도 모르면 종류를 모르는 쓰기(`W`)로 둔다. `R`은 테이블 수준 `READS`가 있을 때만 붙는다(3.4 "소비자가 지키는 규칙"): 단순 `DELETE`·`UPDATE`는 `D`·`U`, `MERGE`의 대상 테이블은 `CU`다.
- **미해결 노드의 위치.** `UNRESOLVED` 노드의 `location`은 처음 등록된 지점 하나뿐이다(같은 원문이 여러 곳에서 나와도 노드는 하나다). 참조 지점을 보여 줄 때는 엣지의 `location`을 쓴다.
- **뷰·시노님 정규화.** SQL 분석은 테이블인지 뷰인지 모르므로 항상 `db:table:X`로 참조한다. 링커(F-012)가 그 참조 스텁(`declared=false`)을 `GraphModel.replaceNode`로 `db:view:X` 또는 시노님의 최종 대상에 합친다. 시노님을 거쳐 옮긴 엣지에는 `attrs.viaSynonym`(처음 거친 시노님 이름)이 붙는다. 스키마로 보이지 않는 한정자를 떼고 맞춘 루틴 참조에는 `attrs.strippedQualifier`가 붙고 신뢰도가 `INFERRED`가 된다. 정의를 찾지 못한 `db:table:`·`db:routine:` 참조는 `declared=false` 노드로 남는다(마무리 단계가 `UNRESOLVED`로 바꾸지 않는다).
- **`dialect` 값.** 노드의 `dialect`는 `Dialect` enum 이름 그대로 대문자 `ORACLE`, `DB2`, `POSTGRES` 가운데 하나다(`POSTGRES`는 F-019). 판정하지 못하면 키를 넣지 않는다(`AUTO`는 쓰지 않는다). 내장 소스 attrs의 `dialect`(3.9)는 이와 달리 생산자가 넘기는 힌트 문자열이다. 구문 노드의 `dialect`는 그 구문에서 판정한 것만이다(4차 수정, F-006-R37): SQL 파일의 구문은 파일의 방언(경로의 방언 이름, 없으면 내용 특징), 내장 소스의 구문은 생산자가 넘긴 `attrs.dialect`, 없으면 구문 특징이 확실할 때다. `--dialect` 옵션은 구문에 찍지 않는다(옵션은 방언별 여러 벌에서 고를 방언, 판정이 없는 루틴·블록의 문법 선택, 줄 처음 `#` 주석 판정에 쓴다). 대상 외 방언으로 판정한 파일(경로에 `mysql`·`altibase`·`hsql` 등의 이름이 있거나 본문이 MySQL)과 생산자가 대상 외 방언이라고 알린 내장 소스의 구문에는 구문 특징(`SYSDATE` 등)으로 Oracle·DB2를 추측해 넣지 않는다(F-006-R28). 구문 특징으로 판정할 때도 Oracle·DB2에 없는 MySQL 표기(괄호가 붙은 `SYSDATE()`, 따옴표 없는 수를 쓴 `INTERVAL [-]n MONTH`, `ADDDATE(`)가 하나라도 있으면 판정하지 않는다(키 없음. 5차 수정, F-006-R42). PostgreSQL에만 있는 표기(달러 인용, `::`, `ILIKE`, 괄호가 붙은 `nextval(`)가 있으면 Oracle·DB2로 판정하지 않고, PostgreSQL 특징의 점수가 가장 높으면 `POSTGRES`다(F-019-R3).
- **읽기 방식(F-019, [ADR-0012](../../decisions/0012-pass-sql-syntax-to-parsers.md)).** sql은 SQL 텍스트마다 읽기 방식(기본 = Oracle·DB2 합집합, MySQL, PostgreSQL)을 하나 정해 렉서·분할기·파서·루틴 파서에 넘긴다. SQL 파일은 파일의 방언(경로의 방언 이름 → 내용 특징), 내장 소스는 생산자가 넘긴 `attrs.dialect` → 프로젝트 방언(`--dialect`, `mybatis.dialectVariant`, 프로젝트 설정, 스크립트에서 고른 방언) → 그 텍스트의 PostgreSQL 내용 특징 순이다. 방언끼리 겹치는 표기(`$$`, 이름 안의 `#`, 줄 처음 `#` 주석, 백슬래시 이스케이프)와 한 방언에만 있는 문장(PL/pgSQL)은 그 읽기 방식에서만 그렇게 읽는다.
- **빈으로 정의된 타입.** 어떤 타입이 빈인지는 `DEFINES_BEAN` 엣지(빈의 `class`로 이어진 타입 포함)나 `JAVA_TYPE`의 `beanName`으로 판단한다. `stereotype`으로 판단하지 않는다: `filter`·`interceptor`는 타입의 역할이지 빈이라는 뜻이 아니다. 링커의 주입 후보 계산도 이 기준을 쓴다(F-012-R27).
- **`INJECTS`의 `candidates`.** 주입 대상에 대입할 수 있는 빈 타입의 **`JAVA_TYPE` 노드 ID**(`java:type:<바이너리 이름>`) 목록이다. java가 qualifier → 필드명 → `@Primary` → 전부 순으로 고른다. 후보가 주입 대상 자신 하나뿐이면 넣지 않는다. 후보가 정확히 하나면 링커(주입 후보, order 240)가 그 타입으로 가는 `INFERRED` `INJECTS`(`resolvedFrom`에 선언된 대상 타입 ID. `via`·`field`·`qualifier`는 원래 엣지 것을 옮기고 `matchedBy`는 없다)를 더한다. 후보가 여럿이면 엣지는 선언된 대상 타입으로 가는 것 하나뿐이고 후보는 attr에만 있다(F-012-R12). 구현 후보가 하나로 정해진 주입은 항상 엣지로 드러난다.
- **JDK 타입 판정(java).** `java.includeJdkTypes`, `java.externalCalls`(3.8)가 말하는 "JDK 타입"은 이름이 아니라 출처로 정한다(F-002-R13). 바인딩이 해석된 타입은 JDK 시스템 모듈(`java.*`, `jdk.*` 모듈)에서 온 것만 JDK다. 출처를 알 수 없을 때만(미해석·복구된 바인딩) 이름 규칙을 쓴다: `java.`, `jdk.`, `sun.`, `com.sun.`으로 시작하거나 JDK에 실제로 든 `javax.`·`org.` 패키지 목록(`javax.sql`, `javax.naming`, `org.w3c.dom` 등, `JdkTypes`)에 속하는 이름이다. `javax.servlet`, `javax.persistence` 같은 Java EE 패키지와 `jakarta.*`는 라이브러리라서 기본 옵션에서도 external 참조와 호출로 기록된다. 판정 기준은 분석기를 실행하는 JDK다.
- **풀기 전의 대상.** 링커가 기존 엣지를 근거로 더 구체적인 엣지를 더할 때는 원래 엣지를 지우지 않고(엣지 삭제 API가 없다) 새 엣지에 `resolvedFrom`(원래 `to`), `resolvedSource`(원래 `from`)를 남긴다. 미해결 `EXECUTES` 엣지 e는 같은 출발·같은 위치에 `resolvedFrom = e.to`인 `EXECUTES`가 있으면 풀린 것이다(`shortId`·`prefixMatch` 공통). 접두로 푼 경우, 그 미해결 노드로 들어오는 엣지가 전부 풀렸으면 링커가 노드에 `resolvedBy=prefixMatch`를 남긴다(F-012-R42).

#### 노드 attrs

| 대상 | 키 | 값 | 쓰는 모듈 |
|---|---|---|---|
| 모든 노드 | `declared` | boolean. 정의를 본 노드면 true, 참조만 됐으면 false | core |
| FILE | `fileType` | 4.2의 `FileType` 이름 | core |
| FILE (Spring XML) | `imports`, `importedFiles`, `componentScan`, `propertyLocations`, `mybatisScan`, `mybatisMapperLocations`, `mybatisTypeAliasesPackage` | List. `imports`는 원문, `importedFiles`는 프로젝트에서 찾은 상대 경로 | springxml |
| | `mybatisConfigLocation` | String | springxml |
| FILE (web.xml) | `servletMappings`, `dispatcherServlets`, `servletContextConfigLocations`, `contextConfigLocations` | List | springxml |
| FILE (Tiles 설정) | `tilesDefinitions` | List. `<정의 이름>=<template JSP 경로>`. `extends`를 푼 최종 값이고 없으면 빈 값, 와일드카드 정의는 이름 원문 그대로다. 항목은 첫 `=`에서 자른다. 경로는 설정에 적힌 그대로(웹 루트 기준)다. 링커(뷰 이름 → JSP, F-012-R30)와 web(전역 스크립트의 범위, F-010-R33)이 읽는다(F-011-R22) | jsp |
| | `tilesAttributes` | List. `<정의 이름>=<조각 JSP 경로>`. 정의 하나에 여러 줄일 수 있다. 물려받은 것을 포함하고, 덮은 것은 덮은 값만, 값이 다른 정의 이름이면 그 template이며 `{n}`은 그대로 둔다 | jsp |
| UNRESOLVED | `category` | 3.6의 분류. 분석기·링커가 만든 노드에는 만든 모듈이 넣고, 마무리 단계가 만든 노드에는 core가 `expectedPrefix`에서 유도해 넣는다(`Ids.danglingCategory`, F-001-R38): `mybatis:stmt` → `mybatis-statement`, `java:method` → `java-call`, `java:type` → `java-type`, `db:routine` → `db-routine`, `spring:bean` → `spring-bean-ref`, `js:module` → `js-module`, `js:function` → `js-call`, `jsp:page` → `jsp-include`, `http:endpoint` → `http-endpoint`, 그 밖에는 접두사의 `:`을 `-`로 바꾼 값(`mybatis:fragment` → `mybatis-fragment`, `db:table` → `db-table`) | 각 모듈, core |
| | `expectedPrefix` | 엣지가 가리키던 ID의 접두사(끝의 콜론 없음). 마무리 단계가 만든 노드에만 있다(마무리 단계가 만든 노드인지는 이 키의 유무로 가른다) | core |
| | `resolvedBy` | 값 `prefixMatch`: 그 미해결 노드(`mybatis-statement`)로 들어오는 접두 `EXECUTES`가 전부 풀렸다(공통 규칙 "풀기 전의 대상"). 소비자가 미해결 집계에서 뺄 수 있다(F-012-R42) | link |
| JAVA_TYPE | `typeKind` | `class`, `interface`, `enum`, `record`, `annotation` | java |
| | `modifiers`, `typeParameters` | List. `modifiers`는 소스에 쓴 수식어만 | java |
| | `anonymous`, `local` | boolean | java |
| | `enclosingMethod` | 로컬·익명 타입을 감싼 메서드의 노드 ID | java |
| | `external` | 소스가 없는 타입이면 true. java가 직접 참조한 외부 타입에는 java가 붙이고, 다른 분석기(springxml, mybatis)가 참조만 만들고 끝내 선언되지 않은 타입에는 링커(외부 참조 표시, order 90)가 붙인다 | java, link |
| | `stereotype` | `controller`, `service`, `repository`, `component`, `configuration`, `mapper`, 그리고 `filter`(서블릿 필터: `Filter` 구현, `OncePerRequestFilter` 등 필터 기반 클래스 상속), `interceptor`(Spring MVC `HandlerInterceptor` 구현·`HandlerInterceptorAdapter` 상속). 프로젝트 상위 클래스를 거친 것도 포함한다(F-004-R18). **`filter`·`interceptor`는 빈이라는 뜻이 아니다**: 빈인지는 `beanName`이나 `DEFINES_BEAN`으로 판단한다. 소비자가 요청 처리의 진입점으로 쓴다 | java |
| | `restController` | boolean (`stereotype=controller`와 함께) | java |
| | `beanName` | 스테레오타입 빈의 이름 | java |
| JAVA_TYPE, JAVA_METHOD, JAVA_FIELD | `synthetic` | 소스에 없는 멤버(3.3)면 true | java |
| | `lombok` | Lombok이 만들 멤버면 그것을 만들게 한 애너테이션의 단순 이름(`Getter`, `Setter`, `Data`, `Value`, `NoArgsConstructor`·`AllArgsConstructor`·`RequiredArgsConstructor`, `Builder`, `SuperBuilder`, `ToString`, `EqualsAndHashCode`, `With`, 로그 애너테이션 `Slf4j`·`Log4j2`·`Log`·`CommonsLog` 등). `synthetic=true`와 함께. 엣지의 `lombok`(boolean)과 값의 타입이 다르다 | java |
| | `external` | 어느 분석기도 선언하지 않은 참조(`declared=false`)에 `external` 키가 없으면 링커(외부 참조 표시, order 90)가 true를 붙인다(F-012-R14). 선언된 노드, 분석기가 `external`을 이미 정한 노드(값이 false여도), `UNRESOLVED` 노드는 건드리지 않는다. 프로젝트 소스에 있어야 하는데 분석 범위에서 빠진 타입(소스 루트 밖, 파싱 실패)도 이 표시를 받는다(7절) | link |
| JAVA_METHOD | `modifiers`, `parameterTypes`, `parameterNames` | List | java |
| | `returnType`, `signature` | String. `signature`는 표시용 | java |
| | `constructor`, `varargs` | boolean | java |
| | `typeParameters`, `throws` | List | java |
| | `external`, `signatureUnknown` | 소스가 없는 타입의 메서드면 `external=true`. 매개변수를 모르는 `(?)` 메서드면 `signatureUnknown=true` | java |
| | `httpMappings` | List. `GET /order/list` 형식 | java |
| | `viewNames` | List. 핸들러가 돌려주는 뷰 이름. 모르는 부분은 `{*}`. 반환식이 프로젝트 메서드의 호출이면(`return form(article, model);`) 호출된 메서드(핸들러·뷰 이름을 조립하는 헬퍼)의 뷰 이름을 전이적으로 합친다. 값을 정하지 못하면 미해결 `RENDERS`(분류 `view`)다(F-004-R14). 도우미의 반환식에 매개변수가 섞였으면 호출 지점의 인자를 그 자리에 대입한 이름이 들어간다(인자가 상수면 그 값, 아니면 `{*}`. 4차 수정, F-004-R25). 뷰 이름이 "리터럴 + 지역 변수"이고 그 변수에 대입된 값이 모두 문자열 상수면 값의 조합마다 이름이 들어간다(F-004-R21). `@Value("${키}")` 필드를 이어 붙인 자리와 프로퍼티를 읽는 꼴의 호출(`X.get("키")` 등. 5차 수정, F-004-R26)의 자리는 자리표시자 원문이다(공통 규칙 "프로퍼티 자리표시자") | java |
| | `viewNameCandidates` | List. `viewNames` 가운데 지역 변수 값의 후보를 펼쳐 만든 이름(조합이 둘 이상일 때만). 링커가 그 이름의 `RENDERS`를 `INFERRED` + `candidate=true`로 만든다(F-004-R21, F-012-R32) | java |
| | `viewNameLines`, `redirectLines`, `forwardLines` | List. `viewNames`·`redirects`·`forwards`와 같은 순서·같은 길이의 호출 지점 `줄:열`(둘 다 1부터. 파일은 메서드의 파일). 뷰 이름의 자리는 반환문·`ModelAndView` 생성식·`setViewName` 호출이고, 다른 메서드에서 합쳐 온 뷰 이름은 이 메서드 안의 그 호출 지점이다. 같은 값이 여러 번 나오면 처음 나온 자리다. 링커가 `RENDERS`·redirect·forward `CALLS`의 위치로 쓴다(F-004-R19, F-012-R28) | java |
| | `statementIdLiterals`, `statementIdLiteralLines` | List. 라이브러리 메서드 호출에 인자로 넘긴 "구문 ID처럼 보이는 문자열 상수"(식별자 조각이 `.`으로 둘 이상 이어지고 공백이 없다. 나온 순서, 중복 없이)와 같은 순서·같은 길이의 `줄:열`. `SqlSession` 계열 호출의 구문 ID, 단언, 구문을 찾아만 보는 호출의 인자는 넣지 않는다. 구문 ID가 아닌 문자열(메시지 코드 등)도 들어 있는 중간 정보다: 링커가 읽어 그 ID의 매퍼 구문이 실제로 있을 때만 `EXECUTES`(`viaArgument=true`)를 만든다(4차 수정, F-004-R24, F-012-R43) | java |
| | `redirects`, `forwards` | List. 그 메서드의 코드가 서블릿 API로 넘기는 경로 패턴이다: `redirects`는 `response.sendRedirect(<식>)`, `forwards`는 `getRequestDispatcher(<식>).forward(..)`·`.include(..)`(F-004-R13). 문자열 상수는 값으로, 모르는 부분은 `{*}`로 두고, 맨 앞의 컨텍스트 경로(`request.getContextPath()`)와 첫 `?`·`#` 뒤(쿼리·fragment)는 뗀다(컨텍스트 경로뿐이면 `/`). 나온 순서대로 중복 없이 넣는다. 핸들러가 아닌 메서드(인터셉터, 필터, 서블릿, Spring Security 인증 핸들러)에도 넣는다. `redirects`에는 형 변환한 응답의 `sendRedirect`, `encodeRedirectURL(<식>)`으로 감싼 경로, Spring Security `RedirectStrategy.sendRedirect(request, response, <식>)`, Shiro `WebUtils.issueRedirect(..)`·`WebUtils.toHttp(response).sendRedirect(<식>)`의 경로도 들어간다(F-004-R17). 경로를 전혀 알 수 없으면(상수 부분이 없으면) 넣지 않고 java가 미해결 `CALLS`(분류 `http-endpoint`, `callType=redirect` 또는 `forward`)를 남긴다. 링커(뷰 연결)가 읽어 `CALLS`·`RENDERS`를 만든다(3.4) | java |
| | `beanType` | `@Bean` 메서드의 반환 타입 FQN | java |
| JAVA_FIELD | `type`, `modifiers` | 타입 원문, List | java |
| | `enumConstant` | boolean | java |
| | `constantValue` | 컴파일 타임 상수 값(String) | java |
| SPRING_BEAN | `class` | 구현 클래스 FQN | java, springxml |
| | `scope`, `primary` | String, boolean | java, springxml |
| | `property.<이름>` | 프로퍼티 값. String, 컬렉션이면 List(map·props는 `키=값`). java는 뷰 리졸버의 `prefix`·`suffix`와 `@Bean` 메서드의 setter 상수 값 | java, springxml |
| | `beanType`, `source`, `stereotype` | `beanType`은 노출 타입 FQN, `source`는 `stereotype` 또는 `beanMethod` | java |
| | `abstract`, `parent`, `factoryBean`, `factoryMethod`, `initMethod`, `destroyMethod`, `autowire`, `profile` | XML 속성 | springxml |
| | `aliases` | List | springxml |
| | `inner` | 내부 빈이면 true | springxml |
| | `element`, `values`, `collectionClass`, `location` | `util:list`처럼 `<bean>`이 아닌 요소로 정의한 빈의 요소 이름, 문자열 원소(List), 요소 속성. `<beans>` 바로 아래의 `id`가 있는 확장 네임스페이스 요소(`jdbc:embedded-database`, `jee:jndi-lookup`, `dubbo:reference`, `task:executor` 등)도 그 이름의 빈으로 등록하고 `element`에 `<네임스페이스 URI의 마지막 조각>:<로컬 이름>`을 남긴다. `class`는 알려진 요소의 대표 타입이고 모르면 없다(F-017-R14) | springxml |
| | `otherDefinitions` | List. 이름이 같은 다른 XML 정의들(다른 모듈 루트, 테스트 설정)을 `<파일>:<줄> <class>`로 남긴 것. 빈 노드는 이름 하나에 하나이고 노드의 나머지 attrs(`class` 등)와 위치는 main 쪽(`src/test/`가 아닌 것)·파일 경로순 첫 정의의 것이다. 다른 정의의 구현 클래스는 이 값과 `DEFINES_BEAN` 엣지(정의마다 하나, 위치가 그 정의)로 본다(F-017-R13). 이름이 같은 스테레오타입 빈 정의가 여럿일 때(다른 모듈의 같은 이름 클래스, 테스트 소스)도 java가 같은 형식으로 남긴다(F-004-R22) | springxml, java |
| | `mapperInterface` | `MapperFactoryBean`의 매퍼 인터페이스 | springxml |
| HTTP_ENDPOINT | `httpMethod`, `path` | `path`에는 프로퍼티 자리표시자 `${…}`가 원문 그대로 들어 있을 수 있다 | java, springxml |
| | `viewController` | boolean. `<mvc:view-controller>`로 선언한 엔드포인트. 핸들러 메서드(`ROUTES_TO`)가 없고 엔드포인트에서 바로 `RENDERS`가 나간다(F-017-R16, F-012-R34) | springxml |
| | `viewNames` | List. 그 선언들의 view-name(중복 없이) | springxml |
| | `viewDeclarations` | List. `<파일>:<줄>:<열> <view-name>`. 선언마다 하나다(엔드포인트 노드는 메서드와 경로가 같으면 하나라 여러 모듈의 선언이 한 노드에 모인다). 링커가 선언마다 `RENDERS`를 만들고, 모듈 경계를 볼 때 선언의 파일을 그 엔드포인트의 선언 위치로 쓴다(F-012-R34·R37) | springxml |
| | `placeholders` | List. 경로에 든 프로퍼티 자리표시자의 이름(나온 순서, 중복 없이. `${이름:기본값}`은 이름만). 경로 변수 `{id}`는 아니다. 없으면 키를 넣지 않는다(F-004-R16) | java |
| | `resolvedPath` | 자리표시자를 프로젝트 properties 값으로 전부 치환한 경로. 치환할 것이 없거나, 값을 찾지 못했거나, 일부만 풀렸거나, 핸들러가 여러 모듈에 있고 모듈마다 다르게 풀리면 없다. 노드 ID와 `path`는 그대로다(F-012-R15) | link |
| HTTP_REQUEST | `httpMethod`, `path`, `url`, `query` | 공통 규칙 참고 | web, jsp |
| | `dynamic` | URL에 동적 조각이 있으면 true | web, jsp |
| | `external` | 프로젝트 밖(다른 호스트)으로 가는 요청. 링커가 맞추지 않는다 | web, jsp |
| | `relative` | `/`로 시작하지 않는 상대 URL | web, jsp |
| | `api` | web: `fetch`, `axios`, `jquery`, `xhr`, `form`, `navigation`, `beacon`, `eventsource`, `option`, `route`(클라이언트 라우터의 이동·링크. 항상 `clientRoute=true`. 3차 수정), 그리고 JSX의 HTML 요소에서 읽은 `link`(`<a href>`·`<area href>`), `form`(`<form action>`), `frame`(`<iframe src>`·`<frame src>`), `img`(`<img src>`)(4차 수정, F-010-R42). 5차 수정부터 같은 네 값은 스크립트가 DOM에 넣는 HTML 문자열(`.html('<iframe src="…">')`, `.append(…)`, `innerHTML`, `document.write(…)`, `$(…)`, `insertAdjacentHTML`) 안의 태그에서도 나온다: 값이 서버 경로 꼴일 때만 만들고, 위치는 그 문자열 식, `url`은 그 식의 원문이다(F-010-R45) / jsp: `form`, `link`, `frame`, `redirect`, `refresh`, `include`, `forward`, `tag`(접두사 있는 커스텀 태그의 URL 속성. 메서드는 `ANY`. 3차 수정, F-011-R24). `option`은 요청 API가 아닌 호출에 넘긴 옵션 객체에서 읽은 URL이다(그 호출이 정말 요청을 보내는지는 모른다). `option`·`tag` 요청으로 가는 `REQUESTS`는 항상 `INFERRED`다. `api=navigation`의 경로는 루트 `/`일 수 있다(`GET /`. F-010-R43) | web, jsp |
| | `optionKey`, `callee` | `api=option`일 때 URL을 읽은 옵션 키(`url`, `ajax`, `ajax.url`, `async.url`, `content`, 그리고 옵션 객체가 아니라 `iframe:`·`url:` 접두 문자열 인자에서 읽었으면 `arg`. F-010-R37)와 피호출자 식의 원문 | web |
| | `httpMethods` | List. `httpMethod=ANY`인 요청의 후보 메서드(대문자). 메서드와 URL의 짝을 모를 때만 있다(공통 규칙 "요청의 메서드 후보"). 후보 가운데 값을 모르는 메서드가 있으면 넣지 않는다. 링커가 읽는다(4차 수정, F-010-R39, F-012-R39) | web |
| | `clientRoute` | boolean. 서버 요청이 아니라 클라이언트 라우터가 처리하는 화면 전환이다. `api=route`인 요청과, 경로가 프로젝트가 선언한 클라이언트 라우트와 같은 `navigation`·`link`·`form`·`frame`·`img` 요청에 붙는다. 링커는 엔드포인트와 맞추지 않고 미해결도 남기지 않는다(`external`과 같은 취급. 3차 수정, F-010-R30, F-012-R29) | web |
| | `selfSubmit` | boolean. `action`이 없는(또는 빈) 폼이 현재 URL로 제출하는 요청이다. `api=form`, 메서드는 폼의 것이고 `path`와 ID의 URL 자리는 `{self}`, `url`은 빈 문자열이다. `REQUESTS`는 항상 `INFERRED`다. 페이지의 스크립트가 폼의 `action`을 대입하거나 **그 폼을** 제출하는 흔적이 없으면 만들지 않는다. 흔적은 그 폼 안의 제출 버튼(`onclick`이 `return false`로 끝나는 버튼, `onsubmit`이 `return false`로 끝나는 폼의 버튼은 제외)과 수신자가 그 폼을 가리킬 수 있는 `.submit()` 호출이다: 다른 이름·ID를 명시한 호출과 다른 창의 폼은 흔적이 아니고, 수신자를 모르는 호출은 페이지에 `action` 없는 폼이 하나뿐일 때만 흔적이다(5차 수정, F-011-R30). 링커가 읽어 이 페이지를 `RENDERS`하는 핸들러의 엔드포인트와 경로가 같고 메서드가 맞는 엔드포인트로 잇는다(4차 수정, F-011-R28, F-012-R40) | jsp |
| | `via` | 요청 래퍼 함수의 노드 ID | web |
| | `modelAttribute` | `<form:form>`의 모델 이름 | jsp |
| BATCH_JOB | `definedBy` | 잡을 만든 메서드의 노드 ID | java |
| | `abstract`, `parent`, `restartable`, `incrementer` | XML 속성 | springxml |
| BATCH_STEP | `stepType` | java: `chunk`, `tasklet` / springxml: `tasklet`, `chunk`, `decision`, `flow`, `split`, `partition`, `job`, `step` | java, springxml |
| | `definedBy`, `chunkSize` | 메서드 노드 ID, 상수일 때의 청크 크기 | java |
| | `abstract`, `parent`, `commitInterval`, `flow`, `flowRef`, `jobRef` | XML 속성. `flow`는 잡 밖 흐름의 이름 | springxml |
| | `endOn`, `failOn`, `stopOn` | List. 도착 스텝이 없는 전이의 `on` 값 | springxml |
| MYBATIS_MAPPER | `namespace` | | mybatis |
| MYBATIS_MAPPER, SQL_STATEMENT(매퍼 구문), SQL_FRAGMENT | `dialectVariant` | 방언별 매퍼 여러 벌 가운데 이 노드의 정의를 가져온 파일의 방언(소문자 표준 이름: `oracle`, `mysql` 등. 공통 규칙 "방언별 매퍼"). 방언 변형이 아니거나 `mybatis.dialectVariant=all`이면 없다 | mybatis |
| SQL_STATEMENT(매퍼 구문) | `borrowedFrom` | 고른(대표) 방언의 파일에 없어 다른 방언 파일에서 가져온 구문이면 그 방언(`dialectVariant`와 같은 값). 없으면 키를 넣지 않는다(4차 수정, F-009-R25). 소비자는 구문 줄에 "다른 방언에서 보충"으로 보인다(3.4) | mybatis |
| SQL_STATEMENT | `statementType` | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `CALL`, `OTHER`. 생산자(mybatis, java)가 태그·애너테이션으로 먼저 채우고 sql이 파싱 결과로 덮어쓴다(파싱 결과가 `OTHER`면 덮어쓰지 않는다). TRUNCATE는 `DELETE` | mybatis, java, sql |
| | `sql` | 공백을 정리한 본문. 매퍼 구문(동적 SQL은 대표 전개본), SQL 파일 구문, 루틴 안의 구문은 2000자에서 자른다. 루틴의 동적 SQL은 풀린 문자열을, 풀리지 않았으면 알아낸 리터럴 조각 사이에 `{*}`를 넣은 문자열을 적는다. springxml의 인라인 SQL은 원문 그대로다. 매퍼 구문·조각의 `${…}` 자리는 내부 표식(`__DYNAMIC__`)이 아니라 `${…}` 원문 그대로다(3차 수정, F-009-R21) | mybatis, java, sql, springxml |
| | `dynamic` | 동적 태그나 `${}`가 있으면 true. 루틴의 동적 SQL 지점(`EXECUTE IMMEDIATE` 등)에서 만든 구문도 true다(식이 리터럴로 완전히 풀렸어도 붙인다) | mybatis, java, sql, springxml |
| | `routine` | 루틴 안의 구문이면 주인 노드 ID(`db:routine:…`, 패키지 초기화부면 `db:package:…`) | sql |
| | `cursor` | 커서의 쿼리면 커서 이름(커서 선언, `OPEN c FOR SELECT`, 이름 있는 커서 FOR 루프) | sql |
| | `dynamicKind` | 루틴의 동적 SQL이면 `EXECUTE IMMEDIATE`, `OPEN FOR`, `PREPARE`, `DBMS_SQL`, `EXECUTE`(PL/pgSQL의 `EXECUTE`·`RETURN QUERY EXECUTE`·`FOR .. IN EXECUTE`. F-019-R28) 중 하나. `dynamic=true`와 함께 | sql |
| | `sequence` | 루틴 본문의 SQL 구문이 아닌 문장(대입문 등)에서 식 안의 시퀀스 참조만으로 만든 구문이면 true(F-008-R15). `statementType=OTHER`이고 `sql`은 대입문이면 문장 원문(`SET V = NEXT VALUE FOR SEQ`), 그 밖에는 식의 원문이다. 이 구문에서 시퀀스로 `READS`(`sequence=true`)가 나간다. 익명 블록에서는 노드를 따로 만들지 않고 블록의 주인 구문에서 `READS`가 나간다. 엣지의 `sequence`(READS)와 뜻이 다르다 | sql |
| | `dialect`, `dummyTable` | 공통 규칙 참고. `dummyTable`은 `DUAL` 또는 `SYSIBM.SYSDUMMY1` | sql |
| | `dataLoad`, `rowCount` | boolean, int. 데이터 적재 파일의 리터럴 INSERT를 테이블별로 묶은 노드면 `dataLoad=true`이고 `rowCount`는 묶은 행 수다(1건짜리 묶음이면 1). 적재 파일의 초기화 구문(조건 없는 `DELETE`·`TRUNCATE`)에도 `dataLoad=true`가 붙고 `rowCount=0`이다(공통 규칙 "데이터 적재 구문") | sql |
| | `borrowedFrom` (데이터 적재 구문) | String. 방언별 스크립트가 여러 벌인 프로젝트에서, 고른 방언의 파일에 그 테이블의 적재가 없어 다른 방언 파일에서 온 적재 묶음(과 그 테이블의 초기화 구문)이면 그 파일의 방언(표준 이름: `mysql`, `hsql` 등). `dataLoad=true`인 노드에만 붙고, 고른 방언의 파일·방언을 읽지 못한 파일의 묶음과 방언별 여러 벌이 아닌 프로젝트에서는 키를 넣지 않는다. 매퍼 구문의 `borrowedFrom`(위)과 같은 뜻이다: 고른 방언의 파일에 없어 다른 방언 파일의 것을 썼다(5차 수정, F-007-R29) | sql |
| | `namespace`, `statementId` | 매퍼 구문과 애너테이션 구문. `statementId`는 namespace를 뗀 짧은 id다: XML의 id가 `<namespace>.`로 시작하면 그 뒤이고, 점이 있는데 namespace로 시작하지 않는 id는 원문 그대로다(3.3) | mybatis, java |
| | `annotation` | 애너테이션 SQL이면 true | java |
| | `tag`, `parameterType`, `resultType`, `resultMap`, `keyProperty`, `jdbcStatementType` | XML 태그 이름과 속성 원문. `jdbcStatementType`은 XML의 `statementType`(`STATEMENT`, `PREPARED`, `CALLABLE`) | mybatis |
| | `fetchSize`, `timeout`, `variants` | int. `variants`는 등록한 전개본 수(selectKey 제외). 본문(공백 정규화)과 `databaseId`가 같은 전개본은 정의가 여러 벌이어도 한 번만 등록하고 한 번만 센다(F-009-R16) | mybatis |
| | `useGeneratedKeys`, `selectKey` | boolean | mybatis |
| | `databaseIds`, `resultColumns`, `nestedSelects` | List. `resultColumns`는 `COLUMN=property`(200개까지), `nestedSelects`는 `<namespace>.<id>` | mybatis |
| | `bean`, `composed` | 인라인 SQL을 가진 빈 이름, 절을 이어 붙여 만든 SQL이면 true | springxml |
| | `propertyKey` | `.properties` 파일의 값에서 읽은 SQL이면 그 키(F-006-R43). 노드의 `name`도 이 키다 | sql |
| SQL_FRAGMENT | `namespace`, `fragmentId`, `sql`, `dynamic`, `databaseIds` | | mybatis |
| DB_TABLE | `schema`, `comment` | 정의가 여러 파일에 있으면 이 표의 값과 컬럼은 모두 선택된 한 파일의 것이다(공통 규칙 "같은 테이블의 중복 정의") | sql |
| | `partitionOf` | PostgreSQL `CREATE TABLE p PARTITION OF 부모`나 `ALTER TABLE 부모 ATTACH PARTITION p`로 붙인 파티션이면 부모 테이블의 이름(대문자, 스키마 없음). `PARTITION OF`면 컬럼은 부모의 것이고 `ATTACH PARTITION`이면 자기 정의의 것이다. 파티션이 아니면 키를 넣지 않는다(F-019-R20) | sql |
| | `primaryKey` | List. PK 컬럼 순서대로 | sql |
| | `uniqueKeys` | List. 복합 UNIQUE마다 `COL1,COL2` | sql |
| | `indexes` | List. `IX1(A,B)`, `UX1 UNIQUE(C)` 형식. 내림차순 항목은 뒤에 ` DESC`가 붙고(`IX1(A,B DESC)`), 함수 기반 인덱스는 식을 그대로 적는다(`IX2(UPPER(D),A)`). MySQL의 테이블 수준 `KEY 이름 (cols)`·`INDEX`도 `이름(COL,..)`로 들어간다. 같은 인덱스를 두 번 선언해도 항목은 하나다. F-007-R13·R16. PostgreSQL의 이름 없는 인덱스(`CREATE INDEX ON t (a)`)는 이름 없이 `(A)`, `UNIQUE(C)`로 적고, 연산자 클래스와 인덱스 방식(`USING gin`)은 적지 않는다. 부분 유일 인덱스(`… WHERE …`)는 여기에는 남기되 컬럼 `unique`·테이블 `uniqueKeys`로 올리지 않는다(F-019-R21) | sql |
| | `temporary` | boolean | sql |
| | `systemCatalog` | boolean. PostgreSQL은 `pg_catalog.*`와, PostgreSQL 읽기 방식으로 읽은 구문에서 스키마 없이 쓴 알려진 카탈로그 이름(`pg_class`·`pg_tables` 등과 `pg_stat_`·`pg_statio_` 접두 이름)이다. 다른 읽기 방식에서는 `PG_`로 시작하는 이름을 카탈로그로 보지 않는다(결제 업무 테이블 `PG_SETTINGS`·`PG_STAT_DAILY`와 겹친다. F-019-R17). DB가 제공하는 사전 뷰·카탈로그 테이블(Oracle의 `USER_`·`ALL_`·`DBA_` 사전 뷰와 `V$…`, `SYS.*`, DB2의 `SYSCAT.*`·`SYSIBM.*`·`SYSSTAT.*`, `INFORMATION_SCHEMA.*` 등. 사전 스키마로 한정한 참조는 이름·따옴표·백틱·대소문자와 상관없이 해당한다: F-006-R41). `declared=false`와 함께 온다. 이름만 비슷한 프로젝트의 테이블, DDL에 정의된 테이블, 사전 스키마 없이도 읽은 이름에는 붙이지 않는다. 소비자는 "DDL에 없는 테이블"의 의심 목록에서 뺀다(4차 수정, F-006-R40) | sql |
| DB_VIEW | `schema`, `comment`, `materialized` | | sql |
| DB_COLUMN | `dataType`, `ordinal`, `defaultValue`, `comment` | `dataType`은 대문자로 정리한 원문. `DEFAULT NULL`은 기본값이 없는 것으로 보아 `defaultValue`를 넣지 않는다 | sql |
| | `nullable`, `primaryKey` | boolean. 뷰 컬럼에는 넣지 않는다 | sql |
| | `unique`, `identity`, `indexed`, `virtual` | boolean | sql |
| DB_ROUTINE | `routineType` | `PROCEDURE`, `FUNCTION`, `TRIGGER` | sql |
| | `parameters`, `dialect`, `schema`, `package` | `parameters`는 List. 원소는 `이름 [모드] 타입`(예: `P_ID IN NUMBER`). DB2의 `IN P_ID INT`도 `P_ID IN INT`로 맞춘다 | sql |
| | `triggerEvent` | 예: `AFTER UPDATE OF ORDER_STATUS` | sql |
| | `language` | 본문의 문법. `PLSQL`, `SQLPL`, `PLPGSQL`, `SQL`(PostgreSQL의 `LANGUAGE sql` 본문. 뒤의 둘은 F-019). 본문이 없는 외부 루틴(`LANGUAGE …`, `EXTERNAL`)과 PostgreSQL의 그 밖의 언어 루틴에는 없다 | sql |
| | `returnType` | 함수의 반환 타입 표기(헤더의 `RETURN`·`RETURNS` 다음) | sql |
| | `statementCount` | int. 본문에서 만든 `SQL_STATEMENT` 노드 수. 식 안의 시퀀스 참조로 만든 구문(`sequence=true`)도 센다 | sql |
| | `typeRefs` | List. 매개변수·반환 타입·선언에 쓰인 `%TYPE`·`%ROWTYPE`이 가리키는 `테이블.컬럼` 또는 `테이블`(스키마 제거·대문자, 나온 순서, 중복 없음. 예: `TB_ORDER.ORDER_STATUS`). 변수·레코드·커서·패키지 변수를 기준으로 한 것은 넣지 않는다. 없으면 키를 넣지 않는다. 엣지는 만들지 않는다(3.4). 컬럼 자료형 변경의 영향 범위를 볼 때 쓴다(F-008-R14) | sql |
| | `dynamicSql` | boolean. 본문에 동적 SQL 지점이 있으면 true(풀렸든 아니든) | sql |
| | `bodyMissing` | 패키지 명세에 선언만 있고 본문이 없는 루틴이면 true | sql |
| DB_PACKAGE | `dialect`, `schema`, `hasBody` | | sql |
| | `typeRefs` | List. 패키지 수준 선언의 `%TYPE`·`%ROWTYPE`이 가리키는 이름. 형식은 DB_ROUTINE의 `typeRefs`와 같다 | sql |
| DB_SEQUENCE | `schema` | | sql |
| DB_SYNONYM | `schema`, `target`, `public` | `target`은 원문 대상 이름(`SCHEMA.NAME[@LINK]`) | sql |
| JS_MODULE | `moduleKind` | `esm`, `commonjs`, `script` | web |
| | `skipped` | 내용을 분석하지 않은 이유: `minified`(압축 파일. 3차 수정부터 이름이 아니라 내용으로 판정한다: 이름에 `.min`이 없어도 내용이 압축본이면 붙고, `.min.js`라도 압축돼 있지 않으면 분석한다. F-010-R36), `vendor`(벤더 스크립트. 공통 규칙 "벤더(서드파티) 파일") | web |
| | `vendor` | boolean. 벤더로 판정했지만 `web.skipVendor=false`라 분석한 모듈 | web |
| | `inline` | JSP 인라인 스크립트의 주인 모듈이면 true | jsp |
| JS_FUNCTION | `kind` | `function`, `arrow`, `method`, `class` | web |
| | `async`, `exported`, `component` | boolean. `component`는 React 컴포넌트로 보이면 true | web |
| | `dynamicName` | boolean. 내장 소스의 함수 이름에 JSP 식이 섞여 이름과 ID의 그 자리가 `{*}`인 함수(3.3) | web |
| JSP_PAGE | `pageType` | `jsp`, `jspf`, `tag`, `html` | jsp |
| | `skipped` | `vendor`: 벤더 폴더 안의 페이지라 내용을 분석하지 않음(노드만 있다) | jsp |
| | `formMethods` | List. 폼 표: 페이지의 폼마다 `"<키>=<METHOD>"`(문서 순서). 키는 폼의 `name` 또는 `#id`이고 같은 키의 메서드가 다르면 `ANY`다. web이 스크립트의 폼 `action` 대입 지점에서 메서드를 정하는 데 읽는다(F-011-R16, F-010-R24). 이름·ID에 EL이 섞인 폼, 스크립트가 만든 폼은 없다 | jsp |
| | `formFields` | List. 필드 표: hidden·text `<input>`의 `value`가 서버 경로처럼 보이는 정적 값일 때 `<폼 name 또는 #id>.<필드 name>=<URL>`, 폼을 모르면 `.<필드 name>=<URL>`, id가 있으면 `#<input id>=<URL>`(문서 순서). 필드 값 자체는 요청이 아니다. web이 `frm.zip_url.value`, `$('#id').val()` 같은 식을 URL로 평가하는 데 읽는다(4차 수정, F-011-R29, F-010-R41). 스크립트가 넣는 값, `<form:hidden>`·`<select>`·`<textarea>`, 동적인 경로는 없다 | jsp |
| | `selfPostForms` | int. `action`이 없어 현재 URL로 보내는 POST 폼의 수. 4차 수정부터 이런 폼은 조건이 맞으면 `selfSubmit=true`인 `HTTP_REQUEST`로도 기록한다(F-011-R28). 이 값은 요청을 만들었는지와 상관없이 폼의 수다 | jsp |
| | `taglibs` | List. `접두사=uri` 또는 `접두사=tagdir:<경로>` | jsp |
| | `scriptletCount` | int. 스크립틀릿·식·선언의 합 | jsp |
| | `externalScripts` | List | jsp |

#### 엣지 attrs

| 엣지 | 키 | 값 | 쓰는 모듈 |
|---|---|---|---|
| EXTENDS, IMPLEMENTS | `typeArguments` | List. 상위 타입에 넘긴 타입 인자(선언 순서, erasure의 바이너리 이름. 타입 변수는 그 이름, 와일드카드는 `?`, 배열은 `[]` 접미사, classpath에 없는 타입은 import로 복원한 FQN 또는 소스에 쓴 이름). 타입 인자가 없으면 키가 없다. 상위 타입의 타입 매개변수 이름은 그 `JAVA_TYPE`의 `typeParameters`에 선언 순서로 있고 같은 위치끼리 대응한다. 소비자가 제네릭 상위 서비스 아래에서 "이 하위 타입이 고정한 DAO·엔티티"를 고르는 데 쓴다(3.4 "소비자가 지키는 규칙", F-002-R15) | java |
| ASSOCIATES | `field`, `multiplicity` | 필드명, `1` 또는 `*` | java |
| CALLS (Java) | `callType` | 3.4의 값 목록 | java |
| | `viaLambda` | 람다 안의 호출이면 true | java |
| | `receiverType` | 수신자 타입이 대상의 선언 타입과 다를 때 그 바이너리 이름. `callType=reflection` 엣지에서는 리플렉션으로 부른 객체의 타입(타입 인자로 정해진 프로젝트 타입)이다. 소비자가 구현과 팬아웃 엣지를 고르는 데 쓴다(3.4 "소비자가 지키는 규칙") | java |
| | `receiverTypes` | List. 같은 선언(공통 상위 인터페이스의 메서드)에 여러 대상 타입이 닿은 `reflection` 엣지에서 `receiverType` 대신 남기는 대상 타입들 | java |
| | `viaTypes` | List. `callType=reflection` 엣지에서 대상 타입을 그 타입 인자로 고정한 하위 타입의 바이너리 이름들(추상 하위 클래스 포함). 특정 하위 타입을 거쳐 온 호출이면 이 목록에 그 타입이 든 엣지만 따라간다(F-003-R20) | java |
| | `inheritedFrom` | 프로젝트 타입이 classpath에 없는 상위 타입에서 물려받았다고 추론한 메서드의 호출이면 그 상위 타입의 FQN. 대상은 `<FQN>#<이름>(?)`이고 `INFERRED`다. 수신자 없는 호출과 프로젝트 타입을 수신자로 한 호출에 붙는다(F-003-R19). classpath에 없는 상위 타입이 여럿이면 상위 클래스 쪽으로 고르고, 엣지는 하나라 `ambiguous`는 붙지 않는다(4차 수정, F-003-R29) | java |
| | `lombok` | 대상이 Lombok 합성 멤버면 true. JDT가 해석하지 못한 호출을 모든 파일을 본 뒤 수신자 타입(과 프로젝트 안의 상위 타입)에서 이름·인자 수로 다시 찾은 것이다(F-003-R11). 후보가 하나면 `RESOLVED`다 | java |
| | `ambiguous` | 같은 (출발, 위치)에서 나가는 후보가 둘 이상일 때만 붙는다(공통 규칙. 4차 수정, F-003-R29). 붙는 경우: Lombok 합성 멤버 등을 이름·인자 수로 찾은 후보가 여럿이라 엣지를 `INFERRED`로 낮추고 인자 타입으로 줄여도 여럿이 남아 남은 후보 모두에 이은 호출, 인자에 classpath에 없는 타입이 있어 오버로드를 가리지 못해 후보 전부로 이은 해석된 호출(`INFERRED`. 타입 표기로 맞는 후보가 정확히 하나면 그것으로만 잇는다. 3차 수정, F-003-R24), 같은 대상 타입에서 후보가 둘 이상인 `reflection` 엣지. 전에 붙던 상속 호출(`inheritedFrom`과 함께 온 것, 수신자 없는 상속 호출)에는 붙지 않는다 | java |
| | `externalChain` | 소스 없는 타입의 반환값에 이어 부른 호출 체인이라 해석하지 않은 미해결 엣지면 true. 링크마다가 아니라 체인당 하나이고, 원문은 가장 바깥 링크의 `<수신자>.<메서드명>(…)`(안쪽 링크는 소스에 쓴 인자 그대로: `registry.add(null).order(…)`), 위치는 체인 식 전체다. 진단은 `INFO`다(3.7) | java |
| | `argCount` | int. 미해결 `java-call` 엣지의 인자 수(체인당 미해결이면 가장 바깥 링크의 것). 원문에는 인자를 적지 않으므로(`vo.set(…)`) 인자 수만 다른 호출이 같은 미해결 노드로 모이고 엣지마다 이 값과 위치로 구분한다. 메서드 참조(`vo::nothing`)처럼 인자 수를 모르면 넣지 않는다(F-003-R8) | java |
| OVERRIDES | `argCount` | int. 미해결 `java-override` 엣지의 매개변수 수. 원문은 `<이름>(…)`이다(3.6, F-003-R18) | java |
| | `lombok` | boolean. 출발이나 대상이 Lombok 합성 메서드인 재정의(`RESOLVED`). 소비자에게는 보통의 `OVERRIDES`와 같은 뜻이다: 인터페이스의 `getId()` 호출 아래에 구현 클래스의 합성 getter가 구현으로 나온다(F-003-R22) | java |
| CALLS (루틴) | `callType` | `procedure`(문장 자리의 호출) 또는 `function`(식 안의 호출). from이 DB_ROUTINE·DB_PACKAGE인 엣지에만 있다 | sql |
| CALLS (JS) | `callType` | `call`, `constructor`, `jsx`, `ref`. `f.bind(x)`는 `ref`, `f.call(x)`·`f.apply(x)`는 `call`이다(3.4) | web |
| | `resolution` | `global`: 다른 스크립트의 전역 이름으로 이음(`INFERRED`). 2차 수정(2026-10-11)에서 뜻을 좁혔다: 프로젝트 전체에서 이름만으로 찾지 않고, **두 스크립트를 함께 싣는 페이지가 확인될 때만** 잇는다(같은 페이지의 `<script src>`·인라인 스크립트, include로 이어진 페이지, 레이아웃으로 묶인 페이지). 함께 싣는 페이지를 모르면 이 표시로는 잇지 않는다(그 이름의 정의가 프로젝트에 하나뿐이면 `loadUnverified`로 잇는다. 7절) | web |
| | `viaPage` | `resolution=global` 엣지의 근거: 두 스크립트를 함께 싣는 페이지의 노드 ID. 3차 수정부터 레이아웃 프레임워크로 함께 실리는 경우도 근거다: SiteMesh는 내용 페이지 → 데코레이터 `INCLUDES`(`via=sitemesh`)를 따라가고, Tiles는 정의(`tilesDefinitions`·`tilesAttributes`)마다 template과 조각 JSP가 싣는 스크립트를 한 묶음으로 본다(이때 값은 template 페이지의 ID다. F-010-R33) | web |
| | `loadUnverified` | boolean. 함께 싣는 근거 없이 "프로젝트(벤더·건너뛴 스크립트 제외)에서 그 이름의 최상위 정의가 정확히 하나"라는 것만으로 이은 전역 호출이다. 항상 `INFERRED`이고 `resolution`·`viaPage`가 없다. 정의가 여럿이면 잇지 않고 건수를 `CALLS_SKIPPED`에 더한다. 함수 참조(`setTimeout(tick)`)와 전역 상수에는 적용하지 않는다(3차 수정, F-010-R34) | web |
| | `ambiguous` | 후보가 여럿이면 true | web |
| | `viaProp` | 컴포넌트 함수가 props로 받은 함수를 호출한 지점에서, JSX 부모가 그 prop으로 넘긴 프로젝트 함수로 이은 엣지의 prop 이름(F-010-R15). 항상 `INFERRED`, `callType=call`이고 위치는 자식의 호출 지점이다. 그 컴포넌트를 쓰는 부모가 여럿이면 부모가 넘긴 함수마다 하나씩 있다 | web |
| CALLS (redirect·forward) | `callType`, `path`, `viewName` | `viewName`은 뷰 이름(`redirect:`·`forward:`)에서 온 엣지에만 있다(메서드 attrs `redirects`·`forwards`에서 온 엣지에는 없다). `MATCHES`와 같은 키(`strippedPrefix` 등)도 붙을 수 있다. java가 남기는 미해결 엣지(전부 동적인 경로)에는 `callType`만 있다 | link, java |
| ACCESSES | `access`, `viaLambda` | `read` 또는 `write`(복합 대입과 `++`/`--`는 `write`) | java |
| DEFINES_BEAN | `beanType` | `@Bean` 메서드가 노출하는 타입 FQN | java |
| INJECTS | `via` | `field`, `constructor`, `setter`, `xml`. Lombok이 만드는 단일 생성자(`@RequiredArgsConstructor` 등)의 매개변수가 된 필드도 `constructor`다(스테레오타입 클래스에 소스 생성자가 없고 합성 생성자가 정확히 하나일 때. 위치는 그 필드, F-004-R3) | java, springxml |
| | `field`, `required`, `candidates` | `required`는 false일 때만. `candidates`는 공통 규칙 참고 | java |
| | `qualifier` | `@Qualifier` 값 또는 XML에서 참조한 빈 이름 | java, springxml |
| | `property`, `innerBean`, `idref`, `inheritedFrom` | XML 프로퍼티 이름, 내부 빈 주입, `idref`, 부모 빈에서 물려받은 주입이면 그 부모 이름 | springxml |
| | `unresolvedBeanRef`, `unresolvedBeanSource` | 3.4의 예외 | springxml |
| | `resolvedFrom`, `resolvedSource`, `matchedBy` | `matchedBy`는 `qualifier` 또는 `name` | link |
| EXECUTES | `statementId`, `namespace`, `operation` | `operation`은 호출한 `SqlSession` 메서드 이름 | java |
| | `shortId` | namespace 없이 쓴 구문 ID면 true(대상은 `mybatis:stmt:<id>`. 링커가 유일한 구문을 찾으면 `INFERRED` 엣지를 더한다) | java |
| | `statementPrefix` | 구문 ID가 "리터럴 접두 + 동적"이고 접두에 namespace(`.`)까지 들어 있을 때의 그 접두. java가 남기는 미해결 `EXECUTES`(대상 `unresolved:mybatis-statement:<접두>{*}`, `operation`과 함께)와 링커가 그 접두로 이은 엣지에 있다(3차 수정, F-004-R20) | java, link |
| | `prefixMatch` | boolean. 구문 ID의 리터럴 접두로 찾은 구문으로 이은 엣지. 항상 `INFERRED`이고 `statementPrefix`, `operation`, `resolvedFrom`(원래의 미해결 노드)과 함께 온다. 위치는 미해결 엣지와 같다. 그 접두로 시작하는 구문(`mybatis:stmt:<접두>…`) 가운데 호출의 `operation`과 구문의 `statementType`이 맞는 것만 후보다: 읽는 호출(`select*`, `queryFor*`, `list*`)은 `SELECT`·`CALL`, 쓰는 호출(`insert`, `update`, `delete`)은 `INSERT`·`UPDATE`·`DELETE`·`MERGE`·`CALL`이고, 연산이나 구문의 종류를 모르면 거르지 않는다. 거른 뒤 후보가 1~5개일 때만 잇는다(F-012-R31, 4차 수정 F-012-R41) | link |
| | `viaArgument` | boolean. 구문 ID 문자열을 라이브러리 메서드에 인자로 넘긴 호출(메서드 attrs `statementIdLiterals`)을 그 ID의 구문과 이은 엣지. 항상 `INFERRED`이고 위치는 `statementIdLiteralLines`의 자리다. 그 메서드에서 그 구문으로 가는 `EXECUTES`가 이미 있으면 더하지 않고, 구문이 없는 문자열은 잇지도 미해결로 남기지도 않는다(4차 수정, F-012-R43) | link |
| | `role`, `bean`, `procedureName` | 스텝 구성 요소의 역할, 빈 이름, 프로시저 이름 원문 | springxml |
| | `declaredIn`, `resolvedFrom` | 상위 인터페이스에서 물려받은 매퍼 메서드의 선언 타입 ID, 짧은 ID로 가리키던 원래 대상 | link |
| | `inherited`, `namespace` | 링커가 만든 "물려받은 매퍼 메서드" 엣지(`declaredIn`과 함께, `INFERRED`)에 `inherited=true`와 `namespace`(그 구문의 namespace인 구체 매퍼 타입의 바이너리 이름)가 붙는다. java가 쓰는 `namespace`(SqlSession 호출의 namespace)와 키 이름도 뜻도 같다(구문의 namespace). 제네릭 상위 DAO의 메서드 노드 하나에서 하위 매퍼마다 엣지가 하나씩 퍼지므로 소비자는 호출 엣지의 `receiverType`과 이 `namespace`가 같은 엣지만 따라간다(3.4, F-012-R25) | link |
| RENDERS | `viewName`, `resolverPrefix`, `resolverSuffix`, `ambiguous` | forward 대상 JSP로 이은 엣지의 `viewName`은 `forward:` 뷰 이름 전체, 메서드 attrs `forwards`에서 왔으면 그 경로다(리졸버 키는 없다). `ambiguous`는 같은 경로의 JSP가 여러 웹 루트에 있어 고르지 못하고 전부로 이은 것이다(`INFERRED`) | link |
| | `via`, `definition`, `tilesAttribute` | Tiles definition으로 이은 엣지(3차 수정, F-012-R30). `via=tiles`, `definition`은 맞은 definition 이름 원문(와일드카드 포함)이다. definition의 template JSP로 가는 엣지에는 이 둘만, put-attribute의 JSP(본문·조각)로 가는 엣지에는 `tilesAttribute=true`도 붙는다. 신뢰도는 `RESOLVED`다. 핸들러와 같은 모듈의 설정 파일에 맞는 정의가 있으면 그것만 보고, 정확한 이름이 와일드카드 정의보다 먼저다 | link |
| | `candidate` | boolean. 여러 후보 가운데 하나인 뷰다. 항상 `INFERRED`. 메서드 attrs `viewNameCandidates`에 든 뷰 이름(F-012-R32)과, 동적 디렉터리 자리의 후보가 둘 이상인 경우(`dynamicSegment`와 함께)에 붙는다 | link |
| | `suffixMatch` | boolean. 컨텍스트 루트 기준의 경로(`getRequestDispatcher(<경로>)`의 JSP 경로, 뷰 이름 규칙으로 찾지 못한 파일 경로 꼴의 view-name)를 알려진 웹 루트 바로 아래에서 찾지 못하고 그 경로로 끝나는 JSP가 하나뿐이라 접미사로 맞춘 엣지. 항상 `INFERRED`. 웹 루트 바로 아래에서 찾았으면 `RESOLVED`이고 이 키가 없다(F-012-R35). 동적 디렉터리 뷰 이름을 경로 접미사로 찾은 경우에도 붙는다 | link |
| | `dynamicSegment` | boolean. 뷰 이름의 동적 조각(`{*}`)이 하나이고 디렉터리 조각 전체일 때(`themes/{*}/frontIndex`) 뷰 리졸버로 푼 경로의 그 자리에 실제 디렉터리 이름을 넣어 찾은 JSP로 이은 엣지. 항상 `INFERRED`. 후보는 1~5개일 때만 잇고 둘 이상이면 `candidate=true`도 붙는다. 동적 조각이 둘 이상이거나 조각의 일부이거나 마지막 조각이면 전처럼 뷰 미해결이다(4차 수정, F-012-R44). 동적 자리가 프로퍼티 자리표시자 하나인 이름(`/${app.name}/login.html`)도 같은 방식으로 다룬다(`viewName`은 원문 그대로). `MATCHES`의 `dynamicSegment`와 키 이름은 같고 뜻은 다르다 | link |
| | `narrowedBy` | String. 동적 디렉터리 뷰(`dynamicSegment=true`)의 후보를 고른 근거(5차 수정, F-012-R47). `property`: 동적 자리가 프로퍼티 자리표시자이고 컨트롤러 모듈의 프로퍼티 값이 하나로 모여 그 디렉터리의 후보만 남겼다(그 디렉터리에 뷰가 없으면 다른 디렉터리로 잇지 않고 뷰 미해결이다). `moduleName`: 프로퍼티 값을 모르고, 동적 자리의 디렉터리 이름이 컨트롤러가 속한 모듈의 디렉터리 이름(모듈 루트의 마지막 경로 조각)과 같은 후보만 남겼다. 근거가 없으면 이 키가 없고 후보 전부(1~5개)가 남는다. 5개 상한은 좁힌 뒤에 본다. 근거가 맞았으면 후보가 처음부터 하나였어도 남긴다. 신뢰도는 그대로 `INFERRED`다 | link |
| MATCHES | `strippedPrefix` | 요청 쪽에서 떼고 맞춘 앞부분(컨텍스트 경로·서블릿 매핑 접두, 호스트, 맨 앞의 `{*}`). 항상 `INFERRED`. 맨 앞의 `{*}`는 0개 이상의 미지 접두 세그먼트로 본다(3.9). 세그먼트 접두는 web.xml의 서블릿 매핑(`servletMappings`의 `/api/*`)과 맞는 것을 먼저, 그다음 추측으로 1개·2개를 떼어 본다. 5차 수정(F-012-R49): 추측으로 떼는 접두는 그 첫 세그먼트가 프로젝트에 선언된 엔드포인트 경로의 첫 고정 세그먼트로 쓰이고 있으면 떼지 않는다(실제 매핑의 일부다: `/manage/…` 엔드포인트가 있는 프로젝트의 `/manage/coder/index`는 `GET /index`로 잇지 않고 미해결이다). 경로 변수나 값을 모르는 자리표시자로 시작하는 엔드포인트는 그 근거가 아니다. 서블릿 매핑 접두, 호스트, 맨 앞의 `{*}`는 이 규칙과 무관하다. redirect·forward `CALLS`의 대상 매칭도 같다 | link |
| | `strippedPlaceholder` | 엔드포인트 쪽에서 떼고 맞춘, 경로 맨 앞의 자리표시자 원문(`${adminPath}`). JSP의 `${ctx}`가 컨텍스트 경로에 그 값까지 포함하는 프로젝트에서 요청 경로에는 그 조각이 없다. 값을 찾았든 못 찾았든 같은 규칙이고 항상 `INFERRED`(F-012-R15) | link |
| | `relative`, `dynamicSegment`, `ambiguous` | boolean. 상대 URL을 맞춤, 요청의 `{*}`가 엔드포인트의 고정 조각과 맞음(섞인 세그먼트의 리터럴이나 값을 모르는 자리표시자를 요청의 `{*}`가 덮는 경우 포함), 후보가 여럿 | link |
| | `relativeBase` | 상대 URL을 푼 기준 디렉터리. 요청이 든 페이지를 그리는 핸들러(`RENDERS`)의 엔드포인트 경로에서 마지막 세그먼트를 뗀 것이다(F-012-R18) | link |
| | `trailingSlash` | boolean. 끝의 `/`만 다른 경로를 맞춤. 신뢰도는 그대로다(F-012-R19) | link |
| | `crossModule` | boolean. 요청 파일의 모듈에 그 엔드포인트의 선언(핸들러 메서드 `ROUTES_TO`, view-controller 선언)이 없고 다른 모듈에만 있는 연결. 항상 `INFERRED`(F-012-R21). 4차 수정에서 정의를 바꿨다(F-012-R36~R38): 같은 모듈에 선언이 있는 후보가 있으면 그것만 남긴다(같은 모듈 매칭, `crossModule` 없음). 없을 때 요청 모듈이 독립 배포 단위(웹앱: 그 모듈에 `web.xml`이 있거나, `pom.xml`의 `<packaging>war</packaging>`·Gradle `war` 플러그인이거나, `@SpringBootApplication` 클래스가 있다)면 **다른 웹앱 모듈의 엔드포인트로는 잇지 않고 미해결(`http-endpoint`)로 남긴다**(다른 웹앱의 같은 경로는 다른 URL이다). 웹앱이 아닌 모듈(war에 함께 묶이는 라이브러리 모듈)의 엔드포인트만 `crossModule`로 잇는다. 요청 모듈이 웹앱이 아니면(정적 자원·프런트 모듈) 다른 모듈의 엔드포인트로 `crossModule`로 잇는다. 요청 파일의 모듈을 모르거나(빌드 파일이 없는 폴더) 선언 위치를 모르는 엔드포인트는 다른 모듈이라고 단정하지 않는다. redirect·forward `CALLS`의 대상 매칭도 같은 규칙이다 | link |
| | `module` | String. 같은 모듈 매칭의 모듈 루트(프로젝트 상대 경로. 프로젝트 루트는 `.`). 선언이 여러 모듈에 있는 엔드포인트(여러 모듈이 선언한 `GET /` 등)로 가는 같은 모듈 매칭에만 남긴다. 소비자가 그 모듈의 핸들러와 화면만 따라가는 데 쓴다(3.4. 4차 수정, F-012-R37). 5차 수정부터 `crossModule=true`와 함께 올 수 있다(F-012-R48): 웹앱이 아닌 모듈의 페이지(정적 UI 모듈의 템플릿)에서 나간 요청은, 그 페이지를 그리는(`RENDERS`) 핸들러가 모두 한 모듈에 있고(view-controller가 그리면 선언한 설정 파일의 모듈. 직접 그리는 핸들러가 없는 조각·스크립트는 그것을 포함하는 페이지를 `INCLUDES`로 거슬러 올라가 본다) 그 모듈에 선언이 있는 후보가 있으면 그 후보만 남기고 그 모듈을 적는다. 이때도 선언이 여러 모듈에 있는 엔드포인트에만 남긴다. 그리는 핸들러가 없거나 여러 모듈이면 `crossModule`만 있다 | link |
| | `selfSubmit` | boolean. `selfSubmit=true`인 요청(action 없는 폼)을 그 폼이 든 페이지를 그리는 엔드포인트와 같은 경로(끝 슬래시까지)·요청 메서드에 맞는 엔드포인트로 이은 것. 항상 `INFERRED`. 그리는 핸들러가 여럿이면 각각 잇고 대상이 둘 이상이면 `ambiguous=true`다. 폼이 include되는 조각에 있어도 포함하는 쪽을 거슬러 올라가지 않는다(4차 수정, F-012-R40) | link |
| IMPORTS | `spec` | import 지정자 원문 | web |
| INCLUDES (JSP) | `via`, `path` | `directive`, `action`, `import`, `tag`, `forward`, `script`, `thymeleaf`(`th:replace`·`th:insert`·`th:include`. `<script th:src>`는 `script` 그대로다), `sitemesh`(내용 페이지 → 데코레이터 JSP), `tiles`(Tiles 정의의 template JSP → 조각 JSP) / 속성 값 원문. `sitemesh` 엣지의 `path`는 `<meta name="decorator">`의 `content` 값 또는 URL 패턴이고, `tiles` 엣지의 `path`는 attribute 값 원문이며 위치는 설정 파일이다. 이 두 값의 엣지는 항상 `INFERRED`다(3.4. 3차 수정, F-011-R21·R22) | jsp |
| | `minOf` | JSP_PAGE→JS_MODULE 엣지: 페이지가 가리킨 것은 내용을 분석하지 않은 압축본(`x.min.js`)이고 이 엣지는 같은 이름의 원본 스크립트로 이은 것이다. 값은 압축본의 경로(jsp가 만든 것은 정규화한 src 경로, web이 만든 것은 프로젝트 기준 파일 경로). `via=script`, `INFERRED`(3차 수정, F-011-R23, F-010-R35) | jsp, web |
| | `dynamicDecorator` | boolean. `via=sitemesh` 엣지: 데코레이터 이름에 EL·식이 섞여 정적인 부분으로 맞춘 엣지(4차 수정, F-011-R27) | jsp |
| | `ambiguous` | boolean. `via=sitemesh` 엣지: 동적 이름의 후보 가운데 페이지 경로와 공통 디렉터리가 가장 긴 데코레이터를 고르는데, 가장 긴 것이 여럿이라 가리지 못해 그것들 전부에 이은 엣지(F-011-R27) | jsp |
| USES_TYPE | `role`, `alias` | `parameter`, `result`, `resultMap` / XML에 적힌 이름이 FQN과 다를 때 그 원문 | mybatis |
| READS | `star`, `lock`, `sequence` | boolean. `SELECT *`, `LOCK TABLE`, 대상이 시퀀스(루틴 본문의 식 안 시퀀스 참조 포함) | sql |
| WRITES | `op`, `truncate` | `INSERT`, `UPDATE`, `DELETE`, `MERGE` / boolean | sql |
| READS·WRITES (동적) | `text` | `unresolved:sql-dynamic:` 노드로 가는 엣지의 원문 조각. `${}` 자리면 그 식별자 조각, 루틴의 풀리지 않은 동적 SQL이면 `{*}`가 든 SQL 조각(루틴 안의 구문이면 그 구문의 `sql`과 같다). `WRITES`면 `op`도 붙는다 | sql |
| JOINS | `leftColumns`, `rightColumns`, `statement` | List, List, 구문 노드 ID | sql |
| | `leftFixedColumns`, `rightFixedColumns` | List. `from`·`to` 테이블 인스턴스의 컬럼 가운데 같은 구문에서 상수(리터럴, 바인드)와 `=`로 비교된 것. 처음 나온 순서. 없으면 키를 넣지 않는다(공통 규칙 "`JOINS`의 고정 컬럼") | sql |
| | `leftFixedValues`, `rightFixedValues` | List. 고정 컬럼의 값을 컬럼 순서대로 `COL=값`으로(예: `GROUP_CD=ORDER_STATUS`). 바인드·변수는 `COL=?`. 전개본마다 값이 다르면 한 컬럼에 값이 여럿이다 | sql |
| | `wrapped`, `leftWrapped`, `rightWrapped` | boolean. 조인 컬럼 쌍 가운데 형 변환·정리 함수로 감싼 컬럼끼리의 등치로만 본 쌍이 있으면 `wrapped=true`, `from`·`to` 쪽 컬럼이 감싸인 쌍이 있으면 `leftWrapped`·`rightWrapped`다. 감싸이지 않은 조인에는 세 키를 넣지 않는다(공통 규칙 "함수로 감싼 조인 키". 3차 수정, F-006-R29) | sql |
| REFERENCES | `columns`, `refColumns`, `source` | List, List, `DDL` 또는 `INFERRED` | sql, link |
| | `constraint`, `onDelete` | 제약 이름, `CASCADE`·`SET NULL`·`SET DEFAULT`·`RESTRICT`·`NO ACTION` | sql |
| | `evidenceCount`, `evidence` | 근거 구문 수, 근거 구문 노드 ID 목록(10개까지) | link |
| | `fromUpgrade`, `note` | DDL 관계(`source=DDL`) 가운데 upgrade·patch·migration 스크립트의 `ALTER TABLE … ADD FOREIGN KEY`에서 온 것이면 `fromUpgrade=true`다. 위치는 그 스크립트다. 현재 정의가 외래 키를 적는 스키마인데 이 외래 키만 없으면 신뢰도가 `INFERRED`이고 `note="현재 정의에 없음"`이 붙는다(공통 규칙 "같은 테이블의 중복 정의". F-007-R22·R26) | sql |
| | `cardinality`, `directionKnown`, `typeMismatch` | `N:1`·`1:1`·`unknown`, boolean, 양쪽 컬럼의 자료형 계열이 다르면 true | link |
| | `declaredOpposite`, `pseudoKey` | boolean. 조인으로 정한 방향을 거둔 까닭이다. 둘 다 `directionKnown=false`·`cardinality=unknown`과 함께 온다. `declaredOpposite`는 같은 테이블 쌍의 DDL 관계가 반대 방향, `pseudoKey`는 자식으로 판정된 쪽 컬럼이 여러 테이블의 식별자로 쓰이는 "사실상 키"다(공통 규칙 "추론한 `REFERENCES`의 방향". 3차 수정, F-018-R13·R14) | link |
| | `directionFrom` | 값 `DDL`: 조인만으로는 방향을 정하지 못해 같은 테이블 쌍의 DDL 외래 키 방향을 따랐다. `directionKnown=true`이고 `cardinality`는 `unknown`이나 `1:1`일 수 있다(4차 수정, F-018-R15) | link |
| | `fixedColumns`, `fixedValues` | List. 상수로 채운 복합 키로 확정한 관계에만 있다(공통 규칙 "상수로 채운 복합 키", F-018-R9). `fixedColumns`는 부모(`to`) 키에서 조인 컬럼을 뺀 나머지(키 순서), `fixedValues`는 근거 구문들에 나온 서로 다른 `COL=값`(10개까지)이다. 바인드로 고정한 `COL=?`는 들어가지 않는다(F-018-R12) | link |
| USES | `role` | `reader`, `processor`, `writer`, `tasklet`, `listener`. springxml은 `decider`, `partitioner`도 쓴다 | java, springxml |
| | `lambda` | 구성 요소를 람다로 준 경우 true | java |
| | `bean`, `inheritedFrom` | 참조한 빈 이름, 부모 스텝·잡에서 물려받았으면 그 이름 | springxml |
| | `resolvedFrom` | 빈을 타입으로 풀기 전의 빈 ID | link |
| NEXT | `on` | 전이 조건 | java, springxml |
| | `transition` | `stop`, `flow`, `split`, `partition` | springxml |
| 정규화로 옮긴 엣지 | `viaSynonym`, `strippedQualifier` | 공통 규칙 참고 | link |

### 3.6 미해결 분류 목록

`unresolved:<분류>:<원문>` 노드의 분류(`attrs.category`)다. 새 분류를 만들 때는 이 표에 적는다.

| 분류 | 엣지 | 뜻 | 만드는 모듈 |
|---|---|---|---|
| `java-type` | 타입으로 가는 엣지(EXTENDS, ASSOCIATES, INJECTS 등) | 이름을 복원하지 못한 타입 | java |
| `java-call` | CALLS | 대상을 해석하지 못한 호출. 원문은 인자를 적지 않은 `<수신자>.<이름>(…)`이고(수신자 없는 호출은 `<이름>(…)`, 생성은 `new <타입>(…)`, 그 밖에 `this(…)`, `super(…)`, `super.<이름>(…)`, enum 상수는 `<상수 이름>(…)`, 메서드 참조는 `<수신자>::<이름>`) 인자 수는 엣지의 `argCount`에 둔다(3.5). 공백을 줄여 200자에서 자른다(잘린 원문은 `...`로 끝난다). 컴파일러가 준 후보라도 인자 수가 맞지 않으면 그 후보로 잇지 않고 미해결로 둔다(F-003-R15). 인자 수가 맞아도 후보의 미해석 타입 매개변수 자리에 그 타입일 수 없는 해석된 인자가 왔거나 원시 타입·final 클래스 자리에 미해석 값이 왔으면 그 후보를 믿지 않고 다른 오버로드(상위 타입 포함)를 찾으며, 맞는 것이 하나가 아니면 미해결이다(F-003-R21). JDT가 해석하지 못한 호출은 바로 미해결로 두지 않고 모든 파일을 본 뒤 Lombok 합성 멤버 등에서 다시 찾으며(F-003-R11, R14), 그래도 찾지 못한 것만 남는다. 소스 없는 타입의 반환값에 이어 부른 체인은 링크마다가 아니라 체인당 하나로 줄인다(`externalChain=true`, 진단은 `INFO`) | java |
| `java-override` | OVERRIDES | `@Override`가 있는데 해석된 상위 타입에 선언이 없고, classpath에 없는 상위 타입도 하나로 정해지지 않음(하나면 그 타입의 `(?)` 메서드로 `INFERRED`). 원문은 `java-call`과 같은 표기 `<이름>(…)`이고 매개변수 수는 엣지의 `argCount`에 둔다(3.5. 2차 수정에서 `<이름>(<매개변수 수>)`에서 바꿨다, F-003-R18) | java |
| `http-endpoint` | ROUTES_TO(from이 미해결), MATCHES, CALLS(redirect·forward) | 상수가 아닌 요청 경로(java. 원문은 식), 경로를 전혀 알 수 없는 `sendRedirect`·`forward`·`include`(java. 원문은 식 텍스트, 엣지에 `callType`), 맞는 엔드포인트가 없는 요청·redirect·forward 대상(link. 원문은 `<METHOD> <경로>`). 요청 모듈이 웹앱이라 다른 웹앱 모듈의 엔드포인트로 잇지 않은 요청도 여기에 남는다(3.5 `crossModule`). action 없는 폼(`selfSubmit=true`)을 그리는 핸들러를 모르거나 맞는 엔드포인트가 없으면 원문은 `<METHOD> (selfSubmit)` 꼴이다(4차 수정, F-012-R40). `clientRoute=true`·`external=true`인 요청과 정적 자원으로 보이는 경로는 미해결로 남기지 않는다 | java, link |
| `view` | RENDERS | 상수가 아닌 뷰 이름(java), 맞는 JSP가 없는 뷰 이름과 forward 대상 JSP 경로(link) | java, link |
| `mybatis-statement` | EXECUTES | 상수가 아니거나 플레이스홀더인 구문 ID. 구문 ID가 "리터럴 접두 + 동적"이면 원문은 `<접두>{*}` 꼴이고 엣지에 `statementPrefix`가 있다. 링커가 그 접두로 구문을 찾아 전부 풀면 노드에 `resolvedBy=prefixMatch`가 붙는다(3.5, F-004-R20, F-012-R42) | java, springxml |
| `batch-step` | CONTAINS(잡→) | 잡이 가리키는 스텝을 찾지 못함 | java |
| `batch-component` | USES | 스텝 구성 요소의 대상을 찾지 못함 | java |
| `spring-bean-ref` | INJECTS, USES | 플레이스홀더로 준 빈 이름 | springxml |
| `db-routine` | EXECUTES | 플레이스홀더인 프로시저 이름 | springxml |
| `mybatis-fragment` | INCLUDES | 찾지 못한 `<sql>` 조각 | mybatis |
| `mybatis-type` | USES_TYPE | 해석하지 못한 타입·별칭 | mybatis |
| `mybatis-resultmap` | USES_TYPE | 찾지 못한 resultMap | mybatis |
| `sql-dynamic` | READS, WRITES | 테이블 자리가 동적(`${}`)이거나, 루틴의 동적 SQL(`EXECUTE IMMEDIATE`, `OPEN c FOR <식>`, `PREPARE … FROM`, `DBMS_SQL.PARSE`)의 식이 리터럴로 풀리지 않는다. ID는 `unresolved:sql-dynamic:<구문 ID>`로 구문마다 하나. 루틴 쪽 규칙은 아래 "루틴의 동적 SQL" | sql |
| `js-module` | IMPORTS | 찾지 못한 import 대상 | web |
| `js-call` | CALLS | 대상을 찾지 못한 JS 호출 | web |
| `http-url` | REQUESTS | 정적 조각이 없어 패턴을 만들 수 없는 URL. 인라인 스크립트에서 온 것의 원문은 JSP에 쓴 그대로다(3.5 `url` 규칙). DOM에 넣는 HTML 문자열 안의 `<a href>`·`<iframe src>`·`<form action>` 값이 통째로 동적일 때도 남고, 그 원문은 HTML 문자열 식이다(`<img src>`는 남기지 않는다. F-010-R45) | web, jsp |
| `jsp-include` | INCLUDES | 찾지 못했거나 동적인 포함 대상. 찾지 못한 SiteMesh 데코레이터는 원문이 `decorator:<이름>`이다(F-011-R21) | jsp |

마무리 단계가 만든 미해결 노드(매달린 엣지의 끝점, 3.2)에도 분류가 있다: ID는 `unresolved:` 형식이 아니라 엣지가 가리키던 ID 그대로이고, `attrs.expectedPrefix`와 그 접두사에서 유도한 `attrs.category`(3.5의 매핑표)를 갖는다. 그래서 짧은 구문 ID로 가리킨 매달린 `mybatis:stmt:…` 노드는 분석기가 남긴 `unresolved:mybatis-statement:…`와 같은 분류(`mybatis-statement`)로 묶인다. 마무리 단계가 만든 노드인지는 `expectedPrefix`의 유무로 가른다. 이 항목이 생기기 전에 저장한 그래프(`category` 없이 `expectedPrefix`만 있음)는 소비자가 같은 매핑으로 읽는다.

루틴의 동적 SQL (F-008-R8, R9)
- 식이 문자열 리터럴로 **완전히 풀리면** 미해결이 아니다. 그 SQL을 파싱해 일반 구문처럼 `READS`/`WRITES`/`JOINS`/`CALLS`를 `RESOLVED`로 기록하고 구문에 `dynamic=true`, `dynamicKind`를 붙인다. 진단은 남기지 않는다. 엣지의 위치는 모두 동적 SQL을 실행·준비하는 문장의 위치다.
- 푸는 범위는 문자열 리터럴, 리터럴끼리의 연결(`||`, DB2 `CONCAT`), 괄호, 그리고 그 루틴 안에서 대입이 정확히 한 번이고 값이 다시 완전히 풀리는 변수까지다. 매개변수, 두 번 이상 대입된 변수, 함수 호출 결과는 풀지 않는다. 분기·루프 같은 흐름은 보지 않는다.
- **풀리지 않으면** 구문 노드(`dynamic=true`, `sql`에 알아낸 조각과 `{*}`)에서 `unresolved:sql-dynamic:<구문 ID>`로 가는 `UNRESOLVED` 엣지 하나와 `WARN` 진단 `DYNAMIC_SQL`을 남긴다. 엣지의 종류는 첫 리터럴의 첫 키워드로 정한다: `INSERT`/`UPDATE`/`DELETE`/`MERGE`면 `WRITES`(`op`는 그 키워드), `TRUNCATE`면 `WRITES`(`op=DELETE`), 그 밖에는 `READS`다.
- 풀리지 않은 동적 SQL의 리터럴 조각에 테이블 이름이 온전히 보여도(`'… FROM TB_ORDER WHERE …'`) 테이블 엣지를 만들지 않는다(추측 금지). 조각은 구문의 `sql`과 엣지의 `text`에만 남는다.
- DB2는 `PREPARE s FROM <식>`이 구문 노드를 만들고 `EXECUTE s`와 그 문장으로 연 커서의 `OPEN`은 노드를 따로 만들지 않는다.
- 익명 블록 안의 동적 SQL은 코드(`BlockSink`)상 블록의 주인 구문 노드에서 같은 방식으로 나간다(구문 노드를 따로 만들지 않으므로 `dynamicKind`는 붙지 않는다). [확인 필요] 이 경우를 직접 확인하는 테스트는 찾지 못했다.

### 3.7 진단 코드 목록

core의 `DiagnosticCodes`에 있는 코드

| 코드 | 심각도 | 남기는 경우 | 남기는 모듈 |
|---|---|---|---|
| `PARSE_FAILED` | ERROR: 파일이나 구문을 통째로 얻지 못함(sql은 패키지 멤버 하나의 본문 분석이 예외로 끝났을 때도) / WARN: 오류를 복구하고 일부만 분석함(루틴 본문은 본문 파서가 구조를 복구한 문제를 루틴마다 하나, 본문의 SQL 구문에서 복구한 문제를 구문마다 하나). WARN에 더한 경우(2차): 정의나 DML로 보이는 줄이 있는데 테이블도 구문도 얻지 못한 SQL 파일(파일마다 1건, 위치는 파일. sql, F-006-R22), 매퍼 요소의 id에 점이 있는데 namespace로 시작하지 않음(원문을 ID로 쓴다. mybatis, F-009-R13). WARN에 더한 경우(3차): JDT가 타입 정보를 읽다가 낸 내부 오류를 넘기고 계속 분석함(위치는 그때 추출 중이던 파일, 같은 파일의 같은 메시지는 한 번. JDT 원문은 표준 오류에 나오지 않는다. java, F-002-R18), 레이아웃 설정 분석 실패(jsp). sql의 WARN 메시지는 `구문 일부를 해석하지 못함: <첫 문제>[ (외 n건)]` 꼴이고, 4차 수정부터 첫 문제에 복구가 일어난 지점의 토큰과 기대한 것을 적는다: 피연산자 바로 뒤에 또 피연산자가 오면 "`<토큰>` 앞에 AND/OR 또는 연산자가 필요한 자리", 잘못 들어간 기호면 "예상하지 못한 `<기호>`", 정말 닫는 괄호 없이 끝났을 때만 "닫는 괄호가 없음"이다(F-006-R39). 메시지의 `${…}` 자리는 내부 표식이 아니라 원문이다(F-006-R26) | 파싱 실패 | java, springxml, sql, mybatis, web, jsp |
| `UNSUPPORTED_SYNTAX` | INFO (mybatis의 전개본 상한 16개 초과·순환 include는 WARN) | 지원하지 않는 구문을 건너뜀. 루틴 본문에서는 본문 파서가 건너뛴 문장마다 하나, 본문을 분석하지 않는 종류의 루틴 단위마다 하나. 매퍼 구문·조각 안의 알 수 없는 태그(구식 iBatis의 `<isEqual>`·`<isNotEmpty>`·`<iterate>` 등)는 본문을 전개본에 넣고 태그마다가 아니라 구문·조각마다 1건(태그 이름 나열)이다(F-009-R18). 루트가 `sqlMap`인 파일은 파일마다 1건이다 | sql, mybatis, springxml |
| `UNRESOLVED_REFERENCE` | WARN. 예외로 INFO인 것: springxml의 찾지 못한 `<import>` 대상, java의 체인당 미해결(`externalChain=true`, F-003-R12) | 미해결 엣지·노드를 만들 때마다. `sql-dynamic`은 예외로 `DYNAMIC_SQL`만 남긴다. java의 체인당 미해결은 엣지 1건에 진단 1건이다 | core(`addUnresolvedEdge`, 마무리 단계), java, mybatis, springxml |
| `DYNAMIC_SQL` | WARN: 테이블 자리가 동적(sql), 루틴의 동적 SQL 식을 리터럴로 풀지 못함(sql), 애너테이션 SQL이 상수가 아님(java) / INFO: 구문에 `${}` 치환이 있음(mybatis) | 동적 SQL. 리터럴로 완전히 풀린 루틴의 동적 SQL에는 남기지 않는다 | sql, java, mybatis |
| `DUPLICATE_OBJECT_NAME` | WARN (프로파일로 나뉜 빈 정의끼리면 INFO. 스키마가 다르지 않은 같은 테이블의 중복 정의도 INFO) | 같은 이름 테이블이 서로 다른 스키마에 정의됨(sql. WARN), 같은 테이블·뷰의 정의가 여러 파일에 있어 한 파일의 것만 씀(sql. INFO. 테이블마다가 아니라 (쓴 파일, 버린 파일) 쌍마다 1건이고, 서로 다른 두 폴더 사이에 파일 쌍이 여럿이면 폴더 쌍 하나로 줄인다. 위치는 버린 파일, 메시지에 테이블 수와 `--exclude`·`--ddl` 안내. 3.5 공통 규칙 "같은 테이블의 중복 정의". 4차 수정부터 쓴 파일과 버린 파일의 방언이 다른 중복, 곧 방언별 여러 벌에서 한 벌을 고른 것에는 남기지 않는다: 요약 `DIALECT_VARIANT_DDL`이 알린다. 방언 이름이 없는 파일끼리의 중복에만 남는다. F-007-R27), 같은 이름 빈이 같은 모듈 루트(가장 가까운 조상의 `pom.xml`·`build.gradle`·`build.gradle.kts` 디렉터리)의 같은 쪽(main끼리, test끼리)에서 여러 번 정의됨(springxml. 2차 후속에서 좁혔다: 다른 모듈·테스트 설정의 같은 이름은 진단하지 않고 `otherDefinitions`에 남긴다. F-017-R13) | sql, springxml |
| `ANALYZER_FAILED`, `LINKER_FAILED`, `EXPORTER_FAILED` | ERROR | 구현체가 예외로 끝남 | core (`Pipeline.run`, `Pipeline.export`) |
| `SERVICE_LOAD_FAILED` | ERROR | `ServiceLoader`가 구현체를 만들지 못함. `Pipeline.export`는 출력기 것만 남긴다 | core |
| `EXCLUDED_DIRECTORY` | INFO | 기본 제외 디렉터리를 건너뜀 | core |
| `FILE_NOT_FOUND` | WARN | 옵션으로 준 경로(`--ddl`, `--classpath`)가 없음. `--classpath <폴더>/*`의 폴더가 없을 때도 남긴다(F-002-R17) | core, java |
| `FILE_NOT_READABLE` | WARN (java가 Java 파일을 읽지 못하면 ERROR) | 파일·디렉터리를 읽지 못함 | core, java, link |
| `INVALID_OPTION` | WARN | 옵션 값이 잘못돼 무시하거나 기본값을 씀 | core, java, link, mybatis, export |

모듈이 정의한 코드

| 코드 | 심각도 | 남기는 경우 | 모듈 |
|---|---|---|---|
| `SOURCE_ROOT_MISMATCH` | INFO | 패키지 선언과 파일 경로가 맞지 않아 소스 루트를 정하지 못함 | java |
| `EXTERNAL_CALLS_SKIPPED` | INFO | `java.externalCalls` 설정에 따라 기록하지 않은 외부 메서드 호출 건수(분석당 한 번) | java |
| `DUPLICATE_STATEMENT_ID` | WARN | 같은 namespace·id·`databaseId`의 구문이 여러 번 정의됨. 방언이 다른 매퍼 파일 사이의 같은 id에는 남기지 않는다(한 벌만 고르거나 `all`로 합친다. 2차 수정에서 범위를 줄였다, F-009-R15) | mybatis |
| `DIALECT_VARIANT_MAPPERS` | INFO (대상 방언과 그 계열의 매퍼 파일이 하나도 없어 명시해 요청하지 않은 대상 외 방언을 쓰게 됐으면 WARN) | 방언별 매퍼 여러 벌을 감지함. 구문마다가 아니라 분석 한 번에 1건이고 위치가 없다. 메시지: `방언별 매퍼 n벌 감지(<방언 목록>): <방언> 파일을 사용, 제외한 파일 c개.`에 이어 다른 방언 파일에서 가져온 구문(방언별 수와 구문 ID, 전체 20개까지), 대표 방언 파일에서 XML 주석으로 빠져 보충하지 않은 구문, 프로젝트 설정의 근거(`<파일>:<키>=<값>`)와 설정대로 골랐는지·설정과 다르게 대상 방언을 골랐는지(이때 설정대로 분석하는 `--opt mybatis.dialectVariant=<방언>` 안내), 엇갈려 쓰지 않은 설정, 바꾸는 옵션이 붙는다. `all`이면 전부 합쳤다는 한 줄이다(4차 수정, F-009-R24·R25) | mybatis |
| `DIALECT_VARIANT_DDL` | INFO (대상 방언과 그 계열의 파일이 하나도 없어 명시해 요청하지 않은 대상 외 방언을 쓰게 됐으면 WARN) | 방언별 DDL·데이터 적재 스크립트 여러 벌을 감지해 한 방언을 고름. 분석 한 번에 1건, 위치 없음. 메시지: `방언별 DDL n벌 감지(<방언 목록>): <방언> 파일을 사용(테이블 a개), 다른 방언에서 보충 b개, 제외한 파일 c개.`(DDL 중복 없이 적재 스크립트만 여러 벌이면 `방언별 데이터 적재 스크립트 n벌 감지…`)에 이어 만들지 않은 적재 묶음 수(`데이터 적재 스크립트도 같은 규칙으로 골라 다른 방언 파일의 묶음 n개를 만들지 않음.`), 다른 방언 파일에서 가져온 적재 묶음 수(`<방언> 파일에 적재가 없는 테이블의 묶음 m개는 다른 방언 파일의 것을 씀(<방언> k개, …).` 그런 묶음이 있을 때만. 5차 수정, F-007-R29), 프로젝트 설정의 근거와 설정과 다르게 골랐는지, 엇갈린 설정, 바꾸는 옵션(`--dialect <oracle\|db2\|postgres>`, 그 밖의 방언은 `--opt mybatis.dialectVariant=<방언>`)이 붙는다. 코드 상수는 `io.codeatlas.sql.SqlDiagnosticCodes`(F-007-R19·R23) | sql |
| `DIALECT_NOT_FOUND` | WARN | 명시해 요청한 방언(`--dialect`, `mybatis.dialectVariant`)의 DDL·데이터 스크립트 파일(sql)이나 매퍼 파일(mybatis)이 하나도 없어 다른 방언으로 대체함. 모듈마다 분석 한 번에 1건, 위치 없음. 메시지에 요청한 방언과 옵션 이름, 감지한 방언, 대체한 방언이 있다. 방언별 여러 벌이 아니면 남기지 않는다(F-007-R19, F-009-R20) | sql, mybatis |
| `DIALECT_NOT_TARGET` | INFO | 방언별 여러 벌은 아니지만 프로젝트의 SQL 스크립트가 대상 외 방언뿐임(MySQL 덤프뿐인 프로젝트 등). 분석 한 번에 1건, 위치 없음. 대상 방언(과 그 계열)의 스크립트가 하나라도 있거나 그 방언을 옵션으로 요청했으면 남기지 않는다. 코드 상수는 `io.codeatlas.sql.SqlDiagnosticCodes`(4차 수정, F-007-R25) | sql |
| `UNKNOWN_COLUMN` | INFO | 비한정 컬럼이 스코프의 선언된 테이블 어느 DDL에도 없고 실제 테이블이 여럿이라 소속 테이블을 정하지 못함. 컬럼 엣지는 만들지 않는다. 주인 구문마다 1건이고 위치는 처음 나온 컬럼(없으면 구문)이다. 메시지: `DDL에 없는 컬럼이라 소속 테이블을 정하지 못함: <컬럼, …> (후보 테이블: <테이블, …>) (<주인 구문 ID>)`. 틀린 엣지를 만들지 않으려는 표시다(3.4 "DDL에 없는 컬럼 참조". 3차 수정, F-006-R31). 코드 상수는 `io.codeatlas.sql.SqlDiagnosticCodes` | sql |
| `REFLECTION_CALL` | INFO | 대상을 정적으로 알 수 없어 기록하지 않은 `Method.invoke`가 있는 메서드. 메서드당 1건(처음 나온 `invoke`의 위치)이고, 한 메서드에 추론한 호출과 추론하지 못한 호출이 함께 있어도 1건이다(F-003-R20) | java |
| `FILE_SKIPPED` | INFO | 내용을 분석하지 않은 파일. 압축된(minified) 스크립트는 파일마다 1건, 벤더 스크립트(web)와 벤더 폴더의 페이지(jsp)는 파일마다가 아니라 **폴더마다 1건**(건너뛴 파일 수, 위치는 그 폴더)이다. 벤더 요약 진단의 메시지는 4차 수정부터 `벤더 스크립트 n개 건너뜀(<근거>): <폴더>`(jsp는 `벤더 페이지 n개 건너뜀(<근거>): <폴더>`)다. 근거는 web이 `경로`·`옵션`·`이름`·`라이선스`·`생성물`, jsp가 `경로`·`옵션`이고, 한 폴더에 근거가 섞였으면 근거마다 건수를 붙여 쉼표로 잇는다(`경로 3, 이름 2`) | web, jsp |
| `DATA_LOAD_GROUPED` | INFO | 데이터 적재 파일의 리터럴 INSERT를 테이블별 구문 노드로 묶음. 적재 파일마다 1건이고 위치는 그 파일이다. 메시지: `데이터 적재 파일: 리터럴 INSERT n건을 테이블별 구문 m개로 묶음[(10건 미만인 테이블 k개 포함)][, 초기화 구문(조건 없는 DELETE·TRUNCATE) j건도 dataLoad로 표시]`. n·m·j는 노드가 된 것만 센다(방언별 여러 벌에서 다른 방언의 것을 골라 버린 묶음과 그 테이블의 초기화 구문은 빼고, 묶음이 하나도 남지 않은 파일에는 남기지 않는다. F-007-R29). 코드 상수는 `io.codeatlas.sql.SqlDiagnosticCodes`(F-006-R15·R24) | sql |
| `DDL_NOT_APPLIED` | INFO | upgrade·patch·migration 스크립트의 `ALTER TABLE`을 현재 DDL에 적용하지 않음(대상 테이블의 `CREATE TABLE`이 다른 파일에 있어 이미 반영된 변경으로 본다). 파일마다 1건. 메시지에 적용하지 않은 건수와 종류별 건수(컬럼 추가, 컬럼 변경·삭제, 제약 추가), 적용한 외래 키 추가 건수(`REFERENCES`의 `fromUpgrade=true`)가 든다. 외래 키만 적용하고 적용하지 않은 것이 없는 파일에도 1건이 남는다. 코드 상수는 `io.codeatlas.sql.SqlDiagnosticCodes`(F-007-R15·R22) | sql |
| `UNRESOLVED_BEAN_PLACEHOLDER` | INFO | 이름에 프로퍼티 자리표시자가 든 빈 참조를 풀지 못해 타입으로 잇지 않음(값을 못 찾았거나 치환한 이름의 빈이 없음). 이름별 건수를 한 건에 요약하고 위치가 없다(F-012-R23) | link |
| `CALLS_SKIPPED` | INFO | 매개변수·props로 받은 함수의 호출(직접 호출, 다른 호출에 넘김) 가운데 대상을 정하지 못해 `CALLS`로 기록하지 않은 건수. 생략이 있는 모듈마다 1건이고 위치는 그 파일, 줄 번호는 메시지에 있다(F-010-R16). 프로젝트 밖 전역·패키지 호출, 이름 없는 콜백의 매개변수 호출, 받은 객체의 메서드 호출은 세지 않는다. 3차 수정부터 함께 싣는 근거가 없고 정의가 여럿이라 잇지 않은 전역 호출도 센다: 메시지에 `정의가 여럿인 전역 이름 n건`이 붙고(전체 건수에 포함) 그런 건만 있는 모듈에도 진단이 난다(F-010-R34) | web |
| `MAPPER_METHOD_WITHOUT_STATEMENT` | WARN | 매퍼 인터페이스 메서드에 대응하는 구문이 없음 | link |
| `UNDEFINED_ROUTINE` | INFO | 정의를 찾지 못한 루틴 참조 | link |
| `SYNONYM_CYCLE` | WARN | 시노님이 서로를 가리킴 | link |
| `RELATION_NOT_RECORDED` | INFO | 추론한 관계를 기록할 근거 위치가 다른 관계와 겹침(7절) | link |

### 3.8 옵션 키 목록

`AnalysisOptions.extra`로 받는 키다. CLI에서는 `--opt <key>=<value>`로 준다(4.5). 분석기와 링커는 `ctx.options().extra()`로, 출력기는 `Exporter.export`의 `options`로 읽는다. 모르는 키는 무시한다. `analyze` 때 준 값은 `graph.json`의 `project.options.extra`에 저장되고(4.4) `export` 명령이 이어받는다(4.5).

도움말, 분석 요약의 힌트(4.5), README에 적는 `--opt` 키 예시는 이 절의 키여야 한다. cli의 테스트(`QueryCommandsTest`의 F-001-AC47)가 이 절의 표, 모든 명령의 도움말, 힌트 문구, README(`--opt` 예시와 `export.`·`java.`·`link.`·`mybatis.`·`web.`으로 시작하는 백틱 속 이름)를 읽어 대조하고, 아래 출력기 키 표가 `StandardExporter.OPTION_KEYS`(출력기가 읽는 키 전부)와 같은지도 확인한다. 키를 더하거나 바꿀 때는 이 표와 그 상수를 함께 고친다.

| 키 | 값 (기본값) | 뜻 | 읽는 모듈 |
|---|---|---|---|
| `java.includeJdkTypes` | `true`/`false` (false) | JDK 타입으로 가는 `ASSOCIATES`·`DEPENDS_ON`도 만든다 | java |
| `java.externalCalls` | `none`, `nonJdk`, `all` (nonJdk) | 소스 없는 타입의 메서드 호출(`CALLS`), 생성(`INSTANTIATES`), JDK 메서드 `OVERRIDES`를 어디까지 기록할지. 잘못된 값은 `INVALID_OPTION` | java |
| `java.sqlSessionTypes` | 쉼표로 구분한 타입 이름 (없음) | `SqlSession` 계열로 볼 타입의 단순 이름을 더한다 | java |
| `link.minJoinEvidence` | 정수 (1) | 추론 `REFERENCES`를 만드는 데 필요한 근거 구문 수. 숫자가 아니면 `INVALID_OPTION`을 남기고 1 | link |
| `link.inferViewRelations` | `true`/`false` (false) | 뷰가 낀 조인으로도 추론 `REFERENCES`를 만든다. 방향은 정하지 않는다(`cardinality=unknown`, `directionKnown=false`). `true`(대소문자 무관)일 때만 켜진다. ERD에서 뷰 관계선을 보려면 `export.erd.views`도 함께 켠다(F-018-R11) | link |
| `mybatis.dialectVariant` | `auto`, 방언 이름, `all` (auto) | 한 namespace의 매퍼가 방언별 파일로 여러 벌 있을 때 쓸 방언. 방언 이름은 `oracle`, `db2`, `postgres`, `tibero`, `altibase`, `mysql`, `mariadb`, `mssql`, `cubrid`, `goldilocks`, `hsql`, `h2`, `derby`, `sqlite`, `sybase`, `informix`(별칭 `ora`, `postgresql`·`pgsql`, `maria`, `sqlserver`, `hsqldb`. 끝에 버전 숫자가 붙은 `mysql55` 같은 이름도 받는다). 방언 이름을 주면 그 방언이 순위의 맨 앞이고, `auto`는 3.5 공통 규칙 "방언 변형 선택"의 순위(`--dialect` → 프로젝트 설정이 가리키는 대상 방언 → Oracle → DB2 → PostgreSQL → Oracle 계열 → 설정이 가리키는 대상 외 방언 → 이 목록 순서)로 고른다. `all`은 고르지 않고 모든 방언 파일의 정의를 한 노드에 합친다(매퍼에만 뜻이 있다). 모르는 값은 mybatis가 `INVALID_OPTION`을 남기고 `auto`로 본다(F-009-R15·R24). sql도 이 키의 방언 이름을 읽어 방언별 DDL·데이터 적재 스크립트를 고른다(`--dialect` 다음 순위. `auto`·`all`과 모르는 값은 건너뛴다. F-007-R18) | mybatis, sql |
| `web.skipVendor` | `true`/`false` (true) | 벤더(서드파티) 스크립트와 벤더 폴더 안의 페이지의 내용을 분석하지 않는다. `false`면 분석하고 모듈에 `vendor=true`만 붙인다(F-010-R17, F-011-R15) | web, jsp |
| `web.vendorPatterns` | 쉼표로 구분한 glob (없음) | 벤더로 볼 경로를 더한다. `!`로 시작하는 glob은 "벤더가 아니다"이고 다른 규칙보다 먼저다 | web, jsp |

`--dialect`(4.5, `AnalysisOptions.dialect`)는 이 표의 키가 아니지만 같은 선택에 쓰인다: 방언별 매퍼와 방언별 DDL·데이터 적재 스크립트에서 고를 방언(3.5 공통 규칙 "방언 변형 선택"의 맨 앞 순위), 방언을 판정하지 못한 루틴·블록의 문법 선택, 줄 처음 `#` 주석 판정에 쓴다. 구문 노드의 `dialect`에는 찍지 않는다(3.5 "`dialect` 값"). 값은 `oracle`, `db2`, `postgres`(별칭 `postgresql`, `pgsql`), `auto`뿐이므로 그 밖의 방언(mysql 등)으로 분석하려면 `mybatis.dialectVariant`에 방언 이름을 준다.

출력기(`export`)의 키. 잘못된 값은 `WARN` 진단 `INVALID_OPTION`을 남기고 기본값을 쓴다. `viewer` 출력기는 읽는 키가 없다.

| 키 | 값 (기본값) | 뜻 |
|---|---|---|
| `export.erd`, `export.classDiagram`, `export.crud`, `export.calltree`, `export.report` | `true`/`false` (true) | `false`면 그 산출물을 쓰지 않는다 |
| `export.includeDataLoad` | boolean (false) | 데이터 적재 구문(`SQL_STATEMENT`의 `dataLoad=true`, 3.5)을 CRUD 매트릭스, 테이블 명세의 구문 수, 콜트리, 리포트의 테이블 점검에 넣는다. 기본은 빼고 `report.md`에 요약만 적는다(F-015-R28) |
| `export.mermaid.maxChars` | 정수 (45000, 0은 보지 않음) | Mermaid 다이어그램 하나의 글자 수 한도(Mermaid의 기본 `maxTextSize` 50,000자보다 조금 작게). 넘으면 ERD는 `erd/erd-<영역>.mmd`·`erd/erd-overview.mmd`·`erd/index.md`로 나누고, 클래스 다이어그램은 멤버를 줄이거나 `<패키지>-<n>.mmd`로 나눈다. PlantUML·DBML은 나누지 않는다 |
| `export.erd.views` | boolean (false) | ERD에 뷰를 넣는다 |
| `export.erd.inferred` | boolean (true) | 추론 관계를 그린다 |
| `export.erd.minEvidence` | 정수 (1) | 그릴 추론 관계의 최소 근거 수 |
| `export.erd.columns` | `all`, `keys`, `none` (all) | 컬럼 표시 수준 |
| `export.erd.comments` | boolean (true) | 주석 표시 |
| `export.classDiagram.maxPackages` | 정수 (200) | 패키지 묶음 수 상한 |
| `export.classDiagram.maxTypes` | 정수 (60, 0은 상한 없음) | 다이어그램당 타입 수 상한 |
| `export.classDiagram.members` | `all`, `public`, `none` (all) | 멤버 표시 수준 |
| `export.classDiagram.hideAccessors` | boolean (false) | getter·setter 숨김 |
| `export.classDiagram.externalSupertypes` | boolean (false) | 소스 없는 상위 타입 표시 |
| `export.crud.bom` | boolean (true) | CSV에 BOM을 붙인다 |
| `export.crud.depth` | 정수 (20) | CRUD 매트릭스가 따라가는 최대 걸음 수. 엔드포인트 기준은 엔드포인트에서 구문까지, 구문·메서드·루틴 기준은 루틴 호출(구문→루틴, 메서드→루틴, 루틴→루틴이 한 걸음)이다 |
| `export.calltree.depth` | 정수 (20) | 표준 산출물 콜트리의 깊이. 트리가 깊이 제한에 잘리면 그 트리 끝에 안내 줄을 적는다 |
| `export.calltree.maxNodes` | 정수 (500) | 트리당 노드 수 상한 |
| `export.calltree.hideExternal` | boolean (true. 2026-10-11 2차: false에서 바꿈) | 소스 없는 메서드의 호출 줄을 접는다 |
| `export.calltree.hideAccessors` | boolean (true) | 접근자 호출 줄을 접는다. 접근자는 Lombok 합성 멤버이거나, 같은 타입에 대응하는 필드가 있는 `getXxx`·`isXxx`(매개변수 0개)·`setXxx`(1개)이고 `static`이 아니면서 그 메서드(와 그것을 재정의한 메서드)에서 `CALLS`·`EXECUTES`가 나가지 않는 메서드다(F-015-R35, R43) |
| `export.calltree.expandViews` | boolean (false) | 컨트롤러가 그리는 JSP 아래의 화면 이동과 redirect·forward로 넘어간 핸들러의 아래도 펼친다(F-015-R42). 레이아웃은 켜도 펼치지 않는다(F-015-R48) |
| `export.calltree.splitOver` | 정수 (50, 0은 나누지 않음) | 엔드포인트가 이 수를 넘으면 엔드포인트 콜트리를 컨트롤러별 `calltree/endpoints/<이름>.md`로 나누고 `calltree/endpoints.md`는 색인으로 쓴다(F-015-R30) |
| `export.report.maxItems` | 정수 (20, 0은 전부) | `report.md`의 항목 수 상한 |

### 3.9 내장 소스 attrs와 자리표시자 약속

`EmbeddedSource.attrs`(4.1)의 키. 값은 모두 문자열이다.

| 언어 | 키 | 값 | 넣는 모듈 |
|---|---|---|---|
| `sql` | `variant` | 한 구문 안의 전개본 번호(1부터). `selectKey` 본문은 따로 센다 | mybatis |
| | `dialect` | `databaseId`를 소문자로 바꾼 것. `databaseId`가 없고 방언별 매퍼 파일에서 고른 구문이면 그 파일의 방언(`oracle`, `mysql` 등. 3.5 공통 규칙 "방언별 매퍼")이다. sql은 `oracle…`·`ora`를 Oracle, `db2…`를 DB2, `postgres`·`postgresql`·`pgsql`을 PostgreSQL로 읽어 구문의 `dialect`와 읽기 방식(3.5 "읽기 방식")에 쓴다(4차 수정부터 `--dialect`보다 이 값이 먼저다. F-006-R37). 그 밖의 방언 이름(`mysql` 등)이면 대상 외 방언의 SQL로 보아 구문 특징으로 Oracle·DB2를 추측하지 않고, MySQL 계열이면 줄 처음 `#` 주석을 받는다 | mybatis |
| | `dynamicParts` | 치환 목록. 본문의 n번째 `__DYNAMIC__` 자리에 있던 `${…}` 원문을 등장 순서대로, 건마다 `<원문 길이>:<원문>`으로 구분자 없이 이어 붙인 문자열이다(길이는 UTF-16 글자 수. 예: `13:${sqlMap.dsf}10:${orderBy}`). `${…}`가 있을 때만 넣는다. sql이 진단 메시지, 미해결 `sql-dynamic`의 원문과 `attrs.text`, `JOINS`의 고정 값에서 내부 표식을 원문으로 되돌리는 데 쓴다(3차 수정, F-009-R21, F-006-R26) | mybatis |
| | `databaseId` | `databaseId` 원문 | mybatis |
| | `selectKey` | `"true"`면 `<selectKey>` 본문 | mybatis |
| `js` | `inlineHandler` | `"true"`면 이벤트 핸들러 속성(`onclick` 등)의 값 | jsp |
| | `attribute` | 핸들러 속성 이름 | jsp |
| | `scriptType` | `<script type>` 원문 | jsp |
| | `jspSubstitutions` | 치환 목록. jsp가 본문에서 JSP 요소를 치환한 구간과 그 자리의 원문을 담아 넘기고, web이 `HTTP_REQUEST`의 `url`과 미해결 `http-url`의 원문을 JSP에 쓴 그대로 되살리는 데 쓴다(F-011-R14, F-010-R14). 형식은 F-011 spec 3절 "치환 목록"이 정한다. 치환이 없는 스크립트와 HTML 파일의 내장 소스에는 키가 없다. 4차 수정부터 web은 문자열 리터럴 안 `__JSP_EXPR__` 자리의 원문이 `${조건 ? '리터럴A' : '리터럴B'}` 꼴이면 그 자리를 `{*}`로 두지 않고 A와 B를 넣은 후보 URL로 펼친다(`REQUESTS`는 `INFERRED`. F-010-R44. 속성 값의 같은 EL은 jsp가 펼친다, F-011-R25). 5차 수정부터 쿼리 문자열 안의 삼항은 web도 jsp도 펼치지 않고 `{*}`로 둔다(경로가 하나로 정해지므로 추론이 아니다. F-011-R31) | jsp |

java(애너테이션 SQL)와 springxml(인라인 SQL)은 attrs 없이 `exactPositions=false`로 넘긴다.

내장 소스 attrs가 아닌 길로 넘기는 것: jsp가 web에 넘기는 **폼 표**는 내장 소스가 아니라 `JSP_PAGE` 노드의 `attrs.formMethods`(와 `selfPostForms`, 3.5)에 싣는다. 폼은 페이지 단위이고 그 페이지가 싣는 JS 파일(`INCLUDES`)의 스크립트도 읽어야 하기 때문이다. web은 폼 `action`을 바꾸는 지점에서 같은 함수의 `.method` 대입 → 그 스크립트를 싣는 페이지의 폼 표 순으로 메서드를 찾는다(F-010-R24, F-011-R16). **필드 표**도 같은 길이다: `JSP_PAGE`의 `attrs.formFields`(3.5)에 hidden·text input에 든 서버 경로를 싣고, web이 폼 필드의 값을 읽는 식(`document.<폼>.<필드>.value`, `$('#<id>').val()` 등)을 URL로 평가할 때 그 스크립트를 싣는 페이지의 표에서 읽는다. 주인 페이지의 표에서 폼까지 맞춰 찾은 것이 아니면 `REQUESTS`는 `INFERRED`다(4차 수정, F-011-R29, F-010-R41). 레이아웃 정보도 노드와 엣지로 넘긴다: jsp가 만든 `INCLUDES`(`via=sitemesh`)와 Tiles 설정 `FILE` 노드의 `tilesDefinitions`·`tilesAttributes`를 web(전역 스크립트의 범위)과 링커(뷰 이름 → JSP)가 읽는다.

자리표시자

| 표기 | 쓰는 곳 | 약속 |
|---|---|---|
| `__DYNAMIC__` | `sql` 내장 소스 본문 | mybatis가 `${...}` 치환 자리에 넣는 식별자다. sql은 식별자가 이 문자열을 **포함**하는지로 판별한다(`TB_LOG_${ym}` → `TB_LOG___DYNAMIC__`도 동적이다). 테이블 자리면 `unresolved:sql-dynamic:<구문 ID>`로 가는 미해결 `READS`/`WRITES`(`attrs.text`에 원문 조각)와 진단 `DYNAMIC_SQL`을 남긴다. 스키마 자리만 동적이면(`__DYNAMIC__.TB_B`) 테이블은 그대로 기록하고 구문에 `dynamic=true`만 붙인다. 조건·절 사이(WHERE·ON·HAVING 조건 뒤, FROM 목록 뒤, SET 항목·VALUES 값·컬럼 목록의 이름 뒤 등)에 홀로 있는 `__DYNAMIC__`은 불투명한 SQL 조각으로 건너뛴다(진단 없음, `dynamic=true`는 유지. F-006-R25). 이 식별자는 분석 중에만 쓰는 것이라 그래프에 남기지 않는다(3차 수정): 구문·조각의 `sql`, sql의 진단 메시지, 미해결 `sql-dynamic`의 이름과 `attrs.text`, `JOINS`의 고정 값은 치환 목록(`dynamicParts`)으로 `${…}` 원문을 되살리고, 목록이 없는 내장 소스(java의 애너테이션 SQL 등)에서는 `${…}`로 적는다(F-006-R26, F-009-R21) |
| `__JSP_EXPR__` | `js` 내장 소스 본문 | jsp가 값을 내는 JSP 요소(EL, `<%= %>`, `<c:out/>` 등) 자리에 넣는 식별자다. web은 URL 맨 앞에 있으면 컨텍스트 경로로 보아 떼고, 그 밖의 자리에서는 동적 조각(`{*}`)으로 읽는다. `<c:url value="/x"/>`는 리터럴 경로로, 값을 내지 않는 요소는 공백으로 바꾼다. 치환은 줄 수를 보존한다(전체 규칙은 F-011 spec 3절). 이 식별자는 분석 중에만 쓰는 것이라 그래프에 남기지 않는다: 요청의 `url`과 미해결 원문은 치환 목록(`jspSubstitutions`)으로 되살리고, 함수 이름에 섞인 자리는 `{*}`로 쓴다(`dynamicName=true`, 3.3. 2차 수정에서 해소) |
| `{*}` | `HTTP_REQUEST`의 ID와 `path`, `JAVA_METHOD`의 `viewNames`·`redirects`·`forwards`(뷰 이름을 만드는 도우미 안의 알 수 없는 조각과 상수가 아닌 인자 포함. F-004-R25), 미해결 `mybatis-statement`의 원문(`<접두>{*}`), JSP 식이 섞인 `JS_FUNCTION`의 ID와 이름 | 정적으로 알 수 없는 조각. 경로 조각 하나를 통째로 차지하면 조각 하나에 맞고, `popup_{*}`처럼 글자와 섞이면 그 조각 안의 부분 와일드카드다. 패턴 맨 앞의 `{*}`(`{*}/tail`)는 알 수 없는 접두사(컨텍스트 경로나 base URL)라는 뜻이다: 링커는 그것을 **0개 이상의 미지 접두 세그먼트**로 보고 떼어 낸 나머지 경로 전체가 엔드포인트 경로 전체(또는 맨 앞 자리표시자를 뗀 경로, 서블릿 매핑 접두를 뗀 경로)와 맞을 때만 잇는다(`strippedPrefix`). `{*}text`처럼 첫 조각에 글자와 붙은 `{*}`는 접두로 보지 않는다. 요청의 `{*}`가 엔드포인트의 고정 조각과 맞으면 `INFERRED` + `dynamicSegment=true`다. `{*}`가 든 뷰 이름은 이름이 비슷한 JSP가 있어도 잇지 않는다. 예외(4차 수정, F-012-R44): `{*}`가 하나이고 디렉터리 조각 전체면 그 자리에 실제 디렉터리를 넣어 찾은 JSP 1~5개로 `INFERRED` + `dynamicSegment=true`로 잇는다(3.5 `RENDERS`). Tiles 정의와는 맞추지 않는다 |
| `{self}` | `selfSubmit=true`인 `HTTP_REQUEST`의 ID와 `path` | action 없는 폼이 제출하는 "현재 URL" 자리다. jsp는 그 경로를 모르므로 이 표기를 쓰고, 링커가 그 페이지를 그리는 엔드포인트의 경로로 맞춘다. URL 패턴이 아니므로 `path`로 엔드포인트를 맞추면 안 된다(F-011-R28, F-012-R40) |
| `${키}`, `${키:기본값}` | `HTTP_ENDPOINT`의 ID와 `path`, 뷰 리졸버 빈의 `property.prefix`·`property.suffix`, 참조한 빈 이름(`spring:bean:` ID), `JAVA_METHOD`의 `viewNames`·`redirects`·`forwards`(`@Value("${키}")` 필드를 이어 붙인 자리. F-004-R23), `viewNames`에서 프로퍼티를 읽는 꼴의 호출 자리(F-004-R26), 그 이름으로 만든 `RENDERS`의 `viewName`과 미해결 `view`의 원문 | 프로퍼티 자리표시자. 분석기는 원문 그대로 남기고 링커가 프로젝트의 `.properties`·`.yml` 값으로 푼다(3.5 공통 규칙 "프로퍼티 자리표시자"). 요청 URL에 정규화되지 않고 남은 `${…}`는 링커가 `{*}`로 읽는다 |

## 4. 인터페이스

### 4.1 SPI (패키지 `io.codeatlas.core.spi`)

```java
public interface Analyzer {
    String name();
    default int order() { return 100; }
    void analyze(AnalysisContext ctx) throws Exception;   // 프로젝트 단위. 파일별 실패는 스스로 잡아 ctx.graph().report(...)
}
public interface Linker {
    String name();
    default int order() { return 100; }
    void link(AnalysisContext ctx) throws Exception;
}
public interface Exporter {
    String name();
    void export(GraphModel graph, Path outputDir, Map<String,String> options) throws Exception;
}
public interface AnalysisContext {
    Path projectRoot();
    ProjectFiles files();          // files.ofType(FileType...), files.all()
    GraphModel graph();
    AnalysisOptions options();     // classpath, ddlPaths, encoding, dialect, excludes, extra(Map)
}
public record SourceFile(Path absolutePath, String relativePath, FileType type, Charset encoding) {
    SourceFile(Path absolutePath, String relativePath, FileType type);   // encoding=null(판별)
    String readText();             // 인코딩 판별 포함. encoding이 있으면(--encoding) 그것만 쓴다. 읽지 못하면 UncheckedIOException
}

/** 다른 언어 안에 박힌 코드 조각. */
public record EmbeddedSource(
    String language,          // "sql" 또는 "js"
    String ownerNodeId,       // 이 조각의 주인 노드 ID. 소비자는 추출한 엣지의 from으로 이 ID를 쓴다
    String text,              // 코드 본문
    SourceLocation origin,    // 조각이 시작하는 호스트 파일 위치
    boolean exactPositions,   // true면 text가 호스트 파일의 연속 구간 그대로라 origin에 줄·열을 더해 실제 위치를 계산할 수 있다
    Map<String,String> attrs  // sql: dialect(선택), variant(동적 SQL 전개본 번호) 등
) {}
// AnalysisContext에 추가:
//   void addEmbedded(EmbeddedSource src);
//   List<EmbeddedSource> embedded(String language);
//   default void progress(String activity);   // 지금 처리하는 것(파일의 상대 경로 등). null이면 지운다
//   default String currentActivity();         // 마지막으로 알린 값. 메모리 부족 안내에 쓴다
```

내장 소스 규칙
- **주인 노드는 생산자가 만든다.** 매퍼 구문이면 mybatis가 `SQL_STATEMENT` 노드(`mybatis:stmt:<ns>.<id>`)를 `declare`하고 그 ID를 `ownerNodeId`로 넘긴다. `@Select` 같은 애너테이션 SQL은 java가 `mybatis:stmt:<인터페이스 바이너리 이름>.<메서드명>` 노드를 `declare`하고 넘긴다. JSP 인라인 스크립트는 jsp가 `JS_MODULE` 노드(`js:module:<JSP 상대경로>`)를 `declare`하고 넘긴다.
- **소비자는 주인 노드에 엣지와 속성을 덧붙인다.** sql은 `READS`, `WRITES`, `JOINS`, `CALLS`(→ROUTINE)를 주인 구문에서 내보내고 `statementType`을 채운다. web은 주인 모듈 아래에 `JS_FUNCTION`, `REQUESTS` 등을 만든다.
- 동적 SQL은 mybatis가 한 구문에 대해 전개본을 하나 이상 등록할 수 있다(같은 `ownerNodeId`). sql은 전개본들의 결과를 합친다. 전개본 1번은 조건을 평가하지 않고 모든 `<if>` 본문을 넣고 `<choose>`는 첫 `<when>`을 택한다. 예외(2차 수정, F-009-R14): 조건이 "식 == 리터럴" 꼴인 `<if>`(`userSe == "GNR"`, `'A'.equals(v)` 등)는 그 식을 구문 전체에서 값 하나로 고정해 값이 맞는 `<if>`의 본문만 넣는다. 전개본 1번은 식마다 처음 나온 값을 쓰고, 식의 다른 값마다 그 값만 바꾼 전개본을 하나씩 더 등록한다(`<choose>`의 다른 갈래와 같은 방식. 한 구문의 상한은 16개이고 넘으면 `UNSUPPORTED_SYNTAX` WARN). 3차 수정부터 한 구문(include한 조각 포함) 안에서 조건이 같은 `<when>`은 함께 움직인다(F-009-R23): 조건의 참·거짓을 구문 전체에서 고정하고 `<choose>`마다 참인 첫 `<when>`(없으면 `<otherwise>`)을 넣는다. 등치 꼴의 `<when>`은 같은 식을 쓰는 등치 `<if>`와 같은 값으로 움직이고, 그 밖에는 test 문자열이 같은 `<when>`끼리 함께 참이거나 거짓이다. 조건이 겹치는 `<choose>`의 분기가 엇갈린 전개본이 없어져 `variants`가 줄 수 있다. 노드·엣지·내장 소스의 키는 바뀌지 않는다. 한 텍스트에 문장이 연달아 든 내장 소스는 sql이 문장 경계에서 잘라 같은 주인에 합친다. `exactPositions=false`인 조각에서 나온 엣지의 위치는 `origin`을 그대로 쓴다.
- mybatis는 `${}` 치환 자리를 `__DYNAMIC__` 식별자로 바꿔 넘기고 그 자리의 원문을 내장 소스 attrs `dynamicParts`에 싣는다. sql은 그것이 테이블 자리에 있으면 `unresolved:sql-dynamic:<구문 ID>` 미해결 노드로의 엣지로 기록한다. 내장 소스 attrs의 키와 자리표시자(`__DYNAMIC__`, `__JSP_EXPR__`, `{*}`)의 약속은 3.9에 있다.
- 애너테이션 SQL(java)과 XML 빈의 인라인 SQL(springxml)은 호스트 파일의 연속 구간이 아니므로 `exactPositions=false`로 넘긴다.

- 분석기 하나가 예외를 던지면 파이프라인은 `ERROR` 진단을 남기고 다음 분석기로 간다. 링커와 출력기도 같다. 진단 코드는 `ANALYZER_FAILED`, `LINKER_FAILED`, `EXPORTER_FAILED`이고 메시지에 구현체의 `name()`과 예외가 들어간다. `Exception`, `StackOverflowError`, `LinkageError`를 잡는다. `ServiceLoader`가 구현체를 만들지 못하면 `SERVICE_LOAD_FAILED`를 남기고 나머지로 진행한다.
- **힙이 모자라면 계속하지 않는다**(F-001-R39). 분석기·링커·마무리·저장·출력기 실행 중 `OutOfMemoryError`가 나면 파이프라인은 다음 구현체로 넘어가지 않고 `io.codeatlas.core.pipeline.AnalysisOutOfMemoryError`(`OutOfMemoryError`의 하위 타입, 원래 오류는 `getCause()`)로 바꿔 던진다. `stage()`(`분석기`·`링커`·`마무리`·`저장`·`출력기`), `component()`(구현체의 `name()`), `activity()`(처리 중이던 것)가 있고, `getMessage()`는 그것과 대처 방법(큰 데이터 스크립트를 `--exclude`로 빼거나 `-Xmx`로 힙을 늘린다)을 담은 한 줄이다. 안내를 만들 메모리도 없으면 원래 오류가 그대로 나온다. `Pipeline.run`·`analyze`·`export` 모두 같다. cli는 이것을 잡아 종료 코드 3으로 끝낸다(4.5).
- **처리 중인 것 알리기.** `AnalysisContext.progress(String activity)`로 구현체가 지금 처리하는 것(보통 파일의 상대 경로)을 알리고 `currentActivity()`로 마지막 값을 읽는다. 둘 다 default 메서드라 이 훅을 모르는 구현체와 `AnalysisContext` 구현도 그대로 동작한다(기본 구현의 `progress`는 아무것도 하지 않고 `currentActivity()`는 null. `AnalysisContext.of`가 만드는 구현은 값 하나를 덮어쓴다). 파이프라인은 구현체가 바뀔 때마다 값을 지우고, 힙이 모자라면 이 값을 `AnalysisOutOfMemoryError.activity()`에 싣는다. 값 하나를 덮어쓰기만 하므로 파일마다 불러도 된다. 분석기 여섯 개가 모두 이 훅을 부른다: 파일을 처리하기 시작할 때 그 파일의 상대 경로를(sql은 1패스와 구문 분석 패스 각각, 내장 SQL은 호스트 파일로. F-006-R23), 파일 단위가 아닌 단계에서는 그 단계의 이름을 알린다(java의 `Java 소스 <n>개 일괄 파싱`·`Java 미해석 호출·빈·배치 후처리`, springxml의 `Spring XML 빈·배치 정의 <n>개 파일 해석`. F-002-R16). 링커와 출력기는 부르지 않는다: 거기서 힙이 모자라면 안내에 단계와 구현체 이름만 나온다(7절).
- `--ddl`로 준 파일이 프로젝트 밖이면 `relativePath`는 `ddl/<파일명>` 형태의 가상 경로를 쓴다.

구현하면서 더한 것 (위 시그니처에 추가만 했다)
- `SourceFile`에 네 번째 컴포넌트 `Charset encoding`을 더했다. `--encoding` 값을 `readText()`까지 전달할 길이 필요했기 때문이다. 3인자 생성자는 그대로 쓸 수 있다. 편의 메서드: `readBytes()`, `fileName()`, `nodeId()`(`file:<상대경로>`), `location(sl, sc, el, ec)`, `location(line)`, `location()`.
- `AnalysisOptions`는 record `(List<Path> classpath, List<Path> ddlPaths, Charset encoding, Dialect dialect, List<String> excludes, Map<String,String> extra)`다. `encoding`은 지정이 없으면 `null`, `dialect`는 enum `Dialect { ORACLE, DB2, POSTGRES, AUTO }`(기본 `AUTO`. `POSTGRES`는 F-019). 대상 방언의 표준 이름(방언 변형 선택에서 쓰는 소문자 이름 `oracle`, `db2`, `postgres`)은 `Dialect`가 갖는다: `canonicalName()`은 그 방언의 표준 이름이고(`AUTO`는 `null`), `Dialect.ofCanonicalName(String)`은 표준 이름이 가리키는 대상 방언이다(대상 방언의 표준 이름이 아니면 — `null`, `mysql` 같은 대상 외 방언, `postgresql` 같은 별칭, 대문자 표기 — `null`. 별칭과 `auto`까지 읽는 것은 옵션 값을 읽는 `Dialect.parse`다). 분석기는 `Dialect`와 표준 이름을 서로 바꿀 때 이 둘을 쓰고 따로 표를 두지 않는다. `AnalysisOptions.defaults()`, `AnalysisOptions.builder().classpath(p).ddlPath(p).encoding(cs).dialect(d).exclude(glob).extra(k, v).build()`.
- `ProjectFiles`: `all()`, `ofType(FileType...)`, `byRelativePath(String)`, `countsByType()`, `size()`, `ProjectFiles.of(Collection<SourceFile>)`. 상대 경로 순으로 정렬되어 있다.
- `AnalysisContext.of(projectRoot, files, graph, options)`가 기본 구현을 만든다. `embedded(language)`는 등록한 순서의 복사본을 돌려준다.
- `Analyzer.ORDER_PRODUCER`(100), `Analyzer.ORDER_CONSUMER`(200) 상수.
- `EmbeddedSource.SQL`(`"sql"`), `EmbeddedSource.JS`(`"js"`) 상수와 `EmbeddedSource.sql(owner, text, origin, exact)`, `EmbeddedSource.js(...)` 팩터리(attrs 없음). `locationOf(startLine, startColumn, endLine, endColumn)`은 조각 안의 위치(1부터)를 호스트 파일 위치로 바꾼다: 줄은 `origin.startLine + line - 1`, 열은 조각의 첫 줄에서만 `origin.startColumn + column - 1`. `exactPositions=false`이거나 origin의 줄을 모르면 `origin`을 그대로 돌려준다.
- `Exporter.export`의 `options`에는 `AnalysisOptions.extra()`가 들어간다. CLI에서는 `analyze`와 `export` 명령의 `--opt <key>=<value>`가 `extra`를 채운다(4.5). 키 목록은 3.8에 있다.
- `Pipeline`(패키지 `io.codeatlas.core.pipeline`): `Pipeline.discover()`는 `ServiceLoader`로 구현체를 찾고, `new Pipeline(analyzers, linkers, exporters)`는 직접 받는다. 같은 `order()`끼리는 이름순, 출력기는 이름순으로 실행한다. `run(projectRoot, options, outputDir)`는 전체를 실행해 `PipelineResult(context, graphFile)`를 돌려주고, `analyze(projectRoot, options)`는 스캔부터 마무리 단계까지만 하고 파일을 쓰지 않는다. `onProgress(Consumer<String>)`로 진행 메시지를 받는다. 출력기가 진단을 더 남기면 `graph.json`을 다시 쓴다.
- 파이프라인의 일부만 실행하는 진입점
  - `withoutExporters()`: 분석기와 링커는 그대로 두고 출력기만 뺀 파이프라인을 돌려준다. 그 파이프라인의 `run`은 스캔부터 `graph.json` 저장까지만 한다(출력 폴더를 스캔에서 빼는 것은 같다). 출력기를 쓰지 않으므로 만들지 못한 출력기의 `SERVICE_LOAD_FAILED`는 남기지 않고, 분석기·링커 것은 그대로 남긴다. `analyze --no-export`가 쓴다.
  - `export(GraphModel graph, Path outputDir, Map<String,String> options)`: 분석 없이 이미 있는 그래프(보통 `GraphJson.read`로 읽은 것)로 출력기만 이름순으로 실행한다. 출력 폴더가 없으면 만들고 `graph.json`은 쓰지 않는다(출력기 계약대로 출력 폴더에 `graph.json`을 두는 것은 부르는 쪽 몫이다). 출력기가 예외로 끝나면 `EXPORTER_FAILED`, 만들지 못한 출력기가 있었으면 `SERVICE_LOAD_FAILED`를 그래프에 남기고 나머지를 계속 실행한다. `ExportResult(exporters, diagnostics)`를 돌려준다: `exporters`는 실행한 출력기 이름(실행 순), `diagnostics`는 이 실행에서 그래프에 새로 남은 진단, `failures()`는 그중 위 두 코드의 진단, `failed()`는 그런 진단이 있는지다. `export` 명령이 쓴다.
- `Pipeline`이 만드는 `ProjectInfo`에는 `analyze`에 준 `AnalysisOptions`가 그대로 들어간다(`graph.project().options()`). `options`가 null이면 `AnalysisOptions.defaults()`다.

### 4.2 FileType
`JAVA`, `MYBATIS_MAPPER`(루트 요소가 `mapper`인 XML), `SPRING_XML`(루트 요소가 `beans`인 XML), `XML`, `SQL`(`.sql .ddl .pks .pkb .pls .plb .prc .fnc .trg .db2 .spsql .pgsql`), `JS`(`.js .mjs .cjs .jsx`), `TS`(`.ts .tsx`, `.d.ts` 제외), `JSP`(`.jsp .jspf .tag`), `HTML`, `PROPERTIES`, `YAML`, `JAR`, `OTHER`.

`HTML`은 `.html .htm`, `PROPERTIES`는 `.properties`, `YAML`은 `.yml .yaml`, `JAR`는 `.jar`다. 확장자는 대소문자를 가리지 않는다. XML의 루트 요소는 파일 앞 64KB에서 선언, 주석, DOCTYPE을 건너뛰고 찾으며 네임스페이스 접두사는 뗀다(`beans:beans` → `beans`). 루트 요소를 찾지 못하면 `XML`이다. 종류가 `XML`인 파일 가운데 루트 요소가 `decorators`·`sitemesh`(SiteMesh 설정), `tiles-definitions`(Tiles 설정)인 것은 jsp가 읽는다(레이아웃 관계. F-011-R21·R22). 종류를 따로 두지 않았다.

기본 제외 디렉터리: `.git`, `node_modules`, `target`, `build`, `dist`, `out`, `.idea`, `.gradle`, `bin`.
- `.git`, `node_modules`, `.idea`, `.gradle`은 어느 깊이에 있든 제외한다.
- `target`, `build`, `dist`, `out`, `bin`은 프로젝트 루트부터 그 디렉터리까지의 조상 경로에 `src` 디렉터리가 있으면 제외하지 않는다. `src/main/java/com/x/batch/out/`은 Java 패키지이므로 스캔한다. 프로젝트 루트 바깥의 경로는 보지 않는다.
- 기본 규칙으로 건너뛴 디렉터리마다 `INFO` 진단 `EXCLUDED_DIRECTORY`를 남긴다.
- 출력 폴더가 프로젝트 안에 있으면 그 폴더도 스캔하지 않는다.
- 심볼릭 링크는 따라가지 않는다.

`--exclude` glob은 상대 경로(`/` 구분)에 맞춘다(Java `PathMatcher`의 glob 문법). `*.min.js`나 `generated`처럼 앞에 경로가 없는 패턴은 어느 깊이에서든 맞고, `/`로 시작하는 패턴은 루트에서만 맞는다. 디렉터리가 맞으면 그 아래 전체를 뺀다. 해석하지 못한 패턴은 `WARN` 진단 `INVALID_OPTION`을 남기고 무시한다.

`--ddl` 경로
- 파일을 직접 주면 확장자와 상관없이 `SQL`로 넣는다. 기본 제외 디렉터리나 `--exclude`에 걸린 파일이어도 넣는다.
- 폴더를 주면 그 아래의 SQL 확장자 파일을 모두 넣는다. 프로젝트 밖 폴더의 파일은 `ddl/<폴더 기준 상대 경로>`가 된다.
- 가상 경로가 이미 있는 경로와 겹치면 `ddl/_2/<파일명>`, `ddl/_3/<파일명>`처럼 번호를 붙인다.
- 없는 경로는 `WARN` 진단 `FILE_NOT_FOUND`를 남기고 넘어간다.

### 4.3 인코딩
UTF-8로 엄격하게 디코딩해 보고 실패하면 MS949로 읽는다. `--encoding`을 주면 그것만 쓴다.

- BOM이 있으면 판별보다 먼저 따른다(UTF-8, UTF-16LE, UTF-16BE). 읽은 문자열에 BOM 문자는 넣지 않는다.
- MS949로 읽을 때 잘못된 바이트는 대체 문자(U+FFFD)로 바꾸고 실패로 보지 않는다.
- 구현은 `io.codeatlas.core.io.TextDecoder`에 있다. `decode(bytes, 지정 인코딩 또는 null)`은 문자열과 실제로 쓴 인코딩을 돌려준다.

### 4.4 graph.json

```json
{ "schemaVersion": 1,
  "project": { "name": "...", "root": "...", "analyzedAt": "ISO-8601",
               "options": { "dialect": "AUTO", "encoding": null, "classpath": [], "ddlPaths": [], "excludes": [], "extra": { } } },
  "nodes": [ { "id": "...", "kind": "...", "name": "...", "qualifiedName": "...",
               "location": { "file": "...", "startLine": 1, "startColumn": 1, "endLine": 1, "endColumn": 1 },
               "attrs": { } } ],
  "edges": [ { "from": "...", "to": "...", "kind": "...", "confidence": "RESOLVED", "location": null, "attrs": { } } ],
  "diagnostics": [ { "severity": "WARN", "code": "...", "message": "...", "location": null } ] }
```

`GraphJson.write(GraphModel, Path)`와 `GraphJson.read(Path)`를 core가 제공한다(패키지 `io.codeatlas.core.io`). 노드와 엣지는 ID 순으로 정렬해 써서 결과 비교가 가능하게 한다.

- 파일은 UTF-8이고 노드, 엣지, 진단을 한 줄에 하나씩 쓴다. 줄 단위 diff로 두 결과를 비교할 수 있다.
- 정렬: 노드는 `id`, 엣지는 `from`, `to`, `kind` 이름, `location` 순, 진단은 `location`, `severity`, `code`, `message` 순(위치 없는 것이 먼저), `attrs`는 키 순.
- `location`이 없으면 `null`, `attrs`가 없으면 `{}`를 쓴다. 필드를 생략하지 않는다.
- `project.root`는 분석한 절대 경로(`/` 구분), `analyzedAt`은 초 단위 UTC 시각이다. `analyzedAt`과 `root`만 빼면 같은 입력에서 같은 파일이 나온다.
- `project.options`는 분석할 때 준 `AnalysisOptions`다(`schemaVersion`은 그대로 1). `dialect`는 `Dialect` 이름, `encoding`은 지정한 인코딩의 이름(지정이 없으면 `null`), `classpath`·`ddlPaths`는 준 순서대로 **입력 그대로의 경로 문자열**(절대 경로로 바꾸지 않는다. 구분자는 운영체제와 무관하게 `/`로 쓴다), `excludes`는 준 순서대로, `extra`는 키 순이다. 프로젝트 정보가 없는 그래프는 `options`를 `null`로 쓴다.
- `read`는 `options` 항목이 없는 파일(이 항목이 생기기 전에 쓴 것)과 항목 안에서 빠진 값을 `AnalysisOptions.defaults()`의 값으로 읽는다. 읽는 JVM이 모르는 인코딩 이름은 지정이 없는 것(`null`)으로 읽는다. 모르는 `dialect` 값은 다른 enum 값과 같이 `IOException`이다.
- `GraphJson.orderedNodes`·`orderedEdges`·`orderedDiagnostics`는 위의 정렬 순서대로 늘어놓은 목록을, `projectJson`은 `project` 항목을 돌려준다. 그래프를 다른 형식으로 내보내면서 `graph.json`과 순서를 맞춰야 하는 출력기(뷰어: F-016-R49)가 쓴다.
- `read`는 `schemaVersion`이 1이 아니거나 내용을 해석하지 못하면 `IOException`을 던진다. 읽은 그래프는 병합 규칙을 다시 적용하지 않고 저장된 그대로다.

### 4.5 CLI

```
codeatlas analyze <projectDir> [--ddl <파일 또는 폴더>]... [--classpath <jar 또는 폴더>]...
                  [--out <dir>] [--encoding <이름>] [--dialect oracle|db2|postgres|auto] [--exclude <glob>]...
                  [--exclude-tests] [--opt <key>=<value>]... [--no-export] [--quiet]
codeatlas summary <graph.json>
codeatlas erd          [<graph>] [--table <이름>]... [--depth <n>] [--pattern <glob>]... [--no-inferred] [--min-evidence <n>]
                       [--views] [--columns all|keys|none] [--no-comments] [--format mermaid|plantuml|dbml] [--spec]
codeatlas classdiagram [<graph>] [--package <접두>] [--no-subpackages] [--type <이름>]... [--depth <n>]
                       [--members all|public|none] [--hide-accessors] [--relations extends,implements,associates,depends,injects]
                       [--external] [--external-supertypes] [--no-nested] [--anonymous] [--max-types <n>] [--group-by-package]
                       [--format mermaid|plantuml]
codeatlas calltree     <검색어 또는 노드 ID> [--callers] [--depth <n>] [--max-nodes <n>] [--no-impl] [--no-inject] [--no-collapse]
                       [--show-external] [--show-accessors] [--views] [--layouts] [--include-data-load] [--no-location]
                       [--kind <NodeKind>]... [--pick <번호>] [--format text|json|mermaid]
codeatlas impact       <테이블 | 테이블.컬럼 | 노드 ID> [--depth <n>] [--include-data-load] [--format md|json]
codeatlas crud         [<graph>] [--by statement|method|endpoint|routine] [--layout matrix|by-table|list] [--format csv|md]
                       [--bom] [--depth <n>] [--include-data-load]
codeatlas find         <검색어> [--kind <NodeKind>]... [--limit <n>]
codeatlas report       [<graph>] [--max-items <n>]
codeatlas export       [<graph>] --out <dir> [--opt <key>=<value>]... [--quiet]
codeatlas help [<명령>] | --help | --version
```

`<graph>`는 `graph.json` 또는 `analyze` 출력 폴더다. `erd`부터 `report`까지는 모두 `--graph <graph>`와 `--out <파일>`도 받는다(아래 "조회용 하위 명령"). `export`도 위치 인자 대신 `--graph <graph>`를 받는다.

`analyze`는 출력 폴더(기본 `./codeatlas-out`)에 `graph.json`과 `diagnostics.md`를 쓰고, 등록된 출력기를 모두 실행하고, 요약(파일 종류별 개수, 노드·엣지 종류별 개수, 신뢰도별 엣지 수, 진단 수, 그리고 해당할 때만 힌트)을 표준 출력에 찍는다. 요약의 노드·엣지 종류별 수와 신뢰도별 엣지 수는 그래프 그대로다(종류별 합이 총수와 같다). `resolvedBy`가 있는 미해결 노드(3.5)와 그리로 가는 미해결 엣지도 그 수에 들어 있고, 그런 것이 있으면 신뢰도 표 아래에 한 줄(`(UNRESOLVED 가운데 참조 n개와 그리로 가는 엣지 m건은 같은 지점에서 접두 일치로 풀렸다: resolvedBy. report.md의 "미해결 참조"와 힌트는 이를 빼고 센다)`)이 붙는다(5차 수정, F-001-R58. 전에는 그 수에서 뺐다: R56). `diagnostics.md`는 진단을 그대로 싣되(건수 유지) 풀린 참조의 `UNRESOLVED_REFERENCE` 메시지 끝에 `(접두 일치로 풀림)`을 덧붙이고 머리에 ``- `UNRESOLVED_REFERENCE` n건 가운데 m건은 링커가 같은 지점에서 접두 일치로 풀었다…`` 한 줄을 적는다(진단과 미해결 노드는 메시지 `해석하지 못한 참조(<분류>): <원문>`으로 맞춘다. F-001-R58). 조회용 하위 명령은 저장된 그래프를 읽어 F-013~F-015의 조회 API(`io.codeatlas.export.*`)를 부른다. 출력의 내용과 모양은 그 기능들의 spec이 정하고, 이 문서는 명령과 옵션만 정한다.

- 값이 있는 옵션은 `--opt 값`과 `--opt=값` 둘 다 받는다. `--classpath`는 여러 번 주거나 OS의 경로 구분자(`:` 또는 `;`)로 이어 줄 수 있다. 폴더를 주면 클래스 폴더로 쓰면서 그 바로 아래(한 단계)의 jar도 쓴다. 자바 관례의 `<폴더>/*`(`<폴더>/*.jar`) 표기도 받고 그때는 그 폴더의 jar만 쓴다. 없는 경로는 `FILE_NOT_FOUND`다(3차 수정, F-002-R17, F-001-R48).
- `--dialect`는 `oracle`, `db2`, `postgres`(별칭 `postgresql`, `pgsql`), `auto`(기본)만 받는다(그 밖의 이름은 사용법 오류). 방언별 매퍼·DDL이 여러 벌일 때 고를 방언이기도 하다. 주지 않으면 프로젝트 설정이 가리키는 대상 방언 → Oracle → DB2 → PostgreSQL → Oracle 계열 순으로 고르고, 대상 방언의 파일이 없을 때만 그 밖의 방언을 쓴다. 그 밖의 방언(mysql 등)으로 분석하려면 `--opt mybatis.dialectVariant=<방언>`을 준다(3.5 공통 규칙 "방언 변형 선택", 3.8).
- 종료 코드: 0 성공(진단이 있어도 0), 1 실행 실패(프로젝트 폴더가 없음, 출력 폴더에 쓰지 못함, 그래프 파일을 읽지 못함, 조회 명령의 대상이 없음, 출력기 실패), 2 사용법 오류(알 수 없는 명령·옵션, 빠진 값, 잘못된 인코딩·방언 이름, `<key>=<value>` 형태가 아닌 `--opt` 값, 조회 명령의 잘못된 옵션 값), 3 자원 부족(힙이 모자람. 2차 수정에서 더했다). 3일 때는 스택을 찍지 않고 표준 오류에 두 줄을 낸다: 첫 줄은 `오류: 메모리 부족: …`(파이프라인의 `AnalysisOutOfMemoryError` 메시지 그대로: 단계, 구현체, 처리 중이던 것, `--exclude`·`-Xmx` 안내. 파이프라인 밖에서 난 `OutOfMemoryError`면 원래 메시지와 `-Xmx` 안내), 둘째 줄은 JVM 옵션을 주는 방법(`JAVA_OPTS=-Xmx8g codeatlas …` 또는 `java -Xmx8g -jar codeatlas.jar …`)이다. 힙이 모자란 상태라 결과 파일을 마저 쓰지 않는다(F-001-R42).
- 요약과 조회 결과는 표준 출력에, 진행 메시지(`[codeatlas] ...`), 경고, 오류는 표준 오류에 쓴다. `--help`, `--version`도 받는다.
- `--help`(또는 `help`)는 등록된 명령의 사용법 항목만 모아 찍는다. 옵션 설명은 `help <명령>` 또는 `<명령> --help`가 그 명령 것만 찍는다. 사용법 오류도 그 명령의 사용법 항목만 붙인다(명령을 모르면 전체).
- `diagnostics.md`는 진단 수, 코드별 개수 표, 전체 목록 표(심각도 높은 순, 그다음 위치순)로 되어 있다.
- 실행 jar는 `codeatlas-cli/target/codeatlas.jar`(maven-shade-plugin)다. 모듈들의 `META-INF/services` 파일을 합치고 서명 파일(`META-INF/*.SF`, `*.DSA`, `*.RSA`, `*.EC`)과 `module-info.class`를 뺀다. `bin/codeatlas`는 이 jar를 `java -jar`로 실행하는 스크립트다(`CODEATLAS_JAR`, `JAVA_HOME`, `JAVA_OPTS` 환경 변수를 본다).

추가 옵션과 명령
- `--opt <key>=<value>`: 여러 번 줄 수 있다. `AnalysisOptions.extra`에 들어가 분석기와 링커(`ctx.options().extra()`), 출력기(`Exporter.export`의 `options`)에 그대로 전달된다. 첫 `=`까지가 키이고, 값에는 `=`가 들어갈 수 있으며 비어 있어도 된다. 키가 비었거나 `=`가 없으면 사용법 오류다. 같은 키를 다시 주면 나중 값이 남는다. 키의 뜻은 그 키를 읽는 모듈의 문서가 정한다.
- `--no-export`: 출력기를 실행하지 않는다. `graph.json`과 `diagnostics.md`만 쓴다. `Pipeline.discover().withoutExporters()`(4.1)로 실행하므로 분석기와 링커의 탐색과 진단은 평소와 같다.
- `--quiet`: 진행 메시지와 요약(힌트 포함)을 찍지 않는다. 결과 파일은 그대로 쓰고 오류는 표준 오류에 나온다.
- `--exclude-tests`: 테스트 소스를 뺀다. `--exclude src/test`와 같다: 스캐너(4.2)가 앞에 경로가 없는 패턴을 어느 깊이에서든 맞추므로 루트의 `src/test`와 하위 모듈의 `<모듈>/src/test`가 모두 빠진다. `graph.json`의 `project.options.excludes`에 `src/test`로 저장된다. 기본은 테스트 소스도 분석한다(F-001-R43).
- 힌트: 요약의 진단 절 뒤에 `힌트` 절을 두고, 진단과 그래프에서 다음이 확인될 때만 한 줄씩 적는다. 해당이 없으면 절 자체가 없다. `summary`도 저장된 그래프와 옵션(`project.options`)으로 같은 힌트를 낸다(F-001-R44).

| 조건 | 안내 |
|---|---|
| 진단 `DIALECT_VARIANT_MAPPERS`가 있음 | mybatis의 요약(감지한 방언, 쓴 방언)과 `--opt mybatis.dialectVariant=<방언\|all>` |
| 미해결 `java-call`로 가는 `CALLS`가 50건을 넘고 `--classpath`가 비어 있음(`resolvedBy`가 있는 대상으로 가는 엣지는 세지 않는다. F-001-R56) | 의존 jar를 `--classpath`로 주라는 안내 |
| 위 미해결의 절반 넘게가 `src/test` 아래에서 나왔고 테스트를 빼지 않았음 | `--exclude-tests` |
| 위치가 SQL 파일인 진단 `DUPLICATE_OBJECT_NAME`이 있음(Spring XML의 빈 이름 중복은 세지 않는다) | 쓸 DDL만 `--ddl`로 주거나 나머지를 `--exclude`로 빼라는 안내 |
| 진단 `DATA_LOAD_GROUPED`가 있음 | 묶은 파일 수, 산출물에서 기본으로 빠진다는 것과 `--opt export.includeDataLoad=true` |
| 진단 `DDL_NOT_APPLIED`가 있음 | 적용하지 않은 파일 수와 `diagnostics.md`에서 찾는 법 |
| 진단 `DIALECT_VARIANT_DDL`이 있음 | sql의 요약(감지한 방언, 쓴 방언). 메시지에 옵션 안내가 없으면 `--dialect <oracle\|db2\|postgres>`와 그 밖의 방언용 `--opt mybatis.dialectVariant=<방언>`을 덧붙인다. 이 진단이 있으면 위의 `DUPLICATE_OBJECT_NAME` 안내는 내지 않는다 |
| 진단 `DIALECT_NOT_FOUND`가 있음 | 요청한 방언의 파일이 없어 다른 방언으로 대체했다는 메시지와 바꾸는 옵션(`--dialect <oracle\|db2\|postgres>`, 그 밖의 방언은 `--opt mybatis.dialectVariant=<방언>`) |
| 진단 `DIALECT_NOT_TARGET`이 있음 | 방언별 여러 벌은 아니지만 SQL이 대상 외 방언으로 보인다는 메시지 한 줄(F-001-R53) |
| 매퍼 구문(`SQL_STATEMENT`. 값이 있는 구문이 없으면 `MYBATIS_MAPPER`)의 `attrs.dialectVariant`에서 가장 많은 값과, 정의 위치가 있는 `DB_TABLE`의 파일 경로에서 읽은 방언 토큰(3.5 "방언별 매퍼"와 같은 표·규칙) 가운데 가장 많은 값이 서로 다름. 진단 문구는 보지 않고, 어느 한쪽을 알 수 없으면 견주지 않는다(F-001-R52) | `경고:` 한 줄(매퍼와 DDL의 방언이 다르다) |
| 방언 진단의 심각도가 `WARN`임(대상 방언 Oracle·DB2·PostgreSQL의 파일이 없어 대상 외 방언으로 분석됨, `DIALECT_NOT_FOUND`). 프로젝트 설정과 다르게 대상 방언을 골랐다는 진단은 INFO라 올리지 않는다 | 그 힌트를 절 맨 위에 `주의:`로 올린다 |
| 진단 `UNKNOWN_COLUMN`이 있음 | 건수와 SQL이 DDL에 없는 컬럼을 참조한다는 안내(`--ddl`, `--dialect`, 그 밖의 방언은 `--opt mybatis.dialectVariant=<방언>` 확인) |
| 서버 요청(`report.md`의 "화면 요청" 절과 같은 수: 외부 URL·정적 페이지 링크·클라이언트 라우트를 뺀 `HTTP_REQUEST`. 3.4 "화면 요청 통계")은 있는데 `HTTP_ENDPOINT`가 하나도 없음 | `서버 요청 n건`과 백엔드 프로젝트와 함께 분석하라는 안내(5차 수정, F-001-R60. 전에는 외부 URL을 포함한 "화면 요청 n개"였다) |
| `HTTP_ENDPOINT`는 있는데 `clientRoute`가 아닌 `HTTP_REQUEST`가 하나도 없음 | 프런트 소스가 분석 범위에 없다는 안내 |
| `skipped=vendor`인 `JS_MODULE`·`JSP_PAGE` 노드가 있음 | 건너뛴 파일 수와 `--opt web.vendorPatterns=!<glob>`, `--opt web.skipVendor=false` |

힌트의 문구는 계약이 아니고 조건만 계약이다. 힌트에 적는 `--opt` 키는 3.8의 키여야 하고 F-001-AC47이 대조한다.
- `summary <graph.json>`: 저장된 그래프를 `GraphJson.read`로 읽어 `analyze`가 끝에 찍는 것과 같은 요약을 표준 출력에 찍는다. 출력 폴더를 주면 그 안의 `graph.json`을 읽는다. 같은 폴더에 `diagnostics.md`가 있으면 그 경로도 머리말에 적는다. 파일 수는 그래프의 `FILE` 노드(`attrs.fileType`)로 세므로 `FILE` 노드를 만들지 않는 `OTHER`, `JAR`는 빠진다.
- 하위 명령은 `io.codeatlas.cli.Command`(이름, 사용법 한 항목, 설명, 실행)를 구현해 `Main`의 레지스트리에 등록한다. `--help`의 사용법은 등록된 명령에서 모아 만든다. 명령이 `UsageException`을 던지면 `Main`이 그 명령의 사용법과 함께 알리고 2로 끝내고, `CommandFailure`를 던지면 이유를 알리고 1로 끝낸다.

조회용 하위 명령 (`erd`, `classdiagram`, `calltree`, `impact`, `crud`, `find`, `report`)
- 공통 뼈대는 `QueryCommand`다. 그래프는 첫 위치 인자 또는 `--graph <graph.json 또는 출력 폴더>`로 받고, 둘 다 없으면 현재 폴더의 `codeatlas-out`을 읽는다. 위치 인자가 검색어인 `calltree`, `impact`, `find`는 `--graph`로만 받는다. 그래프 경로를 둘 주면 사용법 오류다.
- 결과는 표준 출력에 쓴다. `--out <파일>`을 주면 그 파일(UTF-8, 없는 상위 폴더는 만든다)에 쓰고 표준 출력은 비운다. 대상이 없어 실패하면 파일을 만들지 않는다.
- 옵션은 그래프를 읽기 전에 모두 검사한다. 잘못된 옵션은 2, 그래프를 읽지 못하거나 대상이 없으면 1이다. DB 노드가 없는 그래프(DDL도 SQL도 분석하지 않은 경우)에서 `erd`, `impact`, `crud`는 "대상 없음"을 알리고 1로 끝난다.
- 옵션 이름과 조회 API의 대응

| 명령 | 옵션 → API |
|---|---|
| `erd` | `--table`→`ErdOptions.tables`, `--depth`→`neighborDepth`(기본 1), `--pattern`→`patterns`, `--no-inferred`→`includeInferred(false)`, `--min-evidence`→`minEvidence`, `--views`→`includeViews(true)`, `--columns`→`columnLevel`, `--no-comments`→`showComments(false)`, `--format`→`ErdFormat`. `--spec`은 같은 `ErdOptions`로 `TableSpecRenderer.render(graph, options)` |
| `classdiagram` | `--package`→`packagePrefix`, `--no-subpackages`→`includeSubpackages(false)`, `--type`→`types`, `--depth`→`neighborDepth`(기본 1), `--members`→`MemberLevel`, `--hide-accessors`, `--relations`→`relationKinds`(`depends`는 `DEPENDS_ON`), `--external`→`includeExternal(true)`, `--external-supertypes`→`showExternalSupertypes(true)`, `--no-nested`→`includeNested(false)`, `--anonymous`→`includeAnonymous(true)`, `--max-types`(기본 60, 0은 상한 없음), `--group-by-package`, `--format`→`ClassDiagramFormat` |
| `calltree` | `--callers`→`Direction.CALLERS`, `--depth`→`maxDepth`(기본 10), `--max-nodes`(기본 2000), `--no-impl`→`expandImplementations(false)`, `--no-inject`→`narrowByInjection(false)`, `--no-collapse`→`collapseRepeated(false)`, `--show-external`→`hideExternal(false)`(주지 않으면 `hideExternal(true)`), `--show-accessors`→`hideAccessors(false)`(주지 않으면 `hideAccessors(true)`), `--views`→`expandRenderedViews(true)`, `--layouts`→`expandLayouts(true)`, `--include-data-load`→`includeDataLoad(true)`, `--no-location`→`CallTrees.toText(tree, false)`, `--format`→`toText`/`toJson`/`toMermaid` |
| `impact` | `ImpactAnalysis.analyze(graph, 대상, --depth, --include-data-load)`(깊이 기본 30, 데이터 적재 구문은 기본으로 뺀다) → `toMarkdown()`/`toJson()` |
| `crud` | `CrudMatrix.of(graph, --by, --depth, --include-data-load)`(깊이 기본 20) → `toCsv(--layout, --bom)`/`toMarkdown(--layout)`. `by-table`은 `Layout.MATRIX_BY_TABLE` |
| `find` | `NodeFinder.find(graph, 검색어, --kind...)`를 `--limit`(기본 50)개까지. 검색어에 `*`가 있으면 cli의 `NodeGlob`(와일드카드) |
| `report` | `SummaryReport.render(graph, --max-items)`(기본 20, 0은 전부) |

- `erd`: ERD의 주석 표시는 API 기본값이 켜짐이라 끄는 옵션(`--no-comments`)만 둔다. `--spec`(테이블 명세)은 범위 옵션(`--table`, `--depth`, `--pattern`, `--views`)과 관계 옵션(`--no-inferred`, `--min-evidence`)을 ERD와 같은 규칙으로 받는다. 그래서 `--spec`만 주면 전체 테이블이고 뷰는 `--views`를 줘야 든다. 명세에 뜻이 없는 `--columns`, `--no-comments`, `--format`을 `--spec`과 같이 주면 사용법 오류다. 찾지 못한 `--table`은 `ErdModel.unresolvedNames()`(뷰라서 빠진 이름은 `excludedViewNames()`)로 알아낸다. 일부만 찾지 못하면 표준 오류에 경고하고 나머지를 그린다. 범위에 드는 테이블이 하나도 없으면 1이다.
- `classdiagram`: 모델의 경고(타입 수 상한을 넘어 멤버를 생략함)와 찾지 못한 `--type`(`ClassDiagramModel.unresolvedNames()`)은 표준 오류에 알린다. 범위에 드는 타입이 없으면 1이다.
- `calltree`: `NodeFinder`가 찾은 후보가 하나면 그 노드의 트리를 낸다. 여럿이면 목록(번호, 종류, 노드 ID, 위치)과 좁히는 방법(노드 ID를 그대로 주기, `--pick <번호>`, `--kind <NodeKind>`)을 표준 오류에 내고 1로 끝난다. 후보 목록은 30개까지 보인다. `--no-location`은 text 형식에만 적용된다. 기본값(2차 수정, F-001-R40): 컨트롤러가 그리는 JSP는 이름만 적고 그 아래(include, 스크립트, 그 화면에서 나가는 요청)는 펼치지 않으며(`--views`로 켠다. 조회 API의 기본값이 false로 바뀌었다, F-015-R33), 접근자 호출은 표준 산출물처럼 접는다(`--show-accessors`로 보인다). 소스 없는 메서드도 표준 산출물처럼 기본으로 접고 `--show-external`로 편다(3차 수정, F-001-R45. 전에 쓰던 `--hide-external`은 받되 효과가 없고 도움말에 없다). `--views`는 redirect·forward로 넘어간 핸들러의 아래도 펼치고, 레이아웃은 `--layouts`로만 펼친다. 후보가 여럿이어도 검색어와 표시 이름이 정확히 같은 후보가 종류 우선순위(`HTTP_ENDPOINT` > `JAVA_METHOD` > `SQL_STATEMENT` > `DB_TABLE` > `DB_ROUTINE` > `BATCH_JOB` > `JSP_PAGE` > `JS_FUNCTION` > 그 밖)에서 가장 앞선 종류에 하나뿐이면 그것을 고르고 표준 오류에 다른 후보의 수와 `--kind` 안내 한 줄만 낸다(F-001-R46). 전에 쓰던 `--no-views`는 오류로 보지 않고 받되 아무 효과가 없고 도움말에 없다. `--include-data-load`는 데이터 적재 구문(3.5)을 넣는다. URL로 엔드포인트 찾기(5차 수정, F-001-R49): 검색어가 `[METHOD ]<경로>` 꼴(경로가 `/`로 시작, 앞의 `http(s)://호스트` 허용)이고 `--kind`가 `HTTP_ENDPOINT`를 빼지 않았으면 `NodeFinder`보다 먼저 `EndpointLookup`이 찾는다: 쿼리·fragment를 떼고, `attrs.resolvedPath`(없으면 `attrs.path`)가 글자 그대로 같은 엔드포인트 → 경로 변수·와일드카드 패턴에 맞는 엔드포인트(가장 덜 느슨한 것) 순으로 보고, 메서드는 같거나 엔드포인트가 `ANY`면 맞으며(검색어에 메서드가 없으면 전부), 없으면 맨 앞 1~2 세그먼트(컨텍스트 경로)를 떼고 다시 본다. 후보는 맞은 엔드포인트와 같은 경로의 화면 요청이고, 엔드포인트가 정확히 하나면 요청 수와 상관없이 그것을 골라 표준 오류에 한 줄로 알린다(글자 그대로 맞았고 다른 후보가 없으면 알리지 않는다). 엔드포인트가 여럿이면 위의 목록·R46 규칙을 그대로 쓴다. 맞는 엔드포인트가 없으면 같은 경로의 화면 요청을 후보로 삼고(프런트만 분석한 그래프, 그 메서드의 엔드포인트가 없는 경로), 그것도 없으면 `NodeFinder`로 넘어간다. 화면 요청도 엔드포인트와 같은 순서로 찾는다(5차 수정, F-001-R59. 전에는 글자 그대로 같은 경로만 봤다): 글자 그대로 같은 경로 → 요청의 경로 패턴에 구체 경로 맞추기(`/api/schedule/{*}` ↔ `/api/schedule/12`, `{이름}`도 같다. 세그먼트 전체인 `{*}`는 한 세그먼트에, 세그먼트의 일부인 `{*}`(`/api{*}`)는 여러 세그먼트에 맞고 더 좁은 패턴이 있으면 그것만 남긴다. 검색어가 패턴 꼴이면 글자 그대로만) → 맨 앞 1~2 세그먼트를 떼고 다시. 엔드포인트를 찾았을 때 함께 내는 "같은 경로의 화면 요청"에도 같은 패턴 맞추기를 쓴다(`--kind HTTP_REQUEST 'PUT /api/schedule/5'`). 요청 하나만 맞았고 패턴이나 컨텍스트 경로로 맞췄으면 표준 오류에 한 줄로 알린다. URL 꼴 검색어로 아무것도 찾지 못하면 오류 문구에 안내를 덧붙인다: 메서드가 달라 못 찾았으면 `같은 경로에 다른 메서드의 엔드포인트가 있다: POST /x`(엔드포인트가 없으면 화면 요청), 그리고 `경로 변수가 든 URL은 패턴 꼴('/orders/{id}', 화면 요청은 '/api/orders/{*}')로도 찾는다`. 화면 요청은 메서드 후보를 따른다: `httpMethod=ANY`이고 `httpMethods`가 있으면 검색어의 메서드가 그 후보에 든 것일 때만 맞고, 후보가 없는 `ANY` 요청은 어느 메서드에나 맞는다. `--kind`가 `HTTP_ENDPOINT`를 빼고 `HTTP_REQUEST`만 남겨도 URL로 찾은 요청이 후보다. `selfSubmit=true`인 요청(경로 자리가 `{self}`)은 경로가 없으므로 어느 URL에도 맞지 않는다. 후보 목록(`calltree`, `find`)은 메서드 후보가 있는 요청의 줄 끝에 `[POST|PUT]`처럼 후보를 적는다(노드 ID는 `ANY` 그대로다. 7차 수정, F-001-R55). 도움말은 콜트리 줄 끝의 `(인자 구문)`·`(접두 일치)`·`(동적 뷰 이름 후보)`, 요청 이름의 `POST|PUT`·`(자기 페이지로 제출)`, `[ambiguous n]`(n은 2 이상. 후보 엔드포인트 여럿과 맞은 요청 포함)을 설명한다(표기는 export의 것이다. F-001-R57). `--show-accessors`와 `--layouts`가 무엇을 더 펴는지(부수 효과 없는 생성자, `*Example` 빌더, `[shared]` 공통 JSP)는 조회 API의 몫이고(F-015) 도움말에 적는다(F-001-R51).
- `impact`: 대상은 테이블·뷰·컬럼만이다(API의 범위). 찾지 못하면 리포트에 담긴 비슷한 이름의 DB 노드(`ImpactReport.suggestions()`, `NodeFinder`의 규칙)를 10개까지 보여 주고 1로 끝난다. 다른 종류의 노드 ID를 주면 `calltree --callers`를 안내한다.
- `crud`: 테이블 접근이 있는 주체가 없으면 1이다. `--bom`은 csv에만 쓴다(md와 같이 주면 사용법 오류). `--depth`는 `--by endpoint`에서는 엔드포인트에서 구문까지의 걸음 수이고, 그 밖의 기준에서는 루틴 호출을 따라가는 걸음 수다(`export.crud.depth`와 같은 뜻, 3.8. 2026-10-11: 전에는 `--by endpoint`에만 쓰였다).
- `find`: 한 줄에 `번호  종류  노드 ID  위치`. 열은 공백으로 맞추되 ID 열의 맞춤 폭은 80자까지다. `--kind`는 대소문자와 `-`/`_`를 가리지 않고 쉼표로 여러 개를 받는다. 결과가 `--limit`보다 많으면 전체 개수를 표준 오류에 알린다. 없으면 1이다. 검색어에 `*`가 있으면 와일드카드로 보고 이름 전체에 대소문자 무시로 맞춘다(그 글자 그대로인 노드 ID가 있으면 그 노드. 견주는 이름은 `NodeFinder`가 "이름이 같음"을 볼 때의 것과 같다). `*`가 없는 검색어가 이름이 같은 노드에서 멈춰 부분 일치를 보이지 않았으면 검색어가 든 노드의 수와 `'*검색어*'`를 표준 오류에 알린다(F-001-R50).

`export` 명령
- 저장된 그래프를 읽어 `Pipeline.discover().export(graph, outputDir, options)`(4.1)로 등록된 출력기(표준 산출물 `export`, `viewer`)를 이름순으로 실행한다. 분석기와 링커는 실행하지 않는다. `--opt`는 `analyze`와 같은 규칙으로 해석한다.
- 출력기의 `options`는 그래프에 저장된 `analyze` 때의 `--opt`(`project.options.extra`, 4.4)를 기본으로 하고 이 명령의 `--opt`로 같은 키를 덮어쓴 것이다. `export`의 `--opt`는 `graph.json`에 저장하지 않는다(다른 폴더에 쓰는 `graph.json`에도 원래 분석 때의 옵션이 남는다). 옵션이 저장되지 않은 그래프에서는 이 명령의 `--opt`만 쓴다.
- `--out`은 필수다. 그래프가 있는 폴더가 아니면 그 폴더에 `graph.json`과 `diagnostics.md`도 써서 `analyze` 출력 폴더와 같은 구성이 되게 한다(출력기 계약: 출력 폴더에 `graph.json`이 있다). 그래프가 있는 폴더를 그대로 주면 `graph.json`과 `diagnostics.md`는 건드리지 않고 산출물만 덮어쓴다.
- 출력기가 실행 중에 남긴 진단(`INVALID_OPTION` 등)은 표준 오류에 찍는다(`ExportResult.diagnostics()`). 출력기가 예외를 던지거나 만들지 못한 출력기가 있으면(`ExportResult.failed()`) 나머지를 실행한 뒤 1로 끝난다. 등록된 출력기가 하나도 없어도 1이다.

실행 jar 패키징
- 매니페스트에 `Enable-Native-Access: ALL-UNNAMED`를 넣는다. web 모듈이 tree-sitter 네이티브 라이브러리를 `System.load`로 올릴 때 JDK 24부터 나오는 restricted method 경고를 없앤다. `java -jar`로 실행할 때만 적용되므로 `bin/codeatlas`에는 따로 옵션을 넣지 않았다. jar를 classpath에 넣어 `io.codeatlas.cli.Main`을 직접 실행하면 `--enable-native-access=ALL-UNNAMED`를 줘야 경고가 없다.
- 의존 jar의 리소스는 그대로 합쳐진다: tree-sitter 바인딩 jar들의 `lib/<arch>-<os>-tree-sitter*.{dylib,so,dll}`, viewer 모듈의 템플릿과 Mermaid 번들. `CodeatlasJarIT`가 실행 jar로 JS·TSX 분석과 `viewer.html` 생성을 확인한다.

### 4.6 테스트 지원 (`io.codeatlas.core.testing.TestProject`)

다른 모듈이 단위 테스트에서 쓰는 가짜 프로젝트다. core의 main jar에 들어 있고 JUnit에 의존하지 않는다.

```java
try (TestProject project = TestProject.create()                  // 임시 디렉터리. close 때 지운다
        .file("src/main/java/com/a/B.java", "package com.a; class B {}")
        .options(AnalysisOptions.builder().dialect(Dialect.ORACLE).build())) {
    AnalysisContext ctx = project.run(new MyAnalyzer(), new MyLinker());
}
```

- `TestProject.create()`, `TestProject.at(dir)`(기존 폴더, close 때 지우지 않음), `TestProject.of(Map<경로, 본문>)`.
- `file(path, text)`, `file(path, text, charset)`, `bytes(path, byte[])`, `files(Map)`, `options(AnalysisOptions)`, `root()`.
- `context()`는 실제 스캐너로 스캔해 FILE 노드만 든 새 그래프의 컨텍스트를 만든다.
- `run(Object... analyzersAndLinkers)`는 새 컨텍스트에서 분석기 전부(order 순), 링커 전부(order 순)를 실행한다. `TestProject.run(ctx, ...)`은 미리 채운 컨텍스트에서 실행한다.
- 파이프라인과 다른 점: 예외를 삼키지 않고 던진다. 마무리 단계를 하지 않는다(필요하면 `ctx.graph().resolveDanglingEdges()`).

## 6. 검토한 대안

| 대안 | 채택하지 않은 이유 |
|---|---|
| 분석기가 파일 단위로 호출되는 SPI | Java는 프로젝트 전체를 한 번에 넣어야 바인딩이 해석된다. 프로젝트 단위 SPI가 더 일반적이다. |
| 분석기끼리 직접 API 호출로 연결 (예: mybatis가 sql 파서를 호출) | 모듈 사이 의존이 얽히고 병렬 개발이 어렵다. ID 규칙, 내장 소스, 링커로 대신한다. ([ADR-0007](../../decisions/0007-pass-embedded-code-via-embedded-sources.md)) |
| 테이블 ID에 스키마 포함 | SQL은 대부분 스키마 없이 테이블을 쓰므로 DDL과 SQL의 ID가 어긋난다. 스키마는 속성으로 두고, 같은 이름이 여러 스키마에 있으면 진단을 남긴다. ([ADR-0008](../../decisions/0008-reference-tables-by-name-and-normalize-in-linker.md)) |
| Lombok 애너테이션 처리기를 실행하거나 delombok한 소스를 요구 | 의존 jar가 없거나 컴파일되지 않는 프로젝트도 분석해야 한다. Lombok이 만들 멤버를 분석기가 합성한다. ([ADR-0009](../../decisions/0009-synthesize-lombok-members-without-annotation-processing.md)) |
| 방언별로 여러 벌인 매퍼·DDL을 전부 병합, 경로순으로 선택, 프로젝트 설정을 우선해 선택 | 병합하면 한 구문의 테이블이 방언들의 합집합이 되고 없는 테이블과 중복 엣지가 생긴다. 경로순은 테이블마다 다른 방언이 뽑힌다. 설정 우선은 템플릿의 기본 설정(MySQL) 때문에 대상 외 방언이 뽑힌다. 대상 방언을 먼저 고르는 순위로 한 벌만 쓴다. ([ADR-0010](../../decisions/0010-select-one-dialect-variant-by-target-first-ranking.md), 제안 상태) |
| 팬아웃 엣지를 분석기·링커가 좁혀서 기록, 또는 소비자도 좁히지 않음 | 좁히는 데 필요한 수신자 문맥은 호출 경로마다 다르므로 엣지 하나에 담을 수 없다. 좁히지 않으면 제네릭 DAO 하나가 모든 매퍼의 구문으로 퍼져 콜트리와 CRUD가 부푼다. 그래프에는 전부 남기고 소비자가 경로의 문맥으로 좁힌다. ([ADR-0011](../../decisions/0011-keep-fanout-edges-and-narrow-in-consumers.md), 제안 상태) |

## 7. 위험과 미확정 사항
- 같은 이름의 테이블이 여러 스키마에 있는 프로젝트에서는 노드가 합쳐진다. 진단 `DUPLICATE_OBJECT_NAME`으로 드러낸다.
- [확인 필요] 대상 프로젝트 규모. 그래프 전체를 메모리에 올리는 방식의 한계를 재지 않았다. (2026-10-11 변경: 공개 프로젝트 검증에서 리터럴 INSERT 수십만 건짜리 스크립트로 힙이 모자랐고 엣지 수백만 개 그래프에서 출력기가 끝나지 않았다. 데이터 적재 구문 묶기(3.5), 엣지 인덱스(F-001-R37), 산출물 분할(3.8 `export.calltree.splitOver`·`export.mermaid.maxChars`)로 그 경우는 고쳤고, 힙이 모자라면 안내와 종료 코드 3으로 끝난다(4.1, 4.5). 방식의 한계 수치는 여전히 재지 않았다.)

구현하면서 드러난 계약 수준의 한계 (각 기능 spec의 가정·한계 절과 코드에서 확인한 것. "해소(2차)"는 2026-10-11 실전 검증 2차 수정에서 없어진 한계다)

| 한계 | 영향 | 지금의 처리 |
|---|---|---|
| `sql:stmt:<파일>:<시작 줄>`은 줄 단위다 | SQL 파일의 한 줄에서 구문이 둘 이상 시작하면 ID가 같아 한 노드로 합쳐진다 | 없음(F-006 알려진 한계) |
| 같은 이름 테이블의 정의는 한 노드다 | 해소(2차): 전에는 여러 파일의 정의를 진단 없이 한 노드에 합쳐 방언별 DDL 폴더가 있는 프로젝트에서 컬럼·FK·인덱스가 섞였다. 지금은 한 파일의 정의만 쓴다(3.5 공통 규칙 "같은 테이블의 중복 정의"). 남은 것: 어느 파일을 쓸지는 경로의 방언 이름(없으면 내용 특징)과 방언 순위로 고르는 휴리스틱이고(3.5 "방언 변형 선택"), 스키마만 다른 진짜 별개의 테이블도 한 노드다 | 방언별 여러 벌은 `DIALECT_VARIANT_DDL` 한 건, 그 밖의 중복은 `DUPLICATE_OBJECT_NAME`(스키마가 다르면 WARN, 아니면 INFO)으로 알린다. 직접 정하려면 `--dialect`·`--opt mybatis.dialectVariant=<방언>`으로 방언을 정하거나 `--ddl`·`--exclude`로 한 벌만 준다. 같은 폴더 안의 파일 쌍이 많으면 INFO가 쌍마다 나온다(폴더 쌍으로만 줄인다). 스키마는 `attrs.schema`에 하나만 남는다 |
| 엣지를 지우는 API가 없다 | 링커가 더 구체적인 엣지를 더해도 원래 엣지가 남는다. 짧은 구문 ID(`shortId`)로 가리킨 `EXECUTES`는 링커가 맞는 구문을 찾아도 원래의 매달린 엣지가 마무리 단계에서 `UNRESOLVED` 노드로 남고, 빈으로 가는 `INJECTS`·`USES`(`unresolvedBeanRef`)도 타입으로 푼 엣지와 함께 남는다 | 새 엣지에 `resolvedFrom`·`resolvedSource`를 남겨 짝을 찾을 수 있게 한다 |
| 엣지의 중복 제거 키가 `(from, to, kind, location)`이다 | 끝점과 위치가 같은 엣지를 둘 담을 수 없다 | sql은 한 구문 안의 같은 테이블 쌍 `JOINS`를 둘째부터 열을 1씩 밀어 구분한다(위치가 실제와 1열씩 어긋난다). link는 추론 `REFERENCES`에 서로 다른 근거 위치를 고르고 없으면 `RELATION_NOT_RECORDED`를 남긴다. springxml은 요소 대신 속성의 위치를 쓴다 |
| `replaceNode`는 노드 attrs를 옮기지 않는다 | 합쳐지는 참조 스텁(`declared=false`)에 붙어 있던 속성은 사라진다 | 없음 |
| 미해결 노드는 `(분류, 원문)`으로 하나다 | 서로 다른 곳의 같은 원문(`foo(1)` 등)이 한 노드를 공유하고 노드의 위치는 처음 것뿐이다 | 표시에는 엣지의 위치를 쓴다(3.5) |
| 루틴 ID에 시그니처가 없다 | 패키지 안의 오버로드된 프로시저·함수가 한 노드로 합쳐진다 | 없음(F-007 가정) |
| JS 함수 ID는 이름 경로다 | 같은 이름으로 다시 선언한 함수(JSP의 `<c:choose>` 갈래에 든 인라인 스크립트 포함)가 한 노드로 합쳐진다 | 이름 없는 함수 안에서 겹칠 때만 `<anonymous>@줄:열`로 구분한다 |
| classpath에 없는 타입이 든 메서드 ID | 매개변수 타입을 import로 복원하거나 소스에 쓴 이름을 쓰므로, 같은 프로젝트도 `--classpath`를 주느냐에 따라 메서드 노드 ID가 달라질 수 있다 | 없음(F-002 가정) |
| 스프링 빈 ID에 컨텍스트·프로파일·모듈이 없다 | 프로젝트의 모든 Spring XML을 하나의 빈 이름 공간으로 본다. 빈 노드는 이름 하나에 하나(`spring:bean:<이름>`)라 여러 모듈·테스트 설정에 같은 이름의 빈이 있으면 한 노드에 모이고, 빈 노드의 `class`와 위치는 main 쪽·경로순 첫 정의의 것이다. 이름 없는 빈의 번호(`#n`)는 Spring이 붙이는 이름과 다를 수 있다 | 다른 정의는 `otherDefinitions`와 정의마다의 `DEFINES_BEAN`으로 남는다. 같은 모듈의 같은 쪽에서 겹칠 때만 `DUPLICATE_OBJECT_NAME`(F-017-R13). springxml과 링커는 참조를 풀 때 참조하는 파일과 같은 모듈·테스트 여부를 가린다(F-012-R26) |
| 엔드포인트 ID에 모듈이 없다 | `http:endpoint:<METHOD> <경로>`가 같으면 여러 웹 모듈의 핸들러가 한 노드에 모인다. `MATCHES`는 노드로 가므로 "같은 모듈의 핸들러만"을 엣지로 표현하지 못한다 | 링커는 같은 모듈에 선언이 있으면 그 후보만 남기고(여러 모듈의 선언이 모인 엔드포인트면 `module`을 남긴다), 같은 모듈에 선언이 없는 연결에 `crossModule=true`를 붙이고, 소비자가 `ROUTES_TO`를 따라갈 때 요청 파일과 같은 모듈의 핸들러를 고른다(3.4 "소비자가 지키는 규칙", 3.5 `crossModule`·`module`, F-012-R21·R37). 정적 UI 모듈의 페이지에서 나간 `crossModule` 요청은 그 페이지를 그리는 핸들러의 모듈이 하나로 정해질 때만 `module`이 붙는다(F-012-R48): 그리는 핸들러를 찾지 못한 페이지(직접 여는 정적 HTML, 동적 뷰 이름을 풀지 못한 템플릿)의 요청은 여러 모듈의 선언이 모인 엔드포인트에서 여전히 모든 모듈로 퍼진다. 노드의 `location`은 선언 하나의 것뿐이다: 선언 위치 전부는 `ROUTES_TO` 엣지의 위치(핸들러마다)와 `viewDeclarations`(view-controller 선언마다)에 있다 |
| 동적 디렉터리 뷰의 후보는 근거가 있을 때만 좁힌다 | 뷰 이름의 디렉터리 자리가 실행 시점 값이면(`/{*}/login.html`) 그 자리에 실제 디렉터리를 넣어 찾은 후보로 잇는다. 프로퍼티 값(`narrowedBy=property`)이나 모듈 디렉터리 이름(`moduleName`)으로 좁히지 못하면 후보 1~5개가 모두 `candidate`로 남아 다른 서버용 정적 페이지가 섞일 수 있다. 프로퍼티를 읽는 호출은 이름과 인자 꼴로 알아보는 휴리스틱이다(지역 변수에 담았다가 핸들러에서 직접 이어 붙인 값, 필드에 담아 둔 값, `getProperty("키", "기본값")`은 읽지 않는다) | 항상 `INFERRED` + `dynamicSegment=true`이고 근거는 `narrowedBy`에 남는다(F-012-R44·R47, F-004-R26) |
| 한쪽만 PK인 조인은 PK 쪽을 부모로 추론한다 | 1:1에 가까운 부속 테이블(키를 공유하는 설정·상세 테이블. 예: 회원 `ESNTL_ID` = 보안설정 `SCRTY_DTRMN_TRGET_ID`(PK))은 방향이 반대로 나올 수 있다: 부속 테이블의 행이 주 테이블을 가리키는 선택적 0..1인데 부속 테이블이 부모인 `N:1`로 기록된다. 집합 연산 인라인 뷰의 분기마다 만든 `JOINS`(F-006-R38)는 엣지에 그 표시가 없어 같은 조인 조건에서 나온 형제 분기를 가려 방향을 맞추지 못한다(한 분기는 `pseudoKey`로 방향 미상, 다른 분기는 `N:1`일 수 있다) | 없음. 추론 관계는 `INFERRED`이고 근거 구문(`evidence`)이 엣지에 있다. 조인 상대가 셋 이상이면 `pseudoKey`로 방향을 거둔다(F-018-R14, F-018 5절) |
| 다른 웹앱 모듈의 엔드포인트로는 잇지 않는다 | 요청 모듈과 대상 모듈이 둘 다 독립 배포 단위(웹앱)면 같은 경로여도 다른 URL로 보고 잇지 않는다. 게이트웨이·프록시로 한 주소 아래 묶인 여러 war 사이의 링크는 미해결로 남는다. 웹앱인지는 `web.xml`, war 패키징, `@SpringBootApplication`으로 판정하는 휴리스틱이다 | 미해결 `http-endpoint`로 드러난다(F-012-R38) |
| 엔드포인트의 첫 세그먼트와 같은 이름의 접두는 컨텍스트 경로로 떼지 않는다 | 컨텍스트 경로 이름이 엔드포인트 경로의 첫 세그먼트와 같은 프로젝트(`/board`에 배포한 앱의 `/board/list.do`를 화면이 `/board/board/list.do`로 부른다)에서는 맞는 연결이 미해결로 남는다. 근거가 있는 접두는 web.xml의 서블릿 매핑뿐이고 프로퍼티의 컨텍스트 경로(`server.servlet.context-path`)는 읽지 않는다 | 미해결 `http-endpoint`로 드러난다. 화면이 컨텍스트 경로를 식(`${ctx}`, `request.getContextPath()`)으로 붙이면 맨 앞 `{*}`라 이 규칙과 무관하다(F-012-R49) |
| 프로퍼티 자리표시자는 정적 파일의 값으로만 푼다 | 실행 시점의 시스템 프로퍼티·환경 변수·프로파일 선택으로 값이 바뀌는 프로젝트에서는 `resolvedPath`가 실제와 다를 수 있다 | 파일·프로파일마다 값이 갈리면 풀지 않는다(3.5 공통 규칙 "프로퍼티 자리표시자"). 풀지 못한 엔드포인트는 맨 앞 자리표시자를 떼고 맞춘다(`strippedPlaceholder`, `INFERRED`) |
| 끝 슬래시만 다른 경로를 맞춘다 | 요청 메서드를 모르는(`ANY`) 요청이 끝 슬래시만 남은 꼴(`/board/{*}/`: 마지막 변수가 빈 문자열로 평가된 URL)로 오면 조각 수가 하나 적은 엔드포인트(`PUT /board/{nttId}`)에 이어질 수 있다 | `INFERRED` + `trailingSlash=true`로 구분된다(F-012 6절) |
| 루틴 안의 구문 ID도 줄 단위다 | 한 루틴의 같은 줄에 구문이 둘이면 한 노드로 합쳐진다. 오버로드된 패키지 루틴은 한 노드라 본문들의 구문과 호출이 그 노드에 섞인다 | 없음(F-008 가정) |
| 루틴의 동적 SQL은 한 루틴 안의 리터럴과 단일 대입 변수만 푼다 | 다른 루틴에서 조립해 넘긴 문자열, 조건에 따라 달라지는 문자열, 패키지 수준 상수에 담은 문자열은 미해결이다. 풀리지 않은 식의 리터럴 조각에 테이블 이름이 보여도 엣지를 만들지 않는다 | `DYNAMIC_SQL`(WARN)과 `unresolved:sql-dynamic:` 엣지로 드러내고 조각은 구문의 `sql`에 남긴다(3.6) |
| 루틴 본문의 식 안에서 부른 미정의 함수는 호출로 만들지 않는다 | DDL에 정의가 없는 사용자 함수를 식에서 부르면 `CALLS`도 미해결 엣지도 없다(내장 함수·컬렉션 접근·형 변환과 구분할 수 없어서). 호출문 형태의 미정의 이름만 `declared=false` 참조로 남는다 | 없음(F-008 5절) |
| Lombok 멤버는 처리기를 돌리지 않고 합성한다([ADR-0009](../../decisions/0009-synthesize-lombok-members-without-annotation-processing.md)) | 지원하지 않는 애너테이션(`@Delegate`, `@UtilityClass`, `@Accessors(prefix)`, `lombok.config` 등)이 만드는 멤버는 노드가 없어 그 호출이 미해결로 남는다. 합성 멤버로의 호출은 이름·인자 수로 찾으므로 오버로드가 있으면 `INFERRED`(`ambiguous`)가 된다 | 요구사항과 수용 기준은 F-002-R14(AC14~18), F-003-R11·R14(AC14~18, AC21, AC22), F-004-R3(AC13)에 있다 |
| JDT가 해석하지 못한 호출에서는 제네릭 타입 인자를 따라가지 않는다 | `getItems().get(0).getPrice()`처럼 원소 타입이 필요한 체인과, 해석되지 않은 호출에 넘긴 람다의 타입 없는 매개변수에서 추적이 끊긴다 | 체인당 `INFO` 1건(`externalChain`) 또는 `WARN` 미해결로 남는다(F-003 5절) |
| 인자에 해석되지 않은 값이 있는 호출은 인자 수만 확인한다 | 컴파일러가 준 후보의 인자 수가 맞으면 인자 타입이 실제로 맞는지 확인하지 않고 그 후보로 잇는다. 인자 수가 다르면 잇지 않고 미해결(`argCount`)로 둔다. 예외(2차 후속, F-003-R21): 후보의 매개변수 중 classpath에 없는 타입의 자리에 그 타입일 수 없는 해석된 인자(`String` 리터럴 등)가 왔거나, 원시 타입·final 클래스 자리에 미해석 값이 왔으면 그 후보를 믿지 않고 수신자 타입과 상위 타입에서 이름·인자 수가 같은 다른 선언을 찾는다(맞는 후보가 정확히 하나일 때만 잇는다: 모든 자리를 해석된 타입으로 확인했으면 `RESOLVED`, 미해석 타입이 섞인 자리가 있으면 `INFERRED`. 없거나 여럿이면 미해결). 인자 타입도 알 수 없는 오버로드 호출은 여전히 컴파일러의 선택을 믿는다(후보가 둘 이상이면 `INFERRED`). 3·4차 수정에서 범위를 넓혔다: 인자에 classpath에 없는 타입의 값이 있고 이름·인자 수가 맞는 후보가 둘 이상이면 컴파일러가 고른 "먼저 선언된 후보"를 그대로 쓰지 않고 인자와 매개변수의 타입을 위치별로 비교해, 맞는 후보가 정확히 하나면 그것으로 잇고 가리지 못하면 맞는 후보 전부로 `INFERRED` + `ambiguous=true`로 잇는다(F-003-R24). 컴파일러가 타입을 주지 않은 인자 식의 타입은 Lombok 합성 getter의 필드 타입, 선언을 찾은 메서드 호출식의 반환 타입, 문자열이 낀 `+` 사슬(`String`), 캐스트·`new`·클래스 리터럴 등에서 읽는다(F-003-R28) | 같은 이름·인자 수의 후보가 둘 이상이면 `INFERRED`로 낮춘다(F-003-R15, 5절 한계) |
| `OVERRIDES`는 직접 상위로만 있다 | 간접 상위(조부모 타입의 선언)까지의 관계는 엣지에 없다. 상속으로만 충족되는 인터페이스 메서드(`D extends B implements I`에서 `D`에 선언이 없고 `B`에서 물려받은 메서드가 `I`의 것을 충족)는 엣지를 만들 메서드 노드가 `D`에 없어 `OVERRIDES`가 없다 | 소비자가 전이적으로 따라간다(3.4 "소비자가 지키는 규칙"). 뒤의 경우는 처리 없음(F-003 5절 한계) |
| 주입으로 구현을 좁히는 범위 | java가 고른 후보(`candidates`)가 여럿이면 구현 타입으로 가는 `INJECTS` 엣지가 없다. export의 콜트리는 호출하는 타입에서 호출된 메서드를 선언한 타입으로 가는 `INJECTS`의 `candidates`(여럿이면 그 전부), `resolvedFrom`이 그 타입인 `INFERRED` `INJECTS`가 정확히 하나일 때의 그 대상, 그리고 2차 수정부터는 구현 타입으로 바로 가는 주입(XML 주입, 필드 타입이 구현 클래스인 주입)으로 좁힌다. 상위 인터페이스에 선언된 메서드를 하위 인터페이스 타입으로 주입받아 부르면 좁히지 않는다(주입 정보를 선언 타입에서만 찾는다). 좁히지 못하거나 좁힌 결과가 비면 구현 전부를 펼친다 | 없음(F-012-R12, F-015-R19·R34와 5절) |
| 팬아웃 엣지는 수신자 문맥이 있어야 좁힌다 | 상속 매퍼 메서드의 `EXECUTES`와 `reflection` `CALLS`는 한 노드에서 여러 타입으로 퍼진다(공개 프로젝트에서 제네릭 DAO 하나가 25개 매퍼로). 경로에 구체 수신자 타입이 없거나 맞는 엣지가 없으면 전부 따라간다 | 맞는 것을 고르지 못한 줄은 `[?]`로 표시한다(3.4 "소비자가 지키는 규칙", F-015-R29). 그래프의 엣지 자체는 좁혀지지 않는다 |
| 리플렉션 호출은 한 가지 꼴만 잇는다 | `X.getClass().getDeclaredMethod("상수", ..)` → 같은 메서드 안의 `.invoke(X, ..)`만 본다. `Class.forName(..).newInstance()`, `Constructor.newInstance`, `MethodHandle`, 프록시, 유틸리티 메서드로 감싼 리플렉션(`Reflections.invokeMethod(obj, "name", ..)`)은 잇지 않는다 | 그 꼴이 아닌 `Method.invoke`는 메서드당 `REFLECTION_CALL`(INFO) 1건. 그 밖의 리플렉션은 진단도 없다(F-003-R20, 5절 한계) |
| Lombok 합성 메서드가 낀 재정의 | 해소(2차 후속): 전에는 컴파일러가 합성 메서드를 보지 못해 `OVERRIDES`가 없었다. 지금은 모든 파일을 본 뒤 이름·매개변수 수·타입 표기로 찾아 잇는다(`lombok=true`, F-003-R22). 남은 것: 프로젝트 상위 타입만 본다. Lombok 합성 메서드가 classpath에 없는(또는 소스 없는) 상위 타입의 메서드를 구현한 경우에는 `OVERRIDES`가 없다 | 없음 |
| jar 없는 import 타입이 같은 단순 이름의 `java.lang` 타입으로 풀린다 | 해소(3차): 단일 import로 선언한 단순 이름은 컴파일러가 다른 타입(`java.lang`, 같은 패키지, 와일드카드 import한 패키지)으로 풀었어도 import의 FQN으로 다룬다. 타입 참조를 만드는 공통 지점에 두어 `ASSOCIATES`·`DEPENDS_ON`·`INJECTS`의 대상, 메서드 ID의 매개변수 타입, 호출 해석이 같은 이름을 얻는다(F-002-R20, F-003-R23). 이 규칙은 단일 타입 import에만 적용된다(와일드카드 import로 들어온 이름은 그대로다). 아래는 고치기 전의 설명이다. import한 타입이 classpath에 없으면 컴파일러(JDT)가 그 이름을 `java.lang`의 타입으로 풀어 버린다(`import org.activiti.bpmn.model.Process` → `java.lang.Process`, `Module` 등). 호출 해석에서는 그런 인자의 바인딩을 믿지 않고 걸러 내지만(F-003-R21), 구조 분석의 `ASSOCIATES`·`DEPENDS_ON`과 메서드 ID의 매개변수 타입에는 그 `java.lang` 타입이 그대로 쓰인다 | 없음(F-003 5절 한계). jar를 `--classpath`로 주면 생기지 않는다 |
| 선언되지 않은 Java 참조는 모두 `external=true`다 | 프로젝트 소스에 있어야 하는데 분석 범위에서 빠진 타입(소스 루트 밖, 파싱에 실패한 파일의 타입)도 라이브러리 타입과 같은 표시를 받는다(소스를 보지 못한 타입이라는 뜻에서 같다) | 파싱 실패는 `PARSE_FAILED` 진단으로 따로 드러난다(F-012-R14, 5절) |
| 서블릿 API의 redirect·forward는 값만 본다 | `redirects`·`forwards`는 문자열 연결·삼항식·지역 변수(세 단계까지)를 따라간 경로 패턴이다. 필드·매개변수로 받은 경로와 `RequestDispatcher`는 미해결이다. 링커가 만든 `CALLS`·`RENDERS`의 위치는 메서드 attrs의 병렬 목록(`viewNameLines`·`redirectLines`·`forwardLines`)이 있으면 호출 지점이고, 없으면(이 키가 생기기 전에 저장한 그래프) 메서드의 위치다(3차 수정에서 고쳤다. F-012-R28). 스킴이 붙은 대상(`https://…`)은 맞는 엔드포인트가 없어도 미해결로 남기지 않는다 | 전부 동적이면 미해결 `CALLS`(`http-endpoint`)로 드러낸다(F-004-R13, F-012-R13) |
| 내장 소스의 함수 이름에 JSP 식이 섞인 경우 | 해소(2차): 전에는 함수 이름과 노드 ID에 `__JSP_EXPR__`가 남았다. 지금은 그 자리가 `{*}`이고 `dynamicName=true`다(3.3, F-010-R25). 남은 것: 접두사만 다른 두 함수(`${a}Open`, `${b}Open`)는 ID가 같아 한 노드로 합쳐지고 원문은 싣지 않는다 | 없음(F-010 알려진 한계) |
| 전역 JS 호출은 함께 싣는 페이지가 확인될 때만 확정해 잇는다 | 이름만으로 프로젝트 전체에서 찾던 연결을 없앴다(오연결이 많았다). 해소(3차): 레이아웃 프레임워크(SiteMesh, Tiles)가 붙이는 레이아웃과 내용 페이지의 관계를 jsp가 기록하고(`INCLUDES`의 `via=sitemesh\|tiles`, `tilesDefinitions`·`tilesAttributes`) web이 그것으로 전역을 잇는다. 다른 모듈의 자원을 다른 URL로 싣는 `<script src>`도 경로 접미사로 푼다. 남은 것: SiteMesh의 URL 패턴이 여럿이거나 일부 경로만일 때 `meta`가 없는 페이지, `<excludes>` 패턴, Java 설정으로 만든 SiteMesh 3·Tiles 구성, 동적 로더(`loadScript(ctx + '/js/x.js')`), `<c:import url="….do">`로 끼워진 화면이 싣는 스크립트는 "함께 싣는 근거"가 없다 | 근거가 없는 호출은 그 이름의 정의가 프로젝트에 하나뿐일 때만 `loadUnverified=true`(`INFERRED`)로 잇는다. 이름이 우연히 같은 다른 화면의 함수로 이어질 수 있다. 정의가 여럿이면 잇지 않고 건수를 `CALLS_SKIPPED`에 더한다(F-010-R33·R34, F-011-R21~R23, F-010·F-011 알려진 한계) |
| 클라이언트 라우트는 선언을 읽을 수 있는 형태만 가린다 | `clientRoute=true`는 라우터 패키지(`react-router…`, `vue-router`, `next/…`)를 import한 모듈의 이동·링크와, 경로가 프로젝트가 선언한 `<Route path>`와 같은 요청에 붙는다. 객체로 선언한 라우트(`createBrowserRouter([{ path }])`), 부모에 상대적인 `<Route path="notice">`, 이름으로 가는 이동은 읽지 않으므로 그런 화면 전환은 서버 요청으로 남아 대응 없는 요청이 될 수 있다 | 없음(F-010-R30, F-010 알려진 한계) |
| 메서드와 URL의 짝은 같은 객체의 프로퍼티일 때만 안다 | 한 호출 지점의 URL 후보가 여럿이면 URL마다 `ANY` + `httpMethods`가 될 수 있고, 삼항의 조건이 어느 리터럴과 맞는지는 보지 않아 소스에 없는 메서드·URL 조합이 후보에 남는다 | 링커가 `httpMethods`의 메서드로만 잇는다. 후보가 여럿이면 `INFERRED` + `ambiguous`다(F-010-R27·R39, F-012-R39) |
| action 없는 폼은 그 페이지를 직접 그리는 핸들러가 있을 때만 잇는다 | include되는 조각에 든 폼, 정적 HTML이나 직접 여는 JSP의 폼은 그리는 엔드포인트를 찾지 못해 미해결(`<METHOD> (selfSubmit)`)로 남는다. 다른 JS 파일의 함수가 그 폼의 `action`을 대입하면 틀린 요청이 남는다(`INFERRED`). 제출 흔적은 그 폼과 짝지어 보지만(5차 수정) 페이지 본문의 글자만 본다: 제출 버튼이 `return false`로 막히고 다른 JS 파일의 함수가 제출하는 폼, `action` 없는 폼이 여럿인 페이지에서 수신자를 모르는 `.submit()`(`$(this).parents("form").submit()`)의 폼에는 요청을 만들지 않는다 | 없음(F-011-R28·R30, F-012-R40) |
| DOM에 넣는 HTML 문자열의 태그는 넣는 자리에서만 읽는다 | `.html()`·`.append()`·`innerHTML`·`document.write()`·`$()`·`insertAdjacentHTML`에 준 문자열 식만 평가한다. formatter가 `return '<a href="…">'`로 돌려주는 링크, `.prepend()`·`.after()`·`outerHTML`, 여러 문장에 걸쳐 조립한 태그의 뒷부분은 읽지 않는다. 값이 통째로 동적인 `<a href>`는 미해결 `http-url`로 남는다 | 없음(F-010-R45, F-010 알려진 한계) |
| `api=option` 요청은 추론이다 | 요청 API가 아닌 호출에 넘긴 옵션 객체의 `url`을 요청으로 잡으므로 그 호출이 정말 요청을 보내는지 모른다. 프로젝트 함수에 넘긴 설정 객체의 `url`도 잡힌다. JSX 속성의 객체, 스프레드, 두 단계 넘게 중첩된 객체, `server`·`uploadUrl` 같은 다른 키는 읽지 않는다 | `REQUESTS`가 항상 `INFERRED`이고 `optionKey`·`callee`가 남는다(F-010-R23) |
| 벤더 판정은 경로·이름·머리 주석으로 하는 휴리스틱이다 | 프로젝트가 직접 쓴 파일이 벤더로 걸려 분석에서 빠지거나, 이름을 바꿔 넣은 라이브러리가 걸리지 않을 수 있다. 모든 스크립트에 같은 라이선스 헤더를 붙인 프로젝트는 한 폴더의 파일이 전부 후보가 되어 정합성 검사("혼자만 걸렸다")로 되살아나지 못한다(최상위 함수 5개 이상이 불리는 파일만 되살아난다). 반대로 프로젝트 스크립트 폴더에 혼자 놓인 이름 모를 라이브러리는 분석 대상이 된다(F-010-R40) | 건너뛴 것은 `skipped=vendor` 노드와 폴더별 `FILE_SKIPPED`로 드러난다. `web.vendorPatterns`(`!glob`은 "벤더가 아니다")와 `web.skipVendor=false`로 바꾼다(3.8) |
| 방언별 매퍼는 파일·폴더 이름으로만 알아본다 | 방언을 읽지 못한 파일의 정의는 고르는 대상이 아니다: `Foo.xml`과 `Foo_oracle.xml`처럼 방언 파일이 하나뿐이면 전처럼 합치고 `DUPLICATE_STATEMENT_ID`를 남긴다. 낙타 표기로 붙여 쓴 이름(`OracleUserMapper.xml`)은 읽지 않는다. 고른 방언의 파일에 없는 id는 다음 순위 방언 파일의 정의를 쓴다(그 방언 고유의 SQL일 수 있다) | `DIALECT_VARIANT_MAPPERS` 메시지에 쓴 방언과 다른 방언에서 가져온 구문의 ID가 나오고 그 구문에는 `borrowedFrom`이 붙는다(F-009-R25, F-009 5절) |
| 방언 변형 선택은 대상 방언을 먼저 고른다 | 프로젝트 설정이 대상 외 방언(mysql 등)을 가리켜도 Oracle·DB2와 그 계열의 파일이 있으면 그쪽을 쓴다. 실제로 그 대상 외 방언으로 운영하는 프로젝트라면 옵션 없이 돌린 결과는 운영 중인 SQL이 아니라 Oracle용 SQL의 관계다. 방언 토큰 표와 설정 키는 mybatis와 sql이 따로 구현해 한쪽만 고치면 매퍼와 DDL의 방언이 어긋난다 | 설정과 다르게 골랐다는 것을 요약 진단(INFO)과 힌트로 알리고 `--opt mybatis.dialectVariant=<방언>`으로 바꾼다. 매퍼와 DDL의 방언이 다르면 cli가 힌트에 `경고:`를 낸다(4.5). 양쪽 테스트에 같은 표를 둔다(ADR-0010) |
| 대상 방언의 파일이 없으면 매퍼와 DDL의 방언이 드물게 갈릴 수 있다 | 방언 순위의 맨 끝(명시값·설정·대상 방언·그 계열 어디에도 들지 않는 방언)은 mybatis와 sql이 같은 순서(양쪽 경로에 다 나오는 방언의 사전순, 그다음 나머지의 사전순)를 쓰므로 보통은 같은 방언을 고른다(`mysql/`·`postgres/` 두 벌이면 둘 다 mysql. F-007-AC29, F-009-AC47). 순서는 경로만 보므로 다음 경우에는 아직 어긋날 수 있다: sql이 경로가 아니라 내용 특징으로 방언을 읽은 스크립트, 경로에는 양쪽에 다 있지만 한쪽에서는 "여러 벌"이 아닌 방언, 양쪽에 다 있는 방언이 하나도 없는 프로젝트 | 어긋나면 두 요약 진단이 각각 WARN으로 나오고 cli가 힌트에 `경고:`(매퍼와 DDL의 방언이 다르다)를 낸다(4.5, F-001-R52). `--opt mybatis.dialectVariant=<방언>`으로 맞춘다 |
| URL로 화면 요청만 찾을 때는 글자 그대로만 맞춘다 | `calltree`의 URL 검색에서 맞는 엔드포인트가 없어 화면 요청을 후보로 삼을 때는 검색어의 경로와 요청의 `path`가 글자 그대로 같아야 한다. 요청 패턴의 `{*}`에 구체 경로를 맞추지 않고 컨텍스트 경로도 떼지 않는다 | 없음. 이름이나 노드 ID로 찾는다(F-001-R55) |
| 자동으로 확인하지 못한 출력 | PlantUML ERD의 방향만 아는 관계 표기(`부모 <.. 자식`)를 포함해 PlantUML·DBML 출력은 실제 파서로 확인하지 못했다. `viewer.html`은 실제 브라우저에서 손으로 확인했다(2026-10-11, 샘플 결과: `http://localhost`로 여섯 화면, `file://` 주소로는 소유자가 직접 열어 확인. F-016-R44). 자동 테스트는 `file:` 환경을 흉내 내 스크립트를 실행하는 Node 테스트(F-016-AC71)뿐이라 이후 변경은 다시 손으로 확인해야 한다. 뷰어 테스트의 가짜 DOM은 실제 DOM의 일부만 흉내 내므로 브라우저에서만 드러나는 오류가 더 있을 수 있다 | 없음(F-013-R27, F-016 tasks의 미결 항목. architecture.md 11절) |
| 등치 `<if>`·`<choose>`의 전개본은 구문당 16개까지다 | 한 식을 16개 넘는 값과 비교하거나 분기가 많아 전개본이 16개를 넘으면, 넘는 값·분기의 본문은 어느 전개본에도 없어 그 안의 테이블·컬럼이 빠진다(전에는 모든 `<if>` 본문이 1번 전개본에 있었다) | `UNSUPPORTED_SYNTAX`(WARN)로 알린다(F-009-R14, 알려진 한계) |
| 대상 외 방언(MySQL 등)의 SQL은 관용 수준으로만 읽는다 | MySQL의 백슬래시 이스케이프(`'it\'s'`)와 줄 처음의 `#` 주석은 MySQL로 판정한 입력에서만 받는다(Oracle·DB2의 `ESCAPE '\'`, DB2·MSSQL의 `#TMP` 같은 이름과 충돌한다. 3·4차 수정에서 고쳤다: 전에는 백슬래시 이스케이프를 받지 않았고 `#`은 어디서나 주석이었다. F-006-R27·R36). MySQL 판정은 경로·옵션·본문 특징에 기대므로 근거가 없는 MySQL 조각에서는 둘 다 받지 않는다. HSQL의 `.script` 확장자는 SQL 파일로 분류하지 않는다(4.2). 그 방언 고유 구문의 뜻은 분석하지 않는다 | 없음(F-006-R18, F-007-R17. 3.4) |
| 데이터 적재 구문은 구문 여러 개를 대표하는 노드다 | `dataLoad=true` 노드의 `sql`은 첫 구문뿐이고 위치도 첫 INSERT 줄이다. 행마다의 값은 그래프에 없다. 리포트의 "SQL 구문 수"는 이 노드를 1로 센다. 적재 파일이 아닌 파일의 INSERT와 매퍼 구문의 INSERT는 묶지 않는다. 적재 파일 판정은 "10건 이상인 묶음이 있거나 INSERT뿐이고 10건 이상"이라는 휴리스틱이라, DDL과 섞인 설치 스크립트의 소량 INSERT는 묶이지 않고 구문마다 노드로 남는다 | `rowCount`와 `DATA_LOAD_GROUPED`로 건수를 알 수 있다(F-006-R15) |
| 방언별 적재 스크립트는 테이블마다 한 방언의 묶음만 남는다 | 고른 방언의 파일에 그 테이블의 적재가 조금이라도 있으면 그쪽만 남는다. 다른 방언 파일에 행이 더 많아도 합치지 않는다(jeesite의 `SYS_MENU`: oracle 예제 스크립트의 10행만 남고 mysql 전체 스크립트의 97행은 빠진다). 앱이 실제로 실행하는 스크립트가 어느 방언 것인지는 보지 않는다(simple-backend의 HSQL 초기화 스크립트 `shtdb.sql`은 같은 테이블이 oracle 데이터 스크립트에 있어 빠진다). 고르지 않은 방언의 파일이 정의 없는 테이블에 넣는 묶음과 그 파일의 낱개 DML 구문은 만들지 않는다 | 남은 묶음이 다른 방언 파일의 것이면 `borrowedFrom`, 요약 `DIALECT_VARIANT_DDL`에 만들지 않은 묶음 수와 가져온 묶음 수. 다른 방언으로 보려면 `--opt mybatis.dialectVariant=<방언>`(F-007-R20·R27·R29) |
| 처리 중인 것은 분석기만 알린다 | 링커와 출력기는 `AnalysisContext.progress`를 부르지 않는다(출력기는 컨텍스트를 받지 않는다). 거기서 힙이 모자라면 안내에 단계와 구현체 이름만 나온다. java의 일괄 파싱 중에는 파일이 아니라 `Java 소스 <n>개 일괄 파싱`이 나온다(JDT가 전체를 한 번에 파싱한다) | 없음(4.1. F-001 tasks T57) |
| props·매개변수로 받은 함수의 호출은 한 형태만 잇는다 | 함수 컴포넌트가 props로 받은 함수를 직접 호출하는 지점 ← JSX 부모가 프로젝트 함수 참조를 넘긴 경우만 `CALLS`(`viaProp`, `INFERRED`)로 잇는다. 클래스 컴포넌트의 `this.props.onSave()`, 받은 prop을 다시 넘기는 것, 인라인 함수, 스프레드·컨텍스트로 넘긴 함수, 일반 함수의 콜백 매개변수(`ajaxPost(url, data, callback)`)는 잇지 않는다 | 건수만 `CALLS_SKIPPED`(INFO)로 남긴다. 받은 객체의 메서드 호출(`opts.success(res)`)은 세지도 않는다(F-010-R15·R16, 알려진 한계) |
| `<if>` 안의 상수 조건도 고정 컬럼이 된다 | mybatis는 조건을 평가하지 않고 `<if>` 본문을 넣은 전개본을 넘기고 그 조건이 빠진 전개본은 넘기지 않는다(F-009-R4. 2차 수정부터 "식 == 리터럴" 꼴의 `<if>`만 값마다 전개본이 나뉘어 전개본 하나에는 한 값의 본문만 들어간다, F-009-R14). 그래서 `<if>` 안에만 있는 `컬럼 = 상수`도 `JOINS`의 고정 컬럼으로 남고, 그 값으로 추론 `REFERENCES`가 `N:1`로 확정될 수 있다(조건이 빠지는 실행에서는 성립하지 않을 수 있다) | 없음. 추론 관계는 `INFERRED`이고 근거 구문(`evidence`)과 값(`fixedValues`)이 엣지에 있다(F-006 5절) |
| 트리거 사건 판정의 근거는 `op`와 컬럼 엣지뿐이다 | `MERGE`의 `WRITES` 컬럼 엣지는 INSERT 쪽과 UPDATE 쪽을 구분하지 못해 `UPDATE OF <컬럼>` 트리거가 실제보다 넓게 맞는다. 테이블에서 시작한 콜트리(쓰는 구문을 모른다)는 걸린 트리거를 표시 없이 모두 낸다 | 맞는지 알 수 없는 경우는 `[trigger?]`로 표시한다(F-015-R18, 5절) |
| 식 안의 시퀀스는 본문 파서가 식으로 읽은 자리에서만 찾는다 | `SET CURRENT SCHEMA` 같은 레지스터 대입과 건너뛴 문장 안의 시퀀스 참조는 기록되지 않는다 | 없음(F-008 알려진 한계) |

계약에 있지만 구현되지 않은 것 (표에서 지우지 않고 "미구현"으로 표시했다)
- `EXECUTES` JAVA_METHOD→DB_ROUTINE(3.4): 미구현(2026-10-11 감사 오류 수정 라운드 뒤에도 그대로다. `EXECUTES`를 만드는 java·springxml·link 코드에서 확인). java 모듈은 Java 코드에서 프로시저를 직접 부르는 지점(`CallableStatement`, `SimpleJdbcCall` 등)을 찾지 않는다. 프로시저로 가는 경로는 매퍼 구문을 거치는 `EXECUTES → CALLS`뿐이다. export의 CRUD 매트릭스에는 이 엣지가 생기면 따라가는 코드가 있다(F-015 5절).
- 이름 없는 JS 함수의 `JS_FUNCTION` 노드(3.3): 미구현. 이름 조각 `<anonymous>@<줄>:<열>`은 이름 없는 함수 안의 선언이 다른 ID와 겹칠 때 구분하는 데만 쓴다.
- 풀리지 않은 동적 SQL의 리터럴 조각에서 읽은 테이블을 `INFERRED` 엣지(`partial=true`)로 남기는 안(F-008 spec 8절): 계약에 넣지 않았고 구현하지 않았다. 샘플 정답이 이 경우를 단정하지 않기 때문이다. 넣기로 정하면 이 문서 3.6을 먼저 고친다.

- [확인 필요] 한 줄에 같은 METHOD·URL의 요청이 둘 이상이면 `http:request:` ID(`…@<파일>:<줄>`)가 같아진다. 실제로 합쳐지는지 코드에서 확인하지 않았다.
