'use strict';
// 추론 경로로만 닿은 CRUD 칸의 `?`(F-015-R52, R53 / F-016-R34). 기대값은 표준 산출물 테스트 InferredPathTest와 같은 모양의 그래프·같은 칸이다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { javaGraph, genericDao, reflection } = require('./fixtures/java-graph.js');

const graph = core('graph');
const calltree = core('calltree');
const crud = core('crud');

const cellsOf = (ix, id, rowKind) => {
  const m = crud.buildMatrix(ix, { rowKind: rowKind || 'endpoint' });
  const row = m.rows.find((r) => r.id === id);
  return row ? Object.keys(row.cells).map((t) => graph.idRemainder(t) + '=' + row.cells[t]).sort() : [];
};
const C = 'com.i.OrderController';
const M = 'com.i.OrderMapper';

/**
 * POST /order → OrderController#save
 *   ├─ OrderService#sure           ─EXECUTES→ selectSure (T_SURE, T_BOTH 읽기)
 *   ├─ ~ Codec#encode(String)      ─EXECUTES→ callProc ─CALLS→ PROC_X (T_PROC 읽고 갱신)   [ambiguous]
 *   └─ ~ Codec#encode(String,int)  ─EXECUTES→ selectGuess (T_GUESS, T_BOTH 읽기)           [ambiguous]
 */
function shop() {
  const j = javaGraph();
  const f = { j };
  ['T_SURE', 'T_GUESS', 'T_BOTH', 'T_PROC'].forEach((t) => j.table(t));
  j.type(C);
  f.handler = j.method(C, 'save');
  f.endpoint = j.endpoint('POST', '/order', f.handler);
  f.sure = j.method('com.i.OrderService', 'sure');
  f.one = j.method('com.i.Codec', 'encode', ['java.lang.String']);
  f.two = j.method('com.i.Codec', 'encode', ['java.lang.String', 'int']);
  j.call(f.handler, f.sure, 20, 'virtual');
  j.call(f.handler, f.one, 30, 'virtual', null, { ambiguous: true }, 'INFERRED');
  j.call(f.handler, f.two, 30, 'virtual', null, { ambiguous: true }, 'INFERRED');
  f.selectSure = j.statement(M, 'selectSure', 'SELECT', 'T_SURE');
  j.s.e(f.selectSure, 'READS', 'db:table:T_BOTH');
  f.selectGuess = j.statement(M, 'selectGuess', 'SELECT', 'T_GUESS');
  j.s.e(f.selectGuess, 'READS', 'db:table:T_BOTH');
  f.callProc = j.statement(M, 'callProc', 'CALL');
  j.s.e(f.sure, 'EXECUTES', f.selectSure);
  j.s.e(f.two, 'EXECUTES', f.selectGuess);
  j.s.e(f.one, 'EXECUTES', f.callProc);
  const routine = j.s.n('db:routine:PROC_X', 'DB_ROUTINE', 'PROC_X', 'PROC_X', { routineType: 'PROCEDURE' });
  const inner = j.s.n('sql:stmt:PROC_X:5', 'SQL_STATEMENT', 'PROC_X:5', 'PROC_X:5', { statementType: 'UPDATE' });
  j.s.e(routine, 'CONTAINS', inner);
  j.s.e(inner, 'READS', 'db:table:T_PROC');
  j.s.e(inner, 'WRITES', 'db:table:T_PROC', { op: 'UPDATE' });
  j.s.e(f.callProc, 'CALLS', routine);
  return f;
}

test('F-016-AC66 CRUD 엔드포인트 기준: 오버로드 후보(ambiguous)로만 닿은 칸 끝에 ?가 붙고(R?, ru?), 확정 경로가 하나라도 있으면 붙지 않는다 (F-015-AC63)', () => {
  const f = shop();
  const ix = graph.buildIndex(f.j.graph());
  assert.deepEqual(cellsOf(ix, f.endpoint), ['T_BOTH=R', 'T_GUESS=R?', 'T_PROC=ru?', 'T_SURE=R']);
  const m = crud.buildMatrix(ix, { rowKind: 'endpoint' });
  // CSV(표준 산출물과 같은 모양)에도 같은 글자가 나온다. 머리줄과 열 구성은 그대로다
  const csv = crud.toCsv(m);
  assert.ok(csv.startsWith('엔드포인트,위치,T_BOTH,T_GUESS,T_PROC,T_SURE\n'), csv);
  assert.ok(csv.includes(',R,R?,ru?,R\n'), csv);
  // 나눠서 계산해도 같다
  const job = crud.createJob(ix, { rowKind: 'endpoint' });
  while (!job.step(-1)) { /* 엔드포인트 하나씩 */ }
  assert.deepEqual(job.result().rows.map((r) => r.cells), m.rows.map((r) => r.cells));
  // 추론으로 먼저 닿은 메서드에 나중에 확정 경로로 닿으면 그 아래의 칸도 확정이다
  f.j.call(f.sure, f.two, 25, 'virtual');
  assert.deepEqual(cellsOf(graph.buildIndex(f.j.graph()), f.endpoint), ['T_BOTH=R', 'T_GUESS=R', 'T_PROC=ru?', 'T_SURE=R']);
});

