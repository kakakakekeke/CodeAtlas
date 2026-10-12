'use strict';
// 노드 상세의 엣지 줄에 뜨는 풍선(F-016-R50)과 탐색 화면의 나눔선(F-016-R51)을 본다(사용자 요청 2026-10-12).
// 엣지 줄은 좁은 칸에서 요약과 위치가 `…`로 잘린다(같은 테이블로 가는 JOINS, 같은 매퍼 파일의 READS·WRITES가 서로 같아 보인다).
// 줄의 배치는 그대로 두고, 줄에 마우스를 올리거나 줄을 누르면 잘린 것을 넓은 카드에 항목별로 맞춰 다 보인다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { read, assemble } = require('./tools/assemble.js');
const { boot, sleep } = require('./tools/boot.js');
const { slice, loc } = require('./fixtures/slice.js');

const MAPPER = 'src/main/resources/egovframework/mapper/com/utl/sys/dbm/EgovDbMntrng_SQL_oracle.xml';

/** 로그 테이블이 공통 코드 테이블을 코드 그룹만 달리해 조인하고, 같은 매퍼 파일의 구문들이 읽고 쓰는 그래프(전자정부 공통컴포넌트에서 본 모양). */
function codeJoins() {
  const s = slice();
  const f = {
    log: s.n('db:table:COMTHDBMNTRNGLOGINFO', 'DB_TABLE', 'COMTHDBMNTRNGLOGINFO', 'COMTHDBMNTRNGLOGINFO', { primaryKey: ['LOG_ID'] }),
    code: s.n('db:table:COMTCCMMNDETAILCODE', 'DB_TABLE', 'COMTCCMMNDETAILCODE', 'COMTCCMMNDETAILCODE', { primaryKey: ['CODE_ID', 'CODE'] }),
    hist: s.n('db:table:COMTHDBMNTRNGHIST', 'DB_TABLE', 'COMTHDBMNTRNGHIST', 'COMTHDBMNTRNGHIST', { primaryKey: ['LOG_ID', 'SN'] }),
    list: s.n('mybatis:stmt:DbMntrngDao.selectDbMntrngLogList', 'SQL_STATEMENT', 'selectDbMntrngLogList', 'DbMntrngDao.selectDbMntrngLogList', { statementType: 'SELECT' }),
    insert: s.n('mybatis:stmt:DbMntrngDao.insertDbMntrngLog', 'SQL_STATEMENT', 'insertDbMntrngLog', 'DbMntrngDao.insertDbMntrngLog', { statementType: 'INSERT' })
  };
  const join = (column, group, line, endLine) => s.e(f.log, 'JOINS', f.code, { leftColumns: [column], rightColumns: ['CODE'],
    rightFixedColumns: ['CODE_ID'], rightFixedValues: ['CODE_ID=' + group], statement: f.list }, 'RESOLVED', loc(MAPPER, line, endLine));
  join('DBMS_KND', 'COM048', 187, 221);
  join('MNTRNG_STTUS', 'COM046', 223, 249);
  s.e(f.log, 'JOINS', f.hist, { leftColumns: ['LOG_ID', 'SN'], rightColumns: ['LOG_ID', 'HIST_SN'], statement: f.list }, 'RESOLVED', loc(MAPPER, 250));
  s.e(f.list, 'READS', f.log, {}, 'RESOLVED', loc(MAPPER, 187, 221));
  s.e(f.insert, 'WRITES', f.log, { op: 'INSERT' }, 'RESOLVED', loc(MAPPER, 164));
  f.graph = s.graph();
  return f;
}

async function open(f) {
  const window = await boot(assemble(JSON.stringify(f.graph), ''));
  // 기다리는 시간을 줄인다(화면에서는 350ms 뒤에 뜨고 200ms 뒤에 닫힌다)
  window.CodeAtlas.ui.edgePop.showDelay = 5;
  window.CodeAtlas.ui.edgePop.hideDelay = 5;
  window.CodeAtlas.app.navigate('explore', { id: f.log });
  await sleep(20);
  return window;
}

const texts = (els) => els.map((e) => e.textContent);
const rowsOf = (doc) => doc.querySelectorAll('.view-explore .edge-row');
/** 풍선의 항목 표를 { 이름: 값 글자 }로. */
function fields(pop) {
  const out = {};
  const main = pop.querySelector('.edge-pop-main');
  const dts = main.querySelectorAll('dt');
  const dds = main.querySelectorAll('dd');
  dts.forEach((dt, i) => { out[dt.textContent] = dds[i]; });
  return out;
}

