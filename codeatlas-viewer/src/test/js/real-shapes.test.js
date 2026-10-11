'use strict';
// 실제 분석 결과(samples/sample-shop)로 뷰어를 열어 보고 찾은 문제의 재현 테스트.
// 그래프 조각은 실제 graph.json에 나온 노드·엣지 모양을 그대로 옮긴 것이다(fixtures/slice.js).
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { slice, loc, paths, containsInOrder } = require('./fixtures/slice.js');

const graph = core('graph');
const search = core('search');
const calltree = core('calltree');
const erd = core('erd');
const classDiagram = core('classdiagram');
const crud = core('crud');
const diag = core('diagnostics');
const diagram = core('diagram');

// ---------------------------------------------------------------- 탐색·상세 (F-016-AC26)

test('F-016-AC26 참조만 된 노드: 짧게 온 qualifiedName을 ID에서 복원한다', () => {
  const s = slice();
  const m = s.n('java:method:jakarta.servlet.http.HttpServletRequest#getContextPath(?)', 'JAVA_METHOD', 'getContextPath', 'getContextPath',
    { declared: false, external: true, signatureUnknown: true });
  const t = s.n('java:type:jakarta.annotation.Resource', 'JAVA_TYPE', 'Resource', 'Resource', { declared: false, external: true });
  const c = s.n('db:column:TB_BROKEN.BROKEN_ID', 'DB_COLUMN', 'BROKEN_ID', 'BROKEN_ID', { declared: false });
  const stmt = s.n('mybatis:stmt:orderDao.selectOrderBackup', 'SQL_STATEMENT', 'selectOrderBackup', 'orderDao.selectOrderBackup', { statementType: 'SELECT' });
  const ix = graph.buildIndex(s.graph());
  assert.equal(ix.label(m), 'jakarta.servlet.http.HttpServletRequest#getContextPath(?)');
  assert.equal(graph.shortLabel(ix.node(m), m), 'HttpServletRequest#getContextPath(?)');
  assert.equal(ix.label(t), 'jakarta.annotation.Resource');
  assert.equal(ix.label(c), 'TB_BROKEN.BROKEN_ID');
  assert.equal(ix.label(stmt), 'orderDao.selectOrderBackup', '정의가 있는 노드는 그대로');
  assert.deepEqual(search.search(ix, { text: 'httpservletrequest' }).map((n) => n.id), [m]);
});

test('F-016-AC26 검색: 한글 주석과 요청 URL 원문에서도 찾고, 이름이 맞는 것이 먼저 온다', () => {
  const s = slice();
  const order = s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER', 'SHOP.TB_ORDER', { comment: '주문', schema: 'SHOP', primaryKey: ['ORDER_ID'] });
  const col = s.n('db:column:TB_ORDER.ORDER_ID', 'DB_COLUMN', 'ORDER_ID', 'TB_ORDER.ORDER_ID', { comment: '주문ID', dataType: 'NUMBER(12)' });
  const named = s.n('js:function:a.js#주문', 'JS_FUNCTION', '주문', 'a.js#주문', {});
  const req = s.n('http:request:DELETE /api/orders/{*}@frontend/src/api/orderApi.js:26', 'HTTP_REQUEST', 'DELETE /api/orders/{*}', 'DELETE /api/orders/{*}',
    { api: 'axios', dynamic: true, httpMethod: 'DELETE', path: '/api/orders/{*}', url: '`/orders/${id}`' });
  const ix = graph.buildIndex(s.graph());
  const hits = search.search(ix, { text: '주문' }).map((n) => n.id);
  assert.deepEqual(hits.slice().sort(), [order, col, named].sort());
  assert.equal(hits[0], named, '이름이 맞는 노드가 주석만 맞는 노드보다 먼저');
  assert.deepEqual(search.search(ix, { text: 'orders/${id}' }).map((n) => n.id), [req]);
});

test('F-016-AC26 검색: 정의 없는 노드와 합성 노드를 숨길 수 있다', () => {
  const s = slice();
  s.n('java:method:a.B#<init>()', 'JAVA_METHOD', '<init>', 'a.B#<init>()', { synthetic: true, constructor: true });
  s.n('java:method:a.B#getId()', 'JAVA_METHOD', 'getId', 'a.B#getId()', { synthetic: true, lombok: 'Getter' });
  s.n('java:method:a.B#run()', 'JAVA_METHOD', 'run', 'a.B#run()', {});
  s.n('java:type:x.Ext', 'JAVA_TYPE', 'Ext', 'Ext', { declared: false, external: true });
  s.n('db:table:TB_HOLIDAY', 'DB_TABLE', 'TB_HOLIDAY', 'TB_HOLIDAY', { declared: false });
  const ix = graph.buildIndex(s.graph());
  assert.equal(search.search(ix, {}).length, 5);
  assert.deepEqual(search.search(ix, { hideSynthetic: true }).map((n) => n.name).sort(), ['Ext', 'TB_HOLIDAY', 'run']);
  assert.deepEqual(search.search(ix, { hideUndeclared: true }).map((n) => n.name).sort(), ['<init>', 'getId', 'run']);
  assert.deepEqual(search.search(ix, { hideUndeclared: true, hideSynthetic: true }).map((n) => n.name), ['run']);
});

test('F-016-AC26 노드 상세: synthetic, lombok, external, anonymous, local, 미해결 분류를 꼬리표로 보인다', () => {
  const s = slice();
  const tagTexts = (attrs, kind) => graph.nodeTags({ id: 'x', kind: kind || 'JAVA_METHOD', name: 'x', qualifiedName: 'x', attrs }).map((t) => t.text);
  assert.deepEqual(tagTexts({ declared: true }), []);
  assert.deepEqual(tagTexts({ declared: true, synthetic: true }), ['합성']);
  assert.deepEqual(tagTexts({ declared: true, synthetic: true, lombok: 'Getter' }), ['Lombok @Getter']);
  assert.deepEqual(tagTexts({ declared: false, external: true, signatureUnknown: true }), ['외부 라이브러리', '시그니처 모름']);
  assert.deepEqual(tagTexts({ declared: false }, 'DB_TABLE'), ['정의 없음']);
  assert.deepEqual(tagTexts({ declared: true, anonymous: true }, 'JAVA_TYPE'), ['익명 타입']);
  assert.deepEqual(tagTexts({ declared: true, local: true }, 'JAVA_TYPE'), ['로컬 타입']);
  assert.deepEqual(tagTexts({ declared: true, category: 'java-call' }, 'UNRESOLVED'), ['분류 java-call']);
  assert.deepEqual(tagTexts({ declared: true, expectedPrefix: 'java:method' }, 'UNRESOLVED'), ['분류 java-call'], '마무리 단계가 만든 노드도 분류로 적는다(F-016-AC51)');
  assert.deepEqual(tagTexts({ declared: true, dynamic: true, relative: true }, 'HTTP_REQUEST'), ['동적 URL', '상대 URL']);
  assert.ok(graph.nodeTags({ id: 'x', kind: 'JAVA_METHOD', attrs: { synthetic: true } })[0].title.includes('synthetic'));
  // 상세에도 실린다
  const id = s.n('java:method:a.B#<init>()', 'JAVA_METHOD', '<init>', 'a.B#<init>()', { synthetic: true, constructor: true });
  const d = graph.nodeDetail(graph.buildIndex(s.graph()), id);
  assert.deepEqual(d.tags.map((t) => t.text), ['합성']);
});

test('F-016-AC26 노드 상세: 요청의 URL 원문, 루틴의 매개변수처럼 종류별 요약 한 줄을 준다', () => {
  const s = slice();
  const req = s.n('http:request:GET /api/products/low-stock@views/main.jsp:29', 'HTTP_REQUEST', 'GET /api/products/low-stock', 'GET /api/products/low-stock',
    { api: 'fetch', dynamic: false, httpMethod: 'GET', path: '/api/products/low-stock', query: 'limit=5', url: "'/api/products/low-stock?limit=5'" });
  const routine = s.n('db:routine:PKG_ORDER.GET_ORDER_AMT', 'DB_ROUTINE', 'GET_ORDER_AMT', 'PKG_ORDER.GET_ORDER_AMT',
    { dialect: 'ORACLE', language: 'PLSQL', package: 'PKG_ORDER', parameters: ['P_ORDER_ID IN NUMBER'], returnType: 'NUMBER', routineType: 'FUNCTION', statementCount: 1 });
  const trigger = s.n('db:routine:TRG_ORDER_STATUS_HIST', 'DB_ROUTINE', 'TRG_ORDER_STATUS_HIST', 'TRG_ORDER_STATUS_HIST',
    { routineType: 'TRIGGER', triggerEvent: 'AFTER UPDATE OF ORDER_STATUS' });
  const col = s.n('db:column:TB_ORDER.ORDER_ID', 'DB_COLUMN', 'ORDER_ID', 'TB_ORDER.ORDER_ID', { comment: '주문ID', dataType: 'NUMBER(12)', nullable: false, primaryKey: true });
  const ix = graph.buildIndex(s.graph());
  assert.equal(graph.nodeDetail(ix, req).summary, "URL 원문 '/api/products/low-stock?limit=5' · fetch");
  assert.equal(graph.nodeDetail(ix, routine).summary, 'FUNCTION (P_ORDER_ID IN NUMBER) → NUMBER');
  assert.equal(graph.nodeDetail(ix, trigger).summary, 'TRIGGER AFTER UPDATE OF ORDER_STATUS');
  assert.equal(graph.nodeDetail(ix, col).summary, 'NUMBER(12) · PK · NOT NULL · 주문ID');
});

test('F-016-AC26 노드 상세: 노드 ID인 속성 값(evidence, definedBy 등)은 그 노드로 갈 수 있게 표시한다', () => {
  const s = slice();
  const stmt = s.n('mybatis:stmt:orderDao.selectOrderHistory', 'SQL_STATEMENT', 'selectOrderHistory', 'orderDao.selectOrderHistory', {});
  const method = s.n('java:method:c.DailySalesJobConfig#dailySalesJob()', 'JAVA_METHOD', 'dailySalesJob', 'c.DailySalesJobConfig#dailySalesJob()', {});
  const job = s.n('batch:job:dailySalesJob', 'BATCH_JOB', 'dailySalesJob', 'dailySalesJob', { definedBy: method });
  const a = s.n('db:table:TB_ORDER_HIST', 'DB_TABLE', 'TB_ORDER_HIST');
  const b = s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER');
  s.e(a, 'REFERENCES', b, { cardinality: 'N:1', columns: ['ORDER_ID'], directionKnown: true, evidence: [stmt, 'mybatis:stmt:gone'], evidenceCount: 2,
    refColumns: ['ORDER_ID'], source: 'INFERRED' }, 'INFERRED');
  const ix = graph.buildIndex(s.graph());
  const defined = graph.nodeDetail(ix, job).attrs.find((x) => x.key === 'definedBy');
  assert.deepEqual(defined.items, [{ text: method, nodeId: method }]);
  const edge = graph.nodeDetail(ix, a).outgoing.find((g) => g.kind === 'REFERENCES').items[0];
  const evidence = edge.attrs.find((x) => x.key === 'evidence');
  assert.deepEqual(evidence.items, [{ text: stmt, nodeId: stmt }, { text: 'mybatis:stmt:gone', nodeId: null }]);
  assert.deepEqual(edge.attrs.find((x) => x.key === 'columns').items, [{ text: 'ORDER_ID', nodeId: null }]);
  assert.equal(edge.attrs.find((x) => x.key === 'directionKnown').value, 'true');
});

