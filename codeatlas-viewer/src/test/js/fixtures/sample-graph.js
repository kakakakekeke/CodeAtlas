'use strict';
// F-016 테스트 픽스처. 그래프 계약(docs/specs/F-001-analysis-pipeline/design.md)대로 손으로 만든 작은 쇼핑몰 그래프.
// `node sample-graph.js --write` 로 src/test/resources/fixtures/sample-graph.json 을 다시 만든다.

const nodes = [];
const edges = [];
const diagnostics = [];

function loc(file, line, endLine) {
  return { file, startLine: line, startColumn: 1, endLine: endLine || line, endColumn: 1 };
}
function n(id, kind, name, qualifiedName, location, attrs) {
  nodes.push({ id, kind, name, qualifiedName: qualifiedName || name, location: location || null,
    attrs: Object.assign({ declared: true }, attrs || {}) });
  return id;
}
function e(from, kind, to, opts) {
  const o = opts || {};
  edges.push({ from, to, kind, confidence: o.confidence || 'RESOLVED', location: o.location || null, attrs: o.attrs || {} });
}

// ---------- 파일
const F = {
  controller: 'src/main/java/com/shop/web/OrderController.java',
  service: 'src/main/java/com/shop/service/OrderService.java',
  serviceImpl: 'src/main/java/com/shop/service/OrderServiceImpl.java',
  base: 'src/main/java/com/shop/service/BaseService.java',
  mapper: 'src/main/java/com/shop/mapper/OrderMapper.java',
  order: 'src/main/java/com/shop/domain/Order.java',
  item: 'src/main/java/com/shop/domain/OrderItem.java',
  status: 'src/main/java/com/shop/domain/OrderStatus.java',
  tasklet: 'src/main/java/com/shop/batch/SettlementTasklet.java',
  mapperXml: 'src/main/resources/mapper/OrderMapper.xml',
  settleXml: 'src/main/resources/mapper/SettlementMapper.xml',
  ddl: 'ddl/schema.sql',
  pkg: 'ddl/pkg_order.pkb',
  js: 'src/main/webapp/js/order.js',
  listJsp: 'src/main/webapp/WEB-INF/views/order/list.jsp',
  detailJsp: 'src/main/webapp/WEB-INF/views/order/detail.jsp',
  headerJsp: 'src/main/webapp/WEB-INF/views/common/header.jsp',
};
n('file:' + F.controller, 'FILE', 'OrderController.java', F.controller);
n('file:' + F.mapperXml, 'FILE', 'OrderMapper.xml', F.mapperXml);
n('file:' + F.ddl, 'FILE', 'schema.sql', F.ddl);

// ---------- Java 패키지와 타입
const P = (name) => n('java:package:' + name, 'JAVA_PACKAGE', name.split('.').pop(), name);
P('com.shop.web'); P('com.shop.service'); P('com.shop.mapper'); P('com.shop.domain'); P('com.shop.batch');

function type(fqn, file, line, attrs) {
  const id = 'java:type:' + fqn;
  const simple = fqn.substring(fqn.lastIndexOf('.') + 1);
  n(id, 'JAVA_TYPE', simple, fqn, file ? loc(file, line, line + 40) : null, attrs);
  const pkg = 'java:package:' + fqn.substring(0, fqn.lastIndexOf('.'));
  if (nodes.some((x) => x.id === pkg)) e(pkg, 'CONTAINS', id);
  return id;
}
const T = {
  controller: type('com.shop.web.OrderController', F.controller, 12, { typeKind: 'class', modifiers: ['public'], stereotype: 'controller' }),
  service: type('com.shop.service.OrderService', F.service, 8, { typeKind: 'interface', modifiers: ['public'] }),
  serviceImpl: type('com.shop.service.OrderServiceImpl', F.serviceImpl, 15, { typeKind: 'class', modifiers: ['public'], stereotype: 'service' }),
  base: type('com.shop.service.BaseService', F.base, 5, { typeKind: 'class', modifiers: ['public', 'abstract'], typeParameters: ['T'] }),
  mapper: type('com.shop.mapper.OrderMapper', F.mapper, 9, { typeKind: 'interface', modifiers: ['public'], stereotype: 'mapper' }),
  order: type('com.shop.domain.Order', F.order, 6, { typeKind: 'class', modifiers: ['public'] }),
  item: type('com.shop.domain.OrderItem', F.item, 4, { typeKind: 'class', modifiers: ['public'] }),
  status: type('com.shop.domain.OrderStatus', F.status, 3, { typeKind: 'enum', modifiers: ['public'] }),
  tasklet: type('com.shop.batch.SettlementTasklet', F.tasklet, 10, { typeKind: 'class', modifiers: ['public'], stereotype: 'component' }),
};
// 소스가 없는 라이브러리 타입
nodes.push({ id: 'java:type:org.springframework.batch.core.step.tasklet.Tasklet', kind: 'JAVA_TYPE', name: 'Tasklet',
  qualifiedName: 'org.springframework.batch.core.step.tasklet.Tasklet', location: null,
  attrs: { declared: false, typeKind: 'interface', external: true } });
