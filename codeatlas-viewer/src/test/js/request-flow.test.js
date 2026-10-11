'use strict';
// 3차 수정(2026-10-11)에서 바뀐 표준 산출물의 콜트리·CRUD 규칙(F-015-R41~R51)을 뷰어가 같게 따르는지 본다.
// 기대값은 표준 산출물 테스트(RequestFlowTest, AccessorFoldingTest, ReceiverNarrowingTest)의 텍스트 트리와 같은 모양이다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { javaGraph, genericDao } = require('./fixtures/java-graph.js');
const tree = require('./tools/export-tree.js');

const graph = core('graph');
const calltree = core('calltree');
const crud = core('crud');
const diag = core('diagnostics');

const lines = (...l) => l.join('\n') + '\n';
const callees = (ix, id, opts, limits) => tree.text(ix, id, 'callees', opts, limits);
const callers = (ix, id, opts, limits) => tree.text(ix, id, 'callers', opts, limits);
const C = 'com.j.RoleController';
const cellsOf = (ix, id) => {
  const m = crud.buildMatrix(ix, { rowKind: 'endpoint' });
  const row = m.rows.find((r) => r.id === id);
  return row ? Object.keys(row.cells).map((t) => graph.idRemainder(t) + '=' + row.cells[t]).sort() : [];
};

function roles() {
  const j = javaGraph();
  j.table('SYS_ROLE');
  j.type(C);
  const f = { j };
  f.save = j.method(C, 'save');
  f.form = j.method(C, 'form');
  f.get = j.method(C, 'get');
  const find = j.statement('com.j.RoleDao', 'get', 'SELECT', 'SYS_ROLE');
  j.s.e(f.get, 'EXECUTES', find);
  f.saveEndpoint = j.endpoint('POST', '/role/save', f.save);
  f.formEndpoint = j.endpoint('GET', '/role/form', f.form);
  j.call(f.save, f.form, 30, 'virtual');
  j.call(f.save, f.get, 90, 'modelAttribute', null, null, 'INFERRED');
  j.call(f.form, f.get, 90, 'modelAttribute', null, null, 'INFERRED');
  return f;
}

test('F-016-AC59 콜트리: [before](callType=modelAttribute)는 요청으로 진입한 핸들러 아래에만 붙고, 위로는 그 핸들러에서 엔드포인트로만 올라간다 (F-015-AC52)', () => {
  const f = roles();
  const helper = f.j.method('com.j.RoleHelper', 'load');
  f.j.call(helper, f.get, 5, 'virtual');
  const ix = graph.buildIndex(f.j.graph());
  assert.equal(callees(ix, f.saveEndpoint), lines(
    '[endpoint] POST /role/save',
    '└─ RoleController#save()',
    '   ├─ ~ [before] RoleController#get()',
    '   │  └─ [sql] RoleDao.get <SELECT>',
    '   │     └─ [table] SYS_ROLE (R)',
    '   └─ RoleController#form()'));
  assert.ok(callees(ix, f.form).startsWith('RoleController#form()\n└─ ~ [before] RoleController#get()\n'));
  assert.equal(callers(ix, f.get), lines(
    'RoleController#get()',
    '├─ ~ [before] RoleController#form()',
    '│  └─ [endpoint] GET /role/form',
    '├─ ~ [before] RoleController#save()',
    '│  └─ [endpoint] POST /role/save',
    '└─ RoleHelper#load()'));
  assert.deepEqual(cellsOf(ix, f.saveEndpoint), ['SYS_ROLE=R']);
});

