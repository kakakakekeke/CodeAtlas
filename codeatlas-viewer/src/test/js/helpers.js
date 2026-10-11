'use strict';
const path = require('node:path');

const RESOURCE_ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'io', 'codeatlas', 'viewer');

/** DOM과 분리된 순수 모듈(js/core/<name>.js)을 불러온다. */
function core(name) {
  return require(path.join(RESOURCE_ROOT, 'js', 'core', name + '.js'));
}

/** 노드 수가 많은 합성 그래프. 큰 그래프에서 색인·검색·범위 선택이 버티는지 볼 때 쓴다. */
function bigGraph(count) {
  const nodes = [];
  const edges = [];
  for (let i = 0; i < count; i++) {
    const kind = i % 5 === 0 ? 'DB_TABLE' : i % 5 === 1 ? 'JAVA_TYPE' : 'JAVA_METHOD';
    const id = kind === 'DB_TABLE' ? 'db:table:T' + i
      : kind === 'JAVA_TYPE' ? 'java:type:com.big.p' + (i % 7) + '.Type' + i
        : 'java:method:com.big.Type' + (i - (i % 5) + 1) + '#m' + i + '()';
    nodes.push({ id, kind, name: kind === 'DB_TABLE' ? 'T' + i : kind === 'JAVA_TYPE' ? 'Type' + i : 'm' + i,
      qualifiedName: id.substring(id.indexOf(':', id.indexOf(':') + 1) + 1), location: null, attrs: { declared: true } });
    if (i > 0) {
      const prev = nodes[i - 1];
      const k = kind === 'DB_TABLE' && prev.kind === 'DB_TABLE' ? 'REFERENCES' : 'DEPENDS_ON';
      edges.push({ from: id, to: prev.id, kind: k, confidence: 'RESOLVED', location: null, attrs: {} });
    }
  }
  // 테이블끼리 한 줄로 잇는다 (ERD 상한 시험용)
  const tables = nodes.filter((n) => n.kind === 'DB_TABLE');
  for (let i = 1; i < tables.length; i++) {
    edges.push({ from: tables[i].id, to: tables[i - 1].id, kind: 'REFERENCES', confidence: 'RESOLVED', location: null,
      attrs: { columns: ['P_ID'], refColumns: ['ID'], source: 'DDL' } });
  }
  return { schemaVersion: 1, project: { name: 'big', root: '/big', analyzedAt: '2026-10-11T00:00:00Z' }, nodes, edges, diagnostics: [] };
}

module.exports = { RESOURCE_ROOT, core, bigGraph };