const TASKLET_IF = 'java:type:org.springframework.batch.core.step.tasklet.Tasklet';

e(T.serviceImpl, 'EXTENDS', T.base);
e(T.serviceImpl, 'IMPLEMENTS', T.service);
e(T.tasklet, 'IMPLEMENTS', TASKLET_IF);
e(T.controller, 'ASSOCIATES', T.service, { attrs: { field: 'orderService', multiplicity: '1' } });
e(T.serviceImpl, 'ASSOCIATES', T.mapper, { attrs: { field: 'orderMapper', multiplicity: '1' } });
e(T.order, 'ASSOCIATES', T.item, { attrs: { field: 'items', multiplicity: '*' } });
e(T.order, 'ASSOCIATES', T.status, { attrs: { field: 'status', multiplicity: '1' } });
e(T.controller, 'DEPENDS_ON', T.order);
e(T.service, 'DEPENDS_ON', T.order);
e(T.serviceImpl, 'DEPENDS_ON', T.order);
e(T.mapper, 'DEPENDS_ON', T.order);
e(T.mapper, 'DEPENDS_ON', T.item);
e(T.tasklet, 'ASSOCIATES', T.mapper, { attrs: { field: 'orderMapper', multiplicity: '1' } });
e(T.controller, 'INJECTS', T.service, { attrs: { via: 'constructor' } });
e(T.serviceImpl, 'INJECTS', T.mapper, { attrs: { via: 'field' } });

// ---------- 필드
function field(typeId, name, fieldType, modifiers, file, line) {
  const id = 'java:field:' + typeId.substring('java:type:'.length) + '#' + name;
  n(id, 'JAVA_FIELD', name, typeId.substring('java:type:'.length) + '#' + name, loc(file, line), { modifiers, type: fieldType });
  e(typeId, 'CONTAINS', id);
  return id;
}
const FLD = {
  orderMapper: field(T.serviceImpl, 'orderMapper', 'com.shop.mapper.OrderMapper', ['private', 'final'], F.serviceImpl, 17),
  orderId: field(T.order, 'id', 'long', ['private'], F.order, 7),
  orderItems: field(T.order, 'items', 'java.util.List<com.shop.domain.OrderItem>', ['private'], F.order, 8),
  orderStatus: field(T.order, 'status', 'com.shop.domain.OrderStatus', ['private'], F.order, 9),
  maxRetry: field(T.base, 'MAX_RETRY', 'int', ['protected', 'static', 'final'], F.base, 6),
};

