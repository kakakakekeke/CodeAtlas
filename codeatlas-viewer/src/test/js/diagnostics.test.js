'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');

const graph = core('graph');
const diag = core('diagnostics');

function index() { return graph.buildIndex(sampleGraph()); }

test('F-016-AC14 진단과 미해결 참조가 따로, 한 번씩만 나온다', () => {
  const r = diag.collect(index());
  assert.equal(r.diagnostics.length, 5);
  assert.equal(r.unresolved.length, 3, '미해결 엣지 3개. 미해결 노드는 따로 행이 되지 않는다');
  const parse = r.diagnostics.find((x) => x.code === 'PARSE_FAILED');
  assert.equal(parse.severity, 'ERROR');
  assert.equal(parse.location, 'ddl/broken.sql:12');
  const call = r.unresolved.find((x) => x.targetId === 'unresolved:java-call:paymentGateway.charge');
  assert.equal(call.category, 'java-call');
  assert.equal(call.target, 'paymentGateway.charge');
  assert.equal(call.edgeKind, 'CALLS');
  assert.equal(call.fromId, ids.M.implPlace);
  assert.equal(call.nodeId, ids.M.implPlace, '누르면 참조한 메서드로 간다');
  assert.ok(call.fromLabel.includes('placeOrder'));
  assert.equal(call.location, 'src/main/java/com/shop/service/OrderServiceImpl.java:36', '참조 지점은 엣지의 위치다');
  // 마무리 단계가 만든 노드(ID가 엣지가 가리키던 그대로): 분류는 접두사에서 유도한다(java:method → java-call)
  const finalized = r.unresolved.find((x) => x.targetId === 'java:method:com.shop.legacy.LegacyDao#find()');
  assert.equal(finalized.category, 'java-call');
  assert.equal(finalized.target, 'com.shop.legacy.LegacyDao#find()');
  const dynamic = r.unresolved.find((x) => x.category === 'sql-dynamic');
  assert.equal(dynamic.edgeKind, 'READS');
  assert.equal(dynamic.fromId, ids.S.dynamic);
});

test('F-016-AC14 코드별·분류별 개수와 심각도 순서', () => {
  const r = diag.collect(index());
  const count = (c) => r.codes.find((x) => x.code === c).count;
  assert.equal(count('UNRESOLVED_REFERENCE'), 2);
  assert.equal(count('PARSE_FAILED'), 1);
  assert.equal(r.codes.reduce((s, c) => s + c.count, 0), r.diagnostics.length);
  assert.deepEqual(r.categories.map((c) => c.category + ' ' + c.count), ['java-call 2', 'sql-dynamic 1']);
  assert.equal(r.categories.reduce((s, c) => s + c.count, 0), r.unresolved.length);
  // ERROR가 먼저, 그다음 WARN, INFO
  const order = r.diagnostics.map((x) => x.severity);
  assert.equal(order[0], 'ERROR');
  assert.ok(order.lastIndexOf('WARN') < order.indexOf('INFO') || order.indexOf('INFO') === -1);
});

test('F-016-AC14 코드, 심각도, 분류, 글로 거른다', () => {
  const r = diag.collect(index());
  const onlyRef = diag.filter(r.diagnostics, { codes: ['UNRESOLVED_REFERENCE'] });
  assert.equal(onlyRef.length, 2);
  assert.ok(onlyRef.every((x) => x.code === 'UNRESOLVED_REFERENCE'));
  const two = diag.filter(r.diagnostics, { codes: ['PARSE_FAILED', 'DYNAMIC_SQL'] });
  assert.equal(two.length, 2);
  assert.equal(diag.filter(r.diagnostics, { severities: ['ERROR'] }).length, 1);
  const text = diag.filter(r.diagnostics, { text: 'legacydao' });
  assert.equal(text.length, 1);
  assert.equal(diag.filter(r.unresolved, { text: 'legacydao' }).length, 1);
  assert.equal(diag.filter(r.unresolved, { categories: ['java-call'] }).length, 2);
  assert.equal(diag.filter(r.diagnostics, {}).length, r.diagnostics.length);
  assert.equal(diag.filter(r.diagnostics, { codes: [] }).length, r.diagnostics.length, '빈 코드 목록은 거르지 않는다');
});

test('F-016-AC14 미해결 참조를 알린 진단은 같은 지점의 미해결 참조와 이어진다', () => {
  const r = diag.collect(index());
  const refs = r.diagnostics.filter((x) => x.code === 'UNRESOLVED_REFERENCE');
  assert.ok(refs.every((x) => x.unresolved && x.nodeId === x.unresolved.fromId));
  assert.deepEqual(refs.map((x) => x.unresolved.category).sort(), ['java-call', 'java-call']);
  assert.equal(r.diagnostics.find((x) => x.code === 'PARSE_FAILED').unresolved, null);
});