function moves() {
  const j = javaGraph();
  ['TB_A', 'TB_B', 'TB_C'].forEach((t) => j.table(t));
  j.type(C);
  const f = { j };
  ['save', 'list', 'view', 'detail'].forEach((m) => { f[m] = j.method(C, m); });
  j.s.e(f.list, 'EXECUTES', j.statement('com.j.RoleDao', 'findList', 'SELECT', 'TB_A'), {}, null, j.at('C.java', 50));
  j.s.e(f.detail, 'EXECUTES', j.statement('com.j.RoleDao', 'get', 'SELECT', 'TB_B'), {}, null, j.at('C.java', 70));
  j.s.e(f.save, 'EXECUTES', j.statement('com.j.RoleDao', 'insert', 'INSERT', 'TB_C'), {}, null, j.at('C.java', 20));
  j.call(f.save, f.list, 25, 'redirect', null, null, 'INFERRED');
  j.call(f.view, f.detail, 60, 'forward', null, null, 'INFERRED');
  f.saveEndpoint = j.endpoint('POST', '/role/save', f.save);
  f.listEndpoint = j.endpoint('GET', '/role/list', f.list);
  f.viewEndpoint = j.endpoint('GET', '/role/view', f.view);
  return f;
}

test('F-016-AC60 콜트리·CRUD: redirect·forward로 넘어가는 핸들러는 기본으로 한 줄만 보이고, redirect 대상의 테이블은 엔드포인트의 칸에 넣지 않으며 forward 대상은 넣는다 (F-015-AC53)', () => {
  const f = moves();
  const ix = graph.buildIndex(f.j.graph());
  assert.equal(callees(ix, f.saveEndpoint), lines(
    '[endpoint] POST /role/save',
    '└─ RoleController#save()',
    '   ├─ [sql] RoleDao.insert <INSERT>',
    '   │  └─ [table] TB_C (C)',
    '   └─ ~ [redirect] RoleController#list()'));
  assert.equal(callees(ix, f.viewEndpoint), lines(
    '[endpoint] GET /role/view',
    '└─ RoleController#view()',
    '   └─ ~ [forward] RoleController#detail()'));
  assert.ok(callees(ix, f.saveEndpoint, {}, { expandScreens: true }).includes('[redirect] RoleController#list()\n      └─ [sql] RoleDao.findList <SELECT>'));
  assert.equal(callers(ix, f.list), lines('RoleController#list()', '├─ [endpoint] GET /role/list', '└─ ~ [redirect] RoleController#save()'));
  // "모두 펼치기"도 화면 이동에서 멈추고(멈춘 곳의 수를 알린다), 손으로는 펼칠 수 있다
  const root = calltree.createRoot(ix, f.saveEndpoint, 'callees');
  assert.equal(calltree.expandAll(ix, root, {}).screens, 1);
  const redirect = root.children[0].children[1];
  assert.deepEqual(calltree.markersOf(redirect.link), ['redirect']);
  assert.equal(redirect.children, null);
  assert.ok(calltree.expand(ix, redirect).length > 0);
  assert.deepEqual(cellsOf(ix, f.saveEndpoint), ['TB_C=C']);
  // forward는 같은 요청 안의 서버 내부 이동이다: 엣지가 추론이어도 그 길로 닿은 칸에 ?를 붙이지 않는다(F-016-AC66)
  assert.deepEqual(cellsOf(ix, f.viewEndpoint), ['TB_B=R']);
});

