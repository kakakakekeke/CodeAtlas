'use strict';
// 노드 상세의 SQL 정리(F-016-R46).
// 사용자 요청(2026-10-12): 상세에서 SQL을 보여 줄 때 포매터를 썼으면 좋겠다. 분석기는 sql 속성을 공백 한 칸으로 줄여 한 줄에 담는다.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');
const { assemble } = require('./tools/assemble.js');
const { boot } = require('./tools/boot.js');
const { sampleGraph, ids } = require('./fixtures/sample-graph.js');

const sqlFormat = core('sqlformat');
const fmt = sqlFormat.format;
const lines = (...l) => l.join('\n');
/** 공백을 모두 지운 글자. 정리는 공백만 바꾸므로 원문과 같아야 한다. */
const squeeze = (s) => String(s).replace(/\s+/g, '');

function check(sql, want) {
  const got = fmt(sql);
  assert.equal(got, want);
  assert.equal(squeeze(got), squeeze(sql), '공백 말고는 원문과 같다');
}

test('F-016-AC88 SELECT: 절마다 줄을 바꾸고 선택 목록의 쉼표에서 줄을 바꿔 들여 쓴다. 조인은 ON 조건과 한 줄이다', () => {
  check('SELECT O.ORDER_ID, O.STATUS, C.NAME FROM ORDERS O JOIN CUSTOMERS C ON C.CUSTOMER_ID = O.CUSTOMER_ID', lines(
    'SELECT O.ORDER_ID,',
    '  O.STATUS,',
    '  C.NAME',
    'FROM ORDERS O',
    'JOIN CUSTOMERS C ON C.CUSTOMER_ID = O.CUSTOMER_ID'));
  check('SELECT * FROM __DYNAMIC__ WHERE ID = ?', lines('SELECT *', 'FROM __DYNAMIC__', 'WHERE ID = ?'));
});

test('F-016-AC88 WHERE: AND·OR에서 줄을 바꾸되 BETWEEN의 AND는 그대로 둔다. 하위 쿼리는 괄호 안을 한 단계 더 들여 쓴다. FROM의 쉼표도 줄을 바꾼다', () => {
  check("SELECT A.ID FROM T_A A, T_B B WHERE A.ID = B.ID AND A.DT BETWEEN ? AND ? OR A.CD IN (SELECT CD FROM T_C WHERE USE_YN = 'Y') ORDER BY A.ID DESC, A.NM", lines(
    'SELECT A.ID',
    'FROM T_A A,',
    '  T_B B',
    'WHERE A.ID = B.ID',
    '  AND A.DT BETWEEN ? AND ?',
    '  OR A.CD IN (',
    '    SELECT CD',
    '    FROM T_C',
    "    WHERE USE_YN = 'Y'",
    '  )',
    'ORDER BY A.ID DESC, A.NM'));
});

test('F-016-AC88 INSERT·UPDATE·DELETE: 열 목록·VALUES 괄호 안은 한 줄이고, INSERT … SELECT의 SELECT와 UPDATE의 SET은 새 줄이다', () => {
  check('INSERT INTO ORDERS (ORDER_ID, CUSTOMER_ID, STATUS) VALUES (SEQ_ORDER.NEXTVAL, ?, ?)', lines(
    'INSERT INTO ORDERS (ORDER_ID, CUSTOMER_ID, STATUS)',
    'VALUES (SEQ_ORDER.NEXTVAL, ?, ?)'));
  check('INSERT INTO ORDER_ITEMS (ITEM_ID, ORDER_ID, PRODUCT_ID, QTY) SELECT ?, ?, P.PRODUCT_ID, ? FROM PRODUCTS P WHERE P.PRODUCT_ID = ?', lines(
    'INSERT INTO ORDER_ITEMS (ITEM_ID, ORDER_ID, PRODUCT_ID, QTY)',
    'SELECT ?,',
    '  ?,',
    '  P.PRODUCT_ID,',
    '  ?',
    'FROM PRODUCTS P',
    'WHERE P.PRODUCT_ID = ?'));
  check("UPDATE ORDERS SET STATUS = 'CLOSED', UPD_DT = SYSDATE WHERE ORDER_ID = P_ORDER_ID", lines(
    'UPDATE ORDERS',
    "SET STATUS = 'CLOSED',",
    '  UPD_DT = SYSDATE',
    'WHERE ORDER_ID = P_ORDER_ID'));
  check('DELETE FROM ORDER_ITEMS WHERE ORDER_ID = ?', lines('DELETE FROM ORDER_ITEMS', 'WHERE ORDER_ID = ?'));
  check('{ CALL PKG_ORDER.CLOSE_ORDER(?) }', '{ CALL PKG_ORDER.CLOSE_ORDER(?) }');
});

