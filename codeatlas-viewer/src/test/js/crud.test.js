'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');

const graph = core('graph');
const crud = core('crud');

function index() { return graph.buildIndex(sampleGraph()); }
const row = (m, id) => m.rows.find((r) => r.id === id);

test('F-016-AC13 구문 기준: INSERT는 C, 조회는 R, UPDATE는 U, DELETE는 D, MERGE는 C와 U', () => {
  const m = crud.buildMatrix(index(), { rowKind: 'statement' });
  assert.deepEqual(row(m, ids.S.insert).cells, { [ids.TB.orders]: 'C' });
  assert.deepEqual(row(m, ids.S.select).cells, { [ids.TB.orders]: 'R', [ids.TB.customers]: 'R' });
  assert.deepEqual(row(m, ids.S.insertItem).cells, { [ids.TB.items]: 'C', [ids.TB.products]: 'R' });
  assert.deepEqual(row(m, ids.S.del).cells, { [ids.TB.items]: 'D' });
  assert.deepEqual(row(m, ids.RS1).cells, { [ids.TB.orders]: 'U' });
  assert.deepEqual(row(m, ids.RS2).cells, { [ids.TB.history]: 'C', [ids.TB.orders]: 'R' });
  assert.deepEqual(row(m, ids.S.settle).cells, { [ids.TB.settlements]: 'CU', [ids.TB.orders]: 'R' });
  // 루틴만 부르는 구문(CALL)의 행에는 루틴 안의 구문이 접근하는 테이블이 소문자로 나온다(F-016-AC28)
  assert.deepEqual(row(m, ids.S.close).cells, { [ids.TB.orders]: 'ru', [ids.TB.history]: 'c' });
  // 대상 테이블을 알 수 없는 동적 구문은 (동적) 열에 나온다(F-016-AC29)
  assert.deepEqual(row(m, ids.S.dynamic).cells, { [crud.DYNAMIC]: 'R' });
  // 열은 접근이 있는 테이블만, 이름 순. 행은 노드 ID 순(표준 산출물과 같다)
  assert.deepEqual(m.tables.map((t) => t.name), ['CUSTOMERS', 'ORDERS', 'ORDER_HISTORY', 'ORDER_ITEMS', 'PRODUCTS', 'SETTLEMENTS']);
  assert.deepEqual(m.rows.map((r) => r.id), m.rows.map((r) => r.id).slice().sort());
});

test('F-016-AC13 한 구문이 같은 테이블을 읽고 쓰면 글자를 C, R, U, D 순으로 합친다', () => {
  const g = sampleGraph();
  g.edges.push({ from: ids.S.insert, to: ids.TB.orders, kind: 'READS', confidence: 'RESOLVED', location: null, attrs: {} });
  g.edges.push({ from: ids.S.insert, to: ids.TB.orders, kind: 'WRITES', confidence: 'RESOLVED', location: null, attrs: { op: 'DELETE' } });
  const m = crud.buildMatrix(graph.buildIndex(g), { rowKind: 'statement' });
  assert.equal(row(m, ids.S.insert).cells[ids.TB.orders], 'CRD');
});

test('F-016-AC13 op가 없는 WRITES는 구문 종류로 정하고, 그래도 모르면 W로 둔다', () => {
  const g = sampleGraph();
  const del = g.edges.find((e) => e.from === ids.S.del && e.kind === 'WRITES');
  delete del.attrs.op;
  g.nodes.push({ id: 'sql:stmt:x.sql:1', kind: 'SQL_STATEMENT', name: 'x', qualifiedName: 'x.sql:1', location: null, attrs: { declared: true, statementType: 'OTHER' } });
  g.edges.push({ from: 'sql:stmt:x.sql:1', to: ids.TB.coupons, kind: 'WRITES', confidence: 'RESOLVED', location: null, attrs: {} });
  const m = crud.buildMatrix(graph.buildIndex(g), { rowKind: 'statement' });
  assert.equal(row(m, ids.S.del).cells[ids.TB.items], 'D');
  assert.equal(row(m, 'sql:stmt:x.sql:1').cells[ids.TB.coupons], 'W');
});

