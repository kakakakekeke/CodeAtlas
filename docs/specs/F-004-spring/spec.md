---
type: spec
id: F-004
title: Spring 해석 (애너테이션)
status: implemented     # draft | approved | implemented
updated: 2026-10-11
---

# F-004 Spring 해석 (애너테이션) + Java 코드 안의 MyBatis

## 1. 배경
콜트리가 화면 → Controller → Service → Mapper → SQL로 이어지려면(PRD 1) Java 호출만으로는 부족하다. 요청 URL과 핸들러, 주입으로 이어진 빈, 뷰 이름, 문자열로 지정한 MyBatis 구문을 알아야 한다. 이 기능은 애너테이션 기반 Spring 설정과 Java 코드 안의 MyBatis 사용을 해석한다. Spring·MyBatis jar가 classpath에 없어도 import와 단순 이름으로 인식한다.

## 2. 범위
- 포함: 스테레오타입과 `SPRING_BEAN`·`DEFINES_BEAN`, `@Bean`, `INJECTS`, `HTTP_ENDPOINT`·`ROUTES_TO`, 뷰 이름(`viewNames`), Java 코드의 redirect·forward 경로(`redirects`, `forwards`), 컨트롤러의 `@ModelAttribute` 메서드로의 추론 호출, 서블릿 필터·핸들러 인터셉터 표시(`stereotype`=`filter`·`interceptor`), 뷰 리졸버 Java 설정, `SqlSession` 계열 호출의 `EXECUTES`, `@Select` 등 애너테이션 SQL의 구문 노드와 내장 소스.
- 제외: XML 빈 설정(F-017), 매퍼 XML(F-009), 매퍼 메서드↔구문·뷰 이름↔JSP·요청↔엔드포인트 연결(F-012), SQL 본문 파싱(F-006), `@ComponentScan` 범위 판정, 프로파일·조건부 빈, `@SelectProvider`, 인터페이스에 선언한 매핑의 상속, 요청 경로의 프로퍼티 자리표시자 치환(이름만 남긴다. 치환과 매칭은 F-012), `@ControllerAdvice`의 `@ModelAttribute`, web.xml의 필터 매핑(F-017·F-012).

## 3. 요구사항