test('F-016-AC99 엣지 줄: 배치는 전과 같은 한 줄이고 브라우저 풍선말(title)을 달지 않는다', async () => {
  const f = codeJoins();
  const window = await open(f);
  const doc = window.document;
  const join = rowsOf(doc).find((r) => r.textContent.includes('DBMS_KND'));
  assert.deepEqual(join.children.map((c) => c.className.split(' ')[0]), ['edge-toggle', 'conf', 'badge', 'link', 'edge-summary', 'extra']);
  assert.equal(join.querySelector('.edge-summary').textContent, "COMTHDBMNTRNGLOGINFO.DBMS_KND = COMTCCMMNDETAILCODE.CODE · COMTCCMMNDETAILCODE 고정 CODE_ID='COM048'");
  assert.equal(join.querySelector('.extra').textContent, MAPPER + ':187-221');
  // 풍선이 둘 뜨지 않게: 잘린 글자의 전체는 엣지 풍선이 보인다
  ['.link', '.edge-summary', '.extra'].forEach((sel) => assert.equal(join.querySelector(sel).getAttribute('title'), null, sel));
  assert.equal(join.querySelector('.link').getAttribute('data-id'), f.code);
  assert.equal(doc.querySelector('.edge-pop'), null, '마우스를 올리기 전에는 풍선이 없다');
  // 스타일: 풍선은 화면 기준 위치에 뜬다
  assert.match(read('css/viewer.css'), /\n\.edge-pop\s*\{[^}]*position:\s*fixed/);
});

test('F-016-AC99 엣지 풍선: 줄에 마우스를 올리면 종류, 양 끝, 요약의 조각, 전체 위치, 모든 속성을 항목별로 보인다', async () => {
  const f = codeJoins();
  const window = await open(f);
  const doc = window.document;
  const rows = rowsOf(doc);
  const join = rows.find((r) => r.textContent.includes('DBMS_KND'));
  join.fire('mouseenter', { bubbles: false });
  assert.equal(doc.querySelector('.edge-pop'), null, '바로 뜨지는 않는다(훑고 지나갈 때 번쩍이지 않게)');
  await sleep(30);
  let pop = doc.querySelector('.edge-pop');
  assert.ok(pop, '잠깐 뒤에 뜬다');
  assert.ok(join.classList.contains('pop-on'), '풍선의 줄을 표시한다');
  assert.equal(pop.querySelector('.edge-pop-kind').textContent, 'JOINS');
  assert.equal(pop.querySelector('.edge-pop-head .conf').textContent, 'RESOLVED');
  let field = fields(pop);
  assert.deepEqual(Object.keys(field), ['출발', '도착', '요약', '위치']);
  // 양 끝: 이 노드는 글자로, 상대 노드는 링크로. ID도 적는다
  assert.ok(field['출발'].textContent.includes('COMTHDBMNTRNGLOGINFO') && field['출발'].textContent.includes('이 노드'));
  assert.equal(field['출발'].querySelector('.link'), null);
  assert.equal(field['도착'].querySelector('.link').textContent, 'COMTCCMMNDETAILCODE');
  assert.ok(field['도착'].textContent.includes(f.code));
  // 요약은 ` · `로 가른 조각마다 한 줄이다
  assert.deepEqual(texts(field['요약'].querySelectorAll('.part')),
    ['COMTHDBMNTRNGLOGINFO.DBMS_KND = COMTCCMMNDETAILCODE.CODE', "COMTCCMMNDETAILCODE 고정 CODE_ID='COM048'"]);
  assert.equal(field['위치'].textContent, MAPPER + ':187-221');
  // 속성은 줄을 펼쳤을 때와 같은 표이고, 노드인 값은 링크다
  const attrs = pop.querySelector('.edge-pop-attrs');
  assert.deepEqual(texts(attrs.querySelectorAll('dt')), ['leftColumns', 'rightColumns', 'rightFixedColumns', 'rightFixedValues', 'statement']);
  assert.ok(attrs.querySelectorAll('.link').some((l) => l.textContent === f.list));

  // 다른 줄로 옮기면 그 줄의 풍선으로 바뀐다. 풍선은 늘 하나다
  const read = rows.find((r) => r.textContent.includes('selectDbMntrngLogList') && r.closest('.edge-group').textContent.startsWith('READS'));
  join.fire('mouseleave', { bubbles: false });
  read.fire('mouseenter', { bubbles: false });
  await sleep(120);
  assert.equal(doc.querySelectorAll('.edge-pop').length, 1);
  pop = doc.querySelector('.edge-pop');
  assert.equal(pop.querySelector('.edge-pop-kind').textContent, 'READS');
  assert.ok(!join.classList.contains('pop-on') && read.classList.contains('pop-on'));
  field = fields(pop);
  assert.deepEqual(Object.keys(field), ['출발', '도착', '위치'], '요약이 없는 엣지에는 요약 칸이 없다');
  assert.equal(field['출발'].querySelector('.link').textContent, 'DbMntrngDao.selectDbMntrngLogList');
  assert.ok(field['도착'].textContent.includes('이 노드'));
  assert.equal(field['위치'].textContent, MAPPER + ':187-221');
  assert.equal(pop.querySelector('.edge-pop-attrs'), null, '속성이 없으면 속성 표가 없다');

  // 컬럼 쌍이 여럿이면 쌍 표가 풍선에도 나온다
  const multi = rows.find((r) => r.textContent.includes('컬럼 2쌍'));
  read.fire('mouseleave', { bubbles: false });
  multi.fire('mouseenter', { bubbles: false });
  await sleep(120);
  pop = doc.querySelector('.edge-pop');
  assert.deepEqual(pop.querySelectorAll('.edge-pairs .pair').map((p) => texts(p.querySelectorAll('span'))),
    [['COMTHDBMNTRNGLOGINFO', '', 'COMTHDBMNTRNGHIST'], ['LOG_ID', '=', 'LOG_ID'], ['SN', '=', 'HIST_SN']]);

  // 줄을 떠나면 닫히고, 풍선 위로 옮겨 가면 남는다
  multi.fire('mouseleave', { bubbles: false });
  pop.fire('mouseenter', { bubbles: false });
  await sleep(30);
  assert.ok(doc.querySelector('.edge-pop'), '풍선 위에 있는 동안은 남는다');
  pop.fire('mouseleave', { bubbles: false });
  await sleep(30);
  assert.equal(doc.querySelector('.edge-pop'), null);
  assert.ok(!multi.classList.contains('pop-on'));
  assert.deepEqual(window.errors, []);
});

