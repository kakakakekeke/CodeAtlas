'use strict';
// viewer.html을 더블클릭해 `file://`로 연 환경을 흉내 내 스크립트를 실제로 실행한다(F-016-AC71).
// 자산을 글자로 훑는 검사(F-016-AC2)보다 강하게 고정한다: `file:`에서 막히거나 브라우저마다 다르게 실패하는 API(fetch, XHR, 동적 import,
// Worker, 저장소, 쿠키, 주소 바꾸기, 클립보드)를 "건드리면 기록하고 예외를 던지는" 덫으로 바꿔 놓고 모든 화면을 한 바퀴 돈다.
// 덫에 걸린 접근이 하나도 없고 화면 오류가 없어야 한다. 실제 브라우저의 그리기(Mermaid가 만든 SVG, 배치)는 이 테스트로 확인하지 못한다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { assemble, scriptBlocks } = require('./tools/assemble.js');
const { createWindow } = require('./tools/fake-dom.js');
const { sleep } = require('./tools/boot.js');
const { sampleGraph } = require('./fixtures/sample-graph.js');

const FILE_URL = 'file:///C:/Users/dev/out/viewer.html';

/** `file:`로 연 창. 막히는 API는 접근을 trapped에 기록하고 SecurityError를 던진다. */
function fileWindow(data, hash) {
  const window = createWindow(data, { hash: hash || '' });
  const trapped = [];
  const deny = (name) => () => { trapped.push(name); const e = new Error('SecurityError: ' + name + ' is not available on file:'); e.name = 'SecurityError'; throw e; };
  const trapGetter = (target, name, label) => Object.defineProperty(target, name, { configurable: true, enumerable: true, get: deny(label || name) });
  // 주소: file: 문서의 origin은 "null"이다. 해시만 바꿀 수 있다.
  let currentHash = hash || '';
  window.location = {
    protocol: 'file:', origin: 'null', host: '', hostname: '', port: '', pathname: '/C:/Users/dev/out/viewer.html', search: '',
    get href() { return FILE_URL + currentHash; },
    set href(v) { trapped.push('location.href=' + v); },
    get hash() { return currentHash; },
    set hash(v) { currentHash = String(v); },
    assign: deny('location.assign'), replace: deny('location.replace'), reload: deny('location.reload')
  };
  // file:에서 pushState·replaceState는 브라우저마다 막힌다(Chrome은 SecurityError). 뷰어는 실패해도 화면을 바꿔야 한다.
  window.history = { pushState: deny('history.pushState'), replaceState() { const e = new Error('SecurityError: replaceState on file:'); e.name = 'SecurityError'; throw e; } };
  // 네트워크·모듈·워커
  ['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'Worker', 'SharedWorker', 'importScripts', 'openDatabase', 'BroadcastChannel'].forEach((name) => trapGetter(window, name));
  // 저장소: file:에서는 브라우저·설정에 따라 접근만 해도 예외가 난다.
  ['localStorage', 'sessionStorage', 'indexedDB', 'caches'].forEach((name) => trapGetter(window, name));
  trapGetter(window.document, 'cookie', 'document.cookie');
  // 안전하지 않은 문맥(file:)에는 clipboard·serviceWorker가 없을 수 있다. 접근해도 되지만 없어도 동작해야 한다.
  window.navigator = { sendBeacon: deny('navigator.sendBeacon') };
  trapGetter(window.navigator, 'serviceWorker', 'navigator.serviceWorker');
  window.trapped = trapped;
  return window;
}

/**
 * HTML의 스크립트를 file: 창에서 실행한다. 동적 import()는 실행 중에 덫으로 잡을 수 없어서(vm이 플래그 없이는 콜백을 부르지 않는다)
 * 실행하는 스크립트 블록의 글자에서 `import(` 호출을 찾아 덫 기록에 넣는다(주석과 문자열 안의 것은 뺀다).
 */
function bootAsFile(html, hash) {
  const blocks = scriptBlocks(html);
  const data = {};
  blocks.filter((b) => b.type === 'application/json').forEach((b) => { data[b.id] = b.text; });
  const window = fileWindow(data, hash);
  const context = vm.createContext(window);
  blocks.filter((b) => b.type !== 'application/json').forEach((b, i) => {
    vm.runInContext(b.text, context, { filename: 'viewer-script-' + i + '.js' });
    const code = b.text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:\\])\/\/[^\n]*/g, '$1').replace(/'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"/g, '""');
    const found = /(^|[^.\w$])import\s*\(\s*([^)]*)\)/.exec(code);
    if (found) window.trapped.push('import(' + found[2].replace(/^""$/, '…') + ')');
  });
  return window;
}

