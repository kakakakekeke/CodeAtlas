'use strict';
// 노드 상세의 뒤로·앞으로(F-016-R45).
// 사용자 보고(2026-10-11): 탐색에서 A를 고르고 상세 안의 엣지를 눌러 B, C로 가면 A로 돌아갈 방법이 없어 A를 다시 찾아야 했다.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assemble } = require('./tools/assemble.js');
const { boot, sleep } = require('./tools/boot.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');
const { core } = require('./helpers.js');

function sampleWindow() {
  return boot(assemble(JSON.stringify(sampleGraph()), ''));
}

/** scope(탐색의 상세 칸이나 서랍)에 지금 그려진 노드의 ID. */
function shown(doc, scope) {
  return doc.querySelector(scope + ' .detail-head .id').textContent;
}

/** 그 노드와 엣지로 이어진 노드의 ID들. */
function neighbors(window, id) {
  const d = window.CodeAtlas.graph.nodeDetail(window.CodeAtlas.app.index, id);
  const out = new Set();
  d.outgoing.concat(d.incoming).forEach((g) => g.items.forEach((it) => out.add(it.otherId)));
  return [...out];
}

/**
 * scope의 상세에서 not에 없는 노드로 가는 엣지 링크를 누르고 그 노드의 ID를 돌려준다.
 * 거기서 또 다른 노드로 갈 수 있는 것을 먼저 고른다(컬럼처럼 막다른 노드를 피한다).
 */
function follow(window, scope, not) {
  const doc = window.document;
  const links = doc.querySelectorAll(scope + ' .edge-row .link').filter((l) => !not.includes(l.getAttribute('data-id')));
  const onward = (l) => neighbors(window, l.getAttribute('data-id')).some((n) => n !== l.getAttribute('data-id') && !not.includes(n));
  const link = links.find(onward) || links[0];
  assert.ok(link, '따라갈 엣지가 없다: ' + shown(doc, scope));
  link.click();
  return link.getAttribute('data-id');
}

/** 탐색의 결과 목록에서 그 노드의 줄을 누른다. */
function pick(doc, id) {
  const row = doc.querySelectorAll('.view-explore .results .vrow').find((r) => r.getAttribute('title') === id);
  assert.ok(row, '목록에 없다: ' + id);
  row.click();
}

function hashOf(id) {
  return '#/explore?id=' + encodeURIComponent(id);
}

/** 주소를 어떻게 바꾸는지 기록한다: pushes(location.hash에 넣어 새 항목을 쌓음), replaced(replaceState), pushState(쓰면 안 된다). */
function trackAddress(window) {
  let hash = window.location.hash;
  const log = { pushes: [], replaced: [], pushState: [] };
  window.location = { get hash() { return hash; }, set hash(v) { hash = String(v); log.pushes.push(hash); } };
  window.history = {
    replaceState(state, title, url) { hash = String(url); log.replaced.push(hash); },
    pushState(state, title, url) { log.pushState.push(String(url)); }
  };
  /** 브라우저의 뒤로·앞으로 가기: 주소만 바뀌고 hashchange가 온다. */
  log.browserGoes = (to) => { hash = to; window.fire('hashchange'); };
  return log;
}

test('F-016-AC83 자취: 새로 가면 앞으로 갈 칸을 버리고, 목록에서 연달아 고른 것은 한 칸이며, 주소로 온 이동은 앞뒤 칸에 맞춘다', () => {
  const t = core('trail').create();
  assert.equal(t.current(), null);
  assert.equal(t.canBack(), false);
  assert.equal(t.canForward(), false);
  assert.equal(t.back(), null);

  assert.equal(t.visit('A', true), 'push');
  assert.equal(t.visit('A2', true), 'replace', '목록에서 연달아 고르면 칸이 늘지 않는다');
  assert.equal(t.canBack(), false);
  assert.equal(t.visit('B', false), 'push');
  assert.equal(t.visit('B', false), 'same');
  assert.equal(t.visit('C', false), 'push');
  assert.equal(t.peek(-1), 'B');
  assert.equal(t.peek(1), null);

  assert.equal(t.back(), 'B');
  assert.equal(t.back(), 'A2');
  assert.equal(t.back(), null);
  assert.equal(t.current(), 'A2');
  assert.equal(t.forward(), 'B');
  assert.equal(t.peek(1), 'C');
  assert.equal(t.visit('D', false), 'push');
  assert.equal(t.canForward(), false, '뒤로 간 뒤 다른 곳으로 가면 앞으로 갈 칸(C)은 버린다');
  assert.equal(t.peek(-1), 'B');

  // 링크로 온 칸에서 목록의 노드를 고르면 새 칸이고, 이어서 고르면 그 칸을 바꾼다
  assert.equal(t.visit('E', true), 'push');
  assert.equal(t.visit('F', true), 'replace');
  assert.equal(t.peek(-1), 'D');

  // 주소(브라우저의 뒤로·앞으로)로 온 이동
  assert.equal(t.sync('F'), 'same');
  assert.equal(t.sync('D'), 'back');
  assert.equal(t.current(), 'D');
  assert.equal(t.sync('F'), 'forward');
  assert.equal(t.sync('Z'), 'push');
  assert.equal(t.peek(-1), 'F');
});

