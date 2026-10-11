'use strict';
// CRUD 매트릭스를 표준 산출물(codeatlas-export의 CrudMatrix, F-015-R20·R21·R25·R26)과 같은 규칙으로 만드는지 본다.
// 그래프 조각은 실제 graph.json의 모양(CALL 구문, 루틴이 부르는 루틴, 트리거, 동적 구문)을 옮긴 것이다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { slice, loc } = require('./fixtures/slice.js');

const graph = core('graph');
const crud = core('crud');

function shop() {
  const s = slice();
  const table = (name) => s.n('db:table:' + name, 'DB_TABLE', name);
  const f = { order: table('TB_ORDER'), hist: table('TB_ORDER_HIST'), code: table('TB_CODE'), delivery: table('TB_DELIVERY'), stock: table('TB_STOCK') };
  const routine = (name, type, attrs) => s.n('db:routine:' + name, 'DB_ROUTINE', name.substring(name.lastIndexOf('.') + 1), name,
    Object.assign({ routineType: type || 'PROCEDURE' }, attrs || {}), loc('db/pkg_order.pkb', 10));
  const routineStmt = (owner, line, type) => {
    const name = owner.substring('db:routine:'.length) + ':' + line;
    const id = s.n('sql:stmt:' + name, 'SQL_STATEMENT', name, name, { statementType: type }, loc('db/pkg_order.pkb', line));
    s.e(owner, 'CONTAINS', id);
    return id;
  };
  const mapperStmt = (name, type, line) => s.n('mybatis:stmt:com.shop.mapper.OrderMapper.' + name, 'SQL_STATEMENT', name, 'com.shop.mapper.OrderMapper.' + name,
    { statementType: type, namespace: 'com.shop.mapper.OrderMapper', statementId: name }, loc('mapper/OrderMapper.xml', line));
  const method = (owner, name, params) => {
    const id = s.n('java:method:' + owner + '#' + name + '(' + params.join(',') + ')', 'JAVA_METHOD', name, owner + '#' + name + '()', {}, loc('src/' + owner + '.java', 10));
    return id;
  };

  // CLOSE_DAILY(TB_ORDER·TB_DELIVERY를 읽음) → CLOSE_ORDER(TB_ORDER를 갱신, 구문 안에서 FN_CODE 호출) → WRITE_HIST(TB_ORDER_HIST에 입력)
  f.closeDaily = routine('PKG_ORDER.CLOSE_DAILY');
  f.closeOrder = routine('PKG_ORDER.CLOSE_ORDER');
  f.writeHist = routine('PKG_ORDER.WRITE_HIST');
  f.fnCode = routine('FN_CODE', 'FUNCTION');
  f.dailySelect = routineStmt(f.closeDaily, 90, 'SELECT');
  s.e(f.dailySelect, 'READS', f.order);
  s.e(f.dailySelect, 'READS', f.delivery);
  f.orderUpdate = routineStmt(f.closeOrder, 60, 'UPDATE');
  s.e(f.orderUpdate, 'WRITES', f.order, { op: 'UPDATE' });
  s.e(f.orderUpdate, 'CALLS', f.fnCode);
  f.histInsert = routineStmt(f.writeHist, 15, 'INSERT');
  s.e(f.histInsert, 'WRITES', f.hist, { op: 'INSERT' });
  f.codeSelect = routineStmt(f.fnCode, 9, 'SELECT');
  s.e(f.codeSelect, 'READS', f.code);
  s.e(f.closeDaily, 'CALLS', f.closeOrder);
  s.e(f.closeOrder, 'CALLS', f.writeHist);
  s.e(f.closeOrder, 'CALLS', f.closeDaily); // 재귀
  s.e(f.writeHist, 'CALLS', f.writeHist);   // 자기 호출

  // 매퍼: CALL 구문, 직접 읽는 구문
  f.callClose = mapperStmt('callCloseOrder', 'CALL', 197);
  s.e(f.callClose, 'CALLS', f.closeOrder);
  f.selectOrder = mapperStmt('selectOrder', 'SELECT', 95);
  s.e(f.selectOrder, 'READS', f.order);
  f.mCall = method('com.shop.mapper.OrderMapper', 'callCloseOrder', ['java.util.Map']);
  f.mBoth = method('com.shop.dao.OrderDao', 'closeAndRead', ['java.lang.Long', 'java.util.List<com.shop.vo.Order$Item>']);
  f.mRoutine = method('com.shop.dao.OrderDao', 'callDirect', []);
  s.e(f.mCall, 'EXECUTES', f.callClose);
  s.e(f.mBoth, 'EXECUTES', f.callClose);
  s.e(f.mBoth, 'EXECUTES', f.selectOrder);
  s.e(f.mRoutine, 'EXECUTES', f.writeHist); // 메서드가 루틴을 바로 실행한다

  // 엔드포인트 → 컨트롤러 → 매퍼 메서드
  f.ctl = method('com.shop.web.OrderController', 'close', ['long']);
  f.ep = s.n('http:endpoint:POST /order/close.do', 'HTTP_ENDPOINT', 'POST /order/close.do', 'POST /order/close.do', {}, loc('src/OrderController.java', 76));
  s.e(f.ep, 'ROUTES_TO', f.ctl);
  s.e(f.ctl, 'CALLS', f.mBoth, { callType: 'virtual' });

  // 트리거: TB_ORDER의 ORDER_STATUS를 갱신할 때만 TB_STOCK을 읽는다. 엔드포인트만 트리거를 따라간다.
  f.trg = routine('TRG_ORDER', 'TRIGGER', { triggerEvent: 'AFTER UPDATE' });
  s.e(f.trg, 'TRIGGERS_ON', f.order);
  f.trgSelect = routineStmt(f.trg, 7, 'SELECT');
  s.e(f.trgSelect, 'READS', f.stock);
  f.graph = s.graph();
  return f;
}