test('F-016-AC88 MERGE: USING 하위 쿼리를 들여 쓰고 WHEN [NOT] MATCHED마다 한 줄이다', () => {
  check('MERGE INTO SETTLEMENTS S USING (SELECT CUSTOMER_ID, COUNT(*) CNT FROM ORDERS GROUP BY CUSTOMER_ID) O ON (S.CUSTOMER_ID = O.CUSTOMER_ID) WHEN MATCHED THEN UPDATE SET S.CNT = O.CNT WHEN NOT MATCHED THEN INSERT (CUSTOMER_ID, CNT) VALUES (O.CUSTOMER_ID, O.CNT)', lines(
    'MERGE INTO SETTLEMENTS S',
    'USING (',
    '  SELECT CUSTOMER_ID,',
    '    COUNT(*) CNT',
    '  FROM ORDERS',
    '  GROUP BY CUSTOMER_ID',
    ') O ON (S.CUSTOMER_ID = O.CUSTOMER_ID)',
    'WHEN MATCHED THEN UPDATE SET S.CNT = O.CNT',
    'WHEN NOT MATCHED THEN INSERT (CUSTOMER_ID, CNT) VALUES (O.CUSTOMER_ID, O.CNT)'));
});

test('F-016-AC88 UNION ALL, LEFT OUTER JOIN의 ON … AND, GROUP BY·HAVING, WITH의 여러 정의, 세미콜론으로 나뉜 구문', () => {
  check("SELECT A.ID FROM T_A A LEFT OUTER JOIN T_B B ON B.ID = A.ID AND B.USE_YN = 'Y' UNION ALL SELECT C.ID FROM T_C C GROUP BY C.ID HAVING COUNT(*) > 1", lines(
    'SELECT A.ID',
    'FROM T_A A',
    'LEFT OUTER JOIN T_B B ON B.ID = A.ID',
    "  AND B.USE_YN = 'Y'",
    'UNION ALL',
    'SELECT C.ID',
    'FROM T_C C',
    'GROUP BY C.ID',
    'HAVING COUNT(*) > 1'));
  check('WITH X AS (SELECT ID FROM T_A), Y AS (SELECT ID FROM T_B) SELECT X.ID FROM X JOIN Y ON Y.ID = X.ID', lines(
    'WITH X AS (',
    '  SELECT ID',
    '  FROM T_A',
    '),',
    'Y AS (',
    '  SELECT ID',
    '  FROM T_B',
    ')',
    'SELECT X.ID',
    'FROM X',
    'JOIN Y ON Y.ID = X.ID'));
  // PostgreSQL의 변경 구문을 담은 WITH(sample-pg의 markPaidOrders)
  check("WITH paid AS ( UPDATE orders o SET status = 'PAID' FROM payments p WHERE p.order_id = o.id RETURNING o.id ) SELECT count(*) FROM paid", lines(
    'WITH paid AS (',
    '  UPDATE orders o',
    "  SET status = 'PAID'",
    '  FROM payments p',
    '  WHERE p.order_id = o.id',
    '  RETURNING o.id',
    ')',
    'SELECT count(*)',
    'FROM paid'));
  check('SELECT 1 FROM DUAL; SELECT 2 FROM DUAL', lines('SELECT 1', 'FROM DUAL;', 'SELECT 2', 'FROM DUAL'));
});

test('F-016-AC88 함수 인자, IN 목록, OVER(…), CASE … END 안은 한 줄로 둔다', () => {
  check("SELECT CASE WHEN A.X = 1 AND A.Y = 2 THEN 'Y' ELSE 'N' END FLAG, ROW_NUMBER() OVER (PARTITION BY A.K ORDER BY A.D) RN, NVL(A.NM, '-') NM FROM T A WHERE A.CD IN ('A', 'B')", lines(
    "SELECT CASE WHEN A.X = 1 AND A.Y = 2 THEN 'Y' ELSE 'N' END FLAG,",
    '  ROW_NUMBER() OVER (PARTITION BY A.K ORDER BY A.D) RN,',
    "  NVL(A.NM, '-') NM",
    'FROM T A',
    "WHERE A.CD IN ('A', 'B')"));
});

