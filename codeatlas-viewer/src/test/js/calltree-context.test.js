'use strict';
// 2차 수정(2026-10-11)에서 바뀐 표준 산출물의 콜트리 규칙(codeatlas-export의 FlowNavigator, F-015-R28~R39)을 뷰어가 같게 따르는지 본다.
// 기대값은 표준 산출물 테스트(ReceiverNarrowingTest, DispatchNarrowingTest, ModuleAndMarkerTest)의 텍스트 트리와 같은 모양이다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { javaGraph, genericDao, reflection, policies, modules, accessors, dataLoads } = require('./fixtures/java-graph.js');
const tree = require('./tools/export-tree.js');

const graph = core('graph');
const calltree = core('calltree');
const crud = core('crud');

const lines = (...l) => l.join('\n') + '\n';
const callees = (ix, id, opts) => tree.text(ix, id, 'callees', opts);
const callers = (ix, id, opts) => tree.text(ix, id, 'callers', opts);

// ---------------------------------------------------------------- 수신자 문맥 (F-016-R23)

test('F-016-AC41 콜트리 수신자 문맥: 호출 엣지의 receiverType과 맞는 namespace의 상속 EXECUTES만 따라가고, 맞는 것이 없으면 전부 [?], 수신자 타입을 모르면 전부 따라간다', () => {
  const f = genericDao();
  const ix = graph.buildIndex(f.j.graph());
  assert.equal(callees(ix, f.getDictDirect), lines(
    '[endpoint] GET /dict/direct',
    '└─ DictController#direct()',
    '   └─ CrudDao#findList(Object)',
    '      └─ ~ [sql] DictDao.findList <SELECT>',
    '         └─ [table] SYS_DICT (R)'));
  // 수신자 타입이 없는 호출: 좁힐 문맥이 없으므로 전부(표시 없음)
  assert.equal(callees(ix, f.getRaw), lines(
    '[endpoint] GET /raw',
    '└─ RawController#all()',
    '   └─ CrudDao#findList(Object)',
    '      ├─ ~ [sql] DictDao.findList <SELECT>',
    '      │  └─ [table] SYS_DICT (R)',
    '      ├─ ~ [sql] LogDao.findList <SELECT>',
    '      │  └─ [table] SYS_LOG (R)',
    '      └─ ~ [sql] UserDao.findList <SELECT>',
    '         └─ [table] SYS_USER (R)'));
  // 수신자 타입은 있는데 그 타입의 구문이 하나도 없다: 전부 따라가되 [?]
  assert.equal(callees(ix, f.getOdd), lines(
    '[endpoint] GET /odd',
    '└─ OddController#x()',
    '   └─ CrudDao#findList(Object)',
    '      ├─ ~ [?] [sql] DictDao.findList <SELECT>',
    '      │  └─ [table] SYS_DICT (R)',
    '      ├─ ~ [?] [sql] LogDao.findList <SELECT>',
    '      │  └─ [table] SYS_LOG (R)',
    '      └─ ~ [?] [sql] UserDao.findList <SELECT>',
    '         └─ [table] SYS_USER (R)'));
  // 줄의 표식과 글로 내보내기
  const root = calltree.createRoot(ix, f.getOdd, 'callees');
  calltree.expandAll(ix, root, {});
  const uncertain = root.children[0].children[0].children[0];
  assert.equal(uncertain.link.receiverUncertain, true);
  assert.ok(calltree.markersOf(uncertain.link).includes('?'));
  assert.ok(calltree.toText(ix, root).split('\n').some((l) => l.includes('[?]') && l.includes('DictDao.findList')));
});

