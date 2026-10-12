'use strict';
// 압축해 내장한 블록(F-016-R48): gzip으로 압축하고 base64로 감싼 글자를 뷰어가 풀어 켠다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { assemble, scriptBlocks, pack, unpack } = require('./tools/assemble.js');
const { boot, launch, sleep } = require('./tools/boot.js');
const { sampleGraph } = require('./fixtures/sample-graph.js');
const { core } = require('./helpers.js');

const embed = core('embed');

test('F-016-AC94 내장 블록 풀기: base64로 감싼 gzip이 원래 글자로 돌아온다(한글, 큰 글자, 줄바꿈이 낀 base64)', async () => {
  const text = JSON.stringify({ name: '주문 서비스 </script>   😀', list: Array.from({ length: 50000 }, (_, i) => 'java:method:com.shop.OrderService#m' + i + '()') });
  assert.ok(text.length > 1500000);
  const block = pack(text);
  assert.ok(block.length < text.length / 5, '반복이 많은 글자는 크게 줄어든다');
  assert.equal(await embed.inflateText(block), text);
  assert.equal(await embed.inflateText(block.replace(/(.{76})/g, '$1\n')), text);
  assert.equal(await embed.inflateText(pack('')), '');
});

test('F-016-AC94 내장 블록 풀기: gzip이 아니거나 잘린 블록은 예외로 알린다', async () => {
  await assert.rejects(embed.inflateText(Buffer.from('gzip이 아닌 글자').toString('base64')));
  const block = pack(JSON.stringify(sampleGraph()));
  await assert.rejects(embed.inflateText(block.substring(0, block.length >> 1)));
  await assert.rejects(embed.inflateText(''));
});

test('F-016-AC94 화면: 압축한 데이터 블록을 풀어 켜지고, 푸는 동안에는 안내를 보이며, 푼 뒤에는 블록의 글자를 비운다', async () => {
  const html = assemble(JSON.stringify(sampleGraph()), '');
  const data = scriptBlocks(html).find((b) => b.id === 'codeatlas-data');
  assert.match(data.attrs, /type="text\/plain"/, '실행하지 않는 블록이다');
  assert.match(data.text, /^[A-Za-z0-9+/]+=*$/, '블록에는 base64 글자만 있다');
  const window = launch(html);
  assert.ok(window.document.getElementById('app').textContent.includes('푸는 중'), '켜지기 전에는 안내가 보인다');
  assert.equal(window.document.querySelectorAll('.tab').length, 0);
  await window.CodeAtlas.ready;
  assert.deepEqual(window.errors, []);
  assert.equal(window.document.querySelectorAll('.tab').length, 6);
  assert.equal(window.CodeAtlas.app.index.nodes.length, sampleGraph().nodes.length);
  assert.equal(window.document.getElementById('codeatlas-data').textContent, '');
});

test('F-016-AC94 화면: 압축을 풀지 못하는 브라우저에서는 필요한 브라우저 버전을 알린다', async () => {
  const window = await boot(assemble(JSON.stringify(sampleGraph()), ''), { noDecompression: true });
  const fatal = window.document.querySelector('.fatal').textContent;
  assert.ok(fatal.includes('읽지 못했다'), fatal);
  assert.ok(fatal.includes('DecompressionStream') && fatal.includes('Chrome') && fatal.includes('Firefox 113') && fatal.includes('Safari 16.4'), fatal);
  assert.equal(window.document.querySelectorAll('.tab').length, 0);
});

test('F-016-AC94 화면: 깨진 데이터 블록(gzip이 아님, JSON이 아님)이면 읽지 못했다고 알린다', async () => {
  const html = assemble(JSON.stringify(sampleGraph()), '');
  const data = scriptBlocks(html).find((b) => b.id === 'codeatlas-data');
  const notGzip = await boot(html.replace(data.text, Buffer.from('{"nodes":[]}').toString('base64')));
  assert.ok(notGzip.document.querySelector('.fatal').textContent.includes('읽지 못했다'));
  const notJson = await boot(assemble('{ not json', ''));
  assert.ok(notJson.document.querySelector('.fatal').textContent.includes('읽지 못했다'));
});

// 가짜 DOM에서는 진짜 Mermaid가 돌지 않으므로, 같은 모양(initialize, render)의 작은 번들을 넣어 푸는 길을 본다.
const FAKE_MERMAID = 'globalThis.mermaid = { calls: [], initialize: function (c) { this.config = c; },'
  + ' render: function (id, source) { this.calls.push(source); return Promise.resolve({ svg: "<svg viewBox=\\"0 0 10 20\\"></svg>" }); } };'
  + ' globalThis.mermaidLoads = (globalThis.mermaidLoads || 0) + 1; // </script> 한글';