| ID | 요구사항 | 출처 |
|---|---|---|
| F-004-R1 | 시스템은 스테레오타입 애너테이션이 붙은 타입에 `attrs.stereotype`을 남기고 `SPRING_BEAN` 노드(명시한 이름 또는 단순 이름의 첫 글자를 소문자로)와 `DEFINES_BEAN`(TYPE→BEAN)을 만들어야 한다. | 사용자 요청, design 3.4, 3.5 |
| F-004-R2 | 시스템은 `@Bean` 메서드마다 `SPRING_BEAN`(name/value 또는 메서드명)과 `DEFINES_BEAN`(METHOD→BEAN)을 만들고 `attrs.beanType`(반환 타입)과 `attrs.class`(만든 객체의 클래스)를 남겨야 한다. | 사용자 요청 |
| F-004-R3 | 시스템은 `@Autowired`/`@Inject`/`@Resource` 필드·생성자·setter, 스테레오타입 클래스의 단일 생성자, Lombok이 만드는 단일 생성자(`@RequiredArgsConstructor`의 final 필드 등, F-002-R14의 합성 생성자와 같은 매개변수)에 대해 `INJECTS`(주입받는 타입→선언된 대상 타입; `via`, `field`, `qualifier`)를 만들어야 한다. | 사용자 요청, design 3.4 |
| F-004-R4 | 주입 대상이 프로젝트의 인터페이스·추상 타입이고 구현 빈이 있으면, 시스템은 `attrs.candidates`에 후보 타입 ID를 남겨야 한다. qualifier나 필드명이 빈 이름과 맞으면 그 하나만 남긴다. | 사용자 요청 |
| F-004-R5 | 시스템은 `@RequestMapping`(클래스와 메서드의 경로·HTTP 메서드 조합)과 `@GetMapping` 등에서 경로×메서드마다 `HTTP_ENDPOINT`와 `ROUTES_TO`(ENDPOINT→METHOD)를 만들어야 한다. 경로가 상수 식이면 값을 계산한다. | 사용자 요청, design 3.3 |
| F-004-R6 | 시스템은 핸들러 메서드가 돌려주는 뷰 이름(문자열 상수 반환, `new ModelAndView("x")`, `setViewName("x")`)을 `JAVA_METHOD` `attrs.viewNames`에 남겨야 한다. 정적으로 알 수 없는 부분은 `{*}`로 넣고, 전부 알 수 없으면 넣지 않고 미해결 엣지(`RENDERS`, 분류 `view`)를 남긴다. `@RestController`·`@ResponseBody` 메서드는 뺀다. | 사용자 요청, F-012 링커 전제 |
| F-004-R7 | 시스템은 `@Bean` 메서드가 만든 객체에 상수로 준 setter 값을 `SPRING_BEAN` `attrs.property.<이름>`으로 남겨야 한다(뷰 리졸버의 `prefix`, `suffix` 포함). | 사용자 요청 |
| F-004-R8 | 시스템은 `@Mapper` 인터페이스에 `stereotype=mapper`를 남겨야 한다. 이름 관례만으로는 매퍼로 보지 않는다. | 사용자 요청 |
| F-004-R9 | `SqlSession` 계열 객체의 `selectOne`·`selectList`·`selectMap`·`selectCursor`·`select`·`insert`·`update`·`delete` 호출의 첫 인자가 컴파일 타임 상수이면, 시스템은 `EXECUTES`(METHOD→`mybatis:stmt:<namespace>.<id>`)를 만들어야 한다. `.`이 없는 ID는 그대로 쓰고 `attrs.shortId=true`를 붙인다. | 사용자 요청, design 3.4 |
| F-004-R10 | 만약 그 첫 인자가 상수가 아니면, 시스템은 미해결 엣지(`EXECUTES`, 분류 `mybatis-statement`, 원문은 식 텍스트)를 남겨야 한다. | 사용자 요청, AGENTS.md |
| F-004-R11 | 시스템은 `@Select`/`@Insert`/`@Update`/`@Delete`의 SQL로 `SQL_STATEMENT` 노드(`mybatis:stmt:<인터페이스 바이너리 이름>.<메서드명>`; `namespace`, `statementId`, `annotation=true`)를 만들고 SQL을 `sql` 내장 소스로 등록해야 한다. `#{..}`는 `?`, `${..}`는 `__DYNAMIC__`로 바꾸고 `<script>` 본문은 태그를 걷어내며 `dynamic=true`를 붙인다. | 사용자 요청, design 4.1 |
| F-004-R12 | Spring·MyBatis 타입이 classpath에 없는 동안에도, 시스템은 import와 단순 이름으로 위 애너테이션과 타입을 인식해야 한다. | 사용자 요청 |
| F-004-R13 | 시스템은 Java 코드의 `response.sendRedirect(<식>)`(`HttpServletResponse`. jar가 없어도 선언 타입의 단순 이름으로 인식)와 `getRequestDispatcher(<식>).forward(..)`/`.include(..)`의 경로를, 호출한 메서드의 `JAVA_METHOD` `attrs.redirects` / `attrs.forwards`(List, 경로 패턴)에 남겨야 한다. 문자열 상수는 값으로, 알 수 없는 부분은 `{*}`로 두고 맨 앞의 컨텍스트 경로(`request.getContextPath()`)와 쿼리 문자열은 뗀다. 경로를 전혀 알 수 없으면 attrs에 넣지 않고 미해결 엣지(`CALLS`, 분류 `http-endpoint`, `attrs.callType`은 `redirect` 또는 `forward`)를 남긴다. 엔드포인트 핸들러로 잇는 일은 링커(F-012)가 뷰 이름의 `redirect:`와 같은 방식으로 한다. | 독립 감사(U6), AGENTS.md |
| F-004-R14 | 핸들러의 뷰 이름 반환식이 프로젝트 메서드의 호출이면(`return form(article, model);`), 시스템은 그 식을 "동적 뷰 이름" 미해결로 남기지 않고 호출된 메서드가 돌려주는 뷰 이름을 그 핸들러의 `viewNames`에 합쳐야 한다. 호출된 메서드가 핸들러면 그 `viewNames`를, 뷰 이름을 문자열로 조립하는 헬퍼면 그 `return` 식을 F-004-R6과 같은 규칙으로 평가한 값을(헬퍼의 매개변수가 섞인 부분은 `{*}`) 쓰고, 전이적으로 따라가며 순환은 끊는다. 호출된 메서드의 값을 정하지 못하면(값을 그대로 돌려주는 헬퍼, 본문을 볼 수 없는 인터페이스·추상 메서드) 지금처럼 미해결 엣지(`RENDERS`, 분류 `view`)를 남긴다. | 공개 프로젝트 검증(B20), AGENTS.md |
| F-004-R15 | 시스템은 요청 핸들러 메서드가 있는 클래스마다, 그 클래스와 프로젝트 상위 클래스에 선언한 `@ModelAttribute` **메서드**(매개변수의 `@ModelAttribute`가 아니라 메서드 수준)로 각 핸들러에서 `CALLS`(`INFERRED`, `attrs.callType=modelAttribute`, 위치는 그 `@ModelAttribute` 메서드의 선언)를 만들어야 한다. Spring이 매 요청마다 핸들러 호출 전에 부르는 메서드다. `@InitBinder` 메서드로는 만들지 않는다. | 공개 프로젝트 검증(B17) |
| F-004-R16 | 요청 경로에 프로퍼티 자리표시자(`${adminPath}/sys/user`)가 있으면, 시스템은 경로를 그대로(`/${adminPath}/sys/user`) 두고 `HTTP_ENDPOINT` 노드의 `attrs.placeholders`(List: 경로에 든 자리표시자 이름, 나온 순서, 중복 없이)를 남겨야 한다. 경로 정규화(맨 앞 `/`, 겹친 `/` 줄이기)는 `${…}`를 깨뜨리지 않아야 한다. 값으로 바꾸고 요청과 맞추는 일은 링커(F-012)가 한다. | 공개 프로젝트 검증(B5a) |
| F-004-R17 | 시스템은 F-004-R13의 redirect·forward 인식을 서블릿 필터(`doFilter`, `doFilterInternal`), 핸들러 인터셉터, Spring Security 인증 핸들러의 코드에서도 같게 적용해야 하고, 다음 형태도 redirect로 봐야 한다: 형 변환한 응답(`((HttpServletResponse) res).sendRedirect(..)`), `response.encodeRedirectURL(<식>)`으로 감싼 경로, Spring Security의 `RedirectStrategy.sendRedirect(request, response, <식>)`(`getRedirectStrategy()` 호출 결과 포함), Shiro의 `WebUtils.toHttp(response).sendRedirect(<식>)`와 `WebUtils.issueRedirect(request, response, <식>, ..)`. | 공개 프로젝트 검증(B18) |
| F-004-R18 | 시스템은 서블릿 필터 클래스(`javax.servlet.Filter`/`jakarta.servlet.Filter` 구현, `OncePerRequestFilter` 등 필터 기반 클래스 상속. 프로젝트 상위 클래스를 거친 것 포함)에 `JAVA_TYPE` `attrs.stereotype=filter`를, Spring MVC 핸들러 인터셉터(`HandlerInterceptor` 구현, `HandlerInterceptorAdapter` 상속 등)에 `attrs.stereotype=interceptor`를 남겨야 한다. 소비자가 요청 처리의 진입점으로 쓴다. | 공개 프로젝트 검증(B18) |
| F-004-R19 | 시스템은 메서드 attrs `viewNames`·`redirects`·`forwards`와 같은 순서·같은 길이의 병렬 목록 `viewNameLines`·`redirectLines`·`forwardLines`(List: `줄:열`, 둘 다 1부터)를 함께 남겨야 한다. 뷰 이름의 자리는 반환문·`ModelAndView` 생성식·`setViewName` 호출이고, 다른 메서드에서 합쳐 온 뷰 이름(F-004-R14)은 **이 메서드 안의 그 호출 지점**이다. redirect·forward의 자리는 `sendRedirect`·`forward`·`include`(·`issueRedirect`) 호출이다. 같은 값이 여러 번 나오면 처음 나온 자리다. | 실전 재검증 2차 후속(2026-10-11)(N7) |
| F-004-R20 | `SqlSession` 계열 호출의 구문 ID가 상수가 아닐 때, (1) 한 번만 대입된 지역 변수면 시스템은 그 값을 따라가야 하고, (2) "리터럴 접두 + 동적"이고 접두에 namespace(`.`)까지 들어 있으면 `unresolved:mybatis-statement:<접두>{*}`로 가는 미해결 `EXECUTES`에 `attrs.statementPrefix`(접두)와 `attrs.operation`을 남겨야 한다(링커가 그 접두로 시작하는 구문을 찾는다, F-012-R31). | 실전 재검증 2차 후속(2026-10-11)(N28-a, b) |
| F-004-R21 | 뷰 이름이 "리터럴 + 지역 변수"이고 그 변수에 대입된 값이 모두 문자열 상수면, 시스템은 값의 조합마다 뷰 이름을 `viewNames`에 넣고, 조합이 둘 이상이면 그 이름들을 메서드 attrs `viewNameCandidates`(List)에도 남겨야 한다. 값을 모르는 대입이 섞이면 전처럼 `{*}`다. | 실전 재검증 2차 후속(2026-10-11)(N28-d) |
| F-004-R22 | 이름이 같은 스테레오타입 빈 정의가 여럿이면(다른 모듈의 같은 이름 클래스, 테스트 소스), 시스템은 `SPRING_BEAN` 노드의 attrs와 위치를 main 쪽(`src/test/`가 아닌 것)·파일 경로순 첫 정의의 것으로 일관되게 하고 나머지 정의를 `attrs.otherDefinitions`(List: `<파일>:<줄> <class>`)에 남겨야 한다. `DEFINES_BEAN`은 정의마다 하나다. | 실전 재검증 2차 후속(2026-10-11)(N23, zheng) |
| F-004-R23 | 뷰 이름·redirect 경로의 문자열 연결에 `@Value("${키}")`가 붙은 필드(프로젝트 상위 클래스의 것 포함)가 있으면, 시스템은 그 자리를 `{*}`가 아니라 자리표시자 원문(`${키}`, `${키:기본값}`)으로 남겨야 한다(예: `redirect:${adminPath}`). 값으로 푸는 일은 링커가 한다(F-012-R33). | 실전 재검증 2차 후속(2026-10-11)(N27) |
| F-004-R24 | 시스템은 라이브러리 메서드 호출(컴파일러가 찾은 선언이 프로젝트 소스의 것도 JDK의 것도 아니거나, 선언을 찾지 못했고 수신자를 적은 호출)에 인자로 넘긴 "구문 ID처럼 보이는 문자열 상수"(식별자 조각이 `.`으로 둘 이상 이어지고 공백이 없다)를 호출한 메서드의 `JAVA_METHOD` `attrs.statementIdLiterals`(List, 나온 순서·중복 없이)와 병렬 목록 `attrs.statementIdLiteralLines`(List: `줄:열`)에 남겨야 한다. `SqlSession` 계열 호출의 구문 ID(F-004-R9, R20), 단언(`assert*`), 구문을 찾아만 보는 호출(`getMappedStatement`, `hasStatement`)의 인자는 넣지 않는다. 엣지는 만들지 않는다: 그 ID의 매퍼 구문이 실제로 있는지는 링커가 본다(F-012-R43). | 실전 재검증 3차 후속(2026-10-11)(M13-a: enterprise `excelZipService.uploadExcel("ZipManageDAO.insertExcelZip", …)`) |
| F-004-R25 | 핸들러의 반환식이 뷰 이름을 만드는 도우미 메서드의 호출이고(F-004-R14) 그 도우미의 반환식에 매개변수가 섞여 있으면(`return <접두> + 인자 + <접미>`, `.concat(..)` 사슬, 인자를 그대로 반환), 시스템은 호출 지점의 인자를 그 자리에 대입해 뷰 이름을 만들어야 한다. 인자가 문자열 상수면 그 값, 일부만 상수면 나머지는 `{*}`, 상수 조각이 없으면 `{*}`이고, 도우미 안의 알 수 없는 조각도 `{*}`다. 대입한 결과에 상수 조각이 하나도 없으면(인자를 그대로 돌려주는 도우미에 변수를 넘김) 전처럼 미해결이다. 컴파일러가 그 호출을 믿을 수 없는 선언으로 풀었으면(자기 자신의 호출, classpath에 없는 매개변수 타입 자리에 문자열 리터럴) 같은 이름·인자 수의 프로젝트 선언 가운데 인자를 받을 수 있는 하나를 도우미로 본다. | 실전 재검증 3차 후속(2026-10-11)(M13-b: zheng `return thymeleaf("/blog/index");` 23건) |
| F-004-R26 | 뷰 이름의 문자열 연결(도우미의 반환식과 그 안의 한 번 대입된 지역 변수 포함)에 "프로퍼티를 읽는 꼴의 호출"이 있으면, 시스템은 그 자리를 `{*}`가 아니라 `${키}`로 남겨야 한다. 그런 호출은 수신자를 적었고(`X.get("키")`, `X.getInstance().get("키")`), 이름이 `get`·`getProperty`·`getString`·`getValue`·`getConfig` 가운데 하나이고, 인자가 키 꼴(식별자 조각이 `.`·`-`로 이어진 것)의 문자열 상수 하나인 호출이다. 선언을 찾았으면 `String`을 돌려주는 메서드여야 하고, `java.util`의 컬렉션(`Properties`·`ResourceBundle` 제외)과 서블릿 API의 메서드는 아니다. 그 키가 정말 프로퍼티인지는 링커가 프로젝트 properties에서 확인한다(F-012-R33, R47). | 실전 재검증 4차 후속(2026-10-11)(zheng `BaseController.thymeleaf`: `PropertiesFileUtil.getInstance().get("app.name")`. 모듈 디렉터리 이름과 템플릿 디렉터리 이름이 다른 `zheng-demo-web` ↔ `zheng-ui/zheng-demo`는 프로퍼티 값으로만 정해진다) |

