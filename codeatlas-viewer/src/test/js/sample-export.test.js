'use strict';
// 같은 graph.json에서 뷰어의 core 로직이 만든 결과와 표준 산출물(codeatlas-export가 쓴 파일)을 견준다.
// 산출물은 다른 모듈이 만들므로 파일이 있을 때만 돈다:
//   bin/codeatlas analyze samples/sample-shop --out codeatlas-cli/target/final-out
// 다른 위치는 환경 변수 CODEATLAS_OUT으로 준다. 기대값을 적어 두지 않고 파일끼리 견주므로 분석기가 바뀌어도 깨지지 않는다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const out = require('./tools/export-files.js');
const notation = require('./tools/export-notation.js');

const graph = core('graph');
const calltree = core('calltree');
const crud = core('crud');
const erd = core('erd');
const diag = core('diagnostics');

const exportTree = require('./tools/export-tree.js');

// 다른 프로젝트의 산출물(CODEATLAS_OUT)로 돌릴 때는 샘플에만 있는 모양을 단언하지 않는다.
const SAMPLE = !process.env.CODEATLAS_OUT;

const need = (...files) => {
  const missing = ['graph.json'].concat(files).filter((f) => !out.exists(f));
  return missing.length ? '표준 산출물 없음: ' + missing.map((f) => out.file(f)).join(', ') : false;
};

let cached = null;
function load() {
  if (!cached) {
    const raw = JSON.parse(out.read('graph.json'));
    cached = { raw, ix: graph.buildIndex(raw) };
  }
  return cached;
}

// ---------------------------------------------------------------- CRUD (F-016-AC30)

const CRUD_FILES = { statement: 'crud/table-statement.csv', method: 'crud/table-method.csv', endpoint: 'crud/table-endpoint.csv', routine: 'crud/table-routine.csv' };

for (const rowKind of Object.keys(CRUD_FILES)) {
  const rel = CRUD_FILES[rowKind];
  test('sample-shop F-016-AC30 CRUD ' + rowKind + ' 기준: ' + rel + '과 행·열·칸이 모두 같다', { skip: need(rel) }, () => {
    const { ix } = load();
    const text = out.read(rel);
    const expected = out.parseCsv(text);
    const m = crud.buildMatrix(ix, { rowKind });
    const columns = crud.columnsOf(m);
    const header = expected[0];
    assert.deepEqual([header[0], header[1]], [crud.ROW_KINDS.find((k) => k.key === rowKind).csvTitle, '위치']);
    assert.deepEqual(columns.map((c) => c.name), header.slice(2), '열(테이블 이름순, 마지막에 (동적))');
    const diffs = [];
    const mine = new Map(m.rows.map((r) => [r.exportLabel + '\u0000' + (r.location || ''), r]));
    const seen = new Set();
    expected.slice(1).forEach((line) => {
      const key = line[0] + '\u0000' + line[1];
      const row = mine.get(key);
      if (!row) { diffs.push('뷰어에 없는 행: ' + line[0]); return; }
      seen.add(key);
      columns.forEach((c, i) => {
        const want = line[i + 2] || '';
        const got = row.cells[c.id] || '';
        if (want !== got) diffs.push(line[0] + ' × ' + c.name + ': 산출물 "' + want + '", 뷰어 "' + got + '"');
      });
    });
    mine.forEach((row, key) => { if (!seen.has(key)) diffs.push('산출물에 없는 행: ' + row.exportLabel); });
    assert.deepEqual(diffs, [], diffs.length + '곳이 다르다');
    assert.equal(m.rows.length, expected.length - 1);
    // 뷰어의 "CSV 저장"도 같은 파일을 낸다
    assert.equal(crud.toCsv(m, { bom: text.charCodeAt(0) === 0xFEFF }), text, 'CSV가 바이트까지 같다');
  });
}

test('sample-shop F-016-AC29 CRUD: 간접 접근(소문자), CALL 구문 행, (동적) 열이 실제 그래프에서 나온다', { skip: !SAMPLE || need() }, () => {
  const { ix } = load();
  const statements = crud.buildMatrix(ix, { rowKind: 'statement' });
  const call = statements.rows.find((r) => r.id === 'mybatis:stmt:com.example.shop.order.mapper.OrderMapper.callCloseOrder');
  assert.ok(call, 'CALL 구문의 행');
  assert.ok(Object.values(call.cells).every((v) => v === v.toLowerCase()), '루틴을 거친 접근만 있다: ' + JSON.stringify(call.cells));
  assert.equal(statements.dynamicColumn, true);
  const dynamicIds = ix.edges.filter((e) => (e.kind === 'READS' || e.kind === 'WRITES') && e.to.startsWith('unresolved:sql-dynamic:')).map((e) => e.from);
  assert.deepEqual(statements.dynamicStatements.map((d) => d.id), Array.from(new Set(dynamicIds)).sort());
  const methods = crud.buildMatrix(ix, { rowKind: 'method' });
  assert.ok(methods.rows.some((r) => Object.values(r.cells).some((v) => /[A-Z]/.test(v) && /[a-z]/.test(v))) ||
    crud.buildMatrix(ix, { rowKind: 'endpoint' }).rows.some((r) => Object.values(r.cells).some((v) => /[A-Z]/.test(v) && /[a-z]/.test(v))),
  '직접 + 간접이 섞인 칸(Ru 같은)이 있다');
});

