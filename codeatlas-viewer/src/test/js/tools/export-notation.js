'use strict';
// 표준 산출물의 콜트리 표기(codeatlas-export의 NodeLabels: `[종류] 이름  file:line`)를 테스트에서 다시 만든다.
// 텍스트 트리에는 노드 ID가 없어서, 뷰어의 트리 노드가 산출물의 어느 줄과 같은 노드인지 이 표기로 맞춘다. 테스트 전용이다.
const { core } = require('../helpers.js');

const graph = core('graph');

const KIND_TAGS = {
  JAVA_METHOD: '', JAVA_TYPE: 'type', JAVA_FIELD: 'field', JAVA_PACKAGE: 'package', SPRING_BEAN: 'bean', HTTP_ENDPOINT: 'endpoint',
  HTTP_REQUEST: 'request', BATCH_JOB: 'job', BATCH_STEP: 'step', MYBATIS_MAPPER: 'mapper', SQL_STATEMENT: 'sql', SQL_FRAGMENT: 'fragment',
  DB_TABLE: 'table', DB_VIEW: 'view', DB_COLUMN: 'column', DB_PACKAGE: 'db-package', DB_SEQUENCE: 'sequence', DB_SYNONYM: 'synonym',
  JS_MODULE: 'module', JS_FUNCTION: 'js', JSP_PAGE: 'jsp', FILE: 'file', UNRESOLVED: 'unresolved'
};

function oneLine(s) {
  return s == null ? '' : String(s).replace(/[\r\n\t]+/g, ' ').trim();
}

function kindOf(ix, id) {
  const node = ix.node(id);
  return node ? node.kind : graph.kindOfId(id) || 'UNRESOLVED';
}

function simpleName(binaryName) {
  return graph.simpleTypeName(binaryName);
}

function statementName(node, id) {
  const rest = graph.idRemainder(id);
  if (!id.startsWith('mybatis:stmt:')) return rest;
  let namespace = node && node.attrs.namespace;
  let statementId = node && node.attrs.statementId;
  if (!namespace || !statementId) {
    const dot = rest.lastIndexOf('.');
    if (dot < 0) return rest;
    namespace = rest.substring(0, dot);
    statementId = rest.substring(dot + 1);
  }
  return namespace.substring(namespace.lastIndexOf('.') + 1) + '.' + statementId;
}

function labelById(id) {
  const kind = graph.kindOfId(id) || 'UNRESOLVED';
  const rest = graph.idRemainder(id);
  return kind === 'JAVA_METHOD' ? graph.methodLabel(rest, false) : kind === 'JAVA_TYPE' ? simpleName(rest) : rest;
}

/** NodeLabels.label: 짧은 표시 이름. */
function label(ix, id) {
  const node = ix.node(id);
  const kind = kindOf(ix, id);
  const rest = graph.idRemainder(id);
  let hash;
  switch (kind) {
    case 'JAVA_METHOD': return graph.methodLabel(rest, false);
    case 'JAVA_FIELD':
      hash = rest.indexOf('#');
      return hash < 0 ? rest : simpleName(rest.substring(0, hash)) + '#' + rest.substring(hash + 1);
    case 'JAVA_TYPE': return simpleName(rest);
    case 'SQL_STATEMENT': {
      const type = node && node.attrs.statementType;
      return statementName(node, id) + (type ? ' <' + type + '>' : '');
    }
    case 'HTTP_ENDPOINT': return graph.endpointLabel(node, id);
    case 'HTTP_REQUEST': {
      if (node) {
        // 메서드가 여럿인 요청(httpMethod=ANY, httpMethods)은 POST|PUT, 자기 페이지로 내는 폼(selfSubmit)은 말로 적는다(NodeLabels와 같다).
        const several = Array.isArray(node.attrs.httpMethods) ? node.attrs.httpMethods : [];
        const method = (!node.attrs.httpMethod || node.attrs.httpMethod === 'ANY') && several.length ? several.join('|') : node.attrs.httpMethod;
        if (node.attrs.selfSubmit === true) return (method ? method + ' ' : '') + '(자기 페이지로 제출)';
        const path = node.attrs.path ? node.attrs.path : node.attrs.url;
        // 클라이언트 라우트(화면 안의 이동)는 경로만 적는다.
        if (path) return (method && node.attrs.clientRoute !== true ? method + ' ' : '') + oneLine(path);
      }
      const at = rest.lastIndexOf('@');
      return at > 0 ? rest.substring(0, at) : rest;
    }
    case 'JS_FUNCTION': {
      hash = rest.indexOf('#');
      if (hash < 0) return rest;
      const path = rest.substring(0, hash);
      return path.substring(path.lastIndexOf('/') + 1) + rest.substring(hash);
    }
    case 'BATCH_STEP': {
      const slash = rest.indexOf('/');
      return slash < 0 ? rest : rest.substring(slash + 1);
    }
    case 'SQL_FRAGMENT': {
      const dot = rest.lastIndexOf('.');
      if (dot <= 0) return rest;
      const namespace = rest.substring(0, dot);
      return namespace.substring(namespace.lastIndexOf('.') + 1) + rest.substring(dot);
    }
    case 'UNRESOLVED': {
      const byId = graph.kindOfId(id);
      if (byId && byId !== 'UNRESOLVED') return labelById(id);
      // 맞는 엔드포인트를 찾지 못한 action 없는 폼(NodeLabels.label의 요청 문맥 없는 이름)
      const self = /^unresolved:http-endpoint:(\S+) \(selfSubmit\)$/.exec(id);
      if (self) return self[1] + ' (자기 페이지로 제출: 이 화면을 그리는 엔드포인트를 모르거나 그 경로에 ' + self[1] + ' 매핑 없음)';
      if (node && node.name && String(node.name).trim() && node.name !== rest) return oneLine(node.name);
      const colon = rest.indexOf(':');
      return oneLine(id.startsWith('unresolved:') && colon >= 0 ? rest.substring(colon + 1) : rest);
    }
    default: return rest;
  }
}

