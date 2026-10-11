'use strict';
// samples/sample-shop의 실제 분석 결과(graph.json)로 PRD 4절의 시나리오를 한 번씩 따라가 본다.
// 그래프는 다른 모듈이 만들므로 파일이 있을 때만 돈다:
//   bin/codeatlas analyze samples/sample-shop --out codeatlas-cli/target/final-out
// 다른 위치의 그래프는 환경 변수 CODEATLAS_GRAPH_JSON으로 준다. 개수는 그래프에서 직접 세어 견준다(분석기가 바뀌어도 깨지지 않게).
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { core } = require('./helpers.js');
const { paths, containsInOrder } = require('./fixtures/slice.js');

const graph = core('graph');
const search = core('search');
const calltree = core('calltree');
const erd = core('erd');
const classDiagram = core('classdiagram');
const crud = core('crud');
const diag = core('diagnostics');

const file = process.env.CODEATLAS_GRAPH_JSON
  || path.join(__dirname, '..', '..', '..', '..', 'codeatlas-cli', 'target', 'final-out', 'graph.json');
const skip = !fs.existsSync(file) && '실제 그래프 없음: ' + file;

let cached = null;
function load() {
  if (!cached) {
    const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
    cached = { raw, ix: graph.buildIndex(raw) };
  }
  return cached;
}
function expanded(ix, id, direction, limits) {
  const root = calltree.createRoot(ix, id, direction, {});
  calltree.expandAll(ix, root, Object.assign({ maxDepth: 25, maxNodes: 5000 }, limits || {}));
  return root;
}
const kindsOnPath = (ix, p) => p.map((id) => (ix.node(id) || {}).kind);

test('sample-shop F-016-AC25 진단·미해결: 미해결 참조는 미해결 엣지 수만큼, 진단은 진단 수만큼 한 번씩', { skip }, () => {
  const { raw, ix } = load();
  const r = diag.collect(ix);
  const unresolvedEdges = raw.edges.filter((e) => e.confidence === 'UNRESOLVED').length;
  assert.equal(r.diagnostics.length, raw.diagnostics.length);
  assert.equal(r.unresolved.length, unresolvedEdges, '미해결 노드가 엣지와 따로 행이 되지 않는다');
  assert.equal(r.categories.reduce((s, c) => s + c.count, 0), r.unresolved.length);
  assert.ok(r.unresolved.every((x) => x.category && x.location && x.fromId), '분류, 참조 지점, 참조한 곳이 모두 있다');
  assert.ok(r.categories.every((c) => !c.category.startsWith('(')), '분류를 모르는 미해결이 없다');
});

test('sample-shop F-016-AC21 ERD: 기대 관계(erd-expected.md)가 모두 있고 방향 미상 관계는 따로 표시된다', { skip }, () => {
  const { ix } = load();
  const scope = erd.selectScope(ix, {});
  const has = (child, parent, source) => scope.relations.some((r) => r.from === 'db:table:' + child && r.to === 'db:table:' + parent
    && r.source === source && r.directionKnown);
  [['TB_CATEGORY', 'TB_CATEGORY'], ['TB_ORDER', 'TB_MEMBER'], ['TB_ORDER_ITEM', 'TB_ORDER'], ['TB_MEMBER', 'TB_MEMBER_GRADE'],
    ['TB_PRODUCT', 'TB_CATEGORY'], ['TB_ORDER_ITEM', 'TB_PRODUCT'], ['TB_DELIVERY', 'TB_ORDER'], ['TB_SETTLE_DTL', 'TB_SETTLE']]
    .forEach(([c, p]) => assert.ok(has(c, p, 'DDL'), 'DDL FK ' + c + ' → ' + p));
  [['TB_PAYMENT', 'TB_ORDER'], ['TB_STOCK', 'TB_PRODUCT'], ['TB_ORDER_HIST', 'TB_ORDER'], ['TB_DAILY_SALES', 'TB_PRODUCT'],
    ['TB_SETTLE', 'TB_MEMBER'], ['TB_SETTLE_DTL', 'TB_ORDER']]
    .forEach(([c, p]) => assert.ok(has(c, p, 'INFERRED'), '추론 ' + c + ' → ' + p));
  assert.ok(!scope.relations.some((r) => r.source === 'DDL' && r.from === 'db:table:TB_PAYMENT'), '주석 속 FK는 DDL 관계가 아니다');
  const out = erd.toMermaid(ix, scope, {});
  const undirected = scope.relations.filter((r) => !r.directionKnown);
  assert.equal(out.undirected.length, undirected.length);
  assert.ok(undirected.every((r) => r.source === 'INFERRED'));
  assert.equal(out.source.split('방향 미상').length - 1, undirected.length);
  assert.ok(out.source.includes('TB_HOLIDAY["TB_HOLIDAY (DDL 없음)"]:::undeclared'));
  assert.ok(!out.source.includes('VW_ORDER_SUMMARY'), '뷰는 기본으로 넣지 않는다');
  const withViews = erd.toMermaid(ix, erd.selectScope(ix, { includeViews: true }), {}).source;
  assert.ok(withViews.includes('VW_ORDER_SUMMARY["VW_ORDER_SUMMARY (VIEW)"]:::view'));
  const near = erd.selectScope(ix, { tables: ['db:table:TB_ORDER'], depth: 1 });
  assert.ok(['TB_MEMBER', 'TB_ORDER_ITEM', 'TB_DELIVERY', 'TB_PAYMENT', 'TB_ORDER_HIST', 'TB_SETTLE_DTL'].every((n) => near.tables.some((t) => t.name === n)));
});

