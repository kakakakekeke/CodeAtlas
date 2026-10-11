'use strict';
// 그래프 계약에 새로 들어온 속성(design 3.5 갱신분)과 진단이 뷰어에서 읽기 좋게 나오는지 본다.
// 조각의 값은 samples/sample-shop의 실제 graph.json에 나온 모양을 옮긴 것이다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { slice, loc } = require('./fixtures/slice.js');

const graph = core('graph');
const erd = core('erd');
const diag = core('diagnostics');

// ---------------------------------------------------------------- ERD의 고정 컬럼 (F-016-AC36)

function codeTables() {
  const s = slice();
  const table = (name, attrs) => s.n('db:table:' + name, 'DB_TABLE', name, name, attrs);
  const column = (t, name, attrs) => { const id = s.n('db:column:' + t + '.' + name, 'DB_COLUMN', name, t + '.' + name, attrs); s.e('db:table:' + t, 'CONTAINS', id); return id; };
  const f = { order: table('TB_ORDER', { primaryKey: ['ORDER_ID'] }), code: table('TB_CODE', { primaryKey: ['GROUP_CD', 'CODE'] }), pay: table('TB_PAYMENT', { primaryKey: ['PAYMENT_ID'] }) };
  column('TB_ORDER', 'ORDER_ID', { dataType: 'NUMBER(12)', ordinal: 1, nullable: false, primaryKey: true });
  column('TB_ORDER', 'ORDER_STATUS', { dataType: 'VARCHAR2(10)', ordinal: 2, nullable: false });
  column('TB_CODE', 'GROUP_CD', { dataType: 'VARCHAR2(30)', ordinal: 1, nullable: false, primaryKey: true });
  column('TB_CODE', 'CODE', { dataType: 'VARCHAR2(10)', ordinal: 2, nullable: false, primaryKey: true });
  column('TB_PAYMENT', 'PAYMENT_ID', { dataType: 'NUMBER(12)', ordinal: 1, nullable: false, primaryKey: true });
  column('TB_PAYMENT', 'PAY_METHOD_CD', { dataType: 'VARCHAR2(10)', ordinal: 2, nullable: true });
  f.stmt = s.n('mybatis:stmt:m.selectOrderList', 'SQL_STATEMENT', 'selectOrderList', 'm.selectOrderList', { statementType: 'SELECT' });
  s.e(f.order, 'REFERENCES', f.code, { cardinality: 'N:1', columns: ['ORDER_STATUS'], directionKnown: true, evidence: [f.stmt], evidenceCount: 1,
    fixedColumns: ['GROUP_CD'], fixedValues: ['GROUP_CD=ORDER_STATUS'], refColumns: ['CODE'], source: 'INFERRED' }, 'INFERRED');
  s.e(f.pay, 'REFERENCES', f.code, { cardinality: 'N:1', columns: ['PAY_METHOD_CD'], directionKnown: true, evidence: [f.stmt], evidenceCount: 2,
    fixedColumns: ['GROUP_CD'], fixedValues: ['GROUP_CD=PAY_METHOD', 'GROUP_CD=?'], refColumns: ['CODE'], source: 'INFERRED' }, 'INFERRED');
  s.e(f.code, 'JOINS', f.order, { leftColumns: ['CODE'], leftFixedColumns: ['GROUP_CD'], leftFixedValues: ['GROUP_CD=ORDER_STATUS'], rightColumns: ['ORDER_STATUS'],
    rightFixedColumns: ['ORDER_ID', 'USE_YN'], rightFixedValues: ['ORDER_ID=?', 'USE_YN=Y'], statement: f.stmt }, 'RESOLVED', loc('mapper/OrderMapper.xml', 70));
  f.graph = s.graph();
  return f;
}

