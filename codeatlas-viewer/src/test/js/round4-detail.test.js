'use strict';
// 4차 수정(2026-10-11): 3차에서 계약에 들어온 속성 가운데 상세·라벨에 빠져 있던 표시(F-016-R35)와,
// 진단 화면의 "SQL과 DDL의 컬럼 불일치"·"화면 요청" 보기(F-016-R36), ERD의 DDL에 없는 컬럼·방향 미상의 까닭(F-016-R38).
// 속성의 모양은 분석기 소스(sql FactsWriter·DdlProcessor, jsp PageAnalysis, web JsProjectAnalysis, link ViewLinker·MapperBindingLinker)와 실제 그래프에서 옮겼다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { slice, loc } = require('./fixtures/slice.js');

const graph = core('graph');
const erd = core('erd');
const diag = core('diagnostics');

const tagsOf = (ix, id) => graph.nodeTags(ix.node(id)).map((t) => t.text);
function edgeItem(ix, fromId, kind, toId) {
  const group = graph.nodeDetail(ix, fromId).outgoing.find((g) => g.kind === kind);
  return group.items.find((i) => i.otherId === toId);
}
const table = (s, name, attrs) => s.n('db:table:' + name, 'DB_TABLE', name, name, attrs || {}, loc('db/ddl.sql', 1));
function column(s, tableName, name, attrs) {
  const id = s.n('db:column:' + tableName + '.' + name, 'DB_COLUMN', name, tableName + '.' + name, Object.assign({ dataType: 'VARCHAR2(20)' }, attrs || {}));
  s.e('db:table:' + tableName, 'CONTAINS', id);
  return id;
}

test('F-016-AC67 고정 값(fixedValues·leftFixedValues·rightFixedValues)은 첫 =에서만 잘라 컬럼=\'값\'으로 적는다: 빈 값은 컬럼=\'\', 값에 =가 있어도 그대로다', () => {
  assert.equal(graph.fixedText(['GROUP_CD'], ['GROUP_CD=ORDER_STATUS']), "GROUP_CD='ORDER_STATUS'");
  assert.equal(graph.fixedText(['USE_YN'], ['USE_YN=']), "USE_YN=''", '빈 문자열과 비교한 것은 값이 빈 것이다');
  assert.equal(graph.fixedText(['EXPR'], ['EXPR=A=B']), "EXPR='A=B'", '값 안의 =는 값의 일부다');
  assert.equal(graph.fixedText(['EXPR'], ['EXPR=A=B', 'EXPR=']), "EXPR in ('A=B', '')");
  assert.equal(graph.fixedText(['SEQ', 'CD'], ['SEQ=1', 'CD=?']), 'SEQ=1, CD=?');
  assert.equal(graph.fixedText(['CD'], []), 'CD=상수', '값을 모르면 상수라고만 적는다');
  // =로 시작하는 원소(컬럼 이름이 없다)는 버린다
  assert.equal(graph.fixedText([], ['=X']), '');
  assert.equal(erd.fixedText(['A'], ['A=1=2']), "A='1=2'", 'ERD 라벨도 같은 함수를 쓴다');
});

