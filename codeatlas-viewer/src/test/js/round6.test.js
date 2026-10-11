'use strict';
// java·link, web·jsp 4차 수정이 그래프에 새로 남기는 것(MATCHES.module, resolvedFrom·resolvedBy, viaArgument, dynamicSegment, httpMethods,
// selfSubmit, dynamicDecorator, 중간 정보 attrs, 벤더 요약 메시지)을 뷰어가 표준 산출물과 같게 읽는지 본다(F-016-R41, F-015-R62~R67).
// 기대값은 표준 산출물 테스트 Round6Test(F-015-AC75~AC80)와 같은 모양의 그래프·같은 텍스트 트리다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { assemble } = require('./tools/assemble.js');
const { boot, sleep } = require('./tools/boot.js');
const { javaGraph } = require('./fixtures/java-graph.js');
const tree = require('./tools/export-tree.js');
const notation = require('./tools/export-notation.js');

const graph = core('graph');
const calltree = core('calltree');
const crud = core('crud');
const diag = core('diagnostics');

const lines = (...l) => l.join('\n') + '\n';
const callees = (ix, id, opts, limits) => tree.text(ix, id, 'callees', Object.assign({ showAccessors: true, showExternal: true }, opts || {}), limits);
const callers = (ix, id, opts, limits) => tree.text(ix, id, 'callers', Object.assign({ showAccessors: true, showExternal: true }, opts || {}), limits);
const page = (j, path) => j.s.n('jsp:page:' + path, 'JSP_PAGE', path.substring(path.lastIndexOf('/') + 1), path, {}, { file: path, startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
function request(j, from, httpMethod, path, line, attrs, endpoint, matchAttrs, confidence) {
  const file = from.substring(from.indexOf(':', from.indexOf(':') + 1) + 1);
  const id = j.s.n('http:request:' + httpMethod + ' ' + path + '@' + file + ':' + line, 'HTTP_REQUEST', httpMethod + ' ' + path, httpMethod + ' ' + path,
    Object.assign({ httpMethod, path }, attrs || {}), j.at(file, line));
  j.s.e(from, 'REQUESTS', id, {}, null, j.at(file, line));
  if (endpoint) j.s.e(id, 'MATCHES', endpoint, matchAttrs || {}, confidence);
  return id;
}

/** 두 웹 모듈이 같은 경로의 핸들러·뷰 컨트롤러를 가진 프로젝트(Round6Test.TwoModules와 같다). 화면은 WebContent에 있어 경로로는 모듈을 알 수 없다. */
function twoModules() {
  const j = javaGraph();
  j.type('com.m.admin.AdminController', { root: 'admin-web/' });
  j.type('com.m.front.FrontController', { root: 'front-web/' });
  const m = { j };
  m.adminPage = page(j, 'admin-web/WebContent/list.jsp');
  m.frontPage = page(j, 'front-web/WebContent/list.jsp');
  m.admin = j.method('com.m.admin.AdminController', 'list', [], { root: 'admin-web/' });
  m.front = j.method('com.m.front.FrontController', 'list', [], { root: 'front-web/' });
  m.endpoint = j.endpoint('GET', '/user/list', m.admin);
  j.endpoint('GET', '/user/list', m.front);
  m.home = j.endpoint('GET', '/', null);
  m.adminHome = page(j, 'admin-web/WebContent/index.jsp');
  m.frontHome = page(j, 'front-web/WebContent/index.jsp');
  j.s.e(m.home, 'RENDERS', m.adminHome, {}, null, j.at('admin-web/src/main/resources/mvc.xml', 12));
  j.s.e(m.home, 'RENDERS', m.frontHome, {}, null, j.at('front-web/src/main/resources/mvc.xml', 12));
  ['T_ADMIN', 'T_FRONT', 'T_SHARED'].forEach((t) => j.table(t));
  j.s.e(m.admin, 'EXECUTES', j.statement('com.m.AdminMapper', 'select', 'SELECT', 'T_ADMIN'));
  j.s.e(m.front, 'EXECUTES', j.statement('com.m.FrontMapper', 'select', 'SELECT', 'T_FRONT'));
  j.s.e(m.admin, 'EXECUTES', j.statement('com.m.AdminMapper', 'shared', 'SELECT', 'T_SHARED'));
  j.s.e(m.front, 'EXECUTES', j.statement('com.m.FrontMapper', 'shared', 'SELECT', 'T_SHARED'));
  request(j, m.adminPage, 'GET', '/user/list', 7, null, m.endpoint, { module: 'admin-web' });
  request(j, m.adminPage, 'GET', '/', 9, null, m.home, { module: 'admin-web' });
  request(j, m.frontPage, 'GET', '/user/list', 7, null, m.endpoint, { module: 'front-web' });
  request(j, m.frontPage, 'GET', '/', 9, null, m.home, { module: 'front-web' });
  return m;
}

test('F-016-AC73 콜트리: MATCHES.module이 있으면 엔드포인트 아래로는 그 모듈의 핸들러와 그 모듈 선언에서 나온 화면만 따라간다. 없으면 종전대로다 (F-015-AC75)', () => {
  const m = twoModules();
  const other = page(m.j, 'etc/WebContent/a.jsp');
  request(m.j, other, 'GET', '/user/list', 3, null, m.endpoint);
  request(m.j, other, 'GET', '/user/list', 5, null, m.endpoint, { module: 'batch' });
  const ix = graph.buildIndex(m.j.graph());
  assert.equal(callees(ix, m.adminPage), lines(
    '[jsp] admin-web/WebContent/list.jsp',
    '├─ [request] GET /user/list',
    '│  └─ [endpoint] GET /user/list',
    '│     └─ AdminController#list()',
    '│        ├─ [sql] AdminMapper.select <SELECT>',
    '│        │  └─ [table] T_ADMIN (R)',
    '│        └─ [sql] AdminMapper.shared <SELECT>',
    '│           └─ [table] T_SHARED (R)',
    '└─ [request] GET /',
    '   └─ [endpoint] GET /',
    '      └─ [jsp] admin-web/WebContent/index.jsp'));
  assert.equal(callees(ix, other), lines(
    '[jsp] etc/WebContent/a.jsp',
    '├─ [request] GET /user/list',
    '│  └─ [endpoint] GET /user/list',
    '│     ├─ AdminController#list() (모듈 admin-web)',
    '│     │  ├─ [sql] AdminMapper.select <SELECT>',
    '│     │  │  └─ [table] T_ADMIN (R)',
    '│     │  └─ [sql] AdminMapper.shared <SELECT>',
    '│     │     └─ [table] T_SHARED (R)',
    '│     └─ FrontController#list() (모듈 front-web)',
    '│        ├─ [sql] FrontMapper.select <SELECT>',
    '│        │  └─ [table] T_FRONT (R)',
    '│        └─ [sql] FrontMapper.shared <SELECT>',
    '│           └─ [table] T_SHARED (R)',
    '└─ [request] GET /user/list',
    '   └─ [endpoint] GET /user/list'));
  // 위 방향: 핸들러(뷰 컨트롤러의 화면)에서 올라간 엔드포인트의 요청은 그 모듈의 것만
  assert.equal(callers(ix, m.admin), lines(
    'AdminController#list()',
    '└─ [endpoint] GET /user/list',
    '   ├─ [request] GET /user/list',
    '   │  └─ [jsp] admin-web/WebContent/list.jsp',
    '   └─ [request] GET /user/list',
    '      └─ [jsp] etc/WebContent/a.jsp'));
  assert.equal(callers(ix, m.frontHome), lines(
    '[jsp] front-web/WebContent/index.jsp',
    '└─ [endpoint] GET /',
    '   └─ [request] GET /',
    '      └─ [jsp] front-web/WebContent/list.jsp'));
  // 핸들러가 여럿인 엔드포인트는 어디서 왔느냐에 따라 아래가 달라지는 노드다(한 번만 따라가는 탐색이 문맥별로 따로 본다)
  assert.equal(calltree.contextSensitive(ix, m.endpoint), true);
});

test('F-016-AC73 콜트리·진단: 같은 지점에서 다른 방법으로 풀린 구문(resolvedFrom)이 있으면 미해결 EXECUTES 줄을 내지 않고, resolvedBy가 있는 미해결 노드는 미해결 참조에서 뺀다 (F-015-AC76)', () => {
  const j = javaGraph();
  j.type('com.n.LoginDAO');
  const dao = j.method('com.n.LoginDAO', 'updateLoginIncorrect');
  const ent = j.statement('LoginUsr', 'updateLoginIncorrectENT', 'UPDATE');
  const site = { file: 'src/main/java/com/n/LoginDAO.java', startLine: 106, startColumn: 6, endLine: 106, endColumn: 12 };
  const unresolved = j.s.n('unresolved:mybatis-statement:LoginUsr.updateLoginIncorrect{*}', 'UNRESOLVED', 'LoginUsr.updateLoginIncorrect{*}', null,
    { category: 'mybatis-statement', resolvedBy: 'prefixMatch' }, site);
  const other = j.s.n('unresolved:mybatis-statement:LoginUsr.unknown', 'UNRESOLVED', 'LoginUsr.unknown', null, { category: 'mybatis-statement' }, site);
  j.s.e(dao, 'EXECUTES', unresolved, {}, 'UNRESOLVED', site);
  j.s.e(dao, 'EXECUTES', ent, { prefixMatch: true, statementPrefix: 'LoginUsr.updateLoginIncorrect', resolvedFrom: unresolved }, 'INFERRED', site);
  j.s.e(dao, 'EXECUTES', other, {}, 'UNRESOLVED', Object.assign({}, site, { startLine: 110, endLine: 110 }));
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, dao), lines(
    'LoginDAO#updateLoginIncorrect()',
    '├─ ~ [sql] LoginUsr.updateLoginIncorrectENT <UPDATE> (접두 일치)',
    '└─ ? [unresolved] LoginUsr.unknown'));
  // 화면의 줄에는 관계 이름에 밝힌다
  const kids = calltree.expand(ix, calltree.createRoot(ix, dao, 'callees'));
  assert.deepEqual(kids.map((k) => k.link.label), ['EXECUTES(접두 일치)', 'EXECUTES']);
  // 미해결 참조: resolvedBy가 있는 미해결 노드로 가는 참조는 세지 않는다(report.md와 같다)
  const r = diag.collect(ix);
  assert.deepEqual(r.unresolved.map((u) => u.target), ['LoginUsr.unknown']);
  assert.deepEqual(r.categories.map((c) => [c.category, c.count]), [['mybatis-statement', 1]]);
});

