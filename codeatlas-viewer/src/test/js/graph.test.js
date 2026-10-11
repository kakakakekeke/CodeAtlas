'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core, bigGraph } = require('./helpers.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');

const graph = core('graph');
const search = core('search');
const vlist = core('vlist');

test('색인: 종류별 개수와 들어오고 나가는 엣지를 만든다', () => {
  const index = graph.buildIndex(sampleGraph());
  assert.equal(index.node(ids.TB.orders).name, 'ORDERS');
  assert.equal(index.node('no:such'), undefined);
  assert.ok(index.out(ids.M.implPlace).length >= 6);
  assert.ok(index.in(ids.TB.orders).some((e) => e.kind === 'WRITES'));
  assert.deepEqual(index.out('no:such'), []);
  const tables = index.kindCounts.find((k) => k.kind === 'DB_TABLE');
  assert.equal(tables.count, 7);
  assert.equal(index.nodesOfKind('DB_TABLE').length, 7);
});

test('색인: 없는 노드를 가리키는 엣지가 있어도 멈추지 않는다', () => {
  const g = sampleGraph();
  g.edges.push({ from: ids.M.implPlace, to: 'java:method:gone#x()', kind: 'CALLS', confidence: 'UNRESOLVED', location: null, attrs: {} });
  const index = graph.buildIndex(g);
  assert.equal(index.node('java:method:gone#x()'), undefined);
  assert.equal(index.label('java:method:gone#x()'), 'java:method:gone#x()');
  assert.ok(index.in('java:method:gone#x()').length === 1);
});

test('색인: nodes, edges, diagnostics가 빠진 입력도 받는다', () => {
  const index = graph.buildIndex({ schemaVersion: 1 });
  assert.equal(index.nodes.length, 0);
  assert.equal(index.edges.length, 0);
  assert.deepEqual(index.diagnostics, []);
});

test('F-016-AC4 검색: 종류 필터와 이름 검색, 정확히 맞는 이름이 먼저 온다', () => {
  const index = graph.buildIndex(sampleGraph());
  const result = search.search(index, { text: 'order', kinds: ['JAVA_METHOD'] });
  assert.ok(result.length > 0);
  for (const n of result) {
    assert.equal(n.kind, 'JAVA_METHOD');
    assert.ok((n.name + ' ' + n.qualifiedName).toLowerCase().includes('order'));
  }
  // 종류를 주지 않으면 모든 종류에서 찾는다
  const all = search.search(index, { text: 'orders' });
  assert.equal(all[0].name.toLowerCase(), 'orders');
  assert.ok(all.some((n) => n.kind === 'HTTP_ENDPOINT'));
  // 앞부분이 맞는 이름이 중간이 맞는 이름보다 먼저
  const prefix = search.search(index, { text: 'select', kinds: ['SQL_STATEMENT'] });
  assert.ok(prefix.length >= 3);
  assert.ok(prefix.every((n) => n.name.toLowerCase().startsWith('select')));
});

test('F-016-AC4 검색: 여러 낱말은 모두 맞아야 하고 빈 검색어는 필터만 적용한다', () => {
  const index = graph.buildIndex(sampleGraph());
  const both = search.search(index, { text: 'serviceimpl place' });
  assert.ok(both.length >= 1);
  assert.ok(both.every((n) => n.qualifiedName.toLowerCase().includes('serviceimpl') && n.qualifiedName.toLowerCase().includes('place')));
  assert.equal(search.search(index, { text: 'serviceimpl nosuchword' }).length, 0);
  const tables = search.search(index, { text: '  ', kinds: ['DB_TABLE'] });
  assert.equal(tables.length, 7);
  assert.deepEqual(tables.map((n) => n.name), tables.map((n) => n.name).slice().sort());
  assert.equal(search.search(index, {}).length, index.nodes.length);
});