function matrix(f, rowKind, opts) {
  return crud.buildMatrix(graph.buildIndex(f.graph), Object.assign({ rowKind }, opts || {}));
}
const cells = (m, id) => { const r = m.rows.find((x) => x.id === id); return r ? r.cells : undefined; };

test('F-016-AC28 CRUD 구문 기준: CALL 구문의 행에 루틴(과 그 루틴이 부른 루틴)이 접근하는 테이블이 소문자로 나온다', () => {
  const f = shop();
  const m = matrix(f, 'statement');
  assert.deepEqual(cells(m, f.callClose), { [f.order]: 'ru', [f.hist]: 'c', [f.code]: 'r', [f.delivery]: 'r' },
    'CLOSE_ORDER → WRITE_HIST·FN_CODE, 재귀로 닿는 CLOSE_DAILY의 구문까지 간접');
  assert.deepEqual(cells(m, f.orderUpdate), { [f.order]: 'U', [f.code]: 'r' }, '루틴 안 구문: 자기 SQL은 대문자, 구문 안에서 부른 함수는 소문자');
  assert.deepEqual(cells(m, f.selectOrder), { [f.order]: 'R' });
  assert.equal(cells(m, f.callClose)[f.stock], undefined, '구문 기준은 트리거를 따라가지 않는다');
});

test('F-016-AC28 CRUD 메서드 기준: 직접 실행한 구문은 대문자, 구문이 부른 루틴과 직접 실행한 루틴은 소문자', () => {
  const f = shop();
  const m = matrix(f, 'method');
  assert.deepEqual(cells(m, f.mCall), { [f.order]: 'ru', [f.hist]: 'c', [f.code]: 'r', [f.delivery]: 'r' });
  assert.equal(cells(m, f.mBoth)[f.order], 'Ru', '직접 R + 루틴을 거친 u');
  assert.deepEqual(cells(m, f.mRoutine), { [f.hist]: 'c' }, '메서드가 루틴을 바로 EXECUTES하면 그 루틴의 구문도 간접이다');
  assert.equal(cells(m, f.ctl), undefined, '직접 실행하지 않는 메서드는 행이 없다');
  // 걸음 수 상한: 0이면 루틴 안으로 들어가지 않고, 1이면 구문이 부른 루틴까지
  assert.equal(cells(matrix(f, 'method', { maxDepth: 0 }), f.mCall), undefined);
  assert.deepEqual(cells(matrix(f, 'method', { maxDepth: 0 }), f.mBoth), { [f.order]: 'R' });
  assert.deepEqual(cells(matrix(f, 'method', { maxDepth: 1 }), f.mCall), { [f.order]: 'u' });
  assert.deepEqual(cells(matrix(f, 'method', { maxDepth: 2 }), f.mCall), { [f.order]: 'ru', [f.hist]: 'c', [f.code]: 'r', [f.delivery]: 'r' }, '2부터 그 루틴이 부른 루틴까지');
});

