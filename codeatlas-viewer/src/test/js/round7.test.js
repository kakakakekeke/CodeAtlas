'use strict';
// 4차 재검증 후속(5차 수정)에서 표준 산출물이 바꾼 규칙을 뷰어가 같게 따르는지 본다(F-016-R43, F-015-R68~R75, F-013-R28·R29).
// 기대값은 표준 산출물 테스트 Round7Test(F-015-AC81~AC89), ErdRound7Test(F-013-AC31~AC33)와 같은 모양의 그래프·같은 글자다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { javaGraph } = require('./fixtures/java-graph.js');
const { slice } = require('./fixtures/slice.js');
const tree = require('./tools/export-tree.js');

const graph = core('graph');
const calltree = core('calltree');
const diag = core('diagnostics');
const erd = core('erd');

const lines = (...l) => l.join('\n') + '\n';
const callees = (ix, id, opts, limits) => tree.text(ix, id, 'callees', Object.assign({ showAccessors: true, showExternal: true }, opts || {}), limits);
const callers = (ix, id, opts, limits) => tree.text(ix, id, 'callers', Object.assign({ showAccessors: true, showExternal: true }, opts || {}), limits);
const page = (j, path) => j.s.n('jsp:page:' + path, 'JSP_PAGE', path.substring(path.lastIndexOf('/') + 1), path, {}, { file: path, startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
function request(j, from, httpMethod, path, line, attrs, endpoint, matchAttrs, confidence, requestsConfidence) {
  const file = from.substring(from.indexOf(':', from.indexOf(':') + 1) + 1);
  const id = j.s.n('http:request:' + httpMethod + ' ' + path + '@' + file + ':' + line, 'HTTP_REQUEST', httpMethod + ' ' + path, httpMethod + ' ' + path,
    Object.assign({ httpMethod, path }, attrs || {}), j.at(file, line));
  j.s.e(from, 'REQUESTS', id, {}, requestsConfidence || null, j.at(file, line));
  if (endpoint) j.s.e(id, 'MATCHES', endpoint, matchAttrs || {}, confidence);
  return id;
}

/** 두 웹 모듈(admin-web, front-web)이 같은 경로의 핸들러를 가진 프로젝트(Round7Test.TwoModules와 같다). */
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
  request(j, m.adminPage, 'GET', '/user/list', 7, null, m.endpoint, { module: 'admin-web' });
  request(j, m.frontPage, 'GET', '/user/list', 7, null, m.endpoint, { module: 'front-web' });
  return m;
}

test('F-016-AC75 콜트리(위 방향): 선언이 여러 모듈에 있는 엔드포인트에서 시작하면 요청 줄에 그 요청의 MATCHES.module을 (모듈 …)로 붙인다. crossModule과 module이 함께 있어도 그 모듈의 핸들러만 따라간다 (F-015-AC81, AC82)', () => {
  const m = twoModules();
  const other = page(m.j, 'etc/WebContent/a.jsp');
  request(m.j, other, 'GET', '/user/list', 3, null, m.endpoint);
  let ix = graph.buildIndex(m.j.graph());
  assert.equal(callers(ix, m.endpoint), lines(
    '[endpoint] GET /user/list',
    '├─ [request] GET /user/list (모듈 admin-web)',
    '│  └─ [jsp] admin-web/WebContent/list.jsp',
    '├─ [request] GET /user/list',
    '│  └─ [jsp] etc/WebContent/a.jsp',
    '└─ [request] GET /user/list (모듈 front-web)',
    '   └─ [jsp] front-web/WebContent/list.jsp'));
  // 화면의 줄에는 관계 이름에 밝힌다
  const labels = calltree.expand(ix, calltree.createRoot(ix, m.endpoint, 'callers')).map((k) => k.link.label);
  assert.deepEqual(labels, ['MATCHES(모듈 admin-web)', 'MATCHES', 'MATCHES(모듈 front-web)']);
  // 핸들러에서 올라온 엔드포인트는 그 모듈의 요청만 남으므로 표식이 없다
  assert.equal(callers(ix, m.admin), lines(
    'AdminController#list()',
    '└─ [endpoint] GET /user/list',
    '   ├─ [request] GET /user/list',
    '   │  └─ [jsp] admin-web/WebContent/list.jsp',
    '   └─ [request] GET /user/list',
    '      └─ [jsp] etc/WebContent/a.jsp'));

  // crossModule + module: 추론(~)으로 표시하되 module의 핸들러만(아래), 그 모듈의 핸들러에서 올라왔을 때만(위)
  const c = twoModules();
  const ui = page(c.j, 'ui/pages/list.html');
  request(c.j, ui, 'GET', '/user/list', 12, null, c.endpoint, { crossModule: true, module: 'front-web' }, 'INFERRED');
  ix = graph.buildIndex(c.j.graph());
  assert.equal(callees(ix, ui), lines(
    '[jsp] ui/pages/list.html',
    '└─ [request] GET /user/list',
    '   └─ ~ [endpoint] GET /user/list',
    '      └─ FrontController#list()'));
  assert.equal(callers(ix, c.front), lines(
    'FrontController#list()',
    '└─ [endpoint] GET /user/list',
    '   ├─ [request] GET /user/list',
    '   │  └─ [jsp] front-web/WebContent/list.jsp',
    '   └─ ~ [request] GET /user/list',
    '      └─ [jsp] ui/pages/list.html'));
  assert.ok(!callers(ix, c.admin).includes('ui/pages/list.html'));
  assert.ok(callers(ix, c.endpoint).includes('~ [request] GET /user/list (모듈 front-web)\n   └─ [jsp] ui/pages/list.html'));

  // 선언이 한 모듈뿐인 엔드포인트: module이 적혀 있어도 붙이지 않는다
  const s = javaGraph();
  s.type('com.m.AController', { root: 'web/' });
  const p = page(s, 'web/WebContent/a.jsp');
  const endpoint = s.endpoint('GET', '/a', s.method('com.m.AController', 'a', [], { root: 'web/' }));
  request(s, p, 'GET', '/a', 3, null, endpoint, { module: 'web' });
  assert.equal(callers(graph.buildIndex(s.graph()), endpoint), lines('[endpoint] GET /a', '└─ [request] GET /a', '   └─ [jsp] web/WebContent/a.jsp'));
});

test('F-016-AC76 selfSubmit 표기: 미해결 대상을 POST (selfSubmit)로 적지 않고 그 화면을 그리는 엔드포인트의 경로로 풀어 쓰며, 후보 여럿의 묶음 줄은 요청 문구를 되풀이하지 않는다 (F-015-AC83, AC84)', () => {
  const j = javaGraph();
  j.type('com.n.PlaceController');
  const known = page(j, 'src/main/webapp/WEB-INF/jsp/place.jsp');
  const orphan = page(j, 'src/main/webapp/WEB-INF/jsp/fragment.jsp');
  const handler = j.method('com.n.PlaceController', 'view');
  j.endpoint('GET', '/place/view.do', handler);
  j.s.e(handler, 'RENDERS', known);
  const unresolved = j.s.n('unresolved:http-endpoint:POST (selfSubmit)', 'UNRESOLVED', 'POST (selfSubmit)', null, { category: 'http-endpoint' });
  const self = { selfSubmit: true, url: '', api: 'form' };
  request(j, known, 'POST', '{self}', 59, self, unresolved, {}, 'UNRESOLVED', 'INFERRED');
  request(j, orphan, 'POST', '{self}', 12, self, unresolved, {}, 'UNRESOLVED', 'INFERRED');
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, known), lines(
    '[jsp] src/main/webapp/WEB-INF/jsp/place.jsp',
    '└─ ~ [request] POST (자기 페이지로 제출)',
    '   └─ ? [unresolved] POST /place/view.do (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 POST 매핑 없음)'));
  assert.equal(callees(ix, orphan), lines(
    '[jsp] src/main/webapp/WEB-INF/jsp/fragment.jsp',
    '└─ ~ [request] POST (자기 페이지로 제출)',
    '   └─ ? [unresolved] POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 찾지 못함)'));
  // 요청 문맥이 없는 곳(검색 결과, 상세 제목, 시작 노드)
  assert.equal(graph.shortLabel(ix.node(unresolved), unresolved), 'POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 모르거나 그 경로에 POST 매핑 없음)');
  assert.ok(!calltree.toText(ix, (() => { const r = calltree.createRoot(ix, known, 'callees'); calltree.expandAll(ix, r); return r; })()).includes('(selfSubmit)'));
  // 미해결 참조 목록의 대상도 같은 이름이다(report.md와 같다)
  const targets = diag.collect(ix).unresolved.map((u) => u.target);
  assert.deepEqual(targets.sort(), ['POST (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 찾지 못함)',
    'POST /place/view.do (자기 페이지로 제출: 이 화면을 그리는 엔드포인트의 경로에 POST 매핑 없음)']);

  // 후보가 여럿인 selfSubmit의 묶음 줄
  const a = javaGraph();
  a.type('com.n.CnsController');
  const confirm = page(a, 'src/main/webapp/confirm.jsp');
  const view = a.endpoint('ANY', '/cns/confirmView.do', a.method('com.n.CnsController', 'confirmView'));
  const detail = a.endpoint('POST', '/cns/detail.do', a.method('com.n.CnsController', 'detail'));
  const form = request(a, confirm, 'POST', '{self}', 90, self, null, null, null, 'INFERRED');
  const plain = request(a, confirm, 'GET', '/cns/{*}', 95);
  [view, detail].forEach((endpoint) => {
    a.s.e(form, 'MATCHES', endpoint, { selfSubmit: true, ambiguous: true }, 'INFERRED');
    a.s.e(plain, 'MATCHES', endpoint, { ambiguous: true }, 'INFERRED');
  });
  const ax = graph.buildIndex(a.graph());
  assert.equal(callees(ax, confirm), lines(
    '[jsp] src/main/webapp/confirm.jsp',
    '├─ ~ [request] POST (자기 페이지로 제출)',
    '│  └─ ~ [ambiguous 2] 후보 엔드포인트',
    '│     ├─ ~ [endpoint] ANY /cns/confirmView.do',
    '│     │  └─ CnsController#confirmView()',
    '│     └─ ~ [endpoint] POST /cns/detail.do',
    '│        └─ CnsController#detail()',
    '└─ [request] GET /cns/{*}',
    '   └─ ~ [ambiguous 2] GET /cns/{*}',
    '      ├─ ~ [endpoint] ANY /cns/confirmView.do',
    '      │  └─ CnsController#confirmView()',
    '      └─ ~ [endpoint] POST /cns/detail.do',
    '         └─ CnsController#detail()'));
  const group = calltree.expand(ax, calltree.expand(ax, calltree.createRoot(ax, confirm, 'callees'))[0])[0];
  assert.equal(group.synthetic.text, '후보 엔드포인트');
});