test('F-016-AC95 화면: 압축한 Mermaid 블록은 다이어그램을 처음 그릴 때 한 번만 풀어 실행한다', async () => {
  const graph = sampleGraph();
  const table = graph.nodes.find((n) => n.kind === 'DB_TABLE');
  const html = assemble(JSON.stringify(graph), FAKE_MERMAID);
  const block = scriptBlocks(html).find((b) => b.id === 'codeatlas-vendor-mermaid');
  assert.match(block.text, /^[A-Za-z0-9+/]+=*$/);
  assert.equal(unpack(block.text), FAKE_MERMAID);
  const window = await boot(html);
  assert.equal(window.mermaid, undefined, '다이어그램을 그리기 전에는 실행하지 않는다');
  assert.notEqual(window.document.getElementById('codeatlas-vendor-mermaid').textContent, '');
  window.CodeAtlas.app.navigate('erd', { table: table.id });
  await sleep(100);
  assert.deepEqual(window.errors, []);
  assert.equal(window.mermaidLoads, 1);
  assert.equal(window.mermaid.calls.length, 1);
  assert.ok(window.mermaid.calls[0].includes('erDiagram'));
  assert.equal(window.document.getElementById('codeatlas-vendor-mermaid').textContent, '');
  assert.ok(!window.document.querySelector('.view-erd').textContent.includes('Mermaid가 들어 있지 않아'));
  // 다른 다이어그램을 그려도 다시 풀지 않는다
  window.CodeAtlas.app.navigate('classdiagram', {});
  await sleep(100);
  assert.equal(window.mermaidLoads, 1);
  assert.deepEqual(window.errors, []);
});

test('F-016-AC95 화면: Mermaid 블록이 비었거나 풀리지 않으면 그림 대신 소스를 보인다', async () => {
  const graph = sampleGraph();
  const table = graph.nodes.find((n) => n.kind === 'DB_TABLE');
  const empty = await boot(assemble(JSON.stringify(graph), ''));
  empty.CodeAtlas.app.navigate('erd', { table: table.id });
  await sleep(100);
  assert.ok(empty.document.querySelector('.view-erd').textContent.includes('Mermaid가 들어 있지 않아'));
  assert.ok(empty.document.querySelector('.view-erd .dg-source').textContent.includes('erDiagram'));
  assert.deepEqual(empty.errors, []);

  const html = assemble(JSON.stringify(graph), FAKE_MERMAID);
  const block = scriptBlocks(html).find((b) => b.id === 'codeatlas-vendor-mermaid');
  const broken = await boot(html.replace(block.text, Buffer.from('gzip이 아님').toString('base64')));
  broken.CodeAtlas.app.navigate('erd', { table: table.id });
  await sleep(100);
  assert.ok(broken.document.querySelector('.view-erd .dg-source').textContent.includes('erDiagram'));
  assert.ok(broken.document.querySelector('.view-erd').textContent.includes('Mermaid를 실행하지 못해'));
  assert.equal(broken.errors.length, 1);
  assert.ok(broken.errors[0].includes('Mermaid'));
});

// ViewerExporter(Java)가 만든 파일이 있으면 그 블록도 뷰어의 풀기로 읽는다. `mvn -pl codeatlas-viewer test` 뒤에 생긴다.
const built = process.env.CODEATLAS_VIEWER_HTML || path.join(__dirname, '..', '..', '..', 'target', 'viewer-sample', 'viewer.html');
test('F-016-AC93 ViewerExporter가 만든 viewer.html: 데이터와 Mermaid 블록이 base64로 감싼 gzip이고 뷰어의 풀기로 읽힌다', { skip: !fs.existsSync(built) && 'target/viewer-sample/viewer.html 없음 (mvn test를 먼저 돌린다)' }, async () => {
  const blocks = scriptBlocks(fs.readFileSync(built, 'utf8'));
  const data = blocks.find((b) => b.id === 'codeatlas-data');
  const mermaid = blocks.find((b) => b.id === 'codeatlas-vendor-mermaid');
  assert.match(data.text, /^[A-Za-z0-9+/]+=*$/);
  assert.match(mermaid.text, /^[A-Za-z0-9+/]+=*$/);
  const json = await embed.inflateText(data.text);
  assert.equal(json, unpack(data.text));
  assert.ok(JSON.parse(json).nodes.length > 0);
  const bundle = await embed.inflateText(mermaid.text);
  assert.ok(bundle.includes('erDiagram') && bundle.includes('classDiagram'));
  assert.ok(mermaid.text.length < bundle.length / 2, 'Mermaid 블록이 번들 원본의 절반보다 작다');
});
