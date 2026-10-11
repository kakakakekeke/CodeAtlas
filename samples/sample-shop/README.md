# sample-shop: CodeAtlas 검증용 샘플 프로젝트

CodeAtlas 분석기의 통합 테스트 기준으로 쓰는 작은 "주문 관리" 시스템이다. 실제 현장 프로젝트가 없어서 Spring + MyBatis 웹/배치 코드의 전형적인 패턴과 까다로운 경우를 손으로 만들어 담았다.

- 분석 대상은 이 폴더(`samples/sample-shop`) 전체다. 정답 목록의 모든 경로와 노드 ID 는 **이 폴더를 프로젝트 루트로** 한 상대 경로다.
- 실행하거나 빌드하는 프로젝트가 아니다. `pom.xml` 도 `node_modules` 도 없다. 대신 모든 파일은 문법적으로 올바르다(`broken/` 제외).
- 정답(`expected/`)은 분석기 없이 소스를 읽고 사람이 판단해서 쓴 것이다. 분석기 결과로 정답을 고치지 않는다. 정답이 틀렸다고 판단되면 소스의 근거 줄을 확인하고 고친다.

## 1. 구성

```
sample-shop/
├─ src/main/java/com/example/shop/      Java 43개 (중첩·익명 포함 타입 46개)
│   ├─ common/   BaseVO, BaseService<T>, BizException, CodeConstants, PageRequest(record), OrderStatus(enum), LoginCheckInterceptor
│   ├─ config/   AppConfig(@Bean), WebConfig(인터셉터, 뷰 리졸버)
│   ├─ order/    web(OrderController, OrderApiController) · service · mapper(@Mapper) · dao(SqlSessionTemplate) · vo
│   ├─ member/   web · service · mapper(@Mapper + @Select) · vo
│   ├─ product/  web(ProductApiController) · service(인터페이스 없음) · mapper · vo
│   ├─ payment/  DiscountPolicy + 구현체 2개, PaymentService + XML 로만 등록한 구현체
│   └─ batch/    Java 설정 잡(DailySalesJobConfig) + XML 잡의 구성 요소, SettleDao
├─ src/main/resources/
│   ├─ applicationContext.xml           component-scan, XML 빈(constructor-arg, property ref)
│   ├─ mybatis-config.xml               typeAliases (매퍼의 parameterType/resultType 별칭)
│   ├─ batch/settle-job.xml             XML 배치 잡 (batch:job / step / tasklet / chunk, next=)
│   └─ mapper/                          매퍼 XML 7개
│       ├─ common/CommonMapper.xml      namespace `common` (공통 조각)                     Oracle
│       ├─ order/OrderMapper.xml        namespace = 인터페이스 FQN                          Oracle
│       ├─ order/orderDao.xml           namespace `orderDao` (문자열 ID 로 호출)            Oracle
│       ├─ member/MemberMapper.xml                                                          Oracle
│       ├─ product/ProductMapper.xml                                                        Oracle
│       ├─ batch/BatchMapper.xml                                                            Oracle
│       └─ batch/settleDao.xml          namespace `settleDao`                               DB2
├─ src/main/webapp/
│   ├─ WEB-INF/web.xml
│   ├─ WEB-INF/views/                   JSP 7개 (main, order/list·detail·form, member/list, common/header.jsp·footer.jspf)
│   └─ resources/js/                    레거시 JS 3개 (common.js, order.js, member.js)
├─ frontend/src/                        React 9개 (.js 3, .jsx 3, .ts 1, .tsx 2) + package.json
├─ db/
│   ├─ oracle/                          DDL 4개, 패키지 명세·본문, 독립 프로시저, 함수, 트리거 (9개)
│   └─ db2/                             DDL 1개, SQL PL 프로시저 파일 2개 (종결자 @)
├─ broken/                              일부러 깨뜨린 입력 3개
└─ expected/                            정답 목록
```