test('F-016-AC5 노드 상세: 속성, 위치, 종류별 엣지와 신뢰도', () => {
  const index = graph.buildIndex(sampleGraph());
  const d = graph.nodeDetail(index, ids.M.implPlace);
  assert.equal(d.node.id, ids.M.implPlace);
  assert.equal(d.location, 'src/main/java/com/shop/service/OrderServiceImpl.java:30-38');
  assert.ok(d.attrs.some((a) => a.key === 'returnType' && a.value === 'com.shop.domain.Order'));
  assert.ok(d.attrs.some((a) => a.key === 'modifiers' && a.value === 'public'));
  const calls = d.outgoing.find((g) => g.kind === 'CALLS');
  assert.ok(calls.items.length >= 6);
  const unresolved = calls.items.find((i) => i.otherId === 'unresolved:java-call:paymentGateway.charge');
  assert.equal(unresolved.confidence, 'UNRESOLVED');
  assert.equal(unresolved.other.kind, 'UNRESOLVED');
  assert.equal(unresolved.location, 'src/main/java/com/shop/service/OrderServiceImpl.java:36');
  assert.ok(calls.items.some((i) => i.confidence === 'RESOLVED'));
  const incomingKinds = d.incoming.map((g) => g.kind);
  assert.ok(incomingKinds.includes('CALLS'));
  assert.ok(incomingKinds.includes('CONTAINS'));
  assert.equal(d.incoming.find((g) => g.kind === 'CONTAINS').items[0].otherId, ids.T.serviceImpl);
  assert.equal(graph.nodeDetail(index, 'no:such'), null);
});

test('F-016-AC5 노드 상세: declared=false와 위치 없는 노드', () => {
  const index = graph.buildIndex(sampleGraph());
  const d = graph.nodeDetail(index, ids.TB.history);
  assert.equal(d.location, null);
  assert.equal(d.declared, false);
  assert.equal(graph.formatLocation({ file: 'a/b.sql', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 }), 'a/b.sql');
  assert.equal(graph.formatLocation(null), null);
});

test('종류 표시 정보: 계약의 모든 노드 종류에 이름과 묶음이 있다', () => {
  const kinds = ['FILE', 'JAVA_PACKAGE', 'JAVA_TYPE', 'JAVA_METHOD', 'JAVA_FIELD', 'SPRING_BEAN', 'HTTP_ENDPOINT', 'HTTP_REQUEST',
    'BATCH_JOB', 'BATCH_STEP', 'MYBATIS_MAPPER', 'SQL_STATEMENT', 'SQL_FRAGMENT', 'DB_TABLE', 'DB_VIEW', 'DB_COLUMN', 'DB_ROUTINE',
    'DB_PACKAGE', 'DB_SEQUENCE', 'DB_SYNONYM', 'JS_MODULE', 'JS_FUNCTION', 'JSP_PAGE', 'UNRESOLVED'];
  for (const k of kinds) {
    const meta = graph.kindMeta(k);
    assert.ok(meta.label && meta.group, k);
  }
  assert.equal(graph.kindMeta('SOMETHING_NEW').label, 'SOMETHING_NEW');
});

test('F-016-AC15 가상 스크롤: 보이는 행과 여유분만 그릴 범위에 든다', () => {
  const r = vlist.visibleRange({ scrollTop: 28000, viewportHeight: 600, rowHeight: 28, count: 50000, overscan: 5 });
  assert.equal(r.start, 995);
  assert.equal(r.end, 1027);
  assert.equal(r.offsetY, 995 * 28);
  assert.equal(r.totalHeight, 50000 * 28);
  const top = vlist.visibleRange({ scrollTop: 0, viewportHeight: 600, rowHeight: 28, count: 50000, overscan: 5 });
  assert.equal(top.start, 0);
  assert.ok(top.end <= 30);
  const bottom = vlist.visibleRange({ scrollTop: 50000 * 28, viewportHeight: 600, rowHeight: 28, count: 50000, overscan: 5 });
  assert.equal(bottom.end, 50000);
  assert.ok(bottom.start < bottom.end && bottom.start >= 50000 - 40);
  const empty = vlist.visibleRange({ scrollTop: 100, viewportHeight: 600, rowHeight: 28, count: 0, overscan: 5 });
  assert.deepEqual([empty.start, empty.end, empty.totalHeight], [0, 0, 0]);
  const few = vlist.visibleRange({ scrollTop: 0, viewportHeight: 600, rowHeight: 28, count: 3, overscan: 5 });
  assert.deepEqual([few.start, few.end], [0, 3]);
});

test('F-016-AC15 큰 그래프: 5만 노드의 색인과 검색이 끝난다', () => {
  const started = Date.now();
  const index = graph.buildIndex(bigGraph(50000));
  assert.equal(index.nodes.length, 50000);
  const found = search.search(index, { text: 'type4999' });
  assert.ok(found.length >= 1);
  const methods = search.search(index, { text: 'm', kinds: ['JAVA_METHOD'] });
  assert.equal(methods.length, 30000);
  assert.ok(Date.now() - started < 5000, '색인과 검색이 5초 안에 끝나야 한다');
});