test('sample-shop F-016-AC23 클래스 다이어그램: 주문 서비스 패키지에 인터페이스, 구현, 제네릭 상위 클래스가 보인다', { skip }, () => {
  const { ix } = load();
  const scope = classDiagram.selectScope(ix, { packages: ['com.example.shop.order.service'], includeSupertypes: true });
  const src = classDiagram.toMermaid(ix, scope, { showMembers: true }).source;
  assert.ok(src.includes('["OrderService"]') && src.includes('<<interface>>'));
  assert.ok(src.includes('["OrderServiceImpl"]'));
  assert.ok(src.includes('["BaseService&lt;T extends BaseVO&gt;"]'), '범위 밖의 제네릭 상위 클래스');
  assert.match(src, /c\d+ <\|\.\. c\d+/);
  assert.match(src, /c\d+ <\|-- c\d+/);
  assert.ok(!scope.types.some((t) => t.attrs.anonymous), '익명 타입은 기본으로 빠진다');
  const order = classDiagram.selectScope(ix, { packages: ['com.example.shop.order'], includeSubpackages: true });
  assert.ok(order.relations.some((e) => e.kind === 'ASSOCIATES'), '연관');
  const pkgs = classDiagram.packages(ix);
  assert.ok(pkgs.some((p) => p.external) && pkgs.some((p) => !p.external));
});

test('sample-shop F-016-AC19 콜트리: 엔드포인트에서 서비스 구현, 매퍼, 프로시저 안의 구문과 테이블까지', { skip }, () => {
  const { ix } = load();
  const root = expanded(ix, 'http:endpoint:POST /order/close.do', 'callees');
  const all = paths(root);
  const want = ['HTTP_ENDPOINT', 'JAVA_METHOD', 'JAVA_METHOD', 'JAVA_METHOD', 'JAVA_METHOD', 'SQL_STATEMENT', 'DB_ROUTINE', 'SQL_STATEMENT', 'DB_TABLE'];
  const hit = all.find((p) => p[p.length - 1] === 'db:table:TB_ORDER' && want.every((k, i) => kindsOnPath(ix, p)[i] === k));
  assert.ok(hit, '엔드포인트 → 컨트롤러 → 서비스(인터페이스 → 구현) → 매퍼 → 구문 → 루틴 → 루틴 안 구문 → 테이블');
  assert.ok(all.some((p) => p.includes('db:routine:PKG_ORDER.WRITE_HIST') && p[p.length - 1] === 'db:table:TB_ORDER_HIST'), '루틴이 부르는 루틴');
});

test('sample-shop F-016-AC19 콜트리: JSP에서 인라인 스크립트와 요청을 거쳐 테이블까지, 테이블에서 화면까지', { skip }, () => {
  const { ix } = load();
  const page = 'jsp:page:src/main/webapp/WEB-INF/views/order/detail.jsp';
  const down = paths(expanded(ix, page, 'callees'));
  assert.ok(down.some((p) => containsInOrder(p, [page, 'js:module:src/main/webapp/WEB-INF/views/order/detail.jsp', 'http:endpoint:ANY /order/history.do'])
    && p[p.length - 1] === 'db:table:TB_ORDER_HIST'), 'JSP → 인라인 스크립트 → 요청 → 엔드포인트 → … → 테이블');
  const up = paths(expanded(ix, 'db:table:TB_ORDER_HIST', 'callers'));
  assert.ok(up.some((p) => kindsOnPath(ix, p).includes('HTTP_ENDPOINT') && kindsOnPath(ix, p).includes('JSP_PAGE')), '테이블 → 구문 → 메서드 → 엔드포인트 → 화면');
});