test('F-016-AC41 콜트리 수신자 문맥: 같은 메서드를 수신자 타입이 다른 두 곳에서 부르면 호출 지점마다 따로 펼치고, 수신자가 namespace의 하위 인터페이스여도 맞는다', () => {
  const f = genericDao();
  const j = f.j;
  const both = j.method(f.P + 'DictController', 'both');
  j.call(both, f.daoFindList, 70, 'interface', f.P + 'UserDao');
  j.call(both, f.daoFindList, 72, 'interface', f.P + 'LogDao');
  j.type(f.P + 'SubDictDao', { kind: 'interface' });
  j.typeEdge(f.P + 'SubDictDao', 'EXTENDS', f.P + 'DictDao');
  const sub = j.method(f.P + 'DictController', 'sub');
  j.call(sub, f.daoFindList, 80, 'interface', f.P + 'SubDictDao');
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, both), lines(
    'DictController#both()',
    '├─ CrudDao#findList(Object)',
    '│  └─ ~ [sql] UserDao.findList <SELECT>',
    '│     └─ [table] SYS_USER (R)',
    '└─ CrudDao#findList(Object)',
    '   └─ ~ [sql] LogDao.findList <SELECT>',
    '      └─ [table] SYS_LOG (R)'));
  const text = callees(ix, sub);
  assert.ok(text.includes('DictDao.findList') && !text.includes('UserDao.findList') && !text.includes('[?]'), text);
});

test('F-016-AC41 콜트리 수신자 문맥: 제네릭 상위 서비스 안의 DAO 호출은 하위 서비스가 묶는 DAO로 좁힌다 (super 호출, typeArguments, 참조 타입)', () => {
  const f = genericDao();
  const j = f.j;
  const both = j.method(f.P + 'DictController', 'both');
  j.call(both, f.serviceFindPage, 70, 'virtual', f.P + 'DictService');
  j.call(both, f.serviceFindPage, 71, 'virtual', f.P + 'UserService');
  j.call(both, f.serviceFindPage, 72, 'virtual', f.P + 'DictService');
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, f.getDict), lines(
    '[endpoint] GET /dict',
    '└─ DictController#list()',
    '   └─ CrudService#findPage(Object)',
    '      └─ CrudDao#findList(Object)',
    '         └─ ~ [sql] DictDao.findList <SELECT>',
    '            └─ [table] SYS_DICT (R)'));
  // 재정의한 메서드가 super.save를 부른다: 문맥은 호출한 쪽의 타입(UserService), 타입 인자(typeArguments)가 UserDao다(주입받는 LogDao는 아니다)
  assert.equal(callees(ix, f.postUser), lines(
    '[endpoint] POST /user',
    '└─ UserController#save()',
    '   └─ UserService#save(Object)',
    '      └─ CrudService#save(Object) (super)',
    '         └─ CrudDao#insert(Object)',
    '            └─ ~ [sql] UserDao.insert <INSERT>',
    '               └─ [table] SYS_USER (C)'));
  // 타입 인자를 모르면 그 서비스가 참조하는 DAO 전부(DEPENDS_ON, INJECTS)로 좁힌다
  assert.equal(callees(ix, f.postMix), lines(
    '[endpoint] POST /mix',
    '└─ MixController#save()',
    '   └─ CrudService#save(Object)',
    '      └─ CrudDao#insert(Object)',
    '         ├─ ~ [sql] DictDao.insert <INSERT>',
    '         │  └─ [table] SYS_DICT (C)',
    '         └─ ~ [sql] LogDao.insert <INSERT>',
    '            └─ [table] SYS_LOG (C)'));
  // 상위 서비스의 메서드에서 바로 시작하면 문맥이 없다: 전부(표시 없음)
  const fromService = callees(ix, f.serviceSave);
  ['DictDao.insert', 'UserDao.insert', 'LogDao.insert'].forEach((s) => assert.ok(fromService.includes(s), s));
  assert.ok(!fromService.includes('[?]'));
  // 문맥이 다르면 같은 메서드도 따로 펼친다. 문맥이 같은 호출은 한 줄이다
  assert.equal(callees(ix, both), lines(
    'DictController#both()',
    '├─ CrudService#findPage(Object)',
    '│  └─ CrudDao#findList(Object)',
    '│     └─ ~ [sql] DictDao.findList <SELECT>',
    '│        └─ [table] SYS_DICT (R)',
    '└─ CrudService#findPage(Object)',
    '   └─ CrudDao#findList(Object)',
    '      └─ ~ [sql] UserDao.findList <SELECT>',
    '         └─ [table] SYS_USER (R)'));
});

