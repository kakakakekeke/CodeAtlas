'use strict';
// 화면: 2차 수정(2026-10-11)의 규칙·속성·진단이 실제로 그려지는지 본다(콜트리의 접기 토글과 새 표식, 진입점 묶음, CRUD의 적재 구문 토글과
// 엔드포인트 기준의 나눠 계산, ERD의 영역, 탐색의 벤더 숨김, 진단의 새 보기).
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assemble } = require('./tools/assemble.js');
const { boot, sleep } = require('./tools/boot.js');
const { core } = require('./helpers.js');
const { javaGraph, genericDao, reflection, accessors, dataLoads, modules } = require('./fixtures/java-graph.js');
const { shapes, reportShapes } = require('./fixtures/align-graphs.js');
const { slice } = require('./fixtures/slice.js');

const texts = (els) => els.map((e) => e.textContent);
const open = (graph) => boot(assemble(JSON.stringify(graph), ''));
function toggle(doc, selector, on) {
  const input = doc.querySelector(selector);
  assert.ok(input, selector);
  input.checked = on;
  input.fire('change');
}
const calleeRows = (doc) => doc.querySelectorAll('.view-calltree .tree-callees .trow');
/** 화면 오류가 없고, 없는 자식을 그대로 붙여 생긴 "null"·"undefined" 글자도 없다. */
function assertClean(window) {
  assert.deepEqual(window.errors, []);
  const text = window.document.getElementById('app').textContent;
  assert.ok(!/(^|[^A-Za-z])(null|undefined)([^A-Za-z]|$)/.test(text), '화면에 null/undefined 글자: ' + (/.{0,40}(null|undefined).{0,40}/.exec(text) || [''])[0]);
}

test('F-016-AC47 화면: 콜트리는 접근자·소스 없는 메서드의 호출 줄을 기본으로 접고(접은 수를 알린다) 토글로 편다', async () => {
  const f = accessors();
  const window = await open(f.j.graph());
  const doc = window.document;
  window.CodeAtlas.app.navigate('calltree', { id: f.run });
  await sleep(20);
  assert.equal(doc.querySelector('.view-calltree .show-accessors input').checked, false, '기본값은 표준 산출물과 같다(접는다)');
  assert.equal(doc.querySelector('.view-calltree .show-external input').checked, false);
  let names = texts(calleeRows(doc)).join('\n');
  assert.ok(names.includes('Vo#getTotal') && names.includes('Vo#settle') && !names.includes('Vo#getName') && !names.includes('Tool#help'), names);
  const folded = calleeRows(doc)[0].querySelector('.tag.folded');
  assert.ok(folded && folded.textContent.includes('7'), '기준 노드 줄에 접은 줄 수: ' + calleeRows(doc)[0].textContent);
  assert.ok(folded.getAttribute('title').includes('접근자 5') && folded.getAttribute('title').includes('소스 없는 메서드 2'), folded.getAttribute('title'));
  toggle(doc, '.view-calltree .show-accessors input', true);
  await sleep(20);
  names = texts(calleeRows(doc)).join('\n');
  assert.ok(names.includes('Vo#getName') && names.includes('Vo#setCode') && !names.includes('Tool#help'), names);
  toggle(doc, '.view-calltree .show-external input', true);
  await sleep(20);
  names = texts(calleeRows(doc)).join('\n');
  assert.ok(names.includes('Tool#help') && names.includes('Parent#inherited'), names);
  assert.equal(calleeRows(doc)[0].querySelector('.tag.folded'), null);
  assertClean(window);
});