test('sample-shop F-016-AC19 콜트리: 배치 잡에서 스텝, 역할 메서드, 구문, 테이블까지 (스텝은 NEXT 순서)', { skip }, () => {
  const { raw, ix } = load();
  for (const job of ix.nodesOfKind('BATCH_JOB')) {
    const root = expanded(ix, job.id, 'callees');
    const steps = root.children.filter((c) => c.node && c.node.kind === 'BATCH_STEP').map((c) => c.id);
    assert.ok(steps.length >= 1, job.id);
    raw.edges.filter((e) => e.kind === 'NEXT' && steps.includes(e.from) && steps.includes(e.to))
      .forEach((e) => assert.ok(steps.indexOf(e.from) < steps.indexOf(e.to), e.from + ' 다음에 ' + e.to));
    const all = paths(root);
    assert.ok(all.some((p) => {
      const kinds = kindsOnPath(ix, p);
      return kinds[1] === 'BATCH_STEP' && kinds.includes('JAVA_METHOD') && kinds.includes('SQL_STATEMENT') && kinds[kinds.length - 1] === 'DB_TABLE';
    }), job.id + ': 스텝 → 메서드 → 구문 → 테이블');
  }
});

test('sample-shop F-016-AC19 F-016-AC27 콜트리: 트리거는 이벤트가 맞는 쓰기에서만, 주입된 구현으로 좁힌다', { skip }, () => {
  const { ix } = load();
  const trigger = 'db:routine:TRG_ORDER_STATUS_HIST';
  const insert = paths(expanded(ix, 'mybatis:stmt:com.example.shop.order.mapper.OrderMapper.insertOrder', 'callees'));
  assert.ok(insert.some((p) => p.includes('db:table:TB_ORDER')));
  assert.ok(!insert.some((p) => p.includes(trigger)), 'INSERT는 AFTER UPDATE 트리거로 내려가지 않는다');
  const placeOrder = ix.nodesOfKind('JAVA_METHOD').find((m) => m.id.includes('OrderServiceImpl#placeOrder('));
  const tree = expanded(ix, placeOrder.id, 'callees', { maxDepth: 3 });
  const discount = tree.children.find((c) => c.id.includes('DiscountPolicy#discount('));
  assert.deepEqual(discount.children.map((c) => c.id.replace(/^.*\.(\w+#discount).*$/, '$1')), ['VipDiscountPolicy#discount'], '@Qualifier("vipDiscountPolicy")');
});

test('sample-shop F-016-AC24 CRUD: DELETE는 D, MERGE는 CU. 컬럼은 열이 되지 않는다', { skip }, () => {
  const { ix } = load();
  const m = crud.buildMatrix(ix, { rowKind: 'statement' });
  const cells = (name) => m.rows.find((r) => r.label.endsWith(name)).cells;
  assert.equal(cells('BatchMapper.deleteDailySales')['db:table:TB_DAILY_SALES'], 'D');
  assert.equal(cells('BatchMapper.mergeDailySales')['db:table:TB_DAILY_SALES'], 'CU');
  assert.ok(m.tables.every((t) => t.node && (t.node.kind === 'DB_TABLE' || t.node.kind === 'DB_VIEW')));
  const methods = crud.buildMatrix(ix, { rowKind: 'method' });
  const close = methods.rows.find((r) => r.label.includes('OrderMapper#callCloseOrder('));
  assert.ok(close && close.cells['db:table:TB_ORDER_HIST'] === 'c', '프로시저 호출 구문은 루틴 안의 구문까지 합친다(루틴을 거친 접근은 소문자)');
});

test('sample-shop F-016-AC26 탐색: 한글 주석으로 찾고, 외부 메서드는 타입과 함께 보인다', { skip }, () => {
  const { ix } = load();
  const hits = search.search(ix, { text: '주문' });
  assert.ok(hits.some((n) => n.id === 'db:table:TB_ORDER'));
  assert.ok(hits.some((n) => n.kind === 'DB_COLUMN'));
  const external = ix.nodesOfKind('JAVA_METHOD').filter((n) => n.attrs.external === true);
  assert.ok(external.length > 0);
  assert.ok(external.every((n) => n.qualifiedName.includes('#')), '외부 메서드의 이름에 타입이 붙는다');
  const request = ix.nodesOfKind('HTTP_REQUEST').find((n) => n.attrs.url);
  assert.ok(graph.nodeDetail(ix, request.id).summary.includes('URL 원문 ' + request.attrs.url));
});
