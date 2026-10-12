'use strict';
// 방향만 알고 카디널리티는 모르는 추론 관계(REFERENCES, directionKnown=true, directionFrom=DDL, cardinality=unknown. F-018-R15).
// 표준 산출물(codeatlas-export의 ErdModelBuilder·MermaidErdRenderer·ErdLabels, F-013-R27)과 같은 규칙이다:
// 자식 → 부모 방향은 지키되 자식 컬럼의 유일 여부로 N:1을 지어내지 않고 "카디널리티 모름"으로 적는다. 방향 미상(directionKnown=false)과는 다르다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { slice } = require('./fixtures/slice.js');

const graph = core('graph');
const erd = core('erd');

const LEGEND_LINE = "    %% '카디널리티 모름'은 방향(왼쪽이 부모)만 DDL 외래 키로 확인하고 카디널리티는 정하지 못한 추론 관계다. 양쪽 끝 표시는 N:M이라는 뜻이 아니다.";

function builder() {
  const s = slice();
  return {
    s,
    table(name, attrs) { s.n('db:table:' + name, 'DB_TABLE', name, name, attrs || {}); },
    column(t, name, attrs) {
      s.n('db:column:' + t + '.' + name, 'DB_COLUMN', name, t + '.' + name, attrs);
      s.e('db:table:' + t, 'CONTAINS', 'db:column:' + t + '.' + name);
    },
    inferred(child, parent, columns, refColumns, more) {
      s.e('db:table:' + child, 'REFERENCES', 'db:table:' + parent,
        Object.assign({ columns, refColumns, source: 'INFERRED' }, more), 'INFERRED');
    },
    index() { return graph.buildIndex(s.graph()); }
  };
}

/**
 * 공통컴포넌트의 COMTNCOMMENT–COMTNBBS 모양(표준 산출물 테스트 ErdTest.directionOnly와 같은 그래프): 부모의 PK는 (NTT_ID, BBS_ID)이고
 * DDL FK도 그 둘이다. SQL은 NTT_ID만으로 조인해서 링커가 방향은 DDL FK를 따라 정하고 카디널리티는 정하지 못했다.
 * 견줄 것으로 방향 미상(LOG_A–LOG_B)과 확정된 N:1(SCRAP → COMMENT_T)이 있다.
 */
function directionOnly() {
  return directionOnlyBuilder().index();
}

function directionOnlyBuilder() {
  const b = builder();
  b.table('BBS', { primaryKey: ['NTT_ID', 'BBS_ID'] });
  b.column('BBS', 'NTT_ID', { dataType: 'NUMBER', ordinal: 1, nullable: false, primaryKey: true });
  b.column('BBS', 'BBS_ID', { dataType: 'CHAR(20)', ordinal: 2, nullable: false, primaryKey: true });
  b.table('COMMENT_T', { primaryKey: ['COMMENT_NO'] });
  b.column('COMMENT_T', 'COMMENT_NO', { dataType: 'NUMBER', ordinal: 1, nullable: false, primaryKey: true });
  b.column('COMMENT_T', 'NTT_ID', { dataType: 'NUMBER', ordinal: 2, nullable: false });
  b.column('COMMENT_T', 'BBS_ID', { dataType: 'CHAR(20)', ordinal: 3, nullable: false });
  b.s.e('db:table:COMMENT_T', 'REFERENCES', 'db:table:BBS', { columns: ['NTT_ID', 'BBS_ID'], refColumns: ['NTT_ID', 'BBS_ID'], source: 'DDL', constraint: 'COMMENT_FK1' });
  b.inferred('COMMENT_T', 'BBS', ['NTT_ID'], ['NTT_ID'], { evidenceCount: 2, directionKnown: true, directionFrom: 'DDL', cardinality: 'unknown' });
  b.table('LOG_A');
  b.column('LOG_A', 'TX_ID', { dataType: 'NUMBER', ordinal: 1 });
  b.table('LOG_B');
  b.column('LOG_B', 'TX_ID', { dataType: 'NUMBER', ordinal: 1 });
  b.inferred('LOG_A', 'LOG_B', ['TX_ID'], ['TX_ID'], { evidenceCount: 2, directionKnown: false, cardinality: 'unknown' });
  b.table('SCRAP', { primaryKey: ['SCRAP_ID'] });
  b.column('SCRAP', 'SCRAP_ID', { dataType: 'NUMBER', ordinal: 1, nullable: false, primaryKey: true });
  b.column('SCRAP', 'COMMENT_NO', { dataType: 'NUMBER', ordinal: 2, nullable: false });
  b.inferred('SCRAP', 'COMMENT_T', ['COMMENT_NO'], ['COMMENT_NO'], { evidenceCount: 3, directionKnown: true, cardinality: 'N:1' });
  return b;
}