test('F-016-AC28 CRUD 루틴 기준: 자기 본문은 대문자, 부른 루틴의 구문은 소문자 (재귀·자기 호출에서 자기 구문을 간접으로 세지 않는다)', () => {
  const f = shop();
  const m = matrix(f, 'routine');
  assert.deepEqual(cells(m, f.closeDaily), { [f.order]: 'Ru', [f.delivery]: 'R', [f.hist]: 'c', [f.code]: 'r' });
  assert.deepEqual(cells(m, f.closeOrder), { [f.order]: 'rU', [f.hist]: 'c', [f.code]: 'r', [f.delivery]: 'r' });
  assert.deepEqual(cells(m, f.writeHist), { [f.hist]: 'C' });
  assert.deepEqual(cells(m, f.fnCode), { [f.code]: 'R' });
  assert.deepEqual(cells(m, f.trg), { [f.stock]: 'R' });
  assert.ok(m.rows.every((r) => r.node.kind === 'DB_ROUTINE'));
  assert.deepEqual(m.rows.map((r) => r.id), m.rows.map((r) => r.id).slice().sort(), '행은 노드 ID 순(표준 산출물과 같다)');
});

test('F-016-AC28 CRUD 엔드포인트 기준: 호출 사슬로 닿은 구문 가운데 루틴 본문의 것은 소문자, 트리거는 그 쓰기에 걸릴 때만', () => {
  const f = shop();
  const m = matrix(f, 'endpoint');
  assert.deepEqual(cells(m, f.ep), { [f.order]: 'Ru', [f.hist]: 'c', [f.code]: 'r', [f.delivery]: 'r', [f.stock]: 'r' },
    'UPDATE가 일으키는 AFTER UPDATE 트리거가 읽는 TB_STOCK까지');
  assert.deepEqual(m.rows.map((r) => r.id), [f.ep]);
  // 트리거가 INSERT에만 걸리면 그 테이블은 나오지 않는다
  const g = shop();
  g.graph.nodes.find((n) => n.id === g.trg).attrs.triggerEvent = 'BEFORE INSERT';
  assert.equal(cells(matrix(g, 'endpoint'), g.ep)[g.stock], undefined);
  // 깊이 상한: 엔드포인트에서 구문까지의 걸음 수
  assert.equal(cells(matrix(f, 'endpoint', { maxDepth: 2 }), f.ep), undefined, '엔드포인트 → 컨트롤러 → 매퍼 메서드까지만');
  assert.deepEqual(cells(matrix(f, 'endpoint', { maxDepth: 3 }), f.ep), { [f.order]: 'R' });
});