// ---------------------------------------------------------------- 콜트리 (F-016-AC34, F-016-AC56)

const COMPARED_MARKERS = ['impl', 'via interface', 'trigger?', '?', 'reflect', 'before', 'redirect', 'forward', 'layout'];

/**
 * 표준 산출물의 텍스트 트리(want)와 뷰어의 트리 노드(t)를 같은 자리에서부터 견준다.
 * 자식의 집합(같은 노드, 표식 [impl]·[trigger?]·[?]·[reflect]·[before], 신뢰도, 테이블 접근 글자, 호출 방식·역할·모듈)과 순서가 같아야 한다.
 * 줄 모양은 보지 않는다. 뷰어는 같은 노드로 가는 줄을 관계 이름별로 따로 보이므로 표준 산출물의 걸음 단위로 묶어 견준다(tools/export-tree.js).
 * 산출물이 펼치지 않은 곳([see above], [cycle], [depth limit], 컨트롤러가 그리는 화면)은 내려가지 않는다.
 * truncated(노드 수 상한에 잘린 트리)면 산출물에 없는 자식은 따지지 않는다(잘려서 없는 것일 수 있다).
 */
function compareTree(ix, want, t, trail, diffs, stats, truncated) {
  stats.nodes++;
  const here = trail + ' > ' + want.head;
  if (want.markers.includes('see above') || want.markers.includes('cycle') || want.markers.includes('depth limit')) return;
  // 표준 산출물의 기본값: 컨트롤러가 그리는 화면 아래는 펼치지 않는다(뷰어의 "화면 이동 펼치기"를 끈 것과 같다).
  // redirect·forward로 넘어간 핸들러와 레이아웃도 한 줄만 적는다.
  if (exportTree.stopsAt(t)) {
    if (want.children.length) diffs.push(here + ': 화면 이동(컨트롤러가 그리는 화면, redirect·forward, 레이아웃) 아래에 자식이 있다');
    stats.screens++;
    return;
  }
  const groups = exportTree.steps(ix, t);
  // 뷰어만 하는 일(F-016-R6): 뷰 아래로 정의 쿼리를 따라간다. 표준 산출물은 뷰에서 멈춘다.
  if (want.tag === 'view' && want.children.length === 0) {
    stats.viewExtensions += groups.length ? 1 : 0;
    if (!groups.every((g) => g.items.every((k) => k.link.kind === 'CONTAINS'))) diffs.push(here + ': 뷰 아래에는 정의 쿼리만');
    return;
  }
  const unmatched = new Set(groups.map((g, i) => i));
  let last = -1;
  want.children.forEach((w) => {
    const impl = w.markers.includes('impl');
    let found = -1;
    // 같은 노드가 문맥이 달라 두 번 나올 수 있다: 앞에서부터 차례로 맞춘다.
    groups.forEach((g, i) => {
      const synthetic = exportTree.syntheticOf(g);
      // 오버로드 후보를 묶은 줄은 그래프의 노드가 아니다: 종류 표시([ambiguous n])와 이름으로 맞춘다.
      const same = synthetic ? synthetic.type === 'ambiguous' && w.tag === 'ambiguous ' + synthetic.count && w.head === synthetic.text
        : notation.matches(ix, g.id, w);
      if (found < 0 && unmatched.has(i) && g.items.some((k) => !!k.link.impl) === impl && same) found = i;
    });
    if (found < 0) { diffs.push(here + ': 뷰어에 없는 자식 ' + (impl ? '[impl] ' : '') + w.head); return; }
    unmatched.delete(found);
    const g = groups[found];
    const describe = here + ' > ' + w.head;
    if (found < last) diffs.push(describe + ': 순서가 다르다(뷰어에서는 앞의 줄보다 먼저 나온다)');
    last = Math.max(last, found);
    const mine = exportTree.markersOf(g).join(',');
    const theirs = COMPARED_MARKERS.filter((m) => w.markers.includes(m)).join(',');
    if (mine !== theirs) diffs.push(describe + ': 표식 산출물 [' + theirs + '], 뷰어 [' + mine + ']');
    const confidence = exportTree.confidenceOf(g);
    if (confidence !== w.confidence) diffs.push(describe + ': 신뢰도 산출물 ' + w.confidence + ', 뷰어 ' + confidence);
    const kind = notation.kindOf(ix, g.id);
    let detail = kind === 'DB_TABLE' || kind === 'DB_VIEW' ? notation.detailOf(ix, g.items) : exportTree.detailOf(g) || '';
    if (exportTree.MARKER_DETAILS.includes(detail)) detail = '';
    const wantDetail = exportTree.syntheticOf(g) ? '' : notation.detailFromHead(ix, g.id, w);
    if (detail !== wantDetail) diffs.push(describe + ': 세부 표시 산출물 (' + wantDetail + '), 뷰어 (' + detail + ')');
    // 내려갈 때는 쓰기 줄을 먼저 쓴다(트리거는 쓰기 아래에만 있다). 표준 산출물도 읽기와 쓰기가 함께면 쓰기로 본다.
    if (g.items.length > 1) {
      g.items.filter((k) => k !== g.next && k.link.kind === 'READS').forEach((k) => {
        if (calltree.expand(ix, k).length) diffs.push(describe + ': 읽기 줄 아래에 자식이 있다');
      });
    }
    if (g.next.cycle !== w.markers.includes('cycle')) diffs.push(describe + ': [cycle] 산출물 ' + w.markers.includes('cycle') + ', 뷰어 ' + g.next.cycle);
    compareTree(ix, w, g.next, here, diffs, stats, truncated);
  });
  if (!truncated) {
    unmatched.forEach((i) => {
      const g = groups[i];
      diffs.push(here + ': 산출물에 없는 자식 ' + exportTree.line(ix, g) + ' (' + g.items.map((k) => k.link.label).join(', ') + ')');
    });
  }
}

