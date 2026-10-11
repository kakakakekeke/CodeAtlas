'use strict';
// 실제 분석 결과(samples/sample-shop)에서 본 노드·엣지 모양을 그대로 옮겨 작은 그래프를 만드는 도우미.
// 손으로 만든 sample-graph.js와 달리, 분석기가 실제로 내는 조합(계약 design 3.3~3.5 갱신분)을 재현하는 데 쓴다.

function slice() {
  const nodes = [];
  const edges = [];
  const diagnostics = [];
  return {
    /** 노드를 더하고 ID를 돌려준다. attrs.declared는 주지 않으면 true. */
    n(id, kind, name, qualifiedName, attrs, location) {
      nodes.push({ id, kind, name, qualifiedName: qualifiedName || name, location: location || null,
        attrs: Object.assign({ declared: true }, attrs || {}) });
      return id;
    },
    e(from, kind, to, attrs, confidence, location) {
      edges.push({ from, to, kind, confidence: confidence || 'RESOLVED', location: location || null, attrs: attrs || {} });
    },
    d(severity, code, message, location) {
      diagnostics.push({ severity, code, message, location: location || null });
    },
    graph() {
      return { schemaVersion: 1, project: { name: 'slice', root: '/slice', analyzedAt: '2026-10-10T17:34:53Z' }, nodes, edges, diagnostics };
    }
  };
}

function loc(file, line, endLine) {
  return { file, startLine: line, startColumn: 1, endLine: endLine || line, endColumn: 1 };
}

/** 루트에서 잎까지의 모든 경로를 노드 ID 배열로 돌려준다. */
function paths(node, prefix, out) {
  const here = (prefix || []).concat(node.id);
  const acc = out || [];
  if (!node.children || node.children.length === 0) acc.push(here);
  else node.children.forEach((c) => paths(c, here, acc));
  return acc;
}

/** want의 ID들이 path 안에 그 순서대로(사이에 다른 노드가 있어도 됨) 나오는가. */
function containsInOrder(path, want) {
  let i = 0;
  for (const id of path) if (id === want[i]) i++;
  return i === want.length;
}

module.exports = { slice, loc, paths, containsInOrder };