test('F-016-AC100 엣지 풍선 고정: 줄을 누르면 떠 있고, 닫기 단추·Esc·바깥 누르기·다른 노드로 가기로 닫힌다', async () => {
  const f = codeJoins();
  const window = await open(f);
  const doc = window.document;
  const row = () => rowsOf(doc).find((r) => r.textContent.includes('insertDbMntrngLog'));
  const pinned = () => doc.querySelector('.edge-pop.pinned');

  // 줄을 누르면 기다리지 않고 뜨고, 줄을 떠나도 남는다
  row().click();
  assert.ok(pinned(), '눌러서 고정');
  assert.equal(pinned().querySelector('.edge-pop-kind').textContent, 'WRITES');
  assert.deepEqual(texts(pinned().querySelectorAll('.edge-pop-attrs dt')), ['op']);
  row().fire('mouseleave', { bubbles: false });
  await sleep(30);
  assert.ok(pinned(), '고정한 풍선은 줄을 떠나도 남는다');
  // 고정한 동안에는 다른 줄에 마우스를 올려도 바뀌지 않는다
  rowsOf(doc).find((r) => r.textContent.includes('DBMS_KND')).fire('mouseenter', { bubbles: false });
  await sleep(30);
  assert.equal(pinned().querySelector('.edge-pop-kind').textContent, 'WRITES');

  // 닫기 단추
  pinned().querySelector('.edge-pop-close').click();
  assert.equal(doc.querySelector('.edge-pop'), null);
  // 같은 줄을 다시 누르면 닫힌다
  row().click();
  assert.ok(pinned());
  row().click();
  assert.equal(doc.querySelector('.edge-pop'), null);
  // Esc
  row().click();
  doc.dispatchEvent({ type: 'keydown', key: 'Escape' });
  assert.equal(doc.querySelector('.edge-pop'), null);
  // 바깥을 누르면 닫히고, 풍선 안을 누르면 남는다
  row().click();
  doc.dispatchEvent({ type: 'mousedown', target: pinned().querySelector('.edge-pop-kind') });
  assert.ok(pinned(), '풍선 안을 눌러도 남는다');
  doc.dispatchEvent({ type: 'mousedown', target: doc.querySelector('.view-explore .results') });
  assert.equal(doc.querySelector('.edge-pop'), null);

  // 줄 안의 단추(상대 노드 링크, 펼치기)는 제 일을 하고 풍선을 고정하지 않는다
  row().querySelector('.edge-toggle').click();
  assert.equal(doc.querySelector('.edge-pop'), null);
  assert.ok(doc.querySelector('.view-explore .edge-attrs'), '펼치기는 전과 같다');

  // 풍선 안의 노드 링크를 누르면 그 노드로 가고 풍선은 닫힌다
  row().click();
  pinned().querySelectorAll('.edge-pop-main .link').find((l) => l.textContent === 'DbMntrngDao.insertDbMntrngLog').click();
  await sleep(20);
  assert.equal(doc.querySelector('.edge-pop'), null);
  assert.ok(doc.querySelector('.view-explore .detail-head h2').textContent.includes('DbMntrngDao.insertDbMntrngLog'));

  // 다른 화면으로 가면 닫힌다
  rowsOf(doc)[0].click();
  assert.ok(pinned());
  window.CodeAtlas.app.navigate('erd', {});
  assert.equal(doc.querySelector('.edge-pop'), null);
  assert.deepEqual(window.errors, []);
});