test('F-016-AC26 색인: 정의 없는 테이블의 컬럼은 CONTAINS가 없어도 ID로 찾는다', () => {
  const s = slice();
  const t = s.n('db:table:TB_BROKEN', 'DB_TABLE', 'TB_BROKEN', 'TB_BROKEN', { declared: false });
  const c1 = s.n('db:column:TB_BROKEN.BROKEN_ID', 'DB_COLUMN', 'BROKEN_ID', 'BROKEN_ID', { declared: false });
  const v = s.n('db:view:VW_ORDER_SUMMARY', 'DB_VIEW', 'VW_ORDER_SUMMARY');
  const c2 = s.n('db:column:VW_ORDER_SUMMARY.ORDER_ID', 'DB_COLUMN', 'ORDER_ID', 'VW_ORDER_SUMMARY.ORDER_ID', { ordinal: 1 });
  s.e(v, 'CONTAINS', c2);
  const ix = graph.buildIndex(s.graph());
  assert.deepEqual(ix.columnsOf(t).map((c) => c.id), [c1]);
  assert.deepEqual(ix.columnsOf(v).map((c) => c.id), [c2]);
  assert.equal(ix.tableOfColumn(c1), t);
  assert.equal(ix.tableOfColumn(c2), v);
  assert.equal(ix.tableOfColumn('db:column:NOPE.X'), null);
});

// ---------------------------------------------------------------- 콜트리 (F-016-AC19, AC20)

test('F-016-AC19 콜트리: JSP에서 인라인 스크립트(CONTAINS JSP_PAGE→JS_MODULE)를 거쳐 엔드포인트까지 내려간다', () => {
  const s = slice();
  const page = s.n('jsp:page:views/order/detail.jsp', 'JSP_PAGE', 'detail.jsp', 'views/order/detail.jsp', { pageType: 'jsp' });
  const inline = s.n('js:module:views/order/detail.jsp', 'JS_MODULE', 'detail.jsp', 'views/order/detail.jsp', { inline: true });
  const req = s.n('http:request:GET /order/history.do@views/order/detail.jsp:54', 'HTTP_REQUEST', 'GET /order/history.do', 'GET /order/history.do', { api: 'jquery', path: '/order/history.do' });
  const ep = s.n('http:endpoint:ANY /order/history.do', 'HTTP_ENDPOINT', 'ANY /order/history.do');
  const handler = s.n('java:method:w.OrderController#history(java.lang.Long)', 'JAVA_METHOD', 'history', 'w.OrderController#history(Long)');
  s.e(page, 'CONTAINS', inline);
  s.e(inline, 'REQUESTS', req, {}, 'RESOLVED', loc('views/order/detail.jsp', 54));
  s.e(req, 'MATCHES', ep);
  s.e(ep, 'ROUTES_TO', handler);
  const ix = graph.buildIndex(s.graph());
  const down = calltree.createRoot(ix, page, 'callees');
  calltree.expandAll(ix, down, { maxDepth: 10 });
  assert.ok(paths(down).some((p) => containsInOrder(p, [page, inline, req, ep, handler])));
  // 인라인 스크립트의 모듈은 JSP와 경로가 같다. 이름으로 구분되게 한다.
  assert.equal(graph.shortLabel(ix.node(page), page), 'order/detail.jsp');
  assert.equal(graph.shortLabel(ix.node(inline), inline), 'order/detail.jsp (인라인 스크립트)');
  const up = calltree.createRoot(ix, handler, 'callers');
  calltree.expandAll(ix, up, { maxDepth: 10 });
  assert.ok(paths(up).some((p) => containsInOrder(p, [handler, ep, req, inline, page])), '위로는 화면까지');
});

test('F-016-AC19 콜트리: 뷰는 정의 쿼리(CONTAINS DB_VIEW→SQL_STATEMENT)를 거쳐 테이블로 이어진다', () => {
  const s = slice();
  const stmt = s.n('mybatis:stmt:m.selectSummary', 'SQL_STATEMENT', 'selectSummary', 'm.selectSummary', { statementType: 'SELECT' });
  const view = s.n('db:view:VW_ORDER_SUMMARY', 'DB_VIEW', 'VW_ORDER_SUMMARY');
  const def = s.n('sql:stmt:db/oracle/03_views.sql:13', 'SQL_STATEMENT', '03_views.sql:13', 'db/oracle/03_views.sql:13', { statementType: 'SELECT' });
  const table = s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER');
  s.e(stmt, 'READS', view);
  s.e(view, 'CONTAINS', def);
  s.e(def, 'READS', table);
  const ix = graph.buildIndex(s.graph());
  const down = calltree.createRoot(ix, stmt, 'callees');
  calltree.expandAll(ix, down, {});
  assert.deepEqual(paths(down), [[stmt, view, def, table]]);
  const up = calltree.createRoot(ix, table, 'callers');
  calltree.expandAll(ix, up, {});
  assert.deepEqual(paths(up), [[table, def, view, stmt]]);
});

test('F-016-AC19 콜트리: DB 패키지에서 루틴으로 내려가고, 패키지와 시퀀스도 기준으로 고를 수 있다', () => {
  const s = slice();
  const pkg = s.n('db:package:PKG_ORDER', 'DB_PACKAGE', 'PKG_ORDER');
  const routine = s.n('db:routine:PKG_ORDER.WRITE_HIST', 'DB_ROUTINE', 'WRITE_HIST', 'PKG_ORDER.WRITE_HIST', { routineType: 'PROCEDURE', package: 'PKG_ORDER' });
  const stmt = s.n('sql:stmt:PKG_ORDER.WRITE_HIST:15', 'SQL_STATEMENT', 'PKG_ORDER.WRITE_HIST:15', 'PKG_ORDER.WRITE_HIST:15', { statementType: 'INSERT' });
  const seq = s.n('db:sequence:SEQ_ORDER_HIST_ID', 'DB_SEQUENCE', 'SEQ_ORDER_HIST_ID');
  s.e(pkg, 'CONTAINS', routine);
  s.e(routine, 'CONTAINS', stmt);
  s.e(stmt, 'READS', seq, { sequence: true });
  const ix = graph.buildIndex(s.graph());
  const down = calltree.createRoot(ix, pkg, 'callees');
  calltree.expandAll(ix, down, {});
  assert.deepEqual(paths(down), [[pkg, routine, stmt, seq]]);
  // 패키지는 루틴을 "호출"하지 않는다. 위로는 따라가지 않는다.
  assert.deepEqual(calltree.childLinks(ix, routine, 'callers').map((l) => l.nodeId), []);
  assert.deepEqual(calltree.childLinks(ix, seq, 'callers').map((l) => l.nodeId), [stmt]);
  assert.ok(calltree.ROOT_KINDS.includes('DB_PACKAGE'));
  assert.ok(calltree.ROOT_KINDS.includes('DB_SEQUENCE'));
});

function triggerSlice() {
  const s = slice();
  const stmt = (name, type) => s.n('mybatis:stmt:m.' + name, 'SQL_STATEMENT', name, 'm.' + name, { statementType: type });
  const f = {
    upd: stmt('updateStatus', 'UPDATE'), ins: stmt('insertOrder', 'INSERT'), del: stmt('deleteOrder', 'DELETE'),
    merge: stmt('mergeOrder', 'MERGE'), sel: stmt('selectOrder', 'SELECT'),
    order: s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER'),
    hist: s.n('db:table:TB_ORDER_HIST', 'DB_TABLE', 'TB_ORDER_HIST'),
    audit: s.n('db:table:TB_AUDIT', 'DB_TABLE', 'TB_AUDIT'),
    trg: s.n('db:routine:TRG_ORDER_STATUS_HIST', 'DB_ROUTINE', 'TRG_ORDER_STATUS_HIST', 'TRG_ORDER_STATUS_HIST', { routineType: 'TRIGGER', triggerEvent: 'AFTER UPDATE OF ORDER_STATUS' }),
    trgIns: s.n('db:routine:TRG_ORDER_INS', 'DB_ROUTINE', 'TRG_ORDER_INS', 'TRG_ORDER_INS', { routineType: 'TRIGGER', triggerEvent: 'BEFORE INSERT OR DELETE' }),
    trgAny: s.n('db:routine:TRG_ORDER_ANY', 'DB_ROUTINE', 'TRG_ORDER_ANY', 'TRG_ORDER_ANY', { routineType: 'TRIGGER' }),
    histIns: s.n('sql:stmt:TRG_ORDER_STATUS_HIST:8', 'SQL_STATEMENT', 'TRG_ORDER_STATUS_HIST:8', 'TRG_ORDER_STATUS_HIST:8', { statementType: 'INSERT' }),
    auditIns: s.n('sql:stmt:TRG_ORDER_ANY:5', 'SQL_STATEMENT', 'TRG_ORDER_ANY:5', 'TRG_ORDER_ANY:5', { statementType: 'INSERT' })
  };
  s.e(f.upd, 'WRITES', f.order, { op: 'UPDATE' });
  s.e(f.ins, 'WRITES', f.order, { op: 'INSERT' });
  s.e(f.del, 'WRITES', f.order, { op: 'DELETE' });
  s.e(f.merge, 'WRITES', f.order, { op: 'MERGE' });
  s.e(f.sel, 'READS', f.order);
  s.e(f.trg, 'TRIGGERS_ON', f.order);
  s.e(f.trgIns, 'TRIGGERS_ON', f.order);
  s.e(f.trgAny, 'TRIGGERS_ON', f.order);
  s.e(f.trg, 'CONTAINS', f.histIns);
  s.e(f.histIns, 'WRITES', f.hist, { op: 'INSERT' });
  s.e(f.trgAny, 'CONTAINS', f.auditIns);
  s.e(f.auditIns, 'WRITES', f.audit, { op: 'INSERT' });
  f.graph = s.graph();
  return f;
}