test('F-016-AC67 JOINS의 wrapped·leftWrapped·rightWrapped는 엣지 요약에 (함수 적용)으로 나온다', () => {
  const s = slice();
  const a = table(s, 'TB_A');
  const b = table(s, 'TB_B');
  const c = table(s, 'TB_C');
  s.e(a, 'JOINS', b, { leftColumns: ['ID'], rightColumns: ['A_ID'], statement: 'mybatis:stmt:m.M.x', wrapped: true, leftWrapped: true });
  s.e(a, 'JOINS', c, { leftColumns: ['ID'], rightColumns: ['A_ID'], statement: 'mybatis:stmt:m.M.y', wrapped: true, leftWrapped: true, rightWrapped: true,
    rightFixedColumns: ['USE_YN'], rightFixedValues: ['USE_YN='] });
  s.e(b, 'JOINS', c, { leftColumns: ['ID'], rightColumns: ['B_ID'], statement: 'mybatis:stmt:m.M.z' });
  s.e(c, 'JOINS', a, { leftColumns: ['K'], rightColumns: ['K'], statement: 'mybatis:stmt:m.M.w', wrapped: true });
  const ix = graph.buildIndex(s.graph());
  assert.equal(edgeItem(ix, a, 'JOINS', b).summary, 'TB_A.ID = TB_B.A_ID (함수 적용: TB_A 쪽)');
  assert.equal(edgeItem(ix, a, 'JOINS', c).summary, "TB_A.ID = TB_C.A_ID (함수 적용: 양쪽) · TB_C 고정 USE_YN=''");
  assert.equal(edgeItem(ix, b, 'JOINS', c).summary, 'TB_B.ID = TB_C.B_ID');
  assert.equal(edgeItem(ix, c, 'JOINS', a).summary, 'TB_C.K = TB_A.K (함수 적용)');
});

test('F-016-AC67 upgrade 스크립트에서 온 외래 키(fromUpgrade=true)는 엣지 요약과 ERD 라벨에 (upgrade 스크립트)가 붙는다. 표준 산출물의 라벨과 같다 (F-013-AC23)', () => {
  const s = slice();
  ['ORDERS', 'CUSTOMER', 'COUPON', 'STORE'].forEach((t) => { table(s, t); column(s, t, t + '_ID', { primaryKey: true, nullable: false, ordinal: 1 }); });
  column(s, 'ORDERS', 'CUSTOMER_ID', { ordinal: 2, nullable: false });
  column(s, 'ORDERS', 'COUPON_ID', { ordinal: 3 });
  column(s, 'ORDERS', 'STORE_ID', { ordinal: 4 });
  s.e('db:table:ORDERS', 'REFERENCES', 'db:table:CUSTOMER', { columns: ['CUSTOMER_ID'], refColumns: ['CUSTOMER_ID'], constraint: 'FK_ORDERS_CUSTOMER', source: 'DDL' });
  s.e('db:table:ORDERS', 'REFERENCES', 'db:table:COUPON', { columns: ['COUPON_ID'], refColumns: ['COUPON_ID'], constraint: 'FK_ORDERS_COUPON', source: 'DDL', fromUpgrade: true });
  s.e('db:table:ORDERS', 'REFERENCES', 'db:table:STORE', { columns: ['STORE_ID'], refColumns: ['STORE_ID'], source: 'DDL', fromUpgrade: true });
  const ix = graph.buildIndex(s.graph());
  assert.equal(edgeItem(ix, 'db:table:ORDERS', 'REFERENCES', 'db:table:COUPON').summary, 'COUPON_ID → COUPON_ID · DDL FK_ORDERS_COUPON (upgrade 스크립트)');
  assert.equal(edgeItem(ix, 'db:table:ORDERS', 'REFERENCES', 'db:table:STORE').summary, 'STORE_ID → STORE_ID · DDL (upgrade 스크립트)');
  assert.equal(edgeItem(ix, 'db:table:ORDERS', 'REFERENCES', 'db:table:CUSTOMER').summary, 'CUSTOMER_ID → CUSTOMER_ID · DDL FK_ORDERS_CUSTOMER');
  // 화면에 그리는 ERD: DDL 관계(실선)로 그리되 라벨로 구분한다
  const scope = erd.selectScope(ix, {});
  const mermaid = erd.toMermaid(ix, scope, {}).source;
  assert.ok(mermaid.includes('--o{ ORDERS : "FK_ORDERS_COUPON (upgrade 스크립트)"'), mermaid);
  assert.ok(mermaid.includes('--o{ ORDERS : "STORE_ID (upgrade 스크립트)"'), mermaid);
  assert.ok(mermaid.includes('--o{ ORDERS : "FK_ORDERS_CUSTOMER"'), mermaid);
  // 표준 산출물과 같은 글자(erd/erd.mmd): ErdLabels.relationLabel
  const model = erd.exportModel(ix, {});
  const exported = erd.exportMermaid(ix, model.entities, model.relations, {});
  assert.ok(exported.includes('COUPON ||--o{ ORDERS : "FK_ORDERS_COUPON (upgrade 스크립트)"'), exported);
  assert.ok(exported.includes('STORE ||--o{ ORDERS : "STORE_ID (upgrade 스크립트)"'), exported);
  assert.ok(exported.includes('CUSTOMER ||--o{ ORDERS : "FK_ORDERS_CUSTOMER"'), exported);
});

