'use strict';
// 콜트리 전개 규칙을 표준 산출물(codeatlas-export의 FlowNavigator, F-015-R18·R19·R23)과 같게 맞춘 것을 본다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { slice, loc, paths } = require('./fixtures/slice.js');

const graph = core('graph');
const calltree = core('calltree');

// ---------------------------------------------------------------- 트리거 (F-015-R18)

function triggers() {
  const s = slice();
  const stmt = (name, type) => s.n('mybatis:stmt:m.' + name, 'SQL_STATEMENT', name, 'm.' + name, { statementType: type });
  const f = { order: s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER'), hist: s.n('db:table:TB_ORDER_HIST', 'DB_TABLE', 'TB_ORDER_HIST') };
  const col = (name) => {
    const id = s.n('db:column:TB_ORDER.' + name, 'DB_COLUMN', name, 'TB_ORDER.' + name);
    s.e(f.order, 'CONTAINS', id);
    return id;
  };
  f.status = col('ORDER_STATUS');
  f.memo = col('MEMO');
  f.trgStatus = s.n('db:routine:TRG_STATUS', 'DB_ROUTINE', 'TRG_STATUS', 'TRG_STATUS', { routineType: 'TRIGGER', triggerEvent: 'AFTER UPDATE OF ORDER_STATUS' });
  f.trgInsDel = s.n('db:routine:TRG_INS_DEL', 'DB_ROUTINE', 'TRG_INS_DEL', 'TRG_INS_DEL', { routineType: 'TRIGGER', triggerEvent: 'BEFORE INSERT OR DELETE' });
  f.trgAny = s.n('db:routine:TRG_ANY', 'DB_ROUTINE', 'TRG_ANY', 'TRG_ANY', { routineType: 'TRIGGER' });
  [f.trgStatus, f.trgInsDel, f.trgAny].forEach((t) => s.e(t, 'TRIGGERS_ON', f.order));
  f.ins = stmt('insertOrder', 'INSERT');
  s.e(f.ins, 'WRITES', f.order, { op: 'INSERT' });
  s.e(f.ins, 'WRITES', f.status, { op: 'INSERT' });
  f.updStatus = stmt('updateStatus', 'UPDATE');
  s.e(f.updStatus, 'WRITES', f.order, { op: 'UPDATE' });
  s.e(f.updStatus, 'WRITES', f.status, { op: 'UPDATE' });
  f.updMemo = stmt('updateMemo', 'UPDATE');
  s.e(f.updMemo, 'WRITES', f.order, { op: 'UPDATE' });
  s.e(f.updMemo, 'WRITES', f.memo, { op: 'UPDATE' });
  f.updBlind = stmt('updateBlind', 'UPDATE'); // 어느 컬럼을 쓰는지 모른다
  s.e(f.updBlind, 'WRITES', f.order, { op: 'UPDATE' });
  f.del = stmt('deleteOrder', 'DELETE');
  s.e(f.del, 'WRITES', f.order, { op: 'DELETE' });
  f.merge = stmt('mergeOrder', 'MERGE');
  s.e(f.merge, 'WRITES', f.order, { op: 'MERGE' });
  s.e(f.merge, 'WRITES', f.status, { op: 'MERGE' });
  f.trunc = stmt('truncateOrder', 'OTHER');
  s.e(f.trunc, 'WRITES', f.order, { op: 'DELETE', truncate: true });
  f.sel = stmt('selectOrder', 'SELECT');
  s.e(f.sel, 'READS', f.order);
  f.other = stmt('writeOther', 'OTHER'); // 쓰기의 종류를 모른다
  s.e(f.other, 'WRITES', f.order);
  f.histIns = s.n('sql:stmt:TRG_STATUS:7', 'SQL_STATEMENT', 'TRG_STATUS:7', 'TRG_STATUS:7', { statementType: 'INSERT' });
  s.e(f.trgStatus, 'CONTAINS', f.histIns);
  s.e(f.histIns, 'WRITES', f.hist, { op: 'INSERT' });
  f.graph = s.graph();
  return f;
}

test('F-016-AC31 콜트리 트리거: UPDATE OF <컬럼> 트리거는 그 컬럼을 쓰는 구문에서만, 알 수 없으면 [trigger?]로 내려간다', () => {
  const f = triggers();
  const ix = graph.buildIndex(f.graph);
  const below = (stmtId) => {
    const root = calltree.createRoot(ix, stmtId, 'callees');
    const table = calltree.expand(ix, root).find((c) => c.id === f.order);
    return calltree.expand(ix, table).map((c) => c.id + (c.link.triggerUncertain ? ' ?' : '')).sort();
  };
  assert.deepEqual(below(f.ins), [f.trgAny + ' ?', f.trgInsDel].sort(), 'INSERT: INSERT 트리거와 사건 미상 트리거만');
  assert.deepEqual(below(f.updStatus), [f.trgAny + ' ?', f.trgStatus].sort(), '그 컬럼을 쓰는 UPDATE');
  assert.deepEqual(below(f.updMemo), [f.trgAny + ' ?'], '다른 컬럼만 쓰는 UPDATE 아래에는 컬럼 지정 트리거가 없다');
  assert.deepEqual(below(f.updBlind), [f.trgAny + ' ?', f.trgStatus + ' ?'].sort(), '컬럼 엣지가 없는 UPDATE: 알 수 없다');
  assert.deepEqual(below(f.del), [f.trgAny + ' ?', f.trgInsDel].sort());
  assert.deepEqual(below(f.merge), [f.trgAny + ' ?', f.trgInsDel, f.trgStatus].sort(), 'MERGE는 INSERT와 UPDATE 둘 다');
  assert.deepEqual(below(f.trunc), [], 'TRUNCATE는 DML 트리거를 일으키지 않는다');
  assert.deepEqual(below(f.sel), [], '읽기는 트리거를 일으키지 않는다');
  assert.deepEqual(below(f.other), [f.trgAny, f.trgInsDel, f.trgStatus].map((t) => t + ' ?').sort(), '쓰기의 종류를 모르면 모두 불확실');
  // 테이블에서 시작하면 걸린 트리거가 표시 없이 모두 나온다
  const fromTable = calltree.expand(ix, calltree.createRoot(ix, f.order, 'callees'));
  assert.deepEqual(fromTable.map((c) => c.id + (c.link.triggerUncertain ? ' ?' : '')).sort(), [f.trgAny, f.trgInsDel, f.trgStatus].sort());
  // 글로 내보낼 때도 표시한다
  const root = calltree.createRoot(ix, f.updBlind, 'callees');
  calltree.expandAll(ix, root, {});
  assert.ok(calltree.toText(ix, root).split('\n').some((l) => l.includes('TRG_STATUS') && l.includes('[trigger?]')));
});

test('F-016-AC31 콜트리 트리거: 위로는 그 트리거를 일으키는 구문만, 불확실한 것은 구문 줄에 표시한다', () => {
  const f = triggers();
  const ix = graph.buildIndex(f.graph);
  const up = calltree.createRoot(ix, f.trgStatus, 'callers');
  const table = calltree.expand(ix, up).find((c) => c.id === f.order);
  assert.equal(table.link.kind, 'TRIGGERS_ON');
  assert.deepEqual(calltree.expand(ix, table).map((c) => c.id + (c.link.triggerUncertain ? ' ?' : '')).sort(),
    [f.merge, f.other + ' ?', f.updBlind + ' ?', f.updStatus].sort());
  const any = calltree.createRoot(ix, f.trgAny, 'callers');
  const anyTable = calltree.expand(ix, any).find((c) => c.id === f.order);
  assert.deepEqual(calltree.expand(ix, anyTable).map((c) => c.id).sort(), [f.del, f.ins, f.merge, f.other, f.updBlind, f.updMemo, f.updStatus].sort(),
    '사건을 모르는 트리거: 그 테이블을 쓰는 구문 모두(TRUNCATE와 읽기는 빼고)');
  assert.ok(calltree.expand(ix, anyTable).every((c) => c.link.triggerUncertain));
});

test('F-016-AC31 트리거 사건 해석: 시점을 떼고 OR로 이은 사건과 UPDATE OF 뒤의 컬럼 목록을 읽는다', () => {
  const p = calltree.parseTriggerEvent;
  assert.deepEqual(p('AFTER UPDATE OF ORDER_STATUS'), [{ event: 'UPDATE', columns: ['ORDER_STATUS'] }]);
  assert.deepEqual(p('BEFORE INSERT OR DELETE'), [{ event: 'INSERT', columns: [] }, { event: 'DELETE', columns: [] }]);
  assert.deepEqual(p('NO CASCADE BEFORE UPDATE OF a, "Mixed" OR INSERT ON T'), [{ event: 'UPDATE', columns: ['A', 'Mixed'] }, { event: 'INSERT', columns: [] }]);
  assert.deepEqual(p('INSTEAD OF delete'), [{ event: 'DELETE', columns: [] }]);
  assert.deepEqual(p('AFTER LOGON'), []);
  assert.deepEqual(p(null), []);
});

// ---------------------------------------------------------------- 구현 전개와 주입 좁히기 (F-015-R3, R19)

function dispatch() {
  const s = slice();
  const P = 'com.example.shop.payment.';
  const type = (fqn, attrs) => s.n('java:type:' + fqn, 'JAVA_TYPE', fqn.substring(fqn.lastIndexOf('.') + 1), fqn, attrs);
  const method = (owner, name, attrs) => {
    const id = s.n('java:method:' + owner + '#' + name + '()', 'JAVA_METHOD', name, owner + '#' + name + '()', attrs);
    s.e('java:type:' + owner, 'CONTAINS', id);
    return id;
  };
  const f = {
    policy: type(P + 'DiscountPolicy', { typeKind: 'interface' }),
    base: type(P + 'AbstractDiscountPolicy', { typeKind: 'class', modifiers: ['abstract'] }),
    def: type(P + 'DefaultDiscountPolicy', { typeKind: 'class' }),
    vip: type(P + 'VipDiscountPolicy', { typeKind: 'class' }),
    gold: type(P + 'GoldDiscountPolicy', { typeKind: 'class' })
  };
  ['order', 'member', 'pay', 'report', 'batch', 'bean', 'two', 'name'].forEach((k) => { f[k] = type('com.example.shop.caller.' + k + '.Caller', { typeKind: 'class' }); });
  f.policyDiscount = method(P + 'DiscountPolicy', 'discount', { modifiers: ['public', 'abstract'] });
  f.baseDiscount = method(P + 'AbstractDiscountPolicy', 'discount', { modifiers: ['public', 'abstract'] }); // 본문이 없다: 거쳐 가기만 한다
  f.defDiscount = method(P + 'DefaultDiscountPolicy', 'discount', { modifiers: ['public'] });
  f.vipDiscount = method(P + 'VipDiscountPolicy', 'discount', { modifiers: ['public'] });
  f.defRound = method(P + 'DefaultDiscountPolicy', 'round', { modifiers: ['protected'] });
  f.vipRound = method(P + 'VipDiscountPolicy', 'round', { modifiers: ['protected'] });
  s.e(f.baseDiscount, 'OVERRIDES', f.policyDiscount);
  s.e(f.defDiscount, 'OVERRIDES', f.baseDiscount);
  s.e(f.vipDiscount, 'OVERRIDES', f.defDiscount);
  s.e(f.vipRound, 'OVERRIDES', f.defRound);
  s.e(f.base, 'IMPLEMENTS', f.policy);
  s.e(f.def, 'EXTENDS', f.base);
  s.e(f.vip, 'EXTENDS', f.def);
  s.e(f.gold, 'EXTENDS', f.vip);
  s.e(f.vipDiscount, 'CALLS', f.defDiscount, { callType: 'super' }, 'RESOLVED', loc('Vip.java', 17));
  s.e(f.defDiscount, 'CALLS', f.defRound, { callType: 'virtual' }, 'RESOLVED', loc('Default.java', 20));
  f.callers = {};
  ['order', 'member', 'pay', 'report', 'batch', 'bean', 'two', 'name'].forEach((k) => {
    f.callers[k] = method('com.example.shop.caller.' + k + '.Caller', 'run', { modifiers: ['public'] });
    s.e(f.callers[k], 'CALLS', f.policyDiscount, { callType: 'interface' }, 'RESOLVED', loc(k + '/Caller.java', 10));
  });
  s.e(f.order, 'INJECTS', f.policy, { candidates: [f.vip] });
  s.e(f.member, 'INJECTS', f.policy, { candidates: [f.def] });
  s.e(f.pay, 'INJECTS', f.def, { resolvedFrom: f.policy }, 'INFERRED');
  s.e(f.batch, 'INJECTS', f.policy, { candidates: [f.gold] });
  // 빈 ID·빈 이름으로 적힌 후보
  const vipBean = s.n('spring:bean:vipDiscountPolicy', 'SPRING_BEAN', 'vipDiscountPolicy');
  s.e(f.vip, 'DEFINES_BEAN', vipBean);
  s.e(f.bean, 'INJECTS', f.policy, { candidates: ['vipDiscountPolicy'] });
  // resolvedFrom이 붙은 추론 주입이 둘이면 어느 쪽인지 모른다: 좁히지 않는다
  s.e(f.two, 'INJECTS', f.def, { resolvedFrom: f.policy }, 'INFERRED');
  s.e(f.two, 'INJECTS', f.vip, { resolvedFrom: f.policy }, 'INFERRED');
  // resolvedFrom이 ID가 아니라 바이너리 이름
  s.e(f.name, 'INJECTS', f.vip, { resolvedFrom: P + 'DiscountPolicy' }, 'INFERRED');
  f.graph = s.graph();
  return f;
}

test('F-016-AC32 콜트리 구현 전개: 인터페이스·추상 메서드만 구현으로 펼치고, 본문 없는 중간 메서드는 거쳐 가기만 한다', () => {
  const f = dispatch();
  const ix = graph.buildIndex(f.graph);
  const impls = calltree.childLinks(ix, f.policyDiscount, 'callees');
  assert.deepEqual(impls.map((l) => l.nodeId).sort(), [f.defDiscount, f.vipDiscount].sort(), '추상 클래스의 추상 메서드는 구현으로 나오지 않는다');
  assert.ok(impls.every((l) => l.kind === 'OVERRIDES' && l.impl && l.reversed && l.label === '구현'));
  assert.deepEqual(calltree.childLinks(ix, f.baseDiscount, 'callees').map((l) => l.nodeId).sort(), [f.defDiscount, f.vipDiscount].sort(), '추상 메서드가 기준일 때도');
  // 본문이 있는 클래스 메서드는 디스패치 대상이 아니다(표준 산출물 F-015-R3): 하위 재정의로 펼치지 않는다
  assert.deepEqual(calltree.childLinks(ix, f.defDiscount, 'callees').map((l) => l.label + ' ' + l.nodeId), ['CALLS ' + f.defRound]);
  assert.deepEqual(calltree.childLinks(ix, f.defRound, 'callees'), []);
  // super 호출은 관계 이름에 적는다
  const vip = calltree.childLinks(ix, f.vipDiscount, 'callees');
  assert.deepEqual(vip.map((l) => l.label + ' ' + l.nodeId), ['CALLS(super) ' + f.defDiscount]);
});

test('F-016-AC32 콜트리 주입 좁히기: 후보는 타입 ID·빈 ID·빈 이름, resolvedFrom은 ID나 바이너리 이름, 추론 주입은 하나일 때만', () => {
  const f = dispatch();
  const ix = graph.buildIndex(f.graph);
  const implsFrom = (key, opts) => {
    const root = calltree.createRoot(ix, f.callers[key], 'callees', opts);
    const iface = calltree.expand(ix, root).find((c) => c.id === f.policyDiscount);
    return calltree.expand(ix, iface).map((c) => c.link.label + ' ' + c.id).sort();
  };
  const all = ['구현 ' + f.defDiscount, '구현 ' + f.vipDiscount].sort();
  assert.deepEqual(implsFrom('order'), ['구현(주입) ' + f.vipDiscount]);
  assert.deepEqual(implsFrom('member'), ['구현(주입) ' + f.defDiscount]);
  assert.deepEqual(implsFrom('pay'), ['구현(주입) ' + f.defDiscount]);
  assert.deepEqual(implsFrom('batch'), ['구현(주입) ' + f.vipDiscount], '재정의가 없는 후보 타입은 가장 가까운 상위 클래스의 구현');
  assert.deepEqual(implsFrom('bean'), ['구현(주입) ' + f.vipDiscount], '후보가 빈 이름이면 DEFINES_BEAN으로 타입을 찾는다');
  assert.deepEqual(implsFrom('name'), ['구현(주입) ' + f.vipDiscount]);
  assert.deepEqual(implsFrom('two'), all, '추론 주입이 둘이면 좁히지 않는다');
  assert.deepEqual(implsFrom('report'), all);
  assert.deepEqual(implsFrom('order', { allImplementations: true }), all);
});

test('F-016-AC35 콜트리 위 방향: 재정의한 상위 메서드의 호출자를 "인터페이스 경유"로 넣되, 다른 구현을 주입받는 호출자와 super 호출은 뺀다', () => {
  const f = dispatch();
  const ix = graph.buildIndex(f.graph);
  const callersOf = (id, opts) => calltree.childLinks(ix, id, 'callers', opts).map((l) => (l.viaInterface ? 'via ' : '') + l.label + ' ' + l.nodeId).sort();
  const c = f.callers;
  assert.deepEqual(callersOf(f.vipDiscount), ['via CALLS ' + c.order, 'via CALLS ' + c.batch, 'via CALLS ' + c.bean, 'via CALLS ' + c.name, 'via CALLS ' + c.two, 'via CALLS ' + c.report].sort(),
    'Default를 주입받는 member·pay는 빠지고, 자기 자신의 super 호출은 호출자가 아니다');
  assert.deepEqual(callersOf(f.defDiscount), ['CALLS(super) ' + f.vipDiscount, 'via CALLS ' + c.member, 'via CALLS ' + c.pay, 'via CALLS ' + c.two, 'via CALLS ' + c.report].sort(),
    'Vip을 주입받는 호출자는 Vip을 거쳐서 닿는다');
  assert.deepEqual(callersOf(f.vipDiscount, { allImplementations: true }).length, 8, '좁히기를 끄면 인터페이스의 호출자 전부');
  // 경유한 상위 메서드를 남긴다(화면에서 어느 메서드를 거쳤는지 보인다)
  const link = calltree.childLinks(ix, f.vipDiscount, 'callers').find((l) => l.nodeId === c.order);
  assert.equal(link.viaMethod, f.policyDiscount);
  assert.equal(link.kind, 'CALLS');
  // 트리로: 구현 → (인터페이스 경유) 호출자
  const up = calltree.createRoot(ix, f.defRound, 'callers');
  calltree.expandAll(ix, up, {});
  assert.ok(paths(up).some((p) => p.join('>') === [f.defRound, f.defDiscount, c.member].join('>')));
  assert.ok(calltree.toText(ix, up).includes('[via interface]'));
});

// ---------------------------------------------------------------- 배치 (F-015-R23, 스텝의 타입·빈)

test('F-016-AC33 콜트리 배치: 스텝이 타입이나 빈만 가리키면 그 타입의 public 메서드로 펼친다', () => {
  const s = slice();
  const step = s.n('batch:step:job/step', 'BATCH_STEP', 'step', 'job/step');
  const type = s.n('java:type:b.Writer', 'JAVA_TYPE', 'Writer', 'b.Writer', { typeKind: 'class' });
  const write = s.n('java:method:b.Writer#write()', 'JAVA_METHOD', 'write', 'b.Writer#write()', { modifiers: ['public'] });
  const helper = s.n('java:method:b.Writer#helper()', 'JAVA_METHOD', 'helper', 'b.Writer#helper()', { modifiers: ['private'] });
  const ctor = s.n('java:method:b.Writer#<init>()', 'JAVA_METHOD', '<init>', 'b.Writer#<init>()', { modifiers: ['public'], constructor: true });
  [write, helper, ctor].forEach((m) => s.e(type, 'CONTAINS', m));
  const bean = s.n('spring:bean:readerBean', 'SPRING_BEAN', 'readerBean');
  const readerType = s.n('java:type:b.Reader', 'JAVA_TYPE', 'Reader', 'b.Reader', { typeKind: 'class' });
  const read = s.n('java:method:b.Reader#read()', 'JAVA_METHOD', 'read', 'b.Reader#read()', {});
  s.e(readerType, 'CONTAINS', read);
  s.e(readerType, 'DEFINES_BEAN', bean);
  s.e(step, 'USES', type, { role: 'writer' });
  s.e(step, 'USES', bean, { role: 'reader' });
  // 역할 메서드를 직접 USES하는 타입·빈은 접는다
  const covered = s.n('java:type:b.Processor', 'JAVA_TYPE', 'Processor', 'b.Processor', { typeKind: 'class' });
  const coveredBean = s.n('spring:bean:processor', 'SPRING_BEAN', 'processor');
  const process = s.n('java:method:b.Processor#process()', 'JAVA_METHOD', 'process', 'b.Processor#process()', { modifiers: ['public'] });
  s.e(covered, 'CONTAINS', process);
  s.e(covered, 'DEFINES_BEAN', coveredBean);
  s.e(step, 'USES', coveredBean, { role: 'processor' });
  s.e(step, 'USES', process, { role: 'processor' });
  const ix = graph.buildIndex(s.graph());
  const root = calltree.createRoot(ix, step, 'callees');
  calltree.expandAll(ix, root, {});
  assert.deepEqual(paths(root).map((p) => p.join(' > ')).sort(), [
    [step, type, write].join(' > '),
    [step, bean, readerType, read].join(' > '),
    [step, process].join(' > ')
  ].sort());
  assert.equal(root.children.find((c) => c.id === bean).children[0].link.kind, 'DEFINES_BEAN');
  // 위로: 메서드 → 타입(스텝이 타입만 가리킬 때) → 스텝, 타입 → 빈 → 스텝
  const up = calltree.createRoot(ix, write, 'callers');
  calltree.expandAll(ix, up, {});
  assert.deepEqual(paths(up), [[write, type, step]]);
  const upRead = calltree.createRoot(ix, read, 'callers');
  calltree.expandAll(ix, upRead, {});
  assert.deepEqual(paths(upRead), [[read, readerType, bean, step]]);
  const upProcess = calltree.createRoot(ix, process, 'callers');
  calltree.expandAll(ix, upProcess, {});
  assert.deepEqual(paths(upProcess), [[process, step]], '스텝이 메서드를 직접 가리키면 타입을 거치지 않는다');
});

test('F-016-AC33 콜트리 배치: 잡 아래 스텝은 NEXT 흐름 순서(분기는 ID 순, 순환은 한 번만, 흐름 밖 스텝은 뒤에 ID 순)', () => {
  const s = slice();
  const job = s.n('batch:job:j', 'BATCH_JOB', 'j');
  const step = (name) => { const id = s.n('batch:step:j/' + name, 'BATCH_STEP', name, 'j/' + name); return id; };
  const names = ['cAlone', 'mThird', 'bLone', 'aSecond', 'bFail', 'zFirst'];
  const st = {};
  names.forEach((n) => { st[n] = step(n); s.e(job, 'CONTAINS', st[n]); });
  s.e(st.zFirst, 'NEXT', st.aSecond);
  s.e(st.aSecond, 'NEXT', st.mThird);
  s.e(st.aSecond, 'NEXT', st.bFail);
  s.e(st.mThird, 'NEXT', st.aSecond); // 재시도
  const ix = graph.buildIndex(s.graph());
  assert.deepEqual(calltree.childLinks(ix, job, 'callees').map((l) => ix.node(l.nodeId).name), ['zFirst', 'aSecond', 'bFail', 'mThird', 'bLone', 'cAlone']);
  // 합류: a → b, a → c, c → b 이면 b는 c 뒤다(스텝은 그것으로 오는 스텝들 뒤에 놓인다)
  const t = slice();
  const job2 = t.n('batch:job:k', 'BATCH_JOB', 'k');
  const ids = ['a', 'b', 'c'].map((n) => { const id = t.n('batch:step:k/' + n, 'BATCH_STEP', n, 'k/' + n); t.e(job2, 'CONTAINS', id); return id; });
  t.e(ids[0], 'NEXT', ids[1]);
  t.e(ids[0], 'NEXT', ids[2]);
  t.e(ids[2], 'NEXT', ids[1]);
  const ix2 = graph.buildIndex(t.graph());
  assert.deepEqual(calltree.childLinks(ix2, job2, 'callees').map((l) => ix2.node(l.nodeId).name), ['a', 'c', 'b']);
});

// ---------------------------------------------------------------- 테이블 줄

test('F-016-AC32 콜트리 테이블 줄: 컬럼 엣지만 있는 테이블도 한 줄로 접어 보이고, 신뢰도는 가장 확실한 것을 쓴다', () => {
  const s = slice();
  const stmt = s.n('sql:stmt:x.sql:1', 'SQL_STATEMENT', 'x.sql:1', 'x.sql:1', { statementType: 'SELECT' });
  const table = s.n('db:table:T', 'DB_TABLE', 'T');
  const col = s.n('db:column:T.A', 'DB_COLUMN', 'A', 'T.A');
  s.e(table, 'CONTAINS', col);
  s.e(stmt, 'READS', col);
  const a = s.n('java:method:x.A#a()', 'JAVA_METHOD', 'a', 'x.A#a()');
  const b = s.n('java:method:x.A#b()', 'JAVA_METHOD', 'b', 'x.A#b()');
  s.e(a, 'CALLS', b, { callType: 'virtual' }, 'INFERRED', loc('A.java', 3));
  s.e(a, 'CALLS', b, { callType: 'virtual' }, 'RESOLVED', loc('A.java', 5));
  const gone = s.n('java:method:x.Gone#m()', 'UNRESOLVED', 'java:method:x.Gone#m()', 'java:method:x.Gone#m()', { expectedPrefix: 'java:method' });
  s.e(a, 'CALLS', gone, { callType: 'virtual' }, 'RESOLVED', loc('A.java', 7));
  const ix = graph.buildIndex(s.graph());
  const down = calltree.childLinks(ix, stmt, 'callees');
  assert.deepEqual(down.map((l) => l.nodeId), [table]);
  assert.equal(down[0].columnOnly, true, '테이블 수준 엣지 없이 컬럼에서 접은 줄');
  assert.deepEqual(calltree.childLinks(ix, table, 'callers').map((l) => l.nodeId), [stmt], '위로도 컬럼만 건드린 구문이 나온다');
  const calls = calltree.childLinks(ix, a, 'callees');
  assert.equal(calls.find((l) => l.nodeId === b).confidence, 'RESOLVED', '같은 대상의 엣지가 여럿이면 가장 확실한 것');
  assert.equal(calls.find((l) => l.nodeId === b).count, 2);
  assert.equal(calls.find((l) => l.nodeId === gone).confidence, 'UNRESOLVED', '대상이 미해결 노드면 미해결이다');
});
