'use strict';
// 컬럼 쌍이 여럿인 조인·관계를 읽기 쉽게 보이는지 본다(F-016-R47, 사용자 요청 2026-10-12).
// 전에는 JOINS가 `A.X = B.Y AND A.Z = B.W AND …`, REFERENCES가 `X, Z, … → Y, W, …`로 한 줄에 붙어 잘리거나 위치를 세어 맞춰야 했다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { assemble } = require('./tools/assemble.js');
const { boot, sleep } = require('./tools/boot.js');
const { slice, loc } = require('./fixtures/slice.js');

const graph = core('graph');
const erd = core('erd');

/** 주문 상세(TB_ORDER_ITEM)와 주문(TB_ORDER)·상품 옵션(TB_ITEM_OPT)이 복합 키로 이어진 그래프. */
function compositeKeys() {
  const s = slice();
  const table = (name, attrs) => s.n('db:table:' + name, 'DB_TABLE', name, name, attrs);
  const f = {
    order: table('TB_ORDER', { primaryKey: ['SITE_CD', 'ORDER_NO'] }),
    item: table('TB_ORDER_ITEM', { primaryKey: ['SITE_CD', 'ORD_NO', 'ITEM_SEQ'] }),
    opt: table('TB_ITEM_OPT', { primaryKey: ['SITE_CD', 'ORD_NO', 'ITEM_SEQ', 'OPT_SEQ'] }),
    log: table('TB_LOG'),
    one: table('TB_ONE')
  };
  f.stmt = s.n('mybatis:stmt:m.selectItems', 'SQL_STATEMENT', 'selectItems', 'm.selectItems', { statementType: 'SELECT' });
  s.e(f.order, 'JOINS', f.item, { leftColumns: ['SITE_CD', 'ORDER_NO', 'LANG_CD'], rightColumns: ['SITE_CD', 'ORD_NO', 'LANG'],
    rightFixedColumns: ['USE_YN'], rightFixedValues: ['USE_YN=Y'], statement: f.stmt }, 'RESOLVED', loc('mapper/OrderMapper.xml', 40));
  s.e(f.item, 'REFERENCES', f.order, { cardinality: 'N:1', columns: ['SITE_CD', 'ORD_NO'], refColumns: ['SITE_CD', 'ORDER_NO'], directionKnown: true,
    evidence: [f.stmt], evidenceCount: 2, source: 'INFERRED' }, 'INFERRED');
  s.e(f.opt, 'REFERENCES', f.item, { cardinality: 'unknown', columns: ['SITE_CD', 'ORD_NO', 'ITEM_SEQ'], refColumns: ['SITE_CD', 'ORD_NO', 'ITEM_SEQ'],
    directionKnown: false, evidenceCount: 1, source: 'INFERRED' }, 'INFERRED');
  s.e(f.log, 'JOINS', f.one, { leftColumns: ['ID'], rightColumns: ['LOG_ID'], wrapped: true, leftWrapped: true, statement: f.stmt });
  s.e(f.log, 'JOINS', f.order, { leftColumns: ['SITE_CD', 'ORD_NO'], rightColumns: ['SITE_CD', 'ORDER_NO'], wrapped: true, rightWrapped: true, statement: f.stmt });
  f.graph = s.graph();
  return f;
}

const itemOf = (d, direction, kind, otherId) => d[direction].find((g) => g.kind === kind).items.find((i) => i.otherId === otherId);

test('F-016-AC90 상세: 컬럼 쌍이 둘 이상인 JOINS·REFERENCES는 요약에 쌍의 수를 적고 쌍을 따로(pairs) 넘긴다. 한 쌍이면 전과 같다', () => {
  const f = compositeKeys();
  const ix = graph.buildIndex(f.graph);
  const order = graph.nodeDetail(ix, f.order);
  const join = itemOf(order, 'outgoing', 'JOINS', f.item);
  assert.equal(join.summary, "컬럼 3쌍 · TB_ORDER_ITEM 고정 USE_YN='Y'");
  assert.deepEqual(join.pairs, {
    left: 'TB_ORDER', right: 'TB_ORDER_ITEM', op: '=', leftNote: '', rightNote: '',
    rows: [['SITE_CD', 'SITE_CD'], ['ORDER_NO', 'ORD_NO'], ['LANG_CD', 'LANG']]
  });
  assert.equal(join.fullSummary,
    "TB_ORDER.SITE_CD = TB_ORDER_ITEM.SITE_CD AND TB_ORDER.ORDER_NO = TB_ORDER_ITEM.ORD_NO AND TB_ORDER.LANG_CD = TB_ORDER_ITEM.LANG · TB_ORDER_ITEM 고정 USE_YN='Y'",
    '쌍을 모두 풀어 적은 요약(풍선말)');
  // 추론 관계: 방향을 알면 →, 모르면 =
  const ref = itemOf(order, 'incoming', 'REFERENCES', f.item);
  assert.equal(ref.summary, '컬럼 2쌍 · N:1 · 추론 2건');
  assert.deepEqual(ref.pairs, { left: 'TB_ORDER_ITEM', right: 'TB_ORDER', op: '→', leftNote: '', rightNote: '', rows: [['SITE_CD', 'SITE_CD'], ['ORD_NO', 'ORDER_NO']] });
  assert.equal(ref.fullSummary, 'SITE_CD, ORD_NO → SITE_CD, ORDER_NO · N:1 · 추론 2건');
  const unknown = itemOf(graph.nodeDetail(ix, f.opt), 'outgoing', 'REFERENCES', f.item);
  assert.equal(unknown.summary, '컬럼 3쌍 · 방향 미상: 키 정보 없음 · 추론 1건');
  assert.equal(unknown.pairs.op, '=');
  // 함수로 감싼 쪽은 표의 머리에 적는다
  const log = graph.nodeDetail(ix, f.log);
  const wrapped = itemOf(log, 'outgoing', 'JOINS', f.order);
  assert.equal(wrapped.summary, '컬럼 2쌍 (함수 적용: TB_ORDER 쪽)');
  assert.equal(wrapped.pairs.rightNote, '함수 적용');
  assert.equal(wrapped.pairs.leftNote, '');
  // 한 쌍이면 요약에 그대로 적고 표는 없다
  const single = itemOf(log, 'outgoing', 'JOINS', f.one);
  assert.equal(single.summary, 'TB_LOG.ID = TB_ONE.LOG_ID (함수 적용: TB_LOG 쪽)');
  assert.equal(single.pairs, null);
  assert.equal(single.fullSummary, single.summary);
});