## 4. 수용 기준

### F-004-AC1 (대상: F-004-R1, R12)
- Given `@Service`, `@Repository("orderDao")`, `@Controller`, `@RestController`, `@Component`, `@Configuration`, `@ControllerAdvice`가 붙은 클래스 (jar 없음, 단일 import와 와일드카드 import 혼용)와 같은 이름의 프로젝트 자체 애너테이션 `@Service`를 쓴 클래스
- When 분석하면
- Then Spring 애너테이션이 붙은 타입에만 `stereotype`과 빈 노드·`DEFINES_BEAN`이 있고 빈 이름이 `orderServiceImpl`, `orderDao` 등이다.

### F-004-AC2 (대상: F-004-R1, R12)
- Given 같은 소스와 Spring 스텁이 든 classpath
- When 분석하면
- Then AC1과 같은 빈과 stereotype이 나온다.

### F-004-AC3 (대상: F-004-R2)
- Given `@Bean`, `@Bean(name = "defaultPage")`, `@Bean({"a", "b"})` 메서드
- When 분석하면
- Then 빈 이름이 메서드명, `defaultPage`, `a`·`b`이고 `DEFINES_BEAN`(METHOD→BEAN)이 있으며 `beanType`이 빈 노드, 엣지, 메서드 노드에 있다.

### F-004-AC4 (대상: F-004-R3)
- Given `@Autowired` 필드, `@Autowired` 생성자, `@Autowired` setter + `@Qualifier`, `@Resource(name=)` 필드, 스테레오타입 클래스의 단일 생성자, `@RequiredArgsConstructor` + final 필드
- When 분석하면
- Then 주입마다 `INJECTS`가 `via`(`field`/`constructor`/`setter`), `field`, `qualifier`와 함께 있고, 스테레오타입이 아닌 클래스의 생성자와 final이 아닌 필드로는 없다.

### F-004-AC5 (대상: F-004-R4)
- Given 인터페이스 `DiscountPolicy`의 구현 빈 둘(`defaultDiscountPolicy`, `vipDiscountPolicy`)과 구현 빈이 하나인 인터페이스
- When qualifier 없이, `@Qualifier("vipDiscountPolicy")`로, 필드명 `defaultDiscountPolicy`로 주입하면
- Then `candidates`가 각각 둘 전부, vip 하나, default 하나이고, 구현이 하나인 인터페이스는 그 하나다.

### F-004-AC6 (대상: F-004-R5)
- Given 클래스 `@RequestMapping("/order")` + 메서드 `@GetMapping("/list")`, `@RequestMapping(value = {"/a", "/b"}, method = {GET, POST})`, 상수 식 경로, 클래스 매핑 없는 컨트롤러의 `@PostMapping("save")`, 메서드 지정 없는 `@RequestMapping("/x.do")`
- When 분석하면
- Then `http:endpoint:GET /order/list`, `GET /order/a`, `POST /order/a`, `GET /order/b`, `POST /order/b`, 상수 값으로 계산한 경로, `POST /save`, `ANY /order/x.do` 노드가 `httpMethod`, `path`와 함께 있고 각각 핸들러로 `ROUTES_TO`가 있다.

### F-004-AC7 (대상: F-004-R6)
- Given `return "order/list"`, `return "redirect:/order/list"`, 삼항식의 두 상수, `new ModelAndView("order/detail")`, `mav.setViewName("order/form")`, 문자열 조합 반환, `@ResponseBody` 메서드, `@RestController`의 String 반환
- When 분석하면
- Then `viewNames`가 각각 그 상수들이고, `"order/popup_" + type`은 `order/popup_{*}`이며, 매개변수를 그대로 반환하면 `viewNames` 없이 미해결 엣지(`RENDERS`, 분류 `view`)이고, `@ResponseBody`와 `@RestController` 메서드에는 `viewNames`가 없다.

### F-004-AC8 (대상: F-004-R7)
- Given `InternalResourceViewResolver`를 만들어 `setPrefix("/WEB-INF/views/")`, `setSuffix(".jsp")`를 부르고 `ViewResolver`로 반환하는 `@Bean` 메서드
- When 분석하면
- Then `spring:bean:viewResolver`에 `property.prefix`, `property.suffix`가 있고 `class`가 `org.springframework.web.servlet.view.InternalResourceViewResolver`다.

### F-004-AC9 (대상: F-004-R8)
- Given `@Mapper` 인터페이스와 이름만 `XxxMapper`인 인터페이스
- When 분석하면
- Then 앞의 것만 `stereotype=mapper`다.

### F-004-AC10 (대상: F-004-R9, R12)
- Given `SqlSessionTemplate` 필드로 `selectList("orderDao.selectOrders", p)`, `insert(NAMESPACE + ".insertOrder", o)`, `static final` 상수에 담은 ID, `.` 없는 ID, `SqlSessionDaoSupport`를 상속해 `getSqlSession().update(...)`를 부르는 메서드 (jar 없음)
- When 분석하면
- Then 각 메서드에서 `mybatis:stmt:orderDao.selectOrders` 등으로 `EXECUTES`가 있고 `.` 없는 ID에는 `shortId=true`와 `statementId`가 있다. jar가 있을 때도 같다.

### F-004-AC11 (대상: F-004-R10)
- Given `sqlSession.selectList(queryId, param)`처럼 매개변수로 받은 ID
- When 분석하면
- Then `unresolved:mybatis-statement:queryId`로 가는 `UNRESOLVED` `EXECUTES`와 진단이 있다.

### F-004-AC12 (대상: F-004-R11)
- Given `@Select("SELECT * FROM T_ORDER WHERE ID = #{id}")`, `@Update({"UPDATE ${table}", "SET A = #{a}"})`, `<script>`와 `<if>`·`<foreach>`가 든 `@Select`
- When 분석하면
- Then 구문 노드가 `namespace`, `statementId`, `annotation=true`와 함께 있고, 내장 소스 본문이 `... ID = ?`, `UPDATE __DYNAMIC__ SET A = ?`, 태그 없는 SQL이며 `<script>` 구문에 `dynamic=true`가 있다.

### F-004-AC13 (대상: F-004-R3)
- Given 스테레오타입 클래스 셋: `@AllArgsConstructor` + 초기화된 비final 필드·static 필드·초기화된 final 필드, `@NoArgsConstructor` + `@AllArgsConstructor`, `@RequiredArgsConstructor` + `@FieldDefaults(makeFinal = true)`
- When 분석하면
- Then 첫째는 합성 생성자의 매개변수와 같은 필드(초기화된 비final 필드 포함, static·초기화된 final 제외)로, 셋째는 final이 된 필드로 `INJECTS`(`via=constructor`)가 있고, 생성자가 둘인 둘째에는 없다.

### F-004-AC14 (대상: F-004-R13, R12)
- Given `response.sendRedirect("/main.do")`, `response.sendRedirect(request.getContextPath() + "/main.do")`, `String ctx = req.getContextPath(); res.sendRedirect(ctx + LOGIN + "?from=" + req.getRequestURI())`, `sendRedirect("/order/" + id + "/detail.do")`, 삼항식과 지역 변수로 준 경로, `sendRedirect(request.getContextPath())`, `request.getRequestDispatcher("/order/list.do").forward(..)`·`.include(..)`, `RequestDispatcher rd = context.getRequestDispatcher("/WEB-INF/views/main.jsp"); rd.forward(..)`, 서블릿 타입이 아닌 객체의 `sendRedirect`·`forward` (서블릿 jar가 없을 때와 있을 때)
- When 분석하면
- Then 메서드 노드의 `redirects`가 각각 `/main.do`, `/main.do`, `/login.do`, `/order/{*}/detail.do`, 분기·변수의 값 전부, `/`이고, `forwards`가 `/order/list.do`·`/common/header.do`, `/WEB-INF/views/main.jsp`이며, 서블릿 타입이 아닌 객체의 호출에는 attrs도 미해결 엣지도 없다. jar가 있으면 `HttpServletResponse`를 상위 타입으로 가진 타입의 `sendRedirect`도 같다. 샘플의 `LoginCheckInterceptor.java:21`은 `redirects=["/main.do"]`다.