// ---------- 메서드
function method(typeId, name, params, returnType, modifiers, file, line, extra) {
  const owner = typeId.substring('java:type:'.length);
  const id = 'java:method:' + owner + '#' + name + '(' + params.join(',') + ')';
  const shortParams = params.map((p) => p.substring(p.lastIndexOf('.') + 1));
  n(id, 'JAVA_METHOD', name, owner + '#' + name + '(' + shortParams.join(', ') + ')', loc(file, line, line + 8),
    Object.assign({ modifiers, returnType, parameterTypes: params,
      signature: name + '(' + shortParams.join(', ') + '): ' + returnType.substring(returnType.lastIndexOf('.') + 1) }, extra || {}));
  e(typeId, 'CONTAINS', id);
  return id;
}
const ORDER = 'com.shop.domain.Order';
const M = {
  ctlList: method(T.controller, 'list', ['org.springframework.ui.Model'], 'java.lang.String', ['public'], F.controller, 20),
  ctlDetail: method(T.controller, 'detail', ['long'], 'java.lang.String', ['public'], F.controller, 30),
  ctlCreate: method(T.controller, 'create', [ORDER], ORDER, ['public'], F.controller, 40),
  ctlClose: method(T.controller, 'close', ['long'], 'void', ['public'], F.controller, 50),
  svcFind: method(T.service, 'findOrders', [], 'java.util.List', ['public', 'abstract'], F.service, 9),
  svcPlace: method(T.service, 'placeOrder', [ORDER], ORDER, ['public', 'abstract'], F.service, 10),
  svcClose: method(T.service, 'closeOrder', ['long'], 'void', ['public', 'abstract'], F.service, 11),
  implFind: method(T.serviceImpl, 'findOrders', [], 'java.util.List', ['public'], F.serviceImpl, 22),
  implPlace: method(T.serviceImpl, 'placeOrder', [ORDER], ORDER, ['public'], F.serviceImpl, 30),
  implClose: method(T.serviceImpl, 'closeOrder', ['long'], 'void', ['public'], F.serviceImpl, 50),
  implValidate: method(T.serviceImpl, 'validate', [ORDER], 'void', ['private'], F.serviceImpl, 60),
  implRetry: method(T.serviceImpl, 'retry', [ORDER], 'void', ['private'], F.serviceImpl, 70),
  baseLog: method(T.base, 'log', ['java.lang.String'], 'void', ['protected'], F.base, 8),
  mapSelect: method(T.mapper, 'selectOrders', [], 'java.util.List', ['public', 'abstract'], F.mapper, 10),
  mapSelectOne: method(T.mapper, 'selectOrder', ['long'], ORDER, ['public', 'abstract'], F.mapper, 11),
  mapInsert: method(T.mapper, 'insertOrder', [ORDER], 'int', ['public', 'abstract'], F.mapper, 12),
  mapInsertItem: method(T.mapper, 'insertItem', ['com.shop.domain.OrderItem'], 'int', ['public', 'abstract'], F.mapper, 13),
  mapClose: method(T.mapper, 'callCloseOrder', ['long'], 'void', ['public', 'abstract'], F.mapper, 14),
  mapDelete: method(T.mapper, 'deleteItems', ['long'], 'int', ['public', 'abstract'], F.mapper, 15),
  taskletExec: method(T.tasklet, 'execute', ['org.springframework.batch.core.StepContribution', 'org.springframework.batch.core.scope.context.ChunkContext'],
    'org.springframework.batch.repeat.RepeatStatus', ['public'], F.tasklet, 18),
  orderCtor: method(T.order, '<init>', [], 'void', ['public'], F.order, 11, { constructor: true }),
};

e(M.implFind, 'OVERRIDES', M.svcFind);
e(M.implPlace, 'OVERRIDES', M.svcPlace);
e(M.implClose, 'OVERRIDES', M.svcClose);

const call = (from, to, line, callType, file) => e(from, 'CALLS', to, { location: loc(file, line), attrs: { callType } });
call(M.ctlList, M.svcFind, 22, 'interface', F.controller);
call(M.ctlDetail, M.svcFind, 32, 'interface', F.controller);
call(M.ctlCreate, M.svcPlace, 42, 'interface', F.controller);
call(M.ctlClose, M.svcClose, 52, 'interface', F.controller);
call(M.implFind, M.mapSelect, 24, 'interface', F.serviceImpl);
call(M.implPlace, M.implValidate, 31, 'virtual', F.serviceImpl);
call(M.implPlace, M.mapInsert, 33, 'interface', F.serviceImpl);
call(M.implPlace, M.mapInsertItem, 35, 'interface', F.serviceImpl);
call(M.implPlace, M.mapInsertItem, 38, 'interface', F.serviceImpl); // 같은 대상을 두 곳에서 호출
call(M.implPlace, M.baseLog, 40, 'super', F.serviceImpl);
call(M.implPlace, M.implRetry, 44, 'virtual', F.serviceImpl);
call(M.implRetry, M.implPlace, 72, 'virtual', F.serviceImpl); // 순환
call(M.implClose, M.mapClose, 52, 'interface', F.serviceImpl);
call(M.implClose, M.mapDelete, 53, 'interface', F.serviceImpl);
call(M.taskletExec, M.mapSelect, 20, 'interface', F.tasklet);
e(M.ctlCreate, 'INSTANTIATES', T.order, { location: loc(F.controller, 41) });
e(M.implPlace, 'ACCESSES', FLD.orderMapper, { location: loc(F.serviceImpl, 33), attrs: { access: 'read' } });

