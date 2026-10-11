'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { read, assetList, assemble, scriptBlocks } = require('./tools/assemble.js');
const { boot, sleep } = require('./tools/boot.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');
const { bigGraph } = require('./helpers.js');
const { slice, loc } = require('./fixtures/slice.js');

function sampleHtml() {
  return assemble(JSON.stringify(sampleGraph()), '');
}

test('F-016-AC17 스타일: 다크 규칙이 있고 색은 CSS 변수로만 정의한다', () => {
  const css = read('css/viewer.css');
  assert.match(css, /@media \(prefers-color-scheme: dark\)\s*\{\s*:root\s*\{[^}]*--bg:/);
  assert.match(css, /:root\s*\{[^}]*--bg:[^}]*--text:/);
  // :root 블록 밖에는 색 값이 직접 나오지 않는다
  const outside = css.replace(/:root\s*\{[^}]*\}/g, '');
  assert.doesNotMatch(outside, /#[0-9a-fA-F]{3,8}\b/);
  assert.doesNotMatch(outside, /\brgba?\(/);
  // 라이트와 다크가 같은 변수 집합을 정의한다
  const blocks = css.match(/:root\s*\{[^}]*\}/g);
  const names = (b) => [...b.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]).filter((n) => !/^--(font|mono|row)$/.test(n)).sort();
  assert.deepEqual(names(blocks[1]), names(blocks[0]));
});