test('F-016-AC88 글자는 바꾸지 않는다: 문자열·주석 안의 공백과 키워드, MyBatis 자리표시, 달러 인용, 소문자', () => {
  check("SELECT 'a  FROM  b' AS S, /* keep  FROM */ X FROM T WHERE C = #{ code } AND D = ${col} AND E LIKE '%' || ? || '%' AND F = 'it''s  ok'", lines(
    "SELECT 'a  FROM  b' AS S,",
    '  /* keep  FROM */ X',
    'FROM T',
    'WHERE C = #{ code }',
    '  AND D = ${col}',
    "  AND E LIKE '%' || ? || '%'",
    "  AND F = 'it''s  ok'"));
  check('select a.id, b.nm from t_a a inner join t_b b on b.id = a.id where a.x = $1 and a.y = $$x  where y$$ and a.z::text = "Select  Col"', lines(
    'select a.id,',
    '  b.nm',
    'from t_a a',
    'inner join t_b b on b.id = a.id',
    'where a.x = $1',
    '  and a.y = $$x  where y$$',
    '  and a.z::text = "Select  Col"'));
  check('SELECT {*} FROM T WHERE ID = :id', lines('SELECT {*}', 'FROM T', 'WHERE ID = :id'));
});

test('F-016-AC88 원문의 줄 주석은 줄 끝까지이고 뒤는 다음 줄로 넘어간다. 앞에 쉼표를 두는 습관은 그대로 살린다. 여러 줄 블록 주석은 그대로다', () => {
  check('SELECT A -- 첫째\n     , B -- 둘째\n     , C\n  FROM T -- 끝', lines(
    'SELECT A -- 첫째',
    '  , B -- 둘째',
    '  , C',
    'FROM T -- 끝'));
  check('SELECT /* 여러\n   줄 */ A FROM T', lines('SELECT /* 여러\n   줄 */ A', 'FROM T'));
  // 분석기가 공백을 줄인 뒤라 주석이 뒤를 삼킨 값: 정리하지 않고 그대로 둔다
  check('SELECT A -- 주석 FROM T WHERE B = 1', 'SELECT A -- 주석 FROM T WHERE B = 1');
});

test('F-016-AC88 잘린 구문, 짝이 맞지 않는 괄호·따옴표, 조각, 빈 값에서도 멈추지 않고 글자를 모두 돌려준다', () => {
  const inputs = [
    "SELECT A FROM T WHERE B = 'abc",
    'SELECT A FROM (SELECT B FROM T WHERE C IN (1, 2',
    ') ) SELECT ) FROM T WHERE (',
    'SELECT A FROM T WHERE B = 1 AND C = 2 …',
    'SELECT /* 닫히지 않은 주석 FROM T',
    'SELECT CASE WHEN A = 1 THEN (SELECT X FROM Y',
    'END END ) CASE',
    'ID, NAME, STATUS',
    'AND A = ? AND B = ?',
    '#{unclosed',
    '$tag$ 닫히지 않은 달러 인용',
  ];
  inputs.forEach((sql) => {
    const got = fmt(sql);
    assert.equal(squeeze(got), squeeze(sql), sql);
  });
  assert.equal(fmt('ID, NAME, STATUS'), 'ID, NAME, STATUS', '절이 없는 조각은 그대로다');
  assert.equal(fmt('AND A = ? AND B = ?'), 'AND A = ? AND B = ?');
  assert.equal(fmt(''), '');
  assert.equal(fmt(null), '');
  assert.equal(fmt(undefined), '');
});

test('F-016-AC89 SQL 구문 상세의 sql 값은 정리본이고 "원문" 단추로 그래프에 적힌 그대로와 오간다', () => {
  const window = boot(assemble(JSON.stringify(sampleGraph()), ''));
  const doc = window.document;
  const raw = sampleGraph().nodes.find((n) => n.id === ids.S.settle).attrs.sql;
  window.CodeAtlas.app.showDetail(ids.S.settle);
  const scope = '.view-explore .detail';
  assert.equal(doc.querySelector(scope + ' .detail-head .id').textContent, ids.S.settle);

  const dd = doc.querySelector(scope + ' dd.sql');
  assert.ok(dd, 'sql 값 칸이 있다');
  const text = dd.querySelector('.sql-text');
  const toggle = dd.querySelector('.sql-toggle');
  assert.ok(text && toggle, '정리본 글자와 단추가 있다');
  assert.equal(text.textContent, fmt(raw));
  assert.ok(text.textContent.includes('\n'), '여러 줄이다');
  assert.equal(toggle.textContent, '원문');

  toggle.click();
  assert.equal(text.textContent, raw);
  assert.equal(toggle.textContent, '정리');
  toggle.click();
  assert.equal(text.textContent, fmt(raw));
  assert.equal(toggle.textContent, '원문');

  // sql이 아닌 속성은 전과 같다
  const dts = doc.querySelectorAll(scope + ' .kv dt');
  const stmtType = dts.find((dt) => dt.textContent === 'statementType');
  assert.ok(stmtType);
  const dds = doc.querySelectorAll(scope + ' .kv dd');
  assert.equal(dds[dts.indexOf(stmtType)].textContent, 'MERGE');
  assert.equal(dds[dts.indexOf(stmtType)].querySelector('.sql-toggle'), null);
});