test('F-016-AC77 콜트리(위 방향): 엔드포인트에서 시작하면 그 URL로 넘어오는 forward·redirect 핸들러가 화면 요청과 함께 한 줄씩 나온다. 핸들러가 엔드포인트 여럿을 받으면 path가 같은 곳에만 적는다 (F-015-AC85)', () => {
  const j = javaGraph();
  j.type('com.n.BbsController');
  const list = j.method('com.n.BbsController', 'list');
  const insert = j.method('com.n.BbsController', 'insert');
  const update = j.method('com.n.BbsController', 'update');
  const helper = j.method('com.n.BbsController', 'helper');
  const endpoint = j.endpoint('ANY', '/bbs/list.do', list);
  j.endpoint('POST', '/bbs/insert.do', insert);
  const p = page(j, 'src/main/webapp/bbs.jsp');
  request(j, p, 'GET', '/bbs/list.do', 4, null, endpoint);
  j.call(insert, list, 40, 'forward', null, { path: '/bbs/list.do' }, 'INFERRED');
  j.call(update, list, 60, 'redirect', null, { path: '/bbs/list.do' }, 'INFERRED');
  j.call(helper, list, 80, 'virtual');
  const ix = graph.buildIndex(j.graph());
  assert.equal(callers(ix, endpoint), lines(
    '[endpoint] ANY /bbs/list.do',
    '├─ [request] GET /bbs/list.do',
    '│  └─ [jsp] src/main/webapp/bbs.jsp',
    '├─ ~ [forward] BbsController#insert()',
    '└─ ~ [redirect] BbsController#update()'));
  // 핸들러에서 올라온 엔드포인트에는 다시 적지 않는다(핸들러의 호출자에 이미 있다)
  assert.equal(callers(ix, list), lines(
    'BbsController#list()',
    '├─ [endpoint] ANY /bbs/list.do',
    '│  └─ [request] GET /bbs/list.do',
    '│     └─ [jsp] src/main/webapp/bbs.jsp',
    '├─ BbsController#helper()',
    '├─ ~ [forward] BbsController#insert()',
    '└─ ~ [redirect] BbsController#update()'));

  const k = javaGraph();
  k.type('com.n.BbsController');
  const shared = k.method('com.n.BbsController', 'list');
  const a = k.endpoint('ANY', '/bbs/a.do', shared);
  const b = k.endpoint('ANY', '/bbs/b.do', shared);
  k.call(k.method('com.n.BbsController', 'toA'), shared, 40, 'forward', null, { path: '/bbs/a.do?mode=1' }, 'INFERRED');
  k.call(k.method('com.n.BbsController', 'unknown'), shared, 60, 'forward', null, { path: '/ctx/bbs/x.do' }, 'INFERRED');
  const kx = graph.buildIndex(k.graph());
  assert.equal(callers(kx, a), lines('[endpoint] ANY /bbs/a.do', '├─ ~ [forward] BbsController#toA()', '└─ ~ [forward] BbsController#unknown()'));
  assert.equal(callers(kx, b), lines('[endpoint] ANY /bbs/b.do', '└─ ~ [forward] BbsController#unknown()'));
});

