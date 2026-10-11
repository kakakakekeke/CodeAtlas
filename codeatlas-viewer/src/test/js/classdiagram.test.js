'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core, bigGraph } = require('./helpers.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');

const graph = core('graph');
const cd = core('classdiagram');

function index() { return graph.buildIndex(sampleGraph()); }
const names = (scope) => scope.types.map((t) => t.name).sort();

test('F-016-AC10 패키지 목록: 타입이 있는 패키지와 타입 수', () => {
  const pk = cd.packages(index());
  const service = pk.find((p) => p.name === 'com.shop.service');
  assert.equal(service.count, 3);
  assert.ok(pk.some((p) => p.name === 'org.springframework.batch.core.step.tasklet'));
  assert.deepEqual(pk.map((p) => p.name), pk.map((p) => p.name).slice().sort());
});

test('F-016-AC10 패키지 범위: 그 패키지의 타입과 그 사이의 관계만', () => {
  const ix = index();
  const scope = cd.selectScope(ix, { packages: ['com.shop.service'] });
  assert.deepEqual(names(scope), ['BaseService', 'OrderService', 'OrderServiceImpl']);
  assert.equal(scope.truncated, false);
  assert.equal(scope.total, 3);
  const kinds = scope.relations.map((r) => r.kind).sort();
  assert.deepEqual(kinds, ['EXTENDS', 'IMPLEMENTS']);
  // 하위 패키지 포함
  const all = cd.selectScope(ix, { packages: ['com.shop'], includeSubpackages: true });
  assert.equal(all.types.length, 9);
  assert.equal(cd.selectScope(ix, { packages: ['com.shop'] }).types.length, 0);
});

test('F-016-AC10 Mermaid 소스: 타입, 스테레오타입, 관계 화살표', () => {
  const ix = index();
  const scope = cd.selectScope(ix, { packages: ['com.shop'], includeSubpackages: true });
  const out = cd.toMermaid(ix, scope, { showMembers: true });
  const src = out.source;
  assert.ok(src.startsWith('classDiagram\n'));
  const alias = (id) => [...out.aliases.entries()].find(([, v]) => v === id)[0];
  const impl = alias(ids.T.serviceImpl);
  const svc = alias(ids.T.service);
  const base = alias(ids.T.base);
  const order = alias(ids.T.order);
  const item = alias(ids.T.item);
  assert.match(src, new RegExp('class ' + impl + '\\["OrderServiceImpl"\\]'));
  assert.ok(src.includes(base + ' <|-- ' + impl), 'EXTENDS');
  assert.ok(src.includes(svc + ' <|.. ' + impl), 'IMPLEMENTS');
  assert.ok(src.includes(order + ' --> "*" ' + item + ' : items'), 'ASSOCIATES 다중성');
  assert.ok(src.includes(alias(ids.T.controller) + ' ..> ' + order), 'DEPENDS_ON');
  assert.ok(src.includes('<<interface>>'));
  assert.ok(src.includes('<<enum>>'));
  assert.ok(src.includes('<<service>>'));
  // 멤버: 가시성 기호, 필드 타입, 메서드 시그니처
  assert.ok(src.includes('-OrderMapper orderMapper'));
  assert.ok(src.includes('+placeOrder(Order) Order'));
  assert.ok(src.includes('-validate(Order) void'));
  assert.ok(src.includes('#int MAX_RETRY$'), 'static 필드는 $');
  assert.ok(src.includes('+findOrders()* List'), 'abstract 메서드는 *');
  assert.ok(src.includes('-List~OrderItem~ items'), '제네릭은 ~ ~');
});

test('F-016-AC10 관계 종류와 멤버 표시를 끌 수 있다', () => {
  const ix = index();
  const noDeps = cd.selectScope(ix, { packages: ['com.shop'], includeSubpackages: true, kinds: ['EXTENDS', 'IMPLEMENTS'] });
  assert.ok(noDeps.relations.every((r) => r.kind === 'EXTENDS' || r.kind === 'IMPLEMENTS'));
  const src = cd.toMermaid(ix, noDeps, { showMembers: false }).source;
  assert.ok(!src.includes('..>'));
  assert.ok(!src.includes('-->'));
  assert.ok(src.includes('<|--'));
  assert.ok(!src.includes('placeOrder'));
  assert.ok(!src.includes('orderMapper'));
  const none = cd.selectScope(ix, { packages: ['com.shop.service'], kinds: [] });
  assert.equal(none.relations.length, 0);
});

