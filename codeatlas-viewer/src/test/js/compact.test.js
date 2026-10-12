'use strict';
// 뷰어 내장 형식(F-016-R49): 노드 ID와 반복되는 글자를 한 번만 적고 엣지는 번호로 가리키는 줄인 JSON. 뷰어가 graph.json 모양으로 되돌린다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { assemble, assembleRaw, scriptBlocks, unpack } = require('./tools/assemble.js');
const { boot } = require('./tools/boot.js');
const { compactGraph } = require('./tools/compact.js');
const { sampleGraph } = require('./fixtures/sample-graph.js');
const { core, bigGraph } = require('./helpers.js');

const compact = core('compact');

/** graph.json이 늘 적는 칸(location, attrs, confidence)을 채운 모양. 손으로 만든 픽스처에는 빠진 칸이 있다. */
function normalized(graph) {
  const loc = (l) => (l ? { file: l.file, startLine: l.startLine, startColumn: l.startColumn, endLine: l.endLine, endColumn: l.endColumn } : null);
  const text = (v) => (v === undefined ? null : v);
  return {
    schemaVersion: graph.schemaVersion,
    project: text(graph.project),
    nodes: graph.nodes.map((n) => ({ id: n.id, kind: n.kind, name: text(n.name), qualifiedName: text(n.qualifiedName), location: loc(n.location), attrs: n.attrs || {} })),
    edges: graph.edges.map((e) => ({ from: e.from, to: e.to, kind: e.kind, confidence: e.confidence || 'RESOLVED', location: loc(e.location), attrs: e.attrs || {} })),
    diagnostics: graph.diagnostics.map((d) => ({ severity: d.severity, code: d.code, message: text(d.message), location: loc(d.location) }))
  };
}

test('F-016-AC97 뷰어 내장 형식 풀기: 번호로 적은 종류·파일·노드와 생략한 기본값이 graph.json 모양으로 돌아온다', () => {
  const data = {
    compact: 1,
    schemaVersion: 1,
    project: { name: 'demo', root: '/work/demo', analyzedAt: '2026-10-12T00:00:00Z', options: null },
    strings: ['JAVA_TYPE', 'src/A.java', 'JAVA_METHOD', 'DB_TABLE', 'CONTAINS', 'CALLS', 'UNRESOLVED', 'WRITES', 'INFERRED', 'WARN', 'PARSE_FAILED', 'INFO', 'NOTE', 'RESOLVED'],
    nodes: [
      ['java:type:com.a.A', 0, 'A', 'com.a.A', [1, 5, 1, 40, 2], { typeKind: 'class', modifiers: ['public'] }],
      ['java:method:com.a.A#run()', 2, 'run', 'com.a.A#run()', [1, 10]],
      ['db:table:T', 3, 'T', null],
      ['db:table:U', 3, null, 'U', 0, { declared: false }],
      ['file:src/A.java', 0, 'A.java', 'src/A.java', [1]]
    ],
    edges: [
      [0, 1, 4],
      [1, 'java:method:x.Missing#go()', 5, 6, [1, 12, 3]],
      [1, 2, 7, 13, 0, { op: 'INSERT' }],
      [2, 3, 7, 8, [1, 20, 4, 22], { columns: ['A', 'B'], evidenceCount: 2 }]
    ],
    diagnostics: [
      [9, 10, '구문을 건너뜀', [1, 3]],
      [11, 12, null]
    ]
  };
  assert.deepEqual(compact.expand(data), {
    schemaVersion: 1,
    project: { name: 'demo', root: '/work/demo', analyzedAt: '2026-10-12T00:00:00Z', options: null },
    nodes: [
      { id: 'java:type:com.a.A', kind: 'JAVA_TYPE', name: 'A', qualifiedName: 'com.a.A', location: { file: 'src/A.java', startLine: 5, startColumn: 1, endLine: 40, endColumn: 2 }, attrs: { typeKind: 'class', modifiers: ['public'] } },
      { id: 'java:method:com.a.A#run()', kind: 'JAVA_METHOD', name: 'run', qualifiedName: 'com.a.A#run()', location: { file: 'src/A.java', startLine: 10, startColumn: 0, endLine: 10, endColumn: 0 }, attrs: {} },
      { id: 'db:table:T', kind: 'DB_TABLE', name: 'T', qualifiedName: null, location: null, attrs: {} },
      { id: 'db:table:U', kind: 'DB_TABLE', name: null, qualifiedName: 'U', location: null, attrs: { declared: false } },
      { id: 'file:src/A.java', kind: 'JAVA_TYPE', name: 'A.java', qualifiedName: 'src/A.java', location: { file: 'src/A.java', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 }, attrs: {} }
    ],
    edges: [
      { from: 'java:type:com.a.A', to: 'java:method:com.a.A#run()', kind: 'CONTAINS', confidence: 'RESOLVED', location: null, attrs: {} },
      { from: 'java:method:com.a.A#run()', to: 'java:method:x.Missing#go()', kind: 'CALLS', confidence: 'UNRESOLVED', location: { file: 'src/A.java', startLine: 12, startColumn: 3, endLine: 12, endColumn: 3 }, attrs: {} },
      { from: 'java:method:com.a.A#run()', to: 'db:table:T', kind: 'WRITES', confidence: 'RESOLVED', location: null, attrs: { op: 'INSERT' } },
      { from: 'db:table:T', to: 'db:table:U', kind: 'WRITES', confidence: 'INFERRED', location: { file: 'src/A.java', startLine: 20, startColumn: 4, endLine: 22, endColumn: 4 }, attrs: { columns: ['A', 'B'], evidenceCount: 2 } }
    ],
    diagnostics: [
      { severity: 'WARN', code: 'PARSE_FAILED', message: '구문을 건너뜀', location: { file: 'src/A.java', startLine: 3, startColumn: 0, endLine: 3, endColumn: 0 } },
      { severity: 'INFO', code: 'NOTE', message: null, location: null }
    ]
  });
});