test('F-016-AC66 CRUD 엔드포인트 기준: prefixMatch EXECUTES, 추론한 재정의, 후보 여럿 가운데서 맞춘 forward로만 닿은 칸은 ?이고 요청 전 처리([before])와 보통의 forward로 닿은 칸은 아니다', () => {
  const j = javaGraph();
  ['T_PREFIX', 'T_FORWARD', 'T_FORWARD_GUESS', 'T_BEFORE', 'T_IMPL'].forEach((t) => j.table(t));
  j.type(C);
  const handler = j.method(C, 'save');
  const endpoint = j.endpoint('POST', '/order', handler);
  const prefixed = j.statement(M, 'selectOrderList_D', 'SELECT', 'T_PREFIX');
  j.s.e(handler, 'EXECUTES', prefixed, { prefixMatch: true, statementPrefix: M + '.selectOrderList' }, 'INFERRED');
  const target = j.method(C, 'list');
  j.endpoint('GET', '/order/list', target);
  j.call(handler, target, 40, 'forward', null, null, 'INFERRED');
  j.s.e(target, 'EXECUTES', j.statement(M, 'selectList', 'SELECT', 'T_FORWARD'));
  const guessed = j.method(C, 'guess');
  j.call(handler, guessed, 41, 'forward', null, { ambiguous: true }, 'INFERRED');
  j.s.e(guessed, 'EXECUTES', j.statement(M, 'selectGuessed', 'SELECT', 'T_FORWARD_GUESS'));
  const before = j.method(C, 'get');
  j.call(handler, before, 15, 'modelAttribute', null, null, 'INFERRED');
  j.s.e(before, 'EXECUTES', j.statement(M, 'get', 'SELECT', 'T_BEFORE'));
  j.type('com.i.Policy', { kind: 'interface' });
  const policy = j.method('com.i.Policy', 'apply', [], { modifiers: ['public', 'abstract'] });
  j.type('com.i.DefaultPolicy');
  const impl = j.method('com.i.DefaultPolicy', 'apply');
  j.s.e(impl, 'OVERRIDES', policy, {}, 'INFERRED');
  j.call(handler, policy, 50, 'interface');
  j.s.e(impl, 'EXECUTES', j.statement(M, 'apply', 'UPDATE', 'T_IMPL'));
  const ix = graph.buildIndex(j.graph());
  assert.deepEqual(cellsOf(ix, endpoint), ['T_BEFORE=R', 'T_FORWARD=R', 'T_FORWARD_GUESS=R?', 'T_IMPL=U?', 'T_PREFIX=R?']);
});

test('F-016-AC66 CRUD 엔드포인트 기준: 수신자 문맥이 대상 하나를 고른 상속 구문·리플렉션 호출은 확정이고, 고르지 못했거나([?]) 문맥이 없거나 여럿을 고르면 ?다', () => {
  const f = genericDao();
  const ix = graph.buildIndex(f.j.graph());
  assert.deepEqual(cellsOf(ix, f.getDict), ['SYS_DICT=R']);
  assert.deepEqual(cellsOf(ix, f.getDictDirect), ['SYS_DICT=R']);
  assert.deepEqual(cellsOf(ix, f.postUser), ['SYS_USER=C']);
  assert.deepEqual(cellsOf(ix, f.postMix), ['SYS_DICT=C?', 'SYS_LOG=C?']);
  assert.deepEqual(cellsOf(ix, f.getRaw), ['SYS_DICT=R?', 'SYS_LOG=R?', 'SYS_USER=R?']);
  assert.deepEqual(cellsOf(ix, f.getOdd), ['SYS_DICT=R?', 'SYS_LOG=R?', 'SYS_USER=R?']);
  // 줄(link)에 추론의 까닭이 실린다: 고른 것은 null, 고르지 못한 것은 문구
  const direct = calltree.childLinks(ix, f.daoFindList, 'callees', {}, { kind: 'CALLS', context: { type: 'java:type:com.g.DictDao', enclosing: false, origin: null, exact: false } }, f.direct);
  assert.deepEqual(direct.map((l) => [graph.idRemainder(l.nodeId), l.inference]), [['com.g.DictDao.findList', null]]);
  const raw = calltree.childLinks(ix, f.daoFindList, 'callees', {}, null, null);
  assert.equal(raw.length, 3);
  assert.ok(raw.every((l) => /수신자 타입/.test(l.inference)), JSON.stringify(raw.map((l) => l.inference)));

  // 리플렉션 팬아웃: 수신자 타입이 viaTypes와 맞은 것은 확정, 문맥 없이 시작하면 [?]라 추론
  const r = reflection();
  const list = r.j.endpoint('GET', '/users', r.controller);
  const tool = r.j.endpoint('GET', '/tool', r.direct);
  const rx = graph.buildIndex(r.j.graph());
  assert.deepEqual(cellsOf(rx, list), ['T_USER=R']);
  // 대상 타입이 정해진 보통의 리플렉션 호출(viaTypes 없음)은 추론한 호출 그대로다
  assert.deepEqual(cellsOf(rx, tool), ['T_USER=R?']);
});