// 미해결 호출
n('unresolved:java-call:paymentGateway.charge', 'UNRESOLVED', 'paymentGateway.charge', 'paymentGateway.charge', null, {});
e(M.implPlace, 'CALLS', 'unresolved:java-call:paymentGateway.charge',
  { confidence: 'UNRESOLVED', location: loc(F.serviceImpl, 36), attrs: { callType: 'virtual' } });
// 마무리 단계가 만든 미해결 노드 (엣지가 없는 노드를 가리켰다)
nodes.push({ id: 'java:method:com.shop.legacy.LegacyDao#find()', kind: 'UNRESOLVED', name: 'java:method:com.shop.legacy.LegacyDao#find()',
  qualifiedName: 'java:method:com.shop.legacy.LegacyDao#find()', location: null, attrs: { declared: false, expectedPrefix: 'java:method' } });
e(M.implFind, 'CALLS', 'java:method:com.shop.legacy.LegacyDao#find()', { confidence: 'UNRESOLVED', location: loc(F.serviceImpl, 26) });

// ---------- Spring
n('spring:bean:orderServiceImpl', 'SPRING_BEAN', 'orderServiceImpl', 'orderServiceImpl', loc(F.serviceImpl, 14));
e(T.serviceImpl, 'DEFINES_BEAN', 'spring:bean:orderServiceImpl');
function endpoint(httpMethod, path, handler, line) {
  const id = 'http:endpoint:' + httpMethod + ' ' + path;
  n(id, 'HTTP_ENDPOINT', httpMethod + ' ' + path, httpMethod + ' ' + path, loc(F.controller, line), { httpMethod, path });
  e(id, 'ROUTES_TO', handler, { location: loc(F.controller, line) });
  return id;
}
const EP = {
  list: endpoint('GET', '/orders', M.ctlList, 19),
  detail: endpoint('GET', '/orders/{id}', M.ctlDetail, 29),
  create: endpoint('POST', '/orders', M.ctlCreate, 39),
  close: endpoint('POST', '/orders/{id}/close', M.ctlClose, 49),
};

// ---------- MyBatis
const NS = 'com.shop.mapper.OrderMapper';
n('mybatis:mapper:' + NS, 'MYBATIS_MAPPER', 'OrderMapper', NS, loc(F.mapperXml, 3));
function stmt(ns, id, statementType, line, sql, file, extra) {
  const nodeId = 'mybatis:stmt:' + ns + '.' + id;
  n(nodeId, 'SQL_STATEMENT', id, ns + '.' + id, loc(file || F.mapperXml, line, line + 6),
    Object.assign({ statementType, sql, dynamic: false, dialect: 'oracle', namespace: ns, statementId: id }, extra || {}));
  if (nodes.some((x) => x.id === 'mybatis:mapper:' + ns)) e('mybatis:mapper:' + ns, 'CONTAINS', nodeId);
  return nodeId;
}
const S = {
  select: stmt(NS, 'selectOrders', 'SELECT', 10, 'SELECT O.ORDER_ID, O.STATUS, C.NAME FROM ORDERS O JOIN CUSTOMERS C ON C.CUSTOMER_ID = O.CUSTOMER_ID'),
  selectOne: stmt(NS, 'selectOrder', 'SELECT', 20, 'SELECT * FROM ORDERS O JOIN ORDER_ITEMS I ON I.ORDER_ID = O.ORDER_ID WHERE O.ORDER_ID = ?'),
  insert: stmt(NS, 'insertOrder', 'INSERT', 30, 'INSERT INTO ORDERS (ORDER_ID, CUSTOMER_ID, STATUS) VALUES (SEQ_ORDER.NEXTVAL, ?, ?)'),
  insertItem: stmt(NS, 'insertItem', 'INSERT', 40, 'INSERT INTO ORDER_ITEMS (ITEM_ID, ORDER_ID, PRODUCT_ID, QTY) SELECT ?, ?, P.PRODUCT_ID, ? FROM PRODUCTS P WHERE P.PRODUCT_ID = ?'),
  close: stmt(NS, 'callCloseOrder', 'CALL', 50, '{ CALL PKG_ORDER.CLOSE_ORDER(?) }'),
  del: stmt(NS, 'deleteItems', 'DELETE', 56, 'DELETE FROM ORDER_ITEMS WHERE ORDER_ID = ?'),
  dynamic: stmt(NS, 'selectByDynamicTable', 'SELECT', 60, 'SELECT * FROM __DYNAMIC__ WHERE ID = ?', null, { dynamic: true }),
  settle: stmt('com.shop.mapper.SettlementMapper', 'mergeSettlement', 'MERGE', 8,
    'MERGE INTO SETTLEMENTS S USING (SELECT CUSTOMER_ID, COUNT(*) CNT FROM ORDERS GROUP BY CUSTOMER_ID) O ON (S.CUSTOMER_ID = O.CUSTOMER_ID) WHEN MATCHED THEN UPDATE SET S.CNT = O.CNT WHEN NOT MATCHED THEN INSERT (CUSTOMER_ID, CNT) VALUES (O.CUSTOMER_ID, O.CNT)', F.settleXml),
};
n('mybatis:fragment:' + NS + '.orderColumns', 'SQL_FRAGMENT', 'orderColumns', NS + '.orderColumns', loc(F.mapperXml, 5));
e('mybatis:mapper:' + NS, 'CONTAINS', 'mybatis:fragment:' + NS + '.orderColumns');
e(S.select, 'INCLUDES', 'mybatis:fragment:' + NS + '.orderColumns', { location: loc(F.mapperXml, 11) });
e(S.select, 'USES_TYPE', T.order, { attrs: { role: 'resultType' } });
e(S.insert, 'USES_TYPE', T.order, { attrs: { role: 'parameterType' } });