test('F-016-AC78 진단 보기: 미해결 참조의 건수가 신뢰도 UNRESOLVED 엣지 수와 다른 까닭을 report.md와 같은 문장으로 알리고, 데이터 적재 묶음의 borrowedFrom은 "보충한 구문"에 섞지 않고 적재 파일에 방언으로 드러낸다 (F-015-AC86, AC88)', () => {
  const j = javaGraph();
  j.type('com.n.LoginDAO');
  const dao = j.method('com.n.LoginDAO', 'update');
  const ent = j.statement('LoginUsr', 'updateENT', 'UPDATE');
  const site = { file: 'src/main/java/com/n/LoginDAO.java', startLine: 106, startColumn: 6, endLine: 106, endColumn: 12 };
  const resolved = j.s.n('unresolved:mybatis-statement:LoginUsr.update{*}', 'UNRESOLVED', 'LoginUsr.update{*}', null, { category: 'mybatis-statement' }, site);
  const open = j.s.n('unresolved:mybatis-statement:LoginUsr.unknown', 'UNRESOLVED', 'LoginUsr.unknown', null, { category: 'mybatis-statement' }, site);
  j.s.e(dao, 'EXECUTES', resolved, {}, 'UNRESOLVED', site);
  j.s.e(dao, 'EXECUTES', open, {}, 'UNRESOLVED', Object.assign({}, site, { startLine: 110, endLine: 110 }));
  assert.equal(diag.collect(graph.buildIndex(j.graph())).unresolvedDifference, '');
  j.graph().nodes.find((n) => n.id === resolved).attrs.resolvedBy = 'prefixMatch';
  j.s.e(dao, 'EXECUTES', ent, { prefixMatch: true, resolvedFrom: resolved }, 'INFERRED', site);
  const r = diag.collect(graph.buildIndex(j.graph()));
  assert.equal(r.unresolved.length, 1);
  assert.equal(r.unresolvedDifference, '신뢰도가 `UNRESOLVED`인 엣지 2건 가운데 1건은 같은 지점에서 접두 일치로 풀려(대상 노드의 `resolvedBy`) 여기서 뺐다.');

  // 데이터 적재: 다른 방언 파일의 묶음(borrowedFrom)
  const d = javaGraph();
  ['T_CODE', 'T_A', 'T_B'].forEach((t) => d.table(t));
  const load = (file, line, table, rowCount, borrowedFrom) => {
    const id = d.s.n('sql:stmt:' + file + ':' + line, 'SQL_STATEMENT', file + ':' + line, file + ':' + line,
      Object.assign({ statementType: 'INSERT', dataLoad: true, rowCount }, borrowedFrom ? { borrowedFrom } : {}), d.at(file, line));
    d.s.e(id, 'WRITES', 'db:table:' + table, { op: 'INSERT' });
    return id;
  };
  load('db/oracle/data.sql', 1, 'T_CODE', 10);
  const borrowed = load('db/mysql/data.sql', 1, 'T_A', 3, 'mysql');
  load('db/mysql/data.sql', 9, 'T_B', 4, 'mysql');
  load('db/hsql/data.sql', 1, 'T_B', 1, 'hsql');
  const mapped = d.statement('com.n.CodeMapper', 'selectPage', 'SELECT', 'T_CODE', { borrowedFrom: 'mysql' });
  const dx = graph.buildIndex(d.graph());
  const data = diag.collect(dx);
  assert.deepEqual(data.borrowedStatements.map((x) => x.id), [mapped], '보충한 구문에는 매퍼 구문만');
  assert.deepEqual(data.dataLoadFiles.map((f) => [f.file, f.borrowedNote, f.tableCount, f.rowsText]),
    [['db/hsql/data.sql', '다른 방언 파일: hsql', 1, '1'], ['db/mysql/data.sql', '다른 방언 파일: mysql', 2, '7'], ['db/oracle/data.sql', '', 1, '10']]);
  assert.equal(data.dataLoadSummary.borrowedText, '이 가운데 묶음 3개는 고른 방언에 그 테이블의 적재가 없어 다른 방언 파일의 것이다(hsql 1개, mysql 2개).');
  // 꼬리표·요약·콜트리 줄: 적재 묶음은 "보충"이 아니라 "다른 방언 파일"이다
  assert.ok(graph.nodeTags(dx.node(borrowed)).some((t) => t.text === '다른 방언 파일: mysql'), JSON.stringify(graph.nodeTags(dx.node(borrowed))));
  assert.ok(!graph.nodeTags(dx.node(borrowed)).some((t) => /보충/.test(t.text)));
  assert.match(graph.nodeSummary(dx.node(borrowed)), /다른 방언 파일: mysql/);
  assert.equal(callers(dx, 'db:table:T_A', { includeDataLoad: true }), lines('[table] T_A', '└─ [sql] db/mysql/data.sql:1 <INSERT> (다른 방언 파일: mysql)'),
    '테스트 도구는 위 방향의 구문 줄에 접근 글자(C)를 적지 않는다: 표준 산출물은 (C, 다른 방언 파일: mysql)');
});