test('F-016-AC67 요청의 api=tag·route와 optionKey=arg, 클라이언트 라우트는 꼬리표와 요약에 읽는 말로 나온다', () => {
  const s = slice();
  const req = (id, attrs) => s.n('http:request:' + id, 'HTTP_REQUEST', id, id, attrs, loc('src/main/webapp/a.jsp', 3));
  const tag = req('GET /sys/menu/tree@a.jsp:3', { httpMethod: 'GET', path: '/sys/menu/tree', url: '${ctx}/sys/menu/tree', api: 'tag' });
  const route = req('GET /login@Login.jsx:20', { httpMethod: 'GET', path: '/login', url: '/login', api: 'route', clientRoute: true });
  const nav = req('GET /mypage@Login.jsx:24', { httpMethod: 'GET', path: '/mypage', url: 'URL.MYPAGE', api: 'navigation', clientRoute: true });
  const arg = req('GET /sys/user/form@b.js:9', { httpMethod: 'GET', path: '/sys/user/form', url: "'get:' + ctx + '/sys/user/form'", api: 'option', optionKey: 'arg', callee: 'top.$.jBox.open' });
  const link = req('GET /a@a.jsp:9', { httpMethod: 'GET', path: '/a', url: '/a', api: 'link' });
  const ix = graph.buildIndex(s.graph());
  assert.deepEqual(tagsOf(ix, tag), ['태그 속성의 URL']);
  assert.match(graph.nodeTags(ix.node(tag))[0].title, /api=tag/);
  assert.equal(graph.nodeSummary(ix.node(tag)), 'URL 원문 ${ctx}/sys/menu/tree · 커스텀 태그의 속성에서 읽은 URL (api=tag)');
  assert.deepEqual(tagsOf(ix, route), ['화면 내 이동']);
  assert.match(graph.nodeTags(ix.node(route))[0].title, /clientRoute=true/);
  assert.equal(graph.nodeSummary(ix.node(route)), 'URL 원문 /login · 클라이언트 라우터 호출 (api=route)');
  assert.deepEqual(tagsOf(ix, nav), ['화면 내 이동']);
  assert.equal(graph.nodeSummary(ix.node(nav)), 'URL 원문 URL.MYPAGE · navigation');
  assert.deepEqual(tagsOf(ix, arg), ['옵션 객체의 URL', '호출 인자의 URL']);
  assert.match(graph.nodeTags(ix.node(arg))[1].title, /optionKey=arg/);
  assert.equal(graph.nodeSummary(ix.node(arg)), "URL 원문 'get:' + ctx + '/sys/user/form' · option · 호출의 문자열 인자에서 읽은 URL (optionKey=arg) · 호출 top.$.jBox.open");
  assert.deepEqual(tagsOf(ix, link), []);
  assert.equal(graph.nodeSummary(ix.node(link)), 'URL 원문 /a · link');
});

