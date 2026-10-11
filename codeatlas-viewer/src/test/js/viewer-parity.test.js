'use strict';
// 화면: 표준 산출물과 맞춘 규칙·표기(CRUD의 네 기준과 소문자·(동적), 콜트리의 표식, 새 속성의 요약, 진단 화면의 새 보기)가 실제로 그려지는지 본다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assemble } = require('./tools/assemble.js');
const { boot, sleep } = require('./tools/boot.js');
const { slice, loc } = require('./fixtures/slice.js');

function shop() {
  const s = slice();
  const f = {};
  const table = (name, attrs) => s.n('db:table:' + name, 'DB_TABLE', name, name, attrs, loc('db/01_tables.sql', 10));
  f.order = table('TB_ORDER', { comment: '주문', primaryKey: ['ORDER_ID'], indexes: ['IX_ORDER_01(MEMBER_ID,ORDER_DT)', 'IX_ORDER_02(ORDER_DT DESC,ORDER_STATUS)'] });
  f.hist = table('TB_ORDER_HIST', { primaryKey: ['HIST_ID'] });
  f.code = table('TB_CODE', { comment: '공통코드', primaryKey: ['GROUP_CD', 'CODE'] });
  const column = (t, name, attrs) => { const id = s.n('db:column:' + t + '.' + name, 'DB_COLUMN', name, t + '.' + name, attrs); s.e('db:table:' + t, 'CONTAINS', id); return id; };
  column('TB_ORDER', 'ORDER_ID', { dataType: 'NUMBER(12)', ordinal: 1, nullable: false, primaryKey: true });
  f.status = column('TB_ORDER', 'ORDER_STATUS', { dataType: 'VARCHAR2(10)', ordinal: 2, nullable: false });
  column('TB_CODE', 'GROUP_CD', { dataType: 'VARCHAR2(30)', ordinal: 1, nullable: false, primaryKey: true });
  column('TB_CODE', 'CODE', { dataType: 'VARCHAR2(10)', ordinal: 2, nullable: false, primaryKey: true });

  const type = (fqn, attrs) => s.n('java:type:' + fqn, 'JAVA_TYPE', fqn.substring(fqn.lastIndexOf('.') + 1), fqn, attrs);
  const method = (owner, name, attrs, line) => {
    const id = s.n('java:method:' + owner + '#' + name + '()', 'JAVA_METHOD', name, owner + '#' + name + '()', Object.assign({ modifiers: ['public'] }, attrs || {}), loc('src/' + owner + '.java', line || 10));
    s.e('java:type:' + owner, 'CONTAINS', id);
    return id;
  };
  type('w.OrderController', { typeKind: 'class' });
  type('s.OrderService', { typeKind: 'interface' });
  type('s.OrderServiceImpl', { typeKind: 'class' });
  type('m.OrderMapper', { typeKind: 'interface', stereotype: 'mapper' });
  f.ep = s.n('http:endpoint:POST /order/close.do', 'HTTP_ENDPOINT', 'POST /order/close.do', 'POST /order/close.do', { httpMethod: 'POST', path: '/order/close.do' }, loc('src/w.OrderController.java', 76));
  f.epList = s.n('http:endpoint:GET /order/list', 'HTTP_ENDPOINT', 'GET /order/list', 'GET /order/list', { httpMethod: 'GET', path: '/order/list' }, loc('src/w.OrderController.java', 40));
  f.ctl = method('w.OrderController', 'close', { redirects: ['/order/list'] }, 76);
  f.ctlList = method('w.OrderController', 'list', {}, 40);
  f.svc = method('s.OrderService', 'closeOrder', { modifiers: ['public', 'abstract'] });
  f.impl = method('s.OrderServiceImpl', 'closeOrder');
  f.mapClose = method('m.OrderMapper', 'callCloseOrder', { modifiers: ['public', 'abstract'] });
  f.mapSelect = method('m.OrderMapper', 'selectOrderList', { modifiers: ['public', 'abstract'] });
  f.mapLonely = method('m.OrderMapper', 'selectLonely', { modifiers: ['public', 'abstract'] });
  f.mapBackup = method('m.OrderMapper', 'selectBackup', { modifiers: ['public', 'abstract'] });
  s.e(f.ep, 'ROUTES_TO', f.ctl);
  s.e(f.epList, 'ROUTES_TO', f.ctlList);
  s.e(f.ctl, 'CALLS', f.svc, { callType: 'interface' }, 'RESOLVED', loc('src/w.OrderController.java', 78));
  s.e(f.ctl, 'CALLS', f.ctlList, { callType: 'redirect', path: '/order/list' }, 'INFERRED', loc('src/w.OrderController.java', 76));
  s.e(f.impl, 'OVERRIDES', f.svc);
  s.e(f.impl, 'CALLS', f.mapClose, { callType: 'interface' }, 'RESOLVED', loc('src/s.OrderServiceImpl.java', 12));
  s.e(f.impl, 'CALLS', f.mapBackup, { callType: 'interface' }, 'RESOLVED', loc('src/s.OrderServiceImpl.java', 13));
  f.unresolved = s.n('unresolved:java-call:audit.log(…)', 'UNRESOLVED', 'audit.log(…)', 'audit.log(…)', { category: 'java-call' });
  s.e(f.impl, 'CALLS', f.unresolved, { argCount: 2 }, 'UNRESOLVED', loc('src/s.OrderServiceImpl.java', 14));
  s.e(f.ctlList, 'CALLS', f.mapSelect, { callType: 'interface' }, 'RESOLVED', loc('src/w.OrderController.java', 42));

  const mapperStmt = (name, type, line) => s.n('mybatis:stmt:m.OrderMapper.' + name, 'SQL_STATEMENT', name, 'm.OrderMapper.' + name,
    { statementType: type, namespace: 'm.OrderMapper', statementId: name }, loc('mapper/OrderMapper.xml', line));
  f.callClose = mapperStmt('callCloseOrder', 'CALL', 197);
  f.selectList = mapperStmt('selectOrderList', 'SELECT', 67);
  f.lonely = mapperStmt('selectLonely', 'SELECT', 46);
  f.backup = mapperStmt('selectBackup', 'SELECT', 38);
  s.e(f.mapClose, 'EXECUTES', f.callClose);
  s.e(f.mapSelect, 'EXECUTES', f.selectList);
  s.e(f.mapLonely, 'EXECUTES', f.lonely);
  s.e(f.mapBackup, 'EXECUTES', f.backup);
  s.e(f.selectList, 'READS', f.order);
  s.e(f.selectList, 'READS', f.code);
  s.e(f.lonely, 'READS', f.order);
  const dyn = s.n('unresolved:sql-dynamic:' + f.backup, 'UNRESOLVED', '__DYNAMIC__', f.backup, { category: 'sql-dynamic' });
  s.e(f.backup, 'READS', dyn, { text: '__DYNAMIC__' }, 'UNRESOLVED', loc('mapper/OrderMapper.xml', 39));

  f.routine = s.n('db:routine:PKG_ORDER.CLOSE_ORDER', 'DB_ROUTINE', 'CLOSE_ORDER', 'PKG_ORDER.CLOSE_ORDER',
    { routineType: 'PROCEDURE', parameters: ['P_ORDER_ID IN NUMBER'], typeRefs: ['TB_ORDER.ORDER_STATUS'] }, loc('db/pkg_order.pkb', 39));
  s.e(f.callClose, 'CALLS', f.routine);
  f.update = s.n('sql:stmt:PKG_ORDER.CLOSE_ORDER:60', 'SQL_STATEMENT', 'PKG_ORDER.CLOSE_ORDER:60', 'PKG_ORDER.CLOSE_ORDER:60', { statementType: 'UPDATE' }, loc('db/pkg_order.pkb', 60));
  f.read = s.n('sql:stmt:PKG_ORDER.CLOSE_ORDER:47', 'SQL_STATEMENT', 'PKG_ORDER.CLOSE_ORDER:47', 'PKG_ORDER.CLOSE_ORDER:47', { statementType: 'SELECT' }, loc('db/pkg_order.pkb', 47));
  s.e(f.routine, 'CONTAINS', f.update);
  s.e(f.routine, 'CONTAINS', f.read);
  s.e(f.update, 'WRITES', f.order, { op: 'UPDATE' });
  s.e(f.read, 'READS', f.order);
  // 컬럼 지정 트리거: 구문이 쓰는 컬럼을 모른다 → [trigger?]
  f.trigger = s.n('db:routine:TRG_ORDER_STATUS_HIST', 'DB_ROUTINE', 'TRG_ORDER_STATUS_HIST', 'TRG_ORDER_STATUS_HIST',
    { routineType: 'TRIGGER', triggerEvent: 'AFTER UPDATE OF ORDER_STATUS' }, loc('db/trg_order.trg', 2));
  s.e(f.trigger, 'TRIGGERS_ON', f.order);
  f.histInsert = s.n('sql:stmt:TRG_ORDER_STATUS_HIST:7', 'SQL_STATEMENT', 'TRG_ORDER_STATUS_HIST:7', 'TRG_ORDER_STATUS_HIST:7', { statementType: 'INSERT' }, loc('db/trg_order.trg', 7));
  s.e(f.trigger, 'CONTAINS', f.histInsert);
  s.e(f.histInsert, 'WRITES', f.hist, { op: 'INSERT' });

  s.e(f.order, 'REFERENCES', f.code, { cardinality: 'N:1', columns: ['ORDER_STATUS'], directionKnown: true, evidence: [f.selectList], evidenceCount: 1,
    fixedColumns: ['GROUP_CD'], fixedValues: ['GROUP_CD=ORDER_STATUS'], refColumns: ['CODE'], source: 'INFERRED' }, 'INFERRED');
  s.e(f.code, 'JOINS', f.order, { leftColumns: ['CODE'], leftFixedColumns: ['GROUP_CD'], leftFixedValues: ['GROUP_CD=ORDER_STATUS'], rightColumns: ['ORDER_STATUS'], statement: f.selectList });
  s.d('INFO', 'CALLS_SKIPPED', '수신자를 알 수 없는 호출 1건을 기록하지 않았다(매개변수·props로 받은 함수: 직접 호출 0건, 다른 호출에 넘김 1건; 줄 37): js/common.js',
    { file: 'js/common.js', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  s.d('WARN', 'DYNAMIC_SQL', '테이블 이름이 동적(${})이라 대상을 알 수 없음: __DYNAMIC__', loc('mapper/OrderMapper.xml', 39));
  f.graph = s.graph();
  return f;
}

const texts = (els) => els.map((e) => e.textContent);

test('F-016-AC28 F-016-AC29 화면: CRUD는 네 기준을 고르고, 소문자(간접)와 (동적) 열, 범례, 동적 구문 목록을 보인다', async () => {
  const f = shop();
  const window = boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  window.CodeAtlas.app.navigate('crud', {});
  await sleep(100);
  const kinds = doc.querySelectorAll('.view-crud .crud-kind button');
  assert.deepEqual(kinds.map((b) => b.getAttribute('data-value')), ['statement', 'method', 'endpoint', 'routine']);
  const cellsOf = () => texts(doc.querySelectorAll('.view-crud .crud-cell')).filter(Boolean);
  const labels = () => texts(doc.querySelectorAll('.view-crud .crud-label'));
  // 구문 기준: CALL 구문의 행(ru), (동적) 열
  assert.ok(labels().some((l) => l.includes('callCloseOrder')), 'CALL 구문의 행');
  assert.ok(cellsOf().includes('ru'), cellsOf().join(' '));
  assert.ok(doc.querySelectorAll('.view-crud .crud-cell.indirect').length >= 1, '간접 접근만 있는 칸은 따로 칠한다');
  const heads = texts(doc.querySelectorAll('.view-crud .crud-th'));
  assert.equal(heads[heads.length - 1], '(동적)');
  assert.ok(doc.querySelector('.view-crud .crud-th.dynamic'));
  const legend = doc.querySelector('.view-crud .crud-legend').textContent;
  assert.ok(legend.includes('대문자') && legend.includes('소문자') && legend.includes('(동적)'), legend);
  const dynamic = doc.querySelector('.view-crud .crud-dynamic');
  assert.ok(dynamic.textContent.includes('대상 테이블을 정적으로 알 수 없는 구문 (1)'));
  assert.ok(dynamic.textContent.includes('m.OrderMapper.selectBackup') && dynamic.textContent.includes('mapper/OrderMapper.xml:38'));

  // 엔드포인트 기준: 루틴 안의 조회·갱신은 ru, 트리거를 거친 TB_ORDER_HIST는 c(일어나는지 알 수 없는 트리거 [trigger?]라 끝에 ?가 붙는다, F-016-AC66).
  // redirect로 넘어가는 조회(R)는 별개의 요청이라 합치지 않는다(F-015-R42)
  doc.querySelector('.view-crud .crud-kind button[data-value="endpoint"]').click();
  await sleep(50);
  assert.deepEqual(labels().sort(), ['GET /order/list', 'POST /order/close.do']);
  assert.ok(cellsOf().includes('ru') && cellsOf().includes('c?') && !cellsOf().includes('Ru'), cellsOf().join(' '));
  assert.equal(doc.querySelector('.view-crud .crud-dynamic').style.display, 'none', '동적 구문 목록은 구문 기준에만 나온다');
  assert.ok(doc.querySelector('.view-crud .crud-th.dynamic'), '(동적) 열은 그 구문에 닿는 엔드포인트가 있으면 나온다');

  // 루틴 기준
  doc.querySelector('.view-crud .crud-kind button[data-value="routine"]').click();
  await sleep(50);
  assert.deepEqual(labels().sort(), ['PKG_ORDER.CLOSE_ORDER', 'TRG_ORDER_STATUS_HIST']);
  assert.ok(cellsOf().includes('RU'));

  // 메서드 기준 + CSV 저장: 표준 산출물과 같은 모양
  doc.querySelector('.view-crud .crud-kind button[data-value="method"]').click();
  await sleep(50);
  let saved = null;
  window.Blob = class { constructor(parts) { saved = parts.join(''); } };
  doc.querySelector('.view-crud .crud-csv').click();
  assert.ok(saved.startsWith('﻿메서드,위치,TB_CODE,TB_ORDER,(동적)\n'), saved);
  assert.ok(saved.includes('\nm.OrderMapper#callCloseOrder(),src/m.OrderMapper.java:10,,ru,\n'), saved);
  assert.deepEqual(window.errors, []);
});

test('F-016-AC31 F-016-AC35 화면: 콜트리 줄에 [trigger?], 인터페이스 경유, super·redirect 표시가 붙는다', async () => {
  const f = shop();
  const window = boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('calltree', { id: f.ep });
  await sleep(20);
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  const rows = doc.querySelectorAll('.view-calltree .tree-callees .trow');
  const row = (text) => rows.find((r) => r.textContent.includes(text));
  assert.ok(row('TRG_ORDER_STATUS_HIST').querySelector('.tag.trigger-uncertain'), '컬럼 지정 트리거인데 쓰는 컬럼을 모른다');
  assert.ok(row('TRG_ORDER_STATUS_HIST').querySelector('.tag.trigger-uncertain').getAttribute('title').includes('알 수 없'));
  assert.ok(row('OrderController#list').textContent.includes('CALLS(redirect)'));
  assert.ok(row('OrderServiceImpl#closeOrder').textContent.includes('구현'));
  // 위 방향: 구현 메서드의 호출자로 인터페이스를 부른 컨트롤러가 "인터페이스 경유"로 나온다
  app.navigate('calltree', { id: f.impl });
  await sleep(20);
  const up = doc.querySelectorAll('.view-calltree .tree-callers .trow');
  const viaRow = up.find((r) => r.textContent.includes('OrderController#close'));
  assert.ok(viaRow, texts(up).join('\n'));
  const tag = viaRow.querySelector('.tag.via-interface');
  assert.ok(tag && tag.textContent.includes('인터페이스 경유'));
  assert.ok(tag.getAttribute('title').includes('OrderService#closeOrder'), '거친 상위 메서드를 알려 준다');
  assert.ok(!up.some((r) => r.textContent.includes('재정의 대상')));
  assert.deepEqual(window.errors, []);
});

test('F-016-AC36 F-016-AC38 화면: 상세의 엣지 줄에 읽기 좋은 요약이 나오고 indexes는 한 줄에 하나씩, typeRefs·redirects는 눌러서 간다', async () => {
  const f = shop();
  const window = boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('explore', { id: f.order });
  await sleep(20);
  const body = doc.querySelector('.view-explore .detail-body');
  const summaries = texts(body.querySelectorAll('.edge-row .edge-summary'));
  assert.ok(summaries.includes("ORDER_STATUS → CODE (GROUP_CD='ORDER_STATUS') · N:1 · 추론 1건"), summaries.join('\n'));
  assert.ok(summaries.includes("TB_CODE.CODE = TB_ORDER.ORDER_STATUS · TB_CODE 고정 GROUP_CD='ORDER_STATUS'"), summaries.join('\n'));
  assert.ok(doc.querySelector('.view-explore .detail-head .summary').textContent.includes('인덱스 2개'));
  assert.deepEqual(texts(body.querySelectorAll('.kv .multi .item')), ['IX_ORDER_01(MEMBER_ID,ORDER_DT)', 'IX_ORDER_02(ORDER_DT DESC,ORDER_STATUS)']);

  app.navigate('explore', { id: f.routine });
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .detail-head .summary').textContent.includes('%TYPE 참조 TB_ORDER.ORDER_STATUS'));
  doc.querySelectorAll('.view-explore .kv .link').find((l) => l.textContent === 'TB_ORDER.ORDER_STATUS').click();
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .detail-head .id').textContent.includes(f.status));

  app.navigate('explore', { id: f.ctl });
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .detail-head .summary').textContent.includes('redirect → /order/list'));
  const edgeSummaries = texts(doc.querySelectorAll('.view-explore .edge-row .edge-summary'));
  assert.ok(edgeSummaries.includes('redirect /order/list · 메서드가 넘긴 경로(redirects)'), edgeSummaries.join('\n'));
  doc.querySelectorAll('.view-explore .kv .link').find((l) => l.textContent === '/order/list').click();
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .detail-head .id').textContent.includes(f.epList));

  app.navigate('explore', { id: f.impl });
  await sleep(20);
  assert.ok(texts(doc.querySelectorAll('.view-explore .edge-row .edge-summary')).includes('미해결 호출 · 인자 2개'));

  // ERD 라벨
  app.navigate('erd', {});
  await sleep(350);
  assert.ok(doc.querySelector('.view-erd .dg-source').textContent.includes("ORDER_STATUS → CODE (GROUP_CD='ORDER_STATUS', 추론 1건)"));
  assert.deepEqual(window.errors, []);
});

test('F-016-AC39 화면: 진단·미해결에 "Java 호출자 없는 구문"과 "동적 구문" 보기가 있고, 새 진단 코드가 필터에 나온다', async () => {
  const f = shop();
  const window = boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  window.CodeAtlas.app.navigate('diagnostics', {});
  await sleep(50);
  const modes = doc.querySelectorAll('.view-diagnostics .diag-mode button');
  assert.deepEqual(modes.map((b) => b.getAttribute('data-value')), ['unresolved', 'diagnostics', 'uncalled', 'dynamic', 'undeclared', 'dataload', 'columns', 'requests', 'borrowed']);
  assert.deepEqual(texts(modes), ['미해결 참조 2', '진단 2', 'Java 호출자 없는 구문 1', '동적 구문 1', 'DDL에 없는 테이블 0', '데이터 적재 스크립트 0', '컬럼 불일치 0', '화면 요청 0', '보충한 구문 0']);
  // 미해결 java-call: 원문 옆에 인자 수
  const call = doc.querySelectorAll('.view-diagnostics .drow').find((r) => r.textContent.includes('audit.log(…)'));
  assert.ok(call.textContent.includes('인자 2개'), call.textContent);

  doc.querySelector('.view-diagnostics .diag-mode button[data-value="uncalled"]').click();
  await sleep(50);
  let rows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.equal(rows.length, 1);
  assert.ok(rows[0].textContent.includes('m.OrderMapper.selectLonely') && rows[0].textContent.includes('m.OrderMapper#selectLonely()')
    && rows[0].textContent.includes('mapper/OrderMapper.xml:46'), rows[0].textContent);
  assert.ok(doc.querySelector('.view-diagnostics .diag-filters').textContent.includes('부르는'), '무엇을 모은 목록인지 설명한다');

  doc.querySelector('.view-diagnostics .diag-mode button[data-value="dynamic"]').click();
  await sleep(50);
  rows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.equal(rows.length, 1);
  assert.ok(rows[0].textContent.includes('m.OrderMapper.selectBackup') && rows[0].textContent.includes('__DYNAMIC__') && rows[0].textContent.includes('mapper/OrderMapper.xml:38'));
  rows[0].click();
  await sleep(20);
  assert.ok(doc.querySelector('.drawer').textContent.includes(f.backup), '누르면 구문 상세가 열린다');

  doc.querySelector('.view-diagnostics .diag-mode button[data-value="diagnostics"]').click();
  await sleep(50);
  const chip = doc.querySelectorAll('.view-diagnostics .code-chip').find((c) => c.textContent.includes('CALLS_SKIPPED'));
  assert.ok(chip, '새 진단 코드');
  chip.click();
  await sleep(50);
  rows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.equal(rows.length, 1);
  assert.ok(rows[0].textContent.includes('INFO') && rows[0].textContent.includes('js/common.js'));
  assert.deepEqual(window.errors, []);
});