| 종류 | 개수 |
|---|---|
| Java | 43 |
| 매퍼 XML | 7 |
| 그 밖의 XML (Spring 2, MyBatis 설정 1, web.xml 1) | 4 |
| JSP / JSPF | 7 |
| 레거시 JS | 3 |
| React (JS/JSX/TS/TSX) | 9 |
| SQL (DDL, PL/SQL, SQL PL) | 12 |
| 깨진 입력 | 3 |

DB 객체: 테이블 15개(Oracle 12, DB2 3), 뷰 2, 시노님 2, 시퀀스 7, 패키지 1, 루틴 13(패키지 소속 6, 독립 프로시저·함수 5, 트리거 2). 매퍼 구문 59개(XML 57, 애너테이션 2), 엔드포인트 28개, 배치 잡 2개와 스텝 4개.

## 2. 인코딩이 다른 파일

아래 두 파일만 **EUC-KR(MS949)** 이다. 나머지는 전부 UTF-8(BOM 없음)이다. UTF-8 로 엄격하게 디코딩하면 실패하므로 그래프 계약 4.3 의 "UTF-8 실패 시 MS949" 경로를 탄다.

| 파일 | 확인할 것 |
|---|---|
| `src/main/java/com/example/shop/common/CodeConstants.java` | 한글 주석과 한글 문자열 상수. `API_PREFIX` 상수가 `ProductApiController` 의 경로에 쓰이므로, 이 파일을 못 읽으면 `/api/products` 엔드포인트가 전부 틀어진다 |
| `db/oracle/04_comments_and_codes.sql` | `COMMENT ON` 의 한글 코멘트(정답 노드의 `attrs.comment`)와 `INSERT` 의 한글 리터럴 |

편집기로 열 때 인코딩을 지켜야 한다. UTF-8 로 다시 저장하면 이 검증 항목이 사라진다.

## 3. 깨진 입력 (`broken/`)

도구가 죽지 않고 파일 단위 실패로 기록하는지 보는 용도다. 정답 목록에는 이 파일들에서 나오는 노드·엣지를 넣지 않았다.

| 파일 | 깨진 내용 |
|---|---|
| `broken/BrokenService.java` | 세미콜론 없음, 괄호·중괄호 미닫힘 |
| `broken/broken_query.sql` | 괄호 미닫힘, 종결자 없음, 따옴표 미닫힘 |
| `broken/BrokenMapper.xml` | 태그 미닫힘, 이스케이프하지 않은 `<`, 루트 요소 미닫힘 (XML 로서 잘못됨) |

## 4. 일부러 넣은 까다로운 패턴

### Java
| 패턴 | 위치 |
|---|---|
| 제네릭 상위 클래스. `validate(T)` 의 erasure 가 `validate(BaseVO)` | `common/BaseService.java`, `order/service/OrderServiceImpl.java` (`validate`, `doFind`) |
| 인자 타입으로만 구분되는 오버로드 `cancel(Long)` / `cancel(OrderVO)`. `cancel(getOrThrow(id))` 는 제네릭 반환 타입을 풀어야 맞는 쪽이 잡힌다 | `OrderService`, `OrderServiceImpl` |
| `super.xxx()` 호출 (재귀로 오인하기 쉽다) | `OrderServiceImpl.validate`, `payment/VipDiscountPolicy.discount` |
| 인터페이스 default 메서드 + static import | `OrderService.isCancelable` → `CodeConstants.isClosed` |
| 람다, 메서드 참조(`OrderVO::getOrderId`, `this::sortChildren`), 익명 클래스, 내부 클래스 | `OrderServiceImpl.findOrders`, `findOrderIds`, `AmountCalculator`, `product/service/ProductService.getCategoryTree` |
| 재귀 호출 | `ProductService.sortChildren` |
| 생성자 연쇄 `this(...)`, record 의 컴팩트 생성자, enum | `BizException`, `PageRequest`, `OrderStatus` |
| 필드 연관: 단일, `List<T>`, `Map`, 자기 참조, 정적 중첩 클래스 | `order/vo/OrderVO.java`, `product/vo/CategoryVO.java`, `order/vo/OrderSearchCond.java` |