test('F-016-AC84 탐색: 상세 안의 엣지로 A → B → C로 간 뒤 "뒤로"가 B, A를 다시 보이고 "앞으로"가 되돌린다', async () => {
  const window = await sampleWindow();
  const doc = window.document;
  const scope = '.view-explore .detail';
  const back = doc.querySelector(scope + ' .nav-back');
  const forward = doc.querySelector(scope + ' .nav-forward');
  assert.ok(back && forward, '상세 칸에 뒤로·앞으로 단추가 있다');
  assert.ok(back.disabled && forward.disabled, '아무것도 고르지 않았을 때는 둘 다 꺼져 있다');

  const a = ids.TB.orders;
  pick(doc, a);
  assert.equal(shown(doc, scope), a);
  assert.ok(back.disabled, '처음 고른 노드에서는 돌아갈 곳이 없다');

  const b = follow(window, scope, [a]);
  assert.equal(shown(doc, scope), b);
  assert.ok(!back.disabled && forward.disabled);
  const c = follow(window, scope, [a, b]);
  assert.equal(shown(doc, scope), c);

  back.click();
  assert.equal(shown(doc, scope), b);
  assert.ok(!back.disabled && !forward.disabled);
  assert.ok(back.getAttribute('title').includes(window.CodeAtlas.app.index.label(a)), '단추의 풍선말에 돌아갈 노드가 나온다: ' + back.getAttribute('title'));
  back.click();
  assert.equal(shown(doc, scope), a);
  assert.ok(back.disabled && !forward.disabled);
  assert.ok(doc.querySelectorAll('.view-explore .results .vrow.selected').some((r) => r.getAttribute('title') === a), '목록의 선택 표시도 따라온다');

  forward.click();
  assert.equal(shown(doc, scope), b);
  forward.click();
  assert.equal(shown(doc, scope), c);
  assert.ok(forward.disabled);

  // 뒤로 간 뒤 다른 엣지를 타면 앞으로 갈 자취는 없어진다
  back.click();
  back.click();
  const other = follow(window, scope, [a]);
  assert.equal(shown(doc, scope), other);
  assert.ok(forward.disabled && !back.disabled);
  await sleep(10);
  assert.deepEqual(window.errors, []);
});

test('F-016-AC84 탐색: 속성 값의 노드 링크로 간 것도 "뒤로"로 돌아온다', async () => {
  const window = await sampleWindow();
  const doc = window.document;
  const scope = '.view-explore .detail';
  const index = window.CodeAtlas.app.index;
  // 속성 값에 노드 링크가 있는 노드를 찾는다
  const G = window.CodeAtlas.graph;
  const start = index.nodes.find((n) => G.nodeDetail(index, n.id).attrs.some((at) => at.items.some((it) => it.nodeId && it.nodeId !== n.id)));
  assert.ok(start, '픽스처에 속성 값이 노드를 가리키는 노드가 있다');
  window.CodeAtlas.app.navigate('explore', { id: start.id });
  assert.equal(shown(doc, scope), start.id);
  doc.querySelector(scope + ' .kv .link').click();
  assert.notEqual(shown(doc, scope), start.id);
  doc.querySelector(scope + ' .nav-back').click();
  assert.equal(shown(doc, scope), start.id);
});

test('F-016-AC85 탐색: 목록에서 연달아 고른 노드는 자취에 쌓이지 않는다(마지막에 고른 것으로 돌아온다)', async () => {
  const window = await sampleWindow();
  const doc = window.document;
  const scope = '.view-explore .detail';
  const back = doc.querySelector(scope + ' .nav-back');
  const rows = doc.querySelectorAll('.view-explore .results .vrow');
  rows[0].click();
  rows[1].click();
  const a = ids.TB.orders;
  pick(doc, a);
  assert.ok(back.disabled, '목록을 훑은 것만으로는 돌아갈 곳이 생기지 않는다');
  const b = follow(window, scope, [a]);
  back.click();
  assert.equal(shown(doc, scope), a);
  assert.ok(back.disabled);
  // 링크로 간 노드에서 목록의 다른 노드를 고르면 그 노드로는 돌아갈 수 있다
  doc.querySelector(scope + ' .nav-forward').click();
  assert.equal(shown(doc, scope), b);
  rows[0].click();
  assert.ok(!back.disabled);
  back.click();
  assert.equal(shown(doc, scope), b);
});