test('F-016-AC61 콜트리: 접근자로 접는 것은 Lombok 합성 멤버와, 같은 타입에 대응하는 필드가 있고 매개변수 수가 맞고 static이 아니며 호출이 나가지 않는 getXxx/isXxx/setXxx뿐이다 (F-015-AC54)', () => {
  const j = javaGraph();
  const VO = 'com.v.BoardVO';
  j.type(VO);
  ['title', 'open', 'isNotice', 'URL', 'item', 'instance'].forEach((n) => { j.s.n('java:field:' + VO + '#' + n, 'JAVA_FIELD', n, VO + '#' + n, {}); });
  const caller = j.method('com.v.BoardService', 'run');
  const folded = [j.method(VO, 'getTitle'), j.method(VO, 'setTitle', ['java.lang.String']), j.method(VO, 'isOpen'), j.method(VO, 'isNotice'), j.method(VO, 'getURL'),
    j.method(VO, 'withWriter', ['java.lang.String'], { attrs: { synthetic: true, lombok: 'With' } })];
  const kept = [j.method(VO, 'getNextStringId'), j.method(VO, 'isAuthenticated'), j.method(VO, 'setComplete'), j.method(VO, 'getItem', ['int']),
    j.method(VO, 'setItem', ['int', 'java.lang.String']), j.method(VO, 'getInstance', [], { modifiers: ['public', 'static'] }), j.method(VO, 'getter'),
    j.method('com.v.DynamicDataSource', 'setDataSource', ['java.lang.String'], { modifiers: ['public', 'static'] })];
  folded.concat(kept).forEach((m, i) => j.call(caller, m, 10 + i));
  const external = j.s.n('java:method:org.lib.Status#isCompleted()', 'JAVA_METHOD', 'isCompleted', 'org.lib.Status#isCompleted()', { declared: false });
  j.call(caller, external, 99);
  const ix = graph.buildIndex(j.graph());
  folded.forEach((m) => assert.equal(calltree.isAccessor(ix, m), true, m));
  kept.concat([external]).forEach((m) => assert.equal(calltree.isAccessor(ix, m), false, m));
  assert.equal(callees(ix, caller, { showExternal: true }), lines(
    'BoardService#run()',
    '├─ BoardVO#getNextStringId()',
    '├─ BoardVO#isAuthenticated()',
    '├─ BoardVO#setComplete()',
    '├─ BoardVO#getItem(int)',
    '├─ BoardVO#setItem(int, String)',
    '├─ BoardVO#getInstance()',
    '├─ BoardVO#getter()',
    '├─ DynamicDataSource#setDataSource(String)',
    '└─ Status#isCompleted()'));
});

test('F-016-AC62 콜트리(위로): 조건을 넘겨받은 제네릭 상위 클래스의 메서드라도 구체 하위 타입이 모두 다른 매퍼를 묶으면 호출자로 넣지 않는다 (F-015-AC55)', () => {
  const f = genericDao();
  const j = f.j;
  const P = f.P;
  j.type(P + 'TreeService', { modifiers: ['public', 'abstract'] });
  j.typeEdge(P + 'TreeService', 'EXTENDS', P + 'CrudService', { typeArguments: ['D', 'T'] });
  const move = j.method(P + 'TreeService', 'move', ['java.lang.Object']);
  j.call(move, f.serviceFindPage, 34, 'super');
  j.type(P + 'LogService');
  j.typeEdge(P + 'LogService', 'EXTENDS', P + 'TreeService', { typeArguments: [P + 'LogDao', P + 'Log'] });
  const handler = j.method(P + 'LogController', 'move');
  j.call(handler, move, 60, 'virtual', P + 'LogService');
  j.endpoint('POST', '/log/move', handler);
  const ix = graph.buildIndex(j.graph());
  assert.ok(!callers(ix, f.dictFindList).includes('TreeService#move'));
  assert.ok(callers(ix, f.logFindList).includes(lines(
    '   ├─ CrudService#findPage(Object)',
    '   │  └─ TreeService#move(Object) (super)',
    '   │     └─ LogController#move()',
    '   │        └─ [endpoint] POST /log/move')));
});