### Spring
| 패턴 | 위치 |
|---|---|
| 같은 인터페이스의 구현체(빈) 둘 + `@Primary` + `@Qualifier` + `@Resource(name=)` | `payment/DefaultDiscountPolicy`, `VipDiscountPolicy` ← `OrderServiceImpl` 생성자, `member/service/MemberServiceImpl` |
| 생성자 주입 / 필드 주입 / setter 주입 / XML 주입 | `OrderServiceImpl` / `OrderController` / `batch/SettleDao` / `applicationContext.xml`, `batch/settle-job.xml` |
| 애너테이션 없이 XML 로만 등록한 빈 | `payment/PaymentServiceImpl`, `batch/SettleItemReader`, `SettleProcessor`, `BatchLogTasklet` |
| XML 주석 속의 `<bean>` | `applicationContext.xml` (`oldPaymentService`) |
| `@Bean` 메서드 (이름 기본값, `name` 지정) | `config/AppConfig.java` |
| 빈이 아닌 인터셉터를 `new` 로 등록 | `config/WebConfig.java` |
| 클래스 수준 경로가 상수 연결 `API_PREFIX + "/products"` (상수는 EUC-KR 파일에) | `product/web/ProductApiController.java` |
| HTTP 메서드 없는 `@RequestMapping`(→ ANY), `@RequestMapping(method=POST)`, 경로 없는 `@GetMapping`, 클래스 수준 매핑 없는 컨트롤러 | `order/web/OrderController.java`, `OrderApiController.java`, `member/web/MemberController.java` |
| 같은 경로·다른 HTTP 메서드, 리터럴 경로와 경로 변수의 겹침(`/export` vs `/{id}`) | `OrderApiController` |
| 뷰 이름: 문자열, `ModelAndView`, `redirect:`, 문자열 조합(미해결) | `OrderController`, `MemberController.main` |

### MyBatis
| 패턴 | 위치 |
|---|---|
| 문자열 ID 호출: 리터럴, `NAMESPACE + ".id"`, 상수에 담은 연결, 다른 namespace, 변수 ID(미해결) | `order/dao/OrderDao.java` |
| 서로 다른 namespace 에 같은 구문 id (`insertOrder`) | `OrderMapper.xml`, `orderDao.xml` |
| 한 메서드가 구문 둘 실행, 메서드 이름 ≠ 구문 id | `batch/SettleDao.java` (`deleteSettle`, `writeLog`) |
| Java 호출자 없이 문자열로만 실행되는 구문 | `BatchMapper.selectClosedOrders`(리더의 `setQueryId`), `settleDao.insertSettle`(XML 의 `statementId`) |
| 애너테이션 SQL: 한 줄, 문자열 배열 | `member/mapper/MemberMapper.java` |
| 다른 namespace 의 조각 include, 페이징 머리·꼬리가 두 조각으로 갈린 인라인 뷰 | `CommonMapper.xml` ← `OrderMapper.selectOrderList`, `MemberMapper.selectMemberList` |
| `<if>`, `<choose>`, `<where>`, `<set>`, `<trim>`, `<foreach>`, `<selectKey>`(BEFORE/AFTER), CDATA, `&lt;` | 매퍼 전반 |
| `${}` 로 테이블 이름·ORDER BY 가 동적 | `orderDao.selectOrderBackup`, `OrderMapper.selectOrderList` |
| resultMap 의 association / collection, 별칭과 FQN 타입 | `OrderMapper.xml`, `mybatis-config.xml` |
| XML 주석 속 SQL, SQL 주석(`/* */`) 속 테이블 이름, 힌트 주석 | `OrderMapper.xml` 61~64행, `selectOrderList` |
| 프로시저 호출: `{call ...}`, `{ call ... }`, 중괄호 없는 `CALL`, 익명 블록 `BEGIN ... END;`, SELECT 안의 함수 호출 | `OrderMapper.callCloseOrder`, `BatchMapper.callCloseDaily`, `settleDao.callWriteLog`, `orderDao.callCancelOrder`, `orderDao.selectOrderAmount`, `OrderMapper.selectOrderStatusStat` |