### F-004-AC15 (대상: F-004-R13)
- Given `response.sendRedirect(url)`(매개변수), `request.getRequestDispatcher(request.getParameter("next")).forward(..)`, 매개변수로 받은 `RequestDispatcher`의 `include(..)`
- When 분석하면
- Then `redirects`·`forwards` 없이 `unresolved:http-endpoint:url` 등으로 가는 `UNRESOLVED` `CALLS`(`callType`은 `redirect`, `forward`, `forward`)와 진단이 호출마다 있다.

### F-004-AC16 (대상: F-004-R14)
- Given 같은 클래스의 핸들러를 부르는 `return form(article, model);`, 그 핸들러를 다시 부르는 핸들러(전이), 서로를 부르는 두 핸들러(순환), 매개변수로 뷰 이름을 조립하는 같은 클래스의 헬퍼(`return "modules/cms/" + name;`), 다른 파일의 상위 클래스에 있는 헬퍼, `null`만 돌려주는 헬퍼, 매개변수를 그대로 돌려주는 헬퍼, 인터페이스 메서드 호출, 자기 자신을 부르는 반환식, 매개변수를 그대로 반환하는 핸들러
- When 분석하면
- Then 앞의 둘은 호출된 핸들러의 뷰 이름이 `viewNames`에 합쳐지고 미해결이 없으며(그 호출의 Java `CALLS`는 있다), 순환은 뷰 이름도 미해결도 없이 끝나고, 헬퍼는 `modules/cms/{*}`·`error/500`로 평가되며, `null` 헬퍼는 뷰도 미해결도 없고, 나머지 넷은 지금처럼 `unresolved:view:<식>`으로 가는 미해결 `RENDERS`가 식마다 1건(진단도 1건)이다. 헬퍼 메서드 노드에는 `viewNames`가 없다.

### F-004-AC17 (대상: F-004-R15)
- Given 상위 클래스 `BaseController`의 `@ModelAttribute` 메서드 둘과 `@InitBinder` 메서드, 그것을 상속한 컨트롤러의 `@ModelAttribute` 메서드 `get(id)`·상위 것을 재정의한 `@ModelAttribute` 메서드·핸들러 셋(매개변수에 `@ModelAttribute`가 붙은 것, `@ModelAttribute`와 매핑이 함께 붙은 것 포함)·핸들러가 아닌 메서드, `@ModelAttribute` 메서드가 없는 컨트롤러
- When 분석하면
- Then 핸들러마다 `get`, 상위의 `common`, 재정의한 하위의 메서드로 `CALLS`가 `INFERRED`, `callType=modelAttribute`, 위치는 그 `@ModelAttribute` 메서드 선언(상위 것은 상위 클래스 파일)으로 있고, 재정의된 상위 메서드·`@InitBinder` 메서드·매핑이 함께 붙은 메서드로는 없으며, 핸들러가 아닌 메서드와 `@ModelAttribute` 메서드 자신에서는 나가지 않는다.

### F-004-AC18 (대상: F-004-R16)
- Given 클래스 `@RequestMapping("${adminPath}/sys/user")` + 메서드 `"list"`·`{"${sub}/form", ""}`·`"/{id}"`, `@RequestMapping("${a}${b}/x")`, 상수 `"${frontPath:/f}"`를 이어 붙인 경로, 자리표시자가 없는 `/plain/{id}`
- When 분석하면
- Then 엔드포인트가 `/${adminPath}/sys/user/list`, `/${adminPath}/sys/user/${sub}/form`, `/${adminPath}/sys/user`, `/${adminPath}/sys/user/{id}`, `/${a}${b}/x`, `/${frontPath:/f}/index`이고 `placeholders`가 각각 `[adminPath]`, `[adminPath, sub]`, `[adminPath]`, `[adminPath]`, `[a, b]`, `[frontPath]`이며, `/plain/{id}`에는 `placeholders`가 없다. `Ids.normalizePath("${adminPath}//sys/")`는 `/${adminPath}/sys/`다.

### F-004-AC19 (대상: F-004-R17)
- Given `Filter.doFilter`(형 변환한 지역 변수·형 변환한 식의 `sendRedirect`, `req.getRequestDispatcher("..").forward(..)`), `OncePerRequestFilter.doFilterInternal`(`encodeRedirectURL`로 감싼 경로, 상수로 준 forward 경로), `HandlerInterceptor.preHandle`, `SimpleUrlAuthenticationSuccessHandler`의 `getRedirectStrategy().sendRedirect(request, response, "..")`, `RedirectStrategy` 필드의 `sendRedirect(request, response, <동적>)`, Shiro 필터의 `WebUtils.toHttp(response).sendRedirect("..")`·`WebUtils.issueRedirect(request, response, "..?x=1")`·`issueRedirect(request, response, url, null, true)`, 이름만 같은 다른 타입의 `sendRedirect(a, b, "..")` (서블릿 jar가 없을 때와 있을 때)
- When 분석하면
- Then 그 메서드 노드의 `redirects`·`forwards`에 경로가(컨텍스트 경로와 쿼리 문자열 제외) 있고, 동적 경로는 `unresolved:http-endpoint:<식>`으로 가는 미해결 `CALLS`(`callType=redirect`)이며, 앞이 동적이고 상수가 쿼리 문자열 조각뿐인 경로도 미해결이고, 다른 타입의 `sendRedirect`에는 아무것도 없다.

### F-004-AC20 (대상: F-004-R18)
- Given `javax.servlet.Filter` 구현, `OncePerRequestFilter` 상속, `jakarta.servlet.Filter`를 구현한 프로젝트 추상 클래스와 그 하위 클래스, Shiro·Ehcache의 필터 기반 클래스 상속(`@Service`가 함께 붙은 것 포함), `HandlerInterceptor` 구현, `@Component` + `HandlerInterceptorAdapter` 상속, 프로젝트가 만든 `Filter` 인터페이스의 구현, `TypeFilter`·`java.io.FileFilter` 구현, `@Controller` + `HandlerInterceptor` (jar 없음)
- When 분석하면
- Then 필터들은 `stereotype=filter`, 인터셉터들은 `stereotype=interceptor`이고(`@Component`·`@Service`가 붙은 것은 빈도 그대로 만들며 빈 노드의 `stereotype`은 `component`·`service`다), 뒤의 것들에는 그 값이 없고 `@Controller`는 `controller` 그대로다. 빈이 아닌 필터에는 `beanName`이 없다.

### F-004-AC21 (대상: F-004-R19)
- Given 여러 `return`(지역 변수 경유, 삼항식, 같은 이름 반복), `new ModelAndView("..")`·`setViewName(..)`, 같은 클래스의 다른 핸들러를 부르는 반환식, `sendRedirect`(같은 경로 두 번)·`getRequestDispatcher(..).forward(..)`가 있는 컨트롤러
- When 분석하면
- Then `viewNameLines`는 `viewNames`와 같은 순서로 반환문의 `줄:열`(지역 변수 경유는 그 반환문, 같은 이름은 처음 자리), 생성식은 `new`의 자리, `setViewName`은 메서드 이름의 자리다. 합쳐 온 뷰 이름의 자리는 이 메서드 안의 그 호출(메서드 이름의 자리)이지 호출된 메서드의 `return`이 아니다. `redirectLines`·`forwardLines`는 `sendRedirect`·`forward` 이름의 자리이고, 값이 없는 메서드에는 병렬 목록도 없다.

### F-004-AC22 (대상: F-004-R20)
- Given `update("LoginUsr.updateLoginIncorrect" + map.get(..), map)`, 한 번 대입한 지역 변수에 담은 ID, 두 번 대입한 지역 변수, namespace가 없는 접두(`"select" + suffix`), 매개변수로 받은 ID
- When 분석하면
- Then 첫째는 `unresolved:mybatis-statement:LoginUsr.updateLoginIncorrect{*}`로 가는 미해결 `EXECUTES`(`statementPrefix`, `operation=update`), 둘째는 `mybatis:stmt:LoginUsr.selectOne`으로 가는 `EXECUTES`이고, 나머지는 전처럼 식 원문의 미해결이며 `statementPrefix`가 없다.