function dynamicShop() {
  const s = slice();
  const order = s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER');
  const backup = s.n('mybatis:stmt:orderDao.selectOrderBackup', 'SQL_STATEMENT', 'selectOrderBackup', 'orderDao.selectOrderBackup',
    { statementType: 'SELECT', namespace: 'orderDao', statementId: 'selectOrderBackup', dynamic: true }, loc('mapper/orderDao.xml', 38));
  const dyn1 = s.n('unresolved:sql-dynamic:' + backup, 'UNRESOLVED', '__DYNAMIC__', backup, { category: 'sql-dynamic' }, loc('mapper/orderDao.xml', 38));
  s.e(backup, 'READS', dyn1, { text: '__DYNAMIC__' }, 'UNRESOLVED', loc('mapper/orderDao.xml', 38));
  const mixed = s.n('mybatis:stmt:orderDao.selectMixed', 'SQL_STATEMENT', 'selectMixed', 'orderDao.selectMixed', { statementType: 'SELECT' }, loc('mapper/orderDao.xml', 50));
  const dyn2 = s.n('unresolved:sql-dynamic:' + mixed, 'UNRESOLVED', '__DYNAMIC__', mixed, { category: 'sql-dynamic' });
  s.e(mixed, 'READS', order);
  s.e(mixed, 'READS', dyn2, {}, 'UNRESOLVED', loc('mapper/orderDao.xml', 51));
  const routine = s.n('db:routine:PKG_ORDER.ARCHIVE_ORDERS', 'DB_ROUTINE', 'ARCHIVE_ORDERS', 'PKG_ORDER.ARCHIVE_ORDERS', { routineType: 'PROCEDURE' }, loc('db/pkg_order.pkb', 160));
  const archive = s.n('sql:stmt:PKG_ORDER.ARCHIVE_ORDERS:168', 'SQL_STATEMENT', 'PKG_ORDER.ARCHIVE_ORDERS:168', 'PKG_ORDER.ARCHIVE_ORDERS:168', { statementType: 'INSERT' }, loc('db/pkg_order.pkb', 168));
  const dyn3 = s.n('unresolved:sql-dynamic:' + archive, 'UNRESOLVED', 'INSERT INTO TB_ORDER_ARC_{*} SELECT * FROM TB_ORDER', archive, { category: 'sql-dynamic' });
  s.e(routine, 'CONTAINS', archive);
  s.e(archive, 'WRITES', dyn3, { op: 'INSERT', text: 'INSERT INTO TB_ORDER_ARC_{*} SELECT * FROM TB_ORDER' }, 'UNRESOLVED', loc('db/pkg_order.pkb', 168));
  const method = s.n('java:method:com.shop.dao.OrderDao#selectOrderBackup(java.lang.String)', 'JAVA_METHOD', 'selectOrderBackup', 'com.shop.dao.OrderDao#selectOrderBackup(String)');
  s.e(method, 'EXECUTES', backup);
  const plain = s.n('mybatis:stmt:orderDao.selectOrder', 'SQL_STATEMENT', 'selectOrder', 'orderDao.selectOrder', { statementType: 'SELECT' });
  s.e(plain, 'READS', order);
  return { graph: s.graph(), order, backup, mixed, archive, routine, method, plain };
}

test('F-016-AC29 CRUD: 대상 테이블을 정적으로 알 수 없는 구문은 (동적) 열과 구문 목록으로 드러난다', () => {
  const f = dynamicShop();
  const ix = graph.buildIndex(f.graph);
  const m = crud.buildMatrix(ix, { rowKind: 'statement' });
  assert.equal(m.dynamicColumn, true);
  assert.deepEqual(cells(m, f.backup), { [crud.DYNAMIC]: 'R' }, '동적 접근만 있는 구문도 행이 된다');
  assert.deepEqual(cells(m, f.mixed), { [f.order]: 'R', [crud.DYNAMIC]: 'R' });
  assert.deepEqual(cells(m, f.archive), { [crud.DYNAMIC]: 'C' });
  assert.deepEqual(m.tables.map((t) => t.name), ['TB_ORDER'], '(동적)은 테이블 목록에 넣지 않는다');
  assert.deepEqual(crud.columnsOf(m).map((c) => c.name), ['TB_ORDER', '(동적)'], '화면과 CSV의 마지막 열');
  assert.deepEqual(m.dynamicStatements.map((d) => [d.id, d.access, d.location, d.text]), [
    [f.mixed, 'R', 'mapper/orderDao.xml:50', null],
    [f.backup, 'R', 'mapper/orderDao.xml:38', '__DYNAMIC__'],
    [f.archive, 'C', 'db/pkg_order.pkb:168', 'INSERT INTO TB_ORDER_ARC_{*} SELECT * FROM TB_ORDER']
  ]);
  // 그 구문에 닿는 메서드·루틴의 매트릭스에도 (동적) 열이 생긴다. 구문 목록은 구문 기준에만 채운다.
  const methods = crud.buildMatrix(ix, { rowKind: 'method' });
  assert.deepEqual(cells(methods, f.method), { [crud.DYNAMIC]: 'R' });
  assert.deepEqual(methods.dynamicStatements, []);
  assert.deepEqual(cells(crud.buildMatrix(ix, { rowKind: 'routine' }), f.routine), { [crud.DYNAMIC]: 'C' });
  // 동적 구문이 없는 그래프에는 열과 목록이 없다
  const none = crud.buildMatrix(graph.buildIndex({ nodes: f.graph.nodes.filter((n) => n.id === f.order || n.id === f.plain),
    edges: f.graph.edges.filter((e) => e.from === f.plain) }), { rowKind: 'statement' });
  assert.equal(none.dynamicColumn, false);
  assert.deepEqual(none.dynamicStatements, []);
  assert.deepEqual(crud.columnsOf(none).map((c) => c.name), ['TB_ORDER']);
});