### SQL
| 패턴 | 위치 |
|---|---|
| Oracle: ROWNUM 페이징, `(+)` 아우터 조인, NVL/DECODE, MERGE, CONNECT BY, 시퀀스.NEXTVAL/CURRVAL, INSERT ALL | `CommonMapper`, `OrderMapper`, `MemberMapper`, `ProductMapper`, `BatchMapper` |
| DB2: CTE, FETCH FIRST n ROWS ONLY, WITH UR, VALUES NEXT VALUE FOR, CURRENT TIMESTAMP, SYSIBM.SYSDUMMY1 | `settleDao.xml` |
| ANSI JOIN 과 WHERE 절 암묵 조인, 상관 서브쿼리, 인라인 뷰, UNION ALL, INSERT ... SELECT | 매퍼 전반 |
| 다른 테이블을 읽어서 UPDATE (튜플 SET, EXISTS), 조인 인라인 뷰 UPDATE | `orderDao.updateOrderByPayment`, `settleDao.updateSettleAmt`, `orderDao.updateDeliveryShip` |
| 별칭 없이 테이블 이름으로 컬럼을 한정한 상관 서브쿼리 | `OrderMapper.deleteOrder`, `MemberMapper.deleteDormantMembers` |
| 뷰·시노님을 읽는 구문, DDL 에 없는 테이블 | `MemberMapper.selectMemberOrders`, `ProductMapper.selectProduct`, `common.selectCodeList`, `BatchMapper.selectIsHoliday` |
| 스키마를 붙인 이름(`SHOP.TB_ORDER`, `BATCH.TB_BATCH_LOG`) | `db/oracle/01_tables.sql`, `fn_get_code_nm.fnc`, `db/db2/` |
| FK 선언 형태 4가지(컬럼 인라인, 테이블 수준, ALTER, ALTER ADD 괄호), 주석 처리된 FK, CHECK 제약 | `db/oracle/01_tables.sql`, `02_constraints_indexes.sql` |
| FK 없이 조인으로만 드러나는 관계 6개 | `expected/erd-expected.md` |
| 주석 속 DDL, 문자열 리터럴 안의 세미콜론과 `''` | `01_tables.sql` 끝, `04_comments_and_codes.sql` 22·24·47행 |
| PL/SQL: 명세에 없는 내부 프로시저, 패키지 안팎 호출, 커서 FOR 루프, 명시적 커서, SELECT INTO, BULK COLLECT, FORALL, 예외 블록, EXECUTE IMMEDIATE, `TABLE OF` 타입 | `db/oracle/pkg_order.pks`, `pkg_order.pkb` |
| 한 파일에 트리거 둘(`/` 로 구분), `:NEW` / `:OLD` | `db/oracle/trg_order.trg` |
| DB2 SQL PL: 종결자 `@`, BEGIN ATOMIC, DECLARE HANDLER, 커서, PREPARE/EXECUTE, CALL, 문자열로 넘긴 테이블 이름 | `db/db2/sp_batch_util.db2`, `sp_settle_monthly.db2` |

### 화면
| 패턴 | 위치 |
|---|---|
| `<jsp:include>`(+`<jsp:param>`), `<%@ include %>`, `.jspf` | 각 JSP |
| URL 의 컨텍스트 경로: `${ctx}`, `${pageContext.request.contextPath}`, `<c:url>`, JS 변수 `ctx +` | `common/header.jsp`, `order/list.jsp`, `main.jsp` |
| `<form>` 의 method 생략(GET), 쿼리스트링이 붙은 링크, JSP 주석 속 링크 | `member/list.jsp`, `order/list.jsp`, `common/header.jsp` |
| 인라인 스크립트의 `$.ajax`(type 생략) / `$.post` / `$.get` / `$.getJSON` / `fetch` | `order/list.jsp`, `detail.jsp`, `form.jsp`, `main.jsp` |
| 함수 선언, 함수 표현식, IIFE 모듈 패턴, 객체 리터럴 메서드(축약 포함), XMLHttpRequest | `resources/js/common.js`, `order.js`, `member.js` |
| URL 을 인자로 받는 AJAX 래퍼(미해결), JS 주석 속 요청 | `common.js` (`ajaxPost`), `order.js` 67행 |
| axios 인스턴스의 `baseURL`, 템플릿 리터럴 URL, 상수가 든 템플릿 리터럴, 인스턴스 없는 axios, fetch 의 `method` 옵션 | `frontend/src/api/client.js`, `orderApi.js`, `productApi.ts`, `pages/OrderDetail.tsx` |
| 함수 호출로 조립한 URL(미해결) | `orderApi.js` (`exportOrders`) |
| 함수 컴포넌트, 훅, 커스텀 훅, 클래스 컴포넌트, TSX 화살표 컴포넌트, named/default export, default import 로 받은 객체의 메서드 호출 | `frontend/src/pages/`, `hooks/useOrders.js`, `components/CategoryTree.tsx` |