test('F-016-AC36 ERD: 상수로 고정한 복합 키(fixedColumns·fixedValues)를 관계 라벨에 적는다', () => {
  const f = codeTables();
  const ix = graph.buildIndex(f.graph);
  const scope = erd.selectScope(ix, {});
  const labels = scope.relations.map((r) => erd.relationLabel(r)).sort();
  assert.deepEqual(labels, [
    "ORDER_STATUS → CODE (GROUP_CD='ORDER_STATUS', 추론 1건)",
    "PAY_METHOD_CD → CODE (GROUP_CD in ('PAY_METHOD', ?), 추론 2건)"
  ]);
  const order = scope.relations.find((r) => r.from === f.order);
  assert.deepEqual(order.fixedColumns, ['GROUP_CD']);
  assert.deepEqual(order.fixedValues, ['GROUP_CD=ORDER_STATUS']);
  const out = erd.toMermaid(ix, scope, {});
  assert.ok(out.source.includes("TB_CODE |o..o{ TB_ORDER : \"ORDER_STATUS → CODE (GROUP_CD='ORDER_STATUS', 추론 1건)\""), out.source);
  assert.ok(out.source.includes('TB_CODE |o..o{ TB_PAYMENT'), '추론 관계는 부모 쪽이 0..1: ' + out.source);
  const lines = erd.relationLines(ix, scope);
  assert.deepEqual(lines.map((l) => [ix.node(l.parentId).name, l.parentMarker, l.childMarker, ix.node(l.childId).name, l.dashed]),
    [['TB_CODE', '|o', 'o{', 'TB_ORDER', true], ['TB_CODE', '|o', 'o{', 'TB_PAYMENT', true]], '추론 관계의 부모 쪽은 0..1이다(F-013-R28)');
});

test('F-016-AC36 고정 컬럼 표기: 문자열은 따옴표, 바인드와 숫자는 그대로, 값이 여럿이면 in, 값을 모르면 "상수"', () => {
  assert.equal(erd.fixedText(['GROUP_CD'], ['GROUP_CD=ORDER_STATUS']), "GROUP_CD='ORDER_STATUS'");
  assert.equal(erd.fixedText(['ORDER_ID', 'USE_YN'], ['ORDER_ID=?', 'USE_YN=Y']), "ORDER_ID=?, USE_YN='Y'");
  assert.equal(erd.fixedText(['LVL'], ['LVL=1', 'LVL=2']), 'LVL in (1, 2)');
  assert.equal(erd.fixedText(['GROUP_CD'], []), 'GROUP_CD=상수');
  assert.equal(erd.fixedText(['A'], ['A=x=y']), "A='x=y'", '값 안의 =는 값이다');
  assert.equal(erd.fixedText([], undefined), '');
  assert.equal(erd.fixedText(undefined, ['B=1']), 'B=1', '값에만 나온 컬럼도 적는다');
});

// ---------------------------------------------------------------- 상세 패널 (F-016-AC38)

test('F-016-AC38 상세: REFERENCES의 고정 컬럼과 JOINS의 좌·우 고정 컬럼을 한 줄 요약으로 읽는다', () => {
  const f = codeTables();
  const ix = graph.buildIndex(f.graph);
  const d = graph.nodeDetail(ix, f.order);
  const ref = d.outgoing.find((g) => g.kind === 'REFERENCES').items[0];
  assert.equal(ref.summary, "ORDER_STATUS → CODE (GROUP_CD='ORDER_STATUS') · N:1 · 추론 1건");
  assert.deepEqual(ref.attrs.find((a) => a.key === 'fixedValues').items, [{ text: 'GROUP_CD=ORDER_STATUS', nodeId: null }]);
  const join = d.incoming.find((g) => g.kind === 'JOINS').items[0];
  assert.equal(join.summary, "TB_CODE.CODE = TB_ORDER.ORDER_STATUS · TB_CODE 고정 GROUP_CD='ORDER_STATUS' · TB_ORDER 고정 ORDER_ID=?, USE_YN='Y'");
  // 근거 구문은 요약 옆에서 바로 갈 수 있게 노드 ID로 남는다
  assert.deepEqual(join.attrs.find((a) => a.key === 'statement').items, [{ text: f.stmt, nodeId: f.stmt }]);
  // DDL 관계와 방향 미상 관계
  const s = slice();
  const a = s.n('db:table:A', 'DB_TABLE', 'A');
  const b = s.n('db:table:B', 'DB_TABLE', 'B');
  s.e(a, 'REFERENCES', b, { columns: ['B_ID'], constraint: 'FK_A_B', onDelete: 'CASCADE', refColumns: ['ID'], source: 'DDL' });
  s.e(a, 'REFERENCES', b, { cardinality: 'unknown', columns: ['X'], directionKnown: false, evidenceCount: 3, refColumns: ['X'], source: 'INFERRED' }, 'INFERRED');
  const refs = graph.nodeDetail(graph.buildIndex(s.graph()), a).outgoing.find((g) => g.kind === 'REFERENCES').items.map((i) => i.summary).sort();
  assert.deepEqual(refs, ['B_ID → ID · DDL FK_A_B · ON DELETE CASCADE', 'X = X · 방향 미상: 키 정보 없음 · 추론 3건']);
});