test('F-016-AC100 서랍의 Esc: 고정한 풍선이 있으면 풍선만 닫고, 한 번 더 누르면 서랍이 닫힌다', async () => {
  const f = codeJoins();
  const window = await open(f);
  const doc = window.document;
  window.CodeAtlas.app.navigate('erd', {});
  window.CodeAtlas.app.showDetail(f.log);
  await sleep(20);
  const drawer = doc.querySelector('.drawer');
  assert.ok(drawer.classList.contains('open'));
  drawer.querySelectorAll('.edge-row')[0].click();
  assert.ok(doc.querySelector('.edge-pop.pinned'));
  doc.dispatchEvent({ type: 'keydown', key: 'Escape' });
  assert.equal(doc.querySelector('.edge-pop'), null);
  assert.ok(drawer.classList.contains('open'), '서랍은 남는다');
  doc.dispatchEvent({ type: 'keydown', key: 'Escape' });
  assert.ok(!drawer.classList.contains('open'));
  assert.deepEqual(window.errors, []);
});

test('F-016-AC101 탐색 화면의 나눔선: 끌거나 ←·→ 키로 상세 칸의 너비를 바꾸고 두 번 누르면 처음으로 돌아간다', async () => {
  const f = codeJoins();
  const window = await open(f);
  const doc = window.document;
  const view = doc.querySelector('.view-explore');
  // 결과 목록과 상세 사이에 있다
  assert.deepEqual(view.children.map((c) => c.className.split(' ').pop()), ['kinds', 'results', 'splitter', 'detail']);
  const splitter = view.querySelector('.splitter');
  const detail = view.querySelector('.detail');
  assert.equal(splitter.getAttribute('role'), 'separator');
  assert.equal(splitter.getAttribute('aria-orientation'), 'vertical');
  assert.equal(splitter.getAttribute('tabindex'), '0');
  assert.equal(detail.style.flex, undefined, '끌기 전에는 처음 배치(CSS) 그대로다');

  // 가짜 DOM의 칸은 모두 왼쪽 0, 오른쪽 900이다: 상세의 왼쪽 끝(0)을 잡고 끈다
  const drag = (to) => splitter.fire('pointermove', { clientX: to, pointerId: 1 });
  splitter.fire('pointerdown', { clientX: 0, button: 0, pointerId: 1 });
  assert.ok(splitter.classList.contains('dragging') && view.classList.contains('resizing'));
  drag(500);
  assert.equal(detail.style.flex, '0 0 400px', '나눔선의 오른쪽이 상세의 너비다');
  // 상세는 320px, 결과 목록은 280px 아래로 줄지 않는다
  drag(880);
  assert.equal(detail.style.flex, '0 0 320px');
  drag(100);
  assert.equal(detail.style.flex, '0 0 615px', '900 - 결과 목록 280 - 나눔선 5');
  drag(500);
  splitter.fire('pointerup', { clientX: 500, pointerId: 1 });
  assert.ok(!splitter.classList.contains('dragging') && !view.classList.contains('resizing'));
  drag(300);
  assert.equal(detail.style.flex, '0 0 400px', '놓은 뒤에는 따라오지 않는다');

  // 키보드: ←는 나눔선을 왼쪽으로(상세가 넓어진다), →는 오른쪽으로
  const key = (k) => splitter.fire('keydown', { key: k });
  assert.equal(key('ArrowLeft').defaultPrevented, true);
  assert.equal(detail.style.flex, '0 0 424px');
  key('ArrowRight');
  key('ArrowRight');
  assert.equal(detail.style.flex, '0 0 376px');
  assert.equal(key('Enter').defaultPrevented, false, '다른 키는 건드리지 않는다');

  // 노드를 옮겨도 너비는 남고, 두 번 누르면 처음 배치로 돌아간다
  window.CodeAtlas.app.navigate('explore', { id: f.code });
  await sleep(20);
  assert.equal(detail.style.flex, '0 0 376px');
  splitter.fire('dblclick', {});
  assert.equal(detail.style.flex, undefined);
  assert.match(read('css/viewer.css'), /\n\.splitter\s*\{[^}]*cursor:\s*col-resize/);
  assert.deepEqual(window.errors, []);
});