function compareSection(ix, trees, title, id) {
  const want = trees.get(title);
  assert.ok(want, '산출물에 "' + title + '" 절이 없다');
  assert.ok(notation.matches(ix, id, want), title + ': 뿌리 노드');
  const diffs = [];
  const stats = { nodes: 0, screens: 0, viewExtensions: 0, truncated: !!want.truncated };
  compareTree(ix, want, calltree.createRoot(ix, id, 'callees', {}), '', diffs, stats, !!want.truncated);
  return { diffs, stats };
}

// 대표 5개: 트리거·루틴, 주입 좁히기와 super, 리다이렉트와 화면, 미해결, 뷰
const FOCUS_ENDPOINTS = ['DELETE /api/orders/{id}', 'POST /api/orders', 'POST /order/save', 'GET /api/orders/export', 'GET /api/products/{productId}'];

for (const name of FOCUS_ENDPOINTS) {
  test('sample-shop F-016-AC34 콜트리: 엔드포인트 ' + name + '의 전개가 calltree/endpoints.md와 같다', { skip: !SAMPLE || need('calltree/endpoints.md') }, () => {
    const { ix } = load();
    const trees = out.parseTrees(out.read('calltree/endpoints.md'));
    const r = compareSection(ix, trees, name, 'http:endpoint:' + name);
    assert.deepEqual(r.diffs, [], r.diffs.length + '곳이 다르다');
    assert.ok(r.stats.nodes > 3, '트리가 비어 있지 않다');
  });
}

test('sample-shop F-016-AC34 F-016-AC56 콜트리: 모든 엔드포인트의 전개가 calltree/endpoints.md(나뉘었으면 endpoints/*.md)와 자식의 집합·표식·순서까지 같다', { skip: need('calltree/endpoints.md') }, () => {
  const { ix } = load();
  const trees = out.endpointTrees();
  const endpoints = ix.nodesOfKind('HTTP_ENDPOINT');
  assert.equal(trees.size, endpoints.length, '절 수 = 엔드포인트 수');
  const diffs = [];
  let nodes = 0;
  let truncated = 0;
  endpoints.forEach((ep) => {
    const r = compareSection(ix, trees, notation.label(ix, ep.id), ep.id);
    r.diffs.forEach((d) => diffs.push(d));
    nodes += r.stats.nodes;
    if (r.stats.truncated) truncated++;
  });
  assert.deepEqual(diffs.slice(0, 40), [], diffs.length + '곳이 다르다');
  assert.ok(nodes > endpoints.length * 3);
  if (process.env.CODEATLAS_VERBOSE) console.log('엔드포인트 ' + endpoints.length + '개, 견준 줄 ' + nodes + '개, 노드 수 상한에 잘린 트리 ' + truncated + '개');
});