test('F-016-AC42 콜트리 수신자 문맥(위로): 구문에서 상속 메서드로 올라간 뒤 호출자는 수신자 타입(또는 그 서비스가 묶는 DAO)이 구문의 namespace와 맞는 것만 남는다', () => {
  const f = genericDao();
  const ix = graph.buildIndex(f.j.graph());
  assert.equal(callers(ix, f.dictFindList), lines(
    '[sql] DictDao.findList <SELECT>',
    '└─ ~ CrudDao#findList(Object)',
    '   ├─ CrudService#findPage(Object)',
    '   │  └─ DictController#list()',
    '   │     └─ [endpoint] GET /dict',
    '   ├─ DictController#direct()',
    '   │  └─ [endpoint] GET /dict/direct',
    '   ├─ [?] OddController#x()',
    '   │  └─ [endpoint] GET /odd',
    '   └─ RawController#all()',
    '      └─ [endpoint] GET /raw'));
  assert.equal(callers(ix, f.userInsert), lines(
    '[sql] UserDao.insert <INSERT>',
    '└─ ~ CrudDao#insert(Object)',
    '   └─ CrudService#save(Object)',
    '      └─ UserService#save(Object) (super)',
    '         └─ UserController#save()',
    '            └─ [endpoint] POST /user'));
  const log = callers(ix, f.logInsert);
  assert.ok(log.includes('MixController#save()') && !log.includes('UserService#save') && !log.includes('[?]'), log);
});

test('F-016-AC42 CRUD 엔드포인트 기준도 같은 규칙으로 좁힌다: 엔드포인트마다 자기 DAO의 테이블만 나온다', () => {
  const f = genericDao();
  const ix = graph.buildIndex(f.j.graph());
  const m = crud.buildMatrix(ix, { rowKind: 'endpoint' });
  const cells = (id) => {
    const row = m.rows.find((r) => r.id === id);
    return row ? Object.keys(row.cells).map((t) => graph.idRemainder(t) + '=' + row.cells[t]).sort() : [];
  };
  assert.deepEqual(cells(f.getDict), ['SYS_DICT=R']);
  assert.deepEqual(cells(f.getDictDirect), ['SYS_DICT=R']);
  assert.deepEqual(cells(f.postUser), ['SYS_USER=C']);
  // 대상 하나로 좁히지 못한 칸(DAO 둘을 참조하는 서비스, 수신자 타입 미상, [?])에는 ?가 붙는다(F-016-AC66)
  assert.deepEqual(cells(f.postMix), ['SYS_DICT=C?', 'SYS_LOG=C?']);
  assert.deepEqual(cells(f.getRaw), ['SYS_DICT=R?', 'SYS_LOG=R?', 'SYS_USER=R?']);
  assert.deepEqual(cells(f.getOdd), ['SYS_DICT=R?', 'SYS_LOG=R?', 'SYS_USER=R?']);
  // 메서드 기준은 좁히지 않는다: 상속 메서드의 행에는 모든 하위 매퍼의 테이블이 나온다(F-015 5절)
  const methods = crud.buildMatrix(ix, { rowKind: 'method' });
  assert.deepEqual(Object.keys(methods.rows.find((r) => r.id === f.daoFindList).cells).map(graph.idRemainder).sort(), ['SYS_DICT', 'SYS_LOG', 'SYS_USER']);
});