### F-004-AC23 (대상: F-004-R21)
- Given `String view = "leaveForm"; if (..) view = "leaveAudit"; return "modules/oa/" + view;`, 대입이 한 번뿐인 같은 꼴, 매개변수 값을 대입한 같은 꼴
- When 분석하면
- Then 첫째의 `viewNames`와 `viewNameCandidates`는 `modules/oa/leaveForm`, `modules/oa/leaveAudit`이고(`viewNameLines`는 둘 다 그 반환문) 미해결 `RENDERS`가 없다. 둘째는 `modules/oa/leaveList` 하나이고 `viewNameCandidates`가 없다. 셋째는 `modules/oa/{*}`다.

### F-004-AC24 (대상: F-004-R22)
- Given 두 모듈의 main과 한 모듈의 테스트 소스에 있는 `@Controller class ManageController`(경로순으로 뒤인 파일을 먼저 준다)와 이름이 겹치지 않는 컨트롤러
- When 분석하면
- Then `spring:bean:manageController`의 `class`·`beanType`·위치는 main 쪽·경로순 첫 정의의 것이고 `otherDefinitions`에 나머지 둘이 `<파일>:<줄> <class>`로(main이 먼저) 있다. `DEFINES_BEAN`은 타입 셋에서 하나씩 온다. 겹치지 않는 빈에는 `otherDefinitions`가 없다.

### F-004-AC25 (대상: F-004-R23)
- Given 상위 클래스의 `@Value("${adminPath}") protected String adminPath;`·`@Value("${frontPath:/f}")` 필드와, 하위 클래스(먼저 분석된다)의 `return "redirect:" + adminPath;`, `"redirect:" + this.adminPath + "/login?x=" + frontPath`, `@Value`가 없는 필드를 이어 붙인 반환식, `response.sendRedirect(adminPath + "/sys/user")`
- When 분석하면
- Then `viewNames`는 `redirect:${adminPath}`, `redirect:${adminPath}/login?x=${frontPath:/f}`, `redirect:{*}`이고 `redirects`는 `${adminPath}/sys/user`(`redirectLines`는 그 호출)다.

### F-004-AC26 (대상: F-004-R24)
- Given jar 없는 타입의 필드로 부른 `excelZipService.uploadExcel("ZipManageDAO.insertExcelZip", in, 2, 5000)`, 상수 필드를 넘긴 `uploadExcel(QUERY, in)`, `messages.getMessage("fail.common.msg", "no dot", "two words.here", "trailing.", ".leading", "a.b")`와, 넣지 않아야 할 호출들(`session.insert("ZipManageDAO.direct", ..)`, 프로젝트 메서드 `helper.message("Project.method")`·`local("Own.method")`, `System.getProperty("user.dir")`, `assertEquals("ZipManageDAO.asserted", ..)`, `configuration.getMappedStatement(..)`·`hasStatement(..)`)
- When 분석하면
- Then 그 메서드의 `statementIdLiterals`는 `[ZipManageDAO.insertExcelZip, ZipManageDAO.insertRdnmadrZip, fail.common.msg, a.b]`이고 `statementIdLiteralLines`는 같은 길이의 `줄:열`이다. 그런 인자가 없는 메서드에는 attr가 없고, `SqlSession` 호출은 전처럼 `EXECUTES`다.

### F-004-AC27 (대상: F-004-R25)
- Given 도우미 `jsp(path)`(`path.concat(".jsp")`), `thymeleaf(path)`(`"/".concat(folder).concat(path).concat(SUFFIX)`, `folder`는 값을 모르는 지역 변수), `page(module, name)`(`"modules/" + module + "/" + name`), `same(name)`(`return name`), `pick(name, flag)`(상수 반환과 `"views/" + name`)과, 리터럴 인자로 부르는 핸들러들, 변수를 넘기는 핸들러들(`page("cms", type)`, `same(v)`), 그리고 `thymeleaf(Model)` 핸들러의 `return thymeleaf("/index")`(`Model` jar 없음)
- When 분석하면
- Then 뷰 이름은 차례로 `/index.jsp`, `/{*}/blog/index.html`, `modules/cms/list`, `plain/view`, `fixed/view`와 `views/a`이고 뷰 미해결이 없으며 자리(`viewNameLines`)는 도우미를 부른 호출 지점이다. `page("cms", type)`은 `modules/cms/{*}`, `same(v)`는 전처럼 미해결(`same(v)`)이다. `thymeleaf(Model)`의 뷰 이름은 `/{*}/index.html`이다. (5차 수정: 테스트 픽스처의 `folder`는 `Props.get("app.name")`이라 F-004-R26에 따라 그 자리가 `${app.name}`이다: `/${app.name}/blog/index.html`.)

### F-004-AC28 (대상: F-004-R26)
- Given 도우미 `thymeleaf(path)`(`String folder = Props.getInstance().get("app.name"); return "/".concat(folder).concat(path).concat(".html")`)를 부르는 핸들러, 핸들러 안에서 직접 이어 붙인 `"/" + props.getConfig("skin-dir") + "/main"`·`"themes/" + jdk.getProperty("theme") + "/index"`(`java.util.Properties`)·`"redirect:" + props.get("adminPath") + "/login"`, 그리고 `Map.get("app.name")`, 다른 이름의 호출(`lookup`), 키 꼴이 아닌 인자(`"two words"`), `int`를 돌려주는 `getValue`, 수신자 없는 `get("app.name")`
- When 분석하면
- Then 뷰 이름은 차례로 `/${app.name}/login.html`, `/${skin-dir}/main`, `themes/${theme}/index`, `redirect:${adminPath}/login`이고, 나머지 다섯은 전처럼 `/{*}/main`이다.

## 5. 가정
- `@RestController`는 `stereotype=controller` + `attrs.restController=true`, `@ControllerAdvice`·`@RestControllerAdvice`·`@Named`는 `component`, `@SpringBootApplication`은 `configuration`이다. 프로젝트가 만든 합성 애너테이션은 메타 애너테이션을 세 단계까지 따라간다.
- 기본 빈 이름은 `java.beans.Introspector.decapitalize` 규칙이다(앞 두 글자가 대문자면 그대로).
- `@Bean`의 `class`는 반환 식이 `new X(..)`이거나 `new X(..)`로 초기화한 지역 변수면 `X`, 아니면 반환 타입이다. 값은 FQN(바이너리 이름)이다.
- 암묵적 생성자 주입과 Lombok 생성자 주입은 스테레오타입 클래스에만 적용한다. 원시 타입·`java.lang.*`·`@Value` 매개변수는 주입으로 보지 않는다. Lombok 생성자 주입은 소스에 생성자가 없고 Lombok 합성 생성자(F-002-R14)가 정확히 하나일 때 그 매개변수가 된 필드를 주입으로 본다(`@Data`·`@Value`·`@Builder`가 만드는 생성자 포함). 합성 생성자가 둘 이상이면 Spring이 고를 생성자를 알 수 없어 주입으로 보지 않는다.
- `List<T>`, `Optional<T>`, `ObjectProvider<T>`, `Provider<T>` 주입의 대상은 `T`다.
- 생성자·setter 주입의 `attrs.field`는 그 매개변수를 대입한 필드명이고, 없으면 setter의 프로퍼티명 또는 매개변수명이다.
- `candidates`의 후보는 스테레오타입 타입과 `@Bean`이 만든 빈 중 주입 대상에 대입할 수 있는 프로젝트 타입이다. 고르는 순서는 qualifier → 필드명 → `@Primary` → 전부. qualifier가 있는데 맞는 후보가 없으면 `candidates`를 남기지 않는다.
- 매핑 애너테이션이 붙은 메서드가 있는 클래스(인터페이스 제외)를 컨트롤러로 본다. 경로가 상수가 아니면 `unresolved:http-endpoint:<식>`에서 메서드로 가는 `UNRESOLVED` `ROUTES_TO`를 남긴다.
- 뷰 이름은 매핑 또는 `@ExceptionHandler`가 붙은 메서드에서만 모은다. 람다·익명 클래스 안의 `return`은 보지 않는다. 반환 타입이 `String`/`Object`일 때만 `return` 식을 보고, 지역 변수를 반환하면 그 변수에 대입한 값들을 따라간다.
- Java 코드의 redirect·forward(F-004-R13)의 세부
  - 응답 객체는 식의 타입(상위 타입 포함)의 단순 이름이 `HttpServletResponse`, `HttpServletResponseWrapper`인 것이다(`javax`·`jakarta` 구분 없음). 인자가 하나인 `sendRedirect`만 본다. 타입 바인딩이 아예 없는 식(컴파일러가 보지 않은 코드)은 보지 않는다.
  - 디스패처는 `getRequestDispatcher(<식>)` 호출(수신자 타입은 따지지 않는다: 요청, `ServletContext`)이거나 그 결과를 받은 지역 변수다. 인자가 둘인 `forward`·`include`만 보고 둘 다 `forwards`에 넣는다. 타입이 `RequestDispatcher`인데 어디서 온 것인지 모르면(필드, 매개변수) 그 식을 원문으로 미해결을 남긴다.
  - 경로 식은 문자열 연결을 피연산자로 펴서 상수는 값으로, 나머지는 `{*}`로 바꾼다. 맨 앞 피연산자가 `getContextPath()` 호출이거나 그 결과만 받은 지역 변수면 뗀다(그것뿐이면 `/`). 삼항식은 두 값을, 지역 변수는 대입된 값들을 따라간다(세 단계까지). 첫 `?` 또는 `#`부터는 버린다. 남은 것에 상수 부분이 없으면 미해결이다(원문은 식 텍스트).
  - 값은 소스에 쓴 그대로다: `/`로 시작하지 않는 상대 경로와 `https://…` 같은 절대 URL도 그대로 넣는다. 엔드포인트와 맞출지, 외부 URL로 볼지는 링커가 정한다. forward 대상이 JSP 경로(`/WEB-INF/views/x.jsp`)일 수 있다.
  - attrs는 호출을 감싼 메서드(람다 안이면 람다를 감싼 메서드, 필드 초기화식이면 합성 소유자)의 노드에 나온 순서대로, 중복 없이 넣는다. 핸들러 메서드가 아니어도 넣는다(인터셉터, 필터, 서블릿).
  - 그 호출 자체의 Java `CALLS`(`HttpServletResponse#sendRedirect(?)` 등)는 F-003대로 따로 남는다.