e(M.mapSelect, 'EXECUTES', S.select);
e(M.mapSelectOne, 'EXECUTES', S.selectOne);
e(M.mapInsert, 'EXECUTES', S.insert);
e(M.mapInsertItem, 'EXECUTES', S.insertItem);
e(M.mapClose, 'EXECUTES', S.close);
e(M.mapDelete, 'EXECUTES', S.del);

// ---------- DB
function table(name, line, columns, attrs) {
  const id = 'db:table:' + name;
  n(id, 'DB_TABLE', name, name, loc(F.ddl, line, line + columns.length + 1), attrs || { schema: 'SHOP' });
  columns.forEach((c, i) => {
    const cid = 'db:column:' + name + '.' + c[0];
    n(cid, 'DB_COLUMN', c[0], name + '.' + c[0], loc(F.ddl, line + 1 + i),
      { dataType: c[1], nullable: !c[2], primaryKey: c[2] === 'PK', ordinal: i + 1, comment: c[3] || '' });
    e(id, 'CONTAINS', cid);
  });
  return id;
}
const TB = {
  orders: table('ORDERS', 1, [['ORDER_ID', 'NUMBER(12)', 'PK', '주문 번호'], ['CUSTOMER_ID', 'NUMBER(12)', 'NN'], ['STATUS', 'VARCHAR2(10)', 'NN'], ['CREATED_AT', 'TIMESTAMP WITH TIME ZONE']],
    { schema: 'SHOP', comment: '주문' }),
  items: table('ORDER_ITEMS', 10, [['ITEM_ID', 'NUMBER(12)', 'PK'], ['ORDER_ID', 'NUMBER(12)', 'NN'], ['PRODUCT_ID', 'NUMBER(12)', 'NN'], ['QTY', 'NUMBER(5,0)']]),
  customers: table('CUSTOMERS', 20, [['CUSTOMER_ID', 'NUMBER(12)', 'PK'], ['NAME', 'VARCHAR2(100 CHAR)', 'NN']]),
  products: table('PRODUCTS', 26, [['PRODUCT_ID', 'NUMBER(12)', 'PK'], ['NAME', 'VARCHAR2(200)', 'NN'], ['PRICE', 'NUMBER(10,2)']]),
  settlements: table('SETTLEMENTS', 33, [['CUSTOMER_ID', 'NUMBER(12)', 'PK'], ['CNT', 'NUMBER(9)']]),
  coupons: table('COUPONS', 38, [['COUPON_ID', 'NUMBER(12)', 'PK'], ['CODE', 'VARCHAR2(20)']]), // 아무 관계도 없는 테이블
};
// DDL에 없는 테이블 (SQL에서 참조만 됨)
nodes.push({ id: 'db:table:ORDER_HISTORY', kind: 'DB_TABLE', name: 'ORDER_HISTORY', qualifiedName: 'ORDER_HISTORY', location: null, attrs: { declared: false } });
TB.history = 'db:table:ORDER_HISTORY';
n('db:sequence:SEQ_ORDER', 'DB_SEQUENCE', 'SEQ_ORDER', 'SEQ_ORDER', loc(F.ddl, 44));