test('F-016-AC38 상세: 미해결 java-call의 인자 수(argCount), props로 받은 함수 호출(viaProp), redirect·forward 경로를 엣지 요약에 적는다', () => {
  const s = slice();
  const m = s.n('java:method:b.CloseOrderTasklet#execute()', 'JAVA_METHOD', 'execute', 'b.CloseOrderTasklet#execute()');
  const call = s.n('unresolved:java-call:chunkContext.getStepContext().getJobParameters().get(…)', 'UNRESOLVED',
    'chunkContext.getStepContext().getJobParameters().get(…)', 'chunkContext.getStepContext().getJobParameters().get(…)', { category: 'java-call' });
  s.e(m, 'CALLS', call, { argCount: 1, externalChain: true }, 'UNRESOLVED', loc('b/CloseOrderTasklet.java', 30));
  s.e(m, 'CALLS', call, { argCount: 0, externalChain: true }, 'UNRESOLVED', loc('b/CloseOrderTasklet.java', 31));
  const ref = s.n('unresolved:java-call:vo::nothing', 'UNRESOLVED', 'vo::nothing', 'vo::nothing', { category: 'java-call' });
  s.e(m, 'CALLS', ref, {}, 'UNRESOLVED', loc('b/CloseOrderTasklet.java', 32));
  const child = s.n('js:function:frontend/src/pages/OrderRow.jsx#OrderRow', 'JS_FUNCTION', 'OrderRow', 'frontend/src/pages/OrderRow.jsx#OrderRow', { component: true });
  const handler = s.n('js:function:frontend/src/pages/OrderList.jsx#OrderList.handleCancel', 'JS_FUNCTION', 'handleCancel', 'frontend/src/pages/OrderList.jsx#OrderList.handleCancel');
  s.e(child, 'CALLS', handler, { callType: 'call', viaProp: 'onCancel' }, 'INFERRED', loc('frontend/src/pages/OrderRow.jsx', 12));
  const save = s.n('java:method:w.OrderController#save()', 'JAVA_METHOD', 'save', 'w.OrderController#save()');
  const list = s.n('java:method:w.OrderController#list()', 'JAVA_METHOD', 'list', 'w.OrderController#list()');
  s.e(save, 'CALLS', list, { callType: 'redirect', path: '/order/list', viewName: 'redirect:/order/list' }, 'INFERRED');
  s.e(m, 'CALLS', list, { callType: 'forward', path: '/order/list' }, 'INFERRED');
  const ix = graph.buildIndex(s.graph());
  const calls = graph.nodeDetail(ix, m).outgoing.find((g) => g.kind === 'CALLS').items;
  assert.deepEqual(calls.filter((i) => i.otherId === call).map((i) => i.summary).sort(), ['미해결 호출 · 인자 0개', '미해결 호출 · 인자 1개']);
  assert.equal(calls.find((i) => i.otherId === ref).summary, '미해결 호출 · 인자 수 모름');
  assert.equal(calls.find((i) => i.otherId === list).summary, 'forward /order/list · 메서드가 넘긴 경로(forwards)');
  assert.equal(graph.nodeDetail(ix, child).outgoing[0].items[0].summary, 'prop onCancel로 받은 함수 호출');
  assert.equal(graph.nodeDetail(ix, save).outgoing[0].items[0].summary, 'redirect /order/list · 뷰 이름 redirect:/order/list');
  // 진단·미해결 목록에서도 인자 수가 보인다(원문은 name(…) 꼴이라 인자 수만 다른 호출이 같은 대상으로 모인다)
  const rows = diag.collect(ix).unresolved.filter((r) => r.targetId === call);
  assert.deepEqual(rows.map((r) => r.note).sort(), ['인자 0개', '인자 1개']);
  assert.ok(rows.every((r) => r.target === 'chunkContext.getStepContext().getJobParameters().get(…)'));
  assert.equal(diag.filter(diag.collect(ix).unresolved, { text: '인자 1개' }).length, 1, '인자 수로도 거른다');
});