test('F-016-AC43 콜트리: 리플렉션으로 추론한 호출은 [reflect]로 표시하고, 팬아웃(viaTypes)은 문맥의 타입이 든 것만 따라간다. 문맥이 없으면 전부 [?]', () => {
  const f = reflection();
  const ix = graph.buildIndex(f.j.graph());
  assert.equal(callees(ix, f.controller), lines(
    'UserController#list()',
    '└─ BaseServiceImpl#selectAll()',
    '   └─ ~ [reflect] UserMapper#selectAll()',
    '      └─ [sql] UserMapper.selectAll <SELECT>',
    '         └─ [table] T_USER (R)'));
  const byInterface = callees(ix, f.byInterface);
  assert.ok(byInterface.includes('[reflect] RoleMapper#selectAll()') && !byInterface.includes('UserMapper'), byInterface);
  assert.equal(callees(ix, f.direct), lines(
    'Tool#invoke()',
    '└─ ~ [reflect] UserMapper#selectAll()',
    '   └─ [sql] UserMapper.selectAll <SELECT>',
    '      └─ [table] T_USER (R)'));
  // 문맥 없이 시작하면 전부 따라가되 [?]
  assert.equal(callees(ix, f.select), lines(
    'BaseServiceImpl#selectAll()',
    '├─ ~ [?] [reflect] RoleMapper#selectAll()',
    '│  └─ [sql] RoleMapper.selectAll <SELECT>',
    '│     └─ [table] T_ROLE (R)',
    '└─ ~ [?] [reflect] UserMapper#selectAll()',
    '   └─ [sql] UserMapper.selectAll <SELECT>',
    '      └─ [table] T_USER (R)'));
  // 줄의 표식
  const kids = calltree.expand(ix, calltree.createRoot(ix, f.select, 'callees'));
  assert.deepEqual(calltree.markersOf(kids[0].link), ['?', 'reflect']);
  // 위로도 대칭이다
  const role = callers(ix, 'db:table:T_ROLE');
  assert.ok(role.includes('~ [reflect] BaseServiceImpl#selectAll()') && role.includes('UserController#byInterface()'), role);
  assert.ok(!role.includes('UserController#list()') && !role.includes('Tool#invoke'), role);
  const user = callers(ix, 'db:table:T_USER');
  assert.ok(user.includes('UserController#list()') && user.includes('~ [reflect] Tool#invoke()') && !user.includes('byInterface'), user);
});

// ---------------------------------------------------------------- 주입 좁히기 확장, 추론 OVERRIDES (F-016-R24)

test('F-016-AC44 콜트리 주입 좁히기: 구현 구체 타입으로 바로 가는 INJECTS(XML 주입, 구현 클래스 필드)도 후보다. 인터페이스로 가는 후보 없는 주입이 함께 있으면 좁히지 않는다', () => {
  const f = policies();
  const ix = graph.buildIndex(f.j.graph());
  const impls = (id, opts) => {
    const root = calltree.createRoot(ix, id, 'callees', opts);
    const call = calltree.expand(ix, root).find((c) => c.id === f.discount);
    return calltree.expand(ix, call).filter((c) => c.link.impl).map((c) => c.id);
  };
  assert.deepEqual(impls(f.xml), [f.vipDiscount]);
  assert.deepEqual(impls(f.field), [f.defaultDiscount]);
  assert.deepEqual(impls(f.none), [f.defaultDiscount, f.vipDiscount]);
  assert.deepEqual(impls(f.mixed), [f.defaultDiscount, f.vipDiscount]);
  assert.deepEqual(impls(f.xml, { allImplementations: true }), [f.defaultDiscount, f.vipDiscount], '"모든 구현 보기"면 좁히지 않는다');
  // 위로도 같다: 다른 구현을 주입받는 호출자는 빠진다
  const up = (id) => calltree.expand(ix, calltree.createRoot(ix, id, 'callers')).filter((c) => c.link.viaInterface).map((c) => c.id).sort();
  assert.deepEqual(up(f.vipDiscount), [f.mixed, f.none, f.xml].sort());
  assert.deepEqual(up(f.defaultDiscount), [f.field, f.mixed, f.none].sort());
});

test('F-016-AC44 콜트리: 추론 OVERRIDES로 이어진 구현(그것을 거친 간접 구현 포함)은 ~로 표시하고, super로 도착한 메서드 아래에는 구현을 펼치지 않는다', () => {
  const j = javaGraph();
  const P = 'com.o.';
  j.type(P + 'Api', { kind: 'interface' });
  j.type(P + 'Base');
  j.type(P + 'Leaf');
  j.type(P + 'Sure');
  const api = j.method(P + 'Api', 'run', [], { modifiers: ['public', 'default'] });
  const base = j.method(P + 'Base', 'run');
  const leaf = j.method(P + 'Leaf', 'run');
  const sure = j.method(P + 'Sure', 'run');
  j.s.e(base, 'OVERRIDES', api, {}, 'INFERRED');
  j.s.e(leaf, 'OVERRIDES', base);
  j.s.e(sure, 'OVERRIDES', api);
  j.type(P + 'Caller');
  const caller = j.method(P + 'Caller', 'go');
  j.call(caller, api, 5, 'interface');
  // Sure#run이 인터페이스의 default 메서드를 super로 부른다
  j.call(sure, api, 8, 'super');
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, caller), lines(
    'Caller#go()',
    '└─ Api#run()',
    '   ├─ ~ [impl] Base#run()',
    '   ├─ ~ [impl] Leaf#run()',
    '   └─ [impl] Sure#run()',
    '      └─ Api#run() (super) [cycle]'));
  // super로 도착한 인터페이스의 default 메서드: 상위 구현을 명시적으로 부른 것이라 구현으로 펼치지 않는다
  assert.equal(callees(ix, sure), lines(
    'Sure#run()',
    '└─ Api#run() (super)'));
  const impl = calltree.expand(ix, calltree.expand(ix, calltree.createRoot(ix, caller, 'callees'))[0]);
  assert.deepEqual(impl.map((k) => k.link.confidence), ['INFERRED', 'INFERRED', 'RESOLVED']);
});