test('F-016-AC67 엣지 요약: INCLUDES의 minOf, EXECUTES의 prefixMatch, RENDERS의 via=tiles·definition·candidate·suffixMatch, 레이아웃 INCLUDES, 물려받은 빈 설정, loadUnverified', () => {
  const s = slice();
  const page = s.n('jsp:page:src/main/webapp/list.jsp', 'JSP_PAGE', 'list.jsp', 'src/main/webapp/list.jsp', { pageType: 'jsp' });
  const layout = s.n('jsp:page:src/main/webapp/layouts/default.jsp', 'JSP_PAGE', 'default.jsp', 'src/main/webapp/layouts/default.jsp', { pageType: 'jsp' });
  const body = s.n('jsp:page:src/main/webapp/user/body.jsp', 'JSP_PAGE', 'body.jsp', 'src/main/webapp/user/body.jsp', { pageType: 'jsp' });
  const js = s.n('js:module:src/main/webapp/static/common/jeesite.js', 'JS_MODULE', 'jeesite.js', 'src/main/webapp/static/common/jeesite.js', { moduleKind: 'script' });
  s.e(page, 'INCLUDES', js, { via: 'script', path: '${ctxStatic}/common/jeesite.min.js', minOf: 'src/main/webapp/static/common/jeesite.min.js' }, 'INFERRED');
  s.e(page, 'INCLUDES', layout, { via: 'sitemesh', path: 'default' });
  s.e(layout, 'INCLUDES', body, { via: 'tiles', path: 'body' });
  const method = (owner, name) => { const id = s.n('java:method:' + owner + '#' + name + '()', 'JAVA_METHOD', name, owner + '#' + name + '()', {}); return id; };
  const dao = method('g.OrderDao', 'selectList');
  const stmt = s.n('mybatis:stmt:g.OrderDao.selectList_D', 'SQL_STATEMENT', 'selectList_D', 'g.OrderDao.selectList_D', { statementType: 'SELECT' });
  s.e(dao, 'EXECUTES', stmt, { prefixMatch: true, statementPrefix: 'g.OrderDao.selectList', resolvedFrom: 'mybatis:stmt:g.OrderDao.selectList' }, 'INFERRED');
  const handler = method('g.UserController', 'list');
  s.e(handler, 'RENDERS', layout, { viewName: 'user.list', via: 'tiles', definition: 'user.*' });
  s.e(handler, 'RENDERS', body, { viewName: 'user.list', via: 'tiles', definition: 'user.*', tilesAttribute: true });
  const guess = s.n('jsp:page:src/main/webapp/egov/main.jsp', 'JSP_PAGE', 'main.jsp', 'src/main/webapp/egov/main.jsp', { pageType: 'jsp' });
  s.e(handler, 'RENDERS', guess, { viewName: 'main', suffixMatch: true }, 'INFERRED');
  s.e(handler, 'RENDERS', page, { viewName: 'list', candidate: true, resolverPrefix: '/WEB-INF/a/', resolverSuffix: '.jsp' }, 'INFERRED');
  const type = (fqn) => s.n('java:type:' + fqn, 'JAVA_TYPE', fqn.substring(fqn.lastIndexOf('.') + 1), fqn, { typeKind: 'class' });
  type('g.OrderService');
  type('g.OrderDaoImpl');
  s.e('java:type:g.OrderService', 'INJECTS', 'java:type:g.OrderDaoImpl', { via: 'xml', property: 'orderDao', inheritedFrom: 'baseService' });
  const fnA = s.n('js:function:static/a.js#init', 'JS_FUNCTION', 'init', 'static/a.js#init', { kind: 'function' });
  const fnB = s.n('js:function:static/common.js#fnSearch', 'JS_FUNCTION', 'fnSearch', 'static/common.js#fnSearch', { kind: 'function' });
  s.e(fnA, 'CALLS', fnB, { callType: 'call', loadUnverified: true });
  const ix = graph.buildIndex(s.graph());
  assert.equal(edgeItem(ix, page, 'INCLUDES', js).summary, '압축본 jeesite.min.js를 싣고 jeesite.js로 분석');
  assert.equal(edgeItem(ix, page, 'INCLUDES', layout).summary, '레이아웃: sitemesh 데코레이터');
  assert.equal(edgeItem(ix, layout, 'INCLUDES', body).summary, '레이아웃: tiles template의 조각');
  assert.equal(edgeItem(ix, dao, 'EXECUTES', stmt).summary, '구문 ID 접두 일치 · g.OrderDao.selectList로 시작하는 구문');
  assert.equal(edgeItem(ix, handler, 'RENDERS', layout).summary, '뷰 이름 user.list · tiles 정의 user.*의 template(레이아웃)');
  assert.equal(edgeItem(ix, handler, 'RENDERS', body).summary, '뷰 이름 user.list · tiles 정의 user.*의 조각');
  assert.equal(edgeItem(ix, handler, 'RENDERS', guess).summary, '뷰 이름 main · 뷰 이름 접미 일치 (추론)');
  assert.equal(edgeItem(ix, handler, 'RENDERS', page).summary, '뷰 이름 list · 후보 여럿 가운데 하나 (추론)');
  assert.equal(edgeItem(ix, 'java:type:g.OrderService', 'INJECTS', 'java:type:g.OrderDaoImpl').summary, '부모 빈 baseService에서 물려받은 설정');
  assert.equal(edgeItem(ix, fnA, 'CALLS', fnB).summary, '로드 미확인: 이 스크립트를 함께 싣는지 확인하지 못했다');
});