test('F-016-AC38 상세: 루틴의 typeRefs, 메서드의 redirects·forwards, 테이블의 indexes, 식 안 시퀀스 참조 구문(sequence=true)', () => {
  const s = slice();
  const table = s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER', 'SHOP.TB_ORDER',
    { comment: '주문', primaryKey: ['ORDER_ID'], indexes: ['IX_ORDER_01(MEMBER_ID,ORDER_DT)', 'IX_ORDER_HIST_01(ORDER_ID,CHG_DT DESC)', 'UX_ORDER_01 UNIQUE(ORDER_NO)'] });
  const col = s.n('db:column:TB_ORDER.ORDER_STATUS', 'DB_COLUMN', 'ORDER_STATUS', 'TB_ORDER.ORDER_STATUS', { dataType: 'VARCHAR2(10)' });
  s.e(table, 'CONTAINS', col);
  const routine = s.n('db:routine:PKG_ORDER.CLOSE_ORDER', 'DB_ROUTINE', 'CLOSE_ORDER', 'PKG_ORDER.CLOSE_ORDER',
    { routineType: 'PROCEDURE', parameters: ['P_ORDER_ID IN NUMBER'], typeRefs: ['TB_ORDER.ORDER_STATUS', 'TB_ORDER', 'TB_GONE.X'] });
  const ep = s.n('http:endpoint:GET /main.do', 'HTTP_ENDPOINT', 'GET /main.do', 'GET /main.do', { httpMethod: 'GET', path: '/main.do' });
  const pre = s.n('java:method:c.LoginCheckInterceptor#preHandle()', 'JAVA_METHOD', 'preHandle', 'c.LoginCheckInterceptor#preHandle()',
    { signature: 'preHandle(): boolean', returnType: 'boolean', redirects: ['/main.do'], forwards: ['/WEB-INF/views/error.jsp', '/x/{*}'] });
  const seqStmt = s.n('sql:stmt:SP_SETTLE_MONTHLY:62', 'SQL_STATEMENT', 'SP_SETTLE_MONTHLY:62', 'SP_SETTLE_MONTHLY:62',
    { statementType: 'OTHER', sequence: true, sql: 'SET V_SETTLE_ID = NEXT VALUE FOR SEQ_SETTLE_ID' });
  const ix = graph.buildIndex(s.graph());

  const r = graph.nodeDetail(ix, routine);
  assert.equal(r.summary, 'PROCEDURE (P_ORDER_ID IN NUMBER) · %TYPE 참조 TB_ORDER.ORDER_STATUS, TB_ORDER, TB_GONE.X');
  assert.deepEqual(r.attrs.find((a) => a.key === 'typeRefs').items, [
    { text: 'TB_ORDER.ORDER_STATUS', nodeId: col }, { text: 'TB_ORDER', nodeId: table }, { text: 'TB_GONE.X', nodeId: null }
  ], '%TYPE이 가리키는 컬럼·테이블로 갈 수 있다(엣지는 없다)');

  const p = graph.nodeDetail(ix, pre);
  assert.equal(p.summary, 'preHandle(): boolean · redirect → /main.do · forward → /WEB-INF/views/error.jsp, /x/{*}');
  assert.deepEqual(p.attrs.find((a) => a.key === 'redirects').items, [{ text: '/main.do', nodeId: ep }], '경로가 맞는 엔드포인트가 하나면 그리로 간다');
  assert.deepEqual(p.attrs.find((a) => a.key === 'forwards').items.map((i) => i.nodeId), [null, null]);

  const t = graph.nodeDetail(ix, table);
  assert.equal(t.summary, '주문 · PK ORDER_ID · 인덱스 3개');
  const indexes = t.attrs.find((a) => a.key === 'indexes');
  assert.equal(indexes.multiline, true, '쉼표가 든 값의 목록은 한 줄에 하나씩 보인다');
  assert.deepEqual(indexes.items.map((i) => i.text), ['IX_ORDER_01(MEMBER_ID,ORDER_DT)', 'IX_ORDER_HIST_01(ORDER_ID,CHG_DT DESC)', 'UX_ORDER_01 UNIQUE(ORDER_NO)']);
  assert.ok(!t.attrs.find((a) => a.key === 'primaryKey').multiline);

  const q = graph.nodeDetail(ix, seqStmt);
  assert.deepEqual(q.tags.map((x) => x.text), ['시퀀스 참조 식']);
  assert.ok(q.tags[0].title.includes('sequence=true'));
  assert.equal(q.summary, 'OTHER · SQL 구문이 아닌 식 안의 시퀀스 참조');
});