test('F-016-AC97 뷰어 내장 형식 풀기: 모르는 형식(판이 다르거나 graph.json 그대로)은 예외로 알린다', () => {
  assert.throws(() => compact.expand(sampleGraph()), /내장 형식/);
  assert.throws(() => compact.expand({ compact: 2, nodes: [], edges: [], diagnostics: [], strings: [] }), /내장 형식/);
  assert.throws(() => compact.expand(null), /내장 형식/);
});

test('F-016-AC97 줄였다 풀면 같은 그래프다: 픽스처와 큰 합성 그래프, 줄인 JSON은 graph.json의 절반보다 작다', () => {
  for (const graph of [sampleGraph(), bigGraph(20000)]) {
    assert.deepEqual(compact.expand(compactGraph(graph)), normalized(graph));
  }
  const big = bigGraph(20000);
  const before = JSON.stringify(normalized(big)).length;
  const after = JSON.stringify(compactGraph(big)).length;
  assert.ok(after < before / 2, '줄인 JSON ' + after + '자, graph.json 모양 ' + before + '자');
  // 풀어 낸 엣지의 양 끝은 노드의 id와 같은 문자열을 함께 쓴다(엣지마다 ID 글자를 따로 들고 있지 않다)
  const expanded = compact.expand(compactGraph(big));
  assert.equal(expanded.edges[0].from, expanded.nodes[1].id);
});

test('F-016-AC97 화면: graph.json을 그대로 넣은 데이터 블록은 읽지 못했다고 알린다', async () => {
  const window = await boot(assembleRaw(JSON.stringify(sampleGraph()), ''));
  const fatal = window.document.querySelector('.fatal').textContent;
  assert.ok(fatal.includes('읽지 못했다') && fatal.includes('내장 형식'), fatal);
});

test('F-016-AC97 화면: 조립기는 데이터 블록에 줄인 형식을 넣고, 뷰어는 그것을 풀어 같은 색인을 만든다', async () => {
  const graph = sampleGraph();
  const html = assemble(JSON.stringify(graph), '');
  const data = JSON.parse(unpack(scriptBlocks(html).find((b) => b.id === 'codeatlas-data').text));
  assert.equal(data.compact, 1);
  assert.ok(Array.isArray(data.nodes[0]) && Array.isArray(data.strings));
  const window = await boot(html);
  assert.deepEqual(window.errors, []);
  const index = window.CodeAtlas.app.index;
  assert.equal(index.nodes.length, graph.nodes.length);
  assert.equal(index.edges.length, graph.edges.length);
  const want = normalized(graph);
  assert.deepEqual(JSON.parse(JSON.stringify(index.nodes.map((n) => ({ id: n.id, kind: n.kind, location: n.location })))),
    want.nodes.map((n) => ({ id: n.id, kind: n.kind, location: n.location })));
});

// ViewerExporter(Java)가 만든 파일이 있으면 그 블록을 풀어 픽스처와 견준다. `mvn -pl codeatlas-viewer test` 뒤에 생긴다.
const built = process.env.CODEATLAS_VIEWER_HTML || path.join(__dirname, '..', '..', '..', 'target', 'viewer-sample', 'viewer.html');
const fixture = path.join(__dirname, '..', 'resources', 'fixtures', 'sample-graph.json');
test('F-016-AC98 ViewerExporter가 만든 viewer.html: 데이터 블록을 풀면 픽스처 graph.json과 같고, 테스트 조립기가 같은 형식을 만든다', { skip: !fs.existsSync(built) && 'target/viewer-sample/viewer.html 없음 (mvn test를 먼저 돌린다)' }, () => {
  const block = scriptBlocks(fs.readFileSync(built, 'utf8')).find((b) => b.id === 'codeatlas-data');
  const text = unpack(block.text);
  const data = JSON.parse(text);
  assert.equal(data.compact, 1);
  const expanded = compact.expand(data);
  const original = JSON.parse(fs.readFileSync(fixture, 'utf8'));
  // GraphJson은 노드·엣지·진단을 정렬해 쓰고 project.options를 덧붙인다. 순서를 맞추고 픽스처에 있던 항목을 본다.
  const byKey = (a, b) => (JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0);
  const want = normalized(original);
  assert.deepEqual(expanded.nodes.slice().sort(byKey), want.nodes.slice().sort(byKey));
  assert.deepEqual(expanded.edges.slice().sort(byKey), want.edges.slice().sort(byKey));
  assert.deepEqual(expanded.diagnostics.slice().sort(byKey), want.diagnostics.slice().sort(byKey));
  assert.equal(expanded.schemaVersion, 1);
  for (const key of Object.keys(original.project)) assert.deepEqual(expanded.project[key], original.project[key], 'project.' + key);
  // Java가 쓴 것과 테스트 조립기(tools/compact.js)가 만든 것이 같다(글자 표의 순서까지): 화면 테스트가 실제 출력과 같은 형식으로 돈다
  assert.deepEqual(compactGraph(expanded), data);
  assert.ok(text.length < JSON.stringify(want).length / 2, '줄인 JSON이 graph.json 모양의 절반보다 작다');
});