- 다른 메서드를 부르는 반환식의 뷰 이름(F-004-R14)의 세부
  - 대상은 컴파일러가 해석한 프로젝트 메서드의 호출(`m(..)`, `this.m(..)`, `x.m(..)`, `super.m(..)`)이 반환식(또는 반환되는 지역 변수에 대입한 값, 삼항식의 가지)인 경우다. 해석되지 않은 호출과 소스 없는 타입의 호출은 지금처럼 미해결이다.
  - 헬퍼는 반환 타입이 `String`이고 본문이 있는, 뷰 이름을 모으는 핸들러가 아닌 모든 프로젝트 메서드다. 그 `return` 식만 본다(`ModelAndView`, `setViewName`은 보지 않는다). 헬퍼 노드에는 `viewNames`를 붙이지 않는다.
  - 미해결을 남기는 기준: 호출된 메서드가 핸들러면 남기지 않는다(그 핸들러가 자기 미해결을 남긴다). 헬퍼면 값을 정하지 못한 반환식이 그 헬퍼나 그 헬퍼가 부른 헬퍼에 하나라도 있을 때 남긴다(이때도 모은 뷰 이름은 `viewNames`에 넣는다). 본문을 모르는 메서드(인터페이스·추상 메서드, `String`을 돌려주지 않는 메서드)면 남긴다.
  - 자기 자신을 부르는 반환식은 따라가지 않고 미해결로 둔다. 매개변수 타입이 classpath에 없으면 컴파일러가 상위 클래스의 다른 오버로드 대신 자기 자신을 고르는 일이 있기 때문이다(F-003 한계).
  - 합친 `viewNames`의 순서는 자기 코드의 뷰 이름 다음에 호출된 메서드의 것이다. 재정의된 하위 타입의 메서드는 따라가지 않는다(정적으로 해석된 선언만).
- `@ModelAttribute` 메서드(F-004-R15)의 세부
  - "요청 핸들러"는 매핑 애너테이션이 붙은 메서드다(`@ExceptionHandler`만 붙은 메서드는 아니다). 매핑과 `@ModelAttribute`가 함께 붙은 메서드는 핸들러로만 본다(반환값을 모델에 넣을 뿐이다).
  - 상위 클래스는 프로젝트에 선언한 클래스만 따라간다(`EXTENDS`). 하위 클래스가 같은 이름·매개변수로 재정의한 `@ModelAttribute` 메서드는 하위의 것만 대상이다.
  - 엣지의 위치는 호출 지점이 없으므로 `@ModelAttribute` 메서드 선언의 위치다(상위 클래스의 것이면 다른 파일이다).
- 요청 경로의 자리표시자(F-004-R16): `${이름}`과 `${이름:기본값}`에서 이름만 남긴다. 경로 변수 `{id}`는 자리표시자가 아니다. 기본값 안에 `//`가 있으면(`${base:http://x}`) 경로 정규화가 `/` 하나로 줄인다(값이 달라진다. 링커가 치환할 때 원래 프로퍼티 값을 쓴다).
- 필터·인터셉터(F-004-R17, R18)의 세부
  - 필터로 보는 소스 없는 상위 타입: `javax.servlet.Filter`, `jakarta.servlet.Filter`, `javax.servlet.http.HttpFilter`, `jakarta.servlet.http.HttpFilter`, 그리고 이름이 `Filter`로 끝나고 패키지 이름에 `web`·`servlet`·`webapp`·`http`·`catalina` 조각이 있는 JDK 밖의 타입(Spring `web.filter`·`security.web`, Shiro `web.filter`·`web.servlet`, Ehcache `constructs.web.filter` 등). 인터셉터로 보는 것: `org.springframework.web.servlet.` 아래에서 이름에 `Interceptor`가 든 타입과 `WebRequestInterceptor`·`AsyncWebRequestInterceptor`.
  - classpath에 없는 상위 타입은 그 이름을 적은 파일의 import로 FQN을 복원해 판정한다. 복원하지 못하면 표시하지 않는다. 와일드카드 import(`import javax.servlet.*;`)의 `Filter`, `OncePerRequestFilter`, `GenericFilterBean`, `HandlerInterceptor`, `HandlerInterceptorAdapter`는 알려진 타입으로 복원한다.
  - 인터페이스에는 붙이지 않는다. Spring 스테레오타입과 겹치면 `controller`·`configuration`·`mapper`는 그대로 두고, 그 밖의 것(`component`, `service`, `repository`)은 빈 등록용으로 보고 타입에는 `filter`·`interceptor`를 적는다. 이때도 빈(`SPRING_BEAN`, `DEFINES_BEAN`, `beanName`)은 그대로 만들고 빈 노드의 `stereotype`은 애너테이션의 값이다. 빈이 아닌 필터(web.xml에 등록)에는 `beanName`과 빈을 만들지 않는다. 링커는 `stereotype` 값이 아니라 이 둘(`beanName`, `DEFINES_BEAN`)로 빈인지 판단한다(F-012-R27. `ServletFilterTest`의 F-004-AC20이 `@Service` 필터의 `beanName`·`DEFINES_BEAN`을 확인한다).
  - `RedirectStrategy`는 수신자 타입(상위 타입 포함)의 단순 이름이 `RedirectStrategy`·`DefaultRedirectStrategy`이거나 수신자가 `getRedirectStrategy()` 호출일 때, 인자가 셋인 `sendRedirect`의 셋째 인자를 경로로 본다. Shiro는 수신자가 `WebUtils.toHttp(..)` 호출인 `sendRedirect`와, 타입 이름 `WebUtils`로 부른 인자 셋 이상의 `issueRedirect`의 셋째 인자를 본다(타입의 FQN은 확인하지 않는다).
  - 경로 패턴이 `{*}`로 시작하고 상수 부분에 `/`가 없으면(`getLoginUrl() + sep + "forceLogout=1"`) 경로를 아는 것이 아니므로 `redirects`·`forwards`에 넣지 않고 미해결로 남긴다.
  - 필터의 URL 패턴(web.xml `<filter-mapping>`, `FilterRegistrationBean`)과 인터셉터의 경로 패턴은 이 기능이 다루지 않는다.
- 한계
  - `@ControllerAdvice` 클래스의 `@ModelAttribute` 메서드는 모든 컨트롤러의 핸들러 앞에서 불리지만 잇지 않는다. `@SessionAttributes`, `@InitBinder`의 실행도 표현하지 않는다.
  - `WebUtils.redirectToSavedRequest`, `response.setHeader("Location", ..)`, `response.setStatus(302)` 같은 다른 redirect 방식은 보지 않는다.
