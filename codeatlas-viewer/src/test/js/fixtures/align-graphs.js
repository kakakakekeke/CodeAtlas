'use strict';
// 2차 수정(2026-10-11)에서 계약에 들어온 속성과 진단의 모양을 옮긴 조각 그래프. 실제 분석 결과(jeesite, egovframe)에서 본 값이다.
const { core } = require('../helpers.js');
const { slice, loc } = require('./slice.js');

const graph = core('graph');

/** 새 노드·엣지 attrs: resolvedPath, MATCHES의 맞춘 방식, 상속 EXECUTES, 리플렉션·modelAttribute 호출, skipped, dataLoad, otherDefinitions, 마무리 단계의 미해결 노드. */
function shapes() {
  const s = slice();
  const f = {};
  f.endpoint = s.n('http:endpoint:ANY /${adminPath}/sys/user/list', 'HTTP_ENDPOINT', 'ANY /${adminPath}/sys/user/list', 'ANY /${adminPath}/sys/user/list',
    { httpMethod: 'ANY', path: '/${adminPath}/sys/user/list', placeholders: ['adminPath'], resolvedPath: '/a/sys/user/list' });
  f.unresolvedEndpoint = s.n('http:endpoint:GET /${apiRoot}/x', 'HTTP_ENDPOINT', 'GET /${apiRoot}/x', 'GET /${apiRoot}/x',
    { httpMethod: 'GET', path: '/${apiRoot}/x', placeholders: ['apiRoot'] });
  f.request = s.n('http:request:GET /sys/user/list@a.jsp:3', 'HTTP_REQUEST', 'GET /sys/user/list', 'GET /sys/user/list',
    { httpMethod: 'GET', path: '/sys/user/list', url: '${ctx}/sys/user/list', api: 'link' }, loc('src/main/webapp/a.jsp', 3));
  s.e(f.request, 'MATCHES', f.endpoint, { strippedPlaceholder: '${adminPath}', trailingSlash: true, crossModule: true, relativeBase: '/a/sys' }, 'INFERRED');
  f.option = s.n('http:request:GET /dict/tree@b.js:9', 'HTTP_REQUEST', 'GET /dict/tree', 'GET /dict/tree',
    { httpMethod: 'GET', path: '/dict/tree', url: "ctx + '/dict/tree'", api: 'option', optionKey: 'async.url', callee: '$.fn.zTree.init' }, loc('src/main/webapp/static/b.js', 9));
  const type = (fqn, attrs) => s.n('java:type:' + fqn, 'JAVA_TYPE', fqn.substring(fqn.lastIndexOf('.') + 1), fqn, attrs);
  const method = (owner, name, attrs) => { const id = s.n('java:method:' + owner + '#' + name + '()', 'JAVA_METHOD', name, owner + '#' + name + '()', attrs); s.e('java:type:' + owner, 'CONTAINS', id); return id; };
  f.crudDao = type('g.CrudDao', { typeKind: 'interface', typeParameters: ['T'] });
  f.userDao = type('g.UserDao', { typeKind: 'interface', stereotype: 'mapper' });
  s.e(f.userDao, 'EXTENDS', f.crudDao, { typeArguments: ['g.User'] });
  f.filter = type('g.EncodingFilter', { typeKind: 'class', stereotype: 'filter' });
  f.interceptor = type('g.LogInterceptor', { typeKind: 'class', stereotype: 'interceptor' });
  f.findList = method('g.CrudDao', 'findList', { modifiers: ['public', 'abstract'] });
  f.stmt = s.n('mybatis:stmt:g.UserDao.findList', 'SQL_STATEMENT', 'findList', 'g.UserDao.findList',
    { statementType: 'SELECT', namespace: 'g.UserDao', statementId: 'findList', dialectVariant: 'oracle' }, loc('mappings/oracle/UserDao.xml', 20));
  s.e(f.findList, 'EXECUTES', f.stmt, { inherited: true, namespace: 'g.UserDao', declaredIn: f.crudDao }, 'INFERRED');
  f.base = method('g.BaseService', 'selectAll', {});
  type('g.BaseService', { typeKind: 'class' });
  type('g.UserService', { typeKind: 'class' });
  s.e(f.base, 'CALLS', f.findList, { callType: 'reflection', receiverType: 'g.UserDao', viaTypes: ['g.UserService'] }, 'INFERRED', loc('src/g/BaseService.java', 30));
  f.handler = method('g.UserController', 'list', {});
  f.populate = method('g.UserController', 'populate', {});
  s.e(f.handler, 'CALLS', f.populate, { callType: 'modelAttribute' }, 'INFERRED', loc('src/g/UserController.java', 12));
  f.inherited = s.n('java:method:org.lib.Parent#render(?)', 'JAVA_METHOD', 'render', 'org.lib.Parent#render(?)', { declared: false, external: true, signatureUnknown: true });
  s.e(f.handler, 'CALLS', f.inherited, { callType: 'virtual', inheritedFrom: 'org.lib.Parent' }, 'INFERRED', loc('src/g/UserController.java', 14));
  f.getter = method('g.UserService', 'getName', { synthetic: true, lombok: 'Getter' });
  f.apiGetter = method('g.Named', 'getName', { modifiers: ['public', 'abstract'] });
  s.e(f.getter, 'OVERRIDES', f.apiGetter, { lombok: true });
  f.page = s.n('jsp:page:src/main/webapp/a.jsp', 'JSP_PAGE', 'a.jsp', 'src/main/webapp/a.jsp', { pageType: 'jsp' });
  f.fnA = s.n('js:function:static/a.js#save', 'JS_FUNCTION', 'save', 'static/a.js#save', { kind: 'function' });
  f.fnB = s.n('js:function:static/b.js#validate', 'JS_FUNCTION', 'validate', 'static/b.js#validate', { kind: 'function' });
  s.e(f.fnA, 'CALLS', f.fnB, { callType: 'call', resolution: 'global', viaPage: f.page }, 'INFERRED');
  f.vendorJs = s.n('js:module:src/main/webapp/static/jquery/jquery-1.8.3.min.js', 'JS_MODULE', 'jquery-1.8.3.min.js', 'src/main/webapp/static/jquery/jquery-1.8.3.min.js', { moduleKind: 'script', skipped: 'vendor' });
  f.minJs = s.n('js:module:src/main/webapp/static/app.min.js', 'JS_MODULE', 'app.min.js', 'src/main/webapp/static/app.min.js', { moduleKind: 'script', skipped: 'minified' });
  f.vendorJsp = s.n('jsp:page:src/main/webapp/static/ckfinder/browse.jsp', 'JSP_PAGE', 'browse.jsp', 'src/main/webapp/static/ckfinder/browse.jsp', { pageType: 'jsp', skipped: 'vendor' });
  f.appJs = s.n('js:module:src/main/webapp/static/app.js', 'JS_MODULE', 'app.js', 'src/main/webapp/static/app.js', { moduleKind: 'script', vendor: true });
  f.bean = s.n('spring:bean:dataSource', 'SPRING_BEAN', 'dataSource', 'dataSource',
    { class: 'com.zaxxer.hikari.HikariDataSource', otherDefinitions: ['web-b/src/main/resources/ctx.xml:12 org.apache.commons.dbcp.BasicDataSource', 'web-a/src/test/resources/test-ctx.xml:5 org.h2.jdbcx.JdbcDataSource'] });
  f.table = s.n('db:table:SYS_USER', 'DB_TABLE', 'SYS_USER', 'SYS_USER', {});
  f.load = s.n('sql:stmt:db/init/data.sql:3', 'SQL_STATEMENT', 'db/init/data.sql:3', 'db/init/data.sql:3',
    { statementType: 'INSERT', dataLoad: true, rowCount: 120, sql: "INSERT INTO SYS_USER VALUES ('1')" }, loc('db/init/data.sql', 3));
  s.e(f.load, 'WRITES', f.table, { op: 'INSERT' });
  // 마무리 단계가 만든 미해결 노드: ID는 엣지가 가리키던 그대로, expectedPrefix와 그 접두사에서 유도한 category가 있다
  f.dangling = s.n('mybatis:stmt:selectShort', 'UNRESOLVED', 'selectShort', 'selectShort', { declared: false, expectedPrefix: 'mybatis:stmt', category: 'mybatis-statement' });
  s.e(f.handler, 'EXECUTES', f.dangling, { shortId: true }, 'UNRESOLVED', loc('src/g/UserController.java', 20));
  // 전에 저장한 그래프: category 없이 expectedPrefix만 있다
  f.oldDangling = s.n('java:method:g.Gone#run()', 'UNRESOLVED', 'run', 'g.Gone#run()', { declared: false, expectedPrefix: 'java:method' });
  s.e(f.handler, 'CALLS', f.oldDangling, {}, 'UNRESOLVED', loc('src/g/UserController.java', 21));
  f.graph = s.graph();
  return f;
}