// ---------------------------------------------------------------- 모듈, 표시 이름 (F-016-R25)

test('F-016-AC45 콜트리 멀티모듈: 요청에서 내려가면 같은 모듈의 핸들러만, 엔드포인트에서 시작하면 전부를 (모듈 …)과 함께 보이고 crossModule MATCHES는 ~다', () => {
  const f = modules();
  const ix = graph.buildIndex(f.j.graph());
  const root = calltree.createRoot(ix, f.adminJsp, 'callees');
  calltree.expandAll(ix, root, {});
  const handlers = [];
  (function walk(t) { if (t.link && t.link.kind === 'ROUTES_TO') handlers.push(t.id); (t.children || []).forEach(walk); })(root);
  assert.deepEqual(handlers, [f.admin], 'web-admin 화면에서는 web-admin의 핸들러만');
  assert.equal(callees(ix, f.adminJsp), lines(
    '[jsp] web-admin/src/main/webapp/WEB-INF/views/user.jsp',
    '└─ [request] GET /user/list',
    '   └─ [endpoint] GET /user/list',
    '      └─ UserController#list()'));
  assert.equal(callees(ix, f.otherJsp), lines(
    '[jsp] web-other/src/main/webapp/index.jsp',
    '└─ [request] GET /user/list',
    '   └─ ~ [endpoint] GET /user/list',
    '      ├─ UserController#list() (모듈 web-admin)',
    '      └─ UserController#list() (모듈 web-front)'));
  assert.equal(callees(ix, f.endpoint), lines(
    '[endpoint] GET /user/list',
    '├─ UserController#list() (모듈 web-admin)',
    '└─ UserController#list() (모듈 web-front)'));
  // 줄의 관계 이름에 모듈이 적힌다
  const kids = calltree.expand(ix, calltree.createRoot(ix, f.endpoint, 'callees'));
  assert.deepEqual(kids.map((k) => k.link.detail), ['모듈 web-admin', '모듈 web-front']);
  assert.ok(kids.every((k) => k.link.label.includes('모듈 web-')), kids.map((k) => k.link.label).join());
  // 위로도 같다: admin 핸들러의 호출자에 front 화면의 요청은 없다
  const up = callers(ix, f.admin);
  assert.ok(up.includes('web-admin/src/main/webapp/WEB-INF/views/user.jsp') && up.includes('web-other/src/main/webapp/index.jsp'), up);
  assert.ok(!up.includes('web-front/'), up);
});

