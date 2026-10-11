'use strict';
// ERD의 주제 영역(테이블 이름 접두사 묶음): 표준 산출물이 큰 ERD를 나눌 때 쓰는 규칙(codeatlas-export의 ErdSplitter, erd/index.md)과 같은 목록을 만든다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { slice } = require('./fixtures/slice.js');

const graph = core('graph');
const erd = core('erd');

function schema(names, relations, columnsPerTable, comments) {
  const s = slice();
  names.forEach((name) => {
    s.n('db:table:' + name, 'DB_TABLE', name, name, comments && comments[name] ? { primaryKey: ['ID'], comment: comments[name] } : { primaryKey: ['ID'] });
    for (let i = 0; i < (columnsPerTable || 2); i++) {
      const column = i === 0 ? 'ID' : 'COL_' + i;
      s.n('db:column:' + name + '.' + column, 'DB_COLUMN', column, name + '.' + column,
        { dataType: 'NUMBER(10)', ordinal: i + 1, nullable: i !== 0, primaryKey: i === 0 });
      s.e('db:table:' + name, 'CONTAINS', 'db:column:' + name + '.' + column);
    }
  });
  (relations || []).forEach(([child, parent, inferred]) => {
    s.e('db:table:' + child, 'REFERENCES', 'db:table:' + parent,
      inferred ? { columns: ['COL_1'], refColumns: ['ID'], source: 'INFERRED', evidenceCount: 2, cardinality: 'N:1' }
        : { columns: ['COL_1'], refColumns: ['ID'], source: 'DDL', constraint: 'FK_' + child + '_' + parent },
      inferred ? 'INFERRED' : 'RESOLVED');
  });
  return graph.buildIndex(s.graph());
}