function erdBuilder() {
  const s = slice();
  return {
    s,
    table(name, attrs) { return s.n('db:table:' + name, 'DB_TABLE', name, name, attrs || {}); },
    column(t, name, attrs) {
      s.n('db:column:' + t + '.' + name, 'DB_COLUMN', name, t + '.' + name, attrs);
      s.e('db:table:' + t, 'CONTAINS', 'db:column:' + t + '.' + name);
    },
    fk(child, parent, constraint, columns) {
      s.e('db:table:' + child, 'REFERENCES', 'db:table:' + parent, { columns, refColumns: columns, source: 'DDL', constraint });
    },
    inferred(child, parent, columns, more) {
      s.e('db:table:' + child, 'REFERENCES', 'db:table:' + parent, Object.assign({ columns, refColumns: columns, source: 'INFERRED', directionKnown: true }, more), 'INFERRED');
    },
    index() { return graph.buildIndex(s.graph()); }
  };
}

/** ErdRound7Test.orders와 같은 그래프. */
function orders() {
  const b = erdBuilder();
  const pk = { dataType: 'NUMBER', ordinal: 1, nullable: false, primaryKey: true };
  b.table('CUSTOMER');
  b.column('CUSTOMER', 'CUSTOMER_ID', pk);
  b.table('ORDERS');
  b.column('ORDERS', 'ORDER_ID', pk);
  b.column('ORDERS', 'CUSTOMER_ID', { dataType: 'NUMBER', ordinal: 2, nullable: false });
  b.column('ORDERS', 'COUPON_ID', { dataType: 'NUMBER', ordinal: 3, nullable: true });
  b.fk('ORDERS', 'CUSTOMER', 'FK_ORDERS_CUSTOMER', ['CUSTOMER_ID']);
  b.table('COUPON');
  b.column('COUPON', 'COUPON_ID', pk);
  b.fk('ORDERS', 'COUPON', 'FK_ORDERS_COUPON', ['COUPON_ID']);
  b.table('ORDER_HIST');
  b.column('ORDER_HIST', 'HIST_ID', pk);
  b.column('ORDER_HIST', 'ORDER_ID', { dataType: 'NUMBER', ordinal: 2, nullable: false });
  b.inferred('ORDER_HIST', 'ORDERS', ['ORDER_ID'], { evidenceCount: 2 });
  b.table('ORDER_EXT');
  b.column('ORDER_EXT', 'ORDER_ID', pk);
  b.inferred('ORDER_EXT', 'ORDERS', ['ORDER_ID'], { evidenceCount: 1 });
  b.table('PAY');
  b.column('PAY', 'ORDER_ID', { dataType: 'NUMBER', ordinal: 1, nullable: false });
  b.inferred('PAY', 'ORDERS', ['ORDER_ID'], { evidenceCount: 3, cardinality: 'N:1' });
  return b;
}

