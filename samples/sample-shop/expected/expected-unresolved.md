# 미해결로 나와야 하는 지점

정적 분석만으로는 대상을 확정할 수 없는 지점이다. 분석 결과에서 조용히 빠지면 안 되고, 추측으로 채워져도 안 된다.
기대하는 모습은 그래프 계약(`docs/specs/F-001-analysis-pipeline/design.md` 3.2)의 미해결 표현이다:
`unresolved:<분류>:<원문>` 노드와 `UNRESOLVED` 신뢰도의 엣지, 그리고 진단 `UNRESOLVED_REFERENCE` 또는 `DYNAMIC_SQL`.

경로는 `samples/sample-shop` 기준이다. 줄 번호는 파일의 1부터 센 줄이다.

## 1. 반드시 미해결이어야 하는 지점

| # | 위치 | 내용 | 이유 | 있으면 안 되는 것 |
|---|---|---|---|---|
| U1 | `src/main/java/com/example/shop/order/dao/OrderDao.java:110` | `sqlSession.selectList(queryId, param)` | 구문 ID 가 메서드 인자다 | `OrderDao#selectByQueryId` → 어떤 `mybatis:stmt:` 로든 가는 EXECUTES |
| U2 | `src/main/java/com/example/shop/order/service/OrderServiceImpl.java:169-170` | `"orderDao.select" + type` 을 U1 로 넘긴다 | 구문 ID 의 뒷부분이 실행 시점 값이다 | `OrderServiceImpl#export` → `mybatis:stmt:` EXECUTES. (Java 호출 `export` → `OrderDao#selectByQueryId` 는 해석돼야 한다) |
| U3 | `src/main/resources/mapper/order/orderDao.xml:40` | `FROM ${tableName}` (구문 `orderDao.selectOrderBackup`) | 테이블 이름이 `${}` 치환이다 | 이 구문에서 `db:table:*` 로 가는 READS |
| U4 | `src/main/java/com/example/shop/order/web/OrderController.java:108` | `return "order/popup_" + type;` | 뷰 이름이 문자열 조합이다 | `OrderController#popup` → `jsp:page:*` RENDERS |
| U5 | `db/oracle/pkg_order.pkb:168` | `EXECUTE IMMEDIATE V_SQL USING P_BASE_DT` (`PKG_ORDER.ARCHIVE_ORDERS`) | 164~166행에서 테이블 이름을 조립한 동적 SQL | `db:table:TB_ORDER_ARC_*` 노드. (문자열 안의 `TB_ORDER` 를 읽기로 잡는지는 단정하지 않는다) |
| U6 | `db/db2/sp_batch_util.db2:42-43` | `PREPARE S1 FROM V_SQL; EXECUTE S1;` (`SP_PURGE_TABLE`) | 테이블 이름이 프로시저 인자(`P_TABLE_NM`)다 | `SP_PURGE_TABLE` 안의 구문에서 `db:table:*` 로 가는 WRITES |
| U7 | `frontend/src/api/orderApi.js:46` | `client.get(url, ...)`, `url = buildUrl('orders', 'export')` | URL 이 함수 호출 결과다 | 이 요청에서 나가는 MATCHES. `GET /api/orders/export` 엔드포인트는 화면 쪽 호출자가 없는 것으로 나온다 |
| U8 | `src/main/webapp/resources/js/common.js:32-33` | `$.ajax({ url: url, type: 'POST', ... })` (`ajaxPost` 래퍼) | URL 이 함수 인자다 | 이 요청에서 나가는 MATCHES |

## 2. 동적이지만 대상은 정해지는 지점 (미해결 아님, `dynamic` 표시만)