test('F-016-AC91 화면: 컬럼 쌍이 둘 이상인 엣지 줄 아래에 양쪽 테이블 이름을 머리로 한 쌍 표가 늘 보인다', async () => {
  const f = compositeKeys();
  const window = await boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  window.CodeAtlas.app.navigate('explore', { id: f.order });
  await sleep(20);
  const items = doc.querySelectorAll('.view-explore .edge-item');
  const join = items.find((i) => i.querySelector('.edge-row').textContent.includes('컬럼 3쌍'));
  assert.ok(join, '쌍 수를 적은 JOINS 줄');
  assert.equal(join.querySelector('.edge-summary').getAttribute('title'),
    "TB_ORDER.SITE_CD = TB_ORDER_ITEM.SITE_CD AND TB_ORDER.ORDER_NO = TB_ORDER_ITEM.ORD_NO AND TB_ORDER.LANG_CD = TB_ORDER_ITEM.LANG · TB_ORDER_ITEM 고정 USE_YN='Y'");
  const table = join.querySelector('.edge-pairs');
  assert.ok(table, '쌍 표는 펼치지 않아도 보인다');
  const cells = (row) => row.querySelectorAll('span').map((c) => c.textContent);
  const rows = table.querySelectorAll('.pair');
  assert.deepEqual(cells(rows[0]), ['TB_ORDER', '', 'TB_ORDER_ITEM'], '머리');
  assert.deepEqual(rows.slice(1).map(cells), [['SITE_CD', '=', 'SITE_CD'], ['ORDER_NO', '=', 'ORD_NO'], ['LANG_CD', '=', 'LANG']]);
  const ref = items.find((i) => i.querySelector('.edge-row').textContent.includes('컬럼 2쌍 · N:1'));
  assert.deepEqual(ref.querySelectorAll('.edge-pairs .pair').slice(1).map(cells), [['SITE_CD', '→', 'SITE_CD'], ['ORD_NO', '→', 'ORDER_NO']]);
  // 한 쌍뿐인 엣지에는 표가 없다
  window.CodeAtlas.app.navigate('explore', { id: f.one });
  await sleep(20);
  const one = doc.querySelectorAll('.view-explore .edge-item');
  assert.ok(one.length > 0 && one.every((i) => !i.querySelector('.edge-pairs')));
  assert.deepEqual(window.errors, []);
});

test('F-016-AC92 ERD: 컬럼 이름이 양쪽에서 다른 복합 관계의 라벨은 쌍마다 이어 적는다(`A → X, B → Y`)', () => {
  const f = compositeKeys();
  const ix = graph.buildIndex(f.graph);
  const scope = erd.selectScope(ix, {});
  const labels = scope.relations.map((r) => erd.relationLabel(r)).sort();
  assert.deepEqual(labels, [
    'SITE_CD → SITE_CD, ORD_NO → ORDER_NO (추론 2건)',
    'SITE_CD, ORD_NO, ITEM_SEQ (추론 1건, 방향 미상)'
  ]);
  // 이름이 같은 쌍만 있거나 한 쌍이면 전과 같다
  assert.equal(erd.relationLabel({ source: 'DDL', columns: ['A', 'B'], refColumns: ['X', 'Y'], constraint: null }), 'A → X, B → Y');
  assert.equal(erd.relationLabel({ source: 'DDL', columns: ['A'], refColumns: ['X'], constraint: null }), 'A → X');
  assert.equal(erd.relationLabel({ source: 'INFERRED', columns: ['A', 'B'], refColumns: ['X', 'Y'], directionKnown: false, evidenceCount: 1, fixedColumns: [], fixedValues: [] }),
    'A = X, B = Y (추론 1건, 방향 미상)');
});