## 5. 정답 파일 (`expected/`)

| 파일 | 내용 |
|---|---|
| `must-have-edges.json` | 반드시 있어야 하는 엣지 298개 (핵심) |
| `must-have-edges-extra.json` | 같은 형식의 추가 엣지 136개. 핵심 목록을 300개 안으로 줄이면서 뺀, 뻔하거나 중복에 가까운 사실이다. 정답으로서의 무게는 같다 |
| `must-have-nodes.json` | 반드시 있어야 하는 노드 434개 (테이블·루틴·엔드포인트·매퍼 구문·배치 잡/스텝은 전부, 컬럼은 TB_ORDER / TB_ORDER_ITEM / TB_SETTLE 전부) |
| `must-not-have-edges.json` | 있으면 안 되는 엣지 40개 (함정) |
| `must-not-have-edges-extra.json` | 같은 형식의 추가 함정 35개 |
| `expected-unresolved.md` | 미해결로 나와야 하는 지점, 파싱 실패로 기록돼야 하는 파일 |
| `erd-expected.md` | 기대 ERD (Mermaid) 와 DDL FK / 추론 관계 표 |
| `check_expected.py` | 정답 목록 자체를 소스와 대조하는 점검 스크립트 (분석기와 무관) |

### 엣지 항목의 형식

```json
{"from": "<노드ID>", "to": "<노드ID>", "kind": "<EdgeKind>", "note": "근거 위치와 이유"}
```

노드 ID 와 엣지 종류는 `docs/specs/F-001-analysis-pipeline/design.md` 3.3, 3.4 를 따른다. 그 밖의 필드:

| 필드 | 뜻 | 비교할 때 |
|---|---|---|
| `altTo`, `altFrom` | 계약만으로는 한쪽 끝이 하나로 정해지지 않는 경우의 다른 허용 값 | `to`(또는 `from`)나 여기 적힌 값 중 하나와 맞으면 통과 |
| `attrs` | 그 엣지에 기대하는 속성(`op`, `via`, `qualifier`, `callType`, `source`, `columns`, `role` 등) | 엣지 존재 여부가 먼저다. 속성 비교는 선택 |
| `undirected` | `JOINS` 에만 붙는다 | 방향을 무시하고 테이블 쌍으로 비교. `leftColumns` 는 `from` 쪽, `rightColumns` 는 `to` 쪽 컬럼 |
| `statement`, `statementRoutine` | `JOINS` 의 근거 구문 ID, 그 구문이 든 루틴 | 선택 |
| `fromRoutine` | `from` 이 루틴 안의 구문(`sql:stmt:<루틴>:<줄>`)일 때 그 루틴 | 줄 번호 해석이 달라도, 그 루틴이 CONTAINS 하는 구문 중 하나가 `to` 로 가는 같은 종류의 엣지를 가지면 통과로 봐도 된다 |
| `fromFile`, `line` | SQL 파일 구문이나 화면 요청의 위치 | 아래 참고 |
| `certainty: "low"` | 사실 자체는 맞지만, 계약이 그 ID·표현을 확정하지 않아 분석기 설계에 따라 달라질 수 있는 항목 | 실패해도 바로 버그로 보지 말고 계약부터 확인 |