e(TB.items, 'REFERENCES', TB.orders, { location: loc(F.ddl, 15),
  attrs: { columns: ['ORDER_ID'], refColumns: ['ORDER_ID'], constraint: 'FK_ITEMS_ORDER', source: 'DDL' } });
e(TB.orders, 'REFERENCES', TB.customers, { location: loc(F.ddl, 6),
  attrs: { columns: ['CUSTOMER_ID'], refColumns: ['CUSTOMER_ID'], constraint: 'FK_ORDERS_CUSTOMER', source: 'DDL' } });
e(TB.items, 'REFERENCES', TB.products, { confidence: 'INFERRED', location: loc(F.mapperXml, 41),
  attrs: { columns: ['PRODUCT_ID'], refColumns: ['PRODUCT_ID'], source: 'INFERRED', evidenceCount: 2 } });
e(TB.history, 'REFERENCES', TB.orders, { confidence: 'INFERRED', location: loc(F.pkg, 16),
  attrs: { columns: ['ORDER_ID'], refColumns: ['ORDER_ID'], source: 'INFERRED', evidenceCount: 1 } });
e(TB.orders, 'JOINS', TB.customers, { location: loc(F.mapperXml, 12),
  attrs: { leftColumns: ['CUSTOMER_ID'], rightColumns: ['CUSTOMER_ID'], statement: S.select } });

const reads = (s, t, line) => e(s, 'READS', t, { location: line ? loc(F.mapperXml, line) : null });
const writes = (s, t, op, line) => e(s, 'WRITES', t, { location: line ? loc(F.mapperXml, line) : null, attrs: { op } });
reads(S.select, TB.orders, 12); reads(S.select, TB.customers, 12);
reads(S.select, 'db:column:ORDERS.STATUS', 11); reads(S.select, 'db:column:CUSTOMERS.NAME', 11);
reads(S.selectOne, TB.orders, 21); reads(S.selectOne, TB.items, 21);
writes(S.insert, TB.orders, 'INSERT', 31);
writes(S.insert, 'db:column:ORDERS.STATUS', 'INSERT', 31);
writes(S.insertItem, TB.items, 'INSERT', 41); reads(S.insertItem, TB.products, 42);
writes(S.del, TB.items, 'DELETE', 57);
writes(S.settle, TB.settlements, 'MERGE'); reads(S.settle, TB.orders);
n('unresolved:sql-dynamic:' + S.dynamic, 'UNRESOLVED', '__DYNAMIC__', 'unresolved:sql-dynamic:' + S.dynamic, loc(F.mapperXml, 61), {});
e(S.dynamic, 'READS', 'unresolved:sql-dynamic:' + S.dynamic, { confidence: 'UNRESOLVED', location: loc(F.mapperXml, 61) });

// ---------- 프로시저
n('db:package:PKG_ORDER', 'DB_PACKAGE', 'PKG_ORDER', 'PKG_ORDER', loc(F.pkg, 1));
const ROUTINE = n('db:routine:PKG_ORDER.CLOSE_ORDER', 'DB_ROUTINE', 'CLOSE_ORDER', 'PKG_ORDER.CLOSE_ORDER', loc(F.pkg, 5, 20),
  { routineType: 'PROCEDURE', parameters: ['P_ORDER_ID IN NUMBER'], dialect: 'oracle' });
const ROUTINE_LOG = n('db:routine:PKG_ORDER.WRITE_LOG', 'DB_ROUTINE', 'WRITE_LOG', 'PKG_ORDER.WRITE_LOG', loc(F.pkg, 22, 30),
  { routineType: 'PROCEDURE', parameters: ['P_MSG IN VARCHAR2'], dialect: 'oracle' });