test('F-016-AC63 콜트리: 뷰 컨트롤러 엔드포인트는 [jsp]로 바로 이어지고(여러 모듈이면 요청과 같은 모듈의 화면만), 클라이언트 라우트는 [route] 잎이다 (F-015-AC57, AC58)', () => {
  const j = javaGraph();
  const root = j.endpoint('GET', '/', null, { viewController: true });
  const page = (path) => j.s.n('jsp:page:' + path, 'JSP_PAGE', path, path, {}, { file: path, startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  const admin = page('web-admin/src/main/webapp/WEB-INF/jsp/index.jsp');
  const front = page('web-front/src/main/webapp/WEB-INF/jsp/index.jsp');
  const menu = page('web-admin/src/main/webapp/WEB-INF/jsp/menu.jsp');
  j.s.e(root, 'RENDERS', admin);
  j.s.e(root, 'RENDERS', front);
  const request = (id, path, from, line, attrs) => {
    const r = j.s.n('http:request:' + id, 'HTTP_REQUEST', 'GET ' + path, 'GET ' + path, Object.assign({ httpMethod: 'GET', path }, attrs || {}), j.at(from.substring(9), line));
    j.s.e(from, 'REQUESTS', r, {}, null, j.at(from.substring(9), line));
    return r;
  };
  j.s.e(request('GET /@menu:4', '/', menu, 4), 'MATCHES', root);
  const route = request('GET /login@menu:9', '/login', menu, 9, { clientRoute: true, api: 'route' });
  j.s.n('unresolved:http-endpoint:GET /login', 'UNRESOLVED', 'GET /login', 'GET /login', { category: 'http-endpoint' });
  j.s.e(route, 'MATCHES', 'unresolved:http-endpoint:GET /login', {}, 'UNRESOLVED');
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, root), lines(
    '[endpoint] GET /',
    '├─ [jsp] web-admin/src/main/webapp/WEB-INF/jsp/index.jsp (모듈 web-admin)',
    '└─ [jsp] web-front/src/main/webapp/WEB-INF/jsp/index.jsp (모듈 web-front)'));
  assert.equal(callees(ix, menu), lines(
    '[jsp] web-admin/src/main/webapp/WEB-INF/jsp/menu.jsp',
    '├─ [request] GET /',
    '│  └─ [endpoint] GET /',
    '│     └─ [jsp] web-admin/src/main/webapp/WEB-INF/jsp/index.jsp',
    '└─ [route] /login'));
  // 진단 보기: 클라이언트 라우트는 미해결로 세지 않고 요청 통계에서 따로 센다(report.md의 "화면 요청")
  const r = diag.collect(ix);
  assert.deepEqual(r.categories.map((c) => c.category), []);
  assert.deepEqual([r.requestStats.total, r.requestStats.server, r.requestStats.matched, r.requestStats.unmatched, r.requestStats.external, r.requestStats.clientRoutes], [2, 1, 1, 0, 0, 1]);
});

test('F-016-AC64 콜트리: 레이아웃(sitemesh 데코레이터, tiles template·조각)은 [layout] 한 줄만 보이고, 위로는 "쓰는 화면 n개" 한 줄로 접는다 (F-015-AC59)', () => {
  const j = javaGraph();
  const page = (path) => j.s.n('jsp:page:' + path, 'JSP_PAGE', path, path, {}, { file: path, startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  const decorator = page('WEB-INF/decorators/default.jsp');
  const template = page('WEB-INF/tiles/layout.jsp');
  const pages = ['a', 'b', 'c'].map((n) => page('WEB-INF/views/' + n + '.jsp'));
  pages.forEach((p) => j.s.e(p, 'INCLUDES', decorator, { via: 'sitemesh' }, 'INFERRED', j.at('WEB-INF/decorators.xml', 5)));
  const handler = j.method('com.l.BoardController', 'list');
  const endpoint = j.endpoint('GET', '/board/list', handler);
  const logoutHandler = j.method('com.l.LoginController', 'logout');
  const logout = j.endpoint('GET', '/logout', logoutHandler);
  const request = j.s.n('http:request:GET /logout@d:7', 'HTTP_REQUEST', 'GET /logout', 'GET /logout', { httpMethod: 'GET', path: '/logout' }, j.at('WEB-INF/decorators/default.jsp', 7));
  j.s.e(decorator, 'REQUESTS', request, {}, null, j.at('WEB-INF/decorators/default.jsp', 7));
  j.s.e(request, 'MATCHES', logout);
  j.s.e(handler, 'RENDERS', template, { via: 'tiles', definition: 'board.list' }, null, j.at('B.java', 30));
  j.s.e(handler, 'RENDERS', pages[0], { via: 'tiles', definition: 'board.list', tilesAttribute: true }, null, j.at('B.java', 30));
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, pages[0]), lines('[jsp] WEB-INF/views/a.jsp', '└─ ~ [layout] [jsp] WEB-INF/decorators/default.jsp'));
  assert.ok(callees(ix, pages[0], {}, { expandLayouts: true }).includes('[layout] [jsp] WEB-INF/decorators/default.jsp\n   └─ [request] GET /logout'));
  assert.equal(callees(ix, endpoint, {}, { expandScreens: true }), lines(
    '[endpoint] GET /board/list',
    '└─ BoardController#list()',
    '   ├─ [layout] [jsp] WEB-INF/tiles/layout.jsp',
    '   └─ [jsp] WEB-INF/views/a.jsp',
    '      └─ ~ [layout] [jsp] WEB-INF/decorators/default.jsp'));
  // 위로 올라가다 만난 데코레이터는 [shared] 한 줄이고(F-015-AC71), 데코레이터에서 시작하면 "쓰는 화면 n개" 한 줄이다
  assert.ok(callers(ix, logout).includes(lines(
    '└─ [request] GET /logout',
    '   └─ [shared] [jsp] WEB-INF/decorators/default.jsp (포함하는 화면 3개)')), callers(ix, logout));
  assert.equal(callers(ix, decorator), lines('[jsp] WEB-INF/decorators/default.jsp', '└─ [layout] WEB-INF/decorators/default.jsp를 쓰는 화면 3개'));
  assert.ok(callers(ix, logout, { expandLayouts: true }).includes('~ [layout] [jsp] WEB-INF/views/b.jsp'));
});