test('F-016-AC73 콜트리·CRUD: 인자로 넘긴 구문 ID로 이은 실행(viaArgument)은 (인자 구문), 동적 조각이 든 뷰 이름의 화면(RENDERS의 dynamicSegment)은 (동적 뷰 이름 후보)를 붙인 추론 줄이다 (F-015-AC77, AC78)', () => {
  const j = javaGraph();
  ['com.n.JobConfig', 'com.n.BlogController'].forEach((t) => j.type(t));
  j.table('ORDERS');
  const reader = j.method('com.n.JobConfig', 'closedOrderReader');
  const statement = j.statement('com.n.BatchMapper', 'selectClosedOrders', 'SELECT', 'ORDERS');
  j.s.e(reader, 'EXECUTES', statement, { viaArgument: true }, 'INFERRED', { file: 'src/main/java/com/n/JobConfig.java', startLine: 84, startColumn: 27, endLine: 84, endColumn: 27 });
  const handler = j.method('com.n.BlogController', 'index');
  const basic = page(j, 'src/main/webapp/basic/blog/index.jsp');
  const mobile = page(j, 'src/main/webapp/mobile/blog/index.jsp');
  [basic, mobile].forEach((p) => j.s.e(handler, 'RENDERS', p, { dynamicSegment: true, suffixMatch: true, viewName: '/{*}/blog/index.jsp' }, 'INFERRED',
    { file: 'src/main/java/com/n/BlogController.java', startLine: 30, startColumn: 16, endLine: 30, endColumn: 16 }));
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, reader), lines(
    'JobConfig#closedOrderReader()',
    '└─ ~ [sql] BatchMapper.selectClosedOrders <SELECT> (인자 구문)',
    '   └─ [table] ORDERS (R)'));
  assert.equal(callers(ix, statement), lines(
    '[sql] BatchMapper.selectClosedOrders <SELECT>',
    '└─ ~ JobConfig#closedOrderReader() (인자 구문)'));
  assert.equal(callees(ix, handler), lines(
    'BlogController#index()',
    '├─ ~ [jsp] src/main/webapp/basic/blog/index.jsp (동적 뷰 이름 후보)',
    '└─ ~ [jsp] src/main/webapp/mobile/blog/index.jsp (동적 뷰 이름 후보)'));
  assert.equal(callers(ix, basic), lines(
    '[jsp] src/main/webapp/basic/blog/index.jsp',
    '└─ ~ BlogController#index() (동적 뷰 이름 후보)'));
  const link = calltree.expand(ix, calltree.createRoot(ix, reader, 'callees'))[0].link;
  assert.equal(link.inference, '인자로 넘긴 구문 ID(viaArgument)');
  assert.equal(calltree.expand(ix, calltree.createRoot(ix, handler, 'callees'))[0].link.inference, '동적 조각이 든 뷰 이름의 후보(dynamicSegment)');
  // 그 경로로만 닿은 CRUD 칸은 ?다
  const matrix = crud.buildMatrix(ix, { rowKind: 'method' });
  assert.equal(matrix.rows.find((x) => x.id === reader).cells['db:table:ORDERS'], 'R?');
  // 엣지 요약에도 적는다
  const out = graph.nodeDetail(ix, reader).outgoing;
  assert.ok(JSON.stringify(out).includes('인자로 넘긴 구문 ID'), JSON.stringify(out));
  assert.ok(JSON.stringify(graph.nodeDetail(ix, handler).outgoing).includes('동적 조각이 든 뷰 이름의 후보'));
});