/** 정의가 있는 테이블의 declared=false 컬럼(SQL만 아는 컬럼)과, 방향을 거둔 추론 관계 셋. */
function schema() {
  const s = slice();
  const f = {};
  f.user = table(s, 'T_USER');
  column(s, 'T_USER', 'USER_ID', { primaryKey: true, nullable: false, ordinal: 1 });
  f.article = table(s, 'CMS_ARTICLE');
  column(s, 'CMS_ARTICLE', 'ID', { primaryKey: true, nullable: false, ordinal: 1 });
  f.ghost = s.n('db:table:GHOST', 'DB_TABLE', 'GHOST', 'GHOST', { declared: false });
  const stmt = (name) => s.n('mybatis:stmt:com.u.' + name, 'SQL_STATEMENT', name.substring(name.lastIndexOf('.') + 1), 'com.u.' + name,
    { statementType: 'SELECT', namespace: 'com.u.' + name.substring(0, name.lastIndexOf('.')), statementId: name.substring(name.lastIndexOf('.') + 1) }, loc('mapper/' + name + '.xml', 5));
  f.selectUser = stmt('UserMapper.selectUser');
  f.updateUser = stmt('UserMapper.updateUser');
  f.findStats = stmt('CategoryDao.findStats');
  // SQL만 아는 컬럼: sql 분석기가 declared=false 컬럼 노드를 만들고 테이블에 붙인다
  f.subDn = column(s, 'T_USER', 'SUB_DN', { declared: false, dataType: undefined });
  f.zLast = column(s, 'T_USER', 'A_FLAG', { declared: false });
  f.updateDate = column(s, 'CMS_ARTICLE', 'UPDATEDATE', { declared: false });
  // 정의가 없는 테이블의 컬럼은 대상이 아니다(그 테이블은 "DDL에 없는 테이블"에 나온다)
  const ghostCol = column(s, 'GHOST', 'X', { declared: false });
  s.e(f.selectUser, 'READS', f.user);
  s.e(f.selectUser, 'READS', f.subDn);
  s.e(f.updateUser, 'WRITES', f.subDn, { op: 'UPDATE' });
  s.e(f.updateUser, 'READS', f.subDn);
  s.e(f.selectUser, 'READS', f.zLast);
  s.e(f.findStats, 'READS', f.updateDate);
  s.e(f.findStats, 'READS', ghostCol);
  // 방향 미상 추론 관계: 까닭 셋
  const inferred = (from, to, more) => s.e(from, 'REFERENCES', to, Object.assign({ columns: ['K'], refColumns: ['K'], source: 'INFERRED', evidenceCount: 2,
    directionKnown: false, cardinality: 'unknown' }, more), 'INFERRED');
  inferred(f.user, f.article, { declaredOpposite: true });
  inferred(f.article, f.ghost, { pseudoKey: true });
  inferred(f.ghost, f.user, {});
  f.graph = s.graph();
  return f;
}

