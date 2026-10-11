'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core, bigGraph } = require('./helpers.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');

const graph = core('graph');
const erd = core('erd');

function index() { return graph.buildIndex(sampleGraph()); }
const names = (scope) => scope.tables.map((t) => t.name).sort();

test('F-016-AC11 범위를 주지 않으면 모든 테이블과 관계가 들어간다', () => {
  const scope = erd.selectScope(index(), {});
  assert.deepEqual(names(scope), ['COUPONS', 'CUSTOMERS', 'ORDERS', 'ORDER_HISTORY', 'ORDER_ITEMS', 'PRODUCTS', 'SETTLEMENTS']);
  assert.equal(scope.relations.length, 4);
  assert.equal(scope.truncated, false);
  assert.equal(scope.total, 7);
});

test('F-016-AC11 선택한 테이블과 깊이만큼의 이웃', () => {
  const ix = index();
  assert.deepEqual(names(erd.selectScope(ix, { tables: [ids.TB.orders], depth: 0 })), ['ORDERS']);
  const d1 = erd.selectScope(ix, { tables: [ids.TB.orders], depth: 1 });
  assert.deepEqual(names(d1), ['CUSTOMERS', 'ORDERS', 'ORDER_HISTORY', 'ORDER_ITEMS']);
  assert.equal(d1.relations.length, 3);
  const d2 = erd.selectScope(ix, { tables: [ids.TB.orders], depth: 2 });
  assert.deepEqual(names(d2), ['CUSTOMERS', 'ORDERS', 'ORDER_HISTORY', 'ORDER_ITEMS', 'PRODUCTS']);
  // 추론 관계를 끄면 추론으로만 이어진 이웃은 빠진다
  const ddlOnly = erd.selectScope(ix, { tables: [ids.TB.orders], depth: 2, includeInferred: false });
  assert.deepEqual(names(ddlOnly), ['CUSTOMERS', 'ORDERS', 'ORDER_ITEMS']);
  assert.ok(ddlOnly.relations.every((r) => r.source === 'DDL'));
});

