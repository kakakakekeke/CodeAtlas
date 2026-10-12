'use strict';
// graph.json 모양의 그래프를 뷰어 내장 형식(F-016-R49)으로 줄인다. Java의 CompactGraphJson과 같은 규칙이다
// (compact.test.js가 ViewerExporter가 만든 블록과 글자까지 같은지 본다). 노드·엣지·진단의 순서는 받은 그대로 둔다.

function compactGraph(graph) {
  const strings = [];
  const seen = new Map();
  const ref = (text) => {
    let at = seen.get(text);
    if (at === undefined) {
      at = strings.length;
      strings.push(text);
      seen.set(text, at);
    }
    return at;
  };
  // 위치: [파일, startLine, startColumn, endLine, endColumn]. 뒤에서부터 기본값과 같은 칸을 뗀다.
  const location = (l) => {
    const row = [ref(l.file), l.startLine, l.startColumn, l.endLine, l.endColumn];
    if (row[4] !== row[2]) return row;
    row.pop();
    if (row[3] !== row[1]) return row;
    row.pop();
    if (row[2] !== 0) return row;
    row.pop();
    if (row[1] !== 0) return row;
    row.pop();
    return row;
  };
  const hasAttrs = (attrs) => !!attrs && Object.keys(attrs).length > 0;
  const text = (v) => (v === undefined ? null : v);

  const nodeAt = new Map();
  const nodes = (graph.nodes || []).map((n, i) => {
    nodeAt.set(n.id, i);
    const row = [n.id, ref(n.kind), text(n.name), text(n.qualifiedName)];
    if (n.location || hasAttrs(n.attrs)) row.push(n.location ? location(n.location) : 0);
    if (hasAttrs(n.attrs)) row.push(n.attrs);
    return row;
  });
  const end = (id) => (nodeAt.has(id) ? nodeAt.get(id) : id);
  const edges = (graph.edges || []).map((e) => {
    const confidence = e.confidence || 'RESOLVED';
    const row = [end(e.from), end(e.to), ref(e.kind)];
    if (confidence !== 'RESOLVED' || e.location || hasAttrs(e.attrs)) row.push(ref(confidence));
    if (e.location || hasAttrs(e.attrs)) row.push(e.location ? location(e.location) : 0);
    if (hasAttrs(e.attrs)) row.push(e.attrs);
    return row;
  });
  const diagnostics = (graph.diagnostics || []).map((d) => {
    const row = [ref(d.severity), ref(d.code), text(d.message)];
    if (d.location) row.push(location(d.location));
    return row;
  });
  return { compact: 1, schemaVersion: graph.schemaVersion, project: text(graph.project), nodes, edges, diagnostics, strings };
}

module.exports = { compactGraph };