- `candidates`가 주입 대상 자신 하나뿐이면(`@Mapper` 인터페이스 등) 남기지 않는다.
- 추상 클래스와 `@Mapper`가 아닌 인터페이스는 스테레오타입이 있어도 빈을 만들지 않는다(`stereotype` 속성은 남긴다).
- `SqlSession` 계열은 타입(상위 타입 포함)의 단순 이름이 `SqlSession`, `SqlSessionTemplate`, `SqlSessionManager`, `DefaultSqlSession`, `EgovAbstractMapper`이거나 옵션 `java.sqlSessionTypes`(쉼표 구분 단순 이름)에 든 것, 그리고 `getSqlSession()` 호출 결과다.
- `EXECUTES`에는 `attrs.operation`(호출한 메서드 이름)을 남긴다. 구문 노드는 만들지 않는다(mybatis 모듈이 만든다).
- 애너테이션 SQL의 배열 값은 공백 하나로 잇는다. `<script>`의 `<where>`/`<set>`/`<trim prefix>`/`<foreach open close>`는 해당 키워드·문자로 바꾸고 나머지 태그는 본문만 남긴다(모든 분기를 이어 붙인다). 내장 소스의 `exactPositions`는 false다.
- 애너테이션 구문 노드에는 애너테이션 이름으로 아는 `statementType`을 미리 넣는다. 메서드→구문 `EXECUTES`는 링커(F-012-R4)가 만든다.

- 호출 지점 목록(F-004-R19)의 세부: 값은 `줄:열` 문자열이다(attrs 값은 String/Number/Boolean/List\<String>만 허용된다). 반환문은 `return`의 자리, `ModelAndView` 생성식은 `new`의 자리, 호출(`setViewName`, `sendRedirect`, `forward`, `include`, `issueRedirect`, 합쳐 온 뷰 이름의 그 호출)은 메서드 이름의 자리다(그 호출의 Java `CALLS`와 같은 자리). 삼항식·지역 변수로 나온 여러 이름은 같은 자리를 가진다. 자리를 모르는 항목은 `0:0`이다(링커가 메서드 위치로 읽는다).
- 구문 ID(F-004-R20): 접두는 연결식에서 첫 동적 부분 앞의 상수 문자열이다. 접두에 `.`이 없으면(namespace를 모름) 전처럼 식 원문을 미해결 원문으로 쓴다. 한 번 대입된 지역 변수의 값이 다시 "리터럴 접두 + 동적"이면 같은 규칙이다.
- 뷰 이름 후보(F-004-R21): 조합이 8개를 넘으면 펼치지 않는다(`{*}`). 필드·매개변수는 펼치지 않는다. 다른 메서드에서 합쳐 온 후보(F-004-R14)도 `viewNameCandidates`에 든다. **조정자가 제안한 형식은 boolean `viewNameCandidates=true`였는데 List로 했다**: 한 메서드에 확정된 뷰 이름과 후보가 섞일 수 있어, 어느 이름이 후보인지 적어야 링커가 그 `RENDERS`만 `INFERRED`로 만들 수 있다.
- 이름이 같은 스테레오타입 빈(F-004-R22): 빈 노드는 모든 파일을 본 뒤에 만든다(파일 순서와 무관하게 같은 결과). 정렬은 springxml의 XML 빈과 같다(테스트 소스가 뒤, 그 안에서 파일 경로순, 같은 파일이면 줄 순). `@Bean` 메서드가 정의한 같은 이름의 빈과의 정리는 하지 않는다(그 노드의 attrs는 전처럼 나중 것이 덮는다).
- `@Value` 필드(F-004-R23): 필드는 파싱 전에 소스 본문을 훑어 찾는다(`SourcePrescan`: `@Value("${…}")` 바로 뒤의 필드 선언. 값 전체가 자리표시자 하나일 때만). 최상위 타입 이름과 필드 이름으로 찾으므로 중첩 타입의 같은 이름 필드는 구분하지 않는다. 연결식에 상수 부분이 하나도 없으면(`return adminPath;`) 적용하지 않는다. `sendRedirect(adminPath)`처럼 식 전체가 그 필드 하나인 경우도 전처럼 미해결이다.
- 구문 ID 문자열 인자(F-004-R24)의 세부
  - 꼴은 `[A-Za-z_][A-Za-z0-9_$-]*(\.[A-Za-z_][A-Za-z0-9_$-]*)+`이고 200자를 넘는 값은 보지 않는다. 메시지 코드(`fail.common.msg`)나 클래스 이름도 이 꼴이라 함께 들어간다: 구문이 실제로 있는 것만 링커가 잇고, 없는 것은 아무것도 남기지 않는다.
  - 프로젝트 메서드에 넘긴 것은 넣지 않는다(프로젝트의 래퍼 DAO는 `java.sqlSessionTypes`로 다룬다). 선언을 찾지 못한 호출 가운데 수신자 없는 것(`assertEquals(..)`, 자기 타입의 메서드)도 넣지 않는다. 생성자 인자와 `super.` 호출의 인자는 보지 않는다.
  - 공개 프로젝트에서의 양: 메서드 attrs에 든 문자열은 공통컴포넌트 108개, simple-backend 69개, enterprise 16개다(대부분 메시지 코드·프로퍼티 키). 그 가운데 구문과 이어진 것은 공통컴포넌트 2건, enterprise 1건(모두 `uploadExcel`)이다.
- 뷰 이름 도우미(F-004-R25)의 세부
  - 도우미의 반환식은 `+`와 `.concat(<식>)`으로 이은 조각으로 편다. 조각이 상수면 값, 그 도우미의 매개변수(다시 대입되지 않은 것)면 인자 자리, 한 번만 대입된 지역 변수면 그 값의 식(3단계까지), 나머지는 `{*}`다. 매개변수가 섞이지 않은 반환식은 전처럼 F-004-R14의 규칙이다.
  - 호출 지점의 인자는 그 핸들러가 직접 부른 호출의 것이다. 도우미가 다른 도우미를 부르는 경우도 그 호출의 인자를 대입한다(상수일 때).
  - 믿을 수 없는 선언을 대신할 도우미는 컴파일러가 고른 선언의 타입과 그 상위 클래스에서, 매개변수 타입이 모두 해석됐고 인자를 받을 수 있는 선언이 정확히 하나일 때만 쓴다.
- 프로퍼티를 읽는 호출(F-004-R26)의 세부
  - 이름과 인자 꼴로 알아보는 휴리스틱이다. java는 그 자리에 `${키}`를 적을 뿐이고, 값이 없거나 갈리면 링커가 `{*}`로 읽으므로(F-012-R33, R47) 프로퍼티가 아닌 호출을 잘못 알아봐도 그 키가 프로젝트 properties에 없으면 전과 결과가 같다.
  - 핸들러(와 매개변수 없는 도우미)가 직접 이어 붙인 식에서는 호출이 연결식의 피연산자일 때만 본다(지역 변수에 담았다가 이어 붙인 값은 전처럼 `{*}`). 매개변수가 있는 도우미의 반환식에서는 한 번 대입된 지역 변수도 따라간다(F-004-R25의 틀). 필드에 담아 둔 값, `getProperty("키", "기본값")`(인자 둘), 수신자 없는 호출은 읽지 않는다.
  - 서블릿 API의 `redirects`·`forwards`(`sendRedirect(..)` 등)에는 적용하지 않는다(뷰 이름만).

## 6. 미확정 사항
- 없음 (사용자 위임에 따라 5의 가정으로 진행).

## 7. 관련 문서
- 기능정의서: [features.md](../../features.md)
- 그래프 계약: [F-001 design.md](../F-001-analysis-pipeline/design.md)
- 작업: [tasks.md](tasks.md)
- 관련 기능: [F-012 교차 연결](../F-012-cross-linking/spec.md), [F-017 Spring XML](../F-017-spring-xml/spec.md)

## 8. 계약 추가 제안 (design 3.5에 반영 요청)
design.md에 반영됨(2026-10-11): 아래 항목은 모두 F-001 design.md에 있다. 이번 라운드의 `redirects`·`forwards`는 3.5 노드 attrs 표와 3.9(`{*}`를 쓰는 곳)에, java가 만드는 미해결 `CALLS`는 3.4(`CALLS` 표와 `callType` 표)·3.5·3.6(`http-endpoint`)에 넣었다.

