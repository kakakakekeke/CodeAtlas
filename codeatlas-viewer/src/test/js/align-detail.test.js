'use strict';
// 2차 수정(2026-10-11)에서 계약(design 3.5·3.6·3.7)에 들어온 속성과 진단이 상세·탐색·진단 화면의 자료로 읽기 좋게 나오는지 본다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { shapes, reportShapes } = require('./fixtures/align-graphs.js');

const graph = core('graph');
const search = core('search');
const diag = core('diagnostics');

const tagsOf = (ix, id) => graph.nodeTags(ix.node(id)).map((t) => t.text);
function edgeItem(ix, fromId, kind, toId) {
  const group = graph.nodeDetail(ix, fromId).outgoing.find((g) => g.kind === kind);
  return group.items.find((i) => i.otherId === toId);
}

test('F-016-AC51 상세의 꼬리표와 요약: resolvedPath·placeholders, dataLoad·rowCount, dialectVariant, skipped, api=option, 필터·인터셉터, otherDefinitions', () => {
  const f = shapes();
  const ix = graph.buildIndex(f.graph);
  // 엔드포인트: 이름은 치환한 경로, 요약에 원문과 자리표시자
  assert.equal(graph.shortLabel(ix.node(f.endpoint), f.endpoint), 'ANY /a/sys/user/list (/${adminPath}/sys/user/list)');
  assert.equal(graph.nodeSummary(ix.node(f.endpoint)), '치환한 경로 /a/sys/user/list · 자리표시자 ${adminPath}');
  assert.deepEqual(tagsOf(ix, f.unresolvedEndpoint), ['자리표시자 미치환']);
  assert.equal(graph.nodeSummary(ix.node(f.unresolvedEndpoint)), '자리표시자 ${apiRoot} (값을 정하지 못함)');
  // 데이터 적재 구문
  assert.deepEqual(tagsOf(ix, f.load), ['데이터 적재']);
  assert.equal(graph.nodeSummary(ix.node(f.load)), 'INSERT · 데이터 적재 120행');
  // 방언별 매퍼에서 고른 방언
  assert.deepEqual(tagsOf(ix, f.stmt), ['방언 oracle']);
  // 분석하지 않은 파일
  assert.deepEqual(tagsOf(ix, f.vendorJs), ['벤더 (분석 안 함)']);
  assert.deepEqual(tagsOf(ix, f.minJs), ['압축 파일 (분석 안 함)']);
  assert.deepEqual(tagsOf(ix, f.vendorJsp), ['벤더 (분석 안 함)']);
  assert.deepEqual(tagsOf(ix, f.appJs), ['벤더']);
  // 옵션 객체에서 읽은 URL
  assert.deepEqual(tagsOf(ix, f.option), ['옵션 객체의 URL']);
  assert.equal(graph.nodeSummary(ix.node(f.option)), "URL 원문 ctx + '/dict/tree' · option · 옵션 키 async.url · 호출 $.fn.zTree.init");
  // 필터·인터셉터: 요청 처리의 진입점이다(빈이라는 뜻이 아니다)
  assert.deepEqual(tagsOf(ix, f.filter), ['필터']);
  assert.deepEqual(tagsOf(ix, f.interceptor), ['인터셉터']);
  assert.ok(graph.nodeTags(ix.node(f.filter))[0].title.includes('진입점'));
  // 같은 이름의 다른 빈 정의: 한 줄에 하나씩
  assert.equal(graph.nodeSummary(ix.node(f.bean)), 'com.zaxxer.hikari.HikariDataSource · 같은 이름의 다른 정의 2개');
  const other = graph.nodeDetail(ix, f.bean).attrs.find((a) => a.key === 'otherDefinitions');
  assert.equal(other.multiline, true);
  assert.equal(other.items.length, 2);
});

