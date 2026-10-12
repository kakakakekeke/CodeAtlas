'use strict';
// 화면 조각 테스트(4차 수정, 2026-10-11): 3차에서 조각 테스트 없이 넣은 화면 요소(콜트리의 레이아웃 펼치기와 [layout] 줄, 클라이언트 라우트 숨기기,
// [ambiguous n] 묶음 줄의 펼침, [redirect]·[forward] 한 줄과 "화면 이동 펼치기")와 이번에 더한 것(진단의 "컬럼 불일치"·"화면 요청" 보기,
// CRUD의 ? 칸, 테이블 상세의 DDL에 없는 컬럼, ERD 범례의 방향 미상 까닭)을 가짜 DOM에서 본다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assemble } = require('./tools/assemble.js');
const { boot, sleep } = require('./tools/boot.js');
const { javaGraph } = require('./fixtures/java-graph.js');
const { slice, loc } = require('./fixtures/slice.js');

const texts = (els) => els.map((e) => e.textContent);
const open = (graph) => boot(assemble(JSON.stringify(graph), ''));
function toggle(doc, selector, on) {
  const input = doc.querySelector(selector);
  assert.ok(input, selector);
  input.checked = on;
  input.fire('change');
}
const rowsOf = (doc, direction) => doc.querySelectorAll('.view-calltree .tree-' + direction + ' .trow');
const treeText = (doc, direction) => texts(rowsOf(doc, direction)).join('\n');
/** 화면 오류가 없고, 없는 자식을 그대로 붙여 생긴 "null"·"undefined" 글자도 없다. */
function assertClean(window) {
  assert.deepEqual(window.errors, []);
  const text = window.document.getElementById('app').textContent;
  assert.ok(!/(^|[^A-Za-z])(null|undefined)([^A-Za-z]|$)/.test(text), '화면에 null/undefined 글자: ' + (/.{0,40}(null|undefined).{0,40}/.exec(text) || [''])[0]);
}
const page = (j, path) => j.s.n('jsp:page:' + path, 'JSP_PAGE', path.substring(path.lastIndexOf('/') + 1), path, {}, { file: path, startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
function request(j, from, httpMethod, path, line, attrs, endpoint) {
  const file = from.substring(from.indexOf(':', from.indexOf(':') + 1) + 1).replace(/#.*$/, '');
  const id = j.s.n('http:request:' + httpMethod + ' ' + path + '@' + file + ':' + line, 'HTTP_REQUEST', httpMethod + ' ' + path, httpMethod + ' ' + path,
    Object.assign({ httpMethod, path }, attrs || {}), j.at(file, line));
  j.s.e(from, 'REQUESTS', id, {}, null, j.at(file, line));
  if (endpoint) j.s.e(id, 'MATCHES', endpoint);
  return id;
}

/** 내용 페이지 둘 → sitemesh 데코레이터(로그아웃 요청이 있다), tiles template을 그리는 핸들러. */
function layouts() {
  const j = javaGraph();
  const f = { j };
  f.decorator = page(j, 'WEB-INF/decorators/default.jsp');
  f.pages = ['a', 'b'].map((n) => page(j, 'WEB-INF/views/' + n + '.jsp'));
  f.pages.forEach((p) => j.s.e(p, 'INCLUDES', f.decorator, { via: 'sitemesh' }, 'INFERRED', j.at('WEB-INF/decorators.xml', 5)));
  const logoutHandler = j.method('com.l.LoginController', 'logout');
  f.logout = j.endpoint('GET', '/logout', logoutHandler);
  request(j, f.decorator, 'GET', '/logout', 7, {}, f.logout);
  return f;
}

test('F-016-AC69 화면: 콜트리의 레이아웃 줄은 "레이아웃" 꼬리표가 붙은 한 줄이고, "레이아웃 펼치기"를 켜야 모두 펼치기가 그 너머로 간다. 위로는 "쓰는 화면 n개" 한 줄이다', async () => {
  const f = layouts();
  const window = await open(f.j.graph());
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('calltree', { id: f.pages[0] });
  await sleep(20);
  const check = doc.querySelector('.view-calltree .expand-layouts input');
  assert.ok(check, '"레이아웃 펼치기" 토글');
  assert.equal(check.checked, false, '기본은 펼치지 않는다(표준 산출물과 같다)');
  let layoutRow = rowsOf(doc, 'callees').find((r) => r.textContent.includes('default.jsp'));
  assert.ok(layoutRow, treeText(doc, 'callees'));
  assert.equal(layoutRow.querySelector('.rel').textContent, 'INCLUDES(layout)');
  assert.ok(layoutRow.querySelector('.tag.layout'), '레이아웃 꼬리표');
  assert.match(layoutRow.querySelector('.tag.layout').getAttribute('title'), /\[layout\]/);
  // 모두 펼치기는 레이아웃에서 멈춘다: 데코레이터의 요청(GET /logout)이 딸려 오지 않는다
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  assert.ok(!treeText(doc, 'callees').includes('/logout'), treeText(doc, 'callees'));
  // 손으로는 펼칠 수 있다
  layoutRow = rowsOf(doc, 'callees').find((r) => r.textContent.includes('default.jsp'));
  layoutRow.querySelector('.twist').click();
  await sleep(20);
  assert.ok(treeText(doc, 'callees').includes('/logout'));
  // 켜고 모두 펼치면 그 너머까지 간다
  toggle(doc, '.view-calltree .expand-layouts input', true);
  await sleep(20);
  assert.ok(!treeText(doc, 'callees').includes('/logout'), '토글하면 트리를 다시 만든다');
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  assert.ok(treeText(doc, 'callees').includes('/logout') && treeText(doc, 'callees').includes('LoginController#logout'), treeText(doc, 'callees'));

  // 위로: 데코레이터를 쓰는 화면은 줄줄이 내지 않고 한 줄로 접는다(그래프의 노드가 아닌 줄이라 눌러도 상세로 가지 않는다)
  toggle(doc, '.view-calltree .expand-layouts input', false);
  app.navigate('calltree', { id: f.decorator });
  await sleep(20);
  const users = rowsOf(doc, 'callers').find((r) => r.classList.contains('synthetic'));
  assert.ok(users, treeText(doc, 'callers'));
  assert.ok(users.textContent.includes('쓰는 화면 2개'), users.textContent);
  assert.ok(users.querySelector('.tag.layout'));
  assert.ok(!treeText(doc, 'callers').includes('a.jsp'));
  toggle(doc, '.view-calltree .expand-layouts input', true);
  await sleep(20);
  assert.ok(treeText(doc, 'callers').includes('a.jsp') && treeText(doc, 'callers').includes('b.jsp'), treeText(doc, 'callers'));
  assert.equal(rowsOf(doc, 'callers').find((r) => r.classList.contains('synthetic')), undefined);
  assertClean(window);
});

/** React 컴포넌트: 서버 요청 하나와 화면 내 이동(클라이언트 라우트) 셋. */
function routes() {
  const j = javaGraph();
  const f = { j };
  const handler = j.method('com.v.AuthController', 'me');
  f.endpoint = j.endpoint('GET', '/api/auth/me', handler);
  j.s.n('js:module:src/components/Header.jsx', 'JS_MODULE', 'Header.jsx', 'src/components/Header.jsx', { moduleKind: 'esm' }, j.at('src/components/Header.jsx', 1));
  f.component = j.s.n('js:function:src/components/Header.jsx#Header', 'JS_FUNCTION', 'Header', 'src/components/Header.jsx#Header', { kind: 'function', component: true }, j.at('src/components/Header.jsx', 5));
  j.s.e('js:module:src/components/Header.jsx', 'CONTAINS', f.component);
  f.server = request(j, f.component, 'GET', '/api/auth/me', 9, { api: 'fetch' }, f.endpoint);
  f.routes = ['/', '/about', '/login'].map((path, i) => request(j, f.component, 'GET', path, 20 + i, { api: 'route', clientRoute: true }));
  return f;
}

test('F-016-AC69 화면: 화면 내 이동(클라이언트 라우트)은 탐색과 콜트리에서 기본으로 숨기고(숨긴 수를 알린다) 토글로 보인다. 그런 요청이 없는 그래프에는 토글이 없다', async () => {
  const f = routes();
  const window = await open(f.j.graph());
  const doc = window.document;
  const app = window.CodeAtlas.app;
  // 탐색: 기본으로 숨긴다
  const exploreCheck = doc.querySelector('.view-explore .hide-client-routes input');
  assert.ok(exploreCheck && exploreCheck.checked, '탐색의 토글은 그런 요청이 있으면 켜져 있다');
  assert.ok(doc.querySelector('.view-explore .hide-client-routes').textContent.includes('(3)'), doc.querySelector('.view-explore .hide-client-routes').textContent);
  const names = () => texts(doc.querySelectorAll('.view-explore .results .vrow .name'));
  assert.ok(names().includes('GET /api/auth/me') && !names().some((n) => n === 'GET /login' || n === '/login'), names().join(' | '));
  toggle(doc, '.view-explore .hide-client-routes input', false);
  await sleep(20);
  assert.ok(names().some((n) => n.includes('/login')), names().join(' | '));

  // 콜트리: 컴포넌트 아래의 [route] 줄을 기본으로 접는다(React 화면에서는 이 줄이 트리 대부분을 차지한다)
  app.navigate('calltree', { id: f.component });
  await sleep(20);
  const check = doc.querySelector('.view-calltree .hide-client-routes input');
  assert.ok(check, '콜트리의 "화면 내 이동 숨기기" 토글');
  assert.equal(check.checked, true, '그런 요청이 있는 그래프에서는 기본으로 숨긴다');
  assert.notEqual(doc.querySelector('.view-calltree .hide-client-routes').style.display, 'none');
  let tree = treeText(doc, 'callees');
  assert.ok(tree.includes('/api/auth/me') && !tree.includes('/login') && !tree.includes('/about'), tree);
  const folded = rowsOf(doc, 'callees')[0].querySelector('.tag.folded');
  assert.ok(folded && folded.textContent.includes('3'), '기준 노드 줄에 접은 줄 수: ' + rowsOf(doc, 'callees')[0].textContent);
  assert.ok(folded.getAttribute('title').includes('화면 내 이동 3'), folded.getAttribute('title'));
  toggle(doc, '.view-calltree .hide-client-routes input', false);
  await sleep(20);
  tree = treeText(doc, 'callees');
  assert.ok(tree.includes('/login') && tree.includes('/about'), tree);
  const routeRow = rowsOf(doc, 'callees').find((r) => r.textContent.includes('/login'));
  assert.ok(routeRow.querySelector('.tag.route'), '"화면 내 이동" 꼬리표');
  assert.match(routeRow.querySelector('.tag.route').getAttribute('title'), /clientRoute=true/);
  assert.equal(routeRow.querySelector('.twist').classList.contains('leaf'), true, '클라이언트 라우트는 엔드포인트로 이어지지 않는 잎이다');
  assert.equal(rowsOf(doc, 'callees')[0].querySelector('.tag.folded'), null);
  assertClean(window);

  // 클라이언트 라우트가 없는 그래프: 토글을 보이지 않는다
  const plain = await open(layouts().j.graph());
  assert.equal(plain.document.querySelector('.view-calltree .hide-client-routes') == null
    || plain.document.querySelector('.view-calltree .hide-client-routes').style.display === 'none', true);
  plain.CodeAtlas.app.navigate('calltree', { id: 'jsp:page:WEB-INF/views/a.jsp' });
  await sleep(20);
  assert.equal(plain.document.querySelector('.view-calltree .hide-client-routes').style.display, 'none');
  assertClean(plain);
});

test('F-016-AC69 화면: 오버로드 후보 묶음 줄([ambiguous n])은 "후보 n개" 꼬리표가 붙은 한 줄이고, 펼치면 후보가 나온다. 묶음 줄은 눌러도 상세로 가지 않는다', async () => {
  const j = javaGraph();
  ['com.m.LoginService', 'com.m.FileScrty', 'com.m.Digests'].forEach((t) => j.type(t));
  const caller = j.method('com.m.LoginService', 'login');
  const one = j.method('com.m.FileScrty', 'encrypt', ['java.lang.String'], { modifiers: ['public', 'static'] });
  const two = j.method('com.m.FileScrty', 'encrypt', ['java.lang.String', 'byte[]'], { modifiers: ['public', 'static'] });
  const digest = j.method('com.m.Digests', 'sha');
  j.call(two, digest, 12);
  j.s.e(caller, 'CALLS', one, { callType: 'static', ambiguous: true }, 'INFERRED', j.at('L.java', 90, 20));
  j.s.e(caller, 'CALLS', two, { callType: 'static', ambiguous: true }, 'INFERRED', j.at('L.java', 90, 20));
  j.call(caller, digest, 80);
  const window = await open(j.graph());
  const doc = window.document;
  window.CodeAtlas.app.navigate('calltree', { id: caller });
  await sleep(20);
  let rows = rowsOf(doc, 'callees');
  assert.equal(rows.length, 3, treeText(doc, 'callees'));
  const group = rows.find((r) => r.classList.contains('synthetic'));
  assert.ok(group, treeText(doc, 'callees'));
  assert.equal(group.querySelector('.tag.ambiguous').textContent, '후보 2개');
  assert.match(group.querySelector('.tag.ambiguous').getAttribute('title'), /\[ambiguous n\]/);
  assert.equal(group.querySelector('.name').textContent, 'FileScrty#encrypt');
  assert.ok(group.querySelector('.conf.INFERRED'), '묶음 줄은 추론이다');
  assert.ok(!treeText(doc, 'callees').includes('encrypt(java.lang.String'), '후보는 펼치기 전에는 보이지 않는다');
  // 묶음 줄은 그래프의 노드가 아니다: 이름을 눌러도 상세가 열리지 않는다
  group.querySelector('.name').click();
  await sleep(20);
  assert.equal(doc.querySelector('.drawer.open'), null);
  group.querySelector('.twist').click();
  await sleep(20);
  rows = rowsOf(doc, 'callees');
  assert.equal(rows.length, 5, treeText(doc, 'callees'));
  const candidates = texts(rows).filter((t) => t.includes('encrypt(java.lang.String'));
  assert.equal(candidates.length, 2, treeText(doc, 'callees'));
  assert.ok(rows.filter((r) => r.textContent.includes('encrypt(java.lang.String')).every((r) => r.querySelector('.conf.INFERRED')), '후보 줄도 추론이다');
  // 접으면 다시 한 줄이다
  rowsOf(doc, 'callees').find((r) => r.classList.contains('synthetic')).querySelector('.twist').click();
  await sleep(20);
  assert.equal(rowsOf(doc, 'callees').length, 3);
  // 모두 펼치기는 후보 아래까지 간다
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  assert.equal(rowsOf(doc, 'callees').length, 6, treeText(doc, 'callees'));
  assertClean(window);
});

test('F-016-AC69 화면: redirect·forward로 넘어가는 핸들러는 꼬리표가 붙은 한 줄이고, 모두 펼치기는 거기서 멈춘다. "화면 이동 펼치기"를 켜면 넘어간 핸들러의 아래까지 펼친다', async () => {
  const j = javaGraph();
  ['TB_A', 'TB_B'].forEach((t) => j.table(t));
  const C = 'com.j.RoleController';
  j.type(C);
  const save = j.method(C, 'save');
  const list = j.method(C, 'list');
  const view = j.method(C, 'view');
  const detail = j.method(C, 'detail');
  const saveEndpoint = j.endpoint('POST', '/role/save', save);
  j.endpoint('GET', '/role/list', list);
  const viewEndpoint = j.endpoint('GET', '/role/view', view);
  j.endpoint('GET', '/role/detail', detail);
  j.s.e(list, 'EXECUTES', j.statement('com.j.RoleDao', 'findList', 'SELECT', 'TB_A'));
  j.s.e(detail, 'EXECUTES', j.statement('com.j.RoleDao', 'get', 'SELECT', 'TB_B'));
  j.call(save, list, 40, 'redirect', null, { path: '/role/list', viewName: 'redirect:/role/list' }, 'INFERRED');
  j.call(view, detail, 50, 'forward', null, { path: '/role/detail', viewName: 'forward:/role/detail' }, 'INFERRED');
  const window = await open(j.graph());
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('calltree', { id: saveEndpoint });
  await sleep(20);
  assert.equal(doc.querySelector('.view-calltree .expand-screens input').checked, false, '"화면 이동 펼치기"의 기본은 꺼짐');
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  let row = rowsOf(doc, 'callees').find((r) => r.textContent.includes('RoleController#list'));
  assert.ok(row, treeText(doc, 'callees'));
  assert.equal(row.querySelector('.tag.screen-move').textContent, '리다이렉트');
  assert.match(row.querySelector('.tag.screen-move').getAttribute('title'), /\[redirect\]/);
  assert.equal(row.querySelector('.rel').textContent, 'CALLS(redirect)');
  assert.ok(row.querySelector('.conf.INFERRED'));
  assert.ok(!treeText(doc, 'callees').includes('RoleDao.findList') && !treeText(doc, 'callees').includes('TB_A'), '넘어간 핸들러의 아래는 펼치지 않는다');
  const banner = doc.querySelector('.view-calltree .tree-callees .banner');
  assert.ok(banner.textContent.includes('1곳에서 멈췄다') && banner.textContent.includes('화면 이동 펼치기'), banner.textContent);
  // 켜고 모두 펼치면 끝까지 간다
  toggle(doc, '.view-calltree .expand-screens input', true);
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  assert.ok(treeText(doc, 'callees').includes('findList') && treeText(doc, 'callees').includes('TB_A'), treeText(doc, 'callees'));
  assert.equal(doc.querySelector('.view-calltree .tree-callees .banner').style.display, 'none');

  // forward: 꼬리표만 다르다. 넘어오는 쪽(위)도 한 줄이다
  toggle(doc, '.view-calltree .expand-screens input', false);
  app.navigate('calltree', { id: viewEndpoint });
  await sleep(20);
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  row = rowsOf(doc, 'callees').find((r) => r.textContent.includes('RoleController#detail'));
  assert.equal(row.querySelector('.tag.screen-move').textContent, '포워드');
  assert.ok(!treeText(doc, 'callees').includes('TB_B'));
  app.navigate('calltree', { id: detail });
  await sleep(20);
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  const from = rowsOf(doc, 'callers').find((r) => r.textContent.includes('RoleController#view'));
  assert.ok(from && from.querySelector('.tag.screen-move'), treeText(doc, 'callers'));
  assert.ok(!treeText(doc, 'callers').includes('/role/view'), '넘어오는 쪽 핸들러 위의 엔드포인트는 다른 화면 이동이다: ' + treeText(doc, 'callers'));
  assertClean(window);
});

/** 정의가 있는 테이블의 declared=false 컬럼, 방향 미상 추론 관계, 요청 여러 종류. */
function mismatches() {
  const s = slice();
  const f = {};
  const table = (name, attrs) => s.n('db:table:' + name, 'DB_TABLE', name, name, attrs || {}, loc('db/ddl.sql', 1));
  const column = (t, name, attrs) => { const id = s.n('db:column:' + t + '.' + name, 'DB_COLUMN', name, t + '.' + name, Object.assign({ dataType: 'VARCHAR2(20)' }, attrs || {})); s.e('db:table:' + t, 'CONTAINS', id); return id; };
  f.user = table('T_USER');
  column('T_USER', 'USER_ID', { primaryKey: true, nullable: false, ordinal: 1 });
  f.dept = table('T_DEPT');
  column('T_DEPT', 'DEPT_ID', { primaryKey: true, nullable: false, ordinal: 1 });
  f.subDn = column('T_USER', 'SUB_DN', { declared: false });
  f.flag = column('T_DEPT', 'USE_FLAG', { declared: false });
  const stmt = (name) => s.n('mybatis:stmt:com.u.' + name, 'SQL_STATEMENT', name.substring(name.lastIndexOf('.') + 1), 'com.u.' + name,
    { statementType: 'SELECT', namespace: 'com.u.' + name.substring(0, name.lastIndexOf('.')), statementId: name.substring(name.lastIndexOf('.') + 1) }, loc('mapper/' + name + '.xml', 5));
  f.selectUser = stmt('UserMapper.selectUser');
  f.selectDept = stmt('DeptMapper.selectDept');
  s.e(f.selectUser, 'READS', f.user);
  s.e(f.selectUser, 'READS', f.subDn);
  s.e(f.selectDept, 'READS', f.dept);
  s.e(f.selectDept, 'READS', f.flag);
  s.e(f.selectUser, 'READS', f.flag);
  s.e(f.user, 'REFERENCES', f.dept, { columns: ['DEPT_ID'], refColumns: ['DEPT_ID'], source: 'INFERRED', evidenceCount: 2, directionKnown: false, cardinality: 'unknown', pseudoKey: true }, 'INFERRED');
  const endpoint = s.n('http:endpoint:GET /api/users', 'HTTP_ENDPOINT', 'GET /api/users', 'GET /api/users', { httpMethod: 'GET', path: '/api/users' });
  let n = 0;
  const req = (attrs) => s.n('http:request:r' + (++n), 'HTTP_REQUEST', 'r' + n, 'r' + n, Object.assign({ httpMethod: 'GET', path: '/x' + n }, attrs || {}), loc('a.jsp', n));
  s.e(req(), 'MATCHES', endpoint);
  s.e(req(), 'MATCHES', endpoint, { strippedPrefix: '/ctx' }, 'INFERRED');
  const gone = s.n('unresolved:http-endpoint:POST /api/logout', 'UNRESOLVED', 'POST /api/logout', 'POST /api/logout', { category: 'http-endpoint' });
  s.e(req(), 'MATCHES', gone, {}, 'UNRESOLVED');
  req({ external: true, path: 'https://kauth.example.com/oauth/authorize' });
  req({ clientRoute: true, api: 'route', path: '/login' });
  f.graph = s.graph();
  return f;
}

test('F-016-AC68 화면: 진단·미해결에 "컬럼 불일치"(테이블, 컬럼, 참조하는 구문)와 "화면 요청"(구분별 건수와 연결률) 보기가 있다', async () => {
  const f = mismatches();
  const window = await open(f.graph);
  const doc = window.document;
  window.CodeAtlas.app.navigate('diagnostics', {});
  await sleep(50);
  const modes = doc.querySelectorAll('.view-diagnostics .diag-mode button');
  const labels = texts(modes);
  assert.ok(labels.includes('컬럼 불일치 2') && labels.includes('화면 요청 5'), labels.join(' | '));

  doc.querySelector('.view-diagnostics .diag-mode button[data-value="columns"]').click();
  await sleep(50);
  let rows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.deepEqual(rows.map((r) => r.querySelectorAll('span').slice(0, 4).map((x) => x.textContent)), [
    ['T_DEPT', 'USE_FLAG', '2', 'DeptMapper.selectDept, UserMapper.selectUser'],
    ['T_USER', 'SUB_DN', '1', 'UserMapper.selectUser']
  ]);
  assert.deepEqual(texts(doc.querySelectorAll('.view-diagnostics .dhead span')), ['테이블', '컬럼', '구문 수', '참조하는 구문 (최대 5개)']);
  assert.ok(doc.querySelector('.view-diagnostics .diag-filters').textContent.includes('SQL과 DDL의 컬럼 불일치'), '무엇을 모은 목록인지 설명한다');
  assert.ok(doc.querySelector('.view-diagnostics .diag-summary').textContent.includes('2 / 2건'));
  // 글자로 거른다
  const input = doc.querySelector('.view-diagnostics .pane-head input');
  input.value = 'sub_dn';
  input.fire('input');
  await sleep(250);
  assert.equal(doc.querySelectorAll('.view-diagnostics .drow').length, 1);
  // 누르면 그 컬럼의 상세가 열린다
  doc.querySelectorAll('.view-diagnostics .drow')[0].click();
  await sleep(20);
  assert.ok(doc.querySelector('.drawer').textContent.includes(f.subDn));

  doc.querySelector('.view-diagnostics .diag-mode button[data-value="requests"]').click();
  await sleep(50);
  rows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.deepEqual(rows.map((r) => [r.querySelector('.stmt').textContent, r.querySelector('.access').textContent]), [
    ['서버 요청', '3'], ['연결됨(확정)', '1'], ['연결됨(추론)', '1'], ['미연결', '1'], ['외부 URL', '1'], ['정적 페이지 링크', '0'], ['화면 내 이동(클라이언트 라우트)', '1'],
    ['a.jsp:3', '미연결']
  ]);
  assert.ok(rows.slice(0, 7).every((r) => r.querySelector('.msg').textContent.length > 10), '행마다 무엇을 센 것인지 적는다');
  assert.equal(rows[7].querySelector('.msg').textContent, 'GET /x3', '통계 아래에 미연결 요청이 위치와 함께 나온다');
  const note = doc.querySelector('.view-diagnostics .diag-filters').textContent;
  assert.ok(note.includes('서버 요청 3건 중 연결 2(확정 1, 추론 1), 미연결 1. 엔드포인트 연결률 67%.') && note.includes('외부 URL, 클라이언트 라우트는 서버 요청이 아니라서 분모에서 뺐다.'), note);
  // 미연결 요청을 누르면 그 요청의 상세가 열린다
  rows[7].click();
  await sleep(20);
  assert.ok(doc.querySelector('.drawer').textContent.includes('http:request:r3'));
  assert.ok(doc.querySelector('.view-diagnostics .diag-summary').textContent.includes('요청 5건'), doc.querySelector('.view-diagnostics .diag-summary').textContent);
  assertClean(window);
});

test('F-016-AC70 화면: 테이블 상세에 "DDL에 없는데 SQL이 참조하는 컬럼"이 구문과 함께 따로 나오고, 관계 상세와 ERD 범례에 방향 미상의 까닭이 적힌다', async () => {
  const f = mismatches();
  const window = await open(f.graph);
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('explore', { id: f.dept });
  await sleep(20);
  const section = doc.querySelector('.view-explore .undeclared-columns');
  assert.ok(section, '테이블 상세의 구분된 절');
  assert.ok(section.querySelector('h3').textContent.includes('DDL에 없는데 SQL이 참조하는 컬럼'), section.textContent);
  assert.ok(section.querySelector('h3 .n').textContent === '1');
  const item = section.querySelector('.undeclared-column');
  assert.ok(item.textContent.includes('USE_FLAG') && item.textContent.includes('구문 2개') && item.textContent.includes('DeptMapper.selectDept')
    && item.textContent.includes('UserMapper.selectUser'), item.textContent);
  assert.match(section.querySelector('.hint').textContent, /ERD에는 넣지 않는다/);
  // 구문을 누르면 그 구문으로 간다
  item.querySelectorAll('button.link').find((b) => b.textContent === 'UserMapper.selectUser').click();
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .detail-head .id').textContent.includes(f.selectUser));
  assert.equal(doc.querySelector('.view-explore .undeclared-columns'), null, '테이블이 아닌 노드와 그런 컬럼이 없는 테이블에는 절이 없다');
  // 관계 상세: 방향을 모르는 까닭
  app.navigate('explore', { id: f.user });
  await sleep(20);
  const edge = doc.querySelectorAll('.view-explore .edge-row').find((r) => r.textContent.includes('T_DEPT') && r.textContent.includes('방향 미상'));
  assert.ok(edge && edge.textContent.includes('방향 미상: 유일하지 않은 키'), edge && edge.textContent);
  // ERD 범례
  app.navigate('erd', {});
  await sleep(300);
  const legend = doc.querySelector('.view-erd .legend').textContent;
  assert.ok(legend.includes('선언된 FK와 반대') && legend.includes('유일하지 않은 키') && legend.includes('키 정보 없음'), legend);
  assertClean(window);
});

test('F-016-AC66 화면: CRUD에서 추론 경로로만 닿은 칸은 ?와 함께 따로 칠하고 풀어 쓴 말을 붙인다. 범례가 ?를 설명하고 CSV에도 그대로 들어간다', async () => {
  const j = javaGraph();
  ['T_SURE', 'T_GUESS'].forEach((t) => j.table(t));
  j.type('com.i.OrderController');
  const handler = j.method('com.i.OrderController', 'save');
  j.endpoint('POST', '/order', handler);
  const sure = j.method('com.i.OrderService', 'sure');
  const one = j.method('com.i.Codec', 'encode', ['java.lang.String']);
  const two = j.method('com.i.Codec', 'encode', ['java.lang.String', 'int']);
  j.call(handler, sure, 20, 'virtual');
  j.call(handler, one, 30, 'virtual', null, { ambiguous: true }, 'INFERRED');
  j.call(handler, two, 30, 'virtual', null, { ambiguous: true }, 'INFERRED');
  j.s.e(sure, 'EXECUTES', j.statement('com.i.OrderMapper', 'selectSure', 'SELECT', 'T_SURE'));
  j.s.e(two, 'EXECUTES', j.statement('com.i.OrderMapper', 'selectGuess', 'SELECT', 'T_GUESS'));
  const window = await open(j.graph());
  const doc = window.document;
  window.CodeAtlas.app.navigate('crud', {});
  await sleep(100);
  const legend = doc.querySelector('.view-crud .crud-legend').textContent;
  assert.ok(legend.includes('?') && legend.includes('추론') && legend.includes('확정 경로'), legend);
  doc.querySelector('.view-crud .crud-kind button[data-value="endpoint"]').click();
  await sleep(100);
  const cells = doc.querySelectorAll('.view-crud .crud-cell').filter((c) => c.textContent);
  assert.deepEqual(texts(cells), ['R?', 'R']);
  const guessed = cells[0];
  assert.ok(guessed.classList.contains('inferred') && guessed.classList.contains('r'), '추론 칸은 따로 칠하되 읽기·쓰기의 색은 그대로다: ' + guessed.className);
  assert.ok(!cells[1].classList.contains('inferred'));
  assert.match(guessed.getAttribute('title'), /추론한 관계를 거쳐서만/);
  assert.match(guessed.getAttribute('title'), /직접 R/);
  let saved = null;
  window.Blob = class { constructor(parts) { saved = parts.join(''); } };
  doc.querySelector('.view-crud .crud-csv').click();
  assert.ok(saved.includes(',R?,R\n'), saved);
  assertClean(window);
});
