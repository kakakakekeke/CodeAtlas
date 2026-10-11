'use strict';
// 3차 재검증(M14)에서 바뀐 표준 산출물의 콜트리·리포트 규칙(F-015-R55~R61)을 뷰어가 같게 따르는지 본다(F-016-R40).
// 기대값은 표준 산출물 테스트 Round5Test·RequestFlowTest(F-015-AC67~AC74)와 같은 모양의 그래프·같은 텍스트 트리다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { javaGraph } = require('./fixtures/java-graph.js');
const { slice, loc } = require('./fixtures/slice.js');
const tree = require('./tools/export-tree.js');

const graph = core('graph');
const calltree = core('calltree');
const diag = core('diagnostics');

const lines = (...l) => l.join('\n') + '\n';
const callees = (ix, id, opts, limits) => tree.text(ix, id, 'callees', Object.assign({ showAccessors: true, showExternal: true }, opts || {}), limits);
const callers = (ix, id, opts, limits) => tree.text(ix, id, 'callers', Object.assign({ showAccessors: true, showExternal: true }, opts || {}), limits);
const page = (j, path) => j.s.n('jsp:page:' + path, 'JSP_PAGE', path.substring(path.lastIndexOf('/') + 1), path, {}, { file: path, startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
function request(j, from, httpMethod, path, line, attrs, endpoint) {
  const file = from.substring(from.indexOf(':', from.indexOf(':') + 1) + 1);
  const id = j.s.n('http:request:' + httpMethod + ' ' + path + '@' + file + ':' + line, 'HTTP_REQUEST', httpMethod + ' ' + path, httpMethod + ' ' + path,
    Object.assign({ httpMethod, path }, attrs || {}), j.at(file, line));
  j.s.e(from, 'REQUESTS', id, {}, null, j.at(file, line));
  if (endpoint) j.s.e(id, 'MATCHES', endpoint);
  return id;
}
const span = (line, column, endColumn) => ({ file: 'src/main/java/com/n/OrderController.java', startLine: line, startColumn: column, endLine: line, endColumn });

test('F-016-AC72 콜트리: 한 줄의 중첩 호출 f(g(x))는 평가 순서대로 안쪽이 먼저 나온다 (F-015-AC68)', () => {
  const j = javaGraph();
  ['com.n.OrderController', 'com.n.Util'].forEach((t) => j.type(t));
  const caller = j.method('com.n.OrderController', 'save');
  const m = {};
  ['before', 'outer', 'inner', 'innermost', 'after'].forEach((n) => { m[n] = j.method('com.n.Util', n); });
  const call = (to, line, column, endColumn) => j.s.e(caller, 'CALLS', to, { callType: 'static' }, null, span(line, column, endColumn));
  call(m.outer, 10, 5, 40);
  call(m.after, 10, 45, 52);
  call(m.inner, 10, 11, 30);
  call(m.innermost, 10, 17, 29);
  call(m.before, 9, 5, 13);
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, caller), lines('OrderController#save()', '├─ Util#before()', '├─ Util#innermost()', '├─ Util#inner()', '├─ Util#outer()', '└─ Util#after()'));
});

test('F-016-AC72 콜트리: 후보 엔드포인트 여럿과 맞은 요청(MATCHES의 ambiguous=true)도 [ambiguous n] 한 줄로 묶는다 (F-015-AC69)', () => {
  const j = javaGraph();
  ['com.n.AController', 'com.n.BController'].forEach((t) => j.type(t));
  const p = page(j, 'src/main/webapp/list.jsp');
  const a = j.endpoint('GET', '/a/list', j.method('com.n.AController', 'list'));
  const b = j.endpoint('GET', '/b/list', j.method('com.n.BController', 'list'));
  const r = request(j, p, 'GET', '/list', 7);
  [a, b].forEach((e) => j.s.e(r, 'MATCHES', e, { ambiguous: true, relative: true }, 'INFERRED'));
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, p), lines(
    '[jsp] src/main/webapp/list.jsp',
    '└─ [request] GET /list',
    '   └─ ~ [ambiguous 2] GET /list',
    '      ├─ ~ [endpoint] GET /a/list',
    '      │  └─ AController#list()',
    '      └─ ~ [endpoint] GET /b/list',
    '         └─ BController#list()'));
  const root = calltree.createRoot(ix, r, 'callees');
  const group = calltree.expand(ix, root)[0];
  assert.equal(group.synthetic.type, 'ambiguous');
  assert.equal(group.synthetic.count, 2);
  assert.ok(calltree.toText(ix, root).includes('[ambiguous 2]'));
});

