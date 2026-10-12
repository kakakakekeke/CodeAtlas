// 뷰어 내장 형식 풀기: viewer.html에 줄여서 넣은 그래프를 graph.json 모양으로 되돌린다. DOM을 쓰지 않는다.
//
// 줄인 형식(ViewerExporter의 CompactGraphJson이 쓴다. graph.json의 계약과는 별개로 이 파일 안에서만 쓴다):
//   { compact: 1, schemaVersion, project, nodes, edges, diagnostics, strings }
//   strings      반복되는 글자(종류, 신뢰도, 심각도, 진단 코드, 파일 경로)를 한 번씩 적은 표. 아래의 "번호"는 이 표의 자리다.
//   nodes[i]     [id, 종류 번호, name, qualifiedName, 위치, attrs]
//   edges[i]     [from, to, 종류 번호, 신뢰도 번호, 위치, attrs]   from·to는 nodes의 자리(없는 노드면 ID 글자 그대로)
//   diagnostics  [심각도 번호, 코드 번호, message, 위치]
//   위치         [파일 번호, startLine, startColumn, endLine, endColumn]. 없으면 0
// 줄 끝의 칸은 기본값이면 적지 않는다: attrs {}, 위치 없음, 신뢰도 RESOLVED.
// 위치의 끝 칸도 같다: endColumn = startColumn, endLine = startLine, startColumn = 0, startLine = 0.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var VERSION = 1;

  function expand(data) {
    if (!data || data.compact !== VERSION || !Array.isArray(data.strings)) {
      throw new Error('뷰어가 모르는 내장 형식이다(compact: ' + (data && data.compact !== undefined ? data.compact : '없음') + ')');
    }
    var strings = data.strings;

    function location(row) {
      if (!row) return null;
      var startLine = row.length > 1 ? row[1] : 0;
      var startColumn = row.length > 2 ? row[2] : 0;
      return {
        file: strings[row[0]], startLine: startLine, startColumn: startColumn,
        endLine: row.length > 3 ? row[3] : startLine, endColumn: row.length > 4 ? row[4] : startColumn
      };
    }

    var nodes = new Array(data.nodes.length);
    for (var i = 0; i < nodes.length; i++) {
      var n = data.nodes[i];
      nodes[i] = { id: n[0], kind: strings[n[1]], name: n[2], qualifiedName: n[3], location: location(n[4]), attrs: n[5] || {} };
    }
    // 엣지의 양 끝은 노드의 id와 같은 문자열을 함께 쓴다.
    function end(ref) { return typeof ref === 'number' ? nodes[ref].id : ref; }
    var edges = new Array(data.edges.length);
    for (var j = 0; j < edges.length; j++) {
      var e = data.edges[j];
      edges[j] = {
        from: end(e[0]), to: end(e[1]), kind: strings[e[2]], confidence: e.length > 3 ? strings[e[3]] : 'RESOLVED',
        location: location(e[4]), attrs: e[5] || {}
      };
    }
    var diagnostics = new Array(data.diagnostics.length);
    for (var k = 0; k < diagnostics.length; k++) {
      var d = data.diagnostics[k];
      diagnostics[k] = { severity: strings[d[0]], code: strings[d[1]], message: d[2], location: location(d[3]) };
    }
    return { schemaVersion: data.schemaVersion, project: data.project, nodes: nodes, edges: edges, diagnostics: diagnostics };
  }

  var api = { VERSION: VERSION, expand: expand };
  CA.compact = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
