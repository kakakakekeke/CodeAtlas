// 그래프 색인과 노드 상세. DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};

  // 노드 종류의 표시 이름(label), 목록에 쓰는 짧은 이름(short), 색 묶음(group). 순서는 그래프 계약(design 3.3)의 순서다.
  var KIND_META = {
    FILE: { label: '파일', short: '파일', group: 'file' },
    JAVA_PACKAGE: { label: 'Java 패키지', short: '패키지', group: 'java' },
    JAVA_TYPE: { label: 'Java 타입', short: '타입', group: 'java' },
    JAVA_METHOD: { label: 'Java 메서드', short: '메서드', group: 'java' },
    JAVA_FIELD: { label: 'Java 필드', short: '필드', group: 'java' },
    SPRING_BEAN: { label: 'Spring 빈', short: '빈', group: 'spring' },
    HTTP_ENDPOINT: { label: 'HTTP 엔드포인트', short: '엔드포인트', group: 'spring' },
    HTTP_REQUEST: { label: 'HTTP 요청', short: '요청', group: 'web' },
    BATCH_JOB: { label: '배치 잡', short: '잡', group: 'batch' },
    BATCH_STEP: { label: '배치 스텝', short: '스텝', group: 'batch' },
    MYBATIS_MAPPER: { label: 'MyBatis 매퍼', short: '매퍼', group: 'sql' },
    SQL_STATEMENT: { label: 'SQL 구문', short: 'SQL', group: 'sql' },
    SQL_FRAGMENT: { label: 'SQL 조각', short: 'SQL 조각', group: 'sql' },
    DB_TABLE: { label: '테이블', short: '테이블', group: 'db' },
    DB_VIEW: { label: '뷰', short: '뷰', group: 'db' },
    DB_COLUMN: { label: '컬럼', short: '컬럼', group: 'db' },
    DB_ROUTINE: { label: '루틴', short: '루틴', group: 'db' },
    DB_PACKAGE: { label: 'DB 패키지', short: 'DB 패키지', group: 'db' },
    DB_SEQUENCE: { label: '시퀀스', short: '시퀀스', group: 'db' },
    DB_SYNONYM: { label: '시노님', short: '시노님', group: 'db' },
    JS_MODULE: { label: 'JS 모듈', short: 'JS 모듈', group: 'web' },
    JS_FUNCTION: { label: 'JS 함수', short: 'JS 함수', group: 'web' },
    JSP_PAGE: { label: 'JSP', short: 'JSP', group: 'web' },
    UNRESOLVED: { label: '미해결', short: '미해결', group: 'unresolved' }
  };
  var KIND_ORDER = Object.keys(KIND_META);

  function kindMeta(kind) {
    return KIND_META[kind] || { label: kind, short: kind, group: 'other' };
  }

  function kindRank(kind) {
    var i = KIND_ORDER.indexOf(kind);
    return i < 0 ? KIND_ORDER.length : i;
  }

  function compareText(a, b) {
    return a < b ? -1 : a > b ? 1 : 0;
  }

  var EMPTY = Object.freeze([]);
  var COLUMN_PREFIX = 'db:column:';
  var SMALL_EDGE_LIST = 12;

  // 노드 ID의 접두사 → 종류(계약 design 3.3). 긴 접두사를 먼저 본다.
  var ID_PREFIXES = [
    ['java:package', 'JAVA_PACKAGE'], ['java:type', 'JAVA_TYPE'], ['java:method', 'JAVA_METHOD'], ['java:field', 'JAVA_FIELD'],
    ['spring:bean', 'SPRING_BEAN'], ['http:endpoint', 'HTTP_ENDPOINT'], ['http:request', 'HTTP_REQUEST'],
    ['batch:job', 'BATCH_JOB'], ['batch:step', 'BATCH_STEP'], ['mybatis:mapper', 'MYBATIS_MAPPER'], ['mybatis:stmt', 'SQL_STATEMENT'],
    ['sql:stmt', 'SQL_STATEMENT'], ['mybatis:fragment', 'SQL_FRAGMENT'], ['db:table', 'DB_TABLE'], ['db:view', 'DB_VIEW'],
    ['db:column', 'DB_COLUMN'], ['db:routine', 'DB_ROUTINE'], ['db:package', 'DB_PACKAGE'], ['db:sequence', 'DB_SEQUENCE'],
    ['db:synonym', 'DB_SYNONYM'], ['js:module', 'JS_MODULE'], ['js:function', 'JS_FUNCTION'], ['jsp:page', 'JSP_PAGE'],
    ['file', 'FILE'], ['unresolved', 'UNRESOLVED']
  ];

  function prefixEntry(id) {
    var text = String(id == null ? '' : id);
    for (var i = 0; i < ID_PREFIXES.length; i++) {
      if (text.indexOf(ID_PREFIXES[i][0] + ':') === 0) return ID_PREFIXES[i];
    }
    return null;
  }

  /** ID의 접두사로 노드 종류를 알아낸다. 모르는 접두사면 null. */
  function kindOfId(id) {
    var entry = prefixEntry(id);
    return entry ? entry[1] : null;
  }

  /** ID의 접두사(끝의 콜론 없음). 예: `mybatis:stmt:a.B.c` → `mybatis:stmt`. 모르는 접두사면 null. */
  function idPrefix(id) {
    var entry = prefixEntry(id);
    return entry ? entry[0] : null;
  }

  /** 접두사 뒤의 나머지. 예: `db:table:ORDERS` → `ORDERS`, `java:method:a.B#m()` → `a.B#m()`. */
  function idRemainder(id) {
    var text = String(id == null ? '' : id);
    var entry = prefixEntry(text);
    if (entry) return text.substring(entry[0].length + 1);
    var first = text.indexOf(':');
    if (first < 0) return text;
    var second = text.indexOf(':', first + 1);
    return text.substring((second < 0 ? first : second) + 1);
  }

  /** 바이너리 이름의 단순 이름. `com.a.Outer$Inner` → `Outer.Inner`. 첫 글자가 소문자인 앞 조각은 패키지로 본다. */
  function simpleTypeName(binaryName) {
    var name = String(binaryName == null ? '' : binaryName);
    if (!name) return name;
    var varargs = '';
    if (name.length >= 3 && name.substring(name.length - 3) === '...') { varargs = '...'; name = name.substring(0, name.length - 3); }
    var parts = name.split('.');
    if (parts.length > 1) {
      var firstType = parts.length - 1;
      for (var i = 0; i < parts.length; i++) {
        var c = parts[i].charAt(0);
        if (c && c !== c.toLowerCase() && c === c.toUpperCase()) { firstType = i; break; }
      }
      name = parts.slice(firstType).join('.');
    }
    return name.replace(/\$/g, '.') + varargs;
  }

  /** 타입 문자열에서 패키지 한정자를 뗀다. `java.util.List<com.a.Order$Item>` → `List<Order.Item>`. */
  function simpleTypeText(typeText) {
    return String(typeText == null ? '' : typeText).replace(/[A-Za-z0-9_$.\u00C0-\uFFFF]+/g, function (word) { return simpleTypeName(word); });
  }

  /**
   * 메서드 표시 이름(표준 산출물과 같은 표기). `com.a.B#save(java.lang.String,int[])` → `B#save(String, int[])`.
   * qualified면 타입을 전체 이름으로 둔다.
   */
  function methodLabel(methodRemainder, qualified) {
    var text = String(methodRemainder);
    var hash = text.indexOf('#');
    if (hash < 0) return text;
    var type = text.substring(0, hash);
    var member = text.substring(hash + 1);
    var open = member.indexOf('(');
    var out = (qualified ? type : simpleTypeName(type)) + '#' + (open < 0 ? member : member.substring(0, open));
    if (open < 0) return out;
    var close = member.lastIndexOf(')');
    var params = close > open ? member.substring(open + 1, close) : '';
    var simple = [];
    var depth = 0;
    var current = '';
    for (var i = 0; i < params.length; i++) {
      var c = params.charAt(i);
      if (c === '<') depth++;
      else if (c === '>') depth--;
      if (c === ',' && depth === 0) { simple.push(simpleTypeText(current.trim())); current = ''; }
      else current += c;
    }
    if (current.trim()) simple.push(simpleTypeText(current.trim()));
    return out + '(' + simple.join(', ') + ')';
  }

  /** `file:line`. 줄을 모르면 파일만, 위치가 없으면 null. 범위(끝줄)는 적지 않는다(표준 산출물의 표기). */
  function lineLocation(loc) {
    if (!loc || !loc.file) return null;
    return loc.startLine > 0 ? loc.file + ':' + loc.startLine : loc.file;
  }

  /**
   * 참조만 된 노드(declared=false)는 qualifiedName이 단순 이름으로 온다(외부 타입 `Resource`, 외부 메서드 `getContextPath`,
   * 정의 없는 테이블의 컬럼 `BROKEN_ID`). 목록과 트리에서 구분되게 ID의 나머지 부분으로 되돌린다.
   */
  function restoreQualifiedName(n) {
    var qn = n.qualifiedName;
    var prefix, mark;
    switch (n.kind) {
      case 'JAVA_TYPE': prefix = 'java:type:'; mark = '.'; break;
      case 'JAVA_METHOD': prefix = 'java:method:'; mark = '#'; break;
      case 'JAVA_FIELD': prefix = 'java:field:'; mark = '#'; break;
      case 'DB_COLUMN': prefix = COLUMN_PREFIX; mark = '.'; break;
      default: return qn;
    }
    if (String(qn).indexOf(mark) >= 0 || n.id.indexOf(prefix) !== 0) return qn;
    var rest = n.id.substring(prefix.length);
    return rest.indexOf(mark) >= 0 ? rest : qn;
  }

  /** graph.json 객체에서 조회용 색인을 만든다. 입력에 빠진 배열과 attrs는 빈 값으로 채운다. */
  function buildIndex(graph) {
    var g = graph || {};
    var nodes = g.nodes || [];
    var edges = g.edges || [];
    var diagnostics = g.diagnostics || [];
    var byId = new Map();
    var byKind = new Map();
    var outMap = new Map();
    var inMap = new Map();
    var i, n, e, list;

    for (i = 0; i < nodes.length; i++) {
      n = nodes[i];
      if (!n.attrs) n.attrs = {};
      if (n.name == null) n.name = n.id;
      if (n.qualifiedName == null) n.qualifiedName = n.name;
      n.qualifiedName = restoreQualifiedName(n);
      byId.set(n.id, n);
      list = byKind.get(n.kind);
      if (!list) byKind.set(n.kind, list = []);
      list.push(n);
    }
    for (i = 0; i < edges.length; i++) {
      e = edges[i];
      if (!e.attrs) e.attrs = {};
      list = outMap.get(e.from);
      if (!list) outMap.set(e.from, list = []);
      list.push(e);
      list = inMap.get(e.to);
      if (!list) inMap.set(e.to, list = []);
      list.push(e);
    }

    // 컬럼 → 테이블(또는 뷰). CONTAINS가 먼저고, 없으면(정의 없는 테이블의 컬럼) 컬럼 ID의 테이블 이름으로 찾는다.
    var ownerOfColumn = null;
    var columnsByOwner = null;
    function buildColumnMaps() {
      ownerOfColumn = new Map();
      columnsByOwner = new Map();
      (byKind.get('DB_COLUMN') || []).forEach(function (c) {
        var owner = null;
        var incoming = inMap.get(c.id) || EMPTY;
        for (var k = 0; k < incoming.length && !owner; k++) {
          if (incoming[k].kind === 'CONTAINS' && byId.has(incoming[k].from)) owner = incoming[k].from;
        }
        if (!owner && c.id.indexOf(COLUMN_PREFIX) === 0) {
          var rest = c.id.substring(COLUMN_PREFIX.length);
          // 테이블 이름에 점이 있을 수 있어 뒤에서부터 끊어 본다.
          for (var dot = rest.lastIndexOf('.'); dot > 0 && !owner; dot = rest.lastIndexOf('.', dot - 1)) {
            var name = rest.substring(0, dot);
            if (byId.has('db:table:' + name)) owner = 'db:table:' + name;
            else if (byId.has('db:view:' + name)) owner = 'db:view:' + name;
          }
        }
        if (!owner) return;
        ownerOfColumn.set(c.id, owner);
        var cols = columnsByOwner.get(owner);
        if (!cols) columnsByOwner.set(owner, cols = []);
        cols.push(c);
      });
      columnsByOwner.forEach(function (cols) {
        cols.sort(function (a, b) {
          var oa = typeof a.attrs.ordinal === 'number' && a.attrs.ordinal > 0 ? a.attrs.ordinal : Number.MAX_SAFE_INTEGER;
          var ob = typeof b.attrs.ordinal === 'number' && b.attrs.ordinal > 0 ? b.attrs.ordinal : Number.MAX_SAFE_INTEGER;
          return oa - ob || compareText(a.name, b.name);
        });
      });
    }

    // 엣지가 많은 노드(여러 곳에서 불리는 메서드, 구문이 몰린 테이블)만 종류별 목록을 만들어 둔다. 나머지는 그때그때 거른다.
    var outByKind = new Map();
    var inByKind = new Map();
    function ofKind(map, cache, id, kind) {
      var list = map.get(id);
      if (!list) return EMPTY;
      var k;
      if (list.length <= SMALL_EDGE_LIST) {
        var out = null;
        for (k = 0; k < list.length; k++) if (list[k].kind === kind) (out || (out = [])).push(list[k]);
        return out || EMPTY;
      }
      var grouped = cache.get(id);
      if (!grouped) {
        cache.set(id, grouped = new Map());
        for (k = 0; k < list.length; k++) {
          var bucket = grouped.get(list[k].kind);
          if (!bucket) grouped.set(list[k].kind, bucket = []);
          bucket.push(list[k]);
        }
      }
      return grouped.get(kind) || EMPTY;
    }

    var kindCounts = [];
    byKind.forEach(function (v, k) { kindCounts.push({ kind: k, count: v.length }); });
    kindCounts.sort(function (a, b) { return kindRank(a.kind) - kindRank(b.kind) || compareText(a.kind, b.kind); });

    return {
      project: g.project || {},
      schemaVersion: g.schemaVersion,
      nodes: nodes,
      edges: edges,
      diagnostics: diagnostics,
      kindCounts: kindCounts,
      node: function (id) { return byId.get(id); },
      nodesOfKind: function (kind) { return byKind.get(kind) || EMPTY; },
      out: function (id) { return outMap.get(id) || EMPTY; },
      in: function (id) { return inMap.get(id) || EMPTY; },
      /** id에서 나가는 엣지 가운데 종류가 kind인 것. 엣지가 많은 노드는 종류별로 나눠 둔다(큰 그래프에서 같은 노드를 여러 번 훑지 않게). */
      outOf: function (id, kind) { return ofKind(outMap, outByKind, id, kind); },
      /** id로 들어오는 엣지 가운데 종류가 kind인 것. */
      inOf: function (id, kind) { return ofKind(inMap, inByKind, id, kind); },
      /** 테이블·뷰의 컬럼 노드. ordinal 순, 그다음 이름 순. 정의 없는 테이블의 참조된 컬럼도 들어 있다. */
      columnsOf: function (ownerId) {
        if (!columnsByOwner) buildColumnMaps();
        return columnsByOwner.get(ownerId) || EMPTY;
      },
      /** 컬럼이 속한 테이블·뷰의 노드 ID. 찾지 못하면 null. */
      tableOfColumn: function (columnId) {
        if (!ownerOfColumn) buildColumnMaps();
        return ownerOfColumn.get(columnId) || null;
      },
      /** 사람이 읽는 전체 이름. 노드가 없으면 ID를 그대로 준다. */
      label: function (id) {
        var x = byId.get(id);
        if (x && x.kind === 'HTTP_REQUEST') return requestLabel(x);
        return x ? (x.qualifiedName || x.name || id) : id;
      }
    };
  }

  /**
   * 엔드포인트의 표시 이름(표준 산출물과 같은 표기, F-015-R37). 프로퍼티 자리표시자를 치환한 경로(resolvedPath)가 있으면
   * 그것을 쓰고 원문을 괄호로 덧붙인다: `GET /a/sys/user/list (/${adminPath}/sys/user/list)`. 없으면 `METHOD 경로` 그대로다.
   */
  function endpointLabel(node, id) {
    var rest = idRemainder(node ? node.id : id);
    var resolved = node && node.attrs ? node.attrs.resolvedPath : null;
    var space = rest.indexOf(' ');
    if (resolved == null || !String(resolved).trim() || space < 0) return rest;
    var raw = rest.substring(space + 1);
    if (String(resolved) === raw) return rest;
    return rest.substring(0, space + 1) + String(resolved).replace(/[\r\n\t]+/g, ' ').trim() + ' (' + raw + ')';
  }

  function lastSegments(text, sep, count) {
    var parts = text.split(sep);
    return parts.length <= count ? text : parts.slice(parts.length - count).join(sep);
  }

  /** 자기 페이지로 내는 폼(selfSubmit=true)의 표시. 표준 산출물의 NodeLabels.SELF_SUBMIT과 같다. */
  var SELF_SUBMIT = '자기 페이지로 제출';

  /**
   * 요청의 HTTP 메서드 표시. httpMethod=ANY인데 가능한 메서드를 아는 요청(httpMethods=[POST, PUT]: 같은 호출이 조건에 따라 메서드를 바꾼다)은
   * `POST|PUT`으로 적는다.
   */
  function requestMethod(attrs) {
    var a = attrs || {};
    var several = Array.isArray(a.httpMethods) ? a.httpMethods : [];
    if ((a.httpMethod == null || a.httpMethod === '' || String(a.httpMethod).toUpperCase() === 'ANY') && several.length) return several.join('|');
    return a.httpMethod;
  }

  /**
   * 요청(HTTP_REQUEST)의 표시 이름. 메서드가 여럿이면 `POST|PUT /api/x`, 자기 페이지로 내는 폼(ID와 path의 URL 자리가 {self})은
   * `POST (자기 페이지로 제출)`로 적는다({self}를 URL처럼 보이지 않는다). 그 밖에는 노드의 이름 그대로다.
   */
  function requestLabel(node) {
    var a = node.attrs || {};
    var method = requestMethod(a);
    if (a.selfSubmit === true) return (method ? method + ' ' : '') + '(' + SELF_SUBMIT + ')';
    var name = String(node.name || node.qualifiedName || node.id);
    if (a.httpMethod && method !== a.httpMethod && name.indexOf(a.httpMethod + ' ') === 0) return method + name.substring(String(a.httpMethod).length);
    return name;
  }

  var UNRESOLVED_ENDPOINT_PREFIX = 'unresolved:http-endpoint:';
  var SELF_SUBMIT_SUFFIX = ' (selfSubmit)';
  /** 후보가 여럿인 자기 페이지 제출의 묶음 줄 이름(표준 산출물의 NodeLabels.SELF_SUBMIT_CANDIDATES). */
  var SELF_SUBMIT_CANDIDATES = '후보 엔드포인트';
  var MAX_SELF_SUBMIT_PATHS = 3;

  /**
   * id가 "맞는 엔드포인트를 찾지 못한 action 없는 폼"의 미해결 대상(`unresolved:http-endpoint:<METHOD> (selfSubmit)`. 링커가 남긴다)이면
   * 그 메서드, 아니면 null(표준 산출물의 NodeLabels.unresolvedSelfSubmitMethod).
   */
  function unresolvedSelfSubmitMethod(id) {
    var text = String(id == null ? '' : id);
    if (text.indexOf(UNRESOLVED_ENDPOINT_PREFIX) !== 0 || text.length <= SELF_SUBMIT_SUFFIX.length
      || text.lastIndexOf(SELF_SUBMIT_SUFFIX) !== text.length - SELF_SUBMIT_SUFFIX.length) return null;
    var method = text.substring(UNRESOLVED_ENDPOINT_PREFIX.length, text.length - SELF_SUBMIT_SUFFIX.length).trim();
    return !method || method.indexOf(' ') >= 0 || method.indexOf('/') >= 0 ? null : method;
  }

  /**
   * 요청 requestId에서 본 미해결 selfSubmit 대상의 이름(표준 산출물의 NodeLabels.unresolvedSelfSubmitLabel, F-015-R70).
   * 그 요청을 낸 화면(REQUESTS의 출발인 JSP_PAGE)을 그리는 엔드포인트(RENDERS의 출발이 엔드포인트면 그것, 핸들러 메서드면 그 메서드로
   * ROUTES_TO하는 엔드포인트)의 경로를 알면 찾던 URL로 적고, 모르면 그 사실을 적는다. 미해결 selfSubmit 대상이 아니면 null.
   */
  function unresolvedSelfSubmitLabel(index, unresolvedId, requestId) {
    var method = unresolvedSelfSubmitMethod(unresolvedId);
    if (method == null) return null;
    var paths = new Set();
    var addPath = function (endpointId) {
      var endpoint = index.node(endpointId);
      if (!endpoint || endpoint.kind !== 'HTTP_ENDPOINT') return;
      var a = endpoint.attrs || {};
      var path = a.resolvedPath != null && String(a.resolvedPath).trim() ? a.resolvedPath : a.path;
      if (path == null || !String(path).trim()) {
        var rest = idRemainder(endpointId);
        path = rest.indexOf(' ') < 0 ? rest : rest.substring(rest.indexOf(' ') + 1);
      }
      paths.add(String(path).replace(/\s+/g, ' ').trim());
    };
    if (requestId != null) {
      index.inOf(requestId, 'REQUESTS').forEach(function (requests) {
        var pageNode = index.node(requests.from);
        if (!pageNode || pageNode.kind !== 'JSP_PAGE') return;
        index.inOf(requests.from, 'RENDERS').forEach(function (renders) {
          var from = index.node(renders.from);
          if (from && from.kind === 'HTTP_ENDPOINT') addPath(renders.from);
          else if (from && from.kind === 'JAVA_METHOD') index.inOf(renders.from, 'ROUTES_TO').forEach(function (routes) { addPath(routes.from); });
        });
      });
    }
    var sorted = Array.from(paths).sort(compareText);
    if (!sorted.length) return method + ' (' + SELF_SUBMIT + ': 이 화면을 그리는 엔드포인트를 찾지 못함)';
    var shown = sorted.slice(0, MAX_SELF_SUBMIT_PATHS).join(', ') + (sorted.length > MAX_SELF_SUBMIT_PATHS ? ' 외 ' + (sorted.length - MAX_SELF_SUBMIT_PATHS) + '개' : '');
    return method + ' ' + shown + ' (' + SELF_SUBMIT + ': 이 화면을 그리는 엔드포인트의 경로에 ' + method + ' 매핑 없음)';
  }

  /** 다른 방언 파일에서 남긴 데이터 적재 묶음(dataLoad=true + borrowedFrom)의 표시: `다른 방언 파일: mysql`. 아니면 ''(표준 산출물의 DataLoads.borrowedNote). */
  function borrowedDataLoadNote(node) {
    var a = (node && node.attrs) || {};
    if (!node || node.kind !== 'SQL_STATEMENT' || a.dataLoad !== true || a.borrowedFrom == null || !String(a.borrowedFrom).trim()) return '';
    return '다른 방언 파일: ' + String(a.borrowedFrom).replace(/\s+/g, ' ').trim();
  }

  /** 트리와 표에 쓰는 짧은 이름. 패키지와 긴 경로를 뺀다. */
  function shortLabel(node, id) {
    if (!node) return id || '';
    // 맞는 엔드포인트를 찾지 못한 action 없는 폼: 원문 `<METHOD> (selfSubmit)`을 풀어 쓴다(요청 문맥이 없어 까닭 둘을 함께 적는다).
    var selfMethod = node.kind === 'UNRESOLVED' ? unresolvedSelfSubmitMethod(node.id) : null;
    if (selfMethod != null) return selfMethod + ' (' + SELF_SUBMIT + ': 이 화면을 그리는 엔드포인트를 모르거나 그 경로에 ' + selfMethod + ' 매핑 없음)';
    var qn = node.qualifiedName || node.name || node.id;
    var hash;
    switch (node.kind) {
      case 'JAVA_METHOD':
      case 'JAVA_FIELD':
        hash = qn.indexOf('#');
        if (hash < 0) return qn;
        return qn.substring(qn.lastIndexOf('.', hash) + 1);
      case 'SQL_STATEMENT':
      case 'SQL_FRAGMENT':
        return node.id.indexOf('mybatis:') === 0 ? lastSegments(qn, '.', 2) : qn;
      case 'JS_FUNCTION':
        hash = qn.indexOf('#');
        if (hash < 0) return qn;
        return qn.substring(qn.lastIndexOf('/', hash) + 1);
      case 'JS_MODULE':
        // JSP 인라인 스크립트의 주인 모듈은 경로가 JSP와 같다(계약 design 3.3). 이름으로 구분되게 한다.
        return lastSegments(qn, '/', 2) + (node.attrs.inline === true ? ' (인라인 스크립트)' : '');
      case 'JSP_PAGE':
      case 'FILE':
        return lastSegments(qn, '/', 2);
      case 'DB_COLUMN':
      case 'DB_ROUTINE':
      case 'BATCH_STEP':
      case 'JAVA_PACKAGE':
        return qn;
      case 'HTTP_ENDPOINT':
        return node.attrs && node.attrs.resolvedPath ? endpointLabel(node, node.id) : node.name || qn;
      case 'HTTP_REQUEST':
        return requestLabel(node);
      default:
        return node.name || qn;
    }
  }

  /** "파일:시작줄" 또는 "파일:시작줄-끝줄". 줄을 모르면 파일만. 위치가 없으면 null. */
  function formatLocation(loc) {
    if (!loc || !loc.file) return null;
    if (!loc.startLine || loc.startLine <= 0) return loc.file;
    if (loc.endLine && loc.endLine > loc.startLine) return loc.file + ':' + loc.startLine + '-' + loc.endLine;
    return loc.file + ':' + loc.startLine;
  }

  function formatValue(v) {
    if (v == null) return '';
    if (Array.isArray(v)) return v.join(', ');
    return String(v);
  }

  /**
   * 고정 컬럼을 `GROUP_CD='ORDER_STATUS'`처럼 적는다. values는 `COL=값` 목록(JOINS의 leftFixedValues, REFERENCES의 fixedValues)이다.
   * 바인드·변수(`COL=?`)와 숫자는 따옴표 없이, 한 컬럼에 값이 여럿이면 `COL in ('A', 'B')`, 값을 모르면 `COL=상수`로 적는다. 없으면 ''.
   * 원소는 첫 `=`에서만 자른다: 값이 빈 문자열이면(`USE_YN=`) `USE_YN=''`, 값에 `=`가 있어도(`EXPR=A=B`) 값은 `A=B` 그대로다.
   */
  function fixedText(columns, values) {
    var order = [];
    var byColumn = new Map();
    var put = function (column, value) {
      if (!byColumn.has(column)) { byColumn.set(column, []); order.push(column); }
      if (value != null && byColumn.get(column).indexOf(value) < 0) byColumn.get(column).push(value);
    };
    (Array.isArray(columns) ? columns : []).forEach(function (c) { put(String(c), null); });
    (Array.isArray(values) ? values : []).forEach(function (v) {
      var text = String(v);
      var eq = text.indexOf('=');
      if (eq > 0) put(text.substring(0, eq), text.substring(eq + 1));
    });
    var quote = function (value) { return value === '?' || /^-?\d+(\.\d+)?$/.test(value) ? value : "'" + value + "'"; };
    return order.map(function (column) {
      var list = byColumn.get(column);
      if (!list.length) return column + '=상수';
      return list.length === 1 ? column + '=' + quote(list[0]) : column + ' in (' + list.map(quote).join(', ') + ')';
    }).join(', ');
  }

  /**
   * 노드 ID는 아니지만 그래프의 노드를 가리키는 속성 값을 그 노드로 잇는다. 찾지 못하면 null.
   *  typeRefs(루틴·패키지): `%TYPE`·`%ROWTYPE`이 가리키는 `테이블.컬럼` 또는 `테이블`.
   *  redirects·forwards(메서드): 서블릿 API로 넘기는 경로. 그 경로의 엔드포인트가 하나뿐이면 그 엔드포인트.
   */
  function referencedNode(index, key, text) {
    if (key === 'typeRefs') {
      var candidates = ['db:column:' + text, 'db:table:' + text, 'db:view:' + text];
      for (var i = 0; i < candidates.length; i++) if (index.node(candidates[i])) return candidates[i];
      return null;
    }
    if (key === 'redirects' || key === 'forwards') {
      var hits = index.nodesOfKind('HTTP_ENDPOINT').filter(function (n) { return n.attrs.path === text; });
      return hits.length === 1 ? hits[0].id : null;
    }
    // 타입의 바이너리 이름을 담는 속성: 그 타입 노드가 그래프에 있으면 그리로 간다.
    if (Object.prototype.hasOwnProperty.call(TYPE_NAME_ATTRS, key)) {
      return index.node('java:type:' + text) ? 'java:type:' + text : null;
    }
    return null;
  }

  // 값이 타입의 바이너리 이름인 속성(계약 design 3.5): 호출의 수신자 타입, 리플렉션 호출이 거치는 하위 타입, 물려받았다고 추론한 상위 타입,
  // 상위 타입에 넘긴 타입 인자, 구문의 namespace(매퍼 인터페이스).
  var TYPE_NAME_ATTRS = { receiverType: 1, receiverTypes: 1, viaTypes: 1, inheritedFrom: 1, typeArguments: 1, namespace: 1, mapperInterface: 1, 'class': 1, beanType: 1 };
  // 값 하나가 길어서(파일 위치 + 클래스 이름 등) 한 줄에 하나씩 보여야 읽히는 목록.
  // tilesDefinitions·tilesAttributes: tiles 설정 파일(FILE)의 `이름=경로` 목록. viewDeclarations: 뷰 컨트롤러 엔드포인트의 `<파일>:<줄>:<열> <view-name>`.
  var MULTILINE_ATTRS = { otherDefinitions: 1, tilesDefinitions: 1, tilesAttributes: 1, viewDeclarations: 1 };

  /**
   * 마무리 단계가 만든 미해결 노드의 분류: 엣지가 가리키던 ID의 접두사(expectedPrefix)에서 유도한다(core의 Ids.danglingCategory와 같은 표, 계약 design 3.5).
   * `category`가 없는 전의 그래프를 읽을 때 쓴다.
   */
  function danglingCategory(expectedPrefix) {
    if (expectedPrefix == null) return null;
    var prefix = String(expectedPrefix);
    return Object.prototype.hasOwnProperty.call(DANGLING_CATEGORIES, prefix) ? DANGLING_CATEGORIES[prefix] : prefix.replace(/:/g, '-');
  }

  var DANGLING_CATEGORIES = {
    'mybatis:stmt': 'mybatis-statement', 'java:method': 'java-call', 'java:type': 'java-type', 'db:routine': 'db-routine', 'spring:bean': 'spring-bean-ref',
    'js:module': 'js-module', 'js:function': 'js-call', 'jsp:page': 'jsp-include', 'http:endpoint': 'http-endpoint'
  };

  /**
   * 속성 목록. items는 값(목록이면 원소마다)이고, 값이 그래프의 노드 ID면(또는 노드를 가리키는 이름이면) nodeId에 그 ID가 들어 있다.
   * multiline은 값 안에 쉼표가 있어 한 줄에 하나씩 보여야 읽히는 목록이다(테이블의 indexes: `IX1(A,B DESC)`).
   */
  function attrList(attrs, index) {
    var out = [];
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      var values = v == null ? [] : Array.isArray(v) ? v : [v];
      out.push({
        key: k, value: formatValue(v),
        multiline: values.length > 1 && (Object.prototype.hasOwnProperty.call(MULTILINE_ATTRS, k) || values.some(function (x) { return String(x).indexOf(',') >= 0; })),
        items: values.map(function (x) {
          var text = String(x);
          var nodeId = null;
          if (index && typeof x === 'string') nodeId = x.indexOf(':') > 0 && index.node(x) ? x : referencedNode(index, k, text);
          return { text: text, nodeId: nodeId };
        })
      });
    });
    return out;
  }

  /** 노드에 붙일 꼬리표. [{ text, title, warn }] 계약(design 3.5)의 boolean 표시와 미해결 분류를 사람이 읽는 말로 바꾼다. */
  function nodeTags(node) {
    var a = (node && node.attrs) || {};
    var tags = [];
    function tag(text, title, warn) { tags.push({ text: text, title: title, warn: !!warn }); }
    if (a.external === true) {
      tag(node.kind === 'HTTP_REQUEST' ? '외부 호스트' : '외부 라이브러리',
        node.kind === 'HTTP_REQUEST' ? 'external=true: 프로젝트 밖으로 가는 요청이다' : 'external=true: 소스가 없는 타입이나 그 메서드다');
    } else if (a.declared === false) {
      tag('정의 없음', 'declared=false: 참조만 있고 정의를 찾지 못했다', true);
    }
    if (a.signatureUnknown === true) tag('시그니처 모름', 'signatureUnknown=true: 매개변수를 알 수 없는 (?) 메서드다');
    if (a.lombok) tag('Lombok @' + a.lombok, 'synthetic=true, lombok=' + a.lombok + ': 소스에는 없고 Lombok이 만들 멤버다');
    else if (a.synthetic === true) tag('합성', 'synthetic=true: 소스에 없는 멤버다 (기본 생성자, 초기화 코드, record·enum의 자동 멤버)');
    if (a.anonymous === true) tag('익명 타입', 'anonymous=true');
    if (a.local === true) tag('로컬 타입', 'local=true: 메서드 안에 선언한 타입이다');
    if (node && node.kind === 'UNRESOLVED') {
      // 마무리 단계가 만든 노드(expectedPrefix가 있다)도 분류로 적는다. category가 없는 전의 그래프는 접두사에서 유도한다.
      var category = a.category || danglingCategory(a.expectedPrefix);
      if (category) {
        tag('분류 ' + category, a.expectedPrefix
          ? '미해결 분류 (category). 엣지가 ' + a.expectedPrefix + ': 으로 시작하는 ID를 가리켰지만 그 노드를 아무도 만들지 않았다 (expectedPrefix)'
          : '미해결 분류 (category)', true);
      }
    }
    if (node && node.kind === 'HTTP_ENDPOINT' && Array.isArray(a.placeholders) && a.placeholders.length && !a.resolvedPath) {
      tag('자리표시자 미치환', 'placeholders는 있고 resolvedPath는 없다: 경로의 프로퍼티 자리표시자(${…})의 값을 정하지 못했다(값이 없거나, 파일·프로파일마다 다르거나, 모듈마다 다르다)', true);
    }
    if (node && node.kind === 'SQL_STATEMENT' && a.dataLoad === true) {
      tag('데이터 적재', 'dataLoad=true: 애플리케이션이 실행하는 구문이 아니라 SQL 스크립트의 리터럴 INSERT 여러 건을 (파일, 테이블) 단위로 묶은 노드다. rowCount가 묶은 행 수다. 콜트리와 CRUD에서 기본으로 뺀다');
    }
    if (a.dialectVariant) tag('방언 ' + a.dialectVariant, 'dialectVariant: 방언별 매퍼 파일 여러 벌 가운데 이 정의를 가져온 파일의 방언이다(옵션 mybatis.dialectVariant)');
    if (borrowedDataLoadNote(node)) {
      tag(borrowedDataLoadNote(node), 'dataLoad=true, borrowedFrom=' + a.borrowedFrom + ': 고른 방언의 파일에 이 테이블의 적재가 없어 다른 방언 파일의 묶음을 남겼다', true);
    } else if (node && node.kind === 'SQL_STATEMENT' && a.borrowedFrom) {
      tag('다른 방언 ' + a.borrowedFrom + '에서 보충', 'borrowedFrom=' + a.borrowedFrom + ': 선택한 방언의 매퍼 파일에 이 구문이 없어 다른 방언 파일의 정의를 썼다. SQL이 선택한 방언의 것이 아닐 수 있다', true);
    }
    if (a.skipped === 'vendor') tag('벤더 (분석 안 함)', 'skipped=vendor: 벤더(서드파티) 파일로 판정해 내용을 분석하지 않았다. 노드만 있다(옵션 web.skipVendor, web.vendorPatterns)');
    else if (a.skipped === 'minified') tag('압축 파일 (분석 안 함)', 'skipped=minified: 압축된 스크립트라 내용을 분석하지 않았다');
    else if (a.skipped) tag('분석 안 함 (' + a.skipped + ')', 'skipped=' + a.skipped);
    else if (a.vendor === true) tag('벤더', 'vendor=true: 벤더로 판정했지만 web.skipVendor=false라 분석한 모듈이다');
    if (node && node.kind === 'HTTP_REQUEST' && a.api === 'option') {
      tag('옵션 객체의 URL', 'api=option: 요청 API가 아닌 호출에 넘긴 옵션 객체에서 읽은 URL이다(optionKey). 그 호출이 정말 요청을 보내는지는 모른다. 이 요청으로 가는 REQUESTS는 늘 추론이다');
      if (a.optionKey === 'arg') {
        tag('호출 인자의 URL', 'optionKey=arg: 옵션 객체의 키가 아니라 호출의 문자열 인자에서 읽은 URL이다(예: jBox.open("get:/path")의 첫 인자). 그 호출이 정말 그 경로를 요청하는지는 모른다');
      }
    }
    if (node && node.kind === 'HTTP_REQUEST' && a.api === 'tag') {
      tag('태그 속성의 URL', 'api=tag: JSP 커스텀 태그의 URL 속성(url, href, action 등)에서 읽은 요청이다. 그 태그가 실제로 어떤 요청을 만드는지는 태그 구현에 달렸다');
    }
    if (node && node.kind === 'HTTP_REQUEST' && a.selfSubmit === true) {
      tag(SELF_SUBMIT, 'selfSubmit=true: action이 없는 폼이다. 이 페이지를 그린 주소로 제출한다(ID와 path의 {self}는 URL이 아니라 그 자리 표시다). 링커가 이 페이지를 그리는 핸들러의 엔드포인트와 맞춘다(추론)');
    }
    if (node && node.kind === 'HTTP_REQUEST' && Array.isArray(a.httpMethods) && a.httpMethods.length > 1) {
      tag('메서드 ' + a.httpMethods.join('|'), 'httpMethods: 같은 호출이 조건에 따라 메서드를 바꾼다. httpMethod는 ANY로 남고 가능한 메서드가 여기에 있다');
    }
    if (node && node.kind === 'HTTP_REQUEST' && a.clientRoute === true) {
      tag('화면 내 이동', 'clientRoute=true: 서버로 가는 요청이 아니라 화면 안의 이동(React Router의 navigate·Link 등 클라이언트 라우트)이다. 엔드포인트 대상이 아니라서 맞는 엔드포인트가 없어도 미해결로 세지 않는다');
    }
    if (node && node.kind === 'JAVA_TYPE' && a.stereotype === 'filter') {
      tag('필터', 'stereotype=filter: 서블릿 필터다. 요청 처리의 진입점이다(doFilter 등에서 시작하는 콜트리를 볼 수 있다). 빈이라는 뜻은 아니다');
    }
    if (node && node.kind === 'JAVA_TYPE' && a.stereotype === 'interceptor') {
      tag('인터셉터', 'stereotype=interceptor: Spring MVC 핸들러 인터셉터다. 요청 처리의 진입점이다(preHandle 등에서 시작하는 콜트리를 볼 수 있다). 빈이라는 뜻은 아니다');
    }
    if (node && node.kind === 'HTTP_REQUEST') {
      if (a.dynamic === true) tag('동적 URL', 'dynamic=true: URL에 정적으로 알 수 없는 조각({*})이 있다');
      if (a.relative === true) tag('상대 URL', 'relative=true: /로 시작하지 않는 URL이다');
    } else if (a.dynamic === true) {
      tag('동적 SQL', 'dynamic=true: 동적 태그나 ${} 치환이 있다');
    }
    if (node && node.kind === 'SQL_STATEMENT' && a.sequence === true) {
      tag('시퀀스 참조 식', 'sequence=true: SQL 구문이 아니라 루틴 본문의 식(대입식, 조건식, 호출 인자) 안에서 시퀀스를 참조한 자리다. 이 노드에서 시퀀스로 READS가 나간다');
    }
    if (a.inline === true) tag('인라인 스크립트', 'inline=true: JSP 안의 스크립트를 대표하는 모듈이다');
    return tags;
  }

  function joinParts(parts) {
    return parts.filter(function (p) { return p != null && p !== ''; }).join(' · ');
  }

  /** 요청을 어디에서 읽었는지(HTTP_REQUEST의 api)를 읽는 말로. 따로 풀어 쓸 것이 없는 값(link, form, fetch 등)은 그대로 적는다. */
  function requestApiText(api) {
    if (api === 'tag') return '커스텀 태그의 속성에서 읽은 URL (api=tag)';
    if (api === 'route') return '클라이언트 라우터 호출 (api=route)';
    if (api === 'img') return '이미지 태그의 주소 (api=img)';
    if (api === 'frame') return '프레임의 주소 (api=frame)';
    return api;
  }

  /**
   * 방향을 정하지 못한 추론 관계(directionKnown=false)의 까닭. 링커가 방향을 정했다가 거둔 것이면 그 까닭(declaredOpposite: 선언된 FK와 반대 방향,
   * pseudoKey: 부모로 본 쪽의 키가 유일하지 않음), 그 밖에는 어느 쪽에도 PK·UNIQUE 정보가 없어 정하지 못한 것이다.
   */
  function directionReason(attrs) {
    var a = attrs || {};
    return a.declaredOpposite === true ? '선언된 FK와 반대' : a.pseudoKey === true ? '유일하지 않은 키' : '키 정보 없음';
  }

  /**
   * DDL에 없는데 SQL이 참조하는 컬럼: 정의가 있는 테이블·뷰의 declared=false 컬럼(표준 산출물의 UndeclaredColumns와 같다, F-013-R19).
   * ERD 본체에는 넣지 않고 테이블 상세와 진단의 "컬럼 불일치" 보기에서 따로 알린다. 정의가 없는 테이블이면 빈 배열이다.
   * [{ id, name, statementIds(ID 순) }] 컬럼 이름순.
   */
  function undeclaredColumnsOf(index, tableId) {
    var t = index.node(tableId);
    if (!t || (t.kind !== 'DB_TABLE' && t.kind !== 'DB_VIEW') || t.attrs.declared === false) return [];
    var prefix = idRemainder(tableId) + '.';
    var out = [];
    index.columnsOf(tableId).forEach(function (c) {
      if (c.attrs.declared !== false) return;
      var statements = new Set();
      index.in(c.id).forEach(function (e) { if (e.kind === 'READS' || e.kind === 'WRITES') statements.add(e.from); });
      var rest = idRemainder(c.id);
      out.push({ id: c.id, name: rest.indexOf(prefix) === 0 ? rest.substring(prefix.length) : rest.substring(rest.lastIndexOf('.') + 1),
        statementIds: Array.from(statements).sort(compareText) });
    });
    return out.sort(function (a, b) { return compareText(a.name, b.name); });
  }

  /** 구문 이름(표준 산출물의 표기): 매퍼 구문은 `<매퍼 단순 이름>.<구문 ID>`, 그 밖에는 ID의 나머지. */
  function statementName(index, id) {
    var rest = idRemainder(id);
    if (String(id).indexOf('mybatis:stmt:') !== 0) return rest;
    var node = index.node(id);
    var namespace = node ? node.attrs.namespace : null;
    var statementId = node ? node.attrs.statementId : null;
    if (namespace == null || !String(namespace).trim() || statementId == null || !String(statementId).trim()) {
      var dot = rest.lastIndexOf('.');
      if (dot < 0) return rest;
      namespace = rest.substring(0, dot);
      statementId = rest.substring(dot + 1);
    }
    namespace = String(namespace);
    return namespace.substring(namespace.lastIndexOf('.') + 1) + '.' + statementId;
  }

  /** 종류별 요약 한 줄. 속성 표를 읽지 않아도 알아야 할 것(요청의 URL 원문, 루틴의 매개변수, 컬럼의 자료형과 주석 등). 없으면 ''. */
  function nodeSummary(node) {
    var a = node.attrs || {};
    switch (node.kind) {
      case 'HTTP_REQUEST':
        return joinParts([a.url != null && a.url !== '' ? 'URL 원문 ' + a.url : '',
          a.selfSubmit === true ? 'action이 없는 폼: 이 페이지를 그린 주소로 제출' : '',
          Array.isArray(a.httpMethods) && a.httpMethods.length > 1 ? a.httpMethods.join(' 또는 ') + ' (호출에 따라 메서드가 달라진다)' : '', requestApiText(a.api),
          a.optionKey === 'arg' ? '호출의 문자열 인자에서 읽은 URL (optionKey=arg)' : a.optionKey ? '옵션 키 ' + a.optionKey : '', a.callee ? '호출 ' + a.callee : '']);
      case 'HTTP_ENDPOINT': {
        var placeholders = Array.isArray(a.placeholders) && a.placeholders.length
          ? '자리표시자 ' + a.placeholders.map(function (p) { return '${' + p + '}'; }).join(', ') : '';
        if (a.resolvedPath) return joinParts(['치환한 경로 ' + a.resolvedPath, placeholders]);
        return placeholders ? placeholders + ' (값을 정하지 못함)' : '';
      }
      case 'DB_ROUTINE': {
        var typeRefs = Array.isArray(a.typeRefs) && a.typeRefs.length ? '%TYPE 참조 ' + a.typeRefs.join(', ') : '';
        if (a.routineType === 'TRIGGER') return joinParts([['TRIGGER', a.triggerEvent].filter(Boolean).join(' '), typeRefs]);
        return joinParts([(a.routineType || '') + (Array.isArray(a.parameters) ? ' (' + a.parameters.join(', ') + ')' : '') + (a.returnType ? ' → ' + a.returnType : ''), typeRefs]);
      }
      case 'DB_PACKAGE':
        return Array.isArray(a.typeRefs) && a.typeRefs.length ? '%TYPE 참조 ' + a.typeRefs.join(', ') : '';
      case 'DB_COLUMN':
        return joinParts([a.dataType, a.primaryKey === true ? 'PK' : '', a.unique === true ? 'UNIQUE' : '', a.nullable === false ? 'NOT NULL' : '', a.comment]);
      case 'DB_TABLE':
      case 'DB_VIEW':
        return joinParts([a.comment, Array.isArray(a.primaryKey) && a.primaryKey.length ? 'PK ' + a.primaryKey.join(', ') : '',
          Array.isArray(a.indexes) && a.indexes.length ? '인덱스 ' + a.indexes.length + '개' : '']);
      case 'JAVA_METHOD': {
        // signature에는 반환형이 들어 있다(`find(Long): OrderVO`). 없을 때만 returnType을 붙인다.
        var signature = a.signature ? String(a.signature) : '';
        if (signature && a.returnType && signature.indexOf('): ') < 0) signature += ': ' + a.returnType;
        return joinParts([signature,
          Array.isArray(a.redirects) && a.redirects.length ? 'redirect → ' + a.redirects.join(', ') : '',
          Array.isArray(a.forwards) && a.forwards.length ? 'forward → ' + a.forwards.join(', ') : '']);
      }
      case 'JAVA_FIELD':
        return joinParts([a.type, a.constantValue != null ? '= ' + a.constantValue : '']);
      case 'JAVA_TYPE':
        return joinParts([a.typeKind, a.stereotype, Array.isArray(a.typeParameters) && a.typeParameters.length ? '<' + a.typeParameters.join(', ') + '>' : '']);
      case 'SQL_STATEMENT':
        return joinParts([a.statementType, a.tag && a.tag !== String(a.statementType || '').toLowerCase() ? '<' + a.tag + '>' : '',
          a.sequence === true ? 'SQL 구문이 아닌 식 안의 시퀀스 참조' : '',
          a.dataLoad === true ? '데이터 적재 ' + (typeof a.rowCount === 'number' ? a.rowCount + '행' : '(행 수 모름)') : '',
          borrowedDataLoadNote(node) ? borrowedDataLoadNote(node) : a.borrowedFrom ? '다른 방언 ' + a.borrowedFrom + '의 매퍼 파일에서 보충' : '']);
      case 'BATCH_STEP':
        return joinParts([a.stepType, a.chunkSize != null ? 'chunk ' + a.chunkSize : a.commitInterval != null ? 'commit-interval ' + a.commitInterval : '']);
      case 'SPRING_BEAN':
        return joinParts([a.class || a.beanType, a.scope,
          Array.isArray(a.otherDefinitions) && a.otherDefinitions.length ? '같은 이름의 다른 정의 ' + a.otherDefinitions.length + '개' : '']);
      case 'JS_FUNCTION':
        return joinParts([a.kind, a.async === true ? 'async' : '', a.exported === true ? 'export' : '', a.component === true ? 'React 컴포넌트' : '']);
      case 'DB_SYNONYM':
        return a.target ? '→ ' + a.target : '';
      default:
        return '';
    }
  }

  /** 링커가 요청(또는 redirect·forward 경로)과 엔드포인트를 어떻게 맞췄는지(MATCHES의 attrs, 계약 design 3.5). 낱말의 배열. */
  function matchNotes(a) {
    var notes = [];
    if (a.strippedPrefix != null) notes.push('요청의 앞부분 ' + a.strippedPrefix + '를 떼고 맞춤');
    if (a.strippedPlaceholder != null) notes.push('엔드포인트의 ' + a.strippedPlaceholder + '를 떼고 맞춤');
    if (a.dynamicSegment === true) notes.push('요청의 {*}가 고정 조각과 맞음');
    if (a.trailingSlash === true) notes.push('끝의 / 차이 무시');
    if (a.crossModule === true) notes.push('다른 모듈의 핸들러');
    if (a.relativeBase != null) notes.push('상대 URL 기준 ' + a.relativeBase);
    else if (a.relative === true) notes.push('상대 URL');
    if (a.selfSubmit === true) notes.push('자기 페이지로 제출: 이 페이지를 그리는 핸들러의 엔드포인트');
    if (a.module != null) notes.push('요청의 모듈 ' + (a.module === '' ? '.' : a.module));
    if (a.ambiguous === true) notes.push('후보 여럿');
    return notes;
  }

  function shortTable(index, id) {
    var n = index.node(id);
    return n ? n.name : idRemainder(id);
  }

  /**
   * 엣지의 속성을 사람이 읽는 한 줄로 요약한다. 요약할 것이 없으면 ''.
   *  REFERENCES: `ORDER_STATUS → CODE (GROUP_CD='ORDER_STATUS') · N:1 · 추론 1건`
   *  JOINS: `TB_CODE.CODE = TB_ORDER.ORDER_STATUS · TB_CODE 고정 GROUP_CD='ORDER_STATUS'`
   *  CALLS: 미해결 java-call의 인자 수, props로 받은 함수 호출(viaProp), redirect·forward의 경로와 출처
   */
  function edgeSummary(index, e) {
    var a = e.attrs || {};
    var list = function (v) { return Array.isArray(v) ? v : v == null ? [] : [v]; };
    var parts = [];
    if (e.kind === 'REFERENCES') {
      var inferred = (a.source || (e.confidence === 'INFERRED' ? 'INFERRED' : 'DDL')) === 'INFERRED';
      var unknownDirection = inferred && a.directionKnown === false;
      var columns = list(a.columns).join(', ');
      var refColumns = list(a.refColumns).join(', ');
      var fixed = fixedText(a.fixedColumns, a.fixedValues);
      var head = columns + (refColumns ? (unknownDirection ? ' = ' : ' → ') + refColumns : '');
      parts.push(head + (fixed ? ' (' + fixed + ')' : ''));
      // 방향을 모르는 까닭: 선언된 FK와 반대(declaredOpposite), 유일하지 않은 키(pseudoKey), 그 밖에는 키 정보 없음.
      if (unknownDirection) parts.push('방향 미상: ' + directionReason(a));
      else if (a.cardinality && a.cardinality !== 'unknown') parts.push(String(a.cardinality));
      // 방향만 알고 카디널리티는 정하지 못한 추론 관계(ERD의 '카디널리티 모름', F-013-R27).
      else if (inferred && a.cardinality === 'unknown') parts.push((a.directionFrom === 'DDL' ? '방향은 DDL로 확인, ' : '') + '카디널리티 모름');
      // 현재 정의에 없는 upgrade 외래 키: upgrade 스크립트의 ALTER TABLE에만 있고 지금의 CREATE TABLE에는 없다.
      // 실제 그래프에서는 source=DDL인 채 엣지 신뢰도가 INFERRED이고 note("현재 정의에 없음")가 붙는다.
      var staleUpgrade = a.fromUpgrade === true && (inferred || e.confidence === 'INFERRED' || a.note != null);
      if (staleUpgrade) parts.push((a.constraint ? a.constraint + ' ' : '') + '(upgrade 스크립트, ' + (a.note != null ? a.note : '현재 정의에 없음') + ')');
      else if (inferred) parts.push('추론' + (a.evidenceCount != null ? ' ' + a.evidenceCount + '건' : ''));
      // upgrade·patch 스크립트의 ALTER TABLE에서 온 외래 키: 현재 스키마에 반영됐는지는 스크립트를 봐야 안다.
      else parts.push('DDL' + (a.constraint ? ' ' + a.constraint : '') + (a.fromUpgrade === true ? ' (upgrade 스크립트)' : ''));
      if (a.onDelete) parts.push('ON DELETE ' + a.onDelete);
      if (a.typeMismatch === true) parts.push('자료형 불일치');
      return joinParts(parts);
    }
    if (e.kind === 'JOINS') {
      var left = shortTable(index, e.from);
      var right = shortTable(index, e.to);
      var leftColumns = list(a.leftColumns);
      var rightColumns = list(a.rightColumns);
      var pairs = [];
      for (var i = 0; i < Math.max(leftColumns.length, rightColumns.length); i++) {
        pairs.push(left + '.' + (leftColumns[i] || '?') + ' = ' + right + '.' + (rightColumns[i] || '?'));
      }
      // 형 변환·정리 함수(TO_CHAR, TRIM 등)로 감싼 컬럼끼리의 등치: 어느 쪽 컬럼을 감쌌는지 적는다.
      var wrappedSide = a.leftWrapped === true && a.rightWrapped === true ? '양쪽' : a.leftWrapped === true ? left + ' 쪽' : a.rightWrapped === true ? right + ' 쪽' : '';
      var wrapped = a.wrapped === true || wrappedSide ? ' (함수 적용' + (wrappedSide ? ': ' + wrappedSide : '') + ')' : '';
      parts.push(pairs.join(' AND ') + wrapped);
      var leftFixed = fixedText(a.leftFixedColumns, a.leftFixedValues);
      var rightFixed = fixedText(a.rightFixedColumns, a.rightFixedValues);
      if (leftFixed) parts.push(left + ' 고정 ' + leftFixed);
      if (rightFixed) parts.push(right + ' 고정 ' + rightFixed);
      return joinParts(parts);
    }
    if (e.kind === 'CALLS') {
      if (String(e.to).indexOf('unresolved:java-call:') === 0) {
        return joinParts(['미해결 호출', typeof a.argCount === 'number' ? '인자 ' + a.argCount + '개' : '인자 수 모름']);
      }
      if (a.viaProp != null) return 'prop ' + a.viaProp + '로 받은 함수 호출';
      if (a.callType === 'redirect' || a.callType === 'forward') {
        return joinParts([a.callType + (a.path ? ' ' + a.path : ''),
          a.viewName ? '뷰 이름 ' + a.viewName : a.path ? '메서드가 넘긴 경로(' + a.callType + 's)' : ''].concat(matchNotes(a)));
      }
      if (a.callType === 'reflection') {
        var targets = list(a.receiverTypes).length ? list(a.receiverTypes) : list(a.receiverType);
        return joinParts(['리플렉션 호출 (추론)', targets.length ? '대상 객체 ' + targets.join(', ') : '',
          list(a.viaTypes).length ? '거치는 하위 타입 ' + list(a.viaTypes).join(', ') : '', a.ambiguous === true ? '후보 여럿' : '']);
      }
      if (a.callType === 'modelAttribute') return '요청 전 처리: 핸들러보다 먼저 실행되는 @ModelAttribute 메서드 (추론)';
      if (a.inheritedFrom != null) {
        return joinParts(['classpath에 없는 상위 타입 ' + a.inheritedFrom + '에서 물려받은 메서드로 추론', a.ambiguous === true ? '상위 타입 후보 여럿' : '']);
      }
      if (a.resolution === 'global') {
        var page = a.viaPage != null ? index.node(a.viaPage) : null;
        return joinParts(['다른 스크립트의 전역 함수', a.viaPage != null ? '함께 싣는 페이지 ' + (page ? page.name : a.viaPage) : '', a.ambiguous === true ? '후보 여럿' : '']);
      }
      parts = [];
      if (a.loadUnverified === true) parts.push('로드 미확인: 이 스크립트를 함께 싣는지 확인하지 못했다');
      if (a.receiverType != null) parts.push('수신자 타입 ' + simpleTypeName(a.receiverType));
      if (a.lombok === true) parts.push('Lombok 합성 멤버');
      if (a.ambiguous === true) parts.push('후보 여럿');
      return joinParts(parts);
    }
    if (e.kind === 'MATCHES') return joinParts(matchNotes(a));
    if (e.kind === 'EXECUTES' && (a.inherited === true || a.declaredIn != null)) {
      return joinParts(['상속 매퍼 메서드', a.namespace != null ? '하위 매퍼 ' + a.namespace + '의 구문' : '', a.declaredIn != null ? '선언 ' + idRemainder(a.declaredIn) : '']);
    }
    if (e.kind === 'EXECUTES' && a.viaArgument === true) {
      // 구문 ID를 인자로 받는 메서드에 넘긴 문자열로 이은 것. 늘 추론이다.
      return '인자로 넘긴 구문 ID로 이음 (추론)';
    }
    if (e.kind === 'EXECUTES' && a.prefixMatch === true) {
      // 구문 ID가 그 이름으로 시작하는 구문으로 이은 것(전자정부의 `selectList` → `selectList_D` 등). 늘 추론이다.
      return joinParts(['구문 ID 접두 일치', a.statementPrefix != null ? a.statementPrefix + '로 시작하는 구문' : '']);
    }
    if (e.kind === 'RENDERS') {
      var tiles = a.via === 'tiles' ? 'tiles 정의 ' + (a.definition != null ? a.definition : '(이름 없음)') + (a.tilesAttribute === true ? '의 조각' : '의 template(레이아웃)') : '';
      return joinParts([a.viewName != null ? '뷰 이름 ' + a.viewName : '', tiles,
        // 링커가 근거(프로퍼티 값, 모듈 디렉터리 이름)로 후보를 좁힌 것은 "후보"라 부르지 않는다(narrowedBy).
        a.dynamicSegment !== true ? '' : a.narrowedBy === 'property' ? '프로퍼티 값으로 정한 동적 뷰 이름 (추론)'
          : a.narrowedBy === 'moduleName' ? '모듈 이름으로 정한 동적 뷰 이름 (추론)' : '동적 조각이 든 뷰 이름의 후보 (추론)',
        a.suffixMatch === true ? '뷰 이름 접미 일치 (추론)' : '', a.candidate === true ? '후보 여럿 가운데 하나 (추론)' : '', a.ambiguous === true ? '후보 여럿' : '']);
    }
    if (e.kind === 'INCLUDES') {
      if (a.minOf != null) {
        var minName = String(a.minOf).substring(String(a.minOf).lastIndexOf('/') + 1);
        var target = index.node(e.to);
        return '압축본 ' + minName + '를 싣고 ' + (target ? target.name : idRemainder(e.to).substring(idRemainder(e.to).lastIndexOf('/') + 1)) + '로 분석';
      }
      if (a.via === 'sitemesh') {
        return joinParts(['레이아웃: sitemesh 데코레이터',
          a.dynamicDecorator === true ? '동적 데코레이터 이름' + (a.path != null ? ' ' + a.path : '') + ' (추론)' : '', a.ambiguous === true ? '후보 여럿' : '']);
      }
      if (a.via === 'tiles') return '레이아웃: tiles template의 조각';
      return '';
    }
    // XML 빈 설정: 부모 빈(parent=)에서 물려받은 주입·구성 요소
    if ((e.kind === 'INJECTS' || e.kind === 'USES') && a.inheritedFrom != null) return '부모 빈 ' + a.inheritedFrom + '에서 물려받은 설정';
    if ((e.kind === 'EXTENDS' || e.kind === 'IMPLEMENTS') && list(a.typeArguments).length) return '타입 인자 <' + list(a.typeArguments).join(', ') + '>';
    if (e.kind === 'OVERRIDES') {
      if (String(e.to).indexOf('unresolved:java-override:') === 0) {
        return joinParts(['미해결 재정의', typeof a.argCount === 'number' ? '매개변수 ' + a.argCount + '개' : '']);
      }
      if (a.lombok === true) return 'Lombok 합성 메서드가 낀 재정의';
    }
    return '';
  }

  function groupEdges(index, edgeList, otherEnd) {
    var groups = new Map();
    edgeList.forEach(function (e) {
      var otherId = e[otherEnd];
      var g = groups.get(e.kind);
      if (!g) groups.set(e.kind, g = { kind: e.kind, items: [] });
      g.items.push({
        edge: e,
        otherId: otherId,
        other: index.node(otherId) || null,
        label: index.label(otherId),
        confidence: e.confidence || 'RESOLVED',
        location: formatLocation(e.location),
        summary: edgeSummary(index, e),
        attrs: attrList(e.attrs, index)
      });
    });
    var result = Array.from(groups.values());
    result.sort(function (a, b) { return compareText(a.kind, b.kind); });
    result.forEach(function (g) {
      g.items.sort(function (a, b) { return compareText(a.label, b.label) || compareText(a.location || '', b.location || ''); });
    });
    return result;
  }

  // 분석기가 다음 단계(링커)에 넘기려고 남긴 중간 정보. 상세의 속성 표에 쏟지 않고 접어 둔다(F-016-R42).
  var INTERNAL_NODE_ATTRS = { statementIdLiterals: 1, statementIdLiteralLines: 1, formFields: 1 };

  /** 노드 상세 화면에 필요한 값. 없는 ID면 null. attrs는 보여 줄 속성, internalAttrs는 접어 둘 중간 정보다. undeclaredColumns는 테이블·뷰의 "DDL에 없는데 SQL이 참조하는 컬럼"이다([{ id, name, statements: [{ id, label }] }]). */
  function nodeDetail(index, id) {
    var node = index.node(id);
    if (!node) return null;
    return {
      undeclaredColumns: undeclaredColumnsOf(index, id).map(function (c) {
        return { id: c.id, name: c.name, statements: c.statementIds.map(function (sid) { return { id: sid, label: statementName(index, sid) }; }) };
      }),
      node: node,
      declared: node.attrs.declared !== false,
      tags: nodeTags(node),
      summary: nodeSummary(node),
      location: formatLocation(node.location),
      attrs: attrList(node.attrs, index).filter(function (a) { return !Object.prototype.hasOwnProperty.call(INTERNAL_NODE_ATTRS, a.key); }),
      internalAttrs: attrList(node.attrs, index).filter(function (a) { return Object.prototype.hasOwnProperty.call(INTERNAL_NODE_ATTRS, a.key); }),
      outgoing: groupEdges(index, index.out(id), 'to'),
      incoming: groupEdges(index, index.in(id), 'from')
    };
  }

  var api = {
    KIND_ORDER: KIND_ORDER,
    kindMeta: kindMeta,
    kindRank: kindRank,
    kindOfId: kindOfId,
    idPrefix: idPrefix,
    idRemainder: idRemainder,
    simpleTypeName: simpleTypeName,
    simpleTypeText: simpleTypeText,
    methodLabel: methodLabel,
    endpointLabel: endpointLabel,
    lineLocation: lineLocation,
    compareText: compareText,
    buildIndex: buildIndex,
    shortLabel: shortLabel,
    formatLocation: formatLocation,
    formatValue: formatValue,
    fixedText: fixedText,
    danglingCategory: danglingCategory,
    edgeSummary: edgeSummary,
    directionReason: directionReason,
    undeclaredColumnsOf: undeclaredColumnsOf,
    requestLabel: requestLabel, requestMethod: requestMethod, SELF_SUBMIT: SELF_SUBMIT, SELF_SUBMIT_CANDIDATES: SELF_SUBMIT_CANDIDATES,
    unresolvedSelfSubmitMethod: unresolvedSelfSubmitMethod, unresolvedSelfSubmitLabel: unresolvedSelfSubmitLabel, borrowedDataLoadNote: borrowedDataLoadNote,
    statementName: statementName,
    nodeTags: nodeTags,
    nodeSummary: nodeSummary,
    nodeDetail: nodeDetail
  };
  CA.graph = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
