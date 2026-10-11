'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { sampleGraph } = require('./fixtures/sample-graph.js');

const graph = core('graph');
const search = core('search');
const diagram = core('diagram');

test('F-016-AC10 F-016-AC11 다이어그램 도형 ID에서 별칭을 찾는다 (Mermaid 11은 도형 ID 앞에 다이어그램 ID를 붙인다)', () => {
  assert.equal(diagram.aliasOfShapeId('ca-diagram-3-classId-c0-3'), 'c0');
  assert.equal(diagram.aliasOfShapeId('ca-diagram-1-entity-ORDER_HISTORY-3'), 'ORDER_HISTORY');
  assert.equal(diagram.aliasOfShapeId('classId-c12-0'), 'c12');
  assert.equal(diagram.aliasOfShapeId('entity-TB_LOG_2-10'), 'TB_LOG_2');
  assert.equal(diagram.aliasOfShapeId('ca-diagram-1-flowchart-x-1'), null);
  assert.equal(diagram.aliasOfShapeId(''), null);
  assert.equal(diagram.aliasOfShapeId(null), null);
  const aliases = new Map([['c0', 'java:type:com.a.B'], ['ORDERS', 'db:table:ORDERS']]);
  assert.equal(diagram.nodeIdOfShape('ca-diagram-9-classId-c0-0', aliases), 'java:type:com.a.B');
  assert.equal(diagram.nodeIdOfShape('ca-diagram-9-entity-ORDERS-4', aliases), 'db:table:ORDERS');
  assert.equal(diagram.nodeIdOfShape('ca-diagram-9-entity-NOPE-4', aliases), null);
});

test('F-016-AC4 검색어가 없으면 종류 순(계약의 순서), 그 안에서 이름 순으로 나온다', () => {
  const index = graph.buildIndex(sampleGraph());
  const all = search.search(index, {});
  const ranks = all.map((n) => graph.kindRank(n.kind));
  assert.deepEqual(ranks, ranks.slice().sort((a, b) => a - b));
  assert.equal(all[0].kind, 'FILE');
  assert.equal(all[all.length - 1].kind, 'UNRESOLVED');
  const types = all.filter((n) => n.kind === 'JAVA_TYPE').map((n) => n.name);
  assert.deepEqual(types, types.slice().sort());
});