test('F-016-AC13 메서드 기준: EXECUTES로 실행하는 구문과 그 구문이 호출하는 루틴 안의 구문까지 합친다(루틴을 거친 접근은 소문자)', () => {
  const m = crud.buildMatrix(index(), { rowKind: 'method' });
  assert.deepEqual(row(m, ids.M.mapInsert).cells, { [ids.TB.orders]: 'C' });
  assert.deepEqual(row(m, ids.M.mapClose).cells, { [ids.TB.orders]: 'ru', [ids.TB.history]: 'c' });
  assert.deepEqual(row(m, ids.M.mapDelete).cells, { [ids.TB.items]: 'D' });
  // 직접 실행하지 않는 메서드는 행이 없다
  assert.equal(row(m, ids.M.implPlace), undefined);
  assert.equal(row(m, ids.M.ctlCreate), undefined);
  assert.ok(m.rows.every((r) => r.node.kind === 'JAVA_METHOD'));
});

test('F-016-AC13 메서드 기준, 호출 경유 포함: 호출과 인터페이스 구현을 거슬러 올라간 메서드까지', () => {
  const m = crud.buildMatrix(index(), { rowKind: 'method', transitive: true });
  assert.deepEqual(row(m, ids.M.implPlace).cells, { [ids.TB.orders]: 'C', [ids.TB.items]: 'C', [ids.TB.products]: 'R' });
  assert.deepEqual(row(m, ids.M.svcPlace).cells, row(m, ids.M.implPlace).cells, '인터페이스 메서드는 구현체와 같다');
  assert.deepEqual(row(m, ids.M.ctlCreate).cells, row(m, ids.M.implPlace).cells);
  assert.deepEqual(row(m, ids.M.ctlClose).cells, { [ids.TB.orders]: 'ru', [ids.TB.history]: 'c', [ids.TB.items]: 'D' });
  assert.deepEqual(row(m, ids.M.implRetry).cells, row(m, ids.M.implPlace).cells, '순환 호출에서도 끝난다');
  assert.deepEqual(row(m, ids.M.taskletExec).cells, { [ids.TB.orders]: 'R', [ids.TB.customers]: 'R' });
});

test('F-016-AC13 거르기: 행 이름과 테이블 이름으로 좁히고 남은 행에 접근이 있는 테이블만 남긴다', () => {
  const m = crud.buildMatrix(index(), { rowKind: 'statement' });
  const f = crud.filterMatrix(m, { rowText: 'insert' });
  assert.deepEqual(f.rows.map((r) => r.id).sort(), [ids.S.insert, ids.S.insertItem].sort());
  assert.deepEqual(f.tables.map((t) => t.name), ['ORDERS', 'ORDER_ITEMS', 'PRODUCTS']);
  const t = crud.filterMatrix(m, { tableText: 'history' });
  assert.deepEqual(t.tables.map((x) => x.name), ['ORDER_HISTORY']);
  assert.deepEqual(t.rows.map((r) => r.id), [ids.S.close, ids.RS2], 'CALL 구문(루틴을 거쳐 c)과 루틴 안의 INSERT 구문');
  assert.equal(crud.filterMatrix(m, {}).rows.length, m.rows.length);
});

test('F-016-AC13 CSV: 머리행에 기준·위치·테이블, 행마다 CRUD 글자 (거른 결과도 표준 산출물과 같은 모양이다)', () => {
  const m = crud.filterMatrix(crud.buildMatrix(index(), { rowKind: 'statement' }), { rowText: 'insertOrder' });
  const csv = crud.toCsv(m);
  const lines = csv.trim().split('\n');
  assert.equal(lines[0], '구문,위치,ORDERS');
  assert.equal(lines[1], 'com.shop.mapper.OrderMapper.insertOrder,src/main/resources/mapper/OrderMapper.xml:30,C');
});
