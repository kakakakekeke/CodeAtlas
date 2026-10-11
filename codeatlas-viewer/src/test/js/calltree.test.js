'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');

const graph = core('graph');
const calltree = core('calltree');

function index() { return graph.buildIndex(sampleGraph()); }

/** 루트에서 잎까지의 모든 경로를 노드 ID 배열로 돌려준다. */
function paths(node, prefix, out) {
  const here = prefix.concat(node.id);
  if (!node.children || node.children.length === 0) out.push(here);
  else node.children.forEach((c) => paths(c, here, out));
  return out;
}
/** want의 ID들이 path 안에 그 순서대로(사이에 다른 노드가 있어도 됨) 나오는가. */
function containsInOrder(path, want) {
  let i = 0;
  for (const id of path) if (id === want[i]) i++;
  return i === want.length;
}

test('F-016-AC6 호출되는 쪽: JSP에서 테이블까지 한 트리로 이어진다', () => {
  const ix = index();
  const root = calltree.createRoot(ix, ids.JSP.list, 'callees');
  calltree.expandAll(ix, root, { maxDepth: 30 });
  const all = paths(root, [], []);
  const want = [ids.JSP.list, 'js:module:' + ids.F.js, ids.JS.submit, ids.RQ.create, ids.EP.create, ids.M.ctlCreate,
    ids.M.svcPlace, ids.M.implPlace, ids.M.mapInsert, ids.S.insert, ids.TB.orders];
  assert.ok(all.some((p) => containsInOrder(p, want)), 'JSP → JS → 요청 → 엔드포인트 → 컨트롤러 → 서비스 → 매퍼 → 구문 → 테이블 경로가 있어야 한다');
  // JSP가 직접 가진 링크도 엔드포인트로 이어진다
  assert.ok(all.some((p) => containsInOrder(p, [ids.JSP.list, ids.RQ.detailLink, ids.EP.detail, ids.M.ctlDetail])));
  // 포함 관계(INCLUDES)의 JSP도 자식이다
  assert.ok(root.children.some((c) => c.id === ids.JSP.header && c.link.kind === 'INCLUDES'));
});

test('F-016-AC6 호출되는 쪽: 매퍼 구문에서 프로시저 안의 구문과 테이블까지', () => {
  const ix = index();
  const root = calltree.createRoot(ix, ids.M.mapClose, 'callees');
  calltree.expandAll(ix, root, { maxDepth: 10 });
  const all = paths(root, [], []);
  assert.ok(all.some((p) => containsInOrder(p, [ids.M.mapClose, ids.S.close, ids.ROUTINE, ids.RS2, ids.TB.history])));
  assert.ok(all.some((p) => containsInOrder(p, [ids.ROUTINE, ids.ROUTINE_LOG])));
  const stmtNode = root.children[0];
  assert.equal(stmtNode.link.kind, 'EXECUTES');
  const routineNode = stmtNode.children.find((c) => c.id === ids.ROUTINE);
  assert.ok(routineNode.children.some((c) => c.id === ids.RS1 && c.link.kind === 'CONTAINS'));
});

test('F-016-AC6 호출되는 쪽: 컬럼 엣지는 기본으로 숨기고 옵션으로 켠다. 흐름이 아닌 엣지는 따르지 않는다', () => {
  const ix = index();
  const kids = calltree.childLinks(ix, ids.S.select, 'callees');
  assert.deepEqual(kids.map((k) => k.nodeId).sort(), [ids.TB.customers, ids.TB.orders, 'mybatis:fragment:com.shop.mapper.OrderMapper.orderColumns'].sort());
  const withCols = calltree.childLinks(ix, ids.S.select, 'callees', { includeColumns: true });
  assert.ok(withCols.some((k) => k.nodeId === 'db:column:ORDERS.STATUS'));
  // USES_TYPE, ACCESSES, INSTANTIATES, 타입의 CONTAINS는 콜트리에 나오지 않는다
  const fromCreate = calltree.childLinks(ix, ids.M.ctlCreate, 'callees');
  assert.deepEqual(fromCreate.map((k) => k.nodeId), [ids.M.svcPlace]);
});