test('F-016-AC66 CRUD 엔드포인트 기준: 일어나는지 알 수 없는 트리거([trigger?])를 거쳐서만 닿은 칸은 ?다', () => {
  const j = javaGraph();
  ['TB_ORDER', 'TB_EVENT', 'TB_AUDIT'].forEach((t) => j.table(t));
  j.type(C);
  const handler = j.method(C, 'save');
  const endpoint = j.endpoint('POST', '/order/save', handler);
  j.s.e(handler, 'EXECUTES', j.statement(M, 'insertOrder', 'INSERT', 'TB_ORDER'));
  const trigger = (name, event, table, line) => {
    const id = j.s.n('db:routine:' + name, 'DB_ROUTINE', name, name, Object.assign({ routineType: 'TRIGGER' }, event ? { triggerEvent: event } : {}));
    j.s.e(id, 'TRIGGERS_ON', 'db:table:TB_ORDER');
    const inner = j.s.n('sql:stmt:' + name + ':' + line, 'SQL_STATEMENT', name + ':' + line, name + ':' + line, { statementType: 'INSERT' });
    j.s.e(id, 'CONTAINS', inner);
    j.s.e(inner, 'WRITES', 'db:table:' + table, { op: 'INSERT' });
  };
  trigger('TRG_ORDER_NEW', 'AFTER INSERT', 'TB_EVENT', 4);
  trigger('TRG_ORDER_AUDIT', null, 'TB_AUDIT', 3);
  const ix = graph.buildIndex(j.graph());
  assert.deepEqual(cellsOf(ix, endpoint), ['TB_AUDIT=c?', 'TB_EVENT=c', 'TB_ORDER=C']);
});

test('F-016-AC66 CRUD 메서드 기준: EXECUTES 자체가 추론일 때만 ?가 붙는다(상속 매퍼 메서드의 행). 구문·루틴 기준에는 붙지 않는다 (F-015-AC64)', () => {
  const f = genericDao();
  const plain = f.j.method(f.P + 'DictDao', 'findOne');
  const findOne = f.j.statement(f.P + 'DictDao', 'findOne', 'SELECT', 'SYS_DICT');
  f.j.s.e(plain, 'EXECUTES', findOne);
  let ix = graph.buildIndex(f.j.graph());
  assert.deepEqual(cellsOf(ix, f.daoFindList, 'method'), ['SYS_DICT=R?', 'SYS_LOG=R?', 'SYS_USER=R?']);
  assert.deepEqual(cellsOf(ix, f.daoInsert, 'method'), ['SYS_DICT=C?', 'SYS_LOG=C?', 'SYS_USER=C?']);
  assert.deepEqual(cellsOf(ix, plain, 'method'), ['SYS_DICT=R']);
  // 같은 메서드가 확정 EXECUTES로도 그 테이블에 닿으면 그 칸은 확정이다
  f.j.s.e(f.daoFindList, 'EXECUTES', findOne);
  ix = graph.buildIndex(f.j.graph());
  assert.deepEqual(cellsOf(ix, f.daoFindList, 'method'), ['SYS_DICT=R', 'SYS_LOG=R?', 'SYS_USER=R?']);
  // 뷰어만 하는 "호출 경유 포함"으로 올린 행에는 표시를 물려주지 않는다(직접 실행하는 메서드의 칸만 표준 산출물과 견준다)
  const s = shop();
  const sx = graph.buildIndex(s.j.graph());
  ['statement', 'routine'].forEach((rowKind) => {
    const cells = crud.buildMatrix(sx, { rowKind }).rows.flatMap((r) => Object.values(r.cells));
    assert.ok(cells.length > 0 && cells.every((c) => c.indexOf('?') < 0), rowKind + ': ' + cells.join(' '));
  });
});

test('F-016-AC66 CRUD 범례: ?(추론 경로로만 닿은 칸)를 설명한다', () => {
  const item = crud.LEGEND.find((l) => l.key === '?');
  assert.ok(item, '범례에 ? 항목');
  assert.match(item.description, /추론/);
  assert.match(item.description, /확정 경로/);
  assert.equal(crud.INFERRED_MARK, '?');
});