test('sample-shop F-016-AC56 콜트리: 필터·인터셉터 진입 메서드의 목록과 전개가 calltree/filters.md와 같다', { skip: need() }, () => {
  const { ix } = load();
  const entries = calltree.filterEntries(ix);
  // 필터·인터셉터가 없으면 표준 산출물도 파일을 쓰지 않는다
  assert.equal(out.exists('calltree/filters.md'), entries.length > 0, 'filters.md의 유무');
  if (!entries.length) return;
  const trees = out.parseTrees(out.read('calltree/filters.md'));
  assert.deepEqual(entries.map((e) => notation.label(ix, e.id)), Array.from(trees.keys()), '진입 메서드(ID 순)');
  const diffs = [];
  entries.forEach((entry) => compareSection(ix, trees, notation.label(ix, entry.id), entry.id).diffs.forEach((d) => diffs.push(d)));
  assert.deepEqual(diffs.slice(0, 40), [], diffs.length + '곳이 다르다');
});

test('sample-shop F-016-AC34 콜트리: 배치 잡의 전개(스텝 순서 포함)가 calltree/batch-jobs.md와 같다', { skip: need('calltree/batch-jobs.md') }, () => {
  const { ix } = load();
  const trees = out.parseTrees(out.read('calltree/batch-jobs.md'));
  const jobs = ix.nodesOfKind('BATCH_JOB');
  if (SAMPLE) assert.ok(jobs.length >= 2, '잡 2개 이상');
  const diffs = [];
  jobs.forEach((job) => {
    const title = notation.label(ix, job.id);
    const r = compareSection(ix, trees, title, job.id);
    r.diffs.forEach((d) => diffs.push(d));
    // 스텝은 NEXT 흐름 순서다: 순서까지 같아야 한다
    const want = trees.get(title).children.filter((c) => c.tag === 'step').map((c) => c.head);
    const got = calltree.expand(ix, calltree.createRoot(ix, job.id, 'callees', {})).filter((c) => c.node && c.node.kind === 'BATCH_STEP')
      .map((c) => notation.label(ix, c.id));
    if (!trees.get(title).truncated) assert.deepEqual(got, want, title + ': 스텝 순서');
  });
  assert.deepEqual(diffs.slice(0, 40), [], diffs.length + '곳이 다르다');
});

// ---------------------------------------------------------------- ERD (F-016-AC37)