test('F-016-AC6 같은 대상으로 가는 여러 호출 지점은 한 자식으로 합치고 횟수를 남긴다', () => {
  const ix = index();
  const kids = calltree.childLinks(ix, ids.M.implPlace, 'callees');
  const insertItem = kids.filter((k) => k.nodeId === ids.M.mapInsertItem);
  assert.equal(insertItem.length, 1);
  assert.equal(insertItem[0].count, 2);
  assert.equal(insertItem[0].locations.length, 2);
  // 호출 순서(위치)대로 정렬된다
  const order = kids.filter((k) => k.kind === 'CALLS').map((k) => k.nodeId);
  assert.ok(order.indexOf(ids.M.implValidate) < order.indexOf(ids.M.mapInsert));
  assert.ok(order.indexOf(ids.M.mapInsert) < order.indexOf(ids.M.implRetry));
  // 미해결 호출도 버리지 않는다
  const unresolved = kids.find((k) => k.nodeId === 'unresolved:java-call:paymentGateway.charge');
  assert.equal(unresolved.confidence, 'UNRESOLVED');
});

test('F-016-AC7 호출하는 쪽: 테이블에서 화면까지 거슬러 올라간다', () => {
  const ix = index();
  const root = calltree.createRoot(ix, ids.TB.history, 'callers');
  calltree.expandAll(ix, root, { maxDepth: 30 });
  const all = paths(root, [], []);
  // 구현(implClose)에서 위로는 인터페이스 메서드를 거치지 않고 그 호출자(컨트롤러)가 "인터페이스 경유"로 바로 나온다(F-016-AC35).
  const want = [ids.TB.history, ids.RS2, ids.ROUTINE, ids.S.close, ids.M.mapClose, ids.M.implClose,
    ids.M.ctlClose, ids.EP.close, ids.RQ.closeForm, ids.JSP.detail];
  assert.ok(all.some((p) => containsInOrder(p, want)), '테이블 → 루틴 구문 → 루틴 → 매퍼 구문 → 매퍼 → 서비스 → 컨트롤러 → 엔드포인트 → 요청 → JSP');
  assert.ok(!all.some((p) => p.includes(ids.M.svcClose)), '재정의 대상(인터페이스 메서드)은 줄로 나오지 않는다');
  const first = root.children.find((c) => c.id === ids.RS2);
  assert.equal(first.link.kind, 'WRITES');
  assert.equal(first.link.attrs.op, 'INSERT');
});

test('F-016-AC7 호출하는 쪽: 배치 스텝과 잡까지 올라간다', () => {
  const ix = index();
  const root = calltree.createRoot(ix, ids.TB.settlements, 'callers');
  calltree.expandAll(ix, root, { maxDepth: 10 });
  const all = paths(root, [], []);
  assert.ok(all.some((p) => containsInOrder(p, [ids.TB.settlements, ids.S.settle, 'batch:step:settlementJob/mergeStep', 'batch:job:settlementJob'])));
  const fromSelect = calltree.createRoot(ix, ids.M.mapSelect, 'callers');
  calltree.expandAll(ix, fromSelect, { maxDepth: 10 });
  assert.ok(paths(fromSelect, [], []).some((p) => containsInOrder(p, [ids.M.mapSelect, ids.M.taskletExec, 'batch:step:settlementJob/loadStep', 'batch:job:settlementJob'])));
});

test('F-016-AC8 인터페이스 메서드는 OVERRIDES 역방향으로 구현체까지 펼친다', () => {
  const ix = index();
  const down = calltree.childLinks(ix, ids.M.svcPlace, 'callees');
  assert.equal(down.length, 1);
  assert.equal(down[0].nodeId, ids.M.implPlace);
  assert.equal(down[0].kind, 'OVERRIDES');
  assert.equal(down[0].reversed, true);
  assert.equal(down[0].label, '구현');
  assert.equal(down[0].impl, true);
  // 위로: 인터페이스 메서드를 부르는 곳이 구현체의 호출자로 나온다("인터페이스 경유", 표준 산출물의 [via interface])
  const up = calltree.childLinks(ix, ids.M.implPlace, 'callers');
  const viaInterface = up.find((k) => k.nodeId === ids.M.ctlCreate);
  assert.equal(viaInterface.kind, 'CALLS');
  assert.equal(viaInterface.viaInterface, true);
  assert.equal(viaInterface.viaMethod, ids.M.svcPlace, '거친 인터페이스 메서드');
  assert.equal(viaInterface.reversed, true);
  assert.ok(!up.some((k) => k.nodeId === ids.M.svcPlace), '인터페이스 메서드 자체는 줄이 되지 않는다');
  // 구현체에서 컨트롤러와 엔드포인트까지
  const root = calltree.createRoot(ix, ids.M.implPlace, 'callers');
  calltree.expandAll(ix, root, { maxDepth: 6 });
  assert.ok(paths(root, [], []).some((p) => containsInOrder(p, [ids.M.implPlace, ids.M.ctlCreate, ids.EP.create])));
});