test('F-016-AC29 CRUD 범례: 대문자·소문자와 (동적) 열을 설명한다', () => {
  const text = crud.LEGEND.map((l) => l.key + ' ' + l.description).join('\n');
  assert.ok(/대문자/.test(text) && /소문자/.test(text) && text.includes('(동적)'), text);
  assert.ok(crud.LEGEND.some((l) => l.key === 'Ru'), '읽는 법의 예');
  assert.deepEqual(crud.ROW_KINDS.map((k) => k.key), ['statement', 'method', 'endpoint', 'routine'], '표준 산출물과 같은 네 기준');
});

test('F-016-AC29 CRUD 거르기: (동적) 열도 테이블 이름 필터로 고르고, 필터 뒤에도 간접 표기가 남는다', () => {
  const f = dynamicShop();
  const m = crud.buildMatrix(graph.buildIndex(f.graph), { rowKind: 'statement' });
  const only = crud.filterMatrix(m, { tableText: '동적' });
  assert.deepEqual(only.rows.map((r) => r.id).sort(), [f.backup, f.mixed, f.archive].sort());
  assert.deepEqual(crud.columnsOf(only).map((c) => c.name), ['(동적)']);
  const byRow = crud.filterMatrix(m, { rowText: 'selectOrder ' });
  assert.equal(byRow.dynamicColumn, true);
  const noDynamic = crud.filterMatrix(m, { rowText: 'orderDao.selectOrder', tableText: 'TB_ORDER' });
  assert.deepEqual(crud.columnsOf(noDynamic).map((c) => c.name), ['TB_ORDER']);
});

test('F-016-AC30 CRUD CSV: 표준 산출물과 같은 모양(머리줄 `<기준>,위치,<테이블…>`, RFC 4180, 줄 끝 \\n)', () => {
  const f = shop();
  const ix = graph.buildIndex(f.graph);
  const lines = crud.toCsv(crud.buildMatrix(ix, { rowKind: 'method' })).split('\n');
  assert.equal(lines[0], '메서드,위치,TB_CODE,TB_DELIVERY,TB_ORDER,TB_ORDER_HIST');
  assert.equal(lines[1], 'com.shop.dao.OrderDao#callDirect(),src/com.shop.dao.OrderDao.java:10,,,,c');
  assert.equal(lines[2], '"com.shop.dao.OrderDao#closeAndRead(Long, List<Order.Item>)",src/com.shop.dao.OrderDao.java:10,r,r,Ru,c',
    '메서드 이름은 타입 전체 이름 + 단순 매개변수, 쉼표가 든 칸은 따옴표로 감싼다');
  assert.equal(lines[lines.length - 1], '', '마지막 줄도 \\n으로 끝난다');
  assert.equal(crud.toCsv(crud.buildMatrix(ix, { rowKind: 'endpoint' })).split('\n')[0], '엔드포인트,위치,TB_CODE,TB_DELIVERY,TB_ORDER,TB_ORDER_HIST,TB_STOCK');
  assert.equal(crud.toCsv(crud.buildMatrix(ix, { rowKind: 'routine' })).split('\n')[1], 'FN_CODE,db/pkg_order.pkb:10,R,,,,');
  assert.equal(crud.toCsv(crud.buildMatrix(ix, { rowKind: 'statement' })).split('\n')[1],
    'com.shop.mapper.OrderMapper.callCloseOrder,mapper/OrderMapper.xml:197,r,r,ru,c,', '구문 기준의 열에는 트리거가 읽는 TB_STOCK도 있다(CALL 구문의 칸은 빈다)');
  assert.ok(crud.toCsv(crud.buildMatrix(ix, { rowKind: 'statement' }), { bom: true }).startsWith('﻿구문,위치,'));
  const d = dynamicShop();
  assert.ok(crud.toCsv(crud.buildMatrix(graph.buildIndex(d.graph), { rowKind: 'statement' })).split('\n')[0].endsWith(',TB_ORDER,(동적)'));
});