test('F-016-AC19 콜트리: 테이블을 바꾸면 그 변경(op)에 걸린 트리거(triggerEvent)로만 내려간다', () => {
  const f = triggerSlice();
  const ix = graph.buildIndex(f.graph);
  const triggersBelow = (stmtId) => {
    const root = calltree.createRoot(ix, stmtId, 'callees');
    const table = calltree.expand(ix, root).find((c) => c.id === f.order);
    return calltree.expand(ix, table).map((c) => c.id).sort();
  };
  assert.deepEqual(triggersBelow(f.upd), [f.trgAny, f.trg].sort(), 'UPDATE → AFTER UPDATE OF 트리거');
  assert.deepEqual(triggersBelow(f.ins), [f.trgAny, f.trgIns].sort(), 'INSERT는 UPDATE 트리거로 내려가지 않는다');
  assert.deepEqual(triggersBelow(f.del), [f.trgAny, f.trgIns].sort());
  assert.deepEqual(triggersBelow(f.merge), [f.trgAny, f.trgIns, f.trg].sort(), 'MERGE는 INSERT와 UPDATE 둘 다다');
  assert.deepEqual(triggersBelow(f.sel), [], '읽기는 트리거를 일으키지 않는다');

  const down = calltree.createRoot(ix, f.upd, 'callees');
  calltree.expandAll(ix, down, {});
  assert.ok(paths(down).some((p) => containsInOrder(p, [f.upd, f.order, f.trg, f.histIns, f.hist])));
  const table = down.children[0];
  const known = table.children.find((c) => c.id === f.trg);
  assert.equal(known.link.kind, 'TRIGGERS_ON');
  assert.equal(known.link.label, '트리거');
  assert.equal(known.link.reversed, true);
  assert.equal(known.link.triggerUncertain, true, '컬럼 지정 트리거인데 구문이 쓰는 컬럼을 모른다(컬럼 WRITES 엣지가 없다): 내려가되 표시한다');
  const unknown = table.children.find((c) => c.id === f.trgAny);
  assert.equal(unknown.link.label, '트리거');
  assert.equal(unknown.link.triggerUncertain, true, 'triggerEvent가 없으면 내려가되 표시한다(표준 산출물의 [trigger?])');

  // 테이블이 기준이면 걸린 트리거를 모두 보인다
  const fromTable = calltree.createRoot(ix, f.order, 'callees');
  assert.deepEqual(calltree.expand(ix, fromTable).map((c) => c.id).sort(), [f.trgAny, f.trgIns, f.trg].sort());
});

test('F-016-AC19 콜트리: 트리거에서 위로는 대상 테이블을 그 이벤트로 바꾸는 구문만 올라간다', () => {
  const f = triggerSlice();
  const ix = graph.buildIndex(f.graph);
  const up = calltree.createRoot(ix, f.hist, 'callers');
  calltree.expandAll(ix, up, {});
  const all = paths(up);
  assert.deepEqual(all.map((p) => p.join(' > ')).sort(), [
    [f.hist, f.histIns, f.trg, f.order, f.upd].join(' > '),
    [f.hist, f.histIns, f.trg, f.order, f.merge].join(' > ')
  ].sort(), 'UPDATE 트리거 → 대상 테이블 → UPDATE·MERGE 구문 (INSERT, DELETE, 조회 구문은 빠진다)');
  assert.equal(up.children[0].children[0].children[0].link.label, '트리거 대상');
  // 이벤트를 모르는 트리거는 그 테이블을 바꾸는 구문 모두
  const any = calltree.createRoot(ix, f.audit, 'callers');
  calltree.expandAll(ix, any, {});
  assert.deepEqual(paths(any).map((p) => p[p.length - 1]).sort(), [f.upd, f.ins, f.del, f.merge].sort());
});

function dispatchSlice() {
  const s = slice();
  const P = 'com.example.shop.payment.';
  const type = (fqn, attrs) => s.n('java:type:' + fqn, 'JAVA_TYPE', fqn.substring(fqn.lastIndexOf('.') + 1), fqn, attrs);
  const method = (owner, name) => {
    const id = s.n('java:method:' + owner + '#' + name + '()', 'JAVA_METHOD', name, owner + '#' + name + '()');
    s.e('java:type:' + owner, 'CONTAINS', id);
    return id;
  };
  const f = {
    policy: type(P + 'DiscountPolicy', { typeKind: 'interface' }),
    def: type(P + 'DefaultDiscountPolicy', { typeKind: 'class' }),
    vip: type(P + 'VipDiscountPolicy', { typeKind: 'class' }),
    gold: type(P + 'GoldDiscountPolicy', { typeKind: 'class' }),
    order: type('com.example.shop.order.OrderServiceImpl', { typeKind: 'class' }),
    member: type('com.example.shop.member.MemberServiceImpl', { typeKind: 'class' }),
    pay: type('com.example.shop.payment.PaymentServiceImpl', { typeKind: 'class' }),
    report: type('com.example.shop.report.ReportService', { typeKind: 'class' }),
    batch: type('com.example.shop.batch.GoldJob', { typeKind: 'class' })
  };
  f.policyDiscount = method(P + 'DiscountPolicy', 'discount');
  f.defDiscount = method(P + 'DefaultDiscountPolicy', 'discount');
  f.vipDiscount = method(P + 'VipDiscountPolicy', 'discount');
  f.placeOrder = method('com.example.shop.order.OrderServiceImpl', 'placeOrder');
  f.preview = method('com.example.shop.member.MemberServiceImpl', 'previewDiscount');
  f.payMethod = method('com.example.shop.payment.PaymentServiceImpl', 'pay');
  f.reportMethod = method('com.example.shop.report.ReportService', 'run');
  f.batchMethod = method('com.example.shop.batch.GoldJob', 'run');
  // OVERRIDES는 가장 가까운 상위 하나로만 간다: Vip → Default → DiscountPolicy
  s.e(f.defDiscount, 'OVERRIDES', f.policyDiscount);
  s.e(f.vipDiscount, 'OVERRIDES', f.defDiscount);
  s.e(f.def, 'IMPLEMENTS', f.policy);
  s.e(f.vip, 'EXTENDS', f.def);
  s.e(f.gold, 'EXTENDS', f.vip); // discount를 재정의하지 않는다
  s.e(f.vipDiscount, 'CALLS', f.defDiscount, { callType: 'super' });
  [f.placeOrder, f.preview, f.payMethod, f.reportMethod, f.batchMethod].forEach((m) => s.e(m, 'CALLS', f.policyDiscount, { callType: 'interface' }));
  s.e(f.order, 'INJECTS', f.policy, { candidates: [f.vip], field: 'discountPolicy', qualifier: 'vipDiscountPolicy', via: 'constructor' });
  s.e(f.member, 'INJECTS', f.policy, { candidates: [f.def], field: 'discountPolicy', qualifier: 'defaultDiscountPolicy', via: 'field' });
  s.e(f.pay, 'INJECTS', f.def, { field: 'policy', resolvedFrom: f.policy, via: 'constructor' }, 'INFERRED');
  s.e(f.batch, 'INJECTS', f.policy, { candidates: [f.gold], field: 'policy', via: 'field' });
  f.graph = s.graph();
  return f;
}

test('F-016-AC27 콜트리: 인터페이스 메서드의 구현은 OVERRIDES를 전이적으로 거슬러 간접 구현까지 보인다', () => {
  const f = dispatchSlice();
  const ix = graph.buildIndex(f.graph);
  // 주입 정보가 없는 호출자: 직접 구현과 간접 구현 모두
  const root = calltree.createRoot(ix, f.reportMethod, 'callees');
  const iface = calltree.expand(ix, root)[0];
  const impls = calltree.expand(ix, iface);
  assert.deepEqual(impls.map((c) => c.id).sort(), [f.defDiscount, f.vipDiscount].sort());
  assert.ok(impls.every((c) => c.link.kind === 'OVERRIDES' && c.link.label === '구현' && c.link.reversed));
  // 구현으로 내려온 메서드 아래에 하위 재정의를 다시 늘어놓지 않는다(형제로 이미 있다)
  const def = impls.find((c) => c.id === f.defDiscount);
  assert.deepEqual(calltree.expand(ix, def).map((c) => c.id), []);
  const vip = impls.find((c) => c.id === f.vipDiscount);
  const superCall = calltree.expand(ix, vip);
  assert.deepEqual(superCall.map((c) => c.link.label + ' ' + c.id), ['CALLS(super) ' + f.defDiscount]);
  // super.discount()는 동적 디스패치가 아니다. 그 아래에 하위 재정의(자기 자신)를 다시 보이지 않는다.
  assert.deepEqual(calltree.expand(ix, superCall[0]).map((c) => c.id), []);
  // 인터페이스 메서드가 기준일 때도 같다
  assert.deepEqual(calltree.childLinks(ix, f.policyDiscount, 'callees').map((l) => l.nodeId).sort(), [f.defDiscount, f.vipDiscount].sort());
  // 본문이 있는 클래스 메서드는 구현으로 펼치는 대상이 아니다(표준 산출물과 같은 규칙, F-016-AC32)
  assert.deepEqual(calltree.childLinks(ix, f.defDiscount, 'callees').map((l) => l.nodeId), []);
});