test('F-016-AC50 ERD 영역: 테이블 이름의 첫 토큰으로 묶고, 3개 미만인 묶음은 기타로 합친다. 영역마다 테이블 수와 영역 안팎의 관계 수를 센다', () => {
  const ix = schema(['SYS_USER', 'SYS_ROLE', 'SYS_MENU', 'CMS_ARTICLE', 'CMS_SITE', 'CMS_LINK', 'OA_LEAVE', 'OA_NOTIFY', 'LOG'],
    [['SYS_ROLE', 'SYS_USER'], ['CMS_ARTICLE', 'CMS_SITE'], ['CMS_ARTICLE', 'SYS_USER', true], ['OA_LEAVE', 'SYS_USER'], ['LOG', 'OA_LEAVE']]);
  const split = erd.splitAreas(ix, {});
  assert.deepEqual(split.areas.map((a) => [a.name, a.tables.length, a.innerRelations, a.outerRelations]),
    [['CMS', 3, 1, 1], ['SYS', 3, 1, 2], ['기타', 3, 1, 1]]);
  assert.deepEqual(split.areas[2].tables.map((t) => t.name), ['LOG', 'OA_LEAVE', 'OA_NOTIFY'], '기타는 이름순');
  assert.deepEqual(split.areas.map((a) => a.fileName), ['CMS', 'SYS', 'etc']);
  assert.deepEqual(split.crossRelations, [{ from: 'CMS', to: 'SYS', count: 1 }, { from: 'SYS', to: '기타', count: 1 }]);
  assert.equal(split.total, 9);
  // 영역을 범위로 고르면 그 영역의 테이블과, 관계로 이어진 다른 영역의 테이블(이름만)이 들어온다
  const scope = erd.selectScope(ix, { area: 'CMS' });
  assert.deepEqual(scope.tables.map((t) => t.name), ['CMS_ARTICLE', 'CMS_LINK', 'CMS_SITE', 'SYS_USER']);
  assert.deepEqual(Array.from(scope.outside), ['db:table:SYS_USER']);
  assert.equal(scope.relations.length, 2);
  const source = erd.toMermaid(ix, scope, {}).source;
  assert.ok(/^ {2}CMS_ARTICLE \{$/m.test(source), source);
  assert.ok(/^ {2}SYS_USER\["SYS_USER \(다른 영역\)"\]:::outside$/m.test(source), '다른 영역의 테이블은 컬럼 없이 이름만: ' + source);
  assert.ok(source.includes('classDef outside'));
});

test('F-016-AC50 ERD 영역: 접두사가 없는 스키마는 한 묶음(ALL)으로 보고, 뷰는 고를 때만 넣는다', () => {
  const ix = schema(['USERS', 'ROLES', 'MENUS', 'A_B']);
  assert.deepEqual(erd.splitAreas(ix, {}).areas.map((a) => [a.name, a.tables.length]), [['ALL', 4]]);
  assert.deepEqual(erd.splitAreas(schema([]), {}).areas, []);
  // 마지막 토큰까지 쓰면 이름 전체가 되므로 하나는 남긴다: A_B의 접두사는 A다
  assert.equal(erd.areaPrefix('A_B', 2), 'A');
  assert.equal(erd.areaPrefix('sys_user_role', 2), 'SYS_USER');
  assert.equal(erd.areaPrefix('sys_user_role', 1), 'SYS');
  assert.equal(erd.areaPrefix('LOG', 1), 'LOG');
  assert.equal(erd.areaPrefix('__', 1), '__');
});

test('F-016-AC50 ERD 영역: 다이어그램이 글자 수 한도를 넘는 묶음은 둘째 토큰까지로 다시 나누고, 그래도 넘으면 이름순으로 잘라 <영역>-n으로 만든다', () => {
  const names = [];
  ['USER', 'MENU'].forEach((second) => { for (let i = 0; i < 4; i++) names.push('SYS_' + second + '_T' + i); });
  for (let i = 0; i < 3; i++) names.push('CMS_PAGE_' + i);
  const ix = schema(names, [], 6);
  const model = erd.exportModel(ix, {});
  // 이름순으로 앞의 n개 테이블만 그린 표준 산출물 Mermaid의 글자 수 (CMS 3개, 그다음 SYS_MENU 4개)
  const chars = (n) => erd.exportMermaid(ix, model.entities.slice(0, n), [], {}).length;
  // 넉넉하면 첫 토큰 묶음 그대로
  assert.deepEqual(erd.splitAreas(ix, { maxChars: 100000 }).areas.map((a) => [a.name, a.tables.length]), [['CMS', 3], ['SYS', 8]]);
  // SYS 8개가 한도를 넘는다: 둘째 토큰까지로 다시 묶는다
  const refined = erd.splitAreas(ix, { maxChars: chars(5) });
  assert.deepEqual(refined.areas.map((a) => [a.name, a.tables.length]), [['CMS', 3], ['SYS_MENU', 4], ['SYS_USER', 4]]);
  assert.ok(refined.areas.every((a) => a.chars <= chars(5)), '영역 다이어그램의 글자 수(표준 산출물의 Mermaid 기준)가 한도 안이다');
  assert.equal(refined.areas[0].chars, chars(3));
  // 3개도 넘으면 둘째 토큰까지로 묶은 뒤 이름순으로 자른다
  const chunked = erd.splitAreas(ix, { maxChars: chars(3) - 1 });
  assert.deepEqual(chunked.areas.map((a) => [a.name, a.tables.length]),
    [['CMS_PAGE-1', 2], ['CMS_PAGE-2', 1], ['SYS_MENU-1', 2], ['SYS_MENU-2', 2], ['SYS_USER-1', 2], ['SYS_USER-2', 2]]);
  assert.deepEqual(chunked.areas[2].tables.map((t) => t.name), ['SYS_MENU_T0', 'SYS_MENU_T1']);
  assert.deepEqual(chunked.areas.map((a) => a.fileName), ['CMS_PAGE-1', 'CMS_PAGE-2', 'SYS_MENU-1', 'SYS_MENU-2', 'SYS_USER-1', 'SYS_USER-2']);
  // 전체 다이어그램이 한도를 넘는지(표준 산출물이 영역으로 나누는 조건)
  assert.equal(erd.splitAreas(ix, { maxChars: 100000 }).oversize, false);
  assert.equal(chunked.oversize, true);
  assert.ok(chunked.fullChars > chunked.maxChars);
});

test('F-016-AC50 ERD: 표준 산출물과 같은 Mermaid 글자(영역을 나누는 기준)를 만든다 — 주석 줄, NOT NULL, 참조된 컬럼, 방향 미상', () => {
  const s = slice();
  s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER', 'TB_ORDER', { comment: '주문', primaryKey: ['ORDER_ID'] });
  const col = (t, name, attrs) => { s.n('db:column:' + t + '.' + name, 'DB_COLUMN', name, t + '.' + name, attrs); s.e('db:table:' + t, 'CONTAINS', 'db:column:' + t + '.' + name); };
  col('TB_ORDER', 'ORDER_ID', { dataType: 'NUMBER(12, 0)', ordinal: 1, nullable: false, primaryKey: true });
  col('TB_ORDER', 'MEMBER_ID', { dataType: 'NUMBER(12)', ordinal: 2, nullable: false, comment: '회원' });
  col('TB_ORDER', 'MEMO TEXT', { dataType: 'VARCHAR2(100 CHAR)', ordinal: 3, nullable: true });
  s.n('db:table:TB_MEMBER', 'DB_TABLE', 'TB_MEMBER', 'TB_MEMBER', {});
  col('TB_MEMBER', 'MEMBER_ID', { dataType: 'NUMBER(12)', ordinal: 1, nullable: false, primaryKey: true });
  s.n('db:table:TB_GHOST', 'DB_TABLE', 'TB_GHOST', 'TB_GHOST', { declared: false });
  s.n('db:column:TB_GHOST.GHOST_ID', 'DB_COLUMN', 'GHOST_ID', 'TB_GHOST.GHOST_ID', { declared: false });
  s.n('mybatis:stmt:m.x', 'SQL_STATEMENT', 'x', 'm.x', { statementType: 'SELECT' });
  s.e('mybatis:stmt:m.x', 'READS', 'db:column:TB_GHOST.GHOST_ID');
  s.e('db:table:TB_ORDER', 'REFERENCES', 'db:table:TB_MEMBER', { columns: ['MEMBER_ID'], refColumns: ['MEMBER_ID'], source: 'DDL', constraint: 'FK_ORDER_MEMBER' });
  s.e('db:table:TB_ORDER', 'REFERENCES', 'db:table:TB_GHOST', { columns: ['ORDER_ID'], refColumns: ['GHOST_ID'], source: 'INFERRED', evidenceCount: 1, directionKnown: false }, 'INFERRED');
  const ix = graph.buildIndex(s.graph());
  const model = erd.exportModel(ix, {});
  assert.equal(erd.exportMermaid(ix, model.entities, model.relations, {}), [
    'erDiagram',
    '    %% CodeAtlas ERD: 엔티티 3개, 관계 2개 (추론 1개)',
    "    %% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. 추론 관계는 제약이 없어 부모가 반드시 있는지 알 수 없으므로 부모 쪽을 '0 또는 1'(|o)로 그린다.",
    '    "TB_GHOST (DDL 없음)" {',
    '        unknown GHOST_ID "참조된 컬럼"',
    '    }',
    '    TB_MEMBER {',
    '        NUMBER(12) MEMBER_ID PK',
    '    }',
    '    %% TB_ORDER: 주문',
    '    TB_ORDER {',
    '        NUMBER(12_0) ORDER_ID PK',
    '        NUMBER(12) MEMBER_ID FK "NOT NULL, 회원"',
    '        VARCHAR2(100_CHAR) MEMO_TEXT "MEMO TEXT"',
    '    }',
    '    "TB_GHOST (DDL 없음)" }o..o{ TB_ORDER : "ORDER_ID (추론 1건, 방향 미상)"',
    '    TB_MEMBER ||--o{ TB_ORDER : "FK_ORDER_MEMBER"',
    ''].join('\n'));
});

// ---------- 5차 수정: 허브와의 관계, 이름 접두 군집, 중심 테이블과 30개 상한, 논리명 (표준 산출물의 F-013-AC24~AC27과 같은 규칙)

const areaRows = (split) => split.areas.map((a) => [a.name, a.tables.length, a.innerRelations, a.outerRelations, a.hubRelations]);

test('F-013-AC24 (뷰어) ERD 영역: 허브 테이블과의 관계는 "다른 영역과의 관계"에 넣지 않고 따로 센다. 다른 영역과의 관계 수의 합은 영역 간 관계의 두 배다', () => {
  // 사슬 둘(COMTNBBS, COMTNUSER 4개씩), 잎 6개가 참조하는 공통코드 COMTCCODE(허브: 12개 가운데 6개가 참조), 접두사 영역 SYS 3개
  const ix = schema(['COMTCCODE', 'COMTNBBS', 'COMTNBBSA', 'COMTNBBSB', 'COMTNBBSC', 'COMTNUSER', 'COMTNUSERA', 'COMTNUSERB', 'COMTNUSERC', 'SYS_A', 'SYS_B', 'SYS_C'],
    [['COMTNBBSA', 'COMTNBBS'], ['COMTNBBSB', 'COMTNBBS'], ['COMTNBBSC', 'COMTNBBSA'], ['COMTNUSERA', 'COMTNUSER'], ['COMTNUSERB', 'COMTNUSER'], ['COMTNUSERC', 'COMTNUSERA'],
      ['COMTNBBSA', 'COMTCCODE', true], ['COMTNBBSB', 'COMTCCODE', true], ['COMTNBBSC', 'COMTCCODE', true],
      ['COMTNUSERA', 'COMTCCODE', true], ['COMTNUSERB', 'COMTCCODE', true], ['COMTNUSERC', 'COMTCCODE', true], ['SYS_A', 'COMTNBBSC']]);
  const split = erd.splitAreas(ix, {});
  assert.deepEqual(split.hubs.map((h) => [h.name, h.referencedBy]), [['COMTCCODE', 6]]);
  assert.deepEqual(areaRows(split), [['COMTNBBS', 4, 3, 1, 3], ['COMTNUSER', 4, 3, 0, 3], ['SYS', 3, 0, 1, 0]]);
  assert.deepEqual(split.crossRelations, [{ from: 'COMTNBBS', to: 'SYS', count: 1 }]);
  const outer = split.areas.reduce((sum, a) => sum + a.outerRelations, 0);
  assert.equal(outer, 2 * split.crossRelations.reduce((sum, c) => sum + c.count, 0), '영역 표의 "다른 영역과의 관계"와 "영역 간 관계"가 어긋나지 않는다');
  // 허브는 그것을 참조하는 영역에 이름만 나온다
  assert.ok(split.areas[0].neighbors.has('db:table:COMTCCODE'));
});

test('F-013-AC25 (뷰어) ERD 영역: 관계로 묶이지 않고 남은 테이블은 이름의 공통 접두사로 묶는다(가장 긴 접두사부터, 3개 이상·접두사 3자 이상). 묶이지 않는 나머지만 기타다', () => {
  const ix = schema(['COMTNBBSA', 'COMTNBBSB', 'COMTNBBSC', 'COMTNSMS', 'COMTNSMSLOG', 'COMTNSMSRECPTN', 'COMTNX', 'COMTHLOG', 'ROLES', 'USERS',
    'COMTSA', 'COMTSB', 'COMTSC1', 'COMTSC2', 'COMTSC3']);
  const split = erd.splitAreas(ix, {});
  // COMTSC*가 먼저 묶이고, COMTS의 나머지(A, B)는 3개가 안 돼 위로 올라가 COMT의 나머지(COMTHLOG, COMTNX)와 함께 COMT*가 된다
  assert.deepEqual(split.areas.map((a) => [a.name, a.fileName, a.tables.map((t) => t.name).join(' ')]), [
    ['COMT*', 'COMT', 'COMTHLOG COMTNX COMTSA COMTSB'],
    ['COMTNBBS*', 'COMTNBBS', 'COMTNBBSA COMTNBBSB COMTNBBSC'],
    ['COMTNSMS*', 'COMTNSMS', 'COMTNSMS COMTNSMSLOG COMTNSMSRECPTN'],
    ['COMTSC*', 'COMTSC', 'COMTSC1 COMTSC2 COMTSC3'],
    ['기타', 'etc', 'ROLES USERS']]);
  assert.equal(split.byNames, true);
  assert.equal(split.byRelations, false);
  // 접두사가 3자에 못 미치면 묶지 않는다. 접두사로도 관계로도 이름으로도 묶이는 것이 없으면 전처럼 ALL이다
  assert.deepEqual(erd.splitAreas(schema(['ABX', 'ABY', 'ABZ', 'Q']), {}).areas.map((a) => a.name), ['ALL']);
  // 이름 접두 군집도 30개를 넘지 않는다: 넘으면 이름순 조각으로 자른다
  const many = [];
  for (let i = 0; i < 35; i++) many.push('COMTNLOG' + String.fromCharCode(65 + (i % 26)) + (i < 26 ? '' : 'X'));
  const big = erd.splitAreas(schema(many.concat(['USERS'])), {});
  assert.deepEqual(big.areas.map((a) => [a.name, a.tables.length]), [['COMTNLOG*-1', 18], ['COMTNLOG*-2', 17], ['기타', 1]]);
  assert.deepEqual(big.areas.map((a) => a.fileName), ['COMTNLOG-1', 'COMTNLOG-2', 'etc']);
});

// 사슬 하나(A6 → A5 → … → A1). 테이블이 6개라서 한 테이블이 참조하는 것은 허브(20% 초과)가 아니다.
const CHAIN = ['A1', 'A2', 'A3', 'A4', 'A5', 'A6'];
const CHAIN_LINKS = [['A2', 'A1'], ['A3', 'A2'], ['A4', 'A3'], ['A5', 'A4'], ['A6', 'A5']];

/** COMTNUSER를 6개 테이블이 참조한다(33개 가운데 6개: 허브는 아니다). COMTNUSER를 빼면 사슬 셋(12, 12, 4개)과 작은 조각(1, 1, 2개)으로 갈린다. */
function centered() {
  const names = ['COMTNUSER', 'COMTNLOGIN', 'COMTNPASSWD', 'COMTNNOTE', 'COMTNNOTERECPTN'];
  const relations = [['COMTNLOGIN', 'COMTNUSER'], ['COMTNPASSWD', 'COMTNUSER'], ['COMTNNOTE', 'COMTNUSER'], ['COMTNNOTERECPTN', 'COMTNNOTE']];
  [['COMTNBBS', 11], ['COMTNFILE', 11], ['COMTNMENU', 3]].forEach(([head, tail]) => {
    names.push(head);
    let previous = head;
    for (let i = 1; i <= tail; i++) {
      const name = head + String(i).padStart(2, '0');
      names.push(name);
      relations.push([name, previous]);
      previous = name;
    }
  });
  relations.push(['COMTNBBS', 'COMTNUSER'], ['COMTNFILE', 'COMTNUSER'], ['COMTNMENU03', 'COMTNUSER']);
  return schema(names, relations, 2, { COMTNUSER: '사용자', COMTNBBS: '게시판\n|마스터', COMTNFILE01: '첨부 파일 상세' });
}

test('F-013-AC26 (뷰어) ERD 영역: 관계로 묶은 영역이 테이블 30개를 넘으면 가장 많이 참조되는 테이블(중심)을 빼고 나머지를 다시 묶는다. 중심은 자기 영역에 있고 다른 영역에는 이름만 나온다', () => {
  const ix = centered();
  const split = erd.splitAreas(ix, {});
  assert.equal(split.total, 33);
  assert.deepEqual(split.hubs, []);
  assert.deepEqual(areaRows(split), [['COMTNBBS', 12, 11, 1, 0], ['COMTNFILE', 12, 11, 1, 0], ['COMTNMENU', 4, 3, 1, 0], ['COMTNUSER', 5, 4, 3, 0]]);
  assert.ok(split.areas.every((a) => a.tables.length <= erd.MAX_AREA_TABLES));
  assert.equal(erd.MAX_AREA_TABLES, 30);
  // 중심의 영역: 중심 + 중심을 빼면 3개 미만으로 남는 조각
  assert.deepEqual(split.areas[3].tables.map((t) => t.name), ['COMTNLOGIN', 'COMTNNOTE', 'COMTNNOTERECPTN', 'COMTNPASSWD', 'COMTNUSER']);
  assert.deepEqual(split.centers.map((c) => [c.name, c.referencedBy, c.areas]), [['COMTNUSER', 6, ['COMTNBBS', 'COMTNFILE', 'COMTNMENU']]]);
  assert.deepEqual(split.crossRelations, [{ from: 'COMTNBBS', to: 'COMTNUSER', count: 1 }, { from: 'COMTNFILE', to: 'COMTNUSER', count: 1 }, { from: 'COMTNMENU', to: 'COMTNUSER', count: 1 }]);
  // 중심을 참조하는 2차 영역에는 중심이 이름만 나온다
  const scope = erd.selectScope(ix, { area: 'COMTNBBS' });
  assert.deepEqual(Array.from(scope.outside), ['db:table:COMTNUSER']);
  assert.ok(/^ {2}COMTNUSER\["COMTNUSER \(다른 영역\)"\]:::outside$/m.test(erd.toMermaid(ix, scope, {}).source));
  // 30개 이하면 나누지 않는다
  const small = erd.splitAreas(schema(CHAIN, CHAIN_LINKS), {});
  assert.deepEqual(small.areas.map((a) => [a.name, a.tables.length]), [['A1', 6]]);
  assert.deepEqual(small.centers, []);
});

test('F-013-AC26 (뷰어) ERD 영역: 중심을 빼도 갈리지 않는 묶음(고리)은 중심 여럿에 가까운 테이블끼리 나눠 30개 상한을 지킨다', () => {
  const names = [];
  const relations = [];
  for (let i = 0; i < 40; i++) names.push('RING' + String(i).padStart(2, '0'));
  names.forEach((name, i) => relations.push([name, names[(i + 1) % names.length]]));
  const split = erd.splitAreas(schema(names, relations), {});
  assert.ok(split.areas.length >= 2, JSON.stringify(split.areas.map((a) => a.name)));
  assert.ok(split.areas.every((a) => a.tables.length <= 30), JSON.stringify(split.areas.map((a) => [a.name, a.tables.length])));
  assert.equal(split.areas.reduce((sum, a) => sum + a.tables.length, 0), 40);
  assert.deepEqual(split.centers, []);
});

test('F-013-AC27 (뷰어) ERD 영역: 영역 이름이 된 테이블에 논리명(comment)이 있으면 표시 이름은 논리명(물리명)이다. 파일 이름은 물리명 그대로다', () => {
  const split = erd.splitAreas(centered(), {});
  assert.deepEqual(split.areas.map((a) => [a.name, a.label, a.fileName]), [
    ['COMTNBBS', '게시판 |마스터(COMTNBBS)', 'COMTNBBS'], ['COMTNFILE', 'COMTNFILE', 'COMTNFILE'],
    ['COMTNMENU', 'COMTNMENU', 'COMTNMENU'], ['COMTNUSER', '사용자(COMTNUSER)', 'COMTNUSER']]);
  // 접두사 영역과 이름 접두 군집, 기타는 이름 그대로다
  const plain = erd.splitAreas(schema(['SYS_A', 'SYS_B', 'SYS_C', 'COMTNX1', 'COMTNX2', 'COMTNX3', 'ETC'], [], 2, { SYS_A: '시스템', COMTNX1: '엑스' }), {});
  assert.deepEqual(plain.areas.map((a) => [a.name, a.label]), [['COMTNX*', 'COMTNX*'], ['SYS', 'SYS'], ['기타', '기타']]);
  // 긴 논리명은 40자에서 자른다
  const long = erd.splitAreas(schema(CHAIN, CHAIN_LINKS, 2, { A1: '가'.repeat(45) }), {});
  assert.equal(long.areas[0].label, '가'.repeat(40) + '…(A1)');
});

test('F-013-AC26 (뷰어) ERD 영역: 중심을 빼도 잘 갈리지 않는 긴 사슬(70개)은 서로 먼 테이블들을 중심으로 고르게 나눈다. 낱개로 흩어지지 않는다', () => {
  const names = [];
  const relations = [];
  for (let i = 0; i < 70; i++) names.push('CHAIN' + String(i).padStart(2, '0'));
  for (let i = 1; i < 70; i++) relations.push([names[i], names[i - 1]]);
  const split = erd.splitAreas(schema(names, relations), {});
  assert.deepEqual(split.areas.map((a) => [a.name, a.tables.length]), [['CHAIN00', 9], ['CHAIN09', 17], ['CHAIN26', 26], ['CHAIN52', 18]]);
  assert.deepEqual(split.centers, []);
});