test('F-016-AC11 Mermaid 소스: 컬럼 자료형, PK, FK', () => {
  const ix = index();
  const scope = erd.selectScope(ix, { tables: [ids.TB.orders], depth: 1 });
  const src = erd.toMermaid(ix, scope, { columns: 'all' }).source;
  assert.ok(src.startsWith('erDiagram\n'));
  assert.match(src, /ORDERS\["ORDERS \(주문\)"\] \{\n(.*\n)*? {4}NUMBER\(12\) ORDER_ID PK "주문 번호"\n/);
  assert.ok(src.includes('    NUMBER(12) CUSTOMER_ID FK\n'));
  assert.ok(src.includes('    VARCHAR2(10) STATUS\n'));
  assert.ok(src.includes('    TIMESTAMP_WITH_TIME_ZONE CREATED_AT\n'), '공백은 밑줄로');
  assert.ok(src.includes('    NUMBER(12) ORDER_ID FK\n'), 'ORDER_ITEMS.ORDER_ID');
  assert.ok(src.includes('    VARCHAR2(100_CHAR) NAME\n'));
  // 컬럼은 ordinal 순서
  const body = src.substring(src.indexOf('ORDERS["ORDERS (주문)"] {'), src.indexOf('}', src.indexOf('ORDERS["ORDERS (주문)"] {')));
  assert.ok(body.indexOf('ORDER_ID') < body.indexOf('CUSTOMER_ID') && body.indexOf('CUSTOMER_ID') < body.indexOf('STATUS'));
  // 추론 관계의 컬럼은 FK로 표시하지 않는다
  const full = erd.toMermaid(ix, erd.selectScope(ix, {}), { columns: 'all' }).source;
  assert.ok(full.includes('    NUMBER(12) PRODUCT_ID\n'));
  assert.ok(full.includes('    NUMBER(5,0) QTY\n'));
});

test('F-016-AC11 컬럼 표시: 키만 또는 숨김', () => {
  const ix = index();
  const scope = erd.selectScope(ix, { tables: [ids.TB.orders], depth: 1 });
  const keys = erd.toMermaid(ix, scope, { columns: 'keys' }).source;
  assert.ok(keys.includes('ORDER_ID PK'));
  assert.ok(keys.includes('CUSTOMER_ID FK'));
  assert.ok(!keys.includes('STATUS'));
  const none = erd.toMermaid(ix, scope, { columns: 'none' }).source;
  assert.ok(!none.includes('ORDER_ID PK'));
  assert.ok(none.includes('ORDERS'));
});

test('F-016-AC12 DDL 관계는 실선, 추론 관계는 점선', () => {
  const ix = index();
  const out = erd.toMermaid(ix, erd.selectScope(ix, {}), { columns: 'all' });
  const src = out.source;
  assert.ok(src.includes('ORDERS ||--o{ ORDER_ITEMS : "FK_ITEMS_ORDER"'), 'DDL: 부모 ||--o{ 자식');
  assert.ok(src.includes('CUSTOMERS ||--o{ ORDERS : "FK_ORDERS_CUSTOMER"'));
  assert.ok(src.includes('PRODUCTS |o..o{ ORDER_ITEMS : "PRODUCT_ID (추론 2건)"'), '추론: 점선');
  assert.ok(src.includes('ORDERS |o..o{ ORDER_HISTORY : "ORDER_ID (추론 1건)"'));
  const scope = erd.selectScope(ix, {});
  assert.equal(scope.relations.find((r) => r.from === ids.TB.items && r.to === ids.TB.products).source, 'INFERRED');
});

test('F-016-AC12 declared=false 테이블은 구분 클래스와 표시가 붙는다', () => {
  const ix = index();
  const src = erd.toMermaid(ix, erd.selectScope(ix, {}), { columns: 'all' }).source;
  assert.ok(src.includes('ORDER_HISTORY["ORDER_HISTORY (DDL 없음)"]:::undeclared'));
  assert.match(src, /classDef undeclared [^\n]*stroke-dasharray/);
  assert.equal(src.split(':::undeclared').length - 1, 1, '정의가 있는 테이블에는 표시가 없다');
  assert.equal(src.split('(DDL 없음)').length - 1, 1);
});

test('F-016-AC12 범례: DDL, 추론, 방향 미상, 카디널리티 모름(F-013-AC30), DDL 없음', () => {
  const keys = erd.LEGEND.map((l) => l.key);
  assert.deepEqual(keys, ['ddl', 'inferred', 'undirected', 'cardinalityUnknown', 'undeclared']);
  assert.ok(erd.LEGEND.every((l) => l.label && l.description));
  assert.equal(erd.LEGEND.find((l) => l.key === 'inferred').line, 'dashed');
  assert.equal(erd.LEGEND.find((l) => l.key === 'ddl').line, 'solid');
});

test('F-016-AC11 Mermaid가 받지 못하는 이름은 별칭과 정리한 이름으로 바꾼다', () => {
  const g = sampleGraph();
  g.nodes.push({ id: 'db:table:TB$LOG', kind: 'DB_TABLE', name: 'TB$LOG', qualifiedName: 'TB$LOG', location: null, attrs: { declared: true } });
  g.nodes.push({ id: 'db:column:TB$LOG.SEQ#', kind: 'DB_COLUMN', name: 'SEQ#', qualifiedName: 'TB$LOG.SEQ#', location: null,
    attrs: { declared: true, dataType: 'NUMBER', primaryKey: true, ordinal: 1, comment: 'a "quoted" 설명' } });
  g.nodes.push({ id: 'db:column:TB$LOG.MEMO', kind: 'DB_COLUMN', name: 'MEMO', qualifiedName: 'TB$LOG.MEMO', location: null, attrs: { declared: true, ordinal: 2 } });
  g.edges.push({ from: 'db:table:TB$LOG', to: 'db:column:TB$LOG.SEQ#', kind: 'CONTAINS', confidence: 'RESOLVED', location: null, attrs: {} });
  g.edges.push({ from: 'db:table:TB$LOG', to: 'db:column:TB$LOG.MEMO', kind: 'CONTAINS', confidence: 'RESOLVED', location: null, attrs: {} });
  g.edges.push({ from: 'db:table:TB$LOG', to: ids.TB.orders, kind: 'REFERENCES', confidence: 'RESOLVED', location: null, attrs: { columns: ['SEQ#'], refColumns: ['ORDER_ID'], source: 'DDL' } });
  const ix = graph.buildIndex(g);
  const out = erd.toMermaid(ix, erd.selectScope(ix, { tables: ['db:table:TB$LOG'], depth: 1 }), { columns: 'all' });
  const alias = [...out.aliases.entries()].find(([, v]) => v === 'db:table:TB$LOG')[0];
  assert.match(alias, /^[A-Za-z_][A-Za-z0-9_]*$/);
  assert.ok(out.source.includes(alias + '["TB$LOG"]'));
  assert.ok(out.source.includes('    NUMBER SEQ_ PK, FK "a \'quoted\' 설명"\n'));
  assert.ok(out.source.includes('    unknown MEMO\n'), '자료형을 모르면 unknown');
  // FK 컬럼(SEQ#)이 자식의 PK 전체라서 자식 쪽은 0..1이다
  assert.ok(out.source.includes('ORDERS ||--o| ' + alias + ' : "SEQ# → ORDER_ID"'), '제약 이름이 없으면 컬럼으로');
});

test('F-016-AC16 테이블 수가 상한을 넘으면 상한까지만 넣고 잘렸음을 알린다', () => {
  const ix = graph.buildIndex(bigGraph(2000));
  const scope = erd.selectScope(ix, { maxNodes: 30 });
  assert.equal(scope.tables.length, 30);
  assert.equal(scope.truncated, true);
  assert.equal(scope.total, 400);
  const kept = new Set(scope.tables.map((t) => t.id));
  assert.ok(scope.relations.every((r) => kept.has(r.from) && kept.has(r.to)));
  const def = erd.selectScope(ix, {});
  assert.equal(def.tables.length, erd.DEFAULT_MAX_NODES);
  // 선택한 테이블은 잘려도 남는다
  const picked = erd.selectScope(ix, { tables: ['db:table:T1000'], depth: 50, maxNodes: 10 });
  assert.equal(picked.tables.length, 10);
  assert.ok(picked.tables.some((t) => t.id === 'db:table:T1000'));
  assert.equal(picked.truncated, true);
});

test('F-013-AC29 (뷰어) 현재 정의에 없는 upgrade 스크립트의 외래 키(fromUpgrade=true이면서 INFERRED)는 점선으로 그리고 라벨에 (upgrade 스크립트, 현재 정의에 없음)을 적는다. 표준 산출물의 라벨과 같다', () => {
  const { slice } = require('./fixtures/slice.js');
  const s = slice();
  ['ORDERS', 'CUSTOMER', 'COUPON', 'STORE', 'REGION'].forEach((t) => {
    s.n('db:table:' + t, 'DB_TABLE', t, t, {});
    s.n('db:column:' + t + '.' + t + '_ID', 'DB_COLUMN', t + '_ID', t + '.' + t + '_ID', { dataType: 'NUMBER', ordinal: 1, primaryKey: true });
    s.e('db:table:' + t, 'CONTAINS', 'db:column:' + t + '.' + t + '_ID');
  });
  ['CUSTOMER_ID', 'COUPON_ID', 'STORE_ID', 'REGION_ID'].forEach((c, i) => {
    s.n('db:column:ORDERS.' + c, 'DB_COLUMN', c, 'ORDERS.' + c, { dataType: 'NUMBER', ordinal: i + 2 });
    s.e('db:table:ORDERS', 'CONTAINS', 'db:column:ORDERS.' + c);
  });
  s.e('db:table:ORDERS', 'REFERENCES', 'db:table:CUSTOMER', { columns: ['CUSTOMER_ID'], refColumns: ['CUSTOMER_ID'], constraint: 'FK_ORDERS_CUSTOMER', source: 'DDL' });
  // source=INFERRED로 온 것(제약 이름 있음)
  s.e('db:table:ORDERS', 'REFERENCES', 'db:table:COUPON', { columns: ['COUPON_ID'], refColumns: ['COUPON_ID'], constraint: 'FK_ORDERS_COUPON', source: 'INFERRED', fromUpgrade: true }, 'INFERRED');
  // source 없이 엣지 신뢰도만 INFERRED인 것(제약 이름 없음)
  s.e('db:table:ORDERS', 'REFERENCES', 'db:table:STORE', { columns: ['STORE_ID'], refColumns: ['STORE_ID'], fromUpgrade: true }, 'INFERRED');
  // source는 DDL인데 엣지 신뢰도가 INFERRED인 것
  s.e('db:table:ORDERS', 'REFERENCES', 'db:table:REGION', { columns: ['REGION_ID'], refColumns: ['REGION_ID'], constraint: 'FK_ORDERS_REGION', source: 'DDL', fromUpgrade: true }, 'INFERRED');
  const ix = graph.buildIndex(s.graph());
  // 화면에 그리는 ERD: 점선, 추론 n건 대신 (upgrade 스크립트, 현재 정의에 없음)
  const scope = erd.selectScope(ix, {});
  const mermaid = erd.toMermaid(ix, scope, {}).source;
  assert.ok(mermaid.includes('COUPON |o..o{ ORDERS : "FK_ORDERS_COUPON (upgrade 스크립트, 현재 정의에 없음)"'), mermaid);
  assert.ok(mermaid.includes('STORE |o..o{ ORDERS : "STORE_ID (upgrade 스크립트, 현재 정의에 없음)"'), mermaid);
  assert.ok(mermaid.includes('REGION |o..o{ ORDERS : "FK_ORDERS_REGION (upgrade 스크립트, 현재 정의에 없음)"'), mermaid);
  assert.ok(mermaid.includes('CUSTOMER ||--o{ ORDERS : "FK_ORDERS_CUSTOMER"'), mermaid);
  assert.ok(!/추론/.test(mermaid), mermaid);
  // 현재 정의의 FK가 아니므로 컬럼에 FK 표시를 하지 않는다
  assert.ok(/NUMBER CUSTOMER_ID FK/.test(mermaid) && /NUMBER COUPON_ID\n/.test(mermaid) && /NUMBER REGION_ID\n/.test(mermaid), mermaid);
  assert.deepEqual(erd.relationLines(ix, scope).map((l) => [ix.node(l.parentId).name, l.dashed]), [['CUSTOMER', false], ['COUPON', true], ['STORE', true], ['REGION', true]]);
  // 추론 관계를 빼면 함께 빠진다
  assert.deepEqual(erd.selectScope(ix, { includeInferred: false }).relations.map((r) => ix.node(r.to).name), ['CUSTOMER']);
  // 표준 산출물과 같은 글자(erd/erd.mmd): ErdLabels.relationLabel
  const model = erd.exportModel(ix, {});
  const exported = erd.exportMermaid(ix, model.entities, model.relations, {});
  assert.ok(exported.includes('    COUPON |o..o{ ORDERS : "FK_ORDERS_COUPON (upgrade 스크립트, 현재 정의에 없음)"\n'), exported);
  assert.ok(exported.includes('    STORE |o..o{ ORDERS : "STORE_ID (upgrade 스크립트, 현재 정의에 없음)"\n'), exported);
  assert.ok(exported.includes('    REGION |o..o{ ORDERS : "FK_ORDERS_REGION (upgrade 스크립트, 현재 정의에 없음)"\n'), exported);
  assert.ok(exported.includes('    CUSTOMER ||--o{ ORDERS : "FK_ORDERS_CUSTOMER"\n'), exported);
  assert.ok(exported.includes('        NUMBER COUPON_ID\n') && exported.includes('        NUMBER REGION_ID\n'), exported);
  assert.deepEqual(model.relations.map((r) => [r.parent, r.inferred, r.fromUpgrade]), [['COUPON', true, true], ['CUSTOMER', false, false], ['REGION', true, true], ['STORE', true, true]]);
  // 근거 수로 거르는 옵션은 적용하지 않는다
  assert.equal(erd.exportModel(ix, { minEvidence: 3 }).relations.length, 4);
  assert.deepEqual(erd.exportModel(ix, { includeInferred: false }).relations.map((r) => r.parent), ['CUSTOMER']);
});