test('F-016-AC65 콜트리: 한 호출 지점의 오버로드 후보(ambiguous=true)는 [ambiguous n] 한 줄로 묶고, 로드를 확인하지 못한 JS 호출(loadUnverified)은 추론과 (로드 미확인)으로 보인다 (F-015-AC60, AC61)', () => {
  const j = javaGraph();
  const caller = j.method('com.m.LoginService', 'login');
  const one = j.method('com.m.FileScrty', 'encrypt', ['java.lang.String'], { modifiers: ['public', 'static'] });
  const two = j.method('com.m.FileScrty', 'encrypt', ['java.lang.String', 'byte[]'], { modifiers: ['public', 'static'] });
  const other = j.method('com.m.FileScrty', 'decode', ['java.lang.String'], { modifiers: ['public', 'static'] });
  const digest = j.method('com.m.Digests', 'sha');
  j.call(two, digest, 12);
  j.s.e(caller, 'CALLS', one, { callType: 'static', ambiguous: true }, 'INFERRED', j.at('L.java', 90, 20));
  j.s.e(caller, 'CALLS', two, { callType: 'static', ambiguous: true }, 'INFERRED', j.at('L.java', 90, 20));
  j.s.e(caller, 'CALLS', other, { callType: 'static', ambiguous: true }, 'INFERRED', j.at('L.java', 95));
  j.call(caller, digest, 80);
  const fn = (id, name) => j.s.n('js:function:' + id, 'JS_FUNCTION', name, id, {}, j.at(id.split('#')[0], 3));
  const init = fn('js/page.js#init', 'init');
  const search = fn('js/common.js#fnSearch', 'fnSearch');
  j.s.e(init, 'CALLS', search, { callType: 'call', loadUnverified: true }, null, j.at('js/page.js', 5));
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, caller), lines(
    'LoginService#login()',
    '├─ Digests#sha()',
    '├─ ~ [ambiguous 2] FileScrty#encrypt',
    '│  ├─ ~ FileScrty#encrypt(String)',
    '│  └─ ~ FileScrty#encrypt(String, byte[])',
    '│     └─ Digests#sha()',
    '└─ ~ FileScrty#decode(String)'));
  const root = calltree.createRoot(ix, caller, 'callees');
  const group = calltree.expand(ix, root)[1];
  assert.deepEqual(group.synthetic, { type: 'ambiguous', count: 2, text: 'FileScrty#encrypt' });
  assert.ok(calltree.toText(ix, root).includes('[ambiguous 2] FileScrty#encrypt'));
  assert.equal(callees(ix, init), lines('[js] page.js#init', '└─ ~ [js] common.js#fnSearch (로드 미확인)'));
});