/** 새 진단 코드와 report.md의 새 절(데이터 적재 스크립트, DDL에 없는 테이블)에 해당하는 모양. */
function reportShapes() {
  const s = slice();
  const f = {};
  const table = (name, declared) => s.n('db:table:' + name, 'DB_TABLE', name, name, { declared: declared !== false }, declared === false ? null : loc('db/ddl.sql', 1));
  f.user = table('SYS_USER');
  f.area = table('SYS_AREA');
  f.loaded = table('SYS_LOADED');
  f.ghostMany = table('GHOST_MANY', false);
  f.ghostOne = table('GHOST_ONE', false);
  f.ghostNone = table('GHOST_NONE', false);
  f.ghostLoad = table('GHOST_LOAD', false);
  const stmt = (id, attrs, location) => s.n(id, 'SQL_STATEMENT', id.substring(id.lastIndexOf('.') + 1), graph.idRemainder(id), Object.assign({ statementType: 'SELECT' }, attrs || {}), location);
  ['a', 'b', 'c'].forEach((x) => { const id = stmt('mybatis:stmt:m.GhostMapper.' + x, { namespace: 'm.GhostMapper', statementId: x }); s.e(id, 'READS', f.ghostMany); });
  f.one = stmt('mybatis:stmt:m.GhostMapper.one', { namespace: 'm.GhostMapper', statementId: 'one' });
  s.e(f.one, 'READS', f.ghostOne);
  s.e(f.one, 'READS', f.user);
  const load = (file, line, tableId, rowCount) => {
    const id = stmt('sql:stmt:' + file + ':' + line, Object.assign({ statementType: 'INSERT', dataLoad: true }, rowCount == null ? {} : { rowCount }), loc(file, line));
    s.e(id, 'WRITES', tableId, { op: 'INSERT' });
    return id;
  };
  f.load1 = load('db/data/init.sql', 3, f.user, 120);
  load('db/data/init.sql', 200, f.area, 30);
  load('db/data/dict.sql', 1, f.loaded, 5);
  load('db/data/dict.sql', 90, f.ghostLoad);
  s.d('INFO', 'DIALECT_VARIANT_MAPPERS', '방언별 매퍼를 감지했다: oracle, mysql, mssql. oracle을 쓴다(제외한 파일 12개). `mybatis.dialectVariant`로 바꾼다', null);
  s.d('INFO', 'UNRESOLVED_BEAN_PLACEHOLDER', '자리표시자가 든 빈 참조 3개를 풀지 못했다: dataSource-${Globals.DbType} 2건, egov.lobHandler.${Globals.DbType} 1건', null);
  s.d('INFO', 'FILE_SKIPPED', '벤더 스크립트 37개를 분석하지 않았다', { file: 'src/main/webapp/static/jquery', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  s.d('INFO', 'FILE_SKIPPED', '압축된 스크립트라 분석하지 않았다', loc('src/main/webapp/static/app.min.js', 1));
  s.d('INFO', 'DATA_LOAD_GROUPED', 'INSERT 150건을 테이블별 구문 2개로 묶었다', { file: 'db/data/init.sql', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  s.d('INFO', 'DDL_NOT_APPLIED', 'ALTER TABLE 4건을 적용하지 않았다(대상 테이블의 CREATE TABLE이 다른 파일에 있다)', { file: 'db/upgrade/v2.sql', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0 });
  s.d('INFO', 'REFLECTION_CALL', '대상을 정적으로 알 수 없는 Method.invoke가 있다', loc('src/g/Reflections.java', 40));
  f.graph = s.graph();
  return f;
}

module.exports = { shapes, reportShapes };