test('F-016-AC27 콜트리: 호출하는 타입의 주입 정보(INJECTS candidates, INFERRED resolvedFrom)로 구현을 좁힌다', () => {
  const f = dispatchSlice();
  const ix = graph.buildIndex(f.graph);
  const implsFrom = (methodId, opts) => {
    const root = calltree.createRoot(ix, methodId, 'callees', opts);
    const iface = calltree.expand(ix, root).find((c) => c.id === f.policyDiscount);
    return calltree.expand(ix, iface);
  };
  const viaQualifier = implsFrom(f.placeOrder);
  assert.deepEqual(viaQualifier.map((c) => c.id), [f.vipDiscount], '@Qualifier("vipDiscountPolicy") → VipDiscountPolicy#discount');
  assert.equal(viaQualifier[0].link.label, '구현(주입)');
  assert.equal(viaQualifier[0].link.hidden, 1, '숨긴 다른 구현의 수');
  assert.deepEqual(implsFrom(f.preview).map((c) => c.id), [f.defDiscount]);
  // 주입 정보가 가리키는 구현이 전부라서 숨긴 것이 없으면 그냥 "구현"이다
  const one = slice();
  const svcType = one.n('java:type:s.MemberService', 'JAVA_TYPE', 'MemberService', 's.MemberService', { typeKind: 'interface' });
  const implType = one.n('java:type:s.MemberServiceImpl', 'JAVA_TYPE', 'MemberServiceImpl', 's.MemberServiceImpl', { typeKind: 'class' });
  const ctlType = one.n('java:type:w.MemberController', 'JAVA_TYPE', 'MemberController', 'w.MemberController', { typeKind: 'class' });
  const svcFind = one.n('java:method:s.MemberService#find()', 'JAVA_METHOD', 'find', 's.MemberService#find()');
  const implFind = one.n('java:method:s.MemberServiceImpl#find()', 'JAVA_METHOD', 'find', 's.MemberServiceImpl#find()');
  const ctlList = one.n('java:method:w.MemberController#list()', 'JAVA_METHOD', 'list', 'w.MemberController#list()');
  one.e(implFind, 'OVERRIDES', svcFind);
  one.e(ctlList, 'CALLS', svcFind, { callType: 'interface' });
  one.e(ctlType, 'INJECTS', svcType, { candidates: [implType], field: 'memberService', via: 'field' });
  const oneIx = graph.buildIndex(one.graph());
  const oneRoot = calltree.createRoot(oneIx, ctlList, 'callees');
  const oneImpl = calltree.expand(oneIx, calltree.expand(oneIx, oneRoot)[0]);
  assert.deepEqual(oneImpl.map((c) => c.link.label + ' ' + c.id), ['구현 ' + implFind]);
  assert.ok(!oneImpl[0].link.hidden);
  assert.deepEqual(implsFrom(f.payMethod).map((c) => c.id), [f.defDiscount], 'INFERRED INJECTS(resolvedFrom=인터페이스)가 가리키는 구현 타입');
  // 주입된 타입에 재정의가 없으면 가장 가까운 상위 클래스의 구현
  assert.deepEqual(implsFrom(f.batchMethod).map((c) => c.id), [f.vipDiscount]);
  // 좁히지 않게 고르면 모두 보인다
  assert.deepEqual(implsFrom(f.placeOrder, { allImplementations: true }).map((c) => c.id).sort(), [f.defDiscount, f.vipDiscount].sort());
});

test('F-016-AC27 F-016-AC35 콜트리: 위로는 재정의한 상위 메서드(간접 포함)의 호출자가 바로 올라오고, 주입이 다른 구현을 가리키는 호출자는 빠진다', () => {
  const f = dispatchSlice();
  const ix = graph.buildIndex(f.graph);
  const up = calltree.createRoot(ix, f.vipDiscount, 'callers');
  calltree.expandAll(ix, up, {});
  // Vip → Default → DiscountPolicy: 인터페이스의 호출자 가운데 Vip을 주입받거나(placeOrder, Gold를 주입받는 배치) 주입 정보가 없는 것만
  assert.deepEqual(up.children.map((c) => c.id).sort(), [f.placeOrder, f.reportMethod, f.batchMethod].sort());
  assert.ok(up.children.every((c) => c.link.viaInterface && c.link.viaMethod === f.policyDiscount));
  const def = calltree.childLinks(ix, f.defDiscount, 'callers');
  assert.deepEqual(def.map((l) => (l.viaInterface ? 'via ' : '') + l.label + ' ' + l.nodeId).sort(),
    ['CALLS(super) ' + f.vipDiscount, 'via CALLS ' + f.preview, 'via CALLS ' + f.payMethod, 'via CALLS ' + f.reportMethod].sort());
});

test('F-016-AC19 콜트리: 배치 잡의 스텝은 이름이나 정의 위치가 아니라 NEXT 순서(시작 스텝부터)로 나온다', () => {
  const s = slice();
  const job = s.n('batch:job:dailySalesJob', 'BATCH_JOB', 'dailySalesJob');
  const step = (name, line) => s.n('batch:step:dailySalesJob/' + name, 'BATCH_STEP', name, 'dailySalesJob/' + name, { stepType: 'tasklet' }, loc('c/DailySalesJobConfig.java', line));
  // 이름 순(a < b < c)과 정의 위치 순(10 < 20 < 30)이 모두 실행 순서와 다르다.
  const report = step('aReportStep', 10);
  const close = step('bCloseOrderStep', 20);
  const sum = step('cSalesSumStep', 30);
  const orphan = step('dOrphanStep', 5);
  [report, close, sum, orphan].forEach((id) => s.e(job, 'CONTAINS', id));
  s.e(sum, 'NEXT', close);
  s.e(close, 'NEXT', report, { on: 'COMPLETED' });
  s.e(report, 'NEXT', close, { on: 'RETRY' }); // 되돌아가는 전이가 있어도 끝난다
  const ix = graph.buildIndex(s.graph());
  assert.deepEqual(calltree.childLinks(ix, job, 'callees').map((l) => l.nodeId), [sum, close, report, orphan], '시작 스텝부터 NEXT 순서, 흐름에 없는 스텝은 뒤에');
});

test('F-016-AC19 콜트리: 스텝의 USES는 역할 메서드가 있으면 그 타입 줄을 접는다', () => {
  const s = slice();
  const step = s.n('batch:step:settleJob/settleStep', 'BATCH_STEP', 'settleStep', 'settleJob/settleStep', { stepType: 'chunk' });
  const reader = s.n('java:type:b.SettleItemReader', 'JAVA_TYPE', 'SettleItemReader', 'b.SettleItemReader');
  const read = s.n('java:method:b.SettleItemReader#read()', 'JAVA_METHOD', 'read', 'b.SettleItemReader#read()');
  const writer = s.n('java:type:org.mybatis.spring.batch.MyBatisBatchItemWriter', 'JAVA_TYPE', 'MyBatisBatchItemWriter', 'MyBatisBatchItemWriter', { declared: false });
  const stmt = s.n('mybatis:stmt:settleDao.insertSettle', 'SQL_STATEMENT', 'insertSettle', 'settleDao.insertSettle', { statementType: 'INSERT' });
  s.e(reader, 'CONTAINS', read);
  s.e(step, 'USES', reader, { bean: 'settleItemReader', role: 'reader' });
  s.e(step, 'USES', read, { bean: 'settleItemReader', role: 'reader' });
  s.e(step, 'USES', writer, { bean: 'settleWriter', role: 'writer' });
  s.e(step, 'EXECUTES', stmt, { role: 'writer', bean: 'settleWriter' });
  const ix = graph.buildIndex(s.graph());
  const links = calltree.childLinks(ix, step, 'callees');
  assert.deepEqual(links.map((l) => l.nodeId).sort(), [read, writer, stmt].sort());
  assert.equal(links.find((l) => l.nodeId === read).label, 'USES(reader)');
  assert.equal(links.find((l) => l.nodeId === stmt).label, 'EXECUTES(writer)');
});

test('F-016-AC19 콜트리: 같은 대상의 CALLS와 CONTAINS(JS)는 한 줄로 접고, 위로는 호출자가 없을 때만 모듈로 올라간다', () => {
  const s = slice();
  const mod = s.n('js:module:frontend/src/App.jsx', 'JS_MODULE', 'App.jsx', 'frontend/src/App.jsx', { moduleKind: 'esm' });
  const app = s.n('js:function:frontend/src/App.jsx#App', 'JS_FUNCTION', 'App', 'frontend/src/App.jsx#App', { kind: 'function', component: true });
  const render = s.n('js:function:frontend/src/App.jsx#App.renderOrders', 'JS_FUNCTION', 'renderOrders', 'frontend/src/App.jsx#App.renderOrders', { kind: 'arrow' });
  const helper = s.n('js:function:frontend/src/App.jsx#helper', 'JS_FUNCTION', 'helper', 'frontend/src/App.jsx#helper', { kind: 'function' });
  s.e(mod, 'CONTAINS', app);
  s.e(mod, 'CONTAINS', helper);
  s.e(mod, 'CALLS', app, { callType: 'jsx' }, 'RESOLVED', loc('frontend/src/App.jsx', 40));
  s.e(app, 'CONTAINS', render);
  s.e(app, 'CALLS', render, { callType: 'call' }, 'RESOLVED', loc('frontend/src/App.jsx', 30));
  const ix = graph.buildIndex(s.graph());
  const fromModule = calltree.childLinks(ix, mod, 'callees');
  assert.deepEqual(fromModule.map((l) => l.kind + ' ' + l.nodeId).sort(), ['CALLS ' + app, 'CONTAINS ' + helper].sort());
  assert.equal(fromModule.find((l) => l.nodeId === app).label, 'CALLS(jsx)');
  assert.deepEqual(calltree.childLinks(ix, app, 'callees').map((l) => l.kind + ' ' + l.nodeId), ['CALLS ' + render]);
  assert.deepEqual(calltree.childLinks(ix, render, 'callers').map((l) => l.kind + ' ' + l.nodeId), ['CALLS ' + app]);
  assert.deepEqual(calltree.childLinks(ix, app, 'callers').map((l) => l.kind + ' ' + l.nodeId), ['CALLS ' + mod]);
  assert.deepEqual(calltree.childLinks(ix, helper, 'callers').map((l) => l.kind + ' ' + l.nodeId), ['CONTAINS ' + mod], '호출자가 없으면 정의된 모듈로');
});

test('F-016-AC19 콜트리: redirect·forward, 함수 참조(ref) 같은 호출 방식을 관계 이름에 적는다', () => {
  const s = slice();
  const save = s.n('java:method:w.OrderController#save()', 'JAVA_METHOD', 'save', 'w.OrderController#save()');
  const list = s.n('java:method:w.OrderController#list()', 'JAVA_METHOD', 'list', 'w.OrderController#list()');
  const svc = s.n('java:method:s.OrderService#find()', 'JAVA_METHOD', 'find', 's.OrderService#find()');
  const f = s.n('js:function:a.js#init', 'JS_FUNCTION', 'init', 'a.js#init');
  const g = s.n('js:function:a.js#onClick', 'JS_FUNCTION', 'onClick', 'a.js#onClick');
  s.e(save, 'CALLS', list, { callType: 'redirect', path: '/order/list', viewName: 'redirect:/order/list' }, 'INFERRED');
  s.e(save, 'CALLS', svc, { callType: 'interface' });
  s.e(f, 'CALLS', g, { callType: 'ref', resolution: 'global' }, 'INFERRED');
  const ix = graph.buildIndex(s.graph());
  const links = calltree.childLinks(ix, save, 'callees');
  assert.equal(links.find((l) => l.nodeId === list).label, 'CALLS(redirect)');
  assert.equal(links.find((l) => l.nodeId === list).confidence, 'INFERRED');
  assert.equal(links.find((l) => l.nodeId === svc).label, 'CALLS');
  assert.equal(calltree.childLinks(ix, f, 'callees')[0].label, 'CALLS(ref)');
  // 보통의 호출 방식은 적지 않는다. constructor는 객체의 기본 속성 이름이기도 해서 따로 확인한다.
  const ctor = s.n('java:method:s.OrderVO#<init>()', 'JAVA_METHOD', '<init>', 's.OrderVO#<init>()', { constructor: true });
  s.e(list, 'CALLS', ctor, { callType: 'constructor' });
  assert.equal(calltree.childLinks(graph.buildIndex(s.graph()), list, 'callees', { showAccessors: true })[0].label, 'CALLS');
});

