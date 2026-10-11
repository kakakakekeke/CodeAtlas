'use strict';
// Java 쪽 조각 그래프를 짧게 적는 도우미(표준 산출물 테스트의 GraphFixture와 같은 모양의 노드·엣지를 만든다).
// 수신자 문맥, 주입 좁히기, 모듈, 표식처럼 2차 수정(2026-10-11)에서 계약에 들어온 모양을 재현하는 데 쓴다.
const { slice } = require('./slice.js');

function javaGraph() {
  const s = slice();
  const lines = new Map();
  const nextLine = (file) => { const n = (lines.get(file) || 0) + 10; lines.set(file, n); return n; };
  const fileOf = (fqn, root) => (root || '') + 'src/main/java/' + fqn.replace(/\$.*$/, '').replace(/\./g, '/') + '.java';
  const at = (file, line, column) => ({ file, startLine: line, startColumn: column || 1, endLine: line, endColumn: (column || 1) + 1 });
  const api = {
    s,
    at,
    typeId: (fqn) => 'java:type:' + fqn,
    /** 타입. opts: { kind(typeKind, 기본 class), modifiers, attrs, root(모듈 루트, 예: 'web-admin/') } */
    type(fqn, opts) {
      const o = opts || {};
      const file = fileOf(fqn, o.root);
      return s.n('java:type:' + fqn, 'JAVA_TYPE', fqn.substring(Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$')) + 1), fqn,
        Object.assign({ typeKind: o.kind || 'class', modifiers: o.modifiers || ['public'] }, o.attrs || {}), at(file, nextLine(file)));
    },
    /** 메서드. params는 매개변수 타입 목록(기본 없음). opts: { modifiers, attrs, root } */
    method(owner, name, params, opts) {
      const o = opts || {};
      const file = fileOf(owner, o.root);
      const signature = owner + '#' + name + '(' + (params || []).join(',') + ')';
      const id = s.n('java:method:' + signature, 'JAVA_METHOD', name, signature,
        Object.assign({ modifiers: o.modifiers || ['public'], parameterTypes: params || [] }, o.attrs || {}), at(file, nextLine(file)));
      s.e('java:type:' + owner, 'CONTAINS', id);
      return id;
    },
    typeEdge(from, kind, to, attrs, confidence) {
      s.e('java:type:' + from, kind, 'java:type:' + to, attrs, confidence);
    },
    /** 호출 엣지. receiverType이 없으면 넣지 않는다. 위치는 호출한 메서드의 파일과 준 줄이다. */
    call(from, to, line, callType, receiverType, more, confidence) {
      const attrs = Object.assign({ callType: callType || 'virtual' }, more || {});
      if (receiverType) attrs.receiverType = receiverType;
      const fromNode = s.graph().nodes.find((n) => n.id === from);
      s.e(from, 'CALLS', to, attrs, confidence, at(fromNode && fromNode.location ? fromNode.location.file : 'X.java', line));
    },
    table(name) {
      return s.n('db:table:' + name, 'DB_TABLE', name, name, {}, at('db/tables.sql', nextLine('db/tables.sql')));
    },
    /** 매퍼 구문과 그 구문이 읽거나 쓰는 테이블 엣지. type이 SELECT면 READS, 아니면 WRITES(op=type). */
    statement(namespace, id, type, table, attrs) {
      const stmt = s.n('mybatis:stmt:' + namespace + '.' + id, 'SQL_STATEMENT', id, namespace + '.' + id,
        Object.assign({ statementType: type, namespace, statementId: id }, attrs || {}), at('src/main/resources/mapper/' + namespace + '.xml', nextLine(namespace)));
      if (table) {
        if (type === 'SELECT') s.e(stmt, 'READS', 'db:table:' + table);
        else s.e(stmt, 'WRITES', 'db:table:' + table, { op: type });
      }
      return stmt;
    },
    /** 엔드포인트와 핸들러로 가는 ROUTES_TO. */
    endpoint(httpMethod, path, handler, attrs) {
      const id = 'http:endpoint:' + httpMethod + ' ' + path;
      if (!s.graph().nodes.some((n) => n.id === id)) {
        s.n(id, 'HTTP_ENDPOINT', httpMethod + ' ' + path, httpMethod + ' ' + path, Object.assign({ httpMethod, path }, attrs || {}));
      }
      if (handler) s.e(id, 'ROUTES_TO', handler);
      return id;
    },
    graph: () => s.graph()
  };
  return api;
}

/**
 * 제네릭 상위 DAO·서비스를 쓰는 프로젝트(jeesite의 CrudDao<T>, CrudService<D, T> 꼴). 표준 산출물 테스트의 GenericDaoGraph와 같다.
 *   CrudDao#findList, #insert ~EXECUTES(INFERRED)→ DictDao·UserDao·LogDao의 같은 id 구문 (하위 매퍼마다 하나)
 *   CrudService#findPage ─CALLS→ CrudDao#findList, CrudService#save ─CALLS→ CrudDao#insert (수신자 타입 없음)
 *   GET /dict → DictController#list ─(receiverType=DictService)→ CrudService#findPage
 *   GET /dict/direct → DictController#direct ─(receiverType=DictDao)→ CrudDao#findList
 *   POST /user → UserController#save → UserService#save ─(super)→ CrudService#save   (타입 인자 UserDao, LogDao도 주입)
 *   POST /mix → MixController#save ─(receiverType=MixService)→ CrudService#save      (DictDao·LogDao를 참조)
 *   GET /raw → RawController#all → CrudDao#findList (수신자 타입 모름), GET /odd → OddController#x ─(receiverType=OrphanDao)→ CrudDao#findList
 */
function genericDao() {
  const j = javaGraph();
  const P = 'com.g.';
  const f = { j, P };
  ['SYS_DICT', 'SYS_USER', 'SYS_LOG'].forEach((t) => j.table(t));
  j.type(P + 'CrudDao', { kind: 'interface', attrs: { typeParameters: ['T'] } });
  f.daoFindList = j.method(P + 'CrudDao', 'findList', ['java.lang.Object'], { modifiers: ['public', 'abstract'] });
  f.daoInsert = j.method(P + 'CrudDao', 'insert', ['java.lang.Object'], { modifiers: ['public', 'abstract'] });
  ['DictDao', 'UserDao', 'LogDao', 'OrphanDao'].forEach((dao) => {
    j.type(P + dao, { kind: 'interface' });
    j.typeEdge(P + dao, 'EXTENDS', P + 'CrudDao');
  });
  f.dictFindList = j.statement(P + 'DictDao', 'findList', 'SELECT', 'SYS_DICT');
  f.userFindList = j.statement(P + 'UserDao', 'findList', 'SELECT', 'SYS_USER');
  f.logFindList = j.statement(P + 'LogDao', 'findList', 'SELECT', 'SYS_LOG');
  f.dictInsert = j.statement(P + 'DictDao', 'insert', 'INSERT', 'SYS_DICT');
  f.userInsert = j.statement(P + 'UserDao', 'insert', 'INSERT', 'SYS_USER');
  f.logInsert = j.statement(P + 'LogDao', 'insert', 'INSERT', 'SYS_LOG');
  // 링커가 남기는 꼴 세 가지: namespace + inherited, namespace + declaredIn, 엣지 attrs 없음(구문 노드의 namespace로 읽는다)
  const inherited = (method, stmt, attrs) => j.s.e(method, 'EXECUTES', stmt, attrs, 'INFERRED', j.at('src/main/java/com/g/CrudDao.java', 10));
  inherited(f.daoFindList, f.dictFindList, { namespace: P + 'DictDao', inherited: true });
  inherited(f.daoFindList, f.userFindList, { namespace: P + 'UserDao', declaredIn: 'java:type:' + P + 'CrudDao' });
  inherited(f.daoFindList, f.logFindList, {});
  inherited(f.daoInsert, f.dictInsert, { namespace: P + 'DictDao', inherited: true });
  inherited(f.daoInsert, f.userInsert, { namespace: P + 'UserDao', inherited: true });
  inherited(f.daoInsert, f.logInsert, { namespace: P + 'LogDao', inherited: true });

  j.type(P + 'CrudService', { modifiers: ['public', 'abstract'], attrs: { typeParameters: ['D extends CrudDao<T>', 'T'] } });
  f.serviceFindPage = j.method(P + 'CrudService', 'findPage', ['java.lang.Object']);
  f.serviceSave = j.method(P + 'CrudService', 'save', ['java.lang.Object']);
  j.call(f.serviceFindPage, f.daoFindList, 30, 'interface');
  j.call(f.serviceSave, f.daoInsert, 40, 'interface');
  j.typeEdge(P + 'CrudService', 'DEPENDS_ON', P + 'CrudDao');
  // DictService: 타입 인자는 DEPENDS_ON으로만 드러난다
  j.type(P + 'DictService', { attrs: { stereotype: 'service' } });
  j.typeEdge(P + 'DictService', 'EXTENDS', P + 'CrudService');
  j.typeEdge(P + 'DictService', 'DEPENDS_ON', P + 'DictDao');
  // UserService: 상위 타입의 타입 인자가 엣지에 있다. save를 재정의하고 super.save를 부른다. 다른 DAO(LogDao)도 주입받는다
  j.type(P + 'UserService', { attrs: { stereotype: 'service' } });
  j.typeEdge(P + 'UserService', 'EXTENDS', P + 'CrudService', { typeArguments: [P + 'UserDao', P + 'User'] });
  j.typeEdge(P + 'UserService', 'INJECTS', P + 'LogDao');
  f.userServiceSave = j.method(P + 'UserService', 'save', ['java.lang.Object']);
  j.s.e(f.userServiceSave, 'OVERRIDES', f.serviceSave);
  j.call(f.userServiceSave, f.serviceSave, 20, 'super');
  // MixService: 타입 인자를 모르고 DAO 둘을 참조한다
  j.type(P + 'MixService', { attrs: { stereotype: 'service' } });
  j.typeEdge(P + 'MixService', 'EXTENDS', P + 'CrudService');
  j.typeEdge(P + 'MixService', 'DEPENDS_ON', P + 'DictDao');
  j.typeEdge(P + 'MixService', 'INJECTS', P + 'LogDao');

  const endpoint = (httpMethod, path, controller, handler, target, receiverType) => {
    if (!j.graph().nodes.some((n) => n.id === 'java:type:' + P + controller)) j.type(P + controller, { attrs: { stereotype: 'controller' } });
    const method = j.method(P + controller, handler);
    j.call(method, target, 60, target.indexOf('CrudDao#') >= 0 ? 'interface' : 'virtual', receiverType);
    return j.endpoint(httpMethod, path, method);
  };
  f.getDict = endpoint('GET', '/dict', 'DictController', 'list', f.serviceFindPage, P + 'DictService');
  f.direct = j.method(P + 'DictController', 'direct');
  j.call(f.direct, f.daoFindList, 50, 'interface', P + 'DictDao');
  f.getDictDirect = j.endpoint('GET', '/dict/direct', f.direct);
  f.postUser = endpoint('POST', '/user', 'UserController', 'save', f.userServiceSave, null);
  f.postMix = endpoint('POST', '/mix', 'MixController', 'save', f.serviceSave, P + 'MixService');
  f.getRaw = endpoint('GET', '/raw', 'RawController', 'all', f.daoFindList, null);
  f.getOdd = endpoint('GET', '/odd', 'OddController', 'x', f.daoFindList, P + 'OrphanDao');
  return f;
}

/** BaseServiceImpl#selectAll에서 매퍼마다 나가는 리플렉션 호출(receiverType=매퍼, viaTypes=그 매퍼로 타입 인자를 고정한 하위 서비스). */
function reflection() {
  const j = javaGraph();
  const f = { j };
  j.type('com.r.BaseServiceImpl', { modifiers: ['public', 'abstract'] });
  f.select = j.method('com.r.BaseServiceImpl', 'selectAll');
  ['User', 'Role'].forEach((name) => {
    const mapper = 'com.r.' + name + 'Mapper';
    j.type(mapper, { kind: 'interface', attrs: { stereotype: 'mapper' } });
    const method = j.method(mapper, 'selectAll', [], { modifiers: ['public', 'abstract'] });
    j.table('T_' + name.toUpperCase());
    const stmt = j.statement(mapper, 'selectAll', 'SELECT', 'T_' + name.toUpperCase());
    j.s.e(method, 'EXECUTES', stmt);
    const service = 'com.r.' + name + 'ServiceImpl';
    j.type(service);
    j.typeEdge(service, 'EXTENDS', 'com.r.BaseServiceImpl');
    j.type('com.r.' + name + 'Service', { kind: 'interface' });
    j.typeEdge(service, 'IMPLEMENTS', 'com.r.' + name + 'Service');
    j.call(f.select, method, 20, 'reflection', mapper, { viaTypes: [service] }, 'INFERRED');
    f[name.toLowerCase() + 'Mapper'] = method;
  });
  j.type('com.r.UserController');
  f.controller = j.method('com.r.UserController', 'list');
  j.call(f.controller, f.select, 9, 'virtual', 'com.r.UserServiceImpl');
  // 수신자의 정적 타입이 인터페이스여도 그 구현 타입이 viaTypes에 있으면 맞는다
  f.byInterface = j.method('com.r.UserController', 'byInterface');
  j.call(f.byInterface, f.select, 19, 'interface', 'com.r.RoleService');
  // 대상 타입이 정해진 리플렉션 호출(viaTypes 없음)은 팬아웃이 아니다
  j.type('com.r.Tool');
  f.direct = j.method('com.r.Tool', 'invoke');
  j.call(f.direct, f.userMapper, 7, 'reflection', 'com.r.UserMapper', null, 'INFERRED');
  return f;
}

function policies() {
  const j = javaGraph();
  const P = 'com.d.';
  const f = { j, P };
  j.type(P + 'DiscountPolicy', { kind: 'interface' });
  j.type(P + 'DefaultDiscountPolicy');
  j.type(P + 'VipDiscountPolicy');
  j.typeEdge(P + 'DefaultDiscountPolicy', 'IMPLEMENTS', P + 'DiscountPolicy');
  j.typeEdge(P + 'VipDiscountPolicy', 'IMPLEMENTS', P + 'DiscountPolicy');
  f.discount = j.method(P + 'DiscountPolicy', 'discount', [], { modifiers: ['public', 'abstract'] });
  f.defaultDiscount = j.method(P + 'DefaultDiscountPolicy', 'discount');
  f.vipDiscount = j.method(P + 'VipDiscountPolicy', 'discount');
  j.s.e(f.defaultDiscount, 'OVERRIDES', f.discount);
  j.s.e(f.vipDiscount, 'OVERRIDES', f.discount);
  const caller = (name) => {
    j.type(P + name);
    const m = j.method(P + name, 'run');
    j.call(m, f.discount, 12, 'interface');
    return m;
  };
  // XML 주입: 구현 타입으로 바로 가는 RESOLVED INJECTS(resolvedFrom=spring:bean:…, via=xml)
  f.xml = caller('XmlService');
  j.typeEdge(P + 'XmlService', 'INJECTS', P + 'VipDiscountPolicy', { via: 'xml', resolvedFrom: 'spring:bean:vipPolicy', property: 'policy' });
  // 필드 타입이 구현 클래스인 주입
  f.field = caller('FieldService');
  j.typeEdge(P + 'FieldService', 'INJECTS', P + 'DefaultDiscountPolicy', { via: 'field', field: 'policy' });
  // 주입 정보 없음
  f.none = caller('PlainService');
  // 인터페이스로 가는 후보 없는 주입과 구현 타입 주입이 함께: 어느 구현이 들어오는지 모른다
  f.mixed = caller('MixedService');
  j.typeEdge(P + 'MixedService', 'INJECTS', P + 'DiscountPolicy', { via: 'field', field: 'policy' });
  j.typeEdge(P + 'MixedService', 'INJECTS', P + 'VipDiscountPolicy', { via: 'field', field: 'vip' });
  return f;
}

function modules() {
  const j = javaGraph();
  const f = { j };
  j.type('com.m.admin.UserController', { root: 'web-admin/', attrs: { stereotype: 'controller' } });
  j.type('com.m.front.UserController', { root: 'web-front/', attrs: { stereotype: 'controller' } });
  f.admin = j.method('com.m.admin.UserController', 'list', [], { root: 'web-admin/' });
  f.front = j.method('com.m.front.UserController', 'list', [], { root: 'web-front/' });
  f.endpoint = j.endpoint('GET', '/user/list', f.front);
  j.endpoint('GET', '/user/list', f.admin);
  const page = (path, line, matches) => {
    const jsp = j.s.n('jsp:page:' + path, 'JSP_PAGE', path.substring(path.lastIndexOf('/') + 1), path, { pageType: 'jsp' }, j.at(path, 1));
    const request = j.s.n('http:request:GET /user/list@' + path + ':' + line, 'HTTP_REQUEST', 'GET /user/list', 'GET /user/list',
      { httpMethod: 'GET', path: '/user/list', api: 'link' }, j.at(path, line));
    j.s.e(jsp, 'REQUESTS', request, {}, 'RESOLVED', j.at(path, line));
    j.s.e(request, 'MATCHES', f.endpoint, matches || {});
    return jsp;
  };
  f.adminJsp = page('web-admin/src/main/webapp/WEB-INF/views/user.jsp', 3);
  f.frontJsp = page('web-front/src/main/webapp/WEB-INF/views/user.jsp', 4);
  // 같은 모듈에 핸들러가 없는 화면: 링커가 MATCHES에 crossModule=true를 남긴다
  f.otherJsp = page('web-other/src/main/webapp/index.jsp', 9, { crossModule: true });
  return f;
}

function accessors() {
  const j = javaGraph();
  const P = 'com.a.';
  const f = { j, P };
  j.type(P + 'Svc');
  f.run = j.method(P + 'Svc', 'run');
  j.type(P + 'Vo');
  // 접근자는 같은 타입에 대응하는 필드가 있어야 한다(F-015-R43)
  ['name', 'open', 'total', 'key'].forEach((n) => j.s.n('java:field:' + P + (n === 'key' ? 'Base' : 'Vo') + '#' + n, 'JAVA_FIELD', n, P + 'Vo#' + n, {}));
  f.getName = j.method(P + 'Vo', 'getName');
  f.isOpen = j.method(P + 'Vo', 'isOpen');
  f.setCode = j.method(P + 'Vo', 'setCode', ['java.lang.String'], { attrs: { synthetic: true, lombok: 'Setter' } });
  f.builder = j.method(P + 'Vo', 'builder', [], { attrs: { synthetic: true, lombok: 'Builder' } });
  f.getTotal = j.method(P + 'Vo', 'getTotal'); // 다른 메서드를 부르는 getter: 접지 않는다
  f.settle = j.method(P + 'Vo', 'settle');     // 이름이 set + 소문자: 접근자가 아니다
  f.issue = j.method(P + 'Vo', 'issue');
  f.ctor = j.method(P + 'Vo', '<init>', [], { attrs: { constructor: true } });
  j.call(f.getTotal, f.settle, 3);
  // 재정의한 쪽이 호출을 하면 상위의 getter도 접지 않는다
  j.type(P + 'Base');
  f.getKey = j.method(P + 'Base', 'getKey');
  j.type(P + 'Derived');
  f.derivedGetKey = j.method(P + 'Derived', 'getKey');
  j.typeEdge(P + 'Derived', 'EXTENDS', P + 'Base');
  j.s.e(f.derivedGetKey, 'OVERRIDES', f.getKey);
  j.call(f.derivedGetKey, f.settle, 4);
  // 소스 없는 메서드: external=true인 것, 소유 타입이 external인 것, declared=false인 것
  j.s.n('java:type:org.lib.Tool', 'JAVA_TYPE', 'Tool', 'org.lib.Tool', { declared: false, external: true });
  f.extMethod = j.s.n('java:method:org.lib.Tool#help()', 'JAVA_METHOD', 'help', 'org.lib.Tool#help()', { declared: false, external: true });
  f.inherited = j.s.n('java:method:org.lib.Parent#inherited(?)', 'JAVA_METHOD', 'inherited', 'org.lib.Parent#inherited(?)', { declared: false });
  let line = 10;
  [f.getName, f.isOpen, f.setCode, f.builder, f.getTotal, f.settle, f.issue, f.ctor, f.getKey, f.extMethod, f.inherited].forEach((to) => {
    j.call(f.run, to, line += 1, to === f.ctor ? 'constructor' : 'virtual', null, null, to === f.inherited ? 'INFERRED' : 'RESOLVED');
  });
  return f;
}

function dataLoads() {
  const j = javaGraph();
  const f = { j };
  ['SYS_USER', 'SYS_AREA', 'SYS_DICT'].forEach((t) => j.table(t));
  f.select = j.statement('com.l.UserDao', 'findList', 'SELECT', 'SYS_USER');
  const load = (file, line, table, rowCount) => {
    const id = j.s.n('sql:stmt:' + file + ':' + line, 'SQL_STATEMENT', file + ':' + line, file + ':' + line,
      Object.assign({ statementType: 'INSERT', dataLoad: true }, rowCount == null ? {} : { rowCount }), j.at(file, line));
    j.s.e(id, 'WRITES', 'db:table:' + table, { op: 'INSERT' });
    return id;
  };
  f.loadUser = load('db/data/init.sql', 3, 'SYS_USER', 120);
  f.loadArea = load('db/data/init.sql', 200, 'SYS_AREA', 30);
  f.loadDict = load('db/data/dict.sql', 1, 'SYS_DICT', 5);
  f.loadUnknown = load('db/data/dict.sql', 90, 'SYS_AREA');
  return f;
}

module.exports = { javaGraph, genericDao, reflection, policies, modules, accessors, dataLoads };