test('F-016-AC73 요청 이름: 메서드가 여럿인 요청(httpMethod=ANY, httpMethods)은 POST|PUT으로, 자기 페이지로 내는 폼(selfSubmit)은 {self} 대신 말로 적는다 (F-015-AC79)', () => {
  const j = javaGraph();
  j.type('com.n.EditController');
  const p = page(j, 'src/main/webapp/edit.jsp');
  const save = j.endpoint('POST', '/edit', j.method('com.n.EditController', 'save'));
  const multi = request(j, p, 'ANY', '/api/schedule', 12, { httpMethods: ['POST', 'PUT'], url: 'editUrl', api: 'fetch' });
  const self = request(j, p, 'POST', '{self}', 20, { selfSubmit: true, url: '', api: 'form' }, save, { selfSubmit: true }, 'INFERRED');
  const lost = request(j, p, 'GET', '{self}', 25, { selfSubmit: true, url: '', api: 'form' });
  const any = request(j, p, 'ANY', '/plain', 30);
  const ix = graph.buildIndex(j.graph());
  assert.equal(graph.shortLabel(ix.node(multi), multi), 'POST|PUT /api/schedule');
  assert.equal(graph.shortLabel(ix.node(self), self), 'POST (자기 페이지로 제출)');
  assert.equal(graph.shortLabel(ix.node(any), any), 'ANY /plain');
  assert.equal(ix.label(self), 'POST (자기 페이지로 제출)');
  assert.equal(notation.label(ix, multi), 'POST|PUT /api/schedule');
  assert.equal(callees(ix, p), lines(
    '[jsp] src/main/webapp/edit.jsp',
    '├─ [request] POST|PUT /api/schedule',
    '├─ [request] POST (자기 페이지로 제출)',
    '│  └─ ~ [endpoint] POST /edit',
    '│     └─ EditController#save()',
    '├─ [request] GET (자기 페이지로 제출)',
    '└─ [request] ANY /plain'));
  // 화면 요청 통계의 미연결 목록도 같은 이름이다
  const stats = diag.requestStatistics(ix);
  assert.deepEqual(stats.unmatched.map((u) => u.label), ['POST|PUT /api/schedule', 'GET (자기 페이지로 제출)', 'ANY /plain']);
  // 꼬리표와 요약
  const tags = graph.nodeDetail(ix, self).tags.map((t) => t.text);
  assert.ok(tags.includes('자기 페이지로 제출'), tags.join());
  assert.ok(graph.nodeDetail(ix, multi).summary.includes('POST 또는 PUT'), graph.nodeDetail(ix, multi).summary);
  assert.ok(!graph.nodeDetail(ix, self).summary.includes('URL 원문'), '빈 URL 원문은 적지 않는다: ' + graph.nodeDetail(ix, self).summary);
});