test('F-016-AC19 콜트리: 요청 노드는 이름이 같아도 구분되게 요청 지점(파일:줄)을 준다', () => {
  const s = slice();
  const ep = s.n('http:endpoint:GET /order/list', 'HTTP_ENDPOINT', 'GET /order/list');
  const r1 = s.n('http:request:GET /order/list@views/common/header.jsp:12', 'HTTP_REQUEST', 'GET /order/list', 'GET /order/list', { api: 'link' }, loc('views/common/header.jsp', 12));
  const r2 = s.n('http:request:GET /order/list@views/order/form.jsp:30', 'HTTP_REQUEST', 'GET /order/list', 'GET /order/list', { api: 'form' }, loc('views/order/form.jsp', 30));
  s.e(r1, 'MATCHES', ep);
  s.e(r2, 'MATCHES', ep);
  const ix = graph.buildIndex(s.graph());
  const root = calltree.createRoot(ix, ep, 'callers');
  const kids = calltree.expand(ix, root);
  assert.deepEqual(kids.map((k) => calltree.whereOf(k)).sort(), ['views/common/header.jsp:12', 'views/order/form.jsp:30']);
});

function screenFlow() {
  const s = slice();
  const ep = s.n('http:endpoint:GET /order/list', 'HTTP_ENDPOINT', 'GET /order/list');
  const list = s.n('java:method:w.OrderController#list()', 'JAVA_METHOD', 'list', 'w.OrderController#list()');
  const page = s.n('jsp:page:views/order/list.jsp', 'JSP_PAGE', 'list.jsp', 'views/order/list.jsp');
  const req = s.n('http:request:GET /order/detail@views/order/list.jsp:40', 'HTTP_REQUEST', 'GET /order/detail', 'GET /order/detail', { api: 'link' });
  const ep2 = s.n('http:endpoint:GET /order/detail', 'HTTP_ENDPOINT', 'GET /order/detail');
  const detail = s.n('java:method:w.OrderController#detail()', 'JAVA_METHOD', 'detail', 'w.OrderController#detail()');
  const stmt = s.n('mybatis:stmt:m.selectOrder', 'SQL_STATEMENT', 'selectOrder', 'm.selectOrder', { statementType: 'SELECT' });
  const stmt2 = s.n('mybatis:stmt:m.selectOrderList', 'SQL_STATEMENT', 'selectOrderList', 'm.selectOrderList', { statementType: 'SELECT' });
  const table = s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER');
  s.e(ep, 'ROUTES_TO', list);
  s.e(list, 'RENDERS', page, { viewName: 'order/list' });
  s.e(list, 'EXECUTES', stmt2);
  s.e(stmt2, 'READS', table);
  s.e(page, 'REQUESTS', req);
  s.e(req, 'MATCHES', ep2);
  s.e(ep2, 'ROUTES_TO', detail);
  s.e(detail, 'EXECUTES', stmt);
  s.e(stmt, 'READS', table);
  return { graph: s.graph(), ep, list, page, req, ep2, detail, stmt, stmt2, table };
}

test('F-016-AC20 모두 펼치기: 컨트롤러가 그리는 화면 아래의 화면 이동은 고를 때만 펼친다', () => {
  const f = screenFlow();
  const ix = graph.buildIndex(f.graph);
  const root = calltree.createRoot(ix, f.ep, 'callees');
  const r = calltree.expandAll(ix, root, { maxDepth: 20 });
  const all = paths(root);
  assert.ok(all.some((p) => containsInOrder(p, [f.ep, f.list, f.stmt2, f.table])), '자기 흐름은 끝까지');
  assert.ok(all.some((p) => p[p.length - 1] === f.page), '화면은 보이지만 그 아래는 펼치지 않는다');
  assert.ok(!all.some((p) => p.includes(f.ep2)));
  assert.equal(r.screens, 1);
  assert.equal(r.truncated, false);
  // 손으로는 펼칠 수 있다
  const pageNode = root.children[0].children.find((c) => c.id === f.page);
  assert.equal(calltree.hasChildren(ix, pageNode), true);

  const full = calltree.createRoot(ix, f.ep, 'callees');
  const r2 = calltree.expandAll(ix, full, { maxDepth: 20, expandScreens: true });
  assert.ok(paths(full).some((p) => containsInOrder(p, [f.ep, f.list, f.page, f.req, f.ep2, f.detail, f.stmt, f.table])));
  assert.equal(r2.screens, 0);

  // 화면이 기준이면 그 화면의 요청은 펼친다. 다음 화면에서 다시 멈춘다.
  const fromPage = calltree.createRoot(ix, f.page, 'callees');
  calltree.expandAll(ix, fromPage, { maxDepth: 20 });
  assert.ok(paths(fromPage).some((p) => containsInOrder(p, [f.page, f.req, f.ep2, f.detail, f.stmt, f.table])));
});

test('F-016-AC20 모두 펼치기: 위로 올라갈 때도 화면을 그리는 컨트롤러에서 멈춘다', () => {
  const f = screenFlow();
  const ix = graph.buildIndex(f.graph);
  const root = calltree.createRoot(ix, f.stmt, 'callers');
  const r = calltree.expandAll(ix, root, { maxDepth: 20 });
  const all = paths(root);
  assert.ok(all.some((p) => containsInOrder(p, [f.stmt, f.detail, f.ep2, f.req, f.page, f.list])), '화면과 그 화면을 그리는 컨트롤러까지');
  assert.ok(!all.some((p) => p.includes(f.ep)), '그 컨트롤러에 이르는 화면 이동은 펼치지 않는다');
  assert.equal(r.screens, 1);
  const full = calltree.createRoot(ix, f.stmt, 'callers');
  calltree.expandAll(ix, full, { maxDepth: 20, expandScreens: true });
  assert.ok(paths(full).some((p) => containsInOrder(p, [f.stmt, f.detail, f.ep2, f.req, f.page, f.list, f.ep])));
});

// ---------------------------------------------------------------- ERD (F-016-AC21, AC22)

function erdSlice() {
  const s = slice();
  const table = (name, attrs) => s.n('db:table:' + name, 'DB_TABLE', name, name, attrs);
  const column = (t, name, attrs) => {
    const id = s.n('db:column:' + t + '.' + name, 'DB_COLUMN', name, t + '.' + name, attrs);
    s.e('db:table:' + t, 'CONTAINS', id);
    return id;
  };
  table('TB_ORDER', { comment: '주문', schema: 'SHOP', primaryKey: ['ORDER_ID'] });
  column('TB_ORDER', 'ORDER_ID', { dataType: 'NUMBER(12)', ordinal: 1, nullable: false, primaryKey: true, comment: '주문ID' });
  column('TB_ORDER', 'ORDER_STATUS', { dataType: 'VARCHAR2(10)', ordinal: 2, nullable: false, primaryKey: false });
  table('TB_CODE', { comment: '공통코드', primaryKey: ['GROUP_CD', 'CODE'] });
  column('TB_CODE', 'GROUP_CD', { dataType: 'VARCHAR2(30)', ordinal: 1 });
  column('TB_CODE', 'CODE', { dataType: 'VARCHAR2(10)', ordinal: 2 });
  table('TB_PAYMENT', { comment: '결제', primaryKey: ['PAYMENT_ID'] });
  column('TB_PAYMENT', 'PAYMENT_ID', { dataType: 'NUMBER(12)', ordinal: 1, nullable: false, primaryKey: true });
  column('TB_PAYMENT', 'ORDER_ID', { dataType: 'NUMBER(12)', ordinal: 2, nullable: false, primaryKey: false });
  table('TB_DELIVERY', { comment: '배송', primaryKey: ['DELIVERY_ID'], indexes: ['UX_DELIVERY_01 UNIQUE(ORDER_ID)'] });
  column('TB_DELIVERY', 'DELIVERY_ID', { dataType: 'NUMBER(12)', ordinal: 1, nullable: false, primaryKey: true });
  column('TB_DELIVERY', 'ORDER_ID', { dataType: 'NUMBER(12)', ordinal: 2, nullable: false, primaryKey: false, unique: true, indexed: true });
  table('TB_CATEGORY', { comment: '상품 카테고리', primaryKey: ['CATEGORY_ID'] });
  column('TB_CATEGORY', 'CATEGORY_ID', { dataType: 'NUMBER(8)', ordinal: 1, nullable: false, primaryKey: true });
  column('TB_CATEGORY', 'PARENT_ID', { dataType: 'NUMBER(8)', ordinal: 2, nullable: true, primaryKey: false });
  table('TB_MEMBER', { comment: '회원', primaryKey: ['MEMBER_ID'], uniqueKeys: ['EMAIL', 'MEMBER_NM,JOIN_DT'] });
  column('TB_MEMBER', 'MEMBER_ID', { dataType: 'NUMBER(12)', ordinal: 1 });
  column('TB_MEMBER', 'LOGIN_ID', { dataType: 'VARCHAR2(30)', ordinal: 2, unique: true });
  column('TB_MEMBER', 'EMAIL', { dataType: 'VARCHAR2(100)', ordinal: 3 });
  column('TB_MEMBER', 'MEMBER_NM', { dataType: 'VARCHAR2(50)', ordinal: 4 });
  table('TB_BROKEN', { declared: false });
  s.n('db:column:TB_BROKEN.BROKEN_ID', 'DB_COLUMN', 'BROKEN_ID', 'BROKEN_ID', { declared: false });
  s.n('db:column:TB_BROKEN.USE_YN', 'DB_COLUMN', 'USE_YN', 'USE_YN', { declared: false });
  table('TB_HOLIDAY', { declared: false });
  s.n('db:view:VW_ORDER_SUMMARY', 'DB_VIEW', 'VW_ORDER_SUMMARY');
  s.e('db:view:VW_ORDER_SUMMARY', 'CONTAINS', s.n('db:column:VW_ORDER_SUMMARY.ORDER_ID', 'DB_COLUMN', 'ORDER_ID', 'VW_ORDER_SUMMARY.ORDER_ID', { dataType: 'NUMBER(12)', ordinal: 1 }));

  s.e('db:table:TB_CODE', 'REFERENCES', 'db:table:TB_ORDER', { cardinality: 'unknown', columns: ['CODE'], directionKnown: false,
    evidence: ['mybatis:stmt:m.selectOrderList'], evidenceCount: 1, refColumns: ['ORDER_STATUS'], source: 'INFERRED' }, 'INFERRED');
  s.e('db:table:TB_PAYMENT', 'REFERENCES', 'db:table:TB_ORDER', { cardinality: 'N:1', columns: ['ORDER_ID'], directionKnown: true,
    evidence: ['mybatis:stmt:a', 'mybatis:stmt:b', 'mybatis:stmt:c'], evidenceCount: 3, refColumns: ['ORDER_ID'], source: 'INFERRED' }, 'INFERRED');
  s.e('db:table:TB_DELIVERY', 'REFERENCES', 'db:table:TB_ORDER', { columns: ['ORDER_ID'], constraint: 'FK_DELIVERY_ORDER', onDelete: 'CASCADE', refColumns: ['ORDER_ID'], source: 'DDL' });
  s.e('db:table:TB_CATEGORY', 'REFERENCES', 'db:table:TB_CATEGORY', { columns: ['PARENT_ID'], constraint: 'FK_CATEGORY_PARENT', refColumns: ['CATEGORY_ID'], source: 'DDL' });
  s.e('db:view:VW_ORDER_SUMMARY', 'REFERENCES', 'db:table:TB_ORDER', { cardinality: '1:1', columns: ['ORDER_ID'], directionKnown: true,
    evidence: ['mybatis:stmt:v'], evidenceCount: 1, refColumns: ['ORDER_ID'], source: 'INFERRED' }, 'INFERRED');
  return s.graph();
}