e('db:package:PKG_ORDER', 'CONTAINS', ROUTINE);
e('db:package:PKG_ORDER', 'CONTAINS', ROUTINE_LOG);
const RS1 = n('sql:stmt:PKG_ORDER.CLOSE_ORDER:8', 'SQL_STATEMENT', 'UPDATE@8', 'PKG_ORDER.CLOSE_ORDER:8', loc(F.pkg, 8, 10),
  { statementType: 'UPDATE', sql: "UPDATE ORDERS SET STATUS = 'CLOSED' WHERE ORDER_ID = P_ORDER_ID", dynamic: false, dialect: 'oracle' });
const RS2 = n('sql:stmt:PKG_ORDER.CLOSE_ORDER:12', 'SQL_STATEMENT', 'INSERT@12', 'PKG_ORDER.CLOSE_ORDER:12', loc(F.pkg, 12, 16),
  { statementType: 'INSERT', sql: 'INSERT INTO ORDER_HISTORY (ORDER_ID, CLOSED_AT) SELECT ORDER_ID, SYSDATE FROM ORDERS WHERE ORDER_ID = P_ORDER_ID', dynamic: false, dialect: 'oracle' });
e(ROUTINE, 'CONTAINS', RS1); e(ROUTINE, 'CONTAINS', RS2);
e(RS1, 'WRITES', TB.orders, { location: loc(F.pkg, 8), attrs: { op: 'UPDATE' } });
e(RS2, 'WRITES', TB.history, { location: loc(F.pkg, 12), attrs: { op: 'INSERT' } });
e(RS2, 'READS', TB.orders, { location: loc(F.pkg, 14) });
e(ROUTINE, 'CALLS', ROUTINE_LOG, { location: loc(F.pkg, 18) });
e(S.close, 'CALLS', ROUTINE, { location: loc(F.mapperXml, 51) });

// ---------- 화면
n('js:module:' + F.js, 'JS_MODULE', 'order.js', F.js, loc(F.js, 1));
function jsfn(path, line, attrs) {
  const id = 'js:function:' + F.js + '#' + path;
  n(id, 'JS_FUNCTION', path.split('.').pop(), F.js + '#' + path, loc(F.js, line, line + 8), Object.assign({ async: false, exported: true }, attrs || {}));
  return id;
}
const JS = {
  load: jsfn('loadOrders', 3, { async: true }),
  submit: jsfn('submitOrder', 12),
  submitCb: jsfn('submitOrder.<anonymous>@14:20', 14, { exported: false }),
  legacy: jsfn('openLegacy', 24),
};
e('js:module:' + F.js, 'CONTAINS', JS.load);
e('js:module:' + F.js, 'CONTAINS', JS.submit);
e('js:module:' + F.js, 'CONTAINS', JS.legacy);
e(JS.submit, 'CONTAINS', JS.submitCb);
e(JS.submitCb, 'CALLS', JS.load, { location: loc(F.js, 16) });
function request(httpMethod, url, file, line, owner, attrs) {
  const id = 'http:request:' + httpMethod + ' ' + url + '@' + file + ':' + line;
  n(id, 'HTTP_REQUEST', httpMethod + ' ' + url, httpMethod + ' ' + url, loc(file, line), Object.assign({ httpMethod, url, dynamic: false, api: 'fetch' }, attrs || {}));
  e(owner, 'REQUESTS', id, { location: loc(file, line) });
  return id;
}
const RQ = {
  list: request('GET', '/orders', F.js, 5, JS.load),
  create: request('POST', '/orders', F.js, 13, JS.submit, { api: 'axios' }),
  legacy: request('GET', '/legacy/{*}', F.js, 25, JS.legacy, { dynamic: true, url: "'/legacy/' + code", api: 'jquery' }),
};
e(RQ.list, 'MATCHES', EP.list);
e(RQ.create, 'MATCHES', EP.create);