function kindTag(ix, id) {
  const node = ix.node(id);
  const kind = kindOf(ix, id);
  if (kind === 'DB_ROUTINE') return node && node.attrs.routineType ? String(node.attrs.routineType).toLowerCase() : 'routine';
  if (kind === 'HTTP_REQUEST' && node && node.attrs.clientRoute === true) return 'route';
  return KIND_TAGS[kind] == null ? '' : KIND_TAGS[kind];
}

function tagged(ix, id) {
  const tag = kindTag(ix, id);
  return tag ? '[' + tag + '] ' + label(ix, id) : label(ix, id);
}

function location(loc) {
  if (!loc || !loc.file) return null;
  return loc.startLine > 0 ? loc.file + ':' + loc.startLine : loc.file;
}

/** 뷰어의 노드(id)가 산출물의 줄(want: export-files.js의 parseLine 결과)과 같은 노드인가. */
function matches(ix, id, want) {
  if (kindTag(ix, id) !== want.tag) return false;
  const name = label(ix, id);
  if (want.head !== name && !want.head.startsWith(name + ' (')) return false;
  const node = ix.node(id);
  const defined = !!node && node.attrs.declared !== false && node.kind !== 'UNRESOLVED';
  const own = node ? location(node.location) : null;
  // 정의가 있는 노드의 줄에는 정의 위치가 적힌다(이름이 곧 경로인 줄은 생략). 정의가 없는 노드는 호출 지점이라 견주지 않는다.
  if (defined && own) return want.location === own || (want.location == null && own === name);
  return true;
}

/** 산출물 줄의 세부 표시(이름 뒤 괄호). 없으면 ''. */
function detailFromHead(ix, id, want) {
  const rest = want.head.substring(label(ix, id).length);
  const m = /^ \((.*)\)$/.exec(rest);
  return m ? m[1] : '';
}

const WRITE_LETTERS = { INSERT: 'C', UPDATE: 'U', DELETE: 'D', MERGE: 'CU' };

/**
 * 같은 노드로 가는 뷰어의 줄들(items: 트리 노드)이 뜻하는 세부 표시.
 * 테이블·뷰는 접근 글자(R은 테이블 수준 READS가 있을 때만), 호출은 redirect/forward/super, 스텝의 구성 요소는 역할.
 */
function detailOf(ix, items) {
  const first = items[0];
  const kind = kindOf(ix, first.id);
  if (kind === 'DB_TABLE' || kind === 'DB_VIEW') {
    const letters = new Set();
    items.forEach((k) => k.link.edges.forEach((e) => {
      const target = ix.node(e.to);
      const column = target && target.kind === 'DB_COLUMN';
      if (e.kind === 'READS') { if (!column) letters.add('R'); return; }
      if (e.kind !== 'WRITES') return;
      const from = ix.node(e.from);
      const op = String(e.attrs.op || (from && from.attrs.statementType) || '').toUpperCase();
      (WRITE_LETTERS[op] || 'W').split('').forEach((c) => letters.add(c));
    }));
    return 'CRUDW'.split('').filter((c) => letters.has(c)).join('');
  }
  const details = [];
  items.forEach((k) => {
    const m = /^(CALLS|USES)\((.*)\)$/.exec(k.link.label);
    if (!m) return;
    if (m[1] === 'CALLS' && m[2] !== 'super') return;
    details.push(m[2]);
  });
  details.sort();
  return details.length ? details[0] : '';
}

module.exports = { label, kindTag, tagged, kindOf, matches, detailFromHead, detailOf };