/** 모든 화면을 열고, 화면마다 대표 조작을 한 번씩 한다. */
async function tour(window) {
  const doc = window.document;
  const app = window.CodeAtlas.app;
  const index = app.index;
  const first = (kind) => { const list = index.nodesOfKind(kind); return list.length ? list[0].id : null; };
  // 탐색: 검색하고 상세를 연다
  const search = doc.querySelector('.view-explore .search');
  search.value = 'order';
  search.fire('input');
  await sleep(200);
  const hit = doc.querySelector('.view-explore .results .vrow');
  if (hit) hit.click();
  await sleep(20);
  // 콜트리: 기준을 고르고 모두 펼친 뒤 글로 복사한다(클립보드 API가 없는 길)
  const start = first('HTTP_ENDPOINT') || first('JAVA_METHOD');
  if (start) {
    app.navigate('calltree', { id: start });
    await sleep(20);
    doc.querySelector('.view-calltree .expand-all').click();
    await sleep(20);
    doc.querySelectorAll('.view-calltree .pane-head .btn').filter((b) => b.textContent === '글로 복사').forEach((b) => b.click());
    await sleep(20);
  }
  // 클래스 다이어그램, ERD(테이블을 골라 그리기), CRUD(네 기준과 CSV 저장), 진단(모든 보기)
  app.navigate('classdiagram', {});
  await sleep(50);
  app.navigate('erd', first('DB_TABLE') ? { table: first('DB_TABLE') } : {});
  await sleep(300);
  app.navigate('crud', {});
  await sleep(100);
  for (const button of doc.querySelectorAll('.view-crud .crud-kind button')) {
    button.click();
    await sleep(80);
  }
  doc.querySelector('.view-crud .crud-csv').click();
  app.navigate('diagnostics', {});
  await sleep(50);
  for (const button of doc.querySelectorAll('.view-diagnostics .diag-mode button')) {
    button.click();
    await sleep(30);
  }
  // 주소의 #이 바뀌면 그 화면으로 간다(해시는 file:에서도 바뀐다)
  window.location.hash = '#/explore';
  window.fire('hashchange');
  await sleep(20);
}

test('F-016-AC71 file://로 연 viewer.html: 막히는 API(fetch, XHR, 동적 import, Worker, 저장소, 쿠키, 주소 바꾸기)를 건드리지 않고 모든 화면이 켜진다', async () => {
  const window = bootAsFile(assemble(JSON.stringify(sampleGraph()), ''));
  assert.equal(window.location.protocol, 'file:');
  assert.deepEqual(window.errors, [], '시작할 때 오류가 없다');
  assert.equal(window.document.querySelectorAll('.tab').length, 6);
  await tour(window);
  assert.deepEqual(window.trapped, [], 'file:에서 막히는 API에 접근했다');
  assert.deepEqual(window.errors, []);
  assert.ok(window.document.querySelector('.view-explore').classList.contains('active'), '해시로 화면을 바꾼다');
  // 주소를 바꾸지 못해도(replaceState가 SecurityError) 화면은 바뀌어 있어야 한다: 탐색에서 고른 노드의 상세가 그려져 있다
  assert.ok(window.document.querySelector('.view-explore .detail-head'), '상세가 그려져 있다');
});

test('F-016-AC71 file://의 주소에 #이 붙어 있으면 그 화면과 노드를 바로 연다(해시만 읽는다)', async () => {
  const graph = sampleGraph();
  const table = graph.nodes.find((n) => n.kind === 'DB_TABLE');
  const window = bootAsFile(assemble(JSON.stringify(graph), ''), '#/explore?id=' + encodeURIComponent(table.id));
  await sleep(30);
  assert.deepEqual(window.trapped, []);
  assert.deepEqual(window.errors, []);
  assert.ok(window.document.querySelector('.view-explore .detail-head .id').textContent.includes(table.id));
});

test('F-016-AC71 덫이 실제로 걸리는지: 덫을 건드리는 스크립트를 넣으면 기록된다(이 테스트가 헛돌지 않는다는 확인)', async () => {
  const html = assemble(JSON.stringify(sampleGraph()), '').replace('</body>', '<script>try { window.localStorage.getItem("x"); } catch (e) {}\ntry { fetch("graph.json"); } catch (e) {}\nimport("./x.js").catch(function () {});</script></body>');
  const window = bootAsFile(html);
  await sleep(20);
  assert.deepEqual(window.trapped, ['localStorage', 'fetch', 'import(…)']);
});

// ViewerExporter(Java)가 만든 파일이 있으면 그것으로도 돈다. `mvn -pl codeatlas-viewer test` 뒤에 생긴다. Mermaid는 진짜 DOM이 필요해 빼고 켠다.
const built = process.env.CODEATLAS_VIEWER_HTML || path.join(__dirname, '..', '..', '..', 'target', 'viewer-sample', 'viewer.html');
test('F-016-AC71 ViewerExporter가 만든 viewer.html도 file:// 환경에서 덫에 걸리지 않고 켜진다', { skip: !fs.existsSync(built) && 'target/viewer-sample/viewer.html 없음 (mvn test를 먼저 돌린다)' }, async () => {
  const html = fs.readFileSync(built, 'utf8');
  const mermaid = scriptBlocks(html).find((b) => b.id === 'codeatlas-vendor-mermaid');
  const window = bootAsFile(html.replace(mermaid.text, ''));
  assert.deepEqual(window.errors, []);
  await tour(window);
  assert.deepEqual(window.trapped, []);
  assert.deepEqual(window.errors, []);
  // 내장한 Mermaid 번들: file:에서 막히는 방식으로 자기 조각을 가져오지 않는다(한 파일짜리 번들인지). 실행은 진짜 DOM이 필요해 글자로만 본다.
  const bundle = JSON.parse(mermaid.text);
  assert.doesNotMatch(bundle, /\bimportScripts\s*\(|new\s+(Shared)?Worker\s*\(/, 'Mermaid 번들이 워커를 띄우지 않는다');
});