test('F-016-AC68 진단: "SQL과 DDL의 컬럼 불일치" 보기는 정의가 있는 테이블의 declared=false 컬럼을 테이블·컬럼·참조하는 구문과 함께 report.md와 같은 순서로 낸다', () => {
  const f = schema();
  const ix = graph.buildIndex(f.graph);
  const r = diag.collect(ix);
  assert.deepEqual(r.columnMismatches.map((c) => [c.table, c.column, c.statementCount, c.statements.map((x) => x.label)]), [
    ['CMS_ARTICLE', 'UPDATEDATE', 1, ['CategoryDao.findStats']],
    ['T_USER', 'A_FLAG', 1, ['UserMapper.selectUser']],
    ['T_USER', 'SUB_DN', 2, ['UserMapper.selectUser', 'UserMapper.updateUser']]
  ]);
  assert.equal(r.columnMismatches[0].type, 'COLUMN_MISMATCH');
  assert.equal(r.columnMismatches[2].nodeId, f.subDn, '누르면 그 컬럼으로 간다');
  const view = diag.VIEWS.find((v) => v.rows === 'columnMismatches');
  assert.ok(view && /SQL과 DDL의 컬럼 불일치/.test(view.description), '보기 목록에 있다');
  assert.deepEqual(diag.filter(r.columnMismatches, { text: 'sub_dn update' }).map((c) => c.column), ['SUB_DN'], '글자로 거른다(테이블, 컬럼, 구문)');
  // 테이블 상세에서도 구분해 보인다(F-016-AC70)
  assert.deepEqual(graph.nodeDetail(ix, f.user).undeclaredColumns.map((c) => [c.name, c.statements.map((x) => x.label)]), [
    ['A_FLAG', ['UserMapper.selectUser']], ['SUB_DN', ['UserMapper.selectUser', 'UserMapper.updateUser']]
  ]);
  assert.deepEqual(graph.nodeDetail(ix, f.ghost).undeclaredColumns, [], '정의 없는 테이블은 대상이 아니다');
  assert.deepEqual(graph.nodeDetail(ix, f.selectUser).undeclaredColumns, []);
});