루틴 안 구문의 줄 번호는 **SQL 키워드(SELECT/INSERT/UPDATE/DELETE)가 시작하는 줄**로 적었다. 커서 선언은 `CURSOR ... IS` / `DECLARE ... CURSOR FOR` 줄이 아니라 그다음의 `SELECT` 줄이다.

`MATCHES` 는 `from`(HTTP_REQUEST ID)에 줄 번호가 들어가서 아래 형태로 적었다.

```json
{"kind": "MATCHES", "fromFile": "frontend/src/api/orderApi.js", "line": 13, "httpMethod": "GET",
 "requestUrl": "/api/orders/{*}", "rawUrl": "`/orders/${id}`", "to": "http:endpoint:GET /api/orders/{id}", "note": "..."}
```

- `requestUrl` 은 정규화한 URL 패턴이다: 컨텍스트 경로 접두사와 쿼리스트링을 떼고, axios `baseURL` 을 붙이고, 동적 부분을 `{*}` 로 바꿨다. `rawUrl` 은 소스에 적힌 모양이다.
- `line` 은 호출식이 시작하는 줄이다. `$.ajax({` 처럼 URL 이 다음 줄에 있는 경우는 `note` 에 적었다.
- 그 파일의 요청 중 `httpMethod` 와 URL 이 맞는 것이 `to` 엔드포인트로 MATCHES 를 가지면 통과다.

### 노드 항목의 형식

```json
{"id": "<노드ID>", "kind": "<NodeKind>", "attrs": {"기대 속성": "값"}, "note": "..."}
```

`attrs` 는 있을 때만 비교한다. `dataType` 은 공백 없이 대문자로 적었다(`NUMBER(15,2)`). `comment` 는 EUC-KR 파일에서 읽은 한글이 깨지지 않았는지 보는 용도다.

### 함정 항목의 형식

엣지 형식과 같고, 와일드카드를 쓴다.

| 표기 | 뜻 |
|---|---|
| `"from": "*"` 또는 `"to": "*"` | 아무 노드 |
| `"kind": "*"` | 아무 종류의 엣지 |
| 끝이 `*` 인 ID (`db:table:*`, `http:request:GET /admin/secret.do@*`) | 그 접두사로 시작하는 모든 노드 |
| `fromRoutine` | 그 루틴이 CONTAINS 하는 구문에서 나가는 엣지 |
| `attrs` | 그 속성까지 같은 엣지만 금지 (예: 추론으로는 있어야 하지만 `source=DDL` 로는 없어야 하는 FK) |

`"from": "*", "kind": "*"` 인 항목은 사실상 "그 노드가 그래프에 연결돼 있으면 안 된다"는 뜻이다(주석 속 테이블, 별칭, CTE 이름, DUAL 등).

### 정답을 정한 기준

- Java 호출은 컴파일러가 묶는 대상으로 적었다. 인터페이스 타입으로 부르면 인터페이스 메서드로 가는 CALLS, 구현체 쪽은 OVERRIDES 로 잇는다. 상속받은 메서드는 선언한 타입의 메서드 ID 를 쓴다. 람다·메서드 참조 안의 호출은 그것을 감싼 메서드에서 나가는 것으로 본다.
- 메서드 ID 의 매개변수는 erasure 의 바이너리 이름이다(`java.util.List`, `int`, 타입 변수 `T extends BaseVO` → `com.example.shop.common.BaseVO`, 중첩 타입은 `Outer$Inner`).
- INJECTS 의 `to` 는 주입 지점에 선언된 타입(대개 인터페이스)이고, 한정자로 정해지는 구현체는 `altTo` 에 넣었다.
- 추론 FK 는 "FK 제약이 없고, 자식 컬럼이 부모의 단일 컬럼 PK 전체와 등치 조인"인 경우만 넣었다. 그 밖의 조인은 `erd-expected.md` 의 "판단을 미룬 관계"에 적었다.
- 뷰·시노님을 읽는 구문은 `to` 를 `db:view:` / `db:synonym:` 로, `altTo` 에 `db:table:` 을 넣었다(계약 3.3 의 "링커가 맞춘다"가 어떤 모양일지 정해지지 않았다).
- 배치 스텝의 USES 대상은 빈과 타입 중 어느 쪽인지 계약에 없어서 양쪽을 `to` / `altTo` 로 적었다.