test('F-016-AC9 순환은 표시하고 멈춘다', () => {
  const ix = index();
  const root = calltree.createRoot(ix, ids.M.implPlace, 'callees');
  calltree.expandAll(ix, root, { maxDepth: 50 });
  const retry = root.children.find((c) => c.id === ids.M.implRetry);
  assert.equal(retry.cycle, false);
  const back = retry.children.find((c) => c.id === ids.M.implPlace);
  assert.equal(back.cycle, true);
  assert.deepEqual(back.children, []);
  assert.equal(calltree.hasChildren(ix, back), false);
  assert.deepEqual(calltree.expand(ix, back), []);
  // 호출하는 쪽도 같다
  const up = calltree.createRoot(ix, ids.M.implRetry, 'callers');
  calltree.expandAll(ix, up, { maxDepth: 50 });
  const place = up.children.find((c) => c.id === ids.M.implPlace);
  assert.ok(place.children.some((c) => c.id === ids.M.implRetry && c.cycle));
});

test('F-016-AC9 지연 펼침: 펼치기 전에는 자식을 계산하지 않는다', () => {
  const ix = index();
  const root = calltree.createRoot(ix, ids.EP.create, 'callees');
  assert.equal(root.children, null);
  assert.equal(calltree.hasChildren(ix, root), true);
  const kids = calltree.expand(ix, root);
  assert.equal(kids.length, 1);
  assert.equal(kids[0].id, ids.M.ctlCreate);
  assert.equal(kids[0].children, null);
  assert.equal(kids[0].depth, 1);
  assert.equal(kids[0].parent, root);
  assert.equal(calltree.expand(ix, root), kids, '다시 펼치면 이미 만든 자식을 돌려준다');
  assert.equal(calltree.hasChildren(ix, calltree.createRoot(ix, ids.TB.coupons, 'callees')), false);
});

test('F-016-AC9 expandAll은 깊이와 노드 수 상한에서 멈추고 잘렸음을 알린다', () => {
  const ix = index();
  const root = calltree.createRoot(ix, ids.JSP.list, 'callees');
  const r1 = calltree.expandAll(ix, root, { maxDepth: 2 });
  assert.equal(r1.truncated, true);
  const depth2 = root.children.find((c) => c.id === 'js:module:' + ids.F.js).children;
  assert.equal(depth2.length, 3);
  assert.ok(depth2.every((c) => c.children === null));
  const root2 = calltree.createRoot(ix, ids.JSP.list, 'callees');
  const r2 = calltree.expandAll(ix, root2, { maxDepth: 30, maxNodes: 5 });
  assert.equal(r2.truncated, true);
  assert.ok(r2.count <= 5 + 20);
});

test('콜트리 글로 내보내기: 들여쓰기와 관계, 순환 표시', () => {
  const ix = index();
  const root = calltree.createRoot(ix, ids.M.implPlace, 'callees');
  calltree.expandAll(ix, root, { maxDepth: 3 });
  const text = calltree.toText(ix, root);
  const lines = text.split('\n');
  assert.ok(lines[0].includes('OrderServiceImpl#placeOrder'));
  assert.ok(lines.some((l) => /^ {2}CALLS .*validate/.test(l)));
  assert.ok(lines.some((l) => l.includes('(순환)')));
  assert.ok(lines.some((l) => l.includes('[UNRESOLVED]')));
});

test('콜트리 기준으로 고를 수 있는 종류', () => {
  for (const k of ['JAVA_METHOD', 'HTTP_ENDPOINT', 'JS_FUNCTION', 'SQL_STATEMENT', 'DB_TABLE', 'DB_ROUTINE', 'JSP_PAGE']) {
    assert.ok(calltree.ROOT_KINDS.includes(k), k);
  }
  assert.ok(!calltree.ROOT_KINDS.includes('JAVA_TYPE'));
});