test('F-016-AC45 엔드포인트의 표시 이름: resolvedPath가 있으면 `METHOD 치환경로 (원문)`으로 콜트리와 CRUD의 행·CSV에 적힌다', () => {
  const j = javaGraph();
  j.table('SYS_USER');
  const stmt = j.statement('com.r.UserDao', 'findList', 'SELECT', 'SYS_USER');
  j.type('com.r.UserController');
  const handler = j.method('com.r.UserController', 'list');
  j.s.e(handler, 'EXECUTES', stmt);
  const endpoint = j.endpoint('ANY', '/${adminPath}/sys/user/list', handler, { resolvedPath: '/a/sys/user/list', placeholders: ['adminPath'] });
  const plain = j.endpoint('GET', '/plain', handler);
  const ix = graph.buildIndex(j.graph());
  assert.equal(graph.endpointLabel(ix.node(endpoint)), 'ANY /a/sys/user/list (/${adminPath}/sys/user/list)');
  assert.equal(graph.endpointLabel(ix.node(plain)), 'GET /plain');
  assert.equal(graph.shortLabel(ix.node(endpoint), endpoint), 'ANY /a/sys/user/list (/${adminPath}/sys/user/list)');
  const m = crud.buildMatrix(ix, { rowKind: 'endpoint' });
  assert.deepEqual(m.rows.map((r) => r.exportLabel), ['ANY /a/sys/user/list (/${adminPath}/sys/user/list)', 'GET /plain']);
  assert.deepEqual(m.rows.map((r) => r.label), ['ANY /a/sys/user/list (/${adminPath}/sys/user/list)', 'GET /plain']);
  assert.ok(crud.toCsv(m, {}).includes('\nANY /a/sys/user/list (/${adminPath}/sys/user/list),,R\n'));
  // 치환한 경로로도 찾는다
  const search = core('search');
  assert.deepEqual(search.search(ix, { text: '/a/sys/user', kinds: ['HTTP_ENDPOINT'] }).map((n) => n.id), [endpoint]);
});

// ---------------------------------------------------------------- 자식 순서, [before] (F-016-R26)

test('F-016-AC46 콜트리 자식 순서: [before]가 맨 앞, 그다음 호출 지점 위치순(줄, 열), 위치 없는 호출과 구현·테이블은 그 뒤에 ID 순이다. 호출하는 쪽은 ID 순이다', () => {
  const j = javaGraph();
  const P = 'com.b.';
  j.type(P + 'ItemController', { attrs: { stereotype: 'controller' } });
  const handler = j.method(P + 'ItemController', 'list');
  const populate = j.method(P + 'ItemController', 'populate');
  const another = j.method(P + 'ItemController', 'another');
  j.type(P + 'Svc');
  const m = {};
  ['zeta', 'alpha', 'mid', 'late', 'noLocB', 'noLocA'].forEach((name) => { m[name] = j.method(P + 'Svc', name); });
  const file = 'src/main/java/com/b/ItemController.java';
  const call = (to, line, column, type, confidence) => j.s.e(handler, 'CALLS', to, { callType: type || 'virtual' }, confidence, line ? j.at(file, line, column) : null);
  // 입력 순서는 일부러 뒤섞는다
  call(m.late, 20, 3);
  call(m.noLocB, 0);
  call(m.alpha, 10, 30);
  call(populate, 90, 1, 'modelAttribute', 'INFERRED');
  call(m.zeta, 10, 5);
  call(m.noLocA, 0);
  call(another, 70, 1, 'modelAttribute', 'INFERRED');
  call(m.mid, 12, 9);
  call(m.zeta, 30, 1); // 같은 대상을 다시 부른다: 처음 부른 자리에 한 번
  j.table('T_B');
  j.table('T_A');
  const stmt = j.statement(P + 'ItemMapper', 'select', 'SELECT', 'T_B');
  j.s.e(stmt, 'READS', 'db:table:T_A');
  j.s.e(handler, 'EXECUTES', stmt, {}, 'RESOLVED', j.at(file, 15, 1));
  const ix = graph.buildIndex(j.graph());
  assert.equal(callees(ix, handler), lines(
    'ItemController#list()',
    '├─ ~ [before] ItemController#another()',
    '├─ ~ [before] ItemController#populate()',
    '├─ Svc#zeta()',
    '├─ Svc#alpha()',
    '├─ Svc#mid()',
    '├─ [sql] ItemMapper.select <SELECT>',
    '│  ├─ [table] T_A (R)',
    '│  └─ [table] T_B (R)',
    '├─ Svc#late()',
    '├─ Svc#noLocA()',
    '└─ Svc#noLocB()'));
  const kids = calltree.expand(ix, calltree.createRoot(ix, handler, 'callees'));
  assert.deepEqual(calltree.markersOf(kids[0].link), ['before']);
  assert.equal(kids.find((k) => k.id === m.zeta).link.count, 2, '같은 대상을 여러 번 부르면 한 줄에 횟수가 남는다');
  // 호출하는 쪽: ID 순. [before]는 핸들러 줄에 붙는다
  const zetaCallers = j.method(P + 'Svc', 'zzCaller');
  j.s.e(zetaCallers, 'CALLS', m.zeta, { callType: 'virtual' }, 'RESOLVED', j.at('src/main/java/com/b/Svc.java', 1));
  const ix2 = graph.buildIndex(j.graph());
  assert.deepEqual(calltree.expand(ix2, calltree.createRoot(ix2, m.zeta, 'callers')).map((k) => k.id), [handler, zetaCallers]);
  assert.equal(callers(ix2, populate), lines('ItemController#populate()', '└─ ~ [before] ItemController#list()'));
});