test('F-016-AC73 콜트리: 동적 데코레이터(INCLUDES의 dynamicDecorator)는 추론 레이아웃 줄이고, 후보 여럿(ambiguous)이면 [ambiguous n] 아래에 묶는다 (F-015-AC80)', () => {
  const j = javaGraph();
  const p = page(j, 'src/main/webapp/front/index.jsp');
  const single = page(j, 'src/main/webapp/front/list.jsp');
  const basic = page(j, 'src/main/webapp/themes/basic/layouts/default.jsp');
  const weixin = page(j, 'src/main/webapp/themes/weixin/layouts/default.jsp');
  const at = (file) => ({ file, startLine: 7, startColumn: 2, endLine: 7, endColumn: 62 });
  [basic, weixin].forEach((d) => j.s.e(p, 'INCLUDES', d, { via: 'sitemesh', dynamicDecorator: true, ambiguous: true, path: 'cms_default_${site.theme}' }, 'INFERRED',
    at('src/main/webapp/front/index.jsp')));
  j.s.e(single, 'INCLUDES', basic, { via: 'sitemesh', dynamicDecorator: true, path: 'cms_default_${site.theme}' }, 'INFERRED', at('src/main/webapp/front/list.jsp'));
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, p), lines(
    '[jsp] src/main/webapp/front/index.jsp',
    '└─ ~ [ambiguous 2] src/main/webapp/front/index.jsp',
    '   ├─ ~ [layout] [jsp] src/main/webapp/themes/basic/layouts/default.jsp',
    '   └─ ~ [layout] [jsp] src/main/webapp/themes/weixin/layouts/default.jsp'));
  assert.equal(callees(ix, single), lines(
    '[jsp] src/main/webapp/front/list.jsp',
    '└─ ~ [layout] [jsp] src/main/webapp/themes/basic/layouts/default.jsp'));
  const group = calltree.expand(ix, calltree.createRoot(ix, p, 'callees'))[0];
  assert.equal(group.synthetic.count, 2);
  assert.equal(group.children[0].link.inference, '후보 여럿(ambiguous)');
  assert.equal(calltree.expand(ix, calltree.createRoot(ix, single, 'callees'))[0].link.inference, '동적 데코레이터 이름(dynamicDecorator)');
  const summary = JSON.stringify(graph.nodeDetail(ix, single).outgoing);
  assert.ok(summary.includes('동적 데코레이터'), summary);
});