test('F-016-AC38 상세: 요청의 URL 원문은 JSP에 적힌 그대로(EL, JSTL 태그) 보이고 검색된다', () => {
  const s = slice();
  const a = s.n('http:request:GET /order/form@views/common/header.jsp:10', 'HTTP_REQUEST', 'GET /order/form', 'GET /order/form',
    { api: 'link', httpMethod: 'GET', path: '/order/form', url: "<c:url value='/order/form'/>" });
  const b = s.n('http:request:GET /order/detail@views/order/list.jsp:43', 'HTTP_REQUEST', 'GET /order/detail', 'GET /order/detail',
    { api: 'link', httpMethod: 'GET', path: '/order/detail', query: 'orderId={*}', url: '${ctx}/order/detail?orderId=${o.orderId}' });
  const ix = graph.buildIndex(s.graph());
  assert.equal(graph.nodeDetail(ix, a).summary, "URL 원문 <c:url value='/order/form'/> · link");
  assert.equal(graph.nodeDetail(ix, b).summary, 'URL 원문 ${ctx}/order/detail?orderId=${o.orderId} · link');
  assert.deepEqual(core('search').search(ix, { text: 'c:url' }).map((n) => n.id), [a]);
});

// ---------------------------------------------------------------- 진단·미해결 (F-016-AC39)