test('F-016-AC2 자산: 외부 주소 참조와 네트워크 호출이 없고 HTML 종료 문자열이 없다', () => {
  const template = read('template.html');
  assert.doesNotMatch(template, /\b(src|href)\s*=/i, '템플릿은 외부 파일을 참조하지 않는다');
  for (const asset of assetList()) {
    const text = read(asset);
    assert.doesNotMatch(text, /<\/script/i, asset);
    assert.doesNotMatch(text, /<!--/, asset);
    // XML 이름공간 식별자(SVG)는 주소처럼 생겼지만 요청하지 않는다. 그것만 빼고 본다.
    assert.doesNotMatch(text.replace(/http:\/\/www\.w3\.org\/2000\/svg/g, ''), /https?:\/\//, asset);
    if (asset.endsWith('.css')) {
      assert.doesNotMatch(text, /url\(|@import/, asset);
    } else {
      assert.doesNotMatch(text, /\bfetch\(|XMLHttpRequest|\bimport\(|WebSocket|sendBeacon/, asset);
    }
  }
});

test('F-016-AC18 조립한 viewer.html: 자리 표시가 모두 채워지고 스크립트에 구문 오류가 없다', () => {
  const html = sampleHtml();
  assert.ok(!html.includes('@@'), '남은 자리 표시가 없다');
  const blocks = scriptBlocks(html);
  const data = blocks.find((b) => b.id === 'codeatlas-data');
  assert.equal(JSON.parse(data.text).nodes.length, sampleGraph().nodes.length);
  const code = blocks.filter((b) => b.type !== 'application/json');
  assert.ok(code.length >= 1);
  code.forEach((b) => new vm.Script(b.text));
});

test('F-016-AC3 데이터에 </script>가 있어도 데이터 블록이 깨지지 않는다 (JS 조립기)', () => {
  const g = sampleGraph();
  g.nodes[0].name = '</script><script>window.pwned = 1</script><!-- x';
  const html = assemble(JSON.stringify(g), '');
  const data = scriptBlocks(html).find((b) => b.id === 'codeatlas-data');
  assert.equal(JSON.parse(data.text).nodes[0].name, g.nodes[0].name);
  const window = boot(html);
  assert.equal(window.pwned, undefined);
});

test('F-016-AC18 가짜 DOM에서 켜지고 모든 화면이 한 번씩 그려진다', async () => {
  const window = boot(sampleHtml());
  const doc = window.document;
  const app = window.CodeAtlas.app;
  assert.deepEqual(window.errors, []);
  assert.ok(doc.querySelector('.topbar .project').textContent.includes('sample-shop'));
  assert.equal(doc.querySelectorAll('.tab').length, 6);

  // 탐색: 검색하고 고르면 상세가 나온다
  const exploreView = doc.querySelector('.view-explore');
  assert.ok(exploreView.classList.contains('active'));
  assert.ok(doc.querySelectorAll('.view-explore .kind-row').length >= 20);
  const searchInput = doc.querySelector('.view-explore .search');
  searchInput.value = 'placeOrder';
  searchInput.fire('input');
  await sleep(250);
  const rows = doc.querySelectorAll('.view-explore .results .vrow');
  assert.equal(rows.length, 2);
  assert.ok(doc.querySelector('.view-explore .result-count').textContent.includes('2'));
  app.navigate('explore', { id: ids.M.implPlace });
  await sleep(20);
  const detail = doc.querySelector('.view-explore .detail-body').textContent;
  assert.ok(detail.includes('com.shop.service.OrderServiceImpl#placeOrder(Order)'));
  assert.ok(detail.includes('OrderServiceImpl.java:30-38'));
  assert.ok(detail.includes('UNRESOLVED'));
  assert.ok(detail.includes('CALLS'));
  assert.ok(detail.includes('returnType'));

  // 콜트리: 양방향 트리, 모두 펼치면 테이블까지 나온다
  app.navigate('calltree', { id: ids.JSP.list });
  await sleep(20);
  assert.ok(doc.querySelector('.view-calltree').classList.contains('active'));
  assert.ok(!exploreView.classList.contains('active'));
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  const callees = doc.querySelector('.view-calltree .tree-callees').textContent;
  assert.ok(callees.includes('POST /orders'));
  assert.ok(callees.includes('OrderMapper.insertOrder'));
  assert.ok(callees.includes('ORDERS'));
  assert.ok(callees.includes('순환'));
  const callers = doc.querySelector('.view-calltree .tree-callers').textContent;
  assert.ok(callers.includes('OrderController#list'));
  // 한 줄을 접으면 그 아래가 사라진다
  const before = doc.querySelectorAll('.view-calltree .tree-callees .trow').length;
  doc.querySelectorAll('.view-calltree .tree-callees .trow .twist')[0].click();
  await sleep(20);
  assert.ok(doc.querySelectorAll('.view-calltree .tree-callees .trow').length < before);

  // 클래스 다이어그램: Mermaid가 없으면 소스를 보여 준다
  app.navigate('classdiagram', { package: 'com.shop.service' });
  await sleep(350);
  const classSource = doc.querySelector('.view-classdiagram .dg-source').textContent;
  assert.ok(classSource.startsWith('classDiagram'));
  assert.ok(classSource.includes('OrderServiceImpl'));
  assert.ok(doc.querySelector('.view-classdiagram .dg-summary').textContent.includes('3'));

  // ERD: 전체 테이블과 범례
  app.navigate('erd', {});
  await sleep(350);
  const erdSource = doc.querySelector('.view-erd .dg-source').textContent;
  assert.ok(erdSource.startsWith('erDiagram'));
  assert.ok(erdSource.includes('|o..o{'));
  assert.equal(doc.querySelectorAll('.view-erd .legend-item').length, 5, 'DDL, 추론, 방향 미상, 카디널리티 모름(F-013-AC30), DDL 없음');
  app.navigate('erd', { table: ids.TB.coupons });
  await sleep(350);
  const one = doc.querySelector('.view-erd .dg-source').textContent;
  assert.ok(one.includes('COUPONS') && !one.includes('ORDER_ITEMS'));

  // CRUD
  app.navigate('crud', {});
  await sleep(100);
  const crudText = doc.querySelector('.view-crud .crud-grid').textContent;
  assert.ok(crudText.includes('ORDER_HISTORY'));
  assert.ok(crudText.includes('com.shop.mapper.OrderMapper.insertOrder'));
  assert.ok(doc.querySelectorAll('.view-crud .crud-row').length >= 8);
  assert.ok(doc.querySelectorAll('.view-crud .crud-cell').some((c) => c.textContent === 'CU'));

  // 진단·미해결: 미해결 참조(엣지 3개)와 진단(5건)을 따로 보이고 탭에 두 수를 적는다
  const diagTab = doc.querySelector('.tab[data-view="diagnostics"]');
  assert.deepEqual(diagTab.querySelectorAll('.count').map((c) => c.textContent), ['미해결 3', '진단 5']);
  app.navigate('diagnostics', {});
  await sleep(50);
  assert.equal(doc.querySelectorAll('.view-diagnostics .drow').length, 3, '미해결 참조가 먼저 열린다');
  assert.ok(doc.querySelector('.view-diagnostics .diag-summary').textContent.includes('3'));
  const category = doc.querySelectorAll('.view-diagnostics .code-chip').find((c) => c.textContent.includes('java-call'));
  category.click();
  await sleep(50);
  // 마무리 단계가 만든 미해결 노드(LegacyDao#find)도 같은 분류(java-call)로 묶인다(F-016-AC51)
  const urows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.equal(urows.length, 2);
  const charge = urows.find((r) => r.textContent.includes('paymentGateway.charge'));
  assert.ok(charge.textContent.includes('OrderServiceImpl#placeOrder'));
  assert.ok(charge.textContent.includes('OrderServiceImpl.java:36'));
  assert.ok(urows.some((r) => r.textContent.includes('LegacyDao#find')));
  doc.querySelector('.view-diagnostics .diag-mode button[data-value="diagnostics"]').click();
  await sleep(50);
  assert.equal(doc.querySelectorAll('.view-diagnostics .drow').length, 5);
  const chip = doc.querySelectorAll('.view-diagnostics .code-chip').find((c) => c.textContent.includes('PARSE_FAILED'));
  chip.click();
  await sleep(50);
  const drows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.equal(drows.length, 1);
  assert.ok(drows[0].textContent.includes('ddl/broken.sql:12'));
  // 미해결 탭으로 돌아오면 그 탭의 필터가 남아 있다
  doc.querySelector('.view-diagnostics .diag-mode button[data-value="unresolved"]').click();
  await sleep(50);
  assert.equal(doc.querySelectorAll('.view-diagnostics .drow').length, 2);

  // 다른 화면에서 노드를 누르면 상세 서랍이 열린다
  app.showDetail(ids.TB.orders);
  await sleep(20);
  const drawer = doc.querySelector('.drawer');
  assert.ok(drawer.classList.contains('open'));
  assert.ok(drawer.textContent.includes('db:table:ORDERS'));
  assert.deepEqual(window.errors, []);
});

function realShapeGraph() {
  const s = slice();
  // 화면 흐름: 엔드포인트 → 컨트롤러 → 화면 → 요청 → 다음 엔드포인트
  const ep = s.n('http:endpoint:GET /order/list', 'HTTP_ENDPOINT', 'GET /order/list');
  const list = s.n('java:method:w.OrderController#list()', 'JAVA_METHOD', 'list', 'w.OrderController#list()', { modifiers: ['public'], parameterTypes: [], returnType: 'String' });
  const page = s.n('jsp:page:views/order/list.jsp', 'JSP_PAGE', 'list.jsp', 'views/order/list.jsp', { pageType: 'jsp' });
  const req = s.n('http:request:GET /order/detail@views/order/list.jsp:40', 'HTTP_REQUEST', 'GET /order/detail', 'GET /order/detail',
    { api: 'link', dynamic: false, httpMethod: 'GET', path: '/order/detail', query: 'orderId={*}', url: '${ctx}/order/detail?orderId=${o.orderId}' }, loc('views/order/list.jsp', 40));
  const ep2 = s.n('http:endpoint:GET /order/detail', 'HTTP_ENDPOINT', 'GET /order/detail');
  const detail = s.n('java:method:w.OrderController#detail()', 'JAVA_METHOD', 'detail', 'w.OrderController#detail()', { modifiers: ['public'], parameterTypes: [], returnType: 'String' });
  const ctor = s.n('java:method:w.OrderController#<init>()', 'JAVA_METHOD', '<init>', 'w.OrderController#<init>()', { constructor: true, synthetic: true, modifiers: ['public'], parameterTypes: [] });
  const ctl = s.n('java:type:w.OrderController', 'JAVA_TYPE', 'OrderController', 'w.OrderController', { typeKind: 'class', modifiers: ['public'], stereotype: 'controller', typeParameters: [] });
  const ext = s.n('java:method:jakarta.servlet.http.HttpSession#getAttribute(?)', 'JAVA_METHOD', 'getAttribute', 'getAttribute', { declared: false, external: true, signatureUnknown: true });
  const session = s.n('java:type:jakarta.servlet.http.HttpSession', 'JAVA_TYPE', 'HttpSession', 'HttpSession', { declared: false, external: true });
  s.e(ctl, 'DEPENDS_ON', session);
  [list, detail, ctor].forEach((m) => s.e(ctl, 'CONTAINS', m));
  s.e(ep, 'ROUTES_TO', list);
  s.e(list, 'RENDERS', page, { viewName: 'order/list' });
  s.e(list, 'CALLS', ext, { callType: 'interface' }, 'RESOLVED', loc('w/OrderController.java', 12));
  s.e(page, 'REQUESTS', req);
  s.e(req, 'MATCHES', ep2);
  s.e(ep2, 'ROUTES_TO', detail);
  // 테이블: DDL 관계, 방향 미상 추론 관계, DDL 없는 테이블, 뷰
  const order = s.n('db:table:TB_ORDER', 'DB_TABLE', 'TB_ORDER', 'SHOP.TB_ORDER', { comment: '주문', schema: 'SHOP', primaryKey: ['ORDER_ID'] });
  const code = s.n('db:table:TB_CODE', 'DB_TABLE', 'TB_CODE', 'TB_CODE', { comment: '공통코드', primaryKey: ['GROUP_CD', 'CODE'] });
  s.n('db:table:TB_HOLIDAY', 'DB_TABLE', 'TB_HOLIDAY', 'TB_HOLIDAY', { declared: false });
  const view = s.n('db:view:VW_ORDER_SUMMARY', 'DB_VIEW', 'VW_ORDER_SUMMARY');
  s.e(order, 'CONTAINS', s.n('db:column:TB_ORDER.ORDER_ID', 'DB_COLUMN', 'ORDER_ID', 'TB_ORDER.ORDER_ID', { dataType: 'NUMBER(12)', ordinal: 1, nullable: false, primaryKey: true, comment: '주문ID' }));
  const stmt = s.n('mybatis:stmt:m.selectOrderList', 'SQL_STATEMENT', 'selectOrderList', 'm.selectOrderList', { statementType: 'SELECT', sql: 'SELECT 1 FROM TB_ORDER' });
  s.e(detail, 'EXECUTES', stmt);
  s.e(stmt, 'READS', order);
  s.e(code, 'REFERENCES', order, { cardinality: 'unknown', columns: ['CODE'], directionKnown: false, evidence: [stmt], evidenceCount: 1, refColumns: ['ORDER_STATUS'], source: 'INFERRED' }, 'INFERRED');
  return { graph: s.graph(), ep, list, page, req, ep2, detail, ctor, ctl, ext, order, code, view, stmt };
}

test('F-016-AC26 화면: 상세에 꼬리표와 요약이 나오고, 엣지의 모든 속성을 펼쳐 근거 구문으로 갈 수 있다', async () => {
  const f = realShapeGraph();
  const window = boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('explore', { id: f.req });
  await sleep(20);
  const head = doc.querySelector('.view-explore .detail-head').textContent;
  assert.ok(head.includes('URL 원문 ${ctx}/order/detail?orderId=${o.orderId}'), head);
  app.navigate('explore', { id: f.ctor });
  await sleep(20);
  assert.deepEqual(doc.querySelectorAll('.view-explore .detail-head .tag').map((t) => t.textContent), ['합성']);
  app.navigate('explore', { id: f.ext });
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .detail-head h2').textContent.includes('jakarta.servlet.http.HttpSession#getAttribute(?)'));
  assert.deepEqual(doc.querySelectorAll('.view-explore .detail-head .tag').map((t) => t.textContent), ['외부 라이브러리', '시그니처 모름']);

  // 엣지 줄을 펼치면 모든 속성이 나오고, 노드 ID인 값은 눌러서 갈 수 있다
  app.navigate('explore', { id: f.code });
  await sleep(20);
  const row = doc.querySelectorAll('.view-explore .edge-row').find((r) => r.textContent.includes('TB_ORDER'));
  assert.ok(row.textContent.includes('CODE = ORDER_STATUS · 방향 미상: 키 정보 없음 · 추론 1건'), row.textContent);
  row.querySelector('.edge-toggle').click();
  await sleep(20);
  const full = doc.querySelector('.view-explore .edge-attrs');
  assert.ok(full.textContent.includes('evidence') && full.textContent.includes('cardinality') && full.textContent.includes('refColumns'));
  full.querySelectorAll('.link').find((l) => l.textContent === f.stmt).click();
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .detail-head h2').textContent.includes('m.selectOrderList'));

  // 정의 없는 노드와 합성 멤버를 목록에서 숨긴다
  const total = f.graph.nodes.length;
  assert.ok(doc.querySelector('.view-explore .result-count').textContent.includes(String(total)));
  const hideSynthetic = doc.querySelector('.view-explore .hide-synthetic input');
  hideSynthetic.checked = true;
  hideSynthetic.fire('change');
  const hideUndeclared = doc.querySelector('.view-explore .hide-undeclared input');
  hideUndeclared.checked = true;
  hideUndeclared.fire('change');
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .result-count').textContent.includes(String(total - 4)), '합성 1개, 정의 없음 3개가 빠진다');
  assert.deepEqual(window.errors, []);
});

test('F-016-AC20 화면: 모두 펼치기는 화면 이동에서 멈추고 알리며, 고르면 끝까지 펼친다', async () => {
  const f = realShapeGraph();
  const window = boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  window.CodeAtlas.app.navigate('calltree', { id: f.ep });
  await sleep(20);
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  let callees = doc.querySelector('.view-calltree .tree-callees');
  assert.ok(callees.textContent.includes('list.jsp'));
  assert.ok(!callees.textContent.includes('selectOrderList'));
  assert.ok(callees.querySelector('.banner').textContent.includes('화면'));
  // 소스 없는 메서드의 호출 줄은 기본으로 접는다(표준 산출물과 같다, F-016-AC47). 켜면 타입과 함께 보인다
  assert.ok(!callees.textContent.includes('HttpSession#getAttribute(?)'));
  const external = doc.querySelector('.view-calltree .show-external input');
  external.checked = true;
  external.fire('change');
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  callees = doc.querySelector('.view-calltree .tree-callees');
  assert.ok(callees.textContent.includes('HttpSession#getAttribute(?)'), '외부 메서드는 타입과 함께 보인다');
  const screens = doc.querySelector('.view-calltree .expand-screens input');
  screens.checked = true;
  screens.fire('change');
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  callees = doc.querySelector('.view-calltree .tree-callees');
  assert.ok(callees.textContent.includes('selectOrderList'));
  assert.ok(callees.textContent.includes('views/order/list.jsp:40'), '요청 지점');
  assert.deepEqual(window.errors, []);
});

test('F-016-AC21 F-016-AC22 F-016-AC23 화면: ERD와 클래스 다이어그램의 표시 선택', async () => {
  const f = realShapeGraph();
  const window = boot(assemble(JSON.stringify(f.graph), ''));
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('erd', {});
  await sleep(350);
  let src = doc.querySelector('.view-erd .dg-source').textContent;
  assert.ok(src.includes('TB_ORDER }o..o{ TB_CODE : "CODE = ORDER_STATUS (추론 1건, 방향 미상)"'), src);
  assert.ok(src.includes('TB_ORDER["TB_ORDER (주문)"]'));
  assert.ok(!src.includes('VW_ORDER_SUMMARY'));
  assert.deepEqual(doc.querySelectorAll('.view-erd .legend-item').map((l) => l.querySelector('div').firstChild.textContent),
    ['DDL 외래 키', '추론 관계', '추론 관계 (방향 미상)', '추론 관계 (카디널리티 모름)', 'DDL 없음']);
  const views = doc.querySelector('.view-erd .include-views input');
  views.checked = true;
  views.fire('change');
  await sleep(350);
  src = doc.querySelector('.view-erd .dg-source').textContent;
  assert.ok(src.includes('VW_ORDER_SUMMARY["VW_ORDER_SUMMARY (VIEW)"]:::view'));

  app.navigate('classdiagram', { package: 'w' });
  await sleep(350);
  src = doc.querySelector('.view-classdiagram .dg-source').textContent;
  assert.ok(src.includes('+list() String'));
  assert.ok(!src.includes('+OrderController()'), '합성 생성자는 기본으로 숨긴다');
  const synthetic = doc.querySelector('.view-classdiagram .show-synthetic input');
  synthetic.checked = true;
  synthetic.fire('change');
  await sleep(350);
  assert.ok(doc.querySelector('.view-classdiagram .dg-source').textContent.includes('+OrderController()'));
  // 패키지 목록: 소스 없는 패키지는 고를 때만 보인다
  const pkgNames = () => doc.querySelectorAll('.view-classdiagram .pkg-row').map((r) => r.getAttribute('title'));
  assert.deepEqual(pkgNames(), ['w']);
  const ext = doc.querySelector('.view-classdiagram .show-external-packages input');
  ext.checked = true;
  ext.fire('change');
  await sleep(20);
  assert.deepEqual(pkgNames(), ['w', 'jakarta.servlet.http']);
  assert.deepEqual(window.errors, []);
});

test('F-016-AC18 주소의 #으로 화면과 노드를 바로 연다', async () => {
  const window = boot(sampleHtml(), { hash: '#/calltree?id=' + encodeURIComponent(ids.TB.history) });
  await sleep(50);
  const doc = window.document;
  assert.ok(doc.querySelector('.view-calltree').classList.contains('active'));
  assert.ok(doc.querySelector('.view-calltree .tree-callers').textContent.includes('PKG_ORDER.CLOSE_ORDER:12'));
  assert.deepEqual(window.errors, []);
});

test('F-016-AC18 빈 그래프와 깨진 데이터에서도 화면이 뜬다', async () => {
  const empty = boot(assemble(JSON.stringify({ schemaVersion: 1, project: {}, nodes: [], edges: [], diagnostics: [] }), ''));
  await sleep(20);
  assert.deepEqual(empty.errors, []);
  assert.equal(empty.document.querySelectorAll('.tab').length, 6);
  const broken = boot(assemble('{ not json', ''));
  assert.ok(broken.document.querySelector('.fatal').textContent.includes('읽지 못했다'));
});

test('F-016-AC15 큰 그래프에서도 목록은 보이는 행만 DOM에 둔다', async () => {
  const window = boot(assemble(JSON.stringify(bigGraph(20000)), ''));
  const doc = window.document;
  assert.deepEqual(window.errors, []);
  assert.ok(doc.querySelector('.view-explore .result-count').textContent.includes('20,000'));
  const list = doc.querySelector('.view-explore .results .vlist');
  list.clientHeight = 600;
  list.scrollTop = 28 * 10000;
  list.fire('scroll');
  await sleep(30);
  const rows = doc.querySelectorAll('.view-explore .results .vrow');
  assert.ok(rows.length >= 20 && rows.length <= 40, '그린 행 수: ' + rows.length);
  assert.equal(doc.querySelector('.view-explore .results .vlist-spacer').style.height, (20000 * 28) + 'px');
  // ERD는 상한까지만 그리고 경고를 보인다
  window.CodeAtlas.app.navigate('erd', {});
  await sleep(50);
  const warning = doc.querySelector('.view-erd .banner').textContent;
  assert.ok(warning.includes('4,000') && warning.includes('60'), warning);
});

// ViewerExporter(Java)가 만든 파일이 있으면 같은 검사를 한다. `mvn -pl codeatlas-viewer test` 뒤에 생긴다.
const built = process.env.CODEATLAS_VIEWER_HTML || path.join(__dirname, '..', '..', '..', 'target', 'viewer-sample', 'viewer.html');
test('F-016-AC18 ViewerExporter가 만든 viewer.html: 구문 오류가 없고 가짜 DOM에서 켜진다', { skip: !fs.existsSync(built) && 'target/viewer-sample/viewer.html 없음 (mvn test를 먼저 돌린다)' }, async () => {
  const html = fs.readFileSync(built, 'utf8');
  const blocks = scriptBlocks(html);
  blocks.filter((b) => b.type !== 'application/json').forEach((b) => new vm.Script(b.text));
  const mermaid = blocks.find((b) => b.id === 'codeatlas-vendor-mermaid');
  assert.ok(mermaid && mermaid.text.trim().length > 100000, 'Mermaid가 내장되어 있다');
  new vm.Script(JSON.parse(mermaid.text));
  assert.doesNotMatch(html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ''), /\b(src|href)\s*=\s*["']?\s*(https?:)?\/\//i);
  // Mermaid는 진짜 DOM이 필요하므로 가짜 DOM에서는 빼고 켠다.
  const window = boot(html.replace(mermaid.text, ''));
  await sleep(20);
  assert.deepEqual(window.errors, []);
  assert.ok(window.document.querySelectorAll('.view-explore .kind-row').length >= 1);
});