| # | 위치 | 내용 | 기대 |
|---|---|---|---|
| D1 | `src/main/resources/mapper/order/OrderMapper.xml:82` | `ORDER BY ${sortColumn}` (구문 `selectOrderList`) | 테이블 3개(TB_ORDER, TB_MEMBER, TB_CODE)는 그대로 READS. 구문에 `dynamic=true`, 진단 `DYNAMIC_SQL` |
| D2 | `src/main/resources/mapper/order/orderDao.xml:42` | `ORDER BY ${orderBy}` | U3 과 같은 구문. `dynamic=true` |
| D3 | 매퍼 XML 전반 | `<if>`, `<choose>`, `<foreach>`, `<where>`, `<set>`, `<trim>` | 분기 안의 테이블·조건을 모두 합쳐서 본다. 미해결이 아니다 |
| D4 | `src/main/webapp/WEB-INF/views/order/detail.jsp:61`, `order/form.jsp:55`, React 의 템플릿 리터럴 URL | `'/api/orders/' + orderId + '/payments'`, `` `/orders/${id}` `` | 동적 부분을 `{*}` 로 둔 패턴으로 엔드포인트의 경로 변수와 맞춘다 |
| D5 | `src/main/webapp/WEB-INF/views/main.jsp:38` | `ctx + '/api/products/categories/tree'` | 앞의 변수는 컨텍스트 경로다. 뒤쪽 리터럴로 맞추기를 기대하지만 확신이 낮다 (정답에 `certainty: low`) |
| D6 | JSP 의 `${ctx}`, `${pageContext.request.contextPath}`, `<c:url value='...'/>` | 컨텍스트 경로 접두사 | 접두사를 떼고 맞춘다. 미해결이 아니다 |

## 3. 한 단계 더 따라가야 보이는 관계 (단정하지 않음)

| # | 위치 | 내용 |
|---|---|---|
| I1 | `src/main/webapp/resources/js/order.js:36` | `ajaxPost(BASE + '/close.do', ...)`. 실제 요청은 U8 에서 나간다. 인자를 따라가면 `POST /order/close.do` 지만, 정답 목록에는 넣지 않았다 (있어도 없어도 된다). JS 함수 호출(`OrderModule` 의 `close` → `common.js` 의 `ajaxPost`)은 잡히기를 기대하지만, IIFE 안 함수의 ID 이름 규칙이 정해지지 않아 JSON 정답에는 넣지 않았다 |
| I2 | `src/main/java/com/example/shop/batch/config/DailySalesJobConfig.java:84` | `reader.setQueryId("...BatchMapper.selectClosedOrders")`. 리터럴이므로 구문은 확정된다. 다만 스텝 → 구문 EXECUTES 로 잇는 것은 리더 빈을 거쳐야 해서 정답에 `certainty: low` 로 두었다 |
| I3 | `src/main/resources/batch/settle-job.xml:38` | `<property name="statementId" value="settleDao.insertSettle"/>`. I2 와 같다. `settleDao.insertSettle` 은 Java 호출자가 없다 |

## 4. 환경에 따라 달라지는 미해결

- 의존 jar 없이 분석하면 라이브러리 타입의 메서드(`SqlSessionTemplate.selectList`, `Model.addAttribute`, `StepBuilder.chunk` 등)로 가는 호출은 미해결로 남는다. 개수는 단정하지 않는다. 프로젝트 안의 타입끼리의 호출은 jar 없이도 전부 해석돼야 한다.
- `BatchMapper.selectIsHoliday` 가 읽는 `TB_HOLIDAY` 는 DDL 에 없다. 미해결이 아니라 `declared=false` 인 `db:table:TB_HOLIDAY` 노드로 남는다.
- `common/header.jsp:22` 의 `https://code.jquery.com/...` 는 프로젝트 밖의 스크립트다. 프로젝트 모듈로 잇지 않는다.

## 5. 파싱 실패로 기록돼야 하는 파일 (`broken/`)

분석이 멈추지 않고, 아래 파일마다 진단(`PARSE_FAILED` 또는 `UNSUPPORTED_SYNTAX`)이 남아야 한다. 이 파일들에서 나온 노드와 엣지는 정답 목록에 넣지 않았다(부분 복구 결과가 있어도 없어도 된다).

| 파일 | 깨진 곳 |
|---|---|
| `broken/BrokenService.java` | 14행 세미콜론 없음, 17행 매개변수 목록 미닫힘, 18행 조건식 괄호 미닫힘, 클래스 끝 중괄호 없음 |
| `broken/broken_query.sql` | 6~10행 괄호 미닫힘과 종결자 없음, 13행 문자열 따옴표 미닫힘 |
| `broken/BrokenMapper.xml` | 10행 `<if>` 미닫힘, 11행 이스케이프하지 않은 `<`, 루트 요소 `</mapper>` 없음 |
