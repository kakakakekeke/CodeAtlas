---
type: spec
id: F-002
title: Java 구조 분석
status: implemented     # draft | approved | implemented
updated: 2026-10-11
---

# F-002 Java 구조 분석

## 1. 배경
PRD의 클래스 다이어그램 목표와 콜트리 목표는 Java 타입·멤버와 그 사이의 관계(상속, 구현, 연관, 의존)가 그래프에 있어야 이룰 수 있다. 이 기능은 프로젝트의 모든 Java 소스를 Eclipse JDT Core로 한 번에 파싱하고(ADR-0003) 구조 노드와 엣지를 만든다. F-003~F-005가 같은 파싱 결과 위에서 동작한다. 대상 프로젝트는 의존 jar 없이 들어오는 것이 기본이다.

## 2. 범위
- 포함: 패키지·타입·메서드·필드 노드, `CONTAINS`, `EXTENDS`, `IMPLEMENTS`, `ASSOCIATES`, `DEPENDS_ON`, `ANNOTATED_BY`, 소스 없는 타입의 참조 노드, classpath 없는 타입의 FQN 복원, 소스 루트 추정, 인코딩, 구문 오류 복구, JDK 타입과 라이브러리 타입의 구분, Lombok이 만들 멤버의 합성 노드.
- 제외: 메서드 호출·오버라이드·필드 접근(F-003), Spring·MyBatis 의미 해석(F-004), 배치(F-005), Lombok 외의 애너테이션 처리기(MapStruct 등)가 만드는 멤버.

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-002-R1 | 시스템은 프로젝트의 모든 Java 파일을 한 번의 일괄 파싱(`createASTs`)으로 처리하고, 패키지 선언과 경로로 소스 루트를 추정해야 한다. 만약 패키지와 경로가 맞지 않으면 `INFO` 진단 `SOURCE_ROOT_MISMATCH`를 남기고 그 파일도 분석한다. | 사용자 요청, ADR-0003 |
| F-002-R2 | 시스템은 타입(class, interface, enum, record, annotation; 중첩·로컬·익명 포함)마다 `JAVA_TYPE` 노드를 바이너리 이름 ID로 만들고, `JAVA_PACKAGE` 노드와 `CONTAINS`(FILE→최상위 타입, PACKAGE→최상위 타입, TYPE→멤버·중첩 타입)를 만들어야 한다. | design 3.3, 3.4 |
| F-002-R3 | 시스템은 메서드·생성자(`<init>`)·필드·enum 상수마다 `JAVA_METHOD`/`JAVA_FIELD` 노드를 만들고 design 3.5의 attrs를 채워야 한다. 메서드 ID의 매개변수는 erasure의 바이너리 이름이다. | design 3.3, 3.5 |
| F-002-R4 | 시스템은 `EXTENDS`와 `IMPLEMENTS`를 만들어야 한다. `java.lang.Object`와 암묵적 상위 타입(`Enum`, `Record`, `Annotation`)으로는 만들지 않는다. | 사용자 요청 |
| F-002-R5 | 시스템은 필드 타입으로 `ASSOCIATES`(`attrs.field`, `attrs.multiplicity`)를 만들어야 한다. 배열·컬렉션·Map(값 타입)·Optional은 원소 타입으로 풀고 배열·컬렉션·Map은 `*`다. | 사용자 요청, design 3.4 |
| F-002-R6 | 시스템은 메서드 시그니처·본문·필드 타입 인자에서 쓰는 타입 중 `EXTENDS`·`IMPLEMENTS`·`ASSOCIATES`에 없는 것으로 `DEPENDS_ON`을 타입당 한 번 만들어야 한다. | 사용자 요청, design 3.4 |
| F-002-R7 | 시스템은 타입·메서드·필드의 애너테이션마다 `ANNOTATED_BY`를 만들어야 한다. | design 3.4 |
| F-002-R8 | 소스가 없는 타입이 엣지의 대상이 되면, 시스템은 그 타입을 `attrs.external=true`인 참조 노드로 만들어야 한다. 원시 타입과 `java.lang.*`으로는 `ASSOCIATES`/`DEPENDS_ON`/`ANNOTATED_BY`를 만들지 않고, JDK 타입(F-002-R13)으로의 `ASSOCIATES`/`DEPENDS_ON`은 옵션 `java.includeJdkTypes=true`일 때만 만든다. | 사용자 요청 |
| F-002-R9 | 만약 타입이 classpath에 없어 해석되지 않으면, 시스템은 import 문으로 FQN을 복원해 external 참조로 잇고, 복원할 수 없으면 미해결 엣지(분류 `java-type`)를 남겨야 한다. | 사용자 요청, AGENTS.md |
| F-002-R10 | 만약 파일에 구문 오류가 있으면, 시스템은 복구된 AST로 가능한 만큼 분석하고 `WARN` 진단 `PARSE_FAILED`를 남겨야 한다. | AGENTS.md |
| F-002-R11 | 시스템은 MS949 인코딩 파일의 한글 식별자와 문자열을 깨뜨리지 않고 읽어야 한다. | design 4.3 |
| F-002-R12 | 시스템은 옵션의 classpath와 프로젝트 안에서 스캔된 JAR 파일을 타입 해석에 써야 한다. | 사용자 요청 |
| F-002-R13 | 시스템은 JDK 타입인지를 이름이 아니라 출처로 판정해야 한다: 바인딩이 해석된 타입은 JDK 시스템 모듈(`java.*`, `jdk.*` 모듈)에서 온 것만 JDK다. 만약 출처를 알 수 없으면(미해석·복구된 바인딩), 이름 규칙을 쓴다: `java.`, `jdk.`, `sun.`, `com.sun.`과 JDK에 실제로 들어 있는 `javax.`·`org.` 패키지 목록(`javax.sql`, `javax.naming`, `javax.xml.parsers`, `javax.transaction.xa`, `org.w3c.dom`, `org.xml.sax` 등)만 JDK다. `javax.servlet`, `javax.persistence`, `javax.inject`, `javax.validation`, `javax.annotation`, `javax.xml.bind` 같은 Java EE 패키지와 `jakarta.*`는 라이브러리다. | 사용자 요청 |
| F-002-R14 | Lombok 애너테이션(`@Getter`, `@Setter`, `@Data`, `@Value`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@RequiredArgsConstructor`, `@Builder`, `@SuperBuilder`, `@ToString`, `@EqualsAndHashCode`, `@With`, `@Slf4j`·`@Log4j2`·`@Log`·`@CommonsLog` 등 로그 애너테이션; `@Accessors`, `@Singular`, `@Builder.Default`, `@NonNull`, `@FieldDefaults(makeFinal)` 반영)이 붙어 있으면, 시스템은 애너테이션 처리기를 돌리지 않고 Lombok이 만들 멤버를 `JAVA_METHOD`/`JAVA_FIELD`/`JAVA_TYPE` 노드(`attrs.synthetic=true`, `attrs.lombok=<애너테이션 단순 이름>`, 위치는 그 멤버를 만들게 한 필드 또는 클래스 애너테이션)로 만들고 `CONTAINS`(TYPE→멤버)와, 합성 getter·setter에서 필드로 가는 `ACCESSES`(read/write)를 만들어야 한다. Lombok은 import와 단순 이름으로 인식하고(jar가 classpath에 없어도 된다), 소스에 같은 시그니처의 메서드가 이미 있으면 만들지 않는다. | 사용자 요청 |
| F-002-R15 | 시스템은 `EXTENDS`·`IMPLEMENTS` 엣지에 상위 타입에 넘긴 타입 인자를 `attrs.typeArguments`(List: 선언 순서, erasure의 바이너리 이름. 타입 변수는 그 이름, 와일드카드는 `?`, 배열은 `[]` 접미사)로 남겨야 한다. 타입 인자가 없으면 그 키를 넣지 않는다. 상위 타입이나 타입 인자가 classpath에 없어도 소스에 쓴 것을 읽어 남기고, 그때 이름은 import로 복원한 FQN, 복원하지 못하면 소스에 쓴 이름이다. | 실전 검증 2차 후속(콜트리가 제네릭 상위 서비스 아래의 DAO를 좁히는 데 쓴다) |
| F-002-R16 | 시스템은 일괄 파싱을 시작하기 전에 `Java 소스 <n>개 일괄 파싱`을, 파일별 추출을 시작할 때 그 파일의 상대 경로를 `AnalysisContext.progress`로 알려야 한다(메모리 부족 안내에 "처리 중이던 것"으로 실린다). | F-001 (메모리 부족 안내) |
| F-002-R17 | classpath 옵션으로 폴더를 주면, 시스템은 그 폴더를 클래스 폴더로 쓰면서 그 폴더 바로 아래(한 단계)의 `.jar` 파일도 classpath에 넣어야 한다(`WEB-INF/lib`, Maven `target/dependency`, Gradle `libs` 같은 폴더를 그대로 줄 수 있게). 자바 관례의 와일드카드 표기 `dir/*`(`dir/*.jar`)도 받아들이며 그때는 그 폴더의 jar만 넣는다. 없는 폴더면 `FILE_NOT_FOUND`다. | 실전 재검증 2차 후속(2026-10-11) |
| F-002-R18 | JDT가 내부 오류를 표준 오류에 직접 쓰면(headless에서 `Util.log`가 `System.err`로 떨어지는 `Status ERROR: org.eclipse.jdt.core …` 한 줄), 시스템은 그 줄을 표준 오류에 내지 않고 `WARN` 진단 `PARSE_FAILED`(위치는 그때 추출 중이던 파일, 메시지는 상태 머리말과 예외 클래스 이름을 뗀 요약)로 남겨야 한다. 같은 파일의 같은 메시지는 한 번만 남긴다. JDT 로그가 아닌 표준 오류 출력은 그대로 나가야 하고 분석이 끝나면 표준 오류는 원래 스트림이어야 한다. | 실전 재검증 2차 후속(2026-10-11)(N9-c, jeesite) |
| F-002-R19 | 일괄 파싱이 한 파일에서 예외로 끝났고 남은 파일이 그 하나뿐이면, 시스템은 그 파일을 실패로 남기기 전에 새 파서로 혼자 한 번 더 파싱해야 한다. 그래도 실패하면 전처럼 `ERROR` 진단 `PARSE_FAILED`다. | 실전 재검증 2차 후속(2026-10-11)(N9-c) |
| F-002-R20 | 파일이 단일 타입 import(와일드카드 아님)로 선언한 단순 이름은, 컴파일러가 그 import를 풀지 못해(classpath에 없는 타입) 같은 이름의 다른 타입(`java.lang`, 같은 패키지, 와일드카드 import한 패키지)으로 풀었더라도 import의 FQN으로 다뤄야 한다(import 대상이 프로젝트 소스의 타입이면 그 타입, 아니면 external 참조). 이 규칙은 타입 참조를 만드는 공통 지점(바인딩 → 타입 참조·이름 변환)에 두어 `ASSOCIATES`·`DEPENDS_ON`·`INJECTS`의 대상, 메서드 ID의 매개변수 타입, 호출 해석(F-003-R23)이 같은 이름을 얻게 한다. 해석된 import와 전체 이름으로 쓴 타입은 바꾸지 않는다. | 실전 재검증 2차 후속(2026-10-11)(java.lang 오해석, N16) |

## 4. 수용 기준

### F-002-AC1 (대상: F-002-R1, R2)
- Given 소스 루트가 둘(`src/main/java`, `module-b/src`)이고 서로의 타입을 쓰는 클래스, 패키지 선언과 경로가 맞지 않는 파일 하나
- When 분석하면
- Then 두 루트의 타입이 모두 노드가 되고 서로를 가리키는 엣지가 `RESOLVED`이며, 맞지 않는 파일은 `SOURCE_ROOT_MISMATCH` 진단과 함께 분석된다.

### F-002-AC2 (대상: F-002-R2)
- Given class, interface, enum, record, annotation과 중첩·로컬·익명 타입이 있는 파일
- When 분석하면
- Then 각 타입이 바이너리 이름 ID(`Outer$Inner`, `Outer$1`, `Outer$1Local`)와 `typeKind`, `modifiers`, `typeParameters`를 갖고, `CONTAINS`가 FILE→최상위, PACKAGE→최상위, TYPE→중첩으로 있다.

### F-002-AC3 (대상: F-002-R3)
- Given 생성자, 오버로드·제네릭·배열·가변 인자 매개변수의 메서드, 필드, enum 상수가 있는 타입
- When 분석하면
- Then ID가 `java:method:com.a.B#save(java.lang.String,int[])` 형식이고(제네릭은 erasure, 생성자는 `<init>`) `returnType`, `parameterTypes`, `parameterNames`, `signature`, `constructor`, `modifiers`, 필드의 `type`이 채워지고 `CONTAINS`(TYPE→멤버)가 있다.

### F-002-AC4 (대상: F-002-R4)
- Given 클래스 상속, 인터페이스 구현, 인터페이스 상속, enum의 인터페이스 구현
- When 분석하면
- Then `EXTENDS`와 `IMPLEMENTS`가 있고 `java.lang.Object`, `java.lang.Enum`으로 가는 엣지는 없다.

### F-002-AC5 (대상: F-002-R5)
- Given 타입이 `Order`, `List<Item>`, `Item[]`, `Map<String, Item>`, `Optional<Item>`, `int`, `String`인 필드
- When 분석하면
- Then 프로젝트 타입마다 `ASSOCIATES`가 `field`와 `multiplicity`(`1` 또는 `*`)를 갖고, `int`와 `String`으로는 엣지가 없다.

### F-002-AC6 (대상: F-002-R6, R8)
- Given 매개변수·반환·지역 변수·`new`·정적 호출로 다른 타입을 쓰는 메서드와, 같은 타입을 필드로도 가진 경우, JDK 타입(`java.util.ArrayList`)을 쓰는 본문
- When 분석하면
- Then 쓰인 타입마다 `DEPENDS_ON`이 한 번씩 있고 필드로 가진 타입과 `java.lang.*`, JDK 타입으로는 없다. `java.includeJdkTypes=true`면 JDK 타입으로도 있다.

### F-002-AC7 (대상: F-002-R7)
- Given 타입·메서드·필드에 붙은 프로젝트 애너테이션과 `@Override`
- When 분석하면
- Then 각각 `ANNOTATED_BY`가 있고 `java.lang.Override`로는 없다.

### F-002-AC8 (대상: F-002-R8, R12)
- Given 라이브러리 클래스가 든 폴더를 classpath 옵션으로 준 경우와 프로젝트 안의 jar로 둔 경우
- When 그 라이브러리 타입을 상속하는 클래스를 분석하면
- Then 두 경우 모두 `EXTENDS`가 `RESOLVED`이고 대상 노드는 `declared=false`, `external=true`다.

### F-002-AC9 (대상: F-002-R9)
- Given classpath에 없는 타입을 단일 import, 와일드카드 import, 전체 이름으로 쓰는 클래스
- When 분석하면
- Then 단일 import와 전체 이름은 그 FQN의 external 노드로 이어지고, 복원할 수 없는 이름은 `unresolved:java-type:<이름>`으로 가는 `UNRESOLVED` 엣지와 진단을 남긴다. 프로젝트 타입끼리의 관계는 그대로 `RESOLVED`다.

### F-002-AC10 (대상: F-002-R10)
- Given 세미콜론과 닫는 괄호가 빠진 파일과 정상 파일
- When 분석하면
- Then 예외 없이 끝나고 깨진 파일에 `PARSE_FAILED`(WARN)가 있으며 그 파일의 타입 노드와 정상 파일의 결과가 모두 있다.

### F-002-AC11 (대상: F-002-R11)
- Given 한글 필드명과 한글 문자열 상수가 있는 MS949 파일, BOM이 붙은 UTF-8 파일
- When 분석하면
- Then 필드 노드의 이름과 상수 값이 한글 그대로이고 `PARSE_FAILED`가 없다.

### F-002-AC12 (대상: F-002-R13, R8)
- Given `javax.servlet.http.HttpServletRequest`·`jakarta.validation.Validator`·`javax.sql.DataSource` 필드와, `javax.persistence.EntityManager`·`javax.naming.Context`를 쓰는 메서드가 있는 클래스 (라이브러리 스텁이 classpath에 있을 때와 없을 때)
- When 기본 옵션으로 분석하면
- Then 두 경우 모두 `javax.servlet`, `javax.persistence`, `jakarta.*` 타입으로는 `ASSOCIATES`/`DEPENDS_ON`이 external 참조로 있고, `javax.sql`, `javax.naming` 타입으로는 없다. `java.includeJdkTypes=true`면 그쪽도 있다.

### F-002-AC13 (대상: F-002-R13)
- Given 실행 중인 JDK의 시스템 모듈이 내보내는 패키지 전부와, Java EE·Jakarta·그 밖의 라이브러리 타입 이름 목록
- When 이름 규칙으로 판정하면
- Then 시스템 모듈의 패키지는 모두 JDK이고(하드코딩한 목록의 항목마다 실제 패키지가 있다) 라이브러리 이름은 모두 JDK가 아니다.

### F-002-AC14 (대상: F-002-R14)
- Given 클래스 수준 `@Getter`/`@Setter`와 필드 수준 `@Getter(AccessLevel.NONE)`·`@Setter(AccessLevel.PRIVATE)`, static·final 필드, `boolean active`·`boolean isAdmin`·`Boolean verified` 필드, 소스에 이미 있는 `getName()`, 필드에만 붙인 `@Getter`(static 필드 포함), 와일드카드 `import lombok.*` (lombok이 classpath에 없을 때, 애너테이션 스텁이 있을 때, 실제 jar가 있을 때)
- When 분석하면
- Then 세 경우 모두 같은 합성 메서드가 있다: static·`NONE` 필드는 빠지고, final 필드는 setter가 없고, boolean은 `isActive()`/`setActive(boolean)`, `isAdmin` 필드는 `isAdmin()`/`setAdmin(boolean)`, `Boolean`은 `getVerified()`이며, 이미 있는 `getName()`은 그대로다. 합성 노드는 `synthetic=true`, `lombok=Getter|Setter`, 필드 위치, `returnType`·`parameterTypes`·`modifiers`를 갖고 필드로 `ACCESSES`(read/write)가 있다.

### F-002-AC15 (대상: F-002-R14)
- Given `@Data`(final·`@NonNull`·초기화된 필드), `@Value`, `@NoArgsConstructor`+`@AllArgsConstructor`+`@RequiredArgsConstructor(staticName="of")`, enum의 `@AllArgsConstructor`, 같은 시그니처의 생성자·`hashCode()`를 직접 선언한 클래스의 `@AllArgsConstructor`·`@ToString`·`@EqualsAndHashCode`
- When 분석하면
- Then `@Data`는 getter, 비final 필드의 setter, 필수 필드 생성자, `equals`/`hashCode`/`toString`을, `@Value`는 getter와 모든 필드 생성자를 만들고(`lombok=Data|Value`), 생성자 애너테이션은 각자의 생성자와 `private` 생성자 + static `of(..)`를 만든다. 합성 생성자가 있으면 컴파일러의 기본 생성자 노드는 없고, 직접 선언한 시그니처는 다시 만들지 않는다.

### F-002-AC16 (대상: F-002-R14)
- Given 클래스의 `@Builder(toBuilder=true)`(`@Singular("item")`, `@Builder.Default`, 초기화된 final 필드, static 필드), 생성자에 붙인 `@Builder`, 상속 관계의 `@SuperBuilder` 둘
- When 분석하면
- Then static `builder()`, `toBuilder()`, 중첩 타입 `<Type>$<Type>Builder`(`JAVA_TYPE`, `synthetic=true`, `lombok=Builder|SuperBuilder`), 필드별 빌더 메서드, `@Singular`의 단수·복수·`clear` 메서드, `build()`, 패키지 수준의 모든 필드 생성자가 있고, 생성자에 붙인 경우는 그 매개변수만 빌더 메서드가 되며, 하위 `@SuperBuilder`의 빌더 타입은 상위의 빌더 타입을 `EXTENDS`한다.

### F-002-AC17 (대상: F-002-R14)
- Given `@Slf4j`·`@Log` 클래스, `@Getter @Setter @With @Accessors(chain=true)` 클래스와 `@Accessors(fluent=true)` 필드
- When 분석하면
- Then `private static final` 필드 `log`(`type`은 로거 타입의 FQN, `lombok=Slf4j|Log`)가 있고, chain·fluent setter의 `returnType`은 그 클래스이며 fluent 접근자의 이름은 필드 이름이고, `withXxx(..)`가 있다.

### F-002-AC18 (대상: F-002-R14)
- Given 프로젝트가 직접 만든 `@Getter`, import하지 않은 `@Data`, Spring의 `@Value`, 다른 패키지를 와일드카드로 import한 `@Setter`·`@Builder`·`@Slf4j`
- When 분석하면
- Then `attrs.lombok`이 붙은 노드와 엣지가 하나도 없다.

### F-002-AC19 (대상: F-002-R15)
- Given `class ArticleService extends CrudService<ArticleDao, Article> implements Finder<Article.Key, List<Article>>, Named, Comparable<ArticleService>`, 타입 변수·배열을 넘긴 `Middle<T, X extends Named> extends CrudService<ArticleDao, T> implements Finder<X, T[]>`, 인터페이스의 `extends Finder<String, V>`, 익명 클래스 `new Finder<String, int[]>() { }`와 다이아몬드 `new CrudService<>() { }`
- When 분석하면
- Then `EXTENDS` 엣지의 `typeArguments`가 `["com.x.ArticleDao", "com.x.Article"]`이고, 인터페이스마다의 `IMPLEMENTS` 엣지에 각각 `["com.x.Article$Key", "java.util.List"]`, `["com.x.ArticleService"]`가 있으며 `Named`의 엣지에는 키가 없다. 타입 변수는 `T`·`X`·`V` 그대로, 배열은 `T[]`·`int[]`, 다이아몬드는 컴파일러가 추론한 인자다.

### F-002-AC20 (대상: F-002-R15)
- Given classpath에 없는 상위 타입 `BaseService<UserMapper, User, java.lang.Long>`(`User`만 단일 import, `UserMapper`는 와일드카드 import), 프로젝트 인터페이스 `Local<User, Unknown>`, 컴파일되지 않는 `implements Local<? extends Number, String>`
- When jar 없이 분석하면
- Then `typeArguments`가 각각 `["UserMapper", "org.ext.model.User", "java.lang.Long"]`, `["org.ext.model.User", "Unknown"]`, `["?", "java.lang.String"]`이다.

### F-002-AC21 (대상: F-002-R16)
- Given Java 파일이 둘인 프로젝트와 `progress` 호출을 기록하는 컨텍스트
- When 분석하면
- Then 첫 호출이 `Java 소스 2개 일괄 파싱`이고, 두 파일의 상대 경로가 각각 알려지며, 마지막 호출은 파일 경로가 아니라 파일을 다 본 뒤의 단계 이름이다.

### F-002-AC22 (대상: F-002-R17)
- Given 바로 아래에 스텁 jar와 풀린 클래스가 있고 한 단계 아래 폴더에 다른 jar가 있는 폴더(프로젝트 밖), 와일드카드 import만 쓰는 소스
- When 그 폴더를, 그리고 `<폴더>/*`를 classpath로 주고 분석하면
- Then 폴더를 주면 jar의 타입과 풀린 클래스가 모두 해석되고 한 단계 아래 jar의 타입은 미해결이다. `<폴더>/*`는 jar의 타입만 해석되고(대문자 `.JAR` 포함), 없는 폴더의 `*`는 `FILE_NOT_FOUND`다.

### F-002-AC23 (대상: F-002-R18)
- Given classpath의 클래스가 classpath에 없는 타입을 참조하고, 아직 컴파일되지 않은 프로젝트 타입 B의 메서드 시그니처에 그 없는 타입이 있으며, 먼저 분석되는 파일 A가 미해석 타입의 인자로 B의 오버로드 메서드를 부르는 프로젝트(jeesite-v1의 `JaxbMapper` → `Exceptions`와 같은 꼴). 표준 오류를 버퍼로 바꿔 둔 상태
- When 분석하면
- Then 버퍼에 `Status ERROR`·`org.eclipse.jdt`가 없고 그 뒤에 쓴 다른 출력은 있다. `WARN` `PARSE_FAILED` 1건이 파일 A에 있고 메시지에 없는 타입 이름과 classpath 안내가 있다. 분석 뒤 `System.err`는 분석 전의 스트림이다. JDT 로그가 없는 분석에는 진단이 없다.

### F-002-AC24 (대상: F-002-R19)
- Given AC23의 프로젝트(파일 B가 일괄 파싱에서 `ArrayIndexOutOfBoundsException`으로 떨어진다)
- When 분석하면
- Then `ERROR` 진단이 없고, B의 타입·메서드 노드와 B 안의 호출이 그래프에 있으며 A의 결과도 그대로다.

### F-002-AC25 (대상: F-002-R20)
- Given `import org.activiti.bpmn.model.Process;`(jar 없음)로 `Process` 필드·매개변수(배열 포함)를 선언한 클래스와 그 메서드를 부르는 다른 파일. `java.util.Date`를 import하고 `java.sql.Date`·`java.lang.Process`를 전체 이름으로, 프로젝트 타입 `com.b.Process`를 import로 쓰는 클래스
- When 분석하면
- Then `ASSOCIATES`의 대상과 메서드 ID의 매개변수는 `org.activiti.bpmn.model.Process`(external)이고 다른 파일의 호출도 같은 ID로 간다. `java.lang.Process`는 전체 이름으로 쓴 클래스에만 나오고 그 클래스의 메서드 ID는 `f(java.sql.Date,java.util.Date,java.lang.Process,com.b.Process)`다.

### F-002-AC26 (대상: F-002-R20)
- Given 같은 패키지에 프로젝트 타입 `StringUtils`가 있는데 `import org.apache.commons.lang3.StringUtils;`(jar 없음)를 한 파일과 import 없이 쓰는 파일, 같은 패키지의 `Process.java`를 import 없이 쓰는 파일과 `org.activiti…Process`를 import한 파일
- When 분석하면
- Then import한 파일의 `ASSOCIATES`·메서드 ID·static 호출은 `org.apache.commons.lang3.StringUtils`로 가고 프로젝트 `StringUtils`로 가는 엣지가 없다. import 없는 파일은 같은 패키지의 프로젝트 타입(`java.lang.Process`가 아니다)이고, import한 파일은 import한 타입이다.

## 5. 가정
- Lombok 합성(F-002-R14)의 세부
  - 클래스 수준 `@Getter`/`@Setter`/`@With`/`@Data`/`@Value`는 static 필드와 `$`로 시작하는 필드를 빼고, 필드 수준 애너테이션이 클래스 수준을 이긴다. 접근 수준은 `modifiers`에 담고 `AccessLevel.NONE`이면 만들지 않는다.
  - 접근자 이름은 Lombok의 기본 규칙(접두사 + 첫 글자 대문자)이다. 이미 있는 메서드는 이름(대소문자 무시)과 매개변수 수로 판단한다. boolean getter는 `is`·`get` 어느 쪽이든 있으면 만들지 않는다.
  - `@AllArgsConstructor`의 매개변수는 초기화된 final 필드를 뺀 인스턴스 필드 전부, `@RequiredArgsConstructor`는 초기화식 없는 final·`@NonNull` 필드다. `@Value`와 `@FieldDefaults(makeFinal=true)`는 필드를 final로 본다(`@NonFinal` 제외). `@Data`·`@Value`·클래스의 `@Builder`는 명시적 생성자도 생성자 애너테이션도 없을 때만 생성자를 만든다(`@Data`+`@Builder`면 모든 필드 생성자 하나).
  - `@Builder`가 static 메서드에 붙으면 빌더 타입 이름은 `<반환 타입>Builder`이고 `build()`는 그 반환 타입을 돌려준다. `builderMethodName`, `buildMethodName`, `builderClassName`, `setterPrefix`를 따른다. 빌더 클래스를 소스에 직접 선언해 두었으면 그 타입에 없는 메서드만 더한다.
  - 값 없는 `@Singular`의 단수 이름은 영어의 단순한 복수형(`-s`, `-es`, `-ies`)만 푼다. 풀지 못하면 단수 메서드를 만들지 않는다.
  - `@ToString`·`@EqualsAndHashCode`·`@With`·빌더 메서드·합성 생성자에는 `ACCESSES`를 만들지 않는다. `log` 필드로는 `ASSOCIATES`를 만들지 않는다.
  - 지원하지 않는 것: `@Delegate`, `@UtilityClass`, `@Accessors(prefix=..)`, `lombok.config`, `@Getter(lazy=true)`의 내부 구현, `@SuperBuilder`의 `<Type>BuilderImpl`과 `protected` 생성자, `@Data`의 `canEqual`, `@Jacksonized`, `@StandardException`, `@Cleanup`·`@SneakyThrows`·`@Synchronized`(멤버를 만들지 않는다).
- 상위 타입의 타입 인자(F-002-R15)의 세부
  - 값은 소스에 쓴 타입 인자를 AST에서 읽은 것이다. 해석된 타입은 erasure의 바이너리 이름(`List<Article>` → `java.util.List`, 중첩 타입은 `$`)이고, 타입 변수는 선언한 이름 그대로다(하위 타입 자신의 타입 매개변수 이름이다. `EXTENDS` 사슬을 따라 대입하는 것은 소비자가 한다).
  - 최상위 와일드카드는 Java 문법에 없지만(컴파일 오류) 쓴 그대로 `?`로 남긴다. 경계(`? extends X`)는 남기지 않는다.
  - 이름을 복원하지 못해 미해결(`unresolved:java-type:`)로 남긴 상위 타입의 엣지와, Lombok `@SuperBuilder` 빌더 타입 사이의 `EXTENDS`, enum 상수 본문의 익명 타입에서 그 enum으로 가는 `EXTENDS`에는 넣지 않는다.
  - `Outer<A>.Inner<B>`처럼 바깥 타입에도 타입 인자가 있으면 가장 바깥에 쓴 것(`Inner`의 `B`)만 남긴다.
- 진행 상황(F-002-R16): 파일을 다 본 뒤의 단계(미해석 호출 해석, 빈·배치 연결)는 `Java 미해석 호출·빈·배치 후처리`로 알린다. 문자열을 조립하는 것은 이 둘뿐이고 파일마다는 경로 그대로 넘긴다.
- JDK 판정(F-002-R13)은 분석기를 실행하는 JDK의 모듈을 기준으로 한다. 대상 프로젝트가 Java 8이어도 `javax.xml.bind`, `javax.annotation.Resource`는 라이브러리로 본다.
- 익명·로컬 타입의 번호는 JDT가 계산한 바이너리 이름을 소스에 나온 순서로 확정해 쓴다. javac와 순서가 다를 수 있는 드문 경우(람다 안의 익명 클래스 등)는 맞추지 않는다.
- 기본 패키지의 타입은 `JAVA_PACKAGE` 노드 없이 FILE→TYPE만 만든다. `package-info.java`는 패키지 노드만 만든다.
- 로컬·익명 타입은 감싼 타입이 `CONTAINS`하고 `attrs.enclosingMethod`에 감싼 메서드 ID를 남긴다.
- `Optional<T>`의 multiplicity는 `1`이다. 값을 감싸지 않는 제네릭(`ResponseEntity<T>`)의 타입 인자는 `DEPENDS_ON`이다.
- JDK 타입으로의 `ASSOCIATES`도 `DEPENDS_ON`과 같은 옵션(`java.includeJdkTypes`)을 따른다. 필드의 타입 원문은 `JAVA_FIELD` `attrs.type`에 항상 남는다. `EXTENDS`/`IMPLEMENTS`/`ANNOTATED_BY`는 명시적으로 쓴 JDK 타입이면 만든다(`java.lang.*` 애너테이션 제외).
- `DEPENDS_ON`에는 호출한 메서드·접근한 필드를 선언한 타입도 넣는다. 출발은 그 코드를 직접 감싼 타입(익명 타입 포함)이고 위치는 처음 쓰인 곳이다.
- 기본 생성자, record의 표준 생성자와 접근자, enum의 `values`/`valueOf`처럼 소스에 없지만 호출 대상이 되는 메서드는 `attrs.synthetic=true` 노드로 만든다.
- classpath에 없는 타입이 메서드 매개변수에 있으면 ID에는 프로젝트 전체의 단일 import에서 유일하게 정해지는 FQN을 쓰고, 정해지지 않으면 소스에 쓴 이름을 그대로 쓴다.
- 대상 소스는 Java 21 문법으로 본다(PRD 7).
- `JAVA_TYPE`의 `qualifiedName`은 바이너리 이름이다(링커가 매퍼 namespace와 맞출 때 쓴다). 메서드의 `qualifiedName`은 `<타입 바이너리 이름>#<이름>(<소스에 쓴 매개변수 타입>)`이다.
- 선언 노드의 위치는 이름에서 시작해 선언 끝까지이고(앞의 주석·애너테이션 제외), 끝 위치는 마지막 글자 다음(제외)이다.
- `modifiers`는 소스에 쓴 수식어만 담는다. 인터페이스의 `default`·`static` 메서드는 그 키워드가 들어 있어 추상 메서드와 구분된다.
- JDT가 예외로 끝나면 처리하지 못한 파일을 반으로 나눠 다시 파싱해 문제 파일 하나만 `PARSE_FAILED`(ERROR)로 남긴다.

- classpath 폴더의 jar(F-002-R17): 폴더 바로 아래의 `.jar`(대소문자 무관) 정규 파일만, 이름순으로 넣는다. 하위 폴더는 보지 않는다. `dir/*`는 자바의 classpath 와일드카드와 같이 jar만 뜻하고 그 폴더를 클래스 폴더로는 넣지 않는다. 폴더를 읽지 못하면 `WARN` `FILE_NOT_READABLE`이다.
- JDT 내부 로그(F-002-R18)의 세부
  - JDT에는 headless에서 로그를 받을 훅이 없다(`Util.log`가 `JavaCore.getPlugin()==null`이면 `System.err.println(status)`를 부른다). 그래서 Java 분석 동안만 `System.err`를 거르는 스트림으로 바꿔 `Status <수준>: org.eclipse.jdt… code=<n> …` 꼴의 줄만 받아 낸다(`JdtLog`). 받아 낼 곳은 스레드마다 따로 두고, 마지막 가로채기가 끝나면 원래 스트림으로 되돌린다(그 사이 다른 코드가 `System.err`를 바꿨으면 건드리지 않는다).
  - 원인(jeesite-v1에서 확인): 파일을 하나씩 받는 대로 추출하므로, 아직 컴파일되지 않은 프로젝트 타입의 메서드 목록을 물으면(`ITypeBinding.getDeclaredMethods()`: 오버로드 추측 판정) JDT가 그 타입의 시그니처를 "컴파일 중인 단위 없이" 해석한다. 시그니처에 classpath의 다른 jar가 참조하는 없는 타입이 있으면 `AbortCompilation`(`IsClassPathCorrect`)이 나고 DOM이 그것을 삼키며 로그를 찍고 빈 목록을 준다. 그 조회 하나만 빈 결과가 되고 분석은 이어진다(jeesite에서는 파일이 빠지지 않았다).
  - 진단의 위치는 로그가 난 시점에 추출 중이던 파일(그 조회를 한 쪽)이다. 일괄 파싱 중(파일 사이)에 났으면 위치가 없다. 코드는 `PARSE_FAILED`(WARN: 오류를 넘기고 일부만 분석함)를 쓴다.
- 묶음에서 떨어진 파일(F-002-R19): 위 조회의 여파로 컴파일러 상태가 깨지면 그 타입의 파일이 나중에 예외로 떨어질 수 있다(재현 프로젝트에서 `ArrayIndexOutOfBoundsException`). 남은 파일이 여럿이면 전부터 남은 것만 새 파서로 다시 돌렸고, 이번에 "남은 것이 하나"인 경우도 같게 했다. 바인딩 없이 파싱하는 단계는 두지 않았다(추출기가 바인딩에 의존한다).
- 단일 import의 우선(F-002-R20)의 세부
  - "풀지 못한 import"는 그 파일의 AST에서 본다(`ImportDeclaration.resolveBinding()`이 null이거나 복구된 바인딩). 바인딩의 최상위 타입이 `java.lang`·그 파일의 패키지·그 파일이 와일드카드 import한 패키지에 있고, 단순 이름이 풀지 못한 단일 import의 것과 같으며 FQN이 다를 때만 바꾼다. 중첩 타입은 최상위 타입의 이름으로 판단하고 `$`로 이어 붙인다.
  - 메서드 ID의 매개변수는 선언 쪽과 호출 쪽이 같은 이름을 얻어야 하므로 "메서드를 선언한 타입의 파일"의 import로 판단한다(`JavaProject.parameterName`). 그 파일의 AST가 없을 수 있어(호출 쪽이 먼저 분석됨) import가 풀렸는지는 추정한다: import 대상이 프로젝트 소스의 타입이거나 실행 중인 JDK의 타입이면 풀린 것으로 보고 바꾸지 않는다. 최상위 타입 이름이 파일 이름과 다른 타입의 메서드에는 적용하지 않는다.
  - 한계: 다른 파일에 선언한 메서드의 **반환 타입**이나 필드 타입이 이런 이름이면, 그 값을 받은 식의 타입은 이 파일의 import로만 판단한다(선언한 파일의 import를 보지 않는다). import가 jar에 있어 풀리는데 같은 파일에서 `java.lang.Process`를 전체 이름으로 매개변수에 쓴 경우의 메서드 ID는 위 추정 때문에 import의 FQN이 될 수 있다(프로젝트·JDK 타입 import는 예외).

## 6. 미확정 사항
- 없음 (사용자 위임에 따라 5의 가정으로 진행).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 ADR: [0003](../../decisions/0003-use-jdt-core-for-java.md)

## 8. 계약 추가 제안 (design 3.5에 반영 요청)
design.md에 반영됨(2026-10-11): 아래 항목은 모두 F-001 design.md 3.3·3.5(attrs), 3.7(진단 코드), 3.8(옵션)에 있다. 이번 감사 오류 수정 라운드에서 이 기능이 더한 제안은 없다.

- JAVA_TYPE: `anonymous`, `local`(boolean), `enclosingMethod`(메서드 ID)
- JAVA_METHOD: `synthetic`(boolean), `varargs`(boolean), `typeParameters`(List), `throws`(List)
- JAVA_FIELD: `enumConstant`(boolean), `constantValue`(컴파일 타임 상수 값, String)
- JAVA_TYPE·JAVA_METHOD·JAVA_FIELD: `lombok`(String: Lombok이 만들 멤버면 그 애너테이션의 단순 이름. `synthetic=true`와 함께. 클래스 다이어그램이 합성 멤버를 구분하는 데 쓴다) — design 3.3·3.5에 반영됨
- JAVA_TYPE: `synthetic`(boolean: Lombok 빌더 타입)
- 옵션(extra): `java.includeJdkTypes`
- 진단 코드: `SOURCE_ROOT_MISMATCH`

### 실전 검증 2차 후속에서 더한 것
design.md에 반영됨(2026-10-11, 2차): `typeArguments`는 3.5 엣지 attrs 표(EXTENDS, IMPLEMENTS)와 3.4 "소비자가 지키는 규칙"(팬아웃 좁히기)에, 처리 중인 것 알리기(F-002-R16)는 4.1에 넣었다.
- design 3.5 엣지 attrs `EXTENDS`·`IMPLEMENTS`: `typeArguments`(List<String>: 상위 타입에 넘긴 타입 인자. 선언 순서, erasure의 바이너리 이름. 타입 변수는 이름, 와일드카드는 `?`, 배열은 `[]` 접미사, classpath에 없는 타입은 import로 복원한 FQN 또는 쓴 이름. 타입 인자가 없으면 키가 없다. 만드는 모듈 java, F-002-R15)
- 소비자(export)가 알아야 할 것: 제네릭 상위 서비스의 메서드 아래에서 "이 하위 타입이 고정한 DAO·엔티티"를 고를 때 이 값을 쓴다. 상위 타입의 타입 매개변수 이름은 그 `JAVA_TYPE`의 `attrs.typeParameters`(`"T"`, `"X extends Named"` 꼴)에 선언 순서로 있고, `typeArguments`와 같은 위치끼리 대응한다. 값이 타입 변수 이름이면 그 하위 타입의 `typeParameters`에 있는 이름이므로 한 단계 더 아래의 엣지에서 대입한다
- 새 노드·엣지 종류, ID 규칙, 진단 코드, 옵션은 없다

### 실전 재검증 2차 후속(2026-10-11)에서 더한 것 — design.md 반영 요청
design.md 반영됨(2026-10-11): `PARSE_FAILED`의 WARN 경우와 `FILE_NOT_FOUND`는 3.7, `--classpath`의 폴더·`<폴더>/*`는 4.5, 단일 import의 FQN은 3.3("매개변수 타입이 classpath에 없는 메서드 선언" 행)과 7절("jar 없는 import 타입" 행을 해소로 표시)에 넣었다.
- design 3.7 `PARSE_FAILED`: WARN에 "JDT가 타입 정보를 읽다가 낸 내부 오류를 넘기고 계속 분석함(위치는 그때 추출 중이던 파일. java, F-002-R18)"을 더한다. 표준 오류에는 JDT 원문이 나오지 않는다.
- design 3.7 `FILE_NOT_FOUND`: `--classpath <폴더>/*`의 폴더가 없을 때도 남긴다. 4.5(CLI)의 `--classpath` 설명에 "폴더를 주면 그 바로 아래의 jar도 쓴다. `dir/*`도 된다"를 더한다(F-002-R17). README의 사용법도 같다(cli·문서 담당).
- design 3.3 "매개변수 타입이 classpath에 없는 메서드 선언" 행 옆에: 단일 import한 이름은 컴파일러가 `java.lang`·같은 패키지의 같은 이름으로 풀었어도 import의 FQN이 매개변수 자리에 들어간다(F-002-R20). 그래프의 `java:type:java.lang.<이름>`·같은 패키지 타입으로 가던 잘못된 참조가 import한 external 타입으로 바뀐다.