const INFERRED_LEGEND = "    %% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. 추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.\n";

test('F-016-AC79 ERD: 추론 관계는 부모 쪽을 "0 또는 1"(|o)로 그린다(화면의 ERD와 표준 산출물의 글자 둘 다). DDL 외래 키는 FK 컬럼의 NOT NULL로 정한다 (F-013-AC31)', () => {
  const ix = orders().index();
  const model = erd.exportModel(ix, {});
  const text = erd.exportMermaid(ix, model.entities, model.relations, {});
  assert.ok(text.includes('    ORDERS |o..o{ ORDER_HIST : "ORDER_ID (추론 2건)"\n'), text);
  assert.ok(text.includes('    ORDERS |o..o| ORDER_EXT : "ORDER_ID (추론 1건)"\n'), text);
  assert.ok(text.includes('    ORDERS |o..o{ PAY : "ORDER_ID (추론 3건)"\n'), 'cardinality=N:1이어도 부모 쪽은 0..1: ' + text);
  assert.ok(text.includes('    CUSTOMER ||--o{ ORDERS : "FK_ORDERS_CUSTOMER"\n'), text);
  assert.ok(text.includes('    COUPON |o--o{ ORDERS : "FK_ORDERS_COUPON"\n'), text);
  assert.ok(!text.includes('||..'), text);
  assert.ok(text.includes(INFERRED_LEGEND), text);
  assert.equal(model.relations.find((r) => r.child === 'ORDER_HIST').parentSide, 'zeroOrOne');
  // 화면의 ERD
  const source = erd.toMermaid(ix, erd.selectScope(ix, {}), {}).source;
  assert.ok(source.includes('  ORDERS |o..o{ ORDER_HIST : "ORDER_ID (추론 2건)"\n'), source);
  assert.ok(source.includes('  ORDERS |o..o| ORDER_EXT : "ORDER_ID (추론 1건)"\n'), source);
  assert.ok(source.includes('  ORDERS |o..o{ PAY : '), source);
  assert.ok(source.includes('  CUSTOMER ||--o{ ORDERS : "FK_ORDERS_CUSTOMER"\n'), source);
  assert.ok(!source.includes('||..'), source);
  // 범례의 설명에도 적는다
  assert.ok(erd.LEGEND.find((x) => x.key === 'inferred').description.includes('0 또는 1'));
});