test('sample-shop F-016-AC37 ERD: erd/erd.mmd와 엔티티, 관계 집합, 방향, 카디널리티, 선 모양이 같다', { skip: need('erd/erd.mmd') }, () => {
  const { ix } = load();
  const mmd = out.read('erd/erd.mmd');
  const want = out.parseErdRelations(mmd);
  const scope = erd.selectScope(ix, { maxNodes: 1000000 });
  assert.deepEqual(scope.tables.map((t) => t.name).sort(), out.parseErdEntities(mmd).sort(), '엔티티');
  const mine = out.parseErdRelations(erd.toMermaid(ix, scope, {}).source.split('\n').map((l) => '  ' + l).join('\n'));
  const nameOf = (id) => ix.node(id).name;
  const key = (r) => r.parent + ' ' + r.parentMarker + (r.dashed ? '..' : '--') + r.childMarker + ' ' + r.child;
  // 뷰어의 Mermaid 소스는 엔티티에 별칭을 쓸 수 있어 관계는 범위(scope)에서 직접 만든다.
  const lines = erd.relationLines(ix, scope);
  const rendered = lines.map((r) => ({
    parent: nameOf(r.parentId), child: nameOf(r.childId), parentMarker: r.parentMarker, childMarker: r.childMarker, dashed: r.dashed, label: r.label
  }));
  assert.deepEqual(rendered.map(key).sort(), want.map(key).sort());
  // 라벨: 뷰어가 덧붙이는 것(다른 이름의 상대 컬럼, 고정 컬럼)이 없는 관계는 글자까지 같다
  const wantLabels = new Map(want.map((r) => [key(r) + ' ' + r.label.replace(/ \(.*$/, ''), r.label]));
  let sameLabels = 0;
  lines.forEach((l, i) => {
    const r = l.relation;
    if (r.fixedColumns.length || (r.source === 'INFERRED' && r.columns.join() !== r.refColumns.join())) return;
    const k = key(rendered[i]) + ' ' + l.label.replace(/ \(.*$/, '');
    assert.equal(l.label, wantLabels.get(k), key(rendered[i]));
    sameLabels++;
  });
  if (SAMPLE) assert.ok(sameLabels >= want.length - 4, '대부분의 라벨이 표준 산출물과 같다: ' + sameLabels + '/' + want.length);
  assert.equal(mine.length, want.length, 'Mermaid 소스의 관계 줄 수');
  // 과제에 적힌 샘플의 구성: DDL 8, 추론 N:1 9, 방향 미상 1 (그래프에서 직접 세어 견준다)
  // 시스템 카탈로그(systemCatalog=true)와 이어진 관계는 ERD에 그리지 않는다(F-013-R29)
  const catalog = (id) => { const n = ix.node(id); return !!n && n.attrs.systemCatalog === true; };
  const refs = ix.edges.filter((e) => e.kind === 'REFERENCES' && !catalog(e.from) && !catalog(e.to));
  const count = (f) => want.filter(f).length;
  // 현재 정의에 없는 upgrade 외래 키는 source가 DDL이어도 엣지 신뢰도가 INFERRED다(fromUpgrade=true): 점선이다(F-013-AC29)
  const dashedEdge = (e) => e.attrs.source === 'INFERRED' || (e.confidence === 'INFERRED' && (e.attrs.source == null || e.attrs.fromUpgrade === true));
  assert.equal(count((r) => !r.dashed), refs.filter((e) => !dashedEdge(e)).length);
  assert.equal(count((r) => r.dashed && r.label.includes('방향 미상')), refs.filter((e) => dashedEdge(e) && e.attrs.directionKnown === false).length);
  assert.equal(count((r) => r.dashed && !r.label.includes('방향 미상')), refs.filter((e) => dashedEdge(e) && e.attrs.directionKnown !== false).length);
  want.filter((r) => r.label.includes('현재 정의에 없음')).forEach((r) => assert.ok(r.dashed && r.label.includes('(upgrade 스크립트, 현재 정의에 없음)'), r.label));
  // 고정 컬럼(F-018-R9)은 뷰어의 라벨에 나온다
  const fixed = refs.filter((e) => Array.isArray(e.attrs.fixedValues) && e.attrs.fixedValues.length);
  if (SAMPLE) assert.ok(fixed.length > 0, '샘플에 공통코드 관계가 있다');
  const source = erd.toMermaid(ix, scope, {}).source;
  fixed.forEach((e) => {
    // 문자열 값은 따옴표로, 바인드(?)와 숫자는 그대로 적는다
    const text = erd.fixedText(e.attrs.fixedColumns, e.attrs.fixedValues);
    assert.ok(text && source.includes(text.replace(/"/g, "'")), nameOf(e.from) + ' → ' + nameOf(e.to) + ': ' + text);
  });
});

test('sample-shop F-016-AC57 ERD 영역: 표준 산출물과 같은 Mermaid 글자를 만들고(erd/erd.mmd), 한도를 넘으면 영역 목록과 영역 다이어그램이 erd/index.md·erd-<영역>.mmd와 같다', { skip: need('erd/erd.mmd') }, () => {
  const { ix } = load();
  const model = erd.exportModel(ix, {});
  const mine = erd.exportMermaid(ix, model.entities, model.relations, { systemCatalogs: model.systemCatalogs.length });
  // 산출물을 `export.mermaid.maxChars`를 바꿔 만들었으면 같은 값을 CODEATLAS_MERMAID_MAX_CHARS로 준다(기본 45,000).
  const maxChars = Number(process.env.CODEATLAS_MERMAID_MAX_CHARS) || undefined;
  const split = erd.splitAreas(ix, { maxChars });
  const lines = out.read('erd/erd.mmd').split('\n');
  // 한도를 넘은 전체 다이어그램에는 첫 줄 아래에 안내 주석이 한 줄 들어 있다
  if (split.oversize) assert.ok(lines.splice(1, 1)[0].includes('주의'), '한도를 넘는다는 주석');
  assert.equal(mine, lines.join('\n'), '전체 다이어그램의 글자');
  assert.equal(split.fullChars, mine.length);
  assert.equal(out.exists('erd/index.md'), split.oversize, 'erd/index.md는 전체가 한도(' + split.maxChars + '자)를 넘을 때만 있다: ' + split.fullChars + '자');
  if (!split.oversize) return;
  const index = out.read('erd/index.md');
  const rows = out.tableRows(out.reportSection(index, '영역').filter((l) => l.startsWith('|')));
  // 영역의 표시 이름은 논리명(물리명)이고(표 안이라 `|`는 `\|`로 적힌다), 허브가 있으면 허브와의 관계를 따로 센 열이 하나 더 있다(F-013-AC24, AC27)
  const cell = (text) => String(text).replace(/[\r\n\t]+/g, ' ').trim().replace(/\|/g, '\\|');
  const labelOf = new Map(split.areas.map((a) => [a.name, cell(a.label)]));
  assert.deepEqual(split.areas.map((a) => [cell(a.label), String(a.tables.length), String(a.innerRelations), String(a.outerRelations)]
    .concat(split.hubs.length ? [String(a.hubRelations)] : [], ['[erd-' + a.fileName + '.mmd](erd-' + a.fileName + '.mmd)', String(a.chars)])), rows,
  '영역(표시 이름, 테이블 수, 영역 안 관계, 다른 영역과의 관계, 허브와의 관계, 파일, 글자 수)');
  assert.equal(index.includes('| 허브와의 관계 |'), split.hubs.length > 0);
  const cross = out.tableRows(out.reportSection(index, '영역 간 관계').filter((l) => l.startsWith('|')));
  assert.deepEqual(split.crossRelations.map((c) => [labelOf.get(c.from), labelOf.get(c.to), String(c.count)]), cross, '영역 간 관계');
  // "다른 영역과의 관계"는 허브와의 관계를 빼고 센 것이라 영역 간 관계 수의 두 배와 같다(관계 하나를 양쪽 영역에서 센다)
  assert.equal(split.areas.reduce((sum, a) => sum + a.outerRelations, 0), 2 * split.crossRelations.reduce((sum, c) => sum + c.count, 0), '다른 영역과의 관계의 합');
  // 관계로 묶은 영역이 있으면 허브 테이블(어느 영역에도 넣지 않은, 많은 테이블이 참조하는 테이블)을 따로 적는다
  const hubSection = out.reportSection(index, '허브 테이블');
  assert.deepEqual(split.hubs.map((h) => [h.name, String(h.referencedBy)]), hubSection ? out.tableRows(hubSection.filter((l) => l.startsWith('|'))) : [], '허브 테이블');
  assert.equal(index.includes('관계로 이어진 테이블끼리'), split.byRelations);
  // 테이블 30개를 넘는 관계 묶음을 나눈 중심 테이블과, 이름의 공통 접두사로 묶은 영역(F-013-AC25, AC26)
  const centerSection = out.reportSection(index, '중심 테이블');
  assert.deepEqual(split.centers.map((c) => [c.name, String(c.referencedBy), c.areas.map((name) => labelOf.get(name)).join(', ')]),
    centerSection ? out.tableRows(centerSection.filter((l) => l.startsWith('|'))) : [], '중심 테이블');
  assert.equal(index.includes('이름의 공통 접두사'), split.byNames);
  if (split.byRelations || split.byNames) {
    split.areas.filter((a) => a.name.indexOf('*') >= 0 || split.centers.some((c) => c.name === a.name)).forEach((a) => {
      assert.ok(a.tables.length <= erd.MAX_AREA_TABLES, a.name + ': 테이블 ' + a.tables.length + '개');
    });
  }
  // 영역 다이어그램: 영역의 테이블 + 관계로 이어진 다른 영역의 테이블(이름만) + 한쪽 끝이라도 영역 안인 관계
  split.areas.forEach((a) => {
    const entities = model.entities.filter((e) => a.ids.has(e.id) || a.neighbors.has(e.id));
    const relations = model.relations.filter((r) => a.ids.has(r.childId) || a.ids.has(r.parentId));
    assert.equal(erd.exportMermaid(ix, entities, relations, { bare: a.neighbors }), out.read('erd/erd-' + a.fileName + '.mmd'), 'erd-' + a.fileName + '.mmd');
    // 뷰어가 그 영역을 그릴 때의 범위도 같은 테이블이다
    const scope = erd.selectScope(ix, { area: a.name, maxChars });
    assert.deepEqual(scope.tables.map((t) => t.id).sort(), entities.map((e) => e.id).sort(), a.name + ': 뷰어의 범위');
  });
});

// ---------------------------------------------------------------- 진단·미해결 (F-016-AC40)

test('sample-shop F-016-AC40 report.md의 "Java 호출자가 없는 매퍼 구문"과 "대상 테이블을 정적으로 알 수 없는 구문"이 뷰어에도 같은 목록으로 나온다', { skip: need('report.md') }, () => {
  const { raw, ix } = load();
  const report = out.read('report.md');
  const r = diag.collect(ix);
  const codeSpans = (line) => (line.match(/`[^`]*`/g) || []).map((x) => x.slice(1, -1));
  const uncalled = out.reportSection(report, 'Java 호출자가 없는 매퍼 구문').filter((l) => l.startsWith('- ')).map((l) => {
    const spans = codeSpans(l);
    return spans[0] + ' @ ' + spans[1] + ' <- ' + spans.slice(2).join(', ');
  });
  assert.deepEqual(r.uncalledStatements.map((x) => x.statementLabel + ' @ ' + x.location + ' <- ' + x.executors.map((m) => m.label).join(', ')), uncalled);
  const dynamic = out.reportSection(report, '대상 테이블을 정적으로 알 수 없는 구문').filter((l) => l.startsWith('| `')).map((l) => {
    const cells = l.split('|').map((c) => c.trim());
    return cells[1].slice(1, -1) + ' ' + cells[2] + ' ' + cells[3].replace(/`/g, '');
  });
  assert.deepEqual(r.dynamicStatements.map((d) => d.id + ' ' + d.access + ' ' + d.location), dynamic);
  // 새 진단 코드도 필터에 나온다
  const codes = Array.from(new Set(raw.diagnostics.map((d) => d.code))).sort();
  assert.deepEqual(r.codes.map((c) => c.code), codes);
  if (SAMPLE) assert.ok(codes.includes('CALLS_SKIPPED'), '샘플에 CALLS_SKIPPED 진단이 있다');
  const header = out.reportSection(report, '진단 (').filter((l) => /^\| [A-Z_]+ \| \d+ \| \d+ \| \d+ \| \d+ \|$/.test(l)).map((l) => l.split('|')[1].trim() + ' ' + l.split('|')[5].trim());
  assert.deepEqual(r.codes.map((c) => c.code + ' ' + c.count), header, '코드별 건수가 report.md의 표와 같다');
});

test('sample-shop F-016-AC58 report.md의 "미해결 참조" 분류별 건수, "DDL에 없는 테이블"(구문 수와 의심 표시), "데이터 적재 스크립트"가 뷰어의 보기와 같다', { skip: need('report.md') }, () => {
  const { ix } = load();
  const report = out.read('report.md');
  const r = diag.collect(ix);
  // 미해결 참조: 분류별 건수(마무리 단계가 만든 노드도 같은 분류로 묶인다)
  const section = out.reportSection(report, '미해결 참조');
  const first = section.findIndex((l) => l.startsWith('| 분류'));
  const counts = first < 0 ? [] : out.tableRows(section.slice(first, section.findIndex((l, i) => i > first && !l.startsWith('|'))));
  assert.deepEqual(r.categories.map((c) => [c.category, String(c.count)]), counts, '분류별 건수');
  // DDL에 없는 테이블
  const undeclared = out.tableRows(out.reportSection(report, 'DDL에 없는 테이블').filter((l) => l.startsWith('|')));
  assert.deepEqual(r.undeclaredTables.map((t) => [t.name, String(t.statementCount), t.statements.map((x) => '`' + x.label + '`').join(', ').replace(/^`|`$/g, ''), t.note]), undeclared);
  // 다른 방언 파일에서 보충한 구문: 그런 구문이 있을 때만 절이 있다
  const borrowed = out.reportSection(report, '다른 방언 파일에서 보충한 구문');
  assert.equal(borrowed != null, r.borrowedStatements.length > 0, '보충한 구문 절의 유무');
  if (borrowed) assert.deepEqual(r.borrowedStatements.map((x) => [x.statementLabel, x.borrowedFrom, x.location || '-']), out.tableRows(borrowed.filter((l) => l.startsWith('|'))));
  // 데이터 적재 스크립트: 적재 구문이 있을 때만 절이 있다
  const loads = out.reportSection(report, '데이터 적재 스크립트');
  assert.equal(loads != null, r.dataLoadSummary != null, '절의 유무');
  if (loads) {
    const summary = loads.find((l) => l.startsWith('파일 '));
    assert.ok(summary.startsWith(r.dataLoadSummary.text + '. '), summary + ' ≠ ' + r.dataLoadSummary.text);
    assert.ok(summary.includes('구문 ' + r.dataLoadSummary.statements + '개다'), summary);
    const files = out.tableRows(loads.filter((l) => l.startsWith('|')));
    // report.md는 파일을 20개까지만 싣는다(export.report.maxItems)
    // 다른 방언 파일의 묶음(borrowedFrom)은 파일 이름 뒤에 방언을 밝힌다(칸의 코드 표시가 파일 이름에서 닫힌다)
    assert.deepEqual(r.dataLoadFiles.slice(0, files.length).map((f) => [f.file + (f.borrowedNote ? '` (' + f.borrowedNote + ')' : ''), String(f.tableCount), f.rowsText]), files);
    const borrowedLine = loads.find((l) => l.startsWith('이 가운데 묶음'));
    assert.equal(borrowedLine != null, !!r.dataLoadSummary.borrowedText, '다른 방언 파일의 묶음 수를 적은 줄의 유무');
    if (borrowedLine) assert.ok(borrowedLine.startsWith(r.dataLoadSummary.borrowedText.replace(/\)\.$/, '')), borrowedLine + ' ≠ ' + r.dataLoadSummary.borrowedText);
    assert.ok(files.length === r.dataLoadFiles.length || files.length === 20);
  }
});

test('sample-shop F-016-AC68 report.md의 "화면 요청"(서버 요청, 연결됨(확정·추론)·미연결, 외부 URL, 정적 페이지 링크, 클라이언트 라우트, 미연결 요청 목록)과 "SQL과 DDL의 컬럼 불일치"가 뷰어의 보기와 같다', { skip: need('report.md') }, () => {
  const { ix } = load();
  const report = out.read('report.md');
  const r = diag.collect(ix);
  // 화면 요청: 요청이 있을 때만 절이 있다. 표의 행(구분, 건수)과 연결률 문장이 같다
  const requests = out.reportSection(report, '화면 요청');
  assert.equal(requests != null, r.requestStats.total > 0, '화면 요청 절의 유무');
  if (requests) {
    const title = report.split('\n').find((l) => l.startsWith('## 화면 요청'));
    assert.equal(title, '## 화면 요청 (' + r.requestStats.total + ')');
    const cut = requests.findIndex((l) => l.startsWith('### 미연결 요청'));
    const head = cut < 0 ? requests : requests.slice(0, cut);
    assert.deepEqual(r.requestRows.map((x) => [x.label, String(x.count)]), out.tableRows(head.filter((l) => l.startsWith('|'))), '구분별 건수');
    assert.equal(r.requestStats.summaryText, requests.find((l) => l.startsWith('서버 요청 ')) || '', '요약 문장');
    const note = head.find((l) => l.includes('분모에서 뺐다')) || '';
    assert.ok(r.requestStats.noteText ? note.startsWith(r.requestStats.noteText) : note === '', '분모에서 뺀 것의 안내: ' + note);
    // 미연결 요청: 위치순으로 30개까지(파일:줄, 요청)
    assert.equal(cut >= 0, r.unmatchedRequests.length > 0, '미연결 요청 절의 유무');
    if (cut >= 0) {
      const listed = out.tableRows(requests.slice(cut).filter((l) => l.startsWith('|')));
      assert.deepEqual(r.unmatchedRequests.slice(0, 30).map((x) => [x.location || '-', x.label]), listed);
      assert.equal(listed.length, Math.min(30, r.unmatchedRequests.length));
    }
  }
  // SQL과 DDL의 컬럼 불일치: 그런 컬럼이 있을 때만 절이 있다. 테이블·컬럼·구문 수·구문(5개까지)이 같은 순서로 나온다
  const columns = out.reportSection(report, 'SQL과 DDL의 컬럼 불일치');
  assert.equal(columns != null, r.columnMismatches.length > 0, '컬럼 불일치 절의 유무');
  if (columns) {
    const title = report.split('\n').find((l) => l.startsWith('## SQL과 DDL의 컬럼 불일치'));
    assert.equal(title, '## SQL과 DDL의 컬럼 불일치 (' + r.columnMismatches.length + ')');
    const rows = out.tableRows(columns.filter((l) => l.startsWith('|')));
    assert.deepEqual(r.columnMismatches.map((c) => [c.table, c.column, String(c.statementCount), c.statements.map((x) => '`' + x.label + '`').join(', ').replace(/^`|`$/g, '')]), rows);
    // 테이블 명세(erd/tables.md)의 같은 목록과도 같다
    if (out.exists('erd/tables.md')) {
      const spec = out.read('erd/tables.md');
      r.columnMismatches.forEach((c) => {
        const line = '- `' + c.column + '` — 구문 ' + c.statementCount + '개' + (c.statements.length ? ': ' + c.statements.map((x) => '`' + x.label + '`').join(', ') : '');
        assert.ok(spec.includes('\n' + line + '\n'), 'tables.md에 없는 줄: ' + line);
      });
    }
  }
});

test('sample-shop F-016-AC66 crud/README.md가 있고 뷰어의 범례와 같은 표기(대문자·소문자, ?, (동적))를 설명한다', { skip: need('crud/README.md') }, () => {
  const readme = out.read('crud/README.md');
  ['대문자', '소문자', '`?`', '`(동적)`', '데이터 적재'].forEach((word) => assert.ok(readme.includes(word), 'README에 없는 말: ' + word));
  const keys = crud.LEGEND.map((l) => l.key);
  assert.ok(keys.includes('?') && keys.includes('대문자') && keys.includes('소문자') && keys.includes('(동적)'), keys.join(' '));
  // README의 파일 표에 적힌 파일이 실제로 있다
  const listed = readme.split('\n').filter((l) => /^\| `table-[a-z]+\.csv` \|/.test(l)).map((l) => /`(table-[a-z]+\.csv)`/.exec(l)[1]);
  assert.ok(listed.length > 0);
  assert.deepEqual(listed.slice().sort(), out.list('crud').filter((f) => f.endsWith('.csv')));
});