## 6. 검증한 것과 못 한 것

| 대상 | 확인 방법 | 결과 |
|---|---|---|
| Java 43개 | Spring·MyBatis·Batch·Servlet 의 최소 스텁을 임시 폴더에 만들어 `javac 24` 로 컴파일 (EUC-KR 파일은 UTF-8 사본으로) | 오류 없음. 실제 라이브러리 jar 로 컴파일한 것은 아니다 |
| XML 11개 (매퍼 7, 그 밖 4) | `xmllint --noout --nonet` | 정형 XML. DTD/XSD 검증은 하지 않았다 |
| 레거시 JS 3개 | `node --check` | 오류 없음 |
| React 9개 (.js/.jsx/.ts/.tsx) | `bun build --no-bundle` 로 트랜스파일 | 구문 오류 없음. TypeScript 타입 검사는 하지 않았다 |
| JSP 7개 | 눈으로 검토 | **기계 검증 못 함** (JSP 컴파일러 없음) |
| SQL 12개 (Oracle DDL·PL/SQL, DB2 DDL·SQL PL) | 눈으로 검토 | **기계 검증 못 함** (DB 없음). 실제 Oracle/DB2 에서 실행해 보지 않았다 |
| `erd-expected.md` 의 Mermaid | 눈으로 검토 | **기계 검증 못 함** (렌더러 없음) |
| 깨진 입력 3개 | `javac`, `xmllint` 가 오류를 내는지 | Java·XML 은 확인. SQL 은 눈으로만 |
| 인코딩 | 두 파일이 UTF-8 로는 디코딩되지 않고 MS949 로는 되는지, 나머지가 전부 UTF-8 인지 스크립트로 확인 | 확인 |

정답 목록은 `expected/check_expected.py` 로 소스와 대조했다(ID 형식, 타입·메서드·테이블·컬럼·루틴·구문의 존재, 구문 본문에 그 테이블·컬럼 이름이 있는지, 루틴 구문의 줄 번호, FK 가 DDL 에 있는지/없는지, 요청 URL 이 그 줄에 있는지, must-have 와 must-not 의 모순). 여기에 더해 Java 쪽은 컴파일한 클래스에서 `javap` 로 뽑은 메서드 시그니처·호출 지점·상속 관계와 대조했다(메서드 ID 전부, Java CALLS 54개, EXTENDS/IMPLEMENTS 13개 일치. 함정으로 적은 호출이 실제로 없는 것도 확인).

스크립트로 확인하지 못해 소스를 다시 읽어 확인한 것: INJECTS, ASSOCIATES, DEFINES_BEAN, USES, NEXT, CONTAINS, ALIAS_OF, TRIGGERS_ON, OVERRIDES 의 짝, 컬럼의 `primaryKey`, 노드의 `comment`, 그리고 "읽기만 하는가 / 쓰는가" 같은 의미 판단.

```
cd samples/sample-shop
python3 expected/check_expected.py            # 소스 텍스트와 대조
```

## 7. 샘플을 고칠 때

- 루틴 안 구문(`sql:stmt:PKG_ORDER.CLOSE_ORDER:47`), SQL 파일 구문, `MATCHES` 의 `line`, `expected-unresolved.md` 는 **줄 번호에 묶여 있다**. `db/` 의 프로시저 파일, JSP, JS 파일에 줄을 넣거나 빼면 정답도 고쳐야 한다. `check_expected.py` 가 어긋난 줄을 잡아 준다.
- 매퍼 XML 과 Java 는 줄 번호에 묶인 정답이 거의 없다(`expected-unresolved.md` 의 위치 표기만).
- 새 패턴을 넣으면 4절의 표와 정답 목록에 함께 적는다.