test('F-016-AC41 F-016-AC43 F-016-AC46 화면: 콜트리 줄에 [?](수신자), 리플렉션, 요청 전 처리 표식이 붙고 자식은 호출 지점 순이다', async () => {
  const f = reflection();
  const j = f.j;
  // 핸들러보다 먼저 실행되는 @ModelAttribute 메서드와, 위치가 뒤섞인 호출
  const populate = j.method('com.r.UserController', 'populate');
  j.call(f.controller, populate, 90, 'modelAttribute', null, null, 'INFERRED');
  const late = j.method('com.r.Tool', 'late');
  j.call(f.controller, late, 30);
  const early = j.method('com.r.Tool', 'zEarly');
  j.call(f.controller, early, 3);
  const window = await open(j.graph());
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('calltree', { id: f.controller });
  await sleep(20);
  let rows = calleeRows(doc);
  assert.deepEqual(texts(rows).slice(1).map((t) => /(populate|zEarly|selectAll|late)/.exec(t)[1]), ['populate', 'zEarly', 'selectAll', 'late'],
    '[before]가 맨 앞, 그다음 호출 지점 순(3, 9, 30행)');
  const before = rows[1].querySelector('.tag.before');
  assert.ok(before && before.getAttribute('title').includes('@ModelAttribute'), rows[1].textContent);
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  rows = calleeRows(doc);
  const mapper = rows.find((r) => r.textContent.includes('UserMapper#selectAll'));
  assert.ok(mapper.querySelector('.tag.reflect'), mapper.textContent);
  assert.ok(!rows.some((r) => r.textContent.includes('RoleMapper')), '수신자 문맥(UserServiceImpl)과 맞는 대상만');
  // 문맥 없이 시작하면 전부 따라가되 [?]
  app.navigate('calltree', { id: f.select });
  await sleep(20);
  rows = calleeRows(doc);
  const uncertain = rows.filter((r) => r.querySelector('.tag.receiver-uncertain'));
  assert.equal(uncertain.length, 2, texts(rows).join('\n'));
  assert.ok(uncertain[0].querySelector('.tag.receiver-uncertain').getAttribute('title').includes('수신자'));
  assertClean(window);
});

test('F-016-AC45 화면: 멀티모듈 엔드포인트의 핸들러 줄에 모듈이 적히고, resolvedPath가 있는 엔드포인트는 치환한 경로로 보인다', async () => {
  const f = modules();
  f.j.endpoint('ANY', '/${adminPath}/sys/user/list', f.admin, { resolvedPath: '/a/sys/user/list', placeholders: ['adminPath'] });
  const window = await open(f.j.graph());
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('calltree', { id: f.endpoint });
  await sleep(20);
  const rows = texts(calleeRows(doc));
  assert.ok(rows[1].includes('ROUTES_TO(모듈 web-admin)') && rows[2].includes('ROUTES_TO(모듈 web-front)'), rows.join('\n'));
  app.navigate('calltree', { id: 'http:endpoint:ANY /${adminPath}/sys/user/list' });
  await sleep(20);
  assert.ok(doc.querySelector('.view-calltree .tree-root').textContent.includes('ANY /a/sys/user/list (/${adminPath}/sys/user/list)'));
  assert.ok(calleeRows(doc)[0].textContent.includes('ANY /a/sys/user/list'));
  assertClean(window);
});