test('F-016-AC21 ERD: 방향을 모르는 추론 관계(directionKnown=false)는 카디널리티 없는 선으로 그린다', () => {
  const ix = graph.buildIndex(erdSlice());
  const scope = erd.selectScope(ix, {});
  const undirected = scope.relations.find((r) => r.from === 'db:table:TB_CODE');
  assert.equal(undirected.directionKnown, false);
  assert.equal(scope.relations.find((r) => r.from === 'db:table:TB_PAYMENT').directionKnown, true);
  assert.equal(scope.relations.find((r) => r.from === 'db:table:TB_DELIVERY').directionKnown, true, 'DDL 관계는 방향을 안다');
  const out = erd.toMermaid(ix, scope, { columns: 'keys' });
  // Mermaid는 카디널리티 없는 선을 쓸 수 없어 소스에는 양쪽 }o o{ 로 적고(표준 산출물 erd.mmd와 같다), 그린 뒤에 끝 표시를 지운다.
  assert.ok(out.source.includes('  TB_ORDER }o..o{ TB_CODE : "CODE = ORDER_STATUS (추론 1건, 방향 미상)"\n'), out.source);
  const lines = out.source.split('\n').filter((l) => / : "/.test(l));
  assert.deepEqual(out.undirected, [lines.findIndex((l) => l.includes('방향 미상'))]);
  assert.equal(out.undirected.length, 1);
});

test('F-016-AC21 ERD: 카디널리티는 엣지의 cardinality와 컬럼의 nullable·unique에서 정한다', () => {
  const ix = graph.buildIndex(erdSlice());
  const src = erd.toMermaid(ix, erd.selectScope(ix, { includeViews: true }), { columns: 'keys' }).source;
  assert.ok(src.includes('  TB_ORDER |o..o{ TB_PAYMENT : "ORDER_ID (추론 3건)"\n'), 'N:1');
  assert.ok(src.includes('  TB_ORDER |o..o| VW_ORDER_SUMMARY : "ORDER_ID (추론 1건)"\n'), '1:1');
  assert.ok(src.includes('  TB_ORDER ||--o| TB_DELIVERY : "FK_DELIVERY_ORDER"\n'), 'FK 컬럼이 unique면 자식 쪽은 0..1');
  assert.ok(src.includes('  TB_CATEGORY |o--o{ TB_CATEGORY : "FK_CATEGORY_PARENT"\n'), 'FK 컬럼이 nullable이면 부모 쪽은 0..1');
});

test('F-016-AC21 ERD 범례: DDL, 추론, 방향 미상, 카디널리티 모름(F-013-AC30), DDL 없음', () => {
  assert.deepEqual(erd.LEGEND.map((l) => l.key), ['ddl', 'inferred', 'undirected', 'cardinalityUnknown', 'undeclared']);
  const u = erd.LEGEND.find((l) => l.key === 'undirected');
  assert.equal(u.line, 'dashed');
  assert.equal(u.ends, false);
  assert.equal(erd.LEGEND.find((l) => l.key === 'inferred').ends, 'optional', '추론 관계의 부모 쪽은 0 또는 1이다(F-013-R28)');
  assert.ok(u.description.includes('directionKnown=false'));
});

test('F-016-AC22 ERD: PK와 UK를 테이블의 primaryKey·uniqueKeys와 컬럼의 unique에서 읽는다', () => {
  const ix = graph.buildIndex(erdSlice());
  const src = erd.toMermaid(ix, erd.selectScope(ix, { tables: ['db:table:TB_MEMBER', 'db:table:TB_CODE'], depth: 0 }), { columns: 'all' }).source;
  assert.ok(src.includes('    NUMBER(12) MEMBER_ID PK\n'), '컬럼에 primaryKey가 없어도 테이블의 primaryKey로');
  assert.ok(src.includes('    VARCHAR2(30) LOGIN_ID UK\n'), '컬럼의 unique');
  assert.ok(src.includes('    VARCHAR2(100) EMAIL UK\n'), '테이블의 단일 컬럼 uniqueKeys');
  assert.ok(src.includes('    VARCHAR2(50) MEMBER_NM\n'), '복합 UNIQUE의 컬럼에는 표시하지 않는다');
  assert.ok(src.includes('    VARCHAR2(30) GROUP_CD PK\n') && src.includes('    VARCHAR2(10) CODE PK\n'));
});

test('F-016-AC22 ERD: 테이블 주석을 이름 옆에 적고, DDL 없는 테이블은 참조된 컬럼만 보인다', () => {
  const ix = graph.buildIndex(erdSlice());
  const out = erd.toMermaid(ix, erd.selectScope(ix, {}), { columns: 'all' });
  const src = out.source;
  assert.ok(src.includes('  TB_ORDER["TB_ORDER (주문)"] {\n'), src);
  assert.ok(src.includes('  TB_BROKEN["TB_BROKEN (DDL 없음)"]:::undeclared {\n    unknown BROKEN_ID "참조된 컬럼"\n    unknown USE_YN "참조된 컬럼"\n  }\n'), src);
  assert.ok(src.includes('  TB_HOLIDAY["TB_HOLIDAY (DDL 없음)"]:::undeclared\n'), '컬럼을 모르는 테이블은 상자만');
  assert.equal(out.aliases.get('TB_ORDER'), 'db:table:TB_ORDER');
  const plain = erd.toMermaid(ix, erd.selectScope(ix, {}), { columns: 'all', comments: false }).source;
  assert.ok(plain.includes('  TB_ORDER {\n'));
  assert.ok(!plain.includes('주문ID'));
  // Mermaid는 classDef 값의 공백을 지운다("6 4" → "64"). 공백 없는 값만 쓴다.
  assert.match(src, /\n {2}classDef undeclared stroke-dasharray:\d+\n/);
});

test('F-016-AC22 ERD: 뷰는 고를 때만 넣고 테이블과 구분한다', () => {
  const ix = graph.buildIndex(erdSlice());
  const off = erd.selectScope(ix, {});
  assert.ok(off.tables.every((t) => t.kind === 'DB_TABLE'));
  assert.ok(off.relations.every((r) => r.from !== 'db:view:VW_ORDER_SUMMARY'));
  const on = erd.selectScope(ix, { includeViews: true });
  assert.ok(on.tables.some((t) => t.id === 'db:view:VW_ORDER_SUMMARY'));
  const src = erd.toMermaid(ix, on, { columns: 'all' }).source;
  assert.ok(src.includes('  VW_ORDER_SUMMARY["VW_ORDER_SUMMARY (VIEW)"]:::view {\n    NUMBER(12) ORDER_ID\n  }\n'), src);
  assert.match(src, /\n {2}classDef view /);
  // 테이블에서 이웃으로 넓힐 때도 같다
  const near = erd.selectScope(ix, { tables: ['db:table:TB_ORDER'], depth: 1 });
  assert.deepEqual(near.tables.map((t) => t.name).sort(), ['TB_CODE', 'TB_DELIVERY', 'TB_ORDER', 'TB_PAYMENT']);
  const nearViews = erd.selectScope(ix, { tables: ['db:table:TB_ORDER'], depth: 1, includeViews: true });
  assert.ok(nearViews.tables.some((t) => t.id === 'db:view:VW_ORDER_SUMMARY'));
});

test('F-016-AC21 다이어그램: 그려진 관계 선의 ID에서 관계 순번을 꺼낸다', () => {
  assert.equal(diagram.relationIndexOfEdgeId('ca-diagram-1-id_entity-TB_ORDER-10_entity-TB_CODE-4_2'), 2);
  assert.equal(diagram.relationIndexOfEdgeId('id_entity-TB_CATEGORY-3_entity-TB_CATEGORY-3_19'), 19);
  assert.equal(diagram.relationIndexOfEdgeId('ca-diagram-1-entity-TB_ORDER-10'), null);
  assert.equal(diagram.relationIndexOfEdgeId(''), null);
  assert.equal(diagram.relationIndexOfEdgeId(null), null);
});

// ---------------------------------------------------------------- 클래스 다이어그램 (F-016-AC23)

function classSlice() {
  const s = slice();
  const type = (fqn, attrs) => {
    const simple = fqn.substring(fqn.lastIndexOf('.') + 1);
    const declared = !attrs || attrs.declared !== false;
    return s.n('java:type:' + fqn, 'JAVA_TYPE', simple.substring(simple.lastIndexOf('$') + 1) || simple, declared ? fqn : simple, attrs);
  };
  const method = (owner, name, params, attrs) => {
    const id = s.n('java:method:' + owner + '#' + name + '(' + params + ')', 'JAVA_METHOD', name, owner + '#' + name + '(' + params + ')', attrs);
    s.e('java:type:' + owner, 'CONTAINS', id);
    return id;
  };
  const svc = type('com.example.shop.order.service.OrderService', { typeKind: 'interface', modifiers: ['public'], typeParameters: [] });
  const impl = type('com.example.shop.order.service.OrderServiceImpl', { typeKind: 'class', modifiers: ['public'], stereotype: 'service', typeParameters: [] });
  const calc = type('com.example.shop.order.service.OrderServiceImpl$AmountCalculator', { typeKind: 'class', modifiers: ['private'], typeParameters: [] });
  const anon = s.n('java:type:com.example.shop.order.service.OrderServiceImpl$1', 'JAVA_TYPE', 'OrderServiceImpl$1', 'com.example.shop.order.service.OrderServiceImpl$1',
    { anonymous: true, typeKind: 'class', modifiers: [], typeParameters: [], enclosingMethod: 'java:method:x' });
  const base = type('com.example.shop.common.BaseService', { typeKind: 'class', modifiers: ['public', 'abstract'], typeParameters: ['T extends BaseVO'] });
  const vo = type('com.example.shop.order.vo.OrderVO', { typeKind: 'class', modifiers: ['public'], typeParameters: [] });
  const tasklet = type('org.springframework.batch.core.step.tasklet.Tasklet', { declared: false, external: true });
  const ref = type('org.mybatis.spring.SqlSessionFactoryBean', { declared: false });
  s.e(impl, 'IMPLEMENTS', svc);
  s.e(impl, 'EXTENDS', base);
  s.e(impl, 'IMPLEMENTS', tasklet);
  s.e(impl, 'DEPENDS_ON', calc);
  s.e(impl, 'DEPENDS_ON', vo);
  s.e(impl, 'CONTAINS', calc);
  s.e(impl, 'CONTAINS', anon);
  method('com.example.shop.order.service.OrderService', 'getOrder', 'java.lang.Long', { modifiers: [], parameterTypes: ['Long'], returnType: 'OrderVO' });
  method('com.example.shop.order.service.OrderServiceImpl', 'getOrder', 'java.lang.Long', { modifiers: ['public'], parameterTypes: ['Long'], returnType: 'OrderVO' });
  method('com.example.shop.order.service.OrderServiceImpl', '<clinit>', '', { modifiers: ['static'], parameterTypes: [], synthetic: true });
  method('com.example.shop.order.service.OrderServiceImpl', 'getMapper', '', { modifiers: ['public'], parameterTypes: [], returnType: 'OrderMapper', synthetic: true, lombok: 'Getter' });
  method('com.example.shop.order.service.OrderServiceImpl$1', '<init>', '', { constructor: true, modifiers: [], parameterTypes: [], synthetic: true });
  return { graph: s.graph(), svc, impl, calc, anon, base, vo, tasklet, ref };
}
const PKG = 'com.example.shop.order.service';

test('F-016-AC23 클래스 다이어그램: 익명·로컬 타입과 합성 멤버는 고를 때만 보인다', () => {
  const f = classSlice();
  const ix = graph.buildIndex(f.graph);
  const scope = classDiagram.selectScope(ix, { packages: [PKG] });
  assert.deepEqual(scope.types.map((t) => t.id).sort(), [f.svc, f.impl, f.calc].sort());
  const withAnon = classDiagram.selectScope(ix, { packages: [PKG], includeAnonymous: true });
  assert.ok(withAnon.types.some((t) => t.id === f.anon));
  const picked = classDiagram.selectScope(ix, { types: [f.anon] });
  assert.deepEqual(picked.types.map((t) => t.id), [f.anon], '직접 고른 타입은 그대로 넣는다');

  const src = classDiagram.toMermaid(ix, scope, { showMembers: true }).source;
  assert.ok(src.includes('+getOrder(Long) OrderVO'));
  assert.ok(!src.includes('getMapper'), src);
  assert.ok(!src.includes('clinit'));
  const all = classDiagram.toMermaid(ix, scope, { showMembers: true, showSynthetic: true }).source;
  assert.ok(all.includes('+getMapper() OrderMapper'));
  const anonSrc = classDiagram.toMermaid(ix, withAnon, { showMembers: true }).source;
  assert.ok(anonSrc.includes('<<anonymous>>'));
});

test('F-016-AC23 클래스 다이어그램: 인터페이스 멤버는 public으로, 중첩 타입은 바깥 이름과 함께, 제네릭은 꺾쇠를 살려 적는다', () => {
  const f = classSlice();
  const ix = graph.buildIndex(f.graph);
  const scope = classDiagram.selectScope(ix, { packages: [PKG], includeSupertypes: true });
  const out = classDiagram.toMermaid(ix, scope, { showMembers: true });
  const src = out.source;
  assert.match(src, /class c\d+\["OrderService"\] \{\n {4}<<interface>>\n {4}\+getOrder\(Long\) OrderVO\n/);
  assert.ok(src.includes('["OrderServiceImpl.AmountCalculator"]'), src);
  // 라벨에 < > 를 그대로 두면 Mermaid가 태그로 보고 지운다. 엔터티로 적는다.
  assert.ok(src.includes('["BaseService&lt;T extends BaseVO&gt;"]'), src);
  assert.ok(!/\["[^"\n]*[<>][^"\n]*"\]/.test(src), '라벨에 날 꺾쇠가 없다');
});

test('F-016-AC23 클래스 다이어그램: 범위 밖 상위 타입과 외부 타입을 켜고 끈다', () => {
  const f = classSlice();
  const ix = graph.buildIndex(f.graph);
  const plain = classDiagram.selectScope(ix, { packages: [PKG] });
  assert.ok(!plain.types.some((t) => t.id === f.base));
  const sup = classDiagram.selectScope(ix, { packages: [PKG], includeSupertypes: true });
  const ids = sup.types.map((t) => t.id);
  assert.ok(ids.includes(f.base) && ids.includes(f.tasklet), '상속·구현 대상은 범위 밖이어도 넣는다');
  assert.ok(!ids.includes(f.vo), '의존 대상은 넣지 않는다');
  assert.ok(sup.relations.some((e) => e.kind === 'EXTENDS' && e.to === f.base));
  assert.deepEqual(Array.from(sup.outside).sort(), [f.base, f.tasklet].sort());
  const noExt = classDiagram.selectScope(ix, { packages: [PKG], includeSupertypes: true, includeExternal: false });
  assert.ok(noExt.types.some((t) => t.id === f.base));
  assert.ok(!noExt.types.some((t) => t.id === f.tasklet), '외부 타입을 끄면 소스 없는 타입은 빠진다');
  const src = classDiagram.toMermaid(ix, sup, {}).source;
  assert.match(src, /\["Tasklet"\]:::external/);
  // 이웃으로 넓힐 때도 외부 타입 필터가 먹는다
  const near = classDiagram.selectScope(ix, { types: [f.impl], depth: 1, includeExternal: false });
  assert.ok(!near.types.some((t) => t.id === f.tasklet));
  assert.ok(near.types.some((t) => t.id === f.vo));
});

test('F-016-AC23 클래스 다이어그램: 패키지 목록은 소스가 없는 패키지를 구분한다', () => {
  const f = classSlice();
  const list = classDiagram.packages(graph.buildIndex(f.graph));
  const by = (name) => list.find((p) => p.name === name);
  assert.equal(by(PKG).external, false);
  assert.equal(by(PKG).count, 4);
  assert.equal(by('org.springframework.batch.core.step.tasklet').external, true);
  assert.equal(by('org.mybatis.spring').external, true, 'external 표시가 없어도 declared=false면 소스가 없다');
  // 프로젝트 패키지가 먼저
  assert.ok(list.findIndex((p) => p.external) > list.findIndex((p) => p.name === PKG));
  assert.ok(list.slice(0, list.findIndex((p) => p.external)).every((p) => !p.external));
});

// ---------------------------------------------------------------- CRUD (F-016-AC24)

test('F-016-AC24 CRUD: 컬럼 READS/WRITES는 세지 않는다. R은 테이블 수준 READS가 있을 때만이다', () => {
  const s = slice();
  const sales = s.n('db:table:TB_DAILY_SALES', 'DB_TABLE', 'TB_DAILY_SALES');
  const item = s.n('db:table:TB_ORDER_ITEM', 'DB_TABLE', 'TB_ORDER_ITEM');
  const col = (t, name) => {
    const id = s.n('db:column:' + t + '.' + name, 'DB_COLUMN', name, t + '.' + name);
    s.e('db:table:' + t, 'CONTAINS', id);
    return id;
  };
  const salesDt = col('TB_DAILY_SALES', 'SALES_DT');
  const salesAmt = col('TB_DAILY_SALES', 'SALES_AMT');
  const itemAmt = col('TB_ORDER_ITEM', 'AMT');
  // DELETE FROM TB_DAILY_SALES WHERE SALES_DT = ? : 대상 테이블에는 테이블 수준 READS가 없고(계약 design 3.4) WHERE의 컬럼 READS만 있다.
  const del = s.n('mybatis:stmt:b.deleteDailySales', 'SQL_STATEMENT', 'deleteDailySales', 'b.deleteDailySales', { statementType: 'DELETE' });
  s.e(del, 'WRITES', sales, { op: 'DELETE' });
  s.e(del, 'READS', salesDt);
  const upd = s.n('mybatis:stmt:b.updateDailySales', 'SQL_STATEMENT', 'updateDailySales', 'b.updateDailySales', { statementType: 'UPDATE' });
  s.e(upd, 'WRITES', sales, { op: 'UPDATE' });
  s.e(upd, 'WRITES', salesAmt, { op: 'UPDATE' });
  s.e(upd, 'READS', salesDt);
  const merge = s.n('mybatis:stmt:b.mergeDailySales', 'SQL_STATEMENT', 'mergeDailySales', 'b.mergeDailySales', { statementType: 'MERGE' });
  s.e(merge, 'WRITES', sales, { op: 'MERGE' });
  s.e(merge, 'WRITES', salesAmt, { op: 'MERGE' });
  s.e(merge, 'WRITES', salesDt, { op: 'MERGE' });
  s.e(merge, 'READS', salesDt);
  s.e(merge, 'READS', item);
  s.e(merge, 'READS', itemAmt);
  // INSERT INTO TB_DAILY_SALES SELECT ... FROM TB_DAILY_SALES: 같은 테이블을 실제로 읽으면 테이블 수준 READS가 있다.
  const copy = s.n('mybatis:stmt:b.copyDailySales', 'SQL_STATEMENT', 'copyDailySales', 'b.copyDailySales', { statementType: 'INSERT' });
  s.e(copy, 'WRITES', sales, { op: 'INSERT' });
  s.e(copy, 'WRITES', salesDt, { op: 'INSERT' });
  s.e(copy, 'READS', sales);
  s.e(copy, 'READS', salesDt);
  // 컬럼 엣지만 있는 구문은 행이 되지 않는다(분석기는 테이블 엣지를 항상 만든다).
  const odd = s.n('sql:stmt:x.sql:1', 'SQL_STATEMENT', 'x.sql:1', 'x.sql:1', { statementType: 'SELECT' });
  s.e(odd, 'READS', itemAmt);
  const m = crud.buildMatrix(graph.buildIndex(s.graph()), { rowKind: 'statement' });
  const cells = (id) => m.rows.find((r) => r.id === id).cells;
  assert.deepEqual(cells(del), { [sales]: 'D' }, 'WHERE에서 읽은 컬럼은 R이 아니다');
  assert.deepEqual(cells(upd), { [sales]: 'U' });
  assert.deepEqual(cells(merge), { [sales]: 'CU', [item]: 'R' }, 'MERGE는 C와 U');
  assert.deepEqual(cells(copy), { [sales]: 'CR' });
  assert.equal(m.rows.find((r) => r.id === odd), undefined);
  assert.deepEqual(m.tables.map((t) => t.name), ['TB_DAILY_SALES', 'TB_ORDER_ITEM'], '컬럼은 열이 되지 않는다');
});

// ---------------------------------------------------------------- 진단·미해결 (F-016-AC25)

function diagSlice() {
  const s = slice();
  const exportOrders = s.n('js:function:frontend/src/api/orderApi.js#exportOrders', 'JS_FUNCTION', 'exportOrders', 'frontend/src/api/orderApi.js#exportOrders');
  const ajaxPost = s.n('js:function:src/main/webapp/resources/js/common.js#ajaxPost', 'JS_FUNCTION', 'ajaxPost', 'src/main/webapp/resources/js/common.js#ajaxPost');
  const url = s.n('unresolved:http-url:url', 'UNRESOLVED', 'url', 'url', { category: 'http-url' }, loc('frontend/src/api/orderApi.js', 46));
  s.e(exportOrders, 'REQUESTS', url, {}, 'UNRESOLVED', loc('frontend/src/api/orderApi.js', 46));
  s.e(ajaxPost, 'REQUESTS', url, {}, 'UNRESOLVED', loc('src/main/webapp/resources/js/common.js', 32));
  const purge = s.n('sql:stmt:SP_PURGE_TABLE:42', 'SQL_STATEMENT', 'SP_PURGE_TABLE:42', 'SP_PURGE_TABLE:42', { statementType: 'DELETE' }, loc('db/db2/sp_batch_util.db2', 42));
  const dyn = s.n('unresolved:sql-dynamic:sql:stmt:SP_PURGE_TABLE:42', 'UNRESOLVED', 'DELETE FROM {*} WHERE REG_TS < CURRENT TIMESTAMP - {*} DAYS', 'sql:stmt:SP_PURGE_TABLE:42',
    { category: 'sql-dynamic' }, loc('db/db2/sp_batch_util.db2', 42));
  s.e(purge, 'WRITES', dyn, { op: 'DELETE', text: 'DELETE FROM {*} WHERE REG_TS < CURRENT TIMESTAMP - {*} DAYS' }, 'UNRESOLVED', loc('db/db2/sp_batch_util.db2', 42));
  const caller = s.n('java:method:a.B#run()', 'JAVA_METHOD', 'run', 'a.B#run()', {}, loc('src/a/B.java', 10, 20));
  // 마무리 단계가 만든 노드: ID는 엣지가 가리키던 그대로이고 분류가 없다.
  const dangling = s.n('java:method:x.Legacy#find()', 'UNRESOLVED', 'java:method:x.Legacy#find()', 'java:method:x.Legacy#find()', { expectedPrefix: 'java:method' });
  s.e(caller, 'CALLS', dangling, { callType: 'virtual' }, 'UNRESOLVED', loc('src/a/B.java', 12));
  // 경로가 상수가 아닌 엔드포인트: 미해결 노드가 from이다.
  const ep = s.n('unresolved:http-endpoint:BASE + "/x"', 'UNRESOLVED', 'BASE + "/x"', 'BASE + "/x"', { category: 'http-endpoint' }, loc('src/a/B.java', 9));
  s.e(ep, 'ROUTES_TO', caller, {}, 'UNRESOLVED', loc('src/a/B.java', 9));
  // 엣지가 없는 미해결 노드도 빠뜨리지 않는다.
  const orphan = s.n('unresolved:js-module:./gone', 'UNRESOLVED', './gone', './gone', { category: 'js-module' }, loc('frontend/src/App.jsx', 3));
  s.d('WARN', 'UNRESOLVED_REFERENCE', '해석하지 못한 참조(http-url): url', loc('frontend/src/api/orderApi.js', 46));
  s.d('WARN', 'UNRESOLVED_REFERENCE', '해석하지 못한 참조(http-url): url', loc('src/main/webapp/resources/js/common.js', 32));
  s.d('WARN', 'DYNAMIC_SQL', '동적 SQL을 정적으로 풀 수 없어 대상을 알 수 없음: DELETE FROM {*} (sql:stmt:SP_PURGE_TABLE:42)', loc('db/db2/sp_batch_util.db2', 42));
  s.d('ERROR', 'PARSE_FAILED', '매퍼 XML 파싱 실패', loc('broken/BrokenMapper.xml', 11));
  s.d('INFO', 'EXTERNAL_CALLS_SKIPPED', '기록하지 않은 외부 메서드 호출: JDK 124건', null);
  return { graph: s.graph(), exportOrders, ajaxPost, url, purge, dyn, caller, dangling, ep, orphan };
}

test('F-016-AC25 진단·미해결: 미해결 참조는 엣지 하나가 한 건이고 진단은 진단대로 한 번씩만 나온다', () => {
  const f = diagSlice();
  const r = diag.collect(graph.buildIndex(f.graph));
  assert.equal(r.diagnostics.length, 5, '진단 5건');
  assert.equal(r.unresolved.length, 6, '미해결 엣지 5개 + 엣지 없는 미해결 노드 1개');
  // 같은 미해결 노드를 두 곳에서 참조하면 두 건이다(report.md와 같은 셈법). 노드 행은 따로 없다.
  const urls = r.unresolved.filter((x) => x.targetId === f.url);
  assert.deepEqual(urls.map((x) => x.fromId).sort(), [f.exportOrders, f.ajaxPost].sort());
  assert.deepEqual(urls.map((x) => x.location).sort(), ['frontend/src/api/orderApi.js:46', 'src/main/webapp/resources/js/common.js:32']);
  assert.ok(urls.every((x) => x.category === 'http-url' && x.edgeKind === 'REQUESTS' && x.target === 'url'));
  assert.ok(urls.every((x) => x.nodeId === x.fromId), '누르면 참조한 곳으로 간다');
});

test('F-016-AC25 진단·미해결: 분류는 category, 없으면 expectedPrefix에서 유도하고(java:method → java-call) 원문을 대상으로 보인다', () => {
  const f = diagSlice();
  const r = diag.collect(graph.buildIndex(f.graph));
  const row = (pred) => r.unresolved.find(pred);
  const dyn = row((x) => x.targetId === f.dyn);
  assert.equal(dyn.category, 'sql-dynamic');
  assert.equal(dyn.target, 'DELETE FROM {*} WHERE REG_TS < CURRENT TIMESTAMP - {*} DAYS');
  assert.equal(dyn.edgeKind, 'WRITES');
  const dangling = row((x) => x.targetId === f.dangling);
  assert.equal(dangling.category, 'java-call');
  assert.equal(dangling.categoryLabel, 'java-call');
  assert.equal(dangling.target, 'x.Legacy#find()');
  // 미해결 노드가 from인 엣지(ROUTES_TO): 참조한 곳은 해석된 쪽이다
  const ep = row((x) => x.targetId === f.ep);
  assert.equal(ep.category, 'http-endpoint');
  assert.equal(ep.fromId, f.caller);
  assert.equal(ep.edgeKind, 'ROUTES_TO');
  const orphan = row((x) => x.targetId === f.orphan);
  assert.equal(orphan.category, 'js-module');
  assert.equal(orphan.edgeKind, '');
  assert.equal(orphan.nodeId, f.orphan);
  assert.equal(orphan.location, 'frontend/src/App.jsx:3');
  assert.deepEqual(r.categories, [
    { category: 'http-endpoint', label: 'http-endpoint', count: 1 },
    { category: 'http-url', label: 'http-url', count: 2 },
    { category: 'java-call', label: 'java-call', count: 1 },
    { category: 'js-module', label: 'js-module', count: 1 },
    { category: 'sql-dynamic', label: 'sql-dynamic', count: 1 }
  ]);
});

test('F-016-AC25 진단·미해결: 분류와 코드로 거르고, 같은 지점의 진단은 미해결 참조와 이어 준다', () => {
  const f = diagSlice();
  const r = diag.collect(graph.buildIndex(f.graph));
  assert.deepEqual(diag.filter(r.unresolved, { categories: ['http-url'] }).map((x) => x.category), ['http-url', 'http-url']);
  assert.equal(diag.filter(r.unresolved, { categories: ['http-url', 'sql-dynamic'] }).length, 3);
  assert.equal(diag.filter(r.unresolved, { text: 'ajaxpost' }).length, 1);
  assert.equal(diag.filter(r.unresolved, {}).length, 6);
  assert.deepEqual(r.codes.map((c) => ({ code: c.code, count: c.count })), [
    { code: 'DYNAMIC_SQL', count: 1 }, { code: 'EXTERNAL_CALLS_SKIPPED', count: 1 }, { code: 'PARSE_FAILED', count: 1 }, { code: 'UNRESOLVED_REFERENCE', count: 2 }
  ]);
  assert.equal(diag.filter(r.diagnostics, { codes: ['UNRESOLVED_REFERENCE'] }).length, 2);
  assert.equal(diag.filter(r.diagnostics, { severities: ['ERROR'] }).length, 1);
  assert.equal(r.diagnostics[0].severity, 'ERROR', '심각도 순');
  // 미해결 참조와 같은 지점의 진단: 눌렀을 때 그 참조한 노드로 간다
  const linked = r.diagnostics.filter((d) => d.unresolved);
  assert.equal(linked.length, 3);
  const dyn = r.diagnostics.find((d) => d.code === 'DYNAMIC_SQL');
  assert.equal(dyn.nodeId, f.purge);
  assert.equal(dyn.unresolved.category, 'sql-dynamic');
  assert.equal(r.diagnostics.find((d) => d.code === 'PARSE_FAILED').unresolved, null);
  assert.equal(r.diagnostics.find((d) => d.code === 'PARSE_FAILED').nodeId, null);
});