test('F-016-AC68 진단: "화면 요청" 통계는 서버 요청과 그 밖(외부 URL, 정적 페이지 링크, 클라이언트 라우트)으로 나누고, 서버 요청을 연결됨(확정·추론)·미연결로 센다. report.md의 절과 같다 (F-015-AC67)', () => {
  const s = slice();
  const endpoint = s.n('http:endpoint:GET /api/auth/me', 'HTTP_ENDPOINT', 'GET /api/auth/me', 'GET /api/auth/me', { httpMethod: 'GET', path: '/api/auth/me' });
  let n = 0;
  const req = (attrs) => s.n('http:request:r' + (++n), 'HTTP_REQUEST', 'r' + n, 'r' + n, Object.assign({ httpMethod: 'GET', path: '/x' + n }, attrs || {}), loc('a.jsp', n));
  s.e(req(), 'MATCHES', endpoint);
  s.e(req(), 'MATCHES', endpoint, { strippedPrefix: '/ctx' }, 'INFERRED');
  s.e(req(), 'MATCHES', endpoint, { crossModule: true });
  const missing = req();
  const gone = s.n('unresolved:http-endpoint:POST /api/auth/logout', 'UNRESOLVED', 'POST /api/auth/logout', 'POST /api/auth/logout', { category: 'http-endpoint' });
  s.e(missing, 'MATCHES', gone, {}, 'UNRESOLVED');
  req({ external: true, path: 'https://kauth.example.com/oauth/authorize' });
  req({ external: true, path: 'https://nid.example.com/oauth2.0/authorize' });
  req({ clientRoute: true, api: 'route', path: '/login' });
  const ix = graph.buildIndex(s.graph());
  const r = diag.collect(ix);
  assert.deepEqual(r.requestStats, { total: 7, server: 4, matched: 3, sure: 1, inferred: 2, unmatched: 1, external: 2, statics: 0, clientRoutes: 1, rate: 75,
    summaryText: '서버 요청 4건 중 연결 3(확정 1, 추론 2), 미연결 1. 엔드포인트 연결률 75%.',
    noteText: '외부 URL, 클라이언트 라우트는 서버 요청이 아니라서 분모에서 뺐다.' });
  assert.deepEqual(r.requestRows.map((x) => [x.label, x.count]), [
    ['서버 요청', 4], ['연결됨(확정)', 1], ['연결됨(추론)', 2], ['미연결', 1], ['외부 URL', 2], ['정적 페이지 링크', 0], ['화면 내 이동(클라이언트 라우트)', 1]
  ]);
  assert.deepEqual(r.unmatchedRequests.map((x) => x.location), ['a.jsp:4']);
  assert.ok(r.requestRows.every((x) => x.type === 'REQUEST_STAT' && x.description), '행마다 설명이 있다');
  const view = diag.VIEWS.find((v) => v.rows === 'requestRows');
  assert.ok(view && /화면 요청/.test(view.label));
  assert.equal(diag.viewCount(view, r), 7, '보기의 개수는 요청 수다');
  // 서버 요청이 없으면 연결률 문장이 없고, 요청이 없으면 행도 없다
  const routesOnly = slice();
  routesOnly.n('http:request:x', 'HTTP_REQUEST', 'x', 'x', { clientRoute: true, path: '/a' });
  assert.equal(diag.collect(graph.buildIndex(routesOnly.graph())).requestStats.summaryText, '서버 요청 0건 중 연결 0(확정 0, 추론 0), 미연결 0.');
  assert.deepEqual(diag.collect(graph.buildIndex(slice().graph())).requestRows, []);
});

test('F-016-AC70 방향 미상 관계의 까닭: 관계 상세(엣지 요약)와 범례에 선언된 FK와 반대 / 유일하지 않은 키 / 키 정보 없음으로 적는다', () => {
  const f = schema();
  const ix = graph.buildIndex(f.graph);
  assert.equal(edgeItem(ix, f.user, 'REFERENCES', f.article).summary, 'K = K · 방향 미상: 선언된 FK와 반대 · 추론 2건');
  assert.equal(edgeItem(ix, f.article, 'REFERENCES', f.ghost).summary, 'K = K · 방향 미상: 유일하지 않은 키 · 추론 2건');
  assert.equal(edgeItem(ix, f.ghost, 'REFERENCES', f.user).summary, 'K = K · 방향 미상: 키 정보 없음 · 추론 2건');
  assert.equal(graph.directionReason({ declaredOpposite: true }), '선언된 FK와 반대');
  const legend = erd.LEGEND.find((l) => l.key === 'undirected').description;
  assert.ok(legend.includes('선언된 FK와 반대') && legend.includes('유일하지 않은 키') && legend.includes('키 정보 없음'), legend);
  // ERD의 라벨은 표준 산출물과 같게 둔다(F-013-R18): 까닭이 없으면 "방향 미상"만
  const mermaid = erd.toMermaid(ix, erd.selectScope(ix, {}), {}).source;
  assert.ok(mermaid.includes('방향 미상: 선언된 FK와 반대)') && mermaid.includes('방향 미상: 유일하지 않은 키)') && mermaid.includes('추론 2건, 방향 미상)"'), mermaid);
  // DDL에 없는 컬럼은 ERD 본체에 넣지 않는다(F-013-R19)
  assert.ok(!mermaid.includes('SUB_DN') && !mermaid.includes('UPDATEDATE'), mermaid);
});