test('F-016-AC51 미해결 노드의 꼬리표: 마무리 단계가 만든 노드도 "기대한 종류"가 아니라 분류(category)를 쓰고, category가 없는 전의 그래프는 expectedPrefix에서 유도한다', () => {
  const f = shapes();
  const ix = graph.buildIndex(f.graph);
  assert.deepEqual(tagsOf(ix, f.dangling), ['정의 없음', '분류 mybatis-statement']);
  assert.deepEqual(tagsOf(ix, f.oldDangling), ['정의 없음', '분류 java-call']);
  assert.ok(graph.nodeTags(ix.node(f.dangling))[1].title.includes('mybatis:stmt'), '어느 접두사의 ID를 가리켰는지는 설명에 남긴다');
  assert.equal(graph.danglingCategory('db:table'), 'db-table');
  assert.equal(graph.danglingCategory('spring:bean'), 'spring-bean-ref');
  assert.equal(graph.danglingCategory('js:function'), 'js-call');
  // 진단·미해결 화면의 분류도 같다(report.md의 묶음과 같다)
  const r = diag.collect(ix);
  const byTarget = new Map(r.unresolved.map((u) => [u.targetId, u]));
  assert.equal(byTarget.get(f.dangling).category, 'mybatis-statement');
  assert.equal(byTarget.get(f.dangling).target, 'selectShort');
  assert.equal(byTarget.get(f.oldDangling).category, 'java-call');
  assert.deepEqual(r.categories.map((c) => c.category + ' ' + c.count), ['java-call 1', 'mybatis-statement 1']);
});

test('F-016-AC51 상세의 엣지 요약: MATCHES의 맞춘 방식, 상속 EXECUTES, 리플렉션·modelAttribute·inheritedFrom·viaPage 호출, typeArguments, Lombok 재정의', () => {
  const f = shapes();
  const ix = graph.buildIndex(f.graph);
  assert.equal(edgeItem(ix, f.request, 'MATCHES', f.endpoint).summary,
    '엔드포인트의 ${adminPath}를 떼고 맞춤 · 끝의 / 차이 무시 · 다른 모듈의 핸들러 · 상대 URL 기준 /a/sys');
  const executes = edgeItem(ix, f.findList, 'EXECUTES', f.stmt);
  assert.equal(executes.summary, '상속 매퍼 메서드 · 하위 매퍼 g.UserDao의 구문 · 선언 g.CrudDao');
  assert.equal(executes.attrs.find((a) => a.key === 'declaredIn').items[0].nodeId, f.crudDao);
  assert.equal(executes.attrs.find((a) => a.key === 'namespace').items[0].nodeId, f.userDao, 'namespace의 매퍼 타입으로 간다');
  const reflection = edgeItem(ix, f.base, 'CALLS', f.findList);
  assert.equal(reflection.summary, '리플렉션 호출 (추론) · 대상 객체 g.UserDao · 거치는 하위 타입 g.UserService');
  assert.equal(reflection.attrs.find((a) => a.key === 'viaTypes').items[0].nodeId, 'java:type:g.UserService');
  assert.equal(reflection.attrs.find((a) => a.key === 'receiverType').items[0].nodeId, f.userDao);
  assert.equal(edgeItem(ix, f.handler, 'CALLS', f.populate).summary, '요청 전 처리: 핸들러보다 먼저 실행되는 @ModelAttribute 메서드 (추론)');
  assert.equal(edgeItem(ix, f.handler, 'CALLS', f.inherited).summary, 'classpath에 없는 상위 타입 org.lib.Parent에서 물려받은 메서드로 추론');
  const global = edgeItem(ix, f.fnA, 'CALLS', f.fnB);
  assert.equal(global.summary, '다른 스크립트의 전역 함수 · 함께 싣는 페이지 a.jsp');
  assert.equal(global.attrs.find((a) => a.key === 'viaPage').items[0].nodeId, f.page);
  assert.equal(edgeItem(ix, f.userDao, 'EXTENDS', f.crudDao).summary, '타입 인자 <g.User>');
  assert.equal(edgeItem(ix, f.getter, 'OVERRIDES', f.apiGetter).summary, 'Lombok 합성 메서드가 낀 재정의');
});

test('F-016-AC52 탐색: 벤더·압축 파일이라 분석하지 않은 모듈과 페이지(skipped)를 숨길 수 있다', () => {
  const f = shapes();
  const ix = graph.buildIndex(f.graph);
  const ids = (q) => search.search(ix, Object.assign({ kinds: ['JS_MODULE', 'JSP_PAGE'] }, q)).map((n) => n.id).sort();
  assert.deepEqual(ids({}), [f.appJs, f.minJs, f.vendorJs, f.page, f.vendorJsp].sort());
  assert.deepEqual(ids({ hideSkipped: true }), [f.appJs, f.page].sort(), 'vendor=true(분석한 벤더 모듈)는 남는다');
  assert.equal(search.countSkipped(ix), 3);
});

// ---------------------------------------------------------------- 진단