test('F-013-AC30 (뷰어) 표준 산출물과 같은 Mermaid 글자: 방향만 아는 추론 관계는 부모를 왼쪽에 둔 채 }o..o{와 "방향은 DDL, 카디널리티 모름" 라벨로 적고, 머리 주석에 한 줄을 더 적는다', () => {
  const ix = directionOnly();
  const model = erd.exportModel(ix, {});
  const r = model.relations.find((x) => x.child === 'COMMENT_T' && x.inferred);
  assert.equal(r.directionKnown, true);
  assert.deepEqual([r.childSide, r.parentSide], ['unknown', 'unknown'], '자식 컬럼의 유일 여부로 N:1을 지어내지 않는다');
  assert.equal(r.directionFrom, 'DDL');
  assert.equal(r.cardinalityUnknown, true);
  assert.equal(model.relations.find((x) => x.child === 'LOG_A').cardinalityUnknown, false, '방향 미상은 다른 것이다');
  // ErdTest.drawsDirectionWithoutCardinalityWhenOnlyDirectionIsKnown이 단언하는 글자와 같다
  assert.equal(erd.exportMermaid(ix, model.entities, model.relations, {}), [
    'erDiagram',
    '    %% CodeAtlas ERD: 엔티티 5개, 관계 4개 (추론 3개)',
    "    %% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. 추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.",
    LEGEND_LINE,
    '    BBS {',
    '        NUMBER NTT_ID PK',
    '        CHAR(20) BBS_ID PK',
    '    }',
    '    COMMENT_T {',
    '        NUMBER COMMENT_NO PK',
    '        NUMBER NTT_ID FK "NOT NULL"',
    '        CHAR(20) BBS_ID FK "NOT NULL"',
    '    }',
    '    LOG_A {',
    '        NUMBER TX_ID',
    '    }',
    '    LOG_B {',
    '        NUMBER TX_ID',
    '    }',
    '    SCRAP {',
    '        NUMBER SCRAP_ID PK',
    '        NUMBER COMMENT_NO "NOT NULL"',
    '    }',
    '    BBS }o..o{ COMMENT_T : "NTT_ID (추론 2건, 방향은 DDL, 카디널리티 모름)"',
    '    BBS ||--o{ COMMENT_T : "COMMENT_FK1"',
    '    LOG_B }o..o{ LOG_A : "TX_ID (추론 2건, 방향 미상)"',
    '    COMMENT_T |o..o{ SCRAP : "COMMENT_NO (추론 3건)"',
    ''].join('\n'));
  // 그런 관계가 없는 다이어그램의 머리는 전과 같다
  const without = model.relations.filter((x) => !x.cardinalityUnknown);
  assert.ok(!erd.exportMermaid(ix, model.entities, without, {}).includes('카디널리티 모름'));
});