test('F-016-AC10 타입 범위: 깊이만큼 이웃 타입을 넣는다', () => {
  const ix = index();
  const d0 = cd.selectScope(ix, { types: [ids.T.serviceImpl], depth: 0 });
  assert.deepEqual(names(d0), ['OrderServiceImpl']);
  const d1 = cd.selectScope(ix, { types: [ids.T.serviceImpl], depth: 1 });
  assert.deepEqual(names(d1), ['BaseService', 'Order', 'OrderMapper', 'OrderService', 'OrderServiceImpl']);
  const d1NoDeps = cd.selectScope(ix, { types: [ids.T.serviceImpl], depth: 1, kinds: ['EXTENDS', 'IMPLEMENTS', 'ASSOCIATES'] });
  assert.deepEqual(names(d1NoDeps), ['BaseService', 'OrderMapper', 'OrderService', 'OrderServiceImpl']);
  const d2 = cd.selectScope(ix, { types: [ids.T.serviceImpl], depth: 2 });
  assert.ok(names(d2).includes('OrderController'));
  assert.ok(names(d2).includes('SettlementTasklet'));
});

test('F-016-AC10 소스가 없는 타입은 구분 클래스가 붙고, 같은 쌍의 연관은 한 선으로 합친다', () => {
  const g = sampleGraph();
  g.edges.push({ from: ids.T.order, to: ids.T.item, kind: 'ASSOCIATES', confidence: 'RESOLVED', location: null, attrs: { field: 'firstItem', multiplicity: '1' } });
  const ix = graph.buildIndex(g);
  const scope = cd.selectScope(ix, { types: [ids.T.tasklet, ids.T.order], depth: 1 });
  const out = cd.toMermaid(ix, scope, { showMembers: false });
  const ext = [...out.aliases.entries()].find(([, v]) => v === 'java:type:org.springframework.batch.core.step.tasklet.Tasklet')[0];
  assert.match(out.source, new RegExp('class ' + ext + '\\["Tasklet"\\]:::external'));
  // Mermaid 11.17의 classDiagram(브라우저에서 확인한 것):
  //  - classDef의 첫 속성 이름에 '-'가 있으면 구문 오류가 난다. 그래서 stroke를 앞에 둔다.
  //  - stroke-dasharray는 클래스 도형에 적용되지 않는다. 점선은 뷰어가 그린 뒤에 external 클래스가 붙은 도형에 직접 입힌다.
  assert.match(out.source, /\n  classDef external stroke:#[0-9a-f]{6},stroke-dasharray:4 3\n/);
  const lines = out.source.split('\n').filter((l) => l.includes(' --> ') && l.includes('items'));
  assert.equal(lines.length, 1);
  assert.ok(lines[0].includes('firstItem'));
});

test('F-016-AC10 이름이 같은 타입은 전체 이름으로 구분하고, 특수문자가 있는 이름도 소스를 깨지 않는다', () => {
  const g = sampleGraph();
  g.nodes.push({ id: 'java:type:com.other.Order', kind: 'JAVA_TYPE', name: 'Order', qualifiedName: 'com.other.Order', location: null, attrs: { declared: true, typeKind: 'class' } });
  g.nodes.push({ id: 'java:type:com.shop.domain.Order$Line"x', kind: 'JAVA_TYPE', name: 'Order$Line"x', qualifiedName: 'com.shop.domain.Order$Line"x', location: null, attrs: { declared: true, typeKind: 'class' } });
  const ix = graph.buildIndex(g);
  const scope = cd.selectScope(ix, { types: [ids.T.order, 'java:type:com.other.Order', 'java:type:com.shop.domain.Order$Line"x'], depth: 0 });
  const src = cd.toMermaid(ix, scope, { showMembers: false }).source;
  assert.ok(src.includes('["com.shop.domain.Order"]'));
  assert.ok(src.includes('["com.other.Order"]'));
  assert.ok(src.includes('["Order.Line\'x"]'), '중첩 타입은 바깥 이름과 점으로 잇는다');
  assert.ok(!/class \S*["$]/.test(src.replace(/\["[^\n]*"\]/g, '')), '별칭에는 특수문자가 없다');
});

test('F-016-AC16 타입 수가 상한을 넘으면 상한까지만 넣고 잘렸음을 알린다', () => {
  const ix = graph.buildIndex(bigGraph(2000));
  const scope = cd.selectScope(ix, { packages: ['com.big'], includeSubpackages: true, maxNodes: 50 });
  assert.equal(scope.types.length, 50);
  assert.equal(scope.truncated, true);
  assert.equal(scope.total, 400);
  const ids50 = new Set(scope.types.map((t) => t.id));
  assert.ok(scope.relations.every((r) => ids50.has(r.from) && ids50.has(r.to)));
  // 상한을 주지 않으면 기본 상한이 적용된다
  const def = cd.selectScope(ix, { packages: ['com.big'], includeSubpackages: true });
  assert.equal(def.types.length, cd.DEFAULT_MAX_NODES);
  assert.equal(def.truncated, true);
});