test('F-016-AC80 ERD: 시스템 카탈로그(systemCatalog=true) 테이블과 그 사이의 추론 관계는 그리지 않고 뺀 수를 알린다. DDL에 없는 다른 테이블은 그대로 그린다 (F-013-AC32)', () => {
  const b = orders();
  b.table('USER_TABLES', { declared: false, systemCatalog: true });
  b.table('USER_TAB_COLUMNS', { declared: false, systemCatalog: true });
  b.inferred('USER_TAB_COLUMNS', 'USER_TABLES', ['TABLE_NAME'], { evidenceCount: 2 });
  b.table('TMP_GHOST', { declared: false });
  const ix = b.index();
  const model = erd.exportModel(ix, {});
  assert.deepEqual(model.systemCatalogs, ['USER_TABLES', 'USER_TAB_COLUMNS']);
  assert.ok(!model.entities.some((e) => /^USER_/.test(e.name)) && model.entities.some((e) => e.name === 'TMP_GHOST'));
  assert.ok(!model.relations.some((r) => r.parent === 'USER_TABLES'));
  const text = erd.exportMermaid(ix, model.entities, model.relations, { systemCatalogs: model.systemCatalogs.length });
  assert.ok(text.includes('    %% CodeAtlas ERD: 엔티티 7개, 관계 5개 (추론 3개)\n'), text);
  assert.ok(text.includes('    %% 시스템 카탈로그 2개(DB가 제공하는 데이터 사전)는 프로젝트 스키마가 아니라서 뺐다.\n'), text);
  assert.ok(!text.includes('USER_TABLES') && text.includes('TMP_GHOST'), text);
  // 표준 산출물이 나눌지 정하는 전체 글자 수에도 그 줄이 든다
  assert.equal(erd.splitAreas(ix, {}).fullChars, text.length);
  // 화면의 ERD: 전체 범위와 고른 테이블의 이웃 어디에도 넣지 않는다
  const all = erd.selectScope(ix, {});
  assert.ok(!all.tables.some((t) => /^USER_/.test(t.name)) && all.tables.some((t) => t.name === 'TMP_GHOST'));
  assert.equal(all.systemCatalogs, 2);
  assert.ok(!all.relations.some((r) => r.to === 'db:table:USER_TABLES'));
  assert.ok(!erd.toMermaid(ix, all, {}).source.includes('USER_TABLES'));
  assert.equal(erd.selectScope(ix, { tables: ['db:table:USER_TABLES'], depth: 1 }).tables.length, 0);
  assert.equal(erd.selectScope(orders().index(), {}).systemCatalogs, 0);
});