test('F-013-AC30 (뷰어) 화면의 ERD: 방향만 아는 추론 관계는 끝 표시를 가장 약한 것(}o..o{)으로 두고 라벨에 "방향은 DDL, 카디널리티 모름"을 적는다. 방향 미상(끝 표시를 지우는 선)과 따로 센다', () => {
  const ix = directionOnly();
  const scope = erd.selectScope(ix, {});
  const r = scope.relations.find((x) => x.from === 'db:table:COMMENT_T' && x.source === 'INFERRED');
  assert.equal(r.directionKnown, true, '방향은 안다(자식 COMMENT_T → 부모 BBS)');
  assert.equal(r.cardinalityUnknown, true);
  assert.equal(r.directionFrom, 'DDL');
  const undirected = scope.relations.find((x) => x.from === 'db:table:LOG_A');
  assert.equal(undirected.directionKnown, false);
  assert.equal(undirected.cardinalityUnknown, false);
  assert.equal(scope.relations.find((x) => x.from === 'db:table:SCRAP').cardinalityUnknown, false);

  const out = erd.toMermaid(ix, scope, { columns: 'keys' });
  assert.ok(out.source.includes('  BBS }o..o{ COMMENT_T : "NTT_ID (추론 2건, 방향은 DDL, 카디널리티 모름)"\n'), out.source);
  assert.ok(!out.source.includes('BBS ||..o{ COMMENT_T'), 'N:1로 단정하지 않는다: ' + out.source);
  assert.ok(out.source.includes('  BBS ||--o{ COMMENT_T : "COMMENT_FK1"\n'), 'DDL 관계는 그대로');
  assert.ok(out.source.includes('  COMMENT_T |o..o{ SCRAP : "COMMENT_NO (추론 3건)"\n'), '확정된 N:1은 그대로');
  assert.ok(out.source.includes('  LOG_B }o..o{ LOG_A : "TX_ID (추론 2건, 방향 미상)"\n'), '방향 미상은 그대로');
  const lines = out.source.split('\n').filter((l) => / : "/.test(l));
  assert.deepEqual(out.undirected, [lines.findIndex((l) => l.includes('방향 미상'))], '방향 미상(끝 표시를 지울 선)에는 넣지 않는다');
  assert.deepEqual(out.cardinalityUnknown, [lines.findIndex((l) => l.includes('카디널리티 모름'))]);

  const line = erd.relationLines(ix, scope).find((l) => l.relation === r);
  assert.deepEqual([line.parentId, line.childId, line.parentMarker, line.childMarker, line.dashed],
    ['db:table:BBS', 'db:table:COMMENT_T', '}o', 'o{', true]);
});

test('F-013-AC30 (뷰어) directionFrom이 없어도 추론 관계의 cardinality=unknown은 단정하지 않고, directionFrom=DDL이어도 cardinality가 1:1이면 그대로 그린다. DDL 관계의 unknown은 전처럼 컬럼으로 정한다', () => {
  const b = builder();
  ['P', 'C1', 'C2', 'C3'].forEach((t) => {
    b.table(t, { primaryKey: ['ID'] });
    b.column(t, 'ID', { dataType: 'NUMBER', ordinal: 1, nullable: false, primaryKey: true });
    b.column(t, 'P_ID', { dataType: 'NUMBER', ordinal: 2, nullable: false });
  });
  b.inferred('C1', 'P', ['P_ID'], ['ID'], { evidenceCount: 1, directionKnown: true, cardinality: 'unknown' });
  b.inferred('C2', 'P', ['ID'], ['ID'], { evidenceCount: 1, directionKnown: true, directionFrom: 'DDL', cardinality: '1:1' });
  b.s.e('db:table:C3', 'REFERENCES', 'db:table:P', { columns: ['P_ID'], refColumns: ['ID'], source: 'DDL', cardinality: 'unknown' });
  const ix = b.index();
  const model = erd.exportModel(ix, {});
  const text = erd.exportMermaid(ix, model.entities, model.relations, {});
  assert.ok(text.includes('    P }o..o{ C1 : "P_ID (추론 1건, 카디널리티 모름)"\n'), text);
  assert.ok(text.includes('    P |o..o| C2 : "ID (추론 1건)"\n'), text);
  assert.ok(text.includes('    P ||--o{ C3 : "P_ID"\n'), text);
  const source = erd.toMermaid(ix, erd.selectScope(ix, {}), {}).source;
  // 화면의 라벨은 컬럼 이름이 양쪽에서 다르면 상대 쪽 컬럼도 적는다(전부터 그랬다)
  assert.ok(source.includes('  P }o..o{ C1 : "P_ID → ID (추론 1건, 카디널리티 모름)"\n'), source);
  assert.ok(source.includes('  P |o..o| C2 : "ID (추론 1건)"\n'), source);
  assert.ok(source.includes('  P ||--o{ C3 : "P_ID → ID"\n'), source);
});

test('F-013-AC30 (뷰어) ERD 영역의 글자 수: 방향만 아는 추론 관계가 든 영역은 머리 주석 한 줄만큼 길다(표준 산출물의 erd-<영역>.mmd와 같은 글자 수)', () => {
  const b = builder();
  ['BBS_A', 'BBS_B', 'BBS_C', 'CMT_A', 'CMT_B', 'CMT_C', 'SYS_A', 'SYS_B', 'SYS_C'].forEach((t) => {
    b.table(t, { primaryKey: ['ID'] });
    b.column(t, 'ID', { dataType: 'NUMBER', ordinal: 1, nullable: false, primaryKey: true });
    b.column(t, 'REF_ID', { dataType: 'NUMBER', ordinal: 2, nullable: false });
  });
  b.inferred('CMT_A', 'BBS_A', ['REF_ID'], ['REF_ID'], { evidenceCount: 2, directionKnown: true, directionFrom: 'DDL', cardinality: 'unknown' });
  b.inferred('CMT_B', 'CMT_A', ['REF_ID'], ['ID'], { evidenceCount: 1, directionKnown: true, cardinality: 'N:1' });
  b.inferred('SYS_B', 'SYS_A', ['REF_ID'], ['ID'], { evidenceCount: 1, directionKnown: true, cardinality: 'N:1' });
  const ix = b.index();
  const model = erd.exportModel(ix, {});
  const split = erd.splitAreas(ix, {});
  assert.deepEqual(split.areas.map((a) => a.name), ['BBS', 'CMT', 'SYS']);
  assert.equal(split.fullChars, erd.exportMermaid(ix, model.entities, model.relations, {}).length);
  split.areas.forEach((a) => {
    const entities = model.entities.filter((e) => a.ids.has(e.id) || a.neighbors.has(e.id));
    const relations = model.relations.filter((r) => a.ids.has(r.childId) || a.ids.has(r.parentId));
    const text = erd.exportMermaid(ix, entities, relations, { bare: a.neighbors });
    assert.equal(a.chars, text.length, a.name);
    assert.equal(text.includes(LEGEND_LINE + '\n'), a.name !== 'SYS', a.name + ': 그런 관계가 있는 영역에만 한 줄이 붙는다');
  });
});

test('F-013-AC30 (뷰어) ERD 범례: "추론 관계 (카디널리티 모름)" 항목이 방향 미상 다음에 있고, 방향은 DDL 외래 키로 확인했고 카디널리티는 모른다는 것과 끝 표시의 뜻을 적는다', () => {
  assert.deepEqual(erd.LEGEND.map((l) => l.key), ['ddl', 'inferred', 'undirected', 'cardinalityUnknown', 'undeclared']);
  const item = erd.LEGEND.find((l) => l.key === 'cardinalityUnknown');
  assert.equal(item.label, '추론 관계 (카디널리티 모름)');
  assert.equal(item.line, 'dashed');
  assert.equal(item.ends, 'weak', '양쪽 끝이 가장 약한 표시(0개 이상)다. 방향 미상(false: 끝 표시 없음), 다른 관계(true: 부모 하나·자식 여럿)와 다르다');
  ['directionKnown=true', 'directionFrom=DDL', 'cardinality=unknown', '방향은 DDL', '카디널리티', 'N:M'].forEach((word) => {
    assert.ok(item.description.includes(word), word + ': ' + item.description);
  });
  // 다른 항목은 그대로다
  assert.equal(erd.LEGEND.find((l) => l.key === 'undirected').ends, false);
  assert.equal(erd.LEGEND.find((l) => l.key === 'inferred').ends, 'optional', '추론 관계의 부모 쪽은 0 또는 1이다(F-013-R28)');
});

test('F-013-AC30 (뷰어) 화면: ERD 요약에 카디널리티 모름인 추론 관계 수가 방향 미상과 따로 나오고, 범례에 그 항목이 양쪽 까마귀발 그림과 함께 있고, 안내에 표기가 적혀 있다', async () => {
  const { assemble } = require('./tools/assemble.js');
  const { boot, sleep } = require('./tools/boot.js');
  const window = await boot(assemble(JSON.stringify(directionOnlyBuilder().s.graph()), ''));
  const doc = window.document;
  window.CodeAtlas.app.navigate('erd', {});
  await sleep(350);
  const src = doc.querySelector('.view-erd .dg-source').textContent;
  assert.ok(src.includes('BBS }o..o{ COMMENT_T : "NTT_ID (추론 2건, 방향은 DDL, 카디널리티 모름)"'), src);
  const summary = doc.querySelector('.view-erd .dg-summary').textContent;
  assert.ok(summary.includes('관계 4개 (추론 3개, 그중 방향 미상 1개, 카디널리티 모름 1개)'), summary);
  const items = doc.querySelectorAll('.view-erd .legend-item');
  assert.deepEqual(items.map((l) => l.querySelector('div').firstChild.textContent),
    ['DDL 외래 키', '추론 관계', '추론 관계 (방향 미상)', '추론 관계 (카디널리티 모름)', 'DDL 없음']);
  const item = items[3];
  assert.ok(item.textContent.includes('방향은 DDL') && item.textContent.includes('N:M이라는 뜻이 아니다'), item.textContent);
  // 범례 그림: 점선에 양쪽 다 까마귀발(여럿)이다. 다른 추론 관계는 부모 쪽이 0 또는 1이고, 방향 미상은 끝 표시가 없다
  const shapes = (el) => el.querySelector('svg').children.map((c) => String(c.tagName).toLowerCase() + (c.getAttribute('d') ? ':' + c.getAttribute('d') : ''));
  const weak = shapes(item);
  assert.equal(weak.filter((x) => /^path:M\d+ 8l-?7 -4M\d+ 8l-?7 4$/.test(x)).length, 2, '까마귀발 둘: ' + weak.join(' | '));
  assert.ok(!weak.some((x) => /v8M\d+ 4v8/.test(x)), '부모 쪽에 "하나" 표시가 없다: ' + weak.join(' | '));
  // 확정된 추론 관계: 부모 쪽은 "0 또는 1"(세로 금 하나와 동그라미)이다(F-013-R28). DDL 외래 키만 부모 쪽이 세로 금 둘(하나)이다
  assert.ok(shapes(items[1]).some((x) => /^path:M\d+ 4v8$/.test(x)) && shapes(items[1]).includes('circle'), '추론 관계의 부모 쪽은 0 또는 1: ' + shapes(items[1]).join(' | '));
  assert.ok(shapes(items[0]).some((x) => /v8M\d+ 4v8/.test(x)), 'DDL 외래 키의 그림은 그대로다');
  assert.equal(shapes(items[2]).length, 1, '방향 미상의 그림은 선뿐이다');
  const hint = doc.querySelector('.view-erd .pane.side .hint') || doc.querySelectorAll('.view-erd .hint').pop();
  assert.ok(doc.querySelector('.view-erd').textContent.includes('카디널리티를 모르는 추론 관계'), hint && hint.textContent);
  assert.deepEqual(window.errors, []);
});