function mapperSlice() {
  const s = slice();
  const type = (fqn, attrs) => s.n('java:type:' + fqn, 'JAVA_TYPE', fqn.substring(fqn.lastIndexOf('.') + 1), fqn, attrs);
  const method = (owner, name, params) => {
    const id = s.n('java:method:' + owner + '#' + name + '(' + (params || '') + ')', 'JAVA_METHOD', name, owner + '#' + name + '()', { modifiers: ['public'] });
    s.e('java:type:' + owner, 'CONTAINS', id);
    return id;
  };
  const stmt = (name, line) => s.n('mybatis:stmt:m.OrderMapper.' + name, 'SQL_STATEMENT', name, 'm.OrderMapper.' + name, { statementType: 'SELECT' },
    line ? loc('mapper/OrderMapper.xml', line) : null);
  type('m.OrderMapper', { typeKind: 'interface', stereotype: 'mapper' });
  type('m.OrderDao', { typeKind: 'interface' });
  type('m.OrderDaoImpl', { typeKind: 'class' });
  type('s.OrderService', { typeKind: 'class' });
  const f = {};
  const service = method('s.OrderService', 'run');
  // 1) 실행하는 메서드는 있지만 그 메서드를 부르는 곳이 없다
  f.lonely = stmt('selectLonely', 46);
  f.lonelyMethod = method('m.OrderMapper', 'selectLonely', 'java.lang.Long');
  s.e(f.lonelyMethod, 'EXECUTES', f.lonely);
  // 2) 인터페이스로 불리고 구현이 실행한다
  f.viaInterface = stmt('selectViaInterface', 50);
  const daoMethod = method('m.OrderDao', 'find');
  const implMethod = method('m.OrderDaoImpl', 'find');
  s.e(implMethod, 'OVERRIDES', daoMethod);
  s.e(implMethod, 'EXECUTES', f.viaInterface);
  s.e(service, 'CALLS', daoMethod, { callType: 'interface' });
  // 3) 스텝이 메서드를 USES한다
  f.byStep = stmt('selectByStep', 60);
  const stepMethod = method('m.OrderMapper', 'selectByStep');
  const step = s.n('batch:step:job/step', 'BATCH_STEP', 'step', 'job/step');
  s.e(stepMethod, 'EXECUTES', f.byStep);
  s.e(step, 'USES', stepMethod, { role: 'reader' });
  // 4) 엔드포인트의 핸들러가 직접 실행한다
  f.byHandler = stmt('selectByHandler', 70);
  const handler = method('s.OrderService', 'handle');
  const ep = s.n('http:endpoint:GET /x', 'HTTP_ENDPOINT', 'GET /x');
  s.e(ep, 'ROUTES_TO', handler);
  s.e(handler, 'EXECUTES', f.byHandler);
  // 5) 자기 자신만 부르는 메서드
  f.selfOnly = stmt('selectSelfOnly', 80);
  f.selfMethod = method('m.OrderMapper', 'selectSelfOnly');
  s.e(f.selfMethod, 'EXECUTES', f.selfOnly);
  s.e(f.selfMethod, 'CALLS', f.selfMethod, { callType: 'virtual' });
  // 6) 메서드와 배치 스텝이 함께 실행한다
  f.stepToo = stmt('selectStepToo', 90);
  const unusedMethod = method('m.OrderMapper', 'selectStepToo');
  s.e(unusedMethod, 'EXECUTES', f.stepToo);
  s.e(step, 'EXECUTES', f.stepToo, { role: 'reader' });
  // 7) EXECUTES가 없는 구문은 이 목록이 아니다("호출하는 곳이 없는 매퍼 구문")
  f.noExecutes = stmt('selectNoExecutes', 100);
  // 8) 매퍼 구문이 아닌 구문(루틴 안)은 대상이 아니다
  const inRoutine = s.n('sql:stmt:P:3', 'SQL_STATEMENT', 'P:3', 'P:3', { statementType: 'SELECT' });
  const other = method('s.OrderService', 'other');
  s.e(other, 'EXECUTES', inRoutine);
  // 9) 실행 메서드가 둘이고 둘 다 호출자가 없다
  f.two = stmt('selectTwo');
  f.twoA = method('m.OrderMapper', 'selectTwoB');
  f.twoB = method('m.OrderMapper', 'selectTwoA');
  s.e(f.twoA, 'EXECUTES', f.two);
  s.e(f.twoB, 'EXECUTES', f.two);
  // 동적 구문
  f.dynamic = s.n('mybatis:stmt:m.OrderMapper.selectBackup', 'SQL_STATEMENT', 'selectBackup', 'm.OrderMapper.selectBackup', { statementType: 'SELECT' }, loc('mapper/OrderMapper.xml', 38));
  const dyn = s.n('unresolved:sql-dynamic:' + f.dynamic, 'UNRESOLVED', '__DYNAMIC__', f.dynamic, { category: 'sql-dynamic' });
  s.e(f.dynamic, 'READS', dyn, { text: '__DYNAMIC__' }, 'UNRESOLVED', loc('mapper/OrderMapper.xml', 39));
  const backupMethod = method('m.OrderMapper', 'selectBackup');
  s.e(backupMethod, 'EXECUTES', f.dynamic);
  s.e(service, 'CALLS', backupMethod, { callType: 'interface' });
  s.d('INFO', 'CALLS_SKIPPED', '수신자를 알 수 없는 호출 1건을 기록하지 않았다(매개변수·props로 받은 함수: 직접 호출 0건, 다른 호출에 넘김 1건; 줄 76): frontend/src/pages/OrderDetail.tsx',
    { file: 'frontend/src/pages/OrderDetail.tsx', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  s.d('WARN', 'DYNAMIC_SQL', '테이블 이름이 동적(${})이라 대상을 알 수 없음', loc('mapper/OrderMapper.xml', 39));
  f.graph = s.graph();
  return f;
}

test('F-016-AC39 진단: Java 호출자가 없는 매퍼 구문(report.md와 같은 판정)을 실행 메서드와 함께 모은다', () => {
  const f = mapperSlice();
  const r = diag.collect(graph.buildIndex(f.graph));
  assert.deepEqual(r.uncalledStatements.map((x) => x.id), [f.lonely, f.selfOnly, f.two], '구문 ID 순');
  const lonely = r.uncalledStatements[0];
  assert.equal(lonely.type, 'UNCALLED');
  assert.equal(lonely.statementLabel, 'm.OrderMapper.selectLonely');
  assert.equal(lonely.location, 'mapper/OrderMapper.xml:46');
  assert.deepEqual(lonely.executors, [{ id: f.lonelyMethod, label: 'm.OrderMapper#selectLonely(Long)' }]);
  assert.equal(lonely.nodeId, f.lonely, '누르면 구문이 열린다');
  assert.deepEqual(r.uncalledStatements[2].executors.map((e) => e.id), [f.twoB, f.twoA].sort(), '실행 메서드는 ID 순');
  assert.equal(r.uncalledStatements[2].location, null);
  assert.equal(diag.filter(r.uncalledStatements, { text: 'selectTwoA' }).length, 1, '구문·메서드·위치로 거른다');
  assert.equal(diag.filter(r.uncalledStatements, { text: 'OrderMapper.xml:46' }).length, 1);
});

test('F-016-AC39 진단: 대상 테이블을 정적으로 알 수 없는 구문 목록과 새 진단 코드(CALLS_SKIPPED)', () => {
  const f = mapperSlice();
  const r = diag.collect(graph.buildIndex(f.graph));
  assert.deepEqual(r.dynamicStatements.map((d) => [d.type, d.id, d.access, d.location, d.text, d.nodeId]),
    [['DYNAMIC', f.dynamic, 'R', 'mapper/OrderMapper.xml:38', '__DYNAMIC__', f.dynamic]]);
  assert.equal(diag.filter(r.dynamicStatements, { text: 'selectbackup' }).length, 1);
  assert.equal(diag.filter(r.dynamicStatements, { text: 'nothing' }).length, 0);
  assert.deepEqual(r.codes.map((c) => ({ code: c.code, count: c.count })), [{ code: 'CALLS_SKIPPED', count: 1 }, { code: 'DYNAMIC_SQL', count: 1 }]);
  const skipped = diag.filter(r.diagnostics, { codes: ['CALLS_SKIPPED'] });
  assert.equal(skipped.length, 1);
  assert.equal(skipped[0].severity, 'INFO');
  assert.equal(skipped[0].location, 'frontend/src/pages/OrderDetail.tsx', '줄이 0이면 파일만 적는다');
  // 보기(탭) 목록: 건수와 함께
  assert.deepEqual(diag.VIEWS.map((v) => v.key), ['unresolved', 'diagnostics', 'uncalled', 'dynamic', 'undeclared', 'dataload', 'columns', 'requests', 'borrowed']);
  assert.deepEqual(diag.VIEWS.map((v) => diag.viewCount(v, r)), [1, 2, 3, 1, 0, 0, 0, 0, 0]);
});