test('F-016-AC49 화면: 콜트리의 시작 노드를 묶음(엔드포인트, 배치 잡, 필터·인터셉터)에서 고른다', async () => {
  const f = genericDao();
  const j = f.j;
  j.type('com.g.LoginInterceptor', { attrs: { stereotype: 'interceptor' } });
  const pre = j.method('com.g.LoginInterceptor', 'preHandle');
  j.method('com.g.LoginInterceptor', 'helper');
  j.type('com.g.EncodingFilter', { attrs: { stereotype: 'filter' } });
  j.method('com.g.EncodingFilter', 'doFilter');
  j.call(pre, f.serviceSave, 12, 'virtual', 'com.g.UserService');
  const window = await open(j.graph());
  const doc = window.document;
  window.CodeAtlas.app.navigate('calltree', {});
  await sleep(20);
  const groups = doc.querySelectorAll('.view-calltree .entry-groups button');
  assert.deepEqual(groups.map((b) => b.getAttribute('data-group')), ['endpoints', 'filters'], '잡이 없으면 배치 잡 묶음은 없다');
  assert.ok(groups[1].textContent.includes('필터·인터셉터') && groups[1].textContent.includes('2'), groups[1].textContent);
  assert.ok(groups[0].textContent.includes('6'));
  groups[1].click();
  await sleep(20);
  const panel = doc.querySelector('.view-calltree .entry-panel');
  assert.ok(panel.classList.contains('open'));
  const entries = panel.querySelectorAll('.entry-row');
  assert.deepEqual(texts(entries).map((t) => /(EncodingFilter#doFilter|LoginInterceptor#preHandle)/.exec(t)[1]), ['EncodingFilter#doFilter', 'LoginInterceptor#preHandle']);
  assert.ok(entries[0].textContent.includes('필터') && entries[1].textContent.includes('인터셉터'));
  assert.ok(panel.textContent.includes('filters.md'), '표준 산출물의 어느 파일과 같은 묶음인지 알린다');
  entries[1].click();
  await sleep(20);
  assert.ok(!panel.classList.contains('open'), '고르면 닫힌다');
  assert.ok(doc.querySelector('.view-calltree .tree-root').textContent.includes('preHandle'));
  doc.querySelector('.view-calltree .expand-all').click();
  await sleep(20);
  const tree = texts(calleeRows(doc)).join('\n');
  assert.ok(tree.includes('UserDao.insert') && !tree.includes('DictDao.insert'), '인터셉터에서 시작해도 수신자 문맥으로 좁힌다: ' + tree);
  assertClean(window);
});

test('F-016-AC48 화면: 데이터 적재 구문은 콜트리와 CRUD에서 기본으로 빠지고, 토글로 넣는다. 뺀 수를 알린다', async () => {
  const f = dataLoads();
  const window = await open(f.j.graph());
  const doc = window.document;
  const app = window.CodeAtlas.app;
  app.navigate('calltree', { id: 'db:table:SYS_USER' });
  await sleep(20);
  const callers = () => texts(doc.querySelectorAll('.view-calltree .tree-callers .trow')).join('\n');
  assert.ok(callers().includes('UserDao.findList') && !callers().includes('init.sql'), callers());
  toggle(doc, '.view-calltree .include-data-load input', true);
  await sleep(20);
  assert.ok(callers().includes('init.sql:3'), callers());

  app.navigate('crud', {});
  await sleep(100);
  const labels = () => texts(doc.querySelectorAll('.view-crud .crud-label'));
  assert.deepEqual(labels(), ['com.l.UserDao.findList']);
  assert.ok(doc.querySelector('.view-crud .crud-summary').textContent.includes('데이터 적재 구문 4개 제외'), doc.querySelector('.view-crud .crud-summary').textContent);
  toggle(doc, '.view-crud .include-data-load input', true);
  await sleep(100);
  assert.equal(labels().length, 5);
  assert.ok(!doc.querySelector('.view-crud .crud-summary').textContent.includes('제외'));
  assertClean(window);
});

test('F-016-AC55 화면: CRUD의 엔드포인트 기준은 고를 때 나눠서 계산하고 진행을 보인다. 끝나면 다시 계산하지 않는다', async () => {
  const f = genericDao();
  const window = await open(f.j.graph());
  const doc = window.document;
  const CA = window.CodeAtlas;
  // 한 번에 엔드포인트 하나씩만 계산하게 해서 진행 표시를 본다
  CA.views.crud.STEP_BUDGET_MS = -1;
  CA.app.navigate('crud', {});
  await sleep(100);
  const progress = doc.querySelector('.view-crud .crud-progress');
  assert.equal(progress.style.display, 'none');
  doc.querySelector('.view-crud .crud-kind button[data-value="endpoint"]').click();
  assert.notEqual(progress.style.display, 'none', '계산을 시작하면 바로 진행 표시가 나온다');
  assert.ok(/엔드포인트 기준 계산 중.*\/ 6/.test(progress.textContent), progress.textContent);
  await sleep(200);
  assert.equal(progress.style.display, 'none');
  const labels = texts(doc.querySelectorAll('.view-crud .crud-label'));
  assert.deepEqual(labels, ['GET /dict', 'GET /dict/direct', 'GET /odd', 'GET /raw', 'POST /mix', 'POST /user']);
  // 수신자 문맥으로 좁힌 결과: POST /user는 SYS_USER만
  const heads = texts(doc.querySelectorAll('.view-crud .crud-th'));
  const row = doc.querySelectorAll('.view-crud .crud-row').find((r) => r.textContent.includes('POST /user'));
  const cells = texts(row.querySelectorAll('.crud-cell'));
  assert.deepEqual(heads.filter((h, i) => cells[i]), ['SYS_USER']);
  // 다른 기준으로 갔다 와도 다시 계산하지 않는다
  doc.querySelector('.view-crud .crud-kind button[data-value="statement"]').click();
  await sleep(50);
  doc.querySelector('.view-crud .crud-kind button[data-value="endpoint"]').click();
  assert.equal(progress.style.display, 'none');
  assert.equal(doc.querySelectorAll('.view-crud .crud-label').length, 6);
  assertClean(window);
});

function areaSchema() {
  const s = slice();
  const names = ['SYS_USER', 'SYS_ROLE', 'SYS_MENU', 'CMS_ARTICLE', 'CMS_SITE', 'CMS_LINK', 'OA_LEAVE', 'LOG'];
  names.forEach((name) => {
    s.n('db:table:' + name, 'DB_TABLE', name, name, { primaryKey: ['ID'] });
    s.n('db:column:' + name + '.ID', 'DB_COLUMN', 'ID', name + '.ID', { dataType: 'NUMBER(10)', ordinal: 1, nullable: false, primaryKey: true });
    s.e('db:table:' + name, 'CONTAINS', 'db:column:' + name + '.ID');
  });
  s.e('db:table:CMS_ARTICLE', 'REFERENCES', 'db:table:SYS_USER', { columns: ['ID'], refColumns: ['ID'], source: 'DDL', constraint: 'FK_ARTICLE_USER' });
  return s.graph();
}

test('F-016-AC50 화면: ERD에서 영역(테이블 이름 접두사 묶음)을 골라 그린다. 다른 영역의 이웃 테이블은 이름만 나온다', async () => {
  const window = await open(areaSchema());
  const doc = window.document;
  window.CodeAtlas.app.navigate('erd', {});
  await sleep(350);
  const modes = doc.querySelectorAll('.view-erd .erd-mode button');
  assert.deepEqual(modes.map((b) => b.getAttribute('data-value')), ['all', 'area', 'pick']);
  doc.querySelector('.view-erd .erd-mode button[data-value="area"]').click();
  await sleep(350);
  const areas = doc.querySelectorAll('.view-erd .erd-areas .area-row');
  assert.deepEqual(texts(areas).map((t) => /^(CMS|SYS|기타)/.exec(t)[1]), ['CMS', 'SYS', '기타']);
  assert.ok(areas[0].textContent.includes('3'), '테이블 수: ' + areas[0].textContent);
  assert.ok(doc.querySelector('.view-erd .erd-areas').textContent.includes('erd/index.md'), '표준 산출물의 영역 분할과 같은 규칙임을 알린다');
  // 처음에는 첫 영역을 그린다
  let src = doc.querySelector('.view-erd .dg-source').textContent;
  assert.ok(src.includes('CMS_ARTICLE') && src.includes('SYS_USER["SYS_USER (다른 영역)"]:::outside') && !src.includes('SYS_ROLE'), src);
  assert.equal(areas[0].getAttribute('aria-pressed'), 'true');
  areas[1].click();
  await sleep(350);
  src = doc.querySelector('.view-erd .dg-source').textContent;
  assert.ok(src.includes('SYS_ROLE') && src.includes('CMS_ARTICLE["CMS_ARTICLE (다른 영역)"]:::outside') && !src.includes('CMS_SITE'), src);
  assert.ok(doc.querySelector('.view-erd .dg-summary').textContent.includes('영역 SYS'));
  assertClean(window);
});

test('F-016-AC50 화면: 테이블이 상한보다 많으면 전체 ERD는 상한까지만 그리고, 영역을 골라 보라고 알린다', async () => {
  const s = slice();
  for (let i = 0; i < 70; i++) {
    const name = (i < 40 ? 'AA_' : 'BB_') + 'T' + String(i).padStart(3, '0');
    s.n('db:table:' + name, 'DB_TABLE', name, name, {});
  }
  const window = await open(s.graph());
  const doc = window.document;
  window.CodeAtlas.app.navigate('erd', {});
  await sleep(350);
  const banner = doc.querySelector('.view-erd .banner');
  assert.notEqual(banner.style.display, 'none');
  assert.ok(banner.textContent.includes('70') && banner.textContent.includes('영역'), banner.textContent);
  const jump = banner.querySelector('button');
  assert.ok(jump, '영역 보기로 가는 단추');
  jump.click();
  await sleep(350);
  assert.equal(doc.querySelector('.view-erd .erd-mode button[data-value="area"]').getAttribute('aria-pressed'), 'true');
  assert.deepEqual(texts(doc.querySelectorAll('.view-erd .erd-areas .area-row')).map((t) => /^(AA|BB)/.exec(t)[1]), ['AA', 'BB']);
  assert.equal(doc.querySelector('.view-erd .banner').style.display, 'none', '영역은 상한에 걸리지 않는다');
  assertClean(window);
});

test('F-016-AC50 화면: 상한 안이어도 Mermaid 소스가 45,000자(표준 산출물이 ERD를 나누는 한도)를 넘으면 알리고 좁히는 방법을 준다', async () => {
  const s = slice();
  for (let i = 0; i < 40; i++) {
    const name = 'WIDE_T' + String(i).padStart(2, '0');
    s.n('db:table:' + name, 'DB_TABLE', name, name, {});
    for (let c = 0; c < 45; c++) {
      const column = 'COLUMN_WITH_LONG_NAME_' + c;
      s.n('db:column:' + name + '.' + column, 'DB_COLUMN', column, name + '.' + column, { dataType: 'VARCHAR2(200)', ordinal: c + 1, nullable: true });
      s.e('db:table:' + name, 'CONTAINS', 'db:column:' + name + '.' + column);
    }
  }
  const window = await open(s.graph());
  const doc = window.document;
  window.CodeAtlas.app.navigate('erd', {});
  await sleep(350);
  const banner = doc.querySelector('.view-erd .banner');
  assert.notEqual(banner.style.display, 'none');
  assert.ok(banner.textContent.includes('45,000') && banner.textContent.includes('키만'), banner.textContent);
  assert.ok(doc.querySelector('.view-erd .dg-source').textContent.length > 45000, '그래도 그린다(뷰어는 Mermaid의 글자 수 한도를 올려 두었다)');
  // 컬럼을 키만 보이게 하면 한도 안으로 들어온다
  doc.querySelector('.view-erd .erd-columns button[data-value="keys"]').click();
  await sleep(350);
  assert.equal(doc.querySelector('.view-erd .banner').style.display, 'none');
  assertClean(window);
});

test('F-016-AC51 F-016-AC52 화면: 탐색은 분석하지 않은 벤더·압축 파일을 기본으로 숨기고, 상세에 새 꼬리표와 엣지 요약이 나온다', async () => {
  const f = shapes();
  const window = await open(f.graph);
  const doc = window.document;
  const app = window.CodeAtlas.app;
  await sleep(20);
  const hide = doc.querySelector('.view-explore .hide-skipped input');
  assert.equal(hide.checked, true, '기본으로 숨긴다');
  assert.ok(doc.querySelector('.view-explore .hide-skipped').textContent.includes('3'), '숨긴 수');
  const total = f.graph.nodes.length;
  assert.ok(doc.querySelector('.view-explore .result-count').textContent.includes(String(total - 3)), doc.querySelector('.view-explore .result-count').textContent);
  toggle(doc, '.view-explore .hide-skipped input', false);
  await sleep(20);
  assert.ok(doc.querySelector('.view-explore .result-count').textContent.includes(String(total)));
  // 상세: 데이터 적재 구문, 벤더 모듈, 마무리 단계의 미해결 노드
  app.navigate('explore', { id: f.load });
  await sleep(20);
  let detail = doc.querySelector('.view-explore .detail');
  assert.ok(texts(detail.querySelectorAll('.detail-tags .tag')).includes('데이터 적재'));
  assert.ok(detail.querySelector('.summary').textContent.includes('데이터 적재 120행'));
  app.navigate('explore', { id: f.dangling });
  await sleep(20);
  detail = doc.querySelector('.view-explore .detail');
  assert.ok(texts(detail.querySelectorAll('.detail-tags .tag')).includes('분류 mybatis-statement'));
  assert.ok(!detail.textContent.includes('기대한 종류'));
  app.navigate('explore', { id: f.request });
  await sleep(20);
  detail = doc.querySelector('.view-explore .detail');
  assert.ok(detail.textContent.includes('엔드포인트의 ${adminPath}를 떼고 맞춤'), '엣지 요약');
  // 필터 타입에서 콜트리로: 진입 메서드가 없는 타입은 타입의 콜트리 단추가 없다(메서드에서 연다)
  app.navigate('explore', { id: f.filter });
  await sleep(20);
  assert.ok(texts(doc.querySelectorAll('.view-explore .detail .detail-tags .tag')).includes('필터'));
  assertClean(window);
});

test('F-016-AC53 F-016-AC54 화면: 진단에 "DDL에 없는 테이블"과 "데이터 적재 스크립트" 보기가 있고, 코드 필터에 설명이 붙으며 위치 없는 진단은 "프로젝트 전체"로 적힌다', async () => {
  const f = reportShapes();
  const window = await open(f.graph);
  const doc = window.document;
  window.CodeAtlas.app.navigate('diagnostics', {});
  await sleep(50);
  const modes = doc.querySelectorAll('.view-diagnostics .diag-mode button');
  assert.deepEqual(modes.map((b) => b.getAttribute('data-value')), ['unresolved', 'diagnostics', 'uncalled', 'dynamic', 'undeclared', 'dataload', 'columns', 'requests', 'borrowed']);
  assert.ok(modes[4].textContent.includes('DDL에 없는 테이블 4') && modes[5].textContent.includes('데이터 적재 스크립트 2'), texts(modes).join(' | '));

  doc.querySelector('.view-diagnostics .diag-mode button[data-value="diagnostics"]').click();
  await sleep(50);
  const chip = doc.querySelectorAll('.view-diagnostics .code-chip').find((c) => c.textContent.includes('DIALECT_VARIANT_MAPPERS'));
  assert.ok(chip.getAttribute('title').includes('방언'), '코드가 뜻하는 것');
  let rows = doc.querySelectorAll('.view-diagnostics .drow');
  const dialect = rows.find((r) => r.textContent.includes('DIALECT_VARIANT_MAPPERS'));
  assert.ok(dialect.querySelector('.loc').textContent.includes('프로젝트 전체'), dialect.textContent);
  const vendor = rows.find((r) => r.textContent.includes('벤더 스크립트 37개'));
  assert.ok(vendor.querySelector('.loc').textContent.includes('static/jquery/ (폴더)'), vendor.textContent);
  // 긴 메시지는 줄을 누르면 아래에 전부 보인다
  const bean = rows.find((r) => r.textContent.includes('UNRESOLVED_BEAN_PLACEHOLDER'));
  bean.click();
  await sleep(20);
  const full = doc.querySelector('.view-diagnostics .diag-detail');
  assert.notEqual(full.style.display, 'none');
  assert.ok(full.textContent.includes('dataSource-${Globals.DbType} 2건') && full.textContent.includes('풀지 못해'), full.textContent);

  doc.querySelector('.view-diagnostics .diag-mode button[data-value="undeclared"]').click();
  await sleep(50);
  rows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.equal(rows.length, 4);
  const one = rows.find((r) => r.textContent.includes('GHOST_ONE'));
  assert.ok(one.querySelector('.tag.warn').textContent.includes('의심'), one.textContent);
  assert.ok(rows.find((r) => r.textContent.includes('GHOST_MANY')).textContent.includes('GhostMapper.a'));
  assert.ok(doc.querySelector('.view-diagnostics .diag-filters').textContent.includes('의심으로 표시한 테이블 1개'));
  one.click();
  await sleep(20);
  assert.ok(doc.querySelector('.drawer').textContent.includes('db:table:GHOST_ONE'));

  doc.querySelector('.view-diagnostics .diag-mode button[data-value="dataload"]').click();
  await sleep(50);
  rows = doc.querySelectorAll('.view-diagnostics .drow');
  assert.equal(rows.length, 2);
  assert.ok(doc.querySelector('.view-diagnostics .diag-filters').textContent.includes('파일 2개, 테이블 4개, 행 155건 이상(행 수를 모르는 구문 1개)'));
  assert.ok(rows[1].textContent.includes('db/data/init.sql') && rows[1].textContent.includes('150') && rows[1].textContent.includes('SYS_AREA'), rows[1].textContent);
  assertClean(window);
});

test('F-016-AC55 큰 그래프: 엔드포인트 기준 CRUD의 나눠 계산(createJob)은 한 번에 만든 것과 같은 결과를 낸다', () => {
  const graph = core('graph');
  const crud = core('crud');
  const f = genericDao();
  const ix = graph.buildIndex(f.j.graph());
  const whole = crud.buildMatrix(ix, { rowKind: 'endpoint' });
  const job = crud.createJob(ix, { rowKind: 'endpoint' });
  assert.equal(job.total, 6);
  let steps = 0;
  while (!job.step(-1)) { steps++; assert.ok(job.done() > 0 && job.done() < job.total); assert.equal(job.result().partial, true); }
  assert.equal(steps, 5, '시간 예산이 음수면 엔드포인트 하나씩 계산한다');
  assert.equal(job.finished(), true);
  assert.equal(crud.toCsv(job.result(), {}), crud.toCsv(whole, {}));
  assert.equal(job.result().partial, false);
  // 구문·메서드·루틴 기준은 한 걸음에 끝난다
  const statements = crud.createJob(ix, { rowKind: 'statement' });
  assert.equal(statements.total, 1);
  assert.equal(statements.step(-1), true);
  assert.equal(javaGraph().graph().nodes.length, 0);
});