/** 중간 정보 attrs가 든 노드와 새 꼴의 벤더 요약 진단. */
function internals() {
  const j = javaGraph();
  j.type('com.n.Dao');
  const method = j.method('com.n.Dao', 'find', [], { attrs: { statementIdLiterals: ['Order.select', 'Order.count'], statementIdLiteralLines: [12, 14], returnType: 'java.util.List' } });
  const p = j.s.n('jsp:page:src/main/webapp/user.jsp', 'JSP_PAGE', 'user.jsp', 'src/main/webapp/user.jsp',
    { formFields: ['userVO.zip_url=/zip/popup.do', '#userVO.zip_url=/zip/popup.do'], pageType: 'jsp' }, { file: 'src/main/webapp/user.jsp', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  j.s.d('INFO', 'FILE_SKIPPED', '벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): src/main/webapp/js/lib', { file: 'src/main/webapp/js/lib', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  j.s.d('INFO', 'FILE_SKIPPED', '벤더 페이지 1개 건너뜀(경로): src/main/webapp/js/lib', { file: 'src/main/webapp/js/lib', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  return { graph: j.graph(), method, page: p };
}

test('F-016-AC74 상세: 분석의 중간 정보(JAVA_METHOD의 statementIdLiterals·statementIdLiteralLines, JSP_PAGE의 formFields)는 속성 표에 쏟지 않고 접어 둔다', async () => {
  const f = internals();
  const ix = graph.buildIndex(f.graph);
  const d = graph.nodeDetail(ix, f.method);
  assert.deepEqual(d.attrs.map((a) => a.key).filter((k) => /statementIdLiteral/.test(k)), []);
  assert.deepEqual(d.internalAttrs.map((a) => a.key), ['statementIdLiterals', 'statementIdLiteralLines']);
  assert.ok(d.attrs.some((a) => a.key === 'returnType'), '다른 속성은 그대로 보인다');
  assert.deepEqual(graph.nodeDetail(ix, f.page).internalAttrs.map((a) => a.key), ['formFields']);
  // 화면: 속성 표에는 없고, 접힌 "분석 중간 정보" 아래에만 있다
  const window = boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  window.CodeAtlas.app.navigate('explore', { id: f.method });
  await sleep(20);
  const folded = doc.querySelector('.view-explore .internal-attrs');
  assert.ok(folded, '접힌 중간 정보 절');
  assert.equal(folded.tagName.toLowerCase(), 'details');
  assert.ok(!folded.open, '기본으로 접혀 있다');
  assert.ok(folded.querySelector('summary').textContent.includes('분석 중간 정보') && folded.querySelector('summary').textContent.includes('2'));
  assert.ok(folded.textContent.includes('Order.select') && folded.textContent.includes('statementIdLiteralLines'));
  const section = doc.querySelectorAll('.view-explore .section').find((s) => s.querySelector('.internal-attrs'));
  assert.equal(section.querySelector('h3 .n').textContent, String(graph.nodeDetail(ix, f.method).attrs.length), '속성 수는 보이는 것만 센다');
  const outside = section.textContent.replace(folded.textContent, '');
  assert.ok(!outside.includes('statementIdLiteral') && outside.includes('returnType'), outside);
  window.CodeAtlas.app.navigate('explore', { id: 'java:type:com.n.Dao' });
  await sleep(20);
  assert.equal(doc.querySelector('.view-explore .internal-attrs'), null, '중간 정보가 없는 노드에는 접힌 절이 없다');
  assert.deepEqual(window.errors, []);
});

test('F-016-AC74 진단: 벤더 요약 메시지의 새 꼴(벤더 스크립트 n개 건너뜀(이름 a, 라이선스 b): <폴더>)도 문구에 기대지 않고 위치(폴더)로 읽는다', () => {
  const f = internals();
  const r = diag.collect(graph.buildIndex(f.graph));
  const skipped = r.diagnostics.filter((x) => x.code === 'FILE_SKIPPED');
  assert.deepEqual(skipped.map((x) => x.where), ['src/main/webapp/js/lib/ (폴더)', 'src/main/webapp/js/lib/ (폴더)']);
  assert.ok(skipped[0].message.startsWith('벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4)'));
});

test('F-016-AC73 관계 상세: 방향은 DDL로 확인했지만 카디널리티를 모르는 추론 관계(directionKnown=true, directionFrom=DDL, cardinality=unknown)는 요약에 그렇게 적는다 (F-013-AC30)', () => {
  const j = javaGraph();
  const child = j.table('COMTNCOMMENT');
  const parent = j.table('COMTNBBS');
  j.s.e(child, 'REFERENCES', parent, { cardinality: 'unknown', columns: ['NTT_ID'], refColumns: ['NTT_ID'], directionFrom: 'DDL', directionKnown: true, evidenceCount: 2, source: 'INFERRED' }, 'INFERRED');
  const ix = graph.buildIndex(j.graph());
  const summary = graph.nodeDetail(ix, child).outgoing[0].items[0].summary;
  assert.equal(summary, 'NTT_ID → NTT_ID · 방향은 DDL로 확인, 카디널리티 모름 · 추론 2건');
});