test('F-016-AC53 진단: 새 코드가 코드 필터에 나오고 설명이 붙으며, 위치 없는 진단과 폴더 단위 위치를 구분해 적는다', () => {
  const f = reportShapes();
  const ix = graph.buildIndex(f.graph);
  const r = diag.collect(ix);
  assert.deepEqual(r.codes.map((c) => c.code + ' ' + c.count),
    ['DATA_LOAD_GROUPED 1', 'DDL_NOT_APPLIED 1', 'DIALECT_VARIANT_MAPPERS 1', 'FILE_SKIPPED 2', 'REFLECTION_CALL 1', 'UNRESOLVED_BEAN_PLACEHOLDER 1']);
  assert.ok(r.codes.every((c) => c.description && c.description.length > 10), '코드마다 무엇을 뜻하는지 설명이 있다');
  assert.ok(diag.codeDescription('SOMETHING_NEW') === '', '모르는 코드는 설명 없이 그대로 보인다');
  const by = (code) => r.diagnostics.filter((d) => d.code === code);
  // 위치가 없는 진단(분석 한 번에 1건): 빈칸이 아니라 "프로젝트 전체"
  assert.equal(by('DIALECT_VARIANT_MAPPERS')[0].location, null);
  assert.equal(by('DIALECT_VARIANT_MAPPERS')[0].where, '프로젝트 전체');
  assert.equal(by('UNRESOLVED_BEAN_PLACEHOLDER')[0].where, '프로젝트 전체');
  // 폴더 단위 위치(줄 번호 없음)와 파일 위치
  const skipped = by('FILE_SKIPPED').map((d) => d.where).sort();
  assert.deepEqual(skipped, ['src/main/webapp/static/app.min.js:1', 'src/main/webapp/static/jquery/ (폴더)']);
  assert.equal(by('DATA_LOAD_GROUPED')[0].where, 'db/data/init.sql');
  assert.equal(by('REFLECTION_CALL')[0].where, 'src/g/Reflections.java:40');
  // 글자로 거를 때 위치 표기로도 찾는다
  assert.equal(diag.filter(r.diagnostics, { text: '폴더' }).length, 1);
});

test('F-016-AC54 진단: report.md의 "데이터 적재 스크립트"와 "DDL에 없는 테이블"(의심 표시)에 해당하는 보기가 같은 셈법으로 나온다', () => {
  const f = reportShapes();
  const ix = graph.buildIndex(f.graph);
  const r = diag.collect(ix);
  assert.ok(diag.VIEWS.some((v) => v.key === 'dataload' && v.rows === 'dataLoadFiles'));
  assert.ok(diag.VIEWS.some((v) => v.key === 'undeclared' && v.rows === 'undeclaredTables'));
  // 데이터 적재 스크립트: 파일 2개, 테이블 4개, 행 155건 이상(행 수를 모르는 구문 1개)
  assert.equal(r.dataLoadSummary.text, '파일 2개, 테이블 4개, 행 155건 이상(행 수를 모르는 구문 1개)');
  assert.equal(r.dataLoadSummary.statements, 4);
  assert.deepEqual(r.dataLoadFiles.map((d) => [d.file, d.tableCount, d.rowsText, d.statements]),
    [['db/data/dict.sql', 2, '5 이상', 2], ['db/data/init.sql', 2, '150', 2]]);
  assert.deepEqual(r.dataLoadFiles[1].tables, ['SYS_AREA', 'SYS_USER']);
  assert.equal(r.dataLoadFiles[1].nodeId, f.load1, '누르면 그 파일의 첫 적재 구문으로 간다');
  // DDL에 없는 테이블: 구문 수는 적재 구문을 뺀 수, 구문이 하나뿐이면 의심
  assert.deepEqual(r.undeclaredTables.map((t) => [t.name, t.statementCount, t.note]), [
    ['GHOST_LOAD', 0, '데이터 적재 스크립트만 참조'],
    ['GHOST_MANY', 3, ''],
    ['GHOST_NONE', 0, ''],
    ['GHOST_ONE', 1, '의심(구문 파싱 복구에서 나왔을 수 있음)']]);
  assert.deepEqual(r.undeclaredTables[1].statements.map((s) => s.label), ['GhostMapper.a', 'GhostMapper.b', 'GhostMapper.c']);
  assert.equal(r.undeclaredSuspicious, 1);
  assert.equal(diag.filter(r.undeclaredTables, { text: '의심' }).length, 1);
  assert.equal(diag.filter(r.dataLoadFiles, { text: 'sys_area' }).length, 1);
  // 적재 구문이 없는 그래프에는 요약이 없다
  const empty = diag.collect(graph.buildIndex({ nodes: [], edges: [] }));
  assert.equal(empty.dataLoadSummary, null);
  assert.deepEqual(empty.dataLoadFiles, []);
});