test('F-016-AC72 콜트리: 접근자 접기는 부수 효과 없는 생성자와 MyBatis Generator의 *Example·*Example$Criteria 빌더 호출도 접고, 접은 수를 따로 알린다 (F-015-AC70)', () => {
  const j = javaGraph();
  ['com.n.OrderController', 'com.n.Base', 'com.n.Vo', 'com.n.Loader', 'com.n.UserExample', 'com.n.UserExample$Criteria', 'com.n.OrderService'].forEach((t) => j.type(t));
  const init = (type) => j.method(type, '<init>', [], { attrs: { constructor: true } });
  const handler = j.method('com.n.OrderController', 'save');
  const baseInit = init('com.n.Base');
  const voInit = init('com.n.Vo');
  const loaderInit = init('com.n.Loader');
  const load = j.method('com.n.Loader', 'load');
  const exampleInit = init('com.n.UserExample');
  const createCriteria = j.method('com.n.UserExample', 'createCriteria');
  const criteriaInit = init('com.n.UserExample$Criteria');
  const andId = j.method('com.n.UserExample$Criteria', 'andIdEqualTo', ['java.lang.Long']);
  const addCriterion = j.method('com.n.UserExample$Criteria', 'addCriterion', ['java.lang.String'], { modifiers: ['protected'] });
  const run = j.method('com.n.OrderService', 'run');
  j.call(voInit, baseInit, 3, 'constructor');
  j.call(loaderInit, load, 4, 'virtual');
  j.call(createCriteria, criteriaInit, 5, 'constructor');
  j.call(andId, addCriterion, 6, 'virtual');
  j.call(handler, voInit, 10, 'constructor');
  j.call(handler, loaderInit, 11, 'constructor');
  j.call(handler, exampleInit, 12, 'constructor');
  j.call(handler, createCriteria, 13, 'virtual');
  j.call(handler, andId, 14, 'virtual');
  j.call(handler, run, 15, 'virtual');
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, handler, { showAccessors: false }), lines('OrderController#save()', '├─ Loader#<init>()', '│  └─ Loader#load()', '└─ OrderService#run()'));
  assert.ok(callees(ix, handler).includes('Vo#<init>()') && callees(ix, handler).includes('UserExample#createCriteria()'), '켜면 모두 보인다');
  assert.equal(calltree.isAccessor(ix, voInit), true);
  assert.equal(calltree.isAccessor(ix, baseInit), true);
  assert.equal(calltree.isAccessor(ix, loaderInit), false, '다른 메서드를 부르는 생성자는 남긴다');
  assert.equal(calltree.isAccessor(ix, andId), true);
  assert.equal(calltree.isBuilderCall(ix, andId), true);
  assert.equal(calltree.isBuilderCall(ix, voInit), false);
  assert.deepEqual(calltree.hiddenCounts(ix, handler, 'callees', {}), { accessors: 1, external: 0, dataLoad: 0, layoutUsers: 0, routes: 0, builders: 3 });
});

test('F-016-AC72 콜트리(위로): 여러 화면에 들어가는 공통 JSP(5개 이상이 include하거나 레이아웃)는 그 위로 펼치지 않고 [shared] 한 줄이다 (F-015-AC71)', () => {
  const j = javaGraph();
  j.type('com.n.LoginController');
  const endpoint = j.endpoint('GET', '/logout', j.method('com.n.LoginController', 'logout'));
  const menu = page(j, 'src/main/webapp/inc/menu.jsp');
  const small = page(j, 'src/main/webapp/inc/small.jsp');
  request(j, menu, 'GET', '/logout', 4, {}, endpoint);
  request(j, small, 'GET', '/logout', 4, {}, endpoint);
  for (let i = 1; i <= 5; i++) {
    const p = page(j, 'src/main/webapp/p' + i + '.jsp');
    j.s.e(p, 'INCLUDES', menu, { via: 'directive' });
    if (i <= 4) j.s.e(p, 'INCLUDES', small, { via: 'directive' });
  }
  const ix = graph.buildIndex(j.graph());
  const up = callers(ix, endpoint);
  assert.ok(up.includes(lines(
    '├─ [request] GET /logout',
    '│  └─ [shared] [jsp] src/main/webapp/inc/menu.jsp (포함하는 화면 5개)',
    '└─ [request] GET /logout',
    '   └─ [jsp] src/main/webapp/inc/small.jsp',
    '      ├─ [jsp] src/main/webapp/p1.jsp')), up);
  assert.ok(!up.includes('p5.jsp'), up);
  assert.ok(callers(ix, endpoint, { expandLayouts: true }).includes('p5.jsp'), '레이아웃 펼치기를 켜면 화면마다 올라간다');
  const fromMenu = callers(ix, menu);
  assert.ok(fromMenu.includes('[jsp] src/main/webapp/p5.jsp') && !fromMenu.includes('[shared]'), '공통 JSP에서 시작하면 그대로 펼친다: ' + fromMenu);
  // 화면 자료: 줄에 공통 화면 표시와 포함하는 화면 수가 실린다
  const root = calltree.createRoot(ix, endpoint, 'callers');
  const req = calltree.expand(ix, root).find((k) => calltree.expand(ix, k).some((c) => c.id === menu));
  const shared = calltree.expand(ix, req).find((c) => c.id === menu);
  assert.equal(shared.shared, 5);
  assert.equal(calltree.hasChildren(ix, shared), false);
  assert.ok(calltree.toText(ix, root).includes('[shared]'));
});