const JSP = {
  list: n('jsp:page:' + F.listJsp, 'JSP_PAGE', 'list.jsp', F.listJsp, loc(F.listJsp, 1)),
  detail: n('jsp:page:' + F.detailJsp, 'JSP_PAGE', 'detail.jsp', F.detailJsp, loc(F.detailJsp, 1)),
  header: n('jsp:page:' + F.headerJsp, 'JSP_PAGE', 'header.jsp', F.headerJsp, loc(F.headerJsp, 1)),
};
e(JSP.list, 'INCLUDES', JSP.header, { location: loc(F.listJsp, 2) });
e(JSP.detail, 'INCLUDES', JSP.header, { location: loc(F.detailJsp, 2) });
e(JSP.list, 'INCLUDES', 'js:module:' + F.js, { location: loc(F.listJsp, 6) });
RQ.detailLink = request('GET', '/orders/{*}', F.listJsp, 20, JSP.list, { dynamic: true, url: '/orders/${order.id}', api: 'link' });
e(RQ.detailLink, 'MATCHES', EP.detail, { confidence: 'INFERRED' });
RQ.closeForm = request('POST', '/orders/{*}/close', F.detailJsp, 15, JSP.detail, { dynamic: true, url: '/orders/${order.id}/close', api: 'form' });
e(RQ.closeForm, 'MATCHES', EP.close, { confidence: 'INFERRED' });
e(M.ctlList, 'RENDERS', JSP.list, { location: loc(F.controller, 24) });
e(M.ctlDetail, 'RENDERS', JSP.detail, { location: loc(F.controller, 34) });

// ---------- 배치
n('batch:job:settlementJob', 'BATCH_JOB', 'settlementJob', 'settlementJob', loc(F.tasklet, 1));
n('batch:step:settlementJob/loadStep', 'BATCH_STEP', 'loadStep', 'settlementJob/loadStep', loc(F.tasklet, 2));
n('batch:step:settlementJob/mergeStep', 'BATCH_STEP', 'mergeStep', 'settlementJob/mergeStep', loc(F.tasklet, 3));
e('batch:job:settlementJob', 'CONTAINS', 'batch:step:settlementJob/loadStep');
e('batch:job:settlementJob', 'CONTAINS', 'batch:step:settlementJob/mergeStep');
e('batch:step:settlementJob/loadStep', 'NEXT', 'batch:step:settlementJob/mergeStep', { attrs: { on: 'COMPLETED' } });
e('batch:step:settlementJob/loadStep', 'USES', M.taskletExec, { attrs: { role: 'tasklet' } });
e('batch:step:settlementJob/mergeStep', 'EXECUTES', S.settle);

// ---------- 진단
diagnostics.push(
  { severity: 'ERROR', code: 'PARSE_FAILED', message: 'ddl/broken.sql: 12행에서 구문을 해석하지 못했다', location: loc('ddl/broken.sql', 12) },
  { severity: 'WARN', code: 'UNRESOLVED_REFERENCE', message: 'paymentGateway.charge 의 대상을 찾지 못했다', location: loc(F.serviceImpl, 36) },
  { severity: 'WARN', code: 'UNRESOLVED_REFERENCE', message: 'com.shop.legacy.LegacyDao#find() 의 정의가 없다', location: loc(F.serviceImpl, 26) },
  { severity: 'INFO', code: 'DYNAMIC_SQL', message: 'selectByDynamicTable: ${tableName} 치환이 테이블 자리에 있다', location: loc(F.mapperXml, 61) },
  { severity: 'WARN', code: 'UNSUPPORTED_SYNTAX', message: 'CONNECT BY 절은 건너뛰었다', location: null }
);

nodes.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
edges.sort((a, b) => {
  const ka = a.from + '\u0000' + a.to + '\u0000' + a.kind;
  const kb = b.from + '\u0000' + b.to + '\u0000' + b.kind;
  return ka < kb ? -1 : ka > kb ? 1 : 0;
});

const graph = {
  schemaVersion: 1,
  project: { name: 'sample-shop', root: '/work/sample-shop', analyzedAt: '2026-10-11T09:00:00Z' },
  nodes, edges, diagnostics,
};

/** 테스트가 서로의 변경에 영향받지 않게 매번 깊은 복사본을 준다. */
function sampleGraph() { return JSON.parse(JSON.stringify(graph)); }

module.exports = { sampleGraph, ids: { T, M, S, TB, EP, RQ, JS, JSP, FLD, ROUTINE, ROUTINE_LOG, RS1, RS2, F } };

if (require.main === module && process.argv.includes('--write')) {
  const fs = require('node:fs');
  const path = require('node:path');
  const out = path.join(__dirname, '..', '..', 'resources', 'fixtures', 'sample-graph.json');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(graph, null, 1) + '\n');
  console.log(out + ': 노드 ' + nodes.length + ', 엣지 ' + edges.length + ', 진단 ' + diagnostics.length);
}