test('F-016-AC86 다른 화면의 상세 서랍에서도 엣지로 옮겨 간 노드에서 "뒤로"로 돌아온다', async () => {
  const window = await sampleWindow();
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('diagnostics', {});
  const scope = '.drawer';
  const back = doc.querySelector(scope + ' .nav-back');
  const forward = doc.querySelector(scope + ' .nav-forward');
  assert.ok(back && forward, '서랍 머리에 뒤로·앞으로 단추가 있다');

  const a = ids.TB.orders;
  app.showDetail(a);
  assert.ok(doc.querySelector(scope).classList.contains('open'));
  assert.equal(shown(doc, scope), a);
  assert.ok(back.disabled && forward.disabled);

  const b = follow(window, scope, [a]);
  assert.equal(shown(doc, scope), b);
  assert.ok(doc.querySelector(scope).classList.contains('open'), '서랍은 열린 채다');
  const c = follow(window, scope, [a, b]);
  back.click();
  assert.equal(shown(doc, scope), b);
  back.click();
  assert.equal(shown(doc, scope), a);
  assert.ok(back.disabled && !forward.disabled);
  forward.click();
  forward.click();
  assert.equal(shown(doc, scope), c);
  // 서랍에 그린 상세에는 "탐색에서 열기"가 그대로 있다
  assert.ok(doc.querySelectorAll(scope + ' .detail-actions .btn').some((x) => x.textContent === '탐색에서 열기'));
  assert.deepEqual(window.errors, []);
});

test('F-016-AC87 주소: 엣지와 단추로 옮기면 주소에 새 항목이 쌓이고, 브라우저의 뒤로·앞으로 가기로 온 주소의 노드가 뜨며 자취가 맞춰진다', async () => {
  const window = await sampleWindow();
  const doc = window.document;
  const scope = '.view-explore .detail';
  const back = doc.querySelector(scope + ' .nav-back');
  const forward = doc.querySelector(scope + ' .nav-forward');
  const address = trackAddress(window);

  const a = ids.TB.orders;
  pick(doc, a);
  assert.deepEqual(address.pushes, [], '목록에서 고른 것은 주소를 그 자리에서 바꾼다(항목을 쌓지 않는다)');
  assert.deepEqual(address.replaced, [hashOf(a)]);

  const b = follow(window, scope, [a]);
  const c = follow(window, scope, [a, b]);
  assert.deepEqual(address.pushes, [hashOf(b), hashOf(c)]);

  // 브라우저의 뒤로 가기: C → B → A
  address.browserGoes(hashOf(b));
  assert.equal(shown(doc, scope), b);
  assert.ok(!back.disabled && !forward.disabled, '주소로 돌아온 것도 자취의 한 칸 뒤로 간 것이다');
  address.browserGoes(hashOf(a));
  assert.equal(shown(doc, scope), a);
  assert.ok(back.disabled && !forward.disabled);
  // 브라우저의 앞으로 가기
  address.browserGoes(hashOf(b));
  assert.equal(shown(doc, scope), b);
  assert.deepEqual(address.pushes, [hashOf(b), hashOf(c)], '주소를 따라간 이동은 주소를 다시 바꾸지 않는다');

  // 뷰어의 단추로 옮긴 것도 주소에 쌓인다: 브라우저의 뒤로 가기가 방금 본 노드로 돌아온다
  back.click();
  assert.equal(shown(doc, scope), a);
  assert.deepEqual(address.pushes, [hashOf(b), hashOf(c), hashOf(a)]);
  address.browserGoes(hashOf(b));
  assert.equal(shown(doc, scope), b);
  assert.ok(!back.disabled && !forward.disabled);

  assert.deepEqual(address.pushState, [], 'history.pushState는 쓰지 않는다(file:에서 막힌다)');
  assert.deepEqual(window.errors, []);
});

test('F-016-AC87 주소를 바꾸지 못하는 창(replaceState가 예외, location.hash를 못 씀)에서도 단추로 돌아온다', async () => {
  const window = await sampleWindow();
  const doc = window.document;
  const scope = '.view-explore .detail';
  const deny = () => { const e = new Error('SecurityError'); e.name = 'SecurityError'; throw e; };
  window.location = { get hash() { return ''; }, set hash(v) { deny(); } };
  window.history = { replaceState: deny, pushState: deny };
  const a = ids.TB.orders;
  pick(doc, a);
  const b = follow(window, scope, [a]);
  assert.equal(shown(doc, scope), b);
  doc.querySelector(scope + ' .nav-back').click();
  assert.equal(shown(doc, scope), a);
  assert.deepEqual(window.errors, []);
});