- JAVA_TYPE: `restController`(boolean), `beanName`(기본 빈 이름)
- JAVA_METHOD: `viewNames`(List. 모르는 부분은 `{*}`), `httpMappings`(List: `GET /order/list` 형식), `beanType`(`@Bean` 메서드의 반환 타입 FQN)
- (design.md에 반영됨(2026-10-11), 3.5) JAVA_METHOD: `redirects`, `forwards`(List. 그 메서드의 코드가 `sendRedirect` / `getRequestDispatcher(..).forward·include`로 넘기는 경로 패턴. 컨텍스트 경로와 쿼리 문자열 제외, 모르는 부분은 `{*}`). design 3.5 노드 attrs 표에 행 추가, 쓰는 모듈 java, 읽는 모듈 link
- (design.md에 반영됨(2026-10-11), 3.4·3.6) 미해결 `CALLS`(분류 `http-endpoint`, `attrs.callType`=`redirect`|`forward`)를 java도 만든다(경로를 전혀 알 수 없는 `sendRedirect`·`forward`·`include`). design 3.4의 `callType` 표(`redirect`, `forward`의 만드는 모듈)와 3.6 `http-endpoint` 행에 반영
- SPRING_BEAN: `class`(FQN), `beanType`(FQN), `source`(`stereotype` | `beanMethod`), `stereotype`, `primary`(boolean), `scope`, `property.<이름>`(String)
- DEFINES_BEAN attrs: `beanType`
- INJECTS attrs: `field`, `candidates`(List: 타입 ID), `required`(false일 때만)
- EXECUTES attrs: `shortId`(boolean), `statementId`, `namespace`, `operation`
- SQL_STATEMENT: `annotation`(boolean). 애너테이션 구문에는 `sql`(바꾼 본문, 공백 정리)도 java가 넣는다
- 미해결 분류: `mybatis-statement`, `view`, `http-endpoint`
- 옵션(extra): `java.sqlSessionTypes`

### 공개 프로젝트 검증 3차(B5a, B17, B18, B20)에서 더한 것
design.md에 반영됨(2026-10-11, 2차): `modelAttribute`는 3.4(`CALLS` 표, `callType` 표), `placeholders`·`stereotype`의 `filter`·`interceptor`·`viewNames`·`redirects`는 3.5 노드 attrs 표, 엔드포인트 ID의 `${…}`는 3.3·3.9, 소비자 규칙(`[before]`, 필터·인터셉터 진입점)은 3.4 "소비자가 지키는 규칙"에 넣었다. "빈인지는 `beanName`/`DEFINES_BEAN`으로 판단한다"는 3.5 공통 규칙 "빈으로 정의된 타입"에 있다.
- design 3.4 `callType` 표: 값 `modelAttribute` 추가(만드는 모듈 java. 요청 핸들러 → 같은 컨트롤러(상위 클래스 포함)의 `@ModelAttribute` 메서드. 항상 `INFERRED`, 위치는 대상 메서드의 선언, F-004-R15)
- design 3.5 HTTP_ENDPOINT attrs: `placeholders`(List: 경로에 든 프로퍼티 자리표시자 `${이름}`의 이름. 쓰는 모듈 java, 읽는 모듈 link — 프로퍼티 값으로 바꿔 요청과 맞춘다). 엔드포인트 ID와 `path`에는 `${…}`가 그대로 들어 있다
- design 3.5 JAVA_TYPE `stereotype` 값: `filter`(서블릿 필터), `interceptor`(Spring MVC 핸들러 인터셉터) 추가. **빈이 아닐 수 있다**: 이 두 값은 `stereotype`이 있다고 빈으로 보면 안 된다. 빈인지는 `beanName`/`DEFINES_BEAN`으로 판단한다(link의 주입 후보 계산은 2차 후속에서 그렇게 고쳤다. F-012-R27)
- design 3.5 JAVA_METHOD `viewNames`: 반환식이 프로젝트 메서드 호출이면 호출된 메서드(핸들러·헬퍼)의 뷰 이름이 합쳐진다(F-004-R14)
- design 3.5 JAVA_METHOD `redirects`: Spring Security `RedirectStrategy.sendRedirect`, Shiro `WebUtils.issueRedirect`·`WebUtils.toHttp(..).sendRedirect`의 경로도 들어간다(F-004-R17)
- 소비자(export·viewer)가 알아야 할 것: `modelAttribute` 엣지는 호출 지점이 아니라 선언 위치를 가리키고 핸들러마다 있다(jeesite-v1에서 125건). 콜트리에서는 핸들러 본문 호출보다 먼저 실행되는 "요청 전 처리"로 표시하는 것이 맞다

### 실전 재검증 2차 후속(2026-10-11)에서 더한 것 — design.md 반영 요청
design.md 반영됨(2026-10-11): `viewNameLines`·`redirectLines`·`forwardLines`·`viewNameCandidates`는 3.5 노드 attrs 표(JAVA_METHOD), `@Value("${키}")` 자리표시자는 3.5 공통 규칙 "프로퍼티 자리표시자"와 3.9(`${키}` 행), `statementPrefix`는 3.5 엣지 attrs 표(EXECUTES)와 3.6(`mybatis-statement`), `otherDefinitions`의 쓰는 모듈(java)은 3.5 노드 attrs 표(SPRING_BEAN)에 넣었다.
- design 3.5 JAVA_METHOD attrs: `viewNameLines`, `redirectLines`, `forwardLines`(List\<String>: `viewNames`·`redirects`·`forwards`와 같은 순서·같은 길이의 호출 지점 `줄:열`. 쓰는 모듈 java, 읽는 모듈 link — `RENDERS`·redirect·forward `CALLS`의 위치로 쓴다. F-004-R19)
- design 3.5 JAVA_METHOD attrs: `viewNameCandidates`(List\<String>: `viewNames` 가운데 지역 변수 값의 후보를 펼쳐 만든 이름. 쓰는 모듈 java, 읽는 모듈 link — 그 이름의 `RENDERS`를 `INFERRED` + `candidate=true`로 만든다. F-004-R21)
- design 3.5 `viewNames`·`redirects`·`forwards`의 설명과 3.9 `${키}` 행의 "쓰는 곳"에: `@Value("${키}")` 필드를 이어 붙인 자리에는 `${키}`(또는 `${키:기본값}`)가 원문으로 들어간다(F-004-R23). 링커가 프로젝트 properties 값으로 푼다.
- design 3.5 EXECUTES 엣지 attrs: `statementPrefix`(String: 구문 ID의 리터럴 접두. java가 남기는 미해결 `EXECUTES`와 link가 그 접두로 이은 `INFERRED` `EXECUTES`에 있다. F-004-R20). 3.6 `mybatis-statement` 행에 원문 꼴 `<접두>{*}`를 더한다.
- design 3.5 SPRING_BEAN attrs `otherDefinitions`: 쓰는 모듈에 java를 더한다(이름이 같은 스테레오타입 빈 정의. 형식은 springxml과 같다. F-004-R22).

### 실전 재검증 3차 후속(2026-10-11)에서 더한 것 — design.md 반영 요청
design.md 반영됨(2026-10-11): `statementIdLiterals`·`statementIdLiteralLines`와 `viewNames`의 인자 대입(F-004-R25)은 3.5 노드 attrs 표(JAVA_METHOD), `viaArgument`는 3.4(`EXECUTES` 행)와 3.5 엣지 attrs 표, `{*}`를 쓰는 곳은 3.9에 넣었다.
- design 3.5 JAVA_METHOD attrs: `statementIdLiterals`(List\<String>: 라이브러리 메서드 호출에 넘긴 "구문 ID처럼 보이는 문자열 상수". 쓰는 모듈 java, 읽는 모듈 link — 그 ID의 매퍼 구문이 실제로 있으면 `EXECUTES`(`INFERRED`, `viaArgument=true`)를 만든다. F-004-R24, F-012-R43), `statementIdLiteralLines`(List\<String>: 같은 순서·같은 길이의 `줄:열`).
- design 3.5 JAVA_METHOD `viewNames`: 도우미의 반환식에 매개변수가 섞였으면 호출 지점의 인자를 대입한 이름이 들어간다(F-004-R25). 3.9 `{*}`의 "쓰는 곳"에 "도우미 안의 알 수 없는 조각과 상수가 아닌 인자"를 더한다.
- 새 노드·엣지 종류, 진단 코드, 미해결 분류는 없다. `EXECUTES`의 `operation`은 전부터 java가 남긴다(link가 접두 구문 연결을 거르는 데 쓴다, F-012-R41).
- 소비자(export·viewer)가 알아야 할 것: `statementIdLiterals`에는 구문 ID가 아닌 문자열(메시지 코드 등)도 들어 있다. link가 읽는 중간 정보라 상세 화면에 펼칠 필요가 없다.

### 실전 재검증 4차 후속(2026-10-11)에서 더한 것
design.md에 직접 반영했다(2026-10-11): 3.5 공통 규칙 "프로퍼티 자리표시자", 3.5 노드 attrs 표(JAVA_METHOD `viewNames`), 3.9(`${키}`를 쓰는 곳).
- `viewNames`에서 프로퍼티를 읽는 꼴의 호출 자리는 `${키}`다(F-004-R26). 전에 `{*}`였던 이름(zheng의 `/{*}/blog/index.html` 등)이 `/${app.name}/blog/index.html`로 바뀐다. 새 attr·노드·엣지 종류는 없다.