// ---------------------------------------------------------------- 접기와 데이터 적재 (F-016-R27)

test('F-016-AC47 콜트리 접기: 접근자 호출(Lombok 합성 멤버, 대응하는 필드가 있고 호출 없는 get/set/is+대문자)과 소스 없는 메서드의 호출 줄을 기본으로 접고, 옵션으로 편다', () => {
  const f = accessors();
  const ix = graph.buildIndex(f.j.graph());
  const kids = (opts) => calltree.expand(ix, calltree.createRoot(ix, f.run, 'callees', opts)).map((k) => k.id);
  // 부수 효과 없는 생성자(Vo#<init>)도 접근자와 함께 접는다(F-015-R57)
  assert.deepEqual(kids(), [f.getTotal, f.settle, f.issue, f.getKey], '기본값은 표준 산출물과 같다');
  assert.deepEqual(kids({ showAccessors: true }), [f.getName, f.isOpen, f.setCode, f.builder, f.getTotal, f.settle, f.issue, f.ctor, f.getKey]);
  assert.deepEqual(kids({ showExternal: true }), [f.getTotal, f.settle, f.issue, f.getKey, f.extMethod, f.inherited]);
  assert.equal(kids({ showAccessors: true, showExternal: true }).length, 11);
  // 판정 함수
  assert.equal(calltree.isAccessor(ix, f.getName), true);
  assert.equal(calltree.isAccessor(ix, f.builder), true, 'Lombok이 만들 멤버는 이름과 무관하다');
  assert.equal(calltree.isAccessor(ix, f.getTotal), false);
  assert.equal(calltree.isAccessor(ix, f.getKey), false, '재정의한 메서드가 호출을 한다');
  assert.equal(calltree.isAccessor(ix, f.settle), false);
  assert.equal(calltree.isAccessor(ix, f.ctor), true, '다른 메서드를 부르지 않는 생성자는 접는다(F-015-R57)');
  assert.equal(calltree.isExternal(ix, f.inherited), true);
  // 접은 수를 알 수 있다
  const hidden = calltree.hiddenCounts(ix, f.run, 'callees', {});
  assert.deepEqual(hidden, { accessors: 5, external: 2, dataLoad: 0, layoutUsers: 0, routes: 0, builders: 0 });
  // 시작 노드와 호출하는 쪽은 접근자를 접지 않는다
  assert.deepEqual(calltree.expand(ix, calltree.createRoot(ix, f.getName, 'callers')).map((k) => k.id), [f.run]);
});

test('F-016-AC48 데이터 적재 구문(dataLoad=true)은 콜트리와 CRUD 구문 기준에서 기본으로 빼고 옵션으로 넣는다', () => {
  const f = dataLoads();
  const ix = graph.buildIndex(f.j.graph());
  const up = (opts) => calltree.expand(ix, calltree.createRoot(ix, 'db:table:SYS_USER', 'callers', opts)).map((k) => k.id);
  assert.deepEqual(up(), [f.select]);
  assert.deepEqual(up({ includeDataLoad: true }), [f.select, f.loadUser].sort());
  assert.deepEqual(calltree.hiddenCounts(ix, 'db:table:SYS_USER', 'callers', {}), { accessors: 0, external: 0, dataLoad: 1, layoutUsers: 0, routes: 0, builders: 0 });
  const m = crud.buildMatrix(ix, { rowKind: 'statement' });
  assert.deepEqual(m.rows.map((r) => r.id), [f.select]);
  assert.deepEqual(m.tables.map((t) => t.name), ['SYS_USER'], '적재만 되는 테이블은 열이 되지 않는다');
  assert.equal(m.dataLoadExcluded, 4, '뺀 구문 수를 알린다');
  const all = crud.buildMatrix(ix, { rowKind: 'statement', includeDataLoad: true });
  assert.equal(all.rows.length, 5);
  assert.deepEqual(all.tables.map((t) => t.name), ['SYS_AREA', 'SYS_DICT', 'SYS_USER']);
  assert.equal(all.dataLoadExcluded, 0);
});

