// 노드 검색. DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  if (typeof module === 'object' && module.exports) require('./graph.js');
  var CA = root.CodeAtlas = root.CodeAtlas || {};
  var compareText = CA.graph.compareText;
  var kindRank = CA.graph.kindRank;

  function entries(index) {
    if (!index._searchEntries) {
      index._searchEntries = index.nodes.map(function (n) {
        var name = String(n.name || '').toLowerCase();
        var qn = String(n.qualifiedName || '').toLowerCase();
        var hay = name === qn ? name : name + '\n' + qn;
        // 주석(테이블·컬럼의 한글 이름)과 요청의 URL 원문도 찾을 수 있게 한다. 순위는 이름이 맞는 것보다 뒤다.
        var a = n.attrs || {};
        if (a.comment) hay += '\n' + String(a.comment).toLowerCase();
        if (n.kind === 'HTTP_REQUEST' && a.url) hay += '\n' + String(a.url).toLowerCase();
        // 프로퍼티 자리표시자를 치환한 경로(`/a/sys/user/list`)로도 엔드포인트를 찾는다. 표시 이름도 그 경로다.
        if (n.kind === 'HTTP_ENDPOINT' && a.resolvedPath) hay += '\n' + String(a.httpMethod || '').toLowerCase() + ' ' + String(a.resolvedPath).toLowerCase();
        return { node: n, name: name, hay: hay };
      });
    }
    return index._searchEntries;
  }

  /**
   * query.text: 공백으로 나눈 낱말이 이름, 전체 이름, 주석(attrs.comment), 요청의 URL 원문, 엔드포인트의 치환한 경로(resolvedPath) 중 어딘가에 모두 들어 있어야 한다(대소문자 무시).
   * query.kinds: 노드 종류 배열. 없거나 비면 모든 종류.
   * query.hideUndeclared: 정의 없이 참조만 된 노드(declared=false, 외부 라이브러리 포함)를 뺀다.
   * query.hideSynthetic: 소스에 없는 합성 멤버(synthetic=true, Lombok 포함)를 뺀다.
   * query.hideClientRoutes: 클라이언트 라우트(clientRoute=true인 요청)를 뺀다.
   * query.hideSkipped: 내용을 분석하지 않은 모듈·페이지(skipped=vendor|minified: 벤더 스크립트, 벤더 폴더의 페이지, 압축 파일)를 뺀다.
   * 결과 순서: 이름이 검색어와 같은 것, 이름이 검색어로 시작하는 것, 이름에 든 것, 전체 이름에만 든 것. 같은 순위에서는 이름 순.
   * 검색어가 없으면 종류 순(계약의 순서), 그 안에서 이름 순.
   */
  function search(index, query) {
    var q = query || {};
    var text = String(q.text || '').trim().toLowerCase();
    var tokens = text ? text.split(/\s+/) : [];
    var kinds = q.kinds && q.kinds.length ? new Set(q.kinds) : null;
    var all = entries(index);
    var hits = [];
    var i, j, en, ok, rank;
    for (i = 0; i < all.length; i++) {
      en = all[i];
      if (kinds && !kinds.has(en.node.kind)) continue;
      if (q.hideUndeclared && en.node.attrs.declared === false) continue;
      if (q.hideSynthetic && en.node.attrs.synthetic === true) continue;
      if (q.hideSkipped && en.node.attrs.skipped) continue;
      // 클라이언트 라우트(clientRoute=true: 화면 안의 이동)는 서버 요청이 아니다. React 프로젝트에서는 수백 개라 기본으로 숨긴다.
      if (q.hideClientRoutes && en.node.kind === 'HTTP_REQUEST' && en.node.attrs.clientRoute === true) continue;
      rank = 0;
      if (tokens.length) {
        ok = true;
        for (j = 0; j < tokens.length; j++) {
          if (en.hay.indexOf(tokens[j]) < 0) { ok = false; break; }
        }
        if (!ok) continue;
        rank = en.name === text ? 0 : en.name.indexOf(tokens[0]) === 0 ? 1 : en.name.indexOf(tokens[0]) > 0 ? 2 : 3;
      }
      hits.push({ rank: rank, en: en });
    }
    var byKind = !tokens.length;
    hits.sort(function (a, b) {
      return a.rank - b.rank
        || (byKind ? kindRank(a.en.node.kind) - kindRank(b.en.node.kind) : 0)
        || compareText(a.en.node.name, b.en.node.name) || compareText(a.en.node.id, b.en.node.id);
    });
    var out = new Array(hits.length);
    for (i = 0; i < hits.length; i++) out[i] = hits[i].en.node;
    return out;
  }

  /** 내용을 분석하지 않은 모듈·페이지(attrs.skipped가 있는 노드)의 수. */
  function countSkipped(index) {
    if (index._skippedCount == null) {
      var count = 0;
      ['JS_MODULE', 'JSP_PAGE'].forEach(function (kind) {
        index.nodesOfKind(kind).forEach(function (n) { if (n.attrs.skipped) count++; });
      });
      index._skippedCount = count;
    }
    return index._skippedCount;
  }

  /** 클라이언트 라우트(clientRoute=true인 HTTP_REQUEST)의 수. */
  function countClientRoutes(index) {
    if (index._clientRouteCount == null) {
      index._clientRouteCount = index.nodesOfKind('HTTP_REQUEST').filter(function (n) { return n.attrs.clientRoute === true; }).length;
    }
    return index._clientRouteCount;
  }

  var api = { search: search, countSkipped: countSkipped, countClientRoutes: countClientRoutes };
  CA.search = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