test('F-016-AC72 다른 방언에서 보충한 구문(borrowedFrom)은 콜트리 줄과 상세의 꼬리표·요약에 나오고 진단 보기에 목록이 있다 (F-015-AC72)', () => {
  const j = javaGraph();
  j.table('T_USER');
  j.type('com.n.UserMapper', { kind: 'interface' });
  const mapper = j.method('com.n.UserMapper', 'selectUser');
  const borrowed = j.statement('com.n.UserMapper', 'selectUser', 'SELECT', 'T_USER', { borrowedFrom: 'mysql', dialectVariant: 'oracle' });
  j.s.e(mapper, 'EXECUTES', borrowed);
  j.statement('com.n.UserMapper', 'countUser', 'SELECT', 'T_USER');
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, mapper), lines('UserMapper#selectUser()', '└─ [sql] UserMapper.selectUser <SELECT> (다른 방언 mysql에서 보충)', '   └─ [table] T_USER (R)'));
  assert.deepEqual(graph.nodeTags(ix.node(borrowed)).map((t) => t.text), ['방언 oracle', '다른 방언 mysql에서 보충']);
  assert.match(graph.nodeSummary(ix.node(borrowed)), /다른 방언 mysql의 매퍼 파일에서 보충/);
  const r = diag.collect(ix);
  assert.deepEqual(r.borrowedStatements.map((x) => [x.statementLabel, x.borrowedFrom]), [['com.n.UserMapper.selectUser', 'mysql']]);
  assert.ok(diag.VIEWS.some((v) => v.rows === 'borrowedStatements'));
});

test('F-016-AC72 진단: DDL에 없는 테이블에서 시스템 카탈로그(systemCatalog=true)는 뒤에 따로 묶고 의심으로 표시하지 않는다 (F-015-AC73)', () => {
  const s = slice();
  s.n('db:table:TB_GHOST', 'DB_TABLE', 'TB_GHOST', 'TB_GHOST', { declared: false });
  s.n('db:table:ALL_TAB_COLUMNS', 'DB_TABLE', 'ALL_TAB_COLUMNS', 'ALL_TAB_COLUMNS', { declared: false, systemCatalog: true });
  ['a', 'b'].forEach((n, i) => {
    const id = s.n('mybatis:stmt:com.n.M.' + n, 'SQL_STATEMENT', n, 'com.n.M.' + n, { statementType: 'SELECT', namespace: 'com.n.M', statementId: n });
    s.e(id, 'READS', i === 0 ? 'db:table:TB_GHOST' : 'db:table:ALL_TAB_COLUMNS');
  });
  const r = diag.collect(graph.buildIndex(s.graph()));
  assert.deepEqual(r.undeclaredTables.map((t) => [t.name, t.statementCount, t.note, t.suspicious]), [
    ['TB_GHOST', 1, '의심(구문 파싱 복구에서 나왔을 수 있음)', true], ['ALL_TAB_COLUMNS', 1, '시스템 카탈로그', false]
  ]);
  assert.equal(r.undeclaredSuspicious, 1);
  assert.equal(r.undeclaredCatalogs, 1);
});