test('F-016-AC48 CRUD 구문 기준: 값 자리에서 사용자 정의 함수를 부르는 데이터 적재 구문도 행이 되지 않는다(그 함수가 접근하는 테이블 때문에 끼어들지 않는다)', () => {
  const f = dataLoads();
  const j = f.j;
  // 적재 스크립트의 INSERT가 VALUES에서 FN_NEXT_ID()를 부르고, 그 함수가 SYS_DICT를 갱신한다
  const fn = j.s.n('db:routine:FN_NEXT_ID', 'DB_ROUTINE', 'FN_NEXT_ID', 'FN_NEXT_ID', { routineType: 'FUNCTION' });
  const inner = j.s.n('sql:stmt:FN_NEXT_ID:4', 'SQL_STATEMENT', 'FN_NEXT_ID:4', 'FN_NEXT_ID:4', { statementType: 'UPDATE', routine: fn });
  j.s.e(fn, 'CONTAINS', inner);
  j.s.e(inner, 'WRITES', 'db:table:SYS_DICT', { op: 'UPDATE' });
  j.s.e(f.loadUser, 'CALLS', fn);
  const ix = graph.buildIndex(j.graph());
  assert.deepEqual(crud.buildMatrix(ix, { rowKind: 'statement' }).rows.map((r) => r.id), [f.select, inner].sort());
  const all = crud.buildMatrix(ix, { rowKind: 'statement', includeDataLoad: true });
  assert.equal(all.rows.find((r) => r.id === f.loadUser).cells['db:table:SYS_DICT'], 'u', '넣으면 함수가 하는 접근이 소문자로 나온다');
  // 루틴에서 위로 올라갈 때도 적재 구문은 기본으로 빠진다
  assert.deepEqual(calltree.expand(ix, calltree.createRoot(ix, fn, 'callers')).map((k) => k.id), []);
  assert.deepEqual(calltree.expand(ix, calltree.createRoot(ix, fn, 'callers', { includeDataLoad: true })).map((k) => k.id), [f.loadUser]);
});

// ---------------------------------------------------------------- 필터·인터셉터 진입점 (F-016-R28)

test('F-016-AC49 콜트리 시작 노드: 필터·인터셉터(stereotype=filter|interceptor)의 진입 메서드를 묶음으로 준다', () => {
  const j = javaGraph();
  const P = 'com.f.';
  j.type(P + 'LoginInterceptor', { attrs: { stereotype: 'interceptor' } });
  const pre = j.method(P + 'LoginInterceptor', 'preHandle', ['javax.servlet.http.HttpServletRequest']);
  const after = j.method(P + 'LoginInterceptor', 'afterCompletion');
  j.method(P + 'LoginInterceptor', 'helper');
  j.type(P + 'EncodingFilter', { attrs: { stereotype: 'filter' } });
  const doFilter = j.method(P + 'EncodingFilter', 'doFilter');
  j.method(P + 'EncodingFilter', 'init');
  j.type(P + 'EmptyFilter', { attrs: { stereotype: 'filter' } }); // 진입 메서드가 없는 타입
  j.method(P + 'EmptyFilter', 'destroy');
  j.type(P + 'OrderController', { attrs: { stereotype: 'controller' } });
  j.method(P + 'OrderController', 'preHandle'); // 필터·인터셉터가 아닌 타입의 같은 이름
  const ix = graph.buildIndex(j.graph());
  const entries = calltree.filterEntries(ix);
  assert.deepEqual(entries.map((e) => e.id), [doFilter, after, pre].sort());
  assert.deepEqual(entries.map((e) => e.stereotype), entries.map((e) => (e.id === doFilter ? 'filter' : 'interceptor')));
  assert.ok(entries.every((e) => e.typeId && e.label));
  // 없으면 빈 목록
  assert.deepEqual(calltree.filterEntries(graph.buildIndex({ nodes: [], edges: [] })), []);
});