test('F-016-AC81 콜트리·상세: 좁혀진 동적 뷰(RENDERS의 dynamicSegment + narrowedBy)는 "후보"가 아니라 좁힌 근거를 적는다. narrowedBy가 없는 것만 (동적 뷰 이름 후보)다 (F-015-AC89)', () => {
  const j = javaGraph();
  j.type('com.n.BlogController');
  const handler = j.method('com.n.BlogController', 'index');
  const byProperty = page(j, 'src/main/webapp/cms/blog/index.jsp');
  const byModule = page(j, 'src/main/webapp/upms/blog/index.jsp');
  const candidate = page(j, 'src/main/webapp/mobile/blog/index.jsp');
  const at = { file: 'src/main/java/com/n/BlogController.java', startLine: 30, startColumn: 16, endLine: 30, endColumn: 16 };
  const viewName = '/${app.name}/blog/index.jsp';
  j.s.e(handler, 'RENDERS', byProperty, { dynamicSegment: true, narrowedBy: 'property', viewName }, 'INFERRED', at);
  j.s.e(handler, 'RENDERS', byModule, { dynamicSegment: true, narrowedBy: 'moduleName', viewName }, 'INFERRED', at);
  j.s.e(handler, 'RENDERS', candidate, { dynamicSegment: true, candidate: true, viewName }, 'INFERRED', at);
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, handler), lines(
    'BlogController#index()',
    '├─ ~ [jsp] src/main/webapp/cms/blog/index.jsp (동적 뷰 이름: 프로퍼티 값으로 정함)',
    '├─ ~ [jsp] src/main/webapp/mobile/blog/index.jsp (동적 뷰 이름 후보)',
    '└─ ~ [jsp] src/main/webapp/upms/blog/index.jsp (동적 뷰 이름: 모듈 이름으로 정함)'));
  assert.equal(callers(ix, byProperty), lines(
    '[jsp] src/main/webapp/cms/blog/index.jsp',
    '└─ ~ BlogController#index() (동적 뷰 이름: 프로퍼티 값으로 정함)'));
  const kids = calltree.expand(ix, calltree.createRoot(ix, handler, 'callees'));
  assert.deepEqual(kids.map((k) => k.link.label), ['RENDERS(동적 뷰 이름: 프로퍼티 값으로 정함)', 'RENDERS(동적 뷰 이름 후보)', 'RENDERS(동적 뷰 이름: 모듈 이름으로 정함)']);
  assert.deepEqual(kids.map((k) => k.link.inference), ['동적 조각이 든 뷰 이름을 프로퍼티 값으로 정함(narrowedBy=property)',
    '동적 조각이 든 뷰 이름의 후보(dynamicSegment)', '동적 조각이 든 뷰 이름을 모듈 이름으로 정함(narrowedBy=moduleName)']);
  // 엣지 요약
  const summaries = graph.nodeDetail(ix, handler).outgoing.find((g) => g.kind === 'RENDERS').items.map((i) => i.summary);
  assert.ok(summaries.some((s) => s.includes('프로퍼티 값으로 정한 동적 뷰 이름')) && summaries.some((s) => s.includes('모듈 이름으로 정한 동적 뷰 이름'))
    && summaries.some((s) => s.includes('동적 조각이 든 뷰 이름의 후보')), summaries.join(' | '));
});