test('F-016-AC72 진단: 화면 요청 통계는 외부 URL·정적 페이지 링크·클라이언트 라우트를 분모에서 빼고 연결을 확정·추론으로 나누며, 미연결 요청을 위치순으로 낸다 (F-015-AC67)', () => {
  const s = slice();
  const endpoint = s.n('http:endpoint:GET /api/auth/me', 'HTTP_ENDPOINT', 'GET /api/auth/me', 'GET /api/auth/me', { httpMethod: 'GET', path: '/api/auth/me' });
  s.n('jsp:page:src/main/webapp/static/docs/index.html', 'JSP_PAGE', 'index.html', 'src/main/webapp/static/docs/index.html', {});
  s.n('jsp:page:ui/mockup/index.html', 'JSP_PAGE', 'index.html', 'ui/mockup/index.html', {});
  const req = (httpMethod, path, file, line, attrs) => s.n('http:request:' + httpMethod + ' ' + path + '@' + file + ':' + line, 'HTTP_REQUEST', httpMethod + ' ' + path, httpMethod + ' ' + path,
    Object.assign({ httpMethod, path }, attrs || {}), loc(file, line));
  const jsp = 'src/main/webapp/login.jsp';
  s.e(req('GET', '/api/auth/me', jsp, 9), 'MATCHES', endpoint);
  s.e(req('GET', '/ctx/api/auth/me', jsp, 12), 'MATCHES', endpoint, { strippedPrefix: '/ctx' }, 'INFERRED');
  s.e(req('GET', '/api/auth/me', jsp, 15), 'MATCHES', endpoint, { crossModule: true });
  const gone = s.n('unresolved:http-endpoint:POST /api/auth/logout', 'UNRESOLVED', 'POST /api/auth/logout', 'POST /api/auth/logout', { category: 'http-endpoint' });
  s.e(req('POST', '/api/auth/logout', jsp, 18), 'MATCHES', gone, {}, 'UNRESOLVED');
  req('GET', 'https://kauth.example.com/oauth/authorize', jsp, 30, { external: true });
  req('GET', 'https://kauth.example.com/oauth/authorize', jsp, 31, { external: true });
  req('GET', '/docs/index.html', jsp, 40);
  req('GET', 'home/answer.html', 'ui/mockup/index.html', 7);
  req('GET', '/board/list.html', jsp, 44);
  const ix = graph.buildIndex(s.graph());
  const r = diag.collect(ix);
  assert.deepEqual(r.requestRows.map((x) => [x.label, x.count]), [
    ['서버 요청', 5], ['연결됨(확정)', 1], ['연결됨(추론)', 2], ['미연결', 2], ['외부 URL', 2], ['정적 페이지 링크', 2], ['화면 내 이동(클라이언트 라우트)', 0]
  ]);
  assert.equal(r.requestStats.summaryText, '서버 요청 5건 중 연결 3(확정 1, 추론 2), 미연결 2. 엔드포인트 연결률 60%.');
  assert.deepEqual(r.unmatchedRequests.map((x) => [x.location, x.label]), [
    ['src/main/webapp/login.jsp:18', 'POST /api/auth/logout'], ['src/main/webapp/login.jsp:44', 'GET /board/list.html']
  ]);
  assert.equal(r.unmatchedRequests[0].type, 'REQUEST_UNMATCHED');
});

test('F-016-AC72 상세: 현재 정의에 없는 upgrade 외래 키(fromUpgrade=true인 추론 REFERENCES)는 엣지 요약에 그렇게 적는다', () => {
  const s = slice();
  ['A', 'B'].forEach((t) => s.n('db:table:' + t, 'DB_TABLE', t, t, {}));
  // 실제 그래프의 모양(zheng): source는 DDL이고 엣지 신뢰도가 INFERRED이며 note가 붙는다
  ['C'].forEach((t) => s.n('db:table:' + t, 'DB_TABLE', t, t, {}));
  s.e('db:table:A', 'REFERENCES', 'db:table:B', { columns: ['B_ID'], constraint: 'FK_A_B', fromUpgrade: true, note: '현재 정의에 없음', onDelete: 'RESTRICT', refColumns: ['ID'], source: 'DDL' }, 'INFERRED');
  s.e('db:table:A', 'REFERENCES', 'db:table:C', { columns: ['C_ID'], refColumns: ['ID'], source: 'INFERRED', fromUpgrade: true }, 'INFERRED');
  const ix = graph.buildIndex(s.graph());
  const items = graph.nodeDetail(ix, 'db:table:A').outgoing.find((g) => g.kind === 'REFERENCES').items;
  assert.equal(items.find((i) => i.otherId === 'db:table:B').summary, 'B_ID → ID · FK_A_B (upgrade 스크립트, 현재 정의에 없음) · ON DELETE RESTRICT');
  assert.equal(items.find((i) => i.otherId === 'db:table:C').summary, 'C_ID → ID · (upgrade 스크립트, 현재 정의에 없음)');
});
